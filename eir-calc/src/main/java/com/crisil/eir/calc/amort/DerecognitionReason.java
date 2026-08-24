package com.crisil.eir.calc.amort;

/**
 * Why an asset came off the books, from the event-treatment table of calculation
 * specification 6.2.
 *
 * <p>Carried on the result because the three cases post differently, and the signs
 * alone do not distinguish them: a closure gain and a modification gain are both
 * positive and belong in different places.
 */
public enum DerecognitionReason {

    /**
     * Full prepayment or scheduled closure. The borrower discharges the contractual
     * balance against a carrying amount that still holds the unamortised fee, and
     * the difference is that fee arriving early — reference case 2's 1,408.29.
     */
    CLOSURE,

    /**
     * A modification substantial enough to fail the 3.2.3 test. The old asset is
     * derecognised and a new one recognised at fair value; the consideration is that
     * fair value rather than cash.
     */
    SUBSTANTIAL_MODIFICATION,

    /**
     * Write-off. The carrying amount goes to zero and the loss is measured against
     * the allowance the impairment engine holds, not against this engine's income
     * (ACPIR 6(12)). Recorded here so the sub-ledger balance is removed on the same
     * mechanic as every other exit, and flagged so the P&amp;L figure is not posted
     * twice.
     */
    WRITE_OFF;

    /**
     * Whether the resulting figure is this engine's to recognise.
     *
     * <p>False for {@link #WRITE_OFF} — see its note. The distinction is a boundary,
     * not a nicety: the engine consumes staging and allowances as versioned inputs
     * and does not hold the allowance a write-off consumes.
     */
    public boolean recognisedByThisEngine() {
        return this != WRITE_OFF;
    }
}
