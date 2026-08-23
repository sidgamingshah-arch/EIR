package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Table-driven vectors for every supported day count.
 *
 * <p>Every expected value here is the <em>published</em> figure — the day count
 * the ISDA definition prescribes for the interval, divided by the basis the
 * definition prescribes — computed independently of this implementation at
 * {@link #CHECK} precision and compared within {@link #TOLERANCE}. Nothing in
 * this file round-trips the implementation against itself, which is the whole
 * point: month ends and leap years are where day counts go subtly wrong, a
 * self-consistent implementation stays self-consistent while being wrong, and
 * every discount factor in the engine moves when the year fraction does
 * (calculation specification section 3.9).
 *
 * <p>Sensitivity is inverted against tenor. On a twenty-year mortgage a
 * convention error is noise; on a seven-day money-market instrument a three-day
 * error is a materially wrong rate — hence the short-tenor rows.
 */
class DayCountConventionTest {

    /**
     * Deliberately finer than {@link Precision#WORKING}: the expected side of
     * every assertion must not itself be limited by the precision under test.
     */
    private static final MathContext CHECK = new MathContext(50, RoundingMode.HALF_UP);

    /**
     * Working precision carries 28 significant digits, so a year fraction of
     * order one is exact to roughly 1e-28 and a sum of a handful of such terms
     * to 1e-27. A 1e-25 tolerance therefore catches any arithmetic error while
     * tolerating nothing but the last-digit rounding of the divisions.
     */
    private static final BigDecimal TOLERANCE = new BigDecimal("1E-25");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    /** One published term: {@code days / basis}, evaluated above working precision. */
    private static BigDecimal over(long days, int basis) {
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(basis), CHECK);
    }

    private static void assertFraction(
        DayCount convention, String source, LocalDate start, LocalDate end, BigDecimal expected) {
        assertThat(convention.yearFraction(start, end))
            .as("%s %s..%s [%s]", convention.conventionName(), start, end, source)
            .isCloseTo(expected, within(TOLERANCE));
    }

    // ------------------------------------------------------------------ ACT/ACT

    @Nested
    @DisplayName("ACT/ACT (ISDA) — ISDA 2006 section 4.16(b)")
    class ActActIsda {

        /**
         * The defining property: each calendar year contributes days over
         * <em>its own</em> length, so any whole number of calendar years is an
         * exact integer and a leap year is neither long nor short. This is the
         * only supported convention with that property.
         */
        static Stream<Arguments> vectors() {
            return Stream.of(
                Arguments.of("whole non-leap year",
                    d("2015-01-01"), d("2016-01-01"), over(365, 365)),
                Arguments.of("whole leap year",
                    d("2016-01-01"), d("2017-01-01"), over(366, 366)),
                Arguments.of("ISDA published example: annual bond, 1 Nov 2003 to 1 May 2004",
                    d("2003-11-01"), d("2004-05-01"), over(61, 365).add(over(121, 366), CHECK)),
                Arguments.of("ISDA published example: wholly within 1999",
                    d("1999-02-01"), d("1999-07-01"), over(150, 365)),
                Arguments.of("February end to March end, non-leap",
                    d("2019-02-28"), d("2019-03-31"), over(31, 365)),
                Arguments.of("February end to March end, leap",
                    d("2020-02-28"), d("2020-03-31"), over(32, 366)),
                Arguments.of("spans 29 February, wholly inside the leap year",
                    d("2020-02-01"), d("2020-03-01"), over(29, 366)),
                Arguments.of("spans 29 February across the year boundary",
                    d("2019-12-01"), d("2020-03-01"), over(31, 365).add(over(60, 366), CHECK)),
                Arguments.of("31st to 31st",
                    d("2019-01-31"), d("2019-03-31"), over(59, 365)),
                Arguments.of("31st to 30th",
                    d("2019-01-31"), d("2019-04-30"), over(89, 365)),
                Arguments.of("seven-day money-market tenor",
                    d("2026-01-05"), d("2026-01-12"), over(7, 365)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void matchesPublishedVector(String source, LocalDate start, LocalDate end, BigDecimal expected) {
            assertFraction(DayCountConvention.ACT_ACT_ISDA, source, start, end, expected);
        }

        @Test
        @DisplayName("a whole number of calendar years is exactly that integer, leap years included")
        void wholeYearsAreExactIntegers() {
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(d("2015-01-01"), d("2016-01-01")))
                .isEqualByComparingTo("1");
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(d("2016-01-01"), d("2017-01-01")))
                .isEqualByComparingTo("1");
            // 2016 and 2020 are leap; the ISDA form still gives exactly the year count.
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(d("2015-06-01"), d("2018-06-01")))
                .isCloseTo(new BigDecimal("3"), within(TOLERANCE));
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(d("2019-01-01"), d("2021-01-01")))
                .isCloseTo(new BigDecimal("2"), within(TOLERANCE));
        }

        @Test
        @DisplayName("the ISDA published example reproduces 0.497724380567")
        void isdaAnnualBondExample() {
            assertThat(
                Precision.round(
                    DayCountConvention.ACT_ACT_ISDA.yearFraction(d("2003-11-01"), d("2004-05-01")), 12))
                .isEqualByComparingTo("0.497724380567");
        }
    }

    // ----------------------------------------------------------------- ACT/365F

    @Nested
    @DisplayName("ACT/365F — ISDA 2006 section 4.16(d), the Indian money-market convention")
    class Act365Fixed {

        static Stream<Arguments> vectors() {
            return Stream.of(
                Arguments.of("whole non-leap year is exactly one",
                    d("2015-01-01"), d("2016-01-01"), over(365, 365)),
                Arguments.of("whole leap year exceeds one — the fixed denominator does not stretch",
                    d("2016-01-01"), d("2017-01-01"), over(366, 365)),
                Arguments.of("February end to March end",
                    d("2019-02-28"), d("2019-03-31"), over(31, 365)),
                Arguments.of("February end to February end, non-leap",
                    d("2018-02-28"), d("2019-02-28"), over(365, 365)),
                Arguments.of("spans 29 February",
                    d("2020-02-15"), d("2020-03-15"), over(29, 365)),
                Arguments.of("same interval one year earlier, no leap day",
                    d("2019-02-15"), d("2019-03-15"), over(28, 365)),
                Arguments.of("31st to 31st",
                    d("2019-01-31"), d("2019-03-31"), over(59, 365)),
                Arguments.of("31st to 30th",
                    d("2019-01-31"), d("2019-04-30"), over(89, 365)),
                Arguments.of("91-day T-bill tenor",
                    d("2026-04-15"), d("2026-07-15"), over(91, 365)),
                Arguments.of("seven-day money-market tenor",
                    d("2026-01-05"), d("2026-01-12"), over(7, 365)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void matchesPublishedVector(String source, LocalDate start, LocalDate end, BigDecimal expected) {
            assertFraction(DayCountConvention.ACT_365F, source, start, end, expected);
        }
    }

    // ------------------------------------------------------------------ ACT/360

    @Nested
    @DisplayName("ACT/360 — ISDA 2006 section 4.16(e)")
    class Act360 {

        static Stream<Arguments> vectors() {
            return Stream.of(
                Arguments.of("whole non-leap year exceeds one by five days",
                    d("2015-01-01"), d("2016-01-01"), over(365, 360)),
                Arguments.of("whole leap year exceeds one by six days",
                    d("2016-01-01"), d("2017-01-01"), over(366, 360)),
                Arguments.of("February end to March end",
                    d("2019-02-28"), d("2019-03-31"), over(31, 360)),
                Arguments.of("spans 29 February",
                    d("2020-02-15"), d("2020-03-15"), over(29, 360)),
                Arguments.of("31st to 31st",
                    d("2019-01-31"), d("2019-03-31"), over(59, 360)),
                Arguments.of("31st to 30th",
                    d("2019-01-31"), d("2019-04-30"), over(89, 360)),
                Arguments.of("exactly 360 actual days is exactly one",
                    d("2026-01-01"), d("2026-12-27"), over(360, 360)),
                Arguments.of("seven-day money-market tenor",
                    d("2026-01-05"), d("2026-01-12"), over(7, 360)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void matchesPublishedVector(String source, LocalDate start, LocalDate end, BigDecimal expected) {
            assertFraction(DayCountConvention.ACT_360, source, start, end, expected);
        }
    }

    // -------------------------------------------------------- 30/360 vs 30E/360

    /**
     * The two 30/360 flavours share a table because the only interesting question
     * about them is where they diverge, and a shared table makes divergence
     * visible rather than asserted twice in separate files.
     *
     * <p>Both adjust a 31st start day to a 30th. Bond basis adjusts a 31st
     * <em>end</em> day only when the adjusted start day is already 30; Eurobond
     * basis adjusts it unconditionally. So they agree on every 31st-to-31st and
     * 31st-to-30th interval, and disagree exactly where the period ends on a
     * 31st having begun before the 30th — which includes every February-end to
     * month-end coupon, the commonest real case.
     */
    @Nested
    @DisplayName("30/360 bond basis vs 30E/360 — ISDA 2006 sections 4.16(f) and (g)")
    class Thirty360Family {

        /** source, start, end, bond-basis day count, 30E/360 day count. */
        static Stream<Arguments> vectors() {
            return Stream.of(
                Arguments.of("mid-month to mid-month is exactly 30", d("2007-01-15"), d("2007-02-15"), 30L, 30L),
                Arguments.of("mid-month to mid-month, six months", d("2007-01-15"), d("2007-07-15"), 180L, 180L),
                Arguments.of("within one month", d("2007-01-15"), d("2007-01-30"), 15L, 15L),
                Arguments.of("15th to 31st DIVERGES", d("2007-01-15"), d("2007-01-31"), 16L, 15L),
                Arguments.of("31st to 28th February", d("2007-01-31"), d("2007-02-28"), 28L, 28L),
                Arguments.of("28th February to 31st March DIVERGES", d("2007-02-28"), d("2007-03-31"), 33L, 32L),
                Arguments.of("31st August to 28th February", d("2006-08-31"), d("2007-02-28"), 178L, 178L),
                Arguments.of("28th February to 31st August DIVERGES", d("2007-02-28"), d("2007-08-31"), 183L, 182L),
                Arguments.of("mid-February to end February", d("2007-02-14"), d("2007-02-28"), 14L, 14L),
                Arguments.of("February end to early March", d("2007-02-28"), d("2007-03-05"), 7L, 7L),
                Arguments.of("30th to 31st, one month", d("2007-09-30"), d("2007-10-31"), 30L, 30L),
                Arguments.of("30th September to 31st March", d("2007-09-30"), d("2008-03-31"), 180L, 180L),
                Arguments.of("30th to 30th, whole year", d("2007-09-30"), d("2008-09-30"), 360L, 360L),
                Arguments.of("31st October to 28th November", d("2007-10-31"), d("2007-11-28"), 28L, 28L),
                Arguments.of("26th February to 29th February, leap", d("2007-02-26"), d("2008-02-29"), 363L, 363L),
                Arguments.of("29th February to 28th February", d("2008-02-29"), d("2009-02-28"), 359L, 359L),
                Arguments.of("29th February to 30th March", d("2008-02-29"), d("2008-03-30"), 31L, 31L),
                Arguments.of("29th February to 31st March DIVERGES", d("2008-02-29"), d("2008-03-31"), 32L, 31L),
                Arguments.of("29th February to 31st August DIVERGES", d("2008-02-29"), d("2008-08-31"), 182L, 181L),
                Arguments.of("31st August to 29th February", d("2007-08-31"), d("2008-02-29"), 179L, 179L),
                Arguments.of("31st to 31st, whole year over a leap year",
                    d("2016-01-31"), d("2017-01-31"), 360L, 360L),
                Arguments.of("31st to 31st, two months", d("2019-01-31"), d("2019-03-31"), 60L, 60L),
                Arguments.of("31st to 30th, three months", d("2019-01-31"), d("2019-04-30"), 90L, 90L),
                Arguments.of("spans 29 February and cannot see it", d("2020-02-01"), d("2020-03-01"), 30L, 30L),
                Arguments.of("one actual day measured as two", d("2020-02-29"), d("2020-03-01"), 2L, 2L),
                Arguments.of("first to first, whole year", d("2015-01-01"), d("2016-01-01"), 360L, 360L));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void bondBasisMatchesPublishedVector(
            String source, LocalDate start, LocalDate end, long bondDays, long eurobondDays) {
            assertFraction(DayCountConvention.THIRTY_360_BOND, source, start, end, over(bondDays, 360));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void eurobondBasisMatchesPublishedVector(
            String source, LocalDate start, LocalDate end, long bondDays, long eurobondDays) {
            assertFraction(DayCountConvention.THIRTY_E_360, source, start, end, over(eurobondDays, 360));
        }

        @Test
        @DisplayName("31st to 31st and 31st to 30th: the two flavours agree")
        void thirtyFirstIntervalsAgree() {
            // Start day 31 becomes 30 under both, which then licenses the bond-basis
            // end-day adjustment as well, so the two rules coincide.
            assertThat(DayCountConvention.THIRTY_360_BOND.yearFraction(d("2019-01-31"), d("2019-03-31")))
                .isEqualByComparingTo(
                    DayCountConvention.THIRTY_E_360.yearFraction(d("2019-01-31"), d("2019-03-31")))
                .isCloseTo(over(60, 360), within(TOLERANCE));
            assertThat(DayCountConvention.THIRTY_360_BOND.yearFraction(d("2019-01-31"), d("2019-04-30")))
                .isEqualByComparingTo(
                    DayCountConvention.THIRTY_E_360.yearFraction(d("2019-01-31"), d("2019-04-30")))
                .isEqualByComparingTo("0.25");
        }

        @Test
        @DisplayName("28 February to 31 March: the flavours genuinely differ, 33 days against 32")
        void februaryEndToMonthEndDiverges() {
            BigDecimal bond = DayCountConvention.THIRTY_360_BOND.yearFraction(d("2007-02-28"), d("2007-03-31"));
            BigDecimal eurobond = DayCountConvention.THIRTY_E_360.yearFraction(d("2007-02-28"), d("2007-03-31"));
            assertThat(bond).isCloseTo(over(33, 360), within(TOLERANCE));
            assertThat(eurobond).isCloseTo(over(32, 360), within(TOLERANCE));
            assertThat(bond).isNotEqualByComparingTo(eurobond);
            // One day in 360 on a single period: 0.0027778 of a year, which on a
            // documented convention is a rounding and on an undocumented one is a
            // permanent unexplained treasury-to-finance difference.
            assertThat(bond.subtract(eurobond)).isCloseTo(over(1, 360), within(TOLERANCE));
        }

        @Test
        @DisplayName("15 January to 31 January: divergence does not need a month end at the start")
        void midMonthToMonthEndDiverges() {
            assertThat(DayCountConvention.THIRTY_360_BOND.yearFraction(d("2007-01-15"), d("2007-01-31")))
                .isCloseTo(over(16, 360), within(TOLERANCE));
            assertThat(DayCountConvention.THIRTY_E_360.yearFraction(d("2007-01-15"), d("2007-01-31")))
                .isCloseTo(over(15, 360), within(TOLERANCE));
        }

        @Test
        @DisplayName("a 31st-to-31st whole year is exactly one, where ACT/365F is not")
        void thirty360NormalisesTheYearWhereActualDatingDoesNot() {
            assertThat(DayCountConvention.THIRTY_360_BOND.yearFraction(d("2016-01-31"), d("2017-01-31")))
                .isEqualByComparingTo("1");
            assertThat(DayCountConvention.THIRTY_E_360.yearFraction(d("2016-01-31"), d("2017-01-31")))
                .isEqualByComparingTo("1");
            assertThat(DayCountConvention.ACT_365F.yearFraction(d("2016-01-31"), d("2017-01-31")))
                .isCloseTo(over(366, 365), within(TOLERANCE))
                .isGreaterThan(BigDecimal.ONE);
        }
    }

    // ----------------------------------------------------------------- ACT/365L

    /**
     * ACT/365L, on the reading this engine documents: 366 where 29 February falls
     * inside the interval, 365 otherwise.
     *
     * <p>The {@link DayCountConvention#ACT_365L} Javadoc records that the ISDA
     * text admits two readings — one keyed on whether 29 February falls in the
     * period, one on whether the period end falls in a leap year — and that this
     * implementation takes the former. These tests assert that documented
     * reading, and {@link #endInALeapYearWithoutTheLeapDayUsesThe365Basis}
     * pins the one interval where the two readings visibly disagree, so that a
     * silent switch to the other reading cannot pass.
     */
    @Nested
    @DisplayName("ACT/365L — the documented reading of a convention whose text admits two")
    class Act365Leap {

        static Stream<Arguments> vectors() {
            return Stream.of(
                Arguments.of("whole non-leap year, 365 basis",
                    d("2015-01-01"), d("2016-01-01"), over(365, 365)),
                Arguments.of("whole leap year contains the leap day, 366 basis",
                    d("2016-01-01"), d("2017-01-01"), over(366, 366)),
                Arguments.of("spans 29 February, 366 basis",
                    d("2020-02-15"), d("2020-03-15"), over(29, 366)),
                Arguments.of("same calendar interval a year earlier, 365 basis",
                    d("2019-02-15"), d("2019-03-15"), over(28, 365)),
                Arguments.of("period ends on 29 February — end is inclusive, 366 basis",
                    d("2020-02-01"), d("2020-02-29"), over(28, 366)),
                Arguments.of("period starts on 29 February — start is exclusive, 365 basis",
                    d("2020-02-29"), d("2021-02-28"), over(365, 365)),
                Arguments.of("February end to March end, non-leap",
                    d("2019-02-28"), d("2019-03-31"), over(31, 365)),
                Arguments.of("31st to 31st",
                    d("2019-01-31"), d("2019-03-31"), over(59, 365)),
                Arguments.of("31st to 30th",
                    d("2019-01-31"), d("2019-04-30"), over(89, 365)),
                Arguments.of("two years containing one leap day — the basis is per period, not per year",
                    d("2019-01-01"), d("2021-01-01"), over(731, 366)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        void matchesDocumentedReading(String source, LocalDate start, LocalDate end, BigDecimal expected) {
            assertFraction(DayCountConvention.ACT_365L, source, start, end, expected);
        }

        @Test
        @DisplayName("end in a leap year but no leap day inside: 365, which is where the two readings part")
        void endInALeapYearWithoutTheLeapDayUsesThe365Basis() {
            // 1 Mar 2020 to 1 Sep 2020: the period end is in a leap year, so the
            // rival reading of the ISDA text would divide by 366. The documented
            // reading asks only whether 29 February is inside the period; it is
            // not, so the basis is 365.
            BigDecimal actual = DayCountConvention.ACT_365L.yearFraction(d("2020-03-01"), d("2020-09-01"));
            assertThat(actual).isCloseTo(over(184, 365), within(TOLERANCE));
            assertThat(actual).isNotEqualByComparingTo(over(184, 366));
        }

        @Test
        @DisplayName("a whole leap year and a whole non-leap year both come out at exactly one")
        void wholeYearsAreOneUnderEitherBasis() {
            assertThat(DayCountConvention.ACT_365L.yearFraction(d("2015-01-01"), d("2016-01-01")))
                .isEqualByComparingTo("1");
            assertThat(DayCountConvention.ACT_365L.yearFraction(d("2016-01-01"), d("2017-01-01")))
                .isEqualByComparingTo("1");
        }
    }

    // ------------------------------------------------------- cross-convention

    @ParameterizedTest(name = "{0}")
    @EnumSource(DayCountConvention.class)
    @DisplayName("a same-day interval is zero, not a day and not an error")
    void sameDayIntervalIsZero(DayCountConvention convention) {
        assertThat(convention.yearFraction(d("2026-02-28"), d("2026-02-28")))
            .as(convention.conventionName())
            .isEqualByComparingTo("0");
        assertThat(convention.yearFraction(d("2020-02-29"), d("2020-02-29")))
            .as(convention.conventionName())
            .isEqualByComparingTo("0");
        assertThat(convention.yearFraction(d("2019-01-31"), d("2019-01-31")))
            .as(convention.conventionName())
            .isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DayCountConvention.class)
    @DisplayName("an out-of-order interval is rejected, never silently signed")
    void endBeforeStartThrows(DayCountConvention convention) {
        // A negative year fraction would produce a discount factor above one and
        // an accrual running backwards, so this is a hard failure rather than an
        // absolute value.
        assertThatIllegalArgumentException()
            .isThrownBy(() -> convention.yearFraction(d("2026-03-31"), d("2026-03-30")))
            .withMessageContaining("precedes")
            .withMessageContaining("2026-03-30")
            .withMessageContaining("2026-03-31");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> convention.yearFraction(d("2020-03-01"), d("2020-02-29")));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DayCountConvention.class)
    @DisplayName("the convention names its own basis, as policy documents cite it")
    void conventionNameIsStable(DayCountConvention convention) {
        assertThat(convention.conventionName()).isNotBlank();
    }

    @Test
    @DisplayName("the conventions genuinely disagree on one ordinary interval")
    void conventionsDisagreeOnTheSameInterval() {
        // 31 Jan to 31 Mar 2019, one interval, five distinct answers among six
        // conventions. This is why the convention is fixed per instrument class in
        // policy and applied uniformly rather than defaulted per subsystem.
        LocalDate start = d("2019-01-31");
        LocalDate end = d("2019-03-31");
        BigDecimal actAct = DayCountConvention.ACT_ACT_ISDA.yearFraction(start, end);
        BigDecimal act365f = DayCountConvention.ACT_365F.yearFraction(start, end);
        BigDecimal act360 = DayCountConvention.ACT_360.yearFraction(start, end);
        BigDecimal thirty = DayCountConvention.THIRTY_360_BOND.yearFraction(start, end);

        assertThat(actAct).isEqualByComparingTo(act365f);
        assertThat(act360).isNotEqualByComparingTo(act365f);
        assertThat(thirty).isNotEqualByComparingTo(act360);
        assertThat(thirty).isNotEqualByComparingTo(act365f);
        assertThat(act360).isGreaterThan(act365f);
        assertThat(thirty).isGreaterThan(act360);
    }

    @Test
    @DisplayName("the day count is the only thing standing between the same interval and two discount factors")
    void theYearFractionMovesTheDiscountFactor() {
        // The point of the whole file, made once numerically: a seven-day money
        // market instrument at 7% discounted on ACT/365F against ACT/360 differs
        // in the fifth decimal of the factor — on a one-week bill that is a
        // materially wrong rate, which is the inverted sensitivity the day-count
        // interface documents.
        BigDecimal rate = new BigDecimal("0.07");
        BigDecimal on365 = Precision.discountFactor(
            rate, DayCountConvention.ACT_365F.yearFraction(d("2026-01-05"), d("2026-01-12")));
        BigDecimal on360 = Precision.discountFactor(
            rate, DayCountConvention.ACT_360.yearFraction(d("2026-01-05"), d("2026-01-12")));
        assertThat(on365).isNotEqualByComparingTo(on360);
        assertThat(on365).isGreaterThan(on360);
        assertThat(on365.subtract(on360).abs()).isGreaterThan(new BigDecimal("1E-6"));
    }
}
