package com.crisil.eir.policy.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ACPIR 21 and ACPIR 50 as two obligations with one deadline (04 § 6, invariant TM-1).
 *
 * <p>The state that has to be representable and countable: a contract recognising interest on the
 * EIR while discounting its ECL at the contractual rate. That is not a defect before 31 March 2030
 * — it is the shape ACPIR 50's concession describes — and it is invisible to anything reading a
 * single migration flag, which is why 04 § 6 gives the discount basis its own table.
 */
class MigrationTrackerTest {

    private static final LocalDate DURING_CONCESSION = LocalDate.of(2028, 9, 30);
    private static final LocalDate AFTER_DEADLINE = LocalDate.of(2030, 4, 1);
    private static final LocalDate MIGRATED_ON = LocalDate.of(2028, 6, 30);
    private static final Rate EIR = Rate.monthly(new BigDecimal("0.0104214918"));

    private static ContractMigrationState interim(String id) {
        return ContractMigrationState.onInterimBasis(id, 202809, true);
    }

    private static ContractMigrationState notYetOnEir(String id) {
        return ContractMigrationState.onInterimBasis(id, 202809, false);
    }

    private static ContractMigrationState migrated(String id) {
        return ContractMigrationState.migrated(
            id, 202809, "SOLVE-" + id, EIR, MIGRATED_ON, "WP-2028-MIG-04");
    }

    @Nested
    @DisplayName("the two obligations are separate positions")
    class TwoObligations {

        @Test
        @DisplayName("on the EIR for interest, on the contractual rate for ECL: the concession")
        void theConcessionShape() {
            ContractMigrationState state = interim("C1");
            assertThat(state.satisfiesAcpir21()).isTrue();
            assertThat(state.satisfiesAcpir50()).isFalse();
            assertThat(state.isUnderTheAcpir50Concession())
                .as("the expected sequence, not a defect")
                .isTrue();
            assertThat(state.describe())
                .contains("interest on the EIR (ACPIR 21 met)")
                .contains("ACPIR 50 deferred under the interim concession");
        }

        @Test
        @DisplayName("a single flag would have called this migrated")
        void oneFlagWouldHideIt() {
            // The argument 04 section 6 makes for a separate table, asserted. One boolean called
            // "migrated" is true for this contract on the ACPIR 21 reading and false on the
            // ACPIR 50 reading, and whichever the flag meant, the other obligation disappears.
            ContractMigrationState state = interim("C1");
            assertThat(state.satisfiesAcpir21()).isNotEqualTo(state.satisfiesAcpir50());
        }

        @Test
        @DisplayName("ECL ahead of interest is odd, possible, and surfaced rather than refused")
        void reverseGap() {
            // An EIR has to exist before the ECL model can use it, so a contract can be
            // reconstructed for discounting before recognition is switched over. Refusing it would
            // make a legitimate cutover order unrepresentable.
            ContractMigrationState reversed = new ContractMigrationState(
                "C9", 202809, false, EclDiscountBasis.EIR, "SOLVE-C9", EIR,
                MIGRATED_ON, null);
            assertThat(reversed.eclIsAheadOfInterest()).isTrue();

            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), reversed), 2);
            assertThat(tracker.eclAheadOfInterest())
                .extracting(ContractMigrationState::contractId)
                .containsExactly("C9");
            assertThat(tracker.migrationTracked(DURING_CONCESSION).satisfied())
                .as("a sequencing signal, not a breach")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("TM-1 asserts tracking, not completion")
    class Tracking {

        @Test
        @DisplayName("an unfinished migration during the concession passes, and reports the size")
        void unfinishedIsNotABreach() {
            // The design decision. "Fail while any contract is on the interim basis" would fail
            // continuously from 2027 to 2030, and a breach blocks the close — so it would block
            // every close for three years while describing a state ACPIR 50 permits. A control
            // red by design gets suppressed, and then it is absent for the year it matters.
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), interim("C2"), interim("C3")), 3);

            InvariantResult result = tracker.migrationTracked(DURING_CONCESSION);
            assertThat(result.id()).isEqualTo(InvariantId.TM_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("2 still to migrate under the ACPIR 50 concession");
            assertThat(tracker.outstandingAcpir50Migrations())
                .as("published as data, because a shrinking number is what a programme tracks")
                .isEqualTo(2L);
        }

        @Test
        @DisplayName("the same population after the deadline is a breach, counted")
        void pastTheDeadlineIsABreach() {
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), interim("C2"), interim("C3")), 3);

            InvariantResult result = tracker.migrationTracked(AFTER_DEADLINE);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(result.detail())
                .contains("remain on the interim contractual basis after the 31 March 2030")
                .contains("[C2, C3]");
        }

        @Test
        @DisplayName("31 March 2030 itself is still inside the concession")
        void boundaryIsStrictlyAfter() {
            // Same boundary as the cohort deadline, and drawn from the same constant so the two
            // cannot drift apart.
            MigrationTracker tracker = MigrationTracker.over(List.of(interim("C2")), 1);
            assertThat(tracker.migrationTracked(LocalDate.of(2030, 3, 31)).satisfied()).isTrue();
            assertThat(tracker.migrationTracked(LocalDate.of(2030, 4, 1)).satisfied()).isFalse();
            assertThat(LegacyCohort.ACPIR_50_DEADLINE).isEqualTo(LocalDate.of(2030, 3, 31));
        }

        @Test
        @DisplayName("an untracked contract breaches even during the concession")
        void untrackedIsTheWorseFailure() {
            // A contract with no recorded basis is not one that has not migrated — it is one
            // whose position nobody knows. A population reporting zero outstanding migrations
            // because half of it was never presented is exactly the failure the separate table
            // exists to prevent.
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), migrated("C2")), 10);

            assertThat(tracker.outstandingAcpir50Migrations())
                .as("nothing outstanding among the contracts that were presented")
                .isZero();
            InvariantResult result = tracker.migrationTracked(DURING_CONCESSION);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("eight contracts nobody recorded a basis for")
                .isEqualByComparingTo(BigDecimal.valueOf(8));
            assertThat(result.detail())
                .contains("position is unknown rather than outstanding");
        }

        @Test
        @DisplayName("both failure kinds are counted together and named separately")
        void bothKindsAtOnce() {
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), interim("C2")), 5);

            InvariantResult result = tracker.migrationTracked(AFTER_DEADLINE);
            assertThat(result.deviation())
                .as("three untracked plus one past the deadline")
                .isEqualByComparingTo(BigDecimal.valueOf(4));
            assertThat(result.detail())
                .contains("3 contracts have no recorded ECL discount basis")
                .contains("1 contracts remain on the interim contractual basis");
        }

        @Test
        @DisplayName("a fully migrated population passes after the deadline too")
        void complete() {
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), migrated("C2")), 2);
            InvariantResult result = tracker.migrationTracked(AFTER_DEADLINE);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .contains("none remains on the interim basis after the deadline");
        }

        @Test
        @DisplayName("ACPIR 21 is counted on its own too")
        void acpir21Separately() {
            MigrationTracker tracker = MigrationTracker.over(
                List.of(migrated("C1"), interim("C2"), notYetOnEir("C3")), 3);
            assertThat(tracker.outstandingAcpir21Migrations()).isEqualTo(1L);
            assertThat(tracker.outstandingAcpir50Migrations())
                .as("C2 only: C3 is not under the concession because it has not met ACPIR 21")
                .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("claims that cannot be verified later are refused")
    class Refusals {

        @Test
        @DisplayName("migrated to the EIR with no EIR to point at is a claim, not a fact")
        void eirNeedsAComputation() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractMigrationState("C1", 202809, true,
                    EclDiscountBasis.EIR, null, EIR, MIGRATED_ON, null))
                .withMessageContaining("with no EIR to point at is a claim rather than a fact");
        }

        @Test
        @DisplayName("migrated with no date cannot be placed against the deadline")
        void eirNeedsADate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractMigrationState("C1", 202809, true,
                    EclDiscountBasis.EIR, "SOLVE-C1", EIR, null, null))
                .withMessageContaining("whether it was migrated before the ACPIR 50 deadline");
        }

        @Test
        @DisplayName("more recorded states than contracts would make the untracked count negative")
        void populationSmallerThanStates() {
            // Which would read as complete coverage, so the figures are refused rather than
            // clamped.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> MigrationTracker.over(
                    List.of(migrated("C1"), migrated("C2")), 1))
                .withMessageContaining("one of the two figures is wrong");
        }

        @Test
        @DisplayName("two states for one contract is not a position")
        void duplicateContract() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> MigrationTracker.over(
                    List.of(interim("C1"), migrated("C1")), 5))
                .withMessageContaining("two answers for one contract is not a position");
        }
    }
}
