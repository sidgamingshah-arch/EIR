package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link Money} carries a working-precision amount and reduces to presentation
 * scale exactly once. Both halves of that sentence are load-bearing and both are
 * tested here: an amount that rounds to currency scale on every operation loses
 * the fee amortisation tail, and an amount that never rounds cannot be posted.
 */
class MoneyTest {

    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency KWD = Currency.getInstance("KWD");
    private static final Currency USD = Currency.getInstance("USD");

    // ------------------------------------------------------------- equality

    @Nested
    @DisplayName("equality is by value, because the record default would make 1.0 != 1.00")
    class Equality {

        @Test
        void differentScalesSameValueAreEqual() {
            assertThat(Money.inr("1.0")).isEqualTo(Money.inr("1.00"));
            assertThat(Money.inr("1")).isEqualTo(Money.inr("1.000000000000"));
            assertThat(Money.inr("500000")).isEqualTo(Money.inr("500000.00"));
            // The scale-sensitive comparison a record's default equals would have
            // used, kept visible so the reason for the override stays legible.
            assertThat(Money.inr("1.0").amount()).isNotEqualTo(Money.inr("1.00").amount());
            assertThat(Money.inr("1.0").amount()).isEqualByComparingTo(Money.inr("1.00").amount());
        }

        @Test
        void differentScalesSameValueHashAlike() {
            assertThat(Money.inr("1.0")).hasSameHashCodeAs(Money.inr("1.00"));
            assertThat(Money.inr("134763.2800")).hasSameHashCodeAs(Money.inr("134763.28"));
            // Zero is the classic trap: stripTrailingZeros on 0.00 must reach 0.
            assertThat(Money.inr("0.00")).hasSameHashCodeAs(Money.zero(Money.INR));
            assertThat(Money.inr("0.00")).isEqualTo(Money.zero(Money.INR));
        }

        @Test
        void sameAmountInDifferentCurrenciesIsNotEqual() {
            assertThat(Money.of("100.00", Money.INR)).isNotEqualTo(Money.of("100.00", USD));
            assertThat(Money.of("100.00", Money.INR)).isNotEqualTo(Money.of("100.00", JPY));
        }

        @Test
        void notEqualToNullOrOtherTypes() {
            assertThat(Money.inr("1.00")).isNotEqualTo(null);
            assertThat(Money.inr("1.00")).isNotEqualTo(new BigDecimal("1.00"));
            assertThat(Money.inr("1.00")).isNotEqualTo("INR 1.00");
        }

        @Test
        void reflexiveAndSymmetric() {
            Money a = Money.inr("1.5");
            Money b = Money.inr("1.500");
            assertThat(a).isEqualTo(a);
            assertThat(a).isEqualTo(b);
            assertThat(b).isEqualTo(a);
        }
    }

    // ------------------------------------------------------ currency discipline

    @Nested
    @DisplayName("a currency mismatch is a defect, never a silent conversion")
    class CurrencyDiscipline {

        @Test
        void plusRejectsMismatch() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of("1.00", Money.INR).plus(Money.of("1.00", USD)))
                .withMessageContaining("currency mismatch")
                .withMessageContaining("INR")
                .withMessageContaining("USD");
        }

        @Test
        void minusRejectsMismatch() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of("1.00", Money.INR).minus(Money.of("1.00", JPY)))
                .withMessageContaining("currency mismatch");
        }

        @Test
        void compareToRejectsMismatch() {
            // Ordering across currencies has no meaning without a rate, and the
            // engine holds no rates: this must not fall through to comparing
            // numerals.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of("1.00", Money.INR).compareTo(Money.of("1.00", KWD)))
                .withMessageContaining("currency mismatch");
        }

        @Test
        void sameCurrencyOperationsKeepTheCurrency() {
            assertThat(Money.of("1", KWD).plus(Money.of("2", KWD)).currency()).isEqualTo(KWD);
            assertThat(Money.of("1", JPY).times(new BigDecimal("3")).currency()).isEqualTo(JPY);
        }

        @Test
        void constructorRejectsNulls() {
            assertThatNullPointerException()
                .isThrownBy(() -> new Money(null, Money.INR))
                .withMessageContaining("amount");
            assertThatNullPointerException()
                .isThrownBy(() -> new Money(BigDecimal.ONE, null))
                .withMessageContaining("currency");
        }
    }

    // ------------------------------------------------------- presentation scale

    @Nested
    @DisplayName("presentation scale comes from ISO 4217 minor units, per currency")
    class PresentationScale {

        @Test
        void inrCarriesTwoMinorUnits() {
            assertThat(Money.inr("1234.5678").atPresentationScale().amount())
                .isEqualByComparingTo("1234.57");
            assertThat(Money.inr("1234.5678").atPresentationScale().amount().scale()).isEqualTo(2);
            assertThat(Money.inr("1234.5678").presentationScale()).isEqualTo(2);
        }

        @Test
        void jpyCarriesNoMinorUnits() {
            assertThat(Money.of("1234.5678", JPY).presentationScale()).isZero();
            assertThat(Money.of("1234.5678", JPY).atPresentationScale().amount())
                .isEqualByComparingTo("1235");
            assertThat(Money.of("1234.5678", JPY).atPresentationScale().amount().scale()).isZero();
            // The half-way case, to prove the mode reaches every currency.
            assertThat(Money.of("100.5", JPY).atPresentationScale().amount())
                .isEqualByComparingTo("101");
        }

        @Test
        void kwdCarriesThreeMinorUnits() {
            assertThat(Money.of("1234.5678", KWD).presentationScale()).isEqualTo(3);
            assertThat(Money.of("1234.5678", KWD).atPresentationScale().amount())
                .isEqualByComparingTo("1234.568");
            assertThat(Money.of("1234.5678", KWD).atPresentationScale().amount().scale()).isEqualTo(3);
        }

        @Test
        @DisplayName("HALF_UP, not banker's rounding — the reconciliation argument beats the statistical one")
        void roundsHalfUpAwayFromZero() {
            // HALF_EVEN would give 0.12 and 0.14 here. Indian financial reporting
            // convention and every downstream target this engine ties to use
            // HALF_UP, so a half-way amount always moves away from zero.
            assertThat(Money.inr("0.125").atPresentationScale().amount()).isEqualByComparingTo("0.13");
            assertThat(Money.inr("0.135").atPresentationScale().amount()).isEqualByComparingTo("0.14");
            assertThat(Money.inr("-0.125").atPresentationScale().amount()).isEqualByComparingTo("-0.13");
            assertThat(Money.of("0.5", JPY).atPresentationScale().amount()).isEqualByComparingTo("1");
            assertThat(Money.of("-0.5", JPY).atPresentationScale().amount()).isEqualByComparingTo("-1");
        }

        @Test
        void reducingIsIdempotent() {
            Money once = Money.inr("528407.315").atPresentationScale();
            assertThat(once.atPresentationScale()).isEqualTo(once);
            assertThat(once.amount()).isEqualByComparingTo("528407.32");
        }

        @Test
        @DisplayName("reducing to presentation scale is the only lossy operation")
        void reducingIsTheOnlyPlacePrecisionIsLost() {
            Money exact = Money.inr("1000.004999999999999999999999");
            assertThat(exact.amount().scale()).isEqualTo(24);
            assertThat(exact.plus(Money.zero(Money.INR)).amount())
                .isEqualByComparingTo("1000.004999999999999999999999");
            assertThat(exact.atPresentationScale().amount()).isEqualByComparingTo("1000.00");
        }
    }

    // ------------------------------------------------------ working arithmetic

    @Nested
    @DisplayName("arithmetic runs at working precision and does not round early")
    class WorkingPrecision {

        @Test
        void additionRetainsDigitsBelowPresentationScale() {
            // A fee amortisation tail lives in exactly these digits; rounding each
            // addition to two places would lose it and the two legs would never
            // reconcile.
            Money sum = Money.inr("1000000.000000001").plus(Money.inr("0.000000004"));
            assertThat(sum.amount()).isEqualByComparingTo("1000000.000000005");
            assertThat(sum.amount().scale()).isGreaterThan(2);
            assertThat(sum).isNotEqualTo(Money.inr("1000000"));
        }

        @Test
        void divisionCarriesTwentyEightSignificantDigits() {
            Money third = Money.inr("1").dividedBy(new BigDecimal("3"));
            assertThat(third.amount().precision()).isEqualTo(Precision.WORKING.getPrecision());
            assertThat(third.amount()).isEqualByComparingTo("0.3333333333333333333333333333");
            // Not one third of course, but visibly not 0.33 either.
            assertThat(third.times(new BigDecimal("3"))).isNotEqualTo(Money.inr("1"));
            assertThat(third.times(new BigDecimal("3")).amount())
                .isEqualByComparingTo("0.9999999999999999999999999999");
        }

        @Test
        void divisionByZeroIsAnError() {
            assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> Money.inr("1").dividedBy(BigDecimal.ZERO));
        }

        @Test
        void multiplicationByARateStaysExactWhereItCan() {
            // 500,000 at 1.04214918% per month: the periodic interest figure of
            // reference case 1, exact at working precision, rounded only for the
            // ledger.
            Money interest = Money.inr("500000").times(new BigDecimal("0.010421491800"));
            assertThat(interest.amount()).isEqualByComparingTo("5210.745900000");
            assertThat(interest.atPresentationScale().amount()).isEqualByComparingTo("5210.75");
        }

        @Test
        void subtractionOfEqualValuesAtDifferentScalesIsZero() {
            assertThat(Money.inr("1.000").minus(Money.inr("1")).isZero()).isTrue();
            assertThat(Money.inr("1.000").minus(Money.inr("1"))).isEqualTo(Money.zero(Money.INR));
        }
    }

    // --------------------------------------------------------------- signs

    @Nested
    @DisplayName("signed from the holder's perspective — outflows negative, inflows positive")
    class Signs {

        @Test
        void signumAndPredicatesAgree() {
            Money outflow = Money.inr("-500000.00");
            Money inflow = Money.inr("495000.00");
            Money nil = Money.zero(Money.INR);

            assertThat(outflow.signum()).isEqualTo(-1);
            assertThat(outflow.isNegative()).isTrue();
            assertThat(outflow.isPositive()).isFalse();
            assertThat(outflow.isZero()).isFalse();

            assertThat(inflow.signum()).isEqualTo(1);
            assertThat(inflow.isPositive()).isTrue();
            assertThat(inflow.isNegative()).isFalse();

            assertThat(nil.signum()).isZero();
            assertThat(nil.isZero()).isTrue();
            assertThat(nil.isPositive()).isFalse();
            assertThat(nil.isNegative()).isFalse();
        }

        @Test
        void zeroIsUnsignedRegardlessOfScale() {
            assertThat(Money.inr("-0.00").isZero()).isTrue();
            assertThat(Money.inr("-0.00").signum()).isZero();
            assertThat(Money.inr("0.0000").negate().isZero()).isTrue();
        }

        @Test
        void negateFlipsAndAbsRemovesTheSign() {
            assertThat(Money.inr("-500000.00").negate().amount()).isEqualByComparingTo("500000.00");
            assertThat(Money.inr("-500000.00").abs().amount()).isEqualByComparingTo("500000.00");
            assertThat(Money.inr("500000.00").abs().amount()).isEqualByComparingTo("500000.00");
            assertThat(Money.inr("500000.00").negate().negate()).isEqualTo(Money.inr("500000.00"));
        }

        @Test
        @DisplayName("a liability is the same arithmetic with the signs inverted")
        void liabilityDirectionNeedsNoSeparatePath() {
            // The reason the solver has no liability-specific branch: negating the
            // whole vector negates the net, and nothing else changes.
            Money assetNet = Money.inr("-500000").plus(Money.inr("10000")).plus(Money.inr("-2500"));
            Money liabilityNet = Money.inr("500000").plus(Money.inr("-10000")).plus(Money.inr("2500"));
            assertThat(liabilityNet).isEqualTo(assetNet.negate());
            assertThat(assetNet.abs()).isEqualTo(liabilityNet.abs());
        }
    }

    // ------------------------------------------------------------- ordering

    @Nested
    class Ordering {

        @Test
        void comparesByValueNotScale() {
            assertThat(Money.inr("1.0")).isEqualByComparingTo(Money.inr("1.00"));
            assertThat(Money.inr("1.0").compareTo(Money.inr("1.00"))).isZero();
            assertThat(Money.inr("2").compareTo(Money.inr("1.99999"))).isPositive();
            assertThat(Money.inr("-2").compareTo(Money.inr("-1"))).isNegative();
        }

        @Test
        void sortsAscending() {
            List<Money> sorted = List.of(Money.inr("10.00"), Money.inr("-5"), Money.inr("0.000"))
                .stream()
                .sorted()
                .toList();
            assertThat(sorted).containsExactly(Money.inr("-5"), Money.zero(Money.INR), Money.inr("10"));
        }
    }

    @Test
    void toStringNamesTheCurrencyAndAvoidsExponentNotation() {
        assertThat(Money.inr("1234.50")).hasToString("INR 1234.50");
        assertThat(Money.of("1E+3", Money.INR)).hasToString("INR 1000");
        assertThat(Money.of("100", JPY)).hasToString("JPY 100");
    }

    @Test
    void factoriesAgree() {
        assertThat(Money.inr("1.00")).isEqualTo(Money.of("1.00", Money.INR));
        assertThat(Money.of("1.00", Money.INR)).isEqualTo(Money.of(new BigDecimal("1.00"), Money.INR));
        assertThat(Money.zero(Money.INR).amount()).isEqualByComparingTo("0");
        assertThat(Money.INR.getCurrencyCode()).isEqualTo("INR");
    }
}
