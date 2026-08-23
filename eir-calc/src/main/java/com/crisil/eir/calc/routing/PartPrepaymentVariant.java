package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Mechanism;

/**
 * Which of the two part-prepayment shapes an event is (calculation specification
 * section 6.5).
 *
 * <p>Both variants reduce the balance by the cash received. They diverge on what
 * happens to the remaining schedule, and therefore on whether anything hits P&amp;L
 * now:
 *
 * <ul>
 *   <li>{@link #TENOR_REDUCED} — fewer future instalments, each unchanged. The
 *       original EIR still discounts the remaining flows to the post-payment
 *       carrying amount closely enough that no restatement is warranted:
 *       re-project and continue.
 *   <li>{@link #EMI_REDUCED} — same number of instalments, each smaller. The flow
 *       <em>pattern</em> changed, which is a revision of estimated receipts and so
 *       a B5.4.6 catch-up at the original EIR.
 * </ul>
 *
 * <p><strong>Which one applies is carried on the event</strong> — a contractual
 * term or a borrower election at the counter — and is never inferred from the
 * resulting schedule (FR-509). There is deliberately no factory here that reads a
 * pair of schedules and decides. Inference fails in both directions and quietly:
 * an EMI-reduced prepayment on a loan whose instalment was already stepping down
 * looks like a tenor reduction, and a tenor-reduced prepayment that lands
 * mid-period leaves a smaller final instalment that looks like an EMI reduction.
 * Guessing wrong is the difference between a catch-up posted and a catch-up
 * missed, on an event that occurs thousands of times a month in a retail mortgage
 * book — which is exactly why the election has to come from the source system
 * rather than from the engine's reading of the outcome.
 */
public enum PartPrepaymentVariant {

    /** Tenor shortened, instalment held. Re-project and continue; no catch-up. */
    TENOR_REDUCED,

    /** Instalment reduced, tenor held. Restate at the original EIR with a catch-up. */
    EMI_REDUCED;

    /**
     * The mechanism this variant routes to.
     *
     * <p>{@link Mechanism#NONE} for {@link #TENOR_REDUCED} means no rate consequence
     * and no restatement — not "nothing happens". The carrying amount still falls by
     * the cash received and the remaining schedule is still re-projected; there is
     * simply no catch-up and no re-solve.
     */
    public Mechanism mechanism() {
        return this == EMI_REDUCED ? Mechanism.CATCH_UP : Mechanism.NONE;
    }

    /** Whether the remaining schedule must be re-projected before the next accrual. */
    public boolean requiresReprojection() {
        return this == TENOR_REDUCED;
    }

    /** Whether the carrying amount is restated at the original EIR with a P&amp;L catch-up. */
    public boolean requiresCatchUp() {
        return this == EMI_REDUCED;
    }
}
