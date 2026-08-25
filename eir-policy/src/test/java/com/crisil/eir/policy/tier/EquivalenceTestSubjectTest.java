package com.crisil.eir.policy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The FR-412 discriminant: which return profiles are refused Tier 3 at any tenor.
 *
 * <p>The Case 9 figures below are the published golden fixture of
 * {@code docs/reference-cases/case-09-straight-line-vs-eir.md}, which states of itself: "If the
 * engine disagrees with these figures, the engine is wrong." They are quoted, not computed here
 * and never taken from running this code.
 */
class EquivalenceTestSubjectTest {

    /**
     * Case 9's instrument: a 15-year zero-coupon at 8%, bought at 315,241.70 against a face of
     * 1,000,000.00, with 684,758.30 of discount to accrete and no coupon at all.
     */
    private static EquivalenceTestSubject caseNine(MaterialityTier proposedTier) {
        return EquivalenceTestSubject.discountInstrument(
            "INV-ZCB-15Y", proposedTier, 180, Money.inr("315241.70"), Money.inr("1000000.00"));
    }

    @Nested
    @DisplayName("Case 9 — the 15-year zero-coupon, and why FR-412 is absolute")
    class CaseNine {

        @Test
        @DisplayName("the discount to accrete is the published 684,758.30")
        void discountMatchesTheFixture() {
            // Face 1,000,000.00 less purchase price 315,241.70. Case 9 states the discount as
            // 684,758.30, and states that BOTH methods total exactly that over the life — which
            // is the whole reason the error is invisible to a cumulative control.
            assertThat(caseNine(MaterialityTier.TIER_3).accretion().atPresentationScale())
                .as("Case 9: total discount to accrete")
                .isEqualTo(Money.inr("684758.30"));
        }

        @Test
        @DisplayName("straight line over 15 years is the published 45,650.55 a year")
        void straightLineMatchesTheFixture() {
            // Case 9's "Straight-line accretion per year". Asserted because it is the figure
            // FR-411's Tier 3 shortcut would actually post, and Case 9 sets it against EIR
            // accretion of 25,219.34 in year one and 74,074.07 in year fifteen: straight line
            // overstates year-one income by 81.0% and understates the final year by 38.4%.
            Money perYear = caseNine(MaterialityTier.TIER_3)
                .accretion().dividedBy(BigDecimal.valueOf(15)).atPresentationScale();
            assertThat(perYear)
                .as("Case 9: straight-line accretion per year")
                .isEqualTo(Money.inr("45650.55"));
        }

        @Test
        @DisplayName("the entire return is accretion, which is 03 § 10.3's stated mechanism")
        void entireReturnIsAccretion() {
            EquivalenceTestSubject subject = caseNine(MaterialityTier.TIER_3);
            assertThat(subject.totalReturn().atPresentationScale())
                .isEqualTo(Money.inr("684758.30"));
            assertThat(subject.accretionShareOfReturn())
                .as("03 § 10.3: 'The entire return is accretion'")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(subject.isZeroCoupon()).isTrue();
            assertThat(subject.approximationForbidden()).isTrue();
            assertThat(subject.forbiddenApproximationGround()).contains("zero-coupon");
        }
    }

    @Nested
    @DisplayName("zero coupon — a refusal, not a threshold")
    class ZeroCoupon {

        @Test
        @DisplayName("a 91-day T-bill is zero-coupon too, and refused on the same ground")
        void treasuryBillIsRefused() {
            // 03 § 10's Tier 3 row names T-bills by hand; 03 § 10.3 and FR-412 refuse
            // zero-coupon "at any tenor". They cannot both be applied, and the tension is
            // resolved in favour of the refusal — see EquivalenceTestGate's class comment for
            // the reasoning. Three months of tenor is not a mitigant here by construction:
            // every Tier 3 population is inside twelve months, so a short-tenor carve-out would
            // empty FR-412 completely.
            EquivalenceTestSubject bill = EquivalenceTestSubject.discountInstrument(
                "GSEC-TBILL-91D", MaterialityTier.TIER_3, 3,
                Money.inr("98.32"), Money.inr("100.00"));
            assertThat(bill.isZeroCoupon()).isTrue();
            assertThat(bill.approximationForbidden()).isTrue();
        }

        @Test
        @DisplayName("no coupon and no discount is refused as well, deliberately")
        void interestFreeAtParIsRefused() {
            // An interest-free advance repayable at face has no return to mis-accrete, so
            // refusing it Tier 3 buys nothing but consistency. It is refused anyway: the moment
            // FR-412 acquires an exception for "this one is harmless" it stops being a refusal
            // and becomes a threshold with an undocumented boundary, which is the practice
            // 03 § 10.2 exists to forbid.
            EquivalenceTestSubject interestFree = EquivalenceTestSubject.couponBearingAtPar(
                "STAFF-INTEREST-FREE", MaterialityTier.TIER_3, 12,
                Money.inr("100000.00"), Money.inr("0.00"));
            assertThat(interestFree.isZeroCoupon()).isTrue();
            assertThat(interestFree.approximationForbidden()).isTrue();
        }

        @Test
        @DisplayName("a coupon total of 0.00 and one of 0 are the same fact")
        void zeroIsComparedNotEqualled() {
            // Money compares by compareTo throughout, so a feed that sends 0 and one that sends
            // 0.0000 reach the same answer. An equals-based test on scaled BigDecimal would let
            // the scale of the incoming figure decide whether FR-412 applies.
            EquivalenceTestSubject scaled = new EquivalenceTestSubject(
                "P", MaterialityTier.TIER_3, 6, Money.inr("100"), Money.inr("100"),
                Money.inr("0.0000"));
            assertThat(scaled.isZeroCoupon()).isTrue();
        }
    }

    @Nested
    @DisplayName("deep discount — the accretion share of total return")
    class DeepDiscount {

        @Test
        @DisplayName("accretion dominating a coupon leg is refused")
        void accretionDominatingIsRefused() {
            // Accretion 200,000 against coupon 150,000: total return 350,000 and an accretion
            // share of 200,000 / 350,000, which is between 0.57 and 0.58 — comfortably above
            // the 0.50 policy share.
            EquivalenceTestSubject deep = new EquivalenceTestSubject(
                "CORP-DEEP-DISCOUNT", MaterialityTier.TIER_3, 12,
                Money.inr("800000.00"), Money.inr("1000000.00"), Money.inr("150000.00"));
            assertThat(deep.accretionShareOfReturn())
                .isGreaterThan(new BigDecimal("0.57"))
                .isLessThan(new BigDecimal("0.58"));
            assertThat(deep.isDeepDiscount()).isTrue();
            assertThat(deep.forbiddenApproximationGround()).contains("deep-discount");
        }

        @Test
        @DisplayName("a coupon-dominated instrument is not deep discount")
        void couponDominatedIsPermitted() {
            // Accretion 100,000 against coupon 150,000: total 250,000, share exactly 0.40.
            EquivalenceTestSubject shallow = new EquivalenceTestSubject(
                "CORP-SMALL-DISCOUNT", MaterialityTier.TIER_3, 12,
                Money.inr("900000.00"), Money.inr("1000000.00"), Money.inr("150000.00"));
            assertThat(shallow.accretionShareOfReturn())
                .isEqualByComparingTo(new BigDecimal("0.40"));
            assertThat(shallow.isDeepDiscount()).isFalse();
            assertThat(shallow.approximationForbidden()).isFalse();
            assertThat(shallow.forbiddenApproximationGround()).isEmpty();
        }

        @Test
        @DisplayName("the 0.50 share is inclusive, and one part in 200,000 below it is not")
        void shareBoundaryIsPinnedBothSides() {
            // Exactly half: accretion 100,000, coupon 100,000, total 200,000, share 0.50.
            EquivalenceTestSubject onTheBoundary = new EquivalenceTestSubject(
                "ON-BOUNDARY", MaterialityTier.TIER_3, 12,
                Money.inr("900000.00"), Money.inr("1000000.00"), Money.inr("100000.00"));
            assertThat(onTheBoundary.accretionShareOfReturn())
                .isEqualByComparingTo(new BigDecimal("0.50"));
            assertThat(onTheBoundary.isDeepDiscount())
                .as("at or above the policy share, so refused")
                .isTrue();

            // Accretion 99,999, coupon 100,001, total 200,000: share 0.499995 exactly.
            EquivalenceTestSubject justBelow = new EquivalenceTestSubject(
                "JUST-BELOW", MaterialityTier.TIER_3, 12,
                Money.inr("900001.00"), Money.inr("1000000.00"), Money.inr("100001.00"));
            assertThat(justBelow.accretionShareOfReturn())
                .isEqualByComparingTo(new BigDecimal("0.499995"));
            assertThat(justBelow.isDeepDiscount()).isFalse();
        }

        @Test
        @DisplayName("an instrument bought at a premium is not a discount instrument")
        void premiumIsNotDeepDiscount() {
            // Bought at 1,050,000 against a face of 1,000,000: accretion is negative 50,000.
            // The premium amortises against the coupon and there is no discount to front-load,
            // so the share is reported as nil rather than as a negative number.
            EquivalenceTestSubject premium = new EquivalenceTestSubject(
                "CORP-PREMIUM", MaterialityTier.TIER_3, 12,
                Money.inr("1050000.00"), Money.inr("1000000.00"), Money.inr("200000.00"));
            assertThat(premium.accretion().atPresentationScale())
                .isEqualTo(Money.inr("-50000.00"));
            assertThat(premium.accretionShareOfReturn()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(premium.approximationForbidden()).isFalse();
        }

        @Test
        @DisplayName("a premium exceeding the whole coupon leg is still a premium case")
        void premiumLargerThanCouponIsNotDeepDiscount() {
            // Total return negative: the ratio is either negative or a division by zero, and
            // neither is the FR-412 question. Guarded so the answer is "not a discount
            // instrument" rather than an ArithmeticException from deep inside the share.
            EquivalenceTestSubject upsideDown = new EquivalenceTestSubject(
                "CORP-DEEP-PREMIUM", MaterialityTier.TIER_3, 12,
                Money.inr("1300000.00"), Money.inr("1000000.00"), Money.inr("200000.00"));
            assertThat(upsideDown.totalReturn().atPresentationScale())
                .isEqualTo(Money.inr("-100000.00"));
            assertThat(upsideDown.accretionShareOfReturn()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(upsideDown.approximationForbidden()).isFalse();
        }

        @Test
        @DisplayName("a WCDL at par with interest is the ordinary Tier 3 shape and passes FR-412")
        void couponBearingAtParPasses() {
            EquivalenceTestSubject wcdl = EquivalenceTestSubject.couponBearingAtPar(
                "WCDL-2027-Q1", MaterialityTier.TIER_3, 6,
                Money.inr("5000000.00"), Money.inr("225000.00"));
            assertThat(wcdl.accretion().isZero()).isTrue();
            assertThat(wcdl.accretionShareOfReturn()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(wcdl.approximationForbidden()).isFalse();
        }
    }

    @Nested
    @DisplayName("subjects the gate refuses to be handed")
    class Validation {

        @Test
        @DisplayName("a zero tenor is a missing attribute, not a very short instrument")
        void zeroTenorRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EquivalenceTestSubject.couponBearingAtPar(
                    "P", MaterialityTier.TIER_3, 0, Money.inr("100"), Money.inr("10")))
                .withMessageContaining("missing attribute");
        }

        @Test
        @DisplayName("nothing advanced means no accretion share to measure")
        void zeroInceptionAmountRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EquivalenceTestSubject.discountInstrument(
                    "P", MaterialityTier.TIER_3, 3, Money.inr("0"), Money.inr("100")))
                .withMessageContaining("nothing was");
        }

        @Test
        @DisplayName("a negative coupon total would turn a bond into a zero-coupon bond")
        void negativeCouponRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestSubject(
                    "P", MaterialityTier.TIER_3, 12, Money.inr("100"), Money.inr("100"),
                    Money.inr("-1")))
                .withMessageContaining("accretion share exceed one");
        }

        @Test
        @DisplayName("a negative redemption is a sign-convention error in the feed")
        void negativeRedemptionRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestSubject(
                    "P", MaterialityTier.TIER_3, 12, Money.inr("100"), Money.inr("-100"),
                    Money.inr("5")))
                .withMessageContaining("sign-convention error");
        }

        @Test
        @DisplayName("mixed currencies are named at construction, not inside the arithmetic")
        void currencyMismatchRefused() {
            // Money.minus would raise this anyway, several frames deeper and without naming the
            // subject. An accretion share computed across two currencies is not a figure anyone
            // could reconcile.
            Currency usd = Currency.getInstance("USD");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestSubject(
                    "P", MaterialityTier.TIER_3, 12, Money.inr("100"),
                    Money.of("100", usd), Money.inr("5")))
                .withMessageContaining("across two currencies");
        }

        @Test
        @DisplayName("an unnamed population cannot be matched to a test on file")
        void blankPopulationRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> EquivalenceTestSubject.couponBearingAtPar(
                    " ", MaterialityTier.TIER_3, 12, Money.inr("100"), Money.inr("10")))
                .withMessageContaining("names its population");
        }
    }
}
