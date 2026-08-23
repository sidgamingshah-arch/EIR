package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The 10% threshold and the configurable band around it within which an
 * asset-side result must go to a person (calculation specification section 6.4,
 * FR-511).
 *
 * <p>The band exists because a bright line applied to a computed number inherits
 * that number's sensitivity. The 10% ratio moves with the projection assumptions
 * behind the revised flows — a prepayment curve, a DCCO date, a tranche schedule —
 * so a result of 10.02% and one of 9.98% carry the same information and the
 * distance between their consequences could not be larger: derecognition of a
 * corporate loan at a fresh EIR, versus retention of the original EIR with a
 * catch-up. Inside the band the engine declines to choose.
 *
 * <p>The band is a policy input, not a numeric constant, because how wide it needs
 * to be depends on how the revised flows were derived — a contractually
 * re-documented schedule warrants a narrower band than a behaviourally projected
 * one. It is symmetric because the sensitivity is.
 *
 * <p>The threshold itself is configurable for the same reason the routing table is:
 * B3.3.6's 10% is a liability rule, and any asset-side analogue is the bank's own
 * elected indicator rather than a rule of the standard.
 *
 * @param threshold the substantiality threshold as a ratio; 0.10 is IFRS 9 B3.3.6
 * @param halfWidth half the band width, in the same ratio units; may be zero
 */
public record ReviewBand(BigDecimal threshold, BigDecimal halfWidth) {

    /** IFRS 9 B3.3.6: terms differing by at least 10 per cent. */
    public static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");

    /** One percentage point either side of the threshold — a 9% to 11% band. */
    private static final BigDecimal STANDARD_HALF_WIDTH = new BigDecimal("0.01");

    public ReviewBand {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(halfWidth, "halfWidth");
        if (threshold.signum() <= 0) {
            throw new IllegalArgumentException(
                "threshold must be positive, got " + threshold.toPlainString());
        }
        if (halfWidth.signum() < 0) {
            throw new IllegalArgumentException(
                "halfWidth must be non-negative, got " + halfWidth.toPlainString());
        }
        if (halfWidth.compareTo(threshold) >= 0) {
            throw new IllegalArgumentException(
                "halfWidth " + halfWidth.toPlainString() + " must be narrower than threshold "
                    + threshold.toPlainString() + "; a band reaching zero would send every "
                    + "immaterial re-documentation to approval and the band would stop meaning anything");
        }
    }

    /** The 10% threshold with a one-percentage-point band: 9% to 11%. */
    public static ReviewBand standard() {
        return new ReviewBand(TEN_PERCENT, STANDARD_HALF_WIDTH);
    }

    /**
     * The 10% threshold with no band.
     *
     * <p>For a policy that elects to treat the computed ratio as decisive on the
     * asset side too. Permitted, and documented as an election, because a bank may
     * conclude that its restructuring population is re-documented precisely enough
     * to bear a bright line. The engine still records the ratio as evidence and
     * still refuses to conclude over a fired qualitative trigger.
     */
    public static ReviewBand withoutBand() {
        return new ReviewBand(TEN_PERCENT, BigDecimal.ZERO);
    }

    /** An explicit band, for a policy electing something other than the standard width. */
    public static ReviewBand of(BigDecimal threshold, BigDecimal halfWidth) {
        return new ReviewBand(threshold, halfWidth);
    }

    /** Lower edge of the band, {@code threshold - halfWidth}. */
    public BigDecimal lowerBound() {
        return threshold.subtract(halfWidth, Precision.WORKING);
    }

    /** Upper edge of the band, {@code threshold + halfWidth}. */
    public BigDecimal upperBound() {
        return threshold.add(halfWidth, Precision.WORKING);
    }

    /**
     * Whether the ratio reaches the threshold.
     *
     * <p>Inclusive: B3.3.6 is "differ by <em>at least</em> 10 per cent", so exactly
     * 10% breaches.
     */
    public boolean breaches(BigDecimal ratio) {
        Objects.requireNonNull(ratio, "ratio");
        return ratio.compareTo(threshold) >= 0;
    }

    /**
     * Whether the ratio falls inside the review band, edges included.
     *
     * <p>With a zero half-width only an exact hit on the threshold qualifies, which
     * is the intended reading of {@link #withoutBand()}: a ratio landing exactly on
     * a bright line is the one case even a no-band policy should look at.
     */
    public boolean contains(BigDecimal ratio) {
        Objects.requireNonNull(ratio, "ratio");
        return ratio.compareTo(lowerBound()) >= 0 && ratio.compareTo(upperBound()) <= 0;
    }
}
