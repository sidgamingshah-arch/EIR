package com.crisil.eir.application.transition;

import static com.crisil.eir.application.transition.TransitionCoverage.ACPIR_21_DEADLINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.transition.TransitionCoverage.ObligationCoverage;
import com.crisil.eir.policy.transition.LegacyCohort;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Coverage against the ACPIR 21 and ACPIR 50 deadlines, and the merge that must not be possible.
 *
 * <p><b>The defect under test.</b> 04 § 6: "ACPIR 21 and ACPIR 50 are two obligations with a common
 * deadline, not one ... Tracking them in one field would hide a gap." The gap is the expected
 * sequence — a loan under the EIR regime for interest recognition whose ECL is still discounted at
 * the interim contractual rate — so a book in the ordinary mid-migration state has one obligation at
 * 100% and the other at 0%. One blended figure over it reads as half done and describes neither
 * obligation, and the deadline actually at risk is the one the number conceals.
 *
 * <p>Every figure below is counted by hand off the population each test constructs.
 */
class TransitionCoverageTest {

    private static final LocalDate MID_MIGRATION = LocalDate.of(2029, 3, 31);

    /**
     * Three tracked contracts, all under the EIR regime, one with its ECL migrated.
     *
     * <p>Counted by hand: ACPIR 21 is 3 satisfying and 0 outstanding; ACPIR 50 is 1 satisfying and
     * 2 outstanding, both of those two being under the concession. A merged figure would be 4 of 6.
     */
    private static TransitionCoverage midMigration() {
        return new TransitionCoverage(MID_MIGRATION, 3L, 3L, 0L,
            new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                ACPIR_21_DEADLINE, 3L, 3L, 0L),
            new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                LegacyCohort.ACPIR_50_DEADLINE, 3L, 1L, 2L),
            2L, 0L, 2L, 1);
    }

    @Nested
    @DisplayName("the two obligations are reported separately")
    class TheTwoObligations {

        @Test
        @DisplayName("100% on ACPIR 21 and 33% on ACPIR 50 are two figures, and neither moves the"
            + " other")
        void oneObligationsCompletionDoesNotMaskTheOthers() {
            TransitionCoverage coverage = midMigration();

            assertThat(coverage.acpir21().contractsSatisfying()).isEqualTo(3);
            assertThat(coverage.acpir21().contractsOutstanding()).isZero();
            assertThat(coverage.acpir50().contractsSatisfying())
                .as("the same three contracts, and only one has moved its ECL discounting to the"
                    + " EIR; ACPIR 21 being complete says nothing at all about this figure")
                .isEqualTo(1);
            assertThat(coverage.acpir50().contractsOutstanding()).isEqualTo(2);
            assertThat(coverage.obligations())
                .as("two, in the order 04 § 6 states them, and no third combining them")
                .hasSize(2);
            assertThat(coverage.obligations())
                .extracting(ObligationCoverage::obligation)
                .containsExactly("ACPIR 21", "ACPIR 50");
        }

        @Test
        @DisplayName("both obligations, both requirements and both deadlines appear in the report")
        void theReportNamesBothAndBlendsNeither() {
            String report = midMigration().describe();

            assertThat(report)
                .as("each figure travels with the name of the obligation it measures and the"
                    + " sentence of what satisfying it means; two bare percentages side by side are"
                    + " indistinguishable, which is how one gets read for the other")
                .contains("ACPIR 21")
                .contains("ACPIR 50")
                .contains("interest is recognised on the EIR")
                .contains("the ECL is discounted at the EIR")
                .contains("3 of 3")
                .contains("1 of 3");
        }

        @Test
        @DisplayName("the concession population is published beside the ACPIR 50 outstanding count,"
            + " not instead of it")
        void theConcessionPopulationIsItsOwnFigure() {
            TransitionCoverage coverage = midMigration();

            assertThat(coverage.acpir50().contractsOutstanding())
                .as("every tracked contract whose ECL is not yet at the EIR")
                .isEqualTo(2);
            assertThat(coverage.underAcpir50Concession())
                .as("of those, the ones already under the EIR regime for interest — the shape ACPIR"
                    + " 50's concession permits. A book that has migrated nothing at all and a book"
                    + " mid-concession have the same outstanding count and are not in the same"
                    + " position, so both figures are published")
                .isEqualTo(2);
            assertThat(coverage.eclAheadOfInterest())
                .as("nil here; a phased cutover can produce it and it is a sequencing signal rather"
                    + " than a breach")
                .isZero();
        }
    }

    @Nested
    @DisplayName("the two deadlines are two dates")
    class TheTwoDeadlines {

        @Test
        @DisplayName("they coincide today, at 31 March 2030")
        void bothDeadlinesAreCurrently31March2030() {
            assertThat(ACPIR_21_DEADLINE)
                .as("the reference's timeline: 'By 31 Mar 2030 — full legacy book on EIR. Interim"
                    + " contractual-rate ECL discounting fully migrated. Hard deadline under ACPIR"
                    + " 21 and 50.'")
                .isEqualTo(LocalDate.of(2030, 3, 31))
                .isEqualTo(LegacyCohort.ACPIR_50_DEADLINE);
        }

        /**
         * The case the separation exists for. The reference register records the interaction as
         * unresolved — "whether these are one obligation or two with a common deadline is not
         * explicit; treat as two" — so this test constructs the divergent case directly and asserts
         * the report survives it. A type that derived one deadline from the other, or required the
         * two to be equal, would make a clarification from RBI a change that silently moved both.
         */
        @Test
        @DisplayName("coverage with two different deadlines is accepted and each obligation is"
            + " judged against its own")
        void divergentDeadlinesAreReportedSeparately() {
            LocalDate laterAcpir50Deadline = LocalDate.of(2031, 3, 31);
            LocalDate afterTheFirstDeadlineOnly = LocalDate.of(2030, 6, 30);

            TransitionCoverage coverage = new TransitionCoverage(
                afterTheFirstDeadlineOnly, 3L, 3L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 3L, 2L, 1L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    laterAcpir50Deadline, 3L, 1L, 2L),
                1L, 0L, 3L, 1);

            assertThat(coverage.acpir21().deadline()).isEqualTo(ACPIR_21_DEADLINE);
            assertThat(coverage.acpir50().deadline()).isEqualTo(laterAcpir50Deadline);
            assertThat(coverage.acpir21().isInBreach(afterTheFirstDeadlineOnly))
                .as("one contract outstanding and 31 March 2030 has passed: in breach")
                .isTrue();
            assertThat(coverage.acpir50().isInBreach(afterTheFirstDeadlineOnly))
                .as("two contracts outstanding against a deadline still a year away: not in breach."
                    + " Merged into one figure, the first obligation's breach and the second's"
                    + " remaining time would net into a single status that is wrong about both")
                .isFalse();
            assertThat(coverage.describe())
                .contains("2030-03-31")
                .contains("2031-03-31")
                .contains("IN BREACH");
        }

        @Test
        @DisplayName("an obligation outstanding before its deadline is progress, not a breach")
        void outstandingBeforeTheDeadlineIsNotABreach() {
            ObligationCoverage acpir50 = midMigration().acpir50();

            assertThat(acpir50.contractsOutstanding()).isEqualTo(2);
            assertThat(acpir50.isInBreach(MID_MIGRATION))
                .as("ACPIR 50 concedes the interim contractual rate until the deadline; a report red"
                    + " from 2027 is one that gets suppressed, and then it is not there for the year"
                    + " it matters — the reasoning TM-1 is formulated on")
                .isFalse();
            assertThat(acpir50.isPastDeadline(LegacyCohort.ACPIR_50_DEADLINE))
                .as("strictly after, as LegacyCohort draws the same boundary: an exposure on the"
                    + " deadline itself has met it")
                .isFalse();
            assertThat(acpir50.isInBreach(LocalDate.of(2030, 4, 1)))
                .as("one day later, the identical book is in breach")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("a coverage figure the exercise did not measure is refused")
    class TheArithmeticThatIsEnforced {

        /**
         * The failing input, named: a report claiming nil untracked contracts over a population of
         * ten with three recorded positions. That is the aggregation-over-a-partial-population
         * defect exactly — seven contracts absent from both sides of every total, and a coverage
         * report that reads as complete.
         */
        @Test
        @DisplayName("an untracked count that is not population less tracked is refused")
        void anUntrackedCountMustFollowFromTheOtherTwo() {
            assertThatThrownBy(() -> new TransitionCoverage(MID_MIGRATION, 10L, 3L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 3L, 3L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 3L, 1L, 2L),
                2L, 0L, 2L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claiming a coverage it did not measure");
        }

        /**
         * The failing input, named: an obligation classifying two of three tracked contracts. The
         * third has a recorded position and appears in neither the progress figure nor the
         * remaining-work figure, so it is a contract the report silently dropped.
         */
        @Test
        @DisplayName("an obligation whose two columns do not exhaust the tracked population is"
            + " refused")
        void everyRecordedPositionIsClassified() {
            assertThatThrownBy(() -> new ObligationCoverage("ACPIR 50",
                "the ECL is discounted at the EIR", LegacyCohort.ACPIR_50_DEADLINE, 3L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("silently dropped");
        }

        /**
         * The failing input, named: 8 tracked contracts against a population of 5, with the
         * untracked figure supplied as the −3 that difference actually is. Found in review: the
         * non-negativity guard omitted {@code untrackedContracts}, the difference check was
         * satisfied by the negative, and {@code describe()}'s {@code untrackedContracts > 0} branch
         * then suppressed the UNTRACKED warning — so the report read "8 of 5 contracts carry a
         * recorded ECL discount basis" with no warning at all.
         */
        @Test
        @DisplayName("more tracked contracts than the population is refused, not printed as 8 of 5")
        void trackedCannotExceedThePopulation() {
            assertThatThrownBy(() -> new TransitionCoverage(MID_MIGRATION, 5L, 8L, -3L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 8L, 8L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 8L, 8L, 0L),
                0L, 0L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be non-negative");
        }

        /**
         * The failing input, named: seven contracts reported under the ACPIR 50 concession while
         * ACPIR 50 reports nil outstanding. The concession population is
         * {@code satisfies 21 && !satisfies 50}, so it is part of ACPIR 50's outstanding population
         * and cannot exceed it — a decomposition of a figure into more than the figure.
         */
        @Test
        @DisplayName("a sub-population larger than the obligation it decomposes is refused")
        void theConcessionPopulationCannotExceedTheObligationItIsPartOf() {
            assertThatThrownBy(() -> new TransitionCoverage(MID_MIGRATION, 10L, 10L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 10L, 10L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 10L, 10L, 0L),
                7L, 0L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed it");
        }

        @Test
        @DisplayName("an ECL-ahead-of-interest count larger than ACPIR 21's outstanding is refused")
        void theReverseGapCannotExceedAcpir21sOutstanding() {
            assertThatThrownBy(() -> new TransitionCoverage(MID_MIGRATION, 10L, 10L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 10L, 10L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 10L, 10L, 0L),
                0L, 4L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("part of that obligation's outstanding population");
        }

        @Test
        @DisplayName("two obligations counted over different populations are refused")
        void bothObligationsAreMeasuredOverTheSamePositions() {
            assertThatThrownBy(() -> new TransitionCoverage(MID_MIGRATION, 3L, 3L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 3L, 3L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 2L, 1L, 1L),
                2L, 0L, 2L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counted over a different population");
        }

        @Test
        @DisplayName("the untracked count is printed before any completion figure")
        void theUntrackedCountLeadsTheReport() {
            TransitionCoverage partial = new TransitionCoverage(MID_MIGRATION, 10L, 3L, 7L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 3L, 3L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 3L, 3L, 0L),
                0L, 0L, 0L, 0);

            assertThat(partial.fullyTracked())
                .as("both obligations read 3 of 3 and seven contracts were never presented")
                .isFalse();
            List<String> lines = List.of(partial.describe().split("\n"));
            assertThat(lines.getFirst())
                .as("a report opening with a completion figure invites the reader to stop there,"
                    + " and the completion figure is only as good as the population behind it")
                .contains("7")
                .contains("UNTRACKED");
        }

        @Test
        @DisplayName("an empty population is not fully tracked")
        void anEmptyPopulationIsNotCoverage() {
            TransitionCoverage empty = new TransitionCoverage(MID_MIGRATION, 0L, 0L, 0L,
                new ObligationCoverage("ACPIR 21", "interest is recognised on the EIR",
                    ACPIR_21_DEADLINE, 0L, 0L, 0L),
                new ObligationCoverage("ACPIR 50", "the ECL is discounted at the EIR",
                    LegacyCohort.ACPIR_50_DEADLINE, 0L, 0L, 0L),
                0L, 0L, 0L, 0);

            assertThat(empty.fullyTracked())
                .as("nil untracked out of nil tracked is not complete coverage, it is no coverage;"
                    + " every count here is nil and nothing was measured")
                .isFalse();
        }
    }
}
