package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import java.util.Objects;

/**
 * The two temporal predicates of 04 § 5, as SQL, in one place.
 *
 * <p><b>This class is the reason this module exists.</b> {@code eir-api} implements the same seven
 * ports over a {@code java.util.Map}, and its own javadoc concedes the consequence: "a map holds one
 * version of each row, so this book answers the same thing at every boundary". A replay against it
 * reproduces, and reproducing is not evidence — the run would reproduce just as well if
 * {@link AsAtBoundary#recordedAsAt()} were ignored entirely, which is exactly the defect invariant
 * DT-1 exists to detect and cannot detect against a source that has no history.
 *
 * <p>So every read in this module carries <b>both</b> fragments below, and the tests assert that
 * they do. The two answer different questions and neither substitutes for the other:
 *
 * <ul>
 *   <li><b>Business time</b> — {@code valid_from} / {@code valid_to}. <em>When were these terms
 *       true of the contract?</em> Selected by {@link AsAtBoundary#businessAsOf()}, the period end.
 *   <li><b>Decision time</b> — {@code recorded_at} / {@code superseded_at}. <em>When did the engine
 *       believe them?</em> Selected by {@link AsAtBoundary#recordedAsAt()}, the system-time
 *       boundary.
 * </ul>
 *
 * <p><b>The case that makes them both necessary</b>, and it is 04 § 5's own: a backdated amendment
 * is one row with an <em>earlier</em> {@code valid_from} and a <em>current</em> {@code recorded_at}.
 * A query filtering only on business time sees the amendment when replaying a closed period and the
 * replay disagrees with the published figures. A query filtering only on decision time sees the
 * pre-amendment terms for every business date including the ones the amendment covers. Only the
 * conjunction reproduces the closed period and shows the amendment in the current one.
 *
 * <h2>Why explicit comparisons rather than range containment</h2>
 *
 * <p>PostgreSQL would express the same thing more briefly as
 * {@code daterange(valid_from, valid_to) @> ?::date}, and that is what the DDL's own exclusion
 * constraints use. It is not used here, on purpose. V1 creates
 * {@code contract_version_business_time_ix} on {@code (contract_id, valid_from, valid_to)} and
 * {@code contract_version_system_time_ix} on {@code (contract_id, recorded_at)} precisely so these
 * lookups are index-driven at 10,000,000 contracts; a per-row range constructor is not a b-tree
 * predicate and would leave both indexes unused. The cost is that each fragment binds its parameter
 * twice, which {@link Params#systemTime} and {@link Params#businessTime} handle so that no caller
 * has to count placeholders.
 *
 * <h2>Half-open intervals, at both ends</h2>
 *
 * <p>{@code valid_to > ?} and {@code superseded_at > ?}, never {@code >=}. The upper bound is
 * exclusive because that is how the DDL's own {@code daterange(valid_from, valid_to)} exclusion
 * constraint reads it — {@code '[)'} — and a read using {@code >=} where the constraint uses
 * {@code '[)'} would see two versions on the single day a version ends and its successor begins.
 * Two rows for one contract at one business date is not a break the engine can report: it is a
 * figure that depends on which row the planner returned first.
 *
 * <p>The lower bounds are inclusive ({@code <=}) for the mirror reason: a version whose
 * {@code valid_from} is the period end IS true on the period end, and a fact recorded at exactly the
 * boundary instant WAS known at the boundary. Making the lower bound exclusive would make a run
 * recorded at its own boundary instant unable to see its own inputs.
 */
public final class TemporalReads {

    private TemporalReads() {
    }

    /**
     * Business-time visibility for a table carrying {@code valid_from} / {@code valid_to}.
     *
     * <p>Binds two parameters, both {@link AsAtBoundary#businessAsOf()}; use
     * {@link Params#businessTime}.
     *
     * @param alias the table alias the columns are qualified by
     */
    public static String businessTime(String alias) {
        String a = requireAlias(alias);
        return a + ".valid_from <= ? AND (" + a + ".valid_to IS NULL OR " + a + ".valid_to > ?)";
    }

    /**
     * Decision-time visibility for a table carrying {@code recorded_at} / {@code superseded_at}.
     *
     * <p>Binds two parameters, both {@link AsAtBoundary#recordedAsAt()}; use
     * {@link Params#systemTime}.
     *
     * <p>{@code superseded_at IS NULL} means "this is what the engine currently believes", so a
     * live boundary and a boundary set to any instant after the row was recorded both see it. That
     * is what makes the same query serve a live run and a replay with no branch in the Java —
     * a branch being the place a replay would quietly stop being a replay.
     *
     * @param alias the table alias the columns are qualified by
     */
    public static String systemTime(String alias) {
        String a = requireAlias(alias);
        return a + ".recorded_at <= ? AND (" + a + ".superseded_at IS NULL OR "
            + a + ".superseded_at > ?)";
    }

    /**
     * Decision-time visibility where the system-time column is not called {@code recorded_at}.
     *
     * <p>{@code stage_assignment.received_at} is the case V2 names: "received_at is when the engine
     * learned it (system time, 04 § 5) and is supplied, not defaulted". There is no supersession
     * column on that table — the natural key {@code (contract_id, period_id)} is unique — so
     * visibility is the one-sided bound, and that asymmetry is stated here rather than being
     * discovered by a reader wondering why one query looks different.
     *
     * @param alias  the table alias
     * @param column the system-time column on that table
     */
    public static String recordedNoLaterThan(String alias, String column) {
        return requireAlias(alias) + "." + requireAlias(column) + " <= ?";
    }

    private static String requireAlias(String value) {
        Objects.requireNonNull(value, "alias");
        // Concatenated into SQL, so it must not be able to carry any. These are compile-time
        // constants at every call site today; the check is here so that they stay that way — a
        // caller that ever passes a column name in from a row would otherwise have written an
        // injection point into the one module that talks to the database.
        if (!value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                "SQL identifier '" + value + "' is not a bare lower-case name; identifiers here are"
                    + " concatenated into SQL and must be compile-time constants, never values read"
                    + " from a row");
        }
        return value;
    }
}
