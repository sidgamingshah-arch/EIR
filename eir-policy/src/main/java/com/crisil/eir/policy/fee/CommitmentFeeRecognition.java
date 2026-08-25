package com.crisil.eir.policy.fee;

import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Where a commitment fee ended up, at the event that ended its deferral (FR-204).
 *
 * <p>Three legs and they must exhaust the fee: what the commitment period had already taken to
 * income, what this event takes to income, and what this event moves into the carrying amount of
 * the loan that resulted. Recorded as three figures against the original fee rather than as a
 * single "recognised" amount, because the audit question is not how much was recognised but
 * <em>which of the three places</em> the fee went and when — and because the identity across
 * them is checkable, which a single figure is not.
 *
 * <p><b>The identity is enforced at construction.</b>
 * {@code alreadyRecognised + toProfitOrLoss + toCarryingAmount = fee}, exactly, at working
 * precision. A commitment fee that leaks a paisa between the deferred-income account and the
 * loan's carrying amount leaks it into a difference nobody reconciles: the deferred-income
 * account is cleared by this event, so the residue has nothing left to tie against and shows up,
 * if at all, as an unexplained movement in fee income. This belongs in the invariant register as
 * a named check — see the note below — and until it has an id it is enforced here, where it
 * cannot be skipped.
 *
 * <p><b>Invariant id needed.</b> This engine's register (eir-domain {@code InvariantId}) has no
 * constant for the commitment-fee terminal identity. The one this type wants is
 * {@code CF_1 "commitment fee fully accounted at its terminal event"}, at which point the
 * constructor guard should also be published as an {@code InvariantResult} for the period rather
 * than only refusing to build the record. The id is owned by another work unit, so it is
 * reported rather than added.
 *
 * <p>The two triggers are asymmetric on purpose, and each forbids the other's leg:
 *
 * <ul>
 *   <li>{@link Trigger#EXPIRED_UNDRAWN} — there is no asset. A non-zero
 *       {@code toCarryingAmount} would be capitalising a fee into a loan that does not exist,
 *       which is unrepresentable rather than merely wrong.
 *   <li>{@link Trigger#DRAWN} — the fee belongs to the loan's yield. A non-zero
 *       {@code toProfitOrLoss} would front-load into the drawdown period income that IFRS 9
 *       B5.4.2(b) spreads across the life of the loan through its EIR. Note this bars only
 *       income recognised <em>by the drawdown event</em>; {@code alreadyRecognised} may well be
 *       positive, being what the commitment period earned before the assessment was overtaken by
 *       events.
 * </ul>
 *
 * @param feeCode           the posting's fee code
 * @param productId         the product whose threshold classified the fee
 * @param on                the date of the terminal event
 * @param toProfitOrLoss    recognised in income by this event
 * @param toCarryingAmount  moved into the initial carrying amount of the resulting loan
 * @param alreadyRecognised taken to income over the commitment period before this event
 * @param fee               the original fee, which the three legs exhaust
 * @param trigger           what ended the deferral
 * @param policyVersionId   the {@code COMMITMENT_THRESHOLD} version that classified the fee
 * @param detail            a one-line statement of the treatment and why
 */
public record CommitmentFeeRecognition(
    String feeCode,
    String productId,
    LocalDate on,
    Money toProfitOrLoss,
    Money toCarryingAmount,
    Money alreadyRecognised,
    Money fee,
    Trigger trigger,
    String policyVersionId,
    String detail) {

    /** What ended the deferral. */
    public enum Trigger {

        /**
         * The commitment period ran out with nothing drawn. The recognition FR-204 asks for that
         * fires when nothing happens, and therefore the one a scheduler has to go looking for.
         */
        EXPIRED_UNDRAWN,

        /** The facility drew, so the fee belongs to the resulting loan's EIR. */
        DRAWN
    }

    public CommitmentFeeRecognition {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(toProfitOrLoss, "toProfitOrLoss");
        Objects.requireNonNull(toCarryingAmount, "toCarryingAmount");
        Objects.requireNonNull(alreadyRecognised, "alreadyRecognised");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(detail, "detail");
        if (toProfitOrLoss.isNegative() || toCarryingAmount.isNegative()
            || alreadyRecognised.isNegative() || fee.isNegative()) {
            // A negative leg is over-recognition elsewhere: something took more of the fee than
            // there was, and the arithmetic pushed the excess into another leg as a credit.
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " recognition on " + on + " has a negative leg:"
                    + " already " + alreadyRecognised + ", to P&L " + toProfitOrLoss
                    + ", to carrying amount " + toCarryingAmount + ", fee " + fee);
        }
        // Money.plus and Money.minus reject a currency mismatch, so a cross-currency recognition
        // fails here as a caller defect rather than being reported as a broken identity.
        Money accounted = alreadyRecognised.plus(toProfitOrLoss).plus(toCarryingAmount);
        if (accounted.compareTo(fee) != 0) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " recognition on " + on + " accounts for "
                    + accounted + " of a fee of " + fee + "; the three legs must exhaust the fee"
                    + " exactly, since the deferred-income account is cleared by this event and a"
                    + " residue has nothing left to reconcile against");
        }
        if (trigger == Trigger.EXPIRED_UNDRAWN && !toCarryingAmount.isZero()) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " expired undrawn on " + on + " yet moves "
                    + toCarryingAmount + " into a carrying amount; there is no asset to carry it"
                    + " into, which is why B5.4.2(b) makes the fee revenue on expiry");
        }
        if (trigger == Trigger.DRAWN && !toProfitOrLoss.isZero()) {
            throw new IllegalArgumentException(
                "commitment fee " + feeCode + " drawn on " + on + " recognises " + toProfitOrLoss
                    + " in income at drawdown; the fee belongs to the loan's EIR from that date"
                    + " (IFRS 9 B5.4.2(b)) and recognising it here front-loads the yield");
        }
    }

    /**
     * The three legs summed — the fee, on any record that could be constructed.
     *
     * <p>Exposed even though the constructor guarantees it, so that a caller assembling a period's
     * commitment-fee movement can foot its own total against the fees it started from rather than
     * trusting this type's word for it.
     */
    public Money totalAccountedFor() {
        return alreadyRecognised.plus(toProfitOrLoss).plus(toCarryingAmount);
    }

    /** Whether this event moved anything into a loan's initial carrying amount. */
    public boolean entersCarryingAmount() {
        return !toCarryingAmount.isZero();
    }

    /**
     * The same recognition with every figure reduced to paise, still exhausting the fee.
     *
     * <p><b>Reducing each leg on its own does not work, and the failure is not exotic.</b> A fee of
     * 100.05 drawn at the midpoint of its commitment period splits into 50.025 and 50.025; each
     * rounds HALF_UP to 50.03 and the published legs sum to 100.06 against a published fee of
     * 100.05. The extra paisa is posted against a deferred-income account this event has just
     * cleared, so nothing downstream has anything left to reconcile it against — the leak this
     * type's identity exists to prevent, reintroduced at the moment of publication.
     *
     * <p>So one leg is a plug, and which one is a choice rather than a convenience: <b>the leg this
     * event creates absorbs the residue, and the leg earlier periods already published does
     * not.</b> {@code alreadyRecognised} is the sum of figures that have been through a close and
     * been reported; moving a paisa there restates a published period to tidy up a rounding. The
     * terminal leg has not been published yet, and it is the one whose whole purpose is to clear
     * the deferred balance to nil — the same reasoning as a schedule's final-period plug (04 § 2.4
     * {@code residue_policy}).
     *
     * <p>Idempotent: applying it to an already-reduced recognition returns the same figures.
     */
    public CommitmentFeeRecognition atPresentationScale() {
        Money presentedFee = fee.atPresentationScale();
        Money presentedAlready = alreadyRecognised.atPresentationScale();
        // Non-negative: alreadyRecognised never exceeds the fee (the constructor's non-negative
        // legs and exhaustion identity together say so) and HALF_UP rounding is monotonic, so the
        // reduced figures preserve the inequality.
        Money plug = presentedFee.minus(presentedAlready);
        Money nil = Money.zero(fee.currency());
        Money presentedIncome = trigger == Trigger.DRAWN ? nil : plug;
        Money presentedCarrying = trigger == Trigger.DRAWN ? plug : nil;
        return new CommitmentFeeRecognition(feeCode, productId, on, presentedIncome,
            presentedCarrying, presentedAlready, presentedFee, trigger, policyVersionId, detail);
    }

    /**
     * A one-line audit sentence, in the shape {@code PolicyVersion.describe()} established.
     *
     * <p>Built from {@link #atPresentationScale()} so that the figures in the sentence foot to the
     * fee in the sentence. An audit record whose own numbers do not add up is worse than none.
     */
    public String describe() {
        CommitmentFeeRecognition published = atPresentationScale();
        return "commitment fee " + feeCode + " on product " + productId + " " + trigger + " on "
            + on + ": " + published.toProfitOrLoss() + " to income, "
            + published.toCarryingAmount() + " to carrying amount, "
            + published.alreadyRecognised() + " already recognised, of "
            + published.fee() + " — " + detail + " [policy " + policyVersionId + "]";
    }
}
