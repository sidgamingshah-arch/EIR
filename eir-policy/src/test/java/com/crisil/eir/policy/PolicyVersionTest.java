package com.crisil.eir.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Phase 2 spine: one approved version of one kind of policy.
 *
 * <p>Asserted here rather than left to the units that build on it, because eleven work units
 * read this type and a defect in it would surface as eleven unrelated-looking failures.
 */
class PolicyVersionTest {

    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 15);

    private static PolicyVersion approved() {
        return new PolicyVersion(
            "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
            APRIL, "policy.author", "accounting.policy.owner", MARCH,
            PolicyVersionStatus.EFFECTIVE);
    }

    @Nested
    @DisplayName("maker-checker, enforced at construction")
    class MakerChecker {

        @Test
        @DisplayName("self-approval is unrepresentable, not merely invalid")
        void selfApprovalIsRefused() {
            // The distinction matters. A workflow that VALIDATES maker != checker can be
            // bypassed by any code path that constructs the record directly; a constructor
            // that refuses it cannot. ACPIR forbids manual override (ADR-0008), which makes
            // this approval record the only evidence a second person ever looked at the
            // policy governing a published figure.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyVersion(
                    "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", APRIL,
                    "same.person", "same.person", MARCH, PolicyVersionStatus.APPROVED))
                .withMessageContaining("Self-approval is not a defective approval");
        }

        @Test
        @DisplayName("an approved version must name its checker and its approval date")
        void approvedNeedsCheckerAndDate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyVersion(
                    "R-1", PolicyKind.ROUTING_TABLE, "d", APRIL, "maker", null, MARCH,
                    PolicyVersionStatus.APPROVED))
                .withMessageContaining("no checker named");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyVersion(
                    "R-1", PolicyKind.ROUTING_TABLE, "d", APRIL, "maker", "checker", null,
                    PolicyVersionStatus.EFFECTIVE))
                .withMessageContaining("no approval date");
        }

        @Test
        @DisplayName("a DRAFT may have no checker at all")
        void draftNeedsNoChecker() {
            // The gate is on approval, not on existence. A draft nobody has reviewed yet is
            // the normal state of a policy change in progress.
            PolicyVersion draft = new PolicyVersion(
                "FEE-2027.2", PolicyKind.FEE_RULE_SET, "in progress", APRIL,
                "policy.author", null, null, PolicyVersionStatus.DRAFT);
            assertThat(draft.status().isApproved()).isFalse();
            assertThat(draft.describe()).contains("unapproved");
        }

        @Test
        @DisplayName("whitespace and case cannot defeat the self-approval guard")
        void identitiesAreNormalisedBeforeComparison() {
            // A code review on the first cut of this class caught the guard comparing RAW
            // strings, so "policy.author" against "policy.author " constructed successfully and
            // describe() then emitted a two-person approval record for a version one person made
            // and signed. RoutingTableVersion — the type this one generalises — already got it
            // right by stripping inside its requireText before comparing; generalising it
            // dropped the normalisation.
            //
            // Pinned in all three variants, because a guard that holds for the trailing space
            // and not the leading one is not a guard.
            for (String variant : new String[] {
                "policy.author ", " policy.author", "Policy.Author", "  POLICY.AUTHOR  "}) {
                assertThatIllegalArgumentException()
                    .as("checker '%s' against maker 'policy.author'", variant)
                    .isThrownBy(() -> new PolicyVersion(
                        "FEE-2027.1", PolicyKind.FEE_RULE_SET, "d", APRIL,
                        "policy.author", variant, MARCH, PolicyVersionStatus.EFFECTIVE))
                    .withMessageContaining("Self-approval");
            }

            // And the identities really are stored normalised, not merely compared that way —
            // otherwise the audit sentence carries the untrimmed form.
            PolicyVersion padded = new PolicyVersion(
                "  FEE-2027.9  ", PolicyKind.FEE_RULE_SET, "  spaced  ", APRIL,
                "  policy.author  ", "  accounting.owner  ", MARCH,
                PolicyVersionStatus.EFFECTIVE);
            assertThat(padded.id()).isEqualTo("FEE-2027.9");
            assertThat(padded.maker()).isEqualTo("policy.author");
            assertThat(padded.checker()).isEqualTo("accounting.owner");
        }

        @Test
        @DisplayName("a blank checker is stored as absent, not as blank")
        void blankCheckerBecomesNull() {
            // So that downstream gates can test for absence one way rather than each inventing
            // its own null-or-blank check — which is how one of them ends up disagreeing.
            PolicyVersion draft = new PolicyVersion(
                "FEE-2027.3", PolicyKind.FEE_RULE_SET, "d", APRIL, "maker", "   ", null,
                PolicyVersionStatus.DRAFT);
            assertThat(draft.checker()).isNull();
        }

        @Test
        @DisplayName("an unexplained version is refused")
        void descriptionIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyVersion(
                    "R-1", PolicyKind.ROUTING_TABLE, "  ", APRIL, "m", "c", MARCH,
                    PolicyVersionStatus.APPROVED))
                .withMessageContaining("an unexplained version is not an audit trail");
        }
    }

    @Nested
    @DisplayName("approval and effect are separate dates")
    class ApprovalVersusEffect {

        @Test
        @DisplayName("APPROVED is not yet operative; EFFECTIVE is")
        void approvedIsNotEffective() {
            // The reason there are five states and not an approved flag. A version approved in
            // March to take effect on 1 April is approved and not operative, and a run dated
            // in March must not resolve against it.
            PolicyVersion pending = approved().withStatus(PolicyVersionStatus.APPROVED);
            assertThat(pending.status().isApproved()).isTrue();
            assertThat(pending.status().isOperative()).isFalse();
            assertThat(pending.isEffectiveOn(APRIL))
                .as("approved but not in force resolves against nothing")
                .isFalse();

            assertThat(approved().isEffectiveOn(APRIL)).isTrue();
            assertThat(approved().isEffectiveOn(APRIL.minusDays(1)))
                .as("the day before it takes effect")
                .isFalse();
        }

        @Test
        @DisplayName("describe() does not claim approval on an unapproved version")
        void describeDoesNotOverclaim() {
            // The second review finding. The approval clause used to be read off the checker
            // field alone, and the constructor deliberately permits an unapproved version to
            // carry a PROPOSED checker — so a DRAFT rendered as "DRAFT, made by X, approved by
            // Y on D". A sentence that contradicts itself in its own second clause is worse
            // than a terse one, because the half a reader trusts is the false half.
            PolicyVersion draftWithProposedChecker = new PolicyVersion(
                "FEE-2027.4", PolicyKind.FEE_RULE_SET, "awaiting review", APRIL,
                "policy.author", "accounting.policy.owner", MARCH, PolicyVersionStatus.DRAFT);

            assertThat(draftWithProposedChecker.describe())
                .as("names the proposed checker without asserting an approval that has not happened")
                .contains("unapproved (proposed checker accounting.policy.owner)")
                .doesNotContain("approved by accounting.policy.owner on");

            assertThat(approved().describe())
                .as("and a genuinely approved version still says so")
                .contains("approved by accounting.policy.owner on 2027-03-15");
        }

        @Test
        @DisplayName("two operative versions can both have taken effect; resolution is the registry's job")
        void isEffectiveOnIsNecessaryNotSufficient() {
            // The gap that makes "reject overlapping ranges" unimplementable as stated: there is
            // no effectiveTo, and SUPERSEDED is operative so closed periods stay replayable
            // (DT-1). So BOTH of these answer true for a June date, and this predicate cannot
            // be the thing that picks between them.
            PolicyVersion superseded = new PolicyVersion(
                "FEE-2027.0", PolicyKind.FEE_RULE_SET, "the old one",
                LocalDate.of(2027, 1, 1), "maker", "checker", MARCH,
                PolicyVersionStatus.SUPERSEDED);
            LocalDate june = LocalDate.of(2027, 6, 30);

            assertThat(superseded.isEffectiveOn(june)).isTrue();
            assertThat(approved().isEffectiveOn(june)).isTrue();

            // The registry resolves it by latest-wins, and that rule is stated on isEffectiveOn's
            // javadoc so two registries cannot invent two answers. Asserted here on the ordering
            // the rule turns on, since the registry itself lives in another package.
            assertThat(approved().effectiveFrom())
                .as("latest effectiveFrom not after the date is the one in force")
                .isAfter(superseded.effectiveFrom());
        }

        @Test
        @DisplayName("SUPERSEDED stays operative, so a closed period still resolves")
        void supersededRemainsResolvable() {
            // Retained rather than deleted: a replay of a closed period must resolve against
            // the version that was in force when it closed, or it cannot reproduce the
            // figures (invariant DT-1).
            PolicyVersion old = approved().withStatus(PolicyVersionStatus.SUPERSEDED);
            assertThat(old.isEffectiveOn(APRIL))
                .as("a superseded version is history, not a deletion")
                .isTrue();
        }

        @Test
        @DisplayName("a retrospective version is permitted and never silent")
        void retrospectiveIsFlagged() {
            // Effective 1 March, approved 15 March: a correction to a closed-period
            // misclassification. Legitimate, and it restates figures somebody has already
            // reported, so it is asked rather than assumed.
            PolicyVersion backdated = new PolicyVersion(
                "FEE-2027.0", PolicyKind.FEE_RULE_SET, "correcting a misclassification",
                LocalDate.of(2027, 3, 1), "maker", "checker", MARCH,
                PolicyVersionStatus.EFFECTIVE);
            assertThat(backdated.isRetrospective()).isTrue();
            assertThat(backdated.describe()).contains("(RETROSPECTIVE)");

            assertThat(approved().isRetrospective())
                .as("approved in March, effective in April: forward, not retrospective")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the kinds are versioned separately, on purpose")
    class Kinds {

        @Test
        @DisplayName("every kind moves recognised income, so every kind needs a preview")
        void everyKindMovesIncome() {
            // Asserted across the enum rather than on one constant, so that adding a kind
            // forces a decision about whether it needs an impact preview instead of
            // inheriting one silently.
            for (PolicyKind kind : PolicyKind.values()) {
                assertThat(kind.movesRecognisedIncome())
                    .as("%s", kind)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a fee repricing does not re-approve the routing table")
        void kindsAreIndependent() {
            // The reason PolicyKind exists at all. Binding the kinds into one version number
            // would force a fee repricing to re-approve routing, and every forced
            // re-approval is an invitation to approve without reading. ADR-0006 turns on the
            // routing table being independently versionable.
            PolicyVersion fee = approved();
            PolicyVersion routing = new PolicyVersion(
                "RT-2027.1", PolicyKind.ROUTING_TABLE, "IASB ED on B5.4.5", APRIL,
                "maker", "checker", MARCH, PolicyVersionStatus.EFFECTIVE);
            assertThat(fee.kind()).isNotEqualTo(routing.kind());
            assertThat(fee.id()).isNotEqualTo(routing.id());
        }
    }
}
