package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Step-up and step-down instalment ladders.
 *
 * <p>The instalment for step {@code k} is {@code A0 * f^k}, and the base
 * instalment is solved so that the whole ladder discounts to the principal at the
 * contractual rate:
 *
 * <pre>
 * A0 = P / SUM_t ( f^k(t) * (1+i)^-t )     k(t) = (t-1) / stepEveryPeriods
 * </pre>
 *
 * <p>Direct summation rather than a closed form. The closed form exists only for
 * a step at every period and would have to be abandoned the first time a product
 * steps every second year; {@code n} terms at working precision is cheap, and the
 * projection runs once per contract, not once per period.
 *
 * <p><b>Naming and arithmetic must agree.</b> A shape declared
 * {@link ScheduleShape#STEP_UP} with a factor below 1 is rejected rather than
 * projected as a step-down. The declared shape reaches disclosure and the factor
 * reaches the schedule; letting them disagree puts a contract in the wrong
 * population in every report that groups by shape.
 *
 * <p>A contractual step is <em>not</em> a market movement. Its driver is
 * {@code STEP_UP_PREDETERMINED}, which routes to a catch-up rather than to a rate
 * reset (calculation specification section 6.1) — the step is known at inception
 * and is already inside the EIR, so a subsequent step is not new information about
 * price.
 */
public final class StepScheduleProjector implements CashflowProjector {

    private final int stepEveryPeriods;
    private final ResiduePolicy residuePolicy;
    private final int spreadPeriods;

    /** Steps annually — the common retail and project-finance ladder. */
    public StepScheduleProjector() {
        this(0, ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    /**
     * @param stepEveryPeriods periods between steps; 0 means once a year, taken
     *     from the schedule's own frequency
     */
    public StepScheduleProjector(int stepEveryPeriods) {
        this(stepEveryPeriods, ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    public StepScheduleProjector(int stepEveryPeriods, ResiduePolicy residuePolicy, int spreadPeriods) {
        if (stepEveryPeriods < 0) {
            throw new IllegalArgumentException("stepEveryPeriods must be >= 0, got " + stepEveryPeriods);
        }
        this.stepEveryPeriods = stepEveryPeriods;
        this.residuePolicy = Objects.requireNonNull(residuePolicy, "residuePolicy");
        this.spreadPeriods = spreadPeriods;
    }

    @Override
    public boolean supports(ContractTerms terms) {
        return (terms.shape() == ScheduleShape.STEP_UP || terms.shape() == ScheduleShape.STEP_DOWN)
            && terms.stepFactor() != null
            && !terms.hasMoratorium();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        List<CashFlow> ladder = billedLadder(terms);
        Money residue = residue(terms, ladder);
        List<CashFlow> resolved = residuePolicy.apply(ladder, residue, terms.periodicRate(), spreadPeriods);
        return ProjectionSupport.assemble(terms, terms.principal(), fees, resolved, true);
    }

    /** Periods between steps for these terms. */
    public int stepEveryPeriods(ContractTerms terms) {
        return stepEveryPeriods > 0 ? stepEveryPeriods : terms.periodsPerYear();
    }

    /** The first step's instalment, at working precision. */
    public Money baseInstalment(ContractTerms terms) {
        BigDecimal factor = requireDirectedFactor(terms);
        int step = stepEveryPeriods(terms);
        BigDecimal weighted = BigDecimal.ZERO;
        for (int period = 1; period <= terms.termPeriods(); period++) {
            BigDecimal ladder = ladderMultiplier(factor, (period - 1) / step);
            BigDecimal discount = Precision.discountFactor(
                terms.periodicRate(), BigDecimal.valueOf(period));
            weighted = weighted.add(ladder.multiply(discount, Precision.WORKING), Precision.WORKING);
        }
        if (weighted.signum() <= 0) {
            throw new IllegalArgumentException(
                "the step ladder discounts to a non-positive weight; factor "
                    + factor.toPlainString() + " over " + terms.termPeriods() + " periods is not a schedule");
        }
        return terms.principal().dividedBy(weighted);
    }

    /** The billed instalment for a period ordinal. */
    public Money billedInstalmentAt(ContractTerms terms, int periodIndex) {
        if (periodIndex < 1 || periodIndex > terms.termPeriods()) {
            throw new IllegalArgumentException(
                "periodIndex must be in [1, " + terms.termPeriods() + "], got " + periodIndex);
        }
        BigDecimal factor = requireDirectedFactor(terms);
        int step = (periodIndex - 1) / stepEveryPeriods(terms);
        return baseInstalment(terms).times(ladderMultiplier(factor, step)).atPresentationScale();
    }

    public ResiduePolicy residuePolicy() {
        return residuePolicy;
    }

    /** The ladder, billed, in period order. */
    private List<CashFlow> billedLadder(ContractTerms terms) {
        Money base = baseInstalment(terms);
        BigDecimal factor = requireDirectedFactor(terms);
        int step = stepEveryPeriods(terms);
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= terms.termPeriods(); period++) {
            BigDecimal ladder = ladderMultiplier(factor, (period - 1) / step);
            flows.add(CashFlow.of(terms.dueDate(period), period,
                base.times(ladder).atPresentationScale(), FlowKind.COMBINED_EMI));
        }
        return flows;
    }

    /**
     * {@code f^k} at working precision.
     *
     * <p>Not routed through the one-plus-rate power helper: the ladder multiplier
     * is a multiplier, not a rate, and expressing it as {@code (1 + (f-1))^k}
     * would disguise that.
     */
    private static BigDecimal ladderMultiplier(BigDecimal factor, int step) {
        return factor.pow(step, Precision.WORKING);
    }

    private BigDecimal requireDirectedFactor(ContractTerms terms) {
        BigDecimal factor = terms.stepFactor();
        if (factor == null) {
            throw new IllegalArgumentException("a step schedule needs a stepFactor");
        }
        int comparison = factor.compareTo(BigDecimal.ONE);
        if (terms.shape() == ScheduleShape.STEP_UP && comparison <= 0) {
            throw new IllegalArgumentException(
                "STEP_UP needs a factor above 1, got " + factor.toPlainString()
                    + "; declare the shape the schedule actually has");
        }
        if (terms.shape() == ScheduleShape.STEP_DOWN && comparison >= 0) {
            throw new IllegalArgumentException(
                "STEP_DOWN needs a factor below 1, got " + factor.toPlainString()
                    + "; declare the shape the schedule actually has");
        }
        return factor;
    }

    private Money residue(ContractTerms terms, List<CashFlow> leg) {
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), leg);
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), provisional, terms.termPeriods());
    }
}
