package com.crisil.eir.application.run;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a population of contracts produced, aggregated deliberately (05 § 3.2, FR-905).
 *
 * <h2>The trap this type exists to avoid</h2>
 *
 * <p><b>An aggregation over an empty population reports a clean close.</b> Collapse zero contracts
 * into one result per invariant and you get an empty list; ask whether every result is satisfied and
 * the answer over an empty list is yes. Every control ties, nothing is red, and the run processed
 * nothing. That is the most recent shape of this codebase's signature defect — a control whose
 * policy leg iterated only what its inputs happened to mention, and so returned a pass on empty
 * inputs — and it is why {@link #reportsCleanClose()} is not "no breaches" but a list of
 * {@link #blockingReasons()} that an empty population is on.
 *
 * <p><b>And a run short of its own population reconciles perfectly.</b> FR-905 requires per-contract
 * isolation, and the cheap reading of it is to drop the malformed contract: a run over 10,000,000
 * that silently processed 9,999,998 ties on every total, because the two it dropped are absent from
 * both sides of all of them. So the population is an input here, every contract in it is either
 * computed or quarantined ({@link ContractResult}'s own javadoc makes the quarantined one a result
 * and not an absence), and the count is checked and reported rather than assumed.
 *
 * <p><b>The check is reported, not refused.</b> A factory that threw on a short results list could
 * not represent a short run, and a type that cannot represent the failure cannot report it — the
 * same layering argument {@code JournalEntry} makes for letting an unbalanced entry be constructed
 * so that SL-2 has something to detect. {@link MonthEndRun} is what guarantees the list is complete;
 * this is what says whether it was.
 *
 * <h2>How the invariants are aggregated</h2>
 *
 * <p>One result per id, and the deviation is the <b>total absolute residual</b> across breaching
 * contracts, with the breach count in the detail. Three deliberate choices:
 *
 * <ul>
 *   <li><b>Not {@link InvariantResult#conjunction}.</b> It keeps only the <em>first</em> breach's
 *       deviation among results sharing an id, which is right for one contract's several claims and
 *       wrong for ten million contracts' one claim: the close needs the size of the problem, not
 *       the size of the first instance of it.</li>
 *   <li><b>Absolute, never signed.</b> Two contracts out by +1,000 and −1,000 must not net to a
 *       reconciled population. A signed sum is the classic way a reconciliation control passes
 *       while being broken twice.</li>
 *   <li><b>Ids that no contract asserted do not appear.</b> Publishing a pass for an invariant
 *       nothing evaluated is the empty-population trap at the level of a single id.</li>
 * </ul>
 *
 * <p><b>The run-level invariants are not here.</b> 05 § 3.2 puts SL-1, PF-1, HB-1 and TG-1 after the
 * loop, against the GL and the CBS rather than against the contracts, and
 * {@code eir-policy.close.PeriodCloseGate} already owns them. This type aggregates what the
 * per-contract loop asserted and stops there, because a second, weaker gate beside the one that owns
 * the question is worse than no second gate.
 *
 * @param runId            the run
 * @param periodId         the accounting period, {@code YYYYMM}
 * @param populationSize   how many contracts the run was asked to account for
 * @param results          one result per contract, in population order; computed or quarantined
 * @param invariants       one result per invariant id the population asserted
 * @param policyVersionIds the policy versions the run resolved, for 04 § 2.13's run record
 * @param blockingReasons  why this run must not be reported as a clean close; empty where it may
 */
public record RunAggregate(
    String runId,
    int periodId,
    int populationSize,
    List<ContractResult> results,
    List<InvariantResult> invariants,
    List<String> policyVersionIds,
    List<String> blockingReasons) {

    public RunAggregate {
        Objects.requireNonNull(runId, "runId");
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        invariants = List.copyOf(Objects.requireNonNull(invariants, "invariants"));
        policyVersionIds = List.copyOf(Objects.requireNonNull(policyVersionIds, "policyVersionIds"));
        blockingReasons = List.copyOf(Objects.requireNonNull(blockingReasons, "blockingReasons"));
        if (populationSize < 0) {
            throw new IllegalArgumentException(
                "populationSize must be non-negative, got " + populationSize);
        }
    }

    /**
     * Aggregates a population's results.
     *
     * @param population       every contract id the run had to account for, in run order
     * @param results          what came back, in any order
     * @param policyVersionIds the policy versions consulted, for the run record
     */
    public static RunAggregate of(
        String runId,
        int periodId,
        List<String> population,
        List<ContractResult> results,
        List<String> policyVersionIds) {
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(results, "results");

        Map<String, ContractResult> byContract = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (ContractResult result : results) {
            if (byContract.putIfAbsent(result.contractId(), result) != null) {
                // Two results for one contract means one contract's figures overwrote another
                // reading of the same contract, and whichever survived did so by iteration order.
                reasons.add("contract " + result.contractId() + " has more than one result");
            }
        }
        Set<String> inPopulation = new LinkedHashSet<>(population);

        List<ContractResult> ordered = new ArrayList<>(inPopulation.size());
        List<String> missing = new ArrayList<>();
        for (String contractId : inPopulation) {
            ContractResult result = byContract.get(contractId);
            if (result == null) {
                missing.add(contractId);
            } else {
                ordered.add(result);
            }
        }
        List<String> strangers = new ArrayList<>();
        for (String contractId : byContract.keySet()) {
            if (!inPopulation.contains(contractId)) {
                strangers.add(contractId);
                ordered.add(byContract.get(contractId));
            }
        }

        int quarantined = 0;
        int computed = 0;
        for (ContractResult result : ordered) {
            if (result.isComputed()) {
                computed++;
            } else {
                quarantined++;
            }
        }

        List<InvariantResult> aggregated = aggregate(ordered);

        // ---- Blocking reasons ------------------------------------------------------------------
        if (inPopulation.isEmpty()) {
            // The trap named in the class javadoc. Stated first because it explains why everything
            // below it is silent.
            reasons.add(
                "the run accounted for no contracts at all; every aggregation below is over an"
                    + " empty population, so nothing is red and nothing was measured");
        } else if (computed == 0) {
            reasons.add("no contract in a population of " + inPopulation.size()
                + " produced figures; " + quarantined + " were quarantined");
        }
        if (!missing.isEmpty()) {
            reasons.add(missing.size() + " contract(s) in the population have neither figures nor an"
                + " exception record and are absent from both sides of every total: "
                + name(missing));
        }
        if (!strangers.isEmpty()) {
            reasons.add(strangers.size() + " result(s) name contracts the population does not: "
                + name(strangers));
        }
        if (quarantined > 0) {
            // Not a defect in the run — FR-905 working — but 04 § 3 blocks the close on unresolved
            // exceptions, and a close that proceeds while contracts are quarantined is exactly the
            // "processed 9,999,998" outcome wearing a clean report.
            reasons.add(quarantined + " contract(s) were quarantined and their exceptions are"
                + " unresolved (04 § 3): " + name(quarantinedIds(ordered)));
        }
        if (computed > 0 && aggregated.isEmpty()) {
            // Defensive, and the reason it is here rather than assumed away: an invariant set that
            // silently became empty is the same failure as an empty population one level down —
            // contracts computed, nothing asserted, everything green.
            reasons.add(computed + " contract(s) computed and asserted no invariant at all");
        }
        for (InvariantResult result : aggregated) {
            if (!result.satisfied()) {
                reasons.add(result.id() + " breached: " + result.detail());
            }
        }

        return new RunAggregate(
            runId, periodId, inPopulation.size(), ordered, aggregated,
            policyVersionIds == null ? List.of() : policyVersionIds, reasons);
    }

    /**
     * One result per id, deviation the total absolute residual.
     *
     * <p>Order of first appearance, so the reading order of the report follows the order the loop
     * computed things in — which is what makes a breach traceable.
     */
    private static List<InvariantResult> aggregate(List<ContractResult> results) {
        Map<InvariantId, Population> byId = new LinkedHashMap<>();
        for (ContractResult result : results) {
            for (InvariantResult assertion : result.invariants()) {
                byId.computeIfAbsent(assertion.id(), id -> new Population())
                    .add(result.contractId(), assertion);
            }
        }
        List<InvariantResult> aggregated = new ArrayList<>(byId.size());
        for (Map.Entry<InvariantId, Population> entry : byId.entrySet()) {
            aggregated.add(entry.getValue().collapse(entry.getKey()));
        }
        return List.copyOf(aggregated);
    }

    /** How many contracts produced figures. */
    public int computedCount() {
        int computed = 0;
        for (ContractResult result : results) {
            if (result.isComputed()) {
                computed++;
            }
        }
        return computed;
    }

    /** How many contracts the barrier quarantined. */
    public int quarantinedCount() {
        return results.size() - computedCount();
    }

    /**
     * Population less results — the count that has to be nil.
     *
     * <p>Negative where results name contracts the population does not, which is a different defect
     * and named separately in {@link #blockingReasons()}.
     */
    public int unaccountedFor() {
        return populationSize - results.size();
    }

    /** The quarantined contracts, in population order. */
    public List<String> quarantinedContracts() {
        return quarantinedIds(results);
    }

    /** The period's journals, one per computed contract, in population order. */
    public List<JournalEntry> journals() {
        List<JournalEntry> entries = new ArrayList<>(results.size());
        for (ContractResult result : results) {
            if (result.isComputed()) {
                entries.add(result.journal());
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Sum of the computed contracts' closing gross carrying amounts — the sub-ledger side of SL-1.
     *
     * <p>Empty where no contract computed, rather than nil in some assumed currency. A total of zero
     * and a total of nothing are different facts, and only one of them ties to a GL control account.
     */
    public Optional<Money> totalClosingGca() {
        Money total = null;
        for (ContractResult result : results) {
            if (result.isComputed()) {
                total = total == null ? result.closingGca() : total.plus(result.closingGca());
            }
        }
        return Optional.ofNullable(total);
    }

    /** The aggregated invariants that failed. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /**
     * Whether this run may be reported as a clean close.
     *
     * <p><b>Not "no breaches".</b> A run that processed no contracts has no breaches. The question
     * this answers is whether the run accounted for its population, produced figures, asserted
     * something, and asserted it cleanly — and any one of those failing is a reason, listed.
     */
    public boolean reportsCleanClose() {
        return blockingReasons.isEmpty();
    }

    /** The run record's own summary. */
    public String describe() {
        StringBuilder text = new StringBuilder("run ").append(runId)
            .append(" period ").append(periodId)
            .append(": population ").append(populationSize)
            .append(", computed ").append(computedCount())
            .append(", quarantined ").append(quarantinedCount())
            .append(", unaccounted ").append(unaccountedFor())
            .append(", ").append(invariants.size()).append(" invariant(s) asserted");
        if (reportsCleanClose()) {
            return text.append(" — clean").toString();
        }
        text.append(" — NOT a clean close:");
        for (String reason : blockingReasons) {
            text.append("\n  ").append(reason);
        }
        return text.toString();
    }

    private static List<String> quarantinedIds(List<ContractResult> results) {
        List<String> ids = new ArrayList<>();
        for (ContractResult result : results) {
            if (!result.isComputed()) {
                ids.add(result.contractId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * At most five names, then a count.
     *
     * <p>A reason naming ten million contracts is a reason nobody reads; a reason naming none is a
     * reason nobody can act on. Five and a count is the compromise, and the exception queue holds
     * the full list.
     */
    private static String name(List<String> ids) {
        if (ids.size() <= 5) {
            return String.join(", ", ids);
        }
        return String.join(", ", ids.subList(0, 5)) + " and " + (ids.size() - 5) + " more";
    }

    /** One invariant id's results across the population, accumulated. */
    private static final class Population {

        private int asserted;
        private final List<String> breaching = new ArrayList<>();
        private BigDecimal totalAbsoluteDeviation = BigDecimal.ZERO;
        private final List<String> firstDetails = new ArrayList<>();

        private void add(String contractId, InvariantResult result) {
            asserted++;
            if (!result.satisfied()) {
                breaching.add(contractId);
                // ABSOLUTE. Two breaks in opposite directions must not net to a pass.
                totalAbsoluteDeviation =
                    totalAbsoluteDeviation.add(result.deviation().abs());
                if (firstDetails.size() < 3) {
                    firstDetails.add(contractId + ": " + result.detail());
                }
            }
        }

        private InvariantResult collapse(InvariantId id) {
            if (breaching.isEmpty()) {
                return InvariantResult.pass(id,
                    asserted + " contract(s) asserted " + id + " and none breached");
            }
            return InvariantResult.fail(id,
                breaching.size() + " of " + asserted + " contract(s) breached " + id
                    + "; total absolute residual " + totalAbsoluteDeviation.toPlainString()
                    + "; contracts " + name(breaching)
                    + "; first: " + String.join(" | ", firstDetails),
                totalAbsoluteDeviation);
        }
    }
}
