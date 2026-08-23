package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The projection input, and the three data defects it refuses to carry.
 *
 * <p>The two lives are the important one. {@code eirExpectedLifeMonths} (ACPIR 51)
 * and {@code eclHorizonMonths} (ACPIR 46(1)) are separate parameters and one is
 * never derived from the other; where they differ the reconciliation basis is
 * mandatory. Collapsing them into one field is the most common data-model defect in
 * this domain and it is the kind that cannot be fixed later: once a shorter
 * behavioural life has been silently used as the ECL horizon there is no record of
 * which of the two any published figure was computed on, so no recomputation can
 * distinguish a correct historical number from a wrong one. The check therefore sits
 * at construction, not at review.
 */
class ContractTermsTest {

    @Test
    @DisplayName("diverging lives without a basis are rejected")
    void divergingLivesNeedAStatedBasis() {
        // A 20-year mortgage with an 8-year behavioural life is an ordinary contract and
        // an entirely defensible one. What is not defensible is the same pair with no
        // recorded reason, because the difference is the first thing an auditor asks
        // about (3.5, FR-108).
        assertThatThrownBy(() -> case1().withLives(96, 240, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EIR expected life (ACPIR 51) is 96 months")
            .hasMessageContaining("ECL horizon (ACPIR 46(1)) is 240 months")
            .hasMessageContaining("neither parameter may be derived from the other");
        assertThatThrownBy(() -> case1().withLives(96, 240, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lifeDivergenceBasis is mandatory");
    }

    @Test
    @DisplayName("a half-stated pair is a divergence and needs a basis too")
    void aHalfStatedPairIsADivergence() {
        // Zero means "not stated". One side stated and the other not is precisely the
        // ingestion defect that produces a shorter life used as an ECL horizon, so it is
        // treated as a divergence rather than as a convenience.
        assertThatThrownBy(() -> case1().withLives(96, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lifeDivergenceBasis is mandatory");
        assertThatThrownBy(() -> case1().withLives(0, 240, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lifeDivergenceBasis is mandatory");
    }

    @Test
    @DisplayName("equal lives, or both unstated, need no basis")
    void equalLivesAreAcceptedWithoutABasis() {
        assertThat(case1().withLives(24, 24, null).eirExpectedLifeMonths()).isEqualTo(24);
        assertThat(case1().eirExpectedLifeMonths()).isZero();
        assertThat(case1().eclHorizonMonths()).isZero();
        assertThat(case1().withLives(96, 240, "CPR curve, product 4711, board-approved 2026-03")
            .lifeDivergenceBasis()).contains("CPR curve");
    }

    @Test
    @DisplayName("an unstated expected life resolves to the contractual term, which is the ACPIR 51 fallback")
    void unstatedExpectedLifeFallsBackToContractual() {
        // ACPIR 51 permits the fallback where reliable estimation genuinely fails, and it
        // is intended for rare cases. This type cannot tell an election from an omission,
        // which is why the justification is held on the contract and the projection only
        // records that the legs coincided.
        assertThat(case1().expectedLifePeriods()).isEqualTo(24);
        assertThat(case1().expectedLifeEqualsContractual()).isTrue();

        ContractTerms shorter = case1().withLives(12, 24, "behavioural life from the CPR curve");
        assertThat(shorter.expectedLifePeriods()).isEqualTo(12);
        assertThat(shorter.expectedLifeEqualsContractual()).isFalse();
        // Never beyond the contractual term: an expected life longer than the schedule
        // has nothing to project into.
        assertThat(case1().withLives(360, 24, "extension option assumed exercised")
            .expectedLifePeriods()).isEqualTo(24);
    }

    @Test
    @DisplayName("a rate quoted at a different frequency from the schedule is rejected")
    void rateFrequencyMustMatchTheSchedule() {
        // Interest is always computed from the periodic rate, never by dividing an annual
        // one (4.5). A quarterly rate on a monthly schedule is an annualisation
        // round-trip waiting to happen, so the pair is refused rather than coerced.
        assertThatThrownBy(() -> ContractTerms.of(Money.inr("1000000"),
            Rate.periodic(bd("0.03"), 4), 24, 12, DISBURSEMENT, FIRST_DUE,
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, RateType.FIXED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compounds 4 times a year but the schedule runs 12")
            .hasMessageContaining("never by dividing an annual rate");
    }

    @Test
    @DisplayName("due dates advance by whole periods from the first due date, and period 0 is the anchor")
    void dueDatesAdvanceByWholePeriods() {
        ContractTerms terms = case1();

        assertThat(terms.dueDate(0)).isEqualTo(DISBURSEMENT);
        assertThat(terms.dueDate(1)).isEqualTo(FIRST_DUE);
        assertThat(terms.dueDate(12)).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(terms.maturityDate()).isEqualTo(LocalDate.of(2028, 4, 1));
        assertThat(terms.monthsPerPeriod()).isEqualTo(1);
    }

    @Test
    @DisplayName("a frequency that does not divide 12 has no derivable due dates and says so")
    void incommensurableFrequencyRefusesToDeriveDates() {
        // Period-anniversary arithmetic has no defined answer at, say, five times a year,
        // so the schedule must be supplied externally (FR-102) rather than approximated.
        ContractTerms odd = ContractTerms.of(Money.inr("1000000"), Rate.periodic(bd("0.02"), 5),
            10, 5, DISBURSEMENT, FIRST_DUE, DayCountConvention.ACT_365F,
            ScheduleShape.STRUCTURED, RateType.FIXED);

        assertThatThrownBy(odd::monthsPerPeriod)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not divide 12")
            .hasMessageContaining("supply the billed schedule instead");
    }

    @Test
    @DisplayName("a moratorium must leave at least one repayment period")
    void moratoriumCannotConsumeTheWholeTerm() {
        assertThatThrownBy(() -> case1().withMoratorium(24, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("moratoriumPeriods must be in [0, 23]");
        assertThat(case1().withMoratorium(6, true).repaymentPeriods()).isEqualTo(18);
        assertThat(case1().withMoratorium(6, true).hasMoratorium()).isTrue();
        assertThat(case1().hasMoratorium()).isFalse();
    }

    @Test
    @DisplayName("the first due date must fall after disbursement")
    void firstDueDateMustFollowDisbursement() {
        assertThatThrownBy(() -> ContractTerms.of(Money.inr("1000000"),
            CaseFixtures.ONE_PERCENT_MONTHLY, 24, 12, DISBURSEMENT, DISBURSEMENT,
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, RateType.FIXED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be after disbursementDate");
    }

    @Test
    @DisplayName("optional features default to zero rather than to null arithmetic")
    void optionalFeaturesDefaultToZero() {
        assertThat(case1().balloonOrZero().isZero()).isTrue();
        assertThat(case1().residualOrZero().isZero()).isTrue();
        assertThat(case1().withBalloon(Money.inr("200000")).balloonOrZero().amount())
            .isEqualByComparingTo(bd("200000"));
        assertThat(case1().withResidualValue(Money.inr("150000")).residualOrZero().amount())
            .isEqualByComparingTo(bd("150000"));
        assertThat(case1().periodicRate()).isEqualByComparingTo(bd("0.01"));
    }
}
