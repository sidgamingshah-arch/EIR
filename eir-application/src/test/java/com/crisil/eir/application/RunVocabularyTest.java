package com.crisil.eir.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The run vocabulary every other package in this module depends on.
 *
 * <p><b>Why this class exists.</b> {@code ContractResult}, {@code RunRequest} and the six ports
 * shipped with no tests of their own: their behaviour was exercised incidentally, through the three
 * units built on top of them. Both defects the adversarial review found here were in the two
 * guarantees nothing asserted — the smallest unit in the module, and the one every other unit
 * trusts.
 */
class RunVocabularyTest {

    private static final int PERIOD = 202805;
    private static final String RUN = "RUN-202805-01";
    private static final LocalDate POSTED = LocalDate.of(2028, 5, 31);

    private static JournalEntry balancedEntry(String contractId) {
        return new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("5506.79"), "accrual"),
            JournalLine.credit("4101-INTEREST-INCOME", Money.inr("5506.79"), "income")));
    }

    @Nested
    @DisplayName("a computed contract carries the balance every total is built from")
    class ComputedCarriesItsBalance {

        @Test
        @DisplayName("a computed contract with no closing balance is refused")
        void aComputedContractNeedsAClosingBalance() {
            // The shortfall trap one level below the population check written to catch it. Without
            // this guard the contract counts as computed, so no population refusal fires; its
            // journal balances, so SL-2 passes; and it is absent from the sub-ledger side of SL-1,
            // which then ties over a book short by exactly its balance. A contract absent from one
            // side of a reconciliation and present on the other is the one shape every total in
            // this engine agrees about.
            assertThatNullPointerException()
                .isThrownBy(() -> ContractResult.computed(
                    "C-1", null, balancedEntry("C-1"), List.of()))
                .withMessageContaining("was computed and carries no closing gross carrying amount")
                .withMessageContaining("would then tie without it");
        }

        @Test
        @DisplayName("an isolated contract carries no balance, and that is the only null case")
        void anIsolatedContractCarriesNoBalance() {
            ContractResult isolated = ContractResult.isolated("C-2", ExceptionRecord.raise(
                "C-2", RUN, ExceptionCategory.MISSING_MANDATORY_FIELD, "tenor arrived empty",
                "payload/C-2"));

            assertThat(isolated.closingGca()).isNull();
            assertThat(isolated.isComputed()).isFalse();
        }

        @Test
        @DisplayName("exactly one of computed and isolated, and neither is not a state")
        void theXorHolds() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractResult(
                    "C-3", Money.inr("1.00"), null, List.of(), null))
                .withMessageContaining("it is neither");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractResult(
                    "C-3", Money.inr("1.00"), balancedEntry("C-3"), List.of(),
                    ExceptionRecord.raise("C-3", RUN, ExceptionCategory.NO_SOLUTION,
                        "the solver found no root", "payload/C-3")))
                .withMessageContaining("it is both");
        }

        @Test
        @DisplayName("a quarantined contract cannot smuggle an invariant pass into the aggregate")
        void anIsolatedContractAssertsNothing() {
            // A pass from a contract that was never computed reads as coverage in the run-level
            // conjunction, which is the population-denominator defect at the grain of one contract.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContractResult(
                    "C-4", null, null,
                    List.of(InvariantResult.pass(InvariantId.SL_2, "the entry balances")),
                    ExceptionRecord.raise("C-4", RUN, ExceptionCategory.NO_SOLUTION,
                        "the solver found no root", "payload/C-4")))
                .withMessageContaining("has nothing to assert about");
        }
    }

    @Nested
    @DisplayName("a replay boundary is a replay boundary")
    class TheAsAtBoundary {

        private static final LocalDate PERIOD_END = LocalDate.of(2028, 5, 31);
        private static final Instant RECORDED = Instant.parse("2028-06-01T00:00:00Z");

        @Test
        @DisplayName("a blank run id is refused, not silently treated as a live boundary")
        void aBlankRunIdIsRefused() {
            // The compact constructor treats a blank replayOf as absent, so null-checking alone let
            // this factory — whose whole job is to mark a boundary as a replay — return one with
            // isReplay() false. Every port would then answer as at now instead of as at the
            // original run, and DT-1 would fail on version drift the replay itself introduced.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> AsAtBoundary.replaying(PERIOD_END, RECORDED, "   "))
                .withMessageContaining("needs the run id it is replaying")
                .withMessageContaining(RECORDED.toString());
        }

        @Test
        @DisplayName("a replay boundary reports itself as one and keeps the original instant")
        void aReplayBoundaryIsMarked() {
            AsAtBoundary replay = AsAtBoundary.replaying(PERIOD_END, RECORDED, RUN);

            assertThat(replay.isReplay()).isTrue();
            assertThat(replay.replayOf()).isEqualTo(RUN);
            assertThat(replay.recordedAsAt())
                .as("the original run's instant, not the replay's")
                .isEqualTo(RECORDED);
        }

        @Test
        @DisplayName("a live boundary is not a replay, and a surrounding run id is stripped")
        void aLiveBoundaryIsNotAReplay() {
            assertThat(AsAtBoundary.live(PERIOD_END, RECORDED).isReplay()).isFalse();
            assertThat(AsAtBoundary.replaying(PERIOD_END, RECORDED, "  " + RUN + " ").replayOf())
                .as("stripped, because the run id is a join key onto the published run")
                .isEqualTo(RUN);
        }
    }
}
