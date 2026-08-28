package com.crisil.eir.application.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.PolicyKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * DT-1 recomputed from its definition over arbitrary books, independently of the implementation.
 *
 * <p><b>No expected value here comes from running the code under test.</b> Each property restates
 * what 05 § 3.3 and FR-903 <em>mean</em> and checks the use case against that:
 *
 * <ul>
 *   <li>"compared byte-for-byte with the published figures" means the two runs published the same
 *       characters for every figure. So the expected number of discrepancies is, by definition, the
 *       number of keys whose {@code toPlainString()} differs — counted in
 *       {@link #keysWhoseRenderedFigureDiffers} directly from two maps this test builds, with no
 *       reference to {@link ShadowRun}, {@code ReplayFigure} or {@code ReplayComparison}.</li>
 *   <li>The published side is assembled from string literals in {@link #publishedFigures}, never
 *       from {@link ShadowRun#figures}. Building both sides with the code under test would make a
 *       key-convention defect cancel out on both sides and every property here would pass on
 *       it.</li>
 *   <li>The perturbation is the one {@code Money.equals} cannot see: extra decimal places. Every
 *       perturbed pair is numerically identical, so a comparison delegating to {@code Money.equals}
 *       — or normalising with {@code atPresentationScale()} or {@code stripTrailingZeros()}
 *       anywhere in the shadow reduction — reports zero discrepancies on every case generated here
 *       and fails the first property on its first try.</li>
 *   <li>FR-905's subtraction is arithmetic, so {@link #thePopulationSubtractionAlwaysBalances}
 *       recomputes it by counting rather than by asking the type.</li>
 * </ul>
 */
class ReplayUseCasePropertiesTest {

    /** A fixed journal amount, so only the closing balance can drift. */
    private static final String POSTING = "100.00";

    private static String contractId(int index) {
        return String.format("LN-%07d", index);
    }

    /** Rupees and paise from an integer number of paise: 129 becomes {@code 1.29}, scale 2. */
    private static Money fromPaise(int paise) {
        return Money.of(BigDecimal.valueOf(paise).movePointLeft(2), Money.INR);
    }

    /** The same amount carried at a wider scale — the drift this control exists to catch. */
    private static Money reScaled(Money amount, int extraDecimals) {
        return Money.of(
            amount.amount().setScale(amount.amount().scale() + extraDecimals), amount.currency());
    }

    /**
     * The published figure map, written out key by key.
     *
     * <p>{@code CONTRACT:id:column}, the row-level convention {@code PeriodStatement} records.
     * Spelled out here rather than delegated — see the class comment.
     */
    private static Map<String, Money> publishedFigures(List<Money> balances) {
        Map<String, Money> figures = new LinkedHashMap<>();
        for (int i = 0; i < balances.size(); i++) {
            String id = contractId(i);
            figures.put("CONTRACT:" + id + ":closing_gca", balances.get(i));
            figures.put("CONTRACT:" + id + ":journal:1:" + ReplayFixtures.LOAN_ASSET + ":DR",
                Money.inr(POSTING));
            figures.put("CONTRACT:" + id + ":journal:2:" + ReplayFixtures.INTEREST_INCOME + ":CR",
                Money.inr(POSTING));
        }
        return figures;
    }

    /** One computed contract carrying {@code balance} and the fixed two-line posting. */
    private static ContractResult computed(String id, Money balance) {
        return ContractResult.computed(id, balance,
            new JournalEntry(id, 202704, "SHADOW", ReplayFixtures.BOOK,
                LocalDate.of(2027, 4, 30),
                List.of(
                    JournalLine.debit(
                        ReplayFixtures.LOAN_ASSET, Money.inr(POSTING), "EIR interest accrued"),
                    JournalLine.credit(
                        ReplayFixtures.INTEREST_INCOME, Money.inr(POSTING), "interest income"))),
            List.of());
    }

    /**
     * The control's definition, restated: how many keys published different characters.
     *
     * <p>Written from {@code toPlainString} on the raw {@link BigDecimal}s. If this and the
     * implementation ever disagree, one of them has stopped meaning "byte-for-byte".
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
        for (String key : replayed.keySet()) {
            if (!published.containsKey(key)) {
                differing++;
            }
        }
        return differing;
    }

    private static ReplayVerification replay(
        List<Money> publishedBalances,
        List<Money> replayedBalances,
        Map<PolicyKind, String> replayStamps) {

        List<String> population = new ArrayList<>();
        List<ContractResult> results = new ArrayList<>();
        for (int i = 0; i < replayedBalances.size(); i++) {
            population.add(contractId(i));
            results.add(computed(contractId(i), replayedBalances.get(i)));
        }
        ReplayFixtures.Ports ports =
            new ReplayFixtures.Ports(population, ReplayFixtures.supersededTimeline());
        PublishedRun published = ReplayFixtures.publishedRun(
            ReplayFixtures.APRIL_2027, publishedFigures(publishedBalances),
            ReplayFixtures.aprilStamps());
        return new ReplayUseCase(ReplayFixtures.jobReturning(results, replayStamps))
            .replay(
                new ReplayRequest(published, ports.liveTemplate(published.periodId())),
                "REPLAY-PROPERTY");
    }

    /**
     * A scale drift on any subset of the book is exactly that many DT-1 discrepancies.
     *
     * <p>The deviation is checked against the definition twice over, by two independent routes: the
     * rendered-string count, and the count of balances that were actually given extra decimals —
     * the same number, because appending {@code n > 0} zeros to a plain decimal always changes it
     * and appending none never does.
     */
    @Property(tries = 300)
    void aScaleDriftOnAnySubsetIsExactlyThatManyDiscrepancies(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 1, max = 99_999_999) Integer> paise,
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 4) Integer> extraDecimals) {

        int count = Math.min(paise.size(), extraDecimals.size());
        List<Money> published = new ArrayList<>(count);
        List<Money> replayed = new ArrayList<>(count);
        int drifted = 0;
        for (int i = 0; i < count; i++) {
            Money balance = fromPaise(paise.get(i));
            published.add(balance);
            replayed.add(reScaled(balance, extraDecimals.get(i)));
            if (extraDecimals.get(i) > 0) {
                drifted++;
            }
            // The trap, asserted on every try: the type's own equality says the replay reproduced
            // this figure exactly, and the two amounts differ by zero rupees.
            assertThat(published.get(i).equals(replayed.get(i)))
                .as("Money.equals ignores scale, so it reports %s and %s as the same figure",
                    published.get(i).amount().toPlainString(),
                    replayed.get(i).amount().toPlainString())
                .isTrue();
        }

        int expected = keysWhoseRenderedFigureDiffers(
            publishedFigures(published), publishedFigures(replayed));
        assertThat(expected)
            .as("two independent recomputations of the same definition agree")
            .isEqualTo(drifted);

        ReplayVerification verification =
            replay(published, replayed, ReplayFixtures.aprilStamps());
        InvariantResult dtOne = verification.dtOne();

        assertThat(verification.comparison().figuresCompared())
            .as("three figures per contract: a closing balance and two postings")
            .isEqualTo(3 * count);
        assertThat(verification.coverage()).isEqualTo(ReplayCoverage.FIGURES_COMPARED);
        assertThat(verification.comparison().figureDiscrepancyCount()).isEqualTo(expected);
        assertThat(verification.comparison().policyDiscrepancyCount())
            .as("the stamps agree with each other and with the timeline")
            .isZero();
        assertThat(dtOne.satisfied()).isEqualTo(expected == 0);
        assertThat(dtOne.deviation()).isEqualByComparingTo(BigDecimal.valueOf(expected));
        assertThat(verification.provesReproduction()).isEqualTo(expected == 0);
    }

    /**
     * A faithful replay of any book still fails DT-1 when the rule moved.
     *
     * <p>The half of FR-903 that is easiest to drop. Whatever the figures were, and however many of
     * them reproduced perfectly, a replay citing the superseding fee rule set is one discrepancy —
     * so the figure leg passing can never mask the policy leg failing.
     */
    @Property(tries = 200)
    void figuresReproducingNeverMasksThePolicyLegFailing(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 1, max = 99_999_999) Integer> paise) {

        List<Money> balances = new ArrayList<>(paise.size());
        for (Integer amount : paise) {
            balances.add(fromPaise(amount));
        }

        ReplayVerification faithful =
            replay(balances, balances, ReplayFixtures.aprilStamps());
        assertThat(faithful.dtOne().satisfied())
            .as("a faithful copy under the period's own policy reproduces")
            .isTrue();

        ReplayVerification wrongRule =
            replay(balances, balances, ReplayFixtures.stampsResolvedAtTheReplayDate());
        assertThat(wrongRule.comparison().figureDiscrepancyCount())
            .as("every figure is bit-identical — this is not a numeric defect")
            .isZero();
        assertThat(wrongRule.comparison().policyDiscrepancyCount()).isEqualTo(1);
        assertThat(wrongRule.dtOne().satisfied()).isFalse();
        assertThat(wrongRule.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(wrongRule.provesReproduction()).isFalse();
    }

    /**
     * FR-905's subtraction, recomputed by counting.
     *
     * <p>Every contract in the population is either computed or quarantined, so
     * {@code populationSize == computed + quarantined} for any split — and removing any one result
     * breaks it, which is what makes the account able to fail.
     */
    @Property(tries = 200)
    void thePopulationSubtractionAlwaysBalances(
        @ForAll @Size(min = 1, max = 10) List<@IntRange(min = 0, max = 1) Integer> quarantineFlags) {

        List<String> population = new ArrayList<>();
        List<ContractResult> results = new ArrayList<>();
        int expectedQuarantined = 0;
        for (int i = 0; i < quarantineFlags.size(); i++) {
            String id = contractId(i);
            population.add(id);
            if (quarantineFlags.get(i) == 1) {
                results.add(ReplayFixtures.quarantined(id, "SHADOW"));
                expectedQuarantined++;
            } else {
                results.add(computed(id, Money.inr("1000.00")));
            }
        }

        PopulationAccount account = PopulationAccount.of(population, results);
        assertThat(account.addsUp()).isTrue();
        assertThat(account.populationSize()).isEqualTo(quarantineFlags.size());
        assertThat(account.quarantined()).hasSize(expectedQuarantined);
        assertThat(account.computed()).hasSize(quarantineFlags.size() - expectedQuarantined);
        assertThat(account.computed().size() + account.quarantined().size())
            .as("FR-905: computed + quarantined = the population, always")
            .isEqualTo(account.populationSize());

        // Drop the last result: the run silently processed one fewer than it was given, which is
        // the failure that reconciles perfectly against itself.
        PopulationAccount dropped = PopulationAccount.of(
            population, results.subList(0, results.size() - 1));
        assertThat(dropped.addsUp()).isFalse();
        assertThat(dropped.unaccounted())
            .containsExactly(contractId(quarantineFlags.size() - 1));
    }
}
