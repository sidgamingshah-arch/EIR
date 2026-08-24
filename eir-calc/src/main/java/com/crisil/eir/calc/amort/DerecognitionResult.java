package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of taking a financial asset off the books.
 *
 * <p>Three events reach here and they differ in what the P&amp;L line <em>means</em>,
 * which is why {@link #reason} travels with the numbers rather than being inferred
 * from their signs. On a closure the gain is the deferred fee arriving early; on a
 * substantial modification it is the difference between what the old asset was
 * carried at and what the new one is worth; on a write-off there is no gain at all in
 * this engine's sense, because the loss consumes an allowance this engine does not
 * hold. A single {@code gainOrLoss} field with no reason attached invites all three to
 * be posted to the same account, and two of them do not belong there.
 *
 * @param reason                 which event took the asset off the books
 * @param consideration          what was received — contractual settlement, sale
 *     proceeds, recoveries, or the fair value of a replacement asset
 * @param carryingAmountRemoved  the gross carrying amount derecognised, which is the
 *     EIR leg's balance and not the contractual principal
 * @param gainOrLoss             {@code consideration - carryingAmountRemoved};
 *     positive income, negative a charge. Read it with {@link #reason}
 * @param invariants             what the derecognition asserts, chiefly INV-4
 */
public record DerecognitionResult(
    DerecognitionReason reason,
    Money consideration,
    Money carryingAmountRemoved,
    Money gainOrLoss,
    List<InvariantResult> invariants) {

    public DerecognitionResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(consideration, "consideration");
        Objects.requireNonNull(carryingAmountRemoved, "carryingAmountRemoved");
        Objects.requireNonNull(gainOrLoss, "gainOrLoss");
        Objects.requireNonNull(invariants, "invariants");
        invariants = List.copyOf(invariants);
    }

    /**
     * The carrying amount after the event: always zero, in the removed amount's
     * currency.
     *
     * <p>Stated as a value rather than left implicit. Derecognition means the asset
     * is gone, and a routine that computes a gain while leaving a balance behind is
     * the defect reference case 2 exists to catch — a residual written to suspense
     * that then never clears.
     */
    public Money carryingAmountAfter() {
        return Money.zero(carryingAmountRemoved.currency());
    }

    /** True where the event brought income forward, as a closure on a fee-received asset does. */
    public boolean isGain() {
        return gainOrLoss.isPositive();
    }

    /** True where the asset was carried above what it realised. */
    public boolean isLoss() {
        return gainOrLoss.isNegative();
    }

    /** The gain or loss as it is posted. */
    public Money presentedGainOrLoss() {
        return gainOrLoss.atPresentationScale();
    }

    /**
     * Whether this figure belongs in interest income at all.
     *
     * <p>False for a write-off, whose loss is measured against an allowance held by
     * the impairment engine (ACPIR 6(12)); routing it through this engine's fee or
     * interest lines would double-count the loss.
     */
    public boolean affectsInterestIncome() {
        return reason.recognisedByThisEngine();
    }

    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /** Whether every assertion this derecognition makes held. */
    public boolean isClean() {
        return breaches().isEmpty();
    }
}
