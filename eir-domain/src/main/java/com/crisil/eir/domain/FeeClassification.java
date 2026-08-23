package com.crisil.eir.domain;

/**
 * How a fee or cost posting is treated.
 *
 * <p>ACPIR 52 states only the positive limb and carries no negative list, so the
 * discriminating cases here come from IFRS 9 B5.4.2 and B5.4.3 adopted as
 * configured policy. Resolution is a versioned rule set, not code: an unmapped
 * fee code fails the contract into the exception queue rather than defaulting.
 */
public enum FeeClassification {

    /** Into the initial carrying amount; amortised via the EIR. */
    INTEGRAL,

    /** P&amp;L when incurred — servicing fees, contingent charges. */
    AS_INCURRED,

    /** Commitment fee where drawdown is not probable; recognised on expiry if undrawn. */
    OVER_COMMITMENT_PERIOD,

    /** A distinct performance obligation — insurance commission, advisory. */
    SEPARATE_SERVICE,

    /**
     * Cannot enter any EIR stream or the gross carrying amount, by Direction.
     * Penal charges under RBI's 2023 framework: charges rather than penal
     * interest, not capitalised, bearing no further interest. Enforced as a
     * filter at the ingestion boundary, with a positive assertion each period
     * (invariant PC-1) — because legacy core banking systems routinely route
     * penal amounts through the interest ledger.
     */
    EXCLUDED_BY_DIRECTION;

    /** True where the posting enters the initial carrying amount. */
    public boolean entersCarryingAmount() {
        return this == INTEGRAL;
    }
}
