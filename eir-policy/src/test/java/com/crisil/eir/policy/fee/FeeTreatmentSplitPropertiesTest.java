package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;

/**
 * FR-205 as properties: the split never leaks money and never invents a limb.
 *
 * <p>These are relations rather than figures, which is why they are legitimate
 * alongside the hand-worked fixtures in {@link FeeTreatmentRulesTest}. Nothing here
 * asserts what a split <em>is</em> — that would be taking the expected value from
 * the code under test — only that whatever it is reconstitutes the whole and stays
 * inside it. The hand arithmetic pins the values; this pins the invariants of the
 * arithmetic across a range no fixture set would cover.
 *
 * <p>The generated share scale is 12 decimal places deliberately. The EIR limb is
 * {@code fee x retainedShare / feeShare} rounded to 28 significant digits, so for
 * the rounded limb to exceed the whole fee the ratio would have to sit within
 * 5e-28 of one. With both shares at 12 decimal places and the disproportion test
 * requiring a strict inequality, the closest the ratio can come to one is of the
 * order of 1e-12 — eight orders of magnitude of headroom, so the "no limb exceeds
 * the whole" property is a real claim about the rule and not a claim about the
 * generator's luck.
 */
class FeeTreatmentSplitPropertiesTest {

    /**
     * The two limbs always sum back to the whole fee, digit for digit.
     *
     * <p>The property that makes the split safe to run over a portfolio. Rounding both
     * limbs independently at working precision leaves a residue of the order of 1e-22
     * of a rupee per contract; it never reaches presentation scale on any single
     * contract, which is precisely why it survives to the portfolio total and arrives
     * as a sub-ledger-to-GL break (invariant SL-1) that no individual contract
     * explains. Note that a failure here surfaces as an exception from
     * {@link FeeTreatmentSplit}'s own reconstitution guard rather than as a failed
     * assertion — the guard is the enforcement and this is the search for a case that
     * trips it.
     */
    @Property(tries = 2000, seed = "20270401")
    void theTwoLimbsAlwaysReconstituteTheWhole(
        @ForAll @BigRange(min = "0", max = "50000000") @Scale(6) BigDecimal fee,
        @ForAll @BigRange(min = "0.000001", max = "1") @Scale(12) BigDecimal feeShare,
        @ForAll @BigRange(min = "0", max = "1") @Scale(12) BigDecimal retainedShare) {

        FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
            Money.of(fee, Money.INR), feeShare, retainedShare);

        // Exact addition, no MathContext: this is the claim, so it must not be checked
        // through an operation that could round the discrepancy away.
        assertThat(split.toEir().amount().add(split.toArrangementServiceIncome().amount()))
            .as("EIR limb + arrangement service income limb = the whole fee, exactly")
            .isEqualByComparingTo(fee);
    }

    /**
     * Neither limb is ever negative, and neither ever exceeds the whole fee.
     *
     * <p>The property that pins the cap on an under-rewarded arranger. Without it, a
     * retained share above the fee share drives the ratio above one and produces an EIR
     * limb larger than the fee received against a negative arrangement service income
     * limb — a figure that is not merely wrong but unpublishable, since no arranger
     * reports negative service income on a fee it collected.
     */
    @Property(tries = 2000, seed = "20270401")
    void neitherLimbEscapesTheWholeFee(
        @ForAll @BigRange(min = "0", max = "50000000") @Scale(6) BigDecimal fee,
        @ForAll @BigRange(min = "0.000001", max = "1") @Scale(12) BigDecimal feeShare,
        @ForAll @BigRange(min = "0", max = "1") @Scale(12) BigDecimal retainedShare) {

        Money whole = Money.of(fee, Money.INR);
        FeeTreatmentSplit split =
            FeeTreatmentRules.bifurcateSyndicationFee(whole, feeShare, retainedShare);

        assertThat(split.toEir().isNegative()).as("the EIR limb is never negative").isFalse();
        assertThat(split.toArrangementServiceIncome().isNegative())
            .as("the arrangement service income limb is never negative")
            .isFalse();
        assertThat(split.toEir())
            .as("the EIR limb never exceeds the fee the arranger actually received")
            .isLessThanOrEqualTo(whole);
        assertThat(split.toArrangementServiceIncome())
            .as("nor does the excess")
            .isLessThanOrEqualTo(whole);
    }

    /**
     * A proportionate fee is wholly integral — the boundary, over the whole range.
     *
     * <p>Stated as a property because the boundary is where the policy is challenged and
     * the interesting part is that it holds for every pair of equal shares, not only for
     * the 0.25/0.25 in the fixture. A service-income limb of a few paise at equality
     * would be a rounding artefact presented as an accounting judgement.
     */
    @Property(tries = 1000, seed = "20270401")
    void equalSharesLeaveTheWholeFeeIntegral(
        @ForAll @BigRange(min = "0", max = "50000000") @Scale(6) BigDecimal fee,
        @ForAll @BigRange(min = "0.000001", max = "1") @Scale(12) BigDecimal share) {

        Money whole = Money.of(fee, Money.INR);
        FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(whole, share, share);

        assertThat(split.disproportionate()).isFalse();
        assertThat(split.toEir()).isEqualTo(whole);
        assertThat(split.toArrangementServiceIncome())
            .as("exactly zero at the boundary, with no residue to explain")
            .isEqualTo(Money.zero(Money.INR));
    }
}
