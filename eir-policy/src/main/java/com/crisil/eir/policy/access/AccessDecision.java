package com.crisil.eir.policy.access;

import java.util.Objects;
import java.util.Optional;

/**
 * The answer to one authorisation question: permitted, or refused with a named reason.
 *
 * <p><b>A decision, unlike an engine refusal, is singular.</b> Everywhere else in this engine a
 * refusal is one of a list and the whole list comes back — a close reports all five failing gates,
 * because an operator wants every problem once rather than the first problem repeatedly. An
 * authorisation decision is not like that: there is one act, and the first reason it is not
 * permitted is the reason. Enumerating "you also lack two other capabilities you did not ask for"
 * would be noise, and enumerating every rule a caller tripped is how an access log becomes a
 * reconnaissance tool.
 *
 * <p><b>{@link #runMakerSegregationEvaluated()} is on the record on purpose.</b> A permit that says
 * only "permitted" cannot be distinguished from a permit where the segregation limb had no second
 * identity to compare against and therefore never ran. This repository has a commit named "An
 * invariant nobody evaluated reads exactly like one that passed"; the same trap is here, and the
 * flag is how the caller — and the operator reading the response — can tell a cleared check from a
 * skipped one. It is {@code true} on a refusal <em>caused</em> by the rule as well as on a permit
 * that cleared it: a breach is the limb running and failing, and a log query filtering
 * {@code false} to find un-evaluated checks must not sweep up every real breach.
 *
 * <p><b>The name is long because the short one would overstate.</b>
 * {@code APPROVE_EXCEPTION_ACCEPTANCE} sits on a maker–checker pair with two maker halves — the
 * proposer of the acceptance, and the starter of the run whose exceptions it covers. Only the second
 * is comparable here; the first is a fact about the acceptance artefact's own two signatories, held
 * by {@code ExceptionAcceptance.isSelfApproved()} and refused by the close gate as
 * {@code SELF_APPROVED_ACCEPTANCE}. A field called {@code segregationEvaluated} would claim both.
 *
 * @param permitted            whether the act may proceed
 * @param assertedIdentity     the identity as the caller presented it, echoed verbatim so an
 *                             operator can see the string that was actually sent; {@code null} when
 *                             nothing was presented
 * @param principal            the resolved principal, or {@code null} when resolution itself failed
 * @param capability           the resolved capability, or {@code null} when the action named no
 *                             capability this engine has
 * @param refusal              the reason, or {@code null} on a permit
 * @param detail              what was attempted and why the answer is what it is; never blank —
 *                             both halves, always, per {@link AccessRefusal}
 * @param runMakerSegregationEvaluated whether the run-maker limb of the segregation rule had the
 *                             inputs to run — the caller's identity and the run's. Says nothing
 *                             about the proposer-versus-approver limb, which is the acceptance
 *                             artefact's and is decided by the close gate.
 */
public record AccessDecision(
    boolean permitted,
    String assertedIdentity,
    Principal principal,
    Capability capability,
    AccessRefusal refusal,
    String detail,
    boolean runMakerSegregationEvaluated) {

    public AccessDecision {
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "an access decision with no detail cannot be acted on or audited");
        }
        if (permitted && refusal != null) {
            throw new IllegalArgumentException(
                "a permitted decision naming refusal " + refusal + " is a contradiction");
        }
        if (!permitted && refusal == null) {
            throw new IllegalArgumentException(
                "a refused decision must name its reason; an unexplained 403 is untriageable");
        }
    }

    /** A permit. */
    static AccessDecision permit(
        AuthorisationRequest request, Principal principal, Capability capability,
        boolean runMakerSegregationEvaluated, String detail) {
        return new AccessDecision(true, request.assertedIdentity(),
            Objects.requireNonNull(principal, "principal"),
            Objects.requireNonNull(capability, "capability"),
            null, detail, runMakerSegregationEvaluated);
    }

    /**
     * A refusal. {@code principal} and {@code capability} are whatever resolved before the stop.
     *
     * <p>The evaluated flag is <em>derived</em> from the reason rather than passed in, and that is
     * exact rather than convenient: {@link AccessControl} checks the run-maker limb last, so the
     * only refusal it can produce after running that limb is
     * {@link AccessRefusal#SEGREGATION_OF_DUTIES}, and every earlier stop — no identity, no such
     * act, unknown identity, missing grant — returned before the limb had anything to compare.
     * Passing the flag would let a call site set it wrongly; deriving it cannot drift. It was
     * hard-coded {@code false} here at first, which reported every genuine breach as a check that
     * never ran.
     */
    static AccessDecision refuse(
        AuthorisationRequest request, Principal principal, Capability capability,
        AccessRefusal refusal, String because) {
        Objects.requireNonNull(refusal, "refusal");
        return new AccessDecision(false, request.assertedIdentity(), principal, capability,
            refusal, refusal.explanation() + " — " + because,
            refusal == AccessRefusal.SEGREGATION_OF_DUTIES);
    }

    /** The resolved principal, absent where resolution failed. */
    public Optional<Principal> resolvedPrincipal() {
        return Optional.ofNullable(principal);
    }

    /** The resolved capability, absent where the action named no capability this engine has. */
    public Optional<Capability> resolvedCapability() {
        return Optional.ofNullable(capability);
    }

    /** The reason, absent on a permit. */
    public Optional<AccessRefusal> reason() {
        return Optional.ofNullable(refusal);
    }

    /**
     * Whether this refusal is a segregation-of-duties breach rather than a missing grant.
     *
     * <p>Published because the two must not be filed the same way. A missing grant is closed by
     * widening the grant; a segregation breach closed by widening the grant is the control being
     * removed by the ticket that reported it.
     */
    public boolean isSegregationBreach() {
        return refusal == AccessRefusal.SEGREGATION_OF_DUTIES;
    }

    /** One audit sentence, suitable for 07 § 7's append-only access log. */
    public String describe() {
        String who = assertedIdentity == null || assertedIdentity.isBlank()
            ? "[no identity asserted]" : assertedIdentity;
        String what = capability == null ? "[unrecognised act]" : capability.name();
        return (permitted ? "PERMITTED " : "REFUSED ") + who + " -> " + what + ": " + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
