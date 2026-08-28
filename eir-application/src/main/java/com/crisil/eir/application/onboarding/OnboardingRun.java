package com.crisil.eir.application.onboarding;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One initial-recognition run over a population: the three-way split, the population arithmetic, and
 * the aggregated invariants.
 *
 * <h2>The population arithmetic is enforced, not reported</h2>
 *
 * <p>FR-905 isolates per contract, and
 * {@link com.crisil.eir.application.ContractResult}'s javadoc names the reading that satisfies it
 * cheaply and wrongly: "a run over 10,000,000 contracts that silently processed 9,999,998
 * reconciles perfectly, because the two it dropped are absent from both sides of every total." So
 * this record refuses to exist unless every id the {@link com.crisil.eir.application.port.ContractSource}
 * named appears exactly once across {@link #outcomes()} and {@link #unreadable()}, and nothing
 * appears that the population did not name. That is a defect in this module's own bookkeeping rather
 * than a fact about a contract, which is why it throws instead of being reported.
 *
 * <h2>The empty-population trap</h2>
 *
 * <p>An aggregation over an empty population is the specific defect this codebase has shipped
 * repeatedly: a control whose loop iterates whatever its inputs happened to mention, so that with
 * empty inputs it compares nothing and returns a pass. The most recent instance was found in Phase 5
 * by review rather than by test.
 *
 * <p>Two structural decisions here answer it, and neither of them is a manufactured breach:
 *
 * <ol>
 *   <li><b>{@link #POPULATION_INVARIANTS} is a fixed list</b>, declared here, not derived from what
 *       the outcomes happened to publish. An id with no evidence behind it is therefore <em>visible
 *       as unasserted</em> ({@link #unassertedInvariants()}) rather than quietly absent from a
 *       report that then reads as complete.
 *   <li><b>An invariant with no evidence is not published at all</b>, and never published as a pass.
 *       An absent control is bad; a control that reads as coverage and cannot fail is worse, because
 *       nobody looks at it again.
 * </ol>
 *
 * <p>And {@link #blocksClose()} is true whenever {@link #assertedNothing()} is — a run that
 * established nothing does not report a clean close, whatever the reason it established nothing.
 * That is stated as a property of the run rather than as a false invariant breach, because ST-12's
 * statement is "SPPI failure yields no EIR" and publishing that as <em>failed</em> over a population
 * of zero would be a lie about the invariant in service of a true statement about the run.
 *
 * @param runId      the run these outcomes are stamped with (04 § 2.13)
 * @param boundary   the as-at boundary the population and the source were read at
 * @param population every contract id the source named, in the order it named them
 * @param outcomes   one outcome per contract that reached the measurement gate, in population order
 * @param unreadable one entry per contract that did not reach the gate at all — the population named
 *                   it and the onboarding source held no record, or the record threw before the gate
 */
public record OnboardingRun(
    String runId,
    AsAtBoundary boundary,
    List<String> population,
    List<OnboardingOutcome> outcomes,
    List<ExceptionRecord> unreadable) {

    /**
     * The invariants a run of this use case is answerable for — <b>fixed, and not derived from the
     * outcomes</b>.
     *
     * <p>Derivation is the trap. A report that lists the ids its inputs mentioned cannot distinguish
     * "this invariant held" from "nothing in this run touched this invariant", and the second reads
     * as the first on every control report ever printed from it. Declared here so that an id with no
     * evidence is a named gap.
     *
     * <p>Two ids, and both are 05 § 3.1's own: ST-12 is the gate ({@code CALC} never sees an
     * instrument that failed it), IC-1 is the diagram's {@code CALC->>CALC: assert IC-1}.
     */
    public static final List<InvariantId> POPULATION_INVARIANTS =
        List.of(InvariantId.ST_12, InvariantId.IC_1);

    /**
     * How many breaching contracts an aggregate names before summarising the rest.
     *
     * <p>Bounded for the reason {@code FeeClassificationResolver.REFUSAL_RULE_LIMIT} is: a control
     * report a person cannot read is the same as no control report. Bounded deterministically —
     * population order, truncated — so the same run always produces the same text (FR-903).
     */
    public static final int NAMED_BREACH_LIMIT = 8;

    public OnboardingRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(boundary, "boundary");
        population = List.copyOf(Objects.requireNonNull(population, "population"));
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        unreadable = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        if (runId.isBlank()) {
            throw new IllegalArgumentException(
                "an onboarding run needs an id; 04 § 2.13 stamps every exception and every"
                    + " computation with the run that produced it");
        }
        requirePartition(population, outcomes, unreadable);
    }

    /**
     * Every population id accounted for exactly once, and nothing accounted for that the population
     * did not name.
     *
     * <p>Both directions matter and they catch different defects. A missing id is a contract the run
     * silently dropped. An extra id is a contract the run reported on that was not in scope — which
     * on a replay is worse, because 05 § 3.3 requires the replay to see the population the original
     * run saw, and "a contract onboarded since would appear in the replay and not in the published
     * figures" ({@code ContractSource}).
     */
    private static void requirePartition(List<String> population,
        List<OnboardingOutcome> outcomes, List<ExceptionRecord> unreadable) {

        Set<String> accountedFor = new LinkedHashSet<>();
        for (OnboardingOutcome outcome : outcomes) {
            if (!accountedFor.add(outcome.contractId())) {
                throw new IllegalArgumentException(
                    "contract " + outcome.contractId() + " has two outcomes in this run; one would"
                        + " overwrite the other's figures and the loss would be invisible");
            }
        }
        for (ExceptionRecord record : unreadable) {
            if (!accountedFor.add(record.contractId())) {
                throw new IllegalArgumentException(
                    "contract " + record.contractId() + " is both outcome-bearing and unreadable in"
                        + " this run; a contract is gated or it is not");
            }
        }
        Set<String> inScope = new LinkedHashSet<>(population);
        List<String> missing = new ArrayList<>();
        for (String id : inScope) {
            if (!accountedFor.contains(id)) {
                missing.add(id);
            }
        }
        List<String> extra = new ArrayList<>();
        for (String id : accountedFor) {
            if (!inScope.contains(id)) {
                extra.add(id);
            }
        }
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalArgumentException(
                "the population holds " + inScope.size() + " contract(s) and the run accounts for "
                    + accountedFor.size() + "; " + missing.size() + " unaccounted for "
                    + truncate(missing) + " and " + extra.size() + " reported but not in scope "
                    + truncate(extra) + ". FR-905: every contract in the population is either"
                    + " computed, excluded or quarantined, and the count has to add up — a run"
                    + " short by one contract reconciles perfectly, because the contract is absent"
                    + " from both sides of every total");
        }
    }

    /** How many contracts the source named. */
    public int populationSize() {
        return population.size();
    }

    /** How many contracts came out with a rate. */
    public int recognisedCount() {
        return countOf(OnboardingDisposition.RECOGNISED);
    }

    /** How many contracts were recorded and excluded from EIR processing (FR-103). */
    public int excludedCount() {
        return countOf(OnboardingDisposition.EXCLUDED_FROM_EIR);
    }

    /**
     * How many contracts carry an exception-queue entry — including the ones that never reached the
     * gate.
     */
    public int quarantinedCount() {
        return countOf(OnboardingDisposition.QUARANTINED) + unreadable.size();
    }

    private int countOf(OnboardingDisposition disposition) {
        int count = 0;
        for (OnboardingOutcome outcome : outcomes) {
            if (outcome.disposition() == disposition) {
                count++;
            }
        }
        return count;
    }

    /**
     * The three counts against the population, as one sentence.
     *
     * <p>Published as data because it is what an operator reads first, and because
     * {@link #requirePartition} has already made the arithmetic true — so this states a fact rather
     * than asserting one.
     */
    public String describePopulation() {
        return populationSize() + " contract(s) in scope as at " + boundary.businessAsOf()
            + (boundary.isReplay() ? " (replay of " + boundary.replayOf() + ")" : "")
            + ": " + recognisedCount() + " recognised, " + excludedCount()
            + " recorded and excluded from EIR processing, " + quarantinedCount() + " quarantined"
            + (unreadable.isEmpty() ? "" : " (" + unreadable.size() + " never reached the gate)");
    }

    /**
     * One result per invariant this run has evidence for, in {@link #POPULATION_INVARIANTS} order.
     *
     * <p><b>Deviations are counts and absolute totals, never signed sums.</b>
     * {@link InvariantResult#conjunction} keeps only the first breach's deviation among results
     * sharing an id, so it is not used here: the aggregate is built directly, with ST-12's deviation
     * the total count of per-contract breaches and IC-1's the total <em>absolute</em> residual. A
     * signed sum would let two breaks in opposite directions net to a pass, which is the shape of
     * failure a population-level control exists to catch.
     */
    public List<InvariantResult> invariants() {
        Map<InvariantId, List<InvariantResult>> evidence = new EnumMap<>(InvariantId.class);
        Map<InvariantId, List<String>> breachingContracts = new EnumMap<>(InvariantId.class);
        for (InvariantId id : POPULATION_INVARIANTS) {
            evidence.put(id, new ArrayList<>());
            breachingContracts.put(id, new ArrayList<>());
        }
        for (OnboardingOutcome outcome : outcomes) {
            for (InvariantResult result : outcome.invariants()) {
                List<InvariantResult> forId = evidence.get(result.id());
                if (forId == null) {
                    // An id this run is not answerable for. Kept out rather than appended, because
                    // POPULATION_INVARIANTS is the statement of what a run of this use case answers
                    // for, and a report that grew whatever the outcomes mentioned would be the
                    // derived-id-list defect this class exists not to repeat.
                    continue;
                }
                forId.add(result);
                if (!result.satisfied()) {
                    breachingContracts.get(result.id()).add(outcome.contractId());
                }
            }
        }
        List<InvariantResult> aggregated = new ArrayList<>(POPULATION_INVARIANTS.size());
        for (InvariantId id : POPULATION_INVARIANTS) {
            List<InvariantResult> forId = evidence.get(id);
            if (forId.isEmpty()) {
                continue;
            }
            aggregated.add(aggregate(id, forId, breachingContracts.get(id)));
        }
        return List.copyOf(aggregated);
    }

    /**
     * One invariant's population result.
     *
     * <p><b>What input makes ST-12 fail here:</b> any contract in the population whose per-contract
     * ST-12 breached — an asset assessed {@code FAIL} that the contract master declares at amortised
     * cost, or a contract with no EIR for which a projection or a solve was nonetheless performed.
     * The deviation is the total number of such breaches across the population.
     *
     * <p><b>What input makes IC-1 fail here:</b> any projected contract whose initial carrying
     * amount differs from the net cash flow at inception — a misclassified fee, or a non-cash item
     * in the vector. The deviation is the total absolute residual in currency, so a contract 5,000
     * high and one 5,000 low report 10,000 rather than nil.
     */
    private static InvariantResult aggregate(
        InvariantId id, List<InvariantResult> forId, List<String> breaching) {

        BigDecimal deviation = BigDecimal.ZERO;
        for (InvariantResult result : forId) {
            if (!result.satisfied()) {
                deviation = deviation.add(result.deviation().abs());
            }
        }
        String scope = id + " (" + id.statement() + ") asserted over " + forId.size()
            + " contract(s) with evidence; ";
        if (breaching.isEmpty()) {
            return InvariantResult.pass(id, scope + "no breaches");
        }
        return InvariantResult.fail(id,
            scope + breaching.size() + " breached "
                + truncate(breaching) + ". Total "
                + (id == InvariantId.IC_1 ? "absolute residual " : "breach count ")
                + deviation.toPlainString(),
            deviation);
    }

    /**
     * The invariants this run is answerable for and has no evidence for.
     *
     * <p>The honest half of not publishing an unevidenced pass. IC-1 appears here on a population of
     * FVTPL instruments — correctly, because nothing was projected and IC-1 is a claim about a
     * projection — and ST-12 appears here on an empty population, or one where every contract failed
     * before the gate could examine an SPPI outcome.
     */
    public List<InvariantId> unassertedInvariants() {
        Set<InvariantId> asserted = new LinkedHashSet<>();
        for (InvariantResult result : invariants()) {
            asserted.add(result.id());
        }
        List<InvariantId> gaps = new ArrayList<>();
        for (InvariantId id : POPULATION_INVARIANTS) {
            if (!asserted.contains(id)) {
                gaps.add(id);
            }
        }
        return List.copyOf(gaps);
    }

    /**
     * Whether this run established none of the invariants it is answerable for.
     *
     * <p>True on an empty population, which is the case the corollary of the aggregation trap is
     * about: a run that processed no contracts must not report a clean close. Also true where every
     * contract in a non-empty population failed before any invariant had a subject.
     */
    public boolean assertedNothing() {
        return invariants().isEmpty();
    }

    /** The breaches, for a close gate that wants them rather than the whole set. */
    public List<InvariantResult> breaches() {
        return invariants().stream().filter(result -> !result.satisfied()).toList();
    }

    /**
     * Whether this run leaves the accounting close blocked.
     *
     * <p>Three independent reasons, and the third is the one worth having:
     *
     * <ul>
     *   <li>a published invariant breached — 03 § 9, an invariant breach blocks the close;
     *   <li>a contract is quarantined — 04 § 3, "unresolved exceptions block the close unless
     *       explicitly accepted with approval", and every category
     *       {@link com.crisil.eir.policy.exception.ExceptionCategory#blocksClose()};
     *   <li>the run established nothing. A run over an empty population has no breaches and no
     *       exceptions, and reporting it clean is the aggregation-over-nothing defect. It is
     *       reported here, as a property of the run, rather than as a fabricated invariant breach.
     * </ul>
     */
    public boolean blocksClose() {
        return !breaches().isEmpty() || quarantinedCount() > 0 || assertedNothing();
    }

    /** One line an operator can act on: the counts, the gaps, and whether the close can proceed. */
    public String describeClose() {
        StringBuilder line = new StringBuilder("run ").append(runId).append(": ")
            .append(describePopulation());
        List<InvariantId> gaps = unassertedInvariants();
        if (!gaps.isEmpty()) {
            line.append("; no evidence for ").append(gaps);
        }
        List<InvariantResult> breaches = breaches();
        if (!breaches.isEmpty()) {
            line.append("; ").append(breaches.size()).append(" invariant breach(es)");
        }
        line.append(blocksClose() ? "; CLOSE BLOCKED" : "; close may proceed");
        if (assertedNothing()) {
            line.append(" — the run established none of ").append(POPULATION_INVARIANTS)
                .append(", so there is nothing to report clean");
        }
        return line.toString();
    }

    @Override
    public String toString() {
        return describeClose();
    }

    /** A bounded, deterministic rendering of a list of contract ids. */
    private static String truncate(List<String> ids) {
        if (ids.size() <= NAMED_BREACH_LIMIT) {
            return ids.toString();
        }
        return ids.subList(0, NAMED_BREACH_LIMIT) + " and " + (ids.size() - NAMED_BREACH_LIMIT)
            + " more";
    }
}
