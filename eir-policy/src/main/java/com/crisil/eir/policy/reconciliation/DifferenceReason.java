package com.crisil.eir.policy.reconciliation;

/**
 * The stated causes a contractual-leg-to-CBS difference may be attributed to (FR-804, control
 * C-14, invariant RC-1).
 *
 * <p><b>A closed list, and deliberately no {@code OTHER}.</b> RC-1 measures the difference that is
 * <em>not</em> explained, so the category set is the boundary of what "explained" means. A
 * catch-all reason would move that boundary to wherever the operator's imagination reaches: any
 * difference at all becomes explicable by selecting {@code OTHER} and typing a sentence, and the
 * control degrades into a record of how many sentences were typed. The four causes below are the
 * ones this engine's design actually produces, each traceable to a specific document, and a
 * difference arising from none of them is an unexplained difference — which is the finding, not a
 * gap in the enumeration.
 *
 * <p>Adding a fifth member is therefore a policy act with an argument behind it, in the way that
 * adding a tenth {@link com.crisil.eir.policy.exception.ExceptionCategory} would be. That is the
 * intended cost.
 */
public enum DifferenceReason {

    /**
     * The CBS billed on a different day from the engine's accrual boundary, so the same interest
     * falls in adjacent periods on the two sides.
     *
     * <p>The benign majority. 03 § 3 warns that an undocumented mixed day-count convention
     * produces "a permanent unexplained reconciliation break", and this is the documented version
     * of the same arithmetic: a 30/360 monthly instalment billed on the 5th against an accrual run
     * to month-end differs by a few days of interest, reverses next period, and is a genuine
     * timing difference rather than a measurement disagreement.
     *
     * <p>Self-reversing over two periods, which is what makes it checkable: the same contract
     * carrying the same timing explanation with the same sign for six periods running is not a
     * timing difference, and the reversal is what a reviewer should be asked for.
     */
    BILLING_DAY_TIMING,

    /**
     * The CBS books an amount as interest that the engine has classified as an integral fee under
     * ACPIR 52/53, so the amount is inside the CBS interest figure and outside the engine's
     * contractual leg.
     *
     * <p>Not a defect on either side — it is the two systems answering two different questions,
     * which is precisely why ADR-0004 makes the CBS the book of record for what the borrower was
     * billed and the engine the book of record for how it is recognised. The engine's contractual
     * leg carries interest at the contractual rate; a fee the CBS chose to present in the same
     * column belongs to the fee stream and reaches income through amortisation instead.
     *
     * <p>The amount is therefore reconcilable against the fee postings for the contract, and that
     * is the evidence a checker should be looking at. An explanation of this kind whose amount
     * does not appear in {@code fee_posting} for the period is an assertion, not an explanation.
     */
    INTEGRAL_FEE_BILLED_AS_INTEREST,

    /**
     * The two systems round to paise at different points in the same calculation.
     *
     * <p>Bounded and small — the engine rounds once, on the way out (03 § 1.2), while a CBS that
     * rounds each instalment's interest component leaves a few paise per period. 03 § 5.7 is
     * explicit that residue "is never resolved by tolerance": the difference is accounted for by a
     * stated rule, which is what this reason is, rather than absorbed by a threshold nobody
     * revisits.
     *
     * <p>Distinguishable from the others by size. A rounding-convention explanation carrying
     * thousands of rupees is mislabelled, and nothing here checks a reason against the magnitude it claims, and RC-1 does not either — it asks only whether the claim closes the difference, so a 481,463.27 "rounding" claim ties perfectly. Judging a reason against its size would need a threshold this engine has no authority to invent; what the control can do, and now does, is report one-sided and ineffectively-explained contracts on a PASS so the shape is visible even when the money agrees
     * when the difference it claims to explain is larger than rounding can produce.
     */
    ROUNDING_CONVENTION,

    /**
     * A penal charge the CBS bills inside its interest figure, which the RBI direction of 2023
     * excludes from the EIR entirely (invariant PC-1, {@code EXCLUDED_BY_DIRECTION}).
     *
     * <p>Its own reason rather than a variety of the fee case, because the treatment is opposite in
     * kind. An integral fee is deferred and recognised through the EIR; a penal charge is excluded
     * from every EIR stream and from the gross carrying amount, and PC-1 asserts that exclusion
     * positively. So the amount here is one that must <em>never</em> reappear in the engine's
     * interest legs, and an explanation citing this reason is a claim that PC-1 is doing its job —
     * checkable against PC-1's own result for the same period.
     */
    PENAL_CHARGE_EXCLUDED_BY_DIRECTION;

    /**
     * A short phrase for the audit sentence a reconciliation break carries.
     *
     * <p>Spelled out rather than derived from {@link #name()}: the detail on an
     * {@link com.crisil.eir.domain.InvariantResult} is read by whoever is clearing the break, and
     * {@code INTEGRAL_FEE_BILLED_AS_INTEREST} is a constant name rather than a sentence.
     */
    public String statement() {
        return switch (this) {
            case BILLING_DAY_TIMING ->
                "timing: the CBS bills on a different day from the accrual boundary";
            case INTEGRAL_FEE_BILLED_AS_INTEREST ->
                "classification: the CBS books as interest a fee the engine holds as integral";
            case ROUNDING_CONVENTION ->
                "rounding: the two systems reduce to paise at different points";
            case PENAL_CHARGE_EXCLUDED_BY_DIRECTION ->
                "penal charge excluded from the EIR by RBI direction (PC-1)";
        };
    }

    /**
     * Whether a difference of this kind is expected to reverse in a later period.
     *
     * <p>Only timing does. Published because it is the one property of a reason that a reviewer can
     * act on without opening the contract: a non-reversing reason recurring every period is normal
     * (an integral fee is billed monthly), whereas {@link #BILLING_DAY_TIMING} recurring with the
     * same sign indefinitely means the accrual boundary itself is wrong and the "timing" label is
     * hiding it.
     */
    public boolean expectedToReverse() {
        return this == BILLING_DAY_TIMING;
    }
}
