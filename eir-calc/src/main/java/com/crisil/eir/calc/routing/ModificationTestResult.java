package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The recorded outcome of one substantiality assessment — evidence first,
 * conclusion last (calculation specification section 6.4, FR-511).
 *
 * <p>Both present values are retained, not just the ratio they produce. A ratio
 * alone is unreviewable: the question an auditor or a credit committee asks about
 * a 10.4% result is which leg moved and by how much, and reconstructing that from
 * the ratio afterwards means re-deriving the projection. They are also the figures
 * that tie the assessment to the catch-up computation of section 6.3, which
 * discounts the same revised flows at the same original EIR.
 *
 * <p>{@link #triggersFired} is a list rather than a boolean for the same reason.
 * "A qualitative trigger fired" is not a reviewable statement; "the obligor
 * changed and the facility converted from revolving to term" is.
 *
 * @param side                which side of the balance sheet, and so whether the ratio decides or evidences
 * @param presentValueRevised PV of the revised flows at the original EIR, at the modification date
 * @param presentValueRemaining PV of the remaining original flows at the original EIR, same date
 * @param ratio               {@code |PV_revised - PV_remaining| / |PV_remaining|}, non-negative
 * @param breachesThreshold   whether the ratio reaches the band's threshold
 * @param withinReviewBand    whether the ratio lands inside the band around it
 * @param reviewBand          the threshold and band actually applied
 * @param triggersFired       the qualitative triggers that fired, in enum order; may be empty
 * @param conclusion          the engine's conclusion, or {@link ModificationConclusion#REQUIRES_APPROVAL}
 */
public record ModificationTestResult(
    InstrumentSide side,
    Money presentValueRevised,
    Money presentValueRemaining,
    BigDecimal ratio,
    boolean breachesThreshold,
    boolean withinReviewBand,
    ReviewBand reviewBand,
    List<QualitativeTrigger> triggersFired,
    ModificationConclusion conclusion) {

    public ModificationTestResult {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(presentValueRevised, "presentValueRevised");
        Objects.requireNonNull(presentValueRemaining, "presentValueRemaining");
        Objects.requireNonNull(ratio, "ratio");
        Objects.requireNonNull(reviewBand, "reviewBand");
        Objects.requireNonNull(triggersFired, "triggersFired");
        Objects.requireNonNull(conclusion, "conclusion");
        if (ratio.signum() < 0) {
            throw new IllegalArgumentException(
                "ratio is an absolute difference and cannot be negative, got " + ratio.toPlainString());
        }
        triggersFired = List.copyOf(triggersFired);
    }

    /** Whether the engine declined to conclude and the event belongs in the approval queue. */
    public boolean requiresApproval() {
        return conclusion == ModificationConclusion.REQUIRES_APPROVAL;
    }

    /** Whether any qualitative trigger fired. */
    public boolean anyTriggerFired() {
        return !triggersFired.isEmpty();
    }

    /**
     * Signed distance from the threshold, {@code ratio - threshold}.
     *
     * <p>Reported because it is what makes a result readable at a glance: +0.0002
     * says the same thing as "10.02% against a 10% line" and says it without the
     * reader having to hold the threshold in mind.
     */
    public BigDecimal distanceFromThreshold() {
        return ratio.subtract(reviewBand.threshold(), Precision.WORKING);
    }

    /**
     * The difference in present value the ratio is computed from.
     *
     * <p>Signed: negative where the revised flows are worth less than the flows
     * they replace, which for a restructured asset is the ordinary case.
     */
    public Money presentValueDifference() {
        return presentValueRevised.minus(presentValueRemaining);
    }
}
