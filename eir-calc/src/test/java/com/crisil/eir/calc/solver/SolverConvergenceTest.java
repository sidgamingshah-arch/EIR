package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.ANCHOR;
import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static com.crisil.eir.calc.solver.SolverFixtures.deepDiscount;
import static com.crisil.eir.calc.solver.SolverFixtures.interestOnlyBulletWithUpfrontFee;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The reference case 7 instruments that must converge, and the termination
 * guarantee behind them.
 *
 * <p>Each figure is asserted at the precision the fixture states it to — ten
 * decimal places of the decimal fraction, which is the eight decimal places of the
 * percentage the fixture publishes. The stored rate carries twelve, and the two
 * further digits are asserted separately where they matter (see
 * {@link SolverCalibrationTest} and {@link SolverResidualTest}), because a test
 * that pinned all twelve against a fixture stated to ten would be asserting the
 * fixture's rounding rather than the engine's arithmetic.
 */
class SolverConvergenceTest {

    private final RateSolver solver = new BracketedNewtonSolver();

    private static BigDecimal atTenDecimals(BigDecimal rate) {
        return rate.setScale(10, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("interest-only bullet with a large upfront fee solves to 1.11473209% per month")
    void interestOnlyBulletWithLargeUpfrontFee() {
        FlowVector vector = interestOnlyBulletWithUpfrontFee();
        SolveRequest request = SolveRequest.atInception(vector, MONTHLY, ONE_PERCENT_MONTHLY);

        // 50,000 of net integral fee against 1,000,000 of principal — 5% — is where a
        // Newton step seeded from the contractual rate has real distance to travel.
        assertThat(request.target().atPresentationScale().amount()).isEqualByComparingTo(bd("950000.00"));

        SolveResult result = solver.solve(request);

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(atTenDecimals(result.rate().periodic())).isEqualByComparingTo(bd("0.0111473209"));
        // 14.228172% p.a. effective, the fixture's annualisation of the same root.
        assertThat(result.rate().effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.142282"));
        assertThat(result.candidateRoots()).hasSize(1);
    }

    @Test
    @DisplayName("deep-discount instrument, fee above 20% of principal, solves to 3.20369894% per month")
    void deepDiscountInstrument() {
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        SolveResult result = solver.solve(request);

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(atTenDecimals(result.rate().periodic())).isEqualByComparingTo(bd("0.0320369894"));
        // 45.996740% p.a. effective. Wide of anything a credit committee would price,
        // and entirely legitimate: the fixture's point is that a 220,000 discount on a
        // 1,000,000 two-year loan really does yield this.
        assertThat(result.rate().effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.459967"));

        // The method is recorded because it is the cheapest signal about the shape of a
        // vector. Here the safeguarded Newton phase gets there: at this magnitude a raw
        // tangent step from the contractual seed overshoots, and the safeguards of 4.2
        // step 3 replace the overshooting step with a bisection of the current bracket
        // rather than abandoning the solve — which still counts as NEWTON, because what
        // BISECTION_FALLBACK records is that the iteration cap was reached, not that an
        // individual step was bisected.
        assertThat(result.method()).isEqualTo(SolverMethod.NEWTON);
        assertThat(result.iterations()).isLessThanOrEqualTo(SolverTolerance.MAX_NEWTON_ITERATIONS);
        assertThat(result.diagnostic()).contains("bracket [0.01, 0.05]");
    }

    @Test
    @DisplayName("the same instrument solves from the bracket midpoint when no contractual seed is available")
    void deepDiscountConvergesUnseeded() {
        SolveResult seeded = solver.solve(
            SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY));
        SolveResult unseeded = solver.solve(SolveRequest.atInception(deepDiscount(), MONTHLY, null));

        assertThat(unseeded.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(unseeded.rateOrThrow()).isEqualTo(seeded.rateOrThrow());
        assertThat(unseeded.diagnostic()).contains("seeded from the bracket midpoint");
    }

    @Test
    @Timeout(30)
    @DisplayName("a Newton phase that cannot converge falls back to bisection and still terminates on the same rate")
    void bisectionFallbackTerminatesOnTheSameRate() {
        // Forced rather than contrived: a Newton cap of one iteration is the same
        // condition the specification's cap of 100 produces on a genuinely
        // ill-conditioned vector — step EMIs, balloons, moratoria with capitalisation,
        // tranched project finance — without needing a vector whose ill-conditioning
        // would itself have to be defended. What is under test is the fallback, and the
        // fallback does not care why the Newton phase ran out.
        SolverTolerance oneNewtonStep = new SolverTolerance(
            bd("1E-10"), bd("1E-16"), Precision.RATE_EPSILON, 1, SolverTolerance.MAX_BISECTION_ITERATIONS);
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        SolveResult newton = solver.solve(request);
        SolveResult bisected = solver.solve(request.withTolerance(oneNewtonStep));

        assertThat(bisected.method()).isEqualTo(SolverMethod.BISECTION_FALLBACK);
        assertThat(bisected.status()).isEqualTo(SolveStatus.SOLVED);
        // Bisection is in the algorithm for correctness, not for speed: it costs an
        // order of magnitude more iterations and arrives at the same published rate.
        // A bracket containing a sign change cannot fail to converge, which is why the
        // fallback is mandatory rather than optional (4.2).
        assertThat(bisected.rateOrThrow()).isEqualTo(newton.rateOrThrow());
        assertThat(bisected.residualAtStoredRate()).isEqualByComparingTo(newton.residualAtStoredRate());
        assertThat(bisected.iterations()).isGreaterThan(newton.iterations());
        assertThat(bisected.iterations())
            .isLessThanOrEqualTo(1 + SolverTolerance.MAX_BISECTION_ITERATIONS);
        assertThat(bisected.diagnostic()).contains("BISECTION_FALLBACK");
    }

    @Test
    @DisplayName("a unique root outside the plausible band is still SOLVED, and says so")
    void uniqueRootOutsideThePlausibleBandIsStillTheAnswer() {
        // A 3% discount on a seven-day money-market instrument annualises to 387%. The
        // arithmetic is correct and the MIS looks broken (4.5); the presentation
        // convention for sub-year instruments is a policy question settled with finance
        // before go-live, not a solver one. The band disambiguates between several
        // roots — it is not a reasonableness check that can invalidate the only root
        // there is.
        FlowVector sevenDayBill = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-970000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusDays(7), 1, Money.inr("1000000"), FlowKind.PRINCIPAL)));
        TimeConvention weekly = new TimeConvention.PeriodicIndex(52);

        SolveResult result = solver.solve(SolveRequest.atInception(sevenDayBill, weekly, null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(PlausibleBand.standard().contains(result.rate().periodic(), 52)).isFalse();
        assertThat(result.rate().effectiveAnnual()).isGreaterThan(bd("2.0"));
        assertThat(result.diagnostic())
            .contains("unique but outside the plausible band")
            .contains("does not invalidate one");
    }

    @Test
    @DisplayName("a liability solves through the same path with every sign inverted")
    void liabilitySideNeedsNoSeparatePath() {
        // 11: liability signs invert and the same solver applies unchanged. The amount
        // raised is positive at inception, the issue cost negative, the redemption
        // negative, and the target is therefore negative — which is the ledger's signed
        // convention, not a defect to be corrected with an abs().
        FlowVector borrowing = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR, 0, Money.inr("-20000"), FlowKind.INTEGRAL_COST_PAID),
            CashFlow.of(ANCHOR.plusMonths(12), 12, Money.inr("-1120000"), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(borrowing, MONTHLY, null));

        assertThat(SolveRequest.inceptionTarget(borrowing).amount()).isEqualByComparingTo(bd("-980000"));
        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic()).isPositive();
        // 1,120,000 owed against 980,000 received over a year: 14.2857% effective, the
        // finance cost of the borrowing including its issue cost.
        assertThat(result.rate().effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.142857"));
    }
}
