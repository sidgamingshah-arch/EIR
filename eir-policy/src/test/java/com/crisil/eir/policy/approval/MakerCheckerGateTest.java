package com.crisil.eir.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-210's first limb: no version reaches {@code EFFECTIVE} without a second person's signature.
 *
 * <p>The transition table is asserted against a set of edges written out by hand from FR-210 and
 * 04 § 2.12 — <em>not</em> read back from {@link MakerCheckerGate#legalSuccessors}, which would
 * assert only that the implementation equals itself. The whole defect class this gate guards
 * against is an edge nobody decided on being present, and a test that derives its expectation
 * from the table cannot see one.
 */
class MakerCheckerGateTest {

    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate SIGNED_ON = LocalDate.of(2027, 3, 15);
    private static final String MAKER = "policy.author";
    private static final String CHECKER = "accounting.policy.owner";

    /**
     * The life cycle, stated independently of the implementation.
     *
     * <p>Six edges, derived as follows. FR-210 gives the forward chain — a draft is submitted, a
     * checker signs it, an approved version takes effect on its effective date. 04 § 2.12 gives
     * "immutable once approved", which removes every edge back out of {@code APPROVED} and
     * {@code EFFECTIVE} other than supersession. {@code SUPERSEDED} is retained history
     * (invariant DT-1 needs a closed period to resolve against what governed it), so it is
     * terminal. Supersession is unavailable to {@code DRAFT} and {@code PENDING_APPROVAL} because
     * {@code PolicyVersionStatus.isApproved()} counts {@code SUPERSEDED} as approved and
     * {@code PolicyVersion} therefore demands a checker on it — a version nobody approved was
     * never in force and so was never replaced. And {@code PENDING_APPROVAL → DRAFT} exists
     * because a checker who declines must have somewhere to put the version; without it the only
     * remaining move is the approval they declined to give.
     */
    private static final Set<String> LEGAL_EDGES = Set.of(
        "DRAFT->PENDING_APPROVAL",
        "PENDING_APPROVAL->APPROVED",
        "PENDING_APPROVAL->DRAFT",
        "APPROVED->EFFECTIVE",
        "APPROVED->SUPERSEDED",
        "EFFECTIVE->SUPERSEDED");

    /**
     * A version in {@code status}, carrying approval evidence exactly where the record type
     * requires it. {@code PolicyVersion} refuses an approved status with no checker, so the
     * unapproved statuses get none and the approved ones get both fields.
     */
    private static PolicyVersion versionIn(PolicyVersionStatus status) {
        boolean signed = status.isApproved();
        return new PolicyVersion(
            "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
            EFFECTIVE_FROM, MAKER, signed ? CHECKER : null, signed ? SIGNED_ON : null, status);
    }

    private static ApprovalRecord signature() {
        return new ApprovalRecord(CHECKER, SIGNED_ON, "reviewed against ACPIR 53");
    }

    @Nested
    @DisplayName("the transition table, stated rather than inferred")
    class TransitionTable {

        @Test
        @DisplayName("all 25 status pairs agree with the hand-written edge set")
        void everyPairMatchesTheStatedEdges() {
            // 5 x 5 exhaustively, including the five identity pairs (a status is never its own
            // successor). The expectation comes from LEGAL_EDGES above, written from the specs.
            for (PolicyVersionStatus from : PolicyVersionStatus.values()) {
                for (PolicyVersionStatus to : PolicyVersionStatus.values()) {
                    boolean expected = LEGAL_EDGES.contains(from + "->" + to);
                    assertThat(MakerCheckerGate.isLegalTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
                }
            }
        }

        @Test
        @DisplayName("the table holds exactly six edges, no more")
        void edgeCountIsSix() {
            // A cross-check on the pair sweep above, which would pass if BOTH the set and the
            // table gained the same wrong edge. Six is counted off the specs: submit, approve,
            // decline, take effect, supersede-from-approved, supersede-from-effective.
            int edges = 0;
            for (PolicyVersionStatus from : PolicyVersionStatus.values()) {
                edges += MakerCheckerGate.legalSuccessors(from).size();
            }
            assertThat(edges).as("edges in the life cycle").isEqualTo(6);
        }

        @Test
        @DisplayName("SUPERSEDED is terminal and the table says so")
        void supersededHasNoSuccessors() {
            assertThat(MakerCheckerGate.legalSuccessors(PolicyVersionStatus.SUPERSEDED))
                .as("history has no successor state")
                .isEmpty();
        }

        @Test
        @DisplayName("the published successor sets cannot be mutated by a caller")
        void successorSetsAreUnmodifiable() {
            // The table is static and shared. A caller that could add an edge to the set it was
            // handed would change the control for every subsequent transition in the JVM, and
            // nothing would record that it had happened.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> MakerCheckerGate.legalSuccessors(PolicyVersionStatus.DRAFT)
                    .add(PolicyVersionStatus.EFFECTIVE));
        }

        @Test
        @DisplayName("a null status is a caller defect, not a refusal")
        void nullsThrow() {
            // The asymmetry that runs through this package: policy conditions come back as
            // values, defects in the calling code do not.
            assertThatNullPointerException()
                .isThrownBy(() -> MakerCheckerGate.isLegalTransition(null, PolicyVersionStatus.DRAFT));
            assertThatNullPointerException()
                .isThrownBy(() -> MakerCheckerGate.transition(null));
        }
    }

    @Nested
    @DisplayName("DRAFT straight to EFFECTIVE — the hole this gate closes")
    class TheHole {

        @Test
        @DisplayName("a draft cannot be put into force, and the refusal says what was attempted")
        void draftToEffectiveIsRefused() {
            TransitionResult result = MakerCheckerGate.makeEffective(
                versionIn(PolicyVersionStatus.DRAFT));

            assertThat(result.isAllowed()).as("an unapproved rule set in force").isFalse();
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.SKIPS_APPROVAL);
            // Named in the message so that a log line is actionable on its own: which version,
            // which two states, and why not.
            assertThat(result.detail())
                .contains("FEE-2027.1")
                .contains("DRAFT -> EFFECTIVE")
                .contains("no checker's signature");
            assertThat(result.target())
                .as("a refusal that does not say what was attempted cannot be acted on")
                .isEqualTo(PolicyVersionStatus.EFFECTIVE);
        }

        @Test
        @DisplayName("a version awaiting its checker cannot be put into force either")
        void pendingToEffectiveIsRefused() {
            // The near miss. Submitted, so a status field alone looks like progress; still
            // unsigned, so it is the same breach as the draft.
            TransitionResult result = MakerCheckerGate.makeEffective(
                versionIn(PolicyVersionStatus.PENDING_APPROVAL));

            assertThat(result.refusal()).isEqualTo(TransitionRefusal.SKIPS_APPROVAL);
            assertThat(result.detail()).contains("PENDING_APPROVAL -> EFFECTIVE");
        }

        @Test
        @DisplayName("ordinal order does not licence the jump")
        void ordinalOrderIsNotAPath() {
            // PolicyVersionStatus lists DRAFT(0) … EFFECTIVE(3) in progression order and warns
            // that nothing may read it. This is that warning as an assertion: by an ordinal test
            // DRAFT -> EFFECTIVE is a forward move, and a forward move reads as progress in a log.
            assertThat(PolicyVersionStatus.DRAFT.ordinal())
                .isLessThan(PolicyVersionStatus.EFFECTIVE.ordinal());
            assertThat(MakerCheckerGate.isLegalTransition(
                PolicyVersionStatus.DRAFT, PolicyVersionStatus.EFFECTIVE))
                .as("forward by ordinal, and still not a transition")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the approved path, end to end")
    class ApprovedPath {

        @Test
        @DisplayName("submit, approve, take effect — and approval adds the checker and the date")
        void theWholeChain() {
            PolicyVersion draft = versionIn(PolicyVersionStatus.DRAFT);

            TransitionResult submitted = MakerCheckerGate.submitForApproval(draft);
            assertThat(submitted.isAllowed()).isTrue();
            assertThat(submitted.versionOrThrow().status())
                .isEqualTo(PolicyVersionStatus.PENDING_APPROVAL);

            TransitionResult approved =
                MakerCheckerGate.approve(submitted.versionOrThrow(), signature());
            assertThat(approved.isAllowed()).isTrue();
            PolicyVersion signed = approved.versionOrThrow();
            // Approval ADDS facts. withStatus(APPROVED) alone could not express it: the record's
            // own constructor refuses an approved status with no checker named, which is the
            // protection being relied on rather than an obstacle being worked around.
            assertThat(signed.status()).isEqualTo(PolicyVersionStatus.APPROVED);
            assertThat(signed.checker()).isEqualTo(CHECKER);
            assertThat(signed.approvedOn()).isEqualTo(SIGNED_ON);
            assertThat(signed.maker()).as("the maker is untouched by approval").isEqualTo(MAKER);
            assertThat(signed.status().isOperative())
                .as("approved on 15 March, effective 1 April: signed and not yet in force")
                .isFalse();

            TransitionResult effective = MakerCheckerGate.makeEffective(signed);
            assertThat(effective.isAllowed()).isTrue();
            assertThat(effective.versionOrThrow().status())
                .isEqualTo(PolicyVersionStatus.EFFECTIVE);
            assertThat(effective.versionOrThrow().isEffectiveOn(EFFECTIVE_FROM)).isTrue();
            assertThat(effective.versionOrThrow().isEffectiveOn(EFFECTIVE_FROM.minusDays(1)))
                .as("status is EFFECTIVE from March; the effective DATE still governs")
                .isFalse();
        }

        @Test
        @DisplayName("the allowed result carries the approval in its audit line")
        void allowedResultDescribesTheApproval() {
            TransitionResult approved = MakerCheckerGate.approve(
                versionIn(PolicyVersionStatus.PENDING_APPROVAL), signature());
            assertThat(approved.detail())
                .contains(CHECKER)
                .contains("2027-03-15")
                .contains("reviewed against ACPIR 53");
        }

        @Test
        @DisplayName("an approved version goes into force with no further evidence needed")
        void effectiveNeedsNoSecondRecord() {
            // The preview gate (FR-210's second limb) guards this same edge and is deliberately
            // not this class's business: it is applied by the caller alongside this gate, and
            // this gate names no reason for a condition it never evaluates.
            TransitionResult result = MakerCheckerGate.makeEffective(
                versionIn(PolicyVersionStatus.APPROVED));
            assertThat(result.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("maker is never checker")
    class MakerIsNeverChecker {

        @Test
        @DisplayName("the maker signing their own version is refused as a value, not a throw")
        void selfApprovalRefused() {
            TransitionResult result = MakerCheckerGate.approve(
                versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                new ApprovalRecord(MAKER, SIGNED_ON, "looks fine to me"));

            assertThat(result.refusal()).isEqualTo(TransitionRefusal.SELF_APPROVAL);
            assertThat(result.detail()).contains(MAKER).contains("different checker");
        }

        @Test
        @DisplayName("a whitespace or case variant of the maker is still the maker")
        void identityVariantsAreStillSelfApproval() {
            // The defect this catches, and it is not hypothetical. PolicyVersion's constructor
            // compares maker.equals(checker) on RAW strings and does not strip its maker, so the
            // pair below satisfies it — asserted here so the hole is on the record:
            assertThatCode(() -> new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, " Policy.Author ", SIGNED_ON, PolicyVersionStatus.APPROVED))
                .as("the record type accepts a case variant of its own maker as checker")
                .doesNotThrowAnyException();

            // The gate does not. In a bank the realistic route to a self-approval is exactly this
            // — the same directory identity arriving through a channel that trims or cases it
            // differently — and not somebody typing their own name into the checker box.
            TransitionResult result = MakerCheckerGate.approve(
                versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                new ApprovalRecord(" Policy.Author ", SIGNED_ON, ""));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.SELF_APPROVAL);
        }

        @Test
        @DisplayName("APPROVED without a signature is refused")
        void approvalNeedsARecord() {
            TransitionResult result = MakerCheckerGate.transition(new TransitionRequest(
                versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                PolicyVersionStatus.APPROVED, null, null, ""));

            assertThat(result.refusal()).isEqualTo(TransitionRefusal.MISSING_APPROVAL_RECORD);
            assertThat(result.detail()).contains("FR-210");
        }

        @Test
        @DisplayName("a second checker cannot sign a version routed to a first")
        void checkerConflictRefused() {
            // Both readings need a human: either the routing was bypassed, or a first approval
            // already relied on would be overwritten by a second.
            PolicyVersion routed = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, CHECKER, null, PolicyVersionStatus.PENDING_APPROVAL);

            TransitionResult result = MakerCheckerGate.approve(routed,
                new ApprovalRecord("someone.else", SIGNED_ON, ""));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.CHECKER_CONFLICT);
            assertThat(result.detail()).contains(CHECKER).contains("someone.else");

            // The assigned checker signing is not a conflict, including through a case variant.
            assertThat(MakerCheckerGate.approve(routed,
                new ApprovalRecord(CHECKER.toUpperCase(Locale.ROOT), SIGNED_ON, ""))
                .isAllowed())
                .as("the routed checker, same identity, different case")
                .isTrue();
        }

        @Test
        @DisplayName("a decline clears the assignment so the next checker is not a conflict")
        void declineThenApproveBySomeoneElse() {
            // The regression this protects. Were the declining checker left on the version, the
            // ordinary consequence of a decline — reassignment to a second checker — would come
            // back as CHECKER_CONFLICT, and a control whose normal path is refused gets worked
            // around. Per 04 § 2.12 the checker column is the APPROVER, not the queue it sat in.
            PolicyVersion routed = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, CHECKER, null, PolicyVersionStatus.PENDING_APPROVAL);

            TransitionResult declined =
                MakerCheckerGate.returnToMaker(routed, "cost_function attribute missing, FR-203");
            assertThat(declined.isAllowed()).isTrue();
            assertThat(declined.versionOrThrow().status()).isEqualTo(PolicyVersionStatus.DRAFT);
            assertThat(declined.versionOrThrow().checker())
                .as("the assignment ends with the decline")
                .isNull();
            assertThat(declined.detail())
                .as("who declined and why belongs on the transition record")
                .contains("cost_function attribute missing");

            PolicyVersion resubmitted =
                MakerCheckerGate.submitForApproval(declined.versionOrThrow()).versionOrThrow();
            assertThat(MakerCheckerGate.approve(resubmitted,
                new ApprovalRecord("second.checker", SIGNED_ON, "")).isAllowed())
                .as("a different checker after a decline")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("supersession replaces; it does not delete")
    class Supersession {

        @Test
        @DisplayName("an effective version is superseded by a named successor")
        void supersedeNamingSuccessor() {
            TransitionResult result = MakerCheckerGate.supersede(
                versionIn(PolicyVersionStatus.EFFECTIVE), "FEE-2027.2");

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.versionOrThrow().status()).isEqualTo(PolicyVersionStatus.SUPERSEDED);
            assertThat(result.versionOrThrow().isEffectiveOn(EFFECTIVE_FROM))
                .as("still resolvable, or a closed period cannot replay (DT-1)")
                .isTrue();
            assertThat(result.detail()).contains("FEE-2027.2");
            // Handed back as a value and not only as a substring: PolicyVersion has no
            // superseded_by field, so whoever persists this transition is the only party that can
            // record which version took over, and a pointer that lived only in the detail string
            // would be lost the moment the row was written.
            assertThat(result.successorVersionId())
                .as("the successor must survive the transition as data")
                .isEqualTo("FEE-2027.2");
        }

        @Test
        @DisplayName("an approved version may be superseded before it ever takes effect")
        void supersedeFromApproved() {
            // A version signed in March and replaced before 1 April. Legitimate, and it must not
            // be forced through EFFECTIVE first just to be retired.
            assertThat(MakerCheckerGate
                .supersede(versionIn(PolicyVersionStatus.APPROVED), "FEE-2027.2").isAllowed())
                .isTrue();
        }

        @Test
        @DisplayName("SUPERSEDED with nothing in its place is refused")
        void successorIsMandatory() {
            TransitionResult result =
                MakerCheckerGate.supersede(versionIn(PolicyVersionStatus.EFFECTIVE), "   ");
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.MISSING_SUCCESSOR);
            assertThat(result.detail()).contains("resolve against nothing");
        }

        @Test
        @DisplayName("a version cannot supersede itself")
        void selfSuccessionRefused() {
            // The shape a copy-paste in a migration takes, and its effect is worse than a no-op:
            // the version leaves the effective set and the only pointer to a replacement points
            // back at itself.
            TransitionResult result =
                MakerCheckerGate.supersede(versionIn(PolicyVersionStatus.EFFECTIVE), "FEE-2027.1");
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.SELF_SUCCESSION);
        }

        @Test
        @DisplayName("a padded id does not get a version past the self-succession check")
        void selfSuccessionAcrossPadding() {
            // PolicyVersion refuses a blank id but does not strip one, so an id that arrived from
            // a fixed-width extract as "FEE-2027.1 " would slip a raw string comparison against
            // "FEE-2027.1" — and the migration copy-paste this refusal is written for is exactly
            // the case where the two spellings come from different columns.
            PolicyVersion padded = new PolicyVersion(
                "FEE-2027.1 ", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, CHECKER, SIGNED_ON, PolicyVersionStatus.EFFECTIVE);
            assertThat(MakerCheckerGate.supersede(padded, "FEE-2027.1").refusal())
                .isEqualTo(TransitionRefusal.SELF_SUCCESSION);
        }

        @Test
        @DisplayName("a draft was never in force, so nothing replaced it")
        void unapprovedCannotBeSuperseded() {
            // Not merely a missing edge — the vocabulary forbids it. isApproved() counts
            // SUPERSEDED as approved, so PolicyVersion demands a checker on it, and a draft has
            // none. The refusal names that rather than pointing at the table.
            for (PolicyVersionStatus unapproved : List.of(
                PolicyVersionStatus.DRAFT, PolicyVersionStatus.PENDING_APPROVAL)) {
                TransitionResult result =
                    MakerCheckerGate.supersede(versionIn(unapproved), "FEE-2027.2");
                assertThat(result.refusal())
                    .as("%s -> SUPERSEDED", unapproved)
                    .isEqualTo(TransitionRefusal.SUPERSEDING_AN_UNAPPROVED_VERSION);
                assertThat(result.detail()).contains("never in force");
            }
        }

        @Test
        @DisplayName("history is not editable")
        void supersededIsTerminal() {
            // An attempt to move a superseded version is an attempt to rewrite what governed a
            // closed period — the failure a replay detects after the fact.
            TransitionResult result =
                MakerCheckerGate.makeEffective(versionIn(PolicyVersionStatus.SUPERSEDED));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.FROM_TERMINAL_STATE);
        }
    }

    @Nested
    @DisplayName("misplaced or absent evidence")
    class Evidence {

        @Test
        @DisplayName("an approval attached to a submission is refused, not dropped")
        void approvalOnTheWrongEdge() {
            // Accepting the move and discarding the record would destroy the only evidence that a
            // checker looked at the version, and the caller who attached it believes it was kept.
            TransitionResult result = MakerCheckerGate.transition(new TransitionRequest(
                versionIn(PolicyVersionStatus.DRAFT), PolicyVersionStatus.PENDING_APPROVAL,
                signature(), null, ""));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.UNEXPECTED_APPROVAL_RECORD);
            assertThat(result.detail()).contains(CHECKER);
        }

        @Test
        @DisplayName("a successor named on a non-supersession is refused")
        void successorOnTheWrongEdge() {
            TransitionResult result = MakerCheckerGate.transition(new TransitionRequest(
                versionIn(PolicyVersionStatus.APPROVED), PolicyVersionStatus.EFFECTIVE,
                null, "FEE-2027.2", ""));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.UNEXPECTED_SUCCESSOR);
        }

        @Test
        @DisplayName("approval evidence is what a version needs to reach EFFECTIVE")
        void approvalEvidenceIsTheGate() {
            // FR-210's first limb, as a predicate. Tested directly because PolicyVersion's own
            // constructor makes the branch that consumes it unreachable for the approved
            // statuses today — the requirement is on the transition, and a later relaxation of
            // that constructor must not silently open the gate into force.
            assertThat(MakerCheckerGate.carriesApprovalEvidence(versionIn(PolicyVersionStatus.DRAFT)))
                .as("a draft nobody has signed")
                .isFalse();

            PolicyVersion signedDraft = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, CHECKER, SIGNED_ON, PolicyVersionStatus.DRAFT);
            assertThat(MakerCheckerGate.carriesApprovalEvidence(signedDraft))
                .as("checker named and dated")
                .isTrue();

            PolicyVersion namedButUndated = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", EFFECTIVE_FROM,
                MAKER, CHECKER, null, PolicyVersionStatus.PENDING_APPROVAL);
            assertThat(MakerCheckerGate.carriesApprovalEvidence(namedButUndated))
                .as("a checker with no date is a routing, not a sign-off")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("refusals that are neither the hole nor the evidence")
    class OtherRefusals {

        @Test
        @DisplayName("re-approving an approved version is refused, not treated as a no-op")
        void notATransition() {
            // A no-op reported as success is how a second checker comes away believing they
            // signed something that records the first checker's signature.
            TransitionResult result = MakerCheckerGate.approve(
                versionIn(PolicyVersionStatus.APPROVED), signature());
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.NOT_A_TRANSITION);
            assertThat(result.detail()).contains("already APPROVED");
        }

        @Test
        @DisplayName("an approved version cannot be reopened for editing")
        void approvedIsImmutable() {
            // 04 § 2.12 in three words: immutable once approved. A change to an approved version
            // is a new version, or the record of what governed a closed period is destroyed.
            TransitionResult result = MakerCheckerGate.transition(new TransitionRequest(
                versionIn(PolicyVersionStatus.APPROVED), PolicyVersionStatus.DRAFT,
                null, null, "just a small fix"));
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.ILLEGAL_TRANSITION);
            assertThat(result.detail())
                .contains("the only legal moves are")
                .contains("EFFECTIVE");
        }

        @Test
        @DisplayName("a draft cannot be approved without being submitted")
        void draftCannotBeApprovedDirectly() {
            TransitionResult result =
                MakerCheckerGate.approve(versionIn(PolicyVersionStatus.DRAFT), signature());
            assertThat(result.refusal()).isEqualTo(TransitionRefusal.ILLEGAL_TRANSITION);
        }
    }

    @Nested
    @DisplayName("a batch reports every refusal in it")
    class Batch {

        @Test
        @DisplayName("five moves, three refusals, in the order presented")
        void applyAllReportsEveryRefusal() {
            // Hand-derived: (1) submit a draft — allowed. (2) self-approval — refused.
            // (3) draft straight to effective — refused. (4) supersede with no successor —
            // refused. (5) approve a pending version properly — allowed. Two allowed, three
            // refused, and the refusals keep positions 2, 3, 4 of the input.
            List<TransitionRequest> batch = List.of(
                new TransitionRequest(versionIn(PolicyVersionStatus.DRAFT),
                    PolicyVersionStatus.PENDING_APPROVAL, null, null, ""),
                new TransitionRequest(versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                    PolicyVersionStatus.APPROVED, new ApprovalRecord(MAKER, SIGNED_ON, ""), null, ""),
                new TransitionRequest(versionIn(PolicyVersionStatus.DRAFT),
                    PolicyVersionStatus.EFFECTIVE, null, null, ""),
                new TransitionRequest(versionIn(PolicyVersionStatus.EFFECTIVE),
                    PolicyVersionStatus.SUPERSEDED, null, null, ""),
                new TransitionRequest(versionIn(PolicyVersionStatus.PENDING_APPROVAL),
                    PolicyVersionStatus.APPROVED, signature(), null, ""));

            List<TransitionResult> results = MakerCheckerGate.applyAll(batch);

            assertThat(results).hasSize(5);
            assertThat(results.stream().filter(TransitionResult::isAllowed).count())
                .as("submissions and the one good approval")
                .isEqualTo(2L);
            assertThat(TransitionResult.refusalsIn(results))
                .extracting(TransitionResult::refusal)
                .containsExactly(
                    TransitionRefusal.SELF_APPROVAL,
                    TransitionRefusal.SKIPS_APPROVAL,
                    TransitionRefusal.MISSING_SUCCESSOR);
        }

        @Test
        @DisplayName("no status pair escapes as an exception, with or without evidence attached")
        void nothingThrowsForAPolicyCondition() {
            // The property the batch path depends on: 25 pairs x {no evidence, both kinds of
            // evidence} = 50 calls, every one of which must come back as a value. A single throw
            // among them would abandon the rest of a quarter-end batch.
            for (PolicyVersionStatus from : PolicyVersionStatus.values()) {
                for (PolicyVersionStatus to : PolicyVersionStatus.values()) {
                    PolicyVersion version = versionIn(from);
                    for (boolean withEvidence : List.of(false, true)) {
                        TransitionRequest request = new TransitionRequest(version, to,
                            withEvidence ? signature() : null,
                            withEvidence ? "FEE-2027.2" : null, "");
                        TransitionResult result = MakerCheckerGate.transition(request);
                        assertThat(result).as("%s -> %s", from, to).isNotNull();
                        if (result.isAllowed()) {
                            // An allowed move is always an edge of the stated table, and always
                            // lands where it said it would.
                            assertThat(MakerCheckerGate.isLegalTransition(from, to))
                                .as("allowed %s -> %s must be an edge", from, to)
                                .isTrue();
                            assertThat(result.versionOrThrow().status()).isEqualTo(to);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a batch does not chain one version's moves")
        void batchDoesNotChain() {
            // Submit-then-approve in one list is refused at the second step, deliberately: the
            // second request carries the version as its caller supplied it. Chaining would make
            // this method impose an order on a list somebody else assembled, and the point of
            // maker-checker is that submission and approval are two acts by two people.
            PolicyVersion draft = versionIn(PolicyVersionStatus.DRAFT);
            List<TransitionResult> results = MakerCheckerGate.applyAll(List.of(
                new TransitionRequest(draft, PolicyVersionStatus.PENDING_APPROVAL, null, null, ""),
                new TransitionRequest(draft, PolicyVersionStatus.APPROVED, signature(), null, "")));

            assertThat(results.get(0).isAllowed()).isTrue();
            assertThat(results.get(1).refusal()).isEqualTo(TransitionRefusal.ILLEGAL_TRANSITION);
        }
    }
}
