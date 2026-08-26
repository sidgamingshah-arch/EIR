package com.crisil.eir.policy.transition;

/**
 * How a deemed EIR was arrived at (04 § 6, FR-909) — the four bases the schema permits.
 *
 * <p>Ordered by how close each stays to the exposure's own facts. The first two look at what the
 * bank was actually pricing at the time; the third builds a rate from the contractual terms that
 * do survive; the fourth borrows from cohorts that were reconstructed properly. All four are
 * assumptions and the enum does not rank them as better or worse — that is the approver's
 * judgement, recorded in the derivation, not a property of the constant.
 */
public enum DeemedEirBasis {

    /** The pricing grid in force when the tranche was originated. */
    ORIGINATION_PRICING_GRID,

    /** The portfolio's average effective rate at origination. */
    PORTFOLIO_AVERAGE_AT_ORIGINATION,

    /**
     * The contractual rate with an estimated fee loading added.
     *
     * <p>The one that most resembles the pre-ACPIR position, and therefore the one an auditor
     * reads hardest: a fee loading estimated at nil reproduces the contractual rate exactly and
     * calls it an EIR.
     */
    CONTRACTUAL_RATE_PLUS_FEE_LOADING,

    /** The weighted average of a comparable cohort that was fully reconstructed. */
    WEIGHTED_AVERAGE_OF_RECONSTRUCTED_COHORT
}
