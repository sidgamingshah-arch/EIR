package com.crisil.eir.application.transition;

import static com.crisil.eir.application.transition.TransitionFixtures.ASSERTED_AS_OF;
import static com.crisil.eir.application.transition.TransitionFixtures.CARRYING_AMOUNT;
import static com.crisil.eir.application.transition.TransitionFixtures.DCF;
import static com.crisil.eir.application.transition.TransitionFixtures.EIR;
import static com.crisil.eir.application.transition.TransitionFixtures.PERIOD_ID;
import static com.crisil.eir.application.transition.TransitionFixtures.PRESUMED;
import static com.crisil.eir.application.transition.TransitionFixtures.QUOTED;
import static com.crisil.eir.application.transition.TransitionFixtures.RUNOFF_COHORT;
import static com.crisil.eir.application.transition.TransitionFixtures.RUN_ID;
import static com.crisil.eir.application.transition.TransitionFixtures.SURVIVOR_COHORT;
import static com.crisil.eir.application.transition.TransitionFixtures.TOTAL_DIFFERENCE;
import static com.crisil.eir.application.transition.TransitionFixtures.TRANSITION_DATE;
import static com.crisil.eir.application.transition.TransitionFixtures.approvedDerivation;
import static com.crisil.eir.application.transition.TransitionFixtures.boundary;
import static com.crisil.eir.application.transition.TransitionFixtures.cleanMembership;
import static com.crisil.eir.application.transition.TransitionFixtures.cleanSource;
import static com.crisil.eir.application.transition.TransitionFixtures.cleanStates;
import static com.crisil.eir.application.transition.TransitionFixtures.runoffCohort;
import static com.crisil.eir.application.transition.TransitionFixtures.staffLoan;
import static com.crisil.eir.application.transition.TransitionFixtures.staffLoanPosition;
import static com.crisil.eir.application.transition.TransitionFixtures.survivorCohort;
import static com.crisil.eir.application.transition.TransitionFixtures.unapprovedDerivation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.transition.TransitionFixtures.FixedPopulation;
import com.crisil.eir.application.transition.TransitionFixtures.MapStateSource;
import com.crisil.eir.application.transition.TransitionFixtures.MapTransitionSource;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationMethod;
import com.crisil.eir.policy.transition.ValuationTechnique;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The transition exercise: the ACPIR 19 valuation over a population, the two migration obligations,
 * and the five controls that previously had no caller anywhere in {@code src/main}.
 *
 * <p><b>What every expected figure here is derived from.</b> The pre-transition carrying amount is
 * reference case 1's published month-13 opening balance, 528,407.32; the three differences and their
 * total are subtractions stated in {@link TransitionFixtures}'s javadoc; the coverage counts are
 * counted off the three fixture contracts by hand. Nothing is taken from a run of the code.
 *
 * <p><b>The failing input is named at each control.</b> A control that cannot fail is worse than an
 * absent one, so each of TF-1, LC-1, DE-1, TM-1 and BM-1 is driven both ways here, and the input
 * that breaches it is constructed rather than described.
 */
class TransitionExerciseTest {

    private static TransitionRequest request(
        ContractSource population, MapStateSource states, MapTransitionSource source) {
        return new TransitionRequest(RUN_ID, PERIOD_ID, TRANSITION_DATE, boundary(),
            population, states, source);
    }

    /** The plan the clean population is measured against: survivor first, run-off second. */
    private static LegacyMigrationPlan cleanPlan() {
        return LegacyMigrationPlan.of(
            List.of(survivorCohort(1), runoffCohort(2)), List.of(approvedDerivation()));
    }

    private static TransitionRun perform(
        MapStateSource states, MapTransitionSource source, LegacyMigrationPlan plan,
        AssertedCohortMembership membership, List<BelowMarketOrigination> belowMarket,
        ExceptionQueue queue, List<String> population) {
        return TransitionExercise.perform(
            request(new FixedPopulation(population), states, source),
            plan, membership, belowMarket, queue);
    }

    private static TransitionRun cleanRun(ExceptionQueue queue) {
        return perform(cleanStates(), cleanSource(), cleanPlan(), cleanMembership(), List.of(),
            queue, List.of(QUOTED, DCF, PRESUMED));
    }

    @Nested
    @DisplayName("a population where every contract values cleanly")
    class TheCleanPopulation {

        @Test
        @DisplayName("three contracts valued, and the difference to opening retained earnings is"
            + " -36,814.64")
        void everyContractIsValuedAndTheTotalIsTheSumOfTheThreeDifferences() {
            ExceptionQueue queue = new ExceptionQueue();

            TransitionRun run = cleanRun(queue);

            assertThat(run.populationSize()).isEqualTo(3);
            assertThat(run.valuedCount()).isEqualTo(3);
            assertThat(run.isolatedCount()).isZero();
            assertThat(queue.isEmpty())
                .as("nothing was isolated, so nothing reached the queue")
                .isTrue();
            assertThat(run.totalDifferenceToOpeningRetainedEarnings().atPresentationScale())
                .as("500,000.00 - 528,407.32 = -28,407.32; 520,000.00 - 528,407.32 = -8,407.32;"
                    + " 528,407.32 - 528,407.32 = 0.00; and -28,407.32 + -8,407.32 = -36,814.64."
                    + " To OPENING RETAINED EARNINGS, never to a period result (ACPIR 19)")
                .isEqualTo(TOTAL_DIFFERENCE.atPresentationScale());
            assertThat(run.reportsCompleteExercise())
                .as("the population was accounted for, valued, planned, placed, and every"
                    + " obligation with a subject was established cleanly: " + run.blockingReasons())
                .isTrue();
        }

        @Test
        @DisplayName("the carrying amount comes from the book and the fair value from the valuer")
        void theTwoSidesOfTheDifferenceComeFromTwoSources() {
            ExceptionQueue queue = new ExceptionQueue();

            TransitionRun run = cleanRun(queue);

            ContractTransitionOutcome quoted = run.outcomes().getFirst();
            assertThat(quoted.contractId()).isEqualTo(QUOTED);
            assertThat(quoted.valuation().preTransitionCarryingAmount())
                .as("the pre-transition carrying amount is the opening gross carrying amount the"
                    + " ContractStateSource holds, not a figure the valuation supplied — a source"
                    + " that gave both sides could set the difference to any number, nil included,"
                    + " by moving the side the ledger already carries")
                .isEqualTo(CARRYING_AMOUNT);
            assertThat(quoted.valuation().fairValue()).isEqualTo(Money.inr("500000.00"));
            assertThat(quoted.differenceToOpeningRetainedEarnings().atPresentationScale())
                .isEqualTo(Money.inr("-28407.32").atPresentationScale());
            assertThat(quoted.valuation().transitionDate())
                .as("valued at the transition date, which is an argument and not the boundary's"
                    + " business date — the exercise is asserted at " + ASSERTED_AS_OF
                    + " and values at " + TRANSITION_DATE)
                .isEqualTo(TRANSITION_DATE);
        }

        @Test
        @DisplayName("each contract's measurement is read exactly once")
        void thePortIsAskedOncePerContract() {
            MapTransitionSource source = cleanSource();

            perform(cleanStates(), source, cleanPlan(), cleanMembership(), List.of(),
                new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            assertThat(source.measurementCalls())
                .as("three contracts, three reads; a pipeline reading twice would produce identical"
                    + " figures and double the source load over a ten-million-contract book")
                .isEqualTo(3);
            assertThat(source.measurementsAsked()).containsExactlyInAnyOrder(QUOTED, DCF, PRESUMED);
        }

        @Test
        @DisplayName("ACPIR 21 is 3 of 3 and ACPIR 50 is 1 of 3 over the same three contracts")
        void thetwoObligationsAreMeasuredSeparatelyOverOnePopulation() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            assertThat(run.coverage().contractsTracked()).isEqualTo(3);
            assertThat(run.coverage().untrackedContracts()).isZero();
            assertThat(run.coverage().acpir21().contractsSatisfying())
                .as("all three contracts carry a rate in force, so all three are under the EIR"
                    + " regime for recognition")
                .isEqualTo(3);
            assertThat(run.coverage().acpir21().contractsOutstanding()).isZero();
            assertThat(run.coverage().acpir50().contractsSatisfying())
                .as("only C-QUOTED's ECL discounting has moved to the EIR; the other two are on the"
                    + " interim contractual rate. Merged with ACPIR 21's figure this book would read"
                    + " 4 of 6 and describe neither obligation (04 § 6)")
                .isEqualTo(1);
            assertThat(run.coverage().acpir50().contractsOutstanding()).isEqualTo(2);
            assertThat(run.coverage().underAcpir50Concession())
                .as("both outstanding contracts are already on the EIR for interest, which is"
                    + " exactly the shape ACPIR 50's concession describes")
                .isEqualTo(2);
            assertThat(run.coverage().eclAheadOfInterest()).isZero();
        }

        @Test
        @DisplayName("the technique mix is published, because a 99.9% carrying-cost run has measured"
            + " almost nothing")
        void theTechniqueMixIsVisible() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            assertThat(run.valuationRun().techniqueMix())
                .containsEntry(ValuationTechnique.QUOTED_PRICE, 1L)
                .containsEntry(ValuationTechnique.DISCOUNTED_CASH_FLOW, 1L)
                .containsEntry(ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, 1L);
        }
    }

    @Nested
    @DisplayName("one contract fails, the rest complete, and the count still adds up")
    class TheIsolatedContract {

        /**
         * The failing input, named: a contract the population names whose ACPIR 19 measurement is
         * not on file. Defaulting it to carrying cost would silently apply the paragraph 19
         * presumption on the contract's behalf, produce a difference of exactly nil, and be counted
         * by TF-1 as a presumption somebody chose.
         */
        @Test
        @DisplayName("a contract with no fair value measurement is isolated and counted, not skipped")
        void aMissingMeasurementIsIsolatedAndTheOthersComplete() {
            MapTransitionSource source = cleanSource();
            MapStateSource states = cleanStates().with("C-UNMEASURED", TransitionFixtures.state(EIR));
            ExceptionQueue queue = new ExceptionQueue();

            TransitionRun run = perform(states, source, cleanPlan(), cleanMembership(), List.of(),
                queue, List.of(QUOTED, DCF, "C-UNMEASURED", PRESUMED));

            assertThat(run.populationSize()).isEqualTo(4);
            assertThat(run.valuedCount()).isEqualTo(3);
            assertThat(run.isolatedCount()).isEqualTo(1);
            assertThat(run.valuedCount() + run.isolatedCount())
                .as("FR-905: every contract in the population is valued or isolated, and the count"
                    + " has to add up — an exercise short by one contract reconciles perfectly,"
                    + " because that contract is absent from both sides of every total")
                .isEqualTo(run.populationSize());
            assertThat(run.isolated().getFirst().contractId()).isEqualTo("C-UNMEASURED");
            assertThat(run.isolated().getFirst().category())
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(run.isolated().getFirst().raisedByRunId()).isEqualTo(RUN_ID);
            assertThat(queue.isQuarantined("C-UNMEASURED")).isTrue();

            assertThat(run.totalDifferenceToOpeningRetainedEarnings().atPresentationScale())
                .as("the three that valued still total -36,814.64; the isolated contract"
                    + " contributes nothing to the total and is nonetheless in the population")
                .isEqualTo(TOTAL_DIFFERENCE.atPresentationScale());
            assertThat(run.outcomes()).extracting(ContractTransitionOutcome::contractId)
                .as("the contracts after the failing one were valued — FR-905's actual guarantee")
                .containsExactly(QUOTED, DCF, PRESUMED);
        }

        @Test
        @DisplayName("an isolated contract is untracked, so TM-1 breaches with a deviation of 1")
        void anIsolatedContractIsUntrackedRatherThanOutstanding() {
            MapStateSource states = cleanStates().with("C-UNMEASURED", TransitionFixtures.state(EIR));
            ExceptionQueue queue = new ExceptionQueue();

            TransitionRun run = perform(states, cleanSource(), cleanPlan(), cleanMembership(),
                List.of(), queue, List.of(QUOTED, DCF, "C-UNMEASURED", PRESUMED));

            assertThat(run.coverage().contractsInPopulation()).isEqualTo(4);
            assertThat(run.coverage().contractsTracked()).isEqualTo(3);
            assertThat(run.coverage().untrackedContracts())
                .as("the population is 4 and 3 positions were recorded; a tracker built over the"
                    + " positions rather than the population would report nil untracked and a"
                    + " complete migration over the contracts it happened to see (04 § 6)")
                .isEqualTo(1);

            InvariantResult tm1 = only(run, InvariantId.TM_1);
            assertThat(tm1.satisfied()).isFalse();
            assertThat(tm1.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(tm1.detail())
                .as("untracked is the worse of TM-1's two legs: the contract's position is unknown"
                    + " rather than outstanding")
                .contains("no recorded ECL discount basis");
            assertThat(run.reportsCompleteExercise()).isFalse();
            assertThat(run.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("were isolated"));
        }

        @Test
        @DisplayName("a contract with no recorded ECL discount basis is isolated, not assumed onto"
            + " the interim basis")
        void anUnrecordedEclBasisIsNotAssumed() {
            MapTransitionSource source = cleanSource().withoutEcl(DCF);
            ExceptionQueue queue = new ExceptionQueue();

            TransitionRun run = perform(cleanStates(), source, cleanPlan(), cleanMembership(),
                List.of(), queue, List.of(QUOTED, DCF, PRESUMED));

            assertThat(run.isolatedCount()).isEqualTo(1);
            assertThat(run.isolated().getFirst().detail())
                .as("assuming the interim basis would move a contract nobody has assessed into the"
                    + " population that ACPIR 50's concession permits, where it would look like"
                    + " progress")
                .contains("no recorded ECL discount basis");
            assertThat(run.coverage().untrackedContracts()).isEqualTo(1);
        }

        @Test
        @DisplayName("a duplicate contract in the population fails the exercise before anything is"
            + " valued")
        void aDuplicateIsRefusedUpFront() {
            ExceptionQueue queue = new ExceptionQueue();

            assertThatThrownBy(() -> perform(cleanStates(), cleanSource(), cleanPlan(),
                cleanMembership(), List.of(), queue, List.of(QUOTED, DCF, QUOTED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
            assertThat(queue.isEmpty())
                .as("checked as a pass of its own before the loop, so a duplicate never leaves the"
                    + " caller's queue holding entries from an exercise whose results were then"
                    + " discarded")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("a cohort whose expected run-off is after 31 March 2030")
    class TheSurvivingCohort {

        @Test
        @DisplayName("the survivor is identified and its supplied contract count is reported as"
            + " supplied")
        void survivorsAreCountedAndTheCountIsFlaggedAsAsserted() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            assertThat(survivorCohort(1).survivesAcpir50Deadline())
                .as("run-off 31 March 2032 is strictly after the 31 March 2030 deadline, so this"
                    + " exposure needs a rate — which is the case the whole deadline tracking"
                    + " exists for")
                .isTrue();
            assertThat(runoffCohort(2).survivesAcpir50Deadline())
                .as("run-off 30 June 2029: reconstruction capacity spent here buys nothing")
                .isFalse();
            assertThat(run.coverage().survivingCohortCount()).isEqualTo(1);
            assertThat(run.coverage().plannedContractsRequiringMigration())
                .as("the survivor cohort's own contractCount of 2, summed by the plan; SUPPLIED,"
                    + " because nothing in this engine evaluates LegacyCohort.definition")
                .isEqualTo(2);
            assertThat(run.coverage().describe())
                .as("the caveat has to travel with the figure, or a supplied count becomes a"
                    + " measured one over two release cycles")
                .contains(AssertedCohortMembership.BASIS);
            assertThat(run.coverage().plannedLessMeasured())
                .as("2 planned against a measured population of 3, signed: the two figures are"
                    + " published unreconciled rather than added, and this is not a defect claim"
                    + " about either")
                .isEqualTo(-1);
        }

        /**
         * The failing input, named: the surviving cohort queued at priority 3 behind a cohort at
         * priority 1 that runs off in June 2029. That is the natural prioritisation — largest or
         * most convenient first — and it spends the scarce reconstruction capacity on balances that
         * will have gone.
         */
        @Test
        @DisplayName("a survivor queued behind a cohort that runs off first breaches LC-1")
        void lc1BreachesWhenASurvivorIsQueuedBehindARunoff() {
            LegacyMigrationPlan misordered = LegacyMigrationPlan.of(
                List.of(survivorCohort(3), runoffCohort(1)), List.of(approvedDerivation()));

            TransitionRun run = perform(cleanStates(), cleanSource(), misordered, cleanMembership(),
                List.of(), new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            InvariantResult lc1 = only(run, InvariantId.LC_1);
            assertThat(lc1.satisfied()).isFalse();
            assertThat(lc1.deviation())
                .as("one cohort inverted; the deviation is a count because the remedy is a"
                    + " re-ordering")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(lc1.detail()).contains(SURVIVOR_COHORT);
            assertThat(run.reportsCompleteExercise()).isFalse();
        }

        @Test
        @DisplayName("reconstruction aimed at a cohort that runs off early is reported, and does not"
            + " block")
        void wastedEffortIsDataAndNotABlock() {
            LegacyCohort wasteful = new LegacyCohort("AUTO-2024-RECONSTRUCT",
                "product = AUTO_LOAN and maturity < 2030-01-01",
                LocalDate.of(2028, 4, 1), TransitionFixtures.RUNS_OFF_EARLY, 2,
                MigrationMethod.FULL_RECONSTRUCTION, 1L, "programme.head");
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(List.of(survivorCohort(1), wasteful));

            TransitionRun run = perform(cleanStates(), cleanSource(), plan,
                AssertedCohortMembership.asserted(Map.of(
                    QUOTED, SURVIVOR_COHORT, DCF, SURVIVOR_COHORT,
                    PRESUMED, "AUTO-2024-RECONSTRUCT")),
                List.of(), new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            assertThat(run.wastedReconstructionEffort())
                .as("the roadmap's own sentence: reconstructing an EIR for a loan maturing in 2029"
                    + " is wasted effort")
                .hasSize(1);
            assertThat(run.blockingReasons())
                .as("spending effort badly is not an accounting breach, and blocking on it would"
                    + " put a programme management question beside a figure that does not tie")
                .noneSatisfy(reason -> assertThat(reason).contains("wasted"));
            assertThat(run.reportsCompleteExercise()).isTrue();
        }
    }

    @Nested
    @DisplayName("the deemed EIR: derived per the plan's method, and asserted by DE-1 only")
    class TheDeemedRate {

        @Test
        @DisplayName("the deemed cohort's contract takes the derivation's rate; the reconstructed"
            + " ones take their own")
        void theMethodDecidesWhereTheRateComesFrom() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            LegacyRateAssignment quoted = run.outcomes().getFirst().rateAssignment();
            assertThat(quoted.basis()).isEqualTo(LegacyRateBasis.RECONSTRUCTED);
            assertThat(quoted.rate())
                .as("a solved rate already exists on the contract's state; re-solving it here would"
                    + " produce a second EIR for one contract")
                .isEqualTo(EIR);
            assertThat(quoted.assertedCohortName()).isEqualTo(SURVIVOR_COHORT);

            LegacyRateAssignment presumed = run.outcomes().getLast().rateAssignment();
            assertThat(presumed.basis()).isEqualTo(LegacyRateBasis.DEEMED);
            assertThat(presumed.rate())
                .as("the cohort's decision is to measure on the deemed rate, so the derivation's"
                    + " rate governs and not whatever rate the state happens to carry")
                .isEqualTo(TransitionFixtures.MARKET_RATE);
            assertThat(presumed.derivation()).isEqualTo(approvedDerivation());
            assertThat(presumed.isOutstanding()).isFalse();
        }

        @Test
        @DisplayName("a contract queued for reconstruction with no rate yet is outstanding, and does"
            + " not block")
        void reconstructionOutstandingIsNotABreach() {
            MapStateSource states = cleanStates().with(DCF, TransitionFixtures.state(null));

            TransitionRun run = perform(states, cleanSource(), cleanPlan(), cleanMembership(),
                List.of(), new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            assertThat(run.outstandingMigrationWork())
                .extracting(LegacyRateAssignment::basis)
                .containsExactly(LegacyRateBasis.RECONSTRUCTION_OUTSTANDING);
            assertThat(run.reportsCompleteExercise())
                .as("between 1 April 2027 and 31 March 2030 an unfinished reconstruction is what"
                    + " the migration looks like; blocking on it would make the pack red for three"
                    + " years, and a control red by design gets suppressed")
                .isTrue();
        }

        /**
         * The failing input, named: the same auto cohort on a deemed rate whose derivation nobody
         * has signed. A deemed rate recognises income on an assumption every period for the rest of
         * the exposure's life, so preparing one is analysis and measuring a cohort on it is a
         * decision.
         */
        @Test
        @DisplayName("an unsigned derivation breaches DE-1, and the contract carries no rate")
        void de1BreachesOnAnUnsignedDerivation() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(survivorCohort(1), runoffCohort(2)), List.of(unapprovedDerivation()));

            TransitionRun run = perform(cleanStates(), cleanSource(), plan, cleanMembership(),
                List.of(), new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            InvariantResult de1 = only(run, InvariantId.DE_1);
            assertThat(de1.satisfied()).isFalse();
            assertThat(de1.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(de1.detail()).contains("unapproved").contains(RUNOFF_COHORT);

            LegacyRateAssignment presumed = run.outcomes().getLast().rateAssignment();
            assertThat(presumed.basis()).isEqualTo(LegacyRateBasis.DEEMED_UNBACKED);
            assertThat(presumed.hasRate())
                .as("an unsigned derivation's figure stays on the derivation, where a reader sees it"
                    + " is unapproved, rather than in a field named `rate` that reads as the rate in"
                    + " force")
                .isFalse();
            assertThat(presumed.derivation()).isNotNull();
            assertThat(run.reportsCompleteExercise()).isFalse();
        }

        @Test
        @DisplayName("a contract in no asserted cohort has no method, and that blocks")
        void anUnplacedContractBlocks() {
            TransitionRun run = perform(cleanStates(), cleanSource(), cleanPlan(),
                AssertedCohortMembership.none(), List.of(), new ExceptionQueue(),
                List.of(QUOTED, DCF, PRESUMED));

            assertThat(run.outcomes())
                .extracting(outcome -> outcome.rateAssignment().basis())
                .containsOnly(LegacyRateBasis.UNASSIGNED);
            assertThat(run.blockingReasons())
                .as("a contract no cohort claims is absent from every cohort's contract count, so"
                    + " nothing says how it reaches the EIR by 31 March 2030")
                .anySatisfy(reason -> assertThat(reason).contains("in no asserted cohort"));
            assertThat(run.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains(AssertedCohortMembership.BASIS));
            assertThat(run.reportsCompleteExercise()).isFalse();
        }
    }

    @Nested
    @DisplayName("TM-1 is not red by design before the deadline, and is red the day after it")
    class TheDeliberatelyNotRedControl {

        @Test
        @DisplayName("two contracts under the ACPIR 50 concession in FY29: TM-1 PASSES")
        void tm1PassesWhileTheConcessionIsStillRunning() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            InvariantResult tm1 = only(run, InvariantId.TM_1);
            assertThat(tm1.satisfied())
                .as("asserted as at " + ASSERTED_AS_OF + ", a year before 31 March 2030, with two"
                    + " of three contracts still discounting ECL at the contractual rate. ACPIR 50"
                    + " explicitly concedes that basis until the deadline, and TM-1 asserts that"
                    + " both obligations are TRACKED rather than that both are FINISHED — a control"
                    + " that failed continuously from 2027 to 2030 would block every close for"
                    + " three years and be suppressed (InvariantId.TM_1, 03 § 9). Do not tighten"
                    + " this")
                .isTrue();
            assertThat(tm1.detail())
                .as("the remaining migration is in the detail as plain data, which is what a"
                    + " programme tracks")
                .contains("still to migrate under the ACPIR 50 concession");
            assertThat(run.coverage().underAcpir50Concession()).isEqualTo(2);
        }

        /**
         * The failing input, named: the same clean population asserted as at 1 April 2030, one day
         * after the deadline, with two contracts still on the interim contractual basis. That is
         * the day the concession expires and the identical book becomes a breach.
         */
        @Test
        @DisplayName("the same book asserted on 1 April 2030: TM-1 breaches on the two unmigrated"
            + " contracts")
        void tm1BreachesOnceTheConcessionHasExpired() {
            TransitionRequest afterDeadline = new TransitionRequest(RUN_ID, 203004, TRANSITION_DATE,
                AsAtBoundary.live(LocalDate.of(2030, 4, 1), TransitionFixtures.KNOWN_AT),
                new FixedPopulation(List.of(QUOTED, DCF, PRESUMED)), cleanStates(), cleanSource());

            TransitionRun run = TransitionExercise.perform(
                afterDeadline, cleanPlan(), cleanMembership(), List.of(), new ExceptionQueue());

            InvariantResult tm1 = only(run, InvariantId.TM_1);
            assertThat(tm1.satisfied()).isFalse();
            assertThat(tm1.deviation())
                .as("two contracts remain on the interim basis after 31 March 2030")
                .isEqualByComparingTo(new BigDecimal("2"));
            assertThat(tm1.detail()).contains("after the 31 March 2030 deadline");
            assertThat(run.coverage().acpir50().isInBreach(LocalDate.of(2030, 4, 1)))
                .as("the coverage report says the same thing, from the other side")
                .isTrue();
            assertThat(run.coverage().acpir21().isInBreach(LocalDate.of(2030, 4, 1)))
                .as("and ACPIR 21 is satisfied on the same book on the same day, which is exactly"
                    + " why the two are not one figure")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("BM-1 is published only where there is a below-market origination to assert it over")
    class TheBelowMarketDestination {

        @Test
        @DisplayName("no originations presented: BM-1 is not published at all, not published as a"
            + " pass")
        void bm1IsNotAssertedOverNothing() {
            TransitionRun run = cleanRun(new ExceptionQueue());

            assertThat(run.belowMarketCount()).isZero();
            assertThat(run.invariants()).extracting(InvariantResult::id)
                .as("BelowMarketOrigination.destinationsApproved(List.of()) returns a PASS reading"
                    + " '0 below-market originations, all with an approved day-1 destination'. True,"
                    + " and published on a book that presented none it is a green control over"
                    + " nothing — the aggregation-over-nothing defect at the level of a single id")
                .containsExactly(InvariantId.TF_1, InvariantId.LC_1, InvariantId.DE_1,
                    InvariantId.TM_1)
                .doesNotContain(InvariantId.BM_1);
            assertThat(run.unassertedInvariants())
                .as("and BM-1 is not reported as a gap either: a bank with no below-market"
                    + " originations legitimately asserts it nowhere, and an obligation red on every"
                    + " such book gets argued down to a soft one within a quarter")
                .isEmpty();
            assertThat(run.describe()).contains("BM-1 not asserted, nothing to assert it over");
        }

        @Test
        @DisplayName("a staff loan with an approved position: BM-1 passes over one origination")
        void bm1PassesOnAnApprovedDestination() {
            TransitionRun run = perform(cleanStates(), cleanSource(), cleanPlan(), cleanMembership(),
                List.of(staffLoan("C-STAFF-1", staffLoanPosition())), new ExceptionQueue(),
                List.of(QUOTED, DCF, PRESUMED));

            InvariantResult bm1 = only(run, InvariantId.BM_1);
            assertThat(bm1.satisfied()).isTrue();
            assertThat(run.belowMarketCount()).isEqualTo(1);
            assertThat(run.reportsCompleteExercise()).isTrue();
        }

        /**
         * The second failing input, and the subtler one: a position signed off but not yet in force.
         * {@code PolicyVersionStatus} separates approval from effect on purpose — "a version
         * approved in March to take effect on 1 April is approved and not yet operative" — so a
         * destination resting on it is a destination nothing authorised on the day the loan was
         * written. A boolean {@code approved} flag would have reported this as fine.
         */
        @Test
        @DisplayName("a position approved but not yet in force does not authorise the destination")
        void bm1BreachesOnAPositionNotYetInForce() {
            TransitionRun run = perform(cleanStates(), cleanSource(), cleanPlan(), cleanMembership(),
                List.of(staffLoan("C-STAFF-1", TransitionFixtures.approvedButNotInForcePosition())),
                new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            InvariantResult bm1 = only(run, InvariantId.BM_1);
            assertThat(bm1.satisfied()).isFalse();
            assertThat(bm1.detail()).contains("not in force");
            assertThat(run.reportsCompleteExercise()).isFalse();
        }

        /**
         * The failing input, named: the same staff loan with no Board position behind its
         * destination. Reference § 4 Silence 6 at {@code [MED-HIGH]} — ACPIR 19 and 20 say nothing
         * about the day-1 difference, so a destination reached because that is where the code sent
         * it is a policy decision taken by an implementation detail, on 200,000.00 per loan.
         */
        @Test
        @DisplayName("a staff loan with no position behind its destination breaches BM-1")
        void bm1BreachesOnAnUnapprovedDestination() {
            TransitionRun run = perform(cleanStates(), cleanSource(), cleanPlan(), cleanMembership(),
                List.of(staffLoan("C-STAFF-1", null)), new ExceptionQueue(),
                List.of(QUOTED, DCF, PRESUMED));

            InvariantResult bm1 = only(run, InvariantId.BM_1);
            assertThat(bm1.satisfied()).isFalse();
            assertThat(bm1.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(bm1.detail())
                .as("1,000,000.00 disbursed less a fair value of 800,000.00 is a shortfall of"
                    + " 200,000.00, and the deviation is a count because the remedy is per loan")
                .contains("200000.00");
            assertThat(run.reportsCompleteExercise()).isFalse();
        }
    }

    @Nested
    @DisplayName("TF-1, and the exercise refusing a population it did not measure")
    class TheEvidenceAndTheEmptyPopulation {

        /**
         * The failing input, named: the presumed contract with its rebuttal evidence reference
         * removed. Its difference to opening retained earnings then comes out at exactly nil, which
         * is indistinguishable from a valuation that was performed and found no difference.
         */
        @Test
        @DisplayName("a paragraph 19 presumption with no evidence reference breaches TF-1")
        void tf1BreachesOnAnUnevidencedPresumption() {
            MapTransitionSource source = cleanSource().withMeasurement(PRESUMED,
                new TransitionSource.FairValueMeasurement(CARRYING_AMOUNT,
                    ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null, null,
                    "valuer.one", "reviewer.one"));

            TransitionRun run = perform(cleanStates(), source, cleanPlan(), cleanMembership(),
                List.of(), new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            InvariantResult tf1 = only(run, InvariantId.TF_1);
            assertThat(tf1.satisfied()).isFalse();
            assertThat(tf1.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(tf1.detail()).contains("1 of 1 paragraph 19 presumptions");
            assertThat(run.reportsCompleteExercise()).isFalse();
        }

        @Test
        @DisplayName("an empty population publishes no invariant at all and reports every obligation"
            + " as a gap")
        void anEmptyPopulationEstablishesNothing() {
            TransitionRun run = perform(cleanStates(), cleanSource(), cleanPlan(), cleanMembership(),
                List.of(), new ExceptionQueue(), List.of());

            assertThat(run.invariants())
                .extracting(InvariantResult::id)
                .as("TF-1 over no valuations, and TM-1 over no positions, both return a PASS: '0 of"
                    + " 0 valuations ... all on named evidence' and '0 contracts all carry a"
                    + " recorded ECL discount basis'. Publishing either would be five green"
                    + " controls over an exercise that measured nothing")
                .doesNotContain(InvariantId.TF_1, InvariantId.TM_1);
            assertThat(run.unassertedInvariants())
                .as("reported as named gaps rather than as failed results: marking TF-1 breached"
                    + " because nobody evaluated it would be a false statement about the book in"
                    + " service of a true one about the run")
                .containsExactly(InvariantId.TF_1, InvariantId.TM_1);
            assertThat(run.blockingReasons())
                .anySatisfy(reason -> assertThat(reason)
                    .contains("accounted for no contracts at all"));
            assertThat(run.reportsCompleteExercise()).isFalse();
            assertThat(run.totalDifferenceToOpeningRetainedEarnings())
                .isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("a plan with no cohort blocks: LC-1 and DE-1 have no subject")
        void anEmptyPlanBlocks() {
            TransitionRun run = perform(cleanStates(), cleanSource(),
                LegacyMigrationPlan.of(List.of()), AssertedCohortMembership.none(), List.of(),
                new ExceptionQueue(), List.of(QUOTED, DCF, PRESUMED));

            assertThat(run.invariants()).extracting(InvariantResult::id)
                .doesNotContain(InvariantId.LC_1, InvariantId.DE_1);
            assertThat(run.unassertedInvariants())
                .containsExactly(InvariantId.LC_1, InvariantId.DE_1);
            assertThat(run.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("defines no cohort"));
            assertThat(run.reportsCompleteExercise()).isFalse();
        }

        @Test
        @DisplayName("a run whose valued and isolated lists do not partition the population cannot"
            + " be constructed")
        void aShortRunIsRefused() {
            TransitionRun clean = cleanRun(new ExceptionQueue());

            assertThatThrownBy(() -> new TransitionRun(RUN_ID, TRANSITION_DATE, boundary(),
                List.of(QUOTED, DCF, PRESUMED, "C-DROPPED"), clean.outcomes(), List.of(),
                clean.valuationRun(), clean.tracker(), clean.plan(), List.of(), clean.coverage()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unaccounted for");
        }
    }

    /**
     * The one result the run published under {@code id}.
     *
     * <p>Asserted to be exactly one, because publishing an id twice is the defect this exercise's
     * whole delegation policy exists to avoid: {@code InvariantResult.conjunction} keeps only the
     * first breach's deviation among results sharing an id, so a second answer would leave which one
     * a report renders decided by list order.
     */
    private static InvariantResult only(TransitionRun run, InvariantId id) {
        List<InvariantResult> matching = run.invariants().stream()
            .filter(result -> result.id() == id)
            .toList();
        assertThat(matching)
            .as(id + " must be published exactly once; " + matching.size() + " results carry it")
            .hasSize(1);
        return matching.getFirst();
    }
}
