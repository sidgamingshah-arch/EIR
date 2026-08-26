package com.crisil.eir.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The four-eyes comparison: one rule, one place.
 *
 * <p><b>Why this exists.</b> "The maker and the checker must be different people" was stated in
 * four places and answered two different ways:
 *
 * <table>
 *   <caption>Where the rule lived</caption>
 *   <tr><th>Statement</th><th>Comparison</th><th>Verdict on {@code "Policy.Author"} vs
 *       {@code "policy.author"}</th></tr>
 *   <tr><td>{@code policy.PolicyVersion}</td><td>strip, {@code equalsIgnoreCase}</td>
 *       <td>refused</td></tr>
 *   <tr><td>{@code policy.approval.ApprovalRecord}</td><td>strip, lower-case
 *       {@code Locale.ROOT}</td><td>refused</td></tr>
 *   <tr><td>{@code calc.routing.RoutingTableVersion}</td><td>strip, {@code equals}</td>
 *       <td><b>accepted</b></td></tr>
 *   <tr><td>{@code period_balance} / {@code deemed_eir_derivation} DDL</td>
 *       <td>{@code lower(btrim(...))}</td><td>refused</td></tr>
 * </table>
 *
 * <p>So a routing table version — the artefact ADR-0006 makes the whole "change the routing
 * without a code deploy" story rest on — could be approved by its own maker under a different
 * capitalisation, and the row would then be <em>rejected by the database that stores it</em>. Three
 * of the four agreed and the fourth was the one guarding the artefact that changes how every event
 * is treated.
 *
 * <p>This class holds the comparison the other three already made. Each call site keeps its own
 * refusal message, because the wording is what tells a reader which control they tripped and is
 * pinned by tests; what belongs here is the answer to "are these the same person".
 *
 * <p><b>{@code Locale.ROOT} rather than the default locale</b>, carried over from
 * {@code ApprovalRecord} where the reasoning was first written down: Turkish lower-cases
 * {@code "I"} to a dotless {@code "ı"}, so a run whose JVM locale differed from the one a version
 * was approved under would compare the same two identities differently. A control whose answer
 * depends on the host's locale is not a control.
 */
public final class FourEyes {

    private FourEyes() {
    }

    /**
     * The comparison form of an identity: stripped and case-folded to {@link Locale#ROOT}.
     *
     * <p>Published rather than kept private because a caller persisting an approval needs to be
     * able to compute the same key the control compared on — a directory lookup or a uniqueness
     * index built on the raw string would disagree with this class about who is who.
     */
    public static String identityKey(String identity) {
        Objects.requireNonNull(identity, "identity");
        return identity.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether two identities name the same person.
     *
     * <p>A {@code null} or blank identity is "nobody", and nobody is not the same person as
     * anybody — including another nobody. That answer matters: a draft with no checker assigned
     * must not read as self-approved, and two unassigned drafts must not read as approved by the
     * same person.
     */
    public static boolean sameIdentity(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return identityKey(left).equals(identityKey(right));
    }

    /**
     * Whether {@code checker} is the same person as {@code maker} — the self-approval test.
     *
     * <p>Named separately from {@link #sameIdentity} despite being the same call, because the two
     * readings differ at every call site: "these strings match" is a fact and "this approval is
     * void" is a conclusion, and the conclusion is what a reader of the guard needs to see.
     */
    public static boolean isSelfApproval(String maker, String checker) {
        return sameIdentity(maker, checker);
    }

    /**
     * An identity in storage form: stripped, and refused if blank.
     *
     * <p>Not case-folded. The stored form keeps the capitalisation the directory gave it, because
     * an audit sentence naming "policy.author" when the directory says "Policy.Author" is a
     * sentence a reader has to reconcile. Case folding belongs in the comparison, which is
     * {@link #identityKey}.
     */
    public static String requireIdentity(String value, String field, String why) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank: " + why);
        }
        return stripped;
    }
}
