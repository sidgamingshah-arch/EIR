package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.util.Objects;

/**
 * What one event was routed to, and by which approved table.
 *
 * <p>The {@link #routingTableVersionId} is the load-bearing field. It is persisted
 * on the lifecycle event, and it is what makes replay correct across a change in
 * the reading (ADR-0006): a period closed under the old mapping replays under the
 * old mapping, because the event names the version that routed it rather than
 * inviting the engine to look the routing up again with today's table. A replay
 * that re-derived routing from current configuration would silently apply today's
 * logic to yesterday's events — and invariant DT-1 would either fail, or pass
 * while being wrong.
 *
 * <p>{@link #rationale} exists because the auditor's question is never "what did
 * the engine do" but "why". It states the driver, the mapping consulted and, where
 * the rate-type check fired, what it overrode.
 *
 * @param driver                    the event's mandatory driver tag (FR-504)
 * @param rateType                  the instrument's rate type, as much a routing input as the driver
 * @param mechanism                 the routed mechanism
 * @param routingTableVersionId     the {@link RoutingTableVersion#id()} in force for this routing
 * @param rationale                 audit-facing explanation of the routing
 * @param overriddenByRateTypeCheck whether the rate-type check, not the table row, set the mechanism
 */
public record RoutingDecision(
    RateDriver driver,
    RateType rateType,
    Mechanism mechanism,
    String routingTableVersionId,
    String rationale,
    boolean overriddenByRateTypeCheck) {

    public RoutingDecision {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(rateType, "rateType");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(routingTableVersionId, "routingTableVersionId");
        Objects.requireNonNull(rationale, "rationale");
        if (routingTableVersionId.strip().isEmpty()) {
            throw new IllegalArgumentException(
                "routingTableVersionId must not be blank; an event whose routing cannot be attributed "
                    + "to a table version cannot be replayed");
        }
        if (rationale.strip().isEmpty()) {
            throw new IllegalArgumentException("rationale must not be blank");
        }
    }

    /** Whether this routing hands the event to the substantiality assessment (section 6.4). */
    public boolean requiresModificationTest() {
        return mechanism == Mechanism.MODIFICATION_TEST;
    }

    /**
     * Whether this routing changes the persisted rate.
     *
     * <p>True only for {@link Mechanism#RESET}. Its complement is the precondition
     * of invariant CU-1: for a catch-up the rate before and after must be
     * bit-identical, which is the cheapest available detector of the defect where a
     * catch-up is discounted at a re-solved rate and thereby quietly becomes a
     * reset.
     */
    public boolean resolvesRate() {
        return mechanism == Mechanism.RESET;
    }
}
