package com.crisil.eir.calc.projection.blueprint;

import java.util.Objects;

/**
 * How long the ladder runs, and how that length divides into phases.
 *
 * <p>The whole of this type exists to hold one decision that
 * {@link Moratorium.MoratoriumTermEffect} makes and that nothing else in the
 * blueprint records: <b>does the holiday lengthen the instrument, or is it
 * absorbed?</b> The same 1,000,000 loan with the same twelve-period holiday
 * produces a 36-period ladder under {@code EXTEND_TERM} and a 24-period ladder
 * under {@code COMPRESS_REMAINING}, with materially different instalments and
 * therefore a materially different EIR. Reconstructing that from dates alone is
 * impossible — stated maturity says where the contract said it ends, not what the
 * restructuring did to it — so the term effect is a contractual input and this
 * record is where it lands.
 *
 * <p>Phases run in order and do not overlap:
 *
 * <ol>
 *   <li><b>moratorium</b> — principal suspended; what happens to interest is
 *       {@link InterestServicing}'s business, not this record's.
 *   <li><b>interest-only</b> — {@link InterestServicing.ServicedThenCombined}'s
 *       prefix, held separate from the moratorium because a contractual
 *       interest-only phase and a granted repayment holiday are different facts
 *       about a contract even where they bill identically. One is priced in at
 *       origination; the other is a concession that may be a modification.
 *   <li><b>amortising</b> — where the {@link PrincipalProfile} applies.
 * </ol>
 *
 * @param statedPeriods      periods from value date to stated maturity at the
 *     calendar's frequency, before any term effect
 * @param moratoriumPeriods  length of the repayment holiday, zero for none
 * @param interestOnlyPeriods contractual interest-only periods after the holiday
 * @param amortisingPeriods  periods over which the principal profile amortises
 */
public record ScheduleTerm(
    int statedPeriods,
    int moratoriumPeriods,
    int interestOnlyPeriods,
    int amortisingPeriods) {

    public ScheduleTerm {
        if (statedPeriods < 1) {
            throw new IllegalArgumentException("statedPeriods must be >= 1, got " + statedPeriods);
        }
        if (moratoriumPeriods < 0 || interestOnlyPeriods < 0) {
            throw new IllegalArgumentException(
                "phase lengths must not be negative, got moratorium " + moratoriumPeriods
                    + " and interest-only " + interestOnlyPeriods);
        }
        if (amortisingPeriods < 1) {
            throw new IllegalArgumentException(
                "the amortising phase spans at least one period, got " + amortisingPeriods
                    + "; a holiday and an interest-only phase that together consume the whole term"
                    + " leave no period in which principal is repaid");
        }
    }

    /**
     * Resolves the phase lengths for a blueprint.
     *
     * <p>{@code EXTEND_TERM} grants the borrower the full original amortisation
     * term <em>after</em> the holiday, so the ladder runs longer than stated
     * maturity by exactly the holiday. That is what extending a term means, and it
     * is the arithmetic behind fixture S5: a 24-period loan with a twelve-period
     * holiday bills 24 instalments starting in period 13, on a balance grown by
     * the capitalisation.
     *
     * <p>{@code COMPRESS_REMAINING} and {@code BALLOON_ARREARS} both hold maturity
     * at the stated date, so the holiday eats repayment periods. Fixture S4 is the
     * compressed case — six interest-serviced periods then eighteen instalments,
     * not 24 — and the instalment rises from 47,073.47 to 60,982.05 as a result.
     * The two differ only in where the holiday's unpaid interest goes, which is a
     * question for the servicing profile rather than for the term.
     */
    public static ScheduleTerm of(ScheduleBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        int stated = ScheduleDates.statedPeriods(
            blueprint.calendar(), blueprint.valueDate(), blueprint.statedMaturity());
        int holiday = blueprint.moratorium().periods();
        int interestOnly = blueprint.servicing() instanceof InterestServicing.ServicedThenCombined phase
            ? phase.interestOnlyPeriods()
            : 0;
        int repayment = switch (blueprint.moratorium().termEffect()) {
            case EXTEND_TERM -> stated;
            case COMPRESS_REMAINING, BALLOON_ARREARS -> stated - holiday;
        };
        if (repayment - interestOnly < 1) {
            throw new IllegalArgumentException(
                "a " + holiday + "-period " + blueprint.moratorium().kind() + " holiday under "
                    + blueprint.moratorium().termEffect() + " plus " + interestOnly
                    + " interest-only periods leaves no amortising period inside the "
                    + stated + "-period stated term. Either the holiday extends the term or the"
                    + " stated maturity is wrong; the builder will not silently pick one");
        }
        return new ScheduleTerm(stated, holiday, interestOnly, repayment - interestOnly);
    }

    /** Total rungs on the ladder. */
    public int ladderPeriods() {
        return moratoriumPeriods + interestOnlyPeriods + amortisingPeriods;
    }

    /** Periods in which anything is billed — everything after the holiday. */
    public int repaymentPeriods() {
        return interestOnlyPeriods + amortisingPeriods;
    }

    /** 1-based ordinal of the first period in which principal amortises. */
    public int firstAmortisingPeriod() {
        return moratoriumPeriods + interestOnlyPeriods + 1;
    }

    /** Whether the ladder runs past the stated maturity date. */
    public boolean extendsPastStatedMaturity() {
        return ladderPeriods() > statedPeriods;
    }
}
