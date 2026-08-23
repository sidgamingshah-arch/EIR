package com.crisil.eir.domain;

/**
 * What a contractual rate component <em>compensates for</em>.
 *
 * <p>This is the axis on which reset-versus-catch-up routing is decided
 * (ADR-0006). Tagging on the cause rather than on the product, or on the
 * observation that a rate moved, is what lets the routing survive a change in
 * the standard: the IASB's April 2026 tentative decision discriminates on
 * precisely this axis, so when the wording changes only the mapping moves.
 *
 * <p>Routing is <em>not</em> encoded here. The mapping from driver to mechanism
 * is versioned configuration; see {@code RoutingTable} in eir-calc.
 */
public enum RateDriver {

    /** Benchmark movement — repo, EBLR, MCLR, an inflation index. */
    TIME_VALUE_OF_MONEY,

    /** Credit spread repricing to prevailing market. */
    CREDIT_RISK_MARKET,

    /** A contractual step keyed to a covenant or rating trigger. */
    CREDIT_RATCHET_PREDETERMINED,

    /** A sustainability margin ratchet. */
    ESG_LINKED,

    /** A contractual step-up unrelated to market rates. */
    STEP_UP_PREDETERMINED,

    /** Revision of the entity's own behavioural estimate — a CPR curve change. */
    BEHAVIOURAL_ESTIMATE,

    /** Deviation of a tranche disbursement schedule from projection. */
    DISBURSEMENT_TIMING,

    /** Renegotiated terms. Runs the modification substantiality test. */
    NEGOTIATED;

    /**
     * Whether this driver represents a movement in market rates, as opposed to a
     * pre-determined contractual step or the entity's own revised estimate.
     *
     * <p>Used by the routing table's validity check: a market driver cannot arise
     * on a fixed-rate instrument by its own terms, so such a combination can only
     * have come from renegotiation.
     */
    public boolean isMarketMovement() {
        return this == TIME_VALUE_OF_MONEY || this == CREDIT_RISK_MARKET;
    }
}
