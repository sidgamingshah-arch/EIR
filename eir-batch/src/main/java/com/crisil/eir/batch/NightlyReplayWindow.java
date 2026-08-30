package com.crisil.eir.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Which night a scheduler firing belongs to — the one place in this engine that reads a clock
 * (05 § 3.3, 03 § 1.1, ADR-0003).
 *
 * <h2>Why this is a type and not {@code LocalDate.now()}</h2>
 *
 * <p>{@code ReplayUseCase.replayNightly} takes {@code nightOf} as an argument and says why: "an
 * input, never a clock read, which is what makes tonight's sample and tonight's shadow run ids
 * reproducible". Everything downstream of it is a pure function of that date —
 * {@code ReplaySample.forNight} rotates the eligible periods by it, and
 * {@code ReplayRequest.shadowRunId} derives the shadow row's id from it — so the date is the
 * control's identity and not a timestamp on a log line.
 *
 * <p>Which makes the mapping from "the scheduler fired" to "this is the night of" the one decision
 * that has to be made somewhere, and this is the somewhere. Two properties are required of it, and
 * neither is satisfied by reading the local date:
 *
 * <ul>
 *   <li><b>A firing after local midnight belongs to the night before.</b> A nightly batch scheduled
 *       for 01:30 IST fires on the 11th and is the night of the 10th. With {@code LocalDate.now()}
 *       it would be the night of the 11th — and then, because the rotation is a function of the
 *       night, tonight's firing and tomorrow's would land on the same index and one eligible period
 *       would never be visited. A control that quietly stops covering a period is worse than one
 *       that stops running, because {@code ReplaySample.nightsToCoverEligible()} still reports the
 *       coverage it no longer achieves.</li>
 *   <li><b>Two firings for one night replay the same sample.</b> An operator who retriggers a failed
 *       nightly job at 02:00 must get the night the job was for, not a new one. Because the shadow
 *       run id is derived from the night, that also makes the shadow table's writes idempotent —
 *       {@code ReplayRequest.shadowRunId}'s javadoc relies on exactly this.</li>
 * </ul>
 *
 * <h2>The cutover</h2>
 *
 * <p>A firing at or after {@link #cutover} local time belongs to that day's night; a firing before it
 * belongs to the previous day's. So with a 20:00 cutover, 20:30 on the 10th and 01:30 on the 11th are
 * both the night of the 10th, and 19:00 on the 11th is still the night of the 10th — which is the
 * right answer for a job that has not run yet that evening.
 *
 * <p><b>The zone is India's, and it matters.</b> Every date in this engine is an Indian accounting
 * date; a window in UTC would put a firing at 02:00 IST on the 11th (20:30 UTC on the 10th) into the
 * night of the 10th by accident and a firing at 04:00 IST into the night of the 11th, so the
 * boundary would fall in the middle of the batch window rather than outside it.
 *
 * @param zone    the zone the bank's nightly window is expressed in
 * @param cutover the local time at which a new night begins
 */
public record NightlyReplayWindow(ZoneId zone, LocalTime cutover) {

    /** Asia/Kolkata — every accounting date in this engine is an Indian one. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * 20:00 IST: after the end-of-day feeds and before the overnight batch window.
     *
     * <p>A default rather than a rule. The value the bank's own operations calendar dictates is a
     * constructor argument, and the only property this module needs of it is that it does not fall
     * inside the window the nightly job runs in — a cutover at 01:00 with a job scheduled for 01:30
     * would work until the job was ten minutes late.
     */
    public static final LocalTime DEFAULT_CUTOVER = LocalTime.of(20, 0);

    public NightlyReplayWindow {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(cutover, "cutover");
    }

    /** The default window: 20:00 Asia/Kolkata. */
    public static NightlyReplayWindow indiaDefault() {
        return new NightlyReplayWindow(INDIA, DEFAULT_CUTOVER);
    }

    /**
     * The night the firing at {@code firedAt} belongs to.
     *
     * <p>A pure function of its argument and this window. The clock read is the caller's — see
     * {@link #nightOf(Clock)} — so this method is testable at any instant and is where the
     * off-by-a-night is decided.
     */
    public LocalDate nightOf(Instant firedAt) {
        Objects.requireNonNull(firedAt, "firedAt");
        java.time.ZonedDateTime local = firedAt.atZone(zone);
        // isBefore, not isAfter-or-equal negated: a firing exactly at the cutover starts the new
        // night, so 20:00:00 on the 10th is the night of the 10th. The boundary case has to be
        // pinned somewhere and this is the reading a scheduler configured "at 20:00" expects.
        return local.toLocalTime().isBefore(cutover)
            ? local.toLocalDate().minusDays(1)
            : local.toLocalDate();
    }

    /**
     * The night as at {@code clock} — the single clock read in this module.
     *
     * <p>Separated from {@link #nightOf(Instant)} so that the read and the decision are testable
     * apart, and so that this is the only line an audit has to look at to confirm 05 § 3.3's
     * "nothing in the calculation path reads a wall clock" is still true of everything below it.
     */
    public LocalDate nightOf(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return nightOf(clock.instant());
    }

    /** A one-line description for a control report. */
    public String describe() {
        return "nights begin at " + cutover + " " + zone;
    }
}
