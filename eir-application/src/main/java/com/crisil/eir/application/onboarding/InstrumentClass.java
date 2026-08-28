package com.crisil.eir.application.onboarding;

/**
 * The instrument class of 04 § 2.1, and the axis on which the SPPI test applies at all.
 *
 * <p>Aligned to the schema, character for character
 * ({@code V1__core_entities.sql}):
 *
 * <pre>
 * CONSTRAINT contract_instrument_class_ck
 *     CHECK (instrument_class IN ('LOAN', 'INVESTMENT', 'OFF_BALANCE_SHEET', 'LIABILITY'))
 * </pre>
 *
 * <p><b>The one thing this enum is for.</b> Two of the schema's SPPI constraints exempt
 * {@code LIABILITY} and neither exempts anything else:
 *
 * <pre>
 * CONSTRAINT contract_sppi_fail_implies_fvtpl_ck
 *     CHECK (sppi_outcome IS DISTINCT FROM 'FAIL'
 *            OR instrument_class = 'LIABILITY'
 *            OR measurement_category = 'FVTPL'),
 * CONSTRAINT contract_amortised_cost_needs_sppi_ck
 *     CHECK (measurement_category = 'FVTPL'
 *            OR instrument_class = 'LIABILITY'
 *            OR sppi_outcome IS NOT DISTINCT FROM 'PASS')
 * </pre>
 *
 * <p>The migration's own comment states the reason: "A liability is outside the SPPI test, so the
 * liability side is exempted rather than forced." FR-104 confines the cliff to the asset side, and
 * FR-105 gives the liability side an entirely different rule — bifurcate the embedded derivative to
 * FVTPL and <em>retain an EIR on the host</em>. Running an asset-side SPPI gate over an issued
 * structured deposit would send the host to FVTPL and destroy a rate that FR-105 requires to exist.
 *
 * <p><b>Deliberately not {@link com.crisil.eir.policy.tier.TierAssignmentSegment}.</b> That enum's
 * own javadoc makes the point in the other direction: "A wholesale term loan and a wholesale bond
 * holding are different instrument classes and the same segment for tiering purposes." The two
 * taxonomies answer different questions and conflating them "would silently move exposures between
 * tiers".
 */
public enum InstrumentClass {

    /** Advances: the term loans, EMI products, overdrafts and cash credit of the lending book. */
    LOAN,

    /** The investment book: bonds, T-bills, CP, CD, SDLs, debentures held. */
    INVESTMENT,

    /**
     * Commitments, guarantees and letters of credit.
     *
     * <p>On the asset side of the gate. ACPIR 23 dates initial recognition of an irrevocable
     * commitment from the day the bank became party to it, not from first drawdown, so a
     * commitment is an instrument with a recognition date and a fee stream long before it is a
     * balance — and its fees are exactly the ones ACPIR 52 defers.
     */
    OFF_BALANCE_SHEET,

    /**
     * Deposits, borrowings and debt issued — <b>outside the SPPI test</b> (FR-104, FR-105).
     *
     * <p>An EIR arises on a liability and the arithmetic is the same: {@code SolveRequest}'s
     * javadoc notes that "an asset's inception leg is negative and its target positive; a
     * liability's inception leg is positive and its target negative", which is why "the engine
     * needs no liability-specific solver path". What differs is the classification gate, and only
     * the classification gate.
     */
    LIABILITY;

    /**
     * Whether the SPPI test governs this instrument's measurement category.
     *
     * <p>True for the three asset classes, false for {@code LIABILITY}. Stated as a predicate
     * rather than as {@code != LIABILITY} at each site, because the exemption is a statement about
     * FR-104's scope and a reader should find the reasoning attached to it rather than inferring it
     * from a comparison.
     */
    public boolean subjectToSppi() {
        return this != LIABILITY;
    }
}
