package com.crisil.eir.application.run;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.FailureIsolation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The population loop of 05 § 3.2: every contract in scope, each behind FR-905's fault barrier.
 *
 * <p><b>FR-905, and the reading of it that is wrong.</b> "Isolate failures per contract: one
 * malformed contract must not fail a ten-million-contract run." The cheap reading is to drop the
 * contract and carry on, and it fails invisibly — a run over 10,000,000 contracts that silently
 * processed 9,999,998 reconciles perfectly, because the two it dropped are absent from both sides of
 * every total. {@link ContractResult}'s javadoc is explicit that a quarantined contract is a
 * <em>result</em>, so this loop emits exactly one {@code ContractResult} per contract in the
 * population: figures where the contract computed, an exception record where the barrier caught it,
 * and never nothing.
 *
 * <p><b>Why {@link FailureIsolation#runBatch} rather than a loop of
 * {@link FailureIsolation#isolate}.</b> Two things come with it that a hand-rolled loop would have
 * to reproduce and would eventually stop reproducing: a duplicate-contract-id check that runs as a
 * pass of its own <em>before</em> anything is computed or filed, so a duplicate never leaves the
 * caller's queue holding entries from a run whose results were then discarded; and a
 * {@link java.util.LinkedHashMap} of results in input order, which FR-903's byte-identical replay
 * needs. The exception records are then recovered from the queue by contract and run id — the run id
 * is what 04 § 2.13 requires every exception to carry, and it is what makes the recovery
 * unambiguous when a queue outlives a run.
 *
 * <p><b>What is deliberately not here.</b> 05 § 3.2 opens with "verify all upstream feeds received +
 * versioned" and closes with the run-level conjunction of SL-1, PF-1, HB-1 and TG-1 against the GL
 * and the CBS. Both sit outside the loop in the diagram and outside this class in the code: the feed
 * gate precedes a run, and {@code eir-policy.close.PeriodCloseGate} owns the close. This class is
 * the loop, plus the accounting of who was in it.
 *
 * <p><b>No clock.</b> Every date and instant comes from {@code request.boundary()}, which is what
 * makes 05 § 3.3's replay this same class with a different boundary rather than a parallel one
 * (DT-1, ADR-0003).
 */
public final class MonthEndRun {

    private final RunRequest request;
    private final ContractPipeline pipeline;

    public MonthEndRun(RunRequest request, ContractPipeline pipeline) {
        this.request = Objects.requireNonNull(request, "request");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Runs the population.
     *
     * @param queue where quarantined contracts are filed; the same queue the close gate reads
     */
    public Completion execute(ExceptionQueue queue) {
        Objects.requireNonNull(queue, "queue");
        List<String> population = request.contracts().contractIdsInScope(request.boundary());
        Objects.requireNonNull(population, "contractIdsInScope");

        Map<String, ContractComputation> computed = FailureIsolation.runBatch(
            request.runId(), population, contractId -> contractId, pipeline::compute, queue);

        List<ContractResult> results = new ArrayList<>(population.size());
        for (String contractId : population) {
            ContractComputation computation = computed.get(contractId);
            results.add(computation != null
                ? computation.toContractResult()
                : ContractResult.isolated(contractId, filedFor(queue, contractId)));
        }

        RunAggregate aggregate = RunAggregate.of(
            request.runId(), request.periodId(), population, results, policyVersionsConsulted());
        return new Completion(aggregate, Collections.unmodifiableMap(computed));
    }

    /**
     * The exception the barrier filed for a contract that produced no figure.
     *
     * <p>Filtered by run id, not merely by contract. An {@link ExceptionQueue} is documented as
     * shared across partitions and can outlive one run, so "the last record for this contract" could
     * be a record from a previous close — and a {@code ContractResult} carrying it would attribute
     * this run's failure to last month's cause. The last record <em>for this run</em> is the right
     * one because the barrier files exactly one per quarantined contract per run and later entries
     * are its worked successors.
     *
     * @throws IllegalStateException where nothing was filed, which is a defect in the barrier or in
     *     this loop and not a fact about the contract; it must not become a silently dropped
     *     contract, which is the exact outcome the class javadoc rejects
     */
    private ExceptionRecord filedFor(ExceptionQueue queue, String contractId) {
        ExceptionRecord latest = null;
        for (ExceptionRecord record : queue.forContract(contractId)) {
            if (record.raisedByRunId().equals(request.runId())) {
                latest = record;
            }
        }
        if (latest == null) {
            throw new IllegalStateException(
                "contract " + contractId + " produced no figures in run " + request.runId()
                    + " and no exception was filed against it; the contract would be absent from"
                    + " both the results and the exception queue, which is the 9,999,998-of-"
                    + "10,000,000 failure FR-905's per-contract accounting exists to make"
                    + " impossible");
        }
        return latest;
    }

    /**
     * The policy versions in force at the run's own business date, stamped on the run record.
     *
     * <p>04 § 2.13 and FR-903: "the run's figures and the policy that produced them are one record".
     * Resolved once, at the boundary, and carried as data — <em>not</em> as an invariant. PV-1 asks
     * whether every date in a closed period resolves to exactly one version of each kind consulted,
     * which is a claim about a period and a population of dates rather than about this run's single
     * business date, and it already has a publication site in the close gate. Publishing a
     * one-date version of it here would put a weaker claim under an id that means the stronger one.
     */
    private List<String> policyVersionsConsulted() {
        Map<PolicyKind, PolicyVersion> inForce = request.policy()
            .policyVersions(request.boundary())
            .inForceOn(request.boundary().businessAsOf());
        List<String> stamped = new ArrayList<>(inForce.size());
        // Iterated over the enum's own order rather than the map's, so the stamp is byte-identical
        // between runs with the same inputs (FR-903) whatever the map implementation does.
        for (PolicyKind kind : PolicyKind.values()) {
            PolicyVersion version = inForce.get(kind);
            if (version != null) {
                stamped.add(kind + "=" + version.id());
            }
        }
        return List.copyOf(stamped);
    }

    /**
     * A finished run: the aggregate the close reads, and the working papers behind it.
     *
     * @param aggregate    the population accounting and the aggregated invariants
     * @param computations the per-contract detail, keyed by contract in population order — what
     *                     04 § 2.9's period balance is written from, and what an auditor asks for
     *                     when a single figure is queried
     */
    public record Completion(
        RunAggregate aggregate, Map<String, ContractComputation> computations) {

        public Completion {
            Objects.requireNonNull(aggregate, "aggregate");
            Objects.requireNonNull(computations, "computations");
        }

        /** Total solves the run performed, across every contract. */
        public int solveCount() {
            int solves = 0;
            for (ContractComputation computation : computations.values()) {
                solves += computation.solves();
            }
            return solves;
        }
    }
}
