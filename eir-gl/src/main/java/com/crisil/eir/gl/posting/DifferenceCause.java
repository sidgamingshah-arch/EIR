package com.crisil.eir.gl.posting;

/**
 * Why a control account legitimately differs from the sub-ledger it controls (FR-803, control C-13,
 * 07 § 3).
 *
 * <p>An enum rather than free text alone, because "explained" has to mean something a reviewer can
 * check. Free text alone lets an explanation be a sentence nobody reads, and the difference is then
 * suppressed by narrative — which is the failure mode a "zero unexplained difference" control
 * invites and the reason FR-803 does not simply say "zero".
 *
 * <p>{@link #OTHER} exists because a taxonomy that cannot express a real cause gets one of the
 * existing causes misapplied instead, and that is worse than an honest {@code OTHER}. It is a signal
 * that a cause is missing from this list, not a resting place: every explanation carries a mandatory
 * narrative regardless of cause, so {@code OTHER} still has to say something.
 */
public enum DifferenceCause {

    /**
     * The two sides will agree once the cut-off passes — a posting the engine has booked that the
     * GL has not yet taken, or the reverse.
     *
     * <p>Self-reversing: the same difference in the opposite direction next period. That is what
     * makes a timing difference checkable rather than merely asserted — a "timing" difference that
     * does not reverse was never a timing difference, and the next period's reconciliation is where
     * that shows.
     */
    TIMING("timing difference across the GL cut-off", true),

    /**
     * Somebody posted a journal directly in the GL, outside the engine.
     *
     * <p>Real and permanent: the engine's sub-ledger does not know about it and will never know
     * about it, so it does not reverse. Legitimate — a GL is entitled to entries the sub-ledger did
     * not originate — and worth naming separately from timing precisely because it does not go away
     * on its own.
     */
    MANUAL_GL_JOURNAL("manual GL journal posted outside the engine", false),

    /** Cash or a settlement in transit between the two books on the reporting date. */
    IN_TRANSIT_SETTLEMENT("settlement in transit at the reporting date", true),

    /**
     * A restatement artefact for a closed period (FR-902) that the GL has taken and the sub-ledger
     * carries at the original figures, or the reverse.
     *
     * <p>Not self-reversing, and not a defect: closed periods are immutable, so the correction is a
     * dated separate fact and the two books legitimately sit apart until the GL's own restatement
     * lands.
     */
    PRIOR_PERIOD_RESTATEMENT("prior-period restatement not yet mirrored", false),

    /** A cause this list does not name. The narrative has to carry it. */
    OTHER("cause not covered by this taxonomy", false);

    private final String statement;
    private final boolean selfReversing;

    DifferenceCause(String statement, boolean selfReversing) {
        this.statement = statement;
        this.selfReversing = selfReversing;
    }

    /** What this cause claims, for a reconciliation report. */
    public String statement() {
        return statement;
    }

    /**
     * Whether the difference is expected to reverse of its own accord next period.
     *
     * <p>Reported rather than enforced. Enforcing it needs two periods' reconciliations to compare,
     * which belongs to the close workflow (FR-901) and not to a single period's control.
     */
    public boolean selfReversing() {
        return selfReversing;
    }
}
