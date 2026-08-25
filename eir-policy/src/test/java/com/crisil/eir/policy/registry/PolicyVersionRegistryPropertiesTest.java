package com.crisil.eir.policy.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Two structural properties of select-by-date, over arbitrary version timelines.
 *
 * <p>These do not restate the selection algorithm and no expected value is taken from a run of
 * it. Each property is a claim that follows from what "in force on a date" <em>means</em> — a
 * version governs from its own effective date until the next one starts — and each would be
 * violated by a plausible wrong implementation:
 *
 * <ul>
 *   <li><b>Monotonicity.</b> Time moving forward can never move the governing version backward.
 *       An implementation that picked the <em>nearest</em> effective date in either direction, or
 *       that sorted by id, or that let a hash order leak through, breaks this on some timeline
 *       while passing every single-date example.</li>
 *   <li><b>Maximality.</b> Nothing that had already started is later than the version chosen.
 *       This is the defining property of "in force"; an off-by-one on the boundary — treating
 *       {@code effectiveFrom} as exclusive, or admitting a version that starts tomorrow — shows
 *       up here as a version the check can name.</li>
 * </ul>
 *
 * <p>Timelines are built from distinct day offsets, so no two approved versions ever share an
 * effective date: the clash case is refused at construction and is covered by example in
 * {@link PolicyVersionRegistryTest}, not here.
 */
class PolicyVersionRegistryPropertiesTest {

    /** An arbitrary but fixed epoch — the ACPIR go-live year, so the dates read realistically. */
    private static final LocalDate EPOCH = LocalDate.of(2027, 1, 1);

    /**
     * Builds the realistic shape of a timeline: every version superseded except the newest,
     * which is in force. All are operative, so all are resolvable — the DT-1 requirement that
     * history survives supersession.
     */
    private static PolicyVersionRegistry timeline(List<Integer> dayOffsets) {
        TreeSet<Integer> distinctAscending = new TreeSet<>(dayOffsets);
        List<PolicyVersion> versions = new ArrayList<>();
        int index = 0;
        for (Integer offset : distinctAscending) {
            boolean newest = index == distinctAscending.size() - 1;
            LocalDate effectiveFrom = EPOCH.plusDays(offset);
            versions.add(new PolicyVersion(
                "FEE-" + offset, PolicyKind.FEE_RULE_SET, "version at day " + offset,
                effectiveFrom, "policy.author", "accounting.policy.owner",
                effectiveFrom.minusDays(10),
                newest ? PolicyVersionStatus.EFFECTIVE : PolicyVersionStatus.SUPERSEDED));
            index++;
        }
        return PolicyVersionRegistry.of(versions);
    }

    @Property(tries = 300)
    void theGoverningVersionNeverMovesBackwardsAsTheDateAdvances(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 400) Integer> dayOffsets,
        @ForAll @IntRange(min = 0, max = 500) int earlierOffset,
        @ForAll @IntRange(min = 0, max = 60) int gap) {

        PolicyVersionRegistry registry = timeline(dayOffsets);
        LocalDate earlier = EPOCH.plusDays(earlierOffset);
        LocalDate later = earlier.plusDays(gap);

        Optional<PolicyVersion> atEarlier = registry.inForceOn(PolicyKind.FEE_RULE_SET, earlier);
        Optional<PolicyVersion> atLater = registry.inForceOn(PolicyKind.FEE_RULE_SET, later);

        if (atEarlier.isPresent()) {
            // Once a version has taken effect it is never un-taken: a later date must resolve to
            // something. An empty answer here would mean a policy that expired on its own.
            assertThat(atLater)
                .as("%s resolved on %s, so %s must resolve too", atEarlier.get().id(), earlier, later)
                .isPresent();
            assertThat(atLater.get().effectiveFrom())
                .as("moving from %s to %s must not move the governing version back from %s to %s",
                    earlier, later, atEarlier.get().id(), atLater.map(PolicyVersion::id).orElse("-"))
                .isAfterOrEqualTo(atEarlier.get().effectiveFrom());
        }
    }

    @Property(tries = 300)
    void nothingThatHadStartedIsLaterThanTheVersionChosen(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 400) Integer> dayOffsets,
        @ForAll @IntRange(min = 0, max = 500) int queryOffset) {

        PolicyVersionRegistry registry = timeline(dayOffsets);
        LocalDate date = EPOCH.plusDays(queryOffset);
        List<PolicyVersion> held = registry.historyOf(PolicyKind.FEE_RULE_SET);
        Optional<PolicyVersion> chosen = registry.inForceOn(PolicyKind.FEE_RULE_SET, date);

        // The defining property, stated over the versions on file rather than recomputed by the
        // same traversal: whatever is chosen has started, and nothing that has started is later.
        for (PolicyVersion candidate : held) {
            if (chosen.isEmpty()) {
                // Nothing resolves only when nothing had started — never because a version was
                // overlooked. This is the check that catches the tempting "return the earliest"
                // shortcut inverted: an empty answer with a started version on file.
                assertThat(candidate.effectiveFrom())
                    .as("%s had started by %s, so the date cannot resolve to nothing",
                        candidate.id(), date)
                    .isAfter(date);
                continue;
            }
            if (!candidate.effectiveFrom().isAfter(date)) {
                assertThat(candidate.effectiveFrom())
                    .as("%s started on %s and was not chosen over %s for %s",
                        candidate.id(), candidate.effectiveFrom(), chosen.get().id(), date)
                    .isBeforeOrEqualTo(chosen.get().effectiveFrom());
            }
        }
        chosen.ifPresent(version -> assertThat(version.effectiveFrom())
            .as("the chosen version cannot start after the date it governs")
            .isBeforeOrEqualTo(date));
    }
}
