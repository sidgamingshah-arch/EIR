package com.crisil.eir.application.replay;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.replay.ReplaySample;
import com.crisil.eir.policy.replay.SampleOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One night of control C-12: the sample, the periods replayed, and the one DT-1 result the night
 * is entitled to (07 § 4.1 C-12, FR-903, 05 § 3.3 "run nightly against a sampled period").
 *
 * <h2>Why the DT-1 result is an {@link Optional}</h2>
 *
 * <p>Because a night on which nothing was replayed has no DT-1 answer, and both of the available
 * answers would be wrong. A <b>pass</b> is a tautology on a control that did not run, which this
 * codebase treats as worse than an absent control because it reads as coverage. A <b>fail</b>
 * reports "the replay differed" about a replay that never happened, and its deviation — a count of
 * discrepancies — would be counting something else; {@code ReplaySample.isInert()} spells that out
 * and declines to publish DT-1 for exactly this reason, noting that what the condition really
 * wants is an invariant id of its own for C-12 <em>coverage</em>, and that none exists.
 *
 * <p>So the absence is the answer, and it is made loud rather than quiet: {@link #controlRan()},
 * {@link #coverageWarning()} and {@link #describe()} all name it, {@link #allReproduced()} is
 * false, and {@link #invariantResults()} returns an empty list so a caller conjoining a night's
 * results cannot get a pass out of a night that replayed nothing. That last one is the hazard
 * worth stating plainly: {@code allMatch(InvariantResult::satisfied)} over an empty list is
 * {@code true}, so a caller must ask {@link #controlRan()} and not only look at the invariants.
 *
 * @param sample        tonight's C-12 sample — the basis, the population and what it selected
 * @param verifications one per selected period, in the order the sample selected them
 */
public record NightlyReplayReport(ReplaySample sample, List<ReplayVerification> verifications) {

    public NightlyReplayReport {
        Objects.requireNonNull(sample, "sample");
        verifications = List.copyOf(Objects.requireNonNull(verifications, "verifications"));
        if (verifications.size() != sample.selected().size()) {
            // Every selected period is replayed or the night's evidence does not match its own
            // stated basis — a sampled control whose sample and whose results disagree is not
            // evidence, it is two documents.
            throw new IllegalArgumentException(
                "tonight's sample selected " + sample.selected().size() + " period(s) and "
                    + verifications.size() + " were replayed; C-12's evidence is the basis as much"
                    + " as the outcome, and the two have to describe the same night");
        }
    }

    /**
     * The night's DT-1, or empty where nothing was replayed.
     *
     * <p><b>What input makes this fail?</b> Any period in tonight's sample whose replay produced a
     * discrepancy — a figure at a different value or a different scale, a figure present on one
     * side only, a policy version divergence on identical figures, or a comparison that compared
     * nothing over a non-empty population. See {@link ReplayVerification#dtOne()}, which is where
     * each of those is raised; this method only aggregates.
     *
     * <p><b>Why the aggregate is built rather than delegated to
     * {@link InvariantResult#conjunction}.</b> Conjunction keeps only the <em>first</em> breach's
     * deviation among results sharing an id, so a night that replayed three periods and found
     * discrepancies in two would report the first period's count and silently drop the second's.
     * The deviation here is therefore the <b>sum</b> of the periods' counts.
     *
     * <p>A sum is safe here and would not be everywhere: every DT-1 deviation is a count of things
     * needing a remedy, so every term is a non-negative integer and two periods' breaches cannot
     * net to a pass. That is the whole reason DT-1's deviation is a count rather than a money
     * amount — a signed residual summed across periods could cancel, and the signature failure
     * this control catches, a scale drift, has a money size of exactly zero.
     */
    public Optional<InvariantResult> dtOne() {
        if (verifications.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal deviation = BigDecimal.ZERO;
        boolean satisfied = true;
        List<String> details = new ArrayList<>(verifications.size());
        for (ReplayVerification verification : verifications) {
            InvariantResult result = verification.dtOne();
            details.add("period " + verification.periodId() + ": " + result.detail());
            if (!result.satisfied()) {
                satisfied = false;
                deviation = deviation.add(result.deviation().abs());
            }
        }
        String detail = "C-12 on " + sample.nightOf() + " — " + sample.describe() + ". "
            + String.join(" | ", details);
        return Optional.of(satisfied
            ? InvariantResult.pass(InvariantId.DT_1, detail)
            : InvariantResult.fail(InvariantId.DT_1, detail, deviation));
    }

    /**
     * The night's invariant results — one DT-1, or none.
     *
     * <p>The shape a caller assembling a run's or a night's control evidence wants, and
     * {@link InvariantResult#oneResultPerInvariant} applied to it is a no-op by construction
     * because there is at most one result. Empty when the control did not run, which is a state
     * the caller must detect with {@link #controlRan()} — see the class comment.
     */
    public List<InvariantResult> invariantResults() {
        Optional<InvariantResult> result = dtOne();
        return result.isPresent() ? List.of(result.get()) : List.of();
    }

    /** Whether tonight's control replayed anything at all. */
    public boolean controlRan() {
        return !verifications.isEmpty();
    }

    /**
     * Whether every period tonight's control replayed demonstrably reproduced.
     *
     * <p>False on a night that replayed nothing, and that is the point: an empty night is not a
     * clean night. False, too, where a period passed DT-1 without a figure being compared — see
     * {@link ReplayVerification#provesReproduction()}.
     */
    public boolean allReproduced() {
        return controlRan() && verifications.stream()
            .allMatch(ReplayVerification::provesReproduction);
    }

    /** The periods that did not reproduce, for the exception queue. */
    public List<ReplayVerification> breaches() {
        return verifications.stream().filter(v -> !v.dtOne().satisfied()).toList();
    }

    /**
     * Why tonight's control proved nothing, naming the cause, or empty where it ran.
     *
     * <p>Three causes and they read completely differently to whoever has to fix it. Two come
     * straight from {@code ReplaySample.inertnessWarning()} — a basis dialled to zero periods a
     * night during a freeze and never dialled back, or a lookback window that was right when it
     * was chosen and has gone stale. The third is a book with nothing closed yet, which is not a
     * defect and says so.
     */
    public Optional<String> coverageWarning() {
        if (controlRan()) {
            return Optional.empty();
        }
        return Optional.of(sample.inertnessWarning().orElseGet(() ->
            "C-12 selected no period to replay on " + sample.nightOf()
                + " and no closed period is on file (" + SampleOutcome.NOTHING_CLOSED_YET
                + "). Not a defect — but tonight's run is not evidence that any period"
                + " reproduces, and no DT-1 result is published for it"));
    }

    /** A one-line audit sentence: the basis, what ran, and what it found. */
    public String describe() {
        StringBuilder sentence = new StringBuilder("C-12 night of ").append(sample.nightOf())
            .append(" — ").append(sample.describe());
        if (!controlRan()) {
            coverageWarning().ifPresent(warning -> sentence.append(". ").append(warning));
            return sentence.toString();
        }
        sentence.append(". Replayed ").append(verifications.size()).append(" period(s): ");
        for (int i = 0; i < verifications.size(); i++) {
            if (i > 0) {
                sentence.append(" | ");
            }
            sentence.append(verifications.get(i).describe());
        }
        return sentence.toString();
    }
}
