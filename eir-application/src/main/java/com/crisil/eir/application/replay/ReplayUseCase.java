package com.crisil.eir.application.replay;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayComparison;
import com.crisil.eir.policy.replay.ReplayRun;
import com.crisil.eir.policy.replay.ReplaySample;
import com.crisil.eir.policy.replay.ReplaySamplingBasis;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replay: the same batch job, with an as-at boundary (05 § 3.3, FR-903, invariant DT-1, control
 * C-12).
 *
 * <h2>The whole of 05 § 3.3, and where each clause lands</h2>
 *
 * <p>"A replay is the <b>same batch job</b> with {@code is_replay = true} and an <b>as-at
 * boundary</b>." — {@link ReplayRequest#shadowRunRequest}, which copies the live request's five
 * ports, its period and its book, and substitutes the run id and the boundary. This class runs no
 * projector, solves no rate, rolls nothing forward and builds no journal. If it did, the replay
 * would be a second pipeline and would prove nothing about the first: the two would drift, and
 * they would drift in exactly the way DT-1 exists to detect, because a replay that reproduces its
 * own arithmetic reproduces nothing about the run that published the figures.
 *
 * <p>"It reads the <b>contract version set as at the original run's {@code recorded_at}</b>, the
 * <b>policy and rule-set versions effective then</b>, and the <b>ECL input version consumed
 * then</b>." — all three through {@link AsAtBoundary}, which every port on the request takes as an
 * argument. This class supplies the boundary and asks nothing else of the ports: the contract
 * version set is {@code ContractSource}'s answer at that boundary, the ECL input version is on
 * {@code ContractStateSource.OpeningState.eclEngineVersion}, and the policy timeline is
 * {@code PolicySource}'s registry at that boundary, resolved at the period end.
 *
 * <p>"Output goes to a <b>shadow table</b> and is compared <b>byte-for-byte</b> with the published
 * figures — invariant DT-1" — {@link ShadowRun} for the shadow figures, at the scale the run
 * produced them and never rescaled, and {@link ReplayComparison} for the comparison.
 *
 * <p>"run nightly against a <b>sampled period</b>" — {@link #replayNightly}, over
 * {@link ReplaySample} and its stated {@link ReplaySamplingBasis}.
 *
 * <p>"Replay is not a reporting feature added at the end. It is the reason the data model is
 * bitemporal and the reason <b>nothing in the calculation path reads a wall clock</b>." — nothing
 * in this file reads one either. Every instant and every date is an input: the boundary comes from
 * the published run, the night comes from the caller, and the shadow run id is derived from both.
 *
 * <h2>What this class refuses to do</h2>
 *
 * <p><b>It does not resolve the replay's policy stamps on the run's behalf.</b> The registry is
 * read once, at the replay boundary, and handed to {@link ReplayComparison} as the
 * <em>expectation</em>. What the replay <em>cited</em> comes from the job itself
 * ({@link RunOutput#policyVersionsConsulted()}). Substituting the harness's own resolution for the
 * run's would make the policy leg compare the harness against itself — the exact defect
 * {@code ReplayComparison} records having shipped, where "the policy half of DT-1 was a no-op that
 * reported a pass". A job that reports no stamps is not quietly filled in for: every kind the
 * registry says governed the period becomes a {@code POLICY_KIND_NOT_CONSULTED} finding and DT-1
 * fails, which is the correct verdict on a job that cannot say what rule it read.
 *
 * <p><b>It does not report a reproduction over a comparison that compared nothing.</b> See
 * {@link ReplayCoverage}.
 */
public final class ReplayUseCase {

    private final AmortisationRun batchJob;

    /**
     * @param batchJob the same job a live run drives — see {@link AmortisationRun}
     */
    public ReplayUseCase(AmortisationRun batchJob) {
        this.batchJob = Objects.requireNonNull(batchJob, "batchJob");
    }

    /**
     * Replays one closed period and compares it with what the close published.
     *
     * <p>Five steps, in this order, and the order matters: the boundary is built before anything
     * is read, so no port can be asked a question that is not "as at this instant".
     *
     * <ol>
     *   <li>Build the shadow request — the live request with a replay boundary.</li>
     *   <li>Enumerate the population at that boundary, so FR-905's subtraction has a total.</li>
     *   <li>Run the job.</li>
     *   <li>Account for the population; refuse a run that lost or invented a contract.</li>
     *   <li>Reduce to shadow figures and compare, publishing one DT-1 result.</li>
     * </ol>
     *
     * @param request     the period to replay and the live request supplying the ports
     * @param shadowRunId the replay's own run id — {@link ReplayRequest#shadowRunId} derives a
     *                    deterministic one from the published run and the night
     * @throws IllegalStateException where the job's output does not account for its own
     *                               population; see below
     */
    public ReplayVerification replay(ReplayRequest request, String shadowRunId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(shadowRunId, "shadowRunId");

        RunRequest shadowRequest = request.shadowRunRequest(shadowRunId);
        AsAtBoundary boundary = shadowRequest.boundary();

        // Asked here as well as inside the job, and deliberately. The job's population is not
        // otherwise visible from outside it, so this is the only place FR-905's total can come
        // from — and the second read is itself a check: a ContractSource whose answer depends on
        // when it is asked rather than on the boundary it is handed is a port that reads a clock,
        // and this is one of the few places that would show.
        List<String> population =
            List.copyOf(shadowRequest.contracts().contractIdsInScope(boundary));

        RunOutput output = batchJob.execute(shadowRequest);
        Objects.requireNonNull(output, "the batch job returned no output for run " + shadowRunId);

        PopulationAccount account = PopulationAccount.of(population, output.contracts());
        if (!account.addsUp()) {
            // Refused, not reported, and not folded into DT-1. A malformed contract is a data
            // condition and belongs in the exception queue; a contract in the population with
            // neither a figure nor a quarantine record is a defect in the job, and
            // FailureIsolation takes the same line on a computation that returns null: "the
            // barrier isolates malformed contracts, not stages that decline to answer".
            //
            // Folding it into DT-1 would be actively harmful. A dropped contract surfaces in the
            // comparison as MISSING_FROM_REPLAY — indistinguishable from a determinism drift —
            // so the night would be spent tracing arithmetic for a row that was never computed.
            throw new IllegalStateException(
                "replay " + shadowRunId + " of " + request.published().runId()
                    + " does not account for its own population: " + account.describe()
                    + ". FR-905 isolates per contract; every contract in the population is either"
                    + " computed or quarantined, and a run that silently processed fewer than it"
                    + " was given reconciles perfectly against itself");
        }

        // The registry as the ORIGINAL run's knowledge boundary saw it, resolved at the period
        // end. Both halves matter: the system-time half is 05 § 3.3's "the policy and rule-set
        // versions effective then", and the business-time half is ClosedPeriod's
        // policyResolutionDate(), which carries the off-by-one — the period end, never the close
        // date, because 1 April is the commonest effective date in the Indian fiscal calendar and
        // a March period closed in April would otherwise be reproduced under the new fiscal
        // year's rule.
        PolicyVersionRegistry registry = shadowRequest.policy().policyVersions(boundary);
        Objects.requireNonNull(registry,
            "PolicySource returned no registry at " + boundary.recordedAsAt()
                + "; FR-903's second half — under the policy then in force — cannot be asserted"
                + " against a timeline nobody supplied");

        ReplayRun shadow = ShadowRun.of(shadowRunId, request.published(), output);
        ReplayComparison comparison = ReplayComparison.of(
            request.published().period(), request.published().run(), shadow, registry);

        return new ReplayVerification(
            request.published(), shadowRequest, shadow, comparison, account,
            ReplayCoverage.of(account, comparison.figuresCompared()));
    }

    /**
     * Tonight's C-12: draw the sample, replay what it selected, publish one DT-1 for the night.
     *
     * <p>The sample is drawn over the candidates' own closed periods, so the population the basis
     * is applied to is exactly the set of periods this caller can actually replay — a sampler
     * offered periods with no published run to compare against would select one and then have
     * nothing to do, and {@code ReplaySample} would report the night as having run.
     *
     * <p>Selection is {@code ReplaySample}'s rotation, which is reproducible from the night and the
     * population and visits every eligible period once per
     * {@code ReplaySample.nightsToCoverEligible()} nights. A night that selects nothing while
     * closed periods sit in front of it is a control that has stopped running, and the report says
     * so rather than publishing a clean night — see {@link NightlyReplayReport}.
     *
     * @param nightOf    the night the control runs; an input, never a clock read, which is what
     *                   makes tonight's sample and tonight's shadow run ids reproducible
     * @param basis      the stated sampling basis; {@code ReplaySamplingBasis.nightlyDefault()} is
     *                   one period a night from the last twelve
     * @param candidates the periods available to replay, one per published run
     */
    public NightlyReplayReport replayNightly(
        LocalDate nightOf, ReplaySamplingBasis basis, Collection<ReplayRequest> candidates) {
        Objects.requireNonNull(nightOf, "nightOf");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(candidates, "candidates");

        Map<Integer, ReplayRequest> byPeriod = new LinkedHashMap<>();
        List<ClosedPeriod> periods = new ArrayList<>(candidates.size());
        for (ReplayRequest candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            ReplayRequest clash = byPeriod.putIfAbsent(candidate.periodId(), candidate);
            if (clash != null) {
                // ReplaySample.forNight refuses a duplicate period in its population for the
                // reason that it makes the rotation's coverage claim false. Caught here too
                // because the duplicate arrives as two published runs for one period, and the
                // message that names them is more use than the one that names the period twice.
                throw new IllegalArgumentException(
                    "two published runs were offered for period " + candidate.periodId() + ": "
                        + clash.published().runId() + " and " + candidate.published().runId()
                        + "; a period has one published artefact, and DT-1's reference cannot be"
                        + " whichever of two was found first");
            }
            periods.add(candidate.published().period());
        }

        ReplaySample sample = ReplaySample.forNight(nightOf, basis, periods);
        List<ReplayVerification> verifications = new ArrayList<>(sample.selected().size());
        for (ClosedPeriod selected : sample.selected()) {
            ReplayRequest candidate = byPeriod.get(selected.periodId());
            verifications.add(replay(
                candidate,
                ReplayRequest.shadowRunId(candidate.published().runId(), nightOf)));
        }
        return new NightlyReplayReport(sample, verifications);
    }
}
