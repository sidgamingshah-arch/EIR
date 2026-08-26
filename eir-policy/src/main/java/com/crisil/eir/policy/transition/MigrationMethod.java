package com.crisil.eir.policy.transition;

/**
 * How a legacy cohort is brought onto the EIR (04 § 6, FR-908, FR-909).
 */
public enum MigrationMethod {

    /**
     * The original cash flows and fees are reconstructed and the rate is solved.
     *
     * <p>The right answer, and the expensive one. Capacity for it is finite, which is why the
     * prioritisation rule (LC-1) exists: spent on a cohort that runs off before 31 March 2030 it
     * buys nothing, because that exposure never needs a reconstructed rate.
     */
    FULL_RECONSTRUCTION,

    /**
     * A deemed rate, with the derivation recorded and approved (FR-909).
     *
     * <p>Legitimate where reconstruction is genuinely infeasible — the source records do not
     * exist, or exist in a form nobody can attest to. Also the answer that hides an unwillingness
     * to look, which is why {@link DeemedEirDerivation} makes the infeasibility reason and the
     * documented basis mandatory and invariant DE-1 requires the approval.
     */
    DEEMED_EIR;

    /** Whether this method recognises income on an assumption rather than on reconstructed flows. */
    public boolean restsOnAnAssumption() {
        return this == DEEMED_EIR;
    }
}
