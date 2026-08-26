package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.Rate;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One contract's position against the <em>two</em> ACPIR migration obligations (04 § 6).
 *
 * <p><b>They share a deadline and they are not the same requirement.</b> ACPIR 21 requires the
 * loan under the EIR regime; ACPIR 50 requires its ECL discounting to move from the interim
 * contractual rate to the EIR. A contract can satisfy the first and not the second — indeed that is
 * the expected sequence, since ACPIR 50 explicitly concedes the interim basis — and the gap is
 * invisible to anything reading a single migration flag. 04 § 6 gives the discount basis its own
 * table for exactly this reason, and this type carries both facts for the same reason.
 *
 * <p>The pair is what makes the gap measurable. One boolean called {@code migrated} would be true
 * for a contract recognising interest on the EIR while discounting its ECL at the contractual rate,
 * and that is the state ACPIR 50's deadline exists to close.
 *
 * @param contractId             the contract
 * @param periodId               the period this state describes; the schema keys on the pair
 * @param interestRecognisedOnEir whether ACPIR 21 is satisfied for this contract
 * @param eclDiscountBasis       what ACPIR 50 is satisfied by, or deferred under
 * @param eirComputationId       which solve supplied the rate; required when the basis is EIR
 * @param rateUsed               the rate actually used to discount, where recorded
 * @param eclMigratedOn          when the ECL basis moved; required when the basis is EIR
 * @param migrationEvidenceRef   a working-paper reference for the migration, where one exists
 */
public record ContractMigrationState(
    String contractId,
    int periodId,
    boolean interestRecognisedOnEir,
    EclDiscountBasis eclDiscountBasis,
    String eirComputationId,
    Rate rateUsed,
    LocalDate eclMigratedOn,
    String migrationEvidenceRef) {

    public ContractMigrationState {
        contractId = FourEyes.requireIdentity(contractId, "contractId",
            "a migration state has to name the contract it describes");
        Objects.requireNonNull(eclDiscountBasis, "eclDiscountBasis");
        eirComputationId = blankToNull(eirComputationId);
        migrationEvidenceRef = blankToNull(migrationEvidenceRef);

        if (eclDiscountBasis.satisfiesAcpir50()) {
            // The schema's ck_ecl_discount_basis_eir_has_computation, restated, and its comment is
            // the argument: "migrated to the EIR" with no EIR to point at is a claim rather than a
            // fact. Refused rather than reported because there is nothing intermediate about it —
            // a row saying the migration happened, with nothing identifying what it migrated to,
            // cannot be verified later by anyone.
            if (eirComputationId == null) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " period " + periodId + " claims its ECL is"
                        + " discounted at the EIR but names no computation; migrated to the EIR"
                        + " with no EIR to point at is a claim rather than a fact");
            }
            if (eclMigratedOn == null) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " period " + periodId + " claims its ECL is"
                        + " discounted at the EIR with no migration date, so nothing can say"
                        + " whether it was migrated before the ACPIR 50 deadline");
            }
        }
    }

    /** A contract still on the interim basis, with nothing yet to point at. */
    public static ContractMigrationState onInterimBasis(
        String contractId, int periodId, boolean interestRecognisedOnEir) {
        return new ContractMigrationState(contractId, periodId, interestRecognisedOnEir,
            EclDiscountBasis.CONTRACTUAL_INTERIM, null, null, null, null);
    }

    /** A contract whose ECL discounting has moved to the EIR. */
    public static ContractMigrationState migrated(
        String contractId, int periodId, String eirComputationId, Rate rateUsed,
        LocalDate migratedOn, String evidenceRef) {
        return new ContractMigrationState(contractId, periodId, true,
            EclDiscountBasis.EIR, eirComputationId, rateUsed, migratedOn, evidenceRef);
    }

    /** Whether ACPIR 21 is satisfied: the loan is under the EIR regime for recognition. */
    public boolean satisfiesAcpir21() {
        return interestRecognisedOnEir;
    }

    /** Whether ACPIR 50 is satisfied: the ECL is discounted at the EIR. */
    public boolean satisfiesAcpir50() {
        return eclDiscountBasis.satisfiesAcpir50();
    }

    /**
     * The expected gap: on the EIR for interest, still on the contractual rate for ECL.
     *
     * <p>Not a defect before 31 March 2030 — it is the shape ACPIR 50's concession describes. It
     * is the population the migration has to work through, and it is the population that becomes a
     * breach on 1 April 2030.
     */
    public boolean isUnderTheAcpir50Concession() {
        return satisfiesAcpir21() && !satisfiesAcpir50();
    }

    /**
     * The reverse gap: ECL discounted at the EIR while interest is not recognised on it.
     *
     * <p>Possible during a phased cutover — an EIR has to exist before the ECL model can use it,
     * so a contract can be reconstructed for discounting before recognition is switched over — and
     * odd enough to surface. Reported rather than refused for that reason: it is a sequencing
     * signal, not an impossible state, and refusing it would make a legitimate cutover order
     * unrepresentable.
     */
    public boolean eclIsAheadOfInterest() {
        return satisfiesAcpir50() && !satisfiesAcpir21();
    }

    /** Whether the interim basis has outlived the concession as at {@code asOf}. */
    public boolean isPastTheAcpir50Deadline(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !satisfiesAcpir50() && asOf.isAfter(LegacyCohort.ACPIR_50_DEADLINE);
    }

    /** A one-line statement of both positions. */
    public String describe() {
        return "contract " + contractId + " period " + periodId
            + ": interest " + (satisfiesAcpir21() ? "on the EIR (ACPIR 21 met)"
                : "not yet on the EIR (ACPIR 21 outstanding)")
            + ", ECL discounted at " + eclDiscountBasis
            + (satisfiesAcpir50()
                ? " (ACPIR 50 met, migrated " + eclMigratedOn + " on " + eirComputationId + ")"
                : " (ACPIR 50 deferred under the interim concession)");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
