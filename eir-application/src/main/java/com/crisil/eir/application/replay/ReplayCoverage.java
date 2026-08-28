package com.crisil.eir.application.replay;

/**
 * What a replay's figure comparison actually looked at — the denominator, as a verdict.
 *
 * <p><b>Why this exists rather than a boolean, and why it is not folded into DT-1's detail.</b>
 * {@code ReplayComparison.isVacuous()} catches the case where <em>both</em> legs compared nothing,
 * and its javadoc records that widening it to "either leg" was tried and reverted for a good
 * reason. That leaves one gap, and it is precisely this module's corollary of the codebase's
 * signature defect: a comparison with policy stamps on both sides and <b>no figure at all</b>
 * reports a DT-1 pass. A period whose every contract was quarantined in both runs lands there. So
 * does a run whose figure projection was mis-keyed to nothing.
 *
 * <p>An aggregation over an empty population is the trap. A run that processed no contracts must
 * not report a clean close, and a replay that compared no figure must not report a reproduction —
 * so the state is named, one of the three is adverse, and {@link ReplayVerification#dtOne()}
 * conjoins a failure for it.
 *
 * <p>Three states rather than two, for the same reason {@code SampleOutcome} has three: the two
 * ways of comparing nothing are not the same event. One is a book with nothing in it; the other is
 * a run that produced nothing from something.
 */
public enum ReplayCoverage {

    /**
     * Figures were compared. The control ran, and DT-1's verdict means what it says.
     */
    FIGURES_COMPARED(true, false),

    /**
     * The population was empty, so neither run published a figure. Not a defect.
     *
     * <p>The real state of a period in a newly opened book, or of a book whose contracts all
     * matured before it. DT-1 is not failed for it: a control that is red by design gets
     * suppressed, and then it is not there for the night it matters —
     * {@code InvariantId.TM_1} spends a paragraph on exactly that failure mode.
     *
     * <p>It is also not a <em>reproduction</em>. There was nothing to reproduce, and
     * {@link ReplayVerification#provesReproduction()} says so rather than reporting a period whose
     * figures were never compared as evidence that replay works.
     */
    NOTHING_PUBLISHED(false, false),

    /**
     * The population was not empty and the comparison still had no figure to compare. A defect.
     *
     * <p>Two ways in, both real. Every contract quarantined in both runs — a period the close gate
     * should have refused (05 § 4.5 gates on the exception count) and a replay that faithfully
     * reproduced its emptiness. Or a run whose figures exist and whose keys do not match the
     * published convention on either side, which is a projection defect wearing the appearance of
     * a clean night.
     *
     * <p>DT-1 is failed with a deviation of one — one thing needs a remedy, and it is the
     * comparison itself. That is the same reading {@code ReplayComparison.dtOne()} gives a wholly
     * vacuous comparison, and for the same stated reason: "DT-1 cannot be satisfied by a
     * comparison that compared nothing".
     */
    POPULATION_PRODUCED_NO_FIGURE(false, true);

    private final boolean provesFidelity;
    private final boolean defect;

    ReplayCoverage(boolean provesFidelity, boolean defect) {
        this.provesFidelity = provesFidelity;
        this.defect = defect;
    }

    /**
     * Whether a passing DT-1 under this coverage is evidence that the period reproduces.
     *
     * <p>False for both of the nothing-compared states, including the benign one. "DT-1 passed"
     * and "the replay reproduced the published figures" are the same sentence only when a figure
     * was compared.
     */
    public boolean provesFidelity() {
        return provesFidelity;
    }

    /** Whether this coverage is itself a DT-1 breach. */
    public boolean isDefect() {
        return defect;
    }

    /** Which of the three this is, for the run's own population and figure count. */
    static ReplayCoverage of(PopulationAccount population, int figuresCompared) {
        if (figuresCompared > 0) {
            return FIGURES_COMPARED;
        }
        return population.isEmpty() ? NOTHING_PUBLISHED : POPULATION_PRODUCED_NO_FIGURE;
    }
}
