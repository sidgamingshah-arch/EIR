package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * A control account balance <b>as the general ledger reports it</b> (FR-803).
 *
 * <p>The GL side of SL-1, and the reason SL-1 is a control rather than a tautology. This figure comes
 * from outside the engine — a trial balance extract, a control account enquiry — and it is not
 * computed from anything in this package. The engine POSTS to a general ledger; it is not one
 * (08's scope table), so what the GL says its control account stands at is somebody else's number.
 *
 * <p><b>Nothing here derives this from {@link SubLedgerBalance} or {@link GlSummary}, and nothing
 * should.</b> A factory offering to do it would be the obvious convenience and it would destroy the
 * control: the comparison would then be a group-and-sum against its own inputs, which cannot fail,
 * while still reading as a reconciliation on a close pack. That failure is harder to spot than an
 * absent control, which is why this class is a plain carrier with a source reference and no
 * arithmetic.
 *
 * <p><b>{@code sourceRef} is mandatory.</b> A reconciliation is evidence, and a GL figure with no
 * statement of where it came from cannot be re-derived by whoever reviews the break next quarter.
 * The same argument 04 § 2.9 makes for storing {@code ecl_engine_version} alongside a stage
 * assignment: the provenance is what makes the number replayable.
 *
 * @param accountCode the chart-of-accounts code
 * @param bookId      the book the GL reported this for; must match the sub-ledger book being
 *                    reconciled
 * @param balance     as reported, signed in the same direction as {@link SubLedgerBalance}
 * @param sourceRef   where the figure came from — extract id, trial balance reference, enquiry
 *                    timestamp
 */
public record GlControlAccountBalance(
    String accountCode, String bookId, Money balance, String sourceRef) {

    public GlControlAccountBalance {
        Objects.requireNonNull(balance, "balance");
        accountCode = requireText(accountCode, "accountCode");
        bookId = requireText(bookId, "bookId");
        sourceRef = requireText(sourceRef, "sourceRef");
    }

    /** A balance in the default book, for fixtures and single-book callers. */
    public static GlControlAccountBalance of(
        String accountCode, Money balance, String sourceRef) {
        return new GlControlAccountBalance(accountCode, "MAIN", balance, sourceRef);
    }

    @Override
    public String toString() {
        return accountCode + "/" + bookId + " " + balance.atPresentationScale()
            + " per " + sourceRef;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(
                field + " must not be blank on a GL control account balance; a GL figure with no"
                    + " stated source is not evidence of anything");
        }
        return stripped;
    }
}
