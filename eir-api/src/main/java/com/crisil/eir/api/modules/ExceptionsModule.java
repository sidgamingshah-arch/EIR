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
import java.net.URLDecoder;
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

    /**
     * The subtree 06 § 6's two mutations live under: {@code /api/exceptions/{id}/{action}}.
     *
     * <p>Registered with {@link Routes#route}, which hands the handler the exchange — so the id is
     * read from the path where 06 § 6 puts it, and the action is read from the path too rather than
     * guessed from which fields the body happens to carry.
     */
    public static final String SUBTREE_PATH = "/api/exceptions/";

    /** 06 § 6's resolution, as the last segment of {@link #SUBTREE_PATH}. */
    public static final String RESOLVE_ACTION = "resolve";

    /** 06 § 6's acceptance, as the last segment of {@link #SUBTREE_PATH}. */
    public static final String ACCEPT_ACTION = "accept";

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
     * Registers 06 § 6's three endpoints, at the paths 06 § 6 writes them at.
     *
     * <p>The listing keeps {@link Routes#get} — one verb, one fixed path, always 200, which is
     * exactly its shape. The two mutations take {@link Routes#route} over
     * {@link #SUBTREE_PATH}, because a path parameter cannot be read any other way: {@code route}
     * hands the handler the exchange, so {@code {id}} comes off the path and the action comes off
     * the path as well.
     *
     * <p><b>Why reading the action off the path matters more than the id does.</b> Before this seam
     * existed the id travelled as a body field, which was merely inelegant. The action could not
     * travel at all: {@link Routes#post} registers one handler per prefix and the JDK server
     * matches by longest prefix, so {@code /{id}/resolve} and {@code /{id}/accept} arrived at one
     * handler with nothing to distinguish them, and the only way to dispatch would have been to
     * infer the action from which fields the body carried. That is the defect FR-507 names in
     * another guise — treatment inferred from an observation rather than declared — and the guess
     * that goes wrong here turns a resolution into an acceptance, which is the difference between a
     * defect that was fixed and a period closed over one that stands.
     *
     * <p><b>The shape is checked exactly, and anything else is declined.</b> The suffix must be
     * two segments, {@code {id}} then a known action. So {@code POST /api/exceptions/resolveXYZ},
     * {@code POST /api/exceptions/{id}/resolve/anything} and {@code POST /api/exceptions/{id}/approve}
     * are all declined rather than swallowed — a prefix context used to hand every one of them to
     * the resolution handler, which could not see the path it was reached by and so performed the
     * mutation for a mistyped action. Declining returns {@code null}, which lets a sibling module
     * on the same prefix try and, if none claims it, produces the server's 404 naming the path.
     * Declining is only ever for a path or verb this module does not recognise: a request it
     * recognises and refuses comes back as an {@link Routes.Answer} on a 200 with the whole list,
     * because a refusal is a value in this engine.
     *
     * <p>Segments are split on the <em>raw</em> path and each is decoded afterwards. Decoding first
     * would let an id carrying {@code %2F} split into two segments and address something the caller
     * did not name.
     */
    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get(QUEUE_PATH, this::listQueue);
        routes.route(SUBTREE_PATH, this::mutate);
    }

    /**
     * Dispatches {@code /api/exceptions/{id}/{action}} to the resolution or the acceptance.
     *
     * <p>Returns {@code null} for anything else — a suffix that is not two segments, an action this
     * module does not serve, or a method other than POST. A GET under this subtree is somebody
     * else's or nobody's, and 06 § 6 gives it no meaning here.
     */
    private Routes.Answer mutate(HttpExchange exchange, FormBody body) {
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!rawPath.startsWith(SUBTREE_PATH)) {
            return null;
        }
        String[] segments = rawPath.substring(SUBTREE_PATH.length()).split("/");
        if (segments.length != 2) {
            return null;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return null;
        }
        String id = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
        String action = URLDecoder.decode(segments[1], StandardCharsets.UTF_8);
        // Exact match on the action, and no case folding: an action is a path segment 06 § 6
        // spells out, not a value a caller chooses, and accepting /RESOLVE would be inventing
        // surface nothing specifies.
        return switch (action) {
            case RESOLVE_ACTION -> Routes.Answer.ok(resolve(id, body));
            case ACCEPT_ACTION -> Routes.Answer.ok(accept(id, body));
            default -> null;
        };
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
     * as ignoring an unrecognised value. Sent blank, or named with no value at all, the parameter
     * refuses.
     *
     * <p><b>Every aggregate on this response is named for the queue it was computed over</b> —
     * {@code queuedHere}, {@code blocksCloseOnThisQueue}, {@code thisQueueGate} — and
     * {@code authoritativeCloseGate} names what to ask instead. Unscoped names were worse than
     * imprecise: {@code "blocksClose":true} on a client's dashboard reads as the close gate's
     * answer, and this module's derived snapshot is not it. After {@code POST /api/repair} and a
     * re-run the engine has no exception blocker left and this snapshot still holds the row, so an
     * unscoped field would be wrong in the direction that matters — believed when it says a close
     * is blocked, and believed again when it says one is not.
     */
    private Json.Obj listQueue(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        FormBody query = FormBody.parse(rawQuery);
        String rawStatus = filterValue(rawQuery, query, "status");
        String rawCategory = filterValue(rawQuery, query, "category");
        ExceptionStatus status =
            rawStatus == null ? null : parse(ExceptionStatus.class, rawStatus, "status");
        ExceptionCategory category =
            rawCategory == null ? null : parse(ExceptionCategory.class, rawCategory, "category");

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
            .count("queuedHere", queue.size())
            .count("returned", rows.size())
            .count("closeBlockersOnThisQueue", queue.queue().closeBlockers().size())
            .bool("blocksCloseOnThisQueue", queue.queue().blocksClose())
            .str("thisQueueGate", queue.queue().describeCloseGate())
            .str("authoritativeCloseGate", "POST /api/close. PeriodCloseGate reads the engine's"
                + " own queue and not this one, so no field on this response is an answer to"
                + " whether the period's exception gate is clear")
            .strings("quarantinedContractsOnThisQueue",
                List.copyOf(queue.queue().quarantinedContracts()))
            .strings("demotedContractsOnThisQueue",
                List.copyOf(queue.queue().demotedContracts()))
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

    /**
     * The exception this request names, taken from the path segment 06 § 6 specifies.
     *
     * <p>The path is authoritative and the body's {@code id} is not silently ignored: an earlier
     * shape of these two handlers read the id out of the form, and the subtree router that replaced
     * it reads the id out of {@code /api/exceptions/{id}/resolve}. Both spellings therefore exist in
     * callers' hands. Preferring one and dropping the other quietly is the shape of defect this
     * repository keeps finding — a caller who posts {@code id=EX-7} to {@code .../EX-9/resolve}
     * would work the wrong row and be told the work succeeded. So a body id that disagrees with the
     * path is refused, and one that agrees is accepted.
     *
     * @throws IllegalArgumentException when the body names a different exception than the path.
     *     Fails the request rather than returning a refusal value because it is a malformed
     *     request, not an answer the engine declines to give (400, per the API's own convention).
     */
    private static String namedException(String pathId, FormBody body) {
        String bodyId = body.textOr("id", "");
        if (!bodyId.isBlank() && !bodyId.equals(pathId)) {
            throw new IllegalArgumentException(
                "the path names exception " + pathId + " and the body names " + bodyId
                    + "; refused rather than picking one, because working the wrong row and"
                    + " reporting success is worse than refusing an ambiguous request");
        }
        return pathId;
    }

    private Json.Obj resolve(String pathId, FormBody body) {
        ExceptionWorkQueue queue = workQueue();
        ExceptionRecord row = queue.find(namedException(pathId, body));
        String resolvedBy = body.text("resolvedBy");
        String note = body.text("note");
        String previously = row.isOpen() ? null : row.status() + " by " + row.resolvedBy();

        // The one transition that is refused, and the reason is not tidiness. RESOLVED carries
        // defectFixed = true, so it LIFTS the quarantine: a resolution written over an
        // ACCEPTED_WITH_APPROVAL row would discard the approver's name and reason — the row holds
        // one signatory column and that is all the audit file will ever show — and claim the
        // contract back into the reported population on one unverified signature. That is a way
        // past the four-eyes control by the back door: sign nothing, accept with a colleague, then
        // resolve over it alone. ExceptionQueue.resolve already refuses to re-work a worked row for
        // exactly this reason; find() hands back the live record, so the queue's own guard cannot
        // see it and this one has to. Acknowledged explicitly it is allowed, because a defect
        // genuinely fixed after being accepted is a real sequence.
        if (row.status() == ExceptionStatus.ACCEPTED_WITH_APPROVAL
            && !ExceptionStatus.ACCEPTED_WITH_APPROVAL.name()
                .equals(body.textOr("replacing", ""))) {
            return Json.object()
                .bool("worked", false)
                .str("id", ExceptionWorkQueue.idOf(row))
                .str("status", row.status().name())
                .str("approvedBy", row.resolvedBy())
                .str("approvalNote", row.resolutionNote())
                .str("message", "exception " + ExceptionWorkQueue.idOf(row) + " is"
                    + " ACCEPTED_WITH_APPROVAL by " + row.resolvedBy() + " and a resolution would"
                    + " replace that approval with a claim that the defect was fixed — discarding"
                    + " the only signatory the row can hold, and lifting the quarantine, which"
                    + " puts the contract back into the reported population. If the input really"
                    + " was corrected after the acceptance, say so: post again with"
                    + " replacing=ACCEPTED_WITH_APPROVAL")
                .strings("caveats", queueCaveats());
        }

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
     * <p><b>The engine's queue is the only place an acceptance is recorded, and this module keeps
     * no copy.</b> That is the important design decision here and it was arrived at the other way
     * round first. Marking the row accepted on this module's derived queue as well looked like
     * bookkeeping and was a lie waiting for a caller: {@code EirService.accept} records nothing
     * when no run has been rolled forward (it answers {@code "ran":false}), so a local copy would
     * show the row {@code ACCEPTED_WITH_APPROVAL} and this module's queue reporting "close not
     * blocked" with <em>nothing at the gate at all</em> — and a following
     * {@code GET /api/exceptions?status=OPEN} answering that there is nothing outstanding. An
     * understated queue is a nuisance; a queue that says the gate is clear when it is not is the
     * failure the queue exists to prevent. So the acceptance goes to the engine and the engine's
     * answer comes back verbatim, and the row here stays as the run raised it.
     *
     * <p>Two things follow, and both are improvements. An acceptance can be posted again after a
     * re-run — which it must be, because every {@code POST /api/run} clears the engine's acceptance
     * list on purpose and the gate refuses a carried-forward one as
     * {@code ACCEPTANCE_WITHOUT_AN_EXCEPTION} — and no resolution posted afterwards can overwrite
     * an approver's name here, because there is no accepted row here to overwrite.
     *
     * <p><b>The guard on the delegation, and the premise it cannot check.</b>
     * {@code EirService.accept} ignores the id and accepts <em>every</em> row in the engine's
     * queue, which is right for a console driving a one-contract queue and wrong for a signature
     * that names one exception: forty open rows and one signature would become forty accepted rows
     * nobody put forward. So delegation is refused while this module's queue holds more than one
     * row. Say plainly what that is worth: it counts <em>this</em> queue, and it bounds the
     * engine's only while the two hold the same rows. It cannot check that, because
     * {@code EirService} exposes no read of its queue — so {@code engine.accepted} on the response
     * is the number of rows the engine actually signed, and a value above one means this signature
     * covered exceptions it did not name. The seam that turns the guard into a real bound is a
     * per-exception acceptance on {@code EirService}.
     */
    private Json.Obj accept(String pathId, FormBody body) {
        ExceptionWorkQueue queue = workQueue();
        ExceptionRecord row = queue.find(namedException(pathId, body));
        String acceptedBy = body.text("acceptedBy");
        String approvedBy = body.text("approvedBy");
        String reason = body.text("reason");
        boolean selfApproved = FourEyes.isSelfApproval(acceptedBy, approvedBy);

        if (queue.size() > 1) {
            return Json.object()
                .bool("worked", false)
                .str("id", ExceptionWorkQueue.idOf(row))
                .count("queuedHere", queue.size())
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

        // The engine's own answer, verbatim and nested rather than re-rendered, and FIRST on the
        // response because it is the whole of what happened: this is the acceptance
        // PeriodCloseGate weighs, its `ran` field says whether there was a run to accept anything
        // from, and its `accepted` count says how many rows it signed. Nothing is recorded on this
        // module's queue — see the javadoc: a local copy would report a clear gate on a period
        // where the engine had recorded nothing at all.
        Json.Obj engine = service.accept(body);

        return Json.object()
            .obj("engine", engine)
            .bool("worked", true)
            .str("id", ExceptionWorkQueue.idOf(row))
            .str("contractId", row.contractId())
            .str("category", row.category().name())
            .str("recordedOn", "the engine's queue only. This module keeps no copy of an"
                + " acceptance, so the row it lists for this exception still reads as the run"
                + " raised it — GET /api/exceptions understates a worked row rather than"
                + " overstating a clear gate")
            .str("acceptedBy", acceptedBy)
            .str("approvedBy", approvedBy)
            .str("reason", reason)
            .bool("selfApproved", selfApproved)
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
     *
     * <p><b>What laziness costs, said plainly.</b> The derivation runs inside a request handler on
     * {@code EirServer}'s single executor thread, so on a book where the run is expensive the first
     * {@code GET /api/exceptions} blocks every other request for the length of a month-end run, and
     * duplicates work the run it is reporting on has just done. Both are consequences of deriving
     * the queue rather than reading it, and both go away with the same seam: a read accessor for
     * the last run's exceptions on {@code EirService} costs a map lookup and needs no run at all.
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
            "A resolution recorded here is not cleared by a run and the engine's queue is: every"
                + " POST /api/run rebuilds it and clears its acceptance list. So an acceptance has"
                + " to be posted again after a re-run, and this endpoint allows a repeat for"
                + " exactly that reason rather than refusing it as a second signature.",
            "An id is contractId" + ExceptionWorkQueue.ID_SEPARATOR + "CATEGORY, which is the pair"
                + " ExceptionAcceptance matches on, and 06 § 6 puts it in the path:"
                + " POST " + SUBTREE_PATH + "{id}/{resolve|accept}. A body field named id is"
                + " accepted only when it agrees with the path, and a disagreement is refused"
                + " rather than resolved in favour of either.");
    }

    /** The three things an operator accepting an exception has to be told. */
    private static List<String> acceptCaveats() {
        List<String> caveats = new ArrayList<>(queueCaveats());
        caveats.add("An acceptance is recorded on the ENGINE's queue and nowhere else, because"
            + " that is the list PeriodCloseGate weighs. Read engine.ran: the engine has nothing"
            + " to accept until POST /api/run has been rolled forward. Read engine.accepted too —"
            + " the engine's acceptance path signs for every row in its queue at once, so a count"
            + " above one means this signature covered exceptions it did not name.");
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
     * A filter's value, or null where the query does not mention it at all.
     *
     * <p><b>Presence and value are two questions and {@link FormBody} can only answer the
     * second.</b> {@link FormBody#has} reports false for a present-but-blank value, which is right
     * for an optional body field and wrong for a filter: read that way, {@code ?status=} would mean
     * "every status" and answer the whole queue to a caller who asked for a slice of it — the same
     * defect as ignoring an unrecognised value, and with the same consequence, since a caller
     * reading forty accepted rows as forty open ones will work a queue that is already worked.
     *
     * <p>So presence is decided on the query string itself, and both empty forms — {@code ?status=}
     * and a bare {@code ?status} — are refused with one message that says what happened. The
     * parameter <em>name</em> is percent-decoded before comparison: {@code ?%73tatus=OPEN} names
     * {@code status}, and comparing the raw form would have silently dropped the filter and
     * answered the whole queue with {@code "statusFilter":null} — precisely the failure this method
     * exists to prevent, arriving through the door it was watching.
     */
    private static String filterValue(String rawQuery, FormBody query, String key) {
        if (!named(rawQuery, key)) {
            return null;
        }
        if (!query.has(key)) {
            throw new FormBody.BadRequest("'" + key + "' is named in the query with no value; a"
                + " filter sent empty is not 'every value'. Send a value or leave the parameter"
                + " out — answering the whole queue to a caller who asked for a slice of it is the"
                + " same defect as ignoring an unrecognised value");
        }
        return query.text(key);
    }

    /** Whether the query string names this parameter at all, empty value included. */
    private static boolean named(String rawQuery, String key) {
        if (rawQuery == null) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String rawName = split < 0 ? pair : pair.substring(0, split);
            if (key.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
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
