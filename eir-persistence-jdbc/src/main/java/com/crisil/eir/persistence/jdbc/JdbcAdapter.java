package com.crisil.eir.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * What every adapter in this module shares: a {@link DataSource}, a book, and no clock.
 *
 * <h2>No clock, and the ban is the point of the module</h2>
 *
 * <p>There is no {@code Instant.now()} in this package and there must never be one. The temptation
 * is specific and it looks harmless: a port implementation that treats a null or "live" boundary as
 * "read the latest" by substituting the current instant. ADR-0003's consequence is that a run which
 * reads its own clock cannot be re-run, and {@link com.crisil.eir.application.port.AsAtBoundary}
 * makes the same point about its own factory — the instant is supplied even for a live run "because
 * a run that reads {@code Instant.now()} at its own start cannot be re-run to the same boundary
 * tomorrow". {@code DeterminismTest} enforces the ban one and two modules inward; here it is a
 * convention, so it is stated where a reader will find it.
 *
 * <p>The consequence in this module is that {@code boundary.recordedAsAt()} is bound into every
 * query and nothing else ever is. A "latest" read is a boundary whose instant is far in the future,
 * chosen by the caller and recorded on the run.
 *
 * <h2>The book is constructor state, not a query parameter</h2>
 *
 * <p>FR-109 runs parallel books — the ACPIR basis alongside IGAAP and tax on one contract — and
 * {@code period_balance}, {@code journal_entry} and {@code suspense_entry} are all keyed by
 * {@code book_id}. None of the seven port signatures carries a book, so it cannot arrive as an
 * argument. Held here rather than defaulted inside each query so that there is exactly one place a
 * misconfigured book can come from: an adapter reading two books' rows would sum a contract's
 * balance twice, and the sub-ledger would tie to nothing at a difference equal to the second book.
 */
abstract class JdbcAdapter {

    /** The book every read is scoped to (FR-109). */
    static final String DEFAULT_BOOK = "MAIN";

    private final DataSource dataSource;
    private final String bookId;

    JdbcAdapter(DataSource dataSource, String bookId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.bookId = requireBook(bookId);
    }

    /**
     * A connection that cannot write, and that reads one consistent snapshot.
     *
     * <h2>{@code setReadOnly(true)} alone does nothing, which is why autocommit goes off first</h2>
     *
     * <p>The pinned driver's {@code PgConnection.setReadOnly} only sends anything to the server when
     * its {@code readOnlyMode} property is {@code always}; the default is {@code transaction}, whose
     * own description in the driver reads "setting readOnly to 'true' will cause transactions to
     * BEGIN READ ONLY <b>if autocommit is 'false'</b>". Under the default autocommit the call is
     * therefore a Java-side flag and the session stays read-write — so the guarantee this method
     * exists to give would have been a comment rather than a control, and a write issued through one
     * of these connections (a future adapter method, a {@code WITH ... INSERT} CTE, a
     * {@code SELECT ... FOR UPDATE}) would have committed.
     * {@code ReadOnlyConnectionLiveTest} exercises it against the live cluster, because a claim of
     * this shape is not worth making untested.
     *
     * <p>Why it matters beyond tidiness: every one of the seven ports is a read, and a source that
     * could write would let a run mutate the version set it is reading as at — the one thing a replay
     * must be unable to do.
     *
     * <h2>The transaction is a second benefit, not a cost</h2>
     *
     * <p>{@code openingState} issues five queries. Under autocommit each sees its own snapshot, so a
     * correction committing between the second and the third would hand one contract a mixture of two
     * version sets: internally consistent, unreproducible, and invisible. One transaction across the
     * five removes that. Nothing commits — the caller closes the connection and the read-only
     * transaction is discarded.
     */
    final Connection open() {
        try {
            Connection connection = dataSource.getConnection();
            // Order matters: autocommit must be off BEFORE setReadOnly, so the flag reaches the
            // server on the transaction the next statement begins.
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException e) {
            throw new PersistenceFailure("could not open a connection to read as at a boundary", e);
        }
    }

    final String bookId() {
        return bookId;
    }

    private static String requireBook(String value) {
        Objects.requireNonNull(value, "bookId");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(
                "a book id is required (FR-109): the sub-ledger tables are keyed by book, and an"
                    + " unscoped read would sum a contract's balance once per book the bank runs");
        }
        return stripped;
    }
}
