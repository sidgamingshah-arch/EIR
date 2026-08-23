package com.crisil.eir.calc.amort;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Amortisation of a purchased or originated credit-impaired asset at its
 * credit-adjusted EIR (calculation specification section 8, reference case 6).
 *
 * <p>ACPIR 6(4), 24 and 50 track IFRS 9 closely. The credit-adjusted EIR discounts
 * <em>expected</em> cash flows — already net of expected credit losses — to the
 * <em>amortised cost</em> at initial recognition, not to a gross amount. Day-1
 * lifetime ECL sits inside the rate, so there is no separate day-1 allowance;
 * thereafter only cumulative changes in lifetime ECL relative to the initial
 * estimate are recognised, and those are impairment, not interest.
 *
 * <p><strong>Why the rate has to be credit-adjusted.</strong> Reference case 6 is
 * a pool bought for 700,000 against 24 contractual instalments of 47,073.47 with
 * 20% of every receipt expected to be lost. Discounting the <em>contractual</em>
 * flows to that distressed price yields 64.703664% per annum: arithmetically
 * impeccable, economically fictional, and it would accrue income the entity has no
 * expectation of collecting before reversing it through impairment. The
 * credit-adjusted rate is 29.141920% and it is the one that makes the income
 * statement mean anything.
 *
 * <p><strong>Why this is a type and not a method.</strong> The rate is held, not
 * passed. Every operation on a POCI asset — the initial schedule, a cure, a later
 * segment — goes through the same instance and therefore the same rate, so
 * invariant POCI-1 is not something the caller has to remember: there is no
 * parameter through which a re-solved rate could be supplied. A cure improves the
 * expected cash flows; it does not retrospectively make a distressed purchase
 * price a par acquisition, so the rate stands (2019 IFRS Interpretations Committee
 * direction).
 *
 * <p>POCI identification is driven off <em>acquisition terms</em> — ACPIR 6(3)(v)
 * makes a discount reflecting inherent credit losses an explicit
 * credit-impairment indicator — and so applies at transition as well as at
 * origination (FR-110). That determination is upstream of this class; what arrives
 * here is already POCI.
 *
 * @param creditAdjustedEir                 solved on expected flows to amortised cost
 * @param amortisedCostAtInitialRecognition the purchase price or fair value at recognition
 * @param convention                        the time convention the rate was solved under
 */
public record PociAmortisation(
    Rate creditAdjustedEir,
    Money amortisedCostAtInitialRecognition,
    TimeConvention convention) {

    public PociAmortisation {
        Objects.requireNonNull(creditAdjustedEir, "creditAdjustedEir");
        Objects.requireNonNull(amortisedCostAtInitialRecognition, "amortisedCostAtInitialRecognition");
        Objects.requireNonNull(convention, "convention");
        if (creditAdjustedEir.periodsPerYear() != convention.periodsPerYear()) {
            throw new IllegalArgumentException(
                "credit-adjusted EIR compounds " + creditAdjustedEir.periodsPerYear()
                    + " times a year but convention " + convention.label() + " implies "
                    + convention.periodsPerYear());
        }
    }

    /**
     * Takes the amortised cost at initial recognition from the vector's own
     * inception flows.
     *
     * <p>The rate solves to amortised cost, and for a purchased pool that is the
     * price paid. Deriving it from the same vector the rate was solved against is
     * what stops a gross figure being used here — there is no gross-basis phase for
     * a POCI asset, so a gross opening balance would not merely be imprecise, it
     * would be a different measurement basis.
     */
    public static PociAmortisation atInitialRecognition(
        Rate creditAdjustedEir, FlowVector expectedFlows, TimeConvention convention) {
        return new PociAmortisation(
            creditAdjustedEir, Discounting.netAtInception(expectedFlows).negate(), convention);
    }

    /**
     * There is no day-1 allowance on a POCI asset, and this is the figure that says
     * so.
     *
     * <p>Lifetime expected credit losses are inside the rate. Recognising an
     * allowance as well would charge the same losses twice: once through a
     * suppressed yield for the whole life and once immediately. Only cumulative
     * <em>changes</em> in lifetime ECL against the initial estimate are recognised
     * afterwards.
     */
    public Money dayOneAllowance() {
        return Money.zero(amortisedCostAtInitialRecognition.currency());
    }

    /**
     * The schedule from initial recognition over the expected flows.
     *
     * <p>TR-1 is asserted: the rate was solved to this amortised cost over these
     * flows, so they amortise it to zero.
     */
    public AmortisationResult amortise(FlowVector expectedFlows) {
        return AmortisationEngine.eirLeg(
            amortisedCostAtInitialRecognition, creditAdjustedEir, expectedFlows, convention);
    }

    /**
     * The schedule after a cure, at the retained credit-adjusted EIR.
     *
     * <p>Prospective and at the same rate. The improvement in expected cash flows is
     * an impairment gain — recognised as the cumulative change in lifetime ECL
     * against the initial estimate — and not an interest catch-up, so the carrying
     * amount to continue from is supplied by the impairment measurement rather than
     * recomputed here. No terminal assertion for that reason: after an
     * impairment-driven remeasurement the carrying amount is no longer the present
     * value of the revised flows at this rate, and it is not meant to be.
     *
     * @param carryingAmountAtCure   amortised cost after the impairment gain
     * @param revisedExpectedFlows   expected flows from the cure date onward
     */
    public AmortisationResult afterCure(Money carryingAmountAtCure, FlowVector revisedExpectedFlows) {
        return AmortisationEngine.segment(
            carryingAmountAtCure, creditAdjustedEir, revisedExpectedFlows, convention);
    }

    /**
     * Invariant POCI-1: whatever rate another component holds for this asset after a
     * cure is still the credit-adjusted rate this instance was created with.
     *
     * <p>Structurally unnecessary here — {@link #afterCure} has no rate parameter —
     * and worth asserting anyway at any boundary where the rate has been round-
     * tripped through persistence or through a generic event handler that re-solves
     * by default.
     */
    public InvariantResult assertRateRetained(Rate observedAfterCure) {
        return InvariantChecks.creditAdjustedRateRetained(creditAdjustedEir, observedAfterCure);
    }

    /**
     * What the same flows would yield if discounted as if contractual — the wrong
     * answer, retained for the disclosure that shows why the credit-adjusted rate
     * was used.
     *
     * @param rateOnContractualFlows an EIR solved on contractual rather than expected flows
     */
    public BigDecimal creditAdjustmentBps(Rate rateOnContractualFlows) {
        return rateOnContractualFlows.effectiveAnnualBps()
            .subtract(creditAdjustedEir.effectiveAnnualBps());
    }
}
