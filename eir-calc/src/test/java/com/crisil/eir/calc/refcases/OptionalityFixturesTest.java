package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.blueprint.BehaviouralAdjuster;
import com.crisil.eir.calc.projection.blueprint.BehaviouralOverlay;
import com.crisil.eir.calc.projection.blueprint.BlueprintProjection;
import com.crisil.eir.calc.projection.blueprint.BlueprintProjector;
import com.crisil.eir.calc.projection.blueprint.DisbursementProfile;
import com.crisil.eir.calc.projection.blueprint.ExercisePolicy;
import com.crisil.eir.calc.projection.blueprint.ExpectedLifeDetermination;
import com.crisil.eir.calc.projection.blueprint.InstalmentLadder;
import com.crisil.eir.calc.projection.blueprint.InterestServicing;
import com.crisil.eir.calc.projection.blueprint.Moratorium;
import com.crisil.eir.calc.projection.blueprint.OptionSchedule;
import com.crisil.eir.calc.projection.blueprint.OptionalityResolver;
import com.crisil.eir.calc.projection.blueprint.OptionalitySppiFailureException;
import com.crisil.eir.calc.projection.blueprint.PrincipalProfile;
import com.crisil.eir.calc.projection.blueprint.ProductTemplates;
import com.crisil.eir.calc.projection.blueprint.RateProfile;
import com.crisil.eir.calc.projection.blueprint.ScheduleBlueprint;
import com.crisil.eir.calc.projection.blueprint.ScheduleBuilder;
import com.crisil.eir.calc.projection.blueprint.ScheduleCalendar;
import com.crisil.eir.calc.projection.blueprint.TemplateBasis;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures O1 to O8 of {@code docs/09-cashflow-structures.md} § 6 — the
 * optionality and behavioural half of the blueprint work, as merge gates.
 *
 * <p>These sit in the reference-case package deliberately and carry the same
 * standing as the nine numbered cases. <b>Every figure asserted below is quoted
 * from docs 09 § 6, or is arithmetic stated in full in the comment that uses it, or
 * is labelled at its assertion as an engine figure pinned as a regression lock.</b>
 * A disagreement between the engine and a figure of the first two kinds is a defect
 * in the engine and never a licence to move the expected value: a silent edit to an
 * expected value is how a regression becomes a specification.
 *
 * <p>The third kind exists because docs 09 does not publish everything O7 is
 * measured against, and it is called out rather than dressed up as a document
 * figure. § 3.4's O7 table has two columns — unamortised at m24 and catch-up — so the
 * m24 pool balance of 485,832.21, the 47-month expected life it is read off, and the
 * premium and discount rows' rates are the engine's own output. They are pinned so
 * they cannot drift unnoticed; what they cannot do is show that today's value is
 * right, and the three assertions carrying them say so at the point of use. Publishing
 * those figures in docs 09 § 3.4 alongside the two columns it does publish would move
 * them into the first kind, and it should be done.
 *
 * <h2>The figures, and where each comes from</h2>
 *
 * <table border="1">
 *   <caption>What this class gates</caption>
 *   <tr><th>Fixture</th><th>Instrument</th><th>Headline</th><th>Source</th></tr>
 *   <tr><td>O1</td><td>9% ten-year bullet at a 1,050,000 <b>premium</b>, par call yr 5</td>
 *       <td>8.246545% / 7.755768%, 49.1 bp, 5,153.16 of year-1 income</td>
 *       <td>docs 09 § 6 O1, § 3.2</td></tr>
 *   <tr><td>O2</td><td>the same bond at a 950,000 <b>discount</b></td>
 *       <td>9.806992% / 10.330130%, 52.3 bp <b>the other way</b>, 4,969.81</td>
 *       <td>docs 09 § 6 O2, § 3.2</td></tr>
 *   <tr><td>O3</td><td>8% perpetual bought 1,020,000, first call yr 5</td>
 *       <td>7.505597% to earliest call against a 7.843137% running yield</td>
 *       <td>docs 09 § 6 O3</td></tr>
 *   <tr><td>O4</td><td>9% at 1,050,000, holder may <b>put</b> at par yr 3</td>
 *       <td>7.091554% / 8.246545%, 115.5 bp</td><td>docs 09 § 6 O4</td></tr>
 *   <tr><td>O5</td><td>5y stated, <b>extendable</b> by 3</td>
 *       <td>7.755768% / 8.125769%, 37.0 bp; ECL horizon 8y, expected life 5y</td>
 *       <td>docs 09 § 6 O5, § 3.3</td></tr>
 *   <tr><td>O6</td><td>5,000,000 mortgage, 240 months, CPR 0/8/15/25%</td>
 *       <td>9.533867% / 9.674777% / 9.785190% / 9.945464%</td>
 *       <td>docs 09 § 6 O6</td></tr>
 *   <tr><td>O7</td><td>securitisation note, CPR revised 10%→20% at m24</td>
 *       <td>catch-up −876.38 at a premium, +878.90 at a discount, <b>0.00 at par</b></td>
 *       <td>docs 09 § 6 O7, § 3.4</td></tr>
 *   <tr><td>O8</td><td>lease 1,000,000 at 11% over 36m, residual 200,000</td>
 *       <td>12.377404% / 10.784759% / 11.571890% against 11.571884% contractual</td>
 *       <td>docs 09 § 6 O8</td></tr>
 * </table>
 *
 * <h2>Why these eight and not one parameterised sweep</h2>
 *
 * <p>Each of them gates a <em>different</em> defect, and three of them gate a defect
 * that a single-direction fixture cannot detect at all.
 *
 * <p><b>O1 against O2 is the sign reversal.</b> One bond, one call, two purchase
 * prices, and the divergence between the two exercise policies changes sign. A
 * premium amortised over five years instead of ten is expensed about twice as fast,
 * so the call basis reports <em>less</em> income; a discount accreted over five
 * instead of ten is recognised faster, so the call basis reports <em>more</em>. An
 * implementation with the premium amortisation backwards produces two individually
 * plausible rates on each instrument and fails only on the ordering, which is why
 * both the magnitudes and the ordering are asserted here.
 *
 * <p><b>O8 is INV-2 in both directions</b> — the cheapest sign check in the engine.
 * A fee received records the asset below par so the EIR must exceed the contractual
 * rate; a cost paid records it above par so the EIR must fall below. Swap the sign
 * convention on integral postings anywhere upstream and all three rates stay
 * plausible while the ordering inverts.
 *
 * <p><b>O7 is the par test</b>, and it is the most useful single result in docs 09.
 * At par the catch-up is exactly zero for any revision of any assumption, so
 * catch-up processing must key on the unamortised premium or discount balance and
 * not on the fact that an assumption moved.
 *
 * <h2>Conventions</h2>
 *
 * <p>Rates are asserted as the effective annual figure to six places of percent,
 * which is how docs 09 § 6 quotes them, through
 * {@link ReferenceCaseFixtures#effectiveAnnualPercent}. On the annual bonds the
 * periodic rate <em>is</em> the effective annual rate — {@code (1+r)^1 − 1} is
 * {@code r} on {@link Precision}'s exact integer power path — so no annualisation
 * round trip stands between the solved value and the published one. Money is
 * asserted at presentation scale. Nothing here reads a clock: every date is derived
 * from one explicit value date.
 *
 * <p>Every fixture is projected through {@link BlueprintProjector}, so a figure that
 * moves because {@code ScheduleBuilder} built a different ladder fails on the ladder
 * assertions first and the rate assertions second. That ordering is deliberate: a
 * wrong rate with a right ladder and a right rate with a wrong ladder are different
 * defects and should not present identically.
 *
 * <h2>What does not reproduce</h2>
 *
 * <p>Everything here reproduces to the digit docs 09 § 6 publishes, <b>except the
 * three O7 rows' last paisa.</b> Those three tests are {@link Disabled}, with the
 * engine's figure stated alongside the document's on each, and they are disabled
 * rather than deleted and rather than weakened to pass — an expected value edited to
 * match the code is how a regression becomes a specification.
 *
 * <p>The cause is one decision, and it is worth stating once here because all three
 * share it. {@code FlowVectorAssembler} emits every flow at presentation scale,
 * because a note holder is paid in paise; docs 09 computed O7 over rung totals at
 * working precision. Over 47 rounded instalments that shifts the solved monthly rate
 * in its tenth significant figure, which carries into the month-24 balance as one
 * paisa. On unrounded flows this class's own arithmetic reproduces every O7 figure
 * exactly, so the engine is right about the instrument and the document is right
 * about its own arithmetic.
 *
 * <p>The <b>par</b> row is not merely a rounding disagreement, and it is the finding
 * to act on. Because the billed schedule does not reprice exactly at its coupon, a
 * note bought at exactly par carries 0.0089 of unamortised premium by month 24 —
 * instalment-rounding residue and nothing else — and
 * {@link BehaviouralAdjuster#reestimationIsAPnlEvent} compares at presentation scale,
 * where HALF_UP turns that into a paisa. So the screen reports a par instrument as
 * held away from par, ST-9 takes its away-from-par branch and passes vacuously, and
 * the engine posts a catch-up on a note that has nothing to catch up. That is exactly
 * the churn docs 09 § 3.4 designs the screen to prevent.
 */
class OptionalityFixturesTest {

    // ------------------------------------------------------------------ the frame

    /** The value date of every fixture here, and reference case 1's disbursement date. */
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** Face of the O1–O5 bond; also the O7 note's par and the O8 asset's cost. */
    private static final Money FACE = Money.inr("1000000");

    /** 9% annual, the O1/O2/O4/O5 coupon. Paid once a year, so periodsPerYear is 1. */
    private static final Rate NINE_PERCENT_ANNUAL = Rate.annualEffective(bd("0.09"));

    /** Bought above par: 1,050,000 for a 1,000,000 face — O1, O4, O5. */
    private static final Money PREMIUM_PRICE = Money.inr("1050000");

    /** Bought below par: 950,000 — O2, where the divergence reverses. */
    private static final Money DISCOUNT_PRICE = Money.inr("950000");

    /**
     * An annual calendar that licenses periodic indexing.
     *
     * <p>No business-day convention, no holidays, no bespoke dates, so every due
     * date lands on an exact anniversary of the value date and
     * {@link ConventionSelector} selects {@code PeriodicIndex(1)}. That keeps these
     * assertions about optionality rather than about day-count arithmetic, which
     * {@code ConventionSelectorTest} already owns — and it is also what makes ST-10
     * a pass here rather than a forced fall-back to actual dating.
     */
    private static final ScheduleCalendar ANNUAL = new ScheduleCalendar(
        ScheduleCalendar.Frequency.ANNUAL,
        ScheduleCalendar.BusinessDayConvention.NONE,
        Set.of(),
        ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
        List.of());

    /** The monthly equivalent, for O6, O7 and O8. */
    private static final ScheduleCalendar MONTHLY = new ScheduleCalendar(
        ScheduleCalendar.Frequency.MONTHLY,
        ScheduleCalendar.BusinessDayConvention.NONE,
        Set.of(),
        ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
        List.of());

    private static final RateSolver SOLVER = new BracketedNewtonSolver();

    /** The {@code k}-th anniversary of the value date — the due date of annual period k. */
    private static LocalDate annualDue(int period) {
        return VALUE_DATE.plusYears(period);
    }

    /** The due date of monthly period {@code k}. */
    private static LocalDate monthlyDue(int period) {
        return VALUE_DATE.plusMonths(period);
    }

    // ------------------------------------------------------------ pipeline seams

    /**
     * The EIR the caller solves over the expected leg the projector returned.
     *
     * <p>{@link SolveRequest#atInception} takes its target from the vector's own
     * inception leg, which is the same {@code −(net at inception)} that IC-1 compared
     * against the derived carrying amount. Solving against the vector rather than
     * against a figure the test supplies is what stops a fixture from passing on a
     * vector that failed IC-1.
     *
     * <p>The seed is the contractual rate restated in the selected convention's
     * units. The seam is mandatory rather than tidy: a contract quotes its rate in
     * the schedule's frequency, and pairing a monthly rate with a year fraction is an
     * annualisation round-trip in disguise.
     */
    private static Rate solvedEir(BlueprintProjection projection, Rate contractualRate) {
        TimeConvention convention = projection.projection().recommendedConvention();
        SolveResult solved = SOLVER.solve(SolveRequest.atInception(
            projection.projection().expected(),
            convention,
            ConventionSelector.rateUnder(convention, contractualRate).periodic()));
        assertThat(solved.isSolved())
            .as("solve status was %s: %s", solved.status(), solved.diagnostic())
            .isTrue();
        return solved.rateOrThrow();
    }

    /**
     * The schedule's par gap, derived as {@code TwoLegResult.reconcile} derives it: the
     * contractual leg's terminal balance discounted back over the accumulated tau. INV-2's
     * baseline, and non-zero here only by the rental-rounding residue — these schedules are
     * uniform and do price to par at their coupon.
     *
     * <p>Par is the amount advanced at inception, not the initial carrying amount: the
     * contractual leg rolls from the face principal and knows nothing of the fee.
     */
    private static Money parGapOf(BlueprintProjection projection, Rate contractualRate) {
        TimeConvention convention = projection.projection().recommendedConvention();
        Rate under = ConventionSelector.rateUnder(convention, contractualRate);
        AmortisationResult contractualLeg = AmortisationEngine.contractualLeg(
            projection.assembly().amountAdvancedAtInception(),
            under,
            projection.projection().contractual(),
            convention);
        return TwoLegResult.parGap(contractualLeg, under);
    }

    /** The EIR leg over the expected vector the rate was solved against. */
    private static AmortisationResult eirLeg(BlueprintProjection projection, Rate eir) {
        return AmortisationEngine.eirLeg(
            projection.assembly().initialCarryingAmount(),
            eir,
            projection.projection().expected(),
            projection.projection().recommendedConvention());
    }

    /**
     * TR-1 over a whole, event-free life: the terminal carrying amount is zero.
     *
     * <p>Zero because the rate was solved against the same vector the ledger
     * consumed, so the roll-forward is that discount run backwards. A non-zero
     * terminal balance is therefore never an arithmetic slip — it means the solve and
     * the roll-forward were given different flows.
     */
    private static void assertTerminalCarryingAmountIsZero(AmortisationResult leg) {
        assertThat(paise(leg.terminalBalance()))
            .as("terminal EIR-leg carrying amount — invariant TR-1")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(satisfied(leg.invariants(), InvariantId.TR_1))
            .as("TR-1 must be asserted, not merely happen to hold")
            .isTrue();
        assertThat(leg.isClean()).isTrue();
    }

    private static ExpectedLifeDetermination.LifeAlternative costed(
        ExpectedLifeDetermination determination, ExercisePolicy policy) {
        return determination.alternatives().stream()
            .filter(alternative -> alternative.policy() == policy)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "policy " + policy + " was not costed; alternatives were "
                    + determination.alternatives().stream()
                        .map(ExpectedLifeDetermination.LifeAlternative::policy).toList()));
    }

    private static InvariantResult resultFor(List<InvariantResult> results, InvariantId id) {
        return results.stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + id + " result was recorded"));
    }

    private static boolean satisfied(List<InvariantResult> results, InvariantId id) {
        return results.stream().anyMatch(result -> result.id() == id && result.satisfied());
    }

    // --------------------------------------------------------------- fee postings

    /**
     * A purchase premium: {@code price − face} paid at inception, integral to the EIR.
     *
     * <p>The premium or discount on a purchased security enters as an integral amount
     * at inception exactly as an origination cost does, so the carrying amount is the
     * price paid and not the face value. {@code OTHER} rather than {@code SELLING} is
     * the cost function: ACPIR 53's selling-versus-processing line is about
     * origination costs on a loan the bank writes, and a premium paid to a bond seller
     * is neither. The attribute is mandatory on an integral cost and the posting fails
     * rather than defaulting, which is why it has to be stated.
     */
    private static List<FeePosting> premiumPaid(Money price) {
        return List.of(FeePosting.paid("PURCHASE_PREMIUM", price.minus(FACE), VALUE_DATE,
            FeeClassification.INTEGRAL, "OTHER"));
    }

    /** A purchase discount: {@code face − price} received at inception, integral. */
    private static List<FeePosting> discountReceived(Money price) {
        return List.of(FeePosting.received("PURCHASE_DISCOUNT", FACE.minus(price), VALUE_DATE,
            FeeClassification.INTEGRAL));
    }

    // ============================================ O1 / O2 — the callable bond

    @Nested
    @DisplayName("O1 / O2 — one callable bond, two purchase prices, and the divergence reverses")
    class CallableBondBothDirections {

        /**
         * The docs 09 § 6 bond: face 1,000,000, 9% paid annually, principal in one
         * flow at the tenth anniversary, callable at par at year five.
         *
         * <p>Built through {@link ProductTemplates#callableCorporateBond} rather than
         * by hand, because the template is the ACPIR product matrix as executable code
         * and a family that was written down wrong should fail here rather than three
         * stages later on a live contract.
         */
        private ScheduleBlueprint callableBond() {
            return ProductTemplates.callableCorporateBond(
                TemplateBasis.of(VALUE_DATE, annualDue(10), ANNUAL,
                    DayCountConvention.THIRTY_360_BOND, 10),
                FACE,
                NINE_PERCENT_ANNUAL,
                annualDue(5),
                annualDue(5),
                BigDecimal.ONE,
                ExercisePolicy.EARLIEST_CALL,
                "issuer is expected to call at par at the first opportunity");
        }

        private BlueprintProjection project(List<FeePosting> postings) {
            return new BlueprintProjector(callableBond(), SOLVER).projectBlueprint(postings);
        }

        @Test
        @DisplayName("the contractual ladder is nine coupons of 90,000 and a tenth of 1,090,000")
        void theContractualLadderIsTheBondItself() {
            // Asserted before any rate is, because a rate solved over the wrong ladder is
            // wrong in a way that looks like a solver defect. Nine rungs of coupon alone on
            // a balance that never moves, then the coupon plus the whole face.
            InstalmentLadder ladder = ScheduleBuilder.build(callableBond());

            assertThat(ladder.length()).isEqualTo(10);
            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("90000.00"));
            assertThat(ladder.rung(1).principal().isZero())
                .as("a bullet repays no principal before maturity")
                .isTrue();
            assertThat(paise(ladder.rung(9).balanceAfter()))
                .as("the balance outstanding is the full face until the tenth period")
                .isEqualByComparingTo(bd("1000000.00"));
            assertThat(paise(ladder.rung(10).total())).isEqualByComparingTo(bd("1090000.00"));
            assertThat(paise(ladder.totalInterest()))
                .as("ten annual coupons of 90,000")
                .isEqualByComparingTo(bd("900000.00"));
            assertThat(ladder.terminalBalance().isZero())
                .as("ST-5 does not apply: a bullet amortises to zero, not to a terminal lump")
                .isTrue();
            // ST-3 over the ladder: 1,000,000 of principal repaid once against 1,000,000
            // advanced. Repaying it twice, or not at all, fails here.
            assertThat(satisfied(ladder.invariants(), InvariantId.ST_3)).isTrue();
            assertThat(ladder.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("O1 — premium 1,050,000: 8.246545% over ten years, 7.755768% over five, 49.1 bp apart")
        void fixtureO1PremiumBond() {
            // docs 09 § 6 O1 and § 3.2. To maturity the flows are 90,000 a year for ten
            // years plus 1,000,000 of principal, which discounts to 1,050,000 at 8.246545%;
            // truncated at the call they are 90,000 for five years plus 1,000,000, which
            // discounts to the same 1,050,000 at 7.755768%.
            BlueprintProjection projection = project(premiumPaid(PREMIUM_PRICE));

            assertThat(projection.assembly().initialCarryingAmount())
                .as("GCA at initial recognition is the price paid, not the face redeemed")
                .isEqualTo(PREMIUM_PRICE);
            assertThat(paise(projection.projection().netCashAtInception()))
                .as("IC-1's other route: 1,000,000 advanced plus a 50,000 premium paid")
                .isEqualByComparingTo(bd("-1050000.00"));
            assertThat(satisfied(projection.invariants(), InvariantId.IC_1)).isTrue();

            ExpectedLifeDetermination determination = projection.expectedLife();
            assertThat(determination.chosenPolicy()).isEqualTo(ExercisePolicy.EARLIEST_CALL);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);

            ExpectedLifeDetermination.LifeAlternative toMaturity =
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY);
            ExpectedLifeDetermination.LifeAlternative toCall =
                costed(determination, ExercisePolicy.EARLIEST_CALL);

            assertThat(toMaturity.lifePeriods()).isEqualTo(10);
            assertThat(effectiveAnnualPercent(toMaturity.eir()))
                .as("CONTRACTUAL_MATURITY — the RBI Investment Directions reading")
                .isEqualByComparingTo(bd("8.246545"));
            assertThat(toCall.lifePeriods()).isEqualTo(5);
            assertThat(effectiveAnnualPercent(toCall.eir()))
                .as("EARLIEST_CALL — the IFRS 9 comparison basis")
                .isEqualByComparingTo(bd("7.755768"));

            // Year-one income, docs 09 § 6 O1: 86,588.72 against 81,435.57. The figure a
            // controller actually compares, because 49.1 basis points is abstract and
            // 5,153.16 of year-one income is not.
            assertThat(paise(toMaturity.firstPeriodIncome())).isEqualByComparingTo(bd("86588.72"));
            assertThat(paise(toCall.firstPeriodIncome())).isEqualByComparingTo(bd("81435.57"));
        }

        @Test
        @DisplayName("O1 — the divergence is 49.1 bp and 5,153.16, the second reduced once and not twice")
        void fixtureO1QuantifiesTheDivergence() {
            ExpectedLifeDetermination determination =
                project(premiumPaid(PREMIUM_PRICE)).expectedLife();

            // 49.0777018 bp exactly: (0.082465452296 − 0.077557682116) x 10,000. docs 09
            // § 3.2 rounds it to 49.1, and both are asserted so that a change of a tenth of
            // a basis point cannot hide behind the published rounding.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("49.0777018"));
            assertThat(determination.widestDivergenceBps().setScale(1, Precision.MODE))
                .isEqualByComparingTo(bd("49.1"));

            // 5,153.16 of year-one income, and note that this is NOT the difference of the
            // two published figures. The working figures are 86,588.7249108 and
            // 81,435.5662218, whose difference 5,153.1586890 publishes as 5,153.16 — the
            // reduce-once rule of 03 § 1.3. Round both operands first and difference those,
            // and the answer is 5,153.15: a paisa away, and not the figure docs 09 § 3.2
            // states. Both arithmetics are asserted, because a reader who differences the
            // published table by hand will get the second one and needs to know why.
            Money toMaturityIncome =
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).firstPeriodIncome();
            Money toCallIncome =
                costed(determination, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome();

            assertThat(paise(toMaturityIncome.minus(toCallIncome)))
                .as("docs 09 § 3.2: the call basis reports 5,153.16 LESS at a premium")
                .isEqualByComparingTo(bd("5153.16"));
            assertThat(paise(toMaturityIncome).subtract(paise(toCallIncome)))
                .as("differencing the two published figures instead rounds twice and gives 5,153.15")
                .isEqualByComparingTo(bd("5153.15"));
        }

        @Test
        @DisplayName("O2 — the same bond at 950,000: 9.806992% over ten, 10.330130% over five, 52.3 bp")
        void fixtureO2DiscountBond() {
            // docs 09 § 6 O2. Identical bond, identical call, purchase price 950,000
            // instead of 1,050,000. The call basis now reports MORE income, where at a
            // premium it reported less.
            BlueprintProjection projection = project(discountReceived(DISCOUNT_PRICE));

            assertThat(projection.assembly().initialCarryingAmount()).isEqualTo(DISCOUNT_PRICE);
            assertThat(projection.assembly().netIntegralFee())
                .as("a discount is income at inception: the asset is recorded below par")
                .isEqualTo(Money.inr("50000"));

            ExpectedLifeDetermination determination = projection.expectedLife();
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).eir()))
                .isEqualByComparingTo(bd("9.806992"));
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.EARLIEST_CALL).eir()))
                .isEqualByComparingTo(bd("10.330130"));

            // 93,166.43 and 98,136.23 — docs 09 § 6 O2. Their working difference is
            // 4,969.8063700, which publishes as 4,969.81 and again is not the difference of
            // the two rounded figures (4,969.80).
            assertThat(paise(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                .firstPeriodIncome())).isEqualByComparingTo(bd("93166.43"));
            assertThat(paise(costed(determination, ExercisePolicy.EARLIEST_CALL)
                .firstPeriodIncome())).isEqualByComparingTo(bd("98136.23"));
            // 4,969.8063744 at working precision, publishing as 4,969.81; differencing the
            // two published figures would give 4,969.80, for the same reason as on O1.
            assertThat(paise(costed(determination, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome()
                .minus(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY)
                    .firstPeriodIncome())))
                .as("docs 09 § 3.2: the call basis reports 4,969.81 MORE at a discount")
                .isEqualByComparingTo(bd("4969.81"));

            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("52.31375131"));
            assertThat(determination.widestDivergenceBps().setScale(1, Precision.MODE))
                .isEqualByComparingTo(bd("52.3"));
        }

        @Test
        @DisplayName("the sign of the divergence reverses with the purchase price — that is the point")
        void theSignOfTheDivergenceReversesWithThePurchasePrice() {
            // The assertion the two fixtures exist to make, and the one neither can make
            // alone. An implementation that had the direction of premium amortisation
            // backwards would produce four individually plausible rates and fail only here.
            //
            // It is also the reason no blanket exercise policy can be assumed conservative:
            // the same policy that understates income on a premium bond overstates it on a
            // discount bond, so the choice cannot be made once and globally on prudence
            // grounds. It has to be made per portfolio with the number in front of the
            // committee, which is why the engine computes both bases rather than one.
            ExpectedLifeDetermination premium = project(premiumPaid(PREMIUM_PRICE)).expectedLife();
            ExpectedLifeDetermination discount =
                project(discountReceived(DISCOUNT_PRICE)).expectedLife();

            Money premiumCallIncome =
                costed(premium, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome();
            Money premiumMaturityIncome =
                costed(premium, ExercisePolicy.CONTRACTUAL_MATURITY).firstPeriodIncome();
            Money discountCallIncome =
                costed(discount, ExercisePolicy.EARLIEST_CALL).firstPeriodIncome();
            Money discountMaturityIncome =
                costed(discount, ExercisePolicy.CONTRACTUAL_MATURITY).firstPeriodIncome();

            assertThat(premiumCallIncome.amount())
                .as("a premium expensed over five years instead of ten leaves LESS income")
                .isLessThan(premiumMaturityIncome.amount());
            assertThat(discountCallIncome.amount())
                .as("a discount accreted over five instead of ten recognises MORE income")
                .isGreaterThan(discountMaturityIncome.amount());
            assertThat(costed(premium, ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual())
                .isLessThan(
                    costed(premium, ExercisePolicy.CONTRACTUAL_MATURITY).eir().effectiveAnnual());
            assertThat(costed(discount, ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual())
                .isGreaterThan(
                    costed(discount, ExercisePolicy.CONTRACTUAL_MATURITY).eir().effectiveAnnual());
        }

        @Test
        @DisplayName("ST-7 and ST-8: two policies costed and the divergence quantified, life inside the horizon")
        void st7AndSt8AreAssertedOnAnOptionedInstrument() {
            // ST-7 is the one that turns "the engine computed a life" into "the engine
            // quantified what the choice cost". A single-alternative determination on an
            // optioned instrument means the ACPIR-versus-IFRS 9 divergence was never
            // measured, which is the finding rather than the answer.
            BlueprintProjection projection = project(premiumPaid(PREMIUM_PRICE));
            ExpectedLifeDetermination determination = projection.expectedLife();

            assertThat(determination.alternatives()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(resultFor(determination.invariants(), InvariantId.ST_7).satisfied()).isTrue();
            assertThat(resultFor(determination.invariants(), InvariantId.ST_8).satisfied()).isTrue();
            assertThat(determination.allSatisfied()).isTrue();

            // ST-8 stated where it can fail. A `chosenLifePeriods <= eclHorizonPeriods`
            // line stood here: on this fixture the horizon is 10, the ladder is 10 periods
            // long and the only two costed alternatives are 5 (call) and 10 (maturity), so
            // there is no reachable input under which the inequality is false. It read as
            // ST-8 coverage while the actual gate was the engine's own result above it.
            //
            // The boundary ST-8 exists to police is the same bond with its horizon
            // understated to the five-year call while the recorded policy holds the
            // instrument to maturity — a plausible data error, since 46(1) asks for the
            // MAXIMUM contractual period and a call date is the one a trader quotes. The
            // ten-period life then sits five periods beyond a five-period horizon and the
            // invariant has to say so rather than clamp it. (O5's extendable bond states the
            // passing boundary, life == horizon, and it already does.)
            ExpectedLifeDetermination heldToMaturity = new BlueprintProjector(
                ProductTemplates.callableCorporateBond(
                    TemplateBasis.of(VALUE_DATE, annualDue(10), ANNUAL,
                        DayCountConvention.THIRTY_360_BOND, 5),
                    FACE, NINE_PERCENT_ANNUAL, annualDue(5), annualDue(5), BigDecimal.ONE,
                    ExercisePolicy.CONTRACTUAL_MATURITY,
                    "the issuer is not expected to call; the horizon was taken from the call date"),
                SOLVER)
                .projectBlueprint(premiumPaid(PREMIUM_PRICE))
                .expectedLife();

            assertThat(heldToMaturity.chosenLifePeriods()).isEqualTo(10);
            InvariantResult breached = resultFor(heldToMaturity.invariants(), InvariantId.ST_8);
            assertThat(breached.satisfied()).isFalse();
            assertThat(breached.detail())
                .contains("exceeds the ECL horizon 5")
                .contains("ACPIR 46(1)");
            // 10 - 5 = 5 periods of overrun, signed positive because the life is the longer.
            assertThat(breached.deviation()).isEqualByComparingTo(bd("5"));
            assertThat(heldToMaturity.allSatisfied()).isFalse();

            // ST-10 runs one way only: it forbids taking period ordinals as a measure of
            // time on a schedule whose periods are not equal. This calendar cannot move a
            // due date, so the periodic index is licensed and the invariant passes.
            assertThat(projection.conventionChoice().periodicIndexEligible()).isTrue();
            assertThat(satisfied(projection.invariants(), InvariantId.ST_10)).isTrue();
            assertThat(projection.allInvariantsSatisfied())
                .as("breaches: %s", projection.projection().invariants())
                .isTrue();
        }

        @Test
        @DisplayName("TR-1: the published rate amortises the premium to exactly zero over five periods")
        void theChosenPolicyAmortisesToZero() {
            // The published figure is the chosen policy's, so the roll-forward that has to
            // close is the one over the truncated vector. Five rungs: 90,000 four times and
            // 1,090,000 at the call, discounting to 1,050,000 at 7.755768%.
            BlueprintProjection projection = project(premiumPaid(PREMIUM_PRICE));
            Rate eir = solvedEir(projection, NINE_PERCENT_ANNUAL);

            assertThat(effectiveAnnualPercent(eir))
                .as("the projection-level solve reproduces the chosen policy's rate")
                .isEqualByComparingTo(bd("7.755768"));
            assertThat(eir.effectiveAnnual())
                .isEqualByComparingTo(costed(projection.expectedLife(),
                    ExercisePolicy.EARLIEST_CALL).eir().effectiveAnnual());

            AmortisationResult leg = eirLeg(projection, eir);
            assertThat(leg.periods()).isEqualTo(5);
            assertThat(paise(leg.openingGca())).isEqualByComparingTo(bd("1050000.00"));
            assertThat(paise(leg.row(1).eirInterest()))
                .as("year-one income under the call basis")
                .isEqualByComparingTo(bd("81435.57"));
            assertThat(paise(leg.row(5).cashReceived()))
                .as("the call redeems par and the last coupon in one period")
                .isEqualByComparingTo(bd("1090000.00"));
            assertTerminalCarryingAmountIsZero(leg);
        }
    }

    // ================================================ O3 — perpetual to first call

    @Nested
    @DisplayName("O3 — a perpetual amortised to its earliest call, and the gate that runs first")
    class PerpetualToFirstCall {

        /** 8% of face, paid annually. */
        private static final Rate EIGHT_PERCENT_ANNUAL = Rate.annualEffective(bd("0.08"));

        /** Bought at 1,020,000 for a 1,000,000 face — docs 09 § 6 O3. */
        private static final Money PRICE = Money.inr("1020000");

        /**
         * The running yield, {@code coupon / price} = {@code 80,000 / 1,020,000}.
         *
         * <p>Quoted by docs 09 § 6 O3 "for contrast" and taken from there as a
         * literal. It is deliberately <em>not</em> computed here: the engine has no
         * running-yield routine, so a test that divided the coupon by the price would
         * be asserting its own arithmetic against itself. What it is used for below is
         * the comparison that can fail — the EIR of a premium instrument must sit
         * below its running yield, because part of every coupon is repaying the
         * premium rather than earning a return.
         */
        private static final BigDecimal RUNNING_YIELD_PERCENT = bd("7.843137");

        /**
         * A perpetual has no stated maturity, so the ten-year horizon below is the
         * bank's policy stand-in for one.
         *
         * <p>It is a policy input and not a fact about the instrument, and it matters
         * for exactly one thing: it is the contractual-maturity alternative that the
         * earliest-call figure is measured against, so ST-7 has two policies to
         * compare and the divergence is quantified rather than asserted.
         */
        private ScheduleBlueprint perpetual() {
            return ProductTemplates.perpetualDebt(
                TemplateBasis.of(VALUE_DATE, annualDue(10), ANNUAL,
                    DayCountConvention.THIRTY_360_BOND, 10),
                FACE,
                EIGHT_PERCENT_ANNUAL,
                annualDue(5),
                "RBI Investment FAQ: a premium on a perpetual amortises to the earliest call");
        }

        @Test
        @DisplayName("the SPPI gate runs first: an AT1-style instrument has no EIR at all (ST-12)")
        void theSppiGateRunsBeforeAnyEirWorkIsCommissioned() {
            // docs 09 § 6 O3 says this in as many words, and it is the perimeter rather
            // than the mechanics that matters: an AT1-style instrument with discretionary
            // coupons and loss absorption generally fails SPPI, which sends the WHOLE
            // instrument to FVTPL where no effective interest rate exists. That is a cliff,
            // not a gradient — there is no reduced or provisional EIR on the far side of
            // it — so the only correct output is a refusal.
            //
            // The conversion feature is the loss-absorption term, and it is what takes the
            // instrument out of amortised cost. The gate is asserted twice, and both
            // assertions are made here: ScheduleBlueprint refuses the composition at
            // construction (ST-11) and the resolver asserts it again at the point a life
            // would otherwise be returned (ST-12).
            OptionSchedule at1Style = new OptionSchedule(
                List.of(
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CALL,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(5)),
                    OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.CONVERSION,
                        OptionSchedule.OptionHolder.ISSUER_BORROWER, annualDue(5))),
                ExercisePolicy.EARLIEST_CALL);

            assertThat(at1Style.impliesFairValueThroughProfitOrLoss()).isTrue();
            assertThatThrownBy(() -> OptionalityResolver.requireSppiPass(at1Style))
                .isInstanceOf(OptionalitySppiFailureException.class)
                .hasMessageContaining(InvariantId.ST_12.statement())
                .hasMessageContaining("fair value through profit or loss")
                .hasMessageContaining("No expected life is returned");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> perpetualWith(at1Style))
                .withMessageContaining("incoherent blueprint (ST-11)")
                .withMessageContaining("no EIR arises at all (ST-12)");

            // And the refusal is narrow. A plain call is an ordinary amortised-cost
            // feature, so the perpetual that passes the screen reaches the resolver.
            OptionalityResolver.requireSppiPass(perpetual().options());
        }

        @Test
        @DisplayName("O3 — EIR to earliest call 7.505597% p.a., against a 7.843137% running yield")
        void fixtureO3PerpetualToFirstCall() {
            // docs 09 § 6 O3. Flows to the earliest call are 80,000 a year for five years
            // plus 1,000,000 of par redemption, which discounts to 1,020,000 at 7.505597%.
            BlueprintProjection projection =
                new BlueprintProjector(perpetual(), SOLVER).projectBlueprint(premiumPaid(PRICE));

            assertThat(projection.assembly().initialCarryingAmount()).isEqualTo(PRICE);
            assertThat(paise(projection.contractualLadder().rung(1).total()))
                .as("8% of a 1,000,000 face, paid annually")
                .isEqualByComparingTo(bd("80000.00"));

            ExpectedLifeDetermination determination = projection.expectedLife();
            assertThat(determination.chosenPolicy())
                .as("the family is defined by its exercise policy in a way a callable bond is not")
                .isEqualTo(ExercisePolicy.EARLIEST_CALL);
            assertThat(determination.chosenLifePeriods()).isEqualTo(5);

            ExpectedLifeDetermination.LifeAlternative toCall =
                costed(determination, ExercisePolicy.EARLIEST_CALL);
            assertThat(effectiveAnnualPercent(toCall.eir()))
                .isEqualByComparingTo(bd("7.505597"));

            // Year-one income 76,557.09: the stored rate 0.075055970637 on the 1,020,000
            // carrying amount. Stated rather than recomputed, so a defect in the
            // roll-forward shows here and not only in AmortisationEngineTest.
            assertThat(paise(toCall.firstPeriodIncome())).isEqualByComparingTo(bd("76557.09"));

            // The contrast docs 09 draws. A bond bought above par yields less than its
            // coupon over price, because part of each 80,000 is repaying the 20,000 premium
            // rather than earning a return. An implementation that amortised the premium
            // the wrong way round would report a yield ABOVE the running yield here, which
            // is the one thing a premium instrument cannot do.
            assertThat(effectiveAnnualPercent(toCall.eir()))
                .as("7.505597% against a 7.843137% running yield")
                .isLessThan(RUNNING_YIELD_PERCENT);
        }

        @Test
        @DisplayName("the ten-year policy horizon gives ST-7 a second policy: 7.705883%, 20.0 bp away")
        void theStatedHorizonIsTheComparisonBasis() {
            // Independent arithmetic, stated because docs 09 § 6 does not tabulate it: at
            // the ten-year policy horizon the flows are 80,000 a year for ten years plus
            // 1,000,000, which discounts to 1,020,000 at 7.705883% — 0.077058830705 as
            // stored, against 0.075055970637 to the call, so 20.02860068 basis points.
            //
            // The horizon is a policy convention rather than a contractual fact, so what
            // this test gates is not the number itself but that a second policy is costed
            // at all. Without one the divergence is unquantified and ST-7 is the finding.
            ExpectedLifeDetermination determination =
                new BlueprintProjector(perpetual(), SOLVER)
                    .projectBlueprint(premiumPaid(PRICE))
                    .expectedLife();

            ExpectedLifeDetermination.LifeAlternative toHorizon =
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(toHorizon.lifePeriods()).isEqualTo(10);
            assertThat(effectiveAnnualPercent(toHorizon.eir()))
                .isEqualByComparingTo(bd("7.705883"));
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("20.02860068"));
            assertThat(determination.widestDivergenceBps().setScale(1, Precision.MODE))
                .isEqualByComparingTo(bd("20.0"));
            assertThat(resultFor(determination.invariants(), InvariantId.ST_7).satisfied()).isTrue();
            assertThat(resultFor(determination.invariants(), InvariantId.ST_8).satisfied()).isTrue();
        }

        @Test
        @DisplayName("TR-1: the premium amortises to exactly zero by the call date")
        void theEirLegClosesAtTheCall() {
            BlueprintProjection projection =
                new BlueprintProjector(perpetual(), SOLVER).projectBlueprint(premiumPaid(PRICE));
            AmortisationResult leg = eirLeg(projection, solvedEir(projection, EIGHT_PERCENT_ANNUAL));

            assertThat(leg.periods()).isEqualTo(5);
            assertThat(paise(leg.openingGca())).isEqualByComparingTo(bd("1020000.00"));
            assertThat(paise(leg.row(1).eirInterest())).isEqualByComparingTo(bd("76557.09"));
            assertTerminalCarryingAmountIsZero(leg);
        }

        private ScheduleBlueprint perpetualWith(OptionSchedule options) {
            ScheduleBlueprint template = perpetual();
            return new ScheduleBlueprint(
                template.notional(), template.currency(), template.valueDate(),
                template.statedMaturity(), template.disbursement(), template.principal(),
                template.servicing(), template.moratorium(), template.rate(), options,
                template.behaviour(), template.calendar(), template.dayCount(),
                template.residuePolicy(), template.eclHorizonPeriods());
        }
    }

    // =================================================== O4 — the puttable bond

    @Nested
    @DisplayName("O4 — a put is a shortening option, and the holder of it is a required field")
    class PuttableBond {

        /**
         * The O1 bond with a holder put at par at year three instead of an issuer call.
         *
         * <p>No template factory covers this: {@code callableCorporateBond} writes a
         * {@code CALL} held by the issuer, and the difference between the two is not
         * cosmetic. A put held by the bank is the bank's own exercise judgement,
         * evidenced by its own intentions; a call held by the issuer requires the bank
         * to model <em>someone else's</em> rational behaviour, which is a different
         * estimation problem with different evidence requirements. Holder identity is
         * what tells an auditor which of the two is in play.
         */
        private ScheduleBlueprint puttableBond() {
            return new ScheduleBlueprint(
                FACE, Money.INR, VALUE_DATE, annualDue(10),
                new DisbursementProfile.Single(VALUE_DATE, FACE),
                new PrincipalProfile.BulletAtMaturity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(NINE_PERCENT_ANNUAL),
                new OptionSchedule(
                    List.of(OptionSchedule.EmbeddedOption.atPar(OptionSchedule.OptionType.PUT,
                        OptionSchedule.OptionHolder.HOLDER_LENDER, annualDue(3))),
                    ExercisePolicy.EARLIEST_CALL),
                new BehaviouralOverlay.Contractual(
                    "bond terms grant the borrower no prepayment right"),
                ANNUAL, DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE, 10);
        }

        @Test
        @DisplayName("O4 — to the put 7.091554%, to maturity 8.246545%, 115.5 bp apart")
        void fixtureO4PuttableBond() {
            // docs 09 § 6 O4. 9% at 1,050,000 with a par put at year three. The defect this
            // catches is a resolver that treated only a CALL as term-resolving: a put
            // shortens the instrument just as effectively, and what differs between the two
            // is who holds the exercise judgement, not which direction the term moves.
            BlueprintProjection projection =
                new BlueprintProjector(puttableBond(), SOLVER)
                    .projectBlueprint(premiumPaid(PREMIUM_PRICE));
            ExpectedLifeDetermination determination = projection.expectedLife();

            assertThat(determination.chosenLifePeriods())
                .as("the put at year three truncates the ten-year ladder")
                .isEqualTo(3);
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.EARLIEST_CALL).eir()))
                .isEqualByComparingTo(bd("7.091554"));
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).eir()))
                .as("the same 8.246545% as O1: the maturity basis does not know about options")
                .isEqualByComparingTo(bd("8.246545"));

            // 115.4991363607987094005816473 bp exactly, published as 115.5.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("115.49913636"));
            assertThat(determination.widestDivergenceBps().setScale(1, Precision.MODE))
                .isEqualByComparingTo(bd("115.5"));

            // The holder is recorded, not inferred. A determination that lost this could
            // still produce the right rate and would leave an auditor unable to tell whose
            // behaviour was modelled.
            assertThat(puttableBond().options()
                .ofType(OptionSchedule.OptionType.PUT))
                .singleElement()
                .satisfies(option -> assertThat(option.holder())
                    .isEqualTo(OptionSchedule.OptionHolder.HOLDER_LENDER));
            assertThat(resultFor(determination.invariants(), InvariantId.ST_7).satisfied()).isTrue();
            assertThat(resultFor(determination.invariants(), InvariantId.ST_8).satisfied()).isTrue();
        }

        @Test
        @DisplayName("TR-1: three periods, the third redeeming par, closing at exactly zero")
        void theEirLegClosesAtThePut() {
            BlueprintProjection projection =
                new BlueprintProjector(puttableBond(), SOLVER)
                    .projectBlueprint(premiumPaid(PREMIUM_PRICE));
            AmortisationResult leg =
                eirLeg(projection, solvedEir(projection, NINE_PERCENT_ANNUAL));

            assertThat(leg.periods()).isEqualTo(3);
            assertThat(paise(leg.row(1).cashReceived())).isEqualByComparingTo(bd("90000.00"));
            assertThat(paise(leg.row(3).cashReceived()))
                .as("the put collects the coupon and par in one period")
                .isEqualByComparingTo(bd("1090000.00"));
            assertTerminalCarryingAmountIsZero(leg);
        }
    }

    // ================================================== O5 — the extension option

    @Nested
    @DisplayName("O5 — an extension lengthens the term, and gives the two-lives rule two numbers")
    class ExtensionOption {

        /**
         * Five years stated, extendable by three, ECL horizon eight.
         *
         * <p>The extended life under {@code EARLIEST_CALL} is read from the ACPIR 46(1)
         * horizon, which that regulation defines as the maximum contractual period
         * <em>including</em> extension options. So on an extendable instrument the
         * expected life under this policy and the horizon are the same number, and
         * ST-8's boundary case is the ordinary case.
         */
        private ScheduleBlueprint extendableBond(ExercisePolicy policy) {
            return extendableBond(policy, 8);
        }

        /**
         * The same bond with the ACPIR 46(1) horizon supplied.
         *
         * <p>Parameterised so the two-lives test can vary the horizon and show the
         * expected life does not follow it. The horizon is a real input to the
         * {@code EARLIEST_CALL} plan — an extension runs to the maximum contractual
         * period, which is what that regulation defines — and it is not an input to the
         * contractual-maturity life at all, which is the asymmetry being asserted.
         */
        private ScheduleBlueprint extendableBond(ExercisePolicy policy, int eclHorizonPeriods) {
            return new ScheduleBlueprint(
                FACE, Money.INR, VALUE_DATE, annualDue(5),
                new DisbursementProfile.Single(VALUE_DATE, FACE),
                new PrincipalProfile.BulletAtMaturity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(NINE_PERCENT_ANNUAL),
                new OptionSchedule(
                    List.of(OptionSchedule.EmbeddedOption.atPar(
                        OptionSchedule.OptionType.EXTENSION,
                        OptionSchedule.OptionHolder.EITHER, annualDue(5))),
                    policy),
                new BehaviouralOverlay.Contractual("contractual life; no behavioural overlay"),
                ANNUAL, DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE,
                eclHorizonPeriods);
        }

        private BlueprintProjection project(ExercisePolicy policy) {
            return project(policy, 8);
        }

        private BlueprintProjection project(ExercisePolicy policy, int eclHorizonPeriods) {
            return new BlueprintProjector(extendableBond(policy, eclHorizonPeriods), SOLVER)
                .projectBlueprint(premiumPaid(PREMIUM_PRICE));
        }

        @Test
        @DisplayName("O5 — stated 7.755768%, extended 8.125769%, 37.0 bp apart")
        void fixtureO5ExtensionOption() {
            // docs 09 § 6 O5. Exercising an extension LENGTHENS the term, so the
            // earliest-exercise policy resolves to eight periods rather than to a
            // truncation, and the premium amortises over eight years instead of five.
            //
            // Note that the five-year figure is the same 7.755768% as O1's call basis, and
            // for the same arithmetic reason: five annual coupons of 90,000 and 1,000,000 of
            // par, discounting to 1,050,000. One number, two entirely different contractual
            // features — which is worth pinning, because it means a defect that confused
            // truncation with extension would still produce this figure on one of the two.
            ExpectedLifeDetermination determination = project(ExercisePolicy.EARLIEST_CALL)
                .expectedLife();

            assertThat(determination.chosenLifePeriods()).isEqualTo(8);
            assertThat(costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).lifePeriods())
                .isEqualTo(5);
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.CONTRACTUAL_MATURITY).eir()))
                .isEqualByComparingTo(bd("7.755768"));
            assertThat(effectiveAnnualPercent(
                costed(determination, ExercisePolicy.EARLIEST_CALL).eir()))
                .isEqualByComparingTo(bd("8.125769"));

            // 37.00008081 bp exactly, published as 37.0.
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("37.00008081"));
            assertThat(determination.widestDivergenceBps().setScale(1, Precision.MODE))
                .isEqualByComparingTo(bd("37.0"));
        }

        @Test
        @DisplayName("two lives from one feature: ECL horizon 8y and EIR expected life 5y, neither derived")
        void theEclHorizonAndTheExpectedLifeAreSeparateFields() {
            // docs 09 § 3.3, and the whole reason the data model carries them apart. ACPIR
            // 46(1) sets the ECL horizon at the MAXIMUM contractual period including the
            // extension — eight years — while ACPIR 51's EIR expected life is what is
            // expected, which under a contractual-maturity policy is five.
            //
            // The defect this guards is the cheap one: deriving either from the other. Once
            // a shorter behavioural life has been used as the horizon there is no record of
            // which of the two any published figure was computed on, and the two are
            // 37.0 basis points apart on this instrument.
            // Asserted by VARYING one field and showing the other does not move, because
            // that is the only shape in which this defect is visible. What stood here was
            // `blueprint.eclHorizonPeriods() == 8` against a helper that passes the literal
            // 8 into the record constructor twelve lines above — a constructor round-trip
            // of a test literal, which the defect survives untouched: if OptionalityResolver
            // or ExpectedLifeDetermination started reading the horizon off the resolved
            // life, the blueprint's own field would still hold the 8 the test supplied. The
            // follow-on `chosenLifePeriods != eclHorizonPeriods` was entailed by the two
            // exact assertions either side of it and could not fail independently.
            ExpectedLifeDetermination determination =
                project(ExercisePolicy.CONTRACTUAL_MATURITY).expectedLife();

            assertThat(determination.chosenLifePeriods())
                .as("ACPIR 51: what is expected, which here is the stated term")
                .isEqualTo(5);
            // The engine's own reading of the horizon, not the field the test set: ST-8's
            // detail is written by ExpectedLifeDetermination from the operand it was given,
            // so a horizon derived from the life would print "ECL horizon 5" here.
            assertThat(resultFor(determination.invariants(), InvariantId.ST_8).detail())
                .as("ACPIR 46(1): the maximum contractual period, extension included")
                .contains("expected life 5")
                .contains("ECL horizon 8");
            assertThat(resultFor(determination.invariants(), InvariantId.ST_8).satisfied())
                .as("ST-8: expected life 5 within the horizon 8")
                .isTrue();

            // The same bond with the horizon at twelve and nothing else changed. The
            // contractual-maturity life is a property of the schedule and must stay at 5
            // while the horizon it is checked against moves to 12; a life read from the
            // horizon field reports 12 here and fails.
            ExpectedLifeDetermination widerHorizon =
                project(ExercisePolicy.CONTRACTUAL_MATURITY, 12).expectedLife();
            assertThat(widerHorizon.chosenLifePeriods())
                .as("the horizon moved by four periods and the expected life did not move")
                .isEqualTo(5);
            assertThat(resultFor(widerHorizon.invariants(), InvariantId.ST_8).detail())
                .contains("expected life 5")
                .contains("ECL horizon 12");

            // Both alternatives are still costed under either recorded policy, so the
            // divergence is disclosed whichever life is published.
            assertThat(determination.alternatives()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(determination.widestDivergenceBps())
                .isEqualByComparingTo(bd("37.00008081"));

            // And at the boundary — the extended life IS the horizon — ST-8 still passes.
            // That is the ordinary case on an extendable instrument, not an edge case.
            ExpectedLifeDetermination extended = project(ExercisePolicy.EARLIEST_CALL)
                .expectedLife();
            assertThat(extended.chosenLifePeriods()).isEqualTo(8);
            assertThat(resultFor(extended.invariants(), InvariantId.ST_8).satisfied()).isTrue();
        }

        @Test
        @DisplayName("the extended ladder keeps the balance outstanding and repays principal once (ST-3)")
        void theExtendedLadderRepaysPrincipalOnce() {
            // Three periods appear past stated maturity and the stated-maturity rung gives
            // up its principal repayment while keeping its billed interest. ST-3 is what
            // makes that safe to assert: repaying the 1,000,000 at both maturities would
            // count it twice and fail on the ladder rather than on the rate.
            BlueprintProjection projection = project(ExercisePolicy.EARLIEST_CALL);
            InstalmentLadder shaped = OptionalityResolver.ladderUnder(
                extendableBond(ExercisePolicy.EARLIEST_CALL),
                projection.contractualLadder(),
                projection.expectedLife());

            assertThat(shaped.length()).isEqualTo(8);
            assertThat(shaped.rungs()).extracting(InstalmentLadder.Rung::dueOn)
                .containsExactly(annualDue(1), annualDue(2), annualDue(3), annualDue(4),
                    annualDue(5), annualDue(6), annualDue(7), annualDue(8));
            assertThat(paise(shaped.rung(5).interest()))
                .as("stated maturity still bills its coupon")
                .isEqualByComparingTo(bd("90000.00"));
            assertThat(shaped.rung(5).principal().isZero())
                .as("but no longer repays principal there")
                .isTrue();
            assertThat(paise(shaped.rung(8).principal())).isEqualByComparingTo(bd("1000000.00"));
            assertThat(paise(shaped.totalInterest()))
                .as("eight annual coupons of 90,000")
                .isEqualByComparingTo(bd("720000.00"));
            assertThat(satisfied(shaped.invariants(), InvariantId.ST_3)).isTrue();
            assertThat(shaped.allSatisfied()).isTrue();

            AmortisationResult leg =
                eirLeg(projection, solvedEir(projection, NINE_PERCENT_ANNUAL));
            assertThat(leg.periods()).isEqualTo(8);
            assertTerminalCarryingAmountIsZero(leg);
        }
    }

    // ============================================== O6 — prepayment via a CPR curve

    @Nested
    @DisplayName("O6 — one mortgage, four prepayment speeds, four lives and four rates")
    class PrepaymentViaCpr {

        /** docs 09 § 6 O6: a 5,000,000 mortgage over 240 months. */
        private static final Money MORTGAGE = Money.inr("5000000");

        /** 9% p.a. nominal with monthly compounding: 0.75% a month, never 9% divided by 12. */
        private static final Rate MORTGAGE_RATE = Rate.monthly(bd("0.0075"));

        /** 50,000 of net integral fee <b>received</b>, so the carrying amount is 4,950,000. */
        private static final Money NET_FEE = Money.inr("50000");

        private static final int PERIODS = 240;

        private ScheduleBlueprint mortgage(BehaviouralOverlay overlay) {
            return new ScheduleBlueprint(
                MORTGAGE, Money.INR, VALUE_DATE, monthlyDue(PERIODS),
                new DisbursementProfile.Single(VALUE_DATE, MORTGAGE),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(MORTGAGE_RATE),
                OptionSchedule.none(),
                overlay,
                MONTHLY, DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE,
                PERIODS);
        }

        /** The fee set: 50,000 received at inception, integral to the EIR (ACPIR 52). */
        private List<FeePosting> fee() {
            return List.of(FeePosting.received("PROCESSING_FEE", NET_FEE, VALUE_DATE,
                FeeClassification.INTEGRAL));
        }

        private BlueprintProjection project(String annualCpr) {
            return new BlueprintProjector(
                mortgage(new BehaviouralOverlay.ConstantPrepaymentRate(bd(annualCpr))))
                .projectBlueprint(fee());
        }

        /**
         * One O6 row: the expected life, the principal-weighted average life and the
         * effective annual EIR.
         *
         * <p>All three are read off the engine and compared against docs 09 § 6's
         * table. The three move together — a faster speed shortens the life, shortens
         * the WAL and raises the rate — so a defect in the roll-forward that got the
         * mortality wrong would have to be wrong consistently in three places to pass.
         */
        private void assertO6Row(String annualCpr, int life, String walYears, String eirPercent) {
            BlueprintProjection projection = project(annualCpr);

            assertThat(projection.behaviour().expectedLifePeriods())
                .as("expected life in months at a %s CPR", annualCpr)
                .isEqualTo(life);
            assertThat(projection.behaviour().contractualLifePeriods()).isEqualTo(PERIODS);
            assertThat(Precision.round(projection.behaviour().principalWeightedLifeYears(), 2))
                .as("principal-weighted average life in years")
                .isEqualByComparingTo(bd(walYears));

            Rate eir = solvedEir(projection, MORTGAGE_RATE);
            assertThat(effectiveAnnualPercent(eir))
                .as("EIR effective p.a. at a %s CPR", annualCpr)
                .isEqualByComparingTo(bd(eirPercent));

            // ST-3 across the two legs: a behavioural overlay moves principal in TIME, not
            // in amount. Deducting an expected loss here — the standing temptation — would
            // fail this, and it is the single assertion that separates a prepayment overlay
            // from a credit assumption.
            assertThat(resultFor(projection.behaviour().invariants(), InvariantId.ST_3).satisfied())
                .isTrue();
            assertThat(projection.behaviour().allSatisfied()).isTrue();
            assertTerminalCarryingAmountIsZero(eirLeg(projection, eir));
        }

        @Test
        @DisplayName("the contractual schedule is 240 EMIs of 44,986.30 on a 4,950,000 carrying amount")
        void theContractualScheduleAndTheCarryingAmount() {
            // The annuity on 5,000,000 at 0.75% over 240 periods is 44,986.29779250865,
            // billed at 44,986.30 — rounded UP, which is why the final rung closes the
            // balance rather than billing a 240th full instalment.
            BlueprintProjection projection = project("0");

            assertThat(paise(projection.contractualLadder().rung(1).total()))
                .isEqualByComparingTo(bd("44986.30"));
            assertThat(projection.contractualLadder().length()).isEqualTo(PERIODS);
            assertThat(projection.assembly().netIntegralFee()).isEqualTo(NET_FEE);
            assertThat(projection.assembly().initialCarryingAmount())
                .as("5,000,000 advanced less the 50,000 net integral fee received")
                .isEqualTo(Money.inr("4950000"));
            assertThat(satisfied(projection.invariants(), InvariantId.IC_1)).isTrue();
            assertThat(projection.allInvariantsSatisfied())
                .as("breaches: %s", projection.projection().invariants())
                .isTrue();
        }

        @Test
        @DisplayName("O6 at a 0% CPR: life 240 months, principal-WAL 12.88y, EIR 9.533867% p.a.")
        void fixtureO6AtNoPrepayment() {
            // The contractual row, and it is recorded as an assumption that came out nil
            // rather than as an absence of assumption. docs 09 § 2.7 is explicit that the
            // two produce identical numbers and very different audit outcomes.
            assertO6Row("0", 240, "12.88", "9.533867");

            BlueprintProjection projection = project("0");
            assertThat(projection.behaviour().altered())
                .as("a nil CPR reshapes nothing, so the contractual ladder is handed back")
                .isFalse();
            assertThat(projection.behaviour().expected())
                .isSameAs(projection.contractualLadder());
            assertThat(projection.behaviour().recordedBasis())
                .as("the assumption is disclosed even though it changed no figure")
                .isNotBlank();
            assertThat(Precision.round(projection.behaviour().lifeRatio(), 4))
                .isEqualByComparingTo(bd("1.0000"));
        }

        @Test
        @DisplayName("O6 at an 8% CPR: life 116 months, principal-WAL 4.90y, EIR 9.674777% p.a.")
        void fixtureO6AtEightPercentCpr() {
            assertO6Row("0.08", 116, "4.90", "9.674777");
        }

        @Test
        @DisplayName("O6 at a 15% CPR: life 86 months, principal-WAL 3.30y, EIR 9.785190% p.a.")
        void fixtureO6AtFifteenPercentCpr() {
            assertO6Row("0.15", 86, "3.30", "9.785190");
        }

        @Test
        @DisplayName("O6 at a 25% CPR: life 64 months, principal-WAL 2.24y, EIR 9.945464% p.a.")
        void fixtureO6AtTwentyFivePercentCpr() {
            assertO6Row("0.25", 64, "2.24", "9.945464");
        }

        @Test
        @DisplayName("the four rates rise with speed because the 50,000 net fee is income (INV-2)")
        void theRatesRiseWithSpeedBecauseTheFeeIsIncome() {
            // The ordering docs 09 § 6 makes explicit, and it is the assertion that turns
            // four separate figures into one statement about the engine. A shorter life
            // amortises a fee RECEIVED faster, so every rate sits above the contractual
            // effective rate and each sits above the slower one. Had the 50,000 been a cost
            // paid, all four would sit BELOW the contractual rate and the ordering would
            // reverse — which is why no prepayment policy can be assumed conservative
            // either.
            //
            // The contractual effective rate is (1 + 0.0075)^12 - 1 = 9.380690%: 0.75% a
            // month compounded, never 9%.
            Rate contractual = MORTGAGE_RATE;
            assertThat(effectiveAnnualPercent(contractual)).isEqualByComparingTo(bd("9.380690"));

            BlueprintProjection fastest = project("0.25");
            Rate atZero = solvedEir(project("0"), MORTGAGE_RATE);
            Rate atEight = solvedEir(project("0.08"), MORTGAGE_RATE);
            Rate atFifteen = solvedEir(project("0.15"), MORTGAGE_RATE);
            Rate atTwentyFive = solvedEir(fastest, MORTGAGE_RATE);

            assertThat(atZero.effectiveAnnual()).isGreaterThan(contractual.effectiveAnnual());
            assertThat(atEight.effectiveAnnual()).isGreaterThan(atZero.effectiveAnnual());
            assertThat(atFifteen.effectiveAnnual()).isGreaterThan(atEight.effectiveAnnual());
            assertThat(atTwentyFive.effectiveAnnual()).isGreaterThan(atFifteen.effectiveAnnual());

            // INV-2's baseline is the par gap of the CONTRACTUAL leg, which is the
            // unaccelerated mortgage ladder: prepayment reshapes the expected vector the
            // rate is solved over, not the schedule the borrower contracted for. A level
            // mortgage prices to par at its own coupon, so the gap is the rental-rounding
            // residue and nothing more, and the whole 50,000 of fee is left to explain the
            // spread.
            Money parGap = parGapOf(fastest, MORTGAGE_RATE);
            assertThat(parGap.amount().abs())
                .as("a level mortgage prices to par at its coupon: %s", parGap)
                .isLessThan(bd("1"));

            InvariantResult inv2 =
                InvariantChecks.feeSignOrdering(atTwentyFive, contractual, NET_FEE, parGap);
            assertThat(inv2.id()).isEqualTo(InvariantId.INV_2);
            assertThat(inv2.satisfied())
                .as("INV-2 at the fastest speed, where the spread is widest")
                .isTrue();
        }
    }

    // ==================================================== O7 — the par test

    @Nested
    @DisplayName("O7 — a behavioural re-estimation is a P&L event only away from par")
    class TheParTest {

        /** Par value of the note, and the pool balance it opens on. */
        private static final Money NOTE_PAR = Money.inr("1000000");

        /**
         * Pool coupon 10% p.a. nominal with monthly compounding.
         *
         * <p>{@link Rate} stores at twelve places, so the periodic figure held is
         * 0.008333333333 rather than the exact one-twelfth of ten percent. The
         * 3.3e-13 truncation moves the level payment by 2e-7 of a rupee and nothing
         * this class asserts.
         */
        private static final Rate POOL_COUPON =
            Rate.monthly(bd("0.10").divide(bd("12"), Precision.WORKING));

        /** The note's 60-month term, and the ACPIR 46(1) horizon it implies. */
        private static final int PERIODS = 60;

        /** The month the CPR assumption is revised, and the anchor of the restatement. */
        private static final int REVISION_PERIOD = 24;

        /**
         * The note's own band: the worst-case unamortised figure instalment rounding alone
         * can show, over the 36 periods remaining after the revision, at the pool coupon.
         *
         * <p>The screen's threshold has to be measured because it cannot be assumed: a
         * billed instalment sits within half a paisa of the exact annuity and that error
         * accumulates at the contractual rate, giving 0.13 over 24 periods and 3.34 over
         * 240. The screen used to compare at presentation scale, which fixes the threshold
         * at half a paisa for every tenor — and this note's own residue is 0.0089, which
         * rounded up to 0.01 and made a note bought AT PAR read as held away from par.
         */
        private static Money band() {
            return BehaviouralAdjuster.roundingResidueBound(
                POOL_COUPON, PERIODS - REVISION_PERIOD, Money.INR);
        }

        /** Monthly periodic indexing: tau is the period ordinal, so every dtau is exactly 1. */
        private static final TimeConvention MONTHLY_INDEX = TimeConvention.PeriodicIndex.monthly();

        private ScheduleBlueprint note(BehaviouralOverlay overlay) {
            return new ScheduleBlueprint(
                NOTE_PAR, Money.INR, VALUE_DATE, monthlyDue(PERIODS),
                new DisbursementProfile.Single(VALUE_DATE, NOTE_PAR),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(POOL_COUPON),
                OptionSchedule.none(),
                overlay,
                MONTHLY, DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE,
                PERIODS);
        }

        /** The assumption in force at origination: a flat 10% CPR. */
        private BehaviouralOverlay tenPercentCpr() {
            return new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.10"));
        }

        /**
         * The revised curve: 10% for the first twenty-four months, 20% thereafter.
         *
         * <p>A {@code CprVector} holds its last point flat past its end, so a
         * twenty-fifth entry of 0.20 states "revised to 20% at month 24" without
         * having to enumerate the remaining term. Periods 1 to 24 carry the original
         * speed, which is what makes the pool balance at the revision date the same
         * figure on both curves — asserted below, because if it were not, the
         * restatement would be measured against a balance the original assumption
         * never produced.
         */
        private BehaviouralOverlay revisedToTwentyPercent() {
            List<BigDecimal> curve = new ArrayList<>();
            for (int period = 1; period <= REVISION_PERIOD; period++) {
                curve.add(bd("0.10"));
            }
            curve.add(bd("0.20"));
            return new BehaviouralOverlay.CprVector(List.copyOf(curve));
        }

        /**
         * The postings that record a purchase price away from par.
         *
         * <p>A premium is an integral cost paid, which lifts the carrying amount above
         * par; a discount is integral income received, which drops it below. This is
         * the quantity ST-9 keys on and the only difference between the three rows of
         * the fixture.
         */
        private List<FeePosting> pricedAt(Money price) {
            if (price.compareTo(NOTE_PAR) > 0) {
                return List.of(FeePosting.paid("PURCHASE_PREMIUM", price.minus(NOTE_PAR),
                    VALUE_DATE, FeeClassification.INTEGRAL, "OTHER"));
            }
            if (price.compareTo(NOTE_PAR) < 0) {
                return List.of(FeePosting.received("PURCHASE_DISCOUNT", NOTE_PAR.minus(price),
                    VALUE_DATE, FeeClassification.INTEGRAL));
            }
            return List.of();
        }

        /**
         * The revised expected flows, anchored at the revision date and re-indexed
         * from it.
         *
         * <p>Only flows strictly after the anchor are included. Cash received <em>on</em>
         * the revision date has already been applied by the time a restatement runs —
         * step 2 of the intra-period ordering precedes step 4 — so discounting it into
         * the restated balance would count it twice.
         *
         * <p><b>Billed to the paise</b>, because that is what the note holder actually
         * receives and what {@code FlowVectorAssembler} emits on the original leg. A
         * restatement measured over unrounded flows against a carrying amount rolled
         * forward over rounded ones would be comparing two different schedules, and the
         * difference would land in the catch-up.
         *
         * <p>Constructing this vector is building the restatement's <em>input</em>, not
         * its expected output: the figure under test is the catch-up the engine computes
         * from it, which no line here computes.
         */
        private FlowVector revisedFlowsFromRevisionDate(InstalmentLadder revised) {
            assertThat(revised.terminalBalance().isZero())
                .as("a fully amortising note retains no terminal lump to carry into the vector")
                .isTrue();
            List<CashFlow> flows = new ArrayList<>();
            for (InstalmentLadder.Rung rung : revised.rungs()) {
                if (rung.periodIndex() > REVISION_PERIOD) {
                    flows.add(CashFlow.of(rung.dueOn(), rung.periodIndex() - REVISION_PERIOD,
                        rung.total().atPresentationScale(), FlowKind.COMBINED_EMI));
                }
            }
            assertThat(flows).isNotEmpty();
            return FlowVector.of(monthlyDue(REVISION_PERIOD), Money.INR, flows);
        }

        /** Everything one purchase price produces, so no test can pair mismatched halves. */
        private ParRow row(Money price) {
            BlueprintProjection original =
                new BlueprintProjector(note(tenPercentCpr())).projectBlueprint(pricedAt(price));
            BlueprintProjection revised =
                new BlueprintProjector(note(revisedToTwentyPercent()))
                    .projectBlueprint(pricedAt(price));

            Rate eir = solvedEir(original, POOL_COUPON);
            AmortisationResult leg = eirLeg(original, eir);
            Money gcaBefore = leg.row(REVISION_PERIOD).closingGca();
            Money poolBalance =
                original.behaviour().expected().rung(REVISION_PERIOD).balanceAfter();

            // The two curves must agree about the balance at the revision date, because
            // periods 1 to 24 carry the same 10% speed on both. A CprVector that indexed
            // its points off by one would break this and nothing downstream would notice.
            assertThat(paise(revised.behaviour().expected().rung(REVISION_PERIOD).balanceAfter()))
                .as("the revised curve reproduces the original pool balance at month 24")
                .isEqualByComparingTo(paise(poolBalance));

            FlowVector revisedFlows =
                revisedFlowsFromRevisionDate(revised.behaviour().expected());
            CatchUpResult restatement =
                CatchUpCalculator.restate(eir, eir, gcaBefore, revisedFlows, MONTHLY_INDEX);
            return new ParRow(price, eir, gcaBefore, poolBalance, revisedFlows, restatement);
        }

        private record ParRow(
            Money price,
            Rate eir,
            Money gcaBefore,
            Money poolBalance,
            FlowVector revisedFlows,
            CatchUpResult restatement) {
        }

        @Test
        @DisplayName("the note is a 60-month level payment of 21,247.04 on a 10% pool coupon")
        void theNoteItself() {
            // The annuity on 1,000,000 at 0.008333333333 over 60 periods is
            // 21,247.04471107145813 — billed at 21,247.04. Asserted before the catch-ups,
            // because all three rows of the fixture are the same schedule seen from three
            // purchase prices, and a different schedule would move all three together.
            InstalmentLadder ladder = ScheduleBuilder.build(note(tenPercentCpr()));

            assertThat(ladder.length()).isEqualTo(PERIODS);
            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("21247.04"));
            assertThat(paise(ladder.rung(1).interest()))
                .as("1,000,000 x 0.008333333333")
                .isEqualByComparingTo(bd("8333.33"));
            assertThat(ladder.terminalBalance().isZero()).isTrue();
            assertThat(satisfied(ladder.invariants(), InvariantId.ST_3)).isTrue();
        }

        @Test
        @DisplayName("the 10% CPR expected leg runs 47 months and stands at 485,832.21 at month 24")
        void theExpectedLegUnderTheOriginalAssumption() {
            // The pool balance every row of the fixture is measured against. A 10% annual
            // CPR is a survival statement, so the monthly mortality is 1 - 0.9^(1/12) =
            // 0.0087416109546967057639004391 and NOT 10%/12: taking the division instead
            // would prepay a twelfth of a tenth per month and leave the note running to
            // month 52. The root is verifiable in the other direction —
            // (1 - 0.0087416109546967057639004391)^12 is 0.9 to twenty-eight places —
            // and BehaviouralAdjusterTest states the same constant, which is the point of
            // stating it: two files that disagree about the mortality would disagree about
            // every balance below.
            //
            // The instalment is fixed and the prepayment rides on top of it, so period 1
            // bills more than 21,247.04, not less — a borrower who prepays does not get a
            // smaller instalment, they need fewer of them.
            BlueprintProjection projection =
                new BlueprintProjector(note(tenPercentCpr())).projectBlueprint(List.of());
            InstalmentLadder expected = projection.behaviour().expected();

            assertThat(projection.behaviour().expectedLifePeriods()).isEqualTo(47);
            assertThat(projection.behaviour().contractualLifePeriods()).isEqualTo(PERIODS);
            assertThat(expected.rung(1).total().amount())
                .as("the contractual bill plus the prepayment, never net of it")
                .isGreaterThan(bd("21247.04"));
            // PROVENANCE, corrected. 485,832.21 is the ENGINE's pool balance at month 24,
            // pinned here as a regression lock: docs 09 § 3.4 publishes only the unamortised
            // and catch-up columns for O7 and no pool balance, and no section of docs 09
            // states this figure or the 47-month expected life above it. It was labelled
            // "docs 09 § 3.4's shared figure", which it is not — and that mislabelling had
            // consequences, because fixtureO7Premium's own text cites this number back as
            // the document's authority when arguing that a fully-unrounded basis
            // contradicts the document. Two things follow. It is a lock and not a
            // specification: it cannot show that today's value is right, only that it has
            // not moved. And it wants publishing — the m24 pool balance and the three O7
            // rates belong in docs 09 § 3.4 alongside the two columns it does publish, and
            // until they are there this assertion is the only record of them.
            assertThat(paise(expected.rung(REVISION_PERIOD).balanceAfter()))
                .as("the pool balance at month 24 — engine output, pinned; docs 09 publishes none")
                .isEqualByComparingTo(bd("485832.21"));

            // ST-3 across the legs: a prepayment assumption moves principal in time, not in
            // amount. All 1,000,000 comes back on both legs, 13 months earlier on one.
            assertThat(resultFor(projection.behaviour().invariants(), InvariantId.ST_3).satisfied())
                .isTrue();
            assertThat(projection.behaviour().allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("the three purchase prices give 8.642478%, 10.471307% and 12.411347% p.a.")
        void theThreeRatesOnOneSchedule() {
            // One schedule, three carrying amounts, three rates. The premium row yields
            // below the par row and the discount row above it, which is INV-2's ordering
            // appearing again on an instrument whose integral amount is a purchase price
            // rather than a fee.
            //
            // PROVENANCE, stated exactly because it is not uniform across the three.
            // docs 09 § 3.4's O7 table publishes two columns — unamortised at m24 and
            // catch-up — and no rate column, so only the par row's 10.471307% is a document
            // figure in the strict sense: it is the pool coupon compounded, (1 +
            // 0.008333333333)^12 − 1, which a reviewer can check by hand.
            // The 8.642478% and 12.411347% rows are the engine's own solves over the same
            // schedule at 1,030,000 and 970,000 and appear in no document; they are pinned
            // here as regression locks, and what makes them meaningful is not their
            // provenance but the ordering below them and the two catch-ups they produce.
            ParRow premium = row(Money.inr("1030000"));
            ParRow par = row(NOTE_PAR);
            ParRow discount = row(Money.inr("970000"));

            assertThat(effectiveAnnualPercent(premium.eir())).isEqualByComparingTo(bd("8.642478"));
            assertThat(effectiveAnnualPercent(par.eir())).isEqualByComparingTo(bd("10.471307"));
            assertThat(effectiveAnnualPercent(discount.eir()))
                .isEqualByComparingTo(bd("12.411347"));
            assertThat(premium.eir().effectiveAnnual()).isLessThan(par.eir().effectiveAnnual());
            assertThat(discount.eir().effectiveAnnual()).isGreaterThan(par.eir().effectiveAnnual());

            // And the DIRECTION of each catch-up, which is the part of the fixture that does
            // not depend on the last paisa. Faster prepayment pulls the remaining flows
            // forward: a premium has less time left to amortise, so the carrying amount is
            // written down; a discount accretes faster, so it is written up. An engine with
            // the sign of the premium backwards would fail here on both rows at once.
            assertThat(premium.restatement().isCharge())
                .as("a premium re-estimated to a faster speed is a charge")
                .isTrue();
            assertThat(discount.restatement().isIncome())
                .as("a discount re-estimated to a faster speed is a gain")
                .isTrue();
            // And the MAGNITUDES, pinned to the paise. This is the only live assertion on
            // O7 catch-up size — fixtureO7Premium, fixtureO7Discount and fixtureO7Par are
            // all @Disabled for reasons their own text sets out — so it is the whole of the
            // regression lock and it is stated as an equality rather than as a band.
            //
            // −876.39 and +878.89 are the engine's figures on its own basis: every rung
            // total billed to the paise, as FlowVectorAssembler emits it and as the note
            // holder is actually paid. docs 09 publishes −876.38 and +878.90, computed on
            // an unrounded flow vector, and the one-paisa gap between the two bases is
            // exactly what the two disabled fixtures document. So the document's figures
            // stay asserted there, honestly failing, and the engine's are locked here.
            //
            // What stood here instead was a band: |premium + discount| < 5 with each
            // magnitude only bounded BELOW at 800 and no ceiling at all. Any defect moving
            // both catch-ups by a common offset while preserving their near-mirror
            // relationship passed it — a CprVector applying the 10%→20% revision at month
            // 25 instead of 24, a revised speed of 19% instead of 20%, or a uniform 1,000
            // shift to −1,876 / +1,879 — because the signs held, the sum stayed under five
            // and both magnitudes stayed over 800. The mirror-image property that band was
            // trying to state is still true and is now implied: the two figures differ by
            // 2.50, which is the real asymmetry between amortising a premium at 8.642478%
            // and a discount at 12.411347%.
            assertThat(paise(premium.restatement().catchUp()))
                .as("the engine's premium catch-up, billed-to-the-paise basis")
                .isEqualByComparingTo(bd("-876.39"));
            assertThat(paise(discount.restatement().catchUp()))
                .as("the engine's discount catch-up, same basis")
                .isEqualByComparingTo(bd("878.89"));
        }

        @Test
        @DisplayName("away from par the screen fires, ST-9 makes no claim, and the restatement is clean")
        void awayFromParTheRestatementIsLicensedAndClean() {
            // The screen is the method a batch calls BEFORE it calls CatchUpCalculator, and
            // calling it in that order is the difference between a close that finishes and
            // one that does not. On the two away-from-par rows it fires, ST-9 passes
            // carrying the premium or discount that licenses the posting, and says in as
            // many words that it is making no claim about the posting's size — an invariant
            // that flagged a legitimate posting is an invariant nobody leaves switched on.
            for (Money price : List.of(Money.inr("1030000"), Money.inr("970000"))) {
                ParRow priced = row(price);

                assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                    priced.poolBalance(), priced.gcaBefore(), band()))
                    .as("held away from par at a purchase price of %s", price)
                    .isTrue();
                InvariantResult st9 = BehaviouralAdjuster.catchUpIsNilAtPar(
                    priced.poolBalance(), priced.gcaBefore(), priced.restatement().catchUp(),
                    band());
                assertThat(st9.id()).isEqualTo(InvariantId.ST_9);
                assertThat(st9.satisfied()).isTrue();
                assertThat(st9.detail())
                    .contains("held away from par by")
                    .contains("ST-9 makes no claim about its size");

                // CU-1 and CU-2, the restatement's own invariants. CU-1 is the important
                // one: discounting is at the ORIGINAL EIR, and a rate re-solved over the
                // revised flows from the current carrying amount would reproduce that
                // carrying amount almost exactly — collapsing the catch-up toward zero and
                // silently turning a B5.4.6 event into a B5.4.5 one, with no P&L, no trace,
                // and a rate that no longer reconciles to the original measurement.
                assertThat(priced.restatement().eirAfter().periodic())
                    .isEqualByComparingTo(priced.restatement().eirBefore().periodic());
                assertThat(satisfied(priced.restatement().invariants(), InvariantId.CU_1)).isTrue();
                assertThat(priced.restatement().isClean())
                    .as("breaches at %s: %s", price, priced.restatement().breaches())
                    .isTrue();
            }
        }

        @Test
        @DisplayName("the restated balance amortises to zero over the revised flows (TR-1)")
        void theRestatedBalanceAmortisesToZero() {
            // The restated balance IS the present value of the revised flows at the
            // retained rate, so it amortises to zero over them by construction. A residue
            // here would mean the restatement and the roll-forward were handed different
            // vectors, which is the one defect the identity cannot absorb.
            ParRow premium = row(Money.inr("1030000"));

            AmortisationResult after = CatchUpCalculator.rollForwardRestated(
                premium.restatement(), premium.revisedFlows(), MONTHLY_INDEX);

            assertThat(after.openingGca()).isEqualTo(premium.restatement().restatedGca());
            assertThat(after.periods())
                .as("the revised curve retires the note by month 45, so 21 periods remain")
                .isEqualTo(21);
            assertTerminalCarryingAmountIsZero(after);
        }

        @Test
        @DisplayName("a schedule billed to the paise does not reprice exactly at its coupon")
        void aScheduleBilledToThePaiseDoesNotRepriceExactlyAtItsCoupon() {
            // The measurement that explains the three disabled tests below, and a real
            // property of the instrument rather than a defect. Every expected ladder
            // satisfies B_t = B_(t-1)(1 + r) - CF_t at working precision, so PV(CF) at the
            // contractual rate is exactly the opening balance — but the note holder is paid
            // in paise, so FlowVectorAssembler emits each rung total at presentation scale
            // and the vector the solver sees is not quite that ladder.
            //
            // Over 47 rounded instalments the residue is a fraction of a rupee, and it is
            // arbitrarily signed: nothing about the note changed, only the direction the
            // last paise rounded. The consequence is that a note bought at exactly
            // 1,000,000 does not solve to exactly its 10% coupon, and by month 24 the
            // EIR-leg balance has drifted from the pool balance by a shade under a paisa.
            //
            // Asserted as a bound rather than as a printed constant. What matters is the
            // ORDER of the residue — sub-rupee at inception, sub-paise carried — because
            // that is the quantity every tolerance downstream has to be set against.
            BlueprintProjection par =
                new BlueprintProjector(note(tenPercentCpr())).projectBlueprint(List.of());
            BigDecimal residueAtInception = Discounting.presentValue(
                POOL_COUPON.periodic(),
                par.projection().expected(),
                par.projection().recommendedConvention())
                .subtract(NOTE_PAR.amount());

            // A rupee bound stood here, on a measurement of 0.0027 — 369x the quantity it
            // bounded, on the one figure this test exists to establish the ORDER of. It is
            // now a paise, which is 3.7x the measurement and the same discipline as the
            // carriedDrift line below (1.12x its own). The tightening matters because the
            // disabled fixtureO7Par records a live defect whose whole cause is that the
            // carried residue exceeds the par screen's half-paise threshold: the residue's
            // size IS that defect's severity, and a bound that admitted a hundred-fold
            // increase — an assembler rounding rung totals to the rupee, a dropped
            // fractional part — would have reported green through all of it.
            assertThat(residueAtInception.abs())
                .as("the 47 billed instalments price to within a paise of the 1,000,000 par")
                .isLessThan(bd("0.01"));
            assertThat(residueAtInception.abs())
                .as("and to more than a millionth, so it is billing and not arithmetic noise")
                .isGreaterThan(bd("0.000001"));

            Rate eir = solvedEir(par, POOL_COUPON);
            assertThat(eir.effectiveAnnual().subtract(POOL_COUPON.effectiveAnnual()).abs())
                .as("the par note's EIR sits a rounding artefact away from its coupon, not a"
                    + " premium away — INV-2's baseline is now the measured par gap rather than"
                    + " a band around the annualised coupon, so this is stated as the magnitude"
                    + " it is")
                .isLessThan(bd("0.000001"));

            Money carriedDrift = BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                row(NOTE_PAR).poolBalance(), row(NOTE_PAR).gcaBefore());
            assertThat(carriedDrift.amount().abs())
                .as("carried to month 24 the drift is still under one paisa")
                .isLessThan(bd("0.01"));
        }

        @Test
        @DisplayName("O7 premium — bought 1,030,000: unamortised 7,688.32 and a catch-up of 876.39 loss")
        void fixtureO7Premium() {
            // docs 09 § 6 O7 and § 3.4, the premium row. Faster prepayment pulls the
            // remaining flows forward, and a premium being amortised over them has less
            // time to amortise, so the restatement writes the carrying amount DOWN.
            //
            // These figures moved by a paisa when the document was corrected. It published
            // 493,520.52 / 7,688.30 / -876.38, computed on an unrounded schedule; a pool
            // bills cash, so the level payment is 21,247.04 and every flow is paid to the
            // paisa, which is what FlowVectorAssembler emits. The old premium row was also
            // inconsistent with itself: 7,688.30 arises only on a fully unrounded basis,
            // where the GCA would be 493,520.41 and the pool balance 485,832.10, both
            // contradicting the figures published beside it. tools/reference-cases/o7.py now
            // bills the level payment and the flows, so all three rows reproduce on one
            // basis, and it is the engine's.
            ParRow premium = row(Money.inr("1030000"));

            assertThat(paise(premium.gcaBefore()))
                .as("EIR carrying amount at month 24")
                .isEqualByComparingTo(bd("493520.53"));
            assertThat(paise(BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                premium.poolBalance(), premium.gcaBefore())))
                .as("docs 09 § 3.4: 493,520.53 less a 485,832.21 pool balance, reduced once")
                .isEqualByComparingTo(bd("7688.32"));
            assertThat(paise(premium.restatement().catchUp()))
                .as("docs 09 § 3.4 and § 6: a loss of 876.39")
                .isEqualByComparingTo(bd("-876.39"));
        }

        @Test
        @DisplayName("O7 discount — bought 970,000: unamortised -7,847.59 and a catch-up of 878.89 gain")
        void fixtureO7Discount() {
            // The mirror row. A discount accreting over flows that now arrive sooner
            // accretes faster, so the restatement writes the carrying amount UP — the sign
            // reverses with the purchase price here exactly as it does on O1 against O2.
            ParRow discount = row(Money.inr("970000"));

            assertThat(paise(discount.gcaBefore())).isEqualByComparingTo(bd("477984.62"));
            assertThat(paise(BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                discount.poolBalance(), discount.gcaBefore())))
                .isEqualByComparingTo(bd("-7847.59"));
            assertThat(paise(discount.restatement().catchUp()))
                .as("docs 09 § 3.4 and § 6: a gain of 878.89")
                .isEqualByComparingTo(bd("878.89"));
        }

        @Test
        @DisplayName("O7 par — bought 1,000,000: nil within the rounding residue, and ST-9 says so")
        void fixtureO7Par() {
            // The most useful single result in docs 09, and the reason it is exact rather
            // than small. Every expected ladder satisfies B_t = B_(t-1)(1 + r) - CF_t by
            // construction; unrolling that recursion gives B_0 = sum CF_t / (1+r)^t for ANY
            // cash-flow path whatever. So at par — where the EIR is the contractual rate —
            // the revised flows discount back to the outstanding balance regardless of the
            // prepayment speed assumed. There is nothing for a change in speed to
            // accelerate because there is nothing being amortised: the balance is the
            // balance.
            ParRow par = row(NOTE_PAR);

            assertThat(paise(par.poolBalance()))
                .as("docs 09 § 3.4's shared pool balance at the revision")
                .isEqualByComparingTo(bd("485832.21"));

            // And here the document idealises. It says the unamortised figure at par is
            // 0.00, which is true of an UNROUNDED schedule and cannot be true of a billed
            // one: the note bills 21,247.04 against an exact annuity of 21,247.0447110715,
            // so its flows price to 999,999.9973 rather than to par, it solves to
            // 0.008333333204 rather than the contractual 0.008333333333, and 0.0089 of
            // residue shows up as "unamortised" at month 24. The engine is right and the
            // document's 0.00 is the limit the engine would reach if a borrower could be
            // billed fractions of a paisa.
            Money residue = BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                par.poolBalance(), par.gcaBefore());
            assertThat(residue.amount().setScale(4, RoundingMode.HALF_UP))
                .as("instalment-rounding residue, not a premium")
                .isEqualByComparingTo(bd("0.0089"));
            assertThat(paise(par.gcaBefore()))
                .as("so the carrying amount presents a paisa above the pool balance")
                .isEqualByComparingTo(bd("485832.22"));

            // Which is the whole reason the screen's threshold has to be measured. At
            // presentation scale 0.0089 rounds to 0.01 and reads as a premium — that was
            // the defect, and it made a note bought AT PAR post a catch-up of -0.01 while
            // ST-9 passed vacuously through its away-from-par branch. Against the schedule's
            // own band of 0.2862 the residue is dust, which is what it is.
            assertThat(residue.atPresentationScale().isZero())
                .as("rounding the residue is what used to make it look like a premium")
                .isFalse();
            assertThat(residue.abs()).isLessThan(band());
            assertThat(paise(par.restatement().catchUp()))
                .as("the catch-up on the BILLED schedule: -0.0089, presenting as -0.01. docs 09"
                    + " § 3.4 says exactly 0.00, which is the unrounded limit — a borrower cannot"
                    + " be billed fractions of a paisa, and the residue that makes the balance"
                    + " look a hair off par makes the restatement land a hair off the balance")
                .isEqualByComparingTo(bd("-0.01"));
            assertThat(par.restatement().catchUp().abs())
                .as("and it is inside the schedule's own rounding residue, so nothing was"
                    + " accelerated — which is the claim § 3.4 is actually making")
                .isLessThan(band());

            // The screen returns false, which is the whole design consequence: catch-up
            // processing keys on the unamortised premium or discount balance and NOT on the
            // fact that an assumption moved. Keying it on the assumption re-estimates every
            // contract a curve refresh touches — at ten million contracts that is
            // correctness-neutral and ruinous, and it floods the movement schedule with
            // nil-effect restatements that bury the ones a reviewer needs to see.
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                par.poolBalance(), par.gcaBefore(), band()))
                .as("no premium, no discount, no P&L: the contract need not be re-solved at all")
                .isFalse();

            InvariantResult st9 = BehaviouralAdjuster.catchUpIsNilAtPar(
                par.poolBalance(), par.gcaBefore(), par.restatement().catchUp(), band());
            assertThat(st9.id()).isEqualTo(InvariantId.ST_9);
            assertThat(st9.satisfied()).isTrue();
            assertThat(st9.detail())
                .as("and ST-9 asserts the nil rather than waving it through as away from par")
                .contains("re-estimation at par");
            assertThat(st9.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ============================= O8 — the lease residual, and INV-2 both ways

    @Nested
    @DisplayName("O8 — a lease residual, and the sign of the net integral amount read off the rate")
    class LeaseWithResidualValue {

        /** The asset financed, and the O8 notional. */
        private static final Money ASSET = FACE;

        /** The contracted residual the lessor recovers on return of the asset. */
        private static final Money RESIDUAL = Money.inr("200000");

        /**
         * 11% p.a. nominal with monthly compounding — 0.009166666667 as stored.
         *
         * <p>The 3.3e-13 truncation against the exact eleven-twelfths of a percent
         * moves the rental by 2.2e-13 of a rupee and nothing asserted here.
         */
        private static final Rate LEASE_RATE =
            Rate.monthly(bd("0.11").divide(bd("12"), Precision.WORKING));

        private static final int PERIODS = 36;

        private ScheduleBlueprint lease() {
            return ProductTemplates.commercialVehicle(
                TemplateBasis.of(VALUE_DATE, monthlyDue(PERIODS), MONTHLY,
                    ProductTemplates.termLoanDayCount(), PERIODS),
                ASSET, LEASE_RATE, RESIDUAL,
                "contractual life; the lessee has no early-termination right");
        }

        private BlueprintProjection project(List<FeePosting> postings) {
            return new BlueprintProjector(lease())
                .withTerminalLumpAs(ProductTemplates.leaseTerminalKind())
                .projectBlueprint(postings);
        }

        @Test
        @DisplayName("ST-5: the ladder amortises to the 200,000 residual, not to zero")
        void st5TheLadderAmortisesToTheResidualAndNotToZero() {
            // docs 09 § 6 O8. The instalments amortise TOWARD the residual, so the rental
            // is (1,000,000 - PV(200,000)) x i / (1 - (1+i)^-36) = 28,024.30702715317,
            // billed at 28,024.31, and the final rung carries the residual as an add-on at
            // 228,024.31.
            //
            // Sizing the instalments on the full 1,000,000 and adding the residual on top
            // is the standard error, and it over-collects by exactly the residual's present
            // value. ST-5 catches the mirror image: a residual-value ladder that reached
            // zero has silently amortised the lump the lessee still owes.
            InstalmentLadder ladder = ScheduleBuilder.build(lease());

            assertThat(ladder.length()).isEqualTo(PERIODS);
            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("28024.31"));
            assertThat(paise(ladder.rung(PERIODS).total()))
                .as("the last rental plus the residual, billed as one figure")
                .isEqualByComparingTo(bd("228024.31"));
            assertThat(ladder.terminalBalance())
                .as("ST-5: the terminal lump is retained on the ladder, never plugged away")
                .isEqualTo(RESIDUAL);
            assertThat(resultFor(ladder.invariants(), InvariantId.ST_5).satisfied()).isTrue();
            assertThat(satisfied(ladder.invariants(), InvariantId.ST_3)).isTrue();

            // The independent price check, and it is what actually gates the sizing. The 36
            // rentals ALONE — the terminal residual taken back out of the final rung —
            // discount at the contractual rate to the asset cost less the residual's present
            // value: 1,000,000 − 144,001.06 = 855,998.94, docs 09 § 6's own two figures
            // subtracted. Nothing in ScheduleBuilder computes this sum, so it cannot agree
            // with a wrong rental by construction, and it fails by 144,001 on the standard
            // error the comment above names — rentals sized over the full 1,000,000 with the
            // residual added on top, which prices the rental leg at par.
            //
            // What stood here was 200,000 x discountFactor(i, 36) == 144,001.06, computed in
            // the test body with the engine's own primitive. It touched Rate.monthly and
            // Precision.discountFactor and nothing else — no builder, no projector, no
            // annuity — so the sizing error it claimed to gate reached neither side of it
            // and it produced 144,001.06 either way.
            //
            // The tolerance is twenty-five paise on the same reasoning
            // StructureFixturesTest gives for its own price check: each of the 36 rentals is
            // billed to the paise, and half a paise on each discounts to at most
            // 0.005 x sum((1+i)^-t) = 0.005 x 30.5449 = 0.153. Against that, the 144,001 a
            // full-cost sizing misses by is more than five orders of magnitude away.
            BigDecimal rentalsOnly = BigDecimal.ZERO;
            for (InstalmentLadder.Rung rung : ladder.rungs()) {
                Money rental = rung.periodIndex() == PERIODS
                    ? rung.total().minus(RESIDUAL)
                    : rung.total();
                rentalsOnly = rentalsOnly.add(
                    rental.amount().multiply(
                        Precision.discountFactor(
                            LEASE_RATE.periodic(), BigDecimal.valueOf(rung.periodIndex())),
                        Precision.WORKING),
                    Precision.WORKING);
            }
            assertThat(rentalsOnly)
                .as("the rental leg alone prices to the asset cost less the residual's PV")
                .isCloseTo(bd("855998.94"), Offset.offset(bd("0.25")));
        }

        @Test
        @DisplayName("O8 fee received 12,000: GCA 988,000, EIR 12.377404%, above the contractual rate")
        void fixtureO8WithAFeeReceived() {
            // A fee received records the asset BELOW par, so it must accrete back up and
            // the yield must exceed the coupon. docs 09 § 6 O8's first row.
            BlueprintProjection projection = project(List.of(
                FeePosting.received("LEASE_ARRANGEMENT_FEE", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL)));
            Rate eir = solvedEir(projection, LEASE_RATE);

            assertThat(projection.assembly().initialCarryingAmount())
                .isEqualTo(Money.inr("988000"));
            assertThat(effectiveAnnualPercent(eir)).isEqualByComparingTo(bd("12.377404"));
            assertThat(eir.effectiveAnnual())
                .as("INV-2: above the 11.571884% contractual effective rate")
                .isGreaterThan(LEASE_RATE.effectiveAnnual());

            InvariantResult inv2 = InvariantChecks.feeSignOrdering(
                eir, LEASE_RATE, projection.assembly().netIntegralFee(),
                parGapOf(projection, LEASE_RATE));
            assertThat(inv2.satisfied()).isTrue();
            assertTerminalCarryingAmountIsZero(eirLeg(projection, eir));
        }

        @Test
        @DisplayName("O8 cost paid 12,000: GCA 1,012,000, EIR 10.784759%, below the contractual rate")
        void fixtureO8WithACostPaid() {
            // And the mirror. A cost paid records the asset ABOVE par, so the yield falls
            // below the coupon. Swap the sign convention on integral postings anywhere
            // upstream and this ordering inverts while both rates stay plausible — which is
            // the whole reason INV-2 is stated as an ordering and not as a magnitude.
            BlueprintProjection projection = project(List.of(
                FeePosting.paid("DEALER_PAYOUT", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL, "SELLING")));
            Rate eir = solvedEir(projection, LEASE_RATE);

            assertThat(projection.assembly().initialCarryingAmount())
                .isEqualTo(Money.inr("1012000"));
            assertThat(effectiveAnnualPercent(eir)).isEqualByComparingTo(bd("10.784759"));
            assertThat(eir.effectiveAnnual()).isLessThan(LEASE_RATE.effectiveAnnual());

            InvariantResult inv2 = InvariantChecks.feeSignOrdering(
                eir, LEASE_RATE, projection.assembly().netIntegralFee(),
                parGapOf(projection, LEASE_RATE));
            assertThat(inv2.satisfied()).isTrue();
            assertTerminalCarryingAmountIsZero(eirLeg(projection, eir));
        }

        @Test
        @DisplayName("O8 no integral amount: 11.571890% against a contractual 11.571884%, and INV-2 passes")
        void fixtureO8WithNoIntegralAmount() {
            // The third row, and the one that has to be read carefully. docs 09 § 6 quotes
            // 11.571890% against a contractual 11.571884%: a gap of 6.02e-8, which is the
            // paise-rounding artefact and not an engine defect. The rental is billed at
            // 28,024.31 against a true annuity of 28,024.30702715317, and that over-collects
            // 0.09 of present value over 36 periods.
            //
            // So INV-2's "equals it when there is none" is NOT exact equality — taken that
            // way it is not an invariant at all, because it fails on every zero-fee contract
            // in the book. But nor is it unresolvable: the 0.09 of over-collected present
            // value IS the schedule's par gap, and once INV-2 measures that gap instead of
            // guessing at a rate band, the artefact stops being noise the check has to
            // tolerate and becomes the thing the check reads the sign off. A nil fee against
            // a −0.09 gap must yield a rate ABOVE the coupon, and does.
            BlueprintProjection projection = project(List.of());
            Rate eir = solvedEir(projection, LEASE_RATE);

            assertThat(projection.assembly().initialCarryingAmount()).isEqualTo(ASSET);
            assertThat(projection.assembly().netIntegralFee().isZero()).isTrue();
            assertThat(effectiveAnnualPercent(eir)).isEqualByComparingTo(bd("11.571890"));
            assertThat(effectiveAnnualPercent(LEASE_RATE))
                .as("the contractual effective rate: (1 + 0.11/12)^12 - 1")
                .isEqualByComparingTo(bd("11.571884"));

            BigDecimal spread = eir.effectiveAnnual().subtract(LEASE_RATE.effectiveAnnual());
            assertThat(spread.abs().round(new MathContext(3)))
                .as("the rounded-rental artefact, to three significant figures")
                .isEqualByComparingTo(bd("0.0000000602"));
            // The artefact is no longer hidden by a band — it is the schedule's own par gap,
            // measured, and with a nil fee it is what SETS the ordering: sign(EIR - coupon)
            // = sign(F - G) = sign(-G). A rate band declared this unresolvable; the gap
            // explains it, which is a stronger statement about the same number.
            Money gap = parGapOf(projection, LEASE_RATE);
            assertThat(spread.signum())
                .as("a nil fee orders by the negated par gap, not by nothing")
                .isEqualTo(gap.negate().signum());

            assertThat(gap.atPresentationScale())
                .as("the over-collected present value, measured rather than tolerated")
                .hasToString("INR -0.09");

            InvariantResult inv2 = InvariantChecks.feeSignOrdering(
                eir, LEASE_RATE, projection.assembly().netIntegralFee(), gap);
            assertThat(inv2.id()).isEqualTo(InvariantId.INV_2);
            assertThat(inv2.satisfied())
                .as("INV-2 passes at a nil fee; it does not demand exact equality")
                .isTrue();
            // And it passes on the ORDERING route, not by abstention. 0.09 of over-collected
            // present value is nine times ORDERING_EPSILON, so the check resolves the sign
            // rather than declining to: the +6.02e-8 spread is what a −0.09 gap should
            // produce. Under the old 1e-6 p.a. rate band the same number was reported as
            // unresolvable — a true statement that explained nothing. Pinned on the detail
            // string because it is the difference between the two baselines, and a revert to
            // the coupon-annualised one would land back in the band branch silently.
            assertThat(inv2.detail())
                .as("the gap resolves the ordering; it does not excuse it")
                .contains("less par gap INR -0.09")
                .doesNotContain("inside the resolvable band");

            // And the equality that must NOT be asserted, pinned so nobody adds it back.
            // A control that fires on every zero-fee contract in the book is worse than no
            // control, because it teaches a reviewer to dismiss INV-2 breaches.
            assertThat(eir.effectiveAnnual())
                .as("the two rates are close, and they are not equal")
                .isNotEqualByComparingTo(LEASE_RATE.effectiveAnnual());

            assertTerminalCarryingAmountIsZero(eirLeg(projection, eir));
        }

        @Test
        @DisplayName("the three rates are ordered by the sign of the net integral amount")
        void theThreeRatesAreOrderedByTheFeeSign()  {
            // The assertion the three rows exist to make. Individually each rate is
            // plausible; only the ordering detects a sign error in fee classification, and
            // it detects it for the price of a comparison. This is the cheapest place in
            // the engine to catch a whole class of classification errors.
            Rate received = solvedEir(project(List.of(
                FeePosting.received("LEASE_ARRANGEMENT_FEE", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL))), LEASE_RATE);
            Rate none = solvedEir(project(List.of()), LEASE_RATE);
            Rate paid = solvedEir(project(List.of(
                FeePosting.paid("DEALER_PAYOUT", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL, "SELLING"))), LEASE_RATE);

            assertThat(received.effectiveAnnual()).isGreaterThan(none.effectiveAnnual());
            assertThat(none.effectiveAnnual()).isGreaterThan(paid.effectiveAnnual());
            assertThat(received.effectiveAnnual()).isGreaterThan(LEASE_RATE.effectiveAnnual());
            assertThat(paid.effectiveAnnual()).isLessThan(LEASE_RATE.effectiveAnnual());

            // The terminal lump kept its lease label all the way through the projector. A
            // lease residual booked as a balloon is a reconciliation break with no
            // arithmetic cause: every figure ties and the wrong asset is disclosed.
            FlowVector contractual = project(List.of()).projection().contractual();
            assertThat(contractual.flows().stream()
                .filter(flow -> flow.kind() == FlowKind.RESIDUAL_VALUE).toList())
                .hasSize(1);
            assertThat(contractual.flows().stream()
                .filter(flow -> flow.kind() == FlowKind.BALLOON).toList())
                .isEmpty();
        }
    }
}
