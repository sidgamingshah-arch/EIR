package com.crisil.eir.policy.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.approval.ApprovalRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contractual leg reconciled to core banking, as one RC-1 result (FR-804, control C-14).
 *
 * <p><b>Where the figures come from.</b> Both legs below are
 * <a href="../../../../../../../../../docs/reference-cases/case-01-emi-loan-with-fees.md">reference
 * case 1</a>: 1,000,000.00 advanced, 12% nominal monthly, a billed EMI of 47,073.47, a net integral
 * fee of 5,000.00 and a solved EIR of 1.04214918% per month. The contractual leg is rolled from the
 * case's terms at working precision and its interest column reproduces the case's published figures
 * (10,000.00, 9,629.27, 9,254.82); the EIR leg is built from the case's published interest column
 * (10,369.38, 9,986.87) taken as exact, which is enough for the one thing it is used for — proving
 * that the reconciliation reads the contractual leg and not this one.
 *
 * <p>No expected value here is produced by running the code under test. Each CBS figure is an engine
 * figure less a difference fixed in advance, and every expected deviation is the sum of those
 * differences, computed by hand and cross-checked in {@code python3}.
 */
class CoreBankingReconciliationTest {

    private static final int PERIOD = 202705;
    private static final Currency INR = Money.INR;
    private static final LocalDate CHECKED_ON = LocalDate.of(2027, 6, 4);
    private static final String FEED = "CBS-EOD-202705-E01";

    /** Reference case 1's contractual leg, rolled at 1.00% per month over the billed EMI. */
    private static AmortisationResult contractualLeg() {
        return leg(List.of(
            Money.inr("10000.0000"),
            Money.inr("9629.2653"),
            Money.inr("9254.823253")),
            Money.inr("1000000.00"));
    }

    /** Reference case 1's EIR leg, from the case's published interest column taken as exact. */
    private static AmortisationResult eirLeg() {
        return leg(List.of(
            Money.inr("10369.38"),
            Money.inr("9986.87"),
            Money.inr("9600.38")),
            Money.inr("995000.00"));
    }

    private static AmortisationResult leg(List<Money> interest, Money opening) {
        Money emi = Money.inr("47073.47");
        List<AmortisationRow> rows = new ArrayList<>();
        Money balance = opening;
        Money totalInterest = Money.zero(INR);
        for (int period = 1; period <= interest.size(); period++) {
            AmortisationRow row = AmortisationRow.of(
                period, LocalDate.of(2027, 4, 30).plusMonths(period - 1L),
                BigDecimal.ONE, balance, interest.get(period - 1), emi);
            rows.add(row);
            balance = row.closingGca();
            totalInterest = totalInterest.plus(row.eirInterest());
        }
        return new AmortisationResult(
            rows, totalInterest, emi.times(BigDecimal.valueOf(interest.size())), balance,
            List.of());
    }

    private static ContractualLegInterest engine(String contractId) {
        // Period 2 of the contractual leg: 9,629.2653 at working precision, published as 9,629.27.
        return ContractualLegInterest.fromContractualLeg(contractId, PERIOD, contractualLeg(), 2);
    }

    private static CbsBilledInterest cbs(String contractId, String amount) {
        return new CbsBilledInterest(contractId, PERIOD, Money.inr(amount), FEED);
    }

    private static DifferenceExplanation timing(String contractId, String amount) {
        return DifferenceExplanation.approved(
            contractId, PERIOD, DifferenceReason.BILLING_DAY_TIMING, Money.inr(amount),
            "CBS bills on the 5th; the accrual runs to month-end.",
            "recon.preparer", ApprovalRecord.by("recon.checker", CHECKED_ON));
    }

    private static CoreBankingReconciliation over(
        List<ContractualLegInterest> engineLines,
        List<CbsBilledInterest> cbsLines,
        List<DifferenceExplanation> explanations) {
        return CoreBankingReconciliation.over(PERIOD, INR, engineLines, cbsLines, explanations);
    }

    @Nested
    @DisplayName("RC-1 over a population")
    class TheInvariant {

        @Test
        @DisplayName("a book billed at the presented figure ties, and says what it compared")
        void aCleanBookTies() {
            // Three contracts each accruing 9,629.2653, reduced to the billed 9,629.27 by
            // ContractualLegInterest.fromContractualLeg, against a CBS that billed 9,629.27 — the
            // LMS_AUTHORITATIVE shape ADR-0004 prefers. 3 x 9,629.27 = 28,887.81 on both sides,
            // by hand. Before the reduction moved to the adapter this read 28,887.80 against
            // 28,887.81 and tied only because the residual was rounded, which is the tolerance
            // 03 section 5.7 forbids.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2"), engine("A3")),
                List.of(cbs("A1", "9629.27"), cbs("A2", "9629.27"), cbs("A3", "9629.27")),
                List.of());

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.id()).isEqualTo(InvariantId.RC_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.detail())
                .contains("3 contracts tie for period 202705")
                .contains("INR 28887.81");
            assertThat(recon.contractsReconciled()).isEqualTo(3);
        }

        @Test
        @DisplayName("the deviation is money, and it is the total absolute residual")
        void deviationIsTotalAbsolute() {
            // The anti-netting case, and the reason the deviation is absolute rather than signed.
            // A2 is billed 0.03 low and A3 is billed 0.03 high:
            //   A2: 9,629.2653 - 9,629.24 = 0.0253 -> 0.03
            //   A3: 9,629.2653 - 9,629.30 = -0.0347 -> -0.03
            // A signed deviation reports 0.00 and the close proceeds with two live breaks. The
            // absolute total is 0.06, and the signed net is published separately because that is
            // the figure a control account carries.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2"), engine("A3")),
                List.of(cbs("A1", "9629.27"), cbs("A2", "9629.24"), cbs("A3", "9629.30")),
                List.of());

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("0.03 + 0.03 absolute, not 0.03 - 0.03")
                .isEqualByComparingTo("0.06");
            assertThat(recon.netUnexplainedDifference())
                .as("the control-account figure, published and deliberately not the deviation")
                .isEqualTo(Money.inr("0.00"));
            assertThat(result.detail())
                .contains("2 of 3 contracts do not tie")
                .contains("[A2, A3]");
        }

        @Test
        @DisplayName("one result, not one per contract")
        void oneResultForTheWholePopulation() {
            // InvariantResult.conjunction keeps only the first breach's deviation among results
            // sharing an id, so a result per contract would publish whichever break sorted first
            // and drop the rest of the book's disagreement. Asserted by aggregating the answer
            // rather than by counting results: the deviation has to be the whole book's.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2")),
                List.of(cbs("A1", "9629.24"), cbs("A2", "9629.24")),
                List.of());

            List<InvariantResult> collapsed = InvariantResult.oneResultPerInvariant(
                List.of(recon.tiesToCoreBanking()));
            assertThat(collapsed).hasSize(1);
            assertThat(collapsed.getFirst().deviation())
                .as("0.03 from each contract")
                .isEqualByComparingTo("0.06");
        }

        @Test
        @DisplayName("an explained difference does not breach")
        void explainedDoesNotBreach() {
            // "Zero unexplained" rather than zero. A2 is billed 12.88 low and a signed-off timing
            // explanation claims exactly that; 9,629.2653 - 9,616.3853 = 12.8800 by hand.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2")),
                List.of(cbs("A1", "9629.27"), cbs("A2", "9616.39")),
                List.of(timing("A2", "12.88")));

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("INR 12.88 is explained across 1 contracts");
            assertThat(recon.totalExplainedDifference()).isEqualTo(Money.inr("12.88"));
        }

        @Test
        @DisplayName("a claim short of the difference is partial attribution, not a misstatement")
        void shortClaimIsPartialAttribution() {
            // 12.88 of difference, 10.00 claimed, 2.88 left. The 2.88 stays in the deviation —
            // attaching a note to a contract does not reconcile it — but this is NOT the
            // "does not add up" finding. over()'s own javadoc says several explanations per
            // contract are normal, which makes partial coverage mid-close normal too: 10.00
            // attributed to timing with 2.88 still under investigation is the ordinary shape of a
            // close in flight. Reporting it under the aggregator's most escalatory heading, which
            // reads "the same preparer and checker are presumably applying the same method to
            // every other contract in the book", fired that escalation on routine work.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1")), List.of(cbs("A1", "9616.39")),
                List.of(timing("A1", "10.00")));

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo("2.88");
            assertThat(recon.misstatedExplanations())
                .as("short is not misstated")
                .isEmpty();
            assertThat(recon.lines().getFirst().isPartlyAttributed()).isTrue();
        }

        @Test
        @DisplayName("a claim that OVERSHOOTS the difference is its own finding")
        void overClaimIsAMisstatement() {
            // 12.88 of difference, 20.00 claimed. This cannot be a partial account of anything —
            // the preparer's method is wrong rather than incomplete, and that is the reading the
            // escalation was written for.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1")), List.of(cbs("A1", "9616.39")),
                List.of(timing("A1", "20.00")));

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("carry a signed-off explanation whose amount is not the difference");
            assertThat(recon.misstatedExplanations())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A1");
        }

        @Test
        @DisplayName("a claim pointing the other way is a misstatement too")
        void wrongDirectionIsAMisstatement() {
            // The engine is ABOVE the CBS by 12.88 and the claim says the CBS is above the engine.
            // Same magnitude class, opposite sign, and no amount of further attribution reaches
            // the difference from there.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1")), List.of(cbs("A1", "9616.39")),
                List.of(timing("A1", "-5.00")));

            assertThat(recon.misstatedExplanations())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A1");
        }

        @Test
        @DisplayName("an unapproved explanation leaves the whole difference, and the detail says so")
        void unapprovedExplanationLeavesItAll() {
            CoreBankingReconciliation recon = over(
                List.of(engine("A1")), List.of(cbs("A1", "9616.39")),
                List.of(DifferenceExplanation.prepared("A1", PERIOD, DifferenceReason.BILLING_DAY_TIMING,
                    Money.inr("12.88"), "CBS bills on the 5th.", "recon.preparer")));

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.deviation()).isEqualByComparingTo("12.88");
            assertThat(result.detail()).contains("not effective").contains("not approved");
            assertThat(recon.linesWithIneffectiveExplanations()).hasSize(1);
            assertThat(recon.misstatedExplanations())
                .as("nothing effective was offered, so this is not the 'does not add up' finding")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("a contract in one source only")
    class Presence {

        @Test
        @DisplayName("both directions are found, counted and diagnosed as a population question")
        void bothDirections() {
            // A1 ties. A2 is projected and absent from the feed; A3 is billed and not projected.
            // Each contributes 9,629.27 presented, so the absolute total is 19,258.54 and the net
            // is 0.00 — which is the second reason the deviation is not the net: two one-sided
            // contracts in opposite directions would otherwise cancel and the reconciliation would
            // pass with two whole accounts unreconciled.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2")),
                List.of(cbs("A1", "9629.27"), cbs("A3", "9629.27")),
                List.of());

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo("19258.54");
            assertThat(recon.netUnexplainedDifference()).isEqualTo(Money.inr("0.00"));
            assertThat(result.detail())
                .contains("2 are present in one source only")
                .contains("1 projected by the engine and absent from the feed")
                .contains("1 billed by the CBS and not projected");
            assertThat(recon.presenceBreaks())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A2", "A3");
        }

        @Test
        @DisplayName("the population is the union, so neither source can drop a contract alone")
        void thePopulationIsTheUnion() {
            // Where this differs from MigrationTracker. That class takes the population size as an
            // input because it has one presented set and no other evidence of coverage. A
            // reconciliation has two, and each is the other's evidence — so iterating the engine's
            // contracts and looking up the CBS is the one shape that must not be used, because it
            // reintroduces MigrationTracker's failure with the CBS side as the silent casualty.
            CoreBankingReconciliation recon = over(
                List.of(engine("A2")), List.of(cbs("A1", "9629.27")), List.of());

            assertThat(recon.contractsReconciled())
                .as("neither source presented both contracts; the union did")
                .isEqualTo(2);
            assertThat(recon.lines())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A1", "A2");
        }

        @Test
        @DisplayName("lines are in contract id order, so a replay reads the same way")
        void deterministicOrder() {
            // DT-1 byte-compares a replayed closed period. A HashMap iteration order would defeat
            // that, and the difference would show up as a diff in a report nobody changed.
            CoreBankingReconciliation recon = over(
                List.of(engine("Z9"), engine("A1"), engine("M5")),
                List.of(cbs("M5", "9629.27"), cbs("Z9", "9629.27"), cbs("A1", "9629.27")),
                List.of());

            assertThat(recon.lines())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A1", "M5", "Z9");
        }
    }

    @Nested
    @DisplayName("reading the right leg")
    class WhichLeg {

        @Test
        @DisplayName("the contractual leg ties; the EIR leg would break by the fee amortisation")
        void theEirLegIsTheMisWiring() {
            // The mis-wiring the named factories exist to prevent. Reference case 1 period 2:
            // 9,986.87 on the EIR leg against 9,629.27 on the contractual leg, a difference of
            // 357.60 at presented precision (the case quotes 357.61 as the fee amortised at
            // working precision). It arrives on every contract, every period, in the same
            // direction, at a size somebody could plausibly attribute to a convention difference —
            // so it has to be caught by construction rather than by a reviewer's eye.
            ContractualLegInterest right =
                ContractualLegInterest.fromContractualLeg("A1", PERIOD, contractualLeg(), 2);
            ContractualLegInterest wrong =
                ContractualLegInterest.fromContractualLeg("A1", PERIOD, eirLeg(), 2);

            assertThat(right.presentedContractualInterest()).isEqualTo(Money.inr("9629.27"));
            assertThat(wrong.presentedContractualInterest()).isEqualTo(Money.inr("9986.87"));

            assertThat(over(List.of(right), List.of(cbs("A1", "9629.27")), List.of())
                .tiesToCoreBanking().satisfied())
                .isTrue();
            InvariantResult misWired =
                over(List.of(wrong), List.of(cbs("A1", "9629.27")), List.of())
                    .tiesToCoreBanking();
            assertThat(misWired.satisfied()).isFalse();
            assertThat(misWired.deviation()).isEqualByComparingTo("357.60");
        }

        @Test
        @DisplayName("fromTwoLegs takes the contractual leg out of a paired result")
        void fromTwoLegsPicksTheContractualLeg() {
            TwoLegResult twoLeg = TwoLegResult.reconcile(
                eirLeg(), contractualLeg(),
                Rate.monthly(new BigDecimal("0.0104214918")),
                Rate.monthly(new BigDecimal("0.01")),
                Money.inr("5000.00"));

            assertThat(ContractualLegInterest.fromTwoLegs("A1", PERIOD, twoLeg, 2)
                .contractualInterest().amount())
                .as("the contractual leg's 9,629.2653 at the scale it was billed, 9,629.27 —"
                    + " and emphatically not the EIR leg's 9,986.87, which is what this test is"
                    + " for. The reduction happens in the adapter; read its javadoc for why it"
                    + " cannot happen in the comparison.")
                .isEqualByComparingTo("9629.27");
        }
    }

    @Nested
    @DisplayName("what the aggregator refuses, and what it reports instead")
    class CallerDefects {

        @Test
        @DisplayName("two engine lines for one contract is not a position")
        void duplicateEngineLine() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> over(
                    List.of(engine("A1"), engine("A1")), List.of(cbs("A1", "9629.27")), List.of()))
                .withMessageContaining("presented contract A1 twice");
        }

        @Test
        @DisplayName("a duplicate across two CBS extracts names both extracts")
        void duplicateCbsLine() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> over(
                    List.of(engine("A1")),
                    List.of(cbs("A1", "9629.27"),
                        new CbsBilledInterest("A1", PERIOD, Money.inr("9629.27"), "CBS-FIX-01")),
                    List.of()))
                .withMessageContaining("CBS-FIX-01");
        }

        @Test
        @DisplayName("a line from another period is refused, not differenced")
        void wrongPeriod() {
            // TwoLegResult.reconcile refuses to pair legs spanning different periods for the same
            // reason: the comparison produces a difference that looks plausible and reconciles to
            // nothing.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> over(
                    List.of(ContractualLegInterest.fromContractualLeg(
                        "A1", 202704, contractualLeg(), 2)),
                    List.of(cbs("A1", "9629.27")), List.of()))
                .withMessageContaining("is for period 202704, not 202705");
        }

        @Test
        @DisplayName("a line in another currency is refused; a cross-currency deviation is unusable")
        void wrongCurrency() {
            Currency usd = Currency.getInstance("USD");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> over(
                    List.of(engine("A1")),
                    List.of(new CbsBilledInterest(
                        "A1", PERIOD, Money.of("9629.27", usd), FEED)),
                    List.of()))
                .withMessageContaining("summed across");
        }

        @Test
        @DisplayName("a period id of 202713 is refused before it silently joins nothing")
        void impossiblePeriodId() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CbsBilledInterest("A1", 202713, Money.inr("1.00"), FEED))
                .withMessageContaining("YYYYMM");
        }

        @Test
        @DisplayName("a CBS line with no feed reference is refused")
        void missingFeedReference() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CbsBilledInterest("A1", PERIOD, Money.inr("1.00"), "  "))
                .withMessageContaining("feedReference");
        }

        @Test
        @DisplayName("an explanation for a contract neither source presented is reported, not thrown")
        void danglingExplanation() {
            // The usual cause is an explanation carried forward from last period on an account that
            // has since run off. It cannot move the money — there is no difference for it to
            // reduce — so it is published as data and named in the detail on a pass as well as on a
            // fail, in the shape MigrationTracker publishes its programme figure.
            CoreBankingReconciliation recon = over(
                List.of(engine("A1")), List.of(cbs("A1", "9629.27")),
                List.of(timing("GONE-9", "12.88")));

            InvariantResult result = recon.tiesToCoreBanking();
            assertThat(result.satisfied())
                .as("a stale explanation is a hygiene signal, not a money difference")
                .isTrue();
            assertThat(result.detail())
                .contains("1 explanations name a contract neither source presented");
            assertThat(recon.danglingExplanations()).hasSize(1);
            assertThat(recon.contractsReconciled()).isEqualTo(1);
        }

        @Test
        @DisplayName("the report accessors resolve a named contract and describe the position")
        void reportAccessors() {
            CoreBankingReconciliation recon = over(
                List.of(engine("A1"), engine("A2")),
                List.of(cbs("A1", "9629.27"), cbs("A2", "9616.39")),
                List.of(timing("A2", "10.00")));

            assertThat(recon.lineFor("A2")).isNotNull();
            assertThat(recon.lineFor("A2").unexplainedDifference()).isEqualTo(Money.inr("2.88"));
            assertThat(recon.lineFor("NOT-PRESENTED")).isNull();
            assertThat(recon.explainedContracts())
                .extracting(ContractReconciliation::contractId)
                .containsExactly("A2");
            assertThat(recon.periodId()).isEqualTo(PERIOD);
            assertThat(recon.currency()).isEqualTo(INR);
            assertThat(recon.describe())
                .contains("period 202705: 2 contracts")
                .contains("unexplained INR 2.88 absolute over 1 contracts");
        }

        @Test
        @DisplayName("an empty period reconciles, and says that it compared nothing")
        void emptyPopulation() {
            // Honest rather than reassuring. Two truncated feeds are the one population failure
            // RC-1 cannot see — a contract absent from both sources has no money to disagree by —
            // and that is close gate 1 of 07 section 4.3, feed completeness, not this control.
            CoreBankingReconciliation recon = over(List.of(), List.of(), List.of());

            assertThat(recon.tiesToCoreBanking().satisfied()).isTrue();
            assertThat(recon.tiesToCoreBanking().detail()).contains("0 contracts tie");
            assertThat(recon.contractsReconciled()).isZero();
            assertThat(recon.describe()).contains("0 contracts");
        }
    }
}
