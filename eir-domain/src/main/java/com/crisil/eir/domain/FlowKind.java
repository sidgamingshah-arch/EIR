package com.crisil.eir.domain;

/**
 * What a projected cash flow represents.
 *
 * <p>Kind never affects discounting — only amount and date do. It is carried for
 * traceability, for the two reconciliation legs, and so that a projector's output
 * can be validated rather than trusted.
 */
public enum FlowKind {
    DISBURSEMENT,
    PRINCIPAL,
    INTEREST,
    COMBINED_EMI,
    INTEGRAL_FEE_RECEIVED,
    INTEGRAL_COST_PAID,
    BALLOON,
    EXPECTED_PREPAYMENT,
    RESIDUAL_VALUE,
    /**
     * The synthetic terminal flow used by the IFRS 9 B5.4.4 next-repricing-date
     * shortcut: the contractual balance at the reset date, treated as a
     * redemption so that fees amortise to the reset rather than to maturity.
     */
    NOTIONAL_REDEMPTION
}
