package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The legacy book's migration queue, and the two controls on it (FR-908, FR-909, 08 Phase 4).
 *
 * <p><b>LC-1: prioritise by survival, not size.</b> The roadmap states it as a warning —
 * "reconstructing an EIR for a loan maturing in 2029 is wasted effort. Sequence the cohorts
 * expected to remain on the books beyond 31 March 2030 first" — and the reason it needs to be a
 * control rather than advice is that the natural alternative is defensible-looking and wrong.
 * Ordering by contract count, or by balance, puts the biggest cohorts first; some of them run off
 * before the deadline, and the capacity spent on them buys nothing while a surviving cohort waits.
 * The assertion is narrow and checkable: no cohort that survives the deadline is queued behind one
 * that does not.
 *
 * <p><b>DE-1: every deemed cohort has an approved derivation.</b> A deemed rate recognises income
 * on an assumption for the rest of the exposure's life, so preparing one is analysis and measuring
 * a cohort on it is a decision. The derivations are supplied alongside the cohorts because neither
 * type can answer this alone — a cohort does not know whether its derivation was signed, and a
 * derivation does not know whether any cohort is using it.
 *
 * <p>Both are published as <em>one</em> result each with a count, for the reason that keeps
 * recurring: {@link InvariantResult#conjunction} keeps only the first breach's deviation among
 * results sharing an id, so one result per cohort would report the first breach's 1 and read as a
 * single misordered cohort however many there are.
 */
public record LegacyMigrationPlan(
    List<LegacyCohort> cohorts,
    List<DeemedEirDerivation> derivations) {

    public LegacyMigrationPlan {
        cohorts = List.copyOf(Objects.requireNonNull(cohorts, "cohorts"));
        derivations = List.copyOf(Objects.requireNonNull(derivations, "derivations"));
        Map<String, LegacyCohort> byName = new LinkedHashMap<>();
        for (LegacyCohort cohort : cohorts) {
            LegacyCohort existing = byName.putIfAbsent(cohort.cohortName(), cohort);
            if (existing != null) {
                // The schema makes cohort_name unique. Two cohorts of one name would give the
                // plan two answers for every question asked by name, including which method
                // applies and therefore whether a deemed rate is in use.
                throw new IllegalArgumentException(
                    "two cohorts are named " + cohort.cohortName()
                        + "; the name is how a derivation and a plan refer to the same cohort");
            }
        }
    }

    /** A plan with no deemed cohorts, and therefore no derivations to supply. */
    public static LegacyMigrationPlan of(Collection<LegacyCohort> cohorts) {
        return new LegacyMigrationPlan(List.copyOf(cohorts), List.of());
    }

    /** A plan and the derivations backing its deemed cohorts. */
    public static LegacyMigrationPlan of(
        Collection<LegacyCohort> cohorts, Collection<DeemedEirDerivation> derivations) {
        return new LegacyMigrationPlan(List.copyOf(cohorts), List.copyOf(derivations));
    }

    /**
     * Invariant LC-1: no cohort surviving 31 March 2030 is queued behind one that does not.
     *
     * <p>Stated as a pairwise comparison rather than as "the queue is sorted by survival", because
     * the plan is not required to be sorted at all — cohorts at equal priority run together, and
     * two non-surviving cohorts may sit in any order relative to each other. What is forbidden is
     * one specific inversion, and reporting it as an inversion tells a reader what to move.
     */
    public InvariantResult prioritisedBySurvival() {
        int worstNonSurvivorPriority = cohorts.stream()
            .filter(cohort -> !cohort.survivesAcpir50Deadline())
            .mapToInt(LegacyCohort::migrationPriority)
            .min()
            .orElse(Integer.MAX_VALUE);

        List<String> queuedBehind = cohorts.stream()
            .filter(LegacyCohort::survivesAcpir50Deadline)
            .filter(cohort -> cohort.migrationPriority() > worstNonSurvivorPriority)
            .sorted(Comparator.comparingInt(LegacyCohort::migrationPriority))
            .map(cohort -> cohort.cohortName() + " (priority " + cohort.migrationPriority()
                + ", " + cohort.contractCount() + " contracts, runs off "
                + cohort.expectedRunoffDate() + ")")
            .toList();

        if (queuedBehind.isEmpty()) {
            return InvariantResult.pass(InvariantId.LC_1,
                cohorts.size() + " cohorts queued with every survivor of the 2030 deadline ahead"
                    + " of every cohort that runs off before it");
        }
        return InvariantResult.fail(InvariantId.LC_1,
            queuedBehind.size() + " cohorts survive 31 March 2030 and are queued behind a cohort"
                + " that runs off before it, so reconstruction capacity is spent on exposures that"
                + " will have gone: " + queuedBehind,
            BigDecimal.valueOf(queuedBehind.size()));
    }

    /**
     * Invariant DE-1: every cohort on a deemed basis has an approved derivation.
     *
     * <p>Both failure shapes count the same way and are named differently in the detail, because
     * the remedies differ: a cohort with <em>no</em> derivation needs one prepared, and one with an
     * unapproved derivation needs it signed.
     */
    public InvariantResult deemedRatesApproved() {
        List<String> unbacked = new ArrayList<>();
        long deemed = 0;
        for (LegacyCohort cohort : cohorts) {
            if (!cohort.method().restsOnAnAssumption()) {
                continue;
            }
            deemed++;
            Optional<DeemedEirDerivation> derivation = derivationFor(cohort.cohortName());
            if (derivation.isEmpty()) {
                unbacked.add(cohort.cohortName() + " (no derivation)");
            } else if (!derivation.get().isApproved()) {
                unbacked.add(cohort.cohortName() + " (derivation prepared by "
                    + derivation.get().preparedBy() + ", unapproved)");
            }
        }
        if (unbacked.isEmpty()) {
            return InvariantResult.pass(InvariantId.DE_1,
                deemed + " of " + cohorts.size() + " cohorts are on a deemed EIR, all on an"
                    + " approved derivation");
        }
        return InvariantResult.fail(InvariantId.DE_1,
            unbacked.size() + " of " + deemed + " deemed-EIR cohorts would recognise income on an"
                + " unsigned assumption: " + unbacked,
            BigDecimal.valueOf(unbacked.size()));
    }

    /** The derivation governing {@code cohortName}, if one was supplied. */
    public Optional<DeemedEirDerivation> derivationFor(String cohortName) {
        return derivations.stream()
            .filter(derivation -> derivation.appliesToCohort(cohortName))
            .findFirst();
    }

    /**
     * Cohorts queued for full reconstruction that run off before the deadline.
     *
     * <p>Plain data, not an invariant. Spending effort badly is not an accounting breach, and an
     * id on it would put a programme management question in the same list as a figure that does
     * not tie — which is how an invariant dashboard stops being read. Published because it is the
     * roadmap's own sentence and a plan nobody checks will contain some.
     */
    public List<LegacyCohort> wastedReconstructionEffort() {
        return cohorts.stream()
            .filter(LegacyCohort::reconstructionEffortIsWasted)
            .sorted(Comparator.comparingLong(LegacyCohort::contractCount).reversed())
            .toList();
    }

    /** Cohorts that must be on the EIR by 31 March 2030, in queue order. */
    public List<LegacyCohort> survivingCohortsInQueueOrder() {
        return cohorts.stream()
            .filter(LegacyCohort::survivesAcpir50Deadline)
            .sorted(Comparator.comparingInt(LegacyCohort::migrationPriority))
            .toList();
    }

    /** How many exposures must be migrated before the deadline. */
    public long contractsRequiringMigration() {
        return cohorts.stream()
            .filter(LegacyCohort::survivesAcpir50Deadline)
            .mapToLong(LegacyCohort::contractCount)
            .sum();
    }

    /** Both invariants, in the order a reader would work them. */
    public List<InvariantResult> invariants() {
        return List.of(prioritisedBySurvival(), deemedRatesApproved());
    }
}
