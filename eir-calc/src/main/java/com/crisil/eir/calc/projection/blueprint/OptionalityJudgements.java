package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The judgements an exercise policy needs and the engine must not invent.
 *
 * <p>Three of the six policies in {@link ExercisePolicy} cannot be evaluated from
 * the contract alone. {@code MOST_LIKELY_OUTCOME} is a binary management view on
 * whether a contingent event occurs; {@code PROBABILITY_WEIGHTED} is a
 * distribution over outcomes; {@code ECONOMIC_RATIONALITY} needs a prevailing
 * market yield and a materiality threshold before "in the money" means anything.
 * None of those is derivable, and a default for any of them would be an
 * assumption the engine invented and then reported as though a committee had made
 * it.
 *
 * <p>So they arrive here, as data, and {@link OptionalityResolver} supplies only
 * the arithmetic. A policy whose inputs are absent is <em>not computed</em> rather
 * than computed on a guess: the resolver reports it as unavailable, and invariant
 * ST-7 then registers that the divergence was never quantified. That is the
 * finding, and it is a more useful output than a plausible number with no author.
 *
 * <p>{@link #none()} is therefore a real and common value, not a placeholder. It
 * says the only policies available are the ones the contract itself determines —
 * contractual maturity, earliest exercise, and the next repricing date — which is
 * exactly the position of a book that has not yet taken the policy decisions.
 *
 * @param mostLikelyRedemption the single date management judges the instrument
 *     will be redeemed on, or {@code null} where no such judgement exists. The
 *     engine never guesses this date, because guessing it is the judgement
 * @param weightedOutcomes     the exercise distribution, or empty where none has
 *     been supplied. Probabilities must sum to exactly one
 * @param marketView           the prevailing yield and threshold the
 *     economic-rationality model needs, or {@code null} where the model is not to
 *     be run
 * @param extendedLifePeriods  the maximum contractual life in periods once every
 *     extension option is exercised, or {@code null} to read it from the
 *     blueprint's ACPIR 46(1) ECL horizon, which is defined as that same maximum
 */
public record OptionalityJudgements(
    LocalDate mostLikelyRedemption,
    List<WeightedOutcome> weightedOutcomes,
    MarketView marketView,
    Integer extendedLifePeriods) {

    public OptionalityJudgements {
        Objects.requireNonNull(weightedOutcomes, "weightedOutcomes");
        weightedOutcomes = List.copyOf(weightedOutcomes);
        if (!weightedOutcomes.isEmpty()) {
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < weightedOutcomes.size(); i++) {
                WeightedOutcome outcome = weightedOutcomes.get(i);
                if (i > 0 && !outcome.redemptionDate()
                        .isAfter(weightedOutcomes.get(i - 1).redemptionDate())) {
                    // Ordered rather than order-insensitive, because the resolver sums the
                    // weighted flows and BigDecimal addition at 28 digits is not associative
                    // in the last digit. Two runs handed the same outcomes in a different
                    // order would then publish rates differing at the twelfth decimal place,
                    // which is a DT-1 replay failure produced by nothing but map iteration.
                    throw new IllegalArgumentException(
                        "exercise outcomes must be in strictly ascending date order; "
                            + outcome.redemptionDate() + " does not follow "
                            + weightedOutcomes.get(i - 1).redemptionDate());
                }
                total = total.add(outcome.probability(), Precision.WORKING);
            }
            if (total.compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException(
                    "exercise probabilities must sum to exactly 1, got " + total.toPlainString()
                        + ". A weight vector that does not sum to one is not a distribution, and"
                        + " normalising it here would silently restate a management judgement.");
            }
        }
        if (extendedLifePeriods != null && extendedLifePeriods < 1) {
            throw new IllegalArgumentException(
                "extendedLifePeriods must be >= 1, got " + extendedLifePeriods);
        }
    }

    /**
     * No management judgement supplied.
     *
     * <p>Leaves available only the policies the contract determines by itself.
     * Passing this is a statement, not an omission — see the class note.
     */
    public static OptionalityJudgements none() {
        return new OptionalityJudgements(null, List.of(), null, null);
    }

    /** A single management view on the redemption date (IFRS 9, most likely outcome). */
    public static OptionalityJudgements mostLikely(LocalDate redemptionDate) {
        Objects.requireNonNull(redemptionDate, "redemptionDate");
        return new OptionalityJudgements(redemptionDate, List.of(), null, null);
    }

    /** An exercise distribution (IFRS 9, expected value across outcomes). */
    public static OptionalityJudgements probabilityWeighted(List<WeightedOutcome> outcomes) {
        return new OptionalityJudgements(null, outcomes, null, null);
    }

    /** The market inputs the economic-rationality model needs. */
    public static OptionalityJudgements economicRationality(MarketView view) {
        Objects.requireNonNull(view, "view");
        return new OptionalityJudgements(null, List.of(), view, null);
    }

    public OptionalityJudgements withMostLikelyRedemption(LocalDate redemptionDate) {
        return new OptionalityJudgements(
            redemptionDate, weightedOutcomes, marketView, extendedLifePeriods);
    }

    public OptionalityJudgements withWeightedOutcomes(List<WeightedOutcome> outcomes) {
        return new OptionalityJudgements(
            mostLikelyRedemption, outcomes, marketView, extendedLifePeriods);
    }

    public OptionalityJudgements withMarketView(MarketView view) {
        return new OptionalityJudgements(
            mostLikelyRedemption, weightedOutcomes, view, extendedLifePeriods);
    }

    /**
     * The extended life stated explicitly rather than read from the ECL horizon.
     *
     * <p>Worth setting where the two are genuinely different numbers. They usually
     * are not — ACPIR 46(1) defines the horizon as the maximum contractual period
     * including extension options, which is the same fact — but a horizon capped
     * by policy for another reason must not silently become an assumed term.
     */
    public OptionalityJudgements withExtendedLife(int periods) {
        return new OptionalityJudgements(mostLikelyRedemption, weightedOutcomes, marketView, periods);
    }

    public boolean hasMostLikelyRedemption() {
        return mostLikelyRedemption != null;
    }

    public boolean hasWeightedOutcomes() {
        return !weightedOutcomes.isEmpty();
    }

    public boolean hasMarketView() {
        return marketView != null;
    }

    /**
     * The extended life, falling back to the maximum contractual period the caller
     * already holds.
     *
     * <p>The fallback is not a derivation of expected life from the ECL horizon,
     * which [09 § 3.3] forbids in both directions. It reads a <em>contractual
     * fact</em> — how long the term can run once an extension is exercised — from
     * the field that already records it. What the extension alternative is
     * <em>for</em> is quantifying the divergence between that maximum and the
     * expected life; the chosen expected life remains whatever the chosen policy
     * produces.
     */
    public int extendedLifeOr(int maximumContractualPeriods) {
        return extendedLifePeriods == null ? maximumContractualPeriods : extendedLifePeriods;
    }

    /**
     * One exercise outcome and its probability.
     *
     * @param redemptionDate  when the instrument is assumed to be redeemed under
     *     this outcome; the stated maturity for the run-to-term outcome
     * @param strikePctOfPar  redemption price as a fraction of the par balance
     *     outstanding — 1.00 at par
     * @param probability     the weight, in {@code (0, 1]}
     */
    public record WeightedOutcome(
        LocalDate redemptionDate, BigDecimal strikePctOfPar, BigDecimal probability) {

        public WeightedOutcome {
            Objects.requireNonNull(redemptionDate, "redemptionDate");
            Objects.requireNonNull(strikePctOfPar, "strikePctOfPar");
            Objects.requireNonNull(probability, "probability");
            if (strikePctOfPar.signum() <= 0) {
                throw new IllegalArgumentException(
                    "strikePctOfPar must be positive, got " + strikePctOfPar.toPlainString());
            }
            if (probability.signum() <= 0 || probability.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                    "probability must lie in (0, 1], got " + probability.toPlainString());
            }
        }

        /** An outcome redeeming at par — the ordinary case for a call or a maturity. */
        public static WeightedOutcome atPar(LocalDate redemptionDate, BigDecimal probability) {
            return new WeightedOutcome(redemptionDate, BigDecimal.ONE, probability);
        }
    }

    /**
     * What the economic-rationality model values the instrument against.
     *
     * <p>A model under ACPIR Chapter V, so both inputs are stated rather than
     * assumed: the yield because an option is only in the money relative to a
     * market, and the threshold because exercising on a basis point of moneyness
     * is not behaviour any obligor exhibits — refinancing has costs, and a model
     * that ignores them calls every premium bond on its first call date.
     *
     * @param prevailingMarketYield the yield a market participant would demand for
     *     the remaining term, as at the exercise date under test
     * @param exerciseThreshold     how far in the money the option must be before
     *     exercise is assumed, as a fraction of the strike amount. Zero means
     *     frictionless exercise, which is a defensible input and a strong one
     */
    public record MarketView(Rate prevailingMarketYield, BigDecimal exerciseThreshold) {

        public MarketView {
            Objects.requireNonNull(prevailingMarketYield, "prevailingMarketYield");
            Objects.requireNonNull(exerciseThreshold, "exerciseThreshold");
            if (exerciseThreshold.signum() < 0) {
                throw new IllegalArgumentException(
                    "exerciseThreshold must be non-negative, got " + exerciseThreshold.toPlainString()
                        + ". A negative threshold assumes exercise while the option is out of the"
                        + " money, which is not rationality under any reading.");
            }
        }

        public static MarketView of(Rate prevailingMarketYield, BigDecimal exerciseThreshold) {
            return new MarketView(prevailingMarketYield, exerciseThreshold);
        }

        /** Frictionless exercise: any moneyness at all triggers it. */
        public static MarketView frictionless(Rate prevailingMarketYield) {
            return new MarketView(prevailingMarketYield, BigDecimal.ZERO);
        }
    }
}
