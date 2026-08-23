package com.crisil.eir.calc.routing;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * The quantitative 10% test, computed as <strong>evidence</strong> and — on the
 * asset side — deliberately not as a decision (calculation specification
 * section 6.4, FR-511).
 *
 * <pre>
 * ratio = | PV(revised flows @ original EIR) - PV(remaining old flows @ original EIR) |
 *         --------------------------------------------------------------------------
 *                     | PV(remaining old flows @ original EIR) |
 * </pre>
 *
 * <h2>Why this class refuses to answer</h2>
 *
 * <p>Because on the asset side it has no authority to. IFRS 9 sets the 10% bright
 * line for financial <em>liabilities</em> only (B3.3.6); for assets there is no
 * equivalent, and the IASB's February 2025 tentative direction is toward a
 * principles-based qualitative assessment whose outcome cannot be determined by a
 * quantitative test alone. ACPIR is silent on the boundary too: paragraphs 78–81
 * govern stage migration on restructuring and cross-refer the Resolution of
 * Stressed Assets Directions 2025, but say nothing about whether the original EIR
 * survives. For a bank with a restructuring book, a DCCO-deferment book and FITL
 * creation, that silence is the most consequential single gap in the standard.
 *
 * <p>So this is a deliberate refusal to automate a judgement, not an unfinished
 * feature. Silently derecognising a corporate loan on a 10.02% test result is not
 * a feature: the ratio is a function of the projection assumptions behind the
 * revised flows — a prepayment curve, a deferred DCCO, a tranche re-profile — and
 * moving one of those by a rounding's worth moves the result across the line while
 * changing nothing about the economics. What the engine owes is the computation,
 * the qualitative evidence, and a conclusion that says {@code REQUIRES_APPROVAL}
 * where the evidence does not settle the question. Who then decided, on what basis
 * and under which policy version is recorded on the event.
 *
 * <p>On the liability side the test <em>is</em> authoritative and the engine acts
 * on it — with one asymmetry, described in
 * {@link #evaluate(BigDecimal, FlowVector, FlowVector, TimeConvention, InstrumentSide, ReviewBand, Collection)}.
 *
 * <h2>Discounting</h2>
 *
 * <p>Both legs are discounted at the <em>original</em> EIR through
 * {@link Discounting}, which is the same code path the catch-up restatement of
 * section 6.3 uses. That is not tidiness: a test computed with slightly different
 * discounting from the catch-up it feeds would let an event fail the test and then
 * be restated on a different basis than it was tested on.
 *
 * <p>Cash flows dated on the modification date itself enter at a discount factor
 * of one rather than being dropped — see
 * {@link #presentValueAtModificationDate}. B3.3.6 requires fees paid net of fees
 * received to be included, and a restructuring fee is nearly always settled on the
 * modification date.
 */
public final class ModificationTest {

    private ModificationTest() {
    }

    /**
     * Runs the test and reaches a conclusion, or declines to.
     *
     * <p>The conclusion rules, and each one's reason:
     *
     * <ul>
     *   <li><strong>Asset, ratio inside the band</strong> —
     *       {@link ModificationConclusion#REQUIRES_APPROVAL}. The number is too
     *       close to the line to bear the weight of a derecognition, and there is
     *       no bright line for assets in any case.
     *   <li><strong>Asset, ratio below the threshold but a qualitative trigger
     *       fired</strong> — {@code REQUIRES_APPROVAL}. The two kinds of evidence
     *       disagree. A change of obligor with a 3% present-value difference is
     *       exactly the case the February 2025 direction is aimed at, and
     *       concluding {@code NOT_SUBSTANTIAL} from the ratio alone would discard
     *       the qualitative assessment the standard is moving toward.
     *   <li><strong>Asset, otherwise</strong> — the ratio's own indication,
     *       {@link ModificationConclusion#SUBSTANTIAL} or
     *       {@link ModificationConclusion#NOT_SUBSTANTIAL}, as a recommendation
     *       resting on evidence that is clear of the band.
     *   <li><strong>Liability, ratio at or above the threshold</strong> —
     *       {@code SUBSTANTIAL}. B3.3.6 is a bright line and the engine applies
     *       it; the band is still reported but does not change the outcome.
     *   <li><strong>Liability, below the threshold but a qualitative trigger
     *       fired</strong> — {@code REQUIRES_APPROVAL}. B3.3.6 makes a 10%
     *       difference conclusive <em>of</em> substantiality; it does not make a
     *       smaller difference conclusive against it, and a change of currency or
     *       the introduction of an equity conversion feature is not disposed of by
     *       a passing ratio.
     *   <li><strong>Liability, otherwise</strong> — {@code NOT_SUBSTANTIAL}.
     * </ul>
     *
     * <p>Both vectors must be anchored on the modification date and denominated in
     * the same currency: the ratio compares two present values, and present values
     * struck at different dates are not comparable. Contingent flows are rejected
     * from both legs (section 3.3) — a prepayment penalty that may never be
     * incurred would move the ratio across the threshold on a cash flow that never
     * happens.
     *
     * @param originalEir            the rate the instrument was carried at before the
     *                               modification, expressed on the same basis the
     *                               convention implies: periodic for
     *                               {@link TimeConvention.PeriodicIndex}, annual
     *                               effective for {@link TimeConvention.ActualDate}
     * @param remainingOriginalFlows the flows the original terms would still have produced
     * @param revisedFlows           the flows the revised terms will produce, including any
     *                               fee settled on the modification date
     * @param convention             how tau is derived; the same convention for both legs
     * @param side                   asset or liability — decides whether the ratio decides
     * @param band                   threshold and review band, a policy input
     * @param triggersFired          the qualitative triggers that fired; empty, never null
     */
    public static ModificationTestResult evaluate(
        BigDecimal originalEir,
        FlowVector remainingOriginalFlows,
        FlowVector revisedFlows,
        TimeConvention convention,
        InstrumentSide side,
        ReviewBand band,
        Collection<QualitativeTrigger> triggersFired) {

        Objects.requireNonNull(originalEir, "originalEir");
        Objects.requireNonNull(remainingOriginalFlows, "remainingOriginalFlows");
        Objects.requireNonNull(revisedFlows, "revisedFlows");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(triggersFired, "triggersFired");
        requireDiscountable(originalEir);

        if (!remainingOriginalFlows.anchorDate().equals(revisedFlows.anchorDate())) {
            throw new IllegalArgumentException(
                "both legs must be anchored on the modification date; remaining flows are anchored on "
                    + remainingOriginalFlows.anchorDate() + " and revised flows on "
                    + revisedFlows.anchorDate());
        }
        if (!remainingOriginalFlows.currency().equals(revisedFlows.currency())) {
            throw new IllegalArgumentException(
                "both legs must be in one currency, got " + remainingOriginalFlows.currency().getCurrencyCode()
                    + " and " + revisedFlows.currency().getCurrencyCode());
        }
        remainingOriginalFlows.requireNoContingentFlows();
        revisedFlows.requireNoContingentFlows();

        Money pvRemaining = presentValueAtModificationDate(originalEir, remainingOriginalFlows, convention);
        Money pvRevised = presentValueAtModificationDate(originalEir, revisedFlows, convention);
        BigDecimal ratio = ratio(pvRevised, pvRemaining);

        boolean breaches = band.breaches(ratio);
        boolean withinBand = band.contains(ratio);
        List<QualitativeTrigger> fired = normalise(triggersFired);
        ModificationConclusion conclusion = conclude(side, breaches, withinBand, !fired.isEmpty());

        return new ModificationTestResult(
            side, pvRevised, pvRemaining, ratio, breaches, withinBand, band, fired, conclusion);
    }

    /**
     * {@link #evaluate(BigDecimal, FlowVector, FlowVector, TimeConvention, InstrumentSide, ReviewBand, Collection)}
     * with no qualitative trigger fired.
     *
     * <p>Convenience only, and worth being explicit about what it asserts: that the
     * qualitative assessment was performed and found nothing, not that it was
     * skipped. A modification assessed on the ratio alone is not assessed.
     */
    public static ModificationTestResult evaluate(
        BigDecimal originalEir,
        FlowVector remainingOriginalFlows,
        FlowVector revisedFlows,
        TimeConvention convention,
        InstrumentSide side,
        ReviewBand band) {
        return evaluate(originalEir, remainingOriginalFlows, revisedFlows, convention, side, band,
            EnumSet.noneOf(QualitativeTrigger.class));
    }

    /**
     * Present value of a leg at its anchor — the modification date — with
     * same-day flows included undiscounted.
     *
     * <p>{@link Discounting} discounts only flows strictly after the anchor,
     * because in a solve the anchor-dated flows are the target the rate is solved
     * to and counting them on both sides would double-count them. There is no
     * solve here, and B3.3.6's test includes fees paid net of fees received — a
     * restructuring fee, a consent fee, an upfront recovery — which are settled on
     * the modification date and belong in the present value at a factor of one.
     * Dropping them is the quiet way to understate the ratio on precisely the
     * transactions where the fee is the substance of the change.
     */
    public static Money presentValueAtModificationDate(
        BigDecimal rate, FlowVector vector, TimeConvention convention) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(convention, "convention");
        return Discounting.presentValueMoney(rate, vector, convention)
            .plus(Discounting.netAtInception(vector));
    }

    /**
     * {@code |revised - remaining| / |remaining|} at working precision.
     *
     * <p>The denominator is taken in absolute terms so that the ratio is
     * independent of the sign convention a liability's flows are modelled under.
     * Both legs must of course use the <em>same</em> convention; they come from one
     * projection, so they do.
     */
    public static BigDecimal ratio(Money presentValueRevised, Money presentValueRemaining) {
        Objects.requireNonNull(presentValueRevised, "presentValueRevised");
        Objects.requireNonNull(presentValueRemaining, "presentValueRemaining");
        BigDecimal denominator = presentValueRemaining.amount().abs();
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException(
                "the remaining original flows have zero present value, so the 10% test is undefined; a "
                    + "fully repaid or fully written-off exposure is not a modification");
        }
        BigDecimal difference = presentValueRevised.minus(presentValueRemaining).amount().abs();
        return difference.divide(denominator, Precision.WORKING);
    }

    /** The rules set out on {@link #evaluate}. */
    private static ModificationConclusion conclude(
        InstrumentSide side, boolean breaches, boolean withinBand, boolean triggered) {

        if (side.quantitativeTestIsAuthoritative()) {
            if (breaches) {
                return ModificationConclusion.SUBSTANTIAL;
            }
            return triggered ? ModificationConclusion.REQUIRES_APPROVAL
                : ModificationConclusion.NOT_SUBSTANTIAL;
        }
        if (withinBand) {
            return ModificationConclusion.REQUIRES_APPROVAL;
        }
        if (triggered && !breaches) {
            return ModificationConclusion.REQUIRES_APPROVAL;
        }
        return breaches ? ModificationConclusion.SUBSTANTIAL : ModificationConclusion.NOT_SUBSTANTIAL;
    }

    /** De-duplicated, in enum order, so two results are comparable on their evidence. */
    private static List<QualitativeTrigger> normalise(Collection<QualitativeTrigger> triggers) {
        EnumSet<QualitativeTrigger> set = EnumSet.noneOf(QualitativeTrigger.class);
        for (QualitativeTrigger trigger : triggers) {
            set.add(Objects.requireNonNull(trigger, "qualitative trigger"));
        }
        return List.copyOf(set);
    }

    /** A rate at or below -100% has no discount factor; fail loudly rather than in the power. */
    private static void requireDiscountable(BigDecimal rate) {
        if (BigDecimal.ONE.add(rate).signum() <= 0) {
            throw new IllegalArgumentException(
                "originalEir must exceed -1, got " + rate.toPlainString());
        }
    }
}
