package com.crisil.eir.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The live PostgreSQL 16 cluster the {@code @Tag("live-db")} suite runs against.
 *
 * <p><b>Why these tests are a separate group rather than skipped.</b> A test that quietly skips when
 * a database is absent reports green on a machine where nothing was verified, and the property under
 * test here — that a read as at an earlier instant returns the earlier version set — is the whole
 * claim of the module. So the group is <em>excluded</em> by surefire configuration on the default
 * build (it is not counted at all, and nothing is reported as skipped), and switched on explicitly
 * with a URL:
 *
 * <pre>
 *   mvn -Pjdbc -pl eir-persistence-jdbc test \
 *       -Deir.surefire.excludedGroups=none \
 *       -Deir.jdbc.url=jdbc:postgresql://127.0.0.1:5443/postgres \
 *       -Deir.jdbc.user=postgres
 * </pre>
 *
 * <p>If the group is switched on and no URL is supplied, the tests fail rather than pass. Asking for
 * the live suite and getting a green run without a cluster is the outcome that must not be possible.
 *
 * <h2>The schema is dropped and rebuilt once per JVM, through Flyway</h2>
 *
 * <p>Which is also the "a fresh database can be brought up" check: {@link #applied()} carries the
 * versions Flyway reported applying, and the migration suite asserts they are exactly 1, 2 and 3.
 * Dropping happens here, in a test, and not through Flyway's {@code clean} — which
 * {@link SchemaMigration} deliberately leaves disabled, because a production database holding a closed
 * period's published figures must not have a callable "drop everything".
 */
final class LiveDatabase {

    private static DataSource dataSource;
    private static List<String> applied;

    private LiveDatabase() {
    }

    /** The migrated database, built on first use. */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(require("eir.jdbc.url"));
            source.setUser(property("eir.jdbc.user", "postgres"));
            String password = property("eir.jdbc.password", "");
            if (!password.isEmpty()) {
                source.setPassword(password);
            }
            rebuildSchema(source);
            applied = SchemaMigration.migrate(source);
            dataSource = source;
        }
        return dataSource;
    }

    /** The migration versions Flyway applied when the schema was rebuilt. */
    static synchronized List<String> applied() {
        dataSource();
        return applied;
    }

    private static void rebuildSchema(DataSource source) {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            // btree_gist is database-scoped and its operator classes live in whichever schema it was
            // created in. Dropping and recreating `public` and then migrating into it keeps the
            // extension and the tables that need its gist operators in one schema, which is what
            // makes the EXCLUDE constraints in V1, V2 and V3 resolvable.
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException(
                "could not rebuild the public schema on the live cluster; the live-db suite needs a"
                    + " database it owns, not a shared one", e);
        }
    }

    private static String require(String key) {
        String value = System.getProperty(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException(
                "the live-db test group was switched on with no " + key + ". These tests verify the"
                    + " one property this module exists for — a read as at an earlier instant"
                    + " returning the earlier version set — and passing without a cluster would"
                    + " report that verification as done when nothing ran");
        }
        return value;
    }

    private static String property(String key, String fallback) {
        String value = System.getProperty(key, "");
        return value.isBlank() ? fallback : value;
    }
}
