package com.crisil.eir.policy;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One approved version of one kind of policy — the spine of 04 § 2.12's {@code POLICY_VERSION}
 * and the carrier FR-210's maker–checker and impact-preview gates hang off.
 *
 * <p>Modelled on {@code RoutingTableVersion} in {@code eir-calc.routing}, which already carries
 * exactly this shape for the routing table alone. This generalises it across
 * {@link PolicyKind} without disturbing that type: the routing table keeps its own version
 * record, and the registry that selects among versions works on this one. Two types stating the
 * same fields is a cost, and it is the smaller cost — collapsing them would mean editing a
 * mature eir-calc type that eleven other work units also touch indirectly.
 *
 * <p><b>Maker and checker are enforced distinct at construction.</b> Not by a workflow step
 * that can be skipped, and not by a review convention. A version whose maker signed off their
 * own change is not a version with a defective approval; it is not an approved version at all,
 * and the type refuses to represent one. ACPIR requires the computation to be system-driven
 * with no manual intervention (ADR-0008), which makes the approval record the only evidence
 * that a figure's governing policy was ever looked at by a second person.
 *
 * @param id           stable identifier, e.g. {@code "FEE-2027.1"}; what a computation cites
 * @param kind         what this version governs
 * @param description  why this version exists, for the audit trail
 * @param effectiveFrom first date this version governs; approval may precede it
 * @param maker        who authored the change
 * @param checker      who approved it; never equal to {@code maker}
 * @param approvedOn   when the checker signed off, or null while unapproved
 * @param status       where it sits in its approval life
 */
public record PolicyVersion(
    String id,
    PolicyKind kind,
    String description,
    LocalDate effectiveFrom,
    String maker,
    String checker,
    LocalDate approvedOn,
    PolicyVersionStatus status) {

    public PolicyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(status, "status");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a policy version needs an identifier to be cited by");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException(
                "policy version " + id + " has no description; an unexplained version is not an"
                    + " audit trail");
        }
        if (maker.isBlank()) {
            throw new IllegalArgumentException("policy version " + id + " names no maker");
        }
        // Checker may be absent while the version is unapproved, but never equal to the maker.
        if (checker != null && !checker.isBlank() && maker.equals(checker)) {
            throw new IllegalArgumentException(
                "policy version " + id + " has maker and checker both '" + maker + "'."
                    + " Self-approval is not a defective approval, it is the absence of one");
        }
        if (status.isApproved()) {
            if (checker == null || checker.isBlank()) {
                throw new IllegalArgumentException(
                    "policy version " + id + " is " + status + " with no checker named");
            }
            if (approvedOn == null) {
                throw new IllegalArgumentException(
                    "policy version " + id + " is " + status + " with no approval date");
            }
        }
    }

    /** Whether this version governs {@code date}. */
    public boolean isEffectiveOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return status.isOperative() && !date.isBefore(effectiveFrom);
    }

    /**
     * Whether this version takes effect before it was approved.
     *
     * <p>Not forbidden — a correction to a closed-period misclassification is legitimately
     * retrospective — but it is the case that must never happen silently, so it is asked
     * rather than assumed. A retrospective version restates figures somebody has already
     * reported.
     */
    public boolean isRetrospective() {
        return approvedOn != null && effectiveFrom.isBefore(approvedOn);
    }

    /** The same version at a new status, for the maker–checker gate to advance. */
    public PolicyVersion withStatus(PolicyVersionStatus newStatus) {
        return new PolicyVersion(
            id, kind, description, effectiveFrom, maker, checker, approvedOn, newStatus);
    }

    /** A one-line audit sentence naming this version and its approval. */
    public String describe() {
        return kind + " version " + id + " effective " + effectiveFrom + ", " + status
            + ", made by " + maker
            + (checker == null || checker.isBlank() ? ", unapproved" : ", approved by " + checker
                + (approvedOn == null ? "" : " on " + approvedOn))
            + (isRetrospective() ? " (RETROSPECTIVE)" : "");
    }
}
