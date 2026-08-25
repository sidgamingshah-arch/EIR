package com.crisil.eir.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The period-id encoding and partition naming rules of {@link LedgerSchema}.
 *
 * <p>These look like arithmetic helpers and they are, but two of them are load-bearing.
 * {@link LedgerSchema#nextPeriodId(int)} computes a partition's upper bound, and a December that
 * rolled to 202713 rather than 202801 would produce a bound PostgreSQL accepts without complaint
 * and into which no row ever falls on the far side — a defect that shows up as a period with no
 * partition, eleven months after the mistake. {@link LedgerSchema#partitionName(String, int)} has to
 * agree with the migration's {@code ledger_close_accounting_period}, which derives partition names
 * from the period id in order to revoke write privileges on them.
 *
 * <p>Every expected value is written out by hand.
 */
@DisplayName("LedgerSchema")
class LedgerSchemaTest {

    @Test
    @DisplayName("the two numeric shapes of 04 s4")
    void numericShapes() {
        assertThat(LedgerSchema.MONEY_PRECISION).isEqualTo(24);
        assertThat(LedgerSchema.MONEY_SCALE).isEqualTo(6);
        assertThat(LedgerSchema.RATE_PRECISION).isEqualTo(20);
        assertThat(LedgerSchema.RATE_SCALE).isEqualTo(12);
    }

    @Test
    @DisplayName("sixteen tables, each named once")
    void tableInventory() {
        assertThat(LedgerSchema.TABLES).hasSize(16).doesNotHaveDuplicates();
        assertThat(LedgerSchema.TABLES)
                .allSatisfy(name -> assertThat(name).matches("[a-z][a-z_]*"));
    }

    @Test
    @DisplayName("only the two ledger tables are partitioned")
    void partitionedTables() {
        assertThat(LedgerSchema.PARTITIONED_TABLES)
                .containsExactlyInAnyOrder("period_balance", "journal_entry");
    }

    @Test
    @DisplayName("the first ACPIR period is April 2027")
    void firstAcpirPeriod() {
        assertThat(LedgerSchema.FIRST_ACPIR_PERIOD_ID).isEqualTo(202704);
        assertThat(LedgerSchema.yearOf(LedgerSchema.FIRST_ACPIR_PERIOD_ID)).isEqualTo(2027);
        assertThat(LedgerSchema.monthOf(LedgerSchema.FIRST_ACPIR_PERIOD_ID)).isEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({
        "2027, 4, 202704",
        "2027, 12, 202712",
        "2028, 1, 202801",
        "2030, 3, 203003",
    })
    @DisplayName("YYYYMM encoding")
    void periodIdEncoding(int year, int month, int expected) {
        assertThat(LedgerSchema.periodId(year, month)).isEqualTo(expected);
        assertThat(LedgerSchema.yearOf(expected)).isEqualTo(year);
        assertThat(LedgerSchema.monthOf(expected)).isEqualTo(month);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1, 100})
    @DisplayName("a month outside 1-12 is rejected")
    void badMonthRejected(int month) {
        assertThatThrownBy(() -> LedgerSchema.periodId(2027, month))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("month");
    }

    @ParameterizedTest
    @CsvSource({
        "202704, 202705",
        "202711, 202712",
        "202712, 202801",
        "202801, 202802",
        "202912, 203001",
    })
    @DisplayName("the next period rolls December into January")
    void nextPeriodRollsTheYear(int periodId, int expected) {
        assertThat(LedgerSchema.nextPeriodId(periodId)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {202700, 202713, 2027, 20270401})
    @DisplayName("a malformed period id is rejected rather than silently accepted")
    void malformedPeriodIdRejected(int periodId) {
        assertThatThrownBy(() -> LedgerSchema.nextPeriodId(periodId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYYMM");
    }

    @Test
    @DisplayName("partition names follow <table>_p<YYYYMM>, which the close routine relies on")
    void partitionNaming() {
        assertThat(LedgerSchema.partitionName("period_balance", 202704))
                .isEqualTo("period_balance_p202704");
        assertThat(LedgerSchema.partitionName("journal_entry", 202803))
                .isEqualTo("journal_entry_p202803");
        assertThat(LedgerSchema.defaultPartitionName("period_balance"))
                .isEqualTo("period_balance_p_default");
        assertThat(LedgerSchema.defaultPartitionName("journal_entry"))
                .isEqualTo("journal_entry_p_default");
    }

    @Test
    @DisplayName("a table that is not partitioned by period cannot be given a partition name")
    void unpartitionedTableRejected() {
        assertThatThrownBy(() -> LedgerSchema.partitionName("stage_assignment", 202704))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage_assignment");
        assertThatThrownBy(() -> LedgerSchema.defaultPartitionName("pool"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("eight of the ten exception categories stop their contract")
    void stopsTheContract() {
        // From 04 § 3 and ExceptionCategory: STALE_EQUIVALENCE_TEST demotes a Tier 3 population
        // to Tier 2 and POOL_BACKTEST_BREACH forces a pool to contract level. Both still raise
        // and both still block the close; neither quarantines the contract.
        List<String> stopping = List.of(
                "UNMAPPED_FEE_CODE",
                "MISSING_COST_FUNCTION",
                "NO_SOLUTION",
                "MULTIPLE_ROOTS",
                "MISSING_MANDATORY_FIELD",
                "PENAL_CHARGE_REJECTED",
                "IC1_BREACH",
                "DISCONTINUED_HEDGE_NO_SCHEDULE");
        for (String category : stopping) {
            assertThat(LedgerSchema.stopsTheContract(category))
                    .as("%s quarantines its contract", category)
                    .isTrue();
        }
        assertThat(LedgerSchema.stopsTheContract("STALE_EQUIVALENCE_TEST")).isFalse();
        assertThat(LedgerSchema.stopsTheContract("POOL_BACKTEST_BREACH")).isFalse();
        assertThat(stopping).hasSize(8);
        assertThat(LedgerSchema.EXCEPTION_CATEGORIES).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unknown category is rejected rather than defaulted")
    void unknownCategoryRejected() {
        assertThatThrownBy(() -> LedgerSchema.stopsTheContract("SOMETHING_ELSE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the banned SQL type tokens include every binary floating point form")
    void bannedTypes() {
        assertThat(LedgerSchema.BANNED_SQL_TYPE_TOKENS)
                .contains("float", "real", "money")
                .anySatisfy(token -> assertThat(token).isEqualTo("double"));
    }
}
