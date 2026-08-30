package com.crisil.eir.batch;

import com.crisil.eir.application.replay.NightlyReplayReport;
import com.crisil.eir.application.replay.ReplayVerification;
import com.crisil.eir.domain.InvariantResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One night of control C-12, as the schedule reports it: the night, the report, and why the night is
 * or is not evidence (07 § 4.1 C-12, FR-903, invariant DT-1).
 *
 * <h2>The reading of the report that is wrong</h2>
 *
 * <p>{@code NightlyReplayReport.dtOne()} is an {@code Optional<InvariantResult>}, and the tempting
 * scheduler is four lines long: get the DT-1 result, ask {@code satisfied()}, log it, done. Every one
 * of those four lines is a defect.
 *
 * <ul>
 *   <li><b>An empty night has no DT-1 result, and {@code Optional.map(…).orElse(true)} makes it a
 *       pass.</b> {@code NightlyReplayReport}'s own javadoc spells out why the absence is the
 *       answer: "a <b>pass</b> is a tautology on a control that did not run, which this codebase
 *       treats as worse than an absent control because it reads as coverage." It goes further and
 *       names the exact hazard a caller falls into: "{@code allMatch(InvariantResult::satisfied)}
 *       over an empty list is {@code true}, so a caller must ask {@link
 *       NightlyReplayReport#controlRan()} and not only look at the invariants." This type asks.</li>
 *   <li><b>{@code dtOne().satisfied()} is not the same claim as reproduction.</b>
 *       {@code ReplayVerification.provesReproduction()} is {@code dtOne().satisfied() &&
 *       coverage.provesFidelity()}, and the second half is load-bearing: "DT-1 passes over a period
 *       that published nothing — correctly, because nothing failed — and a caller collecting C-12
 *       evidence would otherwise count an empty period as a period that reproduced." A period whose
 *       whole population was quarantined on both sides therefore passes DT-1 and proves nothing, and
 *       a night made only of such periods would be reported clean by a scheduler reading
 *       {@code satisfied()}. This type reads {@code provesReproduction()} — through
 *       {@link NightlyReplayReport#allReproduced()}, which is {@code controlRan()} and every
 *       verification proving reproduction.</li>
 * </ul>
 *
 * <p>So there are three outcomes and not two: the control did not run; it ran and something did not
 * reproduce; it ran and everything did. Only the third is a clean night, and the first two read
 * completely differently to whoever has to act on them — which is why {@link #blockingReasons()} is
 * a list of sentences rather than a boolean.
 *
 * @param nightOf         the night the control ran for — an input to the control, never a clock read
 * @param report          the night's sample and its verifications
 * @param blockingReasons why this night is not evidence that any period reproduces; empty where it is
 */
public record NightlyReplayOutcome(
    LocalDate nightOf, NightlyReplayReport report, List<String> blockingReasons) {

    public NightlyReplayOutcome {
        Objects.requireNonNull(nightOf, "nightOf");
        Objects.requireNonNull(report, "report");
        blockingReasons = List.copyOf(Objects.requireNonNull(blockingReasons, "blockingReasons"));
    }

    /**
     * Reads a night's report into an outcome.
     *
     * <p><b>What input makes this produce a non-clean night?</b> Three, and each is constructed in
     * {@code NightlyReplayScheduleTest}:
     *
     * <ul>
     *   <li>a night with no candidates at all, or with a basis that selected nothing — the control
     *       did not run, and the reason comes from {@code report.coverageWarning()} which
     *       distinguishes "nothing closed yet" from "the basis is dialled to zero";</li>
     *   <li>a night in which a replayed period's figures differ from the published ones, or its
     *       policy stamps do — {@code dtOne()} fails;</li>
     *   <li><b>a night in which every DT-1 result passes and no figure was compared</b> — every
     *       contract in the replayed period quarantined on both sides, so nothing differed and
     *       nothing was measured. This is the leg a scheduler reading {@code satisfied()} misses,
     *       and mutating {@code provesReproduction()} to {@code dtOne().satisfied()} below is what
     *       the test proves it can catch.</li>
     * </ul>
     */
    public static NightlyReplayOutcome of(LocalDate nightOf, NightlyReplayReport report) {
        Objects.requireNonNull(nightOf, "nightOf");
        Objects.requireNonNull(report, "report");
        List<String> reasons = new ArrayList<>();

        if (!report.controlRan()) {
            // Stated first and on its own, because it explains why everything else is silent: with
            // no verifications there is no DT-1 result, no breach and no evidence. The report's own
            // coverageWarning() names the cause — a stale lookback window, a basis dialled to zero,
            // or a book with nothing closed yet — and those read completely differently to whoever
            // has to fix it.
            reasons.add(report.coverageWarning().orElse(
                "C-12 replayed nothing on " + nightOf + " and gave no reason; tonight is not"
                    + " evidence that any period reproduces"));
            return new NightlyReplayOutcome(nightOf, report, reasons);
        }

        for (ReplayVerification verification : report.verifications()) {
            // provesReproduction(), NOT dtOne().satisfied(). See the class javadoc: the second
            // clause of provesReproduction is what separates "the control found no exception" from
            // "the control ran", and a period whose population was entirely quarantined satisfies
            // DT-1 while proving nothing.
            if (verification.provesReproduction()) {
                continue;
            }
            InvariantResult dtOne = verification.dtOne();
            if (dtOne.satisfied()) {
                reasons.add("period " + verification.periodId() + " passed DT-1 and compared no"
                    + " figure (coverage " + verification.coverage() + "): "
                    + verification.describe() + ". A comparison that compared nothing is not a"
                    + " reproduction, it is an absence of measurement");
            } else {
                reasons.add("period " + verification.periodId() + " did not reproduce: "
                    + dtOne.detail() + " (deviation " + dtOne.deviation().toPlainString() + ")");
            }
        }
        return new NightlyReplayOutcome(nightOf, report, reasons);
    }

    /**
     * Whether tonight is evidence that the periods it replayed reproduce.
     *
     * <p>Not "DT-1 passed". See the class javadoc.
     */
    public boolean reportsCleanNight() {
        return blockingReasons.isEmpty();
    }

    /** Whether the control replayed anything at all. */
    public boolean controlRan() {
        return report.controlRan();
    }

    /**
     * The night's invariant results — one DT-1, or none.
     *
     * <p>Empty on a night that replayed nothing, which is {@code NightlyReplayReport}'s choice and
     * is passed through unchanged rather than being filled in with a pass or a fail. A caller
     * conjoining several nights' results must ask {@link #controlRan()} as well; that is stated on
     * the report and it is worth restating here because this is the type an operator's report is
     * built from.
     */
    public List<InvariantResult> invariantResults() {
        return report.invariantResults();
    }

    /**
     * The periods tonight replayed that did not demonstrably reproduce.
     *
     * <p><b>A wider set than {@code NightlyReplayReport.breaches()}, on purpose.</b> That method
     * filters on {@code !dtOne().satisfied()} — the periods where the comparison found a
     * discrepancy — which is the right definition of a DT-1 breach and the wrong one for "what does
     * an operator have to look at tonight". A period that passed DT-1 having compared no figure is
     * not a breach and is also not evidence, and it is the case a scheduler most easily loses: no
     * red anywhere, and the control measured nothing. Both are here, and {@link #blockingReasons()}
     * keeps them distinguishable in words.
     */
    public List<ReplayVerification> periodsThatDidNotReproduce() {
        return report.verifications().stream()
            .filter(verification -> !verification.provesReproduction())
            .toList();
    }

    /** The periods where the comparison found a discrepancy — DT-1's own breaches. */
    public List<ReplayVerification> dtOneBreaches() {
        return report.breaches();
    }

    /** A one-line audit sentence: the night, the basis, what ran, and the verdict. */
    public String describe() {
        StringBuilder sentence = new StringBuilder(report.describe());
        if (reportsCleanNight()) {
            return sentence.append(" — every replayed period demonstrably reproduces").toString();
        }
        sentence.append(" — NOT evidence of reproduction:");
        for (String reason : blockingReasons) {
            sentence.append("\n  ").append(reason);
        }
        return sentence.toString();
    }
}
