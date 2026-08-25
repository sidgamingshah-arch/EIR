package com.crisil.eir.policy.fee;

/**
 * The contingent charges FR-206 names, each one a fee whose <em>amount</em> is
 * unknowable at inception because it depends on an event that may never happen.
 *
 * <p>This vocabulary exists so that the second half of FR-206 is representable at
 * all. The requirement has two limbs — "exclude from the inception projection"
 * <em>and</em> "recognise them in the period the event occurs" — and an
 * implementation that carries only a boolean can express the first limb and not
 * the second. A fee excluded with no record of what it is waiting for is income
 * the engine has silently dropped, which is the defect
 * {@link FeeTreatmentRecognition} refuses to represent.
 *
 * <p>The three members are the three the requirement lists. They are not a
 * classification — every one of them resolves to
 * {@link com.crisil.eir.domain.FeeClassification#AS_INCURRED} — they are the
 * <em>trigger</em>, retained because "recognised in the period the event occurs"
 * is only auditable if the period can be traced back to a named event.
 *
 * <p>Deliberately not here: commitment-fee expiry. A commitment fee's recognition
 * turns on the drawdown assessment of FR-204, not on the FR-206 contingency test,
 * and folding it in would put one decision in two rules.
 * {@link FeeTreatmentRules} refuses a commitment fee by name rather than guessing.
 */
public enum FeeTreatmentContingentEvent {

    /**
     * Prepayment penalty. The amount is a percentage of the balance outstanding
     * <em>at the date of prepayment</em>, so at inception neither the amount nor
     * the date exists. Projecting it would require assuming the very prepayment
     * behaviour the expected-life estimate is separately modelling (FR-306), and
     * would then count that behaviour twice: once as an early principal flow, once
     * as a fee.
     */
    PREPAYMENT_PENALTY(
        "amount is a percentage of the balance outstanding on a date that may never arrive"),

    /**
     * Late payment fee. Contingent on the borrower missing a due date. A projection
     * that included it would recognise delinquency income at inception on a book
     * assumed to perform, and the ACPIR 46 expected-credit-loss estimate is where
     * non-performance belongs.
     */
    LATE_FEE(
        "contingent on a payment being missed, which the performing-book projection does not assume"),

    /**
     * Cheque or mandate bounce charge. Contingent on an instrument being
     * dishonoured. Note this is <em>not</em> a penal charge under RBI's 2023
     * framework merely because it is punitive in character: where the fee master
     * classifies it {@code EXCLUDED_BY_DIRECTION} it never reaches this rule at
     * all, because {@link com.crisil.eir.calc.projection.FeePosting} refuses to
     * carry such a posting (FR-207, invariant PC-1). The two exclusions are
     * different exclusions and both apply.
     */
    BOUNCE_CHARGE(
        "contingent on an instrument being dishonoured; a per-occurrence charge with no expected count");

    private final String whyNotProjectable;

    FeeTreatmentContingentEvent(String whyNotProjectable) {
        this.whyNotProjectable = whyNotProjectable;
    }

    /**
     * Why the inception projection cannot carry this fee, in the words that go into
     * the computation trace.
     *
     * <p>Retained per member rather than stated once for the enum, because the three
     * reasons are genuinely different and a trace line that says only "contingent"
     * tells a reviewer nothing they did not already know.
     */
    public String whyNotProjectable() {
        return whyNotProjectable;
    }
}
