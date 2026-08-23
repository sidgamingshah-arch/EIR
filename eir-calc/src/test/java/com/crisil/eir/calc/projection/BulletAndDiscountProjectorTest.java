package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two single-settlement shapes, quoted the way their markets quote them.
 *
 * <p>On a {@link ScheduleShape#BULLET} the principal is what goes out and the
 * redemption is derived; on a {@link ScheduleShape#DISCOUNT_INSTRUMENT} the principal
 * <em>is</em> the face value at maturity and the price is derived by discounting it
 * at the contractual yield. Forcing one convention on both would push the conversion
 * into every caller, which is where it would eventually be got wrong.
 */
class BulletAndDiscountProjectorTest {

    private final BulletProjector bullet = new BulletProjector();
    private final DiscountInstrumentProjector discount = new DiscountInstrumentProjector();

    /** Case 9's instrument: 1,000,000 of face at an 8% yield, fifteen years, zero coupon. */
    private static ContractTerms fifteenYearZeroCoupon() {
        return new ContractTerms(Money.inr("1000000"), Rate.periodic(bd("0.08"), 1), 15, 1,
            DISBURSEMENT, LocalDate.of(2027, 4, 1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.DISCOUNT_INSTRUMENT, RateType.FIXED, Money.INR,
            0, false, null, null, null, 0, 0, null);
    }

    @Test
    @DisplayName("the zero-coupon bullet redeems at 1,816,696.70 and carries the accrual unrounded")
    void zeroCouponRedemption() {
        ContractTerms terms = shaped(ScheduleShape.BULLET, 60, RateType.FIXED);

        // P * ((1+i)^n - 1), compounded rather than pro-rated: 1,000,000 at 1% a month for
        // sixty months accrues 816,696.698564 of interest.
        assertThat(bullet.accruedInterest(terms).amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("816696.698564"));
        assertThat(bullet.redemptionAmount(terms).atPresentationScale().amount())
            .isEqualByComparingTo(bd("1816696.70"));
        // Deliberately not rounded to paise in the projection. There is no schedule of
        // billed amounts on a zero-coupon, only one terminal settlement, and rounding it
        // here would put 1.3e-11 into the solved rate — visible in the twelfth decimal
        // place, which is exactly where reference case 7's calibration instrument is
        // asserted.
        assertThat(bullet.redemptionAmount(terms).amount().scale()).isGreaterThan(2);
    }

    @Test
    @DisplayName("principal and accrued interest are emitted as two flows on one date")
    void principalAndInterestAreSplitAtMaturity() {
        ContractTerms terms = shaped(ScheduleShape.BULLET, 60, RateType.FIXED);

        List<CashFlow> future = bullet.project(terms, case1Fees()).contractual().future();

        // Discounting is identical either way — kind never affects it — but the
        // contractual interest leg and invariant INV-3 both need the split, and a
        // projector that combined them would force every consumer to re-derive it.
        assertThat(future).hasSize(2);
        assertThat(future).extracting(CashFlow::periodIndex).containsOnly(60);
        assertThat(future).extracting(CashFlow::date).containsOnly(terms.maturityDate());
        assertThat(future).extracting(CashFlow::kind)
            .containsExactlyInAnyOrder(FlowKind.PRINCIPAL, FlowKind.INTEREST);
        assertThat(future.stream().map(flow -> flow.amount().amount()).reduce(bd("0"), BigDecimal::add))
            .isEqualByComparingTo(bullet.redemptionAmount(terms).amount());
    }

    @Test
    @DisplayName("a single terminal flow cannot be periodically indexed, so the bullet is actual-dated")
    void bulletFallsBackToActualDating() {
        ProjectionResult result = bullet.project(shaped(ScheduleShape.BULLET, 60, RateType.FIXED),
            case1Fees());

        assertThat(result.recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND));
        // Which means the rate solved over this vector is an annual effective rate, and a
        // caller rolling the contractual leg must restate the monthly contractual rate
        // through the same seam rather than pairing 1% a month with a year fraction.
        assertThat(ConventionSelector.rateUnder(result.recommendedConvention(),
            CaseFixtures.ONE_PERCENT_MONTHLY).periodsPerYear()).isEqualTo(1);
    }

    @Test
    @DisplayName("an interest-free bullet repays the principal alone")
    void interestFreeBullet() {
        // Not a degenerate case to reject: interest-free intra-group advances exist, and
        // their EIR is zero only if no fee is integral.
        ContractTerms free = ContractTerms.of(Money.inr("500000"), Rate.monthly(bd("0")), 6, 12,
            DISBURSEMENT, FIRST_DUE, DayCountConvention.THIRTY_360_BOND, ScheduleShape.BULLET,
            RateType.FIXED);

        assertThat(bullet.accruedInterest(free).isZero()).isTrue();
        assertThat(bullet.redemptionAmount(free).amount()).isEqualByComparingTo(bd("500000"));
        // No interest flow at all rather than a zero one: a zero-amount flow in a vector
        // is a line in every reconciliation that has to be explained.
        assertThat(bullet.project(free, List.of()).contractual().future()).hasSize(1);
    }

    @Test
    @DisplayName("a bullet that services interest is a different shape and is declined")
    void aServicedBulletIsADifferentShape() {
        // Servicing interest changes every discount factor in the vector, so the
        // distinction is contractual rather than cosmetic.
        assertThat(bullet.supports(shaped(ScheduleShape.BULLET, 60, RateType.FIXED))).isTrue();
        assertThat(bullet.supports(shaped(ScheduleShape.INTEREST_ONLY_BULLET, 60, RateType.FIXED)))
            .isFalse();
        assertThat(new InterestOnlyBulletProjector()
            .supports(shaped(ScheduleShape.INTEREST_ONLY_BULLET, 60, RateType.FIXED))).isTrue();
    }

    @Test
    @DisplayName("the interest-only bullet bills P*i every period and repays principal with the last coupon")
    void interestOnlyBulletCoupons() {
        InterestOnlyBulletProjector projector = new InterestOnlyBulletProjector();
        ContractTerms terms = shaped(ScheduleShape.INTEREST_ONLY_BULLET, 60, RateType.FIXED);

        ProjectionResult result = projector.project(terms, CaseFixtures.feeReceived("50000"));
        List<CashFlow> future = result.contractual().future();

        // Reference case 7's second stress instrument: 59 coupons of 10,000, then
        // 1,010,000 at month 60 against 950,000 advanced.
        assertThat(projector.periodicInterest(terms).amount()).isEqualByComparingTo(bd("10000.00"));
        assertThat(future).hasSize(61);
        assertThat(future.stream().filter(flow -> flow.kind() == FlowKind.INTEREST).toList()).hasSize(60);
        assertThat(future.stream().filter(flow -> flow.periodIndex() == 60)
            .map(flow -> flow.amount().amount()).reduce(bd("0"), BigDecimal::add))
            .isEqualByComparingTo(bd("1010000.00"));
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("950000.00"));
        // The principal does not amortise, so every instalment is identical and there is
        // no residue to resolve.
        assertThat(result.recommendedConvention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
    }

    @Test
    @DisplayName("the Case 9 zero-coupon bond prices at 315,241.70 for 1,000,000 of face")
    void discountInstrumentPrice() {
        ContractTerms bond = fifteenYearZeroCoupon();

        assertThat(discount.faceValue(bond).amount()).isEqualByComparingTo(bd("1000000.00"));
        assertThat(discount.issuePrice(bond).amount()).isEqualByComparingTo(bd("315241.70"));
        assertThat(discount.discountToAccrete(bond).amount()).isEqualByComparingTo(bd("684758.30"));
        // The price is cash actually paid, so it is rounded once to presentation scale.
        // The sub-paise difference from the exact present value is a real feature of a
        // traded instrument: the solver recovers the yield the trade struck rather than
        // the one the calculator wanted.
        assertThat(discount.issuePrice(bond).amount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("the carrying amount opens at the price, not at the face value")
    void discountInstrumentOpensAtThePrice() {
        ProjectionResult result = discount.project(fifteenYearZeroCoupon(), List.of());

        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("315241.70"));
        assertThat(result.netCashAtInception().amount()).isEqualByComparingTo(bd("-315241.70"));
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
        assertThat(result.contractual().future()).hasSize(1);
        assertThat(result.contractual().future().get(0).kind()).isEqualTo(FlowKind.PRINCIPAL);
        assertThat(result.contractual().future().get(0).date()).isEqualTo(LocalDate.of(2041, 4, 1));
        // Nothing to truncate behaviourally on a single-flow instrument, so the legs
        // coincide by construction rather than by election.
        assertThat(result.expected()).isEqualTo(result.contractual());
    }

    @Test
    @DisplayName("straight-line accretion on the same bond is 81% wrong in year one and ties to the paisa over life")
    void theWedgeAgainstStraightLineAccretion() {
        // Calculation specification 10.3: approximation is never permitted on a discount
        // instrument at any tenor, because the entire return is accretion. Year one under
        // the EIR is price * 8% = 25,219.34; straight-line is 684,758.30 / 15 =
        // 45,650.55 — an overstatement of 81%. Over fifteen years the two totals agree
        // exactly, which is what makes the error invisible to any control that looks at
        // cumulative figures and material in every single reporting period.
        ContractTerms bond = fifteenYearZeroCoupon();
        Money straightLinePerYear = discount.discountToAccrete(bond)
            .dividedBy(bd("15")).atPresentationScale();
        Money eirYearOne = discount.issuePrice(bond).times(bd("0.08")).atPresentationScale();

        assertThat(straightLinePerYear.amount()).isEqualByComparingTo(bd("45650.55"));
        assertThat(eirYearOne.amount()).isEqualByComparingTo(bd("25219.34"));
        assertThat(straightLinePerYear.amount())
            .as("straight-line overstates year one by more than eighty per cent")
            .isGreaterThan(eirYearOne.amount().multiply(bd("1.8")));
    }

    @Test
    @DisplayName("a short-tenor bill prices under the convention its vector licenses")
    void shortTenorPricing() {
        // Day-count discipline bites inversely to tenor: on a 20-year mortgage a
        // convention error is noise, on a 91-day T-bill a three-day error is a materially
        // wrong rate. Indian convention is actual/365 for money market (3.9).
        ContractTerms bill = new ContractTerms(Money.inr("1000000"), Rate.periodic(bd("0.0675"), 1),
            1, 1, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1), DayCountConvention.ACT_365F,
            ScheduleShape.DISCOUNT_INSTRUMENT, RateType.FIXED, Money.INR,
            0, false, null, null, null, 0, 0, null);

        ProjectionResult result = discount.project(bill, List.of());
        BigDecimal tau = DayCountConvention.ACT_365F.yearFraction(
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1));

        assertThat(result.recommendedConvention())
            .isEqualTo(new TimeConvention.ActualDate(DayCountConvention.ACT_365F));
        assertThat(tau).isEqualByComparingTo(bd("91").divide(bd("365"), Precision.WORKING));
        assertThat(discount.issuePrice(bill).amount()).isEqualByComparingTo(
            Money.inr("1000000").times(Precision.discountFactor(bd("0.0675"), tau))
                .atPresentationScale().amount());
    }
}
