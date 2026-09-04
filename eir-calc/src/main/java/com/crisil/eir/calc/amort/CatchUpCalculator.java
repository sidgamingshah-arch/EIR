package com.crisil.eir.calc.amort;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The B5.4.6 catch-up restatement (calculation specification section 6.3):
 *
 * <pre>
 *   GCA_restated = sum( revised_flow_t / (1 + r_original)^tau_t )
 *   catch_up     = GCA_restated - GCA_before
 * </pre>
 *
 * <p>ACPIR is silent on subsequent changes in estimated cash flows, so the engine
 * adopts the IFRS 9 mechanics by election under the interpretive hierarchy. Which
 * events arrive here is a versioned mapping from the driver tag on the event
 * (ADR-0006), never an inference from the observation that the rate or the
 * schedule moved: reference case 3 and reference case 4 are the same instrument in
 * the same month, both with a changed schedule, and they differ by 627.42 of P&amp;L.
 *
 * <p><strong>The rate does not move.</strong> Discounting is at the
 * <em>original</em> EIR. Discounting at a re-solved rate is the defect this class
 * exists to prevent: a rate re-solved over the revised flows from the current
 * carrying amount reproduces that carrying amount almost exactly, so the catch-up
 * collapses to approximately zero and a B5.4.6 event has silently become a
 * B5.4.5 one — no P&amp;L, no trace, and a rate that no longer reconciles to the
 * original measurement. Invariant CU-1 asserts the persisted EIR before and after
 * is bit-identical, and it is the cheapest possible detector of exactly that.
 * {@link #restate(Rate, Money, FlowVector, TimeConvention)} cannot commit the
 * defect, because it is given only one rate to discount at; the four-argument
 * overload exists for a pipeline that persists a post-event rate of its own, and
 * checks it.
 *
 * <p>Discounting routes through {@link Discounting}, the same code the solver
 * used. A catch-up computed with even slightly different discounting from the
 * solve that produced the original rate reintroduces the same defect through
 * arithmetic rather than through routing.
 *
 * <p><strong>A restructuring fee settled on the event date does not belong in the
 * vector.</strong> Only flows strictly after the anchor are discounted here, so a
 * fee dated on the modification date would be dropped from the restatement without
 * trace. IFRS 9 5.4.3 requires such a cost to adjust the carrying amount, so it
 * belongs in {@code gcaBefore} — the caller nets it there, and the catch-up then
 * carries it correctly.
 *
 * <p>Note the deliberate asymmetry with the 10% test, which is measured over the
 * same revised vector on the same date and <em>does</em> include anchor-dated
 * flows, because B3.3.6 requires the test to be net of fees paid and received
 * (ModificationTest.presentValueAtModificationDate, in the routing package). Both
 * treatments are right for their own purpose and they are easy to conflate: one
 * vector handed to both routines gives a ratio that counts the fee and a
 * restatement that does not. The fee reaches the restatement through the carrying
 * amount, not through the flows.
 */
public final class CatchUpCalculator {

    private CatchUpCalculator() {
    }

    /**
     * Restates the carrying amount to the present value of the revised flows at
     * the original EIR.
     *
     * <p>The vector's anchor is the event date, and only flows strictly after it
     * are discounted. Cash received <em>on</em> the event date has already been
     * applied by then — step 2 of the intra-period ordering precedes step 4
     * ({@link EventOrdering}) — so discounting it into the restated balance would
     * count it twice.
     *
     * @param originalEir  the EIR in force before the event, retained
     * @param gcaBefore    carrying amount after the day's cash and before restatement
     * @param revisedFlows the revised expected flows, anchored at the event date
     * @param convention   the same time convention the original solve used
     */
    public static CatchUpResult restate(
        Rate originalEir, Money gcaBefore, FlowVector revisedFlows, TimeConvention convention) {
        return restate(originalEir, originalEir, gcaBefore, revisedFlows, convention);
    }

    /**
     * As {@link #restate(Rate, Money, FlowVector, TimeConvention)}, additionally
     * checking the rate the pipeline persisted after the event against the rate the
     * restatement used.
     *
     * @param persistedEirAfterEvent whatever the pipeline stored as the contract's
     *     EIR after the event; a difference is a CU-1 breach, not a rounding matter
     */
    public static CatchUpResult restate(
        Rate originalEir,
        Rate persistedEirAfterEvent,
        Money gcaBefore,
        FlowVector revisedFlows,
        TimeConvention convention) {
        Objects.requireNonNull(originalEir, "originalEir");
        Objects.requireNonNull(persistedEirAfterEvent, "persistedEirAfterEvent");
        Objects.requireNonNull(gcaBefore, "gcaBefore");
        Objects.requireNonNull(revisedFlows, "revisedFlows");
        Objects.requireNonNull(convention, "convention");
        if (!gcaBefore.currency().equals(revisedFlows.currency())) {
            throw new IllegalArgumentException(
                "carrying amount is " + gcaBefore.currency().getCurrencyCode()
                    + " but the revised flows are " + revisedFlows.currency().getCurrencyCode());
        }
        if (originalEir.periodsPerYear() != convention.periodsPerYear()) {
            throw new IllegalArgumentException(
                "original EIR compounds " + originalEir.periodsPerYear() + " times a year but convention "
                    + convention.label() + " implies " + convention.periodsPerYear());
        }

        Money restated =
            Discounting.presentValueMoney(originalEir.periodic(), revisedFlows, convention);
        Money catchUp = restated.minus(gcaBefore);

        List<InvariantResult> invariants = new ArrayList<>();
        invariants.add(InvariantChecks.eirUnchangedAcrossCatchUp(originalEir, persistedEirAfterEvent));
        // CU-2. Kept, and worth knowing what it is and is not.
        //
        // Both sides of this comparison are the SAME SUBTRACTION -- catchUp is defined three lines
        // above as restated.minus(gcaBefore) -- one rounded to presentation scale and one not. So
        // the only discrepancy CU-2 can ever report is a sub-paisa rounding residue between the
        // working scale and the published one. That is a real thing to check and it is emphatically
        // NOT a check on the restatement: a review demonstrated the point by replacing this whole
        // present-value calculation with `Money restated = gcaBefore;` -- deleting the arithmetic
        // outright -- and CU-1 and CU-2 both stayed green while a 91% write-down went through. No
        // test can make CU-2 fail on a wrong restatement, because its two inputs are one expression.
        //
        // The identifier's own statement, "catch-up = PV(revised, original EIR) - GCA before", is
        // the DEFINITION of the quantity, which is precisely why it read as coverage for so long.
        // TR-1 below is the control it was mistaken for.
        invariants.add(InvariantResult.ofMoney(
            InvariantId.CU_2,
            "catch-up ties the published balances at presentation scale: restated "
                + restated.atPresentationScale() + " less carrying amount before "
                + gcaBefore.atPresentationScale(),
            restated.atPresentationScale().minus(gcaBefore.atPresentationScale()),
            catchUp));
        invariants.addAll(terminalCheck(originalEir, restated, revisedFlows, convention));
        return new CatchUpResult(
            originalEir, persistedEirAfterEvent, gcaBefore, restated, catchUp, List.copyOf(invariants));
    }

    /**
     * TR-1 over the restatement: a <em>second, independent</em> derivation of the restated balance.
     *
     * <h2>Why this is not the tautology CU-2 is</h2>
     *
     * <p>{@link Discounting#presentValueMoney} computes a sum of discounted flows,
     * {@code sum(CF_t / (1+r)^t)}. {@link AmortisationEngine#eirLeg} computes an iterative
     * roll-forward, {@code B_k = B_(k-1) * (1+r)^dtau - CF_k}, and asserts the terminal balance is
     * nil. The two agree only if they were given the same rate, the same vector and the same
     * convention, and only if both arithmetics are right — so a wrong exponent, an inverted sign, a
     * dropped flow, or the restatement and the roll being handed different vectors all show up here
     * as a non-nil terminal balance. That is the same "two independent derivations of one quantity"
     * pattern that makes ST-2 a control rather than a tautology.
     *
     * <p><b>It is asserted here rather than left to the caller, and that is the whole point.</b>
     * {@link #rollForwardRestated} has existed since this class was written and its javadoc has
     * always claimed "TR-1 is asserted" — and nothing in {@code eir-application} ever called it, so
     * the claim was true of the method and false of the engine. A control a caller must remember to
     * invoke is a control a caller forgets; folding it into {@code restate} means every restatement
     * in the system carries it, including the ones nobody has written yet.
     *
     * <p><b>The cost, stated.</b> This rolls the whole revised vector forward on every modification
     * event — O(remaining periods) of fractional powers per event. On a ten-million-contract close
     * with events on four percent of the book that is roughly four hundred thousand extra rolls.
     * Paid deliberately: the alternative is an unbounded restatement nothing checks, which is what
     * the engine had.
     *
     * <p>Returns empty rather than throwing where the revised vector carries no future flow. An
     * event whose revised schedule is empty is a data condition for the caller to quarantine, and a
     * terminal-balance assertion over no flows would either divide by nothing or pass vacuously —
     * the second being worse, since a vacuous pass under TR-1's identifier is the exact failure this
     * method exists to end.
     */
    private static List<InvariantResult> terminalCheck(
        Rate originalEir, Money restated, FlowVector revisedFlows, TimeConvention convention) {

        if (revisedFlows.future().isEmpty()) {
            return List.of();
        }
        return AmortisationEngine.eirLeg(restated, originalEir, revisedFlows, convention)
            .invariants();
    }

    /**
     * Rolls the restated balance forward over the revised flows at the retained
     * EIR — the post-modification schedule of reference case 3.
     *
     * <p>TR-1 is asserted, and {@link #restate} now asserts it too — see
     * {@link #terminalCheck}. This method remains for a caller that wants the post-modification
     * <em>rows</em> and not merely the invariant: reference case 3's schedule is read off them.
     * The restatement's own TR-1 no longer depends on anybody calling this.
     */
    public static AmortisationResult rollForwardRestated(
        CatchUpResult restatement, FlowVector revisedFlows, TimeConvention convention) {
        Objects.requireNonNull(restatement, "restatement");
        return AmortisationEngine.eirLeg(
            restatement.restatedGca(), restatement.eirBefore(), revisedFlows, convention);
    }
}
