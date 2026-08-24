package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.CashflowProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.PenalChargeScreen;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Step 7 of the blueprint pipeline
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 4</a>): the
 * bridge that lets a {@link ScheduleBlueprint} be projected by everything already
 * built, without any of it changing.
 *
 * <p>The point of the bridge is that the far bank does not move. The solver, the
 * amortisation engine, the routing table, the nine reference cases and every Phase 1
 * test consume {@link ProjectionResult} through {@link CashflowProjector}, and they
 * keep doing so. The eight dimensions arrive on this side of it. That is also why the
 * existing shape projectors are not deleted: they become the preset layer — what a
 * blueprint is a preset <em>of</em> — and a contract that a preset already fits has
 * no reason to be re-expressed.
 *
 * <h2>The pipeline, and what each stage is allowed to decide</h2>
 *
 * <pre>
 * ScheduleBuilder        -> contractual InstalmentLadder     what the contract says
 * FlowVectorAssembler    -> contractual FlowVector + GCA_0   the ledger's signs
 * OptionalityResolver    -> ExpectedLifeDetermination        a recorded policy
 * BehaviouralAdjuster    -> expected InstalmentLadder        a recorded assumption
 * FlowVectorAssembler    -> expected FlowVector              the ledger's signs
 * ConventionSelector     -> checked periodic-index licence    ST-10
 * ProjectionResult       -> both legs, IC-1 computed inside it
 * </pre>
 *
 * <p>Each stage's output is a value, each is independently testable, and none of them
 * is allowed to substitute for another. That is the whole discipline: a projection
 * that fails at the optionality stage fails loudly there rather than quietly
 * publishing the contractual life, because a silently substituted policy is exactly
 * what a recorded policy exists to prevent.
 *
 * <h2>Why a projector may hold a solver, and when it must not</h2>
 *
 * <p>{@link CashflowProjector} says a projector never solves a rate, and that
 * boundary is what makes a wrong number diagnosably a projection defect or a solver
 * defect rather than ambiguously both. This class does not breach it: it never solves
 * the instrument's <em>own</em> EIR — that remains the caller's {@code SolveRequest}
 * over the expected leg — and the vectors it returns are the vectors that request will
 * be built from.
 *
 * <p>What it does need a solver for is <em>measurement</em>. ST-7 requires that where
 * an option is present at least two exercise policies are computed and the divergence
 * between them is quantified, and there is no way to quantify a divergence in basis
 * points without discounting something. So:
 *
 * <ul>
 *   <li><b>No solver configured.</b> The optionality stage is skipped, expected life
 *       is contractual, and an <em>optioned</em> blueprint is refused at construction
 *       rather than at projection. Refusing early matters: a registry that accepted
 *       the projector and then failed on the tenth contract of a batch has already
 *       published nine.
 *   <li><b>Solver configured.</b> Every applicable policy is costed and the
 *       divergence is on the record, whether or not the blueprint carries an option.
 * </ul>
 *
 * <h2>Selection: this is a per-contract projector, not a shape strategy</h2>
 *
 * <p>{@link #supports} answers on compatibility with the blueprint this instance
 * holds, not on a {@link com.crisil.eir.calc.projection.ScheduleShape}. Like the
 * {@code LMS_AUTHORITATIVE} path it is a per-contract configuration and belongs at
 * the front of a registry through {@code ProjectorRegistry.prepend}, not in the
 * standard list where two blueprint projectors would both claim the same terms.
 *
 * <p>Three attributes are checked and the rest of {@link ContractTerms} is
 * deliberately not read: currency, the initial recognition date, and the rate's
 * compounding frequency. Those are the three whose mismatch changes a number without
 * changing anything visible — a vector anchored a day away from the carrying amount's
 * own date, or an annual rate applied to a monthly schedule. Everything else the terms
 * carry has a blueprint dimension that supersedes it, and a projection that took some
 * fields from the terms and some from the blueprint would be reproducible from
 * neither. Notably {@code principal} is <em>not</em> compared to {@code notional}:
 * on a discount instrument the terms quote the face value at maturity while the
 * blueprint's notional is what the instrument advances, and the two are supposed to
 * differ.
 *
 * <p>Pure and clock-free. Every date is an input, the projector holds no mutable
 * state, and identical inputs reproduce the vectors bit-identically forever, which is
 * what DT-1 asserts every night on a closed period.
 */
public final class BlueprintProjector implements CashflowProjector {

    private final ScheduleBlueprint blueprint;
    private final RateSolver solver;
    private final OptionalityJudgements judgements;
    private final int spreadPeriods;
    private final FlowKind terminalKind;
    private final InstrumentSide side;

    /**
     * A projector for a blueprint with no optionality to measure.
     *
     * @throws IllegalArgumentException where the blueprint carries an embedded option,
     *     or records an exercise policy other than {@code CONTRACTUAL_MATURITY}.
     *     Quantifying the divergence needs a rate, and reporting an optioned
     *     instrument's life without the divergence is the ST-7 finding rather than
     *     the answer
     */
    public BlueprintProjector(ScheduleBlueprint blueprint) {
        this(blueprint, null, OptionalityJudgements.none(), 0,
            FlowVectorAssembler.DEFAULT_TERMINAL_KIND, InstrumentSide.ASSET);
    }

    /** A projector that can cost every applicable exercise policy. */
    public BlueprintProjector(ScheduleBlueprint blueprint, RateSolver solver) {
        this(blueprint, Objects.requireNonNull(solver, "solver"), OptionalityJudgements.none(), 0,
            FlowVectorAssembler.DEFAULT_TERMINAL_KIND, InstrumentSide.ASSET);
    }

    /**
     * The full form.
     *
     * @param blueprint     the composed dimensions; the authority on this contract
     * @param solver        used to cost exercise policies, or null to skip the stage
     * @param judgements    the management judgements the policies need; the
     *                      {@code MOST_LIKELY_OUTCOME}, {@code PROBABILITY_WEIGHTED}
     *                      and {@code ECONOMIC_RATIONALITY} policies have inputs the
     *                      engine does not invent
     * @param spreadPeriods trailing instalments that share the rounding residue under
     *                      {@code ResiduePolicy.SPREAD_LAST_N}; ignored by every other
     *                      policy, and the reason it is a parameter rather than a
     *                      blueprint field is that it belongs to the lending
     *                      platform's billing convention and not to the instrument
     * @param terminalKind  {@code BALLOON} or {@code RESIDUAL_VALUE} — the two are
     *                      arithmetically identical and are different assets to a
     *                      controller
     * @param side          whose perspective the vector's signs are taken from
     */
    public BlueprintProjector(
        ScheduleBlueprint blueprint,
        RateSolver solver,
        OptionalityJudgements judgements,
        int spreadPeriods,
        FlowKind terminalKind,
        InstrumentSide side) {

        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.solver = solver;
        this.judgements = Objects.requireNonNull(judgements, "judgements");
        this.terminalKind = Objects.requireNonNull(terminalKind, "terminalKind");
        this.side = Objects.requireNonNull(side, "side");
        if (spreadPeriods < 0) {
            throw new IllegalArgumentException("spreadPeriods must be >= 0, got " + spreadPeriods);
        }
        this.spreadPeriods = spreadPeriods;
        if (solver == null && requiresMeasurement(blueprint)) {
            throw new IllegalArgumentException(
                "this blueprint records exercise policy " + blueprint.options().exercisePolicy()
                    + " over " + blueprint.options().options().size() + " embedded option(s), and"
                    + " quantifying the divergence between policies needs a rate. Supply a"
                    + " RateSolver. Projecting one life and reporting no divergence would satisfy"
                    + " nothing: ST-7 exists because the ACPIR-versus-IFRS 9 difference on a"
                    + " callable security is one of the few that can be measured exactly rather"
                    + " than argued, and it reverses sign between a premium and a discount, so no"
                    + " blanket policy can be assumed conservative");
        }
        requireRateFrequencyMatchesCalendar(blueprint);
    }

    /** The same projector with a different terminal-lump label. */
    public BlueprintProjector withTerminalLumpAs(FlowKind replacement) {
        return new BlueprintProjector(blueprint, solver, judgements, spreadPeriods, replacement, side);
    }

    /** The same projector on the other side of the balance sheet. */
    public BlueprintProjector withSide(InstrumentSide replacement) {
        return new BlueprintProjector(blueprint, solver, judgements, spreadPeriods, terminalKind,
            replacement);
    }

    /** The same projector with the management judgements the recorded policy needs. */
    public BlueprintProjector withJudgements(OptionalityJudgements replacement) {
        return new BlueprintProjector(blueprint, solver, replacement, spreadPeriods, terminalKind, side);
    }

    /** The same projector billing the residue over {@code periods} trailing instalments. */
    public BlueprintProjector withResidueSpreadOver(int periods) {
        return new BlueprintProjector(blueprint, solver, judgements, periods, terminalKind, side);
    }

    // ------------------------------------------------------- the projector contract

    @Override
    public boolean supports(ContractTerms terms) {
        Objects.requireNonNull(terms, "terms");
        return terms.currency().equals(blueprint.currency())
            && terms.disbursementDate().equals(blueprint.valueDate())
            && terms.contractualRate().periodsPerYear() == ladderRate().periodsPerYear();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(fees, "fees");
        if (!supports(terms)) {
            throw new IllegalArgumentException(
                "these terms are not the ones this blueprint describes: terms are "
                    + terms.currency().getCurrencyCode() + " anchored " + terms.disbursementDate()
                    + " at x" + terms.contractualRate().periodsPerYear() + ", blueprint is "
                    + blueprint.currency().getCurrencyCode() + " anchored " + blueprint.valueDate()
                    + " at x" + ladderRate().periodsPerYear()
                    + ". A blueprint projector is per-contract configuration; register it with"
                    + " ProjectorRegistry.prepend for the contract it belongs to");
        }
        return projectBlueprint(fees).projection();
    }

    @Override
    public String label() {
        return "Blueprint[" + blueprint.describe() + "]";
    }

    // ------------------------------------------------------------------ the stages

    /**
     * Runs the pipeline and returns everything it produced, the bridged
     * {@link ProjectionResult} included.
     *
     * <p>Takes no {@link ContractTerms} because it needs none: the blueprint is the
     * authority on the contract and the terms exist only to gate
     * {@link #supports(ContractTerms)}. A caller holding a blueprint can project from
     * it directly and read the expected-life divergence, the contractual ladder and
     * the recorded behavioural basis off the result.
     */
    public BlueprintProjection projectBlueprint(List<FeePosting> fees) {
        Objects.requireNonNull(fees, "fees");

        InstalmentLadder contractualLadder = ScheduleBuilder.build(blueprint, spreadPeriods);
        FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
            blueprint, contractualLadder, fees, terminalKind, side);
        FlowVector contractual = assembly.vector();

        ExpectedLifeDetermination expectedLife = null;
        InstalmentLadder shaped = contractualLadder;
        FlowVector expected = contractual;
        BehaviouralAdjustment behaviour = null;

        if (solver != null) {
            Money measuredAgainst = assetSigned(assembly.initialCarryingAmount());
            expectedLife = OptionalityResolver.resolve(
                blueprint, contractualLadder, measuredAgainst, solver, judgements);
            if (expectedLife.chosenPolicy() == ExercisePolicy.PROBABILITY_WEIGHTED) {
                // A probability-weighted determination has no ladder — its flows are an
                // expectation across outcomes, each with its own redemption date — so the
                // vector the rate was solved over is taken directly and the behavioural
                // stage is skipped. Skipped rather than approximated: layering a CPR curve
                // on top of an expectation that already blends exercise outcomes would
                // count the same prepayment behaviour twice, and picking the modal outcome
                // to get a ladder would replace an expectation with a scenario at the one
                // stage that bills from it.
                shaped = null;
                expected = onThisSide(OptionalityResolver.flowsUnder(
                    blueprint, contractualLadder, measuredAgainst,
                    expectedLife.chosenPolicy(), judgements));
            } else {
                shaped = OptionalityResolver.ladderUnder(
                    blueprint, contractualLadder, expectedLife, judgements);
            }
        }

        if (shaped != null) {
            behaviour = BehaviouralAdjuster.adjustment(shaped, blueprint.behaviour(), ladderRate());
            InstalmentLadder expectedLadder = behaviour.expected();
            expected = expectedLadder == contractualLadder
                ? contractual
                : FlowVectorAssembler
                    .assemble(blueprint, expectedLadder, fees, terminalKind, side)
                    .vector();
        }

        ConventionSelector.Choice choice =
            FlowVectorAssembler.convention(blueprint, contractual, expected);
        InvariantResult calendarCheck = calendarForcesActualDating(blueprint, choice.convention());

        List<InvariantResult> asserted = new ArrayList<>(contractualLadder.invariants());
        asserted.addAll(assembly.invariants());
        if (expectedLife != null) {
            asserted.addAll(expectedLife.invariants());
        }
        if (behaviour != null) {
            asserted.addAll(behaviour.invariants());
        }
        asserted.add(PenalChargeScreen.overFeePostings(fees));
        asserted.add(calendarCheck);

        // One result per invariant, because a named control an auditor asks for by name
        // gets one answer. Gathering the stages' own results the way this method does
        // published ST-3 three times and ST-5 twice on a plain projection: the ladder
        // asserts both, and BehaviouralAdjustment.of appends the expected ladder's
        // results too, which under a contractual overlay ARE the ladder's. Two of the
        // three ST-3s were therefore the same statement twice — noise — and the third a
        // different claim under the same identifier: the ladder's "scheduled principal +
        // terminal = advanced" against the behavioural "principal recovered on the
        // expected leg = on the contractual leg". Under a prepayment curve that moves
        // principal in amount rather than in time those disagree, and anything resolving
        // ST-3 by identifier got whichever came first — the ladder's, the one that passes.
        //
        // Conjoined rather than deduplicated: dropping later results would drop the
        // behavioural claim, which is the one with independent content. See
        // InvariantResult.conjunction, which is the general form of the fix PC-1 already
        // carried and ST-2 needed after it.
        ProjectionResult projection = new ProjectionResult(
            contractual,
            expected,
            assembly.initialCarryingAmount(),
            choice.convention(),
            expected.equals(contractual),
            InvariantResult.oneResultPerInvariant(asserted));

        // ST-10 is asserted rather than reported, and it is the one invariant in this
        // pipeline that cannot fail for a data reason. A calendar that can move a due
        // date and a vector discounted on period ordinals is not a breach in the book;
        // it is a defect in convention selection, and the number it produces is a
        // wrong rate rather than a flagged one. Nothing downstream can compensate for
        // it, so nothing downstream is given the chance.
        calendarCheck.orThrow();

        return new BlueprintProjection(
            projection, contractualLadder, expectedLife, behaviour, assembly, choice);
    }

    /**
     * Invariant ST-10: a business-day adjustment or an unequal-period calendar forces
     * actual dating.
     *
     * <p>The claim runs one way only. Where the calendar admits periodic indexing the
     * optimisation is still not granted — the vector's own flows decide that, and a
     * moratorium, a broken first period or a mid-period draw all void it on a
     * perfectly regular monthly calendar. What ST-10 forbids is the reverse: taking
     * period ordinals as a measure of time on a schedule whose periods are not equal.
     * On a crop-cycle loan the gaps between due dates genuinely differ, so a
     * monthly-index approximation there is not a slightly rough rate. It is a wrong
     * one, and it is wrong in the direction of the interest the harvest gap actually
     * carries.
     *
     * @param convention the convention {@link ConventionSelector} chose, on the
     *     vectors that will actually be discounted
     */
    public static InvariantResult calendarForcesActualDating(
        ScheduleBlueprint blueprint, TimeConvention convention) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(convention, "convention");
        boolean admits = blueprint.calendar().admitsPeriodicIndexing();
        boolean indexed = convention instanceof TimeConvention.PeriodicIndex;
        if (!admits && indexed) {
            return InvariantResult.fail(
                InvariantId.ST_10,
                "calendar " + blueprint.calendar().frequency() + " under "
                    + blueprint.calendar().businessDayConvention() + " with "
                    + blueprint.calendar().holidays().size() + " holidays can move or unequally"
                    + " space a due date, so period ordinals are not a valid measure of time on"
                    + " this schedule; the vector was nonetheless licensed for "
                    + convention.label(),
                BigDecimal.ONE);
        }
        return InvariantResult.pass(
            InvariantId.ST_10,
            admits
                ? "calendar " + blueprint.calendar().frequency() + " is uniform and unadjusted, so"
                    + " ST-10 imposes no constraint and the vector's own flows decided "
                    + convention.label()
                : "calendar " + blueprint.calendar().frequency() + " can move or unequally space a"
                    + " due date, and the vector is discounted under " + convention.label());
    }

    // ------------------------------------------------------------------ accessors

    public ScheduleBlueprint blueprint() {
        return blueprint;
    }

    /** Whether an expected-life determination will be made. */
    public boolean measuresOptionality() {
        return solver != null;
    }

    public InstrumentSide side() {
        return side;
    }

    // ------------------------------------------------------------------ internals

    /**
     * The single scalar rate the ladder was built at.
     *
     * <p>Taken as period one's rate because that is the rate the behavioural stage
     * needs to roll a faster balance path, and because a profile whose rate is not
     * constant will be refused by {@code BehaviouralAdjuster} on exactly that ground
     * rather than silently rolled at the wrong one. That refusal is correct: a step
     * coupon has no single contractual rate, and an expected leg for one has to be
     * built from the rate profile through the pipeline rather than from a scalar.
     */
    private Rate ladderRate() {
        return blueprint.rate().rateForPeriod(1);
    }

    /**
     * The carrying amount restated on the asset side, for the optionality stage.
     *
     * <p>{@code OptionalityResolver} costs each policy over a vector it builds itself:
     * the carrying amount negated at the anchor, and the ladder's rungs positive. That
     * is the asset convention, and on a liability the two would then carry the
     * <em>same</em> sign — no sign change, no bracket, and every policy reported as
     * unsolvable for a reason that has nothing to do with optionality.
     *
     * <p>Mirroring the target rather than teaching the resolver about sides is not a
     * workaround, it is the arithmetic: {@code PV(r)} is homogeneous in the flows, so
     * negating the target and the flows together leaves every root exactly where it
     * was. An issued bond's EIR is the same number as the EIR of the same bond held by
     * whoever bought it — that is what makes a rate comparable across the balance
     * sheet at all — and the sign convention belongs to the ledger, not to the rate.
     * What must not happen is mirroring only one of the two, which is why this is one
     * expression used for both the {@code resolve} call and the weighted vector.
     */
    private Money assetSigned(Money carryingAmount) {
        return side == InstrumentSide.LIABILITY ? carryingAmount.negate() : carryingAmount;
    }

    /**
     * An asset-signed vector restated on this instrument's side.
     *
     * <p>Needed only for the probability-weighted expected leg, which is taken from the
     * resolver rather than reassembled from a ladder. Every flow flips, the inception
     * leg included, because the resolver's inception flow is the mirrored carrying
     * amount and not a fee-split leg — so unlike the assembler's signing there is no
     * posting here whose direction is already the holder's.
     */
    private FlowVector onThisSide(FlowVector vector) {
        if (side != InstrumentSide.LIABILITY) {
            return vector;
        }
        List<com.crisil.eir.domain.CashFlow> mirrored = new ArrayList<>(vector.flows().size());
        for (com.crisil.eir.domain.CashFlow flow : vector.flows()) {
            mirrored.add(new com.crisil.eir.domain.CashFlow(
                flow.date(), flow.periodIndex(), flow.amount().negate(), flow.kind(),
                flow.contingent()));
        }
        return FlowVector.of(vector.anchorDate(), vector.currency(), mirrored);
    }

    private static boolean requiresMeasurement(ScheduleBlueprint blueprint) {
        return blueprint.isOptioned()
            || blueprint.options().exercisePolicy() != ExercisePolicy.CONTRACTUAL_MATURITY;
    }

    /**
     * Refuses a rate quoted at a frequency the calendar does not bill at.
     *
     * <p>The same check {@link ContractTerms} makes, for the same reason: interest
     * must be computed from the rate for the period the schedule is built on and never
     * by dividing an annual rate. A 12% annual rate handed to a monthly ladder bills
     * twelve times the interest and produces a schedule that looks orderly.
     *
     * <p>Skipped where the calendar has no whole number of periods in a year —
     * weekly, fortnightly, seasonal, custom. There the rate's own frequency is the
     * only statement of what a period is worth, and {@code ScheduleBuilder} uses it
     * as given precisely because on unequal periods "the periodic rate" is a
     * different number in each period and only the rate profile knows which.
     */
    private static void requireRateFrequencyMatchesCalendar(ScheduleBlueprint blueprint) {
        int calendarPeriods = blueprint.calendar().frequency().periodsPerYear();
        if (calendarPeriods < 1) {
            return;
        }
        int ratePeriods = blueprint.rate().rateForPeriod(1).periodsPerYear();
        if (ratePeriods != calendarPeriods) {
            throw new IllegalArgumentException(
                "rate profile " + blueprint.rate().label() + " is quoted x" + ratePeriods
                    + " but calendar " + blueprint.calendar().frequency() + " bills x"
                    + calendarPeriods + "; interest must be computed from the rate for the period"
                    + " the schedule is built on, never by dividing an annual rate");
        }
    }
}
