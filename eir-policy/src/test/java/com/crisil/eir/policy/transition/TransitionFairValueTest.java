package com.crisil.eir.policy.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The ACPIR 19 day-1 fair valuation (FR-908, 04 § 6, 08 Phase 4).
 *
 * <p><b>The fixture, and its arithmetic derived by hand.</b> Four contracts at 1 April 2027,
 * chosen so that each technique appears and the difference is a different sign in each direction:
 *
 * <pre>
 *   A  DCF at 0.9% monthly     carrying 1,000,000.00  fair 940,000.00  diff  −60,000.00
 *   B  carrying cost, evidenced  carrying 500,000.00  fair 500,000.00  diff        0.00
 *   C  carrying cost, NO evidence carrying 250,000.00 fair 250,000.00  diff        0.00
 *   D  quoted price              carrying 300,000.00  fair 312,500.00  diff  +12,500.00
 *                                                       total to equity  −47,500.00
 * </pre>
 *
 * <p>B and C are the pair that matters. They produce identical numbers — a nil difference — and one
 * of them is a measurement and the other is not. The only thing separating them is the evidence
 * reference, which is precisely why TF-1 exists.
 */
class TransitionFairValueTest {

    private static final java.time.LocalDate APRIL_2027 = java.time.LocalDate.of(2027, 4, 1);

    private static TransitionFairValue dcf() {
        return new TransitionFairValue("A", APRIL_2027,
            Money.inr("1000000.00"), Money.inr("940000.00"),
            ValuationTechnique.DISCOUNTED_CASH_FLOW, Rate.monthly(new BigDecimal("0.009")),
            null, "valuation.analyst", "valuation.reviewer");
    }

    private static TransitionFairValue evidencedPresumption() {
        return new TransitionFairValue("B", APRIL_2027,
            Money.inr("500000.00"), Money.inr("500000.00"),
            ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null,
            "FY27-EVID-001", "valuation.analyst", "valuation.reviewer");
    }

    private static TransitionFairValue unevidencedPresumption() {
        return new TransitionFairValue("C", APRIL_2027,
            Money.inr("250000.00"), Money.inr("250000.00"),
            ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null,
            null, "valuation.analyst", null);
    }

    private static TransitionFairValue quoted() {
        return new TransitionFairValue("D", APRIL_2027,
            Money.inr("300000.00"), Money.inr("312500.00"),
            ValuationTechnique.QUOTED_PRICE, null,
            null, "valuation.analyst", "valuation.reviewer");
    }

    @Nested
    @DisplayName("the difference goes to opening retained earnings")
    class Difference {

        @Test
        @DisplayName("a fair value below the carrying amount is a negative adjustment to equity")
        void negativeAdjustment() {
            // 940,000.00 − 1,000,000.00 = −60,000.00. Negative is the ordinary direction for a
            // legacy portfolio remeasured at a current market rate, and it lands on the opening
            // balance rather than a period result — ACPIR 19, not a P&L remeasurement.
            assertThat(dcf().differenceToOpeningRetainedEarnings())
                .isEqualTo(Money.inr("-60000.00"));
        }

        @Test
        @DisplayName("a fair value above it is positive, and a nil difference is nil")
        void bothDirections() {
            assertThat(quoted().differenceToOpeningRetainedEarnings())
                .as("312,500.00 − 300,000.00")
                .isEqualTo(Money.inr("12500.00"));
            assertThat(evidencedPresumption().differenceToOpeningRetainedEarnings())
                .as("carrying cost as best evidence: measured, and found no difference")
                .isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("the run total is summed unrounded")
        void totalIsExact() {
            // −60,000.00 + 0.00 + 0.00 + 12,500.00 = −47,500.00
            TransitionValuationRun run = TransitionValuationRun.over(
                List.of(dcf(), evidencedPresumption(), unevidencedPresumption(), quoted()));
            assertThat(run.totalDifferenceToOpeningRetainedEarnings())
                .isEqualTo(Money.inr("-47500.00"));

            // Rounding each contract before summing accumulates into a figure posted to equity.
            // Asserted on a fixture whose per-contract differences do not round cleanly: three
            // contracts each off by a third of a paise sum to a paise, and a per-contract round
            // would lose it.
            TransitionValuationRun fractional = TransitionValuationRun.over(List.of(
                thirdOfAPaise("X1"), thirdOfAPaise("X2"), thirdOfAPaise("X3")));
            assertThat(fractional.totalDifferenceToOpeningRetainedEarnings()
                .atPresentationScale())
                .as("0.003333 x 3 = 0.009999, which presents as 0.01 — and would be nil if each"
                    + " contract had been rounded first")
                .hasToString("INR 0.01");
        }

        private TransitionFairValue thirdOfAPaise(String id) {
            return new TransitionFairValue(id, APRIL_2027,
                Money.inr("1000.000000"), Money.inr("1000.003333"),
                ValuationTechnique.QUOTED_PRICE, null, null, "valuation.analyst", null);
        }
    }

    @Nested
    @DisplayName("TF-1: the paragraph 19 presumption needs a file behind it")
    class Paragraph19 {

        @Test
        @DisplayName("two contracts with identical numbers, and only one of them measured anything")
        void evidenceIsTheOnlyDifference() {
            // B and C both report a nil difference. That is the whole problem with the
            // presumption: applied without a file, "we kept the numbers we had" is
            // indistinguishable from a valuation that was performed and found no difference.
            assertThat(evidencedPresumption().differenceToOpeningRetainedEarnings())
                .isEqualTo(unevidencedPresumption().differenceToOpeningRetainedEarnings());

            assertThat(evidencedPresumption().paragraph19Evidenced().satisfied()).isTrue();
            assertThat(unevidencedPresumption().paragraph19Evidenced().satisfied()).isFalse();
        }

        @Test
        @DisplayName("a technique that does not use the presumption passes trivially")
        void notInPlay() {
            InvariantResult result = dcf().paragraph19Evidenced();
            assertThat(result.id()).isEqualTo(InvariantId.TF_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("the presumption is not in play");
        }

        @Test
        @DisplayName("the run publishes one TF-1 with the count, not one per contract")
        void oneResultWithACount() {
            // conjunction keeps only the first breach's deviation among same-id results, so a run
            // emitting one TF-1 per contract would publish a deviation of 1 and read as a single
            // unevidenced contract however many there are.
            TransitionValuationRun run = TransitionValuationRun.over(List.of(
                dcf(), evidencedPresumption(), unevidencedPresumption(), quoted(),
                new TransitionFairValue("E", APRIL_2027,
                    Money.inr("100000.00"), Money.inr("100000.00"),
                    ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null, null,
                    "valuation.analyst", null)));

            InvariantResult result = run.paragraph19Evidenced();
            assertThat(result.id()).isEqualTo(InvariantId.TF_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("two unevidenced presumptions, C and E, not one")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(result.detail()).contains("2 of 3 paragraph 19 presumptions");

            assertThat(InvariantResult.conjunction(run.perContractEvidence()).deviation())
                .as("the per-contract results conjoined lose one of the two, which is why the run"
                    + " aggregates instead")
                .isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a fully evidenced run passes, and says how many rested on the presumption")
        void gateMet() {
            TransitionValuationRun run = TransitionValuationRun.over(
                List.of(dcf(), evidencedPresumption(), quoted()));
            InvariantResult result = run.paragraph19Evidenced();
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("1 of 3 valuations");
        }

        @Test
        @DisplayName("the technique mix is published, because a total adjustment hides it")
        void techniqueMixIsTheDisclosure() {
            // A run that is almost all carrying cost has measured almost nothing at fair value.
            // That is invisible in the total adjustment — which is the figure a reader looks at —
            // so the mix is published alongside it.
            TransitionValuationRun run = TransitionValuationRun.over(
                List.of(dcf(), evidencedPresumption(), unevidencedPresumption(), quoted()));
            assertThat(run.techniqueMix())
                .containsEntry(ValuationTechnique.DISCOUNTED_CASH_FLOW, 1L)
                .containsEntry(ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, 2L)
                .containsEntry(ValuationTechnique.QUOTED_PRICE, 1L);
        }
    }

    @Nested
    @DisplayName("what is refused, and what is reported instead")
    class Refusals {

        @Test
        @DisplayName("an unevidenced presumption is reported, not refused")
        void unevidencedConstructs() {
            // Deliberately weaker than the DDL, which rejects the row. A STORED row is a claim
            // that the valuation is final and storing an unevidenced presumption is what the
            // constraint should forbid; a COMPUTED one during the FY27 exercise that builds the
            // evidence file across the year is a normal intermediate state, and refusing it would
            // make the remaining work unmeasurable. Same argument RS-1 makes for a
            // partially-loaded fee taxonomy.
            assertThat(unevidencedPresumption().presumptionIsUnevidenced()).isTrue();
            assertThat(unevidencedPresumption().describe())
                .contains("[PARAGRAPH 19 PRESUMPTION, NO EVIDENCE]");
        }

        @Test
        @DisplayName("a discounted cash flow with no rate has not been performed")
        void dcfNeedsARate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TransitionFairValue("A", APRIL_2027,
                    Money.inr("1000000.00"), Money.inr("940000.00"),
                    ValuationTechnique.DISCOUNTED_CASH_FLOW, null, null,
                    "valuation.analyst", null))
                .withMessageContaining("has not been performed");
        }

        @Test
        @DisplayName("a rate on a technique that does not use one is refused, not ignored")
        void strayRateRefused() {
            // Either the technique is mis-recorded or the rate belongs to a working that was
            // abandoned, and both are things a reader would take as the basis of the number.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TransitionFairValue("D", APRIL_2027,
                    Money.inr("300000.00"), Money.inr("312500.00"),
                    ValuationTechnique.QUOTED_PRICE, Rate.monthly(new BigDecimal("0.009")),
                    null, "valuation.analyst", null))
                .withMessageContaining("belongs to a working that was not used");
        }

        @Test
        @DisplayName("evidence named on a technique that did not apply the presumption is refused")
        void strayEvidenceRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TransitionFairValue("D", APRIL_2027,
                    Money.inr("300000.00"), Money.inr("312500.00"),
                    ValuationTechnique.QUOTED_PRICE, null, "FY27-EVID-001",
                    "valuation.analyst", null))
                .withMessageContaining("documents a decision that was not taken");
        }

        @Test
        @DisplayName("a valuation reviewed by the person who performed it is not reviewed")
        void selfReviewRefused() {
            // Through FourEyes, so a case variant does not defeat it either.
            for (String variant : new String[] {
                "valuation.analyst", "Valuation.Analyst", " valuation.analyst "}) {
                assertThatIllegalArgumentException()
                    .as("reviewer '%s'", variant)
                    .isThrownBy(() -> new TransitionFairValue("A", APRIL_2027,
                        Money.inr("1000000.00"), Money.inr("940000.00"),
                        ValuationTechnique.QUOTED_PRICE, null, null,
                        "valuation.analyst", variant))
                    .withMessageContaining("the same single judgement it started with");
            }
        }

        @Test
        @DisplayName("a run is one transition date, because there is one opening balance")
        void mixedDatesRefused() {
            TransitionFairValue laterDate = new TransitionFairValue("F",
                java.time.LocalDate.of(2027, 4, 2),
                Money.inr("100.00"), Money.inr("100.00"),
                ValuationTechnique.QUOTED_PRICE, null, null, "valuation.analyst", null);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> TransitionValuationRun.over(List.of(quoted(), laterDate)))
                .withMessageContaining("one run is one transition date");
        }
    }

    @Nested
    @DisplayName("completeness against the book is a reconciliation, not an invariant")
    class Completeness {

        @Test
        @DisplayName("the run counts what it valued and reports the shortfall against a book size")
        void coverage() {
            // Deliberately not published under an invariant id. A contract missing from the
            // population was never presented to this run, and an id raised from here would
            // attribute a data-feed problem to the valuation.
            TransitionValuationRun run = TransitionValuationRun.over(
                List.of(dcf(), evidencedPresumption(), quoted()));
            assertThat(run.valuedContractCount()).isEqualTo(3);
            assertThat(run.coverageAgainst(10)).isEqualTo(7L);
            assertThat(run.coverageAgainst(3)).isEqualTo(0L);
            assertThat(run.coverageAgainst(2))
                .as("never negative: more valuations than the book is the caller's problem to"
                    + " explain, not a negative shortfall to propagate")
                .isEqualTo(0L);
        }

        @Test
        @DisplayName("an empty run has no date to take, and says so")
        void emptyRunRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TransitionValuationRun.over(List.of()))
                .withMessageContaining("supply the date explicitly");
        }
    }
}
