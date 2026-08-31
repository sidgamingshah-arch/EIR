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
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.FailureIsolation;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestOutcome;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
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
 * <h2>The tier is assigned, and then it is <em>permitted</em></h2>
 *
 * <p>The sequence diagram's {@code assign materiality tier} is one message and 03 § 10 is two rules.
 * FR-107 assigns; FR-411 and FR-412 decide whether the assignment may be used. Until this unit,
 * only the first ran: {@code TierAssignmentResult.requiresEquivalenceTest()} was true for every
 * Tier 3 assignment and nothing asked it, {@code EquivalenceTestGate} had no caller outside its own
 * package, and the tier reached exactly one consumer — {@link SolverTolerance#forTier}. So a 15-year
 * zero-coupon instrument was assigned Tier 3 by {@code TIER_3_FULLY_COLLATERALISED_LOW_FEE}, solved
 * on Tier 3's tolerance and recognised, with nothing recording that the permission was never sought.
 * Reference case 9 measures the straight-line error on that instrument at <b>81.0% overstatement of
 * year-one income</b> and 38.4% understatement of the final year, the two columns tying at
 * 684,758.30 exactly — the 08 risk register's Cambodia failure mode arriving through an unwired gate
 * rather than through a decision.
 *
 * <p>{@link #recognise} now consults {@link EquivalenceTestGate} between the projection and the
 * solve, and {@link TierPermission#effectiveTier()} is what reaches {@link SolverTolerance#forTier}.
 * <b>Between</b>, and not immediately after the assignment, because the subject FR-412 measures is
 * derived by arithmetic off the projection's contractual leg — see {@link EquivalenceTestSubjects}.
 * Before the solve, because the solve is what the permission licenses. It is not a sixth
 * {@link OnboardingWork} stage: it is a map lookup and six comparisons, not one of the two stages
 * 05 § 3.1 calls "expensive work", and the enum's declaration order is the statement of that
 * document's five stages rather than of every call this class makes.
 *
 * <p><b>The permission does not ride on {@link OnboardingOutcome}</b>, and
 * {@link OnboardingRecognition} says why at length: an outcome carrying an exception reports
 * {@code QUARANTINED}, and a TG-1 demotion is the opposite of a quarantine — the contract is
 * measurable and is measured, at Tier 2. So {@link #onboard} keeps its signature and its meaning,
 * {@link #recognise} is the richer call, and {@link #onboardAll} files both kinds of entry.
 *
 * <h2>No clock</h2>
 *
 * <p>Every date this class uses arrives as an input: the {@link AsAtBoundary} the population and the
 * source are read at, each posting's own {@code postedOn} for the fee rule lookup, the request's
 * {@code initialRecognitionDate} for the tier policy's governance question, and
 * {@code boundary.businessAsOf()} as the reporting date the equivalence test is judged current
 * against. Nothing calls {@code now()}. 03 § 1.1 and ADR-0003: a run that reads
 * {@code Instant.now()} cannot be re-run, which is DT-1.
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
    private final EquivalenceTestGate equivalenceTests;
    private final CashflowProjector projector;
    private final RateSolver solver;

    /**
     * A pipeline with <b>no equivalence tests on file</b>.
     *
     * <p>Kept so that a caller who has not yet wired the 03 § 10.2 register still gets the second
     * gate rather than no gate, and it resolves in the only direction a missing register can
     * honestly resolve in: {@link EquivalenceTestGate#empty()} demotes every Tier 3 proposal to
     * Tier 2 on {@code NO_TEST_ON_FILE}, breaches TG-1, and raises
     * {@code STALE_EQUIVALENCE_TEST} against every contract affected. That is the conservative
     * answer — Tier 2 is more expensive and more correct — and it is <em>loud</em>: one queue entry
     * per contract, blocking the close until somebody either produces the tests or accepts the
     * demotion with approval.
     *
     * <p>It is deliberately not a way to switch the gate off. A default that silently permitted
     * Tier 3 would be the defect this unit closes, reintroduced as a convenience.
     */
    public InitialRecognition(FeeClassificationResolver feeRules, TierAssignment tierGate,
        CashflowProjector projector, RateSolver solver) {
        this(feeRules, tierGate, EquivalenceTestGate.empty(), projector, solver);
    }

    /**
     * @param feeRules  the versioned fee taxonomy (FR-201); resolution happens nowhere else
     * @param tierGate  the § 10 materiality gate with its approved Board threshold (FR-107)
     * @param equivalenceTests the 03 § 10.2 register — the second gate (FR-411, FR-412, TG-1).
     *                  Supplied rather than constructed for the same reason the projector is: the
     *                  register is what decides whether a Tier 3 measurement is permitted, and a
     *                  test has to be able to hand this class one that is empty, one that is stale
     *                  and one that is current
     * @param projector the projection seam — supplied rather than constructed, which is what lets a
     *                  test count its calls and assert the gate ran first
     * @param solver    the solve seam, for the same reason
     */
    public InitialRecognition(FeeClassificationResolver feeRules, TierAssignment tierGate,
        EquivalenceTestGate equivalenceTests, CashflowProjector projector, RateSolver solver) {
        this.feeRules = Objects.requireNonNull(feeRules, "feeRules");
        this.tierGate = Objects.requireNonNull(tierGate, "tierGate");
        this.equivalenceTests = Objects.requireNonNull(equivalenceTests, "equivalenceTests");
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
        return standard(feeRules, tierGate, EquivalenceTestGate.empty());
    }

    /**
     * The production wiring with the 03 § 10.2 equivalence-test register attached.
     *
     * <p>The overload above supplies {@link EquivalenceTestGate#empty()}, which is a real register
     * with nothing in it rather than an absent gate — see the four-argument constructor. This is the
     * one a bank that performs the annual test calls.
     */
    public static InitialRecognition standard(
        FeeClassificationResolver feeRules, TierAssignment tierGate,
        EquivalenceTestGate equivalenceTests) {
        return new InitialRecognition(feeRules, tierGate, equivalenceTests,
            new RegistryProjector(ProjectorRegistry.standard()), new BracketedNewtonSolver());
    }

    /**
     * Recognises one contract, returning the disposition alone.
     *
     * <p>Unchanged in signature and in meaning, so that every existing caller keeps compiling and
     * keeps getting the same answer. <b>It does not carry the tier permission</b>: a caller that
     * persists 04 § 2.1's {@code materiality_tier}, files the TG-1 result, or files the
     * {@code STALE_EQUIVALENCE_TEST} entry must use {@link #recognise} instead, because
     * {@link OnboardingOutcome#tier()} is the FR-107 <em>proposal</em> and the tier actually
     * measured is {@link TierPermission#effectiveTier()}. {@link OnboardingRecognition} states why
     * the two cannot be collapsed today and what a change to {@code OnboardingOutcome} would have
     * to do.
     */
    public OnboardingOutcome onboard(String runId, AsAtBoundary boundary,
        OnboardingRequest request) {
        return recognise(runId, boundary, request).outcome();
    }

    /**
     * Recognises one contract: the disposition, and the second gate's decision.
     *
     * <p>Total: every request yields a recognition. There is no path that returns null, throws for a
     * data condition, or leaves a contract without a disposition — FR-905, and
     * {@link com.crisil.eir.application.ContractResult}'s javadoc on why dropping one is the wrong
     * reading.
     *
     * @param runId    the run the outcome and any queue entry are stamped with (04 § 2.13)
     * @param boundary the as-at boundary; carried for the record, never read as "now", and — as
     *                 {@code businessAsOf} — the reporting date the equivalence test is judged
     *                 current against
     * @param request  the contract, its schedule, its unclassified postings and its classification
     *                 attributes
     */
    public OnboardingRecognition recognise(String runId, AsAtBoundary boundary,
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
            return OnboardingRecognition.ungated(
                OnboardingOutcome.quarantined(contractId, decision, List.of(), null, null, null,
                    work, ExceptionRecord.raise(contractId, runId, decision.refusal(),
                        decision.detail(), payloadRef)));
        }
        if (!decision.eirApplies()) {
            // 05 § 3.1's FVTPL branch. Nothing below this line has run: not the fee lookup, not the
            // tier gate, not the projector, not the solver. The return is here rather than in a
            // branch further down deliberately — a pipeline that computed everything and then chose
            // what to keep would satisfy every assertion about the OUTPUT and none about the cost,
            // and it is the shape 05 § 3.1 calls "waste, and worse".
            return OnboardingRecognition.ungated(
                OnboardingOutcome.excluded(contractId, decision));
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
                return OnboardingRecognition.ungated(
                    OnboardingOutcome.quarantined(contractId, decision, resolutions, null, null,
                        null, work, ExceptionRecord.raise(contractId, runId, refusal.get(),
                            feeRefusalDetail(refusal.get(), resolution, submission), payloadRef)));
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
            // Ungated, and it must be: the tier gate has not run, and a TierPermission fabricated
            // for a Tier 3 assignment with no gate outcome is the one state that type refuses. The
            // permission is absent rather than defaulted, exactly as the projection and the solve
            // are absent from an outcome that never reached them.
            return OnboardingRecognition.ungated(
                OnboardingOutcome.quarantined(contractId, decision, resolutions, tier, projection,
                    null, work,
                    ExceptionRecord.raise(contractId, runId, ExceptionCategory.IC1_BREACH,
                        InvariantId.IC_1 + " (" + InvariantId.IC_1.statement() + ") breached at"
                            + " initial recognition: " + initialRecognitionCheck.detail()
                            + ". Deviation " + initialRecognitionCheck.deviation().toPlainString(),
                        payloadRef)));
        }

        // ------------------------------------------- 4b. permit the tier (FR-411, FR-412, TG-1)
        // Not an OnboardingWork stage; see the class comment. It sits here because FR-412's subject
        // is arithmetic off the contractual leg above, and before the solve because the solve is
        // what the permission licenses.
        TierPermission permission = permit(runId, payloadRef, boundary, request, tier, projection);

        // ----------------------------------------------------------------------- 5. solve EIR
        work.add(OnboardingWork.SOLVE);
        MaterialityTier measuredTier = permission.effectiveTier();
        SolveResult solve =
            solver.solve(solveRequestFor(request.terms(), projection, measuredTier));
        Optional<ExceptionRecord> solveFailure = ExceptionRecord.ofSolve(contractId, runId,
            solve.status(), "initial recognition of " + contractId + " under tier " + measuredTier
                + " (" + permission.describe() + "): " + solve.diagnostic(), payloadRef);
        if (solveFailure.isPresent()) {
            return new OnboardingRecognition(
                OnboardingOutcome.quarantined(contractId, decision, resolutions, tier, projection,
                    solve, work, solveFailure.get()),
                permission);
        }
        return new OnboardingRecognition(
            OnboardingOutcome.recognised(contractId, decision, resolutions, tier, projection, solve,
                work),
            permission);
    }

    /**
     * The second gate of 03 § 10: whether the proposed tier may actually be measured.
     *
     * <p><b>Consulted only for a Tier 3 proposal</b>, which is what
     * {@code TierAssignmentResult.requiresEquivalenceTest()} answers. Not because the gate would
     * misbehave otherwise — {@code EquivalenceTestGate.evaluate} answers a Tier 1 or Tier 2 subject
     * with a vacuous {@code NOT_TIER_3} pass — but because publishing that pass per contract would
     * put a TG-1 result no input can turn into a breach against every retail mortgage in the book.
     * {@code OnboardingRun}'s javadoc names that shape directly: "An absent control is bad; a
     * control that reads as coverage and cannot fail is worse, because nobody looks at it again."
     * {@link TierPermission} refuses both halves structurally, so neither a missing consultation nor
     * a fabricated one can survive an edit here.
     *
     * <p><b>The reporting date is {@code boundary.businessAsOf()}</b>, not the contract's initial
     * recognition date. The gate's own javadoc defines {@code asOf} as the reporting date and
     * reasons about it that way — "a test performed in June says nothing about whether a March close
     * was defensible" — and {@code AsAtBoundary} pins {@code businessAsOf} to the original period end
     * on a replay, so a replay of a closed period asks the same question and gets the same answer
     * (DT-1). The initial recognition date was the other candidate and is the wrong one on a
     * migrated book: it would demand 03 § 10.2 evidence for periods before the bank was under
     * ACPIR at all, turning a legitimate migration into one queue entry per contract — and a control
     * that is red by design gets argued down to a soft one within a quarter, which is the reasoning
     * 03 § 9 records against TM-1.
     */
    private TierPermission permit(String runId, String payloadRef, AsAtBoundary boundary,
        OnboardingRequest request, TierAssignmentResult tier, ProjectionResult projection) {

        if (!tier.requiresEquivalenceTest()) {
            return TierPermission.notRequired(tier);
        }
        EquivalenceTestSubject subject =
            EquivalenceTestSubjects.subjectFor(request, projection, tier.tier());
        EquivalenceTestOutcome outcome =
            equivalenceTests.evaluate(subject, boundary.businessAsOf());
        return TierPermission.gated(tier, outcome, runId, payloadRef);
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
     * <p><b>The TG-1 entries are filed here and not on the outcome.</b>
     * {@code ExceptionCategory.STALE_EQUIVALENCE_TEST} carries {@code stopsTheContract() == false},
     * so a demoted contract is recognised with a Tier 2 rate <em>and</em> queued — the value-path
     * filing {@code FailureIsolation}'s javadoc calls for: "A demotion belongs on the value path —
     * {@code ExceptionRecord.raise} alongside a successful Tier 2 recomputation." It still blocks
     * the close, because {@code ExceptionCategory.blocksClose()} is true for every category, and a
     * population changing measurement basis between one close and the next is exactly what a close
     * should surface.
     *
     * <p><b>The gap this unit named, and how it was closed.</b> As built, the {@link OnboardingRun}
     * returned here did not know about the queue, and {@code OnboardingRun.blocksClose()} read three
     * things — a published invariant breach, the quarantine count, and whether the run asserted
     * nothing. A TG-1 demotion trips none of them: TG-1 is deliberately not a population obligation
     * (next paragraph), and the contract's disposition stays {@code RECOGNISED} because
     * {@code STALE_EQUIVALENCE_TEST} does not stop it — 03 § 10.2's consequence is measurement at
     * Tier 2, not a halt. So {@code run.describeClose()} printed "close may proceed" over a
     * population whose measurement basis had changed, while {@code queue.blocksClose()} correctly
     * said otherwise: two answers to one question.
     *
     * <p>Closed at the coordinator's merge, because it needed a change to {@code OnboardingRun}. The
     * run now carries {@code raised} — every entry it filed, in population order — and
     * {@code blocksClose()} has a fourth reason reading it, so a blocking entry counts whatever the
     * contract's disposition. The run's own list rather than the queue: the queue is the caller's
     * sink and may hold other runs' entries, so a run consulting it would report somebody else's
     * block and would report nothing when handed a fresh one. Both sides now agree, and
     * {@code TierPermissionTest.queueEntriesAreFiled} pins them.
     *
     * <p><b>TG-1 is deliberately absent from {@link OnboardingRun#POPULATION_INVARIANTS}.</b> A
     * population with no Tier 3 contracts asserts TG-1 nowhere, so declaring it an obligation would
     * make {@code unassertedInvariants()} name it — and therefore block the close — on every book
     * without Tier 3 exposure. That is the reasoning {@code RunAggregate.POPULATION_INVARIANTS}
     * records for leaving S3-1 out: "An obligation red on every performing book is a control that
     * gets argued down to a soft one within a quarter." The complementary half that keeps the
     * absence honest is structural rather than declarative: {@link TierPermission} cannot be
     * constructed for a Tier 3 assignment with no gate outcome, so a Tier 3 contract that reached a
     * solve has been through the gate by construction, and {@code OnboardingRun.invariants()}
     * already drops ids it is not answerable for.
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
        // What this run raised, kept alongside the queue rather than instead of it. The queue is the
        // caller's sink and may hold other runs' entries; this is the run's own record, and it is
        // what lets OnboardingRun.blocksClose() see a TG-1 demotion -- which quarantines nothing and
        // so tripped none of its other three reasons.
        List<ExceptionRecord> raised = new ArrayList<>();
        for (String id : ids) {
            FailureIsolation.Outcome<OnboardingRecognition> isolated = FailureIsolation.isolate(
                id, runId, ExceptionCategory.MISSING_MANDATORY_FIELD,
                () -> recognise(runId, boundary, requiredRequest(source, id, boundary)));
            if (isolated.succeeded()) {
                OnboardingRecognition recognition = isolated.value();
                outcomes.add(recognition.outcome());
                // Both kinds of entry, tier gate first, because the gate runs before the solve. A
                // Tier 3 population with no test on file whose Tier 2 solve then finds no root
                // raises two, and they are different facts: one is a control failure somebody has
                // to clear, the other is a computation that produced no figure.
                raised.addAll(recognition.queueEntries());
                queue.raiseAll(recognition.queueEntries());
            } else {
                // The contract never reached the gate, so there is no MeasurementDecision to build
                // an outcome around. Carried as its own list rather than fabricated into an
                // outcome with a null decision: "the gate ran on every contract" is a claim this
                // module makes, and a synthetic decision would make it unfalsifiable.
                unreadable.add(isolated.exception());
                raised.add(isolated.exception());
                queue.raise(isolated.exception());
            }
        }
        return new OnboardingRun(runId, boundary, ids, outcomes, unreadable, raised);
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
     * <p><b>The tier argument is the {@link MaterialityTier} the second gate permitted, not the one
     * FR-107 proposed.</b> Stated as the enum rather than as a {@code TierAssignmentResult} so that
     * the call site cannot reach {@code tier.tier()} out of habit and undo the demotion.
     *
     * <p>Note honestly what a demotion does and does not change here today:
     * {@code SolverTolerance.forTier} maps {@code TIER_2} and {@code TIER_3} to the same
     * {@code standard()} tolerance, so a Tier 3 → Tier 2 demotion does not move the convergence
     * criterion. The demotion's effect is on the tier that is recorded and on the TG-1 result and
     * queue entry that accompany it — and, if policy ever elects
     * {@code SolverTolerance.tightened()} for a long-tenor Tier 2 pool as FR-406 contemplates, on
     * the criterion too. That is why the effective tier is passed even though the two tolerances
     * coincide: a call site that passed the proposal would be correct by coincidence and would
     * silently stop being correct the day the Tier 2 election is made.
     *
     * <p>The seed goes through {@link ConventionSelector#rateUnder} rather than straight off the
     * terms. {@code SolveRequest}'s javadoc requires "the contractual rate in the convention's
     * units", and a vector that failed the periodic-index precondition falls back to actual dating,
     * whose {@code periodsPerYear} is one: pairing a monthly rate with a year fraction is an
     * annualisation round-trip in disguise.
     */
    static SolveRequest solveRequestFor(
        ContractTerms terms, ProjectionResult projection, MaterialityTier measuredTier) {
        BigDecimal seed = ConventionSelector
            .rateUnder(projection.recommendedConvention(), terms.contractualRate())
            .periodic();
        return SolveRequest.of(projection.expected(), projection.initialCarryingAmount(),
                projection.recommendedConvention(), seed)
            .withTolerance(SolverTolerance.forTier(measuredTier));
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
