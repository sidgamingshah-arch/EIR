package com.crisil.eir.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.replay.AmortisationRun;
import com.crisil.eir.application.replay.PublishedRun;
import com.crisil.eir.application.replay.ReplayRequest;
import com.crisil.eir.application.replay.ReplayUseCase;
import com.crisil.eir.application.replay.RunOutput;
import com.crisil.eir.application.replay.ShadowRun;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayRun;
import com.crisil.eir.policy.replay.ReplaySamplingBasis;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The nightly replay schedule: the caller {@code ReplayUseCase.replayNightly} did not have, and the
 * verdict it has to reach (05 § 3.3, FR-903, control C-12, invariant DT-1).
 *
 * <h2>What is actually under test</h2>
 *
 * <p>Not the comparison — {@code ReplayUseCaseTest} and {@code ReplayComparison}'s own tests own the
 * byte comparison, the policy comparison and the population accounting. What this module adds is the
 * <b>verdict</b>, and the verdict is where the interesting mistake is available: a scheduler that
 * reads {@code dtOne().satisfied()} reports a clean night for a night on which nothing was replayed
 * and for a night on which every replayed period compared no figure.
 *
 * <p>So most of these tests hand the use case a pre-canned batch job and vary only what the night
 * amounts to. The last one drives the real partitioned Spring Batch job end to end, which is 05 §
 * 3.3's actual requirement — "a replay is the <em>same batch job</em>" — and is the only way to know
 * that {@link AmortisationJobRun} composes with {@code ReplayUseCase} at all.
 *
 * <h2>Why the published figure keys are written out by hand</h2>
 *
 * <p>The same reason {@code ReplayFixtures} gives: building the published side with
 * {@code ShadowRun.figures} would compare {@code ShadowRun}'s output against {@code ShadowRun}'s
 * output, so a defect in the key convention would cancel on both sides and every test would pass.
 * {@link #publishedKey} spells the convention out in string literals.
 */
@DisplayName("The nightly replay schedule (C-12)")
class NightlyReplayScheduleTest {

    /** April 2027 — the first period under ACPIR 20 — closed 5 May 2027. */
    private static final ClosedPeriod APRIL_2027 = ClosedPeriod.month(
        YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "financial.controller");

    /** The original run's {@code recorded_at}: the system-time half of a replay boundary. */
    private static final Instant RECORDED_AT = Instant.parse("2027-05-05T18:30:00Z");

    /** A night in the following quarter, four periods after the close — inside the lookback. */
    private static final LocalDate NIGHT_OF = LocalDate.of(2027, 8, 10);

    private static final String PUBLISHED_RUN_ID = "RUN-202704-CLOSE";
    private static final String CONTRACT = "LN-0000001";
    private static final String LOAN_ASSET = "1401-EIR-RECEIVABLE";
    private static final String INTEREST_INCOME = "4101-INTEREST-INCOME";

    /**
     * Reference case 1, period 1: opening 995,000.00, EIR interest 10,369.38, closing 958,295.91.
     *
     * <p>Hand check of the roll-forward: {@code 995000.00 + 10369.38 − 47073.47 = 958295.91}. A
     * fixture, not a computed quantity — this test compares published figures and does not produce
     * them.
     */
    private static final String CLOSING_GCA = "958295.91";
    private static final String EIR_INTEREST = "10369.38";

    /** The one policy version in force over April 2027 in these fixtures. */
    private static final Map<PolicyKind, String> STAMPS =
        Map.of(PolicyKind.ROUTING_TABLE, "POL-RT-2028.1");

    @Nested
    @DisplayName("A night that is evidence")
    class ANightThatReproduces {

        @Test
        @DisplayName("a replayed period whose figures and stamps match is a clean night")
        void aMatchingReplayIsACleanNight() {
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures(CLOSING_GCA))),
                job(List.of(computed(CLOSING_GCA)), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.controlRan()).as("the sampler selected April").isTrue();
            assertThat(outcome.reportsCleanNight())
                .as("clean: %s", outcome.describe())
                .isTrue();
            assertThat(outcome.blockingReasons()).isEmpty();
            assertThat(outcome.invariantResults())
                .as("one DT-1 result for the night")
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.id()).isEqualTo(InvariantId.DT_1);
                    assertThat(result.satisfied()).isTrue();
                });
            assertThat(outcome.periodsThatDidNotReproduce()).isEmpty();
        }
    }

    @Nested
    @DisplayName("A night that is not evidence")
    class ANightThatIsNot {

        @Test
        @DisplayName("a figure at a different value fails the night")
        void aFigureThatDriftedFailsTheNight() {
            // 958,295.90 against 958,295.91 — one paisa, which is what a replay reading a corrected
            // contract version rather than the version set as at the original run's recorded_at
            // looks like.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures("958295.90"))),
                job(List.of(computed(CLOSING_GCA)), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.controlRan()).isTrue();
            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons())
                .singleElement()
                .satisfies(reason -> assertThat(reason).contains("did not reproduce"));
            assertThat(outcome.dtOneBreaches()).hasSize(1);
            assertThat(outcome.periodsThatDidNotReproduce()).hasSize(1);
        }

        @Test
        @DisplayName("a figure at a different SCALE fails the night, though the two are numerically"
            + " identical")
        void aScaleDriftFailsTheNight() {
            // 958295.910 against 958295.91. Money.equals reports a match and the difference is
            // worth zero rupees, and it is still a different published artefact — a run that reduced
            // at a different point in the pipeline. ShadowRun never rescales, so the drift survives
            // to be seen, and this is the failure the whole byte comparison exists for.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures("958295.910"))),
                job(List.of(computed(CLOSING_GCA)), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("did not reproduce"));
        }

        @Test
        @DisplayName("a policy version the replay resolved differently fails the night, with every"
            + " figure identical")
        void aPolicyDivergenceFailsTheNight() {
            // The most valuable failure available to C-12: the right number from the wrong rule. It
            // will not hold next period and nothing else in the engine can see it, because every
            // downstream check agrees.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures(CLOSING_GCA))),
                job(List.of(computed(CLOSING_GCA)),
                    Map.of(PolicyKind.ROUTING_TABLE, "POL-RT-2029.1")));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("did not reproduce"));
        }

        @Test
        @DisplayName("a night that replayed a period and compared no figure is NOT a clean night,"
            + " though DT-1 passed")
        void aPeriodThatComparedNothingIsNotEvidence() {
            // THE MUTATION TARGET. A period with an empty population: the close published no figure,
            // the replay produced none, nothing differed, and DT-1 is satisfied — correctly, because
            // nothing failed. ReplayCoverage.NOTHING_PUBLISHED says provesFidelity() is false, and
            // ReplayVerification.provesReproduction() is therefore false: "DT-1 passed" and "the
            // replay reproduced the published figures" are the same sentence only when a figure was
            // compared.
            //
            // A scheduler reading dtOne().satisfied() reports this night clean. Replacing
            // provesReproduction() with dtOne().satisfied() in NightlyReplayOutcome.of makes this
            // test fail and nothing else in this module notice.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(Map.of(), List.of())),
                job(List.of(), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.controlRan()).as("a period WAS replayed").isTrue();
            assertThat(outcome.invariantResults())
                .as("and DT-1 passed")
                .singleElement()
                .satisfies(result -> assertThat(result.satisfied()).isTrue());
            assertThat(outcome.dtOneBreaches()).as("so there is no DT-1 breach").isEmpty();

            assertThat(outcome.reportsCleanNight())
                .as("and the night is still not evidence: %s", outcome.describe())
                .isFalse();
            assertThat(outcome.blockingReasons())
                .singleElement()
                .satisfies(reason -> assertThat(reason)
                    .contains("compared no figure")
                    .contains("absence of measurement"));
            assertThat(outcome.periodsThatDidNotReproduce()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("A night that did not run")
    class ANightThatDidNotRun {

        @Test
        @DisplayName("no closed period on file is not a clean night, and says why")
        void nothingClosedYetIsNotACleanNight() {
            // Before the first close. Not a defect — and tonight is still not evidence that any
            // period reproduces, and no DT-1 result is published for it.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(), job(List.of(computed(CLOSING_GCA)), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.controlRan()).isFalse();
            assertThat(outcome.invariantResults())
                .as("no DT-1 result at all — a pass here would read as coverage")
                .isEmpty();
            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons())
                .singleElement()
                .satisfies(reason -> assertThat(reason).contains("NOTHING_CLOSED_YET"));
        }

        @Test
        @DisplayName("a basis dialled to zero periods a night is not a clean night")
        void aBasisDialledToZeroIsNotACleanNight() {
            // The freeze that was never lifted: periodsPerNight set to zero during a code freeze and
            // not dialled back. Closed periods sit in front of the control and it selects none of
            // them. ReplaySample.inertnessWarning() names the cause, which is the difference between
            // "check the configuration" and "check the book".
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures(CLOSING_GCA))),
                job(List.of(computed(CLOSING_GCA)), STAMPS),
                new ReplaySamplingBasis(0, 12, "frozen during the ACPIR cutover, never restored"));

            NightlyReplayOutcome outcome = schedule.runNight(NIGHT_OF);

            assertThat(outcome.controlRan()).isFalse();
            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons()).isNotEmpty();
            assertThat(outcome.describe()).contains("night of " + NIGHT_OF);
        }

        @Test
        @DisplayName("a lookback window that has gone stale is not a clean night")
        void aStaleLookbackIsNotACleanNight() {
            // The newest closed period is April 2027 and the night is in August 2029 — 28 periods
            // later, against a twelve-period lookback. Correct when it was chosen and now a control
            // that selects nothing every night while the book is fully closed.
            NightlyReplaySchedule schedule = scheduleOver(
                List.of(candidate(publishedFigures(CLOSING_GCA))),
                job(List.of(computed(CLOSING_GCA)), STAMPS));

            NightlyReplayOutcome outcome = schedule.runNight(LocalDate.of(2029, 8, 10));

            assertThat(outcome.controlRan()).isFalse();
            assertThat(outcome.reportsCleanNight()).isFalse();
            assertThat(outcome.blockingReasons()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("The clock, and the job")
    class TheClockAndTheJob {

        @Test
        @DisplayName("runNow() runs the night the firing belongs to, not the calendar date")
        void runNowUsesTheWindow() {
            // 01:30 IST on 11 August 2027 — the scheduled 22:00 job, four hours late. The night is
            // the 10th, which is the night NIGHT_OF names and the night the sample rotates on.
            Clock justAfterMidnight =
                Clock.fixed(Instant.parse("2027-08-10T20:00:00Z"), ZoneId.of("UTC"));
            NightlyReplaySchedule schedule = new NightlyReplaySchedule(
                new ReplayUseCase(job(List.of(computed(CLOSING_GCA)), STAMPS)),
                nightOf -> List.of(candidate(publishedFigures(CLOSING_GCA))),
                ReplaySamplingBasis.nightlyDefault(),
                NightlyReplayWindow.indiaDefault(),
                justAfterMidnight);

            NightlyReplayOutcome outcome = schedule.runNow();

            assertThat(outcome.nightOf()).isEqualTo(LocalDate.of(2027, 8, 10));
            assertThat(outcome.reportsCleanNight()).isTrue();
            // The shadow run id is derived from the night, so a retrigger writes the same row.
            assertThat(outcome.report().verifications())
                .singleElement()
                .satisfies(verification -> assertThat(verification.shadowRunId())
                    .isEqualTo(ReplayRequest.shadowRunId(PUBLISHED_RUN_ID, NIGHT_OF)));
        }

        @Test
        @DisplayName("the C-12 job fails the step on a night that is not evidence")
        void theJobFailsOnANightThatIsNotEvidence() {
            NightlyReplaySchedule schedule =
                scheduleOver(List.of(), job(List.of(computed(CLOSING_GCA)), STAMPS));
            var jobRepository = InMemoryJobRepository.create();
            NightlyReplayBatchJob jobs = new NightlyReplayBatchJob(
                jobRepository,
                new org.springframework.batch.support.transaction.ResourcelessTransactionManager(),
                schedule);
            var launcher = BatchFixtures.launcher(jobRepository);

            org.springframework.batch.core.JobExecution execution;
            try {
                execution = launcher.run(jobs.jobFor(NIGHT_OF), jobs.parametersFor(NIGHT_OF));
            } catch (Exception unexpected) {
                throw new AssertionError("the launch itself must succeed", unexpected);
            }

            // A step that failed only on a DT-1 breach would report this night green: the sampler
            // selected nothing, so there is no DT-1 result to breach. That is the shape of the
            // seventeen controls in this repository that could not fail.
            assertThat(execution.getStatus())
                .isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
            assertThat(execution.getAllFailureExceptions())
                .anySatisfy(failure -> assertThat(failure)
                    .hasMessageContaining("is not evidence that any period reproduces"));
        }

        @Test
        @DisplayName("the real partitioned job is what tonight's replay runs (05 § 3.3)")
        void theReplayIsTheSameBatchJob() {
            // 05 § 3.3's constraint, exercised rather than asserted in prose: the close and the
            // replay are two executions of AmortisationJobRun, so there is no second code path for
            // them to drift on.
            BatchFixtures.Harness harness = BatchFixtures.harness();
            List<String> population = BatchFixtures.standardPopulation();
            String closeRunId = "RUN-202805-CLOSE";
            RunRequest liveRequest =
                BatchFixtures.request(closeRunId, population, Set.of());

            CompletedRun close = harness.runner().run(liveRequest);

            // The published artefact, projected under the same convention the replay will use.
            // Anchored on a figure derived by hand: reference case 1's month 13 closes at
            // 486,840.64255243997600 before the presentation reduction — see BatchFixtures.
            Map<String, Money> published = ShadowRun.figures(close.aggregate().results());
            assertThat(published.get(publishedKey(population.getFirst(), "closing_gca")).amount())
                .as("the close published reference case 1's month-13 closing balance")
                .isEqualByComparingTo(BatchFixtures.CLOSING_GCA_UNROUNDED);

            ClosedPeriod may2028 = ClosedPeriod.month(
                YearMonth.of(2028, 5), LocalDate.of(2028, 6, 4), "financial.controller");
            PublishedRun publishedRun = new PublishedRun(
                ReplayRun.published(
                    closeRunId, may2028.periodId(), published, close.policyVersionsConsulted()),
                may2028, BatchFixtures.KNOWN_AT, BatchFixtures.BOOK_ID);
            LocalDate night = LocalDate.of(2028, 6, 15);

            NightlyReplaySchedule schedule = new NightlyReplaySchedule(
                new ReplayUseCase(harness.runner()),
                nightOf -> List.of(new ReplayRequest(publishedRun, liveRequest)),
                ReplaySamplingBasis.nightlyDefault(),
                NightlyReplayWindow.indiaDefault(),
                Clock.systemUTC());

            NightlyReplayOutcome outcome = schedule.runNight(night);

            assertThat(outcome.controlRan()).isTrue();
            assertThat(outcome.reportsCleanNight())
                .as("the partitioned job reproduces its own close: %s", outcome.describe())
                .isTrue();
            // A second, distinct run: the shadow run id is derived from the night, so it is its own
            // JobInstance and its own row rather than a restart of the close.
            assertThat(outcome.report().verifications())
                .singleElement()
                .satisfies(verification -> {
                    assertThat(verification.shadowRunId())
                        .isEqualTo(ReplayRequest.shadowRunId(closeRunId, night));
                    assertThat(verification.population().populationSize()).isEqualTo(25);
                    assertThat(verification.provesReproduction()).isTrue();
                });
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The published figure key convention, written out rather than delegated.
     *
     * <p>{@code CONTRACT:id:column} for a row-level figure. See the class javadoc for why this is not
     * {@code ShadowRun.closingGcaKey}.
     */
    private static String publishedKey(String contractId, String column) {
        return "CONTRACT:" + contractId + ":" + column;
    }

    /** What the April close published for one contract: a balance and a balanced two-line accrual. */
    private static Map<String, Money> publishedFigures(String closingGca) {
        Map<String, Money> figures = new LinkedHashMap<>();
        figures.put(publishedKey(CONTRACT, "closing_gca"), Money.inr(closingGca));
        figures.put(publishedKey(CONTRACT, "journal:1:" + LOAN_ASSET + ":DR"),
            Money.inr(EIR_INTEREST));
        figures.put(publishedKey(CONTRACT, "journal:2:" + INTEREST_INCOME + ":CR"),
            Money.inr(EIR_INTEREST));
        return figures;
    }

    private static ContractResult computed(String closingGca) {
        JournalEntry journal = new JournalEntry(
            CONTRACT, APRIL_2027.periodId(), "REPLAY", BatchFixtures.BOOK_ID,
            APRIL_2027.endDate(),
            List.of(
                JournalLine.debit(LOAN_ASSET, Money.inr(EIR_INTEREST), "EIR interest accrued"),
                JournalLine.credit(
                    INTEREST_INCOME, Money.inr(EIR_INTEREST), "interest income, EIR basis")));
        return ContractResult.computed(CONTRACT, Money.inr(closingGca), journal, List.of());
    }

    /** A batch job that returns pre-canned output; the comparison is what these tests vary. */
    private static AmortisationRun job(
        List<ContractResult> results, Map<PolicyKind, String> stamps) {
        RunOutput output = RunOutput.of(results, stamps);
        return request -> {
            // Read as a real job would, so the ports see the replay boundary.
            request.contracts().contractIdsInScope(request.boundary());
            return output;
        };
    }

    private static PublishedRun published(Map<String, Money> figures) {
        return new PublishedRun(
            ReplayRun.published(PUBLISHED_RUN_ID, APRIL_2027.periodId(), figures, STAMPS),
            APRIL_2027, RECORDED_AT, BatchFixtures.BOOK_ID);
    }

    private static ReplayRequest candidate(Map<String, Money> figures) {
        return candidate(figures, List.of(CONTRACT));
    }

    private static ReplayRequest candidate(Map<String, Money> figures, List<String> population) {
        RunRequest template = new RunRequest(
            "RUN-202704-LIVE", APRIL_2027.periodId(), BatchFixtures.BOOK_ID,
            com.crisil.eir.application.port.AsAtBoundary.live(
                APRIL_2027.endDate(), Instant.parse("2027-08-10T20:00:00Z")),
            new BatchFixtures.FakeContracts(population),
            new BatchFixtures.FakeState(Set.of()),
            new BatchFixtures.FakeCoreBanking(),
            new BatchFixtures.FakeGeneralLedger(),
            new BatchFixtures.FakePolicy(BatchFixtures.POLICY));
        return new ReplayRequest(published(figures), template);
    }

    private static NightlyReplaySchedule scheduleOver(
        List<ReplayRequest> candidates, AmortisationRun batchJob) {
        return scheduleOver(candidates, batchJob, ReplaySamplingBasis.nightlyDefault());
    }

    private static NightlyReplaySchedule scheduleOver(
        List<ReplayRequest> candidates, AmortisationRun batchJob, ReplaySamplingBasis basis) {
        return new NightlyReplaySchedule(
            new ReplayUseCase(batchJob),
            nightOf -> candidates,
            basis,
            NightlyReplayWindow.indiaDefault(),
            Clock.systemUTC());
    }
}
