package com.crisil.eir.policy.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * DT-1 recomputed from its definition, over arbitrary figure sets.
 *
 * <p><b>No expected value here comes from running the comparison.</b> Each property restates what
 * FR-903 <em>means</em> and checks the control against that:
 *
 * <ul>
 *   <li>"bit-identical" means the two runs published the same characters for every figure. So the
 *       expected number of figure discrepancies is, by definition, the number of keys whose
 *       {@code toPlainString()} differs — computed in the test directly from the two maps, with no
 *       reference to {@link ReplayFigure} or {@link ReplayComparison}.</li>
 *   <li>The perturbation is chosen to be exactly the one {@link Money#equals} cannot see: every
 *       amount is re-scaled, so every pair is numerically identical and
 *       {@code published.equals(replayed)} is true for all of them. {@link #reScalingIsCaught}
 *       asserts that too, on every try — a comparison built on {@code Money.equals} would report
 *       zero discrepancies on every case this property generates.</li>
 *   <li>A policy version divergence is a DT-1 failure whatever the figures did, and its deviation
 *       is one per kind.</li>
 * </ul>
 *
 * <p>These would each be violated by a plausible wrong implementation. A comparison delegating to
 * {@code Money.equals}, or to {@code compareTo}, or normalising with {@code stripTrailingZeros}
 * before comparing, passes every single-figure example that happens to use two-decimal amounts and
 * fails {@link #reScalingIsCaught} on the first re-scaled one.
 */
class ReplayDeterminismPropertiesTest {

    /** April 2027, the first period under ACPIR 20, closed on 5 May. */
    private static final ClosedPeriod PERIOD = ClosedPeriod.month(
        YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "financial.controller");

    /**
     * An empty timeline.
     *
     * <p>Sound for these properties because none of them reaches the timeline check: it is
     * consulted only where the two runs' stamps already agree, and every property here either
     * cites no policy at all or cites two different ids. The timeline's own behaviour is covered
     * by example in {@link ReplayComparisonTest}, where a supersession makes the period-end
     * resolution distinguishable from resolving at the replay date.
     */
    private static final PolicyVersionRegistry NO_TIMELINE = PolicyVersionRegistry.of();

    /** Rupees-and-paise from an integer number of paise: 129 becomes {@code 1.29}, scale 2. */
    private static Map<String, Money> figuresFromPaise(List<Integer> paise, int count) {
        Map<String, Money> figures = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            figures.put("FIG-" + i,
                Money.of(BigDecimal.valueOf(paise.get(i)).movePointLeft(2), Money.INR));
        }
        return figures;
    }

    /** The same amounts, each carried at a wider scale — the drift this control exists to catch. */
    private static Map<String, Money> reScaled(
        Map<String, Money> original, List<Integer> extraDecimals, int count) {
        Map<String, Money> drifted = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Money amount = original.get("FIG-" + i);
            drifted.put("FIG-" + i, Money.of(
                amount.amount().setScale(amount.amount().scale() + extraDecimals.get(i)),
                amount.currency()));
        }
        return drifted;
    }

    private static ReplayComparison compare(
        Map<String, Money> published,
        Map<String, Money> replayed,
        Map<PolicyKind, String> publishedStamps,
        Map<PolicyKind, String> replayedStamps) {
        return ReplayComparison.of(
            PERIOD,
            ReplayRun.published("RUN-ORIGINAL", 202704, published, publishedStamps),
            ReplayRun.replayOf("RUN-REPLAY", "RUN-ORIGINAL", 202704, replayed, replayedStamps),
            NO_TIMELINE);
    }

    /**
     * The control's definition, restated: how many keys published different characters.
     *
     * <p>Deliberately written from {@code toPlainString} on the raw {@link BigDecimal}s rather than
     * by calling anything in the package under test. If this and the implementation ever disagree,
     * one of them has stopped meaning "bit-identical".
     */
    private static int keysWhoseRenderedFigureDiffers(
        Map<String, Money> published, Map<String, Money> replayed) {
        int differing = 0;
        for (Map.Entry<String, Money> entry : published.entrySet()) {
            Money other = replayed.get(entry.getKey());
            if (other == null
                || !entry.getValue().amount().toPlainString()
                    .equals(other.amount().toPlainString())) {
                differing++;
            }
        }
        return differing;
    }

    /**
     * Re-scaling every figure is invisible to {@code Money.equals} and must not be invisible to
     * DT-1.
     *
     * <p>The deviation is checked against the definition twice over, by two independent routes: the
     * rendered-string count above, and the count of figures that were actually given extra decimals
     * — which is the same number because appending {@code n > 0} zeros to a plain decimal always
     * changes it, and appending none never does.
     */
    @Property(tries = 400)
    void reScalingIsCaught(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 99_999_999) Integer> paise,
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 4) Integer> extraDecimals) {

        int count = Math.min(paise.size(), extraDecimals.size());
        Map<String, Money> published = figuresFromPaise(paise, count);
        Map<String, Money> replayed = reScaled(published, extraDecimals, count);

        // The trap, asserted on every try. Every pair is the same amount of money, so the type's
        // own equality says the replay reproduced the period exactly.
        for (int i = 0; i < count; i++) {
            Money before = published.get("FIG-" + i);
            Money after = replayed.get("FIG-" + i);
            assertThat(before.equals(after))
                .as("Money.equals ignores scale, so it reports %s and %s as the same figure",
                    before.amount().toPlainString(), after.amount().toPlainString())
                .isTrue();
            assertThat(before.amount().compareTo(after.amount()))
                .as("and they are the same amount: zero rupees of difference")
                .isZero();
        }

        int expected = keysWhoseRenderedFigureDiffers(published, replayed);
        int reScaledCount = 0;
        for (int i = 0; i < count; i++) {
            if (extraDecimals.get(i) > 0) {
                reScaledCount++;
            }
        }
        assertThat(expected)
            .as("two independent recomputations of the same definition agree")
            .isEqualTo(reScaledCount);

        ReplayComparison comparison = compare(published, replayed, Map.of(), Map.of());
        InvariantResult result = comparison.dtOne();

        assertThat(comparison.figureDiscrepancyCount())
            .as("one finding per key whose published characters differ")
            .isEqualTo(expected);
        assertThat(result.satisfied()).isEqualTo(expected == 0);
        assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(expected));
        assertThat(comparison.discrepanciesOf(DiscrepancyKind.SCALE_ONLY))
            .as("every one of them is a scale drift, never a value move")
            .hasSize(expected);
    }

    /**
     * The figure leg is silent on a faithful copy of any figure set, and the two legs are
     * independent.
     *
     * <p>Two claims in one property because they are the same claim from both sides. A replay that
     * published the same characters produces no figure finding whatever the amounts were — so a
     * pass is not an artefact of the fixtures being tidy. And it produces one all the same on the
     * policy leg when the version both runs cite is not one the timeline resolves, so the figure
     * leg passing cannot mask the policy leg failing.
     */
    @Property(tries = 300)
    void aFaithfulCopyProducesNoFigureFinding(
        @ForAll @Size(min = 0, max = 8) List<@IntRange(min = 0, max = 99_999_999) Integer> paise,
        @ForAll PolicyKind kind) {

        Map<String, Money> figures = figuresFromPaise(paise, paise.size());

        // Both runs cite V-1, which the empty timeline does not resolve, so this is the
        // POLICY_NOT_IN_FORCE case: zero figure findings and exactly one policy finding.
        ReplayComparison withStamps =
            compare(figures, figures, Map.of(kind, "V-1"), Map.of(kind, "V-1"));
        assertThat(withStamps.figureDiscrepancyCount()).isZero();
        assertThat(withStamps.policyDiscrepancyCount()).isEqualTo(1);
        assertThat(withStamps.dtOne().satisfied()).isFalse();

        // Cite nothing, and the same figures pass outright.
        ReplayComparison withoutStamps = compare(figures, figures, Map.of(), Map.of());
        assertThat(withoutStamps.dtOne().satisfied()).isTrue();
        assertThat(withoutStamps.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(withoutStamps.isBitIdentical()).isTrue();
    }

    /**
     * A version divergence fails DT-1 on any figure set, including a perfectly reproduced one.
     *
     * <p>The second half of FR-903, as a property rather than as one example: whatever the figures
     * did, resolving a different policy version is a breach, and the deviation counts kinds.
     */
    @Property(tries = 200)
    void aVersionDivergenceAlwaysFails(
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 99_999_999) Integer> paise,
        @ForAll PolicyKind first,
        @ForAll PolicyKind second) {

        Map<String, Money> figures = figuresFromPaise(paise, paise.size());

        Map<PolicyKind, String> atClose = new EnumMap<>(PolicyKind.class);
        Map<PolicyKind, String> onReplay = new EnumMap<>(PolicyKind.class);
        atClose.put(first, "V-CLOSE");
        onReplay.put(first, "V-REPLAY");
        atClose.put(second, "V-CLOSE");
        onReplay.put(second, "V-REPLAY");

        // One or two distinct kinds, depending on whether jqwik drew the same enum twice. The
        // expected deviation is the number of DISTINCT kinds, because the deviation counts kinds
        // needing a remedy — recomputed here from the map, not from the comparison.
        int distinctKinds = atClose.size();

        ReplayComparison comparison = compare(figures, figures, atClose, onReplay);

        assertThat(comparison.figureDiscrepancyCount())
            .as("the figures were reproduced exactly")
            .isZero();
        assertThat(comparison.dtOne().satisfied())
            .as("and DT-1 fails anyway: the right number from the wrong rule is luck")
            .isFalse();
        assertThat(comparison.dtOne().deviation())
            .isEqualByComparingTo(BigDecimal.valueOf(distinctKinds));
        assertThat(comparison.discrepanciesOf(DiscrepancyKind.POLICY_VERSION_DIFFERS))
            .hasSize(distinctKinds);
    }

    /**
     * The deviation is exactly the number of findings, and zero exactly when nothing was found.
     *
     * <p>The structural claim that rules out both failure modes the codebase names: a control that
     * passes while carrying findings, and one that fails while reporting a deviation of nil.
     */
    @Property(tries = 300)
    void theDeviationIsTheFindingCountAndIsZeroOnlyOnAPass(
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 99_999_999) Integer> paise,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 3) Integer> extraDecimals,
        @ForAll boolean divergePolicy) {

        int count = Math.min(paise.size(), extraDecimals.size());
        Map<String, Money> published = figuresFromPaise(paise, count);
        Map<String, Money> replayed = reScaled(published, extraDecimals, count);
        Map<PolicyKind, String> atClose = Map.of(PolicyKind.FEE_RULE_SET, "FEE-A");
        Map<PolicyKind, String> onReplay =
            Map.of(PolicyKind.FEE_RULE_SET, divergePolicy ? "FEE-B" : "FEE-A");

        ReplayComparison comparison = compare(published, replayed, atClose, onReplay);
        InvariantResult result = comparison.dtOne();

        assertThat(result.deviation())
            .isEqualByComparingTo(BigDecimal.valueOf(comparison.discrepancies().size()));
        assertThat(comparison.discrepancies().size())
            .isEqualTo(comparison.figureDiscrepancyCount() + comparison.policyDiscrepancyCount());
        assertThat(result.satisfied()).isEqualTo(result.deviation().signum() == 0);
        assertThat(result.satisfied()).isEqualTo(comparison.isBitIdentical());
        // One result, never one per finding. InvariantResult.conjunction keeps only the first
        // breach's deviation among results sharing an id, so a list of them would lose all but one.
        assertThat(InvariantResult.oneResultPerInvariant(List.of(result))).hasSize(1);
    }

    /**
     * The C-12 rotation selects a reproducible, correctly-sized subset of the eligible periods on
     * any night.
     *
     * <p>Stated as a property because the selection has to be recomputable by an auditor from the
     * night and the population alone. A sampler that reached for {@code new Random()}, or that
     * ordered the population by insertion, satisfies every single-night example and fails this.
     */
    @Property(tries = 300)
    void tonightsSampleIsReproducibleAndCorrectlySized(
        @ForAll @IntRange(min = 0, max = 700) int nightOffset,
        @ForAll @IntRange(min = 1, max = 24) int populationSize,
        @ForAll @IntRange(min = 0, max = 5) int periodsPerNight) {

        List<ClosedPeriod> population = new ArrayList<>();
        YearMonth month = YearMonth.of(2027, 4);
        for (int i = 0; i < populationSize; i++) {
            population.add(ClosedPeriod.month(
                month, month.atEndOfMonth().plusDays(5), "financial.controller"));
            month = month.plusMonths(1);
        }
        LocalDate night = LocalDate.of(2027, 4, 1).plusDays(nightOffset);
        ReplaySamplingBasis basis =
            new ReplaySamplingBasis(periodsPerNight, 36, "property fixture");

        ReplaySample first = ReplaySample.forNight(night, basis, population);
        List<ClosedPeriod> shuffled = new ArrayList<>(population);
        java.util.Collections.reverse(shuffled);
        ReplaySample second = ReplaySample.forNight(night, basis, shuffled);

        assertThat(first.selected())
            .as("a function of the night and the data, not of load order")
            .isEqualTo(second.selected());
        assertThat(first.selected())
            .as("nothing is replayed that the basis did not admit")
            .allSatisfy(period -> assertThat(first.eligible()).contains(period));
        assertThat(first.selected())
            .as("as deep as the basis asks, or the whole eligible set if that is smaller")
            .hasSize(Math.min(periodsPerNight, first.eligible().size()));
        assertThat(first.selected()).doesNotHaveDuplicates();
        // An inert sample is exactly: something to replay, and nothing selected.
        assertThat(first.isInert())
            .isEqualTo(!population.isEmpty() && first.selected().isEmpty());
    }
}
