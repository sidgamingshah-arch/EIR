package com.crisil.eir.batch;

import com.crisil.eir.application.replay.ReplayRequest;
import java.time.LocalDate;
import java.util.List;

/**
 * The closed periods tonight's C-12 could replay — one candidate per published run
 * (05 § 3.3, FR-903, 07 § 4.1 C-12).
 *
 * <p><b>Candidates, not a sample.</b> Which of them tonight actually replays is
 * {@code ReplaySample}'s rotation, applied inside {@code ReplayUseCase.replayNightly}, and it must
 * stay there: the rotation is what makes the control's coverage claim
 * ({@code ReplaySample.nightsToCoverEligible()}) true, and a source that selected on its own behalf
 * would make that claim a statement about code nobody looked at. So this port answers "what is
 * available" and nothing about "what tonight is for".
 *
 * <p><b>An empty answer is a legitimate answer,</b> and it is the reason this port exists rather
 * than a list being passed in. Before the first close there is nothing to replay;
 * {@code NightlyReplayReport.coverageWarning()} distinguishes that case ("Not a defect — but
 * tonight's run is not evidence that any period reproduces") from a basis dialled to zero during a
 * freeze and never dialled back. Both produce no verifications, and the schedule must not report
 * either as a clean night — see {@link NightlyReplayOutcome}.
 *
 * <p><b>Why {@code nightOf} is an argument.</b> Not so the source can pick differently on different
 * nights, but because the candidate set legitimately grows: a period closed this morning is
 * replayable tonight and was not replayable last night. A source that ignores the argument and
 * returns everything closed is a correct implementation of this interface.
 */
public interface ReplayCandidateSource {

    /**
     * Every period with a published run available to be replayed on {@code nightOf}.
     *
     * <p>At most one candidate per period. {@code ReplayUseCase.replayNightly} refuses two published
     * runs for one period, with the reason that "a period has one published artefact, and DT-1's
     * reference cannot be whichever of two was found first" — this port does not need to restate the
     * check, and an implementation reading {@code amortisation_run} should know that two rows for one
     * period is a condition it will be refused for rather than one it can pass along.
     */
    List<ReplayRequest> candidatesFor(LocalDate nightOf);
}
