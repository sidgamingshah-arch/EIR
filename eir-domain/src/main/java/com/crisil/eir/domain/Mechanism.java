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
    NONE;

    /**
     * Whether a versioned routing table may name this mechanism as a driver's treatment.
     *
     * <p><b>Four of the five may; {@link #DERECOGNITION} may not, and the distinction is between a
     * routing and a conclusion.</b> A routing says "events with this driver tag are treated this
     * way", which is a policy position a bank takes once and has approved. Derecognition is not
     * available as a position: IFRS 9 reaches it only through the substantiality assessment, run
     * per modification, and {@link #MODIFICATION_TEST} is the routing that gets an event there.
     * {@code ModificationConclusion.SUBSTANTIAL.mechanism()} returning {@code DERECOGNITION} is the
     * only legitimate way this value arises, and it arises per instrument from evidence.
     *
     * <p><b>Why the check is here and not in the routing table's parser.</b>
     * {@code RoutingTableFormat}'s own tests state the principle — "the format's job is to say what
     * the file means, not to hold an accounting opinion that {@code RoutingTable} itself does not
     * hold" — and they are right. The opinion belongs to the domain, so it lives on the type that
     * names the mechanisms, and every table, parsed or constructed, is held to it.
     *
     * <p><b>{@code NONE} is routable, and deliberately.</b> A driver a bank has elected as
     * immaterial — a late drawdown whose present-value effect is not worth restating for — is a
     * position it is entitled to take and to have approved. The engine's obligation is then to roll
     * the period forward at the unchanged rate and restate nothing, which is what this value means.
     * Refusing {@code NONE} here would move an accounting election into a build-time constraint.
     */
    public boolean isRoutable() {
        return this != DERECOGNITION;
    }
}
