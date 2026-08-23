package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One repayment at maturity.
 *
 * <p>Two profiles, and the contractual rate distinguishes them:
 *
 * <ul>
 *   <li><b>Zero-coupon.</b> Interest accrues, capitalises and is repaid with the
 *       principal. 1,000,000 advanced at 1% per month for 60 months redeems at
 *       1,816,696.70 — Case 7's calibration instrument, a single flow with a
 *       closed-form answer and no room for the solver to hide. The terminal amount
 *       is carried at working precision for that reason; see
 *       {@link #accruedInterest}.
 *   <li><b>Interest-free.</b> A zero rate repays the principal alone. Not a
 *       degenerate case to reject: interest-free instalment credit and
 *       intra-group advances exist, and their EIR is zero only if no fee is
 *       integral.
 * </ul>
 *
 * <p>The principal and the accrued interest are emitted as two flows on the same
 * date and period ordinal rather than one combined amount. Discounting is
 * identical either way — kind never affects it — but the contractual interest leg
 * and invariant INV-3 both need the split, and a projector that combines them
 * forces every downstream consumer to re-derive it.
 *
 * <p>A bullet that <em>services</em> interest periodically is
 * {@link ScheduleShape#INTEREST_ONLY_BULLET}, not this. The distinction is
 * contractual, not cosmetic: servicing interest changes every discount factor in
 * the vector.
 */
public final class BulletProjector implements CashflowProjector {

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.BULLET && !terms.hasMoratorium();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        int maturity = terms.termPeriods();
        List<CashFlow> future = new ArrayList<>();
        future.add(CashFlow.of(
            terms.dueDate(maturity), maturity, terms.principal(), FlowKind.PRINCIPAL));
        Money accrued = accruedInterest(terms);
        if (accrued.isPositive()) {
            future.add(CashFlow.of(terms.dueDate(maturity), maturity, accrued, FlowKind.INTEREST));
        }
        return ProjectionSupport.assemble(terms, terms.principal(), fees, future, false);
    }

    /**
     * The interest capitalised over the full term, at working precision.
     *
     * <p>{@code P * ((1+i)^n - 1)}, compounded — not a simple-interest pro-rata,
     * which understates a long accrual and is a common source of small persistent
     * breaks.
     *
     * <p><b>Not rounded to paise here</b>, unlike a billed instalment. There is no
     * schedule of billed amounts on a zero-coupon: there is one terminal
     * settlement, and rounding it in the projection would put 1.3e-11 into the
     * solved rate — which is visible in the twelfth decimal place, and the twelfth
     * decimal place is exactly where Case 7's calibration instrument is asserted
     * ("a single flow, closed-form answer, no room to hide"). Presentation happens
     * once, where the figure is persisted or billed, and that is downstream of
     * here.
     */
    public Money accruedInterest(ContractTerms terms) {
        BigDecimal rate = terms.periodicRate();
        if (rate.signum() == 0) {
            return Money.zero(terms.currency());
        }
        BigDecimal growth = Precision.onePlusPow(rate, terms.termPeriods()).subtract(BigDecimal.ONE);
        return terms.principal().times(growth);
    }

    /** The single amount received at maturity — 1,816,696.70 on the Case 7 instrument. */
    public Money redemptionAmount(ContractTerms terms) {
        return terms.principal().plus(accruedInterest(terms));
    }
}
