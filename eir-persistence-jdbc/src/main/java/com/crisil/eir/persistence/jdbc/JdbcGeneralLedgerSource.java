package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The general ledger's control account balances for the boundary's period and book.
 *
 * <h2>This is the leg that was previously unfailable, and now is not</h2>
 *
 * <p>{@code eir-api}'s in-memory book says so of itself: its GL side is "summed from the book's own
 * closing positions", so "SL-1 is <em>reached with a figure on each side</em> and cannot fail on this
 * book. A production GeneralLedgerSource reads the bank's trial balance, and then it can."
 *
 * <p>This is that source. {@code gl_control_account_balance} (V3) is fed from the bank's trial
 * balance extract and is not written by any part of this engine, which is what makes SL-1 a
 * comparison of two independently sourced numbers rather than a restatement of one. 08's scope table
 * is the authority: "the engine posts to a general ledger; it is not one".
 *
 * <h2>Period and book are both in the key, and a missing period is not zero</h2>
 *
 * <p>A trial balance is taken as at a reporting date. A balance with no period attached would be
 * compared against whichever period's sub-ledger the run happened to be computing — a break that
 * looks like arithmetic and is a join. The book is in the key for FR-109's parallel books: an ACPIR
 * control account and its IGAAP counterpart are different accounts with different balances, and
 * summing them would give SL-1 a number roughly twice the sub-ledger's.
 *
 * <p>Where no observation is visible the result is an <b>empty list</b>, not a zero balance. That is
 * the honest answer and it is also the useful one: SL-1 with no GL figure is unresolved, whereas SL-1
 * against a fabricated zero is a screaming break on a book where nothing is wrong — which is exactly
 * the fixture defect {@code eir-api}'s {@code Book} records having produced, "a 36,059.88 break on a
 * book where nothing was wrong ... the worst kind, because an operator would go looking for the
 * 36,059.88".
 */
public final class JdbcGeneralLedgerSource extends JdbcAdapter implements GeneralLedgerSource {

    /** Binds: period id, book id, then {@link Params#systemTime}. */
    static final String SELECT_CONTROL_BALANCES = """
        SELECT g.account_code,
               g.book_id,
               g.balance,
               g.currency,
               g.source_ref
          FROM gl_control_account_balance g
         WHERE g.period_id = ?
           AND g.book_id = ?
           AND %s
         ORDER BY g.account_code
        """.formatted(TemporalReads.systemTime("g"));

    public JdbcGeneralLedgerSource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcGeneralLedgerSource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public List<GlControlAccountBalance> controlAccountBalances(AsAtBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        int periodId = PeriodId.of(boundary.businessAsOf());

        List<GlControlAccountBalance> balances = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement =
                 connection.prepareStatement(SELECT_CONTROL_BALANCES)) {

            new Params(statement)
                .integer(periodId)
                .text(bookId())
                .systemTime(boundary.recordedAsAt());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Currency currency = Rows.currency(rs, "currency");
                    balances.add(new GlControlAccountBalance(
                        Rows.text(rs, "account_code"),
                        Rows.text(rs, "book_id"),
                        Rows.money(rs, "balance", currency),
                        Rows.text(rs, "source_ref")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read GL control account balances for period " + periodId + " book "
                    + bookId() + " as at " + boundary.recordedAsAt(), e);
        }
        return List.copyOf(balances);
    }
}
