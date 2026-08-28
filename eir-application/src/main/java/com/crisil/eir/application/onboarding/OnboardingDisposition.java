package com.crisil.eir.application.onboarding;

/**
 * What became of one contract at initial recognition — the three-way split 05 § 3.1 produces, and
 * the partition FR-905's count has to add up over.
 *
 * <p><b>Three, not two, and the middle one is the point of this unit.</b> The month-end run of
 * 05 § 3.2 has two dispositions, which is what {@link com.crisil.eir.application.ContractResult}
 * models: a contract is computed or it is quarantined, and its javadoc explains why dropping it is
 * the wrong reading — "a run over 10,000,000 contracts that silently processed 9,999,998 reconciles
 * perfectly, because the two it dropped are absent from both sides of every total."
 *
 * <p>Initial recognition has a third, and it is neither of the other two. An FVTPL instrument is
 * recorded and excluded from EIR processing (FR-103). It produced no rate, and that is correct
 * rather than a failure — so filing it as a quarantine would put a correctly-measured instrument in
 * a queue that blocks the close, and filing it as computed would imply a figure that does not
 * exist. It is its own answer, and it has to be counted separately or the population arithmetic
 * cannot distinguish "no rate because the instrument has none" from "no rate because something
 * broke".
 */
public enum OnboardingDisposition {

    /**
     * The gate admitted it, the fees classified, the tier assigned, the projection held IC-1 and
     * the solver returned a rate. An {@code EIR_COMPUTATION} row follows (04 § 2.6).
     */
    RECOGNISED,

    /**
     * Recorded and excluded from EIR processing — FVTPL (FR-103).
     *
     * <p>Not a failure and not an exception. See {@link MeasurementDecision} for why an SPPI failure
     * lands here rather than in the exception queue.
     */
    EXCLUDED_FROM_EIR,

    /**
     * The barrier caught something, or a stage refused: the contract carries an
     * {@link com.crisil.eir.policy.exception.ExceptionRecord} and no figures (FR-905).
     *
     * <p>An unmapped fee code, a missing cost function, a penal charge at the ingestion boundary,
     * an IC-1 breach, a solve with no root or several, a missing SPPI assessment on an instrument
     * claiming amortised cost — each of those is a contract the engine will not produce a number
     * for, and each is named rather than dropped.
     */
    QUARANTINED;

    /** Whether a rate was produced and an {@code EIR_COMPUTATION} row should be written. */
    public boolean producedARate() {
        return this == RECOGNISED;
    }

    /** Whether this disposition contributes an entry to the exception queue. */
    public boolean isQuarantined() {
        return this == QUARANTINED;
    }
}
