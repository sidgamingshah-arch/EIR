package com.crisil.eir.application.onboarding;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One SPPI assessment: the outcome, the date it was made, and who signed it (FR-104).
 *
 * <p><b>All three or none, and the schema says it in one line</b>
 * ({@code V1__core_entities.sql}):
 *
 * <pre>
 * -- The SPPI assessment, its date and its approver (FR-104). All three or none: an
 * -- outcome without a date and an owner is not an assessment, it is an assertion.
 * sppi_outcome                    TEXT,
 * sppi_assessed_on                DATE,
 * sppi_approver                   TEXT,
 * ...
 * CONSTRAINT contract_sppi_complete_ck
 *     CHECK ((sppi_outcome IS NULL) = (sppi_assessed_on IS NULL)
 *            AND (sppi_outcome IS NULL) = (sppi_approver IS NULL))
 * </pre>
 *
 * <p>The Java form of "all three or none" is a record that cannot be built with two of them: the
 * triple exists whole or the reference is null. That is why the date and the approver are refused
 * at construction rather than reported — an incomplete triple is not a fact about the instrument,
 * it is a row the schema would have rejected, so refusing it here keeps the two boundaries saying
 * the same thing. A contract that has genuinely never been assessed carries no assessment at all,
 * and {@link MeasurementGate} treats that as the gate having been skipped.
 *
 * <p><b>The reason the approver is load-bearing rather than metadata.</b> An SPPI failure moves an
 * entire instrument to fair value through profit or loss and removes it from EIR processing for
 * life. A pass admits it to amortised cost, where its fees amortise across its whole term — on
 * reference case 1's figures, 5,000 of net fee on a million moves the reported yield 56.6 basis
 * points. Either way the assessment is a measurement decision with a P&amp;L consequence, and
 * ADR-0008 forbids manual override precisely so that such decisions are attributable. An outcome
 * with no owner is not attributable to anybody.
 *
 * <p>No clock is read here and none may be: {@code assessedOn} is supplied. 03 § 1.1 makes time an
 * input everywhere, and an assessment date defaulted to "today" would move every time a closed
 * period was replayed, which is DT-1.
 *
 * @param outcome    PASS or FAIL
 * @param assessedOn the date the assessment was made — business time, supplied, never read
 * @param approver   who signed it; never blank
 */
public record SppiAssessment(SppiOutcome outcome, LocalDate assessedOn, String approver) {

    public SppiAssessment {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(assessedOn, "assessedOn");
        Objects.requireNonNull(approver, "approver");
        approver = approver.strip();
        if (approver.isEmpty()) {
            throw new IllegalArgumentException(
                "SPPI outcome " + outcome + " assessed on " + assessedOn + " names no approver."
                    + " 04 § 2.1: an outcome without a date and an owner is not an assessment, it"
                    + " is an assertion — and the schema's contract_sppi_complete_ck would reject"
                    + " the row");
        }
    }

    /** A passing assessment. */
    public static SppiAssessment passed(LocalDate assessedOn, String approver) {
        return new SppiAssessment(SppiOutcome.PASS, assessedOn, approver);
    }

    /** A failing assessment — the instrument goes to FVTPL and carries no EIR (FR-104). */
    public static SppiAssessment failed(LocalDate assessedOn, String approver) {
        return new SppiAssessment(SppiOutcome.FAIL, assessedOn, approver);
    }

    /** Whether this assessment permits amortised cost or FVOCI on the asset side. */
    public boolean permitsEir() {
        return outcome.permitsEir();
    }

    /** One audit line, naming all three components because all three are the evidence. */
    public String describe() {
        return "SPPI " + outcome + " assessed " + assessedOn + " by " + approver;
    }

    @Override
    public String toString() {
        return describe();
    }
}
