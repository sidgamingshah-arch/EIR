package com.crisil.eir.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The population aggregation, and the two ways it could report a clean close that was not one.
 *
 * <p>Results are built by hand here rather than driven through the pipeline, because the claims
 * under test are about the <em>aggregation</em> — an empty population, a short results list, two
 * breaks that would net — and constructing those through a pipeline that cannot produce them would
 * test the pipeline instead.
 */
class RunAggregateTest {

    private static final String RUN = "RUN-202805-01";
    private static final int PERIOD = 202805;
    private static final LocalDate POSTED = LocalDate.of(2028, 5, 31);
    private static final Money CLOSING = Money.inr("486840.64");

    /**
     * A computed contract whose journal is out by {@code residual}.
     *
     * <p>Debit 1,000.00 against a credit of {@code 1,000.00 − residual}, so the residual is exactly
     * the figure named and the arithmetic stays whole rupees: the claim is about the aggregation,
     * not about rounding.
     */
    private static ContractResult computed(String contractId, String residual) {
        Money debit = Money.inr("1000.00");
        Money credit = debit.minus(Money.inr(residual));
        JournalEntry entry = new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", debit, "accrual"),
            JournalLine.credit("4101-INTEREST-INCOME", credit, "income")));
        // Both of POPULATION_INVARIANTS, because that is what a computed contract publishes:
        // ContractPipeline.assertions() takes SL-2 from the entry's own sidesBalance() and ST-2
        // from Stage3Decomposition.againstLedger for every contract it computes. A fixture
        // publishing only SL-2 is a run that skipped an obligation, which is the subject of
        // ObligationsWithNoEvidence below and not the subject of the tests that use this helper.
        return ContractResult.computed(contractId, CLOSING, entry, List.of(
            entry.sidesBalance(),
            InvariantResult.pass(InvariantId.ST_2, "the accrual exponents agree")));
    }

    /** A computed contract publishing only SL-2 — a run that skipped ST-2 entirely. */
    private static ContractResult computedWithoutStTwo(String contractId) {
        JournalEntry entry = new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("1000.00"), "accrual"),
            JournalLine.credit("4101-INTEREST-INCOME", Money.inr("1000.00"), "income")));
        return ContractResult.computed(
            contractId, CLOSING, entry, List.of(entry.sidesBalance()));
    }

    private static ContractResult quarantined(String contractId) {
        return ContractResult.isolated(contractId, ExceptionRecord.raise(
            contractId, RUN, ExceptionCategory.MISSING_MANDATORY_FIELD,
            "tenor arrived empty", "payload/" + contractId));
    }

    private static InvariantResult resultFor(RunAggregate aggregate, InvariantId id) {
        return aggregate.invariants().stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " was not aggregated"));
    }

    @Nested
    @DisplayName("deviations are totalled ABSOLUTE, so two breaks cannot net to a pass")
    class Deviations {

        @Test
        @DisplayName("+100 and −100 aggregate to 200, not to nil")
        void oppositeBreaksDoNotNet() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2"),
                List.of(computed("C-1", "100.00"), computed("C-2", "-100.00")),
                List.of());

            InvariantResult slTwo = resultFor(aggregate, InvariantId.SL_2);
            assertThat(slTwo.satisfied()).isFalse();
            // A signed sum would be nil and the population would read as reconciled — the classic
            // way a reconciliation control passes while being broken twice.
            assertThat(slTwo.deviation()).isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(slTwo.detail())
                .contains("2 of 2 contract(s) breached SL_2")
                .contains("total absolute residual 200.00");
            assertThat(aggregate.reportsCleanClose()).isFalse();
        }

        @Test
        @DisplayName("one breach among many is named, with the count as its coverage")
        void oneBreachAmongMany() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2", "C-3"),
                List.of(computed("C-1", "0.00"), computed("C-2", "0.00"),
                    computed("C-3", "12.34")),
                List.of());

            InvariantResult slTwo = resultFor(aggregate, InvariantId.SL_2);
            assertThat(slTwo.satisfied()).isFalse();
            assertThat(slTwo.deviation()).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(slTwo.detail()).contains("1 of 3 contract(s) breached").contains("C-3");
        }

        @Test
        @DisplayName("an invariant no contract asserted does not appear at all")
        void unassertedIdsAreAbsent() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1"), List.of(computed("C-1", "0.00")), List.of());

            // Publishing a pass for an invariant nothing evaluated is the empty-population trap at
            // the level of a single id: S3-1 was never in scope here, so it is silent rather than
            // green.
            assertThat(aggregate.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.SL_2, InvariantId.ST_2);
            assertThat(aggregate.unassertedInvariants())
                .as("S3-1 is not an obligation: a performing book asserts it nowhere, and an"
                    + " obligation red on every performing book becomes a soft one")
                .isEmpty();
            assertThat(aggregate.reportsCleanClose()).isTrue();
        }
    }

    @Nested
    @DisplayName("a clean close requires the population to have been accounted for")
    class PopulationAccounting {

        @Test
        @DisplayName("an empty population is not a clean close, and says why")
        void emptyPopulation() {
            RunAggregate aggregate =
                RunAggregate.of(RUN, PERIOD, List.of(), List.of(), List.of());

            assertThat(aggregate.invariants()).isEmpty();
            assertThat(aggregate.breaches()).isEmpty();
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons().getFirst())
                .contains("accounted for no contracts at all")
                .contains("nothing is red and nothing was measured");
        }

        @Test
        @DisplayName("a results list short of its population names the missing contracts")
        void shortOfThePopulation() {
            // The 9,999,998-of-10,000,000 failure at scale three: C-3 is absent from both sides of
            // every total, so every figure reported ties.
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2", "C-3"),
                List.of(computed("C-1", "0.00"), computed("C-2", "0.00")),
                List.of());

            assertThat(aggregate.breaches()).isEmpty();
            assertThat(aggregate.unaccountedFor()).isEqualTo(1);
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("neither figures nor an exception record").contains("C-3"));
        }

        @Test
        @DisplayName("a result naming a contract outside the population is named too")
        void strangerInTheResults() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1"),
                List.of(computed("C-1", "0.00"), computed("C-99", "0.00")),
                List.of());

            assertThat(aggregate.results()).hasSize(2);
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("name contracts the population does not").contains("C-99"));
        }

        @Test
        @DisplayName("two results for one contract mean one overwrote the other")
        void duplicateResults() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1"),
                List.of(computed("C-1", "0.00"), computed("C-1", "50.00")),
                List.of());

            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("more than one result"));
        }

        @Test
        @DisplayName("a quarantined contract is counted, and blocks the close")
        void quarantineIsCountedAndBlocks() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2"),
                List.of(computed("C-1", "0.00"), quarantined("C-2")),
                List.of());

            assertThat(aggregate.computedCount()).isEqualTo(1);
            assertThat(aggregate.quarantinedCount()).isEqualTo(1);
            assertThat(aggregate.unaccountedFor()).isZero();
            assertThat(aggregate.breaches()).isEmpty();
            assertThat(aggregate.reportsCleanClose())
                .as("04 § 3: unresolved exceptions block the close")
                .isFalse();
            assertThat(aggregate.quarantinedContracts()).containsExactly("C-2");
        }

        @Test
        @DisplayName("a whole population quarantined is not a run with nothing wrong")
        void everythingQuarantined() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2"),
                List.of(quarantined("C-1"), quarantined("C-2")),
                List.of());

            assertThat(aggregate.invariants()).isEmpty();
            assertThat(aggregate.totalClosingGca())
                .as("a total of zero and a total of nothing are different facts")
                .isEmpty();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("no contract in a population of 2 produced figures"));
        }

        @Test
        @DisplayName("contracts that computed and asserted nothing are reported")
        void computedButAssertedNothing() {
            JournalEntry entry = new JournalEntry("C-1", PERIOD, RUN, "MAIN", POSTED, List.of(
                JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("1000.00"), "accrual"),
                JournalLine.credit("4101-INTEREST-INCOME", Money.inr("1000.00"), "income")));
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1"),
                List.of(ContractResult.computed("C-1", CLOSING, entry, List.of())),
                List.of());

            // The same failure as the empty population, one level down: contracts computed,
            // nothing asserted, everything green.
            assertThat(aggregate.invariants()).isEmpty();
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("asserted no invariant at all"));
        }

        @Test
        @DisplayName("a full population, all computed, all clean")
        void aCleanClose() {
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2"),
                List.of(computed("C-1", "0.00"), computed("C-2", "0.00")),
                List.of("ROUTING_TABLE=POL-RT-2028.1"));

            assertThat(aggregate.reportsCleanClose()).isTrue();
            assertThat(aggregate.blockingReasons()).isEmpty();
            assertThat(aggregate.unaccountedFor()).isZero();
            assertThat(resultFor(aggregate, InvariantId.SL_2).detail())
                .contains("2 contract(s) asserted SL_2 and none breached");
            assertThat(aggregate.describe()).contains("clean");
            assertThat(aggregate.policyVersionIds())
                .containsExactly("ROUTING_TABLE=POL-RT-2028.1");
        }
    }

    @Nested
    @DisplayName("an obligation with no evidence, which is not the same as one with no breach")
    class ObligationsWithNoEvidence {

        @Test
        @DisplayName("a run that computed figures and asserted only SL-2 is not a clean close")
        void aSkippedObligationBlocks() {
            // Found reviewing the seam between this type and OnboardingRun, written independently.
            // OnboardingRun declared POPULATION_INVARIANTS and reported the gaps; this type derived
            // its invariant list from whatever the contracts happened to publish, so an id that
            // stopped being asserted became indistinguishable from one that passed. The existing
            // all-or-nothing check does not reach it: the invariant set here is not empty.
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1", "C-2", "C-3"),
                List.of(computedWithoutStTwo("C-1"), computedWithoutStTwo("C-2"),
                    computedWithoutStTwo("C-3")),
                List.of());

            assertThat(aggregate.breaches())
                .as("nothing is red — SL-2 passed over all three")
                .isEmpty();
            assertThat(aggregate.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.SL_2);
            assertThat(aggregate.unassertedInvariants()).containsExactly(InvariantId.ST_2);
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("asserted [ST_2]")
                    .contains("the subjects existed and the control did not run"));
        }

        @Test
        @DisplayName("an empty population owes no obligation, and says so as a population fact")
        void anEmptyPopulationOwesNothing() {
            // Conditioned on something having been computed, because an obligation cannot be owed
            // over subjects that do not exist. Two refusals for one fact would send a reader after
            // a control that did not run when the missing thing is the population.
            RunAggregate aggregate =
                RunAggregate.of(RUN, PERIOD, List.of(), List.of(), List.of());

            assertThat(aggregate.unassertedInvariants()).isEmpty();
            assertThat(aggregate.reportsCleanClose()).isFalse();
            assertThat(aggregate.blockingReasons())
                .noneSatisfy(reason -> assertThat(reason).contains("answerable for"));
        }

        @Test
        @DisplayName("a whole population quarantined owes nothing either")
        void allQuarantinedOwesNothing() {
            // Same reasoning: nothing computed, so nothing to assert an obligation over. The run
            // is blocked, and blocked for the reason that is true of it.
            RunAggregate aggregate = RunAggregate.of(
                RUN, PERIOD, List.of("C-1"), List.of(quarantined("C-1")), List.of());

            assertThat(aggregate.unassertedInvariants()).isEmpty();
            assertThat(aggregate.reportsCleanClose()).isFalse();
        }
    }
}
