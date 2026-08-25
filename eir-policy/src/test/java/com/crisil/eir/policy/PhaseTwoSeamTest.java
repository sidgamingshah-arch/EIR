package com.crisil.eir.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleKey;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import com.crisil.eir.policy.routing.RoutingTableFormat;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import com.crisil.eir.policy.tier.TierAssignment;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentInput;
import com.crisil.eir.policy.tier.TierAssignmentResult;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seams between Phase 2's work units — the only tests here that no single unit could
 * have written.
 *
 * <p><b>Why this file exists.</b> Fourteen units were built in parallel, in isolated
 * worktrees, each green on its own module before it was merged. That proves less than it
 * looks like, and the roadmap says so from experience: Phase 1's integration "found four
 * defects a module-wide compile could not, because the four packages contained no
 * cross-package imports — the build was green on arrival only because nothing referenced
 * anything." Its stated lesson was to carry into Phase 2 that <em>a green build across
 * independently-written packages is evidence of nothing until something exercises the
 * seams.</em>
 *
 * <p>It held again. The merge compiled and every unit's own tests passed, and the schema's
 * two halves still could not be joined: V1 wrote surrogate keys as {@code UUID} and V2 as
 * {@code BIGINT}, so all seventeen of V2's foreign keys into V1's tables silently failed to
 * form. Nothing in either unit's tests could see it, because neither unit could see the
 * other's file. That defect was caught by executing the DDL; these tests are the same idea
 * applied to the Java.
 *
 * <p>Each nested class drives one of the three sentences that <a
 * href="../../../../../../../../docs/08-roadmap.md">08</a> sets as Phase 2's exit gate.
 * Where a unit's own tests assert that <em>it</em> behaves, these assert that the units
 * <em>compose</em> — that the type one produces is the type the next consumes, and that a
 * refusal raised in one package arrives intact in another.
 */
class PhaseTwoSeamTest {

    private static final LocalDate JUNE = LocalDate.of(2027, 6, 30);
    private static final LocalDate APRIL = LocalDate.of(2027, 4, 1);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 15);

    private static PolicyVersion version(String id, PolicyKind kind) {
        return new PolicyVersion(id, kind, "seam fixture", APRIL,
            "policy.author", "accounting.policy.owner", MARCH, PolicyVersionStatus.EFFECTIVE);
    }

    // ========================================== "a fee code cannot reach the engine unclassified"

    @Nested
    @DisplayName("exit gate 1: an unclassified fee code reaches the exception queue, not the engine")
    class FeeResolutionIntoTheQueue {

        private FeeClassificationResolver resolverWithOneMappedCode() {
            // One mapped code and nothing else, so an unmapped lookup is genuinely unmapped
            // rather than falling through to a catch-all that would make FR-202 unfalsifiable.
            FeeRuleSet ruleSet = new FeeRuleSet(
                version("FEE-2027.1", PolicyKind.FEE_RULE_SET),
                List.of(new FeeRule(
                    new FeeRuleKey("PROCESSING_FEE", FeeRuleKey.ANY, FeeRuleKey.ANY, APRIL),
                    FeeClassification.INTEGRAL,
                    "ACPIR 53: origination processing, integral to the yield")));
            return new FeeClassificationResolver(ruleSet);
        }

        @Test
        @DisplayName("a mapped code resolves, and the classification is the engine's own type")
        void aMappedCodeResolves() {
            // The seam that matters most quietly: unit 6 produces a
            // com.crisil.eir.domain.FeeClassification, which is the SAME enum
            // eir-calc's FeePosting consumes. If the resolver had introduced its own
            // classification type the two would need a translation table, and a
            // translation table between an engine and its own policy layer is where
            // classifications get lost.
            FeeClassificationResolution resolved = resolverWithOneMappedCode()
                .resolve("PROCESSING_FEE", "HOUSING_LOAN", "BANK_IN", JUNE);

            assertThat(resolved.isResolved()).isTrue();
            assertThat(resolved.requireClassification()).isEqualTo(FeeClassification.INTEGRAL);
            assertThat(resolved.entersCarryingAmount())
                .as("delegates to FeeClassification.entersCarryingAmount, true only for INTEGRAL")
                .isTrue();
            assertThat(resolved.exception())
                .as("a resolved fee raises nothing")
                .isNull();
        }

        @Test
        @DisplayName("an unmapped code carries unit 12's category and files as a hard stop")
        void anUnmappedCodeFilesIntoTheQueue() {
            // The FR-202 -> FR-905 path, across two packages that were written in parallel.
            // Unit 6 names the category; unit 12 owns the queue and decides what a category
            // does to a contract. Neither could assert this end.
            FeeClassificationResolution unmapped = resolverWithOneMappedCode()
                .resolve("MYSTERY_CHARGE", "HOUSING_LOAN", "BANK_IN", JUNE);

            assertThat(unmapped.isResolved()).isFalse();
            assertThat(unmapped.classification())
                .as("never defaults: both possible defaults are wrong in the direction nobody checks")
                .isNull();
            assertThat(unmapped.exception())
                .as("unit 6 raises the category unit 12 defines")
                .isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
            assertThat(unmapped.detail())
                .as("the offending key is named, so the fee master can be fixed")
                .contains("MYSTERY_CHARGE");

            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(ExceptionRecord.raise(
                "CTR-0001", "RUN-2027-06", unmapped.exception(), unmapped.detail(),
                unmapped.requested().toString()));

            assertThat(queue.isQuarantined("CTR-0001"))
                .as("UNMAPPED_FEE_CODE stops the contract, so the engine never sees it")
                .isTrue();
            assertThat(queue.blocksClose())
                .as("04 § 3: unresolved exceptions block the close unless explicitly accepted")
                .isTrue();
            assertThat(queue.countByCategory())
                .containsEntry(ExceptionCategory.UNMAPPED_FEE_CODE, 1);
        }

        @Test
        @DisplayName("a demoting category quarantines nothing, which is the asymmetry both units encode")
        void aDemotingCategoryDoesNotQuarantine() {
            // Eight of the ten categories stop the contract; STALE_EQUIVALENCE_TEST and
            // POOL_BACKTEST_BREACH instead move the population to a more expensive and
            // correct measurement basis (03 § 10.1, § 10.2). Unit 12's queue must honour
            // that distinction rather than treating every entry as a quarantine — asserted
            // here because the two halves of the rule live in two packages.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(ExceptionRecord.raise(
                "CTR-0002", "RUN-2027-06", ExceptionCategory.STALE_EQUIVALENCE_TEST,
                "Tier 3 WCDL population, test last performed 2026-01-31", "POP-WCDL"));

            assertThat(ExceptionCategory.STALE_EQUIVALENCE_TEST.stopsTheContract()).isFalse();
            assertThat(queue.isQuarantined("CTR-0002"))
                .as("demotion is a different measurement, not an absence of one")
                .isFalse();
            assertThat(queue.demotedContracts()).contains("CTR-0002");
            assertThat(queue.blocksClose())
                .as("it still blocks the close: a population that changed basis must be acknowledged")
                .isTrue();
        }
    }

    // ================================ "the routing table can be changed without a code deploy"

    @Nested
    @DisplayName("exit gate 2: a routing table parsed from text drives the registry")
    class LoaderIntoRegistry {

        @Test
        @DisplayName("emit then parse then register, with no Java mapping anywhere in the path")
        void aTextTableBecomesARoutedDecision() {
            // The whole point of ADR-0006, and before Phase 2 it was false: the only way to
            // obtain a RoutingTable was RoutingTable.ofSpecDefaults, eight literal
            // mapping.put calls in Java. Unit 5 wrote the format, unit 4 the registry, and
            // neither could test this chain.
            //
            // Round-tripped through TEXT deliberately. Handing the registry an in-memory
            // table would exercise the registry and prove nothing about the deploy claim.
            RoutingTable baseline = RoutingTable.currentDefault();
            String text = RoutingTableFormat.emit(baseline);
            assertThat(text)
                .as("the mapping is expressed as data a human can edit")
                .contains("ESG_LINKED")
                .contains("CATCH_UP");

            RoutingTable reparsed = RoutingTableFormat.parse(text, "seam-test");
            assertThat(reparsed)
                .as("round-trip is exact, or a config change means something different on reload")
                .isEqualTo(baseline);

            RoutingTableRegistry registry = RoutingTableRegistry.of(reparsed);
            assertThat(registry.inForceOn(JUNE)).isEqualTo(reparsed);

            // And the decision comes out of the parsed table, not out of code.
            assertThat(registry.route(RateDriver.ESG_LINKED, RateType.FLOATING, JUNE).mechanism())
                .as("an ESG ratchet catches up, per the table that was loaded from text")
                .isEqualTo(Mechanism.CATCH_UP);
        }

        @Test
        @DisplayName("the fixed-rate override survives a table loaded from text")
        void theRateTypeOverrideSurvivesTheRegistry() {
            // FR-507, and the one routing rule that is deliberately NOT data: a renegotiated
            // FIXED rate is a modification, not a reset, because the premise is the
            // instrument's own terms rather than any reading of B5.4.5. Unit 4's registry
            // chooses the table; it must not become a second place a mechanism is decided.
            // If a future config edit could turn this into a RESET, the engine would treat a
            // renegotiation as a repricing on the entire fixed-rate book.
            RoutingTableRegistry registry = RoutingTableRegistry.of(
                RoutingTableFormat.parse(RoutingTableFormat.emit(RoutingTable.currentDefault())));

            var decision = registry.route(
                RateDriver.CREDIT_RISK_MARKET, RateType.FIXED, JUNE);
            assertThat(decision.mechanism())
                .as("market movement on a FIXED instrument is a modification test, whatever the table says")
                .isEqualTo(Mechanism.MODIFICATION_TEST);
            assertThat(decision.overriddenByRateTypeCheck()).isTrue();

            assertThat(registry.route(RateDriver.CREDIT_RISK_MARKET, RateType.FLOATING, JUNE)
                .mechanism())
                .as("the same driver on a FLOATING instrument resets, from the table")
                .isEqualTo(Mechanism.RESET);
        }

        @Test
        @DisplayName("latest-wins across two versions, and both claim to have taken effect")
        void latestWinsAcrossVersions() {
            // The resolution rule I had to settle centrally mid-flight, because the brief I
            // gave units 3 and 4 said "reject overlapping effective ranges" and neither
            // RoutingTableVersion nor PolicyVersion carries an end date — so every pair of
            // versions overlaps by construction and the instruction was unimplementable.
            // The rule is latest effectiveFrom not after the date. Asserted here on two
            // versions that BOTH answer isEffectiveOn(JUNE) true, which is what makes
            // latest-wins a decision rather than an implementation detail.
            RoutingTable first = RoutingTable.currentDefault();
            RoutingTableVersion second = new RoutingTableVersion(
                "RT-2027.2", "IASB ED on B5.4.5", LocalDate.of(2027, 5, 1),
                "policy.author", "accounting.policy.owner", LocalDate.of(2027, 4, 20));
            RoutingTable later = first.reroute(RateDriver.ESG_LINKED, Mechanism.RESET, second);

            assertThat(first.version().isEffectiveOn(JUNE)).isTrue();
            assertThat(later.version().isEffectiveOn(JUNE))
                .as("both had taken effect by June; the predicate cannot pick between them")
                .isTrue();

            RoutingTableRegistry registry = RoutingTableRegistry.of(first, later);
            assertThat(registry.inForceOn(JUNE))
                .as("latest effectiveFrom wins")
                .isEqualTo(later);
            assertThat(registry.route(RateDriver.ESG_LINKED, RateType.FLOATING, JUNE).mechanism())
                .as("so the amended reading governs June, without a code deploy")
                .isEqualTo(Mechanism.RESET);
            assertThat(registry.route(RateDriver.ESG_LINKED, RateType.FLOATING,
                LocalDate.of(2027, 4, 30)).mechanism())
                .as("and April still routes under the version that was in force then")
                .isEqualTo(Mechanism.CATCH_UP);
        }
    }

    // ============================================= tier assignment into the Tier 3 gate

    @Nested
    @DisplayName("FR-107 into FR-411/412: an assigned tier is gated before it is used")
    class TierIntoTheGate {

        private TierAssignment assignment() {
            return TierAssignment.under(
                version("TIER-2027.1", PolicyKind.TIER_ASSIGNMENT), Money.inr("500000000"));
        }

        @Test
        @DisplayName("a short-tenor exposure is assigned Tier 3, and the gate refuses it for a zero coupon")
        void tierThreeProposedThenRefused() {
            // The division of labour I told units 10 and 11 to keep: unit 10 assigns from
            // contract attributes and records why; unit 11 gates the assignment and may
            // refuse it. So unit 10 legitimately PROPOSES Tier 3 for something unit 11
            // then refuses, and that hand-off only exists across the seam.
            TierAssignmentResult proposed = assignment().assign(new TierAssignmentInput(
                "TBILL-0001", TierAssignmentSegment.WHOLESALE, 3,
                Money.inr("10000000"), Set.of()));

            assertThat(proposed.tier())
                .as("03 § 10 puts a three-month bill in the Tier 3 population")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(proposed.basis())
                .as("FR-107's second half: the basis is recorded, not just the tier")
                .isNotBlank();
        }

        @Test
        @DisplayName("a hedged six-month exposure is Tier 1, so the override beats the tenor rule")
        void theOverrideBeatsTheTenorRule() {
            // The ordering defect worth a seam test because getting it backwards is silent:
            // a straight-line approximation applied to a hedged instrument. Six months is
            // squarely inside the Tier 3 tenor band, and the hedge relationship must win.
            TierAssignmentResult hedged = assignment().assign(new TierAssignmentInput(
                "WCDL-0002", TierAssignmentSegment.WHOLESALE, 6,
                Money.inr("10000000"), Set.of(TierAssignmentFeature.HEDGE_RELATIONSHIP)));

            assertThat(hedged.tier())
                .as("a hedged exposure is measured per instrument whatever its tenor")
                .isEqualTo(MaterialityTier.TIER_1);
            assertThat(hedged.tier().requiresTightenedTolerance())
                .as("and Tier 1 tightens the solver tolerance, which is what the tier is FOR")
                .isTrue();
        }

        @Test
        @DisplayName("with no equivalence test on file, an empty gate does not silently permit Tier 3")
        void anEmptyGateDoesNotPermit() {
            // FR-411: the shortcut is "permitted only against a current equivalence test".
            // An empty register is the state of a bank that has not done the work, and the
            // gate must not read absence as permission — the Cambodia failure mode the
            // roadmap risk register names.
            var outcome = com.crisil.eir.policy.tier.EquivalenceTestGate.empty()
                .evaluate(com.crisil.eir.policy.tier.EquivalenceTestSubject.couponBearingAtPar(
                    "POP-WCDL", MaterialityTier.TIER_3, 6,
                    Money.inr("10000000"), Money.inr("450000")), JUNE);

            assertThat(outcome.effectiveTier())
                .as("no test on file demotes to Tier 2 rather than allowing the approximation")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.exception())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
        }
    }
}
