package com.crisil.eir.application.transition;

import com.crisil.eir.policy.transition.MigrationMethod;

/**
 * Where a legacy contract's EIR actually comes from, once the plan's method has been applied to it
 * (FR-908, FR-909, 04 § 6).
 *
 * <p><b>Why this is not {@link MigrationMethod}.</b> The method is the plan's <em>intention</em> for
 * a cohort: reconstruct, or deem. This is the contract's <em>position</em> against that intention,
 * and the two differ in the cases that matter. A cohort queued for {@code FULL_RECONSTRUCTION} whose
 * contracts have not been solved yet is the ordinary state of the programme in 2028; a cohort on
 * {@code DEEMED_EIR} with no derivation prepared is the state DE-1 exists to catch; a contract in
 * the population that no cohort claims has no method at all. Reporting the method alone would show
 * all four of those as "reconstruct" or "deem" and hide which of them has a rate.
 *
 * <p>There is deliberately no constant meaning "migrated", because none of these four is a
 * statement about ACPIR 21 or ACPIR 50 coverage — that is {@link TransitionCoverage}'s subject,
 * measured per contract from the recorded positions, and a rate existing is not the same fact as
 * interest being recognised on it.
 */
public enum LegacyRateBasis {

    /**
     * The original flows were reconstructed and a rate is in force ({@code FULL_RECONSTRUCTION},
     * done).
     *
     * <p>The right answer and the expensive one. The rate is read off the contract's state rather
     * than solved here: this exercise reports the transition position, and re-solving a rate that
     * already exists would produce a second EIR for one contract, differing in whatever the two
     * projections disagreed about.
     */
    RECONSTRUCTED,

    /**
     * Queued for reconstruction and not yet solved ({@code FULL_RECONSTRUCTION}, outstanding).
     *
     * <p><b>Not a defect, and deliberately not a blocking reason.</b> Between 1 April 2027 and 31
     * March 2030 this is what an unfinished migration looks like, and the whole of ACPIR 21's window
     * exists to permit it. Treating it as a breach would make the transition pack red for three
     * years, which is the reasoning TM-1's javadoc gives for its own formulation: a control that is
     * red by design gets suppressed, and then it is not there for the year it matters. It is
     * published as plain data, which is what a programme tracks.
     */
    RECONSTRUCTION_OUTSTANDING,

    /**
     * Measured on a deemed rate with an approved derivation on file ({@code DEEMED_EIR}, signed).
     *
     * <p>Legitimate where reconstruction is genuinely infeasible. The rate comes from the
     * derivation, and the approval is what separates a considered assumption from an unwillingness
     * to look.
     */
    DEEMED,

    /**
     * On a deemed basis with no derivation, or one nobody has signed ({@code DEEMED_EIR},
     * unbacked).
     *
     * <p>Both shapes land here and are distinguished by whether the assignment carries a rate: a
     * cohort with no derivation has none, and one with an unapproved derivation has a rate nobody
     * has authorised income to be recognised on. The remedies differ — prepare one, or sign it —
     * which is why {@code LegacyMigrationPlan.deemedRatesApproved()} names them separately in its
     * detail.
     *
     * <p>This constant does not assert DE-1: DE-1 is published once, over the cohorts, by that same
     * evaluator. This is the per-contract view of the same fact, for a programme team working the
     * file, and it is read off the same accessor ({@code DeemedEirDerivation.isApproved()}) rather
     * than from a second reading of the rule.
     */
    DEEMED_UNBACKED,

    /**
     * No cohort claims this contract, so no method applies to it.
     *
     * <p>The gap that a coverage report over cohorts cannot show, because a contract no cohort names
     * is absent from every cohort's count. It is a blocking reason on {@link TransitionRun}: a
     * contract with no method has nothing saying how it comes onto the EIR by 31 March 2030, and
     * {@code LegacyCohort.definition} being free text means the engine cannot place it either.
     */
    UNASSIGNED;

    /**
     * Whether the rate rests on an assumption rather than on reconstructed flows.
     *
     * <p>Deliberately no {@code hasRate()} here. Whether a rate exists is a property of the
     * assignment and not of the constant — {@link #DEEMED_UNBACKED} covers both a cohort with no
     * derivation, which has no rate, and one with an unsigned derivation, which has one — so the
     * question is answered by {@code LegacyRateAssignment.hasRate()} against the actual field. An
     * enum method claiming a rate exists would be right three times in four and wrong exactly where
     * the programme is behind.
     */
    public boolean restsOnAnAssumption() {
        return this == DEEMED || this == DEEMED_UNBACKED;
    }

    /** Whether migration work remains for this contract. */
    public boolean isOutstanding() {
        return this == RECONSTRUCTION_OUTSTANDING || this == DEEMED_UNBACKED
            || this == UNASSIGNED;
    }
}
