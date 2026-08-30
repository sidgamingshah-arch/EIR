package com.crisil.eir.policy.access;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The roles 07 § 7's RBAC grants, each a fixed set of {@link Capability}.
 *
 * <p><b>Where these five come from.</b> Not from an imagined org chart — from the four user
 * journeys in 02 § 3, which name the actors and say what each of them does:
 *
 * <table>
 *   <caption>Journey to role</caption>
 *   <tr><th>Journey</th><th>Actor</th><th>Role</th></tr>
 *   <tr><td>02 § 3.1 month-end close</td><td>financial controller</td>
 *       <td>{@link #FINANCIAL_CONTROLLER}</td></tr>
 *   <tr><td>02 § 3.2 policy change</td><td>product control</td><td>{@link #PRODUCT_CONTROL}</td></tr>
 *   <tr><td>02 § 3.3 audit sample</td><td>statutory auditor</td><td>{@link #AUDITOR}</td></tr>
 *   <tr><td>07 § 1 the nightly and month-end batch</td><td>the scheduler</td>
 *       <td>{@link #BATCH_OPERATOR}</td></tr>
 *   <tr><td>07 § 4.2 maker–checker</td><td>the checker</td><td>{@link #APPROVER}</td></tr>
 * </table>
 *
 * <p><b>The role table is not the segregation-of-duties control, and must not be mistaken for
 * one.</b> Read the table alone and segregation looks structural: no single role holds both
 * {@link Capability#START_RUN} and {@link Capability#APPROVE_EXCEPTION_ACCEPTANCE}, so the
 * combination looks impossible. It is not, because an identity holds a <em>set</em> of roles
 * ({@link Principal}) and grants compose — {@code BATCH_OPERATOR + APPROVER} holds both, and that
 * is precisely the grant a production deployment drifts into when somebody needs cover over a
 * quarter-end. A control that is only a property of this table would be a control that cannot fail
 * (this repository has found seventeen of those). The control lives in
 * {@link AccessControl#decide}, over identities.
 *
 * <p><b>No role holds an override.</b> There is no capability to override a computed figure to hold
 * — {@link Capability} does not define one — so no role here can grant one and no future role can
 * either. 07 § 5.
 */
public enum Role {

    /**
     * Read-only. 02 § 3.3's statutory auditor, and any reporting consumer.
     *
     * <p>Named {@code AUDITOR} rather than {@code READER} because the read surface exists for a
     * named obligation — FR-808's self-service per-contract trace under 30 seconds — and naming the
     * role for the obligation is what stops the read surface being trimmed as an optimisation.
     */
    AUDITOR("read-only: traces, reports, and shadow replays of closed periods",
        EnumSet.of(Capability.READ_FIGURES)),

    /**
     * Starts runs and nothing else. The month-end and nightly scheduler.
     *
     * <p>Deliberately cannot close, cannot approve, and cannot propose an acceptance. A scheduler
     * identity that could close a period would be a close with no human signature on it, which
     * defeats 07 § 4.3's whole purpose.
     */
    BATCH_OPERATOR("start amortisation runs; no approval and no close",
        EnumSet.of(Capability.READ_FIGURES, Capability.START_RUN)),

    /**
     * 02 § 3.1: triggers the run, works the exception queue, confirms the reconciliations, and
     * locks the period.
     *
     * <p>Holds {@link Capability#PROPOSE_EXCEPTION_ACCEPTANCE} but <b>not</b>
     * {@link Capability#APPROVE_EXCEPTION_ACCEPTANCE}. That split is the whole of 07 § 4.2's last
     * bullet: the person who concluded the period should close over a standing exception is not the
     * person who signs for that conclusion.
     */
    FINANCIAL_CONTROLLER(
        "start a run, work the exception queue, propose acceptances, and close the period",
        EnumSet.of(Capability.READ_FIGURES, Capability.START_RUN,
            Capability.PROPOSE_EXCEPTION_ACCEPTANCE, Capability.CLOSE_PERIOD)),

    /**
     * 02 § 3.2: drafts a rule-set version and runs the mandatory impact preview.
     *
     * <p>Holds no approval capability, so a product-control identity cannot put its own draft into
     * force even before {@code MakerCheckerGate} sees it.
     */
    PRODUCT_CONTROL("draft policy versions, rule sets, routing tables and assumptions",
        EnumSet.of(Capability.READ_FIGURES, Capability.DRAFT_POLICY_VERSION)),

    /**
     * The checker: signs policy versions and exception acceptances.
     *
     * <p>Holds neither maker capability and neither {@link Capability#START_RUN} nor
     * {@link Capability#CLOSE_PERIOD}. A checker who could also start the run whose exceptions they
     * sign over is the exact combination {@link AccessControl}'s segregation rule refuses — and
     * because roles compose, refusing it here is not enough.
     */
    APPROVER("approve policy versions and exception acceptances; no maker capability",
        EnumSet.of(Capability.READ_FIGURES, Capability.APPROVE_POLICY_VERSION,
            Capability.APPROVE_EXCEPTION_ACCEPTANCE));

    private final String description;
    private final Set<Capability> capabilities;

    Role(String description, Set<Capability> capabilities) {
        this.description = description;
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }

    /** What this role is for, in one sentence, for the role listing. */
    public String description() {
        return description;
    }

    /** Every capability this role grants. Unmodifiable. */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    /** Whether this role grants {@code capability}. */
    public boolean grants(Capability capability) {
        return capability != null && capabilities.contains(capability);
    }

    /**
     * The role a name refers to, or empty if there is no such role.
     *
     * <p>Empty rather than a throw for the same reason as {@link Capability#named}: a register
     * built from configuration naming a role that does not exist is a refusal to report, not a
     * crash. A register that silently dropped the unknown role would grant the identity fewer
     * capabilities than intended, and under-granting fails visibly; one that defaulted to a role
     * would over-grant, and over-granting does not.
     */
    public static Optional<Role> named(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Role role : values()) {
            if (role.name().equals(wanted)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
