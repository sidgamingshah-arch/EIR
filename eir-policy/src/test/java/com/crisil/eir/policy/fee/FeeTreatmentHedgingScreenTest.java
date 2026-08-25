package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.PenalChargeScreen;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-208 and invariant HB-2: no hedging or swap cost in any EIR cash flow stream.
 *
 * <p>The failure mode under test is named in three separate places in the
 * specification — 01 §11 item 23, 02 §4 against FR-208, and 03 §12 — and the reason
 * it earns three mentions is that it is silent and permanent. The rate is solved
 * once and persisted at 12 decimal places (FR-404), so a swap premium folded into
 * the inception vector misstates interest in every period through to maturity and
 * nothing later in the amortisation contradicts it. That is why HB-2 is asserted
 * positively rather than trusted.
 */
class FeeTreatmentHedgingScreenTest {

    private static final LocalDate INCEPTION = LocalDate.of(2027, 4, 1);

    /** The treasury desk's own codes, as a fee master would declare them. */
    private static FeeTreatmentHedgingScreen screen() {
        return new FeeTreatmentHedgingScreen(
            "FEE-2027.1", List.of("SWAP-PREMIUM", "IRS-FIXED-LEG", "CCS-COST", "HEDGE-BREAK"));
    }

    private static FeePosting cost(String feeCode, String amount, FeeClassification treatment) {
        return FeePosting.paid(feeCode, Money.inr(amount), INCEPTION, treatment, "OTHER");
    }

    @Nested
    @DisplayName("the classified-fee route")
    class ClassifiedFeeRoute {

        @Test
        @DisplayName("a swap cost classified INTEGRAL breaches HB-2")
        void aSwapCostInsideTheStreamBreaches() {
            InvariantResult result = screen().overFeePostings(List.of(
                cost("PROC-COST", "5000.00", FeeClassification.INTEGRAL),
                cost("SWAP-PREMIUM", "120000.00", FeeClassification.INTEGRAL)));

            assertThat(result.id()).isEqualTo(InvariantId.HB_2);
            assertThat(result.satisfied())
                .as("ACPIR 53 excludes hedging costs as financing costs; this one is in the rate")
                .isFalse();
            assertThat(result.detail())
                .as("the breach names the offending code, so it is actionable without a re-run")
                .contains("SWAP-PREMIUM");
            // FeePosting.paid signs a cost negative to the holder, so the amount that entered
            // the inception net cash flow is -120,000.00 — and that signed figure is what
            // someone has to reverse, not a count of postings.
            assertThat(result.deviation())
                .as("the deviation is the amount by which the inception net cash flow is wrong")
                .isEqualByComparingTo(new BigDecimal("-120000.00"));
        }

        @Test
        @DisplayName("two swap costs in the stream deviate by their sum")
        void theDeviationIsTheSumOfTheOffendingAmounts() {
            // -120,000.00 + -30,000.00 = -150,000.00, worked by hand.
            InvariantResult result = screen().overFeePostings(List.of(
                cost("SWAP-PREMIUM", "120000.00", FeeClassification.INTEGRAL),
                cost("CCS-COST", "30000.00", FeeClassification.INTEGRAL)));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("-150000.00"));
            assertThat(result.detail()).contains("2 declared hedging or swap cost(s)");
        }

        @Test
        @DisplayName("a swap cost booked as incurred satisfies HB-2, and the pass says it was there")
        void aHedgingCostOutsideTheStreamPasses() {
            // The correct treatment: a financing cost in profit or loss, outside the rate.
            // Failing HB-2 on this would make the control fire on the right answer.
            InvariantResult result = screen().overFeePostings(List.of(
                cost("PROC-COST", "5000.00", FeeClassification.INTEGRAL),
                cost("SWAP-PREMIUM", "120000.00", FeeClassification.AS_INCURRED),
                cost("HEDGE-BREAK", "8000.00", FeeClassification.AS_INCURRED)));

            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .as("evidence, not silence: two swap costs were present and both stayed out")
                .contains("2 declared hedging cost(s) present, none of them integral");
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the pass distinguishes 'nothing was there' from 'nothing could be found'")
        void anEmptyMatchStillNamesTheDeclaration() {
            // A pass on a period where the treasury feed never arrived looks identical to a
            // pass on a period that was genuinely clean, unless the assertion says which
            // declaration was in force and how many codes it covered.
            InvariantResult result = screen().overFeePostings(
                List.of(cost("PROC-COST", "5000.00", FeeClassification.INTEGRAL)));

            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .contains("no posting matched a declared hedging or swap fee code")
                .contains("declaration FEE-2027.1", "4 code(s)", "1 posting(s) screened");
        }

        @Test
        @DisplayName("an empty fee set is still a positive assertion")
        void anEmptyFeeSetAsserts() {
            // PenalChargeScreen's reasoning, applied here: a control that only fires when
            // something is already impossible is not evidence that the exclusion held.
            InvariantResult result = screen().overFeePostings(List.of());

            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("0 posting(s) screened");
        }

        @Test
        @DisplayName("a breach spanning currencies reports a count, not an INR-plus-USD hybrid")
        void aCrossCurrencyBreachDoesNotReportAHybridTotal() {
            // Foreign currency loans are where this arises rather than an edge case: ACPIR
            // 101(3) and reference §4 item 18 put ECB and buyer's credit hedging costs in
            // scope, so a book carrying INR and USD swap costs together is ordinary. Summed
            // signed, -120,000.00 INR and -1,500.00 USD would publish as -121,500.00 of
            // nothing — a figure nobody can reconcile that also under-states the INR leg.
            InvariantResult result = screen().overFeePostings(List.of(
                cost("SWAP-PREMIUM", "120000.00", FeeClassification.INTEGRAL),
                FeePosting.paid("CCS-COST", Money.of("1500.00", Currency.getInstance("USD")),
                    INCEPTION, FeeClassification.INTEGRAL, "OTHER")));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("a count of the offending postings, because there is no reconcilable sum")
                .isEqualByComparingTo(new BigDecimal("2"));
            assertThat(result.detail())
                .as("and the detail says so, so nobody reads the count as money")
                .contains("deviation is a count of 2 posting(s) and not an amount")
                .contains("INR -120000.00", "USD -1500.00");
        }

        @Test
        @DisplayName("a hedging cost is recognised whatever the case of its fee code")
        void codesMatchAcrossCase() {
            assertThat(screen().isHedgingCost(" swap-premium ")).isTrue();
            assertThat(screen().isHedgingCost("PROC-COST")).isFalse();
        }
    }

    @Nested
    @DisplayName("the billed-schedule route")
    class BilledScheduleRoute {

        @Test
        @DisplayName("an unattested LMS feed cannot support HB-2")
        void anUnattestedFeedFails() {
            // 03 §12: some ALM teams think in all-in hedged cost and some finance systems let
            // them book it that way. A billed instalment carries a net amount and no
            // components, so a swap cost already dissolved into the rate is undetectable here
            // — and LMS_AUTHORITATIVE is the route the specification marks strongly preferred
            // in production (FR-102), so this is the common case and not the edge.
            InvariantResult result = screen().overBilledSchedule("CBS-LOANS", 24, null);

            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("no hedging-cost attestation")
                .contains("CBS-LOANS");
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("24"));
        }

        @Test
        @DisplayName("an attested feed supports it, and the attestation travels into the detail")
        void anAttestedFeedPasses() {
            PenalChargeScreen.Attestation attestation = new PenalChargeScreen.Attestation(
                "CBS-LOANS", "treasury.control", LocalDate.of(2027, 4, 3), Money.inr("120000.00"));

            InvariantResult result = screen().overBilledSchedule("CBS-LOANS", 24, attestation);

            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .as("who screened, when, and how much was stripped — the evidence itself")
                .contains("treasury.control", "2027-04-03", "INR 120000.00");
        }

        @Test
        @DisplayName("an attestation for another feed is evidence about that feed, not this one")
        void aMisPairedAttestationIsNotEvidence() {
            // At a period close screening two feeds where treasury attested only one, a
            // mis-paired call would otherwise emit HB-2 evidence naming the feed nobody
            // screened — the control gap the method exists to surface, dressed as a pass.
            PenalChargeScreen.Attestation forCards = new PenalChargeScreen.Attestation(
                "CBS-CARDS", "treasury.control", LocalDate.of(2027, 4, 3), Money.zero(Money.INR));

            InvariantResult result = screen().overBilledSchedule("CBS-LOANS", 24, forCards);

            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("attestation for a different feed, CBS-CARDS")
                .contains("Nothing attests to CBS-LOANS");
        }

        @Test
        @DisplayName("a nil amount removed is a screening, not an absence of one")
        void zeroRemovedIsStillAnAttestation() {
            // The distinction the attestation exists to draw: "we looked and found nothing" is
            // evidence, "nobody looked" is a control gap, and a nil figure is the first.
            PenalChargeScreen.Attestation clean = new PenalChargeScreen.Attestation(
                "CBS-LOANS", "treasury.control", LocalDate.of(2027, 4, 3), Money.zero(Money.INR));

            assertThat(screen().overBilledSchedule("CBS-LOANS", 24, clean).satisfied()).isTrue();
        }
    }

    @Nested
    @DisplayName("the declaration of hedging fee codes")
    class Declaration {

        @Test
        @DisplayName("an empty declaration is refused — it would pass every period")
        void anEmptyDeclarationIsRefused() {
            // A screen that can never match anything still emits a pass, which is worse than
            // no screen at all: it produces evidence that the exclusion held on a period
            // nobody screened.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentHedgingScreen("FEE-2027.1", List.of()))
                .withMessageContaining("indistinguishable from nobody having screened");
        }

        @Test
        @DisplayName("an unnamed declaration version is refused")
        void theDeclarationMustBeDated() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentHedgingScreen("  ", List.of("SWAP-PREMIUM")))
                .withMessageContaining("must name the policy version");
        }

        @Test
        @DisplayName("a blank code is refused rather than silently ignored")
        void aBlankCodeIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    new FeeTreatmentHedgingScreen("FEE-2027.1", List.of("SWAP-PREMIUM", " ")))
                .withMessageContaining("hides the fact that the declaration is incomplete");
        }

        @Test
        @DisplayName("codes are canonicalised, and the canonical form is what the screen reports")
        void codesAreCanonicalised() {
            FeeTreatmentHedgingScreen canonical =
                new FeeTreatmentHedgingScreen("FEE-2027.1", List.of(" swap-premium ", "SWAP-PREMIUM"));

            assertThat(canonical.declaredCodes())
                .as("one code, not two; the same canonical form FeeTreatmentRules uses")
                .containsExactly("SWAP-PREMIUM");
        }

        @Test
        @DisplayName("the declaration listing is in declaration order and does not vary per run")
        void theDeclarationListingIsStable() {
            // Set.copyOf randomises iteration order per JVM instance, and declaredCodes() is
            // public. FeePosting.COST_FUNCTIONS states this codebase's rule: a record that
            // varies between identical runs is not one.
            assertThat(screen().declaredCodes())
                .containsExactly("SWAP-PREMIUM", "IRS-FIXED-LEG", "CCS-COST", "HEDGE-BREAK");
        }
    }

    @Nested
    @DisplayName("one HB-2 answer per period")
    class OneAnswerPerPeriod {

        @Test
        @DisplayName("a passing fee route and a failing schedule route conjoin to a breach")
        void theConjunctionIsTheAnswer() {
            // The defect this prevents: emitted separately, the two routes give one pass and
            // one fail under the same identifier, and anything resolving HB-2 by name gets
            // whichever came first in the list. HB-2 is a named control an auditor asks for by
            // name and it gets one answer.
            FeeTreatmentHedgingScreen screen = screen();
            InvariantResult combined = FeeTreatmentHedgingScreen.combine(List.of(
                screen.overFeePostings(
                    List.of(cost("SWAP-PREMIUM", "120000.00", FeeClassification.AS_INCURRED))),
                screen.overBilledSchedule("CBS-LOANS", 24, null)));

            assertThat(combined.id()).isEqualTo(InvariantId.HB_2);
            assertThat(combined.satisfied()).isFalse();
            assertThat(combined.detail())
                .as("the evidence concatenates: which routes were screened is worth keeping")
                .contains("none of them integral")
                .contains("no hedging-cost attestation");
        }

        @Test
        @DisplayName("a foreign invariant cannot be folded into the hedging answer")
        void combineRefusesAForeignInvariant() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentHedgingScreen.combine(List.of(
                    InvariantResult.pass(InvariantId.HB_2, "fee route clean"),
                    InvariantResult.pass(InvariantId.PC_1, "penal route clean"))))
                .withMessageContaining("only HB-2 assertions combine here");
        }

        @Test
        @DisplayName("an empty list is not a pass")
        void anEmptyListIsNotAPass() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentHedgingScreen.combine(List.of()))
                .withMessageContaining("must be asserted on every projection");
        }
    }
}
