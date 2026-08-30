package com.crisil.eir.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The run record's policy stamps, and the control that the run's partitions all worked under them
 * (04 § 2.13, FR-903, DT-1).
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>A partitioned run resolves the policy timeline once per partition, inside each
 * {@code MonthEndRun}, and once more for the run record. That is several independent answers to one
 * question, and a {@code PolicySource} that resolves against {@code LocalDate.now()} rather than
 * against the boundary it is handed — or one whose cache refreshes on a timer — answers differently
 * at 23:59 and at 00:01. A close whose partitions worked under {@code POL-RT-2028.1} and whose run
 * record says {@code POL-RT-2029.1} has published figures under a rule the record denies.
 *
 * <p><b>Nothing else in the engine can see it.</b> The figures are internally consistent; SL-1 ties;
 * and DT-1 compares the run record against a replay that resolves at the same boundary and therefore
 * cites the same version the record does. It reproduces the right number from the wrong rule —
 * {@code ReplayVerification} calls that "the most valuable failure available here", and this is the
 * run-side half of it.
 */
@DisplayName("The run's policy stamps")
class RunPolicyStampsTest {

    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 3, 15);

    /** The timeline every well-behaved fixture in this module resolves to. */
    private static final PolicyVersionRegistry IN_FORCE = BatchFixtures.POLICY;

    /** A later reading of the same kind — what a source resolving at "now" would return. */
    private static final PolicyVersionRegistry SUPERSEDING = PolicyVersionRegistry.of(new PolicyVersion(
        "POL-RT-2029.1", PolicyKind.ROUTING_TABLE, "the 2029 reading",
        LocalDate.of(2027, 4, 1), "policy.author", "policy.owner",
        APPROVED_ON, PolicyVersionStatus.EFFECTIVE));

    @Nested
    @DisplayName("Resolving the stamps")
    class Resolving {

        @Test
        @DisplayName("the version in force at the run's boundary, in PolicyKind order")
        void theVersionInForceAtTheBoundary() {
            RunRequest request = BatchFixtures.request(
                "RUN-202805-01", BatchFixtures.standardPopulation(), Set.of());

            Map<PolicyKind, String> stamps = RunPolicyStamps.consultedBy(request);

            assertThat(stamps).containsExactly(
                Map.entry(PolicyKind.ROUTING_TABLE, "POL-RT-2028.1"));
            // The run record's format, matched to MonthEndRun's exactly — the two are compared as
            // strings, so a format that drifted would make the control fail for a reason that has
            // nothing to do with policy.
            assertThat(RunPolicyStamps.asRunRecord(stamps))
                .containsExactly("ROUTING_TABLE=POL-RT-2028.1");
        }

        @Test
        @DisplayName("a PolicySource that supplies no registry is refused, not defaulted")
        void noRegistryIsRefused() {
            RunRequest request = new RunRequest(
                "RUN-202805-01", BatchFixtures.PERIOD_ID, BatchFixtures.BOOK_ID,
                BatchFixtures.boundary(),
                new BatchFixtures.FakeContracts(List.of("LN-1")),
                new BatchFixtures.FakeState(Set.of()),
                new BatchFixtures.FakeCoreBanking(),
                new BatchFixtures.FakeGeneralLedger(),
                boundary -> null);

            // Not an empty map. RunOutput's javadoc: a run that reports no stamps is ADVERSE — every
            // kind the registry says governed the period becomes a POLICY_KIND_NOT_CONSULTED finding
            // and DT-1 fails. Defaulting here would make that failure arrive at C-12 instead of at
            // the run that could not say what rule it read.
            assertThatThrownBy(() -> RunPolicyStamps.consultedBy(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot say which rule it worked under");
        }
    }

    @Nested
    @DisplayName("The run has to agree with itself")
    class SelfAgreement {

        private static final List<PartitionKey> THREE_PARTITIONS = List.of(
            new PartitionKey(BatchFixtures.RETAIL, BatchFixtures.MUM, 0),
            new PartitionKey(BatchFixtures.RETAIL, BatchFixtures.MUM, 1),
            new PartitionKey(BatchFixtures.CORP, BatchFixtures.MUM, 0));

        @Test
        @DisplayName("identical stamps across every partition are accepted")
        void agreementIsAccepted() {
            List<String> runRecord = List.of("ROUTING_TABLE=POL-RT-2028.1");

            // No exception is the assertion.
            RunPolicyStamps.refuseUnlessTheRunAgreesWithItself(
                "RUN-202805-01", runRecord, THREE_PARTITIONS,
                List.of(runRecord, runRecord, runRecord));
        }

        @Test
        @DisplayName("a partition that worked under a different version is refused, and is named")
        void aDisagreeingPartitionIsRefused() {
            assertThatThrownBy(() -> RunPolicyStamps.refuseUnlessTheRunAgreesWithItself(
                "RUN-202805-01",
                List.of("ROUTING_TABLE=POL-RT-2029.1"),
                THREE_PARTITIONS.subList(0, 2),
                List.of(
                    List.of("ROUTING_TABLE=POL-RT-2028.1"),
                    List.of("ROUTING_TABLE=POL-RT-2029.1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not honour the boundary")
                .hasMessageContaining("the right number from the wrong rule")
                // Named in the units a controller thinks in, not as "partition 0 of 2".
                .hasMessageContaining("product RETAIL-EMI, entity IN-MUM, shard 0");
        }

        @Test
        @DisplayName("a run that committed partitions and retained no stamps is refused")
        void stampsThatWereNotRetainedAreRefused() {
            // The control's own could-not-fail case. The loop compares pairwise, so an empty stamp
            // list passes vacuously — and a durable RunProgressStore that persists results and
            // forgets stamps produces exactly that, silently disabling the whole policy-agreement
            // check for every close after the migration.
            assertThatThrownBy(() -> RunPolicyStamps.refuseUnlessTheRunAgreesWithItself(
                "RUN-202805-01",
                List.of("ROUTING_TABLE=POL-RT-2028.1"),
                THREE_PARTITIONS,
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("committed 3 partition(s) and reported 0 set(s)")
                .hasMessageContaining("passes by having looked at less");
        }

        @Test
        @DisplayName("a PolicySource that answers differently mid-run fails the run")
        void aSourceThatDriftsMidRunFailsTheRun() {
            // THE failing input, end to end. The source hands the first two partitions the reading in
            // force and everything after them a superseding one — a cache that refreshed while the
            // close was running, or a resolution against the wall clock as it crossed a boundary.
            //
            // Four partitions and one run-record resolution, in that order under a SyncTaskExecutor,
            // so the run record is resolved last and cites the NEW version while partitions 0 and 1
            // cite the old one.
            DriftingPolicy drifting = new DriftingPolicy(2);
            BatchFixtures.Harness harness = BatchFixtures.harness();
            RunRequest request = new RunRequest(
                "RUN-202805-DRIFT", BatchFixtures.PERIOD_ID, BatchFixtures.BOOK_ID,
                BatchFixtures.boundary(),
                new BatchFixtures.FakeContracts(BatchFixtures.standardPopulation()),
                new BatchFixtures.FakeState(Set.of()),
                new BatchFixtures.FakeCoreBanking(),
                new BatchFixtures.FakeGeneralLedger(),
                drifting);

            Throwable thrown = catchThrowable(() -> harness.runner().run(request));

            assertThat(thrown)
                .as("the run must not publish figures under a rule its own record denies")
                .isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getSuppressed())
                .anySatisfy(cause -> assertThat(cause)
                    .hasMessageContaining("does not honour the boundary"));
            assertThat(drifting.calls.get())
                .as("four partitions plus the run record")
                .isGreaterThanOrEqualTo(5);

            // And the aggregate is NOT written, because the disagreement is detected before it is
            // built: there is no single reading to stamp on it. That is the one thing in the
            // aggregate step that refuses rather than reports, and the reason is that the figures
            // cannot be attributed to either reading — each partition may have used either.
            assertThat(harness.store().completion("RUN-202805-DRIFT")).isEmpty();
        }
    }

    /** A {@code PolicySource} whose answer changes after {@code flipAfter} calls. */
    private static final class DriftingPolicy implements PolicySource {

        final AtomicInteger calls = new AtomicInteger();
        private final int flipAfter;

        DriftingPolicy(int flipAfter) {
            this.flipAfter = flipAfter;
        }

        @Override
        public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
            return calls.incrementAndGet() <= flipAfter ? IN_FORCE : SUPERSEDING;
        }
    }
}
