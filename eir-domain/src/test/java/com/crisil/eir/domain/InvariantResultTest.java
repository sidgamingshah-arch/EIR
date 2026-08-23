package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The invariants are asserted in production, not only in tests, so the assertion
 * mechanism needs its own tests.
 *
 * <p>The one design decision worth pinning is that {@link InvariantResult#ofMoney}
 * compares at <em>presentation</em> scale. An invariant is a claim about the
 * figures that get published; comparing at working precision would fail the close
 * on a difference no reader could ever see, and comparing at presentation scale
 * means a genuine sub-paisa working difference passes. That is deliberate, and it
 * cuts both ways — two amounts a thousandth apart across a rounding boundary
 * breach, and two amounts a thousandth apart within one do not.
 */
class InvariantResultTest {

    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency KWD = Currency.getInstance("KWD");

    // ------------------------------------------------------ presentation scale

    @Nested
    @DisplayName("ofMoney compares at presentation scale, deliberately")
    class MoneyComparison {

        @Test
        @DisplayName("a working-precision difference below presentation scale passes")
        void differenceBelowPresentationScalePasses() {
            // The realistic case: an EIR-leg carrying amount carried at 28 digits
            // against a contractual leg carried the same way. The tail differs and
            // the published rupee figure does not.
            InvariantResult result = InvariantResult.ofMoney(
                InvariantId.INV_4, "unamortised fee = leg difference",
                Money.inr("1407.294999999999999999"),
                Money.inr("1407.290000000000000001"));

            assertThat(result.satisfied()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo("0");
            assertThat(result.id()).isEqualTo(InvariantId.INV_4);
            assertThat(result.detail()).contains("unamortised fee").contains("1407.29");
        }

        @Test
        @DisplayName("a difference visible at presentation scale breaches, and the deviation is the visible one")
        void differenceAtPresentationScaleFails() {
            InvariantResult result = InvariantResult.ofMoney(
                InvariantId.TR_1, "terminal EIR-leg GCA = 0",
                Money.inr("0.00"),
                Money.inr("0.01"));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo("0.01");
            assertThat(result.detail()).contains("expected").contains("INR 0.00").contains("INR 0.01");
        }

        @Test
        @DisplayName("the deviation is signed — which side of the expectation the figure fell")
        void deviationIsSigned() {
            InvariantResult over = InvariantResult.ofMoney(
                InvariantId.IC_1, "GCA0 = net cash flow at inception",
                Money.inr("495000.00"), Money.inr("495100.00"));
            InvariantResult under = InvariantResult.ofMoney(
                InvariantId.IC_1, "GCA0 = net cash flow at inception",
                Money.inr("495000.00"), Money.inr("494900.00"));

            assertThat(over.deviation()).isEqualByComparingTo("100.00");
            assertThat(under.deviation()).isEqualByComparingTo("-100.00");
        }

        @Test
        @DisplayName("comparing at presentation scale means the rounding boundary decides")
        void theRoundingBoundaryDecides() {
            // A thousandth of a rupee either side of the half-way point: the raw
            // difference is one thousandth in both cases, but only the pair that
            // straddles the boundary breaches. This is the honest cost of comparing
            // published figures, and it is stated rather than hidden.
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "same side of the boundary",
                Money.inr("1000.003"), Money.inr("1000.004")).satisfied()).isTrue();
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "across the boundary",
                Money.inr("1000.004"), Money.inr("1000.005")).satisfied()).isFalse();
        }

        @Test
        @DisplayName("presentation scale is the currency's, not two decimals by assumption")
        void theScaleFollowsTheCurrency() {
            // JPY has no minor units, so a difference of 0.4 is invisible and passes.
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.4", JPY), Money.of("100.0", JPY)).satisfied()).isTrue();
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.4", JPY), Money.of("100.6", JPY)).satisfied()).isFalse();
            // KWD has three, so the same 0.4 fils difference is visible and breaches.
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.4", KWD), Money.of("100.0", KWD)).satisfied()).isFalse();
        }

        @Test
        @DisplayName("equal values at different scales satisfy the invariant")
        void scaleAloneIsNotABreach() {
            assertThat(InvariantResult.ofMoney(InvariantId.INV_3, "cash received",
                Money.inr("134763.2800"), Money.inr("134763.28")).satisfied()).isTrue();
        }

        @Test
        @DisplayName("a currency mismatch is a defect in the caller, not an invariant breach")
        void aCurrencyMismatchIsRejectedRatherThanReportedAsABreach() {
            // The failure path subtracts to size the deviation, and subtraction
            // across currencies has no meaning. Better a loud exception than a
            // breach report the reader would try to reconcile.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                    Money.inr("100.00"), Money.of("100", JPY)))
                .withMessageContaining("currency mismatch");
        }
    }

    // --------------------------------------------------------- plain factories

    @Nested
    class Factories {

        @Test
        void passCarriesNoDeviation() {
            InvariantResult pass = InvariantResult.pass(InvariantId.CU_1, "EIR unchanged across catch-up");
            assertThat(pass.satisfied()).isTrue();
            assertThat(pass.deviation()).isEqualByComparingTo("0");
            assertThat(pass.detail()).isEqualTo("EIR unchanged across catch-up");
            assertThat(pass.id()).isEqualTo(InvariantId.CU_1);
        }

        @Test
        void failRetainsTheDeviationItWasGiven() {
            InvariantResult fail = InvariantResult.fail(
                InvariantId.ST_2, "net interest + ECL unwind = gross interest", new BigDecimal("-12.34"));
            assertThat(fail.satisfied()).isFalse();
            assertThat(fail.deviation()).isEqualByComparingTo("-12.34");
        }

        @Test
        void nullsAreNamed() {
            assertThatNullPointerException()
                .isThrownBy(() -> new InvariantResult(null, true, "detail", BigDecimal.ZERO))
                .withMessageContaining("id");
            assertThatNullPointerException()
                .isThrownBy(() -> new InvariantResult(InvariantId.IC_1, true, null, BigDecimal.ZERO))
                .withMessageContaining("detail");
            assertThatNullPointerException()
                .isThrownBy(() -> new InvariantResult(InvariantId.IC_1, true, "detail", null))
                .withMessageContaining("deviation");
        }
    }

    // ------------------------------------------------------------------ orThrow

    @Nested
    @DisplayName("orThrow — a breach is a control exception, not a rounding nuisance")
    class OrThrow {

        @Test
        void aSatisfiedResultPassesItselfThrough() {
            InvariantResult pass = InvariantResult.pass(InvariantId.DT_1, "deterministic replay");
            assertThat(pass.orThrow()).isSameAs(pass);
        }

        @Test
        void aBreachThrowsCarryingTheResult() {
            InvariantResult breach = InvariantResult.fail(
                InvariantId.S3_2, "Stage 3 recognised income = 0", new BigDecimal("523.19"));

            assertThatExceptionOfType(InvariantBreachException.class)
                .isThrownBy(breach::orThrow)
                .withMessageContaining("S3_2")
                .withMessageContaining("Stage 3 recognised income = 0")
                .satisfies(thrown -> {
                    assertThat(thrown.result()).isSameAs(breach);
                    assertThat(thrown.result().deviation()).isEqualByComparingTo("523.19");
                });
        }

        @Test
        @DisplayName("the breach is unchecked, so nothing has to catch it to continue")
        void theBreachIsUnchecked() {
            // Nothing in the engine catches this to substitute a value; an unchecked
            // exception is the shape that makes that the default rather than a choice.
            assertThat(RuntimeException.class).isAssignableFrom(InvariantBreachException.class);
        }

        @Test
        void aMoneyBreachThrowsThroughTheSamePath() {
            assertThatExceptionOfType(InvariantBreachException.class)
                .isThrownBy(() -> InvariantResult.ofMoney(
                    InvariantId.IC_1, "GCA0 = net cash flow at inception",
                    Money.inr("495000.00"), Money.inr("495000.01")).orThrow())
                .withMessageContaining("IC_1");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InvariantId.class)
    @DisplayName("every invariant states itself, so a breach, a test and a workpaper name the same thing")
    void everyInvariantCarriesAStatement(InvariantId id) {
        assertThat(id.statement()).isNotBlank();
        assertThat(InvariantResult.pass(id, id.statement()).detail()).isEqualTo(id.statement());
    }
}
