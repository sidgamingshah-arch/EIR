package com.crisil.eir.calc.projection.blueprint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Due dates from a {@link ScheduleCalendar} — the calendar half of
 * <a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 2.8</a>.
 *
 * <p>Split out of {@link ScheduleBuilder} because dating and amortising fail in
 * different ways and are debugged from different evidence. A wrong instalment is
 * an arithmetic error visible in one number; a wrong due date is a convention
 * error visible only in the discount factors, and it silently voids the
 * periodic-index eligibility that {@code ConventionSelector} then has to detect
 * downstream (invariant ST-10). Keeping the date derivation addressable on its own
 * is what lets that be tested against a published vector rather than inferred from
 * a rate that came out slightly wrong.
 *
 * <p><b>Three transformations, in a fixed order.</b> The frequency gives the raw
 * anchor, the end-of-month rule resolves what a month-end anchor means in a
 * shorter month, and only then does the business-day convention move the date.
 * The order is not interchangeable: rolling for a holiday first and then applying
 * a month-end rule would undo the roll, and a schedule that lands its instalments
 * on a non-business day bills on a day the borrower's bank is shut.
 *
 * <p><b>{@code SEASONAL} and {@code CUSTOM} dates are taken verbatim.</b> A
 * crop-cycle schedule is aligned to harvest, and harvest does not observe a
 * modified-following convention. Adjusting a supplied date would move a due date
 * the contract fixed, so the supplied list is the schedule.
 */
public final class ScheduleDates {

    /**
     * Ceiling on the derived period count. Weekly instalments over a 30-year
     * tenor come to about 1,560 periods, so anything past this is a mis-stated
     * maturity rather than a long loan, and looping to find that out is worse
     * than saying so.
     */
    private static final int MAX_PERIODS = 6_000;

    private ScheduleDates() {
    }

    /**
     * Periods from the value date to stated maturity at the calendar's frequency.
     *
     * <p>Counted by stepping rather than by dividing a day count, because the
     * step is what generates the due dates and the count has to agree with it.
     * Deriving the count from months-between and the dates from repeated
     * {@code plusMonths} is the classic way to end up one period short on a
     * month-end anchor.
     *
     * <p>For an explicit-date calendar the count is the number of supplied dates
     * falling on or before stated maturity. The supplied list is authoritative
     * about the schedule; maturity only says where to stop reading it.
     */
    public static int statedPeriods(
        ScheduleCalendar calendar, LocalDate valueDate, LocalDate statedMaturity) {

        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(statedMaturity, "statedMaturity");
        if (!statedMaturity.isAfter(valueDate)) {
            throw new IllegalArgumentException(
                "statedMaturity " + statedMaturity + " must follow valueDate " + valueDate);
        }
        if (calendar.frequency().requiresExplicitDates()) {
            int supplied = 0;
            for (LocalDate date : calendar.customDueDates()) {
                if (!date.isAfter(statedMaturity)) {
                    supplied++;
                }
            }
            if (supplied < 1) {
                throw new IllegalArgumentException(
                    calendar.frequency() + " calendar supplies no due date on or before stated"
                        + " maturity " + statedMaturity + "; a schedule with no instalment is not a"
                        + " schedule");
            }
            return supplied;
        }
        int periods = 0;
        while (!anchor(calendar.frequency(), valueDate, periods + 1).isAfter(statedMaturity)) {
            periods++;
            if (periods > MAX_PERIODS) {
                throw new IllegalArgumentException(
                    "more than " + MAX_PERIODS + " " + calendar.frequency() + " periods between "
                        + valueDate + " and " + statedMaturity + "; the maturity is mis-stated");
            }
        }
        if (periods < 1) {
            throw new IllegalArgumentException(
                "stated maturity " + statedMaturity + " falls before the first "
                    + calendar.frequency() + " due date after " + valueDate
                    + "; a broken single period is a money-market instrument, not a schedule");
        }
        return periods;
    }

    /**
     * The first {@code periods} due dates, adjusted and in ascending order.
     *
     * <p>Anchored on the value date rather than chained off the previous due
     * date. Chaining accumulates the end-of-month clamp — a 31 January anchor
     * chained monthly reaches 28 February and then stays on the 28th for the rest
     * of the loan — which quietly turns a month-end schedule into a 28th-of-the-month
     * schedule and moves every discount factor after the second period.
     */
    public static List<LocalDate> dueDates(
        ScheduleCalendar calendar, LocalDate valueDate, int periods) {

        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(valueDate, "valueDate");
        if (periods < 1) {
            throw new IllegalArgumentException("periods must be >= 1, got " + periods);
        }
        if (calendar.frequency().requiresExplicitDates()) {
            List<LocalDate> supplied = calendar.customDueDates();
            if (supplied.size() < periods) {
                throw new IllegalArgumentException(
                    calendar.frequency() + " calendar supplies " + supplied.size()
                        + " due dates but the schedule runs " + periods + " periods."
                        + " An unequal-period calendar cannot extrapolate its own next date —"
                        + " that is what makes it unequal — so the missing dates are a data gap,"
                        + " not something to infer");
            }
            return List.copyOf(supplied.subList(0, periods));
        }
        List<LocalDate> dates = new ArrayList<>(periods);
        for (int period = 1; period <= periods; period++) {
            LocalDate raw = anchor(calendar.frequency(), valueDate, period);
            dates.add(calendar.adjust(endOfMonthResolved(calendar, valueDate, raw)));
        }
        return List.copyOf(dates);
    }

    /**
     * Whether periodic indexing survives this calendar <em>on this value date</em> — the
     * complete ST-10 test, and the one the gate should ask.
     *
     * <p>{@link ScheduleCalendar#admitsPeriodicIndexing()} cannot answer it, and not because
     * it forgot a clause. Its four vetoes are all properties of the calendar alone, but
     * {@code LAST_BUSINESS_DAY_OF_MONTH} is not: {@code endOfMonthResolved} fires it only
     * where the value date is itself a month end, so the same {@link ScheduleCalendar#monthly()}
     * instance moves every due date on a 31 January loan and none on a 15 January one. A veto
     * clause on the rule would therefore be wrong in the common case — it would report the
     * retail default as adjusted on every mid-month loan in the book, and
     * {@code BlueprintProjector.calendarForcesActualDating} throws on {@code !admits &&
     * indexed}, so "wrong" there means a rejected sound schedule rather than a mislabelled one.
     *
     * <p>So the question is asked of the dates rather than of the flags: derive the schedule
     * and compare each due date against its raw anchor. Nothing moved means period ordinals
     * measure time on this schedule; anything moved means they do not.
     *
     * <p>No live rate changes when the gate switches to this. {@code ConventionSelector} picks
     * {@code PeriodicIndex} only where {@code FlowVector.periodicIndexEligible} already holds,
     * and that re-checks every flow date against the raw anchor, so a moved date has always
     * fallen back to actual dating. What changes is the <em>record</em>: ST-10 stops passing a
     * month-end retail loan with "calendar MONTHLY is uniform and unadjusted" on a schedule
     * whose dates were adjusted. An invariant that is asserted in order to be believed cannot
     * be believed while it says that.
     */
    public static boolean admitsPeriodicIndexing(
        ScheduleCalendar calendar, LocalDate valueDate, LocalDate statedMaturity) {

        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(statedMaturity, "statedMaturity");
        if (!calendar.admitsPeriodicIndexing()) {
            return false;
        }
        // Only reachable for a derivable frequency: the argless check vetoes every
        // explicit-date calendar, which is also the only kind anchor() refuses to step.
        int periods = statedPeriods(calendar, valueDate, statedMaturity);
        List<LocalDate> due = dueDates(calendar, valueDate, periods);
        for (int period = 1; period <= periods; period++) {
            if (!due.get(period - 1).equals(anchor(calendar.frequency(), valueDate, period))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The 1-based period whose due date first falls on or after {@code date}, or
     * {@code -1} where none does.
     *
     * <p>Used to place a deferred-interest settlement on the schedule. A
     * settlement date that matches no due date is not an error to swallow: the
     * caller decides whether to snap it or to reject the blueprint, and it needs
     * to be told which case it is in.
     */
    public static int periodOnOrAfter(List<LocalDate> dueDates, LocalDate date) {
        Objects.requireNonNull(dueDates, "dueDates");
        Objects.requireNonNull(date, "date");
        for (int index = 0; index < dueDates.size(); index++) {
            if (!dueDates.get(index).isBefore(date)) {
                return index + 1;
            }
        }
        return -1;
    }

    /**
     * The raw anchor {@code periods} steps after {@code valueDate}, before any
     * end-of-month or business-day treatment.
     *
     * <p>{@code FORTNIGHTLY} steps two weeks rather than half a month, because a
     * fortnightly instalment is a fortnightly instalment; expressing it in months
     * would make its period length depend on February.
     */
    static LocalDate anchor(ScheduleCalendar.Frequency frequency, LocalDate valueDate, int periods) {
        return switch (frequency) {
            case WEEKLY -> valueDate.plusWeeks(periods);
            case FORTNIGHTLY -> valueDate.plusWeeks(2L * periods);
            case MONTHLY -> valueDate.plusMonths(periods);
            case QUARTERLY -> valueDate.plusMonths(3L * periods);
            case HALF_YEARLY -> valueDate.plusMonths(6L * periods);
            case ANNUAL -> valueDate.plusYears(periods);
            case SEASONAL, CUSTOM -> throw new IllegalArgumentException(
                frequency + " has no derivable period length; its due dates are supplied");
        };
    }

    /**
     * The end-of-month rule applied to one raw anchor.
     *
     * <p><b>The rule fires only where the value date is itself a month end.</b> A
     * loan disbursed on the 15th has no month-end anchor to resolve, and
     * applying {@code LAST_BUSINESS_DAY_OF_MONTH} to it would move every
     * instalment a fortnight — which is why the rule is conditioned on the anchor
     * rather than applied unconditionally. {@link ScheduleCalendar#monthly()}
     * carries {@code LAST_BUSINESS_DAY_OF_MONTH} as its default precisely on that
     * understanding: it is the rule for a month-end loan, dormant on every other
     * one.
     *
     * <p>Week-based frequencies are exempt. A fortnightly schedule has no month
     * anchor to preserve, and snapping it to a month end would collapse two
     * distinct due dates onto one date in a 28-day month.
     */
    private static LocalDate endOfMonthResolved(
        ScheduleCalendar calendar, LocalDate valueDate, LocalDate raw) {

        ScheduleCalendar.EndOfMonthRule rule = calendar.endOfMonthRule();
        if (rule == ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH) {
            return raw;
        }
        if (!monthAnchored(calendar.frequency())) {
            return raw;
        }
        if (valueDate.getDayOfMonth() != valueDate.lengthOfMonth()) {
            return raw;
        }
        LocalDate lastCalendarDay = raw.withDayOfMonth(raw.lengthOfMonth());
        if (rule == ScheduleCalendar.EndOfMonthRule.LAST_CALENDAR_DAY_OF_MONTH) {
            return lastCalendarDay;
        }
        LocalDate candidate = lastCalendarDay;
        for (int guard = 0; guard < 40; guard++) {
            if (calendar.isBusinessDay(candidate)) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        throw new IllegalStateException(
            "no business day in the month ending " + lastCalendarDay
                + "; the holiday set is implausible");
    }

    private static boolean monthAnchored(ScheduleCalendar.Frequency frequency) {
        return frequency == ScheduleCalendar.Frequency.MONTHLY
            || frequency == ScheduleCalendar.Frequency.QUARTERLY
            || frequency == ScheduleCalendar.Frequency.HALF_YEARLY
            || frequency == ScheduleCalendar.Frequency.ANNUAL;
    }
}
