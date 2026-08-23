package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One line of a schedule the lending system actually billed.
 *
 * <p>The input to {@link ExternalScheduleProjector} and therefore to the
 * {@code LMS_AUTHORITATIVE} path (FR-102, ADR-0004). A line may be supplied
 * combined, as a {@link FlowKind#COMBINED_EMI}, or split into a principal line
 * and an interest line sharing one date and one period ordinal — which is why the
 * ordinal is carried explicitly rather than inferred from position.
 *
 * @param dueOn       the billed due date
 * @param periodIndex the 1-based period ordinal the line belongs to
 * @param amount      signed from the holder's perspective; a receipt is positive
 * @param kind        what the line represents; never affects discounting
 */
public record Instalment(LocalDate dueOn, int periodIndex, Money amount, FlowKind kind) {

    public Instalment {
        Objects.requireNonNull(dueOn, "dueOn");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
        if (periodIndex < 1) {
            throw new IllegalArgumentException(
                "a billed instalment sits in period 1 or later, got " + periodIndex);
        }
    }

    /** A combined instalment, which is what most retail schedules supply. */
    public static Instalment of(LocalDate dueOn, int periodIndex, Money amount) {
        return new Instalment(dueOn, periodIndex, amount, FlowKind.COMBINED_EMI);
    }

    /** A split line — principal or interest — sharing a date with its sibling. */
    public static Instalment of(LocalDate dueOn, int periodIndex, Money amount, FlowKind kind) {
        return new Instalment(dueOn, periodIndex, amount, kind);
    }

    CashFlow toCashFlow() {
        return CashFlow.of(dueOn, periodIndex, amount, kind);
    }
}
