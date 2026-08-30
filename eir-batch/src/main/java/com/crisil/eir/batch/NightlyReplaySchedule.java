package com.crisil.eir.batch;

import com.crisil.eir.application.replay.NightlyReplayReport;
import com.crisil.eir.application.replay.ReplayRequest;
import com.crisil.eir.application.replay.ReplayUseCase;
import com.crisil.eir.policy.replay.ReplaySamplingBasis;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The caller {@code ReplayUseCase.replayNightly} did not have (05 § 3.3, FR-903, control C-12).
 *
 * <h2>The gap this closes</h2>
 *
 * <p>docs/08, Phase 5: "<b>What still has no caller</b>, stated as narrowly as it now deserves:
 * {@code NightlyReplayReport} is built by {@code ReplayUseCase.replayNightly} and nothing schedules
 * it, which is {@code eir-batch}'s job." Everything below the schedule was already there and
 * correct: the sample and its rotation, the boundary, the byte comparison, the policy comparison, the
 * coverage verdict. What was missing was something that decided <em>which night it is</em>, asked for
 * the candidates, ran the control and said whether the night was evidence — and, crucially, said no
 * on a night that replayed nothing.
 *
 * <h2>The three lines that matter</h2>
 *
 * <ol>
 *   <li>{@link NightlyReplayWindow#nightOf(Clock)} — the only clock read below the API layer, and the
 *       one that makes a firing at 01:30 belong to the night before. Everything downstream is a pure
 *       function of the resulting date, including the shadow run ids, so a retrigger of the same
 *       night is idempotent.</li>
 *   <li>{@code ReplayUseCase.replayNightly(nightOf, basis, candidates)} — unchanged, unwrapped, and
 *       not second-guessed. The sampling rotation, the population accounting and DT-1 all stay where
 *       they are.</li>
 *   <li>{@link NightlyReplayOutcome#of} — the verdict, which reads {@code provesReproduction()} and
 *       not {@code dtOne().satisfied()}. See that type for why the difference is the whole point.</li>
 * </ol>
 *
 * <h2>What schedules the schedule</h2>
 *
 * <p>The bank's own scheduler, through {@link NightlyReplayBatchJob} — a Spring Batch job with one
 * step, which an operations cron entry triggers and which fails visibly on a night that is not
 * evidence. No timer thread is started here, deliberately: a thread that fires on an interval is
 * untestable at the boundary that matters (does 01:30 belong to the night before?) and unobservable
 * to the operations tooling a bank actually runs its overnight window from. The design decision that
 * had to be made was <em>which night a firing is for</em>, and it is made in
 * {@link NightlyReplayWindow} where it can be tested at any instant.
 *
 * <h2>No clock below this class</h2>
 *
 * <p>{@link #runNight(LocalDate)} takes the night as an argument and is the method every test uses.
 * {@link #runNow()} is the one that reads the clock, and it does nothing else. That split is not
 * cosmetic: it is what lets a test assert the boundary behaviour of the window and the verdict
 * behaviour of the control without a fixed clock threaded through either.
 */
public final class NightlyReplaySchedule {

    private final ReplayUseCase replays;
    private final ReplayCandidateSource candidates;
    private final ReplaySamplingBasis basis;
    private final NightlyReplayWindow window;
    private final Clock clock;

    /**
     * @param replays    the control; drives {@link AmortisationJobRun}, so tonight's replay is the
     *                   same partitioned job the close ran (05 § 3.3)
     * @param candidates the closed periods available to replay
     * @param basis      the stated sampling basis; {@code ReplaySamplingBasis.nightlyDefault()} is
     *                   one period a night from the last twelve
     * @param window     which night a firing belongs to
     * @param clock      read by {@link #runNow()} and by nothing else
     */
    public NightlyReplaySchedule(
        ReplayUseCase replays,
        ReplayCandidateSource candidates,
        ReplaySamplingBasis basis,
        NightlyReplayWindow window,
        Clock clock) {
        this.replays = Objects.requireNonNull(replays, "replays");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.basis = Objects.requireNonNull(basis, "basis");
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the control for the night this firing belongs to.
     *
     * <p>The entry point a scheduler reaches. Two lines, and the first of them is the clock read.
     */
    public NightlyReplayOutcome runNow() {
        return runNight(window.nightOf(clock));
    }

    /**
     * Runs the control for a named night.
     *
     * <p>Reproducible: the same night over the same candidates draws the same sample, replays the
     * same periods and writes the same shadow run ids ({@code ReplayRequest.shadowRunId}). So an
     * operator retriggering a failed nightly job gets the night it was for rather than a fresh one,
     * and the shadow table's rows are overwritten rather than duplicated.
     *
     * <p><b>The candidates are read once, for this night.</b> A source read twice could answer
     * differently between the sample being drawn and the periods being replayed, and the sample's
     * coverage claim is a claim about the population it was drawn over.
     */
    public NightlyReplayOutcome runNight(LocalDate nightOf) {
        Objects.requireNonNull(nightOf, "nightOf");
        List<ReplayRequest> available = candidates.candidatesFor(nightOf);
        Objects.requireNonNull(available,
            "the replay candidate source returned null for the night of " + nightOf
                + "; an empty night is a legitimate answer and is reported as one, but a null is a"
                + " source that could not answer, and reporting it as an empty night would publish"
                + " 'nothing closed yet' about a book that may be fully closed");
        NightlyReplayReport report = replays.replayNightly(nightOf, basis, available);
        return NightlyReplayOutcome.of(nightOf, report);
    }

    /** The window this schedule derives its nights from — for a control report's own header. */
    public NightlyReplayWindow window() {
        return window;
    }
}
