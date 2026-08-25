package com.crisil.eir.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The constants layer, checked against the data model rather than against itself.
 *
 * <p>Every expected value below is written out from {@code docs/04-data-model.md} by
 * hand. The point of a constants class is that one place states the schema's vocabulary;
 * the point of this test is that the place states it correctly, which it cannot
 * demonstrate by being consulted.
 */
class CoreSchemaTest {

    @Test
    @DisplayName("money is 24 digits with 6 decimals; rates are 20 with 12")
    void numericScalesMatchThePhysicalDesign() {
        // 04 section 4: six decimals holds working values above presentation scale, and
        // twelve decimal places is the rate storage policy of 03 section 1 exactly.
        assertThat(CoreSchema.MONEY_PRECISION).isEqualTo(24);
        assertThat(CoreSchema.MONEY_SCALE).isEqualTo(6);
        assertThat(CoreSchema.RATE_PRECISION).isEqualTo(20);
        assertThat(CoreSchema.RATE_SCALE).isEqualTo(12);
    }

    @Test
    @DisplayName("the money scale is above presentation scale for every ISO 4217 minor unit")
    void moneyScaleSitsAbovePresentationScale() {
        // The largest minor-unit exponent in ISO 4217 is 4 (the Chilean unidad de
        // fomento and the Uruguayan indexed unit). Six decimals is therefore strictly
        // above presentation scale for every currency the engine can be asked about,
        // which is the property that lets a working value be stored unrounded.
        assertThat(CoreSchema.MONEY_SCALE).isGreaterThan(4);
        assertThat(CoreSchema.RATE_SCALE).isGreaterThan(CoreSchema.MONEY_SCALE);
    }

    @Test
    @DisplayName("the eight core tables, in dependency order")
    void coreTablesAreTheEightEntitiesOfSections21To26() {
        assertThat(CoreSchema.CORE_TABLES).containsExactly(
            "product",
            "contract",
            "contract_version",
            "cashflow_schedule",
            "cashflow_line",
            "fee_rule_set",
            "fee_posting",
            "eir_computation");
    }

    @Test
    @DisplayName("a table appears after everything it references")
    void coreTablesAreInDependencyOrder() {
        List<String> tables = CoreSchema.CORE_TABLES;
        assertThat(tables.indexOf("product")).isLessThan(tables.indexOf("contract"));
        assertThat(tables.indexOf("contract")).isLessThan(tables.indexOf("contract_version"));
        assertThat(tables.indexOf("contract_version"))
            .isLessThan(tables.indexOf("cashflow_schedule"));
        assertThat(tables.indexOf("cashflow_schedule"))
            .isLessThan(tables.indexOf("cashflow_line"));
        assertThat(tables.indexOf("fee_rule_set")).isLessThan(tables.indexOf("fee_posting"));
        assertThat(tables.indexOf("fee_rule_set"))
            .isLessThan(tables.indexOf("eir_computation"));
    }

    @Test
    @DisplayName("only contract_version is bitemporal, and only cashflow_line is partitioned")
    void temporalAndPhysicalListsAreNotBlanketApplied() {
        assertThat(CoreSchema.BITEMPORAL_TABLES).containsExactly("contract_version");
        assertThat(CoreSchema.PERIOD_PARTITIONED_TABLES).containsExactly("cashflow_line");
        assertThat(CoreSchema.CORE_TABLES)
            .containsAll(CoreSchema.BITEMPORAL_TABLES)
            .containsAll(CoreSchema.PERIOD_PARTITIONED_TABLES);
    }

    @Test
    @DisplayName("the trace path is (contract_id, period_id), in that order")
    void traceIndexColumnsAreInTraceOrder() {
        // 04 section 4. The order is the useful one: a trace starts from a contract and
        // narrows to a period, so the contract must lead for the index to be usable on a
        // contract alone.
        assertThat(CoreSchema.TRACE_INDEX_COLUMNS).containsExactly("contract_id", "period_id");
    }

    @Test
    @DisplayName("the temporal column names are the three axes of section 5")
    void temporalColumnNamesNameTheThreeAxes() {
        assertThat(CoreSchema.VALID_FROM).isEqualTo("valid_from");
        assertThat(CoreSchema.VALID_TO).isEqualTo("valid_to");
        assertThat(CoreSchema.RECORDED_AT).isEqualTo("recorded_at");
        assertThat(CoreSchema.SUPERSEDED_AT).isEqualTo("superseded_at");
        assertThat(CoreSchema.PERIOD_ID).isEqualTo("period_id");
    }

    @Test
    @DisplayName("table names are lower snake case, as the DDL declares them")
    void tableNamesAreUnquotedIdentifiers() {
        // PostgreSQL folds an unquoted identifier to lower case. A constant carrying a
        // mixed-case name would only match if every reader quoted it, so the constants
        // are written the way the database stores them.
        assertThat(CoreSchema.CORE_TABLES)
            .allMatch(name -> name.matches("[a-z][a-z0-9_]*"));
    }

    @Test
    @DisplayName("the published lists cannot be modified by a caller")
    void publishedListsAreImmutable() {
        assertThatThrownBy(() -> CoreSchema.CORE_TABLES.add("something"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CoreSchema.TRACE_INDEX_COLUMNS.set(0, "something"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the migration resource named by the constant is on the classpath")
    void migrationResourceResolves() {
        assertThat(CoreSchema.class.getResourceAsStream(CoreSchema.CORE_MIGRATION_RESOURCE))
            .as("%s must resolve, or nothing can load the schema",
                CoreSchema.CORE_MIGRATION_RESOURCE)
            .isNotNull();
    }
}
