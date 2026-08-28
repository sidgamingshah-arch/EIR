package com.crisil.eir.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * The system-time boundary every port reads as at (04 § 5, 05 § 3.3).
 *
 * <p><b>An explicit argument rather than "now", everywhere.</b> This is the single mechanism that
 * makes a replay possible. 05 § 3.3: a replay "reads the contract version set as at the original
 * run's {@code recorded_at}, the policy and rule-set versions effective then, and the ECL input
 * version consumed then". If any port answered from the current state instead, the replay would
 * produce figures that are internally consistent and do not reproduce — and DT-1 would fail with
 * no way to say which input drifted.
 *
 * <p><b>Business time and system time are both here, and they are different questions.</b>
 * {@code businessAsOf} is the date the figures are about — the period end. {@code recordedAsAt} is
 * the moment the data set was known. A live run asks for the period end and the latest knowledge;
 * a replay asks for the same period end and the knowledge as at the original run. Collapsing them
 * into one field is the mistake 04 § 5 exists to prevent: a correction recorded after the close
 * changes system time and must not change what the period is about.
 *
 * @param businessAsOf the date the figures concern; the accounting period's end
 * @param recordedAsAt the system-time boundary — data recorded after this instant is not visible
 * @param replayOf     the run being reproduced, or null for a live run
 */
public record AsAtBoundary(
    java.time.LocalDate businessAsOf, Instant recordedAsAt, String replayOf) {

    public AsAtBoundary {
        Objects.requireNonNull(businessAsOf, "businessAsOf");
        Objects.requireNonNull(recordedAsAt, "recordedAsAt");
        replayOf = replayOf == null || replayOf.isBlank() ? null : replayOf.strip();
    }

    /**
     * A live run: the period end, and knowledge as at a supplied instant.
     *
     * <p>The instant is supplied and not read from a clock even here, because a run that reads
     * {@code Instant.now()} at its own start cannot be re-run to the same boundary tomorrow — and
     * "re-run the close" is an ordinary operational request, not only a replay.
     */
    public static AsAtBoundary live(java.time.LocalDate periodEnd, Instant knownAt) {
        return new AsAtBoundary(periodEnd, knownAt, null);
    }

    /** A replay of {@code runId}, reading the world as the original run saw it. */
    public static AsAtBoundary replaying(
        java.time.LocalDate periodEnd, Instant originalRecordedAt, String runId) {
        Objects.requireNonNull(runId, "runId");
        return new AsAtBoundary(periodEnd, originalRecordedAt, runId);
    }

    /** Whether this boundary is reproducing an earlier run. */
    public boolean isReplay() {
        return replayOf != null;
    }
}
