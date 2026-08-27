package com.crisil.eir.policy.replay;

/**
 * Whether tonight's C-12 sample selected anything, and if not, whether that is a fact or a defect.
 *
 * <p>Three states rather than a boolean, because the two ways of selecting nothing are not the same
 * event and must not be reported the same way. One is the book being new; the other is the control
 * having stopped running while nobody noticed.
 */
public enum SampleOutcome {

    /** Tonight's sample selected at least one closed period. The control runs. */
    PERIOD_SELECTED(true),

    /**
     * No closed period exists to replay. Not a defect.
     *
     * <p>The real state of a book before its first close — April 2027, under ACPIR 20 — and of any
     * book whose closes are all outside the lookback for legitimate reasons. Reporting this as a
     * breach would make the control red by design, which is the failure mode
     * {@code InvariantId.TM_1} spends a paragraph on: a control that is red by design gets
     * suppressed, and then it is not there for the night it matters.
     */
    NOTHING_CLOSED_YET(false),

    /**
     * Closed periods exist and the sample selected none of them. A defect.
     *
     * <p>The control has silently stopped running. The nightly job completes, logs no findings, and
     * a workpaper that records "C-12 performed, no exceptions" is literally true and completely
     * misleading. See {@link ReplaySamplingBasis} for the two mundane ways this happens and for why
     * the basis deliberately allows the condition to be represented at all.
     */
    CONTROL_INERT(false);

    private final boolean ran;

    SampleOutcome(boolean ran) {
        this.ran = ran;
    }

    /** Whether the control actually replayed something tonight. */
    public boolean controlRan() {
        return ran;
    }

    /** Whether this outcome should be escalated rather than filed. */
    public boolean isDefect() {
        return this == CONTROL_INERT;
    }
}
