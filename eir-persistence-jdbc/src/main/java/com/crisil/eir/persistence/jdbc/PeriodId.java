package com.crisil.eir.persistence.jdbc;

import java.time.LocalDate;
import java.time.YearMonth;
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
     * How many whole periods separate two dates at a given compounding frequency.
     *
     * <p>Used to place a business date in the contract's own schedule. Truncating division, so a
     * part period counts as not yet elapsed — which is right for an ordinal: the period that
     * <em>contains</em> a date is the one whose boundary the date has not yet reached.
     *
     * @param monthsPerPeriod calendar months in one compounding period, from
     *                        {@link CompoundingBasis#monthsInPeriod}
     */
    public static long periodsBetween(LocalDate from, LocalDate to, int monthsPerPeriod) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (monthsPerPeriod < 1) {
            throw new IllegalArgumentException(
                "monthsPerPeriod must be >= 1, got " + monthsPerPeriod);
        }
        long months = YearMonth.from(from).until(YearMonth.from(to), java.time.temporal.ChronoUnit.MONTHS);
        return months / monthsPerPeriod;
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
