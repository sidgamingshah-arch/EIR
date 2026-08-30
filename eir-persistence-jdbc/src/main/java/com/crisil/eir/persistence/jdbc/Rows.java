package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * Reading columns out of a {@link ResultSet} without leaving the exact numeric domain.
 *
 * <h2>Money and rates are read with {@code getBigDecimal}, and only with {@code getBigDecimal}</h2>
 *
 * <p>ADR-0002 bans binary floating point in the calculation path and the build enforces it, but the
 * ban a checkstyle rule can express is narrower than the defect. {@code ResultSet.getDouble} is not
 * spelled {@code double} anywhere at the call site: it compiles, it returns a value, and the value
 * is wrong in a way no later stage can detect.
 *
 * <p>The figures make it concrete. V1 stores money as {@code NUMERIC(24,6)} and rates as
 * {@code NUMERIC(20,12)} — 24 significant digits and 20 — and the DDL says why on the column:
 * "a money column that came out NUMERIC(24,2) parses without complaint and silently truncates every
 * working value that passes through it, which is unrecoverable once a period has closed". A
 * {@code double} carries about 15–17 significant decimal digits. Reading the reference case's
 * periodic EIR of {@code 0.010421491800} through a {@code double} and back loses the twelfth
 * decimal place, which is the place the rate is <em>stored to</em> and the place FR-404 says every
 * downstream period must use. A gross carrying amount of ₹5,28,407.32 accreting at a rate wrong in
 * the twelfth place drifts by a few paise a month, which is under every tolerance and never zero —
 * so no invariant fires, and the sub-ledger simply stops tying to the general ledger by an amount
 * nobody can attribute.
 *
 * <p>So there is no {@code getDouble} in this module, and no overload here that could become one.
 *
 * <h2>NULL is refused rather than defaulted</h2>
 *
 * <p>{@code getBigDecimal} returns {@code null} for a SQL NULL, and {@code new Money(null, ...)}
 * would fail several frames later with nothing to say which column was empty. Worse, the tempting
 * repair — {@code Optional.ofNullable(v).orElse(BigDecimal.ZERO)} — turns a missing balance into a
 * repaid loan. {@link #money} therefore names the column in its failure, and
 * {@link #moneyOrNull} exists separately so that a nullable column is nullable <em>because the
 * schema says so</em> and not because a reader forgot.
 */
final class Rows {

    private Rows() {
    }

    /**
     * A money column that the schema declares NOT NULL.
     *
     * @throws PersistenceFailure where the value is NULL, naming the column
     */
    static Money money(ResultSet rs, String column, Currency currency) throws SQLException {
        return Money.of(requiredDecimal(rs, column), Objects.requireNonNull(currency, "currency"));
    }

    /** A money column the schema declares nullable; {@code null} where it is NULL. */
    static Money moneyOrNull(ResultSet rs, String column, Currency currency) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : Money.of(value, currency);
    }

    /**
     * A rate column, wrapped in the {@link Rate} its compounding frequency implies.
     *
     * <p>{@code periodsPerYear} is supplied by the caller from {@code compounding_basis} and is not
     * guessable here. Defaulting it to 12 would make an annual-compounding contract's rate read as a
     * monthly one, which understates the effective annual rate by a factor of about twelve and does
     * so without any figure looking odd on the row.
     */
    static Rate rate(ResultSet rs, String column, int periodsPerYear) throws SQLException {
        return Rate.periodic(requiredDecimal(rs, column), periodsPerYear);
    }

    /** A nullable rate column; {@code null} where it is NULL. */
    static Rate rateOrNull(ResultSet rs, String column, int periodsPerYear) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : Rate.periodic(value, periodsPerYear);
    }

    /** A nullable numeric column, exact. */
    static BigDecimal decimalOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    /** A NOT NULL text column, stripped. */
    static String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            throw missing(column, "text");
        }
        return value.strip();
    }

    /** A nullable text column; {@code null} where NULL or blank. */
    static String textOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** A NOT NULL {@code TIMESTAMPTZ} as an absolute instant. */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) {
            throw missing(column, "timestamp");
        }
        return value.toInstant();
    }

    /** A nullable {@code TIMESTAMPTZ}. */
    static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A NOT NULL {@code DATE}. */
    static LocalDate date(ResultSet rs, String column) throws SQLException {
        LocalDate value = rs.getObject(column, LocalDate.class);
        if (value == null) {
            throw missing(column, "date");
        }
        return value;
    }

    /** A nullable {@code DATE}. */
    static LocalDate dateOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    /** A NOT NULL integer or smallint. */
    static int integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        if (rs.wasNull()) {
            throw missing(column, "integer");
        }
        return value;
    }

    /**
     * A nullable integer, as a boxed value.
     *
     * <p>{@code getInt} returns 0 for a SQL NULL and only {@code wasNull()} distinguishes the two.
     * For {@code moratorium_months} that difference is a contract with no moratorium against one
     * whose moratorium length was not supplied, and V1's
     * {@code contract_version_moratorium_months_ck} refuses the second — so the distinction is
     * carried rather than collapsed.
     */
    static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }

    /** ISO 4217 from a {@code CHAR(3)} column. */
    static Currency currency(ResultSet rs, String column) throws SQLException {
        String code = text(rs, column);
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "currency code '" + code + "' in column " + column + " is not ISO 4217; the"
                    + " presentation scale of every figure on this contract is derived from it"
                    + " (04 § 2.1), so an unknown code cannot be defaulted");
        }
    }

    private static BigDecimal requiredDecimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        if (value == null) {
            throw missing(column, "numeric");
        }
        return value;
    }

    private static PersistenceFailure missing(String column, String kind) {
        return new PersistenceFailure(
            "column " + column + " is NULL where the schema declares it NOT NULL and this read"
                + " needs a " + kind + " value; defaulting it here would substitute a plausible"
                + " figure for a missing one, and a zero balance on a live contract reconciles to"
                + " nothing while looking like a repaid loan");
    }
}
