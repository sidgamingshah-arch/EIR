package com.crisil.eir.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The ordered flow sequence the solver consumes.
 *
 * <p>The solver never reads contract terms — projection is a separate,
 * independently testable stage, and this is the boundary between them. Flows are
 * held in date then period order, and the vector is immutable.
 */
public record FlowVector(LocalDate anchorDate, Currency currency, List<CashFlow> flows) {

    public FlowVector {
        Objects.requireNonNull(anchorDate, "anchorDate");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(flows, "flows");
        List<CashFlow> sorted = new ArrayList<>(flows);
        sorted.sort(Comparator.comparing(CashFlow::date).thenComparingInt(CashFlow::periodIndex));
        for (CashFlow flow : sorted) {
            if (!flow.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                    "flow currency " + flow.amount().currency().getCurrencyCode()
                        + " does not match vector currency " + currency.getCurrencyCode());
            }
            if (flow.date().isBefore(anchorDate)) {
                throw new IllegalArgumentException(
                    "flow dated " + flow.date() + " precedes anchor " + anchorDate);
            }
        }
        flows = List.copyOf(sorted);
    }

    public static FlowVector of(LocalDate anchorDate, Currency currency, List<CashFlow> flows) {
        return new FlowVector(anchorDate, currency, flows);
    }

    public boolean isEmpty() {
        return flows.isEmpty();
    }

    public int size() {
        return flows.size();
    }

    /** Flows strictly after the anchor — the ones that get discounted. */
    public List<CashFlow> future() {
        return flows.stream().filter(f -> f.date().isAfter(anchorDate)).toList();
    }

    /** Flows on the anchor date, which form part of the inception net amount. */
    public List<CashFlow> atInception() {
        return flows.stream().filter(f -> f.date().equals(anchorDate)).toList();
    }

    /** Sum of all flows, signed. */
    public Money total() {
        Money sum = Money.zero(currency);
        for (CashFlow flow : flows) {
            sum = sum.plus(flow.amount());
        }
        return sum;
    }

    /** The highest period ordinal present, or 0 for an empty vector. */
    public int maxPeriodIndex() {
        return flows.stream().mapToInt(CashFlow::periodIndex).max().orElse(0);
    }

    /**
     * Rejects any contingent flow.
     *
     * <p>Contingent fees are excluded from the inception projection regardless of
     * being contractually specified. Systems that sweep every contractual charge
     * into the projection overstate yield across the whole book, so this is
     * enforced at the boundary rather than left to the projector's discretion.
     *
     * @throws IllegalStateException if any flow is contingent
     */
    public FlowVector requireNoContingentFlows() {
        List<CashFlow> offending = flows.stream().filter(CashFlow::contingent).toList();
        if (!offending.isEmpty()) {
            throw new IllegalStateException(
                "contingent flows must not enter the EIR projection: " + offending);
        }
        return this;
    }

    /**
     * Whether the cheaper periodic-index time convention is exactly equivalent to
     * actual dating for this vector, at the given compounding frequency.
     *
     * <p>The precondition is <em>checked</em>, never assumed. A moratorium, a
     * mid-period disbursement, an actual-dated 28/31-day month pair, or a broken
     * first period all void it. Actual dating is the default and the fallback;
     * periodic indexing is an optimisation whose licence this method grants.
     *
     * @param periodsPerYear compounding periods per year; must divide 12
     */
    public boolean periodicIndexEligible(int periodsPerYear) {
        if (periodsPerYear < 1 || 12 % periodsPerYear != 0) {
            return false;
        }
        int monthsPerPeriod = 12 / periodsPerYear;
        List<CashFlow> future = future();
        if (future.isEmpty()) {
            return false;
        }
        int max = maxPeriodIndex();
        boolean[] seen = new boolean[max + 1];
        for (CashFlow flow : future) {
            int index = flow.periodIndex();
            if (index < 1) {
                return false;
            }
            seen[index] = true;
            if (!flow.date().equals(anchorDate.plusMonths((long) monthsPerPeriod * index))) {
                return false;
            }
        }
        for (int index = 1; index <= max; index++) {
            if (!seen[index]) {
                return false;
            }
        }
        return true;
    }
}
