package com.crisil.eir.application.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.ReconciliationScope;
import com.crisil.eir.policy.close.ReconciliationTie;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import com.crisil.eir.policy.reconciliation.ContractualLegInterest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The run-level close, and the trap it exists to refuse (05 § 3.2).
 *
 * <p><b>The figures.</b> Three contracts, each posting reference case 1's month-13 shape — DR
 * 5,506.79 to the EIR receivable against CR 5,298.16 interest income and CR 208.63 unamortised fee,
 * which balances because the 208.63 is the period's fee amortisation slice. Closing GCA of
 * 533,914.11 each, so the sub-ledger totals 3 x 533,914.11 = 1,601,742.33 and the GL is given the
 * same. The CBS bills 5,298.16 per contract against an engine contractual leg of the same, so
 * RC-1 ties at 3 x 5,298.16 = 15,894.48. Every figure by hand.
 *
 * <p><b>The two ties this class hands in.</b> Step 6 names four reconciliations and
 * {@code ReconciliationScope.requiredForClose()} is true for all four, so a close presenting two
 * of them is refused — which is what the first run of this class found, and correctly. The Stage 3
 * four-way and the floor duality are supplied here as tied at nil against nil, standing in for a
 * book with no Stage 3 accounts and no binding floor, because a {@code ContractResult} carries no
 * ECL allowance, no suspense balance and no stage for {@code RunClose} to derive them from.
 *
 * <p><b>What the empty cases are for.</b> A close is the one place a green result gets signed, and
 * every total in this class is nil-and-tying over an empty population. Three distinct emptinesses
 * are asserted separately because their remedies differ, and because a single "population is empty"
 * check would let the third — a shortfall between what was named and what came back — pass.
 */
class RunCloseTest {

    private static final String RUN = "RUN-202804-01";
    private static final int PERIOD = 202804;
    private static final String BOOK = "MAIN";
    private static final String GCA = "1301-LOANS-GCA";
    private static final LocalDate PERIOD_END = LocalDate.of(2028, 4, 30);
    private static final Instant KNOWN_AT = Instant.parse("2028-05-10T04:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2028-05-12T09:00:00Z");
    private static final Money CLOSING_GCA = Money.inr("533914.11");
    private static final Money BILLED = Money.inr("5298.16");

    private static JournalEntry journal(String contractId) {
        return new JournalEntry(contractId, PERIOD, RUN, BOOK, PERIOD_END, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("5506.79"), "EIR accrual"),
            JournalLine.credit("4101-INTEREST-INCOME", BILLED, "contractual"),
            JournalLine.credit("2301-UNAMORTISED-FEE", Money.inr("208.63"), "fee amortisation")));
    }

    private static ContractResult computed(String contractId) {
        return ContractResult.computed(contractId, CLOSING_GCA, journal(contractId), List.of());
    }

    /**
     * The two reconciliations {@code RunClose} cannot source, tied.
     *
     * <p>Nil against nil is the honest fixture for a book with no Stage 3 accounts and no binding
     * floor, and it is deliberately <em>not</em> what {@code RunClose} synthesises internally: a
     * caller who has Stage 3 accounts and hands in nil-against-nil is stating something false about
     * its own book, which is a claim the close can attribute to somebody. The same figure invented
     * inside {@code RunClose} would be attributable to nobody and true of every run ever made.
     */
    private static List<ReconciliationTie> portfolioTies() {
        Money nil = Money.zero(Money.INR);
        return List.of(
            new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY, nil, nil,
                "no Stage 3 accounts in the book"),
            new ReconciliationTie(ReconciliationScope.PRE_AND_POST_FLOOR, nil, nil,
                "no binding regulatory floor"));
    }

    private static RunRequest request(
        List<String> population, List<String> cbsPopulation, Money glBalance) {
        ContractSource contracts = boundary -> population;
        ContractStateSource state = (id, boundary) -> Optional.empty();
        CoreBankingFeed cbs = boundary -> cbsPopulation.stream()
            .map(id -> new CbsBilledInterest(id, PERIOD, BILLED, "CBS-EOD-202804"))
            .toList();
        GeneralLedgerSource gl = boundary -> glBalance == null ? List.of()
            : List.of(GlControlAccountBalance.of(GCA, glBalance, "TB-202804-FINAL"));
        PolicySource policy = boundary -> PolicyVersionRegistry.of();
        return new RunRequest(RUN, PERIOD, BOOK,
            AsAtBoundary.live(PERIOD_END, KNOWN_AT), contracts, state, cbs, gl, policy);
    }

    private static List<ContractualLegInterest> engineLines(List<ContractResult> results) {
        List<ContractualLegInterest> lines = new ArrayList<>();
        for (ContractResult result : results) {
            if (result.isComputed()) {
                lines.add(new ContractualLegInterest(result.contractId(), PERIOD, BILLED));
            }
        }
        return lines;
    }

    /** The CBS feed scoped to the whole named population, which is the normal integration. */
    private static RunClose.ClosePresentation present(
        List<String> population, List<ContractResult> results, Money glBalance) {
        return present(population, population, results, glBalance, portfolioTies());
    }

    private static RunClose.ClosePresentation present(
        List<String> population,
        List<String> cbsPopulation,
        List<ContractResult> results,
        Money glBalance,
        List<ReconciliationTie> ties) {
        return RunClose.present(
            request(population, cbsPopulation, glBalance),
            RunAggregate.of(RUN, PERIOD, population, results, List.of("FEE-2027.1")),
            GCA, engineLines(results), ties,
            AccountingPeriod.open(PERIOD, "FY2028-29", LocalDate.of(2028, 4, 1), PERIOD_END)
                .startClosing(Instant.parse("2028-05-11T09:00:00Z")),
            "financial.controller", CLOSED_AT, List.of(), List.of());
    }

    @Nested
    @DisplayName("the controls are actually invoked, which is the point of this class")
    class ControlsHaveACaller {

        @Test
        @DisplayName("SL-2, SL-1 and RC-1 are all put to the gate")
        void everyPhaseFiveControlIsAsserted() {
            // docs/08 recorded that every Phase 5 control was live code with no caller: nothing in
            // src/main constructed a GlReconciliation, a PeriodCloseGate or a
            // CoreBankingReconciliation. An evaluator nobody invokes is indistinguishable from an
            // absent one, so this asserts INVOCATION, not merely that each passed.
            List<String> population = List.of("C1", "C2", "C3");
            RunClose.ClosePresentation presentation = present(
                population,
                List.of(computed("C1"), computed("C2"), computed("C3")),
                Money.inr("1601742.33"));

            assertThat(presentation.asserted(InvariantId.SL_2)).isTrue();
            assertThat(presentation.asserted(InvariantId.SL_1)).isTrue();
            assertThat(presentation.asserted(InvariantId.RC_1)).isTrue();
        }

        @Test
        @DisplayName("a clean run ties on all three and may close")
        void aCleanRunCloses() {
            // 3 x 533,914.11 = 1,601,742.33 on both sides of SL-1, and 3 x 5,298.16 = 15,894.48 on
            // both sides of RC-1 — by hand, not read off the engine.
            RunClose.ClosePresentation presentation = present(
                List.of("C1", "C2", "C3"),
                List.of(computed("C1"), computed("C2"), computed("C3")),
                Money.inr("1601742.33"));

            assertThat(presentation.breaches())
                .as("breaches: %s", presentation.breaches())
                .isEmpty();
            assertThat(presentation.populationRefusals()).isEmpty();
            assertThat(presentation.mayClose()).isTrue();
            assertThat(presentation.coreBanking().totalCbsBilledInterest())
                .isEqualTo(Money.inr("15894.48"));
        }

        @Test
        @DisplayName("a GL that disagrees breaks SL-1 and blocks the close")
        void aGlDifferenceBlocks() {
            // The GL is short by 1,000.00. Nothing about the journals changes, so SL-2 still
            // passes — which is the point of the two controls being separate.
            RunClose.ClosePresentation presentation = present(
                List.of("C1", "C2", "C3"),
                List.of(computed("C1"), computed("C2"), computed("C3")),
                Money.inr("1600742.33"));

            assertThat(presentation.breaches())
                .extracting(com.crisil.eir.domain.InvariantResult::id)
                .containsExactly(InvariantId.SL_1);
            assertThat(presentation.totalDeviation()).isEqualByComparingTo("1000.00");
            assertThat(presentation.mayClose()).isFalse();
        }
    }

    @Nested
    @DisplayName("a population it did not measure cannot support a close")
    class TheEmptyPopulationTrap {

        @Test
        @DisplayName("no contracts at all: every total is nil, every reconciliation ties, and it refuses")
        void noPopulation() {
            // The defect this codebase has found more often than any other, at the one place a
            // green result gets signed. Note what the gate itself says: with all four
            // reconciliations presented and no evidence of a breach it returns a clean decision —
            // correct on the evidence, and exactly why the check is not delegated to it. Every
            // total put to it here is over nothing at all.
            RunClose.ClosePresentation presentation = present(List.of(), List.of(), null);

            assertThat(presentation.breaches())
                .as("nothing breached, because nothing was measured")
                .isEmpty();
            assertThat(presentation.decision().isRefused())
                .as("the gate is content — it cannot know the evidence covers nothing")
                .isFalse();
            assertThat(presentation.mayClose())
                .as("and the close still refuses")
                .isFalse();
            assertThat(presentation.populationRefusals())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("an empty population is a feed failure, not a period without activity");
        }

        @Test
        @DisplayName("a population where everything was quarantined refuses, for its own reason")
        void allQuarantined() {
            // Distinct from an empty population and distinct from a shortfall: the contracts were
            // named and every one failed, which reconciles perfectly because both sides are empty.
            List<ContractResult> isolated = List.of(
                ContractResult.isolated("C1", exception("C1")),
                ContractResult.isolated("C2", exception("C2")));

            RunClose.ClosePresentation presentation =
                present(List.of("C1", "C2"), isolated, null);

            assertThat(presentation.mayClose()).isFalse();
            assertThat(presentation.populationRefusals())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("all 2 contracts were quarantined");
            // Caught twice, and the second detection is worth pinning rather than leaving masked
            // behind mayClose(): the CBS billed both contracts, the engine projected neither, so
            // RC-1 is red on presence at 2 x 5,298.16 = 10,596.32 and the gate refuses on its own
            // evidence. Deleting the population check would still leave this run refused — which
            // is why the shortfall case above, not this one, is the one that carries the argument.
            assertThat(presentation.breaches())
                .extracting(com.crisil.eir.domain.InvariantResult::id)
                .containsExactly(InvariantId.RC_1);
            assertThat(presentation.totalDeviation()).isEqualByComparingTo("10596.32");
        }

        @Test
        @DisplayName("a shortfall refuses: named, and neither computed nor quarantined")
        void aShortfallIsTheSilentOne() {
            // The one a single "is the population empty" check would miss, and the dangerous one:
            // a run over 10,000,000 contracts that silently processed 9,999,998 reconciles
            // perfectly, because the two it dropped are absent from BOTH sides of every total.
            //
            // The CBS feed is scoped to the engine's output here, which is the unlucky case and
            // the one worth testing: 2 x 533,914.11 = 1,067,828.22 on both sides of SL-1 and
            // 2 x 5,298.16 on both sides of RC-1, so every reconciliation ties and C3's
            // disappearance is invisible to all of them. aFullFeedAlsoCatchesTheShortfall below
            // covers the lucky case; that one must not be what the engine relies on.
            RunClose.ClosePresentation presentation = present(
                List.of("C1", "C2", "C3"),
                List.of("C1", "C2"),
                List.of(computed("C1"), computed("C2")),
                Money.inr("1067828.22"),
                portfolioTies());

            assertThat(presentation.breaches())
                .as("every reconciliation ties on the contracts that came back: %s",
                    presentation.breaches())
                .isEmpty();
            assertThat(presentation.decision().isRefused())
                .as("and the gate, on that evidence, is content")
                .isFalse();
            assertThat(presentation.mayClose()).isFalse();
            assertThat(presentation.populationRefusals())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("1 of 3 contracts were neither computed nor quarantined");
        }

        @Test
        @DisplayName("a CBS feed over the whole population catches the same shortfall as RC-1")
        void aFullFeedAlsoCatchesTheShortfall() {
            // The same run, with the feed scoped to the named population rather than to the
            // engine's output. C3 is then billed by the CBS and absent from the engine, so RC-1
            // breaks on presence. Worth pinning because it is the difference between two
            // integrations of the same feed, and because it shows the population check and RC-1
            // are independent detections of one fact rather than one detection counted twice.
            RunClose.ClosePresentation presentation = present(
                List.of("C1", "C2", "C3"),
                List.of(computed("C1"), computed("C2")),
                Money.inr("1067828.22"));

            assertThat(presentation.breaches())
                .extracting(com.crisil.eir.domain.InvariantResult::id)
                .containsExactly(InvariantId.RC_1);
            assertThat(presentation.mayClose()).isFalse();
        }
    }

    @Nested
    @DisplayName("step 6 wants four reconciliations, and this class computes two of them")
    class TheReconciliationsItCannotSource {

        @Test
        @DisplayName("a close presenting only the two it computes is refused")
        void theOtherTwoAreNotOptional() {
            // The first run of RunCloseTest failed exactly here, and the gate was right. A
            // ContractResult carries a closing GCA and a journal, so a Stage 3 four-way and a
            // floor duality are not derivable from this aggregate — and the tempting fix, a nil
            // against nil synthesised inside RunClose, would present as tied on every run in the
            // book's history. This pins the refusal so that fix cannot be made quietly.
            RunClose.ClosePresentation presentation = present(
                List.of("C1", "C2", "C3"), List.of("C1", "C2", "C3"),
                List.of(computed("C1"), computed("C2"), computed("C3")),
                Money.inr("1601742.33"), List.of());

            assertThat(presentation.breaches())
                .as("nothing breached: the two it does compute tie")
                .isEmpty();
            assertThat(presentation.populationRefusals()).isEmpty();
            assertThat(presentation.decision().isRefused())
                .as("and the close is refused anyway, on the two nobody presented")
                .isTrue();
            assertThat(presentation.decision().describe())
                .contains("Stage 3 four-way")
                .contains("pre- and post-floor");
        }

        @Test
        @DisplayName("a second answer for a scope this class computes is a wiring defect")
        void aDuplicateOwnedScopeThrows() {
            // Throws rather than refusing, because it is not a fact about the book. The gate checks
            // every tie it is handed, so a caller-supplied tied SUBLEDGER_TO_GL would not hide a
            // real break — but it would put two residuals for one reconciliation in front of
            // whoever has to fix it, with list position deciding which the report shows.
            List<ReconciliationTie> withADuplicate = new ArrayList<>(portfolioTies());
            withADuplicate.add(new ReconciliationTie(ReconciliationScope.SUBLEDGER_TO_GL,
                Money.zero(Money.INR), Money.zero(Money.INR), "a second opinion"));

            assertThatThrownBy(() -> present(
                List.of("C1"), List.of("C1"), List.of(computed("C1")),
                Money.inr("533914.11"), withADuplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this close computes that scope itself");
        }
    }

    private static com.crisil.eir.policy.exception.ExceptionRecord exception(String contractId) {
        return com.crisil.eir.policy.exception.ExceptionRecord.raise(
            contractId, RUN, com.crisil.eir.policy.exception.ExceptionCategory.NO_SOLUTION,
            "the solver found no root", "payload/" + contractId);
    }
}
