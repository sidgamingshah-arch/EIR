package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which time convention a vector licenses (calculation specification 3.10, FR-303).
 *
 * <pre>
 * if all periods uniform AND all flows on period boundaries AND no broken period:
 *       periodic-index          # cheaper, and exactly equivalent here
 * else: actual-date             # required for correctness
 * </pre>
 *
 * <p><strong>Actual dating is the default and the fallback.</strong> The asymmetry
 * is deliberate: falling back to actual dating where periodic indexing would have
 * been valid costs arithmetic, while taking periodic indexing where it is invalid
 * costs correctness, silently, on the instruments where day-count sensitivity is
 * highest. Every test below that expects actual dating is a case where the cheaper
 * convention would have produced a plausible wrong rate.
 */
class ConventionSelectorTest {

    private final AnnuityProjector projector = new AnnuityProjector();

    private static ContractTerms datedAnnuity(LocalDate disbursement, LocalDate firstDue) {
        return ContractTerms.of(Money.inr("1000000"), ONE_PERCENT_MONTHLY, 24, 12,
            disbursement, firstDue, DayCountConvention.ACT_365F, ScheduleShape.ANNUITY_EMI,
            RateType.FIXED);
    }

    @Test
    @DisplayName("a clean uniform monthly annuity takes the periodic index")
    void cleanMonthlyAnnuityTakesPeriodicIndex() {
        FlowVector vector = projector.project(case1(), List.of()).contractual();

        ConventionSelector.Choice choice =
            ConventionSelector.choose(vector, 12, DayCountConvention.THIRTY_360_BOND);

        assertThat(choice.periodicIndexEligible()).isTrue();
        assertThat(choice.convention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
        assertThat(choice.basis())
            .contains("every flow on a period boundary")
            .contains("exactly equivalent and cheaper");
    }

    @Test
    @DisplayName("a broken first period falls back to actual dating")
    void brokenFirstPeriodFallsBackToActualDating() {
        // Disbursed mid-April against a first instalment on 1 June: the first period is
        // 47 days, not one month, and period ordinals stop being a measure of time.
        ContractTerms broken = datedAnnuity(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 6, 1));

        ProjectionResult result = projector.project(broken, List.of());
        ConventionSelector.Choice choice = ConventionSelector.choose(
            result.contractual(), 12, DayCountConvention.ACT_365F);

        assertThat(choice.periodicIndexEligible()).isFalse();
        assertThat(result.recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.ACT_365F));
        assertThat(choice.basis())
            .contains("precondition not met")
            .contains("ACT/365F")
            .contains("required for correctness");
    }

    @Test
    @DisplayName("a mid-period disbursement falls back to actual dating")
    void midPeriodDisbursementFallsBackToActualDating() {
        // Disbursed on the 15th, billed on the 1st: every flow is half a period away
        // from where the periodic index would put it.
        ContractTerms midPeriod = datedAnnuity(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 1));

        assertThat(projector.project(midPeriod, List.of()).recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.ACT_365F));
    }

    @Test
    @DisplayName("a moratorium falls back to actual dating without anyone remembering to say so")
    void moratoriumFallsBackToActualDating() {
        // A capitalising moratorium leaves the early periods with no flows at all, which
        // the precondition check detects as a gap in the period ordinals.
        ContractTerms moratorium = case1().withMoratorium(6, true);

        ProjectionResult result = new MoratoriumProjector().project(moratorium, List.of());

        assertThat(result.contractual().periodicIndexEligible(12)).isFalse();
        assertThat(result.recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND));
    }

    @Test
    @DisplayName("an actual-dated 28/31-day month pair voids the periodic index")
    void monthEndDriftVoidsThePeriodicIndex() {
        // Disbursed 31 January: the first instalment on 28 February is one month away by
        // the calendar, and the second on 28 March is not — 31 January plus two months is
        // 31 March. Month-ends are easy to get subtly wrong and every discount factor
        // moves when they are.
        ContractTerms monthEnd = datedAnnuity(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));

        assertThat(projector.project(monthEnd, List.of()).recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.ACT_365F));
    }

    @Test
    @DisplayName("a single terminal flow cannot be periodically indexed")
    void singleFlowBulletFallsBackToActualDating() {
        // A zero-coupon bullet has one flow at period 60 and nothing at periods 1 to 59.
        // The check requires every ordinal up to the last to be present, because a vector
        // with gaps is not a uniform schedule whatever its endpoints look like.
        FlowVector bullet = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(DISBURSEMENT.plusMonths(60), 60, Money.inr("1816696.70"), FlowKind.PRINCIPAL)));

        assertThat(bullet.periodicIndexEligible(12)).isFalse();
        assertThat(ConventionSelector.select(bullet, 12, DayCountConvention.THIRTY_360_BOND))
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND));
    }

    @Test
    @DisplayName("actual dating is the fallback even where there is nothing to check")
    void actualDatingIsTheDefault() {
        // No future flows, so no evidence that the cheaper convention is safe — and
        // absence of evidence resolves to actual dating, never to the optimisation.
        FlowVector inceptionOnly = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT)));

        ConventionSelector.Choice choice =
            ConventionSelector.choose(inceptionOnly, 12, DayCountConvention.ACT_365F);

        assertThat(choice.periodicIndexEligible()).isFalse();
        assertThat(choice.convention()).isInstanceOf(TimeConvention.ActualDate.class);
        // A frequency that does not divide 12 is also not indexable.
        assertThat(ConventionSelector.choose(
            projector.project(case1(), List.of()).contractual(), 5, DayCountConvention.ACT_365F)
            .periodicIndexEligible()).isFalse();
    }

    @Test
    @DisplayName("the two conventions agree on a vector that licenses both")
    void thePeriodicIndexIsExactlyEquivalentWhereItIsLicensed() {
        // The claim in the specification is not "close enough" but "exactly equivalent",
        // and this is what that means: discounting the licensed vector at 1% a month
        // under period ordinals, and at the same rate's annual effective form under
        // 30/360 year fractions, produces the same present value to the last working
        // digit. That is why taking the cheaper convention is free rather than
        // approximate — and why taking it unlicensed is a wrong rate rather than a slow
        // one.
        FlowVector vector = projector.project(case1(), CaseFixtures.case1Fees()).contractual();
        Rate monthly = Rate.monthly(bd("0.01"));
        TimeConvention indexed = new TimeConvention.PeriodicIndex(12);
        TimeConvention dated = new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND);

        BigDecimal underIndex = Discounting.presentValue(monthly.periodic(), vector, indexed);
        BigDecimal underDates = Discounting.presentValue(
            ConventionSelector.rateUnder(dated, monthly).periodic(), vector, dated);

        assertThat(underIndex).isEqualByComparingTo(underDates);
    }

    @Test
    @DisplayName("rateUnder restates a rate in the units the convention implies")
    void rateUnderIsTheSeamBetweenRateAndConvention() {
        Rate monthly = Rate.monthly(bd("0.01"));

        // Periodic index at the rate's own frequency: nothing to convert.
        assertThat(ConventionSelector.rateUnder(new TimeConvention.PeriodicIndex(12), monthly))
            .isEqualTo(monthly);
        // Actual dating measures tau in years, so the same rate has to be stated as its
        // annual effective equivalent — 1% a month is 12.6825030132% a year, not 12%.
        // Pairing a per-period rate with a year fraction is an annualisation round-trip in
        // disguise and produces a plausible, wrong number.
        Rate annual = ConventionSelector.rateUnder(
            new TimeConvention.ActualDate(DayCountConvention.ACT_365F), monthly);
        assertThat(annual.periodsPerYear()).isEqualTo(1);
        assertThat(annual.periodic()).isEqualByComparingTo(bd("0.126825030132"));
        assertThat(annual.periodic().scale())
            .as("a rate a ledger rolls forward with is at storage precision")
            .isEqualTo(Precision.RATE_SCALE);
    }

    @Test
    @DisplayName("a periodic index at another frequency is a projection defect, not a units mismatch")
    void rateUnderRefusesAFrequencyMismatch() {
        // This is not repairable by conversion: it means the schedule and the rate
        // disagree about the compounding period, and coercing it would bury that.
        assertThatThrownBy(() -> ConventionSelector.rateUnder(
            new TimeConvention.PeriodicIndex(4), Rate.monthly(bd("0.01"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("convention compounds x4 but the rate is quoted x12");
    }

    @Test
    @DisplayName("both legs must license the periodic index before it is taken")
    void bothLegsMustAgreeOnTheConvention() {
        // The EIR is solved over the expected leg and the 10% test and the catch-up run
        // over the contractual one. Letting them carry different conventions would put a
        // convention difference inside a comparison meant to isolate a cash-flow one.
        ContractTerms truncated = case1().withLives(12, 24, "CPR-based behavioural life");

        ProjectionResult result = projector.project(truncated, List.of());

        assertThat(result.contractual().periodicIndexEligible(12)).isTrue();
        assertThat(result.expected().periodicIndexEligible(12)).isTrue();
        assertThat(result.recommendedConvention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
    }
}
