package com.crisil.eir.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Sequential parameter binding, so that a temporal predicate cannot be bound half-way.
 *
 * <p><b>Why this exists rather than {@code ps.setX(1, ...)} at each call site.</b> Both fragments in
 * {@link TemporalReads} bind their instant or date <em>twice</em> — once for the lower bound and
 * once inside the {@code OR} for the upper. Counting placeholders by hand across a five-join query
 * is how a boundary ends up bound to the wrong index, and the failure mode is not a crash: the query
 * runs, returns rows, and the rows are the version set as at some other instant. Nothing downstream
 * can tell. {@link #systemTime} and {@link #businessTime} bind the pair as one call, which removes
 * the arithmetic entirely.
 *
 * <p><b>Instants are bound as {@link Timestamp} from {@link Instant}, never as a string.</b> The
 * columns are {@code TIMESTAMPTZ}; a string literal would be interpreted in the session's
 * {@code TimeZone} and a run's visible version set would depend on the server's configuration. The
 * driver binds a {@code Timestamp} as an absolute instant, which is the only reading under which a
 * replay in one time zone reproduces a run performed in another.
 */
final class Params {

    private final PreparedStatement statement;
    private int next = 1;

    Params(PreparedStatement statement) {
        this.statement = Objects.requireNonNull(statement, "statement");
    }

    /** Binds the pair {@link TemporalReads#systemTime} expects, in that order. */
    Params systemTime(Instant recordedAsAt) throws SQLException {
        Objects.requireNonNull(recordedAsAt, "recordedAsAt");
        instant(recordedAsAt);
        instant(recordedAsAt);
        return this;
    }

    /** Binds the pair {@link TemporalReads#businessTime} expects, in that order. */
    Params businessTime(LocalDate businessAsOf) throws SQLException {
        Objects.requireNonNull(businessAsOf, "businessAsOf");
        date(businessAsOf);
        date(businessAsOf);
        return this;
    }

    Params instant(Instant value) throws SQLException {
        statement.setTimestamp(next++, Timestamp.from(Objects.requireNonNull(value, "instant")));
        return this;
    }

    Params date(LocalDate value) throws SQLException {
        statement.setObject(next++, Objects.requireNonNull(value, "date"), Types.DATE);
        return this;
    }

    /**
     * Binds a contract or version id as a {@code UUID}.
     *
     * <p>Parsed here rather than bound as text, and the parse failure is deliberate. The keys in 04
     * are {@code UUID} columns; PostgreSQL will cast a well-formed string for a comparison but
     * refuses a malformed one with a message naming the type, not the contract. Parsing first means
     * a caller that hands over a CBS account number where a contract id was expected — the two are
     * different things, and {@code contract.source_system_ref} is the account number — is told which
     * value was wrong.
     */
    Params contractId(String value) throws SQLException {
        Objects.requireNonNull(value, "id");
        UUID parsed;
        try {
            parsed = UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new PersistenceFailure(
                "'" + value + "' is not a contract id: 04 § 2.1 keys CONTRACT on a UUID, and the"
                    + " CBS account number lives in source_system_ref. Passing an account number"
                    + " here would read as a contract that does not exist, which FR-905 would"
                    + " quarantine as missing data rather than report as the wiring defect it is");
        }
        statement.setObject(next++, parsed);
        return this;
    }

    Params text(String value) throws SQLException {
        statement.setString(next++, Objects.requireNonNull(value, "text"));
        return this;
    }

    Params integer(int value) throws SQLException {
        statement.setInt(next++, value);
        return this;
    }

    /** How many parameters have been bound; the tests check this against the placeholder count. */
    int bound() {
        return next - 1;
    }
}
