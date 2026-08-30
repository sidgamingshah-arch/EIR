package com.crisil.eir.api.modules.rulesets;

import com.crisil.eir.api.modules.rulesets.FeeRuleFormat.FeeRuleFormatException;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.approval.ApprovalRecord;
import com.crisil.eir.policy.approval.MakerCheckerGate;
import com.crisil.eir.policy.approval.TransitionRefusal;
import com.crisil.eir.policy.approval.TransitionResult;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleKey;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The fee-rule-set lifecycle behind {@code POST /api/fee-rule-sets/proposals} and
 * {@code .../approvals} — the same draft-then-four-eyes shape as {@link RoutingTableRegister}, over
 * the taxonomy that classifies a fee posting (06 § 5, FR-201, FR-202).
 *
 * <p><b>The approval gate is not restated here.</b> A fee rule set is approved under a
 * {@link PolicyVersion}, so its life cycle is {@code PolicyVersionStatus}'s and its transitions are
 * {@link MakerCheckerGate}'s — including self-approval, which the gate answers as
 * {@link TransitionRefusal#SELF_APPROVAL} on an identity comparison rather than a string one, and an
 * approval by somebody the draft was not routed to, which it answers as
 * {@link TransitionRefusal#CHECKER_CONFLICT}. This class supplies the two things the gate cannot see:
 * the artefact the rows came from, and the register the approved version lands in. The routing table
 * needs its own version of those refusals only because {@code RoutingTableVersion} is a separate
 * record in eir-calc that the gate does not know about.
 *
 * <p><b>Approval marks the version effective, and the date still governs.</b> Approve moves
 * {@code PENDING_APPROVAL → APPROVED → EFFECTIVE} in one call, which is what
 * {@link MakerCheckerGate#makeEffective} is for and which its own comment licenses: "a version
 * approved in March to take effect on 1 April is legitimately marked effective in March — the date,
 * not the status, keeps a March run off it". So a set approved today with an {@code effectiveFrom}
 * next quarter classifies nothing today, and {@link #classify} shows that rather than asserting it.
 *
 * <p><b>One thing is refused here that no type below refuses in time.</b>
 * {@link FeeClassificationResolver}'s constructor rejects two <em>operative</em> versions sharing an
 * {@code effectiveFrom} — nothing orders them, so which one classified a posting would depend on
 * registration order. That rejection is a throw, and it would arrive on the next classification
 * rather than on the approval that caused it: an operator would approve a version successfully and
 * then find the fee taxonomy unusable, with the message naming neither the approval nor the
 * approver. So the collision is checked at the approval, as a refusal, before the version goes into
 * force.
 *
 * <p><b>An unmapped fee code is not defaulted, here or anywhere.</b> {@link #classify} returns
 * {@link FeeClassificationResolver}'s own {@link FeeClassificationResolution}, refusal and all. Both
 * available defaults are wrong in the direction nobody checks — {@code INTEGRAL} spreads the error
 * across sixty periods as a small yield difference, {@code AS_INCURRED} books it as period-one
 * income nobody questions — and on reference case 1, 5,000 of net fee on a million moves the EIR
 * 56.6 basis points. The refusal is the answer.
 *
 * <p>State lives in this object for the life of the process, and it is not thread-safe, for the
 * reasons {@link RoutingTableRegister} states.
 */
public final class FeeRuleSetRegister {

    /**
     * The baseline taxonomy this register starts from, mirroring {@code EirService.feeRules()}.
     *
     * <p><b>Deliberately one rule for one code.</b> FR-201 says an unmapped fee code raises into the
     * exception queue and never defaults, and a demonstration taxonomy covering every code an
     * operator might type would hide the single most important behaviour in the fee rule set. The
     * same reasoning, and the same rule, as the set the console resolves against: type anything but
     * {@code PROC_FEE} at {@code /api/fee-rule-sets/classifications} and watch it refuse.
     */
    private static final String BASELINE_ID = "POL-FEE-2028.1";

    /** Why a submission was not held as a draft. Parallel to {@link RoutingTableRegister.FaultKind}. */
    public enum FaultKind {

        /** Nothing was wrong; the draft was accepted. */
        NONE,

        /** The rows could not be read. The detail carries a row number. */
        FORMAT,

        /**
         * The rows read cleanly and state something no rule set may hold, refused in the domain's
         * own words — a wildcard fee code, a blank rationale, a self-approving version, two rules on
         * one key.
         */
        ACCOUNTING,

        /** A version already carries that id, so two readings would claim one identity. */
        DUPLICATE_ID
    }

    /** Why an approval was refused. */
    public enum ApprovalRefusal {

        /** No version carries that id. */
        UNKNOWN_VERSION,

        /** {@link MakerCheckerGate} refused the transition; {@link Approval#gateRefusal} says which. */
        GATE_REFUSED,

        /**
         * Another operative version already takes effect on the same date.
         *
         * <p>Nothing orders two operative readings sharing a first day, so which one classified a
         * posting would depend on registration order. See the class comment for why this is refused
         * at the approval rather than left to the resolver's constructor.
         */
        EFFECTIVE_DATE_COLLISION
    }

    /**
     * The outcome of one submission.
     *
     * @param draft     the set now awaiting a checker, or null on any fault
     * @param faultKind {@link FaultKind#NONE} where the draft was accepted
     * @param detail    what happened, in one sentence; never blank
     */
    public record Submission(FeeRuleSet draft, FaultKind faultKind, String detail) {

        public Submission {
            Objects.requireNonNull(faultKind, "faultKind");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("a submission states what happened");
            }
            if ((faultKind == FaultKind.NONE) != (draft != null)) {
                // One record, one meaning — see RoutingTableRegister.Submission for the argument.
                throw new IllegalArgumentException(
                    "a submission is an accepted draft or a fault and not both or neither; got "
                        + faultKind + " with draft=" + draft);
            }
        }

        public boolean isAccepted() {
            return faultKind == FaultKind.NONE;
        }
    }

    /**
     * The outcome of one approval.
     *
     * @param approved    the effective set, or null on a refusal
     * @param refusal     why not, or null where approved
     * @param gateRefusal  the gate's own reason, non-null exactly where {@code refusal} is
     *                     {@link ApprovalRefusal#GATE_REFUSED}
     * @param detail       what happened, in one sentence; never blank
     * @param supersession what happened to the version this one replaced, in one sentence; blank on
     *                     a refusal, since nothing was replaced
     */
    public record Approval(
        FeeRuleSet approved,
        ApprovalRefusal refusal,
        TransitionRefusal gateRefusal,
        String detail,
        String supersession) {

        public Approval {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(supersession, "supersession");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("an approval states what happened");
            }
            if ((approved != null) == (refusal != null)) {
                throw new IllegalArgumentException(
                    "an approval is a set or a refusal and not both or neither; got approved="
                        + approved + ", refusal=" + refusal);
            }
            if ((refusal == ApprovalRefusal.GATE_REFUSED) != (gateRefusal != null)) {
                // The coarse code and the gate's own vocabulary have to agree. A GATE_REFUSED
                // carrying no gate reason would tell a caller that a control fired and not which,
                // and a gate reason on any other refusal would name a control that never ran.
                throw new IllegalArgumentException(
                    "GATE_REFUSED carries the gate's reason and no other refusal does; got refusal="
                        + refusal + ", gateRefusal=" + gateRefusal);
            }
        }

        public boolean isApproved() {
            return approved != null;
        }
    }

    /** Every version this register holds, in submission order, keyed by version id. */
    private final Map<String, FeeRuleSet> sets = new LinkedHashMap<>();

    /** Starts from the one-rule baseline. See {@link #BASELINE_ID}. */
    public FeeRuleSetRegister() {
        PolicyVersion baseline = new PolicyVersion(BASELINE_ID, PolicyKind.FEE_RULE_SET,
            "fee and cost taxonomy, FY2028-29", LocalDate.of(2028, 4, 1),
            "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
            PolicyVersionStatus.EFFECTIVE);
        sets.put(BASELINE_ID, new FeeRuleSet(baseline, List.of(
            FeeRule.catchAll("PROC_FEE", LocalDate.of(2028, 4, 1), FeeClassification.INTEGRAL,
                "a processing fee charged at origination is integral to the yield (ACPIR 53)"))));
    }

    /** Every version, in submission order. */
    public List<FeeRuleSet> all() {
        return List.copyOf(sets.values());
    }

    /** One version by id. */
    public Optional<FeeRuleSet> find(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        return Optional.ofNullable(sets.get(versionId.strip()));
    }

    /**
     * Parses submitted rows and holds them as a version awaiting a checker.
     *
     * <p>The draft is submitted for approval in the same call — {@code DRAFT → PENDING_APPROVAL} —
     * because posting a rule set <em>is</em> a maker offering it. The two acts the gate keeps apart
     * are the offer and the sign-off, and those are two requests here as they are two people there.
     *
     * @param id            the version id every classified posting will cite
     * @param description   why this version exists; an unexplained version is not an audit trail
     * @param effectiveFrom the first date it governs
     * @param maker         who authored it
     * @param checker       who it is routed to; the approval must come from this identity
     * @param rulesText     the rows, in {@link FeeRuleFormat}'s format
     */
    public Submission submit(String id, String description, LocalDate effectiveFrom,
        String maker, String checker, String rulesText) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(checker, "checker");
        Objects.requireNonNull(rulesText, "rulesText");

        String key = id.strip();
        if (sets.containsKey(key)) {
            // Refused even where the existing version is only a draft, unlike a routing table draft
            // which may be corrected in place. The asymmetry is deliberate: this register holds
            // approved versions and drafts in one map keyed by id, so a replacement would have to
            // decide whether it was editing a draft or reopening an approved version, and 04 § 2.12
            // settles the second in three words — immutable once approved.
            return new Submission(null, FaultKind.DUPLICATE_ID,
                "fee rule set version '" + key + "' already exists ("
                    + sets.get(key).version().status() + "). A change to the taxonomy is a new"
                    + " version id, because every EIR_COMPUTATION row stores the"
                    + " rule_set_version_id that classified its postings (04 § 2.6) and two"
                    + " readings under one id make a closed period irreproducible");
        }

        List<FeeRule> rules;
        try {
            rules = FeeRuleFormat.parse(rulesText, "fee-rule-set:" + key);
        } catch (FeeRuleFormatException unreadable) {
            return new Submission(null, FaultKind.FORMAT, unreadable.getMessage());
        } catch (IllegalArgumentException refusedByTheDomain) {
            // A row that reads cleanly and states something no rule set may hold — a '*' fee code,
            // which would make FR-202 unenforceable by construction, or a blank rationale. Caught
            // second because FeeRuleFormatException extends IllegalArgumentException, exactly as in
            // RoutingTableRegister.submit, so the order of the clauses is load-bearing.
            return new Submission(null, FaultKind.ACCOUNTING, refusedByTheDomain.getMessage());
        }

        FeeRuleSet drafted;
        try {
            PolicyVersion version = new PolicyVersion(key, PolicyKind.FEE_RULE_SET, description,
                effectiveFrom, maker, checker, null, PolicyVersionStatus.DRAFT);
            drafted = new FeeRuleSet(version, rules);
        } catch (IllegalArgumentException refusedByTheDomain) {
            // PolicyVersion refuses a self-approving version and a blank description; FeeRuleSet
            // refuses two rules on one key, which is what makes FeeRule.PRECEDENCE total. All three
            // are the domain declining to represent the artefact, in its own words.
            return new Submission(null, FaultKind.ACCOUNTING, refusedByTheDomain.getMessage());
        }

        TransitionResult offered = MakerCheckerGate.submitForApproval(drafted.version());
        if (offered.isRefused()) {
            // Unreachable through the constructor above, which produces a DRAFT, and DRAFT ->
            // PENDING_APPROVAL is the one edge a draft has. Reported rather than asserted because
            // the life cycle is data (PolicyVersionStatus.legalSuccessors) and a future edit to it
            // must surface here as a refusal an operator can read, not as a silent success.
            return new Submission(null, FaultKind.ACCOUNTING, offered.detail());
        }
        FeeRuleSet pending = new FeeRuleSet(offered.versionOrThrow(), drafted.rules());
        sets.put(key, pending);
        return new Submission(pending, FaultKind.NONE,
            "fee rule set version '" + key + "' parsed and submitted for approval: "
                + pending.rules().size() + " rule(s) over " + pending.feeCodes().size()
                + " fee code(s), effective " + effectiveFrom + ", made by " + maker
                + ", routed to checker " + checker
                + ". It classifies nothing until that checker approves it");
    }

    /**
     * A checker signs a pending version, which puts it into force from its own effective date.
     *
     * @param versionId  the pending version's id
     * @param checker    the identity signing
     * @param approvedOn the date they signed
     */
    public Approval approve(String versionId, String checker, LocalDate approvedOn) {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(checker, "checker");
        Objects.requireNonNull(approvedOn, "approvedOn");
        String key = versionId.strip();

        FeeRuleSet pending = sets.get(key);
        if (pending == null) {
            return new Approval(null, ApprovalRefusal.UNKNOWN_VERSION, null,
                "no fee rule set version carries id '" + key + "'. Registered: "
                    + new ArrayList<>(sets.keySet()), "");
        }

        TransitionResult signed = MakerCheckerGate.approve(
            pending.version(), ApprovalRecord.by(checker, approvedOn));
        if (signed.isRefused()) {
            // The gate's whole vocabulary comes back: SELF_APPROVAL where the maker signed their
            // own set, CHECKER_CONFLICT where somebody other than the named checker did,
            // NOT_A_TRANSITION on a second approval of an already-approved version. Reported as a
            // value with the gate's own detail, because the gate is the control and this class is
            // not entitled to summarise it.
            return new Approval(null, ApprovalRefusal.GATE_REFUSED,
                signed.refusal(), signed.detail(), "");
        }
        PolicyVersion approvedVersion = signed.versionOrThrow();

        Optional<PolicyVersion> collision = operativeOn(approvedVersion.effectiveFrom(), key);
        if (collision.isPresent()) {
            // Refused before the version goes into force. See the class comment: the resolver's own
            // constructor refuses this, by throwing, on the next classification — long after the
            // approval that caused it and naming neither.
            return new Approval(null, ApprovalRefusal.EFFECTIVE_DATE_COLLISION, null,
                "fee rule set version '" + key + "' would take effect "
                    + approvedVersion.effectiveFrom() + ", the same day as operative version '"
                    + collision.get().id() + "'. Nothing orders two operative readings sharing a"
                    + " first day, so which one classified a posting would depend on registration"
                    + " order. Reissue this version with its own effective date", "");
        }

        TransitionResult inForce = MakerCheckerGate.makeEffective(approvedVersion);
        if (inForce.isRefused()) {
            // Unreachable: the version was just approved, so it carries a checker and a date, which
            // is the only thing this edge asks for. Asserted anyway, for the reason
            // MakerCheckerGate gives about carriesApprovalEvidence — the requirement is on the
            // transition, and a caller that delegated it entirely to another type's constructor
            // would open silently if that constructor were relaxed.
            return new Approval(null, ApprovalRefusal.GATE_REFUSED,
                inForce.refusal(), inForce.detail(), "");
        }
        FeeRuleSet effective = new FeeRuleSet(inForce.versionOrThrow(), pending.rules());
        sets.put(key, effective);
        return new Approval(effective, null, null,
            "fee rule set version '" + key + "' approved by '" + checker + "' on " + approvedOn
                + " and marked EFFECTIVE; it classifies postings dated "
                + effective.version().effectiveFrom() + " onward. The status is the approval and the"
                + " date is what keeps an earlier posting off it, so a version approved today for"
                + " next quarter classifies nothing today",
            supersedePredecessorOf(effective.version()));
    }

    /**
     * Marks the version this one takes over from {@code SUPERSEDED}, and says what it did.
     *
     * <p><b>Why bother, when resolution does not need it.</b>
     * {@link FeeClassificationResolver#ruleSetInForceOn} is latest-operative-wins and
     * {@code SUPERSEDED} is itself operative — deliberately, so a closed period still resolves
     * against the version that governed it (invariant DT-1) — so this changes no classification. It
     * changes the <em>record</em>. Without it, {@code GET /api/fee-rule-sets} lists two versions both
     * {@code EFFECTIVE} and both {@code operative}, which is not a state 04 § 2.12's life cycle
     * describes, and anyone reading the listing as an audit trail would have to work out for
     * themselves which of the two was actually governing.
     *
     * <p><b>Only the immediate predecessor, and only on an append.</b> The predecessor is the
     * operative version with the greatest {@code effectiveFrom} strictly before this one's. Where any
     * operative version takes effect <em>later</em>, this approval is an insertion into the middle of
     * the series rather than a hand-over, nothing is superseded and the returned sentence says so:
     * a chain of supersessions is a statement about which version replaced which, and inserting a
     * version behind an existing one does not answer that question — it raises it, and the answer is
     * a restatement decision rather than an adoption.
     */
    private String supersedePredecessorOf(PolicyVersion successor) {
        PolicyVersion predecessor = null;
        for (FeeRuleSet candidate : sets.values()) {
            PolicyVersion version = candidate.version();
            if (version.id().equals(successor.id()) || !version.status().isOperative()) {
                continue;
            }
            if (version.effectiveFrom().isAfter(successor.effectiveFrom())) {
                return "nothing superseded: operative version '" + version.id()
                    + "' takes effect later (" + version.effectiveFrom() + "), so this approval"
                    + " inserts a reading behind an existing one rather than handing over from it."
                    + " Which version replaced which is then a restatement decision, not something"
                    + " this register may assert";
            }
            if (predecessor == null
                || version.effectiveFrom().isAfter(predecessor.effectiveFrom())) {
                predecessor = version;
            }
        }
        if (predecessor == null) {
            return "nothing superseded: this is the first operative version of the taxonomy";
        }
        // Through the gate, not by advancedTo: SUPERSEDED means replaced, and the gate refuses a
        // supersession that names no successor because a version marked superseded with nothing in
        // its place leaves the dates it governed resolving against nothing.
        TransitionResult replaced = MakerCheckerGate.supersede(predecessor, successor.id());
        if (replaced.isRefused()) {
            // Reported rather than thrown, and rather than silently skipped. The predecessor is
            // operative, hence approved, hence carries a checker and a date, so this is unreachable
            // today — but a supersession that failed quietly would leave exactly the two-EFFECTIVE
            // listing this method exists to prevent, with nothing saying why.
            return "not superseded: " + replaced.detail();
        }
        FeeRuleSet superseded =
            new FeeRuleSet(replaced.versionOrThrow(), sets.get(predecessor.id()).rules());
        sets.put(predecessor.id(), superseded);
        return "version '" + predecessor.id() + "' marked SUPERSEDED by '" + successor.id()
            + "'; it stays operative and keeps resolving the dates it governed, because a closed"
            + " period must replay against the reading it closed under (invariant DT-1)";
    }

    /**
     * The resolver over every registered version — the classification path.
     *
     * <p>Built per call rather than cached, because it is a value over the register's current
     * contents and a cached one would answer for a taxonomy that has since been added to. Drafts are
     * included deliberately: a {@code PENDING_APPROVAL} version is not operative, so it resolves
     * nothing, and holding it is what lets an impact preview be computed against a pending version.
     */
    public FeeClassificationResolver resolver() {
        return new FeeClassificationResolver(all());
    }

    /**
     * Resolves one lookup against the taxonomy in force on {@code asOf}.
     *
     * <p>Total: every input yields a classification or a refusal naming the key. There is no third
     * outcome and no fallback (FR-202).
     */
    public FeeClassificationResolution classify(
        String feeCode, String product, String entity, LocalDate asOf) {
        return resolver().resolve(FeeRuleKey.query(feeCode, product, entity, asOf));
    }

    /**
     * Resolves against one <em>named</em> version — the replay entry point.
     *
     * @throws IllegalArgumentException where the version is not registered or is unapproved; no
     *     computation can ever have cited an unapproved version, so asking to replay against one is
     *     a defect in the caller rather than a fact about the book
     */
    public FeeClassificationResolution classifyAgainstVersion(
        String feeCode, String product, String entity, LocalDate asOf, String versionId) {
        return resolver().resolveAgainstVersion(
            FeeRuleKey.query(feeCode, product, entity, asOf), versionId.strip());
    }

    /** An operative version other than {@code exceptId} taking effect on {@code date}. */
    private Optional<PolicyVersion> operativeOn(LocalDate date, String exceptId) {
        for (FeeRuleSet candidate : sets.values()) {
            PolicyVersion version = candidate.version();
            if (!version.id().equals(exceptId)
                && version.status().isOperative()
                && version.effectiveFrom().equals(date)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
