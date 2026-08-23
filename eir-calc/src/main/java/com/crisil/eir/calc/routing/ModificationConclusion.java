package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Mechanism;

/**
 * Where the substantiality assessment lands (calculation specification
 * section 6.4).
 *
 * <p>Three outcomes, not two. The third is the design point: an engine that can
 * only answer "substantial" or "not substantial" has no way to say "this is a
 * judgement and the evidence does not settle it", so it answers anyway — and on
 * the asset side, where IFRS 9 sets no bright line, it answers without authority.
 */
public enum ModificationConclusion {

    /**
     * Derecognise the original and recognise a new asset or liability at fair value
     * with a fresh EIR (IFRS 9 3.2.3, B3.3.6).
     */
    SUBSTANTIAL,

    /**
     * Retain the original EIR, restate the gross carrying amount at that rate
     * against the revised flows and book the difference (IFRS 9 5.4.3) — the
     * catch-up of section 6.3.
     */
    NOT_SUBSTANTIAL,

    /**
     * The engine declines to conclude. A person decides, and who decided, on what
     * basis and under which policy version is recorded.
     */
    REQUIRES_APPROVAL;

    /** Whether the engine reached a conclusion it is entitled to act on. */
    public boolean isDecided() {
        return this != REQUIRES_APPROVAL;
    }

    /**
     * The accounting mechanism this conclusion implies.
     *
     * <p>Throws for {@link #REQUIRES_APPROVAL}, rather than returning
     * {@link Mechanism#NONE}. {@code NONE} would be read downstream as "no EIR
     * consequence" and the event would pass through the amortisation silently at
     * the original rate with no catch-up and no derecognition — a wrong number
     * produced by a missing check. A caller must consult {@link #isDecided()} and
     * route the event to approval; failing to is a programming error and gets an
     * exception.
     */
    public Mechanism mechanism() {
        return switch (this) {
            case SUBSTANTIAL -> Mechanism.DERECOGNITION;
            case NOT_SUBSTANTIAL -> Mechanism.CATCH_UP;
            case REQUIRES_APPROVAL -> throw new IllegalStateException(
                "no mechanism: the substantiality assessment requires approval and the engine must not "
                    + "decide it. Check isDecided() and route the event to the approval queue.");
        };
    }
}
