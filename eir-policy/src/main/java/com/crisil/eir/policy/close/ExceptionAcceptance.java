package com.crisil.eir.policy.close;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.time.Instant;
import java.util.Objects;

/**
 * A decision to close a period over an exception that still stands — 04 § 3's "explicitly accepted
 * with approval", with both signatures on it.
 *
 * <p><b>Why this exists when the queue already has an accepted status.</b>
 * {@code ExceptionRecord.acceptWithApproval} sets the row to
 * {@code ACCEPTED_WITH_APPROVAL} and fills {@code resolved_by} and {@code resolution_note} —
 * 04 § 3's own columns, and one name. One name cannot evidence an approval: an approval is
 * somebody else's signature on somebody's decision, so a row that records only the signatory
 * cannot be distinguished from a row where the person who asked for the acceptance signed it
 * themselves. That distinction is the whole content of the control, because
 * {@link ExceptionCategory#blocksClose()} is true for every one of the ten categories —
 * acceptance is the <em>only</em> way past a queued exception, which makes acceptance, and not the
 * category list, the thing worth two people.
 *
 * <p>So this artefact carries both parties and the close gate matches it against the queue row.
 * {@link #acceptedBy()} is the maker: whoever worked the queue at step 3 of 02 § 3.1 and concluded
 * the period should close over this contract. {@link #approvedBy()} is the checker who signed for
 * that conclusion, and is the name the queue row should carry.
 *
 * <p><b>A self-approved acceptance is representable, deliberately.</b> The constructor does not
 * refuse one, for the reason {@code JournalEntry} allows an unbalanced entry to be constructed:
 * {@link CloseGateRefusal#SELF_APPROVED_ACCEPTANCE} is the control, and a control whose condition
 * its own input type forbids is a control that cannot fire. It would also be the wrong shape —
 * a close presents its problems as a list, and a constructor that threw would surface the first
 * self-approval and hide the other thirty-nine.
 *
 * <p>The comparison itself is {@link FourEyes#isSelfApproval}, which strips and case-folds at
 * {@code Locale.ROOT}. Not re-implemented here: the same rule is stated by the policy-version
 * gate, the approval record and the routing-table version, and the one place it was written a
 * fourth time with a plain {@code equals} was the one that let a maker approve their own artefact
 * under a different capitalisation.
 *
 * @param contractId  the contract whose exception is being accepted; a machine key, compared
 *                    exactly — case is not folded, because two contract ids differing in case are
 *                    two contracts
 * @param category    which of the ten categories of 04 § 3 is being accepted; part of the match,
 *                    because a contract can raise several and accepting one is not accepting
 *                    another
 * @param acceptedBy  who put the acceptance forward
 * @param approvedBy  who signed for it
 * @param reason      why the period may close over a defect that stands; never blank — this is
 *                    the entire audit trail of a published set of accounts omitting a figure
 * @param acceptedOn  system time the approval was given
 */
public record ExceptionAcceptance(
    String contractId,
    ExceptionCategory category,
    String acceptedBy,
    String approvedBy,
    String reason,
    Instant acceptedOn) {

    public ExceptionAcceptance {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(acceptedOn, "acceptedOn");
        contractId = FourEyes.requireIdentity(contractId, "contractId",
            "an acceptance names the contract it lets the close proceed over (FR-905)");
        acceptedBy = FourEyes.requireIdentity(acceptedBy, "acceptedBy",
            "04 § 3's acceptance is a person's decision, not the queue's");
        approvedBy = FourEyes.requireIdentity(approvedBy, "approvedBy",
            "an anonymous approval is not an approval");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                "acceptance of " + category + " on contract " + contractId + " gives no reason;"
                    + " the reason is the whole audit trail of why a close proceeded over a known"
                    + " defect");
        }
        // Note what is NOT checked here: whether acceptedBy and approvedBy are the same identity.
        // See the class javadoc — that is CloseGateRefusal.SELF_APPROVED_ACCEPTANCE's job, and a
        // guard here would make the refusal unreachable.
    }

    /**
     * Whether the same person put this acceptance forward and signed for it.
     *
     * <p>What input makes this true: {@code acceptedBy = "ops.lead"} and
     * {@code approvedBy = " Ops.Lead "}. Both are inputs and neither is constrained by the
     * constructor, so the condition is representable and the gate's refusal is a real one.
     */
    public boolean isSelfApproved() {
        return FourEyes.isSelfApproval(acceptedBy, approvedBy);
    }

    /**
     * Whether this acceptance is the one that covers {@code record}.
     *
     * <p>Contract <em>and</em> category. A contract with an unmapped fee code and a missing
     * {@code cost_function} has two exceptions that need two separate decisions — the fee code may
     * be immaterial while the cost function is not — and matching on the contract alone would let
     * one signature clear both.
     */
    public boolean covers(ExceptionRecord record) {
        Objects.requireNonNull(record, "record");
        return contractId.equals(record.contractId().strip()) && category == record.category();
    }

    /**
     * Whether {@code signatory} is the person who approved this acceptance.
     *
     * <p>Identity comparison, not string equality, so that a queue row carrying
     * {@code "Ops.Lead"} and an artefact carrying {@code "ops.lead"} are not reported as a
     * signatory conflict. The conflict this feeds ({@link
     * CloseGateRefusal#ACCEPTANCE_SIGNATORY_CONFLICT}) is about two <em>people</em>.
     */
    public boolean approvedBySignatory(String signatory) {
        return FourEyes.sameIdentity(approvedBy, signatory);
    }

    /** One audit sentence: who accepted what, who signed, and why. */
    public String describe() {
        return category + " on contract " + contractId + " accepted by " + acceptedBy
            + ", approved by " + approvedBy + " at " + acceptedOn + ": " + reason;
    }

    @Override
    public String toString() {
        return describe();
    }
}
