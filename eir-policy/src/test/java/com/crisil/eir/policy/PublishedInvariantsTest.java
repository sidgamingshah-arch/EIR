package com.crisil.eir.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleKey;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import com.crisil.eir.policy.preview.ActivationDecision;
import com.crisil.eir.policy.preview.ActivationRefusalReason;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.routing.RoutingCoverage;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four Phase 2 invariants, asserted as published rather than merely computed.
 *
 * <p><b>Why this file exists.</b> Each of `PG_1`, `PV_1`, `RT_1` and `RS_1` was identified by
 * the work unit that needed it, during a parallel build in which no unit could edit
 * {@code InvariantId}. Each unit did the right thing with that constraint: it computed the
 * condition, returned it as plain data or under a borrowed id, and reported the id it wanted.
 * The result was four controls that were correct and unasserted — and a computed control
 * nobody asserts is indistinguishable from one that does not exist.
 *
 * <p>So the constants alone are not the change. Adding four enum values would leave four
 * unused constants, which is worse than the gap: it reads as coverage. These tests are what
 * make the difference between an identifier and a control, and each one asserts the same three
 * things — the right id is published, a breach carries a deviation a caller can act on, and the
 * result does not silently merge with a different claim under the same id.
 *
 * <p><b>On the deviations.</b> None of the four has a money size, and each therefore carries a
 * count: versions implicated, uncovered days, fee codes without a default, or simply one. That
 * is deliberate and it is why they could not share {@link InvariantId#DT_1}, whose deviation is
 * an amount. {@code InvariantResult.conjunction} keeps only the first breach's deviation among
 * results sharing an id — a defect this engine has recorded finding three times — so a count
 * and an amount under one identifier publishes whichever came first and loses the other.
 */
class PublishedInvariantsTest {

    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);
    private static final LocalDate JUNE = LocalDate.of(2027, 6, 30);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 15);

    private static PolicyVersion version(String id, PolicyKind kind, LocalDate from) {
        return new PolicyVersion(id, kind, "published-invariant fixture", from,
            "policy.author", "accounting.policy.owner", MARCH, PolicyVersionStatus.EFFECTIVE);
    }

    @Nested
    @DisplayName("PG-1: no policy version effective without a current impact preview")
    class ImpactPreviewGate {

        @Test
        @DisplayName("a permitted activation passes under PG-1")
        void permittedPasses() {
            InvariantResult result = new ActivationDecision(
                true, null, "FEE-2027.1", "preview matches the draft and the book").asInvariantResult();

            assertThat(result.id()).isEqualTo(InvariantId.PG_1);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("a refusal fails under PG-1, with the reason in the detail and a count as deviation")
        void refusalFails() {
            // The reason belongs in the detail rather than the deviation: a numeric encoding of
            // which of the seven refusals fired would invite arithmetic on an enum, and a close
            // summing deviations wants a count of blocked versions, not a sum of reason codes.
            InvariantResult result = new ActivationDecision(
                false, ActivationRefusalReason.NO_PREVIEW_STORED, "FEE-2027.2",
                "no preview stored for this version").asInvariantResult();

            assertThat(result.id()).isEqualTo(InvariantId.PG_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("one blocked version, so a sum across a batch counts them")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail()).contains("NO_PREVIEW_STORED");
        }
    }

    @Nested
    @DisplayName("PV-1: a policy version resolves for every date in a closed period")
    class PolicyResolvability {

        @Test
        @DisplayName("a resolvable date passes under PV-1, not under DT-1")
        void resolvablePassesUnderPvOne() {
            // The substance of this one is the ID, not the outcome. It previously published
            // under DT_1 with a javadoc warning never to conjoin it with a replay result —
            // an honest workaround for an id that did not exist, and one that put the burden
            // on every caller to remember. It now has its own.
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(
                List.of(version("FEE-2027.1", PolicyKind.FEE_RULE_SET, APRIL)));

            InvariantResult result = registry.policyResolvableOn(PolicyKind.FEE_RULE_SET, JUNE);
            assertThat(result.id())
                .as("its own identifier, so a workpaper reads 'policy resolvable' and not 'replay'")
                .isEqualTo(InvariantId.PV_1);
            assertThat(result.id()).isNotEqualTo(InvariantId.DT_1);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("a date before every version fails under PV-1 rather than resolving to the earliest")
        void unresolvableFails() {
            // A period with a date no version governs cannot be replayed at all, which is a
            // different failure from one that replays to different numbers. Returning the
            // earliest version would make the period look replayable and reproduce the wrong
            // policy.
            PolicyVersionRegistry registry = PolicyVersionRegistry.of(
                List.of(version("FEE-2027.1", PolicyKind.FEE_RULE_SET, APRIL)));

            InvariantResult result = registry.policyResolvableOn(
                PolicyKind.FEE_RULE_SET, APRIL.minusDays(1));
            assertThat(result.id()).isEqualTo(InvariantId.PV_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("the breach is the absence of an answer, which has no money size")
                .isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Nested
    @DisplayName("RT-1: every routed event resolves to a table version in force")
    class RoutingCoverageAssertion {

        @Test
        @DisplayName("a fully covered window passes under RT-1")
        void coveredPasses() {
            RoutingTableRegistry registry = RoutingTableRegistry.of(RoutingTable.currentDefault());
            RoutingCoverage coverage = registry.coverageOver(
                LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30));

            assertThat(coverage.isComplete()).isTrue();
            InvariantResult result = coverage.asInvariantResult();
            assertThat(result.id()).isEqualTo(InvariantId.RT_1);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("a gap fails under RT-1 with the uncovered day count as the deviation")
        void gapFailsWithDayCount() {
            // The baseline table takes effect 2026-05-01, so a window opening before that has
            // dates no approved table governs. The deviation is the day count because that is
            // what a close can act on — and because a day count under DT-1, whose deviation is
            // a money amount, would be the two-claims-one-id defect over again.
            RoutingTableRegistry registry = RoutingTableRegistry.of(RoutingTable.currentDefault());
            LocalDate from = LocalDate.of(2026, 4, 1);
            RoutingCoverage coverage = registry.coverageOver(from, LocalDate.of(2026, 5, 31));

            assertThat(coverage.isComplete()).isFalse();
            InvariantResult result = coverage.asInvariantResult();
            assertThat(result.id()).isEqualTo(InvariantId.RT_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("thirty uncovered days in April 2026, before the baseline takes effect")
                .isEqualByComparingTo(BigDecimal.valueOf(coverage.uncoveredDays()));
            assertThat(coverage.uncoveredDays())
                .as("1 to 30 April inclusive, derived from the calendar and not from the code")
                .isEqualTo(30L);
        }
    }

    @Nested
    @DisplayName("RS-1: every fee code has a per-code default in force")
    class FeeRuleSetCompleteness {

        private FeeRuleSet ruleSet(FeeRuleKey... keys) {
            List<FeeRule> rules = new java.util.ArrayList<>();
            for (FeeRuleKey key : keys) {
                rules.add(new FeeRule(key, FeeClassification.INTEGRAL, "fixture"));
            }
            return new FeeRuleSet(
                version("FEE-2027.1", PolicyKind.FEE_RULE_SET, APRIL), List.copyOf(rules));
        }

        @Test
        @DisplayName("a per-code default for every code passes under RS-1")
        void completePasses() {
            InvariantResult result = ruleSet(
                new FeeRuleKey("PROCESSING_FEE", FeeRuleKey.ANY, FeeRuleKey.ANY, APRIL),
                new FeeRuleKey("DSA_COMMISSION", FeeRuleKey.ANY, FeeRuleKey.ANY, APRIL))
                .catchAllCoverage(JUNE);

            assertThat(result.id()).isEqualTo(InvariantId.RS_1);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("a code reachable only via a product carve-out fails, and the count is the deviation")
        void productOnlyCarveOutFails() {
            // The state this catches is real and is the programme's critical path: a code loaded
            // for the product somebody got round to and for no other. It classifies HOUSING_LOAN
            // and raises UNMAPPED_FEE_CODE on every other product — so the deviation is directly
            // the number of codes that will raise exceptions on unlisted products, which is the
            // figure an impact preview exists to carry.
            InvariantResult result = ruleSet(
                new FeeRuleKey("PROCESSING_FEE", FeeRuleKey.ANY, FeeRuleKey.ANY, APRIL),
                new FeeRuleKey("DSA_COMMISSION", "HOUSING_LOAN", FeeRuleKey.ANY, APRIL))
                .catchAllCoverage(JUNE);

            assertThat(result.id()).isEqualTo(InvariantId.RS_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("one code without a per-code default")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail()).contains("DSA_COMMISSION");
        }
    }

    @Nested
    @DisplayName("the four are distinct, and none borrows another's identifier")
    class Distinctness {

        @Test
        @DisplayName("each id is its own, and each states what it claims")
        void eachIdIsDistinct() {
            // The whole reason four constants were needed rather than one reused: conjunction
            // keeps only the first breach's deviation among results sharing an id, so any two
            // of these under one identifier would publish one figure and lose the other.
            List<InvariantId> added = List.of(
                InvariantId.PG_1, InvariantId.PV_1, InvariantId.RT_1, InvariantId.RS_1);
            assertThat(added).doesNotHaveDuplicates();
            assertThat(added).doesNotContain(InvariantId.DT_1);
            for (InvariantId id : added) {
                assertThat(id.statement())
                    .as("%s states its claim", id)
                    .isNotBlank();
            }
        }

        @Test
        @DisplayName("conjoining two of them is refused, which is why they are separate")
        void conjunctionRefusesMixedIds() {
            // InvariantResult.conjunction is same-id only. Asserted here because it is the
            // mechanism the whole four-constant decision rests on: if it silently merged
            // across ids, borrowing one would have been harmless and none of this was needed.
            InvariantResult routing = InvariantResult.fail(
                InvariantId.RT_1, "a gap", BigDecimal.valueOf(30));
            InvariantResult replay = InvariantResult.fail(
                InvariantId.DT_1, "figures differ", new BigDecimal("1234.56"));

            assertThat(InvariantResult.oneResultPerInvariant(List.of(routing, replay)))
                .as("kept apart, each with its own deviation intact")
                .hasSize(2);
        }
    }
}
