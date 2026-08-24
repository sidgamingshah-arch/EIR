package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Expected life under every applicable exercise policy, with the divergence
 * between them costed.
 *
 * <p>Stage four of the blueprint pipeline ([09 § 4]): the contractual ladder comes
 * in, and out comes an {@link ExpectedLifeDetermination} carrying the life under
 * the chosen policy plus the life, rate and first-period income under every other
 * policy the contract and the supplied judgements make available.
 *
 * <p><b>The engine does not choose.</b> It cannot, because the governing sources
 * disagree and the disagreement is a Board decision rather than a calculation: RBI's
 * Investment Directions amortise a discount or premium over residual
 * <em>contractual</em> maturity even for a callable security, while IFRS 9 Appendix
 * A runs over <em>expected</em> life considering the call. What the engine owes that
 * decision is arithmetic — compute the alternatives, quantify the difference, record
 * which one produced the published figure.
 *
 * <p><b>Why both, and not the prudent one.</b> The divergence has no fixed sign. On
 * the same ten-year 9% bond callable at par at year five:
 *
 * <table border="1">
 *   <caption>Fixtures O1 and O2 — one bond, two purchase prices</caption>
 *   <tr><th>Bought at</th><th>Policy</th><th>Life</th><th>EIR p.a.</th>
 *       <th>Year-1 income</th><th>Divergence</th></tr>
 *   <tr><td rowspan="2">1,050,000 (<b>premium</b>)</td>
 *       <td>{@code CONTRACTUAL_MATURITY}</td><td>10y</td><td>8.246545%</td>
 *       <td>86,588.72</td><td rowspan="2">49.1 bp / 5,153.16 <b>less</b></td></tr>
 *   <tr><td>{@code EARLIEST_CALL}</td><td>5y</td><td>7.755768%</td><td>81,435.57</td></tr>
 *   <tr><td rowspan="2">950,000 (<b>discount</b>)</td>
 *       <td>{@code CONTRACTUAL_MATURITY}</td><td>10y</td><td>9.806992%</td>
 *       <td>93,166.43</td><td rowspan="2">52.3 bp / 4,969.81 <b>more</b></td></tr>
 *   <tr><td>{@code EARLIEST_CALL}</td><td>5y</td><td>10.330130%</td><td>98,136.23</td></tr>
 * </table>
 *
 * <p>A premium amortised over five years instead of ten is expensed about twice as
 * fast, so the call basis reports <em>less</em> income. A discount accreted over
 * five instead of ten is recognised faster, so the call basis reports <em>more</em>.
 * <b>The sign of the divergence depends on whether the instrument was bought above
 * or below par.</b> Which means no blanket exercise policy can be assumed
 * conservative, the choice cannot be made once and globally on prudence grounds,
 * and there is no shortcut that lets the engine compute one basis and infer the
 * direction of the other. It has to be made per portfolio, with the number in front
 * of the committee — so the engine computes both and hands over the number.
 *
 * <p><b>What is refused.</b> Two things, loudly.
 *
 * <ul>
 *   <li>A {@code CONVERSION} option means SPPI failure means fair value through
 *       profit or loss means <em>no EIR at all</em> (ST-12). No life is returned;
 *       see {@link OptionalitySppiFailureException}.
 *   <li>A management judgement nobody made. {@code MOST_LIKELY_OUTCOME} and
 *       {@code PROBABILITY_WEIGHTED} are judgements, not derivations, and they
 *       arrive through {@link OptionalityJudgements}. Absent them, those policies
 *       are not computed rather than computed on a default.
 * </ul>
 *
 * <p><b>ST-7.</b> Every determination is built through
 * {@link ExpectedLifeDetermination#of} so that ST-7 and ST-8 are asserted rather
 * than assumed. On an optioned instrument at least two policies must be costed:
 * {@code CONTRACTUAL_MATURITY} is always one of them, because it is always
 * computable, so a single-alternative determination means every option-driven
 * policy was unavailable — and that is a finding to be recorded, not an exception
 * to be thrown.
 *
 * <p>Pure and clock-free like every other stage: exercise dates are inputs, the
 * value date is an input, and nothing here reads a calendar.
 */
public final class OptionalityResolver {

    /** Redemption at par — the default strike where no option prices the exit. */
    private static final BigDecimal PAR = BigDecimal.ONE;

    private OptionalityResolver() {
    }

    // ------------------------------------------------------------- the ST-12 gate

    /**
     * Refuses any instrument whose optionality takes it out of amortised cost
     * (ST-12).
     *
     * <p>Separate and public so that a caller holding an option schedule can run
     * the classification gate before commissioning the projection work that
     * precedes this resolver. {@code ScheduleBlueprint} already rejects a
     * {@code CONVERSION} at construction as a coherence conflict, so on the
     * blueprint path this check is unreachable — which is the point. The gate is
     * asserted at the place a life would otherwise be <em>returned</em>, because
     * that is the only place where being wrong produces a number rather than an
     * error, and a second assertion costs one branch.
     *
     * @throws OptionalitySppiFailureException where the schedule fails the gate
     */
    public static void requireSppiPass(OptionSchedule options) {
        Objects.requireNonNull(options, "options");
        for (OptionSchedule.EmbeddedOption option : options.options()) {
            if (option.type() == OptionSchedule.OptionType.CONVERSION) {
                throw new OptionalitySppiFailureException(option.type());
            }
        }
    }

    // -------------------------------------------------------------- the resolver

    /**
     * Resolves expected life with no management judgement supplied.
     *
     * <p>Computes the policies the contract determines by itself —
     * {@code CONTRACTUAL_MATURITY}, {@code EARLIEST_CALL} and
     * {@code NEXT_REPRICING}. The two judgement policies and the
     * economic-rationality model are not computed, because their inputs do not
     * exist and the engine invents neither. Where the blueprint's recorded policy
     * is one of those, this form raises
     * {@link OptionalityUnresolvedException} naming the missing input rather than
     * quietly publishing a different policy's figure.
     *
     * @param blueprint             the composed dimensions, including the recorded
     *                              exercise policy and the ACPIR 46(1) horizon
     * @param contractual           the contractual ladder, from {@code ScheduleBuilder}
     * @param initialCarryingAmount gross carrying amount at initial recognition —
     *                              positive for an asset, negative for a liability,
     *                              equal to the net cash flow at inception with the
     *                              sign reversed (invariant IC-1)
     * @param solver                the rate solver; a failed solve on the chosen
     *                              policy propagates rather than defaulting (4.3)
     */
    public static ExpectedLifeDetermination resolve(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        Money initialCarryingAmount,
        RateSolver solver) {
        return resolve(blueprint, contractual, initialCarryingAmount, solver,
            OptionalityJudgements.none());
    }

    /**
     * Resolves expected life under every policy the contract and the supplied
     * judgements make available.
     *
     * <p>Each alternative is solved on its own truncated or extended vector, and
     * each selects its own time convention through {@link ConventionSelector} —
     * checked on the vector that will actually be discounted, never assumed.
     * Truncation and extension never move the <em>first</em> flow, so the
     * first-period income figures compare across policies on the same accrual
     * window even where the conventions differ.
     *
     * @throws OptionalitySppiFailureException where a conversion option is present
     * @throws OptionalityUnresolvedException  where the recorded policy cannot be
     *     evaluated, including where its solve fails
     */
    public static ExpectedLifeDetermination resolve(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        Money initialCarryingAmount,
        RateSolver solver,
        OptionalityJudgements judgements) {

        requireLadder(blueprint, contractual, judgements);
        requireCarryingAmount(blueprint, initialCarryingAmount);
        Objects.requireNonNull(solver, "solver");
        requireSppiPass(blueprint.options());

        ExercisePolicy chosen = blueprint.options().exercisePolicy();
        List<Plan> plans = plans(blueprint, contractual, judgements);
        Plan chosenPlan = plan(plans, chosen);
        if (!chosenPlan.available()) {
            throw new OptionalityUnresolvedException(chosen, chosenPlan.unavailableBecause());
        }

        List<ExpectedLifeDetermination.LifeAlternative> alternatives = new ArrayList<>();
        for (Plan candidate : plans) {
            if (!candidate.available()) {
                continue;
            }
            ExpectedLifeDetermination.LifeAlternative costed =
                cost(blueprint, contractual, initialCarryingAmount, solver, candidate);
            if (costed == null) {
                // The solve produced no rate. On the chosen policy that is fatal — 4.3
                // names the silent fallback as the most damaging failure available to the
                // engine. On an unchosen one it is not: the alternative is simply absent,
                // and ST-7 reports that fewer than two policies were costed, which is the
                // honest disclosure of a divergence that could not be quantified.
                if (candidate.policy() == chosen) {
                    throw new OptionalityUnresolvedException(chosen,
                        "the solve over its " + candidate.lifePeriods()
                            + "-period vector produced no rate");
                }
                continue;
            }
            alternatives.add(costed);
        }

        return ExpectedLifeDetermination.of(
            chosen,
            chosenPlan.lifePeriods(),
            alternatives,
            blueprint.isOptioned(),
            blueprint.eclHorizonPeriods());
    }

    /**
     * Which policies can be evaluated on this contract with these judgements.
     *
     * <p>Always contains {@code CONTRACTUAL_MATURITY}: ignoring the options is
     * computable on every instrument, which is what makes it the reference basis
     * the divergence is measured against.
     */
    public static List<ExercisePolicy> applicablePolicies(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, OptionalityJudgements judgements) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(judgements, "judgements");
        List<ExercisePolicy> available = new ArrayList<>();
        for (Plan candidate : plans(blueprint, contractual, judgements)) {
            if (candidate.available()) {
                available.add(candidate.policy());
            }
        }
        return List.copyOf(available);
    }

    // ---------------------------------------------------------- the shaped ladder

    /**
     * The ladder truncated or extended to the chosen expected life.
     *
     * <p>What the behavioural adjuster and the flow assembler consume downstream.
     * Under {@code CONTRACTUAL_MATURITY} the contractual ladder is returned
     * unchanged — including a non-zero terminal balance, which a balloon or a
     * residual-value lease legitimately carries and which ST-5 exists to protect.
     *
     * <p>Where the life is shorter, the final rung absorbs the redemption, and the
     * split matters: the <b>par balance redeemed is principal</b> and any strike
     * premium or discount over par is <b>interest</b>. A call at 102 does not
     * return 102% of the principal advanced — it returns the principal and pays 2%
     * for the privilege of early termination. Booking the premium as principal
     * would break ST-3 on an instrument where nothing is wrong, and would misstate
     * the contractual interest leg by the same amount.
     *
     * <p>Rebuilt through {@link InstalmentLadder#of} so ST-3 and ST-5 are asserted
     * on the shaped ladder and not only on the contractual one.
     *
     * @throws OptionalityUnresolvedException under {@code PROBABILITY_WEIGHTED},
     *     which has no single ladder — see the four-argument form
     */
    public static InstalmentLadder ladderUnder(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        ExpectedLifeDetermination determination) {
        return ladderUnder(blueprint, contractual, determination, OptionalityJudgements.none());
    }

    /**
     * {@link #ladderUnder(ScheduleBlueprint, InstalmentLadder, ExpectedLifeDetermination)}
     * with the judgements that produced the determination.
     *
     * <p>{@code PROBABILITY_WEIGHTED} is refused here and the refusal is the
     * correct answer, not a gap. A probability-weighted determination has no
     * ladder: its flows are an expectation across outcomes, and every outcome has
     * a different redemption date. Asking for "the" ladder is asking which outcome
     * happened, and answering it — with the modal outcome, say, or the one nearest
     * the weighted life — would quietly replace an expectation with a scenario and
     * hand a rounded life to a stage that bills from it. Callers wanting the flows
     * the rate was solved over use {@link #flowsUnder}, which returns the weighted
     * vector itself.
     */
    public static InstalmentLadder ladderUnder(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        ExpectedLifeDetermination determination,
        OptionalityJudgements judgements) {

        Objects.requireNonNull(determination, "determination");
        requireLadder(blueprint, contractual, judgements);
        requireSppiPass(blueprint.options());

        ExercisePolicy policy = determination.chosenPolicy();
        Plan plan = requirePlan(blueprint, contractual, judgements, policy);
        if (plan.isWeighted()) {
            throw new OptionalityUnresolvedException(policy,
                "a probability-weighted determination has no single instalment ladder — its flows"
                    + " are an expectation across " + plan.outcomes().size() + " exercise outcomes,"
                    + " each with its own redemption date. Use flowsUnder to obtain the weighted"
                    + " vector the rate was solved over");
        }
        if (plan.lifePeriods() != determination.chosenLifePeriods()) {
            // The plan is re-derived here rather than carried on the determination, which
            // has no field for it. That is only safe if it re-derives to the same life, so
            // the equality is checked: a determination resolved under one set of judgements
            // and shaped under another would otherwise bill a ladder the published rate was
            // never solved over, and nothing downstream could detect it.
            throw new OptionalityUnresolvedException(policy,
                "the determination records a life of " + determination.chosenLifePeriods()
                    + " periods but these inputs resolve " + policy + " to " + plan.lifePeriods()
                    + "; the judgements that shaped the ladder are not the ones that produced the"
                    + " determination");
        }
        Reshaped reshaped = reshape(blueprint, contractual, plan);
        if (!reshaped.altered()) {
            return contractual;
        }
        return InstalmentLadder.of(
            contractual.currency(),
            reshaped.rungs(),
            blueprint.notional(),
            Money.zero(contractual.currency()));
    }

    /**
     * The flow vector a policy is solved over, inception leg included.
     *
     * <p>Exposed because it is the auditable artefact behind an alternative: a rate
     * with no visible vector is a rate that has to be trusted rather than
     * re-performed.
     */
    public static FlowVector flowsUnder(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        Money initialCarryingAmount,
        ExercisePolicy policy,
        OptionalityJudgements judgements) {

        requireLadder(blueprint, contractual, judgements);
        requireCarryingAmount(blueprint, initialCarryingAmount);
        requireSppiPass(blueprint.options());
        Plan plan = requirePlan(blueprint, contractual, judgements, policy);
        return vector(blueprint, contractual, initialCarryingAmount, plan);
    }

    // ------------------------------------------------------------------ costing

    /**
     * Solves one policy and measures its first-period income.
     *
     * <p>Returns {@code null} where the solve produced no rate. A rate is never
     * substituted here — not the contractual rate, not another policy's rate, not
     * zero.
     *
     * <p>First-period income comes from a real roll-forward rather than from
     * {@code opening x r}, so that a broken or fractional first period is measured
     * the way the ledger will measure it. It is the figure a controller actually
     * compares: a divergence of 49.1 basis points is abstract, and 5,153.16 of
     * year-one income is not.
     */
    private static ExpectedLifeDetermination.LifeAlternative cost(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        Money initialCarryingAmount,
        RateSolver solver,
        Plan plan) {

        FlowVector vector = vector(blueprint, contractual, initialCarryingAmount, plan);
        TimeConvention convention = ConventionSelector.select(
            vector, blueprint.calendar().frequency().periodsPerYear(), blueprint.dayCount());
        SolveResult solved = solver.solve(
            SolveRequest.atInception(vector, convention, seed(blueprint, convention, plan.policy())));
        if (!solved.hasRate()) {
            return null;
        }
        Rate eir = solved.rate();
        Money firstPeriodIncome = AmortisationEngine
            .segment(initialCarryingAmount, eir, vector, convention)
            .rows().get(0).eirInterest();
        return new ExpectedLifeDetermination.LifeAlternative(
            plan.policy(), plan.lifePeriods(), eir, firstPeriodIncome);
    }

    /**
     * The contractual rate, restated in the units the selected convention implies,
     * as the solver's seed.
     *
     * <p>A contractual seed converges in three or four iterations, and it is also
     * the reference point the multiple-root policy needs (4.4(2)). The frequency
     * check is explicit rather than left to {@link ConventionSelector#rateUnder}
     * so that the failure names the contract rather than arriving as a units
     * complaint from inside a numeric helper: a schedule whose calendar and rate
     * disagree about the compounding period is a configuration defect, and the
     * seed is merely where it surfaces.
     */
    private static BigDecimal seed(
        ScheduleBlueprint blueprint, TimeConvention convention, ExercisePolicy policy) {

        Rate contractualRate = blueprint.rate().rateForPeriod(1);
        if (convention instanceof TimeConvention.PeriodicIndex periodic
            && periodic.periodsPerYear() != contractualRate.periodsPerYear()) {
            throw new OptionalityUnresolvedException(policy,
                "the schedule compounds x" + periodic.periodsPerYear() + " on a "
                    + blueprint.calendar().frequency() + " calendar but the contractual rate is"
                    + " quoted x" + contractualRate.periodsPerYear()
                    + "; the calendar and the rate disagree about the compounding period");
        }
        return ConventionSelector.rateUnder(convention, contractualRate).periodic();
    }

    // -------------------------------------------------------------------- plans

    /**
     * Every policy, evaluated to a plan or to a reason it cannot be one.
     *
     * <p>Built in enum order so that two runs of the same contract produce the
     * alternatives in the same order, which is what makes a published divergence
     * comparable between runs (invariant DT-1).
     */
    private static List<Plan> plans(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, OptionalityJudgements judgements) {

        List<Plan> plans = new ArrayList<>();
        for (ExercisePolicy policy : ExercisePolicy.values()) {
            plans.add(planFor(blueprint, contractual, judgements, policy));
        }
        return List.copyOf(plans);
    }

    private static Plan plan(List<Plan> plans, ExercisePolicy policy) {
        for (Plan candidate : plans) {
            if (candidate.policy() == policy) {
                return candidate;
            }
        }
        throw new IllegalStateException("no plan was built for " + policy);
    }

    private static Plan requirePlan(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        OptionalityJudgements judgements,
        ExercisePolicy policy) {

        Objects.requireNonNull(policy, "policy");
        Plan plan = planFor(blueprint, contractual, judgements, policy);
        if (!plan.available()) {
            throw new OptionalityUnresolvedException(policy, plan.unavailableBecause());
        }
        return plan;
    }

    private static Plan planFor(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        OptionalityJudgements judgements,
        ExercisePolicy policy) {

        return switch (policy) {
            case CONTRACTUAL_MATURITY -> Plan.of(policy, statedLife(contractual), PAR, FlowKind.PRINCIPAL);
            case EARLIEST_CALL -> earliestExercise(blueprint, contractual, judgements);
            case NEXT_REPRICING -> nextRepricing(blueprint, contractual);
            case MOST_LIKELY_OUTCOME -> mostLikelyOutcome(blueprint, contractual, judgements);
            case PROBABILITY_WEIGHTED -> probabilityWeighted(contractual, judgements);
            case ECONOMIC_RATIONALITY -> economicRationality(blueprint, contractual, judgements);
        };
    }

    /**
     * The earliest-exercise plan: the RBI FAQ reading for perpetual debt, and the
     * IFRS 9 comparison basis for a callable security.
     *
     * <p>Two branches, because the two kinds of option resolve the term in opposite
     * directions.
     *
     * <ul>
     *   <li>A <b>shortening</b> option — call, put, prepayment, clean-up call — ends
     *       the instrument early, so the ladder truncates at the earliest such date
     *       and the balance is redeemed at that option's
     *       {@code strikePctOfPar x par}. Where several options compete, the
     *       earliest date wins: this policy is about the earliest date the premium
     *       can be taken away.
     *   <li>An <b>extension</b>, with no shortening option present, is the only
     *       exercise available, and exercising it <em>lengthens</em> the term. Fixture
     *       O5 is this case: a five-year bond extendable by three amortises the
     *       premium over eight years at 8.125769% instead of five at 7.755768%, 37.0
     *       basis points apart. The extended life is the maximum contractual period,
     *       which ST-8 then requires to sit within the ECL horizon — and on an
     *       extendable instrument the two are the same number, from ACPIR 46(1).
     * </ul>
     *
     * <p>Declined where the earliest exercise falls at or beyond the last rung.
     * There truncation is a no-op, and reporting an alternative identical to
     * contractual maturity under a different policy name would put a spurious zero
     * into the divergence rather than admitting the option is economically absent.
     */
    private static Plan earliestExercise(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, OptionalityJudgements judgements) {

        OptionSchedule options = blueprint.options();
        if (options.isEmpty()) {
            return Plan.unavailable(ExercisePolicy.EARLIEST_CALL,
                "the instrument carries no option, so there is nothing to exercise");
        }
        OptionSchedule.EmbeddedOption shortening = earliestShortening(options);
        if (shortening != null) {
            int period = periodOnOrBefore(contractual, shortening.firstExercise());
            if (period < 1) {
                return Plan.unavailable(ExercisePolicy.EARLIEST_CALL,
                    "the earliest exercise " + shortening.firstExercise() + " falls before the first"
                        + " due date " + contractual.rungs().get(0).dueOn() + ", so there is no rung"
                        + " to truncate at");
            }
            if (period >= statedLife(contractual)) {
                return Plan.unavailable(ExercisePolicy.EARLIEST_CALL,
                    "the earliest exercise " + shortening.firstExercise() + " falls at or beyond the"
                        + " final rung (period " + statedLife(contractual) + "), so truncation is a"
                        + " no-op and the option makes no difference to the rate");
            }
            return Plan.of(ExercisePolicy.EARLIEST_CALL, period, shortening.strikePctOfPar(),
                FlowKind.PRINCIPAL);
        }
        List<OptionSchedule.EmbeddedOption> extensions =
            options.ofType(OptionSchedule.OptionType.EXTENSION);
        if (extensions.isEmpty()) {
            return Plan.unavailable(ExercisePolicy.EARLIEST_CALL,
                "no option on this instrument resolves its term: " + options.options().size()
                    + " option(s) present, none of them a call, put, prepayment, clean-up call or"
                    + " extension");
        }
        int extended = judgements.extendedLifeOr(blueprint.eclHorizonPeriods());
        if (extended <= statedLife(contractual)) {
            return Plan.unavailable(ExercisePolicy.EARLIEST_CALL,
                "the extension option would run to period " + extended + ", which does not lengthen"
                    + " the " + statedLife(contractual) + "-period stated term. Either the extended"
                    + " life or the ACPIR 46(1) horizon it defaults to is understated");
        }
        return Plan.of(ExercisePolicy.EARLIEST_CALL, extended,
            extensions.get(0).strikePctOfPar(), FlowKind.PRINCIPAL);
    }

    /**
     * The B5.4.4 next-repricing shortcut, expressed over the ladder.
     *
     * <p>Mechanically identical to {@code RepricingShortcutProjector}: keep the
     * flows up to the reset and close the vector with a synthetic
     * {@link FlowKind#NOTIONAL_REDEMPTION} equal to the <em>contractual</em>
     * balance there. Contractual, not the EIR-leg balance, because the synthetic
     * flow stands in for what the borrower would owe if the instrument matured at
     * the reset, and using the EIR-leg balance would put the unamortised fee inside
     * the flow the fee is being amortised against.
     *
     * <p><b>At working precision, never presented.</b> A notional redemption is an
     * accounting construct nobody is ever billed, so there is no cash event at
     * which currency scale attaches and rounding it would round an intermediate,
     * which 1.2 forbids. On the Case 8 loan the rounding is worth five units in the
     * eighth decimal place of the monthly rate and a paisa on five closing
     * balances — bought in exchange for tidying a figure no counterparty receives.
     * The ladder's {@code balanceAfter} is already the contractual balance at
     * working precision, so the discipline is kept by taking it as it stands.
     */
    private static Plan nextRepricing(ScheduleBlueprint blueprint, InstalmentLadder contractual) {
        if (!blueprint.nextRepricingIsAvailable()) {
            return Plan.unavailable(ExercisePolicy.NEXT_REPRICING,
                "rate profile " + blueprint.rate().label() + " does not reprice to market by its own"
                    + " terms, so the shortcut has no repricing date to amortise to");
        }
        Optional<LocalDate> reset = nextReset(blueprint);
        if (reset.isEmpty()) {
            return Plan.unavailable(ExercisePolicy.NEXT_REPRICING,
                "rate profile " + blueprint.rate().label() + " carries no reset date after the value"
                    + " date " + blueprint.valueDate());
        }
        int period = periodOnOrBefore(contractual, reset.get());
        if (period < 1) {
            return Plan.unavailable(ExercisePolicy.NEXT_REPRICING,
                "the next reset " + reset.get() + " falls before the first due date "
                    + contractual.rungs().get(0).dueOn());
        }
        if (period >= statedLife(contractual)) {
            return Plan.unavailable(ExercisePolicy.NEXT_REPRICING,
                "the next reset " + reset.get() + " falls at or beyond the final rung (period "
                    + statedLife(contractual) + "), so there is nothing to truncate");
        }
        return Plan.of(ExercisePolicy.NEXT_REPRICING, period, PAR, FlowKind.NOTIONAL_REDEMPTION);
    }

    /**
     * The most-likely-outcome plan, from a date the engine did not choose.
     *
     * <p>A binary management judgement on whether the contingent event occurs, and
     * the engine's contribution is arithmetic only. There is deliberately no
     * heuristic here — not "the first call date if the bond is at a premium", not
     * "maturity unless the option is in the money". Any of those would be a model,
     * and a model masquerading as a judgement is worse than either: it carries none
     * of the Chapter V governance a model owes and none of the documented
     * reasoning a judgement owes.
     *
     * <p>The strike is read from whichever option's exercise window contains the
     * supplied date. A date no option covers redeems at par, which is the right
     * treatment for the common case of an assumed prepayment on a facility that
     * grants no formal option — and the engine does not second-guess the judgement
     * by refusing it.
     */
    private static Plan mostLikelyOutcome(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, OptionalityJudgements judgements) {

        if (!judgements.hasMostLikelyRedemption()) {
            return Plan.unavailable(ExercisePolicy.MOST_LIKELY_OUTCOME,
                "no redemption date was supplied. This policy is a management judgement on a binary"
                    + " outcome and the engine does not guess it — supply it through"
                    + " OptionalityJudgements.mostLikely");
        }
        LocalDate assumed = judgements.mostLikelyRedemption();
        int period = periodOnOrBefore(contractual, assumed);
        if (period < 1) {
            return Plan.unavailable(ExercisePolicy.MOST_LIKELY_OUTCOME,
                "the assumed redemption " + assumed + " falls before the first due date "
                    + contractual.rungs().get(0).dueOn());
        }
        return Plan.of(ExercisePolicy.MOST_LIKELY_OUTCOME, period,
            strikeAt(blueprint.options(), assumed), FlowKind.PRINCIPAL);
    }

    /**
     * The probability-weighted plan, from a distribution the engine did not build.
     *
     * <p>The right method for a homogeneous retail pool, and the rate is solved
     * over <b>probability-weighted cash flows</b> rather than as a weighted average
     * of per-outcome rates. The two are not the same number, and the flows are the
     * one IFRS 9 asks for: an expected value of a non-linear function of a rate is
     * not that function of the expected rate, so averaging rates would produce a
     * figure that reproduces no amortisation at all.
     *
     * <p>The reported life is the weighted average rounded to a whole period, and
     * it is a <em>label</em> rather than an input. Nothing in the arithmetic uses
     * it: rounding a life and then solving over that life would be a different, and
     * wrong, computation.
     */
    private static Plan probabilityWeighted(
        InstalmentLadder contractual, OptionalityJudgements judgements) {

        if (!judgements.hasWeightedOutcomes()) {
            return Plan.unavailable(ExercisePolicy.PROBABILITY_WEIGHTED,
                "no exercise distribution was supplied. This policy is expected value across"
                    + " outcomes and the weights are a management input — supply them through"
                    + " OptionalityJudgements.probabilityWeighted");
        }
        BigDecimal weightedLife = BigDecimal.ZERO;
        for (OptionalityJudgements.WeightedOutcome outcome : judgements.weightedOutcomes()) {
            int period = periodOnOrBefore(contractual, outcome.redemptionDate());
            if (period < 1) {
                return Plan.unavailable(ExercisePolicy.PROBABILITY_WEIGHTED,
                    "outcome dated " + outcome.redemptionDate() + " falls before the first due date "
                        + contractual.rungs().get(0).dueOn());
            }
            weightedLife = weightedLife.add(
                outcome.probability().multiply(BigDecimal.valueOf(period), Precision.WORKING),
                Precision.WORKING);
        }
        int life = Math.max(1, Precision.round(weightedLife, 0).intValueExact());
        return Plan.weighted(life, judgements.weightedOutcomes());
    }

    /**
     * The economic-rationality plan: exercise where the option is in the money past
     * a threshold.
     *
     * <p><b>A model under ACPIR Chapter V</b> — inventory, tiering, documentation
     * and independent validation before production use, which
     * {@link ExercisePolicy#isModelDriven()} flags. The assumptions are stated in
     * full here rather than buried in the arithmetic, because a model whose
     * assumptions cannot be written down cannot be validated:
     *
     * <ol>
     *   <li>The instrument is valued at the supplied prevailing market yield, on
     *       actual dates, as a market participant would value it at the exercise
     *       date. That valuation is not an EIR and never enters one.
     *   <li>A <b>call</b> — and a prepayment or clean-up call, which are calls held
     *       by the borrower — is exercised where the instrument <em>trades above the
     *       strike</em>. The obligor is then paying more than market for its money,
     *       so redeeming at the strike and refinancing is cheaper. Note what this
     *       asks of the bank: it is modelling <em>someone else's</em> rational
     *       behaviour, which is a different estimation problem from modelling its
     *       own and carries different evidence requirements. Holder identity is what
     *       tells an auditor which of the two is in play; it does not change the
     *       direction of the moneyness test, which the option <em>type</em> fixes.
     *   <li>A <b>put</b> is exercised where the instrument trades below the strike:
     *       the holder collects more by putting than by holding.
     *   <li>The threshold is a fraction of the strike amount and it is mandatory
     *       input, not a default. Frictionless exercise calls every premium bond on
     *       its first call date, which is not behaviour any market exhibits.
     *   <li>Only the <em>first</em> exercise date of each option is tested. Scanning
     *       a continuous American window is a lattice model — a different model, with
     *       a different validation burden — and pretending a single prevailing yield
     *       scans one would be the more misleading of the two errors.
     * </ol>
     *
     * <p>An {@code EXTENSION} makes the policy unavailable rather than being
     * ignored. Rational exercise of an extension depends on the forward curve at
     * the extension date, not on a yield prevailing today, and a single scalar
     * cannot express it. Refusing says so; silently dropping the extension would
     * return a life that looks like it priced the option and did not.
     *
     * <p>Where no option is in the money the plan runs to contractual maturity, and
     * the alternative is still recorded. "The model ran and found no exercise
     * rational" and "the model was never run" are the same number and different
     * audit outcomes.
     */
    private static Plan economicRationality(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, OptionalityJudgements judgements) {

        if (!judgements.hasMarketView()) {
            return Plan.unavailable(ExercisePolicy.ECONOMIC_RATIONALITY,
                "no market view was supplied. Moneyness is relative to a market and the exercise"
                    + " threshold is a modelling choice — supply both through"
                    + " OptionalityJudgements.economicRationality");
        }
        OptionSchedule options = blueprint.options();
        if (options.isEmpty()) {
            return Plan.unavailable(ExercisePolicy.ECONOMIC_RATIONALITY,
                "the instrument carries no option, so there is no exercise decision to model");
        }
        if (!options.ofType(OptionSchedule.OptionType.EXTENSION).isEmpty()) {
            return Plan.unavailable(ExercisePolicy.ECONOMIC_RATIONALITY,
                "an EXTENSION option is present. Rational exercise of an extension depends on the"
                    + " forward curve at the extension date rather than on a yield prevailing now,"
                    + " and a single prevailing yield cannot express it. This model is scoped to"
                    + " redemption options");
        }

        OptionalityJudgements.MarketView view = judgements.marketView();
        List<OptionSchedule.EmbeddedOption> ordered = new ArrayList<>(options.options());
        ordered.sort(Comparator.comparing(OptionSchedule.EmbeddedOption::firstExercise));
        for (OptionSchedule.EmbeddedOption option : ordered) {
            int period = periodOnOrBefore(contractual, option.firstExercise());
            if (period < 1 || period >= statedLife(contractual)) {
                continue;
            }
            Money par = contractual.rung(period).balanceAfter();
            BigDecimal strikeAmount = par.times(option.strikePctOfPar()).amount();
            BigDecimal continuation = continuationValue(blueprint, contractual, period, view);
            boolean exercise = switch (option.type()) {
                case CALL, PREPAYMENT, CLEAN_UP_CALL -> continuation.compareTo(
                    strikeAmount.multiply(
                        BigDecimal.ONE.add(view.exerciseThreshold()), Precision.WORKING)) > 0;
                case PUT -> continuation.compareTo(
                    strikeAmount.multiply(
                        BigDecimal.ONE.subtract(view.exerciseThreshold()), Precision.WORKING)) < 0;
                case EXTENSION, CONVERSION -> false;
            };
            if (exercise) {
                return Plan.of(ExercisePolicy.ECONOMIC_RATIONALITY, period,
                    option.strikePctOfPar(), FlowKind.PRINCIPAL);
            }
        }
        return Plan.of(ExercisePolicy.ECONOMIC_RATIONALITY, statedLife(contractual), PAR,
            FlowKind.PRINCIPAL);
    }

    /**
     * What the remaining contractual flows are worth at the exercise date, at the
     * prevailing market yield.
     *
     * <p>Discounted on actual dates under the instrument's own day count. A market
     * valuation is what a market participant would pay, and a market participant
     * prices on dates rather than on the schedule's period ordinals; the
     * periodic-index optimisation belongs to the EIR solve, where the rate and the
     * exponent are in matching units by construction.
     */
    private static BigDecimal continuationValue(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        int period,
        OptionalityJudgements.MarketView view) {

        LocalDate exerciseDate = contractual.rung(period).dueOn();
        List<CashFlow> remaining = new ArrayList<>();
        for (InstalmentLadder.Rung rung : contractual.rungs()) {
            if (rung.periodIndex() > period && !rung.total().isZero()) {
                remaining.add(CashFlow.of(rung.dueOn(), rung.periodIndex(), rung.total(), kindOf(rung)));
            }
        }
        if (!contractual.terminalBalance().isZero()) {
            InstalmentLadder.Rung last = contractual.rungs().get(contractual.rungs().size() - 1);
            remaining.add(CashFlow.of(last.dueOn(), last.periodIndex(),
                contractual.terminalBalance(), FlowKind.RESIDUAL_VALUE));
        }
        FlowVector vector = FlowVector.of(exerciseDate, contractual.currency(), remaining);
        return Discounting.presentValue(
            view.prevailingMarketYield().effectiveAnnual(),
            vector,
            new TimeConvention.ActualDate(blueprint.dayCount()));
    }

    // ---------------------------------------------------------------- reshaping

    /**
     * The ladder and the future flows a plan implies.
     *
     * <p>Both, from one place, because they have to agree: the ladder is what the
     * downstream stages bill from and the flows are what the rate was solved over,
     * and a redemption folded into one but not the other is a reconciliation break
     * with no single diagnosable cause.
     */
    private static Reshaped reshape(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, Plan plan) {

        int stated = statedLife(contractual);
        if (plan.lifePeriods() == stated) {
            return unaltered(contractual);
        }
        if (plan.lifePeriods() < stated) {
            return truncate(contractual, plan);
        }
        return extend(blueprint, contractual, plan);
    }

    /**
     * The contractual ladder, with any terminal balance collected as a flow.
     *
     * <p>The terminal balance is retained on the ladder rather than plugged (ST-5),
     * and it is nonetheless <em>value the holder receives</em> — a residual realised
     * on return of the asset, a balloon settled outside the instalment stream. A
     * vector that omitted it would discount to less than the carrying amount and
     * solve to a rate that is simply wrong, so it enters the flows as a
     * {@link FlowKind#RESIDUAL_VALUE} at the final due date. Truncation reaches the
     * same place from the other side: there the redemption subsumes the whole
     * outstanding balance.
     */
    private static Reshaped unaltered(InstalmentLadder contractual) {
        List<CashFlow> flows = new ArrayList<>();
        for (InstalmentLadder.Rung rung : contractual.rungs()) {
            flows.add(CashFlow.of(rung.dueOn(), rung.periodIndex(), rung.total(), kindOf(rung)));
        }
        if (!contractual.terminalBalance().isZero()) {
            InstalmentLadder.Rung last = contractual.rungs().get(contractual.rungs().size() - 1);
            flows.add(CashFlow.of(last.dueOn(), last.periodIndex(),
                contractual.terminalBalance(), FlowKind.RESIDUAL_VALUE));
        }
        return new Reshaped(contractual.rungs(), List.copyOf(flows), statedLife(contractual), false);
    }

    /**
     * Truncation at an exercise date, with the balance redeemed at the strike.
     *
     * <p>The kept rungs are the contractual rungs unchanged — the truncated
     * instalments are the same instalments the lender bills, which is the property
     * that lets the contractual leg still reconcile to the core banking system. The
     * final rung gains the redemption, split par-to-principal and premium-to-interest
     * for the reason
     * {@link #ladderUnder(ScheduleBlueprint, InstalmentLadder, ExpectedLifeDetermination)}
     * states.
     *
     * <p>The redemption travels as its own flow on the same date and period ordinal
     * as the instalment, and any strike premium as a third, so a notional redemption
     * stays identifiable as the accounting construct it is and the flow kinds carry
     * the same par/premium split the ladder does. Several flows sharing an ordinal is
     * exactly why {@code CashFlow} carries its period rather than deriving it from
     * position; they share a discounting exponent and therefore a single accrual
     * boundary, so the split costs nothing in the rate.
     */
    private static Reshaped truncate(InstalmentLadder contractual, Plan plan) {
        int life = plan.lifePeriods();
        InstalmentLadder.Rung base = contractual.rung(life);
        Money par = base.balanceAfter();
        Money premium = par.times(plan.strikePctOfPar().subtract(BigDecimal.ONE));
        Money redemption = par.plus(premium);

        List<InstalmentLadder.Rung> rungs = new ArrayList<>(contractual.through(life - 1));
        rungs.add(new InstalmentLadder.Rung(
            life,
            base.dueOn(),
            base.principal().plus(par),
            base.interest().plus(premium),
            base.total().plus(redemption),
            Money.zero(contractual.currency())));

        List<CashFlow> flows = new ArrayList<>();
        for (InstalmentLadder.Rung rung : contractual.through(life - 1)) {
            flows.add(CashFlow.of(rung.dueOn(), rung.periodIndex(), rung.total(), kindOf(rung)));
        }
        if (!base.total().isZero()) {
            flows.add(CashFlow.of(base.dueOn(), life, base.total(), kindOf(base)));
        }
        flows.add(CashFlow.of(base.dueOn(), life, par, plan.terminalKind()));
        if (!premium.isZero()) {
            flows.add(CashFlow.of(base.dueOn(), life, premium, FlowKind.INTEREST));
        }
        return new Reshaped(List.copyOf(rungs), List.copyOf(flows), life, true);
    }

    /**
     * Extension of the term past stated maturity.
     *
     * <p>Restricted to an instrument whose principal is repaid in one flow at
     * maturity — a bullet, a perpetual to its call, an interest-only note. That is
     * not a shortcut: extending an <em>amortising</em> ladder means re-solving the
     * instalment over the longer term, which changes every rung including the ones
     * already billed, and that is schedule construction rather than optionality.
     * Doing it here would put two different amortisation formulas in the codebase
     * and let them drift.
     *
     * <p>Interest over the extended periods is taken from the rate profile for
     * those periods rather than copied from the last contractual rung, so a step
     * coupon that steps during the extension is honoured. The stated-maturity rung
     * keeps its billed interest and gives up its principal repayment, which moves
     * to the extended maturity.
     */
    private static Reshaped extend(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, Plan plan) {

        InstalmentLadder.Rung finalRung =
            contractual.rungs().get(contractual.rungs().size() - 1);
        if (!contractual.terminalBalance().isZero()) {
            throw new OptionalityUnresolvedException(plan.policy(),
                "the contractual ladder still carries " + contractual.terminalBalance()
                    + " after its final rung, so what an extension would extend is undefined");
        }
        for (InstalmentLadder.Rung rung : contractual.rungs()) {
            if (rung.periodIndex() < finalRung.periodIndex() && !rung.principal().isZero()) {
                throw new OptionalityUnresolvedException(plan.policy(),
                    "period " + rung.periodIndex() + " repays " + rung.principal()
                        + " of principal, so this ladder amortises before maturity. Extending an"
                        + " amortising term re-solves every instalment, which belongs to schedule"
                        + " construction and not to the optionality resolver");
            }
        }

        Money par = finalRung.principal();
        List<InstalmentLadder.Rung> rungs =
            new ArrayList<>(contractual.through(finalRung.periodIndex() - 1));
        List<CashFlow> flows = new ArrayList<>();
        for (InstalmentLadder.Rung rung : contractual.through(finalRung.periodIndex() - 1)) {
            flows.add(CashFlow.of(rung.dueOn(), rung.periodIndex(), rung.total(), kindOf(rung)));
        }

        int life = plan.lifePeriods();
        LocalDate previousDue = finalRung.dueOn();
        for (int period = finalRung.periodIndex(); period <= life; period++) {
            LocalDate dueOn = period == finalRung.periodIndex()
                ? finalRung.dueOn()
                : rolledDueDate(blueprint, contractual, previousDue, period, plan.policy());
            Money interest = period == finalRung.periodIndex()
                ? finalRung.interest()
                : par.times(blueprint.rate().rateForPeriod(period).periodic());
            if (period < life) {
                rungs.add(new InstalmentLadder.Rung(period, dueOn,
                    Money.zero(contractual.currency()), interest, interest, par));
                if (!interest.isZero()) {
                    flows.add(CashFlow.of(dueOn, period, interest, FlowKind.INTEREST));
                }
            } else {
                Money premium = par.times(plan.strikePctOfPar().subtract(BigDecimal.ONE));
                Money redemption = par.plus(premium);
                rungs.add(new InstalmentLadder.Rung(period, dueOn, par,
                    interest.plus(premium), interest.plus(redemption),
                    Money.zero(contractual.currency())));
                if (!interest.plus(premium).isZero()) {
                    flows.add(CashFlow.of(dueOn, period, interest.plus(premium), FlowKind.INTEREST));
                }
                flows.add(CashFlow.of(dueOn, period, par, plan.terminalKind()));
            }
            previousDue = dueOn;
        }
        return new Reshaped(List.copyOf(rungs), List.copyOf(flows), life, true);
    }

    /**
     * The due date of an extended period, rolled from the last contractual one.
     *
     * <p>Explicit dates win where the calendar supplies them, because a bespoke or
     * seasonal roll cannot be inferred from a frequency and guessing it would put a
     * wrong date into a discounting exponent. Otherwise the frequency's own step is
     * applied and the business-day convention adjusts the result — which, by ST-10,
     * is then picked up by {@link ConventionSelector} as a reason to discount on
     * actual dates. A caller with a roll convention this does not reproduce supplies
     * {@code customDueDates} covering the extended term; date generation is the
     * schedule builder's responsibility and this is the minimum needed to extend a
     * bullet.
     */
    private static LocalDate rolledDueDate(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        LocalDate previousDue,
        int period,
        ExercisePolicy policy) {

        List<LocalDate> explicit = blueprint.calendar().customDueDates();
        if (explicit.size() >= period) {
            return explicit.get(period - 1);
        }
        if (blueprint.calendar().frequency().requiresExplicitDates()) {
            throw new OptionalityUnresolvedException(policy,
                "calendar " + blueprint.calendar().frequency() + " has no derivable period length"
                    + " and supplies only " + explicit.size() + " explicit due dates, so the due"
                    + " date of extended period " + period + " cannot be determined");
        }
        LocalDate rolled = switch (blueprint.calendar().frequency()) {
            case WEEKLY -> previousDue.plusWeeks(1);
            case FORTNIGHTLY -> previousDue.plusWeeks(2);
            case MONTHLY -> previousDue.plusMonths(1);
            case QUARTERLY -> previousDue.plusMonths(3);
            case HALF_YEARLY -> previousDue.plusMonths(6);
            case ANNUAL -> previousDue.plusMonths(12);
            case SEASONAL, CUSTOM -> throw new OptionalityUnresolvedException(policy,
                "calendar " + blueprint.calendar().frequency() + " requires explicit due dates");
        };
        LocalDate adjusted = blueprint.calendar().adjust(rolled);
        if (!adjusted.isAfter(previousDue)) {
            throw new OptionalityUnresolvedException(policy,
                "extended period " + period + " rolls to " + adjusted + ", which does not follow "
                    + previousDue + " on the " + contractual.currency().getCurrencyCode()
                    + " ladder; the calendar's adjustment collapsed a period");
        }
        return adjusted;
    }

    // ------------------------------------------------------------------- vectors

    /** The flow vector for a plan, inception leg included. */
    private static FlowVector vector(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        Money initialCarryingAmount,
        Plan plan) {

        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(blueprint.valueDate(), 0, initialCarryingAmount.negate(),
            FlowKind.DISBURSEMENT));
        flows.addAll(plan.isWeighted()
            ? weightedFutureFlows(blueprint, contractual, plan)
            : reshape(blueprint, contractual, plan).futureFlows());
        return FlowVector.of(blueprint.valueDate(), contractual.currency(), flows)
            .requireNoContingentFlows();
    }

    /**
     * Probability-weighted future flows: {@code sum_i w_i x CF_t^(i)}.
     *
     * <p>One vector, solved once, rather than one rate per outcome averaged
     * afterwards. Flows are keyed by date, period and kind so that an outcome's
     * redemption stays distinguishable from another outcome's coupon falling on the
     * same day — the weighting blends amounts, it does not blend what they are.
     *
     * <p>Accumulated in the outcomes' own ascending date order, which
     * {@link OptionalityJudgements} enforces. Summation at 28 digits is not
     * associative in the last place, so a run that added the same weighted flows in
     * a different order could publish a rate differing in the twelfth decimal — a
     * DT-1 replay failure caused by nothing but iteration order.
     */
    private static List<CashFlow> weightedFutureFlows(
        ScheduleBlueprint blueprint, InstalmentLadder contractual, Plan plan) {

        Map<FlowSlot, Money> weighted = new TreeMap<>(
            Comparator.comparing(FlowSlot::date)
                .thenComparingInt(FlowSlot::periodIndex)
                .thenComparing(slot -> slot.kind().ordinal()));
        for (OptionalityJudgements.WeightedOutcome outcome : plan.outcomes()) {
            int life = periodOnOrBefore(contractual, outcome.redemptionDate());
            Plan branch = Plan.of(plan.policy(), life, outcome.strikePctOfPar(), FlowKind.PRINCIPAL);
            for (CashFlow flow : reshape(blueprint, contractual, branch).futureFlows()) {
                FlowSlot slot = new FlowSlot(flow.date(), flow.periodIndex(), flow.kind());
                Money scaled = flow.amount().times(outcome.probability());
                Money running = weighted.get(slot);
                weighted.put(slot, running == null ? scaled : running.plus(scaled));
            }
        }
        List<CashFlow> flows = new ArrayList<>();
        for (Map.Entry<FlowSlot, Money> entry : weighted.entrySet()) {
            flows.add(CashFlow.of(entry.getKey().date(), entry.getKey().periodIndex(),
                entry.getValue(), entry.getKey().kind()));
        }
        return List.copyOf(flows);
    }

    // ------------------------------------------------------------------- helpers

    private static void requireLadder(
        ScheduleBlueprint blueprint,
        InstalmentLadder contractual,
        OptionalityJudgements judgements) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(judgements, "judgements");
        if (!contractual.currency().equals(blueprint.currency())) {
            throw new IllegalArgumentException(
                "the ladder is " + contractual.currency().getCurrencyCode() + " but the blueprint is "
                    + blueprint.currency().getCurrencyCode());
        }
    }

    /**
     * Checks the figure the whole determination is solved against.
     *
     * <p>Zero is refused rather than solved. Every policy's vector would discount
     * to a target of nothing, every solve would report no sign change, and the
     * determination would come back empty for a reason that has nothing to do with
     * optionality — so the failure is raised where the cause is still visible.
     */
    private static void requireCarryingAmount(ScheduleBlueprint blueprint, Money initialCarryingAmount) {
        Objects.requireNonNull(initialCarryingAmount, "initialCarryingAmount");
        if (!initialCarryingAmount.currency().equals(blueprint.currency())) {
            throw new IllegalArgumentException(
                "the initial carrying amount is "
                    + initialCarryingAmount.currency().getCurrencyCode() + " but the blueprint is "
                    + blueprint.currency().getCurrencyCode());
        }
        if (initialCarryingAmount.isZero()) {
            throw new IllegalArgumentException(
                "the initial carrying amount is zero, so every policy discounts to nothing and no"
                    + " rate is determined by any of them");
        }
    }

    /**
     * The period ordinal the contractual ladder ends on.
     *
     * <p>The last rung's <em>index</em>, not the number of rungs. Life is measured
     * in periods elapsed rather than in rows billed, and the two part company on a
     * ladder that carries no row for a period — a moratorium the builder chose not
     * to emit a boundary for, a season with no due date. Taking the count there
     * would report a life short by exactly the missing rows and truncate a call
     * date that had not yet arrived. On the ordinary contiguous ladder the two are
     * the same number.
     */
    private static int statedLife(InstalmentLadder ladder) {
        return ladder.rungs().get(ladder.rungs().size() - 1).periodIndex();
    }

    /**
     * The highest rung falling on or before a date, or 0 where none does.
     *
     * <p>Exercise between two due dates is aligned <em>back</em> to the boundary at
     * or before it. Exercising mid-period creates a broken period and a part-period
     * accrual, which {@code BrokenPeriodAccrual} and the schedule builder own; here
     * the effect of aligning back is that the truncated ladder stays a prefix of the
     * billed one, which is what keeps the contractual leg reconcilable. Aligning
     * forward would instead bill an instalment the truncation says was never due.
     */
    private static int periodOnOrBefore(InstalmentLadder ladder, LocalDate date) {
        int period = 0;
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            if (!rung.dueOn().isAfter(date)) {
                period = rung.periodIndex();
            }
        }
        return period;
    }

    /** The earliest option that ends the instrument early, or {@code null} where none does. */
    private static OptionSchedule.EmbeddedOption earliestShortening(OptionSchedule options) {
        OptionSchedule.EmbeddedOption earliest = null;
        for (OptionSchedule.EmbeddedOption option : options.options()) {
            boolean shortens = switch (option.type()) {
                case CALL, PUT, PREPAYMENT, CLEAN_UP_CALL -> true;
                case EXTENSION, CONVERSION -> false;
            };
            if (shortens && (earliest == null
                || option.firstExercise().isBefore(earliest.firstExercise()))) {
                earliest = option;
            }
        }
        return earliest;
    }

    /** The strike of whichever option's exercise window contains a date, else par. */
    private static BigDecimal strikeAt(OptionSchedule options, LocalDate date) {
        for (OptionSchedule.EmbeddedOption option : options.options()) {
            boolean shortens = switch (option.type()) {
                case CALL, PUT, PREPAYMENT, CLEAN_UP_CALL -> true;
                case EXTENSION, CONVERSION -> false;
            };
            if (shortens
                && !date.isBefore(option.firstExercise())
                && !date.isAfter(option.lastExercise())) {
                return option.strikePctOfPar();
            }
        }
        return PAR;
    }

    /** The first contractual repricing date after the value date. */
    private static Optional<LocalDate> nextReset(ScheduleBlueprint blueprint) {
        return switch (blueprint.rate()) {
            case RateProfile.Floating floating -> floating.nextResetAfter(blueprint.valueDate());
            case RateProfile.FloatingWithCollar collar ->
                collar.base().nextResetAfter(blueprint.valueDate());
            case RateProfile.MarketSpreadReset spread -> {
                LocalDate found = null;
                for (LocalDate reset : spread.resetDates()) {
                    if (reset.isAfter(blueprint.valueDate())) {
                        found = reset;
                        break;
                    }
                }
                yield Optional.ofNullable(found);
            }
            case RateProfile.Fixed ignored -> Optional.empty();
            case RateProfile.StepCoupon ignored -> Optional.empty();
            case RateProfile.Ratchet ignored -> Optional.empty();
            case RateProfile.InflationIndexed ignored -> Optional.empty();
        };
    }

    /**
     * What a rung's instalment is, for traceability only.
     *
     * <p>Kind never affects discounting — only amount and date do. A rung billing
     * neither principal nor interest is a moratorium boundary: a zero-amount flow
     * changes no present value and no balance, it declares an accrual boundary so
     * that the holiday shows as a row per accounting period rather than one long
     * compound accretion.
     */
    private static FlowKind kindOf(InstalmentLadder.Rung rung) {
        if (rung.principal().isZero()) {
            return FlowKind.INTEREST;
        }
        return rung.interest().isZero() ? FlowKind.PRINCIPAL : FlowKind.COMBINED_EMI;
    }

    // --------------------------------------------------------------- value types

    /**
     * One policy resolved to a life, or to the reason it could not be.
     *
     * <p>Carrying the reason rather than throwing at construction is what lets an
     * unavailable policy be skipped silently in the alternatives and reported loudly
     * when it is the chosen one. The same evaluation serves both, so the two can
     * never disagree about whether a policy was available.
     */
    private record Plan(
        ExercisePolicy policy,
        int lifePeriods,
        BigDecimal strikePctOfPar,
        FlowKind terminalKind,
        List<OptionalityJudgements.WeightedOutcome> outcomes,
        String unavailableBecause) {

        static Plan of(
            ExercisePolicy policy, int lifePeriods, BigDecimal strikePctOfPar, FlowKind terminalKind) {
            return new Plan(policy, lifePeriods, strikePctOfPar, terminalKind, List.of(), null);
        }

        static Plan weighted(
            int lifePeriods, List<OptionalityJudgements.WeightedOutcome> outcomes) {
            return new Plan(ExercisePolicy.PROBABILITY_WEIGHTED, lifePeriods, PAR,
                FlowKind.PRINCIPAL, List.copyOf(outcomes), null);
        }

        static Plan unavailable(ExercisePolicy policy, String because) {
            return new Plan(policy, 1, PAR, FlowKind.PRINCIPAL, List.of(),
                Objects.requireNonNull(because, "because"));
        }

        boolean available() {
            return unavailableBecause == null;
        }

        boolean isWeighted() {
            return !outcomes.isEmpty();
        }
    }

    /**
     * A ladder and the future flows that go with it.
     *
     * @param altered whether anything was truncated or extended; false means the
     *     contractual ladder is returned as it stands, terminal balance included
     */
    private record Reshaped(
        List<InstalmentLadder.Rung> rungs,
        List<CashFlow> futureFlows,
        int lifePeriods,
        boolean altered) {
    }

    /** The identity a weighted flow accumulates under. */
    private record FlowSlot(LocalDate date, int periodIndex, FlowKind kind) {
    }
}
