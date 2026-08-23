package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Revolving facilities: an approximation that says so (ACPIR 54, calculation
 * specification 3.7).
 *
 * <p>Cash credit, overdraft, credit cards, KCC and working-capital limits have no
 * contractual repayment schedule, so there is nothing to solve a conventional EIR
 * over. ACPIR 54 accepts exactly that and permits an approximation, which is what
 * makes this legitimate rather than expedient.
 *
 * <p>The property that matters is that the output is <em>marked</em>. A revolver bent
 * into an EMI shape to reuse the annuity code yields a number that looks like an EIR,
 * reconciles to nothing, and carries no disclosure that it was an approximation. So
 * the projected vector carries no instalment leg at all and
 * {@link ProjectionResult#futureLegIsWhollySynthetic()} is true — the machine-readable
 * statement that any rate struck over this vector must be labelled an approximation.
 */
class RevolvingProjectorTest {

    private final RevolvingProjector projector = new RevolvingProjector();

    /** A 5,000,000 cash-credit limit on a twelve-month sanction. */
    private static ContractTerms cashCredit() {
        return ContractTerms.of(Money.inr("5000000"), ONE_PERCENT_MONTHLY, 12, 12,
            DISBURSEMENT, FIRST_DUE, DayCountConvention.ACT_365F, ScheduleShape.REVOLVING,
            RateType.FLOATING);
    }

    @Test
    @DisplayName("the result is explicitly an approximation, not a solved EIR")
    void theOutputIsMarkedAsAnApproximation() {
        ProjectionResult result = projector.project(cashCredit(), CaseFixtures.feeReceived("50000"));

        assertThat(result.futureLegIsWhollySynthetic())
            .as("the machine-readable statement that this is an approximation and not an EIR")
            .isTrue();
        // No instalment leg at all: the drawn balance goes out at inception, the integral
        // fee lands with it, and one synthetic flow squares the facility at renewal.
        assertThat(result.contractual().future()).hasSize(1);
        CashFlow squaring = result.contractual().future().get(0);
        assertThat(squaring.kind()).isEqualTo(FlowKind.NOTIONAL_REDEMPTION);
        assertThat(squaring.amount().amount()).isEqualByComparingTo(bd("5000000.00"));
        assertThat(squaring.date()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(squaring.date()).isEqualTo(projector.renewalDate(cashCredit()));
        assertThat(result.contractual().future())
            .noneMatch(flow -> flow.kind() == FlowKind.COMBINED_EMI);
    }

    @Test
    @DisplayName("the shape itself records that no contractual schedule exists")
    void theShapeCarriesTheDistinction() {
        // Not cosmetic: it is the difference between an EIR and an approximation that must
        // be disclosed as one. Every other shape in the family carries a schedule.
        assertThat(ScheduleShape.REVOLVING.carriesContractualSchedule()).isFalse();
        assertThat(ScheduleShape.ANNUITY_EMI.carriesContractualSchedule()).isTrue();
        assertThat(ScheduleShape.DISCOUNT_INSTRUMENT.carriesContractualSchedule()).isTrue();
        assertThat(projector.supports(cashCredit())).isTrue();
        assertThat(projector.supports(CaseFixtures.case1())).isFalse();
    }

    @Test
    @DisplayName("the fee accretes straight-line over the renewal period and the periods sum to the fee")
    void feeAccretesStraightLineOverTheRenewalPeriod() {
        // Straight-line is correct here and wrong on a term loan, and that is not an
        // inconsistency: EIR amortisation front-loads on a declining balance, and a
        // revolver has no declining balance to amortise on.
        List<FeePosting> fees = CaseFixtures.feeReceived("50000");

        RevolvingProjector.RenewalFeeAccretion accretion =
            projector.feeOverRenewal(cashCredit(), fees);

        assertThat(accretion.approximation()).isEqualTo(RevolvingApproximation.FEE_OVER_RENEWAL);
        assertThat(accretion.renewalPeriods()).isEqualTo(12);
        assertThat(accretion.deferredFee().amount()).isEqualByComparingTo(bd("50000.00"));
        assertThat(accretion.perPeriod().amount()).isEqualByComparingTo(bd("4166.67"));
        // The final period absorbs the rounding difference so the column adds up. A
        // movement schedule whose column does not sum is a defect even when every row is
        // individually correct.
        assertThat(accretion.finalPeriod().amount()).isEqualByComparingTo(bd("4166.63"));
        assertThat(accretion.perPeriod().times(bd("11")).plus(accretion.finalPeriod()).amount())
            .isEqualByComparingTo(bd("50000.00"));
    }

    @Test
    @DisplayName("only integral postings defer; availability-fee income does not")
    void onlyIntegralPostingsDefer() {
        // The defensible split is between the component compensating credit assessment and
        // documentation at each renewal, which defers, and the component compensating
        // availability of the undrawn limit, which is service income. That split is a
        // classification decision the rule set makes before projection, so whatever
        // arrives here as INTEGRAL is already the deferring component.
        List<FeePosting> mixed = List.of(
            FeePosting.received("RENEWAL_FEE", Money.inr("50000"), DISBURSEMENT,
                FeeClassification.INTEGRAL),
            FeePosting.received("AVAILABILITY_FEE", Money.inr("30000"), DISBURSEMENT,
                FeeClassification.SEPARATE_SERVICE));

        assertThat(projector.feeOverRenewal(cashCredit(), mixed).deferredFee().amount())
            .isEqualByComparingTo(bd("50000.00"));
        assertThat(projector.project(cashCredit(), mixed).initialCarryingAmount().amount())
            .isEqualByComparingTo(bd("4950000.00"));
    }

    @Test
    @DisplayName("the other ACPIR 54 approximation is refused at construction, not at projection")
    void theUtilisationApproximationNeedsASuppliedProfile() {
        // EIR over an expected drawdown and repayment profile needs that profile as an
        // input — for cards, the ACPIR 46(2)(iii) behavioural analysis. Failing at
        // construction rather than at projection means a misconfigured product cannot
        // reach a close and quietly produce the other approximation's number.
        assertThatThrownBy(() -> new RevolvingProjector(RevolvingApproximation.EIR_OVER_UTILISATION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs an expected utilisation profile")
            .hasMessageContaining("ExternalScheduleProjector");
        assertThat(projector.approximation()).isEqualTo(RevolvingApproximation.FEE_OVER_RENEWAL);
    }

    @Test
    @DisplayName("the renewal period is read from the term, and IC-1 still holds")
    void renewalPeriodIsTheTerm() {
        // termPeriods is the renewal or sanction period: the horizon the fee defers over
        // and the one the ACPIR 46(2)(ii) substantive-renewal test attaches to. Where a
        // facility is rolled continuously, the rollover-versus-new-instrument question is
        // a product-level policy attribute (FR-513) and not something this projector may
        // infer.
        ContractTerms sixMonthLimit = ContractTerms.of(Money.inr("5000000"), ONE_PERCENT_MONTHLY,
            6, 12, DISBURSEMENT, FIRST_DUE, DayCountConvention.ACT_365F, ScheduleShape.REVOLVING,
            RateType.FLOATING);

        assertThat(projector.renewalDate(sixMonthLimit)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(projector.feeOverRenewal(sixMonthLimit, CaseFixtures.feeReceived("50000"))
            .renewalPeriods()).isEqualTo(6);
        ProjectionResult result = projector.project(sixMonthLimit, CaseFixtures.feeReceived("50000"));
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("4950000.00"));
    }
}
