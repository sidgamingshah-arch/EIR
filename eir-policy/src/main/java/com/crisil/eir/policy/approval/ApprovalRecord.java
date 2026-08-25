package com.crisil.eir.policy.approval;

import com.crisil.eir.policy.PolicyVersion;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * One checker's sign-off on one policy version — the {@code checker}/{@code approved_at} pair of
 * <a href="../../../../../../../../../docs/04-data-model.md">04 § 2.12</a>, as a value rather
 * than as two nullable columns on the version.
 *
 * <p>Its own type for two reasons. First, {@link PolicyVersion} can only <em>hold</em> a checker
 * and an approval date; it cannot say who is <em>offering</em> one, and the maker–checker gate
 * has to decide whether to accept the offer before those fields exist on the record. Second, the
 * offer is the thing that gets refused: a self-approval must come back as a refusal a batch can
 * report (FR-210, FR-905), and a refusal needs something to point at.
 *
 * <p>The note is optional. The audit weight of ACPIR's four-eyes requirement sits on identity and
 * date, which are mandatory here; the note is evidence <em>quality</em>, and a gate that refused
 * an approval for a missing sentence would be refusing on a condition no regulation states.
 *
 * @param checker   who signed off; never blank, and never the version's own maker
 * @param checkedOn the date of sign-off, which becomes the version's {@code approved_at}
 * @param note      what the checker relied on, or empty
 */
public record ApprovalRecord(String checker, LocalDate checkedOn, String note) {

    public ApprovalRecord {
        Objects.requireNonNull(checker, "checker");
        Objects.requireNonNull(checkedOn, "checkedOn");
        checker = checker.strip();
        if (checker.isEmpty()) {
            throw new IllegalArgumentException(
                "an approval names no checker; an anonymous sign-off is not a sign-off");
        }
        note = note == null ? "" : note.strip();
    }

    /** An approval carrying no note. */
    public static ApprovalRecord by(String checker, LocalDate checkedOn) {
        return new ApprovalRecord(checker, checkedOn, "");
    }

    /**
     * Whether this approval is the version's own maker signing off their own change.
     *
     * <p><b>This is not the test {@link PolicyVersion}'s constructor performs, and the difference
     * is the reason it exists here.</b> That constructor compares {@code maker.equals(checker)}
     * on the raw strings — exactly right for what it guards, an already-assembled pair — but it
     * normalises neither side, and {@code PolicyVersion} does not strip its {@code maker}. So
     * {@code " alice"} signs off {@code "alice"} and the constructor is satisfied. That is the
     * one plausible route to self-approval that survives a four-eyes control in a real bank: not
     * somebody typing their own name into the checker box, but a whitespace or case variant of
     * the same directory identity arriving through a second channel — an upload, a migration, a
     * screen that trims one field and not the other.
     *
     * <p>Identity is therefore compared stripped and case-folded. The cost of that choice is a
     * false refusal where two genuinely different people differ only in the case of their
     * identifier; the cost of the other choice is a policy version that moved recognised income
     * on no authority but its author's. The first is an inconvenience an operator can see and
     * escalate; the second is invisible, and ACPIR forbids the manual override that would
     * otherwise let somebody force the first through anyway (ADR-0008).
     */
    public boolean isSelfApprovalOf(PolicyVersion version) {
        Objects.requireNonNull(version, "version");
        return identityKey(version.maker()).equals(identityKey(checker));
    }

    /**
     * Whether {@code other} names the same person as this record's checker.
     *
     * <p>Same normalisation as {@link #isSelfApprovalOf}, and used by the gate to compare an
     * offered approval against a checker the version was already routed to. A {@code null}
     * {@code other} is "nobody", which matches no checker — a version with no assigned checker
     * has nothing for this to conflict with.
     */
    public boolean checkedBy(String other) {
        return other != null && !other.isBlank() && identityKey(checker).equals(identityKey(other));
    }

    /**
     * The comparison form of an identity: stripped and case-folded to {@link Locale#ROOT}.
     *
     * <p>{@code Locale.ROOT} rather than the default locale on purpose. Turkish lower-cases
     * {@code "I"} to dotless {@code "ı"}, so a run whose JVM locale differed from the one a
     * version was approved under would compare the same two identities differently — and a
     * control whose answer depends on the host's locale is not a control.
     */
    public static String identityKey(String identity) {
        Objects.requireNonNull(identity, "identity");
        return identity.strip().toLowerCase(Locale.ROOT);
    }

    /** A one-line audit sentence naming the approval. */
    public String describe() {
        return "approved by " + checker + " on " + checkedOn
            + (note.isEmpty() ? "" : " (" + note + ")");
    }
}
