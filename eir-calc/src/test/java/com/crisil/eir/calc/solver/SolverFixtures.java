package com.crisil.eir.calc.solver;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The flow vectors the solver tests solve over, built by hand.
 *
 * <p>Hand-built on purpose. Projection is a separate stage (calculation
 * specification 3) and the solver never reads contract terms, so a solver test
 * that obtained its vector from a projector would fail on a projection defect and
 * report it as a solver defect. Reference case 7's instruments are stated in the
 * fixture as flows, and that is how they are stated here — the amounts below are
 * the fixture's own, not a projector's output.
 *
 * <p>Sign convention is the holder's: the advance is negative on the anchor date
 * and forms the target ({@code SolveRequest.atInception}), receipts are positive.
 */
final class SolverFixtures {

    /** Any date works — nothing in the calculation path reads a clock. */
    static final LocalDate ANCHOR = LocalDate.of(2026, 4, 1);

    /** Monthly periodic indexing: tau is the period ordinal, and the root is a monthly rate. */
    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /** 1% per month, the contractual rate on every reference-case term loan. */
    static final BigDecimal ONE_PERCENT_MONTHLY = new BigDecimal("0.01");

    private SolverFixtures() {
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * Case 7's calibration instrument: 1,000,000 advanced, one receipt at month 60
     * of {@code 1,000,000 * (1.01)^60}.
     *
     * <p>The receipt is carried at working precision rather than rounded to paise,
     * exactly as {@code BulletProjector} carries it, because rounding a single
     * terminal settlement to the paisa moves the solved rate in the twelfth decimal
     * place — and the twelfth decimal place is where this fixture is asserted.
     */
    static FlowVector zeroCouponBullet() {
        Money redemption = Money.of(
            bd("1000000").multiply(Precision.onePlusPow(ONE_PERCENT_MONTHLY, 60), Precision.WORKING),
            Money.INR);
        return FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(60), 60, redemption, FlowKind.PRINCIPAL)));
    }

    /**
     * Case 7's second stress instrument: 59 monthly receipts of 10,000, then
     * 1,010,000 at month 60, against 950,000 advanced.
     *
     * <p>The 1,010,000 is emitted as two flows sharing the date and the period
     * ordinal — the final coupon and the principal — because that is what the
     * contractual interest leg needs and what a projector produces. Discounting is
     * indifferent to the split, and asserting the rate over the split form is what
     * proves it.
     */
    static FlowVector interestOnlyBulletWithUpfrontFee() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT));
        flows.add(CashFlow.of(ANCHOR, 0, Money.inr("50000"), FlowKind.INTEGRAL_FEE_RECEIVED));
        for (int period = 1; period <= 60; period++) {
            flows.add(CashFlow.of(ANCHOR.plusMonths(period), period, Money.inr("10000.00"), FlowKind.INTEREST));
        }
        flows.add(CashFlow.of(ANCHOR.plusMonths(60), 60, Money.inr("1000000.00"), FlowKind.PRINCIPAL));
        return FlowVector.of(ANCHOR, Money.INR, flows);
    }

    /**
     * Case 7's deep-discount instrument: the 24 Case 1 EMIs of 47,073.47 against
     * 780,000 advanced — a fee above 20% of principal.
     */
    static FlowVector deepDiscount() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(ANCHOR, 0, Money.inr("-780000"), FlowKind.DISBURSEMENT));
        flows.addAll(monthlyReceipts("47073.47", 24));
        return FlowVector.of(ANCHOR, Money.INR, flows);
    }

    /** {@code count} monthly receipts of {@code amount}, periods 1..count. */
    static List<CashFlow> monthlyReceipts(String amount, int count) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= count; period++) {
            flows.add(CashFlow.of(
                ANCHOR.plusMonths(period), period, Money.inr(amount), FlowKind.COMBINED_EMI));
        }
        return flows;
    }

    /**
     * A stylised interim-drawdown profile: an advance, one large receipt, then a
     * further drawdown — the shape that gives the present-value function more than
     * one sign change and hence more than one mathematically valid IRR
     * (calculation specification 3.8, 4.4).
     *
     * <p>Three flows rather than a full project-finance schedule because the roots
     * then have closed forms and the test asserts a policy, not an arithmetic
     * coincidence. {@code (-1000, +5100, -4400)} has roots at exactly 0.1 and 3.0;
     * {@code (-1000, +2500, -1540)} at exactly 0.1 and 0.4.
     *
     * @param periodMonths months between the period ordinals, so the same amounts
     *     can be read as annual or as monthly periods
     */
    static FlowVector interimDrawdown(String advance, String receipt, String drawdown, int periodMonths) {
        return FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr(advance), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(periodMonths), 1, Money.inr(receipt), FlowKind.PRINCIPAL),
            CashFlow.of(ANCHOR.plusMonths(2L * periodMonths), 2, Money.inr(drawdown), FlowKind.DISBURSEMENT)));
    }

    /** Roots at exactly 0.1 and 3.0 per period. */
    static FlowVector rootsAtTenPercentAndThreeHundred(int periodMonths) {
        return interimDrawdown("-1000", "5100", "-4400", periodMonths);
    }

    /** Roots at exactly 0.1 and 0.4 per period. */
    static FlowVector rootsAtTenAndFortyPercent(int periodMonths) {
        return interimDrawdown("-1000", "2500", "-1540", periodMonths);
    }

    /**
     * A project-finance profile: 600,000 drawn at closure, a further 400,000 drawn
     * in month 6 after repayment has begun, and level instalments from month 4 to
     * month 24.
     *
     * <p>The flow signs change three times and the present-value function still
     * crosses the axis once. Multiple sign changes are a necessary condition for
     * multiple roots and not a sufficient one, which is why the solver decides on the
     * ladder scan rather than on the shape of the vector.
     */
    static FlowVector tranchedWithInterimDrawdown() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(ANCHOR, 0, Money.inr("-600000"), FlowKind.DISBURSEMENT));
        flows.add(CashFlow.of(ANCHOR.plusMonths(6), 6, Money.inr("-400000"), FlowKind.DISBURSEMENT));
        for (int period = 4; period <= 24; period++) {
            flows.add(CashFlow.of(
                ANCHOR.plusMonths(period), period, Money.inr("53371.03"), FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(ANCHOR, Money.INR, flows);
    }
}
