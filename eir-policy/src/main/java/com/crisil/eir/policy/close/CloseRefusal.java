package com.crisil.eir.policy.close;

import java.util.Objects;

/**
 * One thing wrong with a close: a reason from the vocabulary, and the particulars.
 *
 * <p><b>Why a close carries a list of these rather than one.</b> A month-end close presents many
 * problems at once — six red invariants, forty unworked exceptions, a sub-ledger break — and an
 * operator wants the whole list, because working them is a day's work distributed across several
 * desks and each round trip through the gate costs a full run. This is the argument
 * {@code MakerCheckerGate.applyAll} makes for a batch of policy transitions, and it applies with
 * more force here: the transitions in a batch are independent, whereas the problems in a close all
 * belong to the same period and are all blocking the same publication.
 *
 * <p>So refusals are <b>values</b>. Nothing in this package throws for a data condition. A throw
 * would surface the first problem and hide the rest, which is the same failure mode FR-905's
 * per-contract isolation exists to prevent one level down.
 *
 * <p>Both halves are always present. A refusal that says only {@code INVARIANT_BREACH} sends its
 * reader back to the dashboard to work out which one; one that says only "S3-1 is red" leaves them
 * guessing at which gate they tripped.
 *
 * @param reason which gate refused
 * @param detail the particulars — the invariant id, the contract, the residual; never blank
 */
public record CloseRefusal(CloseGateRefusal reason, String detail) {

    public CloseRefusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "close refusal " + reason + " carries no detail; a reason code alone cannot be"
                    + " worked — nobody knows which invariant, contract or residual it means");
        }
    }

    /** Whether this refusal is evidence that exists and does not hold, rather than absent. */
    public boolean looksLikeDiligence() {
        return reason.looksLikeDiligence();
    }

    /** One audit sentence: the rule, then what tripped it. */
    public String describe() {
        return reason + ": " + reason.explanation() + " — " + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
