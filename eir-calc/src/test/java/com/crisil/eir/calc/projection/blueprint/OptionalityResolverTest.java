package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Expected life under optionality, and the two refusals that matter
 * (docs 09 § 3, invariants ST-3, ST-7, ST-8, ST-9, ST-12).
 *
 * <p>Six types are exercised here because they are one mechanism.
 * {@link OptionSchedule} carries the embedded options and the recorded policy,
 * {@link ExercisePolicy} names the six readings of "expected life",
 * {@link OptionalityJudgements} carries the three inputs the engine must not
 * invent, {@link OptionalityResolver} does the arithmetic, and the two exceptions
 * are the answers it gives when arithmetic is the wrong output —
 * {@link OptionalitySppiFailureException} where the instrument has no EIR at all
 * and {@link OptionalityUnresolvedException} where the recorded policy cannot be
 * evaluated.
 *
 * <p><b>The bond every figure below is measured on.</b> Face 1,000,000, 9% annual
 * coupon paid once a year, principal repaid in one flow at the tenth anniversary of
 * the 1 April 2026 value date. That is fixtures O1 to O5 of docs 09 § 6, and the
 * whole ladder is nine rungs of 90,000 interest followed by one of 90,000 interest
 * plus 1,000,000 principal. It is built by hand here rather than through
 * {@code ScheduleBuilder}, so that a builder defect cannot silently move a figure
 * this test attributes to the resolver.
 *
 * <p><b>Where the expected values come from.</b> Every rate asserted below appears
 * in docs 09 § 6 (fixtures O1, O2, O4, O5) or is the yield to maturity of a stated
 * flow sequence, solved independently of this codebase and reproduced in the
 * comment that uses it. The two that docs 09 does not tabulate — the
 * probability-weighted rate and the step-coupon extension — carry their flow
 * sequence in full in the comment, so a reader can re-perform them:
 *
 * <table border="1">
 *   <caption>The rates this test asserts, and where each comes from</caption>
 *   <tr><th>Basis</th><th>Life</th><th>Effective annual</th><th>Source</th></tr>
 *   <tr><td>Premium 1,050,000, to maturity</td><td>10</td><td>8.246545%</td>
 *       <td>docs 09 § 6 O1</td></tr>
 *   <tr><td>Premium 1,050,000, to par call</td><td>5</td><td>7.755768%</td>
 *       <td>docs 09 § 6 O1 / O5</td></tr>
 *   <tr><td>Discount 950,000, to maturity</td><td>10</td><td>9.806992%</td>
 *       <td>docs 09 § 6 O2</td></tr>
 *   <tr><td>Discount 950,000, to par call</td><td>5</td><td>10.330130%</td>
 *       <td>docs 09 § 6 O2</td></tr>
 *   <tr><td>Premium 1,050,000, to par put</td><td>3</td><td>7.091554%</td>
 *       <td>docs 09 § 6 O4</td></tr>
 *   <tr><td>Premium 1,050,000, extended</td><td>8</td><td>8.125769%</td>
 *       <td>docs 09 § 6 O5</td></tr>
 *   <tr><td>Par 1,000,000, any life</td><td>any</td><td>9.000000%</td>
 *       <td>docs 09 § 3.4 — a bond bought at par yields its coupon</td></tr>
 * </table>
 *
 * <p>Under an annual calendar the periodic rate <em>is</em> the effective annual
 * rate — {@code (1+r)^1 - 1} is {@code r} on {@link com.crisil.eir.domain.Precision}'s
 * exact integer power path — so {@link Rate#effectiveAnnual()} is asserted directly
 * against the documented figure with no annualisation round trip in between.
 *
 * <p>Two tests are {@link Disabled} and each names a production defect. Neither is
 * fixed here; see the comment on each.
 */
class OptionalityResolverTest {

    /** Face of the O1–O5 bond, and the blueprint notional throughout. */
    private static final Money FACE = Money.inr("1000000");

    /** 9% of face, paid annually. */
    private static final Money COUPON = Money.inr("90000");

    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** Bought above par: 1,050,000 for a 1,000,000 face. Fixture O1. */
    private static final Money PREMIUM = Money.inr("1050000");

    /** Bought below par: 950,000. Fixture O2, where the divergence reverses. */
    private static final Money DISCOUNT = Money.inr("950000");

    /** Bought at par. Fixture O7 and the § 3.4 par test. */
    private static final Money AT_PAR = Money.inr("1000000");

    private static final Rate NINE_PERCENT_ANNUAL = Rate.annualEffective(new BigDecimal("0.09"));

    /**
     * Annual, unadjusted, no holidays, no bespoke dates.
     *
     * <p>Deliberately the calendar that licenses periodic indexing: every due date
     * lands on an exact anniversary of the value date, so
     * {@code ConventionSelector} selects {@code PeriodicIndex(1)} and the day count
     * never gets used. That keeps the assertions about optionality rather than about
     * day-count arithmetic, which {@code ConventionSelectorTest} already owns.
     */
    private static final ScheduleCalendar ANNUAL = new ScheduleCalendar(
        ScheduleCalendar.Frequency.ANNUAL,
        ScheduleCalendar.BusinessDayConvention.NONE,
        Set.of(),
        ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
        List.of());

    private final RateSolver solver = new BracketedNewtonSolver();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** The {@code k}-th anniversary of the value date — the due date of period k. */
    private static LocalDate annualDue(int period) {
        return VALUE_DATE.plusYears(period);
    }

    // -------------------------------------------------------------- the fixtures

    /**
     * A bullet ladder: {@code periods - 1} rungs of coupon only, then coupon plus
     * the whole principal.
     *
     * <p>Built through {@link InstalmentLadder#of} so ST-3 and ST-5 are asserted on
     * the input as well as on whatever the resolver reshapes it into. The balance
     * outstanding after every rung but the last is the full face, which is what
     * makes a truncation's par redemption 1,000,000 whichever period it lands on.
     */
    private static InstalmentLadder bullet(int periods) {
        List<InstalmentLadder.Rung> rungs = new ArrayList<>();
        for (int period = 1; period < periods; period++) {
            rungs.add(new InstalmentLadder.Rung(period, annualDue(period),
                Money.zero(Money.INR), COUPON, COUPON, FACE));
        }
        rungs.add(new InstalmentLadder.Rung(periods, annualDue(periods),
            FACE, COUPON, COUPON.plus(FACE), Money.zero(Money.INR)));
        return InstalmentLadder.of(Money.INR, rungs, FACE, Money.zero(Money.INR));
    }

    /**
     * A five-period equal-principal ladder on the same 1,000,000 at 9%.
     *
     * <p>200,000 of principal a period, so interest falls 90,000, 72,000, 54,000,
     * 36,000, 18,000 as the balance runs down. Used only where the point is that the
     * ladder <em>amortises before maturity</em>.
     */
    private static InstalmentLadder equalPrincipal() {
        List<InstalmentLadder.Rung> rungs = new ArrayList<>();
        Money principal = Money.inr("200000");
        Money balance = FACE;
        for (int period = 1; period <= 5; period++) {
            Money interest = balance.times(bd("0.09"));
            balance = balance.minus(principal);
            rungs.add(new InstalmentLadder.Rung(period, annualDue(period),
                principal, interest, principal.plus(interest), balance));
        }
        return InstalmentLadder.of(Money.INR, rungs, FACE, Money.zero(Money.INR));
    }

    private static ScheduleBlueprint bond(
        int statedPeriods,
        PrincipalProfile principal,
        RateProfile rate,
        OptionSchedule options,
        int eclHorizonPeriods) {

        return new ScheduleBlueprint(FACE, Money.INR, VALUE_DATE, annualDue(statedPeriods),
            new DisbursementProfile.Single(VALUE_DATE, FACE),
            principal,
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            rate,
            options,
            new BehaviouralOverlay.Contractual("bond terms grant the holder no prepayment right"),
            ANNUAL,
            DayCountConvention.THIRTY_360_BOND,
            ResiduePolicy.FINAL_PERIOD_PLUG,
            eclHorizonPeriods);
    }

    /** The O1–O4 bond: ten annual periods, fixed 9%, bullet. */
    private static ScheduleBlueprint tenYearBond(OptionSchedule options) {
        return bond(10, new PrincipalProfile.BulletAtMaturity(),
            new RateProfile.Fixed(NINE_PERCENT_ANNUAL), options, 10);
    }

    private static OptionSchedule.EmbeddedOption option(
        OptionSchedule.OptionType type,
        OptionSchedule.OptionHolder holder,
        int firstPeriod,
        int lastPeriod,
        String strikePctOfPar) {

        return new OptionSchedule.EmbeddedOption(type, holder, annualDue(firstPeriod),
            annualDue(lastPeriod), bd(strikePctOfPar), false);
    }

    /** A one-shot par call at the {@code period}-th anniversary, held by the issuer. */
    private static OptionSchedule.EmbeddedOption parCall(int period) {
        return OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CALL,
            OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(period));
    }

    private static OptionSchedule schedule(
        ExercisePolicy policy, OptionSchedule.EmbeddedOption... options) {
        return new OptionSchedule(List.of(options), policy);
    }

    // ------------------------------------------------------------ assertion helpers

    private static ExpectedLifeDetermination.LifeAlternative costed(
        ExpectedLifeDetermination determination, ExercisePolicy policy) {

        return determination.alternatives().stream()
            .filter(alternative -> alternative.policy() == policy)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no alternative was costed for " + policy + "; costed "
                    + determination.alternatives().stream()
                        .map(ExpectedLifeDetermination.LifeAlternative::policy).toList()));
    }

    private static InvariantResult resultFor(
        ExpectedLifeDetermination determination, InvariantId id) {

        return determination.invariants().stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + id + " result on the determination"));
    }

    private static List<CashFlow> atPeriod(FlowVector vector, int period) {
        return vector.future().stream().filter(flow -> flow.periodIndex() == period).toList();
    }

    // ============================================================ 1. the ST-12 gate

    @Nested
    @DisplayName("an instrument whose optionality fails SPPI gets a refusal, never a rate (ST-12)")
    class TheSppiGate {

        /**
         * The AT1-style instrument of docs 09 § 6 O3: a perpetual whose coupons are
         * discretionary and which converts to equity on a capital trigger. The
         * conversion is the loss-absorption feature, and it is what takes the
         * instrument out of amortised cost.
         */
        private OptionSchedule at1Style() {
            return schedule(ExercisePolicy.CONTRACTUAL_MATURITY,
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CONVERSION,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(5)));
        }

        @Test
        @DisplayName("an AT1-style convertible has no EIR at all: the gate raises, and returns no life")
        void anAt1StyleConvertibleHasNoEir() {
            // ST-12, and the reason this is an exception rather than a life. An SPPI
            // failure sends the whole instrument to FVTPL, where no effective interest
            // rate exists. There is no reduced, approximate or provisional EIR on the far
            // side of that cliff, so returning any life would put a number into a field
            // whose existence asserts amortised cost — and the number would then be
            // indistinguishable from one belonging to an instrument that passed.
            //
            // The assertion is deliberately on the throw and not on a returned value:
            // there is nothing this call could return that would be correct.
            assertThatThrownBy(() -> OptionalityResolver.requireSppiPass(at1Style()))
                .isInstanceOf(OptionalitySppiFailureException.class)
                .hasMessageContaining(InvariantId.ST_12.statement())
                .hasMessageContaining("fair value through profit or loss")
                .hasMessageContaining("No expected life is returned");

            OptionalitySppiFailureException raised = null;
            try {
                OptionalityResolver.requireSppiPass(at1Style());
            } catch (OptionalitySppiFailureException expected) {
                raised = expected;
            }
            assertThat(raised).isNotNull();
            assertThat(raised.offendingType()).isEqualTo(OptionSchedule.OptionType.CONVERSION);
            assertThat(at1Style().impliesFairValueThroughProfitOrLoss()).isTrue();
        }

        @Test
        @DisplayName("a conversion is caught even where a call would otherwise resolve the term")
        void theGateScansEveryOptionAndNotJustTheFirst() {
            // The defect this guards: a gate that inspected only the earliest option, or
            // only the option that resolves the term, would let this instrument through.
            // The call at year five is a perfectly good truncation point and EARLIEST_CALL
            // would happily cost it — producing an EIR for an instrument that has none. The
            // conversion is dated later and sorts later, so order cannot be relied on.
            OptionSchedule callableConvertible = schedule(ExercisePolicy.EARLIEST_CALL,
                parCall(5),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CONVERSION,
                    OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(7)));

            assertThatThrownBy(() -> OptionalityResolver.requireSppiPass(callableConvertible))
                .isInstanceOf(OptionalitySppiFailureException.class);
            assertThat(callableConvertible.earliestExercise()).contains(annualDue(5));
        }

        @Test
        @DisplayName("the refusal is scoped to conversion: every other option type passes the gate")
        void everyOtherOptionTypePassesTheGate() {
            // The gate is a cliff and it must be a narrow one. A call, a put, a prepayment
            // right, a clean-up call and an extension are all ordinary amortised-cost
            // features, and a gate that refused any of them would exclude most of the
            // investment book from EIR processing entirely. Asserting the pass side is what
            // stops the ST-12 branch from being widened by a later edit.
            OptionSchedule everythingButConversion = schedule(ExercisePolicy.EARLIEST_CALL,
                parCall(4),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PUT,
                    OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(5)),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PREPAYMENT,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(6)),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CLEAN_UP_CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(7)),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                    OptionSchedule.OptionHolder.EITHER, annualDue(8)));

            OptionalityResolver.requireSppiPass(everythingButConversion);
            assertThat(everythingButConversion.impliesFairValueThroughProfitOrLoss()).isFalse();
        }

        @Test
        @DisplayName("the gate is asserted twice, so an AT1-style instrument cannot reach the resolver")
        void theBlueprintRefusesTheConvertibleBeforeTheResolverEverSeesIt() {
            // Both gates fire on the same instrument, and that redundancy is the design:
            // [03 § 11] wants the classification gate run before any EIR work is
            // commissioned, so ScheduleBlueprint refuses the composition, and the resolver
            // asserts it again at the point a life would otherwise be *returned* — the only
            // place where being wrong produces a number rather than an error.
            //
            // Which means there is no reachable path from a constructed blueprint into
            // OptionalityResolver.resolve with a conversion option present. That is worth
            // pinning: if the blueprint check were ever relaxed, this test fails and the
            // reviewer is told that the second gate is now load-bearing.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> tenYearBond(at1Style()))
                .withMessageContaining("incoherent blueprint (ST-11)")
                .withMessageContaining("CONVERSION option on an asset fails SPPI")
                .withMessageContaining("no EIR arises at all (ST-12)");
        }
    }

    // ================================================== 2. the par test — docs 09 § 3.4

    @Nested
    @DisplayName("at par a change of assumed life is not a P&L event (docs 09 § 3.4, ST-9)")
    class TheParTest {

        /**
         * A floating profile so that all six policies are computable on one
         * instrument: {@code NEXT_REPRICING} needs a repricing date and nothing else
         * here supplies one.
         */
        private ScheduleBlueprint sixPolicyBond() {
            return bond(10, new PrincipalProfile.BulletAtMaturity(),
                new RateProfile.Floating("EBLR", bd("250"), List.of(annualDue(7)),
                    NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.CONTRACTUAL_MATURITY, parCall(5)), 10);
        }

        /**
         * Every judgement supplied, so that no policy is skipped for want of an
         * input. Redemption judged at year three; a 40/60 split between the call and
         * maturity; a market view that makes the call comfortably in the money.
         */
        private OptionalityJudgements everyJudgement() {
            return OptionalityJudgements.mostLikely(annualDue(3))
                .withWeightedOutcomes(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.60"))))
                .withMarketView(OptionalityJudgements.MarketView.frictionless(
                    Rate.annualEffective(bd("0.06"))));
        }

        @Test
        @DisplayName("six policies, six different lives, one rate: 9.000000% at par, divergence zero")
        void atParEveryPolicyProducesTheCouponRate() {
            // The most useful single result in docs 09, expressed in the resolver's own
            // terms. A bond bought at par yields its coupon whatever life is assumed,
            // because the revised flows always discount to the outstanding balance at the
            // contractual rate. So six policies resolve six different lives — 10, 5, 3, 8,
            // 7 and 5 periods — and every one of them solves to exactly 9%.
            //
            // What this catches is a whole family of truncation defects at once, and it
            // catches them where they are unmissable. Omit the par redemption from a
            // truncated vector, book it at the wrong period, apply a strike where the
            // strike is par, discount the notional redemption against the EIR-leg balance
            // rather than the contractual one, or average per-outcome rates instead of
            // weighting flows — any of those moves at least one of these six numbers off
            // 9%. None of them can be hidden by an offsetting error, because there is
            // nothing here for the error to offset against.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                sixPolicyBond(), bullet(10), AT_PAR, solver, everyJudgement());

            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::policy)
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY, ExercisePolicy.EARLIEST_CALL,
                    ExercisePolicy.MOST_LIKELY_OUTCOME, ExercisePolicy.PROBABILITY_WEIGHTED,
                    ExercisePolicy.NEXT_REPRICING, ExercisePolicy.ECONOMIC_RATIONALITY);
            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::lifePeriods)
                .containsExactly(10, 5, 3, 8, 7, 5);

            assertThat(determination.alternatives()).allSatisfy(alternative ->
                assertThat(alternative.eir().effectiveAnnual())
                    .as("policy %s over %d periods", alternative.policy(), alternative.lifePeriods())
                    .isEqualByComparingTo(bd("0.09")));

            // 1,000,000 x 9% = 90,000, identically under all six. This is the figure a
            // controller sees, and its invariance is what makes the design consequence
            // concrete: re-estimating the life of a par-priced instrument moves no income.
            assertThat(determination.alternatives()).allSatisfy(alternative ->
                assertThat(alternative.firstPeriodIncome().atPresentationScale().amount())
                    .isEqualByComparingTo(bd("90000.00")));

            // Zero, exactly. Not "small" — the whole ST-9 argument is that the catch-up on
            // a nil unamortised premium is exactly nothing.
            assertThat(determination.widestDivergenceBps()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(resultFor(determination, InvariantId.ST_7).satisfied()).isTrue();
            assertThat(determination.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("away from par the same life change moves the rate, so the zero above can fail")
        void awayFromParTheSameLifeChangeMovesTheRate() {
            // Without this the test above is an assertion that cannot fail, and an
            // invariant that cannot fail is worse than no invariant: it reads as coverage
            // of the truncation arithmetic while gating nothing. The mechanism under test
            // is that divergence keys on the *unamortised premium or discount*, so the same
            // instrument, the same six policies and the same judgements must produce a
            // non-zero divergence as soon as the purchase price leaves par — and must
            // produce it in *both* directions, since the two are separately capable of
            // being zero by accident.
            ExpectedLifeDetermination above = OptionalityResolver.resolve(
                sixPolicyBond(), bullet(10), PREMIUM, solver, everyJudgement());
            ExpectedLifeDetermination below = OptionalityResolver.resolve(
                sixPolicyBond(), bullet(10), DISCOUNT, solver, everyJudgement());

            assertThat(above.widestDivergenceBps()).isGreaterThan(BigDecimal.ZERO);
            assertThat(below.widestDivergenceBps()).isGreaterThan(BigDecimal.ZERO);

            // At a premium the shorter life reports *less* income, at a discount *more*.
            // Same two lives, opposite signs — which is the docs 09 § 3.2 result and the
            // reason no blanket exercise policy can be assumed conservative.
            assertThat(costed(above, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome().amount())
                .isLessThan(costed(above, ExercisePolicy.CONTRACTUAL_MATURITY)
                    .firstPeriodIncome().amount());
            assertThat(costed(below, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome().amount())
                .isGreaterThan(costed(below, ExercisePolicy.CONTRACTUAL_MATURITY)
                    .firstPeriodIncome().amount());
        }

        @Test
        @DisplayName("a par instrument still records the alternatives, so the nil effect is evidenced")
        void theParInstrumentStillRecordsItsAlternatives() {
            // The performance consequence in docs 09 § 3.4 is that catch-up processing must
            // key on the unamortised balance rather than on the fact an assumption moved.
            // The disclosure consequence is the opposite: the determination must still show
            // the policies it costed, because "the divergence is nil" and "the divergence
            // was never computed" are the same number and different audit outcomes. So a
            // par instrument gets six alternatives and a satisfied ST-7, not one
            // alternative and a shrug.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                sixPolicyBond(), bullet(10), AT_PAR, solver, everyJudgement());

            assertThat(determination.alternatives()).hasSize(6);
            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(determination.chosenLifePeriods()).isEqualTo(10);
            assertThat(determination.chosen().policy())
                .isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(resultFor(determination, InvariantId.ST_7).detail())
                .contains("6 exercise policies computed, divergence quantified");
        }
    }

    // ======================================= 3. contractual maturity against the call

    @Nested
    @DisplayName("contractual maturity against earliest exercise, costed both ways (O1, O2, O4)")
    class ContractualMaturityAgainstEarliestExercise {

        @Test
        @DisplayName("O1 — premium bond, par call at year five: 8.246545% over ten, 7.755768% over five")
        void fixtureO1PremiumBond() {
            // docs 09 § 6 O1. A 9% ten-year bullet bought at 1,050,000 and callable at par
            // at year five. To maturity the flows are 90,000 a year for ten years plus
            // 1,000,000, which yields 8.246545%; truncated at the call they are 90,000 for
            // five years plus 1,000,000, which yields 7.755768%.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL, parCall(5))),
                bullet(10), PREMIUM, solver);

            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.EARLIEST_CALL);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);

            ExpectedLifeDetermination.LifeAlternative toMaturity =
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY);
            ExpectedLifeDetermination.LifeAlternative toCall =
                costed(determination, ExercisePolicy.EARLIEST_CALL);

            assertThat(toMaturity.lifePeriods()).isEqualTo(10);
            assertThat(toMaturity.eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.082465452296"));
            assertThat(toCall.lifePeriods()).isEqualTo(5);
            assertThat(toCall.eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.077557682116"));

            // Year-one income, docs 09 § 6 O1: 86,588.72 against 81,435.57. Both are
            // 1,050,000 x the stored rate, because the first period is a whole period and
            // the roll-forward accretes opening x r over it — but they are asserted against
            // the published fixture rather than recomputed, so a defect in the roll-forward
            // shows up here as well as in AmortisationEngineTest.
            assertThat(toMaturity.firstPeriodIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("86588.72"));
            assertThat(toCall.firstPeriodIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("81435.57"));

            // 49.1 bp. The exact figure is (0.082465452296 - 0.077557682116) x 10,000.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("49.0777018"));
        }

        @Test
        @DisplayName("O2 — the same bond at a discount, and the divergence reverses sign")
        void fixtureO2DiscountBond() {
            // docs 09 § 6 O2, and the reason the engine computes both bases instead of the
            // prudent one. Identical bond, identical call, purchase price 950,000 instead
            // of 1,050,000: to maturity 9.806992%, to the call 10.330130%. The call basis
            // now reports *more* income, where at a premium it reported less.
            //
            // This is what makes "pick the conservative policy globally" incoherent, and it
            // is why the assertion is on the ordering of the two incomes and not only on
            // their magnitudes: an implementation that had the sign of the premium
            // amortisation backwards would still produce two plausible rates.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL, parCall(5))),
                bullet(10), DISCOUNT, solver);

            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.098069922639"));
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.103301297770"));

            // 93,166.43 and 98,136.23 — docs 09 § 6 O2, 4,969.81 apart.
            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                .firstPeriodIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("93166.43"));
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL)
                .firstPeriodIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("98136.23"));
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("52.31375131"));
        }

        @Test
        @DisplayName("O4 — a put is a shortening option: the holder's exercise truncates too")
        void fixtureO4PuttableBond() {
            // docs 09 § 6 O4. 9% at 1,050,000, holder may put at par at year three: to the
            // put 7.091554%, to maturity 8.246545%, 115.5 bp apart. The defect this catches
            // is a resolver that treated only a CALL as term-resolving — a put shortens the
            // instrument just as effectively, and the difference between the two is who
            // holds the exercise judgement, not which direction the term moves.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL,
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PUT,
                        OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(3)))),
                bullet(10), PREMIUM, solver);

            assertThat(determination.chosenLifePeriods()).isEqualTo(3);
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.070915538660"));
            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.082465452296"));
            // 115.5 bp: (0.082465452296 - 0.070915538660) x 10,000.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("115.49913636"));
        }

        @Test
        @DisplayName("the earliest of several competing shortening options wins")
        void theEarliestShorteningOptionWins() {
            // Three exits, none of them the first in the list, and the policy is about the
            // earliest date the premium can be taken away — so the year-three put is the
            // truncation point even though the year-five call is the option a reader would
            // reach for first. An implementation that took options.get(0) or the last match
            // would pass a single-option test and fail this one.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL,
                    parCall(5),
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PUT,
                        OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(3)),
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CLEAN_UP_CALL,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(8)))),
                bullet(10), PREMIUM, solver);

            assertThat(determination.chosenLifePeriods()).isEqualTo(3);
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.070915538660"));
        }

        @Test
        @DisplayName("an exercise date at or beyond the final rung is declined, not reported as a zero")
        void anExerciseAtMaturityIsDeclinedRatherThanCosted() {
            // A call dated on the maturity date truncates nothing. Reporting an alternative
            // identical to contractual maturity under a different policy name would put a
            // spurious zero into the published divergence — a reviewer would read "we
            // computed both bases and they agree" where the truth is "the option is
            // economically absent". So the policy is declined.
            //
            // And because it is *unchosen* here, the decline is a finding rather than an
            // error: the determination comes back with one alternative and a failed ST-7,
            // which is the honest disclosure of a divergence that could not be quantified.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.CONTRACTUAL_MATURITY, parCall(10))),
                bullet(10), PREMIUM, solver);

            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::policy)
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(determination.widestDivergenceBps()).isEqualByComparingTo(BigDecimal.ZERO);

            InvariantResult st7 = resultFor(determination, InvariantId.ST_7);
            assertThat(st7.satisfied()).isFalse();
            assertThat(st7.detail()).contains("divergence was never quantified");
            assertThat(determination.allSatisfied()).isFalse();

            assertThat(OptionalityResolver.applicablePolicies(
                tenYearBond(schedule(ExercisePolicy.CONTRACTUAL_MATURITY, parCall(10))),
                bullet(10), OptionalityJudgements.none()))
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY);
        }

        @Test
        @DisplayName("the same decline is fatal where that policy is the recorded one")
        void theSameDeclineIsFatalWhenChosen() {
            // The other half of the rule, and the half that matters most. An unchosen
            // policy that cannot be evaluated is left out; the *recorded* one cannot be,
            // because the only alternative to failing is publishing a figure produced by a
            // policy nobody elected. Section 4.3 names that silent substitution the most
            // damaging failure available to the engine.
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL, parCall(10))),
                bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("at or beyond the final rung (period 10)")
                .hasMessageContaining("truncation is a no-op")
                .hasMessageContaining("does not substitute another policy for the one recorded");
        }

        @Test
        @DisplayName("an exercise date before the first due date has no rung to truncate at")
        void anExerciseBeforeTheFirstDueDateIsRefused() {
            // Exercise is aligned back to the boundary at or before it, which keeps the
            // truncated ladder a prefix of the billed one. A date before the first due date
            // has no such boundary, and aligning it forward would bill an instalment the
            // truncation says was never due. The message names the first due date, which is
            // what makes it a data-fix instruction rather than a stack trace.
            OptionSchedule tooEarly = new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, LocalDate.of(2026, 6, 1))),
                ExercisePolicy.EARLIEST_CALL);

            assertThatThrownBy(() -> OptionalityResolver.resolve(
                tenYearBond(tooEarly), bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("2026-06-01")
                .hasMessageContaining("falls before the first due date 2027-04-01");
        }

        @Test
        @DisplayName("alternatives are built in enum order, so two runs are comparable (DT-1)")
        void alternativesAreBuiltInEnumOrder() {
            // A published divergence is only comparable between runs if the alternatives
            // arrive in a fixed order. Enum order is the fixed order chosen, and it is not
            // the order the policies were requested in nor the order the plans became
            // available — an implementation accumulating into a HashMap or appending the
            // chosen policy first would pass every arithmetic test above and fail this one.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                tenYearBond(schedule(ExercisePolicy.MOST_LIKELY_OUTCOME, parCall(5))),
                bullet(10), PREMIUM, solver, OptionalityJudgements.mostLikely(annualDue(7)));

            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::policy)
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY, ExercisePolicy.EARLIEST_CALL,
                    ExercisePolicy.MOST_LIKELY_OUTCOME);
            // The chosen policy is the third entry, not the first: chosen() has to find it
            // rather than take the head.
            assertThat(determination.chosen().policy())
                .isEqualTo(ExercisePolicy.MOST_LIKELY_OUTCOME);
            assertThat(determination.chosenLifePeriods()).isEqualTo(7);
        }
    }

    // ============================================= 4. the shaped ladder and its flows

    @Nested
    @DisplayName("the shaped ladder splits a strike premium par-to-principal, premium-to-interest")
    class TheShapedLadderAndItsFlows {

        /** A one-shot call at 102 at year five, on the ten-year premium bond. */
        private ScheduleBlueprint callableAt102() {
            return tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL,
                option(OptionSchedule.OptionType.CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER, 5, 5, "1.02")));
        }

        @Test
        @DisplayName("a call at 102 returns principal of 1,000,000 and interest of 110,000, not principal of 1,020,000")
        void theStrikePremiumIsInterestAndNotPrincipal() {
            // A call at 102 does not return 102% of the principal advanced. It returns the
            // principal and pays 2% for the privilege of early termination, and that 2% is
            // interest. Booking it as principal would break ST-3 on an instrument where
            // nothing is wrong — scheduled principal would come to 1,020,000 against
            // 1,000,000 advanced — and would misstate the contractual interest leg by the
            // same 20,000.
            //
            // Both halves are asserted, because either alone is weak: the rung split could
            // be right while ST-3 was computed off a different figure, and ST-3 could pass
            // on a ladder whose interest leg was short.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                callableAt102(), bullet(10), PREMIUM, solver);
            InstalmentLadder shaped = OptionalityResolver.ladderUnder(
                callableAt102(), bullet(10), determination);

            assertThat(shaped.rungs()).hasSize(5);
            InstalmentLadder.Rung redemption = shaped.rung(5);
            assertThat(redemption.dueOn()).isEqualTo(annualDue(5));
            assertThat(redemption.principal().amount()).isEqualByComparingTo(bd("1000000"));
            assertThat(redemption.interest().amount()).isEqualByComparingTo(bd("110000"));
            assertThat(redemption.total().amount()).isEqualByComparingTo(bd("1110000"));
            assertThat(redemption.balanceAfter().isZero()).isTrue();

            // ST-3 on the *shaped* ladder, not only on the contractual one: 1,000,000 of
            // scheduled principal plus a nil terminal balance against 1,000,000 advanced.
            assertThat(shaped.allSatisfied()).isTrue();
            assertThat(shaped.totalInterest().amount()).isEqualByComparingTo(bd("470000"));
            // Four coupons kept unchanged from the billed ladder. The truncated instalments
            // are the ones the lender actually bills, which is the property that lets the
            // contractual leg still reconcile to the core banking system.
            assertThat(shaped.through(4)).allSatisfy(rung ->
                assertThat(rung.total().amount()).isEqualByComparingTo(bd("90000")));
        }

        @Test
        @DisplayName("the same split appears in the flows, as three flows sharing one period ordinal")
        void theFlowsCarryTheSameSplitAsTheLadder() {
            // The ladder is what the downstream stages bill from and the flows are what the
            // rate was solved over, so a redemption folded into one but not the other is a
            // reconciliation break with no single diagnosable cause. Both come out of one
            // routine, and this asserts they agree.
            //
            // Three flows share period five — the coupon, the par redemption and the strike
            // premium. That is exactly why CashFlow carries its period rather than deriving
            // it from position: they share a discounting exponent, so the split costs
            // nothing in the rate while keeping the par/premium distinction auditable.
            FlowVector vector = OptionalityResolver.flowsUnder(
                callableAt102(), bullet(10), PREMIUM, ExercisePolicy.EARLIEST_CALL,
                OptionalityJudgements.none());

            assertThat(vector.anchorDate()).isEqualTo(VALUE_DATE);
            assertThat(vector.atInception()).hasSize(1);
            assertThat(vector.atInception().get(0).amount().amount())
                .isEqualByComparingTo(bd("-1050000"));
            assertThat(vector.atInception().get(0).kind()).isEqualTo(FlowKind.DISBURSEMENT);
            assertThat(vector.maxPeriodIndex()).isEqualTo(5);

            List<CashFlow> atCall = atPeriod(vector, 5);
            assertThat(atCall).hasSize(3);
            assertThat(atCall).allSatisfy(flow -> assertThat(flow.date()).isEqualTo(annualDue(5)));
            assertThat(atCall).filteredOn(flow -> flow.kind() == FlowKind.PRINCIPAL)
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount().amount())
                    .isEqualByComparingTo(bd("1000000")));
            // Compared by value rather than by BigDecimal equality: the premium arrives as
            // 20000.00 from 1,000,000 x 0.02 at working precision, and a scale-sensitive
            // comparison would fail on a figure that is arithmetically right.
            assertThat(atCall).filteredOn(flow -> flow.kind() == FlowKind.INTEREST)
                .extracting(flow -> flow.amount().amount())
                .satisfiesExactlyInAnyOrder(
                    coupon -> assertThat(coupon).isEqualByComparingTo(bd("90000")),
                    premium -> assertThat(premium).isEqualByComparingTo(bd("20000")));

            // Nothing after the call, and the four coupons before it untouched.
            assertThat(vector.future()).hasSize(7);
        }

        @Test
        @DisplayName("under contractual maturity the ladder is handed back untouched, terminal balance included")
        void contractualMaturityReturnsTheLadderItself() {
            // Truncation and extension are the only reasons to rebuild, and neither
            // applies. Returning the same instance is not an optimisation detail: it is the
            // guarantee that the contractual leg the ledger reconciles against has not been
            // silently reconstructed, rung by rung, into something arithmetically equal and
            // structurally different. ST-5 exists to protect a non-zero terminal balance on
            // a balloon or a residual-value lease, and a rebuild is where such a balance
            // gets quietly plugged.
            InstalmentLadder contractual = bullet(10);
            ScheduleBlueprint blueprint =
                tenYearBond(schedule(ExercisePolicy.CONTRACTUAL_MATURITY, parCall(5)));
            ExpectedLifeDetermination determination =
                OptionalityResolver.resolve(blueprint, contractual, PREMIUM, solver);

            assertThat(OptionalityResolver.ladderUnder(blueprint, contractual, determination))
                .isSameAs(contractual);
        }

        @Test
        @DisplayName("a ladder shaped under judgements the determination never saw is refused")
        void aLadderShapedUnderDifferentJudgementsIsRefused() {
            // The plan is re-derived inside ladderUnder rather than carried on the
            // determination, which has no field for it. That is only safe if it re-derives
            // to the same life — so the equality is checked. A determination resolved under
            // one management view and shaped under another would otherwise bill a ladder
            // the published rate was never solved over, and nothing downstream could detect
            // it: both ladders are internally consistent, both satisfy ST-3, and the only
            // evidence of the mismatch is a rate that reproduces no amortisation.
            ScheduleBlueprint blueprint = tenYearBond(
                schedule(ExercisePolicy.MOST_LIKELY_OUTCOME, parCall(5)));
            ExpectedLifeDetermination resolvedAtYearSix = OptionalityResolver.resolve(
                blueprint, bullet(10), PREMIUM, solver,
                OptionalityJudgements.mostLikely(annualDue(6)));

            assertThat(resolvedAtYearSix.chosenLifePeriods()).isEqualTo(6);
            assertThatThrownBy(() -> OptionalityResolver.ladderUnder(
                blueprint, bullet(10), resolvedAtYearSix,
                OptionalityJudgements.mostLikely(annualDue(4))))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("records a life of 6 periods but these inputs resolve"
                    + " MOST_LIKELY_OUTCOME to 4")
                .hasMessageContaining("the judgements that shaped the ladder are not the ones"
                    + " that produced the determination");
        }
    }

    // ================================================= 5. the B5.4.4 repricing shortcut

    @Nested
    @DisplayName("the next-repricing shortcut closes the vector with a notional redemption (B5.4.4)")
    class NextRepricing {

        /**
         * A floating ten-year bond resetting at year five, with a par call at year
         * seven so the schedule can carry a policy other than contractual maturity.
         */
        private ScheduleBlueprint repricingBond() {
            return bond(10, new PrincipalProfile.BulletAtMaturity(),
                new RateProfile.Floating("EBLR", bd("250"), List.of(annualDue(5)),
                    NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.NEXT_REPRICING, parCall(7)), 10);
        }

        @Test
        @DisplayName("exactly one NOTIONAL_REDEMPTION closes the vector, at the reset, for the contractual balance")
        void oneNotionalRedemptionAtTheReset() {
            // Mechanically identical to RepricingShortcutProjector: keep the flows up to
            // the reset and close with a synthetic flow equal to the *contractual* balance
            // there. Contractual, not the EIR-leg balance, because the synthetic flow stands
            // in for what the borrower would owe if the instrument matured at the reset —
            // using the EIR-leg balance would put the unamortised premium inside the flow
            // the premium is being amortised against.
            //
            // The kind carries the whole disclosure. A NOTIONAL_REDEMPTION is an accounting
            // construct nobody is ever billed; labelling it PRINCIPAL would tell a reader
            // the instrument redeems at year five, which it does not.
            FlowVector vector = OptionalityResolver.flowsUnder(
                repricingBond(), bullet(10), PREMIUM, ExercisePolicy.NEXT_REPRICING,
                OptionalityJudgements.none());

            List<CashFlow> synthetic = vector.future().stream()
                .filter(flow -> flow.kind() == FlowKind.NOTIONAL_REDEMPTION).toList();
            assertThat(synthetic).hasSize(1);
            assertThat(synthetic.get(0).periodIndex()).isEqualTo(5);
            assertThat(synthetic.get(0).date()).isEqualTo(annualDue(5));
            assertThat(synthetic.get(0).amount().amount()).isEqualByComparingTo(bd("1000000"));

            // No PRINCIPAL flow at all: the bullet's real principal repayment sits at year
            // ten and the vector stops at year five.
            assertThat(vector.future()).noneSatisfy(flow ->
                assertThat(flow.kind()).isEqualTo(FlowKind.PRINCIPAL));
            assertThat(vector.maxPeriodIndex()).isEqualTo(5);
            assertThat(vector.future()).hasSize(6);
        }

        @Test
        @DisplayName("truncating at the reset yields the same rate as a real par call on that date")
        void theShortcutAgreesWithAParCallAtTheSameDate() {
            // The cross-check the shared truncation routine exists to make possible.
            // A notional redemption of 1,000,000 at year five and a real par call at year
            // five are the same amounts on the same dates, so they must solve to the same
            // rate — 7.755768%, docs 09 § 6 O1. Only the flow kind differs, and kind never
            // affects discounting.
            //
            // What this catches is the substitution the routine's comment warns about: had
            // the shortcut closed on the EIR-leg balance, this equality would break by
            // roughly the unamortised premium at the reset, and it would break in a way no
            // single-basis test would notice.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                repricingBond(), bullet(10), PREMIUM, solver);

            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.NEXT_REPRICING);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);
            assertThat(costed(determination, ExercisePolicy.NEXT_REPRICING).eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.077557682116"));
            assertThat(costed(determination, ExercisePolicy.NEXT_REPRICING)
                .firstPeriodIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("81435.57"));

            // The call at year seven is a separate basis and is costed alongside: 90,000 a
            // year for seven years plus 1,000,000 against 1,050,000 is 8.038377%.
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL).lifePeriods())
                .isEqualTo(7);
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual())
                .isEqualByComparingTo(bd("0.080383771334"));
        }

        @Test
        @DisplayName("a rate profile with no repricing date has nothing to amortise to")
        void aFixedRateProfileHasNoRepricingDate() {
            // Elected on a fixed-rate instrument the shortcut has no anchor. Falling back
            // to expected life would apply a different policy from the one recorded, which
            // is exactly the substitution the recorded policy exists to prevent — so
            // NEXT_REPRICING is simply not among the applicable policies, and the blueprint
            // refuses the election outright as a coherence conflict.
            ScheduleBlueprint fixed = tenYearBond(
                schedule(ExercisePolicy.CONTRACTUAL_MATURITY, parCall(5)));

            assertThat(OptionalityResolver.applicablePolicies(
                fixed, bullet(10), OptionalityJudgements.none()))
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY,
                    ExercisePolicy.EARLIEST_CALL);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> tenYearBond(
                    schedule(ExercisePolicy.NEXT_REPRICING, parCall(5))))
                .withMessageContaining("NEXT_REPRICING policy amortises to a repricing date")
                .withMessageContaining("rate profile FIXED has none");
        }

        @Test
        @DisplayName("a reset at or beyond the final rung leaves nothing to truncate")
        void aResetAtMaturityIsDeclined() {
            // Same shape as the call-at-maturity decline, and it needs its own test because
            // it comes from a different branch reading a different date source. A reset on
            // the maturity date makes the shortcut a no-op, and the honest answer is that
            // the policy is unavailable rather than that it agrees with contractual
            // maturity.
            ScheduleBlueprint resetAtMaturity = bond(10, new PrincipalProfile.BulletAtMaturity(),
                new RateProfile.Floating("EBLR", bd("250"), List.of(annualDue(10)),
                    NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.NEXT_REPRICING, parCall(7)), 10);

            assertThatThrownBy(() -> OptionalityResolver.resolve(
                resetAtMaturity, bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("the next reset 2036-04-01 falls at or beyond the final rung")
                .hasMessageContaining("nothing to truncate");
        }
    }

    // ============================== 6. the judgements the engine will not invent

    @Nested
    @DisplayName("a policy whose management input is absent is not computed on a default")
    class JudgementsTheEngineWillNotInvent {

        private ScheduleBlueprint callable(ExercisePolicy policy) {
            return tenYearBond(schedule(policy, parCall(5)));
        }

        @Test
        @DisplayName("MOST_LIKELY_OUTCOME with no supplied date reaches the exception queue")
        void mostLikelyOutcomeWithoutADateIsUnresolved() {
            // There is deliberately no heuristic behind this policy — not "the first call
            // date if the bond is at a premium", not "maturity unless the option is in the
            // money". Any of those would be a model, and a model masquerading as a
            // judgement is worse than either: it carries none of the Chapter V governance a
            // model owes and none of the documented reasoning a judgement owes.
            //
            // So the instrument goes to the exception queue. Note what is *not* asserted
            // here: no rate, no life, no partial determination. The contract is that
            // nothing plausible comes back.
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                callable(ExercisePolicy.MOST_LIKELY_OUTCOME), bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("no redemption date was supplied")
                .hasMessageContaining("the engine does not guess it")
                .hasMessageContaining("OptionalityJudgements.mostLikely");
        }

        @Test
        @DisplayName("PROBABILITY_WEIGHTED with no distribution reaches the exception queue")
        void probabilityWeightedWithoutADistributionIsUnresolved() {
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                callable(ExercisePolicy.PROBABILITY_WEIGHTED), bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("no exercise distribution was supplied")
                .hasMessageContaining("the weights are a management input")
                .hasMessageContaining("OptionalityJudgements.probabilityWeighted");
        }

        @Test
        @DisplayName("ECONOMIC_RATIONALITY with no market view reaches the exception queue")
        void economicRationalityWithoutAMarketViewIsUnresolved() {
            // Moneyness is relative to a market, and the threshold is a modelling choice.
            // Neither is derivable from the contract, and a default for the threshold in
            // particular would be a strong hidden assumption: zero friction calls every
            // premium bond on its first call date.
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                callable(ExercisePolicy.ECONOMIC_RATIONALITY), bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("no market view was supplied")
                .hasMessageContaining("the exercise threshold is a modelling choice")
                .hasMessageContaining("OptionalityJudgements.economicRationality");
        }

        @Test
        @DisplayName("the three judgement policies appear only once their inputs do")
        void theJudgementPoliciesAppearOnlyOnceSupplied() {
            // OptionalityJudgements.none() is a real and common value, not a placeholder:
            // it is the position of a book that has not yet taken the policy decisions, and
            // what it leaves available is exactly the three the contract determines by
            // itself. This is the assertion that the resolver does not quietly compute a
            // judgement policy on an invented input and then report it as though a
            // committee had made it.
            ScheduleBlueprint blueprint = callable(ExercisePolicy.CONTRACTUAL_MATURITY);

            assertThat(OptionalityResolver.applicablePolicies(
                blueprint, bullet(10), OptionalityJudgements.none()))
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY,
                    ExercisePolicy.EARLIEST_CALL);

            OptionalityJudgements supplied = OptionalityJudgements.mostLikely(annualDue(6))
                .withWeightedOutcomes(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.60"))))
                .withMarketView(OptionalityJudgements.MarketView.of(
                    Rate.annualEffective(bd("0.06")), bd("0.02")));

            assertThat(OptionalityResolver.applicablePolicies(blueprint, bullet(10), supplied))
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY,
                    ExercisePolicy.EARLIEST_CALL, ExercisePolicy.MOST_LIKELY_OUTCOME,
                    ExercisePolicy.PROBABILITY_WEIGHTED, ExercisePolicy.ECONOMIC_RATIONALITY);
        }

        @Test
        @DisplayName("the assumed redemption reads its strike from whichever exercise window covers it")
        void theAssumedRedemptionReadsTheStrikeFromItsWindow() {
            // A call exercisable at 102 between year five and year seven, and management
            // judges redemption at year six. The strike is read from the window rather than
            // assumed at par, so the redemption is 1,020,000 and the rate is 8.183757% —
            // 90,000 a year for six years plus 1,020,000, against 1,050,000. Redeeming the
            // same six-year life at par instead yields 7.921013%, understating the rate by
            // 26.3 bp, because the 20,000 call premium is income the holder receives.
            ScheduleBlueprint blueprint = tenYearBond(
                schedule(ExercisePolicy.MOST_LIKELY_OUTCOME,
                    option(OptionSchedule.OptionType.CALL,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, 5, 7, "1.02")));
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                blueprint, bullet(10), PREMIUM, solver,
                OptionalityJudgements.mostLikely(annualDue(6)));

            assertThat(determination.chosenLifePeriods()).isEqualTo(6);
            assertThat(costed(determination, ExercisePolicy.MOST_LIKELY_OUTCOME)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.081837568337"));

            // And the ladder carries the same 102: 1,000,000 of principal against 110,000
            // of interest at year six, the coupon plus the 20,000 premium.
            InstalmentLadder shaped = OptionalityResolver.ladderUnder(blueprint, bullet(10),
                determination, OptionalityJudgements.mostLikely(annualDue(6)));
            assertThat(shaped.rung(6).principal().amount()).isEqualByComparingTo(bd("1000000"));
            assertThat(shaped.rung(6).interest().amount()).isEqualByComparingTo(bd("110000"));
        }

        @Test
        @DisplayName("a judged redemption no option covers redeems at par rather than being refused")
        void aJudgedRedemptionOutsideEveryWindowRedeemsAtPar() {
            // The common case is an assumed prepayment on a facility that grants no formal
            // option, and refusing it would be the engine second-guessing the judgement. So
            // a date outside every exercise window redeems at par: year eight is past the
            // year-seven end of the window, and the rate is 8.125769% — the eight-year par
            // redemption. Had the 102 strike leaked outside its window the rate would be
            // 8.305767%, 18 bp higher.
            ScheduleBlueprint blueprint = tenYearBond(
                schedule(ExercisePolicy.MOST_LIKELY_OUTCOME,
                    option(OptionSchedule.OptionType.CALL,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, 5, 7, "1.02")));
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                blueprint, bullet(10), PREMIUM, solver,
                OptionalityJudgements.mostLikely(annualDue(8)));

            assertThat(determination.chosenLifePeriods()).isEqualTo(8);
            assertThat(costed(determination, ExercisePolicy.MOST_LIKELY_OUTCOME)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.081257690197"));
        }

        @Test
        @DisplayName("a judged redemption before the first due date is refused, naming that date")
        void aJudgedRedemptionBeforeTheFirstDueDateIsRefused() {
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                callable(ExercisePolicy.MOST_LIKELY_OUTCOME), bullet(10), PREMIUM, solver,
                OptionalityJudgements.mostLikely(LocalDate.of(2026, 9, 30))))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("the assumed redemption 2026-09-30 falls before the first"
                    + " due date 2027-04-01");
        }
    }

    // ==================================================== 7. probability-weighted flows

    @Nested
    @DisplayName("a probability-weighted rate is solved over weighted flows, never averaged from rates")
    class ProbabilityWeighted {

        /** 40% called at par at year five, 60% held to maturity. */
        private OptionalityJudgements fortySixty() {
            return OptionalityJudgements.probabilityWeighted(List.of(
                OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")),
                OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.60"))));
        }

        private ScheduleBlueprint weightedBond() {
            return tenYearBond(schedule(ExercisePolicy.PROBABILITY_WEIGHTED, parCall(5)));
        }

        @Test
        @DisplayName("the rate comes from the weighted vector, and differs from the weighted average of rates")
        void theRateIsSolvedOverFlowsAndNotAveragedFromRates() {
            // An expected value of a non-linear function of a rate is not that function of
            // the expected rate. Averaging per-outcome rates would produce a figure that
            // reproduces no amortisation at all, so IFRS 9 asks for the flows.
            //
            // The weighted flows on this bond, at 1,050,000: 90,000 in years one to four
            // (both outcomes pay the coupon), 90,000 plus 0.40 x 1,000,000 = 490,000 in
            // year five, 0.60 x 90,000 = 54,000 in years six to nine, and
            // 0.60 x 1,090,000 = 654,000 in year ten. That sequence yields 8.107027%.
            //
            // The weighted average of the two per-outcome rates is
            // 0.40 x 7.755768% + 0.60 x 8.246545% = 8.050234%, which is 5.7 bp away. So the
            // two are distinguishable, and asserting the first while explicitly rejecting
            // the second is what makes this test catch the tempting implementation rather
            // than merely describing the correct one.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                weightedBond(), bullet(10), PREMIUM, solver, fortySixty());

            BigDecimal weighted = costed(determination, ExercisePolicy.PROBABILITY_WEIGHTED)
                .eir().effectiveAnnual();
            assertThat(weighted).isEqualByComparingTo(bd("0.081070271749"));
            assertThat(weighted).isNotEqualByComparingTo(bd("0.080502344224"));
        }

        @Test
        @DisplayName("the reported life is a label: 8 periods, and nothing in the arithmetic used it")
        void theReportedLifeIsALabelAndNotAnInput() {
            // 0.40 x 5 + 0.60 x 10 = 8, and the 8 is reported because a reviewer needs a
            // life to look at. It is not an input: solving over an eight-period vector
            // instead of the weighted one would be a different, and wrong, computation.
            //
            // The proof is arithmetic rather than structural. An eight-period par
            // redemption on this bond yields 8.125769% (docs 09 § 6 O5, same flows), and
            // the weighted rate is 8.107027%. They differ, so the rounded life demonstrably
            // did not drive the solve.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                weightedBond(), bullet(10), PREMIUM, solver, fortySixty());

            assertThat(determination.chosenLifePeriods()).isEqualTo(8);
            assertThat(costed(determination, ExercisePolicy.PROBABILITY_WEIGHTED).lifePeriods())
                .isEqualTo(8);
            assertThat(costed(determination, ExercisePolicy.PROBABILITY_WEIGHTED)
                .eir().effectiveAnnual())
                .isNotEqualByComparingTo(bd("0.081257690197"));
            // ST-8 still applies to the label: 8 periods inside a ten-period horizon.
            assertThat(resultFor(determination, InvariantId.ST_8).satisfied()).isTrue();
        }

        @Test
        @DisplayName("the weighted vector blends amounts and not kinds, so a partial redemption stays a redemption")
        void theWeightedVectorBlendsAmountsAndNotKinds() {
            // Flows are keyed by date, period and kind, so an outcome's redemption stays
            // distinguishable from another outcome's coupon falling on the same day. Year
            // five is where that matters: the coupon is paid under both outcomes and comes
            // to the full 90,000, while the redemption is paid under one and comes to
            // 400,000. Merging them into a single 490,000 flow would discount identically
            // and destroy the audit trail — and would make the year-ten COMBINED_EMI
            // indistinguishable from a coupon.
            FlowVector vector = OptionalityResolver.flowsUnder(
                weightedBond(), bullet(10), PREMIUM, ExercisePolicy.PROBABILITY_WEIGHTED,
                fortySixty());

            assertThat(atPeriod(vector, 1)).singleElement().satisfies(flow -> {
                assertThat(flow.kind()).isEqualTo(FlowKind.INTEREST);
                assertThat(flow.amount().amount()).isEqualByComparingTo(bd("90000"));
            });
            assertThat(atPeriod(vector, 5)).hasSize(2);
            assertThat(atPeriod(vector, 5))
                .filteredOn(flow -> flow.kind() == FlowKind.INTEREST).singleElement()
                .satisfies(flow -> assertThat(flow.amount().amount())
                    .isEqualByComparingTo(bd("90000")));
            assertThat(atPeriod(vector, 5))
                .filteredOn(flow -> flow.kind() == FlowKind.PRINCIPAL).singleElement()
                .satisfies(flow -> assertThat(flow.amount().amount())
                    .isEqualByComparingTo(bd("400000")));
            // Past the call only the surviving 60% pays.
            assertThat(atPeriod(vector, 6)).singleElement().satisfies(flow ->
                assertThat(flow.amount().amount()).isEqualByComparingTo(bd("54000")));
            assertThat(atPeriod(vector, 10)).singleElement().satisfies(flow -> {
                assertThat(flow.kind()).isEqualTo(FlowKind.COMBINED_EMI);
                assertThat(flow.amount().amount()).isEqualByComparingTo(bd("654000"));
            });
        }

        @Test
        @DisplayName("a probability-weighted determination has no single instalment ladder, and says so")
        void aWeightedDeterminationHasNoLadder() {
            // Its flows are an expectation across outcomes and every outcome has a
            // different redemption date. Asking for "the" ladder is asking which outcome
            // happened, and answering it — with the modal outcome, say, or the one nearest
            // the weighted life — would quietly replace an expectation with a scenario and
            // hand a rounded life to a stage that bills from it. The refusal is the correct
            // answer, not a gap, and it points the caller at flowsUnder.
            ScheduleBlueprint blueprint = weightedBond();
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                blueprint, bullet(10), PREMIUM, solver, fortySixty());

            assertThatThrownBy(() -> OptionalityResolver.ladderUnder(
                blueprint, bullet(10), determination, fortySixty()))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("has no single instalment ladder")
                .hasMessageContaining("an expectation across 2 exercise outcomes")
                .hasMessageContaining("Use flowsUnder");
        }

        @Test
        @DisplayName("an outcome before the first due date is refused for the same reason a call is")
        void anOutcomeBeforeTheFirstDueDateIsRefused() {
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                weightedBond(), bullet(10), PREMIUM, solver,
                OptionalityJudgements.probabilityWeighted(List.of(
                    new OptionalityJudgements.WeightedOutcome(
                        LocalDate.of(2026, 10, 1), BigDecimal.ONE, bd("0.30")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.70"))))))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("outcome dated 2026-10-01 falls before the first due date"
                    + " 2027-04-01");
        }
    }

    // ==================================================== 8. the economic-rationality model

    @Nested
    @DisplayName("the economic-rationality model, and the assumptions it is scoped to (ACPIR Chapter V)")
    class EconomicRationality {

        private ScheduleBlueprint callableBond() {
            return tenYearBond(schedule(ExercisePolicy.ECONOMIC_RATIONALITY, parCall(5)));
        }

        private OptionalityJudgements marketAt(String yield, String threshold) {
            return OptionalityJudgements.economicRationality(
                OptionalityJudgements.MarketView.of(
                    Rate.annualEffective(bd(yield)), bd(threshold)));
        }

        @Test
        @DisplayName("a call trading well above its strike is assumed exercised")
        void aCallDeepInTheMoneyIsExercised() {
            // The obligor is paying more than market for its money, so redeeming at the
            // strike and refinancing is cheaper. At a 6% prevailing yield the remaining
            // flows from year five — 90,000 in each of years six to nine and 1,090,000 in
            // year ten, discounted on 30/360 dates where every year fraction is exactly one
            // — are worth 1,126,370.91 against a 1,000,000 strike. That is 12.6% in the
            // money, comfortably past a 2% threshold, so the model exercises and the life
            // is five periods at 7.755768%.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                callableBond(), bullet(10), PREMIUM, solver, marketAt("0.06", "0.02"));

            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.ECONOMIC_RATIONALITY);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);
            assertThat(costed(determination, ExercisePolicy.ECONOMIC_RATIONALITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.077557682116"));
            assertThat(ExercisePolicy.ECONOMIC_RATIONALITY.isModelDriven()).isTrue();
        }

        @Test
        @DisplayName("a call out of the money runs to maturity, and the alternative is still recorded")
        void aCallOutOfTheMoneyRunsToMaturity() {
            // At a 12% prevailing yield the continuation value at year five is 891,856.71,
            // well below the 1,000,000 strike: nobody redeems at par what the market prices
            // at 89. The life runs to contractual maturity and the alternative is *still*
            // recorded, because "the model ran and found no exercise rational" and "the
            // model was never run" are the same number and different audit outcomes.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                callableBond(), bullet(10), PREMIUM, solver, marketAt("0.12", "0.02"));

            assertThat(determination.chosenLifePeriods()).isEqualTo(10);
            assertThat(costed(determination, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(10);
            assertThat(costed(determination, ExercisePolicy.ECONOMIC_RATIONALITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.082465452296"));
            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::policy)
                .contains(ExercisePolicy.ECONOMIC_RATIONALITY);
        }

        @Test
        @DisplayName("the threshold is applied, and is a fraction of the strike amount")
        void theThresholdIsAppliedAndIsAFractionOfTheStrike() {
            // The decisive case, and the reason the threshold is mandatory input rather
            // than a default. At an 8.5% prevailing yield the continuation value at year
            // five is 1,019,703.21 — 19,703.21 in the money on a 1,000,000 strike, so
            // 1.97%. Frictionless exercise calls the bond; a 2% threshold, which requires
            // 1,020,000, does not, by 296.79.
            //
            // One market view, two thresholds, opposite lives. That is only possible if the
            // threshold is genuinely consulted and genuinely scaled by the strike amount:
            // an implementation that ignored it, or that compared it against the par
            // balance instead, would return the same life twice.
            ExpectedLifeDetermination frictionless = OptionalityResolver.resolve(
                callableBond(), bullet(10), PREMIUM, solver, marketAt("0.085", "0"));
            ExpectedLifeDetermination withFriction = OptionalityResolver.resolve(
                callableBond(), bullet(10), PREMIUM, solver, marketAt("0.085", "0.02"));

            assertThat(costed(frictionless, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(5);
            assertThat(costed(withFriction, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(10);
        }

        @Test
        @DisplayName("a put's moneyness test runs the other way, and the option type fixes the direction")
        void aPutsMoneynessTestRunsTheOtherWay() {
            // A put is exercised where the instrument trades *below* the strike: the holder
            // collects more by putting than by holding. At a 12% yield the continuation
            // value at the year-three put is 863,087.30 against a 1,000,000 strike, so the
            // put is 13.7% in the money and the life is three periods at 7.091554%
            // (docs 09 § 6 O4). At 6% the continuation value is 1,167,471.44 and putting
            // would be destroying value, so the life runs to maturity.
            //
            // The two market views are the same two that reverse the *call* decision above,
            // and they reverse it in the opposite order. A sign inversion in the moneyness
            // test would swap exactly these two answers and nothing else.
            OptionSchedule puttable = schedule(ExercisePolicy.ECONOMIC_RATIONALITY,
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PUT,
                    OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(3)));

            ExpectedLifeDetermination cheapMarket = OptionalityResolver.resolve(
                tenYearBond(puttable), bullet(10), PREMIUM, solver, marketAt("0.12", "0.02"));
            ExpectedLifeDetermination richMarket = OptionalityResolver.resolve(
                tenYearBond(puttable), bullet(10), PREMIUM, solver, marketAt("0.06", "0.02"));

            assertThat(costed(cheapMarket, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(3);
            assertThat(costed(cheapMarket, ExercisePolicy.ECONOMIC_RATIONALITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.070915538660"));
            assertThat(costed(richMarket, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(10);
        }

        @Test
        @DisplayName("an extension option makes the model unavailable rather than being ignored")
        void anExtensionOptionScopesTheModelOut() {
            // Rational exercise of an extension depends on the forward curve at the
            // extension date, not on a yield prevailing today, and a single scalar cannot
            // express it. Refusing says so. Silently dropping the extension would return a
            // life that looks like it priced the option and did not — which is the more
            // misleading of the two available errors, because the output is a plausible
            // number with a model's authority behind it.
            ScheduleBlueprint extendable = bond(5, new PrincipalProfile.BulletAtMaturity(),
                new RateProfile.Fixed(NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.ECONOMIC_RATIONALITY,
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                        OptionSchedule.OptionHolder.EITHER, annualDue(5))), 8);

            assertThatThrownBy(() -> OptionalityResolver.resolve(
                extendable, bullet(5), PREMIUM, solver, marketAt("0.06", "0.02")))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("an EXTENSION option is present")
                .hasMessageContaining("forward curve at the extension date")
                .hasMessageContaining("scoped to redemption options");

            assertThat(OptionalityResolver.applicablePolicies(
                extendable, bullet(5), marketAt("0.06", "0.02")))
                .doesNotContain(ExercisePolicy.ECONOMIC_RATIONALITY);
        }

        @Test
        @DisplayName("options are scanned in date order and an out-of-the-money one is skipped, not fatal")
        void optionsAreScannedInDateOrderAndOutOfTheMoneyOnesAreSkipped() {
            // Two calls, and the earlier one is out of the money. At a 6% prevailing yield
            // the continuation value at year four is 1,147,519.73 against a 1,160,000 strike
            // — 116 is expensive even in a rallying market — so that option is not
            // exercised. The year-six call struck at par has a continuation value of
            // 1,103,953.17 against 1,000,000, 10.4% in the money and past the 2% threshold,
            // so it is. Life six, at 7.921013%.
            //
            // Three separate defects change this answer. Returning on the first option
            // regardless of moneyness gives life four. Abandoning the scan at the first
            // option that is not exercised gives life ten via the no-exercise fallback.
            // Sorting by anything other than first-exercise date — list order, say, which
            // here puts the year-six call first — also gives six but for the wrong reason,
            // which is why the list order below is deliberately the reverse of the date
            // order.
            //
            // Not asserted, and deliberately so: that only the *first* exercise date of
            // each option is tested rather than its whole window. Under a single prevailing
            // yield and a fixed strike the sign of moneyness cannot change across a
            // window — the price walks monotonically toward par as maturity approaches — so
            // no input to this model distinguishes a first-date test from a window scan.
            // An assertion here could not fail and would read as coverage of a property it
            // does not test.
            ScheduleBlueprint twoCalls = tenYearBond(
                schedule(ExercisePolicy.ECONOMIC_RATIONALITY,
                    parCall(6),
                    option(OptionSchedule.OptionType.CALL,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, 4, 4, "1.16")));

            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                twoCalls, bullet(10), PREMIUM, solver, marketAt("0.06", "0.02"));

            assertThat(costed(determination, ExercisePolicy.ECONOMIC_RATIONALITY).lifePeriods())
                .isEqualTo(6);
            assertThat(costed(determination, ExercisePolicy.ECONOMIC_RATIONALITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.079210133984"));
            // The earliest-exercise policy, by contrast, does not price the option at all:
            // it truncates at the earliest date the premium can be taken away, which is the
            // year-four call whatever it costs to exercise.
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL).lifePeriods())
                .isEqualTo(4);
        }
    }

    // ============================================== 9. extension options — docs 09 § 3.3

    @Nested
    @DisplayName("an extension lengthens the term to the ACPIR 46(1) maximum (O5, docs 09 § 3.3)")
    class ExtensionOptions {

        /** O5: five years stated, extendable by three, ECL horizon eight. */
        private ScheduleBlueprint extendableBond(RateProfile rate, int eclHorizonPeriods) {
            return bond(5, new PrincipalProfile.BulletAtMaturity(), rate,
                schedule(ExercisePolicy.EARLIEST_CALL,
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                        OptionSchedule.OptionHolder.EITHER, annualDue(5))),
                eclHorizonPeriods);
        }

        @Test
        @DisplayName("O5 — a five-year bond extendable by three: 7.755768% stated against 8.125769% extended")
        void fixtureO5ExtensionOption() {
            // docs 09 § 6 O5. Exercising an extension *lengthens* the term, so the
            // earliest-exercise policy resolves to eight periods rather than to a
            // truncation, and the premium amortises over eight years instead of five:
            // 8.125769% against 7.755768%, 37.0 bp apart.
            //
            // Note which two numbers ST-8 is comparing. The extended life is read from the
            // ACPIR 46(1) horizon, which that regulation defines as the maximum contractual
            // period *including* extension options — so on an extendable instrument the
            // expected life under this policy and the horizon are the same number, and
            // ST-8's boundary case is the ordinary case.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                extendableBond(new RateProfile.Fixed(NINE_PERCENT_ANNUAL), 8),
                bullet(5), PREMIUM, solver);

            assertThat(determination.chosenLifePeriods()).isEqualTo(8);
            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).lifePeriods())
                .isEqualTo(5);
            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.077557682116"));
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.081257690197"));
            // 37.0 bp: (0.081257690197 - 0.077557682116) x 10,000.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("37.00008081"));
            assertThat(resultFor(determination, InvariantId.ST_8).satisfied()).isTrue();
        }

        @Test
        @DisplayName("the extended ladder rolls due dates on the calendar's own step and keeps the balance outstanding")
        void theExtendedLadderRollsOnTheCalendarStep() {
            // Three periods appear past stated maturity, dated on the annual step from the
            // last contractual due date, and the stated-maturity rung gives up its
            // principal repayment while keeping its billed interest. The balance stays
            // outstanding at 1,000,000 through the extension, which is what makes the
            // interest on those periods 90,000 rather than nothing.
            ScheduleBlueprint blueprint =
                extendableBond(new RateProfile.Fixed(NINE_PERCENT_ANNUAL), 8);
            ExpectedLifeDetermination determination =
                OptionalityResolver.resolve(blueprint, bullet(5), PREMIUM, solver);
            InstalmentLadder shaped =
                OptionalityResolver.ladderUnder(blueprint, bullet(5), determination);

            assertThat(shaped.rungs()).hasSize(8);
            assertThat(shaped.rungs()).extracting(InstalmentLadder.Rung::dueOn)
                .containsExactly(annualDue(1), annualDue(2), annualDue(3), annualDue(4),
                    annualDue(5), annualDue(6), annualDue(7), annualDue(8));

            // Stated maturity: interest still billed, principal no longer repaid there.
            assertThat(shaped.rung(5).interest().amount()).isEqualByComparingTo(bd("90000"));
            assertThat(shaped.rung(5).principal().isZero()).isTrue();
            assertThat(shaped.rung(5).balanceAfter().amount()).isEqualByComparingTo(bd("1000000"));
            // Extended maturity: the principal, once.
            assertThat(shaped.rung(8).principal().amount()).isEqualByComparingTo(bd("1000000"));
            assertThat(shaped.rung(8).total().amount()).isEqualByComparingTo(bd("1090000"));
            assertThat(shaped.rung(8).balanceAfter().isZero()).isTrue();
            // ST-3 over the extended ladder: 1,000,000 repaid once, against 1,000,000
            // advanced. Repaying it at both maturities would double it and fail here.
            assertThat(shaped.allSatisfied()).isTrue();
            assertThat(shaped.totalInterest().amount()).isEqualByComparingTo(bd("720000"));
        }

        @Test
        @DisplayName("a coupon that steps during the extension is honoured, not copied from the last billed rung")
        void aCouponSteppingDuringTheExtensionIsHonoured() {
            // Interest over the extended periods comes from the rate profile for those
            // periods. The alternative — copying the last contractual rung's interest —
            // looks harmless on a flat coupon and is wrong on a step-up, which is precisely
            // the structure an extension option tends to accompany.
            //
            // 9% to period five and 11% from period six: the extended flows are 90,000 in
            // years one to five, 110,000 in years six and seven, and 110,000 plus
            // 1,000,000 in year eight. Against 1,050,000 that yields 8.705624%. Copying the
            // last billed rung would bill 90,000 through the extension and yield 8.125769%
            // — 58.0 bp lower, and plausible enough to survive review.
            RateProfile stepped = new RateProfile.StepCoupon(List.of(
                new RateProfile.CouponStep(1, NINE_PERCENT_ANNUAL),
                new RateProfile.CouponStep(6, Rate.annualEffective(bd("0.11")))));
            ScheduleBlueprint blueprint = extendableBond(stepped, 8);

            ExpectedLifeDetermination determination =
                OptionalityResolver.resolve(blueprint, bullet(5), PREMIUM, solver);
            assertThat(costed(determination, ExercisePolicy.EARLIEST_CALL)
                .eir().effectiveAnnual()).isEqualByComparingTo(bd("0.087056243033"));

            InstalmentLadder shaped =
                OptionalityResolver.ladderUnder(blueprint, bullet(5), determination);
            assertThat(shaped.rung(5).interest().amount()).isEqualByComparingTo(bd("90000"));
            assertThat(shaped.rung(6).interest().amount()).isEqualByComparingTo(bd("110000"));
            assertThat(shaped.rung(7).interest().amount()).isEqualByComparingTo(bd("110000"));
            assertThat(shaped.rung(8).interest().amount()).isEqualByComparingTo(bd("110000"));
        }

        @Test
        @DisplayName("an extended life that does not lengthen the term is declined, naming both numbers")
        void anUnderstatedHorizonRemovesTheAlternativeRatherThanInventingALife() {
            // The extended life defaults to the ACPIR 46(1) horizon because that horizon
            // *is* the maximum contractual period including extensions — the same
            // contractual fact, read from the field that already records it. Where the
            // horizon has been capped by policy for some other reason it understates the
            // term, and the resolver must not paper over that by inventing a length. It
            // declines and names both numbers, which is what turns the failure into a
            // data-quality finding.
            assertThatThrownBy(() -> OptionalityResolver.resolve(
                extendableBond(new RateProfile.Fixed(NINE_PERCENT_ANNUAL), 5),
                bullet(5), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("the extension option would run to period 5, which does not"
                    + " lengthen the 5-period stated term")
                .hasMessageContaining("ACPIR 46(1) horizon it defaults to is understated");

            // Supplied explicitly, the alternative comes back — and ST-8 then records that
            // it sits outside the capped horizon rather than the resolver clamping it. A
            // clamp would produce a life that satisfies every check and matches no
            // contract.
            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                extendableBond(new RateProfile.Fixed(NINE_PERCENT_ANNUAL), 5),
                bullet(5), PREMIUM, solver, OptionalityJudgements.none().withExtendedLife(8));

            assertThat(determination.chosenLifePeriods()).isEqualTo(8);
            InvariantResult st8 = resultFor(determination, InvariantId.ST_8);
            assertThat(st8.satisfied()).isFalse();
            assertThat(st8.detail()).contains("expected life 8 exceeds the ECL horizon 5");
            assertThat(st8.deviation()).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("extending an amortising ladder is refused where that policy is the recorded one")
        void extendingAnAmortisingLadderIsRefusedWhenChosen() {
            // Extending an amortising ladder means re-solving the instalment over the
            // longer term, which changes every rung including the ones already billed. That
            // is schedule construction, not optionality, and doing it here would put two
            // amortisation formulas in the codebase and let them drift. The refusal names
            // the offending period and its principal, so the reader can see why the ladder
            // is not a bullet.
            ScheduleBlueprint amortisingExtendable = bond(5, new PrincipalProfile.EqualPrincipal(),
                new RateProfile.Fixed(NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.EARLIEST_CALL,
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                        OptionSchedule.OptionHolder.EITHER, annualDue(5))), 8);

            assertThatThrownBy(() -> OptionalityResolver.resolve(
                amortisingExtendable, equalPrincipal(), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("period 1 repays")
                .hasMessageContaining("this ladder amortises before maturity")
                .hasMessageContaining("belongs to schedule construction");
        }

        @Test
        @DisplayName("an unchosen extension that cannot be shaped must not abort a computable determination")
        void anUnshapableUnchosenPolicyMustNotAbortTheDetermination() {
            // The instrument is ordinary: a five-year equal-principal term loan that either
            // party may extend, amortised over contractual maturity — the RBI Investment
            // Directions reading, and the recorded policy here. CONTRACTUAL_MATURITY needs
            // no reshaping at all: its life is the stated life, so the contractual ladder
            // passes through untouched and its rate is simply the yield of the billed
            // flows.
            //
            // EARLIEST_CALL is a different matter. The extension is the only exercise
            // available, extending an amortising ladder is out of scope, and so that
            // alternative cannot be costed. Per OptionalityUnresolvedException's own
            // javadoc that is a finding and not an error: "An *unchosen* policy that cannot
            // be evaluated does not raise this. It is simply left out of the alternatives,
            // and invariant ST-7 then records that fewer than two policies were costed."
            //
            // Today resolve() throws instead, so the bank gets no EIR for an instrument
            // whose recorded policy is computable and whose contractual leg is not in
            // question.
            ScheduleBlueprint amortisingExtendable = bond(5, new PrincipalProfile.EqualPrincipal(),
                new RateProfile.Fixed(NINE_PERCENT_ANNUAL),
                schedule(ExercisePolicy.CONTRACTUAL_MATURITY,
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                        OptionSchedule.OptionHolder.EITHER, annualDue(5))), 8);

            ExpectedLifeDetermination determination = OptionalityResolver.resolve(
                amortisingExtendable, equalPrincipal(), PREMIUM, solver);

            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);
            assertThat(determination.alternatives())
                .extracting(ExpectedLifeDetermination.LifeAlternative::policy)
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY);
            // The divergence was not quantified, and that is the finding to record.
            assertThat(resultFor(determination, InvariantId.ST_7).satisfied()).isFalse();
        }
    }

    // ============================== 10. configuration failures the resolver surfaces

    @Nested
    @DisplayName("a configuration defect is surfaced where its cause is still visible")
    class ConfigurationFailures {

        @Test
        @DisplayName("a zero carrying amount is refused rather than solved to nothing")
        void aZeroCarryingAmountIsRefused() {
            // Every policy's vector would discount to a target of nothing, every solve
            // would report no sign change, and the determination would come back empty for
            // a reason that has nothing to do with optionality. Raising here keeps the cause
            // attached to the failure instead of handing the exception queue six identical
            // no-solution diagnostics.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityResolver.resolve(
                    tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL, parCall(5))),
                    bullet(10), Money.zero(Money.INR), solver))
                .withMessageContaining("the initial carrying amount is zero")
                .withMessageContaining("no rate is determined by any of them");
        }

        @Test
        @DisplayName("a ladder or a carrying amount in the wrong currency is refused, naming both codes")
        void aCurrencyMismatchIsRefused() {
            // Two separate checks because they fail on different inputs, and a message that
            // named only "currency mismatch" would leave the reader to work out which of
            // the three currencies in play was the odd one.
            ScheduleBlueprint blueprint =
                tenYearBond(schedule(ExercisePolicy.EARLIEST_CALL, parCall(5)));
            Currency usd = Currency.getInstance("USD");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityResolver.resolve(
                    blueprint, bullet(10), Money.of("1050000", usd), solver))
                .withMessageContaining("the initial carrying amount is USD but the blueprint is INR");

            List<InstalmentLadder.Rung> dollarRungs = List.of(new InstalmentLadder.Rung(
                1, annualDue(1), Money.of("1000000", usd), Money.zero(usd),
                Money.of("1000000", usd), Money.zero(usd)));
            InstalmentLadder dollarLadder = InstalmentLadder.of(
                usd, dollarRungs, Money.of("1000000", usd), Money.zero(usd));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityResolver.resolve(
                    blueprint, dollarLadder, PREMIUM, solver))
                .withMessageContaining("the ladder is USD but the blueprint is INR");
        }

        @Test
        @DisplayName("a calendar and a rate that disagree about the compounding period name the contract")
        void aCalendarAndRateFrequencyMismatchNamesTheContract() {
            // An annual calendar with a monthly-quoted contractual rate is a configuration
            // defect, and the seed is merely where it surfaces. The check is explicit rather
            // than left to ConventionSelector.rateUnder so that the failure arrives as
            // "the calendar and the rate disagree about the compounding period" and not as
            // a units complaint from inside a numeric helper several frames away, with
            // nothing left to say which contract produced it.
            //
            // The assertion is therefore on the *type* as much as on the message: an
            // IllegalArgumentException from the numeric layer would satisfy a message-only
            // test and would be the defect this check exists to prevent.
            ScheduleBlueprint mismatched = bond(10, new PrincipalProfile.BulletAtMaturity(),
                new RateProfile.Fixed(Rate.monthly(bd("0.0075"))),
                schedule(ExercisePolicy.EARLIEST_CALL, parCall(5)), 10);

            assertThatThrownBy(() -> OptionalityResolver.resolve(
                mismatched, bullet(10), PREMIUM, solver))
                .isInstanceOf(OptionalityUnresolvedException.class)
                .hasMessageContaining("the schedule compounds x1 on a ANNUAL calendar but the"
                    + " contractual rate is quoted x12")
                .hasMessageContaining("the calendar and the rate disagree about the compounding"
                    + " period");
        }

        @Test
        @DisplayName("contractual maturity is always applicable, even with nothing to exercise")
        void contractualMaturityIsAlwaysApplicable() {
            // Ignoring the options is computable on every instrument, which is what makes
            // it the reference basis the divergence is measured against — and what makes a
            // single-alternative determination on an optioned instrument mean "every
            // option-driven policy was unavailable" rather than "the engine gave up". On a
            // vanilla instrument it is the only coherent policy and ST-7 does not fire.
            ScheduleBlueprint vanilla = tenYearBond(OptionSchedule.none());

            assertThat(OptionalityResolver.applicablePolicies(
                vanilla, bullet(10), OptionalityJudgements.none()))
                .containsExactly(ExercisePolicy.CONTRACTUAL_MATURITY);

            ExpectedLifeDetermination determination =
                OptionalityResolver.resolve(vanilla, bullet(10), PREMIUM, solver);
            assertThat(determination.alternatives()).hasSize(1);
            assertThat(determination.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.ST_8);
            assertThat(determination.allSatisfied()).isTrue();
        }
    }

    // ==================================== 11. the option schedule and its policy enum

    @Nested
    @DisplayName("the option schedule refuses a policy the instrument cannot support")
    class TheOptionScheduleAndItsPolicies {

        @Test
        @DisplayName("none() is the vanilla instrument: no options, contractual maturity")
        void noneIsTheVanillaInstrument() {
            assertThat(OptionSchedule.none().isEmpty()).isTrue();
            assertThat(OptionSchedule.none().exercisePolicy())
                .isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(OptionSchedule.none().earliestExercise()).isEmpty();
            assertThat(OptionSchedule.none().impliesFairValueThroughProfitOrLoss()).isFalse();
        }

        @Test
        @DisplayName("an instrument with no options cannot record a policy that assumes exercise")
        void anEmptyScheduleCannotRecordAnExercisePolicy() {
            // An instrument with nothing to exercise has one coherent reading of expected
            // life. Accepting EARLIEST_CALL on it would leave a recorded policy that no
            // computation could honour, and the resolver would then have to choose between
            // failing on a contract that is not defective and silently substituting
            // contractual maturity.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new OptionSchedule(List.of(), ExercisePolicy.EARLIEST_CALL))
                .withMessageContaining("an instrument with no options has nothing to exercise")
                .withMessageContaining("got EARLIEST_CALL");
        }

        @Test
        @DisplayName("an exercise window that ends before it opens, or a non-positive strike, is refused")
        void anIncoherentEmbeddedOptionIsRefused() {
            // Both guards protect a truncation that would otherwise be silently wrong: an
            // inverted window makes strikeAt's containment test unsatisfiable, so every
            // judged redemption inside the intended window would quietly fall back to par,
            // and a zero or negative strike would produce a redemption of nothing or a
            // negative premium booked as interest.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new OptionSchedule.EmbeddedOption(
                    OptionSchedule.OptionType.CALL, OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    annualDue(7), annualDue(5), BigDecimal.ONE, false))
                .withMessageContaining("lastExercise 2031-04-01 precedes firstExercise 2033-04-01");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new OptionSchedule.EmbeddedOption(
                    OptionSchedule.OptionType.CALL, OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    annualDue(5), annualDue(5), BigDecimal.ZERO, false))
                .withMessageContaining("strikePctOfPar must be positive, got 0");
        }

        @Test
        @DisplayName("the schedule reports its earliest exercise and its options by type")
        void theScheduleReportsItsEarliestExerciseAndItsTypes() {
            // Both are read by the resolver, and both are order-independent: earliest is
            // the minimum date and not the head of the list, and ofType filters rather than
            // assuming one option per type. An extension listed after a call must still be
            // found.
            OptionSchedule mixed = schedule(ExercisePolicy.EARLIEST_CALL,
                parCall(7),
                OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.EXTENSION,
                    OptionSchedule.OptionHolder.EITHER, annualDue(10)),
                parCall(4));

            assertThat(mixed.earliestExercise()).contains(annualDue(4));
            assertThat(mixed.ofType(OptionSchedule.OptionType.CALL)).hasSize(2);
            assertThat(mixed.ofType(OptionSchedule.OptionType.EXTENSION)).hasSize(1);
            assertThat(mixed.ofType(OptionSchedule.OptionType.PUT)).isEmpty();
        }

        @Test
        @DisplayName("the policy enum flags which readings need an option and which are models")
        void thePolicyEnumFlagsItsOwnRequirements() {
            // isModelDriven is what routes a policy into ACPIR Chapter V governance —
            // inventory, tiering, documentation and independent validation before
            // production use. Getting it wrong in either direction is a compliance failure
            // rather than an arithmetic one: a model used without validation, or a
            // judgement burdened with a validation it does not need.
            assertThat(ExercisePolicy.CONTRACTUAL_MATURITY.requiresOptions()).isFalse();
            assertThat(ExercisePolicy.EARLIEST_CALL.requiresOptions()).isTrue();

            assertThat(ExercisePolicy.PROBABILITY_WEIGHTED.isModelDriven()).isTrue();
            assertThat(ExercisePolicy.ECONOMIC_RATIONALITY.isModelDriven()).isTrue();
            assertThat(ExercisePolicy.MOST_LIKELY_OUTCOME.isModelDriven()).isFalse();
            assertThat(ExercisePolicy.CONTRACTUAL_MATURITY.isModelDriven()).isFalse();
            assertThat(ExercisePolicy.EARLIEST_CALL.isModelDriven()).isFalse();
            assertThat(ExercisePolicy.NEXT_REPRICING.isModelDriven()).isFalse();
        }

        @Test
        @DisplayName("the B5.4.4 election is available on a floating instrument with no embedded option")
        void theRepricingElectionDoesNotRequireAnEmbeddedOption() {
            // Reference case 8's instrument: floating, no options, the shortcut elected per
            // product so that no unamortised fee is carried across the reset. Nothing about
            // that instrument involves an exercise decision, and requiring one forces a
            // fictitious option into the data.
            OptionSchedule election =
                new OptionSchedule(List.of(), ExercisePolicy.NEXT_REPRICING);

            assertThat(election.isEmpty()).isTrue();
            assertThat(election.exercisePolicy()).isEqualTo(ExercisePolicy.NEXT_REPRICING);
            // And with no option present the instrument is not optioned, so ST-7 has no
            // divergence to demand be quantified.
            assertThat(ExercisePolicy.NEXT_REPRICING.requiresOptions()).isFalse();
        }
    }

    // ============================================ 12. the judgements record's own rules

    @Nested
    @DisplayName("the judgements record refuses an input that is not the judgement it claims to be")
    class TheJudgementsRecord {

        @Test
        @DisplayName("weights that do not sum to exactly one are refused rather than normalised")
        void weightsMustSumToExactlyOne() {
            // Normalising here would silently restate a management judgement: a distribution
            // handed in as 40/50 is either a typo or a missing outcome, and scaling it to
            // 44.4/55.6 answers a question nobody asked while destroying the evidence that
            // the input was wrong. Exact equality, not a tolerance, because a weight vector
            // is authored by hand and any drift is an error rather than arithmetic noise.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityJudgements.probabilityWeighted(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.50")))))
                .withMessageContaining("must sum to exactly 1, got 0.90")
                .withMessageContaining("normalising it here would silently restate a management"
                    + " judgement");

            // And a probability outside (0, 1] is not a weight at all.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new OptionalityJudgements.WeightedOutcome(
                    annualDue(5), BigDecimal.ONE, bd("1.5")))
                .withMessageContaining("probability must lie in (0, 1], got 1.5");
        }

        @Test
        @DisplayName("outcomes must be in strictly ascending date order, for replay and not for tidiness")
        void outcomesMustBeInAscendingDateOrder() {
            // The resolver sums weighted flows, and BigDecimal addition at 28 digits is not
            // associative in the last digit. Two runs handed the same outcomes in a
            // different order would publish rates differing at the twelfth decimal place —
            // a DT-1 replay failure produced by nothing but iteration order. Ordering the
            // input is how that is made impossible rather than unlikely.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityJudgements.probabilityWeighted(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(10), bd("0.60")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")))))
                .withMessageContaining("must be in strictly ascending date order")
                .withMessageContaining("2031-04-01 does not follow 2036-04-01");

            // Two outcomes on the same date are refused for the same reason: they are one
            // outcome whose weight was written twice.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityJudgements.probabilityWeighted(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.40")),
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), bd("0.60")))))
                .withMessageContaining("strictly ascending date order");
        }

        @Test
        @DisplayName("a negative exercise threshold is not rationality under any reading")
        void aNegativeExerciseThresholdIsRefused() {
            // It would assume exercise while the option is out of the money. Zero is
            // permitted and is a strong input rather than a neutral one — frictionless
            // exercise calls every premium bond on its first call date — so the boundary is
            // asserted in both directions.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityJudgements.MarketView.of(
                    Rate.annualEffective(bd("0.06")), bd("-0.01")))
                .withMessageContaining("exerciseThreshold must be non-negative")
                .withMessageContaining("not rationality under any reading");

            assertThat(OptionalityJudgements.MarketView
                .frictionless(Rate.annualEffective(bd("0.06"))).exerciseThreshold())
                .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the extended life falls back to the horizon supplied, and an explicit value wins")
        void theExtendedLifeFallsBackToTheHorizon() {
            // The fallback reads a contractual fact — how long the term can run once an
            // extension is exercised — from the field that already records it, which is not
            // the same thing as deriving expected life from the ECL horizon. docs 09 § 3.3
            // forbids that derivation in both directions, and the guard against it is that
            // an explicit extended life overrides the fallback rather than being merged
            // with it.
            assertThat(OptionalityJudgements.none().extendedLifeOr(8)).isEqualTo(8);
            assertThat(OptionalityJudgements.none().withExtendedLife(6).extendedLifeOr(8))
                .isEqualTo(6);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> OptionalityJudgements.none().withExtendedLife(0))
                .withMessageContaining("extendedLifePeriods must be >= 1, got 0");
        }

        @Test
        @DisplayName("none() states an absence, and each builder adds one judgement without disturbing the others")
        void noneStatesAnAbsenceAndTheBuildersCompose() {
            // none() is a real and common value: it is the position of a book that has not
            // yet taken the policy decisions, and it must be distinguishable from a
            // half-populated record. The builders are what let a portfolio supply a market
            // view without implicitly withdrawing a most-likely date, so each one is
            // asserted to leave the other two alone.
            OptionalityJudgements empty = OptionalityJudgements.none();
            assertThat(empty.hasMostLikelyRedemption()).isFalse();
            assertThat(empty.hasWeightedOutcomes()).isFalse();
            assertThat(empty.hasMarketView()).isFalse();

            OptionalityJudgements all = empty
                .withMostLikelyRedemption(annualDue(6))
                .withWeightedOutcomes(List.of(
                    OptionalityJudgements.WeightedOutcome.atPar(annualDue(5), BigDecimal.ONE)))
                .withMarketView(OptionalityJudgements.MarketView.frictionless(
                    Rate.annualEffective(bd("0.06"))))
                .withExtendedLife(8);

            assertThat(all.mostLikelyRedemption()).isEqualTo(annualDue(6));
            assertThat(all.weightedOutcomes()).hasSize(1);
            assertThat(all.marketView().prevailingMarketYield().effectiveAnnual())
                .isEqualByComparingTo(bd("0.06"));
            assertThat(all.extendedLifeOr(99)).isEqualTo(8);
        }
    }
}
