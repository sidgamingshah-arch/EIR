package com.crisil.eir.batch;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.MonthEndRun;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.policy.exception.ExceptionQueue;
import java.util.List;
import java.util.Objects;

/**
 * One partition's work: {@code MonthEndRun} over a slice of the population (05 § 3.2, ADR-0007).
 *
 * <h2>This class runs no loop of its own</h2>
 *
 * <p>The population loop already exists, and reimplementing it here would be the whole cost of this
 * module with none of the benefit. {@code MonthEndRun} enumerates the contracts in its request's
 * scope, runs each through {@code FailureIsolation.runBatch}, recovers the exception record the
 * barrier filed for each contract that produced no figure, and returns a {@code RunAggregate} in
 * which every contract in the population is either computed or quarantined and never absent. A
 * Spring Batch reader/processor/writer triple over the same contracts would reproduce all of that
 * approximately — and then FR-905's accounting would depend on whether a {@code SkipPolicy} and a
 * {@code null}-returning {@code ItemProcessor} happened to agree with {@code ContractResult}'s
 * definition of a quarantined contract, which they do not: a filtered item is an absence, and
 * {@code ContractResult}'s javadoc is written specifically to reject absences.
 *
 * <p>So a partition <b>is</b> a {@code MonthEndRun}, over its own slice, and this class is the
 * eleven lines that scope the request to the slice and check the result.
 *
 * <h2>The slice-scoped request</h2>
 *
 * <p>Everything is the run's own — run id, period, book, boundary, and all five ports — but the
 * {@code ContractSource}, which answers with the slice. Two consequences worth stating:
 *
 * <ul>
 *   <li><b>The run id does not change.</b> Exception records are stamped with it (04 § 2.13), and
 *       {@code MonthEndRun.filedFor} recovers a contract's record by contract <em>and</em> run id.
 *       A per-partition run id would make each partition's exceptions belong to a run that does not
 *       exist in {@code amortisation_run}, and the close gate reading the queue would find none of
 *       them under the run it is closing.</li>
 *   <li><b>The boundary does not change.</b> Every partition reads the world as at the same instant,
 *       which is what makes a partitioned run one consistent cut rather than forty-eight of them —
 *       ADR-0007 rejected streaming for exactly this reason: "streaming would impose eventual
 *       consistency on a close that requires a consistent cut".</li>
 * </ul>
 *
 * <h2>Ports are read concurrently</h2>
 *
 * <p>{@code ContractStateSource}, {@code ContractPeriodSource}, {@code PolicySource},
 * {@code CoreBankingFeed} and {@code GeneralLedgerSource} are called from every partition at once.
 * All five are read-only queries against an as-at boundary, so a correct implementation is naturally
 * safe; an implementation that caches into a plain {@code HashMap} on first read is not, and that is
 * a requirement on the adapter rather than something this class can defend against.
 * {@code ExceptionQueue} is safe — its own javadoc names ADR-0007 and "several partitions raise into
 * one queue at once" as the reason every method is synchronized.
 */
public final class SliceRun {

    private SliceRun() {
    }

    /**
     * Runs one partition and returns one result per contract in it.
     *
     * @param runRequest the run — the population-scoped request, whose contract source is replaced
     * @param slice      the partition's contracts
     * @param pipelines  where this partition's pipeline comes from; see
     *                   {@link ContractPipelineFactory} for why it is per-partition
     * @param queue      the run's exception queue, shared across partitions
     * @return the partition's committed outcome: one {@code ContractResult} per contract in
     *         {@code slice}, in slice order, and the policy reading this partition worked under
     * @throws IllegalStateException where the slice's own run did not account for the slice — see
     *                               below
     */
    public static SliceOutcome execute(
        RunRequest runRequest,
        PopulationSlice slice,
        ContractPipelineFactory pipelines,
        ExceptionQueue queue) {
        Objects.requireNonNull(runRequest, "runRequest");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(pipelines, "pipelines");
        Objects.requireNonNull(queue, "queue");

        RunRequest sliceRequest = scopedTo(runRequest, slice);
        ContractPipeline pipeline = pipelines.forSlice(sliceRequest, slice.key());
        Objects.requireNonNull(pipeline,
            "the pipeline factory returned null for partition " + slice.key().name());

        RunAggregate aggregate = new MonthEndRun(sliceRequest, pipeline).execute(queue).aggregate();

        // Checked here as well as at the run level, and not as belt and braces. The run-level
        // check subtracts results from the population and reports a number; this one attributes a
        // shortfall to the partition that caused it while the partition is still on the stack, which
        // is the difference between "the run is 240 contracts short" and "partition
        // RETAIL-EMI|IN-MUM|shard3 returned 9,760 of 10,000". At ten million contracts across
        // forty-eight partitions the first sentence costs a day of triage.
        //
        // What input makes it fail: a MonthEndRun whose ContractSource answered with a different
        // list on the second call than the one the plan was built from — a source that reads current
        // state rather than its boundary. scopedTo() closes that route by handing MonthEndRun an
        // immutable list, so reaching this throw means the guarantee was broken somewhere this
        // module can no longer see, which is exactly when a loud refusal is worth more than a
        // figure.
        if (aggregate.populationSize() != slice.size() || aggregate.unaccountedFor() != 0) {
            throw new IllegalStateException(
                "partition " + slice.key().name() + " was given " + slice.size()
                    + " contract(s) and its run accounted for " + aggregate.populationSize()
                    + " with " + aggregate.unaccountedFor() + " unaccounted for; a partition that"
                    + " commits fewer contracts than it was given is the run-level shortfall"
                    + " FR-905's per-contract accounting exists to make impossible, arriving one"
                    + " level down where the run cannot attribute it");
        }
        return new SliceOutcome(
            slice.key(), aggregate.results(), aggregate.policyVersionIds());
    }

    /**
     * The run's own request, with its {@code ContractSource} narrowed to the slice.
     *
     * <p>A fixed list rather than a filter over the population source: the source is not called
     * again, so it cannot answer differently the second time. That is a real hazard rather than a
     * hypothetical — {@code ReplayUseCase} enumerates the population a second time specifically
     * because "a {@code ContractSource} whose answer depends on when it is asked rather than on the
     * boundary it is handed is a port that reads a clock" — and here a second answer would mean the
     * partition ran a different population from the one the plan assigned it, with no way to notice.
     */
    static RunRequest scopedTo(RunRequest runRequest, PopulationSlice slice) {
        return new RunRequest(
            runRequest.runId(),
            runRequest.periodId(),
            runRequest.bookId(),
            runRequest.boundary(),
            new SliceContracts(slice.contractIds()),
            runRequest.contractState(),
            runRequest.coreBanking(),
            runRequest.generalLedger(),
            runRequest.policy());
    }

    /**
     * The partition's contracts, as a {@code ContractSource}.
     *
     * <p>The boundary is accepted and ignored, and that is correct rather than lazy: the population
     * this list came from was enumerated at the run's boundary, once, before the plan was built. A
     * source that re-derived the slice from the boundary here would be answering the same question
     * twice and could answer it differently.
     */
    private record SliceContracts(List<String> contractIds) implements ContractSource {

        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            return contractIds;
        }
    }
}
