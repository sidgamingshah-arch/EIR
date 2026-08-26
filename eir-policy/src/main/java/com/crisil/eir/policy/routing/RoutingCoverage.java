package com.crisil.eir.policy.routing;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Whether every date in a window resolves to an approved routing table, and which
 * versions govern it — the pre-close routing coverage answer.
 *
 * <p>Reported rather than thrown. {@link RoutingTableRegistry#inForceOn} refuses a
 * single uncovered date loudly because it has one routing to produce and cannot
 * produce it; a close run asking about a whole period wants the gap stated once,
 * with its size, alongside its other results — not the first uncovered contract as a
 * stack trace.
 *
 * <p><strong>The facts, and separately the assertion.</strong> This record carries what was
 * measured; {@link #asInvariantResult()} publishes it under {@link InvariantId#RT_1}. The two
 * are kept apart because a caller wanting the gap's size and its ends should not have to parse
 * a detail string to get them.
 *
 * <p>{@code RT_1} rather than a borrowed id, and {@link InvariantId#DT_1} ("deterministic
 * replay") was the tempting wrong answer: an uncovered date does break replay, but DT-1 already
 * carries a different claim, and {@link InvariantResult#conjunction} documents putting two
 * claims under one identifier as a defect already found three times in this engine. It keeps
 * only the <em>first</em> failing result's deviation, so a run breaching both replay and routing
 * coverage would publish one DT-1 whose deviation is a day count while the replay figure
 * vanished — a control reporting on a projection nobody asked about.
 *
 * @param from                 first date of the window asked about, inclusive
 * @param to                   last date of the window asked about, inclusive
 * @param uncoveredDays        days in the window that resolve to no approved table;
 *                             zero when the window is fully covered
 * @param firstUncovered       first uncovered date, or null when there is none
 * @param lastUncovered        last uncovered date, or null when there is none
 * @param governingVersionIds  every version governing part of the window, in force
 *                             order; empty only where nothing governs any of it
 */
public record RoutingCoverage(
    LocalDate from,
    LocalDate to,
    long uncoveredDays,
    LocalDate firstUncovered,
    LocalDate lastUncovered,
    List<String> governingVersionIds) {

    public RoutingCoverage {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(governingVersionIds, "governingVersionIds");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(
                "coverage window " + from + ".." + to + " ends before it begins");
        }
        if (uncoveredDays < 0) {
            throw new IllegalArgumentException("uncoveredDays cannot be negative");
        }
        // A gap has both ends or neither. Half of one would mean the size and the location
        // disagree, and a control report that cannot say where the gap is cannot be actioned.
        if ((uncoveredDays == 0) != (firstUncovered == null)) {
            throw new IllegalArgumentException(
                "an uncovered day count of " + uncoveredDays + " disagrees with a first uncovered "
                    + "date of " + firstUncovered);
        }
        if ((firstUncovered == null) != (lastUncovered == null)) {
            throw new IllegalArgumentException("a gap needs both ends or neither");
        }
        if (firstUncovered != null) {
            // The gap must be a real interval inside the window it is reported against, and
            // its size must be the size of that interval. This record is public and the
            // registry is not its only possible producer, so an unchecked one could publish
            // "no approved routing table for 5 day(s) from 2027-06-30 to 2027-06-01" — a
            // control report nobody can action, which is what the both-ends check above
            // already exists to prevent.
            if (lastUncovered.isBefore(firstUncovered)) {
                throw new IllegalArgumentException(
                    "gap " + firstUncovered + ".." + lastUncovered + " ends before it begins");
            }
            if (firstUncovered.isBefore(from) || lastUncovered.isAfter(to)) {
                throw new IllegalArgumentException(
                    "gap " + firstUncovered + ".." + lastUncovered + " falls outside the window "
                        + from + ".." + to + " it is reported against");
            }
            long span = ChronoUnit.DAYS.between(firstUncovered, lastUncovered) + 1;
            if (span != uncoveredDays) {
                throw new IllegalArgumentException(
                    "uncovered day count " + uncoveredDays + " contradicts the gap "
                        + firstUncovered + ".." + lastUncovered + ", which spans " + span
                        + " day(s) inclusive");
            }
        }
        governingVersionIds = List.copyOf(governingVersionIds);
    }

    /** Whether every date in the window resolves to an approved table. */
    public boolean isComplete() {
        return uncoveredDays == 0;
    }

    /**
     * Whether the window spans a change of reading.
     *
     * <p>Worth asking separately, because a covered window is not therefore a
     * uniformly-routed one: reference cases 3 and 4 are the same instrument in the same
     * month, one carrying a 627.42 charge and the other nothing, so two readings inside
     * one reporting period is a disclosure fact rather than a detail.
     */
    public boolean spansAChangeover() {
        return governingVersionIds.size() > 1;
    }

    /** The audit-facing sentence, naming every version that governs part of the window. */
    /**
     * This coverage as the assertion it supports — invariant {@link InvariantId#RT_1}.
     *
     * <p>Deviation is the uncovered <em>day count</em>, not a money amount: the breach is the
     * absence of a routing, which has no money size, and a count is what a close can act on and
     * sum across kinds. A caller wanting the ends of the gap reads them off the record rather
     * than out of the detail string.
     */
    public InvariantResult asInvariantResult() {
        return isComplete()
            ? InvariantResult.pass(InvariantId.RT_1, detail())
            : InvariantResult.fail(
                InvariantId.RT_1, detail(), java.math.BigDecimal.valueOf(uncoveredDays));
    }

    public String detail() {
        StringBuilder text = new StringBuilder("routing coverage ")
            .append(from).append("..").append(to).append(" — ");
        if (isComplete()) {
            text.append("in force throughout, under ")
                .append(governingVersionIds.size())
                .append(" version(s) ")
                .append(governingVersionIds);
        } else {
            text.append("no approved routing table for ")
                .append(uncoveredDays)
                .append(" day(s) from ")
                .append(firstUncovered)
                .append(" to ")
                .append(lastUncovered)
                .append(", in which a cash-flow-change event could not be routed to any version; "
                    + "versions governing the rest of the window: ")
                .append(governingVersionIds);
        }
        return text.toString();
    }

}
