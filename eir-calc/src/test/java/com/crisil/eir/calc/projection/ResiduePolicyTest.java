package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to the residue a rounded instalment leaves on the contractual leg
 * (calculation specification 5.7).
 *
 * <p>The residue is real and it is not a defect. The true annuity on the Case 1 loan
 * is 47,073.472223 and the billed EMI is 47,073.47; over 24 periods that 0.002223
 * monthly shortfall compounds to 0.059969 of terminal balance. Every real lender
 * resolves this somewhere, and the engine's job is to follow whatever the core
 * banking system does rather than invent its own answer.
 *
 * <p>Two rules hold under every policy and both are asserted below. The residue lands
 * on the contractual leg only and never touches the EIR leg — the rate was solved
 * against the billed flows, so that leg amortises to exactly zero by construction
 * (invariant TR-1). And a residue is never resolved by tolerance: a tolerance hides
 * exactly the class of defect this engine exists to prevent, so where a difference
 * exists a rule accounts for it.
 */
class ResiduePolicyTest {

    private static Money terminalBalance(ProjectionResult result) {
        return ContractualBalance.after(Money.inr("1000000"), bd("0.01"), result.contractual(), 24);
    }

    private static List<CashFlow> futureOf(ResiduePolicy policy, int spreadPeriods) {
        return new AnnuityProjector(policy, spreadPeriods)
            .project(case1(), case1Fees()).contractual().future();
    }

    @Test
    @DisplayName("LMS_AUTHORITATIVE leaves the residue visible and derives nothing")
    void lmsAuthoritativeLeavesTheResidueWhereTheArithmeticPutsIt() {
        ProjectionResult result = new AnnuityProjector(ResiduePolicy.LMS_AUTHORITATIVE)
            .project(case1(), case1Fees());

        assertThat(result.contractual().future())
            .allSatisfy(flow -> assertThat(flow.amount().amount()).isEqualByComparingTo(bd("47073.47")));
        // The 0.06 that stands in the unamortised-fee column at period 24 of the Case 1
        // fixture. Leaving it visible is the safe failure: it shows up in a reconciliation
        // instead of being quietly absorbed.
        assertThat(terminalBalance(result).amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.059969"));
        assertThat(terminalBalance(result).atPresentationScale().amount())
            .isEqualByComparingTo(bd("0.06"));
    }

    @Test
    @DisplayName("LMS_AUTHORITATIVE consumes a supplied schedule unchanged")
    void lmsAuthoritativeConsumesASuppliedScheduleUnchanged() {
        // The policy's real form: the schedule comes from the lending system and the
        // engine adjusts nothing. apply() returns the lines it was given.
        List<CashFlow> billed = List.of(
            CashFlow.of(FIRST_DUE, 1, Money.inr("47073.47"), FlowKind.COMBINED_EMI),
            CashFlow.of(FIRST_DUE.plusMonths(1), 2, Money.inr("47073.47"), FlowKind.COMBINED_EMI),
            CashFlow.of(FIRST_DUE.plusMonths(2), 3, Money.inr("47100.00"), FlowKind.COMBINED_EMI));

        List<CashFlow> applied = ResiduePolicy.LMS_AUTHORITATIVE.apply(
            billed, Money.inr("0.059969"), bd("0.01"), 0);

        assertThat(applied).containsExactlyElementsOf(billed);
        // And through the projector that exists for this path.
        ExternalScheduleProjector supplied = new ExternalScheduleProjector(List.of(
            Instalment.of(FIRST_DUE, 1, Money.inr("47073.47")),
            Instalment.of(FIRST_DUE.plusMonths(1), 2, Money.inr("47100.00"))));
        assertThat(supplied.residuePolicy()).isEqualTo(ResiduePolicy.LMS_AUTHORITATIVE);
        assertThat(supplied.project(shaped(ScheduleShape.STRUCTURED, 24, RateType.FIXED), List.of())
            .contractual().future())
            .extracting(flow -> flow.amount().amount().stripTrailingZeros())
            .containsExactly(bd("47073.47").stripTrailingZeros(), bd("47100.00").stripTrailingZeros());
    }

    @Test
    @DisplayName("FINAL_PERIOD_PLUG makes the last Case 1 instalment 47,073.53 and closes the leg at zero")
    void finalPeriodPlugAbsorbsTheResidueInTheLastInstalment() {
        ProjectionResult result = new AnnuityProjector(ResiduePolicy.FINAL_PERIOD_PLUG)
            .project(case1(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future.get(23).amount().amount()).isEqualByComparingTo(bd("47073.53"));
        assertThat(future.get(23).periodIndex()).isEqualTo(24);
        // Every other instalment is untouched.
        assertThat(future.subList(0, 23))
            .allSatisfy(flow -> assertThat(flow.amount().amount()).isEqualByComparingTo(bd("47073.47")));
        // The published leg closes at the scale it is published in. The remaining
        // -0.000031 is unpresentable rather than untreated: an instalment is only billable
        // to the paise, so a plug cannot close a leg at working precision.
        assertThat(terminalBalance(result).atPresentationScale().amount())
            .isEqualByComparingTo(bd("0.00"));
        assertThat(terminalBalance(result).amount().abs()).isLessThan(bd("0.005"));
        assertThat(terminalBalance(result).amount().abs())
            .as("the plug moves the residue from six hundredths of a rupee to three hundred-thousandths")
            .isLessThan(bd("0.059969"));
    }

    @Test
    @DisplayName("FIRST_PERIOD_PLUG discounts the residue back so the leg still closes")
    void firstPeriodPlugDiscountsTheResidueBack() {
        // A residue is a dated amount. Moving it 23 periods earlier undiscounted would
        // change the schedule's economics, so it is discounted at the contractual rate:
        // 0.059969 at month 24 is 0.0477 at month 1.
        List<CashFlow> future = futureOf(ResiduePolicy.FIRST_PERIOD_PLUG, 0);

        assertThat(future.get(0).amount().amount()).isEqualByComparingTo(bd("47073.52"));
        assertThat(future.get(23).amount().amount()).isEqualByComparingTo(bd("47073.47"));
        assertThat(ContractualBalance.after(Money.inr("1000000"), bd("0.01"),
            new AnnuityProjector(ResiduePolicy.FIRST_PERIOD_PLUG).project(case1(), case1Fees())
                .contractual(), 24).atPresentationScale().amount())
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("SPREAD_LAST_N shares the residue over the final instalments")
    void spreadLastNSharesTheResidue() {
        List<CashFlow> future = futureOf(ResiduePolicy.SPREAD_LAST_N, 3);

        assertThat(future.get(20).amount().amount()).isEqualByComparingTo(bd("47073.47"));
        assertThat(future.subList(21, 24))
            .allSatisfy(flow -> assertThat(flow.amount().amount()).isEqualByComparingTo(bd("47073.49")));
        // Out-of-range spreads are a configuration error, not something to clamp.
        assertThatThrownBy(() -> ResiduePolicy.SPREAD_LAST_N.apply(
            List.of(CashFlow.of(FIRST_DUE, 1, Money.inr("47073.47"), FlowKind.COMBINED_EMI)),
            Money.inr("0.06"), bd("0.01"), 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spreadPeriods must be between 1 and 1");
    }

    @Test
    @DisplayName("a plug attaches to receipts only, never to a drawdown in the same leg")
    void aPlugNeverAdjustsADrawdown() {
        // A tranched facility carries its interim draws in the same instalment leg. A plug
        // that landed on one would change the amount drawn, which is a fact about the
        // facility rather than a rounding artefact.
        TranchedProjector projector = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("400000"))),
            3, ResiduePolicy.FINAL_PERIOD_PLUG, 0);

        ProjectionResult result = projector.project(
            shaped(ScheduleShape.TRANCHED, 24, RateType.FIXED), List.of());

        assertThat(result.contractual().future())
            .filteredOn(flow -> flow.kind() == FlowKind.DISBURSEMENT)
            .singleElement()
            .satisfies(draw -> assertThat(draw.amount().amount()).isEqualByComparingTo(bd("-400000.00")));
        assertThat(result.contractual().future())
            .filteredOn(flow -> flow.periodIndex() == 24)
            .singleElement()
            .satisfies(last -> assertThat(last.amount().amount()).isEqualByComparingTo(bd("53371.09")));
    }

    @Test
    @DisplayName("apply returns a new list and leaves its input alone")
    void applyIsNonDestructive() {
        List<CashFlow> original = new ArrayList<>(List.of(
            CashFlow.of(FIRST_DUE, 1, Money.inr("47073.47"), FlowKind.COMBINED_EMI),
            CashFlow.of(FIRST_DUE.plusMonths(1), 2, Money.inr("47073.47"), FlowKind.COMBINED_EMI)));

        List<CashFlow> plugged = ResiduePolicy.FINAL_PERIOD_PLUG.apply(
            original, Money.inr("0.06"), bd("0.01"), 0);

        assertThat(plugged).isNotSameAs(original);
        assertThat(original.get(1).amount().amount()).isEqualByComparingTo(bd("47073.47"));
        assertThat(plugged.get(1).amount().amount()).isEqualByComparingTo(bd("47073.53"));
        assertThatThrownBy(() -> plugged.add(original.get(0)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a zero residue is a no-op under every policy")
    void aZeroResidueChangesNothing() {
        List<CashFlow> instalments = List.of(
            CashFlow.of(FIRST_DUE, 1, Money.inr("10000.00"), FlowKind.COMBINED_EMI),
            CashFlow.of(FIRST_DUE.plusMonths(1), 2, Money.inr("10000.00"), FlowKind.COMBINED_EMI));

        for (ResiduePolicy policy : ResiduePolicy.values()) {
            assertThat(policy.apply(instalments, Money.zero(Money.INR), bd("0.01"), 1))
                .as("%s must leave an already-closing leg alone", policy)
                .containsExactlyElementsOf(instalments);
        }
    }

    @Test
    @DisplayName("the residue is resolved by a rule, never by a tolerance, and never on the EIR leg")
    void theResidueIsAContractualLegPhenomenon() {
        // Under every policy the initial carrying amount is unchanged and the plug lands in
        // the contractual leg alone. Invariant INV-3 is stated against the actual billed
        // flows, so it holds whichever policy is in force — and the EIR leg amortises to
        // exactly zero because the rate was solved against those same billed flows.
        for (ResiduePolicy policy : ResiduePolicy.values()) {
            ProjectionResult result = new AnnuityProjector(policy, 3).project(case1(), case1Fees());
            assertThat(result.initialCarryingAmount().amount())
                .as("%s must not touch the amount recognised at inception", policy)
                .isEqualByComparingTo(bd("995000.00"));
            assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
            assertThat(terminalBalance(result).atPresentationScale().amount().abs())
                .as("%s must leave the contractual leg closing at no more than the visible residue", policy)
                .isLessThanOrEqualTo(bd("0.06"));
        }
    }
}
