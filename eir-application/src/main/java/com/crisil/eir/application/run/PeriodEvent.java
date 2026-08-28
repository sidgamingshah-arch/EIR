package com.crisil.eir.application.run;

import com.crisil.eir.calc.routing.ModificationConclusion;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.RateDriver;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A cash-flow-change event a contract carries into one period (05 § 3.2, "alt event present").
 *
 * <p><b>The driver tag is mandatory and is not inferred.</b> FR-504 makes it a required field on
 * the event for the reason 03 § 6.1 gives: routing keys off <em>why</em> the flows changed, never
 * off the observation that they did. A benchmark reset and a renegotiated fixed-rate loan look
 * identical from the rate movement and are a B5.4.5 reset and a modification respectively —
 * reference cases 3 and 4 are the same instrument in the same month, and the two readings differ by
 * a 627.42 charge. So the field is non-null here and there is deliberately no default.
 *
 * <p><b>The revised vector is mandatory too, and for both mechanisms.</b> A reset re-solves over
 * the revised remaining flows from the current carrying amount; a catch-up discounts the same
 * revised flows at the retained rate. An event with no revised flows is an event with nothing to
 * route — it changed no cash flow — and the honest place to refuse that is here, at assembly,
 * rather than eight frames into a projector.
 *
 * <p><b>{@code conclusion} is null except where the routing lands on a modification test.</b> The
 * substantiality assessment is a judgement with three outcomes, not two
 * ({@link ModificationConclusion}), and the engine is not entitled to reach it. Where the table
 * routes an event to {@code MODIFICATION_TEST} and no conclusion has been recorded,
 * {@link ContractPipeline} refuses the contract rather than picking a mechanism — see its javadoc
 * for why quarantining beats defaulting.
 *
 * @param driver       why the projected flows changed; FR-504's mandatory tag
 * @param eventDate    the date the event occurred, which selects the routing table version in
 *                     force (never today's, which is what makes a closed period replayable)
 * @param revisedFlows the revised remaining flows, anchored at the event date
 * @param conclusion   the recorded substantiality conclusion, or null where none was needed or
 *                     none has been reached
 */
public record PeriodEvent(
    RateDriver driver,
    LocalDate eventDate,
    FlowVector revisedFlows,
    ModificationConclusion conclusion) {

    public PeriodEvent {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(revisedFlows, "revisedFlows");
        if (revisedFlows.future().isEmpty()) {
            throw new IllegalArgumentException(
                "event on " + eventDate + " tagged " + driver + " carries no flows after its own"
                    + " anchor; an event that changes no future cash flow has nothing for the"
                    + " routing table to route, and a reset solved against an empty vector would"
                    + " report a rate for a contract with no remaining flows");
        }
        if (!revisedFlows.anchorDate().equals(eventDate)) {
            // The anchor is what both mechanisms measure from: CatchUpCalculator discounts only
            // flows strictly after it, because cash received ON the event date has already been
            // applied by step 2 of the intra-period ordering and discounting it into the restated
            // balance would count it twice. An anchor that is not the event date silently moves
            // that boundary.
            throw new IllegalArgumentException(
                "event dated " + eventDate + " carries a revised vector anchored "
                    + revisedFlows.anchorDate() + "; the anchor is the date both the re-solve and"
                    + " the restatement measure from, so a mismatch shifts every exponent by the"
                    + " gap and neither TR-1 nor CU-2 would attribute the break to here");
        }
    }

    /** An event needing no substantiality conclusion — every driver but a negotiated change. */
    public static PeriodEvent of(RateDriver driver, LocalDate eventDate, FlowVector revisedFlows) {
        return new PeriodEvent(driver, eventDate, revisedFlows, null);
    }

    /** An event carrying a recorded substantiality conclusion. */
    public static PeriodEvent assessed(
        RateDriver driver, LocalDate eventDate, FlowVector revisedFlows,
        ModificationConclusion conclusion) {
        Objects.requireNonNull(conclusion, "conclusion");
        return new PeriodEvent(driver, eventDate, revisedFlows, conclusion);
    }

    /** Whether a substantiality conclusion has been recorded and is one the engine may act on. */
    public boolean hasDecidedConclusion() {
        return conclusion != null && conclusion.isDecided();
    }
}
