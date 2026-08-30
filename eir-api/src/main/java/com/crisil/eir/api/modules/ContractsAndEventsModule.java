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
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.calc.projection.ContractTerms;
import com.sun.net.httpserver.HttpExchange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p><b>What the endpoints are, and why two of the paths are not the ones 06 § 2 writes.</b>
 *
 * <table border="1">
 *   <caption>this module's HTTP surface</caption>
 *   <tr><th>06 § 2/3</th><th>here</th><th>note</th></tr>
 *   <tr><td>{@code POST /contracts}</td><td>{@code POST /api/contracts}</td><td>as specified</td></tr>
 *   <tr><td>{@code POST /contracts/{id}/events}</td><td>{@code POST /api/contracts/{id}/events}</td>
 *       <td>as specified, except that the id is read from the form body — see below</td></tr>
 *   <tr><td>{@code GET /contracts/{id}}</td><td>{@code GET /api/contract/{id}}</td>
 *       <td>singular, because the plural prefix is claimed by the POST context</td></tr>
 *   <tr><td>{@code GET /contracts/{id}/versions}</td><td>{@code GET /api/contract/{id}/versions}</td>
 *       <td>same reason</td></tr>
 * </table>
 *
 * <p><b>Both deviations come from one gap in the route seam, and it is worth stating precisely
 * because several other modules in {@code ApiModules} hit the same wall.</b>
 *
 * <ol>
 *   <li>{@code Routes.post} hands a handler a parsed {@code FormBody} and <em>nothing else</em>, so
 *       a POST handler cannot read {@code {id}} out of its own request path. Every POST in 06 whose
 *       path carries an id — this one, {@code /runs/{id}/replay}, {@code /periods/{id}/close},
 *       {@code /exceptions/{id}/resolve}, {@code /policy-versions/{id}/approve} — needs the id in
 *       the body until the seam can pass a path. Guessing the contract from anything else would
 *       apply an event to the wrong instrument and restate the wrong balance, so this module
 *       requires {@code contractId} in the form and says so in the refusal.
 *   <li>{@code com.sun.net.httpserver} routes on the <em>longest matching path prefix</em> and
 *       {@code Routes} registers one verb per path. So the paths under {@code /api/contracts/}
 *       belong entirely to one verb: whichever context is the longest prefix answers, and the other
 *       verb gets a 405. {@code GET /api/contracts/{id}} and {@code POST /api/contracts/{id}/events}
 *       cannot both be served, for any arrangement of context paths, because the second path
 *       extends the first and no literal context can sit between them. The POST subtree wins here —
 *       the FR-504 refusal has to live at the documented URL — and the reads move to the singular
 *       sibling {@code /api/contract/…}, which is not a prefix of the plural one.
 * </ol>
 *
 * <p>Both are seam limitations rather than design choices, and both are one method on
 * {@code Routes} away from disappearing: a POST registration that hands the handler the exchange
 * (or the path suffix) alongside the body would let this module serve 06's paths exactly. The
 * deviations are documented rather than papered over, because an integrator reading 06 and getting
 * a 405 needs to know it is the seam and not their request.
 *
 * <p><b>Status codes.</b> {@code EirServer} maps outcomes onto 200 for every engine answer including
 * every refusal, 400 for a malformed request, 500 for a defect. Two consequences worth naming:
 * a missing driver tag <em>is</em> a malformed event, so it is a 400 and not a refusal value; and
 * there is no 404 available, so a reference to a contract the book does not hold comes back as a
 * 400 naming it — which is what {@code EirService.onboard} already does for the mirror-image
 * condition of a contract that is already on the book.
 *
 * <p><b>Not thread-safe, deliberately and visibly</b>, exactly as {@code EirService} is not:
 * {@code EirServer} runs a single-threaded executor and says why. The event log below is a plain
 * {@code LinkedHashMap} for that reason.
 */
public final class ContractsAndEventsModule implements ApiModule {

    /** The POST context: onboarding, and — by prefix — the event submission. */
    static final String CONTRACTS = "/api/contracts";

    /** The GET context. Singular, so it is not a prefix of {@link #CONTRACTS}. */
    static final String CONTRACT = "/api/contract";

    private static final String VERSIONS = "versions";

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
        routes.post(CONTRACTS, this::post);
        routes.get(CONTRACT, this::read);
    }

    @Override
    public String specSection() {
        return "06 §§ 2, 3";
    }

    // ================================================================ POST /api/contracts

    /**
     * The POST subtree: an onboarding, or an event submission.
     *
     * <p><b>Why one handler serves two resources.</b> The seam gives this module one POST context
     * for the whole {@code /api/contracts} subtree (see the class javadoc), and a handler with no
     * path cannot tell {@code /api/contracts} from {@code /api/contracts/{id}/events}. So the two
     * request shapes are told apart on their own fields, by {@link EventSubmission#looksLikeEvent}.
     *
     * <p><b>That is not the engine inferring treatment, and the discriminator is chosen so that it
     * cannot become one.</b> It keys off {@code driver} <em>or</em> {@code eventDate}, so an event
     * carrying a date and no driver still arrives at the event handler and is still refused there
     * on FR-504's own terms. Keying off {@code driver} alone would have sent precisely the untagged
     * event — the one case that matters — to the onboarding handler, where it would have come back
     * as a complaint about a missing principal.
     */
    private Json.Obj post(FormBody body) {
        if (EventSubmission.looksLikeEvent(body)) {
            return submitEvent(body);
        }
        // Onboarding goes through EirService.onboard, which the console already drives. Forking it
        // would give one bank two initial-recognition paths — 05 § 3.1's ordered pipeline with its
        // SPPI gate, fee lookup and tier gate, and a copy — and the copy would be the one that
        // drifts. The response is what the console shows, plus where to read the contract back.
        Json.Obj recognised = service.onboard(body);
        return recognised.str("reads", CONTRACT + "/" + body.text("contractId"));
    }

    /**
     * {@code POST /api/contracts/{id}/events} — 06 § 3.
     *
     * <p>Every refusal here is a 400 and every engine answer, including the refusal that no
     * approved routing table governs the event's date, is a 200. The one that matters is the first
     * check {@link EventSubmission#parse} makes.
     */
    private Json.Obj submitEvent(FormBody body) {
        EventSubmission submission = EventSubmission.parse(body);
        Book.Holding holding = require(submission.contractId());

        EventRouting.Routed routed =
            EventRouting.route(holding, service.routingTables(), submission);
        RecordedEvent recorded = routed.recorded();
        if (recorded != null) {
            events.computeIfAbsent(holding.contractId(), id -> new ArrayList<>()).add(recorded);
        }
        return routed.response()
            .bool("accepted", recorded != null)
            .count("eventsOnRecord", eventsFor(holding.contractId()).size())
            .str("versions", CONTRACT + "/" + holding.contractId() + "/" + VERSIONS)
            .str("appliedToBook", "no — routing and evidence only. The balance moves in the"
                + " month-end roll-forward, which is the only place the roll-forward invariants are"
                + " asserted over the result.");
    }

    // ================================================================ GET /api/contract/…

    /** {@code GET /api/contract/{id}} and {@code GET /api/contract/{id}/versions} — 06 § 2. */
    private Json.Obj read(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String remainder = path.length() > CONTRACT.length()
            ? path.substring(CONTRACT.length()) : "";
        List<String> segments = new ArrayList<>();
        for (String segment : remainder.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        String contractId = segments.isEmpty()
            ? queryParameter(exchange.getRequestURI().getQuery(), "id") : segments.get(0);
        if (contractId == null) {
            throw new FormBody.BadRequest(
                "a contract id is required: GET " + CONTRACT + "/{id} or " + CONTRACT
                    + "/{id}/versions. It is not defaulted to the first contract on the book — a"
                    + " read that answers about some other contract than the one asked about is"
                    + " worse than one that refuses.");
        }
        if (segments.size() > 1 && !VERSIONS.equals(segments.get(1))) {
            throw new FormBody.BadRequest(
                "no view '" + segments.get(1) + "' on a contract; this module serves " + CONTRACT
                    + "/{id} and " + CONTRACT + "/{id}/" + VERSIONS
                    + ". The trace view is GET /api/contracts/{id}/trace and belongs to"
                    + " TraceModule (06 § 2, FR-808).");
        }
        Book.Holding holding = require(contractId);
        return segments.size() > 1 ? versions(holding) : current(holding);
    }

    /** The contract as the book holds it now. */
    private Json.Obj current(Book.Holding holding) {
        ContractStateSource.OpeningState state = holding.state();
        ContractTerms terms = state.terms();
        List<RecordedEvent> recorded = eventsFor(holding.contractId());
        return Json.object()
            .str("contractId", holding.contractId())
            .bool("onFile", true)
            .bool("openingStateOnFile", holding.stateOnFile())
            .str("product", holding.productId())
            .str("entity", holding.entityId())
            .str("description", holding.description())
            .str("stage", state.stage().name())
            .str("rateType", terms.rateType().name())
            .str("instrumentCurrency", terms.currency().getCurrencyCode())
            // Every figure a string, never a JSON number: 0.010421491800 through a double loses the
            // trailing zeros that say the rate is stated to twelve places (see Json's javadoc).
            .figure("eir", state.eir().periodic())
            .figure("eirEffectiveAnnual", state.eir().effectiveAnnual())
            .figure("carryingAmount", state.openingGca().amount())
            .figure("contractualCarryingAmount", state.openingContractual().amount())
            .figure("allowance", state.allowance().amount())
            .figure("contractualInterestBilled", state.contractualInterestBilled().amount())
            .str("eclModel", state.eclEngineVersion())
            .figure("principal", terms.principal().amount())
            .figure("contractualRate", terms.contractualRate().periodic())
            .count("termPeriods", terms.termPeriods())
            .count("periodsPerYear", terms.periodsPerYear())
            .str("disbursementDate", terms.disbursementDate().toString())
            .str("firstDueDate", terms.firstDueDate().toString())
            .str("contractualMaturityDate", terms.maturityDate().toString())
            .str("dayCountConvention", terms.dayCount().name())
            .str("scheduleShape", terms.shape().name())
            .count("eventsOnRecord", recorded.size())
            // 06 § 1: every response carries the versions that produced its figures. The routing
            // series is the one this module's own answers depend on; the fee and tier policy
            // versions belong to the computation that recognised the contract and travel on the
            // onboarding response.
            .strings("routingTableVersionIds", service.routingTables().versionIds())
            .str("versions", CONTRACT + "/" + holding.contractId() + "/" + VERSIONS);
    }

    /**
     * {@code GET /api/contract/{id}/versions} — the version history, and an explicit statement of
     * what this book can and cannot evidence.
     *
     * <p>06 § 2 asks for "full bitemporal version history with validFrom/validTo and
     * recordedAt/supersededAt". Two of those four are real here and two are not, and saying which
     * is the point. {@code validFrom} and {@code validTo} are genuine: initial recognition runs from
     * the disbursement date, and each routed event opens a new version on its own event date.
     * {@code recordedAt} and {@code supersededAt} are <b>null</b>, because {@code Book} holds one
     * row per contract and has no {@code recorded_at} series to report — its own javadoc says so:
     * "a map holds one version of each row … that is enough to make a replay reproduce and not
     * enough to make it a fair test of bitemporality". A response that filled those two fields with
     * plausible instants would be the worst available answer, because an auditor cannot tell an
     * invented {@code recorded_at} from a recorded one.
     */
    private Json.Obj versions(Book.Holding holding) {
        List<RecordedEvent> recorded = eventsFor(holding.contractId());
        List<Json.Obj> rows = new ArrayList<>(recorded.size() + 1);

        LocalDate firstEvent = recorded.isEmpty() ? null : recorded.get(0).eventDate();
        rows.add(Json.object()
            .count("versionNo", 1)
            .str("validFrom", holding.state().terms().disbursementDate().toString())
            .str("validTo", firstEvent == null ? null : firstEvent.toString())
            .str("basis", "INITIAL_RECOGNITION")
            .str("eventId", null)
            .str("driver", null)
            .str("routedMechanism", null)
            .str("routingTableVersionId", null)
            .figure("eir", holding.state().eir().periodic())
            .figure("carryingAmount", holding.state().openingGca().amount())
            .bool("pendingApproval", false));

        for (int index = 0; index < recorded.size(); index++) {
            LocalDate validTo = index + 1 < recorded.size()
                ? recorded.get(index + 1).eventDate() : null;
            rows.add(recorded.get(index).asVersionRow(index + 2, validTo));
        }

        return Json.object()
            .str("contractId", holding.contractId())
            .count("versionCount", rows.size())
            .array("versions", rows)
            .bool("bitemporalityDemonstrated", false)
            .str("recordedAtSeries", "NOT_CARRIED")
            .str("caveat", "validFrom and validTo are real: version 1 runs from the disbursement"
                + " date and each routed event opens a version on its own event date. recordedAt and"
                + " supersededAt are null because this book holds one row per contract and carries"
                + " no recorded_at series (see Book's javadoc). A JDBC ContractStateSource answers"
                + " as at a recorded point and can populate them; filling them here with plausible"
                + " instants would be indistinguishable from having recorded them.")
            .strings("routingTableVersionIds", service.routingTables().versionIds());
    }

    // ================================================================ shared

    /**
     * The holding, or a 400 naming the contract.
     *
     * <p>A 404 is what this deserves and the route seam cannot emit one — {@code EirServer} maps a
     * handler's outcome onto 200, 400 or 500. So the closest honest code is used, and it follows
     * {@code EirService.onboard}'s own precedent: a request whose contract id contradicts the
     * book's state is treated there as a malformed request rather than as an engine refusal. The
     * alternative — a 200 carrying {@code onFile: false} — would make "no such contract"
     * indistinguishable from "a contract with nothing in it" for any caller that reads the status
     * line, and integration clients read the status line.
     */
    private Book.Holding require(String contractId) {
        return service.holding(contractId).orElseThrow(() -> new FormBody.BadRequest(
            "no contract " + contractId + " on the book. Onboard it with POST " + CONTRACTS
                + " first; an event or a read against a contract the master does not carry has no"
                + " instrument to route against, and this layer will not invent one."));
    }

    private List<RecordedEvent> eventsFor(String contractId) {
        return List.copyOf(events.getOrDefault(contractId, List.of()));
    }

    /** One query parameter, or null. Enough for {@code ?id=}; no general query parser needed. */
    private static String queryParameter(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && key.equals(pair.substring(0, split))) {
                String value = pair.substring(split + 1).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
