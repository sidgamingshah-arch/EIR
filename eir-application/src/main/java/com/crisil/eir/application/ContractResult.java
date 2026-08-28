package com.crisil.eir.application;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import java.util.List;
import java.util.Objects;

/**
 * What one contract produced in one run — the unit that crosses from the per-contract loop to the
 * run-level aggregation (05 § 3.2).
 *
 * <p><b>A quarantined contract is a result, not an absence.</b> FR-905 requires per-contract
 * isolation: "one malformed contract must not fail a ten-million-contract run". The cheap reading
 * of that is to drop the contract and carry on, and it is wrong in a way that is invisible — a run
 * over 10,000,000 contracts that silently processed 9,999,998 reconciles perfectly, because the two
 * it dropped are absent from both sides of every total. So a failure is carried here as a value
 * with its {@code exception} populated and its figures absent, and the run-level count of contracts
 * has to add up.
 *
 * <p>Exactly one of {@code journal} and {@code exception} is present, which is the same discipline
 * {@code FailureIsolation.Outcome} enforces one module down and for the same reason: a result that
 * is both is a contract the engine both computed and refused, and neither the ledger nor the
 * exception queue should have to decide which.
 *
 * @param contractId the contract
 * @param closingGca the gross carrying amount carried out, or null where the contract was isolated
 * @param journal    the period's postings, or null where the contract was isolated
 * @param invariants the per-contract invariant results; empty where the contract was isolated,
 *                   because a contract that could not be computed has nothing to assert about
 * @param exception  the exception record, or null where the contract was computed
 */
public record ContractResult(
    String contractId,
    Money closingGca,
    JournalEntry journal,
    List<InvariantResult> invariants,
    com.crisil.eir.policy.exception.ExceptionRecord exception) {

    public ContractResult {
        Objects.requireNonNull(contractId, "contractId");
        invariants = List.copyOf(Objects.requireNonNull(invariants, "invariants"));
        if ((journal == null) == (exception == null)) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is exactly one of computed and isolated; "
                    + (journal == null ? "it is neither" : "it is both"));
        }
        if (exception != null && !invariants.isEmpty()) {
            // A contract the engine refused has no figures, so it has nothing to assert. Letting
            // invariant results ride along on a quarantined contract would put them in the
            // run-level conjunction, where a pass from a contract that was never computed reads as
            // coverage.
            throw new IllegalArgumentException(
                "contract " + contractId + " was isolated and carries " + invariants.size()
                    + " invariant results; a contract that could not be computed has nothing to"
                    + " assert about");
        }
    }

    /** A contract that computed. */
    public static ContractResult computed(
        String contractId, Money closingGca, JournalEntry journal,
        List<InvariantResult> invariants) {
        Objects.requireNonNull(journal, "journal");
        return new ContractResult(contractId, closingGca, journal, invariants, null);
    }

    /** A contract the barrier quarantined. */
    public static ContractResult isolated(
        String contractId, com.crisil.eir.policy.exception.ExceptionRecord exception) {
        Objects.requireNonNull(exception, "exception");
        return new ContractResult(contractId, null, null, List.of(), exception);
    }

    /** Whether this contract produced figures. */
    public boolean isComputed() {
        return journal != null;
    }

    /** The invariants that failed; empty on a clean contract and on an isolated one. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }
}
