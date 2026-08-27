package com.crisil.eir.policy.close;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A correction to a closed period, recorded the only way FR-902 permits: as a new fact in system
 * time, in a later period, leaving the closed period's published figures alone.
 *
 * <p>FR-902: "Make closed periods immutable. A correction creates a restatement artefact; it never
 * mutates history." 04 § 2.13 says the same in the schema's words — "{@code CLOSED} is immutable: a
 * correction creates a restatement artefact referencing the original" — and 04 § 5 supplies the
 * mechanism, which is the only part of this that is not obvious:
 *
 * <ul>
 *   <li><b>Business time</b> ({@code valid_from} / {@code valid_to}) says when the fact was true in
 *       the world. A correction leaves it <em>alone</em>: the amendment really did apply from a date
 *       inside the closed period, and pretending otherwise would misstate the world.
 *   <li><b>System time</b> ({@code recorded_at} / {@code superseded_at}) says when the engine
 *       learned it. A correction records a new version here, after the close.
 *   <li><b>Accounting time</b> ({@code period_id}) says which period reports it. The correction is
 *       reported in a <em>later, open</em> period — {@link #recognisedInPeriodId()} — which is what
 *       keeps the closed period reporting what it published.
 * </ul>
 *
 * <p>The consequence is the one 04 § 5 spells out: "the already-closed periods keep their published
 * figures; the amendment produces a restatement artefact in the current period. History is never
 * rewritten." A replay of the closed period reads the version set as at that period's
 * {@code version_cutoff_at}, which is before this artefact's {@code recordedAt}, so the replay
 * reproduces the published figures and DT-1 holds — while the restatement is visible, dated, and
 * attributable.
 *
 * <p><b>What the guards below are and are not.</b> They enforce the shape of a legitimate
 * correction, and none of them is the condition an invariant asserts. CL-1 counts <em>mutated
 * figures in a closed period</em> ({@link ClosedPeriodImmutability}); it does not assert anything
 * about a restatement's fields, so refusing a malformed restatement here removes nothing from any
 * control's reach. The one relationship worth stating explicitly: a well-formed restatement does
 * <em>not</em> make a mutated figure acceptable to CL-1. Both existing at once is the worst case,
 * not the excused one — the movement has then been recognised twice, once in the closed period and
 * once in the open one.
 *
 * @param restatementId       identity of this artefact, for the reference 04 § 2.13 requires
 * @param correctedPeriodId   the closed period whose figure was wrong ({@code YYYYMM})
 * @param recognisedInPeriodId the open period that reports the correction; strictly later
 * @param figureKey           the figure being restated, in {@link PeriodStatement}'s key space
 * @param originalAmount      what the closed period published
 * @param correctedAmount     what it should have published
 * @param validFrom           business time the corrected fact was true from; inside the corrected
 *                            period, because that is where the fact belongs (04 § 5)
 * @param recordedAt          system time the engine learned it; after the close
 * @param raisedBy            who found the error
 * @param approvedBy          who signed the restatement off; never the same identity as
 *                            {@code raisedBy}
 * @param reason              why the original figure was wrong; never blank
 */
public record RestatementArtefact(
    String restatementId,
    int correctedPeriodId,
    int recognisedInPeriodId,
    String figureKey,
    Money originalAmount,
    Money correctedAmount,
    LocalDate validFrom,
    Instant recordedAt,
    String raisedBy,
    String approvedBy,
    String reason) {

    public RestatementArtefact {
        Objects.requireNonNull(originalAmount, "originalAmount");
        Objects.requireNonNull(correctedAmount, "correctedAmount");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(reason, "reason");
        restatementId = FourEyes.requireIdentity(restatementId, "restatementId",
            "04 § 2.13 requires the restatement to be referenceable from the original");
        figureKey = FourEyes.requireIdentity(figureKey, "figureKey",
            "a restatement of an unnamed figure cannot be tied to what it corrects");
        raisedBy = FourEyes.requireIdentity(raisedBy, "raisedBy",
            "a restatement of published accounts names who found the error");
        approvedBy = FourEyes.requireIdentity(approvedBy, "approvedBy",
            "a restatement of published accounts names who signed it off");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " gives no reason; the reason is what a reader of"
                    + " restated accounts is entitled to and the only thing distinguishing a"
                    + " correction from an adjustment nobody can explain");
        }
        // THE GUARD THAT IS THE WHOLE REQUIREMENT. A correction recognised in the period it
        // corrects is not a restatement — it is the in-place fix FR-902 forbids, with an artefact
        // filed alongside it. Refused rather than corrected, because the caller has decided
        // something this type cannot decide for them: which open period bears the movement.
        if (recognisedInPeriodId == correctedPeriodId) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " would recognise the correction in period "
                    + correctedPeriodId + ", the very period it corrects; that is a mutation of a"
                    + " closed period (FR-902), not a restatement of one");
        }
        if (recognisedInPeriodId < correctedPeriodId) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " would recognise a correction to period "
                    + correctedPeriodId + " in the earlier period " + recognisedInPeriodId
                    + "; a correction is learned after the fact and reported in an open period,"
                    + " never carried backwards into a period that is closed too");
        }
        // Business time belongs to the corrected period. 04 § 5: the backdated fact keeps its
        // valid_from; it is accounting time that moves. A valid_from outside the corrected period
        // means this artefact is correcting a different period from the one it names, and the
        // arithmetic is AccountingPeriod.periodIdOf so the two spellings cannot drift.
        if (AccountingPeriod.periodIdOf(validFrom) != correctedPeriodId) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " claims to correct period " + correctedPeriodId
                    + " but its business-time valid_from " + validFrom + " falls in period "
                    + AccountingPeriod.periodIdOf(validFrom));
        }
        if (!originalAmount.currency().equals(correctedAmount.currency())) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " restates " + originalAmount.currency()
                    + " as " + correctedAmount.currency() + "; a cross-currency delta restates"
                    + " nothing a reader can reconcile");
        }
        // A restatement of nothing. Refused because it is indistinguishable in a disclosure note
        // from a real one, and a register full of zero-delta artefacts is how a material
        // restatement gets lost in a list.
        if (correctedAmount.minus(originalAmount).atPresentationScale().isZero()) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " of figure " + figureKey + " has a zero delta at"
                    + " presentation scale (" + originalAmount.atPresentationScale() + " -> "
                    + correctedAmount.atPresentationScale() + "); it restates nothing");
        }
        // Four eyes, via the one comparison. Not re-implemented: FourEyes strips and case-folds at
        // Locale.ROOT, and the single place this rule was written a fourth time with a plain
        // equals was the place that let a maker approve their own artefact under a different
        // capitalisation. A throw is right here — unlike the exception acceptance, whose
        // self-approval is a gate refusal so that a close reports all forty at once, a restatement
        // is raised one at a time by a person who is present to be told.
        if (FourEyes.isSelfApproval(raisedBy, approvedBy)) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " is raised and approved by the same identity '"
                    + raisedBy + "'; a restatement of published accounts is not a one-person"
                    + " decision");
        }
    }

    /**
     * A restatement of a figure in {@code closedPeriod}, checked against the period's own record.
     *
     * <p>The production route, and the only one that can check the two things the canonical
     * constructor cannot see: that the period being corrected really is closed, and that the
     * correction was recorded <em>after</em> it was closed. The second is what keeps the closed
     * period's replay boundary meaningful — an artefact recorded before {@code version_cutoff_at}
     * would be inside the version set a replay reads, so the replay would reproduce the corrected
     * figure and the period would no longer replay to what it published.
     */
    public static RestatementArtefact correcting(
        AccountingPeriod closedPeriod, int recognisedInPeriodId, String restatementId,
        String figureKey, Money originalAmount, Money correctedAmount, LocalDate validFrom,
        Instant recordedAt, String raisedBy, String approvedBy, String reason) {
        Objects.requireNonNull(closedPeriod, "closedPeriod");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (!closedPeriod.isClosed()) {
            throw new IllegalArgumentException(
                "period " + closedPeriod.periodId() + " is " + closedPeriod.status()
                    + ", not CLOSED; a correction to an open period is an ordinary posting and"
                    + " needs no restatement artefact");
        }
        if (recordedAt.isBefore(closedPeriod.closedAt())) {
            throw new IllegalArgumentException(
                "restatement " + restatementId + " is recorded at " + recordedAt + ", before"
                    + " period " + closedPeriod.periodId() + " was closed at "
                    + closedPeriod.closedAt() + "; a version recorded before the close is inside"
                    + " the set a replay reads as at " + closedPeriod.versionCutoffAt()
                    + ", so the period would stop replaying to what it published (DT-1)");
        }
        return new RestatementArtefact(restatementId, closedPeriod.periodId(),
            recognisedInPeriodId, figureKey, originalAmount, correctedAmount, validFrom,
            recordedAt, raisedBy, approvedBy, reason);
    }

    /**
     * {@code corrected - original}: the movement the later period recognises.
     *
     * <p>Signed, because the direction is the disclosure. Note that an aggregate over several
     * restatements must not be built from a signed sum alone — see
     * {@link RestatementRegister#absoluteRestated}.
     */
    public Money delta() {
        return correctedAmount.minus(originalAmount).atPresentationScale();
    }

    /** Whether this artefact restates {@code figureKey} of period {@code periodId}. */
    public boolean restates(int periodId, String figureKey) {
        return correctedPeriodId == periodId && this.figureKey.equals(figureKey);
    }

    /** One audit sentence: what was restated, by how much, where it lands, and who signed. */
    public String describe() {
        return "restatement " + restatementId + " of " + figureKey + " in period "
            + correctedPeriodId + " (valid from " + validFrom + "): "
            + originalAmount.atPresentationScale() + " -> "
            + correctedAmount.atPresentationScale() + ", delta " + delta()
            + ", recognised in period " + recognisedInPeriodId + ", recorded " + recordedAt
            + ", raised by " + raisedBy + ", approved by " + approvedBy + ": " + reason;
    }

    @Override
    public String toString() {
        return describe();
    }
}
