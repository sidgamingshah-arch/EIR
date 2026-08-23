package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.DayCount;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Accrual over a period that is not a whole compounding period (calculation
 * specification section 5.3):
 *
 * <pre>
 *   interest = opening x ((1 + R_annual)^(D(start, end)/B) - 1)
 * </pre>
 *
 * <p>Broken periods are the rule rather than the exception: the first period runs
 * from disbursement to the first due date and almost never measures a whole
 * month, a moratorium ends mid-period, and every intra-period event truncates the
 * period it lands in (section 5.4).
 *
 * <p><strong>Compound accretion, not a simple-interest pro-rata.</strong> The
 * distinction is not cosmetic and it does not cancel over a life.
 * {@code (1+R)^f} is concave in {@code f}, so the pro-rata {@code R x f}
 * <em>overstates</em> a fraction shorter than one basis year and
 * <em>understates</em> one longer than it — a 15-month first period on an annual
 * basis, an IDC accrual across a deferred commercial operation date, an education
 * loan moratorium. The specification calls out the long case because that is
 * where the error is largest and where it persists: the same sign every period,
 * never netting off, leaving a small permanent break between the EIR ledger and
 * the interest the discounting implies. The pro-rata figure is also not the
 * inverse of any discount factor the solver used, so a ledger built on it cannot
 * satisfy TR-1.
 *
 * <p><strong>Which rate.</strong> The exponent is a year fraction, so the rate
 * must be the <em>effective annual</em> rate — {@link Rate#effectiveAnnual()},
 * never {@link Rate#nominalAnnual()}, and never a periodic rate scaled up. A
 * broken period spanning what looks like one month will not reproduce
 * {@code opening x r_monthly} exactly, because a day-counted month is not
 * exactly one twelfth of a year. That difference is the point of dating the
 * accrual rather than counting periods.
 */
public final class BrokenPeriodAccrual {

    private BrokenPeriodAccrual() {
    }

    /**
     * Interest over {@code [start, end)} under the instrument's day count.
     *
     * @param openingGca carrying amount at {@code start}
     * @param rate       the instrument's rate; its effective annual form is used
     * @param dayCount   the instrument's day-count convention (FR-304)
     */
    public static Money interest(
        Money openingGca, Rate rate, LocalDate start, LocalDate end, DayCount dayCount) {
        Objects.requireNonNull(rate, "rate");
        return interest(openingGca, rate.effectiveAnnual(), yearFraction(start, end, dayCount));
    }

    /** Interest over {@code [start, end)} at an explicit effective annual rate. */
    public static Money interest(
        Money openingGca,
        BigDecimal annualEffectiveRate,
        LocalDate start,
        LocalDate end,
        DayCount dayCount) {
        return interest(openingGca, annualEffectiveRate, yearFraction(start, end, dayCount));
    }

    /** Interest for an already-computed year fraction. */
    public static Money interest(Money openingGca, BigDecimal annualEffectiveRate, BigDecimal yearFraction) {
        Objects.requireNonNull(openingGca, "openingGca");
        return openingGca.times(accretionFactor(annualEffectiveRate, yearFraction));
    }

    /**
     * {@code (1 + R)^f - 1} — the fraction of a year's accretion actually earned.
     *
     * <p>Zero for a zero-length period, which is the ordinary case for an event
     * falling on a period boundary: the accrue-to-event-date step of section 5.4
     * still runs, and it correctly earns nothing.
     */
    public static BigDecimal accretionFactor(BigDecimal annualEffectiveRate, BigDecimal yearFraction) {
        Objects.requireNonNull(annualEffectiveRate, "annualEffectiveRate");
        Objects.requireNonNull(yearFraction, "yearFraction");
        if (yearFraction.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return Precision.onePlusPow(annualEffectiveRate, yearFraction).subtract(BigDecimal.ONE);
    }

    /**
     * The day-counted year fraction for {@code [start, end)}.
     *
     * @throws IllegalArgumentException if {@code end} precedes {@code start} —
     *     an accrual cannot run backwards, and a segment built the wrong way
     *     round would otherwise silently credit interest
     */
    public static BigDecimal yearFraction(LocalDate start, LocalDate end, DayCount dayCount) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(dayCount, "dayCount");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("accrual end " + end + " precedes start " + start);
        }
        return dayCount.yearFraction(start, end);
    }

    /**
     * How far a simple-interest pro-rata lands from the compound figure:
     * {@code compound - (opening x R x f)}.
     *
     * <p>A migration diagnostic, not an alternative. Legacy systems commonly
     * pro-rate, and quantifying the gap per contract is how the difference gets
     * explained during parallel running instead of appearing as an unexplained
     * reconciliation break. Negative for a fraction under one basis year, positive
     * beyond it.
     */
    public static Money deviationFromProRata(
        Money openingGca, BigDecimal annualEffectiveRate, BigDecimal yearFraction) {
        Money compound = interest(openingGca, annualEffectiveRate, yearFraction);
        Money proRata = openingGca.times(annualEffectiveRate.multiply(yearFraction, Precision.WORKING));
        return compound.minus(proRata);
    }
}
