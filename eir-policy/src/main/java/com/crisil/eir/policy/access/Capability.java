package com.crisil.eir.policy.access;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The acts this engine distinguishes for authorisation purposes — 07 § 7's "scopes per resource
 * group; RBAC on approval actions".
 *
 * <p><b>Why these seven and not a generic permission string.</b> A generic {@code resource:verb}
 * grammar would let a deployment invent {@code rate:write}, and 07 § 5 is explicit that no such act
 * exists: "There is deliberately <b>no manual rate override anywhere in the system</b>." A closed
 * enum is the only form in which that sentence is enforced rather than asserted — a capability that
 * cannot be named cannot be granted, and {@link #named(String)} refuses the name rather than
 * inventing a scope for it. See {@link AccessRefusal#NO_SUCH_CAPABILITY}.
 *
 * <p><b>What determined the cut.</b> Four distinctions actually matter in this domain, and they are
 * the four an auditor asks about:
 *
 * <ol>
 *   <li>who may <em>start a run</em> — {@link #START_RUN};</li>
 *   <li>who may <em>close a period</em> — {@link #CLOSE_PERIOD};</li>
 *   <li>who may <em>approve</em> a policy version or an exception acceptance —
 *       {@link #APPROVE_POLICY_VERSION}, {@link #APPROVE_EXCEPTION_ACCEPTANCE};</li>
 *   <li>who may only <em>read</em> — {@link #READ_FIGURES}.</li>
 * </ol>
 *
 * <p>The two maker-side capabilities ({@link #DRAFT_POLICY_VERSION},
 * {@link #PROPOSE_EXCEPTION_ACCEPTANCE}) exist because an approval capability with no corresponding
 * maker capability is not a maker–checker control, it is a single signature with a longer name.
 * 07 § 4.2 requires "a maker, a checker, an effective date and a stored impact preview"; this enum
 * carries the first two, {@code PolicyVersion} carries the third and the preview gate the fourth.
 *
 * <p><b>What is deliberately absent.</b> No capability to override a computed rate, a computed
 * balance, or an invariant result; no capability to reopen a closed period; no capability to delete
 * an {@code EIR_COMPUTATION} row. Each of those is refused by design and permanently — 07 § 5,
 * 07 § 4.4, 07 § 6.2 — so a role model that could express one would be describing an engine this
 * is not. {@code CapabilityTest} asserts the absence, so that adding one is a test failure and not
 * a quiet widening.
 */
public enum Capability {

    /**
     * Read a published figure, its trace, and the reports over it — 06 § 2, § 7.
     *
     * <p>Every role holds this, including {@link Role#AUDITOR}. A shadow replay (06 § 4's
     * {@code POST /runs/{id}/replay}) is covered here rather than by a capability of its own,
     * because a replay writes to a shadow table and changes no published figure: it is a read of
     * the past, and 02 § 3.3 has a statutory auditor performing one as part of an audit sample.
     */
    READ_FIGURES("read a published figure, its computation trace and the reports over it"),

    /**
     * Start an amortisation run — 06 § 4's {@code POST /runs}.
     *
     * <p>Separated from {@link #CLOSE_PERIOD} even though 02 § 3.1 has one financial controller
     * doing both, because a run is also started by the batch scheduler, and a scheduler identity
     * that could also close a period would be a close nobody signed.
     */
    START_RUN("start an amortisation run over a period"),

    /**
     * Put forward an acceptance that would let a period close over an exception that stands —
     * the maker side of 07 § 4.2's last bullet.
     */
    PROPOSE_EXCEPTION_ACCEPTANCE(
        "put forward an acceptance that would let a close proceed over a standing exception"),

    /**
     * Sign for such an acceptance — the checker side, and the capability the whole close gate
     * rests on.
     *
     * <p>{@code ExceptionCategory.blocksClose()} is true for all ten categories, so acceptance is
     * the <em>only</em> route past a queued exception. That makes this the most attacked capability
     * in the model, and the one the segregation-of-duties rule in {@link AccessControl} is written
     * for.
     */
    APPROVE_EXCEPTION_ACCEPTANCE(
        "approve an exception acceptance, permitting a close over a standing exception"),

    /**
     * Draft a policy version, fee rule set, routing table, pool definition, behavioural assumption
     * or materiality threshold — the maker side of 07 § 4.2, and 02 § 3.2's product-control
     * journey.
     */
    DRAFT_POLICY_VERSION(
        "draft a policy version, rule set, routing table, pool definition or assumption"),

    /**
     * Approve such a version — the checker side, and 07 § 7's "RBAC on approval actions".
     *
     * <p>Holding this capability is necessary and not sufficient: {@code MakerCheckerGate} still
     * refuses a self-approval and still refuses an activation with no stored impact preview. This
     * capability answers "may this identity approve anything at all"; the gate answers "may this
     * identity approve <em>this</em>".
     */
    APPROVE_POLICY_VERSION("approve a policy version, rule set, routing table or assumption"),

    /**
     * Close a period — 06 § 4's {@code POST /periods/{id}/close}, and the act that makes the
     * period's partitions read-only (07 § 4.3).
     */
    CLOSE_PERIOD("close an accounting period, making its figures immutable");

    private final String description;

    Capability(String description) {
        this.description = description;
    }

    /** What holding this capability lets an identity do, in one sentence, for the role listing. */
    public String description() {
        return description;
    }

    /**
     * Whether this capability is an approval — the set 07 § 7 singles out for RBAC.
     *
     * <p>Published because it is what makes a "toxic combination" reportable: an identity holding
     * an approval capability alongside the maker capability it checks is a segregation-of-duties
     * finding whether or not it ever exercises both. See {@link Principal#toxicCombinations()}.
     */
    public boolean isApproval() {
        return this == APPROVE_EXCEPTION_ACCEPTANCE || this == APPROVE_POLICY_VERSION;
    }

    /**
     * The maker-side capabilities this approval capability checks — the pairs that must not land on
     * one identity.
     *
     * <p><b>Why the pairing lives here and not on the gate.</b> {@code MakerCheckerGate}'s comment
     * records what happened when the transition <em>table</em> lived in the class holding the
     * decision: the class holding the vocabulary had no idea which moves existed, so a second
     * method moved a version along any edge at all and the gate's claim to be the only legal route
     * was false. The same argument applies to this table. The pairs belong to the vocabulary; the
     * refusals, which need two identities and a run record the enum cannot see, belong to
     * {@link AccessControl}.
     *
     * <p>The three pairs, and why each is a pair:
     *
     * <ul>
     *   <li>{@link #APPROVE_POLICY_VERSION} checks {@link #DRAFT_POLICY_VERSION} — 07 § 4.2, and
     *       the rule {@code FourEyes} was extracted for.</li>
     *   <li>{@link #APPROVE_EXCEPTION_ACCEPTANCE} checks
     *       {@link #PROPOSE_EXCEPTION_ACCEPTANCE} — the same rule at the acceptance, held as a
     *       value by {@code ExceptionAcceptance.isSelfApproved} and refused by the close gate as
     *       {@code SELF_APPROVED_ACCEPTANCE}. Reported here as a standing grant, <em>not</em>
     *       re-decided: see {@link AccessControl}.</li>
     *   <li>{@link #APPROVE_EXCEPTION_ACCEPTANCE} also checks {@link #START_RUN} — the limb nothing
     *       in this engine held before: the identity that produced the exceptions must not be the
     *       identity that signs for closing over them.</li>
     * </ul>
     *
     * <p>Empty for every non-approval capability. {@link #CLOSE_PERIOD} deliberately checks
     * nothing: 02 § 3.1 has one financial controller trigger the run at step 2 and lock the period
     * at step 7, and that is the designed journey. What the close must not do is rest on an
     * acceptance the closer signed, and that is a fact about the acceptance rather than about the
     * close — the close gate reads it off the artefact.
     */
    public Set<Capability> checks() {
        return switch (this) {
            case APPROVE_POLICY_VERSION -> Set.of(DRAFT_POLICY_VERSION);
            case APPROVE_EXCEPTION_ACCEPTANCE -> Set.of(PROPOSE_EXCEPTION_ACCEPTANCE, START_RUN);
            default -> Set.of();
        };
    }

    /**
     * The capability an action name refers to, or empty if there is no such capability.
     *
     * <p><b>Empty is the point.</b> The edge receives an action as a string, and the honest answer
     * to {@code action=OVERRIDE_RATE} is not "you lack that capability" — which implies somebody
     * could hold it — but "no such act exists in this engine" (07 § 5). {@link Optional} rather
     * than a throw because the caller is at an HTTP boundary answering a request, and this is a
     * refusal, not a defect.
     *
     * <p>Case-folded at {@link Locale#ROOT} for the reason {@code FourEyes} folds an identity
     * there: a Turkish JVM lower-cases {@code "I"} to a dotless {@code "ı"}, and an authorisation
     * decision whose answer depends on the host's locale is not a decision.
     */
    public static Optional<Capability> named(String action) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        String wanted = action.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Capability capability : values()) {
            if (capability.name().equals(wanted)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
