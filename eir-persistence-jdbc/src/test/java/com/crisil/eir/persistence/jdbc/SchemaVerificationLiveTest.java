package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The schema, verified by interrogating the catalogue rather than by trusting an exit code.
 *
 * <p><b>Why {@code psql} exiting 0 is not the check.</b> A migration can execute cleanly and produce
 * the wrong schema: 04 § 4 says of the money scale that "a money column that came out NUMERIC(24,2)
 * parses without complaint and silently truncates every working value that passes through it, which
 * is unrecoverable once a period has closed". So the assertions below read
 * {@code information_schema} and {@code pg_class} — precision, scale, and whether a table really is
 * partitioned — and then exercise the check constraints from both sides: a row that violates each,
 * and a row that satisfies it. A constraint only present in the DDL text is not a control until
 * something has been refused by it.
 *
 * <p>Every expected value is written out by hand from 04 § 4 and from V1's and V2's own
 * {@code CHECK} clauses. This class follows the four in {@code eir-persistence}'s test source, which
 * do the same thing over the migration text; what it adds is that these assertions are made against
 * a database that has actually had the migrations applied by Flyway.
 */
@Tag("live-db")
class SchemaVerificationLiveTest {

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = LiveDatabase.dataSource();
        // Seeded because the constraint exercises below need a product and a contract to point at.
        // A foreign key firing before the CHECK a test names would make that test pass without
        // having exercised the constraint at all.
        Fixtures.seed(dataSource);
    }

    @Nested
    @DisplayName("Flyway brings a fresh database up")
    class Migration {

        @Test
        @DisplayName("exactly V1, V2 and V3 are applied, in that order")
        void allThreeMigrationsApply() {
            // V1 and V2 come from the eir-persistence jar; V3 from this module's own resources.
            // Flyway merges classpath locations by version, so the order is a property of the
            // version numbers and not of the jar order.
            assertThat(LiveDatabase.applied())
                .as("a fresh database must reach V3 through the migrations, not by hand")
                .containsExactly("1", "2", "3");
            assertThat(SchemaMigration.appliedVersions(dataSource))
                .as("and the history table must record the same three")
                .containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("a second migrate applies nothing, so start-up is safe to repeat")
        void migrationIsIdempotent() {
            assertThat(SchemaMigration.migrate(dataSource))
                .as("an already-current database must have nothing outstanding")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("the tables exist")
    class Tables {

        @Test
        @DisplayName("the eight core entities of 04 sections 2.1 to 2.6")
        void coreTables() throws SQLException {
            assertThat(baseTables()).contains(
                "product", "contract", "contract_version", "cashflow_schedule", "cashflow_line",
                "fee_rule_set", "fee_posting", "eir_computation");
        }

        @Test
        @DisplayName("the ledger and transition tables of V2")
        void ledgerTables() throws SQLException {
            assertThat(baseTables()).contains(
                "policy_version", "accounting_period", "amortisation_run", "lifecycle_event",
                "period_balance", "journal_entry", "stage_assignment", "suspense_entry",
                "exception", "transition_fair_value", "deemed_eir_derivation");
        }

        @Test
        @DisplayName("the five adapter-owned tables of V3")
        void adapterTables() throws SQLException {
            // Without these, CoreBankingFeed and GeneralLedgerSource cannot be implemented at all
            // and ContractPeriodSource cannot be implemented without deriving the cash split from
            // the engine's own output. V3's header carries the argument.
            assertThat(baseTables()).contains(
                "contract_version_schedule_anchor", "cbs_billed_interest",
                "gl_control_account_balance", "cash_book_application", "suspense_movement",
                "contract_onboarding_attribute");
        }
    }

    @Nested
    @DisplayName("the physical conventions of 04 section 4")
    class PhysicalDesign {

        @Test
        @DisplayName("money is NUMERIC(24,6) on V1, V2 and V3 columns alike")
        void moneyPrecisionAndScale() throws SQLException {
            // Written out by hand: 24 significant digits, six decimals. Six is above presentation
            // scale for every ISO 4217 minor unit, which is the property that lets a working value
            // be stored unrounded.
            for (String[] column : new String[][] {
                {"contract_version", "principal"},
                {"cashflow_line", "amount"},
                {"eir_computation", "opening_carrying_amount"},
                {"period_balance", "closing_gca"},
                {"cbs_billed_interest", "billed_interest"},
                {"gl_control_account_balance", "balance"},
                {"cash_book_application", "applied_to_principal"},
                {"suspense_movement", "recovered"},
                {"contract_onboarding_attribute", "exposure_at_origination"}}) {

                assertThat(numericType(column[0], column[1]))
                    .as("%s.%s must be NUMERIC(24,6); at (24,2) it would truncate every working"
                        + " value silently", column[0], column[1])
                    .containsExactly(24, 6);
            }
        }

        @Test
        @DisplayName("rates are NUMERIC(20,12) on V1, V2 and V3 columns alike")
        void ratePrecisionAndScale() throws SQLException {
            // Twelve decimal places is the storage policy of 03 § 1 and the reason reference case
            // 1's periodic EIR is quoted as 0.010421491800 rather than 0.0104215.
            for (String[] column : new String[][] {
                {"contract_version", "contractual_rate"},
                {"eir_computation", "rate_periodic"},
                {"eir_computation", "rate_effective_annual"},
                {"period_balance", "rate_periodic_used"},
                {"contract_version_schedule_anchor", "step_factor"}}) {

                assertThat(numericType(column[0], column[1]))
                    .as("%s.%s must be NUMERIC(20,12)", column[0], column[1])
                    .containsExactly(20, 12);
            }
        }

        @Test
        @DisplayName("the three range-partitioned tables really are partitioned")
        void partitionedTablesReportRelkindP() throws SQLException {
            // relkind 'p' is a partitioned table; 'r' is an ordinary one. A partitioned table that
            // came out ordinary would execute every statement in V1 and V2 identically and lose the
            // pruning that keeps a close-period query tractable, the per-period read-only switch,
            // and the detach-and-archive retention step.
            assertThat(relkind("cashflow_line")).isEqualTo("p");
            assertThat(relkind("period_balance")).isEqualTo("p");
            assertThat(relkind("journal_entry")).isEqualTo("p");
            // And a control: a table that is NOT partitioned must not report 'p', or the assertion
            // above would pass for the wrong reason.
            assertThat(relkind("contract")).isEqualTo("r");
            assertThat(relkind("cbs_billed_interest")).isEqualTo("r");
        }

        @Test
        @DisplayName("the generated columns are generated, not writable")
        void invariantFourIsAGeneratedColumn() throws SQLException {
            // V2 makes INV-4 impossible to represent a violation of rather than merely checked
            // afterwards. If these came out as ordinary columns the invariant would be back to
            // being a sweep, and an accumulator can drift from the balances it describes.
            assertThat(isGenerated("period_balance", "unamortised_fee")).isTrue();
            assertThat(isGenerated("period_balance", "fee_amortised")).isTrue();
            assertThat(isGenerated("period_balance", "closing_gca")).isFalse();
        }
    }

    @Nested
    @DisplayName("check constraints refuse and admit")
    class Constraints {

        @Test
        @DisplayName("an asset at amortised cost with no SPPI assessment is refused")
        void amortisedCostNeedsSppi() {
            // V1's contract_amortised_cost_needs_sppi_ck, and its comment records that the first
            // live run of the migration accepted exactly this row, because `sppi_outcome = 'PASS'`
            // is NULL — and therefore satisfied — when the column is NULL.
            assertThatThrownBy(() -> attempt("""
                INSERT INTO contract (contract_id, source_system_ref, entity_id, product_id,
                    currency, measurement_category, instrument_class, is_poci, materiality_tier,
                    tier_basis, initial_recognition_date, book_id)
                VALUES ('90000000-0000-4000-8000-000000000001', 'CBS-BAD-1', 'ENT-01', '%s', 'INR',
                    'AMORTISED_COST', 'LOAN', false, 1, 'basis', DATE '2026-04-01', 'MAIN')
                """.formatted(Fixtures.PRODUCT_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("contract_amortised_cost_needs_sppi_ck");

            assertThatCode(() -> attempt("""
                INSERT INTO contract (contract_id, source_system_ref, entity_id, product_id,
                    currency, measurement_category, instrument_class, sppi_outcome,
                    sppi_assessed_on, sppi_approver, is_poci, materiality_tier, tier_basis,
                    initial_recognition_date, book_id)
                VALUES ('90000000-0000-4000-8000-000000000002', 'CBS-OK-1', 'ENT-01', '%s', 'INR',
                    'AMORTISED_COST', 'LOAN', 'PASS', DATE '2026-03-31', 'checker.one', false, 1,
                    'basis', DATE '2026-04-01', 'MAIN')
                """.formatted(Fixtures.PRODUCT_ID)))
                .as("the same row with the assessment on file must be accepted")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("V3 refuses a negative cash application and admits a non-negative one")
        void cashApplicationMagnitudes() {
            // ck_cash_book_application_magnitudes. A negative interest application and a positive
            // principal application net to the same cash_received while describing a completely
            // different ledger, and only one of the two ties to the cash book.
            assertThatThrownBy(() -> attempt("""
                INSERT INTO cash_book_application (application_id, contract_id, period_id, book_id,
                    applied_to_principal, applied_to_interest, source_ref, recorded_at)
                VALUES ('90000000-0000-4000-8000-000000000003', '%s', 202703, 'CHECK-BOOK',
                    '1000.000000', '-1.000000', 'CB-BAD', TIMESTAMPTZ '2027-05-01 10:00:00+00')
                """.formatted(Fixtures.CONTRACT_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_cash_book_application_magnitudes");

            assertThatCode(() -> attempt("""
                INSERT INTO cash_book_application (application_id, contract_id, period_id, book_id,
                    applied_to_principal, applied_to_interest, source_ref, recorded_at)
                VALUES ('90000000-0000-4000-8000-000000000004', '%s', 202703, 'CHECK-BOOK',
                    '1000.000000', '1.000000', 'CB-OK', TIMESTAMPTZ '2027-05-01 10:00:00+00')
                """.formatted(Fixtures.CONTRACT_ID)))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("V3 refuses two CBS figures visible at the same instant")
        void oneVisibleCbsFigurePerContractPeriod() {
            // ex_cbs_billed_interest_one_visible. Two overlapping rows would make RC-1 compare
            // against whichever the planner returned first — a reconciliation whose answer depends
            // on a query plan.
            String first = """
                INSERT INTO cbs_billed_interest (feed_line_id, contract_id, period_id,
                    billed_interest, feed_reference, recorded_at, superseded_at)
                VALUES ('90000000-0000-4000-8000-000000000005', '%s', 202612, '100.000000',
                    'CBS-A', TIMESTAMPTZ '2027-01-01 00:00:00+00', NULL)
                """.formatted(Fixtures.CONTRACT_ID);
            String overlapping = """
                INSERT INTO cbs_billed_interest (feed_line_id, contract_id, period_id,
                    billed_interest, feed_reference, recorded_at, superseded_at)
                VALUES ('90000000-0000-4000-8000-000000000006', '%s', 202612, '200.000000',
                    'CBS-B', TIMESTAMPTZ '2027-02-01 00:00:00+00', NULL)
                """.formatted(Fixtures.CONTRACT_ID);
            String nonOverlapping = """
                INSERT INTO cbs_billed_interest (feed_line_id, contract_id, period_id,
                    billed_interest, feed_reference, recorded_at, superseded_at)
                VALUES ('90000000-0000-4000-8000-000000000007', '%s', 202611, '200.000000',
                    'CBS-C', TIMESTAMPTZ '2027-02-01 00:00:00+00', NULL)
                """.formatted(Fixtures.CONTRACT_ID);

            assertThatThrownBy(() -> attempt(first, overlapping))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ex_cbs_billed_interest_one_visible");
            assertThatCode(() -> attempt(first, nonOverlapping))
                .as("a different period is a different key and must be accepted")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("V3 refuses a first due date on or before the disbursement date")
        void anchorDatesMustBeOrdered() {
            // ck_contract_version_schedule_anchor_first_due, mirroring ContractTerms' own refusal.
            // A first due date at t = 0 would put an instalment on the anchor, where FlowVector
            // treats it as part of the inception net amount rather than as a discounted flow.
            // The fixture anchors every version, so the existing row is removed first inside the
            // same rolled-back transaction. Without that the primary key would fire before the
            // CHECK and the test would pass without exercising the constraint it names.
            assertThatThrownBy(() -> attempt(
                "DELETE FROM contract_version_schedule_anchor WHERE contract_version_id = '"
                    + Fixtures.FVTPL_VERSION_ID + "'",
                """
                INSERT INTO contract_version_schedule_anchor (contract_version_id,
                    disbursement_date, first_due_date, term_periods)
                VALUES ('%s', DATE '2026-04-01', DATE '2026-04-01', 24)
                """.formatted(Fixtures.FVTPL_VERSION_ID)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_contract_version_schedule_anchor_first_due");
        }
    }

    // ---- catalogue helpers -----------------------------------------------------------------

    private static List<String> baseTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT table_name FROM information_schema.tables
                  WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                 """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }
        return tables;
    }

    private static List<Integer> numericType(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT numeric_precision, numeric_scale
                   FROM information_schema.columns
                  WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                 """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no column " + table + "." + column);
                }
                return List.of(rs.getInt("numeric_precision"), rs.getInt("numeric_scale"));
            }
        }
    }

    private static String relkind(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT c.relkind FROM pg_class c
                   JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'public' AND c.relname = ?
                 """)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no relation " + table);
                }
                return rs.getString("relkind");
            }
        }
    }

    private static boolean isGenerated(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT is_generated FROM information_schema.columns
                  WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                 """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no column " + table + "." + column);
                }
                return "ALWAYS".equals(rs.getString("is_generated"));
            }
        }
    }

    /**
     * Runs statements in a transaction that is always rolled back.
     *
     * <p>So that a row admitted by a constraint does not survive to change what another test reads.
     * A satisfying insert left behind would, for instance, put a second visible CBS figure in the
     * fixture and the bitemporal suite would then be reading a book this class had edited.
     */
    private static void attempt(String... sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String each : sql) {
                    statement.execute(each);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
