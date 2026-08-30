package com.crisil.eir.application.transition;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationTracker;
import com.crisil.eir.policy.transition.TransitionValuationRun;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One transition exercise's result: the figures, the evidence, and every reason the exercise must
 * not be reported as complete (04 § 6, 08 Phase 4).
 *
 * <h2>Nothing here is derived twice</h2>
 *
 * <p>{@link #invariants()} publishes TF-1, LC-1, DE-1, TM-1 and — where there is a subject for it —
 * BM-1, and every one of them is the answer of the policy evaluator that owns the question. Not one
 * is restated. The reason is stated three times in this codebase and once more in
 * {@code eir-policy.transition}'s own javadoc: a second answer published under an identifier
 * entitled to one leaves whichever a report happens to render decided by list order, and an
 * operator who sees an id twice learns to distrust the whole list.
 *
 * <h2>An evaluator is asked only where it has a subject</h2>
 *
 * <p>Every one of these evaluators returns a <em>pass</em> over an empty input, correctly and
 * unavoidably — "0 of 0 valuations apply the presumption, all on named evidence" is a true sentence.
 * Publishing it would be the aggregation-over-nothing defect in its purest form: an exercise that
 * valued no contract, planned no cohort and measured no position would present five green controls.
 * So each is asked only where a subject exists, and an obligation with no evidence is reported as a
 * named gap ({@link #unassertedInvariants()}) rather than as a pass — {@code OnboardingRun}'s rule,
 * and {@code InvariantResult.idsWithoutEvidence} holds the one statement of it.
 *
 * <p><b>BM-1 is not on {@link #TRANSITION_INVARIANTS}, and that is deliberate.</b> A bank with no
 * below-market originations in the period legitimately has nothing to assert it over, and an
 * obligation red on every such book is a control that gets argued down to a soft one within a
 * quarter — {@code RunAggregate}'s reasoning for leaving S3-1 off its own list, reached the same way.
 * It is published when and only when originations are presented, and {@link #belowMarketCount()} is
 * published always, so a reader can see whether it was asserted over anything at all.
 *
 * <h2>The population has to add up, and this record refuses to exist otherwise</h2>
 *
 * <p>{@code ContractResult}'s javadoc names the failure: "a run over 10,000,000 contracts that
 * silently processed 9,999,998 reconciles perfectly, because the two it dropped are absent from both
 * sides of every total." So every id the {@code ContractSource} named appears exactly once across
 * {@link #outcomes()} and {@link #isolated()}, nothing appears that the population did not name, and
 * the valuation run and the migration tracker are checked to have been built over that same
 * population. A mismatch is a defect in this module's own bookkeeping rather than a fact about a
 * contract, which is why it throws — {@code OnboardingRun} makes the identical choice — while facts
 * about the book are reported in {@link #blockingReasons()}.
 *
 * @param runId                   the exercise's identity, stamped on every exception raised
 * @param transitionDate          the ACPIR 19 valuation date
 * @param boundary                the as-at boundary; {@code businessAsOf} is the date TM-1 is
 *                                asserted on
 * @param population              every contract id the source named, in the order it named them
 * @param outcomes                one outcome per contract that was valued, in population order
 * @param isolated                one entry per contract the barrier stopped — the population named
 *                                it and something it needed was absent or malformed
 * @param valuationRun            the ACPIR 19 run over the valuations, which owns TF-1
 * @param tracker                 the migration tracker over the recorded positions and the full
 *                                population, which owns TM-1
 * @param plan                    the migration plan, which owns LC-1 and DE-1
 * @param belowMarketOriginations the day-1 below-market measurements presented, which BM-1 is
 *                                asserted over
 * @param coverage                the two obligations' coverage, tracked separately
 */
public record TransitionRun(
    String runId,
    LocalDate transitionDate,
    AsAtBoundary boundary,
    List<String> population,
    List<ContractTransitionOutcome> outcomes,
    List<ExceptionRecord> isolated,
    TransitionValuationRun valuationRun,
    MigrationTracker tracker,
    LegacyMigrationPlan plan,
    List<BelowMarketOrigination> belowMarketOriginations,
    TransitionCoverage coverage) {

    /**
     * The invariants a transition exercise is answerable for — <b>declared, not derived from what
     * the run happened to produce</b>.
     *
     * <p>Derivation is the trap {@code OnboardingRun} and {@code RunAggregate} both name: a report
     * listing the ids its inputs mentioned cannot distinguish "this held" from "nothing in this run
     * touched it", and the second reads as the first on every control report printed from it. Four
     * ids, and each has a subject any transition exercise necessarily has: contracts to value
     * (TF-1), cohorts to sequence (LC-1), cohorts on a deemed basis or not (DE-1), and positions to
     * track (TM-1).
     */
    public static final List<InvariantId> TRANSITION_INVARIANTS =
        List.of(InvariantId.TF_1, InvariantId.LC_1, InvariantId.DE_1, InvariantId.TM_1);

    /** How many contract ids a blocking reason names before it summarises the rest. */
    public static final int NAMED_LIMIT = 8;

    public TransitionRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transitionDate, "transitionDate");
        Objects.requireNonNull(boundary, "boundary");
        population = List.copyOf(Objects.requireNonNull(population, "population"));
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        isolated = List.copyOf(Objects.requireNonNull(isolated, "isolated"));
        Objects.requireNonNull(valuationRun, "valuationRun");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(plan, "plan");
        belowMarketOriginations = List.copyOf(
            Objects.requireNonNull(belowMarketOriginations, "belowMarketOriginations"));
        Objects.requireNonNull(coverage, "coverage");

        requirePartition(population, outcomes, isolated);
        if (!valuationRun.transitionDate().equals(transitionDate)) {
            throw new IllegalArgumentException(
                "the exercise values at " + transitionDate + " and its valuation run is dated "
                    + valuationRun.transitionDate() + "; one exercise is one transition date,"
                    + " because there is one opening balance to post the difference against");
        }
        if (valuationRun.valuedContractCount() != outcomes.size()) {
            throw new IllegalArgumentException(
                "the valuation run covers " + valuationRun.valuedContractCount()
                    + " contracts and the exercise produced " + outcomes.size()
                    + " outcomes; the total difference to opening retained earnings would then be a"
                    + " sum over a different population than the one this run accounts for");
        }
        if (tracker.trackedContracts() != outcomes.size()
            || tracker.trackedContracts() + tracker.untrackedContracts() != population.size()) {
            throw new IllegalArgumentException(
                "the migration tracker holds " + tracker.trackedContracts() + " tracked and "
                    + tracker.untrackedContracts() + " untracked positions against " + outcomes.size()
                    + " outcomes in a population of " + population.size()
                    + "; TM-1's untracked leg is the difference between the population and the"
                    + " recorded positions, so a tracker built over another population would report"
                    + " a coverage this exercise did not measure");
        }
    }

    /**
     * Every population id accounted for exactly once, and nothing accounted for that the population
     * did not name.
     *
     * <p>Both directions, because they catch different defects. A missing id is a contract the
     * exercise silently dropped. An extra id is a contract it reported on that was not in scope,
     * which on a re-run is worse: 05 § 3.3 requires the same population the earlier run saw, and a
     * contract onboarded since would appear in the new pack and not in the figures already
     * circulated.
     */
    private static void requirePartition(List<String> population,
        List<ContractTransitionOutcome> outcomes, List<ExceptionRecord> isolated) {

        Set<String> accountedFor = new LinkedHashSet<>();
        for (ContractTransitionOutcome outcome : outcomes) {
            if (!accountedFor.add(outcome.contractId())) {
                throw new IllegalArgumentException(
                    "contract " + outcome.contractId() + " has two outcomes in this exercise; one"
                        + " valuation would overwrite the other and the loss would be invisible in"
                        + " the total to opening retained earnings");
            }
        }
        for (ExceptionRecord record : isolated) {
            if (!accountedFor.add(record.contractId())) {
                throw new IllegalArgumentException(
                    "contract " + record.contractId() + " is both valued and isolated in this"
                        + " exercise; a contract produced a valuation or it did not");
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
                "the population holds " + inScope.size() + " contract(s) and the exercise accounts"
                    + " for " + accountedFor.size() + "; " + missing.size() + " unaccounted for "
                    + truncate(missing) + " and " + extra.size() + " reported but not in scope "
                    + truncate(extra) + ". FR-905: every contract in the population is either valued"
                    + " or isolated, and the count has to add up — an exercise short by one contract"
                    + " reconciles perfectly, because that contract is absent from both sides of"
                    + " every total");
        }
    }

    /** How many contracts the source named. */
    public int populationSize() {
        return population.size();
    }

    /** How many contracts were valued. */
    public int valuedCount() {
        return outcomes.size();
    }

    /** How many contracts the barrier stopped. */
    public int isolatedCount() {
        return isolated.size();
    }

    /** How many below-market originations BM-1 was, or would be, asserted over. */
    public int belowMarketCount() {
        return belowMarketOriginations.size();
    }

    /**
     * The total ACPIR 19 adjustment to opening retained earnings.
     *
     * <p>Delegated to {@code TransitionValuationRun}, which sums unrounded at working precision.
     * Named as the policy type names it, and there is no accessor on this path that would let the
     * figure be read as a period result — ACPIR 19 is an adjustment against the opening balance, and
     * a remeasurement landing in profit or loss is the reading that moves a reported number nobody
     * expected to move.
     */
    public Money totalDifferenceToOpeningRetainedEarnings() {
        return valuationRun.totalDifferenceToOpeningRetainedEarnings();
    }

    /**
     * The invariants this exercise established, each from the evaluator that owns it.
     *
     * <p>In {@link #TRANSITION_INVARIANTS} order, with BM-1 last where it has a subject. An
     * evaluator with no subject is not asked — see the class javadoc — so a caller must read
     * {@link #unassertedInvariants()} alongside this list rather than treating a short list as a
     * clean one.
     */
    public List<InvariantResult> invariants() {
        List<InvariantResult> evidence = new ArrayList<>(TRANSITION_INVARIANTS.size() + 1);
        if (valuationRun.valuedContractCount() > 0) {
            // TF-1, once over the population. TransitionValuationRun's javadoc: a run emitting one
            // TF-1 per contract would publish a deviation of 1 however many were unevidenced.
            evidence.add(valuationRun.paragraph19Evidenced());
        }
        if (!plan.cohorts().isEmpty()) {
            // LC-1 and DE-1, in the order a reader works them, from the plan's own accessor.
            evidence.addAll(plan.invariants());
        }
        if (populationSize() > 0) {
            // TM-1, asserted at the boundary's business date. NOT tightened: the tracker's own
            // formulation fires on an untracked contract and on an unmigrated one past 31 March
            // 2030, and deliberately not on a contract still under the concession before it. A
            // control red by design from 2027 to 2030 gets suppressed and is then not there for the
            // year it matters (InvariantId.TM_1, 03 § 9).
            evidence.add(tracker.migrationTracked(boundary.businessAsOf()));
        }
        if (!belowMarketOriginations.isEmpty()) {
            // BM-1. Guarded because destinationsApproved(List.of()) returns a pass reading "0
            // below-market originations, all with an approved day-1 destination" — a true sentence
            // and, published on a book that presented none, a green control over nothing.
            evidence.add(BelowMarketOrigination.destinationsApproved(belowMarketOriginations));
        }
        return List.copyOf(evidence);
    }

    /** The invariants this exercise is answerable for and produced no result under. */
    public List<InvariantId> unassertedInvariants() {
        return InvariantResult.idsWithoutEvidence(TRANSITION_INVARIANTS, invariants());
    }

    /** The published results that failed. */
    public List<InvariantResult> breaches() {
        return invariants().stream().filter(result -> !result.satisfied()).toList();
    }

    /** Contracts with a rate assignment that still needs work, in population order. */
    public List<LegacyRateAssignment> outstandingMigrationWork() {
        return outcomes.stream()
            .map(ContractTransitionOutcome::rateAssignment)
            .filter(LegacyRateAssignment::isOutstanding)
            .toList();
    }

    /**
     * Cohorts queued for full reconstruction that run off before the deadline.
     *
     * <p>Plain data, from the plan's own accessor, and not a blocking reason — spending effort badly
     * is not an accounting breach, and an id or a block on it would put a programme management
     * question beside a figure that does not tie. Published because it is the roadmap's own sentence:
     * "reconstructing an EIR for a loan maturing in 2029 is wasted effort."
     */
    public List<LegacyCohort> wastedReconstructionEffort() {
        return plan.wastedReconstructionEffort();
    }

    /**
     * Every reason this exercise must not be reported as complete.
     *
     * <p><b>Not "no breaches".</b> An exercise over an empty population has no breaches: every total
     * is nil, every coverage figure is complete over nothing, and each of the five evaluators would
     * pass if it were asked. So the question this answers is whether the exercise accounted for its
     * population, valued something, planned the migration, placed its contracts, and established
     * cleanly the obligations it is answerable for — and any of those failing is a listed reason.
     *
     * <p><b>What is deliberately not a reason.</b> Outstanding migration work before 31 March 2030,
     * a cohort still queued for reconstruction, contracts under the ACPIR 50 concession, and
     * reconstruction effort aimed at a cohort that runs off early. The first three are what an
     * unfinished migration looks like during the window ACPIR 21 and ACPIR 50 grant, and blocking on
     * them would make the pack red for three years — the reasoning TM-1 is formulated on. The fourth
     * is a programme question, not an accounting one.
     */
    public List<String> blockingReasons() {
        List<String> reasons = new ArrayList<>();
        if (population.isEmpty()) {
            // Stated first because it explains why everything below it is silent.
            reasons.add("the exercise accounted for no contracts at all; every total and every"
                + " coverage figure below is over an empty population, so nothing is red and"
                + " nothing was measured");
        } else if (outcomes.isEmpty()) {
            reasons.add("no contract in a population of " + populationSize() + " was valued; all "
                + isolatedCount() + " were isolated, which reconciles perfectly and means the"
                + " exercise failed entirely");
        }
        if (!isolated.isEmpty()) {
            // 04 § 3: unresolved exceptions block the close unless explicitly accepted. Also
            // reported by TM-1, whose untracked leg counts exactly these contracts — the same
            // duplication RunClose accepts, because the two lists are sourced from the two places
            // that actually know, and neither can be inferred from the other.
            reasons.add(isolated.size() + " contract(s) were isolated and their exceptions are"
                + " unresolved (04 § 3): " + truncate(isolatedIds()));
        }
        if (plan.cohorts().isEmpty()) {
            reasons.add("the migration plan defines no cohort, so LC-1 and DE-1 have no subject and"
                + " nothing states how the legacy book reaches the EIR by "
                + LegacyCohort.ACPIR_50_DEADLINE);
        }
        List<String> unplaced = outcomes.stream()
            .filter(outcome -> outcome.rateAssignment().basis() == LegacyRateBasis.UNASSIGNED)
            .map(ContractTransitionOutcome::contractId)
            .toList();
        if (!unplaced.isEmpty()) {
            reasons.add(unplaced.size() + " valued contract(s) are in no asserted cohort, so no"
                + " migration method applies to them and they are absent from every cohort's"
                + " contract count: " + truncate(unplaced) + ". " + AssertedCohortMembership.BASIS);
        }
        List<InvariantId> gaps = unassertedInvariants();
        if (!gaps.isEmpty()) {
            reasons.add("no evidence was produced for " + gaps + ", which this exercise is"
                + " answerable for; an id absent from the report reads the same as an id that"
                + " passed");
        }
        for (InvariantResult result : breaches()) {
            reasons.add(result.id() + " breached: " + result.detail());
        }
        return List.copyOf(reasons);
    }

    /** Whether this exercise may be reported as complete. */
    public boolean reportsCompleteExercise() {
        return blockingReasons().isEmpty();
    }

    /** The exercise's own summary: the counts, the coverage, and every reason it is not complete. */
    public String describe() {
        StringBuilder text = new StringBuilder("transition exercise ").append(runId)
            .append(" valuing at ").append(transitionDate)
            .append(", asserted as at ").append(boundary.businessAsOf())
            .append(boundary.isReplay() ? " (replay of " + boundary.replayOf() + ")" : "")
            .append(": ").append(populationSize()).append(" contract(s) in scope, ")
            .append(valuedCount()).append(" valued, ").append(isolatedCount()).append(" isolated")
            .append("\n  to opening retained earnings ")
            .append(totalDifferenceToOpeningRetainedEarnings().atPresentationScale())
            .append(", techniques ").append(valuationRun.techniqueMix())
            .append("\n  ").append(belowMarketCount())
            .append(" below-market origination(s) presented")
            .append(belowMarketCount() == 0 ? " — BM-1 not asserted, nothing to assert it over" : "")
            .append("\n  ").append(coverage.describe());
        if (reportsCompleteExercise()) {
            return text.append("\n  — complete").toString();
        }
        text.append("\n  — NOT complete:");
        for (String reason : blockingReasons()) {
            text.append("\n    ").append(reason);
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    private List<String> isolatedIds() {
        return isolated.stream().map(ExceptionRecord::contractId).toList();
    }

    /**
     * At most {@link #NAMED_LIMIT} names, then a count.
     *
     * <p>Bounded deterministically — population order, truncated — so the same exercise always
     * produces the same text (FR-903). A reason naming ten million contracts is one nobody reads;
     * a reason naming none is one nobody can act on.
     */
    private static String truncate(List<String> ids) {
        if (ids.size() <= NAMED_LIMIT) {
            return ids.toString();
        }
        return ids.subList(0, NAMED_LIMIT) + " and " + (ids.size() - NAMED_LIMIT) + " more";
    }
}
