package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Broken-period accrual (specification 5.3): compound accretion for the fraction,
 * never a simple-interest pro-rata.
 *
 * <p>The distinction is the whole content of the section, so the tests are built
 * around it rather than around the formula. {@code (1+R)^f} is concave in {@code f},
 * so a pro-rata {@code R x f} overstates a fraction shorter than one basis year and
 * understates one longer than it — and the specification calls out the long case
 * because that is where the error is largest and where it persists with the same sign
 * every period, never netting off. The 15-month first period below is the shape that
 * produces it: an IDC accrual to a deferred commercial operation date, or an
 * education-loan moratorium under ACPIR 9(6)(i).
 *
 * <p>One test carries the migration case explicitly. Legacy systems commonly pro-rate,
 * and quantifying the gap per contract is how a parallel run explains the difference
 * rather than reporting it as an unexplained break.
 */
class BrokenPeriodAccrualTest {

    /** 1% per month is 12.6825030131969720661201% a year. The exponent is a year fraction. */
    private static final BigDecimal ANNUAL_EFFECTIVE = CONTRACTUAL.effectiveAnnual();

    private static final Money ONE_MILLION = Money.inr("1000000");

    /** A 15-month first period: 1 April 2026 to 1 July 2027, 1.25 years on 30/360. */
    private static final LocalDate LONG_PERIOD_END = LocalDate.of(2027, 7, 1);

    @Test
    @DisplayName("a long broken period: compound accretion exceeds the pro-rata, by 2,437.67")
    void proRatingUnderstatesALongBrokenPeriod() {
        BigDecimal yearFraction =
            BrokenPeriodAccrual.yearFraction(DISBURSEMENT, LONG_PERIOD_END, DayCountConvention.THIRTY_360_BOND);
        assertThat(yearFraction).isEqualByComparingTo(bd("1.25"));

        Money compound = BrokenPeriodAccrual.interest(
            ONE_MILLION, CONTRACTUAL, DISBURSEMENT, LONG_PERIOD_END, DayCountConvention.THIRTY_360_BOND);
        Money proRata = ONE_MILLION.times(ANNUAL_EFFECTIVE.multiply(yearFraction, Precision.WORKING));

        assertThat(compound.atPresentationScale().amount()).isEqualByComparingTo(bd("160968.96"));
        assertThat(proRata.atPresentationScale().amount()).isEqualByComparingTo(bd("158531.29"));

        // The sign is the assertion. Pro-rating a period longer than one basis year
        // understates the interest, so the compound figure is the larger one, and the
        // difference is not a rounding: 2,437.67 on a single accrual.
        assertThat(compound.amount()).isGreaterThan(proRata.amount());
        Money deviation =
            BrokenPeriodAccrual.deviationFromProRata(ONE_MILLION, ANNUAL_EFFECTIVE, yearFraction);
        assertThat(deviation.signum()).isEqualTo(1);
        assertThat(deviation.atPresentationScale().amount()).isEqualByComparingTo(bd("2437.67"));
        assertThat(deviation.amount()).isEqualByComparingTo(compound.minus(proRata).amount());
    }

    @Test
    @DisplayName("a short broken period: the same pro-rata overstates, so the sign is not cosmetic")
    void proRatingOverstatesAShortBrokenPeriod() {
        // Twenty days on actual/365 — a fraction well under one basis year, where the
        // concavity runs the other way. That the error changes sign with the length is why
        // "pro-rating is close enough" is not a defensible position: the two cases do not
        // cancel, they just fail to be diagnosable together.
        LocalDate end = DISBURSEMENT.plusDays(20);
        BigDecimal yearFraction =
            BrokenPeriodAccrual.yearFraction(DISBURSEMENT, end, DayCountConvention.ACT_365F);
        Money deviation =
            BrokenPeriodAccrual.deviationFromProRata(ONE_MILLION, ANNUAL_EFFECTIVE, yearFraction);

        assertThat(deviation.signum()).isEqualTo(-1);
        assertThat(deviation.atPresentationScale().amount()).isEqualByComparingTo(bd("-385.18"));
        assertThat(BrokenPeriodAccrual.interest(ONE_MILLION, ANNUAL_EFFECTIVE, yearFraction)
            .atPresentationScale().amount()).isEqualByComparingTo(bd("6564.13"));
    }

    @ParameterizedTest(name = "year fraction {0} -> deviation from pro-rata has signum {1}")
    @CsvSource({
        "0.25, -1",
        "0.5,  -1",
        "0.99, -1",
        "1,     0",
        "1.01,  1",
        "1.25,  1",
        "3,     1",
    })
    @DisplayName("the pro-rata error is negative below one basis year, zero at it, positive above")
    void deviationSignFollowsTheFraction(String fraction, int expectedSignum) {
        Money deviation =
            BrokenPeriodAccrual.deviationFromProRata(ONE_MILLION, ANNUAL_EFFECTIVE, bd(fraction));

        assertThat(deviation.signum()).isEqualTo(expectedSignum);
    }

    @Test
    @DisplayName("at exactly one basis year compound and pro-rata coincide, which is why the error hides")
    void oneWholeYearIsTheOnePointTheyAgree() {
        Money compound = BrokenPeriodAccrual.interest(ONE_MILLION, ANNUAL_EFFECTIVE, BigDecimal.ONE);

        assertThat(compound.amount()).isEqualByComparingTo(ONE_MILLION.times(ANNUAL_EFFECTIVE).amount());
        assertThat(BrokenPeriodAccrual.deviationFromProRata(ONE_MILLION, ANNUAL_EFFECTIVE, BigDecimal.ONE)
            .isZero()).isTrue();
    }

    @Test
    @DisplayName("the rate must be the effective annual rate, and the nominal one is materially wrong")
    void theExponentIsAYearFractionSoTheRateIsAnnualEffective() {
        // 12.682503% effective against 12% nominal. The exponent is a year fraction, so
        // feeding the nominal rate — or a periodic rate scaled up — accretes at the wrong
        // rate for the whole broken period.
        assertThat(CONTRACTUAL.nominalAnnual()).isEqualByComparingTo(bd("0.12"));
        assertThat(CONTRACTUAL.effectiveAnnual().setScale(6, java.math.RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.126825"));

        Money onEffective = BrokenPeriodAccrual.interest(
            ONE_MILLION, CONTRACTUAL, DISBURSEMENT, LONG_PERIOD_END, DayCountConvention.THIRTY_360_BOND);
        Money onNominal = BrokenPeriodAccrual.interest(
            ONE_MILLION, CONTRACTUAL.nominalAnnual(),
            DISBURSEMENT, LONG_PERIOD_END, DayCountConvention.THIRTY_360_BOND);

        assertThat(onEffective.amount()).isGreaterThan(onNominal.amount());
        assertThat(onEffective.minus(onNominal).atPresentationScale().amount())
            .isEqualByComparingTo(bd("8783.13"));
    }

    @Test
    @DisplayName("a day-counted month is not one twelfth of a year, and that is the point of dating it")
    void aDatedMonthDoesNotReproduceThePeriodicAccrual() {
        // April on actual/365 is 30/365 of a year, not 1/12. So a broken period spanning
        // what looks like one month accrues 9,862.34 rather than the 10,000.00 a period
        // ordinal would give — a real difference, and the reason a broken period is dated
        // rather than counted.
        LocalDate end = DISBURSEMENT.plusMonths(1);
        BigDecimal yearFraction =
            BrokenPeriodAccrual.yearFraction(DISBURSEMENT, end, DayCountConvention.ACT_365F);

        assertThat(BrokenPeriodAccrual.interest(ONE_MILLION, ANNUAL_EFFECTIVE, yearFraction)
            .atPresentationScale().amount()).isEqualByComparingTo(bd("9862.34"));
        assertThat(ONE_MILLION.times(CONTRACTUAL.periodic()).atPresentationScale().amount())
            .isEqualByComparingTo(bd("10000.00"));

        // On 30/360 the same month is exactly one twelfth, and the two agree — which is
        // why the convention is an input and never an assumption.
        BigDecimal thirty360 =
            BrokenPeriodAccrual.yearFraction(DISBURSEMENT, end, DayCountConvention.THIRTY_360_BOND);
        assertThat(BrokenPeriodAccrual.interest(ONE_MILLION, ANNUAL_EFFECTIVE, thirty360)
            .atPresentationScale().amount()).isEqualByComparingTo(bd("10000.00"));
    }

    @Test
    @DisplayName("a zero-length period earns nothing rather than being a special case")
    void zeroLengthAccrualEarnsNothing() {
        // An event on a period boundary — a Stage 3 migration at the start of a month —
        // still runs the accrue-to-event-date step of 5.4. It correctly earns nothing.
        assertThat(BrokenPeriodAccrual.accretionFactor(ANNUAL_EFFECTIVE, BigDecimal.ZERO).signum())
            .isEqualTo(0);
        assertThat(BrokenPeriodAccrual.interest(
            ONE_MILLION, CONTRACTUAL, DISBURSEMENT, DISBURSEMENT, DayCountConvention.ACT_365F).isZero())
            .isTrue();
    }

    @Test
    @DisplayName("an accrual cannot run backwards")
    void endBeforeStartIsRejected() {
        // A segment built the wrong way round would otherwise credit interest silently.
        assertThatThrownBy(() -> BrokenPeriodAccrual.yearFraction(
            LONG_PERIOD_END, DISBURSEMENT, DayCountConvention.ACT_365F))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("precedes start");
    }

    @Test
    @DisplayName("a liability's broken period accretes negative interest on the same path")
    void signsFollowTheHolder() {
        Money borrowing = ONE_MILLION.negate();

        Money interest = BrokenPeriodAccrual.interest(
            borrowing, CONTRACTUAL, DISBURSEMENT, LONG_PERIOD_END, DayCountConvention.THIRTY_360_BOND);
        Money deviation = BrokenPeriodAccrual.deviationFromProRata(borrowing, ANNUAL_EFFECTIVE, bd("1.25"));

        assertThat(interest.atPresentationScale().amount()).isEqualByComparingTo(bd("-160968.96"));
        // Pro-rating still understates the charge; on the liability side that reads as an
        // overstated (less negative) expense.
        assertThat(deviation.signum()).isEqualTo(-1);
    }

    @Test
    @DisplayName("compound accretion over consecutive fractions equals one accretion over their sum")
    void accretionIsAssociativeAcrossASplitPeriod() {
        // The property a moratorium relies on and a pro-rata does not have: splitting an
        // accrual at an event date must not change the interest earned to period end.
        Money openingBalance = ONE_MILLION;
        Money firstHalf = BrokenPeriodAccrual.interest(openingBalance, ANNUAL_EFFECTIVE, bd("0.5"));
        Money secondHalf =
            BrokenPeriodAccrual.interest(openingBalance.plus(firstHalf), ANNUAL_EFFECTIVE, bd("0.75"));
        Money split = firstHalf.plus(secondHalf);
        Money whole = BrokenPeriodAccrual.interest(openingBalance, ANNUAL_EFFECTIVE, bd("1.25"));

        assertThat(split.atPresentationScale().amount()).isEqualByComparingTo(whole.atPresentationScale().amount());

        // A pro-rata is additive in the fraction but not in the balance, so the same split
        // over a pro-rata does change the answer, and by more than a rounding.
        Money proRataFirst = openingBalance.times(ANNUAL_EFFECTIVE.multiply(bd("0.5"), Precision.WORKING));
        Money proRataSecond = openingBalance.plus(proRataFirst)
            .times(ANNUAL_EFFECTIVE.multiply(bd("0.75"), Precision.WORKING));
        Money proRataWhole = openingBalance.times(ANNUAL_EFFECTIVE.multiply(bd("1.25"), Precision.WORKING));
        assertThat(proRataFirst.plus(proRataSecond).atPresentationScale().amount())
            .isNotEqualByComparingTo(proRataWhole.atPresentationScale().amount());
    }
}
