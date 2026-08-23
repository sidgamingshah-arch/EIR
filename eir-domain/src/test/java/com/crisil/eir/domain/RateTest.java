package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Rate} holds the <em>stored</em> rate — construction rounds to
 * {@link Precision#RATE_SCALE} — and exposes two annualisations that are not
 * interchangeable. Both properties are audit requirements rather than
 * conveniences, so both are pinned here.
 *
 * <p>The reference figures are those of reference case 1: a five-year monthly EMI
 * loan whose solved EIR is 1.04214918% per month, 12.505790% nominal and
 * 13.248094% effective per annum. A test that only checked one annualisation
 * would pass with the other substituted, which is precisely the confusion the
 * type exists to prevent.
 */
class RateTest {

    /** The solved EIR of reference case 1, per month. */
    private static final BigDecimal CASE_1_MONTHLY = new BigDecimal("0.0104214918");

    private static final BigDecimal TOLERANCE = new BigDecimal("1E-24");

    // ----------------------------------------------------- storage discipline

    @Nested
    @DisplayName("construction rounds to storage scale, so the published rate is the used rate")
    class StorageScale {

        @Test
        @DisplayName("a twenty-decimal input is stored at twelve decimals")
        void roundsToTwelveDecimals() {
            // The solver works to 28 significant digits; persisting 12 and then
            // rolling forward on the unrounded value would publish an amortisation
            // that cannot be reproduced from the published rate. Rate makes that
            // impossible by construction rather than by convention.
            Rate rate = Rate.monthly(new BigDecimal("0.01042149181250000000000"));
            assertThat(rate.periodic()).isEqualByComparingTo("0.010421491813");
            assertThat(rate.periodic().scale()).isEqualTo(Precision.RATE_SCALE);
        }

        @Test
        void roundsHalfUp() {
            assertThat(Rate.monthly(new BigDecimal("0.0000000000005")).periodic())
                .isEqualByComparingTo("0.000000000001");
            assertThat(Rate.monthly(new BigDecimal("0.0000000000004")).periodic())
                .isEqualByComparingTo("0");
            assertThat(Rate.monthly(new BigDecimal("-0.0000000000005")).periodic())
                .isEqualByComparingTo("-0.000000000001");
        }

        @Test
        void storageScaleIsFixedRegardlessOfInputScale() {
            assertThat(Rate.monthly(new BigDecimal("0.01")).periodic().scale()).isEqualTo(12);
            assertThat(Rate.monthly(new BigDecimal("1")).periodic().scale()).isEqualTo(12);
            assertThat(Rate.annualEffective(new BigDecimal("0.1324809408546510348")).periodic())
                .isEqualByComparingTo("0.132480940855");
        }

        @Test
        @DisplayName("re-wrapping a stored rate changes nothing")
        void wrappingIsIdempotent() {
            Rate once = Rate.monthly(new BigDecimal("0.01042149181250000000000"));
            Rate twice = Rate.monthly(once.periodic());
            assertThat(twice).isEqualTo(once);
            assertThat(twice.periodic()).isEqualByComparingTo(once.periodic());
        }

        @Test
        @DisplayName("two rates differing below storage scale are the same rate")
        void inputsBelowStorageScaleCollapse() {
            assertThat(Rate.monthly(new BigDecimal("0.01042149180000000001")))
                .isEqualTo(Rate.monthly(CASE_1_MONTHLY));
        }
    }

    // -------------------------------------------------------- annualisations

    @Nested
    @DisplayName("the two annualisations differ and neither is 'the annual rate'")
    class Annualisation {

        @Test
        @DisplayName("reference case 1: 1.04214918% per month is 13.248094% effective")
        void effectiveAnnualMatchesReferenceCase() {
            BigDecimal effective = Rate.monthly(CASE_1_MONTHLY).effectiveAnnual();
            // (1 + r)^12 - 1, exact to the digits the fixture publishes.
            assertThat(Precision.round(effective, 8)).isEqualByComparingTo("0.13248094");
            assertThat(Precision.round(effective, 12)).isEqualByComparingTo("0.132480940855");
            assertThat(effective)
                .isCloseTo(new BigDecimal("0.132480940854651034845383422"), within(TOLERANCE));
        }

        @Test
        @DisplayName("reference case 1: the same rate is 12.505790% nominal")
        void nominalAnnualMatchesReferenceCase() {
            BigDecimal nominal = Rate.monthly(CASE_1_MONTHLY).nominalAnnual();
            assertThat(nominal).isEqualByComparingTo("0.1250579016");
            assertThat(Precision.round(nominal, 8)).isEqualByComparingTo("0.12505790");
        }

        @Test
        @DisplayName("the gap between them is 72 basis points and is not rounding")
        void theTwoAnnualisationsAreDifferentNumbers() {
            Rate rate = Rate.monthly(CASE_1_MONTHLY);
            assertThat(rate.effectiveAnnual()).isGreaterThan(rate.nominalAnnual());
            assertThat(rate.effectiveAnnual().subtract(rate.nominalAnnual()))
                .isCloseTo(new BigDecimal("0.0074230392546510348453834"), within(new BigDecimal("1E-16")));
            // Confusing them misstates the disclosed yield by 72bp on an ordinary
            // retail loan, which is why the accessors are named and no method is
            // called simply "annual".
            assertThat(rate.effectiveAnnualBps().subtract(
                rate.nominalAnnual().multiply(new BigDecimal("10000"))))
                .isGreaterThan(new BigDecimal("70"));
        }

        @Test
        @DisplayName("the contractual leg of reference case 1: 1% per month is 12.682503% effective")
        void contractualRateAnnualisesExactly() {
            Rate contractual = Rate.monthly(new BigDecimal("0.01"));
            assertThat(contractual.effectiveAnnual())
                .isEqualByComparingTo("0.126825030131969720661201");
            assertThat(Precision.round(contractual.effectiveAnnual(), 6)).isEqualByComparingTo("0.126825");
            assertThat(contractual.nominalAnnual()).isEqualByComparingTo("0.12");
            // The EIR exceeds the contractual effective rate because the net
            // integral fee is income (invariant INV-2).
            assertThat(Rate.monthly(CASE_1_MONTHLY).effectiveAnnual())
                .isGreaterThan(contractual.effectiveAnnual());
        }

        @Test
        void quarterlyCompoundingIsExact() {
            Rate quarterly = Rate.periodic(new BigDecimal("0.03"), 4);
            assertThat(quarterly.effectiveAnnual()).isEqualByComparingTo("0.12550881");
            assertThat(quarterly.nominalAnnual()).isEqualByComparingTo("0.12");
        }

        @Test
        @DisplayName("under the actual-date convention the periodic rate IS the effective annual rate")
        void annualEffectiveNeedsNoRoundTrip() {
            // periodsPerYear is 1, so (1+r)^1 - 1 = r exactly and no annualisation
            // round-trip can enter the amortisation.
            Rate annual = Rate.annualEffective(new BigDecimal("0.132480940855"));
            assertThat(annual.periodsPerYear()).isEqualTo(1);
            assertThat(annual.effectiveAnnual()).isEqualByComparingTo(annual.periodic());
            assertThat(annual.nominalAnnual()).isEqualByComparingTo(annual.periodic());
            assertThat(annual.effectiveAnnual()).isEqualByComparingTo(annual.nominalAnnual());
        }

        @Test
        void basisPointsAreTenThousandthsOfTheEffectiveAnnualRate() {
            Rate rate = Rate.monthly(CASE_1_MONTHLY);
            assertThat(Precision.round(rate.effectiveAnnualBps(), 2)).isEqualByComparingTo("1324.81");
            assertThat(rate.effectiveAnnualBps())
                .isEqualByComparingTo(rate.effectiveAnnual().multiply(new BigDecimal("10000")));
        }

        @Test
        void aZeroRateAnnualisesToZeroBothWays() {
            Rate zero = Rate.monthly(BigDecimal.ZERO);
            assertThat(zero.effectiveAnnual()).isEqualByComparingTo("0");
            assertThat(zero.nominalAnnual()).isEqualByComparingTo("0");
            assertThat(zero.effectiveAnnualBps()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a negative periodic rate annualises consistently — the liability and deep-discount case")
        void negativeRatesAnnualise() {
            Rate negative = Rate.monthly(new BigDecimal("-0.001"));
            assertThat(negative.nominalAnnual()).isEqualByComparingTo("-0.012");
            // (0.999)^12 - 1 = -0.011934...; compounding makes the effective loss
            // smaller in magnitude than the nominal, the mirror of the gain case.
            assertThat(negative.effectiveAnnual()).isNegative();
            assertThat(negative.effectiveAnnual()).isGreaterThan(negative.nominalAnnual());
        }
    }

    // ------------------------------------------------------------ validation

    @Nested
    @DisplayName("a rate at or below -100% is not a rate")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {"-1", "-1.0", "-1.000000000000", "-1.5", "-2", "-100"})
        void atOrBelowMinusOneHundredPercentThrows(String value) {
            // (1 + r) <= 0 makes every discount factor either a division by zero
            // or sign-alternating, so this is rejected at the boundary rather than
            // producing a plausible-looking amortisation.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Rate.monthly(new BigDecimal(value)))
                .withMessageContaining("must exceed -100%");
        }

        @Test
        void justAboveMinusOneHundredPercentIsAccepted() {
            assertThat(Rate.monthly(new BigDecimal("-0.99")).periodic()).isEqualByComparingTo("-0.99");
        }

        @Test
        @DisplayName("validation precedes rounding, so -0.9999999999996 is stored as exactly -100%")
        void validationPrecedesRounding() {
            // Documents current behaviour, and the reason it is reported as a
            // defect rather than asserted as correct: the -100% guard is applied to
            // the constructor argument, but the value actually STORED is that
            // argument rounded to 12dp. An input inside the guard by 4e-13 rounds
            // onto the excluded boundary, and the resulting Rate has
            // 1 + periodic == 0 — a rate whose discount factor divides by zero.
            // If the guard moves after the rounding, this test should be changed to
            // expect a throw.
            Rate degenerate = Rate.monthly(new BigDecimal("-0.9999999999996"));
            assertThat(degenerate.periodic()).isEqualByComparingTo("-1");
            assertThat(BigDecimal.ONE.add(degenerate.periodic())).isEqualByComparingTo("0");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -12, Integer.MIN_VALUE})
        void periodsPerYearBelowOneThrows(int periodsPerYear) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Rate.periodic(new BigDecimal("0.01"), periodsPerYear))
                .withMessageContaining("periodsPerYear must be >= 1");
        }

        @Test
        void nullPeriodicThrows() {
            assertThatNullPointerException()
                .isThrownBy(() -> Rate.periodic(null, 12))
                .withMessageContaining("periodic");
        }
    }

    // -------------------------------------------------------------- identity

    @Nested
    @DisplayName("equality is by value, like Money and for the same reason")
    class Identity {

        @Test
        void differentScalesSameValueAreEqual() {
            assertThat(Rate.monthly(new BigDecimal("0.01")))
                .isEqualTo(Rate.monthly(new BigDecimal("0.0100000000000000")));
            assertThat(Rate.monthly(new BigDecimal("0.01")).hashCode())
                .isEqualTo(Rate.monthly(new BigDecimal("0.010000000000")).hashCode());
        }

        @Test
        void frequencyIsPartOfIdentity() {
            // 1% monthly and 1% quarterly are different rates; nothing about the
            // periodic figure alone distinguishes them.
            assertThat(Rate.periodic(new BigDecimal("0.01"), 12))
                .isNotEqualTo(Rate.periodic(new BigDecimal("0.01"), 4));
            assertThat(Rate.monthly(new BigDecimal("0.01")).effectiveAnnual())
                .isNotEqualByComparingTo(Rate.periodic(new BigDecimal("0.01"), 4).effectiveAnnual());
        }

        @Test
        void notEqualToNullOrOtherTypes() {
            assertThat(Rate.monthly(new BigDecimal("0.01"))).isNotEqualTo(null);
            assertThat(Rate.monthly(new BigDecimal("0.01"))).isNotEqualTo(new BigDecimal("0.01"));
        }

        @Test
        void factoriesCarryTheirFrequency() {
            assertThat(Rate.monthly(new BigDecimal("0.01")).periodsPerYear()).isEqualTo(12);
            assertThat(Rate.annualEffective(new BigDecimal("0.13")).periodsPerYear()).isEqualTo(1);
            assertThat(Rate.periodic(new BigDecimal("0.01"), 365).periodsPerYear()).isEqualTo(365);
        }

        @Test
        void toStringStatesTheFrequency() {
            assertThat(Rate.monthly(CASE_1_MONTHLY))
                .hasToString("0.010421491800 per period (x12)");
        }
    }
}
