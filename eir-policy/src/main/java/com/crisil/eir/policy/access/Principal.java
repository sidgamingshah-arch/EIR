package com.crisil.eir.policy.access;

import com.crisil.eir.domain.FourEyes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An identity and the roles granted to it — the subject of every authorisation decision.
 *
 * <p><b>An identity holds a set of roles, not one role.</b> That is what makes the
 * segregation-of-duties check in {@link AccessControl} a real control rather than a property of
 * {@link Role}'s table. Read the table alone and no single role holds both
 * {@link Capability#START_RUN} and {@link Capability#APPROVE_EXCEPTION_ACCEPTANCE}, so the
 * combination looks structurally impossible; grant one identity {@code BATCH_OPERATOR} and
 * {@code APPROVER} — the grant a real deployment drifts into the first time somebody needs cover
 * over a quarter-end — and it holds both. A control that could only fire on an input the type system
 * forbade would be the eighteenth control in this repository that cannot fail.
 *
 * <p><b>The identity is stored as the directory spelled it and compared as {@code FourEyes}
 * says.</b> {@link #identity()} keeps its capitalisation, because an audit sentence naming
 * {@code "policy.author"} when the directory says {@code "Policy.Author"} is a sentence a reader has
 * to reconcile. Every comparison goes through {@link FourEyes}, which strips and case-folds at
 * {@code Locale.ROOT}; nothing here re-implements it. That rule was stated in four places in this
 * codebase with two answers, and the one place it was written a fourth time with a plain
 * {@code equals} was the one guarding the routing table.
 *
 * @param identity the identity as the directory spells it; never blank
 * @param roles    the roles granted; may be empty, which is a principal that may do nothing —
 *                 representable on purpose, because a revoked grant is a real state and a
 *                 constructor that refused it would force callers to model revocation as absence
 */
public record Principal(String identity, Set<Role> roles) {

    public Principal {
        identity = FourEyes.requireIdentity(identity, "identity",
            "07 § 7 logs every mutating call with its principal; an anonymous principal makes that"
                + " log a record of calls with no callers");
        Objects.requireNonNull(roles, "roles");
        roles = roles.isEmpty()
            ? Collections.unmodifiableSet(EnumSet.noneOf(Role.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    /** A principal with one role. */
    public static Principal of(String identity, Role role) {
        Objects.requireNonNull(role, "role");
        return new Principal(identity, EnumSet.of(role));
    }

    /**
     * A principal with several roles — the grant that makes segregation of duties matter.
     *
     * <p>Deliberately as easy to build as the single-role form. A model that made the composed
     * grant awkward would push a deployment towards inventing a wider role instead, and a wide role
     * is the same breach with no name on it.
     */
    public static Principal of(String identity, Role first, Role... rest) {
        Objects.requireNonNull(first, "first");
        EnumSet<Role> granted = EnumSet.of(first);
        for (Role role : Objects.requireNonNull(rest, "rest")) {
            granted.add(Objects.requireNonNull(role, "role"));
        }
        return new Principal(identity, granted);
    }

    /** The union of every capability this identity's roles grant. Unmodifiable. */
    public Set<Capability> capabilities() {
        EnumSet<Capability> union = EnumSet.noneOf(Capability.class);
        for (Role role : roles) {
            union.addAll(role.capabilities());
        }
        return Collections.unmodifiableSet(union);
    }

    /**
     * Whether any of this identity's roles grants {@code capability}.
     *
     * <p>Necessary and not sufficient for an act to be permitted — see
     * {@link AccessControl#decide}, which then asks whether this identity is already on the other
     * side of the control.
     */
    public boolean may(Capability capability) {
        if (capability == null) {
            return false;
        }
        for (Role role : roles) {
            if (role.grants(capability)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code asserted} names this same person, by {@code FourEyes}' comparison. */
    public boolean is(String asserted) {
        return FourEyes.sameIdentity(identity, asserted);
    }

    /**
     * Every maker/checker pair this identity holds both halves of — 07 § 7's segregation of duties,
     * reported as a standing condition of the <em>grant</em> rather than of any one request.
     *
     * <p><b>Why report a combination that no single request has yet abused.</b> Because the request
     * is the wrong place to find this. {@link AccessControl} refuses the individual act, which
     * protects the close but leaves the grant in place — and a grant that lets one identity both
     * start runs and sign over their exceptions is a finding at the next audit whether or not it was
     * ever exercised. 07 § 4.1's whole premise is that a control is asserted and <em>reported</em>,
     * not merely enforced when tripped. This is what {@code /api/access/whoami} puts on the
     * response, so the operator sees the combination before an auditor does.
     *
     * <p>Sorted by pair text, so the report is stable — {@code EnumSet} iteration is already
     * deterministic, and sorting the rendered pairs keeps it so if the enum is ever reordered.
     *
     * @return one entry per pair, as {@code "APPROVE_X checks MAKER_Y"}; empty when the grant is
     *         clean
     */
    public List<String> toxicCombinations() {
        Set<Capability> held = capabilities();
        Set<String> found = new TreeSet<>();
        for (Capability approval : held) {
            for (Capability maker : approval.checks()) {
                if (held.contains(maker)) {
                    found.add(approval.name() + " checks " + maker.name());
                }
            }
        }
        return List.copyOf(new ArrayList<>(found));
    }

    /** One audit sentence: who, and what they hold. */
    public String describe() {
        Set<String> roleNames = new TreeSet<>();
        for (Role role : roles) {
            roleNames.add(role.name());
        }
        return identity + " as " + (roleNames.isEmpty() ? "[no role]" : roleNames);
    }

    @Override
    public String toString() {
        return describe();
    }
}
