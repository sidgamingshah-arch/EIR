package com.crisil.eir.policy.access;

/**
 * Why an authorisation was refused. One reason per genuinely different instruction to its reader.
 *
 * <p><b>These are not engine refusals, and the difference decides a status code.</b> Everywhere else
 * in this engine a refusal is a value that comes back on a 200, because "this period may not close,
 * here are the five reasons" is an <em>answer</em> and the whole list has to arrive. An
 * authorisation refusal is the opposite: the engine has not been asked to compute anything, the
 * caller is not entitled to ask, and nothing about the portfolio was examined. That is one of the
 * few genuine client errors this API has, and it is a 403 — which is exactly why engine refusals are
 * 200s. Collapse the two and an operator can no longer tell "the book is not ready" from "you are
 * not allowed to look".
 *
 * <p>Each constant's {@link #explanation()} is the rule; the call site supplies what was actually
 * attempted. Both halves always, for the reason {@code MakerCheckerGate} gives: a refusal that says
 * only the rule sends its reader to the source to work out which attempt tripped it, and one that
 * says only the attempt leaves them guessing at the rule.
 */
public enum AccessRefusal {

    /**
     * No identity was presented at all.
     *
     * <p>Separate from {@link #UNKNOWN_IDENTITY} because the remedies differ: an absent header is a
     * caller that has not been wired up, an unrecognised one is a caller whose grant is missing. A
     * single "unauthorised" would send both to the wrong team.
     *
     * <p>There is no anonymous role in this model, deliberately. 07 § 7 logs "every mutating call
     * with principal, payload hash and timestamp"; an anonymous principal makes that log a record
     * of calls with no callers.
     */
    NO_IDENTITY_ASSERTED("no identity was presented, and this engine has no anonymous role"),

    /**
     * An identity was presented that the role register does not hold.
     *
     * <p>Refused rather than granted a default. A default role is how an unmapped caller acquires
     * capabilities nobody granted it — the same failure the fee rule set refuses when it quarantines
     * an unmapped fee code instead of defaulting it (FR-201).
     */
    UNKNOWN_IDENTITY("the identity is not in the role register, and there is no default role"),

    /**
     * The identity is known and holds none of the roles that grant the capability asked for.
     *
     * <p>The ordinary RBAC refusal, and the one the read-only role meets.
     */
    ROLE_LACKS_CAPABILITY("none of the identity's roles grants the capability required"),

    /**
     * The identity holds the capability but may not exercise it here, because doing so would put
     * one person on both sides of a control — 07 § 7's "a maker cannot be the checker on the same
     * version — enforced, not advisory".
     *
     * <p>Named separately from {@link #ROLE_LACKS_CAPABILITY}, and this is the distinction that
     * matters most in the whole enum. The two refusals mean opposite things to whoever reads the
     * log: one says the grant is wrong and should be widened or the request routed elsewhere; the
     * other says the grant is right and the <em>request</em> is wrong — a second person must do
     * this. Reported as the same reason, a segregation breach would be filed as a permissions
     * ticket and closed by widening the grant, which is the failure the control exists to prevent.
     */
    SEGREGATION_OF_DUTIES(
        "the identity is on the other side of this control already, so a second person is required"),

    /**
     * The action named does not correspond to any capability this engine has.
     *
     * <p><b>This is the automation mandate at the edge.</b> 07 § 5: "There is deliberately no manual
     * rate override anywhere in the system... a single overridden rate makes the whole book's
     * replayability unprovable, because nothing distinguishes a computed figure from an adjusted
     * one." A caller asking to be authorised for {@code OVERRIDE_RATE} must not be told they lack
     * the capability, because that sentence says somebody could hold it and this deployment simply
     * has not granted it. The honest answer is that the act does not exist and no grant would create
     * it. Refused, not ignored, and refused with the citation.
     */
    NO_SUCH_CAPABILITY(
        "no such act exists in this engine; corrections change inputs or policy, then recompute"
            + " (07 § 5)");

    private final String explanation;

    AccessRefusal(String explanation) {
        this.explanation = explanation;
    }

    /** The rule, in one sentence. The attempt is supplied by the call site. */
    public String explanation() {
        return explanation;
    }

    /**
     * Whether this refusal is about the identity rather than about what it may do.
     *
     * <p>Published because it is the one operational split that matters when reading an access log
     * at volume: a rise in {@link #NO_IDENTITY_ASSERTED} or {@link #UNKNOWN_IDENTITY} is a
     * deployment or directory problem, and a rise in the other three is either an attack or a
     * process that has been asked to do something it was never meant to do.
     */
    public boolean isAboutTheIdentity() {
        return this == NO_IDENTITY_ASSERTED || this == UNKNOWN_IDENTITY;
    }
}
