package com.crisil.eir.policy.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pool-level income suspension for cards and KCC (FR-608, 03 § 7.4).
 *
 * <p>Account-level suspension analysis is impractical at volume, ACPIR provides no portfolio
 * carve-out, and 03 § 7.4 has policy supply one with the pool definition as the approved
 * artefact. These tests are about that word: what the approval has to be for, and what happens to
 * an exposure suspended without one.
 */
class SuspensionPoolsTest {

    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);
    private static final LocalDate MAY = LocalDate.of(2027, 5, 15);
    private static final LocalDate JUNE = LocalDate.of(2027, 6, 1);
    private static final LocalDate JULY = LocalDate.of(2027, 7, 15);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 15);

    private static PolicyVersion version(String id, PolicyKind kind, LocalDate from) {
        return new PolicyVersion(id, kind, "pool fixture", from,
            "portfolio.owner", "accounting.policy.owner", MARCH, PolicyVersionStatus.EFFECTIVE);
    }

    private static PoolDefinition cards(String poolId, LocalDate from, String... members) {
        return new PoolDefinition(
            version(poolId + "." + from, PolicyKind.POOL_DEFINITION, from),
            poolId, "CREDIT_CARD",
            Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER),
            Set.of(members));
    }

    @Nested
    @DisplayName("PL-1: nothing is suspended without a definition in force")
    class Authority {

        @Test
        @DisplayName("exposures inside an approved pool are authorised")
        void coveredPasses() {
            SuspensionPools pools = SuspensionPools.of(cards("CARDS-RETAIL", APRIL, "C1", "C2"));

            InvariantResult result = pools.suspensionIsAuthorised(List.of("C1", "C2"), MAY);
            assertThat(result.id()).isEqualTo(InvariantId.PL_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(pools.covers("C1", MAY)).isTrue();
        }

        @Test
        @DisplayName("an exposure suspended with no pool fails, and is named")
        void uncoveredFails() {
            // The failure this exists for looks identical to a correct suspension in every ledger
            // it touches: income is nil, the suspense ledger has a balance, and nothing says the
            // decision was unauthorised. The suspended set is handed IN for exactly this reason —
            // derived from the pools it would be a tautology, because every exposure in a pool is
            // in a pool.
            SuspensionPools pools = SuspensionPools.of(cards("CARDS-RETAIL", APRIL, "C1", "C2"));

            InvariantResult result =
                pools.suspensionIsAuthorised(List.of("C1", "C2", "TL-9", "TL-10"), MAY);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("two exposures, each needing its own remedy — a pool or an analysis")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(result.detail()).contains("[TL-10, TL-9]");
        }

        @Test
        @DisplayName("a definition that has not taken effect yet authorises nothing")
        void notYetEffective() {
            SuspensionPools pools = SuspensionPools.of(cards("CARDS-RETAIL", JUNE, "C1"));
            assertThat(pools.covers("C1", MAY))
                .as("approved in March, effective in June: a May suspension has no cover")
                .isFalse();
            assertThat(pools.suspensionIsAuthorised(List.of("C1"), MAY).satisfied()).isFalse();
            assertThat(pools.suspensionIsAuthorised(List.of("C1"), JULY).satisfied()).isTrue();
        }
    }

    @Nested
    @DisplayName("the approval has to be for a pool")
    class Authorisation {

        @Test
        @DisplayName("a fee rule set's approval is not authority to suspend income on a card book")
        void wrongKindRefused() {
            // The maker and checker who signed a fee rule set were looking at fee
            // classifications. Treating that signature as cover for suspending recognised revenue
            // on a million card accounts is exactly what PolicyKind exists to prevent — the same
            // argument that stops a fee repricing re-approving the routing table.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PoolDefinition(
                    version("FEE-2027.1", PolicyKind.FEE_RULE_SET, APRIL),
                    "CARDS-RETAIL", "CREDIT_CARD",
                    Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER), Set.of("C1")))
                .withMessageContaining("is not authority to suspend income");
        }

        @Test
        @DisplayName("an empty definition is a failed load, not an empty pool")
        void emptyRefused() {
            // It suspends nothing, so this is not a safety question. It is that a definition with
            // no members would sit in the set looking like cover for a pool that has none.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PoolDefinition(
                    version("POOL-1", PolicyKind.POOL_DEFINITION, APRIL),
                    "CARDS-RETAIL", "CREDIT_CARD",
                    Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER), Set.of()))
                .withMessageContaining("a failed load");
        }
    }

    @Nested
    @DisplayName("PL-2: the carve-out is for cards and KCC, not for anything pooled")
    class Eligibility {

        private PoolDefinition termLoans(String poolId) {
            return new PoolDefinition(
                version(poolId, PolicyKind.POOL_DEFINITION, APRIL),
                poolId, "HOUSING_LOAN", Set.of(), Set.of(poolId + "-1"));
        }

        @Test
        @DisplayName("a pool of term loans is the abuse the control is for")
        void ineligibleProductFails() {
            // Pool-level suspension exists because account-level analysis is impractical for
            // cards and KCC. Pooling a book of term loans, where it is entirely practical, avoids
            // an analysis that can be done — which is the whole reason this is not a general
            // licence to suspend by pool.
            InvariantResult result = termLoans("TL-POOL").eligibility();
            assertThat(result.id()).isEqualTo(InvariantId.PL_2);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail()).contains("the analysis it avoids is one that can be done");
        }

        @Test
        @DisplayName("eligibility is read off the feature the tier assignment already uses")
        void eligibilityReusesTheTierFeature() {
            // Not a second product list. "Is this a portfolio-managed revolver" is a question the
            // tier assignment answers off the same feature set, and two answers would eventually
            // disagree — leaving a book Tier 2 for measurement and account-level for suspension,
            // with nothing saying which is right.
            assertThat(cards("CARDS-RETAIL", APRIL, "C1").eligibility().satisfied()).isTrue();
            assertThat(SuspensionPools.of(cards("CARDS-RETAIL", APRIL, "C1"))
                .measurementTierOfPooledExposures())
                .as("and the measurement tier the same feature implies")
                .isEqualTo(com.crisil.eir.domain.MaterialityTier.TIER_2);
        }

        @Test
        @DisplayName("three ineligible pools report three, not one")
        void aggregateCountsThem() {
            // conjunction keeps only the first breach's deviation among same-id results, so one
            // result per pool would publish a deviation of one and read as a single
            // misconfiguration. The remedy is per pool — dissolve it — so the count is of pools.
            SuspensionPools pools = SuspensionPools.of(
                cards("CARDS-RETAIL", APRIL, "C1"),
                termLoans("TL-POOL-A"), termLoans("TL-POOL-B"), termLoans("TL-POOL-C"));

            InvariantResult result = pools.eligibility(MAY);
            assertThat(result.id()).isEqualTo(InvariantId.PL_2);
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(result.detail()).contains("TL-POOL-A").contains("TL-POOL-C");
        }
    }

    @Nested
    @DisplayName("resolution is latest-wins within a pool id, which the policy registry is not")
    class Grain {

        @Test
        @DisplayName("many pools are in force at once; the registry would return one")
        void theRegistryIsTheWrongGrain() {
            // The finding this class's javadoc records, pinned so it cannot be quietly undone by
            // someone reaching for the registry. PolicyVersionRegistry resolves ONE version per
            // PolicyKind, latest-wins, which is right for a fee rule set and wrong here: every
            // pool definition is a POOL_DEFINITION version, so the registry returns whichever
            // pool was defined last and discards the rest.
            PoolDefinition retail = cards("CARDS-RETAIL", APRIL, "C1");
            PoolDefinition corporate = cards("CARDS-CORPORATE", JUNE, "C9");

            PolicyVersionRegistry registry = PolicyVersionRegistry.of(
                List.of(retail.version(), corporate.version()));
            assertThat(registry.inForceOn(PolicyKind.POOL_DEFINITION, JULY))
                .as("one answer, and it is the later pool — the retail book is simply gone")
                .contains(corporate.version());

            SuspensionPools pools = SuspensionPools.of(retail, corporate);
            assertThat(pools.inForceOn(JULY))
                .as("both, which is what a bank with two card pools has")
                .containsExactlyInAnyOrder(retail, corporate);
            assertThat(pools.covers("C1", JULY)).isTrue();
            assertThat(pools.covers("C9", JULY)).isTrue();
        }

        @Test
        @DisplayName("a later version of a pool replaces its membership, and only from its own date")
        void latestWinsWithinAPool() {
            // Same pool, two versions: April covers C1 and C2, June covers C1 only — C2 was
            // closed or moved to account-level analysis. So C2 is authorised in May and not in
            // July, and the change takes effect on its own date rather than retrospectively.
            SuspensionPools pools = SuspensionPools.of(
                cards("CARDS-RETAIL", APRIL, "C1", "C2"),
                cards("CARDS-RETAIL", JUNE, "C1"));

            assertThat(pools.sizeOn(MAY)).isEqualTo(1);
            assertThat(pools.covers("C2", MAY)).as("in force in May").isTrue();
            assertThat(pools.covers("C2", JULY)).as("dropped from the June version").isFalse();
            assertThat(pools.covers("C1", JULY)).isTrue();

            assertThat(pools.suspensionIsAuthorised(List.of("C1", "C2"), JULY).deviation())
                .as("suspending C2 in July is now unauthorised, and that is the point of a version")
                .isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Nested
    @DisplayName("configurations with no determinate answer are refused, not reported")
    class Refusals {

        @Test
        @DisplayName("an exposure in two pools in force is refused")
        void overlappingMembership() {
            // Two pools that both contain an exposure disagree about nothing until they disagree
            // about suspension, and then the answer is whichever was found first. Same argument
            // the policy registry makes for refusing two versions with an identical effective
            // date.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspensionPools.of(
                    cards("CARDS-RETAIL", APRIL, "C1", "C2"),
                    cards("CARDS-PREMIUM", JUNE, "C2")))
                .withMessageContaining("exposure C2 is in pools")
                .withMessageContaining("nothing says which governs");
        }

        @Test
        @DisplayName("the overlap check is exact, not sampled: it catches an overlap that opens later")
        void overlapAtALaterBoundary() {
            // Membership changes only at an effectiveFrom, so testing every distinct effectiveFrom
            // tests every date. Here nothing overlaps in April — CARDS-PREMIUM does not exist yet
            // — and the overlap opens on 1 June. A check that looked only at the earliest date, or
            // at "today", would pass.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspensionPools.of(
                    cards("CARDS-RETAIL", APRIL, "C1"),
                    cards("CARDS-PREMIUM", JUNE, "C1")))
                .withMessageContaining("both in force on 2027-06-01");
        }

        @Test
        @DisplayName("two versions of one pool on the same date are refused")
        void duplicateEffectiveDate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SuspensionPools.of(
                    cards("CARDS-RETAIL", APRIL, "C1"),
                    new PoolDefinition(
                        version("CARDS-RETAIL.alt", PolicyKind.POOL_DEFINITION, APRIL),
                        "CARDS-RETAIL", "CREDIT_CARD",
                        Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER), Set.of("C2"))))
                .withMessageContaining("has two versions effective 2027-04-01");
        }

        @Test
        @DisplayName("the same exposure in one pool across successive versions is fine")
        void successiveVersionsAreNotAnOverlap() {
            // The boundary the overlap check must not trip on. C1 is in the April version and the
            // June version of the SAME pool, which is the ordinary case — a pool whose membership
            // is restated. Only two pools sharing a member is ambiguous.
            SuspensionPools pools = SuspensionPools.of(
                cards("CARDS-RETAIL", APRIL, "C1", "C2"),
                cards("CARDS-RETAIL", JUNE, "C1", "C3"));
            assertThat(pools.poolIds()).containsExactly("CARDS-RETAIL");
            assertThat(pools.covers("C3", JULY)).isTrue();
        }
    }
}
