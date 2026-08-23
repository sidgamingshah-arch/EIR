package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import ch.obermuhlner.math.big.BigDecimalMath;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The numeric policy itself. Three properties matter enough to pin:
 *
 * <ul>
 *   <li>the integer and fractional power paths agree where the exponent is a
 *       whole number — the property that licenses the periodic-index convention
 *       to take the exact, cheap path;
 *   <li>the fractional path is accurate well past the twelfth decimal of a rate,
 *       checked against an independent algorithm rather than against itself;
 *   <li>discounting and compounding are inverses, so a present value taken and
 *       then rolled forward returns the amount it started from.
 * </ul>
 *
 * <p>Expected values are computed at {@link #CHECK} — finer than
 * {@link Precision#WORKING} — or taken from square roots, which
 * {@link BigDecimalMath#sqrt} computes by a different algorithm than
 * {@link BigDecimalMath#pow}. Neither route reuses the code under test.
 */
class PrecisionTest {

    private static final MathContext CHECK = new MathContext(50, RoundingMode.HALF_UP);

    /** One unit in the last place of a value of order one at working precision is ~1e-27. */
    private static final BigDecimal ULP_TOLERANCE = new BigDecimal("1E-26");

    /** The accuracy the fractional path is required to reach. */
    private static final BigDecimal FRACTIONAL_TOLERANCE = new BigDecimal("1E-24");

    // ------------------------------------------------------------- the policy

    @Nested
    @DisplayName("the declared policy — three precisions, one rounding mode")
    class Policy {

        @Test
        void workingPrecisionIsTwentyEightDigitsHalfUp() {
            assertThat(Precision.WORKING.getPrecision()).isEqualTo(28);
            assertThat(Precision.WORKING.getRoundingMode()).isEqualTo(RoundingMode.HALF_UP);
        }

        @Test
        @DisplayName("HALF_UP everywhere — the reconciliation argument, not the statistical one")
        void thereIsOneRoundingMode() {
            assertThat(Precision.MODE).isEqualTo(RoundingMode.HALF_UP);
            assertThat(Precision.WORKING.getRoundingMode()).isEqualTo(Precision.MODE);
        }

        @Test
        void rateScaleIsTwelveDecimals() {
            assertThat(Precision.RATE_SCALE).isEqualTo(12);
        }

        @Test
        @DisplayName("the solver epsilon sits below the last stored digit of a rate")
        void epsilonCannotMoveTheStoredRate() {
            // 1e-14 against a rate stored at 1e-12: two orders of margin, so a
            // further iteration provably cannot change the persisted figure. If the
            // epsilon were coarser than the storage scale the solver could stop
            // while the stored rate was still moving.
            assertThat(Precision.RATE_EPSILON).isEqualByComparingTo("0.00000000000001");
            assertThat(Precision.RATE_EPSILON)
                .isLessThan(BigDecimal.ONE.scaleByPowerOfTen(-Precision.RATE_SCALE));
        }
    }

    // -------------------------------------------------------------- rounding

    @Nested
    class Rounding {

        @ParameterizedTest(name = "round({0}, {1}) = {2}")
        @CsvSource({
            "1234.5678, 2, 1234.57",
            "1234.5650, 2, 1234.57",
            "1234.5649, 2, 1234.56",
            "0.125, 2, 0.13",
            "-0.125, 2, -0.13",
            "0.5, 0, 1",
            "-0.5, 0, -1",
            "1.5, 0, 2",
            "2.5, 0, 3",
            "1000, 2, 1000.00"
        })
        void roundsHalfUpAwayFromZero(String value, int scale, String expected) {
            // 2.5 -> 3 is the discriminating case: HALF_EVEN would give 2.
            assertThat(Precision.round(new BigDecimal(value), scale)).isEqualByComparingTo(expected);
            assertThat(Precision.round(new BigDecimal(value), scale).scale()).isEqualTo(scale);
        }

        @Test
        void storedRateFixesTwelveDecimals() {
            BigDecimal solved = new BigDecimal("0.0104214918046729317458376291");
            assertThat(Precision.storedRate(solved)).isEqualByComparingTo("0.010421491805");
            assertThat(Precision.storedRate(solved).scale()).isEqualTo(12);
        }

        @Test
        void storedRateIsIdempotent() {
            BigDecimal once = Precision.storedRate(new BigDecimal("0.0104214918046729317458376291"));
            assertThat(Precision.storedRate(once)).isEqualByComparingTo(once);
            assertThat(Precision.storedRate(once).scale()).isEqualTo(once.scale());
        }

        @Test
        void storedRateRoundsHalfUpOnBothSignsOfTheRate() {
            assertThat(Precision.storedRate(new BigDecimal("0.0000000000005")))
                .isEqualByComparingTo("0.000000000001");
            assertThat(Precision.storedRate(new BigDecimal("-0.0000000000005")))
                .isEqualByComparingTo("-0.000000000001");
        }
    }

    // ------------------------------------------------------- the integer path

    @Nested
    @DisplayName("the integer power path is exact")
    class IntegerPath {

        @Test
        void smallExactCases() {
            assertThat(Precision.onePlusPow(new BigDecimal("0.5"), 2)).isEqualByComparingTo("2.25");
            assertThat(Precision.onePlusPow(new BigDecimal("0.1"), 3)).isEqualByComparingTo("1.331");
            assertThat(Precision.onePlusPow(new BigDecimal("0.01"), 1)).isEqualByComparingTo("1.01");
            assertThat(Precision.onePlusPow(new BigDecimal("0.0104214918"), 0))
                .isEqualByComparingTo("1");
            assertThat(Precision.onePlusPow(BigDecimal.ZERO, 360)).isEqualByComparingTo("1");
        }

        @ParameterizedTest(name = "(1+{0})^{1} equals {1} repeated multiplications")
        @CsvSource({"0.01, 12", "0.0104214918, 12", "0.0104214918, 60", "0.005, 240", "0.12, 5"})
        void agreesWithRepeatedMultiplication(String base, int exponent) {
            // The independent check: exact repeated multiplication, no MathContext,
            // rounded only at the end. BigDecimal.pow(int, mc) is documented as
            // accurate to within one ulp, so this pins that it really is.
            BigDecimal onePlus = BigDecimal.ONE.add(new BigDecimal(base));
            BigDecimal exact = BigDecimal.ONE;
            for (int i = 0; i < exponent; i++) {
                exact = exact.multiply(onePlus);
            }
            assertThat(Precision.onePlusPow(new BigDecimal(base), exponent))
                .isCloseTo(exact.round(CHECK), within(ULP_TOLERANCE));
        }

        @Test
        @DisplayName("reference case 1: (1.0104214918)^12 to the published digits")
        void referenceCaseCompounding() {
            BigDecimal compounded = Precision.onePlusPow(new BigDecimal("0.0104214918"), 12);
            assertThat(Precision.round(compounded.subtract(BigDecimal.ONE), 8))
                .isEqualByComparingTo("0.13248094");
            assertThat(compounded)
                .isCloseTo(new BigDecimal("1.132480940854651034845383422"), within(ULP_TOLERANCE));
        }

        @Test
        void negativeIntegerExponentIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Precision.onePlusPow(new BigDecimal("0.01"), -1))
                .withMessageContaining("exponent must be non-negative");
        }
    }

    // -------------------------------------------- dispatch between the paths

    @Nested
    @DisplayName("the two paths agree where the exponent is a whole number")
    class Dispatch {

        @ParameterizedTest(name = "(1+{0})^{1}")
        @CsvSource({
            "0.01, 1", "0.01, 12", "0.01, 60",
            "0.0104214918, 1", "0.0104214918, 12", "0.0104214918, 24", "0.0104214918, 60",
            "0.005, 120", "0.12, 5", "0, 12"
        })
        void integerAndFractionalPathsAgree(String base, int exponent) {
            // This is the property that lets the periodic-index convention take the
            // exact integer path: it must not be a different answer from the
            // arbitrary-precision one, only a cheaper route to the same answer.
            BigDecimal viaInteger = Precision.onePlusPow(new BigDecimal(base), exponent);
            BigDecimal viaArbitraryPrecision = BigDecimalMath.pow(
                BigDecimal.ONE.add(new BigDecimal(base)), BigDecimal.valueOf(exponent), CHECK);
            assertThat(viaInteger).isCloseTo(viaArbitraryPrecision, within(FRACTIONAL_TOLERANCE));
        }

        @Test
        @DisplayName("a whole exponent expressed with decimals or an exponent still takes the exact path")
        void wholeExponentsTakeTheIntegerPathWhateverTheirScale() {
            BigDecimal base = new BigDecimal("0.0104214918");
            BigDecimal expected = Precision.onePlusPow(base, 12);
            // Bit-identical, not merely close: the dispatch strips trailing zeros
            // and hands 12, 12.0 and 1.2E+1 alike to the exact integer routine.
            assertThat(Precision.onePlusPow(base, new BigDecimal("12"))).isEqualTo(expected);
            assertThat(Precision.onePlusPow(base, new BigDecimal("12.0"))).isEqualTo(expected);
            assertThat(Precision.onePlusPow(base, new BigDecimal("12.000000"))).isEqualTo(expected);
            assertThat(Precision.onePlusPow(base, new BigDecimal("1.2E+1"))).isEqualTo(expected);
        }

        @Test
        void aZeroExponentIsOneOnBothPaths() {
            BigDecimal base = new BigDecimal("0.0104214918");
            assertThat(Precision.onePlusPow(base, BigDecimal.ZERO)).isEqualByComparingTo("1");
            assertThat(Precision.onePlusPow(base, new BigDecimal("0.0000"))).isEqualByComparingTo("1");
            assertThat(Precision.onePlusPow(base, 0)).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("an exponent too large for the exact path still answers")
        void veryLargeExponentsFallThroughToTheArbitraryPrecisionPath() {
            // The guard is a magnitude guard, not a correctness one: beyond nine
            // digits the exact routine is refused and the arbitrary-precision one
            // takes over. A zero rate makes the answer checkable by inspection.
            assertThat(Precision.onePlusPow(BigDecimal.ZERO, new BigDecimal("1234567890")))
                .isEqualByComparingTo("1");
        }
    }

    // ---------------------------------------------------- the fractional path

    @Nested
    @DisplayName("the fractional power path — required by actual-date discounting")
    class FractionalPath {

        @Test
        @DisplayName("a half-year power equals the square root, to 1e-24 and better")
        void halfPowerMatchesAnIndependentSquareRoot() {
            BigDecimal base = new BigDecimal("0.0104214918");
            BigDecimal viaPow = Precision.onePlusPow(base, new BigDecimal("0.5"));
            BigDecimal viaSqrt = BigDecimalMath.sqrt(BigDecimal.ONE.add(base), CHECK);
            assertThat(viaPow).isCloseTo(viaSqrt, within(FRACTIONAL_TOLERANCE));
            // The published value of sqrt(1.0104214918), to 28 digits.
            assertThat(viaPow).isCloseTo(
                new BigDecimal("1.005197240246907960043262982"), within(FRACTIONAL_TOLERANCE));
        }

        @Test
        @DisplayName("a known published root: sqrt(1.05)")
        void knownPublishedRoot() {
            assertThat(Precision.onePlusPow(new BigDecimal("0.05"), new BigDecimal("0.5")))
                .isCloseTo(new BigDecimal("1.024695076595959838322103868"),
                    within(FRACTIONAL_TOLERANCE));
        }

        @Test
        void aQuarterPowerIsTheSquareRootOfTheSquareRoot() {
            BigDecimal base = new BigDecimal("0.132480940855");
            BigDecimal onePlus = BigDecimal.ONE.add(base);
            BigDecimal viaPow = Precision.onePlusPow(base, new BigDecimal("0.25"));
            BigDecimal viaSqrt = BigDecimalMath.sqrt(BigDecimalMath.sqrt(onePlus, CHECK), CHECK);
            assertThat(viaPow).isCloseTo(viaSqrt, within(FRACTIONAL_TOLERANCE));
        }

        @Test
        @DisplayName("squaring a half power returns the base — the round trip that discounting relies on")
        void squaringAHalfPowerReturnsTheBase() {
            BigDecimal base = new BigDecimal("0.0104214918");
            BigDecimal half = Precision.onePlusPow(base, new BigDecimal("0.5"));
            assertThat(half.multiply(half, Precision.WORKING))
                .isCloseTo(BigDecimal.ONE.add(base), within(FRACTIONAL_TOLERANCE));
        }

        @Test
        @DisplayName("a mixed exponent splits into its whole and fractional parts")
        void mixedExponentsCompose() {
            // 12.5 periods = 12 whole periods then half of one, which is exactly
            // what a broken final period is; the two routes must not disagree.
            BigDecimal base = new BigDecimal("0.0104214918");
            BigDecimal composed = Precision.onePlusPow(base, 12)
                .multiply(Precision.onePlusPow(base, new BigDecimal("0.5")), Precision.WORKING);
            assertThat(Precision.onePlusPow(base, new BigDecimal("12.5")))
                .isCloseTo(composed, within(FRACTIONAL_TOLERANCE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"-0.5", "-1", "-0.000000000001", "-12"})
        void negativeFractionalExponentIsRejected(String exponent) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Precision.onePlusPow(new BigDecimal("0.01"), new BigDecimal(exponent)))
                .withMessageContaining("exponent must be non-negative");
        }

        @ParameterizedTest
        @ValueSource(strings = {"-1", "-1.5", "-2"})
        @DisplayName("a base at or below -100% is rejected rather than producing a complex or infinite answer")
        void nonPositiveOnePlusBaseIsRejected(String base) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Precision.onePlusPow(new BigDecimal(base), new BigDecimal("0.5")))
                .withMessageContaining("1 + base must be positive");
        }
    }

    // ------------------------------------------------------ discount factors

    @Nested
    @DisplayName("discounting and compounding are inverses")
    class DiscountFactors {

        @Test
        void aZeroExponentDiscountsToUnity() {
            assertThat(Precision.discountFactor(new BigDecimal("0.0104214918"), BigDecimal.ZERO))
                .isEqualByComparingTo("1");
        }

        @Test
        void aZeroRateDoesNotDiscount() {
            assertThat(Precision.discountFactor(BigDecimal.ZERO, new BigDecimal("2.5")))
                .isEqualByComparingTo("1");
        }

        @Test
        void oneWholePeriodIsTheReciprocal() {
            assertThat(Precision.discountFactor(new BigDecimal("0.01"), BigDecimal.ONE))
                .isCloseTo(new BigDecimal("0.9900990099009900990099009901"), within(ULP_TOLERANCE));
        }

        @ParameterizedTest(name = "rate {0}, tau {1}")
        @CsvSource({
            "0.0104214918, 1", "0.0104214918, 12", "0.0104214918, 60",
            "0.0104214918, 0.5", "0.0104214918, 2.5",
            "0.132480940855, 0.246575342466", "0.132480940855, 4.75",
            "0.07, 0.019178082192"
        })
        void roundTripsAgainstCompounding(String rate, String tau) {
            // Discount then compound returns the amount discounted. Everything the
            // solver and both amortisation legs do rests on this holding for
            // fractional tau as well as whole periods.
            BigDecimal r = new BigDecimal(rate);
            BigDecimal t = new BigDecimal(tau);
            BigDecimal factor = Precision.discountFactor(r, t);
            assertThat(factor.multiply(Precision.onePlusPow(r, t), Precision.WORKING))
                .isCloseTo(BigDecimal.ONE, within(FRACTIONAL_TOLERANCE));
        }

        @Test
        void anIntegerTauMatchesTheReciprocalOfTheIntegerPower() {
            BigDecimal rate = new BigDecimal("0.0104214918");
            BigDecimal viaFactor = Precision.discountFactor(rate, new BigDecimal("12"));
            BigDecimal viaReciprocal = BigDecimal.ONE.divide(
                Precision.onePlusPow(rate, 12), Precision.WORKING);
            assertThat(viaFactor).isEqualByComparingTo(viaReciprocal);
        }

        @Test
        @DisplayName("the annual effective rate over a fractional year matches the monthly rate over the periods")
        void theTwoTimeConventionsDiscountAlike() {
            // The equivalence the convention choice depends on: 12 monthly periods
            // at the periodic rate and one year at the effective annual rate are the
            // same discount factor. Where the vector is uniform, periodic indexing
            // is an optimisation and not a different answer.
            BigDecimal monthly = new BigDecimal("0.010421491800");
            BigDecimal annual = Rate.monthly(monthly).effectiveAnnual();
            assertThat(Precision.discountFactor(monthly, new BigDecimal("12")))
                .isCloseTo(Precision.discountFactor(annual, BigDecimal.ONE),
                    within(FRACTIONAL_TOLERANCE));
            assertThat(Precision.discountFactor(monthly, new BigDecimal("6")))
                .isCloseTo(Precision.discountFactor(annual, new BigDecimal("0.5")),
                    within(FRACTIONAL_TOLERANCE));
        }

        @Test
        void aNegativeTauIsRejected() {
            // A flow before the anchor is a projection defect, not a compounding
            // instruction; the vector rejects it and so does this.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Precision.discountFactor(
                    new BigDecimal("0.01"), new BigDecimal("-0.5")))
                .withMessageContaining("exponent must be non-negative");
        }

        @Test
        void discountFactorsFallMonotonicallyWithTau() {
            BigDecimal rate = new BigDecimal("0.0104214918");
            BigDecimal near = Precision.discountFactor(rate, new BigDecimal("1"));
            BigDecimal mid = Precision.discountFactor(rate, new BigDecimal("1.5"));
            BigDecimal far = Precision.discountFactor(rate, new BigDecimal("60"));
            assertThat(near).isLessThan(BigDecimal.ONE).isGreaterThan(mid);
            assertThat(mid).isGreaterThan(far);
            assertThat(far).isGreaterThan(BigDecimal.ZERO);
        }
    }
}
