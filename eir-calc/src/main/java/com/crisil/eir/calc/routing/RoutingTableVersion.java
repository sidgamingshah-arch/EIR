package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.FourEyes;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The identity and approval trail of one {@link RoutingTable} (ADR-0006).
 *
 * <p>Routing is versioned <em>data</em> rather than code because the rule being
 * encoded is under active revision: ACPIR is silent on subsequent changes in cash
 * flows, the engine adopts the IFRS 9 B5.4.5/B5.4.6 mechanics by election, and the
 * IASB tentatively decided in April 2026 to amend B5.4.5 with an Exposure Draft
 * planned for H2 2026. When that wording lands the response must be an approved
 * policy version — not a code change, a regression cycle and a release.
 *
 * <p>This record is the reason replay survives the change. Every routed event
 * stores the {@link #id} that routed it (see
 * {@link RoutingDecision#routingTableVersionId()}), so a period closed under the
 * old reading replays under the old reading. Were routing code, a replay would
 * silently apply today's logic to yesterday's events and invariant DT-1 would
 * either fail or — worse — pass while being wrong.
 *
 * <p>A version is immutable once approved. There is deliberately no wither and no
 * mutable field: a change to the mapping is a <em>new</em> version through
 * {@link RoutingTable#reroute}, carrying its own maker, checker and impact
 * preview. Editing an approved version in place would destroy the only record of
 * what was in force when a closed period was computed.
 *
 * <p>{@code maker} and {@code checker} must differ. Four-eyes on a routing change
 * is not ceremony here: reference cases 3 and 4 are the same instrument in the
 * same month, and the mapping alone decides whether the month carries a 627.42
 * charge or nothing at all.
 *
 * @param id            stable identifier persisted on every event this version routes
 * @param description   what interpretation this version encodes, in the policy's own words
 * @param effectiveFrom first date on which this version governs newly routed events
 * @param maker         identity that proposed the version
 * @param checker       identity that approved it; must not be the maker
 * @param approvedOn    date the checker approved it
 */
public record RoutingTableVersion(
    String id,
    String description,
    LocalDate effectiveFrom,
    String maker,
    String checker,
    LocalDate approvedOn) {

    public RoutingTableVersion {
        id = requireText(id, "id");
        description = requireText(description, "description");
        maker = requireText(maker, "maker");
        checker = requireText(checker, "checker");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(approvedOn, "approvedOn");
        // Case-folded as well as stripped, through the one comparison every four-eyes control in
        // the codebase now shares. This guard used to be a raw equals(), which made it the ONLY
        // one of four statements of the rule that accepted "Policy.Author" approving
        // "policy.author" — while the DDL column it is stored in rejects exactly that row. A
        // routing table is the artefact ADR-0006 rests on: it changes how every event is treated,
        // and it could be self-approved by capitalising a letter.
        if (FourEyes.isSelfApproval(maker, checker)) {
            throw new IllegalArgumentException(
                "maker and checker must differ; a routing table approved by its own maker is not approved: " + maker);
        }
    }

    /**
     * Whether this version governs events routed on {@code asOf}.
     *
     * <p>Selection by date applies to <em>new</em> routings only. A replay never
     * re-selects by date — it uses the version id stored on the event, which is
     * the whole point of storing it.
     */
    public boolean isEffectiveOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !asOf.isBefore(effectiveFrom);
    }

    /**
     * Whether approval post-dates the effective date.
     *
     * <p>Permitted rather than rejected, because a baseline policy approved
     * part-way through a parallel-run build legitimately takes effect from the
     * start of that build. It is flagged because it is a restatement decision
     * needing its own trail, not a routine version: events already routed under
     * the superseded version keep that version's id and continue to replay under
     * it, so a retrospective version can never silently re-route a closed period —
     * it can only be the basis of an explicit restatement.
     */
    public boolean isRetrospective() {
        return approvedOn.isAfter(effectiveFrom);
    }

    private static String requireText(String value, String field) {
        return FourEyes.requireIdentity(value, field, "a routing table version cites it");
    }
}
