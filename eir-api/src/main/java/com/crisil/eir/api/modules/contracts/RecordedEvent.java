package com.crisil.eir.api.modules.contracts;

import com.crisil.eir.api.http.Json;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One routed event, as the module keeps it — the engine's own event log, not a bitemporal table.
 *
 * <p><b>What this is for.</b> {@code GET /api/contract/{id}/versions} (06 § 2) asks for a version
 * history, and the honest answer over an in-memory book is the series of events the engine actually
 * routed: initial recognition, then one version boundary per accepted event. That is a real series
 * with real {@code validFrom} dates, and it is not a claim about {@code recorded_at} —
 * {@code Book}'s own javadoc says a map holds one version of each row, and the versions response
 * says so too rather than letting a reader infer bitemporality this book does not have.
 *
 * <p><b>{@code routingTableVersionId} is the load-bearing field</b>, exactly as it is on
 * {@code RoutingDecision}: it is what makes the event replayable across a change of reading
 * (ADR-0006). A version history that re-derived routing from today's table would apply today's
 * logic to yesterday's events, and DT-1 would either fail or pass while being wrong.
 *
 * <p><b>{@code pendingApproval} is never collapsed into a mechanism.</b> Where the routing reached
 * a substantiality assessment and the engine declined to conclude (FR-511), this record carries no
 * EIR and no balance — because none was produced. Writing the pre-event figures into those fields
 * would make the log read as though the event had been assessed and found immaterial.
 *
 * @param eventId                 deterministic id, derived from the submission rather than a clock
 * @param contractId              the instrument the event was applied to
 * @param eventDate               the date the event occurred, and this version's {@code validFrom}
 * @param driver                  the event's mandatory driver tag (FR-504)
 * @param mechanism               the routed mechanism
 * @param routingTableVersionId   the approved table that routed it
 * @param eirAfter                the rate in force after the event, or null where none was produced
 * @param carryingAmountAfter     the balance after the event, or null where none was produced
 * @param pendingApproval         whether the engine declined to conclude and a person must decide
 * @param basis                   audit-facing one-line description of what this version records
 */
public record RecordedEvent(
    String eventId,
    String contractId,
    LocalDate eventDate,
    RateDriver driver,
    Mechanism mechanism,
    String routingTableVersionId,
    Rate eirAfter,
    Money carryingAmountAfter,
    boolean pendingApproval,
    String basis) {

    public RecordedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(routingTableVersionId, "routingTableVersionId");
        Objects.requireNonNull(basis, "basis");
        if (pendingApproval && (eirAfter != null || carryingAmountAfter != null)) {
            // A guard rather than a comment. An event awaiting a substantiality conclusion has no
            // rate and no restated balance, and a record carrying either would read downstream as
            // an assessed event that changed nothing — which is one of the two wrong answers
            // ModificationConclusion exists to make inexpressible.
            throw new IllegalArgumentException(
                "event " + eventId + " is pending approval and carries a rate or a balance;"
                    + " the engine does not decide a substantiality assessment (FR-511), so there is"
                    + " no post-event position to record and publishing the pre-event one would read"
                    + " as an assessment that concluded");
        }
    }

    /**
     * Deterministic and reproducible: the same submission yields the same id.
     *
     * <p>No clock and no counter. docs/06 § 1 requires an {@code Idempotency-Key} on every POST for
     * this reason, and an id derived from the submission's own identifying fields is strictly
     * better than a generated one — a retried submission is recognisably the same event rather than
     * a second event with the same effect.
     */
    public static String idFor(String contractId, LocalDate eventDate, RateDriver driver) {
        return "EV-" + contractId + "-" + eventDate + "-" + driver;
    }

    /** This event as one row of the version history. */
    public Json.Obj asVersionRow(int versionNo, LocalDate validTo) {
        return Json.object()
            .count("versionNo", versionNo)
            .str("validFrom", eventDate.toString())
            .str("validTo", validTo == null ? null : validTo.toString())
            .str("basis", basis)
            .str("eventId", eventId)
            .str("driver", driver.name())
            .str("routedMechanism", mechanism.name())
            .str("routingTableVersionId", routingTableVersionId)
            .figure("eir", eirAfter == null ? null : eirAfter.periodic())
            .figure("carryingAmount",
                carryingAmountAfter == null ? null : carryingAmountAfter.atPresentationScale().amount())
            .bool("pendingApproval", pendingApproval);
    }
}
