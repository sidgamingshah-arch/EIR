package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One fee or cost posting arriving at the projection boundary, with its
 * classification already resolved by the versioned rule set.
 *
 * <p>Sign carries direction, from the holder's perspective: a fee <em>received</em>
 * is positive and a cost <em>paid</em> is negative. Both are integral where the
 * rule set says so, and they net — 15,000 received against 10,000 paid is 5,000
 * of net integral fee, which is what lifts the Case 1 EIR 56.6 basis points above
 * the contractual effective rate.
 *
 * <p>Two constructor rejections, and both are boundary filters rather than
 * judgements:
 *
 * <ul>
 *   <li>{@link FeeClassification#EXCLUDED_BY_DIRECTION} cannot be constructed at
 *       all. Under RBI's 2023 framework penal amounts are <em>charges</em>, not
 *       penal interest: not capitalised, bearing no further interest, and
 *       therefore incapable of entering the amortisation schedule or the gross
 *       carrying amount. Legacy core banking systems routinely book penal amounts
 *       into the interest ledger, which is exactly why this is a hard filter with
 *       a positive assertion each period (invariant {@link InvariantId#PC_1})
 *       rather than a rule the rule set could be configured out of.
 *   <li>An integral <em>cost</em> posting with no {@code costFunction} is
 *       rejected (FR-203). ACPIR 53 draws the line at <em>selling</em>, not
 *       processing: an incentive paid to an employee acting as a selling agent is
 *       a capitalisable transaction cost, while the salary of the credit-appraisal
 *       team is internal administrative cost and is excluded. Source HR and
 *       cost-centre data is structured along neither, so the attribute is
 *       mandatory and its absence fails the posting instead of defaulting.
 * </ul>
 *
 * <p>What this record deliberately does <em>not</em> re-decide is which cost
 * function is capitalisable. {@code classification} arrives already resolved by
 * the rule set keyed on {@code (fee_code, product, entity, effective_date)};
 * re-testing it here would put the same judgement in two places and let them
 * disagree. An {@code INTEGRAL} posting carrying {@code ADMIN} is a rule-set
 * defect, and it belongs in the rule set's own control, not in a second opinion
 * held by the projector.
 *
 * <p>Contingent charges — prepayment penalty, late fee, bounce charge — reach the
 * projector classified {@link FeeClassification#AS_INCURRED} and are excluded
 * from the inception projection regardless of being contractually specified
 * (FR-206). Only {@code INTEGRAL} postings enter the initial carrying amount.
 *
 * @param feeCode            the rule set's key; retained for the computation trace
 * @param amount             signed: positive received, negative paid
 * @param postedOn           when the posting was raised, retained for audit
 * @param classification     the rule set's resolved treatment
 * @param costFunction       {@code SELLING}, {@code PROCESSING}, {@code ADMIN} or
 *                           {@code OTHER}; mandatory on an integral cost
 * @param drawdownProbability the commitment-fee assessment (FR-204), in
 *                           {@code [0,1]}, or null where the fee is not a
 *                           commitment fee
 */
public record FeePosting(
    String feeCode,
    Money amount,
    LocalDate postedOn,
    FeeClassification classification,
    String costFunction,
    BigDecimal drawdownProbability) {

    /**
     * The cost-function vocabulary the ingestion layer must populate.
     *
     * <p>Ordered, so that a rejection message reads the same on every run — an
     * exception-queue entry is a record, and a record that varies between
     * identical runs is not one.
     */
    public static final List<String> COST_FUNCTIONS = List.of("SELLING", "PROCESSING", "ADMIN", "OTHER");

    public FeePosting {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(postedOn, "postedOn");
        Objects.requireNonNull(classification, "classification");
        if (feeCode.isBlank()) {
            throw new IllegalArgumentException("feeCode must not be blank");
        }
        if (classification == FeeClassification.EXCLUDED_BY_DIRECTION) {
            throw new IllegalArgumentException(
                "fee code " + feeCode + " resolves to EXCLUDED_BY_DIRECTION and cannot enter any EIR cash"
                    + " flow stream or the gross carrying amount. Penal charges under RBI's 2023 framework"
                    + " are charges, not penal interest: not capitalised, bearing no further interest."
                    + " Rejected at the ingestion boundary and asserted for the period as invariant "
                    + InvariantId.PC_1);
        }
        String function = costFunction == null ? null : costFunction.trim().toUpperCase(Locale.ROOT);
        if (function != null && !function.isEmpty() && !COST_FUNCTIONS.contains(function)) {
            throw new IllegalArgumentException(
                "costFunction must be one of " + COST_FUNCTIONS + ", got " + costFunction);
        }
        boolean integralCost = classification == FeeClassification.INTEGRAL && amount.isNegative();
        if (integralCost && (function == null || function.isEmpty())) {
            throw new IllegalArgumentException(
                "fee code " + feeCode + " is an integral cost paid by the bank and carries no cost_function."
                    + " ACPIR 53 capitalises a selling-agent incentive and excludes internal"
                    + " credit-appraisal cost, so the selling-versus-processing attribute is mandatory"
                    + " (FR-203); the posting fails rather than defaulting to either treatment");
        }
        if (drawdownProbability != null
            && (drawdownProbability.signum() < 0 || drawdownProbability.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(
                "drawdownProbability must be in [0,1], got " + drawdownProbability.toPlainString());
        }
        costFunction = function == null || function.isEmpty() ? null : function;
    }

    /** A fee received — positive to the holder. */
    public static FeePosting received(String feeCode, Money amount, LocalDate postedOn,
        FeeClassification classification) {
        return new FeePosting(feeCode, amount, postedOn, classification, null, null);
    }

    /**
     * A cost paid — negative to the holder, with the ACPIR 53 cost function that
     * the rule set used and the projector records.
     */
    public static FeePosting paid(String feeCode, Money amount, LocalDate postedOn,
        FeeClassification classification, String costFunction) {
        Money outflow = amount.isPositive() ? amount.negate() : amount;
        return new FeePosting(feeCode, outflow, postedOn, classification, costFunction, null);
    }

    /**
     * A commitment fee with its drawdown assessment.
     *
     * <p>ACPIR 52 omits IFRS 9 B5.4.2(b)'s probable-drawdown condition, so read
     * literally every commitment fee defers — including on facilities that were
     * never going to draw. The numeric threshold is per product in policy; this
     * record only carries the assessment and refuses to be constructed without it.
     */
    public static FeePosting commitment(String feeCode, Money amount, LocalDate postedOn,
        FeeClassification classification, BigDecimal drawdownProbability) {
        Objects.requireNonNull(drawdownProbability, "drawdownProbability");
        return new FeePosting(feeCode, amount, postedOn, classification, null, drawdownProbability);
    }

    /** Whether this posting enters the initial carrying amount and amortises via the EIR. */
    public boolean entersInitialCarryingAmount() {
        return classification.entersCarryingAmount();
    }
}
