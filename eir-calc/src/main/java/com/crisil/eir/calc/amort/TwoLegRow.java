package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One period of the two-leg reconciliation: the contractual leg and the EIR leg
 * side by side, with the fee amortisation that is the difference between them
 * (ADR-0004, reference case 1).
 *
 * <p>The delta-over-contractual-ledger posture in one row. The contractual leg is
 * what the core banking system billed and what the borrower owes; the EIR leg is
 * the accounting measurement. Neither is derived from the other, and the engine's
 * job is to explain the gap — so the gap is computed, never accumulated.
 *
 * <p><strong>Invariant INV-4 as code rather than as a check.</strong>
 * {@link #unamortisedFee()} is the difference between the two carrying amounts. It
 * is not a balance this row carries and reconciles afterwards, because an
 * accumulator can drift from the balances it claims to describe — one rounding
 * decision applied to the fee movement but not to the balances, one event that
 * moves a balance without touching the accumulator — and a derivation cannot
 * drift at all. Reference case 1 at month 12: {@code 529,815.61 - 528,407.32 =
 * 1,408.29}, the same figure reference case 2 accelerates on prepayment and
 * reference case 8 eliminates through the B5.4.4 shortcut.
 *
 * <p>{@link #feeAmortised()} is likewise derived, as EIR interest less contractual
 * interest. That it also equals the movement in {@link #unamortisedFee()} is not a
 * coincidence to be checked: both legs subtract the same cash, so the movement in
 * their difference is exactly the difference in their interest. Two derivations of
 * one number, and no third place for it to be stored wrongly.
 *
 * <p>Nothing on this row is stored twice. Both legs are held whole and every fee
 * figure is computed from them, at working precision and again at presentation
 * scale, so there is no state for a fee column to disagree with.
 *
 * @param period          the period ordinal, shared by both legs
 * @param eirLeg          the amortised-cost row
 * @param contractualLeg  the contractual row at the contractual rate from par
 */
public record TwoLegRow(
    int period,
    AmortisationRow eirLeg,
    AmortisationRow contractualLeg) {

    public TwoLegRow {
        Objects.requireNonNull(eirLeg, "eirLeg");
        Objects.requireNonNull(contractualLeg, "contractualLeg");
        if (eirLeg.period() != period || contractualLeg.period() != period) {
            throw new IllegalArgumentException("legs disagree on the period: " + eirLeg.period()
                + " and " + contractualLeg.period() + " against " + period);
        }
        if (!eirLeg.date().equals(contractualLeg.date())) {
            throw new IllegalArgumentException("legs disagree on the period end: " + eirLeg.date()
                + " and " + contractualLeg.date());
        }
        if (!eirLeg.cashReceived().equals(contractualLeg.cashReceived())) {
            throw new IllegalArgumentException(
                "legs disagree on cash received in period " + period + ": " + eirLeg.cashReceived()
                    + " and " + contractualLeg.cashReceived()
                    + " — both legs consume the same billed flows");
        }
    }

    public LocalDate date() {
        return eirLeg.date();
    }

    /** Interest recognised at the EIR. */
    public Money eirInterest() {
        return eirLeg.interestAccrued();
    }

    /** Interest billed at the contractual rate. */
    public Money contractualInterest() {
        return contractualLeg.interestAccrued();
    }

    /** The billed cash, identical on both legs. */
    public Money cashReceived() {
        return eirLeg.cashReceived();
    }

    /** Net integral fee recognised in the period: EIR interest less contractual interest. */
    public Money feeAmortised() {
        return eirInterest().minus(contractualInterest());
    }

    /** Closing amortised cost. */
    public Money eirCarryingAmount() {
        return eirLeg.closingGca();
    }

    /** Closing contractual balance — what the borrower owes. */
    public Money contractualCarryingAmount() {
        return contractualLeg.closingGca();
    }

    /** Unamortised fee carried forward, derived as the leg difference (INV-4). */
    public Money unamortisedFee() {
        return contractualCarryingAmount().minus(eirCarryingAmount());
    }

    /** Unamortised fee at the start of the period, on the same derivation. */
    public Money openingUnamortisedFee() {
        return contractualLeg.openingGca().minus(eirLeg.openingGca());
    }

    /**
     * The published unamortised fee: the derived balance, rounded once.
     *
     * <p>Rounded from the working difference rather than computed from the two
     * rounded balances, and the two are not always the same figure. At period 23 of
     * reference case 1 the working difference is 19.4966, which publishes as 19.50,
     * while the published balances differenced give {@code 46,607.46 - 46,587.95 =
     * 19.51}. The reference cases publish the former and so does this. Rounding
     * once, at the end, is the general rule (section 1.3); rounding the inputs and
     * then subtracting rounds twice.
     */
    public Money presentedUnamortisedFee() {
        return unamortisedFee().atPresentationScale();
    }

    /** The opening unamortised fee as published. */
    public Money presentedOpeningUnamortisedFee() {
        return openingUnamortisedFee().atPresentationScale();
    }

    /** Net integral fee recognised in the period, as published. */
    public Money presentedFeeAmortised() {
        return feeAmortised().atPresentationScale();
    }

    /**
     * The published movement in the unamortised fee balance.
     *
     * <p>The same number as {@link #feeAmortised()} before rounding — both legs
     * subtract the same cash, so the movement in their difference is the difference
     * in their interest — and up to a minor unit away from
     * {@link #presentedFeeAmortised()} once each side has been rounded
     * independently.
     */
    public Money presentedFeeMovement() {
        return presentedOpeningUnamortisedFee().minus(presentedUnamortisedFee());
    }

    /**
     * What the published fee column is missing to tie to the published balance
     * movement: {@link #presentedFeeAmortised()} less {@link #presentedFeeMovement()}.
     *
     * <p>Zero on most rows, a paise on the rest, and published as a rounding line
     * rather than absorbed into either column. Both figures are correct roundings of
     * the same working number; a schedule that quietly adjusts one to match the
     * other has stopped reporting what the ledger says.
     */
    public Money presentedFeeColumnResidue() {
        return presentedFeeAmortised().minus(presentedFeeMovement());
    }

    public Money presentedEirInterest() {
        return eirLeg.presentedEirInterest();
    }

    public Money presentedContractualInterest() {
        return contractualLeg.presentedEirInterest();
    }

    public Money presentedCashReceived() {
        return eirLeg.presentedCashReceived();
    }
}
