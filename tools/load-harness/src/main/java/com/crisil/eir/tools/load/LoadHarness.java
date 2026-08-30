package com.crisil.eir.tools.load;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.close.RunClose;
import com.crisil.eir.application.load.Archetype;
import com.crisil.eir.application.load.SyntheticBook;
import com.crisil.eir.application.replay.AmortisationRun;
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
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.ReconciliationScope;
import com.crisil.eir.policy.close.ReconciliationTie;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.reconciliation.ContractualLegInterest;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayRun;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The load harness: it times a synthetic month-end close and a replay of it, and reports what it
 * measured — including a miss.
 *
 * <h2>The two gates this exists to measure</h2>
 *
 * <p>{@code docs/08} § Phase 5: "a 10M-contract synthetic close inside 4 hours; a replay of that
 * close is byte-identical; the close workflow refuses to close on any red invariant." The roadmap
 * records the third as met and the first two as unmet, because "there is no run to load, so there
 * is no 10M-contract close to time and no close to replay", and adds that "the 4-hour figure is
 * untested and remains an estimate, as does ADR-0009's core-hour costing". This class replaces the
 * estimates with numbers. {@code RESULTS.md} beside it carries them, and it reports the gate as
 * missed where it is missed — a measured six hours stated plainly is the deliverable, and a harness
 * tuned until it passes is not.
 *
 * <h2>What it measures, phase by phase</h2>
 *
 * <ol>
 *   <li><b>POPULATION</b> — {@code ContractSource.contractIdsInScope}. Timed on its own because the
 *       port's return type is {@code List<String>}: enumerating a 10M-contract book materialises
 *       ten million strings before a single figure is computed, and that is a cost of the port
 *       contract rather than of the arithmetic.</li>
 *   <li><b>RUN</b> — {@code MonthEndRun.execute}, the population loop of 05 § 3.2 through
 *       {@code ContractPipeline} and all seven ports.</li>
 *   <li><b>CLOSE</b> — {@code RunClose.present}: SL-2 over the journal batch, SL-1 against the GL
 *       control account, RC-1 against the core banking feed, the per-contract invariants collapsed
 *       one per id, and the gate's decision.</li>
 *   <li><b>REPLAY</b> — {@code ReplayUseCase.replay} over the figures the run published, compared
 *       by {@code ShadowRun} and {@code ReplayComparison}. The harness calls those; it does not
 *       reimplement the comparison.</li>
 * </ol>
 *
 * <h2>Why the replay comparison here is genuine and where it stops</h2>
 *
 * <p>The published side is {@code ShadowRun.figures} over the <em>first</em> run's results and the
 * shadow side is {@code ShadowRun.figures} over a <em>second, independent</em> execution of the
 * engine at the replay boundary. Both sides go through the same reducer, which is the point — the
 * reducer is not what is being tested, the arithmetic is, and two executions reduced identically
 * agree only if they computed identically. What this does <b>not</b> test is the persistence
 * round-trip: a real DT-1 reads the published side off a shadow table, and a scale lost on the way
 * into the database would show there and cannot show here. {@code ReplayUseCaseTest} is where the
 * key convention and the scale sensitivity are pinned against hand-written literals.
 *
 * <h2>Timing hygiene, and the noise this run was taken in</h2>
 *
 * <p>Every size is measured {@code --repeats} times in one JVM and the spread is printed, because a
 * single figure from a shared machine is not a measurement. The JIT is warmed on a small book
 * first, which is the right choice rather than a flattering one: a 10M-contract close is ten
 * million iterations of one loop and is thoroughly warm within its first second, so a cold-start
 * number would be less representative, not more.
 *
 * <p><b>Peak heap</b> is the maximum of a 10 ms sampler over
 * {@code MemoryMXBean.getHeapMemoryUsage()}, plus the sum of per-pool peaks for comparison. The
 * sampler can miss a spike between samples and the pool sum over-counts peaks that happened at
 * different moments, so the two together bracket the truth rather than either being it.
 * <b>Retained heap</b> is taken after three {@code System.gc()} calls with the run's output still
 * reachable, which is what a close actually holds: {@code MonthEndRun.Completion} keeps a
 * {@code Map} of every {@code ContractComputation} and {@code RunAggregate} keeps a
 * {@code List} of every {@code ContractResult}.
 *
 * <p>No {@code double} anywhere, per ADR-0002. Durations are {@code long} nanoseconds and every
 * derived figure — per-contract cost, throughput, the extrapolation to 10M, core-hours — is
 * {@link BigDecimal}. That is not ceremony for a timing tool: a harness that used doubles could not
 * be lifted into {@code src/test} when somebody wants these numbers guarded by a test.
 */
public final class LoadHarness {

    /** The gate: 10,000,000 contracts. */
    private static final BigDecimal GATE_CONTRACTS = new BigDecimal("10000000");

    /** The gate: four hours, in nanoseconds. */
    private static final BigDecimal GATE_NANOS = new BigDecimal("14400000000000");

    private static final BigDecimal NANOS_PER_SECOND = new BigDecimal("1000000000");
    private static final BigDecimal NANOS_PER_HOUR = new BigDecimal("3600000000000");
    private static final BigDecimal NANOS_PER_MICRO = new BigDecimal("1000");
    private static final BigDecimal MEBIBYTE = new BigDecimal("1048576");

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /** The close's own instants and dates — supplied, never read from a clock. */
    private static final Instant CLOSED_AT = Instant.parse("2028-06-05T09:00:00Z");
    private static final Instant CLOSING_STARTED_AT = Instant.parse("2028-06-02T09:00:00Z");
    private static final LocalDate CLOSED_ON = LocalDate.of(2028, 6, 5);
    private static final LocalDate NIGHT_OF = LocalDate.of(2028, 8, 10);

    private LoadHarness() {
    }

    public static void main(String[] args) throws Exception {
        List<Integer> sizes = new ArrayList<>();
        int resetPermille = Archetype.EVENT_RESET.defaultPermille();
        int repeats = 3;
        boolean close = true;
        boolean replay = true;
        boolean attribute = false;
        List<Integer> sweep = new ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--reset-permille=")) {
                resetPermille = Integer.parseInt(value(arg));
            } else if (arg.startsWith("--repeats=")) {
                repeats = Integer.parseInt(value(arg));
            } else if (arg.equals("--no-close")) {
                close = false;
            } else if (arg.equals("--no-replay")) {
                replay = false;
            } else if (arg.equals("--attribute")) {
                attribute = true;
            } else if (arg.startsWith("--sweep=")) {
                for (String share : value(arg).split(",")) {
                    sweep.add(Integer.parseInt(share.strip()));
                }
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option " + arg + usage());
            } else {
                for (String size : arg.split(",")) {
                    sizes.add(Integer.parseInt(size.strip().replace("_", "")));
                }
            }
        }
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("no population size given" + usage());
        }

        header(resetPermille, repeats, close, replay);

        // Warm-up, discarded. A close is a long loop and runs warm; measuring a cold JVM would
        // report the interpreter rather than the engine.
        say("warming up on 5,000 contracts (discarded) ...");
        for (int i = 0; i < 2; i++) {
            measure(new SyntheticBook(5_000, resetPermille), true, true);
        }
        say("");

        for (int size : sizes) {
            List<Measurement> taken = new ArrayList<>(repeats);
            for (int repeat = 1; repeat <= repeats; repeat++) {
                Measurement measurement =
                    measure(new SyntheticBook(size, resetPermille), close, replay);
                taken.add(measurement);
                report(measurement, size, repeat, repeats);
            }
            spread(size, taken, close, replay);
        }

        if (!sweep.isEmpty()) {
            say("");
            say("=== reset-share sensitivity: the marginal cost of a B5.4.5 solve ===");
            say("The reset share is the least defensible number in Archetype and the one the");
            say("timing turns on, so it is swept rather than asserted. Same size, same everything");
            say("else; the slots come out of PERFORMING_PERIODIC.");
            say("");
            int sweepSize = sizes.get(sizes.size() - 1);
            for (int share : sweep) {
                Measurement measurement =
                    measure(new SyntheticBook(sweepSize, share), false, false);
                say(String.format(Locale.ROOT,
                    "  reset %4d permille  solves %9d  run %12s s  %10s us/contract",
                    share, measurement.solves,
                    seconds(measurement.runNanos), perContract(measurement.runNanos, sweepSize)));
            }
        }

        if (attribute) {
            say("");
            say("=== cost attribution: one archetype at a time ===");
            say("A pure book gives the JIT a monomorphic loop it does not get on a mixed book, so");
            say("these are a FLOOR on what each path costs inside a real close. They are here for");
            say("one comparison the mixed run cannot make: PERFORMING_ACTUAL minus");
            say("PERFORMING_PERIODIC is the cost of one fractional power per contract, which is");
            say("the quantity ADR-0009 sized at 0.2 core-hours per 10M-contract close.");
            say("");
            // 20,000 and not the run's own size, for a reason that is itself the headline finding:
            // a pure EVENT_RESET book has one solve per contract, and ContractPipeline calls
            // SolveAudit.solveCountFor twice per contract while that method scans every record the
            // audit holds — so a pure reset book costs O(n^2) string comparisons and a 200,000-row
            // one would spend minutes inside an accessor. See RESULTS.md, "the hot spot".
            int attributionSize = Math.min(20_000, sizes.get(sizes.size() - 1));
            Map<Archetype, Measurement> pure = new LinkedHashMap<>();
            for (Archetype archetype : Archetype.values()) {
                Measurement measurement =
                    measure(SyntheticBook.pure(attributionSize, archetype), false, false);
                pure.put(archetype, measurement);
                say(String.format(Locale.ROOT,
                    "  %-22s n %8d  run %10s s  %10s us/contract  solves %8d  breaches %d",
                    archetype, attributionSize, seconds(measurement.runNanos),
                    perContract(measurement.runNanos, attributionSize), measurement.solves,
                    measurement.breaches));
            }
            attribution(pure, attributionSize);
        }

        say("");
        say("Done. Numbers above are for RESULTS.md; the harness draws no conclusion for you.");
    }

    // ------------------------------------------------------------------ one measurement

    /** Everything one timed close yielded. */
    private static final class Measurement {
        long populationNanos;
        long runNanos;
        long closeNanos;
        long replayNanos;
        long peakSampledBytes;
        long peakPoolBytes;
        long retainedAfterRunBytes;
        long retainedAfterCloseBytes;
        long replayPeakSampledBytes;
        int computed;
        int quarantined;
        int unaccounted;
        int solves;
        int breaches;
        int blockingReasons;
        int figuresCompared;
        boolean mayClose;
        boolean bitIdentical;
        boolean provesReproduction;
        String replayVerdict = "not run";
        String firstBlockingReason = "";
    }

    private static Measurement measure(SyntheticBook book, boolean close, boolean replay)
        throws Exception {
        Measurement measurement = new Measurement();
        int size = book.size();

        resetPoolPeaks();
        gc();
        long baseline = heapUsed();
        HeapSampler sampler = HeapSampler.started();

        // ---- POPULATION ---------------------------------------------------------------------
        long t0 = System.nanoTime();
        List<String> population = book.contractIdsInScope(SyntheticBook.boundary());
        measurement.populationNanos = System.nanoTime() - t0;
        if (population.size() != size) {
            throw new IllegalStateException(
                "the population port answered " + population.size() + " for a book of " + size);
        }
        // Released before the run, deliberately: MonthEndRun enumerates the population itself, so
        // holding this second copy would double-count the port's cost in every heap figure below.
        population = null;

        // ---- RUN ----------------------------------------------------------------------------
        RunRequest request = book.liveRequest(SyntheticBook.RUN_ID);
        SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
        MonthEndRun run = new MonthEndRun(
            request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));
        ExceptionQueue queue = new ExceptionQueue();

        long t1 = System.nanoTime();
        MonthEndRun.Completion completion = run.execute(queue);
        measurement.runNanos = System.nanoTime() - t1;

        RunAggregate aggregate = completion.aggregate();
        measurement.computed = aggregate.computedCount();
        measurement.quarantined = aggregate.quarantinedCount();
        measurement.unaccounted = aggregate.unaccountedFor();
        measurement.solves = audit.solveCount();
        measurement.breaches = aggregate.breaches().size();
        measurement.blockingReasons = aggregate.blockingReasons().size();
        if (!aggregate.blockingReasons().isEmpty()) {
            measurement.firstBlockingReason = aggregate.blockingReasons().get(0);
        }
        // The accounting identity, checked rather than assumed. FR-905's whole point is that a run
        // over 10,000,000 contracts that silently processed 9,999,998 reconciles perfectly, so a
        // harness reporting a throughput over a population it never verified would be reporting the
        // wrong number quickly.
        if (measurement.computed + measurement.quarantined + measurement.unaccounted != size) {
            throw new IllegalStateException(
                "the run does not account for its population: " + size + " in scope, "
                    + measurement.computed + " computed, " + measurement.quarantined
                    + " quarantined, " + measurement.unaccounted + " unaccounted");
        }
        gc();
        measurement.retainedAfterRunBytes = heapUsed() - baseline;

        // ---- CLOSE --------------------------------------------------------------------------
        if (close) {
            Money subLedger = Money.zero(Money.INR);
            List<ContractualLegInterest> engineLines = new ArrayList<>(size);
            for (ContractResult result : aggregate.results()) {
                if (result.isComputed()) {
                    subLedger = subLedger.plus(result.closingGca());
                    engineLines.add(new ContractualLegInterest(
                        result.contractId(), SyntheticBook.PERIOD_ID,
                        SyntheticBook.BILLED_INTEREST));
                }
            }
            book.withGlBalance(subLedger);

            long t2 = System.nanoTime();
            RunClose.ClosePresentation presentation = RunClose.present(
                request, aggregate, SyntheticBook.GCA_ACCOUNT, engineLines, portfolioTies(),
                closingPeriod(), "financial.controller", CLOSED_AT, List.of(), List.of());
            measurement.closeNanos = System.nanoTime() - t2;
            measurement.mayClose = presentation.mayClose();
            gc();
            measurement.retainedAfterCloseBytes = heapUsed() - baseline;
            // Held to here so the retained figure includes it, then dropped.
            if (presentation.dashboard().isEmpty()) {
                throw new IllegalStateException("the close presented an empty dashboard");
            }
        }

        measurement.peakSampledBytes = sampler.peak();
        measurement.peakPoolBytes = poolPeaks();

        // ---- REPLAY -------------------------------------------------------------------------
        if (replay) {
            // The published side is extracted and the live run's output then RELEASED, because a
            // nightly replay reads the published figures off a shadow table and does not have last
            // month's Completion in memory. Keeping it would report a peak no real replay pays.
            Map<String, Money> publishedFigures = ShadowRun.figures(aggregate.results());
            PublishedRun published = new PublishedRun(
                ReplayRun.published(
                    SyntheticBook.RUN_ID, SyntheticBook.PERIOD_ID, publishedFigures,
                    book.policyStamps()),
                ClosedPeriod.month(YearMonth.of(2028, 5), CLOSED_ON, "financial.controller"),
                SyntheticBook.KNOWN_AT, SyntheticBook.BOOK_ID);
            completion = null;
            aggregate = null;
            publishedFigures = null;
            gc();

            HeapSampler replaySampler = HeapSampler.started();
            ReplayRequest replayRequest = new ReplayRequest(
                published, book.liveRequest(SyntheticBook.RUN_ID + "-TEMPLATE"));
            ReplayUseCase replayUseCase = new ReplayUseCase(new ReplayJob(book));

            long t3 = System.nanoTime();
            ReplayVerification verification = replayUseCase.replay(
                replayRequest, ReplayRequest.shadowRunId(SyntheticBook.RUN_ID, NIGHT_OF));
            measurement.replayNanos = System.nanoTime() - t3;
            measurement.replayPeakSampledBytes = replaySampler.peak();
            replaySampler.stop();

            measurement.bitIdentical = verification.comparison().isBitIdentical();
            measurement.provesReproduction = verification.provesReproduction();
            measurement.figuresCompared = verification.comparison().figuresCompared();
            measurement.replayVerdict = verification.comparison().describe();
        }

        sampler.stop();
        return measurement;
    }

    /**
     * The batch job a replay drives: a real {@link MonthEndRun} at the boundary it is handed.
     *
     * <p>05 § 3.3: "a replay is the <b>same batch job</b> with {@code is_replay = true} and an
     * as-at boundary". So this builds the same pipeline over the same book and takes its boundary
     * from the request rather than from anywhere else — a job that reached for the live boundary
     * would reproduce nothing however deterministic its arithmetic.
     */
    private record ReplayJob(SyntheticBook book) implements AmortisationRun {
        @Override
        public RunOutput execute(RunRequest request) {
            SolveAudit audit = SolveAudit.over(new BracketedNewtonSolver());
            MonthEndRun run = new MonthEndRun(
                request, new ContractPipeline(request, book, SyntheticBook.ROUTING, audit));
            RunAggregate aggregate = run.execute(new ExceptionQueue()).aggregate();
            return RunOutput.of(aggregate.results(), book.policyStamps());
        }
    }

    /**
     * The two reconciliations {@code RunClose} cannot source from a {@code ContractResult}.
     *
     * <p>Nil against nil, with the note saying so. This matters to how the harness's close verdict
     * is read and it is stated here rather than buried: a {@code ContractResult} carries a closing
     * balance and a journal and no ECL allowance, suspense balance or stage, so the Stage 3
     * four-way and the pre-/post-floor duality are not derivable from the aggregate — and
     * {@code RunClose} is right to refuse to synthesise them. A nil-against-nil tie presents as
     * tied, so <b>this harness's {@code mayClose} is evidence about cost and not about controls.</b>
     * The population here is 4% Stage 3 by construction, so a real close over it would have a real
     * four-way to present. {@code MonthEndCloseTest} and {@code RunCloseTest} are where the gate's
     * verdict is the subject.
     */
    private static List<ReconciliationTie> portfolioTies() {
        Money nil = Money.zero(Money.INR);
        return List.of(
            new ReconciliationTie(ReconciliationScope.STAGE_THREE_FOUR_WAY, nil, nil,
                "not sourced by the load harness: timing only, see LoadHarness.portfolioTies"),
            new ReconciliationTie(ReconciliationScope.PRE_AND_POST_FLOOR, nil, nil,
                "not sourced by the load harness: timing only, see LoadHarness.portfolioTies"));
    }

    private static AccountingPeriod closingPeriod() {
        // The accounting period starts on the first of its month. SyntheticBook.PERIOD_START is
        // 2028-04-30, the previous instalment date and the boundary the accrual runs FROM, and
        // AccountingPeriod enforces that its id encodes its own start date.
        return AccountingPeriod.open(
                SyntheticBook.PERIOD_ID, "FY2028-29",
                LocalDate.of(2028, 5, 1), SyntheticBook.PERIOD_END)
            .startClosing(CLOSING_STARTED_AT);
    }

    // ------------------------------------------------------------------ reporting

    private static void header(int resetPermille, int repeats, boolean close, boolean replay) {
        say("EIR load harness — Phase 5 exit gate, clauses one and two");
        say("=========================================================");
        say("gate:            10,000,000 contracts inside 4 hours (docs/08, docs/07 § NFR)");
        say("jvm:             " + System.getProperty("java.vm.name") + " "
            + System.getProperty("java.version"));
        say("max heap:        " + mib(Runtime.getRuntime().maxMemory()) + " MiB");
        say("cpus visible:    " + Runtime.getRuntime().availableProcessors()
            + "  (the run loop is single-threaded: MonthEndRun iterates its population)");
        say("reset share:     " + resetPermille + " permille");
        say("repeats:         " + repeats);
        say("phases:          POPULATION, RUN" + (close ? ", CLOSE" : "")
            + (replay ? ", REPLAY" : ""));
        say("");
        say("Mix (Archetype declares the shares and why):");
        for (Archetype archetype : Archetype.values()) {
            say(String.format(Locale.ROOT, "  %-22s %4d permille   %s%s",
                archetype, archetype.defaultPermille(),
                archetype.hasEvent() ? "event" : "no event",
                archetype.solves() ? ", solves once" : ", no solve"));
        }
        say("");
        say("NOISE WARNING: this machine is shared. Read the spread, not the best run.");
        say("");
    }

    private static void report(Measurement m, int size, int repeat, int repeats) {
        say(String.format(Locale.ROOT, "--- n = %,d   repeat %d/%d ---", size, repeat, repeats));
        say(String.format(Locale.ROOT,
            "  POPULATION  %12s s   %10s us/contract", seconds(m.populationNanos),
            perContract(m.populationNanos, size)));
        say(String.format(Locale.ROOT,
            "  RUN         %12s s   %10s us/contract   %14s contracts/s",
            seconds(m.runNanos), perContract(m.runNanos, size), throughput(m.runNanos, size)));
        if (m.closeNanos > 0) {
            say(String.format(Locale.ROOT,
                "  CLOSE       %12s s   %10s us/contract", seconds(m.closeNanos),
                perContract(m.closeNanos, size)));
        }
        if (m.replayNanos > 0) {
            say(String.format(Locale.ROOT,
                "  REPLAY      %12s s   %10s us/contract", seconds(m.replayNanos),
                perContract(m.replayNanos, size)));
        }
        long endToEnd = m.populationNanos + m.runNanos + m.closeNanos;
        say(String.format(Locale.ROOT,
            "  CLOSE E2E   %12s s   %10s us/contract   -> 10M in %s h",
            seconds(endToEnd), perContract(endToEnd, size), hoursAtTenMillion(endToEnd, size)));
        say(String.format(Locale.ROOT,
            "  heap        peak(sampled) %9s MiB   peak(pool sum) %9s MiB",
            mib(m.peakSampledBytes), mib(m.peakPoolBytes)));
        say(String.format(Locale.ROOT,
            "  retained    after RUN %9s MiB   after CLOSE %9s MiB   %s B/contract",
            mib(m.retainedAfterRunBytes), mib(m.retainedAfterCloseBytes),
            bytesPer(Math.max(m.retainedAfterRunBytes, m.retainedAfterCloseBytes), size)));
        if (m.replayPeakSampledBytes > 0) {
            say(String.format(Locale.ROOT,
                "  replay heap peak(sampled) %9s MiB", mib(m.replayPeakSampledBytes)));
        }
        say(String.format(Locale.ROOT,
            "  accounting  computed %,d  quarantined %,d  unaccounted %,d  solves %,d",
            m.computed, m.quarantined, m.unaccounted, m.solves));
        say(String.format(Locale.ROOT,
            "  controls    red invariants %d   blocking reasons %d   mayClose %s",
            m.breaches, m.blockingReasons, m.mayClose));
        if (!m.firstBlockingReason.isEmpty()) {
            say("  first reason: " + truncate(m.firstBlockingReason));
        }
        if (m.replayNanos > 0) {
            say(String.format(Locale.ROOT,
                "  replay      byte-identical %s   proves reproduction %s   figures compared %,d",
                m.bitIdentical, m.provesReproduction, m.figuresCompared));
            say("  replay verdict: " + truncate(m.replayVerdict));
        }
        say(String.format(Locale.ROOT,
            "  core-hours  run %s   close e2e %s   (single-threaded, so core-hours = wall-clock)",
            coreHours(m.runNanos), coreHours(endToEnd)));
        say("");
    }

    private static void spread(int size, List<Measurement> taken, boolean close, boolean replay) {
        say(String.format(Locale.ROOT, "=== n = %,d over %d repeats ===", size, taken.size()));
        spreadOf("POPULATION", taken.stream().mapToLong(m -> m.populationNanos).toArray(), size);
        spreadOf("RUN", taken.stream().mapToLong(m -> m.runNanos).toArray(), size);
        if (close) {
            spreadOf("CLOSE", taken.stream().mapToLong(m -> m.closeNanos).toArray(), size);
        }
        if (replay) {
            spreadOf("REPLAY", taken.stream().mapToLong(m -> m.replayNanos).toArray(), size);
        }
        spreadOf("CLOSE E2E",
            taken.stream()
                .mapToLong(m -> m.populationNanos + m.runNanos + m.closeNanos).toArray(), size);
        say("");
    }

    private static void spreadOf(String phase, long[] nanos, int size) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        BigDecimal total = BigDecimal.ZERO;
        for (long value : nanos) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            total = total.add(BigDecimal.valueOf(value));
        }
        BigDecimal mean = total.divide(BigDecimal.valueOf(nanos.length), MC);
        say(String.format(Locale.ROOT,
            "  %-11s min %10s s  mean %10s s  max %10s s  spread %6s%%  -> 10M in %s h",
            phase, seconds(min), seconds(mean.longValue()), seconds(max),
            percentSpread(min, max), hoursAtTenMillion(mean.longValue(), size)));
    }

    /**
     * The one comparison against ADR-0009: what a fractional power costs per contract.
     *
     * <p>ADR-0009 records "0.2 core-hours per 10M-contract close" for obtaining the par gap from
     * the leg already computed — one fractional power per contract — against 922 core-hours for a
     * second bracketed-Newton solve, and says plainly that both "are an estimate, not a measurement
     * on this codebase". The measurable proxy is here: {@code PERFORMING_ACTUAL} and
     * {@code PERFORMING_PERIODIC} are the same contract, the same period and the same figures,
     * differing only in that the actual-dated one raises {@code (1 + i)} to 31/365 while the
     * periodic one raises it to exactly one. The difference in per-contract cost is therefore one
     * fractional power, plus one day-count year fraction that the schedule derivation also pays.
     */
    private static void attribution(Map<Archetype, Measurement> pure, int size) {
        Measurement periodic = pure.get(Archetype.PERFORMING_PERIODIC);
        Measurement actual = pure.get(Archetype.PERFORMING_ACTUAL);
        Measurement reset = pure.get(Archetype.EVENT_RESET);
        if (periodic == null || actual == null || reset == null) {
            return;
        }
        long powerNanos = actual.runNanos - periodic.runNanos;
        long solveNanos = reset.runNanos - periodic.runNanos;
        say("");
        say("  ADR-0009 comparison (its figures are estimates; these are measurements):");
        say(String.format(Locale.ROOT,
            "    one fractional power + day count : %s us/contract  -> %s core-hours per 10M",
            perContract(powerNanos, size), coreHoursAtTenMillion(powerNanos, size)));
        say("    ADR-0009's design-time estimate  : 0.2 core-hours per 10M");
        say(String.format(Locale.ROOT,
            "    one B5.4.5 solve (event branch)  : %s us/contract  -> %s core-hours per 10M",
            perContract(solveNanos, size), coreHoursAtTenMillion(solveNanos, size)));
        say("    ADR-0009's estimate for a solve  : 922 core-hours per 10M");
        say("    Read the second pair as 'if every one of ten million contracts solved', which is");
        say("    the counterfactual ADR-0009 costed and not a book anybody has.");
    }

    // ------------------------------------------------------------------ formatting, no doubles

    private static String seconds(long nanos) {
        return BigDecimal.valueOf(nanos).divide(NANOS_PER_SECOND, 3, RoundingMode.HALF_UP)
            .toPlainString();
    }

    private static String perContract(long nanos, int size) {
        if (size == 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(nanos)
            .divide(BigDecimal.valueOf(size), MC)
            .divide(NANOS_PER_MICRO, 4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String throughput(long nanos, int size) {
        if (nanos == 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(size).multiply(NANOS_PER_SECOND)
            .divide(BigDecimal.valueOf(nanos), 0, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The extrapolation: per-contract cost measured here, multiplied by ten million.
     *
     * <p><b>Linear by assumption, which the sizes measured are there to test.</b> If the
     * per-contract figure is flat across 10k, 100k and 1M then the projection is a projection; if
     * it climbs, the projection is a floor and {@code RESULTS.md} says so and by how much. It
     * cannot be anything better than that from a machine that cannot hold 10M contracts, and a
     * harness claiming a 10M number it did not run would be exactly the thing this unit replaces.
     */
    private static String hoursAtTenMillion(long nanos, int size) {
        if (size == 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(size), MC)
            .multiply(GATE_CONTRACTS)
            .divide(NANOS_PER_HOUR, 3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String coreHours(long nanos) {
        return BigDecimal.valueOf(nanos).divide(NANOS_PER_HOUR, 4, RoundingMode.HALF_UP)
            .toPlainString();
    }

    private static String coreHoursAtTenMillion(long nanos, int size) {
        if (size == 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(size), MC)
            .multiply(GATE_CONTRACTS)
            .divide(NANOS_PER_HOUR, 4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percentSpread(long min, long max) {
        if (min <= 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(max - min).multiply(new BigDecimal("100"))
            .divide(BigDecimal.valueOf(min), 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String mib(long bytes) {
        return BigDecimal.valueOf(bytes).divide(MEBIBYTE, 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String bytesPer(long bytes, int size) {
        if (size == 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(bytes)
            .divide(BigDecimal.valueOf(size), 0, RoundingMode.HALF_UP).toPlainString();
    }

    /** Whether the gate is met, for the reader's convenience — never for the harness's. */
    static boolean withinGate(long nanos, int size) {
        return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(size), MC)
            .multiply(GATE_CONTRACTS).compareTo(GATE_NANOS) <= 0;
    }

    private static String truncate(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 297) + "...";
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static String usage() {
        return "\nusage: LoadHarness <size>[,<size>...] [--reset-permille=N] [--repeats=N]"
            + " [--no-close] [--no-replay] [--attribute] [--sweep=p1,p2,...]";
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
    }

    // ------------------------------------------------------------------ heap measurement

    /**
     * A 10 ms sampler over the heap's used bytes.
     *
     * <p>A sampler rather than an allocation counter, because the question is how much heap a close
     * needs at once and not how much it allocates in total — a run that allocates a terabyte and
     * retains a megabyte fits in a small heap, and the reverse does not. It can miss a spike
     * between two samples, so the per-pool peak sum is printed beside it as an upper bracket.
     */
    private static final class HeapSampler {
        private volatile boolean running = true;
        private volatile long peak;
        private final Thread thread;

        private HeapSampler() {
            this.thread = new Thread(() -> {
                while (running) {
                    long used = heapUsed();
                    if (used > peak) {
                        peak = used;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "heap-sampler");
            thread.setDaemon(true);
        }

        static HeapSampler started() {
            HeapSampler sampler = new HeapSampler();
            sampler.thread.start();
            return sampler;
        }

        long peak() {
            return peak;
        }

        void stop() {
            running = false;
            thread.interrupt();
        }
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long poolPeaks() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum;
    }

    private static void resetPoolPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /**
     * Three collections and a pause, which is the honest way to ask a JVM what is live.
     *
     * <p>{@code System.gc()} is a request; one call routinely leaves floating garbage behind, and
     * the retained figure is the finding, so it is worth paying for. The pause lets a concurrent
     * collector finish.
     */
    private static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(120);
        }
    }
}
