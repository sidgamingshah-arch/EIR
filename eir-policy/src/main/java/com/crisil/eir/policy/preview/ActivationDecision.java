package com.crisil.eir.policy.preview;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import java.util.Objects;
import java.util.Optional;

/**
 * The impact-preview gate's answer: may this policy version become {@code EFFECTIVE}?
 *
 * <p><b>A value, never a throw.</b> Same posture as {@link com.crisil.eir.domain.InvariantResult}
 * and for the same reason: an unpreviewed version is a data condition, and a run reviewing a
 * batch of pending versions must be able to collect every refusal and report them together
 * rather than stopping at the first. An exception here would also make the gate awkward to
 * compose — the maker–checker transition needs to combine this answer with its own, and
 * combining values is arithmetic while combining thrown exceptions is control flow.
 *
 * <p>Composition is deliberately one-directional. This package does not know what a status
 * transition is; the approval side calls {@link ActivationGate} and refuses its transition when
 * {@link #permitted()} is false, quoting {@link #detail()}. That keeps the preview gate testable
 * on its own and means neither side has to change when the other does.
 *
 * <p>A permitted decision still carries a {@link #detail()} — the audit sentence naming the
 * preview that permitted it. A gate that says only "yes" leaves nothing in the record to show
 * <em>which</em> preview was relied on, and that is the field an auditor asks for first.
 *
 * @param permitted        whether the move to {@code EFFECTIVE} may proceed
 * @param refusal          why not, or null when permitted
 * @param policyVersionId  the version this decision is about
 * @param detail           human-readable statement of what was checked and found
 */
public record ActivationDecision(
    boolean permitted,
    ActivationRefusalReason refusal,
    String policyVersionId,
    String detail) {

    public ActivationDecision {
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(detail, "detail");
        // The two halves must agree. A "permitted" decision carrying a refusal reason, or a
        // refusal carrying none, is the shape a caller would read whichever field it happened to
        // trust — and a caller that trusts the reason would treat a permit as a block, or worse,
        // a caller that trusts the boolean would let a named refusal through.
        if (permitted && refusal != null) {
            throw new IllegalArgumentException(
                "a permitted activation cannot also carry the refusal " + refusal);
        }
        if (!permitted && refusal == null) {
            throw new IllegalArgumentException(
                "a refused activation must name its reason; an unexplained refusal cannot be"
                    + " actioned or appealed");
        }
    }

    /** The version may go effective, on the evidence of the preview named in {@code detail}. */
    public static ActivationDecision permit(String policyVersionId, String detail) {
        return new ActivationDecision(true, null, policyVersionId, detail);
    }

    /** The version may not go effective. */
    public static ActivationDecision refuse(
        String policyVersionId, ActivationRefusalReason reason, String detail) {
        Objects.requireNonNull(reason, "reason");
        return new ActivationDecision(false, reason, policyVersionId, detail);
    }

    /** Whether this is a refusal. */
    public boolean isRefused() {
        return !permitted;
    }

    /** The refusal reason, empty when permitted. */
    public Optional<ActivationRefusalReason> refusalReason() {
        return Optional.ofNullable(refusal);
    }

    /**
     * Whether this refusal describes a stored preview that would satisfy a control asking only
     * whether a preview exists. False for a permit.
     *
     * <p>Lifted onto the decision because that is where a control report reads it — the
     * escalation is different for "nobody has run this" and "somebody ran it and then changed
     * the draft". See {@link ActivationRefusalReason#looksLikeDiligence()}.
     */
    public boolean refusalLooksLikeDiligence() {
        return refusal != null && refusal.looksLikeDiligence();
    }

    /** One audit sentence. */
    /**
     * This decision as the assertion it supports — invariant {@link com.crisil.eir.domain.InvariantId#PG_1}.
     *
     * <p>FR-210 calls the impact preview mandatory, and until this existed the gate returned a
     * value nobody asserted. A mandatory artefact whose presence no control states is one
     * somebody eventually skips: the roadmap's register puts 3.73x leverage on year-one fee
     * recognition behind a behavioural-curve revision, so a version going effective unpreviewed
     * is how that lands with nobody having seen the number.
     *
     * <p>Deviation is {@link java.math.BigDecimal#ONE} — a refused activation is a fact, not a
     * magnitude, so a caller summing deviations gets a count of versions blocked from going
     * effective. The reason lives in the detail, where it can name which of the seven refusals
     * fired; a numeric encoding of the reason would invite arithmetic on an enum.
     */
    public InvariantResult asInvariantResult() {
        return permitted
            ? InvariantResult.pass(InvariantId.PG_1, describe())
            : InvariantResult.fail(InvariantId.PG_1, describe(), java.math.BigDecimal.ONE);
    }

    public String describe() {
        return (permitted ? "ACTIVATION PERMITTED" : "ACTIVATION REFUSED (" + refusal + ")")
            + " for policy version " + policyVersionId + ": " + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
