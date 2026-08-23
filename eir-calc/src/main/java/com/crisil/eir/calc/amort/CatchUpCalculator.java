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
        invariants.add(InvariantResult.ofMoney(
            InvariantId.CU_2,
            "catch-up ties the published balances: restated " + restated.atPresentationScale()
                + " less carrying amount before " + gcaBefore.atPresentationScale(),
            restated.atPresentationScale().minus(gcaBefore.atPresentationScale()),
            catchUp));
        return new CatchUpResult(
            originalEir, persistedEirAfterEvent, gcaBefore, restated, catchUp, List.copyOf(invariants));
    }

    /**
     * Rolls the restated balance forward over the revised flows at the retained
     * EIR — the post-modification schedule of reference case 3.
     *
     * <p>TR-1 is asserted: the restated balance <em>is</em> the present value of
     * these flows at this rate, so it amortises to zero over them by construction,
     * and a residue would mean the restatement and the roll-forward were given
     * different vectors.
     */
    public static AmortisationResult rollForwardRestated(
        CatchUpResult restatement, FlowVector revisedFlows, TimeConvention convention) {
        Objects.requireNonNull(restatement, "restatement");
        return AmortisationEngine.eirLeg(
            restatement.restatedGca(), restatement.eirBefore(), revisedFlows, convention);
    }
}
