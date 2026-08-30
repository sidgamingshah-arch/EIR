package com.crisil.eir.api.modules.runs;

import com.crisil.eir.api.http.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * That difference is why {@link #latestFor} exists and is checked before a replay: this register can
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
     * The prefix every run id this resource mints carries.
     *
     * <p><b>Deliberately not the shape the console's own {@code POST /api/run} defaults to.</b> That
     * endpoint names its run {@code RUN-<periodId>-01} when the caller says nothing, which is
     * byte-identical to what a plain sequence would mint here for the first run of a period — and the
     * two endpoints publish into the same engine, which holds one run's working papers per period. So
     * a console run could land under an id this register was serving from a different body, and a
     * replay would report its byte comparison as that id's. Measured before this prefix existed:
     * start a run here, repair C-0003, run the console, and {@code POST
     * /api/runs/RUN-202805-01/replay} answered {@code dtOneSatisfied: true} for a comparison of a
     * run with three computed contracts under the id of a run that had two. A separate namespace
     * makes the collision impossible rather than unlikely: the {@code replayOf} check downstream can
     * only pass for a run this resource actually started.
     */
    public static final String ID_PREFIX = "API-RUN-";

    /**
     * The id format: the prefix, the period, and a two-digit sequence.
     *
     * <p>{@code Locale.ROOT} at every use, and that is not boilerplate. {@code String.format} without
     * a locale formats {@code %d} through {@code Locale.getDefault()}, and under a default locale
     * whose numbering system is not Latin — {@code -Duser.language=ar -Duser.country=EG} is enough —
     * every id this resource minted would carry Arabic-Indic digits, which no URL path segment can
     * name. Every {@code POST /api/runs} in that JVM would produce a run whose own resource could not
     * be fetched, and nothing in a test on an English CI would ever show it.
     */
    private static final String ID_FORMAT = ID_PREFIX + "%d-%02d";

    /**
     * The next run id for a period: {@code API-RUN-202805-03}.
     *
     * <p>Sequenced rather than fixed, because the arc 06 § 4 exists to support is refuse, repair,
     * <b>re-run</b>, close — two runs of one period — and a second run reusing the first's id would
     * leave {@code GET /api/runs/{id}} answering for whichever the map kept and a replay unable to
     * say which run it reproduced. The sequence counts this period's runs, so it does not renumber
     * when another period is added.
     *
     * <p><b>And it steps over an id already taken.</b> The endpoint no longer accepts a caller's own
     * run id — which is what made a collision reachable in the first place, and is now refused for
     * the reason {@link #ID_PREFIX} gives — so this is insurance rather than a live path. It is cheap
     * insurance: the failure it prevents is {@link #record} throwing on a duplicate, which reaches a
     * caller as a 500 on an ordinary re-run.
     */
    public String nextRunId(int periodId) {
        int sequence = idsFor(periodId).size() + 1;
        String candidate = String.format(Locale.ROOT, ID_FORMAT, periodId, sequence);
        while (byId.containsKey(candidate)) {
            sequence++;
            candidate = String.format(Locale.ROOT, ID_FORMAT, periodId, sequence);
        }
        return candidate;
    }

    public void record(Run run) {
        Objects.requireNonNull(run, "run");
        if (byId.containsKey(run.runId())) {
            // Unreachable through the endpoint, which mints every id from nextRunId. A throw rather
            // than an overwrite because the alternative is one run's body served under another's id.
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

    /**
     * The most recently started run <b>of one period</b>, which is the only one of that period's runs
     * the engine can still replay.
     *
     * <p>Keyed by period rather than global, because that is how the engine holds them:
     * {@code EirService}'s working papers are a map from period id to the last completed run, so
     * starting a run in June does not make May's newest run unreplayable. A global "latest" read the
     * two callers as though it were per-period, and the first second period registered would have
     * produced a refusal for period A's own newest run naming a run in period B.
     */
    public Optional<Run> latestFor(int periodId) {
        Run last = null;
        for (Run run : byId.values()) {
            if (run.periodId() == periodId) {
                last = run;
            }
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
