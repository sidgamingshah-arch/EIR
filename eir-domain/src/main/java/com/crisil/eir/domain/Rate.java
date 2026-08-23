package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An effective interest rate, stored as a decimal fraction at
 * {@link Precision#RATE_SCALE} — 1.04214918% is {@code 0.010421491800}.
 *
 * <p>Construction rounds to storage scale, by design. The specification requires
 * that the rate which is persisted is the rate which is used in every downstream
 * period: solving to 28 digits, persisting 12, and then rolling forward with the
 * unrounded value produces a published amortisation that cannot be reproduced
 * from the published rate. Making {@code Rate} always hold the stored value means
 * that cannot happen by accident — the solver works in raw {@link BigDecimal} and
 * wraps once, at the end.
 *
 * <p>Two annualisations exist and they are not interchangeable. Both are exposed,
 * both are named, and neither is called simply "the annual rate":
 *
 * <ul>
 *   <li>{@link #effectiveAnnual()} — {@code (1+r)^n - 1}. The economically
 *       meaningful figure; used for reporting, disclosure and comparison.
 *   <li>{@link #nominalAnnual()} — {@code r * n}. Comparison to a quoted
 *       contractual rate only.
 * </ul>
 *
 * <p>Interest is always computed from {@link #periodic()}, never by dividing an
 * annual figure, so no annualisation round-trip enters the amortisation.
 *
 * <p>For the actual-date (XIRR) convention, {@code periodsPerYear} is 1 and
 * {@link #periodic()} <em>is</em> the effective annual rate; discounting then
 * raises {@code (1+r)} to a fractional year count.
 */
public record Rate(BigDecimal periodic, int periodsPerYear) {

    public Rate {
        Objects.requireNonNull(periodic, "periodic");
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        if (periodic.compareTo(BigDecimal.ONE.negate()) <= 0) {
            throw new IllegalArgumentException("rate must exceed -100%, got " + periodic.toPlainString());
        }
        periodic = Precision.storedRate(periodic);
    }

    /** A per-period rate compounding {@code periodsPerYear} times a year. */
    public static Rate periodic(BigDecimal value, int periodsPerYear) {
        return new Rate(value, periodsPerYear);
    }

    /** A monthly rate — the common retail case. */
    public static Rate monthly(BigDecimal value) {
        return new Rate(value, 12);
    }

    /** An annual effective rate, as produced by the actual-date convention. */
    public static Rate annualEffective(BigDecimal value) {
        return new Rate(value, 1);
    }

    /** {@code (1 + r)^n - 1}. The figure to report. */
    public BigDecimal effectiveAnnual() {
        return Precision.onePlusPow(periodic, periodsPerYear).subtract(BigDecimal.ONE);
    }

    /** {@code r * n}. Comparable to a quoted nominal contractual rate only. */
    public BigDecimal nominalAnnual() {
        return periodic.multiply(BigDecimal.valueOf(periodsPerYear), Precision.WORKING);
    }

    /** Basis points of effective annual rate, for spread reporting. */
    public BigDecimal effectiveAnnualBps() {
        return effectiveAnnual().multiply(BigDecimal.valueOf(10_000), Precision.WORKING);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rate(BigDecimal otherPeriodic, int otherPpy))) {
            return false;
        }
        return periodsPerYear == otherPpy && periodic.compareTo(otherPeriodic) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(periodic.stripTrailingZeros(), periodsPerYear);
    }

    @Override
    public String toString() {
        return periodic.toPlainString() + " per period (x" + periodsPerYear + ")";
    }
}
