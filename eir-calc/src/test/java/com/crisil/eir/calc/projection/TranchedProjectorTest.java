package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A facility drawn in tranches — and the shape that produces multiple sign changes
 * (calculation specification 3.8).
 *
 * <p>Draws after {@code t=0} are outflows inside the vector, so where a draw lands
 * after repayment has begun the present-value function changes sign more than once
 * and may admit more than one mathematically valid IRR. The projector does not hide
 * that: it emits the draws as the negative flows they are and leaves the solver's
 * plausible-band disambiguation to deal with the consequences. Smoothing the draws
 * into a single notional advance would remove the sign changes and the disclosure
 * with them, which is why the sign-change count is asserted here rather than assumed
 * away.
 */
class TranchedProjectorTest {

    private static final ContractTerms FACILITY = shaped(ScheduleShape.TRANCHED, 24, RateType.FIXED);

    /** 600,000 at financial closure and 400,000 in month 6, against a 1,000,000 sanction. */
    private static List<Tranche> twoDraws() {
        return List.of(
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("400000")));
    }

    @Test
    @DisplayName("a draw after repayment has begun gives the vector more than one sign change")
    void interimDrawdownProducesMultipleSignChanges() {
        // Repayment from period 4, second draw in period 6: the vector runs
        // out, in, out, in.
        TranchedProjector projector = new TranchedProjector(twoDraws(), 3);

        ProjectionResult result = projector.project(FACILITY, List.of());

        assertThat(Discounting.signChanges(result.contractual()))
            .as("this is the profile that makes the multiple-root policy of 4.4 necessary")
            .isEqualTo(3);
        assertThat(Discounting.signChanges(result.contractual())).isGreaterThan(1);
        assertThat(result.contractual().future())
            .filteredOn(flow -> flow.kind() == FlowKind.DISBURSEMENT)
            .singleElement()
            .satisfies(draw -> {
                assertThat(draw.periodIndex()).isEqualTo(6);
                assertThat(draw.amount().amount()).isEqualByComparingTo(bd("-400000.00"));
                assertThat(draw.date()).isEqualTo(DISBURSEMENT.plusMonths(6));
            });
        // The interim draw shares its period with an instalment, and both are in the
        // vector: netting them would lose the drawdown from the contractual leg.
        assertThat(result.contractual().future())
            .filteredOn(flow -> flow.periodIndex() == 6).hasSize(2);
    }

    @Test
    @DisplayName("sequential draws before repayment leave one sign change")
    void sequentialDrawsAreUnambiguous() {
        // The ordinary project-finance case: draws through construction, repayment after
        // the last of them. Repayment starts the period after the final draw, so the
        // vector is out, out, in and changes sign once.
        TranchedProjector projector = new TranchedProjector(twoDraws());

        ProjectionResult result = projector.project(FACILITY, List.of());

        assertThat(projector.repaymentStartPeriod(FACILITY)).isEqualTo(7);
        assertThat(projector.drawdownPeriods()).isEqualTo(6);
        assertThat(Discounting.signChanges(result.contractual())).isEqualTo(1);
    }

    @Test
    @DisplayName("the carrying amount opens at the first draw, not at the sanctioned limit")
    void carryingAmountOpensAtTheFirstDraw() {
        // Only the money that has actually left is recognised. The sanction is a
        // commitment, and a carrying amount of 1,000,000 on day one would recognise an
        // asset for cash the bank still holds.
        TranchedProjector projector = new TranchedProjector(twoDraws(), 3);

        ProjectionResult result = projector.project(FACILITY, case1Fees());

        assertThat(projector.firstDraw().amount()).isEqualByComparingTo(bd("600000"));
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("595000.00"));
        assertThat(result.netCashAtInception().amount()).isEqualByComparingTo(bd("-595000.00"));
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
    }

    @Test
    @DisplayName("the instalment closes the contractual leg at zero over every draw")
    void theInstalmentAmortisesEveryDraw() {
        // A = sum(d_j (1+i)^(n-j)) / sum((1+i)^(n-p)) — stated over all draws rather than
        // as an annuity on the drawn balance, which is what lets an interim draw be
        // amortised instead of left stranded at maturity.
        TranchedProjector projector = new TranchedProjector(twoDraws(), 3);
        ProjectionResult result = projector.project(FACILITY, List.of());

        assertThat(projector.billedInstalment(FACILITY).amount()).isEqualByComparingTo(bd("53371.03"));
        assertThat(ContractualBalance.after(projector.firstDraw(), FACILITY.periodicRate(),
            result.contractual(), 24).atPresentationScale().amount())
            .as("the derived instalment leaves only the rounding residue behind")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("interest during construction capitalises over whole periods")
    void interestDuringConstructionCapitalises() {
        // IDC is capitalised rather than expensed, so it is an EIR input. On sequential
        // draws the construction-period balance is exactly what the instalment amortises.
        TranchedProjector projector = new TranchedProjector(twoDraws());

        Money capitalised = projector.capitalisedBalance(FACILITY);

        // 600,000 compounded over six periods plus the 400,000 drawn at the end of them.
        assertThat(capitalised.amount()).isEqualByComparingTo(
            Money.inr("600000").times(Precision.onePlusPow(bd("0.01"), 6))
                .plus(Money.inr("400000")).amount());
        assertThat(capitalised.amount()).isGreaterThan(bd("1000000"));
    }

    @Test
    @DisplayName("a draw schedule that does not sum to the facility is a data defect")
    void drawsMustSumToThePrincipal() {
        // FR-512 monitors deviation of actual against the projection; a projection that
        // does not add up to the sanction is not a deviation to be absorbed.
        TranchedProjector underDrawn = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("300000"))));

        assertThatThrownBy(() -> underDrawn.project(FACILITY, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("projected draws total")
            .hasMessageContaining("data defect, not a");
    }

    @Test
    @DisplayName("the first tranche must be drawn at initial recognition")
    void theFirstDrawIsTheAnchor() {
        // The anchor date and initial recognition are one date. A facility whose first
        // draw is later has a different anchor, and discounting it from this one would
        // date every flow wrongly.
        TranchedProjector late = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT.plusMonths(1), 1, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("400000"))));

        assertThatThrownBy(() -> late.project(FACILITY, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the first tranche must be drawn at initial recognition");
    }

    @Test
    @DisplayName("a draw at or after maturity, or a repayment start beyond it, is refused")
    void degenerateScheduleShapesAreRefused() {
        TranchedProjector drawAtMaturity = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(24), 24, Money.inr("400000"))));
        TranchedProjector noRepaymentPeriods = new TranchedProjector(twoDraws(), 24);

        assertThatThrownBy(() -> drawAtMaturity.project(FACILITY, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a draw at or after maturity is a data defect");
        assertThatThrownBy(() -> noRepaymentPeriods.project(FACILITY, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("leaving nothing to amortise");
        assertThatThrownBy(() -> new TranchedProjector(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs at least one drawdown");
    }

    @Test
    @DisplayName("a tranche is supplied unsigned and signed by the projector")
    void tranchesAreSuppliedUnsigned() {
        // Keeping the input unsigned avoids the class of defect where a facility is loaded
        // with the wrong sign and projects as a receipt.
        assertThatThrownBy(() -> Tranche.of(DISBURSEMENT, 0, Money.inr("-600000")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("drawn as a positive amount");

        List<CashFlow> flows = new TranchedProjector(twoDraws(), 3)
            .project(FACILITY, List.of()).contractual().flows();
        assertThat(flows.get(0).amount().isNegative()).isTrue();
        assertThat(flows).filteredOn(flow -> flow.kind() == FlowKind.DISBURSEMENT)
            .allSatisfy(flow -> assertThat(flow.amount().isNegative()).isTrue());
    }

    @Test
    @DisplayName("draws are ordered by period whatever order they are supplied in")
    void drawsAreSortedOnConstruction() {
        // The projection has to be reproducible from an unordered feed (invariant DT-1).
        TranchedProjector reversed = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT.plusMonths(6), 6, Money.inr("400000")),
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000"))));

        assertThat(reversed.tranches()).extracting(Tranche::periodIndex).containsExactly(0, 6);
        assertThat(reversed.project(FACILITY, List.of()).contractual())
            .isEqualTo(new TranchedProjector(twoDraws()).project(FACILITY, List.of()).contractual());
    }

    @Test
    @DisplayName("a milestone-dated draw voids periodic indexing")
    void offCycleDrawsFallBackToActualDating() {
        // Draws land on milestones, not on period boundaries. The convention check
        // declines the periodic index for the discounting; the capitalisation still steps
        // period by period, which is why a genuinely off-cycle facility should supply its
        // billed schedule rather than have one derived.
        TranchedProjector offCycle = new TranchedProjector(List.of(
            Tranche.of(DISBURSEMENT, 0, Money.inr("600000")),
            Tranche.of(DISBURSEMENT.plusMonths(6).plusDays(11), 6, Money.inr("400000"))));

        ProjectionResult result = offCycle.project(FACILITY, List.of());

        assertThat(result.contractual().periodicIndexEligible(12)).isFalse();
        assertThat(result.recommendedConvention())
            .isInstanceOf(TimeConvention.ActualDate.class);
    }
}
