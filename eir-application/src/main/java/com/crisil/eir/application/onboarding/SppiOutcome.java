package com.crisil.eir.application.onboarding;

/**
 * The two outcomes of an SPPI assessment (FR-104).
 *
 * <p>Aligned to the schema, character for character ({@code V1__core_entities.sql}):
 *
 * <pre>
 * CONSTRAINT contract_sppi_outcome_ck
 *     CHECK (sppi_outcome IN ('PASS', 'FAIL'))
 * </pre>
 *
 * <p><b>Two values and no third.</b> The column is nullable and the absence of a row-level
 * outcome is a real and distinct state — the assessment has not been made — but it is modelled by
 * the <em>absence of an {@link SppiAssessment}</em> rather than by a constant here. A
 * {@code NOT_ASSESSED} member would be readable as an outcome by every {@code switch} that handled
 * it, and the whole content of
 * {@code contract_amortised_cost_needs_sppi_ck}'s {@code IS NOT DISTINCT FROM} is that an absent
 * assessment must not be treated as one: the migration's comment records that the first live run
 * "accepted exactly that row", because {@code sppi_outcome = 'PASS'} is satisfied when the column
 * is NULL.
 */
public enum SppiOutcome {

    /**
     * The contractual cash flows are solely payments of principal and interest.
     *
     * <p>The precondition for amortised cost or FVOCI on the asset side. Necessary and not
     * sufficient: the business-model test is the other limb, and it is what
     * {@link MeasurementCategory#FVTPL} designation still expresses on an SPPI-passing instrument
     * held for trading.
     */
    PASS,

    /**
     * They are not — and the whole instrument goes to FVTPL (FR-104).
     *
     * <p>"There is no bifurcation" is FR-104's own wording for the asset side, and 05 § 3.1 calls
     * it "a cliff, not a gradient". There is no reduced, provisional or approximate EIR on the far
     * side of a failure, which is the reasoning
     * {@link com.crisil.eir.calc.projection.blueprint.OptionalitySppiFailureException} already
     * states one module down: "any life returned here would be read as evidence of amortised
     * cost."
     */
    FAIL;

    /** Whether this outcome permits amortised cost or FVOCI on the asset side. */
    public boolean permitsEir() {
        return this == PASS;
    }
}
