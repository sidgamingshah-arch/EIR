package com.crisil.eir.persistence.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A second, isolated migrated database, for fixtures that must not be visible to the shared one.
 *
 * <h2>Why a whole separate database and not more rows in {@link Fixtures}</h2>
 *
 * <p>One of the defects this exists to exercise is that
 * {@code JdbcContractSource.SELECT_POPULATION} carries no {@code book_id} predicate although the
 * adapter holds a book. Demonstrating it needs a contract on a <em>second</em> book — and seeding
 * one into the shared fixture would enlarge the population every other live test reads, so
 * {@code RemainingPortsLiveTest} and {@code BatchCloseLiveTest} would go red **because of the
 * defect**. That is a true and useless outcome: a suite that is red for a recorded reason teaches
 * nobody anything and stops being run.
 *
 * <p>So the cross-book rows live here, in {@code <configured database>_latent}, and the shared
 * fixture is left exactly as it is. The isolation is the point: these tests assert what the adapters
 * do with awkward data, and the other classes assert what they do with ordinary data. Neither should
 * be able to move the other.
 *
 * <p><b>A separate database rather than a separate schema.</b> {@code LiveDatabase.rebuildSchema}
 * explains that {@code btree_gist}'s operator classes are schema-scoped and V1's, V2's and V3's
 * {@code EXCLUDE} constraints need them resolvable — so a second schema would need its own extension
 * and a {@code search_path} that every connection honoured. A second database is one
 * {@code CREATE DATABASE} and no ambiguity.
 */
final class LatentDefectDatabase {

    private static DataSource dataSource;

    private LatentDefectDatabase() {
    }

    /** The isolated migrated database, created and migrated on first use. */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            String configured = System.getProperty("eir.jdbc.url", "");
            if (configured.isBlank()) {
                throw new IllegalStateException(
                    "the live-db group was switched on with no eir.jdbc.url; see LiveDatabase");
            }
            String isolated = configured + "_latent";
            createDatabase(configured, databaseName(isolated));
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(isolated);
            source.setUser(System.getProperty("eir.jdbc.user", "postgres"));
            String password = System.getProperty("eir.jdbc.password", "");
            if (!password.isEmpty()) {
                source.setPassword(password);
            }
            rebuild(source);
            SchemaMigration.migrate(source);
            dataSource = source;
        }
        return dataSource;
    }

    /**
     * Creates the isolated database if it is not there, through a connection to the configured one.
     *
     * <p>{@code CREATE DATABASE} cannot run inside a transaction, which is why this uses a plain
     * connection at its default autocommit rather than {@code JdbcAdapter.open}'s read-only,
     * autocommit-off one. Existence is checked first rather than relying on an error, because
     * "already exists" and "you may not create databases" are different problems and only the second
     * should stop the suite.
     */
    private static void createDatabase(String configuredUrl, String name) {
        try (Connection connection = connect(configuredUrl);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                if (rs.next()) {
                    return;
                }
            }
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "could not create the isolated database " + name + " on the live cluster. These"
                    + " tests need a database of their own: they seed a second accounting book, and"
                    + " putting that into the shared fixture would turn every other live test red"
                    + " for a defect that is already recorded", e);
        }
    }

    private static Connection connect(String url) throws SQLException {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(System.getProperty("eir.jdbc.user", "postgres"));
        String password = System.getProperty("eir.jdbc.password", "");
        if (!password.isEmpty()) {
            source.setPassword(password);
        }
        return source.getConnection();
    }

    private static void rebuild(DataSource source) {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException("could not rebuild the isolated schema", e);
        }
    }

    /** The database name from a JDBC URL — everything after the last slash, minus any query. */
    private static String databaseName(String url) {
        String tail = url.substring(url.lastIndexOf('/') + 1);
        int query = tail.indexOf('?');
        return query < 0 ? tail : tail.substring(0, query);
    }
}
