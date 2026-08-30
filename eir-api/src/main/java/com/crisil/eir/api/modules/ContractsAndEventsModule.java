package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.contracts.EventRouting;
import com.crisil.eir.api.modules.contracts.EventSubmission;
import com.crisil.eir.api.modules.contracts.RecordedEvent;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.calc.projection.ContractTerms;
import com.sun.net.httpserver.HttpExchange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contract onboarding, contract reads, and the event submission where the driver tag is required
 * and treatment is never inferred from the observation that the rate moved (06 §§ 2, 3).
 *
 * <p><b>The one endpoint worth reading first.</b> {@code POST /api/contracts/{id}/events} refuses an
 * untagged event with a 400 (FR-504). That refusal is the module's reason to exist. The engine
 * routes on the event's {@code driver} plus the instrument's {@code rateType} and never on the
 * observation that the rate changed (FR-507), because an EBLR reset and a renegotiated fixed rate
 * are indistinguishable from the observation and differ by the entire modification question:
 * routed on the observation, the renegotiation gets a B5.4.5 reset with no catch-up, no
 * substantiality test and no derecognition assessment. Nothing downstream can detect that
 * afterwards — both readings publish a rate and a balance that reconcile — so the discrimination
 * has to be structural, at the boundary, and it is. See {@link EventSubmission}.
 *
 * <p><b>The surface, at 06 § 2 and § 3's own URLs.</b>
 *
 * <ul>
 *   <li>{@code POST /api/contracts} — onboard, through {@code EirService.onboard}
 *   <li>{@code POST /api/contracts/{id}/events} — the event submission
 *   <li>{@code GET /api/contracts/{id}} — current state
 *   <li>{@code GET /api/contracts/{id}/versions} — version history
 * </ul>
 *
 * <p><b>All four are one {@code Routes.route} registration, and that is what makes the path
 * parameter readable at all.</b> An earlier version of this module had to deviate from two of these
 * URLs, because {@code Routes} offered only {@code get} and {@code post}: a POST handler received a
 * {@code FormBody} and no path, so {@code {id}} was unreadable and had to travel in the form; and
 * the JDK's server matches the longest path prefix with one handler per context, so
 * {@code GET /api/contracts/{id}} and {@code POST /api/contracts/{id}/events} could not both be
 * served for any arrangement of context paths — the second path extends the first and no literal
 * context can sit between them. The reads lived at a singular {@code /api/contract/{id}} sibling as
 * a result. {@code Routes.route} hands over the exchange and lets several modules share one prefix,
 * so both deviations are gone and the documented URLs are restored.
 *
 * <p><b>What this module declines.</b> {@code GET /api/contracts/{id}/trace} is 06 § 2's audit
 * endpoint (FR-808) and belongs to {@code TraceModule}, which registers on this same prefix. This
 * handler returns {@code null} for it — and for any other suffix or verb it does not recognise — so
 * the next module registered on the prefix is tried and an unclaimed request becomes a 404 naming
 * the path. Declining is only ever for a path this module does not recognise; a request it
 * recognises and refuses comes back as an {@link Routes.Answer} carrying the whole reason, because
 * a refusal is a value in this engine.
 *
 * <p><b>Status codes.</b> 200 for every answer the engine gives, including every refusal, because
 * the reasons are the answer. 400 for a malformed request — and a missing driver tag <em>is</em> a
 * malformed event, so it is a 400 and not a refusal value. 404 for a contract that is not there,
 * which is the one thing a REST client has no other way to ask: a 200 carrying
 * {@code onFile: false} makes "no such contract" indistinguishable from "a contract holding
 * nothing" for any caller that reads the status line, and integration clients read the status line.
 * The 404 body still names the contract and the reason.
 *
 * <p><b>Not thread-safe, deliberately and visibly</b>, exactly as {@code EirService} is not:
 * {@code EirServer} runs a single-threaded executor and says why. The event log below is a plain
 * {@code LinkedHashMap} for that reason.
 */
public final class ContractsAndEventsModule implements ApiModule {

    /** The prefix all four endpoints hang off, shared with {@code TraceModule}. */
    static final String CONTRACTS = "/api/contracts";

    private static final String VERSIONS = "versions";
    private static final String EVENTS = "events";

    /** {@code TraceModule}'s suffix on this prefix (06 § 2, FR-808). Declined, never claimed. */
    private static final String TRACE = "trace";

    /**
     * The date the opening position every holding carries is struck at.
     *
     * <p>The book is positioned at the opening of one period and holds one row per contract, so this
     * is the {@code validFrom} of version 1 for every contract — both the seeded ones, whose row is
     * month 13's opening position, and one recognised in this session, whose inception position
     * {@code EirService.onboard} also strikes here. Taken from {@code Seed} rather than restated,
     * for the reason {@code Seed.policies()} gives about the routing version: two places stating one
     * date is two places that can disagree while every figure stays identical.
     */
    private static final LocalDate POSITION_STRUCK_AT = Seed.PERIOD_START;

    private final EirService service;

    /**
     * The events this module has routed, per contract, in submission order.
     *
     * <p>The engine's own event log, and the honest content of a version history over an in-memory
     * book — see {@link RecordedEvent}. It is <em>not</em> a bitemporal table and the versions
     * response says so instead of letting a reader infer a {@code recorded_at} series this book
     * does not carry.
     */
    private final Map<String, List<RecordedEvent>> events = new LinkedHashMap<>();

    public ContractsAndEventsModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** The engine this module reads through. */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.route(CONTRACTS, this::dispatch);
    }

    @Override
    public String specSection() {
        return "06 §§ 2, 3";
    }

    // ================================================================ dispatch

    /**
     * Reads the method and the remaining path segments, and answers or declines.
     *
     * <p>Dispatch is on the <b>path</b>, which is what the seam now provides. An earlier version had
     * to tell an onboarding from an event submission by which form fields were present, because one
     * POST context served the whole subtree and the handler could not see its own URL. That worked
     * and was uncomfortable — this engine's whole discipline is that nothing is inferred — and it is
     * gone.
     *
     * @return the answer, or null for a suffix or verb this module does not recognise
     */
    private Routes.Answer dispatch(HttpExchange exchange, FormBody body) {
        List<String> segments = segmentsBelow(exchange.getRequestURI().getPath());
        String method = exchange.getRequestMethod();

        if (segments.isEmpty()) {
            // The collection itself. 06 § 2 specifies POST here and no GET; the book's own listing
            // is GET /api/book, and a second way to ask for it would be surface with no purpose.
            return "POST".equals(method) ? Routes.Answer.ok(onboard(body)) : null;
        }

        String contractId = segments.get(0);
        if (segments.size() == 1) {
            return "GET".equals(method) ? read(contractId) : null;
        }
        if (segments.size() == 2) {
            String suffix = segments.get(1);
            if (EVENTS.equals(suffix) && "POST".equals(method)) {
                return submitEvent(contractId, body);
            }
            if (VERSIONS.equals(suffix) && "GET".equals(method)) {
                return versions(contractId);
            }
            // Everything else below a contract — TRACE above all, which is 06 § 2's audit endpoint
            // and TraceModule's to answer — is declined so the next module on this prefix is tried.
            return null;
        }
        return null;
    }

    /** The path segments below {@link #CONTRACTS}, blanks dropped. */
    private static List<String> segmentsBelow(String path) {
        String remainder = path.length() > CONTRACTS.length()
            ? path.substring(CONTRACTS.length()) : "";
        List<String> segments = new ArrayList<>();
        for (String segment : remainder.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    // ================================================================ POST /api/contracts

    /**
     * Onboarding, through the entry point the console already drives.
     *
     * <p>Forking it would give one bank two initial-recognition paths — 05 § 3.1's ordered pipeline
     * with its SPPI gate, fee lookup and tier gate, and a copy — and the copy would be the one that
     * drifts. The response is what the console shows, plus where to read the contract back.
     */
    private Json.Obj onboard(FormBody body) {
        return service.onboard(body).str("reads", CONTRACTS + "/" + body.text("contractId"));
    }

    // ================================================ POST /api/contracts/{id}/events

    /**
     * {@code POST /api/contracts/{id}/events} — 06 § 3.
     *
     * <p>The contract id comes from the path, which is where 06 § 3 puts it. Every refusal is a 400,
     * a contract that is not there is a 404, and every engine answer — including the refusal that no
     * approved routing table governs the event's date — is a 200. The one that matters is the first
     * check {@link EventSubmission#parse} makes.
     */
    private Routes.Answer submitEvent(String contractId, FormBody body) {
        EventSubmission submission = EventSubmission.parse(body, contractId);
        Optional<Book.Holding> found = service.holding(contractId);
        if (found.isEmpty()) {
            return notFound(contractId, "NOT_ON_BOOK",
                "no contract " + contractId + " on the book. Onboard it with POST " + CONTRACTS
                    + " first; an event against a contract the master does not carry has no"
                    + " instrument to route against, and this layer will not invent one.");
        }
        Book.Holding holding = found.get();
        if (!holding.stateOnFile()) {
            return noOpeningState(contractId);
        }
        Routes.Answer duplicate = alreadyRecorded(contractId, submission);
        if (duplicate != null) {
            return duplicate;
        }

        EventRouting.Routed routed =
            EventRouting.route(holding, service.routingTables(), submission);
        RecordedEvent recorded = routed.recorded();
        if (recorded != null) {
            events.computeIfAbsent(contractId, id -> new ArrayList<>()).add(recorded);
        }
        List<RecordedEvent> onRecord = eventsFor(contractId);
        Json.Obj response = routed.response()
            .bool("accepted", recorded != null)
            .count("eventsOnRecord", onRecord.size())
            .str("versions", CONTRACTS + "/" + contractId + "/" + VERSIONS)
            .str("carryingAmountBasis", "OPENING_POSITION_ON_FILE")
            .str("appliedToBook", "no — routing and evidence only. The balance moves in the"
                + " month-end roll-forward, which is the only place the roll-forward invariants are"
                + " asserted over the result.");
        if (onRecord.size() > 1) {
            // Said once, plainly, rather than left for a reader to infer from two responses that
            // both start at 528,407.32. Because nothing here moves the book, every event is
            // measured from the same opening position — so two events on one contract are two
            // independent readings of the same starting balance, not a chain. A version series
            // whose figures did not compose and did not say so would be read as one that did.
            response.str("compositionCaveat", "this is event " + onRecord.size() + " on "
                + contractId + " and its figures are measured from the OPENING position on file, not"
                + " from the balance the previous event restated — nothing here is applied to the"
                + " book. The events do not compose into a running balance; the month-end run is"
                + " what applies them in order.");
        }
        return Routes.Answer.ok(response);
    }

    /**
     * Refuses a second submission of an event already on record.
     *
     * <p>{@link RecordedEvent#idFor} is deterministic — the same contract, date and driver is the
     * same event — and its javadoc claims a retry is "recognisably the same event rather than a
     * second event with the same effect". It was not: the log appended unconditionally, so a retry
     * produced two versions carrying one {@code eventId}, the first of them zero-length because its
     * {@code validTo} equalled its own {@code validFrom}. A duplicate identifier in a version series
     * makes anything resolving a figure by event id get whichever row comes first.
     *
     * <p>A 409, not a 400: the request is well formed and names a real contract, and what is wrong
     * is the state of the resource it would create. docs/06 § 1's {@code Idempotency-Key} is the
     * proper mechanism and belongs in the seam; until it exists, refusing is better than appending.
     *
     * @return the refusal, or null where this event is new
     */
    private Routes.Answer alreadyRecorded(String contractId, EventSubmission submission) {
        String candidate = RecordedEvent.idFor(
            contractId, submission.eventDate(), submission.driver());
        for (RecordedEvent prior : events.getOrDefault(contractId, List.of())) {
            if (prior.eventId().equals(candidate)) {
                return Routes.Answer.of(409, Json.object()
                    .str("error", "event already on record")
                    .str("contractId", contractId)
                    .str("eventId", candidate)
                    .str("detail", "one contract, one date, one driver is one event. Accepting it"
                        + " again would open a second version under the same event id, and the first"
                        + " of the two would be a zero-length version whose validTo equals its own"
                        + " validFrom. Change the event date or the driver if this is a different"
                        + " event.")
                    .str("versions", CONTRACTS + "/" + contractId + "/" + VERSIONS));
            }
        }
        return null;
    }

    // ================================================================ GET /api/contracts/{id}

    /** {@code GET /api/contracts/{id}} — the contract as the book holds it now (06 § 2). */
    private Routes.Answer read(String contractId) {
        Optional<Book.Holding> found = service.holding(contractId);
        if (found.isEmpty()) {
            return notFound(contractId, "NOT_ON_BOOK",
                "no contract " + contractId + " on the book. Onboard it with POST " + CONTRACTS
                    + " first.");
        }
        Book.Holding holding = found.get();
        if (!holding.stateOnFile()) {
            return noOpeningState(contractId);
        }
        ContractStateSource.OpeningState state = holding.state();
        ContractTerms terms = state.terms();
        return Routes.Answer.ok(Json.object()
            .str("contractId", holding.contractId())
            .bool("onFile", true)
            .bool("openingStateOnFile", true)
            .str("product", holding.productId())
            .str("entity", holding.entityId())
            .str("description", holding.description())
            .str("stage", state.stage().name())
            .str("rateType", terms.rateType().name())
            .str("instrumentCurrency", terms.currency().getCurrencyCode())
            // Every figure a string, never a JSON number: 0.010421491800 through a double loses the
            // trailing zeros that say the rate is stated to twelve places (see Json's javadoc).
            // Rupee figures go out at presentation scale, as every figure EventRouting publishes
            // does: a nil allowance reached the wire as "0" beside a "528407.32" balance in the same
            // object, and Json's own argument is that the stated scale is information — a control
            // report reading one field at two paise and its neighbour at none is reading two
            // different statements about the same book.
            .figure("eir", state.eir().periodic())
            .figure("eirEffectiveAnnual", state.eir().effectiveAnnual())
            .figure("carryingAmount", state.openingGca().atPresentationScale().amount())
            .figure("contractualCarryingAmount",
                state.openingContractual().atPresentationScale().amount())
            .figure("allowance", state.allowance().atPresentationScale().amount())
            .figure("contractualInterestBilled",
                state.contractualInterestBilled().atPresentationScale().amount())
            .str("eclModel", state.eclEngineVersion())
            .figure("principal", terms.principal().atPresentationScale().amount())
            .figure("contractualRate", terms.contractualRate().periodic())
            .count("termPeriods", terms.termPeriods())
            .count("periodsPerYear", terms.periodsPerYear())
            .str("disbursementDate", terms.disbursementDate().toString())
            .str("firstDueDate", terms.firstDueDate().toString())
            .str("contractualMaturityDate", terms.maturityDate().toString())
            .str("dayCountConvention", terms.dayCount().name())
            .str("scheduleShape", terms.shape().name())
            .count("eventsOnRecord", eventsFor(contractId).size())
            // 06 § 1: every response carries the versions that produced its figures. The routing
            // series is the one this module's own answers depend on; the fee and tier policy
            // versions belong to the computation that recognised the contract and travel on the
            // onboarding response.
            .strings("routingTableVersionIds", service.routingTables().versionIds())
            .str("versions", CONTRACTS + "/" + contractId + "/" + VERSIONS)
            .str("trace", CONTRACTS + "/" + contractId + "/" + TRACE));
    }

    /**
     * {@code GET /api/contracts/{id}/versions} — the version history, and an explicit statement of
     * what this book can and cannot evidence.
     *
     * <p>06 § 2 asks for "full bitemporal version history with validFrom/validTo and
     * recordedAt/supersededAt". Two of those four are real here and two are not, and saying which
     * is the point. {@code validFrom} and {@code validTo} are genuine: the position the book holds
     * runs from the period it was struck at, and each routed event opens a new version on its own
     * event date. {@code recordedAt} and {@code supersededAt} are <b>null</b>, because {@code Book}
     * holds one row per contract and has no {@code recorded_at} series to report — its own javadoc
     * says so: "a map holds one version of each row … that is enough to make a replay reproduce and
     * not enough to make it a fair test of bitemporality". A response that filled those two fields
     * with plausible instants would be the worst available answer, because an auditor cannot tell an
     * invented {@code recorded_at} from a recorded one.
     *
     * <p><b>Row 1 is not labelled {@code INITIAL_RECOGNITION}, and that correction matters.</b> It
     * was, and it dated itself from the disbursement date while taking its figures from
     * {@code holding.state()} — which is the opening position of the <em>current</em> period. On
     * reference case 1 that told a reader the balance on 2027-04-30 was 528,407.32, the month-13
     * figure, when the position at recognition was GCA0 of 990,000.00. The book carries one row and
     * that row is the period's opening position, so the row says exactly that and carries the
     * recognition date as {@code initialRecognitionDate} — a date, not a claim about these figures.
     *
     * <p><b>Rows are chained in event-date order, not submission order.</b> Chaining on the append
     * order let a back-dated event produce {@code validTo} two months before its own
     * {@code validFrom}, and left the row before it overlapping the row after. A validity interval
     * that runs backwards is not a caveat, it is a wrong answer, and the sort is what makes the
     * series monotonic whatever order the events arrived in.
     */
    private Routes.Answer versions(String contractId) {
        Optional<Book.Holding> found = service.holding(contractId);
        if (found.isEmpty()) {
            return notFound(contractId, "NOT_ON_BOOK",
                "no contract " + contractId + " on the book, so it has no version history.");
        }
        Book.Holding holding = found.get();
        if (!holding.stateOnFile()) {
            return noOpeningState(contractId);
        }

        List<RecordedEvent> submitted = eventsFor(contractId);
        // Stable sort on the event date, so events submitted out of order still produce a monotonic
        // series and two events on one date keep the order they arrived in.
        List<RecordedEvent> recorded = new ArrayList<>(submitted);
        recorded.sort(Comparator.comparing(RecordedEvent::eventDate));
        boolean submittedOutOfOrder = !recorded.equals(submitted);

        List<Json.Obj> rows = new ArrayList<>(recorded.size() + 1);
        LocalDate firstEvent = recorded.isEmpty() ? null : recorded.get(0).eventDate();
        rows.add(Json.object()
            .count("versionNo", 1)
            // The date these figures are the position AT, which is the period the book is opened
            // at — not the disbursement date, which is a different date and a different balance.
            .str("validFrom", POSITION_STRUCK_AT.toString())
            .str("validTo", firstEvent == null ? null : firstEvent.toString())
            .str("basis", "OPENING_POSITION_ON_FILE")
            .str("initialRecognitionDate", holding.state().terms().disbursementDate().toString())
            .str("eventId", null)
            .str("driver", null)
            .str("routedMechanism", null)
            .str("routingTableVersionId", null)
            .figure("eir", holding.state().eir().periodic())
            .figure("carryingAmount", holding.state().openingGca().atPresentationScale().amount())
            .bool("pendingApproval", false));

        for (int index = 0; index < recorded.size(); index++) {
            LocalDate validTo = index + 1 < recorded.size()
                ? recorded.get(index + 1).eventDate() : null;
            rows.add(recorded.get(index).asVersionRow(index + 2, validTo));
        }

        Json.Obj response = Json.object()
            .str("contractId", contractId)
            .count("versionCount", rows.size())
            .array("versions", rows)
            .bool("bitemporalityDemonstrated", false)
            .str("recordedAtSeries", "NOT_CARRIED")
            .bool("chainedInEventDateOrder", true)
            .bool("figuresCompose", recorded.size() < 2)
            .str("caveat", "validFrom and validTo are real: version 1 is the opening position the"
                + " book carries, struck at " + POSITION_STRUCK_AT + ", and each routed event opens a"
                + " version on its own event date — chained in event-date order, not submission"
                + " order. Version 1 is NOT the position at initial recognition: this book holds one"
                + " row per contract and that row is the current period's opening position, so"
                + " initialRecognitionDate is reported as a date and not as a claim about these"
                + " figures. recordedAt and supersededAt are null for the same reason (see Book's"
                + " javadoc); a JDBC ContractStateSource answers as at a recorded point and can"
                + " populate them, and filling them here with plausible instants would be"
                + " indistinguishable from having recorded them.")
            .strings("routingTableVersionIds", service.routingTables().versionIds());
        if (recorded.size() > 1) {
            response.str("compositionCaveat", "each event's figures are measured from version 1's"
                + " balance, because nothing here is applied to the book. The rows are a series of"
                + " independent readings of the same opening position, not a running balance, and"
                + " the month-end run is what applies events in order.");
        }
        if (submittedOutOfOrder) {
            response.str("orderCaveat", "at least one event was submitted out of event-date order;"
                + " the rows below are sorted by event date so the validity intervals do not run"
                + " backwards, which means row order is not submission order.");
        }
        return Routes.Answer.ok(response);
    }

    // ================================================================ shared

    /**
     * A 404 naming the contract and the reason it cannot be answered.
     *
     * <p>Genuinely a 404 now that {@link Routes.Answer} can carry one. It used to be a 400, which
     * was the closest honest code the old seam could express and was wrong about what had happened:
     * the request was well formed and the resource was absent. The body still carries the whole
     * explanation, because a status with no reasons is not an answer — and {@code reason} is a
     * machine-readable field precisely so that the two ways a contract can be unanswerable stay
     * distinguishable to a client.
     */
    private static Routes.Answer notFound(String contractId, String reason, String detail) {
        return Routes.Answer.of(404, Json.object()
            .str("error", "no such contract")
            .str("contractId", contractId)
            .str("reason", reason)
            .bool("onFile", false)
            .str("detail", detail));
    }

    /**
     * FR-905's data condition: period movements on file and no opening balance recorded.
     *
     * <p>Refused rather than read, and this is the highest-value check in the module after the
     * driver tag. {@code Book.Holding.movementsOnly} carries a <b>placeholder</b> state so that the
     * period movements have somewhere to live, and its own javadoc says that state "is never read" —
     * the {@code contractState()} port filters on {@code stateOnFile} and answers
     * {@code Optional.empty()}, which is what a real master does for a row it does not carry.
     * {@code EirService.holding()} returns the raw holding, so this module has to apply the same
     * filter or it reads the placeholder.
     *
     * <p>Reading it is not a cosmetic defect. In the seeded book C-0003's placeholder is a <b>copy
     * of C-0001's performing state</b>, so the read served C-0001's EIR, principal and 528,407.32
     * balance under C-0003's id, and an event against C-0003 published a restated balance and a
     * satisfied CU-1 for a contract the master carries no balance for. That is the precise failure
     * this contract exists to expose, wearing a green control result.
     */
    private static Routes.Answer noOpeningState(String contractId) {
        return notFound(contractId, "NO_OPENING_STATE_ON_FILE",
            "contract " + contractId + " has period movements on file and no opening balance"
                + " recorded, so there is no position to read and nothing to route an event"
                + " against. The contract master does not carry the row (FR-905); the run isolates"
                + " it into the exception queue and the close gates on the count. Answering from"
                + " the placeholder state the book parks alongside the movements would publish"
                + " another contract's figures under this id.");
    }

    private List<RecordedEvent> eventsFor(String contractId) {
        return List.copyOf(events.getOrDefault(contractId, List.of()));
    }
}
