package com.crisil.eir.gl.journal;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * One posting to one account (04 § 2.7).
 *
 * <p>The amount is a magnitude and the direction is {@link #side()} — the same discipline the
 * suspense ledger uses, and the same the schema enforces with {@code CHECK (amount >= 0)}. A
 * negative amount is refused rather than normalised, because the two readings of it (a debit of
 * −100 and a credit of 100) are the same net and different journals, and only one of them
 * reconciles to the account it was meant for.
 *
 * @param accountCode the chart-of-accounts code; bank-specific, so validated for shape and not
 *                    against an enumeration this engine has no business holding
 * @param side        debit or credit
 * @param amount      unsigned
 * @param narrative   what the posting is for; optional, and empty rather than null
 */
public record JournalLine(String accountCode, DrCr side, Money amount, String narrative) {

    public JournalLine {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        accountCode = requireCode(accountCode);
        narrative = narrative == null ? "" : narrative.strip();
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                "journal line to " + accountCode + " carries " + amount.atPresentationScale()
                    + "; the amount is a magnitude and the side is the direction, so post the"
                    + " opposite side rather than a negative amount");
        }
    }

    /** A debit. */
    public static JournalLine debit(String accountCode, Money amount, String narrative) {
        return new JournalLine(accountCode, DrCr.DR, amount, narrative);
    }

    /** A credit. */
    public static JournalLine credit(String accountCode, Money amount, String narrative) {
        return new JournalLine(accountCode, DrCr.CR, amount, narrative);
    }

    /** The line's contribution to a residual: positive for a debit, negative for a credit. */
    public Money signed() {
        return side == DrCr.DR ? amount : amount.negate();
    }

    /** Whether this line moves nothing. */
    public boolean isZero() {
        return amount.isZero();
    }

    @Override
    public String toString() {
        return side + " " + accountCode + " " + amount.atPresentationScale()
            + (narrative.isEmpty() ? "" : " (" + narrative + ")");
    }

    private static String requireCode(String value) {
        Objects.requireNonNull(value, "accountCode");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(
                "a journal line with no account code cannot be posted anywhere");
        }
        return stripped;
    }
}
