package com.crisil.eir.policy.approval;

import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.util.Objects;

/**
 * One asked-for move of one policy version to one status, with whatever the move needs to carry.
 *
 * <p>A request object rather than five method signatures because the batch entry point
 * ({@link MakerCheckerGate#applyAll}) has to hold heterogeneous moves in one list: a quarter-end
 * activation typically submits some versions, approves others and supersedes the versions the
 * approvals replace, and FR-905's per-item isolation is only meaningful if the whole set can be
 * presented at once and every refusal comes back together.
 *
 * <p>{@link #approval} and {@link #successorVersionId} are each meaningful on exactly one edge.
 * Supplying either on any other edge is refused rather than ignored — see
 * {@link TransitionRefusal#UNEXPECTED_APPROVAL_RECORD}. That is why they are nullable here
 * instead of being pushed into a sealed hierarchy per edge: the gate's job includes catching a
 * caller that attached evidence to the wrong move, and a type that made the mistake
 * unrepresentable would move that mistake to the call site and out of the refusal report.
 *
 * @param version            the version as it stands now; its {@code status()} is the origin state
 * @param target             the status being asked for
 * @param approval           the checker's sign-off; required on the approval edge, forbidden
 *                           elsewhere
 * @param successorVersionId id of the version replacing this one; required on the supersession
 *                           edge, forbidden elsewhere
 * @param note               why the move is being made, for the audit trail; may be empty
 */
public record TransitionRequest(
    PolicyVersion version,
    PolicyVersionStatus target,
    ApprovalRecord approval,
    String successorVersionId,
    String note) {

    public TransitionRequest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(target, "target");
        // Blank is normalised to absent rather than refused: an empty string arriving from a form
        // or a CSV column means "nothing was given", and the gate's refusals are stated in terms
        // of present-or-absent. Leaving both spellings alive would need every check doubled.
        successorVersionId = blankToNull(successorVersionId);
        note = note == null ? "" : note.strip();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Whether a sign-off accompanies this request. */
    public boolean hasApproval() {
        return approval != null;
    }

    /** Whether a replacement version is named. */
    public boolean namesSuccessor() {
        return successorVersionId != null;
    }

    /** The origin state — where the version is now, not where it is going. */
    public PolicyVersionStatus from() {
        return version.status();
    }

    /** The move, as an audit trail reads it: {@code FEE_RULE_SET version FEE-2027.1 DRAFT -> …}. */
    public String describe() {
        return version.kind() + " version " + version.id() + " " + from() + " -> " + target
            + (note.isEmpty() ? "" : " (" + note + ")");
    }
}
