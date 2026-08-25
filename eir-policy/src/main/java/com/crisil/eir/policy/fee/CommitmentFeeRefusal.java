package com.crisil.eir.policy.fee;

import com.crisil.eir.policy.exception.ExceptionCategory;

/**
 * Why FR-204 could not classify a commitment fee at all.
 *
 * <p>Two gaps, and they are genuinely different failures even though both land in the same
 * exception-queue category. One is missing <em>contract</em> data — nobody assessed this
 * facility. The other is missing <em>policy</em> data — nobody defined what "probable" means
 * for this product. A queue entry that cannot tell an operator which of the two to go and fix
 * is a queue entry that gets closed by guessing.
 *
 * <p><b>Neither is a default.</b> That is the whole content of this enum. A commitment fee with
 * no assessment is not a low-probability commitment, it is an unassessed one, and the two are
 * different facts: reading absence as "not probable" routes the fee to
 * {@code OVER_COMMITMENT_PERIOD} and recognises income over the commitment period that ACPIR 52
 * would have deferred into the loan's EIR, while reading it as "probable" defers income that
 * belongs in this period's revenue. Both directions are wrong and neither leaves a trace,
 * which is why FR-905 makes every category here a hard stop for that contract rather than a
 * fallback (04 § 3).
 *
 * <p>Both map to {@link ExceptionCategory#MISSING_MANDATORY_FIELD} rather than to
 * {@link ExceptionCategory#UNMAPPED_FEE_CODE}. The distinction is worth stating because the
 * second is the closer-looking of the two: an unmapped fee code is the rule set failing to
 * resolve {@code (fee_code, product, entity, effective_date)} to a classification at all
 * (FR-202), which is a different lookup owned by a different artefact. Here the fee code
 * resolved perfectly well — it resolved to "this is a commitment fee, now apply the drawdown
 * test" — and it is an attribute of the posting (04 § 2.5 {@code drawdown_probability}) or of
 * the product (04 § 2.2 {@code drawdown_probability_threshold}) that is absent.
 */
public enum CommitmentFeeRefusal {

    /**
     * The posting carries no numeric {@code drawdown_probability} (04 § 2.5).
     *
     * <p>Reachable only through the assessment-free construction paths —
     * {@code FeePosting.received} and {@code FeePosting.paid} leave the attribute null, since
     * it is meaningless on the overwhelming majority of fees that are not commitment fees.
     * {@code FeePosting.commitment} already refuses a null, so a posting built through the
     * commitment factory can never produce this refusal; a commitment fee that arrived through
     * the general factory can, and does.
     */
    PROBABILITY_NOT_ASSESSED(ExceptionCategory.MISSING_MANDATORY_FIELD),

    /**
     * The product has no {@code drawdown_probability_threshold} in the {@code COMMITMENT_THRESHOLD}
     * policy version being resolved against (04 § 2.2, 03 § 3.2).
     *
     * <p>Refused rather than fallen back to a global figure. FR-204 makes the threshold per
     * product because the evidence is per product — a historical drawdown rate, which is the
     * auditable way to define "probable" (ACPIR 52; reference item 33, undrawn commitments). A
     * single bank-wide number is not that evidence for any product, so a product the policy
     * forgot is a gap in the policy and has to be visible as one. A working capital demand loan
     * draws at a rate nothing like a project finance sanction, and a global 50% would silently
     * misclassify one of them in whichever direction nobody is looking.
     */
    THRESHOLD_NOT_DEFINED_FOR_PRODUCT(ExceptionCategory.MISSING_MANDATORY_FIELD);

    private final ExceptionCategory category;

    CommitmentFeeRefusal(ExceptionCategory category) {
        this.category = category;
    }

    /** Where this refusal is queued (04 § 3). */
    public ExceptionCategory exceptionCategory() {
        return category;
    }

    /**
     * Whether the contract yields no figure at all.
     *
     * <p>Delegated rather than restated, so that a change to the category's own answer cannot
     * leave two disagreeing copies of it. Both refusals here stop the contract today.
     */
    public boolean stopsTheContract() {
        return category.stopsTheContract();
    }
}
