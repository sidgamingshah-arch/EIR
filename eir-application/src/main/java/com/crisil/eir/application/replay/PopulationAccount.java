package com.crisil.eir.application.replay;

import com.crisil.eir.application.ContractResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * FR-905's arithmetic: every contract in the population is either computed or quarantined, and the
 * count adds up.
 *
 * <p><b>Why this is checked at all, when the barrier is upstream.</b> Because the cheap reading of
 * FR-905 — "one malformed contract must not fail a ten-million-contract run" — is to drop the
 * contract, and {@link ContractResult}'s javadoc names the consequence exactly: "a run over
 * 10,000,000 contracts that silently processed 9,999,998 reconciles perfectly, because the two it
 * dropped are absent from both sides of every total". A replay is the one place that has both the
 * population and the results in hand, so it is the one place the subtraction can be done.
 *
 * <p><b>And why a replay in particular cannot skip it.</b> A dropped contract shows up in DT-1 as
 * a {@code MISSING_FROM_REPLAY} finding — a determinism breach, investigated as a determinism
 * breach, when the actual defect is a run that lost a row. Naming the condition for what it is
 * turns a night of tracing arithmetic into one sentence.
 *
 * <p>This record can represent an account that does <em>not</em> add up, and must be able to:
 * {@link ReplayUseCase} refuses a run whose output fails to account for its own population, and a
 * type that could not express the condition would leave the refusal with nothing to say. So
 * {@link #addsUp()} is a real question here even though a constructed {@link ReplayVerification}
 * never holds a false one.
 *
 * @param population   every contract id the run was responsible for, in the order the source
 *                     enumerated them ({@code ContractSource.contractIdsInScope})
 * @param computed     ids that produced figures
 * @param quarantined  ids the barrier isolated
 * @param unaccounted  ids in the population with neither a figure nor a quarantine record — the
 *                     dropped contracts
 * @param extraneous   ids the run returned results for that were never in its population — the
 *                     other direction of the same defect, and the one a total cannot see either
 */
public record PopulationAccount(
    List<String> population,
    List<String> computed,
    List<String> quarantined,
    List<String> unaccounted,
    List<String> extraneous) {

    public PopulationAccount {
        population = List.copyOf(Objects.requireNonNull(population, "population"));
        computed = List.copyOf(Objects.requireNonNull(computed, "computed"));
        quarantined = List.copyOf(Objects.requireNonNull(quarantined, "quarantined"));
        unaccounted = List.copyOf(Objects.requireNonNull(unaccounted, "unaccounted"));
        extraneous = List.copyOf(Objects.requireNonNull(extraneous, "extraneous"));
    }

    /**
     * Reconciles a run's results against the population it was given.
     *
     * @param population the ids {@code ContractSource} named for the run's boundary
     * @param results    one result per contract, as the run returned them
     */
    public static PopulationAccount of(List<String> population, List<ContractResult> results) {
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(results, "results");

        // A LinkedHashSet, so a population that names one contract twice does not make the
        // subtraction come out right by accident: the duplicate collapses here and the second
        // result for it lands in `extraneous`, which is the honest reading — a run cannot be
        // responsible for one contract twice, and FailureIsolation.runBatch refuses such a
        // population outright.
        Set<String> owed = new LinkedHashSet<>(population);
        List<String> computed = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        List<String> extraneous = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ContractResult result : results) {
            Objects.requireNonNull(result, "contract result");
            String id = result.contractId();
            if (!owed.contains(id) || !seen.add(id)) {
                extraneous.add(id);
                continue;
            }
            if (result.isComputed()) {
                computed.add(id);
            } else {
                quarantined.add(id);
            }
        }
        List<String> unaccounted = new ArrayList<>();
        for (String id : owed) {
            if (!seen.contains(id)) {
                unaccounted.add(id);
            }
        }
        return new PopulationAccount(
            List.copyOf(owed), computed, quarantined, unaccounted, extraneous);
    }

    /** Whether every contract in the population is accounted for, and nothing else appeared. */
    public boolean addsUp() {
        return unaccounted.isEmpty() && extraneous.isEmpty();
    }

    /** How many contracts the run was responsible for. */
    public int populationSize() {
        return population.size();
    }

    /** Whether the run had nothing to do — the empty period, which is a fact and not a defect. */
    public boolean isEmpty() {
        return population.isEmpty();
    }

    /** A one-line audit sentence: the subtraction, written out. */
    public String describe() {
        StringBuilder sentence = new StringBuilder()
            .append(population.size()).append(" contract(s) in scope = ")
            .append(computed.size()).append(" computed + ")
            .append(quarantined.size()).append(" quarantined");
        if (addsUp()) {
            return sentence.toString();
        }
        if (!unaccounted.isEmpty()) {
            sentence.append("; ").append(unaccounted.size())
                .append(" unaccounted for (").append(named(unaccounted)).append(')');
        }
        if (!extraneous.isEmpty()) {
            sentence.append("; ").append(extraneous.size())
                .append(" returned but not in scope (").append(named(extraneous)).append(')');
        }
        return sentence.toString();
    }

    /** At most five ids by name, so a message about ten million contracts stays readable. */
    private static String named(List<String> ids) {
        if (ids.size() <= 5) {
            return String.join(", ", ids);
        }
        return String.join(", ", ids.subList(0, 5)) + ", and " + (ids.size() - 5) + " more";
    }
}
