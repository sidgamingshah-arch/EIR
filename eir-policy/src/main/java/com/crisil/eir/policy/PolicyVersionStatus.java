package com.crisil.eir.policy;

/**
 * Where a policy version sits in its approval life.
 *
 * <p>Five states rather than a boolean, because FR-210 requires maker–checker <em>and</em> a
 * stored impact preview before a version can go effective, and those are two distinct gates
 * that fail for different reasons. A single {@code approved} flag cannot express "signed off
 * but nobody has seen what it does to the book".
 *
 * <p>The states are ordered by progression and nothing reads that order — transitions are
 * stated explicitly by the maker–checker gate rather than inferred from ordinals, because a
 * version can be superseded from any live state and an ordinal comparison would licence a
 * jump from {@code DRAFT} straight to {@code EFFECTIVE}.
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
}
