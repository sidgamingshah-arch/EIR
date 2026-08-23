package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The two-leg reconciliation over a life: the contractual ledger, the EIR ledger,
 * and the fee amortisation that reconciles them (ADR-0004, reference case 1).
 *
 * <p>This is the artefact the controls tie to. The contractual leg reconciles to
 * the core banking system with zero unexplained difference (FR-804); the EIR leg
 * is the accounting measurement; the difference between their carrying amounts is
 * the unamortised fee, and the difference between their interest is the fee
 * recognised. Everything published here is one of those three things, and nothing
 * is accumulated alongside them.
 *
 * <p>The legs are held whole rather than flattened into totals, so the totals
 * cannot say something the rows do not.
 *
 * @param rows           the paired movement schedule
 * @param eirLeg         the amortised-cost roll-forward, opening below par by the net fee
 * @param contractualLeg the contractual roll-forward from par at the contractual rate
 * @param netIntegralFee fees received less integral costs paid; positive is income
 * @param totalCatchUps  catch-up adjustments recognised over the life, for INV-1
 * @param invariants     INV-1 to INV-4, plus whatever the legs asserted
 */
public record TwoLegResult(
    List<TwoLegRow> rows,
    AmortisationResult eirLeg,
    AmortisationResult contractualLeg,
    Money netIntegralFee,
    Money totalCatchUps,
    List<InvariantResult> invariants) {

    public TwoLegResult {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(eirLeg, "eirLeg");
        Objects.requireNonNull(contractualLeg, "contractualLeg");
        Objects.requireNonNull(netIntegralFee, "netIntegralFee");
        Objects.requireNonNull(totalCatchUps, "totalCatchUps");
        Objects.requireNonNull(invariants, "invariants");
        rows = List.copyOf(rows);
        invariants = List.copyOf(invariants);
    }

    /**
     * Pairs two completed legs and asserts the reconciliation.
     *
     * <p>The legs must have been rolled over the same billed flows — same periods,
     * same dates, same cash — and that is checked rather than assumed, because
     * pairing an EIR leg against a contractual leg built from a different schedule
     * produces a fee profile that looks plausible and reconciles to nothing.
     *
     * @param eirLeg           from {@link AmortisationEngine#eirLeg}
     * @param contractualLeg   from {@link AmortisationEngine#contractualLeg}
     * @param eir              the solved EIR, for INV-2
     * @param contractualRate  the contractual rate, for INV-2
     * @param netIntegralFee   the classified net integral fee, for INV-1 and INV-4
     */
    public static TwoLegResult reconcile(
        AmortisationResult eirLeg,
        AmortisationResult contractualLeg,
        Rate eir,
        Rate contractualRate,
        Money netIntegralFee) {
        return reconcile(eirLeg, contractualLeg, eir, contractualRate, netIntegralFee,
            Money.zero(netIntegralFee.currency()));
    }

    /** As {@link #reconcile}, for a life that included catch-up restatements. */
    public static TwoLegResult reconcile(
        AmortisationResult eirLeg,
        AmortisationResult contractualLeg,
        Rate eir,
        Rate contractualRate,
        Money netIntegralFee,
        Money totalCatchUps) {
        Objects.requireNonNull(eirLeg, "eirLeg");
        Objects.requireNonNull(contractualLeg, "contractualLeg");
        Objects.requireNonNull(netIntegralFee, "netIntegralFee");
        Objects.requireNonNull(totalCatchUps, "totalCatchUps");
        if (eirLeg.periods() != contractualLeg.periods()) {
            throw new IllegalArgumentException("legs cover different numbers of periods: "
                + eirLeg.periods() + " and " + contractualLeg.periods()
                + ". INV-4 pairs the two legs row by row, so they have to span the same periods."
                + " The usual cause is a behaviourally truncated expected life: the EIR leg ends at"
                + " expected life and the contractual leg runs to contractual maturity. Roll the"
                + " contractual leg over the expected vector as well — it carries the expected"
                + " prepayment that settles the exposure at that date, so both legs then end"
                + " together and the fee is fully amortised by expected life, which is what"
                + " truncating the life asserted. Reconciling past that date compares an EIR leg"
                + " that assumed the asset was gone against a contractual leg that assumes it is"
                + " still there.");
        }

        List<TwoLegRow> rows = new ArrayList<>();
        for (int index = 0; index < eirLeg.periods(); index++) {
            AmortisationRow eirRow = eirLeg.rows().get(index);
            AmortisationRow contractualRow = contractualLeg.rows().get(index);
            rows.add(new TwoLegRow(eirRow.period(), eirRow, contractualRow));
        }

        Money principalAdvanced = contractualLeg.openingGca();
        Money contractualInterestBilled = eirLeg.totalCash().minus(principalAdvanced);
        List<InvariantResult> invariants = new ArrayList<>();
        invariants.add(InvariantChecks.lifetimeInterest(
            eirLeg.totalInterest(), contractualInterestBilled, netIntegralFee, totalCatchUps));
        invariants.add(InvariantChecks.feeSignOrdering(eir, contractualRate, netIntegralFee));
        invariants.add(InvariantChecks.billedCashReconciliation(
            contractualLeg.totalCash(),
            principalAdvanced,
            contractualLeg.totalInterest(),
            contractualLeg.terminalBalance()));
        invariants.add(InvariantChecks.unamortisedFeeIsLegDifference(
            principalAdvanced, eirLeg.openingGca(), netIntegralFee));
        List<InvariantResult> all = InvariantChecks.merge(
            InvariantChecks.merge(eirLeg.invariants(), contractualLeg.invariants()), invariants);
        return new TwoLegResult(rows, eirLeg, contractualLeg, netIntegralFee, totalCatchUps, all);
    }

    /** Par amount advanced — the contractual leg's opening balance. */
    public Money principalAdvanced() {
        return contractualLeg.openingGca();
    }

    /** Initial gross carrying amount — par less the net integral fee. */
    public Money openingEirCarryingAmount() {
        return eirLeg.openingGca();
    }

    /** Lifetime interest recognised at the EIR. Reference case 1: 134,763.28. */
    public Money totalEirInterest() {
        return eirLeg.totalInterest();
    }

    /** Lifetime contractual interest as accrued on the leg, residue included. */
    public Money totalContractualInterestAccrued() {
        return contractualLeg.totalInterest();
    }

    /**
     * Lifetime contractual interest on the <em>billed</em> basis: cash received
     * less principal advanced. Reference case 1: 129,763.28.
     *
     * <p>The billed basis and the accrued basis differ by whatever the billed
     * schedule left uncollected — 0.06 in reference case 1 — and INV-1 is stated on
     * the billed basis because that is the figure the cash and the customer
     * statements agree on.
     */
    public Money contractualInterestBilled() {
        return eirLeg.totalCash().minus(principalAdvanced());
    }

    public Money totalCashReceived() {
        return eirLeg.totalCash();
    }

    /**
     * Net fee recognised over the life on the billed basis: lifetime EIR interest
     * less contractual interest billed. Reference case 1: 5,000.00, equal to the
     * net integral fee, which is invariant INV-1 read as a subtraction.
     */
    public Money netFeeRecognised() {
        return eirLeg.totalInterest().minus(contractualInterestBilled());
    }

    /**
     * The same figure on the accrued basis: lifetime EIR interest less contractual
     * interest as accrued on the leg.
     *
     * <p>Smaller than {@link #netFeeRecognised()} by the uncollected residue — 4,999.94
     * against 5,000.00 in reference case 1 — because the contractual leg accrued 0.06
     * of interest the billed schedule never collected. Both figures are right; they
     * answer different questions, and a reconciliation that mixes them is short by
     * exactly the residue.
     */
    public Money totalFeeAmortisedOnAccruedBasis() {
        return eirLeg.totalInterest().minus(contractualLeg.totalInterest());
    }

    /** What the billed schedule left uncollected on the contractual leg. */
    public Money contractualResidue() {
        return contractualLeg.terminalBalance();
    }

    /**
     * Unamortised fee at the end of a period, derived from the two legs.
     *
     * @throws IllegalArgumentException if no row carries that period ordinal
     */
    public Money unamortisedFeeAt(int period) {
        for (TwoLegRow row : rows) {
            if (row.period() == period) {
                return row.unamortisedFee();
            }
        }
        throw new IllegalArgumentException("no row for period " + period);
    }

    /** As {@link #unamortisedFeeAt}, at presentation scale. Month 12 of case 1: 1,408.29. */
    public Money presentedUnamortisedFeeAt(int period) {
        for (TwoLegRow row : rows) {
            if (row.period() == period) {
                return row.presentedUnamortisedFee();
            }
        }
        throw new IllegalArgumentException("no row for period " + period);
    }

    /**
     * What a straight-line accretion of the same fee would recognise each period.
     *
     * <p>Reported for comparison, never used: reference case 1 recognises 369.38 in
     * month 1 and 19.44 in month 24 against a straight line of 208.33, and the
     * front-loading is the whole purpose of the method. A Tier 3 population may
     * substitute the straight line, but only against a current equivalence test
     * (invariant TG-1, reference case 9).
     */
    public Money straightLineFeePerPeriod() {
        if (rows.isEmpty()) {
            return Money.zero(netIntegralFee.currency());
        }
        return netIntegralFee.dividedBy(BigDecimal.valueOf(rows.size()));
    }

    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    public boolean isClean() {
        return breaches().isEmpty();
    }

    /**
     * @throws InvariantBreachException on the first breach
     */
    public TwoLegResult orThrow() {
        for (InvariantResult result : invariants) {
            result.orThrow();
        }
        return this;
    }
}
