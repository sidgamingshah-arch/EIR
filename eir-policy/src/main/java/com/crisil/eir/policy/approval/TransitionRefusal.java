package com.crisil.eir.policy.approval;

import com.crisil.eir.policy.PolicyVersionStatus;

/**
 * Why the maker–checker gate refused a status transition.
 *
 * <p>A vocabulary rather than a message, because a refusal is reported, counted and acted on: a
 * batch resubmitting forty pending versions needs to separate "the checker is the maker" (send it
 * to a different checker) from "this jumps the approval step" (a defect in the caller) without
 * pattern-matching on English. The message still carries the specifics — which version, which
 * two states — and lives in {@link TransitionResult#detail()}.
 *
 * <p><b>Several of these overlap with a plain illegal-edge answer, and that is deliberate.</b>
 * {@code DRAFT → EFFECTIVE} is not in the successor table, so {@link #ILLEGAL_TRANSITION} would
 * be a true answer; it is refused as {@link #SKIPS_APPROVAL} instead because the two need
 * different responses from whoever reads the log. An illegal edge is usually a caller wiring
 * mistake. A jump straight to {@code EFFECTIVE} is an attempt — however inadvertent — to put a
 * rule set that moves recognised income into force with nobody's signature on it, which is the
 * single failure FR-210 exists to prevent, and it should not be filed under the same heading as
 * a typo.
 *
 * <p>There is deliberately <em>no</em> reason here for a missing impact preview. The preview gate
 * is a separate control on the same {@code APPROVED → EFFECTIVE} edge and owns its own
 * vocabulary; a reason code named for a condition this gate never evaluates would invite a
 * caller to read a clean transition as evidence the preview was checked.
 */
public enum TransitionRefusal {

    /**
     * The target status is the status the version already has.
     *
     * <p>Refused rather than treated as a satisfied no-op. Approving an already-{@code APPROVED}
     * version is a second sign-off of which only one can be recorded — the second checker
     * believes they approved something and the record names the first — and marking an
     * already-{@code EFFECTIVE} version effective a second time is how a duplicate activation
     * gets logged as a success.
     */
    NOT_A_TRANSITION("the version is already in the requested status"),

    /**
     * The pair of states is not an edge of the life cycle.
     *
     * <p>Covers, among others, {@code APPROVED → DRAFT} and {@code EFFECTIVE → APPROVED}. Both
     * are attempts to reopen a version for editing, and 04 § 2.12 states the rule they break in
     * three words: immutable once approved. A change to an approved version is a new version.
     */
    ILLEGAL_TRANSITION("the life cycle has no edge between those two states"),

    /**
     * {@code EFFECTIVE} was requested from {@code DRAFT} or {@code PENDING_APPROVAL}.
     *
     * <p>The specific hole this gate exists to close. {@link PolicyVersionStatus} does not order
     * its constants for comparison precisely so that no caller can reach {@code EFFECTIVE} by
     * an ordinal test, and this refusal is the other half of that decision: the only route into
     * force runs through a checker who is not the maker.
     */
    SKIPS_APPROVAL("a version reaches EFFECTIVE only from APPROVED, never straight from a draft"),

    /**
     * {@code APPROVED} or {@code EFFECTIVE} was requested with no approval record, or on a
     * version carrying no checker and no approval date.
     *
     * <p>FR-210 in one line: the approval record is the transition's authority, not its
     * paperwork.
     */
    MISSING_APPROVAL_RECORD("no approval record accompanies a transition that requires one"),

    /**
     * The offered checker is the version's own maker.
     *
     * <p>See {@link ApprovalRecord#isSelfApprovalOf} for why this is tested here at all when
     * {@link com.crisil.eir.policy.PolicyVersion}'s constructor also tests it: the constructor
     * compares raw strings, this compares identities, and the gate must answer as a value
     * anyway so that a batch reports every self-approval in it rather than the first.
     */
    SELF_APPROVAL("maker and checker are the same person; a self-approval is the absence of one"),

    /**
     * The version already names a checker and a different person is signing.
     *
     * <p>Refused rather than resolved in either direction, because both readings need a human.
     * If the named checker was an assignment, a different signer means the routing was
     * bypassed. If it was an earlier approval, then two approvals exist and only one can be
     * stored — and the one that would be lost is the one that has already been relied on.
     */
    CHECKER_CONFLICT("the version is already routed to a different checker"),

    /**
     * An approval record was supplied on an edge that is not an approval.
     *
     * <p>Refused rather than ignored. A caller that attaches a sign-off to a submission or a
     * return-to-maker believes the version is being approved; accepting the transition and
     * dropping the record silently destroys the only evidence that a checker looked at it.
     */
    UNEXPECTED_APPROVAL_RECORD("an approval record was supplied on a transition that is not an approval"),

    /**
     * {@code SUPERSEDED} was requested with no successor version named.
     *
     * <p>{@code SUPERSEDED} means replaced, not withdrawn. A version marked superseded with
     * nothing named in its place leaves the dates it used to govern resolving against nothing,
     * and the reason the state exists at all is that deleting a version breaks replay of a
     * closed period (invariant DT-1).
     */
    MISSING_SUCCESSOR("a superseded version must name the version that replaces it"),

    /**
     * The named successor is the version being superseded.
     *
     * <p>The shape a copy-paste in a migration takes. Its effect is worse than a no-op: the
     * version leaves the effective set and the only thing pointing at a replacement points back
     * at itself, so nothing governs the period and nothing says so.
     */
    SELF_SUCCESSION("a version cannot supersede itself"),

    /**
     * A successor was named on an edge that is not a supersession.
     *
     * <p>The mirror of {@link #UNEXPECTED_APPROVAL_RECORD}, refused for the same reason: the
     * caller meant something this transition does not do.
     */
    UNEXPECTED_SUCCESSOR("a successor version was named on a transition that is not a supersession"),

    /**
     * {@code SUPERSEDED} was requested on a version that was never approved.
     *
     * <p>Named separately from {@link #ILLEGAL_TRANSITION} because the constraint is not really
     * in the life cycle — it is in the vocabulary.
     * {@link PolicyVersionStatus#isApproved()} counts {@code SUPERSEDED} as approved, so
     * {@link com.crisil.eir.policy.PolicyVersion}'s constructor requires a checker and an
     * approval date on it, and a draft that never had either cannot be represented as
     * superseded. Which is the right answer rather than an obstacle: a version nobody approved
     * was never in force, so nothing replaced it. An abandoned draft stays a draft.
     */
    SUPERSEDING_AN_UNAPPROVED_VERSION(
        "SUPERSEDED is an approved status; a version that was never in force cannot be replaced"),

    /**
     * The version is already {@code SUPERSEDED}.
     *
     * <p>Terminal, and named rather than folded into {@link #ILLEGAL_TRANSITION} because an
     * attempt to move a superseded version is an attempt to rewrite what governed a closed
     * period. That is the failure invariant DT-1 detects after the fact; refusing it here is
     * cheaper than detecting it in a replay.
     */
    FROM_TERMINAL_STATE("SUPERSEDED is terminal; history is not editable");

    private final String explanation;

    TransitionRefusal(String explanation) {
        this.explanation = explanation;
    }

    /**
     * Why, in general terms, this class of transition is refused.
     *
     * <p>The general statement, not the particulars — {@link TransitionResult#detail()} names the
     * version and the two states.
     */
    public String explanation() {
        return explanation;
    }
}
