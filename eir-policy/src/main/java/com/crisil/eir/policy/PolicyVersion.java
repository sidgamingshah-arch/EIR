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
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(status, "status");
        // Normalised BEFORE the self-approval comparison, and that order is the whole point.
        // Comparing raw strings lets any whitespace or case variant defeat the guard: a maker
        // of "policy.author" against a checker of "policy.author " is one person, and a raw
        // equals() says they are two. RoutingTableVersion — which this type generalises — gets
        // this right by stripping inside its requireText before comparing; generalising it
        // dropped the normalisation and voided the guarantee stated above for the one control
        // ADR-0008 makes load-bearing. Case is folded as well as stripped, because an identity
        // directory that treats "Policy.Author" and "policy.author" as two people is not one
        // this control can rely on.
        id = requireText(id, "id",
            "a policy version needs an identifier to be cited by");
        description = requireText(description, "description",
            "an unexplained version is not an audit trail");
        maker = requireText(maker, "maker", "a version with no maker has no author");
        checker = checker == null || checker.isBlank() ? null : checker.strip();
        if (checker != null && maker.equalsIgnoreCase(checker)) {
            throw new IllegalArgumentException(
                "policy version " + id + " has maker and checker both '" + maker + "'."
                    + " Self-approval is not a defective approval, it is the absence of one");
        }
        if (status.isApproved()) {
            if (checker == null) {
                throw new IllegalArgumentException(
                    "policy version " + id + " is " + status + " with no checker named");
            }
            if (approvedOn == null) {
                throw new IllegalArgumentException(
                    "policy version " + id + " is " + status + " with no approval date");
            }
        }
    }

    /**
     * Whether this version governs {@code date} — <em>necessary, not sufficient</em>.
     *
     * <p>Open-ended on purpose, and the consequence has to be understood before this is used to
     * select a version. There is no {@code effectiveTo}, and {@link PolicyVersionStatus#SUPERSEDED}
     * is operative so that a closed period stays replayable (invariant DT-1). So a superseded
     * version answers {@code true} for every date from its own {@code effectiveFrom} onward,
     * forever, and two versions of one kind will happily both answer {@code true} for the same
     * date. This predicate therefore says "this version had taken effect by then", not "this is
     * the version in force".
     *
     * <p><b>Resolution is latest-wins</b>, and it belongs to the registry rather than here: among
     * the versions of a kind whose status is operative and whose {@code effectiveFrom} is not
     * after the date, the one in force is the one with the <em>greatest</em>
     * {@code effectiveFrom}. That is the standard temporal-table rule and it needs no end-date
     * column — which is why none was added. It follows that the only genuinely ambiguous
     * configuration is two versions of the same kind sharing an <em>identical</em>
     * {@code effectiveFrom}, and that is what a registry must reject at construction:
     * "whichever we found first" is not an accounting answer.
     */
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

    /**
     * A one-line audit sentence naming this version and its approval.
     *
     * <p>The approval clause is gated on {@link PolicyVersionStatus#isApproved()} and not merely
     * on a checker being named, because the constructor deliberately permits an unapproved
     * version to carry a proposed checker — only self-approval is refused. Reading the clause off
     * the field alone rendered a {@code DRAFT} as "DRAFT, made by X, approved by Y on D": a
     * sentence that contradicts itself in its own second clause, where the half a reader trusts
     * is the false half.
     */
    public String describe() {
        return kind + " version " + id + " effective " + effectiveFrom + ", " + status
            + ", made by " + maker
            + (status.isApproved()
                ? ", approved by " + checker + (approvedOn == null ? "" : " on " + approvedOn)
                : checker == null ? ", unapproved" : ", unapproved (proposed checker " + checker + ")")
            + (isRetrospective() ? " (RETROSPECTIVE)" : "");
    }

    private static String requireText(String value, String field, String why) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank: " + why);
        }
        return stripped;
    }
}
