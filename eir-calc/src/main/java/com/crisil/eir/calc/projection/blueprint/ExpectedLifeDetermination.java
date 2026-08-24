package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The expected life actually used, the policy that chose it, every alternative
 * computed, and the difference between them.
 *
 * <p>The engine's obligation on an instrument with optionality is not to pick the
 * right life — it cannot, because the governing sources disagree — but to make the
 * choice explicit and its cost visible. The domain reference calls the callable
 * security divergence "one of the few that can be measured exactly rather than
 * argued: compute both and disclose the difference". This record is that disclosure.
 *
 * <p>Invariant ST-7 requires at least two alternatives whenever any option is
 * present. A single-alternative determination on an optioned instrument means the
 * divergence was never quantified, which is the finding rather than the answer.
 *
 * @param chosenPolicy      the policy that produced the published figure
 * @param chosenLifePeriods expected life in schedule periods under that policy
 * @param alternatives      every policy computed, chosen one included
 * @param invariants        ST-7 and ST-8 results
 */
public record ExpectedLifeDetermination(
    ExercisePolicy chosenPolicy,
    int chosenLifePeriods,
    List<LifeAlternative> alternatives,
    List<InvariantResult> invariants) {

    public ExpectedLifeDetermination {
        Objects.requireNonNull(chosenPolicy, "chosenPolicy");
        Objects.requireNonNull(alternatives, "alternatives");
        Objects.requireNonNull(invariants, "invariants");
        if (chosenLifePeriods < 1) {
            throw new IllegalArgumentException(
                "expected life spans at least one period, got " + chosenLifePeriods);
        }
        alternatives = List.copyOf(alternatives);
        invariants = List.copyOf(invariants);
        LifeAlternative chosenAlternative = null;
        for (LifeAlternative alternative : alternatives) {
            if (alternative.policy() == chosenPolicy) {
                chosenAlternative = alternative;
                break;
            }
        }
        if (chosenAlternative == null) {
            throw new IllegalArgumentException(
                "the chosen policy " + chosenPolicy + " is absent from the alternatives; the"
                    + " published figure must be one of the computed ones");
        }
        // And the published LIFE must be the chosen alternative's life, not merely some
        // number sitting beside the chosen policy's name. The check above established that
        // the published figure is one of the computed ones; this establishes that it is the
        // one it claims to be. Without it a determination could publish life 5 while
        // chosen().lifePeriods() returned 8, and be accepted in silence: ST-8 would then be
        // asserted against a life no computed policy supports, and two readers of the same
        // record would get different lives depending on which accessor they reached for.
        if (chosenAlternative.lifePeriods() != chosenLifePeriods) {
            throw new IllegalArgumentException(
                "the published expected life is " + chosenLifePeriods + " period(s) but policy "
                    + chosenPolicy + " computes " + chosenAlternative.lifePeriods()
                    + "; the published figure must be the chosen alternative's own, or ST-8 is"
                    + " asserted against a life no policy supports");
        }
    }

    /**
     * Builds a determination and asserts ST-7 and ST-8.
     *
     * @param optioned         whether the instrument carries any embedded option
     * @param eclHorizonPeriods the ACPIR 46(1) horizon, for the ST-8 ordering check
     */
    public static ExpectedLifeDetermination of(
        ExercisePolicy chosenPolicy,
        int chosenLifePeriods,
        List<LifeAlternative> alternatives,
        boolean optioned,
        int eclHorizonPeriods) {

        List<InvariantResult> checks = new ArrayList<>();
        if (optioned) {
            checks.add(alternatives.size() >= 2
                ? InvariantResult.pass(
                    InvariantId.ST_7,
                    " " + alternatives.size() + " exercise policies computed, divergence quantified")
                : InvariantResult.fail(
                    InvariantId.ST_7,
                    " an instrument with embedded options carries only "
                        + alternatives.size() + " computed policy, so the ACPIR-versus-IFRS 9"
                        + " divergence was never quantified. Compute both and disclose the difference.",
                    BigDecimal.valueOf(2L - alternatives.size())));
        }
        checks.add(chosenLifePeriods <= eclHorizonPeriods
            ? InvariantResult.pass(
                InvariantId.ST_8,
                "expected life " + chosenLifePeriods + " within the ECL horizon " + eclHorizonPeriods)
            : InvariantResult.fail(
                InvariantId.ST_8,
                "expected life " + chosenLifePeriods + " exceeds the ECL horizon "
                    + eclHorizonPeriods + ". ACPIR 46(1) sets the horizon at the MAXIMUM contractual"
                    + " period including extension options, so nothing can be expected beyond it.",
                BigDecimal.valueOf((long) chosenLifePeriods - eclHorizonPeriods)));
        return new ExpectedLifeDetermination(chosenPolicy, chosenLifePeriods, alternatives, checks);
    }

    /** The alternative the published figure came from. */
    public LifeAlternative chosen() {
        for (LifeAlternative alternative : alternatives) {
            if (alternative.policy() == chosenPolicy) {
                return alternative;
            }
        }
        throw new IllegalStateException("chosen policy absent; the constructor should have rejected this");
    }

    /**
     * The widest rate divergence across the computed alternatives, in basis points of
     * effective annual rate.
     *
     * <p>The number to put in front of the committee. Zero where only one alternative
     * was computed, which ST-7 already flags on an optioned instrument.
     */
    public BigDecimal widestDivergenceBps() {
        if (alternatives.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal lowest = null;
        BigDecimal highest = null;
        for (LifeAlternative alternative : alternatives) {
            BigDecimal bps = alternative.eir().effectiveAnnualBps();
            if (lowest == null || bps.compareTo(lowest) < 0) {
                lowest = bps;
            }
            if (highest == null || bps.compareTo(highest) > 0) {
                highest = bps;
            }
        }
        return highest.subtract(lowest);
    }

    public boolean allSatisfied() {
        return invariants.stream().allMatch(InvariantResult::satisfied);
    }

    /**
     * One exercise policy, costed.
     *
     * @param policy           the policy computed
     * @param lifePeriods      expected life it implies
     * @param eir              the rate it produces
     * @param firstPeriodIncome interest recognised in the first period under it — the
     *     figure a controller compares, because a rate difference in basis points is
     *     abstract and a first-period income difference is not
     */
    public record LifeAlternative(
        ExercisePolicy policy, int lifePeriods, Rate eir, Money firstPeriodIncome) {

        public LifeAlternative {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(eir, "eir");
            Objects.requireNonNull(firstPeriodIncome, "firstPeriodIncome");
            if (lifePeriods < 1) {
                throw new IllegalArgumentException("lifePeriods must be >= 1, got " + lifePeriods);
            }
        }
    }
}
