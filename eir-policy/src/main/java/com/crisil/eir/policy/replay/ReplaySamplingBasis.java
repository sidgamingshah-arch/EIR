package com.crisil.eir.policy.replay;

import java.util.Objects;

/**
 * The stated basis on which control C-12 picks the period it replays tonight.
 *
 * <p>C-12 is "sampled closed period replayed and byte-compared", nightly. A sample is a
 * <em>decision</em>, and an audit sample with no stated basis is an anecdote — so the two numbers
 * that determine what gets replayed are named, versioned by whoever set them, and carried on the
 * sample itself rather than being implicit in a scheduler's configuration.
 *
 * <h2>Why neither number is guarded to be non-zero</h2>
 *
 * <p>Both {@code periodsPerNight} and {@code lookbackPeriods} may be zero, and that is the most
 * important design decision in this file. A control that never selects a period is a control that
 * never runs, and this codebase's stated position is that such a control is worse than an absent
 * one because it reads as coverage. If a zero were refused at construction, the condition would
 * become unrepresentable in Java and would live instead in whatever configuration file supplies
 * these numbers — where nothing checks it, and where the nightly job reports success every night
 * for having done nothing.
 *
 * <p>The two ways it happens in practice are both mundane:
 *
 * <ul>
 *   <li><b>{@code periodsPerNight} dialled to zero</b> during a release freeze or an incident, and
 *       never dialled back. The job runs, logs a clean night, and alerts nobody.</li>
 *   <li><b>A stale {@code lookbackPeriods}</b>. A window of six periods against a book whose most
 *       recent close is nine periods old excludes every closed period on file. Nothing is
 *       misconfigured in an obvious way — the number was right when it was chosen — and the
 *       control has silently stopped running with a non-empty population sitting in front of
 *       it.</li>
 * </ul>
 *
 * <p>{@link ReplaySample#isInert()} is what names the condition, and it can only name it because
 * this record can represent it.
 *
 * @param periodsPerNight   how many closed periods tonight's run replays. C-12 says "a sampled
 *                          period", so one is the norm; more than one is a deeper sample and zero
 *                          is an inert control
 * @param lookbackPeriods   how many periods back from tonight's own period remain eligible. Bounds
 *                          the sample to the recent book, which is where a determinism defect is
 *                          both most likely and most expensive — and see above for how it goes
 *                          stale
 * @param rationale         why these numbers, for the audit trail. Not decoration: it is the part
 *                          of the sampling basis a reviewer actually reads
 */
public record ReplaySamplingBasis(int periodsPerNight, int lookbackPeriods, String rationale) {

    public ReplaySamplingBasis {
        // Negatives are refused, zero is not. A negative is not a weaker control, it is a number
        // with no reading at all — "minus two periods back" describes nothing a sampler could do —
        // whereas zero describes something real and adverse that the sample must be able to report.
        if (periodsPerNight < 0) {
            throw new IllegalArgumentException(
                "periodsPerNight must not be negative, got " + periodsPerNight
                    + "; zero is permitted and is reported as an inert control");
        }
        if (lookbackPeriods < 0) {
            throw new IllegalArgumentException(
                "lookbackPeriods must not be negative, got " + lookbackPeriods);
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException(
                "a sampling basis states why it samples the way it does; an unexplained sample is"
                    + " an anecdote, and C-12's evidence is the basis as much as the outcome");
        }
        rationale = rationale.strip();
    }

    /**
     * The programme default: one period a night, twelve periods back.
     *
     * <p>Twelve because a determinism defect is introduced by a deploy and found by the next replay
     * of a period the deploy touched, and a fiscal year of lookback means every period of the
     * current year is revisited within twelve nights of any change — see
     * {@link ReplaySample#nightsToCoverEligible()}. One a night because C-12 says "a sampled
     * period" and because a replay of a ten-million-contract period is not free.
     */
    public static ReplaySamplingBasis nightlyDefault() {
        return new ReplaySamplingBasis(1, 12,
            "C-12 nightly: one closed period a night from the last twelve, rotating, so every"
                + " period of the fiscal year is re-replayed within twelve nights of any deploy");
    }

    /**
     * Whether this basis can ever select anything.
     *
     * <p>{@code periodsPerNight > 0} alone, and deliberately not {@code && lookbackPeriods >= 0}:
     * the constructor has already refused a negative lookback, so that clause could not be false
     * and would be a condition wearing the shape of a check. A lookback of zero <em>can</em> still
     * select — the period whose month is tonight's own month, if one has closed — so it is not a
     * second way of switching the control off, and cannot be folded in here. Only
     * {@link ReplaySample#isInert()}, which sees the population, can say whether the lookback
     * excluded everything.
     */
    public boolean selectsAnything() {
        return periodsPerNight > 0;
    }

    /** A one-line audit sentence. */
    public String describe() {
        return periodsPerNight + " period(s) a night from the last " + lookbackPeriods
            + " period(s): " + rationale;
    }
}
