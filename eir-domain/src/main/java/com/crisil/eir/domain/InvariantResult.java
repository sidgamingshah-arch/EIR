package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The outcome of asserting one invariant, retained so that a run's invariant
 * results are reportable rather than merely thrown.
 *
 * @param id        which invariant
 * @param satisfied whether it held
 * @param detail    human-readable statement of what was compared
 * @param deviation the signed size of the breach, or zero when satisfied
 */
public record InvariantResult(InvariantId id, boolean satisfied, String detail, BigDecimal deviation) {

    public InvariantResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(deviation, "deviation");
    }

    public static InvariantResult pass(InvariantId id, String detail) {
        return new InvariantResult(id, true, detail, BigDecimal.ZERO);
    }

    public static InvariantResult fail(InvariantId id, String detail, BigDecimal deviation) {
        return new InvariantResult(id, false, detail, deviation);
    }

    /**
     * Asserts an expected equality of money amounts at presentation scale.
     *
     * <p>Comparison is at presentation scale on purpose: the invariant is a claim
     * about the figures that get published, and a working-precision comparison
     * would fail on a difference no reader could ever see.
     */
    public static InvariantResult ofMoney(InvariantId id, String detail, Money expected, Money actual) {
        Money e = expected.atPresentationScale();
        Money a = actual.atPresentationScale();
        if (e.equals(a)) {
            return pass(id, detail + " (" + a + ")");
        }
        return fail(id, detail + " — expected " + e + ", got " + a,
            a.minus(e).amount());
    }

    /** Throws if this result is a breach. */
    public InvariantResult orThrow() {
        if (!satisfied) {
            throw new InvariantBreachException(this);
        }
        return this;
    }
}
