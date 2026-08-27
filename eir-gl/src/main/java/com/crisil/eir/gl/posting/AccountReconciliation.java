package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import java.util.List;
import java.util.Objects;

/**
 * One control account's line on the SL-1 reconciliation: what the sub-ledger holds, what the GL
 * reports, what is explained, and what is left (FR-803, control C-13).
 *
 * <p>A report row and nothing more. It validates no relationship between its own fields, because
 * every relationship worth asserting here is the one SL-1 asserts, and a guard in this constructor
 * would make {@link GlReconciliation#tiesToGl()} unfailable — the trap this repository has fallen
 * into four times. {@code JournalEntry} makes the same choice for SL-2 and says so in the same words.
 *
 * <h2>The arithmetic, and why it subtracts rather than caps</h2>
 *
 * <pre>
 *   difference  = subLedgerTotal − glBalance          (signed)
 *   unexplained = difference − explained              (signed)
 * </pre>
 *
 * <p><b>{@code unexplained} is a subtraction, deliberately not
 * {@code max(0, |difference| − |explained|)}.</b> The capped form is the intuitive one and it breaks
 * the control in two ways at once. It lets an explanation of 500.00 silence a difference of 125.00,
 * so a wrong explanation suppresses a real break; and it makes over-explanation unrepresentable, so
 * "an explanation that does not match the difference it claims to explain" — itself a finding — has
 * no way to surface.
 *
 * <p>Under subtraction it surfaces for free and needs no separate check: a +500.00 explanation
 * against a +125.00 difference leaves −375.00 unexplained, and SL-1 breaches with a deviation of
 * 375.00. That is the right answer whichever of the two figures is wrong, because either the GL
 * balance or the explanation has to be corrected before the account ties, and the accountant needs
 * to know the pair disagrees. The direction of the residual tells them which way.
 *
 * <p>The same subtraction catches an explanation filed against an account that already ties: a
 * +250.00 explanation on a nil difference leaves −250.00 unexplained. The one case it cannot see is
 * two spurious explanations on a tying account that exactly cancel; that is reported as plain data
 * by {@link GlReconciliation#unmatchedExplanations()} rather than folded into SL-1's deviation,
 * because nothing about the account's balance is unaccounted for and SL-1's deviation has to keep
 * meaning "money the two books disagree by".
 *
 * @param accountCode        the control account
 * @param contractCount      how many sub-ledger balances were summed into {@link #subLedgerTotal}
 * @param subLedgerTotal     the sum of those balances
 * @param glBalance          what the GL reported, or nil where it reported nothing
 * @param glBalanceSupplied  whether a GL figure was supplied at all — a nil balance and an absent
 *                           one give the same arithmetic and are very different findings
 * @param explained          the signed total of explanations filed against this account
 * @param explanations       those explanations, for the report
 */
public record AccountReconciliation(
    String accountCode,
    int contractCount,
    Money subLedgerTotal,
    Money glBalance,
    boolean glBalanceSupplied,
    Money explained,
    List<ExplainedDifference> explanations) {

    public AccountReconciliation {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(subLedgerTotal, "subLedgerTotal");
        Objects.requireNonNull(glBalance, "glBalance");
        Objects.requireNonNull(explained, "explained");
        explanations = List.copyOf(Objects.requireNonNull(explanations, "explanations"));
    }

    /**
     * Sub-ledger less GL, unrounded.
     *
     * <p>Unrounded because the reduction happens once, on the residual, where SL-1 compares it —
     * {@code InvariantResult.ofMoney} sets out at length why differencing two separately rounded
     * operands manufactures a one-paise breach on figures that agree, and cites the measured case:
     * two legs of 242,103,892.5032 and 242,103,892.5063 presenting as .50 and .51.
     */
    public Money difference() {
        return subLedgerTotal.minus(glBalance);
    }

    /** The difference less the explanations: the figure FR-803 requires to be nil. */
    public Money unexplained() {
        return difference().minus(explained);
    }

    /** Whether this account contributes nothing to SL-1's deviation, at presentation scale. */
    public boolean ties() {
        return unexplained().atPresentationScale().isZero();
    }

    /** Whether the two books agree before any explanation is applied. */
    public boolean agreesBeforeExplanation() {
        return difference().atPresentationScale().isZero();
    }

    /**
     * Whether explanations were filed against an account whose books already agree.
     *
     * <p>A data-quality signal on the explanation register. Where the explanations do not cancel it
     * is already an SL-1 breach; where they do, this is the only place it shows.
     */
    public boolean carriesUnmatchedExplanation() {
        return agreesBeforeExplanation() && !explanations.isEmpty();
    }

    /** A reconciliation-report line, at presentation scale. */
    public String describe() {
        return accountCode + ": sub-ledger " + subLedgerTotal.atPresentationScale()
            + " over " + contractCount + " contracts against GL "
            + (glBalanceSupplied ? glBalance.atPresentationScale().toString() : "(none supplied)")
            + ", difference " + difference().atPresentationScale()
            + ", explained " + explained.atPresentationScale()
            + ", unexplained " + unexplained().atPresentationScale();
    }

    @Override
    public String toString() {
        return describe();
    }
}
