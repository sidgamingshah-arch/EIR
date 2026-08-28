package com.crisil.eir.application.onboarding;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A fee or cost posting as the source system sends it — <b>before</b> classification (05 § 3.1's
 * {@code CBS->>API: contract + schedule + fee postings}).
 *
 * <p><b>Why this is not {@link FeePosting}.</b> That record takes an <em>already-resolved</em>
 * {@link com.crisil.eir.domain.FeeClassification} and deliberately declines to re-decide it; its
 * javadoc and {@code FeeClassificationResolver}'s agree that the rule set is "the only place the
 * decision is made". So a posting arriving from a feed cannot be a {@code FeePosting}: it does not
 * yet have a classification, and giving it one at the ingestion boundary would be the second place
 * the decision gets made. This record is what a feed can honestly supply, and
 * {@link InitialRecognition} turns it into a {@code FeePosting} by asking the resolver.
 *
 * <p>The consequence is worth stating because it is the whole reason for the extra type: a fee code
 * the rule set does not map cannot be represented as a {@code FeePosting} at all, which is what
 * makes FR-202's refusal reachable. If the feed's own record carried a classification field, an
 * unmapped code would arrive with something in it.
 *
 * <p><b>Sign.</b> Positive is received by the bank, negative is paid, which is
 * {@code FeePosting}'s convention and {@link Money}'s convention throughout the engine. On
 * reference case 1 the two postings are +15,000 of processing fee and −10,000 of DSA commission,
 * netting to 5,000 of integral fee income — which lifts the reported yield 56.6 basis points over
 * the 12.682503% contractual effective rate.
 *
 * @param feeCode           the rule set's lookup key; never blank
 * @param amount            signed: positive received, negative paid
 * @param postedOn          the posting date — the date the rule set is asked about, and never a
 *                          clock read (03 § 1.1)
 * @param costFunction      {@code SELLING}, {@code PROCESSING}, {@code ADMIN} or {@code OTHER};
 *                          mandatory on an integral cost per ACPIR 53 and FR-203, and carried here
 *                          unvalidated because whether it is required depends on a classification
 *                          this record does not have yet
 * @param drawdownProbability the FR-204 commitment-fee assessment in {@code [0,1]}, or null where
 *                          the posting is not a commitment fee
 */
public record FeeSubmission(
    String feeCode,
    Money amount,
    LocalDate postedOn,
    String costFunction,
    BigDecimal drawdownProbability) {

    public FeeSubmission {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(postedOn, "postedOn");
        feeCode = feeCode.strip();
        if (feeCode.isEmpty()) {
            // Structural. FR-202 requires a refusal to name the key that could not be resolved,
            // and FeeRuleKey refuses a blank code for exactly that reason: "a blank code cannot be
            // classified or refused by name". A posting with no code would produce a queue entry
            // nobody can act on.
            throw new IllegalArgumentException(
                "a fee submission needs a fee code; FR-202's refusal has to name the key it could"
                    + " not resolve, and a blank code names nothing");
        }
    }

    /** A fee received by the bank — positive. */
    public static FeeSubmission received(String feeCode, Money amount, LocalDate postedOn) {
        return new FeeSubmission(feeCode, amount.abs(), postedOn, null, null);
    }

    /**
     * A cost paid by the bank — negative, with its ACPIR 53 cost function.
     *
     * <p>The function is supplied here even though only an {@code INTEGRAL} cost needs one, because
     * the feed knows it and the classification is not yet known. ACPIR 53 capitalises an incentive
     * paid to an employee acting as a selling agent and excludes internal credit-appraisal cost, so
     * a cost that turns out to be integral and arrives without the attribute cannot be placed on
     * either side of that line — {@link com.crisil.eir.policy.exception.ExceptionCategory#MISSING_COST_FUNCTION},
     * FR-203.
     */
    public static FeeSubmission paid(String feeCode, Money amount, LocalDate postedOn,
        String costFunction) {
        Money outflow = amount.isPositive() ? amount.negate() : amount;
        return new FeeSubmission(feeCode, outflow, postedOn, costFunction, null);
    }

    /** A commitment fee with its FR-204 drawdown assessment. */
    public static FeeSubmission commitment(String feeCode, Money amount, LocalDate postedOn,
        BigDecimal drawdownProbability) {
        Objects.requireNonNull(drawdownProbability, "drawdownProbability");
        return new FeeSubmission(feeCode, amount, postedOn, null, drawdownProbability);
    }

    /** One line for a refusal message or an audit trail. */
    public String describe() {
        return feeCode + " " + amount.atPresentationScale() + " posted " + postedOn;
    }

    @Override
    public String toString() {
        return describe();
    }
}
