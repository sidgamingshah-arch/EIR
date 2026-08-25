package com.crisil.eir.policy.approval;

import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of asking the maker–checker gate to move a version: the moved version, or the reason
 * it was refused.
 *
 * <p><b>A value and not an exception</b>, on the same reasoning as
 * {@link com.crisil.eir.calc.solver.SolveResult} in the calculation core. A quarter-end activation
 * presents a batch of moves, and a gate that threw on the first self-approval would tell an
 * operator about one of them and hide the rest, turning one round of corrections into as many
 * rounds as there are defects. FR-905 requires per-item isolation for contracts and the same
 * argument applies to policy: refusals are data.
 *
 * <p>Exactly one of {@link #after} and {@link #refusal} is populated, and the constructor enforces
 * the pairing in <em>both</em> directions. A result carrying both would be a refusal somebody
 * could accidentally read the moved version out of; a result carrying neither would be a refusal
 * with no reason, which is the thing this type exists to make impossible.
 *
 * <p><b>The whole request is retained, not just the version and the target.</b> That is not
 * tidiness. {@link PolicyVersion} has no {@code superseded_by} field, so the id of the version
 * taking over — which the gate makes mandatory, because a superseded version with nothing in its
 * place is a deletion — has nowhere to live on {@link #after}. Keeping the request means the
 * pointer survives as data for whatever persists the transition, instead of surviving only as a
 * substring of {@link #detail}. The same holds for the approval note and for the checker's reason
 * on a decline.
 *
 * @param request the move as it was asked for: the version, the target, and whatever the move
 *                carried
 * @param after   the moved version; {@code null} when refused
 * @param refusal why it was refused; {@code null} when allowed
 * @param detail  the particulars, naming the version and both states — self-contained, so that a
 *                log line or an exception message is actionable without the request beside it
 */
public record TransitionResult(
    TransitionRequest request,
    PolicyVersion after,
    TransitionRefusal refusal,
    String detail) {

    public TransitionResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(detail, "detail");
        if ((after == null) == (refusal == null)) {
            // Both, or neither. Either way this is a defect in the gate rather than a fact about
            // a policy version, so it throws: nothing downstream could compensate for a result
            // whose own fields disagree about whether the transition happened.
            throw new IllegalStateException(
                "a transition result is either a moved version or a refusal, never both and never"
                    + " neither: " + detail);
        }
        if (after != null && after.status() != request.target()) {
            // Guards the one mistake that would make an allowed result actively misleading: a
            // gate that returned the version unchanged, or advanced to a state nobody asked for,
            // while reporting success. The caller's next action is to persist `after`.
            throw new IllegalStateException(
                "transition to " + request.target() + " produced a version in status "
                    + after.status());
        }
    }

    /** The version moved. */
    public static TransitionResult allowed(
        TransitionRequest request, PolicyVersion after, String detail) {
        Objects.requireNonNull(after, "after");
        return new TransitionResult(request, after, null, detail);
    }

    /** The move refused, with the reason and the particulars. */
    public static TransitionResult refused(
        TransitionRequest request, TransitionRefusal refusal, String detail) {
        Objects.requireNonNull(refusal, "refusal");
        return new TransitionResult(request, null, refusal, detail);
    }

    /** The version as presented, before the move. */
    public PolicyVersion before() {
        return request.version();
    }

    /**
     * The status that was asked for.
     *
     * <p>Retained on a refusal as well, and that is the point of reading it off the request: a
     * refusal that does not say what was attempted cannot be acted on.
     */
    public PolicyVersionStatus target() {
        return request.target();
    }

    /**
     * The id of the version taking over, on an allowed supersession; {@code null} otherwise.
     *
     * <p>Surfaced here because it is the one thing a caller must persist that
     * {@link #after} cannot carry — {@code PolicyVersion} has no {@code superseded_by} field, and
     * a superseded version whose successor is recorded nowhere leaves the dates it governed
     * resolving against nothing. Whoever writes the transition away is the only party that can
     * store the pointer, so it is handed back as a value.
     */
    public String successorVersionId() {
        return request.successorVersionId();
    }

    /** Whether the move happened. */
    public boolean isAllowed() {
        return after != null;
    }

    /** Whether the move was refused. */
    public boolean isRefused() {
        return refusal != null;
    }

    /**
     * The moved version, or a failure carrying the refusal.
     *
     * <p>Separate from {@link #after} for the reason {@code SolveResult.rateOrThrow} is separate
     * from its {@code rate}: the batch path needs refusals back as data, and a caller that has
     * already checked {@link #isAllowed} should not have to defend against a silent
     * {@code null} it has proven cannot be there.
     *
     * @throws IllegalStateException if the transition was refused
     */
    public PolicyVersion versionOrThrow() {
        if (after == null) {
            throw new IllegalStateException("transition refused (" + refusal + "): " + detail);
        }
        return after;
    }

    /**
     * Only the refusals in a batch's results, in the order they were presented.
     *
     * <p>What a batch reports. Order is presentation order rather than grouped by reason, because
     * the operator's next act is to fix the inputs and re-present them, and that is done in the
     * order the inputs are held.
     */
    public static List<TransitionResult> refusalsIn(List<TransitionResult> results) {
        Objects.requireNonNull(results, "results");
        return results.stream().filter(TransitionResult::isRefused).toList();
    }

    /**
     * A one-line audit sentence: what became of the move, and the particulars.
     *
     * <p>The version and both states come from {@link #detail}, which the gate builds
     * self-contained so that the same string is useful in a log, in an exception message and here.
     * Repeating them around it would only make the line harder to read.
     */
    public String describe() {
        return (isAllowed() ? "allowed" : "REFUSED " + refusal) + " — " + detail;
    }
}
