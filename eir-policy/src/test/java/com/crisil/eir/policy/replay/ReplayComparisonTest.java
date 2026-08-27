package com.crisil.eir.policy.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Deterministic replay of a closed period, reported as DT-1 (FR-903, control C-12).
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>None of them from running this code. The figures are reference case 1 at month 12, as quoted
 * in {@code eir-calc InvariantChecks.unamortisedFeeIsLegDifference}: contractual carrying amount
 * 529,815.61, EIR carrying amount 528,407.32, unamortised fee 1,408.29. Hand check:
 * {@code 529815.61 - 528407.32 = 1408.29}. They are fixtures rather than computed quantities here —
 * this package compares published figures, it does not produce them — and using the real ones keeps
 * the scales realistic, which is the whole subject of the file.
 *
 * <p>Every expected deviation is a count derived by reading the fixture: the number of figure keys
 * whose rendered decimal differs plus the number of policy kinds that diverge. Each test states the
 * arithmetic.
 *
 * <h2>The two things this file is really about</h2>
 *
 * <p><b>{@code Money.equals} is not the comparison DT-1 needs.</b> It compares by numeric value and
 * ignores scale, by design and correctly for accounting arithmetic. So {@code 1.0} equals
 * {@code 1.00} and a replay that published one where the close published the other reads as a
 * match. {@link Trap} asserts both halves: that {@code Money.equals} does say "same", and that this
 * comparison says "different anyway".
 *
 * <p><b>Figures matching is not enough.</b> {@link UnderThePolicyThenInForce} is the more valuable
 * half. A replay that reproduces every figure while resolving a different policy version has proved
 * nothing — it got the right number from the wrong rule — and nothing else in the engine can see
 * it, because every downstream check agrees.
 */
class ReplayComparisonTest {

    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 2, 15);

    /** Reference case 1 at month 12, as quoted in eir-calc InvariantChecks. */
    private static final String CONTRACTUAL_GCA = "C-1/CONTRACTUAL_GCA";
    private static final String EIR_GCA = "C-1/EIR_GCA";
    private static final String UNAMORTISED_FEE = "C-1/UNAMORTISED_FEE";

    /** April 2027 — the first period under ACPIR 20 — closed on 5 May 2027. */
    private static final ClosedPeriod APRIL_2027 = ClosedPeriod.month(
        YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "financial.controller");

    private static PolicyVersion version(
        String id, PolicyKind kind, LocalDate from, PolicyVersionStatus status) {
        return new PolicyVersion(id, kind, "replay fixture", from,
            "policy.author", "accounting.policy.owner", APPROVED_ON, status);
    }

    /**
     * The timeline as at a replay run in, say, October 2027: the April rule set has been
     * superseded by a July one, and the routing table has not changed.
     *
     * <p>This shape is what makes the policy leg of DT-1 testable at all. A registry with one
     * version of each kind cannot distinguish a replay that resolved at the period end from one
     * that resolved at {@code LocalDate.now()}, because both answers are the same version — which
     * is exactly why a harness with that defect passes its own tests until the first supersession.
     */
    private static PolicyVersionRegistry supersededTimeline() {
        return PolicyVersionRegistry.of(List.of(
            version("FEE-2027.1", PolicyKind.FEE_RULE_SET,
                LocalDate.of(2027, 4, 1), PolicyVersionStatus.SUPERSEDED),
            version("FEE-2027.2", PolicyKind.FEE_RULE_SET,
                LocalDate.of(2027, 7, 1), PolicyVersionStatus.EFFECTIVE),
            version("ROUTE-2027.1", PolicyKind.ROUTING_TABLE,
                LocalDate.of(2027, 4, 1), PolicyVersionStatus.EFFECTIVE)));
    }

    /** What the April close cited, and what a correct replay of it must cite. */
    private static Map<PolicyKind, String> aprilStamps() {
        Map<PolicyKind, String> stamps = new LinkedHashMap<>();
        stamps.put(PolicyKind.FEE_RULE_SET, "FEE-2027.1");
        stamps.put(PolicyKind.ROUTING_TABLE, "ROUTE-2027.1");
        return stamps;
    }

    private static Map<String, Money> publishedFigures() {
        Map<String, Money> figures = new LinkedHashMap<>();
        figures.put(CONTRACTUAL_GCA, Money.inr("529815.61"));
        figures.put(EIR_GCA, Money.inr("528407.32"));
        figures.put(UNAMORTISED_FEE, Money.inr("1408.29"));
        return figures;
    }

    private static ReplayRun publishedRun() {
        return ReplayRun.published("RUN-202704-ORIGINAL", 202704, publishedFigures(), aprilStamps());
    }

    private static ReplayRun replayRun(Map<String, Money> figures, Map<PolicyKind, String> stamps) {
        return ReplayRun.replayOf(
            "RUN-202704-REPLAY-N1", "RUN-202704-ORIGINAL", 202704, figures, stamps);
    }

    private static ReplayComparison compare(
        Map<String, Money> replayedFigures, Map<PolicyKind, String> replayedStamps) {
        return ReplayComparison.of(
            APRIL_2027, publishedRun(), replayRun(replayedFigures, replayedStamps),
            supersededTimeline());
    }

    @Nested
    @DisplayName("bit-identical is stricter than numerically equal")
    class Trap {

        @Test
        @DisplayName("a faithful replay passes DT-1 with a deviation of zero")
        void faithfulReplayPasses() {
            // Same keys, same rendered decimals, same policy stamps. Zero discrepancies, so the
            // deviation is zero and the detail states both denominators.
            ReplayComparison comparison = compare(publishedFigures(), aprilStamps());

            InvariantResult result = comparison.dtOne();
            assertThat(result.id()).isEqualTo(InvariantId.DT_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(comparison.figuresCompared())
                .as("three figures, and the detail says so — a pass over nothing reads the same")
                .isEqualTo(3);
            assertThat(comparison.policyKindsCompared()).isEqualTo(2);
            assertThat(result.detail()).contains("3 figures and 2 policy kinds compared");
        }

        @Test
        @DisplayName("1408.290 where the close published 1408.29 is a DT-1 breach")
        void scaleOnlyDifferenceFails() {
            // THE trap. 1408.290 and 1408.29 are the same amount of money and a different
            // published artefact. Deviation 1: one figure key differs, the other two are
            // character-identical.
            Map<String, Money> drifted = publishedFigures();
            drifted.put(UNAMORTISED_FEE, Money.inr("1408.290"));

            // First, the fact that makes this control necessary rather than pedantic.
            assertThat(Money.inr("1408.290").equals(Money.inr("1408.29")))
                .as("Money.equals compares by value and ignores scale — deliberately, and this is"
                    + " why a comparison built on it would report a match")
                .isTrue();
            assertThat(Money.inr("1408.290").amount().compareTo(new BigDecimal("1408.29")))
                .as("numerically identical: zero rupees of difference")
                .isZero();

            ReplayComparison comparison = compare(drifted, aprilStamps());
            InvariantResult result = comparison.dtOne();

            assertThat(result.satisfied())
                .as("scale-sensitive comparison says the published artefact differs")
                .isFalse();
            assertThat(result.deviation())
                .as("one figure of three differs; the other two render identically")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.SCALE_ONLY))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.subject()).isEqualTo(UNAMORTISED_FEE);
                    assertThat(finding.expected()).isEqualTo("INR 1408.29");
                    assertThat(finding.found()).isEqualTo("INR 1408.290");
                });
        }

        @Test
        @DisplayName("a scale drift the other way — 1408.3 for 1408.29 — is a value difference")
        void fewerDecimalsThatChangeTheValueIsAValueDifference() {
            // Worth separating from the case above, because "the scale moved" and "the value
            // moved" are not the same finding and a rounding defect produces both shapes. 1408.3
            // is not 1408.29: it is a paise more, and compareTo says so.
            Map<String, Money> rounded = publishedFigures();
            rounded.put(UNAMORTISED_FEE, Money.inr("1408.3"));

            ReplayComparison comparison = compare(rounded, aprilStamps());

            assertThat(comparison.discrepanciesOf(DiscrepancyKind.VALUE_DIFFERS))
                .as("0.01 of difference, so the value moved and not merely its presentation")
                .hasSize(1);
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.SCALE_ONLY)).isEmpty();
            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("three figures differing count as three, not as one")
        void severalDifferencesAreCounted() {
            // Deviation is a count and the count is the actionable figure: three figures to look
            // at, each with its own remedy. A single money aggregate would also have to survive
            // two differences in opposite directions, which InvariantResult.conjunction warns
            // must never net to a pass.
            Map<String, Money> broken = new LinkedHashMap<>();
            broken.put(CONTRACTUAL_GCA, Money.inr("529815.610"));   // scale only
            broken.put(EIR_GCA, Money.inr("528407.31"));            // one paise low
            broken.put(UNAMORTISED_FEE, Money.inr("1408.290"));     // scale only

            ReplayComparison comparison = compare(broken, aprilStamps());

            assertThat(comparison.dtOne().deviation())
                .as("three of three figures differ")
                .isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(comparison.figureDiscrepancyCount()).isEqualTo(3);
            assertThat(comparison.policyDiscrepancyCount()).isZero();
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.SCALE_ONLY)).hasSize(2);
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.VALUE_DIFFERS)).hasSize(1);
        }

        @Test
        @DisplayName("a figure the replay did not produce, and one the close never published")
        void missingAndExtraFiguresAreBothCounted() {
            // The union is compared, not the published set. Iterating the published figures and
            // looking each up would never see the second finding — which is the shape a replay
            // takes when it runs today's eligibility rule over last year's population.
            Map<String, Money> shifted = new LinkedHashMap<>();
            shifted.put(CONTRACTUAL_GCA, Money.inr("529815.61"));
            shifted.put(EIR_GCA, Money.inr("528407.32"));
            shifted.put("C-2/EIR_GCA", Money.inr("100000.00"));

            ReplayComparison comparison = compare(shifted, aprilStamps());

            assertThat(comparison.dtOne().deviation())
                .as("one published figure not reproduced, one produced that was never published")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.MISSING_FROM_REPLAY))
                .singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo(UNAMORTISED_FEE));
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.ABSENT_FROM_PUBLICATION))
                .singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("C-2/EIR_GCA"));
        }

        @Test
        @DisplayName("a currency difference is reported, not thrown")
        void currencyDifferenceIsReported() {
            // InvariantResult.ofMoney throws on a currency mismatch because it has to subtract to
            // produce a deviation. Nothing is subtracted here — the deviation is a count — so the
            // mismatch is a reportable finding, and reporting beats aborting the nightly sweep on
            // the first multi-currency book.
            Map<String, Money> foreign = publishedFigures();
            foreign.put(UNAMORTISED_FEE,
                Money.of("1408.29", java.util.Currency.getInstance("USD")));

            ReplayComparison comparison = compare(foreign, aprilStamps());

            assertThat(comparison.discrepanciesOf(DiscrepancyKind.CURRENCY_DIFFERS))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.expected()).isEqualTo("INR 1408.29");
                    assertThat(finding.found()).isEqualTo("USD 1408.29");
                });
            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Nested
    @DisplayName("under the policy then in force — the half that is not optional")
    class UnderThePolicyThenInForce {

        @Test
        @DisplayName("every figure bit-identical and a different policy version is still a FAILURE")
        void bitIdenticalFiguresUnderADifferentPolicyVersionFails() {
            // The most valuable test in this unit. The replay reproduced all three figures
            // character for character and resolved FEE-2027.2 — the version effective from July —
            // for a period that ended in April. That is reproducing the right number from the
            // wrong rule: it says nothing about the engine being deterministic, it will not hold
            // next period, and by then the divergence will be blamed on whatever changed most
            // recently.
            //
            // Concretely this is a harness that resolves policy at LocalDate.now(). It passes
            // every test it has until the first supersession.
            Map<PolicyKind, String> todaysPolicy = aprilStamps();
            todaysPolicy.put(PolicyKind.FEE_RULE_SET, "FEE-2027.2");

            ReplayComparison comparison = compare(publishedFigures(), todaysPolicy);
            InvariantResult result = comparison.dtOne();

            assertThat(comparison.figureDiscrepancyCount())
                .as("nothing at all wrong with the figures")
                .isZero();
            assertThat(result.satisfied())
                .as("and DT-1 fails anyway, because the second half of FR-903 broke")
                .isFalse();
            assertThat(result.deviation())
                .as("one policy kind diverged; the remedy is per kind")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.POLICY_VERSION_DIFFERS))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.subject()).isEqualTo(PolicyKind.FEE_RULE_SET.name());
                    assertThat(finding.expected()).isEqualTo("FEE-2027.1");
                    assertThat(finding.found()).isEqualTo("FEE-2027.2");
                });
        }

        @Test
        @DisplayName("a fail on a version divergence still carries a non-zero deviation")
        void aPolicyOnlyBreachIsNotReportedAsZero() {
            // Why the policy findings share the figure count rather than sitting outside it. A
            // DT-1 failure whose deviation was zero would be dropped by any caller ranking
            // breaches by size or filtering on signum, and this is the breach that must not be
            // dropped: it is the one nothing else in the engine can see.
            Map<PolicyKind, String> todaysPolicy = aprilStamps();
            todaysPolicy.put(PolicyKind.FEE_RULE_SET, "FEE-2027.2");

            InvariantResult result = compare(publishedFigures(), todaysPolicy).dtOne();

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation().signum())
                .as("a breach that a signum filter would keep")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the expected snapshot comes from the period END, not the close date")
        void policyResolvesAtPeriodEndNotAtTheCloseDate() {
            // The off-by-one ClosedPeriod.policyResolutionDate exists for. March 2027 closes on
            // 5 April 2027, and 1 April is the single most likely effective date in the Indian
            // fiscal calendar. A harness that reads "the policy in force when the period closed"
            // literally picks up the April rule set and applies a rule written for the new fiscal
            // year to a period that ended in the old one.
            //
            // Derived from the timeline by hand, not from the code: FEE-2026.1 is effective
            // 2026-04-01 and FEE-2027.1 from 2027-04-01, so latest-wins at 2027-03-31 is
            // FEE-2026.1 and at 2027-04-05 is FEE-2027.1.
            PolicyVersionRegistry timeline = PolicyVersionRegistry.of(List.of(
                version("FEE-2026.1", PolicyKind.FEE_RULE_SET,
                    LocalDate.of(2026, 4, 1), PolicyVersionStatus.SUPERSEDED),
                version("FEE-2027.1", PolicyKind.FEE_RULE_SET,
                    LocalDate.of(2027, 4, 1), PolicyVersionStatus.EFFECTIVE)));
            ClosedPeriod march = ClosedPeriod.month(
                YearMonth.of(2027, 3), LocalDate.of(2027, 4, 5), "financial.controller");

            assertThat(march.policyResolutionDate()).isEqualTo(LocalDate.of(2027, 3, 31));
            assertThat(ReplayComparison.policyThenInForce(timeline, march))
                .as("the rule that governed the facts, not the one live on close day")
                .containsExactly(Map.entry(PolicyKind.FEE_RULE_SET, "FEE-2026.1"));
            assertThat(timeline.requireInForceOn(
                PolicyKind.FEE_RULE_SET, march.closedOn()).id())
                .as("what a harness resolving at the close date would have picked up")
                .isEqualTo("FEE-2027.1");

            Map<String, Money> figures = Map.of(EIR_GCA, Money.inr("528407.32"));
            ReplayComparison comparison = ReplayComparison.of(
                march,
                ReplayRun.published("RUN-202703-ORIGINAL", 202703, figures,
                    Map.of(PolicyKind.FEE_RULE_SET, "FEE-2026.1")),
                ReplayRun.replayOf("RUN-202703-REPLAY", "RUN-202703-ORIGINAL", 202703, figures,
                    Map.of(PolicyKind.FEE_RULE_SET, "FEE-2027.1")),
                timeline);

            assertThat(comparison.dtOne().satisfied()).isFalse();
            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("both runs citing a version that never governed the period is a breach too")
        void bothRunsCitingAVersionNotInForceFails() {
            // A different claim from the one above, and the reason the timeline is consulted at
            // all rather than only the two runs' stamps being compared. Here the runs agree, so a
            // stamp-against-stamp check passes — and the version they agree on took effect in July
            // for a period that ended in April. The period is not replayable under the policy then
            // in force however well the figures reproduce.
            ReplayRun published = ReplayRun.published(
                "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.FEE_RULE_SET, "FEE-2027.2"));
            ReplayRun replayed = ReplayRun.replayOf(
                "RUN-202704-REPLAY-N1", "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.FEE_RULE_SET, "FEE-2027.2"));

            ReplayComparison comparison =
                ReplayComparison.of(APRIL_2027, published, replayed, supersededTimeline());

            assertThat(comparison.figureDiscrepancyCount()).isZero();
            assertThat(comparison.discrepanciesOf(
                DiscrepancyKind.POLICY_NOT_IN_FORCE_AT_PERIOD_END))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.expected())
                        .as("what the timeline says governed April")
                        .isEqualTo("FEE-2027.1");
                    assertThat(finding.found()).isEqualTo("FEE-2027.2");
                });
            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a version the registry no longer holds fails rather than resolving to nothing")
        void aKindWithNoVersionInForceFails() {
            // The precondition PolicyVersionRegistry.policyResolvableOn publishes under PV-1, seen
            // from the replay side: the registry holds no BEHAVIOURAL_CURVE at all, so the version
            // both runs cite cannot be the one in force, because none is. DT-1 has to fail on it —
            // it is the control that was asked to run — and the expected side reads "(absent)"
            // rather than inventing an id.
            ReplayRun published = ReplayRun.published(
                "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.BEHAVIOURAL_CURVE, "CURVE-2027.1"));
            ReplayRun replayed = ReplayRun.replayOf(
                "RUN-202704-REPLAY-N1", "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.BEHAVIOURAL_CURVE, "CURVE-2027.1"));

            ReplayComparison comparison =
                ReplayComparison.of(APRIL_2027, published, replayed, supersededTimeline());

            assertThat(comparison.dtOne().satisfied()).isFalse();
            assertThat(comparison.discrepanciesOf(
                DiscrepancyKind.POLICY_NOT_IN_FORCE_AT_PERIOD_END))
                .singleElement()
                .satisfies(finding ->
                    assertThat(finding.expected()).isEqualTo(ReplayDiscrepancy.ABSENT));
        }

        @Test
        @DisplayName("a kind consulted on only one side is counted, in the right direction")
        void aKindConsultedOnOneSideOnly() {
            Map<PolicyKind, String> replayStamps = aprilStamps();
            replayStamps.remove(PolicyKind.ROUTING_TABLE);
            replayStamps.put(PolicyKind.TIER_ASSIGNMENT, "TIER-2027.1");

            ReplayComparison comparison = compare(publishedFigures(), replayStamps);

            assertThat(comparison.dtOne().deviation())
                .as("routing consulted at close and not on replay; tier the other way round")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(comparison.discrepanciesOf(
                DiscrepancyKind.POLICY_VERSION_MISSING_FROM_REPLAY))
                .singleElement()
                .satisfies(finding ->
                    assertThat(finding.subject()).isEqualTo(PolicyKind.ROUTING_TABLE.name()));
            assertThat(comparison.discrepanciesOf(
                DiscrepancyKind.POLICY_VERSION_ABSENT_FROM_PUBLICATION))
                .singleElement()
                .satisfies(finding ->
                    assertThat(finding.subject()).isEqualTo(PolicyKind.TIER_ASSIGNMENT.name()));
        }

        @Test
        @DisplayName("one kind wrong in two ways is one finding, so the count stays a count")
        void atMostOneFindingPerKind() {
            // The published run cites FEE-2027.2, which never governed April, and the replay cites
            // FEE-2027.1, which did. Both a divergence and a not-in-force condition are true of
            // this kind. It counts once: the deviation is the number of kinds needing a remedy,
            // and one kind resolved wrongly is one remedy however many ways it is wrong. Counting
            // twice would make the deviation a function of how many checks happen to be written.
            ReplayRun published = ReplayRun.published(
                "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.FEE_RULE_SET, "FEE-2027.2"));
            ReplayRun replayed = ReplayRun.replayOf(
                "RUN-202704-REPLAY-N1", "RUN-202704-ORIGINAL", 202704, publishedFigures(),
                Map.of(PolicyKind.FEE_RULE_SET, "FEE-2027.1"));

            ReplayComparison comparison =
                ReplayComparison.of(APRIL_2027, published, replayed, supersededTimeline());

            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(comparison.discrepanciesOf(DiscrepancyKind.POLICY_VERSION_DIFFERS))
                .hasSize(1);
            assertThat(comparison.discrepanciesOf(
                DiscrepancyKind.POLICY_NOT_IN_FORCE_AT_PERIOD_END)).isEmpty();
        }

        @Test
        @DisplayName("figure and policy discrepancies add into one deviation and stay separable")
        void figureAndPolicyCountsAddAndRemainReadable() {
            // Two figures drifted and one kind diverged, so the deviation is three. The split is
            // what a reader needs on top of it, because "the numbers moved" and "the rule moved"
            // send the investigation to different places.
            Map<String, Money> drifted = publishedFigures();
            drifted.put(CONTRACTUAL_GCA, Money.inr("529815.610"));
            drifted.put(EIR_GCA, Money.inr("528407.320"));
            Map<PolicyKind, String> stamps = aprilStamps();
            stamps.put(PolicyKind.FEE_RULE_SET, "FEE-2027.2");

            ReplayComparison comparison = compare(drifted, stamps);

            assertThat(comparison.figureDiscrepancyCount()).isEqualTo(2);
            assertThat(comparison.policyDiscrepancyCount()).isEqualTo(1);
            assertThat(comparison.dtOne().deviation()).isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(comparison.dtOne().detail())
                .contains("2 figure and 1 policy discrepancies");
        }
    }

    @Nested
    @DisplayName("one result, published under DT-1 and nothing else")
    class OneResult {

        @Test
        @DisplayName("a comparison publishes exactly one DT-1 result, never one per figure")
        void exactlyOneResult() {
            // InvariantResult.conjunction keeps only the FIRST breach's deviation among results
            // sharing an id. A comparison publishing one result per differing figure would
            // therefore report one difference and silently drop the rest — which the id's own
            // javadoc calls worse than reporting none, because it looks as though it has been
            // read.
            Map<String, Money> broken = new LinkedHashMap<>();
            broken.put(CONTRACTUAL_GCA, Money.inr("529815.610"));
            broken.put(EIR_GCA, Money.inr("528407.320"));
            broken.put(UNAMORTISED_FEE, Money.inr("1408.290"));

            InvariantResult only = compare(broken, aprilStamps()).dtOne();
            List<InvariantResult> published =
                InvariantResult.oneResultPerInvariant(List.of(only));

            assertThat(published).hasSize(1);
            assertThat(published.get(0).deviation())
                .as("all three survive into the one deviation")
                .isEqualByComparingTo(BigDecimal.valueOf(3));
        }

        @Test
        @DisplayName("nothing is published under PV-1, whose deviation counts something else")
        void nothingIsPublishedUnderPvOne() {
            // PolicyVersionRegistry.policyResolvableOn warns explicitly against conjoining its
            // PV-1 results with a bit-identical-replay result: conjunction keeps the first
            // breach's deviation among results sharing an id, and the two count different things.
            // This package publishes DT-1 alone, so the two never meet by accident.
            InvariantResult replay = compare(publishedFigures(), aprilStamps()).dtOne();
            InvariantResult resolvable = supersededTimeline()
                .policyResolvableOn(PolicyKind.FEE_RULE_SET, APRIL_2027.policyResolutionDate());

            assertThat(replay.id()).isEqualTo(InvariantId.DT_1);
            assertThat(resolvable.id()).isEqualTo(InvariantId.PV_1);
            assertThat(InvariantResult.oneResultPerInvariant(List.of(replay, resolvable)))
                .as("two ids, two deviations, neither swallowed")
                .hasSize(2);
        }

        @Test
        @DisplayName("a comparison of two empty runs is visible as vacuous rather than as a pass")
        void anEmptyComparisonIsVisiblyVacuous() {
            // A pass over nothing reads exactly like a pass over ten thousand figures unless the
            // denominator is on the line. Not refused at construction — a period with no
            // contracts is a real state — but a caller assembling C-12 evidence has to be able to
            // tell the difference, so both denominators are always in the detail.
            ReplayComparison comparison = ReplayComparison.of(
                APRIL_2027,
                ReplayRun.published("RUN-EMPTY", 202704, Map.of(), Map.of()),
                ReplayRun.replayOf("RUN-EMPTY-REPLAY", "RUN-EMPTY", 202704, Map.of(), Map.of()),
                supersededTimeline());

            assertThat(comparison.dtOne().satisfied()).isTrue();
            assertThat(comparison.isVacuous())
                .as("passed, and compared nothing — a control that did not run")
                .isTrue();
            assertThat(comparison.dtOne().detail())
                .contains("0 figures and 0 policy kinds compared");
        }

        @Test
        @DisplayName("the finding list reads the same whatever order the figures arrived in")
        void findingOrderIsStable() {
            // A determinism control whose own report reorders between two replays of one period
            // shows a reader differences that are not there. Map.copyOf gives no iteration-order
            // guarantee, so the comparison sorts.
            Map<String, Money> forwards = new LinkedHashMap<>();
            forwards.put(CONTRACTUAL_GCA, Money.inr("529815.610"));
            forwards.put(EIR_GCA, Money.inr("528407.320"));
            forwards.put(UNAMORTISED_FEE, Money.inr("1408.290"));

            Map<String, Money> backwards = new LinkedHashMap<>();
            backwards.put(UNAMORTISED_FEE, Money.inr("1408.290"));
            backwards.put(EIR_GCA, Money.inr("528407.320"));
            backwards.put(CONTRACTUAL_GCA, Money.inr("529815.610"));

            assertThat(compare(forwards, aprilStamps()).describe())
                .isEqualTo(compare(backwards, aprilStamps()).describe());
            assertThat(compare(forwards, aprilStamps()).discrepancies())
                .extracting(ReplayDiscrepancy::subject)
                .as("sorted by key, so the workpaper is comparable night to night")
                .containsExactly(CONTRACTUAL_GCA, EIR_GCA, UNAMORTISED_FEE);
        }
    }

    @Nested
    @DisplayName("harness defects are refused, not reported as findings")
    class Refusals {

        @Test
        @DisplayName("comparing a run against itself is refused, because it cannot fail")
        void selfComparisonRefused() {
            // The signature defect this codebase has found four times wearing invariant ids. Every
            // figure is bit-identical to itself and every version id equal to itself, so the
            // comparison passes unconditionally — which reads as DT-1 coverage and is not.
            ReplayRun run = publishedRun();
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    ReplayComparison.of(APRIL_2027, run, run, supersededTimeline()))
                .withMessageContaining("cannot fail");
        }

        @Test
        @DisplayName("a run naming itself as the run it replays is refused at construction")
        void selfReplayRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplayRun.replayOf(
                    "RUN-1", "RUN-1", 202704, publishedFigures(), aprilStamps()))
                .withMessageContaining("not a replay of itself");
        }

        @Test
        @DisplayName("two runs over different periods are refused")
        void periodMismatchRefused() {
            ReplayRun mayReplay = ReplayRun.replayOf(
                "RUN-202705-REPLAY", "RUN-202704-ORIGINAL", 202705,
                publishedFigures(), aprilStamps());
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplayComparison.of(
                    APRIL_2027, publishedRun(), mayReplay, supersededTimeline()))
                .withMessageContaining("period mismatch");
        }

        @Test
        @DisplayName("a reference side that is itself a replay is refused")
        void replayAsReferenceRefused() {
            // DT-1's reference is the artefact the close published. Two replays agreeing with
            // each other proves they agree with each other.
            ReplayRun firstReplay = ReplayRun.replayOf(
                "RUN-N1", "RUN-202704-ORIGINAL", 202704, publishedFigures(), aprilStamps());
            ReplayRun secondReplay = ReplayRun.replayOf(
                "RUN-N2", "RUN-202704-ORIGINAL", 202704, publishedFigures(), aprilStamps());

            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplayComparison.of(
                    APRIL_2027, firstReplay, secondReplay, supersededTimeline()))
                .withMessageContaining("is itself a replay");
        }

        @Test
        @DisplayName("a blank policy version stamp is refused, because absent already means a thing")
        void blankStampRefused() {
            // An absent stamp means "this run did not consult this kind" and is a legitimate
            // answer the comparison reads. A blank string is a stamp somebody failed to write, and
            // letting the two collapse would give "which kinds did this run consult" two answers.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplayRun.published(
                    "RUN-1", 202704, publishedFigures(),
                    Map.of(PolicyKind.FEE_RULE_SET, "   ")))
                .withMessageContaining("blank version id");
        }

        @Test
        @DisplayName("a period whose id does not encode its own start date is refused")
        void periodIdMustMatchItsDates() {
            // ck_accounting_period_id_matches_dates. The YYYYMM encoding is only useful if it
            // cannot drift from the dates it encodes, which is the schema's own reason for the
            // CHECK.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClosedPeriod(
                    202705, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 30),
                    LocalDate.of(2027, 5, 5), "financial.controller"))
                .withMessageContaining("does not encode its own start date");
        }

        @Test
        @DisplayName("a period closed before it ended is refused")
        void closedBeforeItEndedRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ClosedPeriod.month(
                    YearMonth.of(2027, 4), LocalDate.of(2027, 4, 20), "financial.controller"))
                .withMessageContaining("before it ended");
        }

        @Test
        @DisplayName("a period with no attesting closer is refused (FR-902)")
        void unattestedCloseRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ClosedPeriod.month(
                    YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "  "))
                .withMessageContaining("names no closer");
        }
    }

    @Nested
    @DisplayName("the two comparisons, stated directly")
    class ComparisonPrimitives {

        @Test
        @DisplayName("bitIdentical is scale-sensitive where numericallyEqual is not")
        void theTwoComparisonsPartOnScale() {
            Money coarse = Money.inr("1.0");
            Money fine = Money.inr("1.00");

            assertThat(ReplayFigure.numericallyEqual(coarse, fine))
                .as("VALUE question, asked with compareTo — the same amount of money")
                .isTrue();
            assertThat(ReplayFigure.bitIdentical(coarse, fine))
                .as("IDENTITY question — \"1.0\" and \"1.00\" are different published figures")
                .isFalse();
            assertThat(coarse.equals(fine))
                .as("and Money.equals agrees with the value question, which is why it is not used")
                .isTrue();
        }

        @Test
        @DisplayName("bitIdentical compares the rendered decimal, so 1E+2 and 100 are one figure")
        void identityIsTheRenderedArtefact() {
            // The one shape where toPlainString and BigDecimal.equals disagree, stated so the
            // choice is on the record rather than accidental. Scale -2 against scale 0; both
            // render "100", so both published the same artefact. BigDecimal.equals would raise a
            // DT-1 breach on a pair no reader could distinguish, and DT-1 blocks a period close.
            Money scientific = Money.of(new BigDecimal("1E+2"), Money.INR);
            Money plain = Money.inr("100");

            assertThat(scientific.amount().scale()).isEqualTo(-2);
            assertThat(plain.amount().scale()).isZero();
            assertThat(scientific.amount().equals(plain.amount()))
                .as("BigDecimal.equals separates them on scale")
                .isFalse();
            assertThat(scientific.amount().toPlainString()).isEqualTo("100");
            assertThat(ReplayFigure.bitIdentical(scientific, plain))
                .as("same characters published, so the same figure for DT-1")
                .isTrue();
        }

        @Test
        @DisplayName("a figure carries the scale it was published at, and renders at it")
        void aFigureCarriesItsPublishedScale() {
            ReplayFigure fee = ReplayFigure.of(UNAMORTISED_FEE, Money.inr("1408.290"));

            assertThat(fee.publishedScale())
                .as("three decimals, which is the quantity a scale drift moves")
                .isEqualTo(3);
            assertThat(fee.rendered()).isEqualTo("INR 1408.290");
            assertThat(APRIL_2027.covers(APRIL_2027.policyResolutionDate()))
                .as("the resolution date is inside the period it resolves policy for")
                .isTrue();
            assertThat(APRIL_2027.covers(APRIL_2027.closedOn()))
                .as("and the close date is not, which is the off-by-one in one line")
                .isFalse();
        }

        @Test
        @DisplayName("one key naming two figures is refused when a figure map is assembled")
        void duplicateFigureKeyRefused() {
            // A key that names two figures makes the comparison depend on which was found first,
            // which is the "whichever we found first is not an accounting answer" argument
            // PolicyVersionRegistry makes about two versions sharing an effective date.
            assertThat(ReplayRun.figureMap(List.of(
                ReplayFigure.of(CONTRACTUAL_GCA, Money.inr("529815.61")),
                ReplayFigure.of(EIR_GCA, Money.inr("528407.32")))))
                .hasSize(2);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ReplayRun.figureMap(List.of(
                    ReplayFigure.of(EIR_GCA, Money.inr("528407.32")),
                    ReplayFigure.of(EIR_GCA, Money.inr("528407.33")))))
                .withMessageContaining("appears twice");
        }

        @Test
        @DisplayName("the two agree on every non-negative scale, which is every persisted scale")
        void theTwoAgreeOnPersistedScales() {
            // NUMERIC(24,6) and NUMERIC(20,12) cannot hold a negative scale, so a figure that has
            // been through the persistence layer is normalised into the region where the two
            // tests give the same answer. The divergence above is reachable only in memory.
            for (int scale = 0; scale <= 12; scale++) {
                BigDecimal left = new BigDecimal("1408.29")
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
                BigDecimal right = new BigDecimal("1408.29")
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
                assertThat(left.toPlainString().equals(right.toPlainString()))
                    .isEqualTo(left.equals(right));
            }
        }
    }
}
