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

    final Connection open() {
        try {
            Connection connection = dataSource.getConnection();
            // Read-only, and not as an optimisation. Every one of the seven ports is a read; a
            // source that could write would let a run mutate the version set it is reading as at,
            // which is the one thing a replay must be unable to do. PostgreSQL enforces this at the
            // transaction level, so an accidental INSERT fails here rather than in a review.
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
