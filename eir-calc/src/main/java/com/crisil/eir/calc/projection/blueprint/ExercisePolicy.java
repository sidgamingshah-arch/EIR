package com.crisil.eir.calc.projection.blueprint;

/**
 * How the exercise of embedded options is assumed when determining expected life.
 *
 * <p>Expected life is <b>chosen by policy and recorded</b>, never derived. There is
 * no single correct answer available to the engine, because the governing sources
 * disagree: RBI's Investment Directions amortise over residual <em>contractual</em>
 * maturity even for a callable security, while IFRS 9 Appendix A runs over
 * <em>expected</em> life considering the call. That divergence is a Board decision
 * (divergence register items 7 and 13), not a calculation.
 *
 * <p>What the engine owes the decision is arithmetic: compute the alternatives,
 * quantify the difference, and record which policy produced the published figure. See
 * {@link ExpectedLifeDetermination}.
 */
public enum ExercisePolicy {

    /**
     * Ignore the options; run to stated maturity.
     *
     * <p>The RBI Investment Directions reading for callable and puttable securities.
     * Also the only coherent policy for an instrument with no options.
     */
    CONTRACTUAL_MATURITY,

    /**
     * Assume the earliest exercise date.
     *
     * <p>The RBI FAQ reading for perpetual debt: amortise a discount or premium up to
     * the earliest call. Run the SPPI screen first — an AT1-style instrument with
     * discretionary coupons and loss absorption generally fails SPPI and sits at
     * FVTPL, where no EIR arises at all, so the earliest-call rule applies only to
     * perpetual debt that <em>passes</em>.
     */
    EARLIEST_CALL,

    /**
     * A binary management judgement on whether the contingent event occurs.
     *
     * <p>One of the two methods IASB outreach found in use. Suited to large
     * single-name corporate facilities with bespoke features, and poorly suited to a
     * collective estimate — it requires instrument-level judgement, which does not
     * aggregate.
     */
    MOST_LIKELY_OUTCOME,

    /**
     * Expected value across exercise outcomes.
     *
     * <p>The other method in use, and the right one for homogeneous retail pools:
     * mortgage prepayment, card behavioural life, KCC rollover. Requires a curve
     * rather than a judgement.
     *
     * <p>Firms' manuals point to IAS 37.39–40 and IFRIC 23 by analogy for choosing
     * between this and {@link #MOST_LIKELY_OUTCOME}: use whichever better predicts
     * the resolution of the uncertainty. That formulation is worth adopting in policy
     * because it is the position IASB staff themselves endorsed.
     */
    PROBABILITY_WEIGHTED,

    /**
     * Amortise to the next repricing date.
     *
     * <p>The IFRS 9 B5.4.4 shortcut. For a floating-rate retail mortgage it largely
     * dissolves the behavioural-life estimation problem, and it removes the
     * reset-loop cost by leaving no unamortised fee to carry across the reset. An
     * accounting policy election <b>per product</b>, not a per-contract optimisation:
     * applied inconsistently it is indefensible.
     */
    NEXT_REPRICING,

    /**
     * Exercise where the option is in the money past a threshold.
     *
     * <p>Model-driven, and therefore a model under ACPIR Chapter V — inventory,
     * tiering, documentation and independent validation before implementation. The
     * most defensible policy for a callable investment book and the most expensive to
     * govern.
     */
    ECONOMIC_RATIONALITY;

    /** Whether the policy requires an option to be present to mean anything. */
    public boolean requiresOptions() {
        return this != CONTRACTUAL_MATURITY;
    }

    /**
     * Whether the policy is a model under ACPIR Chapter V and so needs independent
     * validation before it may be used in production.
     */
    public boolean isModelDriven() {
        return this == PROBABILITY_WEIGHTED || this == ECONOMIC_RATIONALITY;
    }
}
