package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.PRINCIPAL;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.case1Fees;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ResiduePolicy;
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
import com.crisil.eir.calc.projection.blueprint.PrincipalProfile;
import com.crisil.eir.calc.projection.blueprint.ProductTemplates;
import com.crisil.eir.calc.projection.blueprint.RateProfile;
import com.crisil.eir.calc.projection.blueprint.ScheduleBlueprint;
import com.crisil.eir.calc.projection.blueprint.ScheduleCalendar;
import com.crisil.eir.calc.projection.blueprint.TemplateBasis;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures S1–S6 from {@code docs/09-cashflow-structures.md} § 6 — the
 * cash-flow structures, end to end.
 *
 * <p>These are merge gates in the same spirit as the nine reference cases, and they
 * are read the same way: <b>every figure asserted here is quoted from docs 09 § 6, or
 * is closed-form arithmetic stated in the comment that uses it.</b> Where the engine
 * disagrees with a figure the engine is wrong. The document says so itself — "where
 * either and a fixture in § 6 disagree, the fixture governs" — and moving an expected
 * value to match what the code produced is how a regression becomes a specification.
 *
 * <h2>What these six fixtures are for</h2>
 *
 * <p>One loan, six structures. The base throughout is reference case 1's own loan —
 * 1,000,000 at 12% nominal monthly, which is 1.00% per period and never 12% divided by
 * twelve, over 24 monthly periods from 1 April 2026, with 15,000 of processing fee
 * received against 10,000 of DSA commission paid, netting to 5,000 of integral fee
 * income and an initial carrying amount of 995,000. Contractual effective rate
 * 12.682503% p.a.
 *
 * <p>Holding the loan fixed is the whole design. Reference case 1 bills that loan as a
 * level annuity and solves 13.248094% p.a.; the six fixtures below change exactly one
 * dimension of the blueprint each and nothing else, so a rate that moves has moved
 * because a structural dimension changed and not because the instrument did:
 *
 * <pre>
 *   S1  equal principal                       13.268805%   +58.6 bp over contractual
 *   S2  balloon 400,000 at period 24          13.112667%   +43.0 bp
 *   S3  step-up +10% every 6 periods          13.216248%   +53.4 bp
 *   S4  6-period principal holiday, serviced  13.149467%   +46.7 bp
 *   S5  12-period full holiday, capitalised   12.965053%   +28.3 bp
 *   S6  12-period full holiday, simple        12.619315%    −6.3 bp
 * </pre>
 *
 * <p>The last line is the one worth reading twice. S6 receives the same 5,000 of net
 * fee income as the other five and still reports an EIR <em>below</em> the contractual
 * effective rate, because deferring interest simple rather than compounding it gives
 * away 6,056.91 of present value at the contractual rate. INV-2's convenient reading —
 * a fee received lifts the yield — is a statement about a par instrument and it does
 * not survive a structure that is itself away from par, which is exactly why this
 * package asserts orderings against stated figures rather than against a rule of thumb.
 *
 * <h2>What is asserted, and why in this shape</h2>
 *
 * <p>Four things per fixture, and they catch four different defects.
 *
 * <p><b>1. The rate</b>, to the eight places of percent docs 09 publishes it at, and
 * the effective annual to six. A single scalar that a mis-sized instalment, a
 * mis-shaped holiday or a rate applied to the wrong balance all move.
 *
 * <p><b>2. The stated intermediate amounts</b> — the instalment, the total cash, the
 * capitalised interest, the grown balance. A rate can be right while the ladder behind
 * it is wrong in two offsetting ways, and it is the ladder that reconciles to the
 * lending system.
 *
 * <p><b>3. The roll-forward, row by row where docs 09 gives a row</b>, and TR-1 at the
 * end. A total can be right while every row inside it is wrong; that is the failure
 * mode reference case 9 exists to name.
 *
 * <p><b>4. An independent price check.</b> {@link #presentValueAtOnePercent(FlowVector)} discounts
 * the projected contractual flows at the contractual 1% per period. The engine has no
 * routine for it, so it cannot agree with a wrong instalment by construction, and it
 * catches the sizing errors no single instalment reveals — a balloon sized on the full
 * principal over-collects by the balloon's present value, a step ladder sized on its
 * first rung over-collects by whatever the ladder adds. On five of the six fixtures the
 * answer must be the 1,000,000 advanced; on S6 it must be 993,943.09, and that gap
 * <em>is</em> fixture S6.
 *
 * <h2>What is deliberately not asserted</h2>
 *
 * <p>No expected value below is computed in the test body from the inputs the engine
 * was given. The temptation is real on these fixtures — the roll-forward closing
 * balance is one multiplication away from the opening one — and an assertion built that
 * way passes however wrong both figures are. Where a figure had to be derived rather
 * than quoted, the derivation is arithmetic over docs 09's own published rate and is
 * written out in the comment so a reviewer can check it by hand.
 *
 * <p>Of the structure-specific invariants in docs 09 § 7, four do not apply to S1–S6
 * and are not asserted here: ST-6 needs a tranched disbursement, ST-7 and ST-9 need
 * optionality or a re-estimation, and ST-12 needs a classification failure. Asserting
 * them on a fixture that cannot breach them would read as coverage and gate nothing.
 * ST-3, ST-4, ST-5, ST-8, ST-10 and ST-11 do apply and are asserted, in
 * {@link StructureInvariants} where they span fixtures and inside a fixture where they
 * carry content there.
 */
@DisplayName("Golden fixtures S1-S6: the cash-flow structures, end to end")
class StructureFixturesTest {

    /** Twenty-four monthly periods after reference case 1's disbursement date. */
    private static final LocalDate STATED_MATURITY = LocalDate.of(2028, 4, 1);

    /** The due date of period 12 — where S5's and S6's holidays end. */
    private static final LocalDate PERIOD_12_DUE = LocalDate.of(2027, 4, 1);

    /** 1% per period, as a scalar, for the independent price check. */
    private static final BigDecimal ONE_PERCENT = bd("0.01");

    /**
     * The ACPIR 46(1) horizon for the fixtures whose ladder ends at stated maturity.
     *
     * <p>A separate input from expected life and never derived from it. It must be at
     * least the ladder's own length, which is why S5 and S6 carry
     * {@link #THIRTY_SIX_PERIODS} instead: an {@code EXTEND_TERM} holiday runs the
     * stated term <em>plus</em> the holiday.
     */
    private static final int TWENTY_FOUR_PERIODS = 24;

    /** The horizon for the two extended-term holidays: 24 stated plus a 12-period holiday. */
    private static final int THIRTY_SIX_PERIODS = 36;

    /**
     * The tolerance on the independent price check, in rupees.
     *
     * <p>Twenty-five paise, and it is not slack for the engine. The billed instalments
     * are rounded to the paise, so the ladder's own present value cannot land exactly
     * on par: a half-paise rounding on each billed instalment discounts to at most
     * {@code 0.005 x sum(1.01^-t)} over 24 monthly periods, which is
     * {@code 0.005 x 21.2434 = 0.1062}. The observed misses against a 1,000,000 target
     * are 0.0076 on S1, 0.0708 on S2, 0.1231 on S3, 0.0325 on S4, 0.0612 on S5 and
     * 0.0004 on S6.
     *
     * <p>S3 is the one that exceeds the single-level bound, and it should: its ladder
     * rounds <em>four</em> instalment levels, each of them the rounded product of an
     * already-rounded base, so the residues do not have to partially cancel the way one
     * repeated instalment's do. Twenty-five paise clears all six with room and still
     * refuses any real sizing error by four orders of magnitude — a balloon sized on
     * the full principal misses par by 315,026.45, and a step ladder sized on its first
     * rung by 152,000.
     */
    private static final Offset<BigDecimal> PRICE_CHECK_TOLERANCE = Offset.offset(bd("0.25"));

    // ================================================= the six blueprints

    /**
     * A plain monthly calendar: no business-day convention, no holidays, and the
     * day-of-month held rather than rolled to a month end.
     *
     * <p>Value date is the 1st, so every due date is the 1st and every period is a
     * whole month. That is what licenses the periodic index, and it is asserted rather
     * than assumed on each fixture — the interesting cases are S5 and S6, where twelve
     * periods bill nothing and the index survives only because the holiday still emits
     * a flow per period to declare the accrual boundary.
     */
    private static ScheduleCalendar plainMonthly() {
        return new ScheduleCalendar(
            ScheduleCalendar.Frequency.MONTHLY,
            ScheduleCalendar.BusinessDayConvention.NONE,
            Set.of(),
            ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
            List.of());
    }

    /**
     * The base loan with three dimensions supplied and the other five held at docs 09
     * § 6's defaults.
     *
     * <p>Single draw at inception, fixed at 1% per period, no options, contractual
     * expectation, plain monthly calendar, 30/360 bond basis,
     * {@code LMS_AUTHORITATIVE} residue. The residue policy matters: it derives nothing
     * and plugs nothing, so the instalments come out as docs 09 quotes them rather than
     * adjusted by a plug this fixture never mentioned.
     */
    private static ScheduleBlueprint compose(
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        int eclHorizonPeriods) {

        return new ScheduleBlueprint(
            PRINCIPAL,
            Money.INR,
            DISBURSEMENT,
            STATED_MATURITY,
            new DisbursementProfile.Single(DISBURSEMENT, PRINCIPAL),
            principal,
            servicing,
            moratorium,
            new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(
                "docs 09 § 6 states S1-S6 as contractual ladders; recorded as a policy choice"
                    + " rather than as an absence of assumption, because the two produce identical"
                    + " numbers and very different audit outcomes"),
            plainMonthly(),
            DayCountConvention.THIRTY_360_BOND,
            ResiduePolicy.LMS_AUTHORITATIVE,
            eclHorizonPeriods);
    }

    /** S1 — equal principal. Principal is the primitive; the instalment declines. */
    private static ScheduleBlueprint s1() {
        return compose(new PrincipalProfile.EqualPrincipal(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(), TWENTY_FOUR_PERIODS);
    }

    /** S2 — balloon 400,000 at period 24. */
    private static ScheduleBlueprint s2() {
        return compose(new PrincipalProfile.Balloon(Money.inr("400000")),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(), TWENTY_FOUR_PERIODS);
    }

    /** S3 — the instalment steps +10% every sixth period. */
    private static ScheduleBlueprint s3() {
        return compose(
            new PrincipalProfile.StepLadder(bd("1.10"), 6, PrincipalProfile.StepDirection.UP),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none(), TWENTY_FOUR_PERIODS);
    }

    /**
     * S4 — a six-period principal holiday with interest serviced, maturity held.
     *
     * <p>{@code COMPRESS_REMAINING} rather than {@code EXTEND_TERM} because docs 09 § 6
     * puts the EMI at periods 7–24: the holiday eats repayment periods and eighteen
     * instalments carry what 24 would have, which is the whole reason the instalment
     * rises to 60,982.05 from 47,073.47. Extending the term instead would bill 24
     * instalments from period 7 and produce a different rate from the same concession.
     */
    private static ScheduleBlueprint s4() {
        return compose(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(),
            new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING),
            TWENTY_FOUR_PERIODS);
    }

    /** S5 — a twelve-period full holiday with interest capitalising, term extended. */
    private static ScheduleBlueprint s5() {
        return compose(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.CapitalisedEachPeriod(),
            new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                Moratorium.MoratoriumTermEffect.EXTEND_TERM),
            THIRTY_SIX_PERIODS);
    }

    /**
     * S6 — the same holiday deferring interest simple to a lump at period 12.
     *
     * <p>One dimension apart from S5, and the settlement date is the end of the holiday
     * because ACPIR 9(6)(i) makes the deferred interest due once the holiday ends.
     */
    private static ScheduleBlueprint s6() {
        return compose(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.DeferredSimple(PERIOD_12_DUE),
            new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                Moratorium.MoratoriumTermEffect.EXTEND_TERM),
            THIRTY_SIX_PERIODS);
    }

    // ================================================= the pipeline

    /**
     * One fixture, projected, solved and rolled forward.
     *
     * <p>Held whole for the reason {@code ReferenceCaseFixtures.Baseline} is: no test
     * can then pair a rate solved over one vector against a roll-forward over another,
     * which produces a schedule that looks plausible and reconciles to nothing.
     *
     * @param blueprint the composed dimensions
     * @param projected everything the blueprint pipeline produced, the ladder included
     * @param solve     the solve over the expected leg
     * @param eir       the solved rate at storage precision
     * @param eirLeg    the EIR-leg roll-forward, with TR-1 asserted inside it
     */
    private record Fixture(
        ScheduleBlueprint blueprint,
        BlueprintProjection projected,
        SolveResult solve,
        Rate eir,
        AmortisationResult eirLeg) {

        ProjectionResult projection() {
            return projected.projection();
        }

        InstalmentLadder ladder() {
            return projected.contractualLadder();
        }
    }

    /**
     * Blueprint to ladder to vectors to rate to roll-forward, through the published
     * seams and nothing else.
     *
     * <p>The projector is constructed without a solver, which is the correct form for
     * these six: none of them carries an embedded option, so there is no exercise-policy
     * divergence to quantify and the optionality stage has nothing to do.
     * {@link StructureInvariants#st8ExpectedLifeSitsWithinTheEclHorizon} runs the
     * solver-configured form separately, because ST-8 is only asserted where a
     * determination is actually made.
     *
     * <p>{@link SolveRequest#of} takes the target from
     * {@link ProjectionResult#initialCarryingAmount()} — the figure the ledger holds —
     * rather than from the vector's own inception leg. The two are the same number by
     * IC-1, and IC-1 is asserted separately on every fixture; solving against the
     * ledger's figure is what makes that assertion load-bearing instead of decorative.
     */
    private static Fixture project(ScheduleBlueprint blueprint) {
        BlueprintProjection projected =
            new BlueprintProjector(blueprint).projectBlueprint(case1Fees());
        ProjectionResult projection = projected.projection();
        SolveResult solve = new BracketedNewtonSolver().solve(SolveRequest.of(
            projection.expected(),
            projection.initialCarryingAmount(),
            projection.recommendedConvention(),
            ONE_PERCENT_MONTHLY.periodic()));
        Rate eir = solve.rateOrThrow();
        AmortisationResult eirLeg = AmortisationEngine.eirLeg(
            projection.initialCarryingAmount(), eir, projection.expected(),
            projection.recommendedConvention());
        return new Fixture(blueprint, projected, solve, eir, eirLeg);
    }

    // ================================================= derived quantities

    /**
     * The projected flows discounted at the contractual 1% per period.
     *
     * <p>Arithmetic the engine has no routine for, which is the point. Only the future
     * leg is discounted, so the answer is what the schedule is worth at the contractual
     * rate and the fee legs at the anchor stay out of it — the comparison is against the
     * 1,000,000 advanced, not against the 995,000 recorded.
     *
     * <p>Nothing here rounds before summing: each flow is discounted at working
     * precision and accumulated at working precision, because a present value assembled
     * from figures rounded to the paise is a different number and the difference grows
     * with the schedule.
     */
    private static BigDecimal presentValueAtOnePercent(FlowVector vector) {
        return presentValueAtOnePercent(vector, null);
    }

    /**
     * The same, over the flows whose kind is not {@code excluded}.
     *
     * @param excluded a kind to leave out, or null to discount everything
     */
    private static BigDecimal presentValueAtOnePercent(FlowVector vector, FlowKind excluded) {
        BigDecimal present = BigDecimal.ZERO;
        for (CashFlow flow : vector.future()) {
            if (excluded != null && flow.kind() == excluded) {
                continue;
            }
            BigDecimal factor = Precision.discountFactor(
                ONE_PERCENT, BigDecimal.valueOf(flow.periodIndex()));
            present = present.add(
                flow.amount().amount().multiply(factor, Precision.WORKING), Precision.WORKING);
        }
        return present;
    }

    /** Sum of the ladder's interest column over a period range, inclusive. */
    private static Money interestOver(InstalmentLadder ladder, int fromPeriod, int toPeriod) {
        Money sum = Money.zero(ladder.currency());
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            if (rung.periodIndex() >= fromPeriod && rung.periodIndex() <= toPeriod) {
                sum = sum.plus(rung.interest());
            }
        }
        return sum;
    }

    /** Sum of the ladder's principal column over a period range, inclusive. */
    private static Money principalOver(InstalmentLadder ladder, int fromPeriod, int toPeriod) {
        Money sum = Money.zero(ladder.currency());
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            if (rung.periodIndex() >= fromPeriod && rung.periodIndex() <= toPeriod) {
                sum = sum.plus(rung.principal());
            }
        }
        return sum;
    }

    /** Every result the projection recorded for an invariant. */
    private static List<InvariantResult> resultsFor(Fixture fixture, InvariantId id) {
        List<InvariantResult> matching = new ArrayList<>();
        for (InvariantResult result : fixture.projection().invariants()) {
            if (result.id() == id) {
                matching.add(result);
            }
        }
        return matching;
    }

    /**
     * Asserts that an invariant was recorded at all, and that every recording of it
     * passed.
     *
     * <p>Presence is asserted as well as satisfaction, and separately: an invariant that
     * quietly stopped being asserted reports the same {@code allInvariantsSatisfied()}
     * as one that passed, and the difference between "checked and clean" and "never
     * checked" is the entire value of a blocking invariant.
     */
    private static void assertRecordedAndSatisfied(Fixture fixture, InvariantId id) {
        List<InvariantResult> results = resultsFor(fixture, id);
        assertThat(results)
            .as("%s must be recorded on this projection, not merely happen to hold", id)
            .isNotEmpty();
        assertThat(results)
            .as("%s breaches: %s", id, results)
            .allMatch(InvariantResult::satisfied);
    }

    // ================================================= assertions

    /** One roll-forward row, at presentation scale, as docs 09 § 6 prints it. */
    private static void assertRow(
        Fixture fixture, int period, String opening, String interest, String cash, String closing) {

        AmortisationRow row = fixture.eirLeg().row(period);
        assertThat(paise(row.openingGca())).as("period %d opening GCA", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d EIR interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash received", period)
            .isEqualByComparingTo(bd(cash));
        assertThat(paise(row.closingGca())).as("period %d closing GCA", period)
            .isEqualByComparingTo(bd(closing));
    }

    /**
     * The three things every one of the six fixtures must satisfy, whatever its shape.
     *
     * <p>IC-1 at 995,000.00; the monthly periodic index licensed by the vector rather
     * than assumed; and TR-1 at exactly zero with the invariant recorded. Called from
     * each fixture rather than written once over a loop so that a failure names the
     * fixture that broke.
     */
    private static void assertCommonGates(Fixture fixture, int expectedPeriods) {
        assertThat(fixture.solve().status()).isEqualTo(SolveStatus.SOLVED);

        // IC-1: 1,000,000 advanced less the 5,000 net integral fee. Two independent
        // routes to one number — the assembler derived the carrying amount from the
        // disbursement profile and the postings, the vector carries the cash — and where
        // they disagree either a fee is misclassified or a non-cash item entered the
        // vector.
        assertThat(paise(fixture.projection().initialCarryingAmount()))
            .as("GCA at initial recognition")
            .isEqualByComparingTo(bd("995000.00"));
        assertThat(paise(fixture.projection().netCashAtInception()))
            .as("net cash outflow at inception, holder's sign")
            .isEqualByComparingTo(bd("-995000.00"));
        assertThat(fixture.projection().initialRecognitionCheck().id()).isEqualTo(InvariantId.IC_1);
        assertThat(fixture.projection().initialRecognitionCheck().satisfied()).isTrue();
        assertThat(fixture.projected().allInvariantsSatisfied())
            .as("projection breaches: %s", fixture.projection().invariants())
            .isTrue();

        // Every period a whole month on a calendar that cannot move a due date, so the
        // periodic index is exactly equivalent to actual dating and is licensed by the
        // vector's own flows. Checked, never assumed: a moratorium is one of the four
        // things that ordinarily voids it, and S5 and S6 keep it only because a holiday
        // period still emits a flow to declare its accrual boundary.
        assertThat(fixture.projected().conventionChoice().periodicIndexEligible()).isTrue();
        assertThat(fixture.projection().recommendedConvention())
            .isInstanceOf(TimeConvention.PeriodicIndex.class);
        assertThat(fixture.eir().periodsPerYear()).isEqualTo(12);
        assertThat(fixture.eir().periodic().scale())
            .as("a rate a ledger rolls forward with must be reproducible from the persisted figure")
            .isEqualTo(Precision.RATE_SCALE);

        // TR-1. The terminal balance is the one figure docs 09 § 6 states for all six
        // fixtures, and it is the assertion that the rate and the vector belong to each
        // other: a rate solved against a different schedule leaves a terminal balance
        // that is small, plausible and non-zero.
        assertThat(fixture.eirLeg().periods()).isEqualTo(expectedPeriods);
        assertThat(paise(fixture.eirLeg().terminalBalance()))
            .as("terminal EIR-leg carrying amount")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(fixture.eirLeg().invariants())
            .as("TR-1 must be recorded, not merely happen to hold")
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
        assertThat(fixture.eirLeg().isClean())
            .as("roll-forward breaches: %s", fixture.eirLeg().breaches())
            .isTrue();

        // And the chain itself, which no single row assertion covers: 24 or 36 rows each
        // opening exactly where the last one closed, every one a whole compounding
        // period. A broken chain with correct endpoints is arithmetically impossible and
        // a chain with a silently re-rounded balance is not.
        Money previousClosing = fixture.eirLeg().openingGca();
        for (AmortisationRow row : fixture.eirLeg().rows()) {
            assertThat(row.openingGca())
                .as("period %d opens where period %d closed", row.period(), row.period() - 1)
                .isEqualTo(previousClosing);
            assertThat(row.wholePeriod())
                .as("period %d is a whole compounding period under monthly indexing", row.period())
                .isTrue();
            previousClosing = row.closingGca();
        }
    }

    // ================================================================== S1

    @Nested
    @DisplayName("S1 - equal principal (EPI)")
    class S1EqualPrincipal {

        private final Fixture fixture = project(s1());

        @Test
        @DisplayName("EIR 1.04368895% per month = 13.268805% p.a.")
        void theRate() {
            assertCommonGates(fixture, 24);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("1.04368895"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("13.268805"));

            // The highest of the six, and it should be: equal principal repays fastest, so
            // the 5,000 net fee is recovered over the shortest average balance and lifts
            // the reported yield furthest. 13.268805% against a contractual 12.682503% is
            // 58.6 bp, where reference case 1's level annuity on the same loan and the same
            // fee is 56.6 bp.
            assertThat(fixture.eir().effectiveAnnual())
                .isGreaterThan(ONE_PERCENT_MONTHLY.effectiveAnnual());
            assertThat(effectiveAnnualPercent(ONE_PERCENT_MONTHLY))
                .isEqualByComparingTo(bd("12.682503"));
        }

        @Test
        @DisplayName("principal 41,666.67 per period; instalment 51,666.67 falling to 42,083.26")
        void theStatedInstalments() {
            InstalmentLadder ladder = fixture.ladder();

            // 1,000,000 / 24 = 41,666.6667, billed to the paise as 41,666.67 because
            // 41,666.6667 ties to nothing on a repayment schedule. Interest is then the
            // accrual on the opening balance and the instalment is their sum: 41,666.67 +
            // 10,000.00 = 51,666.67, declining as the balance falls.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(paise(ladder.rung(1).principal())).isEqualByComparingTo(bd("41666.67"));
            assertThat(paise(ladder.rung(1).interest())).isEqualByComparingTo(bd("10000.00"));
            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("51666.67"));

            // Twenty-three bills of 41,666.67 repay 958,333.41, so the last rung repays
            // 41,666.59 and not another 41,666.67, and bills 41,666.59 + 416.67 = 42,083.26
            // rather than the 42,083.34 an unrounded principal would produce.
            assertThat(paise(ladder.rung(24).principal())).isEqualByComparingTo(bd("41666.59"));
            assertThat(paise(ladder.rung(24).total())).isEqualByComparingTo(bd("42083.26"));
        }

        @Test
        @DisplayName("total interest 125,000.00 over 1,125,000.00 of cash")
        void theStatedTotals() {
            // Docs 09 § 6's total, and the reason the paise the bill rounds belong to the
            // interest column: 125,000.00 ties 1,125,000.00 of cash against 1,000,000 of
            // principal. Recording the raw accrual instead sums to 124,999.9908, publishes
            // as 124,999.99, and ties to nothing.
            assertThat(paise(fixture.ladder().totalInterest()))
                .isEqualByComparingTo(bd("125000.00"));
            assertThat(paise(fixture.ladder().totalCash()))
                .isEqualByComparingTo(bd("1125000.00"));
        }

        @Test
        @DisplayName("period 1: opening 995,000.00, interest 10,384.71, closing 953,718.04")
        void periodOne() {
            // Docs 09 § 6 states this row. It is also reproducible by hand from the
            // fixture's own published rate, which is why it is the row the document
            // publishes: 995,000.00 x 0.0104368895 = 10,384.7051, and 995,000.00 +
            // 10,384.71 - 51,666.67 = 953,718.04. Note the accretion is on 995,000 and not
            // on the 1,000,000 advanced — the fee is inside the carrying amount from
            // inception, which is what makes the EIR method the EIR method.
            assertRow(fixture, 1, "995000.00", "10384.71", "51666.67", "953718.04");
        }

        @Test
        @DisplayName("TR-1: period 24 closes at exactly zero")
        void periodTwentyFourClosesAtZero() {
            // 41,648.58 x 0.0104368895 = 434.68, and 41,648.58 + 434.68 - 42,083.26 = 0.00.
            // The opening balance is the engine's; the two figures derived from it are hand
            // arithmetic on the published rate and the published final instalment.
            assertRow(fixture, 24, "41648.58", "434.68", "42083.26", "0.00");
        }

        @Test
        @DisplayName("the declining ladder prices to the 1,000,000 advanced at the contractual 1%")
        void theLadderPricesToPar() {
            // The independent check. Equal principal is the one profile where the
            // instalment is not solved at all — it is principal plus accrual — so what this
            // catches is a balance rolled at the wrong precision: re-rounding the balance to
            // paise each period walks the interest column off by paise that accumulate, and
            // the price then misses par by rupees rather than by the 0.0076 the rounded
            // instalments account for.
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .isCloseTo(bd("1000000"), PRICE_CHECK_TOLERANCE);
        }
    }

    // ================================================================== S2

    @Nested
    @DisplayName("S2 - balloon 400,000 at period 24")
    class S2Balloon {

        private final Fixture fixture = project(s2());

        @Test
        @DisplayName("EIR 1.03207441% per month = 13.112667% p.a.")
        void theRate() {
            assertCommonGates(fixture, 24);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("1.03207441"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("13.112667"));

            // The lowest of the five par-priced fixtures, and for the mirror-image reason
            // S1 is the highest: a balloon repays slowest, so the fee is spread over the
            // largest average balance and lifts the yield least. 43.0 bp against S1's 58.6.
            assertThat(fixture.eir().effectiveAnnual())
                .isGreaterThan(ONE_PERCENT_MONTHLY.effectiveAnnual());
        }

        @Test
        @DisplayName("level instalment 32,244.08 with a final period of 432,244.08")
        void theStatedInstalments() {
            InstalmentLadder ladder = fixture.ladder();

            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("32244.08"));
            assertThat(paise(ladder.rung(23).total())).isEqualByComparingTo(bd("32244.08"));
            assertThat(paise(ladder.rung(24).total())).isEqualByComparingTo(bd("432244.08"));
        }

        @Test
        @DisplayName("ST-5: the ladder amortises to exactly 400,000, not to zero")
        void st5TheLadderRetainsItsTerminalLump() {
            // A balloon schedule that reaches zero has silently amortised the lump the
            // borrower still owes. The terminal is retained on the ladder and taken back out
            // by the assembler as its own BALLOON flow, so the cash total is identical
            // either way and a balloon stays distinguishable from a lease residual — which
            // are different assets to a controller even though the arithmetic that sizes
            // them is the same.
            assertThat(paise(fixture.ladder().terminalBalance()))
                .isEqualByComparingTo(bd("400000.00"));
            assertRecordedAndSatisfied(fixture, InvariantId.ST_5);

            List<CashFlow> balloon = new ArrayList<>();
            for (CashFlow flow : fixture.projection().contractual().future()) {
                if (flow.kind() == FlowKind.BALLOON) {
                    balloon.add(flow);
                }
            }
            assertThat(balloon).singleElement().satisfies(flow -> {
                assertThat(flow.periodIndex()).isEqualTo(24);
                assertThat(paise(flow.amount())).isEqualByComparingTo(bd("400000.00"));
            });
        }

        @Test
        @DisplayName("the instalments are sized on 684,973.55 — the principal less the balloon's PV of 315,026.45")
        void theInstalmentsAreSizedNetOfTheBalloonsPresentValue() {
            // Docs 09 § 6 puts the balloon's present value at 315,026.45, which is
            // 400,000 x 1.01^-24, so the annuity runs over 1,000,000 - 315,026.45 =
            // 684,973.55 and bills 32,244.08. This is that statement asserted rather than
            // recited: the instalment leg alone, discounted at the contractual 1% with the
            // balloon flow excluded, must come to 684,973.55.
            //
            // It is the assertion that catches the classic sizing error. Sizing the
            // instalments on the full 1,000,000 and adding the balloon on top bills
            // 47,073.47 a period, prices the instalment leg at 1,000,000 and the whole
            // schedule at 1,315,026.45, and every individual figure in the schedule still
            // looks like a plausible instalment on a plausible loan.
            assertThat(presentValueAtOnePercent(fixture.projection().contractual(), FlowKind.BALLOON))
                .as("PV of the 24 instalments at the contractual rate")
                .isCloseTo(bd("684973.55"), PRICE_CHECK_TOLERANCE);
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .as("instalments plus balloon price to the 1,000,000 advanced")
                .isCloseTo(bd("1000000"), PRICE_CHECK_TOLERANCE);
        }

        @Test
        @DisplayName("TR-1: the 432,244.08 final period closes the carrying amount at zero")
        void periodTwentyFourClosesAtZero() {
            // 427,828.57 x 0.0103207441 = 4,415.51, and 427,828.57 + 4,415.51 - 432,244.08
            // = 0.00. The 400,000 lump is the last thing the roll-forward consumes, so a
            // balloon dropped from the expected leg would leave the carrying amount 400,000
            // short at maturity rather than failing anywhere earlier.
            assertRow(fixture, 24, "427828.57", "4415.51", "432244.08", "0.00");
        }
    }

    // ================================================================== S3

    @Nested
    @DisplayName("S3 - step-up, +10% every 6 periods")
    class S3StepUp {

        private final Fixture fixture = project(s3());

        @Test
        @DisplayName("EIR 1.03978106% per month = 13.216248% p.a.")
        void theRate() {
            assertCommonGates(fixture, 24);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("1.03978106"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("13.216248"));
        }

        @Test
        @DisplayName("base instalment 40,861.10 stepping to 44,947.21, 49,441.93 and 54,386.12")
        void theStatedLadder() {
            InstalmentLadder ladder = fixture.ladder();

            assertThat(paise(ladder.rung(1).total())).isEqualByComparingTo(bd("40861.10"));
            assertThat(paise(ladder.rung(6).total())).isEqualByComparingTo(bd("40861.10"));
            assertThat(paise(ladder.rung(7).total())).isEqualByComparingTo(bd("44947.21"));
            assertThat(paise(ladder.rung(13).total())).isEqualByComparingTo(bd("49441.93"));
            assertThat(paise(ladder.rung(19).total())).isEqualByComparingTo(bd("54386.12"));
            assertThat(paise(ladder.rung(24).total())).isEqualByComparingTo(bd("54386.12"));

            // The contract steps the *bill*, not the unrounded base: "+10% every six months"
            // of the 40,861.10 on the repayment schedule gives 40,861.10 x 1.21 = 49,441.931
            // -> 49,441.93 and x 1.331 = 54,386.1241 -> 54,386.12. Stepping the unrounded
            // 40,861.104010 instead gives 49,441.94 and 54,386.13, and nobody was ever
            // billed the unrounded base for the step to be a percentage of. Two paise, and
            // the fixture governs. The two figures are excluded by the equalities above and
            // by nothing else: x == 49,441.93 implies x != 49,441.94 at presentation scale
            // for every possible x, so the two `isNotEqualByComparingTo` lines that stood
            // here could not fail on any input while the equalities held. They read as a
            // second, independent gate on the choice of stepping basis and were not one:
            // the equalities are the gate, and they are the only one there can be, because
            // two paise on four instalment levels move no rate and no total that any other
            // assertion in this class pins.
        }

        @Test
        @DisplayName("total cash 1,137,818.16")
        void theStatedTotal() {
            // 6 x (40,861.10 + 44,947.21 + 49,441.93 + 54,386.12) = 6 x 189,636.36.
            assertThat(paise(fixture.ladder().totalCash()))
                .isEqualByComparingTo(bd("1137818.16"));
        }

        @Test
        @DisplayName("the step ladder prices to par, so it was sized over the ladder and not over its first rung")
        void theLadderPricesToPar() {
            // The base is solved by summation over f^k(t) D_t rather than from a closed
            // form, because the closed form exists only for a step at every period and this
            // product steps every sixth one. A ladder sized on its first rung — the natural
            // implementation error — bills 47,073.47 rising to 62,674.79 and over-collects
            // by whatever the ladder adds, which is 15.2% of the principal here and shows up
            // nowhere in any single instalment.
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .isCloseTo(bd("1000000"), PRICE_CHECK_TOLERANCE);
        }

        @Test
        @DisplayName("TR-1: period 24 closes at exactly zero")
        void periodTwentyFourClosesAtZero() {
            // 53,826.44 x 0.0103978106 = 559.68, and 53,826.44 + 559.68 - 54,386.12 = 0.00.
            assertRow(fixture, 24, "53826.44", "559.68", "54386.12", "0.00");
        }
    }

    // ================================================================== S4

    @Nested
    @DisplayName("S4 - principal moratorium, 6 periods interest-serviced")
    class S4PrincipalMoratorium {

        private final Fixture fixture = project(s4());

        @Test
        @DisplayName("EIR 1.03481316% per month = 13.149467% p.a.")
        void theRate() {
            assertCommonGates(fixture, 24);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("1.03481316"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("13.149467"));
        }

        @Test
        @DisplayName("interest serviced 10,000.00 for six periods with the principal untouched")
        void theHolidayServicesItsInterestAndRepaysNothing() {
            InstalmentLadder ladder = fixture.ladder();

            // A principal-only holiday says when *principal* repayment begins; the interest
            // leaves as cash exactly as it falls due, so nothing is in arrears and nothing
            // capitalises. 1,000,000 x 0.01 = 10,000.00 a period, six times, on a balance
            // that has not moved.
            for (int period = 1; period <= 6; period++) {
                assertThat(paise(ladder.rung(period).total()))
                    .as("period %d bills the accrual alone", period)
                    .isEqualByComparingTo(bd("10000.00"));
                assertThat(paise(ladder.rung(period).principal()))
                    .as("period %d repays no principal", period)
                    .isEqualByComparingTo(bd("0.00"));
                assertThat(paise(ladder.rung(period).balanceAfter()))
                    .as("the contractual balance after period %d", period)
                    .isEqualByComparingTo(bd("1000000.00"));
            }
            assertThat(paise(interestOver(ladder, 1, 6)))
                .as("six serviced periods at 10,000.00")
                .isEqualByComparingTo(bd("60000.00"));
        }

        @Test
        @DisplayName("EMI 60,982.05 for periods 7-24")
        void theCompressedInstalment() {
            InstalmentLadder ladder = fixture.ladder();

            // Maturity is held, so the holiday eats repayment periods: eighteen instalments
            // carry what 24 would have. A = 1,000,000 x 0.01 / (1 - 1.01^-18) = 60,982.0479,
            // billed 60,982.05, against the 47,073.47 the same loan bills over 24. That gap
            // is the entire content of COMPRESS_REMAINING, and a term effect defaulted to
            // EXTEND_TERM would bill 47,073.47 here and be wrong by 13,908.58 a month while
            // looking like a perfectly ordinary EMI.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(paise(ladder.rung(7).total())).isEqualByComparingTo(bd("60982.05"));
            assertThat(paise(ladder.rung(24).total())).isEqualByComparingTo(bd("60982.05"));
            assertThat(paise(ladder.totalCash()))
                .as("6 x 10,000.00 + 18 x 60,982.05")
                .isEqualByComparingTo(bd("1157676.90"));
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .isCloseTo(bd("1000000"), PRICE_CHECK_TOLERANCE);
        }

        @Test
        @DisplayName("the carrying amount RISES to 996,824.99 by period 6 while no principal repays")
        void theCarryingAmountRisesThroughTheHoliday() {
            // The counter-intuitive figure, and docs 09 § 6 singles it out: the fee is
            // amortising while no principal repays, so a loan on which the borrower is
            // paying and the balance is not falling has a gross carrying amount that goes
            // *up*. The mechanism is arithmetic rather than surprising — the EIR accretion
            // 1.03481316% exceeds the contractual 1.00% by exactly the fee's contribution,
            // and the cash bills only the contractual amount, so the difference stays in the
            // balance.
            //
            // Period 1 by hand on the published rate: 995,000.00 x 0.0103481316 = 10,296.39,
            // and 995,000.00 + 10,296.39 - 10,000.00 = 995,296.39. The other five rows
            // continue it and compound to 996,824.99, which is the figure the fixture
            // publishes.
            assertRow(fixture, 1, "995000.00", "10296.39", "10000.00", "995296.39");
            assertThat(paise(fixture.eirLeg().row(6).closingGca()))
                .as("gross carrying amount at the end of the holiday")
                .isEqualByComparingTo(bd("996824.99"));

            // Monotone through the holiday, which is the claim rather than the endpoint. A
            // single row asserted at the end can be reached by a path that dipped and
            // recovered, and a fee amortisation that ran backwards for two periods and
            // corrected would produce exactly that.
            Money previous = fixture.projection().initialCarryingAmount();
            for (int period = 1; period <= 6; period++) {
                Money closing = fixture.eirLeg().row(period).closingGca();
                assertThat(closing.amount())
                    .as("carrying amount must rise through holiday period %d", period)
                    .isGreaterThan(previous.amount());
                previous = closing;
            }

            // And it turns over the moment principal starts repaying: period 7 bills
            // 60,982.05 against 10,315.28 of accretion, so the balance falls for the first
            // time. 996,824.99 x 0.0103481316 = 10,315.28, and 996,824.99 + 10,315.28 -
            // 60,982.05 = 946,158.22.
            assertRow(fixture, 7, "996824.99", "10315.28", "60982.05", "946158.22");
        }

        @Test
        @DisplayName("TR-1: period 24 closes at exactly zero")
        void periodTwentyFourClosesAtZero() {
            // 60,357.46 x 0.0103481316 = 624.59, and 60,357.46 + 624.59 - 60,982.05 = 0.00.
            assertRow(fixture, 24, "60357.46", "624.59", "60982.05", "0.00");
        }
    }

    // ================================================================== S5

    @Nested
    @DisplayName("S5 - full moratorium, interest capitalised (education loan / IDC)")
    class S5FullMoratoriumCapitalised {

        private final Fixture fixture = project(s5());

        @Test
        @DisplayName("EIR 1.02108050% per month = 12.965053% p.a.")
        void theRate() {
            assertCommonGates(fixture, 36);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("1.02108050"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("12.965053"));
        }

        @Test
        @DisplayName("the balance grows to 1,126,825.03, of which 126,825.03 is capitalised interest")
        void theHolidayCapitalisesIntoTheBalance() {
            InstalmentLadder ladder = fixture.ladder();

            // Twelve periods on which nothing is paid, so nothing is billed and the accrual
            // joins the balance: 1,000,000 x 1.01^12 = 1,126,825.0301. The compounding is
            // real, so period 2 accrues on 1,010,000 and not on 1,000,000 — a holiday that
            // accrued 10,000.00 twelve times would reach 1,120,000 and be fixture S6 under
            // fixture S5's name.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(paise(ladder.rung(1).interest())).isEqualByComparingTo(bd("10000.00"));
            assertThat(paise(ladder.rung(2).interest())).isEqualByComparingTo(bd("10100.00"));
            assertThat(paise(ladder.rung(12).balanceAfter()))
                .isEqualByComparingTo(bd("1126825.03"));
            assertThat(paise(interestOver(ladder, 1, 12)))
                .as("capitalised interest over the holiday")
                .isEqualByComparingTo(bd("126825.03"));
        }

        @Test
        @DisplayName("ST-3: -126,825.03 of holiday principal against +1,126,825.03 amortising sums to the advance")
        void st3TelescopesAcrossTheCapitalisation() {
            // ST-3 has real content on a capitalising instrument, which is why its two
            // components are asserted rather than the invariant flag alone: capitalised
            // interest is negative principal, so the holiday runs the principal column
            // 126,825.03 the wrong way and the 24 instalments then have to recover
            // 1,126,825.03 for the two to sum to the 1,000,000 advanced. Either figure alone
            // can be wrong; both being wrong in step requires the capitalisation and the
            // annuity to agree on the same error.
            assertThat(paise(principalOver(fixture.ladder(), 1, 12)))
                .isEqualByComparingTo(bd("-126825.03"));
            assertThat(paise(principalOver(fixture.ladder(), 13, 36)))
                .isEqualByComparingTo(bd("1126825.03"));
            assertRecordedAndSatisfied(fixture, InvariantId.ST_3);
        }

        @Test
        @DisplayName("then 24 EMIs of 53,043.57, and the term runs a year past stated maturity")
        void theAmortisingPhase() {
            InstalmentLadder ladder = fixture.ladder();

            // EXTEND_TERM is the contractual reading of an education loan: the borrower gets
            // the whole original amortisation term *after* the holiday, so a 24-period loan
            // with a 12-period holiday bills 24 instalments from period 13 and matures on
            // 1 April 2029 rather than the stated 1 April 2028. A = 1,126,825.0301 x 0.01 /
            // (1 - 1.01^-24) = 53,043.5661, billed 53,043.57, against the 47,073.47 the
            // ungrown balance would bill.
            assertThat(paise(ladder.rung(13).total())).isEqualByComparingTo(bd("53043.57"));
            assertThat(paise(ladder.rung(36).total())).isEqualByComparingTo(bd("53043.57"));
            assertThat(ladder.rung(36).dueOn()).isEqualTo(LocalDate.of(2029, 4, 1));
            assertThat(ladder.rung(36).dueOn()).isAfter(STATED_MATURITY);

            // Capitalisation at the contractual rate is value-neutral, and that is what
            // makes S5 the reference point S6 is read against: 1,126,825.03 discounted 12
            // periods at 1% is the 1,000,000 advanced, so the whole schedule still prices to
            // par despite twelve periods of nothing.
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .isCloseTo(bd("1000000"), PRICE_CHECK_TOLERANCE);
        }

        @Test
        @DisplayName("EIR carrying amount at period 12: 1,124,002.28")
        void theCarryingAmountAtTheEndOfTheHoliday() {
            // Docs 09 § 6 states this figure, and it is the one place the two accretions can
            // be told apart: the contract grew the balance to 1,126,825.03 at 1.00% a period
            // while the EIR leg grew the carrying amount to 995,000.00 x 1.0102108050^12 =
            // 1,124,002.28 at the solved rate on a base reduced by the fee. The 2,822.75
            // between them is the unamortised fee at that date, and a system that
            // capitalised at the EIR rate — or accreted at the contractual one — lands on
            // one of the two figures for the other's reason and reconciles to nothing.
            assertRow(fixture, 1, "995000.00", "10159.75", "0.00", "1005159.75");
            assertThat(paise(fixture.eirLeg().row(12).closingGca()))
                .as("EIR gross carrying amount at the end of the holiday")
                .isEqualByComparingTo(bd("1124002.28"));

            // The accretion continues through the holiday and the first instalment lands on
            // it: 1,124,002.28 x 0.0102108050 = 11,476.97, and 1,124,002.28 + 11,476.97 -
            // 53,043.57 = 1,082,435.68.
            assertRow(fixture, 13, "1124002.28", "11476.97", "53043.57", "1082435.68");
        }

        @Test
        @DisplayName("the twelve holiday periods each bill nothing and each still carry a row")
        void theHolidayPeriodsAreRowsRatherThanAGap() {
            // A holiday period emits one zero flow, and it has to. The zero declares the
            // accrual boundary, so the holiday shows as a row per period in the movement
            // schedule instead of as a twelve-period gap — and it is also what keeps the
            // vector periodic-index eligible, since that licence requires every period
            // ordinal from 1 to the last to be present. Emitting the +accrual and -accrual
            // components instead would net to the same present value and add two sign
            // changes a period to the multiple-root screen, reporting a single-rooted
            // instrument as potentially multi-rooted.
            for (int period = 1; period <= 12; period++) {
                assertThat(paise(fixture.eirLeg().row(period).cashReceived()))
                    .as("holiday period %d bills nothing", period)
                    .isEqualByComparingTo(bd("0.00"));
            }
            assertThat(fixture.projection().contractual().size())
                .as("one advance plus two fee postings, then 12 holiday rows and 24 instalments")
                .isEqualTo(3 + 12 + 24);
        }

        @Test
        @DisplayName("TR-1: period 36 closes at exactly zero")
        void periodThirtySixClosesAtZero() {
            // 52,507.43 x 0.0102108050 = 536.14, and 52,507.43 + 536.14 - 53,043.57 = 0.00.
            assertRow(fixture, 36, "52507.43", "536.14", "53043.57", "0.00");
        }

        @Test
        @DisplayName("ProductTemplates.educationLoan composes exactly this fixture")
        void theEducationLoanTemplateReproducesTheFixture() {
            // Docs 09 § 5 maps the education-loan family to LevelAnnuity +
            // FULL_INTEREST_CAPITALISED + EXTEND_TERM, and § 6 gives S5 as the figures that
            // family produces. This asserts the template is that composition rather than
            // merely described as it: a course-plus-grace holiday of 12 periods on the same
            // basis must solve the same rate to all eight places.
            //
            // The frame is supplied rather than defaulted because it is per-contract fact,
            // not product design — and the calendar in particular is this fixture's own, so
            // that a difference in the rate is a difference in the composed dimensions and
            // not in the dating.
            ScheduleBlueprint template = ProductTemplates.educationLoan(
                TemplateBasis.of(DISBURSEMENT, STATED_MATURITY, plainMonthly(),
                    ProductTemplates.termLoanDayCount(), THIRTY_SIX_PERIODS),
                PRINCIPAL,
                ONE_PERCENT_MONTHLY,
                9,
                3,
                "contractual life; ACPIR 9(6)(i) defers the instalments but grants no"
                    + " prepayment right, so there is no behavioural life to model");
            Fixture templated = project(template);

            assertThat(periodicPercent(templated.eir()))
                .isEqualByComparingTo(bd("1.02108050"));
            assertThat(effectiveAnnualPercent(templated.eir()))
                .isEqualByComparingTo(bd("12.965053"));
            assertThat(paise(templated.ladder().rung(13).total()))
                .isEqualByComparingTo(bd("53043.57"));
            assertThat(paise(templated.eirLeg().row(12).closingGca()))
                .isEqualByComparingTo(bd("1124002.28"));

            // Nine periods of course and three of grace, and the fixture's twelve-period
            // holiday: the split is a real distinction to the borrower and none at all to
            // the ladder, which is why the template sums them rather than carrying two
            // phases into the builder.
            assertThat(templated.blueprint().moratorium().periods()).isEqualTo(12);
            assertThat(templated.blueprint().moratorium().kind())
                .isEqualTo(Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED);
        }
    }

    // ================================================================== S6

    @Nested
    @DisplayName("S6 - full moratorium, interest deferred simple")
    class S6FullMoratoriumDeferredSimple {

        private final Fixture fixture = project(s6());

        @Test
        @DisplayName("EIR 0.99527906% per month = 12.619315% p.a.")
        void theRate() {
            assertCommonGates(fixture, 36);

            assertThat(periodicPercent(fixture.eir()))
                .as("EIR per month")
                .isEqualByComparingTo(bd("0.99527906"));
            assertThat(effectiveAnnualPercent(fixture.eir()))
                .as("EIR effective p.a.")
                .isEqualByComparingTo(bd("12.619315"));
        }

        @Test
        @DisplayName("the simple accrual is 120,000.00, settled as one lump at period 12")
        void theDeferredLumpSettlesAtTheEndOfTheHoliday() {
            InstalmentLadder ladder = fixture.ladder();

            // The accrual does not join the balance, so every holiday period accrues
            // 1,000,000 x 0.01 = 10,000.00 on the same untouched 1,000,000 — twelve times,
            // 120,000.00. ACPIR 9(6)(i): the interest becomes due once the holiday ends, so
            // rungs 1 to 11 bill nothing at all and rung 12 bills the whole lump while still
            // recording only its own 10,000.00 of interest.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(paise(ladder.rung(2).interest())).isEqualByComparingTo(bd("10000.00"));
            assertThat(paise(ladder.rung(12).balanceAfter()))
                .isEqualByComparingTo(bd("1000000.00"));
            assertThat(paise(interestOver(ladder, 1, 12)))
                .isEqualByComparingTo(bd("120000.00"));
            assertThat(paise(ladder.rung(11).total())).isEqualByComparingTo(bd("0.00"));
            assertThat(paise(ladder.rung(12).total())).isEqualByComparingTo(bd("120000.00"));
            assertThat(paise(ladder.rung(12).principal())).isEqualByComparingTo(bd("0.00"));
        }

        @Test
        @DisplayName("then 24 EMIs of 47,073.47 on the balance the holiday never grew")
        void theAmortisingPhaseBillsThePlainAnnuity() {
            // The whole difference from S5 in one figure. The balance never grew, so the
            // amortising phase runs over the original 1,000,000 and bills the plain
            // 24-period annuity — reference case 1's own EMI, on a loan that spent a year in
            // moratorium.
            assertThat(paise(fixture.ladder().rung(13).total()))
                .isEqualByComparingTo(bd("47073.47"));
            assertThat(paise(fixture.ladder().rung(36).total()))
                .isEqualByComparingTo(bd("47073.47"));
        }

        @Test
        @DisplayName("the schedule prices to 993,943.09 at the contractual rate, 6,056.91 below par")
        void theLadderPricesBelowPar() {
            // The economic content of S6, and the reason it cannot share a code path with
            // S5. The 24 instalments from period 13 discount back to 1,000,000 at period 12;
            // add the 120,000.00 lump and the whole schedule is worth 1,120,000 at period
            // 12, which is 1,120,000 x 1.01^-12 = 993,943.09 today. A lender who accepted
            // simple deferral instead of capitalisation gave away 6,056.91 of present value
            // at the contractual rate — S5's identical schedule prices to the full
            // 1,000,000 — and that is a real economic difference rather than a
            // presentational one.
            assertThat(presentValueAtOnePercent(fixture.projection().contractual()))
                .isCloseTo(bd("993943.09"), PRICE_CHECK_TOLERANCE);
        }

        @Test
        @DisplayName("the EIR is 6.3 bp BELOW the contractual rate even though the net fee is income")
        void theEirFallsBelowContractualDespiteFeeIncome() {
            // The most useful single result in this file, and the one that stops INV-2's
            // convenient reading from being applied as a rule. Every one of these six
            // fixtures receives the same 5,000 of net integral fee income, and on five of
            // them the asset is therefore recorded below par, accretes back up, and reports
            // an EIR above the contractual rate. Here it does not: 12.619315% against a
            // contractual 12.682503% is 6.3 bp the other way.
            //
            // Nothing is wrong. INV-2 orders the EIR against the contractual rate on an
            // instrument whose contractual cash flows price to par at that rate, and this
            // one does not — the simple deferral put the schedule 6,056.91 below par before
            // the fee was considered at all, and 5,000 of fee income does not recover it.
            //
            // FINDING, recorded here because this is the fixture that surfaces it.
            // InvariantChecks.feeSignOrdering states INV-2 as
            // sign(EIR_eff - contractual_eff) == sign(netIntegralFee), passing only inside
            // an ORDERING_EPSILON band of 1e-6. On S6 the spread is -6.3188e-4 against a
            // net fee of +5,000, which is 632 times the band, so a two-leg reconciliation
            // run over this fixture would report a BLOCKING INV-2 breach on an instrument
            // where every figure is correct. The blueprint pipeline does not build a
            // TwoLegResult, so nothing fails today; the hazard is live for whoever wires
            // one to a DeferredSimple structure. The fix is in the invariant's premise and
            // not in the fixture: INV-2 compares the EIR to the contractual rate as a
            // proxy for comparing the carrying amount to par, and the proxy holds only
            // where the contractual flows themselves price to par.
            assertThat(fixture.eir().effectiveAnnual())
                .isLessThan(ONE_PERCENT_MONTHLY.effectiveAnnual());

            // And the fee is unambiguously income, read off the ASSEMBLY rather than off
            // the constant. This half is load-bearing — the claim is an EIR below
            // contractual DESPITE net fee income — so it has to be the engine's netting
            // that is asserted: 15,000 of processing fee received less 10,000 of DSA
            // commission paid, both INTEGRAL, netted on the DeferredSimple structure
            // specifically. A line asserting the test constant
            // ReferenceCaseFixtures.NET_INTEGRAL_FEE against 5,000.00 stood here and
            // reduced to Money.inr("5000") equalling its own literal: sign-flip the
            // netting, misclassify the 10,000 SELLING cost as non-integral or drop the fee
            // legs from S6's vector entirely and it still passed.
            // Positive, so it is income and the INV-2 reading this fixture refutes is the
            // one that applies. Asserted as the signed figure rather than as a value and a
            // separate signum() call, because signum() of a quantity already pinned at
            // +5,000.00 cannot fail independently of the equality.
            assertThat(paise(fixture.projected().assembly().netIntegralFee()))
                .as("net integral fee as the assembler derived it: 15,000 received - 10,000 paid")
                .isEqualByComparingTo(bd("5000.00"));
        }

        @Test
        @DisplayName("the carrying amount stands at 1,000,562.19 once the lump is settled")
        void theCarryingAmountAfterTheLump() {
            // Period 1 by hand on the published rate: 995,000.00 x 0.0099527906 = 9,903.03,
            // and nothing is billed, so the carrying amount closes at 1,004,903.03. Twelve
            // periods of that accretion reach 995,000.00 x 1.0099527906^12 = 1,120,562.19,
            // and the 120,000.00 lump takes it to 1,000,562.19.
            //
            // Worth stating because it is the one row where the deferral's shape is visible
            // in the ledger: the carrying amount is above the 1,000,000 of principal
            // outstanding immediately after the borrower has settled a year of interest in
            // cash. A holiday that had capitalised instead would stand at 1,124,002.28 here
            // with nothing paid at all (S5).
            assertRow(fixture, 1, "995000.00", "9903.03", "0.00", "1004903.03");
            assertRow(fixture, 12, "1109519.37", "11042.81", "120000.00", "1000562.19");
        }

        @Test
        @DisplayName("TR-1: period 36 closes at exactly zero")
        void periodThirtySixClosesAtZero() {
            // 46,609.57 x 0.0099527906 = 463.90, and 46,609.57 + 463.90 - 47,073.47 = 0.00.
            assertRow(fixture, 36, "46609.57", "463.90", "47073.47", "0.00");
        }
    }

    // ============================================== the structure invariants

    @Nested
    @DisplayName("the structure-specific invariants of docs 09 section 7")
    class StructureInvariants {

        @Test
        @DisplayName("ST-3: every fixture's scheduled principal plus terminal equals the 1,000,000 advanced")
        void st3ScheduledPrincipalEqualsPrincipalAdvanced() {
            // Applies to every blueprint. Asserted here across all six rather than once per
            // fixture because the interesting variation is between them: S1 telescopes a
            // level principal column, S2 leaves 400,000 of it in the terminal balance, S4
            // has six rungs of zero, and S5 has twelve rungs of *negative* principal. One
            // implementation has to make all four tie.
            for (Fixture fixture : allSix()) {
                assertRecordedAndSatisfied(fixture, InvariantId.ST_3);

                Money scheduled = principalOver(fixture.ladder(), 1, fixture.ladder().length());
                assertThat(paise(scheduled.plus(fixture.ladder().terminalBalance())))
                    .as("scheduled principal plus terminal on %s", fixture.blueprint().describe())
                    .isEqualByComparingTo(bd("1000000.00"));
            }
        }

        @Test
        @DisplayName("ST-4: the holiday accrues 126,825.03 capitalising against 120,000.00 simple - a 6,825.03 difference")
        void st4MoratoriumInterestMatchesItsServicingBasis() {
            // ST-4's own words: capitalised interest during a moratorium equals the compound
            // accretion, deferred-simple equals the simple accrual, "and the two differ".
            // The two limbs and the difference, all three from docs 09 § 6, on the same loan
            // with the same twelve-period holiday and the same term effect — the one
            // dimension that differs is what happens to interest nobody paid.
            Fixture capitalised = project(s5());
            Fixture deferred = project(s6());

            assertThat(paise(interestOver(capitalised.ladder(), 1, 12)))
                .as("compound accretion: 1,000,000 x (1.01^12 - 1)")
                .isEqualByComparingTo(bd("126825.03"));
            assertThat(paise(interestOver(deferred.ladder(), 1, 12)))
                .as("simple accrual: 12 x 1,000,000 x 0.01")
                .isEqualByComparingTo(bd("120000.00"));

            // The 6,825.03 the two limbs differ by is the figure docs 09 § 6 prices the
            // servicing choice at, and it is stated here rather than asserted. An assertion
            // on the difference stood here and was arithmetically determined by the two
            // above it: once the limbs are pinned at 126,825.03 and 120,000.00 the
            // subtraction cannot come out anything else, so no input to the engine could
            // satisfy those two and fail it. It was the same shape as the GCA.times /
            // GCA.multiply cross-check this repo has already removed — two spellings of one
            // quantity compared to each other.
            //
            // What is asserted instead is the capitalised limb BELOW presentation scale,
            // which the paise assertion above cannot see. The closed form is
            //   1,000,000 x (1.01^12 − 1) = 126,825.0301319697206611,
            // 1.01^12 being 1.1268250301319697206611 exactly, so to six places the limb is
            // 126,825.030132. The engine reaches it by twelve unrounded accruals rather
            // than by a power — ScheduleBuilder's COMPOUND branch takes balance x rate at
            // working precision and adds it straight back to the balance, rounding
            // nothing — and this assertion is what says so. A capitalisation that rounded
            // each period's accrual to the paise before compounding it still bills
            // 126,825.03 and fails here, which is the ADR-0002 defect class in its exact
            // form: an intermediate rounded before it enters the next period's arithmetic.
            assertThat(Precision.round(interestOver(capitalised.ladder(), 1, 12).amount(), 6))
                .as("1,000,000 x (1.01^12 - 1) to six places, unrounded through all twelve periods")
                .isEqualByComparingTo(bd("126825.030132"));

            // And the rate consequence: 12.965053% against 12.619315% is 34.6 bp, which is
            // the figure docs 09 § 2.3 and § 6 both quote and the reason DeferredSimple
            // cannot share a code path with CapitalisedEachPeriod. Both operands are
            // published figures; the subtraction is stated so it can be checked by hand.
            BigDecimal divergenceBps = capitalised.eir().effectiveAnnualBps()
                .subtract(deferred.eir().effectiveAnnualBps());
            assertThat(divergenceBps.setScale(1, RoundingMode.HALF_UP))
                .as("(12.965053 - 12.619315) x 100")
                .isEqualByComparingTo(bd("34.6"));

            // NOTE. No engine routine publishes an InvariantResult carrying
            // InvariantId.ST_4, so unlike ST-3, ST-5, ST-8 and ST-10 there is nothing here
            // to read off the projection's invariant list — the two limbs are asserted
            // against the fixture's figures instead. Docs 09 § 7 lists ST-4 as asserted and
            // blocking, so that gap is a finding against the implementation and not against
            // this test; it is recorded here rather than papered over with a check that
            // reads like one.
        }

        @Test
        @DisplayName("ST-5: only the balloon retains a terminal amount; the other five amortise to zero")
        void st5OnlyABalloonAmortisesToATerminalAmount() {
            // ST-5 is stated for balloons and leases, and its content is a claim about both
            // directions. A balloon ladder that reaches zero has amortised a lump the
            // borrower still owes; a fully-amortising ladder that retains a terminal has
            // left principal outstanding after the last instalment. Asserting the second is
            // what makes the first mean something, because a builder that always plugged the
            // terminal to zero would satisfy five of these six.
            for (Fixture fixture : allSix()) {
                assertRecordedAndSatisfied(fixture, InvariantId.ST_5);
            }
            assertThat(paise(project(s2()).ladder().terminalBalance()))
                .isEqualByComparingTo(bd("400000.00"));
            for (Fixture fixture : List.of(
                project(s1()), project(s3()), project(s4()), project(s5()), project(s6()))) {
                assertThat(paise(fixture.ladder().terminalBalance()))
                    .as("%s amortises fully", fixture.blueprint().describe())
                    .isEqualByComparingTo(bd("0.00"));
            }
        }

        @Test
        @DisplayName("ST-8: expected life sits within the ECL horizon, and the two are separate inputs")
        void st8ExpectedLifeSitsWithinTheEclHorizon() {
            // ST-8 applies to every blueprint but is only *asserted* where an expected-life
            // determination is actually made, which needs a solver — quantifying a
            // divergence in basis points means discounting something. The six fixtures carry
            // no options, so the plain projector skips the stage entirely and ST-8 never
            // appears; that is why this test constructs the solver-configured form rather
            // than reading the fixtures' own invariant lists.
            for (ScheduleBlueprint blueprint : List.of(s1(), s2(), s3(), s4(), s5(), s6())) {
                BlueprintProjection projected =
                    new BlueprintProjector(blueprint, new BracketedNewtonSolver())
                        .projectBlueprint(case1Fees());

                assertThat(projected.hasExpectedLife())
                    .as("a determination must be recorded for %s", blueprint.describe())
                    .isTrue();
                assertThat(projected.expectedLife().chosenPolicy())
                    .as("no option is present, so contractual maturity is the only basis")
                    .isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
                assertThat(projected.expectedLife().chosenLifePeriods())
                    .isEqualTo(projected.contractualLadder().length());
                assertThat(projected.expectedLife().invariants())
                    .as("ST-8 must be recorded on %s", blueprint.describe())
                    .anyMatch(result -> result.id() == InvariantId.ST_8 && result.satisfied());

                // ST-7 must be absent, not merely satisfied. Its scope is optionality, and
                // an ST-7 result on an instrument with no option would either fail — flagging
                // a divergence that cannot exist — or pass on a single computed policy and
                // teach a reviewer that one policy is enough.
                assertThat(projected.expectedLife().invariants())
                    .noneMatch(result -> result.id() == InvariantId.ST_7);
                assertThat(projected.optionalityDivergenceBps())
                    .as("nothing to diverge from")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            }

            // The horizon is an input and never derived from the life, and S5 makes the
            // point falsifiable rather than rhetorical: its EXTEND_TERM holiday runs the
            // ladder to 36 periods, so a horizon reconstructed from the stated 24-period
            // term sits BELOW the expected life and ST-8 fails on an instrument where every
            // figure is correct. That is asserted here by composing exactly that
            // misconfiguration.
            //
            // An `s5().eclHorizonPeriods() == 36` line stood here carrying this paragraph's
            // claim, and it read back the THIRTY_SIX_PERIODS literal s5() had just supplied
            // — ScheduleBlueprint stores the horizon verbatim and validates only >= 1, so no
            // engine input could break it. The 36-period ladder length below is the half
            // that was always engine output, and it stays.
            assertThat(project(s5()).ladder().length()).isEqualTo(36);

            ScheduleBlueprint horizonFromTheStatedTerm = compose(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM),
                TWENTY_FOUR_PERIODS);
            ExpectedLifeDetermination understated =
                new BlueprintProjector(horizonFromTheStatedTerm, new BracketedNewtonSolver())
                    .projectBlueprint(case1Fees())
                    .expectedLife();

            assertThat(understated.chosenLifePeriods())
                .as("the holiday extends the term, so the life is the ladder's 36 periods")
                .isEqualTo(36);
            InvariantResult breached = understated.invariants().stream()
                .filter(result -> result.id() == InvariantId.ST_8)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ST-8 result was recorded"));
            assertThat(breached.satisfied())
                .as("a 36-period life against a 24-period horizon is an ST-8 breach")
                .isFalse();
            assertThat(breached.detail())
                .contains("exceeds the ECL horizon 24")
                .contains("ACPIR 46(1)");
            // 36 - 24 = 12 periods of overrun, and the determination is still built carrying
            // the 36: clamping the life to the horizon would hide the misconfiguration
            // inside a figure that looked ordinary.
            assertThat(breached.deviation()).isEqualByComparingTo(bd("12"));
        }

        @Test
        @DisplayName("ST-10: the fixtures' calendar imposes no constraint, and the invariant is still recorded")
        void st10IsRecordedOnEveryFixture() {
            // ST-10 runs one way only: it forbids taking period ordinals as time on a
            // schedule whose periods are not equal, and says nothing about a calendar that
            // is uniform. All six fixtures bill on a plain monthly calendar with no
            // business-day convention and no holidays, so ST-10 cannot fail on them and the
            // vector's own flows decided the convention.
            //
            // What is asserted here is therefore *presence*, which can fail: an invariant
            // that quietly stopped being recorded reports the same allInvariantsSatisfied()
            // as one that passed. A breach on this calendar would be a defect in convention
            // selection rather than a data problem, and the projector accordingly throws on
            // it instead of reporting it — the falsifying inputs live with the convention
            // tests, not here.
            for (Fixture fixture : allSix()) {
                assertRecordedAndSatisfied(fixture, InvariantId.ST_10);
                assertThat(fixture.blueprint().calendar().admitsPeriodicIndexing()).isTrue();
            }
        }

        @Test
        @DisplayName("ST-11: a holiday whose kind and servicing disagree is refused at construction, naming both")
        void st11IncoherentCombinationsAreRefusedAtConstruction() {
            // ST-11 applies to every blueprint, and S5 and S6 are the pair that makes it
            // matter: the two differ in one dimension, and the moratorium kind and the
            // servicing profile each state half of the same fact. Crossing them produces a
            // blueprint that would build a ladder and solve a rate — and would silently be
            // one fixture's holiday priced on the other's arithmetic, 34.6 bp away from
            // either answer.
            assertThatIllegalArgumentException()
                .as("S5's capitalising holiday with servicing that does not compound")
                .isThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                        Moratorium.MoratoriumTermEffect.EXTEND_TERM),
                    THIRTY_SIX_PERIODS))
                .withMessageContaining("ST-11")
                .withMessageContaining("FULL_INTEREST_CAPITALISED")
                .withMessageContaining("SERVICED_EACH_PERIOD");

            assertThatIllegalArgumentException()
                .as("S6's deferring holiday with servicing that compounds")
                .isThrownBy(() -> compose(new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.CapitalisedEachPeriod(),
                    new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                        Moratorium.MoratoriumTermEffect.EXTEND_TERM),
                    THIRTY_SIX_PERIODS))
                .withMessageContaining("ST-11")
                .withMessageContaining("FULL_INTEREST_DEFERRED_SIMPLE")
                .withMessageContaining("CAPITALISED_EACH_PERIOD");

            // And S4's, from the other direction: a principal-only holiday suspends
            // principal, so it says nothing at all on an instrument that repays none until
            // maturity.
            assertThatIllegalArgumentException()
                .as("a principal holiday on a profile that repays no principal anyway")
                .isThrownBy(() -> compose(new PrincipalProfile.NoneUntilMaturity(),
                    new InterestServicing.CapitalisedEachPeriod(),
                    Moratorium.principalOnly(6),
                    TWENTY_FOUR_PERIODS))
                .withMessageContaining("ST-11")
                .withMessageContaining("PRINCIPAL_ONLY");
        }

        @Test
        @DisplayName("ST-6, ST-7, ST-9 and ST-12 do not apply to S1-S6, and are not asserted here")
        void theInapplicableInvariantsAreNotAsserted() {
            // Recorded as an assertion rather than as a comment because the *reason* they do
            // not apply is a property of these blueprints that can change: give S3 a
            // prepayment option and ST-7 applies; make S2's draw tranched and ST-6 does.
            // Each line below is the precondition the invariant needs, absent.
            for (Fixture fixture : allSix()) {
                ScheduleBlueprint blueprint = fixture.blueprint();

                // ST-6 needs a tranched profile; every fixture draws once at inception.
                assertThat(blueprint.disbursement())
                    .as("%s disburses once, so there are no tranches to sum", blueprint.describe())
                    .isInstanceOf(DisbursementProfile.Single.class);
                assertThat(resultsFor(fixture, InvariantId.ST_6)).isEmpty();

                // ST-7 and ST-12 need optionality; ST-12 needs a CONVERSION option or another
                // SPPI failure, which would have sent the instrument to FVTPL where no EIR
                // arises at all and no blueprint should have been built.
                assertThat(blueprint.isOptioned()).isFalse();
                assertThat(resultsFor(fixture, InvariantId.ST_7)).isEmpty();
                assertThat(resultsFor(fixture, InvariantId.ST_12)).isEmpty();

                // ST-9 is a claim about a re-estimation, and these six are initial
                // recognition only: the expected leg is the contractual leg, by recorded
                // policy rather than by omission.
                assertThat(fixture.projection().expected())
                    .isEqualTo(fixture.projection().contractual());
                assertThat(blueprint.behaviour())
                    .isInstanceOf(BehaviouralOverlay.Contractual.class);
                assertThat(resultsFor(fixture, InvariantId.ST_9)).isEmpty();
            }
        }

        /** The six fixtures, projected. */
        private List<Fixture> allSix() {
            return List.of(
                project(s1()), project(s2()), project(s3()),
                project(s4()), project(s5()), project(s6()));
        }
    }
}
