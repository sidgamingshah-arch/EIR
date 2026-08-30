package com.crisil.eir.batch;

import java.util.List;
import java.util.Objects;

/**
 * One partition's work: a {@link PartitionKey} and the contracts assigned to it.
 *
 * <p>The unit a worker step runs, and — because the store is written once per completed slice — the
 * unit a restart replays. See {@link AmortisationBatchJob} for why the commit boundary sits here
 * rather than at a finer chunk inside the slice.
 *
 * <p>Contract ids are held in the order the population enumerated them, not sorted. FR-903 wants
 * byte-identical output from identical inputs, and the cheapest way to have that is for nothing
 * downstream to reorder anything: {@code FailureIsolation.runBatch} keeps insertion order, so a
 * slice that preserves population order produces its results in population order, and
 * {@code RunAggregate.of} then reassembles the whole population in its own order regardless of which
 * slice finished first.
 *
 * @param key         the partition
 * @param contractIds the contracts assigned to it, in population order; never empty
 */
public record PopulationSlice(PartitionKey key, List<String> contractIds) {

    public PopulationSlice {
        Objects.requireNonNull(key, "key");
        contractIds = List.copyOf(Objects.requireNonNull(contractIds, "contractIds"));
        if (contractIds.isEmpty()) {
            // An empty slice is not merely useless, it is misleading. It becomes a Spring Batch
            // partition that completes instantly having done nothing, and a run report showing
            // "48 of 48 partitions completed" over a population of nil reads exactly like a run
            // report over a population of ten million. RunAggregate's own javadoc names the
            // parent of this trap: "an aggregation over an empty population reports a clean
            // close". PartitionPlan never builds one, so this refuses a caller that would.
            throw new IllegalArgumentException(
                "partition " + key.name() + " was given no contracts; an empty partition completes"
                    + " instantly having measured nothing and inflates the partition count a run"
                    + " report is read from");
        }
    }

    /** How many contracts this partition must account for. */
    public int size() {
        return contractIds.size();
    }
}
