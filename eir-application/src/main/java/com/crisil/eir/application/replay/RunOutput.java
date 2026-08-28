package com.crisil.eir.application.replay;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.policy.PolicyKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one execution of the batch job produced: a result per contract, and the policy reading it
 * worked under.
 *
 * <p><b>Why the policy stamps are here and not on {@link ContractResult}.</b> FR-903 has two
 * halves — "replay any prior period <b>bit-identically</b> <b>under the policy then in force</b>"
 * — and the second one is a property of the <em>run</em>, not of a contract: 04 § 2.13 and
 * {@code amortisation_run}'s version columns stamp the interpretive hierarchy once per run.
 * {@link ContractResult} carries figures and invariant results and deliberately no policy id, so
 * a replay that only had contract results could compare figures and would have nothing to say
 * about the rule. That is exactly the half {@code ReplayComparison}'s javadoc records having
 * shipped as a no-op, so the run has to report it.
 *
 * <p><b>There is no default and no fallback, on purpose.</b> The tempting convenience — a run
 * that reports no stamps, with the replay harness filling in whatever the registry resolves — is
 * the defect {@code DiscrepancyKind.POLICY_KIND_NOT_CONSULTED} was added to catch: the harness
 * would then be comparing its own resolution against itself, the policy leg could not fail, and
 * two runs with no stamps at all would report a pass against a registry that held a version in
 * force. So a run that reports an empty map is <em>adverse</em> and reads that way: every kind the
 * registry says governed the period becomes a {@code POLICY_KIND_NOT_CONSULTED} finding and DT-1
 * fails. That is the correct outcome for a partly-wired job, and it is why no
 * {@code withNoPolicyStamps} factory exists here to make it look routine.
 *
 * @param contracts               one result per contract in the run's population — every one of
 *                                them, computed or quarantined; see {@link PopulationAccount} for
 *                                why the count has to add up
 * @param policyVersionsConsulted the version id the run actually cited per {@link PolicyKind}.
 *                                A kind the run did not consult is <b>absent</b>, never blank:
 *                                {@code ReplayRun} refuses a blank stamp for the same reason —
 *                                absent means not consulted, blank means a stamp somebody failed
 *                                to write, and one answer to "which kinds did this run consult"
 *                                is all a comparison can use
 */
public record RunOutput(
    List<ContractResult> contracts, Map<PolicyKind, String> policyVersionsConsulted) {

    public RunOutput {
        contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
        Objects.requireNonNull(policyVersionsConsulted, "policyVersionsConsulted");
        // An EnumMap copy, so iteration is in PolicyKind declaration order rather than hash
        // order. FR-903 wants byte-identical output from identical inputs, and a stamp map whose
        // order varies between two runs of the same job produces two differently-ordered audit
        // sentences for one reading. ReplayRun makes the same choice for the same reason.
        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        for (Map.Entry<PolicyKind, String> entry : policyVersionsConsulted.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "policy kind");
            String id = entry.getValue();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                    "the run stamps " + entry.getKey() + " with a blank policy version id; omit"
                        + " the kind if the run did not consult it — a blank stamp is a version"
                        + " nobody can resolve, not a kind nobody read");
            }
            stamps.put(entry.getKey(), id.strip());
        }
        policyVersionsConsulted = Map.copyOf(stamps);
    }

    /** A run's output. */
    public static RunOutput of(
        List<ContractResult> contracts, Map<PolicyKind, String> policyVersionsConsulted) {
        return new RunOutput(contracts, policyVersionsConsulted);
    }

    /** How many contracts produced figures. */
    public int computedCount() {
        return (int) contracts.stream().filter(ContractResult::isComputed).count();
    }

    /**
     * How many contracts the per-contract barrier quarantined (FR-905).
     *
     * <p>Reported rather than discarded. 05 § 4.5 has the close gate refuse on a non-zero
     * exception count, and {@link ContractResult}'s javadoc gives the reason this number must
     * exist at all: "a run over 10,000,000 contracts that silently processed 9,999,998 reconciles
     * perfectly, because the two it dropped are absent from both sides of every total".
     */
    public int quarantinedCount() {
        return contracts.size() - computedCount();
    }
}
