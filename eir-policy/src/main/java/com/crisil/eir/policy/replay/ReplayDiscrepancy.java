package com.crisil.eir.policy.replay;

import java.util.Objects;

/**
 * One thing a replay did not reproduce — a figure or a policy version.
 *
 * <p>Both sides are held as <em>rendered strings</em> rather than as {@code Money} or
 * {@code BigDecimal}, and that is deliberate rather than lazy. What DT-1 asserts about a figure is
 * a claim about the characters that were published (see {@link ReplayFigure}), so a finding that
 * held a {@code Money} would hand its reader a value whose {@code toString} may not preserve the
 * very property that was breached. Holding {@code "INR 1.0"} against {@code "INR 1.00"} makes a
 * scale-only breach legible in a log line, an exception-queue entry and an audit workpaper without
 * the reader needing to know that {@code Money.equals} would have called them equal.
 *
 * <p>No deviation field. The size lives on the one DT-1 result and is a count; a per-finding
 * amount would invite summing money differences across a population, and the difference this
 * package exists to catch has no money size at all.
 *
 * <p><b>{@code expected} and {@code found} rather than {@code published} and {@code replayed}.</b>
 * For a figure the expectation is the published artefact and the finding is what the replay
 * produced, which is what the obvious names would have said. For
 * {@link DiscrepancyKind#POLICY_NOT_IN_FORCE_AT_PERIOD_END} both runs cite the same id and the
 * expectation comes from the policy <em>timeline</em> instead — so "published" would name the
 * wrong source on the one finding where the source is the point. {@link #kind} says which
 * expectation is in play; these two fields never have to.
 *
 * @param kind     what went wrong
 * @param subject  the figure key, or the {@link com.crisil.eir.policy.PolicyKind} name
 * @param expected what should have come back, rendered, or {@link #ABSENT}
 * @param found    what did, rendered, or {@link #ABSENT}
 */
public record ReplayDiscrepancy(
    DiscrepancyKind kind,
    String subject,
    String expected,
    String found) {

    /** What a side reads as when there is nothing on it. */
    public static final String ABSENT = "(absent)";

    public ReplayDiscrepancy {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(found, "found");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                "a discrepancy names what differed; an unnamed finding cannot be actioned");
        }
        subject = subject.strip();
    }

    /** Whether this finding is about policy resolution rather than about a figure. */
    public boolean isPolicyFinding() {
        return kind.isPolicyFinding();
    }

    /** One line, for the DT-1 detail and for a workpaper. */
    public String describe() {
        return subject + ": " + kind.statement() + " — expected " + expected + ", found " + found;
    }

    @Override
    public String toString() {
        return describe();
    }
}
