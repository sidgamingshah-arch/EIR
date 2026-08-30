package com.crisil.eir.application.onboarding;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ExternalScheduleProjector;
import com.crisil.eir.calc.projection.Instalment;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScratchProbeTest {

    @Test
    void deepDiscountWithATinyCoupon() {
        LocalDate start = LocalDate.of(2026, 4, 1);
        Money price = Money.inr("315241.70");
        List<Instalment> billed = new ArrayList<>();
        for (int p = 1; p <= 15; p++) {
            billed.add(Instalment.of(start.plusYears(p), p, Money.inr("10000"),
                FlowKind.INTEREST));
        }
        billed.add(Instalment.of(start.plusYears(15), 15, Money.inr("1000000"),
            FlowKind.PRINCIPAL));

        ContractTerms terms = ContractTerms.of(price,
            Rate.annualEffective(new BigDecimal("0.08")), 15, 1, start, start.plusYears(1),
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.STRUCTURED, RateType.FIXED);

        ProjectionResult projection =
            new ExternalScheduleProjector(billed).project(terms, List.of());

        OnboardingRequest request = OnboardingRequest.of("C-PROBE", InstrumentClass.INVESTMENT,
                MeasurementCategory.AMORTISED_COST,
                SppiAssessment.passed(start, "classification.committee"), terms,
                TierAssignmentSegment.WHOLESALE)
            .withRuleSetKey("ZCB", "IN-MUM")
            .with(TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE);

        EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
            request, projection, MaterialityTier.TIER_3);

        System.out.println("inception   = " + subject.inceptionAmount());
        System.out.println("redemption  = " + subject.redemptionAmount());
        System.out.println("coupon      = " + subject.contractualCouponTotal());
        System.out.println("accretion   = " + subject.accretion());
        System.out.println("share       = " + subject.accretionShareOfReturn());
        System.out.println("zeroCoupon  = " + subject.isZeroCoupon());
        System.out.println("deep        = " + subject.isDeepDiscount());
        System.out.println("forbidden   = " + subject.approximationForbidden());
        System.out.println("tenorMonths = " + subject.originalTenorMonths());
    }
}
