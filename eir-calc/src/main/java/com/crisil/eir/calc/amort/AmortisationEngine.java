package com.crisil.eir.calc.amort;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The amortised-cost roll-forward (calculation specification section 5.1):
 *
 * <pre>
 *   interest_p = opening_p x ((1 + r)^dtau_p - 1)
 *   closing_p  = opening_p + interest_p - cash_p
 * </pre>
 *
 * <p>Under ACPIR, Stage 1 and Stage 2 interest continues on the <em>gross</em>
 * carrying amount with provisions presented separately rather than netted, so
 * this is the roll-forward for every performing exposure. Stage 3 does not get a
 * different roll-forward — it gets a different recognition decision on the same
 * figures ({@link Stage3Decomposition}).
 *
 * <p><strong>Why the exponent.</strong> The accrual for a period is
 * {@code (1+r)^dtau - 1} where {@code dtau} is the increase in the discounting
 * exponent since the previous boundary, taken from the same
 * {@link TimeConvention} the rate was solved under. For
 * {@link TimeConvention.PeriodicIndex} every {@code dtau} is exactly 1 and this
 * collapses to {@code opening x r}, the literal form in the specification. For
 * {@link TimeConvention.ActualDate} it is the compound fractional accretion of
 * section 5.3. Deriving the accrual from the convention rather than assuming
 * whole periods is what makes the roll-forward the exact inverse of the discount
 * that produced the rate, and that is the whole content of invariant TR-1: the
 * terminal balance is zero because the rate was solved against this same vector,
 * so a non-zero terminal balance means the solve and the roll-forward disagreed
 * about the flows, not that the arithmetic slipped.
 *
 * <p><strong>The projector declares the boundaries.</strong> There is one row per
 * accrual boundary and a boundary is where the vector says one is, so a period
 * carrying no flow at all is folded into the next boundary and accretes over
 * {@code dtau > 1}. That is arithmetically identical to accruing it separately —
 * compounding is associative — and it is exactly the moratorium treatment of
 * section 5.5: with no cash to apply, the interest stays in the balance, which is
 * capitalisation, and the engine needs no moratorium mode to do it. A caller that
 * wants the moratorium visible as a row per accounting period, which a period
 * close generally does, puts a zero-amount flow on each moratorium period date.
 * Zero-amount flows change no present value and no balance; they declare a
 * boundary. Choosing granularity that way keeps it with the projector, which is
 * the only stage that knows the accounting calendar.
 *
 * <p><strong>Which leg.</strong> Three entry points, because the terminal
 * residue means different things on each (section 5.7). On the EIR leg a residue
 * is a defect and TR-1 is asserted. On the contractual leg it is real and
 * expected — reference case 1 bills 47,073.47 against a true annuity payment of
 * 47,073.472223, and that 0.002223 monthly shortfall compounds to 0.059969 over
 * 24 periods — so no terminal assertion is made and the residue is reported.
 * A segment run covers part of a life, between events, where the terminal balance
 * is simply the balance at the segment end.
 */
public final class AmortisationEngine {

    private AmortisationEngine() {
    }

    /**
     * Rolls the EIR leg forward over a full, event-free life and asserts TR-1.
     *
     * <p>Use this only where the vector is the whole remaining life at the rate
     * that was solved against it. For a partial run use {@link #segment}, which
     * makes no claim about the terminal balance.
     */
    public static AmortisationResult eirLeg(
        Money openingGca, Rate rate, FlowVector vector, TimeConvention convention) {
        return roll(openingGca, rate, vector, convention, true);
    }

    /**
     * Rolls the EIR leg forward with the opening carrying amount taken from the
     * vector's own inception flows.
     *
     * <p>The initial gross carrying amount <em>is</em> the net cash flow at
     * inception (invariant IC-1), so deriving it here removes the chance of
     * rolling forward from a figure the rate was not solved to — for reference
     * case 1, 995,000.00 rather than either the 1,000,000.00 advanced or the
     * 985,000.00 the borrower received.
     */
    public static AmortisationResult eirLeg(Rate rate, FlowVector vector, TimeConvention convention) {
        return roll(Discounting.netAtInception(vector).negate(), rate, vector, convention, true);
    }

    /**
     * Rolls the contractual leg forward at the contractual rate from par.
     *
     * <p>No terminal assertion: the contractual leg carries the billed-schedule
     * residue and that residue is real. Where the entity's rounding policy is
     * {@code LMS_AUTHORITATIVE} the vector is the schedule the core banking
     * system actually billed, which is the only way the contractual leg
     * reconciles to the CBS every month (FR-804).
     */
    public static AmortisationResult contractualLeg(
        Money principalAdvanced, Rate contractualRate, FlowVector billedFlows, TimeConvention convention) {
        return roll(principalAdvanced, contractualRate, billedFlows, convention, false);
    }

    /**
     * Rolls forward over part of a life — between two events, or up to an event
     * date — with no terminal assertion.
     */
    public static AmortisationResult segment(
        Money openingGca, Rate rate, FlowVector vector, TimeConvention convention) {
        return roll(openingGca, rate, vector, convention, false);
    }

    private static AmortisationResult roll(
        Money openingGca,
        Rate rate,
        FlowVector vector,
        TimeConvention convention,
        boolean assertTerminalZero) {
        Objects.requireNonNull(openingGca, "openingGca");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(convention, "convention");
        if (!openingGca.currency().equals(vector.currency())) {
            throw new IllegalArgumentException(
                "opening carrying amount is " + openingGca.currency().getCurrencyCode()
                    + " but the flow vector is " + vector.currency().getCurrencyCode());
        }
        if (rate.periodsPerYear() != convention.periodsPerYear()) {
            throw new IllegalArgumentException(
                "rate compounds " + rate.periodsPerYear() + " times a year but convention "
                    + convention.label() + " implies " + convention.periodsPerYear()
                    + " — an annual effective rate rolled on period ordinals, or the reverse");
        }

        BigDecimal periodic = rate.periodic();
        List<AmortisationRow> rows = new ArrayList<>();
        Money balance = openingGca;
        Money totalInterest = Money.zero(vector.currency());
        Money totalCash = Money.zero(vector.currency());
        BigDecimal previousTau = BigDecimal.ZERO;

        for (Boundary boundary : boundaries(vector, convention)) {
            BigDecimal exponent = boundary.tau().subtract(previousTau, Precision.WORKING);
            if (exponent.signum() <= 0) {
                throw new IllegalArgumentException(
                    "flow vector is not in increasing time order under " + convention.label()
                        + ": period " + boundary.period() + " at tau " + boundary.tau().toPlainString()
                        + " does not follow " + previousTau.toPlainString());
            }
            if (boundary.period() < 1) {
                throw new IllegalArgumentException(
                    "flow dated " + boundary.date() + " is after the anchor but carries period index 0;"
                        + " a post-inception flow must carry its 1-based period ordinal");
            }
            Money interest = balance.times(accretion(periodic, exponent));
            AmortisationRow row = AmortisationRow.of(
                boundary.period(), boundary.date(), exponent, balance, interest, boundary.cash());
            rows.add(row);
            totalInterest = totalInterest.plus(interest);
            totalCash = totalCash.plus(boundary.cash());
            balance = row.closingGca();
            previousTau = boundary.tau();
        }

        List<InvariantResult> invariants = new ArrayList<>();
        if (assertTerminalZero) {
            invariants.add(InvariantResult.ofMoney(
                InvariantId.TR_1,
                "terminal EIR-leg carrying amount after " + rows.size() + " periods",
                Money.zero(vector.currency()),
                balance));
        }
        return new AmortisationResult(rows, totalInterest, totalCash, balance, List.copyOf(invariants));
    }

    /**
     * {@code (1 + r)^dtau - 1}.
     *
     * <p>For a whole period this is exactly {@code r}: {@code (1+r)^1} is computed
     * on the exact integer path and the subtraction returns the rate unchanged, so
     * a whole-period accrual is {@code opening x r} with nothing lost to the
     * fractional-power routine.
     */
    static BigDecimal accretion(BigDecimal periodicRate, BigDecimal exponent) {
        return Precision.onePlusPow(periodicRate, exponent).subtract(BigDecimal.ONE);
    }

    /**
     * Groups the future flows into accrual boundaries — one per distinct
     * discounting exponent.
     *
     * <p>Grouping on tau rather than on period ordinal is deliberate. Two flows
     * can share a date and differ in ordinal: the B5.4.4 shortcut puts an
     * instalment and a synthetic notional redemption on the same reset date. Under
     * actual dating they discount identically, so they must accrete identically —
     * one boundary, both amounts — and under periodic indexing their ordinals
     * differ and they are separate boundaries with a zero-length accrual between
     * them. Either way the roll-forward reverses exactly the discount the solver
     * applied, which is what TR-1 tests.
     */
    private static List<Boundary> boundaries(FlowVector vector, TimeConvention convention) {
        LocalDate anchor = vector.anchorDate();
        List<Boundary> boundaries = new ArrayList<>();
        BigDecimal currentTau = null;
        int period = 0;
        LocalDate date = anchor;
        Money cash = Money.zero(vector.currency());
        for (CashFlow flow : vector.future()) {
            BigDecimal tau = convention.tau(anchor, flow);
            if (currentTau != null && tau.compareTo(currentTau) != 0) {
                boundaries.add(new Boundary(period, date, currentTau, cash));
                cash = Money.zero(vector.currency());
                period = 0;
                date = anchor;
            }
            currentTau = tau;
            period = Math.max(period, flow.periodIndex());
            date = flow.date().isAfter(date) ? flow.date() : date;
            cash = cash.plus(flow.amount());
        }
        if (currentTau != null) {
            boundaries.add(new Boundary(period, date, currentTau, cash));
        }
        return boundaries;
    }

    /** One accrual boundary: the flows sharing a discounting exponent. */
    private record Boundary(int period, LocalDate date, BigDecimal tau, Money cash) {
    }
}
