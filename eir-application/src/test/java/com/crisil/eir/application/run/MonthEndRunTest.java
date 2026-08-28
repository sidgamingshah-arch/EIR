package com.crisil.eir.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionQueue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The population loop: FR-905's barrier, and the accounting that makes it honest.
 *
 * <p>The population here is three contracts with the malformed one <b>in the middle</b>, and that
 * position is the test. FR-905's guarantee is that the contracts <em>after</em> a failing contract
 * are computed; a barrier that aborted at the first failure would leave C-0003 absent and every
 * total would still tie, because a contract missing from both sides of a reconciliation is missing
 * from neither.
 */
class MonthEndRunTest {

    private static final String GOOD_ONE = "C-0001";
    private static final String MALFORMED = "C-0002";
    private static final String GOOD_TWO = "C-0003";

    private static RunFixtures.Harness threeContractsOneMalformed() {
        // C-0002 has period movements but no opening state as at the boundary — the data condition
        // ContractStateSource returns as empty rather than throwing, precisely so that it is a
        // per-contract report and not a reason to abandon a ten-million-contract close.
        Map<String, com.crisil.eir.application.port.ContractStateSource.OpeningState> states =
            new LinkedHashMap<>();
        states.put(GOOD_ONE, RunFixtures.performingState());
        states.put(GOOD_TWO, RunFixtures.performingState());

        Map<String, ContractPeriod> periods = new LinkedHashMap<>();
        periods.put(GOOD_ONE, RunFixtures.performingPeriod(GOOD_ONE));
        periods.put(MALFORMED, RunFixtures.performingPeriod(MALFORMED));
        periods.put(GOOD_TWO, RunFixtures.performingPeriod(GOOD_TWO));

        return RunFixtures.harness(
            List.of(GOOD_ONE, MALFORMED, GOOD_TWO), states, periods,
            RunFixtures.EXPLODING_SOLVER);
    }

    @Nested
    @DisplayName("every contract in the population is either computed or quarantined")
    class Accounting {

        @Test
        @DisplayName("one malformed contract in the middle leaves two results and one queue entry")
        void theCountAddsUp() {
            RunFixtures.Harness harness = threeContractsOneMalformed();
            ExceptionQueue queue = new ExceptionQueue();

            MonthEndRun.Completion completion = harness.run().execute(queue);
            RunAggregate aggregate = completion.aggregate();

            assertThat(aggregate.populationSize()).isEqualTo(3);
            assertThat(aggregate.results()).hasSize(3);
            assertThat(aggregate.computedCount()).isEqualTo(2);
            assertThat(aggregate.quarantinedCount()).isEqualTo(1);
            assertThat(aggregate.unaccountedFor())
                .as("nobody is absent from both sides of every total")
                .isZero();
            assertThat(aggregate.quarantinedContracts()).containsExactly(MALFORMED);
            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.isQuarantined(MALFORMED)).isTrue();
        }

        @Test
        @DisplayName("the contract AFTER the failing one is computed — this is the whole of FR-905")
        void theRunSurvivesTheFailure() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            // In population order, so the third result is the contract that follows the failure.
            assertThat(aggregate.results()).extracting(ContractResult::contractId)
                .containsExactly(GOOD_ONE, MALFORMED, GOOD_TWO);
            ContractResult after = aggregate.results().get(2);
            assertThat(after.isComputed()).isTrue();
            assertThat(after.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("486840.64"));
        }

        @Test
        @DisplayName("the quarantined contract is a RESULT carrying its exception, not an absence")
        void quarantineIsAResult() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();
            ContractResult isolated = aggregate.results().get(1);

            assertThat(isolated.contractId()).isEqualTo(MALFORMED);
            assertThat(isolated.isComputed()).isFalse();
            assertThat(isolated.journal()).isNull();
            assertThat(isolated.closingGca()).isNull();
            assertThat(isolated.exception()).isNotNull();
            assertThat(isolated.exception().raisedByRunId()).isEqualTo(RunFixtures.RUN_ID);
            assertThat(isolated.exception().category().stopsTheContract()).isTrue();
            assertThat(isolated.exception().detail()).contains("has no state as at");
            // A contract that could not be computed has nothing to assert about, so no invariant
            // pass rides along into the run-level aggregation wearing the look of coverage.
            assertThat(isolated.invariants()).isEmpty();
        }

        @Test
        @DisplayName("two journals, one per computed contract")
        void journalsTieToComputedContracts() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            assertThat(aggregate.journals()).hasSize(aggregate.computedCount());
            // 2 x 486,840.64255243997600 — the sub-ledger side of SL-1, which the close gate reads.
            assertThat(aggregate.totalClosingGca()).isPresent();
            assertThat(aggregate.totalClosingGca().orElseThrow().atPresentationScale())
                .isEqualTo(Money.inr("973681.29"));
        }

        @Test
        @DisplayName("a duplicate contract id is refused before anything is computed or filed")
        void duplicatesRefused() {
            Map<String, com.crisil.eir.application.port.ContractStateSource.OpeningState> states =
                Map.of(GOOD_ONE, RunFixtures.performingState());
            Map<String, ContractPeriod> periods =
                Map.of(GOOD_ONE, RunFixtures.performingPeriod(GOOD_ONE));
            RunFixtures.Harness harness = RunFixtures.harness(
                List.of(GOOD_ONE, GOOD_ONE), states, periods, RunFixtures.EXPLODING_SOLVER);
            ExceptionQueue queue = new ExceptionQueue();

            // A duplicate would overwrite one contract's figures with another's and the loss would
            // be invisible in the output; the barrier checks in a pass of its own, so the queue is
            // left as it was.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> harness.run().execute(queue))
                .withMessageContaining("appears twice");
            assertThat(queue.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("the run reports what it asserted, and refuses to call it a clean close")
    class Reporting {

        @Test
        @DisplayName("aggregated invariants are one per id, over the contracts that asserted them")
        void oneResultPerId() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            assertThat(aggregate.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.SL_2, InvariantId.ST_2);
            for (InvariantResult result : aggregate.invariants()) {
                assertThat(result.satisfied()).as("%s", result.id()).isTrue();
                assertThat(result.detail())
                    .as("the count is the coverage, and it is the quarantined contract short")
                    .contains("2 contract(s) asserted");
            }
        }

        @Test
        @DisplayName("a quarantined contract blocks the close even though every figure ties")
        void quarantineBlocksTheClose() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            assertThat(aggregate.breaches()).isEmpty();
            assertThat(aggregate.reportsCleanClose())
                .as("every invariant passed and the run is still not clean: 2 of 3 were processed")
                .isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("1 contract(s) were quarantined").contains(MALFORMED));
            assertThat(aggregate.describe()).contains("NOT a clean close");
        }

        @Test
        @DisplayName("no solve happened anywhere in the run")
        void theRunPerformedNoSolve() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            MonthEndRun.Completion completion = harness.run().execute(new ExceptionQueue());

            // The solver in this fixture throws on any call, so a passing run cannot have solved;
            // the counts are asserted as well, because that is the form a production run has to
            // report when no landmine is wired in. 05 § 3.2: the steady-state run is overwhelmingly
            // roll-forward arithmetic, which is what makes the 10M-contract target reachable.
            assertThat(harness.audit().solveCount()).isZero();
            assertThat(completion.solveCount()).isZero();
            assertThat(completion.computations()).hasSize(2);
            assertThat(completion.computations().values())
                .allSatisfy(computation -> assertThat(computation.solves()).isZero());
        }

        @Test
        @DisplayName("the policy versions the run resolved are stamped on the aggregate")
        void policyVersionsAreOnTheRecord() {
            RunFixtures.Harness harness = threeContractsOneMalformed();

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            // 04 § 2.13 and FR-903: the run's figures and the policy that produced them are one
            // record. Carried as data, not as an invariant — PV-1 is a claim about every date in a
            // closed period and already has a publication site in the close gate.
            assertThat(aggregate.policyVersionIds()).containsExactly("ROUTING_TABLE=POL-RT-2028.1");
        }
    }

    @Nested
    @DisplayName("a run that processed no contracts")
    class EmptyPopulation {

        @Test
        @DisplayName("reports no breaches and is emphatically not a clean close")
        void emptyPopulationIsNotClean() {
            RunFixtures.Harness harness = RunFixtures.harness(
                List.of(), Map.of(), Map.of(), RunFixtures.EXPLODING_SOLVER);

            RunAggregate aggregate = harness.run().execute(new ExceptionQueue()).aggregate();

            // This is the trap. One result per invariant over zero contracts is an empty list, and
            // "every result is satisfied" over an empty list is true — so every control ties,
            // nothing is red, and the run processed nothing.
            assertThat(aggregate.invariants()).isEmpty();
            assertThat(aggregate.breaches()).isEmpty();
            assertThat(aggregate.totalClosingGca()).isEmpty();
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons().getFirst())
                .contains("accounted for no contracts at all");
        }
    }
}
