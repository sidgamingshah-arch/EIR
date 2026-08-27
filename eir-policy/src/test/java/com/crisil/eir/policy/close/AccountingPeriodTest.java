package com.crisil.eir.policy.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountingPeriod} against the DDL it mirrors, and {@link PeriodStatus} against FR-902.
 *
 * <p>Every expected value here is read off {@code V2__ledger_and_transition.sql} — the four
 * {@code CHECK} constraints on {@code ACCOUNTING_PERIOD} — or off FR-902's one sentence. Nothing is
 * taken from running the code: the period ids and dates are hand-chosen to sit on the boundary each
 * constraint draws, and the arithmetic ({@code 2027 * 100 + 4 = 202704}) is stated in the comment
 * that uses it.
 */
class AccountingPeriodTest {

    private static final LocalDate APRIL_START = LocalDate.of(2027, 4, 1);
    private static final LocalDate APRIL_END = LocalDate.of(2027, 4, 30);
    private static final Instant CLOSING_BEGAN = Instant.parse("2027-05-01T02:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2027-05-05T10:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2027-05-05T09:30:00Z");

    private static AccountingPeriod april() {
        // 2027 * 100 + 4 = 202704, which is what ck_accounting_period_id_matches_dates requires
        // of a period starting 2027-04-01.
        return AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END);
    }

    @Nested
    @DisplayName("the four CHECK constraints of ACCOUNTING_PERIOD")
    class SchemaAlignment {

        @Test
        @DisplayName("ck_accounting_period_id_shape: a month part of 13 is refused")
        void monthPartOutOfRange() {
            // 202713 % 100 = 13. The DDL requires period_id % 100 BETWEEN 1 AND 12.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> AccountingPeriod.open(
                    202713, "FY2027-28", LocalDate.of(2027, 4, 1), APRIL_END))
                .withMessageContaining("ck_accounting_period_id_shape");
        }

        @Test
        @DisplayName("ck_accounting_period_id_shape: a year before 1900 is refused")
        void yearBelowFloor() {
            // The DDL's floor is 190001. 189912 is one month below it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> AccountingPeriod.open(
                    189912, "FY1899-00", LocalDate.of(1899, 12, 1), LocalDate.of(1899, 12, 31)))
                .withMessageContaining("ck_accounting_period_id_shape");
        }

        @Test
        @DisplayName("ck_accounting_period_id_matches_dates: an id that does not encode its start")
        void idMustEncodeItsStartDate() {
            // A period_id of 202704 against a start date of 2027-05-01 files May's rows into
            // April's partition, and every total still ties — which is why the DDL refuses it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> AccountingPeriod.open(
                    202704, "FY2027-28", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 31)))
                .withMessageContaining("expected 202705");
        }

        @Test
        @DisplayName("ck_accounting_period_dates: an end before the start")
        void endBeforeStart() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> AccountingPeriod.open(
                    202704, "FY2027-28", APRIL_START, LocalDate.of(2027, 3, 31)))
                .withMessageContaining("ck_accounting_period_dates");
        }

        @Test
        @DisplayName("ck_accounting_period_closure_attested: CLOSED with no cutoff is refused")
        void closedWithoutCutoff() {
            // FR-902's three columns: closed_at, closed_by, version_cutoff_at. Two of three is
            // not closed.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AccountingPeriod(
                    202704, "FY2027-28", APRIL_START, APRIL_END, PeriodStatus.CLOSED,
                    CLOSING_BEGAN, CLOSED_AT, "financial.controller", null))
                .withMessageContaining("ck_accounting_period_closure_attested");
        }

        @Test
        @DisplayName("ck_accounting_period_closure_attested: CLOSED with nobody named is refused")
        void closedWithoutASignatory() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AccountingPeriod(
                    202704, "FY2027-28", APRIL_START, APRIL_END, PeriodStatus.CLOSED,
                    CLOSING_BEGAN, CLOSED_AT, "   ", CUTOFF))
                .withMessageContaining("names nobody");
        }

        @Test
        @DisplayName("a CLOSING period carrying a closed_at is refused")
        void attestationWithoutTheStatus() {
            // Not a DDL constraint, and it cannot usefully be one: a CLOSING row with a
            // closed_at reads as closed to anything testing the timestamp instead of the status.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AccountingPeriod(
                    202704, "FY2027-28", APRIL_START, APRIL_END, PeriodStatus.CLOSING,
                    CLOSING_BEGAN, CLOSED_AT, "financial.controller", CUTOFF))
                .withMessageContaining("attestation and status move together");
        }

        @Test
        @DisplayName("a rehydrated CLOSED row is representable, so CL-1 has something to assert over")
        void closedRowIsRepresentable() {
            AccountingPeriod closed = new AccountingPeriod(
                202704, "FY2027-28", APRIL_START, APRIL_END, PeriodStatus.CLOSED,
                CLOSING_BEGAN, CLOSED_AT, "financial.controller", CUTOFF);

            assertThat(closed.isClosed()).isTrue();
            assertThat(closed.describe())
                .as("the audit sentence names all three attestation fields")
                .contains("financial.controller", "2027-05-05T10:00:00Z", "2027-05-05T09:30:00Z");
        }
    }

    @Nested
    @DisplayName("the OPEN / CLOSING / CLOSED life cycle")
    class LifeCycle {

        @Test
        @DisplayName("CLOSED is terminal: FR-902 gives it no successor at all")
        void closedIsTerminal() {
            assertThat(PeriodStatus.CLOSED.legalSuccessors())
                .as("no reopen edge; reopening a period is the mutation FR-902 forbids")
                .isEmpty();
            assertThat(PeriodStatus.CLOSED.canMoveTo(PeriodStatus.OPEN)).isFalse();
        }

        @Test
        @DisplayName("only CLOSING is closeable")
        void onlyClosingIsCloseable() {
            assertThat(PeriodStatus.OPEN.isCloseable()).isFalse();
            assertThat(PeriodStatus.CLOSING.isCloseable()).isTrue();
            assertThat(PeriodStatus.CLOSED.isCloseable()).isFalse();
        }

        @Test
        @DisplayName("an abandoned close returns to OPEN and clears closing_started_at")
        void abandonedCloseReopens() {
            // The ordinary consequence of step 4 finding red on an upstream cause.
            AccountingPeriod reopened = april().startClosing(CLOSING_BEGAN).abandonClose();

            assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
            assertThat(reopened.closingStartedAt())
                .as("the next close is a new one; its elapsed time is not measured from the"
                    + " abandoned attempt")
                .isNull();
        }

        @Test
        @DisplayName("closing an OPEN period directly is a wiring defect, and throws")
        void openPeriodCannotBeClosedDirectly() {
            // Unreachable through the gate, which refuses PERIOD_NOT_IN_CLOSING as a value. The
            // throw is for a caller bypassing the gate.
            AccountingPeriod open = april();
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> open.attestedClose("financial.controller", CLOSED_AT, CUTOFF))
                .withMessageContaining("cannot move to CLOSED");
        }

        @Test
        @DisplayName("a closed period refuses a second close, naming FR-902")
        void closedPeriodRefusesASecondClose() {
            AccountingPeriod closed = april().startClosing(CLOSING_BEGAN)
                .attestedClose("financial.controller", CLOSED_AT, CUTOFF);

            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> closed.attestedClose("someone.else", CLOSED_AT, CUTOFF))
                .withMessageContaining("CLOSED is terminal (FR-902)");
        }
    }

    @Nested
    @DisplayName("periodIdOf: one spelling of the partition key")
    class PeriodIdArithmetic {

        @Test
        @DisplayName("YYYYMM, matching the DDL's EXTRACT arithmetic")
        void encodesYearAndMonth() {
            // 2027 * 100 + 4 = 202704; 2030 * 100 + 3 = 203003 (the ACPIR 21 deadline month).
            assertThat(AccountingPeriod.periodIdOf(LocalDate.of(2027, 4, 1))).isEqualTo(202704);
            assertThat(AccountingPeriod.periodIdOf(LocalDate.of(2027, 4, 30))).isEqualTo(202704);
            assertThat(AccountingPeriod.periodIdOf(LocalDate.of(2030, 3, 31))).isEqualTo(203003);
        }

        @Test
        @DisplayName("covers is inclusive at both ends")
        void coversIsInclusive() {
            AccountingPeriod april = april();
            assertThat(april.covers(APRIL_START)).isTrue();
            assertThat(april.covers(APRIL_END)).isTrue();
            assertThat(april.covers(LocalDate.of(2027, 5, 1))).isFalse();
            assertThat(april.covers(LocalDate.of(2027, 3, 31))).isFalse();
        }
    }
}
