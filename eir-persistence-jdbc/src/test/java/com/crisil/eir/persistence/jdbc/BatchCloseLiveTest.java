package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.batch.AmortisationBatchJob;
import com.crisil.eir.batch.AmortisationJobRun;
import com.crisil.eir.batch.CompletedRun;
import com.crisil.eir.batch.InMemoryRunProgressStore;
import com.crisil.eir.batch.PartitionKey;
import com.crisil.eir.batch.RunExceptionQueues;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A partitioned month-end run driven through the JDBC ports against a live PostgreSQL cluster —
 * {@code eir-batch} and {@code eir-persistence-jdbc} exercised together for the first time.
 *
 * <p><b>Why this test is the module's real exit gate.</b> {@code eir-batch} was built against fake
 * ports and this module against no runner, so each was green on its own and the seam between them
 * had never been constructed. {@code docs/08} records five latent defects found by reviewing this
 * module after its first successful build, all of them latent for exactly one reason: nothing had
 * ever built a {@code JdbcPorts} and handed it to a run. This test builds one. Whatever it finds is
 * therefore a finding about the seam and not about either module's own arithmetic, which is the
 * pattern this repository has now recorded six times.
 *
 * <h2>Everything here is real except the population's size</h2>
 *
 * <p>A real PostgreSQL 16 cluster, the three migrations applied by Flyway, the seven ports over JDBC,
 * Spring Batch's own {@code JdbcJobRepository} against {@code BATCH_JOB_EXECUTION} — not an in-memory
 * stand-in, because a job repository is precisely the thing whose behaviour under a restart is the
 * point of ADR-0007 — and the production solver and routing table. What is small is the book:
 * {@code Fixtures} seeds a handful of contracts, and the scale question is answered separately and
 * honestly by {@code tools/load-harness/RESULTS.md}.
 *
 * <p><b>The executor is synchronous and must stay so.</b> {@code AmortisationJobRun} reads the job
 * execution's status the moment {@code run} returns, so an asynchronous launcher would have it read
 * the status of a job that had barely started. That is the same reasoning {@code BatchFixtures} gives
 * in {@code eir-batch}, restated here because this file has its own launcher.
 */
@Tag("live-db")
class BatchCloseLiveTest {

    /** Spring Batch's own PostgreSQL DDL, shipped in spring-batch-core. */
    private static final String BATCH_SCHEMA = "/org/springframework/batch/core/schema-postgresql.sql";

    private static DataSource dataSource;
    private static JdbcRunComposition composition;
    private static AmortisationJobRun runner;
    private static InMemoryRunProgressStore store;
    private static RunExceptionQueues queues;

    /** The boundary the run reads at: the fixture's whole feed set is visible here. */
    private static final AsAtBoundary NOW =
        AsAtBoundary.live(Fixtures.PERIOD_END, Fixtures.AS_AT_CORRECTED);

    @BeforeAll
    static void wire() throws Exception {
        dataSource = LiveDatabase.dataSource();
        Fixtures.seed(dataSource);
        applyBatchSchema(dataSource);

        composition = new JdbcRunComposition(dataSource, Fixtures.BOOK_ID);
        store = new InMemoryRunProgressStore();
        queues = RunExceptionQueues.perRun();

        PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        JobRepository jobRepository = jdbcJobRepository(dataSource, transactions);
        AmortisationBatchJob jobs = new AmortisationBatchJob(
            jobRepository, transactions, new SyncTaskExecutor(),
            composition.grains(), composition.pipelines(), store, queues, 1_000);
        runner = new AmortisationJobRun(jobs, launcher(jobRepository), store);
    }

    @Nested
    @DisplayName("a partitioned run over the JDBC ports")
    class TheRun {

        @Test
        @DisplayName("the population is enumerated, partitioned and accounted for")
        void theRunAccountsForItsPopulation() {
            RunRequest request = composition.requestFor(Fixtures.RUN_ID, Fixtures.PERIOD_ID, NOW);

            CompletedRun completed = runner.run(request);

            // FR-905's population accounting: every contract the source named is computed, excluded
            // or quarantined, and the count has to add up. A run short by one contract reconciles
            // perfectly, because the contract is absent from both sides of every total -- which is
            // why this is asserted before any figure.
            assertThat(completed.aggregate().results())
                .as("every enumerated contract carries a result")
                .isNotEmpty();
            assertThat(completed.aggregate().unaccountedFor())
                .as("FR-905: a contract no worker ran is a contract absent from both sides of"
                    + " every total, so it reconciles perfectly")
                .isZero();
            assertThat(completed.aggregate().computedCount()
                    + completed.aggregate().quarantinedCount())
                .as("computed plus quarantined must be the whole population")
                .isEqualTo(completed.aggregate().results().size());
            assertThat(completed.policyVersionsConsulted())
                .as("the run stamps the versions it read, so a replay can resolve the same ones")
                .isNotEmpty();
        }

        @Test
        @DisplayName("the run reports what it actually did, so a green test is not an empty run")
        void theRunIsNotEmpty() {
            RunRequest request = composition.requestFor(
                Fixtures.RUN_ID + "-census", Fixtures.PERIOD_ID, NOW);

            CompletedRun completed = runner.run(request);

            // WHAT THIS RUN ACTUALLY DID, pinned rather than printed. The first close through
            // these ports restated the contract from 528,407.32 to 46,538.28 -- a catch-up of
            // -481,869.04, 91% of the carrying amount, in one period -- and closed it to nil, with
            // CU-1, CU-2, SL-2 and ST-2 all satisfied and zero breaches.
            //
            // The chain, traced: the period carries a NEGOTIATED event which the baseline table
            // routes to MODIFICATION_TEST; that is a B5.4.6 restatement to the present value of the
            // REVISED flows at the original EIR; the revised vector holds ONE flow of 47,073.47
            // against a 24-period term at ordinal 13; so the restated balance is that flow's PV at
            // 0.0115. CatchUpCalculator says the roll to nil then follows "by construction".
            //
            // Asserted here, deliberately, as the CURRENT behaviour and not as correct behaviour.
            // The finding is recorded in docs/03 section 9 and the missing materiality threshold in
            // docs/10 as DR-06a. When that control lands this test must fail, and it should: a test
            // pinning a 91% write-down as unremarkable is the thing that has to break when somebody
            // finally bounds it. Derived by hand from the fixture's own inserts, not from output.
            com.crisil.eir.application.run.ContractComputation computed = composition.pipelines()
                .forSlice(request, com.crisil.eir.batch.PartitionKey.grain(
                    Fixtures.PRODUCT_ID, "ENT-01"))
                .compute(Fixtures.CONTRACT_ID);

            assertThat(computed.routing()).isNotNull();
            assertThat(computed.routing().mechanism().name())
                .as("a NEGOTIATED event under the baseline table is a modification test")
                .isEqualTo("MODIFICATION_TEST");
            assertThat(computed.catchUp()).as("which restates the carrying amount").isNotNull();
            assertThat(computed.catchUp().catchUp().atPresentationScale().amount())
                .as("-481,869.04 on an opening of 528,407.32 is a 91%% write-down in one period")
                .isEqualByComparingTo(new java.math.BigDecimal("-481869.04"));
            assertThat(computed.closingGca().isZero())
                .as("and the contract closes to nil, which CatchUpCalculator calls 'by"
                    + " construction' once the restated balance is the PV of those flows")
                .isTrue();
            assertThat(computed.catchUp().invariants())
                .as("CU-1 and CU-2 both report satisfied through all of it -- CU-2 cannot do"
                    + " otherwise, because catchUp is DEFINED as restated minus gcaBefore and CU-2"
                    + " asserts that same subtraction. This assertion is the record of a tautology,"
                    + " not evidence of a working control.\n"
                    + "\n"
                    + "DEMONSTRATED, not merely argued. Replacing the whole restatement in"
                    + " CatchUpCalculator with `Money restated = gcaBefore;` -- discarding the"
                    + " present-value calculation entirely -- makes the catch-up 0.00 and fails the"
                    + " assertion above, while CU-1 and CU-2 BOTH STAY GREEN. A control that"
                    + " survives the deletion of the arithmetic it exists to check is not a"
                    + " control, and no test can make it fail because its two inputs are the same"
                    + " subtraction. The fix is a second independent derivation of the restated"
                    + " balance (docs/03 section 9); the materiality threshold that would bound the"
                    + " catch-up is a Board decision, docs/10 DR-06a")
                .allMatch(com.crisil.eir.domain.InvariantResult::satisfied);
            assertThat(computed.invariants())
                .as("and nothing else breached either")
                .allMatch(com.crisil.eir.domain.InvariantResult::satisfied);

            assertThat(completed.aggregate().computedCount()
                    + completed.aggregate().quarantinedCount())
                .as("computed plus quarantined must be the whole population")
                .isEqualTo(completed.aggregate().results().size());
            assertThat(completed.policyVersionsConsulted())
                .as("the run stamps the versions it read, so a replay can resolve the same ones")
                .isNotEmpty();
        }


        @Test
        @DisplayName("the grain comes from the contract table, not from the contract id")
        void theGrainIsRead() {
            List<PartitionKey> grains =
                composition.grains().grainsOf(List.of(Fixtures.CONTRACT_ID));

            assertThat(grains).hasSize(1);
            assertThat(grains.get(0).product())
                .as("read from contract.product_id; anything derived from the id would be a second"
                    + " source for the pair a policy resolution is keyed on")
                .isEqualTo(Fixtures.PRODUCT_ID);
            assertThat(grains.get(0).shard())
                .as("shard ordinals are the plan's to assign and must be zero from a grain source")
                .isZero();
        }

        @Test
        @DisplayName("a contract with no grain on this book is refused, not silently dropped")
        void anUnplaceableContractIsRefused() {
            // The "processed 9,999,998" failure, arriving before any arithmetic. PartitionPlan.over
            // refuses a short answer; this asserts the grain source refuses first and names the
            // contract, because a population and a grain source pointed at different books is the
            // realistic cause and a count mismatch alone would not say so.
            String notOnThisBook = "99999999-9999-4999-8999-999999999999";

            assertThat(
                org.assertj.core.api.Assertions.catchThrowable(
                    () -> composition.grains().grainsOf(List.of(notOnThisBook))))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining(notOnThisBook)
                .hasMessageContaining("FR-905");
        }
    }

    // ----------------------------------------------------------------- infrastructure

    /**
     * Spring Batch's own tables, applied to the live cluster.
     *
     * <p>Applied here rather than by Flyway on purpose: they are the batch framework's schema, not
     * the engine's, and putting them in {@code eir-persistence}'s migrations would make the engine's
     * DDL depend on a framework version. {@code LiveDatabase} drops and recreates {@code public} on
     * each run, so these go in after it.
     */
    private static void applyBatchSchema(DataSource source) throws SQLException, IOException {
        String ddl;
        try (InputStream resource = BatchCloseLiveTest.class.getResourceAsStream(BATCH_SCHEMA)) {
            if (resource == null) {
                throw new IllegalStateException(
                    "spring-batch-core does not carry " + BATCH_SCHEMA + "; a job repository cannot"
                        + " be created and this test would otherwise report a wiring defect");
            }
            ddl = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static JobRepository jdbcJobRepository(
        DataSource source, PlatformTransactionManager transactions) throws Exception {

        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionManager(transactions);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    private static JobLauncher launcher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }

    /** Unused today; kept so a reader can see the job is a {@link Job} and not a bespoke runnable. */
    @SuppressWarnings("unused")
    private static Job unusedJobTypeWitness() {
        return null;
    }
}
