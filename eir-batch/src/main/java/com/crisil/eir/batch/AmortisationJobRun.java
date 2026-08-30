package com.crisil.eir.batch;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.replay.AmortisationRun;
import com.crisil.eir.application.replay.RunOutput;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;

/**
 * {@code AmortisationRun}, implemented by launching the Spring Batch job — the adapter that makes a
 * replay literally "the same batch job" (05 § 3.3, ADR-0007).
 *
 * <h2>Why this class is the whole of the seam</h2>
 *
 * <p>{@code AmortisationRun} is declared in {@code eir-application.replay} with an explicit note:
 * "The per-contract run is being built as a sibling unit and its exact type names are not yet
 * settled, so this is the narrowest surface the replay can be written against … When the sibling
 * lands, adapting it is one lambda; nothing in this package needs to know its shape." This is the
 * sibling landing, and the adapter is one method.
 *
 * <p>What that buys is not tidiness. 05 § 3.3's constraint is that a replay is the <em>same</em> job:
 * "A replay implemented as a second pipeline proves nothing about the first — the two drift, and the
 * drift is invisible precisely where DT-1 exists to see it." Because {@code ReplayUseCase} drives
 * this interface and this class is a partitioned Spring Batch job, tonight's replay is a partitioned
 * Spring Batch job over a replay boundary. There is no second code path for it to drift from.
 *
 * <h2>Restart, and the launch that is refused</h2>
 *
 * <p>{@link #run} launches the job with {@link AmortisationBatchJob#parametersFor}, which identify a
 * run by its run id. So calling it twice for one {@code RunRequest} is a <b>restart</b>: Spring Batch
 * creates a second {@code JobExecution} on the same {@code JobInstance}, skips the steps and
 * partitions whose last execution completed, and re-runs the rest. That is the operational request
 * ADR-0007 names — "a run that dies at hour three of a four-hour window against a close deadline
 * must resume, not restart".
 *
 * <p>Calling it a third time, after the run has <em>completed</em>, is refused by the job repository
 * with {@code JobInstanceAlreadyCompleteException}, and that refusal is translated rather than
 * swallowed. It is the correct behaviour and an easy one to misread: a completed run is a published
 * artefact, and re-running it under the same run id would overwrite the figures a close was signed
 * off on. Re-running a period for real means a new run id; reproducing one means a replay, which
 * carries its own shadow run id by construction ({@code ReplayRequest.shadowRunId}).
 *
 * <h2>Failure is thrown, not reported</h2>
 *
 * <p>A job that ends anything other than {@code COMPLETED} throws, carrying the job execution's
 * failure exceptions as suppressed causes. {@code AmortisationRun.execute} returns a
 * {@code RunOutput} and a {@code RunOutput} has no way to say "this run did not finish" — and a
 * {@code RunOutput} assembled from a partial store would be the module's own worst failure mode:
 * {@code ReplayUseCase} would compare it against the published figures and report the missing
 * contracts as DT-1 discrepancies, sending an auditor to trace arithmetic for rows that were never
 * computed. {@code ReplayUseCase}'s own javadoc makes exactly this argument for refusing rather than
 * folding a population shortfall into DT-1.
 *
 * <p>A run that finished and does <em>not</em> report a clean close is a different thing and is
 * returned, not thrown: quarantined contracts and aggregated invariant breaches are findings about
 * the book, they are on {@code CompletedRun.blockingReasons()}, and the close gate is what refuses
 * on them. See {@code AmortisationBatchJob.aggregateStep}.
 */
public final class AmortisationJobRun implements AmortisationRun {

    private final AmortisationBatchJob jobs;
    private final JobLauncher launcher;
    private final RunProgressStore store;

    /**
     * @param jobs     builds the job for a request
     * @param launcher a launcher whose task executor runs the job <em>synchronously</em>; this class
     *                 reads the job execution's status the moment {@code run} returns, and an
     *                 asynchronous launcher would have it read the status of a job that has barely
     *                 started — reporting every close as failed and every replay as a DT-1 breach
     * @param store    where the aggregate step wrote the run's conclusion
     */
    public AmortisationJobRun(
        AmortisationBatchJob jobs, JobLauncher launcher, RunProgressStore store) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Runs — or resumes — the request, and returns what it produced.
     *
     * <p>The method {@code ReplayUseCase} calls. Its contract is "one result per contract in the
     * population, computed or quarantined, and the policy versions the run consulted", and
     * {@link CompletedRun#toRunOutput()} supplies exactly that from the aggregate.
     */
    @Override
    public RunOutput execute(RunRequest request) {
        return run(request).toRunOutput();
    }

    /**
     * As {@link #execute}, returning the run's full conclusion rather than the replay's view of it.
     *
     * <p>The population accounting — {@code computedCount()}, {@code quarantinedCount()},
     * {@code unaccountedFor()}, {@code blockingReasons()} — is on the {@code RunAggregate} and not on
     * {@code RunOutput}, so a caller running a close rather than a replay wants this one.
     *
     * @throws IllegalStateException where the job did not complete, or completed without writing a
     *                               conclusion
     */
    public CompletedRun run(RunRequest request) {
        Objects.requireNonNull(request, "request");
        Job job = jobs.jobFor(request);
        JobExecution execution;
        try {
            execution = launcher.run(job, jobs.parametersFor(request));
        } catch (JobInstanceAlreadyCompleteException alreadyComplete) {
            // Translated rather than propagated, because the framework's message ("A job instance
            // already exists and is complete") does not say what the caller has to do instead, and
            // the two things they might have meant have different answers. See the class javadoc.
            throw new IllegalStateException(
                "run " + request.runId() + " has already completed; its figures are a published"
                    + " artefact and re-running under the same run id would overwrite them. Running"
                    + " the period again is a new run id; reproducing it is a replay, which carries"
                    + " its own shadow run id (ReplayRequest.shadowRunId)", alreadyComplete);
        } catch (Exception launchFailure) {
            // JobExecutionAlreadyRunningException, JobRestartException and
            // JobParametersInvalidException. All three are conditions of the launch rather than of
            // the run, and none of them leaves a conclusion in the store, so there is nothing to
            // return and nothing to fold into DT-1.
            throw new IllegalStateException(
                "run " + request.runId() + " could not be launched: " + launchFailure.getMessage(),
                launchFailure);
        }

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            IllegalStateException failure = new IllegalStateException(
                "run " + request.runId() + " ended " + execution.getStatus() + " (exit "
                    + execution.getExitStatus().getExitCode() + ")"
                    + describeProgress(request)
                    + ". Nothing is returned for a run that did not finish: a RunOutput assembled"
                    + " from the partitions that happened to complete would be compared against the"
                    + " published figures and its missing contracts would read as DT-1"
                    + " discrepancies. Relaunch with the same request to resume from the partitions"
                    + " that did not complete");
            for (Throwable cause : execution.getAllFailureExceptions()) {
                failure.addSuppressed(cause);
            }
            throw failure;
        }

        return store.completion(request.runId()).orElseThrow(() -> new IllegalStateException(
            "run " + request.runId() + " completed and wrote no conclusion; the aggregate step is"
                + " what writes it and a completed job that skipped it is a job whose last step was"
                + " not the completion barrier ADR-0007 requires"));
    }

    /**
     * How far the run got, for the failure message.
     *
     * <p>Read from the store rather than from the job execution's step executions, because it is the
     * number that matters to whoever is relaunching: partitions committed, and contracts committed.
     * A partition that ran and did not commit is not progress.
     */
    private String describeProgress(RunRequest request) {
        List<PartitionKey> committed = store.recordedSlices(request.runId());
        return "; " + committed.size() + " partition(s) committed, "
            + store.contractsAccountedFor(request.runId()).size() + " contract(s) accounted for";
    }
}
