package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * One contract's closing balance on one control account, as the engine's sub-ledger holds it
 * (FR-801, FR-803, 04 § 2.8).
 *
 * <p>The sub-ledger side of SL-1. These are {@code period_balance} closing figures — 04 § 2.8 gives
 * one row per contract per period per book, carrying {@code closing_gca},
 * {@code closing_contractual}, {@code unamortised_fee}, {@code allowance}, {@code suspense_movement}
 * and the rest — mapped onto the control account that carries each of them in the chart of accounts.
 *
 * <p><b>Balances, not movements.</b> This is the quantity that makes SL-1 a different check from the
 * summarisation in {@link GlSummary}: a summary is what moved this period, and a control account
 * reconciliation is what is standing at close. A period whose movements summarise perfectly can
 * still have a control account 125.00 out because a prior period's posting went to the wrong account,
 * and only the balance comparison sees it.
 *
 * <p><b>{@code bookId} is part of the identity.</b> 04 § 2.8's grain is contract × period × book, and
 * a multi-book ledger reconciles each book to its own control accounts. Omitting it would let two
 * books' balances for one contract be summed into a control account that only carries one of them,
 * which reads as a break of exactly the other book's balance.
 *
 * @param contractId         the contract, from {@code period_balance.contract_id}
 * @param bookId             the book, from {@code period_balance.book_id}
 * @param controlAccountCode the GL control account this balance is controlled by
 * @param closingBalance     signed from the holder's perspective, as {@link Money} is throughout;
 *                           unrounded, and reduced to presentation scale once where SL-1 compares
 */
public record SubLedgerBalance(
    String contractId, String bookId, String controlAccountCode, Money closingBalance) {

    public SubLedgerBalance {
        Objects.requireNonNull(closingBalance, "closingBalance");
        contractId = requireText(contractId, "contractId");
        bookId = requireText(bookId, "bookId");
        controlAccountCode = requireText(controlAccountCode, "controlAccountCode");
    }

    /**
     * A balance in the default book, for fixtures and single-book callers.
     *
     * <p>{@code MAIN} matches the book id the journal fixtures in this module already use.
     */
    public static SubLedgerBalance of(
        String contractId, String controlAccountCode, Money closingBalance) {
        return new SubLedgerBalance(contractId, "MAIN", controlAccountCode, closingBalance);
    }

    /** The (contract, book, account) triple this balance is the one figure for. */
    public String identity() {
        return contractId + "/" + bookId + "/" + controlAccountCode;
    }

    @Override
    public String toString() {
        return identity() + " " + closingBalance.atPresentationScale();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank on a sub-ledger balance");
        }
        return stripped;
    }
}
