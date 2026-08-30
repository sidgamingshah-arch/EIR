package com.crisil.eir.batch;

import com.crisil.eir.application.replay.RunOutput;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.policy.PolicyKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a finished partitioned run concluded: the population accounting, and the policy reading it
 * worked under.
 *
 * <p><b>Why the policy stamps are carried separately from the aggregate.</b> {@code RunAggregate}
 * holds them as {@code List<String>} of {@code KIND=id} for the run record of 04 § 2.13, and
 * {@code RunOutput} — what {@code ReplayUseCase} compares — needs them as
 * {@code Map<PolicyKind, String>}. Parsing the run record's strings back into a typed map would make
 * DT-1's policy leg depend on a string format nobody owns, and {@code RunOutput}'s javadoc is
 * explicit that a run reporting no stamps is <em>adverse</em> rather than neutral: every kind the
 * registry says governed the period becomes a {@code POLICY_KIND_NOT_CONSULTED} finding and DT-1
 * fails. A parse that quietly produced an empty map on an unexpected separator would therefore fail
 * DT-1 for a reason that has nothing to do with either run. So the typed map is resolved once, by
 * {@link RunPolicyStamps}, and both forms are carried.
 *
 * <p>{@link RunPolicyStamps#refuseUnlessTheRunAgreesWithItself} is what keeps the two forms from
 * drifting; see it for the control and the input that fails it.
 *
 * @param aggregate               the population accounting and the aggregated per-contract
 *                                invariants, over the whole population and every partition
 * @param policyVersionsConsulted the version id the run cited per {@link PolicyKind}; a kind the run
 *                                did not consult is absent, never blank
 */
public record CompletedRun(
    RunAggregate aggregate, Map<PolicyKind, String> policyVersionsConsulted) {

    public CompletedRun {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(policyVersionsConsulted, "policyVersionsConsulted");
        // An EnumMap copy, so iteration follows PolicyKind's declaration order rather than hash
        // order — the choice RunOutput and ReplayRun both make, for the reason FR-903 gives: a
        // stamp map whose order varies between two runs of one job renders two differently-ordered
        // audit sentences for one reading.
        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        stamps.putAll(policyVersionsConsulted);
        policyVersionsConsulted = Map.copyOf(stamps);
    }

    /** The shape {@code ReplayUseCase} and {@code AmortisationRun} exchange. */
    public RunOutput toRunOutput() {
        // aggregate.results() and not the store's own list: RunAggregate holds one result per
        // contract in POPULATION order, which is the order ShadowRun's figure map is built in and
        // therefore the order FR-903's byte comparison sees. The store's order is deterministic but
        // it is partition order, and the two differ as soon as a grain is sharded.
        return RunOutput.of(aggregate.results(), policyVersionsConsulted);
    }

    /**
     * Whether this run may be reported as a clean close.
     *
     * <p>Delegated to {@code RunAggregate.reportsCleanClose()} rather than restated, and worth
     * naming what that includes: it is not "no breaches". A run that accounted for no contracts, a
     * run whose contracts computed and asserted no invariant, a run short of its own population and
     * a run with an unresolved quarantine are each a blocking reason there, and this module adds
     * none of its own — the partitioning does not change what a clean close is.
     */
    public boolean reportsCleanClose() {
        return aggregate.reportsCleanClose();
    }

    /** Why this run must not be reported as a clean close; empty where it may. */
    public List<String> blockingReasons() {
        return aggregate.blockingReasons();
    }

    /** The run record's own summary. */
    public String describe() {
        return aggregate.describe();
    }
}
