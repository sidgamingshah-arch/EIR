package com.crisil.eir.policy.close;

import java.util.Set;

/**
 * The {@code status} column of {@code ACCOUNTING_PERIOD} — {@code OPEN | CLOSING | CLOSED},
 * exactly the three values {@code ck_accounting_period_status} admits
 * (V2__ledger_and_transition.sql; 04 § 2.13).
 *
 * <p>Three states and one terminal one. {@code CLOSED} is immutable (FR-902), which in this
 * vocabulary means it has no legal successor at all: there is no reopen edge, because reopening
 * a period is the mutation FR-902 forbids wearing a status change. A correction to a closed
 * period is a {@link RestatementArtefact} recognised in a later period, never a return to
 * {@code OPEN}.
 *
 * <p><b>{@code CLOSING} is not decoration.</b> It is the state in which the close gate runs:
 * the period has stopped taking upstream postings, the amortisation run has produced figures,
 * and the seven steps of 02 § 3.1 are being worked. Without it the gate would have to evaluate
 * an {@code OPEN} period, whose figures can still move underneath the very invariant results it
 * is reading — so a green dashboard at step 4 would say nothing about the book at step 7.
 *
 * <p>The edges are stated here rather than in {@link PeriodCloseGate} for the reason
 * {@code PolicyVersionStatus} gives for the same decision: a class holding a status that does
 * not know which moves exist lets any caller move it anywhere, and the gate's claim to be the
 * only route into {@code CLOSED} is then false.
 */
public enum PeriodStatus {

    /** Taking postings. Figures can still move, so nothing about a close is assertable yet. */
    OPEN,

    /**
     * Stopped taking postings; the close of 02 § 3.1 is being worked. The only state
     * {@link PeriodCloseGate} will close from, and the only one it will return to on an
     * abandoned close.
     */
    CLOSING,

    /**
     * Locked (FR-902). Terminal: no successor, no reopen, no edit. The row names who closed it,
     * when, and the system-time boundary a replay must read as at —
     * {@code ck_accounting_period_closure_attested}.
     */
    CLOSED;

    /**
     * The statuses this one may legally move to. Empty for {@link #CLOSED}.
     *
     * <p>{@code CLOSING → OPEN} is deliberately present. An abandoned close is the ordinary
     * outcome of step 4 finding red: the fix is upstream, in data the period has to reopen to
     * receive. Refusing that edge would leave an operator with a period stuck in {@code CLOSING}
     * and the only way out through the database.
     */
    public Set<PeriodStatus> legalSuccessors() {
        return switch (this) {
            case OPEN -> Set.of(CLOSING);
            case CLOSING -> Set.of(OPEN, CLOSED);
            case CLOSED -> Set.of();
        };
    }

    /** Whether the life cycle has an edge from this status to {@code to}. */
    public boolean canMoveTo(PeriodStatus to) {
        return to != null && legalSuccessors().contains(to);
    }

    /** Whether a period at this status is locked against change (FR-902). */
    public boolean isClosed() {
        return this == CLOSED;
    }

    /**
     * Whether the close gate may be run against a period at this status.
     *
     * <p>Only {@link #CLOSING}. Published so that an operator screen offers the step exactly
     * where it will be accepted — a screen offering a move the gate refuses trains its users to
     * expect refusals, which is how a control stops being read
     * ({@code MakerCheckerGate.legalSuccessors} makes the same argument).
     */
    public boolean isCloseable() {
        return this == CLOSING;
    }
}
