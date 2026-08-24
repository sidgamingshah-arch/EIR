package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.domain.DayCountConvention;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The dating and policy frame every {@link ProductTemplates} factory needs, and that
 * none of them can supply.
 *
 * <p>It exists to keep two kinds of input apart. What makes a housing loan a housing
 * loan — a level annuity, floating off a benchmark, with a prepayment option and a
 * curve — is product design and belongs in the template. When the money went out,
 * when the contract ends, which calendar the lender bills on, which day count the
 * bank documented for the product class, where its platform lands the rounding
 * residue and how long the ACPIR 46(1) horizon is are all facts about <em>this</em>
 * contract and this bank, and a template that invented any of them would be
 * inventing the answer rather than the shape.
 *
 * <p><b>There is no amount here.</b> That is deliberate, and it is the one thing this
 * record could plausibly carry and must not: "notional" means a different quantity in
 * each family. On a term loan it is the sum advanced; on a discount instrument it is
 * the face value redeemed, which is not what left the bank; on a revolver it is the
 * expected drawn balance, which is the limit times a utilisation assumption; on a
 * tranched facility it is the sum of the projected draws and no single draw equals it.
 * Folding those into one field is how a projection ends up opening its roll-forward
 * at a balance the borrower does not owe. Each factory therefore names the amount it
 * needs in the units its market quotes.
 *
 * @param valueDate         initial recognition; the discounting anchor
 * @param statedMaturity    contractual maturity before any optionality is considered
 * @param calendar          when instalments fall due, and whether they can move
 * @param dayCount          the documented convention for this product class
 * @param residuePolicy     where the lending platform lands the instalment-rounding
 *     residue. {@code LMS_AUTHORITATIVE} is the default because it leaves the residue
 *     visible, which is the safe failure for a schedule the engine derived rather than
 *     consumed
 * @param eclHorizonPeriods the ACPIR 46(1) horizon — the <b>maximum contractual</b>
 *     period including extension options, in schedule periods. A separate input from
 *     expected life and never derived from it: once a shorter behavioural life has
 *     been used as the horizon there is no record of which of the two any published
 *     figure was computed on. It must be at least the ladder's own length, which on an
 *     {@code EXTEND_TERM} moratorium is the stated term <em>plus</em> the holiday
 */
public record TemplateBasis(
    LocalDate valueDate,
    LocalDate statedMaturity,
    ScheduleCalendar calendar,
    DayCountConvention dayCount,
    ResiduePolicy residuePolicy,
    int eclHorizonPeriods) {

    public TemplateBasis {
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(statedMaturity, "statedMaturity");
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(residuePolicy, "residuePolicy");
        if (!statedMaturity.isAfter(valueDate)) {
            throw new IllegalArgumentException(
                "statedMaturity " + statedMaturity + " must follow valueDate " + valueDate);
        }
        if (eclHorizonPeriods < 1) {
            throw new IllegalArgumentException(
                "eclHorizonPeriods is the ACPIR 46(1) maximum contractual period in schedule"
                    + " periods and must be >= 1, got " + eclHorizonPeriods);
        }
    }

    /** The frame with the residue left where the lending system put it. */
    public static TemplateBasis of(
        LocalDate valueDate,
        LocalDate statedMaturity,
        ScheduleCalendar calendar,
        DayCountConvention dayCount,
        int eclHorizonPeriods) {

        return new TemplateBasis(valueDate, statedMaturity, calendar, dayCount,
            ResiduePolicy.LMS_AUTHORITATIVE, eclHorizonPeriods);
    }

    /** The retail frame: a plain monthly calendar with no business-day adjustment. */
    public static TemplateBasis monthly(
        LocalDate valueDate,
        LocalDate statedMaturity,
        DayCountConvention dayCount,
        int eclHorizonPeriods) {

        return of(valueDate, statedMaturity, ScheduleCalendar.monthly(), dayCount,
            eclHorizonPeriods);
    }

    /**
     * The money-market frame: one flow, on one date, under actual/365.
     *
     * <p>A 91-day treasury bill or a 30-day working-capital demand loan has no
     * schedule to derive and no whole month to derive it over, so the due date is
     * supplied and the calendar is {@code CUSTOM}. That also settles the convention
     * question the right way round: an explicit-date calendar cannot license periodic
     * indexing (ST-10), so the instrument is discounted on actual dates. Which is
     * exactly where it matters — day-count sensitivity runs <em>inversely</em> to
     * tenor, and on a seven-day instrument a three-day convention error is a
     * materially wrong rate where on a twenty-year mortgage it is noise.
     *
     * <p>Actual/365 is Indian money-market practice and it is fixed here rather than
     * left open because a convention that is not documented produces a permanent
     * unexplained break between treasury and finance.
     */
    public static TemplateBasis moneyMarket(LocalDate valueDate, LocalDate maturity) {
        return of(
            valueDate,
            maturity,
            new ScheduleCalendar(
                ScheduleCalendar.Frequency.CUSTOM,
                ScheduleCalendar.BusinessDayConvention.NONE,
                Set.of(),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
                List.of(maturity)),
            DayCountConvention.ACT_365F,
            1);
    }

    /** The same frame with the platform's residue convention stated. */
    public TemplateBasis withResiduePolicy(ResiduePolicy replacement) {
        return new TemplateBasis(valueDate, statedMaturity, calendar, dayCount, replacement,
            eclHorizonPeriods);
    }

    /** The same frame with a different ACPIR 46(1) horizon. */
    public TemplateBasis withEclHorizonPeriods(int replacement) {
        return new TemplateBasis(valueDate, statedMaturity, calendar, dayCount, residuePolicy,
            replacement);
    }
}
