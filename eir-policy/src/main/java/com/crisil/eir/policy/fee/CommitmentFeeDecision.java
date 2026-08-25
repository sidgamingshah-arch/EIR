package com.crisil.eir.policy.fee;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link CommitmentFeePolicy} concluded about one commitment fee: a classification, or a
 * refusal naming the gap that stopped it (FR-204).
 *
 * <p>One record for both outcomes rather than an exception for the refusal, because a refusal
 * is an <em>output</em> of this policy and not a malfunction of it. An unassessed commitment fee
 * is a routine consequence of source data that was never structured for ACPIR, and the close
 * needs it counted, categorised and reported alongside the fees that classified cleanly — which
 * a thrown exception at the point of classification makes awkward and a swallowed one makes
 * impossible.
 *
 * <p>Every field the decision turned on is retained, including the ones that are only
 * interesting when they agree. A stored decision that says {@code INTEGRAL} and nothing else
 * cannot be re-derived two years later when the threshold has moved twice: the assessed 0.72,
 * the threshold 0.60 it was tested against and the policy version that supplied it are the
 * whole audit answer to "why is this fee in the carrying amount". The ACPIR reference's retention
 * inventory asks for exactly that: for commitment fees, the drawdown-probability assessment and
 * the threshold it was tested against, held per posting.
 *
 * <p><b>Numeric domains are checked at their source, not here.</b> {@code FeePosting} already
 * enforces {@code drawdownProbability ∈ [0,1]} at the ingestion boundary and
 * {@link CommitmentFeePolicy} enforces the same closed interval on every threshold as it is
 * loaded. Re-testing either here would put one judgement in two places and licence them to
 * disagree — the defect {@code FeePosting} itself warns about when it declines to re-decide the
 * rule set's classification.
 *
 * <p><b>On a refusal both figures are best-effort and either may be null.</b> A classified
 * decision always carries both — it could not have compared them otherwise. A refusal carries
 * whichever was available, and the two gaps are not exclusive: a fee with no assessment on a
 * product with no threshold refuses for the assessment (that being the one the front office can
 * supply) and has nothing to put in either field. A consumer building an operator message from a
 * refusal must therefore test both for null rather than inferring presence from the refusal
 * reason.
 *
 * @param feeCode              the posting's fee code, retained for the computation trace
 * @param productId            the product whose threshold was applied, normalised
 * @param assessedProbability  the posting's assessment; non-null when classified, may be null on
 *                             any refusal
 * @param threshold            the product's threshold; non-null when classified, may be null on
 *                             any refusal
 * @param classification       {@code INTEGRAL} or {@code OVER_COMMITMENT_PERIOD}; null when refused
 * @param refusal              the gap that stopped classification; null when classified
 * @param policyVersionId      the {@code COMMITMENT_THRESHOLD} version consulted, for replay
 * @param detail               a one-line statement of the comparison or the gap
 */
public record CommitmentFeeDecision(
    String feeCode,
    String productId,
    BigDecimal assessedProbability,
    BigDecimal threshold,
    FeeClassification classification,
    CommitmentFeeRefusal refusal,
    String policyVersionId,
    String detail) {

    public CommitmentFeeDecision {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(detail, "detail");
        // Exactly one outcome. Both set is a contradiction the reader would resolve by whichever
        // field they happened to check first — a caller testing isRefused() would queue the fee
        // while a caller reading classification() would capitalise it, from one record.
        if ((classification == null) == (refusal == null)) {
            throw new IllegalArgumentException(
                "fee code " + feeCode + " needs exactly one of a classification and a refusal, got "
                    + "classification=" + classification + " refusal=" + refusal);
        }
        if (classification != null
            && classification != FeeClassification.INTEGRAL
            && classification != FeeClassification.OVER_COMMITMENT_PERIOD) {
            // FR-204 has two outcomes and no third. AS_INCURRED and SEPARATE_SERVICE are real
            // classifications for other fees resolved by other rules (03 § 3.2), and a
            // commitment fee reaching either of them means the drawdown test was bypassed
            // rather than applied. EXCLUDED_BY_DIRECTION cannot even be constructed on a
            // posting, and a commitment fee is not a penal charge.
            throw new IllegalArgumentException(
                "fee code " + feeCode + " resolved to " + classification + "; the drawdown test"
                    + " (FR-204) yields INTEGRAL or OVER_COMMITMENT_PERIOD and nothing else");
        }
    }

    /** A classified commitment fee, with both figures the comparison used. */
    public static CommitmentFeeDecision classified(String feeCode, String productId,
        BigDecimal assessedProbability, BigDecimal threshold, FeeClassification classification,
        String policyVersionId, String detail) {
        Objects.requireNonNull(assessedProbability, "assessedProbability");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(classification, "classification");
        return new CommitmentFeeDecision(feeCode, productId, assessedProbability, threshold,
            classification, null, policyVersionId, detail);
    }

    /**
     * A refusal. Whichever of the two figures was available is still retained: the assessed 0.72
     * that could not be tested is what the operator resolving the queue entry needs, and
     * discarding it because the other side was missing would make the entry unactionable. Both may
     * be absent — the two gaps are independent and can arrive together.
     */
    public static CommitmentFeeDecision refused(String feeCode, String productId,
        BigDecimal assessedProbability, BigDecimal threshold, CommitmentFeeRefusal refusal,
        String policyVersionId, String detail) {
        Objects.requireNonNull(refusal, "refusal");
        return new CommitmentFeeDecision(feeCode, productId, assessedProbability, threshold,
            null, refusal, policyVersionId, detail);
    }

    /** Whether this fee stopped in the exception queue instead of classifying. */
    public boolean isRefused() {
        return refusal != null;
    }

    /** Whether the drawdown test ran and produced a treatment. */
    public boolean isClassified() {
        return classification != null;
    }

    /** Where a refusal is queued (04 § 3), empty on a classified decision. */
    public Optional<ExceptionCategory> exceptionCategory() {
        return refusal == null ? Optional.empty() : Optional.of(refusal.exceptionCategory());
    }

    /**
     * The classification, for callers that have already established the decision classified.
     *
     * @throws IllegalStateException on a refusal — there is no treatment to return, and
     *     returning any of the five would be inventing the answer the refusal exists to withhold
     */
    public FeeClassification classificationOrThrow() {
        if (classification == null) {
            throw new IllegalStateException(
                "fee code " + feeCode + " was refused (" + refusal + "): " + detail);
        }
        return classification;
    }

    /**
     * Whether drawdown was assessed as probable, so the fee is integral to the EIR of the loan
     * that is expected to result (ACPIR 52, IFRS 9 B5.4.2(b)).
     *
     * <p>Asked of the classification rather than by re-running the comparison. Two places
     * deciding "is this probable" is how the answer starts depending on which one you ask.
     */
    public boolean drawdownProbable() {
        return classification == FeeClassification.INTEGRAL;
    }

    /**
     * The deferral this decision implies, ready for its terminal event.
     *
     * <p>The bridge from classification to recognition, and the reason it exists on the decision
     * is that the deferral must carry the classification the decision reached — a deferral built
     * independently of the decision could spread an {@code INTEGRAL} fee over the commitment
     * period, which is the misrecognition FR-204 exists to prevent.
     *
     * @throws IllegalStateException on a refused decision; a fee that could not be classified
     *     has no recognition pattern to follow, and a refusal that quietly produced a deferral
     *     would defeat the hard stop (FR-905)
     */
    public CommitmentFeeDeferral defer(Money amount, LocalDate commitmentStart,
        LocalDate commitmentEnd) {
        return new CommitmentFeeDeferral(feeCode, productId, amount, commitmentStart, commitmentEnd,
            classificationOrThrow(), policyVersionId);
    }

    /** A one-line audit sentence, in the shape {@code PolicyVersion.describe()} established. */
    public String describe() {
        return "commitment fee " + feeCode + " on product " + productId + ": "
            + (isRefused()
                ? refusal + " (" + refusal.exceptionCategory() + ")"
                : classification.toString())
            + " — " + detail + " [policy " + policyVersionId + "]";
    }
}
