package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The supported day-count conventions.
 *
 * <p>Indian convention discipline, fixed once in policy: <b>actual/365 for money
 * market</b> instruments (T-bills, CP, CD, call and notice money, TREPS),
 * <b>30/360 commonly for term loans</b>. A mixed convention that is not
 * documented produces a permanent unexplained reconciliation difference between
 * treasury and finance.
 *
 * <p>Month ends and leap years are where these go subtly wrong, and every
 * discount factor moves when they do — hence the table-driven tests against
 * published vectors rather than round-trip tests against this implementation.
 */
public enum DayCountConvention implements DayCount {

    /**
     * ISDA 2006 §4.16(b). Days in each calendar year over that year's own length,
     * summed. The only convention here that treats leap years exactly.
     */
    ACT_ACT_ISDA("ACT/ACT (ISDA)") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            if (start.equals(end)) {
                return BigDecimal.ZERO;
            }
            if (start.getYear() == end.getYear()) {
                return ratio(days(start, end), yearLength(start.getYear()));
            }
            BigDecimal total = ratio(
                days(start, LocalDate.of(start.getYear() + 1, 1, 1)),
                yearLength(start.getYear()));
            for (int year = start.getYear() + 1; year < end.getYear(); year++) {
                total = total.add(BigDecimal.ONE, Precision.WORKING);
            }
            total = total.add(
                ratio(days(LocalDate.of(end.getYear(), 1, 1), end), yearLength(end.getYear())),
                Precision.WORKING);
            return total;
        }
    },

    /** ISDA 2006 §4.16(d). Actual days over a fixed 365. Indian money market. */
    ACT_365F("ACT/365F") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            return ratio(days(start, end), 365);
        }
    },

    /** ISDA 2006 §4.16(e). Actual days over a fixed 360. */
    ACT_360("ACT/360") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            return ratio(days(start, end), 360);
        }
    },

    /**
     * ISDA 2006 §4.16(f), bond basis. A 31st in the start date becomes a 30th; a
     * 31st in the end date becomes a 30th only when the adjusted start day is
     * already 30.
     */
    THIRTY_360_BOND("30/360 (bond basis)") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            int d1 = Math.min(start.getDayOfMonth(), 30);
            int d2 = end.getDayOfMonth();
            if (d2 == 31 && d1 == 30) {
                d2 = 30;
            }
            return ratio(thirty360Days(start, end, d1, d2), 360);
        }
    },

    /**
     * ISDA 2006 §4.16(g), Eurobond basis. Both a 31st start and a 31st end become
     * a 30th, unconditionally — the difference from bond basis.
     */
    THIRTY_E_360("30E/360") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            int d1 = Math.min(start.getDayOfMonth(), 30);
            int d2 = Math.min(end.getDayOfMonth(), 30);
            return ratio(thirty360Days(start, end, d1, d2), 360);
        }
    },

    /**
     * ACT/365L. Actual days over 366 where 29 February falls inside the interval,
     * else 365.
     *
     * <p>The ISDA text admits two readings — one keyed on whether 29 February
     * falls in the period, one on whether the period end falls in a leap year.
     * This implementation takes the former, and the choice is stated here rather
     * than left to the reader precisely because the specification requires
     * conventions to be documented rather than inferred.
     */
    ACT_365L("ACT/365L") {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            return ratio(days(start, end), containsLeapDay(start, end) ? 366 : 365);
        }
    };

    private final String conventionName;

    DayCountConvention(String conventionName) {
        this.conventionName = conventionName;
    }

    @Override
    public String conventionName() {
        return conventionName;
    }

    static void requireOrdered(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " precedes start " + start);
        }
    }

    static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    static int yearLength(int year) {
        return java.time.Year.isLeap(year) ? 366 : 365;
    }

    static BigDecimal ratio(long numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), Precision.WORKING);
    }

    static long thirty360Days(LocalDate start, LocalDate end, int adjustedStartDay, int adjustedEndDay) {
        return 360L * (end.getYear() - start.getYear())
            + 30L * (end.getMonthValue() - start.getMonthValue())
            + (adjustedEndDay - adjustedStartDay);
    }

    static boolean containsLeapDay(LocalDate start, LocalDate end) {
        for (int year = start.getYear(); year <= end.getYear(); year++) {
            if (!java.time.Year.isLeap(year)) {
                continue;
            }
            LocalDate leapDay = LocalDate.of(year, 2, 29);
            if (leapDay.isAfter(start) && !leapDay.isAfter(end)) {
                return true;
            }
        }
        return false;
    }
}
