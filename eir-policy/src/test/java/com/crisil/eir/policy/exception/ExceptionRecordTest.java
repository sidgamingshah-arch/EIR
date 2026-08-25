package com.crisil.eir.policy.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.solver.SolveStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code EXCEPTION} entity of 04 § 3, and the bridge from a failed solve into it.
 *
 * <p>Expected values throughout are the ones 04 § 3 and 03 § 4.3 state, not values read back out
 * of the implementation.
 */
class ExceptionRecordTest {

    private static final String RUN = "RUN-2027-04";
    private static final String CONTRACT = "LN-000123";
    private static final String PAYLOAD = "s3://eir-payloads/RUN-2027-04/LN-000123.json";

    private static ExceptionRecord open() {
        return ExceptionRecord.raise(
            CONTRACT, RUN, ExceptionCategory.UNMAPPED_FEE_CODE,
            "fee code PROC-XX is not in fee rule set FEE-2027.1", PAYLOAD);
    }

    @Nested
    @DisplayName("the eight columns of 04 § 3")
    class EntityShape {

        @Test
        @DisplayName("a raised exception is OPEN, unresolved, and carries its payload")
        void raisedShape() {
            ExceptionRecord record = open();

            assertThat(record.contractId()).as("contract_id").isEqualTo(CONTRACT);
            assertThat(record.raisedByRunId()).as("raised_by_run_id").isEqualTo(RUN);
            assertThat(record.category()).as("category")
                .isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
            assertThat(record.detail()).as("detail").contains("PROC-XX");
            assertThat(record.payloadRef()).as("payload_ref").isEqualTo(PAYLOAD);
            assertThat(record.status()).as("status").isEqualTo(ExceptionStatus.OPEN);
            assertThat(record.resolvedBy()).as("resolved_by").isNull();
            assertThat(record.resolutionNote()).as("resolution_note").isNull();
        }

        @Test
        @DisplayName("an exception nobody can attribute to a contract is refused")
        void contractIdIsMandatory() {
            // FR-905 isolates failures PER CONTRACT. A record with no contract id cannot be
            // excluded from the reported population, which is the one thing a quarantine is for.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExceptionRecord.raise(
                    "  ", RUN, ExceptionCategory.NO_SOLUTION, "d", PAYLOAD))
                .withMessageContaining("FR-905 is per-contract");
        }

        @Test
        @DisplayName("a category with no detail is refused")
        void detailIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExceptionRecord.raise(
                    CONTRACT, RUN, ExceptionCategory.NO_SOLUTION, "", PAYLOAD))
                .withMessageContaining("category alone tells whoever works the queue nothing");
        }

        @Test
        @DisplayName("a run id is mandatory, because 04 § 2.13 counts exceptions per run")
        void runIdIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExceptionRecord.raise(
                    CONTRACT, " ", ExceptionCategory.NO_SOLUTION, "d", PAYLOAD))
                .withMessageContaining("names no run");
        }
    }

    @Nested
    @DisplayName("a captured failure keeps its diagnostic")
    class Diagnostics {

        @Test
        @DisplayName("neither a payload reference nor a throwable is refused outright")
        void somethingDiagnosableIsMandatory() {
            // The defect this catches: a barrier that satisfies FR-905 by catching, filing a
            // category and discarding the throwable. Ten thousand entries reading
            // MISSING_MANDATORY_FIELD with no field named is a queue nobody can work, so nothing
            // gets fixed and the same exceptions are accepted with approval every period.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExceptionRecord(
                    CONTRACT, RUN, ExceptionCategory.MISSING_MANDATORY_FIELD, "something broke",
                    null, ExceptionStatus.OPEN, null, null, null))
                .withMessageContaining("a contract nobody can fix");
        }

        @Test
        @DisplayName("a raised-from-status exception must carry the payload, per 03 § 4.3")
        void raisedFromStatusNeedsPayload() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExceptionRecord.raise(
                    CONTRACT, RUN, ExceptionCategory.NO_SOLUTION, "no sign change", null))
                .withMessageContaining("03 § 4.3 requires it to be attached");
        }

        @Test
        @DisplayName("the throwable is retained, and rendered with its stack")
        void throwableIsRetained() {
            IllegalStateException thrown = new IllegalStateException("tenor field was empty");
            ExceptionRecord record = ExceptionRecord.captured(
                CONTRACT, RUN, ExceptionCategory.MISSING_MANDATORY_FIELD, "stage failed", thrown);

            assertThat(record.cause()).as("the throwable itself, not a summary of it")
                .isSameAs(thrown);
            assertThat(record.diagnostic()).as("rendered diagnostic").isPresent();
            assertThat(record.diagnostic().orElseThrow())
                .as("names the type, the message and the frame that threw")
                .contains("IllegalStateException")
                .contains("tenor field was empty")
                .contains("ExceptionRecordTest");
        }

        @Test
        @DisplayName("a status-raised exception has no throwable and says so")
        void noThrowableIsEmptyRatherThanBlank() {
            assertThat(open().diagnostic()).isEmpty();
            assertThat(open().cause()).isNull();
        }

        @Test
        @DisplayName("equality is over the eight persisted columns, not over the throwable")
        void equalityIgnoresTheCause() {
            // Two runs of the same defect produce distinct throwable instances. If the cause
            // counted, queue.records().contains(record) would be false for a record the queue is
            // holding, and the resolve-in-place lookup would never find its target.
            ExceptionRecord first = ExceptionRecord.captured(
                CONTRACT, RUN, ExceptionCategory.IC1_BREACH, "IC-1 breached by 250.00",
                new IllegalStateException("first"));
            ExceptionRecord second = ExceptionRecord.captured(
                CONTRACT, RUN, ExceptionCategory.IC1_BREACH, "IC-1 breached by 250.00",
                new IllegalStateException("second"));

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first.cause()).isNotSameAs(second.cause());
        }

        @Test
        @DisplayName("the payload reference can be filled in later without losing the throwable")
        void payloadRefHandOffKeepsTheThrowable() {
            IllegalStateException thrown = new IllegalStateException("boom");
            ExceptionRecord stored = ExceptionRecord
                .captured(CONTRACT, RUN, ExceptionCategory.IC1_BREACH, "d", thrown)
                .withPayloadRef(PAYLOAD);

            assertThat(stored.payloadRef()).isEqualTo(PAYLOAD);
            assertThat(stored.cause()).as("the stack says which stage, the payload says on what")
                .isSameAs(thrown);
        }
    }

    @Nested
    @DisplayName("resolution and acceptance-with-approval are different things")
    class Working {

        @Test
        @DisplayName("resolution unblocks the close and lifts the quarantine")
        void resolutionLiftsTheQuarantine() {
            ExceptionRecord resolved = open().resolve(
                "fee.master.owner", "PROC-XX mapped INTEGRAL in FEE-2027.2");

            assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
            assertThat(resolved.blocksClose()).as("04 § 3: resolved does not block").isFalse();
            assertThat(resolved.quarantinesContract())
                .as("the defect is fixed, so a recomputation will produce a figure").isFalse();
            assertThat(resolved.resolvedBy()).isEqualTo("fee.master.owner");
        }

        @Test
        @DisplayName("acceptance unblocks the close and does NOT lift the quarantine")
        void acceptanceKeepsTheQuarantine() {
            // The distinction the queue exists to hold. Accepting is a decision to close without
            // this contract's figure. Treating it as a release would put the contract back into
            // the reported population with no figure behind it, which is the exact failure
            // 04 § 3 is guarding against.
            ExceptionRecord accepted = open().acceptWithApproval(
                "cfo.delegate", "immaterial: single 500.00 fee, closing 31 Mar");

            assertThat(accepted.status()).isEqualTo(ExceptionStatus.ACCEPTED_WITH_APPROVAL);
            assertThat(accepted.blocksClose()).as("explicitly accepted with approval").isFalse();
            assertThat(accepted.quarantinesContract())
                .as("nobody signed a figure into existence").isTrue();
            assertThat(accepted.status().defectFixed()).isFalse();
        }

        @Test
        @DisplayName("an anonymous acceptance is not an approval")
        void acceptanceNeedsAnApprover() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> open().acceptWithApproval("", "immaterial"))
                .withMessageContaining("an anonymous approval is not one");
        }

        @Test
        @DisplayName("an unsigned resolution is refused, and cites the resolution rule not the"
            + " acceptance rule")
        void resolutionNeedsASignatoryToo() {
            // 04 § 3's acceptance-with-approval clause is about closing over a defect that
            // stands. Quoting it at somebody who was recording a fix misnames what they did.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> open().resolve("", "mapped"))
                .withMessageContaining("a fix nobody signed cannot be evidence");
        }

        @Test
        @DisplayName("an unexplained acceptance is refused")
        void acceptanceNeedsAReason() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> open().acceptWithApproval("cfo.delegate", "   "))
                .withMessageContaining("why a close was allowed to proceed");
        }

        @Test
        @DisplayName("an OPEN record cannot half-carry a resolution")
        void openCarriesNoResolver() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExceptionRecord(
                    CONTRACT, RUN, ExceptionCategory.NO_SOLUTION, "d", PAYLOAD,
                    ExceptionStatus.OPEN, "somebody", "some note", null))
                .withMessageContaining("resolution and status move together");
        }
    }

    @Nested
    @DisplayName("the two categories that demote rather than stop")
    class DemotingCategories {

        @Test
        @DisplayName("STALE_EQUIVALENCE_TEST blocks the close without quarantining the contract")
        void staleEquivalenceTestDemotes() {
            // 03 § 10.2: the population is demoted to Tier 2 and computed properly, which is more
            // expensive and correct. A figure is produced, so there is nothing to quarantine.
            ExceptionRecord record = ExceptionRecord.raise(
                CONTRACT, RUN, ExceptionCategory.STALE_EQUIVALENCE_TEST,
                "equivalence test for pool RETAIL-PL last run 2026-09-30", PAYLOAD);

            assertThat(record.stopsTheContract()).isFalse();
            assertThat(record.quarantinesContract()).isFalse();
            assertThat(record.blocksClose())
                .as("a population that moved measurement basis is a close-gate item").isTrue();
        }

        @Test
        @DisplayName("POOL_BACKTEST_BREACH likewise")
        void poolBacktestBreachDemotes() {
            ExceptionRecord record = ExceptionRecord.raise(
                CONTRACT, RUN, ExceptionCategory.POOL_BACKTEST_BREACH,
                "pool AUTO-NEW failed Q4 back-test", PAYLOAD);

            assertThat(record.quarantinesContract()).isFalse();
            assertThat(record.blocksClose()).isTrue();
            assertThat(record.describe()).contains("contract still measured");
        }
    }

    @Nested
    @DisplayName("SolveStatus.routesToExceptionQueue is consumed here — 03 § 4.3")
    class SolveBridge {

        @Test
        @DisplayName("NO_SOLUTION becomes ExceptionCategory.NO_SOLUTION with the vector attached")
        void noSolution() {
            Optional<ExceptionRecord> raised = ExceptionRecord.ofSolve(
                CONTRACT, RUN, SolveStatus.NO_SOLUTION,
                "no sign change on the bracket ladder", PAYLOAD);

            assertThat(raised).as("03 § 4.3 sends a failed solve to the queue").isPresent();
            assertThat(raised.orElseThrow().category()).isEqualTo(ExceptionCategory.NO_SOLUTION);
            assertThat(raised.orElseThrow().detail())
                .as("the status is on the record, not only the prose")
                .contains("NO_SOLUTION").contains("bracket ladder");
            assertThat(raised.orElseThrow().payloadRef())
                .as("03 § 4.3: with the flow vector attached").isEqualTo(PAYLOAD);
            assertThat(raised.orElseThrow().quarantinesContract())
                .as("FR-402: never fall back to the contractual rate").isTrue();
        }

        @Test
        @DisplayName("MULTIPLE_ROOTS becomes ExceptionCategory.MULTIPLE_ROOTS")
        void multipleRoots() {
            Optional<ExceptionRecord> raised = ExceptionRecord.ofSolve(
                CONTRACT, RUN, SolveStatus.MULTIPLE_ROOTS, "three sign changes", PAYLOAD);

            assertThat(raised).isPresent();
            assertThat(raised.orElseThrow().category()).isEqualTo(ExceptionCategory.MULTIPLE_ROOTS);
        }

        @Test
        @DisplayName("SOLVED raises nothing")
        void solvedRaisesNothing() {
            assertThat(ExceptionRecord.ofSolve(
                CONTRACT, RUN, SolveStatus.SOLVED, "root at 0.1234", PAYLOAD)).isEmpty();
        }

        @Test
        @DisplayName("REQUIRES_REVIEW raises nothing — it routes for approval, not to the queue")
        void requiresReviewRaisesNothing() {
            // 03 § 4.4(2): the figure is computed and usable, so it is flagged rather than
            // blocked. Filing it here would block closes on contracts that have a rate.
            assertThat(ExceptionRecord.ofSolve(
                CONTRACT, RUN, SolveStatus.REQUIRES_REVIEW, "two roots in band", PAYLOAD))
                .isEmpty();
        }

        @Test
        @DisplayName("asking for the category of a non-routing status is loud, not a guess")
        void categoryOfNonRoutingStatusThrows() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExceptionRecord.categoryOf(SolveStatus.SOLVED))
                .withMessageContaining("does not route to the exception queue");

            assertThatThrownBy(() -> ExceptionRecord.categoryOf(SolveStatus.REQUIRES_REVIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routes for approval");
        }

        @Test
        @DisplayName("the two routing statuses map to the two solver categories of 04 § 3")
        void mappingIsTheOneTheDataModelStates() {
            assertThat(ExceptionRecord.categoryOf(SolveStatus.NO_SOLUTION))
                .isEqualTo(ExceptionCategory.NO_SOLUTION);
            assertThat(ExceptionRecord.categoryOf(SolveStatus.MULTIPLE_ROOTS))
                .isEqualTo(ExceptionCategory.MULTIPLE_ROOTS);
        }
    }
}
