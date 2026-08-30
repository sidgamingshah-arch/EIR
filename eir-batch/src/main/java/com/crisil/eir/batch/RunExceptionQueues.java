package com.crisil.eir.batch;

import com.crisil.eir.policy.exception.ExceptionQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link ExceptionQueue} per run (04 § 2.13, 04 § 3, 05 § 4.5).
 *
 * <h2>Why this is not a single queue on the job</h2>
 *
 * <p>It was, and the consequence is a close that cannot be signed. {@code ExceptionQueue}'s
 * reporting surface — {@code records()}, {@code blocksClose()}, {@code closeBlockers()},
 * {@code quarantinedContracts()}, {@code countByCategory()} — has <b>no run-id filter</b>. Only
 * {@code MonthEndRun.filedFor} filters by run, and only to attribute a record to a
 * {@code ContractResult}. So a long-lived job holding one queue accumulates every close's records in
 * it, and then:
 *
 * <ul>
 *   <li>{@code PeriodCloseGate} refuses period N's close on period N−1's unresolved blockers, which
 *       reads as "this month has 340 exceptions" when this month has four;</li>
 *   <li>{@code amortisation_run.exceptions_raised} is cumulative rather than per run, so 04 § 2.13's
 *       run record overstates every close after the first.</li>
 * </ul>
 *
 * <p>Neither is visible in a figure. Both are visible to whoever has to clear the queue before a
 * statutory deadline, which is the worst possible place for them to become visible.
 *
 * <p>The queue could not simply be handed in on {@code AmortisationRun.execute(RunRequest)} — the
 * interface {@code ReplayUseCase} drives takes a request and nothing else, deliberately, so that a
 * replay is the same call a live run makes. So the run id selects the queue instead, which also gives
 * the close gate a way to ask for exactly the run it is closing.
 */
@FunctionalInterface
public interface RunExceptionQueues {

    /**
     * The queue for one run — the same instance on every call for that run id.
     *
     * <p>Stability matters: the aggregate step files into it and the close gate reads it afterwards,
     * and a source that returned a fresh queue each time would give the close gate an empty one.
     */
    ExceptionQueue forRun(String runId);

    /**
     * A source that creates one queue per run id and keeps it.
     *
     * <p>Retained rather than evicted, because 04 § 3 makes an unresolved exception a close blocker
     * an operator works <em>after</em> the run: a queue discarded when the job ended would take the
     * evidence with it. A durable implementation is a table, and it belongs in
     * {@code eir-persistence} for the same reason {@link InMemoryRunProgressStore}'s does.
     */
    static RunExceptionQueues perRun() {
        Map<String, ExceptionQueue> queues = new ConcurrentHashMap<>();
        return runId -> queues.computeIfAbsent(
            Objects.requireNonNull(runId, "runId"), id -> new ExceptionQueue());
    }
}
