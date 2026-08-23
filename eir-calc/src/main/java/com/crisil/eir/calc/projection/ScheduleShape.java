package com.crisil.eir.calc.projection;

/**
 * The repayment shapes the projector family covers (FR-302).
 *
 * <p>The shape selects a projector, and selection is by shape rather than by
 * product because the mathematics follows the shape: two products with different
 * names, tenors and pricing produce the same flow pattern and must be projected
 * by the same code. Product remains the carrier of <em>policy</em> — day count
 * default, expected-life method, the B5.4.4 election — and is resolved before
 * projection begins.
 *
 * <p>{@link #REVOLVING} is deliberately in this enum rather than being folded
 * into an annuity with an assumed profile. ACPIR 54 accepts that the EIR cannot
 * be determined directly for a revolver, and a revolver bent into an annuity
 * shape produces a number with no meaning
 * (calculation specification section 3.7).
 */
public enum ScheduleShape {

    /** Equal instalments of principal and interest combined — the retail EMI. */
    ANNUITY_EMI,

    /** Instalments rising on a contractual ladder; the step is not a market movement. */
    STEP_UP,

    /** Instalments falling on a contractual ladder. */
    STEP_DOWN,

    /**
     * One repayment at maturity. Where the rate is non-zero the accrued interest
     * capitalises and is repaid with the principal — the zero-coupon profile.
     * A bullet that <em>services</em> interest periodically is
     * {@link #INTEREST_ONLY_BULLET}, not this.
     */
    BULLET,

    /** Periodic interest serviced, principal repaid with the final interest instalment. */
    INTEREST_ONLY_BULLET,

    /** Instalments sized against a terminal lump sum — balloon or lease residual. */
    BALLOON,

    /** An irregular contractual schedule that no formula reproduces; supply it externally. */
    STRUCTURED,

    /**
     * Cash credit, overdraft, credit cards, KCC, working-capital limits. No
     * contractual repayment schedule exists, so no conventional EIR can be
     * struck (ACPIR 54).
     */
    REVOLVING,

    /**
     * Issued or bought at a discount to face, redeemed at face: T-bills, CP, CD,
     * zero-coupon bonds. The entire return is accretion, which is why
     * approximation is never permitted here at any tenor
     * (calculation specification section 10.3).
     */
    DISCOUNT_INSTRUMENT,

    /**
     * Drawn in tranches against milestones. Produces outflows after {@code t=0}
     * and hence possibly more than one sign change in the present-value function
     * (calculation specification section 3.8).
     */
    TRANCHED;

    /**
     * Whether a contractual repayment schedule exists to project at all.
     *
     * <p>False only for {@link #REVOLVING}. The distinction is not cosmetic: it
     * is the difference between an EIR and an approximation that must be
     * disclosed as one.
     */
    public boolean carriesContractualSchedule() {
        return this != REVOLVING;
    }
}
