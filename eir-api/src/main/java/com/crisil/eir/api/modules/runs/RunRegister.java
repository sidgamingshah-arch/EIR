package com.crisil.eir.api.modules.runs;

import com.crisil.eir.api.http.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The runs this resource has started, so that {@code GET /api/runs/{id}} has something to answer.
 *
 * <p><b>Why the run's own response is what is kept.</b> {@code EirService} holds the last run's
 * working papers — the aggregate, the computations, the queue — privately, because a close and a
 * replay read them and no HTTP layer should. What it hands back is the rendered answer. So the run
 * resource keeps that answer verbatim and serves it as the body of the run, which has a property
 * worth more than a second rendering would have: {@code GET /api/runs/{id}} and the response to the
 * {@code POST} that started it are <b>the same bytes</b>, and cannot drift into two accounts of one
 * run.
 *
 * <p><b>What this deliberately is not.</b> A run store. {@code eir-persistence}'s
 * {@code AMORTISATION_RUN} and {@code PERIOD_BALANCE} hold every published run keyed by run and
 * period, which is what makes a replay of a period closed last March possible at all; this holds
 * what one process ran, and {@code EirService} itself keeps only the <em>latest</em> run per period.
 * That difference is why {@link #latest} exists and is checked before a replay: this register can
 * name a run whose figures the engine no longer holds, and answering that request with another run's
 * byte comparison would be a false DT-1.
 *
 * <p>Not thread-safe, for the reason {@code EirService} gives: {@code EirServer} runs a
 * single-threaded executor so that the engine, not a lock at the edge, decides what two
 * simultaneous runs of one period mean.
 */
public final class RunRegister {

    private final Map<String, Run> byId = new LinkedHashMap<>();

    /**
     * One completed run.
     *
     * @param runId    the id assigned when the run was started
     * @param periodId the accounting period, {@code YYYYMM}
     * @param bookId   the book that was run
     * @param body     the engine's own rendered answer — see the class note
     */
    public record Run(String runId, int periodId, String bookId, Json.Obj body) {

        public Run {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(bookId, "bookId");
            Objects.requireNonNull(body, "body");
            if (runId.isBlank()) {
                throw new IllegalArgumentException("a run is identified or it is not a run");
            }
        }

        /** A view over the engine's answer, for the fields the run resource reports. */
        public JsonView view() {
            return JsonView.of(body);
        }
    }

    /**
     * The next run id for a period: {@code RUN-202805-03}.
     *
     * <p>Sequenced rather than fixed, because the arc 06 § 4 exists to support is refuse, repair,
     * <b>re-run</b>, close — two runs of one period — and a second run reusing the first's id would
     * leave {@code GET /api/runs/{id}} answering for whichever the map kept and a replay unable to
     * say which run it reproduced. The sequence counts this period's runs, so it does not renumber
     * when another period is added.
     */
    public String nextRunId(int periodId) {
        int already = idsFor(periodId).size();
        return String.format("RUN-%d-%02d", periodId, already + 1);
    }

    public void record(Run run) {
        Objects.requireNonNull(run, "run");
        if (byId.containsKey(run.runId())) {
            // A caller-supplied duplicate is refused as a value by the endpoint, before it gets
            // here. Reaching this is a defect in that check.
            throw new IllegalStateException("run " + run.runId() + " is already on file");
        }
        byId.put(run.runId(), run);
    }

    public Optional<Run> find(String runId) {
        return Optional.ofNullable(byId.get(runId));
    }

    public boolean holds(String runId) {
        return byId.containsKey(runId);
    }

    /** The most recently started run, which is the only one the engine can still replay. */
    public Optional<Run> latest() {
        Run last = null;
        for (Run run : byId.values()) {
            last = run;
        }
        return Optional.ofNullable(last);
    }

    /** Every run id, in the order they were started. */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /** Every run id for one period, in the order they were started. */
    public List<String> idsFor(int periodId) {
        List<String> ids = new ArrayList<>();
        for (Run run : byId.values()) {
            if (run.periodId() == periodId) {
                ids.add(run.runId());
            }
        }
        return List.copyOf(ids);
    }
}
