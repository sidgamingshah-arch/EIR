package com.crisil.eir.batch;

import com.crisil.eir.application.ContractResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link RunProgressStore} that outlives a job execution and not a process.
 *
 * <p><b>What it is for.</b> A single-process close, and every test in this module. The restart
 * semantics ADR-0007 requires — a partition that completed is not re-run, and its results are still
 * in the run's accounting afterwards — are a property of the <em>job</em> and this store, not of
 * where the bytes live: the job repository remembers which partitions completed, and this remembers
 * what they produced. Both survive a failed job execution and a fresh launch of the same run.
 *
 * <p><b>What it is honestly not for.</b> A run that must survive the machine going down. ADR-0007's
 * scenario — "a run that dies at hour three of a four-hour window against a close deadline" — is a
 * process dying, and the durable implementation of this interface is a table in
 * {@code eir-persistence} alongside 04 § 2.9's period balance. Not building it here is a scope
 * decision, not an oversight, and it is stated in the class name rather than in a comment nobody
 * reads: an operator wiring {@code InMemoryRunProgressStore} into a production close can see from
 * the type what it will do.
 *
 * <p><b>Concurrency.</b> {@link #recordSlice} is called from every partition worker at once. The
 * outer map is a {@link ConcurrentHashMap} and the per-run state is guarded by its own monitor;
 * {@link #results} takes the same monitor, so a reader never sees half a slice. A lost update here
 * would present as unaccounted contracts at the aggregate step even though every partition's step
 * completed — the single most misleading way this module could fail.
 */
public final class InMemoryRunProgressStore implements RunProgressStore {

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Override
    public void recordSlice(String runId, SliceOutcome outcome) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(outcome, "outcome");
        state(runId).record(outcome);
    }

    @Override
    public List<ContractResult> results(String runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId"));
        return state == null ? List.of() : state.results();
    }

    @Override
    public Set<String> contractsAccountedFor(String runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId"));
        return state == null ? Set.of() : state.contracts();
    }

    @Override
    public List<PartitionKey> recordedSlices(String runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId"));
        return state == null ? List.of() : state.recordedSlices();
    }

    @Override
    public List<List<String>> policyStampsByPartition(String runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId"));
        return state == null ? List.of() : state.policyStampsByPartition();
    }

    @Override
    public void recordCompletion(String runId, CompletedRun completion) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(completion, "completion");
        state(runId).recordCompletion(completion);
    }

    @Override
    public Optional<CompletedRun> completion(String runId) {
        RunState state = runs.get(Objects.requireNonNull(runId, "runId"));
        return state == null ? Optional.empty() : state.completion();
    }

    private RunState state(String runId) {
        return runs.computeIfAbsent(runId, id -> new RunState());
    }

    /** One run's committed slices, and its conclusion once the aggregate step has written it. */
    private static final class RunState {

        /**
         * Partition to its committed results, in commit order.
         *
         * <p>A {@link LinkedHashMap} under the monitor rather than a concurrent map, because
         * {@link #results()} has to be a consistent snapshot: a reader iterating a concurrent map
         * while a partition commits sees a slice that is present and empty, which arrives at the
         * aggregate step as that partition's whole contract list unaccounted for.
         */
        private final Map<PartitionKey, SliceOutcome> byPartition = new LinkedHashMap<>();
        private CompletedRun completion;

        synchronized void record(SliceOutcome outcome) {
            // PUT, not merge. A partition re-run after a mid-slice failure recomputes contracts it
            // had already computed; RunProgressStore's javadoc has the argument, and the visible
            // consequence of getting it wrong is RunAggregate blocking the close with "contract X
            // has more than one result" over two results that are identical.
            byPartition.put(outcome.key(), outcome);
        }

        synchronized List<ContractResult> results() {
            // Sorted by partition, then by position within the partition. Deterministic
            // independently of the order the partitions actually completed in, which is what
            // FR-903 needs from a concurrent runner — see RunProgressStore.results().
            List<PartitionKey> keys = new ArrayList<>(byPartition.keySet());
            keys.sort(Comparator.comparing(PartitionKey::product)
                .thenComparing(PartitionKey::entity)
                .thenComparingInt(PartitionKey::shard));
            // One result per contract. The put semantics above already make a partition's own
            // re-run idempotent; this collapses the other route to a duplicate, a contract that
            // somehow reached two partitions. It is deliberately NOT silent about which survives:
            // PartitionPlan refuses such a plan outright, so reaching here means the plan was
            // bypassed, and the first write wins so that the surviving figure is at least
            // reproducible rather than a function of iteration order.
            Map<String, ContractResult> byContract = new LinkedHashMap<>();
            for (PartitionKey key : keys) {
                for (ContractResult result : byPartition.get(key).results()) {
                    byContract.putIfAbsent(result.contractId(), result);
                }
            }
            return List.copyOf(byContract.values());
        }

        synchronized Set<String> contracts() {
            Set<String> ids = new LinkedHashSet<>();
            for (SliceOutcome outcome : byPartition.values()) {
                for (ContractResult result : outcome.results()) {
                    ids.add(result.contractId());
                }
            }
            return Set.copyOf(ids);
        }

        synchronized List<PartitionKey> recordedSlices() {
            return List.copyOf(byPartition.keySet());
        }

        synchronized List<List<String>> policyStampsByPartition() {
            List<List<String>> stamps = new ArrayList<>(byPartition.size());
            for (SliceOutcome outcome : byPartition.values()) {
                stamps.add(outcome.policyVersionIds());
            }
            return List.copyOf(stamps);
        }

        synchronized void recordCompletion(CompletedRun completed) {
            this.completion = completed;
        }

        synchronized Optional<CompletedRun> completion() {
            return Optional.ofNullable(completion);
        }
    }
}
