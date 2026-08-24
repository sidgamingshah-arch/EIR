package com.crisil.eir.calc.solver;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * The solver's round-trip property: a vector built from a rate solves back to that
 * rate.
 *
 * <p>The calibration test in {@link SolverCalibrationTest} asserts this on one
 * instrument where the answer is known by hand. These properties assert the same
 * thing across the rate and tenor space an Indian bank's book actually occupies — one
 * basis point to five hundred per period, one period to thirty years — because a
 * solver that is exact on 1% a month over sixty months and drifts at 4.37% a month
 * over 293 months would pass the fixture and fail the portfolio.
 *
 * <p>Rates are generated as integer basis points and divided, never sampled as
 * binary floating point: the point of the exercise is that the arithmetic is exact,
 * and generating inputs that cannot represent 0.01 would test the opposite.
 *
 * <p>The flows are carried unrounded. A billed schedule rounds to paise and the
 * rate that reprices it is then not the rate that generated it — a real and
 * documented effect (the Case 1 residue), and not the one under test here.
 *
 * <p>Named {@code ...PropertiesTest} rather than {@code ...Properties} because
 * surefire's default includes are {@code Test*}, {@code *Test}, {@code *Tests} and
 * {@code *TestCase}, and the parent POM sets no {@code <includes>}. Under the
 * shorter name this class compiled, was packaged, and was silently never executed —
 * three properties absent from every build with nothing in the output to say so.
 */
class SolverRoundTripPropertiesTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 4, 1);
    private static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();
    private static final Money PRINCIPAL = Money.inr("1000000");

    private final RateSolver solver = new BracketedNewtonSolver();

    private static BigDecimal rateOf(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 4);
    }

    @Property(tries = 200)
    void aZeroCouponVectorSolvesBackToTheRateThatBuiltIt(
            @ForAll @IntRange(min = 1, max = 5000) int basisPointsPerPeriod,
            @ForAll @IntRange(min = 1, max = 360) int periods) {

        BigDecimal rate = rateOf(basisPointsPerPeriod);
        Money redemption = PRINCIPAL.times(Precision.onePlusPow(rate, periods));
        FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, PRINCIPAL.negate(), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(periods), periods, redemption, FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(vector, MONTHLY, null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic())
            .as("zero-coupon at %s per period over %s periods", rate.toPlainString(), periods)
            .isEqualByComparingTo(Precision.storedRate(rate));
    }

    @Property(tries = 200)
    void anAnnuityVectorSolvesBackToTheRateThatBuiltIt(
            @ForAll @IntRange(min = 1, max = 5000) int basisPointsPerPeriod,
            @ForAll @IntRange(min = 2, max = 360) int periods) {

        BigDecimal rate = rateOf(basisPointsPerPeriod);
        BigDecimal denominator = BigDecimal.ONE.subtract(
            Precision.discountFactor(rate, BigDecimal.valueOf(periods)), Precision.WORKING);
        Money instalment = PRINCIPAL.times(rate).dividedBy(denominator);
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(ANCHOR, 0, PRINCIPAL.negate(), FlowKind.DISBURSEMENT));
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(
                ANCHOR.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }

        SolveResult result = solver.solve(
            SolveRequest.atInception(FlowVector.of(ANCHOR, Money.INR, flows), MONTHLY, null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.candidateRootCount())
            .as("a conventional annuity has one sign change and one root")
            .isEqualTo(1);
        assertThat(result.rate().periodic())
            .as("annuity at %s per period over %s periods", rate.toPlainString(), periods)
            .isEqualByComparingTo(Precision.storedRate(rate));
    }

    @Property(tries = 100)
    void aFeeAlwaysMovesTheRateInTheDirectionTheFeeIsSigned(
            @ForAll @IntRange(min = 25, max = 300) int basisPointsPerPeriod,
            @ForAll @IntRange(min = 6, max = 240) int periods,
            @ForAll @IntRange(min = 1, max = 100000) int feePaise) {

        // Invariant INV-2, as a property: a net fee received records the asset below par,
        // so it must accrete back up and the EIR must exceed the contractual rate; a net
        // cost paid records it above par and the EIR must fall below. The magnitude is the
        // amortisation's business — the sign is the solver's, and getting it backwards
        // would flatter or understate yield on the entire book.
        BigDecimal rate = rateOf(basisPointsPerPeriod);
        BigDecimal denominator = BigDecimal.ONE.subtract(
            Precision.discountFactor(rate, BigDecimal.valueOf(periods)), Precision.WORKING);
        Money instalment = PRINCIPAL.times(rate).dividedBy(denominator);
        Money fee = Money.of(BigDecimal.valueOf(feePaise, 2), Money.INR);

        List<CashFlow> withFeeReceived = new ArrayList<>();
        withFeeReceived.add(CashFlow.of(ANCHOR, 0, PRINCIPAL.negate(), FlowKind.DISBURSEMENT));
        withFeeReceived.add(CashFlow.of(ANCHOR, 0, fee, FlowKind.INTEGRAL_FEE_RECEIVED));
        List<CashFlow> withCostPaid = new ArrayList<>();
        withCostPaid.add(CashFlow.of(ANCHOR, 0, PRINCIPAL.negate(), FlowKind.DISBURSEMENT));
        withCostPaid.add(CashFlow.of(ANCHOR, 0, fee.negate(), FlowKind.INTEGRAL_COST_PAID));
        for (int period = 1; period <= periods; period++) {
            CashFlow flow = CashFlow.of(
                ANCHOR.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI);
            withFeeReceived.add(flow);
            withCostPaid.add(flow);
        }

        SolveResult feeReceived = solver.solve(SolveRequest.atInception(
            FlowVector.of(ANCHOR, Money.INR, withFeeReceived), MONTHLY, rate));
        SolveResult costPaid = solver.solve(SolveRequest.atInception(
            FlowVector.of(ANCHOR, Money.INR, withCostPaid), MONTHLY, rate));

        assertThat(feeReceived.rateOrThrow().periodic()).isGreaterThan(rate);
        assertThat(costPaid.rateOrThrow().periodic()).isLessThan(rate);
    }
}
