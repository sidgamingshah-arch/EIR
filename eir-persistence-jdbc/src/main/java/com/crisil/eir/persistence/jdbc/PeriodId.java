package com.crisil.eir.persistence.jdbc;

import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The {@code YYYYMM} accounting-period encoding of 04 § 2.13, and the arithmetic on it.
 *
 * <p>V2 defends the encoding: {@code period_id} is the range partition key of
 * {@code period_balance} and {@code journal_entry}, "so it must be monotonic in reporting order, and
 * a partition bound of FROM (202704) TO (202705) is legible to whoever is reading an EXPLAIN plan at
 * 2am during close".
 *
 * <p><b>The encoding is monotonic and it is not contiguous, which is the trap this class exists
 * to close.</b> {@code 202704 + 1} is May 2027 and {@code 202712 + 1} is 202713, which is not a
 * month at all — V1's {@code cashflow_line_period_id_ck} refuses it ({@code period_id % 100 BETWEEN
 * 1 AND 12}) and so does every other period column in the schema. So arithmetic goes through
 * {@link YearMonth} rather than through the integer, and {@link #previous} is the method that
 * matters: the opening position for a period is the closing position of the period <em>before</em>
 * it, and "before" at January is the December of the previous year. An off-by-one-year read of the
 * prior period returns no row, which {@link JdbcContractStateSource} reports as a contract with no
 * opening state — so the defect would surface in January as an entire book quarantined under FR-905,
 * with the cause twelve months away from where anyone would look.
 */
public final class PeriodId {

    /**
     * How far {@link #elapsedPeriods} may walk past its closed-form estimate.
     *
     * <p>Four, because the estimate can only under-count and only by the day-of-month clamping
     * described there — one step in practice. A larger allowance would hide a real error; a smaller
     * one would refuse a correct schedule.
     */
    private static final int MAX_WALK = 4;

    private PeriodId() {
    }

    /** The period a business date falls in. */
    public static int of(LocalDate businessAsOf) {
        Objects.requireNonNull(businessAsOf, "businessAsOf");
        return businessAsOf.getYear() * 100 + businessAsOf.getMonthValue();
    }

    /**
     * The period immediately before {@code periodId}.
     *
     * <p>Via {@link YearMonth#minusMonths}, so January rolls to the previous December rather than to
     * a period id ending in 00.
     */
    public static int previous(int periodId) {
        return encode(yearMonth(periodId).minusMonths(1));
    }

    /** The first day of the period — the anchor a period's flow vector is measured from. */
    public static LocalDate startOf(int periodId) {
        return yearMonth(periodId).atDay(1);
    }

    /** The last day of the period — the period end a boundary's business date describes. */
    public static LocalDate endOf(int periodId) {
        return yearMonth(periodId).atEndOfMonth();
    }

    /**
     * How many of the contract's due dates fall strictly before {@code businessAsOf}.
     *
     * <p>The count is the number of periods that have <em>closed</em> by the business date, so the
     * ordinal of the period the date sits in is this plus one. Due date <i>n</i> is
     * {@code firstDueDate + (n − 1) × step}, and the date {@code d} sits in period <i>n</i> where
     * {@code dueDate(n − 1) < d <= dueDate(n)}.
     *
     * <p><b>Day-of-month is respected, and that is the whole reason this is not a month subtraction.</b>
     * An earlier version truncated both dates to {@link YearMonth} and divided. That counts a
     * one-day step across a month boundary as a whole month — 30 April to 1 May came out as one — and
     * it counts a due date already passed within the month as not passed. Concretely: a monthly
     * contract with instalments on the 20th, read at a period end of the 30th, came out one period
     * short. {@code ContractPipeline} then derives the accrual length from
     * {@code ContractTerms.dueDate(n−1) → dueDate(n)} while the roll-forward derives it from the
     * supplied vector's dates, and those two independent derivations are what makes invariant ST-2 a
     * control rather than a tautology — so an ordinal off by one makes them disagree for every
     * contract whose due day is not the 1st, on every period, for ever.
     *
     * <p>A date at or before the first due date gives 0 — it sits inside the contract's first period,
     * the one running from disbursement to the first instalment — so the ordinal derived from it is 1
     * and never 0. {@code ContractPeriod} refuses 0 with the right reason ("zero is the disbursement
     * boundary, not a period"), and returning it here would only move the refusal.
     *
     * @param firstDueDate  the contract's first scheduled repayment
     * @param businessAsOf  the date to place in the schedule
     * @param step          one period's calendar step, from {@link CompoundingBasis#stepOf}
     */
    public static long elapsedPeriods(LocalDate firstDueDate, LocalDate businessAsOf, Period step) {
        Objects.requireNonNull(firstDueDate, "firstDueDate");
        Objects.requireNonNull(businessAsOf, "businessAsOf");
        Objects.requireNonNull(step, "step");

        long perPeriod = step.toTotalMonths() > 0 ? step.toTotalMonths() : step.getDays();
        if (perPeriod < 1) {
            throw new IllegalArgumentException(
                "a period step must be at least one month or one day, got " + step);
        }
        long between = step.toTotalMonths() > 0
            ? ChronoUnit.MONTHS.between(firstDueDate, businessAsOf)
            : ChronoUnit.DAYS.between(firstDueDate, businessAsOf);

        // The closed form, then a forward walk to the first due date not before the business date.
        //
        // The walk is needed and it is short. It can only ever go forward, because month arithmetic
        // clamps a day-of-month the target month does not have — 31 January plus one month is
        // 28 February, and ChronoUnit.MONTHS.between then reports the gap as under a month — so the
        // closed form can under-count and never over-counts. In practice it corrects by at most one.
        long count = Math.max(0L, Math.floorDiv(between, perPeriod));
        long walked = 0;
        while (dueDate(firstDueDate, step, count).isBefore(businessAsOf)) {
            count++;
            if (++walked > MAX_WALK) {
                // Unreachable by the argument above. Present so that a future addition to the step
                // vocabulary turns a wrong answer into a named failure rather than a hung close.
                throw new IllegalStateException(
                    "could not place " + businessAsOf + " in a schedule from " + firstDueDate
                        + " stepping " + step + " after " + MAX_WALK + " steps");
            }
        }
        return count;
    }

    /** Due date {@code n + 1} of a schedule: {@code firstDueDate + n × step}. */
    private static LocalDate dueDate(LocalDate firstDueDate, Period step, long stepsElapsed) {
        return step.toTotalMonths() > 0
            ? firstDueDate.plusMonths(step.toTotalMonths() * stepsElapsed)
            : firstDueDate.plusDays((long) step.getDays() * stepsElapsed);
    }

    private static YearMonth yearMonth(int periodId) {
        int month = periodId % 100;
        int year = periodId / 100;
        if (month < 1 || month > 12 || year < 1900 || year > 9999) {
            // Exactly the shape V1 and V2 refuse on every period column. Refused here too, because
            // a period id that got past this method would be turned into a partition-key value and
            // land in period_balance_p_default — the partition V2 calls "a safety net, not a
            // destination", whose contents nothing prunes and no read-only switch can freeze.
            throw new IllegalArgumentException(
                "period id " + periodId + " is not YYYYMM with a month in 1..12; V2's"
                    + " ck_accounting_period_id_shape refuses the same value, and a period id that"
                    + " escaped this check would be written as a partition key");
        }
        return YearMonth.of(year, month);
    }

    private static int encode(YearMonth month) {
        return month.getYear() * 100 + month.getMonthValue();
    }
}
