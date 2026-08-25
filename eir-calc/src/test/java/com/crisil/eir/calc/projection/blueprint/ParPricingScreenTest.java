package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariant ST-13 — a structure that prices at par does price to par at its own coupon.
 *
 * <p><b>Why this control exists.</b> INV-2 used to provide it as a side effect. It compared
 * the EIR against the annualised coupon, so any schedule away from par breached it — loudly,
 * and wrongly, on structures where nothing was wrong. Correcting that baseline to subtract
 * the measured par gap (ADR-0009) removed the false breaches and, in the same motion, removed
 * the signal: {@code sign(F - G)} orders correctly whether {@code G} is 0.09 or 6,192.66, so a
 * schedule missing par by thousands now passes INV-2 on arithmetic that is entirely correct.
 * ST-13 is what notices instead, and only where par is a claim about the instrument at all.
 *
 * <p><b>The scope is measured, not reasoned.</b> Every clause of
 * {@link ScheduleBlueprint#pricesAtParUnder} was set by holding all other dimensions fixed on
 * a three-year monthly advance of a million and reading the gap off the projected contractual
 * vector. {@link ScopeIsMeasured} is that survey, kept as assertions. Three of its results
 * contradict what reasoning suggests, and those are the ones worth reading: the day count is
 * irrelevant while the convention is decisive, the whole floating family holds par including
 * collars and ratchets, and a behavioural overlay cannot move the gap at all.
 *
 * <p><b>What it costs and what it catches.</b> One residual evaluation per contract — n
 * fractional powers against a solve's iterations × n. In the blueprint pipeline it can only
 * fail if the engine's own ladder derivation is wrong, because {@code ScheduleBuilder} sizes
 * the instalment from the rate and cannot produce a schedule inconsistent with it; that is a
 * regression net rather than a data-error screen, and {@link ItFires} states the magnitudes it
 * would report. The data-error case arrives with a pre-billed vector — {@code
 * ResiduePolicy.LMS_AUTHORITATIVE}, where the schedule is what the core banking system
 * actually billed — and there an instalment that disagrees with its rate by one rupee a month
 * shows up at a hundred and forty times the band.
 */
class ParPricingScreenTest {

    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);
    private static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(new BigDecimal("0.01"));
    private static final Money MILLION = Money.inr("1000000");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ==================================================== the measured scope

    @Nested
    @DisplayName("the scope of the claim, as measured across the dimensions")
    class ScopeIsMeasured {

        @Test
        @DisplayName("every principal profile prices to par, to the paisa")
        void everyPrincipalProfilePricesToPar() {
            // The principal profile is entirely par-neutral, and that is not obvious: a
            // balloon and a bullet return principal on wildly different schedules. They
            // price to par anyway because every one of them charges interest on whatever
            // balance is outstanding, and the discount assumes exactly that.
            for (PrincipalProfile profile : List.of(
                new PrincipalProfile.LevelAnnuity(),
                new PrincipalProfile.EqualPrincipal(),
                new PrincipalProfile.BulletAtMaturity(),
                new PrincipalProfile.Balloon(Money.inr("200000")),
                new PrincipalProfile.StepLadder(
                    bd("1.1"), 6, PrincipalProfile.StepDirection.UP))) {

                assertThat(gapOf(base(profile, new InterestServicing.ServicedEachPeriod(),
                    Moratorium.none())).atPresentationScale())
                    .as("%s", profile.label())
                    .hasToString("INR 0.00");
            }
        }

        @Test
        @DisplayName("the DAY COUNT is irrelevant and the CONVENTION is decisive")
        void theConventionDecidesNotTheDayCount() {
            // The finding that reasoning gets wrong, and the reason the predicate takes a
            // TimeConvention rather than reading the blueprint's dayCount.
            //
            // ACT/365F and ACT/360 both hold par EXACTLY on a uniform monthly calendar,
            // because that calendar licenses the periodic index and the day count never
            // reaches the discounting at all.
            for (DayCountConvention dayCount : List.of(
                DayCountConvention.ACT_365F,
                DayCountConvention.ACT_360,
                DayCountConvention.THIRTY_360_BOND)) {

                assertThat(gapOf(withDayCount(dayCount)).atPresentationScale())
                    .as("%s under a uniform monthly calendar", dayCount)
                    .hasToString("INR 0.00");
            }

            // But under ACTUAL dating, a schedule spaced in EXACT WHOLE MONTHS still misses
            // par by 261.03 — a monthly rate compounded over year fractions does not
            // reproduce the monthly annuity that sized the instalments. Nothing here is
            // irregular; the measure of time simply is not the one the schedule was built
            // on. So par is not a claim ST-13 can make under actual dating, and the
            // predicate says so.
            ScheduleBlueprint actualDated = withCalendar(customMonthly(0));
            TimeConvention convention = conventionOf(actualDated);
            assertThat(convention).isInstanceOf(TimeConvention.ActualDate.class);
            assertThat(gapOf(actualDated).atPresentationScale()).hasToString("INR 261.03");
            assertThat(actualDated.pricesAtParUnder(convention, 48))
                .as("261.03 is the convention, not a defect, so ST-13 does not claim par")
                .isFalse();

            // And the stub is worse by an order of magnitude, which is the broken-first-period
            // population from 03 § 9 reappearing in the blueprint model.
            assertThat(gapOf(withCalendar(customMonthly(18))).atPresentationScale())
                .hasToString("INR 6130.61");
        }

        @Test
        @DisplayName("the whole floating family prices to par, collars and ratchets included")
        void theFloatingFamilyPricesToPar() {
            // Also not obvious, and the reason the rate clause is stated as a property
            // rather than as a list of admissible profiles. A collar that binds or a
            // ratchet that trips would move the coupons away from the discount rate — but
            // neither is projected as binding at inception, so each prices at its current
            // rate and the leg discounts at that same rate.
            RateProfile.Floating floating = new RateProfile.Floating(
                "MCLR_1Y", bd("150"),
                List.of(VALUE_DATE.plusMonths(12), VALUE_DATE.plusMonths(24)),
                ONE_PERCENT_MONTHLY);

            for (RateProfile profile : List.of(
                new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
                floating,
                new RateProfile.FloatingWithCollar(floating, bd("0.02"), bd("0.005")),
                new RateProfile.MarketSpreadReset(
                    List.of(VALUE_DATE.plusMonths(12)), ONE_PERCENT_MONTHLY),
                new RateProfile.InflationIndexed("CPI_IW", ONE_PERCENT_MONTHLY),
                new RateProfile.Ratchet(
                    List.of(new RateProfile.RatchetTier("DSCR < 1.2", bd("50"))),
                    RateProfile.RatchetTrigger.FINANCIAL_COVENANT, ONE_PERCENT_MONTHLY))) {

                ScheduleBlueprint blueprint = withRate(profile);
                assertThat(gapOf(blueprint).atPresentationScale())
                    .as("%s", profile.label())
                    .hasToString("INR 0.00");
                assertThat(blueprint.pricesAtParUnder(conventionOf(blueprint), 36))
                    .as("%s carries one rate for life and is in scope", profile.label())
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a step coupon is out of scope, and a single-rung one is not")
        void aStepCouponIsOutOfScope() {
            // 1% for a year then 1.5% is 37,079.62 away from par, and correctly so: there
            // is no single contractual rate for the schedule to price against.
            ScheduleBlueprint stepped = withRate(new RateProfile.StepCoupon(List.of(
                new RateProfile.CouponStep(1, ONE_PERCENT_MONTHLY),
                new RateProfile.CouponStep(13, Rate.monthly(bd("0.015"))))));
            assertThat(gapOf(stepped).atPresentationScale()).hasToString("INR -37079.62");
            assertThat(stepped.pricesAtParUnder(conventionOf(stepped), 36)).isFalse();

            // But the clause is the PROPERTY — one rate in force for every period — not the
            // profile's name. A step coupon with a single rung carries one rate for life, so
            // it is in scope and does price to par. Enumerating admissible profile types
            // would have excluded it, and would have excluded any profile written later.
            ScheduleBlueprint oneRung = withRate(new RateProfile.StepCoupon(
                List.of(new RateProfile.CouponStep(1, ONE_PERCENT_MONTHLY))));
            assertThat(oneRung.pricesAtParUnder(conventionOf(oneRung), 36))
                .as("a step coupon that never steps is a fixed rate")
                .isTrue();
            assertThat(gapOf(oneRung).atPresentationScale()).hasToString("INR 0.00");
        }

        @Test
        @DisplayName("deferred-simple and discounted-upfront servicing are out of scope")
        void servicingThatDoesNotPriceAtPar() {
            // S6's finding, reproduced in the blueprint model: 6,056.87 here against
            // StructureFixturesTest's 6,056.91 on its own term and residue policy. Simple
            // accrual collects less than the discount compounds, and that is the
            // instrument.
            ScheduleBlueprint deferred = base(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DeferredSimple(VALUE_DATE.plusMonths(12)),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING));
            assertThat(gapOf(deferred).atPresentationScale()).hasToString("INR 6056.87");
            assertThat(deferred.pricesAtParUnder(conventionOf(deferred), 36)).isFalse();

            // And a discount instrument is DEFINED away from par at its quoted yield, which
            // is why the figure is six digits rather than four.
            ScheduleBlueprint discounted = base(
                new PrincipalProfile.NoneUntilMaturity(),
                new InterestServicing.DiscountedUpfront(), Moratorium.none());
            assertThat(gapOf(discounted).atPresentationScale()).hasToString("INR 301075.05");
            assertThat(discounted.pricesAtParUnder(conventionOf(discounted), 36)).isFalse();
        }

        @Test
        @DisplayName("servicing that charges and settles each period is in scope")
        void servicingThatPricesAtPar() {
            // Capitalisation is in scope, which is the pairing to get right: it does not
            // leave as cash, so leavesAsCashEachPeriod() is false for it, yet compounding
            // into the balance is exactly what the discount assumes. A predicate written as
            // leavesAsCashEachPeriod() would have scoped S5 out of a control it passes.
            assertThat(new InterestServicing.CapitalisedEachPeriod().leavesAsCashEachPeriod())
                .isFalse();
            assertThat(new InterestServicing.CapitalisedEachPeriod().pricesAtParAtItsCoupon())
                .isTrue();

            assertThat(gapOf(base(new PrincipalProfile.BulletAtMaturity(),
                new InterestServicing.CapitalisedEachPeriod(), Moratorium.none()))
                .atPresentationScale()).hasToString("INR 0.00");
            assertThat(gapOf(base(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedThenCombined(12), Moratorium.none()))
                .atPresentationScale()).hasToString("INR 0.00");
        }

        @Test
        @DisplayName("an undrawn tranche shows up in the gap as itself, to the paisa")
        void anUndrawnTrancheIsTheGap() {
            // The cleanest result in the survey. The contractual leg rolls from the full
            // notional, so principal still undrawn at inception IS the par gap: 400,000
            // advanced at inception against a million of notional gives a gap of exactly
            // 600,000.00. Nothing is wrong with the facility; par is not a claim about it.
            List<Tranche> draws = List.of(
                new Tranche(VALUE_DATE, 0, Money.inr("400000")),
                new Tranche(VALUE_DATE.plusMonths(3), 3, Money.inr("300000")),
                new Tranche(VALUE_DATE.plusMonths(6), 6, Money.inr("300000")));

            ScheduleBlueprint tranched = new ScheduleBlueprint(
                MILLION, Money.INR, VALUE_DATE, VALUE_DATE.plusYears(3),
                new DisbursementProfile.Tranched(draws, draws, bd("0.05")),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new RateProfile.Fixed(ONE_PERCENT_MONTHLY), OptionSchedule.none(),
                new BehaviouralOverlay.Contractual("no prepayment"),
                ScheduleCalendar.monthly(), DayCountConvention.THIRTY_360_BOND,
                ResiduePolicy.FINAL_PERIOD_PLUG, 36);

            assertThat(gapOf(tranched).atPresentationScale()).hasToString("INR 600000.00");
            assertThat(tranched.pricesAtParUnder(conventionOf(tranched), 36)).isFalse();

            // A tranched profile whose draws all land on the value date is in scope, because
            // the clause is about principal being in hand at inception and not about the
            // profile's name.
            List<Tranche> sameDay = List.of(
                new Tranche(VALUE_DATE, 0, Money.inr("400000")),
                new Tranche(VALUE_DATE, 0, Money.inr("600000")));
            assertThat(new DisbursementProfile.Tranched(sameDay, sameDay, bd("0.05"))
                .advancesInFullAtInception(VALUE_DATE))
                .isTrue();
        }

        @Test
        @DisplayName("a behavioural overlay cannot move the gap, because the gap is contractual")
        void behaviourCannotMoveTheGap() {
            // Asserted rather than argued, because the argument is easy to get backwards. A
            // 15% prepayment curve reshapes the EXPECTED leg and shortens the expected life;
            // the par gap is a property of the CONTRACTUAL leg, so it is untouched. If this
            // ever fails, the overlay has leaked into the contractual vector.
            ScheduleBlueprint prepaying = new ScheduleBlueprint(
                MILLION, Money.INR, VALUE_DATE, VALUE_DATE.plusYears(3),
                new DisbursementProfile.Single(VALUE_DATE, MILLION),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.Fixed(ONE_PERCENT_MONTHLY), OptionSchedule.none(),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.15")),
                ScheduleCalendar.monthly(), DayCountConvention.THIRTY_360_BOND,
                ResiduePolicy.FINAL_PERIOD_PLUG, 36);

            assertThat(gapOf(prepaying).atPresentationScale()).hasToString("INR 0.00");
            assertThat(prepaying.pricesAtParUnder(conventionOf(prepaying), 36)).isTrue();
        }

        @Test
        @DisplayName("every moratorium kind holds par when servicing charges each period")
        void moratoriaHoldPar() {
            assertThat(gapOf(base(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM))).atPresentationScale())
                .hasToString("INR 0.00");
            assertThat(gapOf(base(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING))).atPresentationScale())
                .hasToString("INR 0.00");
        }
    }

    // ==================================================== the two derivations agree

    @Nested
    @DisplayName("the gap, two independent ways")
    class TwoDerivations {

        @Test
        @DisplayName("the direct P - PV agrees with the leg's terminal balance discounted back")
        void theTwoDerivationsAgree() {
            // ST-13 takes the direct route because the blueprint pipeline rolls no
            // contractual leg; TwoLegResult.parGap takes the leg route because the
            // reconciliation already holds one. They must be the same number or one of the
            // two invariants is measuring something the other is not, and INV-2 and ST-13
            // would then disagree about the same schedule.
            //
            // Asserted on a structure with a NON-ZERO gap, because zero equals zero for
            // reasons that have nothing to do with either derivation being right.
            ScheduleBlueprint deferred = base(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DeferredSimple(VALUE_DATE.plusMonths(12)),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING));

            BlueprintProjection projected = project(deferred);
            TimeConvention convention = projected.projection().recommendedConvention();
            Rate coupon = ConventionSelector.rateUnder(convention, ONE_PERCENT_MONTHLY);
            AmortisationResult leg = AmortisationEngine.contractualLeg(
                deferred.notional(), coupon, projected.projection().contractual(), convention);

            assertThat(TwoLegResult.parGap(leg, coupon).atPresentationScale())
                .as("the leg route")
                .isEqualTo(gapOf(deferred).atPresentationScale());
        }
    }

    // ==================================================== it actually fires

    @Nested
    @DisplayName("the control fires, and the band is measured rather than constant")
    class ItFires {

        @Test
        @DisplayName("an instalment one rupee short of its rate reports at 140x the band")
        void anInstalmentThatDisagreesWithItsRate() {
            // The data error the control exists for, at the magnitude it actually produces.
            // A billed instalment one rupee a month below what the rate sizes leaves a
            // present-value shortfall of 30.11 over 36 periods at 1% a month — the PV of a
            // 1.00 annuity — against a rounding band of 0.22. That is 140 times the band,
            // so the finding is unambiguous rather than marginal.
            Money band = BehaviouralAdjuster.roundingResidueBound(ONE_PERCENT_MONTHLY, 36, Money.INR);
            assertThat(band.atPresentationScale()).hasToString("INR 0.22");

            InvariantResult breach =
                InvariantChecks.parPricing(true, Money.inr("30.11"), band);
            assertThat(breach.satisfied()).isFalse();
            assertThat(breach.id()).isEqualTo(InvariantId.ST_13);
            assertThat(breach.detail())
                .contains("does not price to par at the rate it bills at")
                .contains("an instalment, a rate or a principal that disagree");
            assertThat(breach.deviation())
                .as("how far past the band, so a triage queue can sort by it")
                .isEqualByComparingTo(bd("30.11").subtract(band.amount()));
        }

        @Test
        @DisplayName("it fires on a shortfall in either direction")
        void itFiresOnAnOvercollectionToo() {
            Money band = BehaviouralAdjuster.roundingResidueBound(ONE_PERCENT_MONTHLY, 36, Money.INR);
            assertThat(InvariantChecks.parPricing(true, Money.inr("-30.11"), band).satisfied())
                .as("over-collecting is as much a disagreement as under-collecting")
                .isFalse();
        }

        @Test
        @DisplayName("no constant band could serve: 1.00 breaches at 36 periods and passes at 240")
        void theBandMustBeMeasured() {
            // The reason the band is derived rather than written down, and the same reason
            // ST-9 derives its own. Half a paisa per instalment accumulates at the coupon,
            // so the largest gap rounding alone can produce grows with the tenor: 0.13 over
            // 24 periods, 0.22 over 36, 4.95 over 240, 17.47 over 360.
            //
            // A gap of exactly one rupee is therefore a defect on a three-year loan and
            // noise on a twenty-year one. Any single constant is wrong at one end of the
            // book: tight enough for 36 periods and it fires on every long-tenor contract;
            // loose enough for 240 and it misses a real error on every short one.
            Money oneRupee = Money.inr("1.00");
            Money shortTenor = BehaviouralAdjuster.roundingResidueBound(
                ONE_PERCENT_MONTHLY, 36, Money.INR);
            Money longTenor = BehaviouralAdjuster.roundingResidueBound(
                ONE_PERCENT_MONTHLY, 240, Money.INR);

            assertThat(shortTenor.atPresentationScale()).hasToString("INR 0.22");
            assertThat(longTenor.atPresentationScale()).hasToString("INR 4.95");

            assertThat(InvariantChecks.parPricing(true, oneRupee, shortTenor).satisfied())
                .as("a rupee is 4.6x the band at 36 periods")
                .isFalse();
            assertThat(InvariantChecks.parPricing(true, oneRupee, longTenor).satisfied())
                .as("and a fifth of it at 240")
                .isTrue();
        }

        @Test
        @DisplayName("a rounded-down billed instalment sits inside the band")
        void theLmsResidueIsInsideTheBand() {
            // The population the band exists to pass. Under LMS_AUTHORITATIVE the vector is
            // what the core banking system billed, the annuity is rounded down to the
            // paisa, and the schedule under-collects by 0.01 of present value against a
            // band of 0.22. Measured on the engine, not asserted from the formula.
            ScheduleBlueprint lms = withResidue(ResiduePolicy.LMS_AUTHORITATIVE);
            assertThat(gapOf(lms).atPresentationScale()).hasToString("INR -0.01");

            InvariantResult result = st13(lms);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("on a structure that prices at par");
        }

        @Test
        @DisplayName("out of scope passes without examining the gap, and says which it is")
        void outOfScopePassesAndSaysSo() {
            // A pass that means "no claim made" must not read like a pass that means
            // "checked and fine". A reviewer reading a green ST-13 on a deferred-simple
            // structure needs to know the control abstained, or the green is misleading.
            InvariantResult abstained =
                InvariantChecks.parPricing(false, Money.inr("6056.87"), Money.inr("0.22"));
            assertThat(abstained.satisfied()).isTrue();
            assertThat(abstained.detail())
                .contains("the structure does not price at par at its coupon")
                .contains("ST-13 makes no claim about it")
                .doesNotContain("does not price to par at the rate it bills at");
        }

        @Test
        @DisplayName("a negative band is a caller error, not a wider band")
        void aNegativeBandIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> InvariantChecks.parPricing(
                    true, Money.inr("1"), Money.inr("-0.22")))
                .withMessageContaining("magnitude");
        }
    }

    // ==================================================== wiring

    @Nested
    @DisplayName("as the projector asserts it")
    class AsAsserted {

        @Test
        @DisplayName("ST-13 is published exactly once on every projection")
        void publishedExactlyOnce() {
            List<InvariantResult> invariants = project(base(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none())).invariants();

            assertThat(invariants)
                .filteredOn(result -> result.id() == InvariantId.ST_13)
                .as("a named control an auditor asks for by name gets one answer")
                .hasSize(1);
        }

        @Test
        @DisplayName("a failing ST-13 is returned rather than thrown, unlike ST-10")
        void reportedRatherThanThrown() {
            // The deliberate difference from ST-10, and the reason it is worth a test rather
            // than a comment. ST-10 fails only when convention selection has gone wrong,
            // which is an engine defect that nothing downstream can compensate for, so the
            // projector throws and the contract yields no rate. ST-13 fails on a bad input,
            // which is an exception-queue item: quarantine the contract with its evidence,
            // let the rest of the close proceed, and send the fix to the feed. A batch of
            // ten million that aborts on one mis-keyed instalment is the worse control.
            InvariantResult breach = InvariantChecks.parPricing(
                true, Money.inr("30.11"), Money.inr("0.22"));
            assertThat(breach.satisfied()).isFalse();
            assertThat(breach.deviation()).isPositive();

            // And the projector's ST-10 is the one that throws, which is what makes the
            // contrast real rather than asserted.
            assertThat(InvariantId.ST_10.statement()).isNotEqualTo(InvariantId.ST_13.statement());
        }

        @Test
        @DisplayName("the periods the band is sized on are the LADDER's, not the stated term")
        void thebandIsSizedOnTheLadder() {
            // A moratorium that extends the term makes the ladder longer than the stated
            // maturity implies, and the band grows with the period count. Sizing it on the
            // stated term would understate the band on exactly the population whose
            // schedule is longest.
            ScheduleBlueprint extended = base(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM));

            BlueprintProjection projected = project(extended);
            assertThat(projected.contractualLadder().length())
                .as("twelve interest-only periods on top of the three-year ladder")
                .isGreaterThan(36);
            assertThat(st13(extended).detail())
                .as("so the band is the longer one, 0.31 rather than 0.22")
                .contains("bound of INR 0.31");
        }
    }

    // ==================================================== seams

    private static InvariantResult st13(ScheduleBlueprint blueprint) {
        BlueprintProjection projected = project(blueprint);
        return projected.invariants().stream()
            .filter(result -> result.id() == InvariantId.ST_13)
            .findFirst()
            .orElseThrow(() -> new AssertionError("ST-13 was not published"));
    }

    private static Money gapOf(ScheduleBlueprint blueprint) {
        BlueprintProjection projected = project(blueprint);
        TimeConvention convention = projected.projection().recommendedConvention();
        Rate coupon = ConventionSelector.rateUnder(
            convention, blueprint.rate().rateForPeriod(1));
        return blueprint.notional().minus(com.crisil.eir.calc.Discounting.presentValueMoney(
            coupon.periodic(), projected.projection().contractual(), convention));
    }

    private static TimeConvention conventionOf(ScheduleBlueprint blueprint) {
        return project(blueprint).projection().recommendedConvention();
    }

    private static BlueprintProjection project(ScheduleBlueprint blueprint) {
        return new BlueprintProjector(blueprint).projectBlueprint(List.of());
    }

    private static ScheduleCalendar customMonthly(int stubDays) {
        java.util.List<LocalDate> dates = new java.util.ArrayList<>();
        for (int period = 1; period <= 36; period++) {
            dates.add(VALUE_DATE.plusMonths(period).plusDays(stubDays));
        }
        return new ScheduleCalendar(
            ScheduleCalendar.Frequency.CUSTOM, ScheduleCalendar.BusinessDayConvention.NONE,
            Set.of(), ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, dates);
    }

    private static ScheduleBlueprint base(
        PrincipalProfile principal, InterestServicing servicing, Moratorium moratorium) {
        return build(principal, servicing, moratorium, new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
            DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG);
    }

    private static ScheduleBlueprint withDayCount(DayCountConvention dayCount) {
        return build(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
            new RateProfile.Fixed(ONE_PERCENT_MONTHLY), dayCount, ResiduePolicy.FINAL_PERIOD_PLUG);
    }

    private static ScheduleBlueprint withResidue(ResiduePolicy residue) {
        return build(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
            new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
            DayCountConvention.THIRTY_360_BOND, residue);
    }

    private static ScheduleBlueprint withRate(RateProfile rate) {
        return build(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(), rate,
            DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG);
    }

    private static ScheduleBlueprint withCalendar(ScheduleCalendar calendar) {
        return new ScheduleBlueprint(
            MILLION, Money.INR, VALUE_DATE, VALUE_DATE.plusYears(4),
            new DisbursementProfile.Single(VALUE_DATE, MILLION),
            new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
            new RateProfile.Fixed(ONE_PERCENT_MONTHLY), OptionSchedule.none(),
            new BehaviouralOverlay.Contractual("prepayment not permitted by the facility"),
            calendar, DayCountConvention.ACT_365F, ResiduePolicy.FINAL_PERIOD_PLUG, 48);
    }

    private static ScheduleBlueprint build(
        PrincipalProfile principal, InterestServicing servicing, Moratorium moratorium,
        RateProfile rate, DayCountConvention dayCount, ResiduePolicy residue) {

        return new ScheduleBlueprint(
            MILLION, Money.INR, VALUE_DATE, VALUE_DATE.plusYears(3),
            new DisbursementProfile.Single(VALUE_DATE, MILLION),
            principal, servicing, moratorium, rate, OptionSchedule.none(),
            new BehaviouralOverlay.Contractual("prepayment not permitted by the facility"),
            ScheduleCalendar.monthly(), dayCount, residue, 36);
    }
}
