package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;

/**
 * Maps a cash-flow-change event to the mechanism that accounts for it (FR-504).
 *
 * <p>The routing inputs are exactly two: the event's driver tag — what the change
 * compensates for — and the instrument's rate type. Nothing else. In particular
 * not the product, and not the observation that a rate moved; see
 * {@link DefaultEventRouter} for why the second is the most expensive available
 * mistake in this domain.
 *
 * <p>The table is a parameter rather than a field so that a caller replaying a
 * closed period passes the version that period was computed under. An
 * implementation that captured a table at construction and used it for everything
 * would make historical replay depend on deployment configuration, which is
 * precisely the failure ADR-0006 exists to prevent.
 *
 * <p>Implementations are pure: no clock, no randomness, no state. The same three
 * arguments must give the same decision forever (invariant DT-1).
 */
public interface EventRouter {

    /**
     * Routes one event.
     *
     * @param driver   the event's driver tag; mandatory, because a defaulted
     *                 driver is a silently wrong routing (FR-504)
     * @param rateType the instrument's rate type
     * @param table    the approved mapping in force for this routing
     * @return the decision, carrying the table version id for replay
     */
    RoutingDecision route(RateDriver driver, RateType rateType, RoutingTable table);
}
