package com.crisil.eir.batch;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The policy versions a run cited, resolved once at the run's own boundary (04 § 2.13, FR-903).
 *
 * <h2>Why the run resolves this and not the replay harness</h2>
 *
 * <p>{@code ReplayUseCase}'s javadoc is emphatic about the half of DT-1 this feeds: "It does not
 * resolve the replay's policy stamps on the run's behalf … Substituting the harness's own resolution
 * for the run's would make the policy leg compare the harness against itself — the exact defect
 * {@code ReplayComparison} records having shipped, where 'the policy half of DT-1 was a no-op that
 * reported a pass'." The registry is read there as the <em>expectation</em>; what the run
 * <em>cited</em> has to come from the run. This class is the run citing it.
 *
 * <p>The resolution is the same one {@code MonthEndRun.policyVersionsConsulted()} performs: the
 * registry at the request's system-time boundary, resolved at the request's business date. Two forms
 * of one answer are needed — {@code RunAggregate} takes the run record's {@code KIND=id} strings and
 * {@code RunOutput} takes a typed map — and rather than parse one out of the other, both are produced
 * here from one resolution and {@link #refuseUnlessTheRunAgreesWithItself} checks they agree.
 *
 * <h2>The control, and the input that fails it</h2>
 *
 * <p>A partitioned run resolves the policy timeline once per partition, inside each
 * {@code MonthEndRun}, and once more here for the run record. That is several independent
 * resolutions of one question, and they can disagree: a {@code PolicySource} that consults a cache
 * refreshed on a timer, or one that resolves against {@code LocalDate.now()} rather than against the
 * boundary it was handed, answers differently at 23:59 and at 00:01. A close whose partitions worked
 * under {@code FEE-2027.1} and whose run record says {@code FEE-2027.2} has published figures under
 * a rule the record denies, and <em>nothing downstream can see it</em>: the figures are internally
 * consistent, SL-1 ties, and DT-1 compares the record against a replay that also resolves at the
 * boundary and therefore also says {@code FEE-2027.2}. It reproduces the right number from the wrong
 * rule — {@code ReplayVerification} calls that "the most valuable failure available here" and this is
 * the run-side half of it.
 *
 * <p>So the stamps every partition reported are collected and compared against the run's own
 * resolution. {@code RunPolicyStampsTest.aPolicySourceThatAnswersDifferentlyPerPartition} builds a
 * source that returns one timeline to the first caller and another to the second, and the run is
 * refused.
 */
public final class RunPolicyStamps {

    private RunPolicyStamps() {
    }

    /**
     * The versions in force at the run's boundary, per kind.
     *
     * <p>Iterated over {@link PolicyKind}'s own declaration order rather than the registry map's, so
     * two runs with identical inputs produce an identically-ordered stamp — FR-903's byte-identical
     * output, applied to the run record. {@code MonthEndRun} makes the same choice for the same
     * reason, and {@code RunOutput} makes it again with an {@code EnumMap}.
     *
     * @throws IllegalStateException where {@code PolicySource} returns no registry; FR-903's second
     *                               half, "under the policy then in force", cannot be asserted
     *                               against a timeline nobody supplied, and a run stamping nothing is
     *                               adverse rather than neutral — see {@code RunOutput}
     */
    public static Map<PolicyKind, String> consultedBy(RunRequest request) {
        Objects.requireNonNull(request, "request");
        PolicyVersionRegistry registry = request.policy().policyVersions(request.boundary());
        if (registry == null) {
            throw new IllegalStateException(
                "PolicySource returned no registry at " + request.boundary().recordedAsAt()
                    + " for run " + request.runId() + "; the run cannot say which rule it worked"
                    + " under, and a run that reports no stamps fails DT-1 with every kind the"
                    + " registry says governed the period reported as not consulted");
        }
        Map<PolicyKind, PolicyVersion> inForce =
            registry.inForceOn(request.boundary().businessAsOf());
        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        for (PolicyKind kind : PolicyKind.values()) {
            PolicyVersion version = inForce.get(kind);
            if (version != null) {
                // Absent, never blank, for a kind the run did not consult. RunOutput refuses a blank
                // stamp: "absent means not consulted, blank means a stamp somebody failed to write,
                // and one answer to 'which kinds did this run consult' is all a comparison can use".
                stamps.put(kind, version.id());
            }
        }
        return Map.copyOf(stamps);
    }

    /**
     * The run record's form of the same stamps: {@code KIND=id}, in {@link PolicyKind} order.
     *
     * <p>The format {@code MonthEndRun.policyVersionsConsulted()} produces, matched exactly, because
     * {@link #refuseUnlessTheRunAgreesWithItself} compares the two as strings and a format that
     * drifted would make the control fail for a reason that has nothing to do with policy.
     */
    public static List<String> asRunRecord(Map<PolicyKind, String> stamps) {
        Objects.requireNonNull(stamps, "stamps");
        List<String> stamped = new ArrayList<>(stamps.size());
        for (PolicyKind kind : PolicyKind.values()) {
            String id = stamps.get(kind);
            if (id != null) {
                stamped.add(kind + "=" + id);
            }
        }
        return List.copyOf(stamped);
    }

    /**
     * Refuses a run whose partitions did not all work under the run record's own policy reading.
     *
     * <p>See the class javadoc for the control and the input that fails it. Every partition's
     * {@code RunAggregate.policyVersionIds()} is a {@code KIND=id} list produced by
     * {@code MonthEndRun} from the same {@code PolicySource} at the same boundary; agreement is
     * therefore the expected outcome and a disagreement is a port that does not honour its boundary.
     *
     * <p><b>Refused, not reported.</b> The figures a disagreeing run produced are figures under a
     * rule the run record denies, and there is no way to say which of the two readings the numbers
     * came from — each partition may have used either. A reported breach would leave the close to
     * decide whether to publish, and there is nothing to decide with.
     *
     * <p><b>And it refuses a run that presented no stamps to compare.</b> The loop below passes
     * vacuously over an empty list, which would make this a control that cannot fail — in a module
     * whose stated purpose includes not shipping any more of those. The input that produces it is
     * real: a durable {@link RunProgressStore} that persists results and forgets stamps disables the
     * whole check silently, and the run then publishes under whatever the last resolution happened
     * to be. So the number of stamp lists has to match the number of partitions that committed.
     *
     * @param runId      the run
     * @param runRecord  the run's own resolution, from {@link #asRunRecord}
     * @param committed  the partitions that committed, in the store's own order
     * @param perPartition each partition's stamps, positionally aligned with {@code committed}
     */
    public static void refuseUnlessTheRunAgreesWithItself(
        String runId,
        List<String> runRecord,
        List<PartitionKey> committed,
        List<List<String>> perPartition) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runRecord, "runRecord");
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(perPartition, "perPartition");
        if (perPartition.size() != committed.size()) {
            throw new IllegalStateException(
                "run " + runId + " committed " + committed.size() + " partition(s) and reported "
                    + perPartition.size() + " set(s) of policy stamps; a partition whose reading was"
                    + " not retained cannot be compared against the run record, and a comparison"
                    + " over the partitions that happen to have kept theirs is a control that"
                    + " passes by having looked at less");
        }
        for (int i = 0; i < perPartition.size(); i++) {
            List<String> partition = perPartition.get(i);
            if (!runRecord.equals(partition)) {
                throw new IllegalStateException(
                    "run " + runId + " stamped " + runRecord + " on its run record and partition "
                        + committed.get(i).describe() + " worked under " + partition
                        + "; the two readings resolve the same registry at the same boundary, so a"
                        + " disagreement is a PolicySource that does not honour the boundary it is"
                        + " handed — and figures published under a rule the run record denies"
                        + " reproduce the right number from the wrong rule, which every downstream"
                        + " control agrees with");
            }
        }
    }
}
