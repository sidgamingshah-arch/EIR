package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.runs.JsonView;
import com.crisil.eir.api.modules.runs.PeriodRegister;
import com.crisil.eir.api.modules.runs.Resource;
import com.crisil.eir.api.modules.runs.RunRegister;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.policy.close.AccountingPeriod;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs and periods — {@code docs/06-api-spec.md} 06 § 4, including the close that answers 409.
 *
 * <table>
 *   <caption>The five endpoints, and what each is for</caption>
 *   <tr><th>Endpoint</th><th>Answer</th></tr>
 *   <tr><td>{@code POST /api/runs}</td>
 *       <td>Starts an amortisation run for {@code {periodId, bookId}} and returns the run id</td></tr>
 *   <tr><td>{@code GET /api/runs/{id}}</td>
 *       <td>Status, partition progress, contracts processed, exception count, invariant results</td></tr>
 *   <tr><td>{@code POST /api/runs/{id}/replay}</td>
 *       <td>The byte comparison of a replay against what the run published — DT-1</td></tr>
 *   <tr><td>{@code GET /api/periods} · {@code GET /api/periods/{id}}</td>
 *       <td>Period status {@code OPEN} / {@code CLOSING} / {@code CLOSED}</td></tr>
 *   <tr><td>{@code POST /api/periods/{id}/close}</td>
 *       <td>The close workflow — <b>409 when any gate fails</b>, with every failing gate in the
 *           body</td></tr>
 * </table>
 *
 * <h2>The 409, which is the point of this module</h2>
 *
 * <p>Everything else in this engine answers 200 for every answer it gives, refusals included,
 * because a refusal is a value here and the whole list comes back. 06 § 4 makes one exception and
 * says so in as many words: "{@code POST /periods/{id}/close} returning {@code 409} is the intended
 * behaviour, not an error path to route around." The reason is that this one endpoint has two
 * audiences who need different things from the same answer. A REST client — a scheduler, an
 * orchestration job, a downstream reporting pipeline — branches on the status line, and a 200 that
 * carries {@code mayClose: false} to a caller that checked only the status is a period believed
 * closed that is not. The operator console needs the opposite: not a code, but <b>the complete list
 * of what is wrong</b>, because a close presents forty unworked exceptions and a sub-ledger break at
 * once, worked by three different desks, and one refusal at a time turns a day of parallel work into
 * a week of serial re-runs. So: the status is 409, and the body is the whole refusal set.
 *
 * <p><b>Both refusal lists come back, and they are not merged.</b> {@code ClosePresentation} carries
 * two, from two places that each know something the other cannot:
 *
 * <ul>
 *   <li>{@code runRefusals()} — the run's own facts. Its population arithmetic (a run over ten
 *       million contracts that processed 9,999,998 reconciles perfectly, because the two it dropped
 *       are absent from both sides of every total), its unasserted obligations, its per-contract
 *       invariant breaches. None of these is an invariant result: "this run measured nothing" is not
 *       a breach, it is a reason the invariants cannot be believed.</li>
 *   <li>{@code decision().refusals()} — the gate's own: SL-2 over the batch, SL-1 against the GL,
 *       RC-1 against the CBS, the four reconciliation ties, the period's attestation, and the
 *       exception queue with its acceptances weighed under four eyes.</li>
 * </ul>
 *
 * <p>A per-contract breach appears in both, once as a reason the run blocked and once as a
 * {@code CloseGateRefusal}. {@code RunClose}'s own note calls that "duplication in a report and not
 * two answers to one question, and it is the price of the two lists being sourced from the two
 * places that actually know". Collapsing them into one message at the edge would throw away
 * precisely that, so this module reports the engine's answer whole: {@code close.runRefusals} is the
 * first list, {@code gateVerdict} and {@code close.gateVerdict} enumerate the second one gate to a
 * line, {@code gateRefusalCount} says how many gates refused, and {@code gateRefused} and
 * {@code runRefused} say which of the two lists is the reason — because either alone can refuse a
 * close, and a client that read only the gate's count would see {@code 0} on a 409.
 *
 * <h2>How the status codes are put on the wire</h2>
 *
 * <p>Through {@code Routes.route}, which registers a path <b>subtree</b> and lets the handler choose
 * its own status. That primitive exists because {@code get} and {@code post} cover the console's
 * shape — one verb, one fixed path, always 200 — and none of 06 § 4's: the run and period ids are in
 * the path, {@code GET /api/periods/{id}} and {@code POST /api/periods/{id}/close} share a prefix the
 * JDK's longest-prefix dispatch makes one context, and the close has to answer 409. Everything else
 * in this module is still a 200, including every refusal: a run into a closed period, a book that is
 * not on file, a replay of a superseded run. The status codes here are the ones 06 § 4 specifies for
 * a client that has no other way to ask — a close that did not happen, a resource that is not there —
 * and each still carries the complete answer in its body.
 *
 * <h2>What this module does not add</h2>
 *
 * <p><b>No {@code PATCH /periods/{id}} and no reopen.</b> A closed period is immutable (FR-902), and
 * {@code PeriodStatus.CLOSED} has no legal successor — not as an omission but because "reopening a
 * period is the mutation FR-902 forbids wearing a status change". A correction is
 * {@code POST /restatements}, recognised in the current open period, leaving the original published
 * figures intact and still replayable. Any verb other than {@code GET} on
 * {@code /api/periods/{id}} answers 405 and says this.
 *
 * <p><b>The close gate is not re-implemented, re-derived, or second-guessed.</b> Every figure and
 * every refusal in the close response comes from {@code EirService.close}, which assembles the
 * evidence and puts it to {@code RunClose.present} → {@code PeriodCloseGate}. This module decides
 * two things and nothing else: the status code, and whether the period's recorded status moves.
 *
 * <h2>Where this diverges from 06 § 4, deliberately</h2>
 *
 * <p>06 § 4 gives {@code POST /runs} a {@code 202} with a run id, which is right for the partitioned
 * batch it describes — accepted now, progress later, {@code eir-batch}'s job. This engine runs the
 * population inline on the request thread, so the run is <em>finished</em> before the response is
 * written: 202 would tell a caller to poll for something that has already happened, and the run
 * resource is readable immediately. So 200, with {@code status} reporting {@code COMPLETED}.
 *
 * <p><b>And the run id is assigned here, never accepted.</b> 06 § 4's request is
 * {@code {periodId, bookId}} and the id comes back in the response, which this module now enforces
 * rather than merely following: the console's own {@code POST /api/run} defaults its run to
 * {@code RUN-<periodId>-01}, so a caller naming that id put a run in this register under an id the
 * console could later publish a different run under — and the replay's {@code replayOf} check could
 * not tell the two apart. Ids minted here carry {@code API-RUN-} for that reason.
 */
public final class RunsAndPeriodsModule implements ApiModule {

    /** The resource prefixes. Distinct from the console's {@code /api/run} and {@code /api/close}. */
    private static final String RUNS = "/api/runs";
    private static final String PERIODS = "/api/periods";

    /** The one book this deployment holds; {@code EirService} runs no other. */
    private static final String BOOK_ID = "MAIN";

    /**
     * The period's first day, which is <b>not</b> {@code Seed.PERIOD_START}.
     *
     * <p>{@code Seed.PERIOD_START} is 2028-04-30, the date the book's flows are indexed from.
     * {@code ck_accounting_period_id_matches_dates} requires the period id to encode its own start
     * date, and 202805 encodes May, so the {@code ACCOUNTING_PERIOD} row starts on the 1st. Getting
     * this wrong files May's rows in April's partition and every total still ties, which is why the
     * constraint exists and why {@code AccountingPeriod} restates it rather than leaving it to the
     * database.
     */
    private static final LocalDate PERIOD_FIRST_DAY = LocalDate.of(2028, 5, 1);
    private static final String FISCAL_YEAR = "FY2028-29";

    /**
     * When the close began, in system time.
     *
     * <p>Deliberately the same instant {@code EirService.close} presents to the gate. Two different
     * values would mean the period this resource reports and the period the gate weighed disagree
     * about when its close started, and then {@code CLOSE_PREDATES_ITS_OWN_START} could refuse a
     * close that this register would have recorded, or the reverse. Fixed rather than read from a
     * clock: every instant in this book is an argument (04 § 5, "time is an input, always"), and a
     * clock here would also make the response bytes different on every request, which is the one
     * thing a byte-comparison test cannot work with.
     */
    private static final Instant CLOSING_STARTED_AT = Instant.parse("2028-06-02T09:00:00Z");

    private final EirService service;
    private final RunRegister runs = new RunRegister();
    private final PeriodRegister periods;

    public RunsAndPeriodsModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.periods = new PeriodRegister(PeriodRegister.of(
            AccountingPeriod.open(Seed.PERIOD_ID, FISCAL_YEAR, PERIOD_FIRST_DAY, Seed.PERIOD_END)));
    }

    /**
     * Registers the two resources, each as a subtree.
     *
     * <p>Through {@link Routes#route} and not through {@code get}/{@code post}, because three of
     * these five endpoints carry their subject in the path and one of them has to choose a status.
     * {@code GET /api/periods/{id}} and {@code POST /api/periods/{id}/close} are also two verbs under
     * one path prefix, which the JDK's longest-prefix dispatch makes one context whatever is
     * registered — so a resource that owns its subtree and dispatches on the method is not a
     * convenience here, it is the only arrangement that serves both endpoints at all.
     */
    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.route(RUNS, (exchange, body) ->
            runsResource(Resource.of(RUNS, exchange, body)));
        routes.route(PERIODS, (exchange, body) ->
            periodsResource(Resource.of(PERIODS, exchange, body)));
    }

    @Override
    public String specSection() {
        return "06 § 4";
    }

    // ============================================================== runs

    private Routes.Answer runsResource(Resource.Request request) {
        if (!request.belongs()) {
            return notThisResource(request, RUNS);
        }
        List<String> path = request.segments();
        if (path.isEmpty()) {
            return request.isPost()
                ? startRun(request.form())
                : Resource.methodNotAllowed("POST",
                    "POST " + RUNS + " starts a run for {periodId, bookId}; 06 § 4 has no listing"
                        + " of runs, and the runs of a period are on GET " + PERIODS + "/{id}");
        }
        if (path.size() == 1) {
            return request.isGet()
                ? runStatus(path.get(0))
                : Resource.methodNotAllowed("GET",
                    "GET " + RUNS + "/{id} reads a run; a run is started with POST " + RUNS
                        + " and replayed with POST " + RUNS + "/{id}/replay. A published run is"
                        + " never edited — a correction is a restatement (FR-902).");
        }
        if (path.size() == 2 && "replay".equals(path.get(1))) {
            return request.isPost()
                ? replayRun(path.get(0))
                : Resource.methodNotAllowed("POST",
                    "POST " + RUNS + "/{id}/replay replays a run to a shadow table and reports the"
                        + " byte comparison (DT-1)");
        }
        return Resource.notFound(request.path(),
            "the runs resource is POST " + RUNS + ", GET " + RUNS + "/{id}, and POST " + RUNS
                + "/{id}/replay (06 § 4)");
    }

    /**
     * Starts an amortisation run for a period and a book.
     *
     * <p><b>Every refusal here is a 200 with a value, and that is not an inconsistency with the
     * close's 409.</b> 06 § 4 marks one endpoint as carrying a status, and it marks it because a
     * close is the thing a scheduler branches on. A run refused because the period is closed or the
     * book is unknown is an answer, and the caller needs the reason far more than it needs a code:
     * "no book 'RETAIL'" and "period 202805 is CLOSED" are different fixes and a 4xx would make them
     * the same event.
     *
     * <p>{@code periodId} and {@code bookId} are required and neither is defaulted. A missing period
     * id is a 400: it is not zero, and it is not "the only period on file" — defaulting it would let
     * a caller who thought they were running June get May's figures with June's expectations.
     */
    private Routes.Answer startRun(FormBody form) {
        int periodId = form.integer("periodId");
        String bookId = form.text("bookId");

        List<String> refusals = new ArrayList<>();
        Optional<AccountingPeriod> period = periods.find(periodId);
        if (period.isEmpty()) {
            refusals.add("no period " + periodId + " on this book; the periods on file are "
                + periods.ids());
        } else if (period.get().isClosed()) {
            // FR-902. A run publishes figures into a period, and a closed period's figures are
            // exactly what may not move. This is refused here rather than left to the close, because
            // a run that overwrote a closed period's carrying amounts would have already done the
            // damage by the time any gate looked.
            refusals.add("period " + periodId + " is CLOSED and a run publishes into a period;"
                + " FR-902 makes a closed period immutable, so there is nothing this run could"
                + " correct. A correction is POST /restatements, recognised in the current open"
                + " period, leaving the published figures intact and still replayable.");
        }
        if (!BOOK_ID.equals(bookId)) {
            refusals.add("no book '" + bookId + "' on this deployment; it holds one book, "
                + BOOK_ID + ". A production deployment resolves the book through"
                + " eir-persistence rather than holding it in memory.");
        }
        if (form.has("runId")) {
            // 06 § 4's request body is {periodId, bookId} and the run id comes back in the response.
            // Refusing to take one is not pedantry, it is the fix for a measured defect: a caller
            // naming RUN-202805-01 — which is exactly what the console's own POST /api/run defaults
            // to — put a run in this register under an id the console could later publish a
            // different run under, and the replay's replayOf check could not tell them apart. This
            // resource mints every id it serves, in its own namespace, so that check cannot be
            // fooled. A run id that cannot be named in a path (a slash, a quote) becomes impossible
            // for the same reason.
            refusals.add("this resource assigns run ids and does not accept one: 06 § 4's request"
                + " is {periodId, bookId} and the id comes back in the response. An id supplied"
                + " here could collide with one the console's own POST /api/run mints, and then a"
                + " replay could not tell which run it reproduced.");
        }
        if (!refusals.isEmpty()) {
            return Resource.ok(Json.object()
                .bool("started", false)
                .count("periodId", periodId)
                .str("bookId", bookId)
                .strings("refusals", refusals));
        }

        String runId = runs.nextRunId(periodId);
        // The run this endpoint starts is the SAME run the console's POST /api/run drives, through
        // the same entry point: EirService holds the completed run so that a close can read the
        // figures it published, and a run started here that the close could not see would give an
        // operator two engines. The form is rebuilt rather than forwarded because EirService.run
        // takes the run id from the body and this resource, not the caller, assigns it.
        Json.Obj body = service.run(FormBody.parse(
            "runId=" + URLEncoder.encode(runId, StandardCharsets.UTF_8)));
        runs.record(new RunRegister.Run(runId, periodId, bookId, body));

        JsonView view = JsonView.of(body);
        return Resource.ok(Json.object()
            .bool("started", true)
            .str("runId", runId)
            .count("periodId", periodId)
            .str("bookId", bookId)
            .str("status", runStatusOf(view))
            .str("runResource", RUNS + "/" + runId)
            .count("populationSize", view.count("populationSize"))
            .count("contractsProcessed", view.count("computed"))
            .count("quarantined", view.count("quarantined"))
            .count("unaccountedFor", view.count("unaccountedFor"))
            .count("exceptionCount", exceptionCount(view))
            .bool("reportsCleanClose", view.flag("reportsCleanClose"))
            .obj("run", body));
    }

    /**
     * A run's status and what it did.
     *
     * <p><b>The nested {@code run} object is the engine's own answer, byte for byte</b> — the same
     * bytes the {@code POST} that started this run returned. That is deliberate: a second rendering
     * of the same run would be a second account of it, and two accounts of one run is how a control
     * report and a general ledger end up disagreeing with nobody able to say which is right. The
     * fields lifted to the top level are the ones 06 § 4 names, so that a client does not have to
     * walk into the nested object to branch on them; the invariant results, the per-contract rows,
     * the journals and the exception descriptions are in there whole.
     */
    private Routes.Answer runStatus(String runId) {
        Optional<RunRegister.Run> found = runs.find(runId);
        if (found.isEmpty()) {
            return Resource.notFound(RUNS + "/" + runId,
                "no run '" + runId + "' on this deployment; the runs it has started are "
                    + runs.ids() + ". A run this process did not start is not absent from the"
                    + " engine — eir-persistence's AMORTISATION_RUN holds every published run,"
                    + " keyed by run and period.");
        }
        RunRegister.Run run = found.get();
        JsonView view = run.view();
        boolean isLatest = runs.latestFor(run.periodId())
            .map(RunRegister.Run::runId).orElseThrow().equals(runId);

        // One partition, and the response says so rather than implying a progress model this engine
        // does not have. 06 § 4 asks for per-partition progress because the batch it describes
        // partitions a ten-million-contract book; here the population is walked inline on the
        // request thread, so there is one partition and it is complete before the response exists.
        Json.Obj partition = Json.object()
            .str("partitionId", run.bookId() + "/" + run.periodId())
            .str("status", "COMPLETE")
            .count("contracts", view.count("populationSize"))
            .count("processed", view.count("computed"))
            .count("quarantined", view.count("quarantined"));

        return Resource.ok(Json.object()
            .str("runId", run.runId())
            .count("periodId", run.periodId())
            .str("bookId", run.bookId())
            .str("status", runStatusOf(view))
            .count("populationSize", view.count("populationSize"))
            .count("contractsProcessed", view.count("computed"))
            .count("quarantined", view.count("quarantined"))
            .count("unaccountedFor", view.count("unaccountedFor"))
            .count("exceptionCount", exceptionCount(view))
            .count("invariantResults", view.occurrencesOf("statement"))
            .count("invariantBreaches", view.occurrencesOf("satisfied", "false"))
            .bool("reportsCleanClose", view.flag("reportsCleanClose"))
            // Named for what it actually knows. "replayable" would be a claim about the engine's
            // working papers, and this resource cannot see them: the console's own POST /api/run
            // replaces them without telling anybody here. What this field knows is that no LATER run
            // was started through this resource for this period; the replay endpoint then checks
            // with the engine before reporting any byte comparison.
            .bool("latestOfThisResource", isLatest)
            .count("partitions", 1)
            .array("partitionProgress", List.of(partition))
            .obj("run", run.body()));
    }

    /**
     * Replays a run and reports the byte comparison — DT-1.
     *
     * <p><b>The check that this is the run being replayed is the control here.</b>
     * {@code EirService} holds the latest run per period and replays that; this register can name an
     * earlier run, and the console's own {@code POST /api/run} can publish a run this resource never
     * saw. Either way the engine would happily replay <em>a</em> run and this endpoint would report
     * its DT-1 under the id in the path — a byte comparison attributed to a run that was never
     * compared, which is the only failure mode of a reproduction control that matters. So the
     * request is refused where the id is not the latest, and the engine's own {@code replayOf} is
     * checked against the id afterwards.
     */
    private Routes.Answer replayRun(String runId) {
        Optional<RunRegister.Run> found = runs.find(runId);
        if (found.isEmpty()) {
            return Resource.notFound(RUNS + "/" + runId + "/replay",
                "no run '" + runId + "' on this deployment; the runs it has started are "
                    + runs.ids());
        }
        String latest = runs.latestFor(found.get().periodId())
            .map(RunRegister.Run::runId).orElseThrow();
        if (!latest.equals(runId)) {
            return Resource.ok(Json.object()
                .bool("replayed", false)
                .str("runId", runId)
                .str("latestRunId", latest)
                .strings("refusals", List.of(
                    "run '" + runId + "' is not the latest run for its period; this deployment"
                        + " holds one run's working papers at a time and the latest is '" + latest
                        + "'. Replaying that one and reporting its DT-1 as this run's byte"
                        + " comparison would be a reproduction claim about figures nothing"
                        + " compared.",
                    "eir-persistence's AMORTISATION_RUN and PERIOD_BALANCE hold every published"
                        + " run keyed by run and period, which is what makes a replay of a period"
                        + " closed last March possible; an in-memory book holds the last one.")));
        }

        Json.Obj body = service.replay(FormBody.parse(
            "shadowRunId=" + URLEncoder.encode("SHADOW-" + runId, StandardCharsets.UTF_8)));
        JsonView view = JsonView.of(body);
        if (!view.flag("ran")) {
            return Resource.ok(Json.object()
                .bool("replayed", false)
                .str("runId", runId)
                .strings("refusals", List.of(view.text("message")))
                .obj("replay", body));
        }
        String replayOf = view.text("replayOf");
        if (!runId.equals(replayOf)) {
            return Resource.ok(Json.object()
                .bool("replayed", false)
                .str("runId", runId)
                .str("engineReplayed", replayOf)
                .strings("refusals", List.of(
                    "the engine replayed run '" + replayOf + "', not '" + runId + "'. Something"
                        + " published a run this resource never saw — the console's own POST"
                        + " /api/run does exactly that — so the DT-1 just computed is not this"
                        + " run's and is not reported as it.")));
        }

        return Resource.ok(Json.object()
            .bool("replayed", true)
            .str("runId", runId)
            .str("shadowRunId", view.text("shadowRunId"))
            .bool("dtOneSatisfied", view.flag("dtOneSatisfied"))
            .bool("provesReproduction", view.flag("provesReproduction"))
            .str("coverage", view.text("coverage"))
            // provesReproduction is the answer to read, not dtOneSatisfied: DT-1 passes over a
            // period that published nothing, correctly, because nothing failed. The engine's own
            // caveats say so and they are in the nested body.
            .str("readThisOne", "provesReproduction")
            .obj("replay", body));
    }

    /**
     * The run's status, and what it can and cannot be.
     *
     * <p><b>There is no {@code QUEUED} and no {@code RUNNING}</b>, because this engine walks the
     * population on the request thread and the run is finished before the response is written.
     * Publishing states that cannot occur would be a progress model that reads as coverage; they
     * arrive with {@code eir-batch}, along with the partition progress that makes them mean
     * something.
     *
     * <p><b>What it can vary on is the one thing worth reporting.</b> The status is derived from the
     * run's own population accounting rather than asserted, so a run that returned without
     * accounting for every contract it was given is not reported as {@code COMPLETED}: FR-905's
     * failure mode is a run over ten million contracts that processed 9,999,998 and reconciled
     * perfectly, because the two it dropped are absent from both sides of every total.
     */
    private static String runStatusOf(JsonView view) {
        return view.count("unaccountedFor") == 0 ? "COMPLETED" : "COMPLETED_WITH_A_SHORTFALL";
    }

    /**
     * A path that only shares this resource's opening characters.
     *
     * <p>{@code Routes.route} registers a subtree, and the JDK matches a context by string prefix, so
     * {@code /api/runsomething} arrives at the {@code /api/runs} context. Answered 404 rather than
     * read as the run named {@code omething}.
     */
    private static Routes.Answer notThisResource(Resource.Request request, String prefix) {
        return Resource.notFound(request.path(),
            "the resource at " + prefix + " answers for " + prefix + " and its children; '"
                + request.path() + "' only shares its opening characters");
    }

    /**
     * How many contracts the run isolated with an exception recorded against them.
     *
     * <p>Counted from the run's own per-contract rows — every quarantined contract carries an
     * {@code exceptionCategory} and no computed one does — rather than from the {@code exceptions}
     * array, whose elements are prose sentences carrying contract ids and rupee figures. Counting
     * those would mean splitting on a delimiter that appears inside the sentences.
     */
    private static int exceptionCount(JsonView view) {
        return view.occurrencesOf("exceptionCategory");
    }

    // ============================================================== periods

    private Routes.Answer periodsResource(Resource.Request request) {
        if (!request.belongs()) {
            return notThisResource(request, PERIODS);
        }
        List<String> path = request.segments();
        if (path.isEmpty()) {
            return request.isGet()
                ? Resource.ok(periodList())
                : Resource.methodNotAllowed("GET",
                    "GET " + PERIODS + " lists the periods and their status; a period is closed"
                        + " with POST " + PERIODS + "/{id}/close");
        }
        int periodId = periodId(path.get(0));
        if (path.size() == 1) {
            if (!request.isGet()) {
                return Resource.methodNotAllowed("GET",
                    "GET " + PERIODS + "/{id} reads a period. The only write on a period is POST "
                        + PERIODS + "/{id}/close: there is deliberately no PATCH " + PERIODS
                        + "/{id} and no reopen endpoint, because a closed period is immutable"
                        + " (FR-902) and CLOSED has no legal successor. A correction is POST"
                        + " /restatements, recognised in the current open period.");
            }
            Optional<AccountingPeriod> period = periods.find(periodId);
            return period.isPresent()
                ? Resource.ok(periodRow(period.get()))
                : unknownPeriod(PERIODS + "/" + periodId, periodId);
        }
        if (path.size() == 2 && "close".equals(path.get(1))) {
            return request.isPost()
                ? closePeriod(periodId, request.form())
                : Resource.methodNotAllowed("POST",
                    "POST " + PERIODS + "/{id}/close runs the close workflow (FR-901)");
        }
        return Resource.notFound(request.path(),
            "the periods resource is GET " + PERIODS + ", GET " + PERIODS + "/{id}, and POST "
                + PERIODS + "/{id}/close (06 § 4)");
    }

    private Json.Obj periodList() {
        List<Json.Obj> rows = new ArrayList<>();
        for (AccountingPeriod period : periods.all()) {
            rows.add(periodRow(period));
        }
        return Json.object()
            .count("count", rows.size())
            .array("periods", rows);
    }

    /**
     * One period, its status, and its attestation where it has one.
     *
     * <p>{@code closeAttempts} and {@code lastCloseOutcome} are here because a refused close returns
     * the period to {@code OPEN} — the ordinary outcome of step 4 finding red, since the fix is
     * upstream in data the period has to reopen to receive — and {@code abandonClose} clears the
     * {@code closingStartedAt} that was the only evidence the attempt happened. A period that reads
     * exactly like one nobody has tried to close is not a useful answer to an operator asking why
     * the month is still open.
     */
    private Json.Obj periodRow(AccountingPeriod period) {
        return Json.object()
            .count("periodId", period.periodId())
            .str("fiscalYear", period.fiscalYearLabel())
            .str("periodStart", period.periodStartDate().toString())
            .str("periodEnd", period.periodEndDate().toString())
            .str("status", period.status().name())
            .bool("closed", period.isClosed())
            .bool("closeable", period.status().isCloseable())
            .str("closingStartedAt", instant(period.closingStartedAt()))
            .str("closedBy", period.closedBy())
            .str("closedAt", instant(period.closedAt()))
            .str("versionCutoffAt", instant(period.versionCutoffAt()))
            .count("closeAttempts", periods.attempts(period.periodId()))
            .str("lastCloseOutcome", periods.lastOutcome(period.periodId()))
            .strings("runs", runs.idsFor(period.periodId()))
            .strings("legalSuccessors", statusNames(period))
            .str("describe", period.describe());
    }

    /**
     * The close workflow: 200 where the gate permitted, <b>409 where any gate failed</b>.
     *
     * <p>The order of what follows is the control. The period's own status is tested first, and a
     * {@code CLOSED} period is refused <em>without asking the gate</em> — not as an optimisation but
     * because {@code EirService.close} builds a fresh {@code OPEN} period per request and moves it to
     * {@code CLOSING} itself, so a gate asked here would be shown a period that is open in its view
     * and closed in this register's, and it would permit. FR-902's immutability would then hold in
     * every document and nowhere in the code. {@code gateAsked} in the body records which of the two
     * paths answered.
     *
     * <p>Then the period moves to {@code CLOSING}, the gate is asked through
     * {@code EirService.close}, and the answer decides both the status code and whether the period
     * moves to {@code CLOSED} or back to {@code OPEN}. Nothing here re-derives any of it: whether the
     * close may proceed is read from the presentation's own {@code mayClose}, which is
     * {@code runRefusals.isEmpty() && !decision.isRefused()} — both lists, which is why neither can
     * be dropped from the body.
     */
    private Routes.Answer closePeriod(int periodId, FormBody form) {
        Optional<AccountingPeriod> found = periods.find(periodId);
        if (found.isEmpty()) {
            return unknownPeriod(PERIODS + "/" + periodId + "/close", periodId);
        }
        AccountingPeriod period = found.get();
        if (period.isClosed()) {
            List<String> refusals = List.of(
                "period " + periodId + " is already CLOSED — " + period.describe()
                    + ". CLOSED is terminal (FR-902): it has no legal successor, there is no"
                    + " PATCH and no reopen endpoint, and a second close would be the mutation"
                    + " the requirement forbids wearing a status change.",
                "A correction to a closed period is POST /restatements: it records the cause,"
                    + " the approver and the policy version, recognises the adjustment in the"
                    + " current open period, and leaves the original published figures intact"
                    + " and still replayable (07 § 4.4).");
            return Resource.conflict(Json.object()
                .bool("mayClose", false)
                .bool("gateAsked", false)
                .count("periodId", periodId)
                .str("periodStatus", period.status().name())
                .str("closedBy", period.closedBy())
                .str("closedAt", instant(period.closedAt()))
                .count("refusalCount", refusals.size())
                .strings("refusals", refusals)
                .str("note", "the close gate was not asked. This refusal is the register's own,"
                    + " and it has to be: EirService.close presents the gate a period it opens"
                    + " per request, so a gate asked here would see an OPEN period and permit."));
        }

        periods.beginClose(periodId, CLOSING_STARTED_AT);
        try {
            return putItToTheGate(periodId, form);
        } catch (RuntimeException defect) {
            // The failing input, measured: before this catch existed, a close that threw left the
            // period in CLOSING for the life of the process — status CLOSING, lastCloseOutcome "in
            // progress", nobody closing it — while runs went on publishing into a period that had
            // supposedly stopped taking postings. A close that did not happen must not leave behind
            // a status saying one is under way. Every path out of the gate is covered and not just
            // the parse above, because a response shape that changed under JsonView throws here too
            // and would strand the period identically.
            //
            // Guarded on the period still being CLOSING, because putItToTheGate records a permitted
            // close before it renders the response: a throw after that point must not stamp a
            // CLOSED, fully attested period with "abandoned", which is a closed period that reads
            // as though its close failed.
            //
            // The defect is still reported: it is rethrown, and EirServer.route maps it exactly as
            // every other route does — 400 for a malformed request, 500 with its own message for a
            // defect. Swallowing it to tidy the status would turn an engine defect into a
            // data-quality ticket against a contract that is fine.
            if (periods.find(periodId).map(open -> open.status().isCloseable()).orElse(false)) {
                periods.abandonClose(periodId,
                    "abandoned: the close failed with " + defect.getClass().getSimpleName());
            }
            if (defect instanceof DateTimeParseException notAnInstant) {
                // Classified rather than passed on as a defect. EirService parses closedAt with
                // Instant.parse instead of through a FormBody accessor, so 'closedAt=nope' arrives
                // here as a DateTimeParseException and would be answered 500 — telling an operator
                // the engine is broken when the request was. Reclassified where the format is
                // already known to be wrong, without this module parsing or defaulting the field
                // itself: EirService still owns the value, and a second parse here would be a
                // second opinion about the format.
                throw new FormBody.BadRequest("'closedAt' must be an ISO-8601 instant such as"
                    + " 2028-06-05T09:00:00Z: " + notAnInstant.getMessage());
            }
            throw defect;
        }
    }

    /**
     * The part of a close that runs with the period already in {@code CLOSING}.
     *
     * <p>Split out so that {@link #closePeriod} can guarantee the period does not stay there if
     * anything in here throws. Everything below reads the gate's answer; nothing decides it.
     */
    private Routes.Answer putItToTheGate(int periodId, FormBody form) {
        Json.Obj body = service.close(form);
        JsonView view = JsonView.of(body);

        if (!view.flag("ran")) {
            // No run, so there are no figures to close over. A gate refusal in substance — FR-901's
            // step 4 has nothing to be green about — and 06 § 4's status applies to it.
            AccountingPeriod reopened =
                periods.abandonClose(periodId, "refused: no run to close over");
            return Resource.conflict(Json.object()
                .bool("mayClose", false)
                .bool("gateAsked", false)
                .count("periodId", periodId)
                .str("periodStatus", reopened.status().name())
                .bool("closeAbandoned", true)
                .count("refusalCount", 1)
                .strings("refusals", List.of(view.text("message")))
                .obj("close", body));
        }

        boolean mayClose = view.flag("mayClose");
        int gateRefusals = view.count("refusalCount");
        boolean runRefused = runRefused(view);
        if (!mayClose) {
            AccountingPeriod reopened = periods.abandonClose(periodId,
                "refused by " + refusalSources(gateRefusals > 0, runRefused));
            return Resource.conflict(Json.object()
                .bool("mayClose", false)
                .bool("gateAsked", true)
                .bool("closeAbandoned", true)
                .count("periodId", periodId)
                .str("periodStatus", reopened.status().name())
                // BOTH lists are named at the top level, and each has its own flag and its own
                // count where a count exists. mayClose() is `runRefusals.isEmpty() &&
                // !decision.isRefused()`, so either list alone can refuse a close — and
                // RunClose says the gate-permits-run-refuses case is real: "A run over an empty
                // population produces a clean gate decision, which is exactly why the run's own
                // verdict is asked separately." A client that read only gateRefusalCount would see
                // 0 on a 409 and have nothing to act on.
                .bool("gateRefused", gateRefusals > 0)
                .count("gateRefusalCount", gateRefusals)
                .bool("runRefused", runRefused)
                .strings("refusalSources", sourceList(gateRefusals > 0, runRefused))
                // The gate's own list, one refusal to a line, exactly as CloseDecision.describe()
                // renders it. Re-emitted at the top level rather than only nested, because a client
                // that reads the status must not have to walk into a sub-object to find out what
                // failed — but ONLY where the gate actually refused. CloseDecision.describe() of a
                // permitted decision reads "CLOSE PERMITTED — period 202805 … CLOSED", and putting
                // that at the top of a 409 would be a body asserting the opposite of its status.
                .str("gateVerdict", gateRefusals > 0
                    ? view.text("gateVerdict")
                    : "the close gate permitted on the evidence it was given; the refusal is the"
                        + " run's own — see runRefused and close.runRefusals. The gate weighs the"
                        + " evidence presented and cannot know that the evidence covers nothing.")
                // No count of breaches: EirService's close body carries every red result twice,
                // once in the collapsed dashboard it presents as `evidence` and once in `breaches`,
                // and a control report that double-counts its own reds is how an operator learns to
                // stop reading it. The list is nested whole under `close.breaches`.
                .figure("totalDeviation", new BigDecimal(view.text("totalDeviation")))
                .strings("readAllOfThese", List.of(
                    "gateVerdict — the close gate's refusals, one to a line: the reconciliation"
                        + " ties, the exception queue with its acceptances weighed under four eyes,"
                        + " the period's attestation, and the run-level invariants SL-1, SL-2 and"
                        + " RC-1.",
                    "close.runRefusals — the run's own reasons it cannot support a close: its"
                        + " population arithmetic, its unasserted obligations, its per-contract"
                        + " breaches. Sourced from the run and not from the gate, because the gate"
                        + " weighs the evidence it was given and cannot know that the evidence"
                        + " covers nothing.",
                    "close.breaches — every red invariant result behind those refusals, with the"
                        + " deviation each is out by."))
                .str("note", "409 is the intended answer here (06 § 4), not an error. The period is"
                    + " OPEN again so the fix can be posted to it; nothing has been closed and"
                    + " nothing has been lost.")
                .obj("close", body));
        }

        // Permitted. The gate has already decided; this records the outcome and the attestation.
        // closedBy and closedAt are read back from the answer rather than from the request, so that
        // what is recorded is what the gate weighed — EirService defaults both where the caller
        // omitted them, and a second set of defaults here could differ from the first.
        String closedBy = view.text("closedBy");
        Instant closedAt = Instant.parse(view.text("closedAt"));
        Instant cutoff = versionCutoff();
        AccountingPeriod closed = periods.recordClose(periodId, closedBy, closedAt, cutoff);
        return Resource.ok(Json.object()
            .bool("mayClose", true)
            .bool("closed", true)
            .bool("gateAsked", true)
            .count("periodId", periodId)
            .str("periodStatus", closed.status().name())
            .str("closedBy", closedBy)
            .str("closedAt", closedAt.toString())
            .str("versionCutoffAt", cutoff.toString())
            // The same two flags the refusal carries, both false here. A field that only appears on
            // one branch is a field a client learns to treat as optional, and then its absence and
            // its being false read the same.
            .bool("gateRefused", false)
            .count("gateRefusalCount", 0)
            .bool("runRefused", false)
            .str("gateVerdict", view.text("gateVerdict"))
            .str("attestation", closed.describe())
            .str("immutability", "CLOSED is terminal (FR-902). This period will not close again,"
                + " will not reopen, and has no PATCH; a correction is POST /restatements.")
            .obj("close", body));
    }

    /**
     * Whether the <b>run's own</b> refusal list is non-empty.
     *
     * <p>The list itself cannot be lifted to the top level: its elements are prose sentences carrying
     * contract ids and rupee figures, and splitting a rendered JSON array of those on a delimiter
     * would split on the commas inside them. Whether it is <em>empty</em> is exact, though —
     * {@code Json.Obj.strings} writes an empty array as {@code []} and a non-empty one as
     * {@code ["…}. So the flag is exact, the count is not published because it cannot be read
     * honestly, and the list comes back whole under {@code close.runRefusals}.
     */
    private static boolean runRefused(JsonView view) {
        return view.occurrencesOf("runRefusals", "[]") == 0;
    }

    /** Which of the two lists refused, as one phrase for the period register's record. */
    private static String refusalSources(boolean gateRefused, boolean runRefused) {
        if (gateRefused && runRefused) {
            return "the close gate and the run's own verdict";
        }
        return gateRefused ? "the close gate" : "the run's own verdict";
    }

    /** Which of the two lists refused, named so a client knows where to look for each. */
    private static List<String> sourceList(boolean gateRefused, boolean runRefused) {
        List<String> sources = new ArrayList<>(2);
        if (gateRefused) {
            sources.add("the close gate — gateVerdict, and close.evidence for what it weighed");
        }
        if (runRefused) {
            sources.add("the run's own verdict — close.runRefusals");
        }
        return sources;
    }

    /**
     * The system-time boundary a replay of this period must read as at (04 § 5).
     *
     * <p>Read from the book rather than invented: {@code recordedAsAt} is the instant the book's
     * figures were known, which is precisely the {@code recorded_at} horizon a replay of the closed
     * period has to read at to reproduce it. The DDL's comment on the column says why it is stored
     * and not computed — "as at the close" is otherwise a wall-clock guess, and a replay that guesses
     * reads today's version set and fails DT-1.
     */
    private Instant versionCutoff() {
        return Instant.parse(JsonView.of(service.book()).text("recordedAsAt"));
    }

    private Routes.Answer unknownPeriod(String path, int periodId) {
        return Resource.notFound(path,
            "no period " + periodId + " on this deployment; the periods on file are "
                + periods.ids() + ". A period the engine does not have is an absence and not a"
                + " refusal — there is nothing for a gate to weigh.");
    }

    /**
     * The period id from the path.
     *
     * <p>A 400 where it is not a whole number, through the same exception the form accessors use, so
     * that {@code /api/periods/last-month} is a malformed request and not a period that happens not
     * to exist. The {@code YYYYMM} shape itself is {@code AccountingPeriod}'s to enforce and is not
     * restated here.
     */
    private static int periodId(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException notANumber) {
            throw new FormBody.BadRequest("a period id is a YYYYMM whole number, got '"
                + segment + "'");
        }
    }

    private static List<String> statusNames(AccountingPeriod period) {
        List<String> names = new ArrayList<>();
        period.status().legalSuccessors().forEach(status -> names.add(status.name()));
        return names;
    }

    private static String instant(Instant at) {
        return at == null ? null : at.toString();
    }
}
