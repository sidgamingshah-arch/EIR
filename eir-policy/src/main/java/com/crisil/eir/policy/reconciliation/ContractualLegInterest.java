package com.crisil.eir.policy.reconciliation;

import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * The engine's side of one C-14 line: the contractual interest one contract accrued in one period,
 * read out of the contractual leg the amortisation already produced.
 *
 * <p><b>This is a projection of the leg, not a second copy of it.</b> The contractual roll-forward
 * is {@link AmortisationResult} inside {@link TwoLegResult}, and it stays there — the two factories
 * below take the leg and a period ordinal and lift one figure out of it. Re-entering the figure by
 * hand, or accumulating a parallel per-period total alongside the leg, would give the
 * reconciliation something to tie to that is not the ledger: 04 § 2.8 makes the same argument for
 * making {@code period_balance.unamortised_fee} a generated column, and ADR-0004's whole structure
 * rests on the contractual leg being one artefact that both the CBS reconciliation and the fee
 * amortisation read.
 *
 * <p><b>Why {@link #fromTwoLegs} exists as a named route.</b> Reconciling the <em>EIR</em> leg to
 * the CBS instead of the contractual leg is the single most plausible mis-wiring in this control,
 * and it would not look like a bug. Both are interest figures on the same contract for the same
 * period, and they differ by that period's fee amortisation — reference case 1 period 2 gives
 * 9,986.87 on the EIR leg against 9,629.27 on the contractual leg, a difference of 357.61 that
 * arrives on every contract, every period, in the same direction, at a size somebody could
 * plausibly attribute to a rounding or timing convention. So the caller does not hand over a
 * {@link Money}; it hands over the two-leg result and this class picks the leg ADR-0004 names.
 *
 * @param contractId  the CBS account reference — {@code contract.source_system_ref} (V1 §
 *                    CONTRACT), whose {@code UNIQUE (entity_id, book_id, source_system_ref)}
 *                    constraint is what makes it usable as the join key on both sides
 * @param periodId    the accounting period, {@code YYYYMM} as the schema's
 *                    {@code CHECK (period_id BETWEEN 190001 AND 999912 ...)} defines it
 * @param contractualInterest interest accrued at the contractual rate in that period, at working
 *                    precision
 */
public record ContractualLegInterest(String contractId, int periodId, Money contractualInterest) {

    public ContractualLegInterest {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(contractualInterest, "contractualInterest");
        contractId = contractId.strip();
        if (contractId.isEmpty()) {
            throw new IllegalArgumentException("contractId must not be blank");
        }
        requirePeriodId(periodId);
    }

    /**
     * Reads the period's contractual interest out of a two-leg reconciliation.
     *
     * <p>The route a period close uses. It takes {@link TwoLegResult#contractualLeg()} and never
     * {@link TwoLegResult#eirLeg()} — see the class javadoc for what happens when those are
     * transposed.
     *
     * @param periodOrdinal the 1-based ordinal on the leg's own movement schedule, which is
     *                      <em>not</em> {@code periodId}: the leg counts from inception and the
     *                      accounting period is a calendar. Mapping between them is the caller's,
     *                      because only the caller knows the contract's first accrual boundary,
     *                      and it is stated as two arguments rather than derived so that a wrong
     *                      mapping is visible at the call site instead of buried in an offset.
     */
    public static ContractualLegInterest fromTwoLegs(
        String contractId, int periodId, TwoLegResult twoLeg, int periodOrdinal) {
        Objects.requireNonNull(twoLeg, "twoLeg");
        return fromContractualLeg(contractId, periodId, twoLeg.contractualLeg(), periodOrdinal);
    }

    /**
     * As {@link #fromTwoLegs}, from a bare contractual leg.
     *
     * <p>For the paths that hold a leg without a pairing — the suspense ledger's source figure
     * under Stage 3 suppression, and a contract whose EIR leg has not been rolled yet.
     *
     * <p>{@link AmortisationRow#interestAccrued()} rather than {@code eirInterest()}: the component
     * is named for the leg it usually describes, and on this leg the figure is contractual
     * interest. The neutral accessor exists precisely so that a reader of this line is not told the
     * wrong thing about which rate produced it.
     */
    /**
     * The period's contractual interest, at the scale it was billed.
     *
     * <p><b>The reduction to presentation scale happens here, and placing it took two wrong
     * answers first.</b> The two systems being reconciled differ structurally: the engine accrues
     * at working precision — 28 significant digits, per ADR-0002 — and the CBS bills in paise,
     * because paise is what a borrower can pay. On reference case 1 period 2 the engine's
     * contractual leg carries {@code 9,629.2653} and the CBS bills {@code 9,629.27}. Neither
     * system is wrong.
     *
     * <p>The first answer was to round the <em>residual</em>: subtract at working precision, then
     * reduce the difference before testing it for nil. 03 § 5.7 forbids precisely that — "residue
     * is never resolved by tolerance; a tolerance hides exactly the class of defect this system
     * exists to prevent" — and it scales with the book: two hundred accounts each genuinely out
     * by {@code 0.004} summed to {@code 0.80} of real unexplained difference and reported nil.
     *
     * <p>The second was to remove the rounding and leave the residue unexplained. That obeys the
     * letter of § 5.7 and misses its second sentence — "where a difference exists, <em>a rule
     * accounts for it</em>" — and it makes RC-1 permanently red on every contract in the book for
     * a difference nobody can act on. A control red by design is a control that gets suppressed.
     *
     * <p>The rule that accounts for it is that the borrower was billed in paise, and ADR-0004
     * makes the CBS the book of record for what was billed. So the reduction belongs where the
     * engine's accrual <em>becomes</em> a billed figure — on this input, once, named — and not
     * inside the comparison, where it would silently absorb anything smaller than a paise
     * whatever its cause. After it, {@code difference()} is exact arithmetic on two figures at the
     * same scale, and a genuine discrepancy of {@code 0.0047} is reported in full rather than
     * rounded away.
     *
     * <p>Note what this does <em>not</em> do: it does not reduce a figure a caller supplies
     * directly through the canonical constructor. A caller handing in a working-precision amount
     * is stating that the amount it holds is what was billed, and this class does not second-guess
     * that.
     */
    public static ContractualLegInterest fromContractualLeg(
        String contractId, int periodId, AmortisationResult contractualLeg, int periodOrdinal) {
        Objects.requireNonNull(contractualLeg, "contractualLeg");
        AmortisationRow row = contractualLeg.row(periodOrdinal);
        // Reduced to the scale the borrower was billed at, ONCE, here — at the boundary where an
        // engine accrual becomes a billed amount. See the note below on why this is the only
        // place it can go.
        return new ContractualLegInterest(
            contractId, periodId, row.interestAccrued().atPresentationScale());
    }

    /** The figure as it is published, at the currency's minor units. */
    public Money presentedContractualInterest() {
        return contractualInterest.atPresentationScale();
    }

    static void requirePeriodId(int periodId) {
        // The schema's own bound (V2, period_balance and journal_entry both carry
        // CHECK (period_id BETWEEN 190001 AND 999912 AND period_id % 100 BETWEEN 1 AND 12)).
        // Asserted here rather than trusted because a period id of 202713 joins nothing and would
        // present as a whole book missing from one side — a presence break with a typo behind it,
        // which is the most expensive kind to diagnose from a deviation figure.
        int month = periodId % 100;
        if (periodId < 190001 || periodId > 999912 || month < 1 || month > 12) {
            throw new IllegalArgumentException(
                "periodId must be YYYYMM between 190001 and 999912 with a month of 01..12, got "
                    + periodId);
        }
    }
}
