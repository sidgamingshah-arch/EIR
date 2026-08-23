package com.crisil.eir.domain;

/**
 * The materiality gate. Estimation risk distributes by tenor rather than by
 * product, so the tier — not the product — selects the computation strategy.
 */
public enum MaterialityTier {

    /**
     * Instrument level, mandatory. Wholesale above threshold, all project
     * finance, all POCI, all restructured or modified exposures, anything with a
     * contingent rate feature, anything in a hedge relationship. Carries a
     * tightened solver tolerance.
     */
    TIER_1,

    /**
     * Pool level under the ACPIR 51 group presumption. Retail and MSME above
     * 12-month tenor, cards, KCC revolvers.
     */
    TIER_2,

    /**
     * Documented approximation: contractual rate plus straight-line fee
     * accretion. Permitted only against a current equivalence test (invariant
     * TG-1), and never for zero-coupon or deep-discount instruments at any tenor.
     */
    TIER_3;

    /** Tier 1 tightens the solver tolerance; long-tenor Tier 2 pools may too. */
    public boolean requiresTightenedTolerance() {
        return this == TIER_1;
    }
}
