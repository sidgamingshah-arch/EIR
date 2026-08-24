package com.crisil.eir.calc.projection.blueprint;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * When instalments fall due.
 *
 * <p>Not a presentational detail. Any business-day adjustment moves a due date, and
 * a seasonal calendar makes the periods genuinely unequal — so this dimension is an
 * <em>input to convention selection</em>. Under either, periodic indexing is
 * unavailable and actual-date discounting is mandatory (invariant ST-10).
 *
 * <p>{@code SEASONAL} in particular is not a nicety. KCC and agricultural term loans
 * have due dates aligned to harvest, so a monthly-index approximation on a crop-cycle
 * loan is simply a wrong rate rather than a slightly rough one.
 *
 * @param frequency          nominal instalment frequency
 * @param businessDayConvention how a due date falling on a non-business day moves
 * @param holidays           dates treated as non-business, beyond weekends
 * @param endOfMonthRule     how a month-end anchor rolls through short months
 * @param customDueDates     explicit due dates; required for SEASONAL and CUSTOM
 */
public record ScheduleCalendar(
    Frequency frequency,
    BusinessDayConvention businessDayConvention,
    Set<LocalDate> holidays,
    EndOfMonthRule endOfMonthRule,
    List<LocalDate> customDueDates) {

    public ScheduleCalendar {
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(businessDayConvention, "businessDayConvention");
        Objects.requireNonNull(holidays, "holidays");
        Objects.requireNonNull(endOfMonthRule, "endOfMonthRule");
        Objects.requireNonNull(customDueDates, "customDueDates");
        holidays = Set.copyOf(holidays);
        customDueDates = List.copyOf(customDueDates);
        if (frequency.requiresExplicitDates() && customDueDates.isEmpty()) {
            throw new IllegalArgumentException(
                frequency + " has no derivable period length, so it requires explicit due dates."
                    + " A crop-cycle or bespoke schedule cannot be inferred from a frequency.");
        }
        for (int i = 1; i < customDueDates.size(); i++) {
            if (!customDueDates.get(i).isAfter(customDueDates.get(i - 1))) {
                throw new IllegalArgumentException(
                    "custom due dates must be strictly ascending; " + customDueDates.get(i)
                        + " does not follow " + customDueDates.get(i - 1));
            }
        }
    }

    /** The plain monthly calendar with no adjustment — the retail default. */
    public static ScheduleCalendar monthly() {
        return new ScheduleCalendar(
            Frequency.MONTHLY, BusinessDayConvention.NONE, Set.of(),
            EndOfMonthRule.LAST_BUSINESS_DAY_OF_MONTH, List.of());
    }

    /** A crop-cycle or otherwise bespoke calendar. */
    public static ScheduleCalendar seasonal(List<LocalDate> dueDates) {
        return new ScheduleCalendar(
            Frequency.SEASONAL, BusinessDayConvention.NONE, Set.of(),
            EndOfMonthRule.SAME_DAY_OF_MONTH, dueDates);
    }

    /**
     * Whether this calendar can license the cheaper periodic-index convention.
     *
     * <p>False wherever a due date could move or the periods are not uniform. The
     * check is conservative on purpose: it grants the optimisation only when nothing
     * about the calendar can disturb uniformity, and actual dating is both the
     * default and the fallback.
     */
    public boolean admitsPeriodicIndexing() {
        return businessDayConvention == BusinessDayConvention.NONE
            && holidays.isEmpty()
            && !frequency.requiresExplicitDates()
            && customDueDates.isEmpty();
    }

    /** Whether {@code date} is a business day under this calendar. */
    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
            && date.getDayOfWeek() != DayOfWeek.SUNDAY
            && !holidays.contains(date);
    }

    /** {@code date} moved per the convention, or unchanged where it is a business day. */
    public LocalDate adjust(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (businessDayConvention == BusinessDayConvention.NONE || isBusinessDay(date)) {
            return date;
        }
        return switch (businessDayConvention) {
            case NONE -> date;
            case FOLLOWING -> roll(date, 1);
            case PRECEDING -> roll(date, -1);
            case MODIFIED_FOLLOWING -> {
                LocalDate forward = roll(date, 1);
                yield forward.getMonth() == date.getMonth() ? forward : roll(date, -1);
            }
            case MODIFIED_PRECEDING -> {
                LocalDate back = roll(date, -1);
                yield back.getMonth() == date.getMonth() ? back : roll(date, 1);
            }
        };
    }

    private LocalDate roll(LocalDate from, int step) {
        LocalDate candidate = from;
        for (int guard = 0; guard < 400; guard++) {
            candidate = candidate.plusDays(step);
            if (isBusinessDay(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "no business day found within 400 days of " + from + "; the holiday set is implausible");
    }

    /** Nominal instalment frequency. */
    public enum Frequency {
        WEEKLY(0),
        FORTNIGHTLY(0),
        MONTHLY(12),
        QUARTERLY(4),
        HALF_YEARLY(2),
        ANNUAL(1),
        /** Due dates aligned to a crop or harvest cycle. Periods are unequal. */
        SEASONAL(0),
        /** An arbitrary supplied schedule. */
        CUSTOM(0);

        private final int periodsPerYear;

        Frequency(int periodsPerYear) {
            this.periodsPerYear = periodsPerYear;
        }

        /**
         * Compounding periods per year, or 0 where the frequency does not divide a
         * year evenly and so cannot support periodic indexing.
         */
        public int periodsPerYear() {
            return periodsPerYear;
        }

        /** Whether the frequency cannot derive its own due dates. */
        public boolean requiresExplicitDates() {
            return this == SEASONAL || this == CUSTOM;
        }
    }

    /** How a due date falling on a non-business day moves. */
    public enum BusinessDayConvention {
        NONE,
        FOLLOWING,
        MODIFIED_FOLLOWING,
        PRECEDING,
        MODIFIED_PRECEDING
    }

    /** How a month-end anchor behaves through shorter months. */
    public enum EndOfMonthRule {
        SAME_DAY_OF_MONTH,
        LAST_BUSINESS_DAY_OF_MONTH,
        LAST_CALENDAR_DAY_OF_MONTH
    }
}
