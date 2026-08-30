package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code YYYYMM} encoding and, mainly, the year boundary.
 *
 * <p>Every expected value below is written out by hand. The January case is the one that matters: the
 * opening position for a period is the closing position of the period before it, and the naive
 * {@code periodId - 1} gives 202700 for January 2027 — a period id V1 and V2 both refuse. The
 * consequence would not look like an encoding bug. {@link JdbcContractStateSource} returns
 * {@code Optional.empty()} where no prior balance is visible, so a wrong prior period would
 * quarantine every contract in the book under FR-905, once a year, in January.
 */
class PeriodIdTest {

    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        @DisplayName("April 2027 — the first ACPIR period — encodes as 202704")
        void encodesTheFirstAcpirPeriod() {
            // ACPIR is effective 1 April 2027; V2 spells out period_balance_p202704 for that reason.
            assertThat(PeriodId.of(LocalDate.of(2027, 4, 30))).isEqualTo(202704);
            assertThat(PeriodId.of(LocalDate.of(2027, 4, 1))).isEqualTo(202704);
        }

        @Test
        @DisplayName("a single-digit month is zero-padded by the arithmetic, not by a format")
        void singleDigitMonthsEncodeWithoutAFormatter() {
            assertThat(PeriodId.of(LocalDate.of(2027, 1, 31))).isEqualTo(202701);
            assertThat(PeriodId.of(LocalDate.of(2027, 9, 30))).isEqualTo(202709);
        }

        @Test
        @DisplayName("the period's first and last calendar days")
        void startAndEnd() {
            assertThat(PeriodId.startOf(202704)).isEqualTo(LocalDate.of(2027, 4, 1));
            assertThat(PeriodId.endOf(202704)).isEqualTo(LocalDate.of(2027, 4, 30));
            // February 2028 is a leap February: 29 days. Written out by hand because a hard-coded
            // 28 is the classic form of this bug and it survives every test that uses April.
            assertThat(PeriodId.endOf(202802)).isEqualTo(LocalDate.of(2028, 2, 29));
        }
    }

    @Nested
    @DisplayName("the previous period")
    class Previous {

        @Test
        @DisplayName("within a year it is one less")
        void withinAYear() {
            assertThat(PeriodId.previous(202704)).isEqualTo(202703);
            assertThat(PeriodId.previous(202712)).isEqualTo(202711);
        }

        @Test
        @DisplayName("January's previous period is the previous December, not YYYY00")
        void januaryRollsBackAYear() {
            // The whole reason this method exists. 202701 - 1 is 202700, which fails
            // ck_accounting_period_id_shape and matches no partition.
            assertThat(PeriodId.previous(202701)).isEqualTo(202612);
            assertThat(PeriodId.previous(202801)).isEqualTo(202712);
        }

        @Test
        @DisplayName("stepping back twelve times lands on the same month a year earlier")
        void twelveStepsIsOneYear() {
            int period = 202704;
            for (int i = 0; i < 12; i++) {
                period = PeriodId.previous(period);
            }
            assertThat(period).isEqualTo(202604);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a month of 00 or 13 is refused, as V2's shape check refuses it")
        void refusesAMonthOutsideOneToTwelve() {
            assertThatThrownBy(() -> PeriodId.previous(202700))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("202700")
                .hasMessageContaining("YYYYMM");
            assertThatThrownBy(() -> PeriodId.startOf(202713))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("202713");
        }

        @Test
        @DisplayName("a year before 1900 is refused, matching the DDL's lower bound")
        void refusesAnImplausibleYear() {
            // V1: period_id BETWEEN 190001 AND 999912. A four-digit id like 2704 would otherwise
            // decode as year 27, month 4 and silently address a partition nobody provisioned.
            assertThatThrownBy(() -> PeriodId.startOf(2704))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2704");
        }
    }

    @Nested
    @DisplayName("placing a date in the contract's own schedule")
    class PeriodsBetween {

        @Test
        @DisplayName("monthly: April to April is twelve periods")
        void monthlyPeriods() {
            assertThat(PeriodId.periodsBetween(
                LocalDate.of(2027, 4, 30), LocalDate.of(2028, 4, 30), 1)).isEqualTo(12);
        }

        @Test
        @DisplayName("quarterly: a part quarter has not elapsed")
        void quarterlyTruncates() {
            // Two months after the first due date is inside the first quarter, not into the second.
            // Truncating is right for an ordinal: the period that CONTAINS a date is the one whose
            // boundary the date has not yet reached.
            assertThat(PeriodId.periodsBetween(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 30), 3)).isEqualTo(0);
            assertThat(PeriodId.periodsBetween(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 7, 31), 3)).isEqualTo(1);
        }

        @Test
        @DisplayName("a date before the first due date gives a negative count, not zero")
        void beforeTheFirstDueDate() {
            // Reported rather than clamped here, so that the clamp lives in one place
            // (JdbcContractPeriodSource.periodOrdinal) and is visible as a decision.
            assertThat(PeriodId.periodsBetween(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 2, 28), 1)).isEqualTo(-2);
        }
    }
}
