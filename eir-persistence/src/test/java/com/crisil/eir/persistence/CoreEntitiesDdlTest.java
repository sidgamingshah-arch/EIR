package com.crisil.eir.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural properties of {@code V1__core_entities.sql}, asserted over the migration
 * text.
 *
 * <p>The migration itself is verified by execution against a real PostgreSQL 16 cluster —
 * that is the check that proves the schema is what the data model specifies, and the
 * module README carries the recipe. A unit test cannot reach a database, so what it does
 * here is narrower and different in kind: it guards the properties that are cheap to
 * break silently in an edit and expensive to notice later.
 *
 * <p>The one that matters most is numeric scale. A money column that came out with two
 * decimal places instead of six parses without complaint, executes without complaint,
 * and truncates working values for as long as nobody looks. So every expected value in
 * this class is written out by hand from {@code docs/04-data-model.md} section 4 — never
 * read back from {@link CoreSchema}, and never derived from the file under test. A test
 * that asks the file what it says and then agrees with it asserts nothing.
 */
class CoreEntitiesDdlTest {

    /** The eight tables of 04 sections 2.1 to 2.6, spelled out independently. */
    private static final List<String> EXPECTED_TABLES = List.of(
        "product",
        "contract",
        "contract_version",
        "cashflow_schedule",
        "cashflow_line",
        "fee_rule_set",
        "fee_posting",
        "eir_computation");

    /** Money columns, as {@code table.column}. Every one must be NUMERIC(24,6). */
    private static final List<String> MONEY_COLUMNS = List.of(
        "contract_version.principal",
        "cashflow_line.amount",
        "fee_posting.amount",
        "eir_computation.opening_carrying_amount",
        "eir_computation.residual_at_stored_rate");

    /** Rate and ratio columns. Every one must be NUMERIC(20,12). */
    private static final List<String> RATE_COLUMNS = List.of(
        "product.drawdown_probability_threshold",
        "contract_version.contractual_rate",
        "contract_version.spread_bps",
        "fee_posting.drawdown_probability",
        "eir_computation.rate_periodic",
        "eir_computation.rate_effective_annual",
        "eir_computation.rate_nominal_annual");

    /** The ten flow kinds of 04 section 2.4, matching the eir-domain FlowKind enum. */
    private static final List<String> FLOW_KINDS = List.of(
        "DISBURSEMENT",
        "PRINCIPAL",
        "INTEREST",
        "COMBINED_EMI",
        "INTEGRAL_FEE_RECEIVED",
        "INTEGRAL_COST_PAID",
        "BALLOON",
        "EXPECTED_PREPAYMENT",
        "RESIDUAL_VALUE",
        "NOTIONAL_REDEMPTION");

    /** The eight rate-component drivers of 04 section 2.7, feeding event routing. */
    private static final List<String> RATE_DRIVERS = List.of(
        "TIME_VALUE_OF_MONEY",
        "CREDIT_RISK_MARKET",
        "CREDIT_RATCHET_PREDETERMINED",
        "ESG_LINKED",
        "STEP_UP_PREDETERMINED",
        "BEHAVIOURAL_ESTIMATE",
        "DISBURSEMENT_TIMING",
        "NEGOTIATED");

    /** The five fee classifications, matching the eir-domain FeeClassification enum. */
    private static final List<String> FEE_CLASSIFICATIONS = List.of(
        "INTEGRAL",
        "AS_INCURRED",
        "OVER_COMMITMENT_PERIOD",
        "SEPARATE_SERVICE",
        "EXCLUDED_BY_DIRECTION");

    /** The migration as written, comments and all. */
    private static final String RAW = readMigration();

    /** Comments removed, string literals kept — for reading value lists. */
    private static final String NO_COMMENTS = stripLineComments(RAW);

    /** Comments and string literals removed — for reading declarations and types. */
    private static final String CODE = stripStringLiterals(NO_COMMENTS);

    @Test
    @DisplayName("the migration resource exists and follows the Flyway naming convention")
    void migrationResourceIsPresent() {
        assertThat(CoreSchema.CORE_MIGRATION_RESOURCE)
            .as("Flyway V<n>__<description>.sql, so wiring Flyway later is an addition")
            .isEqualTo("/db/migration/V1__core_entities.sql");
        assertThat(RAW).isNotEmpty();
    }

    @Test
    @DisplayName("each of the eight core tables is created exactly once")
    void everyCoreTableIsCreatedExactlyOnce() {
        for (String table : EXPECTED_TABLES) {
            assertThat(countOccurrences(CODE, "CREATE TABLE " + table + " ("))
                .as("CREATE TABLE for %s", table)
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("no banned physical type appears: no binary floating point, no money type")
    void noBannedPhysicalTypeAppears() {
        // ADR-0002 bans binary floating point outright: it cannot represent 0.01, and
        // over 360 compounding periods the accumulated error becomes a reconciliation
        // break with no single diagnosable cause. PostgreSQL's currency type is banned
        // for a different reason — its scale is fixed by a run-time locale setting, so
        // the same DDL means different things on two servers.
        List<String> banned = List.of(
            "\\bfloat\\b", "\\bfloat4\\b", "\\bfloat8\\b",
            "\\breal\\b", "\\bmoney\\b", "\\bdouble\\s+precision\\b");
        for (String pattern : banned) {
            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(CODE);
            if (matcher.find()) {
                fail("banned physical type matched /%s/ at offset %d: %s",
                    pattern, matcher.start(), context(CODE, matcher.start()));
            }
        }
    }

    @Test
    @DisplayName("every money column is NUMERIC(24,6) — six decimals, not presentation scale")
    void everyMoneyColumnCarriesTheWorkingScale() {
        for (String qualified : MONEY_COLUMNS) {
            assertColumnType(qualified, 24, 6);
        }
    }

    @Test
    @DisplayName("every rate column is NUMERIC(20,12) — the 12dp storage policy exactly")
    void everyRateColumnCarriesTheStorageScale() {
        for (String qualified : RATE_COLUMNS) {
            assertColumnType(qualified, 20, 12);
        }
    }

    @Test
    @DisplayName("no NUMERIC column has drifted off the two permitted scales")
    void noNumericColumnDriftsOffTheTwoScales() {
        // Counts, months and ordinals are exact integers, so the only NUMERIC columns in
        // the file are money and rates. That makes "nothing drifted" a property the whole
        // file can be checked against, rather than a per-column list that a new column
        // can be added outside of.
        Matcher matcher = Pattern.compile("NUMERIC\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
            .matcher(CODE);
        int moneyCount = 0;
        int rateCount = 0;
        while (matcher.find()) {
            String declared = matcher.group(1) + "," + matcher.group(2);
            if ("24,6".equals(declared)) {
                moneyCount++;
            } else if ("20,12".equals(declared)) {
                rateCount++;
            } else {
                fail("NUMERIC(%s) is neither the money scale (24,6) nor the rate scale "
                    + "(20,12): %s", declared, context(CODE, matcher.start()));
            }
        }
        assertThat(moneyCount).as("money columns").isEqualTo(MONEY_COLUMNS.size());
        assertThat(rateCount).as("rate columns").isEqualTo(RATE_COLUMNS.size());
    }

    @Test
    @DisplayName("cashflow_line is range-partitioned by period_id, with no default partition")
    void cashflowLineIsRangePartitionedByPeriodId() {
        assertThat(CODE).contains("PARTITION BY RANGE (period_id)");

        Map<String, String> expectedBounds = new LinkedHashMap<>();
        expectedBounds.put("cashflow_line_fy2027", "FOR VALUES FROM (202604) TO (202704)");
        expectedBounds.put("cashflow_line_fy2028", "FOR VALUES FROM (202704) TO (202804)");
        expectedBounds.put("cashflow_line_fy2029", "FOR VALUES FROM (202804) TO (202904)");
        expectedBounds.put("cashflow_line_fy2030", "FOR VALUES FROM (202904) TO (203004)");
        for (Map.Entry<String, String> partition : expectedBounds.entrySet()) {
            assertThat(collapseWhitespace(CODE))
                .as("partition %s", partition.getKey())
                .contains("CREATE TABLE " + partition.getKey()
                    + " PARTITION OF cashflow_line " + partition.getValue());
        }

        // A DEFAULT partition would swallow rows for unprovisioned periods: no pruning
        // can skip it and no read-only switch can freeze it, and an insert that should
        // have been a hard stop (FR-905) becomes a silent landing.
        assertThat(collapseWhitespace(CODE))
            .doesNotContain("PARTITION OF cashflow_line DEFAULT");
    }

    @Test
    @DisplayName("the (contract_id, period_id) trace path is indexed on both period-keyed tables")
    void tracePathIsIndexed() {
        String flat = collapseWhitespace(CODE);
        assertThat(flat)
            .as("trace index on the partitioned flow table")
            .contains("ON cashflow_line (contract_id, period_id)");
        assertThat(flat)
            .as("trace index on the audit anchor")
            .contains("ON eir_computation (contract_id, period_id)");
    }

    @Test
    @DisplayName("bitemporal columns are on contract_version and nowhere the doc does not say")
    void bitemporalColumnsAreNotBlanketApplied() {
        String contractVersion = tableBody("contract_version");
        assertThat(contractVersion)
            .contains("valid_from")
            .contains("valid_to")
            .contains("recorded_at")
            .contains("superseded_at");

        // Business time and supersession belong to the entity whose facts change. An
        // identity row and a taxonomy row have no history to keep, and giving them a
        // valid-time range would invite two rows to disagree about a fact that cannot
        // change. 04 section 2.3 names contract_version and nothing else.
        for (String table : List.of("product", "contract", "cashflow_schedule",
                                    "cashflow_line", "fee_rule_set", "fee_posting")) {
            String body = tableBody(table);
            assertThat(body).as("%s has no business-time range", table)
                .doesNotContain("valid_from")
                .doesNotContain("valid_to")
                .doesNotContain("superseded_at");
        }

        // The system-time instant appears on the audit anchor too, because superseded_by
        // can only order a chain of beliefs if the beliefs are timestamped.
        assertThat(tableBody("eir_computation"))
            .contains("recorded_at")
            .contains("superseded_by")
            .doesNotContain("valid_from");
    }

    @Test
    @DisplayName("the two lives are separate columns with a mandatory divergence basis")
    void theTwoLivesRuleIsEnforcedInTheSchema() {
        String body = tableBody("contract_version");
        assertThat(body)
            .as("ACPIR 51 expected life and ACPIR 46(1) horizon are separate attributes")
            .containsPattern("\\beir_expected_life_months\\s+INTEGER\\s+NOT NULL")
            .containsPattern("\\becl_horizon_months\\s+INTEGER\\s+NOT NULL");
        assertThat(collapseWhitespace(body))
            .as("life_divergence_basis is required where the two differ (FR-108)")
            .contains("CHECK (eir_expected_life_months = ecl_horizon_months "
                + "OR life_divergence_basis IS NOT NULL)");
    }

    @Test
    @DisplayName("the audit anchor stores the reading in force and never updates in place")
    void auditAnchorCarriesThePolicyReading() {
        String body = tableBody("eir_computation");
        // TEXT, not UUID, and the merge is what settled it. V1 first wrote these as UUID
        // surrogates while V2 wrote them as TEXT, so the two halves of the schema could not
        // be joined — every deferred foreign key from V2 into V1 failed to form. TEXT wins
        // on the evidence: PolicyVersion.id and RoutingTableVersion.id are Java Strings
        // carrying human-authored natural keys like "FEE-2027.1", which a computation CITES.
        // A surrogate would need a translation table to get from the cited value to the row.
        assertThat(body)
            .as("without these two, replay is impossible (04 section 2.6)")
            .containsPattern("\\bpolicy_version_id\\s+TEXT\\s+NOT NULL")
            .containsPattern("\\brule_set_version_id\\s+TEXT\\s+NOT NULL");
        // Reads the body with literals intact: the constraint's meaning is in the value
        // it compares status against.
        assertThat(collapseWhitespace(tableBodyWithLiterals("eir_computation")))
            .as("a NO_SOLUTION carries no rate: no silent fallback to the contractual "
                + "rate (FR-402)")
            .contains("CHECK ((status = 'NO_SOLUTION') = (rate_periodic IS NULL))");
        assertThat(body)
            .as("both annual forms stored and labelled (FR-405)")
            .contains("rate_effective_annual")
            .contains("rate_nominal_annual");
    }

    @Test
    @DisplayName("cost_function is mandatory on a fee posting")
    void costFunctionIsMandatory() {
        // FR-203, the ACPIR 53 selling-versus-processing line: a selling-agent incentive
        // is capitalisable and internal credit-appraisal cost is not, so a posting that
        // does not say which it is cannot be classified and is rejected.
        assertThat(tableBody("fee_posting"))
            .containsPattern("\\bcost_function\\s+TEXT\\s+NOT NULL");
    }

    @Test
    @DisplayName("enumerated value lists match the eir-domain enums they mirror")
    void valueListsMatchTheDomainEnums() {
        String flows = tableBodyWithLiterals("cashflow_line");
        for (String kind : FLOW_KINDS) {
            assertThat(flows).as("FlowKind.%s", kind).contains("'" + kind + "'");
        }
        for (String driver : RATE_DRIVERS) {
            assertThat(flows).as("RateDriver.%s", driver).contains("'" + driver + "'");
        }
        for (String classification : FEE_CLASSIFICATIONS) {
            assertThat(tableBodyWithLiterals("fee_posting"))
                .as("FeeClassification.%s on a posting", classification)
                .contains("'" + classification + "'");
            assertThat(tableBodyWithLiterals("fee_rule_set"))
                .as("FeeClassification.%s in the rule set", classification)
                .contains("'" + classification + "'");
        }
    }

    @Test
    @DisplayName("no column defaults to a non-deterministic value")
    void noColumnDefaultsToANonDeterministicValue() {
        // A replay of a closed period must reproduce the same keys and the same rows from
        // the same inputs (FR-903), and nothing in the calculation path reads a wall
        // clock — time is an input, always (04 section 5). A generated key or a
        // clock-valued default would break both quietly.
        // Asserted over EVERY migration, not just this one, and that widening is the
        // lesson of the merge. This test passed on V1 alone while V2 — written in parallel,
        // and citing FR-903 and DT-1 twenty-eight times in its own comments — declared
        // fourteen surrogate keys as BIGINT GENERATED ALWAYS AS IDENTITY. A sequence
        // advances independently of the inputs, so those keys could not be reproduced by a
        // replay, which is the one thing the rule exists to guarantee. A determinism rule
        // scoped to one file is not a rule about the schema.
        for (String migration : migrationSources()) {
            // Comments and string literals stripped first, exactly as CODE is derived for
            // V1. Without that this check reads V1's own header, which EXPLAINS why
            // gen_random_uuid() is banned and would therefore fail on the prose that
            // documents the rule — a false positive that would teach the next reader to
            // weaken the assertion rather than trust it.
            String upper = stripStringLiterals(stripLineComments(readMigration(migration)))
                .toUpperCase(Locale.ROOT);
            assertThat(upper).as("%s: gen_random_uuid()", migration).doesNotContain("GEN_RANDOM_UUID");
            assertThat(upper).as("%s: identity always", migration).doesNotContain("GENERATED ALWAYS AS IDENTITY");
            assertThat(upper).as("%s: identity by default", migration).doesNotContain("GENERATED BY DEFAULT AS IDENTITY");
            assertThat(upper).as("%s: serial", migration).doesNotContain("SERIAL");
            assertThat(upper).as("%s: now()", migration).doesNotContain("DEFAULT NOW()");
            assertThat(upper).as("%s: current_timestamp", migration).doesNotContain("DEFAULT CURRENT_TIMESTAMP");
            assertThat(upper).as("%s: current_date", migration).doesNotContain("DEFAULT CURRENT_DATE");
        }
    }

    /** Every migration under db/migration, so a rule about the schema covers the schema. */
    private static java.util.List<String> migrationSources() {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.nio.file.Path dir = java.nio.file.Path.of(
            "src", "main", "resources", "db", "migration");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of(
                "eir-persistence", "src", "main", "resources", "db", "migration");
        }
        try (java.util.stream.Stream<java.nio.file.Path> files =
                 java.nio.file.Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".sql"))
                .map(f -> f.getFileName().toString())
                .sorted()
                .forEach(names::add);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("cannot list " + dir.toAbsolutePath(), unreadable);
        }
        // Both halves of 04 must be present, or this test silently checks less than it says.
        assertThat(names).as("migrations found in %s", dir.toAbsolutePath()).hasSizeGreaterThanOrEqualTo(2);
        return names;
    }

    private static String readMigration(String name) {
        java.nio.file.Path dir = java.nio.file.Path.of(
            "src", "main", "resources", "db", "migration");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of(
                "eir-persistence", "src", "main", "resources", "db", "migration");
        }
        try {
            return java.nio.file.Files.readString(dir.resolve(name));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("cannot read " + name, unreadable);
        }
    }

    @Test
    @DisplayName("each table block cites the data-model section it implements")
    void eachTableCitesItsSection() {
        // The register the repository is written in: a reader should be able to get from
        // a column to the paragraph that requires it without a second document.
        for (String table : EXPECTED_TABLES) {
            String heading = RAW.substring(0, RAW.indexOf("CREATE TABLE " + table + " ("));
            String preamble = heading.substring(Math.max(0, heading.length() - 2500));
            assertThat(preamble)
                .as("comment block above %s cites its 04 section", table)
                .containsPattern("04 section 2\\.\\d");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertColumnType(String qualified, int precision, int scale) {
        int dot = qualified.indexOf('.');
        String table = qualified.substring(0, dot);
        String column = qualified.substring(dot + 1);
        String expected = "NUMERIC(" + precision + "," + scale + ")";
        assertThat(collapseWhitespace(tableBody(table)))
            .as("%s must be declared %s", qualified, expected)
            .containsPattern("\\b" + Pattern.quote(column) + " "
                + "NUMERIC\\(" + precision + "," + scale + "\\)");
    }

    /** The column and constraint list of one CREATE TABLE, literals removed. */
    private static String tableBody(String table) {
        return bodyOf(CODE, table);
    }

    /** The same body with string literals intact, for reading CHECK value lists. */
    private static String tableBodyWithLiterals(String table) {
        return bodyOf(NO_COMMENTS, table);
    }

    private static String bodyOf(String sql, String table) {
        String needle = "CREATE TABLE " + table + " (";
        int start = sql.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("no CREATE TABLE for " + table);
        }
        int open = start + needle.length() - 1;
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unterminated CREATE TABLE for " + table);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }

    /** Line comments removed, so a type named only in prose cannot fail a type check. */
    private static String stripLineComments(String sql) {
        List<String> kept = new ArrayList<>();
        for (String line : sql.split("\n", -1)) {
            int marker = indexOfCommentMarker(line);
            kept.add(marker < 0 ? line : line.substring(0, marker));
        }
        return String.join("\n", kept);
    }

    /** The first {@code --} outside a string literal, or -1. */
    private static int indexOfCommentMarker(String line) {
        boolean inLiteral = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && current == '-' && i + 1 < line.length()
                       && line.charAt(i + 1) == '-') {
                return i;
            }
        }
        return -1;
    }

    /** String literals emptied, so their contents cannot satisfy a structural check. */
    private static String stripStringLiterals(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "''");
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String context(String sql, int offset) {
        int from = Math.max(0, offset - 60);
        int to = Math.min(sql.length(), offset + 60);
        return collapseWhitespace(sql.substring(from, to));
    }

    private static String readMigration() {
        try (InputStream in =
                 CoreEntitiesDdlTest.class.getResourceAsStream(
                     "/db/migration/V1__core_entities.sql")) {
            if (in == null) {
                throw new AssertionError(
                    "V1__core_entities.sql is not on the classpath; expected it under "
                    + "src/main/resources/db/migration");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read V1__core_entities.sql", e);
        }
    }
}
