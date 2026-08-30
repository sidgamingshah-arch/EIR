package com.crisil.eir.batch;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.run.ContractPipeline;

/**
 * Where a partition gets its {@code ContractPipeline} from.
 *
 * <h2>Why a factory, and one per partition</h2>
 *
 * <p><b>{@code ContractPipeline} is constructed against a {@code RunRequest}</b> and a partition runs
 * against a slice-scoped one — the same request but for its {@code ContractSource}, which answers
 * with the slice rather than the population ({@link SliceRun}). So the pipeline cannot be a
 * singleton the job holds; there is one per slice by construction.
 *
 * <p><b>And {@code SolveAudit} is not thread-safe.</b> It accumulates into an {@code ArrayList}, and
 * ADR-0007's partitions run concurrently. A single audit shared across partitions would be corrupted
 * under concurrent {@code solve}, and the corruption matters more than it looks: the audit is how
 * {@code ContractPipeline} proves 05 § 3.2's "the solve is inside the event branch only", the count
 * travels on {@code ContractComputation}, and its constructor refuses a computation whose solve count
 * is positive with no rate-resolving routing decision behind it. A lost or duplicated increment
 * therefore does not merely mis-report a statistic — it quarantines a contract that solved correctly,
 * or lets one through that solved when it should not have. An audit per partition removes the race
 * without weakening the control, and the counts are per-contract deltas so nothing is lost by
 * splitting them.
 *
 * <p>An implementation is therefore expected to build a <em>new</em> {@code SolveAudit} for each
 * call. The {@code ContractPeriodSource} and {@code RoutingTableRegistry} it closes over are
 * read-only and may be shared.
 *
 * <h2>What an implementation must not do</h2>
 *
 * <p>Read a clock, and nothing here needs to: every date and instant a pipeline uses reaches it
 * through {@code sliceRequest.boundary()}, which is what makes 05 § 3.3's replay the same job with a
 * different boundary. A factory that stamped {@code Instant.now()} onto anything would make the run
 * unreproducible in the one place DT-1 could not attribute it.
 */
@FunctionalInterface
public interface ContractPipelineFactory {

    /**
     * A pipeline for one partition.
     *
     * @param sliceRequest the run, scoped to this partition's contracts; its boundary, period, book,
     *                     run id and every other port are the run's own
     * @param key          the partition, for logging and for a per-partition cache key — the
     *                     {@code product × entity} grain ADR-0007 partitions on is exactly the grain
     *                     a policy or rule-set cache is keyed on
     */
    ContractPipeline forSlice(RunRequest sliceRequest, PartitionKey key);
}
