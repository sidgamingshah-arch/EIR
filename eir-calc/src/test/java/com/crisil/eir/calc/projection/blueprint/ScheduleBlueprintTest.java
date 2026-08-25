package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The blueprint dimensions and the coherence they are checked for
 * (docs 09 § 2, invariants ST-3, ST-5, ST-10, ST-11).
 *
 * <p>Five types are exercised here because they are one mechanism: a
 * {@link ScheduleBlueprint} composes the dimensions and refuses an incoherent
 * combination, {@link ScheduleTerm} divides its length into phases,
 * {@link ScheduleCalendar} and {@link ScheduleDates} say when the instalments fall
 * due, and {@link InstalmentLadder} is what the four consumers downstream pass
 * around. Testing them apart from the projector is deliberate: a wrong instalment is
 * an arithmetic error visible in one number, while a wrong due date or a silently
 * accepted contradiction is visible only in a rate that came out slightly wrong three
 * stages later.
 *
 * <p><b>Every figure below is either a calendar fact or a fixture.</b> Day-of-week and
 * month-length claims are stated in the comment that uses them (2026-02-28 is a
 * Saturday; February 2029 has 28 days; 2032 is a leap year). Period counts come from
 * docs 09 § 6 fixtures S4 and S5 — S4 bills eighteen instalments over periods 7 to 24
 * after a six-period holiday, S5 bills twenty-four starting in period 13 after a
 * twelve-period one — and from the tenor of the S1–S6 base loan, 1,000,000 over 24
 * monthly periods. Nothing here was read back out of the implementation.
 *
 * <p>Two tests are {@link Disabled} and each names a production defect. Neither is
 * fixed here; see the comment on each.
 */
class ScheduleBlueprintTest {

    /** The S1–S6 base loan: 1,000,000 over 24 monthly periods at 1% a period. */
    private static final Money NOTIONAL = Money.inr("1000000");

    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** Twenty-four monthly periods after the value date. */
    private static final LocalDate STATED_MATURITY = LocalDate.of(2028, 4, 1);

    /** A month-end value date: 31 January 2026, itself a Saturday. */
    private static final LocalDate MONTH_END = LocalDate.of(2026, 1, 31);

    /** 29 February 2028 — a leap day, and the last day of its month. */
    private static final LocalDate LEAP_DAY = LocalDate.of(2028, 2, 29);

    private static final int ECL_HORIZON_PERIODS = 24;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------- dimension defaults

    private static DisbursementProfile singleDraw() {
        return new DisbursementProfile.Single(VALUE_DATE, NOTIONAL);
    }

    private static RateProfile fixed() {
        return new RateProfile.Fixed(Rate.monthly(bd("0.01")));
    }

    private static RateProfile floating() {
        return new RateProfile.Floating("EBLR", bd("250"),
            List.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)), Rate.monthly(bd("0.01")));
    }

    private static BehaviouralOverlay contractual() {
        return new BehaviouralOverlay.Contractual("no prepayment permitted on this product");
    }

    private static ScheduleCalendar monthly(ScheduleCalendar.EndOfMonthRule rule) {
        return new ScheduleCalendar(ScheduleCalendar.Frequency.MONTHLY,
            ScheduleCalendar.BusinessDayConvention.NONE, Set.of(), rule, List.of());
    }

    private static ScheduleCalendar calendar(
        ScheduleCalendar.Frequency frequency,
        ScheduleCalendar.BusinessDayConvention convention,
        ScheduleCalendar.EndOfMonthRule rule) {

        return new ScheduleCalendar(frequency, convention, Set.of(), rule, List.of());
    }

    /** The whole composition, for the tests that vary something other than a dimension. */
    private static ScheduleBlueprint compose(
        DisbursementProfile disbursement,
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        RateProfile rate,
        OptionSchedule options,
        BehaviouralOverlay behaviour,
        ScheduleCalendar calendar) {

        return new ScheduleBlueprint(NOTIONAL, Money.INR, VALUE_DATE, STATED_MATURITY,
            disbursement, principal, servicing, moratorium, rate, options, behaviour, calendar,
            DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
            ECL_HORIZON_PERIODS);
    }

    /** The three dimensions most of the coherence rules are about; the rest vanilla. */
    private static ScheduleBlueprint compose(
        PrincipalProfile principal, InterestServicing servicing, Moratorium moratorium) {

        return compose(singleDraw(), principal, servicing, moratorium, fixed(),
            OptionSchedule.none(), contractual(),
            monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH));
    }

    /** The plain 24-period EMI loan, coherent in every dimension. */
    private static ScheduleBlueprint vanilla() {
        return compose(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none());
    }

    // ================================================ 1. coherence — invariant ST-11

    @Nested
    @DisplayName("an incoherent combination is refused at construction, naming the conflict (ST-11)")
    class Coherence {

        @Test
        @DisplayName("a PRINCIPAL_ONLY holiday on a NONE_UNTIL_MATURITY profile suspends nothing")
        void principalOnlyHolidayOnAProfileThatRepaysNoPrincipalAnyway() {
            // Both dimensions say "no principal before maturity". Accepting the pair would
            // leave the holiday as a field that reads as a concession and changes no flow,
            // which is worse than a rejection: a reader would take the concession as priced.
            assertThatThrownBy(() -> compose(new PrincipalProfile.NoneUntilMaturity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.principalOnly(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incoherent blueprint (ST-11)")
                .hasMessageContaining("NONE_UNTIL_MATURITY already repays no principal")
                .hasMessageContaining("PRINCIPAL_ONLY holiday suspends nothing");
        }

        @Test
        @DisplayName("a discount instrument has no instalment stream for a holiday to suspend")
        void aDiscountInstrumentCannotCarryAHoliday() {
            // Kind is FULL_INTEREST_DEFERRED_SIMPLE rather than PRINCIPAL_ONLY so that this
            // fails on the discount rule alone: PRINCIPAL_ONLY would also trip the rule
            // above and the test would pass on the wrong conflict.
            assertThatThrownBy(() -> compose(new PrincipalProfile.NoneUntilMaturity(),
                new InterestServicing.DiscountedUpfront(),
                new Moratorium(6, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collects its interest at inception")
                .hasMessageContaining("no instalment stream for a holiday to suspend");
        }

        @Test
        @DisplayName("DISCOUNTED_UPFRONT interest cannot sit on an amortising principal profile")
        void discountedUpfrontInterestNeedsASingleRedemption() {
            // A T-bill's interest is the discount. An instrument that amortises has interest
            // billed period by period, so the two describe different instruments — and the
            // rejection names the profile, which is what makes the message actionable.
            assertThatThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DiscountedUpfront(), Moratorium.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCOUNTED_UPFRONT interest belongs to an instrument"
                    + " redeemed in one flow")
                .hasMessageContaining("LEVEL_ANNUITY");
        }

        @Test
        @DisplayName("a FULL_INTEREST_CAPITALISED holiday requires servicing that compounds")
        void capitalisingHolidayWithoutCompoundingServicing() {
            // The 34.6 bp in the message is fixtures S5 against S6 — 12.965053% against
            // 12.619315% p.a. on the same loan. That is why this pair cannot be waved
            // through as a labelling difference: it is the whole gap between the two.
            assertThatThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.fullyCapitalised(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires servicing that compounds")
                .hasMessageContaining("SERVICED_EACH_PERIOD does not")
                .hasMessageContaining("34.6 bp");
        }

        @Test
        @DisplayName("a FULL_INTEREST_DEFERRED_SIMPLE holiday must not compound")
        void deferredSimpleHolidayWithCompoundingServicing() {
            // The mirror of the rule above, and it has to be its own rule: an engine that
            // only checked one direction would let a deferred-simple holiday compound, which
            // overstates income on exactly the S6 shape.
            assertThatThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not compound")
                .hasMessageContaining("CAPITALISED_EACH_PERIOD does");
        }

        @Test
        @DisplayName("a utilisation-driven revolver cannot carry an amortising principal profile")
        void revolverHasNoContractualRepaymentSchedule() {
            assertThatThrownBy(() -> compose(
                new DisbursementProfile.UtilisationDriven(Money.inr("2000000"), bd("0.50")),
                new PrincipalProfile.LevelAnnuity(), new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(), fixed(), OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no contractual repayment schedule")
                .hasMessageContaining("LEVEL_ANNUITY")
                .hasMessageContaining("ACPIR 54");
        }

        @Test
        @DisplayName("a CONVERSION option sends the instrument to FVTPL, where no EIR arises (ST-12)")
        void conversionOptionIsRefusedRatherThanProjected() {
            // The cliff, not a gradient: the whole instrument leaves amortised cost, so a
            // blueprint for it is work commissioned on a contract that has no EIR at all.
            OptionSchedule convertible = new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CONVERSION,
                    OptionSchedule.OptionHolder.HOLDER_LENDER, LocalDate.of(2027, 4, 1))),
                ExercisePolicy.CONTRACTUAL_MATURITY);

            assertThatThrownBy(() -> compose(singleDraw(), new PrincipalProfile.BulletAtMaturity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                convertible, contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fails SPPI")
                .hasMessageContaining("ST-12")
                .hasMessageContaining("Run the classification gate");
        }

        @Test
        @DisplayName("NEXT_REPRICING is refused on a rate profile that never reprices")
        void nextRepricingNeedsARepricingDateToAmortiseTo() {
            // A prepayment option is present so that OptionSchedule accepts a policy other
            // than CONTRACTUAL_MATURITY; the conflict under test is the fixed rate.
            OptionSchedule prepayable = new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PREPAYMENT,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, LocalDate.of(2027, 4, 1))),
                ExercisePolicy.NEXT_REPRICING);

            assertThatThrownBy(() -> compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                prepayable, contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEXT_REPRICING policy amortises to a repricing date")
                .hasMessageContaining("FIXED")
                .hasMessageContaining("different policy from the one recorded");

            // And the same schedule on a floating profile is accepted — the rule is about
            // the anchor, not about the election.
            assertThat(compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), floating(),
                prepayable, contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))
                .options().exercisePolicy())
                .isEqualTo(ExercisePolicy.NEXT_REPRICING);
        }

        @Test
        @DisplayName("REVOLVER_BEHAVIOUR is refused on a facility that is not a drawn limit")
        void revolverBehaviourNeedsARevolverFacility() {
            BehaviouralOverlay cardBook = new BehaviouralOverlay.RevolverBehaviour(
                36, List.of(bd("0.40"), bd("0.45")), "ACPIR 46(2)(iii) analysis CARD-2026-01");

            assertThatThrownBy(() -> compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                OptionSchedule.none(), cardBook,
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVOLVER_BEHAVIOUR describes a drawn-and-repaid limit")
                .hasMessageContaining("SINGLE");
        }

        @Test
        @DisplayName("every conflict is reported at once, not one refusal per run")
        void conflictsAreReportedWhole() {
            // Two rules at once: DISCOUNTED_UPFRONT against an amortising profile, and a
            // capitalising holiday against servicing that does not compound. Reporting one
            // and rediscovering the other on the next run is how a misconfiguration turns
            // into an afternoon, which is the reason coherenceConflicts returns a list.
            assertThatThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DiscountedUpfront(), Moratorium.fullyCapitalised(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCOUNTED_UPFRONT interest belongs to an instrument"
                    + " redeemed in one flow")
                .hasMessageContaining("requires servicing that compounds");

            assertThat(ScheduleBlueprint.coherenceConflicts(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DiscountedUpfront(), Moratorium.fullyCapitalised(6),
                singleDraw(), fixed(), OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)))
                .hasSize(2);
        }

        @Test
        @DisplayName("the coherent product families are accepted")
        void coherentCompositionsPass() {
            // A T-bill: interest collected at inception, principal in one flow, no holiday.
            assertThat(ScheduleBlueprint.coherenceConflicts(new PrincipalProfile.NoneUntilMaturity(),
                new InterestServicing.DiscountedUpfront(), Moratorium.none(), singleDraw(),
                fixed(), OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))).isEmpty();

            // The S5 education loan: a full holiday whose interest capitalises, then an
            // annuity struck on the grown balance.
            assertThat(compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(), Moratorium.fullyCapitalised(12))
                .moratorium().kind())
                .isEqualTo(Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED);

            // A card book: a drawn limit, no repayment schedule, behavioural life modelled.
            ScheduleBlueprint revolver = compose(
                new DisbursementProfile.UtilisationDriven(Money.inr("2000000"), bd("0.50")),
                new PrincipalProfile.BulletAtMaturity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                OptionSchedule.none(),
                new BehaviouralOverlay.RevolverBehaviour(36, List.of(bd("0.40")),
                    "ACPIR 46(2)(iii) analysis CARD-2026-01"),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH));
            assertThat(revolver.behaviour().altersFlows()).isTrue();

            // A crop-cycle loan. The calendar is unequal and that is a fact about the
            // instrument, not an incoherence — see the disabled test in Calendar below for
            // the ST-11 condition that was evidently meant to police it.
            ScheduleCalendar harvest = ScheduleCalendar.seasonal(
                List.of(LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15)));
            assertThat(ScheduleBlueprint.coherenceConflicts(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), singleDraw(),
                fixed(), OptionSchedule.none(), contractual(), harvest)).isEmpty();
        }
    }

    // ==================================================== 2. the non-dimension guards

    @Nested
    @DisplayName("the scalar fields are checked before any dimension is looked at")
    class ConstructionGuards {

        @Test
        @DisplayName("a notional in another currency than the blueprint is refused")
        void currencyMismatchIsRefused() {
            // Not coerced and not converted: a blueprint carrying a USD notional under an
            // INR heading produces amounts nobody can reconcile, and Money's own currency
            // discipline would only catch it at the first arithmetic.
            Money dollars = Money.of("1000000", Currency.getInstance("USD"));

            assertThatThrownBy(() -> new ScheduleBlueprint(dollars, Money.INR, VALUE_DATE,
                STATED_MATURITY, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
                ECL_HORIZON_PERIODS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notional is USD but the blueprint is INR");
        }

        @Test
        @DisplayName("a zero or negative notional is refused")
        void notionalMustBePositive() {
            for (Money bad : List.of(Money.zero(Money.INR), Money.inr("-1000"))) {
                assertThatThrownBy(() -> new ScheduleBlueprint(bad, Money.INR, VALUE_DATE,
                    STATED_MATURITY, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                    OptionSchedule.none(), contractual(),
                    monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                    DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
                    ECL_HORIZON_PERIODS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notional must be positive");
            }
        }

        @Test
        @DisplayName("stated maturity must follow the value date, and equality is not enough")
        void maturityMustFollowInception() {
            for (LocalDate bad : List.of(VALUE_DATE, VALUE_DATE.minusDays(1))) {
                assertThatThrownBy(() -> new ScheduleBlueprint(NOTIONAL, Money.INR, VALUE_DATE,
                    bad, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                    OptionSchedule.none(), contractual(),
                    monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                    DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
                    ECL_HORIZON_PERIODS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must follow valueDate");
            }
        }

        @Test
        @DisplayName("the ECL horizon is a separate input and must be at least one period")
        void eclHorizonIsRequiredAndPositive() {
            // ACPIR 46(1): the horizon is the maximum contractual period including any
            // extension option. A zero horizon is an unset field, and defaulting it from
            // the term is the derivation the separate parameter exists to prevent.
            assertThatThrownBy(() -> new ScheduleBlueprint(NOTIONAL, Money.INR, VALUE_DATE,
                STATED_MATURITY, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eclHorizonPeriods must be >= 1, got 0");
        }

        @Test
        @DisplayName("a missing dimension is named, and named before the coherence pass runs")
        void missingDimensionsAreNamed() {
            // Ordering matters here: coherenceConflicts dereferences every dimension, so a
            // null calendar reaching it would surface as a NullPointerException from inside
            // the rule set with no field name in it.
            assertThatThrownBy(() -> new ScheduleBlueprint(NOTIONAL, Money.INR, VALUE_DATE,
                STATED_MATURITY, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), fixed(),
                OptionSchedule.none(), contractual(), null,
                DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
                ECL_HORIZON_PERIODS))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("calendar");

            assertThatThrownBy(() -> new ScheduleBlueprint(NOTIONAL, Money.INR, VALUE_DATE,
                STATED_MATURITY, singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), null, fixed(),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                DayCountConvention.THIRTY_360_BOND, ResiduePolicy.FINAL_PERIOD_PLUG,
                ECL_HORIZON_PERIODS))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("moratorium");
        }
    }

    // ============================================== 3. what the composition then reports

    @Nested
    @DisplayName("the composition answers the two questions the stages downstream ask of it")
    class Composition {

        @Test
        @DisplayName("the B5.4.4 election is available only where the rate actually reprices")
        void nextRepricingAvailabilityFollowsTheRateProfile() {
            // The election needs an anchor. Reporting it available on a fixed-rate loan is
            // what lets a recorded NEXT_REPRICING policy quietly become expected life.
            assertThat(vanilla().nextRepricingIsAvailable()).isFalse();

            RateProfile.Floating base = (RateProfile.Floating) floating();
            assertThat(compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), base,
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))
                .nextRepricingIsAvailable()).isTrue();

            // A collar is still a repricing instrument: the cap is a contractual term, not
            // the absence of a reset.
            assertThat(compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.FloatingWithCollar(base, bd("0.02"), bd("0.005")),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))
                .nextRepricingIsAvailable()).isTrue();

            assertThat(compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.MarketSpreadReset(List.of(LocalDate.of(2027, 4, 1)),
                    Rate.monthly(bd("0.01"))),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))
                .nextRepricingIsAvailable()).isTrue();

            // A step-up coupon is pre-determined, not a reset: it has no repricing date.
            assertThat(compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.StepCoupon(List.of(
                    new RateProfile.CouponStep(1, Rate.monthly(bd("0.01"))),
                    new RateProfile.CouponStep(13, Rate.monthly(bd("0.012"))))),
                OptionSchedule.none(), contractual(),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH))
                .nextRepricingIsAvailable()).isFalse();
        }

        @Test
        @DisplayName("describe() states every dimension, so the trace shows what was composed")
        void describeNamesTheWholeComposition() {
            // The one line an auditor reads to see which combination produced a figure. A
            // dimension omitted here is a dimension nobody can see was applied — which is
            // why the moratorium and options segments are asserted in both their states.
            assertThat(compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(), Moratorium.fullyCapitalised(12))
                .describe())
                .isEqualTo("SINGLE + LEVEL_ANNUITY + CAPITALISED_EACH_PERIOD"
                    + " + MORATORIUM(12 FULL_INTEREST_CAPITALISED) + FIXED + NO_OPTIONS"
                    + " + CONTRACTUAL + MONTHLY");

            OptionSchedule prepayable = new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PREPAYMENT,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, LocalDate.of(2027, 4, 1))),
                ExercisePolicy.NEXT_REPRICING);
            ScheduleBlueprint mortgage = compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), floating(),
                prepayable, new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH));

            assertThat(mortgage.describe())
                .isEqualTo("SINGLE + LEVEL_ANNUITY + SERVICED_EACH_PERIOD + NO_MORATORIUM"
                    + " + FLOATING(EBLR + 250bp) + OPTIONS(1, NEXT_REPRICING) + CPR(0.08)"
                    + " + MONTHLY");
            assertThat(mortgage.isOptioned()).isTrue();
            assertThat(vanilla().isOptioned()).isFalse();
        }
    }

    // ================================================= 4. the term and its phase split

    @Nested
    @DisplayName("the term records whether a holiday lengthened the instrument or was absorbed")
    class Term {

        private static ScheduleBlueprint withHoliday(
            Moratorium moratorium, InterestServicing servicing) {

            return compose(new PrincipalProfile.LevelAnnuity(), servicing, moratorium);
        }

        @Test
        @DisplayName("EXTEND_TERM grants the full amortisation term after the holiday (S5)")
        void extendTermRunsPastStatedMaturity() {
            // Fixture S5: twelve periods with nothing paid, then twenty-four EMIs of
            // 53,043.57. Twenty-four, not twelve — extending a term means the borrower keeps
            // the whole original amortisation term after the holiday, so the ladder runs to
            // period 36 on a contract whose stated maturity is period 24.
            ScheduleTerm term = ScheduleTerm.of(withHoliday(Moratorium.fullyCapitalised(12),
                new InterestServicing.CapitalisedEachPeriod()));

            assertThat(term.statedPeriods()).isEqualTo(24);
            assertThat(term.moratoriumPeriods()).isEqualTo(12);
            assertThat(term.interestOnlyPeriods()).isZero();
            assertThat(term.amortisingPeriods()).isEqualTo(24);
            assertThat(term.ladderPeriods()).isEqualTo(36);
            assertThat(term.firstAmortisingPeriod()).isEqualTo(13);
            assertThat(term.repaymentPeriods()).isEqualTo(24);
            assertThat(term.extendsPastStatedMaturity()).isTrue();
        }

        @Test
        @DisplayName("COMPRESS_REMAINING holds maturity, so the holiday eats repayment periods (S4)")
        void compressRemainingHoldsStatedMaturity() {
            // Fixture S4: six interest-serviced periods, then EMI 60,982.05 for periods 7 to
            // 24 — eighteen instalments, not twenty-four. The instalment rises from
            // 47,073.47 precisely because the holiday was absorbed rather than added.
            ScheduleTerm term = ScheduleTerm.of(withHoliday(
                new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new InterestServicing.ServicedEachPeriod()));

            assertThat(term.moratoriumPeriods()).isEqualTo(6);
            assertThat(term.amortisingPeriods()).isEqualTo(18);
            assertThat(term.ladderPeriods()).isEqualTo(24);
            assertThat(term.firstAmortisingPeriod()).isEqualTo(7);
            assertThat(term.extendsPastStatedMaturity()).isFalse();
        }

        @Test
        @DisplayName("BALLOON_ARREARS holds maturity exactly as COMPRESS_REMAINING does")
        void balloonArrearsDoesNotExtendTheTerm() {
            // The two differ only in where the holiday's unpaid interest goes, which is the
            // servicing profile's business. A term calculation that let BALLOON_ARREARS
            // extend would lengthen the ladder and change the EIR on a contract whose
            // maturity date never moved.
            ScheduleTerm compressed = ScheduleTerm.of(withHoliday(
                new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new InterestServicing.ServicedEachPeriod()));
            ScheduleTerm arrears = ScheduleTerm.of(withHoliday(
                new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.BALLOON_ARREARS),
                new InterestServicing.ServicedEachPeriod()));

            assertThat(arrears).isEqualTo(compressed);
        }

        @Test
        @DisplayName("a contractual interest-only phase is held apart from a granted holiday")
        void interestOnlyPhaseIsNotAHoliday() {
            // Six interest-only periods then eighteen instalments bills identically to the
            // S4 holiday above — same ladder length, same first amortising period — and the
            // two are different facts about the contract. One was priced in at origination;
            // the other is a concession that may be a modification. Collapsing them loses
            // the distinction, so the phase lengths must disagree even where the totals
            // agree.
            ScheduleTerm interestOnly = ScheduleTerm.of(withHoliday(Moratorium.none(),
                new InterestServicing.ServicedThenCombined(6)));
            ScheduleTerm holiday = ScheduleTerm.of(withHoliday(
                new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new InterestServicing.ServicedEachPeriod()));

            assertThat(interestOnly.interestOnlyPeriods()).isEqualTo(6);
            assertThat(interestOnly.moratoriumPeriods()).isZero();
            assertThat(interestOnly.amortisingPeriods()).isEqualTo(18);
            assertThat(interestOnly.ladderPeriods()).isEqualTo(holiday.ladderPeriods());
            assertThat(interestOnly.firstAmortisingPeriod())
                .isEqualTo(holiday.firstAmortisingPeriod());
            assertThat(interestOnly).isNotEqualTo(holiday);
        }

        @Test
        @DisplayName("a holiday that consumes the stated term is refused, not silently extended")
        void aHolidayThatLeavesNoAmortisingPeriodIsRefused() {
            // Two ways to reach it, and the second is the one that hides: eighteen holiday
            // periods and six interest-only periods each look survivable inside a
            // twenty-four period term and together leave nothing.
            assertThatThrownBy(() -> ScheduleTerm.of(withHoliday(
                new Moratorium(24, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new InterestServicing.ServicedEachPeriod())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a 24-period PRINCIPAL_ONLY holiday under"
                    + " COMPRESS_REMAINING")
                .hasMessageContaining("leaves no amortising period inside the 24-period stated"
                    + " term")
                .hasMessageContaining("the builder will not silently pick one");

            assertThatThrownBy(() -> ScheduleTerm.of(withHoliday(
                new Moratorium(18, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
                new InterestServicing.ServicedThenCombined(6))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plus 6 interest-only periods");
        }

        @Test
        @DisplayName("the phase lengths are checked on their own, not only when derived")
        void phaseLengthsAreValidatedByTheConstructor() {
            // ScheduleTerm is a public value type that four stages construct; the checks
            // cannot live only in the of() derivation.
            assertThatThrownBy(() -> new ScheduleTerm(0, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statedPeriods must be >= 1");
            assertThatThrownBy(() -> new ScheduleTerm(24, -1, 0, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase lengths must not be negative");
            assertThatThrownBy(() -> new ScheduleTerm(24, 12, 12, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the amortising phase spans at least one period");
        }
    }

    // ============================================================ 5. the resolved ladder

    @Nested
    @DisplayName("the ladder retains its terminal balance rather than plugging it (ST-3, ST-5)")
    class Ladder {

        private static final LocalDate DUE_1 = LocalDate.of(2026, 5, 1);
        private static final LocalDate DUE_2 = LocalDate.of(2026, 6, 1);
        private static final LocalDate DUE_3 = LocalDate.of(2026, 7, 1);

        /**
         * 300,000 at 1% a period over three periods, repaying 100,000 of principal in
         * each of the first two and leaving a 100,000 balloon outstanding at period 3.
         * Interest is 1% of the opening balance: 3,000.00, then 2,000.00, then 1,000.00.
         */
        private static List<InstalmentLadder.Rung> balloonRungs() {
            return List.of(
                new InstalmentLadder.Rung(1, DUE_1, Money.inr("100000"), Money.inr("3000"),
                    Money.inr("103000"), Money.inr("200000")),
                new InstalmentLadder.Rung(2, DUE_2, Money.inr("100000"), Money.inr("2000"),
                    Money.inr("102000"), Money.inr("100000")),
                new InstalmentLadder.Rung(3, DUE_3, Money.zero(Money.INR), Money.inr("1000"),
                    Money.inr("1000"), Money.inr("100000")));
        }

        private static InstalmentLadder balloonLadder() {
            return InstalmentLadder.of(Money.INR, balloonRungs(),
                Money.inr("300000"), Money.inr("100000"));
        }

        private static InvariantResult check(InstalmentLadder ladder, InvariantId id) {
            return ladder.invariants().stream()
                .filter(result -> result.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + id + " result on the ladder"));
        }

        @Test
        @DisplayName("a balloon ladder keeps the lump out of total cash and out of the principal sum")
        void balloonLadderRetainsItsTerminalLump() {
            InstalmentLadder ladder = balloonLadder();

            assertThat(ladder.length()).isEqualTo(3);
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(bd("100000"));
            // 3,000 + 2,000 + 1,000. Interest only — a totalInterest that folded in the
            // principal would read as a materially more expensive loan.
            assertThat(ladder.totalInterest().amount()).isEqualByComparingTo(bd("6000"));
            // 103,000 + 102,000 + 1,000. The 100,000 balloon is deliberately absent: it is
            // outstanding at the last rung, not collected by it, and adding it here would
            // double-count the redemption against the flow the assembler emits separately.
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("206000"));
            assertThat(ladder.allSatisfied()).isTrue();
            // ST-3: 200,000 of scheduled principal plus the 100,000 still outstanding is the
            // 300,000 advanced.
            assertThat(check(ladder, InvariantId.ST_3).satisfied()).isTrue();
            assertThat(check(ladder, InvariantId.ST_5).satisfied()).isTrue();
        }

        @Test
        @DisplayName("ST-5 catches a balloon ladder that amortised the lump away")
        void st5CatchesABalloonAmortisedToZero() {
            // The same three periods, except the last rung repays the 100,000 balloon and
            // closes at zero. ST-3 still holds — 300,000 of principal was scheduled and
            // 300,000 was advanced — so this defect is invisible to it, and a ladder that
            // reaches zero on a balloon contract has repaid a lump the borrower still owes.
            List<InstalmentLadder.Rung> overAmortising = List.of(
                balloonRungs().get(0),
                balloonRungs().get(1),
                new InstalmentLadder.Rung(3, DUE_3, Money.inr("100000"), Money.inr("1000"),
                    Money.inr("101000"), Money.zero(Money.INR)));

            InstalmentLadder ladder = InstalmentLadder.of(Money.INR, overAmortising,
                Money.inr("300000"), Money.inr("100000"));

            assertThat(check(ladder, InvariantId.ST_3).satisfied()).isTrue();
            assertThat(check(ladder, InvariantId.ST_5).satisfied()).isFalse();
            assertThat(check(ladder, InvariantId.ST_5).deviation())
                .isEqualByComparingTo(bd("-100000"));
            assertThat(ladder.allSatisfied()).isFalse();
            assertThat(ladder.terminalBalance().isZero()).isTrue();
        }

        @Test
        @DisplayName("ST-3 catches principal that does not sum to the advance")
        void st3CatchesAShortPrincipalLadder() {
            // 100,000 + 100,000 + 90,000 scheduled against 300,000 advanced, on a ladder
            // that nonetheless claims a zero closing balance. The mirror of the case above:
            // ST-5 is satisfied because the ladder ended where a fully-amortising one
            // should, and only ST-3 sees the 10,000 that was never scheduled.
            List<InstalmentLadder.Rung> short10k = List.of(
                balloonRungs().get(0),
                balloonRungs().get(1),
                new InstalmentLadder.Rung(3, DUE_3, Money.inr("90000"), Money.inr("1000"),
                    Money.inr("91000"), Money.zero(Money.INR)));

            InstalmentLadder ladder = InstalmentLadder.of(Money.INR, short10k,
                Money.inr("300000"), Money.zero(Money.INR));

            assertThat(check(ladder, InvariantId.ST_3).satisfied()).isFalse();
            assertThat(check(ladder, InvariantId.ST_3).deviation())
                .isEqualByComparingTo(bd("-10000"));
            assertThat(check(ladder, InvariantId.ST_5).satisfied()).isTrue();
        }

        @Test
        @DisplayName("rungs are addressed by period ordinal, and an absent period is an error")
        void rungLookupIsByPeriodNotByListPosition() {
            // The rungs of a ladder with a holiday are not the list positions of a ladder
            // without one, so a lookup that indexed the list would return the wrong period's
            // instalment on exactly the schedules where it matters most.
            InstalmentLadder ladder = balloonLadder();

            assertThat(ladder.rung(2).dueOn()).isEqualTo(DUE_2);
            assertThat(ladder.rung(2).interest().amount()).isEqualByComparingTo(bd("2000"));
            assertThatThrownBy(() -> ladder.rung(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rung at period 5");
        }

        @Test
        @DisplayName("through() truncates inclusively, so the last kept period is billed")
        void truncationIncludesTheNamedPeriod() {
            // The optionality resolver truncates here to place a call. Exclusive truncation
            // would drop the instalment billed on the call date itself, which is the flow
            // that carries the redemption.
            InstalmentLadder ladder = balloonLadder();

            assertThat(ladder.through(2)).extracting(InstalmentLadder.Rung::periodIndex)
                .containsExactly(1, 2);
            assertThat(ladder.through(3)).hasSize(3);
            assertThat(ladder.through(99)).hasSize(3);
            assertThat(ladder.through(0)).isEmpty();
        }

        @Test
        @DisplayName("a ladder with no rung, an out-of-order rung or a foreign currency is refused")
        void malformedLaddersAreRefused() {
            assertThatThrownBy(() -> new InstalmentLadder(Money.INR, List.of(),
                Money.zero(Money.INR), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a ladder needs at least one rung");

            // Descending, and the duplicate-ordinal case: two rungs at period 1 would make
            // rung(1) ambiguous and double-count the period in any roll-forward.
            InstalmentLadder.Rung first = balloonRungs().get(0);
            InstalmentLadder.Rung second = balloonRungs().get(1);
            assertThatThrownBy(() -> new InstalmentLadder(Money.INR, List.of(second, first),
                Money.zero(Money.INR), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rungs must ascend by period; 1 does not follow 2");
            assertThatThrownBy(() -> new InstalmentLadder(Money.INR, List.of(first, first),
                Money.zero(Money.INR), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rungs must ascend by period");

            Currency usd = Currency.getInstance("USD");
            InstalmentLadder.Rung dollars = new InstalmentLadder.Rung(1, DUE_1,
                Money.of("100000", usd), Money.of("3000", usd), Money.of("103000", usd),
                Money.of("200000", usd));
            assertThatThrownBy(() -> new InstalmentLadder(Money.INR, List.of(dollars),
                Money.zero(Money.INR), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rung at period 1 is USD but the ladder is INR");
        }

        @Test
        @DisplayName("a rung refuses a zero ordinal and a negative closing balance")
        void malformedRungsAreRefused() {
            assertThatThrownBy(() -> new InstalmentLadder.Rung(0, DUE_1, Money.inr("1"),
                Money.inr("1"), Money.inr("2"), Money.inr("1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodIndex is 1-based, got 0");

            // A negative closing balance is over-amortisation: the schedule has repaid
            // principal the borrower never owed, and every balance after it is wrong.
            assertThatThrownBy(() -> new InstalmentLadder.Rung(3, DUE_3, Money.inr("100000"),
                Money.inr("1000"), Money.inr("101000"), Money.inr("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance after period 3 is negative")
                .hasMessageContaining("repaid principal the borrower never owed");
        }

        @Test
        @DisplayName("the ladder copies the rungs it was given and publishes them unmodifiable")
        void theLadderIsImmutable() {
            // Four stages hold the same ladder — the optionality resolver, the behavioural
            // adjuster, the assembler and the reconciliation. A caller that kept its list
            // and appended to it later would reshape a ladder those stages had already
            // agreed on, and the reconciliation would compare against a schedule that no
            // longer matches the vector it published.
            List<InstalmentLadder.Rung> source = new ArrayList<>(balloonRungs());
            InstalmentLadder ladder = InstalmentLadder.of(Money.INR, source,
                Money.inr("300000"), Money.inr("100000"));

            source.clear();

            assertThat(ladder.length()).isEqualTo(3);
            assertThatThrownBy(() -> ladder.rungs().remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ladder.invariants().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ============================================================== 6. the due dates

    @Nested
    @DisplayName("due dates are anchored on the value date, then resolved, then adjusted")
    class Dating {

        @Test
        @DisplayName("the period count is stepped at the frequency, never divided out of a tenor")
        void statedPeriodsIsCountedByStepping() {
            // 1 April 2026 to 1 April 2028 is 731 days, the extra day being 29 February
            // 2028. Twenty-four months, eight quarters, four half-years, two years — and
            // 104 whole weeks with three days left over, which is why a tenor divided by
            // seven would not have given the same answer as the step that generates the
            // dates.
            assertThat(ScheduleDates.statedPeriods(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(24);
            assertThat(ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.QUARTERLY,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(8);
            assertThat(ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.HALF_YEARLY,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(4);
            assertThat(ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.ANNUAL,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(2);
            assertThat(ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.WEEKLY,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(104);
            // Fortnightly steps two weeks, not half a month: 52 fortnights is 728 days.
            assertThat(ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.FORTNIGHTLY,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                VALUE_DATE, STATED_MATURITY)).isEqualTo(52);
        }

        @Test
        @DisplayName("a month-end anchor is not dragged forward by February's clamp")
        void monthEndAnchorIsNotChained() {
            // 31 January 2026, monthly, no month-end rule. February 2026 has 28 days, so
            // period 1 clamps to the 28th — and period 2 must return to the 31st. Chaining
            // off the previous due date instead would leave the schedule on the 28th for the
            // rest of the loan, which is the classic defect: it silently turns a month-end
            // loan into a 28th-of-the-month loan and moves every discount factor after
            // period 2.
            assertThat(ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), MONTH_END, 4))
                .containsExactly(
                    LocalDate.of(2026, 2, 28),
                    LocalDate.of(2026, 3, 31),
                    LocalDate.of(2026, 4, 30),
                    LocalDate.of(2026, 5, 31));
        }

        @Test
        @DisplayName("a 29 February anchor recovers the 29th in the next leap year")
        void leapDayAnchorIsPreserved() {
            // 29 February 2028 annually. 2029, 2030 and 2031 have no 29 February so each
            // clamps to the 28th, and 2032 is a leap year — so period 4 is 29 February 2032.
            // A schedule chained off period 3 would give the 28th there and lose the day
            // permanently; anchoring on the value date is what recovers it.
            assertThat(ScheduleDates.dueDates(
                calendar(ScheduleCalendar.Frequency.ANNUAL,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), LEAP_DAY, 4))
                .containsExactly(
                    LocalDate.of(2029, 2, 28),
                    LocalDate.of(2030, 2, 28),
                    LocalDate.of(2031, 2, 28),
                    LocalDate.of(2032, 2, 29));

            // The same anchor monthly: period 12 lands on 28 February 2029, the clamp again.
            assertThat(ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), LEAP_DAY, 12))
                .last().isEqualTo(LocalDate.of(2029, 2, 28));
        }

        @Test
        @DisplayName("the end-of-month rule fires only where the value date is itself a month end")
        void endOfMonthRuleIsConditionedOnTheAnchor() {
            // 29 February 2028 is the last day of its month, so LAST_CALENDAR_DAY_OF_MONTH
            // applies: the 29th anchors become month ends — 31 March, 30 April, 31 May.
            assertThat(ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.LAST_CALENDAR_DAY_OF_MONTH), LEAP_DAY, 3))
                .containsExactly(
                    LocalDate.of(2028, 3, 31),
                    LocalDate.of(2028, 4, 30),
                    LocalDate.of(2028, 5, 31));

            // A loan disbursed on the 15th has no month-end anchor to resolve, so the rule
            // is dormant and the instalments stay on the 15th. Applying it unconditionally
            // would move every due date a fortnight. Note that 15 February 2026 is a Sunday
            // and stays there: the end-of-month rule is not a business-day convention, and
            // this calendar's convention is NONE.
            assertThat(ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.LAST_BUSINESS_DAY_OF_MONTH),
                LocalDate.of(2026, 1, 15), 3))
                .containsExactly(
                    LocalDate.of(2026, 2, 15),
                    LocalDate.of(2026, 3, 15),
                    LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("LAST_BUSINESS_DAY_OF_MONTH steps back off a weekend month end")
        void lastBusinessDayRuleStepsBack() {
            // ScheduleCalendar.monthly() — the retail default — on a 31 January 2026 loan.
            // 28 February 2026 is a Saturday, so period 1 becomes Friday the 27th; 31 March
            // is a Tuesday and 30 April a Thursday, both unmoved; 31 May 2026 is a Sunday,
            // so period 4 becomes Friday the 29th.
            assertThat(ScheduleDates.dueDates(ScheduleCalendar.monthly(), MONTH_END, 4))
                .containsExactly(
                    LocalDate.of(2026, 2, 27),
                    LocalDate.of(2026, 3, 31),
                    LocalDate.of(2026, 4, 30),
                    LocalDate.of(2026, 5, 29));
        }

        @Test
        @DisplayName("week-based frequencies are exempt from the month-end rule")
        void fortnightlyScheduleIsNotSnappedToMonthEnds() {
            // 31 January 2026 fortnightly under LAST_CALENDAR_DAY_OF_MONTH. Periods 1 and 2
            // fall on 14 and 28 February; snapping either to the month end would put both on
            // 28 February and collapse two due dates into one, losing an instalment in every
            // 28-day month.
            List<LocalDate> due = ScheduleDates.dueDates(
                new ScheduleCalendar(ScheduleCalendar.Frequency.FORTNIGHTLY,
                    ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                    ScheduleCalendar.EndOfMonthRule.LAST_CALENDAR_DAY_OF_MONTH, List.of()),
                MONTH_END, 4);

            assertThat(due).containsExactly(
                LocalDate.of(2026, 2, 14),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 14),
                LocalDate.of(2026, 3, 28));
            assertThat(due).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the month-end rule resolves before the business-day convention moves anything")
        void transformationsRunInAFixedOrder() {
            // 31 January 2026 monthly, month end resolved to the last calendar day and then
            // rolled FOLLOWING. Period 1: the anchor is 28 February 2026, the month-end rule
            // leaves it there, and FOLLOWING moves that Saturday to Monday 2 March.
            //
            // The order is what the assertion pins. Rolling first and resolving the month
            // end afterwards would take the same date to 2 March and then to 31 March — a
            // whole month out, on the instalment where the borrower's first payment lands.
            assertThat(ScheduleDates.dueDates(
                new ScheduleCalendar(ScheduleCalendar.Frequency.MONTHLY,
                    ScheduleCalendar.BusinessDayConvention.FOLLOWING, Set.of(),
                    ScheduleCalendar.EndOfMonthRule.LAST_CALENDAR_DAY_OF_MONTH, List.of()),
                MONTH_END, 1))
                .containsExactly(LocalDate.of(2026, 3, 2));
        }

        @Test
        @DisplayName("SEASONAL and CUSTOM due dates are taken verbatim, convention or not")
        void suppliedDatesAreTheSchedule() {
            // Harvest does not observe a modified-following convention. The second date is a
            // Sunday and the third is in the holiday set, and both stay put: adjusting a
            // supplied date would move a due date the contract fixed.
            List<LocalDate> harvest = List.of(
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 4, 15));
            ScheduleCalendar seasonal = new ScheduleCalendar(
                ScheduleCalendar.Frequency.SEASONAL,
                ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING,
                Set.of(LocalDate.of(2027, 4, 15)),
                ScheduleCalendar.EndOfMonthRule.LAST_BUSINESS_DAY_OF_MONTH, harvest);

            assertThat(ScheduleDates.dueDates(seasonal, VALUE_DATE, 3))
                .containsExactlyElementsOf(harvest);
            assertThat(seasonal.isBusinessDay(LocalDate.of(2027, 2, 28))).isFalse();
        }

        @Test
        @DisplayName("for an explicit calendar the supplied list is authoritative about the count")
        void explicitDatesDecideThePeriodCount() {
            // Maturity says where to stop reading the list, not how long the periods are:
            // three of these four harvest dates fall on or before 31 December 2027.
            ScheduleCalendar seasonal = ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 4, 15),
                LocalDate.of(2027, 10, 15),
                LocalDate.of(2028, 4, 15)));

            assertThat(ScheduleDates.statedPeriods(seasonal, VALUE_DATE,
                LocalDate.of(2027, 12, 31))).isEqualTo(3);
            assertThat(ScheduleDates.statedPeriods(seasonal, VALUE_DATE,
                LocalDate.of(2028, 4, 15))).isEqualTo(4);

            // No date on or before maturity is a schedule with no instalment.
            assertThatThrownBy(() -> ScheduleDates.statedPeriods(seasonal, VALUE_DATE,
                LocalDate.of(2026, 5, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplies no due date on or before stated maturity")
                .hasMessageContaining("a schedule with no instalment is not a schedule");
        }

        @Test
        @DisplayName("an unequal calendar will not extrapolate its own next date")
        void suppliedDatesAreNotExtrapolated() {
            // Being unable to derive the next date is exactly what makes the calendar
            // unequal, so a schedule longer than the supplied list is a data gap. Inferring
            // one — by repeating the last gap, say — would invent a harvest date.
            ScheduleCalendar seasonal = ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15)));

            assertThatThrownBy(() -> ScheduleDates.dueDates(seasonal, VALUE_DATE, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplies 2 due dates but the schedule runs 3 periods")
                .hasMessageContaining("a data gap");
            assertThat(ScheduleDates.dueDates(seasonal, VALUE_DATE, 2)).hasSize(2);
        }

        @Test
        @DisplayName("a maturity inside the first period, and a mis-stated one, are both refused")
        void degenerateTenorsAreRefused() {
            // Monthly from 31 January 2026: the first due date is 28 February, so a maturity
            // of the 27th leaves no period. A broken single period is a money-market
            // instrument and belongs on the discount path, not on a schedule.
            assertThatThrownBy(() -> ScheduleDates.statedPeriods(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), MONTH_END,
                LocalDate.of(2026, 2, 27)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falls before the first MONTHLY due date")
                .hasMessageContaining("money-market instrument");

            assertThatThrownBy(() -> ScheduleDates.statedPeriods(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), VALUE_DATE,
                VALUE_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must follow valueDate");

            // Weekly instalments over 200 years: about 10,435 periods. Weekly over a 30-year
            // tenor is only about 1,560, so anything past the 6,000 ceiling is a mis-stated
            // maturity — and saying so beats looping to find out.
            assertThatThrownBy(() -> ScheduleDates.statedPeriods(
                calendar(ScheduleCalendar.Frequency.WEEKLY,
                    ScheduleCalendar.BusinessDayConvention.NONE,
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH),
                LocalDate.of(2026, 1, 1), LocalDate.of(2226, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 6000 WEEKLY periods")
                .hasMessageContaining("the maturity is mis-stated");

            assertThatThrownBy(() -> ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), VALUE_DATE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periods must be >= 1, got 0");
        }

        @Test
        @DisplayName("periodOnOrAfter reports the period a settlement lands in, or that there is none")
        void settlementDatesArePlacedOnTheSchedule() {
            // Used to place a deferred-interest lump. A date matching no due date is the
            // caller's decision to snap or reject, so -1 has to be distinguishable from
            // period 1 rather than folded into it.
            List<LocalDate> due = ScheduleDates.dueDates(
                monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH), VALUE_DATE, 4);

            assertThat(ScheduleDates.periodOnOrAfter(due, LocalDate.of(2026, 5, 1))).isEqualTo(1);
            assertThat(ScheduleDates.periodOnOrAfter(due, LocalDate.of(2026, 6, 15))).isEqualTo(3);
            assertThat(ScheduleDates.periodOnOrAfter(due, LocalDate.of(2026, 4, 15))).isEqualTo(1);
            assertThat(ScheduleDates.periodOnOrAfter(due, LocalDate.of(2027, 1, 1))).isEqualTo(-1);
        }

        @Test
        @DisplayName("the anchor step refuses a frequency whose dates are supplied")
        void anchorRefusesExplicitDateFrequencies() {
            // Unreachable through statedPeriods and dueDates, both of which branch on
            // requiresExplicitDates first. It is asserted because the alternative for a
            // future caller is a silently plausible date: SEASONAL has no period length, and
            // treating it as one month would fabricate a harvest.
            assertThatThrownBy(() -> ScheduleDates.anchor(
                ScheduleCalendar.Frequency.SEASONAL, VALUE_DATE, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SEASONAL has no derivable period length");
            assertThatThrownBy(() -> ScheduleDates.anchor(
                ScheduleCalendar.Frequency.CUSTOM, VALUE_DATE, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("its due dates are supplied");
        }
    }

    // ============================================================== 7. the calendar

    @Nested
    @DisplayName("the calendar decides when a due date moves, and whether indexing is licensed")
    class Calendar {

        @Test
        @DisplayName("each business-day convention moves a weekend date its own way")
        void conventionsRollInTheirOwnDirections() {
            // 28 February 2026 is a Saturday. Following is Monday 2 March, preceding is
            // Friday the 27th, and MODIFIED_FOLLOWING rolls forward unless that crosses the
            // month — which it does here, so it lands on the 27th. Getting modified wrong is
            // how a February instalment ends up billed in March, one period out of line with
            // the accrual it settles.
            LocalDate saturday = LocalDate.of(2026, 2, 28);

            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.NONE,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH).adjust(saturday))
                .isEqualTo(saturday);
            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.FOLLOWING,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH).adjust(saturday))
                .isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.PRECEDING,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH).adjust(saturday))
                .isEqualTo(LocalDate.of(2026, 2, 27));
            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH).adjust(saturday))
                .isEqualTo(LocalDate.of(2026, 2, 27));

            // MODIFIED_PRECEDING needs a date at the other end of a month to show its own
            // reversal: 1 February 2026 is a Sunday, whose preceding business day is Friday
            // 30 January — the month before — so it rolls forward to Monday the 2nd.
            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.MODIFIED_PRECEDING,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)
                .adjust(LocalDate.of(2026, 2, 1)))
                .isEqualTo(LocalDate.of(2026, 2, 2));
        }

        @Test
        @DisplayName("a roll walks past a run of holidays rather than one non-business day")
        void rollingSkipsConsecutiveHolidays() {
            // 31 May 2026 is a Sunday and 1 June a Monday holiday, so FOLLOWING has to reach
            // Tuesday the 2nd. A roll that stepped once and stopped would bill on a day the
            // borrower's bank is shut.
            ScheduleCalendar withHolidays = new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.FOLLOWING,
                Set.of(LocalDate.of(2026, 6, 1)),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of());

            assertThat(withHolidays.adjust(LocalDate.of(2026, 5, 31)))
                .isEqualTo(LocalDate.of(2026, 6, 2));
            assertThat(withHolidays.isBusinessDay(LocalDate.of(2026, 6, 1))).isFalse();
            assertThat(withHolidays.isBusinessDay(LocalDate.of(2026, 5, 30))).isFalse();
            assertThat(withHolidays.isBusinessDay(LocalDate.of(2026, 6, 2))).isTrue();
        }

        @Test
        @DisplayName("periodic indexing is licensed only where nothing can disturb uniformity")
        void periodicIndexingIsGrantedConservatively() {
            // ST-10. Taking period ordinals as time on a schedule whose periods are not
            // equal is a wrong rate, not a rough one, so every clause here is a veto: a
            // convention that could move a date, a holiday that could trigger one, and a
            // frequency whose periods are unequal by construction.
            //
            // The licensed case is stated with SAME_DAY_OF_MONTH rather than with
            // ScheduleCalendar.monthly() on purpose: the default's
            // LAST_BUSINESS_DAY_OF_MONTH rule is the subject of the disabled test below,
            // and pinning the default's answer here would make that defect's fix look like
            // a regression.
            assertThat(monthly(ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)
                .admitsPeriodicIndexing()).isTrue();
            assertThat(calendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH)
                .admitsPeriodicIndexing()).isFalse();
            assertThat(new ScheduleCalendar(ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.NONE,
                Set.of(LocalDate.of(2026, 8, 15)),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of())
                .admitsPeriodicIndexing()).isFalse();
            assertThat(ScheduleCalendar.seasonal(List.of(LocalDate.of(2026, 10, 15)))
                .admitsPeriodicIndexing()).isFalse();

            // And the frequencies that do not divide a year report zero periods per year,
            // which is what stops a caller annualising a weekly rate by multiplication.
            assertThat(ScheduleCalendar.Frequency.MONTHLY.periodsPerYear()).isEqualTo(12);
            assertThat(ScheduleCalendar.Frequency.QUARTERLY.periodsPerYear()).isEqualTo(4);
            assertThat(ScheduleCalendar.Frequency.WEEKLY.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.SEASONAL.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.SEASONAL.requiresExplicitDates()).isTrue();
            assertThat(ScheduleCalendar.Frequency.CUSTOM.requiresExplicitDates()).isTrue();
            assertThat(ScheduleCalendar.Frequency.MONTHLY.requiresExplicitDates()).isFalse();
        }

        @Test
        @DisplayName("an explicit-date frequency without dates, or with unordered dates, is refused")
        void explicitCalendarsAreValidated() {
            assertThatThrownBy(() -> new ScheduleCalendar(ScheduleCalendar.Frequency.SEASONAL,
                ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SEASONAL has no derivable period length")
                .hasMessageContaining("cannot be inferred from a frequency");

            // Strictly ascending, so a repeated date is refused too: two instalments on one
            // date is one period of zero length, and every discount factor after it shifts.
            assertThatThrownBy(() -> ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2027, 4, 15), LocalDate.of(2026, 10, 15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom due dates must be strictly ascending");
            assertThatThrownBy(() -> ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strictly ascending");
        }

        @Test
        @DisplayName("the calendar copies the holiday set and the supplied dates")
        void theCalendarIsImmutable() {
            // A calendar is shared by every contract on a product. A caller that kept its
            // holiday set and added to it would retrospectively move due dates on schedules
            // already published.
            Set<LocalDate> holidays = new HashSet<>(Set.of(LocalDate.of(2026, 6, 1)));
            List<LocalDate> harvest = new ArrayList<>(List.of(LocalDate.of(2026, 10, 15)));
            ScheduleCalendar calendar = new ScheduleCalendar(
                ScheduleCalendar.Frequency.SEASONAL,
                ScheduleCalendar.BusinessDayConvention.FOLLOWING, holidays,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, harvest);

            holidays.add(LocalDate.of(2026, 12, 25));
            harvest.add(LocalDate.of(2027, 4, 15));

            assertThat(calendar.holidays()).containsExactly(LocalDate.of(2026, 6, 1));
            assertThat(calendar.customDueDates()).containsExactly(LocalDate.of(2026, 10, 15));
            // 25 December 2026 is a Friday, so it is a business day unless the calendar took
            // the late addition — which is the point of asserting through isBusinessDay as
            // well as through the accessor.
            assertThat(calendar.isBusinessDay(LocalDate.of(2026, 12, 25))).isTrue();
        }

        @Test
        @DisplayName("the month-end rule voids periodic indexing on a month-end loan, and not"
            + " on a mid-month one")
        void lastBusinessDayRuleMustVoidPeriodicIndexing() {
            // ScheduleCalendar.monthly() — the retail default — carries
            // LAST_BUSINESS_DAY_OF_MONTH, and on a month-end value date that rule moves due
            // dates off weekends: period 1 of a 31 January 2026 loan bills on Friday 27
            // February rather than on the Saturday anchor of the 28th. Those periods are not
            // equal, and ST-10 has to say so.
            //
            // The defect this test was disabled for was that it did not. The obvious fix —
            // endOfMonthRule != LAST_BUSINESS_DAY_OF_MONTH as a fifth veto on
            // ScheduleCalendar.admitsPeriodicIndexing() — is wrong, and the second half of
            // this test is why: the SAME calendar object moves nothing on a mid-month loan,
            // because ScheduleDates applies the rule only where the value date is itself a
            // month end. A flag-only veto would report every mid-month retail loan in the
            // book as adjusted, and calendarForcesActualDating throws on !admits && indexed,
            // so it would reject sound schedules rather than merely mislabel them.
            //
            // So the question is asked of the dates. Same calendar, two value dates, two
            // answers — which is exactly the shape a calendar-only predicate cannot have,
            // and the reason the complete test lives on ScheduleDates.
            ScheduleCalendar retailDefault = ScheduleCalendar.monthly();
            LocalDate maturity = MONTH_END.plusYears(2);

            assertThat(ScheduleDates.dueDates(retailDefault, MONTH_END, 1))
                .containsExactly(LocalDate.of(2026, 2, 27));
            assertThat(ScheduleDates.admitsPeriodicIndexing(retailDefault, MONTH_END, maturity))
                .as("the rule moved period 1 off its anchor, so ordinals do not measure time")
                .isFalse();

            LocalDate midMonth = LocalDate.of(2026, 1, 15);
            assertThat(ScheduleDates.dueDates(retailDefault, midMonth, 1))
                .containsExactly(LocalDate.of(2026, 2, 15));
            assertThat(ScheduleDates.admitsPeriodicIndexing(
                retailDefault, midMonth, midMonth.plusYears(2)))
                .as("dormant on a mid-month anchor: the same calendar, and nothing moved")
                .isTrue();

            // The date-independent screen still answers its own narrower question the same
            // way for both, which is the whole reason it cannot be the gate.
            assertThat(retailDefault.admitsPeriodicIndexing()).isTrue();
        }

        @Test
        @DisplayName("an unequal calendar that claims periodic indexing is an ST-11 conflict")
        void anExplicitDateCalendarCannotAdmitOrdinalDiscounting() {
            // This was @Disabled and asserted the tenth coherence condition firing. It could
            // not: it read requiresExplicitDates() && admitsPeriodicIndexing(), and
            // admitsPeriodicIndexing() begins by requiring !requiresExplicitDates(), so the
            // conjunction was A && (... && !A && ...). The condition is now removed, and this
            // test asserts what is actually true and worth keeping — that the property is
            // enforced by construction in the type, so a coherence rule restating it would
            // add nothing.
            //
            // Worth noting what the rule was not: a seasonal calendar is a perfectly coherent
            // dimension — crop-cycle loans exist — and rejecting one would be wrong. It read
            // as a guard against a calendar that misreports its own uniformity, and the live
            // instance of exactly that was the LAST_BUSINESS_DAY_OF_MONTH hole in the test
            // above — which this rule did not reach either, and which is now closed by asking
            // the question of the dates rather than of the flags.
            ScheduleCalendar harvest = ScheduleCalendar.seasonal(
                List.of(LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15)));
            assertThat(harvest.frequency().requiresExplicitDates()).isTrue();
            assertThat(harvest.admitsPeriodicIndexing()).isFalse();

            // The type makes the contradiction unconstructable, so there is nothing for a
            // coherence rule to catch: every explicit-date calendar reports false.
            for (ScheduleCalendar.Frequency frequency : ScheduleCalendar.Frequency.values()) {
                if (frequency.requiresExplicitDates()) {
                    assertThat(new ScheduleCalendar(frequency,
                        ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                        ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
                        List.of(LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15)))
                        .admitsPeriodicIndexing())
                        .as("%s requires explicit dates, so it cannot admit ordinal discounting",
                            frequency)
                        .isFalse();
                }
            }

            // And a seasonal calendar is coherent: it builds, and it raises no conflict.
            assertThat(ScheduleBlueprint.coherenceConflicts(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), singleDraw(),
                fixed(), OptionSchedule.none(), contractual(), harvest))
                .as("a crop-cycle loan is an ordinary instrument, not an incoherent blueprint")
                .noneMatch(conflict -> conflict.contains("ST-10"));
        }
    }
}
