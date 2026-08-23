package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code LMS_AUTHORITATIVE} path: consume the schedule the lending system
 * actually billed rather than deriving one (FR-102, ADR-0004).
 *
 * <p>Strongly preferred in production, whatever the shape. A derived schedule the
 * core banking system did not bill guarantees a reconciliation break every month,
 * which turns the schedule reconciliation control into noise and trains everyone to
 * ignore it. The break is not caused by an error in either system: a lender bills in
 * paise and rounds where its own product configuration says to, and no formula
 * reproduces that for every product, every rounding rule and every mid-life event.
 *
 * <p>So the test of this projector is a test of restraint — the flows out must be the
 * flows in, unrounded, unplugged and unreordered.
 */
class ExternalScheduleProjectorTest {

    /** The Case 1 schedule as a lender would actually bill it, final instalment plugged. */
    private static List<Instalment> billedCase1() {
        List<Instalment> lines = new ArrayList<>();
        for (int period = 1; period <= 24; period++) {
            lines.add(Instalment.of(FIRST_DUE.plusMonths(period - 1L), period,
                Money.inr(period == 24 ? "47073.53" : "47073.47")));
        }
        return lines;
    }

    private static ContractTerms structured() {
        return shaped(ScheduleShape.STRUCTURED, 24, RateType.FIXED);
    }

    @Test
    @DisplayName("the billed schedule is consumed unchanged")
    void theScheduleIsConsumedUnchanged() {
        List<Instalment> billed = billedCase1();
        ExternalScheduleProjector projector = new ExternalScheduleProjector(billed);

        ProjectionResult result = projector.project(structured(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(24);
        for (int index = 0; index < billed.size(); index++) {
            Instalment line = billed.get(index);
            CashFlow flow = future.get(index);
            assertThat(flow.date()).isEqualTo(line.dueOn());
            assertThat(flow.periodIndex()).isEqualTo(line.periodIndex());
            assertThat(flow.kind()).isEqualTo(line.kind());
            assertThat(flow.amount().amount()).isEqualByComparingTo(line.amount().amount());
        }
        // The final instalment is the lender's 47,073.53 and not a derived 47,073.47: this
        // path derives nothing and plugs nothing.
        assertThat(future.get(23).amount().amount()).isEqualByComparingTo(bd("47073.53"));
        assertThat(projector.residuePolicy()).isEqualTo(ResiduePolicy.LMS_AUTHORITATIVE);
    }

    @Test
    @DisplayName("the terminal contractual balance is retained for the reconciliation, not plugged")
    void theTerminalBalanceIsRetained() {
        ExternalScheduleProjector projector = new ExternalScheduleProjector(billedCase1());

        // A non-zero figure here is the lender's own residue and belongs in the
        // reconciliation; adjusting it would recreate exactly the break this path exists
        // to avoid. On this schedule the lender's own final-period plug leaves 0.00 at
        // presentation scale and a sub-paise negative at working precision.
        assertThat(projector.terminalContractualBalance(structured()).amount()
            .setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("-0.000031"));
        assertThat(projector.terminalContractualBalance(structured())
            .atPresentationScale().amount()).isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("split principal and interest lines sharing a date and ordinal are accepted")
    void splitLinesAreAccepted() {
        // Which is why the period ordinal is carried explicitly rather than inferred from
        // position: two lines in one period are two lines.
        ExternalScheduleProjector projector = new ExternalScheduleProjector(List.of(
            Instalment.of(FIRST_DUE, 1, Money.inr("10000.00"), FlowKind.INTEREST),
            Instalment.of(FIRST_DUE, 1, Money.inr("37073.47"), FlowKind.PRINCIPAL)));

        ProjectionResult result = projector.project(structured(), List.of());

        assertThat(result.contractual().future()).hasSize(2);
        assertThat(result.contractual().future()).extracting(CashFlow::periodIndex).containsOnly(1);
        assertThat(result.contractual().future()).extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.INTEREST, FlowKind.PRINCIPAL);
        assertThat(result.recommendedConvention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
    }

    @Test
    @DisplayName("lines are ordered on construction so an unordered feed still projects reproducibly")
    void linesAreSortedOnConstruction() {
        List<Instalment> shuffled = new ArrayList<>(billedCase1());
        java.util.Collections.reverse(shuffled);

        ExternalScheduleProjector projector = new ExternalScheduleProjector(shuffled);

        assertThat(projector.billedSchedule()).extracting(Instalment::periodIndex)
            .isSorted();
        assertThat(projector.project(structured(), case1Fees()).contractual())
            .isEqualTo(new ExternalScheduleProjector(billedCase1())
                .project(structured(), case1Fees()).contractual());
    }

    @Test
    @DisplayName("the projector answers on whether a schedule was supplied, not on the shape")
    void itIsNotAShapeStrategy() {
        // It is not a shape strategy, it is the refusal to guess — so it accepts any shape
        // and a registry that places it first uses the billed schedule wherever one exists.
        ExternalScheduleProjector projector = new ExternalScheduleProjector(billedCase1());

        assertThat(projector.supports(structured())).isTrue();
        assertThat(projector.supports(case1())).isTrue();
        assertThat(projector.supports(shaped(ScheduleShape.BALLOON, 24, RateType.FIXED))).isTrue();
    }

    @Test
    @DisplayName("a schedule in the wrong currency, or dated before inception, is declined")
    void mismatchedSchedulesAreDeclined() {
        ExternalScheduleProjector wrongCurrency = new ExternalScheduleProjector(List.of(
            Instalment.of(FIRST_DUE, 1, Money.of(bd("47073.47"), Currency.getInstance("USD")))));
        ExternalScheduleProjector beforeInception = new ExternalScheduleProjector(List.of(
            Instalment.of(DISBURSEMENT.minusMonths(1), 1, Money.inr("47073.47"))));

        assertThat(wrongCurrency.supports(structured())).isFalse();
        assertThat(beforeInception.supports(structured())).isFalse();
        assertThatThrownBy(() -> beforeInception.project(structured(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("precedes initial recognition");
    }

    @Test
    @DisplayName("an empty schedule is not a schedule")
    void anEmptyScheduleIsRefused() {
        assertThatThrownBy(() -> new ExternalScheduleProjector(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("either supply the billed lines or");
        assertThatThrownBy(() -> Instalment.of(FIRST_DUE, 0, Money.inr("47073.47")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a billed instalment sits in period 1 or later");
    }

    @Test
    @DisplayName("the supplied schedule still opens the ledger at the net cash flow at inception")
    void icOneHoldsOnASuppliedSchedule() {
        ProjectionResult result = new ExternalScheduleProjector(billedCase1())
            .project(structured(), case1Fees());

        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("995000.00"));
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
        assertThat(result.contractual().atInception()).hasSize(3);
    }
}
