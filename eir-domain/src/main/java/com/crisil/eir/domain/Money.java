package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * A currency amount carried at {@link Precision#WORKING} until it is explicitly
 * reduced to presentation scale.
 *
 * <p>Signed from the <em>holder's</em> perspective throughout: outflows negative,
 * inflows positive. For a liability the signs invert and the same arithmetic
 * applies unchanged, which is why the solver needs no liability-specific path.
 *
 * <p>{@code equals} compares by numeric value rather than by scale, so
 * {@code Money.of("1.0", INR)} equals {@code Money.of("1.00", INR)}. Record
 * default equality would use {@link BigDecimal#equals}, which is scale-sensitive
 * and would make {@code 1.0 != 1.00} — a footgun in a type whose whole job is
 * comparing amounts.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency INR = Currency.getInstance("INR");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    /** Convenience for the reference cases and tests, which are all INR. */
    public static Money inr(String amount) {
        return new Money(new BigDecimal(amount), INR);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount, Precision.WORKING), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount, Precision.WORKING), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor, Precision.WORKING), currency);
    }

    public Money dividedBy(BigDecimal divisor) {
        return new Money(amount.divide(divisor, Precision.WORKING), currency);
    }

    /**
     * Reduces to the currency's ISO 4217 minor units. This is the only place a
     * money value loses precision, and it is called once per persisted figure.
     */
    public Money atPresentationScale() {
        return new Money(Precision.round(amount, currency.getDefaultFractionDigits()), currency);
    }

    public int presentationScale() {
        return currency.getDefaultFractionDigits();
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public int signum() {
        return amount.signum();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money(BigDecimal otherAmount, Currency otherCurrency))) {
            return false;
        }
        return currency.equals(otherCurrency) && amount.compareTo(otherAmount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
