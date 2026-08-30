package com.crisil.eir.application.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.replay.PublishedRun;
import com.crisil.eir.application.replay.ReplayRequest;
import com.crisil.eir.application.replay.ReplayUseCase;
import com.crisil.eir.application.replay.ReplayVerification;
import com.crisil.eir.application.replay.RunOutput;
import com.crisil.eir.application.replay.ShadowRun;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.MonthEndRun;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.application.run.SolveAudit;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayRun;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The CI-sized version of {@code tools/load-harness}: the population the harness measures, asserted
 * over a book small enough to add no meaningful time to the build.
 *
 * <h2>What is asserted here and what is deliberately not</h2>
 *
 * <p><b>Never a duration.</b> Not one assertion in this class mentions elapsed time, and that is
 * not squeamishness about flaky tests. A build that asserted "2,000 contracts in under a second"
 * would be a test of whatever else is running on the machine, and it would pass on a fast machine
 * while the 10M gate was missed by hours. Timing lives in the harness, where it is measured
 * repeatedly and reported with its spread; {@code tools/load-harness/RESULTS.md} carries the
 * numbers.
 *
 * <p><b>What is asserted is the accounting.</b> FR-905's whole argument is that "a run over
 * 10,000,000 contracts that silently processed 9,999,998 reconciles perfectly, because the two it
 * dropped are absent from both sides of every total". So the identity {@code population = computed +
 * quarantined + unaccounted} is checked, and so is the fact that a mixed synthetic book quarantines
 * nothing and breaches nothing — because a harness whose population was 4% red would be timing
 * breach-message construction and reporting it as throughput.
 *
 * <p><b>And the mix.</b> {@link Archetype}'s shares are the measurement — 05 § 3.2 makes the event
 * mix rather than N the thing the 10M target depends on — so a book that silently drifted from them
 * would invalidate every extrapolation in {@code RESULTS.md} with nothing to show it. The solve
 * count is asserted against the reset count for the same reason and one more: it is the
 * population-scale statement of 05 § 3.2's "<b>inside the event branch only</b>", which
 * {@code ContractPipelineTest} proves one contract at a time.
 */
class SyntheticBookTest {

    /**
     * Two thousand contracts: twice the interleave table, so the mix is exact.
     *
     * <p>Small enough that the whole class runs in well under a second, and {@code 2 x 1000} rather
     * than a round-looking 2,048 because the interleave is a table of a thousand slots — a size that
     * is not a multiple of it carries {@code size % 1000} contracts of drift in the mix, which is
     * fine for a timing run and would make the share assertions below approximate.
     */
    private static final int SIZE = 2_000;

    private static RunAggregate runOver(SyntheticBook book, String runId) {
        RunRequest request = book.liveRequest(runId);
        SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
        MonthEndRun run = new MonthEndRun(
            request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));
        return run.execute(new ExceptionQueue()).aggregate();
    }

    @Nested
    @DisplayName("the mix is the measurement, so the book has to carry it")
    class TheMix {

        @Test
        @DisplayName("the declared shares add up and every archetype gets its slots")
        void sharesAreExact() {
            SyntheticBook book = new SyntheticBook(SIZE);

            int total = 0;
            for (Archetype archetype : Archetype.values()) {
                // 2,000 contracts over a 1,000-slot table: exactly twice each archetype's permille.
                assertThat(book.countOf(archetype))
                    .as("%s", archetype)
                    .isEqualTo(2 * archetype.defaultPermille());
                total += book.countOf(archetype);
            }
            assertThat(total).isEqualTo(SIZE);
            assertThat(book.expectedSolves())
                .as("one solve per reset contract and nowhere else")
                .isEqualTo(2 * Archetype.EVENT_RESET.defaultPermille());
        }

        @Test
        @DisplayName("the roll-forward archetypes dominate, which is what 05 § 3.2 sizes on")
        void steadyStateDominates() {
            // "The steady-state run is overwhelmingly roll-forward arithmetic, which is what makes
            // the 10M-contract target reachable." A mix that quietly became event-heavy would
            // measure a book nobody has, so the property is pinned rather than left to the shares.
            SyntheticBook book = new SyntheticBook(SIZE);

            int withEvent = 0;
            for (Archetype archetype : Archetype.values()) {
                if (archetype.hasEvent()) {
                    withEvent += book.countOf(archetype);
                }
            }
            assertThat(withEvent).isLessThan(SIZE / 10);
            assertThat(book.expectedSolves()).isLessThan(withEvent);
        }

        @Test
        @DisplayName("every prefix of the population carries the mix, so sizes are comparable")
        void theMixIsInterleavedNotBlocked() {
            // The reason the interleave exists. Blocked assignment would put the first 1,200
            // contracts of a 2,000-contract book all in one archetype, so a 10,000-contract timing
            // and a 1,000,000-contract timing would be measuring different books and the scaling
            // question the gate turns on would be unanswerable.
            SyntheticBook book = new SyntheticBook(SIZE);

            int resetsInFirstThousand = 0;
            int resetsInSecondThousand = 0;
            for (int i = 0; i < 1_000; i++) {
                if (book.archetypeAt(i) == Archetype.EVENT_RESET) {
                    resetsInFirstThousand++;
                }
                if (book.archetypeAt(1_000 + i) == Archetype.EVENT_RESET) {
                    resetsInSecondThousand++;
                }
            }
            assertThat(resetsInFirstThousand)
                .isEqualTo(Archetype.EVENT_RESET.defaultPermille());
            assertThat(resetsInSecondThousand).isEqualTo(resetsInFirstThousand);

            // And no two adjacent slots are the same expensive path, which is what stops the JIT
            // specialising on a run of resets the book does not have.
            int adjacentResets = 0;
            for (int i = 1; i < 1_000; i++) {
                if (book.archetypeAt(i) == Archetype.EVENT_RESET
                    && book.archetypeAt(i - 1) == Archetype.EVENT_RESET) {
                    adjacentResets++;
                }
            }
            assertThat(adjacentResets).isZero();
        }

        @Test
        @DisplayName("the reset share is an argument, and a share nobody can honour is refused")
        void theResetShareIsSweepable() {
            assertThat(new SyntheticBook(SIZE, 0).expectedSolves()).isZero();
            assertThat(new SyntheticBook(SIZE, 100).expectedSolves()).isEqualTo(200);
            assertThatThrownBy(() -> new SyntheticBook(SIZE, 900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodic performing block negative");
            assertThatThrownBy(() -> new SyntheticBook(SIZE, 1_001))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("contract ids round-trip, because the ports resolve an archetype from one")
        void idsRoundTrip() {
            // Every port on the book answers by parsing the index back out of the id. An id that
            // did not round-trip would silently give one contract another's state, and both would
            // reconcile — the shape of loss FR-905's per-contract accounting exists to prevent.
            for (int index : new int[] {0, 1, 9, 10, 999, 1_000, 1_999, 9_999_999}) {
                assertThat(SyntheticBook.indexOf(SyntheticBook.contractId(index)))
                    .isEqualTo(index);
            }
            assertThat(SyntheticBook.contractId(0)).isEqualTo("LN-00000000");
            assertThat(SyntheticBook.contractId(1_234)).isEqualTo("LN-00001234");
        }
    }

    @Nested
    @DisplayName("a mixed synthetic close accounts for its population and publishes no red")
    class TheRun {

        @Test
        @DisplayName("population = computed + quarantined + unaccounted, with nothing quarantined")
        void theRunAccountsForItsPopulation() {
            RunAggregate aggregate = runOver(new SyntheticBook(SIZE), "LOAD-TEST-01");

            assertThat(aggregate.populationSize()).isEqualTo(SIZE);
            assertThat(aggregate.computedCount() + aggregate.quarantinedCount()
                + aggregate.unaccountedFor())
                .as("FR-905: every contract is computed or quarantined, and never neither")
                .isEqualTo(SIZE);
            assertThat(aggregate.quarantinedCount())
                .as("a harness population that quarantines is timing the exception path;"
                    + " quarantined: %s", aggregate.quarantinedContracts())
                .isZero();
            assertThat(aggregate.unaccountedFor()).isZero();
        }

        @Test
        @DisplayName("no invariant is red, so the timing is of arithmetic and not of breach messages")
        void nothingBreaches() {
            RunAggregate aggregate = runOver(new SyntheticBook(SIZE), "LOAD-TEST-02");

            assertThat(aggregate.breaches())
                .as("breaches: %s", aggregate.breaches())
                .isEmpty();
            assertThat(aggregate.blockingReasons())
                .as("blocking reasons: %s", aggregate.blockingReasons())
                .isEmpty();
            assertThat(aggregate.reportsCleanClose()).isTrue();
            // The obligations the aggregate declares itself answerable for, over a mixed book
            // rather than the two performing contracts MonthEndCloseTest uses.
            assertThat(aggregate.unassertedInvariants()).isEmpty();
            assertThat(aggregate.invariants()).extracting(result -> result.id())
                .contains(InvariantId.SL_2, InvariantId.ST_2, InvariantId.S3_1);
        }

        @Test
        @DisplayName("the solve count equals the reset count: 05 § 3.2's rule at population scale")
        void onlyResetsSolve() {
            // ContractPipelineTest proves this one contract at a time with an exploding solver.
            // What it cannot show is the population statement the 4-hour gate rests on: over a
            // mixed book, the number of solves is the number of RESET contracts and not one more.
            // A pipeline that re-solved every contract would produce identical figures, identical
            // balances and identical journals — the cost would be invisible to every other test in
            // this repository.
            SyntheticBook book = new SyntheticBook(SIZE);
            RunRequest request = book.liveRequest("LOAD-TEST-03");
            SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
            MonthEndRun run = new MonthEndRun(
                request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));

            MonthEndRun.Completion completion = run.execute(new ExceptionQueue());

            assertThat(audit.solveCount())
                .isEqualTo(book.expectedSolves())
                .isEqualTo(book.countOf(Archetype.EVENT_RESET));
            assertThat(completion.solveCount()).isEqualTo(audit.solveCount());
            assertThat(audit.records()).allSatisfy(record ->
                assertThat(record.reason()).contains("B5.4.5 reset"));
        }

        @Test
        @DisplayName("a book with no resets reaches no solver at all")
        void aBookWithoutResetsNeverSolves() {
            SyntheticBook book = new SyntheticBook(SIZE, 0);
            RunRequest request = book.liveRequest("LOAD-TEST-04");
            SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
            MonthEndRun run = new MonthEndRun(
                request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));

            run.execute(new ExceptionQueue());

            assertThat(audit.solveCount()).isZero();
        }

        @Test
        @DisplayName("each archetype takes the branch its share was costed on")
        void everyArchetypeTakesItsOwnBranch() {
            // Cost attribution in RESULTS.md is per archetype, so each has to actually reach the
            // path it is credited with. A catch-up archetype that quietly routed to NONE would cost
            // nothing and would still produce a green run.
            SyntheticBook book = new SyntheticBook(SIZE);
            RunRequest request = book.liveRequest("LOAD-TEST-05");
            SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
            ContractPipeline pipeline =
                new ContractPipeline(request, book, SyntheticBook.ROUTING, audit);

            for (int index = 0; index < 1_000; index++) {
                Archetype archetype = book.archetypeAt(index);
                var computation = pipeline.compute(SyntheticBook.contractId(index));
                switch (archetype) {
                    case PERFORMING_PERIODIC, PERFORMING_ACTUAL -> {
                        assertThat(computation.routing()).isNull();
                        assertThat(computation.incomeSuppressed()).isFalse();
                        assertThat(computation.solves()).isZero();
                    }
                    case STAGE_3_SUPPRESSED -> {
                        assertThat(computation.routing()).isNull();
                        assertThat(computation.incomeSuppressed()).isTrue();
                        assertThat(computation.solves()).isZero();
                    }
                    case EVENT_CATCH_UP -> {
                        assertThat(computation.routing().mechanism())
                            .isEqualTo(Mechanism.CATCH_UP);
                        assertThat(computation.catchUp()).isNotNull();
                        assertThat(computation.solves()).isZero();
                        assertThat(computation.rateMoved()).isFalse();
                    }
                    case EVENT_RESET -> {
                        assertThat(computation.routing().mechanism()).isEqualTo(Mechanism.RESET);
                        assertThat(computation.solves()).isEqualTo(1);
                        assertThat(computation.rateMoved()).isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("the periodic performing archetype reproduces reference case 1 at month 13")
        void thePerformingArchetypeIsReferenceCaseOne() {
            // The single figure that makes the whole synthetic book recognisable, and the only
            // arithmetic assertion in this class. Reference case 1, month 13: opening 528,407.32,
            // gross EIR interest 5,506.79, closing 486,840.64 — published in
            // docs/reference-cases/case-01, not read back off this code.
            SyntheticBook book = new SyntheticBook(SIZE);
            RunRequest request = book.liveRequest("LOAD-TEST-06");
            ContractPipeline pipeline = new ContractPipeline(
                request, book, SyntheticBook.ROUTING,
                SolveAudit.over(new BracketedNewtonSolver()));
            int periodic = -1;
            for (int index = 0; index < 1_000 && periodic < 0; index++) {
                if (book.archetypeAt(index) == Archetype.PERFORMING_PERIODIC) {
                    periodic = index;
                }
            }

            var computation = pipeline.compute(SyntheticBook.contractId(periodic));

            assertThat(computation.openingGca()).isEqualTo(Money.inr("528407.32"));
            assertThat(computation.row().presentedEirInterest()).isEqualTo(Money.inr("5506.79"));
            assertThat(computation.closingGca().atPresentationScale())
                .isEqualTo(Money.inr("486840.64"));
        }
    }

    @Nested
    @DisplayName("a replay of the synthetic close reproduces it byte for byte")
    class TheReplay {

        @Test
        @DisplayName("two independent executions reduce to identical published figures")
        void theReplayIsByteIdentical() {
            // The CI-sized version of the harness's REPLAY phase and of Phase 5's second clause.
            // The published side is the first run's figures; the shadow side is a SECOND,
            // independent execution of the engine at the replay boundary. Both go through
            // ShadowRun, which is the point: the reducer is not the subject, the arithmetic is, and
            // two executions reduced identically agree only if they computed identically.
            //
            // What this cannot show, and ReplayUseCaseTest can: the persistence round-trip. A scale
            // lost on the way into a shadow table would not appear here.
            SyntheticBook book = new SyntheticBook(SIZE);
            RunAggregate published = runOver(book, SyntheticBook.RUN_ID);
            Map<String, Money> publishedFigures = ShadowRun.figures(published.results());

            PublishedRun publishedRun = new PublishedRun(
                ReplayRun.published(
                    SyntheticBook.RUN_ID, SyntheticBook.PERIOD_ID, publishedFigures,
                    book.policyStamps()),
                ClosedPeriod.month(
                    YearMonth.of(2028, 5), LocalDate.of(2028, 6, 5), "financial.controller"),
                SyntheticBook.KNOWN_AT, SyntheticBook.BOOK_ID);
            ReplayUseCase replay = new ReplayUseCase(request -> {
                SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
                MonthEndRun run = new MonthEndRun(
                    request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));
                return RunOutput.of(
                    run.execute(new ExceptionQueue()).aggregate().results(), book.policyStamps());
            });

            ReplayVerification verification = replay.replay(
                new ReplayRequest(publishedRun, book.liveRequest("LOAD-TEST-TEMPLATE")),
                ReplayRequest.shadowRunId(SyntheticBook.RUN_ID, LocalDate.of(2028, 8, 10)));

            assertThat(verification.comparison().isBitIdentical()).isTrue();
            assertThat(verification.provesReproduction()).isTrue();
            assertThat(verification.comparison().discrepancyCount()).isZero();
            // Five figures a contract — a closing balance and four journal lines on the performing
            // ones — so the denominator is stated rather than left to a pass over nothing.
            assertThat(verification.comparison().figuresCompared())
                .isGreaterThanOrEqualTo(SIZE * 4);
            assertThat(verification.population().addsUp()).isTrue();
            assertThat(verification.population().populationSize()).isEqualTo(SIZE);
        }
    }

    @Nested
    @DisplayName("the port that has to materialise the whole book")
    class ThePopulationPort {

        @Test
        @DisplayName("contractIdsInScope answers the whole population, in order, once each")
        void thePopulationIsEnumeratedInOrder() {
            // Reported in RESULTS.md as a cost rather than a defect: ContractSource returns
            // List<String>, so a 10M-contract close materialises ten million strings before a
            // figure is computed. FailureIsolation.runBatch then builds a HashSet over the same
            // ids for its duplicate pass and a LinkedHashMap for the results, so the population
            // exists three times over at peak. That is what the heap figures measure.
            var ids = new SyntheticBook(SIZE).contractIdsInScope(SyntheticBook.boundary());

            assertThat(ids).hasSize(SIZE).doesNotHaveDuplicates();
            assertThat(ids.get(0)).isEqualTo(SyntheticBook.contractId(0));
            assertThat(ids.get(SIZE - 1)).isEqualTo(SyntheticBook.contractId(SIZE - 1));
        }

        @Test
        @DisplayName("a contract outside the book has no state, which is FR-905's data condition")
        void aContractOutsideTheBookHasNoState() {
            SyntheticBook book = new SyntheticBook(SIZE);

            assertThat(book.openingState(SyntheticBook.contractId(SIZE), SyntheticBook.boundary()))
                .isEmpty();
            assertThat(book.openingState(SyntheticBook.contractId(0), SyntheticBook.boundary()))
                .isPresent();
        }
    }

    @Nested
    @DisplayName("the figures every contract publishes")
    class ThePublishedFigures {

        @Test
        @DisplayName("a Stage 3 contract publishes a balance and suppresses recognition")
        void stageThreeSuppresses() {
            SyntheticBook book = new SyntheticBook(SIZE);
            RunAggregate aggregate = runOver(book, "LOAD-TEST-07");

            int suppressed = 0;
            for (ContractResult result : aggregate.results()) {
                assertThat(result.isComputed()).isTrue();
                assertThat(result.closingGca()).isNotNull();
                if (book.archetypeAt(SyntheticBook.indexOf(result.contractId()))
                    == Archetype.STAGE_3_SUPPRESSED) {
                    suppressed++;
                }
            }
            assertThat(suppressed)
                .isEqualTo(book.countOf(Archetype.STAGE_3_SUPPRESSED))
                .isPositive();
        }
    }
}
