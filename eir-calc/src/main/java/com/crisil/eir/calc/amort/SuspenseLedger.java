package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * Interest-in-suspense as a ledger with a balance, not a memorandum note (FR-604, 03 § 7.3).
 *
 * <p><b>Why this type exists at all.</b> Until now the suspended amount was a single
 * {@code Money} field on {@link Stage3Decomposition} — {@code toSuspense}, the contractual
 * interest billed and not recognised in one period. That is a memorandum figure: it has no
 * opening balance, no movements, and nothing that can be reconciled against anything. 03 § 7.3
 * is explicit that this will not do, and states the reason as precedent rather than principle:
 * the Hong Kong experience is that a suspense regime reconciles to accounting EIR <em>only</em>
 * when the suspense ledger is a real object. A per-period figure cannot answer the question an
 * auditor actually asks, which is not "what did you suspend in March" but "what is the balance,
 * and does the sum of what went in and out explain it".
 *
 * <p><b>Four movements, and no others.</b> Interest is charged to suspense while recognition is
 * suppressed; it leaves on recovery in cash, or on write-off. There is deliberately no movement
 * for a cure. That is the whole content of FR-607: curing stops the charging and does not touch
 * the balance. The tempting fifth movement — release to income on cure — would recognise income
 * that was correctly never recognised, and the fact that the balance is sitting there when the
 * borrower recovers is exactly what makes it tempting. See {@link #onCure}.
 *
 * <p><b>A negative closing balance throws rather than reporting.</b> This is the one place in the
 * Stage 3 machinery that throws for a data condition, against the convention that data conditions
 * are returned as {@link com.crisil.eir.domain.InvariantResult}. The reason is that a negative
 * suspense balance is not a reconciliation break to be reported and carried forward — it is a
 * balance that cannot be carried forward at all, and every subsequent period computed from it
 * would be wrong in a way no later invariant would attribute to here. Recovering more suspended
 * interest than was ever suspended means the recovery has been posted against the wrong contract
 * or the opening balance was not loaded, and both need the contract quarantined. FR-905's barrier
 * ({@code policy.exception.FailureIsolation}) catches {@code RuntimeException} per contract and
 * files it, so throwing here quarantines one contract rather than failing a run — which is the
 * behaviour a report-and-continue would have been chosen for anyway.
 *
 * @param openingBalance   suspended interest carried in from the prior period; never negative
 * @param chargedToSuspense contractual interest billed and not recognised this period
 * @param recovered        suspended interest received in cash this period
 * @param writtenOff       suspended interest written off this period
 * @param closingBalance   derived; never negative
 */
public record SuspenseLedger(
    Money openingBalance,
    Money chargedToSuspense,
    Money recovered,
    Money writtenOff,
    Money closingBalance) {

    public SuspenseLedger {
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(chargedToSuspense, "chargedToSuspense");
        Objects.requireNonNull(recovered, "recovered");
        Objects.requireNonNull(writtenOff, "writtenOff");
        Objects.requireNonNull(closingBalance, "closingBalance");
        // Every movement is stated as a positive magnitude and the direction is the field's name.
        // The alternative — signed movements — reads more flexibly and loses the one thing this
        // ledger is for: a recovery posted as a negative charge and a charge posted as a negative
        // recovery produce the same closing balance and different ledgers, and only one of them
        // reconciles to the cash book.
        requireNotNegative(openingBalance, "openingBalance");
        requireNotNegative(chargedToSuspense, "chargedToSuspense");
        requireNotNegative(recovered, "recovered");
        requireNotNegative(writtenOff, "writtenOff");
        if (closingBalance.isNegative()) {
            throw new IllegalArgumentException(
                "interest-in-suspense would close at " + closingBalance.atPresentationScale()
                    + " from an opening " + openingBalance.atPresentationScale() + " charged "
                    + chargedToSuspense.atPresentationScale() + " recovered "
                    + recovered.atPresentationScale() + " written off "
                    + writtenOff.atPresentationScale()
                    + "; more suspended interest has left the ledger than ever entered it, so"
                    + " either the recovery is against the wrong contract or the opening balance"
                    + " was not loaded");
        }
    }

    /**
     * A period's movements, with the closing balance derived rather than supplied.
     *
     * <p>Derived on purpose. Accepting a closing balance and checking it against the movements
     * would make the identity {@code closing = opening + charged − recovered − written off} an
     * assertion, and it is not one — it is the definition of the balance. What is worth asserting
     * is that the ledger agrees with the sources the movements came from, and that belongs one
     * level up, in {@link Stage3Reconciliation}, where the billed amount and the cash book are
     * both in view.
     */
    public static SuspenseLedger forPeriod(
        Money openingBalance, Money chargedToSuspense, Money recovered, Money writtenOff) {
        Objects.requireNonNull(openingBalance, "openingBalance");
        Money closing = openingBalance
            .plus(chargedToSuspense)
            .minus(recovered)
            .minus(writtenOff);
        return new SuspenseLedger(
            openingBalance, chargedToSuspense, recovered, writtenOff, closing);
    }

    /** A contract with nothing suspended yet. */
    public static SuspenseLedger opening(Money zeroBalance) {
        Objects.requireNonNull(zeroBalance, "zeroBalance");
        return new SuspenseLedger(zeroBalance, Money.zero(zeroBalance.currency()),
            Money.zero(zeroBalance.currency()), Money.zero(zeroBalance.currency()), zeroBalance);
    }

    /**
     * The period a contract cures in: nothing is charged, and the balance carries forward intact.
     *
     * <p>Named rather than left to {@code forPeriod(balance, zero, zero, zero)} because the
     * absence of a movement is the requirement (FR-607) and an absence is invisible at a call
     * site. A reader looking for where cure touches the suspense ledger finds this method and its
     * answer, instead of finding nothing and having to conclude the case was handled.
     *
     * <p>Recoveries and write-offs remain available in the cure period through
     * {@link #forPeriod}: a borrower who cures by paying arrears has both cured and recovered
     * suspended interest, and those are two facts, not one. What is unavailable in every period
     * is a release to income.
     */
    public static SuspenseLedger onCure(SuspenseLedger priorPeriod) {
        Objects.requireNonNull(priorPeriod, "priorPeriod");
        Money balance = priorPeriod.closingBalance();
        return new SuspenseLedger(balance, Money.zero(balance.currency()),
            Money.zero(balance.currency()), Money.zero(balance.currency()), balance);
    }

    /** The next period, opening where this one closed. */
    public SuspenseLedger next(Money chargedToSuspense, Money recovered, Money writtenOff) {
        return forPeriod(closingBalance, chargedToSuspense, recovered, writtenOff);
    }

    /** Net movement for the period; negative where the ledger ran down. */
    public Money netMovement() {
        return closingBalance.minus(openingBalance);
    }

    /** Whether anything is suspended at the close of the period. */
    public boolean hasBalance() {
        return closingBalance.isPositive();
    }

    /** A one-line ledger movement, at presentation scale. */
    public String describe() {
        return "interest-in-suspense " + openingBalance.atPresentationScale()
            + " + charged " + chargedToSuspense.atPresentationScale()
            + " − recovered " + recovered.atPresentationScale()
            + " − written off " + writtenOff.atPresentationScale()
            + " = " + closingBalance.atPresentationScale();
    }

    private static void requireNotNegative(Money value, String field) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(
                field + " is a magnitude and the direction is its name, so it cannot be "
                    + value.atPresentationScale()
                    + "; post the opposite movement instead of a negative one");
        }
    }
}
