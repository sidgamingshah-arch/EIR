package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.DrCr;
import java.util.Objects;

/**
 * One summarised GL posting: the total of every contract-level journal line that hit one account on
 * one side, in one run and period (FR-802, 04 § 2.7).
 *
 * <p>{@link #contributingLines()} is carried rather than discarded, and it is the field that makes
 * summarisation auditable. Without it the summary is three numbers nobody can walk back to the
 * postings behind them, and the failure mode {@code JournalBatch}'s javadoc names — "grouping can
 * lose an entry" — is undetectable after the fact. With it, the line count over the whole summary
 * reconciles to the batch's line count, which is the cheapest possible statement that nothing was
 * dropped. FR-808's per-contract trace requirement is the same argument at contract grain.
 *
 * <p><b>A zero-amount posting is retained.</b> A group exists because lines landed in it, not
 * because their total came out non-nil, and a contract posting DR 0.00 to an account is a line that
 * has to appear in the count. Filtering zeroes out here would be the tempting tidy-up that makes
 * the line count stop reconciling, on a summary that still looks right.
 *
 * @param accountCode      the chart-of-accounts code
 * @param side             debit or credit; not netted against the other side, see {@link
 *                         GlPostingKey}
 * @param amount           unsigned total, at working precision — reduced to presentation scale once,
 *                         where the feed is emitted, per 03 § 1.2
 * @param contributingLines how many journal lines were summed into it; at least one
 */
public record GlPosting(String accountCode, DrCr side, Money amount, int contributingLines) {

    public GlPosting {
        Objects.requireNonNull(amount, "amount");
        // Delegated so that the account-code and side rules live in exactly one place; a second
        // copy of "strip and refuse blank" is how the summary and its key eventually disagree about
        // which account a posting is for.
        GlPostingKey key = new GlPostingKey(accountCode, side);
        accountCode = key.accountCode();
        side = key.side();
        if (amount.isNegative()) {
            // Same discipline as JournalLine, and for the same reason: a credit posted as a
            // negative debit and a debit posted as a negative credit have the same net and are
            // different journals. The schema says it too — CHECK (amount >= 0).
            throw new IllegalArgumentException(
                "summarised posting " + side + " " + accountCode + " carries "
                    + amount.atPresentationScale() + "; the side is the direction, so a negative"
                    + " total means the lines were summed with signs and the sides collapsed");
        }
        if (contributingLines < 1) {
            throw new IllegalArgumentException(
                "summarised posting " + side + " " + accountCode + " claims "
                    + contributingLines + " contributing lines; a posting nothing contributed to is"
                    + " not a summary of anything");
        }
    }

    /** The grouping key this posting is the total for. */
    public GlPostingKey key() {
        return new GlPostingKey(accountCode, side);
    }

    /** The posting's contribution to a residual: positive for a debit, negative for a credit. */
    public Money signed() {
        return side == DrCr.DR ? amount : amount.negate();
    }

    @Override
    public String toString() {
        return side + " " + accountCode + " " + amount.atPresentationScale()
            + " (" + contributingLines + " lines)";
    }
}
