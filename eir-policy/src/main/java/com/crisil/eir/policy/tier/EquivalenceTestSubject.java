package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What is being put to the tier gate: a proposed tier, the population it belongs to, and enough
 * of the return profile to apply FR-412's absolute refusal.
 *
 * <p>The proposed tier arrives from the tier assignment of FR-107 (03 § 10's table: original
 * tenor, product family, threshold). This type does not compute it and must not — the division
 * is deliberate. Assignment proposes; the gate disposes. A proposal of Tier 3 that this gate
 * refuses is not a defect in the assignment, it is the two rules doing their separate jobs.
 *
 * <h2>Why the return profile is derived from the cash amounts and not read off a flag</h2>
 *
 * <p>FR-412 refuses Tier 3 for zero-coupon and deep-discount instruments. The engine could take
 * a supplied {@code is_zero_coupon} attribute and trust it, and then the refusal would hold
 * exactly as far as the reference data does. 03 § 10.3 gives the mechanism instead of a flag —
 * "The entire return is accretion and the straight-line error compounds with tenor" — and the
 * mechanism is computable from figures the engine needs anyway. An instrument booked with a
 * coupon code but no coupon flows is caught here; a flag-based test would pass it.
 *
 * <p>So the discriminant is the <b>accretion share of total return</b>:
 *
 * <pre>
 *   accretion       = redemptionAmount - inceptionAmount
 *   total return    = accretion + contractualCouponTotal
 *   accretion share = accretion / total return
 * </pre>
 *
 * <p>Zero-coupon is the degenerate case where that share is exactly 1 — reference
 * {@code Case 9}'s 15-year instrument bought at 315,241.70 against a face of 1,000,000.00 has
 * 684,758.30 of discount and not one rupee of coupon. Deep discount is the case where the share
 * is high but not one, and it fails for the same reason at a slightly slower rate.
 *
 * <h2>The one policy figure</h2>
 *
 * <p>{@link #DEEP_DISCOUNT_ACCRETION_SHARE} has to be a number and neither 03 § 10.3 nor
 * {@code Case 9} publishes one — they publish the mechanism and an absolute prohibition. It is
 * therefore declared here as a policy default, public rather than private for the same reason
 * {@code InvariantChecks.ORDERING_EPSILON} is: it decides which instruments a refusal catches,
 * which makes it a Board matter and not an implementation detail. Zero-coupon does not depend
 * on it at all — that limb is a test for the absence of a coupon leg, so the absolute part of
 * FR-412 is absolute regardless of where the threshold is set.
 *
 * @param populationId           the Tier 3 population, keying the equivalence test on file
 * @param proposedTier           what FR-107 assignment proposed for this subject
 * @param originalTenorMonths    original tenor in months; reported, never used as a carve-out,
 *                               because FR-412 refuses "at any tenor"
 * @param inceptionAmount        cash paid or advanced at inception — a purchase price for an
 *                               investment, the principal advanced for a loan; always positive
 * @param redemptionAmount       the contractual amount receivable at maturity, excluding
 *                               coupon: face value for a discount instrument, principal
 *                               repayable for a loan
 * @param contractualCouponTotal lifetime contractual coupon or interest, excluding fees; zero
 *                               for a discount instrument, never negative
 */
public record EquivalenceTestSubject(
    String populationId,
    MaterialityTier proposedTier,
    int originalTenorMonths,
    Money inceptionAmount,
    Money redemptionAmount,
    Money contractualCouponTotal) {

    /**
     * The share of total return arising from accretion at or above which an instrument is a
     * deep-discount instrument for FR-412.
     *
     * <p>A policy default of one half, not a specification figure. The reasoning: the
     * straight-line error of 03 § 10.3 is an error in the <em>accretion</em> pattern, so it
     * scales with how much of the instrument's return is accretion. Where accretion is the
     * minority of return, a coupon leg carries the recognition profile and the straight-line
     * treatment of the remainder is a second-order effect on reported income; where accretion
     * is the majority, the shortcut is setting the shape of the income statement. One half is
     * the point those swap over, and it is the only boundary in the vicinity that can be
     * justified from the mechanism rather than from market usage of the phrase "deep discount".
     *
     * <p>It is deliberately set to catch more rather than fewer. The cost of a false positive
     * is Tier 2 measurement, which 03 § 10.2 already names as the consequence of the test being
     * out of date — more expensive and correct. The cost of a false negative is the Cambodia
     * failure mode.
     */
    public static final BigDecimal DEEP_DISCOUNT_ACCRETION_SHARE = new BigDecimal("0.50");

    public EquivalenceTestSubject {
        Objects.requireNonNull(populationId, "populationId");
        Objects.requireNonNull(proposedTier, "proposedTier");
        Objects.requireNonNull(inceptionAmount, "inceptionAmount");
        Objects.requireNonNull(redemptionAmount, "redemptionAmount");
        Objects.requireNonNull(contractualCouponTotal, "contractualCouponTotal");
        // Stripped for the same reason as EquivalenceTestRecord: this is the key the gate joins
        // on, and whitespace on one side of the join is a silent demotion to Tier 2.
        populationId = populationId.strip();
        if (populationId.isBlank()) {
            throw new IllegalArgumentException(
                "a tier-gate subject names its population; TG-1 is asserted per Tier 3"
                    + " population and an unnamed subject cannot be matched to a test on file");
        }
        if (originalTenorMonths < 1) {
            throw new IllegalArgumentException(
                "subject " + populationId + " has original tenor " + originalTenorMonths
                    + " months; tenor is what 03 § 10 assigns tiers by, so a tenor of zero is a"
                    + " missing attribute rather than a very short instrument");
        }
        if (!inceptionAmount.isPositive()) {
            throw new IllegalArgumentException(
                "subject " + populationId + " was acquired or advanced for "
                    + inceptionAmount + "; the accretion share is measured against what was"
                    + " paid, and nothing was");
        }
        if (redemptionAmount.isNegative()) {
            throw new IllegalArgumentException(
                "subject " + populationId + " redeems at " + redemptionAmount
                    + "; a negative contractual redemption is a sign-convention error in the"
                    + " feed, not an instrument");
        }
        if (contractualCouponTotal.isNegative()) {
            throw new IllegalArgumentException(
                "subject " + populationId + " carries lifetime coupon "
                    + contractualCouponTotal + "; a negative coupon total would make the"
                    + " accretion share exceed one and turn a coupon-bearing bond into a"
                    + " zero-coupon one");
        }
        // Currency coherence is checked here rather than left to Money.minus deep inside
        // accretion(). A three-field subject mixing currencies is a mapping defect and the
        // useful place to say so names the subject, not the arithmetic.
        requireSameCurrency(populationId, inceptionAmount, redemptionAmount, "redemption amount");
        requireSameCurrency(
            populationId, inceptionAmount, contractualCouponTotal, "contractual coupon total");
    }

    private static void requireSameCurrency(
        String populationId, Money reference, Money other, String what) {
        if (!reference.currency().equals(other.currency())) {
            throw new IllegalArgumentException(
                "subject " + populationId + " has inception amount in "
                    + reference.currency().getCurrencyCode() + " and " + what + " in "
                    + other.currency().getCurrencyCode()
                    + "; an accretion share across two currencies is not a figure anyone can"
                    + " reconcile");
        }
    }

    /** A discount instrument — T-bill, CP, CD, zero-coupon bond. No coupon leg at all. */
    public static EquivalenceTestSubject discountInstrument(
        String populationId,
        MaterialityTier proposedTier,
        int originalTenorMonths,
        Money pricePaid,
        Money faceValue) {
        return new EquivalenceTestSubject(
            populationId, proposedTier, originalTenorMonths, pricePaid, faceValue,
            Money.zero(pricePaid.currency()));
    }

    /** A coupon-bearing exposure advanced and repayable at par — the WCDL and temporary-OD shape. */
    public static EquivalenceTestSubject couponBearingAtPar(
        String populationId,
        MaterialityTier proposedTier,
        int originalTenorMonths,
        Money principal,
        Money lifetimeInterest) {
        return new EquivalenceTestSubject(
            populationId, proposedTier, originalTenorMonths, principal, principal,
            lifetimeInterest);
    }

    /** Discount to be accreted over the life; negative for an instrument acquired at a premium. */
    public Money accretion() {
        return redemptionAmount.minus(inceptionAmount);
    }

    /** Accretion plus coupon: everything the instrument returns over and above what was paid. */
    public Money totalReturn() {
        return accretion().plus(contractualCouponTotal);
    }

    /**
     * Whether there is no coupon leg — the limb of FR-412 that is a refusal rather than a
     * threshold.
     *
     * <p>Compared with {@link Money#isZero()} and so by {@code compareTo} on the underlying
     * {@link BigDecimal}: a coupon total of {@code 0.00} and one of {@code 0} are the same fact
     * and {@code equals} on scaled decimals would disagree.
     *
     * <p>This catches the degenerate no-coupon-at-par case too — an interest-free advance
     * repayable at face has no return to mis-accrete, so refusing it Tier 3 buys nothing except
     * consistency. It is refused anyway, because FR-412 is a refusal and the moment it acquires
     * an exception for "well, this one is harmless" it becomes a threshold with an
     * undocumented boundary, which is what 03 § 10.3 exists to forbid.
     */
    public boolean isZeroCoupon() {
        return contractualCouponTotal.isZero();
    }

    /**
     * The accretion share of total return, or zero where the share is not meaningful.
     *
     * <p>Two guards, both of which are real instruments rather than defensive noise:
     *
     * <ul>
     *   <li>Accretion at or below zero — an instrument acquired at a premium. The premium
     *       amortises against the coupon and there is no discount to front-load, so the share
     *       is reported as zero rather than as a negative number that would sort below every
     *       threshold anyway.
     *   <li>Total return at or below zero — a premium large enough to exceed the whole coupon
     *       leg, so the instrument loses money contractually. The ratio is then either negative
     *       or a division by zero, and neither is the FR-412 question. Such an instrument is a
     *       premium case, not a discount case.
     * </ul>
     */
    public BigDecimal accretionShareOfReturn() {
        Money accretion = accretion();
        Money total = totalReturn();
        if (!accretion.isPositive() || !total.isPositive()) {
            return BigDecimal.ZERO;
        }
        return accretion.amount().divide(total.amount(), Precision.WORKING);
    }

    /**
     * Whether accretion dominates the return badly enough to make this a deep-discount
     * instrument for FR-412, per {@link #DEEP_DISCOUNT_ACCRETION_SHARE}.
     */
    public boolean isDeepDiscount() {
        return accretionShareOfReturn().compareTo(DEEP_DISCOUNT_ACCRETION_SHARE) >= 0;
    }

    /**
     * Whether 03 § 10.3 forbids approximation for this subject outright — the FR-412 test.
     *
     * <p>Note what is <em>not</em> here: tenor. FR-412 says "at any tenor" and 03 § 10.3 says
     * "at any tenor", and a 91-day T-bill is refused on exactly the same ground as
     * {@code Case 9}'s 15-year zero-coupon bond. See {@link EquivalenceTestGate} for why that
     * reading is taken even though 03 § 10's Tier 3 table names T-bills, CP and CD by hand.
     */
    public boolean approximationForbidden() {
        return isZeroCoupon() || isDeepDiscount();
    }

    /** The clause of FR-412 this subject falls foul of, for the recorded tier basis. */
    public String forbiddenApproximationGround() {
        if (isZeroCoupon()) {
            return "zero-coupon: the entire return of " + totalReturn().atPresentationScale()
                + " is accretion";
        }
        if (isDeepDiscount()) {
            return "deep-discount: " + accretionShareOfReturn().setScale(4, Precision.MODE)
                .toPlainString() + " of total return is accretion, at or above the "
                + DEEP_DISCOUNT_ACCRETION_SHARE.toPlainString() + " policy share";
        }
        return "";
    }
}
