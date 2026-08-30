package com.crisil.eir.batch;

import com.crisil.eir.application.ContractResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a partition's results are committed, and the only thing that makes a restart honest
 * (ADR-0007, FR-905).
 *
 * <h2>Why a run needs this at all</h2>
 *
 * <p>Spring Batch gives a restart the framework half for free: a partition whose last execution
 * completed is not run again. It cannot give the other half, because it does not know what a
 * partition <em>produced</em>. So a restarted run holds, in memory, only the results of the
 * partitions this execution ran — and if the final aggregation is built from those, the completed
 * partitions' contracts have no figures, no exception records and no presence in the run at all.
 * {@code RunAggregate.unaccountedFor()} would then be the number of contracts the <em>previous</em>
 * execution computed, and the operator's reading of it would be "the restart lost work" when what
 * actually happened is that the report was built from the wrong set.
 *
 * <p>Worse, and this is the failure mode the module is built around: with the aggregation narrowed
 * to the population this execution happened to touch, the subtraction ties. Computed plus
 * quarantined equals the partitions that ran, nothing is unaccounted for, every control is green,
 * and a run over ten million contracts has published figures for the four hundred thousand in the
 * one partition that had failed. So the aggregate step reads the whole run back from here, over the
 * whole population, and the arithmetic {@code computed + quarantined + unaccounted = population} is
 * asserted against the population the run <em>started</em> with.
 *
 * <h2>Idempotence per contract is a requirement, not a convenience</h2>
 *
 * <p>{@link #recordSlice} is a <b>put</b> keyed by contract id, not an append. A partition that was
 * killed part-way through and re-run on restart recomputes contracts it had already computed — that
 * is what "restart from the last completed partition" means — and an appending store would then hold
 * two results for each of them. {@code RunAggregate.of} detects that and blocks the close with
 * "contract X has more than one result", which is the correct refusal and an entirely avoidable
 * one: the two results are identical, because the pipeline is deterministic and the boundary has not
 * moved. So a re-run overwrites, and the run reconciles.
 *
 * <p>Idempotence per <em>slice</em> is not required and not offered. A slice is written once, when it
 * has finished; a slice that was interrupted wrote nothing.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Required. ADR-0007's partitions run concurrently — that is the entire point of them — and every
 * one of them writes here. An implementation that is not safe under concurrent {@link #recordSlice}
 * loses a partition's results to a lost update, which arrives at the aggregate step as unaccounted
 * contracts: the most confusing possible presentation of a defect, because the partition's own step
 * completed successfully.
 *
 * <h2>What belongs in an implementation of this, and what does not</h2>
 *
 * <p>{@link InMemoryRunProgressStore} survives a job execution and not a process. It is the right
 * implementation for a single-process close and for every test in this module, and it is honestly
 * not the right one for a run that has to survive the machine going down — that implementation is a
 * table, it belongs in {@code eir-persistence}, and 04 § 2.9's period balance is most of its schema
 * already. The interface is here rather than there so that this module's restart semantics are
 * stated once and both implementations answer to them.
 */
public interface RunProgressStore {

    /**
     * Commits one partition's results.
     *
     * <p>Called once per partition, after {@code MonthEndRun} has accounted for the whole slice.
     * Every contract in the slice must be present in {@code SliceOutcome.results()} — computed or
     * quarantined, per {@code ContractResult}'s own contract — and {@link SliceRun} checks that
     * before calling, because a slice that committed a subset would be a partition that reports
     * success having lost contracts.
     *
     * @param runId   the run these results belong to
     * @param outcome the partition, its results and the policy reading it worked under
     */
    void recordSlice(String runId, SliceOutcome outcome);

    /**
     * Every result committed for the run so far, one per contract.
     *
     * <p>Ordered deterministically — by partition and then by position within the partition — so
     * that two runs over the same population produce this list in the same order whatever order the
     * partitions actually completed in. FR-903 requires byte-identical output from identical inputs,
     * and a concurrent partitioned run is precisely where an order that depends on thread scheduling
     * would creep in.
     */
    List<ContractResult> results(String runId);

    /** The contracts the run has committed a result for, computed or quarantined. */
    Set<String> contractsAccountedFor(String runId);

    /** The partitions committed so far, in commit order — a run report's progress line. */
    List<PartitionKey> recordedSlices(String runId);

    /**
     * Each committed partition's own policy stamps, in the same order as {@link #recordedSlices}.
     *
     * <p>The evidence {@link RunPolicyStamps#refuseUnlessTheRunAgreesWithItself} needs. Read from
     * here rather than accumulated in the job, because on a restart the partitions that completed in
     * the earlier execution are not run again — so an in-memory accumulation would compare the run
     * record against only the partitions this execution happened to run, and a
     * {@code PolicySource} that started answering differently overnight is precisely the case where
     * the earlier execution's partitions are the ones that disagree.
     */
    List<List<String>> policyStampsByPartition(String runId);

    /**
     * Records the run's own conclusion: the population accounting and the policy it worked under.
     *
     * <p>Written by the final aggregate step, read by {@link AmortisationJobRun} to answer
     * {@code AmortisationRun.execute}. Kept here rather than returned from the job because a Spring
     * Batch step returns an {@code ExitStatus} and not a value, and smuggling a {@code RunAggregate}
     * out through a step's execution context would put a ten-million-element object graph into the
     * job repository.
     */
    void recordCompletion(String runId, CompletedRun completion);

    /** The run's conclusion, or empty where the aggregate step has not run or did not complete. */
    Optional<CompletedRun> completion(String runId);
}
