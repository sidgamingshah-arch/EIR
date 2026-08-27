package com.crisil.eir.policy.replay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tonight's C-12 sample: which closed periods get replayed, and on what basis (07 § 4.1 control
 * C-12, FR-903).
 *
 * <h2>Rotation, not a pseudo-random draw</h2>
 *
 * <p>Selection is a rotation over the eligible periods in {@code period_id} order, offset by the
 * night's own epoch day. Two properties follow, and both are worth more here than randomness:
 *
 * <ul>
 *   <li><b>Reproducible.</b> Given the night and the population, the selection is recomputable by
 *       anyone. A sampled control whose sample nobody can reproduce is not evidence, and "we
 *       replayed a random period" is not a workpaper. Note that a seeded {@code Random} would also
 *       be reproducible — and would tie the sample to a JDK implementation detail, which is the
 *       kind of dependency V1's own header calls out as making a replay non-deterministic.</li>
 *   <li><b>Complete.</b> A rotation visits every eligible period once per {@code n} nights, where
 *       {@code n} is {@link #nightsToCoverEligible()}. A uniform random draw does not: over twelve
 *       nights of drawing one from twelve, the chance a given period is never picked is about 35%.
 *       For a control whose purpose is to find a defect somewhere in the recent book, guaranteed
 *       coverage beats an unbiased draw.</li>
 * </ul>
 *
 * <p>The offset is {@code floorMod(epochDay, size)} rather than {@code %}: {@code %} is
 * sign-preserving in Java and a pre-1970 date would index negatively. The dates in play are all
 * 2027 and later, so this cannot fire — it is written this way because an index that is only
 * correct for dates after an arbitrary epoch is the sort of thing that survives until somebody
 * back-tests the sampler against a historical calendar.
 *
 * <h2>Eligibility, and how it goes stale</h2>
 *
 * <p>A period is eligible if it had ended by tonight and it is within
 * {@link ReplaySamplingBasis#lookbackPeriods()} periods of tonight's own period. The first clause is
 * arithmetic; the second is the policy, and it is the one that can silently exclude everything —
 * see {@link ReplaySamplingBasis} and {@link #isInert()}.
 *
 * @param nightOf    the night this sample is for; also the rotation offset
 * @param basis      the stated basis
 * @param population every closed period offered to the sampler
 * @param eligible   the subset the basis admits, ascending by period
 * @param selected   what gets replayed tonight, ascending by period
 */
public record ReplaySample(
    LocalDate nightOf,
    ReplaySamplingBasis basis,
    List<ClosedPeriod> population,
    List<ClosedPeriod> eligible,
    List<ClosedPeriod> selected) {

    public ReplaySample {
        Objects.requireNonNull(nightOf, "nightOf");
        Objects.requireNonNull(basis, "basis");
        population = List.copyOf(population);
        eligible = List.copyOf(eligible);
        selected = List.copyOf(selected);
    }

    /**
     * Draws tonight's sample.
     *
     * @param nightOf        the night the control runs
     * @param basis          the stated sampling basis
     * @param closedPeriods  every closed period on file; duplicates by period id are refused,
     *                       because a population holding one period twice makes the rotation's
     *                       coverage claim false and the selection depend on load order
     */
    public static ReplaySample forNight(
        LocalDate nightOf, ReplaySamplingBasis basis, Collection<ClosedPeriod> closedPeriods) {

        Objects.requireNonNull(nightOf, "nightOf");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(closedPeriods, "closedPeriods");

        // Ascending by period id, which is monotonic in reporting order by construction of the
        // YYYYMM encoding. A total, data-only ordering is what makes the rotation reproducible;
        // insertion order would make tonight's sample a function of how the query happened to
        // return.
        List<ClosedPeriod> ordered = new ArrayList<>(closedPeriods);
        ordered.sort(Comparator.comparingInt(ClosedPeriod::periodId));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).periodId() == ordered.get(i - 1).periodId()) {
                throw new IllegalArgumentException(
                    "period " + ordered.get(i).periodId() + " appears twice in the replay"
                        + " population; a duplicate makes the rotation's coverage claim false and"
                        + " ties tonight's selection to load order");
            }
        }

        int tonightOrdinal = nightOf.getYear() * 12 + (nightOf.getMonthValue() - 1);
        List<ClosedPeriod> eligible = new ArrayList<>();
        for (ClosedPeriod period : ordered) {
            // Not yet ended: a period cannot be replayed before the facts it reports have
            // happened. Not merely "not yet closed" — ClosedPeriod is the closed case already.
            if (period.endDate().isAfter(nightOf)) {
                continue;
            }
            if (tonightOrdinal - period.monthOrdinal() > basis.lookbackPeriods()) {
                continue;
            }
            eligible.add(period);
        }

        List<ClosedPeriod> selected = new ArrayList<>();
        if (!eligible.isEmpty() && basis.periodsPerNight() > 0) {
            int take = Math.min(basis.periodsPerNight(), eligible.size());
            int offset = (int) Math.floorMod(nightOf.toEpochDay(), eligible.size());
            for (int i = 0; i < take; i++) {
                selected.add(eligible.get((offset + i) % eligible.size()));
            }
            selected.sort(Comparator.comparingInt(ClosedPeriod::periodId));
        }

        return new ReplaySample(nightOf, basis, ordered, eligible, selected);
    }

    /**
     * What tonight's sample amounts to — and specifically whether selecting nothing was a fact or a
     * defect.
     *
     * <p>The distinction is the whole reason this is not a boolean. A book with no closed period
     * inside the lookback has nothing to replay and that is correct; a book with closed periods the
     * sampler declined to select has a control that has stopped running.
     */
    public SampleOutcome outcome() {
        if (!selected.isEmpty()) {
            return SampleOutcome.PERIOD_SELECTED;
        }
        return population.isEmpty()
            ? SampleOutcome.NOTHING_CLOSED_YET
            : SampleOutcome.CONTROL_INERT;
    }

    /**
     * Whether the control has silently stopped running: closed periods exist and none was selected.
     *
     * <p><b>Why this is not published as an {@link com.crisil.eir.domain.InvariantResult}.</b> The
     * obvious move is a DT-1 failure, and it would be wrong. DT-1 states "a re-run of a closed
     * period reproduces published figures bit-identically", and its deviation here is a count of
     * discrepancies found by a comparison. An inert sampler found no discrepancies because it
     * performed no comparison — so a DT-1 breach would report "the replay differed" about a replay
     * that never happened, and the count it carried would be a count of something else. That is
     * precisely the two-claims-one-id defect {@code InvariantResult.conjunction} records having been
     * found three times in this engine, and that {@code PolicyVersionRegistry.policyResolvableOn}
     * avoided by taking PV-1 rather than borrowing DT-1.
     *
     * <p>Publishing a DT-1 <em>pass</em> would be worse: a tautological pass on a control that did
     * not run, which is the exact shape this codebase treats as worse than an absent control.
     *
     * <p>So the condition is surfaced as data, loudly and by name: {@link #outcome()},
     * {@link #inertnessWarning()} and {@link #describe()}, with
     * {@link SampleOutcome#isDefect()} for a caller routing it to the exception queue. What it
     * actually wants is an invariant id of its own — a claim about C-12 <em>coverage</em> rather
     * than about replay fidelity — and none exists; adding one is not this unit's call.
     */
    public boolean isInert() {
        return outcome() == SampleOutcome.CONTROL_INERT;
    }

    /** Whether tonight's control replayed anything. */
    public boolean controlRan() {
        return outcome().controlRan();
    }

    /**
     * The reason the control is inert, naming which of the two causes it is, or empty if it ran.
     *
     * <p>The two causes read completely differently to whoever has to fix it — one is a number
     * somebody set to zero, the other is a window that was right when it was chosen — and
     * distinguishing them from a message that mentions neither is the slow part of the
     * investigation. {@code PolicyVersionRegistry.describeMissingSuccessor} separates its two causes
     * for the same reason.
     */
    public Optional<String> inertnessWarning() {
        if (!isInert()) {
            return Optional.empty();
        }
        String preamble = "C-12 selected no period to replay on " + nightOf + " although "
            + population.size() + " closed period(s) are on file (most recent "
            + population.get(population.size() - 1).periodId() + "): ";
        if (basis.periodsPerNight() == 0) {
            return Optional.of(preamble
                + "the basis replays 0 periods a night. The nightly job will report a clean night"
                + " every night for having done nothing — a control that cannot fail reads as"
                + " coverage and is not.");
        }
        return Optional.of(preamble
            + "the lookback of " + basis.lookbackPeriods() + " period(s) from " + nightOf
            + " excludes every closed period on file. The window was presumably right when it was"
            + " chosen; it has gone stale, and C-12 has stopped running without failing.");
    }

    /**
     * How many nights the rotation needs to replay every eligible period once.
     *
     * <p>The coverage figure the basis is chosen against, and the number that makes the rotation
     * defensible against a random draw: it is finite and exact, where a draw's is neither.
     * {@code ceil(eligible / periodsPerNight)}, computed in integers — zero when nothing is eligible
     * or nothing is sampled, which is {@link #isInert()}'s territory rather than a coverage claim.
     */
    public int nightsToCoverEligible() {
        if (eligible.isEmpty() || basis.periodsPerNight() == 0) {
            return 0;
        }
        return (eligible.size() + basis.periodsPerNight() - 1) / basis.periodsPerNight();
    }

    /** A one-line audit sentence: the basis, the population, and what it chose. */
    public String describe() {
        StringBuilder sentence = new StringBuilder("C-12 sample for ").append(nightOf)
            .append(" — ").append(basis.describe())
            .append("; population ").append(population.size())
            .append(", eligible ").append(eligible.size())
            .append(", selected ");
        if (selected.isEmpty()) {
            sentence.append("none (").append(outcome()).append(')');
            inertnessWarning().ifPresent(warning -> sentence.append(". ").append(warning));
            return sentence.toString();
        }
        List<Integer> ids = selected.stream().map(ClosedPeriod::periodId).toList();
        return sentence.append(ids)
            .append("; full eligible coverage in ").append(nightsToCoverEligible())
            .append(" nights").toString();
    }
}
