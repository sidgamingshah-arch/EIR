package com.crisil.eir.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which night a scheduler firing belongs to — the one clock read below the API layer (05 § 3.3,
 * 03 § 1.1).
 *
 * <p>Everything downstream of {@code nightOf} is a pure function of it: {@code ReplaySample.forNight}
 * rotates the eligible periods by {@code nightOf.toEpochDay()}, and
 * {@code ReplayRequest.shadowRunId} derives the shadow table's row id from it. So getting the night
 * wrong is not a mislabelled log line — it changes which period is replayed and which row is written.
 *
 * <p>The instants below are written as UTC and read in IST (+05:30), which is the arithmetic a
 * scheduler configured in local time actually does.
 */
@DisplayName("The nightly replay window")
class NightlyReplayWindowTest {

    private static final NightlyReplayWindow WINDOW = NightlyReplayWindow.indiaDefault();

    @Nested
    @DisplayName("A firing after local midnight belongs to the night before")
    class AcrossMidnight {

        @Test
        @DisplayName("01:30 IST on the 11th is the night of the 10th")
        void afterMidnightIsThePreviousNight() {
            // 01:30 IST on 11 August 2027 is 20:00 UTC on the 10th.
            Instant firedAt = Instant.parse("2027-08-10T20:00:00Z");
            assertThat(firedAt.atZone(NightlyReplayWindow.INDIA).toLocalTime())
                .as("the arithmetic this test rests on")
                .isEqualTo(LocalTime.of(1, 30));

            // LocalDate.now() would say the 11th. Because ReplaySample's rotation offset is
            // nightOf.toEpochDay() mod the eligible count, that shift makes tonight's firing and
            // tomorrow's land on the same index, and one eligible period is never visited — a
            // control that has quietly stopped covering a period while
            // nightsToCoverEligible() still reports the coverage it no longer achieves.
            assertThat(WINDOW.nightOf(firedAt)).isEqualTo(LocalDate.of(2027, 8, 10));
        }

        @Test
        @DisplayName("20:30 IST on the 10th is also the night of the 10th")
        void afterTheCutoverIsTheSameNight() {
            // 20:30 IST on the 10th is 15:00 UTC on the 10th.
            Instant firedAt = Instant.parse("2027-08-10T15:00:00Z");
            assertThat(firedAt.atZone(NightlyReplayWindow.INDIA).toLocalTime())
                .isEqualTo(LocalTime.of(20, 30));
            assertThat(WINDOW.nightOf(firedAt)).isEqualTo(LocalDate.of(2027, 8, 10));
        }

        @Test
        @DisplayName("19:00 IST on the 11th is still the night of the 10th")
        void beforeTheCutoverIsStillThePreviousNight() {
            // 19:00 IST on the 11th is 13:30 UTC on the 11th — the evening job has not run yet.
            Instant firedAt = Instant.parse("2027-08-11T13:30:00Z");
            assertThat(WINDOW.nightOf(firedAt)).isEqualTo(LocalDate.of(2027, 8, 10));
        }

        @Test
        @DisplayName("exactly at the cutover starts the new night")
        void theBoundaryItselfStartsTheNewNight() {
            // 20:00:00 IST on the 11th is 14:30 UTC. The boundary case has to be pinned somewhere,
            // and this is the reading a scheduler configured "at 20:00" expects.
            Instant firedAt = Instant.parse("2027-08-11T14:30:00Z");
            assertThat(firedAt.atZone(NightlyReplayWindow.INDIA).toLocalTime())
                .isEqualTo(NightlyReplayWindow.DEFAULT_CUTOVER);
            assertThat(WINDOW.nightOf(firedAt)).isEqualTo(LocalDate.of(2027, 8, 11));
        }
    }

    @Nested
    @DisplayName("Idempotence and the zone")
    class Idempotence {

        @Test
        @DisplayName("every firing within one night maps to that one night")
        void everyFiringInOneNightIsOneNight() {
            // A scheduled 22:00 run, a retrigger at 23:15, and an operator's third attempt at 02:45
            // the next morning. All three must be the night of the 10th, because the shadow run id
            // is derived from the night: two of the three writing under a different id would put
            // three rows in the shadow table for one night's control and DT-1's reference would be
            // whichever was found first.
            Set<LocalDate> nights = new LinkedHashSet<>();
            nights.add(WINDOW.nightOf(Instant.parse("2027-08-10T16:30:00Z")));  // 22:00 IST 10th
            nights.add(WINDOW.nightOf(Instant.parse("2027-08-10T17:45:00Z")));  // 23:15 IST 10th
            nights.add(WINDOW.nightOf(Instant.parse("2027-08-10T21:15:00Z")));  // 02:45 IST 11th

            assertThat(nights).containsExactly(LocalDate.of(2027, 8, 10));
        }

        @Test
        @DisplayName("a UTC window would put the boundary inside the batch window")
        void theZoneMatters() {
            // 02:00 IST on the 11th is 20:30 UTC on the 10th; 04:00 IST is 22:30 UTC on the 10th.
            // Under the Indian window both are the night of the 10th. Under a UTC window with the
            // same 20:00 cutover they would be too — but 07:00 IST (01:30 UTC on the 11th) would
            // become the night of the 10th under India and the night of the 10th under UTC as well,
            // while 20:30 IST on the 10th (15:00 UTC) would be the night of the NINTH under UTC.
            // The boundary lands in the middle of the batch window rather than outside it.
            NightlyReplayWindow utc =
                new NightlyReplayWindow(ZoneId.of("UTC"), LocalTime.of(20, 0));
            Instant earlyEvening = Instant.parse("2027-08-10T15:00:00Z");  // 20:30 IST on the 10th

            assertThat(WINDOW.nightOf(earlyEvening)).isEqualTo(LocalDate.of(2027, 8, 10));
            assertThat(utc.nightOf(earlyEvening))
                .as("the same firing, one night earlier, because 15:00 UTC precedes a UTC cutover")
                .isEqualTo(LocalDate.of(2027, 8, 9));
        }

        @Test
        @DisplayName("the clock overload reads the clock and does nothing else")
        void theClockOverloadOnlyReadsTheClock() {
            Instant firedAt = Instant.parse("2027-08-10T20:00:00Z");
            Clock fixed = Clock.fixed(firedAt, ZoneId.of("UTC"));

            assertThat(WINDOW.nightOf(fixed)).isEqualTo(WINDOW.nightOf(firedAt));
            assertThat(WINDOW.describe()).contains("20:00").contains("Asia/Kolkata");
        }
    }
}
