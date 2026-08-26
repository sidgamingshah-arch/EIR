package com.crisil.eir.policy.pool;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pool definitions in force, and the two controls on suspending income through them
 * (FR-608, 03 § 7.4).
 *
 * <p><b>Why this is not {@code PolicyVersionRegistry}.</b> That registry resolves <em>one</em>
 * version per {@code PolicyKind}, latest-wins, and that model is right for a fee rule set or a
 * routing table: there is one of each in force at a time. A bank has many suspension pools in
 * force simultaneously, and every one of them is a {@code POOL_DEFINITION} version — so asking
 * the registry for the pool definition in force on a date returns whichever pool was defined
 * most recently and silently discards the rest. The temporal rule is the same and the grain is
 * not: resolution here is latest-wins <em>within a pool id</em>.
 *
 * <p>Stated because reaching for the registry is the obvious move and it fails quietly. The
 * failure is not an exception or a wrong answer to a narrow question; it is a card book that
 * stops being covered by an approved definition the moment a second pool is defined, which
 * surfaces as PL-1 breaches on exposures whose pool is sitting right there in the registry.
 *
 * <p><b>Overlapping membership is refused at construction, not reported.</b> Two pools in force
 * that both contain an exposure disagree about nothing until they disagree about suspension, and
 * then the answer is whichever was found first — which is not an accounting answer, the same
 * argument {@code PolicyVersionRegistry} makes for refusing two versions with an identical
 * effective date. The check is exact rather than sampled: membership changes only at an
 * {@code effectiveFrom}, so testing every distinct {@code effectiveFrom} in the set tests every
 * date.
 */
public final class SuspensionPools {

    private final Map<String, List<PoolDefinition>> byPoolId;

    private SuspensionPools(Map<String, List<PoolDefinition>> byPoolId) {
        this.byPoolId = byPoolId;
    }

    /**
     * Build the set, refusing the two configurations that have no determinate answer.
     *
     * @throws IllegalArgumentException if two versions of one pool share an effective date, or if
     *                                 two pools in force on some date share a member
     */
    public static SuspensionPools of(Collection<PoolDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, List<PoolDefinition>> grouped = new LinkedHashMap<>();
        for (PoolDefinition definition : definitions) {
            grouped.computeIfAbsent(definition.poolId(), key -> new ArrayList<>()).add(definition);
        }
        for (Map.Entry<String, List<PoolDefinition>> entry : grouped.entrySet()) {
            Set<LocalDate> seen = new LinkedHashSet<>();
            for (PoolDefinition definition : entry.getValue()) {
                LocalDate from = definition.version().effectiveFrom();
                if (!seen.add(from)) {
                    throw new IllegalArgumentException(
                        "pool " + entry.getKey() + " has two versions effective " + from
                            + "; latest-wins cannot choose between them and \"whichever we found"
                            + " first\" is not an accounting answer");
                }
            }
            entry.getValue().sort(
                (left, right) -> right.version().effectiveFrom()
                    .compareTo(left.version().effectiveFrom()));
        }
        SuspensionPools pools = new SuspensionPools(Map.copyOf(grouped));
        pools.refuseOverlappingMembership();
        return pools;
    }

    /** Convenience for tests and fixtures. */
    public static SuspensionPools of(PoolDefinition... definitions) {
        return of(List.of(definitions));
    }

    /**
     * Exhaustive because membership changes only at an effective date. Checking each distinct
     * {@code effectiveFrom} across the whole set therefore checks every date on which the set of
     * pools in force, or any pool's membership, could differ from the day before.
     */
    private void refuseOverlappingMembership() {
        Set<LocalDate> boundaries = new TreeSet<>();
        for (List<PoolDefinition> versions : byPoolId.values()) {
            for (PoolDefinition definition : versions) {
                boundaries.add(definition.version().effectiveFrom());
            }
        }
        for (LocalDate boundary : boundaries) {
            Map<String, String> owner = new HashMap<>();
            for (PoolDefinition pool : inForceOn(boundary)) {
                for (String member : pool.memberExposureIds()) {
                    String existing = owner.putIfAbsent(member, pool.poolId());
                    if (existing != null) {
                        throw new IllegalArgumentException(
                            "exposure " + member + " is in pools " + existing + " and "
                                + pool.poolId() + ", both in force on " + boundary
                                + "; two pools can suspend it differently and nothing says which"
                                + " governs");
                    }
                }
            }
        }
    }

    /** Every pool whose latest version on or before {@code date} is in force. */
    public List<PoolDefinition> inForceOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        List<PoolDefinition> live = new ArrayList<>();
        for (List<PoolDefinition> versions : byPoolId.values()) {
            // Sorted newest-first at construction, so the first hit is latest-wins.
            for (PoolDefinition definition : versions) {
                if (definition.isEffectiveOn(date)) {
                    live.add(definition);
                    break;
                }
            }
        }
        return List.copyOf(live);
    }

    /** The pool covering {@code exposureId} on {@code date}, if an approved one does. */
    public Optional<PoolDefinition> poolFor(String exposureId, LocalDate date) {
        Objects.requireNonNull(exposureId, "exposureId");
        return inForceOn(date).stream()
            .filter(pool -> pool.contains(exposureId))
            .findFirst();
    }

    /** Whether an approved definition authorises suspending {@code exposureId} on {@code date}. */
    public boolean covers(String exposureId, LocalDate date) {
        return poolFor(exposureId, date).isPresent();
    }

    /**
     * Invariant PL-1: every exposure the run suspended is covered by a pool definition in force.
     *
     * <p>The suspended set is handed in rather than derived, and that is the point of the control.
     * Derived from the pools, it would be a tautology — every exposure in a pool is in a pool.
     * What has to be checked is the other direction: the close suspended a set of exposures for
     * its own reasons, and the question is whether policy authorised each one. An exposure
     * suspended at pool level with no pool is the failure, and it looks exactly like a correctly
     * suspended one in every ledger it touches.
     *
     * @param suspendedExposureIds what the run suspended at pool level, from the run
     * @param date                 the date the suspension applies on
     */
    public InvariantResult suspensionIsAuthorised(
        Collection<String> suspendedExposureIds, LocalDate date) {
        Objects.requireNonNull(suspendedExposureIds, "suspendedExposureIds");
        Objects.requireNonNull(date, "date");
        List<String> uncovered = suspendedExposureIds.stream()
            .filter(exposureId -> !covers(exposureId, date))
            .sorted()
            .toList();
        if (uncovered.isEmpty()) {
            return InvariantResult.pass(InvariantId.PL_1,
                suspendedExposureIds.size() + " pool-level suspensions on " + date
                    + ", all under a definition in force");
        }
        return InvariantResult.fail(InvariantId.PL_1,
            uncovered.size() + " of " + suspendedExposureIds.size()
                + " pool-level suspensions on " + date
                + " are not covered by any definition in force: " + uncovered,
            BigDecimal.valueOf(uncovered.size()));
    }

    /**
     * Invariant PL-2 across every pool in force: one result, deviation the count of ineligible
     * pools.
     *
     * <p>One result and not one per pool, because {@link InvariantResult#conjunction} keeps only
     * the first breach's deviation among results sharing an id — so a run with three ineligible
     * pools would publish a deviation of one and read as a single misconfiguration.
     */
    public InvariantResult eligibility(LocalDate date) {
        List<String> ineligible = new ArrayList<>();
        for (PoolDefinition pool : inForceOn(date)) {
            if (!pool.eligibility().satisfied()) {
                ineligible.add(pool.poolId() + " (" + pool.productCode() + ")");
            }
        }
        if (ineligible.isEmpty()) {
            return InvariantResult.pass(InvariantId.PL_2,
                "every pool in force on " + date + " covers a portfolio-managed product");
        }
        return InvariantResult.fail(InvariantId.PL_2,
            ineligible.size() + " pools in force on " + date
                + " cover products where account-level analysis is practical: " + ineligible,
            BigDecimal.valueOf(ineligible.size()));
    }

    /**
     * The tier a pooled exposure is measured at, for the record rather than for a decision.
     *
     * <p>{@link MaterialityTier#TIER_2} is what the tier assignment already gives a card or KCC
     * revolver, and pooling for suspension does not change it. Published here so that a reader
     * comparing the suspension basis against the measurement basis finds them stated in one place
     * and consistent — the divergence this guards against is a book pooled for suspension and
     * measured account by account, or the reverse.
     */
    public MaterialityTier measurementTierOfPooledExposures() {
        return MaterialityTier.TIER_2;
    }

    /** Every pool id known, in insertion order. */
    public Set<String> poolIds() {
        return byPoolId.keySet();
    }

    /** How many pools are in force on {@code date}. */
    public int sizeOn(LocalDate date) {
        return inForceOn(date).size();
    }
}
