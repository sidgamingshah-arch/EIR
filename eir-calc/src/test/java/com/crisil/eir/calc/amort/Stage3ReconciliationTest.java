package com.crisil.eir.calc.amort;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariant S3-1 as the four-way reconciliation FR-605 names (03 § 7.3).
 *
 * <p><b>Where the expected figures come from.</b> Reference case 5, cross-checked in Python at 28
 * significant digits rather than read off the engine:
 *
 * <pre>
 *   GCA opening (month 13)  528,407.32
 *   EIR                     1.04214918% per month
 *   allowance (40%)         211,362.928
 *   E = GCA x EIR           5,506.792552439976   -> presented 5,506.79   (case 5 (a))
 *   U = allowance x EIR     2,202.7170209759904  -> presented 2,202.72   (case 5 (c))
 *   N = E - U               3,304.0755314639856  -> presented 3,304.08   (case 5 (b))
 *   contractual billed      5,298.16                                     (case 5)
 *   closing GCA = 528,407.32 + E                 533,914.112552439976
 * </pre>
 *
 * <p>The three presented figures match case 5's published (a), (b) and (c) exactly, which is the
 * check that the derivation is the document's and not the engine's.
 */
class Stage3ReconciliationTest {

    private static final Money GCA_OPEN = Money.inr("528407.32");
    private static final Money ALLOWANCE = Money.inr("211362.928");
    private static final Rate EIR = Rate.monthly(new BigDecimal("0.0104214918"));
    private static final Money BILLED = Money.inr("5298.16");
    private static final Money GCA_CLOSE = Money.inr("533914.112552439976");
    private static final Money NIL = Money.zero(Money.INR);

    private static Stage3Decomposition suppressedPeriod() {
        return Stage3Decomposition.forPeriod(GCA_OPEN, ALLOWANCE, EIR, BILLED, Stage.STAGE_3);
    }

    @Nested
    @DisplayName("the reference-case period reconciles on all four legs")
    class CaseFive {

        @Test
        @DisplayName("no cash, everything billed suspended: four legs nil")
        void reconciles() {
            Stage3Decomposition period = suppressedPeriod();
            assertThat(period.grossBasisInterest().atPresentationScale())
                .as("the derivation ties to case 5 (a) before anything is reconciled")
                .hasToString("INR 5506.79");

            SuspenseLedger suspense = SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL);
            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE, NIL, NIL, BILLED, period, suspense);

            assertThat(reconciliation.reconciles()).isTrue();
            assertThat(reconciliation.fourWay().id()).isEqualTo(InvariantId.S3_1);
            assertThat(reconciliation.residualsByLeg())
                .as("every leg exactly nil, not within a tolerance")
                .allSatisfy(residual -> assertThat(residual.signum()).isZero());
        }

        @Test
        @DisplayName("a part payment reconciles when the cash and the recovery agree")
        void partPaymentReconciles() {
            // 4,000 received and applied to interest, matched by a 4,000 recovery out of suspense.
            // GCA: 528,407.32 + 5,506.792552439976 − 4,000 = 529,914.112552439976
            // Suspense: 0 + 5,298.16 − 4,000 = 1,298.16
            SuspenseLedger suspense = SuspenseLedger.forPeriod(
                NIL, BILLED, Money.inr("4000.00"), NIL);
            assertThat(suspense.closingBalance()).isEqualTo(Money.inr("1298.16"));

            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, Money.inr("529914.112552439976"), NIL, Money.inr("4000.00"),
                BILLED, suppressedPeriod(), suspense);
            assertThat(reconciliation.reconciles()).isTrue();
        }
    }

    @Nested
    @DisplayName("each leg breaks on its own, and says which one it was")
    class Legs {

        @Test
        @DisplayName("leg 1: a roll-forward at the wrong rate")
        void rollForwardBreaks() {
            // Closing GCA short by 100.00 — the shape of a roll-forward that ran at a rate other
            // than the EIR the decomposition used, which is the failure this leg exists for.
            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE.minus(Money.inr("100.00")), NIL, NIL, BILLED,
                suppressedPeriod(), SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL));

            assertThat(reconciliation.reconciles()).isFalse();
            assertThat(reconciliation.fourWay().detail())
                .contains("1 of 4 legs broken")
                .contains("carrying-amount roll-forward out by INR -100.00");
            assertThat(reconciliation.fourWay().deviation())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("leg 2: the ledger charged something other than what was billed")
        void suspenseChargeBreaks() {
            // Charged 5,000.00 against 5,298.16 billed: 298.16 of billed interest that reached
            // neither income nor the suspense ledger. Two systems disagreeing is exactly what
            // this leg reconciles, which is why the billed figure is taken from billing rather
            // than read back off the decomposition.
            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE, NIL, NIL, BILLED, suppressedPeriod(),
                SuspenseLedger.forPeriod(NIL, Money.inr("5000.00"), NIL, NIL));

            assertThat(reconciliation.fourWay().detail())
                .contains("suspense ledger movement against billed interest out by INR -298.16");
            assertThat(reconciliation.fourWay().deviation())
                .isEqualByComparingTo(new BigDecimal("298.16"));
        }

        @Test
        @DisplayName("leg 3: income recognised on a Stage 3 contract")
        void recognitionBreaks() {
            // Built by hand rather than through forPeriod, which cannot produce it — the point of
            // the leg is to catch a decomposition assembled or rehydrated somewhere else with a
            // non-nil recognised figure while the ledger says Stage 3.
            Stage3Decomposition leaked = new Stage3Decomposition(
                Money.inr("5506.792552439976"), Money.inr("3304.0755314639856"),
                Money.inr("2202.7170209759904"), Money.inr("3304.08"), BILLED,
                Stage.STAGE_3, BigDecimal.ONE, List.of());

            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE, NIL, NIL, BILLED, leaked,
                SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL));

            assertThat(reconciliation.fourWay().detail())
                .contains("recognised income against the stage out by INR 3304.08");
        }

        @Test
        @DisplayName("leg 4: interest both suspended and received — the double count")
        void doubleCountBreaks() {
            // 4,000 applied to interest with nothing recovered out of suspense. The same rupees
            // are now in the suspense balance and in the cash book, and no other leg notices:
            // the GCA roll-forward is satisfied by the cash, the charge equals the billing, and
            // recognition is nil. This is the leg that exists for it.
            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, Money.inr("529914.112552439976"), NIL, Money.inr("4000.00"),
                BILLED, suppressedPeriod(), SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL));

            assertThat(reconciliation.fourWay().detail())
                .contains("1 of 4 legs broken")
                .contains("cash applied to interest against suspense recovered out by INR 4000.00");
        }
    }

    @Nested
    @DisplayName("one result, and the reasons it has to be one")
    class OneResult {

        @Test
        @DisplayName("two breaks in opposite directions do not net to a reconciled period")
        void oppositeBreaksDoNotNet() {
            // The classic way a reconciliation control passes while being broken twice. The legs
            // have to be genuinely independent for this to be a real test: the first attempt
            // moved the closing GCA down by 100.00 and the cash up by 100.00, and those cancel
            // INSIDE leg 1, because the cash is subtracted from the expected close. Leg 2 long by
            // 100.00 and leg 4 short by 100.00 are independent, and a signed sum of them is nil.
            //
            // Charged 5,398.16 against 5,298.16 billed (leg 2 = +100.00) and 100.00 recovered
            // against no cash applied to interest (leg 4 = −100.00). Suspense closes at
            // 0 + 5,398.16 − 100.00 = 5,298.16, so nothing else notices either.
            SuspenseLedger crossed = SuspenseLedger.forPeriod(
                NIL, Money.inr("5398.16"), Money.inr("100.00"), NIL);
            assertThat(crossed.closingBalance()).isEqualTo(Money.inr("5298.16"));

            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE, NIL, NIL, BILLED, suppressedPeriod(), crossed);

            assertThat(reconciliation.residualOn(1)).isEqualTo(Money.inr("100.00"));
            assertThat(reconciliation.residualOn(3)).isEqualTo(Money.inr("-100.00"));
            assertThat(reconciliation.reconciles()).isFalse();
            assertThat(reconciliation.fourWay().deviation())
                .as("200.00, not nil — the residuals are summed in absolute terms")
                .isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(reconciliation.fourWay().detail()).contains("2 of 4 legs broken");
        }

        @Test
        @DisplayName("four legs under one id would lose three deviations")
        void conjunctionWouldHaveLostThem() {
            // The mechanism the single-result decision rests on, asserted rather than assumed. If
            // conjunction kept every deviation, four results under S3_1 would have been harmless
            // and none of this shape was needed.
            InvariantResult first = InvariantResult.fail(
                InvariantId.S3_1, "leg 1", new BigDecimal("100.00"));
            InvariantResult second = InvariantResult.fail(
                InvariantId.S3_1, "leg 4", new BigDecimal("4000.00"));

            InvariantResult merged = InvariantResult.conjunction(List.of(first, second));
            assertThat(merged.deviation())
                .as("the second breach's 4,000.00 is gone, which is why S3-1 publishes once")
                .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("the period's own invariants come first, then S3-1")
        void invariantOrdering() {
            // A reader diagnosing a broken S3-1 wants to know whether ST-2 held first: an ST-2
            // break explains an S3-1 break and the converse is not true.
            Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
                GCA_OPEN, GCA_CLOSE, NIL, NIL, BILLED, suppressedPeriod(),
                SuspenseLedger.forPeriod(NIL, BILLED, NIL, NIL));

            List<InvariantId> ids = reconciliation.invariants().stream()
                .map(InvariantResult::id).toList();
            assertThat(ids).containsExactly(
                InvariantId.ST_2, InvariantId.S3_2, InvariantId.S3_1);
        }
    }

    @Nested
    @DisplayName("the claims that used to wear S3-1 now wear their own ids")
    class IdsSplit {

        @Test
        @DisplayName("staging publishes SG-1 for the rate and SG-2 for the balance")
        void stagingHasItsOwnIds() {
            // Both used to be S3_1, alongside two other claims. One carried a periodic RATE
            // deviation and the rest carried money, so a close aggregating S3-1 deviations was
            // adding a rate difference to rupees — and conjunction meant at most one of the four
            // figures ever surfaced.
            List<InvariantResult> results = Stage3Decomposition.stagingIsNotAnEirEvent(
                EIR, EIR, GCA_OPEN, GCA_OPEN);
            assertThat(results.stream().map(InvariantResult::id).toList())
                .containsExactly(InvariantId.SG_1, InvariantId.SG_2)
                .doesNotContain(InvariantId.S3_1);
            assertThat(results).allMatch(InvariantResult::satisfied);
        }

        @Test
        @DisplayName("a re-solve on the way past a stage change is caught, with a rate deviation")
        void resolveOnStagingCaught() {
            // The failure the check exists for: a pipeline routing staging through a general
            // event handler reaches the same code path as a reset and picks up a re-solve.
            Rate drifted = Rate.monthly(new BigDecimal("0.0104300000"));
            List<InvariantResult> results = Stage3Decomposition.stagingIsNotAnEirEvent(
                EIR, drifted, GCA_OPEN, GCA_OPEN);

            InvariantResult rate = results.get(0);
            assertThat(rate.id()).isEqualTo(InvariantId.SG_1);
            assertThat(rate.satisfied()).isFalse();
            assertThat(rate.deviation())
                .as("0.0104300000 − 0.0104214918, a rate and not an amount")
                .isEqualByComparingTo(new BigDecimal("0.0000085082"));
        }

        @Test
        @DisplayName("cure publishes CR-1, and the period's S3-1 is not asserted from there")
        void cureHasItsOwnId() {
            Stage3Decomposition cured = Stage3Decomposition.onCure(
                GCA_OPEN, ALLOWANCE, EIR, BILLED, Stage.STAGE_1);
            List<InvariantId> ids = cured.invariants().stream()
                .map(InvariantResult::id).toList();

            assertThat(ids)
                .as("ST-2 for the split, CR-1 for the no-catch-up; no S3-1, which needs the ledger")
                .containsExactly(InvariantId.ST_2, InvariantId.CR_1)
                .doesNotContain(InvariantId.S3_1);
            assertThat(cured.recognisedIncome().atPresentationScale())
                .as("recognition resumes at the period's own gross-basis interest, and no more")
                .hasToString("INR 5506.79");
        }

        @Test
        @DisplayName("a suppressed period no longer publishes a tautological S3-1")
        void noTautologyLeft() {
            // The removed claim was that billed interest splits into recognised income and
            // suspense, asserted three lines below the two lines constructing exactly that split.
            // It could not fail, and it was occupying the id of a control that was not being run.
            assertThat(suppressedPeriod().invariants().stream().map(InvariantResult::id).toList())
                .containsExactly(InvariantId.ST_2, InvariantId.S3_2);
        }
    }
}
