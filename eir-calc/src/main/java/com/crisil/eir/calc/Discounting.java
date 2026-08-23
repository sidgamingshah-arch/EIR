package com.crisil.eir.calc;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Present value and its first derivative over a flow vector.
 *
 * <p>The single place discounting happens. Both the solver and the catch-up
 * restatement route through here, which is what stops them drifting apart — a
 * catch-up computed with slightly different discounting from the solve that
 * produced the original rate is the defect that silently converts a B5.4.6 event
 * into a B5.4.5 one.
 *
 * <p>Only flows strictly after the anchor are discounted. Flows on the anchor
 * date form the target the rate is solved to, and including them on both sides
 * would double-count them.
 */
public final class Discounting {

    private Discounting() {
    }

    /**
     * {@code sum( CF_t / (1+rate)^tau_t )} over future flows.
     *
     * @param rate       the periodic or annual rate, per the convention
     * @param vector     the flow vector
     * @param convention how tau is derived
     */
    public static BigDecimal presentValue(BigDecimal rate, FlowVector vector, TimeConvention convention) {
        LocalDate anchor = vector.anchorDate();
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow flow : vector.future()) {
            BigDecimal tau = convention.tau(anchor, flow);
            BigDecimal factor = Precision.discountFactor(rate, tau);
            total = total.add(flow.amount().amount().multiply(factor, Precision.WORKING), Precision.WORKING);
        }
        return total;
    }

    /** {@link #presentValue} as a {@link Money} in the vector's currency. */
    public static Money presentValueMoney(BigDecimal rate, FlowVector vector, TimeConvention convention) {
        return Money.of(presentValue(rate, vector, convention), vector.currency());
    }

    /**
     * {@code d/dr sum( CF_t / (1+rate)^tau_t )}
     * {@code  = -sum( tau_t * CF_t / (1+rate)^(tau_t + 1) )}.
     *
     * <p>Analytic rather than a finite difference. A finite-difference derivative
     * would introduce a step-size choice into a calculation whose whole point is
     * that it has no free parameters.
     */
    public static BigDecimal derivative(BigDecimal rate, FlowVector vector, TimeConvention convention) {
        LocalDate anchor = vector.anchorDate();
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow flow : vector.future()) {
            BigDecimal tau = convention.tau(anchor, flow);
            if (tau.signum() == 0) {
                continue;
            }
            BigDecimal factor = Precision.discountFactor(rate, tau.add(BigDecimal.ONE));
            BigDecimal term = tau.multiply(flow.amount().amount(), Precision.WORKING)
                .multiply(factor, Precision.WORKING);
            total = total.add(term, Precision.WORKING);
        }
        return total.negate();
    }

    /**
     * The net cash flow at inception — the sum of flows dated on the anchor.
     *
     * <p>Its absolute value is the initial gross carrying amount, and that
     * identity is invariant IC-1: where it fails to hold, either a fee has been
     * misclassified or a non-cash item has entered the vector.
     */
    public static Money netAtInception(FlowVector vector) {
        Money sum = Money.zero(vector.currency());
        for (CashFlow flow : vector.atInception()) {
            sum = sum.plus(flow.amount());
        }
        return sum;
    }

    /**
     * Counts sign changes in the flow amounts, inception included.
     *
     * <p>More than one means the present-value function may have multiple real
     * roots. Tranched project finance with large interim drawdowns is the case
     * that produces it in practice.
     */
    public static int signChanges(FlowVector vector) {
        List<CashFlow> flows = vector.flows();
        int changes = 0;
        int previous = 0;
        for (CashFlow flow : flows) {
            int signum = flow.amount().signum();
            if (signum == 0) {
                continue;
            }
            if (previous != 0 && signum != previous) {
                changes++;
            }
            previous = signum;
        }
        return changes;
    }
}
