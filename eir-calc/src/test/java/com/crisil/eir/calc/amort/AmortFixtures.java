package com.crisil.eir.calc.amort;

import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference case 1 loan as flow vectors, built by hand.
 *
 * <p>Hand-built on purpose, for the same reason the solver fixtures are: projection
 * is a separate stage (calculation specification 3) and the amortisation engine
 * never reads contract terms, so an amortisation test that obtained its vector from
 * a projector would fail on a projection defect and report it as an amortisation
 * defect. The amounts below are the reference case's own — 1,000,000 advanced at
 * 12% p.a. nominal monthly over 24 EMIs of 47,073.47, with 15,000 of processing fee
 * received against 10,000 of DSA commission paid — not a projector's output.
 *
 * <p>The EIR is likewise the fixture's published figure rather than a solve.
 * 1.04214918% per month is what the reference case states and what a published
 * amortisation has to be reproducible from (specification 1.4), so pinning the roll-
 * forward against the published rate is the assertion that matters. Where a case has
 * no published rate — the mirrored net-cost loan, the nil-fee loan — the fixture
 * solves, because there the property under test is a sign ordering and not a digit.
 *
 * <p>Dates are explicit constants. Nothing in the calculation path may read a clock,
 * so a fixture that derived a disbursement date from today would be asserting a
 * moving target.
 */
final class AmortFixtures {

    /** Reference case 1's inception date, and the anchor of every inception vector here. */
    static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);

    /**
     * The month-12 due date — the event date for reference cases 3 and 5.
     *
     * <p>An event vector is anchored here, not at disbursement: a restatement
     * discounts the flows that remain, from the date they remain at.
     */
    static final LocalDate MONTH_12 = DISBURSEMENT.plusMonths(12);

    /** Monthly periodic indexing: tau is the period ordinal, so every dtau is exactly 1. */
    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /** 12% p.a. nominal with monthly compounding: 1% per month, never 12% divided by 12. */
    static final Rate CONTRACTUAL = Rate.monthly(new BigDecimal("0.01"));

    /** The published case 1 EIR: 1.04214918% per month, 13.248094% effective p.a. */
    static final Rate CASE1_EIR = Rate.monthly(new BigDecimal("0.0104214918"));

    static final Money PRINCIPAL = Money.inr("1000000");

    /** The billed EMI: the true annuity payment of 47,073.472223 rounded to paise. */
    static final Money EMI = Money.inr("47073.47");

    /** GCA at initial recognition — 1,000,000 less the 5,000 net integral fee (invariant IC-1). */
    static final Money INITIAL_GCA = Money.inr("995000");

    /** 15,000 received less 10,000 paid. Positive is income. */
    static final Money NET_INTEGRAL_FEE = Money.inr("5000");

    /** Case 1 month-12 closing balances: the EIR leg and the contractual leg. */
    static final Money GCA_AT_MONTH_12 = Money.inr("528407.32");

    static final Money CONTRACTUAL_BALANCE_AT_MONTH_12 = Money.inr("529815.61");

    private AmortFixtures() {
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * Reference case 1's billed vector: the advance and both fee legs on the anchor
     * date, then 24 monthly instalments.
     *
     * <p>Both legs consume this one vector. The contractual leg has to roll over the
     * schedule the core banking system actually billed for the reconciliation to mean
     * anything (specification 5.7, {@code LMS_AUTHORITATIVE}), and the EIR leg has to
     * roll over the same flows the rate was solved against or TR-1 is not a statement
     * about anything.
     */
    static FlowVector case1Billed() {
        return inceptionVector(EMI, 24, Money.inr("15000"), Money.inr("-10000"));
    }

    /**
     * An inception vector: 1,000,000 advanced, the given inception-dated fee postings,
     * then {@code periods} level instalments.
     *
     * <p>Period 0 carries the inception leg and periods 1..n the receipts, because the
     * period index is explicit and the anchor-dated flows are the solve target rather
     * than something discounted.
     */
    static FlowVector inceptionVector(Money instalment, int periods, Money... inceptionPostings) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, PRINCIPAL.negate(), FlowKind.DISBURSEMENT));
        for (Money posting : inceptionPostings) {
            FlowKind kind = posting.isNegative() ? FlowKind.INTEGRAL_COST_PAID : FlowKind.INTEGRAL_FEE_RECEIVED;
            flows.add(CashFlow.of(DISBURSEMENT, 0, posting, kind));
        }
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(DISBURSEMENT.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(DISBURSEMENT, Money.INR, flows);
    }

    /** The case 1 EIR leg: 995,000.00 rolled at the published EIR over the billed flows. */
    static AmortisationResult case1EirLeg() {
        return AmortisationEngine.eirLeg(INITIAL_GCA, CASE1_EIR, case1Billed(), MONTHLY);
    }

    /** The case 1 contractual leg: par rolled at 1% per month over the same billed flows. */
    static AmortisationResult case1ContractualLeg() {
        return AmortisationEngine.contractualLeg(PRINCIPAL, CONTRACTUAL, case1Billed(), MONTHLY);
    }

    /** The case 1 two-leg reconciliation. */
    static TwoLegResult case1TwoLeg() {
        return TwoLegResult.reconcile(
            case1EirLeg(), case1ContractualLeg(), CASE1_EIR, CONTRACTUAL, NET_INTEGRAL_FEE);
    }

    /**
     * A level schedule of {@code periods} instalments anchored on an event date.
     *
     * <p>Used for the flows that <em>remain</em> after an event. Nothing is dated on
     * the anchor: cash received on the event date has already been applied by the time
     * a restatement runs (event ordering step 2 precedes step 4), so discounting it
     * into the restated balance would count it twice.
     */
    static FlowVector remainingAt(LocalDate anchor, Money instalment, int periods) {
        return remainingAt(anchor, instalment, periods, null);
    }

    /** As {@link #remainingAt(LocalDate, Money, int)}, with a smaller final instalment. */
    static FlowVector remainingAt(LocalDate anchor, Money instalment, int periods, Money finalInstalment) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(anchor.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        if (finalInstalment != null) {
            flows.add(CashFlow.of(
                anchor.plusMonths(periods + 1), periods + 1, finalInstalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(anchor, Money.INR, flows);
    }

    /** Reference case 3's revised schedule: 18 instalments of 32,309.24 from month 12. */
    static FlowVector case3RevisedFlows() {
        return remainingAt(MONTH_12, Money.inr("32309.24"), 18);
    }

    /** The 12 instalments that would have remained had nothing been re-profiled. */
    static FlowVector case1RemainingAtMonth12() {
        return remainingAt(MONTH_12, EMI, 12);
    }

    /** Solves a vector to its own inception leg, seeded from the contractual rate. */
    static Rate solveAtInception(FlowVector vector) {
        SolveResult result = new BracketedNewtonSolver()
            .solve(SolveRequest.atInception(vector, MONTHLY, CONTRACTUAL.periodic()));
        return result.rateOrThrow();
    }
}
