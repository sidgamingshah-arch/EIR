package com.crisil.eir.gl.journal;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * One contract's postings for one period, in one run (FR-802, 04 § 2.7).
 *
 * <p><b>Balance is not a constructor check, and that is the important decision here.</b> Refusing
 * to construct an unbalanced entry is the obvious design and it would make invariant SL-2
 * unfailable — a control asserting a property the type cannot violate is a tautology, and this
 * codebase has now recorded four of those wearing invariant ids. FR-802 says "emit <em>balanced</em>
 * journal postings … (invariant SL-2)", which places the requirement on the emission and names the
 * control that checks it; so the entry is constructible, {@link #sidesBalance()} evaluates it, and
 * the close gate (FR-901) is what refuses to publish a period whose SL-2 is red.
 *
 * <p>That layering is what makes both controls real. A constructor guard would move the failure to
 * whoever built the entry, where it becomes an exception in a batch rather than a figure on a
 * reconciliation — and the number an accountant needs is <em>by how much</em>, not <em>that</em>.
 *
 * <p>What <em>is</em> refused: an entry with no lines, and lines in more than one currency. Neither
 * is an unbalanced journal; both are journals that cannot be summed at all.
 *
 * @param contractId the contract these postings belong to
 * @param periodId   the accounting period, in the schema's {@code YYYYMM} form
 * @param runId      the amortisation run that produced them
 * @param bookId     the book, for a multi-book ledger
 * @param postedOn   the posting date
 * @param lines      at least one, all in one currency
 */
public record JournalEntry(
    String contractId,
    int periodId,
    String runId,
    String bookId,
    java.time.LocalDate postedOn,
    List<JournalLine> lines) {

    public JournalEntry {
        contractId = requireText(contractId, "contractId");
        runId = requireText(runId, "runId");
        bookId = requireText(bookId, "bookId");
        Objects.requireNonNull(postedOn, "postedOn");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(
                "journal entry for contract " + contractId + " has no lines; an entry that posts"
                    + " nothing is not a balanced entry, it is an absent one");
        }
        Currency currency = lines.getFirst().amount().currency();
        for (JournalLine line : lines) {
            if (!line.amount().currency().equals(currency)) {
                // Not an imbalance — a cross-currency entry has no single residual to report, so
                // SL-2 could not answer for it at all.
                throw new IllegalArgumentException(
                    "journal entry for contract " + contractId + " mixes " + currency + " and "
                        + line.amount().currency() + "; one entry is one currency, or its residual"
                        + " is not a number");
            }
        }
    }

    /** The currency every line is in. */
    public Currency currency() {
        return lines.getFirst().amount().currency();
    }

    /** Sum of the debit lines. */
    public Money totalDebits() {
        return total(DrCr.DR);
    }

    /** Sum of the credit lines. */
    public Money totalCredits() {
        return total(DrCr.CR);
    }

    /**
     * Debits less credits — nil on a balanced entry.
     *
     * <p>Computed unrounded. Rounding the two sides independently before subtracting manufactures
     * a residual on an entry that balances exactly, which is the failure mode ST-2's note in
     * 03 § 9 describes for the Stage 3 decomposition.
     */
    public Money residual() {
        return totalDebits().minus(totalCredits());
    }

    /**
     * Invariant SL-2 for this contract: debits equal credits.
     *
     * <p>Per contract, which is the finer of the two grains SL-2's statement names. The run-level
     * grain is discussed on {@link JournalBatch#sidesBalance()} and is not a second check.
     */
    public InvariantResult sidesBalance() {
        Money residual = residual();
        String detail = "contract " + contractId + " period " + periodId + ": debits "
            + totalDebits().atPresentationScale() + " against credits "
            + totalCredits().atPresentationScale();
        if (residual.signum() == 0) {
            return InvariantResult.pass(InvariantId.SL_2, detail);
        }
        return InvariantResult.fail(InvariantId.SL_2,
            detail + " — out by " + residual.atPresentationScale(), residual.amount());
    }

    /** The lines posting to {@code accountCode}, either side. */
    public List<JournalLine> linesFor(String accountCode) {
        List<JournalLine> matching = new ArrayList<>();
        for (JournalLine line : lines) {
            if (line.accountCode().equals(accountCode)) {
                matching.add(line);
            }
        }
        return List.copyOf(matching);
    }

    /** A one-line summary naming the contract and its residual. */
    public String describe() {
        return "contract " + contractId + " run " + runId + " period " + periodId + ": "
            + lines.size() + " lines, DR " + totalDebits().atPresentationScale()
            + " CR " + totalCredits().atPresentationScale()
            + (residual().signum() == 0 ? " (balanced)"
                : " (OUT BY " + residual().atPresentationScale() + ")");
    }

    private Money total(DrCr side) {
        Money total = Money.zero(currency());
        for (JournalLine line : lines) {
            if (line.side() == side) {
                total = total.plus(line.amount());
            }
        }
        return total;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank on a journal entry");
        }
        return stripped;
    }
}
