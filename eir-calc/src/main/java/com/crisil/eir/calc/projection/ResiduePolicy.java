package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What to do with the residue a rounded instalment leaves on the contractual leg
 * (calculation specification section 5.7).
 *
 * <p>The residue is real, not a defect. The true annuity on the Case 1 loan is
 * 47,073.472223 and the billed instalment is 47,073.47; over 24 periods that
 * 0.002223 monthly shortfall compounds to 0.059969 of terminal balance on the
 * contractual leg. Every real lender resolves this somewhere, and the engine's
 * job is to follow whatever the core banking system does rather than invent its
 * own answer — which is why {@link #LMS_AUTHORITATIVE} is preferred and the plug
 * policies exist only for instruments where no billed schedule is available.
 *
 * <p>Two rules hold under every policy. The residue lands on the contractual leg
 * only and never touches the EIR leg, because the EIR was solved against the
 * billed flows and amortises to exactly zero by construction (invariant TR-1).
 * And a residue is never resolved by tolerance: a tolerance hides exactly the
 * class of defect this engine exists to prevent (ADR-0002), so where a difference
 * exists a rule accounts for it.
 *
 * <p>A plug is rounded once, at presentation scale, so the published leg closes
 * at the scale it is published in. It cannot close at working precision, because
 * an instalment is only billable to the paise — that remaining sub-paise
 * difference is unpresentable rather than untreated.
 */
public enum ResiduePolicy {

    /**
     * Consume the schedule the lending system actually billed; derive nothing and
     * plug nothing.
     *
     * <p>Strongly preferred in production (FR-102, ADR-0004). {@link #apply}
     * returns the instalments untouched: under this policy the residue is
     * whatever the core banking system billed, and an engine-side adjustment
     * would create the reconciliation break the policy exists to avoid. A
     * <em>derived</em> schedule carrying this policy is a misconfiguration, and
     * leaving the residue visible is the safe failure — it shows up in the
     * unamortised-fee column instead of being quietly absorbed.
     */
    LMS_AUTHORITATIVE,

    /** The last instalment absorbs the residue. On Case 1 the final EMI becomes 47,073.53. */
    FINAL_PERIOD_PLUG,

    /**
     * The first instalment absorbs it, discounted back from the final period at
     * the contractual rate so that the leg still closes at zero.
     */
    FIRST_PERIOD_PLUG,

    /** Spread over the final {@code n} instalments, each increment equal in nominal terms. */
    SPREAD_LAST_N;

    /**
     * Applies this policy to a derived instalment leg.
     *
     * @param instalments    the future instalment flows in ascending period order;
     *                       the plug attaches to positive (receipt) flows only, so
     *                       a tranche drawdown sitting in the same leg is never
     *                       adjusted
     * @param terminalResidue the contractual leg's closing balance at working
     *                       precision — positive where the billed instalment was
     *                       rounded down and the lender under-collects
     * @param periodicRate   the contractual periodic rate, used to move a residue
     *                       between periods; a residue is a dated amount and
     *                       moving it undiscounted would change the schedule's
     *                       economics
     * @param spreadPeriods  how many trailing instalments share the residue under
     *                       {@link #SPREAD_LAST_N}; ignored by the other policies
     * @return a new list; the input is not modified
     */
    public List<CashFlow> apply(
        List<CashFlow> instalments,
        Money terminalResidue,
        BigDecimal periodicRate,
        int spreadPeriods) {

        Objects.requireNonNull(instalments, "instalments");
        Objects.requireNonNull(terminalResidue, "terminalResidue");
        Objects.requireNonNull(periodicRate, "periodicRate");
        if (this == LMS_AUTHORITATIVE || terminalResidue.isZero() || instalments.isEmpty()) {
            return List.copyOf(instalments);
        }
        List<Integer> receipts = receiptPositions(instalments);
        if (receipts.isEmpty()) {
            return List.copyOf(instalments);
        }
        int lastPeriod = instalments.get(receipts.get(receipts.size() - 1)).periodIndex();
        List<CashFlow> adjusted = new ArrayList<>(instalments);
        switch (this) {
            case FINAL_PERIOD_PLUG -> {
                int position = receipts.get(receipts.size() - 1);
                adjusted.set(position, plus(adjusted.get(position), terminalResidue));
            }
            case FIRST_PERIOD_PLUG -> {
                int position = receipts.get(0);
                CashFlow first = adjusted.get(position);
                BigDecimal shift = BigDecimal.valueOf((long) lastPeriod - first.periodIndex());
                Money atFirstPeriod = terminalResidue.times(Precision.discountFactor(periodicRate, shift));
                adjusted.set(position, plus(first, atFirstPeriod));
            }
            case SPREAD_LAST_N -> {
                if (spreadPeriods < 1 || spreadPeriods > receipts.size()) {
                    throw new IllegalArgumentException(
                        "spreadPeriods must be between 1 and " + receipts.size() + ", got " + spreadPeriods);
                }
                List<Integer> tail = receipts.subList(receipts.size() - spreadPeriods, receipts.size());
                BigDecimal accumulation = BigDecimal.ZERO;
                for (int position : tail) {
                    int exponent = lastPeriod - instalments.get(position).periodIndex();
                    accumulation = accumulation.add(Precision.onePlusPow(periodicRate, exponent), Precision.WORKING);
                }
                Money increment = terminalResidue.dividedBy(accumulation);
                for (int position : tail) {
                    adjusted.set(position, plus(adjusted.get(position), increment));
                }
            }
            default -> throw new IllegalStateException("unhandled residue policy " + this);
        }
        return List.copyOf(adjusted);
    }

    /** Positions of the receipt flows, in ascending period order. */
    private static List<Integer> receiptPositions(List<CashFlow> instalments) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < instalments.size(); index++) {
            if (instalments.get(index).amount().isPositive()) {
                positions.add(index);
            }
        }
        return positions;
    }

    /** The plug is added at presentation scale, because an instalment is billed in paise. */
    private static CashFlow plus(CashFlow flow, Money adjustment) {
        Money billed = flow.amount().plus(adjustment).atPresentationScale();
        return new CashFlow(flow.date(), flow.periodIndex(), billed, flow.kind(), flow.contingent());
    }
}
