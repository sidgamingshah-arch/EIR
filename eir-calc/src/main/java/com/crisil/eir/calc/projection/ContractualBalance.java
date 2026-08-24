package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The contractual leg's balance after a given period.
 *
 * <p>Two consumers need it and both need the same roll: the B5.4.4 shortcut,
 * whose synthetic redemption is the <em>contractual</em> balance at the next reset
 * date, and behavioural truncation, whose expected prepayment is the contractual
 * balance at the end of expected life. Case 8's 529,815.61 at month 12 and
 * Case 1's INV-4 figure at the same date are the same number by construction, and
 * that cross-check only holds if one routine produces both.
 *
 * <p><b>The roll is at working precision and presented once.</b> Rolling a
 * balance that is re-rounded to paise every period drifts: on the Case 1 loan the
 * re-rounded roll gives 529,815.59 at month 12 and a terminal residue of 0.03,
 * against the golden 529,815.61 and 0.059969. The published movement schedule
 * still adds up in presented components — that is a presentation obligation
 * discharged where the schedule is rendered, not licence to round the carry.
 *
 * <p>Synthetic flows are skipped. A notional redemption or an expected prepayment
 * is an accounting construct, not cash, so it does not reduce a contractual
 * balance; without this a vector that already carries one would roll to zero and
 * silently answer the wrong question.
 *
 * <p><b>The two consumers present the result differently, and deliberately.</b> The
 * B5.4.4 notional redemption takes {@link #after} at working precision; behavioural
 * truncation takes {@link #presentedAfter}. The line between them is whether the
 * flow is a BILLED amount or a PROJECTED one.
 *
 * <p>Neither of the two synthetic terminal flows is billed. A notional redemption
 * models an instrument that does not actually mature at the reset — nobody is ever
 * billed it and no settlement occurs. An expected prepayment models a settlement
 * that may occur, but the <em>amount</em> is a projected balance, not a figure any
 * borrower has been charged. Both are intermediates entering a solve, so
 * calculation specification 1.2 applies to both and {@link #after} is the right
 * accessor for both.
 *
 * <p>An earlier version of this note argued the other way for the expected
 * prepayment, on the ground that a prepayment is a cash settlement, and cited
 * reference case 6's expected receipt as support. That citation was wrong and is
 * worth recording as an error rather than quietly dropping: case 6's receipt is 80%
 * of an instalment — a statistical expectation of partial collection that no single
 * payment ever equals — so it is not an instance of the settlement category at all.
 * The case itself is explicit that the receipt is carried unrounded. Two structurally
 * identical synthetic terminal flows following two different rounding rules was the
 * tell.
 *
 * <p>The distinction is worth stating because it is invisible at presentation scale —
 * both read 529,815.61 at month 12 on the Case 1 loan, which is the cross-check
 * against Case 1's INV-4 figure — and because collapsing it in either direction
 * costs something real. Presenting the notional redemption moves Case 8's published
 * monthly EIR by five units in its eighth decimal place; unpresenting the expected
 * prepayment would change the solve leg of every behaviourally truncated contract
 * with no reference case asking for it.
 */
public final class ContractualBalance {

    private ContractualBalance() {
    }

    /**
     * Rolls {@code openingPrincipal} forward {@code throughPeriod} periods,
     * accruing interest and then applying that period's cash.
     *
     * <p>Order within a period is accrue-then-apply, which is the deterministic
     * ordering the specification fixes for every event in a period. Signed
     * arithmetic handles a drawdown without a special case: a negative flow is an
     * outflow to the borrower and increases the balance.
     *
     * @param openingPrincipal balance at inception — the principal, not the gross
     *                         carrying amount, because the contractual leg knows
     *                         nothing about integral fees
     * @param periodicRate     the contractual periodic rate
     * @param vector           the contractual vector; only its future flows count
     * @param throughPeriod    the period ordinal to roll to; 0 returns the opening
     *                         balance
     * @return the balance at working precision
     */
    public static Money after(
        Money openingPrincipal,
        BigDecimal periodicRate,
        FlowVector vector,
        int throughPeriod) {

        Objects.requireNonNull(openingPrincipal, "openingPrincipal");
        Objects.requireNonNull(periodicRate, "periodicRate");
        Objects.requireNonNull(vector, "vector");
        if (throughPeriod < 0) {
            throw new IllegalArgumentException("throughPeriod must be non-negative, got " + throughPeriod);
        }
        Money[] cash = cashByPeriod(vector, throughPeriod);
        Money balance = openingPrincipal;
        for (int period = 1; period <= throughPeriod; period++) {
            balance = balance.plus(balance.times(periodicRate));
            if (cash[period] != null) {
                balance = balance.minus(cash[period]);
            }
        }
        return balance;
    }

    /**
     * {@link #after} at presentation scale — for a flow that models a cash
     * settlement, such as behavioural truncation's expected prepayment. A flow that
     * models no cash event, such as the B5.4.4 notional redemption, takes
     * {@link #after} instead; see the class comment for where that line falls.
     */
    public static Money presentedAfter(
        Money openingPrincipal,
        BigDecimal periodicRate,
        FlowVector vector,
        int throughPeriod) {

        return after(openingPrincipal, periodicRate, vector, throughPeriod).atPresentationScale();
    }

    private static Money[] cashByPeriod(FlowVector vector, int throughPeriod) {
        Money[] cash = new Money[throughPeriod + 1];
        for (CashFlow flow : vector.future()) {
            int period = flow.periodIndex();
            if (period < 1 || period > throughPeriod || isSynthetic(flow.kind())) {
                continue;
            }
            cash[period] = cash[period] == null ? flow.amount() : cash[period].plus(flow.amount());
        }
        return cash;
    }

    private static boolean isSynthetic(FlowKind kind) {
        return kind == FlowKind.NOTIONAL_REDEMPTION || kind == FlowKind.EXPECTED_PREPAYMENT;
    }
}
