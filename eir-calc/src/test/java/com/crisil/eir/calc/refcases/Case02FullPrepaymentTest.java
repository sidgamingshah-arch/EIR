package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.NET_INTEGRAL_FEE;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.amort.DerecognitionCalculator;
import com.crisil.eir.calc.amort.DerecognitionReason;
import com.crisil.eir.calc.amort.DerecognitionResult;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 2 — full prepayment at month 12 accelerates the unamortised fee.
 *
 * <p>Continues case 1. The borrower settles the <em>contractual</em> principal of
 * 529,815.61 against a carrying amount of 528,407.32; the residual 1,408.29 is the
 * deferred fee that would have been released over months 13 to 24 and is
 * recognised immediately in P&amp;L on derecognition.
 *
 * <p>The failure mode this case guards is writing that residual to a suspense
 * account rather than to income. That produces unexplained P&amp;L and an
 * unreconciled fee balance which then never clears — and because the unamortised
 * fee here is <em>defined</em> as the gap between the two legs rather than
 * accumulated separately, the engine cannot carry a fee balance that has drifted
 * away from the balances it sits between.
 *
 * <p>Figures from {@code docs/reference-cases/case-02-full-prepayment.md}.
 */
class Case02FullPrepaymentTest {

    private final ReferenceCaseFixtures.Baseline case1 = baseline();

    @Test
    @DisplayName("position before settlement: contractual 529,815.61, carrying amount 528,407.32")
    void thePositionImmediatelyBeforeSettlement() {
        TwoLegResult twoLeg = case1.twoLeg();

        assertThat(paise(case1.contractualLeg().row(12).closingGca()))
            .as("contractual outstanding principal the borrower discharges")
            .isEqualByComparingTo(bd("529815.61"));
        assertThat(paise(case1.eirLeg().row(12).closingGca()))
            .as("EIR amortised cost — the gross carrying amount on the books")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(paise(twoLeg.presentedUnamortisedFeeAt(12)))
            .as("unamortised net integral fee")
            .isEqualByComparingTo(bd("1408.29"));
    }

    @Test
    @DisplayName("months 1-12 amortised 3,591.71 of the 5,000.00 net fee")
    void theFeeAlreadyAmortisedOverTheFirstTwelveMonths() {
        Money amortisedToDate = NET_INTEGRAL_FEE.minus(case1.twoLeg().unamortisedFeeAt(12));

        assertThat(paise(amortisedToDate))
            .as("5,000.00 net integral fee less the 1,408.29 still deferred")
            .isEqualByComparingTo(bd("3591.71"));

        // The same figure the other way round: the per-period fee column, summed. Stated
        // both ways because the fee balance is derived from the two legs rather than
        // accumulated, so the sum of the movements and the closing balance are two
        // independent routes to it.
        Money summedMovements = Money.zero(Money.INR);
        for (int period = 1; period <= 12; period++) {
            summedMovements = summedMovements.plus(case1.twoLeg().rows().get(period - 1).feeAmortised());
        }
        assertThat(paise(summedMovements))
            .as("periods 1..12 of the fee-amortised column")
            .isEqualByComparingTo(bd("3591.71"));
    }

    @Test
    @DisplayName("derecognition: 529,815.61 received less 528,407.32 derecognised is a 1,408.29 gain to P&L")
    void theAcceleratedFeeIsAGainOnDerecognition() {
        // Through the engine, not computed here. This assertion used to read
        // cashReceived.minus(grossCarryingAmount) in the test body — right arithmetic, and
        // as a merge gate it gated nothing, because no derecognition routine existed for
        // it to disagree with. A reference case that computes its own answer states a fact
        // about subtraction rather than testing an implementation.
        DerecognitionResult closure = DerecognitionCalculator.onClosure(
            case1.contractualLeg().row(12).closingGca(),
            case1.eirLeg().row(12).closingGca(),
            case1.twoLeg().unamortisedFeeAt(12));

        assertThat(closure.reason()).isEqualTo(DerecognitionReason.CLOSURE);
        assertThat(paise(closure.gainOrLoss()))
            .as("gain to P&L on closure — accelerated fee, not a suspense balance")
            .isEqualByComparingTo(bd("1408.29"));
        assertThat(closure.isGain())
            .as("a net fee received is income, so early closure brings income forward")
            .isTrue();
        assertThat(closure.affectsInterestIncome())
            .as("a closure gain is this engine's to recognise, unlike a write-off's loss")
            .isTrue();

        // The position is extinguished, not left with a residual. A routine that returns a
        // gain while leaving a balance behind is precisely the defect this case names: a
        // residual written to suspense that then never clears.
        assertThat(closure.carryingAmountAfter().isZero()).isTrue();

        // And INV-4 is asserted by the engine rather than restated here: the gain it
        // computed IS the unamortised fee the sub-ledger reports, so two legs that had
        // drifted apart could not survive this event.
        assertThat(closure.isClean()).isTrue();
        assertThat(closure.invariants())
            .anyMatch(result -> result.id() == InvariantId.INV_4 && result.satisfied());
        assertThat(paise(closure.gainOrLoss()))
            .isEqualByComparingTo(paise(case1.twoLeg().unamortisedFeeAt(12)));
    }

    @Test
    @DisplayName("a drifted fee balance cannot pass closure: INV-4 fails and the result is not clean")
    void aDriftedFeeBalanceBreachesInvariantFourOnClosure() {
        // The reason INV-4 is worth asserting at the moment of derecognition rather than
        // trusting it from the roll-forward. The fee balance reaching the engine comes from
        // the sub-ledger; if it has drifted from the gap between the legs, closure is the
        // last point at which anything can notice, because after it both legs are gone.
        DerecognitionResult drifted = DerecognitionCalculator.onClosure(
            case1.contractualLeg().row(12).closingGca(),
            case1.eirLeg().row(12).closingGca(),
            case1.twoLeg().unamortisedFeeAt(12).plus(Money.inr("0.50")));

        assertThat(drifted.isClean()).isFalse();
        assertThat(drifted.breaches()).hasSize(1);
        assertThat(drifted.breaches().get(0).id()).isEqualTo(InvariantId.INV_4);
        // The gain itself is still the leg difference — the engine reports the arithmetic
        // it can verify and flags the figure it was handed, rather than adopting it.
        assertThat(paise(drifted.gainOrLoss())).isEqualByComparingTo(bd("1408.29"));
    }

    @Test
    @DisplayName("a write-off removes the balance but its loss is not this engine's to recognise")
    void aWriteOffIsRemovedWithoutBeingRecognisedHere() {
        // The boundary of ADR's ECL split, expressed in code. The balance goes to zero on
        // the same mechanic as a closure, so SL-1 continues to tie; the loss is measured
        // against the allowance the impairment engine holds (ACPIR 6(12)), so posting it
        // to a fee or interest line here would count it twice.
        DerecognitionResult writeOff = DerecognitionCalculator.onWriteOff(
            Money.zero(Money.INR), case1.eirLeg().row(12).closingGca());

        assertThat(paise(writeOff.gainOrLoss())).isEqualByComparingTo(bd("-528407.32"));
        assertThat(writeOff.isLoss()).isTrue();
        assertThat(writeOff.affectsInterestIncome())
            .as("the allowance this consumes is not held by this engine")
            .isFalse();
        assertThat(writeOff.carryingAmountAfter().isZero()).isTrue();
        // No INV-4: the consideration is a recovery, not a contractual balance, so the
        // difference is not an unamortised fee and asserting it would fail on every
        // sound write-off.
        assertThat(writeOff.invariants()).isEmpty();
    }

    @Test
    @DisplayName("a substantial modification derecognises at carrying amount against the new asset's fair value")
    void aSubstantialModificationRemeasuresRatherThanAcceleratingAFee() {
        // Same mechanic, different meaning: the consideration is a fair value, so the
        // difference is a remeasurement rather than a deferred fee. INV-4 is deliberately
        // not asserted — it would fail on every sound substantial modification, which is
        // why these are separate methods and not one method with a flag.
        Money carrying = case1.eirLeg().row(12).closingGca();
        DerecognitionResult modified = DerecognitionCalculator.onSubstantialModification(
            carrying.minus(Money.inr("25000")), carrying);

        assertThat(modified.reason()).isEqualTo(DerecognitionReason.SUBSTANTIAL_MODIFICATION);
        assertThat(paise(modified.gainOrLoss())).isEqualByComparingTo(bd("-25000.00"));
        assertThat(modified.affectsInterestIncome()).isTrue();
        assertThat(modified.invariants()).isEmpty();
    }

    @Test
    @DisplayName("no prepayment penalty enters the calculation: a contingent flow cannot reach a projection")
    void contingentFlowsAreExcludedFromTheProjection() {
        // Contingent fees are excluded from the inception projection regardless of being
        // contractually specified (FR-206). A penalty, where one is chargeable at all, is
        // separate income in the period the event occurs — and for floating-rate
        // individual loans RBI restricts foreclosure charges, so in the Indian retail
        // book there is usually no penalty cash flow to consider at all.
        ProjectionResult clean = case1.projection();
        List<CashFlow> withPenalty = new ArrayList<>(clean.contractual().flows());
        withPenalty.add(CashFlow.contingent(ReferenceCaseFixtures.MONTH_12, 12,
            Money.inr("10596.31"), FlowKind.INTEGRAL_FEE_RECEIVED));
        FlowVector contaminated = FlowVector.of(DISBURSEMENT, Money.INR, withPenalty);

        assertThatThrownBy(() -> new ProjectionResult(
            contaminated, contaminated, clean.initialCarryingAmount(),
            clean.recommendedConvention(), false, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contingent flows must not enter the EIR projection");
    }

    @Test
    @DisplayName("a prepayment penalty classified AS_INCURRED does not enter the carrying amount")
    void anAsIncurredPenaltyDoesNotEnterTheInitialCarryingAmount() {
        FeePosting penalty = FeePosting.received("PREPAYMENT_PENALTY", Money.inr("10596.31"),
            ReferenceCaseFixtures.MONTH_12, FeeClassification.AS_INCURRED);
        assertThat(penalty.entersInitialCarryingAmount())
            .as("recognised in the period the event occurs, never inside the rate")
            .isFalse();

        List<FeePosting> fees = new ArrayList<>(ReferenceCaseFixtures.case1Fees());
        fees.add(penalty);
        ProjectionResult withPenalty =
            ProjectorRegistry.standard().project(case1.terms(), fees);

        assertThat(paise(withPenalty.initialCarryingAmount()))
            .as("GCA is unmoved by a contingent charge — still 1,000,000 less the 5,000 net fee")
            .isEqualByComparingTo(bd("995000.00"));
    }

    @Test
    @DisplayName("cross-check: 1,408.29 is Case 1's INV-4 at month 12")
    void theFigureIsCaseOnesMonthTwelveInvariant() {
        // Three independent routes to one figure: Case 1's INV-4 (contractual GCA less
        // EIR GCA), this case's acceleration on closure, and Case 8's eliminated balance.
        // The cross-check is worth stating because it demonstrates the fee balance cannot
        // drift away from the two legs it is defined as the gap between.
        Money legDifference = case1.contractualLeg().row(12).closingGca()
            .minus(case1.eirLeg().row(12).closingGca());
        assertThat(paise(legDifference)).isEqualByComparingTo(bd("1408.29"));
        assertThat(paise(case1.twoLeg().presentedUnamortisedFeeAt(12)))
            .isEqualByComparingTo(bd("1408.29"));
    }
}
