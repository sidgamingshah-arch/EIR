package com.crisil.eir.calc.solver;

import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * When the solver is allowed to stop (4.2 step 4).
 *
 * <p>The residual tolerance is <strong>scale-relative</strong>, not a constant:
 * {@code tol_abs = max(absoluteFloor, |target| * targetRelativeFactor)}. A fixed
 * absolute tolerance cannot serve both a 50,000 consumer-durable loan and a
 * 40,000,000,000 infrastructure facility — on the first it is loose enough to be
 * meaningless, on the second it is tighter than 28-digit working precision can
 * deliver and the solve would never terminate on the residual test. Because the
 * derivative scales with the exposure in the same proportion as the residual, a
 * relative floor keeps the implied <em>rate</em> accuracy roughly constant across
 * six orders of magnitude of balance sheet.
 *
 * <p>That is also why the absolute tolerance is a method rather than a field: it
 * is a property of the tolerance <em>and</em> the target, and the target lives on
 * the {@link SolveRequest}.
 *
 * <p><strong>And it is not the whole test.</strong> A residual tolerance says how
 * small a residual is worth chasing; it does not say how small a residual survives
 * rounding the rate to twelve decimal places for publication. On a real instrument
 * the published residual misses {@code tol_abs} by two to five orders of magnitude —
 * see {@link #attainableResidual}, which is where that arithmetic, the measurements
 * behind it, and its consequences are set out.
 *
 * <p>{@link #tightened()} is the Tier 1 setting (3.9, 10, FR-406). It tightens the
 * residual, not the step test: {@link Precision#RATE_EPSILON} is already two
 * orders of magnitude below the twelfth decimal place at which a rate is stored,
 * so tightening the step test further could not change a single stored rate and
 * would be pure theatre. What tightening the residual buys is real, and it is
 * bought for one reason — ACPIR 50 makes the EIR the ECL discount rate, so on a
 * 20-year exposure a rate error propagates into lifetime ECL with compounding
 * effect (1.4).
 *
 * <p>The iteration caps are fixed by the specification at 100 Newton and 200
 * bisection iterations and are the same for every tier. They are a termination
 * guarantee, not a quality dial: 200 halvings of the widest possible bracket leave
 * an interval far narrower than anything a 12dp rate can express.
 *
 * @param absoluteFloor          smallest residual worth chasing, whatever the exposure
 * @param targetRelativeFactor   residual tolerance as a fraction of {@code |target|}
 * @param rateEpsilon            step size below which a further iteration cannot move the stored rate
 * @param maxNewtonIterations    hard cap on the Newton phase before bisection takes over
 * @param maxBisectionIterations hard cap on the bisection phase
 */
public record SolverTolerance(
    BigDecimal absoluteFloor,
    BigDecimal targetRelativeFactor,
    BigDecimal rateEpsilon,
    int maxNewtonIterations,
    int maxBisectionIterations) {

    /** 4.2 step 4: {@code tol_abs = max(1e-10, GCA_0 * 1e-16)}. */
    private static final BigDecimal STANDARD_FLOOR = BigDecimal.ONE.scaleByPowerOfTen(-10);
    private static final BigDecimal STANDARD_FACTOR = BigDecimal.ONE.scaleByPowerOfTen(-16);
    private static final BigDecimal TIGHTENED_FLOOR = BigDecimal.ONE.scaleByPowerOfTen(-12);
    private static final BigDecimal TIGHTENED_FACTOR = BigDecimal.ONE.scaleByPowerOfTen(-18);

    /** The specification's caps. Not per-tier: they guarantee termination. */
    public static final int MAX_NEWTON_ITERATIONS = 100;

    /** See {@link #MAX_NEWTON_ITERATIONS}. */
    public static final int MAX_BISECTION_ITERATIONS = 200;

    public SolverTolerance {
        Objects.requireNonNull(absoluteFloor, "absoluteFloor");
        Objects.requireNonNull(targetRelativeFactor, "targetRelativeFactor");
        Objects.requireNonNull(rateEpsilon, "rateEpsilon");
        if (absoluteFloor.signum() <= 0) {
            throw new IllegalArgumentException(
                "absoluteFloor must be positive, got " + absoluteFloor.toPlainString());
        }
        if (targetRelativeFactor.signum() < 0) {
            throw new IllegalArgumentException(
                "targetRelativeFactor must be non-negative, got " + targetRelativeFactor.toPlainString());
        }
        if (rateEpsilon.signum() <= 0) {
            throw new IllegalArgumentException(
                "rateEpsilon must be positive, got " + rateEpsilon.toPlainString());
        }
        if (maxNewtonIterations < 1) {
            throw new IllegalArgumentException(
                "maxNewtonIterations must be >= 1, got " + maxNewtonIterations);
        }
        if (maxBisectionIterations < 1) {
            throw new IllegalArgumentException(
                "maxBisectionIterations must be >= 1, got " + maxBisectionIterations);
        }
    }

    /** Tier 2 and Tier 3: {@code max(1e-10, |target| * 1e-16)}. */
    public static SolverTolerance standard() {
        return new SolverTolerance(STANDARD_FLOOR, STANDARD_FACTOR, Precision.RATE_EPSILON,
            MAX_NEWTON_ITERATIONS, MAX_BISECTION_ITERATIONS);
    }

    /** Tier 1, and any long-tenor population policy elects to tighten (FR-406). */
    public static SolverTolerance tightened() {
        return new SolverTolerance(TIGHTENED_FLOOR, TIGHTENED_FACTOR, Precision.RATE_EPSILON,
            MAX_NEWTON_ITERATIONS, MAX_BISECTION_ITERATIONS);
    }

    /**
     * The tolerance the materiality gate selects. Tier 1 tightens; long-tenor
     * Tier 2 pools may too, which is a policy election and therefore passes
     * {@link #tightened()} explicitly rather than being inferred here.
     */
    public static SolverTolerance forTier(MaterialityTier tier) {
        Objects.requireNonNull(tier, "tier");
        return tier.requiresTightenedTolerance() ? tightened() : standard();
    }

    /**
     * The finest residual a rate stored at {@link Precision#RATE_SCALE} decimal places
     * can leave behind, given the local slope of {@code f}.
     *
     * <p><strong>Why this exists, and why {@link #absoluteFor} is not the whole test.</strong>
     * {@code tol_abs} says how small a residual is worth chasing. It does not say how
     * small a residual survives <em>publication</em>, and those are different numbers.
     * The refinement works at 28 significant digits and reaches {@code tol_abs}
     * comfortably — three to seven iterations on an ordinary instrument. Then step 5
     * rounds the rate to {@link Precision#RATE_SCALE} decimal places, which moves it by
     * up to half a unit in its last place, and the residual comes back up by roughly
     * {@code |f'| * 10^-12 / 2}. That is what a reviewer sees on the computation record,
     * and {@code f'} scales with exposure and tenor, so it lands far above
     * {@code tol_abs}. Measured on annuities priced net of a 0.5% integral fee, so that
     * the root is not itself a 12dp grid point:
     *
     * <pre>
     *   instrument                        residualAtStoredRate   tol_abs
     *   50,000 consumer durable, 12 EMIs         2.5e-08          1e-10
     *   50,000 consumer durable, 24 EMIs         2.5e-07          1e-10
     *   1,000,000 EMI loan, 12 months            3.9e-07          1e-10
     *   1,000,000 EMI loan, 24 months            4.1e-06          1e-10
     *   1,000,000,000 facility, 24 months        4.9e-03          1e-07
     * </pre>
     *
     * <p>Across that sweep, <strong>none</strong> of the 36 published rates satisfied
     * {@code tol_abs} — by two to five orders of magnitude. Price the same annuity
     * <em>without</em> a fee and every one of them satisfies it with room to spare, at
     * 1e-23 to 4.5e-18: that is not the solver doing better, it is the fixture placing
     * the true root exactly on a 12dp grid point so that step 5's rounding has nothing
     * to do. A test built that way measures its own construction.
     *
     * <p>So this is not a solver defect and not a specification error either, once it is
     * said out loud: refining the rate past its twelfth decimal place cannot change a
     * published figure, so there is nothing to buy by chasing the residual further. What
     * was missing was the statement. A reviewer reading
     * {@code residualAtStoredRate = 4.1e-6} against a {@code tol_abs} of 1e-10 needs to
     * know that is what a 12dp rate leaves on a 1,000,000 twenty-four-month loan, not
     * evidence that the solve went wrong.
     *
     * <p>The solver therefore treats {@code max(tol_abs, attainable)} as the bound a
     * published rate must satisfy, and flags a rate that misses <em>both</em>. Missing
     * only {@code tol_abs} is the ordinary case and says nothing. The formula here is an
     * upper bound rather than an estimate — the rounding displacement is somewhere in
     * {@code [0, half a ULP]}, so a typical residual sits a few times under it, and the
     * measurements above all do — which is the right shape for a gate.
     *
     * @param slope {@code f'(r)} at the returned root, in the convention's own units
     */
    public BigDecimal attainableResidual(BigDecimal slope) {
        Objects.requireNonNull(slope, "slope");
        return slope.abs()
            .multiply(BigDecimal.ONE.movePointLeft(Precision.RATE_SCALE), Precision.WORKING)
            .divide(BigDecimal.valueOf(2), Precision.WORKING);
    }

    /**
     * The bound a published rate's residual must actually satisfy:
     * {@code max(tol_abs, attainable)}.
     *
     * <p>See {@link #attainableResidual}. Below this the residual carries no
     * information about the quality of the solve; above it, something is wrong with
     * the vector or the refinement.
     */
    public BigDecimal publishableResidual(BigDecimal target, BigDecimal slope) {
        return absoluteFor(target).max(attainableResidual(slope));
    }

    /** {@code max(absoluteFloor, |target| * targetRelativeFactor)}. */
    public BigDecimal absoluteFor(BigDecimal target) {
        Objects.requireNonNull(target, "target");
        return target.abs().multiply(targetRelativeFactor, Precision.WORKING).max(absoluteFloor);
    }

    /** {@link #absoluteFor(BigDecimal)} against a money target. */
    public BigDecimal absoluteFor(Money target) {
        Objects.requireNonNull(target, "target");
        return absoluteFor(target.amount());
    }
}
