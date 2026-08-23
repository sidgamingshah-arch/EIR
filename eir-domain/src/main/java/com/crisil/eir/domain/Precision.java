package com.crisil.eir.domain;

import ch.obermuhlner.math.big.BigDecimalMath;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The single declaration of the engine's numeric policy (ADR-0002, calculation
 * specification section 1).
 *
 * <p>Three distinct precisions, and conflating any two of them is a defect:
 *
 * <ul>
 *   <li>{@link #WORKING} — 28 significant digits, used for every intermediate.
 *       Intermediates are never rounded to currency scale.
 *   <li>Presentation scale — per currency, from ISO 4217 minor units. Applied
 *       exactly once, where a figure is persisted or emitted to the GL.
 *   <li>{@link #RATE_SCALE} — 12 decimal places. The rate that is persisted is
 *       the rate that must be used in every downstream period.
 * </ul>
 *
 * <p>{@code HALF_UP} rather than banker's {@code HALF_EVEN} throughout.
 * {@code HALF_EVEN} has the better statistical argument and loses to the
 * reconciliation argument: Indian financial reporting convention and every
 * downstream target this engine must tie to use {@code HALF_UP}.
 */
public final class Precision {

    /** 28 significant digits — IEEE 754 decimal128. Every intermediate. */
    public static final MathContext WORKING = new MathContext(28, RoundingMode.HALF_UP);

    /** The one rounding mode, everywhere. */
    public static final RoundingMode MODE = RoundingMode.HALF_UP;

    /** Decimal places at which a solved rate is persisted and thereafter used. */
    public static final int RATE_SCALE = 12;

    /**
     * Solver convergence floor. Below this a further iteration cannot change the
     * stored 12dp rate, so iterating is waste.
     */
    public static final BigDecimal RATE_EPSILON = BigDecimal.ONE.scaleByPowerOfTen(-14);

    private Precision() {
    }

    /** Rounds to {@code scale} decimal places under the engine's single mode. */
    public static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, MODE);
    }

    /**
     * Rounds a rate to storage scale. Call this once, when a solve completes;
     * everything downstream then uses the returned value, so that a published
     * amortisation is reproducible from the published rate.
     */
    public static BigDecimal storedRate(BigDecimal rate) {
        return round(rate, RATE_SCALE);
    }

    /**
     * {@code (1 + base) ^ exponent} for a non-negative integer exponent, exact
     * within {@link #WORKING}.
     */
    public static BigDecimal onePlusPow(BigDecimal base, int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("exponent must be non-negative, got " + exponent);
        }
        return BigDecimal.ONE.add(base).pow(exponent, WORKING);
    }

    /**
     * {@code (1 + base) ^ exponent} for an arbitrary non-negative exponent.
     *
     * <p>Dispatches to exact integer {@link BigDecimal#pow} where the exponent is
     * a whole number, and to an arbitrary-precision power otherwise. The two
     * agree to within 1e-24 at working precision, but the integer path is both
     * exact and materially faster, and the periodic-index time convention always
     * takes it.
     *
     * @throws IllegalArgumentException if {@code 1 + base} is not positive, or
     *     the exponent is negative
     */
    public static BigDecimal onePlusPow(BigDecimal base, BigDecimal exponent) {
        if (exponent.signum() < 0) {
            throw new IllegalArgumentException(
                "exponent must be non-negative, got " + exponent.toPlainString());
        }
        BigDecimal onePlus = BigDecimal.ONE.add(base);
        if (onePlus.signum() <= 0) {
            throw new IllegalArgumentException(
                "1 + base must be positive, got " + onePlus.toPlainString());
        }
        if (exponent.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal whole = exponent.stripTrailingZeros();
        if (whole.scale() <= 0 && whole.precision() - whole.scale() <= 9) {
            return onePlus.pow(whole.intValueExact(), WORKING);
        }
        return BigDecimalMath.pow(onePlus, exponent, WORKING);
    }

    /**
     * The discount factor {@code (1 + rate) ^ -tau}.
     *
     * <p>The single place discounting happens, so that the periodic-index and
     * actual-date conventions cannot drift apart.
     */
    public static BigDecimal discountFactor(BigDecimal rate, BigDecimal tau) {
        return BigDecimal.ONE.divide(onePlusPow(rate, tau), WORKING);
    }
}
