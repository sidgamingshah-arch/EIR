package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CASE1_EIR;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL_BALANCE_AT_MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.GCA_AT_MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.MONTHLY;
import static com.crisil.eir.calc.amort.AmortFixtures.NET_INTEGRAL_FEE;
import static com.crisil.eir.calc.amort.AmortFixtures.PRINCIPAL;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static com.crisil.eir.calc.amort.AmortFixtures.case1ContractualLeg;
import static com.crisil.eir.calc.amort.AmortFixtures.case1EirLeg;
import static com.crisil.eir.calc.amort.AmortFixtures.case1TwoLeg;
import static com.crisil.eir.calc.amort.AmortFixtures.inceptionVector;
import static com.crisil.eir.calc.amort.AmortFixtures.solveAtInception;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The two-leg reconciliation of ADR-0004 on the reference case 1 figures, and the
 * four invariants it asserts (specification 9).
 *
 * <p>The point of the artefact is that nothing in it is accumulated. The unamortised
 * fee is <em>defined</em> as the difference between the two carrying amounts and the
 * fee recognised is <em>defined</em> as the difference between the two interest
 * figures, so neither can drift from the ledgers it describes. Two of the tests here
 * exist only to make that concrete: they build the accumulator the engine refuses to
 * keep and show it drifting, once from rounding over a life and once from a single
 * event, while the derived figure stays right.
 */
class TwoLegReconciliationTest {

    @Test
    @DisplayName("INV-1: 134,763.28 of EIR interest = 129,763.28 contractual + 5,000.00 net fee")
    void lifetimeInterestIsUnchangedInTotal() {
        TwoLegResult two = case1TwoLeg();

        assertThat(two.totalEirInterest().atPresentationScale().amount()).isEqualByComparingTo(bd("134763.28"));
        assertThat(two.contractualInterestBilled().atPresentationScale().amount())
            .isEqualByComparingTo(bd("129763.28"));
        assertThat(two.netFeeRecognised().atPresentationScale().amount()).isEqualByComparingTo(bd("5000.00"));

        // The EIR method changes the timing of recognition and never the total, which is
        // the whole of INV-1 read as a subtraction.
        assertThat(invariant(two, InvariantId.INV_1).satisfied()).isTrue();
        assertThat(two.isClean()).isTrue();
        assertThat(two.orThrow()).isSameAs(two);
    }

    @Test
    @DisplayName("INV-1 is stated on the billed basis, and the accrued basis differs by the residue")
    void billedAndAccruedBasesAreBothRightAndNotInterchangeable() {
        TwoLegResult two = case1TwoLeg();

        // Cash received less principal advanced: 129,763.28. What the leg accrued:
        // 129,763.34. The 0.06 gap is interest the billed schedule never collected, and a
        // reconciliation that mixes the two bases is short by exactly that.
        assertThat(two.contractualInterestBilled().atPresentationScale().amount())
            .isEqualByComparingTo(bd("129763.28"));
        assertThat(two.totalContractualInterestAccrued().atPresentationScale().amount())
            .isEqualByComparingTo(bd("129763.34"));
        assertThat(two.totalFeeAmortisedOnAccruedBasis().atPresentationScale().amount())
            .isEqualByComparingTo(bd("4999.94"));
        assertThat(two.contractualResidue().atPresentationScale().amount()).isEqualByComparingTo(bd("0.06"));

        // INV-3 accounts for the residue rather than absorbing it.
        assertThat(invariant(two, InvariantId.INV_3).satisfied()).isTrue();
    }

    @ParameterizedTest(name = "period {0}: EIR {1} - contractual {2} = fee {3}, c/f {4}")
    @CsvSource({
        "1,  10369.38, 10000.00, 369.38, 4630.62",
        "2,   9986.87,  9629.27, 357.61, 4273.01",
        "3,   9600.38,  9254.82, 345.55, 3927.46",
        "6,   8416.55,  8108.88, 307.67, 2965.99",
        "12,  5935.51,  5711.77, 223.74, 1408.29",
        "18,  3295.24,  3167.20, 128.05,  394.79",
        "23,    966.02,  927.53,  38.49,   19.50",
        "24,    485.52,  466.07,  19.44,    0.06",
    })
    @DisplayName("the case 1 fee amortisation profile reproduces period by period")
    void feeProfile(int period, String eirInterest, String contractualInterest, String fee, String carried) {
        TwoLegRow row = case1TwoLeg().rows().get(period - 1);

        assertThat(row.presentedEirInterest().amount()).isEqualByComparingTo(bd(eirInterest));
        assertThat(row.presentedContractualInterest().amount()).isEqualByComparingTo(bd(contractualInterest));
        assertThat(row.presentedFeeAmortised().amount()).isEqualByComparingTo(bd(fee));
        assertThat(row.presentedUnamortisedFee().amount()).isEqualByComparingTo(bd(carried));
    }

    @Test
    @DisplayName("month 1 fee amortised is 369.38, and it is EIR interest less contractual interest")
    void firstMonthFee() {
        TwoLegRow row = case1TwoLeg().rows().getFirst();

        assertThat(row.presentedFeeAmortised().amount()).isEqualByComparingTo(bd("369.38"));
        // Derived, not stored: the same figure computed from the row's own two legs.
        assertThat(row.feeAmortised().amount())
            .isEqualByComparingTo(row.eirInterest().minus(row.contractualInterest()).amount());
        // And the same figure again as the movement in the derived balance, because both
        // legs subtract identical cash.
        assertThat(row.openingUnamortisedFee().minus(row.unamortisedFee()).amount())
            .isEqualByComparingTo(row.feeAmortised().amount());
    }

    @Test
    @DisplayName("INV-4 at month 12: 529,815.61 - 528,407.32 = 1,408.29, derived from the legs")
    void monthTwelveUnamortisedFeeIsTheLegDifference() {
        TwoLegResult two = case1TwoLeg();
        TwoLegRow month12 = two.rows().get(11);

        assertThat(month12.contractualCarryingAmount().atPresentationScale().amount())
            .isEqualByComparingTo(CONTRACTUAL_BALANCE_AT_MONTH_12.amount());
        assertThat(month12.eirCarryingAmount().atPresentationScale().amount())
            .isEqualByComparingTo(GCA_AT_MONTH_12.amount());
        assertThat(two.presentedUnamortisedFeeAt(12).amount()).isEqualByComparingTo(bd("1408.29"));

        // Not "computed and then checked against a stored balance" — there is no stored
        // balance. The published figure is the working difference rounded once, which at
        // period 23 is 19.50 while differencing the two published balances gives 19.51.
        assertThat(two.presentedUnamortisedFeeAt(23).amount()).isEqualByComparingTo(bd("19.50"));
        TwoLegRow month23 = two.rows().get(22);
        assertThat(month23.contractualCarryingAmount().atPresentationScale()
            .minus(month23.eirCarryingAmount().atPresentationScale()).amount())
            .isEqualByComparingTo(bd("19.51"));
        assertThat(invariant(two, InvariantId.INV_4).satisfied()).isTrue();
    }

    @Test
    @DisplayName("an independent fee accumulator drifts on rounding alone; the derivation does not")
    void anAccumulatorDriftsFromRoundingAndTheDerivationCannot() {
        TwoLegResult two = case1TwoLeg();

        // The accumulator a naive ledger keeps: open at the net fee, subtract each
        // period's fee recognised as published. Each subtraction is a correct rounding;
        // the sum of them is not the rounding of the sum.
        Money accumulator = NET_INTEGRAL_FEE;
        for (TwoLegRow row : two.rows()) {
            accumulator = accumulator.minus(row.presentedFeeAmortised());
        }

        assertThat(accumulator.amount()).isEqualByComparingTo(bd("0.07"));
        assertThat(two.presentedUnamortisedFeeAt(24).amount()).isEqualByComparingTo(bd("0.06"));

        // A paise, on a 24-period retail loan, from nothing but rounding. The derived
        // balance is the contractual leg less the EIR leg and agrees with both ledgers by
        // construction; the accumulator now agrees with neither, and there is no single
        // period to point at as the cause. Over 360 periods it does worse.
        assertThat(accumulator.minus(two.unamortisedFeeAt(24)).atPresentationScale().amount())
            .isEqualByComparingTo(bd("0.01"));
        assertThat(two.unamortisedFeeAt(24).amount()).isEqualByComparingTo(
            two.rows().getLast().contractualCarryingAmount()
                .minus(two.rows().getLast().eirCarryingAmount()).amount());
    }

    @Test
    @DisplayName("a catch-up moves a balance without moving cash, and only the derived fee follows it")
    void anAccumulatorDriftsByTheWholeCatchUpAndTheDerivationFollowsTheLedger() {
        // Reference case 3 restates the EIR leg at month 12 from 528,407.32 to 527,779.90
        // — a 627.42 charge, with no cash moving and the contractual leg untouched at
        // 529,815.61 because the borrower still owes what the borrower owed.
        CatchUpResult restatement = CatchUpCalculator.restate(
            CASE1_EIR, GCA_AT_MONTH_12, AmortFixtures.case3RevisedFlows(), MONTHLY);
        assertThat(restatement.presentedCatchUp().amount()).isEqualByComparingTo(bd("-627.42"));

        Money derivedBefore = CONTRACTUAL_BALANCE_AT_MONTH_12.minus(GCA_AT_MONTH_12);
        Money derivedAfter = CONTRACTUAL_BALANCE_AT_MONTH_12.minus(restatement.restatedGca());

        assertThat(derivedBefore.atPresentationScale().amount()).isEqualByComparingTo(bd("1408.29"));
        assertThat(derivedAfter.atPresentationScale().amount()).isEqualByComparingTo(bd("2035.71"));

        // This is the failure mode the derivation exists to make impossible. An
        // accumulator carrying 1,408.29 has no reason to move: no cash was received and no
        // fee was recognised. It therefore drifts by the entire catch-up on the day of the
        // event, and permanently, with the two legs it claims to reconcile sitting right
        // there disagreeing with it.
        Money accumulator = derivedBefore;
        assertThat(derivedAfter.minus(accumulator).atPresentationScale().amount())
            .isEqualByComparingTo(bd("627.42"));
        assertThat(restatement.catchUp().negate().atPresentationScale().amount())
            .isEqualByComparingTo(derivedAfter.minus(accumulator).atPresentationScale().amount());
    }

    @Test
    @DisplayName("INV-2: the EIR exceeds the contractual rate because the net fee is income")
    void feeIncomeLiftsTheYield() {
        TwoLegResult two = case1TwoLeg();

        // 13.248094% against 12.682503% — 56.6 basis points for 5,000 of net fee. The
        // asset is recorded below par, so it must accrete back up.
        assertThat(CASE1_EIR.effectiveAnnual()).isGreaterThan(CONTRACTUAL.effectiveAnnual());
        assertThat(two.openingEirCarryingAmount().amount()).isEqualByComparingTo(bd("995000"));
        assertThat(two.principalAdvanced().amount()).isEqualByComparingTo(PRINCIPAL.amount());
        assertThat(invariant(two, InvariantId.INV_2).satisfied()).isTrue();
    }

    @Test
    @DisplayName("INV-2 mirrored: a net integral cost puts the EIR below the contractual rate")
    void feeCostDepressesTheYield() {
        // The same loan with the fee sign reversed: 5,000 of net integral cost rather than
        // income, so the asset is recorded above par at 1,005,000 and must accrete down.
        // The rate is solved rather than quoted, because a mirrored case has no published
        // figure — what is asserted is the ordering, not a digit.
        FlowVector mirrored = inceptionVector(AmortFixtures.EMI, 24, Money.inr("-5000"));
        Money openingGca = Money.inr("1005000");
        Rate mirroredEir = solveAtInception(mirrored);

        AmortisationResult eirLeg = AmortisationEngine.eirLeg(openingGca, mirroredEir, mirrored, MONTHLY);
        AmortisationResult contractualLeg =
            AmortisationEngine.contractualLeg(PRINCIPAL, CONTRACTUAL, mirrored, MONTHLY);
        TwoLegResult two = TwoLegResult.reconcile(
            eirLeg, contractualLeg, mirroredEir, CONTRACTUAL, Money.inr("-5000"));

        assertThat(mirroredEir.effectiveAnnual()).isLessThan(CONTRACTUAL.effectiveAnnual());
        // 12.123448% p.a. against a 12.682503% coupon.
        assertThat(mirroredEir.effectiveAnnual().setScale(6, java.math.RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.121234"));

        // INV-1 still holds, with the fee entering as a deduction: 129,763.28 - 5,000.00.
        assertThat(two.totalEirInterest().atPresentationScale().amount())
            .isEqualByComparingTo(bd("124763.28"));
        assertThat(two.netFeeRecognised().atPresentationScale().amount())
            .isEqualByComparingTo(bd("-5000.00"));
        // The unamortised "fee" is a negative balance — a deferred cost — and it still
        // runs to the residue by maturity.
        assertThat(two.presentedUnamortisedFeeAt(1).amount()).isEqualByComparingTo(bd("-4629.38"));
        assertThat(two.presentedUnamortisedFeeAt(24).amount()).isEqualByComparingTo(bd("0.06"));
        assertThat(two.isClean()).isTrue();
    }

    @Test
    @DisplayName("INV-2 with no fee: the EIR is the contractual rate, to the last stored digit")
    void nilFeeMeansEqualRates() {
        // No fee, and the instalment is the unrounded annuity payment rather than the
        // billed one, so there is no residue either. Then the two legs are the same ledger
        // and the solved rate has to come back as exactly 1.000000000000% per month — not
        // approximately, because the discount the solver inverts is the annuity that
        // defined the instalment.
        Money exactAnnuity = Annuity.instalment(PRINCIPAL, CONTRACTUAL.periodic(), 24);
        FlowVector nilFee = inceptionVector(exactAnnuity, 24);
        Rate solved = solveAtInception(nilFee);

        assertThat(solved.periodic()).isEqualByComparingTo(CONTRACTUAL.periodic());
        assertThat(InvariantChecks.bitIdentical(solved, CONTRACTUAL)).isTrue();

        AmortisationResult eirLeg = AmortisationEngine.eirLeg(PRINCIPAL, solved, nilFee, MONTHLY);
        AmortisationResult contractualLeg =
            AmortisationEngine.contractualLeg(PRINCIPAL, CONTRACTUAL, nilFee, MONTHLY);
        TwoLegResult two = TwoLegResult.reconcile(
            eirLeg, contractualLeg, solved, CONTRACTUAL, Money.zero(Money.INR));

        assertThat(two.netFeeRecognised().atPresentationScale().isZero()).isTrue();
        assertThat(two.presentedUnamortisedFeeAt(1).isZero()).isTrue();
        assertThat(two.presentedUnamortisedFeeAt(24).isZero()).isTrue();
        assertThat(two.isClean()).isTrue();
    }

    @ParameterizedTest(name = "net fee {0} -> INV-2 requires the spread to have signum {1}")
    @CsvSource({"5000, 1", "-5000, -1", "0, 0"})
    @DisplayName("INV-2 is a sign law, and it catches the ordering going the wrong way")
    void feeSignOrdering(String netFee, int expectedSignum) {
        Money fee = Money.inr(netFee);
        Rate above = Rate.monthly(bd("0.0104214918"));
        Rate below = Rate.monthly(bd("0.009581469078"));
        Rate equal = CONTRACTUAL;
        Rate consistent = expectedSignum > 0 ? above : expectedSignum < 0 ? below : equal;

        assertThat(InvariantChecks.feeSignOrdering(consistent, CONTRACTUAL, fee).satisfied()).isTrue();

        // And the contradictions fail: a fee income with a yield below the coupon, or a
        // fee cost with a yield above it, is either a classification sign error or a
        // solver that converged on the wrong root. One comparison catches both.
        if (expectedSignum != 0) {
            Rate contradictory = expectedSignum > 0 ? below : above;
            InvariantResult breach = InvariantChecks.feeSignOrdering(contradictory, CONTRACTUAL, fee);
            assertThat(breach.satisfied()).isFalse();
            assertThat(breach.id()).isEqualTo(InvariantId.INV_2);
            assertThat(breach.detail()).contains("ordering contradicts the fee sign");
        }
    }

    @Test
    @DisplayName("INV-2 compares effective annual rates, so it holds across conventions")
    void feeSignOrderingComparesAnnualisedRates() {
        // A monthly periodic rate and an actual-dated annual rate are not comparable as
        // stored. 1.0421% monthly is 13.25% a year; compared as stored it would look
        // smaller than a 12.68% annual coupon and INV-2 would fail on sound inputs.
        Rate annualCoupon = Rate.annualEffective(CONTRACTUAL.effectiveAnnual());

        assertThat(CASE1_EIR.periodic()).isLessThan(annualCoupon.periodic());
        assertThat(InvariantChecks.feeSignOrdering(CASE1_EIR, annualCoupon, NET_INTEGRAL_FEE).satisfied())
            .isTrue();
    }

    @Test
    @DisplayName("the straight line is reported for contrast and never used")
    void straightLineIsAComparativeOnly() {
        TwoLegResult two = case1TwoLeg();

        assertThat(two.straightLineFeePerPeriod().atPresentationScale().amount())
            .isEqualByComparingTo(bd("208.33"));
        // 369.38 in month 1 against 19.44 in month 24. The front-loading is the whole
        // purpose of the method, and it is the gap a Tier 3 equivalence test has to close
        // before a straight line may be substituted (invariant TG-1).
        assertThat(two.rows().getFirst().presentedFeeAmortised().amount()).isEqualByComparingTo(bd("369.38"));
        assertThat(two.rows().getLast().presentedFeeAmortised().amount()).isEqualByComparingTo(bd("19.44"));
    }

    @Test
    @DisplayName("legs built from different schedules cannot be paired")
    void legsMustSpanTheSamePeriods() {
        AmortisationResult shortLeg = AmortisationEngine.contractualLeg(
            PRINCIPAL, CONTRACTUAL, inceptionVector(AmortFixtures.EMI, 12), MONTHLY);

        assertThatThrownBy(() -> TwoLegResult.reconcile(
            case1EirLeg(), shortLeg, CASE1_EIR, CONTRACTUAL, NET_INTEGRAL_FEE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("legs cover different numbers of periods");
    }

    @Test
    @DisplayName("a row whose legs disagree on cash cannot be built: both legs consume one schedule")
    void rowsRefuseMismatchedCash() {
        AmortisationRow eirRow = case1EirLeg().row(1);
        AmortisationRow tampered = AmortisationRow.of(
            1, eirRow.date(), eirRow.accrualExponent(), PRINCIPAL, Money.inr("10000"), Money.inr("40000"));

        assertThatThrownBy(() -> new TwoLegRow(1, eirRow, tampered))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both legs consume the same billed flows");
    }

    @Test
    @DisplayName("case 1's whole reconciliation is clean, and every INV is actually asserted")
    void allFourInvariantsArePresent() {
        List<InvariantId> asserted = case1TwoLeg().invariants().stream().map(InvariantResult::id).toList();

        assertThat(asserted).contains(
            InvariantId.INV_1, InvariantId.INV_2, InvariantId.INV_3, InvariantId.INV_4, InvariantId.TR_1);
        assertThat(case1TwoLeg().breaches()).isEmpty();
        // The legs' own invariants are merged in rather than discarded, so the EIR leg's
        // TR-1 travels with the reconciliation that consumed it.
        assertThat(case1ContractualLeg().invariants()).isEmpty();
    }

    private static InvariantResult invariant(TwoLegResult two, InvariantId id) {
        return two.invariants().stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " was not asserted"));
    }
}
