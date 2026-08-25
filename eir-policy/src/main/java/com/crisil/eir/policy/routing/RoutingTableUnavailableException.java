package com.crisil.eir.policy.routing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Raised where a routing is asked for and the registry holds no table that can
 * answer it — either nothing in force on the event date, or no table carrying the
 * version id a stored event names.
 *
 * <p><strong>Why this is an exception and not a default.</strong> A routing
 * decision has to name the approved table version that produced it: that id is
 * persisted on the {@code LIFECYCLE_EVENT} and is what makes replay correct across
 * a change in the reading (ADR-0006). A registry that answered "no table in force,
 * so use the newest one" would attach a version id to an event that version never
 * governed, and the resulting decision would look every bit as attributable as a
 * correct one. Every downstream control — the version id on the event, invariant
 * DT-1's replay, the auditor's question of which policy was in force — would agree
 * with each other and all be wrong together. So the engine refuses: the contract
 * routes to the exception queue as a configuration gap, which is what it is.
 *
 * <p>Modelled on {@code UnsupportedScheduleShapeException} in {@code eir-calc}, for
 * the same reason and with the same discipline of naming what was available. A gap
 * in routing configuration is diagnosable in seconds if the message says which
 * versions the registry did hold and from when; it is a morning's work if it says
 * only that a lookup failed.
 *
 * <p>The two identifying values are carried as fields as well as in the message,
 * because the caller this class anticipates writes an {@code EXCEPTION} row per
 * contract: it needs the unroutable event date, or the unresolvable version id, as
 * data. Recovering them by parsing the message would make the exception text a wire
 * format, and the first improvement to the wording would break the queue.
 */
public class RoutingTableUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The event date that resolved to no table, or null where this is a version miss. */
    private final LocalDate eventDate;

    /** The version id that was not held, or null where this is a date miss. */
    private final String versionId;

    private RoutingTableUnavailableException(
        String message, LocalDate eventDate, String versionId) {
        super(message);
        this.eventDate = eventDate;
        this.versionId = versionId;
    }

    /**
     * The event date that could not be routed, where this is a date miss.
     *
     * <p>Empty for a version miss, which is the other way this exception arises.
     */
    public Optional<LocalDate> eventDate() {
        return Optional.ofNullable(eventDate);
    }

    /** The version id that this registry does not hold, where this is a version miss. */
    public Optional<String> versionId() {
        return Optional.ofNullable(versionId);
    }

    /**
     * No approved table takes effect on or before {@code eventDate}.
     *
     * <p>This is the front-edge gap, and in practice it is the migration case: a
     * bank whose earliest approved table is effective 1 April 2027 replaying a
     * transition-date event dated 31 March 2027. It has to fail rather than borrow
     * the 2027 reading, because the two readings produce materially different P&amp;L
     * — reference cases 3 and 4 are the same instrument in the same month, one
     * carrying a 627.42 charge and the other nothing.
     */
    static RoutingTableUnavailableException noneInForce(
        LocalDate eventDate, LocalDate earliestEffective, List<String> heldVersions) {
        return new RoutingTableUnavailableException(
            "no approved routing table is in force on " + eventDate
                + "; the earliest table takes effect " + earliestEffective
                + ". A routing decision with no table is not a decision: it would attach a version "
                + "id to an event that version never governed. Held versions: " + heldVersions,
            eventDate, null);
    }

    /**
     * The registry holds no table carrying {@code versionId}.
     *
     * <p>Reached on replay, when a stored event names a version this registry was
     * not loaded with. Silently re-selecting by date instead would apply today's
     * reading to yesterday's event — exactly the failure ADR-0006 exists to prevent,
     * and one that DT-1 would either fail on or, worse, pass while being wrong.
     */
    static RoutingTableUnavailableException unknownVersion(
        String versionId, List<String> heldVersions) {
        return new RoutingTableUnavailableException(
            "routing table version '" + versionId + "' is not in this registry, so the event that "
                + "recorded it cannot be replayed under the reading that routed it. Re-selecting by "
                + "date would apply today's reading to a closed period. Held versions: "
                + heldVersions,
            null, versionId);
    }
}
