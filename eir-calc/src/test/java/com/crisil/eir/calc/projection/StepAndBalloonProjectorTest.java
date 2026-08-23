package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two shapes whose instalments are solved rather than derived from the
 * principal: the step ladder and the balloon.
 *
 * <p>Both reduce to the same statement — the instalments must discount to the
 * principal at the contractual rate — and both have a well-known way of getting it
 * wrong. Sizing a balloon's instalments on the full principal and adding the balloon
 * on top over-collects by the balloon's present value; sizing a step ladder on the
 * first rung and letting the ladder run over-collects by whatever the ladder adds.
 * The assertion that catches either is the same: discount the projected leg at the
 * contractual rate and it must come back to the principal.
 */
class StepAndBalloonProjectorTest {

    private static final BigDecimal ONE_PERCENT = bd("0.01");

    private final StepScheduleProjector step = new StepScheduleProjector();
    private final BalloonProjector balloon = new BalloonProjector();

    private static ContractTerms stepUp() {
        return shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED).withStepFactor(bd("1.10"));
    }

    private static ContractTerms stepDown() {
        return shaped(ScheduleShape.STEP_DOWN, 24, RateType.FIXED).withStepFactor(bd("0.90"));
    }

    private static ContractTerms balloonTerms() {
        return shaped(ScheduleShape.BALLOON, 24, RateType.FIXED).withBalloon(Money.inr("200000"));
    }

    private static BigDecimal presentValueAtContractualRate(ProjectionResult result) {
        return Discounting.presentValue(ONE_PERCENT, result.contractual(),
            result.recommendedConvention());
    }

    @Test
    @DisplayName("the step-up ladder rises by the factor at each step and discounts to the principal")
    void stepUpLadderRisesAndPricesToPar() {
        ContractTerms terms = stepUp();

        // Steps once a year by default, taken from the schedule's own frequency: periods
        // 1 to 12 pay the base rung and 13 to 24 pay it multiplied by 1.10.
        assertThat(step.stepEveryPeriods(terms)).isEqualTo(12);
        assertThat(step.billedInstalmentAt(terms, 1).amount()).isEqualByComparingTo(bd("44959.54"));
        assertThat(step.billedInstalmentAt(terms, 12).amount()).isEqualByComparingTo(bd("44959.54"));
        assertThat(step.billedInstalmentAt(terms, 13).amount()).isEqualByComparingTo(bd("49455.50"));
        assertThat(step.billedInstalmentAt(terms, 24).amount()).isEqualByComparingTo(bd("49455.50"));
        // The first rung is below the level annuity of 47,073.47 and the second above it,
        // which is the whole point of the ladder.
        assertThat(step.billedInstalmentAt(terms, 1).amount()).isLessThan(bd("47073.47"));
        assertThat(step.billedInstalmentAt(terms, 13).amount()).isGreaterThan(bd("47073.47"));

        assertThat(presentValueAtContractualRate(step.project(terms, List.of())))
            .as("the whole ladder discounts to the principal at the contractual rate, "
                + "give or take the paise the billed rungs were rounded to")
            .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
    }

    @Test
    @DisplayName("a step-down ladder falls, and its first rung is above the level annuity")
    void stepDownLadderFalls() {
        ContractTerms terms = stepDown();

        assertThat(step.billedInstalmentAt(terms, 1).amount()).isEqualByComparingTo(bd("49395.99"));
        assertThat(step.billedInstalmentAt(terms, 13).amount()).isEqualByComparingTo(bd("44456.40"));
        assertThat(step.billedInstalmentAt(terms, 1).amount())
            .isGreaterThan(step.billedInstalmentAt(terms, 13).amount());
        assertThat(presentValueAtContractualRate(step.project(terms, List.of())))
            .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
    }

    @Test
    @DisplayName("the step interval is configuration, not a constant of the shape")
    void stepIntervalIsConfigurable() {
        // Direct summation rather than a closed form, precisely so that a product which
        // steps every second year — or every six months, as here — does not need a new
        // formula.
        StepScheduleProjector everySixMonths = new StepScheduleProjector(6);
        ContractTerms terms = stepUp();

        assertThat(everySixMonths.stepEveryPeriods(terms)).isEqualTo(6);
        assertThat(everySixMonths.billedInstalmentAt(terms, 1).amount())
            .isEqualByComparingTo(bd("40861.10"));
        assertThat(everySixMonths.billedInstalmentAt(terms, 7).amount())
            .isEqualByComparingTo(bd("44947.21"));
        assertThat(everySixMonths.billedInstalmentAt(terms, 13).amount())
            .isEqualByComparingTo(bd("49441.94"));
        assertThat(presentValueAtContractualRate(everySixMonths.project(terms, List.of())))
            .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
    }

    @Test
    @DisplayName("a declared shape that contradicts the factor is rejected")
    void namingAndArithmeticMustAgree() {
        // The declared shape reaches disclosure and the factor reaches the schedule.
        // Letting them disagree puts a contract in the wrong population in every report
        // that groups by shape.
        ContractTerms upWithFallingFactor =
            shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED).withStepFactor(bd("0.90"));
        ContractTerms downWithRisingFactor =
            shaped(ScheduleShape.STEP_DOWN, 24, RateType.FIXED).withStepFactor(bd("1.10"));

        assertThatThrownBy(() -> step.project(upWithFallingFactor, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STEP_UP needs a factor above 1")
            .hasMessageContaining("declare the shape the schedule actually has");
        assertThatThrownBy(() -> step.project(downWithRisingFactor, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STEP_DOWN needs a factor below 1");
        // A factor of exactly 1 is a level annuity and belongs to the annuity shape.
        assertThatThrownBy(() -> step.project(
            shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED).withStepFactor(bd("1")), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a contractual step is not a market movement")
    void aStepIsNotARepricing() {
        // Its driver is STEP_UP_PREDETERMINED, which routes to a catch-up rather than to a
        // rate reset (6.1): the step is known at inception and is already inside the EIR,
        // so a subsequent step is not new information about price. The projection is where
        // that shows up — the whole ladder is in the vector the rate is solved over.
        ProjectionResult result = step.project(stepUp(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(24);
        assertThat(future).extracting(CashFlow::kind).containsOnly(FlowKind.COMBINED_EMI);
        assertThat(future.get(12).amount().amount())
            .as("the month-13 step is in the vector from day one, not applied later as an event")
            .isGreaterThan(future.get(11).amount().amount());
    }

    @Test
    @DisplayName("balloon instalments amortise the principal less the present value of the lump")
    void balloonInstalmentsAreSizedOnTheDiscountedLump() {
        ContractTerms terms = balloonTerms();

        // A = annuity(P - L*(1+i)^-n, i, n). Sizing on the full principal and adding the
        // balloon on top is the common error and over-collects by the balloon's present
        // value — here by nearly 8,000 a month.
        Money amortising = Money.inr("1000000").minus(
            Money.inr("200000").times(Precision.discountFactor(ONE_PERCENT, bd("24"))));
        assertThat(balloon.instalment(terms).amount())
            .isEqualByComparingTo(Annuity.instalment(amortising, ONE_PERCENT, 24).amount());
        assertThat(balloon.billedInstalment(terms).amount()).isEqualByComparingTo(bd("39658.78"));
        assertThat(balloon.terminalLumpSum(terms).amount()).isEqualByComparingTo(bd("200000"));

        assertThat(presentValueAtContractualRate(balloon.project(terms, List.of())))
            .as("instalments and lump together discount to the principal")
            .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
    }

    @Test
    @DisplayName("the lump is its own flow at maturity, sharing the period with the final instalment")
    void theLumpIsASeparateFlow() {
        ProjectionResult result = balloon.project(balloonTerms(), case1Fees());
        List<CashFlow> atMaturity = result.contractual().future().stream()
            .filter(flow -> flow.periodIndex() == 24)
            .toList();

        assertThat(result.contractual().future()).hasSize(25);
        assertThat(atMaturity).hasSize(2);
        assertThat(atMaturity).extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.COMBINED_EMI, FlowKind.BALLOON);
        assertThat(atMaturity).extracting(CashFlow::date).containsOnly(balloonTerms().maturityDate());
    }

    @Test
    @DisplayName("a lease residual is emitted as a residual and not as a balloon")
    void aLeaseResidualIsItsOwnKind() {
        // The arithmetic is identical and the two are different assets to a controller, so
        // the reconciliation legs need to tell them apart.
        ContractTerms lease = shaped(ScheduleShape.BALLOON, 24, RateType.FIXED)
            .withResidualValue(Money.inr("150000"));

        ProjectionResult result = balloon.project(lease, List.of());

        assertThat(balloon.billedInstalment(lease).amount()).isEqualByComparingTo(bd("41512.45"));
        assertThat(result.contractual().future()).filteredOn(flow -> flow.periodIndex() == 24)
            .extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.COMBINED_EMI, FlowKind.RESIDUAL_VALUE);
        // Both together where a contract carries both.
        ContractTerms both = lease.withBalloon(Money.inr("50000"));
        assertThat(balloon.terminalLumpSum(both).amount()).isEqualByComparingTo(bd("200000"));
        assertThat(balloon.project(both, List.of()).contractual().future())
            .filteredOn(flow -> flow.periodIndex() == 24)
            .extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.COMBINED_EMI, FlowKind.BALLOON,
                FlowKind.RESIDUAL_VALUE);
    }

    @Test
    @DisplayName("a lump that discounts to the whole principal is a bullet, not a balloon")
    void anOversizedLumpIsRejected() {
        ContractTerms notABalloon = shaped(ScheduleShape.BALLOON, 24, RateType.FIXED)
            .withBalloon(Money.inr("2000000"));

        assertThatThrownBy(() -> balloon.instalment(notABalloon))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no positive instalment exists")
            .hasMessageContaining("this is a bullet or a discount instrument, not a balloon");
    }
}
