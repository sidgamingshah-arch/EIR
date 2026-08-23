package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.deepDiscount;
import static com.crisil.eir.calc.solver.SolverFixtures.interestOnlyBulletWithUpfrontFee;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The residual on the computation record is measured at the <em>rounded</em> 12dp
 * rate, not at the raw solved value (calculation specification 4.2 step 5).
 *
 * <p>This is the assertion that keeps the published figures reproducible. The rate
 * that is persisted is the rate every downstream period must be rolled forward with
 * (1.4), so the residual worth recording is the one that rate leaves behind.
 * Reporting the residual at 28 digits would flatter the engine and describe a
 * calculation nobody performs: the solve converges to within 1e-10 of zero, and the
 * published rate — five decimal places coarser — leaves a residual four orders of
 * magnitude larger. Both numbers are true; only one of them is auditable.
 *
 * <p>The test is therefore constructed so that the two answers cannot be confused:
 * on these instruments the residual at the stored rate is <em>above</em> the
 * solver's own convergence tolerance. A residual measured at the unrounded root
 * would have to be at or below it, by definition of having converged.
 */
class SolverResidualTest {

    private final RateSolver solver = new BracketedNewtonSolver();

    /** {@code |f(rate)| = |PV(rate) - target|}, recomputed the way the solver defines it (4.1). */
    private static BigDecimal residualAt(BigDecimal rate, SolveRequest request) {
        return Discounting.presentValue(rate, request.flows(), request.convention())
            .subtract(request.target().amount(), Precision.WORKING)
            .abs();
    }

    @Test
    @DisplayName("the recorded residual is |f| at the stored rate, to the digit")
    void residualIsMeasuredAtTheStoredRate() {
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        SolveResult result = solver.solve(request);

        assertThat(result.rate().periodic().scale()).isEqualTo(Precision.RATE_SCALE);
        assertThat(result.residualAtStoredRate())
            .as("the recorded residual must be reproducible by re-discounting at the published rate")
            .isEqualByComparingTo(residualAt(result.rate().periodic(), request));
    }

    @Test
    @DisplayName("the recorded residual exceeds the convergence tolerance, which only the rounded rate can do")
    void residualExceedsTheToleranceTheSolveConvergedTo() {
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        SolveResult result = solver.solve(request);

        // 4.2 step 4 stops the iteration at |f| <= max(1e-10, |target| * 1e-16). Any rate
        // the solver accepted therefore has a residual inside that bound. The recorded
        // figure is well outside it, which is only possible because it was re-measured
        // after rounding to twelve decimal places.
        assertThat(request.absoluteTolerance()).isEqualByComparingTo(new BigDecimal("1E-10"));
        assertThat(result.residualAtStoredRate())
            .as("a residual at or below tolerance here would mean the raw root was reported, "
                + "not the published one")
            .isGreaterThan(request.absoluteTolerance());
        // 2.9e-6 of a rupee on a 780,000 exposure: invisible at any scale that is
        // reported, and the honest number for the rate that is.
        assertThat(result.residualAtStoredRate()).isLessThan(new BigDecimal("0.00001"));
    }

    @Test
    @DisplayName("rounding the rate the other way would leave a larger residual — the recorded one is the minimum")
    void theStoredRateIsTheNearerOfTheTwelveDecimalNeighbours() {
        SolveRequest request = SolveRequest.atInception(
            interestOnlyBulletWithUpfrontFee(), MONTHLY, ONE_PERCENT_MONTHLY);
        SolveResult result = solver.solve(request);
        BigDecimal stored = result.rate().periodic();
        BigDecimal oneTickUp = stored.add(BigDecimal.ONE.scaleByPowerOfTen(-Precision.RATE_SCALE));
        BigDecimal oneTickDown = stored.subtract(BigDecimal.ONE.scaleByPowerOfTen(-Precision.RATE_SCALE));

        // HALF_UP at 12dp lands on the nearer of the two representable rates, so both
        // neighbours must be worse. This is what makes the residual a property of the
        // storage precision rather than of the search path: a solver that stopped early
        // and then rounded could land on the further neighbour and would fail here.
        assertThat(result.residualAtStoredRate()).isLessThan(residualAt(oneTickUp, request));
        assertThat(result.residualAtStoredRate()).isLessThan(residualAt(oneTickDown, request));
    }

    @Test
    @DisplayName("a failed solve records no residual at all")
    void aFailedSolveHasNoResidual() {
        // A zero here would read as a perfect solve of a rate that was never found, which
        // is why SolveResult enforces the pairing in both directions.
        FlowVector unsolvable = SolverFixtures.interimDrawdown("-1000000", "-1", "-1", 6);

        SolveResult result = solver.solve(SolveRequest.atInception(unsolvable, MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(result.status()).isEqualTo(SolveStatus.NO_SOLUTION);
        assertThat(result.residualAtStoredRate()).isNull();
    }
}
