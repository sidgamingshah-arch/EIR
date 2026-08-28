package com.crisil.eir.application.replay;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.replay.ReplayComparison;
import com.crisil.eir.policy.replay.ReplayRun;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One period replayed and compared: the DT-1 result, and the evidence behind it (FR-903, C-12,
 * 05 § 3.3).
 *
 * <p>Everything on this record is evidence rather than commentary. The shadow request is here
 * because "the same batch job with an as-at boundary" is a claim somebody will want to check; the
 * population account is here because FR-905's subtraction is what says the replay ran over the
 * whole period; the comparison is here because DT-1's deviation is a count and a count sends
 * nobody anywhere on its own.
 *
 * @param published    the run whose figures were published
 * @param shadowRequest the request the batch job was actually given — identical to the live
 *                      template but for the run id and the boundary
 * @param shadow       what the replay produced, as the shadow table holds it
 * @param comparison   the byte comparison and the policy comparison
 * @param population   FR-905's accounting over the replay's own population
 * @param coverage     what the figure leg looked at; see {@link ReplayCoverage}
 */
public record ReplayVerification(
    PublishedRun published,
    RunRequest shadowRequest,
    ReplayRun shadow,
    ReplayComparison comparison,
    PopulationAccount population,
    ReplayCoverage coverage) {

    public ReplayVerification {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(shadowRequest, "shadowRequest");
        Objects.requireNonNull(shadow, "shadow");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(coverage, "coverage");
        if (!shadowRequest.isReplay()) {
            // The one guard worth having on this record. A verification built from a run whose
            // boundary was not a replay boundary read the world as it is today, not as at the
            // original run's recorded_at, and its figures reproduce nothing however well they
            // agree. 05 § 3.3's "is_replay = true" is not a label on the row; it is the statement
            // that the boundary was moved.
            throw new IllegalArgumentException(
                "the run behind this verification carried a live boundary ("
                    + shadowRequest.boundary().recordedAsAt() + ", no replayOf); it read the"
                    + " world as at that instant rather than as at the original run's"
                    + " recorded_at, so its figures reproduce nothing (05 § 3.3)");
        }
    }

    /**
     * Invariant DT-1 for this period: one result, deviation a count of things needing a remedy.
     *
     * <p><b>What input makes this fail?</b> Five kinds of input, and each is reachable from data
     * this package accepts without complaint:
     *
     * <ul>
     *   <li>a replayed figure whose rendered decimal differs from the published one — including
     *       {@code 958295.910} against {@code 958295.91}, where the two are numerically identical
     *       and {@code Money.equals} reports a match. {@link ShadowRun} never rescales, so the
     *       drift survives to be seen;</li>
     *   <li>a figure the close published that the replay did not produce, or the reverse — which
     *       is what a contract quarantined on one side and computed on the other looks like;</li>
     *   <li><b>a policy version the replay resolved that differs from the one the close cited,
     *       with every figure bit-identical.</b> The most valuable failure available here:
     *       reproducing the right number from the wrong rule is luck, it will not hold next
     *       period, and nothing else in the engine can see it because every downstream check
     *       agrees;</li>
     *   <li>a policy kind the registry says governed the period that neither run cited — the
     *       shape a partly-wired job takes, and the reason {@link RunOutput} has no
     *       no-stamps convenience factory;</li>
     *   <li>a replay that compared no figure at all over a non-empty population — see
     *       {@link ReplayCoverage#POPULATION_PRODUCED_NO_FIGURE}. Without this leg the answer
     *       would be a pass, which is the codebase's signature defect in its purest form: a
     *       control reporting coverage for having looked at nothing.</li>
     * </ul>
     *
     * <p>Nothing in the construction path guards any of them away. The published figures arrive
     * from a port, the replayed ones from the batch job, the expected policy snapshot from the
     * registry, and no constructor compares any of the three against another.
     *
     * <p><b>One result under one id.</b> {@link InvariantResult#conjunction} keeps only the first
     * breach's deviation among results sharing an id, so the two legs are conjoined in the order
     * that keeps the more informative deviation: the comparison's count of discrepancies first,
     * the coverage failure's single remedy second. Where both fail the count survives, which is
     * the number somebody can act on.
     */
    public InvariantResult dtOne() {
        List<InvariantResult> legs = new ArrayList<>(2);
        legs.add(comparison.dtOne());
        if (coverage.isDefect()) {
            legs.add(InvariantResult.fail(InvariantId.DT_1,
                comparison.describe() + " — " + population.describe()
                    + ", and not one figure was compared; DT-1 cannot be satisfied by a"
                    + " comparison that compared nothing, and a period that produced no figure"
                    + " from a non-empty population did not reproduce, it was not measured",
                BigDecimal.ONE));
        }
        return InvariantResult.conjunction(legs);
    }

    /**
     * Whether this period demonstrably reproduces: DT-1 held <em>and</em> a figure was compared.
     *
     * <p>The second clause is not belt and braces. DT-1 passes over a period that published
     * nothing — correctly, because nothing failed — and a caller collecting C-12 evidence would
     * otherwise count an empty period as a period that reproduced. It is the difference between
     * "the control found no exception" and "the control ran".
     */
    public boolean provesReproduction() {
        return dtOne().satisfied() && coverage.provesFidelity();
    }

    /** The {@code YYYYMM} period replayed. */
    public int periodId() {
        return published.periodId();
    }

    /** The replay's own run id — the shadow table's row. */
    public String shadowRunId() {
        return shadow.runId();
    }

    /**
     * A one-line audit sentence: what was replayed, against what, over what population, and what
     * the comparison found.
     *
     * <p>The denominators lead, because a pass over nothing reads exactly like a pass over ten
     * thousand figures unless the population and the figure count are on the line.
     */
    public String describe() {
        return "replay of " + published.describe() + " as at "
            + shadowRequest.boundary().recordedAsAt() + "; " + population.describe()
            + "; coverage " + coverage + "; " + comparison.describe();
    }
}
