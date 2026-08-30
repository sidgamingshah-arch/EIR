package com.crisil.eir.policy.access;

import java.util.Objects;

/**
 * One authorisation question, exactly as it arrives from the edge — nothing resolved, nothing
 * validated.
 *
 * <p><b>Why the fields are raw strings and not a {@link Principal} and a {@link Capability}.</b>
 * Because resolving them <em>is</em> part of the decision, and every refusal that lives in the
 * resolution — no identity presented, an identity nobody granted anything to, an act that does not
 * exist — is a refusal a caller has to be told about with its own reason. A request type that could
 * only be built from an already-resolved principal would push those three cases out to the HTTP
 * layer, where each handler would answer them slightly differently. That is how a filter ends up
 * guarding some routes and silently missing others, which is worse than no filter at all.
 *
 * @param assertedIdentity what the caller says it is — {@code null} or blank when nothing was
 *                         presented. <b>Asserted, never authenticated.</b> This engine has no
 *                         identity provider: at the HTTP edge the value comes off a request header,
 *                         and a production deployment terminates TLS and authenticates upstream
 *                         (07 § 7: OAuth2 client credentials, TLS 1.3) before this string is
 *                         trusted for anything.
 * @param action           the act being attempted, as named by the caller; resolved by
 *                         {@link Capability#named}, and unresolvable on purpose for an act this
 *                         engine does not have — see {@link AccessRefusal#NO_SUCH_CAPABILITY}
 * @param runStartedBy     the identity that started the run this act concerns, or {@code null}
 *                         where the act concerns no run. The segregation-of-duties input: an
 *                         acceptance approval is refused when it names the same person who produced
 *                         the exceptions. In a production deployment this is read off the run
 *                         record and never off the request — see {@link #runStartedBy()}.
 */
public record AuthorisationRequest(String assertedIdentity, String action, String runStartedBy) {

    public AuthorisationRequest {
        Objects.requireNonNull(action, "action");
    }

    /** An act that concerns no run — a read, a draft, a policy approval. */
    public static AuthorisationRequest of(String assertedIdentity, String action) {
        return new AuthorisationRequest(assertedIdentity, action, null);
    }

    /**
     * An act over the exceptions of a run somebody started.
     *
     * <p>The named factory exists so that a call site which <em>should</em> supply the run's maker
     * and does not is visibly using the other one. An optional field silently left null is how a
     * segregation check quietly stops firing; a second factory makes the omission a choice somebody
     * wrote down.
     */
    public static AuthorisationRequest overRunStartedBy(
        String assertedIdentity, String action, String runStartedBy) {
        return new AuthorisationRequest(assertedIdentity, action, runStartedBy);
    }

    /** Whether an identity was presented at all. */
    public boolean assertsAnIdentity() {
        return assertedIdentity != null && !assertedIdentity.isBlank();
    }

    /**
     * Whether this request carries the run's maker, so the segregation limb has an input.
     *
     * <p>Read by {@link AccessControl}, which reports {@code segregationEvaluated: false} when it is
     * absent rather than reporting a clean pass. An invariant nobody evaluated reads exactly like
     * one that passed — this repository has a commit by that name — and the same is true of a
     * segregation check with no second identity to compare against.
     */
    public boolean namesRunMaker() {
        return runStartedBy != null && !runStartedBy.isBlank();
    }
}
