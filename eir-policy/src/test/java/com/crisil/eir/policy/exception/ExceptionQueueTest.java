package com.crisil.eir.policy.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The close gate of 04 § 3: "Unresolved exceptions block the close unless explicitly accepted
 * with approval."
 *
 * <p>Expected values are the specification's own — which categories stop a contract comes from
 * {@link ExceptionCategory}'s documented asymmetry, and the close-gate rule comes from that one
 * sentence in 04 § 3.
 */
class ExceptionQueueTest {

    private static final String RUN = "RUN-2027-04";
    private static final String PAYLOAD = "s3://eir-payloads/RUN-2027-04/x.json";

    private static ExceptionRecord raised(String contractId, ExceptionCategory category) {
        return ExceptionRecord.raise(
            contractId, RUN, category, category + " on " + contractId, PAYLOAD);
    }

    @Nested
    @DisplayName("the close gate")
    class CloseGate {

        @Test
        @DisplayName("an empty queue does not block")
        void emptyQueueDoesNotBlock() {
            ExceptionQueue queue = new ExceptionQueue();

            assertThat(queue.isEmpty()).isTrue();
            assertThat(queue.blocksClose()).isFalse();
            assertThat(queue.closeBlockers()).isEmpty();
            assertThat(queue.describeCloseGate())
                .as("an auditor reads this sentence; 'all accepted with approval' over nothing"
                    + " raised asserts an approval nobody gave")
                .isEqualTo("close not blocked: no exceptions raised");
        }

        @Test
        @DisplayName("one unresolved exception blocks the close")
        void oneOpenExceptionBlocks() {
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(raised("LN-1", ExceptionCategory.UNMAPPED_FEE_CODE));

            assertThat(queue.blocksClose()).isTrue();
            assertThat(queue.closeBlockers()).hasSize(1);
            assertThat(queue.describeCloseGate()).contains("CLOSE BLOCKED by 1 of 1");
        }

        @Test
        @DisplayName("resolving the last blocker unblocks the close")
        void resolvingUnblocks() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord open = raised("LN-1", ExceptionCategory.UNMAPPED_FEE_CODE);
            queue.raise(open);

            ExceptionRecord resolved =
                queue.resolve(open, "fee.master.owner", "PROC-XX mapped in FEE-2027.2");

            assertThat(queue.blocksClose()).isFalse();
            assertThat(queue.size()).as("replaced in place, not appended").isEqualTo(1);
            assertThat(queue.records()).containsExactly(resolved);
            assertThat(queue.openRecords()).isEmpty();
        }

        @Test
        @DisplayName("acceptance with approval unblocks the close without fixing anything")
        void acceptanceUnblocks() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord open = raised("LN-1", ExceptionCategory.MISSING_COST_FUNCTION);
            queue.raise(open);

            queue.acceptWithApproval(open, "cfo.delegate", "sourcing deferred to FY28 Q1");

            assertThat(queue.blocksClose()).as("04 § 3's explicit exception").isFalse();
            assertThat(queue.quarantinedContracts())
                .as("the defect stands, so LN-1 still has no figure")
                .containsExactly("LN-1");
            assertThat(queue.records().get(0).status())
                .isEqualTo(ExceptionStatus.ACCEPTED_WITH_APPROVAL);
        }

        @Test
        @DisplayName("one blocker among many still blocks")
        void oneBlockerAmongManyStillBlocks() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord first = raised("LN-1", ExceptionCategory.UNMAPPED_FEE_CODE);
            ExceptionRecord second = raised("LN-2", ExceptionCategory.NO_SOLUTION);
            ExceptionRecord third = raised("LN-3", ExceptionCategory.IC1_BREACH);
            queue.raiseAll(List.of(first, second, third));

            queue.resolve(first, "a.person", "fixed");
            queue.acceptWithApproval(second, "b.person", "immaterial");

            assertThat(queue.blocksClose()).isTrue();
            assertThat(queue.closeBlockers()).containsExactly(third);
            assertThat(queue.describeCloseGate()).contains("CLOSE BLOCKED by 1 of 3");
        }
    }

    @Nested
    @DisplayName("quarantine is not the same set as the close gate")
    class Quarantine {

        @Test
        @DisplayName("the two demoting categories never quarantine, but do block the close")
        void demotingCategoriesAreNotQuarantines() {
            // The asymmetry ExceptionCategory documents: eight of ten stop the contract, and
            // STALE_EQUIVALENCE_TEST and POOL_BACKTEST_BREACH instead demote the population to a
            // more expensive, correct measurement (03 § 10.1-10.2). A queue that quarantined them
            // would drop from the reported population the very contracts that had just been
            // measured contract by contract.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(raised("POOL-A-1", ExceptionCategory.STALE_EQUIVALENCE_TEST));
            queue.raise(raised("POOL-B-1", ExceptionCategory.POOL_BACKTEST_BREACH));

            assertThat(queue.quarantinedContracts()).isEmpty();
            assertThat(queue.demotedContracts()).containsExactly("POOL-A-1", "POOL-B-1");
            assertThat(queue.blocksClose())
                .as("a population that moved measurement basis is a close-gate item").isTrue();
        }

        @Test
        @DisplayName("the eight stopping categories all quarantine their contract")
        void stoppingCategoriesQuarantine() {
            // Enumerated from ExceptionCategory's own javadoc rather than derived by calling it:
            // these are the eight 04 § 3 describes as a hard stop for that contract.
            List<ExceptionCategory> stopping = List.of(
                ExceptionCategory.UNMAPPED_FEE_CODE,
                ExceptionCategory.MISSING_COST_FUNCTION,
                ExceptionCategory.NO_SOLUTION,
                ExceptionCategory.MULTIPLE_ROOTS,
                ExceptionCategory.MISSING_MANDATORY_FIELD,
                ExceptionCategory.PENAL_CHARGE_REJECTED,
                ExceptionCategory.IC1_BREACH,
                ExceptionCategory.DISCONTINUED_HEDGE_NO_SCHEDULE);
            ExceptionQueue queue = new ExceptionQueue();
            for (ExceptionCategory category : stopping) {
                queue.raise(raised("LN-" + category, category));
            }

            assertThat(queue.quarantinedContracts()).hasSize(8);
            assertThat(queue.demotedContracts()).isEmpty();
        }

        @Test
        @DisplayName("resolution lifts a quarantine; acceptance does not")
        void onlyResolutionLiftsQuarantine() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord fixed = raised("LN-1", ExceptionCategory.NO_SOLUTION);
            ExceptionRecord signedOver = raised("LN-2", ExceptionCategory.NO_SOLUTION);
            queue.raiseAll(List.of(fixed, signedOver));

            queue.resolve(fixed, "quant.owner", "flow vector corrected, re-solved at 11.4%");
            queue.acceptWithApproval(signedOver, "cfo.delegate", "exposure written off 31 Mar");

            assertThat(queue.isQuarantined("LN-1"))
                .as("a recomputation will now produce a figure").isFalse();
            assertThat(queue.isQuarantined("LN-2"))
                .as("nobody signed a figure into existence").isTrue();
            assertThat(queue.quarantinedContracts()).containsExactly("LN-2");
        }

        @Test
        @DisplayName("a contract with two exceptions stays quarantined until both are worked")
        void quarantineNeedsEveryExceptionWorked() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord fee = raised("LN-1", ExceptionCategory.UNMAPPED_FEE_CODE);
            ExceptionRecord cost = raised("LN-1", ExceptionCategory.MISSING_COST_FUNCTION);
            queue.raiseAll(List.of(fee, cost));

            queue.resolve(fee, "fee.master.owner", "mapped");

            assertThat(queue.forContract("LN-1")).as("no de-duplication: two distinct fixes")
                .hasSize(2);
            assertThat(queue.isQuarantined("LN-1")).isTrue();

            queue.resolve(cost, "hr.data.owner", "cost_function sourced for centre 4471");
            assertThat(queue.isQuarantined("LN-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("the demoted set and the quarantined set never overlap")
    class DemotedSet {

        @Test
        @DisplayName("a contract with both kinds of exception counts as quarantined, not demoted")
        void aStoppingExceptionWins() {
            // The defect this catches: a caller reading demotedContracts() as "still measured,
            // still reported" and reporting a contract that was never computed. Both sets are
            // consumed by the same reporting step, so an overlap is a figure published twice or
            // not at all.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(raised("LN-1", ExceptionCategory.STALE_EQUIVALENCE_TEST));
            queue.raise(raised("LN-1", ExceptionCategory.UNMAPPED_FEE_CODE));

            assertThat(queue.quarantinedContracts()).containsExactly("LN-1");
            assertThat(queue.demotedContracts()).isEmpty();
        }

        @Test
        @DisplayName("a resolved demotion is no longer a demotion")
        void resolvingADemotionClearsIt() {
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord stale = raised("POOL-A-1", ExceptionCategory.STALE_EQUIVALENCE_TEST);
            queue.raise(stale);
            assertThat(queue.demotedContracts()).containsExactly("POOL-A-1");

            queue.resolve(stale, "tier.owner", "equivalence test re-run 2027-03-31");

            assertThat(queue.demotedContracts())
                .as("the population is back on the basis its tier assignment claims").isEmpty();
            assertThat(queue.quarantinedContracts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the payload-reference hand-off")
    class PayloadHandOff {

        @Test
        @DisplayName("a captured record can be given its payload reference in place")
        void payloadRefIsAttachedInPlace() {
            // Without this the queued copy keeps a null payload_ref for the life of the run, and
            // because record equality includes payload_ref, resolving the updated record would
            // report it as never raised.
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord captured = ExceptionRecord.captured(
                "LN-1", RUN, ExceptionCategory.IC1_BREACH, "IC-1 breached by 250.00",
                new IllegalStateException("non-cash item in the vector"));
            queue.raise(captured);

            ExceptionRecord stored = queue.attachPayloadRef(captured, PAYLOAD);

            assertThat(queue.records()).containsExactly(stored);
            assertThat(queue.records().get(0).payloadRef()).isEqualTo(PAYLOAD);
            assertThat(queue.records().get(0).cause())
                .as("the stack says which stage, the payload says on what").isNotNull();
            assertThat(queue.size()).as("replaced in place, not appended").isEqualTo(1);

            ExceptionRecord resolved = queue.resolve(stored, "quant.owner", "vector corrected");
            assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
        }
    }

    @Nested
    @DisplayName("the run record")
    class RunRecord {

        @Test
        @DisplayName("counts are by category, in the order 04 § 3 lists them, zeros omitted")
        void countByCategory() {
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(raised("LN-1", ExceptionCategory.NO_SOLUTION));
            queue.raise(raised("LN-2", ExceptionCategory.UNMAPPED_FEE_CODE));
            queue.raise(raised("LN-3", ExceptionCategory.NO_SOLUTION));

            Map<ExceptionCategory, Integer> counts = queue.countByCategory();

            assertThat(counts).containsExactly(
                Map.entry(ExceptionCategory.UNMAPPED_FEE_CODE, 1),
                Map.entry(ExceptionCategory.NO_SOLUTION, 2));
            assertThat(counts).as("a report of ten rows of which eight are zero buries the two"
                + " that matter").hasSize(2);
            assertThat(queue.size()).as("exceptions_raised, 04 § 2.13").isEqualTo(3);
        }

        @Test
        @DisplayName("records() is a snapshot and cannot be written through")
        void recordsIsAnImmutableSnapshot() {
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(raised("LN-1", ExceptionCategory.NO_SOLUTION));
            List<ExceptionRecord> snapshot = queue.records();

            queue.raise(raised("LN-2", ExceptionCategory.NO_SOLUTION));

            assertThat(snapshot).as("taken before the second raise").hasSize(1);
            assertThat(queue.records()).hasSize(2);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snapshot.add(raised("LN-3", ExceptionCategory.NO_SOLUTION)));
        }
    }

    @Nested
    @DisplayName("working an exception is not silently idempotent")
    class Working {

        @Test
        @DisplayName("resolving something that was never raised is refused")
        void resolvingAnUnqueuedRecordIsRefused() {
            ExceptionQueue queue = new ExceptionQueue();

            assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> queue.resolve(
                    raised("LN-9", ExceptionCategory.NO_SOLUTION), "a.person", "note"))
                .withMessageContaining("never raised, or it has already been worked");
        }

        @Test
        @DisplayName("resolving twice is refused, so an approval cannot be overwritten")
        void resolvingTwiceIsRefused() {
            // The defect this catches: a workflow that replays a resolution and silently
            // overwrites an acceptance-with-approval, erasing the record of who signed for the
            // close and why.
            ExceptionQueue queue = new ExceptionQueue();
            ExceptionRecord open = raised("LN-1", ExceptionCategory.NO_SOLUTION);
            queue.raise(open);
            queue.acceptWithApproval(open, "cfo.delegate", "immaterial");

            assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> queue.resolve(open, "someone.else", "fixed"));
            assertThat(queue.records().get(0).resolvedBy()).isEqualTo("cfo.delegate");
        }
    }

    @Nested
    @DisplayName("a partitioned run raises into one queue")
    class Concurrency {

        @Test
        @DisplayName("eight threads raising five hundred each lose nothing")
        void noRecordIsLost() throws InterruptedException {
            // 4,000 is 8 x 500, hand-multiplied. The defect this catches is the worst available
            // failure of this class: a dropped record is a contract that failed and that nobody
            // will ever know failed — absent from the results, absent from the queue, and every
            // control still ties on a population short by one.
            ExceptionQueue queue = new ExceptionQueue();
            int threads = 8;
            int perThread = 500;
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> failures = new ArrayList<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    int partition = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int n = 0; n < perThread; n++) {
                                queue.raise(raised(
                                    "LN-" + partition + "-" + n, ExceptionCategory.NO_SOLUTION));
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException unexpected) {
                            synchronized (failures) {
                                failures.add(unexpected);
                            }
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(failures).isEmpty();
            assertThat(queue.size()).isEqualTo(4000);
            assertThat(queue.quarantinedContracts()).hasSize(4000);
        }
    }
}
