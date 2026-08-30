package com.crisil.eir.policy.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The vocabulary the gate reads: {@link Capability}, {@link Role} and {@link Principal}.
 *
 * <p><b>Where the expected values come from.</b> 02 § 3's journeys and 07 § 4.2's list, read off the
 * documents. The role-to-capability assertions are written out longhand rather than derived from the
 * enum, because a test that computed the expectation from the thing under test would pass whatever
 * the table said — which is the failure mode these assertions exist to catch when somebody widens a
 * role in passing.
 */
class RoleModelTest {

    @Nested
    @DisplayName("the capability set is closed, and closed against one thing in particular")
    class Capabilities {

        @Test
        @DisplayName("every capability describes itself, because the role listing renders it")
        void everyCapabilityDescribesItself() {
            for (Capability capability : Capability.values()) {
                assertThat(capability.description())
                    .as("a capability with no description makes /api/access/roles illegible")
                    .isNotBlank();
            }
        }

        @Test
        @DisplayName("exactly two capabilities are approvals — the set 07 § 7 singles out for RBAC")
        void twoCapabilitiesAreApprovals() {
            Set<Capability> approvals = EnumSet.noneOf(Capability.class);
            for (Capability capability : Capability.values()) {
                if (capability.isApproval()) {
                    approvals.add(capability);
                }
            }
            assertThat(approvals).containsExactlyInAnyOrder(
                Capability.APPROVE_POLICY_VERSION, Capability.APPROVE_EXCEPTION_ACCEPTANCE);
        }

        @Test
        @DisplayName("every approval names the maker capabilities it checks, and no other does")
        void onlyApprovalsCheckSomething() {
            for (Capability capability : Capability.values()) {
                if (capability.isApproval()) {
                    assertThat(capability.checks())
                        .as("an approval that checks nothing is a single signature with a longer"
                            + " name")
                        .isNotEmpty();
                } else {
                    assertThat(capability.checks()).isEmpty();
                }
            }
        }

        @Test
        @DisplayName("the acceptance approval checks the proposal AND the run that produced it")
        void theAcceptanceApprovalChecksTwoThings() {
            assertThat(Capability.APPROVE_EXCEPTION_ACCEPTANCE.checks())
                .containsExactlyInAnyOrder(
                    Capability.PROPOSE_EXCEPTION_ACCEPTANCE, Capability.START_RUN);
            assertThat(Capability.APPROVE_POLICY_VERSION.checks())
                .containsExactly(Capability.DRAFT_POLICY_VERSION);
        }

        @Test
        @DisplayName("an act this engine does not have resolves to nothing")
        void unknownActsResolveToNothing() {
            assertThat(Capability.named("OVERRIDE_RATE")).isEmpty();
            assertThat(Capability.named("OVERRIDE_BALANCE")).isEmpty();
            assertThat(Capability.named("REOPEN_PERIOD")).isEmpty();
            assertThat(Capability.named(null)).isEmpty();
            assertThat(Capability.named("")).isEmpty();
            assertThat(Capability.named("close-period")).contains(Capability.CLOSE_PERIOD);
        }
    }

    @Nested
    @DisplayName("the roles, against the journeys they came from")
    class Roles {

        @Test
        @DisplayName("every role reads, because FR-808's trace is the one universal entitlement")
        void everyRoleReads() {
            for (Role role : Role.values()) {
                assertThat(role.grants(Capability.READ_FIGURES))
                    .as("%s must be able to read the figures it is responsible for", role)
                    .isTrue();
                assertThat(role.description()).isNotBlank();
            }
        }

        @Test
        @DisplayName("the capability sets are exactly the journeys of 02 § 3")
        void theCapabilitySetsMatchTheJourneys() {
            assertThat(Role.AUDITOR.capabilities())
                .containsExactly(Capability.READ_FIGURES);
            assertThat(Role.BATCH_OPERATOR.capabilities())
                .containsExactlyInAnyOrder(Capability.READ_FIGURES, Capability.START_RUN);
            assertThat(Role.FINANCIAL_CONTROLLER.capabilities())
                .containsExactlyInAnyOrder(Capability.READ_FIGURES, Capability.START_RUN,
                    Capability.PROPOSE_EXCEPTION_ACCEPTANCE, Capability.CLOSE_PERIOD);
            assertThat(Role.PRODUCT_CONTROL.capabilities())
                .containsExactlyInAnyOrder(
                    Capability.READ_FIGURES, Capability.DRAFT_POLICY_VERSION);
            assertThat(Role.APPROVER.capabilities())
                .containsExactlyInAnyOrder(Capability.READ_FIGURES,
                    Capability.APPROVE_POLICY_VERSION, Capability.APPROVE_EXCEPTION_ACCEPTANCE);
        }

        @Test
        @DisplayName("no single role holds both halves of a maker-checker pair")
        void nosingleRoleIsBothMakerAndChecker() {
            // True, and NOT the segregation control. See Principal: grants compose, and the
            // composed grant is what the runtime check exists for. This assertion is here so that a
            // future widening of one role is a test failure, not so that the runtime check can be
            // deleted.
            for (Role role : Role.values()) {
                for (Capability held : role.capabilities()) {
                    for (Capability checked : held.checks()) {
                        assertThat(role.grants(checked))
                            .as("role %s grants %s and the %s it checks", role, held, checked)
                            .isFalse();
                    }
                }
            }
        }

        @Test
        @DisplayName("an unknown role name resolves to nothing rather than to a default")
        void unknownRoleNamesResolveToNothing() {
            assertThat(Role.named("SUPERUSER")).isEmpty();
            assertThat(Role.named(null)).isEmpty();
            assertThat(Role.named("financial-controller")).contains(Role.FINANCIAL_CONTROLLER);
        }
    }

    @Nested
    @DisplayName("a principal holds a SET of roles, and that is what makes segregation real")
    class Principals {

        @Test
        @DisplayName("capabilities are the union of the roles granted")
        void capabilitiesAreTheUnion() {
            Principal composed =
                Principal.of("ops.superuser", Role.BATCH_OPERATOR, Role.APPROVER);

            assertThat(composed.capabilities()).containsExactlyInAnyOrder(
                Capability.READ_FIGURES, Capability.START_RUN,
                Capability.APPROVE_POLICY_VERSION, Capability.APPROVE_EXCEPTION_ACCEPTANCE);
            assertThat(composed.may(Capability.START_RUN)).isTrue();
            assertThat(composed.may(Capability.APPROVE_EXCEPTION_ACCEPTANCE)).isTrue();
            assertThat(composed.may(Capability.CLOSE_PERIOD)).isFalse();
        }

        @Test
        @DisplayName("the composed grant reports its toxic combination; a single role does not")
        void thecomposedGrantIsToxic() {
            assertThat(Principal.of("ops.superuser", Role.BATCH_OPERATOR, Role.APPROVER)
                .toxicCombinations())
                .containsExactly("APPROVE_EXCEPTION_ACCEPTANCE checks START_RUN");
            assertThat(Principal.of("policy.approver", Role.APPROVER).toxicCombinations())
                .isEmpty();
            assertThat(Principal.of("both.sides", Role.PRODUCT_CONTROL, Role.APPROVER)
                .toxicCombinations())
                .as("the maker-checker pair FourEyes was extracted for")
                .containsExactly("APPROVE_POLICY_VERSION checks DRAFT_POLICY_VERSION");
        }

        @Test
        @DisplayName("the identity keeps the directory's capitalisation and compares folded")
        void theIdentityIsStoredRawAndComparedFolded() {
            Principal principal = Principal.of(" Ops.Lead ", Role.AUDITOR);

            assertThat(principal.identity())
                .as("an audit sentence naming 'ops.lead' when the directory says 'Ops.Lead' is a"
                    + " sentence a reader has to reconcile")
                .isEqualTo("Ops.Lead");
            assertThat(principal.is("ops.lead")).isTrue();
            assertThat(principal.is("OPS.LEAD")).isTrue();
            assertThat(principal.is("ops.deputy")).isFalse();
            assertThat(principal.is(null)).isFalse();
        }

        @Test
        @DisplayName("a principal with no role is representable, because revocation is a real state")
        void anemptyGrantIsRepresentable() {
            Principal revoked = new Principal("left.the.bank", Set.of());

            assertThat(revoked.capabilities()).isEmpty();
            assertThat(revoked.may(Capability.READ_FIGURES)).isFalse();
            assertThat(revoked.describe()).contains("[no role]");
        }

        @Test
        @DisplayName("a blank identity is refused: 07 § 7 logs a principal on every mutating call")
        void ablankIdentityIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Principal.of("   ", Role.AUDITOR))
                .withMessageContaining("identity");
        }

        @Test
        @DisplayName("the roles set is defensive: mutating the caller's set does not widen a grant")
        void therolesSetIsCopied() {
            EnumSet<Role> granted = EnumSet.of(Role.AUDITOR);
            Principal principal = new Principal("reader.only", granted);
            granted.add(Role.APPROVER);

            assertThat(principal.capabilities())
                .as("a grant that a caller can widen after the fact is not a grant")
                .containsExactly(Capability.READ_FIGURES);
        }
    }
}
