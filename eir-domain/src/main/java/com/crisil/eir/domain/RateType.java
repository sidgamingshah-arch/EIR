package com.crisil.eir.domain;

/**
 * Whether an instrument reprices off a benchmark by its own terms.
 *
 * <p>Load-bearing for event routing. A fixed-rate loan whose rate is renegotiated
 * is a modification, not a benchmark reset, even though both "look like the rate
 * moved". Routing keys off this together with the event's
 * {@link RateDriver} — never off the observation itself.
 */
public enum RateType {
    FIXED,
    FLOATING
}
