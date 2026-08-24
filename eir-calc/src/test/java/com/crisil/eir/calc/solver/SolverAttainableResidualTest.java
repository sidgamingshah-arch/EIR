package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The residual bound a published rate can actually satisfy.
 *
 * <p>{@code tol_abs = max(1e-10, |target| * 1e-16)} says how small a residual is
 * worth chasing. It does not say how small a residual survives being rounded to a
 * rate published at twelve decimal places, and on a real instrument the published
 * residual misses {@code tol_abs} by two to five orders of magnitude. This class pins
 * that arithmetic down, because it changes how a residual should be read: a
 * {@code residualAtStoredRate} of 4.1e-6 against a {@code tol_abs} of 1e-10 is what a
 * 12dp rate leaves on a 1,000,000 two-year loan, not evidence of a defect.
 *
 * <p>Which is also why the solver flags on {@code max(tol_abs, rounding floor)} and
 * not on {@code tol_abs} alone. Flagging on {@code tol_abs} sends most of the book for
 * approval — the round-trip properties in {@link SolverRoundTripPropertiesTest}
 * caught exactly that when it was tried.
 */
class SolverAttainableResidualTest {

    private final SolverTolerance standard = SolverTolerance.standard();
    private final BracketedNewtonSolver solver = new BracketedNewtonSolver();

    @Test
    @DisplayName("the attainable floor is half a unit in the last place of a 12dp rate, carried through the slope")
    void theAttainableFloorScalesWithTheSlope() {
        // |f'| * 1e-12 / 2. The slope, not the exposure, because it is the slope that
        // converts a rate error into a residual.
        assertThat(standard.attainableResidual(bd("2E8")))
            .isEqualByComparingTo(bd("1E-4"));
        assertThat(standard.attainableResidual(bd("-2E8")))
            .as("sign of the slope is irrelevant; f' is negative on every ordinary asset")
            .isEqualByComparingTo(bd("1E-4"));
        assertThat(standard.attainableResidual(BigDecimal.ZERO))
            .as("a flat residual attains anything, and the tolerance floor then binds")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the rounding floor sits above tol_abs on every real instrument, by three to six orders")
    void theSpecifiedToleranceIsUnattainableOnRealInstruments() {
        // The table in SolverTolerance.attainableResidual, asserted rather than asserted-in-prose.
        assertUnattainable("50,000 consumer durable, 12 EMIs", "50000", "3.4E5", 3);
        assertUnattainable("1,000,000 EMI loan, 24 months", "1000000", "5.2E7", 5);
        assertUnattainable("1,000,000 zero-coupon, 360 periods", "1000000", "2.4E8", 6);
        assertUnattainable("1,000,000,000 facility, 240 EMIs", "1000000000", "1.2E11", 5);
    }

    private void assertUnattainable(String label, String target, String slope, int ordersAtLeast) {
        BigDecimal tolerance = standard.absoluteFor(bd(target));
        BigDecimal attainable = standard.attainableResidual(bd(slope));
        assertThat(attainable)
            .as("%s: the attainable floor must exceed tol_abs, or the residual test would bind", label)
            .isGreaterThan(tolerance);
        assertThat(attainable.divide(tolerance, 0, RoundingMode.DOWN).precision())
            .as("%s: unattainable by at least 10^%d", label, ordersAtLeast)
            .isGreaterThanOrEqualTo(ordersAtLeast);
        assertThat(standard.publishableResidual(bd(target), bd(slope)))
            .as("%s: so the publishable bound is the attainable one", label)
            .isEqualByComparingTo(attainable);
    }

    @Test
    @DisplayName("where the slope is shallow enough, tol_abs binds after all")
    void theToleranceBindsOnAShallowResidual() {
        // Not a case any instrument produces, and the max() has to be a real max rather
        // than a silent substitution — otherwise a shallow vector would be held to a
        // bound looser than the specification's.
        BigDecimal shallow = bd("1E-3");
        assertThat(standard.attainableResidual(shallow)).isLessThan(standard.absoluteFor(bd("1000000")));
        assertThat(standard.publishableResidual(bd("1000000"), shallow))
            .isEqualByComparingTo(standard.absoluteFor(bd("1000000")));
    }

    @Test
    @DisplayName("an ordinary solve is SOLVED even though its residual is far outside tol_abs")
    void anOrdinarySolveIsNotFlaggedForMissingAnUnattainableTolerance() {
        SolveResult result = solver.solve(SolveRequest.atInception(
            SolverFixtures.zeroCouponBullet(), MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(result.status())
            .as("the whole long-tenor book would go for approval if tol_abs alone gated this")
            .isEqualTo(SolveStatus.SOLVED);
        assertThat(result.safeguardSteps())
            .as("and a well-conditioned vector needs no safeguarded steps")
            .isZero();
        assertThat(result.safeguardSteps()).isLessThanOrEqualTo(result.iterations());
    }

    @Test
    @DisplayName("a step-up EMI records safeguarded steps; a level one does not, and SolverMethod cannot tell them apart")
    void safeguardStepsAreTheProfileSignal() {
        // Both terminate as NEWTON, so SolverMethod says nothing about either. The
        // safeguard count separates them, which is the correction this field exists to
        // make. Measured across an eighty-eight vector sweep of level annuities, balloons,
        // moratoria and deep discounts, the step-up unseeded was the only shape that
        // raised it — so the claim here is narrow on purpose: it fires on irregular
        // instalment profiles and is zero on regular ones. Notably it is NOT raised by
        // Case 7's deep discount, which the repo previously asserted in a comment.
        SolveResult stepUp = solver.solve(
            SolveRequest.atInception(SolverFixtures.stepUpEmi(), MONTHLY, null));
        SolveResult level = solver.solve(SolveRequest.atInception(
            SolverFixtures.deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(stepUp.method()).isEqualTo(SolverMethod.NEWTON);
        assertThat(level.method()).isEqualTo(SolverMethod.NEWTON);
        assertThat(stepUp.safeguardSteps())
            .as("a tangent step off a step-up profile leaves the bracket and is safeguarded")
            .isPositive();
        assertThat(stepUp.safeguardSteps()).isLessThanOrEqualTo(stepUp.iterations());
        assertThat(level.safeguardSteps()).isZero();
        assertThat(stepUp.diagnostic()).contains("safeguarded");
        assertThat(level.diagnostic()).doesNotContain("safeguarded");
    }

    @Test
    @DisplayName("a loan priced at its contractual rate costs no iterations at all, because its root is a ladder node")
    void aRootOnALadderNodeCostsNoIterations() {
        // The modal instrument in the book, and the algorithm's worst case until the scan
        // was asked whether it had already found the root. 1% a month IS the ladder node
        // 0.01, so f there is about 1e-21 — non-zero, so the bracket is not degenerate,
        // and the root is the bracket's own lower endpoint. f is convex and decreasing, so
        // every tangent step from inside the bracket lands past the root and outside it
        // (0.00639, then 0.00913, then 0.00979, creeping up on 0.01 from below), is
        // refused by the safeguard, and pure bisection walks the bracket down to
        // rateEpsilon. That was 41 iterations for a root the scan had already located.
        //
        // Guarded here rather than left to a benchmark: 41 versus 0 on the modal vector
        // is a twenty-fold cost across a close window that has to price ten million
        // contracts (4.6), and it is invisible in every correctness assertion because the
        // rate it produced was right all along.
        SolveResult result = solver.solve(SolveRequest.atInception(
            SolverFixtures.levelAnnuityAtOnePercent(24), MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rateOrThrow().periodic()).isEqualByComparingTo(bd("0.010000000000"));
        assertThat(result.iterations())
            .as("the scan located the root; refining toward it is the slow path, not the fast one")
            .isZero();
        assertThat(result.diagnostic()).contains("no iteration was required");
    }

    @Test
    @DisplayName("a failed solve records no safeguard steps, because it produced no rate")
    void aFailedSolveRecordsNoSafeguards() {
        SolveResult result = SolveResult.noSolution("nothing to discount");

        assertThat(result.safeguardSteps()).isZero();
        assertThat(result.iterations()).isZero();
        assertThat(Money.zero(Money.INR).isZero()).isTrue();
    }
}
