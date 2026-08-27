package com.crisil.eir.policy.close;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-901's hard gates, one leg at a time and then all at once.
 *
 * <p><b>Where the expected values come from.</b> Nowhere near the code under test. The gate's
 * answers are read off three sources and each is cited at the assertion that uses it: FR-901's one
 * sentence ("all invariants green, all exceptions cleared or accepted with approval, all
 * reconciliations tied"), the seven steps of 02 § 3.1, and 04 § 3's sentence on the exception queue.
 * The two numeric derivations — the residual of an untied reconciliation and the instant at which
 * April 2027 has ended on the earliest clock on earth — are worked by hand in the comments that
 * assert them.
 *
 * <p>The fixture is the April 2027 close: the first period of the ACPIR 20 regime, chosen because
 * it is the one whose figures nobody can compare against a prior period.
 */
class PeriodCloseGateTest {

    private static final LocalDate APRIL_START = LocalDate.of(2027, 4, 1);
    private static final LocalDate APRIL_END = LocalDate.of(2027, 4, 30);
    private static final Instant CLOSING_BEGAN = Instant.parse("2027-05-01T02:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2027-05-05T10:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2027-05-05T09:30:00Z");
    private static final String CONTROLLER = "financial.controller";
    private static final String RUN = "RUN-2027-04";
    private static final String PAYLOAD = "s3://eir-payloads/RUN-2027-04/LN-1.json";

    private static AccountingPeriod aprilClosing() {
        return AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END)
            .startClosing(CLOSING_BEGAN);
    }

    /** A green dashboard: two invariants asserted, both satisfied. */
    private static List<InvariantResult> greenDashboard() {
        return List.of(
            InvariantResult.pass(InvariantId.IC_1, "GCA0 = net cash flow at inception"),
            InvariantResult.pass(InvariantId.SL_1, "sub-ledger ties to GL"));
    }

    /** All four of step 6's reconciliations, each tied on 12,00,000.00 exactly. */
    private static List<ReconciliationTie> tiedReconciliations() {
        List<ReconciliationTie> ties = new ArrayList<>();
        for (ReconciliationScope scope : ReconciliationScope.values()) {
            ties.add(new ReconciliationTie(
                scope, Money.inr("1200000.00"), Money.inr("1200000.00"), "fixture"));
        }
        return List.copyOf(ties);
    }

    private static CloseRequest request(
        AccountingPeriod period, List<InvariantResult> invariants,
        List<ExceptionRecord> exceptions, List<ExceptionAcceptance> acceptances,
        List<ReconciliationTie> reconciliations) {
        return new CloseRequest(period, CONTROLLER, CLOSED_AT, CUTOFF, invariants, exceptions,
            acceptances, reconciliations);
    }

    /** The green close: everything FR-901 asks for, present and satisfied. */
    private static CloseRequest greenRequest() {
        return request(aprilClosing(), greenDashboard(), List.of(), List.of(),
            tiedReconciliations());
    }

    private static ExceptionRecord unmappedFee(String contractId) {
        return ExceptionRecord.raise(contractId, RUN, ExceptionCategory.UNMAPPED_FEE_CODE,
            "fee code PROC-XX is not mapped by FEE-2027.1", PAYLOAD);
    }

    private static ExceptionAcceptance acceptance(
        String contractId, String acceptedBy, String approvedBy) {
        return new ExceptionAcceptance(contractId, ExceptionCategory.UNMAPPED_FEE_CODE,
            acceptedBy, approvedBy, "immaterial: 1,240.00 of fee on a 2.4 crore book",
            Instant.parse("2027-05-04T11:00:00Z"));
    }

    @Nested
    @DisplayName("step 7: approve and lock")
    class ApproveAndLock {

        @Test
        @DisplayName("a green close is permitted and produces the attested CLOSED row")
        void greenClosePermitted() {
            CloseDecision decision = PeriodCloseGate.evaluate(greenRequest());

            assertThat(decision.permitted())
                .as("all invariants green, no exceptions, all four reconciliations tied (FR-901)")
                .isTrue();
            assertThat(decision.refusals()).isEmpty();
            AccountingPeriod closed = decision.closedPeriod();
            assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
            assertThat(closed.closedBy()).isEqualTo(CONTROLLER);
            assertThat(closed.closedAt()).isEqualTo(CLOSED_AT);
            assertThat(closed.versionCutoffAt())
                .as("FR-902's third column: the system-time boundary a replay reads as at (04 § 5)")
                .isEqualTo(CUTOFF);
        }

        @Test
        @DisplayName("an OPEN period is refused alone, even with everything else wrong")
        void openPeriodRefusedAlone() {
            // The deliberate exception to reporting everything: the evidence is not what needs
            // fixing, the period the caller is pointing at is.
            CloseRequest wrongPeriod = new CloseRequest(
                AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END),
                null, null, null, List.of(), List.of(), List.of(), List.of());

            CloseDecision decision = PeriodCloseGate.evaluate(wrongPeriod);

            assertThat(decision.isRefused()).isTrue();
            assertThat(decision.refusalCount())
                .as("one refusal, not the six the evidence would otherwise produce")
                .isEqualTo(1);
            assertThat(decision.refusedFor(CloseGateRefusal.PERIOD_NOT_IN_CLOSING)).isTrue();
        }

        @Test
        @DisplayName("a CLOSED period is refused: a second close overwrites the attestation")
        void closedPeriodRefused() {
            AccountingPeriod closed = new AccountingPeriod(
                202704, "FY2027-28", APRIL_START, APRIL_END, PeriodStatus.CLOSED,
                CLOSING_BEGAN, CLOSED_AT, CONTROLLER, CUTOFF);

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                closed, greenDashboard(), List.of(), List.of(), tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.PERIOD_ALREADY_CLOSED)).isTrue();
            assertThat(decision.refusals().get(0).detail())
                .as("the reason names what a second close would destroy")
                .contains("version_cutoff_at");
        }

        @Test
        @DisplayName("no signatory and no cutoff are two refusals, not one")
        void attestationRefusals() {
            CloseRequest unattested = new CloseRequest(
                aprilClosing(), "  ", CLOSED_AT, null, greenDashboard(), List.of(), List.of(),
                tiedReconciliations());

            CloseDecision decision = PeriodCloseGate.evaluate(unattested);

            assertThat(decision.reasons()).containsExactlyInAnyOrder(
                CloseGateRefusal.CLOSURE_NOT_ATTESTED, CloseGateRefusal.MISSING_VERSION_CUTOFF);
        }

        @Test
        @DisplayName("a cutoff later than the close is refused: a replay would read past the lock")
        void cutoffAfterClose() {
            CloseRequest lateCutoff = new CloseRequest(
                aprilClosing(), CONTROLLER, CLOSED_AT, CLOSED_AT.plusSeconds(1),
                greenDashboard(), List.of(), List.of(), tiedReconciliations());

            assertThat(PeriodCloseGate.evaluate(lateCutoff)
                .refusedFor(CloseGateRefusal.VERSION_CUTOFF_AFTER_CLOSE)).isTrue();
        }

        @Test
        @DisplayName("closing April before April has ended anywhere on earth is refused")
        void closePredatesPeriodEnd() {
            // Derivation, by hand: the earliest real-world clock to enter a date is UTC+14, so
            // 2027-04-30 has ended somewhere from 2027-05-01T00:00+14:00, which is
            // 2027-05-01T00:00 minus 14 hours = 2027-04-30T10:00Z. A close stamped 2027-04-20 is
            // ten days before that and cannot be inside any reading of April.
            AccountingPeriod startedEarly = AccountingPeriod
                .open(202704, "FY2027-28", APRIL_START, APRIL_END)
                .startClosing(Instant.parse("2027-04-19T00:00:00Z"));
            CloseRequest early = new CloseRequest(
                startedEarly, CONTROLLER, Instant.parse("2027-04-20T00:00:00Z"),
                Instant.parse("2027-04-19T12:00:00Z"), greenDashboard(), List.of(), List.of(),
                tiedReconciliations());

            CloseDecision decision = PeriodCloseGate.evaluate(early);

            assertThat(decision.refusedFor(CloseGateRefusal.CLOSE_PREDATES_PERIOD_END)).isTrue();
            assertThat(decision.refusals().stream()
                .filter(refusal -> refusal.reason() == CloseGateRefusal.CLOSE_PREDATES_PERIOD_END)
                .findFirst().orElseThrow().detail())
                .as("the hand-derived boundary appears in the refusal")
                .contains("2027-04-30T10:00:00Z");
        }

        @Test
        @DisplayName("a close on 1 May IST is not refused, though it is 30 April in UTC")
        void noFalseRefusalAcrossTheDateBoundary() {
            // 2027-05-01T04:30+05:30 is 2027-04-30T23:00Z — still April by a UTC clock, and a
            // perfectly ordinary time for an Indian bank to lock April. Refusing it would be a
            // false refusal, and a hard gate that produces false refusals gets argued down.
            AccountingPeriod closingOvernight = AccountingPeriod
                .open(202704, "FY2027-28", APRIL_START, APRIL_END)
                .startClosing(Instant.parse("2027-04-30T18:00:00Z"));
            CloseRequest istMorning = new CloseRequest(
                closingOvernight, CONTROLLER, Instant.parse("2027-04-30T23:00:00Z"),
                Instant.parse("2027-04-30T22:00:00Z"), greenDashboard(), List.of(), List.of(),
                tiedReconciliations());

            assertThat(PeriodCloseGate.evaluate(istMorning).permitted()).isTrue();
        }

        @Test
        @DisplayName("a close stamped before the close began is a refusal, not a thrown exception")
        void closePredatesItsOwnStart() {
            // The gate's contract is that it never throws for a data condition. AccountingPeriod's
            // constructor does throw on this one, so the gate has to catch it first — otherwise a
            // clock skew would hide the other five things wrong with the close.
            CloseRequest skewed = new CloseRequest(
                aprilClosing(), CONTROLLER, CLOSING_BEGAN.minusSeconds(3600),
                CLOSING_BEGAN.minusSeconds(7200), greenDashboard(), List.of(), List.of(),
                tiedReconciliations());

            CloseDecision decision = PeriodCloseGate.evaluate(skewed);

            assertThat(decision.refusedFor(CloseGateRefusal.CLOSE_PREDATES_ITS_OWN_START))
                .isTrue();
        }
    }

    @Nested
    @DisplayName("step 4: any red blocks the close")
    class InvariantDashboard {

        @Test
        @DisplayName("an empty dashboard is not a green one")
        void emptyDashboardRefused() {
            // The specific failure a "no red found" gate would pass: the sweep never ran, so
            // nothing is known about any figure.
            CloseRequest noResults = request(
                aprilClosing(), List.of(), List.of(), List.of(), tiedReconciliations());

            CloseDecision decision = PeriodCloseGate.evaluate(noResults);

            assertThat(decision.refusedFor(CloseGateRefusal.NO_INVARIANT_RESULTS)).isTrue();
            assertThat(decision.refusalCount())
                .as("one refusal: an absent dashboard is reported once, not once per invariant")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("one red invariant blocks, and the refusal carries its id and deviation")
        void redInvariantBlocks() {
            List<InvariantResult> dashboard = List.of(
                InvariantResult.pass(InvariantId.IC_1, "GCA0 ties"),
                InvariantResult.fail(InvariantId.S3_1, "Stage 3 four-way is out by 1,240.50",
                    new BigDecimal("1240.50")));

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), dashboard, List.of(), List.of(), tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.INVARIANT_BREACH)).isTrue();
            assertThat(decision.refusals().get(0).detail())
                .as("an operator gets the id, the statement and the deviation without a lookup")
                .contains("S3_1", "1240.50");
        }

        @Test
        @DisplayName("an invariant asserted twice, passing once and failing once, still blocks")
        void disagreeingResultsUnderOneIdStillBlock() {
            // The defect InvariantResult.conjunction's javadoc records finding three times: two
            // results under one id, one of which passes. Collapsing before reading is what stops
            // list position deciding the close.
            List<InvariantResult> dashboard = List.of(
                InvariantResult.pass(InvariantId.PC_1, "classified-fee route: no penal charge"),
                InvariantResult.fail(InvariantId.PC_1, "billed-schedule route: 500.00 of penal"
                    + " charge in the EIR stream", new BigDecimal("500.00")));

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), dashboard, List.of(), List.of(), tiedReconciliations()));

            assertThat(decision.isRefused())
                .as("a pass listed first must not make PC-1 green")
                .isTrue();
            assertThat(decision.refusals())
                .as("one line per invariant, not one per assertion")
                .hasSize(1);
        }
    }

    @Nested
    @DisplayName("step 3 read at step 7: cleared, or accepted with approval")
    class ExceptionQueueGate {

        @Test
        @DisplayName("an unworked exception blocks the close (04 § 3)")
        void openExceptionBlocks() {
            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(unmappedFee("LN-1")), List.of(),
                tiedReconciliations()));

            assertThat(decision.refusedFor(
                CloseGateRefusal.EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED)).isTrue();
        }

        @Test
        @DisplayName("a RESOLVED exception is cleared and needs no signature")
        void resolvedExceptionClears() {
            ExceptionRecord fixed = unmappedFee("LN-1")
                .resolve("fee.master.owner", "PROC-XX mapped in FEE-2027.2");

            assertThat(PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(fixed), List.of(),
                tiedReconciliations())).permitted())
                .as("the defect was fixed; nobody had to sign for closing over it")
                .isTrue();
        }

        @Test
        @DisplayName("an accepted row with no acceptance artefact is refused: one column, one name")
        void acceptedRowWithoutEvidence() {
            ExceptionRecord accepted = unmappedFee("LN-1")
                .acceptWithApproval("ops.lead", "immaterial, closing over it");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(accepted), List.of(),
                tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.UNEVIDENCED_ACCEPTANCE))
                .as("resolved_by holds one name, so a proper approval and a self-approval are"
                    + " indistinguishable on the row alone")
                .isTrue();
        }

        @Test
        @DisplayName("a two-signature acceptance lets the close proceed over a defect that stands")
        void fourEyedAcceptancePermits() {
            ExceptionRecord accepted = unmappedFee("LN-1")
                .acceptWithApproval("product.control.head", "immaterial, closing over it");
            ExceptionAcceptance evidence =
                acceptance("LN-1", "ops.lead", "product.control.head");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(accepted), List.of(evidence),
                tiedReconciliations()));

            assertThat(decision.permitted())
                .as("04 § 3: unresolved exceptions block the close unless explicitly accepted"
                    + " with approval")
                .isTrue();
        }

        @Test
        @DisplayName("an exception accepted by the person who put it forward is not accepted")
        void selfApprovedAcceptanceRefused() {
            // The identity comparison is FourEyes.isSelfApproval, which strips and case-folds:
            // 'ops.lead' and ' Ops.Lead ' are one person. A plain equals would accept this, and
            // the one place in this codebase where the rule was written a fourth time with a
            // plain equals was the one that let a maker sign their own artefact.
            ExceptionRecord accepted = unmappedFee("LN-1")
                .acceptWithApproval(" Ops.Lead ", "immaterial, closing over it");
            ExceptionAcceptance selfApproved =
                acceptance("LN-1", "ops.lead", " Ops.Lead ");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(accepted), List.of(selfApproved),
                tiedReconciliations()));

            assertThat(decision.reasons())
                .containsExactly(CloseGateRefusal.SELF_APPROVED_ACCEPTANCE);
        }

        @Test
        @DisplayName("an acceptance approved by somebody the row does not name is a conflict")
        void signatoryConflict() {
            ExceptionRecord accepted = unmappedFee("LN-1")
                .acceptWithApproval("product.control.head", "immaterial");
            ExceptionAcceptance elsewhere =
                acceptance("LN-1", "ops.lead", "group.cfo");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(accepted), List.of(elsewhere),
                tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.ACCEPTANCE_SIGNATORY_CONFLICT))
                .as("either the artefact was filed against the wrong exception, or two people"
                    + " believe they signed and only one name is stored")
                .isTrue();
        }

        @Test
        @DisplayName("an acceptance on file against a still-OPEN row is refused")
        void acceptanceNotOnTheQueue() {
            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(unmappedFee("LN-1")),
                List.of(acceptance("LN-1", "ops.lead", "product.control.head")),
                tiedReconciliations()));

            assertThat(decision.refusedFor(
                CloseGateRefusal.ACCEPTANCE_NOT_RECORDED_ON_THE_QUEUE))
                .as("every other reader of the queue would still see a live blocker")
                .isTrue();
        }

        @Test
        @DisplayName("an acceptance for an exception this queue never raised is refused")
        void acceptanceWithoutAnException() {
            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(),
                List.of(acceptance("LN-99", "ops.lead", "product.control.head")),
                tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.ACCEPTANCE_WITHOUT_AN_EXCEPTION))
                .as("an acceptance list carried over from another run cannot authorise this close")
                .isTrue();
        }

        @Test
        @DisplayName("an acceptance for a RESOLVED exception is a signature nobody needed")
        void acceptanceForAResolvedException() {
            ExceptionRecord fixed = unmappedFee("LN-1")
                .resolve("fee.master.owner", "PROC-XX mapped in FEE-2027.2");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(fixed),
                List.of(acceptance("LN-1", "ops.lead", "product.control.head")),
                tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.ACCEPTANCE_WITHOUT_AN_EXCEPTION))
                .isTrue();
            assertThat(decision.refusals().get(0).detail()).contains("RESOLVED");
        }

        @Test
        @DisplayName("acceptance of one category does not accept another on the same contract")
        void categoryIsPartOfTheMatch() {
            // A contract with an unmapped fee code AND a missing cost_function has two defects
            // needing two decisions: the fee code may be immaterial while the cost function is
            // not. One signature must not clear both.
            ExceptionRecord accepted = ExceptionRecord.raise(
                "LN-1", RUN, ExceptionCategory.MISSING_COST_FUNCTION,
                "no cost_function on an INTEGRAL cost posting", PAYLOAD)
                .acceptWithApproval("product.control.head", "immaterial");

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(accepted),
                List.of(acceptance("LN-1", "ops.lead", "product.control.head")),
                tiedReconciliations()));

            assertThat(decision.reasons()).containsExactlyInAnyOrder(
                CloseGateRefusal.UNEVIDENCED_ACCEPTANCE,
                CloseGateRefusal.ACCEPTANCE_WITHOUT_AN_EXCEPTION);
        }

        @Test
        @DisplayName("the live queue can be presented directly, as a snapshot")
        void presentingTheLiveQueue() {
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(unmappedFee("LN-1"));

            CloseDecision decision = PeriodCloseGate.evaluate(CloseRequest.presenting(
                aprilClosing(), CONTROLLER, CLOSED_AT, CUTOFF, greenDashboard(), queue,
                List.of(), tiedReconciliations()));

            assertThat(decision.refusedFor(
                CloseGateRefusal.EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED)).isTrue();
        }
    }

    @Nested
    @DisplayName("step 6: confirm reconciliations")
    class Reconciliations {

        @Test
        @DisplayName("no reconciliations at all is four refusals, one per required scope")
        void allFourRequired() {
            // 02 § 3.1 step 6 names four: sub-ledger to GL, contractual leg to CBS, Stage 3
            // four-way, pre- and post-floor. FR-901 says "all".
            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(), List.of(), List.of()));

            assertThat(decision.refusals())
                .as("four scopes, four refusals")
                .hasSize(4);
            assertThat(decision.reasons())
                .containsExactly(CloseGateRefusal.RECONCILIATION_NOT_PRESENTED);
        }

        @Test
        @DisplayName("a missing Stage 3 four-way is refused by name")
        void oneMissingScope() {
            List<ReconciliationTie> threeOfFour = tiedReconciliations().stream()
                .filter(tie -> tie.scope() != ReconciliationScope.STAGE_THREE_FOUR_WAY)
                .toList();

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(), List.of(), threeOfFour));

            assertThat(decision.refusals()).hasSize(1);
            assertThat(decision.refusals().get(0).detail())
                .as("named, and pointed at the invariant that quantifies it")
                .contains("Stage 3 four-way", "S3_1");
        }

        @Test
        @DisplayName("a one-paise residual blocks the close")
        void untiedReconciliation() {
            // Derivation by hand: a GL balance of 12,00,000.00 against a sub-ledger total of
            // 12,00,000.01 leaves 12,00,000.01 - 12,00,000.00 = 0.01. One paise, and it blocks:
            // there is no tolerance, because a configurable one is how a systematic break gets
            // carried for four quarters at 40% of it each time.
            List<ReconciliationTie> broken = new ArrayList<>(tiedReconciliations().stream()
                .filter(tie -> tie.scope() != ReconciliationScope.SUBLEDGER_TO_GL)
                .toList());
            ReconciliationTie break_ = new ReconciliationTie(
                ReconciliationScope.SUBLEDGER_TO_GL, Money.inr("1200000.00"),
                Money.inr("1200000.01"), "GL trial balance vs PERIOD_BALANCE rollup");
            broken.add(break_);

            assertThat(break_.residual())
                .as("hand arithmetic: 1200000.01 - 1200000.00 = 0.01")
                .isEqualTo(Money.inr("0.01"));
            assertThat(break_.isTied()).isFalse();

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(), List.of(), broken));

            assertThat(decision.refusedFor(CloseGateRefusal.RECONCILIATION_NOT_TIED)).isTrue();
        }

        @Test
        @DisplayName("two breaks in opposite directions do not net to a tie")
        void oppositeBreaksDoNotNet() {
            // The arithmetic every aggregate in this engine avoids. A sub-ledger 4,00,000 over
            // the GL and a contractual leg 4,00,000 under the CBS would net to zero on a signed
            // sum; here they are two ties, each checked on its own, so they are two refusals.
            List<ReconciliationTie> ties = new ArrayList<>();
            ties.add(new ReconciliationTie(ReconciliationScope.SUBLEDGER_TO_GL,
                Money.inr("1200000.00"), Money.inr("1600000.00"), "GL vs sub-ledger"));
            ties.add(new ReconciliationTie(ReconciliationScope.CONTRACTUAL_LEG_TO_CBS,
                Money.inr("1200000.00"), Money.inr("800000.00"), "CBS vs contractual leg"));
            ties.add(new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY,
                Money.inr("0.00"), Money.inr("0.00"), "no Stage 3 exposure"));
            ties.add(new ReconciliationTie(ReconciliationScope.PRE_AND_POST_FLOOR,
                Money.inr("0.00"), Money.inr("0.00"), "no floor applied"));

            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(), greenDashboard(), List.of(), List.of(), ties));

            assertThat(decision.refusals())
                .as("+4,00,000 and -4,00,000 are two breaks, not zero")
                .hasSize(2);
            assertThat(ties.get(0).absoluteResidual()).isEqualTo(Money.inr("400000.00"));
            assertThat(ties.get(1).absoluteResidual()).isEqualTo(Money.inr("400000.00"));
        }
    }

    @Nested
    @DisplayName("refusals are values: the whole list, once")
    class RefusalsAreValues {

        @Test
        @DisplayName("a close with six problems reports six, not the first")
        void everyProblemAtOnce() {
            // Six, counted by hand from the request built below:
            //   1  INVARIANT_BREACH                        (S3-1 red)
            //   2  EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED   (LN-1 OPEN)
            //   3  UNEVIDENCED_ACCEPTANCE                   (LN-2 accepted, no artefact)
            //   4  RECONCILIATION_NOT_PRESENTED             (pre- and post-floor absent)
            //   5  RECONCILIATION_NOT_TIED                  (sub-ledger 0.01 out)
            //   6  MISSING_VERSION_CUTOFF                   (no replay boundary)
            List<InvariantResult> dashboard = List.of(
                InvariantResult.pass(InvariantId.IC_1, "GCA0 ties"),
                InvariantResult.fail(InvariantId.S3_1, "four-way out by 1,240.50",
                    new BigDecimal("1240.50")));
            List<ExceptionRecord> exceptions = List.of(
                unmappedFee("LN-1"),
                unmappedFee("LN-2").acceptWithApproval("ops.lead", "immaterial"));
            List<ReconciliationTie> ties = new ArrayList<>();
            ties.add(new ReconciliationTie(ReconciliationScope.SUBLEDGER_TO_GL,
                Money.inr("1200000.00"), Money.inr("1200000.01"), "GL vs sub-ledger"));
            ties.add(new ReconciliationTie(ReconciliationScope.CONTRACTUAL_LEG_TO_CBS,
                Money.inr("1200000.00"), Money.inr("1200000.00"), "CBS"));
            ties.add(new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY,
                Money.inr("0.00"), Money.inr("0.00"), "no Stage 3 exposure"));

            CloseDecision decision = PeriodCloseGate.evaluate(new CloseRequest(
                aprilClosing(), CONTROLLER, CLOSED_AT, null, dashboard, exceptions, List.of(),
                ties));

            assertThat(decision.refusalCount())
                .as("all six, because they are worked by different people")
                .isEqualTo(6);
            assertThat(decision.reasons()).containsExactlyInAnyOrder(
                CloseGateRefusal.MISSING_VERSION_CUTOFF,
                CloseGateRefusal.INVARIANT_BREACH,
                CloseGateRefusal.EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED,
                CloseGateRefusal.UNEVIDENCED_ACCEPTANCE,
                CloseGateRefusal.RECONCILIATION_NOT_PRESENTED,
                CloseGateRefusal.RECONCILIATION_NOT_TIED);
            assertThat(decision.describe())
                .as("one sentence, then one line per refusal")
                .contains("CLOSE REFUSED by 6 gate(s)");
        }

        @Test
        @DisplayName("the refusals that look like diligence are separable from the absences")
        void diligenceSplit() {
            // The distinction ActivationRefusalReason draws for the impact preview: a missing
            // reconciliation is a step nobody performed, while an untied one is a step somebody
            // performed and filed. The second passes a checkbox control.
            assertThat(CloseGateRefusal.RECONCILIATION_NOT_PRESENTED.looksLikeDiligence())
                .isFalse();
            assertThat(CloseGateRefusal.RECONCILIATION_NOT_TIED.looksLikeDiligence()).isTrue();
            assertThat(CloseGateRefusal.SELF_APPROVED_ACCEPTANCE.looksLikeDiligence()).isTrue();
            assertThat(CloseGateRefusal.NO_INVARIANT_RESULTS.looksLikeDiligence()).isFalse();
        }
    }

    @Nested
    @DisplayName("FR-901's gate publishes no invariant identifier")
    class NoInvariantForTheGate {

        @Test
        @DisplayName("CloseDecision exposes nothing that returns an InvariantResult")
        void decisionPublishesNoInvariantResult() {
            // The one structural assertion in this file. FR-901's gate is where invariants are
            // READ; an id meaning "the close gate held" would be green exactly when the gate said
            // permitted, and unfalsifiable by any ledger content. Four such ids have been found
            // in this codebase wearing invariant identifiers, and this test is what stops a fifth
            // being added here by a well-meaning later hand.
            boolean publishesAnInvariant = java.util.Arrays.stream(
                    CloseDecision.class.getMethods())
                .anyMatch(method -> method.getReturnType() == InvariantResult.class);

            assertThat(publishesAnInvariant)
                .as("the gate consumes InvariantResults; it does not produce one")
                .isFalse();
        }

        @Test
        @DisplayName("the gate consumes results it was handed, and re-derives none")
        void gateConsumesResults() {
            // A red result the gate could not possibly have computed — there is no Stage 3 data
            // in the request at all — still blocks. That is the shape of an enforcement point.
            CloseDecision decision = PeriodCloseGate.evaluate(request(
                aprilClosing(),
                List.of(InvariantResult.fail(InvariantId.HB_2,
                    "a swap cost is inside the EIR stream", BigDecimal.ONE)),
                List.of(), List.of(), tiedReconciliations()));

            assertThat(decision.refusedFor(CloseGateRefusal.INVARIANT_BREACH)).isTrue();
        }
    }
}
