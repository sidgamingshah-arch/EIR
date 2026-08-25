package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * The rules of 03 § 10's table, <b>in evaluation order</b>, each carrying the tier it assigns and
 * the row it comes from.
 *
 * <p><b>Declaration order is the precedence order, and it is the substance of this class.</b>
 * {@link TierAssignment} walks {@link #values()} and takes the first rule that fires, so
 * reordering these constants changes the tier of live exposures. § 10's table is not a decision
 * list — it is three rows of populations, and the rows overlap. A six-month exposure inside a
 * hedge relationship satisfies the Tier 1 row ("any exposure in a hedge relationship") and the
 * Tier 3 row ("original tenor ≤ 12 months") at the same time. The Tier 1 limbs are stated without
 * tenor conditions, so they are overrides and they win; put the tenor rule first and the engine
 * hands a hedged instrument the straight-line approximation, silently, and invariant HB-1 then
 * has no rate to amortise the basis adjustment on.
 *
 * <p>Three orderings inside the ladder are worth naming, because each of them is a defect if
 * inverted:
 *
 * <ol>
 *   <li>The six Tier 1 overrides precede everything. Among themselves their order is
 *       immaterial — they all assign Tier 1 — so it follows § 10's own reading order, and every
 *       one of them that matched is reported, not just the first
 *       ({@link TierAssignmentResult#suppressedRules()}).</li>
 *   <li>{@link #TIER_2_CARD_OR_KCC_REVOLVER} precedes
 *       {@link #TIER_1_TENOR_NOT_DETERMINABLE}. A revolver has no contractual maturity by
 *       construction, so its absent tenor is a fact about the product and not a gap in the data,
 *       and escalating it would move the entire card book — one of the five families § 10's
 *       preamble names as holding roughly 80% of the estimation risk — out of the pool treatment
 *       that ACPIR 51 exists to permit.</li>
 *   <li>{@link #TIER_1_TENOR_NOT_DETERMINABLE} precedes both tenor rules. Everything after it
 *       reads a number that must be there; a missing tenor reaching
 *       {@link #TIER_3_SHORT_TENOR} through a zero default is the Cambodia failure mode in one
 *       line of code.</li>
 * </ol>
 */
public enum TierAssignmentRule {

    /**
     * § 10 Tier 1 row — "wholesale above a Board threshold".
     *
     * <p>The only rule whose condition is a policy figure rather than a contract attribute,
     * which is why the threshold is carried by a dated {@link com.crisil.eir.policy.PolicyVersion}
     * of kind {@code TIER_ASSIGNMENT} and not by a constant here. A Board decision has a date and
     * an approver, and a period closed under last year's threshold must replay under last year's
     * threshold.
     */
    TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — wholesale above a Board threshold"),

    /** § 10 Tier 1 row — "all project finance". Unconditional on tenor. */
    TIER_1_PROJECT_FINANCE(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — all project finance"),

    /** § 10 Tier 1 row — "all POCI". Unconditional on tenor. */
    TIER_1_POCI(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — all POCI"),

    /** § 10 Tier 1 row — "all restructured or modified". Unconditional on tenor. */
    TIER_1_RESTRUCTURED_OR_MODIFIED(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — all restructured or modified"),

    /**
     * § 10 Tier 1 row — "any contingent rate feature (ratchet, ESG, step-up)". Unconditional on
     * tenor.
     */
    TIER_1_CONTINGENT_RATE_FEATURE(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — any contingent rate feature"),

    /** § 10 Tier 1 row — "any exposure in a hedge relationship". Unconditional on tenor. */
    TIER_1_HEDGE_RELATIONSHIP(
        MaterialityTier.TIER_1, "03 § 10 Tier 1 row — any exposure in a hedge relationship"),

    /**
     * § 10 Tier 2 row — "credit cards · KCC revolvers", the limb stated separately from the
     * 12-month clause.
     *
     * <p>Placed ahead of {@link #TIER_1_TENOR_NOT_DETERMINABLE} on purpose; see the class comment.
     */
    TIER_2_CARD_OR_KCC_REVOLVER(
        MaterialityTier.TIER_2, "03 § 10 Tier 2 row — credit cards and KCC revolvers"),

    /**
     * Not a row of § 10. The conservative stop for a contract whose tenor cannot be read, taken
     * from ADR-0005's default rather than from the table.
     *
     * <p>§ 10 assigns on tenor and this contract has none to assign on, so the table is silent and
     * something has to answer. ADR-0005 answers: "contract-level measurement is the default.
     * Pooling is an explicit, governed election." Tier 2 requires that election and an approved
     * pool definition; Tier 3 requires a current equivalence test (invariant TG-1). Neither can be
     * assumed on a record nobody can read, so the assignment is Tier 1 and it is visibly a
     * fallback — {@link TierAssignmentResult#isResidualDefault()} reports it, so a control can
     * count these and get the tenor sourced rather than let them sit.
     */
    TIER_1_TENOR_NOT_DETERMINABLE(
        MaterialityTier.TIER_1,
        "ADR-0005 contract-level default — original tenor not determinable, no 03 § 10 row applies"),

    /**
     * § 10 Tier 2 row — "retail and MSME with original tenor > 12 months".
     *
     * <p>Strictly greater than twelve. The complement is {@link #TIER_3_SHORT_TENOR}'s "≤ 12
     * months", and between them they must partition the tenor axis with no gap and no overlap:
     * exactly 12 is Tier 3.
     */
    TIER_2_RETAIL_OR_MSME_LONG_TENOR(
        MaterialityTier.TIER_2, "03 § 10 Tier 2 row — retail and MSME with original tenor > 12 months"),

    /**
     * § 10 Tier 3 row — "original tenor ≤ 12 months" (WCDL, bills, packing credit, post-shipment,
     * temporary OD, T-bills, CP, CD, call/notice money, TREPS, market repo).
     *
     * <p>Segment-blind, and that is the section's whole argument: the same treasury book holds
     * 364-day T-bills here and long-dated HTM SDLs three rules above. The products listed in the
     * row are illustrations of the tenor, not the test.
     */
    TIER_3_SHORT_TENOR(
        MaterialityTier.TIER_3, "03 § 10 Tier 3 row — original tenor ≤ 12 months"),

    /**
     * § 10 Tier 3 row — "plus fully collateralised low-fee products".
     *
     * <p>Last of the substantive rules, and it has to be. The limb carries no tenor condition in
     * § 10, so read in isolation it would sweep a 20-year fully collateralised housing loan into
     * the straight-line approximation — the housing book being the <em>first</em> of the five
     * long-tenor families § 10's preamble names as holding roughly 80% of estimation risk, and
     * reference case 9 putting the straight-line error at +81.0% of year-one income on a long
     * zero-coupon. Reading it after the two tenor rules resolves the contradiction the way the
     * section's opening sentence requires: tenor decides, and this limb catches only what the
     * tenor rules leave — a long-tenor exposure outside retail and MSME, fully secured, with
     * immaterial fees, which has little for a solver to find.
     */
    TIER_3_FULLY_COLLATERALISED_LOW_FEE(
        MaterialityTier.TIER_3, "03 § 10 Tier 3 row — fully collateralised low-fee products"),

    /**
     * Not a row of § 10, and the rule that fires when none of them does. ADR-0005's default.
     *
     * <p>The population is real and § 10's table does not cover it: wholesale below the Board
     * threshold, written for more than twelve months, not secured into the collateral limb. Also
     * every long-dated treasury holding — and § 10's preamble names "long-dated HTM SDLs and
     * corporate bonds" among the five families carrying the estimation risk, so landing them at
     * contract level is not an accident of the fallback, it is the section's own position arrived
     * at from the other direction.
     *
     * <p><b>Why the fallback is Tier 1 and not Tier 3.</b> Because those are the only two
     * candidates and they are not symmetric. Tier 3 is a permission — "permitted only against a
     * current equivalence test" — and a fallback cannot grant a permission that nobody has
     * evidenced. Tier 1 costs a tightened solve, and ADR-0005 measures that cost: solving is
     * event-triggered, so a fixed-rate contract with no events solves once in its lifetime. An
     * over-tiered exposure costs one solve. An under-tiered one costs a restatement, and the UK
     * pattern in the 08 risk register carries 3.73x leverage on year-one fee recognition.
     *
     * <p>{@link #fires} returns false for this constant. It is the terminal answer rather than a
     * matching rule, so it never appears in {@link TierAssignmentResult#suppressedRules()} and
     * never adds noise to a basis that some other rule already decided.
     */
    TIER_1_DEFAULT_CONTRACT_LEVEL(
        MaterialityTier.TIER_1,
        "ADR-0005 contract-level default — no 03 § 10 row applies");

    private final MaterialityTier tier;
    private final String specRow;

    TierAssignmentRule(MaterialityTier tier, String specRow) {
        this.tier = tier;
        this.specRow = specRow;
    }

    /** The tier this rule assigns. */
    public MaterialityTier tier() {
        return tier;
    }

    /** The § 10 row, or the decision record, this rule implements. Persisted in the basis. */
    public String specRow() {
        return specRow;
    }

    /**
     * Whether this rule is one of § 10's Tier 1 overrides — the limbs stated without a tenor
     * condition, which therefore beat the tenor rule.
     *
     * <p>Asked rather than assumed from the tier, because two Tier 1 rules here are <em>not</em>
     * overrides: the two ADR-0005 fallbacks assign Tier 1 because nothing in § 10 applied, which
     * is a different statement and reads differently on a control report.
     *
     * <p>Stated per constant rather than as an ordinal range. Being an override is a fact about
     * what § 10 says, not about where the constant sits in this file; an ordinal test would read
     * as true for whatever a future maintainer happened to insert above the hedge limb.
     */
    public boolean isOverride() {
        return switch (this) {
            case TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD, TIER_1_PROJECT_FINANCE, TIER_1_POCI,
                TIER_1_RESTRUCTURED_OR_MODIFIED, TIER_1_CONTINGENT_RATE_FEATURE,
                TIER_1_HEDGE_RELATIONSHIP -> true;
            case TIER_2_CARD_OR_KCC_REVOLVER, TIER_1_TENOR_NOT_DETERMINABLE,
                TIER_2_RETAIL_OR_MSME_LONG_TENOR, TIER_3_SHORT_TENOR,
                TIER_3_FULLY_COLLATERALISED_LOW_FEE, TIER_1_DEFAULT_CONTRACT_LEVEL -> false;
        };
    }

    /** Whether this rule is an ADR-0005 fallback rather than a row of § 10's table. */
    public boolean isResidualDefault() {
        return this == TIER_1_TENOR_NOT_DETERMINABLE || this == TIER_1_DEFAULT_CONTRACT_LEVEL;
    }

    /**
     * Whether this rule matches {@code input}.
     *
     * <p>Matching is not deciding: several rules can match one contract and precedence picks one.
     * The others are kept, because "the hedge override fired and the short-tenor rule was
     * suppressed" is the sentence an auditor needs and "Tier 1" is not.
     *
     * @param boardThreshold the wholesale threshold in force, from the governing policy version
     */
    public boolean fires(TierAssignmentInput input, Money boardThreshold) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(boardThreshold, "boardThreshold");
        return switch (this) {
            case TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD ->
                input.segment() == TierAssignmentSegment.WHOLESALE
                    && input.exceedsThreshold(boardThreshold);
            case TIER_1_PROJECT_FINANCE ->
                input.has(TierAssignmentFeature.PROJECT_FINANCE);
            case TIER_1_POCI ->
                input.has(TierAssignmentFeature.PURCHASED_OR_ORIGINATED_CREDIT_IMPAIRED);
            case TIER_1_RESTRUCTURED_OR_MODIFIED ->
                input.has(TierAssignmentFeature.RESTRUCTURED_OR_MODIFIED);
            case TIER_1_CONTINGENT_RATE_FEATURE ->
                input.has(TierAssignmentFeature.CONTINGENT_RATE_FEATURE);
            case TIER_1_HEDGE_RELATIONSHIP ->
                input.has(TierAssignmentFeature.HEDGE_RELATIONSHIP);
            case TIER_2_CARD_OR_KCC_REVOLVER ->
                input.has(TierAssignmentFeature.CARD_OR_KCC_REVOLVER);
            // A revolver's absent tenor is the product, not a data gap, so it is excluded here
            // rather than relying on the preceding rule to have already decided. The rule is then
            // true only where the tenor is genuinely unreadable, which is what makes
            // isResidualDefault() countable as a data-quality measure.
            case TIER_1_TENOR_NOT_DETERMINABLE ->
                !input.hasDeterminableTenor()
                    && !input.has(TierAssignmentFeature.CARD_OR_KCC_REVOLVER);
            case TIER_2_RETAIL_OR_MSME_LONG_TENOR ->
                isRetailOrMsme(input) && input.hasDeterminableTenor()
                    && input.originalTenorMonths() > TierAssignmentInput.SHORT_TENOR_MONTHS;
            case TIER_3_SHORT_TENOR ->
                input.hasDeterminableTenor()
                    && input.originalTenorMonths() <= TierAssignmentInput.SHORT_TENOR_MONTHS;
            case TIER_3_FULLY_COLLATERALISED_LOW_FEE ->
                input.has(TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE);
            case TIER_1_DEFAULT_CONTRACT_LEVEL -> false;
        };
    }

    /**
     * What this rule fired on, for the basis. The value, not the rule name: FR-107 asks for the
     * basis to be recorded, and a basis that says "short tenor" without saying six months is a
     * label rather than evidence.
     */
    String firedOn(TierAssignmentInput input, Money boardThreshold) {
        return switch (this) {
            case TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD ->
                "wholesale exposure " + input.describeExposure() + " above Board threshold "
                    + boardThreshold.abs().atPresentationScale();
            case TIER_1_PROJECT_FINANCE -> "project finance";
            case TIER_1_POCI -> "purchased or originated credit-impaired";
            case TIER_1_RESTRUCTURED_OR_MODIFIED -> "restructured or contractually modified";
            case TIER_1_CONTINGENT_RATE_FEATURE ->
                "contingent rate feature (ratchet, ESG or step-up)";
            case TIER_1_HEDGE_RELATIONSHIP -> "designated in a hedge relationship";
            case TIER_2_CARD_OR_KCC_REVOLVER ->
                "credit card or KCC revolver, no contractual maturity to tier on";
            case TIER_1_TENOR_NOT_DETERMINABLE ->
                input.describeTenor() + ", so no tenor-driven row of 03 § 10 can be applied";
            case TIER_2_RETAIL_OR_MSME_LONG_TENOR ->
                input.segment() + " with " + input.describeTenor() + " > "
                    + TierAssignmentInput.SHORT_TENOR_MONTHS;
            case TIER_3_SHORT_TENOR ->
                input.describeTenor() + " <= " + TierAssignmentInput.SHORT_TENOR_MONTHS;
            case TIER_3_FULLY_COLLATERALISED_LOW_FEE ->
                "fully collateralised with immaterial fees, and " + input.describeTenor()
                    + " reaches no tenor-driven row";
            case TIER_1_DEFAULT_CONTRACT_LEVEL ->
                input.segment() + " with " + input.describeTenor() + " and "
                    + input.describeExposure() + " matches no row of 03 § 10";
        };
    }

    private static boolean isRetailOrMsme(TierAssignmentInput input) {
        return input.segment() == TierAssignmentSegment.RETAIL
            || input.segment() == TierAssignmentSegment.MSME;
    }
}
