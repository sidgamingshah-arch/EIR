package com.crisil.eir.policy.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The C-12 sampling decision: which closed period gets replayed tonight, and on what basis.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>The selected period is derived from the rotation's definition — {@code eligible[floorMod(epoch
 * day, size)]} — with the epoch day cross-checked in python rather than read off a run of the
 * sampler:
 *
 * <pre>
 * python3 -c "import datetime; d=datetime.date(2028,4,10);
 *            print((d-datetime.date(1970,1,1)).days)"   -> 21284
 * 21284 mod 12 = 8
 * </pre>
 *
 * <p>The eligible list for that night is the twelve periods of FY2027-28 ascending, so index 8 is
 * 202712 — December 2027. The lookback arithmetic is likewise done by hand: tonight 2028-04 has
 * month ordinal {@code 2028*12 + 3 = 24339}, and 202704 has {@code 2027*12 + 3 = 24327}, a gap of
 * twelve, which a lookback of twelve admits and a lookback of eleven does not.
 *
 * <h2>What the file is for</h2>
 *
 * <p>Two things. That the sample is <em>reproducible</em> — a sampled control whose sample nobody
 * can recompute is not evidence, and "we replayed a random period" is not a workpaper. And that a
 * sample which selects <em>nothing</em> is distinguishable from one that found nothing wrong: see
 * {@link Inertness}, which is the half of this file that matters.
 */
class ReplaySampleTest {

    private static final LocalDate NIGHT = LocalDate.of(2028, 4, 10);

    /** The twelve months of FY2027-28, each closed five days after it ended. */
    private static List<ClosedPeriod> fiscalYear() {
        List<ClosedPeriod> periods = new ArrayList<>();
        YearMonth month = YearMonth.of(2027, 4);
        for (int i = 0; i < 12; i++) {
            periods.add(ClosedPeriod.month(
                month, month.atEndOfMonth().plusDays(5), "financial.controller"));
            month = month.plusMonths(1);
        }
        return periods;
    }

    @Nested
    @DisplayName("the sample is a decision with a basis, and it is reproducible")
    class Rotation {

        @Test
        @DisplayName("one period a night, chosen by rotation on the night's own epoch day")
        void selectsTheRotationIndex() {
            // Expected value derived above, not from a run: epoch day 21284 mod 12 = 8, and index
            // 8 of the ascending FY2027-28 list is December 2027.
            ReplaySample sample = ReplaySample.forNight(
                NIGHT, ReplaySamplingBasis.nightlyDefault(), fiscalYear());

            assertThat(sample.eligible()).hasSize(12);
            assertThat(sample.selected())
                .extracting(ClosedPeriod::periodId)
                .containsExactly(202712);
            assertThat(sample.outcome()).isEqualTo(SampleOutcome.PERIOD_SELECTED);
            assertThat(sample.controlRan()).isTrue();
        }

        @Test
        @DisplayName("the same night over the same population selects the same period")
        void isReproducible() {
            // The property that makes the sample evidence. Note the population is supplied in a
            // different order the second time: selection is a function of the data, not of how the
            // query happened to return.
            List<ClosedPeriod> forwards = fiscalYear();
            List<ClosedPeriod> backwards = new ArrayList<>(forwards);
            java.util.Collections.reverse(backwards);

            assertThat(ReplaySample.forNight(
                NIGHT, ReplaySamplingBasis.nightlyDefault(), forwards).selected())
                .isEqualTo(ReplaySample.forNight(
                    NIGHT, ReplaySamplingBasis.nightlyDefault(), backwards).selected());
        }

        @Test
        @DisplayName("twelve consecutive nights replay all twelve eligible periods exactly once")
        void rotationCoversEveryEligiblePeriod() {
            // The claim that justifies a rotation over a pseudo-random draw. Over twelve nights of
            // drawing one from twelve uniformly at random, the chance a given period is never
            // picked is (11/12)^12, about 35% — so a third of the book would go unreplayed in a
            // fortnight and nobody could say which third. The rotation's coverage is exact.
            //
            // All twelve nights are inside April 2028, so the eligible set does not move under the
            // rotation: the lookback is measured from tonight's month.
            Set<Integer> replayed = new LinkedHashSet<>();
            for (int night = 0; night < 12; night++) {
                ReplaySample sample = ReplaySample.forNight(
                    NIGHT.plusDays(night), ReplaySamplingBasis.nightlyDefault(), fiscalYear());
                assertThat(sample.eligible()).hasSize(12);
                sample.selected().forEach(period -> replayed.add(period.periodId()));
            }

            assertThat(replayed)
                .as("every period of FY2027-28, once each")
                .containsExactlyInAnyOrder(
                    202704, 202705, 202706, 202707, 202708, 202709,
                    202710, 202711, 202712, 202801, 202802, 202803);
            assertThat(ReplaySample.forNight(
                NIGHT, ReplaySamplingBasis.nightlyDefault(), fiscalYear())
                .nightsToCoverEligible())
                .as("twelve eligible, one a night")
                .isEqualTo(12);
        }

        @Test
        @DisplayName("a deeper sample takes consecutive entries and reports its own coverage")
        void aDeeperSample() {
            // Five a night over twelve eligible: ceil(12/5) = 3 nights for full coverage. Starting
            // at index 8 and wrapping, the five are indices 8, 9, 10, 11, 0 — 202712, 202801,
            // 202802, 202803 and 202704, reported ascending.
            ReplaySample sample = ReplaySample.forNight(
                NIGHT,
                new ReplaySamplingBasis(5, 12, "deep sample ahead of the year-end audit"),
                fiscalYear());

            assertThat(sample.selected())
                .extracting(ClosedPeriod::periodId)
                .containsExactly(202704, 202712, 202801, 202802, 202803);
            assertThat(sample.nightsToCoverEligible()).isEqualTo(3);
        }

        @Test
        @DisplayName("a sample deeper than the population takes the population and no more")
        void aSampleDeeperThanThePopulation() {
            ReplaySample sample = ReplaySample.forNight(
                NIGHT,
                new ReplaySamplingBasis(50, 12, "replay the lot"),
                fiscalYear());

            assertThat(sample.selected()).hasSize(12);
            assertThat(sample.nightsToCoverEligible())
                .as("one night covers everything")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the lookback excludes the period one step beyond it")
        void lookbackBoundary() {
            // Boundary derived from the ordinal arithmetic above: April 2027 sits exactly twelve
            // periods behind April 2028, so a lookback of twelve admits it and eleven does not.
            // Stated because an off-by-one here silently narrows the control's reach by a month.
            ReplaySample twelve = ReplaySample.forNight(
                NIGHT, new ReplaySamplingBasis(1, 12, "a fiscal year"), fiscalYear());
            ReplaySample eleven = ReplaySample.forNight(
                NIGHT, new ReplaySamplingBasis(1, 11, "one short"), fiscalYear());

            assertThat(twelve.eligible()).extracting(ClosedPeriod::periodId).contains(202704);
            assertThat(eleven.eligible()).extracting(ClosedPeriod::periodId)
                .doesNotContain(202704).hasSize(11);
        }

        @Test
        @DisplayName("a period that has not ended by tonight is not eligible")
        void aPeriodThatHasNotEndedYet() {
            // A period cannot be replayed before the facts it reports have happened. The fixture
            // is legitimate — a period ending 30 April 2028 that a data load has already created —
            // and a night of 10 April 2028 must not select it.
            List<ClosedPeriod> withFuture = new ArrayList<>(fiscalYear());
            withFuture.add(ClosedPeriod.month(
                YearMonth.of(2028, 4), LocalDate.of(2028, 5, 5), "financial.controller"));

            ReplaySample sample = ReplaySample.forNight(
                NIGHT, ReplaySamplingBasis.nightlyDefault(), withFuture);

            assertThat(sample.population()).hasSize(13);
            assertThat(sample.eligible())
                .extracting(ClosedPeriod::periodId)
                .doesNotContain(202804)
                .hasSize(12);
        }

        @Test
        @DisplayName("a population holding one period twice is refused")
        void duplicatePopulationRefused() {
            List<ClosedPeriod> doubled = new ArrayList<>(fiscalYear());
            doubled.add(ClosedPeriod.month(
                YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "financial.controller"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplaySample.forNight(
                    NIGHT, ReplaySamplingBasis.nightlyDefault(), doubled))
                .withMessageContaining("appears twice");
        }
    }

    @Nested
    @DisplayName("a sample that never selects a period is a control that never runs")
    class Inertness {

        @Test
        @DisplayName("an empty population is a fact, not a defect")
        void nothingClosedYetIsNotADefect() {
            // The real state of a book before its first close — April 2027, under ACPIR 20.
            // Reporting it as a breach would make C-12 red by design, which InvariantId.TM_1
            // spends a paragraph on: a control that is red by design gets suppressed, and then it
            // is not there for the night it matters.
            ReplaySample sample = ReplaySample.forNight(
                LocalDate.of(2027, 4, 15), ReplaySamplingBasis.nightlyDefault(), List.of());

            assertThat(sample.outcome()).isEqualTo(SampleOutcome.NOTHING_CLOSED_YET);
            assertThat(sample.isInert()).isFalse();
            assertThat(sample.outcome().isDefect()).isFalse();
            assertThat(sample.inertnessWarning()).isEmpty();
        }

        @Test
        @DisplayName("zero periods a night against a full book is inert, and says which cause")
        void zeroPeriodsPerNightIsInert() {
            // The number dialled to zero during a release freeze and never dialled back. The job
            // runs, logs a clean night, alerts nobody, and a workpaper recording "C-12 performed,
            // no exceptions" is literally true and completely misleading.
            //
            // Note what is NOT guarded: ReplaySamplingBasis accepts the zero. Refusing it at
            // construction would move the condition into a configuration file where nothing checks
            // it, which is how a control stops running without failing.
            ReplaySample sample = ReplaySample.forNight(
                NIGHT, new ReplaySamplingBasis(0, 12, "paused for the release freeze"),
                fiscalYear());

            assertThat(sample.eligible())
                .as("twelve periods sitting right there")
                .hasSize(12);
            assertThat(sample.outcome()).isEqualTo(SampleOutcome.CONTROL_INERT);
            assertThat(sample.isInert()).isTrue();
            assertThat(sample.controlRan()).isFalse();
            assertThat(sample.outcome().isDefect()).isTrue();
            assertThat(sample.inertnessWarning())
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the two causes read differently to whoever has to fix it, so it names this one")
                .contains("replays 0 periods a night")
                .contains("12 closed period(s) are on file");
            assertThat(sample.nightsToCoverEligible())
                .as("no coverage claim to make")
                .isZero();
        }

        @Test
        @DisplayName("a stale lookback that excludes the whole book is inert, and says so")
        void staleLookbackIsInert() {
            // The subtler cause, and the one nothing else would notice. A window of six periods
            // against a book whose most recent close is nine periods old excludes everything. The
            // number was right when it was chosen. Derived by hand: tonight 2028-04 is ordinal
            // 24339, September 2027 is 24332, a gap of seven, and six does not admit it.
            List<ClosedPeriod> firstHalf = fiscalYear().subList(0, 6);
            ReplaySample sample = ReplaySample.forNight(
                NIGHT, new ReplaySamplingBasis(1, 6, "last two quarters"), firstHalf);

            assertThat(sample.population()).hasSize(6);
            assertThat(sample.eligible()).isEmpty();
            assertThat(sample.outcome()).isEqualTo(SampleOutcome.CONTROL_INERT);
            assertThat(sample.inertnessWarning())
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("lookback of 6 period(s)")
                .contains("has gone stale");
            assertThat(sample.describe())
                .as("the warning is in the audit sentence, not only behind a method call")
                .contains("CONTROL_INERT");
        }

        @Test
        @DisplayName("the basis refuses a negative, which describes nothing, and accepts a zero")
        void negativesRefusedZeroAccepted() {
            // The asymmetry is the point. "Minus two periods back" describes nothing a sampler
            // could do; zero describes something real and adverse that the sample must be able to
            // report.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplaySamplingBasis(-1, 12, "nonsense"))
                .withMessageContaining("zero is permitted");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplaySamplingBasis(1, -1, "nonsense"));
            assertThat(new ReplaySamplingBasis(0, 0, "paused").selectsAnything()).isFalse();
        }

        @Test
        @DisplayName("a basis with no stated rationale is refused")
        void rationaleRequired() {
            // An audit sample with no stated basis is an anecdote. C-12's evidence is the basis as
            // much as the outcome.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplaySamplingBasis(1, 12, "   "))
                .withMessageContaining("an anecdote");
        }

        @Test
        @DisplayName("the programme default is one a night over a fiscal year")
        void theProgrammeDefault() {
            ReplaySamplingBasis basis = ReplaySamplingBasis.nightlyDefault();
            assertThat(basis.periodsPerNight()).isEqualTo(1);
            assertThat(basis.lookbackPeriods()).isEqualTo(12);
            assertThat(basis.selectsAnything()).isTrue();
            assertThat(basis.describe()).contains("C-12 nightly");
        }
    }
}
