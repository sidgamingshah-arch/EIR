package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The ACPIR 19 day-1 fair valuation of one contract (FR-908, 04 § 6).
 *
 * <p><b>The difference goes to opening retained earnings, not to profit or loss.</b> That is the
 * whole accounting content of ACPIR 19 and it is the thing most easily got wrong, because a
 * remeasurement that lands in P&amp;L looks like a plausible answer and moves a reported number
 * that nobody expected to move. This type names the destination in
 * {@link #differenceToOpeningRetainedEarnings()} and offers no accessor that would let the figure
 * be read as a period result — the naming is the control, since there is no ledger here to post
 * it wrongly.
 *
 * <p><b>One fact, one field.</b> The schema carries {@code valuation_technique} and
 * {@code para_19_presumption_applied} as two columns with a CHECK keeping them consistent, because
 * a boolean column is what a report filters on. In Java the flag is derived from the technique
 * ({@link ValuationTechnique#appliesParagraph19Presumption()}), so the inconsistency the CHECK
 * exists to prevent is unrepresentable rather than prevented.
 *
 * <p><b>Where this reports and the schema refuses.</b> {@code transition_fair_value} rejects a row
 * claiming the presumption with no evidence reference. This type accepts it and reports TF-1
 * instead, and the difference is deliberate: a <em>stored</em> row is a claim that the valuation is
 * final, and storing an unevidenced presumption is exactly what the constraint should forbid. A
 * <em>computed</em> one, during the FY27 exercise the roadmap says builds the evidence file across
 * the year rather than at the transition date, is a real and normal intermediate state. Refusing to
 * construct it would make the remaining work unmeasurable — the same argument RS-1 makes for a
 * partially-loaded fee taxonomy: refusing the state makes the gap invisible, not absent.
 *
 * <p>Two things are still refused at construction, because they are not intermediate states:
 * a discounted cash flow with no discount rate has not been performed, and a review by the person
 * who measured it is not a review.
 *
 * @param contractId                  the contract valued
 * @param transitionDate              1 April 2027 for ACPIR, or a below-market origination's own
 *                                    day 1 — the same structure serves both (FR-909)
 * @param preTransitionCarryingAmount what the book carried before the valuation
 * @param fairValue                   the measured fair value; an input, not computed here
 * @param technique                   how it was arrived at
 * @param discountRateUsed            required for {@code DISCOUNTED_CASH_FLOW}, else null
 * @param paragraph19EvidenceRef      the rebuttal evidence file reference, where the presumption
 *                                    was applied; null where it was not, and null-with-presumption
 *                                    is what TF-1 reports
 * @param measuredBy                  who performed the valuation
 * @param reviewedBy                  who reviewed it, or null while unreviewed
 */
public record TransitionFairValue(
    String contractId,
    LocalDate transitionDate,
    Money preTransitionCarryingAmount,
    Money fairValue,
    ValuationTechnique technique,
    Rate discountRateUsed,
    String paragraph19EvidenceRef,
    String measuredBy,
    String reviewedBy) {

    public TransitionFairValue {
        contractId = FourEyes.requireIdentity(contractId, "contractId",
            "a valuation has to name what it valued");
        Objects.requireNonNull(transitionDate, "transitionDate");
        Objects.requireNonNull(preTransitionCarryingAmount, "preTransitionCarryingAmount");
        Objects.requireNonNull(fairValue, "fairValue");
        Objects.requireNonNull(technique, "technique");
        measuredBy = FourEyes.requireIdentity(measuredBy, "measuredBy",
            "an unattributed valuation is not evidence of one");
        paragraph19EvidenceRef = blankToNull(paragraph19EvidenceRef);
        reviewedBy = blankToNull(reviewedBy);

        if (technique.requiresDiscountRate() && discountRateUsed == null) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued by " + technique + " with no discount rate;"
                    + " a discounted cash flow without a rate has not been performed, so there is"
                    + " nothing to report about it");
        }
        if (!technique.requiresDiscountRate() && discountRateUsed != null) {
            // Refused rather than ignored. A rate on a QUOTED_PRICE row is either the wrong
            // technique recorded or a rate from a working that was abandoned, and both are things
            // a reader would take as the basis of the number.
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued by " + technique + " and carries a discount"
                    + " rate of " + discountRateUsed.periodic().toPlainString()
                    + "; either the technique is mis-recorded or the rate belongs to a working"
                    + " that was not used");
        }
        if (reviewedBy != null && FourEyes.isSelfApproval(measuredBy, reviewedBy)) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued and reviewed by '" + measuredBy
                    + "'; a valuation reviewed by the person who performed it carries the same"
                    + " single judgement it started with");
        }
        if (paragraph19EvidenceRef != null && !technique.appliesParagraph19Presumption()) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued by " + technique + " and names paragraph 19"
                    + " rebuttal evidence '" + paragraph19EvidenceRef + "'; the presumption was not"
                    + " applied, so the reference documents a decision that was not taken");
        }
    }

    /**
     * The ACPIR 19 adjustment: fair value less the pre-transition carrying amount.
     *
     * <p><b>To opening retained earnings.</b> Not to profit or loss, and not to a period result —
     * ACPIR 19 is a transition adjustment against the opening balance, and there is deliberately
     * no accessor on this type that would let it be read as anything else. Negative where the
     * fair value is below what the book carried, which for a legacy portfolio measured at a
     * current market rate is the ordinary direction.
     */
    public Money differenceToOpeningRetainedEarnings() {
        return fairValue.minus(preTransitionCarryingAmount);
    }

    /** Whether this valuation rests on the paragraph 19 presumption. Derived, never stored. */
    public boolean appliesParagraph19Presumption() {
        return technique.appliesParagraph19Presumption();
    }

    /** Whether the presumption was applied with no evidence file named — the TF-1 condition. */
    public boolean presumptionIsUnevidenced() {
        return appliesParagraph19Presumption() && paragraph19EvidenceRef == null;
    }

    /**
     * Invariant TF-1 for this contract.
     *
     * <p>Per contract as well as per run, because the two readings are used at different times: a
     * programme team works the FY27 evidence file contract by contract, and a close needs the
     * portfolio count. {@link TransitionValuationRun#paragraph19Evidenced()} is the second.
     */
    public InvariantResult paragraph19Evidenced() {
        if (!appliesParagraph19Presumption()) {
            return InvariantResult.pass(InvariantId.TF_1,
                "contract " + contractId + " valued by " + technique
                    + ", so the presumption is not in play");
        }
        if (paragraph19EvidenceRef != null) {
            return InvariantResult.pass(InvariantId.TF_1,
                "contract " + contractId + " applies the paragraph 19 presumption on evidence "
                    + paragraph19EvidenceRef);
        }
        return InvariantResult.fail(InvariantId.TF_1,
            "contract " + contractId + " takes carrying cost as the best evidence of fair value"
                + " with no rebuttal evidence named; the difference to opening retained earnings"
                + " is nil because nothing was measured, which is indistinguishable from a"
                + " valuation that was performed and found no difference",
            BigDecimal.ONE);
    }

    /** A one-line audit sentence naming the valuation, its basis and its difference. */
    public String describe() {
        return "contract " + contractId + " at " + transitionDate + ": carrying "
            + preTransitionCarryingAmount.atPresentationScale() + " -> fair value "
            + fairValue.atPresentationScale() + " by " + technique
            + (discountRateUsed == null ? ""
                : " at " + discountRateUsed.periodic().toPlainString())
            + ", to opening retained earnings "
            + differenceToOpeningRetainedEarnings().atPresentationScale()
            + ", measured by " + measuredBy
            + (reviewedBy == null ? " (unreviewed)" : ", reviewed by " + reviewedBy)
            + (appliesParagraph19Presumption()
                ? paragraph19EvidenceRef == null
                    ? " [PARAGRAPH 19 PRESUMPTION, NO EVIDENCE]"
                    : " [paragraph 19 presumption, evidence " + paragraph19EvidenceRef + "]"
                : "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
