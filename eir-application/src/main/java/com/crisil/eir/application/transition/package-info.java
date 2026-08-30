/**
 * The ACPIR 19–21 and 50 transition exercise: the use case that finally calls
 * {@code eir-policy.transition}.
 *
 * <p><b>What this package closes.</b> docs/08 recorded the gap plainly — nothing in
 * {@code eir-application} reached {@code policy/transition} at all, so
 * {@code TransitionValuationRun}, {@code LegacyMigrationPlan}, {@code MigrationTracker} and
 * {@code BelowMarketOrigination} were live code reachable only from their own tests, and invariants
 * BM-1, DE-1 and TM-1 were evaluators with no caller. An evaluator nobody invokes is
 * indistinguishable from an absent one: it passes its unit tests forever and is never once asked
 * about the book. {@link com.crisil.eir.application.transition.TransitionExercise} invokes them,
 * over a population read through the ports, in the order 04 § 6 and 08 Phase 4 state.
 *
 * <p><b>What it deliberately does not do.</b> It derives no invariant of its own. Every result it
 * publishes comes from the policy evaluator that owns the question —
 * {@code TransitionValuationRun.paragraph19Evidenced()} for TF-1,
 * {@code LegacyMigrationPlan.invariants()} for LC-1 and DE-1,
 * {@code MigrationTracker.migrationTracked(asOf)} for TM-1 and
 * {@code BelowMarketOrigination.destinationsApproved(...)} for BM-1. Restating any of them here
 * would publish a second answer under an identifier entitled to one, which is a defect this
 * codebase records finding three times.
 *
 * <p><b>The two obligations stay two.</b> 04 § 6: "ACPIR 21 and ACPIR 50 are two obligations with a
 * common deadline, not one: the loan must come under the EIR regime, and its ECL discounting must
 * migrate to the EIR. Tracking them in one field would hide a gap."
 * {@link com.crisil.eir.application.transition.TransitionCoverage} therefore reports two coverage
 * figures against two deadlines and offers no combined one — see its javadoc for why the absence is
 * the control.
 *
 * <p><b>No clock.</b> The transition date, the as-at boundary and therefore the date TM-1 is
 * asserted on all arrive as arguments (03 § 1.1, ADR-0003). The exercise is re-runnable to the same
 * boundary tomorrow, which is not only a replay requirement — "re-run the transition pack" is an
 * ordinary request during a three-year migration programme.
 */
package com.crisil.eir.application.transition;
