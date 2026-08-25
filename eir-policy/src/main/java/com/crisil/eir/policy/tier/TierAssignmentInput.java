package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The contract attributes the materiality gate reads (03 § 10, FR-107).
 *
 * <p>Four things and nothing else: who the counterparty is, how long the exposure was written
 * for, how big it is, and which § 10 attributes it carries. That is the whole of the section's
 * input surface, and keeping it that small is the point — § 10 opens by saying estimation risk
 * distributes by <b>tenor</b>, not by product, so a gate that could see product codes would
 * drift back towards keying on them.
 *
 * <p><b>Two components are nullable and that is a modelled state, not laxity.</b>
 * {@code originalTenorMonths} is genuinely absent on a revolver — a credit card has no
 * contractual maturity (03 § 5) — and is genuinely missing on a badly migrated legacy record.
 * {@code exposureAtOrigination} is irrelevant outside the wholesale segment and often unsourced
 * for the rest. Those two absences must lead to different answers, which is why they are
 * distinguishable here rather than defaulted to zero on the way in: a zero tenor would read as
 * "inside 12 months" and buy the Tier 3 approximation, and a zero exposure would read as "below
 * the Board threshold" and escape Tier 1. Both are the roadmap's Cambodia failure mode — an
 * undocumented shortcut applied to a product that needs a full solver — arriving through a
 * default value rather than through a decision.
 *
 * @param contractId          the contract this assignment is about, for the audit record
 * @param segment             counterparty segment; the only segment key § 10 uses
 * @param originalTenorMonths original contractual tenor in whole months, or null when it is not
 *                            determinable (a revolver, or a record that never carried one)
 * @param exposureAtOrigination size at initial recognition, or null when unknown; compared with
 *                            the Board threshold on its absolute value, see
 *                            {@link #exceedsThreshold}
 * @param features            the § 10 attributes asserted for this contract
 */
public record TierAssignmentInput(
    String contractId,
    TierAssignmentSegment segment,
    Integer originalTenorMonths,
    Money exposureAtOrigination,
    Set<TierAssignmentFeature> features) {

    /** § 10 Tier 2 / Tier 3 boundary: "original tenor > 12 months" against "≤ 12 months". */
    public static final int SHORT_TENOR_MONTHS = 12;

    public TierAssignmentInput {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(features, "features");
        if (contractId.strip().isEmpty()) {
            // Structural, not a data condition: FR-107 requires the basis to be recorded against
            // a contract, and a basis that names no contract is not an audit trail. Compare
            // PolicyVersion, which refuses a blank id for the same reason.
            throw new IllegalArgumentException(
                "contractId must not be blank; a tier basis that names no contract cannot be filed"
                    + " against one");
        }
        features = features.isEmpty()
            ? Collections.unmodifiableSet(EnumSet.noneOf(TierAssignmentFeature.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(features));
    }

    /**
     * A contract with no § 10 attributes — the ordinary case, decided on segment and tenor alone.
     */
    public static TierAssignmentInput of(
        String contractId, TierAssignmentSegment segment, Integer originalTenorMonths) {
        return new TierAssignmentInput(contractId, segment, originalTenorMonths, null,
            EnumSet.noneOf(TierAssignmentFeature.class));
    }

    /** As {@link #of}, with § 10 attributes. */
    public static TierAssignmentInput of(
        String contractId,
        TierAssignmentSegment segment,
        Integer originalTenorMonths,
        TierAssignmentFeature... features) {
        EnumSet<TierAssignmentFeature> asserted = EnumSet.noneOf(TierAssignmentFeature.class);
        // Added one at a time rather than via Set.of(features), which refuses duplicates: a
        // caller listing the same attribute twice is redundant, not wrong, and refusing it would
        // turn a harmless call site into a runtime failure.
        for (TierAssignmentFeature feature : features) {
            asserted.add(Objects.requireNonNull(feature, "feature"));
        }
        return new TierAssignmentInput(contractId, segment, originalTenorMonths, null, asserted);
    }

    /** The same contract with a size attached, for the wholesale Board-threshold limb. */
    public TierAssignmentInput withExposure(Money exposure) {
        return new TierAssignmentInput(contractId, segment, originalTenorMonths, exposure, features);
    }

    /** The same contract with one more § 10 attribute asserted. */
    public TierAssignmentInput with(TierAssignmentFeature feature) {
        Objects.requireNonNull(feature, "feature");
        EnumSet<TierAssignmentFeature> extended = EnumSet.noneOf(TierAssignmentFeature.class);
        extended.addAll(features);
        extended.add(feature);
        return new TierAssignmentInput(
            contractId, segment, originalTenorMonths, exposureAtOrigination, extended);
    }

    /** Whether this contract carries {@code feature}. */
    public boolean has(TierAssignmentFeature feature) {
        return features.contains(feature);
    }

    /**
     * Whether the original tenor is a figure the tenor rule can act on.
     *
     * <p>A null tenor is not determinable, and neither is a non-positive one: an exposure whose
     * recorded maturity is on or before its origination date is a migration defect, and the one
     * thing that must not happen to a migration defect is that it reads as "zero months, inside
     * twelve" and collects the straight-line approximation. Tenor 1 is the shortest tenor this
     * treats as real — a call-money placement repaid the next day is still a positive tenor in
     * months once {@link #tenorMonthsBetween} rounds it up.
     */
    public boolean hasDeterminableTenor() {
        return originalTenorMonths != null && originalTenorMonths > 0;
    }

    /**
     * Whether the exposure exceeds {@code threshold} — the § 10 Tier 1 wholesale limb's test.
     *
     * <p>Three decisions are buried in three lines here, and each of them is a defect avoided.
     *
     * <p><b>Absolute value.</b> Money in this engine is signed from the holder's perspective,
     * outflows negative, so the cash advanced on a loan is negative while the carrying amount it
     * becomes is positive. A threshold is a statement about size. Comparing a signed advance with
     * a positive threshold would put every wholesale exposure below every threshold and empty the
     * Tier 1 row.
     *
     * <p><b>Strictly greater.</b> § 10 says "above a Board threshold". An exposure struck exactly
     * at the threshold is not above it. The distinction is not academic: Board thresholds are set
     * at round numbers and loans are written at round numbers, so the boundary case is common
     * rather than rare.
     *
     * <p><b>False on a currency mismatch, rather than a raised exception.</b>
     * {@link Money#compareTo} refuses to compare across currencies, correctly — a rupee threshold
     * says nothing about a dollar exposure. But an unconvertible exposure is a data condition, and
     * data conditions are reported, not thrown: converting it here would need an FX rate policy
     * with its own version and approval, which this unit does not own and must not invent. The
     * caller is not left with a silent pass either, because a wholesale exposure that fails this
     * test does not fall to Tier 3 — it falls to
     * {@link TierAssignmentRule#TIER_1_DEFAULT_CONTRACT_LEVEL}, which is the conservative answer
     * and is recorded as such.
     */
    public boolean exceedsThreshold(Money threshold) {
        Objects.requireNonNull(threshold, "threshold");
        if (exposureAtOrigination == null
            || !exposureAtOrigination.currency().equals(threshold.currency())) {
            return false;
        }
        return exposureAtOrigination.abs().compareTo(threshold.abs()) > 0;
    }

    /**
     * Original tenor in whole months between two dates, <b>rounded up</b> on any part month.
     *
     * <p>Rounding up rather than truncating, because the 12-month line in § 10 is a permission
     * boundary and not a reporting convention. Truncation reads a 12-month-and-29-day facility as
     * twelve months and hands it the straight-line approximation; rounding up reads it as
     * thirteen and sends it to a pool or a solver. Where the two disagree, the assignment should
     * fall on the side that does more work, because the cost of the extra work is one solve
     * (ADR-0005: solving is event-triggered, so a fixed-rate contract with no events solves once
     * in its life) and the cost of the wrong approximation is a restatement.
     *
     * <p>Worked, on the 364-day T-bill that is the commonest Tier 3 instrument in the book: 1 Apr
     * 2027 to 31 Mar 2028 is eleven whole months plus thirty days, so this returns 12 and the
     * bill is Tier 3, which is where § 10 puts it. 1 Apr 2027 to 2 Apr 2028 returns 13 and is
     * not.
     *
     * @return the tenor in months, null if either date is absent, or a non-positive figure when
     *     maturity precedes origination — which {@link #hasDeterminableTenor} then reports as not
     *     determinable rather than as a short tenor
     */
    public static Integer tenorMonthsBetween(LocalDate origination, LocalDate maturity) {
        if (origination == null || maturity == null) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(origination, maturity);
        if (origination.plusMonths(months).isBefore(maturity)) {
            months += 1;
        }
        return Math.toIntExact(months);
    }

    /** How the tenor reads in an audit sentence, including when it does not read at all. */
    String describeTenor() {
        if (originalTenorMonths == null) {
            return "original tenor absent";
        }
        return "original tenor " + originalTenorMonths + " months";
    }

    /** How the size reads in an audit sentence. */
    String describeExposure() {
        return exposureAtOrigination == null
            ? "exposure at origination absent"
            : exposureAtOrigination.abs().atPresentationScale().toString();
    }
}
