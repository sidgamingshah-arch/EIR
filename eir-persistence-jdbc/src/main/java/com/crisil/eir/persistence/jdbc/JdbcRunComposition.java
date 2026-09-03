package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.SolveAudit;
import com.crisil.eir.batch.ContractPipelineFactory;
import com.crisil.eir.batch.PartitionGrainSource;
import com.crisil.eir.batch.PartitionKey;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The composition of {@code eir-batch} and the JDBC ports: the wiring that lets a partitioned
 * month-end run read a real database.
 *
 * <p><b>Why this class exists at all.</b> {@code eir-batch} was delivered against fake ports and
 * {@code eir-persistence-jdbc} was delivered against no runner, so the two were verified
 * independently and never together. {@code docs/08} recorded the consequence plainly: the JDBC
 * module's exit gate "is not 'the tests pass'; it is a close driven through these ports". This is the
 * seam that close goes through, and the five latent defects the module's review found are latent
 * precisely because nothing had constructed it.
 *
 * <p><b>What it is not.</b> It is not a Spring configuration and it registers no beans. The batch job
 * needs a {@code JobRepository}, a transaction manager and an executor, and those are a deployment's
 * to choose — an in-memory repository for a test, a JDBC one against
 * {@code BATCH_JOB_EXECUTION} for a real close. This class supplies only the three things that are
 * genuinely a function of the ports: the run request, the partition grain source, and the pipeline
 * factory. A caller assembles the job from those and its own infrastructure.
 *
 * <h2>The pipeline factory, and the one thing it must not share</h2>
 *
 * <p>Each slice gets a <b>fresh {@link SolveAudit}</b>. {@link ContractPipelineFactory}'s javadoc is
 * explicit about why: {@code SolveAudit} accumulates into an {@code ArrayList}, and a shared one
 * mutated by concurrent partitions corrupts in a way that quarantines <em>correct</em> contracts. The
 * routing registry, by contrast, is shared on purpose — a registry constructed per slice is a second
 * series that can drift from the one the run record stamps, which is a defect this codebase has
 * already found through invariant DT-1.
 *
 * <p>The period source is also per-slice, and that is not an optimisation. {@link JdbcAdapter#open}
 * opens a read-only connection with autocommit off and holds one transaction across a contract's
 * five reads, so two partitions sharing a source would interleave their reads inside one
 * transaction. Each slice therefore gets its own adapter over the shared {@link DataSource}, and the
 * pool does what a pool is for.
 */
public final class JdbcRunComposition {

    private final DataSource dataSource;
    private final String bookId;
    private final JdbcPorts ports;
    private final RoutingTableRegistry routing;

    /**
     * A composition over {@code dataSource} for one book, routing through the current default table.
     *
     * <p>The default table is the same one {@code EirService} and {@code InitialRecognition} use, so
     * a batch close and a console close route an event identically. A deployment that has approved
     * its own table passes it to the other constructor; what it must not do is construct a registry
     * per caller, because two registries are two answers to which reading governed a period.
     */
    public JdbcRunComposition(DataSource dataSource, String bookId) {
        this(dataSource, bookId, RoutingTableRegistry.of(RoutingTable.currentDefault()));
    }

    public JdbcRunComposition(DataSource dataSource, String bookId, RoutingTableRegistry routing) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.ports = new JdbcPorts(dataSource, bookId);
    }

    /** The seven ports this composition reads through, for a caller that needs one directly. */
    public JdbcPorts ports() {
        return ports;
    }

    /** The routing series the run stamps and every slice routes through. */
    public RoutingTableRegistry routing() {
        return routing;
    }

    /**
     * The run as a value: the boundary, the book, and the five ports {@code RunRequest} carries.
     *
     * <p>{@code RunRequest} does not carry the period source or the onboarding source — those reach
     * the run through the pipeline factory and through {@code InitialRecognition} respectively, which
     * is why this class supplies both separately rather than pretending one object is the whole
     * wiring.
     *
     * @param runId    the run id every journal, balance and exception is stamped with (04 § 2.13)
     * @param periodId the accounting period, {@code YYYYMM}
     * @param boundary the as-at boundary; every port read below resolves at it, and a replay
     *                 supplies the original run's {@code recorded_at} here
     */
    public RunRequest requestFor(String runId, int periodId, AsAtBoundary boundary) {
        return new RunRequest(
            runId, periodId, bookId, boundary,
            ports.contracts(), ports.contractState(),
            ports.coreBanking(), ports.generalLedger(), ports.policySource());
    }

    /** The {@code product × entity} grain, read from {@code contract}. */
    public PartitionGrainSource grains() {
        return new JdbcPartitionGrains(dataSource, bookId);
    }

    /**
     * A pipeline per partition, each with its own period source and its own solve audit.
     *
     * <p>The solver is {@link BracketedNewtonSolver}, the same one {@code InitialRecognition.standard}
     * wires: a batch close that solved by a different method from the recognition that produced the
     * rate would publish figures no replay of the original could reproduce.
     */
    public ContractPipelineFactory pipelines() {
        return (sliceRequest, key) -> pipelineFor(sliceRequest, key);
    }

    private ContractPipeline pipelineFor(RunRequest sliceRequest, PartitionKey key) {
        Objects.requireNonNull(key, "key");
        return new ContractPipeline(
            sliceRequest,
            new JdbcContractPeriodSource(dataSource, bookId),
            routing,
            SolveAudit.over(new BracketedNewtonSolver()));
    }
}
