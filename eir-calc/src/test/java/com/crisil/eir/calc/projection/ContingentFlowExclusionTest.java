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
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contingent flows are excluded from every projection, on every shape
 * (calculation specification 3.3, FR-206).
 *
 * <p>A prepayment penalty, a late fee or a bounce charge is excluded at inception
 * <em>regardless of being contractually specified</em>, and recognised in the period
 * the event occurs. The check is asserted by every projector on its own output
 * rather than trusted, because a contingent flow inside an EIR vector prices an
 * event that has not happened into the yield of every period before it.
 *
 * <p>The parameterised case walks every shape in the projector family. A per-shape
 * test would pass for eleven projectors and silently omit the twelfth; this one
 * fails when a projector is added without the check.
 */
class ContingentFlowExclusionTest {

    private static Stream<Arguments> everyProjector() {
        ContractTerms structured = shaped(ScheduleShape.STRUCTURED, 24, RateType.FIXED);
        return Stream.of(
            Arguments.of("annuity", new AnnuityProjector(), case1()),
            Arguments.of("moratorium, capitalising", new MoratoriumProjector(),
                case1().withMoratorium(6, true)),
            Arguments.of("moratorium, servicing", new MoratoriumProjector(),
                case1().withMoratorium(6, false)),
            Arguments.of("step up", new StepScheduleProjector(),
                shaped(ScheduleShape.STEP_UP, 24, RateType.FIXED).withStepFactor(bd("1.10"))),
            Arguments.of("step down", new StepScheduleProjector(),
                shaped(ScheduleShape.STEP_DOWN, 24, RateType.FIXED).withStepFactor(bd("0.90"))),
            Arguments.of("balloon", new BalloonProjector(),
                shaped(ScheduleShape.BALLOON, 24, RateType.FIXED).withBalloon(Money.inr("200000"))),
            Arguments.of("lease residual", new BalloonProjector(),
                shaped(ScheduleShape.BALLOON, 24, RateType.FIXED)
                    .withResidualValue(Money.inr("150000"))),
            Arguments.of("interest-only bullet", new InterestOnlyBulletProjector(),
                shaped(ScheduleShape.INTEREST_ONLY_BULLET, 60, RateType.FIXED)),
            Arguments.of("zero-coupon bullet", new BulletProjector(),
                shaped(ScheduleShape.BULLET, 60, RateType.FIXED)),
            Arguments.of("discount instrument", new DiscountInstrumentProjector(),
                shaped(ScheduleShape.DISCOUNT_INSTRUMENT, 12, RateType.FIXED)),
            Arguments.of("revolving", new RevolvingProjector(),
                shaped(ScheduleShape.REVOLVING, 12, RateType.FLOATING)),
            Arguments.of("tranched", new TranchedProjector(List.of(
                Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
                Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("400000")))),
                shaped(ScheduleShape.TRANCHED, 24, RateType.FIXED)),
            Arguments.of("external schedule", new ExternalScheduleProjector(List.of(
                Instalment.of(FIRST_DUE, 1, Money.inr("500000")),
                Instalment.of(FIRST_DUE.plusMonths(1), 2, Money.inr("530000")))),
                structured),
            Arguments.of("B5.4.4 repricing shortcut",
                new RepricingShortcutProjector(new AnnuityProjector(), 12),
                shaped(ScheduleShape.ANNUITY_EMI, 24, RateType.FLOATING)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyProjector")
    @DisplayName("every projector's output carries no contingent flow and passes IC-1")
    void everyProjectorProducesACleanVector(String label, CashflowProjector projector,
            ContractTerms terms) {
        assertThat(projector.supports(terms))
            .as("%s must claim the terms it is registered for", label)
            .isTrue();

        ProjectionResult result = projector.project(terms, case1Fees());

        // requireNoContingentFlows throws rather than returning a flag, so a clean
        // return is the assertion.
        assertThat(result.contractual().requireNoContingentFlows()).isSameAs(result.contractual());
        assertThat(result.expected().requireNoContingentFlows()).isSameAs(result.expected());
        assertThat(result.contractual().flows()).noneMatch(CashFlow::contingent);
        assertThat(result.expected().flows()).noneMatch(CashFlow::contingent);
        assertThat(result.initialRecognitionCheck().satisfied())
            .as("%s must open its ledger at the net cash flow at inception (IC-1)", label)
            .isTrue();
        assertThat(projector.label()).isNotBlank();
    }

    @Test
    @DisplayName("a contingent flow cannot reach a projection result at all")
    void aContingentFlowIsRejectedAtConstruction() {
        // The projector never builds one, so this is the boundary that catches a vector
        // assembled anywhere else — a supplied schedule, a replayed extract, a test.
        FlowVector withPenalty = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(FIRST_DUE, 1, Money.inr("1010000"), FlowKind.COMBINED_EMI),
            CashFlow.contingent(FIRST_DUE, 1, Money.inr("5000"), FlowKind.INTEGRAL_FEE_RECEIVED)));

        assertThatThrownBy(() -> ProjectionResult.of(withPenalty, withPenalty,
            Money.inr("1000000"), TimeConvention.PeriodicIndex.monthly(), true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contingent flows must not enter the EIR projection");
    }

    @Test
    @DisplayName("a foreclosure charge is a fee classification question, not a projection one")
    void aForeclosureChargeNeverReachesTheVector() {
        // RBI's restrictions on foreclosure charges for floating-rate individual loans
        // mean there is little prepayment-penalty cash flow to model in the first place —
        // a variable that dominates the equivalent calculation in other jurisdictions is
        // largely absent here. What arrives is classified AS_INCURRED and is dropped by
        // the same rule that drops every non-integral posting.
        List<FeePosting> withForeclosure = List.of(
            FeePosting.received("PROCESSING_FEE", Money.inr("15000"), DISBURSEMENT,
                FeeClassification.INTEGRAL),
            FeePosting.received("FORECLOSURE_CHARGE", Money.inr("25000"), DISBURSEMENT,
                FeeClassification.AS_INCURRED));

        ProjectionResult result = new AnnuityProjector().project(case1(), withForeclosure);

        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("985000.00"));
        assertThat(result.contractual().atInception()).hasSize(2);
    }
}
