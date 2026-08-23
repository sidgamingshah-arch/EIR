package com.crisil.eir.calc.routing;

/**
 * The configured qualitative indicators of a substantial modification
 * (calculation specification section 6.4, FR-511).
 *
 * <p>These exist because the quantitative test is not the assessment. The IASB
 * tentatively decided in February 2025 to require a principles-based qualitative
 * assessment of substantiality, with outcomes that cannot be determined by a
 * quantitative test alone; the indicative factors under consideration are the ones
 * enumerated here. A restructuring that changes the obligor, the currency or the
 * basis on which interest is determined can be substantial at a 2% present-value
 * difference, and one that reprices a covenant can be non-substantial at 15%.
 *
 * <p>A fired trigger is <em>evidence recorded</em>, never a decision taken. None
 * of these constants carries a "therefore substantial" flag, deliberately: the
 * engine's posture is that this judgement belongs to a person with a documented
 * basis (see {@link ModificationTest}). What a fired trigger does do is stop the
 * engine concluding {@link ModificationConclusion#NOT_SUBSTANTIAL} on the strength
 * of a passing ratio alone.
 */
public enum QualitativeTrigger {

    /**
     * The basis for determining interest changed — fixed to floating, or a change
     * of benchmark. An IASB February 2025 indicative factor, and the one most
     * often present in an Indian resolution plan.
     */
    CHANGE_IN_INTEREST_BASIS,

    /** The currency of the instrument changed. */
    CHANGE_OF_CURRENCY,

    /** The obligor changed — a substitution, novation or transfer of the borrowing. */
    CHANGE_OF_OBLIGOR,

    /**
     * An equity conversion feature was introduced. Common in Indian resolution
     * plans through CCD or optionally convertible instruments.
     */
    EQUITY_CONVERSION_FEATURE_ADDED,

    /**
     * The change causes the contractual cash flows to fail the SPPI test, so the
     * revised instrument could not remain at amortised cost even if the original
     * could.
     */
    SPPI_FAILURE_CAUSED,

    /**
     * A revolving facility was converted to a term loan. Also the point at which
     * the ACPIR 46(2)(ii) substantive-renewal question and the
     * rollover-versus-new-instrument policy attribute (section 6.6) meet.
     */
    REVOLVING_TO_TERM_CONVERSION,

    /**
     * The change was made for commercial reasons and aligns the terms to current
     * market. An IASB February 2025 indicative factor; distinct from a contractual
     * reset, which is not a modification at all.
     */
    ALIGNED_TO_CURRENT_MARKET_TERMS
}
