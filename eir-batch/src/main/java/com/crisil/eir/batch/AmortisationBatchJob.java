package com.crisil.eir.batch;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.replay.PopulationAccount;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The month-end amortisation run as a Spring Batch job: partitioned, restartable, and gated on its
 * own population afterwards (ADR-0007, 05 § 3.2).
 *
 * <h2>Shape of the job</h2>
 *
 * <pre>
 *   verify-plan   →   amortise-population   →   aggregate-population
 *   (always runs)     (partitioned, one         (RunAggregate over the whole
 *                      partition per shard)      population; fails the run where
 *                                                it does not account for it)
 * </pre>
 *
 * <p>Three steps, and each of the three earns its place from a different clause of ADR-0007.
 *
 * <h3>{@code verify-plan} — and why it is {@code allowStartIfComplete(true)}</h3>
 *
 * <p>It stamps the partition plan's {@link PartitionPlan#fingerprint()} into the job's execution
 * context on the first execution and compares it on every subsequent one. That check has to run on a
 * <em>restart</em>, which is exactly when Spring Batch skips a step whose last execution completed —
 * so the step opts out of that skipping, and it is the only step here that does. A step that
 * verified the population had not moved and was skipped on the one execution where the population
 * could have moved would be a control that cannot fail; see {@link PartitionPlan#fingerprint()} for
 * the failure it exists to catch and why nothing downstream can see it.
 *
 * <h3>{@code amortise-population} — the partitioned step</h3>
 *
 * <p>One partition per {@link PopulationSlice}: {@code product × entity}, sub-sharded for skew. Each
 * partition is a {@code MonthEndRun} over its slice ({@link SliceRun}) and commits its results to the
 * {@link RunProgressStore} as its last act. On a restart Spring Batch's own
 * {@code SimpleStepExecutionSplitter} re-runs only the partitions whose last execution did not
 * complete — matched by partition <em>name</em>, which is why {@link PartitionKey#name()} has to be
 * stable and why the plan has to be deterministic.
 *
 * <p><b>No skip policy.</b> ADR-0007 asks for "a skip policy routing failed items to the exception
 * queue", and installing one here would duplicate FR-905's barrier with a weaker one — see
 * {@link SliceRun}, which has the argument. The barrier that runs is
 * {@code FailureIsolation.runBatch}, inside {@code MonthEndRun}, and it emits a
 * {@code ContractResult} for every contract rather than filtering any of them out.
 *
 * <h3>{@code aggregate-population} — the completion barrier</h3>
 *
 * <p>ADR-0007: "Aggregate invariants run as a final step after all partitions complete." This step
 * reads the whole run back from the store, builds one {@code RunAggregate} over the whole population,
 * and <b>fails the run</b> where the accounting does not tie. What it deliberately does not fail the
 * run on is stated at {@link #aggregateStep}.
 *
 * <h2>Why the commit boundary is the shard and not a finer chunk</h2>
 *
 * <p>ADR-0007 says "restart from the last committed chunk", and a chunk here is a shard: a partition
 * commits once, when its {@code MonthEndRun} has accounted for every contract in it. The finer
 * boundary — a Spring Batch {@code chunk(n)} step with a reader over contract ids, a processor that
 * computes one, and a writer that persists it — would give commit points every {@code n} contracts,
 * and it would get them by replacing {@code MonthEndRun}'s population loop with a
 * reader/processor/writer triple. That is the one thing this module must not do: FR-905's accounting
 * would then rest on a {@code SkipPolicy} and a {@code null}-returning processor agreeing with
 * {@code ContractResult} about what a quarantined contract is, and they do not agree — a filtered
 * item is an absence.
 *
 * <p>The cost of the coarser boundary is bounded and small, which is what makes the trade worth
 * taking: with a shard size of ten thousand, a kill at the worst possible moment loses at most ten
 * thousand contracts' work per in-flight partition, against a population of ten million and a
 * four-hour window. Recomputing that is seconds. Losing the guarantee that every contract is
 * accounted for is the defect this engine is built around.
 *
 * <h2>Nothing in this file computes anything</h2>
 *
 * <p>It builds three steps, reads a partition name out of an execution context, and counts
 * contracts. Every figure comes from {@code ContractPipeline} in {@code eir-application}. That is
 * the boundary ADR-0010's exemption is drawn at, and it is meant to stay visible in the diff: a step
 * that does arithmetic is the thing the exemption does not cover.
 */
public final class AmortisationBatchJob {

    /** The job name — the identity a restart is looked up under, with the job parameters. */
    public static final String JOB_NAME = "eir-amortisation-run";

    static final String VERIFY_PLAN_STEP = "verify-plan";
    static final String MASTER_STEP = "amortise-population";
    static final String WORKER_STEP = "amortise-partition";
    static final String AGGREGATE_STEP = "aggregate-population";

    /** Where a partition's own name is put for the worker step to read. */
    static final String PARTITION_NAME_KEY = "eir.partition.name";

    /** Where the plan's fingerprint is stamped, in the JOB execution context. */
    static final String PLAN_FINGERPRINT_KEY = "eir.plan.fingerprint";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TaskExecutor partitionExecutor;
    private final PartitionGrainSource grains;
    private final ContractPipelineFactory pipelines;
    private final RunProgressStore store;
    private final RunExceptionQueues queues;
    private final int maxShardSize;

    /**
     * @param jobRepository      Spring Batch's own metadata store; what remembers which partitions
     *                           completed
     * @param transactionManager the step transaction manager. {@code ResourcelessTransactionManager}
     *                           is the honest choice while the {@link RunProgressStore} is in
     *                           memory; a durable store makes this the store's transaction manager,
     *                           and that is the point at which a partition's commit and its step's
     *                           completion record become one transaction rather than two
     * @param partitionExecutor  where partitions run; a {@code SyncTaskExecutor} runs them one at a
     *                           time, which is what a determinism test wants and not what a close
     *                           window wants. Null means sequential
     * @param grains             the {@code product × entity} grain per contract
     * @param pipelines          one pipeline per partition — see {@link ContractPipelineFactory}
     * @param store              where a partition commits, and where the restart reads back from
     * @param queues             where each run's exception queue comes from — one per run, see
     *                           {@link RunExceptionQueues} for why a single shared queue blocks the
     *                           next period's close on this one's records
     * @param maxShardSize       the skew control and the restart granularity
     */
    public AmortisationBatchJob(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        TaskExecutor partitionExecutor,
        PartitionGrainSource grains,
        ContractPipelineFactory pipelines,
        RunProgressStore store,
        RunExceptionQueues queues,
        int maxShardSize) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.partitionExecutor =
            partitionExecutor == null ? new SyncTaskExecutor() : partitionExecutor;
        this.grains = Objects.requireNonNull(grains, "grains");
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.store = Objects.requireNonNull(store, "store");
        this.queues = Objects.requireNonNull(queues, "queues");
        if (maxShardSize < 1) {
            throw new IllegalArgumentException(
                "maxShardSize must be at least 1, got " + maxShardSize);
        }
        this.maxShardSize = maxShardSize;
    }

    /**
     * Plans the run and builds the job for it.
     *
     * <p><b>The population is enumerated here, once, before the job starts.</b> That is deliberate:
     * the plan has to exist before the partitioner can be asked for partitions, and the partitioner
     * is asked at step-execution time. Enumerating it inside the job instead would mean the
     * partitioner read {@code ContractSource} on every execution, so a restart would silently plan
     * over whatever the source answered that morning — which is the drift
     * {@link PartitionPlan#fingerprint()} exists to catch, and catching it requires having both
     * plans' identities to compare. This way the second plan is built, stamped and refused.
     *
     * @throws IllegalArgumentException where the plan does not reconstitute the population
     */
    public Job jobFor(RunRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> population = request.contracts().contractIdsInScope(request.boundary());
        Objects.requireNonNull(population,
            "ContractSource.contractIdsInScope returned null for run " + request.runId()
                + "; a run whose population is unknown cannot be accounted for");
        PartitionPlan plan = PartitionPlan.over(population, grains, maxShardSize);
        return jobFor(request, plan);
    }

    /** As {@link #jobFor(RunRequest)}, over a plan the caller already has. */
    public Job jobFor(RunRequest request, PartitionPlan plan) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        return new JobBuilder(JOB_NAME, jobRepository)
            .start(verifyPlanStep(request, plan))
            .next(partitionedStep(request, plan))
            .next(aggregateStep(request, plan))
            .build();
    }

    /**
     * The job parameters that identify this run.
     *
     * <p><b>What is in here is what a restart is matched on.</b> Spring Batch finds the
     * {@code JobInstance} by job name plus the identifying parameters, so launching the job twice
     * with these produces a second {@code JobExecution} on the same instance — a restart — and
     * launching it with a different run id produces a different run. Both behaviours are wanted:
     * "re-run the close" resumes, and a replay, which carries its own shadow run id
     * ({@code ReplayRequest.shadowRunId}), is a separate run rather than a restart of the one it
     * reproduces.
     *
     * <p>The boundary's {@code recordedAsAt} is in here too, as a <em>non</em>-identifying parameter.
     * It belongs on the record — it is the knowledge cut every port answered as at, and 04 § 2.13's
     * run record needs it — and it must not be identifying, because then re-running the same run id
     * at a different knowledge boundary would silently become a new job instance instead of being
     * refused as the mistake it is.
     */
    public JobParameters parametersFor(RunRequest request) {
        Objects.requireNonNull(request, "request");
        return new JobParametersBuilder()
            .addString("runId", request.runId())
            .addLong("periodId", (long) request.periodId())
            .addString("bookId", request.bookId())
            .addString("recordedAsAt", request.boundary().recordedAsAt().toString(), false)
            .toJobParameters();
    }

    /**
     * The identity of one execution's view of the world: the partition plan, and the boundary every
     * port answered as at.
     *
     * <p><b>Why the boundary is in here and not only on the job parameters.</b>
     * {@link #parametersFor} carries {@code recordedAsAt} as a <em>non</em>-identifying parameter,
     * deliberately — making it identifying would turn a re-run at a moved knowledge cut into a
     * different job instance, which is silently starting a new run rather than refusing a mistake.
     * But non-identifying meant nothing checked it at all, and the result was worse than either:
     * a run killed at a boundary of 1 June and resumed at a boundary of 9 June <em>completed</em>,
     * with twenty contracts carrying figures read at the first knowledge cut and five at the second,
     * and reported a clean close.
     *
     * <p>That contradicts the claim {@link SliceRun} rests on — "every partition reads the world as
     * at the same instant, which is what makes a partitioned run one consistent cut rather than
     * forty-eight of them" — and ADR-0007 rejected streaming precisely to avoid it: "streaming would
     * impose eventual consistency on a close that requires a consistent cut." A partitioned run
     * spread across two boundaries is that eventual consistency, arriving through the restart path.
     *
     * <p>So the boundary is stamped, the resume is refused, and the operator is told which of the two
     * things moved. Both halves of the boundary are in the stamp: {@code recordedAsAt} because it is
     * the knowledge cut, and {@code businessAsOf} because a resume at the same instant about a
     * different period end would be a different run wearing this one's id.
     */
    String runFingerprint(RunRequest request, PartitionPlan plan) {
        return plan.fingerprint()
            + ";businessAsOf=" + request.boundary().businessAsOf()
            + ";recordedAsAt=" + request.boundary().recordedAsAt()
            + ";replayOf=" + request.boundary().replayOf();
    }

    /**
     * Stamps the plan on the first execution and refuses a changed one on every later execution.
     *
     * <p>See {@link PartitionPlan#fingerprint()} for the defect. The write goes through
     * {@code jobRepository.updateExecutionContext} explicitly rather than relying on the step
     * handler to flush it, because the value has to survive <em>this</em> execution failing: the
     * whole point is to compare against a run that died.
     */
    private Step verifyPlanStep(RunRequest request, PartitionPlan plan) {
        Tasklet tasklet = (StepContribution contribution, ChunkContext chunkContext) -> {
            JobExecution jobExecution =
                chunkContext.getStepContext().getStepExecution().getJobExecution();
            ExecutionContext context = jobExecution.getExecutionContext();
            String fingerprint = runFingerprint(request, plan);
            String stamped = context.getString(PLAN_FINGERPRINT_KEY, null);
            if (stamped == null) {
                context.putString(PLAN_FINGERPRINT_KEY, fingerprint);
                jobRepository.updateExecutionContext(jobExecution);
            } else if (!stamped.equals(fingerprint)) {
                throw new IllegalStateException(
                    "this run was planned as [" + stamped + "] and is being resumed over ["
                        + fingerprint + "]; the population or the knowledge boundary moved between"
                        + " the two executions, so the partitions the restart skips as complete"
                        + " belong to one cut of the world and the ones it runs belong to another —"
                        + " and the aggregation over the union would tie against a set that never"
                        + " existed");
            }
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(VERIFY_PLAN_STEP, jobRepository)
            .tasklet(tasklet, transactionManager)
            // The one step that must run on a restart. See the class javadoc.
            .allowStartIfComplete(true)
            .build();
    }

    /** The partitioned step: one partition per shard, each a {@code MonthEndRun} over its slice. */
    private Step partitionedStep(RunRequest request, PartitionPlan plan) {
        return new StepBuilder(MASTER_STEP, jobRepository)
            .partitioner(WORKER_STEP, partitionerFor(plan))
            .step(workerStep(request, plan))
            // The grid is the plan, so the grid size is the partition count and never a cap on it.
            // A gridSize below the number of shards makes SimpleStepExecutionSplitter drop
            // partitions, which is contracts in no partition at all — PartitionPlan spends a whole
            // method refusing that upstream and it must not be reintroduced by a tuning parameter.
            .gridSize(plan.slices().size())
            .taskExecutor(partitionExecutor)
            .build();
    }

    /**
     * The partitions, named by {@link PartitionKey#name()}.
     *
     * <p><b>{@code gridSize} is ignored,</b> and that is the correct reading of ADR-0007 rather than
     * a shortcut. The partition count is a property of the book — how many {@code product × entity}
     * grains it has, and how badly the largest ones skew — not a thread-pool setting. A partitioner
     * that reshaped the population to fill a requested grid would be hash-partitioning it, and
     * ADR-0007 rejects that explicitly: a partition exists so that it "shares its configuration and
     * its cache" and so that a run report reads in "the units the controller thinks in". Concurrency
     * is set where concurrency belongs, on the task executor.
     */
    private static Partitioner partitionerFor(PartitionPlan plan) {
        return gridSize -> {
            Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
            for (PopulationSlice slice : plan.slices()) {
                ExecutionContext context = new ExecutionContext();
                // The name only. The slice's contract ids are resolved from the plan inside the
                // worker: ten million ids spread across the partitions' execution contexts would
                // put the whole population into the job repository, twice — once per execution —
                // and the job repository is not a place to store a population.
                context.putString(PARTITION_NAME_KEY, slice.key().name());
                partitions.put(slice.key().name(), context);
            }
            return partitions;
        };
    }

    /** One partition's step: run the slice, commit it, count what it committed. */
    private Step workerStep(RunRequest request, PartitionPlan plan) {
        Tasklet tasklet = (StepContribution contribution, ChunkContext chunkContext) -> {
            ExecutionContext context =
                chunkContext.getStepContext().getStepExecution().getExecutionContext();
            String partitionName = context.getString(PARTITION_NAME_KEY);
            PopulationSlice slice = plan.slice(partitionName).orElseThrow(
                () -> new IllegalStateException(
                    "partition " + partitionName + " is not in this run's plan (" + plan.describe()
                        + "); a partition the plan does not contain is a partition whose contracts"
                        + " nothing else will run, and its step completing would report success"
                        + " for work nobody did"));
            // A queue of this attempt's own, not the run's. SliceRun.execute explains why: a
            // re-run partition re-raises, and the run's queue has no way to tell a retry's record
            // from a first attempt's. The records that matter travel on the ContractResults and are
            // filed into the run's queue once, by the aggregate step.
            SliceOutcome outcome =
                SliceRun.execute(request, slice, pipelines, new ExceptionQueue());
            // Committed as the partition's last act. Nothing between here and the step's completion
            // record does any work, which is what makes "the step completed" and "the results are
            // in the store" the same fact for the purposes of a restart.
            store.recordSlice(request.runId(), outcome);
            contribution.incrementWriteCount(outcome.size());
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(WORKER_STEP, jobRepository)
            .tasklet(tasklet, transactionManager)
            .build();
    }

    /**
     * Files one {@code ExceptionRecord} into the run's queue per quarantined contract — once.
     *
     * <h4>Why the partitions do not file directly</h4>
     *
     * <p>Because a restart re-runs the partition that did not finish, and the barrier inside it files
     * again. {@link RunProgressStore#recordSlice} was made a per-contract <em>put</em> for exactly
     * this reason, so the <em>results</em> are idempotent across a resume — but the
     * {@code ExceptionQueue} the partitions were handed had no such semantics, and the observed
     * outcome was {@code queue.size() = 2} and {@code closeBlockers() = 2} for
     * {@code quarantinedCount() = 1}. The two records are not {@code equals} (their captured causes
     * are different throwables), so {@code ExceptionQueue.resolve} matches only one of them and the
     * close stays blocked on a duplicate nobody was told existed; 04 § 2.13's
     * {@code exceptions_raised} double-counts alongside it.
     *
     * <p>Nothing is lost by filing late. {@code ContractResult.isolated} carries the record for the
     * contract the barrier quarantined, the store keeps one result per contract, and
     * {@code MonthEndRun} has already used its own attempt's queue to attribute the record. So the
     * run's queue is derived from the surviving results, which is the same de-duplication the results
     * already have, applied to the queue.
     *
     * <h4>And why it is guarded rather than assumed to run once</h4>
     *
     * <p>The aggregate step itself can run more than once: it fails the run on a population that does
     * not add up, and a relaunch re-runs it. So a record is filed only where the queue holds none for
     * this contract <em>in this run</em> — the same {@code raisedByRunId} filter
     * {@code MonthEndRun.filedFor} uses, and for the same reason: a queue outlives a run.
     */
    private void fileExceptions(String runId, RunAggregate aggregate) {
        ExceptionQueue runQueue = queues.forRun(runId);
        Objects.requireNonNull(runQueue, "no exception queue for run " + runId);
        for (ContractResult result : aggregate.results()) {
            if (result.isComputed() || alreadyFiled(runQueue, runId, result.contractId())) {
                continue;
            }
            runQueue.raise(result.exception());
        }
    }

    /** Whether this run has already filed against this contract. */
    private static boolean alreadyFiled(ExceptionQueue queue, String runId, String contractId) {
        for (ExceptionRecord record : queue.forContract(contractId)) {
            if (record.raisedByRunId().equals(runId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The completion barrier: one {@code RunAggregate} over the whole population, and the run-level
     * refusal.
     *
     * <h4>What fails the run here</h4>
     *
     * <p>One condition, and it is a comparison of <b>identities</b> rather than of totals:
     * {@code PopulationAccount.of(population, results).addsUp()}, which is true only when no contract
     * in the population is missing a result <em>and</em> no result names a contract outside it.
     *
     * <p><b>It was a check on {@code unaccountedFor() != 0} and that was wrong.</b>
     * {@code RunAggregate.unaccountedFor()} is {@code populationSize − results.size()} and
     * {@code RunAggregate.of} <em>appends</em> a stranger to {@code results}, so one missing contract
     * and one stranger cancel to exactly nil. The input that reaches it is the overnight swap of
     * {@link PartitionPlan#fingerprint()}'s javadoc — one contract leaves scope, one enters, in the
     * same grain — and under the netting check the run completed and reported
     * "population 25, computed 25, quarantined 0, unaccounted 0" while a contract that entered scope
     * was never computed by anybody and a departed contract's stale result stood in for it. A gate
     * whose two failure modes cancel is a gate that reports a clean close over the exact defect it
     * was written to catch.
     *
     * <p>{@code PopulationAccount} is reused rather than reimplemented here: it already keeps
     * {@code unaccounted} and {@code extraneous} as separate lists of ids for
     * {@code ReplayUseCase}'s identical refusal, and its {@code addsUp()} requires both to be empty.
     * Two mechanisms for one question is this codebase's recurring defect.
     *
     * <h4>What deliberately does not fail the run</h4>
     *
     * <ul>
     *   <li><b>Quarantined contracts.</b> FR-905: "one malformed contract must not fail a
     *       ten-million-contract run." They are a blocking reason on {@code RunAggregate} and the
     *       close gate refuses on them (04 § 3) — that is the right place, because resolving an
     *       exception is an operator's job with a deadline of its own and there is no version of it
     *       that involves re-running the close.</li>
     *   <li><b>Aggregated invariant breaches (SL-2, ST-2).</b> They mean some contracts' figures do
     *       not agree with each other, and {@code CompletedRun.reportsCleanClose()} is false as a
     *       result. Failing the job on them would abort before the aggregate is written, leaving an
     *       operator with a stack trace where the thing they need is the aggregate: which contracts,
     *       by how much, and the total absolute residual. A run that produced a red report is more
     *       use than a run that produced no report.</li>
     * </ul>
     *
     * <p><b>What is deliberately not asserted here at all:</b> the identity
     * {@code computed + quarantined + unaccounted = populationSize}. It is true by construction of
     * {@code RunAggregate} — {@code quarantinedCount()} is {@code results.size() - computedCount()}
     * and {@code unaccountedFor()} is {@code populationSize - results.size()} — so a check on it
     * here could not fail whatever this module did wrong, and this codebase has recorded seventeen
     * controls that could not fail. It is asserted where it is a real claim: in the tests, over a
     * run that was actually killed and resumed.
     */
    private Step aggregateStep(RunRequest request, PartitionPlan plan) {
        Tasklet tasklet = (StepContribution contribution, ChunkContext chunkContext) -> {
            String runId = request.runId();

            // The run's own policy reading, and the check that every partition worked under it.
            // Before the aggregate, because a run whose partitions disagree about the rule has no
            // single reading to stamp on the aggregate in the first place.
            Map<PolicyKind, String> stamps = RunPolicyStamps.consultedBy(request);
            List<String> runRecord = RunPolicyStamps.asRunRecord(stamps);
            RunPolicyStamps.refuseUnlessTheRunAgreesWithItself(
                runId, runRecord, store.recordedSlices(runId),
                store.policyStampsByPartition(runId));

            // plan.population(), not the store's contracts: the denominator is the population the
            // run STARTED with. Taking it from the results would make the subtraction tie by
            // construction, which is the "processed 9,999,998" defect written as an aggregation.
            List<ContractResult> results = store.results(runId);
            RunAggregate aggregate = RunAggregate.of(
                runId, request.periodId(), plan.population(), results, runRecord);
            CompletedRun completed = new CompletedRun(aggregate, stamps);
            PopulationAccount account = PopulationAccount.of(plan.population(), results);

            // The run's exception queue, filled here and exactly once per quarantined contract.
            // See fileExceptions for why the partitions do not write to it directly.
            fileExceptions(runId, aggregate);

            // Written BEFORE the refusal below, so that a run which failed its own accounting still
            // leaves the report that says by how much and which contracts. An operator triaging a
            // short close at 03:00 needs the aggregate more than they need the job to have failed
            // tidily.
            store.recordCompletion(runId, completed);
            contribution.incrementWriteCount(aggregate.results().size());

            if (!account.addsUp()) {
                throw new IllegalStateException(
                    "run " + runId + " does not account for its population: " + account.describe()
                        + ". " + aggregate.describe()
                        + " — every contract in the population is computed or quarantined and never"
                        + " absent (FR-905), so a missing contract or a result naming a contract the"
                        + " population does not means the run published figures over a different set"
                        + " from the one it was asked about. Compared by identity and not by total,"
                        + " because one of each cancels");
            }
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(AGGREGATE_STEP, jobRepository)
            .tasklet(tasklet, transactionManager)
            .build();
    }
}
