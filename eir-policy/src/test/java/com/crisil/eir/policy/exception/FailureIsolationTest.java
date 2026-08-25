package com.crisil.eir.policy.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.projection.UnsupportedScheduleShapeException;
import com.crisil.eir.calc.projection.blueprint.ExercisePolicy;
import com.crisil.eir.calc.projection.blueprint.OptionSchedule;
import com.crisil.eir.calc.projection.blueprint.OptionalitySppiFailureException;
import com.crisil.eir.calc.projection.blueprint.OptionalityUnresolvedException;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-905: "Isolate failures <b>per contract</b>: one malformed contract must not fail a
 * ten-million-contract run."
 *
 * <p>The central test is {@link PerContractIsolation#otherContractsStillProduceResults()}, which
 * is the requirement stated directly: run a batch in which one contract throws, and assert the
 * others produced their figures. Everything else here guards the two ways that requirement is
 * usually satisfied wrongly — by swallowing failures that condemn the whole run, and by
 * discarding the diagnostic.
 */
class FailureIsolationTest {

    private static final String RUN = "RUN-2027-04";

    /** Priced result for a contract id of the form {@code C-n}: n × 100.00, by construction. */
    private static BigDecimal price(String contractId) {
        return new BigDecimal(contractId.substring(2)).multiply(new BigDecimal("100"));
    }

    /** {@code breach} wrapped {@code layers} deep, as a proxy stack would wrap it. */
    private static RuntimeException wrapped(int layers, RuntimeException breach) {
        RuntimeException at = breach;
        for (int layer = 0; layer < layers; layer++) {
            at = new IllegalStateException("wrapper " + layer, at);
        }
        return at;
    }

    private static InvariantBreachException breachOf(InvariantId id) {
        return new InvariantBreachException(
            InvariantResult.fail(id, id.statement() + " did not hold", new BigDecimal("250.00")));
    }

    @Nested
    @DisplayName("FR-905 — one malformed contract does not fail the run")
    class PerContractIsolation {

        @Test
        @DisplayName("the other contracts still produce results")
        void otherContractsStillProduceResults() {
            // The requirement, stated directly. Five contracts, the third malformed. Expected
            // figures are n x 100.00 by the fixture's construction, written out rather than
            // recomputed: C-1 -> 100.00, C-2 -> 200.00, C-4 -> 400.00, C-5 -> 500.00.
            List<String> contracts = List.of("C-1", "C-2", "C-3", "C-4", "C-5");
            ExceptionQueue queue = new ExceptionQueue();

            Map<String, BigDecimal> priced = FailureIsolation.runBatch(
                RUN, contracts, id -> id,
                id -> {
                    if ("C-3".equals(id)) {
                        throw new IllegalStateException("tenor field arrived empty");
                    }
                    return price(id);
                },
                queue);

            assertThat(priced).as("four of five contracts priced")
                .containsExactly(
                    Map.entry("C-1", new BigDecimal("100")),
                    Map.entry("C-2", new BigDecimal("200")),
                    Map.entry("C-4", new BigDecimal("400")),
                    Map.entry("C-5", new BigDecimal("500")));
            assertThat(priced).as("the malformed contract has no figure")
                .doesNotContainKey("C-3");
            assertThat(queue.size()).as("exactly one exception raised").isEqualTo(1);
            assertThat(queue.records().get(0).contractId()).isEqualTo("C-3");
            assertThat(queue.quarantinedContracts()).containsExactly("C-3");
        }

        @Test
        @DisplayName("the contracts AFTER the failing one are computed, not just the ones before")
        void batchDoesNotStopAtTheFirstFailure() {
            // The defect this catches: a barrier that reports the failure correctly but is
            // wired outside the loop, so the run still ends at the first bad row. Only the
            // contracts after the failure prove the loop continued.
            ExceptionQueue queue = new ExceptionQueue();

            Map<String, BigDecimal> priced = FailureIsolation.runBatch(
                RUN, List.of("C-1", "C-2", "C-3"), id -> id,
                id -> {
                    if ("C-1".equals(id)) {
                        throw new IllegalStateException("first row malformed");
                    }
                    return price(id);
                },
                queue);

            assertThat(priced.keySet()).containsExactly("C-2", "C-3");
        }

        @Test
        @DisplayName("a hundred contracts with every seventh malformed leaves 86 results")
        void isolationScales() {
            // Hand-counted: of C-1..C-100, the multiples of seven are 7, 14, 21, 28, 35, 42, 49,
            // 56, 63, 70, 77, 84, 91, 98 — fourteen of them, leaving eighty-six priced. The point
            // is that the count of survivors does not depend on where in the batch the failures
            // fall, which is what FR-905's ten million contracts require.
            List<String> contracts = new ArrayList<>();
            for (int n = 1; n <= 100; n++) {
                contracts.add("C-" + n);
            }
            ExceptionQueue queue = new ExceptionQueue();

            Map<String, BigDecimal> priced = FailureIsolation.runBatch(
                RUN, contracts, id -> id,
                id -> {
                    if (Integer.parseInt(id.substring(2)) % 7 == 0) {
                        throw new IllegalStateException("malformed " + id);
                    }
                    return price(id);
                },
                queue);

            assertThat(priced).as("86 priced").hasSize(86);
            assertThat(queue.size()).as("14 quarantined").isEqualTo(14);
            assertThat(priced).containsKey("C-1").containsKey("C-100").doesNotContainKey("C-98");
            assertThat(priced.get("C-100")).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("a single isolated unit reports the value or the exception, never both")
        void singleUnitOutcome() {
            FailureIsolation.Outcome<BigDecimal> ok =
                FailureIsolation.isolate("C-1", RUN, () -> price("C-1"));
            assertThat(ok.succeeded()).isTrue();
            assertThat(ok.value()).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(ok.failure()).isEmpty();

            FailureIsolation.Outcome<BigDecimal> bad = FailureIsolation.isolate(
                "C-2", RUN, () -> {
                    throw new IllegalStateException("bad row");
                });
            assertThat(bad.failed()).isTrue();
            assertThat(bad.result()).isEmpty();
            assertThat(bad.exception().contractId()).isEqualTo("C-2");
        }

        @Test
        @DisplayName("an outcome that is neither, or both, is unrepresentable")
        void outcomeIsExactlyOneThing() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FailureIsolation.Outcome<>(null, null))
                .withMessageContaining("both were absent");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FailureIsolation.Outcome<>(
                    BigDecimal.ONE,
                    ExceptionRecord.captured("C-1", RUN, ExceptionCategory.NO_SOLUTION, "d",
                        new IllegalStateException("x"))))
                .withMessageContaining("both were present");
        }

        @Test
        @DisplayName("a duplicated contract id fails the run rather than overwriting a figure")
        void duplicateContractIdIsRefused() {
            ExceptionQueue queue = new ExceptionQueue();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.runBatch(
                    RUN, List.of("C-1", "C-1"), id -> id, FailureIsolationTest::price, queue))
                .withMessageContaining("appears twice");
        }

        @Test
        @DisplayName("a duplicate whose first copy FAILED is caught too")
        void duplicateIsCaughtEvenWhenTheFirstCopyFailed() {
            // The defect this catches: a guard that checks the results map, which failed
            // contracts never enter. The second copy's figure would be reported while the queue
            // held the same contract as quarantined — one contract both measured and not.
            ExceptionQueue queue = new ExceptionQueue();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.runBatch(
                    RUN, List.of("C-1", "C-1"), id -> id,
                    id -> {
                        if (queue.isEmpty()) {
                            throw new IllegalStateException("first copy malformed");
                        }
                        return price(id);
                    },
                    queue))
                .withMessageContaining("appears twice");
        }

        @Test
        @DisplayName("a blank contract id fails the run always, not only when that row throws")
        void blankContractIdFailsDeterministically() {
            // A validation failure inside the catch kills the batch the barrier exists to keep
            // alive — and only for rows that also happened to throw, which makes the run's
            // success depend on which rows were malformed. Checked before the work runs instead,
            // so a caller defect fails the same way every time.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.isolate("  ", RUN, () -> BigDecimal.ONE))
                .withMessageContaining("blank contract id");
        }

        @Test
        @DisplayName("a blank run id also fails before the work runs")
        void blankRunIdFailsDeterministically() {
            // With the contract id, the run id, the category and the detail all guaranteed good
            // before the work starts, nothing inside the barrier's catch can throw. An exception
            // escaping that catch aborts the batch and discards every figure computed so far.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.isolate("C-1", "  ", () -> BigDecimal.ONE))
                .withMessageContaining("blank run id");
        }

        @Test
        @DisplayName("the duplicate check runs before anything is computed or filed")
        void duplicateAbortLeavesTheQueueUntouched() {
            // The defect this catches: aborting mid-loop with the caller's queue already holding
            // entries from a run whose results are then discarded. 04 § 2.13's exceptions_raised
            // is written from that queue, so a half-finished run would read as a completed one.
            ExceptionQueue queue = new ExceptionQueue();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.runBatch(
                    RUN, List.of("C-1", "C-2", "C-2"), id -> id,
                    id -> {
                        if ("C-1".equals(id)) {
                            throw new IllegalStateException("first row malformed");
                        }
                        return price(id);
                    },
                    queue))
                .withMessageContaining("appears twice");
            assertThat(queue.isEmpty())
                .as("no partial run metadata from a batch that never ran").isTrue();
        }

        @Test
        @DisplayName("an anonymous, message-less throwable is filed rather than killing the batch")
        void anonymousThrowableIsStillFiled() {
            // getSimpleName() is the empty string for an anonymous class, so a naive detail would
            // be blank, ExceptionRecord would refuse it inside the catch, and one such contract
            // would end the run.
            ExceptionQueue queue = new ExceptionQueue();
            Map<String, BigDecimal> priced = FailureIsolation.runBatch(
                RUN, List.of("C-1", "C-2"), id -> id,
                id -> {
                    if ("C-1".equals(id)) {
                        throw new RuntimeException() {
                            private static final long serialVersionUID = 1L;
                        };
                    }
                    return price(id);
                },
                queue);

            assertThat(priced).containsOnlyKeys("C-2");
            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.records().get(0).detail())
                .as("the binary name stands in where there is no simple name")
                .contains("FailureIsolationTest");
        }

        @Test
        @DisplayName("a cycle in the cause chain does not hang the batch thread")
        void causeCycleTerminates() {
            // Throwable.initCause permits a cycle. An unbounded walk loops forever on the batch
            // thread: one contract hangs the partition with no exception and no diagnostic, which
            // is worse than any filing decision this class could get wrong.
            RuntimeException outer = new RuntimeException("A");
            RuntimeException inner = new RuntimeException("B", outer);
            outer.initCause(inner);

            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw inner;
                });

            assertThat(outcome.failed()).isTrue();
            assertThat(outcome.exception().detail()).startsWith("RuntimeException: B");
        }

        @Test
        @DisplayName("a stage that returns null fails the run; it is a code defect, not bad data")
        void nullResultIsNotIsolated() {
            // Filing it would put an entry in the queue that nobody can fix upstream, because
            // there is nothing wrong with the contract.
            assertThatIllegalStateException()
                .isThrownBy(() -> FailureIsolation.isolate("C-1", RUN, () -> null))
                .withMessageContaining("stages that decline to answer");
        }
    }

    @Nested
    @DisplayName("an Error is not a per-contract failure")
    class ErrorsAreNotIsolated {

        @Test
        @DisplayName("OutOfMemoryError propagates and takes the run with it")
        void outOfMemoryPropagates() {
            // A run that continues after an OOM produces figures assembled from a heap that ran
            // out partway through. They reconcile to nothing, and nothing in the output says so.
            // Unpublished accounts are recoverable; published wrong ones are not.
            ExceptionQueue queue = new ExceptionQueue();

            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new OutOfMemoryError("Java heap space");
                }))
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessage("Java heap space");
            assertThat(queue.isEmpty()).as("nothing was filed; the process is the problem")
                .isTrue();
        }

        @Test
        @DisplayName("StackOverflowError propagates")
        void stackOverflowPropagates() {
            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new StackOverflowError();
                }))
                .isInstanceOf(StackOverflowError.class);
        }

        @Test
        @DisplayName("an Error mid-batch ends the batch")
        void errorEndsTheBatch() {
            ExceptionQueue queue = new ExceptionQueue();
            assertThatThrownBy(() -> FailureIsolation.runBatch(
                RUN, List.of("C-1", "C-2", "C-3"), id -> id,
                id -> {
                    if ("C-2".equals(id)) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                    return price(id);
                },
                queue))
                .isInstanceOf(OutOfMemoryError.class);
            assertThat(queue.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("a run-level invariant breach is not isolated either — 05 § 4.5")
    class RunLevelBreaches {

        @Test
        @DisplayName("SL-1 fails the run: the whole set is untrustworthy")
        void subLedgerTieFailsTheRun() {
            // 05 § 4.5 verbatim: a contract-level failure is isolated; an aggregate invariant
            // failure fails the run. Filing SL-1 against whichever contract happened to be in
            // hand would attribute a set-level break to one contract, which is a fiction, and
            // would let the close proceed on a sub-ledger that does not tie to the GL.
            ExceptionQueue queue = new ExceptionQueue();

            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw breachOf(InvariantId.SL_1);
                }))
                .isInstanceOf(InvariantBreachException.class);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("PF-1 and DT-1 likewise")
        void otherRunLevelInvariants() {
            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw breachOf(InvariantId.PF_1);
                }))
                .isInstanceOf(InvariantBreachException.class);
            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw breachOf(InvariantId.DT_1);
                }))
                .isInstanceOf(InvariantBreachException.class);
        }

        @Test
        @DisplayName("the run-level set is exactly SL-1, PF-1 and DT-1")
        void runLevelSetIsNamed() {
            assertThat(FailureIsolation.RUN_LEVEL_INVARIANTS)
                .containsExactlyInAnyOrder(InvariantId.SL_1, InvariantId.PF_1, InvariantId.DT_1);
            assertThat(FailureIsolation.RUN_LEVEL_INVARIANTS)
                .as("HB-1 has its own per-contract category in 04 § 3, so it is isolable")
                .doesNotContain(InvariantId.HB_1)
                .doesNotContain(InvariantId.SL_2)
                .doesNotContain(InvariantId.IC_1);
        }

        @Test
        @DisplayName("a WRAPPED run-level breach still fails the run")
        void wrappedRunLevelBreachStillFailsTheRun() {
            // The defect this catches: an aggregation layer that rethrows an SL-1 breach inside
            // its own exception. A barrier that inspects only the outermost type isolates it as
            // one contract's queue entry, the run completes, and the close can be signed off over
            // a general ledger that does not reconcile — with the wrapping invisible in the
            // output. 05 § 4.5 forbids exactly that.
            assertThatThrownBy(() -> FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new IllegalStateException(
                        "sub-ledger aggregation failed", breachOf(InvariantId.SL_1));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InvariantBreachException.class);
            assertThat(FailureIsolation.failsTheRun(new IllegalStateException(
                "wrapped", breachOf(InvariantId.SL_1)))).isTrue();
        }

        @Test
        @DisplayName("a run-level breach wrapped forty layers deep STILL fails the run")
        void deeplyWrappedRunLevelBreachStillFailsTheRun() {
            // The defect this catches: a depth-capped walk of the cause chain. A cap of any size
            // means an SL-1 breach wrapped one layer deeper is not seen at all — the run
            // completes, the sub-ledger break is filed as one contract's MISSING_MANDATORY_FIELD
            // entry, and the truncation is invisible in the output. Spring Batch, JPA and AOP
            // proxies stack wrappers freely, so forty is not a hypothetical depth.
            ExceptionQueue queue = new ExceptionQueue();

            assertThat(FailureIsolation.failsTheRun(wrapped(40, breachOf(InvariantId.SL_1))))
                .isTrue();
            assertThatThrownBy(() -> FailureIsolation.runBatch(
                RUN, List.of("C-1"), id -> id,
                id -> {
                    throw wrapped(40, breachOf(InvariantId.SL_1));
                },
                queue))
                .isInstanceOf(IllegalStateException.class);
            assertThat(queue.isEmpty()).as("nothing filed: 05 § 4.5 fails the run").isTrue();
        }

        @Test
        @DisplayName("a deeply wrapped detail names the true root cause, not a middle wrapper")
        void deepChainNamesTheRealRoot() {
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw wrapped(40, new NullPointerException("the real root"));
                });

            assertThat(outcome.exception().detail())
                .as("a middle wrapper reported as the root misdirects whoever works the queue")
                .contains("[caused by NullPointerException: the real root]");
            assertThat(outcome.exception().category())
                .as("and the category recognised from that root survives the depth")
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(FailureIsolation.recognise(wrapped(40, breachOf(InvariantId.IC_1))))
                .contains(ExceptionCategory.IC1_BREACH);
        }

        @Test
        @DisplayName("a WRAPPED contract-level breach keeps its category")
        void wrappedContractLevelBreachKeepsItsCategory() {
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new IllegalStateException(
                        "projection failed", breachOf(InvariantId.IC_1));
                });

            assertThat(outcome.exception().category()).isEqualTo(ExceptionCategory.IC1_BREACH);
        }

        @Test
        @DisplayName("a contract-level breach IS isolated")
        void contractLevelBreachIsIsolated() {
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw breachOf(InvariantId.IC_1);
                });

            assertThat(outcome.failed()).isTrue();
            assertThat(outcome.exception().category()).isEqualTo(ExceptionCategory.IC1_BREACH);
        }
    }

    @Nested
    @DisplayName("categories the engine can justify — 04 § 3's closed set of ten")
    class Categorisation {

        @Test
        @DisplayName("the four invariants 04 § 3 names get their own categories")
        void invariantsWithTheirOwnCategory() {
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.IC_1)))
                .contains(ExceptionCategory.IC1_BREACH);
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.TG_1)))
                .contains(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.PC_1)))
                .contains(ExceptionCategory.PENAL_CHARGE_REJECTED);
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.HB_1)))
                .contains(ExceptionCategory.DISCONTINUED_HEDGE_NO_SCHEDULE);
        }

        @Test
        @DisplayName("a TG-1 breach arriving as a THROW is quarantined, not filed as a demotion")
        void aThrownDemotionIsStillAQuarantine() {
            // The trap. STALE_EQUIVALENCE_TEST does not stop the contract, because 03 § 10.2's
            // response is to demote the population to Tier 2 and measure it PROPERLY — a
            // statement about a computation that succeeded by a costlier route. The barrier only
            // runs when the computation aborted, so there is no Tier 2 figure here. Filing it as
            // non-stopping would leave C-1 absent from the results and absent from
            // quarantinedContracts(): a reported population short by one contract with every
            // control still tying.
            ExceptionQueue queue = new ExceptionQueue();
            Map<String, BigDecimal> priced = FailureIsolation.runBatch(
                RUN, List.of("C-1", "C-2"), id -> id,
                id -> {
                    if ("C-1".equals(id)) {
                        throw breachOf(InvariantId.TG_1);
                    }
                    return price(id);
                },
                queue);

            assertThat(priced).as("C-1 produced no figure").containsOnlyKeys("C-2");
            assertThat(queue.quarantinedContracts())
                .as("so it must be excluded from the reported population")
                .containsExactly("C-1");
            assertThat(queue.demotedContracts())
                .as("demotedContracts promises 'still measured, still reported'").isEmpty();
            assertThat(queue.records().get(0).category())
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(queue.records().get(0).detail())
                .as("the recognised name of the defect is kept, only the routing label changes")
                .contains("recognised as STALE_EQUIVALENCE_TEST")
                .contains("filed as MISSING_MANDATORY_FIELD");
        }

        @Test
        @DisplayName("recognise still names TG-1 accurately; only the barrier demotes it")
        void recogniseNamesTheDefectItself() {
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.TG_1)))
                .as("the true name of the defect")
                .contains(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            assertThat(FailureIsolation.capturedCategory(
                breachOf(InvariantId.TG_1), ExceptionCategory.MISSING_MANDATORY_FIELD))
                .as("but a captured category always stops the contract")
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }

        @Test
        @DisplayName("the barrier refuses a fallback category that does not stop the contract")
        void nonStoppingFallbackIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FailureIsolation.isolate(
                    "C-1", RUN, ExceptionCategory.POOL_BACKTEST_BREACH, () -> BigDecimal.ONE))
                .withMessageContaining("does not stop the contract");
        }

        @Test
        @DisplayName("a breach of an invariant with no category of its own is not guessed at")
        void unnamedInvariantFallsThrough() {
            // INV-1, ST-3 and the rest have no entry in 04 § 3's ten. recognise() says so by
            // answering empty, and the caller's fallback fills the label while the detail keeps
            // the invariant id.
            assertThat(FailureIsolation.recognise(breachOf(InvariantId.INV_1))).isEmpty();

            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw breachOf(InvariantId.INV_1);
                });
            assertThat(outcome.exception().category())
                .isEqualTo(FailureIsolation.UNRECOGNISED_FAILURE);
            assertThat(outcome.exception().detail())
                .as("the label is coarse, so the detail has to be exact")
                .contains("INV_1")
                .contains(InvariantId.INV_1.statement());
        }

        @Test
        @DisplayName("a NullPointerException is a field the computation could not proceed without")
        void nullPointerIsAMissingField() {
            assertThat(FailureIsolation.recognise(new NullPointerException("rate driver")))
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }

        @Test
        @DisplayName("the three eir-calc projection refusals map to MISSING_MANDATORY_FIELD")
        void projectionRefusalsAreRecognised() {
            assertThat(FailureIsolation.recognise(new OptionalityUnresolvedException(
                ExercisePolicy.MOST_LIKELY_OUTCOME, "no most-likely date supplied")))
                .as("absent judgement inputs are literally a missing mandatory field")
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);

            assertThat(FailureIsolation.recognise(new OptionalitySppiFailureException(
                OptionSchedule.OptionType.CONVERSION)))
                .as("the missing input is the classification determination of 03 § 11")
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);

            ContractTerms unsupported = ContractTerms.of(
                Money.inr("1000000"), Rate.monthly(new BigDecimal("0.01")), 24, 12,
                LocalDate.of(2027, 4, 1), LocalDate.of(2027, 5, 1),
                DayCountConvention.ACT_365F, ScheduleShape.STRUCTURED, RateType.FIXED);
            assertThat(FailureIsolation.recognise(
                new UnsupportedScheduleShapeException(unsupported, List.of("AnnuityProjector"))))
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }

        @Test
        @DisplayName("an unrecognised RuntimeException is filed, not propagated")
        void unrecognisedFailureIsStillFiled() {
            // The FR-905 trade-off, made explicitly. Propagating an exception the closed
            // ten-category set cannot name would fail a ten-million-contract run on one bad row.
            assertThat(FailureIsolation.recognise(new ArithmeticException("/ by zero"))).isEmpty();
            assertThat(FailureIsolation.categorise(
                new ArithmeticException("/ by zero"), ExceptionCategory.NO_SOLUTION))
                .as("the caller's stage default wins over the generic one")
                .isEqualTo(ExceptionCategory.NO_SOLUTION);
            assertThat(FailureIsolation.UNRECOGNISED_FAILURE)
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }

        @Test
        @DisplayName("a caller that knows its stage names a better category")
        void callerSuppliedFallbackIsUsed() {
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, ExceptionCategory.MISSING_COST_FUNCTION, () -> {
                    throw new IllegalStateException("cost centre 4471 has no cost_function");
                });

            assertThat(outcome.exception().category())
                .isEqualTo(ExceptionCategory.MISSING_COST_FUNCTION);
        }

        @Test
        @DisplayName("failsTheRun answers only for the run-level invariants")
        void failsTheRunIsNarrow() {
            assertThat(FailureIsolation.failsTheRun(breachOf(InvariantId.SL_1))).isTrue();
            assertThat(FailureIsolation.failsTheRun(breachOf(InvariantId.IC_1))).isFalse();
            assertThat(FailureIsolation.failsTheRun(new NullPointerException())).isFalse();
        }
    }

    @Nested
    @DisplayName("the isolation never loses the throwable")
    class DiagnosticsSurvive {

        @Test
        @DisplayName("the captured record carries the throwable instance and its stack")
        void throwableTravelsWithTheRecord() {
            IllegalStateException thrown = new IllegalStateException("tenor field arrived empty");

            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw thrown;
                });

            assertThat(outcome.exception().cause()).isSameAs(thrown);
            assertThat(outcome.exception().diagnostic().orElseThrow())
                .contains("IllegalStateException")
                .contains("tenor field arrived empty")
                .contains("FailureIsolationTest");
        }

        @Test
        @DisplayName("the detail names the exception type as well as its message")
        void detailNamesTheType() {
            // MISSING_MANDATORY_FIELD covers five distinct exception classes, so the class name
            // is what separates them in a queue report of ten thousand rows.
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new NullPointerException("rateDriver was null");
                });

            assertThat(outcome.exception().detail())
                .isEqualTo("NullPointerException: rateDriver was null");
        }

        @Test
        @DisplayName("a wrapped failure names the root cause too")
        void detailUnwrapsToTheRootCause() {
            // A NullPointerException wrapped three frames up presents as the wrapper, and the
            // wrapper is never the thing that needs fixing.
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new IllegalStateException(
                        "projection failed", new NullPointerException("tenorMonths"));
                });

            assertThat(outcome.exception().detail())
                .isEqualTo("IllegalStateException: projection failed"
                    + " [caused by NullPointerException: tenorMonths]");
        }

        @Test
        @DisplayName("a message-less throwable still yields a detail")
        void messagelessThrowableStillDescribed() {
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw new NullPointerException();
                });

            assertThat(outcome.exception().detail()).isEqualTo("NullPointerException");
        }

        @Test
        @DisplayName("orElseThrow chains the original throwable rather than summarising it")
        void orElseThrowKeepsTheCause() {
            IllegalStateException thrown = new IllegalStateException("bad row");
            FailureIsolation.Outcome<BigDecimal> outcome = FailureIsolation.isolate(
                "C-1", RUN, () -> {
                    throw thrown;
                });

            assertThatThrownBy(outcome::orElseThrow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C-1 was quarantined")
                .hasCause(thrown);
        }
    }
}
