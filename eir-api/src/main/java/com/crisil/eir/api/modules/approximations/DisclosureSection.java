package com.crisil.eir.api.modules.approximations;

/**
 * The sections of the Ind AS 107 extract (FR-806), enumerated once.
 *
 * <p>Same structural discipline as {@link ApproximationCategory} and for the same reason:
 * {@link IndAs107Extract} loops over {@code values()}, so a section cannot be dropped by an
 * author forgetting to render it. A disclosure extract missing a section is a note to the accounts
 * missing a note, which is a worse thing than a note with a hole in it labelled as such.
 *
 * <p><b>One section is not sourced like the others.</b>
 * {@link #MEASUREMENT_BASIS_AND_APPROXIMATIONS} is fed by the FR-809 register rather than by a
 * figure source, because that is what FR-809 is <em>for</em>: the shortcuts are the judgements and
 * estimates a reader of the accounts is entitled to. {@link #fedByTheApproximationsRegister()}
 * names the difference so the extract does not have to hard-code a constant name.
 */
public enum DisclosureSection {

    /**
     * Interest revenue calculated using the effective interest method, presented separately.
     *
     * <p>Ind AS 107.20(b)(i). This is the figure the whole engine exists to produce, and the one
     * an auditor traces back through FR-808's per-contract trace.
     */
    INTEREST_REVENUE_EFFECTIVE_INTEREST_METHOD(
        "interest revenue calculated using the effective interest method",
        "Ind AS 107.20(b)(i) · FR-806 · FR-805",
        false,
        "the period's presented EIR interest, split from the contractual leg, off the movement"
            + " schedule of FR-805"),

    /**
     * The loss allowance reconciliation from opening to closing balance, by stage.
     *
     * <p>Ind AS 107.35H, and FR-806's "ACPIR-mandated credit-quality and loss-allowance
     * reconciliation tables".
     */
    LOSS_ALLOWANCE_RECONCILIATION(
        "reconciliation of the loss allowance from opening to closing balance",
        "Ind AS 107.35H · FR-806",
        false,
        "the opening-to-closing loss allowance movement by stage, with the transfers between"
            + " stages shown separately"),

    /**
     * Gross carrying amount by credit-risk rating grade, by stage.
     *
     * <p>Ind AS 107.35M. The stage split exists in the engine; the rating-grade dimension does
     * not.
     */
    CREDIT_QUALITY_BY_STAGE(
        "gross carrying amount by credit risk rating grade and stage",
        "Ind AS 107.35M · FR-806",
        false,
        "gross carrying amount analysed by credit-risk rating grade within stage"),

    /**
     * The hedging result, disclosed separately from interest revenue.
     *
     * <p>Ind AS 107.24A–24C, and FR-807: derivative net interest belongs in Other Income rather
     * than Schedule 13, "and disclose the hedging result separately so the economic margin on the
     * hedged banking book is reconstructable".
     */
    HEDGING_RESULT(
        "the hedging result, disclosed separately from effective-interest revenue",
        "Ind AS 107.24A–24C · FR-807 · FR-702",
        false,
        "basis-adjustment amortisation per FR-702 and derivative net interest presented in Other"
            + " Income per FR-807"),

    /**
     * The measurement basis, and every approximation applied within it.
     *
     * <p>Ind AS 107.21 requires the measurement basis and the accounting policies used;
     * Ind AS 1.122 and 1.125 require the judgements and the estimation uncertainty. A Tier 3
     * straight-line approximation, an ACPIR 51 contractual-life fallback, a pool EIR and an ACPIR
     * 54 revolving approximation are all four of those things, which is why FR-809's register
     * feeds this section directly and why the section carries the register's completeness rather
     * than a summary of it.
     */
    MEASUREMENT_BASIS_AND_APPROXIMATIONS(
        "measurement basis, and the approximations applied within it",
        "Ind AS 107.21 · Ind AS 1.122 · Ind AS 1.125 · FR-809 · 06 § 7",
        true,
        "");

    private final String title;
    private final String reference;
    private final boolean fedByTheApproximationsRegister;
    private final String wouldBePopulatedBy;

    DisclosureSection(
        String title,
        String reference,
        boolean fedByTheApproximationsRegister,
        String wouldBePopulatedBy) {
        this.title = title;
        this.reference = reference;
        this.fedByTheApproximationsRegister = fedByTheApproximationsRegister;
        this.wouldBePopulatedBy = wouldBePopulatedBy;
    }

    public String title() {
        return title;
    }

    /** The standard paragraph and the requirement, so the extract can be checked against both. */
    public String reference() {
        return reference;
    }

    /** Whether the FR-809 register supplies this section instead of a figure source. */
    public boolean fedByTheApproximationsRegister() {
        return fedByTheApproximationsRegister;
    }

    /**
     * What would populate this section, for the gap. Empty for the register-fed section, which
     * has no external source and therefore no gap of this kind.
     */
    public String wouldBePopulatedBy() {
        return wouldBePopulatedBy;
    }
}
