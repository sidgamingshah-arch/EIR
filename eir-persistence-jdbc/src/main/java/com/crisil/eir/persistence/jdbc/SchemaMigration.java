package com.crisil.eir.persistence.jdbc;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Bringing a fresh database up to the schema the adapters read.
 *
 * <p>Flyway rather than a hand-rolled runner, and the module pays for it in ADR-0010's framework
 * exemption (named in this module's pom). What it buys is the one property a hand-rolled runner does
 * not have for free: a schema history table, so that "which migrations has this database had" is a
 * question with an answer, and a checksum per migration, so that an edit to an already-applied file
 * fails the next deployment rather than diverging two environments silently. For a schema whose whole
 * purpose is that a closed period can be reproduced years later, a database whose version is
 * unknowable is not an option.
 *
 * <h2>Migrations come from the classpath, across two modules</h2>
 *
 * <p>{@code classpath:db/migration} resolves to V1 and V2 in the {@code eir-persistence} jar — the
 * verified DDL of 04, which this module depends on and does not modify — plus V3 in this module's own
 * resources. Flyway merges locations by version, so the order is V1, V2, V3 regardless of which jar a
 * file came from.
 *
 * <p>A filesystem path was the alternative and is rejected: it works on a developer's machine and not
 * in a deployed jar, and the failure is at deployment time on a database that is then half-migrated.
 *
 * <h2>Clean is left disabled</h2>
 *
 * <p>Flyway 10 disables {@code clean} by default and this class does not enable it. A production
 * database holding a closed period's published figures must not have a callable "drop everything";
 * V2 goes to the length of a trigger — {@code ledger_reject_reopen_of_closed_period} — to make CLOSED
 * terminal, and handing back a method that drops the table it protects would make that trigger
 * decorative. Tests that need an empty database drop and recreate the schema themselves, which is a
 * decision taken in a test and not a capability shipped in main.
 */
public final class SchemaMigration {

    /** Where V1, V2 and V3 are found; see the class javadoc on why this is a classpath location. */
    public static final String MIGRATION_LOCATION = "classpath:db/migration";

    private SchemaMigration() {
    }

    /**
     * Applies every outstanding migration and returns the versions that were applied.
     *
     * @param dataSource the target database
     * @return the applied versions in order, e.g. {@code [1, 2, 3]} on a fresh database and
     *     {@code []} on one already current — which is the property that makes calling this at
     *     start-up safe
     */
    public static List<String> migrate(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        MigrateResult result = flyway(dataSource).migrate();
        return result.migrations.stream().map(m -> m.version).toList();
    }

    /**
     * The versions this database has recorded as applied.
     *
     * <p>Separate from {@link #migrate} so that a caller can assert the state of a database without
     * changing it — which is what a start-up check should do in an environment where migration is a
     * release step rather than an application concern.
     */
    public static List<String> appliedVersions(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        MigrationInfo[] applied = flyway(dataSource).info().applied();
        return java.util.Arrays.stream(applied)
            .filter(info -> info.getVersion() != null)
            .map(info -> info.getVersion().getVersion())
            .toList();
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            // V1 and V2 predate this wiring and were applied by hand in every environment that has
            // them today. baselineOnMigrate would let Flyway adopt such a database by stamping a
            // baseline — and it is deliberately NOT set, because stamping a baseline records
            // "everything before this is assumed present" without checking, and the one thing this
            // schema cannot tolerate is an environment whose DDL is assumed rather than known. A
            // database with the tables and no history table is migrated explicitly, once, by a
            // release step that knows what it is adopting.
            .baselineOnMigrate(false)
            // Out-of-order migration off: a V3 applied before a V2 would leave
            // contract_version_schedule_anchor's foreign key pointing at a table that does not exist
            // yet, and Flyway would report success on the ones that did apply.
            .outOfOrder(false)
            .validateOnMigrate(true)
            .load();
    }
}
