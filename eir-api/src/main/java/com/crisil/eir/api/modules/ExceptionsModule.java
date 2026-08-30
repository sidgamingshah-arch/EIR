package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.run.ContractPipeline;
import com.crisil.eir.application.run.MonthEndRun;
import com.crisil.eir.application.run.SolveAudit;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.ExceptionStatus;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The exception work queue: list by status and category, resolve with a mandatory note, accept with
 * approval under four eyes.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 6, over the {@code EXCEPTION} entity and the
 * ten categories of {@code docs/04-data-model.md} 04 § 3, in service of FR-905.
 *
 * <p><b>Acceptance is the most attacked control in this module, and the reason is arithmetic.</b>
 * All ten of 04 § 3's categories answer {@code true} to
 * {@link ExceptionCategory#blocksClose()}, so there is no category a close can walk past and
 * acceptance is the <em>only</em> route past a queued exception. That makes acceptance the control,
 * and a self-approved acceptance the way past every other control in the package: sign your own
 * approval and the quarantine, the population arithmetic and the sub-ledger break all stop
 * mattering. So the maker and the checker are compared through
 * {@link FourEyes#isSelfApproval}, which strips and case-folds at {@code Locale.ROOT} exactly as
 * the {@code lower(btrim(...))} constraints in the DDL do, and the comparison is reported on the
 * response rather than being used to reject the request. Rejecting it here would be the wrong
 * shape twice over: {@code PeriodCloseGate} is where
 * {@link com.crisil.eir.policy.close.CloseGateRefusal#SELF_APPROVED_ACCEPTANCE} lives, and a
 * refusal this layer manufactured would leave that one unreachable — a control that cannot fire.
 *
 * <p><b>Three things this module deliberately does not do.</b> It does not decide that an
 * acceptance is void (the gate does). It does not default a resolution note (04 § 3 makes it
 * mandatory, because a resolved row with no note is indistinguishable from one nobody looked at).
 * And it does not carry an acceptance across runs — see {@link #acceptCaveats()} and
 * {@code ACCEPTANCE_WITHOUT_AN_EXCEPTION}, which is the gate refusing to read one run's approvals
 * as authority to close over another's problems.
 *
 * <p><b>Where this module's queue comes from, and the seam that is missing.</b> {@link EirService}
 * holds the authoritative queue — the records the console's {@code POST /api/run} raised — in a
 * private field and exposes no read of it. So this module keeps its own {@link ExceptionWorkQueue},
 * derived once, on first use, by rolling the seeded book forward through the same
 * {@link ContractPipeline} and {@link MonthEndRun} that {@code EirService.run} uses, with the same
 * run id. It therefore agrees with the console's queue on the book as seeded — C-0003 arrives with
 * period movements and no opening position and files under {@code MISSING_MANDATORY_FIELD} — and it
 * does not see a contract repaired through {@code /api/repair} or onboarded through
 * {@code /api/onboard} afterwards. That divergence is reported on every response rather than left
 * to be discovered, and the seam that removes it is a read accessor for the last run's exceptions
 * on {@code EirService}.
 *
 * <p><b>What follows from that, and is the most important sentence in this class.</b> A status on
 * this module's queue is not what {@code PeriodCloseGate} reads, so <em>nothing this module reports
 * is an answer to whether the exception gate is clear</em> — {@code POST /api/close} is. The two
 * mutations therefore reach through to the engine wherever the engine has somewhere to reach:
 * {@link #accept} records the acceptance on the engine's own queue because that is the list the
 * gate weighs, and {@link #resolve} delegates the one input correction the engine offers, because a
 * resolution that corrected nothing leaves the close refusing with
 * {@code EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED} however this module's row is labelled. Both hand
 * the engine's answer back verbatim, nested, so a caller reads what the engine did rather than what
 * this module recorded.
 */
public final class ExceptionsModule implements ApiModule {

    /** 06 § 6's work queue. */
    public static final String QUEUE_PATH = "/api/exceptions";

    /** 06 § 6's resolution. See {@link #register} on why the id is not in the path. */
    public static final String RESOLVE_PATH = "/api/exceptions/resolve";

    /** 06 § 6's acceptance. See {@link #register} on why the id is not in the path. */
    public static final String ACCEPT_PATH = "/api/exceptions/accept";

    /** Everything else under the queue — the {@code /{id}/…} shape. See {@link #register}. */
    public static final String PATH_ID_PATH = "/api/exceptions/";

    /**
     * The run id the derived queue attributes its rows to.
     *
     * <p>The same id {@code EirService.run} defaults to, so a row listed here and the same row in
     * the console's run report name one run rather than two. 04 § 2.13 requires an exception to tie
     * back to a run, and two different ids for one roll-forward would make the tie a lie.
     */
    private static final String DERIVED_RUN_ID = "RUN-" + Seed.PERIOD_ID + "-01";

    /** The book id and the instant the seeded book's figures were known, as {@code EirService}. */
    private static final String BOOK_ID = "MAIN";
    private static final Instant KNOWN_AT = Instant.parse("2028-06-01T00:00:00Z");

    private final EirService service;
    private final Supplier<ExceptionQueue> source;

    /** Built on first use, never in the constructor. See {@link #workQueue()}. */
    private ExceptionWorkQueue workQueue;

    public ExceptionsModule(EirService service) {
        this(service, ExceptionsModule::queueOfTheSeededBook);
    }

    /**
     * A module over a supplied queue, for the tests that have to construct a queue this book cannot
     * raise — two rows of one category on one contract, or two rows a single console acceptance
     * would sign for at once. Neither is reachable through the seeded book, and a control whose
     * condition no test can construct is a control nobody has seen fire.
     */
    ExceptionsModule(EirService service, Supplier<ExceptionQueue> source) {
        this.service = Objects.requireNonNull(service, "service");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Registers 06 § 6's three endpoints, and one fourth that exists to explain a deviation.
     *
     * <p><b>Why the id is a body field and not a path segment.</b> 06 § 6 writes
     * {@code POST /exceptions/{id}/resolve} and {@code POST /exceptions/{id}/accept}.
     * {@link Routes#post} hands a handler the parsed form body and not the exchange, so a POST
     * handler cannot read its own request path; and the JDK's HTTP server matches a context by
     * longest path prefix, so both of those paths fall into one {@code /api/exceptions/} context
     * with nothing to tell {@code resolve} from {@code accept}. Guessing the action from which
     * fields the body happens to carry is the defect FR-507 names in another guise — treatment
     * inferred from an observation — and here the guess that goes wrong turns a resolution into an
     * acceptance, which is the difference between a defect that was fixed and a period closed over
     * one that stands. So the action is the path and the id is a body field, and
     * {@link #PATH_ID_PATH} answers the specified shape with a 400 that names the seam instead of a
     * bewildering 405 from the listing route.
     */
    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get(QUEUE_PATH, this::listQueue);
        routes.post(RESOLVE_PATH, this::resolve);
        routes.post(ACCEPT_PATH, this::accept);
        routes.post(PATH_ID_PATH, body -> {
            throw new FormBody.BadRequest("06 § 6 addresses an exception as"
                + " /api/exceptions/{id}/resolve and /api/exceptions/{id}/accept, and this server"
                + " cannot read a POST handler's own path: Routes.post carries the form body and"
                + " not the exchange, and one prefix context cannot tell the two actions apart."
                + " Post to " + RESOLVE_PATH + " or " + ACCEPT_PATH + " with the id as a form"
                + " field. GET " + QUEUE_PATH + " reports the id of every queued exception");
        });
    }

    @Override
    public String specSection() {
        return "06 § 6";
    }

    // ============================================================ GET /api/exceptions

    /**
     * 06 § 6's work queue, filterable on status and category.
     *
     * <p>The response leads with the whole-queue arithmetic — how many rows, how many block the
     * close, which contracts have no figure — and only then the filtered rows, because a work queue
     * read through a filter is the easiest place in this API to conclude that nothing is wrong. A
     * caller who asks for {@code ?category=NO_SOLUTION} on a queue holding forty
     * {@code MISSING_MANDATORY_FIELD} rows gets an empty {@code exceptions} array and
     * {@code "blocksClose":true} on the same response.
     *
     * <p>The query string is parsed by {@link FormBody}, which is the same {@code k=v&k=v} grammar
     * the request bodies use. <b>Presence is decided on the raw query and not on
     * {@link FormBody#has}</b>, which answers false for a present-but-blank value: read that way,
     * {@code ?status=} would mean "no filter" and answer the whole queue, which is the same defect
     * as ignoring an unrecognised value. Sent blank, the parameter reaches
     * {@link FormBody#text} and refuses.
     */
    private Json.Obj listQueue(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        FormBody query = FormBody.parse(rawQuery);
        ExceptionStatus status = named(rawQuery, "status")
            ? parse(ExceptionStatus.class, query.text("status"), "status") : null;
        ExceptionCategory category = named(rawQuery, "category")
            ? parse(ExceptionCategory.class, query.text("category"), "category") : null;

        ExceptionWorkQueue queue = workQueue();
        List<ExceptionRecord> matching = queue.list(status, category);
        List<Json.Obj> rows = new ArrayList<>(matching.size());
        for (ExceptionRecord record : matching) {
            rows.add(row(record));
        }

        List<Json.Obj> counts = new ArrayList<>();
        for (Map.Entry<ExceptionCategory, Integer> count
                : queue.queue().countByCategory().entrySet()) {
            counts.add(Json.object()
                .str("category", count.getKey().name())
                .count("raised", count.getValue()));
        }

        return Json.object()
            .count("periodId", Seed.PERIOD_ID)
            .str("raisedByRunId", DERIVED_RUN_ID)
            .str("statusFilter", status == null ? null : status.name())
            .str("categoryFilter", category == null ? null : category.name())
            .count("queued", queue.size())
            .count("returned", rows.size())
            .count("closeBlockers", queue.queue().closeBlockers().size())
            .bool("blocksClose", queue.queue().blocksClose())
            .str("gate", queue.queue().describeCloseGate())
            .strings("quarantinedContracts",
                List.copyOf(queue.queue().quarantinedContracts()))
            .strings("demotedContracts", List.copyOf(queue.queue().demotedContracts()))
            .array("countByCategory", counts)
            .array("exceptions", rows)
            .strings("categories", names(ExceptionCategory.values()))
            .strings("statuses", names(ExceptionStatus.values()))
            .strings("caveats", queueCaveats());
    }

    /**
     * One queue row.
     *
     * <p>{@code blocksClose}, {@code quarantinesContract} and {@code demotesContract} are three
     * separate answers and all three are reported, because they come apart:
     * {@code ACCEPTED_WITH_APPROVAL} stops blocking the close and does <b>not</b> lift the
     * quarantine, and {@code STALE_EQUIVALENCE_TEST} blocks the close without stopping its
     * contract. A single "worked" flag over the three would let an operator conclude that an
     * accepted exception put its contract's figure back into the population.
     */
    private static Json.Obj row(ExceptionRecord record) {
        return Json.object()
            .str("id", ExceptionWorkQueue.idOf(record))
            .str("contractId", record.contractId())
            .str("raisedByRunId", record.raisedByRunId())
            .str("category", record.category().name())
            .str("status", record.status().name())
            .str("detail", record.detail())
            .str("payloadRef", record.payloadRef())
            .str("resolvedBy", record.resolvedBy())
            .str("resolutionNote", record.resolutionNote())
            .bool("blocksClose", record.blocksClose())
            .bool("quarantinesContract", record.quarantinesContract())
            .bool("demotesContract", record.demotesContract())
            .bool("hasDiagnostic", record.diagnostic().isPresent())
            .str("describe", record.describe());
    }

    // ================================================= POST /api/exceptions/resolve

    /**
     * 06 § 6's resolution, with 04 § 3's mandatory note.
     *
     * <p><b>The note is read with {@link FormBody#text}, never {@link FormBody#textOr}.</b> That is
     * the whole control: {@code text} refuses an absent field and refuses a blank one, so a
     * resolution posted with {@code note=} comes back a 400 naming the field rather than a row that
     * reads as worked and carries no reason. 04 § 3 makes the note mandatory because a resolved row
     * with no note is indistinguishable from one nobody looked at, and the next run raises the same
     * exception on the same input.
     *
     * <p>{@code resolvedBy} is mandatory for the same reason and a different one: a fix nobody
     * signed cannot be evidence that the input was corrected.
     *
     * <p>Resolution is not acceptance. It asserts that a recomputation will now succeed, so it
     * lifts the contract's quarantine — which is why claiming it for a defect that still stands is
     * worse than accepting: it puts a contract back into the reported population with no figure
     * behind it. The response says which of the two happened in those words.
     *
     * <p><b>A recorded resolution is not, by itself, a fixed input, and this endpoint does not
     * pretend otherwise.</b> {@code PeriodCloseGate} reads the engine's own queue, and
     * {@link EirService} exposes no resolution path — so a row marked resolved here and not
     * corrected on the book still refuses the close with
     * {@code EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED}, correctly. What does change what the next run
     * raises is the <em>input correction</em>, and the engine has exactly one:
     * {@code EirService.repair} supplies a missing opening balance. So a resolution of a
     * {@link ExceptionCategory#MISSING_MANDATORY_FIELD} row delegates to it and hands back its
     * answer verbatim — {@code "repaired":true} means the book was corrected and the next run will
     * raise nothing for that contract; {@code "repaired":false} means nothing on the book changed
     * and the resolution is a claim with no correction behind it. For every other category the
     * engine has no remediation path at all, and the response says so rather than implying a fix.
     */
    private Json.Obj resolve(FormBody body) {
        ExceptionWorkQueue queue = workQueue();
        ExceptionRecord row = queue.find(body.text("id"));
        String resolvedBy = body.text("resolvedBy");
        String note = body.text("note");
        String previously = row.isOpen() ? null : row.status() + " by " + row.resolvedBy();

        ExceptionRecord resolved = queue.resolve(row, resolvedBy, note);
        return Json.object()
            .bool("worked", true)
            .str("id", ExceptionWorkQueue.idOf(resolved))
            .str("contractId", resolved.contractId())
            .str("category", resolved.category().name())
            .str("status", resolved.status().name())
            .str("resolvedBy", resolved.resolvedBy())
            .str("resolutionNote", resolved.resolutionNote())
            .str("replacedWorking", previously)
            .obj("engine", repairFor(resolved))
            // Nested rather than flat, and named for the queue it came from. These three read as
            // the close gate's answer when they sit at the top of the response, and they are not:
            // they are this module's own queue reporting on its own row. The gate's answer is
            // POST /api/close.
            .obj("thisQueue", Json.object()
                .bool("blocksClose", resolved.blocksClose())
                .bool("quarantinesContract", resolved.quarantinesContract())
                .bool("defectFixed", resolved.status().defectFixed())
                .str("gate", queue.queue().describeCloseGate()))
            .str("note", "resolution asserts the defect was FIXED, so the quarantine is lifted and"
                + " a recomputation is expected to produce a figure. It is not acceptance:"
                + " accepting an exception leaves the input malformed and signs for closing"
                + " without that contract's figure. Read engine.repaired — that, and not this"
                + " row's status, is what changes what the next POST /api/run raises. Then roll"
                + " the period forward and close: thisQueue is this module's queue and"
                + " PeriodCloseGate does not read it")
            .strings("caveats", queueCaveats());
    }

    /**
     * The engine's input correction for a resolved row, or the reason there is none.
     *
     * <p>{@code MISSING_MANDATORY_FIELD} is the one category the engine can remediate: C-0003
     * arrives with period movements and no recorded opening position, and
     * {@code EirService.repair} puts the balance on file so the contract computes with the rest.
     * The other nine have no engine-side fix — an unmapped fee code is corrected in a rule set, a
     * failed solve in the schedule — so nothing is called and the response says which.
     *
     * <p>A {@link FormBody.BadRequest} from the repair is caught and rendered as a value rather
     * than allowed out. The caller's request was well formed; it is the <em>engine</em> that could
     * not attempt the correction, and turning that into a 400 on a valid resolution would tell an
     * operator they had typed something wrong.
     */
    private Json.Obj repairFor(ExceptionRecord resolved) {
        if (resolved.category() != ExceptionCategory.MISSING_MANDATORY_FIELD) {
            return Json.object()
                .bool("attempted", false)
                .str("detail", "the engine has no input-correction path for "
                    + resolved.category() + "; its only one is POST /api/repair, which supplies a"
                    + " missing opening balance. Correct the input at source and roll the period"
                    + " forward — this resolution is a record of the decision, not the fix");
        }
        try {
            return service.repair(FormBody.parse(
                "contractId=" + URLEncoder.encode(resolved.contractId(), StandardCharsets.UTF_8)))
                .bool("attempted", true);
        } catch (FormBody.BadRequest engineCannot) {
            return Json.object()
                .bool("attempted", true)
                .bool("repaired", false)
                .str("detail", engineCannot.getMessage());
        }
    }

    // ================================================== POST /api/exceptions/accept

    /**
     * 06 § 6's acceptance with approval, which permits a close despite the exception.
     *
     * <p>Both signatures are mandatory and neither is defaulted. The comparison is
     * {@link FourEyes#isSelfApproval} and its answer is <em>reported</em>, not enforced: see the
     * class javadoc on why manufacturing a refusal here would leave
     * {@code CloseGateRefusal.SELF_APPROVED_ACCEPTANCE} unreachable. Put the same identity on both
     * sides — {@code ops.analyst} and {@code Ops.Analyst } are the same identity — and this
     * endpoint records it, reports {@code "selfApproved":true}, and the close then refuses.
     *
     * <p><b>The delegation, and the guard on it.</b> The list the close gate weighs lives on
     * {@link EirService}, so the acceptance has to be recorded there too and this endpoint calls
     * the engine's own acceptance path rather than forking it. That path accepts <em>every</em> row
     * in the engine's queue at once, which is right for a console driving a one-contract queue and
     * wrong for a signature that names one exception: forty open rows and one signature would
     * become forty accepted rows nobody put forward. So delegation is refused while this module's
     * queue holds more than one row. The premise the guard rests on is stated rather than assumed:
     * it counts <em>this</em> queue, and it is a floor under the engine's queue only for as long as
     * the two hold the same rows — which they do on the book as seeded, and which is the reason the
     * first caveat exists.
     *
     * <p><b>An acceptance may be posted again, and that is not a loophole.</b> Every
     * {@code POST /api/run} rebuilds the engine's queue and clears its acceptance list on purpose,
     * so an acceptance recorded before a re-run is gone from the only place the gate reads. If this
     * endpoint refused a second signature the acceptance could never be re-recorded and 06 § 6's
     * only route past a queued exception would be closed for the life of the process. So a repeat
     * is allowed, the signatory it replaced is named on the response, and the gate still refuses a
     * self-approved one however many times it is posted.
     */
    private Json.Obj accept(FormBody body) {
        ExceptionWorkQueue queue = workQueue();
        ExceptionRecord row = queue.find(body.text("id"));
        String acceptedBy = body.text("acceptedBy");
        String approvedBy = body.text("approvedBy");
        String reason = body.text("reason");
        boolean selfApproved = FourEyes.isSelfApproval(acceptedBy, approvedBy);

        if (queue.size() > 1) {
            return Json.object()
                .bool("worked", false)
                .str("id", ExceptionWorkQueue.idOf(row))
                .count("queued", queue.size())
                .str("reason", reason)
                .bool("selfApproved", selfApproved)
                .str("message", "this queue holds " + queue.size() + " exceptions and the engine's"
                    + " acceptance path signs for every row in its queue at once, so recording one"
                    + " signature would accept " + (queue.size() - 1) + " exception(s) nobody put"
                    + " forward. Refused rather than narrowed: an acceptance covers one contract"
                    + " and one category (04 § 3), and the seam that would let this endpoint"
                    + " accept exactly one row is a per-exception acceptance on EirService")
                .strings("caveats", acceptCaveats());
        }

        String previously = row.isOpen() ? null : row.status() + " by " + row.resolvedBy();
        ExceptionRecord accepted = queue.accept(row, approvedBy, reason);
        // The engine's own answer, verbatim and nested rather than re-rendered, and first on the
        // response because it is the half that matters: this is the acceptance PeriodCloseGate
        // weighs, and its `ran` field says whether there was a run to accept anything from. This
        // layer does not parse its own JSON back to find out — that is what nesting it is for, and
        // an acceptance posted before a run can simply be posted again after one.
        Json.Obj engine = service.accept(body);

        return Json.object()
            .obj("engine", engine)
            .bool("worked", true)
            .str("id", ExceptionWorkQueue.idOf(accepted))
            .str("contractId", accepted.contractId())
            .str("category", accepted.category().name())
            .str("status", accepted.status().name())
            .str("acceptedBy", acceptedBy)
            .str("approvedBy", approvedBy)
            .str("reason", reason)
            .bool("selfApproved", selfApproved)
            .str("replacedWorking", previously)
            .obj("thisQueue", Json.object()
                .bool("blocksClose", accepted.blocksClose())
                .bool("quarantinesContract", accepted.quarantinesContract())
                .bool("defectFixed", accepted.status().defectFixed())
                .str("gate", queue.queue().describeCloseGate()))
            .str("note", selfApproved
                ? "the same identity is on both sides. This is recorded, not rejected: the control"
                    + " is PeriodCloseGate's SELF_APPROVED_ACCEPTANCE, and a refusal manufactured"
                    + " here would leave it unreachable. Close the period and read the verdict —"
                    + " an exception accepted by the person who put it forward is not accepted"
                    + " (04 § 3)"
                : "recorded. Acceptance changes no figure: the input is still malformed, the"
                    + " contract still has no figure, and the next run raises the same exception."
                    + " It signs for closing over that, which is why the close reports it"
                    + " separately from a resolution")
            .str("readEngineRan", "engine.ran is the field that says whether the acceptance"
                + " reached the list PeriodCloseGate weighs. False means no run has been rolled"
                + " forward and the engine recorded nothing — POST /api/run and post this again."
                + " The same applies after every re-run: a run clears the engine's acceptance"
                + " list, so an acceptance from before it no longer exists")
            .strings("caveats", acceptCaveats());
    }

    // ============================================================== the queue itself

    /**
     * The queue, built on first use and not in the constructor.
     *
     * <p>Lazily, because a module is constructed while the server is starting and a month-end
     * roll-forward is not something a server start should do — a book of ten million contracts
     * would make the port open minutes after the process began, and an operator would read that as
     * a hung deployment.
     */
    private ExceptionWorkQueue workQueue() {
        if (workQueue == null) {
            workQueue = ExceptionWorkQueue.over(source.get());
        }
        return workQueue;
    }

    /**
     * The queue the seeded book raises, from the same pipeline the console's run uses.
     *
     * <p>Not a hand-built list of expected exceptions: the categorisation is
     * {@code FailureIsolation}'s and the detail and the diagnostic are the ones the failure
     * actually produced, so a change to how a malformed contract is categorised shows up here
     * rather than being masked by a fixture that restates the old answer. C-0003 arrives with
     * period movements and no opening position, so this raises exactly one row —
     * {@code MISSING_MANDATORY_FIELD} on C-0003 — which is FR-905 working: the other two contracts
     * computed.
     *
     * <p>Deterministic: no clock is read, the boundary's instant is a literal, and the solver is
     * the same bracketed Newton the run uses. Two calls raise the same rows.
     */
    private static ExceptionQueue queueOfTheSeededBook() {
        Book book = Seed.book();
        RunRequest request = new RunRequest(DERIVED_RUN_ID, Seed.PERIOD_ID, BOOK_ID,
            AsAtBoundary.live(Seed.PERIOD_END, KNOWN_AT),
            book.contracts(), book.contractState(), book.coreBanking(),
            book.generalLedger(), book.policySource());
        ContractPipeline pipeline = new ContractPipeline(request, book.periods(),
            RoutingTableRegistry.of(RoutingTable.currentDefault()),
            SolveAudit.over(new BracketedNewtonSolver()));
        ExceptionQueue queue = new ExceptionQueue();
        new MonthEndRun(request, pipeline).execute(queue);
        return queue;
    }

    // ===================================================================== helpers

    /** What this module's queue is, and what it is not. Said on every response. */
    private static List<String> queueCaveats() {
        return List.of(
            "This queue is this module's own reading of 04 § 3's EXCEPTION table. EirService holds"
                + " the run's authoritative queue in a private field and exposes no read of it, so"
                + " these rows are derived once — on first request — by rolling the SEEDED book"
                + " forward through the same ContractPipeline and MonthEndRun the console's"
                + " POST /api/run uses, under run id " + DERIVED_RUN_ID + ". They agree with the"
                + " console's queue on the book as seeded and do not see a contract repaired"
                + " through POST /api/repair or onboarded through POST /api/onboard afterwards."
                + " The seam that removes this caveat is a read accessor for the last run's"
                + " exceptions on EirService.",
            "A status on this queue is this module's record of what was worked THROUGH THIS"
                + " MODULE, and PeriodCloseGate does not read it. So a row shown RESOLVED here"
                + " still refuses the close with EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED unless the"
                + " input was corrected on the book, and GET /api/exceptions?status=OPEN can"
                + " therefore report nothing outstanding over an engine queue that still holds a"
                + " close blocker. POST /api/close is the authoritative answer to whether the"
                + " exception gate is clear; nothing on this endpoint is.",
            "This module's worked state is not cleared by a run, and the engine's is: every"
                + " POST /api/run rebuilds the engine's queue and clears its acceptance list. So"
                + " an acceptance has to be posted again after a re-run, and this endpoint allows"
                + " a repeat for exactly that reason rather than refusing it as a second"
                + " signature.",
            "An id is contractId" + ExceptionWorkQueue.ID_SEPARATOR + "CATEGORY, which is the pair"
                + " ExceptionAcceptance matches on. 06 § 6 puts it in the path; Routes.post hands a"
                + " handler the form body and not the exchange, so it travels as a form field"
                + " until that interface carries the request path.");
    }

    /** The three things an operator accepting an exception has to be told. */
    private static List<String> acceptCaveats() {
        List<String> caveats = new ArrayList<>(queueCaveats());
        caveats.add("Acceptance is recorded on the engine's queue as well as this one, because"
            + " PeriodCloseGate weighs the engine's acceptance list. Read engine.ran on this"
            + " response: the engine has nothing to accept until POST /api/run has been rolled"
            + " forward, and an acceptance recorded here alone will not move the close.");
        caveats.add("An acceptance belongs to the run whose queue raised the exception. A new run"
            + " clears the engine's acceptance list on purpose — the gate refuses a carried-forward"
            + " one as ACCEPTANCE_WITHOUT_AN_EXCEPTION, because an acceptance list from a previous"
            + " run would otherwise read as authority to close over another run's problems.");
        caveats.add("No authentication. FR-906's role model is unimplemented, so the maker and the"
            + " checker on an acceptance are whatever the caller typed, and the four-eyes"
            + " comparison is over two strings rather than over two authenticated identities.");
        return caveats;
    }

    /**
     * One enum value from a query parameter, or a 400 that names every accepted value.
     *
     * <p>Case-folded at {@code Locale.ROOT} for the same reason {@link FourEyes} folds there: a
     * filter that accepted {@code status=open} on one host's locale and refused it on another is a
     * filter whose answer depends on the host. Unknown values are refused rather than ignored — a
     * filter that silently drops what it does not recognise answers
     * {@code ?status=OPENN} with the whole queue, and the caller reads forty accepted rows as forty
     * open ones.
     */
    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new FormBody.BadRequest("'" + field + "' must be one of "
                + Arrays.toString(type.getEnumConstants()) + ", got '" + raw + "'");
        }
    }

    /**
     * Whether the query string names this parameter at all, blank value included.
     *
     * <p>{@link FormBody#has} cannot answer this: it reports false for a present-but-blank value,
     * which is right for a body field that is optional and wrong for a filter, because
     * {@code ?status=} would then silently mean "every status" and answer a whole queue to a caller
     * who asked for a slice of it. Decided on the raw query, before decoding, so that a blank
     * arrives at {@link FormBody#text} and is refused there with the field named.
     */
    private static boolean named(String rawQuery, String key) {
        if (rawQuery == null) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String name = split < 0 ? pair : pair.substring(0, split);
            if (key.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> named = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            named.add(value.name());
        }
        return named;
    }
}
