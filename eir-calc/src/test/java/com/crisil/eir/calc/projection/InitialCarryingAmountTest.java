package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.TimeConvention;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant IC-1: the initial gross carrying amount is the net cash flow at
 * inception.
 *
 * <p>On the Case 1 loan that is 995,000.00 — neither the 1,000,000.00 advanced nor
 * the 985,000.00 the borrower received. The figure has two independent derivations,
 * and IC-1 is the assertion that they agree: the projector computes it from the
 * contract terms and the fee classifications, and the vector produces it by summing
 * the flows dated on the anchor. Where the two disagree, either a fee has been
 * misclassified or a non-cash item has entered the vector.
 *
 * <p>IC-1 is computed inside {@link ProjectionResult}, not supplied to it, so no
 * projector can omit it and no caller can substitute a friendlier answer. Both
 * properties are asserted below.
 */
class InitialCarryingAmountTest {

    private final AnnuityProjector projector = new AnnuityProjector();

    @Test
    @DisplayName("the Case 1 carrying amount is 995,000.00 and equals the net cash outflow at inception")
    void carryingAmountEqualsNetCashAtInception() {
        ProjectionResult result = projector.project(case1(), case1Fees());

        // 1,000,000 out to the borrower, 15,000 of processing fee in, 10,000 of DSA
        // commission out to the sourcing agent: 995,000 of net cash outflow.
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("995000.00"));
        assertThat(result.netCashAtInception().amount()).isEqualByComparingTo(bd("-995000.00"));
        assertThat(result.initialCarryingAmount().amount())
            .as("IC-1: the carrying amount is the net cash flow at inception, signed for the ledger")
            .isEqualByComparingTo(result.netCashAtInception().negate().amount());

        InvariantResult ic1 = result.initialRecognitionCheck();
        assertThat(ic1.id()).isEqualTo(InvariantId.IC_1);
        assertThat(ic1.satisfied()).isTrue();
        assertThat(ic1.deviation()).isEqualByComparingTo(bd("0"));
        assertThat(result.allInvariantsSatisfied()).isTrue();
        assertThat(result.requireInvariantsSatisfied()).isSameAs(result);
    }

    @Test
    @DisplayName("the inception leg carries the advance and each integral posting on the anchor date")
    void inceptionLegIsThreeFlowsOnOneDate() {
        ProjectionResult result = projector.project(case1(), case1Fees());
        List<CashFlow> inception = result.contractual().atInception();

        assertThat(inception).hasSize(3);
        assertThat(inception).allSatisfy(flow -> {
            assertThat(flow.date()).isEqualTo(DISBURSEMENT);
            assertThat(flow.periodIndex()).isZero();
        });
        assertThat(inception).extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.DISBURSEMENT, FlowKind.INTEGRAL_FEE_RECEIVED,
                FlowKind.INTEGRAL_COST_PAID);
        // Integral postings are dated at initial recognition rather than at their own
        // posting date. Discounting an origination cost as a future flow understates the
        // carrying amount; dropping it breaks IC-1.
        assertThat(inception).extracting(flow -> flow.amount().amount().stripTrailingZeros())
            .containsExactlyInAnyOrder(bd("-1000000").stripTrailingZeros(),
                bd("15000").stripTrailingZeros(), bd("-10000").stripTrailingZeros());
    }

    @Test
    @DisplayName("only INTEGRAL postings enter the carrying amount")
    void nonIntegralPostingsAreExcluded() {
        // Each of these is a real posting that simply does not belong to the initial
        // carrying amount: a commitment fee where drawdown is not probable, an
        // as-incurred servicing charge, a distinct performance obligation. Dropping them
        // here is the point of having the rule set resolve classification first (3.2).
        List<FeePosting> mixed = new ArrayList<>(case1Fees());
        mixed.add(FeePosting.received("ANNUAL_SERVICING", Money.inr("2000"), DISBURSEMENT,
            FeeClassification.AS_INCURRED));
        mixed.add(FeePosting.commitment("COMMITMENT_FEE", Money.inr("3000"), DISBURSEMENT,
            FeeClassification.OVER_COMMITMENT_PERIOD, bd("0.15")));
        mixed.add(FeePosting.received("INSURANCE_COMMISSION", Money.inr("4000"), DISBURSEMENT,
            FeeClassification.SEPARATE_SERVICE));

        ProjectionResult result = projector.project(case1(), mixed);

        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("995000.00"));
        assertThat(result.contractual().atInception()).hasSize(3);
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
    }

    @Test
    @DisplayName("a misclassified fee breaks IC-1, and the breach is caught rather than absorbed")
    void aMisclassifiedFeeBreaksIcOne() {
        // The projector derives the carrying amount and the vector from one assembly, so
        // they cannot disagree by accident — which is the design. What can disagree is a
        // carrying amount that reached the engine from elsewhere: a fee the rule set
        // classified INTEGRAL for the balance-sheet figure but AS_INCURRED for the cash
        // leg, or an amount taken from a core banking extract that still carries the
        // gross 1,000,000. Both present exactly as below.
        ProjectionResult sound = projector.project(case1(), case1Fees());

        ProjectionResult misclassified = new ProjectionResult(sound.contractual(), sound.expected(),
            Money.inr("1000000.00"), sound.recommendedConvention(), true, List.of());

        InvariantResult ic1 = misclassified.initialRecognitionCheck();
        assertThat(ic1.satisfied()).isFalse();
        assertThat(ic1.deviation())
            .as("the deviation is the whole of the net integral fee, which is exactly what was lost")
            .isEqualByComparingTo(bd("5000.00"));
        assertThat(misclassified.allInvariantsSatisfied()).isFalse();
        assertThat(ic1.detail()).contains("995000.00").contains("1000000.00");

        // A breach is a control exception, not a rounding nuisance: nothing downstream
        // may continue with a substituted value.
        assertThatThrownBy(misclassified::requireInvariantsSatisfied)
            .isInstanceOf(InvariantBreachException.class)
            .hasMessageContaining("IC_1")
            .hasMessageContaining("initial gross carrying amount");
    }

    @Test
    @DisplayName("a supplied IC-1 result is discarded in favour of the computed one")
    void suppliedIcOneIsIgnored() {
        // An engine that trusted a passed-in IC-1 would let a caller assert its own
        // compliance. The constructor recomputes and drops whatever it was handed.
        ProjectionResult sound = projector.project(case1(), case1Fees());
        InvariantResult fabricated = InvariantResult.pass(InvariantId.IC_1, "all is well");

        ProjectionResult tampered = new ProjectionResult(sound.contractual(), sound.expected(),
            Money.inr("1000000.00"), sound.recommendedConvention(), true, List.of(fabricated));

        assertThat(tampered.invariants()).hasSize(1);
        assertThat(tampered.initialRecognitionCheck().satisfied()).isFalse();
        assertThat(tampered.initialRecognitionCheck().detail()).isNotEqualTo("all is well");
    }

    @Test
    @DisplayName("IC-1 is signed, so a liability's carrying amount is negative")
    void icOneIsSignedForTheLedger() {
        // The roll-forward is closing = opening + interest - cash, so a liability raised
        // is carried negative, accretes negative finance cost and is cleared to zero by
        // negative payments. Stating it positive reverses the roll and grows the balance
        // without limit — which is why IC-1 compares signed figures and not magnitudes.
        List<CashFlow> flows = List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-20000"), FlowKind.INTEGRAL_COST_PAID),
            CashFlow.of(DISBURSEMENT.plusMonths(12), 12, Money.inr("-1120000"), FlowKind.PRINCIPAL));
        FlowVector borrowing = FlowVector.of(DISBURSEMENT, Money.INR, flows);

        ProjectionResult liability = ProjectionResult.of(borrowing, borrowing, Money.inr("-980000.00"),
            TimeConvention.PeriodicIndex.monthly(), true);

        assertThat(liability.initialRecognitionCheck().satisfied()).isTrue();
        assertThat(liability.netCashAtInception().amount()).isEqualByComparingTo(bd("980000"));
        assertThat(liability.initialCarryingAmount().isNegative()).isTrue();
    }
}
