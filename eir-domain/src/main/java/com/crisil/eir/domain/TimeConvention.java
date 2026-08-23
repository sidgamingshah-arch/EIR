package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * How the exponent tau is derived for a flow when discounting.
 *
 * <p>Two conventions, and which applies is a checked property of the vector
 * rather than a preference:
 *
 * <ul>
 *   <li>{@link PeriodicIndex} — tau is the period ordinal and the rate is
 *       per-period. Valid only when periods are uniform and every flow sits on a
 *       boundary. Cheaper, and exactly equivalent where valid.
 *   <li>{@link ActualDate} — tau is a day-counted year fraction and the rate is
 *       annual effective. Required for correctness everywhere else, and the
 *       default.
 * </ul>
 */
public sealed interface TimeConvention {

    /** The discounting exponent for a flow. */
    BigDecimal tau(LocalDate anchor, CashFlow flow);

    /** Compounding periods per year implied by the convention. */
    int periodsPerYear();

    /** A short label for the computation trace. */
    String label();

    /** Wraps a solved raw rate in the {@link Rate} this convention implies. */
    default Rate toRate(BigDecimal solved) {
        return Rate.periodic(solved, periodsPerYear());
    }

    /** Uniform periods, flows on boundaries. tau is the ordinal. */
    record PeriodicIndex(int periodsPerYear) implements TimeConvention {

        public PeriodicIndex {
            if (periodsPerYear < 1) {
                throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
            }
        }

        public static PeriodicIndex monthly() {
            return new PeriodicIndex(12);
        }

        @Override
        public BigDecimal tau(LocalDate anchor, CashFlow flow) {
            return BigDecimal.valueOf(flow.periodIndex());
        }

        @Override
        public String label() {
            return "PERIODIC_INDEX(x" + periodsPerYear + ")";
        }
    }

    /**
     * Day-counted year fractions — the XIRR form. The solved rate is annual
     * effective, so {@link #periodsPerYear()} is 1 and no annualisation
     * round-trip enters the amortisation.
     */
    record ActualDate(DayCount dayCount) implements TimeConvention {

        public ActualDate {
            Objects.requireNonNull(dayCount, "dayCount");
        }

        @Override
        public BigDecimal tau(LocalDate anchor, CashFlow flow) {
            return dayCount.yearFraction(anchor, flow.date());
        }

        @Override
        public int periodsPerYear() {
            return 1;
        }

        @Override
        public String label() {
            return "ACTUAL_DATE(" + dayCount.conventionName() + ")";
        }
    }
}
