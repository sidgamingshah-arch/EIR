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
        @DisplayName("the difference is reduced once, so a rounding boundary between the operands is not a breach")
        void theDifferenceIsReducedOnce() {
            // A thousandth of a rupee apart in both pairs, one pair straddling the
            // half-way point. Neither breaches: the invariant compares the reduced
            // DIFFERENCE, not two reduced operands, which is section 1.3's round-once
            // rule and the rule the reference cases publish (case 1 period 23 states
            // 19.50 from the working difference 19.4966, not the 19.51 the two
            // published balances differenced would give).
            //
            // Rounding both operands first would report the second pair as a one-paise
            // breach on a true difference of one thousandth of a rupee — and that error
            // has no floor, so a difference of 1e-20 straddling the boundary would
            // breach too. It bit a real 30-year exposure whose legs differed by 0.0031.
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "same side of the boundary",
                Money.inr("1000.003"), Money.inr("1000.004")).satisfied()).isTrue();
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "across the boundary",
                Money.inr("1000.004"), Money.inr("1000.005")).satisfied()).isTrue();
            // Half a paise is where a real difference starts, and it is reported.
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "half a paise apart",
                Money.inr("1000.000"), Money.inr("1000.005")).satisfied()).isFalse();
            assertThat(InvariantResult.ofMoney(InvariantId.INV_1, "half a paise apart",
                Money.inr("1000.000"), Money.inr("1000.005")).deviation())
                .isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("presentation scale is the currency's, not two decimals by assumption")
        void theScaleFollowsTheCurrency() {
            // JPY has no minor units, so a difference below half a yen is invisible.
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.4", JPY), Money.of("100.0", JPY)).satisfied()).isTrue();
            // 0.2 of a yen, reduced once, is still zero yen — the operands happen to
            // straddle 100.5 but the difference between them does not reach half a unit.
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.4", JPY), Money.of("100.6", JPY)).satisfied()).isTrue();
            // Half a yen apart is a real difference at JPY scale and is reported.
            assertThat(InvariantResult.ofMoney(InvariantId.SL_1, "sub-ledger ties to GL",
                Money.of("100.0", JPY), Money.of("100.5", JPY)).satisfied()).isFalse();
            // KWD has three minor digits, so the same 0.4 is enormous there and breaches.
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

    @Nested
    @DisplayName("a named invariant gets exactly one answer per period")
    class Conjunction {

        // The problem this solves, found three times before it was generalised. PC-1 was
        // asserted twice per LMS projection with the two routes disagreeing; ST-2 twice per
        // Stage 3 decomposition; ST-3 three times per blueprint projection, two of them the
        // same statement and the third a different claim. In every case the conjunction was
        // still enforced, so no breach was lost — but anything resolving the invariant by
        // name got whichever result came first, which on ST-3 was the one that passed.

        @Test
        @DisplayName("a breach anywhere fails the conjunction, whichever order the results arrive in")
        void aBreachAnywhereFailsTheWhole() {
            InvariantResult passed = InvariantResult.pass(InvariantId.ST_3, "the ladder ties");
            InvariantResult failed = InvariantResult.fail(
                InvariantId.ST_3, "the expected leg does not tie", new BigDecimal("-1200.00"));

            assertThat(InvariantResult.conjunction(List.of(passed, failed)).satisfied()).isFalse();
            assertThat(InvariantResult.conjunction(List.of(failed, passed)).satisfied()).isFalse();
            assertThat(InvariantResult.conjunction(List.of(passed, passed)).satisfied()).isTrue();
            assertThat(InvariantResult.conjunction(List.of(passed, failed)).deviation())
                .as("the breach's own magnitude survives, not a zero from the passing result")
                .isEqualByComparingTo(new BigDecimal("-1200.00"));
        }

        @Test
        @DisplayName("distinct evidence is kept and identical evidence collapses")
        void evidenceIsKeptWithoutStuttering() {
            // Which routes were asserted is the part worth keeping: a pass on one route
            // means something different from a pass on all of them. But two copies of one
            // statement is a stutter, and the common ST-3 case is exactly that.
            InvariantResult ladder = InvariantResult.pass(InvariantId.ST_3, "the ladder ties");
            InvariantResult behavioural =
                InvariantResult.pass(InvariantId.ST_3, "the expected leg ties");

            assertThat(InvariantResult.conjunction(List.of(ladder, behavioural)).detail())
                .isEqualTo("the ladder ties; the expected leg ties");
            assertThat(InvariantResult.conjunction(List.of(ladder, ladder, ladder)).detail())
                .as("the same statement gathered three times reads once")
                .isEqualTo("the ladder ties");
        }

        @Test
        @DisplayName("a single result passes through untouched rather than being reworded")
        void oneResultIsItself() {
            InvariantResult only = InvariantResult.pass(InvariantId.IC_1, "as at inception");
            assertThat(InvariantResult.conjunction(List.of(only))).isSameAs(only);
        }

        @Test
        @DisplayName("conjoining across invariants is refused: a conjunction is one invariant's own results")
        void mixedIdentifiersAreRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> InvariantResult.conjunction(List.of(
                    InvariantResult.pass(InvariantId.ST_3, "a"),
                    InvariantResult.pass(InvariantId.ST_5, "b"))))
                .withMessageContaining("cannot conjoin");
            // An empty conjunction would be a control that silently vanished.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> InvariantResult.conjunction(List.of()))
                .withMessageContaining("at least one result");
        }

        @Test
        @DisplayName("collapsing a mixed list leaves one result per invariant, in first-appearance order")
        void collapsingAMixedListPreservesOrder() {
            List<InvariantResult> gathered = List.of(
                InvariantResult.pass(InvariantId.ST_3, "the ladder ties"),
                InvariantResult.pass(InvariantId.ST_5, "terminal as intended"),
                InvariantResult.pass(InvariantId.ST_3, "the expected leg ties"),
                InvariantResult.pass(InvariantId.ST_5, "terminal as intended"),
                InvariantResult.pass(InvariantId.PC_1, "screened"));

            List<InvariantResult> collapsed = InvariantResult.oneResultPerInvariant(gathered);

            assertThat(collapsed).hasSize(3);
            assertThat(collapsed.stream().map(InvariantResult::id))
                .as("first-appearance order, so a report reads in the order things were computed")
                .containsExactly(InvariantId.ST_3, InvariantId.ST_5, InvariantId.PC_1);
            assertThat(collapsed.get(0).detail()).isEqualTo("the ladder ties; the expected leg ties");
            assertThat(collapsed.get(1).detail())
                .as("the duplicated ST-5 collapses to one statement")
                .isEqualTo("terminal as intended");
            assertThat(collapsed).isUnmodifiable();
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
