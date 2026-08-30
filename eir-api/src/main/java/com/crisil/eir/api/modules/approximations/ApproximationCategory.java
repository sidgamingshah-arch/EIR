package com.crisil.eir.api.modules.approximations;

/**
 * The four shortcuts FR-809 requires reported, enumerated once.
 *
 * <p><b>Why an enum and not four render methods.</b> FR-809 names exactly four: "Tier 3
 * populations, ACPIR 51 contractual fallbacks, pool-level measurement, revolving
 * approximations", and 06 § 7 repeats the list. {@link ApproximationRegister} iterates
 * {@code values()} and renders one block per constant, so a category cannot be dropped by an
 * author forgetting to call a method — the omission would have to be a deletion from this file,
 * where it is visible. That matters more here than in most enums, because the failure mode of
 * this endpoint is not a wrong figure: it is a report that reads as coverage while a category is
 * missing. 06 § 7 states the consequence directly — "an undocumented approximation drifting
 * quietly across a portfolio is the failure this endpoint exists to prevent".
 *
 * <p>Each constant carries {@link #wouldBePopulatedBy()}: the named artefact whose absence turns
 * the category into a gap. A gap that cannot say what would fill it is an apology rather than a
 * finding, and nobody can act on it.
 */
public enum ApproximationCategory {

    /**
     * Tier 3 — contractual rate plus straight-line accretion of net fees over tenor.
     *
     * <p>The one 06 § 7 singles out, and the one with a refusal attached. 03 § 10.2 permits it
     * "only against a current equivalence test" (invariant TG-1, FR-411), and 03 § 10.3 refuses
     * it outright for zero-coupon and deep-discount instruments <em>at any tenor</em> (FR-412).
     * Reference case 9 is the measurement: a 15-year zero-coupon at 8% bought at 315,241.70
     * against a face of 1,000,000.00 has 684,758.30 to accrete, and straight line takes
     * 45,650.55 every year against the EIR method's 25,219.34 in year one and 74,074.07 in year
     * fifteen — overstating year-one income by 81.0% and understating the final year by 38.4%
     * while the two columns tie at 684,758.30 exactly. The exact tie is the defect, not the
     * reassurance: an error that nets to zero over the life is invisible to every control that
     * looks at cumulative figures and material in every single period.
     */
    TIER_3_APPROXIMATION(
        "Tier 3 populations with equivalence-test dates",
        "Tier 3: contractual rate plus straight-line accretion of net fees over tenor"
            + " (03 § 10 Tier 3 row)",
        "FR-809 · FR-411 · FR-412 · 03 § 10.2 · 03 § 10.3 · 06 § 7",
        "TG-1",
        "the Tier 3 populations FR-107 assignment proposed for the period, and the"
            + " EquivalenceTestRecord register they are gated against"),

    /**
     * The ACPIR 51 contractual fallback: expected life taken as the contractual term.
     *
     * <p>FR-310's fallback, and {@code ContractTerms.expectedLifePeriods()} is where it happens —
     * an unstated {@code eirExpectedLifeMonths} resolves to the contractual term. That method's
     * own javadoc states the control this category reports on: the fallback "is intended for rare
     * cases and must be an explicit, justified election on the contract", and the method "cannot
     * tell an election from an omission", so {@code ProjectionResult.expectedEqualsContractualByPolicy()}
     * "records only that the two legs coincide and the justification is held upstream". This
     * category is the report on whether anything upstream actually holds it.
     */
    CONTRACTUAL_LIFE_FALLBACK(
        "ACPIR 51 contractual fallbacks with justifications",
        "expected life taken as the contractual term (FR-310)",
        "FR-809 · FR-310 · 06 § 7",
        null,
        "the contracts whose expected life fell back to the contractual term, each with the"
            + " justification for the election and the date it was made"),

    /**
     * Pool-level measurement under the ACPIR 51 group presumption (Tier 2).
     *
     * <p>03 § 10.1 permits it and attaches four requirements, of which two are reportable here:
     * the pool definition is "a versioned, approved artefact with explicit homogeneity criteria"
     * — {@code PoolDefinition} carries the {@code POOL_DEFINITION} policy version that authorises
     * it — and there is a "<b>mandatory quarterly back-test</b> against contract-level
     * computation on a statistical sample, with a materiality threshold that, when breached,
     * forces the pool to contract-level measurement". A pool in force with no in-date back-test
     * is a shortcut in force with no evidence, which is exactly what this register counts.
     */
    POOL_LEVEL_MEASUREMENT(
        "pool-level measurement with back-test variances",
        "pool EIR under the ACPIR 51 group presumption instead of contract-level measurement"
            + " (03 § 10.1)",
        "FR-809 · FR-608 · 03 § 10.1 · 06 § 7",
        "PL-2",
        "the PoolDefinition versions in force at the reporting date, and the quarterly"
            + " back-test artefact 03 § 10.1 requires against each"),

    /**
     * The ACPIR 54 revolving approximations.
     *
     * <p>ACPIR 54 contemplates that the EIR cannot be determined directly for a revolver, which
     * is what makes {@code RevolvingApproximation}'s two options legitimate rather than
     * expedient. Which applies "turns on whether utilisation is modellable, and that is a
     * product-level assessment with evidence behind it, not a per-contract choice made at
     * projection time" — so the reportable unit is the product election, with the evidence date.
     */
    REVOLVING_APPROXIMATION(
        "revolving approximations",
        "an ACPIR 54 approximation in place of a directly determined EIR",
        "FR-809 · FR-308 · ACPIR 54 · 06 § 7",
        null,
        "the per-product ACPIR 54 election, the renewal or sanction period it defers over, and"
            + " the utilisation profile where EIR_OVER_UTILISATION is elected");

    private final String title;
    private final String shortcut;
    private final String specReference;
    private final String invariant;
    private final String wouldBePopulatedBy;

    ApproximationCategory(
        String title,
        String shortcut,
        String specReference,
        String invariant,
        String wouldBePopulatedBy) {
        this.title = title;
        this.shortcut = shortcut;
        this.specReference = specReference;
        this.invariant = invariant;
        this.wouldBePopulatedBy = wouldBePopulatedBy;
    }

    /** 06 § 7's own words for this category, so the report and the specification can be diffed. */
    public String title() {
        return title;
    }

    /** What is actually being approximated, for a reader who does not know the tier vocabulary. */
    public String shortcut() {
        return shortcut;
    }

    /** The requirement and specification sections this category answers to. */
    public String specReference() {
        return specReference;
    }

    /**
     * The invariant asserted over this category, or null where none is.
     *
     * <p>Two of the four have one and two do not, and the asymmetry is reported rather than
     * smoothed over. {@code InvariantId} carries TG-1 for the Tier 3 gate and PL-2 for pool
     * eligibility; there is no invariant id for an unjustified ACPIR 51 election or for a
     * revolving approximation with no recorded assessment. Naming null here is how the report
     * says so — a category shown with a fabricated invariant id would read as controlled.
     */
    public String invariant() {
        return invariant;
    }

    /** The artefact whose absence makes this category a gap. See the class javadoc. */
    public String wouldBePopulatedBy() {
        return wouldBePopulatedBy;
    }
}
