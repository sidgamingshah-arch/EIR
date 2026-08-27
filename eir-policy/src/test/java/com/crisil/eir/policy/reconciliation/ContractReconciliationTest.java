package com.crisil.eir.policy.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.approval.ApprovalRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One contract's C-14 line: the difference, what an explanation is entitled to reduce, and what is
 * left (FR-804, invariant RC-1).
 *
 * <p><b>Where the figures come from.</b> Every engine-side amount here is
 * <a href="../../../../../../../../../docs/reference-cases/case-01-emi-loan-with-fees.md">reference
 * case 1</a>'s published contractual-interest column — period 1 10,000.00, period 2 9,629.27,
 * period 3 9,254.82 — a golden fixture generated independently of this engine. The working-precision
 * values behind them were re-derived by hand from the case's terms (1,000,000.00 at 1.00% per month
 * against a billed EMI of 47,073.47) and cross-checked in {@code python3}:
 *
 * <pre>
 *   period 1: 1,000,000.0000 x 0.01 = 10,000.0000    closing   962,926.5300
 *   period 2:   962,926.5300 x 0.01 =  9,629.2653    closing   925,482.3253
 *   period 3:   925,482.3253 x 0.01 =  9,254.823253  closing   887,663.678553
 * </pre>
 *
 * <p>Nothing below takes an expected value from running the code under test. Each CBS-side figure is
 * the engine figure less a difference chosen in advance, and each expected residual is that
 * difference less the explanation amounts, computed by hand.
 */
class ContractReconciliationTest {

    private static final int PERIOD = 202705;

    /**
     * Reference case 1 period 2 contractual interest, at the scale it was billed.
     *
     * <p>The engine's contractual leg carries {@code 9,629.2653} at working precision;
     * {@code ContractualLegInterest.fromContractualLeg} reduces it to {@code 9,629.27} because
     * that is what the borrower was billed and ADR-0004 makes the CBS the book of record for
     * billing. This constant is the adapter's output, which is what a caller reconciling against a
     * CBS feed actually holds — see {@link #workingPrecisionSuppliedDirectlyIsTakenAtItsWord} for
     * the other path.
     */
    private static final Money ENGINE_PERIOD_2 = Money.inr("9629.27");

    /** The same figure before the adapter reduces it. */
    private static final Money ENGINE_PERIOD_2_WORKING = Money.inr("9629.2653");

    private static final LocalDate CHECKED_ON = LocalDate.of(2027, 5, 4);

    private static ApprovalRecord approvedBy(String checker) {
        return ApprovalRecord.by(checker, CHECKED_ON);
    }

    private static DifferenceExplanation timing(String contractId, String amount) {
        return DifferenceExplanation.approved(
            contractId, PERIOD, DifferenceReason.BILLING_DAY_TIMING, Money.inr(amount),
            "CBS bills on the 5th; the accrual runs to month-end. Reverses in 202706.",
            "recon.preparer", approvedBy("recon.checker"));
    }

    @Nested
    @DisplayName("the difference, and the one reduction that produces it")
    class TheDifference {

        @Test
        @DisplayName("a CBS figure equal to the engine's presented figure ties")
        void presentedFigureTies() {
            // The everyday case under LMS_AUTHORITATIVE (ADR-0004). The engine's 9,629.2653 was
            // reduced to the billed 9,629.27 by the adapter, and the CBS billed 9,629.27, so this
            // is exact arithmetic on two figures at the same scale. It must tie, or C-14 raises a
            // break on every contract in the book every period — the "control becomes noise"
            // outcome ADR-0004 warns about — and it ties because a RULE accounted for the
            // difference at the boundary, not because a tolerance absorbed it after the fact.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-1", ENGINE_PERIOD_2, Money.inr("9629.27"), List.of());

            assertThat(line.difference().amount())
                .as("9,629.27 - 9,629.27, exactly")
                .isEqualByComparingTo("0");
            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("0.00"));
            assertThat(line.isTied()).isTrue();
            assertThat(line.presence()).isEqualTo(SourcePresence.BOTH);
        }

        @Test
        @DisplayName("a working-precision figure supplied directly is taken at its word")
        void workingPrecisionSuppliedDirectlyIsTakenAtItsWord() {
            // The path the adapter does not sit on. A caller using the canonical constructor is
            // stating that the amount it holds is what was billed, and this class does not
            // second-guess it — so an unreduced 9,629.2653 against a billed 9,629.27 is a real
            // difference of -0.0047 and is reported at that size.
            //
            // This is the case the old per-line rounding hid, and hiding it is what made the
            // tolerance dangerous: the same 0.005 window that absorbed a harmless scale artefact
            // would have absorbed a genuine 0.004 break on a figure already at billing scale.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-1W", ENGINE_PERIOD_2_WORKING, Money.inr("9629.27"), List.of());

            assertThat(line.difference().amount()).isEqualByComparingTo("-0.0047");
            assertThat(line.unexplainedDifference().amount()).isEqualByComparingTo("-0.0047");
            assertThat(line.isTied())
                .as("reported, not absorbed; the rule belongs at the adapter, and this caller"
                    + " bypassed it")
                .isFalse();
        }

        @Test
        @DisplayName("the residual is reported at its own size, never rounded away")
        void residualIsReportedAtItsOwnSize() {
            // Section 1.3's rule, and InvariantResult.ofMoney's. Constructed so the two orders
            // disagree: the difference is 9,629.2653 - 9,629.2603 = 0.0050 and the explanation
            // claims 0.0040, so
            //   reduce once:  round(0.0050 - 0.0040) = round(0.0010) = 0.00  -> ties
            //   round both:   round(0.0050) - round(0.0040) = 0.01 - 0.00 = 0.01 -> a break
            // Rounding both operands rounds twice, and the error that admits has no floor: this
            // contract's explanation is right to a tenth of a paise and the second order reports a
            // control exception on it.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-2", ENGINE_PERIOD_2_WORKING, Money.inr("9629.2603"),
                List.of(timing("ACC-2", "0.0040")));

            assertThat(line.difference().amount()).isEqualByComparingTo("0.0050");
            assertThat(line.explainedAmount().amount()).isEqualByComparingTo("0.0040");
            assertThat(line.unexplainedDifference().amount())
                .as("0.0010 of genuine unexplained difference, reported at its own size. This"
                    + " assertion previously expected 0.00, because the residual was reduced to"
                    + " presentation scale before being tested — a tolerance of half a paise per"
                    + " contract, which 03 section 5.7 forbids by name and which erased 0.80 of"
                    + " real difference across 200 accounts each out by 0.004.")
                .isEqualByComparingTo("0.0010");
            assertThat(line.isTied())
                .as("and it does not tie: a rule accounts for the billing-scale difference at the"
                    + " adapter, so anything left here is real")
                .isFalse();
        }

        @Test
        @DisplayName("a real difference with nothing attached is unexplained in full")
        void unexplainedInFull() {
            // 9,629.2653 - 9,616.3853 = 12.8800, chosen in advance.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-3", ENGINE_PERIOD_2, Money.inr("9616.39"), List.of());

            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("12.88"));
            assertThat(line.isTied()).isFalse();
            assertThat(line.hasMisstatedExplanation())
                .as("nobody attached anything, so nothing is misstated — a different finding")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("an explanation is a quantity that has to add up, not a note")
    class Explanations {

        @Test
        @DisplayName("an explanation equal to the difference ties it")
        void exactExplanationTies() {
            ContractReconciliation line = new ContractReconciliation(
                "ACC-4", ENGINE_PERIOD_2, Money.inr("9616.39"),
                List.of(timing("ACC-4", "12.88")));

            assertThat(line.isTied()).isTrue();
            assertThat(line.explainedAmount().amount()).isEqualByComparingTo("12.88");
            assertThat(line.hasMisstatedExplanation()).isFalse();
        }

        @Test
        @DisplayName("two explanations may share one difference")
        void severalExplanationsSum() {
            // 10.00 of timing plus 2.88 of fee classification against a difference of 12.88. Both
            // reasons genuinely applying to one contract is ordinary, which is why the aggregator
            // accepts several explanations per contract rather than one.
            DifferenceExplanation fee = DifferenceExplanation.approved(
                "ACC-5", PERIOD, DifferenceReason.INTEGRAL_FEE_BILLED_AS_INTEREST, Money.inr("2.88"),
                "Processing fee tranche booked to interest in the CBS; held integral under"
                    + " ACPIR 52 and reconciled to fee_posting FP-88431.",
                "recon.preparer", approvedBy("recon.checker"));
            ContractReconciliation line = new ContractReconciliation(
                "ACC-5", ENGINE_PERIOD_2, Money.inr("9616.39"),
                List.of(timing("ACC-5", "10.00"), fee));

            assertThat(line.explainedAmount().amount()).isEqualByComparingTo("12.88");
            assertThat(line.isTied()).isTrue();
        }

        @Test
        @DisplayName("an explanation that claims too little leaves the shortfall, and is flagged")
        void shortExplanationLeavesTheShortfall() {
            // The abuse this arithmetic exists to prevent: attach a note and the difference is
            // "explained". 9,629.27 - 9,616.39 = 12.88 of difference against 10.00 claimed leaves
            // 2.88, by hand. The shortfall stays in the deviation.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-6", ENGINE_PERIOD_2, Money.inr("9616.39"),
                List.of(timing("ACC-6", "10.00")));

            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("2.88"));
            assertThat(line.isTied()).isFalse();
            assertThat(line.hasMisstatedExplanation())
                .as("short is PARTIAL ATTRIBUTION, not a misstatement. Several explanations per"
                    + " contract are normal, so a claim accounting for part of a difference with"
                    + " the rest under investigation is the ordinary mid-close shape — and the"
                    + " heading it used to trigger says the preparer's method is wrong on every"
                    + " other contract in the book too.")
                .isFalse();
            assertThat(line.isPartlyAttributed()).isTrue();
            assertThat(line.describe()).doesNotContain("EXPLANATION DOES NOT ADD UP");
        }

        @Test
        @DisplayName("an explanation claiming more than the difference leaves the excess")
        void overClaimLeavesTheExcess() {
            // 12.88 of difference against 20.00 claimed leaves -7.12. Over-claiming is not
            // conservative: it means the stated cause is not the cause.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-7", ENGINE_PERIOD_2, Money.inr("9616.39"),
                List.of(timing("ACC-7", "20.00")));

            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("-7.12"));
            assertThat(line.hasMisstatedExplanation()).isTrue();
        }

        @Test
        @DisplayName("the right magnitude in the wrong direction leaves twice the difference")
        void wrongSignDoublesIt() {
            // 12.88 - (-12.88) = 25.76 by hand. The reason the arithmetic subtracts a signed
            // amount rather than comparing magnitudes: an explanation offered in the wrong
            // direction is a misunderstanding of which system is high, and must not match.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-8", ENGINE_PERIOD_2, Money.inr("9616.39"),
                List.of(timing("ACC-8", "-12.88")));

            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("25.76"));
            assertThat(line.hasMisstatedExplanation()).isTrue();
        }
    }

    @Nested
    @DisplayName("an explanation nobody signed explains nothing")
    class Effectiveness {

        private static final Money CBS = Money.inr("9616.39");

        @Test
        @DisplayName("prepared and unapproved: the difference stands")
        void unapprovedIsIneffective() {
            // 07 section 4.2 lists exception acceptances that permit a close among the artefacts
            // needing a maker, a checker and a date. RC-1 gates the close (07 section 4.3 gate 4),
            // so an explanation is one of those acceptances.
            DifferenceExplanation prepared = DifferenceExplanation.prepared(
                "ACC-9", PERIOD, DifferenceReason.BILLING_DAY_TIMING, Money.inr("12.88"),
                "CBS bills on the 5th.", "recon.preparer");
            ContractReconciliation line = new ContractReconciliation(
                "ACC-9", ENGINE_PERIOD_2, CBS, List.of(prepared));

            assertThat(prepared.isEffective()).isFalse();
            assertThat(prepared.ineffectiveBecause()).contains("not approved");
            assertThat(line.explainedAmount().isZero()).isTrue();
            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("12.88"));
            assertThat(line.hasMisstatedExplanation())
                .as("nothing effective was offered, so this is 'not yet done', not 'does not add up'")
                .isFalse();
            assertThat(line.ineffectiveExplanations()).containsExactly(prepared);
        }

        @Test
        @DisplayName("self-approved through a case and whitespace variant: still self-approved")
        void selfApprovalIsIneffective() {
            // FourEyes, not a fourth local copy of the rule. ApprovalRecord's javadoc records that
            // the plausible route in a bank is not somebody typing their own name but a whitespace
            // or case variant of the same directory identity arriving through a second channel —
            // and an explanation row uploaded from a close spreadsheet is that second channel.
            DifferenceExplanation selfApproved = new DifferenceExplanation(
                "ACC-10", PERIOD, DifferenceReason.BILLING_DAY_TIMING, Money.inr("12.88"),
                "CBS bills on the 5th.", "Recon.Preparer", approvedBy(" recon.preparer "));
            ContractReconciliation line = new ContractReconciliation(
                "ACC-10", ENGINE_PERIOD_2, CBS, List.of(selfApproved));

            assertThat(selfApproved.isSelfApproved()).isTrue();
            assertThat(selfApproved.isEffective()).isFalse();
            assertThat(selfApproved.ineffectiveBecause()).contains("self-approved");
            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("12.88"));
        }

        @Test
        @DisplayName("a reason code with no narrative is a category, not an explanation")
        void blankNarrativeIsIneffective() {
            DifferenceExplanation noNarrative = new DifferenceExplanation(
                "ACC-11", PERIOD, DifferenceReason.ROUNDING_CONVENTION, Money.inr("12.88"),
                "   ", "recon.preparer", approvedBy("recon.checker"));

            assertThat(noNarrative.isEffective()).isFalse();
            assertThat(noNarrative.ineffectiveBecause()).contains("no narrative");
            assertThat(new ContractReconciliation("ACC-11", ENGINE_PERIOD_2, CBS,
                List.of(noNarrative)).unexplainedDifference())
                .isEqualTo(Money.inr("12.88"));
        }

        @Test
        @DisplayName("no preparer named is ineffective")
        void noPreparerIsIneffective() {
            DifferenceExplanation anonymous = new DifferenceExplanation(
                "ACC-12", PERIOD, DifferenceReason.ROUNDING_CONVENTION, Money.inr("12.88"),
                "Paise rounding per instalment component.", null, approvedBy("recon.checker"));

            assertThat(anonymous.statedBy()).isEmpty();
            assertThat(anonymous.isEffective()).isFalse();
            assertThat(anonymous.ineffectiveBecause()).contains("no preparer");
        }

        @Test
        @DisplayName("none of these is refused at construction, because RC-1 has to see them")
        void badExplanationsAreConstructible() {
            // The corollary of the project's signature defect: a guard here would be the guard the
            // invariant asserts, and the control would become a tautology. JournalEntry allows an
            // unbalanced entry to be constructed so SL-2 has something to detect; the same choice.
            assertThat(DifferenceExplanation.prepared("A", PERIOD,
                DifferenceReason.BILLING_DAY_TIMING, Money.inr("1.00"), "", "").isEffective())
                .isFalse();
            assertThat(new DifferenceExplanation("A", PERIOD, DifferenceReason.BILLING_DAY_TIMING,
                Money.inr("1.00"), "n", "p", approvedBy("p")).isEffective())
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the reason vocabulary")
    class Reasons {

        @Test
        @DisplayName("every reason states its cause in a sentence a reviewer can act on")
        void everyReasonStatesItself() {
            // The detail on an InvariantResult is read by whoever is clearing the break, and
            // INTEGRAL_FEE_BILLED_AS_INTEREST is a constant name rather than a sentence. Asserted
            // over the whole enum so a fifth member cannot be added without one.
            for (DifferenceReason reason : DifferenceReason.values()) {
                assertThat(reason.statement())
                    .as("%s", reason)
                    .isNotBlank()
                    .doesNotContain("_");
            }
        }

        @Test
        @DisplayName("only a timing difference is expected to reverse")
        void onlyTimingReverses() {
            // The one property of a reason a reviewer can act on without opening the contract. A
            // fee billed monthly recurs forever and that is correct; a timing difference recurring
            // with the same sign forever means the accrual boundary is wrong and the label is
            // hiding it.
            assertThat(DifferenceReason.BILLING_DAY_TIMING.expectedToReverse()).isTrue();
            assertThat(DifferenceReason.INTEGRAL_FEE_BILLED_AS_INTEREST.expectedToReverse())
                .isFalse();
            assertThat(DifferenceReason.ROUNDING_CONVENTION.expectedToReverse()).isFalse();
            assertThat(DifferenceReason.PENAL_CHARGE_EXCLUDED_BY_DIRECTION.expectedToReverse())
                .isFalse();
        }

        @Test
        @DisplayName("there is no catch-all reason")
        void noCatchAll() {
            // The boundary of what "explained" means. A catch-all would move it to wherever the
            // operator's imagination reaches, and RC-1 would become a record of how many sentences
            // were typed.
            assertThat(DifferenceReason.values()).hasSize(4);
            assertThat(DifferenceReason.values())
                .extracting(Enum::name)
                .noneMatch(name -> name.contains("OTHER") || name.contains("MISC"));
        }

        @Test
        @DisplayName("an explanation describes itself, and says when it is ineffective")
        void describeNamesTheDefect() {
            DifferenceExplanation good = timing("ACC-17", "12.88");
            assertThat(good.describe())
                .contains("ACC-17: INR 12.88")
                .contains("timing: the CBS bills on a different day")
                .contains("approved by recon.checker on 2027-05-04");

            DifferenceExplanation bad = DifferenceExplanation.prepared(
                "ACC-18", PERIOD, DifferenceReason.ROUNDING_CONVENTION, Money.inr("0.03"),
                "Paise per instalment component.", "recon.preparer");
            assertThat(bad.describe()).contains("INEFFECTIVE").contains("not approved");
        }
    }

    @Nested
    @DisplayName("a contract in one source only is not a zero difference")
    class Presence {

        @Test
        @DisplayName("engine only: the whole projected amount is unexplained")
        void engineOnly() {
            // The failure mode this exists to prevent: iterate the engine's contracts, look up the
            // CBS figure, and on a miss either skip the contract or substitute zero — both of which
            // report a line that ties. Here the answer to "by how much do the two systems disagree
            // about this account" is: by all of it.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-13", ENGINE_PERIOD_2, null, List.of());

            assertThat(line.presence()).isEqualTo(SourcePresence.ENGINE_ONLY);
            assertThat(line.presence().isOneSided()).isTrue();
            assertThat(line.unexplainedDifference())
                .as("the whole billed figure, because the CBS presented nothing to net against")
                .isEqualTo(Money.inr("9629.27"));
            assertThat(line.describe()).contains("CBS (absent)");
        }

        @Test
        @DisplayName("CBS only: interest billed on an exposure the engine is not measuring")
        void cbsOnly() {
            ContractReconciliation line = new ContractReconciliation(
                "ACC-14", null, Money.inr("9629.27"), List.of());

            assertThat(line.presence()).isEqualTo(SourcePresence.CBS_ONLY);
            assertThat(line.unexplainedDifference()).isEqualTo(Money.inr("-9629.27"));
            assertThat(line.currency()).isEqualTo(Money.INR);
        }

        @Test
        @DisplayName("a one-sided contract can be explained, and stays in the presence report")
        void aClosedAccountIsExplicable() {
            // An account closed in the CBS mid-period while the engine still holds a live schedule
            // is a genuine timing difference, and refusing to let it be explained would leave a
            // permanent red control — which is the state MigrationTracker's javadoc says gets
            // suppressed. So it can tie on the money, and it still appears in presenceBreaks()
            // because the diagnosis is about the feed rather than the contract.
            ContractReconciliation line = new ContractReconciliation(
                "ACC-15", ENGINE_PERIOD_2, null, List.of(timing("ACC-15", "9629.27")));

            assertThat(line.isTied()).isTrue();
            assertThat(line.presence().isOneSided()).isTrue();
        }

        @Test
        @DisplayName("a line neither source presented is not a line")
        void neitherSourceIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractReconciliation("ACC-16", null, null, List.of()))
                .withMessageContaining("presented by neither source");
        }
    }
}
