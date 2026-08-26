package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four-eyes comparison, in the one place that now holds it.
 *
 * <p>It was stated in four places and answered two ways. Three stripped and case-folded;
 * {@code RoutingTableVersion} stripped and compared raw, which made it the only one that accepted
 * a maker approving their own change under a different capitalisation — while the DDL column the
 * row is stored in rejects exactly that, so the two would have disagreed about a routing table
 * already in force.
 */
class FourEyesTest {

    @Nested
    @DisplayName("the same person under any spelling is the same person")
    class Identity {

        @Test
        @DisplayName("whitespace and case are both defeated")
        void variantsAreTheSamePerson() {
            // All three variants, because a guard that holds for the trailing space and not the
            // leading one is not a guard.
            for (String variant : new String[] {
                "policy.author", "policy.author ", " policy.author", "Policy.Author",
                "  POLICY.AUTHOR  "}) {
                assertThat(FourEyes.isSelfApproval("policy.author", variant))
                    .as("checker '%s' against maker 'policy.author'", variant)
                    .isTrue();
            }
            assertThat(FourEyes.isSelfApproval("policy.author", "accounting.owner")).isFalse();
        }

        @Test
        @DisplayName("nobody is not the same person as anybody, including another nobody")
        void absenceIsNotAMatch() {
            // The answer matters in both directions. A draft with no checker assigned must not
            // read as self-approved, and two unassigned drafts must not read as approved by the
            // same person.
            assertThat(FourEyes.isSelfApproval("policy.author", null)).isFalse();
            assertThat(FourEyes.isSelfApproval("policy.author", "   ")).isFalse();
            assertThat(FourEyes.isSelfApproval(null, null)).isFalse();
            assertThat(FourEyes.sameIdentity("  ", "  ")).isFalse();
        }

        @Test
        @DisplayName("the comparison key is stripped and folded; the stored form is only stripped")
        void keyFoldsButStorageDoesNot() {
            // Case folding belongs in the comparison. An audit sentence naming "policy.author"
            // when the directory says "Policy.Author" is a sentence a reader has to reconcile,
            // so the stored form keeps the directory's capitalisation.
            assertThat(FourEyes.identityKey("  Policy.Author  ")).isEqualTo("policy.author");
            assertThat(FourEyes.requireIdentity("  Policy.Author  ", "maker", "why"))
                .isEqualTo("Policy.Author");
        }

        @Test
        @DisplayName("a blank identity is refused, with the reason the caller gave")
        void blankRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FourEyes.requireIdentity("   ", "maker",
                    "a version with no maker has no author"))
                .withMessageContaining("maker must not be blank")
                .withMessageContaining("a version with no maker has no author");
        }

        @Test
        @DisplayName("folding is locale-independent")
        void foldingDoesNotDependOnTheHost() {
            // Locale.ROOT rather than the default. Turkish lower-cases "I" to a dotless "ı", so a
            // run whose JVM locale differed from the one a version was approved under would
            // compare the same two identities differently — and a control whose answer depends on
            // the host's locale is not a control. Asserted on the character that exposes it.
            assertThat(FourEyes.identityKey("ISTANBUL.RISK"))
                .as("dotted i, whatever the host locale")
                .isEqualTo("istanbul.risk");
            assertThat(FourEyes.isSelfApproval("Istanbul.Risk", "ISTANBUL.RISK")).isTrue();
        }
    }
}
