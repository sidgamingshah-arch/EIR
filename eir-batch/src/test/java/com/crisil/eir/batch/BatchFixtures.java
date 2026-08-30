package com.crisil.eir.batch;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.application.run.ContractPeriodSource;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.SolveAudit;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Reference case 1 at month 13, replicated across a small multi-product population, plus the wiring
 * a partitioned run needs.
 *
 * <h2>Where every figure comes from</h2>
 *
 * <p><b>Reference case 1</b> — a fixed-rate 24-month EMI loan of 1,000,000.00, EIR
 * <b>1.0421491800%</b> per month at the stored 12dp scale. Its month-13 row is stated in
 * {@code docs/reference-cases} and in this unit's brief, and it is used here because every figure in
 * it is published and none of it has to be computed to know what the run must produce:
 *
 * <ul>
 *   <li>opening gross carrying amount <b>528,407.32</b>;</li>
 *   <li>EIR interest <b>5,506.79</b> — hand check: {@code 528407.32 × 0.0104214918 =
 *       5506.79255243997600}, HALF_UP to paise → 5,506.79;</li>
 *   <li>instalment <b>47,073.47</b> (the true annuity 47,073.472223 rounded to paise);</li>
 *   <li>closing gross carrying amount <b>486,840.64</b> — hand check:
 *       {@code 528407.32 + 5506.79 − 47073.47 = 486840.64}.</li>
 * </ul>
 *
 * <p>That last figure is the anchor every test in this module asserts against, and it is the reason
 * the population is made of identical contracts: whatever the partitioning does, a computed contract
 * closes at 486,840.64 or the runner moved a figure. <b>None of it is read back from the code under
 * test.</b>
 *
 * <p>The cash split is the two published figures subtracted: contractual interest billed
 * <b>5,298.16</b> (reference case 5's suspense charge for the same month), so principal applied is
 * {@code 47073.47 − 5298.16 = 41775.31}. Splitting it by hand rather than deriving it is what makes
 * the journal's cash block balance <em>because the figures agree</em> rather than by construction —
 * see {@code PeriodJournal}, which takes each block's two sides from two different sources so that
 * SL-2 has something to detect.
 *
 * <h2>The solver is a landmine</h2>
 *
 * <p>{@link #EXPLODING_SOLVER} throws if it is ever reached. 05 § 3.2 puts the solve inside the
 * event branch only — "a fixed-rate contract with no events never re-solves. The steady-state run is
 * overwhelmingly roll-forward arithmetic, which is what makes the 10M-contract target reachable" —
 * and that is a cost requirement, invisible to any test that only compares figures. A run that
 * re-solved every contract would produce identical numbers. This population has no events, so a test
 * that passes with this solver wired in cannot have solved.
 *
 * <h2>The population's shape, and why it is that shape</h2>
 *
 * <p>{@link #standardPopulation()} is 25 contracts over three {@code product × entity} grains:
 * 12 × {@code RETAIL-EMI/IN-MUM}, 5 × {@code RETAIL-EMI/IN-DEL}, 8 × {@code CORP-TERM/IN-MUM}. With
 * {@link #SHARD_SIZE} of 10 that plans as four partitions — the retail Mumbai grain splits into a
 * full shard and a remainder of 2, which is ADR-0007's skew case, and the remainder is what an
 * integer division would silently discard.
 */
final class BatchFixtures {

    // ------------------------------------------------------------------ the run

    static final int PERIOD_ID = 202805;
    static final String BOOK_ID = "IN-RETAIL";

    /** The month-13 accrual boundary: the month-12 due date to the month-13 due date. */
    static final LocalDate PERIOD_START = LocalDate.of(2028, 4, 30);
    static final LocalDate PERIOD_END = LocalDate.of(2028, 5, 31);

    /**
     * The system-time boundary, supplied and never read from a clock.
     *
     * <p>A run that read {@code Instant.now()} at its own start could not be re-run to the same
     * boundary tomorrow, and "re-run the close" — which is what every restart test here does — is an
     * ordinary operational request (DT-1, ADR-0003).
     */
    static final Instant KNOWN_AT = Instant.parse("2028-06-01T00:00:00Z");

    // ------------------------------------------------------------------ figures

    /** Reference case 1's EIR, per month, at the stored 12dp scale. */
    static final Rate EIR = Rate.periodic(new BigDecimal("0.010421491800"), 12);

    /** Month-13 opening gross carrying amount. */
    static final Money OPENING_GCA = Money.inr("528407.32");

    /** Month-13 opening contractual balance, for the contractual-basis leg. */
    static final Money OPENING_CONTRACTUAL = Money.inr("529815.61");

    /** The billed EMI. */
    static final Money EMI = Money.inr("47073.47");

    /** Contractual interest billed in month 13. */
    static final Money BILLED_INTEREST = Money.inr("5298.16");

    /** {@code 47073.47 − 5298.16}, subtracted by hand from the two published figures. */
    static final Money PRINCIPAL_APPLIED = Money.inr("41775.31");

    /**
     * Month-13 closing gross carrying amount at presentation scale —
     * {@code 528407.32 + 5506.79 − 47073.47}.
     *
     * <p>The anchor. Every computed contract in every run in this module must close here once
     * reduced, and reference case 1's own roll-forward table states this figure.
     */
    static final Money CLOSING_GCA = Money.inr("486840.64");

    /**
     * The same balance <em>before</em> the presentation reduction.
     *
     * <p>{@code Money.atPresentationScale()} is "the only place a money value loses precision, and
     * it is called once per persisted figure" — so the roll-forward carries the accretion at full
     * precision and the run's own result does too. Hand-derived from the two published inputs and
     * the stored 12dp rate:
     *
     * <pre>
     *   528,407.32 x 0.010421491800 = 5,506.79255243997600   (the fixture's own hand check)
     *   528,407.32 + 5,506.79255243997600 = 533,914.11255243997600
     *   533,914.11255243997600 - 47,073.47 = 486,840.64255243997600
     * </pre>
     *
     * <p>Asserted alongside {@link #CLOSING_GCA} rather than instead of it, because the two claims
     * are different and both matter: the unrounded figure says the roll-forward is exact, and the
     * reduced one says it agrees with the published table. Comparing only the reduced figure would
     * hide a runner that reduced twice; comparing only the unrounded one would let a drift of a
     * paisa in the fifth decimal read as a match against nothing published.
     */
    static final BigDecimal CLOSING_GCA_UNROUNDED =
        new BigDecimal("486840.64255243997600");

    static final Money NIL = Money.zero(Money.INR);

    /** Uniform monthly periods with every flow on a boundary — reference case 1's shape. */
    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    // ------------------------------------------------------------------ population

    static final String RETAIL = "RETAIL-EMI";
    static final String CORP = "CORP-TERM";
    static final String MUM = "IN-MUM";
    static final String DEL = "IN-DEL";

    /** The skew control and the restart granularity. See the class javadoc for the shape it gives. */
    static final int SHARD_SIZE = 10;

    /** The engine's baseline reading of 03 § 6.1, effective 2026-05-01 and so in force here. */
    static final RoutingTableRegistry ROUTING =
        RoutingTableRegistry.of(RoutingTable.currentDefault());

    /** One effective routing-table policy version, so the run has something to stamp. */
    static final PolicyVersionRegistry POLICY = PolicyVersionRegistry.of(new PolicyVersion(
        "POL-RT-2028.1", PolicyKind.ROUTING_TABLE,
        "the bank's adoption of 03 § 6.1's baseline reading",
        LocalDate.of(2027, 4, 1), "policy.author", "policy.owner",
        LocalDate.of(2027, 3, 15), PolicyVersionStatus.EFFECTIVE));

    /** A solver that must never be reached. See the class javadoc. */
    static final RateSolver EXPLODING_SOLVER = request -> {
        throw new AssertionError(
            "the solver was called. 05 § 3.2 puts the solve inside the event branch only, and this"
                + " population has no events — a partitioned runner that re-solved would produce"
                + " identical figures and cost the close window");
    };

    private BatchFixtures() {
    }

    /** A contract id that carries its own grain: {@code product/entity/ordinal}. */
    static String contractId(String product, String entity, int ordinal) {
        return product + "/" + entity + "/" + String.format("%04d", ordinal);
    }

    static List<String> contracts(String product, String entity, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(contractId(product, entity, i));
        }
        return List.copyOf(ids);
    }

    /** 25 contracts over three grains — see the class javadoc for why these counts. */
    static List<String> standardPopulation() {
        List<String> population = new ArrayList<>(25);
        population.addAll(contracts(RETAIL, MUM, 12));
        population.addAll(contracts(RETAIL, DEL, 5));
        population.addAll(contracts(CORP, MUM, 8));
        return List.copyOf(population);
    }

    // ------------------------------------------------------------------ contract state

    /**
     * Reference case 1's terms.
     *
     * <p>{@code firstDueDate} 2027-05-31 makes {@code dueDate(12)} 2028-04-30 and {@code dueDate(13)}
     * 2028-05-31 — the period boundaries above. That correspondence is what lets
     * {@code ContractPipeline} derive the accrual length from the schedule independently of the
     * supplied vector's dates, which is the whole reason ST-2 is a control there rather than a
     * restatement.
     */
    static ContractTerms case1Terms() {
        return ContractTerms.of(
            Money.inr("1000000.00"),
            Rate.periodic(new BigDecimal("0.010000000000"), 12),
            24,
            12,
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 31),
            DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI,
            RateType.FIXED);
    }

    /** A performing contract at month 13: Stage 1, no allowance, the EMI received. */
    static ContractStateSource.OpeningState performingState() {
        return new ContractStateSource.OpeningState(
            case1Terms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL, Stage.STAGE_1, NIL,
            "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /**
     * The period's own flows: one boundary, one flow, carrying the ordinal 1 relative to the anchor.
     *
     * <p>Relative to the anchor and not to the contract: {@code AmortisationEngine} differences tau
     * from zero at the anchor, so a flow labelled 13 in a one-period segment would declare a
     * thirteen-period accrual and ST-2 would say so.
     */
    static ContractPeriod performingPeriod(String contractId) {
        FlowVector vector = FlowVector.of(PERIOD_START, Money.INR,
            List.of(CashFlow.of(PERIOD_END, 1, EMI, FlowKind.COMBINED_EMI)));
        return ContractPeriod.of(
            contractId, 13, vector, MONTHLY, PRINCIPAL_APPLIED, BILLED_INTEREST);
    }

    // ------------------------------------------------------------------ the request

    static AsAtBoundary boundary() {
        return AsAtBoundary.live(PERIOD_END, KNOWN_AT);
    }

    static RunRequest request(String runId, List<String> population, Set<String> withoutState) {
        return requestAsAt(runId, population, withoutState, KNOWN_AT);
    }

    /**
     * As {@link #request}, at a chosen knowledge boundary.
     *
     * <p>For one test: a run resumed at a <em>moved</em> {@code recordedAsAt}, which
     * {@code AmortisationBatchJob.runFingerprint} refuses. A partitioned run spread across two
     * knowledge cuts is the eventual consistency ADR-0007 rejected streaming to avoid.
     */
    static RunRequest requestAsAt(
        String runId, List<String> population, Set<String> withoutState, Instant knownAt) {
        return new RunRequest(
            runId, PERIOD_ID, BOOK_ID, AsAtBoundary.live(PERIOD_END, knownAt),
            new FakeContracts(population), new FakeState(withoutState),
            new FakeCoreBanking(), new FakeGeneralLedger(), new FakePolicy(POLICY));
    }

    // ------------------------------------------------------------------ ports

    /** The population, in run order. Immutable, so every partition sees the same answer. */
    record FakeContracts(List<String> ids) implements ContractSource {
        FakeContracts(List<String> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            return ids;
        }
    }

    /**
     * Every contract is reference case 1 at month 13, except those named in {@code withoutState}.
     *
     * <p>A contract with no opening state as at the boundary is the data condition FR-905 quarantines:
     * {@code ContractPipeline} throws with "a period cannot be rolled forward from a balance nobody
     * recorded, and inventing an opening of nil would publish a contract with no exposure and
     * reconcile perfectly", and the barrier turns that into a quarantined {@code ContractResult}.
     * It is how the tests get a quarantined contract without a malformed fixture.
     */
    record FakeState(Set<String> withoutState) implements ContractStateSource {
        FakeState(Set<String> withoutState) {
            this.withoutState = Set.copyOf(withoutState);
        }

        @Override
        public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
            return withoutState.contains(contractId)
                ? Optional.empty()
                : Optional.of(performingState());
        }
    }

    /** Empty, and legitimately so: RC-1 is the close gate's control, not the run's. */
    record FakeCoreBanking() implements CoreBankingFeed {
        @Override
        public List<com.crisil.eir.policy.reconciliation.CbsBilledInterest> billedInterest(
            AsAtBoundary boundary) {
            return List.of();
        }
    }

    /** Empty, for the same reason: SL-1 is the close gate's. */
    record FakeGeneralLedger() implements GeneralLedgerSource {
        @Override
        public List<com.crisil.eir.gl.posting.GlControlAccountBalance> controlAccountBalances(
            AsAtBoundary boundary) {
            return List.of();
        }
    }

    /**
     * One registry, handed to every caller, and it counts them.
     *
     * <p>The count matters: a partitioned run resolves the policy timeline once per partition inside
     * {@code MonthEndRun} and once more for the run record, so this port is called concurrently and
     * repeatedly. Returning the same immutable registry each time is what a correct adapter does, and
     * {@code RunPolicyStampsTest} has one that does not.
     */
    static final class FakePolicy implements PolicySource {
        private final PolicyVersionRegistry registry;
        final AtomicInteger calls = new AtomicInteger();

        FakePolicy(PolicyVersionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
            calls.incrementAndGet();
            return registry;
        }
    }

    // ------------------------------------------------------------------ grains

    /**
     * The grain from the contract id's own {@code product/entity} prefix.
     *
     * <p>A test convenience standing in for a grouped query against the contract master; see
     * {@link PartitionGrainSource} for why the real port is a batch call.
     */
    static final class PrefixGrains implements PartitionGrainSource {
        @Override
        public List<PartitionKey> grainsOf(List<String> contractIds) {
            List<PartitionKey> grains = new ArrayList<>(contractIds.size());
            for (String contractId : contractIds) {
                String[] parts = contractId.split("/");
                grains.add(PartitionKey.grain(parts[0], parts[1]));
            }
            return grains;
        }
    }

    // ------------------------------------------------------------------ the kill

    /**
     * A simulated process kill, mid-partition.
     *
     * <p><b>An {@link Error} and not a {@link RuntimeException}, deliberately.</b>
     * {@code FailureIsolation.isolate} catches {@code RuntimeException} and files it as a quarantined
     * contract; its javadoc states that "{@code Error} and any run-level invariant breach propagate".
     * A {@code RuntimeException} here would therefore test the barrier rather than the restart: the
     * contract would be quarantined, the partition would complete, and there would be nothing to
     * resume. The whole point of these tests is a partition that did <em>not</em> finish.
     */
    static final class SimulatedProcessKill extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedProcessKill(String message) {
            super(message);
        }
    }

    /**
     * The pipeline factory: one pipeline and one {@code SolveAudit} per partition, a compute counter,
     * and an arm-able mid-partition kill.
     *
     * <p>A fresh {@code SolveAudit} per call, which is not a detail — see
     * {@link ContractPipelineFactory}, where the argument is that {@code SolveAudit} accumulates into
     * an {@code ArrayList} and a shared one would be corrupted by concurrent partitions in a way that
     * quarantines correct contracts.
     */
    static final class RecordingPipelines implements ContractPipelineFactory {

        /** How many times each contract's period was actually read — i.e. computed. */
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        /** Partitions handed out, for asserting that a restart did not re-enter completed ones. */
        final Map<String, AtomicInteger> slicesRun = new ConcurrentHashMap<>();

        private volatile String killPartition;
        private volatile int killAfter;

        /** Arms the kill: in {@code partitionName}, throw when computing contract {@code after + 1}. */
        void killIn(String partitionName, int after) {
            this.killPartition = partitionName;
            this.killAfter = after;
        }

        /** Disarms the kill — what an operator restarting a fixed process has done. */
        void disarm() {
            this.killPartition = null;
        }

        int attemptsFor(String contractId) {
            AtomicInteger count = attempts.get(contractId);
            return count == null ? 0 : count.get();
        }

        int totalAttempts() {
            int total = 0;
            for (AtomicInteger count : attempts.values()) {
                total += count.get();
            }
            return total;
        }

        @Override
        public ContractPipeline forSlice(RunRequest sliceRequest, PartitionKey key) {
            slicesRun.computeIfAbsent(key.name(), name -> new AtomicInteger()).incrementAndGet();
            AtomicInteger seenInThisSlice = new AtomicInteger();
            ContractPeriodSource periods = (contractId, boundary) -> {
                if (key.name().equals(killPartition)
                    && seenInThisSlice.incrementAndGet() > killAfter) {
                    throw new SimulatedProcessKill(
                        "simulated process kill in partition " + key.name() + " while computing "
                            + contractId + ", after " + killAfter + " contract(s)");
                }
                attempts.computeIfAbsent(contractId, id -> new AtomicInteger()).incrementAndGet();
                return performingPeriod(contractId);
            };
            return new ContractPipeline(
                sliceRequest, periods, ROUTING, SolveAudit.over(EXPLODING_SOLVER));
        }
    }

    // ------------------------------------------------------------------ the harness

    /** A wired-up runner, so a test can assert on the store and the counters as well as the run. */
    record Harness(
        RunProgressStore store,
        RunExceptionQueues queues,
        RecordingPipelines pipelines,
        AmortisationBatchJob jobs,
        AmortisationJobRun runner,
        JobRepository jobRepository) {
    }

    static Harness harness() {
        return harness(new SyncTaskExecutor(), SHARD_SIZE);
    }

    /**
     * @param executor     where partitions run; a {@code SyncTaskExecutor} runs them one at a time
     * @param maxShardSize the skew control and the restart granularity
     */
    static Harness harness(TaskExecutor executor, int maxShardSize) {
        return harness(executor, maxShardSize, new InMemoryRunProgressStore());
    }

    /**
     * As {@link #harness(TaskExecutor, int)}, over a caller-supplied store.
     *
     * <p>The overload exists for one test: a store that loses a partition's commit, which is the
     * input the completion barrier of ADR-0007 exists to refuse.
     */
    static Harness harness(TaskExecutor executor, int maxShardSize, RunProgressStore store) {
        // One queue per run, not one per job: a shared queue makes period N's close gate refuse on
        // period N-1's unresolved blockers. See RunExceptionQueues.
        RunExceptionQueues queues = RunExceptionQueues.perRun();
        RecordingPipelines pipelines = new RecordingPipelines();
        JobRepository jobRepository = InMemoryJobRepository.create();
        AmortisationBatchJob jobs = new AmortisationBatchJob(
            jobRepository, new ResourcelessTransactionManager(), executor,
            new PrefixGrains(), pipelines, store, queues, maxShardSize);
        return new Harness(
            store, queues, pipelines, jobs,
            new AmortisationJobRun(jobs, launcher(jobRepository), store), jobRepository);
    }

    /**
     * A launcher whose task executor is synchronous.
     *
     * <p>{@code TaskExecutorJobLauncher}'s default is a {@code SyncTaskExecutor}, and it has to stay
     * one: {@link AmortisationJobRun} reads the job execution's status the moment {@code run}
     * returns, so an asynchronous launcher would have it read the status of a job that has barely
     * started.
     */
    static JobLauncher launcher(JobRepository jobRepository) {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        try {
            launcher.afterPropertiesSet();
        } catch (Exception unexpected) {
            throw new IllegalStateException("could not start the job launcher", unexpected);
        }
        return launcher;
    }

    /** The partition name a grain's shard has, for arming the kill. */
    static String partition(String product, String entity, int shard) {
        return new PartitionKey(product, entity, shard).name();
    }
}
