package com.crisil.eir.policy.fee;

/**
 * What ACPIR 53 says about a cost function: capitalise it, exclude it, or —
 * <b>the case the existing vocabulary forces on us</b> — it does not say, because
 * the function as recorded straddles the paragraph's dividing line.
 *
 * <p><b>Three values, not two, and the third one is the finding.</b> A boolean
 * {@code capitalisable} flag would be the tidier type and it would be wrong. ACPIR
 * 53 includes "fees and commission paid to agents (including employees acting as
 * selling agents), advisers, brokers and dealers" and excludes "internal
 * administrative or holding costs". The line is drawn at <em>selling</em>, not at
 * <em>processing</em> (03 § 3.2; 01 § "ACPIR 53 draws the line at selling, not
 * processing"). Of the four cost functions the ingestion layer can actually populate
 * — {@code SELLING}, {@code PROCESSING}, {@code ADMIN}, {@code OTHER} (04 § 2.5) —
 * only two land on a side of that line. {@code PROCESSING} covers both the
 * credit-appraisal salary ACPIR 53 excludes by name and the external valuation,
 * legal or bureau charge paid to a third party in the course of origination, which
 * is a directly attributable transaction cost that capitalises. {@code OTHER} is a
 * residual bucket and says nothing at all.
 *
 * <p>Collapsing that to a boolean would put a made-up answer into the type system,
 * where every downstream call site would then read it as a decided one. Under an
 * {@code INDETERMINATE} value the posting has to stop and be refined — the same
 * discipline {@code ExceptionCategory.UNMAPPED_FEE_CODE} applies to an unmapped fee
 * code, and for the reason stated there: "both defaults are wrong in the direction
 * nobody checks".
 *
 * <p>The defect this three-valued type exists to catch: a {@code PROCESSING} cost
 * silently capitalised because a boolean said {@code true}, moving internal
 * credit-appraisal salary into the gross carrying amount and out of the period's
 * expense. On reference case 1 the entire net integral fee is 5,000.00 on a
 * 1,000,000.00 advance and it moves the EIR 56.6 basis points; a mis-swept appraisal
 * cost centre is the same order of magnitude as the effect being measured.
 */
public enum CostFunctionCapitalisability {

    /**
     * A transaction cost inside ACPIR 53's positive limb: into the initial carrying
     * amount, amortised through the EIR. The paradigm case is the DSA commission of
     * reference case 1 — 10,000.00 paid, capitalised — and, by the words RBI chose,
     * the sourcing incentive paid to a branch employee acting as a selling agent.
     */
    CAPITALISE,

    /**
     * Excluded by ACPIR 53's negative limb — internal administrative or holding cost,
     * financing cost, debt premium or discount. Expensed as incurred; it never touches
     * the carrying amount or the rate.
     */
    EXCLUDE,

    /**
     * ACPIR 53 gives no answer for this function <em>as recorded</em>, because the
     * recorded function spans both limbs. Not a shrug and not a default: a posting
     * carrying an indeterminate function cannot be capitalised, and cannot be quietly
     * expensed either, because either choice is an accounting conclusion nobody
     * reached. It has to be refined at source — the months-long HR and cost-centre
     * re-attribution exercise of 08 § 0 — and until it is, the posting belongs in the
     * exception queue.
     */
    INDETERMINATE;

    /** Whether ACPIR 53 yields an answer for this function without further refinement. */
    public boolean isDecided() {
        return this != INDETERMINATE;
    }
}
