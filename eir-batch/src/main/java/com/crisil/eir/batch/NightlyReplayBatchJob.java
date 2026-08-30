package com.crisil.eir.batch;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Control C-12 as a job an operations scheduler can trigger (05 § 3.3, FR-903, ADR-0007).
 *
 * <h2>One step, and why it is a job at all</h2>
 *
 * <p>The work is {@link NightlyReplaySchedule#runNight}, which is one call. Wrapping it in a Spring
 * Batch job buys three things a cron entry calling a main method does not:
 *
 * <ul>
 *   <li><b>A record that the control ran.</b> C-12 is an auditable control, and 07 § 4.1 wants
 *       evidence that it ran on a given night, not only evidence of what it found. The job repository
 *       holds one {@code JobInstance} per night — the night is an identifying job parameter — so
 *       "was C-12 run on the night of 10 August" is a query rather than a log grep.</li>
 *   <li><b>Idempotence, enforced rather than hoped for.</b> Because the night identifies the job
 *       instance, a second trigger for a night that already completed is refused by the job
 *       repository. A third trigger after a failure resumes it. Both are the right answers, and
 *       neither needs code here.</li>
 *   <li><b>A visible failure.</b> The step fails on a night that is not evidence, so the operations
 *       tooling that watches the overnight window sees it in the same place it sees a failed close.
 *       A control whose failures are only in a log is a control that gets noticed at the next audit.
 *       </li>
 * </ul>
 *
 * <h2>The step fails on a night that replayed nothing</h2>
 *
 * <p>Which is the whole reason this file is not four lines. {@link NightlyReplayOutcome} reads
 * {@code provesReproduction()} rather than {@code dtOne().satisfied()} and treats an empty night as a
 * blocking reason rather than as a pass; this step fails on {@code !reportsCleanNight()}, so both of
 * those become a red job. A step that failed only on a DT-1 breach would report a green nightly
 * control for every night on which the sampler selected nothing — the exact shape of the seventeen
 * controls this codebase has found that could not fail.
 *
 * <p><b>The failure carries the reasons, not a status code.</b> "C-12 failed" sends an operator to
 * read code; "the lookback window is twelve periods and the newest closed period is nineteen periods
 * old" sends them to a configuration file.
 *
 * <h2>Nothing here decides anything</h2>
 *
 * <p>The night comes from {@link NightlyReplayWindow}, the sample from {@code ReplaySample}, the
 * verdict from {@link NightlyReplayOutcome}. This file builds one step and turns a boolean into an
 * exception.
 */
public final class NightlyReplayBatchJob {

    /** The job name; with the night, the identity of one night's control run. */
    public static final String JOB_NAME = "eir-nightly-replay";

    static final String REPLAY_STEP = "replay-sampled-period";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final NightlyReplaySchedule schedule;

    public NightlyReplayBatchJob(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        NightlyReplaySchedule schedule) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    /**
     * The job for one night.
     *
     * <p>The night is fixed when the job is built rather than read inside the step, so that the job
     * and its parameters describe the same night — a step that read the clock itself could run under
     * the parameters of one night and replay another, which is the off-by-a-night
     * {@link NightlyReplayWindow} exists to prevent, reintroduced one layer up.
     */
    public Job jobFor(LocalDate nightOf) {
        Objects.requireNonNull(nightOf, "nightOf");
        return new JobBuilder(JOB_NAME, jobRepository)
            .start(replayStep(nightOf))
            .build();
    }

    /**
     * The night a firing right now belongs to, read from the schedule's own clock.
     *
     * <p>This replaced a {@code jobForTonight()} that read {@code Clock.systemUTC()} directly. That
     * method had two defects and the second was the worse one. It ignored the {@link Clock} injected
     * into {@link NightlyReplaySchedule} — the only reason that field exists, and a violation of the
     * package's own claim that exactly one type reads a clock — so a schedule wired with a fixed or
     * offset clock got a job for a different night than {@code runNow()} would have run. And because
     * there was no matching {@code parametersForTonight()}, a caller had to derive the night a
     * <em>second</em> time for {@link #parametersFor}: two clock reads straddling the 20:00 cutover
     * give the job's step and the job's identifying parameter different nights, which is exactly what
     * {@link #jobFor}'s javadoc says it prevents.
     *
     * <p>So the night is read once, here, and the caller passes it to both:
     *
     * <pre>
     *   LocalDate night = jobs.tonight();
     *   launcher.run(jobs.jobFor(night), jobs.parametersFor(night));
     * </pre>
     */
    public LocalDate tonight() {
        return schedule.tonight();
    }

    /**
     * The parameters identifying one night's run of the control.
     *
     * <p>The night, and nothing else. It is what makes a second trigger for the same night a restart
     * of that night rather than a new one — see the class javadoc.
     */
    public JobParameters parametersFor(LocalDate nightOf) {
        Objects.requireNonNull(nightOf, "nightOf");
        return new JobParametersBuilder().addString("nightOf", nightOf.toString())
            .toJobParameters();
    }

    private org.springframework.batch.core.Step replayStep(LocalDate nightOf) {
        Tasklet tasklet = (StepContribution contribution, ChunkContext chunkContext) -> {
            NightlyReplayOutcome outcome = schedule.runNight(nightOf);
            // Counted so the step's own metrics say how many periods were replayed. Zero is the
            // interesting value and it is also the one the throw below is about.
            contribution.incrementWriteCount(outcome.report().verifications().size());
            if (!outcome.reportsCleanNight()) {
                throw new IllegalStateException(
                    "control C-12 for the night of " + nightOf + " is not evidence that any period"
                        + " reproduces: " + outcome.describe());
            }
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(REPLAY_STEP, jobRepository)
            .tasklet(tasklet, transactionManager)
            .build();
    }
}
