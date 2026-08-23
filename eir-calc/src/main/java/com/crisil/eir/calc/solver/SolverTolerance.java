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
