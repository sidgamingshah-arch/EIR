package com.crisil.eir.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.replay.ShadowRun;
import com.crisil.eir.domain.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The partitioned amortisation run, and the property the whole module exists for: a run killed after
 * N contracts and resumed still accounts for the whole population (ADR-0007, FR-905, 05 § 3.2).
 *
 * <h2>What "accounts for" means here, precisely</h2>
 *
 * <p>{@code computed + quarantined + unaccounted = population}, with {@code unaccounted} nil. The
 * identity itself is true by construction of {@code RunAggregate} — {@code quarantinedCount()} is
 * {@code results.size() − computedCount()} and {@code unaccountedFor()} is
 * {@code populationSize − results.size()} — which is exactly why it is asserted <em>here</em>, over a
 * run that was actually killed and resumed, and not inside {@code AmortisationBatchJob} where it
 * could not fail. The claim under test is not the arithmetic; it is that the three numbers still
 * describe the population the run started with after the run died in the middle of it.
 *
 * <p>The defect being excluded is the one docs/08 and this codebase's whole invariant vocabulary are
 * organised around: a run over 10,000,000 contracts that silently processed 9,999,998 reconciles
 * perfectly, because the two it dropped are absent from both sides of every total. A restart has two
 * ways to produce it — lose the completed partitions' results (aggregate from this execution's memory
 * instead of the store) or lose the in-flight partition's contracts (skip a partition that failed) —
 * and the tests below construct both.
 *
 * <h2>Where the expected figures come from</h2>
 *
 * <p>Reference case 1, month 13, published: opening 528,407.32, EIR interest 5,506.79, instalment
 * 47,073.47, closing <b>486,840.64</b>. Derived by hand in {@link BatchFixtures}, never read back
 * from a run.
 */
@DisplayName("The partitioned amortisation run (ADR-0007)")
class AmortisationBatchJobTest {

    private static final String RUN_ID = "RUN-202805-01";

    @Nested
    @DisplayName("A run over the whole population")
    class WholePopulation {

        @Test
        @DisplayName("partitions on product x entity, shards the skew, and accounts for every"
            + " contract")
        void aPartitionedRunAccountsForEveryContract() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            RunRequest request = BatchFixtures.request(RUN_ID, population, Set.of());

            CompletedRun completed = harness.runner().run(request);

            // 12 + 5 + 8 = 25, counted by hand from the three grains.
            assertThat(population).as("the fixture population").hasSize(25);
            assertThat(completed.aggregate().populationSize())
                .as("the run's own denominator is the population it was given")
                .isEqualTo(25);
            assertThat(completed.aggregate().computedCount()).as("computed").isEqualTo(25);
            assertThat(completed.aggregate().quarantinedCount()).as("quarantined").isZero();
            assertThat(completed.aggregate().unaccountedFor()).as("unaccounted for").isZero();
            assertThat(completed.reportsCleanClose())
                .as("a full population, computed, with no breach: %s", completed.describe())
                .isTrue();

            // Four partitions: RETAIL-EMI/IN-MUM splits at 10 into shard0 (10) and shard1 (2);
            // RETAIL-EMI/IN-DEL is 5 in one shard; CORP-TERM/IN-MUM is 8 in one shard. Counted by
            // hand from the population and a shard size of 10.
            assertThat(harness.store().recordedSlices(RUN_ID))
                .as("one commit per partition")
                .extracting(PartitionKey::name)
                .containsExactlyInAnyOrder(
                    BatchFixtures.partition(BatchFixtures.RETAIL, BatchFixtures.MUM, 0),
                    BatchFixtures.partition(BatchFixtures.RETAIL, BatchFixtures.MUM, 1),
                    BatchFixtures.partition(BatchFixtures.RETAIL, BatchFixtures.DEL, 0),
                    BatchFixtures.partition(BatchFixtures.CORP, BatchFixtures.MUM, 0));
        }

        @Test
        @DisplayName("every computed contract closes at reference case 1's month-13 figure,"
            + " 486,840.64")
        void everyComputedContractClosesAtTheReferenceFigure() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            RunRequest request = BatchFixtures.request(
                RUN_ID, BatchFixtures.standardPopulation(), Set.of());

            CompletedRun completed = harness.runner().run(request);

            // The anchor. 528,407.32 + 5,506.79 − 47,073.47 = 486,840.64, hand-derived from the
            // published reference case and not from any run. If the partitioning moved a figure —
            // by feeding a slice the wrong period, by sharing mutable state between partitions, by
            // reordering flows — this is where it shows.
            for (ContractResult result : completed.aggregate().results()) {
                assertThat(result.isComputed()).as("contract %s computed", result.contractId())
                    .isTrue();
                assertThat(result.closingGca().amount())
                    .as("contract %s closing GCA, unreduced", result.contractId())
                    .isEqualByComparingTo(BatchFixtures.CLOSING_GCA_UNROUNDED);
                assertThat(result.closingGca().atPresentationScale())
                    .as("contract %s closing GCA at presentation scale", result.contractId())
                    .isEqualTo(BatchFixtures.CLOSING_GCA);
            }
            // The solver is BatchFixtures.EXPLODING_SOLVER, so reaching this line at all is the
            // assertion that 05 § 3.2's "the solve is inside the event branch only" held for every
            // contract in every partition. A run that re-solved would have thrown an AssertionError.
            assertThat(completed.aggregate().invariants())
                .as("SL-2 and ST-2 are asserted over the population")
                .isNotEmpty();
        }

        @Test
        @DisplayName("a quarantined contract does not fail the run, and is still a result (FR-905)")
        void aQuarantinedContractDoesNotFailTheRun() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            // A contract with no opening state as at the boundary. ContractPipeline refuses it —
            // "a period cannot be rolled forward from a balance nobody recorded" — and the barrier
            // turns the refusal into a quarantined ContractResult.
            String malformed = BatchFixtures.contractId(BatchFixtures.CORP, BatchFixtures.MUM, 3);
            RunRequest request =
                BatchFixtures.request(RUN_ID, population, Set.of(malformed));

            CompletedRun completed = harness.runner().run(request);

            assertThat(completed.aggregate().computedCount()).as("computed").isEqualTo(24);
            assertThat(completed.aggregate().quarantinedCount()).as("quarantined").isEqualTo(1);
            assertThat(completed.aggregate().unaccountedFor())
                .as("the malformed contract is a result, not an absence")
                .isZero();
            assertThat(completed.aggregate().quarantinedContracts()).containsExactly(malformed);
            // The run's queue holds exactly one record for it — filed once, by the aggregate step.
            assertThat(harness.queues().forRun(RUN_ID).quarantinedContracts())
                .containsExactly(malformed);
            assertThat(harness.queues().forRun(RUN_ID).size())
                .as("one record per quarantined contract per run")
                .isEqualTo(1);
            // FR-905: "one malformed contract must not fail a ten-million-contract run". The run
            // completed — the assertion is that this line was reached without an exception — and it
            // is the CLOSE that refuses, because the exception is unresolved (04 § 3).
            assertThat(completed.reportsCleanClose())
                .as("an unresolved quarantine blocks the close, not the run")
                .isFalse();
            assertThat(completed.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("quarantined"));
        }
    }

    @Nested
    @DisplayName("Restart")
    class Restart {

        /** In the 5-contract Delhi shard, die while computing the third contract. */
        private static final String KILLED_PARTITION =
            BatchFixtures.partition(BatchFixtures.RETAIL, BatchFixtures.DEL, 0);
        private static final int KILL_AFTER = 2;

        @Test
        @DisplayName("a run killed after N contracts resumes and still accounts for the whole"
            + " population")
        void aRunKilledMidPartitionResumesAndStillAccountsForThePopulation() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            RunRequest request = BatchFixtures.request(RUN_ID, population, Set.of());

            harness.pipelines().killIn(KILLED_PARTITION, KILL_AFTER);
            assertThatThrownBy(() -> harness.runner().run(request))
                .as("a partition that died takes the run down with it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ended FAILED");

            // What survived the kill: the three partitions that finished, and nothing from the one
            // that did not. 10 + 2 + 8 = 20 contracts, counted by hand.
            assertThat(harness.store().recordedSlices(RUN_ID))
                .as("only completed partitions commit")
                .hasSize(3)
                .extracting(PartitionKey::name)
                .doesNotContain(KILLED_PARTITION);
            assertThat(harness.store().contractsAccountedFor(RUN_ID)).hasSize(20);

            harness.pipelines().disarm();
            CompletedRun resumed = harness.runner().run(request);

            // THE ASSERTION THIS MODULE EXISTS FOR.
            int computed = resumed.aggregate().computedCount();
            int quarantined = resumed.aggregate().quarantinedCount();
            int unaccounted = resumed.aggregate().unaccountedFor();
            assertThat(computed + quarantined + unaccounted)
                .as("computed %d + quarantined %d + unaccounted %d must be the population,"
                    + " after a kill and a resume: %s",
                    computed, quarantined, unaccounted, resumed.describe())
                .isEqualTo(25);
            assertThat(unaccounted)
                .as("a contract with neither a figure nor an exception record is the"
                    + " 9,999,998-of-10,000,000 defect")
                .isZero();
            assertThat(computed).isEqualTo(25);
            assertThat(resumed.reportsCleanClose())
                .as("the resumed run is a clean close: %s", resumed.describe())
                .isTrue();

            // And no contract has two results. RunAggregate reports a duplicate as a blocking
            // reason, which is the observable form of an appending progress store: the re-run
            // partition's contracts would each appear twice.
            assertThat(resumed.blockingReasons())
                .as("a re-run partition must overwrite its own results, not append them")
                .noneSatisfy(reason -> assertThat(reason).contains("more than one result"));
            assertThat(resumed.aggregate().results())
                .extracting(ContractResult::contractId)
                .containsExactlyElementsOf(population);

            // Figures unchanged by the interruption.
            for (ContractResult result : resumed.aggregate().results()) {
                assertThat(result.closingGca().atPresentationScale())
                    .as("contract %s closing GCA after a restart", result.contractId())
                    .isEqualTo(BatchFixtures.CLOSING_GCA);
            }
        }

        @Test
        @DisplayName("the restart re-runs only the partition that did not complete")
        void aRestartDoesNotRecomputeCompletedPartitions() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            RunRequest request = BatchFixtures.request(RUN_ID, population, Set.of());

            harness.pipelines().killIn(KILLED_PARTITION, KILL_AFTER);
            assertThatThrownBy(() -> harness.runner().run(request))
                .isInstanceOf(IllegalStateException.class);
            harness.pipelines().disarm();
            harness.runner().run(request);

            // The 20 contracts in the three partitions that completed: computed exactly once across
            // both executions. This is the half of restart Spring Batch provides — a completed
            // partition is not re-run — and it is what makes resuming cheaper than restarting.
            // ADR-0007: "A run that dies at hour three of a four-hour window against a close
            // deadline must resume, not restart."
            for (String contractId : population) {
                if (contractId.startsWith(BatchFixtures.RETAIL + "/" + BatchFixtures.DEL)) {
                    continue;
                }
                assertThat(harness.pipelines().attemptsFor(contractId))
                    .as("contract %s is in a completed partition and must not be recomputed",
                        contractId)
                    .isEqualTo(1);
            }

            // The in-flight partition is re-run in full, and that is the honest cost of a commit
            // boundary at the shard: its first two contracts are computed twice. Stated as an
            // assertion rather than left implicit, because it is the number that bounds what a kill
            // costs — at a shard size of ten thousand against ten million contracts, seconds.
            assertThat(harness.pipelines().attemptsFor(
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 1)))
                .as("computed once before the kill and once after")
                .isEqualTo(2);
            assertThat(harness.pipelines().attemptsFor(
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 2)))
                .isEqualTo(2);
            assertThat(harness.pipelines().attemptsFor(
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 3)))
                .as("the contract the kill landed on was never computed before the resume")
                .isEqualTo(1);

            // 20 in completed partitions + 2 recomputed + 3 first computed on the resume = 27,
            // against 30 for a run that restarted from scratch.
            assertThat(harness.pipelines().totalAttempts())
                .as("total contract computations across both executions")
                .isEqualTo(27);
        }

        @Test
        @DisplayName("a population that moved between the failure and the restart is refused")
        void aPopulationThatMovedIsRefused() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            RunRequest request = BatchFixtures.request(RUN_ID, population, Set.of());

            harness.pipelines().killIn(KILLED_PARTITION, KILL_AFTER);
            assertThatThrownBy(() -> harness.runner().run(request))
                .isInstanceOf(IllegalStateException.class);
            harness.pipelines().disarm();

            // The overnight onboarding feed landed while the run was down. Three new retail Delhi
            // contracts are in scope that were not in the run when it started, so the Delhi grain
            // now needs 8 contracts where the plan assigned 5.
            List<String> grown = new ArrayList<>(population);
            grown.addAll(List.of(
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 6),
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 7),
                BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 8)));
            RunRequest afterTheFeed = BatchFixtures.request(RUN_ID, grown, Set.of());

            // Refused, and this is the case nothing downstream could see: every partition the
            // restart skipped as complete belongs to the old population, every partition it runs
            // belongs to the new one, and the aggregation over the union would tie against a
            // population that never existed as a single set.
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> harness.runner().run(afterTheFeed));
            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .as("the verify-plan step's own refusal travels out with the failed job")
                .anySatisfy(cause -> assertThat(cause)
                    .hasMessageContaining(
                        "the population or the knowledge boundary moved between the two"
                            + " executions"));
        }

        @Test
        @DisplayName("a restart over a population that SWAPPED a contract for another in the same"
            + " grain is refused, though every count is unchanged")
        void aSwapThatPreservesEveryCountIsRefused() {
            // The ordinary overnight churn, and the reason the plan fingerprint carries a checksum
            // of each partition's membership rather than only its name and size. One retail Mumbai
            // contract closes and one is onboarded, in the SAME grain: population size 25 -> 25,
            // grains 3 -> 3, partition names unchanged, shard sizes 10/2/5/8 unchanged.
            //
            // With names and sizes only, the two plans stamp identically and the restart skips the
            // completed shard that used to hold the departed contract — so the newly onboarded one
            // is computed by nobody, the departed one's stale result stands in for it, and the
            // population NETS: one missing, one extraneous, difference nil.
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            String departed = BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.MUM, 12);
            String onboarded = BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.MUM, 99);
            assertThat(population).contains(departed).doesNotContain(onboarded);

            harness.pipelines().killIn(KILLED_PARTITION, KILL_AFTER);
            assertThatThrownBy(() -> harness.runner().run(
                BatchFixtures.request(RUN_ID, population, Set.of())))
                .isInstanceOf(IllegalStateException.class);
            harness.pipelines().disarm();

            List<String> swapped = new ArrayList<>(population);
            swapped.set(swapped.indexOf(departed), onboarded);
            assertThat(swapped)
                .as("every count a name-and-size fingerprint can see is identical")
                .hasSameSizeAs(population);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> harness.runner().run(
                    BatchFixtures.request(RUN_ID, swapped, Set.of())));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .as("the membership checksum is what notices")
                .anySatisfy(cause -> assertThat(cause).hasMessageContaining(
                    "the population or the knowledge boundary moved between the two executions"));
            assertThat(harness.pipelines().attemptsFor(onboarded))
                .as("and the onboarded contract was never quietly left uncomputed")
                .isZero();
        }

        @Test
        @DisplayName("a restart at a moved knowledge boundary is refused")
        void aRestartAtAMovedBoundaryIsRefused() {
            // A partitioned run spread across two knowledge cuts. ADR-0007 rejected streaming
            // because it "would impose eventual consistency on a close that requires a consistent
            // cut", and a resume at a moved recordedAsAt is that eventual consistency arriving
            // through the restart path: the partitions that completed read the world as at 1 June
            // and the ones the restart runs read it as at 9 June, and the run reported a clean
            // close over the pair.
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();

            harness.pipelines().killIn(KILLED_PARTITION, KILL_AFTER);
            assertThatThrownBy(() -> harness.runner().run(
                BatchFixtures.request(RUN_ID, population, Set.of())))
                .isInstanceOf(IllegalStateException.class);
            harness.pipelines().disarm();

            RunRequest laterCut = BatchFixtures.requestAsAt(
                RUN_ID, population, Set.of(), Instant.parse("2028-06-09T00:00:00Z"));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> harness.runner().run(laterCut));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .anySatisfy(cause -> assertThat(cause)
                    .hasMessageContaining("knowledge boundary moved")
                    .hasMessageContaining("2028-06-09T00:00:00Z"));
        }

        @Test
        @DisplayName("a completed run refuses to be re-run under the same run id")
        void aCompletedRunRefusesToBeRunAgain() {
            BatchFixtures.Harness harness = BatchFixtures.harness();
            RunRequest request = BatchFixtures.request(
                RUN_ID, BatchFixtures.standardPopulation(), Set.of());

            harness.runner().run(request);

            // A completed run's figures are a published artefact. Re-running under the same run id
            // would overwrite the numbers a close was signed off on; running the period again is a
            // new run id, and reproducing it is a replay with its own shadow run id.
            assertThatThrownBy(() -> harness.runner().run(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already completed")
                .hasMessageContaining("shadow run id");
        }
    }

    @Nested
    @DisplayName("The completion barrier (ADR-0007)")
    class CompletionBarrier {

        @Test
        @DisplayName("a run whose store lost a partition's commit is refused, and still leaves the"
            + " report that says by how much")
        void aRunThatLostAPartitionIsRefused() {
            // The failing input, constructed: a progress store that accepts a partition's commit and
            // silently keeps nothing. A lost update under concurrency looks exactly like this, and it
            // is the most misleading way this module could fail, because the partition's own Spring
            // Batch step completed successfully — every partition green, and the run short by a
            // shard.
            String lost = BatchFixtures.partition(BatchFixtures.CORP, BatchFixtures.MUM, 0);
            BatchFixtures.Harness harness = BatchFixtures.harness(
                new SyncTaskExecutor(), BatchFixtures.SHARD_SIZE,
                new LosingStore(new InMemoryRunProgressStore(), lost));
            RunRequest request = BatchFixtures.request(
                "RUN-202805-LOSS", BatchFixtures.standardPopulation(), Set.of());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> harness.runner().run(request));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .as("the aggregate step's refusal, naming the residual")
                .anySatisfy(cause -> assertThat(cause)
                    .hasMessageContaining("does not account for its population")
                    .hasMessageContaining("unaccounted 8"));

            // And the report survives the refusal. An operator triaging a short close at 03:00 needs
            // the aggregate — which contracts, by how much — more than they need the job to have
            // failed tidily.
            assertThat(harness.store().completion("RUN-202805-LOSS"))
                .as("written before the refusal, on purpose")
                .isPresent()
                .get()
                .satisfies(completed -> {
                    assertThat(completed.aggregate().populationSize()).isEqualTo(25);
                    assertThat(completed.aggregate().computedCount()).isEqualTo(17);
                    assertThat(completed.aggregate().unaccountedFor()).isEqualTo(8);
                    assertThat(completed.reportsCleanClose()).isFalse();
                    assertThat(completed.blockingReasons()).anySatisfy(reason ->
                        assertThat(reason).contains("neither figures nor an exception record"));
                });
        }

        @Test
        @DisplayName("a missing contract and a stranger do not cancel: the gate compares identities")
        void aMissingContractAndAStrangerDoNotCancel() {
            // The gate used to be `unaccountedFor() != 0`, which is
            // `populationSize - results.size()` — and RunAggregate.of APPENDS a stranger to results.
            // So one contract with no result plus one result naming a contract outside the
            // population cancels to exactly nil, and the run completed reporting
            // "population 25, computed 25, unaccounted 0" over a book missing a contract.
            //
            // Constructed here at the store rather than through a population change, so that the
            // gate is tested even when the plan fingerprint would have caught the cause upstream.
            String swappedOut = BatchFixtures.contractId(BatchFixtures.CORP, BatchFixtures.MUM, 3);
            BatchFixtures.Harness harness = BatchFixtures.harness(
                new SyncTaskExecutor(), BatchFixtures.SHARD_SIZE,
                new RelabellingStore(new InMemoryRunProgressStore(), swappedOut, "LN-NOT-IN-SCOPE"));
            RunRequest request = BatchFixtures.request(
                "RUN-202805-SWAP", BatchFixtures.standardPopulation(), Set.of());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> harness.runner().run(request));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .anySatisfy(cause -> assertThat(cause)
                    .hasMessageContaining("does not account for its population")
                    .hasMessageContaining("Compared by identity and not by total"));

            // The netting is real: the two totals agree while the identities do not.
            assertThat(harness.store().completion("RUN-202805-SWAP"))
                .isPresent()
                .get()
                .satisfies(completed -> assertThat(completed.aggregate().unaccountedFor())
                    .as("one missing, one extraneous — the difference of the totals is nil")
                    .isZero());
        }
    }

    /** A store that relabels one contract's result, so the population nets without tying. */
    private record RelabellingStore(
        RunProgressStore delegate, String from, String to) implements RunProgressStore {

        @Override
        public void recordSlice(String runId, SliceOutcome outcome) {
            List<ContractResult> relabelled = outcome.results().stream()
                .map(result -> result.contractId().equals(from)
                    ? ContractResult.computed(
                        to, result.closingGca(), result.journal(), result.invariants())
                    : result)
                .toList();
            delegate.recordSlice(runId, new SliceOutcome(
                outcome.key(), relabelled, outcome.policyVersionIds()));
        }

        @Override
        public List<ContractResult> results(String runId) {
            return delegate.results(runId);
        }

        @Override
        public Set<String> contractsAccountedFor(String runId) {
            return delegate.contractsAccountedFor(runId);
        }

        @Override
        public List<PartitionKey> recordedSlices(String runId) {
            return delegate.recordedSlices(runId);
        }

        @Override
        public List<List<String>> policyStampsByPartition(String runId) {
            return delegate.policyStampsByPartition(runId);
        }

        @Override
        public void recordCompletion(String runId, CompletedRun completion) {
            delegate.recordCompletion(runId, completion);
        }

        @Override
        public java.util.Optional<CompletedRun> completion(String runId) {
            return delegate.completion(runId);
        }
    }

    /** A store that accepts one named partition's commit and keeps nothing of it. */
    private record LosingStore(RunProgressStore delegate, String losePartition)
        implements RunProgressStore {

        @Override
        public void recordSlice(String runId, SliceOutcome outcome) {
            if (outcome.key().name().equals(losePartition)) {
                return;
            }
            delegate.recordSlice(runId, outcome);
        }

        @Override
        public List<ContractResult> results(String runId) {
            return delegate.results(runId);
        }

        @Override
        public Set<String> contractsAccountedFor(String runId) {
            return delegate.contractsAccountedFor(runId);
        }

        @Override
        public List<PartitionKey> recordedSlices(String runId) {
            return delegate.recordedSlices(runId);
        }

        @Override
        public List<List<String>> policyStampsByPartition(String runId) {
            return delegate.policyStampsByPartition(runId);
        }

        @Override
        public void recordCompletion(String runId, CompletedRun completion) {
            delegate.recordCompletion(runId, completion);
        }

        @Override
        public java.util.Optional<CompletedRun> completion(String runId) {
            return delegate.completion(runId);
        }
    }

    @Nested
    @DisplayName("Exception queue discipline (04 s 2.13, 04 s 3)")
    class ExceptionQueueDiscipline {

        @Test
        @DisplayName("a restart files one record per quarantined contract, not one per attempt")
        void aRestartFilesOneRecordPerQuarantinedContract() {
            // RunProgressStore.recordSlice was made a per-contract PUT so that a re-run partition's
            // RESULTS are idempotent across a resume. The ExceptionQueue had no such semantics, and
            // the partitions used to file into the run's queue directly - so a partition killed
            // after it had quarantined a contract, then re-run, filed twice.
            //
            // The two records are not equals (their captured causes are different throwables), so
            // ExceptionQueue.resolve matches only one and the close stays blocked on a duplicate
            // nobody was told existed; the run record's exceptions_raised double-counts too.
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            // Quarantine the FIRST contract of the partition that gets killed, so the kill lands
            // after the record has been filed and the resume re-quarantines it.
            String malformed = BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.DEL, 1);
            RunRequest request = BatchFixtures.request(
                "RUN-202805-REQ", population, Set.of(malformed));

            harness.pipelines().killIn(
                BatchFixtures.partition(BatchFixtures.RETAIL, BatchFixtures.DEL, 0), 2);
            assertThatThrownBy(() -> harness.runner().run(request))
                .isInstanceOf(IllegalStateException.class);
            harness.pipelines().disarm();
            CompletedRun resumed = harness.runner().run(request);

            assertThat(resumed.aggregate().quarantinedCount()).isEqualTo(1);
            assertThat(resumed.aggregate().unaccountedFor()).isZero();
            assertThat(resumed.aggregate().computedCount()).isEqualTo(24);

            var queue = harness.queues().forRun("RUN-202805-REQ");
            assertThat(queue.size())
                .as("one record per quarantined contract per run, across a kill and a resume")
                .isEqualTo(1);
            assertThat(queue.closeBlockers())
                .as("so the operator is asked to work one exception, not two")
                .hasSize(1);
            assertThat(queue.forContract(malformed)).hasSize(1);
        }

        @Test
        @DisplayName("one run's queue does not hold another run's records")
        void eachRunHasItsOwnQueue() {
            // A single ExceptionQueue on the job accumulated every close's records, and
            // ExceptionQueue's reporting surface has no run-id filter - so period N's close gate
            // refused on period N-1's unresolved blockers, and exceptions_raised was cumulative.
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            String malformed = BatchFixtures.contractId(BatchFixtures.CORP, BatchFixtures.MUM, 3);

            harness.runner().run(
                BatchFixtures.request("RUN-202805-FIRST", population, Set.of(malformed)));
            harness.runner().run(
                BatchFixtures.request("RUN-202806-SECOND", population, Set.of()));

            assertThat(harness.queues().forRun("RUN-202805-FIRST").size())
                .as("the first close's one exception")
                .isEqualTo(1);
            assertThat(harness.queues().forRun("RUN-202806-SECOND").isEmpty())
                .as("and the second close, which had none, is not blocked by it")
                .isTrue();
            assertThat(harness.queues().forRun("RUN-202806-SECOND").blocksClose()).isFalse();
        }
    }

    @Nested
    @DisplayName("The partition shape does not move a figure (FR-903)")
    class PartitionShape {

        @Test
        @DisplayName("the same population under a different shard size and concurrency produces"
            + " byte-identical figures")
        void partitionShapeDoesNotMoveAFigure() {
            List<String> population = BatchFixtures.standardPopulation();

            // Four partitions, one at a time.
            BatchFixtures.Harness sequential =
                BatchFixtures.harness(new SyncTaskExecutor(), 10);
            CompletedRun oneAtATime = sequential.runner().run(
                BatchFixtures.request("RUN-202805-SEQ", population, Set.of()));

            // Eleven partitions, concurrently: 12 retail Mumbai contracts become three shards of 4,
            // 5 retail Delhi become two shards (4 + 1 — the remainder an integer division drops),
            // 8 corporate Mumbai become two shards of 4.
            BatchFixtures.Harness concurrent =
                BatchFixtures.harness(new SimpleAsyncTaskExecutor("eir-partition-"), 4);
            CompletedRun manyAtOnce = concurrent.runner().run(
                BatchFixtures.request("RUN-202805-PAR", population, Set.of()));

            assertThat(concurrent.store().recordedSlices("RUN-202805-PAR"))
                .as("3 + 2 + 2 partitions, counted by hand from a shard size of 4")
                .hasSize(7);

            // ShadowRun.figures is the projection DT-1 byte-compares — every contract's closing
            // balance and every journal line, keyed by contract, ordinal, account and side. Equal
            // maps mean the two runs are the same published artefact, which is FR-903 applied to the
            // runner rather than to the arithmetic: a partitioned run whose figure set depended on
            // how the work was divided, or on which thread finished first, could not be replayed.
            Map<String, Money> sequentialFigures =
                ShadowRun.figures(oneAtATime.aggregate().results());
            Map<String, Money> concurrentFigures =
                ShadowRun.figures(manyAtOnce.aggregate().results());
            assertThat(sequentialFigures)
                .as("%d figures", sequentialFigures.size())
                .isNotEmpty()
                .isEqualTo(concurrentFigures);

            // Order too, not only content: RunAggregate reassembles into population order whatever
            // order the partitions completed in, and a replay's byte comparison is over a rendered
            // artefact.
            assertThat(manyAtOnce.aggregate().results())
                .extracting(ContractResult::contractId)
                .containsExactlyElementsOf(population);
        }
    }

    @Nested
    @DisplayName("Wall clock")
    class WallClock {

        @Test
        @DisplayName("a small close runs and reports its wall clock")
        void aSmallCloseReportsItsWallClock() {
            // 2,000 contracts over two grains, sharded at 250 — eight partitions, run concurrently.
            List<String> population = new ArrayList<>();
            population.addAll(BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 1200));
            population.addAll(BatchFixtures.contracts(BatchFixtures.CORP, BatchFixtures.MUM, 800));

            BatchFixtures.Harness harness =
                BatchFixtures.harness(new SimpleAsyncTaskExecutor("eir-partition-"), 250);
            RunRequest request =
                BatchFixtures.request("RUN-202805-LOAD", population, Set.of());

            long startedAt = System.nanoTime();
            CompletedRun completed = harness.runner().run(request);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(completed.aggregate().populationSize()).isEqualTo(2000);
            assertThat(completed.aggregate().computedCount()).isEqualTo(2000);
            assertThat(completed.aggregate().unaccountedFor()).isZero();
            assertThat(harness.store().recordedSlices("RUN-202805-LOAD"))
                .as("1200/250 = 5 shards (4 x 250 + 200) and 800/250 = 4 (3 x 250 + 50)")
                .hasSize(9);

            // Reported rather than asserted against a threshold. Unit 16 owns the 10M-contract
            // Phase 5 exit gate and a wall clock on this machine, at this size, is not evidence
            // about it. What it is evidence about is that nothing here is accidentally quadratic in
            // the population — which a partitioner that scanned the population per partition, or a
            // progress store that rebuilt its result list per commit, would be.
            System.out.println(
                "[eir-batch] 2,000 contracts across 9 partitions in " + elapsedMillis + " ms ("
                    + completed.describe().lines().findFirst().orElse("") + ")");
        }
    }
}
