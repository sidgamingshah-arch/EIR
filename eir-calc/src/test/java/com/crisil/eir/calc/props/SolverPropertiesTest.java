package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
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
import net.jqwik.api.constraints.LongRange;

/**
 * The solver's two obligations: report the residual of the rate it published, and
 * never publish a rate it did not find.
 *
 * <p>Rates are generated as integer basis points and divided, never sampled as
 * binary floating point. The point of the exercise is that the arithmetic is exact,
 * and generating inputs that cannot represent 0.01 would test the opposite.
 */
class SolverPropertiesTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 4, 1);

    /** Half a unit in the last place of a rate persisted at twelve decimal places (1.4). */
    private static final BigDecimal HALF_ULP_OF_STORED_RATE = new BigDecimal("5E-13");

    private final RateSolver solver = new BracketedNewtonSolver();

    /**
     * Where a solution exists, the residual recorded at the <em>stored</em> rate is
     * within the solver's tolerance plus what rounding the rate to twelve places
     * costs.
     *
     * <p>Specification 4.2 converges on {@code tol_abs = max(1e-10, GCA x 1e-16)},
     * then step 5 rounds to twelve decimal places and re-evaluates {@code |f|}
     * there. Those are two different numbers and the second is larger, often by
     * orders of magnitude: half a unit in the last place of the rate moves the
     * present value by {@code |f'(r)| x 5e-13}, which on a 240-period exposure of a
     * million is around 3e-5 against a tolerance floor of 1e-10. Asserting the
     * tolerance alone against the recorded residual would be asserting something
     * false about a correct engine; asserting nothing would leave the published
     * rate unchecked. The bound is the sum, and it is tight — the recorded residual
     * sits just inside it across the sampled space, which is what makes it a test
     * rather than a licence.
     */
    @Property(tries = 10000, seed = "20270409")
    void theResidualAtTheStoredRateIsWithinToleranceAndRateRounding(
            @ForAll @LongRange(min = 1_000_000L, max = 10_000_000_000L) long principalPaise,
            @ForAll @IntRange(min = 1, max = 3000) int basisPointsPerPeriod,
            @ForAll @IntRange(min = 1, max = 360) int periods,
            @ForAll @IntRange(min = 0, max = 500) int feeBasisPoints) {

        BigDecimal rate = BigDecimal.valueOf(basisPointsPerPeriod, 4);
        Money principal = Money.of(BigDecimal.valueOf(principalPaise, 2), Money.INR);
        Money fee = principal.times(BigDecimal.valueOf(feeBasisPoints, 4)).atPresentationScale();
        FlowVector vector = annuityVector(principal, fee, rate, periods);
        TimeConvention convention = TimeConvention.PeriodicIndex.monthly();

        SolveRequest request = SolveRequest.atInception(vector, convention, rate);
        SolveResult result = solver.solve(request);

        assertThat(result.status())
            .as("an annuity discounted from a positive target has exactly one root")
            .isEqualTo(SolveStatus.SOLVED);
        assertThat(result.candidateRootCount()).isEqualTo(1);
        assertThat(result.rate().periodic().scale())
            .as("the published rate is the persisted rate, at twelve places (1.4)")
            .isEqualTo(Precision.RATE_SCALE);

        BigDecimal roundingAllowance = Discounting
            .derivative(result.rate().periodic(), vector, convention).abs()
            .multiply(HALF_ULP_OF_STORED_RATE, Precision.WORKING);
        assertThat(result.residualAtStoredRate().abs())
            .as("residual at %s over %s periods with %s of fee (tolerance %s, rounding %s)",
                rate.toPlainString(), periods, fee.atPresentationScale(),
                request.absoluteTolerance().toPlainString(), roundingAllowance.toPlainString())
            .isLessThanOrEqualTo(request.absoluteTolerance().add(roundingAllowance));

        // The residual is measured at the rounded rate, so it is also the evidence that
        // rounding is the only thing between the published rate and the root: re-pricing
        // the vector at the published rate must land within the same bound of the target.
        BigDecimal presentValue = Discounting.presentValue(result.rate().periodic(), vector, convention);
        assertThat(presentValue.subtract(request.target().amount()).abs())
            .isLessThanOrEqualTo(request.absoluteTolerance().add(roundingAllowance));
    }

    /**
     * Where no solution exists the result carries no rate — and in particular it
     * does not quietly carry the contractual rate it was seeded with.
     *
     * <p>Section 4.3 is unusually emphatic and it is right to be: silently falling
     * back to the contractual rate is the defect that reproduces the pre-ACPIR
     * position while appearing to have implemented EIR. It produces plausible
     * numbers and leaves no trace, so this property seeds every failing solve with
     * the contractual rate and then insists the failure came back empty. The record
     * itself forbids a rate on a failed status, so the assertion is partly on the
     * type; the point is that the type was chosen to make the defect
     * unrepresentable, and a test that stops checking would not notice the type
     * changing.
     *
     * <p>Two families, and they fail differently — which is the point, because the
     * property that protects the ledger is not "no rate" but "no <em>published</em>
     * rate". A facility that never repays has every future flow an outflow, so
     * {@code f} never changes sign and genuinely has no root: {@code NO_SOLUTION}. A
     * token recovery worth a twenty-thousandth of the advance does have a root, at
     * exactly -99.995% per month, and the standard ladder's -0.9999 floor cannot reach
     * it; the engine escalates, finds it, and flags it. Neither family may ever come
     * back {@code SOLVED}, and neither may ever come back carrying the contractual seed
     * or a zero — that is the invariant, and it holds across both.
     */
    @Property(tries = 10000, seed = "20270410")
    void aVectorWithNothingToCollectIsNeverGivenAPublishedRate(
            @ForAll @LongRange(min = 1_000_000L, max = 10_000_000_000L) long advancePaise,
            @ForAll @IntRange(min = 1, max = 3000) int seedBasisPoints,
            @ForAll @IntRange(min = 1, max = 60) int periods,
            @ForAll boolean tokenRecovery) {

        Money advance = Money.of(BigDecimal.valueOf(advancePaise, 2), Money.INR);
        BigDecimal seed = BigDecimal.valueOf(seedBasisPoints, 4);
        FlowVector vector = tokenRecovery
            ? tokenRecoveryVector(advance)
            : outflowsOnlyVector(advance, periods);

        SolveResult result = solver.solve(SolveRequest.atInception(
            vector, TimeConvention.PeriodicIndex.monthly(), seed));

        // Holds across both families: nothing here is ever publishable, and nothing here
        // is ever the contractual rate or a zero. That is the defect of 4.3.
        assertThat(result.status()).isNotEqualTo(SolveStatus.SOLVED);
        assertThat(result.isSolved()).isFalse();
        assertThat(result.diagnostic()).isNotBlank();
        if (result.hasRate()) {
            assertThat(result.rate().periodic())
                .as("never the contractual seed %s", seed.toPlainString())
                .isNotEqualByComparingTo(seed);
            assertThat(result.rate().periodic()).isNotEqualByComparingTo(BigDecimal.ZERO);
        }

        if (tokenRecovery) {
            // A twenty-thousandth recovered one period out is -99.995% a month whatever
            // the advance, so the root is fixed and deep — and it is reported, flagged,
            // rather than denied.
            assertThat(result.status()).isEqualTo(SolveStatus.REQUIRES_REVIEW);
            assertThat(result.status().requiresApproval()).isTrue();
            assertThat(result.rateOrThrow().periodic())
                .as("a recovery this far below the advance implies a deeply negative rate")
                .isLessThan(new BigDecimal("-0.999"));
            assertThat(result.diagnostic()).contains("escalating the ladder");
        } else {
            assertThat(result.status()).isEqualTo(SolveStatus.NO_SOLUTION);
            assertThat(result.status().routesToExceptionQueue()).isTrue();
            assertThat(result.status().carriesRate()).isFalse();
            assertThat(result.hasRate()).isFalse();
            assertThat(result.rate()).isNull();
            assertThat(result.method()).isNull();
            assertThat(result.residualAtStoredRate()).isNull();
            assertThat(result.candidateRoots()).isEmpty();
            assertThatThrownBy(result::rateOrThrow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_SOLUTION");
        }
    }

    // ------------------------------------------------------------------ vectors

    /** An advance net of fee against {@code periods} level instalments priced at {@code rate}. */
    private static FlowVector annuityVector(Money principal, Money fee, BigDecimal rate, int periods) {
        BigDecimal annuityFactor = BigDecimal.ONE.subtract(
            Precision.discountFactor(rate, BigDecimal.valueOf(periods)), Precision.WORKING);
        Money instalment = principal.times(rate).dividedBy(annuityFactor);
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(ANCHOR, 0, principal.negate(), FlowKind.DISBURSEMENT));
        if (fee.isPositive()) {
            flows.add(CashFlow.of(ANCHOR, 0, fee, FlowKind.INTEGRAL_FEE_RECEIVED));
        }
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(ANCHOR.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(ANCHOR, Money.INR, flows);
    }

    /** A facility drawn twice and never repaid: outflows on both sides of the anchor. */
    private static FlowVector outflowsOnlyVector(Money advance, int periods) {
        return FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, advance.negate(), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(periods), periods,
                advance.times(new BigDecimal("0.1")).negate(), FlowKind.DISBURSEMENT)));
    }

    /**
     * One receipt worth a twenty-thousandth of the advance, one period out.
     *
     * <p>The standard ladder's lowest rung is -99.99%, which multiplies a flow one
     * period out by ten thousand. A recovery below a ten-thousandth of the advance
     * therefore cannot be bracketed on the standard rungs — but the root is there, at
     * -99.995% a month, and the escalated ladder reaches it.
     */
    private static FlowVector tokenRecoveryVector(Money advance) {
        Money recovery = advance.times(new BigDecimal("0.00005")).atPresentationScale();
        return FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, advance.negate(), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(1), 1, recovery, FlowKind.PRINCIPAL)));
    }
}
