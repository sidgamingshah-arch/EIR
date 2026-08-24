package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Taking an asset off the books: the derecognition rows of the event-treatment table
 * (calculation specification 6.2).
 *
 * <p><strong>Why this class exists as engine code and not as a formula in a test.</strong>
 * Reference case 2's headline — 1,408.29 accelerated to P&amp;L at month 12 — was
 * asserted by computing {@code contractualBalance - eirBalance} inside the test
 * itself. That arithmetic is right, and as a merge gate it gated nothing: there was
 * no derecognition routine in the engine for it to disagree with, so the case could
 * not fail however the engine behaved. A reference case that computes its own answer
 * is a statement about arithmetic, not a test of an implementation.
 *
 * <p><strong>The failure mode it guards.</strong> On closure the borrower discharges
 * the <em>contractual</em> balance while the books carry the <em>amortised cost</em>,
 * and the gap is the deferred fee that would have been released over the remaining
 * periods. Written to income, it is the fee arriving early and the position closes
 * clean. Written to a suspense account — which is what a system that reconciles
 * balances but not their composition will do — it produces unexplained P&amp;L and a
 * fee balance that never clears. So the routine returns the gain and the extinguished
 * balance together, and asserts INV-4 in the process: the gain it computed <em>is</em>
 * the unamortised fee, measured as the leg difference rather than accumulated
 * separately, so a drifted fee balance cannot survive the event.
 *
 * <p>Stateless; every method is a pure function of its arguments.
 */
public final class DerecognitionCalculator {

    private DerecognitionCalculator() {
    }

    /**
     * Full prepayment or closure: the borrower discharges the contractual balance.
     *
     * <p>The consideration is the <em>contractual</em> outstanding balance, because
     * that is what a borrower owes and pays; the amount removed is the <em>EIR</em>
     * leg's balance, because that is what the asset is carried at. Passing the same
     * figure for both is the mistake this signature is shaped to make awkward — it
     * would report a nil gain on every contract that ever had a fee.
     *
     * <p>INV-4 is asserted rather than assumed. The gain is
     * {@code consideration - carryingAmount} and the unamortised fee is
     * {@code contractual - EIR}; on a closure those are the same subtraction, so the
     * assertion is a genuine cross-check only because the fee is reported
     * independently by {@link TwoLegResult#unamortisedFeeAt}. Where the two disagree,
     * the two legs have drifted apart and the fee balance was never what it claimed.
     *
     * <p>The consideration is the contractual balance and <em>only</em> that. A
     * prepayment penalty or closure charge, where one is chargeable at all, is
     * contingent income in the period the event occurs (FR-206) and is not part of this
     * measurement — folding it in here would breach the INV-4 assertion above, correctly,
     * because the gain would no longer be the unamortised fee. For floating-rate
     * individual loans RBI restricts foreclosure charges, so in the Indian retail book
     * there is usually no such amount at all.
     *
     * @param considerationReceived the contractual balance discharged
     * @param grossCarryingAmount   the EIR leg's closing balance immediately before
     *     settlement, after the period's accrual
     * @param reportedUnamortisedFee the fee balance the sub-ledger carries at that
     *     date, for the INV-4 cross-check
     */
    public static DerecognitionResult onClosure(
        Money considerationReceived, Money grossCarryingAmount, Money reportedUnamortisedFee) {
        Objects.requireNonNull(considerationReceived, "considerationReceived");
        Objects.requireNonNull(grossCarryingAmount, "grossCarryingAmount");
        Objects.requireNonNull(reportedUnamortisedFee, "reportedUnamortisedFee");
        requireSameCurrency(considerationReceived, grossCarryingAmount);
        requireSameCurrency(considerationReceived, reportedUnamortisedFee);

        Money gain = considerationReceived.minus(grossCarryingAmount);
        List<InvariantResult> invariants = new ArrayList<>();
        invariants.add(InvariantChecks.unamortisedFeeIsLegDifference(
            considerationReceived, grossCarryingAmount, reportedUnamortisedFee));
        return new DerecognitionResult(DerecognitionReason.CLOSURE, considerationReceived,
            grossCarryingAmount, gain, invariants);
    }

    /**
     * A modification substantial enough to fail the 3.2.3 test: the old asset is
     * derecognised and the new one recognised at fair value.
     *
     * <p>No INV-4 assertion. The consideration is a fair value rather than a
     * contractual balance, so {@code consideration - carryingAmount} is a
     * remeasurement and not the unamortised fee — asserting INV-4 here would fail on
     * every sound substantial modification. That the two events share a mechanic and
     * not an invariant is the reason they are separate methods rather than one method
     * with a flag.
     *
     * @param fairValueOfNewAsset  the replacement asset's fair value at the
     *     modification date, plus any cash passing
     * @param grossCarryingAmount  the derecognised asset's carrying amount
     */
    public static DerecognitionResult onSubstantialModification(
        Money fairValueOfNewAsset, Money grossCarryingAmount) {
        Objects.requireNonNull(fairValueOfNewAsset, "fairValueOfNewAsset");
        Objects.requireNonNull(grossCarryingAmount, "grossCarryingAmount");
        requireSameCurrency(fairValueOfNewAsset, grossCarryingAmount);

        return new DerecognitionResult(DerecognitionReason.SUBSTANTIAL_MODIFICATION,
            fairValueOfNewAsset, grossCarryingAmount,
            fairValueOfNewAsset.minus(grossCarryingAmount), List.of());
    }

    /**
     * Write-off: the balance is removed and the loss is not this engine's to
     * recognise.
     *
     * <p>{@link DerecognitionResult#affectsInterestIncome()} is false here. The loss
     * is measured against the allowance the impairment engine holds (ACPIR 6(12)),
     * and this engine consumes staging and allowances as versioned inputs rather than
     * holding them. Posting the figure to a fee or interest line would count the loss
     * twice — once here and once where the allowance was raised. The event is modelled
     * anyway, on the same mechanic as every other exit, so that the sub-ledger balance
     * is removed by one code path and SL-1 continues to tie.
     *
     * @param recoveries          amounts recovered at write-off; commonly zero
     * @param grossCarryingAmount the balance removed
     */
    public static DerecognitionResult onWriteOff(Money recoveries, Money grossCarryingAmount) {
        Objects.requireNonNull(recoveries, "recoveries");
        Objects.requireNonNull(grossCarryingAmount, "grossCarryingAmount");
        requireSameCurrency(recoveries, grossCarryingAmount);
        if (recoveries.isNegative()) {
            throw new IllegalArgumentException(
                "recoveries must not be negative, got " + recoveries);
        }

        return new DerecognitionResult(DerecognitionReason.WRITE_OFF, recoveries,
            grossCarryingAmount, recoveries.minus(grossCarryingAmount), List.of());
    }

    private static void requireSameCurrency(Money left, Money right) {
        if (!left.currency().equals(right.currency())) {
            throw new IllegalArgumentException("cannot derecognise across currencies: "
                + left.currency().getCurrencyCode() + " against "
                + right.currency().getCurrencyCode());
        }
    }
}
