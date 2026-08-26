package com.crisil.eir.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Structural assertions over {@code V2__ledger_and_transition.sql}.
 *
 * <p><b>What this test is and is not.</b> The migration is verified <em>by execution</em> against a
 * real PostgreSQL 16 cluster — that is the check that proves the schema is what 04 specifies, and
 * the module README carries the recipe. This test is the part of that verification which can run in
 * a Maven build with no cluster and no driver: it reads the DDL as text and asserts the properties
 * that are true of the file itself.
 *
 * <p>That is a narrower claim than "the schema is correct", and worth stating plainly. A text
 * assertion cannot tell you that a partition was created or that a constraint fires. What it can
 * tell you is that a money column did not come out {@code NUMERIC(24,2)}, that no banned numeric
 * type crept in, and that the ten exception categories are all still there — which are precisely
 * the defects that parse cleanly, execute cleanly, and are wrong.
 *
 * <p>Every expected value here is written out from 04 and from ADR-0002, never read back out of the
 * file under test. A test that derives its expectation from its subject asserts only that the file
 * equals itself.
 */
@DisplayName("V2 ledger and transition DDL")
class LedgerMigrationDdlTest {

    /** The raw migration text, comments and all. */
    private static String ddl;

    /**
     * The migration with {@code --} comments removed. Almost every assertion runs against this
     * rather than the raw text: the file's prose deliberately names the banned types in order to
     * explain why they are banned, and a check that cannot tell a ban from its rationale is a
     * check that has to be silenced.
     */
    private static String executable;

    @BeforeAll
    static void readMigration() throws IOException {
        try (InputStream in =
                LedgerSchema.class.getResourceAsStream(LedgerSchema.MIGRATION_RESOURCE)) {
            assertThat(in)
                    .as("migration resource %s is on the classpath",
                            LedgerSchema.MIGRATION_RESOURCE)
                    .isNotNull();
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        executable = stripLineComments(ddl);
    }

    /**
     * Removes {@code --} comments, ignoring a {@code --} that falls inside a single-quoted
     * string.
     *
     * <p>The quote awareness is not hypothetical tidiness: {@code COMMENT ON} text is a string
     * literal and prose acquires dashes. A naive cut at the first {@code --} would silently drop
     * the remainder of such a line from {@code executable}, and the checks that run against it are
     * bans — so the blinding would make them pass, not fail. A check that fails open is worse than
     * one that is absent, because it reports a green.
     *
     * <p>Doubled quotes ({@code ''}) inside a literal need no special handling here: each one
     * toggles the flag twice and leaves it where it was.
     */
    private static String stripLineComments(String sql) {
        List<String> kept = new ArrayList<>();
        for (String line : sql.split("\n", -1)) {
            boolean inString = false;
            int cut = -1;
            for (int i = 0; i < line.length() && cut < 0; i++) {
                char c = line.charAt(i);
                if (c == '\'') {
                    inString = !inString;
                } else if (!inString && c == '-' && i + 1 < line.length()
                        && line.charAt(i + 1) == '-') {
                    cut = i;
                }
            }
            kept.add(cut < 0 ? line : line.substring(0, cut));
        }
        return String.join("\n", kept);
    }

    @Nested
    @DisplayName("ADR-0002 numeric discipline")
    class NumericDiscipline {

        @Test
        @DisplayName("no banned numeric type appears in the executable DDL")
        void noBannedNumericTypes() {
            for (String token : LedgerSchema.BANNED_SQL_TYPE_TOKENS) {
                Pattern wholeWord =
                        Pattern.compile("\\b" + Pattern.quote(token) + "\\b",
                                Pattern.CASE_INSENSITIVE);
                assertThat(wholeWord.matcher(executable).find())
                        .as("ADR-0002: SQL type token '%s' must not appear in the DDL", token)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("every NUMERIC column is either money (24,6) or a rate (20,12)")
        void onlyTwoNumericShapes() {
            List<String> shapes = new ArrayList<>();
            Matcher m = Pattern.compile("NUMERIC\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
                    .matcher(executable);
            while (m.find()) {
                shapes.add(m.group(1) + "," + m.group(2));
            }

            assertThat(shapes)
                    .as("the DDL declares NUMERIC columns at all")
                    .isNotEmpty();
            assertThat(shapes)
                    .as("04 s4 allows exactly two numeric shapes: money (24,6) and rates "
                            + "(20,12). A (24,2) money column parses fine and silently "
                            + "truncates working precision.")
                    .containsOnly(
                            LedgerSchema.MONEY_PRECISION + "," + LedgerSchema.MONEY_SCALE,
                            LedgerSchema.RATE_PRECISION + "," + LedgerSchema.RATE_SCALE);
            assertThat(shapes)
                    .contains(LedgerSchema.MONEY_PRECISION + "," + LedgerSchema.MONEY_SCALE)
                    .contains(LedgerSchema.RATE_PRECISION + "," + LedgerSchema.RATE_SCALE);
        }

        /**
         * Named money columns, spot-checked individually. The shape assertion above would still
         * pass if a money column had been declared as a rate — both shapes are permitted — so the
         * columns whose scale actually carries an accounting meaning are named here.
         */
        @Test
        @DisplayName("the balance and allowance columns are money, not rates")
        void moneyColumnsAreMoney() {
            List<String> moneyColumns = List.of(
                    "opening_gca",
                    "closing_gca",
                    "opening_contractual",
                    "closing_contractual",
                    "eir_interest",
                    "contractual_interest",
                    "fee_amortised",
                    "unamortised_fee",
                    "cash_received",
                    "allowance",
                    "shadow_unwind",
                    "net_basis_interest",
                    "recognised_interest_income",
                    "suspense_movement",
                    "ecl_pre_floor",
                    "ecl_post_floor",
                    "basis_adjustment_amortised",
                    "contractual_interest_suspended",
                    "released_on_recovery",
                    "written_off",
                    "pre_transition_carrying_amount",
                    "fair_value",
                    "difference_to_retained_earnings",
                    "materiality_threshold_amount");
            for (String column : moneyColumns) {
                assertThat(declaredTypeOf(column))
                        .as("%s is a money column and must be NUMERIC(24,6)", column)
                        .isEqualTo("NUMERIC(24,6)");
            }
        }

        @Test
        @DisplayName("the rate columns carry the 12dp storage policy")
        void rateColumnsAreRates() {
            List<String> rateColumns = List.of(
                    "original_eir_used",
                    "ten_percent_test_ratio",
                    "rate_periodic_used",
                    "pool_eir",
                    "backtest_variance",
                    "backtest_threshold",
                    "materiality_threshold_rate",
                    "discount_rate_used",
                    "deemed_rate_periodic",
                    "deemed_rate_effective_annual",
                    "deemed_rate_nominal_annual",
                    "rate_used");
            for (String column : rateColumns) {
                assertThat(declaredTypeOf(column))
                        .as("%s is a rate column and must be NUMERIC(20,12)", column)
                        .isEqualTo("NUMERIC(20,12)");
            }
        }

        /**
         * Finds the declared numeric type of a column, taking the first declaration. Columns of
         * the same name recur across tables (every {@code allowance} is money), and the shape
         * assertion above already guarantees no column of any name carries a third shape.
         */
        private String declaredTypeOf(String column) {
            Matcher m = Pattern.compile(
                            "\\n\\s+" + Pattern.quote(column)
                                    + "\\s+(NUMERIC\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\))")
                    .matcher(executable);
            if (!m.find()) {
                // Generated columns declare their type on the column line and their expression on
                // the next, which the pattern above already covers; a miss means the column is
                // absent.
                return "<not declared>";
            }
            return m.group(1).replaceAll("\\s+", "");
        }
    }

    @Nested
    @DisplayName("04 s4 physical design")
    class PhysicalDesign {

        @Test
        @DisplayName("PERIOD_BALANCE and JOURNAL_ENTRY are range-partitioned by period_id")
        void ledgerTablesArePartitioned() {
            for (String table : LedgerSchema.PARTITIONED_TABLES) {
                assertThat(executable)
                        .as("%s must be range-partitioned by period_id (04 s4)", table)
                        .containsPattern("CREATE TABLE " + table + "\\s*\\((?s).*?\\)\\s*"
                                + "PARTITION BY RANGE \\(period_id\\)");
            }
        }

        @Test
        @DisplayName("the first ACPIR period has a concrete partition on both ledger tables")
        void firstAcpirPeriodPartitionExists() {
            int first = LedgerSchema.FIRST_ACPIR_PERIOD_ID;
            int next = 202705;
            for (String table : LedgerSchema.PARTITIONED_TABLES) {
                assertThat(executable)
                        .as("a concrete partition for period %d on %s", first, table)
                        .contains("CREATE TABLE " + LedgerSchema.partitionName(table, first))
                        .containsPattern("PARTITION OF " + table + "\\s+FOR VALUES FROM \\("
                                + first + "\\) TO \\(" + next + "\\)");
            }
        }

        @Test
        @DisplayName("both ledger tables carry a DEFAULT partition as a safety net")
        void defaultPartitionsExist() {
            for (String table : LedgerSchema.PARTITIONED_TABLES) {
                assertThat(executable)
                        .contains("CREATE TABLE " + LedgerSchema.defaultPartitionName(table));
            }
        }

        @Test
        @DisplayName("the three index shapes 04 s4 names are all present")
        void requiredIndexShapes() {
            assertThat(executable)
                    .as("(contract_id, period_id) - the trace path")
                    .containsPattern("ON period_balance \\(contract_id, period_id\\)");
            assertThat(executable)
                    .as("(period_id, product_id) - reporting rollups")
                    .containsPattern("ON period_balance \\(period_id, product_id\\)");
            assertThat(executable)
                    .as("(run_id, status) - run monitoring")
                    .containsPattern("ON amortisation_run \\(run_id, status\\)");
            assertThat(executable)
                    .as("(run_id, status) on the exception queue, where the selectivity matters")
                    .containsPattern("ON exception \\(raised_by_run_id, status\\)");
        }

        @Test
        @DisplayName("a closed period's ledger partitions are made read-only")
        void closedPeriodIsReadOnly() {
            assertThat(executable)
                    .as("the row-level guard exists")
                    .contains("CREATE FUNCTION ledger_reject_write_to_closed_period()")
                    .contains("read_only_sql_transaction");
            for (String table : LedgerSchema.PARTITIONED_TABLES) {
                assertThat(executable)
                        .as("the guard is attached to %s", table)
                        .containsPattern(
                                "BEFORE INSERT OR UPDATE OR DELETE ON " + table + "\\s+"
                                        + "FOR EACH ROW EXECUTE FUNCTION "
                                        + "ledger_reject_write_to_closed_period\\(\\)");
            }
            assertThat(executable)
                    .as("the close revokes write privileges on the period's partitions too")
                    .contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON");
        }

        @Test
        @DisplayName("CLOSED cannot be un-set, or the read-only guard is one UPDATE from off")
        void closedIsTerminalOnItsOwnRow() {
            assertThat(executable)
                    .as("the ledger guard reads accounting_period.status, so that row has to be "
                            + "frozen too (FR-902)")
                    .contains("CREATE FUNCTION ledger_reject_reopen_of_closed_period()")
                    .containsPattern("BEFORE UPDATE OR DELETE ON accounting_period\\s+"
                            + "FOR EACH ROW EXECUTE FUNCTION "
                            + "ledger_reject_reopen_of_closed_period\\(\\)");
        }

        @Test
        @DisplayName("a ledger row cannot be stamped with a period other than its run's")
        void ledgerRowsAgreeWithTheirRun() {
            assertThat(executable)
                    .as("the unique constraint the composite foreign keys point at")
                    .contains("UNIQUE (run_id, period_id)");
            for (String table : List.of("fk_period_balance_run_period",
                    "fk_journal_entry_run_period")) {
                assertThat(executable)
                        .as("%s is composite on (run_id, period_id)", table)
                        .containsPattern("CONSTRAINT " + table + "\\s+"
                                + "FOREIGN KEY \\(run_id, period_id\\)\\s+"
                                + "REFERENCES amortisation_run \\(run_id, period_id\\)");
            }
        }

        @Test
        @DisplayName("at most one policy version of a kind is in force on any date")
        void policyVersionsDoNotOverlap() {
            assertThat(executable)
                    .as("an EXCLUDE constraint rather than a trigger: a trigger cannot see an "
                            + "uncommitted concurrent insert")
                    .contains("EXCLUDE USING gist")
                    .containsPattern("daterange\\(effective_from, effective_to, '\\[\\)'\\) "
                            + "WITH &&")
                    .contains("CREATE EXTENSION IF NOT EXISTS btree_gist");
        }

        @Test
        @DisplayName("four-eyes comparisons are trimmed and case-folded")
        void fourEyesCannotBeDefeatedByWhitespace() {
            assertThat(executable)
                    .as("'alice' checked by 'alice ' is not two people")
                    .containsPattern(
                            "CHECK \\(checker IS NULL\\s+"
                                    + "OR lower\\(btrim\\(checker\\)\\) <> lower\\(btrim\\(maker\\)\\)\\)")
                    .containsPattern("OR lower\\(btrim\\(approved_by\\)\\) "
                            + "<> lower\\(btrim\\(prepared_by\\)\\)");
        }

        @Test
        @DisplayName("no column defaults to now(), so a replay reproduces its timestamps")
        void noWallClockDefaults() {
            assertThat(executable)
                    .as("04 s5: time is an input, always. A DEFAULT now() breaks DT-1.")
                    .doesNotContainPattern("DEFAULT\\s+now\\s*\\(\\s*\\)")
                    .doesNotContainPattern("DEFAULT\\s+CURRENT_TIMESTAMP");
        }
    }

    @Nested
    @DisplayName("spec 03 s7 impairment interaction")
    class ImpairmentInteraction {

        @Test
        @DisplayName("PF-1: the floor raises the reported figure, not only 'both or neither'")
        void theFloorRaises() {
            // Both halves of PF-1, and only one of them was here. "Both retained" is the storage
            // requirement; the substantive rule is that a floor raises the reported provision or
            // does nothing. The duality check on its own is satisfied by a post-floor number
            // BELOW the pre-floor one, which is not a floor — verified against a live cluster,
            // where a row carrying pre-floor 211,362.93 and post-floor 79,261.10 was accepted by
            // the duality constraint and rejected by this one.
            //
            // Added after FloorApplication asserted the same rule in Java. One rule stated in two
            // places with only one of them stating it is this schema's recurring defect, and the
            // pair of columns is exactly where a caller that computed the provision elsewhere
            // lands.
            assertThat(executable)
                    .as("the storage half")
                    .contains("CHECK ((ecl_pre_floor IS NULL) = (ecl_post_floor IS NULL))");
            assertThat(executable)
                    .as("the substantive half")
                    .contains("CHECK (ecl_pre_floor IS NULL OR ecl_post_floor >= ecl_pre_floor)");
        }

        @Test
        @DisplayName("FR-604: suspense movements are magnitudes, and the continuity check needs that")
        void suspenseMovementsAreMagnitudes() {
            // The continuity check alone is satisfied by a recovery posted as a negative and a
            // suspension posted as a negative recovery: same closing balance, different ledger,
            // and only one of them reconciles to the cash book. The balances were already
            // constrained non-negative and the movements were not.
            assertThat(executable)
                    .contains("CHECK (opening_balance >= 0 AND closing_balance >= 0)");
            assertThat(executable)
                    .as("the movements too, or the direction stops being the column's name")
                    .containsPattern(
                            "CHECK \\(contractual_interest_suspended >= 0\\s+"
                            + "AND released_on_recovery >= 0\\s+"
                            + "AND written_off >= 0\\)");
        }

        @Test
        @DisplayName("the suspense ledger's columns are the movements SuspenseLedger carries")
        void suspenseLedgerMatchesTheJavaType() {
            // A seam check, in the spirit of the V1/V2 key-type defect this module's README makes
            // the case for executing rather than reading. SuspenseLedger was written against
            // 03 s7.3 and this table against 04, independently — an opening balance, three
            // movements and a closing balance, on both sides. Named here so a change to either
            // that drops or renames a movement fails rather than quietly leaving the ledger
            // unable to round-trip.
            for (String column : List.of(
                    "opening_balance",
                    "contractual_interest_suspended",
                    "released_on_recovery",
                    "written_off",
                    "closing_balance")) {
                assertThat(executable)
                        .as("suspense_entry.%s", column)
                        .contains(column);
            }
        }
    }

    @Nested
    @DisplayName("04 s3 exception queue")
    class ExceptionQueue {

        /**
         * The ten categories, written out from 04 § 3 rather than read from
         * {@link LedgerSchema} — the constant and the DDL are two copies of one list and this test
         * is the third, which is what makes a drift in either of the other two visible.
         */
        private static final List<String> TEN_CATEGORIES = List.of(
                "UNMAPPED_FEE_CODE",
                "MISSING_COST_FUNCTION",
                "NO_SOLUTION",
                "MULTIPLE_ROOTS",
                "MISSING_MANDATORY_FIELD",
                "PENAL_CHARGE_REJECTED",
                "IC1_BREACH",
                "STALE_EQUIVALENCE_TEST",
                "DISCONTINUED_HEDGE_NO_SCHEDULE",
                "POOL_BACKTEST_BREACH");

        @Test
        @DisplayName("the category CHECK admits exactly the ten categories of 04 s3")
        void categoryCheckIsExact() {
            Matcher m = Pattern.compile(
                            "CONSTRAINT ck_exception_category\\s+CHECK \\(category IN \\(([^)]*)\\)")
                    .matcher(executable);
            assertThat(m.find())
                    .as("the exception category CHECK constraint is present")
                    .isTrue();

            List<String> declared = new ArrayList<>();
            Matcher values = Pattern.compile("'([A-Z0-9_]+)'").matcher(m.group(1));
            while (values.find()) {
                declared.add(values.group(1));
            }

            assertThat(declared)
                    .as("all ten categories, and only those ten")
                    .containsExactlyInAnyOrderElementsOf(TEN_CATEGORIES);
        }

        @Test
        @DisplayName("the constants class agrees with 04 s3")
        void constantsAgreeWithTheSpecification() {
            assertThat(LedgerSchema.EXCEPTION_CATEGORIES)
                    .containsExactlyElementsOf(TEN_CATEGORIES);
        }

        @Test
        @DisplayName("stops_the_contract is generated from the category, not written")
        void stopsTheContractIsGenerated() {
            assertThat(executable)
                    .as("mirrors ExceptionCategory.stopsTheContract(); generated so it cannot "
                            + "disagree with the category it derives from")
                    .containsPattern("stops_the_contract\\s+BOOLEAN\\s+GENERATED ALWAYS AS "
                            + "\\(category NOT IN \\('STALE_EQUIVALENCE_TEST',\\s+"
                            + "'POOL_BACKTEST_BREACH'\\)\\) STORED");
        }

        @Test
        @DisplayName("there is no blocks_close column, because every category blocks the close")
        void noBlocksCloseColumn() {
            assertThat(executable).doesNotContain("blocks_close");
        }

        @Test
        @DisplayName("acceptance of an open exception requires a named approver")
        void acceptanceRequiresApproval() {
            assertThat(executable)
                    .contains("ACCEPTED_WITH_APPROVAL")
                    .contains("ck_exception_acceptance_approved");
        }
    }

    @Nested
    @DisplayName("invariants encoded in the schema")
    class Invariants {

        @Test
        @DisplayName("INV-4: the fee balances are generated from the two legs, never accumulated")
        void inv4IsGenerated() {
            assertThat(executable)
                    .as("fee_amortised = eir_interest - contractual_interest")
                    .containsPattern("fee_amortised\\s+NUMERIC\\(24,6\\)\\s+GENERATED ALWAYS AS "
                            + "\\(eir_interest - contractual_interest\\) STORED");
            assertThat(executable)
                    .as("unamortised_fee = closing_contractual - closing_gca")
                    .containsPattern("unamortised_fee\\s+NUMERIC\\(24,6\\)\\s+GENERATED ALWAYS AS "
                            + "\\(closing_contractual - closing_gca\\) STORED");
        }

        @Test
        @DisplayName("S3-2: recognised interest income is zero on a Stage 3 row")
        void s32IsAConstraint() {
            assertThat(executable)
                    .containsPattern("CHECK \\(stage <> 3 OR recognised_interest_income = 0\\)");
        }

        @Test
        @DisplayName("HB-1: a discontinued hedge cannot exist without an amortisation schedule")
        void hb1IsAConstraint() {
            assertThat(executable)
                    .containsPattern("CHECK \\(status <> 'DISCONTINUED' "
                            + "OR amortisation_schedule_id IS NOT NULL\\)");
        }

        @Test
        @DisplayName("PF-1: pre-floor and post-floor ECL are retained together or not at all")
        void pf1IsAConstraint() {
            assertThat(executable)
                    .containsPattern(
                            "CHECK \\(\\(ecl_pre_floor IS NULL\\) = \\(ecl_post_floor IS NULL\\)\\)");
        }

        @Test
        @DisplayName("FR-701: only benchmark interest rate risk can be designated")
        void fr701LeavesCreditSpreadOutOfTheVocabulary() {
            assertThat(executable)
                    .containsPattern("CHECK \\(designated_risk = 'BENCHMARK_INTEREST_RATE'\\)");
            assertThat(executable)
                    .as("credit spread is not in the vocabulary at all")
                    .doesNotContain("CREDIT_SPREAD");
        }

        @Test
        @DisplayName("SL-2 and ST-2 are views, because they are properties of sets of rows")
        void setLevelInvariantsAreViews() {
            assertThat(executable)
                    .contains("CREATE VIEW vw_journal_entry_unbalanced")
                    .contains("CREATE VIEW vw_stage3_st2_breach");
        }

        @Test
        @DisplayName("a pool is a closed cohort and a back-test breach forces contract level")
        void poolConstraints() {
            assertThat(executable)
                    .containsPattern("CHECK \\(is_closed_cohort\\)")
                    .contains("ck_pool_backtest_breach_forces_contract_level");
        }

        @Test
        @DisplayName("the paragraph 19 presumption cannot be claimed without its evidence")
        void para19RequiresEvidence() {
            assertThat(executable)
                    .containsPattern("CHECK \\(NOT para_19_presumption_applied\\s+"
                            + "OR para_19_rebuttal_evidence_ref IS NOT NULL\\)");
        }
    }

    @Nested
    @DisplayName("cross-file foreign keys")
    class CrossFileForeignKeys {

        private static final List<String> V1_OWNED_TABLES =
                List.of("contract", "product", "eir_computation");

        @Test
        @DisplayName("no hard foreign key references a table V1 owns")
        void noHardCrossFileForeignKeys() {
            int deferredSection = executable.indexOf("DO $deferred_foreign_keys$");
            assertThat(deferredSection)
                    .as("the deferred foreign key section is present")
                    .isGreaterThan(0);

            String beforeDeferred = executable.substring(0, deferredSection);
            for (String parent : V1_OWNED_TABLES) {
                assertThat(beforeDeferred)
                        .as("a hard FK to %s would make V2 unrunnable standalone; it belongs in "
                                + "the deferred section", parent)
                        .doesNotContainPattern("REFERENCES\\s+" + parent + "\\s*\\(");
            }
        }

        @Test
        @DisplayName("the deferred section adds each constraint only when its parent exists")
        void deferredSectionIsGuarded() {
            int deferredSection = executable.indexOf("DO $deferred_foreign_keys$");
            String section = executable.substring(deferredSection);
            assertThat(section)
                    .as("existence of both ends is checked before the ALTER")
                    .contains("to_regclass")
                    .as("a type disagreement with V1 warns rather than aborting")
                    .contains("RAISE WARNING")
                    .as("the statement is an ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY")
                    .contains("ADD CONSTRAINT %I FOREIGN KEY");
        }

        @Test
        @DisplayName("intra-file relationships are enforced immediately")
        void intraFileForeignKeysAreReal() {
            List<String> expected = List.of(
                    "FOREIGN KEY (period_id) REFERENCES accounting_period (period_id)",
                    "FOREIGN KEY (replay_of_run_id) REFERENCES amortisation_run (run_id)",
                    "FOREIGN KEY (relationship_id) REFERENCES hedge_relationship "
                            + "(relationship_id)",
                    "FOREIGN KEY (cohort_id) REFERENCES legacy_cohort (cohort_id)",
                    "FOREIGN KEY (pool_id) REFERENCES pool (pool_id)");
            assertThat(executable).contains(expected);
            assertThat(executable)
                    .as("every ..._version_id resolves to POLICY_VERSION in this file")
                    .contains("REFERENCES policy_version (policy_version_id)");
        }
    }

    @Nested
    @DisplayName("completeness and style")
    class CompletenessAndStyle {

        @Test
        @DisplayName("every entity of 04 ss2.7-2.13, s3 and s6 is created")
        void everyTableIsCreated() {
            for (String table : LedgerSchema.TABLES) {
                assertThat(executable)
                        .as("CREATE TABLE %s", table)
                        .containsPattern("CREATE TABLE " + table + "\\s*\\(");
            }
        }

        @Test
        @DisplayName("each table is introduced by a comment citing its 04 section")
        void eachTableCitesItsSection() {
            for (String table : LedgerSchema.TABLES) {
                Matcher m = Pattern.compile("(?m)^CREATE TABLE " + table + "\\s*\\(")
                        .matcher(ddl);
                assertThat(m.find()).as("CREATE TABLE %s at line start", table).isTrue();

                String preamble = ddl.substring(Math.max(0, m.start() - 4000), m.start());
                int lastBlank = preamble.lastIndexOf("\n\n");
                String comment = lastBlank < 0 ? preamble : preamble.substring(lastBlank);
                assertThat(comment)
                        .as("the comment above %s cites the 04 section it implements", table)
                        .containsPattern("04 (§|s)\\s?\\d");
            }
        }

        @Test
        @DisplayName("SQL keywords are upper case in the statements that create objects")
        void keywordsAreUpperCase() {
            assertThat(executable)
                    .doesNotContainPattern("(?m)^create table\\b")
                    .doesNotContainPattern("(?m)^create index\\b")
                    .doesNotContainPattern("(?m)^create view\\b");
        }

        @Test
        @DisplayName("the DDL is not empty and is a single migration file")
        void sanity() {
            assertThat(ddl).hasSizeGreaterThan(20_000);
            assertThat(ddl.toLowerCase(Locale.ROOT))
                    .as("no ORM annotations or framework references leak into the schema")
                    .doesNotContain("hibernate")
                    .doesNotContain("jakarta.persistence");
        }

        @Test
        @DisplayName("reading the migration resource does not throw")
        void resourceIsReadable() {
            assertThatCode(() -> {
                try (InputStream in = LedgerSchema.class
                        .getResourceAsStream(LedgerSchema.MIGRATION_RESOURCE)) {
                    in.readAllBytes();
                }
            }).doesNotThrowAnyException();
        }
    }
}
