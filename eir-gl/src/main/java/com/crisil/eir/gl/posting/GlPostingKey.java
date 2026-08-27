package com.crisil.eir.gl.posting;

import com.crisil.eir.gl.journal.DrCr;
import java.util.Objects;

/**
 * The grain a journal batch is summarised at: one account code, one side (FR-802).
 *
 * <p><b>Side is part of the key, so debits and credits to one account are not netted.</b> That is
 * the decision worth stating, because netting is the smaller output and it is wrong. The
 * {@code journal_entry} DDL gives the argument in the schema's own words — amount is unsigned with
 * the side in its own column, because "signed amounts … make 'total debits posted this run' a
 * filtered aggregate instead of a sum". A GL feed that receives a net movement per account has lost
 * the gross debit and gross credit, and those are the figures a control account's own movement
 * schedule is built from.
 *
 * <p>The net is still available — {@link GlSummary#netFor(String)} differences the two postings —
 * so nothing is lost by keeping the sides apart, and something is lost by merging them.
 *
 * @param accountCode the chart-of-accounts code, as it appeared on the journal lines
 * @param side        debit or credit
 */
public record GlPostingKey(String accountCode, DrCr side) implements Comparable<GlPostingKey> {

    public GlPostingKey {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(accountCode, "accountCode");
        accountCode = accountCode.strip();
        if (accountCode.isEmpty()) {
            throw new IllegalArgumentException("a GL posting with no account code posts nowhere");
        }
    }

    /**
     * Account code, then side with debits first.
     *
     * <p>A total order so that summarisation is deterministic in the output as well as in the
     * arithmetic. Two runs over the same postings arriving in different orders must emit the same
     * feed, or DT-1's bit-identical replay is a claim about luck. {@link DrCr}'s declaration order
     * is {@code DR, CR}, which is the order a journal is conventionally read in.
     */
    @Override
    public int compareTo(GlPostingKey other) {
        int byAccount = accountCode.compareTo(other.accountCode);
        return byAccount != 0 ? byAccount : side.compareTo(other.side);
    }

    @Override
    public String toString() {
        return side + " " + accountCode;
    }
}
