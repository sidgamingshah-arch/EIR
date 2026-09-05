package com.crisil.eir.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.calc.routing.ModificationConclusion;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The inner loop of the month-end run, against reference cases 1 and 5 (05 § 3.2).
 *
 * <p>Every expected figure in this class comes from {@code docs/reference-cases} or from a
 * {@code python3} recomputation at 28 significant digits stated in the comment beside it. Nothing is
 * read back from the engine — see {@link RunFixtures} for the three independent statements of the
 * month-13 position and for the cross-check that recovers 528,407.32 by pricing the remaining
 * instalments at the stored rate.
 */
class ContractPipelineTest {

    /** Reference case 1's month-13 accrual: 528,407.32 x 0.0104214918 = 5,506.79255243997600. */
    private static final Money GROSS_INTEREST = Money.inr("5506.79255243997600");

    private static ContractComputation performing() {
        RunFixtures.Harness harness = RunFixtures.harness(
            "C-0001", RunFixtures.performingState(), RunFixtures.performingPeriod("C-0001"),
            RunFixtures.EXPLODING_SOLVER);
        return harness.pipeline().compute("C-0001");
    }

    private static InvariantResult only(ContractComputation computation, InvariantId id) {
        List<InvariantResult> matching =
            computation.invariants().stream().filter(result -> result.id() == id).toList();
        assertThat(matching)
            .as("%s must appear exactly once: a named invariant gets one answer, or anything"
                + " resolving it by name gets whichever came first", id)
            .hasSize(1);
        return matching.getFirst();
    }

    @Nested
    @DisplayName("a performing contract rolls forward at its stored rate and never re-solves")
    class SteadyState {

        @Test
        @DisplayName("month 13 reproduces reference case 1 to the paise")
        void reproducesReferenceCaseOne() {
            ContractComputation computation = performing();

            // Reference case 1, period 13: 528,407.32 | 5,506.79 | 47,073.47 | 486,840.64.
            assertThat(computation.openingGca()).as("opening GCA")
                .isEqualTo(RunFixtures.OPENING_GCA);
            assertThat(computation.row().presentedEirInterest()).as("EIR interest")
                .isEqualTo(Money.inr("5506.79"));
            assertThat(computation.row().presentedCashReceived()).as("cash received")
                .isEqualTo(RunFixtures.EMI);
            assertThat(computation.closingGca().atPresentationScale()).as("closing GCA")
                .isEqualTo(Money.inr("486840.64"));
        }

        @Test
        @DisplayName("no solve happened, and the solver would have exploded if one had")
        void neverReSolves() {
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0001", RunFixtures.performingState(), RunFixtures.performingPeriod("C-0001"),
                RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0001");

            // Two assertions, because they say different things. The exploding solver proves no
            // solve was reachable at all; the count proves the pipeline's own record of that agrees,
            // which is what a production run has to report when there is no landmine wired in.
            assertThat(computation.solves()).as("solves for a contract with no event").isZero();
            assertThat(harness.audit().solveCount()).as("solves across the run").isZero();
            assertThat(harness.audit().records()).isEmpty();
            assertThat(harness.audit().describe()).contains("no solve was performed");
            // The rate is the same object it came in as: 05 § 3.2's steady state is roll-forward
            // arithmetic, and the only thing that moves a rate is a B5.4.5 reset.
            assertThat(computation.rateMoved()).isFalse();
            assertThat(computation.eirAfter()).isEqualTo(RunFixtures.EIR);
            assertThat(computation.routing()).as("no event, so the routing table is never consulted")
                .isNull();
        }

        @Test
        @DisplayName("the journal balances, and SL-2 is what says so")
        void journalBalances() {
            ContractComputation computation = performing();
            JournalEntry journal = computation.journal();

            // Four lines: DR the carrying amount with the accrual, CR interest income with it,
            // DR cash with what the cash book applied, CR the carrying amount with what the vector
            // says was received. 5,506.79 + 47,073.47 on each side.
            assertThat(journal.lines()).hasSize(4);
            assertThat(journal.totalDebits().atPresentationScale())
                .isEqualTo(Money.inr("52580.26"));
            assertThat(journal.totalCredits().atPresentationScale())
                .isEqualTo(Money.inr("52580.26"));
            assertThat(journal.residual().isZero()).isTrue();

            InvariantResult slTwo = only(computation, InvariantId.SL_2);
            assertThat(slTwo.satisfied()).isTrue();

            // All of the accrual reached interest income: Stage 1 recognises on the gross basis.
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.INTEREST_INCOME_ACCRUAL))
                .isEqualTo(GROSS_INTEREST);
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.INTEREST_IN_SUSPENSE)
                .isZero()).isTrue();
        }

        @Test
        @DisplayName("ST-2 holds because the schedule and the vector agree on one whole period")
        void accrualLengthAgrees() {
            ContractComputation computation = performing();

            assertThat(computation.row().accrualExponent()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(computation.decomposition().accrualExponent())
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(only(computation, InvariantId.ST_2).satisfied()).isTrue();
        }

        @Test
        @DisplayName("S3-1 is not asserted where recognition is not suppressed")
        void noFourWayOnAPerformingContract() {
            ContractComputation computation = performing();

            // Its fourth leg says cash applied to interest equals what came out of suspense, which
            // is false for every performing contract in the book — a Stage 1 borrower pays interest
            // with nothing in suspense to recover. Running it here would put a guaranteed breach on
            // ten million rows, and a control that is red by design gets suppressed.
            assertThat(computation.reconciliation()).isNull();
            assertThat(computation.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.SL_2, InvariantId.ST_2);
        }

        @Test
        @DisplayName("the spine's ContractResult carries the closing balance and the journal")
        void producesAContractResult() {
            ContractResult result = performing().toContractResult();

            assertThat(result.isComputed()).isTrue();
            assertThat(result.closingGca().atPresentationScale()).isEqualTo(Money.inr("486840.64"));
            assertThat(result.breaches()).isEmpty();
            assertThat(result.exception()).isNull();
        }
    }

    @Nested
    @DisplayName("SL-2 can fail, which is why the entry is not built balanced")
    class JournalImbalance {

        @Test
        @DisplayName("a cash book that disagrees with the flow vector is a measured residual")
        void cashBookAgainstVector() {
            // The vector says 47,073.47 was received; the cash book applied 41,775.31 + 5,225.00 =
            // 47,000.31. Residual = 47,000.31 − 47,073.47 = −73.16, derived by hand. Nothing else
            // in the engine compares these two systems, so without SL-2 a receipt short-posted by
            // the CBS would roll forward the balance correctly and reconcile to nothing.
            ContractPeriod period = ContractPeriod.of(
                "C-0001", 13, RunFixtures.periodVector(RunFixtures.EMI), RunFixtures.MONTHLY,
                Money.inr("41775.31"), Money.inr("5225.00"));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0001", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0001");

            InvariantResult slTwo = only(computation, InvariantId.SL_2);
            assertThat(slTwo.satisfied()).isFalse();
            assertThat(slTwo.deviation()).isEqualByComparingTo(new BigDecimal("-73.16"));
            assertThat(slTwo.detail()).contains("out by INR -73.16");
        }
    }

    @Nested
    @DisplayName("ST-2 can fail, because the accrual length is derived twice")
    class AccrualLength {

        @Test
        @DisplayName("a period flow carrying the wrong ordinal declares an accrual the schedule "
            + "does not have")
        void twoPeriodsInOneBoundary() {
            // The vector's single flow carries ordinal 2, so AmortisationEngine accretes over two
            // periods while the contract's schedule says the month-13 boundary is one. This is what
            // a loop feeding two periods' flows into one boundary looks like, and ST-2, S3-1 and
            // S3-2 are algebraic identities that hold however wrong the accrual factor is — so
            // without the second derivation nothing would notice.
            ContractPeriod period = ContractPeriod.of(
                "C-0001", 13, RunFixtures.periodVector(RunFixtures.EMI, 2), RunFixtures.MONTHLY,
                Money.inr("41775.31"), RunFixtures.BILLED_INTEREST);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0001", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0001");

            InvariantResult stTwo = only(computation, InvariantId.ST_2);
            assertThat(stTwo.satisfied()).isFalse();
            // The exponent leg is asserted first because it is the cause, so its deviation — 2
            // periods against 1 — is the one the conjunction keeps.
            assertThat(stTwo.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(stTwo.detail()).contains("the ledger row accrued over 2");

            // And the interest figures diverge with it: 528,407.32 x ((1.0104214918)^2 − 1) =
            // 11,071.00964... against the decomposition's 5,506.79255..., so SL-2 reports the gap
            // too. Two controls, one cause, and the ST-2 detail is the one that names it.
            assertThat(only(computation, InvariantId.SL_2).satisfied()).isFalse();
        }

        @Test
        @DisplayName("under actual dating the exponent is day-counted off the schedule's own dates")
        void actualDatingAgrees() {
            // 03 § 3.10 makes actual dating the default. The exponent is
            // ACT/365F(2028-04-30, 2028-05-31) = 31/365 = 0.08493150684931506849315068493, and the
            // accrual is 528,407.32 x ((1.13248094)^(31/365) − 1) = 5,612.9611964510071214... →
            // 5,612.96, with a closing balance of 486,946.81. python3 at 28 digits, HALF_UP.
            //
            // Note the figure is NOT reference case 1's 5,506.79, and that is the point of the
            // convention: 31/365 is not 1/12, so the same instrument accrues differently under the
            // two readings. A pipeline assuming an exponent of one would publish 5,506.79 here and
            // every control except this one would still tie.
            ContractPeriod period = ContractPeriod.of(
                "C-0012", 13,
                RunFixtures.periodVectorDated(RunFixtures.PERIOD_END, RunFixtures.EMI),
                RunFixtures.ACTUAL_365F, Money.inr("41775.31"), RunFixtures.BILLED_INTEREST);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0012", RunFixtures.actualDatedState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0012");

            assertThat(computation.row().accrualExponent())
                .isEqualByComparingTo(new BigDecimal("0.08493150684931506849315068493"));
            assertThat(computation.row().presentedEirInterest()).isEqualTo(Money.inr("5612.96"));
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("486946.81"));
            assertThat(only(computation, InvariantId.ST_2).satisfied()).isTrue();
            assertThat(only(computation, InvariantId.SL_2).satisfied()).isTrue();
            assertThat(computation.solves()).isZero();
        }

        @Test
        @DisplayName("a period flow dated a month late is caught by the day-counted derivation")
        void actualDatingCatchesAMisDatedFlow() {
            // The feed dated the month-13 flow 2028-06-30 while the schedule says 2028-05-31, so
            // the ledger accrues over 61/365 and the schedule says 31/365. Deviation is
            // 61/365 − 31/365 = 0.08219178082191780821917808217, computed by hand from the day
            // counts. This is the broken-period defect the two derivations exist to separate.
            ContractPeriod period = ContractPeriod.of(
                "C-0013", 13,
                RunFixtures.periodVectorDated(
                    RunFixtures.PERIOD_END.plusMonths(1), RunFixtures.EMI),
                RunFixtures.ACTUAL_365F, Money.inr("41775.31"), RunFixtures.BILLED_INTEREST);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0013", RunFixtures.actualDatedState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0013");

            InvariantResult stTwo = only(computation, InvariantId.ST_2);
            assertThat(stTwo.satisfied()).isFalse();
            assertThat(stTwo.deviation())
                .isEqualByComparingTo(new BigDecimal("0.08219178082191780821917808217"));
            // The cash block still balances — the cash book and the vector agree on 47,073.47 — so
            // SL-2's residual is exactly the accrual difference and nothing else.
            assertThat(only(computation, InvariantId.SL_2).satisfied()).isFalse();
        }
    }

    @Nested
    @DisplayName("Stage 3: gross roll-forward, suppressed recognition, suspense as a ledger")
    class StageThree {

        @Test
        @DisplayName("reference case 5's decomposition, with nothing recognised")
        void reproducesReferenceCaseFive() {
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0002", RunFixtures.stage3State(), RunFixtures.defaultedPeriod("C-0002"),
                RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0002");

            // Reference case 5: (a) 5,506.79 gross, (b) 3,304.08 net basis, (c) 2,202.72 unwind,
            // nil recognised, 5,298.16 to interest-in-suspense.
            assertThat(computation.decomposition().grossBasisInterest().atPresentationScale())
                .isEqualTo(Money.inr("5506.79"));
            assertThat(computation.decomposition().netBasisInterest().atPresentationScale())
                .isEqualTo(Money.inr("3304.08"));
            assertThat(computation.shadowUnwind().atPresentationScale())
                .isEqualTo(Money.inr("2202.72"));
            assertThat(computation.decomposition().recognisedIncome().isZero()).isTrue();
            assertThat(computation.suspense().chargedToSuspense())
                .isEqualTo(RunFixtures.BILLED_INTEREST);
            assertThat(computation.suspense().closingBalance())
                .isEqualTo(RunFixtures.BILLED_INTEREST);

            // The roll-forward is unchanged by staging (FR-610): gross basis, same rate, same
            // balance. 528,407.32 + 5,506.79255243997600 = 533,914.11255... → 533,914.11.
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("533914.11"));
            assertThat(computation.eirAfter()).isEqualTo(RunFixtures.EIR);
            assertThat(computation.solves()).isZero();
        }

        @Test
        @DisplayName("nothing reaches the accrual income account, and the journal still balances")
        void nothingRecognised() {
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0002", RunFixtures.stage3State(), RunFixtures.defaultedPeriod("C-0002"),
                RunFixtures.EXPLODING_SOLVER);

            JournalEntry journal = harness.pipeline().compute("C-0002").journal();

            // DR carrying amount 5,506.79255...; CR suspense 5,298.16; CR unamortised fee 208.63255
            // (= 5,506.79255... − 5,298.16, the month-13 fee slice eir-gl's own fixture states as
            // 208.63). No cash lines: the borrower paid nothing.
            assertThat(journal.lines()).hasSize(3);
            assertThat(journal.residual().isZero()).isTrue();
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.INTEREST_INCOME_ACCRUAL)
                .isZero())
                .as("S3-2 read off the artefact that reaches the ledger, not off the field the"
                    + " decomposition set")
                .isTrue();
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.INTEREST_IN_SUSPENSE))
                .isEqualTo(RunFixtures.BILLED_INTEREST);
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.UNAMORTISED_FEE)
                .atPresentationScale())
                .isEqualTo(Money.inr("208.63"));
        }

        @Test
        @DisplayName("S3-1's four legs reconcile when the suspense ledger ties to billing and cash")
        void fourWayReconciles() {
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0002", RunFixtures.stage3State(), RunFixtures.defaultedPeriod("C-0002"),
                RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0002");

            assertThat(computation.reconciliation()).isNotNull();
            assertThat(computation.reconciliation().reconciles()).isTrue();
            assertThat(only(computation, InvariantId.S3_1).satisfied()).isTrue();
        }

        @Test
        @DisplayName("S3-1 catches the double count: cash on interest with nothing out of suspense")
        void fourWayCatchesTheDoubleCount() {
            // The borrower paid 5,298.16 and the cash book applied all of it to interest, but no
            // recovery was posted against the suspense ledger. Interest cannot be sitting in
            // suspense and received at the same time — the same rupee has been counted twice — and
            // legs 1, 2 and 3 all reconcile, which is why the leg exists.
            ContractPeriod period = ContractPeriod.of(
                "C-0002", 13, RunFixtures.periodVector(RunFixtures.BILLED_INTEREST),
                RunFixtures.MONTHLY, RunFixtures.NIL, RunFixtures.BILLED_INTEREST);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0002", RunFixtures.stage3State(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0002");

            InvariantResult s3one = only(computation, InvariantId.S3_1);
            assertThat(s3one.satisfied()).isFalse();
            // Deviation is the total ABSOLUTE residual over the broken legs — here one leg, out by
            // the whole 5,298.16.
            assertThat(s3one.deviation()).isEqualByComparingTo(new BigDecimal("5298.16"));
            assertThat(s3one.detail()).contains("1 of 4 legs broken")
                .contains("cash applied to interest against suspense recovered");
            // SL-2 is unaffected: the cash book and the vector agree, so the journal balances. A
            // reconciliation break is not a posting break, and conflating them would have made the
            // double count invisible.
            assertThat(only(computation, InvariantId.SL_2).satisfied()).isTrue();
        }

        @Test
        @DisplayName("a recovery is recognised in its own income account, not in the accrual's")
        void recoveryDoesNotLookLikeRecognisedAccrual() {
            // The borrower cleared the arrears: 5,298.16 received and applied to interest, matched
            // by a 5,298.16 recovery out of a suspense balance brought forward. The recovery IS
            // recognised — that is what takes it off the ledger (FR-604) — and it must not land in
            // the account S3-2 is asserted against, or S3-2 would fail on a contract behaving
            // exactly as ACPIR requires and the fix would be to widen S3-2.
            ContractPeriod period = ContractPeriod.of(
                "C-0002", 13, RunFixtures.periodVector(RunFixtures.BILLED_INTEREST),
                RunFixtures.MONTHLY, RunFixtures.NIL, RunFixtures.BILLED_INTEREST)
                .withSuspense(RunFixtures.BILLED_INTEREST, RunFixtures.BILLED_INTEREST,
                    RunFixtures.NIL);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0002", RunFixtures.stage3State(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0002");
            JournalEntry journal = computation.journal();

            assertThat(only(computation, InvariantId.S3_1).satisfied())
                .as("legs 2 and 4 both tie: charged equals billed, recovered equals cash on"
                    + " interest")
                .isTrue();
            assertThat(PeriodJournal.netCreditTo(journal, PeriodJournal.INTEREST_INCOME_ACCRUAL)
                .isZero()).isTrue();
            assertThat(PeriodJournal.netCreditTo(
                journal, PeriodJournal.INTEREST_INCOME_SUSPENSE_RECOVERED))
                .isEqualTo(RunFixtures.BILLED_INTEREST);
            // Opening 5,298.16 + charged 5,298.16 − recovered 5,298.16 = 5,298.16 carried forward.
            assertThat(computation.suspense().closingBalance())
                .isEqualTo(RunFixtures.BILLED_INTEREST);
            assertThat(journal.residual().isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("events route through the approved table, and only a reset re-solves")
    class Events {

        @Test
        @DisplayName("a B5.4.5 reset re-solves exactly once, from the current carrying amount")
        void resetSolvesOnce() {
            // FLOATING, because DefaultEventRouter's rate-type check sends a market-movement driver
            // on a FIXED instrument to the substantiality test whatever the table says: a
            // fixed-rate loan has no term that reprices off a benchmark, so the combination can
            // only have arisen from renegotiation (FR-507).
            Rate reset = Rate.periodic(new BigDecimal("0.011000000000"), 12);
            ContractPeriod period = RunFixtures.performingPeriod("C-0003").withEvent(
                PeriodEvent.of(RateDriver.TIME_VALUE_OF_MONEY, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00"))));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0003", RunFixtures.floatingState(), period,
                RunFixtures.solverReturning(reset));

            ContractComputation computation = harness.pipeline().compute("C-0003");

            assertThat(computation.routing().mechanism()).isEqualTo(Mechanism.RESET);
            assertThat(computation.routing().overriddenByRateTypeCheck()).isFalse();
            assertThat(computation.solves()).as("one solve, in the one branch that resolves a rate")
                .isEqualTo(1);
            assertThat(harness.audit().records()).hasSize(1);
            assertThat(harness.audit().records().getFirst().reason())
                .contains("B5.4.5 reset").contains("TIME_VALUE_OF_MONEY")
                .contains(computation.routing().routingTableVersionId());
            assertThat(computation.rateMoved()).isTrue();
            assertThat(computation.eirAfter()).isEqualTo(reset);

            // The period then rolls forward at the NEW rate and the balance is unchanged by the
            // reset itself (B5.4.5 books nothing): 528,407.32 x 0.011 = 5,812.48052 → 5,812.48,
            // and 528,407.32 + 5,812.48052 − 47,073.47 = 487,146.33052 → 487,146.33. Cross-checked
            // with python3 at 28 digits.
            assertThat(computation.openingGca()).isEqualTo(RunFixtures.OPENING_GCA);
            assertThat(computation.row().presentedEirInterest()).isEqualTo(Money.inr("5812.48"));
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("487146.33"));
            assertThat(computation.catchUp()).isNull();
        }

        @Test
        @DisplayName("a driver a bank has elected as immaterial rolls forward, and is not quarantined")
        void noneRollsForwardUnchanged() {
            // The defect this test was written for: both this branch and DERECOGNITION threw, on
            // the reasoning that "neither is a roll-forward". For DERECOGNITION that holds. NONE is
            // precisely a roll-forward — Mechanism.NONE is "no EIR consequence" — so every contract
            // whose driver a bank had elected as immaterial was quarantined, every month, with a
            // message telling the operator the routing was not a roll-forward. RoutingTable
            // requires every driver mapped and RoutingTableFormatTest pins that
            // "DISBURSEMENT_TIMING routed to NONE is a legitimate materiality election", so an
            // approved table carries it and the whole affected sub-book is lost.
            //
            // The expected figures are the performing baseline, unchanged by the event, and they
            // are the SAME figures SteadyState.rollsForwardAtTheStoredRate asserts: that is the
            // claim — an immaterial event changes nothing. 528,407.32 x 0.0104214918 = 5,506.79...,
            // and 528,407.32 + 5,506.79 − 47,073.47 = 486,840.64.
            RoutingTableVersion elected = new RoutingTableVersion(
                "RT-2028.2-IMMATERIAL-DRAWDOWN",
                "late drawdown elected immaterial; no restatement",
                LocalDate.of(2028, 4, 1), "policy.maker", "policy.checker",
                LocalDate.of(2028, 3, 20));
            RoutingTableRegistry registry = RoutingTableRegistry.of(
                RoutingTable.currentDefault().reroute(
                    RateDriver.DISBURSEMENT_TIMING, Mechanism.NONE, elected));

            ContractPeriod period = RunFixtures.performingPeriod("C-0009").withEvent(
                PeriodEvent.of(RateDriver.DISBURSEMENT_TIMING, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00"))));
            RunFixtures.Harness harness = RunFixtures.harnessRoutedBy(
                "C-0009", RunFixtures.performingState(), period,
                RunFixtures.EXPLODING_SOLVER, registry);

            ContractComputation computation = harness.pipeline().compute("C-0009");

            assertThat(computation.routing().mechanism()).isEqualTo(Mechanism.NONE);
            assertThat(computation.solves())
                .as("no EIR consequence cannot re-solve; the exploding solver would have said so")
                .isZero();
            assertThat(computation.catchUp())
                .as("and restates nothing")
                .isNull();
            assertThat(computation.rateMoved()).isFalse();
            assertThat(computation.eirAfter()).isEqualTo(RunFixtures.EIR);
            assertThat(computation.openingGca()).isEqualTo(RunFixtures.OPENING_GCA);
            assertThat(computation.row().presentedEirInterest()).isEqualTo(Money.inr("5506.79"));
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("486840.64"));
        }

        @Test
        @DisplayName("a B5.4.6 catch-up restates the balance and performs no solve at all")
        void catchUpDoesNotSolve() {
            // ESG_LINKED is a pre-determined adjustment compensating for neither the time value of
            // money nor credit risk, so 03 § 6.1's baseline routes it to a catch-up. The retained
            // rate is what discounts the revised flows, and the exploding solver proves it was not
            // re-solved on the way past — the defect CU-1 exists to detect.
            ContractPeriod period = RunFixtures.performingPeriod("C-0004").withEvent(
                PeriodEvent.of(RateDriver.ESG_LINKED, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00"))));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0004", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0004");

            assertThat(computation.routing().mechanism()).isEqualTo(Mechanism.CATCH_UP);
            assertThat(computation.solves()).isZero();
            assertThat(harness.audit().solveCount()).isZero();
            assertThat(computation.rateMoved()).isFalse();

            // python3, 28 digits, HALF_UP: twelve monthly flows of 45,000.00 discounted at
            // 0.010421491800 give 505,132.2823460797087813909446 → 505,132.28, so the catch-up is
            // 505,132.28 − 528,407.32 = −23,275.04, a charge. The period then accretes on the
            // RESTATED balance: 505,132.2823460797... x 0.0104214918 = 5,264.2319383849... →
            // 5,264.23, and 505,132.28 + 5,264.23 − 47,073.47 = 463,323.04.
            assertThat(computation.catchUp()).isNotNull();
            assertThat(computation.catchUp().restatedGca().atPresentationScale())
                .isEqualTo(Money.inr("505132.28"));
            assertThat(computation.catchUp().presentedCatchUp())
                .isEqualTo(Money.inr("-23275.04"));
            assertThat(computation.catchUp().isCharge()).isTrue();
            assertThat(computation.row().presentedEirInterest()).isEqualTo(Money.inr("5264.23"));
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("463323.04"));

            // The catch-up is posted, so the journal carries it and still balances: the restatement
            // credits the carrying amount 23,275.04 and debits the catch-up line.
            assertThat(only(computation, InvariantId.SL_2).satisfied()).isTrue();
            assertThat(PeriodJournal.netCreditTo(
                computation.journal(), PeriodJournal.CATCH_UP).atPresentationScale())
                .isEqualTo(Money.inr("-23275.04"));
        }

        @Test
        @DisplayName("a renegotiated FIXED-rate loan is a modification, and needs a conclusion")
        void modificationTestWithoutAConclusionQuarantines() {
            ContractPeriod period = RunFixtures.performingPeriod("C-0005").withEvent(
                PeriodEvent.of(RateDriver.TIME_VALUE_OF_MONEY, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00"))));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0005", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            // The engine computes the 10% test as evidence and does not decide. Guessing between a
            // catch-up and a derecognition is a judgement taken by an implementation detail on
            // figures that differ by the whole balance, so the contract is refused — which the
            // barrier turns into a quarantined contract, not a lost one.
            assertThatIllegalStateException()
                .isThrownBy(() -> harness.pipeline().compute("C-0005"))
                .withMessageContaining("routes to a substantiality assessment")
                .withMessageContaining("no conclusion has been recorded");
        }

        @Test
        @DisplayName("assessed NOT_SUBSTANTIAL, the same event restates at the retained rate")
        void modificationTestAssessedNotSubstantial() {
            ContractPeriod period = RunFixtures.performingPeriod("C-0006").withEvent(
                PeriodEvent.assessed(RateDriver.TIME_VALUE_OF_MONEY, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00")),
                    ModificationConclusion.NOT_SUBSTANTIAL));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0006", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computation = harness.pipeline().compute("C-0006");

            assertThat(computation.routing().mechanism()).isEqualTo(Mechanism.MODIFICATION_TEST);
            assertThat(computation.routing().overriddenByRateTypeCheck())
                .as("the rate-type check fired, not the table row").isTrue();
            assertThat(computation.solves()).isZero();
            assertThat(computation.catchUp().presentedCatchUp())
                .isEqualTo(Money.inr("-23275.04"));
        }

        @Test
        @DisplayName("assessed SUBSTANTIAL, the contract is refused: derecognition is 05 section 3.1")
        void modificationTestAssessedSubstantial() {
            ContractPeriod period = RunFixtures.performingPeriod("C-0007").withEvent(
                PeriodEvent.assessed(RateDriver.TIME_VALUE_OF_MONEY, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00")),
                    ModificationConclusion.SUBSTANTIAL));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0007", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            assertThatIllegalStateException()
                .isThrownBy(() -> harness.pipeline().compute("C-0007"))
                .withMessageContaining("DERECOGNITION");
        }

        @Test
        @DisplayName("a reset whose solve finds no root quarantines rather than falling back")
        void noSolutionQuarantines() {
            ContractPeriod period = RunFixtures.performingPeriod("C-0008").withEvent(
                PeriodEvent.of(RateDriver.TIME_VALUE_OF_MONEY, RunFixtures.PERIOD_START,
                    RunFixtures.remainingInstalments(Money.inr("45000.00"))));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0008", RunFixtures.floatingState(), period,
                RunFixtures.solverWithNoSolution());

            // 03 § 4.3 names a silent fallback the most damaging failure available to the engine:
            // it produces plausible numbers and leaves no trace. So the contract is refused and the
            // solver's own diagnostic travels with it.
            assertThatIllegalStateException()
                .isThrownBy(() -> harness.pipeline().compute("C-0008"))
                .withMessageContaining("NO_SOLUTION")
                .withMessageContaining("no sign change on the ladder");
            assertThat(harness.audit().solveCount())
                .as("the solve happened and is on the record even though it produced nothing")
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the loop refuses conditions under which publishing a figure is worse than none")
    class Refusals {

        @Test
        @DisplayName("a contract in the population with no state as at the boundary")
        void noOpeningState() {
            RunFixtures.Harness harness = RunFixtures.harness(
                List.of("C-0009"), Map.of(),
                Map.of("C-0009", RunFixtures.performingPeriod("C-0009")),
                RunFixtures.EXPLODING_SOLVER);

            assertThatIllegalStateException()
                .isThrownBy(() -> harness.pipeline().compute("C-0009"))
                .withMessageContaining("has no state as at")
                .withMessageContaining("inventing an opening of nil");
        }

        @Test
        @DisplayName("a contract with no EIR: initial recognition is a different use case")
        void neverSolved() {
            com.crisil.eir.application.port.ContractStateSource.OpeningState unsolved =
                new com.crisil.eir.application.port.ContractStateSource.OpeningState(
                    RunFixtures.case1Terms(), null, RunFixtures.OPENING_GCA,
                    Money.inr("529815.61"),
                    com.crisil.eir.domain.Stage.STAGE_1, RunFixtures.NIL, "ECL-MODEL-2028.05",
                    RunFixtures.BILLED_INTEREST);
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0010", unsolved, RunFixtures.performingPeriod("C-0010"),
                RunFixtures.EXPLODING_SOLVER);

            assertThatIllegalStateException()
                .isThrownBy(() -> harness.pipeline().compute("C-0010"))
                .withMessageContaining("05 § 3.1")
                .withMessageContaining("neither the SPPI gate nor the fee classification");
        }

        /**
         * <b>Moved out of {@code Refusals} in spirit, kept here so the history is visible.</b>
         *
         * <p>This test required {@code compute} to throw {@code IllegalStateException} with
         * "produced 2 accrual boundaries". {@code ContractPipeline} now summarises several accrual
         * periods into the one movement a close publishes, so it must not throw — and the figures
         * it produces are what this asserts instead.
         *
         * <h2>Where the expected figures come from</h2>
         *
         * <p><b>Not from running this code.</b> Reference case 1's periodic EIR is published as
         * {@code 0.010421491800} and the opening GCA at month 13 as {@code 528,407.32}, with an EMI
         * of {@code 47,073.47}. Two flows one month apart give two accrual periods at exponent 1
         * each, computed independently at 28 significant digits (ADR-0002's working precision):
         *
         * <ul>
         *   <li>Period 1 interest = 528,407.32 x 0.010421491800 =
         *       <b>5,506.79255243997600</b> — which is the figure {@code docs/03}'s reference case
         *       already publishes for this contract at this month, so the derivation is checked
         *       against the repository's own published value rather than only against itself.</li>
         *   <li>Period 1 closing = 528,407.32 + 5,506.79255243997600 - 47,073.47 =
         *       <b>486,840.64255243997600</b>.</li>
         *   <li>Period 2 interest = 486,840.64255243997600 x 0.010421491800 =
         *       <b>5,073.605764266984279876196800</b>.</li>
         *   <li>Period 2 closing = <b>444,840.7783167069602798761968</b>.</li>
         *   <li><b>Interest summed</b> = <b>10,580.39831670696027987619680</b>, presented
         *       <b>10,580.40</b>. Note it is not 2 x 5,506.79: the EMI in period 1 reduces the
         *       balance period 2 accrues on, so a test that expected twice the first period would
         *       pass on an implementation that multiplied instead of summing.</li>
         *   <li><b>Cash summed</b> = 2 x 47,073.47 = <b>94,146.94</b> exactly.</li>
         *   <li><b>Accrual exponent</b> = 1 + 1 = <b>2</b>.</li>
         * </ul>
         */
        @Test
        @DisplayName("a period vector producing two accrual boundaries is summarised, not refused")
        void twoBoundaries() {
            com.crisil.eir.domain.FlowVector twoBoundaries =
                com.crisil.eir.domain.FlowVector.of(
                    RunFixtures.PERIOD_START, Money.INR,
                    List.of(
                        com.crisil.eir.domain.CashFlow.of(
                            RunFixtures.PERIOD_END, 1, RunFixtures.EMI,
                            com.crisil.eir.domain.FlowKind.COMBINED_EMI),
                        com.crisil.eir.domain.CashFlow.of(
                            RunFixtures.PERIOD_END.plusMonths(1), 2, RunFixtures.EMI,
                            com.crisil.eir.domain.FlowKind.COMBINED_EMI)));
            ContractPeriod period = ContractPeriod.of(
                "C-0011", 13, twoBoundaries, RunFixtures.MONTHLY, Money.inr("83550.62"),
                Money.inr("10596.32"));
            RunFixtures.Harness harness = RunFixtures.harness(
                "C-0011", RunFixtures.performingState(), period, RunFixtures.EXPLODING_SOLVER);

            ContractComputation computed = harness.pipeline().compute("C-0011");

            assertThat(computed.row().period())
                .as("the accounting period's ordinal, which the pipeline passes in")
                .isEqualTo(13);
            assertThat(computed.row().openingGca().amount())
                .as("the FIRST accrual period's opening, because that is where the month started")
                .isEqualByComparingTo(new java.math.BigDecimal("528407.32"));
            assertThat(computed.row().interestAccrued().amount())
                .as("the two periods summed at working precision -- NOT 2 x 5,506.79, because the"
                    + " period-1 EMI reduces the balance period 2 accrues on")
                .isEqualByComparingTo(
                    new java.math.BigDecimal("10580.39831670696027987619680"));
            assertThat(computed.row().cashReceived().amount())
                .as("both instalments")
                .isEqualByComparingTo(new java.math.BigDecimal("94146.94"));
            assertThat(computed.row().closingGca().amount())
                .as("the SECOND accrual period's closing, reached by summing the movements")
                .isEqualByComparingTo(
                    new java.math.BigDecimal("444840.7783167069602798761968"));
            assertThat(computed.row().accrualExponent())
                .as("elapsed accrual time summed: two monthly periods are two of them, and the"
                    + " last row's exponent alone would report one month for two months' accrual")
                .isEqualByComparingTo(new java.math.BigDecimal("2"));
            assertThat(computed.closingGca())
                .as("and the computation publishes that same closing, so the row the close reads"
                    + " and the balance it carries forward are one figure")
                .isEqualTo(computed.row().closingGca());
        }

        @Test
        @DisplayName("a computation claiming a solve with no rate-resolving routing behind it")
        void solveWithoutAResetIsRefused() {
            ContractComputation clean = performing();

            // The structural half of 05 § 3.2's note, asserted on the type rather than on a test
            // double: a figure produced by an unauthorised solve should not exist to be reported,
            // because the rate it returns is plausible and the journal it produces balances.
            assertThatIllegalStateException()
                .isThrownBy(() -> new ContractComputation(
                    clean.contractId(), clean.eirBefore(), clean.eirAfter(), clean.openingGca(),
                    clean.closingGca(), clean.row(), clean.contractualInterest(),
                    clean.decomposition(), clean.suspense(),
                    null, null, null, 1, clean.journal(), clean.invariants()))
                .withMessageContaining("carried no event at all")
                .withMessageContaining("10M-contract target");
        }
    }
}
