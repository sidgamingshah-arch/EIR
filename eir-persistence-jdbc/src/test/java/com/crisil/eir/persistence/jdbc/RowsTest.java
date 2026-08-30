package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Column reading: exactness, and the refusal to substitute a figure for a missing one.
 *
 * <p>Expected values are the reference case's own figures, written out by hand: periodic EIR
 * {@code 0.010421491800} at twelve decimal places, month 13 opening gross carrying amount
 * {@code 528407.32}. Nothing is read back from the code under test.
 */
class RowsTest {

    private static final Currency INR = Currency.getInstance("INR");

    private static ResultSet row(String column, Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(column, value);
        return FakeResultSet.of(values);
    }

    @Nested
    @DisplayName("exactness")
    class Exactness {

        @Test
        @DisplayName("a rate keeps all twelve decimal places the column stores")
        void rateKeepsTwelveDecimals() throws Exception {
            // NUMERIC(20,12) returns exactly this scale. FR-404 makes the stored rate the one every
            // downstream period uses, so the twelfth place is not decoration.
            Rate rate = Rows.rate(
                row("rate_periodic", new BigDecimal("0.010421491800")), "rate_periodic", 12);

            assertThat(rate.periodic().toPlainString()).isEqualTo("0.010421491800");
            assertThat(rate.periodsPerYear()).isEqualTo(12);
        }

        @Test
        @DisplayName("a money value keeps its six decimal places, above presentation scale")
        void moneyKeepsSixDecimals() throws Exception {
            // 04 § 4: six decimals "holds working values above presentation scale". A working value
            // stored at 2 decimals is unrecoverable once a period has closed.
            Money money = Rows.money(
                row("closing_gca", new BigDecimal("486840.640000")), "closing_gca", INR);

            assertThat(money.amount().toPlainString()).isEqualTo("486840.640000");
            assertThat(money.atPresentationScale().amount().toPlainString()).isEqualTo("486840.64");
        }

        @Test
        @DisplayName("the reference EIR survives a BigDecimal read and would not survive a double")
        @SuppressWarnings("PMD")
        void aDoubleWouldLoseTheTwelfthPlace() throws Exception {
            // The one place in this module where binary floating point appears, and it appears in
            // order to be shown losing. ADR-0002's checkstyle ban covers src/main/java only
            // (the root pom sets includeTestSourceDirectory=false), which is what makes this
            // demonstration possible at all — and the demonstration is why there is no getDouble
            // anywhere in Rows.
            BigDecimal stored = new BigDecimal("0.010421491800");
            BigDecimal viaBigDecimal =
                Rows.rate(row("rate_periodic", stored), "rate_periodic", 12).periodic();

            // Exact: the same unscaled digits at the same scale.
            assertThat(viaBigDecimal).isEqualByComparingTo(stored);
            assertThat(viaBigDecimal.toPlainString()).isEqualTo("0.010421491800");

            // The same figure through the nearest binary value, which is what ResultSet.getDouble
            // would hand back. 0.0104214918 has no exact binary representation, so the round trip
            // introduces digits beyond the twelfth place that the database never stored.
            BigDecimal viaBinary = new BigDecimal(0.0104214918d);
            assertThat(viaBinary.toPlainString())
                .as("the binary value is not the stored decimal; that difference, accreted over"
                    + " 360 periods, is a reconciliation break with no single diagnosable cause")
                .isNotEqualTo("0.010421491800");
            assertThat(viaBinary.compareTo(stored))
                .as("and it is not even equal in value")
                .isNotZero();
        }
    }

    @Nested
    @DisplayName("NULL is refused, not defaulted")
    class NullHandling {

        @Test
        @DisplayName("a NULL money column names itself in the failure")
        void nullMoneyIsRefused() {
            assertThatThrownBy(() -> Rows.money(row("closing_gca", null), "closing_gca", INR))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("closing_gca")
                .hasMessageContaining("looking like a repaid loan");
        }

        @Test
        @DisplayName("a nullable money column returns null rather than zero")
        void nullableMoneyReturnsNull() throws Exception {
            // Zero and absent are different facts. ecl_pre_floor is nullable and V2's
            // ck_period_balance_floor_duality turns on "both or neither", which a substituted zero
            // would satisfy while destroying the comparison FR-609 asks for.
            assertThat(Rows.moneyOrNull(row("ecl_pre_floor", null), "ecl_pre_floor", INR)).isNull();
        }

        @Test
        @DisplayName("a stored zero and a SQL NULL are distinguished on an integer column")
        void integerOrNullDistinguishesZeroFromNull() throws Exception {
            // getInt returns 0 for NULL and only wasNull() separates them. For moratorium_months
            // that is "no moratorium" against "length not supplied", and V1's
            // contract_version_moratorium_months_ck refuses the second.
            assertThat(Rows.integerOrNull(row("moratorium_months", null), "moratorium_months"))
                .isNull();
            assertThat(Rows.integerOrNull(row("moratorium_months", 0), "moratorium_months"))
                .isEqualTo(0);
            assertThat(Rows.integerOrNull(row("moratorium_months", 6), "moratorium_months"))
                .isEqualTo(6);
        }

        @Test
        @DisplayName("a blank text column reads as absent, so a blank approver is not an approver")
        void blankTextIsAbsent() throws Exception {
            // V1 keeps the SPPI triple whole or absent; a whitespace approver would satisfy a
            // NOT NULL check and name nobody.
            assertThat(Rows.textOrNull(row("sppi_approver", "   "), "sppi_approver")).isNull();
            assertThat(Rows.textOrNull(row("sppi_approver", " alice "), "sppi_approver"))
                .isEqualTo("alice");
        }
    }

    @Nested
    @DisplayName("currency")
    class CurrencyColumn {

        @Test
        @DisplayName("an ISO 4217 code resolves and drives presentation scale")
        void isoCodeResolves() throws Exception {
            assertThat(Rows.currency(row("currency", "INR"), "currency")).isEqualTo(INR);
            assertThat(Rows.currency(row("currency", "INR"), "currency").getDefaultFractionDigits())
                .isEqualTo(2);
        }

        @Test
        @DisplayName("an unknown code is refused rather than defaulted to INR")
        void unknownCodeIsRefused() {
            // Defaulting would put a foreign-currency contract's figures on the wrong presentation
            // scale, and RC-1 would then compare two numbers in different units numerically.
            assertThatThrownBy(() -> Rows.currency(row("currency", "XYZ"), "currency"))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("XYZ")
                .hasMessageContaining("ISO 4217");
        }
    }
}
