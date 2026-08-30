package com.crisil.eir.api;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.close.RunClose;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.onboarding.FeeSubmission;
import com.crisil.eir.application.onboarding.InitialRecognition;
import com.crisil.eir.application.onboarding.InstrumentClass;
import com.crisil.eir.application.onboarding.MeasurementCategory;
import com.crisil.eir.application.onboarding.OnboardingOutcome;
import com.crisil.eir.application.onboarding.OnboardingRequest;
import com.crisil.eir.application.onboarding.OnboardingWork;
import com.crisil.eir.application.onboarding.SppiAssessment;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.replay.AmortisationRun;
import com.crisil.eir.application.replay.PublishedRun;
import com.crisil.eir.application.replay.ReplayRequest;
import com.crisil.eir.application.replay.ReplayUseCase;
import com.crisil.eir.application.replay.ReplayVerification;
import com.crisil.eir.application.replay.RunOutput;
import com.crisil.eir.application.replay.ShadowRun;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.MonthEndRun;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.application.run.SolveAudit;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleKey;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.ExceptionAcceptance;
import com.crisil.eir.policy.close.ReconciliationScope;
import com.crisil.eir.policy.close.ReconciliationTie;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.reconciliation.ContractualLegInterest;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayRun;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import com.crisil.eir.policy.tier.TierAssignment;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The four entry points, wired to one book, rendered as JSON.
 *
 * <p><b>What this class is careful about.</b> The engine's whole design is that a refusal is a
 * value, not an exception — a close presents forty unworked exceptions and a sub-ledger break at the
 * same time, worked by three different desks, and a gate that threw on the first would turn a day's
 * parallel work into a week of serial re-runs. An HTTP layer is the easiest place in a system to
 * throw that away, by mapping every unhappy answer onto a 4xx with one message. So every refusal
 * here comes back on a <b>200 with the whole list</b>, and the only 4xx is a malformed request:
 * a period id that is not a number, a missing approver. The distinction is
 * {@link FormBody.BadRequest} versus everything the engine returns.
 *
 * <p><b>State, and why there is any.</b> A close reads the run that produced the figures, and a
 * replay reads the run the close published. Those are three requests, so the run has to survive
 * between them. {@link #lastRun} is that, and it is the one piece of this class a production
 * deployment throws away: {@code eir-persistence}'s {@code AMORTISATION_RUN} and
 * {@code PERIOD_BALANCE} tables hold it, keyed by run and period, which is also what makes a replay
 * of a period closed last March possible at all. Here you can replay only what this process ran.
 *
 * <p><b>Not thread-safe, deliberately and visibly.</b> {@link EirServer} runs a single-threaded
 * executor for exactly this reason, and says so. Making this class concurrent would mean deciding
 * what two simultaneous closes of one period mean, and the answer in the domain is that the second
 * is refused — {@code PeriodCloseGate} already refuses a second close, so the honest place for that
 * decision is the period's own status, not a lock here.
 */
public final class EirService {

    /** The book's business date and the instant its figures were known — see the field note. */
    private static final AsAtBoundary BOUNDARY =
        AsAtBoundary.live(Seed.PERIOD_END, Instant.parse("2028-06-01T00:00:00Z"));

    private static final String BOOK_ID = "MAIN";
    private static final String CLOSED_BY_DEFAULT = "financial.controller";

    private final Book book;
    private final RoutingTableRegistry routing;

    /** The last completed run, per period. See the class javadoc on why this exists. */
    private final Map<Integer, Completed> lastRun = new LinkedHashMap<>();

    /** Acceptances recorded against the current run's queue, for the close to weigh. */
    private List<ExceptionAcceptance> acceptances = List.of();

    /** A finished run and the working papers a close and a replay both need. */
    private record Completed(
        String runId,
        RunAggregate aggregate,
        Map<String, ContractComputation> computations,
        List<ExceptionRecord> exceptions,
        Map<PolicyKind, String> policyStamps) {
    }

    /**
     * The last completed run for a period, as a <b>report</b> reads it — empty where this process
     * has run none.
     *
     * <p><b>Why the reporting modules need a read seam at all.</b> 06 § 7's reports are all
     * functions of figures a run already published: a movement schedule, four reconciliations, an
     * approximation register. None of them may run, post, accept or close, and none of them may
     * recompute — a report that re-derived its own figures would reconcile against itself and tie
     * on a book the run got wrong. So this returns the run's own working papers, read-only, and
     * nothing that could change them.
     *
     * <p><b>Why the holdings come along.</b> A movement schedule is presented per product and a
     * product id lives on the holding, not on the computation. The alternative — copying the
     * product id onto {@code ContractComputation} — would widen the spine's vocabulary for one
     * report's benefit, which is the trade {@code ContractComputation}'s own javadoc refuses.
     *
     * <p><b>The holdings and not the {@link Book}, and the difference is the whole point of the
     * word read-only above.</b> {@code Book} carries {@code put}, {@code postToGl} and
     * {@code recordOnboarded}, so handing one to a report would document an incapability the type
     * does not have — a reporting module could post to the general ledger through a seam whose
     * javadoc promises it cannot. {@code Book.holdings()} is an immutable list of immutable records,
     * so the promise is enforced by the type rather than by this sentence.
     */
    public Optional<RunSnapshot> lastRunFor(int periodId) {
        Completed completed = lastRun.get(periodId);
        return completed == null
            ? Optional.empty()
            : Optional.of(new RunSnapshot(completed.runId(), periodId, completed.aggregate(),
                completed.computations(), book.holdings()));
    }

    /**
     * What a report needs of a finished run: the spine's per-contract results, the per-contract
     * working papers, and the holdings the population came from. See {@link #lastRunFor}.
     */
    public record RunSnapshot(
        String runId,
        int periodId,
        RunAggregate aggregate,
        Map<String, ContractComputation> computations,
        List<Book.Holding> holdings) {

        public RunSnapshot {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(aggregate, "aggregate");
            computations = Map.copyOf(Objects.requireNonNull(computations, "computations"));
            holdings = List.copyOf(Objects.requireNonNull(holdings, "holdings"));
        }
    }

    public EirService(Book book) {
        this.book = Objects.requireNonNull(book, "book");
        this.routing = RoutingTableRegistry.of(RoutingTable.currentDefault());
    }

    // ================================================================= the book

    /** The opening position of every contract on file. */
    public Json.Obj book() {
        List<Json.Obj> rows = new ArrayList<>();
        for (Book.Holding holding : book.holdings()) {
            boolean stateOnFile = !Seed.CONTRACT_WITHOUT_STATE.equals(holding.contractId());
            rows.add(Json.object()
                .str("contractId", holding.contractId())
                .str("product", holding.productId())
                .str("entity", holding.entityId())
                .str("description", holding.description())
                .str("stage", holding.state().stage().name())
                .figure("eir", holding.state().eir().periodic())
                .figure("openingGca", stateOnFile ? holding.state().openingGca().amount() : null)
                .figure("allowance", holding.state().allowance().amount())
                .figure("billedInterest", holding.state().contractualInterestBilled().amount())
                .str("eclModel", holding.state().eclEngineVersion())
                .bool("openingStateOnFile", stateOnFile)
                .bool("onboardedHere", book.onboardedHere().contains(holding.contractId())));
        }
        return Json.object()
            .count("periodId", Seed.PERIOD_ID)
            .str("periodStart", Seed.PERIOD_START.toString())
            .str("periodEnd", Seed.PERIOD_END.toString())
            .str("recordedAsAt", BOUNDARY.recordedAsAt().toString())
            .str("gcaAccount", Book.GCA_ACCOUNT)
            .count("contracts", rows.size())
            .array("holdings", rows)
            .strings("policyVersions", policyVersionIds());
    }

    // ============================================ stage 1: initial recognition

    /**
     * Recognises one new contract, and reports which pipeline steps actually ran.
     *
     * <p><b>{@code stepsPerformed} is the part of this response worth reading.</b> 05 § 3.1's
     * ordering is the control, not the output: an SPPI failure on the asset side exits before the
     * fee lookup, the tier gate, the projector and the solver, and a pipeline that computed
     * everything and then chose what to keep would satisfy every assertion about the answer and
     * none about the cost. {@code OnboardingWork} records what ran, so the UI can show that nothing
     * below the gate did.
     *
     * <p>A recognised contract is added to the book at its inception position, so the operator can
     * then roll it forward with everything else and watch it appear in the population accounting.
     */
    public Json.Obj onboard(FormBody body) {
        String contractId = body.text("contractId");
        if (book.holds(contractId)) {
            throw new FormBody.BadRequest("contract " + contractId + " is already on the book;"
                + " initial recognition happens once, and a second one would give one contract two"
                + " inception positions");
        }

        Money principal = Money.inr(body.decimal("principal").toPlainString());
        Rate contractualRate = Rate.periodic(body.decimal("rate"), 12);
        int termPeriods = body.integer("termPeriods");
        boolean sppiPasses = !"fail".equalsIgnoreCase(body.textOr("sppi", "pass"));
        MeasurementCategory declared = MeasurementCategory.valueOf(
            body.textOr("declaredCategory", "AMORTISED_COST"));
        String feeCode = body.textOr("feeCode", "PROC_FEE");
        Money feeAmount = Money.inr(body.decimal("feeAmount").toPlainString());

        ContractTerms terms = ContractTerms.of(
            principal, contractualRate, termPeriods, 12,
            Seed.PERIOD_START, Seed.PERIOD_START.plusMonths(1),
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, RateType.FIXED);

        OnboardingRequest onboardingRequest = new OnboardingRequest(
            contractId, InstrumentClass.LOAN, declared,
            sppiPasses
                ? SppiAssessment.passed(Seed.PERIOD_START, "credit.analyst")
                : SppiAssessment.failed(Seed.PERIOD_START, "credit.analyst"),
            Seed.PERIOD_START, body.textOr("product", "HL"), body.textOr("entity", "IN-MUM"),
            terms,
            List.of(new FeeSubmission(feeCode, feeAmount, Seed.PERIOD_START, null, null)),
            TierAssignmentSegment.RETAIL, Set.of(), principal);

        OnboardingOutcome outcome = InitialRecognition
            .standard(feeRules(), tierGate())
            .onboard("ONBOARD-" + Seed.PERIOD_ID, BOUNDARY, onboardingRequest);

        List<String> stepsPerformed = new ArrayList<>();
        for (OnboardingWork stage : OnboardingWork.values()) {
            if (outcome.performed(stage)) {
                stepsPerformed.add(stage.name());
            }
        }

        Json.Obj response = Json.object()
            .str("contractId", contractId)
            .bool("recognised", outcome.eir().isPresent())
            .bool("eirApplies", outcome.decision().eirApplies())
            .str("measuredAt", outcome.decision().effectiveCategory() == null
                ? null : outcome.decision().effectiveCategory().name())
            .str("decision", outcome.decision().detail())
            .figure("eir", outcome.eir().map(Rate::periodic).orElse(null))
            .strings("stepsPerformed", stepsPerformed)
            .bool("expensiveWorkPerformed", outcome.expensiveWorkPerformed())
            .array("invariants", invariantRows(outcome.invariants()));

        outcome.failure().ifPresent(record -> response
            .str("exceptionCategory", record.category().name())
            .str("exceptionDetail", record.describe()));

        // Only a recognised contract joins the book. A quarantined one has no rate and an excluded
        // one has no EIR at all, and putting either on the book would give the month-end run a
        // contract to roll forward at a rate nobody solved.
        if (outcome.eir().isPresent()) {
            book.put(Book.Holding.onFile(contractId, onboardingRequest.productId(),
                onboardingRequest.entityId(),
                "Recognised in this session — " + termPeriods + " months at "
                    + contractualRate.periodic().toPlainString() + " periodic",
                new ContractStateSource.OpeningState(
                    terms, outcome.eir().orElseThrow(), principal, principal,
                    Stage.STAGE_1, Seed.NIL, "ECL-MODEL-2028.05", Seed.NIL),
                ContractPeriod.of(contractId, 1,
                    FlowVector.of(Seed.PERIOD_START, Money.INR,
                        List.of(CashFlow.of(Seed.PERIOD_END, 1, Seed.NIL,
                            FlowKind.COMBINED_EMI))),
                    TimeConvention.PeriodicIndex.monthly(), Seed.NIL, Seed.NIL)));
            book.recordOnboarded(contractId);
            response.str("addedToBook", "at its inception position; roll the period forward to"
                + " see it in the population accounting");
        }
        return response;
    }

    /**
     * The fee rule set this tool resolves against: one rule, for one code.
     *
     * <p>Deliberately one. FR-201 says an unmapped fee code raises into the exception queue and
     * never defaults to a classification, and a demonstration rule set covering every code an
     * operator might type would hide the single most important behaviour in the fee taxonomy. Type
     * anything but {@code PROC_FEE} and watch it refuse.
     */
    private static FeeClassificationResolver feeRules() {
        PolicyVersion version = new PolicyVersion("POL-FEE-2028.1", PolicyKind.FEE_RULE_SET,
            "fee and cost taxonomy, FY2028-29", LocalDate.of(2028, 4, 1),
            "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
            PolicyVersionStatus.EFFECTIVE);
        return new FeeClassificationResolver(new FeeRuleSet(version, List.of(
            new FeeRule(
                new FeeRuleKey("PROC_FEE", FeeRuleKey.ANY, FeeRuleKey.ANY,
                    LocalDate.of(2028, 4, 1)),
                FeeClassification.INTEGRAL,
                "a processing fee charged at origination is integral to the yield (ACPIR 53)"))));
    }

    private static TierAssignment tierGate() {
        return TierAssignment.under(
            new PolicyVersion("POL-TIER-2028.1", PolicyKind.TIER_ASSIGNMENT,
                "materiality tier thresholds, FY2028-29", LocalDate.of(2028, 4, 1),
                "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
                PolicyVersionStatus.EFFECTIVE),
            Money.inr("500000000.00"));
    }

    // ================================================== stage 2: the month-end run

    /**
     * Rolls every contract in the book forward one period.
     *
     * <p>The response leads with the population accounting rather than with the figures, because
     * that is the order the engine's own controls are in: a run over ten million contracts that
     * silently processed 9,999,998 reconciles perfectly, and the two it dropped are absent from
     * both sides of every total.
     */
    public Json.Obj run(FormBody body) {
        String runId = body.textOr("runId", "RUN-" + Seed.PERIOD_ID + "-01");
        RunRequest request = request(runId);
        ContractPipeline pipeline = new ContractPipeline(
            request, book.periods(), routing, SolveAudit.over(new BracketedNewtonSolver()));

        ExceptionQueue queue = new ExceptionQueue();
        MonthEndRun.Completion completion = new MonthEndRun(request, pipeline).execute(queue);
        RunAggregate aggregate = completion.aggregate();

        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        stamps.put(PolicyKind.FEE_RULE_SET, "POL-FEE-2028.1");
        stamps.put(PolicyKind.TIER_ASSIGNMENT, "POL-TIER-2028.1");
        stamps.put(PolicyKind.ROUTING_TABLE, RoutingTable.currentDefault().version().id());

        lastRun.put(Seed.PERIOD_ID, new Completed(
            runId, aggregate, completion.computations(),
            List.copyOf(queue.records()), stamps));
        // A new run's queue is a new queue. Carrying acceptances across would have the gate reading
        // one run's approvals as authority to close over another's problems, which
        // PeriodCloseGate refuses as ACCEPTANCE_WITHOUT_AN_EXCEPTION — correctly, and this layer
        // should not be manufacturing that refusal for it.
        this.acceptances = List.of();

        List<Json.Obj> contracts = new ArrayList<>();
        for (ContractResult result : aggregate.results()) {
            ContractComputation computation = completion.computations().get(result.contractId());
            Json.Obj row = Json.object()
                .str("contractId", result.contractId())
                .bool("computed", result.isComputed())
                .figure("closingGca", result.isComputed()
                    ? result.closingGca().atPresentationScale().amount() : null)
                .figure("closingGcaWorking", result.isComputed()
                    ? result.closingGca().amount() : null)
                .count("invariants", result.invariants().size());
            if (computation != null) {
                row.figure("eirBefore", computation.eirBefore().periodic())
                    .figure("eirAfter", computation.eirAfter().periodic())
                    .bool("rateMoved", computation.rateMoved())
                    .count("solves", computation.solves())
                    .figure("grossInterest",
                        computation.row().presentedEirInterest().amount())
                    .figure("openingGca",
                        computation.openingGca().atPresentationScale().amount())
                    .bool("incomeSuppressed", computation.decomposition().incomeSuppressed())
                    .figure("toSuspense", computation.suspense().chargedToSuspense().amount())
                    .figure("suspenseClosing", computation.suspense().closingBalance().amount());
            }
            if (!result.isComputed()) {
                row.str("exceptionCategory", result.exception().category().name())
                    .str("exceptionDetail", result.exception().describe());
            }
            contracts.add(row);
        }

        return Json.object()
            .str("runId", runId)
            .count("periodId", Seed.PERIOD_ID)
            .count("populationSize", aggregate.populationSize())
            .count("computed", aggregate.computedCount())
            .count("quarantined", aggregate.quarantinedCount())
            .count("unaccountedFor", aggregate.unaccountedFor())
            .count("solves", completion.solveCount())
            .bool("reportsCleanClose", aggregate.reportsCleanClose())
            .strings("blockingReasons", aggregate.blockingReasons())
            .strings("unassertedInvariants", names(aggregate.unassertedInvariants()))
            .array("invariants", invariantRows(aggregate.invariants()))
            .array("contracts", contracts)
            .array("journals", journalRows(aggregate.journals()))
            .strings("exceptions", describeAll(lastRun.get(Seed.PERIOD_ID).exceptions()));
    }

    // ==================================================== remediation: the two ways past a queue

    /**
     * Supplies the opening balance a quarantined contract was missing.
     *
     * <p>The remediation FR-905's queue exists to route somebody to. C-0003 arrives with period
     * movements and no recorded opening position; the run isolates it, the close refuses over it,
     * and the fix is not to accept the exception but to <em>correct the input</em>. Once the balance
     * is on file the contract computes with the rest and RC-1's presence break — the CBS billed it
     * and the engine never projected it — disappears because there is nothing left to disagree
     * about.
     *
     * <p>Kept distinct from {@link #accept} for that reason. One of them fixes the book and the
     * other signs for closing over a book that is still wrong, and a tool that offered only the
     * second would teach the wrong habit.
     */
    public Json.Obj repair(FormBody body) {
        String contractId = body.textOr("contractId", Seed.CONTRACT_WITHOUT_STATE);
        Book.Holding holding = book.holding(contractId)
            .orElseThrow(() -> new FormBody.BadRequest(
                "no contract " + contractId + " on the book"));
        if (holding.stateOnFile()) {
            return Json.object()
                .bool("repaired", false)
                .str("contractId", contractId)
                .str("message", "contract " + contractId + " already has an opening balance on"
                    + " file; there is nothing to repair");
        }

        book.put(Book.Holding.onFile(holding.contractId(), holding.productId(),
            holding.entityId(),
            holding.description() + " — opening balance supplied by the data-quality desk",
            holding.state(), holding.period()));

        return Json.object()
            .bool("repaired", true)
            .str("contractId", contractId)
            .figure("openingGca", holding.state().openingGca().atPresentationScale().amount())
            .str("note", "the opening balance is on file. Roll the period forward again: the"
                + " contract computes, the queue empties, and RC-1's presence break goes with it"
                + " because the engine now projects what the CBS billed.");
    }

    /**
     * Accepts a queued exception under four eyes, so a close may proceed over it.
     *
     * <p><b>This is the most attacked control in the close gate, and the reason is arithmetic.</b>
     * Every one of 04 § 3's ten exception categories blocks the close, so acceptance is the only
     * route past a queued exception — which makes acceptance the control, and a self-approved
     * acceptance the way past every other control in the package. So the maker and the checker are
     * compared through {@code FourEyes}, case-insensitively and after stripping, which is the same
     * comparison the database performs on the row.
     *
     * <p>Try it with the same name on both sides. The gate refuses, and says why.
     */
    public Json.Obj accept(FormBody body) {
        Completed completed = lastRun.get(Seed.PERIOD_ID);
        if (completed == null) {
            return Json.object()
                .bool("ran", false)
                .str("message", "no run for period " + Seed.PERIOD_ID + " yet, so there is no"
                    + " queue to accept anything from");
        }
        String acceptedBy = body.text("acceptedBy");
        String approvedBy = body.text("approvedBy");
        String reason = body.textOr("reason",
            "accepted for this period pending a data-quality fix");

        List<ExceptionRecord> updated = new ArrayList<>();
        List<ExceptionAcceptance> acceptances = new ArrayList<>();
        for (ExceptionRecord record : completed.exceptions()) {
            ExceptionRecord accepted = record.acceptWithApproval(approvedBy, reason);
            updated.add(accepted);
            acceptances.add(new ExceptionAcceptance(record.contractId(), record.category(),
                acceptedBy, approvedBy, reason, Instant.parse("2028-06-04T09:00:00Z")));
        }

        lastRun.put(Seed.PERIOD_ID, new Completed(completed.runId(), completed.aggregate(),
            completed.computations(), List.copyOf(updated), completed.policyStamps()));
        this.acceptances = List.copyOf(acceptances);

        boolean selfApproved = FourEyes.isSelfApproval(acceptedBy, approvedBy);
        return Json.object()
            .bool("ran", true)
            .count("accepted", acceptances.size())
            .str("acceptedBy", acceptedBy)
            .str("approvedBy", approvedBy)
            .bool("selfApproved", selfApproved)
            .str("note", selfApproved
                ? "the same identity on both sides. The close will refuse this: an exception"
                    + " accepted by the person who put it forward is not accepted (04 § 3)."
                : "recorded. Close the period to see the gate weigh it — note that accepting an"
                    + " exception does not make the underlying figure right, it signs for closing"
                    + " over it.");
    }

    // ================================================ between run and close: post the journals

    /**
     * Posts the run's journals to the general ledger (FR-802, FR-803).
     *
     * <p><b>Why this is a separate step and not folded into the run.</b> SL-1 compares the
     * sub-ledger's contract-level detail against the GL's posted balance, and the whole value of the
     * comparison is that the two are different systems in different states. Posting inside the run
     * would make the tie true by construction at every moment and there would be nothing for an
     * operator to observe. Here a close attempted before posting is refused with SL-1 red, which is
     * the correct answer and the one a real month-end produces.
     */
    public Json.Obj post(FormBody body) {
        Completed completed = lastRun.get(Seed.PERIOD_ID);
        if (completed == null) {
            return Json.object()
                .bool("ran", false)
                .str("message", "no run for period " + Seed.PERIOD_ID + " yet; there are no"
                    + " journals to post. Roll the period forward first.");
        }

        Money before = book.glBalance();
        Money subLedger = Money.zero(Money.INR);
        for (ContractResult result : completed.aggregate().results()) {
            if (result.isComputed()) {
                subLedger = subLedger.plus(result.closingGca());
            }
        }
        book.postToGl(subLedger);

        return Json.object()
            .bool("ran", true)
            .str("runId", completed.runId())
            .count("entries", completed.aggregate().journals().size())
            .figure("glBefore", before.atPresentationScale().amount())
            .figure("glAfter", subLedger.atPresentationScale().amount())
            .figure("movement", subLedger.minus(before).atPresentationScale().amount())
            .str("account", Book.GCA_ACCOUNT)
            .str("note", "the sub-ledger's closing detail is now the ledger's balance, so SL-1 has"
                + " two sides in the same state. Attempting a close before this step is refused"
                + " with SL-1 red, which is the correct answer.");
    }

    // ============================================================ stage 3: the close

    /**
     * Puts the last run's evidence to {@code PeriodCloseGate}.
     *
     * <p>Two of step 6's four reconciliation ties are computed by {@code RunClose} from the run's
     * own figures. The Stage 3 four-way is built here from the per-contract reconciliations the
     * pipeline actually produced. The pre-/post-floor duality is nil against nil, because this book
     * carries no regulatory floor — and that is reported as a caveat on the response rather than
     * left to look like a tie that passed, since a nil-against-nil tie reads as tied on every run
     * ever made.
     */
    public Json.Obj close(FormBody body) {
        Completed completed = lastRun.get(Seed.PERIOD_ID);
        if (completed == null) {
            return Json.object()
                .bool("ran", false)
                .str("message", "no run for period " + Seed.PERIOD_ID + " yet; a close reads the"
                    + " figures a run published, and there are none. Roll the period forward"
                    + " first.");
        }

        String closedBy = body.textOr("closedBy", CLOSED_BY_DEFAULT);
        Instant closedAt = Instant.parse(body.textOr("closedAt", "2028-06-05T09:00:00Z"));

        AccountingPeriod period = AccountingPeriod
            .open(Seed.PERIOD_ID, "FY2028-29", LocalDate.of(2028, 5, 1), Seed.PERIOD_END)
            .startClosing(Instant.parse("2028-06-02T09:00:00Z"));

        RunClose.ClosePresentation presentation = RunClose.present(
            request(completed.runId()), completed.aggregate(), Book.GCA_ACCOUNT,
            contractualLegLines(completed, Seed.PERIOD_ID), portfolioTies(completed),
            period, closedBy, closedAt,
            completed.exceptions(), acceptances);

        return Json.object()
            .bool("ran", true)
            .str("runId", completed.runId())
            .count("periodId", Seed.PERIOD_ID)
            .str("closedBy", closedBy)
            .str("closedAt", closedAt.toString())
            .bool("mayClose", presentation.mayClose())
            .bool("gateRefused", presentation.decision().isRefused())
            .count("refusalCount", presentation.decision().refusals().size())
            .str("gateVerdict", presentation.decision().describe())
            .strings("runRefusals", presentation.runRefusals())
            // The collapsed view, which is what the gate weighed. presentation.evidence() keeps
            // both SL-2 results — the batch's and the population's — and showing one id twice is
            // how a control report loses an operator's trust.
            .array("evidence", invariantRows(presentation.dashboard()))
            .count("evidencePresented", presentation.evidence().size())
            .array("breaches", invariantRows(presentation.breaches()))
            .figure("totalDeviation", presentation.totalDeviation())
            .bool("journalsPosted", book.posted())
            .strings("caveats", closeCaveats());
    }

    // ============================================= what a reporting module reads (06 § 7)

    /**
     * The figures a run published for one period, for the report modules of 06 § 7.
     *
     * <p><b>Why the reports read through this and not through the book.</b> A module is handed this
     * service and nothing else, and a reconciliation report has to read the same run a close reads
     * — otherwise the report and the close are two answers to one question, which is the defect
     * this codebase has recorded finding three times. What comes back is the run's own working
     * papers and the read ports it was assembled with; there is no write anywhere on it, so a
     * report cannot post a journal or move a ledger balance on the way to rendering one.
     *
     * <p><b>{@link Optional#empty()} for a period no run has published,</b> and that is the whole
     * reason this returns an Optional rather than an empty aggregate. A report over a period that
     * never ran must say so: every total over an empty population is nil and every reconciliation
     * over it ties, so "nothing ran" and "everything reconciled" render identically unless the
     * absence is a distinct answer.
     *
     * @param periodId the accounting period, {@code YYYYMM} per 04 § 2.13
     */
    public Optional<PublishedFigures> publishedRun(int periodId) {
        Completed completed = lastRun.get(periodId);
        if (completed == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedFigures(
            completed.runId(), request(completed.runId()), completed.aggregate(),
            completed.computations(), contractualLegLines(completed, periodId)));
    }

    /**
     * One run's published figures, the ports it read, and the one input a caller has to supply.
     *
     * <p><b>{@code engineContractualLeg} is on this record rather than rebuilt by each reader,</b>
     * because it is the engine's side of RC-1 and RC-1 must have exactly one engine side. It is
     * assembled once, by {@link #contractualLegLines}, and both the close and the report read the
     * same list — so a change to how the contractual leg is sourced cannot move one of them and
     * leave the other where it was.
     *
     * @param runId                the run that published these figures
     * @param request              the run as a value: its boundary, its book and its five read
     *                             ports, so a report resolves the GL and the CBS feed exactly as
     *                             the run did rather than at some later boundary of its own
     * @param aggregate            the population accounting and the per-contract results
     * @param computations         the per-contract working papers, by contract id; a quarantined
     *                             contract has no entry, which is why a report has to publish the
     *                             population counts alongside its figures
     * @param engineContractualLeg the engine's contractual-leg interest per computed contract
     */
    public record PublishedFigures(
        String runId,
        RunRequest request,
        RunAggregate aggregate,
        Map<String, ContractComputation> computations,
        List<ContractualLegInterest> engineContractualLeg) {

        public PublishedFigures {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(aggregate, "aggregate");
            computations = Map.copyOf(Objects.requireNonNull(computations, "computations"));
            engineContractualLeg =
                List.copyOf(Objects.requireNonNull(engineContractualLeg, "engineContractualLeg"));
        }

        /** The period these figures are for, taken from the request rather than restated. */
        public int periodId() {
            return request.periodId();
        }
    }

    /**
     * The engine's side of RC-1: what the contractual leg carries per computed contract.
     *
     * <p><b>Computed contracts only, and that is what lets RC-1 catch a dropped one.</b> A
     * quarantined contract contributes no engine line, so a contract the CBS billed and the engine
     * never projected shows up as a one-sided line worth the whole of the CBS figure — C-0003 on
     * this book, 5,298.16. A loop that took its population from the CBS feed instead would present
     * a line on both sides for every contract the feed carried and the presence break would be
     * invisible.
     *
     * <p><b>{@code periodId} is a parameter and not the book's constant, which is a correctness
     * fix rather than tidiness.</b> {@code CoreBankingReconciliation.over} refuses a line stamped
     * with another period — "C-14 compares one period against the same period" — so a line
     * hard-coded to the seed period would make both the close and the reconciliation report throw
     * the first time {@code lastRun} held a second period. Found in review, before there was a
     * second period to find it with.
     */
    private List<ContractualLegInterest> contractualLegLines(Completed completed, int periodId) {
        List<ContractualLegInterest> lines = new ArrayList<>();
        for (ContractResult result : completed.aggregate().results()) {
            if (result.isComputed()) {
                lines.add(new ContractualLegInterest(result.contractId(), periodId,
                    book.holding(result.contractId())
                        .orElseThrow()
                        .state()
                        .contractualInterestBilled()));
            }
        }
        return List.copyOf(lines);
    }

    // =========================================================== stage 4: the replay

    /** Replays the last run and reports DT-1, its coverage, and whether reproduction is proven. */
    public Json.Obj replay(FormBody body) {
        Completed completed = lastRun.get(Seed.PERIOD_ID);
        if (completed == null) {
            return Json.object()
                .bool("ran", false)
                .str("message", "no published run for period " + Seed.PERIOD_ID + " to replay;"
                    + " a replay reproduces figures a run published. Roll the period forward"
                    + " first.");
        }

        List<ContractResult> published = completed.aggregate().results();
        ReplayRun publishedRun = new ReplayRun(
            completed.runId(), Seed.PERIOD_ID, null,
            ShadowRun.figures(published), completed.policyStamps());

        PublishedRun publishedFor = new PublishedRun(
            publishedRun,
            new ClosedPeriod(Seed.PERIOD_ID, LocalDate.of(2028, 5, 1), Seed.PERIOD_END,
                LocalDate.of(2028, 6, 5), CLOSED_BY_DEFAULT),
            BOUNDARY.recordedAsAt(), BOOK_ID);

        // The job the replay drives is the SAME month-end job a live run drives. Substituting a
        // second implementation here would make the replay reproduce its own arithmetic, which
        // proves nothing about the run that published the figures.
        AmortisationRun job = shadowRequest -> {
            ContractPipeline pipeline = new ContractPipeline(shadowRequest, book.periods(),
                routing, SolveAudit.over(new BracketedNewtonSolver()));
            MonthEndRun.Completion again =
                new MonthEndRun(shadowRequest, pipeline).execute(new ExceptionQueue());
            return RunOutput.of(again.aggregate().results(), completed.policyStamps());
        };

        String shadowRunId = body.textOr("shadowRunId", "SHADOW-" + completed.runId());
        ReplayVerification verification = new ReplayUseCase(job)
            .replay(new ReplayRequest(publishedFor, request(completed.runId())), shadowRunId);

        InvariantResult dtOne = verification.dtOne();
        return Json.object()
            .bool("ran", true)
            .str("shadowRunId", verification.shadowRunId())
            .str("replayOf", completed.runId())
            .count("periodId", verification.periodId())
            .bool("dtOneSatisfied", dtOne.satisfied())
            .figure("dtOneDeviation", dtOne.deviation())
            .str("dtOneDetail", dtOne.detail())
            .str("coverage", verification.coverage().name())
            .bool("provesReproduction", verification.provesReproduction())
            .str("summary", verification.describe())
            .strings("caveats", List.of(
                "This book is a map, not a bitemporal store, so it answers the same thing at every"
                    + " boundary. The replay therefore reproduces, and it is not a fair test of"
                    + " bitemporality — that needs eir-persistence behind the ports.",
                "provesReproduction() is the answer to read, not dtOneSatisfied(): DT-1 passes over"
                    + " a period that published nothing, correctly, because nothing failed."));
    }

    // ================================================================= helpers

    private RunRequest request(String runId) {
        return new RunRequest(runId, Seed.PERIOD_ID, BOOK_ID, BOUNDARY,
            book.contracts(), book.contractState(), book.coreBanking(),
            book.generalLedger(), book.policySource());
    }

    /**
     * The Stage 3 four-way and the floor duality — the two ties {@code RunClose} cannot source.
     *
     * <p>The Stage 3 tie is real: it is the total absolute residual across every contract whose
     * recognition was suppressed, against nil. Absolute, because two residuals in opposite
     * directions must not net to a reconciled period.
     */
    private List<ReconciliationTie> portfolioTies(Completed completed) {
        Money residual = Money.zero(Money.INR);
        for (ContractComputation computation : completed.computations().values()) {
            Stage3Reconciliation reconciliation = computation.reconciliation();
            if (reconciliation != null) {
                // Every leg, summed ABSOLUTE: two residuals in opposite directions must not net to
                // a reconciled period. The legs are indexed from 1 because S3-1's four claims are
                // numbered 1..4 in FR-605, and reading residualsByLeg from 0 was an off-by-one that
                // the first close request turned into an ArrayIndexOutOfBounds.
                for (int leg = 0; leg < Stage3Reconciliation.LEGS.size(); leg++) {
                    residual = residual.plus(reconciliation.residualOn(leg).abs());
                }
            }
        }
        Money nil = Money.zero(Money.INR);
        return List.of(
            new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY, nil, residual,
                "Stage 3 four-way, total absolute residual across suppressed contracts"),
            new ReconciliationTie(ReconciliationScope.PRE_AND_POST_FLOOR, nil, nil,
                "no regulatory floor supplied by this book — see caveats"));
    }

    private List<String> closeCaveats() {
        return List.of(
            "SL-1 cannot fail on this book. The GeneralLedgerSource sums the book's own closing"
                + " positions, so both sides of the sub-ledger-to-GL tie come from one place. Its"
                + " value is that the two sides are independently sourced; a production"
                + " implementation reads the bank's trial balance, and then it can go red.",
            "The pre-/post-floor tie is nil against nil, because no regulatory floor is supplied."
                + " A nil-against-nil tie reads as tied on every run ever made, which is why"
                + " RunClose refuses to synthesise it and this layer labels it instead.",
            "No authentication or authorisation. FR-906's role model is unimplemented, so the"
                + " maker-checker identity on a close is whatever the caller typed.");
    }

    /**
     * The policy versions in force at the period end, as the run record wants them.
     *
     * <p>Resolved at the period end rather than at today's date, which is the whole of ADR-0006's
     * point: a closed period replays under the reading it closed under, so the version set is a
     * function of the period and not of when somebody asks.
     */
    private List<String> policyVersionIds() {
        List<String> ids = new ArrayList<>();
        book.policies().inForceOn(Seed.PERIOD_END).forEach(
            (kind, version) -> ids.add(kind.name() + "=" + version.id()));
        return ids;
    }

    private static List<Json.Obj> invariantRows(List<InvariantResult> results) {
        List<Json.Obj> rows = new ArrayList<>(results.size());
        for (InvariantResult result : results) {
            rows.add(Json.object()
                .str("id", result.id().name().replace('_', '-'))
                .str("statement", result.id().statement())
                .bool("satisfied", result.satisfied())
                .figure("deviation", result.deviation())
                .str("detail", result.detail()));
        }
        return rows;
    }

    private static List<Json.Obj> journalRows(List<JournalEntry> entries) {
        List<Json.Obj> rows = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            List<Json.Obj> lines = new ArrayList<>(entry.lines().size());
            for (JournalLine line : entry.lines()) {
                lines.add(Json.object()
                    .str("account", line.accountCode())
                    .str("side", line.side().name())
                    .figure("amount", line.amount().amount())
                    .str("narrative", line.narrative()));
            }
            rows.add(Json.object()
                .str("contractId", entry.contractId())
                .str("postedOn", entry.postedOn().toString())
                .bool("balances", entry.sidesBalance().satisfied())
                .figure("residual", entry.sidesBalance().deviation())
                .array("lines", lines));
        }
        return rows;
    }

    private static List<String> describeAll(List<ExceptionRecord> records) {
        List<String> described = new ArrayList<>(records.size());
        for (ExceptionRecord record : records) {
            described.add(record.describe());
        }
        return described;
    }

    private static List<String> names(List<com.crisil.eir.domain.InvariantId> ids) {
        List<String> named = new ArrayList<>(ids.size());
        for (com.crisil.eir.domain.InvariantId id : ids) {
            named.add(id.name().replace('_', '-'));
        }
        return named;
    }

    /** For the smoke test: the deviation total across a list, so a test can assert on one number. */
    public static BigDecimal totalDeviation(List<InvariantResult> results) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvariantResult result : results) {
            total = total.add(result.deviation().abs());
        }
        return total;
    }

    // ============================================== FR-808: the audit endpoint's read path

    /**
     * The working papers of the last run for a period, or empty where this process has none.
     *
     * <p><b>Why this exists and why it is read-only.</b> FR-808's trace resolves a published figure
     * to its inputs, and every one of those inputs is already here: {@link #lastRun} holds the
     * aggregate the close read, the per-contract {@link ContractComputation} the period balance is
     * written from, the queue, and the policy stamps. A trace that re-ran the pipeline to answer
     * would be answering about a <em>second</em> computation — plausible, arithmetically identical
     * on a book that has not moved, and no evidence at all about the figures actually published.
     * So the endpoint reads what was published, through this, and cannot write.
     *
     * <p><b>Empty is the honest answer, not an error.</b> A trace request for a period this process
     * never ran has no inputs to resolve to, and the caller needs to be told which of the two
     * things happened — nothing ran, or the contract was quarantined by the run that did. An
     * absent-versus-refused distinction collapsed into a 404 is the single most common way an audit
     * endpoint stops being usable as evidence.
     */
    public java.util.Optional<RunPapers> runPapers(int periodId) {
        Completed completed = lastRun.get(periodId);
        return completed == null
            ? java.util.Optional.empty()
            : java.util.Optional.of(new RunPapers(
                completed.runId(), completed.aggregate(), completed.computations(),
                completed.exceptions(), completed.policyStamps()));
    }

    /**
     * One contract's position and movements as the book carries them, or empty.
     *
     * <p>Narrower than handing out {@link Book} on purpose: the trace endpoint needs the inputs
     * side of a figure — terms, opening balances, stage, the period's flow vector and the cash
     * book's split — and nothing at the edge should be able to move the book while rendering an
     * audit answer.
     */
    public java.util.Optional<Book.Holding> holdingOnFile(String contractId) {
        return book.holding(contractId);
    }

    /**
     * A finished run's working papers, exposed for the trace endpoint.
     *
     * <p>The same five things {@code Completed} carries. A separate public type rather than
     * publishing {@code Completed} itself, so that what a reader may see is a decision this class
     * makes rather than a consequence of a private record's shape.
     *
     * @param runId         the run that published these figures
     * @param aggregate     the population accounting and the run-level invariants
     * @param computations  per-contract working papers, keyed by contract; a quarantined contract
     *                      is absent from this map and present in {@code aggregate.results()}
     * @param exceptions    the run's queue, as it now stands including any acceptances
     * @param policyStamps  the versions the run stamped, per kind
     */
    public record RunPapers(
        String runId,
        RunAggregate aggregate,
        Map<String, ContractComputation> computations,
        List<ExceptionRecord> exceptions,
        Map<PolicyKind, String> policyStamps) {

        public RunPapers {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(aggregate, "aggregate");
            computations = Map.copyOf(Objects.requireNonNull(computations, "computations"));
            exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
            policyStamps = Map.copyOf(Objects.requireNonNull(policyStamps, "policyStamps"));
        }

        /** This contract's working papers, or empty where the run published none for it. */
        public java.util.Optional<ContractComputation> computationFor(String contractId) {
            return java.util.Optional.ofNullable(computations.get(contractId));
        }

        /** This contract's result — computed or quarantined — or empty where it was not in the run. */
        public java.util.Optional<ContractResult> resultFor(String contractId) {
            for (ContractResult result : aggregate.results()) {
                if (result.contractId().equals(contractId)) {
                    return java.util.Optional.of(result);
                }
            }
            return java.util.Optional.empty();
        }
    }
}
