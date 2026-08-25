package com.crisil.eir.policy.approval;

import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The only legal way a {@link PolicyVersion} changes status — FR-210's maker–checker gate.
 *
 * <p><b>What this gate is for.</b> Every {@link com.crisil.eir.policy.PolicyKind} moves recognised
 * income ({@code PolicyKind.movesRecognisedIncome}), so a rule set going into force restates
 * figures the bank publishes. FR-210 answers that with four eyes and an effective date; this class
 * is the part that makes the four eyes unavoidable rather than procedural. The transition table
 * below is the whole content of the control: {@code EFFECTIVE} has exactly one predecessor, and
 * reaching it requires a checker who is not the maker.
 *
 * <p><b>Why the transitions are stated and not derived.</b> {@link PolicyVersionStatus} lists its
 * constants in progression order and its own documentation warns that nothing may read that
 * order. An ordinal test — "the target must be the successor of the origin" — looks like the
 * obvious implementation and licenses the one move this gate exists to refuse: {@code DRAFT}
 * ordinal 0 to {@code EFFECTIVE} ordinal 3 is a forward move by that test. Worse, it is a forward
 * move that <em>reads</em> as progress in a log. The table is written out so that adding a sixth
 * status fails the class initialiser instead of quietly acquiring edges nobody decided on.
 *
 * <pre>
 *   DRAFT            → PENDING_APPROVAL
 *   PENDING_APPROVAL → APPROVED | DRAFT
 *   APPROVED         → EFFECTIVE | SUPERSEDED
 *   EFFECTIVE        → SUPERSEDED
 *   SUPERSEDED       → (terminal)
 * </pre>
 *
 * <p><b>Two edges in that table need their absence explained.</b>
 *
 * <p>{@code DRAFT → SUPERSEDED} and {@code PENDING_APPROVAL → SUPERSEDED} are not edges, even
 * though supersession is otherwise reachable from every live state. {@code SUPERSEDED} counts as
 * approved in {@link PolicyVersionStatus#isApproved()} — a closed period must still resolve
 * against the version that governed it — so {@code PolicyVersion}'s constructor requires a checker
 * and an approval date on a superseded version, and a draft that never had either cannot be
 * represented as one. That is the right answer and not a limitation: a version nobody approved was
 * never in force, so nothing replaced it. There is no {@code ABANDONED} status because an
 * abandoned draft needs none — it stays a draft, and no date resolves against it.
 *
 * <p>{@code PENDING_APPROVAL → DRAFT} <em>is</em> an edge, and it is the one addition to FR-210's
 * bare forward chain. Without it a checker who declines has nowhere to put the version:
 * supersession is unavailable to it by the paragraph above, and the only remaining move would be
 * the approval they just declined to give. A control whose refusal path dead-ends is a control
 * that gets worked around.
 *
 * <p><b>Refusals are values.</b> Nothing here throws for a policy condition. A quarter-end
 * activation presents a batch (see {@link #applyAll}), and a gate that threw on the first
 * self-approval would surface one defect and hide the rest — the same argument FR-905 makes for
 * per-contract isolation. Throwing is reserved for a gate defect: a {@code null} argument, or a
 * transition table that has lost a status.
 *
 * <p><b>Composing with the impact-preview gate.</b> FR-210 has a second limb — a stored
 * portfolio-level impact preview before a version goes effective — and it guards the same
 * {@code APPROVED → EFFECTIVE} edge. It is deliberately not implemented or referenced here. This
 * gate answers only the question it can answer from the version and the approval record in front
 * of it, and a caller activating a version applies both, in either order, keeping whichever
 * refusals come back. The alternative — a reason code here named for a preview this class never
 * looks at — would let a clean result be read as evidence a preview exists.
 */
public final class MakerCheckerGate {

    /**
     * The life cycle, as data.
     *
     * <p>Total over {@link PolicyVersionStatus}: every status has an entry, including the terminal
     * one with an empty set. Checked at class initialisation rather than trusted, because a
     * missing entry would read as "no legal moves" and quietly strand a new status — a failure
     * that would show up as an unexplained refusal months later rather than as a build failure
     * on the day the status was added.
     */
    private static final Map<PolicyVersionStatus, Set<PolicyVersionStatus>> LEGAL_SUCCESSORS =
        legalSuccessorTable();

    private MakerCheckerGate() {
    }

    private static Map<PolicyVersionStatus, Set<PolicyVersionStatus>> legalSuccessorTable() {
        EnumMap<PolicyVersionStatus, Set<PolicyVersionStatus>> table =
            new EnumMap<>(PolicyVersionStatus.class);
        table.put(PolicyVersionStatus.DRAFT,
            EnumSet.of(PolicyVersionStatus.PENDING_APPROVAL));
        table.put(PolicyVersionStatus.PENDING_APPROVAL,
            EnumSet.of(PolicyVersionStatus.APPROVED, PolicyVersionStatus.DRAFT));
        table.put(PolicyVersionStatus.APPROVED,
            EnumSet.of(PolicyVersionStatus.EFFECTIVE, PolicyVersionStatus.SUPERSEDED));
        table.put(PolicyVersionStatus.EFFECTIVE,
            EnumSet.of(PolicyVersionStatus.SUPERSEDED));
        table.put(PolicyVersionStatus.SUPERSEDED,
            EnumSet.noneOf(PolicyVersionStatus.class));

        for (PolicyVersionStatus status : PolicyVersionStatus.values()) {
            if (!table.containsKey(status)) {
                // A gate defect, not a data condition: the class cannot decide anything about a
                // status whose edges nobody stated, and guessing would be worse than failing.
                throw new IllegalStateException(
                    "the maker-checker transition table has no entry for " + status
                        + "; a new status needs its edges decided, not defaulted");
            }
            table.put(status, Collections.unmodifiableSet(table.get(status)));
        }
        return Collections.unmodifiableMap(table);
    }

    /**
     * The statuses {@code from} may legally move to. Empty for {@code SUPERSEDED}.
     *
     * <p>Published because a caller building an operator screen should offer exactly the moves the
     * gate will accept. A screen that offers a move the gate refuses trains its users to expect
     * refusals, which is how a control stops being read.
     */
    public static Set<PolicyVersionStatus> legalSuccessors(PolicyVersionStatus from) {
        Objects.requireNonNull(from, "from");
        return LEGAL_SUCCESSORS.get(from);
    }

    /**
     * Whether the life cycle has an edge from {@code from} to {@code to}.
     *
     * <p>An edge is necessary and not sufficient: {@code APPROVED → EFFECTIVE} is an edge and is
     * still refused on a version carrying no approval evidence. Use {@link #transition} for the
     * answer that accounts for the version in hand.
     */
    public static boolean isLegalTransition(PolicyVersionStatus from, PolicyVersionStatus to) {
        Objects.requireNonNull(to, "to");
        return legalSuccessors(from).contains(to);
    }

    /**
     * Whether a version carries the evidence a transition into or past {@code APPROVED} needs: a
     * named checker and a date they signed on.
     *
     * <p>Separate and public because it is the literal content of FR-210's first limb — "a version
     * cannot reach {@code EFFECTIVE} without an approval record" — and because
     * {@code PolicyVersion}'s constructor already enforces it for the approved statuses, which
     * makes the check inside {@link #transition} unreachable through that constructor today. It
     * is asserted at the transition anyway, and testable here on its own, because the requirement
     * is on the transition: a later relaxation of the record's own validation must not silently
     * open the gate into force.
     */
    public static boolean carriesApprovalEvidence(PolicyVersion version) {
        Objects.requireNonNull(version, "version");
        return version.checker() != null && !version.checker().isBlank()
            && version.approvedOn() != null;
    }

    /** A maker submits their draft for approval. {@code DRAFT → PENDING_APPROVAL}. */
    public static TransitionResult submitForApproval(PolicyVersion version) {
        return transition(new TransitionRequest(
            version, PolicyVersionStatus.PENDING_APPROVAL, null, null, ""));
    }

    /**
     * A checker signs off. {@code PENDING_APPROVAL → APPROVED}.
     *
     * <p>Approval <em>adds</em> facts to the version — who checked it and when — so this cannot be
     * expressed as {@code withStatus(APPROVED)}: that record's constructor refuses a status
     * claiming approval with no checker named, which is exactly the protection being relied on.
     * The approved version returned here carries the record's checker and date.
     */
    public static TransitionResult approve(PolicyVersion version, ApprovalRecord approval) {
        return transition(new TransitionRequest(
            version, PolicyVersionStatus.APPROVED, approval, null, ""));
    }

    /**
     * A checker declines and hands the version back to its maker.
     * {@code PENDING_APPROVAL → DRAFT}.
     *
     * @param why the checker's reason, kept on the transition record
     */
    public static TransitionResult returnToMaker(PolicyVersion version, String why) {
        return transition(new TransitionRequest(
            version, PolicyVersionStatus.DRAFT, null, null, why));
    }

    /**
     * An approved version goes into force. {@code APPROVED → EFFECTIVE}.
     *
     * <p>Note what this does <em>not</em> check. It does not compare today against
     * {@code effectiveFrom}: whether a version governs a given date is
     * {@link PolicyVersion#isEffectiveOn}'s question, and a version approved in March to take
     * effect on 1 April is legitimately marked effective in March — the date, not the status,
     * keeps a March run off it. It does not check for another effective version of the same kind
     * either; that is the version registry's question, over a set this gate never sees.
     */
    public static TransitionResult makeEffective(PolicyVersion version) {
        return transition(new TransitionRequest(
            version, PolicyVersionStatus.EFFECTIVE, null, null, ""));
    }

    /**
     * A later version replaces this one. {@code APPROVED | EFFECTIVE → SUPERSEDED}.
     *
     * @param successorVersionId the id of the version taking over; never this version's own id,
     *                           and never absent — a superseded version with no successor is a
     *                           deletion wearing a status, and deletions break replay (DT-1)
     */
    public static TransitionResult supersede(PolicyVersion version, String successorVersionId) {
        return transition(new TransitionRequest(
            version, PolicyVersionStatus.SUPERSEDED, null, successorVersionId, ""));
    }

    /**
     * Every request in the batch, answered independently and in order.
     *
     * <p>The reason refusals are values. A quarter-end activation presents submissions, approvals
     * and the supersessions those approvals imply as one set; an operator wants the whole list of
     * problems once, not the first problem repeatedly. Nothing here short-circuits on a refusal,
     * because the requests concern different versions and one bad approval says nothing about the
     * next.
     *
     * <p>Requests are answered against the versions <em>as presented</em>. Two requests moving the
     * same version in sequence — submit then approve — will not chain: the second sees the version
     * its caller supplied, in the status it supplied it in, and is refused. That is deliberate.
     * Chaining would mean this method deciding an ordering over a list a caller assembled, and the
     * whole point of a maker–checker gate is that the submission and the approval are two acts by
     * two people, not two rows of one file.
     */
    public static List<TransitionResult> applyAll(List<TransitionRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        List<TransitionResult> results = new ArrayList<>(requests.size());
        for (TransitionRequest request : requests) {
            results.add(transition(request));
        }
        return List.copyOf(results);
    }

    /**
     * The gate itself: one request in, one allowed-or-refused answer out.
     *
     * <p>The order of the checks is part of the control, because each refusal is a different
     * instruction to whoever reads it. The specific readings come before the general ones: a jump
     * to {@code EFFECTIVE} is reported as {@link TransitionRefusal#SKIPS_APPROVAL} and not as a
     * missing edge, and an attempt to supersede a draft is reported against the vocabulary that
     * actually forbids it and not as a missing edge either. Only then does the table answer.
     * Evidence-placement checks come last of all, because a misplaced approval record on an
     * illegal edge is the smaller of the two mistakes the caller made.
     */
    public static TransitionResult transition(TransitionRequest request) {
        Objects.requireNonNull(request, "request");
        PolicyVersion version = request.version();
        PolicyVersionStatus from = request.from();
        PolicyVersionStatus target = request.target();

        // (1) Asking for the status it already has. Refused rather than reported as a satisfied
        // no-op: a second approval of an APPROVED version is a second checker believing they
        // signed something, and only one signature can be stored.
        if (from == target) {
            return refuse(request, TransitionRefusal.NOT_A_TRANSITION,
                "the version is already " + from);
        }

        // (2) SUPERSEDED is history. Moving it would rewrite what governed a closed period, which
        // is precisely the failure a replay (DT-1) is meant to detect after the fact.
        if (from == PolicyVersionStatus.SUPERSEDED) {
            return refuse(request, TransitionRefusal.FROM_TERMINAL_STATE,
                "a superseded version is the record of what was in force; supersede its"
                    + " successor instead, or issue a new version");
        }

        // (3) THE HOLE THIS CLASS EXISTS TO CLOSE. Straight into force with no signature on it.
        // Named separately from an illegal edge because the log entry has to say what was really
        // attempted: putting a rule set that moves recognised income into force unapproved.
        if (target == PolicyVersionStatus.EFFECTIVE && !from.isApproved()) {
            return refuse(request, TransitionRefusal.SKIPS_APPROVAL,
                "attempted " + from + " -> EFFECTIVE, which would put a " + version.kind()
                    + " into force with no checker's signature on it; route it through"
                    + " PENDING_APPROVAL and APPROVED (FR-210)");
        }

        // (4) Supersession of something that was never in force. The constraint is in the
        // vocabulary rather than the life cycle: PolicyVersionStatus.isApproved() counts
        // SUPERSEDED as approved, so PolicyVersion refuses to represent a superseded version with
        // no checker. Answered here, before the table, so the refusal names the real reason.
        if (target == PolicyVersionStatus.SUPERSEDED && !from.isApproved()) {
            return refuse(request, TransitionRefusal.SUPERSEDING_AN_UNAPPROVED_VERSION,
                "a version in " + from + " was never in force, so nothing replaced it; an"
                    + " abandoned draft stays a draft");
        }

        // (5) The table. What is left here is a genuine wiring mistake — reopening an approved
        // version for editing (04 § 2.12: immutable once approved), or skipping the submission.
        if (!isLegalTransition(from, target)) {
            return refuse(request, TransitionRefusal.ILLEGAL_TRANSITION,
                "from " + from + " the only legal moves are " + legalSuccessors(from));
        }

        // (6) Evidence attached to the wrong move. Refused rather than ignored: silently dropping
        // a sign-off destroys the only record that a checker looked at the version.
        if (target != PolicyVersionStatus.APPROVED && request.hasApproval()) {
            return refuse(request, TransitionRefusal.UNEXPECTED_APPROVAL_RECORD,
                "an approval by " + request.approval().checker() + " was supplied on a "
                    + from + " -> " + target + " move, which records no approval");
        }
        if (target != PolicyVersionStatus.SUPERSEDED && request.namesSuccessor()) {
            return refuse(request, TransitionRefusal.UNEXPECTED_SUCCESSOR,
                "successor " + request.successorVersionId() + " was named on a " + from + " -> "
                    + target + " move, which replaces nothing");
        }

        return switch (target) {
            case PENDING_APPROVAL -> allow(request, version.withStatus(target));
            case DRAFT -> returnedToMaker(request);
            case APPROVED -> approved(request);
            case EFFECTIVE -> madeEffective(request);
            case SUPERSEDED -> superseded(request);
        };
    }

    /**
     * {@code PENDING_APPROVAL → DRAFT}, clearing any checker the version was routed to.
     *
     * <p>The clearing is the interesting part. A declined version that kept its assigned checker
     * would meet {@link TransitionRefusal#CHECKER_CONFLICT} the moment it was resubmitted and
     * approved by anybody else — so the ordinary consequence of a decline, reassignment to a
     * second checker, would be refused as a control breach. Per 04 § 2.12 the {@code checker}
     * column is the approver, not the queue it sat in; who declined it belongs on the transition
     * record, which is what this result's detail carries.
     */
    private static TransitionResult returnedToMaker(TransitionRequest request) {
        PolicyVersion version = request.version();
        PolicyVersion draft = new PolicyVersion(
            version.id(), version.kind(), version.description(), version.effectiveFrom(),
            version.maker(), null, null, PolicyVersionStatus.DRAFT);
        return allow(request, draft);
    }

    /** {@code PENDING_APPROVAL → APPROVED}, on a sign-off by somebody other than the maker. */
    private static TransitionResult approved(TransitionRequest request) {
        PolicyVersion version = request.version();
        if (!request.hasApproval()) {
            return refuse(request, TransitionRefusal.MISSING_APPROVAL_RECORD,
                "APPROVED is a claim that a second person signed the version off; the claim needs"
                    + " the signature (FR-210)");
        }
        ApprovalRecord approval = request.approval();

        // Maker != checker, answered as a value. PolicyVersion's constructor would throw on the
        // same condition, and a throw is the wrong shape here for two reasons: a batch must
        // report every self-approval in it, and that constructor compares raw strings while this
        // compares identities — see ApprovalRecord.isSelfApprovalOf for the ' alice' / 'alice'
        // case it lets through.
        if (approval.isSelfApprovalOf(version)) {
            return refuse(request, TransitionRefusal.SELF_APPROVAL,
                "maker '" + version.maker() + "' and checker '" + approval.checker()
                    + "' are the same identity; send it to a different checker");
        }

        // Already routed to somebody else. Neither reading of this is safe to resolve silently:
        // a bypassed assignment, or a second approval overwriting a first that has been relied on.
        if (version.checker() != null && !version.checker().isBlank()
            && !approval.checkedBy(version.checker())) {
            return refuse(request, TransitionRefusal.CHECKER_CONFLICT,
                "the version names checker '" + version.checker() + "' and the approval is by '"
                    + approval.checker() + "'");
        }

        PolicyVersion signed = new PolicyVersion(
            version.id(), version.kind(), version.description(), version.effectiveFrom(),
            version.maker(), approval.checker(), approval.checkedOn(),
            PolicyVersionStatus.APPROVED);
        return allow(request, signed);
    }

    /** {@code APPROVED → EFFECTIVE}, on a version that carries its approval evidence. */
    private static TransitionResult madeEffective(TransitionRequest request) {
        // Unreachable through PolicyVersion's own constructor, which will not build an APPROVED
        // version without a checker and a date. Asserted anyway: FR-210's requirement is on this
        // transition, and a gate that delegated it entirely to another type's constructor would
        // open silently if that constructor were ever relaxed. See carriesApprovalEvidence.
        if (!carriesApprovalEvidence(request.version())) {
            return refuse(request, TransitionRefusal.MISSING_APPROVAL_RECORD,
                "the version is APPROVED but names no checker or no approval date, so there is no"
                    + " approval to put into force");
        }
        return allow(request, request.version().withStatus(PolicyVersionStatus.EFFECTIVE));
    }

    /** {@code APPROVED | EFFECTIVE → SUPERSEDED}, naming the version that takes over. */
    private static TransitionResult superseded(TransitionRequest request) {
        PolicyVersion version = request.version();
        if (!request.namesSuccessor()) {
            return refuse(request, TransitionRefusal.MISSING_SUCCESSOR,
                "SUPERSEDED means replaced; with nothing named in its place the dates this"
                    + " version governed would resolve against nothing");
        }
        // Compared on the stripped ids. TransitionRequest strips the successor it was given, and
        // PolicyVersion does not strip its own id — it only refuses a blank one — so a version
        // whose id arrived as "FEE-2027.1 " would otherwise pass this check against "FEE-2027.1"
        // and supersede itself. That is exactly the migration copy-paste the refusal is written
        // for. Case is deliberately NOT folded, unlike an identity: a version id is a machine key
        // and two ids differing in case are two rows.
        if (version.id().strip().equals(request.successorVersionId())) {
            return refuse(request, TransitionRefusal.SELF_SUCCESSION,
                "version " + version.id() + " cannot be its own successor");
        }
        return allow(request,
            version.withStatus(PolicyVersionStatus.SUPERSEDED));
    }

    /**
     * An allowed result, whose detail names the move and whatever authorised it.
     *
     * <p>The successor id appears in the detail <em>and</em> stays reachable as a value through
     * {@link TransitionResult#successorVersionId()}. {@code PolicyVersion} has no
     * {@code superseded_by} field, so whoever persists the transition is the only party that can
     * store the pointer, and a pointer that existed only inside an English sentence would be lost
     * the moment the row was written.
     */
    private static TransitionResult allow(TransitionRequest request, PolicyVersion moved) {
        return TransitionResult.allowed(request, moved,
            request.describe()
                + (request.hasApproval() ? ", " + request.approval().describe() : "")
                + (request.namesSuccessor()
                    ? ", superseded by " + request.successorVersionId() : ""));
    }

    /**
     * A refusal naming what was attempted and why it is not allowed.
     *
     * <p>Both halves, always. A refusal that says only "illegal transition" sends its reader back
     * to the source to work out which transition; one that says only what was attempted leaves
     * them guessing at the rule.
     */
    private static TransitionResult refuse(
        TransitionRequest request, TransitionRefusal refusal, String because) {
        return TransitionResult.refused(request, refusal,
            request.describe() + " refused: " + refusal.explanation() + " — " + because);
    }
}
