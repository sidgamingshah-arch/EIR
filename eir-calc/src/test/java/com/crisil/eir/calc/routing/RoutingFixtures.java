package com.crisil.eir.calc.routing;

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
 * The reference case 1 loan at its month-12 modification date, as flow vectors.
 *
 * <p>Hand-built, and anchored on the event date rather than on inception, because both
 * legs of the 10% test are present values struck on the modification date — present
 * values struck at different dates are not comparable, and the test rejects vectors
 * that disagree about the date for that reason.
 *
 * <p>Dates and amounts are explicit constants. Nothing in the calculation path may read
 * a clock, and a routing test in particular has to be able to say what the answer was
 * on a stated date under a stated policy version.
 */
final class RoutingFixtures {

    /** Month 12 of reference case 1 — the modification date for cases 3 and 4. */
    static final LocalDate MODIFICATION_DATE = LocalDate.of(2027, 4, 1);

    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /** The retained case 1 EIR: 1.04214918% per month. Both legs discount at it. */
    static final BigDecimal ORIGINAL_EIR = new BigDecimal("0.0104214918");

    static final Rate CONTRACTUAL = Rate.monthly(new BigDecimal("0.01"));

    static final Money EMI = Money.inr("47073.47");

    /** EIR-leg carrying amount at month 12, and the balance a restatement moves. */
    static final Money GCA_AT_MONTH_12 = Money.inr("528407.32");

    /** Contractual balance at month 12 — what the borrower owes. */
    static final Money CONTRACTUAL_BALANCE_AT_MONTH_12 = Money.inr("529815.61");

    private RoutingFixtures() {
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** A level schedule anchored on the modification date, with nothing dated on the anchor. */
    static FlowVector level(Money instalment, int periods) {
        return level(instalment, periods, null);
    }

    /** As {@link #level(Money, int)}, with a smaller final instalment. */
    static FlowVector level(Money instalment, int periods, Money finalInstalment) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(
                MODIFICATION_DATE.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        if (finalInstalment != null) {
            flows.add(CashFlow.of(
                MODIFICATION_DATE.plusMonths(periods + 1), periods + 1, finalInstalment,
                FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(MODIFICATION_DATE, Money.INR, flows);
    }

    /** The 12 instalments the original terms would still have produced. */
    static FlowVector remainingOriginal() {
        return level(EMI, 12);
    }

    /** Reference case 3's re-profile: 18 instalments of 32,309.24. */
    static FlowVector reProfiled() {
        return level(Money.inr("32309.24"), 18);
    }

    /**
     * A one-flow pair calibrated to a stated ratio.
     *
     * <p>Where the assertion is about where a ratio lands relative to the threshold, a
     * two-flow instrument is a better fixture than a real amortisation schedule: the ratio
     * is then exactly {@code 1 - revised/original} by construction, so the test is about
     * the conclusion rule rather than about whether a projection happened to produce
     * 10.02%.
     */
    static FlowVector singleFlow(String amount) {
        return FlowVector.of(MODIFICATION_DATE, Money.INR, List.of(
            CashFlow.of(MODIFICATION_DATE.plusMonths(1), 1, Money.inr(amount), FlowKind.PRINCIPAL)));
    }
}
