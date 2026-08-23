package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.List;

/**
 * Instruments issued or bought at a discount and redeemed at face: T-bills,
 * commercial paper, certificates of deposit, zero-coupon bonds.
 *
 * <p>{@code principal} here is the <b>face value at maturity</b>, not the amount
 * advanced, and the price is derived by discounting it at the contractual yield.
 * That is the opposite of {@link BulletProjector}, where the principal is what
 * goes out and the redemption is derived — the two shapes are quoted the way their
 * markets quote them, and forcing one convention on both would push the conversion
 * into every caller.
 *
 * <p><b>Approximation is never permitted here, at any tenor</b> (calculation
 * specification section 10.3). The entire return is accretion, so the
 * straight-line error compounds with tenor and cannot pass a Tier 3 equivalence
 * test. On Case 9's 15-year zero-coupon at 8% — 315,241.70 for 1,000,000 of face —
 * straight-line accretion overstates year-one income by 81.0% and understates the
 * final year by 38.4%, while the life totals tie to the paisa. An error that nets
 * to zero over fifteen years is invisible to any control that looks at cumulative
 * figures and material in every single reporting period, which is exactly what
 * makes it the wedge against selective EIR adoption on the investment book.
 *
 * <p>Day-count discipline bites hardest here and it bites inversely to tenor. On a
 * 20-year mortgage a convention error is noise; on a 7-day money-market instrument
 * a three-day error is a materially wrong rate. Indian convention is actual/365
 * for money market, and the price is derived under whichever convention the vector
 * licenses so that the solver recovers the yield it was priced at rather than one
 * that a mismatched exponent produced.
 */
public final class DiscountInstrumentProjector implements CashflowProjector {

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.DISCOUNT_INSTRUMENT && !terms.hasMoratorium();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        return ProjectionSupport.assemble(
            terms, issuePrice(terms), fees, List.of(redemption(terms)), false);
    }

    /** The face value received at maturity. */
    public Money faceValue(ContractTerms terms) {
        return terms.principal().atPresentationScale();
    }

    /**
     * The price: face discounted at the contractual yield over the instrument's
     * own tenor — 315,241.70 on the Case 9 bond.
     *
     * <p>Rounded to presentation scale, because a price is cash actually paid. The
     * sub-paise difference between the rounded price and the exact present value is
     * a real feature of a traded instrument, not a defect: the solver will recover
     * the yield the trade actually struck rather than the one the calculator wanted.
     */
    public Money issuePrice(ContractTerms terms) {
        CashFlow maturity = redemption(terms);
        TimeConvention convention = pricingConvention(terms, maturity);
        BigDecimal rate = ProjectionSupport.rateUnder(convention, terms.contractualRate());
        BigDecimal tau = convention.tau(terms.disbursementDate(), maturity);
        return faceValue(terms).times(Precision.discountFactor(rate, tau)).atPresentationScale();
    }

    /** Face less price — the whole of the return, all of it accretion. */
    public Money discountToAccrete(ContractTerms terms) {
        return faceValue(terms).minus(issuePrice(terms));
    }

    private static CashFlow redemption(ContractTerms terms) {
        return CashFlow.of(
            terms.maturityDate(), terms.termPeriods(),
            terms.principal().atPresentationScale(), FlowKind.PRINCIPAL);
    }

    /**
     * The convention the redemption flow licenses.
     *
     * <p>Checked on the redemption flow alone, which is sound because the inception
     * leg sits on the anchor and is never discounted: the flows that determine
     * eligibility are exactly the ones present here.
     */
    private static TimeConvention pricingConvention(ContractTerms terms, CashFlow maturity) {
        FlowVector provisional = FlowVector.of(
            terms.disbursementDate(), terms.currency(), List.of(maturity));
        return ConventionSelector.select(provisional, terms.periodsPerYear(), terms.dayCount());
    }
}
