package com.crisil.eir.calc.routing;

import static com.crisil.eir.calc.routing.RoutingFixtures.CONTRACTUAL_BALANCE_AT_MONTH_12;
import static com.crisil.eir.calc.routing.RoutingFixtures.GCA_AT_MONTH_12;
import static com.crisil.eir.calc.routing.RoutingFixtures.MODIFICATION_DATE;
import static com.crisil.eir.calc.routing.RoutingFixtures.MONTHLY;
import static com.crisil.eir.calc.routing.RoutingFixtures.ORIGINAL_EIR;
import static com.crisil.eir.calc.routing.RoutingFixtures.bd;
import static com.crisil.eir.calc.routing.RoutingFixtures.level;
import static com.crisil.eir.calc.routing.RoutingFixtures.reProfiled;
import static com.crisil.eir.calc.routing.RoutingFixtures.remainingOriginal;
import static com.crisil.eir.calc.routing.RoutingFixtures.singleFlow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The 10% test as evidence, and the asymmetry that decides whether it is also the answer
 * (specification 6.4, FR-511).
 *
 * <p>IFRS 9 B3.3.6 sets the bright line for financial <em>liabilities</em>. For assets
 * there is no equivalent: the IASB's February 2025 tentative direction is toward a
 * principles-based qualitative assessment whose outcome cannot be determined by a
 * quantitative test alone, and ACPIR 78–81 govern stage migration on restructuring while
 * saying nothing about whether the original EIR survives. So the same ratio is
 * authoritative on one side of the balance sheet and evidential on the other, and the
 * side is a required parameter rather than a default.
 *
 * <p>The 10.02% case below is the one that matters. Silently derecognising a corporate
 * loan on a 10.02% test result is not a feature: the ratio is a function of the
 * projection assumptions behind the revised flows — a prepayment curve, a deferred DCCO,
 * a tranche re-profile — and moving one of those by a rounding's worth moves the result
 * across the line while changing nothing about the economics.
 */
class ModificationTestTest {

    @Test
    @DisplayName("both legs discount at the original EIR, and they ARE the catch-up's two balances")
    void thePresentValueLegsTieToTheRestatement() {
        Money pvRemaining = ModificationTest.presentValueAtModificationDate(
            ORIGINAL_EIR, remainingOriginal(), MONTHLY);
        Money pvRevised =
            ModificationTest.presentValueAtModificationDate(ORIGINAL_EIR, reProfiled(), MONTHLY);

        // The remaining original flows discount to the carrying amount the ledger already
        // holds, and the revised flows to the balance a B5.4.6 restatement would move it to.
        // That is not a coincidence to be checked: both routines discount the same vectors at
        // the same rate through the same code, which is what stops an event being tested on
        // one basis and then restated on another.
        assertThat(pvRemaining.atPresentationScale().amount()).isEqualByComparingTo(bd("528407.32"));
        assertThat(pvRemaining.atPresentationScale().amount())
            .isEqualByComparingTo(GCA_AT_MONTH_12.amount());
        assertThat(pvRevised.atPresentationScale().amount()).isEqualByComparingTo(bd("527779.90"));
        assertThat(pvRevised.minus(pvRemaining).atPresentationScale().amount())
            .isEqualByComparingTo(bd("-627.42"));
    }

    @ParameterizedTest
    @EnumSource(InstrumentSide.class)
    @DisplayName("reference case 3 is a 0.12% difference: not substantial on either side")
    void referenceCaseThreeIsNotSubstantial(InstrumentSide side) {
        ModificationTestResult result = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, side, ReviewBand.standard());

        // A six-month tenor extension at the same contractual rate moves the present value
        // by 0.119% — nowhere near the line, and clear of the band, so the engine is entitled
        // to conclude on either side. Which is why case 3 is a catch-up and not a
        // derecognition.
        assertThat(result.ratio().setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("0.001187"));
        assertThat(result.breachesThreshold()).isFalse();
        assertThat(result.withinReviewBand()).isFalse();
        assertThat(result.conclusion()).isEqualTo(ModificationConclusion.NOT_SUBSTANTIAL);
        assertThat(result.conclusion().isDecided()).isTrue();
        assertThat(result.conclusion().mechanism()).isEqualTo(Mechanism.CATCH_UP);
        assertThat(result.requiresApproval()).isFalse();
        assertThat(result.anyTriggerFired()).isFalse();
        assertThat(result.distanceFromThreshold().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("-0.098813"));
    }

    @Test
    @DisplayName("10.02% on an ASSET does NOT silently derecognise: it requires approval")
    void tenPointOhTwoOnAnAssetGoesToAPerson() {
        // 89.98 against 100.00 — a ratio of exactly 10.02%, two basis points past a bright
        // line that does not exist for assets.
        ModificationTestResult result = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.standard());

        assertThat(result.ratio().setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("0.100200"));
        assertThat(result.breachesThreshold()).isTrue();
        assertThat(result.withinReviewBand()).isTrue();

        // The number breaches, and the engine still declines. A 10.02% result and a 9.98%
        // one carry the same information, and the distance between their consequences could
        // not be larger: derecognition of a corporate loan at a fresh EIR, against retention
        // of the original EIR with a catch-up. This is a refusal to automate a judgement, not
        // an unfinished feature.
        assertThat(result.conclusion()).isEqualTo(ModificationConclusion.REQUIRES_APPROVAL);
        assertThat(result.conclusion()).isNotEqualTo(ModificationConclusion.SUBSTANTIAL);
        assertThat(result.requiresApproval()).isTrue();
        assertThat(result.conclusion().isDecided()).isFalse();
        assertThat(result.distanceFromThreshold().setScale(4, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0002"));

        // And a caller that ignores that and asks for a mechanism anyway gets an exception
        // rather than Mechanism.NONE — which downstream would read as "no EIR consequence"
        // and let the event pass through the amortisation at the original rate with no
        // catch-up and no derecognition: a wrong number produced by a missing check.
        assertThatThrownBy(() -> result.conclusion().mechanism())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("route the event to the approval queue");
    }

    @Test
    @DisplayName("the same 10.02% on a LIABILITY is authoritative: substantial, derecognise")
    void tenPointOhTwoOnALiabilityIsDecided() {
        ModificationTestResult result = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.LIABILITY, ReviewBand.standard());

        // B3.3.6 is a bright line and the engine applies it. The band is still reported —
        // the result does land inside it — but it does not change the outcome, because on the
        // liability side the standard has already made the judgement the band exists to
        // protect.
        assertThat(result.ratio().setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("0.100200"));
        assertThat(result.withinReviewBand()).isTrue();
        assertThat(result.conclusion()).isEqualTo(ModificationConclusion.SUBSTANTIAL);
        assertThat(result.conclusion().isDecided()).isTrue();
        assertThat(result.conclusion().mechanism()).isEqualTo(Mechanism.DERECOGNITION);
        assertThat(result.side().quantitativeTestIsAuthoritative()).isTrue();
        assertThat(InstrumentSide.ASSET.quantitativeTestIsAuthoritative()).isFalse();
    }

    @Test
    @DisplayName("the asymmetry in one comparison: one ratio, two sides, two conclusions")
    void theAsymmetryIsTheWholePoint() {
        FlowVector remaining = singleFlow("100.00");
        FlowVector revised = singleFlow("89.98");

        ModificationTestResult asset = ModificationTest.evaluate(
            ORIGINAL_EIR, remaining, revised, MONTHLY, InstrumentSide.ASSET, ReviewBand.standard());
        ModificationTestResult liability = ModificationTest.evaluate(
            ORIGINAL_EIR, remaining, revised, MONTHLY, InstrumentSide.LIABILITY, ReviewBand.standard());

        assertThat(asset.ratio()).isEqualByComparingTo(liability.ratio());
        assertThat(asset.breachesThreshold()).isEqualTo(liability.breachesThreshold());
        assertThat(asset.conclusion()).isNotEqualTo(liability.conclusion());
        assertThat(asset.conclusion()).isEqualTo(ModificationConclusion.REQUIRES_APPROVAL);
        assertThat(liability.conclusion()).isEqualTo(ModificationConclusion.SUBSTANTIAL);
    }

    @Test
    @DisplayName("9.98% on an asset is also inside the band, so it also goes to a person")
    void justBelowTheLineIsTheSameJudgement() {
        // The point of the band: 9.98% and 10.02% carry the same information, so they get the
        // same treatment. A bright line applied to a computed number inherits that number's
        // sensitivity, and the band is where the engine declines to pretend otherwise.
        ModificationTestResult asset = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("90.02"), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.standard());

        assertThat(asset.ratio().setScale(6, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("0.099800"));
        assertThat(asset.breachesThreshold()).isFalse();
        assertThat(asset.withinReviewBand()).isTrue();
        assertThat(asset.conclusion()).isEqualTo(ModificationConclusion.REQUIRES_APPROVAL);

        // On the liability side the bright line does decide it, and the answer is the
        // opposite of the 10.02% case — which is exactly the sensitivity the asset-side band
        // is protecting against.
        ModificationTestResult liability = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("90.02"), MONTHLY,
            InstrumentSide.LIABILITY, ReviewBand.standard());
        assertThat(liability.conclusion()).isEqualTo(ModificationConclusion.NOT_SUBSTANTIAL);
    }

    @Test
    @DisplayName("an asset result clear of the band is a recommendation the engine may state")
    void anAssetResultClearOfTheBandIsConcluded() {
        // 40% is not a close call, and refusing to conclude on every asset would make the
        // approval queue meaningless. The band is where judgement lives, not everywhere.
        ModificationTestResult substantial = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("60.00"), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.standard());

        assertThat(substantial.ratio().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("0.40"));
        assertThat(substantial.withinReviewBand()).isFalse();
        assertThat(substantial.conclusion()).isEqualTo(ModificationConclusion.SUBSTANTIAL);
        assertThat(substantial.conclusion().mechanism()).isEqualTo(Mechanism.DERECOGNITION);
    }

    @ParameterizedTest
    @EnumSource(InstrumentSide.class)
    @DisplayName("a qualitative trigger below the threshold stops the ratio concluding alone")
    void aFiredTriggerBlocksANotSubstantialConclusion(InstrumentSide side) {
        FlowVector remaining = singleFlow("100.00");
        FlowVector revised = singleFlow("97.00");

        ModificationTestResult withoutTrigger = ModificationTest.evaluate(
            ORIGINAL_EIR, remaining, revised, MONTHLY, side, ReviewBand.standard());
        ModificationTestResult withTrigger = ModificationTest.evaluate(
            ORIGINAL_EIR, remaining, revised, MONTHLY, side, ReviewBand.standard(),
            EnumSet.of(QualitativeTrigger.CHANGE_OF_OBLIGOR));

        // A change of obligor at a 3% present-value difference is exactly the case the
        // February 2025 direction is aimed at. B3.3.6 makes a 10% difference conclusive *of*
        // substantiality; it does not make a smaller one conclusive against it, so the
        // liability side declines here too.
        assertThat(withoutTrigger.conclusion()).isEqualTo(ModificationConclusion.NOT_SUBSTANTIAL);
        assertThat(withTrigger.conclusion()).isEqualTo(ModificationConclusion.REQUIRES_APPROVAL);
        assertThat(withTrigger.anyTriggerFired()).isTrue();
        assertThat(withTrigger.triggersFired()).containsExactly(QualitativeTrigger.CHANGE_OF_OBLIGOR);
    }

    @Test
    @DisplayName("triggers are recorded as a list, de-duplicated and in enum order")
    void triggersAreReviewableEvidence() {
        // "A qualitative trigger fired" is not a reviewable statement; "the obligor changed
        // and the facility converted from revolving to term" is. Normalised so two results
        // are comparable on their evidence.
        ModificationTestResult result = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("97.00"), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.standard(),
            List.of(QualitativeTrigger.REVOLVING_TO_TERM_CONVERSION,
                QualitativeTrigger.CHANGE_OF_OBLIGOR,
                QualitativeTrigger.CHANGE_OF_OBLIGOR));

        assertThat(result.triggersFired()).containsExactly(
            QualitativeTrigger.CHANGE_OF_OBLIGOR, QualitativeTrigger.REVOLVING_TO_TERM_CONVERSION);
        assertThatThrownBy(() -> result.triggersFired().add(QualitativeTrigger.CHANGE_OF_CURRENCY))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the no-trigger overload asserts that the assessment was performed, not skipped")
    void theConvenienceOverloadIsAnAssertion() {
        ModificationTestResult explicit = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard(), EnumSet.noneOf(QualitativeTrigger.class));
        ModificationTestResult convenience = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard());

        assertThat(convenience).isEqualTo(explicit);
        assertThat(convenience.triggersFired()).isEmpty();
    }

    @Test
    @DisplayName("both present values are retained, not just the ratio they produce")
    void bothLegsAreEvidence() {
        ModificationTestResult result = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard());

        // The question an auditor or a credit committee asks about a result is which leg
        // moved and by how much. Reconstructing that from the ratio means re-deriving the
        // projection.
        assertThat(result.presentValueRemaining().atPresentationScale().amount())
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(result.presentValueRevised().atPresentationScale().amount())
            .isEqualByComparingTo(bd("527779.90"));
        // Signed: negative where the revised flows are worth less, which for a restructured
        // asset is the ordinary case.
        assertThat(result.presentValueDifference().atPresentationScale().amount())
            .isEqualByComparingTo(bd("-627.42"));
        assertThat(result.reviewBand()).isEqualTo(ReviewBand.standard());
    }

    @Test
    @DisplayName("a fee settled on the modification date enters the test at a factor of one")
    void anchorDatedFeesAreInTheTest() {
        // B3.3.6 requires the test to be net of fees paid and received, and a restructuring
        // fee is nearly always settled on the modification date. Dropping it is the quiet way
        // to understate the ratio on precisely the transactions where the fee is the
        // substance of the change.
        Money consentFee = Money.inr("40000");
        List<CashFlow> withFee = new ArrayList<>(reProfiled().flows());
        withFee.add(CashFlow.of(
            MODIFICATION_DATE, 0, consentFee.negate(), FlowKind.INTEGRAL_COST_PAID));
        FlowVector revisedWithFee = FlowVector.of(MODIFICATION_DATE, Money.INR, withFee);

        Money withoutTheFee =
            ModificationTest.presentValueAtModificationDate(ORIGINAL_EIR, reProfiled(), MONTHLY);
        Money withTheFee =
            ModificationTest.presentValueAtModificationDate(ORIGINAL_EIR, revisedWithFee, MONTHLY);

        assertThat(withTheFee.minus(withoutTheFee).amount())
            .isEqualByComparingTo(consentFee.negate().amount());

        ModificationTestResult withFeeResult = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), revisedWithFee, MONTHLY, InstrumentSide.LIABILITY,
            ReviewBand.standard());
        ModificationTestResult withoutFeeResult = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.LIABILITY,
            ReviewBand.standard());

        // The fee alone moves the ratio from 0.12% to 7.7%. Not decisive here, and on a
        // marginal case it is the whole answer.
        assertThat(withoutFeeResult.ratio().setScale(4, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0012"));
        assertThat(withFeeResult.ratio().setScale(4, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0769"));
        assertThat(withFeeResult.ratio()).isGreaterThan(withoutFeeResult.ratio());
    }

    @Test
    @DisplayName("the band and the threshold are policy inputs, not numeric constants")
    void theBandIsConfigurable() {
        assertThat(ReviewBand.standard().threshold()).isEqualByComparingTo(ReviewBand.TEN_PERCENT);
        assertThat(ReviewBand.standard().lowerBound()).isEqualByComparingTo(bd("0.09"));
        assertThat(ReviewBand.standard().upperBound()).isEqualByComparingTo(bd("0.11"));

        // "At least 10 per cent" — so exactly 10% breaches.
        assertThat(ReviewBand.standard().breaches(bd("0.10"))).isTrue();
        assertThat(ReviewBand.standard().breaches(bd("0.0999999"))).isFalse();

        // A policy that elects to treat the computed ratio as decisive on the asset side too
        // is permitted and documented as an election. With no band, only an exact hit on the
        // line still goes to a person — the one case even a no-band policy should look at.
        assertThat(ReviewBand.withoutBand().contains(bd("0.10"))).isTrue();
        assertThat(ReviewBand.withoutBand().contains(bd("0.1002"))).isFalse();
        ModificationTestResult noBand = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.withoutBand());
        assertThat(noBand.conclusion()).isEqualTo(ModificationConclusion.SUBSTANTIAL);

        // How wide the band needs to be depends on how the revised flows were derived: a
        // contractually re-documented schedule warrants a narrower band than a behaviourally
        // projected one. At one basis point either side, 10.02% is outside and 10.005% is in.
        ReviewBand narrow = ReviewBand.of(bd("0.10"), bd("0.0001"));
        assertThat(narrow.lowerBound()).isEqualByComparingTo(bd("0.0999"));
        assertThat(narrow.upperBound()).isEqualByComparingTo(bd("0.1001"));
        assertThat(narrow.contains(bd("0.1002"))).isFalse();
        assertThat(narrow.contains(bd("0.10005"))).isTrue();
        assertThat(ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.ASSET, narrow).conclusion())
            .isEqualTo(ModificationConclusion.SUBSTANTIAL);

        // And a wider one takes the same result back to a person, which is the election the
        // parameter exists to express.
        ReviewBand wide = ReviewBand.of(bd("0.10"), bd("0.02"));
        assertThat(ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.ASSET, wide).conclusion())
            .isEqualTo(ModificationConclusion.REQUIRES_APPROVAL);
    }

    @Test
    @DisplayName("a band reaching zero is rejected: it would stop meaning anything")
    void theBandMustBeNarrowerThanTheThreshold() {
        assertThatThrownBy(() -> ReviewBand.of(bd("0.10"), bd("0.10")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be narrower than threshold");
        assertThatThrownBy(() -> ReviewBand.of(bd("0"), bd("0")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be positive");
        assertThatThrownBy(() -> ReviewBand.of(bd("0.10"), bd("-0.01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("halfWidth must be non-negative");
    }

    @Test
    @DisplayName("legs anchored on different dates are rejected: present values must be comparable")
    void bothLegsMustBeAnchoredOnTheModificationDate() {
        FlowVector elsewhere = FlowVector.of(MODIFICATION_DATE.plusMonths(1), Money.INR, List.of(
            CashFlow.of(MODIFICATION_DATE.plusMonths(2), 1, Money.inr("100"), FlowKind.PRINCIPAL)));

        assertThatThrownBy(() -> ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), elsewhere, MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both legs must be anchored on the modification date");
    }

    @Test
    @DisplayName("a contingent flow is rejected from both legs")
    void contingentFlowsAreRejected() {
        // A prepayment penalty that may never be incurred would move the ratio across the
        // threshold on a cash flow that never happens.
        FlowVector withPenalty = FlowVector.of(MODIFICATION_DATE, Money.INR, List.of(
            CashFlow.of(MODIFICATION_DATE.plusMonths(1), 1, Money.inr("100"), FlowKind.PRINCIPAL),
            CashFlow.contingent(MODIFICATION_DATE.plusMonths(1), 1, Money.inr("15"), FlowKind.INTEREST)));

        assertThatThrownBy(() -> ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), withPenalty, MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("contingent flows must not enter the EIR projection");
    }

    @Test
    @DisplayName("a fully repaid exposure has no test: the denominator is zero")
    void aZeroPresentValueLegIsRejected() {
        assertThatThrownBy(() -> ModificationTest.ratio(Money.inr("100"), Money.zero(Money.INR)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not a modification");
    }

    @Test
    @DisplayName("the ratio is sign-independent, so a liability's own sign convention does not matter")
    void theRatioIsAbsolute() {
        // A liability's flows are modelled with the opposite sign. The ratio compares two
        // present values on the same convention, and taking the denominator in absolute terms
        // is what makes the same threshold mean the same thing on both sides.
        ModificationTestResult asset = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("100.00"), singleFlow("89.98"), MONTHLY,
            InstrumentSide.LIABILITY, ReviewBand.standard());
        ModificationTestResult mirrored = ModificationTest.evaluate(
            ORIGINAL_EIR, singleFlow("-100.00"), singleFlow("-89.98"), MONTHLY,
            InstrumentSide.LIABILITY, ReviewBand.standard());

        assertThat(mirrored.ratio()).isEqualByComparingTo(asset.ratio());
        assertThat(mirrored.conclusion()).isEqualTo(asset.conclusion());
        assertThat(mirrored.ratio().signum()).isNotNegative();
    }

    @Test
    @DisplayName("a rate at or below -100% is rejected in the test, not inside the power")
    void anUndiscountableRateFailsLoudly() {
        assertThatThrownBy(() -> ModificationTest.evaluate(
            bd("-1"), remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("originalEir must exceed -1");
    }

    @Test
    @DisplayName("the test is deterministic and reads no clock")
    void deterministic() {
        ModificationTestResult first = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard());
        ModificationTestResult second = ModificationTest.evaluate(
            ORIGINAL_EIR, remainingOriginal(), reProfiled(), MONTHLY, InstrumentSide.ASSET,
            ReviewBand.standard());

        assertThat(second).isEqualTo(first);
        // Dates come from the vectors: the fixture's own modification date, never today's.
        assertThat(remainingOriginal().anchorDate()).isEqualTo(MODIFICATION_DATE);
    }

    @Test
    @DisplayName("the rate matters: at the EIR the remaining flows are the carrying amount")
    void theTestDiscountsAtTheEirNotTheCoupon() {
        // Discounting the 12 remaining instalments at the original EIR gives 528,407.32 — the
        // EIR-leg carrying amount exactly, because that leg amortises to zero over these same
        // flows. Discounting at the contractual coupon gives 529,815.55 instead, and a ratio
        // computed from the wrong pair starts 1,408 out on the denominator.
        Money atTheEir =
            ModificationTest.presentValueAtModificationDate(ORIGINAL_EIR, remainingOriginal(), MONTHLY);
        Money atTheCoupon = ModificationTest.presentValueAtModificationDate(
            RoutingFixtures.CONTRACTUAL.periodic(), remainingOriginal(), MONTHLY);

        assertThat(atTheEir.atPresentationScale().amount())
            .isEqualByComparingTo(GCA_AT_MONTH_12.amount());
        assertThat(atTheCoupon.atPresentationScale().amount()).isEqualByComparingTo(bd("529815.55"));
        assertThat(atTheCoupon.minus(atTheEir).atPresentationScale().amount())
            .isEqualByComparingTo(bd("1408.23"));

        // Note the 1,408.23 is *not* the 1,408.29 unamortised fee of invariant INV-4. INV-4
        // is stated on the two legs' carrying amounts, and the contractual leg's balance of
        // 529,815.61 exceeds the present value of its own remaining billed flows by the
        // discounted 0.06 the billed schedule never collects. Both figures are right and they
        // answer different questions; a reconciliation that swaps one for the other is short
        // by exactly that residue.
        assertThat(CONTRACTUAL_BALANCE_AT_MONTH_12.amount()).isGreaterThan(atTheCoupon.amount());
        assertThat(CONTRACTUAL_BALANCE_AT_MONTH_12.minus(atTheCoupon).amount())
            .isLessThan(bd("0.06"));
    }

    @Test
    @DisplayName("the fixture is the reference case's own schedule, not an approximation of it")
    void fixtureCalibration() {
        // 12 instalments of 47,073.47 at the contractual rate, and the same 12 at the EIR:
        // the second reproduces the published month-12 carrying amount to the paisa, which is
        // what says the vectors are reference case 1's rather than something close to it.
        assertThat(ModificationTest.presentValueAtModificationDate(
                ORIGINAL_EIR, level(RoutingFixtures.EMI, 12), MONTHLY)
            .atPresentationScale().amount()).isEqualByComparingTo(bd("528407.32"));
        assertThat(level(RoutingFixtures.EMI, 12).size()).isEqualTo(12);
        assertThat(level(RoutingFixtures.EMI, 12).maxPeriodIndex()).isEqualTo(12);
    }
}
