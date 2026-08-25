package com.crisil.eir.policy.tier;

/**
 * A contract attribute that 03 § 10 makes tier-relevant, other than segment, tenor and size.
 *
 * <p><b>Why a feature set rather than eight boolean components on
 * {@link TierAssignmentInput}.</b> Every one of these is a limb of a row in § 10's table, and
 * the table is a Board-approved artefact that will grow — a new contingent-rate product, a new
 * collateral concession. A set-valued input takes a new limb as a new constant plus a new rule;
 * a record with eight booleans takes it as a change to the shape of every call site and every
 * persisted row. It also makes the audit answer natural: the assignment can report exactly
 * which attributes it saw, which is half of FR-107.
 *
 * <p>The Tier 1 constants are <b>overrides</b>. § 10 states them without a tenor condition, and
 * {@link TierAssignmentRule} therefore evaluates them ahead of the tenor rule. A six-month
 * exposure in a hedge relationship is Tier 1, not Tier 3.
 *
 * <p><b>Deliberately absent: zero-coupon and deep-discount.</b> § 10.3 says approximation "is
 * never permitted" for those at any tenor, and that is a statement about whether a Tier 3
 * assignment may be <em>used</em>, not about how the tier is derived — reference case 9 puts the
 * error at 81.0% overstatement of year-one income on a 15-year zero-coupon at 8%. It is
 * evaluated with the equivalence test (FR-411, FR-412, invariant TG-1), which is a separate work
 * unit in this same package. This class therefore does not carry the attribute, and the
 * assignment here may legitimately propose Tier 3 for an instrument that the equivalence test
 * subsequently refuses. See {@link TierAssignmentResult#requiresEquivalenceTest()}.
 */
public enum TierAssignmentFeature {

    /**
     * Project and infrastructure finance. § 10 Tier 1 row: "all project finance", unconditional.
     *
     * <p>Unconditional because the cash-flow profile is the estimation risk — moratorium,
     * capitalised construction-period interest, drawdown in tranches — and a short-tenor bridge
     * inside a project structure carries the same estimation problem as the term facility.
     */
    PROJECT_FINANCE,

    /**
     * Purchased or originated credit-impaired, ACPIR 6(3)(v). § 10 Tier 1 row: "all POCI".
     *
     * <p>Unconditional because a POCI instrument's EIR is credit-adjusted — solved on
     * expected rather than contractual flows — and there is no approximation of a
     * credit-adjusted rate by "contractual rate plus straight-line fees" at any tenor. The
     * contractual rate is not the starting point.
     */
    PURCHASED_OR_ORIGINATED_CREDIT_IMPAIRED,

    /**
     * Restructured or contractually modified. § 10 Tier 1 row: "all restructured or modified".
     *
     * <p>Unconditional because the modification is itself the event that needs an instrument-level
     * answer: the routing decision between catch-up, reset and derecognition (ADR-0006) is taken
     * per instrument, and a pooled or approximated carrying amount has nothing for it to act on.
     */
    RESTRUCTURED_OR_MODIFIED,

    /**
     * Any contingent rate feature — margin ratchet, ESG-linked step, contractual step-up.
     * § 10 Tier 1 row.
     *
     * <p>Unconditional because the contingency has to be estimated, and an estimate needs an
     * instrument to attach to and a documented assumption to be reviewed against.
     */
    CONTINGENT_RATE_FEATURE,

    /**
     * Designated in a hedge relationship. § 10 Tier 1 row: "any exposure in a hedge
     * relationship".
     *
     * <p>Unconditional, and the sharpest of the six. Hedge accounting produces a basis
     * adjustment that must be amortised over the hedged item's remaining life on the item's own
     * effective rate (invariant HB-1), so an approximated rate is not merely imprecise, it
     * leaves the discontinued-hedge amortisation with no rate to run on. Six-month hedged
     * exposures exist and are exactly the case a tenor-first evaluation order gets wrong.
     */
    HEDGE_RELATIONSHIP,

    /**
     * A credit card or KCC revolver. § 10 Tier 2 row: "credit cards · KCC revolvers".
     *
     * <p>Listed in the Tier 2 row as its own limb, <em>separate</em> from the "> 12 months"
     * clause, and that separation is load-bearing. A revolver has no contractual maturity —
     * 03 § 5 and 05 § 239 both note that cards, OD and KCC have no contractual repayment
     * schedule — so its expected life is a behavioural estimate, which is precisely the
     * collective estimate ACPIR 51's group presumption exists for. Keying it on tenor would
     * either read the annual review date as a 12-month tenor and drop the card book into the
     * Tier 3 approximation, or read a missing tenor as missing data and escalate it to Tier 1.
     * Both are wrong; the limb is evaluated on the attribute instead.
     */
    CARD_OR_KCC_REVOLVER,

    /**
     * Fully collateralised with immaterial fees. § 10 Tier 3 row: "plus fully collateralised
     * low-fee products".
     *
     * <p>The one product limb in § 10 that carries no tenor condition, and the one that has to
     * be read against the section's opening sentence rather than literally. Read literally it
     * would sweep a 20-year fully collateralised housing loan into the straight-line
     * approximation — the same housing book that § 10's preamble names first among the five
     * families holding roughly 80% of estimation risk. So it is evaluated <em>after</em> the
     * tenor rules and catches only what they leave: see
     * {@link TierAssignmentRule#TIER_3_FULLY_COLLATERALISED_LOW_FEE}.
     */
    FULLY_COLLATERALISED_LOW_FEE
}
