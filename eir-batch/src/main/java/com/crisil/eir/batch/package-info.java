/**
 * The runner: partitioned amortisation runs, restartability, and the nightly replay schedule
 * (ADR-0007, 05 § 2, docs/08 Phase 5).
 *
 * <h2>What this module is for</h2>
 *
 * <p>Two gaps docs/08 has recorded as open since Phase 5, and nothing else:
 *
 * <ul>
 *   <li>"<b>{@code eir-batch} — Spring Batch partitioned runs; restartability; per-contract
 *       isolation … Deferred.</b>" The population loop already exists — {@code MonthEndRun} over
 *       {@code ContractPipeline} — and what was missing was a runner that could split it, survive
 *       being killed in the middle of it, and still account for every contract afterwards.</li>
 *   <li>"<b>{@code NightlyReplayReport} is built by {@code ReplayUseCase.replayNightly} and nothing
 *       schedules it, which is {@code eir-batch}'s job.</b>" {@link
 *       com.crisil.eir.batch.NightlyReplaySchedule} is that caller.</li>
 * </ul>
 *
 * <h2>Spring Batch is the runner, not the arithmetic</h2>
 *
 * <p>ADR-0010 makes the framework ban fail closed and inherit into every module, and this module's
 * pom takes the one exemption that mechanism provides, naming ADR-0007 as the authority. That
 * exemption buys partitioning, a job repository and restart from a completed step. It buys nothing
 * on the calculation path, and the split is visible in the file list: everything Spring Batch
 * touches is in {@link com.crisil.eir.batch.AmortisationBatchJob}, {@link
 * com.crisil.eir.batch.AmortisationJobRun} and {@link
 * com.crisil.eir.batch.NightlyReplayBatchJob} — three files that import
 * {@code org.springframework} and contain no arithmetic at all. Every figure in a run is produced
 * by {@code ContractPipeline}, in {@code eir-application}, over {@code eir-calc}; the only thing a
 * job step does with a number here is count contracts.
 *
 * <h2>Isolation is delegated, not reimplemented</h2>
 *
 * <p>ADR-0007 asks for "a skip policy routing failed items to the exception queue", and this module
 * does not install one, because the barrier it would duplicate is already there:
 * {@code MonthEndRun} runs its population through {@code FailureIsolation.runBatch}, which files
 * one {@code ExceptionRecord} per quarantined contract and emits one {@code ContractResult} per
 * contract in the population — computed or quarantined, never absent. A Spring Batch
 * {@code SkipPolicy} on top of that would be a second, weaker barrier beside the one that owns the
 * question, and the two would disagree about what a skipped item is. So a partition here is
 * literally a {@code MonthEndRun} over its own shard, and what this module adds is the shard, the
 * commit boundary and the accounting across shards.
 *
 * <p>Partitioning is about throughput. It is not about error handling, and nothing here widens or
 * narrows what FR-905 quarantines.
 *
 * <h2>The clock enters here</h2>
 *
 * <p>{@code DeterminismTest} keeps every clock read out of {@code eir-domain} and
 * {@code eir-calc}, and 05 § 3.3 gives the reason: "nothing in the calculation path reads a wall
 * clock". This module is the edge, and it is where time is supposed to enter — as an
 * {@code AsAtBoundary} on a {@code RunRequest}, and as the {@code nightOf} a nightly control runs
 * for. Exactly one type reads a {@link java.time.Clock}: {@link
 * com.crisil.eir.batch.NightlyReplayWindow}, which turns the instant a scheduler fired into the
 * night that firing belongs to, and hands that inward as a {@link java.time.LocalDate}.
 */
package com.crisil.eir.batch;
