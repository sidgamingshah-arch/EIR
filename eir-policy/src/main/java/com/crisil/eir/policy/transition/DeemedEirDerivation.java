package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.Rate;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The documented basis for a deemed EIR (FR-909, 04 § 6).
 *
 * <p><b>Why every field here is mandatory.</b> A deemed rate is a legitimate answer where full
 * reconstruction is genuinely infeasible, and it is also the answer that hides an unwillingness to
 * look. The two are indistinguishable from the rate alone — a number is a number — so the record
 * has to carry both halves of the justification: <em>why reconstruction failed</em>
 * ({@code infeasibilityReason}) and <em>how the rate was arrived at instead</em>
 * ({@code documentedBasis}). Either one on its own leaves the other assumed.
 *
 * <p><b>And why the approval is not optional in use.</b> A deemed rate recognises income on an
 * assumption, every period, for the rest of the exposure's life. Preparing one is analysis;
 * measuring a cohort on it is a decision. So an unapproved derivation constructs — that is the
 * normal state of one in flight — and invariant DE-1, on {@link LegacyMigrationPlan}, is the
 * assertion that no cohort is <em>measured</em> on a rate nobody signed.
 *
 * @param cohortName          the cohort this applies to, or null for a single-contract derivation
 * @param contractId          the contract, or null for a cohort-wide one; not both null
 * @param deemedRate          the rate the engine will use
 * @param basis               which of the four bases was used
 * @param infeasibilityReason why reconstruction was not possible
 * @param documentedBasis     how the rate was arrived at
 * @param evidenceRef         a working-paper reference, where one exists
 * @param preparedBy          who derived it
 * @param approvedBy          who approved recognising income on it, or null while unapproved
 * @param approvedOn          when; present exactly when {@code approvedBy} is
 */
public record DeemedEirDerivation(
    String cohortName,
    String contractId,
    Rate deemedRate,
    DeemedEirBasis basis,
    String infeasibilityReason,
    String documentedBasis,
    String evidenceRef,
    String preparedBy,
    String approvedBy,
    LocalDate approvedOn) {

    public DeemedEirDerivation {
        Objects.requireNonNull(deemedRate, "deemedRate");
        Objects.requireNonNull(basis, "basis");
        cohortName = blankToNull(cohortName);
        contractId = blankToNull(contractId);
        evidenceRef = blankToNull(evidenceRef);
        approvedBy = blankToNull(approvedBy);
        infeasibilityReason = FourEyes.requireIdentity(infeasibilityReason, "infeasibilityReason",
            "a deemed rate with no stated reason reconstruction failed is a rate nobody tried to"
                + " reconstruct");
        documentedBasis = FourEyes.requireIdentity(documentedBasis, "documentedBasis",
            "the basis is what an auditor reads instead of the flows that no longer exist");
        preparedBy = FourEyes.requireIdentity(preparedBy, "preparedBy",
            "an unattributed derivation is not a derivation");

        if (cohortName == null && contractId == null) {
            // The schema's num_nonnulls(cohort_id, contract_id) >= 1, restated: a derivation
            // applies to a cohort or to one contract, and neither means it applies to nothing.
            throw new IllegalArgumentException(
                "a deemed EIR derivation names neither a cohort nor a contract, so it applies to"
                    + " nothing; a rate with no subject cannot be the basis of any measurement");
        }
        if (approvedBy != null && FourEyes.isSelfApproval(preparedBy, approvedBy)) {
            throw new IllegalArgumentException(
                "deemed EIR for " + subject() + " is prepared and approved by '" + preparedBy
                    + "'; the person who assumed the rate is not a second opinion on recognising"
                    + " income from it");
        }
        if ((approvedBy == null) != (approvedOn == null)) {
            throw new IllegalArgumentException(
                "deemed EIR for " + subject() + " has approver '" + approvedBy + "' and date "
                    + approvedOn + "; an approval is a person and a date, and half of one is"
                    + " neither an approval nor an absence of one");
        }
    }

    /** What this derivation applies to — a cohort, or one contract. */
    public String subject() {
        return cohortName != null ? "cohort " + cohortName : "contract " + contractId;
    }

    /** Whether somebody other than the preparer has approved recognising income on this rate. */
    public boolean isApproved() {
        return approvedBy != null;
    }

    /** Whether this derivation governs {@code cohort}. */
    public boolean appliesToCohort(String cohort) {
        return cohortName != null && cohortName.equals(cohort);
    }

    /**
     * Whether the basis reproduces the contractual rate with nothing added.
     *
     * <p>{@link DeemedEirBasis#CONTRACTUAL_RATE_PLUS_FEE_LOADING} with a loading of nil is the
     * pre-ACPIR position wearing the language of an EIR, and it is the shape an auditor reads
     * hardest. Published as a question rather than a refusal because a genuinely nil loading is
     * possible — a facility with no integral fees or costs has an EIR equal to its contractual
     * rate, which is the correct answer and not a shortcut. What is not defensible is that
     * outcome arriving without anyone having looked, so it is surfaced.
     *
     * @param contractualRate the exposure's contractual rate, for comparison
     */
    public boolean reproducesTheContractualRate(Rate contractualRate) {
        Objects.requireNonNull(contractualRate, "contractualRate");
        return basis == DeemedEirBasis.CONTRACTUAL_RATE_PLUS_FEE_LOADING
            && deemedRate.periodic().compareTo(contractualRate.periodic()) == 0;
    }

    /** A one-line audit sentence naming the rate, its basis and its approval. */
    public String describe() {
        return "deemed EIR " + deemedRate.periodic().toPlainString() + " for " + subject()
            + " on " + basis + " (" + documentedBasis + "), reconstruction infeasible: "
            + infeasibilityReason
            + (evidenceRef == null ? "" : " [" + evidenceRef + "]")
            + ", prepared by " + preparedBy
            + (isApproved() ? ", approved by " + approvedBy + " on " + approvedOn
                : ", UNAPPROVED");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
