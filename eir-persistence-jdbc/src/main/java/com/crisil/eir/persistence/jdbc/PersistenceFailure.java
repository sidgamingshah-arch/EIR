package com.crisil.eir.persistence.jdbc;

import java.sql.SQLException;

/**
 * A database read that could not be performed at all.
 *
 * <p><b>Thrown, where invariants are returned — and the distinction is the point.</b> 03 § 9's
 * invariants are returned as {@link com.crisil.eir.domain.InvariantResult} because a breach is a
 * finding about the book: the run continues, the figure is reported, and somebody investigates. A
 * dropped connection, a missing table, a driver that cannot parse a URL is none of those things. It
 * is the adapter's environment being broken, there is no figure to report, and there is nothing for
 * a reviewer to conclude except that the run did not happen.
 *
 * <p><b>And emphatically not a swallowed empty result.</b> The tempting shape is
 * {@code catch (SQLException e) { return Optional.empty(); }} — which would turn a database outage
 * into "every contract has no opening state", quarantine ten million contracts under FR-905, and
 * produce a run report that looks like a data-quality catastrophe rather than a connection error.
 * {@link com.crisil.eir.application.ContractResult}'s javadoc makes the general form of this
 * argument: a run that silently processed 9,999,998 of 10,000,000 reconciles perfectly. Here the
 * arithmetic is the same and the cause would be one line of exception handling.
 *
 * <p>Unchecked, because every one of the seven port signatures in {@code eir-application} is
 * declared without a checked exception — deliberately, so that the use cases are not written around
 * a persistence detail. Wrapping is the only way to honour those signatures, and the SQLException is
 * kept as the cause so that the SQLSTATE survives to the log.
 */
public final class PersistenceFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PersistenceFailure(String message, SQLException cause) {
        super(message + " [SQLSTATE " + cause.getSQLState() + "]", cause);
    }

    PersistenceFailure(String message) {
        super(message);
    }
}
