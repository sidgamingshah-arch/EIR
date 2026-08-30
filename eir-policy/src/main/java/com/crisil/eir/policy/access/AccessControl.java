package com.crisil.eir.policy.access;

import com.crisil.eir.domain.FourEyes;
import java.util.Objects;
import java.util.Optional;

/**
 * The authorisation gate: one request in, one permitted-or-refused answer out.
 *
 * <p><b>What this closes.</b> {@code eir-api}'s README and its own close response said it plainly:
 * "No authentication or authorisation. FR-906's role model is unimplemented, so the maker-checker
 * identity on a close is whatever the caller typed." Every control in the close gate rested on
 * identities the caller asserted about themselves — the exception acceptance named its own maker and
 * its own checker, and nothing anywhere asked whether the caller was entitled to be either. This
 * class is the missing half: the acceptance artefact still carries both signatures and the close gate
 * still weighs them, but reaching the act at all now requires a grant, and holding the grant is not
 * enough if you are already on the other side of the control.
 *
 * <p><b>The four checks, in this order, and the order is part of the control.</b> Each refusal is a
 * different instruction to whoever reads it, and the specific readings come before the general ones —
 * the same discipline {@code MakerCheckerGate.transition} follows:
 *
 * <ol>
 *   <li><b>Was an identity presented at all?</b> {@link AccessRefusal#NO_IDENTITY_ASSERTED}. First,
 *       because it is the only refusal that says nothing about the caller's entitlements — there is
 *       no caller yet.</li>
 *   <li><b>Does the act exist?</b> {@link AccessRefusal#NO_SUCH_CAPABILITY}, and this comes before
 *       resolving the identity on purpose. 07 § 5 forbids a manual rate override permanently, so
 *       {@code action=OVERRIDE_RATE} must be answered "no such act exists" for <em>every</em>
 *       caller, including one the register does not hold. Answered after identity resolution, an
 *       unknown caller would be told their identity was the problem, which invites them to fix the
 *       identity and try again for an act that will never exist.</li>
 *   <li><b>Do we know the identity?</b> {@link AccessRefusal#UNKNOWN_IDENTITY}. No default role.</li>
 *   <li><b>Does the grant cover the act, and is this identity already on the other side?</b>
 *       {@link AccessRefusal#ROLE_LACKS_CAPABILITY}, then
 *       {@link AccessRefusal#SEGREGATION_OF_DUTIES}. In that order, because a caller who never had
 *       the capability should be told that and not handed the more specific fact that they also
 *       started the run.</li>
 * </ol>
 *
 * <p><b>Segregation of duties, and the one comparison.</b> The rule is 07 § 7's: "A maker cannot be
 * the checker on the same version — enforced, not advisory." The limb this class adds is the one
 * nothing in the engine held before — the identity that <em>started a run</em> must not be the
 * identity that approves closing over its exceptions — and the comparison is
 * {@link FourEyes#sameIdentity}. Not re-implemented. That rule was stated in four places in this
 * codebase and answered two different ways, and the fourth spelling, a plain {@code equals}, was the
 * one guarding the routing table that decides how every event is treated. This is the fifth site and
 * it borrows rather than restates.
 *
 * <p><b>What this class deliberately does not decide.</b> Whether a particular
 * {@code ExceptionAcceptance} is self-approved. That is
 * {@code ExceptionAcceptance.isSelfApproved()}, refused by the close gate as
 * {@code SELF_APPROVED_ACCEPTANCE}, and it is a fact about the artefact's two named signatories
 * rather than about the caller's grant. Deciding it here would be the sixth spelling of the same
 * rule, and the two answers could then disagree — which is exactly the failure mode this repository
 * already found once. What is reported here instead is the standing grant:
 * {@link Principal#toxicCombinations()} names an identity that holds both halves, which is a finding
 * about the role table rather than a verdict on any one acceptance.
 *
 * <p>Refusals are returned, never thrown. Throwing is reserved for a defect in the gate itself — a
 * {@code null} register or request.
 */
public final class AccessControl {

    private AccessControl() {
    }

    /**
     * Decides one request against one register.
     *
     * @param register the grant table; never {@code null}, because a gate with no table would
     *                 permit or refuse everything and both are wrong
     * @param request  the act attempted, with the identity as asserted
     */
    public static AccessDecision decide(RoleRegister register, AuthorisationRequest request) {
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(request, "request");

        // (1) Nothing presented. There is no anonymous role in this model: 07 § 7 requires every
        // mutating call to be logged with its principal, and a principal-less log entry records a
        // call with no caller.
        if (!request.assertsAnIdentity()) {
            return AccessDecision.refuse(request, null, null,
                AccessRefusal.NO_IDENTITY_ASSERTED,
                "attempted '" + request.action() + "' with no identity on the request");
        }

        // (2) THE AUTOMATION MANDATE, AT THE EDGE. Before we look up who is asking, because the
        // answer to "may I override this rate" is the same for everybody and it is not "you lack a
        // capability" — that would say somebody could hold it. 07 § 5: no endpoint permits
        // overriding a computed rate or balance; corrections change inputs or policy, then
        // recompute. A single overridden rate makes the whole book's replayability unprovable.
        Optional<Capability> resolved = Capability.named(request.action());
        if (resolved.isEmpty()) {
            return AccessDecision.refuse(request, null, null,
                AccessRefusal.NO_SUCH_CAPABILITY,
                "'" + request.action() + "' is not an act this engine has; no role grants it and no"
                    + " grant would create it");
        }
        Capability capability = resolved.get();

        // (3) An identity nobody granted anything to. Refused, not defaulted to read-only.
        Optional<Principal> found = register.resolve(request.assertedIdentity());
        if (found.isEmpty()) {
            return AccessDecision.refuse(request, null, capability,
                AccessRefusal.UNKNOWN_IDENTITY,
                "'" + request.assertedIdentity() + "' attempting " + capability.name()
                    + "; the register holds " + register.size() + " identities and none is this one");
        }
        Principal principal = found.get();

        // (4a) The ordinary RBAC refusal, and the one a read-only identity meets. Before the
        // segregation limb, so that a caller who never had the capability is told that rather than
        // the more specific fact that they also started the run.
        if (!principal.may(capability)) {
            return AccessDecision.refuse(request, principal, capability,
                AccessRefusal.ROLE_LACKS_CAPABILITY,
                principal.describe() + " may " + principal.capabilities() + ", which does not"
                    + " include " + capability.name());
        }

        // (4b) SEGREGATION OF DUTIES. The identity holds the capability; the question is whether
        // exercising it here would put one person on both sides of the control. The only limb with
        // an input at this layer is the run: an acceptance approval over exceptions the same
        // identity's own run produced.
        //
        // Note what makes this reachable, because a control that cannot fire is worse than an
        // absent one. Role's table gives no single role both START_RUN and
        // APPROVE_EXCEPTION_ACCEPTANCE, so read as a property of the table this could never fire.
        // A Principal holds a SET of roles, and BATCH_OPERATOR + APPROVER on one identity holds
        // both — the grant a deployment drifts into the first time somebody needs cover over a
        // quarter-end. That composed grant is the failing input, and it is constructed in
        // AccessControlTest.
        boolean segregationEvaluated =
            capability.checks().contains(Capability.START_RUN) && request.namesRunMaker();
        if (segregationEvaluated
            && FourEyes.sameIdentity(principal.identity(), request.runStartedBy())) {
            return AccessDecision.refuse(request, principal, capability,
                AccessRefusal.SEGREGATION_OF_DUTIES,
                "'" + principal.identity() + "' started the run whose exceptions this "
                    + capability.name() + " would close over (run started by '"
                    + request.runStartedBy() + "'); the exceptions and the signature for closing"
                    + " over them cannot be one person's (07 § 7)");
        }

        return AccessDecision.permit(request, principal, capability, segregationEvaluated,
            principal.describe() + " may " + capability.name() + " — " + capability.description()
                + segregationNote(capability, request, segregationEvaluated));
    }

    /**
     * What the permit says about the segregation limb: cleared, not applicable, or <em>not
     * evaluated</em>.
     *
     * <p>The third case is the one worth spelling out on the response. A permit for
     * {@code APPROVE_EXCEPTION_ACCEPTANCE} that reached this point without a run maker to compare
     * against has not passed the segregation check, it has skipped it, and the two are
     * indistinguishable from a bare "permitted". That is the failure mode of the commit named "An
     * invariant nobody evaluated reads exactly like one that passed".
     */
    private static String segregationNote(
        Capability capability, AuthorisationRequest request, boolean evaluated) {
        if (evaluated) {
            return "; segregation cleared against run maker '" + request.runStartedBy() + "'";
        }
        if (segregationApplies(capability)) {
            return "; SEGREGATION NOT EVALUATED — this act is subject to the"
                + " segregation-of-duties rule and the request named no run maker to compare"
                + " against";
        }
        return "";
    }

    /**
     * Whether {@code capability} is subject to the run-level segregation limb at all.
     *
     * <p>Published so that a caller building a request can tell whether it is obliged to supply the
     * run's maker. An act that is subject to the rule and reaches a permit without the input has
     * not passed the check — it has skipped it — and both the detail sentence and
     * {@link AccessDecision#segregationEvaluated()} say so rather than letting a skipped check read
     * as a cleared one.
     */
    public static boolean segregationApplies(Capability capability) {
        return capability != null && capability.checks().contains(Capability.START_RUN);
    }
}
