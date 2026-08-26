package com.crisil.eir.policy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where a policy version sits in its approval life.
 *
 * <p>Five states rather than a boolean, because FR-210 requires maker–checker <em>and</em> a
 * stored impact preview before a version can go effective, and those are two distinct gates
 * that fail for different reasons. A single {@code approved} flag cannot express "signed off
 * but nobody has seen what it does to the book".
 *
 * <p>The states are ordered by progression and <b>nothing reads that order</b>. Transitions are
 * stated explicitly, in {@link #legalSuccessors()} below, rather than inferred from ordinals: a
 * version can be superseded from more than one live state, and an ordinal comparison — "the
 * target must come after the origin" — licences the single move the whole control exists to
 * refuse, {@code DRAFT} (0) straight to {@code EFFECTIVE} (3), while reading in a log as
 * progress.
 *
 * <p><b>Why the edges live here and not on the gate.</b> They were originally stated inside
 * {@code approval.MakerCheckerGate}, which is where the <em>decision</em> belongs — but the
 * consequence was that {@code PolicyVersion.withStatus} could move a version along any edge at
 * all, because the type holding the status had no idea which moves existed. A draft went to
 * {@code EFFECTIVE} in one call, past the gate that documents itself as the only legal way a
 * version changes status. Two places asserting one rule is the recurring defect in this
 * codebase; here it was worse, because only one of the two places had the rule. The edges are
 * now a property of the vocabulary, the gate reads them, and every mutator is bound by them.
 */
public enum PolicyVersionStatus {

    /** Being drafted. Carries no approval and may be edited freely. */
    DRAFT,

    /** Submitted by its maker, awaiting a different checker. */
    PENDING_APPROVAL,

    /**
     * Signed off by a checker, but not yet in force.
     *
     * <p>Distinct from {@link #EFFECTIVE} because approval and effect are separated by the
     * effective date: a version approved in March to take effect on 1 April is approved and
     * not yet operative, and a run dated in March must not pick it up.
     */
    APPROVED,

    /** In force. What the engine resolves against for dates inside its effective range. */
    EFFECTIVE,

    /**
     * Replaced by a later version.
     *
     * <p>Retained rather than deleted: a closed period must still resolve against the version
     * that was in force when it closed, or a replay cannot reproduce it (invariant DT-1).
     */
    SUPERSEDED;

    /** Whether the engine may resolve against this version at all. */
    public boolean isOperative() {
        return this == EFFECTIVE || this == SUPERSEDED;
    }

    /** Whether a checker has signed this version off. */
    public boolean isApproved() {
        return this == APPROVED || this == EFFECTIVE || this == SUPERSEDED;
    }

    /**
     * The life cycle, as data.
     *
     * <pre>
     *   DRAFT            &#8594; PENDING_APPROVAL
     *   PENDING_APPROVAL &#8594; APPROVED | DRAFT
     *   APPROVED         &#8594; EFFECTIVE | SUPERSEDED
     *   EFFECTIVE        &#8594; SUPERSEDED
     *   SUPERSEDED       &#8594; (terminal)
     * </pre>
     *
     * <p><b>Two absences that need explaining.</b>
     *
     * <p>{@code DRAFT → SUPERSEDED} and {@code PENDING_APPROVAL → SUPERSEDED} are not edges,
     * even though supersession is otherwise reachable from every live state. {@code SUPERSEDED}
     * counts as approved in {@link #isApproved()} — a closed period must still resolve against
     * the version that governed it — so {@code PolicyVersion} requires a checker and an approval
     * date on a superseded version, and a draft that never had either cannot be represented as
     * one. That is the right answer rather than a limitation: a version nobody approved was never
     * in force, so nothing replaced it. There is no {@code ABANDONED} status because an abandoned
     * draft needs none — it stays a draft, and no date resolves against it.
     *
     * <p>{@code PENDING_APPROVAL → DRAFT} <em>is</em> an edge, and it is the one addition to
     * FR-210's bare forward chain. Without it a checker who declines has nowhere to put the
     * version: supersession is unavailable to it by the paragraph above, and the only remaining
     * move would be the approval they just declined to give. A control whose refusal path
     * dead-ends is a control that gets worked around.
     */
    private static final Map<PolicyVersionStatus, Set<PolicyVersionStatus>> EDGES = edgeTable();

    private static Map<PolicyVersionStatus, Set<PolicyVersionStatus>> edgeTable() {
        EnumMap<PolicyVersionStatus, Set<PolicyVersionStatus>> table =
            new EnumMap<>(PolicyVersionStatus.class);
        table.put(DRAFT, EnumSet.of(PENDING_APPROVAL));
        table.put(PENDING_APPROVAL, EnumSet.of(APPROVED, DRAFT));
        table.put(APPROVED, EnumSet.of(EFFECTIVE, SUPERSEDED));
        table.put(EFFECTIVE, EnumSet.of(SUPERSEDED));
        table.put(SUPERSEDED, EnumSet.noneOf(PolicyVersionStatus.class));

        for (PolicyVersionStatus status : values()) {
            if (!table.containsKey(status)) {
                // Totality checked rather than trusted. A missing entry reads as "no legal moves"
                // and would strand a new status silently — surfacing months later as an
                // unexplained refusal instead of as a build failure on the day it was added.
                throw new IllegalStateException(
                    "the policy life cycle has no entry for " + status
                        + "; a new status needs its edges decided, not defaulted");
            }
            table.put(status, Collections.unmodifiableSet(table.get(status)));
        }
        return Collections.unmodifiableMap(table);
    }

    /**
     * The statuses this one may legally move to. Empty for {@link #SUPERSEDED}.
     *
     * <p>Published because an operator screen should offer exactly the moves that will be
     * accepted. A screen offering a move the gate refuses trains its users to expect refusals,
     * which is how a control stops being read.
     */
    public Set<PolicyVersionStatus> legalSuccessors() {
        return EDGES.get(this);
    }

    /**
     * Whether the life cycle has an edge from this status to {@code target}.
     *
     * <p><b>Necessary, not sufficient.</b> {@code APPROVED → EFFECTIVE} is an edge and is still
     * refused on a version carrying no approval evidence; {@code PENDING_APPROVAL → APPROVED} is
     * an edge and needs a signature this enum cannot see. Those are the maker–checker gate's
     * questions, and it asks them on top of this one. What an edge test answers on its own is the
     * narrower question that no caller should have to remember: whether the move exists at all.
     */
    public boolean canMoveTo(PolicyVersionStatus target) {
        Objects.requireNonNull(target, "target");
        return legalSuccessors().contains(target);
    }
}
