package com.crisil.eir.policy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The materiality tier gate against 03 § 10's table, row by row (FR-107).
 *
 * <p><b>Every expected tier in this file is read off § 10's three-row table and the row is cited
 * at the assertion.</b> Nothing here was obtained by running the assignment and writing down what
 * came back, which for a classification gate would be a test of the implementation against itself:
 * the failure mode § 10 is guarding against — the roadmap's Cambodia failure mode, an undocumented
 * shortcut applied to products that need a full solver — produces a plausible tier and no
 * exception, so a self-derived expectation would agree with it.
 *
 * <p>Two things get disproportionate attention, because they are the two ways this gate goes wrong
 * quietly. The first is the evaluation order: § 10's rows overlap, and a tenor-first reading hands
 * a hedged six-month exposure the straight-line approximation. The second is the 12-month boundary,
 * tested on both sides and at the point, because it is the line between a pool EIR and an
 * approximation and § 10 states it as "> 12 months" against "≤ 12 months" with no gap between.
 */
class TierAssignmentTest {

    /** 50 crore. An illustrative Board figure — § 10 does not name one, it names the governance. */
    private static final Money BOARD_THRESHOLD = Money.inr("500000000.00");

    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 3, 15);

    private static final PolicyVersion TIER_VERSION = new PolicyVersion(
        "TIER-2027.1", PolicyKind.TIER_ASSIGNMENT,
        "03 § 10 wholesale Tier 1 threshold set at 50 crore", EFFECTIVE_FROM,
        "policy.author", "accounting.policy.owner", APPROVED_ON, PolicyVersionStatus.EFFECTIVE);

    private static final TierAssignment GATE = TierAssignment.under(TIER_VERSION, BOARD_THRESHOLD);

    private static TierAssignmentResult assign(TierAssignmentInput input) {
        return GATE.assign(input);
    }

    @Nested
    @DisplayName("03 § 10 Tier 1 row: the six overrides, none of them tenor-conditioned")
    class TierOneOverrides {

        // Every case in this class is written at a SIX-MONTH tenor. That is the point: § 10's
        // Tier 1 row states its limbs with no tenor condition, so a six-month exposure carrying
        // any one of them is Tier 1 even though the Tier 3 row's "original tenor <= 12 months"
        // also describes it. The tenor is held at 6 so that a regression to a tenor-first
        // evaluation order fails here rather than passing on a long-tenor fixture.

        @Test
        @DisplayName("all project finance — Tier 1 at six months")
        void projectFinanceIsTierOneAtAnyTenor() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "PF-BRIDGE-1", TierAssignmentSegment.WHOLESALE, 6,
                TierAssignmentFeature.PROJECT_FINANCE));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'all project finance', stated without a tenor condition,"
                    + " so a six-month project bridge is Tier 1 and not Tier 3")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_PROJECT_FINANCE);
        }

        @Test
        @DisplayName("all POCI — Tier 1 at six months")
        void pociIsTierOneAtAnyTenor() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "POCI-1", TierAssignmentSegment.WHOLESALE, 6,
                TierAssignmentFeature.PURCHASED_OR_ORIGINATED_CREDIT_IMPAIRED));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'all POCI'. A credit-adjusted EIR is solved on expected"
                    + " flows, so 'contractual rate plus straight-line fees' has no contractual"
                    + " rate to start from")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_POCI);
        }

        @Test
        @DisplayName("all restructured or modified — Tier 1 at six months")
        void restructuredIsTierOneAtAnyTenor() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "RESTR-1", TierAssignmentSegment.MSME, 6,
                TierAssignmentFeature.RESTRUCTURED_OR_MODIFIED));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'all restructured or modified'")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_RESTRUCTURED_OR_MODIFIED);
        }

        @Test
        @DisplayName("any contingent rate feature — Tier 1 at six months")
        void contingentRateFeatureIsTierOneAtAnyTenor() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "ESG-LINKED-1", TierAssignmentSegment.WHOLESALE, 6,
                TierAssignmentFeature.CONTINGENT_RATE_FEATURE));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'any contingent rate feature (ratchet, ESG, step-up)'")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_CONTINGENT_RATE_FEATURE);
        }

        @Test
        @DisplayName("any exposure in a hedge relationship — Tier 1 at six months")
        void hedgedExposureIsTierOneAtAnyTenor() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "HEDGED-1", TierAssignmentSegment.WHOLESALE, 6,
                TierAssignmentFeature.HEDGE_RELATIONSHIP));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'any exposure in a hedge relationship'. The defect this"
                    + " catches is a straight-line approximation applied to a hedged instrument,"
                    + " which leaves invariant HB-1's basis-adjustment amortisation with no"
                    + " effective rate to run on")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_HEDGE_RELATIONSHIP);
        }

        @Test
        @DisplayName("wholesale above the Board threshold — Tier 1 at six months")
        void wholesaleAboveThresholdIsTierOneAtAnyTenor() {
            // 60 crore against the 50 crore threshold, on a six-month WCDL. The Tier 3 row lists
            // WCDL by name; the Tier 1 row's threshold limb still wins, because the row is not
            // tenor-conditioned either.
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("WCDL-LARGE-1", TierAssignmentSegment.WHOLESALE, 6)
                .withExposure(Money.inr("600000000.00")));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'wholesale above a Board threshold'")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule())
                .isEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
            assertThat(result.suppressedRules())
                .as("the Tier 3 short-tenor rule matched this WCDL and lost on precedence; the"
                    + " record has to say so")
                .containsExactly(TierAssignmentRule.TIER_3_SHORT_TENOR);
        }

        @Test
        @DisplayName("every Tier 1 limb is reported, not only the first that fired")
        void allMatchingOverridesAreRecorded() {
            // A restructured POCI project loan. All three limbs are true and they all assign
            // Tier 1, so precedence among them is immaterial to the tier — but not to the record:
            // an ACPIR 57 sub-committee reviewing an individually significant exposure needs to
            // know it is POCI as well as project finance.
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "PF-POCI-1", TierAssignmentSegment.WHOLESALE, 240,
                TierAssignmentFeature.PROJECT_FINANCE,
                TierAssignmentFeature.PURCHASED_OR_ORIGINATED_CREDIT_IMPAIRED,
                TierAssignmentFeature.RESTRUCTURED_OR_MODIFIED));

            assertThat(result.tier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_PROJECT_FINANCE);
            assertThat(result.suppressedRules())
                .as("in 03 § 10's own reading order")
                .containsExactly(
                    TierAssignmentRule.TIER_1_POCI,
                    TierAssignmentRule.TIER_1_RESTRUCTURED_OR_MODIFIED);
            assertThat(result.overrodeATenorRule())
                .as("no tenor rule was ever in contention on a 240-month wholesale loan, so this"
                    + " must not inflate the count of overridden tenor assignments")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the overrides are evaluated BEFORE the tenor rule, and it is provable from the"
        + " output")
    class OverrideBeforeTenorOrdering {

        @Test
        @DisplayName("a six-month hedged exposure is Tier 1, and records that Tier 3 lost")
        void hedgedShortTenorIsTierOneNotTierThree() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "HEDGED-SHORT-1", TierAssignmentSegment.WHOLESALE, 6,
                TierAssignmentFeature.HEDGE_RELATIONSHIP));

            // The named defect: getting this backwards silently applies a straight-line
            // approximation to a hedged instrument. Nothing throws, no invariant breaches, and the
            // tier on the record looks reasonable for a six-month exposure.
            assertThat(result.tier())
                .as("03 § 10 Tier 1 row beats the Tier 3 row's 'original tenor <= 12 months' for"
                    + " the same contract")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.suppressedRules())
                .containsExactly(TierAssignmentRule.TIER_3_SHORT_TENOR);
            assertThat(result.overrodeATenorRule()).isTrue();
            assertThat(result.describe())
                .as("the basis must name the rule it beat, or the ordering is unauditable from the"
                    + " record")
                .contains("TIER_3_SHORT_TENOR");
        }

        @Test
        @DisplayName("declaration order in TierAssignmentRule puts all six overrides ahead of both"
            + " tenor rules")
        void overridesPrecedeTenorRulesInDeclarationOrder() {
            // Asserted against the enum itself and not through an assignment, because this is the
            // property a future maintainer breaks by inserting a new product limb in the wrong
            // place. The behavioural tests above would then fail one by one; this fails once and
            // says why.
            List<TierAssignmentRule> ladder = List.of(TierAssignmentRule.values());
            int lastOverride = ladder.stream()
                .filter(TierAssignmentRule::isOverride)
                .mapToInt(ladder::indexOf)
                .max()
                .orElseThrow();

            assertThat(ladder.indexOf(TierAssignmentRule.TIER_3_SHORT_TENOR))
                .as("03 § 10: the Tier 1 limbs carry no tenor condition, so they are overrides and"
                    + " must be tested before the tenor rule")
                .isGreaterThan(lastOverride);
            assertThat(ladder.indexOf(TierAssignmentRule.TIER_2_RETAIL_OR_MSME_LONG_TENOR))
                .isGreaterThan(lastOverride);
            assertThat(ladder.stream().filter(TierAssignmentRule::isOverride))
                .as("exactly the six limbs of 03 § 10's Tier 1 row")
                .hasSize(6);
        }

        @Test
        @DisplayName("the fully collateralised limb never outranks the tenor rule that describes a"
            + " housing loan")
        void collateralLimbDoesNotCaptureLongTenorRetail() {
            // The one internal contradiction in § 10's table. The Tier 3 row's second limb —
            // "plus fully collateralised low-fee products" — carries no tenor condition, and a
            // 20-year housing loan is fully collateralised with modest fees. Read literally it
            // would put the housing book on straight-line fee accretion; § 10's preamble names
            // individual housing loans FIRST among the five families holding roughly 80% of the
            // bank's EIR estimation risk, and reference case 9 measures the straight-line error on
            // a long instrument at +81.0% of year-one income. So the tenor rule decides, per the
            // section's opening sentence: "estimation risk distributes by tenor, not by product".
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "HOUSING-240M", TierAssignmentSegment.RETAIL, 240,
                TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE));

            assertThat(result.tier())
                .as("03 § 10 Tier 2 row: retail with original tenor > 12 months, housing named"
                    + " explicitly")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(result.rule())
                .isEqualTo(TierAssignmentRule.TIER_2_RETAIL_OR_MSME_LONG_TENOR);
            assertThat(result.suppressedRules())
                .as("the collateral limb matched and lost; recording it is what makes the reading"
                    + " of § 10 reviewable rather than buried in code")
                .containsExactly(TierAssignmentRule.TIER_3_FULLY_COLLATERALISED_LOW_FEE);
        }

        @Test
        @DisplayName("the collateral limb still catches what the tenor rules leave")
        void collateralLimbCatchesLongTenorOutsideRetailAndMsme() {
            // Wholesale, below the Board threshold, three years, fully secured and low-fee.
            // Neither tenor rule reaches it — not retail or MSME, not inside twelve months — so
            // the Tier 3 row's second limb is what § 10 leaves for it. This is the population the
            // limb exists for, and the test proves the reordering above did not delete it.
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("LAS-36M", TierAssignmentSegment.WHOLESALE, 36,
                    TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE)
                .withExposure(Money.inr("100000000.00")));

            assertThat(result.tier())
                .as("03 § 10 Tier 3 row: 'plus fully collateralised low-fee products'")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(result.rule())
                .isEqualTo(TierAssignmentRule.TIER_3_FULLY_COLLATERALISED_LOW_FEE);
        }
    }

    @Nested
    @DisplayName("the 12-month boundary, on both sides and at the point")
    class TenorBoundary {

        @Test
        @DisplayName("retail at 13 months is Tier 2")
        void thirteenMonthsIsPoolLevel() {
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("AUTO-13M", TierAssignmentSegment.RETAIL, 13));

            assertThat(result.tier())
                .as("03 § 10 Tier 2 row: 'retail and MSME with original tenor > 12 months'")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(result.rule())
                .isEqualTo(TierAssignmentRule.TIER_2_RETAIL_OR_MSME_LONG_TENOR);
        }

        @Test
        @DisplayName("retail at exactly 12 months is Tier 3, not Tier 2")
        void exactlyTwelveMonthsIsApproximation() {
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("TEMP-OD-12M", TierAssignmentSegment.RETAIL, 12));

            // The point itself. § 10 writes the two rows as "> 12 months" and "<= 12 months", so
            // twelve belongs to Tier 3 and the two rows partition the tenor axis with no gap and
            // no overlap. An off-by-one here moves a whole population between a pool EIR and an
            // approximation, in whichever direction the error runs.
            assertThat(result.tier())
                .as("03 § 10 Tier 3 row: 'original tenor <= 12 months', which includes temporary"
                    + " OD by name")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_3_SHORT_TENOR);
            assertThat(result.suppressedRules())
                .as("the Tier 2 long-tenor rule must not even match at twelve")
                .isEmpty();
        }

        @Test
        @DisplayName("MSME at 11 months is Tier 3 and at 12 months is Tier 3; at 13 it is Tier 2")
        void msmeAcrossTheBoundary() {
            assertThat(assign(TierAssignmentInput.of("PC-11M", TierAssignmentSegment.MSME, 11))
                .tier())
                .as("03 § 10 Tier 3 row: packing credit inside twelve months")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(assign(TierAssignmentInput.of("PC-12M", TierAssignmentSegment.MSME, 12))
                .tier())
                .as("03 § 10 Tier 3 row: the boundary belongs to '<= 12 months'")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(assign(TierAssignmentInput.of("MSME-TERM-13M", TierAssignmentSegment.MSME, 13))
                .tier())
                .as("03 § 10 Tier 2 row: 'MSME term' above twelve months")
                .isEqualTo(MaterialityTier.TIER_2);
        }

        @Test
        @DisplayName("the tenor rule is segment-blind inside twelve months")
        void shortTenorIsSegmentBlind() {
            // § 10's Tier 3 row lists WCDL and bills (wholesale), packing credit and
            // post-shipment (MSME), temporary OD (retail) and the money-market instruments
            // (treasury) in one row keyed on tenor. The listed products are illustrations of the
            // tenor, not the test — which is the section's argument in miniature.
            assertThat(assign(TierAssignmentInput.of("WCDL-6M", TierAssignmentSegment.WHOLESALE, 6))
                .tier())
                .as("03 § 10 Tier 3 row: WCDL")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(assign(TierAssignmentInput.of("TBILL-364D", TierAssignmentSegment.TREASURY, 12))
                .tier())
                .as("03 § 10 Tier 3 row: T-bills")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(assign(TierAssignmentInput.of("TREPS-1D", TierAssignmentSegment.TREASURY, 1))
                .tier())
                .as("03 § 10 Tier 3 row: TREPS")
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("wholesale and treasury above twelve months are NOT Tier 2")
        void longTenorOutsideRetailIsNotPooled() {
            // § 10's Tier 2 row is drawn around retail and MSME only — pooling rests on ACPIR 51's
            // group presumption, which needs a homogeneous population. A ten-year SDL holding is
            // not that, and § 10's preamble puts "long-dated HTM SDLs and corporate bonds" among
            // the five families carrying the estimation risk. Nothing in the table describes them,
            // so ADR-0005's default answers: contract-level measurement.
            TierAssignmentResult sdl = assign(
                TierAssignmentInput.of("SDL-120M", TierAssignmentSegment.TREASURY, 120));

            assertThat(sdl.tier())
                .as("no row of 03 § 10 matches; ADR-0005 — 'contract-level measurement is the"
                    + " default. Pooling is an explicit, governed election'")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(sdl.rule())
                .isEqualTo(TierAssignmentRule.TIER_1_DEFAULT_CONTRACT_LEVEL);
            assertThat(sdl.isResidualDefault()).isTrue();
        }
    }

    @Nested
    @DisplayName("03 § 10 Tier 2 row: cards and KCC revolvers, the limb that is not tenor-keyed")
    class Revolvers {

        @Test
        @DisplayName("a credit card with no contractual maturity is Tier 2, not escalated")
        void cardWithoutTenorIsPoolLevel() {
            // A revolver has no contractual repayment schedule (03 § 5, 05 § 239), so its tenor is
            // absent by construction rather than missing. Reading that absence as a data gap would
            // escalate the entire card book — one of the five families in § 10's preamble — out of
            // the pool treatment ACPIR 51 exists to permit.
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "CARD-1", TierAssignmentSegment.RETAIL, null,
                TierAssignmentFeature.CARD_OR_KCC_REVOLVER));

            assertThat(result.tier())
                .as("03 § 10 Tier 2 row: 'credit cards', listed separately from the > 12 months"
                    + " clause")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_2_CARD_OR_KCC_REVOLVER);
            assertThat(result.suppressedRules())
                .as("the not-determinable fallback must not even match a revolver, or every card"
                    + " would carry a spurious data-quality flag")
                .isEmpty();
            assertThat(result.isResidualDefault()).isFalse();
        }

        @Test
        @DisplayName("a card whose annual review reads as a 12-month tenor is still Tier 2")
        void cardWithTwelveMonthReviewIsNotTierThree() {
            // The second ordering subtlety. Card limits are reviewed annually, and a source system
            // that populates tenor from the review date hands the gate a 12 — which the Tier 3 row
            // describes exactly. The revolver limb is evaluated first, so the card book stays
            // pooled and the record shows what it beat.
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "CARD-REVIEWED-1", TierAssignmentSegment.RETAIL, 12,
                TierAssignmentFeature.CARD_OR_KCC_REVOLVER));

            assertThat(result.tier())
                .as("03 § 10 Tier 2 row names credit cards without a tenor condition; the annual"
                    + " review date is not the instrument's expected life")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(result.suppressedRules())
                .containsExactly(TierAssignmentRule.TIER_3_SHORT_TENOR);
        }

        @Test
        @DisplayName("a KCC revolver is Tier 2 on the same limb")
        void kccRevolverIsPoolLevel() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "KCC-1", TierAssignmentSegment.MSME, null,
                TierAssignmentFeature.CARD_OR_KCC_REVOLVER));

            assertThat(result.tier())
                .as("03 § 10 Tier 2 row: 'KCC revolvers'")
                .isEqualTo(MaterialityTier.TIER_2);
        }

        @Test
        @DisplayName("a restructured card is Tier 1 — the override still wins")
        void restructuredCardIsTierOne() {
            TierAssignmentResult result = assign(TierAssignmentInput.of(
                "CARD-RESTR-1", TierAssignmentSegment.RETAIL, null,
                TierAssignmentFeature.CARD_OR_KCC_REVOLVER,
                TierAssignmentFeature.RESTRUCTURED_OR_MODIFIED));

            assertThat(result.tier())
                .as("03 § 10 Tier 1 row: 'all restructured or modified' beats the Tier 2 revolver"
                    + " limb, because a modification is routed per instrument (ADR-0006) and a"
                    + " pooled carrying amount gives the routing nothing to act on")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_RESTRUCTURED_OR_MODIFIED);
            assertThat(result.suppressedRules())
                .containsExactly(TierAssignmentRule.TIER_2_CARD_OR_KCC_REVOLVER);
        }
    }

    @Nested
    @DisplayName("the Board threshold: a dated policy figure, compared on size")
    class BoardThreshold {

        @Test
        @DisplayName("an exposure exactly at the threshold is not above it")
        void exactlyAtThresholdIsNotAboveIt() {
            // § 10 says "above a Board threshold". Boundary cases are common rather than rare
            // here: thresholds are round numbers and facilities are written at round numbers.
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("WHOLESALE-AT-50CR", TierAssignmentSegment.WHOLESALE, 60)
                .withExposure(Money.inr("500000000.00")));

            assertThat(result.rule())
                .as("03 § 10 Tier 1 row reads 'above', so equality does not fire the limb")
                .isNotEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_DEFAULT_CONTRACT_LEVEL);
        }

        @Test
        @DisplayName("one paisa above the threshold fires the limb")
        void onePaisaAboveThresholdFiresTheLimb() {
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("WHOLESALE-50CR-1P", TierAssignmentSegment.WHOLESALE, 60)
                .withExposure(Money.inr("500000000.01")));

            assertThat(result.rule())
                .as("the comparison is on value at working precision, not on a rounded crore"
                    + " figure")
                .isEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
        }

        @Test
        @DisplayName("a signed advance is compared on its absolute value")
        void signConventionDoesNotHideALargeExposure() {
            // Money in this engine is signed from the holder's perspective, so the cash advanced
            // on a loan is NEGATIVE while the carrying amount it becomes is positive. A signed
            // comparison against a positive threshold would put every wholesale exposure below
            // every threshold and empty § 10's Tier 1 row without any symptom.
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("WHOLESALE-ADVANCE-1", TierAssignmentSegment.WHOLESALE, 60)
                .withExposure(Money.inr("-600000000.00")));

            assertThat(result.rule())
                .as("a 60 crore advance recorded as an outflow is still a 60 crore exposure")
                .isEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
        }

        @Test
        @DisplayName("an unknown exposure does not read as below the threshold")
        void absentExposureDoesNotEscapeTierOne() {
            // Absent, not zero. A zero default would read as "below the threshold" and let an
            // unsourced 500 crore facility out of the Tier 1 row. It falls to ADR-0005's
            // contract-level default instead, which is conservative and is flagged as a default.
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("WHOLESALE-UNSOURCED-1", TierAssignmentSegment.WHOLESALE, 60));

            assertThat(result.tier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_DEFAULT_CONTRACT_LEVEL);
            assertThat(result.isResidualDefault())
                .as("countable on a control report, so the gap gets sourced rather than sitting")
                .isTrue();
            assertThat(result.basis()).contains("exposure at origination absent");
        }

        @Test
        @DisplayName("a foreign-currency exposure is not compared, and does not raise")
        void currencyMismatchIsADataConditionNotAnException() {
            // Money.compareTo refuses to compare across currencies, correctly. Converting here
            // would need an FX rate policy with its own version and approval, which this unit does
            // not own. So the limb does not fire, nothing is thrown, and the assignment falls to
            // the conservative default rather than to the Tier 3 approximation.
            TierAssignmentResult result = assign(TierAssignmentInput
                .of("USD-FACILITY-1", TierAssignmentSegment.WHOLESALE, 60)
                .withExposure(Money.of("90000000.00", Currency.getInstance("USD"))));

            assertThat(result.tier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_DEFAULT_CONTRACT_LEVEL);
        }

        @Test
        @DisplayName("a zero threshold is a legitimate Board election: all wholesale at instrument"
            + " level")
        void zeroThresholdPutsAllWholesaleInTierOne() {
            TierAssignment allWholesale = TierAssignment.under(
                TIER_VERSION, Money.inr("0.00"));

            TierAssignmentResult result = allWholesale.assign(TierAssignmentInput
                .of("WCDL-SMALL-1", TierAssignmentSegment.WHOLESALE, 3)
                .withExposure(Money.inr("100000.00")));

            assertThat(result.rule())
                .as("a Board may set the threshold at nil, meaning every wholesale exposure is"
                    + " measured at instrument level; that is a policy election and the gate must"
                    + " express it")
                .isEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
        }

        @Test
        @DisplayName("a negative threshold is refused")
        void negativeThresholdIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TierAssignment.under(TIER_VERSION, Money.inr("-1.00")))
                .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("the threshold must come from a TIER_ASSIGNMENT policy version")
        void wrongPolicyKindIsRefused() {
            PolicyVersion feeVersion = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
                EFFECTIVE_FROM, "policy.author", "accounting.policy.owner", APPROVED_ON,
                PolicyVersionStatus.EFFECTIVE);

            // Wiring defect, not a data condition: a tier recorded against a fee rule set's
            // version id cannot be replayed, because that version's history has nothing to do with
            // the threshold that was applied (invariant DT-1).
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TierAssignment.under(feeVersion, BOARD_THRESHOLD))
                .withMessageContaining("TIER_ASSIGNMENT");
        }
    }

    @Nested
    @DisplayName("an unreadable tenor never buys the Tier 3 approximation")
    class UnreadableTenor {

        @Test
        @DisplayName("an absent tenor on a non-revolver escalates to contract level")
        void absentTenorEscalates() {
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("LEGACY-MIGRATED-1", TierAssignmentSegment.RETAIL, null));

            assertThat(result.tier())
                .as("ADR-0005: contract-level measurement is the default, and neither Tier 2"
                    + " (which needs an approved pool definition) nor Tier 3 (which needs a"
                    + " current equivalence test, TG-1) can be assumed on a record nobody can"
                    + " read")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_TENOR_NOT_DETERMINABLE);
            assertThat(result.isResidualDefault()).isTrue();
        }

        @Test
        @DisplayName("a zero tenor is not 'inside twelve months'")
        void zeroTenorDoesNotReadAsShortTenor() {
            // The one-line version of the Cambodia failure mode: a defaulted or missing tenor
            // arriving as 0, satisfying "<= 12 months", and collecting the straight-line
            // approximation with a plausible-looking basis attached.
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("ZERO-TENOR-1", TierAssignmentSegment.RETAIL, 0));

            assertThat(result.tier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(result.rule()).isEqualTo(TierAssignmentRule.TIER_1_TENOR_NOT_DETERMINABLE);
            assertThat(result.requiresEquivalenceTest()).isFalse();
        }

        @Test
        @DisplayName("a maturity before origination escalates rather than raising")
        void reversedDatesEscalate() {
            Integer tenor = TierAssignmentInput.tenorMonthsBetween(
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 1, 1));

            assertThat(tenor)
                .as("five months backwards; the figure is kept rather than nulled so the basis can"
                    + " say what it saw")
                .isEqualTo(-5);

            TierAssignmentResult result = assign(
                TierAssignmentInput.of("REVERSED-DATES-1", TierAssignmentSegment.MSME, tenor));

            assertThat(result.rule())
                .as("a migration defect is a data condition: reported through the assignment, not"
                    + " thrown, so a ten-million-contract run (FR-905) classifies the rest")
                .isEqualTo(TierAssignmentRule.TIER_1_TENOR_NOT_DETERMINABLE);
            assertThat(result.basis()).contains("original tenor -5 months");
        }
    }

    @Nested
    @DisplayName("tenor in months from dates, rounded up on any part month")
    class TenorFromDates {

        @Test
        @DisplayName("a 364-day T-bill is twelve months and therefore Tier 3")
        void threeSixtyFourDayBillIsTwelveMonths() {
            // 1 Apr 2027 to 31 Mar 2028: eleven whole months plus thirty days. Truncation gives
            // 11, rounding up gives 12, and both land in § 10's Tier 3 row — which is where the
            // section puts T-bills. The case is here to pin the arithmetic, not the tier.
            Integer tenor = TierAssignmentInput.tenorMonthsBetween(
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31));

            assertThat(tenor).isEqualTo(12);
            assertThat(assign(TierAssignmentInput.of("TBILL-364D", TierAssignmentSegment.TREASURY, tenor))
                .tier())
                .as("03 § 10 Tier 3 row: T-bills, original tenor <= 12 months")
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("exactly one year is twelve months, and a day more is thirteen")
        void oneYearAndOneDay() {
            assertThat(TierAssignmentInput.tenorMonthsBetween(
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 4, 1)))
                .as("no part month, so no rounding up")
                .isEqualTo(12);

            // Rounding up rather than truncating is a deliberate choice about which side of a
            // permission boundary a part month falls on. Truncation would read a
            // 12-month-and-29-day facility as twelve and hand it the approximation; the cost of
            // the other error is one extra solve, and ADR-0005 shows solving is event-triggered
            // so a fixed-rate contract with no events solves once in its life.
            assertThat(TierAssignmentInput.tenorMonthsBetween(
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 4, 2)))
                .isEqualTo(13);
            assertThat(TierAssignmentInput.tenorMonthsBetween(
                LocalDate.of(2027, 4, 1), LocalDate.of(2028, 4, 30)))
                .as("twelve months and twenty-nine days is not 'tenor <= 12 months'")
                .isEqualTo(13);
        }

        @Test
        @DisplayName("an absent date yields an absent tenor, not a zero one")
        void absentDateYieldsAbsentTenor() {
            assertThat(TierAssignmentInput.tenorMonthsBetween(null, LocalDate.of(2028, 4, 1)))
                .isNull();
            assertThat(TierAssignmentInput.tenorMonthsBetween(LocalDate.of(2027, 4, 1), null))
                .isNull();
        }
    }

    @Nested
    @DisplayName("recording the basis is the other half of FR-107")
    class RecordedBasis {

        @Test
        @DisplayName("the basis names the rule, the § 10 row, the value fired on and the version")
        void basisCarriesTheWholeReasoning() {
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("HOUSING-1", TierAssignmentSegment.RETAIL, 240));
            String basis = result.describe();

            // 04 § 2.1 pairs materiality_tier with tier_basis. This is what goes in the second
            // column, and each element of it answers a question an auditor actually asks.
            assertThat(basis)
                .as("which contract, which tier, which rule, which row of 03 § 10, what it fired"
                    + " on, and under whose approved threshold")
                .contains("HOUSING-1")
                .contains("TIER_2")
                .contains("TIER_2_RETAIL_OR_MSME_LONG_TENOR")
                .contains("03 § 10 Tier 2 row")
                .contains("original tenor 240 months")
                .contains("TIER-2027.1");
        }

        @Test
        @DisplayName("a Tier 3 basis says on its face that it is conditional")
        void tierThreeBasisNamesItsCondition() {
            TierAssignmentResult result = assign(
                TierAssignmentInput.of("BILL-3M", TierAssignmentSegment.WHOLESALE, 3));

            assertThat(result.tier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(result.describe())
                .as("03 § 10 Tier 3 row's control column: 'Permitted only against a current"
                    + " equivalence test. Invariant TG-1.' A Tier 3 basis that does not say so is"
                    + " the undocumented shortcut the gate exists to prevent")
                .contains("equivalence test")
                .contains("TG-1");
        }

        @Test
        @DisplayName("a blank basis is unrepresentable")
        void blankBasisIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TierAssignmentResult(
                    "C-1", MaterialityTier.TIER_3, TierAssignmentRule.TIER_3_SHORT_TENOR, "  ",
                    List.of(), "TIER-2027.1", false))
                .withMessageContaining("FR-107");
        }

        @Test
        @DisplayName("the tier and the rule that assigned it cannot disagree")
        void tierMustMatchItsRule() {
            // If these could diverge, the persisted materiality_tier would stop meaning what the
            // persisted tier_basis says it means, and every reconciliation of the two columns
            // would be vacuous.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TierAssignmentResult(
                    "C-1", MaterialityTier.TIER_3, TierAssignmentRule.TIER_1_HEDGE_RELATIONSHIP,
                    "hedged", List.of(), "TIER-2027.1", false))
                .withMessageContaining("assigns TIER_1");
        }

        @Test
        @DisplayName("an assignment must name the version whose threshold governed it")
        void policyVersionIdIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TierAssignmentResult(
                    "C-1", MaterialityTier.TIER_3, TierAssignmentRule.TIER_3_SHORT_TENOR, "3 months",
                    List.of(), " ", false))
                .withMessageContaining("DT-1");
        }

        @Test
        @DisplayName("the deciding rule is not also listed as suppressed")
        void decidingRuleIsNotSuppressed() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TierAssignmentResult(
                    "C-1", MaterialityTier.TIER_3, TierAssignmentRule.TIER_3_SHORT_TENOR, "3 months",
                    List.of(TierAssignmentRule.TIER_3_SHORT_TENOR), "TIER-2027.1", false))
                .withMessageContaining("must not also be listed as suppressed");
        }

        @Test
        @DisplayName("a contract id is mandatory on the way in")
        void blankContractIdIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TierAssignmentInput.of(" ", TierAssignmentSegment.RETAIL, 24))
                .withMessageContaining("cannot be filed");
        }

        @Test
        @DisplayName("the same input under the same version gives the same basis, character for"
            + " character")
        void assignmentIsDeterministic() {
            // Invariant DT-1: a re-run of a closed period reproduces published figures
            // bit-identically. tier_basis is a published figure.
            TierAssignmentInput input = TierAssignmentInput.of(
                "DETERMINISM-1", TierAssignmentSegment.RETAIL, 6,
                TierAssignmentFeature.HEDGE_RELATIONSHIP);

            assertThat(assign(input).describe()).isEqualTo(assign(input).describe());
        }

        @Test
        @DisplayName("a batch preserves order and classifies every contract")
        void batchClassifiesEverything() {
            List<TierAssignmentResult> results = GATE.assignAll(List.of(
                TierAssignmentInput.of("A", TierAssignmentSegment.RETAIL, 240),
                TierAssignmentInput.of("B", TierAssignmentSegment.WHOLESALE, 3),
                TierAssignmentInput.of("C", TierAssignmentSegment.RETAIL, null)));

            assertThat(results).extracting(TierAssignmentResult::contractId)
                .containsExactly("A", "B", "C");
            assertThat(results).extracting(TierAssignmentResult::tier)
                .as("03 § 10 Tier 2 row, Tier 3 row, then ADR-0005's default for the unreadable"
                    + " record — an unreadable tenor must not stop the run (FR-905)")
                .containsExactly(
                    MaterialityTier.TIER_2, MaterialityTier.TIER_3, MaterialityTier.TIER_1);
        }
    }

    @Nested
    @DisplayName("the governing version: dated, and provisional until it is operative")
    class GoverningVersion {

        @Test
        @DisplayName("the version's effective date is asked, not assumed")
        void versionEffectiveDateIsAvailable() {
            assertThat(GATE.governs(LocalDate.of(2027, 4, 1)))
                .as("effective from 1 April 2027")
                .isTrue();
            assertThat(GATE.governs(LocalDate.of(2027, 3, 31)))
                .as("approved in March, effective in April; a March run must not pick it up")
                .isFalse();
        }

        @Test
        @DisplayName("an assignment under a draft threshold is marked PROVISIONAL, not refused")
        void draftVersionYieldsAProvisionalAssignment() {
            // FR-210 requires a quantified impact preview BEFORE a threshold change goes
            // effective, so a preview is by definition a run under a non-operative version.
            // Refusing to assign under one would block the control this supports; assigning
            // silently would let a preview's output be filed as the governing basis. So it is
            // marked.
            PolicyVersion draft = new PolicyVersion(
                "TIER-2028.1", PolicyKind.TIER_ASSIGNMENT, "threshold cut to 25 crore",
                LocalDate.of(2028, 4, 1), "policy.author", null, null,
                PolicyVersionStatus.DRAFT);
            TierAssignmentResult preview = TierAssignment
                .under(draft, Money.inr("250000000.00"))
                .assign(TierAssignmentInput
                    .of("PREVIEW-1", TierAssignmentSegment.WHOLESALE, 60)
                    .withExposure(Money.inr("300000000.00")));

            assertThat(preview.rule())
                .as("30 crore is above the proposed 25 crore threshold and below the 50 crore one"
                    + " in force — exactly the population an impact preview exists to count")
                .isEqualTo(TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD);
            assertThat(preview.provisional()).isTrue();
            assertThat(preview.describe()).contains("PROVISIONAL");
        }

        @Test
        @DisplayName("the gate describes its own threshold and approval trail")
        void gateDescribesItself() {
            assertThat(GATE.describe())
                .contains("500000000.00")
                .contains("TIER-2027.1")
                .contains("approved by accounting.policy.owner");
        }
    }

    @Nested
    @DisplayName("what the tier does downstream, and where this unit stops")
    class DownstreamAndScope {

        @Test
        @DisplayName("Tier 1 selects the tightened solver tolerance")
        void tierOneTightensTheSolver() {
            // The tier's only production consumer today is SolverTolerance.forTier. Asserted
            // through it rather than restated, because two answers to the same question is how
            // they come to differ. The expected floors are 03 § 4.2 step 4's: 1e-10 standard,
            // 1e-12 tightened.
            TierAssignmentResult tierOne = assign(TierAssignmentInput.of(
                "PF-1", TierAssignmentSegment.WHOLESALE, 240,
                TierAssignmentFeature.PROJECT_FINANCE));

            assertThat(tierOne.requiresTightenedTolerance()).isTrue();
            assertThat(SolverTolerance.forTier(tierOne.tier()).absoluteFloor())
                .as("03 § 4.2 step 4 tightened floor; it matters because ACPIR 50 makes the EIR the"
                    + " ECL discount rate, so a rate error on a 20-year exposure compounds into"
                    + " lifetime ECL")
                .isEqualByComparingTo(new BigDecimal("1E-12"));
        }

        @Test
        @DisplayName("Tier 2 and Tier 3 take the standard tolerance")
        void lowerTiersTakeTheStandardTolerance() {
            TierAssignmentResult tierTwo = assign(
                TierAssignmentInput.of("HOUSING-1", TierAssignmentSegment.RETAIL, 240));
            TierAssignmentResult tierThree = assign(
                TierAssignmentInput.of("CP-3M", TierAssignmentSegment.TREASURY, 3));

            assertThat(tierTwo.requiresTightenedTolerance()).isFalse();
            assertThat(tierThree.requiresTightenedTolerance()).isFalse();
            assertThat(SolverTolerance.forTier(tierThree.tier()).absoluteFloor())
                .as("03 § 4.2 step 4 standard floor")
                .isEqualByComparingTo(new BigDecimal("1E-10"));
        }

        @Test
        @DisplayName("this unit proposes Tier 3; it does not certify that Tier 3 is permitted")
        void tierThreeIsProposedNotPermitted() {
            // The division with the equivalence-test unit, asserted so it is not accidentally
            // closed later. A 15-year zero-coupon is written here as what it is — a long-tenor
            // treasury holding — and the tenor rule correctly does NOT reach Tier 3 for it. But a
            // three-month zero-coupon bill would, and § 10.3 refuses approximation for
            // zero-coupon instruments "at any tenor" (FR-411, FR-412). That refusal is the
            // equivalence test's, evaluated against invariant TG-1, and this result is the input
            // to it rather than a conclusion ahead of it.
            TierAssignmentResult shortBill = assign(
                TierAssignmentInput.of("ZC-BILL-3M", TierAssignmentSegment.TREASURY, 3));

            assertThat(shortBill.tier())
                .as("03 § 10 Tier 3 row, on tenor; whether the approximation may be USED is a"
                    + " separate gate")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(shortBill.requiresEquivalenceTest())
                .as("the assignment carries the condition forward instead of asserting the"
                    + " permission")
                .isTrue();

            TierAssignmentResult longZeroCoupon = assign(
                TierAssignmentInput.of("ZC-SDL-180M", TierAssignmentSegment.TREASURY, 180));
            assertThat(longZeroCoupon.requiresEquivalenceTest())
                .as("reference case 9's 15-year zero-coupon never reaches Tier 3 in the first"
                    + " place — the tenor rule keeps it at contract level, which is the same"
                    + " answer § 10.3 gives for a different reason")
                .isFalse();
        }
    }
}
