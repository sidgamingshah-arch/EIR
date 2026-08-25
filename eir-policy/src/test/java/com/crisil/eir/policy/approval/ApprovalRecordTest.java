package com.crisil.eir.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The checker's sign-off as a value: who, when, and whether it is really a second person. */
class ApprovalRecordTest {

    private static final LocalDate SIGNED_ON = LocalDate.of(2027, 3, 15);
    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);

    private static PolicyVersion pendingBy(String maker) {
        return new PolicyVersion(
            "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
            EFFECTIVE_FROM, maker, null, null, PolicyVersionStatus.PENDING_APPROVAL);
    }

    @Nested
    @DisplayName("what an approval must carry")
    class Mandatory {

        @Test
        @DisplayName("an anonymous sign-off is refused")
        void checkerIsMandatory() {
            // The identity IS the control. A record with no name on it is indistinguishable from
            // no record, and FR-210's four eyes reduce to two.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApprovalRecord("   ", SIGNED_ON, "reviewed"))
                .withMessageContaining("anonymous sign-off is not a sign-off");
            assertThatNullPointerException()
                .isThrownBy(() -> new ApprovalRecord(null, SIGNED_ON, "reviewed"));
        }

        @Test
        @DisplayName("the date is mandatory and the note is not")
        void dateMandatoryNoteOptional() {
            // The split follows what 04 § 2.12 stores: checker and approved_at are columns, the
            // reasoning is not. A gate refusing an approval for a missing sentence would be
            // refusing on a condition no regulation states.
            assertThatNullPointerException()
                .isThrownBy(() -> new ApprovalRecord("checker", null, "reviewed"));

            ApprovalRecord terse = ApprovalRecord.by("accounting.policy.owner", SIGNED_ON);
            assertThat(terse.note()).as("absent, not null").isEmpty();
            assertThat(new ApprovalRecord("c", SIGNED_ON, null).note()).isEmpty();
        }

        @Test
        @DisplayName("checker and note are stripped as stored")
        void fieldsAreStripped() {
            // Stripped once, here, so that every identity comparison downstream sees the same
            // spelling. The alternative is each caller stripping, and the one that forgets is the
            // one that lets a whitespace variant through.
            ApprovalRecord padded = new ApprovalRecord("  owner  ", SIGNED_ON, "  reviewed  ");
            assertThat(padded.checker()).isEqualTo("owner");
            assertThat(padded.note()).isEqualTo("reviewed");
        }

        @Test
        @DisplayName("the audit line names the checker and the date")
        void describeNamesBoth() {
            assertThat(new ApprovalRecord("owner", SIGNED_ON, "ACPIR 53 checked").describe())
                .isEqualTo("approved by owner on 2027-03-15 (ACPIR 53 checked)");
            assertThat(ApprovalRecord.by("owner", SIGNED_ON).describe())
                .as("no note, no empty parentheses")
                .isEqualTo("approved by owner on 2027-03-15");
        }
    }

    @Nested
    @DisplayName("identity, compared the way a bypass would arrive")
    class Identity {

        @Test
        @DisplayName("stripped and case-folded, and folded to Locale.ROOT")
        void identityKeyNormalises() {
            // Expected values written out by hand: the key of any spelling of this identity is
            // the lower-cased, stripped form.
            assertThat(ApprovalRecord.identityKey("  Policy.Author ")).isEqualTo("policy.author");
            assertThat(ApprovalRecord.identityKey("POLICY.AUTHOR")).isEqualTo("policy.author");

            // Locale.ROOT rather than the default locale, so the answer does not depend on the
            // host. Under a Turkish locale "I".toLowerCase() is dotless "ı", which would make
            // two runs of the same control disagree about whether two identities match.
            assertThat(ApprovalRecord.identityKey("IRIS"))
                .as("case-folded independently of the JVM's locale")
                .isEqualTo("iris")
                .isNotEqualTo("ırıs")
                .isEqualTo("IRIS".toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("a case or whitespace variant of the maker is still self-approval")
        void selfApprovalAcrossVariants() {
            PolicyVersion pending = pendingBy("policy.author");
            assertThat(new ApprovalRecord("policy.author", SIGNED_ON, "").isSelfApprovalOf(pending))
                .as("the plain case")
                .isTrue();
            assertThat(new ApprovalRecord(" Policy.Author ", SIGNED_ON, "").isSelfApprovalOf(pending))
                .as("the case that gets past PolicyVersion's raw string comparison")
                .isTrue();
            assertThat(new ApprovalRecord("someone.else", SIGNED_ON, "").isSelfApprovalOf(pending))
                .isFalse();
        }

        @Test
        @DisplayName("a padded maker on the version is normalised too, not only the checker")
        void makerSideIsNormalisedAsWell() {
            // Both sides. PolicyVersion does not strip its maker — RoutingTableVersion does, this
            // one does not — so the variant can arrive on either side of the comparison, and
            // normalising only the checker would leave half the hole open.
            PolicyVersion pending = pendingBy("  Policy.Author  ");
            assertThat(new ApprovalRecord("policy.author", SIGNED_ON, "").isSelfApprovalOf(pending))
                .isTrue();
        }

        @Test
        @DisplayName("checkedBy matches an assigned checker across spellings, and nobody at all")
        void checkedByComparesIdentities() {
            ApprovalRecord record = new ApprovalRecord("accounting.policy.owner", SIGNED_ON, "");
            assertThat(record.checkedBy("Accounting.Policy.Owner ")).isTrue();
            assertThat(record.checkedBy("someone.else")).isFalse();
            // A version with no assigned checker has nothing for an approval to conflict with, so
            // absent must answer "no match" rather than throwing: the gate calls this on a field
            // that is legitimately null while a version is unapproved.
            assertThat(record.checkedBy(null)).as("nobody is not a match").isFalse();
            assertThat(record.checkedBy("  ")).as("blank is nobody").isFalse();
        }

        @Test
        @DisplayName("a null version is a caller defect")
        void nullVersionThrows() {
            assertThatNullPointerException()
                .isThrownBy(() -> ApprovalRecord.by("owner", SIGNED_ON).isSelfApprovalOf(null));
        }
    }
}
