package com.crisil.eir.calc.solver;

import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The range of <strong>annual effective</strong> rates a solved root may plausibly
 * occupy — the economic judgement that disambiguates a mathematically ambiguous
 * vector (4.4).
 *
 * <p>Default {@code (-0.5, 2.0)}: from a 50% annual loss of value to a trebling.
 * Deliberately wide. It is not a reasonableness check on the book — a distressed
 * POCI pool solves to 29.1% and Case 7's deep-discount instrument to 46.0%, both
 * entirely legitimate — it is a device for picking the economically meaningful root
 * out of several mathematically valid ones. Configured per product, because the
 * plausible range for a 30-day WCDL whose fee annualises above the card rate (4.5)
 * is not the plausible range for a 20-year mortgage.
 *
 * <p><strong>The band is annualised and the solver's roots are not.</strong> Under
 * the periodic-index convention a root is a per-period rate, so containment is
 * tested on {@code (1+r)^p - 1} rather than on {@code r} — 10% per month is 214%
 * per annum and outside the default band, while 10% per annum is inside it.
 * Comparing a per-period root against an annualised band would silently widen the
 * band by the compounding frequency, which is exactly the kind of unit confusion
 * that makes a disambiguation policy indefensible in a review.
 *
 * <p>Bounds are inclusive. A root landing exactly on a bound is a rounding
 * coincidence, and pushing a defensible rate into the exception queue over one
 * serves nobody.
 *
 * @param lower minimum plausible annual effective rate
 * @param upper maximum plausible annual effective rate
 */
public record PlausibleBand(BigDecimal lower, BigDecimal upper) {

    private static final BigDecimal DEFAULT_LOWER = new BigDecimal("-0.5");
    private static final BigDecimal DEFAULT_UPPER = new BigDecimal("2.0");

    public PlausibleBand {
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
        if (lower.compareTo(upper) >= 0) {
            throw new IllegalArgumentException("lower " + lower.toPlainString()
                + " must be below upper " + upper.toPlainString());
        }
    }

    /** The specification default, {@code (-0.5, 2.0)} annual effective (4.4). */
    public static PlausibleBand standard() {
        return new PlausibleBand(DEFAULT_LOWER, DEFAULT_UPPER);
    }

    /** A per-product band, from policy. */
    public static PlausibleBand of(String lower, String upper) {
        return new PlausibleBand(new BigDecimal(lower), new BigDecimal(upper));
    }

    /**
     * Whether a solved root lies in the band, annualising it first.
     *
     * @param solved         the root in the convention's own units — per-period for
     *                       {@code PeriodicIndex}, already annual for {@code ActualDate}
     * @param periodsPerYear the convention's compounding frequency; 1 leaves the rate alone
     */
    public boolean contains(BigDecimal solved, int periodsPerYear) {
        Objects.requireNonNull(solved, "solved");
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        if (BigDecimal.ONE.add(solved).signum() <= 0) {
            // At or below -100% the compounding identity is meaningless — an even
            // number of periods would flip the sign and report a plausible rate for
            // a wholly implausible root.
            return false;
        }
        return containsAnnualEffective(annualEffective(solved, periodsPerYear));
    }

    /** Whether an already-annualised effective rate lies in the band. */
    public boolean containsAnnualEffective(BigDecimal annualEffective) {
        Objects.requireNonNull(annualEffective, "annualEffective");
        return annualEffective.compareTo(lower) >= 0 && annualEffective.compareTo(upper) <= 0;
    }

    /**
     * {@code (1+r)^p - 1} — the effective annual form (4.5), never the nominal
     * {@code r * p}. The nominal form exists only for comparison against a quoted
     * contractual rate and would understate every compounding root here.
     */
    public static BigDecimal annualEffective(BigDecimal solved, int periodsPerYear) {
        if (periodsPerYear == 1) {
            return solved;
        }
        return Precision.onePlusPow(solved, periodsPerYear).subtract(BigDecimal.ONE);
    }

    /** For the computation trace. */
    public String label() {
        return "[" + lower.toPlainString() + ", " + upper.toPlainString() + "] annual effective";
    }
}
