package com.crisil.eir.policy.reconciliation;

/**
 * Which of the two sources presented a figure for a contract in the period.
 *
 * <p><b>Why this is a category and not a zero.</b> A reconciliation written as "for each engine
 * line, look up the CBS figure, difference them" has one branch nobody writes a test for: the
 * lookup misses. The two natural things to do there are to skip the contract or to substitute
 * zero, and both report the same thing — a line that ties. That is how a reconciliation passes with
 * half a book unreconciled, and it is worse than a missing control because the report says
 * "reconciled".
 *
 * <p>So the two one-sided states are named, counted and carried into RC-1's detail. The money still
 * flows through the ordinary arithmetic — the absent side contributes nothing, so the whole of the
 * present side's amount is a difference — because that is the honest answer to "by how much do the
 * two systems disagree about this account": by all of it. What the category adds is the diagnosis,
 * and the diagnosis is what differs. An {@link #BOTH} break is investigated on the schedule; a
 * one-sided break is investigated on the population — an extract truncated at a row limit, a book
 * or entity filter that differs between the two queries, an account opened or closed mid-period —
 * and none of those is found by looking at a contract's interest calculation.
 */
public enum SourcePresence {

    /** Both sources presented a figure. The ordinary case, and the only one that is a comparison. */
    BOTH,

    /**
     * The engine projected contractual interest for a contract the CBS feed does not carry.
     *
     * <p>Reads as the engine measuring an exposure the book of record does not have — a contract
     * closed or written off in the CBS while the engine still holds a live schedule, or an extract
     * that dropped rows.
     */
    ENGINE_ONLY,

    /**
     * The CBS billed interest on a contract the engine did not project.
     *
     * <p>The more serious direction on ADR-0004's reasoning: the CBS is the book of record, so this
     * is interest the borrower was billed on an exposure the engine is not measuring at all. No EIR
     * leg exists for it either, which means it is absent from recognised income rather than merely
     * mis-stated.
     */
    CBS_ONLY;

    /** Whether only one source presented the contract. */
    public boolean isOneSided() {
        return this != BOTH;
    }

    /** A phrase for the audit sentence on a break. */
    public String statement() {
        return switch (this) {
            case BOTH -> "presented by both sources";
            case ENGINE_ONLY -> "projected by the engine, absent from the CBS feed";
            case CBS_ONLY -> "billed by the CBS, not projected by the engine";
        };
    }
}
