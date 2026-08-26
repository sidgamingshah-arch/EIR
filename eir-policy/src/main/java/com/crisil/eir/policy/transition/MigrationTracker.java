package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ACPIR 21 and ACPIR 50 tracked as two obligations over a population (04 § 6, invariant TM-1).
 *
 * <p><b>What TM-1 asserts, and what it deliberately does not.</b> It asserts that both obligations
 * are <em>tracked</em> — that every contract in the population has a recorded ECL discount basis —
 * and that the interim concession has not outlived its deadline. It does not assert that the
 * migration is finished, and that distinction is the whole design.
 *
 * <p>The obvious formulation is "fail while any contract is still on the interim basis". It fails
 * continuously from 2027 to 2030, and a breach blocks the close ([03 § 9](../../../../../../../../docs/03-calculation-spec.md#9-invariants))
 * — so it would block every close for three years while describing a state ACPIR 50 explicitly
 * permits. A control that is red by design is a control that gets suppressed, and then it is not
 * there for the year it matters.
 *
 * <p>So the size of the remaining migration is published as plain data
 * ({@link #outstandingAcpir50Migrations()}), which is what a programme tracks, and the invariant
 * fires on the two things that are genuine failures on the day they occur: an untracked contract,
 * and an unmigrated one past the deadline.
 *
 * <p><b>Untracked is the worse of the two.</b> A contract with no recorded basis is not a contract
 * that has not migrated — it is one whose position nobody knows, and 04 § 6's whole argument for a
 * separate table is that a single flag hides the gap. A population that reports zero outstanding
 * migrations because half of it was never presented is the failure this catches.
 */
public final class MigrationTracker {

    private final Map<String, ContractMigrationState> byContract;
    private final long contractsInPopulation;

    private MigrationTracker(
        Map<String, ContractMigrationState> byContract, long contractsInPopulation) {
        this.byContract = byContract;
        this.contractsInPopulation = contractsInPopulation;
    }

    /**
     * A tracker over the states recorded, and the population they were drawn from.
     *
     * @param states                what was recorded, one per contract
     * @param contractsInPopulation how many contracts the period covers, from the contract master
     */
    public static MigrationTracker over(
        Collection<ContractMigrationState> states, long contractsInPopulation) {
        Objects.requireNonNull(states, "states");
        if (contractsInPopulation < states.size()) {
            // More recorded states than contracts means the population figure is wrong or a
            // contract appears twice, and either way the untracked count below would come out
            // negative and read as complete coverage.
            throw new IllegalArgumentException(
                states.size() + " migration states were recorded for a population of "
                    + contractsInPopulation + " contracts; the untracked count cannot be negative,"
                    + " so one of the two figures is wrong");
        }
        Map<String, ContractMigrationState> byContract = new LinkedHashMap<>();
        for (ContractMigrationState state : states) {
            ContractMigrationState existing = byContract.putIfAbsent(state.contractId(), state);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "contract " + state.contractId() + " has two recorded migration states"
                        + " (periods " + existing.periodId() + " and " + state.periodId()
                        + "); a tracker covers one period, and two answers for one contract is"
                        + " not a position");
            }
        }
        return new MigrationTracker(Map.copyOf(byContract), contractsInPopulation);
    }

    /**
     * Invariant TM-1 as at {@code asOf}: both obligations tracked, and the concession not
     * outlived.
     *
     * @param asOf the date the assertion is made on; the deadline test is against it
     */
    public InvariantResult migrationTracked(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        long untracked = untrackedContracts();
        List<String> pastDeadline = byContract.values().stream()
            .filter(state -> state.isPastTheAcpir50Deadline(asOf))
            .map(ContractMigrationState::contractId)
            .sorted()
            .toList();

        long breaches = untracked + pastDeadline.size();
        if (breaches == 0) {
            return InvariantResult.pass(InvariantId.TM_1,
                contractsInPopulation + " contracts all carry a recorded ECL discount basis"
                    + (asOf.isAfter(LegacyCohort.ACPIR_50_DEADLINE)
                        ? ", and none remains on the interim basis after the deadline"
                        : ", with " + outstandingAcpir50Migrations()
                            + " still to migrate under the ACPIR 50 concession"));
        }

        List<String> reasons = new ArrayList<>();
        if (untracked > 0) {
            reasons.add(untracked + " contracts have no recorded ECL discount basis, so their"
                + " position is unknown rather than outstanding");
        }
        if (!pastDeadline.isEmpty()) {
            reasons.add(pastDeadline.size() + " contracts remain on the interim contractual basis"
                + " after the 31 March 2030 deadline"
                + (pastDeadline.size() > 20
                    ? " (first 20: " + pastDeadline.subList(0, 20) + ")"
                    : ": " + pastDeadline));
        }
        return InvariantResult.fail(InvariantId.TM_1,
            String.join("; ", reasons), BigDecimal.valueOf(breaches));
    }

    /**
     * How many contracts are on the EIR for interest and still discounting ECL at the contractual
     * rate — the population the migration has to work through.
     *
     * <p>Plain data. Before the deadline this is progress, not a defect: it is the shape ACPIR 50's
     * concession describes. 08's warning is about the reading rather than the number — the
     * concession buys time on ECL discounting and must not be read as a general deferral.
     */
    public long outstandingAcpir50Migrations() {
        return byContract.values().stream()
            .filter(ContractMigrationState::isUnderTheAcpir50Concession)
            .count();
    }

    /** Contracts whose ECL moved to the EIR before recognition did — a sequencing signal. */
    public List<ContractMigrationState> eclAheadOfInterest() {
        return byContract.values().stream()
            .filter(ContractMigrationState::eclIsAheadOfInterest)
            .toList();
    }

    /** Contracts still outside the EIR regime altogether (ACPIR 21 outstanding). */
    public long outstandingAcpir21Migrations() {
        return byContract.values().stream()
            .filter(state -> !state.satisfiesAcpir21())
            .count();
    }

    /**
     * Contracts in the population with no recorded basis at all.
     *
     * <p>The figure that makes a clean migration report meaningful or meaningless. A population
     * reporting zero outstanding migrations because half of it was never presented is exactly the
     * failure 04 § 6 gives the discount basis its own table to prevent.
     */
    public long untrackedContracts() {
        return contractsInPopulation - byContract.size();
    }

    /** The state recorded for a contract, if one was. */
    public ContractMigrationState stateOf(String contractId) {
        return byContract.get(contractId);
    }

    /** How many contracts carry a recorded basis. */
    public int trackedContracts() {
        return byContract.size();
    }

    /** A one-line migration position. */
    public String describe(LocalDate asOf) {
        return "as at " + asOf + ": " + trackedContracts() + " of " + contractsInPopulation
            + " contracts tracked, ACPIR 21 outstanding on " + outstandingAcpir21Migrations()
            + ", ACPIR 50 outstanding on " + outstandingAcpir50Migrations();
    }
}
