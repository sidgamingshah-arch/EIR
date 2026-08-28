package com.crisil.eir.application.onboarding;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.calc.projection.CashflowProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.FailureIsolation;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.tier.TierAssignment;
import com.crisil.eir.policy.tier.TierAssignmentResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Initial recognition: 05 § 3.1's {@code OnboardContract}, in the order the sequence diagram runs
 * it.
 *
 * <pre>
 * APP-&gt;&gt;POL: measurement category / SPPI gate     &lt;-- FIRST, always, for every contract
 * alt FVTPL
 *     POL--&gt;&gt;APP: no EIR applies
 *     APP-&gt;&gt;DB:  record, exclude from EIR processing
 * else Amortised cost or FVOCI
 *     APP-&gt;&gt;POL:  classify fees (rule set version)
 *     APP-&gt;&gt;POL:  assign materiality tier
 *     APP-&gt;&gt;CALC: project flows (contractual + expected)
 *     CALC-&gt;&gt;CALC: assert IC-1 (GCA0 = net cash at inception)
 *     APP-&gt;&gt;CALC: solve EIR
 *     APP-&gt;&gt;DB:   EIR_COMPUTATION + policy/rule-set versions
 * end
 * </pre>
 *
 * <h2>The ordering is the control</h2>
 *
 * <p>05 § 3.1, in prose directly under that diagram: "The SPPI/measurement gate runs <b>first</b>,
 * before any projection or solve. On the asset side an SPPI failure is a cliff, not a gradient: the
 * whole instrument goes to FVTPL and no EIR arises. Doing expensive work before that check is waste,
 * and worse, produces a rate for an instrument that should not have one."
 *
 * <p>The FVTPL branch of {@link #onboard} therefore returns before the projector and the solver have
 * been <em>touched</em> — not after discarding their results, and not after computing them for a
 * trace. Three things make that checkable rather than asserted:
 *
 * <ol>
 *   <li>The collaborators are the published seams of {@code eir-calc} —
 *       {@link CashflowProjector} and {@link RateSolver} — supplied at construction, so a test
 *       substitutes counting fakes and asserts <b>zero</b> calls.
 *   <li>{@link OnboardingOutcome#workPerformed()} records the stages consumed, and reads exactly
 *       {@code [MEASUREMENT_GATE]} for an excluded contract.
 *   <li>ST-12's work leg, computed inside {@code OnboardingOutcome}, breaches on any outcome that
 *       shows a projection or a solve for a contract the gate did not license.
 * </ol>
 *
 * <h2>What is a queue entry here and what is not</h2>
 *
 * <p>An SPPI failure is <b>not</b> an exception. It is a measurement outcome: the instrument is at
 * FVTPL, is recorded, and is excluded from EIR processing. {@link MeasurementDecision} argues that at
 * length. Five conditions in this pipeline genuinely are exception-queue entries, and each is one of
 * the ten categories of 04 § 3 rather than an invented one:
 *
 * <ul>
 *   <li>{@link ExceptionCategory#MISSING_MANDATORY_FIELD} — an asset claiming amortised cost or
 *       FVOCI with no SPPI assessment on file (FR-104), and a contract the population names that the
 *       master does not carry;
 *   <li>{@link ExceptionCategory#UNMAPPED_FEE_CODE} — FR-202, never a defaulted treatment;
 *   <li>{@link ExceptionCategory#MISSING_COST_FUNCTION} — FR-203, an integral cost with no ACPIR 53
 *       selling-versus-processing attribute;
 *   <li>{@link ExceptionCategory#PENAL_CHARGE_REJECTED} — a posting the rule set classifies
 *       {@code EXCLUDED_BY_DIRECTION} reaching the ingestion boundary;
 *   <li>{@link ExceptionCategory#IC1_BREACH} — the initial carrying amount is not the net cash flow
 *       at inception;
 *   <li>{@link ExceptionCategory#NO_SOLUTION} and {@link ExceptionCategory#MULTIPLE_ROOTS} — via
 *       {@link ExceptionRecord#ofSolve}, and never a fallback to the contractual rate (03 § 4.3).
 * </ul>
 *
 * <h2>No clock</h2>
 *
 * <p>Every date this class uses arrives as an input: the {@link AsAtBoundary} the population and the
 * source are read at, each posting's own {@code postedOn} for the fee rule lookup, and the request's
 * {@code initialRecognitionDate} for the tier policy's governance question. Nothing calls
 * {@code now()}. 03 § 1.1 and ADR-0003: a run that reads {@code Instant.now()} cannot be re-run,
 * which is DT-1.
 */
public final class InitialRecognition {

    /**
     * The prefix of the deterministic payload reference every queue entry from this pipeline
     * carries.
     *
     * <p>{@link ExceptionRecord#raise} requires one, because 03 § 4.3 makes the stored input the only
     * diagnostic a value-path failure will ever have. Derived from the run and the contract rather
     * than from a counter or a timestamp, so that two runs of the same population produce
     * byte-identical entries — FR-903, and the reason {@code FeeRuleKey.ANY} is a sentinel rather
     * than a null.
     */
    public static final String PAYLOAD_PREFIX = "onboarding";

    private final FeeClassificationResolver feeRules;
    private final TierAssignment tierGate;
    private final CashflowProjector projector;
    private final RateSolver solver;

    /**
     * @param feeRules  the versioned fee taxonomy (FR-201); resolution happens nowhere else
     * @param tierGate  the § 10 materiality gate with its approved Board threshold (FR-107)
     * @param projector the projection seam — supplied rather than constructed, which is what lets a
     *                  test count its calls and assert the gate ran first
     * @param solver    the solve seam, for the same reason
     */
    public InitialRecognition(FeeClassificationResolver feeRules, TierAssignment tierGate,
        CashflowProjector projector, RateSolver solver) {
        this.feeRules = Objects.requireNonNull(feeRules, "feeRules");
        this.tierGate = Objects.requireNonNull(tierGate, "tierGate");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.solver = Objects.requireNonNull(solver, "solver");
    }

    /**
     * The production wiring: {@link ProjectorRegistry#standard()} and
     * {@link BracketedNewtonSolver}.
     *
     * <p>A named factory rather than a default constructor, because the two collaborators it fills
     * in are the two the ordering control is asserted against, and a caller should have to say it
     * wants the real ones.
     */
    public static InitialRecognition standard(
        FeeClassificationResolver feeRules, TierAssignment tierGate) {
        return new InitialRecognition(feeRules, tierGate,
            new RegistryProjector(ProjectorRegistry.standard()), new BracketedNewtonSolver());
    }

    /**
     * Recognises one contract.
     *
     * <p>Total: every request yields an outcome. There is no path that returns null, throws for a
     * data condition, or leaves a contract without a disposition — FR-905, and
     * {@link com.crisil.eir.application.ContractResult}'s javadoc on why dropping one is the wrong
     * reading.
     *
     * @param runId    the run the outcome and any queue entry are stamped with (04 § 2.13)
     * @param boundary the as-at boundary; carried for the record and never read as "now"
     * @param request  the contract, its schedule, its unclassified postings and its classification
     *                 attributes
     */
    public OnboardingOutcome onboard(String runId, AsAtBoundary boundary,
        OnboardingRequest request) {

        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(request, "request");
        String contractId = request.contractId();
        String payloadRef = payloadRef(runId, contractId);

        // ---------------------------------------------------------------- 1. the gate, FIRST
        List<OnboardingWork> work = new ArrayList<>(OnboardingWork.values().length);
        work.add(OnboardingWork.MEASUREMENT_GATE);
        MeasurementDecision decision = MeasurementGate.assess(request);

        if (decision.isRefused()) {
            return OnboardingOutcome.quarantined(contractId, decision, List.of(), null, null, null,
                work, ExceptionRecord.raise(contractId, runId, decision.refusal(),
                    decision.detail(), payloadRef));
        }
        if (!decision.eirApplies()) {
            // 05 § 3.1's FVTPL branch. Nothing below this line has run: not the fee lookup, not the
            // tier gate, not the projector, not the solver. The return is here rather than in a
            // branch further down deliberately — a pipeline that computed everything and then chose
            // what to keep would satisfy every assertion about the OUTPUT and none about the cost,
            // and it is the shape 05 § 3.1 calls "waste, and worse".
            return OnboardingOutcome.excluded(contractId, decision);
        }

        // ------------------------------------------------- 2. classify fees (rule set version)
        work.add(OnboardingWork.FEE_CLASSIFICATION);
        List<FeeClassificationResolution> resolutions =
            new ArrayList<>(request.feeSubmissions().size());
        List<FeePosting> postings = new ArrayList<>(request.feeSubmissions().size());
        for (FeeSubmission submission : request.feeSubmissions()) {
            FeeClassificationResolution resolution = feeRules.resolve(
                submission.feeCode(), request.productId(), request.entityId(),
                submission.postedOn());
            resolutions.add(resolution);
            Optional<ExceptionCategory> refusal = feeRefusal(resolution, submission);
            if (refusal.isPresent()) {
                return OnboardingOutcome.quarantined(contractId, decision, resolutions, null, null,
                    null, work, ExceptionRecord.raise(contractId, runId, refusal.get(),
                        feeRefusalDetail(refusal.get(), resolution, submission), payloadRef));
            }
            postings.add(new FeePosting(submission.feeCode(), submission.amount(),
                submission.postedOn(), resolution.classification(), submission.costFunction(),
                submission.drawdownProbability()));
        }

        // ----------------------------------------------------- 3. assign the materiality tier
        work.add(OnboardingWork.TIER_ASSIGNMENT);
        TierAssignmentResult tier = tierGate.assign(request.tierInput());

        // ------------------------------------------------------------------- 4. project flows
        work.add(OnboardingWork.PROJECTION);
        ProjectionResult projection = projector.project(request.terms(), postings);
        InvariantResult initialRecognitionCheck = projection.initialRecognitionCheck();
        if (!initialRecognitionCheck.satisfied()) {
            // IC-1 is the one invariant 04 § 3 gives a category of its own, and its own javadoc
            // says why it stops the contract: "Either a fee is misclassified or a non-cash item
            // entered the vector." Both mean the carrying amount the solver would target is wrong,
            // so a rate solved against it would be precise and meaningless. The projection is kept
            // on the outcome so the breach and its two operands stay readable.
            return OnboardingOutcome.quarantined(contractId, decision, resolutions, tier, projection,
                null, work, ExceptionRecord.raise(contractId, runId, ExceptionCategory.IC1_BREACH,
                    InvariantId.IC_1 + " (" + InvariantId.IC_1.statement() + ") breached at initial"
                        + " recognition: " + initialRecognitionCheck.detail() + ". Deviation "
                        + initialRecognitionCheck.deviation().toPlainString(), payloadRef));
        }

        // ----------------------------------------------------------------------- 5. solve EIR
        work.add(OnboardingWork.SOLVE);
        SolveResult solve = solver.solve(solveRequestFor(request.terms(), projection, tier));
        Optional<ExceptionRecord> solveFailure = ExceptionRecord.ofSolve(contractId, runId,
            solve.status(), "initial recognition of " + contractId + " under tier " + tier.tier()
                + " (" + tier.basis() + "): " + solve.diagnostic(), payloadRef);
        if (solveFailure.isPresent()) {
            return OnboardingOutcome.quarantined(contractId, decision, resolutions, tier, projection,
                solve, work, solveFailure.get());
        }
        return OnboardingOutcome.recognised(contractId, decision, resolutions, tier, projection,
            solve, work);
    }

    /**
     * Recognises a whole population, isolating per contract (FR-905).
     *
     * <p><b>The population comes from {@link ContractSource}, not from the loader.</b> That is the
     * port's own stated reason for returning ids rather than contracts: "the run-level count that
     * FR-905's isolation depends on — every contract in the population is either computed or
     * quarantined — comes from here rather than from whatever the loader happened to return." So a
     * contract the source names and the master does not carry is quarantined and counted, not
     * skipped.
     *
     * <p><b>Why not {@link FailureIsolation#runBatch}.</b> {@code runBatch} is the right shape for
     * the month-end run: it returns the contracts that produced figures and files the rest into the
     * queue. Here the reconciliation needs <em>both</em> sides as values — the run has to state that
     * {@code recognised + excluded + quarantined = population}, and
     * {@link OnboardingRun} refuses to be assembled when it does not. So the barrier is used one
     * contract at a time, through {@link FailureIsolation#isolate}, and the duplicate-id pass
     * {@code runBatch} performs before it starts is reproduced here for the same reason its javadoc
     * gives: a duplicate "would overwrite one contract's figures with another's and the loss would
     * be invisible in the output".
     *
     * @param queue every entry raised is filed here, in population order, so that two runs of one
     *              population file byte-identically (FR-903)
     */
    public OnboardingRun onboardAll(String runId, AsAtBoundary boundary, ContractSource population,
        OnboardingSource source, ExceptionQueue queue) {

        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(queue, "queue");

        List<String> ids = List.copyOf(population.contractIdsInScope(boundary));
        Set<String> seen = new HashSet<>(Math.max(16, ids.size() * 2));
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                    "contract " + id + " appears twice in the population of run " + runId
                        + "; a duplicate would overwrite one contract's figures with another's and"
                        + " the loss would be invisible in the output");
            }
        }

        List<OnboardingOutcome> outcomes = new ArrayList<>(ids.size());
        List<ExceptionRecord> unreadable = new ArrayList<>();
        for (String id : ids) {
            FailureIsolation.Outcome<OnboardingOutcome> isolated = FailureIsolation.isolate(
                id, runId, ExceptionCategory.MISSING_MANDATORY_FIELD,
                () -> onboard(runId, boundary, requiredRequest(source, id, boundary)));
            if (isolated.succeeded()) {
                OnboardingOutcome outcome = isolated.value();
                outcomes.add(outcome);
                outcome.failure().ifPresent(queue::raise);
            } else {
                // The contract never reached the gate, so there is no MeasurementDecision to build
                // an outcome around. Carried as its own list rather than fabricated into an
                // outcome with a null decision: "the gate ran on every contract" is a claim this
                // module makes, and a synthetic decision would make it unfalsifiable.
                unreadable.add(isolated.exception());
                queue.raise(isolated.exception());
            }
        }
        return new OnboardingRun(runId, boundary, ids, outcomes, unreadable);
    }

    /**
     * The request the source holds for {@code contractId}, or a throw the barrier files.
     *
     * <p>Thrown rather than returned, because this runs <em>inside</em>
     * {@link FailureIsolation#isolate} and the barrier's job is to turn a throw about one contract
     * into a queue entry about one contract. An {@link IllegalStateException} is not one of the
     * shapes {@link FailureIsolation#recognise} names, so it files under the fallback the caller
     * passed — {@link ExceptionCategory#MISSING_MANDATORY_FIELD}, which is the category's own
     * definition: "a field the computation cannot proceed without."
     */
    private static OnboardingRequest requiredRequest(
        OnboardingSource source, String contractId, AsAtBoundary boundary) {
        return source.onboardingRequest(contractId, boundary).orElseThrow(() ->
            new IllegalStateException(
                "contract " + contractId + " is in the population as at " + boundary.businessAsOf()
                    + " (recorded as at " + boundary.recordedAsAt() + ") and the onboarding source"
                    + " holds no record for it. Quarantined rather than skipped: a population short"
                    + " by one contract reconciles perfectly, because the contract is absent from"
                    + " both sides of every total (FR-905)"));
    }

    /**
     * The solve request: the expected leg, the carrying amount as target, the convention the vector
     * licensed, a contractual seed, and the tier's tolerance.
     *
     * <p><b>The tier is load-bearing here and nowhere else in this pipeline.</b>
     * {@link SolverTolerance#forTier} tightens the convergence criterion for Tier 1, which is FR-406
     * — so the tier assignment changes the criterion under which the published rate is accepted,
     * rather than being a column written and never read. A pipeline that assigned a tier and solved
     * at the standard tolerance would satisfy FR-107's letter and lose its point.
     *
     * <p>The seed goes through {@link ConventionSelector#rateUnder} rather than straight off the
     * terms. {@code SolveRequest}'s javadoc requires "the contractual rate in the convention's
     * units", and a vector that failed the periodic-index precondition falls back to actual dating,
     * whose {@code periodsPerYear} is one: pairing a monthly rate with a year fraction is an
     * annualisation round-trip in disguise.
     */
    private static SolveRequest solveRequestFor(
        ContractTerms terms, ProjectionResult projection, TierAssignmentResult tier) {
        BigDecimal seed = ConventionSelector
            .rateUnder(projection.recommendedConvention(), terms.contractualRate())
            .periodic();
        return SolveRequest.of(projection.expected(), projection.initialCarryingAmount(),
                projection.recommendedConvention(), seed)
            .withTolerance(SolverTolerance.forTier(tier.tier()));
    }

    /**
     * Which of the three fee-stage refusals this posting hits, or empty where it classifies.
     *
     * <p>All three are checked <em>before</em> {@link FeePosting} is constructed, and that ordering
     * is the point: {@code FeePosting}'s own constructor throws on an {@code EXCLUDED_BY_DIRECTION}
     * classification and on an integral cost with no cost function, and letting it throw would file
     * both under the barrier's coarse fallback instead of under the two categories 04 § 3 names for
     * them. A queue entry labelled {@code MISSING_MANDATORY_FIELD} when the truth is
     * {@code PENAL_CHARGE_REJECTED} sends the operator to the wrong system.
     */
    private static Optional<ExceptionCategory> feeRefusal(
        FeeClassificationResolution resolution, FeeSubmission submission) {
        if (!resolution.isResolved()) {
            return Optional.of(resolution.exception());
        }
        if (resolution.classification() == FeeClassification.EXCLUDED_BY_DIRECTION) {
            return Optional.of(ExceptionCategory.PENAL_CHARGE_REJECTED);
        }
        boolean integralCost = resolution.classification() == FeeClassification.INTEGRAL
            && submission.amount().isNegative();
        if (integralCost && isBlank(submission.costFunction())) {
            return Optional.of(ExceptionCategory.MISSING_COST_FUNCTION);
        }
        return Optional.empty();
    }

    /** The queue entry's text, which has to name the posting and the reason (FR-202). */
    private static String feeRefusalDetail(ExceptionCategory category,
        FeeClassificationResolution resolution, FeeSubmission submission) {
        return switch (category) {
            case UNMAPPED_FEE_CODE -> resolution.detail();
            case PENAL_CHARGE_REJECTED -> "posting " + submission.describe() + " resolves to "
                + FeeClassification.EXCLUDED_BY_DIRECTION + " under rule set version "
                + resolution.ruleSetVersionId() + " and reached the ingestion boundary. RBI's 2023"
                + " direction excludes penal charges from the EIR entirely: not capitalised,"
                + " bearing no further interest. Rejected here and asserted for the period as "
                + InvariantId.PC_1;
            case MISSING_COST_FUNCTION -> "posting " + submission.describe() + " resolves to "
                + FeeClassification.INTEGRAL + " under rule set version "
                + resolution.ruleSetVersionId() + " and is a cost paid by the bank with no"
                + " cost_function attribute. ACPIR 53 capitalises a selling-agent incentive and"
                + " excludes internal credit-appraisal cost, so the posting cannot be placed on"
                + " either side of that boundary (FR-203). One of "
                + FeePosting.COST_FUNCTIONS + " is required";
            default -> throw new IllegalStateException(
                "fee stage raised " + category + ", which it does not produce");
        };
    }

    /** The deterministic payload reference: run, then contract. No counter and no clock. */
    private static String payloadRef(String runId, String contractId) {
        return PAYLOAD_PREFIX + "/" + runId + "/" + contractId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * {@link ProjectorRegistry} as a {@link CashflowProjector}, so that the pipeline's projection
     * seam is one interface whatever is behind it.
     *
     * <p>The registry is a selector rather than a projector — it holds nine implementations and
     * picks by shape and features — but the pipeline does not care which one answers, and a test
     * substituting a counting fake needs the same type as production. Adapting here keeps
     * {@link #projector} a single interface rather than a union of "one projector or a registry".
     */
    private record RegistryProjector(ProjectorRegistry registry) implements CashflowProjector {

        private RegistryProjector {
            Objects.requireNonNull(registry, "registry");
        }

        @Override
        public boolean supports(ContractTerms terms) {
            for (CashflowProjector candidate : registry.projectors()) {
                if (candidate.supports(terms)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
            return registry.project(terms, fees);
        }

        @Override
        public String label() {
            return "ProjectorRegistry(" + registry.projectors().size() + ")";
        }
    }
}
