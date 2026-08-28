package com.crisil.eir.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.close.RunClose;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.ReconciliationScope;
import com.crisil.eir.policy.close.ReconciliationTie;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.reconciliation.ContractualLegInterest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two halves of the month-end path, joined: {@link MonthEndRun} into
 * {@link RunClose} (05 § 3.2).
 *
 * <p><b>Why this class exists and no unit test replaces it.</b> Both halves were green
 * independently — 158 tests across the two packages — and neither could answer the one question
 * that decides whether a real close is possible: does the population loop actually publish the
 * invariants {@link RunAggregate#POPULATION_INVARIANTS} declares it answerable for? That list was
 * derived by <em>reading</em> {@code ContractPipeline.assertions}, and a declared obligation the
 * pipeline does not in fact satisfy is a close blocked on every performing book in production —
 * the failure mode named at length on the constant itself and refused there for S3-1. The
 * complementary error, an obligation list too short to catch anything, is refused by
 * {@code RunCloseTest.aRunThatSkippedAnObligation}. Only a real run settles the first.
 *
 * <p>The same argument found the two defects recorded in 03 § 9's tail: the gate that refused every
 * close, and the invariant nobody evaluated. Both were statements about what a control does for
 * whoever invokes it, and neither is reachable from a test of the control alone.
 *
 * <p><b>The figures come from the run, and the expectations do not.</b> The GL balance handed to
 * SL-1 and the CBS figure handed to RC-1 are the fixtures' own published values —
 * {@code OPENING_GCA} rolled forward by the period's accretion less its cash, and
 * {@code BILLED_INTEREST} — not values read back off the aggregate. Where a figure has to be taken
 * from the run to be reconciled against itself, the test says so and asserts the tie is
 * <em>reached</em> rather than that it holds.
 */
class MonthEndCloseTest {

    private static final String GCA_ACCOUNT = "1301-LOANS-GCA";
    private static final Instant CLOSED_AT = Instant.parse("2028-06-05T09:00:00Z");

    /** The two reconciliations {@code RunClose} cannot source from a {@code ContractResult}. */
    private static List<ReconciliationTie> portfolioTies() {
        Money nil = Money.zero(Money.INR);
        return List.of(
            new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY, nil, nil,
                "no Stage 3 accounts in this population"),
            new ReconciliationTie(ReconciliationScope.PRE_AND_POST_FLOOR, nil, nil,
                "no binding regulatory floor"));
    }

    /**
     * The accounting period 202805, which is not the fixtures' accrual window.
     *
     * <p>{@code RunFixtures.PERIOD_START} is 2028-04-30 — the previous instalment date, and the
     * boundary the accrual runs <em>from</em>. An accounting period starts on the first of its
     * month, and {@code AccountingPeriod} enforces that its id encodes its own start date
     * ({@code ck_accounting_period_id_matches_dates}), which is what caught the conflation here.
     * The two dates are different things and the check is right to refuse the substitution: an
     * accrual that begins on the last day of the prior month is normal, and a period 202805 that
     * begins in April is not.
     */
    private static AccountingPeriod closingPeriod() {
        return AccountingPeriod.open(RunFixtures.PERIOD_ID, "FY2028-29",
            LocalDate.of(2028, 5, 1), RunFixtures.PERIOD_END)
            .startClosing(Instant.parse("2028-06-02T09:00:00Z"));
    }

    private static RunFixtures.Harness twoPerformingContracts() {
        Map<String, com.crisil.eir.application.port.ContractStateSource.OpeningState> states =
            new LinkedHashMap<>();
        states.put("C-0001", RunFixtures.performingState());
        states.put("C-0002", RunFixtures.performingState());
        Map<String, ContractPeriod> periods = new LinkedHashMap<>();
        periods.put("C-0001", RunFixtures.performingPeriod("C-0001"));
        periods.put("C-0002", RunFixtures.performingPeriod("C-0002"));
        return RunFixtures.harness(
            List.of("C-0001", "C-0002"), states, periods, RunFixtures.EXPLODING_SOLVER);
    }

    private static RunClose.ClosePresentation close(
        RunFixtures.Harness harness, RunAggregate aggregate, Money glBalance) {
        // The engine's contractual leg for each contract that came back, and the CBS feed scoped
        // to the same set. Both sides carry BILLED_INTEREST, which is the fixtures' published
        // 5,298.16 — so RC-1 is reached with a figure on each side. It cannot be an assertion that
        // two independent systems agree; no fixture can arrange that. RunCloseTest is where a
        // disagreement and a presence difference are exercised.
        List<ContractualLegInterest> engineLines = new ArrayList<>();
        List<String> cbsContracts = new ArrayList<>();
        for (var result : aggregate.results()) {
            if (result.isComputed()) {
                engineLines.add(new ContractualLegInterest(
                    result.contractId(), RunFixtures.PERIOD_ID, RunFixtures.BILLED_INTEREST));
                cbsContracts.add(result.contractId());
            }
        }
        return RunClose.present(
            RunFixtures.requestForClose(harness.request(), GCA_ACCOUNT, glBalance, cbsContracts),
            aggregate, GCA_ACCOUNT, engineLines, portfolioTies(),
            closingPeriod(), "financial.controller", CLOSED_AT, List.of(), List.of());
    }

    @Nested
    @DisplayName("the obligation list is satisfied by the pipeline it describes")
    class TheDeclaredObligations {

        @Test
        @DisplayName("a real run publishes every invariant the aggregate is answerable for")
        void thePipelineSatisfiesItsOwnObligations() {
            // THE test in this class. RunAggregate.POPULATION_INVARIANTS declares SL-2 and ST-2 on
            // the strength of a reading of ContractPipeline.assertions(). If that reading is wrong
            // in either direction the consequence is severe and opposite: an obligation the
            // pipeline does not satisfy blocks every close in production, and one it satisfies
            // trivially catches nothing.
            RunFixtures.Harness harness = twoPerformingContracts();
            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            assertThat(aggregate.computedCount())
                .as("both contracts computed, so the obligations are owed")
                .isEqualTo(2);
            assertThat(aggregate.unassertedInvariants())
                .as("a declared obligation the pipeline does not publish would block every"
                    + " performing book's close; %s", aggregate.blockingReasons())
                .isEmpty();
            assertThat(aggregate.invariants()).extracting(com.crisil.eir.domain.InvariantResult::id)
                .contains(InvariantId.SL_2, InvariantId.ST_2);
        }

        @Test
        @DisplayName("S3-1 is silent on a performing book, which is why it is not an obligation")
        void stageThreeIsNotAnObligation() {
            // The reason S3-1 is deliberately absent from the list, demonstrated rather than
            // argued. The pipeline publishes it only where a Stage 3 reconciliation exists, so on
            // this population it appears nowhere — and had it been declared an obligation, this
            // run would be blocked for a control that had nothing to say.
            RunAggregate aggregate = twoPerformingContracts().run()
                .execute(new ExceptionQueue()).aggregate();

            assertThat(aggregate.invariants()).extracting(com.crisil.eir.domain.InvariantResult::id)
                .doesNotContain(InvariantId.S3_1);
            assertThat(aggregate.unassertedInvariants()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the run reaches the close, and the close reaches the gate")
    class TheSeam {

        @Test
        @DisplayName("a two-contract performing run is presented to the gate on all three controls")
        void theRunReachesTheGate() {
            RunFixtures.Harness harness = twoPerformingContracts();
            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            // The GL is handed the sub-ledger's own closing figures, so SL-1 ties. That makes this
            // an assertion that the control was REACHED with a figure, not that two independent
            // systems agree — which no fixture can arrange. RunCloseTest.aGlDifferenceBlocks is
            // where a disagreement is exercised.
            Money subLedger = Money.zero(Money.INR);
            for (var result : aggregate.results()) {
                if (result.isComputed()) {
                    subLedger = subLedger.plus(result.closingGca());
                }
            }
            RunClose.ClosePresentation presentation = close(harness, aggregate, subLedger);

            assertThat(presentation.asserted(InvariantId.SL_2)).isTrue();
            assertThat(presentation.asserted(InvariantId.SL_1)).isTrue();
            assertThat(presentation.asserted(InvariantId.RC_1)).isTrue();
            assertThat(presentation.breaches())
                .as("breaches: %s", presentation.breaches())
                .isEmpty();
            assertThat(presentation.runRefusals())
                .as("run refusals: %s", presentation.runRefusals())
                .isEmpty();
            assertThat(presentation.mayClose())
                .as("decision: %s", presentation.decision().describe())
                .isTrue();
            assertThat(presentation.coreBanking().totalCbsBilledInterest())
                .as("2 x 5,298.16, by hand")
                .isEqualTo(Money.inr("10596.32"));
        }

        @Test
        @DisplayName("a quarantined contract carries through the run into a refused close")
        void aQuarantinedContractBlocksTheClose() {
            // The end-to-end statement of FR-905 and 04 § 3 together: the barrier isolates the
            // malformed contract so the other two are computed, and the close then refuses because
            // the exception is unresolved. Both halves were tested; the path between them was not.
            Map<String, com.crisil.eir.application.port.ContractStateSource.OpeningState> states =
                new LinkedHashMap<>();
            states.put("C-0001", RunFixtures.performingState());
            states.put("C-0003", RunFixtures.performingState());
            Map<String, ContractPeriod> periods = new LinkedHashMap<>();
            periods.put("C-0001", RunFixtures.performingPeriod("C-0001"));
            periods.put("C-0002", RunFixtures.performingPeriod("C-0002"));
            periods.put("C-0003", RunFixtures.performingPeriod("C-0003"));
            RunFixtures.Harness harness = RunFixtures.harness(
                List.of("C-0001", "C-0002", "C-0003"), states, periods,
                RunFixtures.EXPLODING_SOLVER);

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();
            assertThat(aggregate.computedCount()).isEqualTo(2);
            assertThat(aggregate.quarantinedCount()).isEqualTo(1);

            Money subLedger = Money.zero(Money.INR);
            for (var result : aggregate.results()) {
                if (result.isComputed()) {
                    subLedger = subLedger.plus(result.closingGca());
                }
            }
            RunClose.ClosePresentation presentation = close(harness, aggregate, subLedger);

            assertThat(presentation.breaches())
                .as("every total ties over the two that came back — a contract missing from both"
                    + " sides of a reconciliation is missing from neither: %s",
                    presentation.breaches())
                .isEmpty();
            assertThat(presentation.mayClose()).isFalse();
            assertThat(presentation.runRefusals())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("exceptions are unresolved"));
        }
    }
}
