package com.crisil.eir.policy.routing;

/**
 * A routing table configuration file that cannot be read as one approved table
 * (ADR-0006).
 *
 * <p><strong>Why this throws instead of returning an
 * {@code InvariantResult}.</strong> The house rule is that a <em>data</em>
 * condition is reported, never thrown: a contract with an impossible schedule is
 * a fact about the portfolio, it must be recorded against that contract and the
 * run must continue for the other nine million. A malformed routing table is not
 * that. It is the engine's own configuration — the file that decides whether an
 * ESG ratchet resets the rate or books a catch-up, and therefore whether
 * reference case 3's month carries a 627.42 charge or nothing. There is no
 * contract to attach the failure to and no correct answer to continue with, so
 * the only safe outcome is that nothing computes: a run that half-loaded its
 * routing table would silently route some drivers by policy and the rest by
 * accident. Treat this as a deploy-time defect, surfaced at load, not as an
 * exception-queue item.
 *
 * <p>It extends {@link IllegalArgumentException} because that is what
 * {@code RoutingTable} and {@code RoutingTableVersion} already throw for a
 * structurally impossible table, so a caller that guards a policy reload with
 * {@code catch (IllegalArgumentException)} keeps working and gains the line
 * number.
 *
 * <p>The offending location travels with the exception rather than only inside
 * the message, so a loader can report {@code file:line} in its own format — an
 * operator editing an approved table needs to be told <em>which line</em>, not
 * that "parsing failed".
 */
public final class RoutingTableFormatException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Line number sentinel for a fault that belongs to the file as a whole.
     *
     * <p>Public because this type exists so that a loader in another package can
     * report {@code file:line} in its own format, and it cannot do that without the
     * sentinel that says "no line" — a missing driver row has no line to point at.
     */
    public static final int NO_LINE = 0;

    private final String sourceName;
    private final int lineNumber;
    private final String line;

    RoutingTableFormatException(String message, String sourceName, int lineNumber, String line) {
        this(message, sourceName, lineNumber, line, null);
    }

    RoutingTableFormatException(
            String message, String sourceName, int lineNumber, String line, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName;
        this.lineNumber = lineNumber;
        this.line = line;
    }

    /** Where the text came from — a file path, or a marker for in-memory text. */
    public String sourceName() {
        return sourceName;
    }

    /**
     * The 1-based line that is at fault, or {@link #NO_LINE} when the fault is a
     * property of the whole file — a missing driver row has no line to point at,
     * which is precisely what makes it easy to miss on review.
     */
    public int lineNumber() {
        return lineNumber;
    }

    /** The offending line's text, or {@code null} for a whole-file fault. */
    public String line() {
        return line;
    }
}
