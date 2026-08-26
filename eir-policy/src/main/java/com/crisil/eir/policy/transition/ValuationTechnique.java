package com.crisil.eir.policy.transition;

/**
 * How a transition fair value was arrived at (04 § 6, ACPIR 19).
 *
 * <p>The three the schema permits, and the order is a hierarchy of evidence rather than a list of
 * options: a quoted price is observed, a discounted cash flow is modelled, and carrying cost is
 * <em>presumed</em>. Only the last one needs a file behind it, and it is the one that will be
 * applied to most of a legacy book — which is why it is the one with a control on it.
 */
public enum ValuationTechnique {

    /** An observable price for the instrument or a close proxy. Needs no rate and no file. */
    QUOTED_PRICE,

    /**
     * Modelled: expected flows discounted at a current market rate for the same risk.
     *
     * <p>Requires the rate used, and requires it structurally rather than as a control — a
     * discounted cash flow with no discount rate has not been computed, so there is nothing to
     * report about it.
     */
    DISCOUNTED_CASH_FLOW,

    /**
     * The ACPIR 19 paragraph 19 presumption: carrying cost as the best evidence of fair value.
     *
     * <p>Legitimate and unavoidable at volume — nobody discounts ten million retail loans
     * individually. It is also the technique that produces a difference to opening retained
     * earnings of exactly nil, which is indistinguishable from having measured nothing. The
     * rebuttal evidence reference is what separates the two, and invariant TF-1 is the assertion
     * that it exists.
     */
    CARRYING_COST_AS_BEST_EVIDENCE;

    /** Whether this technique relies on the paragraph 19 presumption. */
    public boolean appliesParagraph19Presumption() {
        return this == CARRYING_COST_AS_BEST_EVIDENCE;
    }

    /** Whether a discount rate is part of having performed this technique at all. */
    public boolean requiresDiscountRate() {
        return this == DISCOUNTED_CASH_FLOW;
    }
}
