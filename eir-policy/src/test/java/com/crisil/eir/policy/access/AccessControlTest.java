package com.crisil.eir.policy.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-906's role model, and 07 § 7's segregation of duties.
 *
 * <p><b>Where the expected values come from.</b> Nowhere but the specification and this file. Every
 * capability set asserted below is read off {@link Role}'s own declaration in the source and off
 * 02 § 3's journeys, and every refusal reason is named as a constant rather than matched on a
 * message the implementation happens to produce. Nothing here runs the gate to discover what it
 * ought to say.
 *
 * <p><b>The failing inputs, named, because a control that cannot fail is worse than an absent
 * one.</b> Each of these is a state no type in the package prevents:
 *
 * <ul>
 *   <li><b>{@code ROLE_LACKS_CAPABILITY}:</b> {@code reader.only} as {@link Role#AUDITOR} attempting
 *       {@code START_RUN}. The role holds only {@link Capability#READ_FIGURES}.</li>
 *   <li><b>{@code SEGREGATION_OF_DUTIES}:</b> {@code ops.superuser} granted
 *       {@link Role#BATCH_OPERATOR} <em>and</em> {@link Role#APPROVER}, approving an acceptance over
 *       a run it started itself. This is the input that would be impossible if segregation were read
 *       off the role table instead of the identity — no single role holds both halves. It is the
 *       whole reason {@link Principal} holds a <em>set</em> of roles.</li>
 *   <li><b>The case-folded variant of the same:</b> the run recorded {@code "Ops.Superuser"} and the
 *       caller asserts {@code " ops.superuser "}. A comparison by {@code equals} would permit it.
 *       This is the exact defect {@code FourEyes} was extracted to close, and the reason this class
 *       borrows the comparison rather than spelling it a fifth time.</li>
 *   <li><b>{@code NO_SUCH_CAPABILITY}:</b> {@code action=OVERRIDE_RATE} — 07 § 5's permanently
 *       refused act, attempted by an identity that holds every role in the model.</li>
 *   <li><b>{@code UNKNOWN_IDENTITY}:</b> {@code intruder} against a register that does not hold it,
 *       proving there is no read-only default.</li>
 *   <li><b>{@code NO_IDENTITY_ASSERTED}:</b> a blank header.</li>
 * </ul>
 */
class AccessControlTest {

    private static final String READER = "reader.only";
    private static final String OPERATOR = "batch.operator";
    private static final String CONTROLLER = "financial.controller";
    private static final String DRAFTER = "product.control";
    private static final String APPROVER = "policy.approver";
    /** The composed grant: BATCH_OPERATOR + APPROVER on one identity. */
    private static final String SUPERUSER = "Ops.Superuser";

    /**
     * A register shaped like a real deployment, including the one grant that should not exist.
     *
     * <p>{@code ops.superuser} is in here deliberately. Without it the segregation refusal would
     * have no reachable input and would be the eighteenth control in this repository that cannot
     * fail.
     */
    private static RoleRegister register() {
        return RoleRegister.of(List.of(
            Principal.of(READER, Role.AUDITOR),
            Principal.of(OPERATOR, Role.BATCH_OPERATOR),
            Principal.of(CONTROLLER, Role.FINANCIAL_CONTROLLER),
            Principal.of(DRAFTER, Role.PRODUCT_CONTROL),
            Principal.of(APPROVER, Role.APPROVER),
            Principal.of(SUPERUSER, Role.BATCH_OPERATOR, Role.APPROVER)));
    }

    private static AccessDecision decide(String identity, String action) {
        return AccessControl.decide(register(), AuthorisationRequest.of(identity, action));
    }

    private static AccessDecision decideOverRun(
        String identity, String action, String runStartedBy) {
        return AccessControl.decide(register(),
            AuthorisationRequest.overRunStartedBy(identity, action, runStartedBy));
    }

    @Nested
    @DisplayName("the four distinctions FR-906 draws")
    class TheRoleModel {

        @Test
        @DisplayName("the read-only role may read and may not start a run")
        void readOnlyMayOnlyRead() {
            assertThat(decide(READER, "READ_FIGURES").permitted())
                .as("FR-808's trace is self-service; an auditor who cannot read it has no journey")
                .isTrue();

            AccessDecision refused = decide(READER, "START_RUN");
            assertThat(refused.permitted()).isFalse();
            assertThat(refused.refusal())
                .as("a missing grant, not a segregation breach — the two are closed differently")
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
            assertThat(refused.isSegregationBreach()).isFalse();
        }

        @Test
        @DisplayName("the batch operator may start a run and may not close or approve")
        void theBatchOperatorMayOnlyRun() {
            assertThat(decide(OPERATOR, "START_RUN").permitted()).isTrue();

            assertThat(decide(OPERATOR, "CLOSE_PERIOD").refusal())
                .as("a scheduler that could close a period would be a close nobody signed")
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
            assertThat(decide(OPERATOR, "APPROVE_EXCEPTION_ACCEPTANCE").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
            assertThat(decide(OPERATOR, "APPROVE_POLICY_VERSION").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
        }

        @Test
        @DisplayName("the financial controller runs, proposes and closes, but never approves")
        void theControllerProposesAndClosesButDoesNotApprove() {
            // 02 § 3.1 steps 2, 3 and 7: trigger the run, work the queue, approve and lock.
            assertThat(decide(CONTROLLER, "START_RUN").permitted()).isTrue();
            assertThat(decide(CONTROLLER, "PROPOSE_EXCEPTION_ACCEPTANCE").permitted()).isTrue();
            assertThat(decide(CONTROLLER, "CLOSE_PERIOD").permitted()).isTrue();

            // 07 § 4.2's last bullet: the person who concluded the period should close over a
            // standing exception is not the person who signs for that conclusion.
            assertThat(decide(CONTROLLER, "APPROVE_EXCEPTION_ACCEPTANCE").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
        }

        @Test
        @DisplayName("product control drafts and cannot approve its own kind of artefact")
        void productControlDraftsOnly() {
            assertThat(decide(DRAFTER, "DRAFT_POLICY_VERSION").permitted()).isTrue();
            assertThat(decide(DRAFTER, "APPROVE_POLICY_VERSION").refusal())
                .as("02 § 3.2 step 3 submits for a checker; the drafter is not the checker")
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
        }

        @Test
        @DisplayName("the approver approves and holds no maker capability at all")
        void theApproverHoldsNoMakerCapability() {
            assertThat(decide(APPROVER, "APPROVE_POLICY_VERSION").permitted()).isTrue();

            assertThat(decide(APPROVER, "DRAFT_POLICY_VERSION").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
            assertThat(decide(APPROVER, "START_RUN").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
            assertThat(decide(APPROVER, "PROPOSE_EXCEPTION_ACCEPTANCE").refusal())
                .isEqualTo(AccessRefusal.ROLE_LACKS_CAPABILITY);
        }
    }

    @Nested
    @DisplayName("segregation of duties: enforced, not advisory (07 § 7)")
    class SegregationOfDuties {

        @Test
        @DisplayName("the identity that started the run may not approve closing over its exceptions")
        void theRunMakerMayNotApproveTheAcceptanceOverItsOwnExceptions() {
            AccessDecision refused = decideOverRun(
                SUPERUSER, "APPROVE_EXCEPTION_ACCEPTANCE", SUPERUSER);

            assertThat(refused.permitted())
                .as("this is the hole FR-906 left open: the caller typed both identities")
                .isFalse();
            assertThat(refused.refusal()).isEqualTo(AccessRefusal.SEGREGATION_OF_DUTIES);
            assertThat(refused.isSegregationBreach())
                .as("a segregation breach closed by widening the grant is the control being"
                    + " removed by the ticket that reported it")
                .isTrue();
            // The grant is not the problem, so the refusal must not read as a missing grant.
            assertThat(refused.resolvedPrincipal()).isPresent();
            assertThat(refused.detail()).contains("started the run");
        }

        @Test
        @DisplayName("the same breach under a different capitalisation is still refused")
        void caseAndWhitespaceDoNotDefeatTheComparison() {
            // THE FAILING INPUT, and getting it right matters. The comparison is between the
            // REGISTER's spelling of the identity — "Ops.Superuser", as the directory gave it — and
            // the run record's. So the spelling that has to differ is runStartedBy's, not the
            // header's: an earlier draft of this test varied only the asserted identity, and the
            // register resolved it back to "Ops.Superuser" before the comparison, so a mutation
            // replacing FourEyes.sameIdentity with String.equals passed it. That is the exact shape
            // of a control that cannot fail.
            //
            // A plain equals — the fourth spelling this codebase found, the one guarding the
            // routing table — permits both of these.
            assertThat(decideOverRun(SUPERUSER, "APPROVE_EXCEPTION_ACCEPTANCE", "ops.superuser")
                .refusal())
                .as("the run record lower-cased the identity the register spells 'Ops.Superuser'")
                .isEqualTo(AccessRefusal.SEGREGATION_OF_DUTIES);
            assertThat(decideOverRun(SUPERUSER, "APPROVE_EXCEPTION_ACCEPTANCE", " Ops.Superuser ")
                .refusal())
                .as("a migration that padded the run record's identity column")
                .isEqualTo(AccessRefusal.SEGREGATION_OF_DUTIES);

            // And the asserted identity resolves through the register on the way in, so both sides
            // of the pair can vary at once.
            assertThat(decideOverRun(
                " OPS.SUPERUSER ", "APPROVE_EXCEPTION_ACCEPTANCE", "ops.superuser").refusal())
                .isEqualTo(AccessRefusal.SEGREGATION_OF_DUTIES);
        }

        @Test
        @DisplayName("a different person approving the same run's exceptions is permitted")
        void asecondPersonMayApprove() {
            AccessDecision permitted = decideOverRun(
                APPROVER, "APPROVE_EXCEPTION_ACCEPTANCE", OPERATOR);

            assertThat(permitted.permitted()).isTrue();
            assertThat(permitted.segregationEvaluated())
                .as("cleared, and the record has to say the check ran")
                .isTrue();
            assertThat(permitted.detail()).contains("segregation cleared against run maker");
        }

        @Test
        @DisplayName("a permit with no run maker to compare against says the check was SKIPPED")
        void aSkippedCheckDoesNotReadAsAClearedOne() {
            AccessDecision permitted = decide(APPROVER, "APPROVE_EXCEPTION_ACCEPTANCE");

            assertThat(permitted.permitted()).isTrue();
            assertThat(permitted.segregationEvaluated())
                .as("an invariant nobody evaluated reads exactly like one that passed; this flag is"
                    + " the only thing that tells them apart")
                .isFalse();
            assertThat(permitted.detail()).contains("SEGREGATION NOT EVALUATED");
        }

        @Test
        @DisplayName("the rule does not reach a close, because 02 § 3.1 has one person do both")
        void closingAPeriodIsNotSubjectToTheRunLimb() {
            // The financial controller triggers the run at step 2 and locks the period at step 7.
            // A rule that refused that would refuse the designed journey. What the close must not
            // rest on is an acceptance the closer signed, and that is a fact about the artefact —
            // ExceptionAcceptance.isSelfApproved, refused by the close gate.
            AccessDecision permitted = decideOverRun(CONTROLLER, "CLOSE_PERIOD", CONTROLLER);

            assertThat(permitted.permitted()).isTrue();
            assertThat(AccessControl.segregationApplies(Capability.CLOSE_PERIOD)).isFalse();
            assertThat(permitted.detail())
                .as("not applicable is not the same as skipped; neither note belongs here")
                .doesNotContain("SEGREGATION NOT EVALUATED");
        }

        @Test
        @DisplayName("the composed grant is reported as a standing exception, not only refused")
        void theGrantItselfIsReported() {
            Map<String, List<String>> exceptions = register().segregationExceptions();

            assertThat(exceptions)
                .as("07 § 4.1: a control is asserted AND reported; refusing the act leaves the"
                    + " grant in place")
                .containsOnlyKeys(SUPERUSER);
            assertThat(exceptions.get(SUPERUSER))
                .containsExactly("APPROVE_EXCEPTION_ACCEPTANCE checks START_RUN");
        }

        @Test
        @DisplayName("a clean register reports no segregation exception at all")
        void acleanRegisterIsClean() {
            RoleRegister clean = RoleRegister.of(
                Principal.of(OPERATOR, Role.BATCH_OPERATOR),
                Principal.of(APPROVER, Role.APPROVER));

            assertThat(clean.segregationExceptions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the automation mandate at the edge (07 § 5)")
    class NoOverrideExists {

        @Test
        @DisplayName("an override is refused as an act that does not exist, not as a missing grant")
        void anOverrideIsNotAMissingGrant() {
            // Attempted by the identity that holds the most in the model. If the answer were
            // "you lack the capability", the sentence would say somebody could hold it.
            AccessDecision refused = decide(SUPERUSER, "OVERRIDE_RATE");

            assertThat(refused.refusal()).isEqualTo(AccessRefusal.NO_SUCH_CAPABILITY);
            assertThat(refused.detail())
                .contains("not an act this engine has")
                .contains("no grant would create it");
            assertThat(refused.resolvedCapability()).isEmpty();
        }

        @Test
        @DisplayName("the act is refused before the identity is even resolved")
        void theOverrideIsRefusedEvenForAnUnknownCaller() {
            // Ordering matters: answered after identity resolution, an unknown caller would be told
            // their identity was the problem, which invites them to fix the identity and try again
            // for an act that will never exist.
            AccessDecision refused = decide("nobody.at.all", "OVERRIDE_RATE");

            assertThat(refused.refusal()).isEqualTo(AccessRefusal.NO_SUCH_CAPABILITY);
        }

        @Test
        @DisplayName("no capability in the model can override a computed figure")
        void theEnumHoldsNoOverride() {
            // The structural guard, and it can fail: adding an OVERRIDE_* or REOPEN_* capability
            // breaks this assertion rather than quietly widening the model.
            for (Capability capability : Capability.values()) {
                assertThat(capability.name())
                    .as("07 § 5: no endpoint permits overriding a computed rate or balance")
                    .doesNotContain("OVERRIDE")
                    .doesNotContain("REOPEN")
                    .doesNotContain("DELETE")
                    .doesNotContain("ADJUST");
            }
        }
    }

    @Nested
    @DisplayName("resolution refusals, which say nothing about entitlement")
    class Resolution {

        @Test
        @DisplayName("no identity at all is refused, and there is no anonymous role")
        void anAbsentIdentityIsRefused() {
            assertThat(decide(null, "READ_FIGURES").refusal())
                .isEqualTo(AccessRefusal.NO_IDENTITY_ASSERTED);
            assertThat(decide("   ", "READ_FIGURES").refusal())
                .as("a blank header is an absent one, not an identity named with spaces")
                .isEqualTo(AccessRefusal.NO_IDENTITY_ASSERTED);
        }

        @Test
        @DisplayName("an unknown identity gets no read-only default")
        void anUnknownIdentityIsRefused() {
            AccessDecision refused = decide("intruder", "READ_FIGURES");

            assertThat(refused.refusal()).isEqualTo(AccessRefusal.UNKNOWN_IDENTITY);
            assertThat(refused.refusal().isAboutTheIdentity()).isTrue();
            assertThat(refused.resolvedPrincipal())
                .as("nothing resolved, so nothing may be reported as having resolved")
                .isEmpty();
        }

        @Test
        @DisplayName("the register resolves an identity by FourEyes' key, not by the raw string")
        void resolutionFoldsCase() {
            // A register keyed on the raw string would make "Ops.Superuser" and "ops.superuser" two
            // principals, one of whom could approve the other's work without tripping segregation.
            assertThat(decide("OPS.SUPERUSER", "START_RUN").permitted()).isTrue();
            assertThat(decide("  batch.operator  ", "START_RUN").permitted()).isTrue();
        }

        @Test
        @DisplayName("an action name is resolved case- and hyphen-insensitively")
        void actionNamesAreForgiving() {
            assertThat(decide(OPERATOR, "start-run").permitted()).isTrue();
            assertThat(decide(OPERATOR, "start_run").permitted()).isTrue();
            assertThat(decide(OPERATOR, " Start_Run ").permitted()).isTrue();
        }

        @Test
        @DisplayName("an absent action name is an act that does not exist")
        void ablankActionIsNoAct() {
            assertThat(decide(OPERATOR, "").refusal()).isEqualTo(AccessRefusal.NO_SUCH_CAPABILITY);
        }
    }

    @Nested
    @DisplayName("the gate's own defects throw, unlike its policy answers")
    class Defects {

        @Test
        @DisplayName("a null register throws rather than permitting or refusing everything")
        void anullRegisterThrows() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> AccessControl.decide(null, AuthorisationRequest.of(READER, "READ_FIGURES")));
        }

        @Test
        @DisplayName("a register holding one identity twice is refused at construction")
        void aduplicateGrantIsRefused() {
            // Merging would union the roles, which is a widening nobody approved; last-one-wins
            // would make the grant a function of iteration order.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RoleRegister.of(
                    Principal.of("ops.lead", Role.AUDITOR),
                    Principal.of(" Ops.Lead ", Role.APPROVER)))
                .withMessageContaining("the same identity");
        }

        @Test
        @DisplayName("a decision cannot claim to be permitted and refused at once")
        void acontradictoryDecisionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AccessDecision(true, READER, null, null,
                    AccessRefusal.UNKNOWN_IDENTITY, "detail", false))
                .withMessageContaining("contradiction");

            assertThatExceptionOfType(IllegalArgumentException.class)
                .as("an unexplained 403 is untriageable")
                .isThrownBy(() -> new AccessDecision(false, READER, null, null, null,
                    "detail", false))
                .withMessageContaining("must name its reason");
        }
    }

    @Nested
    @DisplayName("the audit sentence, which is what 07 § 7's access log stores")
    class AuditSentence {

        @Test
        @DisplayName("a refusal names both the attempt and the rule")
        void arefusalNamesBothHalves() {
            String sentence = decideOverRun(
                SUPERUSER, "APPROVE_EXCEPTION_ACCEPTANCE", SUPERUSER).describe();

            assertThat(sentence)
                .startsWith("REFUSED")
                .contains(SUPERUSER)
                .contains("APPROVE_EXCEPTION_ACCEPTANCE")
                .contains("a second person is required");
        }

        @Test
        @DisplayName("an absent identity still produces a readable log line")
        void anabsentIdentityIsStillLoggable() {
            assertThat(decide(null, "READ_FIGURES").describe())
                .contains("[no identity asserted]");
        }

        @Test
        @DisplayName("an unrecognised act is named as such rather than as a null")
        void anunrecognisedActIsNamed() {
            assertThat(decide(READER, "OVERRIDE_RATE").describe())
                .contains("[unrecognised act]");
        }
    }
}
