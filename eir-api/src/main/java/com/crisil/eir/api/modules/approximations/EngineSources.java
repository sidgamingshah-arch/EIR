package com.crisil.eir.api.modules.approximations;

import java.util.List;

/**
 * What this engine actually holds for FR-809 and FR-806 today: nothing, category by category,
 * with the reason for each.
 *
 * <p><b>This class is the deliverable, not a placeholder.</b> Every method returns a gap, and each
 * gap names the class, the requirement and the wiring that is missing. That is the honest answer
 * and it is the answer 06 § 7 asks for: "making the shortcuts <i>visible</i> is what keeps them
 * defensible". A register that emitted four empty lists here would report a book with no
 * approximations in force, on an engine whose every contract takes the ACPIR 51 contractual
 * fallback and whose Tier 3 permission gate has never been asked a question.
 *
 * <p><b>Why the gaps are not closed here.</b> {@code ApiModules} constructs every module with an
 * {@code EirService} and nothing else, and {@code EirService}'s public surface is eight methods
 * that each return a rendered {@code Json.Obj}: there is no reader for the book, the last run's
 * computations, or the tier assignments. So this module cannot reach the state it would need even
 * where the state exists — and for three of the four categories it does not exist anywhere in the
 * engine. Closing a gap by inventing its contents is the one thing this endpoint must never do:
 * 06 § 7 exists because an undocumented approximation drifting quietly across a portfolio is the
 * failure, and a fabricated register is that failure with a report on top of it.
 *
 * <p>The reasons below are checked against the code, not paraphrased from the roadmap. Each names
 * a class a reader can open.
 */
public final class EngineSources implements ApproximationSources, DisclosureSources {

    /**
     * The Tier 3 gap, which 08 § "what still has no caller" calls the highest-value open item in
     * the engine.
     *
     * <p>Two distinct absences, and both have to be closed before this category can be reported.
     * There is no register of {@code EquivalenceTestRecord} — nothing in the engine constructs
     * one outside {@code eir-policy}'s own tests — and there is no record of which populations
     * were proposed for Tier 3: {@code EirService} runs {@code TierAssignment} once, inside
     * {@code onboard}, and discards the {@code TierAssignmentResult} after reading the solver
     * tolerance off it. {@code TierAssignmentResult.requiresEquivalenceTest()} is true for every
     * Tier 3 assignment and nothing asks it.
     */
    private static final String TIER_3_GAP =
        "no source. Two absences, both required: (1) nothing in the engine constructs an"
            + " EquivalenceTestRecord, so there is no register of performed equivalence tests to"
            + " gate against; and (2) EirService runs TierAssignment only inside onboard() and"
            + " discards the TierAssignmentResult, so the populations FR-107 proposed for Tier 3"
            + " are not recorded anywhere. EquivalenceTestGate implements the whole of FR-411 and"
            + " FR-412 — including FORBIDDEN_APPROXIMATION for zero-coupon and deep-discount"
            + " instruments and NO_TEST_ON_FILE demoting to Tier 2 with an exception raised — and"
            + " has no caller outside its own package. So a 15-year zero-coupon instrument is"
            + " assigned Tier 3, solved on Tier 3's tightened tolerance and recognised, with"
            + " nothing recording that the permission was never sought. Reference case 9 measures"
            + " that shortcut at 81.0% overstatement of year-one income.";

    private static final String CONTRACTUAL_LIFE_GAP =
        "no source. ContractTerms.expectedLifePeriods() resolves an unstated"
            + " eirExpectedLifeMonths to the contractual term, which is FR-310's fallback, and"
            + " ContractTerms.of(...) — the nine-argument factory every call site in EirService"
            + " and Seed uses — leaves both lives unstated at zero. So the fallback is in force on"
            + " every contract this deployment holds. What is missing is the justification: the"
            + " method's own javadoc records that it \"cannot tell an election from an omission\","
            + " and ProjectionResult.expectedEqualsContractualByPolicy() records only that the two"
            + " legs coincide, stating that the justification is held upstream. Nothing upstream"
            + " holds it, and there is no field on any type in the engine that could.";

    private static final String POOL_GAP =
        "no source. PoolDefinition and SuspensionPools exist in eir-policy and have no caller"
            + " outside it — no pool is defined in this deployment and nothing in eir-application"
            + " or eir-api constructs one. Separately, 03 § 10.1's mandatory quarterly back-test"
            + " has no artefact in the engine at all: ExceptionCategory.POOL_BACKTEST_BREACH"
            + " exists as a queue category with nothing that can raise it. PoolBackTest in this"
            + " package is the shape such an artefact would take, and it has no producer.";

    private static final String REVOLVING_GAP =
        "no source. ProjectorRegistry wires one RevolvingProjector on its no-argument"
            + " constructor, which fixes RevolvingApproximation.FEE_OVER_RENEWAL for every"
            + " revolving facility, and no per-product ACPIR 54 election is recorded anywhere —"
            + " so the approximation is applied by construction rather than by an assessment. The"
            + " assessment ACPIR 54 requires is product-level with evidence behind it (FR-308,"
            + " ACPIR 46(2)(iii) behavioural analysis for cards), and there is no type in the"
            + " engine that holds one. This deployment also books no revolving facility, so even"
            + " a wired election register would have nothing to report on this book.";

    @Override
    public Answer<Tier3Submission> tier3(int periodId) {
        return Answer.unavailable(TIER_3_GAP);
    }

    @Override
    public Answer<List<ContractualLifeFallback>> contractualLifeFallbacks(int periodId) {
        return Answer.unavailable(CONTRACTUAL_LIFE_GAP);
    }

    @Override
    public Answer<PoolSubmission> pools(int periodId) {
        return Answer.unavailable(POOL_GAP);
    }

    @Override
    public Answer<List<RevolvingElection>> revolvingElections(int periodId) {
        return Answer.unavailable(REVOLVING_GAP);
    }

    /**
     * Every figure-bearing disclosure section, unavailable, with the section's own reason.
     *
     * <p>The reason lives on {@link DisclosureSection} rather than here because it is a fact about
     * what the section needs, not about this particular wiring, and a second implementation
     * reading a real sub-ledger would want the same sentence for the sections it still could not
     * fill.
     *
     * @throws IllegalArgumentException if asked for the register-fed section, which
     *     {@link IndAs107Extract} must never route here
     */
    @Override
    public Answer<List<DisclosureSources.DisclosureLine>> section(
        DisclosureSection section, int periodId) {
        if (section.fedByTheApproximationsRegister()) {
            // A defect in IndAs107Extract, not a data condition. This section's content is the
            // FR-809 register; routing it to a figure source would render the measurement-basis
            // note as a gap and hide the register's own findings inside the gap mechanism.
            throw new IllegalArgumentException(
                section + " is fed by the approximations register and must not be sourced here");
        }
        return Answer.unavailable(switch (section) {
            case INTEREST_REVENUE_EFFECTIVE_INTEREST_METHOD ->
                "no source. The run computes the presented EIR interest per contract, but"
                    + " EirService's public surface is eight methods each returning a rendered"
                    + " Json.Obj and exposes no reader for a run's figures, so a module"
                    + " constructed with an EirService cannot reach them. The figure exists; the"
                    + " seam does not.";
            case LOSS_ALLOWANCE_RECONCILIATION ->
                "no source. ContractStateSource.OpeningState carries an allowance and an ECL"
                    + " engine version per contract, and nothing anywhere computes or holds an"
                    + " opening-to-closing allowance movement — so this table has no producer,"
                    + " not merely no seam.";
            case CREDIT_QUALITY_BY_STAGE ->
                "no source. Stage is carried per contract, so one of the table's two dimensions"
                    + " exists; no credit-risk rating grade is held by any type in the engine, so"
                    + " the other does not.";
            case HEDGING_RESULT ->
                "no source. Nothing in the run output carries a hedge relationship or a basis"
                    + " adjustment, so invariant HB-1 has no population and this section has no"
                    + " figures to disclose separately.";
            case MEASUREMENT_BASIS_AND_APPROXIMATIONS ->
                throw new IllegalStateException("guarded above");
        });
    }
}
