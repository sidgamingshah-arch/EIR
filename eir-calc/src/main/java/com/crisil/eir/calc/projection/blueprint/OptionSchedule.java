package com.crisil.eir.calc.projection.blueprint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The embedded options on an instrument, and the policy by which their exercise is
 * assumed.
 *
 * <p>This is where ACPIR and IFRS 9 diverge in a way that is <b>exactly measurable
 * rather than arguable</b>, which is why expected life is chosen by policy and
 * recorded rather than derived. RBI's Investment Directions amortise a discount or
 * premium over residual <em>contractual</em> maturity even for a callable security;
 * IFRS 9 Appendix A runs over <em>expected</em> life considering the call. Both are
 * computable, so the engine computes both and reports the difference.
 *
 * <p>The size of that difference, and its direction, is not intuitive. On a
 * ten-year 9% bond callable at par at year five:
 *
 * <ul>
 *   <li>bought at a <b>premium</b>, the earliest-call basis reports 49.1 bp and
 *       5,153.16 of year-one income <em>less</em> than the contractual-maturity basis;
 *   <li>bought at a <b>discount</b>, it reports 52.3 bp and 4,969.81 <em>more</em>.
 * </ul>
 *
 * <p>The sign reverses with purchase price, because a premium amortised over five
 * years instead of ten is expensed about twice as fast while a discount accreted over
 * five recognises income faster. So <b>no blanket exercise policy can be assumed
 * conservative</b>, and the choice cannot be made once, globally, on prudence
 * grounds. It has to be made per portfolio with the number in front of the committee.
 *
 * @param options       every option on the instrument; empty for a vanilla one
 * @param exercisePolicy how exercise is assumed for expected-life purposes
 */
public record OptionSchedule(List<EmbeddedOption> options, ExercisePolicy exercisePolicy) {

    public OptionSchedule {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(exercisePolicy, "exercisePolicy");
        options = List.copyOf(options);
        // Asked of the policy rather than restated here. This read
        // `exercisePolicy != CONTRACTUAL_MATURITY` inline, which is the same rule
        // ExercisePolicy.requiresOptions() states — and the two disagreed, because the
        // predicate had no callers at all and so nothing kept them in step. B5.4.4's
        // NEXT_REPRICING amortises to a repricing date and needs no option, which the
        // predicate now says and this guard now honours.
        if (options.isEmpty() && exercisePolicy.requiresOptions()) {
            throw new IllegalArgumentException(
                "an instrument with no options has nothing to exercise, so its policy is"
                    + " CONTRACTUAL_MATURITY or the NEXT_REPRICING election; got " + exercisePolicy);
        }
    }

    /** A vanilla instrument with no embedded options. */
    public static OptionSchedule none() {
        return new OptionSchedule(List.of(), ExercisePolicy.CONTRACTUAL_MATURITY);
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }

    /** Every option of a given type. */
    public List<EmbeddedOption> ofType(OptionType type) {
        Objects.requireNonNull(type, "type");
        List<EmbeddedOption> matching = new ArrayList<>();
        for (EmbeddedOption option : options) {
            if (option.type() == type) {
                matching.add(option);
            }
        }
        return List.copyOf(matching);
    }

    /** The earliest exercise date across all options, or empty where there are none. */
    public Optional<LocalDate> earliestExercise() {
        LocalDate earliest = null;
        for (EmbeddedOption option : options) {
            if (earliest == null || option.firstExercise().isBefore(earliest)) {
                earliest = option.firstExercise();
            }
        }
        return Optional.ofNullable(earliest);
    }

    /**
     * Whether any option would, on its own, send the instrument out of amortised cost.
     *
     * <p>A conversion feature on an <b>asset</b> almost always fails SPPI, and an
     * SPPI failure sends the <em>entire</em> instrument to FVTPL where no EIR arises.
     * That is a cliff, not a gradient, which is why the classification gate must run
     * before any EIR work is commissioned rather than after.
     */
    public boolean impliesFairValueThroughProfitOrLoss() {
        for (EmbeddedOption option : options) {
            if (option.type() == OptionType.CONVERSION) {
                return true;
            }
        }
        return false;
    }

    /**
     * One embedded option.
     *
     * @param type            what right it confers
     * @param holder          who holds it — a required field, see {@link OptionHolder}
     * @param firstExercise   earliest exercise date
     * @param lastExercise    latest exercise date; equal to first for a one-shot option
     * @param strikePctOfPar  exercise price as a fraction of par — 1.00 for par,
     *     above for a call premium
     * @param makeWhole       whether exercise triggers a make-whole payment
     */
    public record EmbeddedOption(
        OptionType type,
        OptionHolder holder,
        LocalDate firstExercise,
        LocalDate lastExercise,
        BigDecimal strikePctOfPar,
        boolean makeWhole) {

        public EmbeddedOption {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(holder, "holder");
            Objects.requireNonNull(firstExercise, "firstExercise");
            Objects.requireNonNull(lastExercise, "lastExercise");
            Objects.requireNonNull(strikePctOfPar, "strikePctOfPar");
            if (lastExercise.isBefore(firstExercise)) {
                throw new IllegalArgumentException(
                    "lastExercise " + lastExercise + " precedes firstExercise " + firstExercise);
            }
            if (strikePctOfPar.signum() <= 0) {
                throw new IllegalArgumentException(
                    "strikePctOfPar must be positive, got " + strikePctOfPar.toPlainString());
            }
        }

        /** A one-shot option exercisable at par on a single date. */
        public static EmbeddedOption atPar(OptionType type, OptionHolder holder, LocalDate on) {
            return new EmbeddedOption(type, holder, on, on, BigDecimal.ONE, false);
        }
    }

    /** What right an option confers. */
    public enum OptionType {
        /** The obligor may redeem early. */
        CALL,
        /** The holder may require early redemption. */
        PUT,
        /** Either party may extend the term. */
        EXTENSION,
        /** The borrower may prepay, in whole or in part. */
        PREPAYMENT,
        /** A securitisation clean-up call once the pool falls below a threshold. */
        CLEAN_UP_CALL,
        /**
         * Conversion into equity. Present so that it can be <em>rejected</em>: on an
         * asset it fails SPPI and there is no EIR at all.
         */
        CONVERSION
    }

    /**
     * Who holds the option.
     *
     * <p>Required, not decoration. A put held by the bank is the bank's own exercise
     * judgement, evidenced by its own intentions. A call held by the issuer requires
     * the bank to model <em>someone else's</em> rational behaviour — a different
     * estimation problem with different evidence requirements and a different
     * validation burden. Collapsing the two loses the distinction that decides which
     * evidence an auditor should expect to see.
     */
    public enum OptionHolder {
        ISSUER_BORROWER,
        HOLDER_LENDER,
        EITHER
    }
}
