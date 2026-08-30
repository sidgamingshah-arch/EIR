package com.crisil.eir.application.transition;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.FailureIsolation;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.ContractMigrationState;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationTracker;
import com.crisil.eir.policy.transition.TransitionFairValue;
import com.crisil.eir.policy.transition.TransitionValuationRun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The ACPIR 19–21 and 50 transition exercise: 04 § 6 and 08 Phase 4, in the order they state it.
 *
 * <pre>
 * APP-&gt;&gt;PORT: population in scope as at the boundary
 * loop each contract, behind FR-905's barrier
 *     APP-&gt;&gt;PORT: opening state          &lt;-- the book's side: carrying amount, rate in force
 *     APP-&gt;&gt;PORT: fair value measurement  &lt;-- the valuer's side: fair value, technique, evidence
 *     APP-&gt;&gt;POL:  TransitionFairValue     (ACPIR 19 day-1 valuation)
 *     APP-&gt;&gt;PORT: ECL discount position
 *     APP-&gt;&gt;POL:  ContractMigrationState  (ACPIR 21 and ACPIR 50, two positions)
 *     APP-&gt;&gt;POL:  LegacyRateAssignment    (deem or reconstruct, per the plan's method)
 * end
 * APP-&gt;&gt;POL: TransitionValuationRun      -&gt; TF-1
 * APP-&gt;&gt;POL: MigrationTracker            -&gt; TM-1
 * APP-&gt;&gt;POL: LegacyMigrationPlan         -&gt; LC-1, DE-1
 * APP-&gt;&gt;POL: BelowMarketOrigination      -&gt; BM-1, where any were presented
 * </pre>
 *
 * <h2>What this class decides: nothing the engine decides</h2>
 *
 * <p>It solves no rate, derives no invariant and chooses no destination. Every figure it publishes
 * comes from a policy evaluator or a port, and the class's whole content is the <em>sequence</em>,
 * the per-contract isolation, and the accounting of who was in the population. That is the shape
 * {@code InitialRecognition} and {@code RunClose} establish, and the reason is the same: a use case
 * that recomputed a policy answer would be a second answer to a question that admits one, and which
 * of the two a report showed would be decided by list order.
 *
 * <h2>FR-905, delegated</h2>
 *
 * <p>Each contract runs behind {@link FailureIsolation#isolate}, not behind a hand-rolled try/catch
 * and not behind {@link FailureIsolation#runBatch}. The barrier because it is the one place that
 * knows an {@code Error} must propagate and a run-level invariant breach must not be filed against a
 * contract; {@code isolate} rather than {@code runBatch} for {@code InitialRecognition}'s reason —
 * the accounting here needs <em>both</em> sides as values, because {@link TransitionRun} refuses to
 * be assembled unless the valued and the isolated partition the population exactly.
 *
 * <h2>Two sources for two sides of one figure</h2>
 *
 * <p>The ACPIR 19 difference is fair value less the carrying amount, and it goes to opening retained
 * earnings. The carrying amount comes from {@code ContractStateSource} — the book — and the fair
 * value from {@code TransitionSource} — the valuation. Reading both from one place would let the
 * difference be set to any figure, nil included, by moving the side the ledger already carries, and
 * nil is precisely the answer that is indistinguishable from having measured nothing (which is what
 * TF-1 exists to catch on the other technique).
 *
 * <p>The same split applies to the two migration obligations. ACPIR 21 is read off whether a rate is
 * in force ({@code OpeningState.hasBeenSolved()}); ACPIR 50 off the recorded ECL discount basis. If
 * one record carried both, the party recording the discount basis would also be asserting the
 * recognition position, and 04 § 6's two obligations would have a single source — the merged flag it
 * gives the discount basis its own table to prevent.
 *
 * <h2>No clock</h2>
 *
 * <p>The transition date, the boundary, and therefore the date TM-1 is asserted on are all arguments.
 * Nothing calls {@code now()}. A three-year migration programme re-runs this exercise often, and a
 * pack that cannot be reproduced to yesterday's boundary is a pack nobody can audit (03 § 1.1,
 * ADR-0003, DT-1).
 */
public final class TransitionExercise {

    private TransitionExercise() {
    }

    /**
     * Performs the exercise over the population the request's {@code ContractSource} names.
     *
     * <p>Total over the population: every id is valued or isolated, and there is no path that skips
     * one. A contract whose measurement is missing, whose ECL basis is unrecorded, or whose recorded
     * basis is internally inconsistent throws inside the barrier and is filed under
     * {@link ExceptionCategory#MISSING_MANDATORY_FIELD} — the category's own definition, "a field the
     * computation cannot proceed without".
     *
     * @param request                 the exercise, carrying its identity, its date and its ports
     * @param plan                    the legacy migration plan. Supplied rather than read through a
     *                                port because it is an approved artefact of the programme, not
     *                                per-contract data: cohorts are struck once and re-used across
     *                                every re-run of the pack
     * @param membership              which cohort each contract is asserted into. Supplied for the
     *                                reason {@link AssertedCohortMembership} is named after — nothing
     *                                in this engine evaluates {@code LegacyCohort.definition}, so
     *                                this mapping cannot be computed, and a use case that quietly
     *                                derived one would be inventing membership
     * @param belowMarketOriginations the day-1 below-market measurements to assert BM-1 over. A
     *                                parameter for {@code RunClose}'s reason: this class cannot
     *                                source them and must not fabricate them, and passing none means
     *                                BM-1 is not asserted rather than asserted over nothing
     * @param queue                   every entry the barrier raises is filed here, in population
     *                                order, so two runs of one population file byte-identically
     */
    public static TransitionRun perform(
        TransitionRequest request,
        LegacyMigrationPlan plan,
        AssertedCohortMembership membership,
        List<BelowMarketOrigination> belowMarketOriginations,
        ExceptionQueue queue) {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(belowMarketOriginations, "belowMarketOriginations");
        Objects.requireNonNull(queue, "queue");

        List<String> population = List.copyOf(
            Objects.requireNonNull(request.contracts().contractIdsInScope(request.boundary()),
                "contractIdsInScope"));
        requireNoDuplicates(population, request.runId());

        List<ContractTransitionOutcome> outcomes = new ArrayList<>(population.size());
        List<ExceptionRecord> isolated = new ArrayList<>();
        for (String contractId : population) {
            FailureIsolation.Outcome<ContractTransitionOutcome> attempt = FailureIsolation.isolate(
                contractId, request.runId(), ExceptionCategory.MISSING_MANDATORY_FIELD,
                () -> position(request, plan, membership, contractId));
            if (attempt.succeeded()) {
                outcomes.add(attempt.value());
            } else {
                // Carried as its own list rather than fabricated into an outcome with null fields.
                // "Every contract in the population was valued or isolated" is a claim this module
                // makes, and a synthetic outcome would make it unfalsifiable — OnboardingRun's
                // argument for keeping its unreadable entries apart from its outcomes.
                isolated.add(attempt.exception());
                queue.raise(attempt.exception());
            }
        }

        List<TransitionFairValue> valuations = outcomes.stream()
            .map(ContractTransitionOutcome::valuation)
            .toList();
        // The explicit constructor, not TransitionValuationRun.over(...). over() throws on an empty
        // list — reasonably, since it takes its date from the first valuation — and an exercise that
        // valued nothing has to be REPORTED, not thrown: a throw would leave the caller with no
        // population figure at all, which is the state in which an empty run looks like no run. The
        // type's own javadoc points here: "supply the date explicitly if the intent is a run over a
        // book that produced nothing."
        TransitionValuationRun valuationRun =
            new TransitionValuationRun(request.transitionDate(), valuations);

        List<ContractMigrationState> states = outcomes.stream()
            .map(ContractTransitionOutcome::migration)
            .toList();
        // The population, not states.size(). TM-1's untracked leg is the difference between the two,
        // and passing the number of positions recorded would make it nil by construction — a
        // migration report that is complete over the contracts it happened to see, which is 04 § 6's
        // stated reason for giving the discount basis its own table.
        MigrationTracker tracker = MigrationTracker.over(states, population.size());

        TransitionCoverage coverage = TransitionCoverage.of(
            request.boundary().businessAsOf(), states, tracker, plan);

        return new TransitionRun(request.runId(), request.transitionDate(), request.boundary(),
            population, outcomes, isolated, valuationRun, tracker, plan,
            List.copyOf(belowMarketOriginations), coverage);
    }

    /**
     * One contract's transition position, or a throw the barrier turns into a queue entry.
     *
     * <p>Thrown rather than returned as an empty, because this runs <em>inside</em>
     * {@link FailureIsolation#isolate} and the barrier's job is to turn a throw about one contract
     * into a queue entry about one contract. {@link IllegalStateException} is not a shape
     * {@code FailureIsolation.recognise} names, so each of these files under the fallback category
     * the caller passed.
     */
    private static ContractTransitionOutcome position(
        TransitionRequest request,
        LegacyMigrationPlan plan,
        AssertedCohortMembership membership,
        String contractId) {

        AsAtBoundary boundary = request.boundary();
        ContractStateSource.OpeningState state = request.contractState()
            .openingState(contractId, boundary)
            .orElseThrow(() -> new IllegalStateException(
                "contract " + contractId + " is in the transition population as at "
                    + boundary.businessAsOf() + " (recorded as at " + boundary.recordedAsAt()
                    + ") and the state source holds no opening state for it, so there is no"
                    + " pre-transition carrying amount to value against. Isolated rather than"
                    + " skipped: a population short by one contract reconciles perfectly, because"
                    + " that contract is absent from both sides of every total (FR-905)"));

        TransitionSource.FairValueMeasurement measurement = request.source()
            .fairValueMeasurement(contractId, boundary)
            .orElseThrow(() -> new IllegalStateException(
                "contract " + contractId + " has no ACPIR 19 fair value measurement on file as at "
                    + boundary.recordedAsAt() + ". Isolated rather than valued at its carrying"
                    + " amount: defaulting to carrying cost would silently apply the paragraph 19"
                    + " presumption on this contract's behalf, produce a difference to opening"
                    + " retained earnings of exactly nil, and be counted by TF-1 as a presumption"
                    + " somebody chose"));

        // The book's side of the difference. The measurement supplies the other side and neither
        // supplies both — see the class javadoc.
        Money preTransitionCarryingAmount = state.openingGca();
        TransitionFairValue valuation =
            measurement.at(contractId, request.transitionDate(), preTransitionCarryingAmount);

        TransitionSource.EclDiscountPosition ecl = request.source()
            .eclDiscountPosition(contractId, boundary)
            .orElseThrow(() -> new IllegalStateException(
                "contract " + contractId + " has no recorded ECL discount basis for period "
                    + request.periodId() + ". Isolated rather than assumed onto the interim basis:"
                    + " a contract with no recorded basis is not one that has yet to migrate, it is"
                    + " one whose position nobody knows, and TM-1 counts the two differently"
                    + " because their remedies differ (04 § 6)"));

        // ACPIR 21 from the rate in force, ACPIR 50 from the recorded basis. Two obligations, two
        // sources, as 04 § 6 requires.
        ContractMigrationState migration =
            ecl.stateOf(contractId, request.periodId(), state.hasBeenSolved());

        return new ContractTransitionOutcome(contractId, valuation, migration,
            assignment(contractId, plan, membership, state.eir()));
    }

    /**
     * The contract's rate basis under the plan: deemed, reconstructed, or outstanding.
     *
     * <p>An asserted cohort name the plan does not define produces {@link LegacyRateBasis#UNASSIGNED}
     * rather than a throw, and the distinction matters. The contract's valuation succeeded and its
     * migration position is recorded — both are real figures — and isolating it would remove them
     * from the totals over a defect in the plan's own bookkeeping. So the figures stand, the contract
     * is reported as unplaced, and {@link TransitionRun#blockingReasons()} names it: nothing says how
     * it comes onto the EIR by 31 March 2030.
     */
    private static LegacyRateAssignment assignment(
        String contractId,
        LegacyMigrationPlan plan,
        AssertedCohortMembership membership,
        Rate rateInForce) {

        Optional<String> cohortName = membership.cohortOf(contractId);
        if (cohortName.isEmpty()) {
            return LegacyRateAssignment.unassigned(contractId, rateInForce);
        }
        Optional<LegacyCohort> cohort = plan.cohorts().stream()
            .filter(candidate -> candidate.cohortName().equals(cohortName.get()))
            .findFirst();
        if (cohort.isEmpty()) {
            return LegacyRateAssignment.unassigned(contractId, rateInForce);
        }
        return LegacyRateAssignment.under(contractId, cohort.get(), plan, rateInForce);
    }

    /**
     * The population, checked for duplicates before any contract is valued.
     *
     * <p>A pass of its own and a throw, not a reported reason, for {@code FailureIsolation.runBatch}'s
     * stated reason: a duplicate "would overwrite one contract's figures with another's and the loss
     * would be invisible in the output". Checked before the loop so that a duplicate never leaves the
     * caller's queue holding entries from an exercise whose results were then discarded.
     */
    private static void requireNoDuplicates(List<String> population, String runId) {
        Set<String> seen = new HashSet<>(Math.max(16, population.size() * 2));
        for (String contractId : population) {
            if (!seen.add(contractId)) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " appears twice in the transition population of "
                        + runId + "; one valuation would overwrite the other and the loss would be"
                        + " invisible in the total difference to opening retained earnings");
            }
        }
    }
}
