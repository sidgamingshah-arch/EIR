package com.crisil.eir.batch;

import com.crisil.eir.application.ContractResult;
import java.util.List;
import java.util.Objects;

/**
 * What one partition committed: a result per contract, and the policy reading it worked under.
 *
 * <p><b>Why the policy stamps travel with the results rather than being resolved once for the run.</b>
 * They are resolved once for the run — {@link RunPolicyStamps#consultedBy} — and that is exactly why
 * each partition's own reading has to be carried alongside its figures. {@code MonthEndRun} resolves
 * the timeline itself, per partition, from the same {@code PolicySource} at the same boundary, so
 * forty-eight partitions produce forty-eight independent answers to one question. Discarding them
 * would leave the run record's single answer unfalsifiable; keeping them turns
 * {@link RunPolicyStamps#refuseUnlessTheRunAgreesWithItself} into a control with something to
 * compare. See that method for the input that fails it.
 *
 * @param key               the partition
 * @param results           one per contract in the slice, in slice order — computed or quarantined,
 *                          never absent ({@code ContractResult})
 * @param policyVersionIds  the {@code KIND=id} stamps this partition's own run resolved
 */
public record SliceOutcome(
    PartitionKey key, List<ContractResult> results, List<String> policyVersionIds) {

    public SliceOutcome {
        Objects.requireNonNull(key, "key");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        policyVersionIds = List.copyOf(Objects.requireNonNull(policyVersionIds, "policyVersionIds"));
    }

    /** How many contracts this partition committed. */
    public int size() {
        return results.size();
    }
}
