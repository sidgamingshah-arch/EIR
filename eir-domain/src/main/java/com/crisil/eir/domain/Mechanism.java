package com.crisil.eir.domain;

/**
 * What happens when projected cash flows change.
 *
 * <p>The two substantive mechanisms produce materially different P&amp;L on the
 * same instrument in the same month — see reference cases 3 and 4, where one
 * yields a 627.42 charge and the other nothing. Which applies is decided by a
 * versioned mapping from {@link RateDriver}, never by inspecting the rate.
 */
public enum Mechanism {

    /**
     * IFRS 9 B5.4.5. Re-solve the EIR over remaining flows from the
     * <em>current</em> carrying amount. Carrying amount unchanged, no catch-up.
     */
    RESET,

    /**
     * IFRS 9 B5.4.6. Retain the <em>original</em> EIR; restate the gross carrying
     * amount to the present value of revised flows at that rate; recognise the
     * difference in P&amp;L immediately.
     */
    CATCH_UP,

    /**
     * Run the substantiality assessment. The engine computes the 10% test as
     * evidence and evaluates qualitative triggers; it does not decide.
     */
    MODIFICATION_TEST,

    /** Derecognise and recognise a new asset at fair value with a fresh EIR. */
    DERECOGNITION,

    /** No EIR consequence — an allowance remeasurement, a staging change. */
    NONE
}
