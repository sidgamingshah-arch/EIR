package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The EMI annuity, and the distinction between the instalment that is calculated
 * and the instalment that is billed.
 *
 * <p>On the Case 1 loan the annuity is 47,073.472223 and the billed EMI is
 * 47,073.47. Both figures are asserted here because both are used: the EIR is solved
 * against the <em>billed</em> flows, since those are the amounts the borrower
 * actually pays, and the 0.002223 monthly shortfall the rounding leaves compounds to
 * a residue on the contractual leg that an explicit
 * {@link ResiduePolicy} resolves rather than a tolerance
 * (calculation specification 5.7).
 */
class AnnuityProjectorTest {

    private final AnnuityProjector projector = new AnnuityProjector();

    @Test
    @DisplayName("the Case 1 annuity is 47,073.472223 unrounded and 47,073.47 billed")
    void annuityAndBilledInstalment() {
        // A = P * i / (1 - (1+i)^-n) on 1,000,000 at 1% a month over 24 months.
        assertThat(projector.annuityInstalment(case1()).amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("47073.472223"));
        assertThat(projector.billedInstalment(case1()).amount())
            .isEqualByComparingTo(bd("47073.47"));
        // Billed means billed: presentation scale, because a lender bills in paise and
        // the schedule the borrower pays is the rounded one.
        assertThat(projector.billedInstalment(case1()).amount().scale()).isEqualTo(2);
        assertThat(projector.annuityInstalment(case1()).amount())
            .as("the unrounded annuity is carried at working precision, not at 2dp")
            .isGreaterThan(projector.billedInstalment(case1()).amount());
    }

    @Test
    @DisplayName("the rounding residue on the contractual leg is 0.059969 and is not treated as an error")
    void contractualResidue() {
        // 0.002223 a month compounded over 24 periods at 1%. This is the 0.06 that
        // stands in the unamortised-fee column at period 24 in the Case 1 fixture. The
        // default policy leaves it visible: in production the schedule should come from
        // the lending system, and a projector that quietly plugged the residue would
        // disagree with the golden fixture and with the core banking system at once.
        assertThat(projector.contractualResidue(case1()).amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.059969"));
        assertThat(projector.residuePolicy()).isEqualTo(ResiduePolicy.LMS_AUTHORITATIVE);
    }

    @Test
    @DisplayName("the projected leg is 24 billed EMIs on the contractual due dates")
    void projectedLeg() {
        ProjectionResult result = projector.project(case1(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(24);
        for (int index = 0; index < future.size(); index++) {
            CashFlow flow = future.get(index);
            assertThat(flow.periodIndex()).isEqualTo(index + 1);
            assertThat(flow.date()).isEqualTo(FIRST_DUE.plusMonths(index));
            assertThat(flow.kind()).isEqualTo(FlowKind.COMBINED_EMI);
            assertThat(flow.amount().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(flow.contingent()).isFalse();
        }
        // Total receipts of 1,129,763.28 against 995,000 of carrying amount — the
        // fixture's lifetime figure.
        assertThat(result.contractual().future().stream()
            .map(flow -> flow.amount().amount())
            .reduce(bd("0"), BigDecimal::add))
            .isEqualByComparingTo(bd("1129763.28"));
    }

    @Test
    @DisplayName("a clean monthly annuity licenses the periodic index")
    void cleanScheduleLicensesPeriodicIndexing() {
        ProjectionResult result = projector.project(case1(), case1Fees());

        // Periods uniform, every flow on a period boundary, no broken first period: the
        // cheaper convention is exactly equivalent here, and it is taken only because
        // the precondition was checked (3.10).
        assertThat(result.recommendedConvention())
            .isEqualTo(new TimeConvention.PeriodicIndex(12));
        assertThat(result.recommendedConvention().periodsPerYear()).isEqualTo(12);
        assertThat(result.contractual().periodicIndexEligible(12)).isTrue();
    }

    @Test
    @DisplayName("with no expected life stated the two legs coincide, and the result records it as a policy choice")
    void bothLegsCoincideWhereExpectedLifeIsContractual() {
        ProjectionResult result = projector.project(case1(), case1Fees());

        assertThat(result.expected()).isEqualTo(result.contractual());
        // "We used contractual life" and "we never considered life" produce identical
        // numbers and very different audit outcomes (3.6). The flag is the only thing
        // that distinguishes them downstream; the justification lives on the contract.
        assertThat(result.expectedEqualsContractualByPolicy()).isTrue();
    }

    @Test
    @DisplayName("a shorter expected life truncates the leg and carries the contractual balance over")
    void behaviouralLifeTruncatesTheExpectedLeg() {
        ContractTerms shorterLife = case1().withLives(12, 24,
            "ACPIR 51 behavioural life from the product CPR curve; ACPIR 46(1) horizon is contractual");

        ProjectionResult result = projector.project(shorterLife, case1Fees());
        CashFlow terminal = result.expected().flows().get(result.expected().size() - 1);

        assertThat(result.contractual().future()).hasSize(24);
        assertThat(result.expected().future()).hasSize(13);
        assertThat(result.expectedEqualsContractualByPolicy()).isFalse();
        // The prepayment is the *contractual* balance at that date, so the expected leg
        // differs from the contractual one in timing only — expected credit losses are
        // excluded for every non-POCI instrument (ACPIR 51). 529,815.61 is the same
        // month-12 figure as the Case 8 notional redemption and the Case 1 INV-4 check.
        assertThat(terminal.kind()).isEqualTo(FlowKind.EXPECTED_PREPAYMENT);
        assertThat(terminal.periodIndex()).isEqualTo(12);
        assertThat(terminal.amount().amount()).isEqualByComparingTo(bd("529815.61"));
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("995000.00"));
    }

    @Test
    @DisplayName("the annuity projector declines a moratorium rather than relying on registry order")
    void declinesAMoratorium() {
        // Interest capitalising through a moratorium changes the balance the annuity is
        // struck on, and that is MoratoriumProjector's to get right. Declining the terms
        // means adding a projector cannot change what an existing one answers.
        assertThat(projector.supports(case1())).isTrue();
        assertThat(projector.supports(case1().withMoratorium(6, true))).isFalse();
        assertThat(projector.supports(CaseFixtures.shaped(ScheduleShape.BULLET, 24, RateType.FIXED)))
            .isFalse();
    }

    @Test
    @DisplayName("an interest-free annuity spreads the principal evenly")
    void interestFreeAnnuity() {
        // Not a degenerate case to reject: interest-free instalment credit exists, and
        // its annuity is the principal divided by the term.
        ContractTerms interestFree = ContractTerms.of(Money.inr("120000"), Rate.monthly(bd("0")),
            12, 12, DISBURSEMENT, FIRST_DUE, DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI, RateType.FIXED);

        assertThat(projector.annuityInstalment(interestFree).amount()).isEqualByComparingTo(bd("10000"));
        assertThat(Annuity.instalment(Money.inr("120000"), bd("0"), 12).amount())
            .isEqualByComparingTo(bd("10000"));
        assertThat(projector.contractualResidue(interestFree).amount())
            .isEqualByComparingTo(bd("0"));
    }

    @Test
    @DisplayName("the annuity formula is shared, not reimplemented per shape")
    void annuityIsOneFormulaInOnePlace() {
        // Five shapes reduce to an annuity over some balance for some number of periods.
        // One formula in one place cannot drift between them.
        assertThat(Annuity.instalment(Money.inr("1000000"), bd("0.01"), 24).amount())
            .isEqualByComparingTo(projector.annuityInstalment(case1()).amount());
        assertThat(Annuity.billedInstalment(Money.inr("1000000"), bd("0.01"), 24))
            .isEqualTo(projector.billedInstalment(case1()));
        assertThat(Precision.round(Annuity.instalment(Money.inr("1000000"), bd("0.01"), 24).amount(), 2))
            .isEqualByComparingTo(bd("47073.47"));
    }
}
