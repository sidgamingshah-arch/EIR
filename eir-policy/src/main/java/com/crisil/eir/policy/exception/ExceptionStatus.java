package com.crisil.eir.policy.exception;

/**
 * Where a queued exception sits in its working life — the {@code status} column of
 * <a href="../../../../../../../../../docs/04-data-model.md">04 § 3</a>'s {@code EXCEPTION}
 * entity.
 *
 * <p>Three states, and the third is the one the specification actually asks for. 04 § 3:
 * "Unresolved exceptions <b>block the close</b> unless explicitly accepted with approval." That
 * sentence names two distinct ways a queue entry stops blocking, and collapsing them loses the
 * distinction an auditor is looking for:
 *
 * <ul>
 *   <li>{@link #RESOLVED} — the defect was <em>fixed</em>. The fee code was mapped, the missing
 *       {@code cost_function} was sourced, the malformed schedule was corrected upstream. The
 *       contract can be recomputed and will produce a figure.
 *   <li>{@link #ACCEPTED_WITH_APPROVAL} — the defect <b>stands</b>, and a named person with
 *       authority signed for closing the period over it. Nothing was fixed. The contract still
 *       has no figure if its category stops the contract, and the next run will raise the same
 *       exception again.
 * </ul>
 *
 * <p>Reporting them as one status would let a close pass with a hundred accepted exceptions look
 * exactly like a close with a hundred fixed ones. ACPIR forbids manual override of the
 * computation (ADR-0008); acceptance is not an override — it changes no figure — but it is the
 * closest thing the process has to one, so it is recorded under its own name and carries an
 * approver and a reason.
 */
public enum ExceptionStatus {

    /** Raised and not yet worked. Blocks the close (04 § 3). */
    OPEN(true, false),

    /**
     * The underlying defect was corrected. Does not block the close, and lifts the contract's
     * quarantine, because a recomputation will now produce a figure.
     */
    RESOLVED(false, true),

    /**
     * The close was explicitly signed off over this exception. Does not block the close, and
     * does <em>not</em> lift the quarantine: accepting an exception is a decision to close
     * without that contract's figure, never a decision to publish one.
     */
    ACCEPTED_WITH_APPROVAL(false, false);

    private final boolean blocksClose;
    private final boolean defectFixed;

    ExceptionStatus(boolean blocksClose, boolean defectFixed) {
        this.blocksClose = blocksClose;
        this.defectFixed = defectFixed;
    }

    /** Whether an exception at this status stops the accounting close (04 § 3). */
    public boolean blocksClose() {
        return blocksClose;
    }

    /**
     * Whether the underlying defect was actually corrected, as against merely signed over.
     *
     * <p>The question the quarantine list turns on. {@link #ACCEPTED_WITH_APPROVAL} answers
     * {@code false} on purpose — the input is still malformed, so the contract still has no
     * computed figure.
     */
    public boolean defectFixed() {
        return defectFixed;
    }

    /** Whether this status requires a named person and a reason on the record. */
    public boolean requiresSignatory() {
        return this != OPEN;
    }
}
