package com.crisil.eir.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One projected cash flow.
 *
 * <p>{@code periodIndex} is the 1-based ordinal of the compounding period the
 * flow falls in, or 0 for a flow at inception. It is not derived from position in
 * the vector, because two flows can legitimately share a period: the B5.4.4
 * shortcut puts an instalment and a notional redemption on the same reset date,
 * and deriving the ordinal from position would give the second one the wrong
 * discount factor.
 *
 * @param date         when the flow occurs
 * @param periodIndex  compounding period ordinal; 0 at inception
 * @param amount       signed from the holder's perspective
 * @param kind         what the flow represents; never affects discounting
 * @param contingent   true where the flow depends on an event that may not occur
 */
public record CashFlow(
    LocalDate date,
    int periodIndex,
    Money amount,
    FlowKind kind,
    boolean contingent) {

    public CashFlow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
        if (periodIndex < 0) {
            throw new IllegalArgumentException("periodIndex must be non-negative, got " + periodIndex);
        }
    }

    /** A non-contingent flow, which is the ordinary case. */
    public static CashFlow of(LocalDate date, int periodIndex, Money amount, FlowKind kind) {
        return new CashFlow(date, periodIndex, amount, kind, false);
    }

    /**
     * A flow contingent on a future event — a prepayment penalty, a late fee, a
     * bounce charge. The projector rejects these: they are excluded from the
     * inception projection regardless of being contractually specified, and
     * recognised in the period the event occurs.
     */
    public static CashFlow contingent(LocalDate date, int periodIndex, Money amount, FlowKind kind) {
        return new CashFlow(date, periodIndex, amount, kind, true);
    }
}
