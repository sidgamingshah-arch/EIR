package com.crisil.eir.application.replay;

import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayComparison;
import com.crisil.eir.policy.replay.ReplayRun;
import java.time.Instant;
import java.util.Objects;

/**
 * The artefact a close published, and the one fact a replay cannot derive: when the run's data set
 * was known.
 *
 * <p><b>{@code recordedAt} is the whole reason this type exists.</b> 05 § 3.3: a replay "reads the
 * contract version set as at the original run's {@code recorded_at}, the policy and rule-set
 * versions effective then, and the ECL input version consumed then". That instant is the
 * {@code recorded_at} of the original run — {@code accounting_period.version_cutoff_at}'s
 * run-level counterpart — and it is the single input a replay has no way to compute. Business time
 * it can derive: the period end is on the {@link ClosedPeriod}. System time it cannot, and a
 * replay that guessed it with a clock read would produce figures that are internally consistent
 * and do not reproduce, which is the failure {@code AsAtBoundary}'s javadoc says DT-1 would then
 * report "with no way to say which input drifted".
 *
 * <p><b>What is deliberately not guarded here.</b> Nothing checks that {@code run} is not itself a
 * replay, and nothing compares it against anything. {@link ReplayComparison#of} refuses a
 * reference side that is a replay, with the reason stated where it belongs: "two replays agreeing
 * with each other proves they agree with each other". Restating that guard here would put one rule
 * in two places, which is this codebase's recurring defect; {@link ReplayUseCase} lets the refusal
 * come from the control.
 *
 * @param run        the published run reduced to what DT-1 compares — the figures at the scale
 *                   they were published at, and the policy versions the close cited
 * @param period     the closed period; supplies the business-time half of the replay boundary and
 *                   the policy resolution date ({@link ClosedPeriod#policyResolutionDate()})
 * @param recordedAt the original run's {@code recorded_at} — the system-time half of the replay
 *                   boundary
 * @param bookId     the book the run covered; a replay of a period must run the same book, or the
 *                   population differs and every figure is a finding
 */
public record PublishedRun(
    ReplayRun run, ClosedPeriod period, Instant recordedAt, String bookId) {

    public PublishedRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(bookId, "bookId");
        if (bookId.isBlank()) {
            throw new IllegalArgumentException(
                "published run " + run.runId() + " names no book; a replay has to run the same"
                    + " book or its population is a different population");
        }
        bookId = bookId.strip();
        if (run.periodId() != period.periodId()) {
            // Refused rather than reported. ReplayComparison would raise the same mismatch, but
            // it would raise it about the two runs it was handed; here the mismatch is between a
            // run and the period record supplied alongside it, and that is a defect in whoever
            // assembled this pair rather than a finding about a replay.
            throw new IllegalArgumentException(
                "published run " + run.runId() + " covers period " + run.periodId()
                    + " but was paired with " + period.describe()
                    + "; a replay of one would be measured against the figures of the other");
        }
    }

    /** The run id a replay reproduces — {@code AsAtBoundary.replayOf}. */
    public String runId() {
        return run.runId();
    }

    /** The {@code YYYYMM} period. */
    public int periodId() {
        return period.periodId();
    }

    /** A one-line audit sentence: the run, its period, and the knowledge boundary it was run at. */
    public String describe() {
        return run.describe() + ", recorded as at " + recordedAt + ", book " + bookId;
    }
}
