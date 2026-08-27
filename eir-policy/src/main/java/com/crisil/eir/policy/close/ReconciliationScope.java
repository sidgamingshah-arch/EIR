package com.crisil.eir.policy.close;

import com.crisil.eir.domain.InvariantId;

/**
 * The four reconciliations step 6 of 02 § 3.1 names: "confirm reconciliations: sub-ledger to GL,
 * contractual leg to CBS, Stage 3 four-way, pre- and post-floor."
 *
 * <p><b>An enumeration and not a free-form list, because FR-901 says "all".</b> "All
 * reconciliations tied", read over whichever reconciliations a caller happened to hand in, passes
 * a close in which the Stage 3 four-way was never run — and that is the reconciliation with the
 * most moving parts and the one whose absence is least visible in a figure. Naming the four makes
 * an omission a refusal ({@link CloseGateRefusal#RECONCILIATION_NOT_PRESENTED}) instead of a
 * silence.
 *
 * <p><b>Each names the invariant that asserts it, and this package asserts none of them.</b> The
 * sub-ledger-to-GL tie is SL-1's claim, the CBS tie is RC-1's, the Stage 3 four-way is S3-1's and
 * the floor duality is PF-1's; all four are computed by the engines that hold the figures, and each
 * publishes its own {@link com.crisil.eir.domain.InvariantResult} with its own deviation. What is
 * recorded here is the correspondence, so that an operator handed
 * {@code RECONCILIATION_NOT_TIED} on {@code SUBLEDGER_TO_GL} can go straight to the invariant
 * whose deviation quantifies it. Re-deriving any of them in the close would publish a second
 * answer under an identifier entitled to one — the defect
 * {@code InvariantResult.conjunction}'s javadoc records finding three times.
 */
public enum ReconciliationScope {

    /**
     * Sub-ledger to general ledger. Invariant SL-1.
     *
     * <p>The tie that makes the sub-ledger's contract-level detail and the GL's posted balances
     * the same book. A break here is the residue of per-contract rounding surviving to a
     * portfolio total, which no single contract explains.
     */
    SUBLEDGER_TO_GL(InvariantId.SL_1, "sub-ledger to GL"),

    /**
     * The contractual leg against core banking. Invariant RC-1.
     *
     * <p>The two-leg model runs an EIR leg and a contractual leg (03 § 5); the contractual leg is
     * arithmetic the CBS also performs, so it is the one figure in this engine that has an
     * independent external check. A break means the engine and the bank's system of record
     * disagree about what the borrower owes.
     */
    CONTRACTUAL_LEG_TO_CBS(InvariantId.RC_1, "contractual leg to core banking"),

    /**
     * The Stage 3 four-way. Invariant S3-1.
     *
     * <p>Gross carrying amount, ECL allowance, net carrying amount and the suspense ledger, tied
     * as one identity (FR-605). Four figures produced by three subsystems; the reason it is a
     * four-way rather than three pairs is that any pair can be made to tie by moving the fourth.
     */
    STAGE_THREE_FOUR_WAY(InvariantId.S3_1, "Stage 3 four-way"),

    /**
     * Pre- and post-floor. Invariant PF-1.
     *
     * <p>FR-609: the pre-floor ECL is retained alongside the floored figure, and the floor never
     * overwrites it. The failure mode is silent — a floored figure is a valid-looking number — so
     * the reconciliation is the only place the duality is visible.
     */
    PRE_AND_POST_FLOOR(InvariantId.PF_1, "pre- and post-floor");

    private final InvariantId assertedBy;
    private final String label;

    ReconciliationScope(InvariantId assertedBy, String label) {
        this.assertedBy = assertedBy;
        this.label = label;
    }

    /**
     * The invariant that asserts this tie, computed elsewhere.
     *
     * <p>A cross-reference, not a delegation. Nothing in this package computes or publishes the
     * named result; the close reads the residual it was handed and points whoever has to fix it at
     * the control that quantifies it.
     */
    public InvariantId assertedBy() {
        return assertedBy;
    }

    /** Step 6's own wording for this reconciliation. */
    public String label() {
        return label;
    }

    /**
     * Whether a close must present this reconciliation.
     *
     * <p>All four, today. Kept as a predicate rather than assumed over the whole enum so that a
     * later scope which is genuinely optional — a disclosure tie that only applies at year end,
     * say — can be added without silently loosening the four that are not.
     */
    public boolean requiredForClose() {
        return true;
    }
}
