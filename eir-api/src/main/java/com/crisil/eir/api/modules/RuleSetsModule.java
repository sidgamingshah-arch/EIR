package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.rulesets.FeeRuleSetRegister;
import com.crisil.eir.api.modules.rulesets.RoutingTableRegister;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import com.crisil.eir.policy.routing.RoutingTableFormat;
import com.crisil.eir.policy.routing.RoutingTableUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * fee rule sets and routing tables: draft and approve, so the routing table changes without a code
 * deploy (ADR-0006, 06 § 5).
 *
 * <p><b>What this module makes observable.</b> ADR-0006's Phase 2 exit gate is one sentence — "the
 * routing table can be changed without a code deploy" — and until now it was structurally true and
 * impossible to exercise from outside the JVM. {@link RoutingTableFormat} was the artefact,
 * {@code RoutingTableRegistry} was the approved series, and the only way into either was Java code,
 * which is exactly the deploy the sentence promises to avoid. These endpoints are the missing path:
 * post a table in the text format, have a second identity approve it, then ask for a routing on a
 * date the new version governs and watch the mechanism change. Nothing restarts.
 *
 * <p>The routing answer comes from {@code RoutingTableRegistry.route(driver, rateType, eventDate)} —
 * character for character the call {@code ContractPipeline} makes per event. So the routing shown is
 * the routing a run would take, not a parallel imitation of it, which is the only version of this
 * demonstration worth anything.
 *
 * <h2>The paths, and why two of them are not what 06 § 5 writes</h2>
 *
 * <table border="1">
 *   <caption>Registered routes</caption>
 *   <tr><th>06 § 5</th><th>Here</th></tr>
 *   <tr><td>{@code GET /routing-tables}</td><td>{@code GET /api/routing-tables}</td></tr>
 *   <tr><td>{@code POST /routing-tables}</td><td>{@code POST /api/routing-tables/proposals}</td></tr>
 *   <tr><td>{@code POST /routing-tables/{id}/approve}</td>
 *       <td>{@code POST /api/routing-tables/approvals} with {@code id=}</td></tr>
 *   <tr><td>{@code GET /fee-rule-sets}</td><td>{@code GET /api/fee-rule-sets}</td></tr>
 *   <tr><td>{@code POST /fee-rule-sets}</td><td>{@code POST /api/fee-rule-sets/proposals}</td></tr>
 *   <tr><td>{@code POST /fee-rule-sets/{id}/approve}</td>
 *       <td>{@code POST /api/fee-rule-sets/approvals} with {@code id=}</td></tr>
 * </table>
 *
 * <p><b>Two framework constraints force both deviations, and neither is negotiable from inside a
 * module.</b>
 *
 * <p><i>One path, one verb.</i> {@link Routes#get} and {@link Routes#post} each call
 * {@code HttpServer.createContext}, and the JDK's server throws {@code IllegalArgumentException}
 * ("cannot add context to list") on a second context for the same path. A context is also
 * method-agnostic — the 405 for the wrong verb is emitted by {@code EirServer}'s wrapper, before any
 * handler runs — so a collection path can serve {@code GET} <em>or</em> {@code POST} and not both.
 * The bare paths keep the {@code GET}, because that is the discovery request an integrator makes
 * first; the listing response names the {@code POST} paths, so the deviation is self-correcting
 * rather than a surprise. Closing this properly means a verb-agnostic third method on {@code Routes}
 * and a matching arm in {@code EirServer}'s anonymous implementation, which is a shared-file change
 * this unit is not entitled to make.
 *
 * <p><i>A {@code POST} handler cannot read the path.</i> {@link Routes#post} hands its handler a
 * {@link FormBody} and nothing else, so {@code {id}} in {@code .../{id}/approve} is unreadable. The
 * id therefore arrives as a form field. Contexts registered per drafted id at run time would restore
 * the path shape, and were rejected: the route would then exist only after a draft was posted, so an
 * approval naming an unknown id would land on a different handler entirely and be answered as a
 * malformed submission.
 *
 * <p><b>Sub-paths under the two {@code GET} contexts</b>, which <em>can</em> read the path because a
 * {@code GET} handler receives the exchange:
 *
 * <ul>
 *   <li>{@code GET /api/routing-tables/routings?driver=&rateType=} plus either {@code on=<date>}
 *       (the live path: select the version in force on that date) or {@code version=<id>} (the
 *       replay path: the version the event recorded). Exactly one, refused otherwise — a replay that
 *       re-selected by date would apply today's reading to yesterday's events, and DT-1 would either
 *       fail or pass while being wrong.
 *   <li>{@code GET /api/routing-tables/artefacts?version=<id>} — the table re-emitted through
 *       {@link RoutingTableFormat#emit}, which is deterministic, so two versions of one table diff
 *       to exactly the rows that changed. That diff is what a checker approves against.
 *   <li>{@code GET /api/fee-rule-sets/classifications?feeCode=&product=&entity=&asOf=} plus an
 *       optional {@code version=<id>} to pin the reading. An unmapped code refuses here and never
 *       defaults (FR-202).
 * </ul>
 *
 * <h2>Status codes, and the 409 this surface cannot emit</h2>
 *
 * <p>06 § 5 specifies {@code 409} for an approval with no stored impact preview. {@code EirServer}
 * maps a handler's outcome onto exactly three codes — 200 for every answer the engine gives
 * including every refusal, 400 for a malformed request, 500 for a defect — and that is the house
 * rule this module follows rather than works around: <b>a refusal is a value and the whole list comes
 * back</b>. So a self-approval, a table naming {@code DERECOGNITION} and an approval by the wrong
 * checker are all {@code 200} with {@code "approved": false} and the reason. A 400 means the caller
 * sent no {@code table} field at all, or a date that is not a date. FR-210's impact-preview limb is
 * not checked here at all; see {@link RoutingTableRegister} for why a weaker restatement of it would
 * be worse than its absence.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 5.
 */
public final class RuleSetsModule implements ApiModule {

    /** The collection path for routing tables — the {@code GET} context and the sub-path root. */
    private static final String ROUTING_TABLES = "/api/routing-tables";

    /** The collection path for fee rule sets. */
    private static final String FEE_RULE_SETS = "/api/fee-rule-sets";

    /** Named in a submission's fault messages, so the operator knows which endpoint refused. */
    private static final String ROUTING_SOURCE = "POST " + ROUTING_TABLES + "/proposals";

    /**
     * What an approved table here does <em>not</em> yet change, stated on every approval.
     *
     * <p>This module holds its own register because {@code EirService.routing} is a private final
     * field built at construction with no accessor, so there is no seam to share. The consequence has
     * to reach the operator rather than living in a javadoc: an approval that answered only
     * "approved" would entitle them to expect the new reading in the next run, and they would get the
     * baseline with nothing saying so. Both paths go through the same
     * {@code RoutingTableRegistry} and the same {@code DefaultEventRouter}, so what the two differ
     * about is the series, never the rule.
     */
    private static final String NOT_YET_IN_FORCE_FOR =
        "POST /api/run and POST /api/replay, which route through EirService's own series and still"
            + " see only RT-BASELINE-2026.1. Sharing one register with the run means handing it to"
            + " EirService at construction, which is a change to that class rather than to this"
            + " module. Routings under the approved series are answered at"
            + " GET /api/routing-tables/routings.";

    /**
     * How much the four-eyes refusals on this surface are actually worth, stated with them.
     *
     * <p>The gates are real — {@code FourEyes} and {@code MakerCheckerGate} refuse a self-approval on
     * a case-folded identity comparison, and the refusals are tested — but they compare identities
     * the <em>caller supplies</em>. {@code eir-api} has no authentication of any kind and
     * {@code AccessControlModule} registers no route, so one unauthenticated actor can post a table
     * naming two identities and then approve it as the second. Saying "four-eyes is not ceremony
     * here" while shipping that unqualified would be the more misleading of the two available
     * failures, so the qualification travels with every approval: the control is a comparison, and it
     * is worth exactly as much as the authentication in front of it.
     */
    private static final String IDENTITY_ASSURANCE =
        "the maker and checker identities are asserted by the caller and authenticated nowhere:"
            + " eir-api has no authentication and FR-906's role model is unimplemented"
            + " (AccessControlModule registers no route). The four-eyes gate refuses maker =="
            + " checker on a case-folded comparison and cannot establish who either party is.";

    private final RoutingTableRegister routingTables = new RoutingTableRegister();
    private final FeeRuleSetRegister feeRuleSets = new FeeRuleSetRegister();

    /**
     * Validates the collaborator the module contract supplies, and deliberately does not keep it.
     *
     * <p>Nothing on {@link EirService} is a seam onto either register: its own routing series is a
     * private final field built at construction and it exposes no accessor. Keeping a reference this
     * module never calls would advertise a dependency that does not exist. The consequence is stated
     * plainly rather than hidden, here and on the wire through {@link #NOT_YET_IN_FORCE_FOR}:
     * {@code POST /api/run} still routes through the service's own baseline-only series, so a table
     * approved here changes what {@code /api/routing-tables/routings} answers and not yet what a
     * console run does. Closing that means handing one register to both, which is a change to
     * {@code EirService} — a file this unit may not touch. Both paths go through the same
     * {@code RoutingTableRegistry} and the same {@code DefaultEventRouter}, so what the two would
     * disagree about is the series, never the rule.
     *
     * @param service required by the module contract; validated and not retained
     */
    public RuleSetsModule(EirService service) {
        Objects.requireNonNull(service, "service");
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get(ROUTING_TABLES, this::routingTableGet);
        routes.post(ROUTING_TABLES + "/proposals", this::proposeRoutingTable);
        routes.post(ROUTING_TABLES + "/approvals", this::approveRoutingTable);
        routes.get(FEE_RULE_SETS, this::feeRuleSetGet);
        routes.post(FEE_RULE_SETS + "/proposals", this::proposeFeeRuleSet);
        routes.post(FEE_RULE_SETS + "/approvals", this::approveFeeRuleSet);
    }

    @Override
    public String specSection() {
        return "06 § 5";
    }

    // ============================================================ routing tables: GET

    /**
     * Dispatches the three routing-table reads on the sub-path.
     *
     * <p>Done here rather than by registering a context per sub-path, because a deeper context would
     * shadow this one for every path beneath it — prefix matching picks the longest context and pays
     * no attention to the verb — and the collection listing would then 405.
     */
    private Json.Obj routingTableGet(HttpExchange exchange) {
        String tail = subPath(exchange, ROUTING_TABLES);
        return switch (tail) {
            case "" -> routingTableSeries();
            case "routings" -> routing(query(exchange));
            case "artefacts" -> routingArtefact(query(exchange));
            default -> throw new FormBody.BadRequest(
                "no route " + ROUTING_TABLES + "/" + tail + "; this collection serves '' (the"
                    + " series), '/routings' and '/artefacts'");
        };
    }

    /** The approved series and the drafts awaiting a checker. */
    private Json.Obj routingTableSeries() {
        List<Json.Obj> approved = new ArrayList<>();
        for (RoutingTable table : routingTables.approvedTables()) {
            approved.add(describeRoutingTable(table, true));
        }
        List<Json.Obj> pending = new ArrayList<>();
        for (RoutingTable table : routingTables.pendingDrafts()) {
            pending.add(describeRoutingTable(table, false));
        }
        return Json.object()
            .str("specSection", specSection())
            .str("adr", "ADR-0006 — reset-versus-catch-up routing as versioned configuration")
            .count("approvedVersions", approved.size())
            .count("pendingDrafts", pending.size())
            // The registry's own audit sentence, which states the changeover dates. "Which reading
            // was in force when" is the first question asked of a routing, and it should not have to
            // be reconstructed from eight version records.
            .str("series", routingTables.describeSeries())
            .array("approved", approved)
            .array("pending", pending)
            // Named because this collection path cannot itself accept a POST — see the class
            // javadoc. An integrator who starts with a GET here learns the rest of the surface.
            .str("notYetInForceFor", NOT_YET_IN_FORCE_FOR)
            .str("identityAssurance", IDENTITY_ASSURANCE)
            .strings("submitAndApproveAt", List.of(
                "POST " + ROUTING_TABLES + "/proposals  table=<the artefact text>"
                    + " [sourceName=<what to name in a fault>]",
                "POST " + ROUTING_TABLES + "/approvals  id=<version id>&checker=<identity>",
                "GET  " + ROUTING_TABLES + "/routings?driver=&rateType=&on=<date>|version=<id>",
                "GET  " + ROUTING_TABLES + "/artefacts?version=<id>"));
    }

    private static Json.Obj describeRoutingTable(RoutingTable table, boolean approved) {
        RoutingTableVersion version = table.version();
        List<String> routes = new ArrayList<>();
        for (RateDriver driver : RateDriver.values()) {
            routes.add(driver.name() + " -> " + table.mechanismFor(driver).name());
        }
        return Json.object()
            .str("versionId", version.id())
            .str("status", approved ? "APPROVED" : "PENDING_APPROVAL")
            .str("effectiveFrom", version.effectiveFrom().toString())
            .str("maker", version.maker())
            .str("checker", version.checker())
            .str("approvedOn", version.approvedOn().toString())
            .bool("retrospective", version.isRetrospective())
            .str("description", version.description())
            // Every driver, always, in RateDriver declaration order. A response listing only the
            // rows that differ from the baseline would be the more compact answer and the wrong
            // one: the table is total by construction because a defaulted routing is a silently
            // wrong routing, and a partial rendering invites a reader to assume a default.
            .strings("routes", routes);
    }

    /**
     * One routing, under the version in force on a date or under a named version.
     *
     * <p>This is the endpoint the exit gate is observed through. It takes the two inputs that decide
     * a mechanism — the event's driver tag and the instrument's rate type (FR-507) — and one input
     * that decides only <em>which approved reading</em> applies. The observation that a rate moved is
     * not among them, and cannot be: routing on that is the trap row of 03 § 6.2, because a
     * renegotiated fixed-rate loan and an EBLR reset are indistinguishable from there and are a
     * modification and a reset respectively.
     */
    private Json.Obj routing(FormBody query) {
        RateDriver driver = constant(RateDriver.values(), query.text("driver"), "driver");
        RateType rateType = constant(RateType.values(), query.text("rateType"), "rateType");
        boolean byDate = query.has("on");
        boolean byVersion = query.has("version");
        if (byDate == byVersion) {
            // Exactly one, and the refusal is not pedantry. A live routing selects by date; a replay
            // selects by the version id the event recorded, and never by date. Accepting both would
            // mean this endpoint choosing between two readings of a closed period; accepting
            // neither would mean defaulting to one.
            throw new FormBody.BadRequest(
                "give exactly one of 'on' (the event date, which selects the version in force) and"
                    + " 'version' (the id the event recorded, for a replay); got "
                    + (byDate ? "both" : "neither") + ". They are two different questions, and a"
                    + " replay that re-selected by date would apply today's reading to yesterday's"
                    + " events (invariant DT-1)");
        }

        Json.Obj response = Json.object()
            .str("driver", driver.name())
            .str("rateType", rateType.name())
            .str("selectedBy", byDate ? "the version in force on " + query.text("on")
                : "the version id recorded on the event: " + query.text("version"));
        RoutingDecision decision;
        try {
            decision = byDate
                ? routingTables.routeOn(driver, rateType, date(query, "on"))
                : routingTables.replay(driver, rateType, query.text("version"));
        } catch (RoutingTableUnavailableException noTable) {
            // A fact about the configuration, not a malformed request: the date precedes every
            // approved table, or the named version is not held. Reported as a value, because the
            // alternative the registry refuses to offer is worse — substituting the nearest reading
            // would stamp an event with a version id that version never governed.
            return response
                .bool("routed", false)
                .str("refusal", "NO_APPROVED_TABLE")
                .str("detail", noTable.getMessage());
        }
        return response
            .bool("routed", true)
            .str("mechanism", decision.mechanism().name())
            // The load-bearing field. It is persisted on the lifecycle event, and it is what makes
            // replay correct across a change of reading.
            .str("routingTableVersionId", decision.routingTableVersionId())
            .bool("overriddenByRateTypeCheck", decision.overriddenByRateTypeCheck())
            .bool("requiresModificationTest", decision.requiresModificationTest())
            .bool("resolvesRate", decision.resolvesRate())
            .str("rationale", decision.rationale());
    }

    /** One table re-emitted in the format it was submitted in. */
    private Json.Obj routingArtefact(FormBody query) {
        String versionId = query.text("version");
        Optional<RoutingTable> table = routingTables.find(versionId);
        if (table.isEmpty()) {
            return Json.object()
                .str("versionId", versionId)
                .bool("found", false)
                .str("detail", "no approved or pending routing table carries version id '"
                    + versionId + "'");
        }
        return Json.object()
            .str("versionId", versionId)
            .bool("found", true)
            // Round-tripped through the same format the submission arrived in, so the artefact an
            // operator reads back is the artefact they can resubmit. emit is deterministic — fixed
            // key order, fixed LF endings, no generation timestamp — which is what makes comparing
            // two approved artefacts byte for byte meaningful.
            .str("artefact", RoutingTableFormat.emit(table.get()));
    }

    // ============================================================ routing tables: POST

    /** Submits one candidate table, which routes nothing until a checker approves it. */
    private Json.Obj proposeRoutingTable(FormBody body) {
        String text = body.text("table");
        String sourceName = body.textOr("sourceName", ROUTING_SOURCE);
        RoutingTableRegister.Submission submission = routingTables.submit(text, sourceName);

        Json.Obj response = Json.object()
            .bool("accepted", submission.isAccepted())
            .str("versionId", submission.versionId())
            .str("fault", submission.faultKind().name())
            .str("detail", submission.detail());
        if (!submission.isAccepted()) {
            // The one distinction this response exists to carry. A FORMAT fault says the file could
            // not be read and names a line; an ACCOUNTING fault says it read perfectly and states a
            // table no routing may name — DERECOGNITION as a driver's standing treatment, which is
            // the conclusion of the substantiality assessment and never a position. Labelling the
            // second as a parse fault would send the operator hunting for a typo in a clean file.
            return response.str("faultMeans", switch (submission.faultKind()) {
                case FORMAT -> "the artefact could not be read as one approved table; the detail"
                    + " names the offending line where one line is at fault, and the whole file"
                    + " where the fault is an omission — a driver with no route row";
                case ACCOUNTING -> "the artefact read cleanly and states a table this engine must"
                    + " not hold; the detail is the accounting reason, not a parse error";
                case DUPLICATE_ID -> "a version already claims that id, and every routed event"
                    + " stores the id that routed it";
                case NONE -> "nothing";
            });
        }
        RoutingTable draft = submission.table();
        return response
            .bool("supersededAnEarlierDraft", submission.supersededDraft())
            .obj("draft", describeRoutingTable(draft, false))
            .str("approveWith", "POST " + ROUTING_TABLES + "/approvals  id="
                + draft.version().id() + "&checker=" + draft.version().checker());
    }

    /** A checker signs a draft, which appends it to the approved series. */
    private Json.Obj approveRoutingTable(FormBody body) {
        String id = body.text("id");
        String checker = body.text("checker");
        RoutingTableRegister.Approval approval = routingTables.approve(id, checker);

        Json.Obj response = Json.object()
            .str("versionId", id)
            .str("checker", checker)
            .bool("approved", approval.isApproved())
            .str("detail", approval.detail());
        if (!approval.isApproved()) {
            return response.str("refusal", approval.refusal().name());
        }
        return response
            .str("effectiveFrom", approval.approved().version().effectiveFrom().toString())
            .count("approvedVersions", routingTables.approvedTables().size())
            .str("series", routingTables.describeSeries())
            .str("routingsNowAnsweredAt", "GET " + ROUTING_TABLES + "/routings?driver=&rateType="
                + "&on=" + approval.approved().version().effectiveFrom())
            // Stated on the wire, not only in a javadoc nobody serving this response will read.
            // An operator who sees "approved" and then runs a period would otherwise be entitled
            // to expect the new reading in the run, and would get the baseline with no warning.
            .str("notYetInForceFor", NOT_YET_IN_FORCE_FOR)
            .str("identityAssurance", IDENTITY_ASSURANCE);
    }

    // ============================================================ fee rule sets: GET

    private Json.Obj feeRuleSetGet(HttpExchange exchange) {
        String tail = subPath(exchange, FEE_RULE_SETS);
        return switch (tail) {
            case "" -> feeRuleSetVersions(exchange);
            case "classifications" -> classification(query(exchange));
            default -> throw new FormBody.BadRequest(
                "no route " + FEE_RULE_SETS + "/" + tail + "; this collection serves '' (the"
                    + " versions) and '/classifications'");
        };
    }

    /** Every registered version, with the per-code completeness control asserted on each. */
    private Json.Obj feeRuleSetVersions(HttpExchange exchange) {
        FormBody query = query(exchange);
        List<Json.Obj> versions = new ArrayList<>();
        for (FeeRuleSet set : feeRuleSets.all()) {
            // The date the completeness control is asked of defaults to the version's own effective
            // date, which is the FR-210 gate's question: "on the day this goes live, which codes
            // will queue exceptions?". A date-blind form of the control is a hole — a code whose
            // only default is dated 2030 has a default and would report complete on a set effective
            // 2028, while every posting of it between the two dates refuses.
            InvariantResult coverage = query.has("asOf")
                ? set.catchAllCoverage(date(query, "asOf"))
                : set.catchAllCoverage(set.version().effectiveFrom());
            versions.add(describeFeeRuleSet(set, coverage));
        }
        return Json.object()
            .str("specSection", specSection())
            .count("versions", versions.size())
            .array("registered", versions)
            .str("identityAssurance", IDENTITY_ASSURANCE)
            .strings("submitAndApproveAt", List.of(
                "POST " + FEE_RULE_SETS + "/proposals  id=&description=&effectiveFrom=&maker="
                    + "&checker=&rules=<CODE | product | entity | date | CLASSIFICATION | why>",
                "POST " + FEE_RULE_SETS + "/approvals  id=&checker=&approvedOn=",
                "GET  " + FEE_RULE_SETS + "/classifications?feeCode=&product=&entity=&asOf="
                    + "[&version=]"));
    }

    private static Json.Obj describeFeeRuleSet(FeeRuleSet set, InvariantResult coverage) {
        PolicyVersion version = set.version();
        List<String> rules = new ArrayList<>();
        for (FeeRule rule : set.rules()) {
            rules.add(rule.describe());
        }
        return Json.object()
            .str("versionId", version.id())
            .str("status", version.status().name())
            .bool("operative", version.status().isOperative())
            .str("effectiveFrom", version.effectiveFrom().toString())
            .str("audit", version.describe())
            .count("ruleCount", set.rules().size())
            .count("feeCodeCount", set.feeCodes().size())
            // Invariant RS-1: every fee code carries a per-code default (04 § 2.5). Reported, not
            // refused at construction, because a partially-loaded taxonomy is a real state during
            // the sourcing exercise 08 § 0 calls the programme's critical path. The deviation is the
            // count of codes that will raise UNMAPPED_FEE_CODE on any product nobody wrote a
            // carve-out for.
            .str("catchAllCoverage", coverage.id().name() + " "
                + (coverage.satisfied() ? "PASS" : "FAIL"))
            .figure("catchAllDeviation", coverage.deviation())
            .str("catchAllDetail", coverage.detail())
            .strings("rules", rules);
    }

    /**
     * Resolves one fee posting against the taxonomy, or refuses and names the key.
     *
     * <p>The refusal is the behaviour worth exercising. Both available defaults are wrong in the
     * direction nobody checks, so an unmapped code raises into the exception queue under
     * {@code UNMAPPED_FEE_CODE} and carries no classification at all — {@code classification} comes
     * back null, because there is nothing a caller could responsibly read out of it.
     *
     * <p>An optional {@code version=} pins the reading, which is the replay question. Two things
     * about a pinned lookup are answered <em>before</em> the resolver sees it, because the resolver
     * throws on both and a throw here would become a 500 — and a caller's typo'd version id is not
     * an engine defect. An unregistered id is a malformed request (400). An id that exists and is
     * unapproved gets a refusal (200): the question has an honest answer, which is that a version no
     * checker has signed has classified nothing and never can, and that answer is the observable form
     * of the whole draft-then-approve control.
     */
    private Json.Obj classification(FormBody query) {
        String feeCode = query.text("feeCode");
        // Null rather than a wildcard where the caller sent nothing. FeeRuleKey normalises a null
        // dimension to '*', and the asymmetry matters: a rule wildcard matches anything, a lookup
        // wildcard matches only a wildcard rule. So a posting arriving with no entity resolves
        // against the product rule and the per-code default and never against an entity carve-out,
        // which is the conservative reading — an override claimed for a posting that never named an
        // entity would be a guess.
        String product = query.textOr("product", null);
        String entity = query.textOr("entity", null);
        LocalDate asOf = date(query, "asOf");

        FeeClassificationResolution resolution;
        if (query.has("version")) {
            String versionId = query.text("version");
            FeeRuleSet pinned = feeRuleSets.find(versionId).orElseThrow(() ->
                new FormBody.BadRequest(
                    "no fee rule set version '" + versionId + "' is registered, so no computation"
                        + " can cite it and there is nothing to replay against (invariant DT-1)."
                        + " Registered: " + registeredVersionIds()));
            if (!pinned.version().status().isApproved()) {
                // A value, not a 400 and not a throw. Naming an unapproved version is a question
                // with an answer, and the answer is the control: no EIR_COMPUTATION row can cite a
                // DRAFT or PENDING_APPROVAL version, because that version has never classified
                // anything. Held in the register anyway — that is what lets an impact preview be
                // computed against a pending version.
                return Json.object()
                    .str("requested", versionId)
                    .bool("resolved", false)
                    .str("refusal", "VERSION_NOT_APPROVED")
                    .str("classification", null)
                    .str("detail", "fee rule set version '" + pinned.version().id() + "' is "
                        + pinned.version().status() + " and has classified nothing; no computation"
                        + " can cite an unapproved version, so there is no reading to replay"
                        + " against. " + pinned.version().describe());
            }
            // Now unreachable in the resolver: it throws only for an unregistered or unapproved
            // version, and both were just answered above.
            resolution =
                feeRuleSets.classifyAgainstVersion(feeCode, product, entity, asOf, versionId);
        } else {
            resolution = feeRuleSets.classify(feeCode, product, entity, asOf);
        }

        Json.Obj response = Json.object()
            .str("requested", resolution.requested().describe())
            .bool("resolved", resolution.isResolved())
            .str("detail", resolution.detail());
        if (!resolution.isResolved()) {
            return response
                .str("classification", null)
                .str("exceptionCategory", resolution.exception().name());
        }
        return response
            .str("classification", resolution.classification().name())
            .str("ruleSetVersionId", resolution.ruleSetVersionId())
            .str("rule", resolution.rule().describe());
    }

    // ============================================================ fee rule sets: POST

    /** Submits one candidate taxonomy version and offers it to its named checker. */
    private Json.Obj proposeFeeRuleSet(FormBody body) {
        FeeRuleSetRegister.Submission submission = feeRuleSets.submit(
            body.text("id"),
            body.text("description"),
            date(body, "effectiveFrom"),
            body.text("maker"),
            body.text("checker"),
            body.text("rules"));

        Json.Obj response = Json.object()
            .bool("accepted", submission.isAccepted())
            .str("fault", submission.faultKind().name())
            .str("detail", submission.detail());
        if (!submission.isAccepted()) {
            return response.str("faultMeans", switch (submission.faultKind()) {
                case FORMAT -> "the rows could not be read; the detail names the row";
                case ACCOUNTING -> "the rows read cleanly and state a rule set the domain refuses;"
                    + " the detail is that refusal in its own words";
                case DUPLICATE_ID -> "a version already carries that id, and every computation"
                    + " stores the rule_set_version_id that classified its postings";
                case NONE -> "nothing";
            });
        }
        FeeRuleSet draft = submission.draft();
        return response
            .obj("draft", describeFeeRuleSet(draft,
                draft.catchAllCoverage(draft.version().effectiveFrom())))
            .str("approveWith", "POST " + FEE_RULE_SETS + "/approvals  id=" + draft.version().id()
                + "&checker=" + draft.version().checker() + "&approvedOn=<date>");
    }

    /** A checker signs a pending version, which puts it into force from its own effective date. */
    private Json.Obj approveFeeRuleSet(FormBody body) {
        String id = body.text("id");
        String checker = body.text("checker");
        LocalDate approvedOn = date(body, "approvedOn");
        FeeRuleSetRegister.Approval approval = feeRuleSets.approve(id, checker, approvedOn);

        Json.Obj response = Json.object()
            .str("versionId", id)
            .str("checker", checker)
            .bool("approved", approval.isApproved())
            .str("detail", approval.detail());
        if (!approval.isApproved()) {
            return response
                .str("refusal", approval.refusal().name())
                // The gate's own vocabulary, where the gate is what refused. Passed through rather
                // than folded into the coarse code: a batch has to separate "the checker is the
                // maker" (send it to somebody else) from "this jumps the approval step" (a defect
                // in the caller) without matching on English.
                .str("gateRefusal", approval.gateRefusal() == null
                    ? null : approval.gateRefusal().name())
                .str("gateRefusalMeans", approval.gateRefusal() == null
                    ? null : approval.gateRefusal().explanation());
        }
        return response
            .str("status", approval.approved().version().status().name())
            .str("effectiveFrom", approval.approved().version().effectiveFrom().toString())
            .str("audit", approval.approved().version().describe())
            .str("supersession", approval.supersession())
            .str("identityAssurance", IDENTITY_ASSURANCE);
    }

    /** Every registered fee rule set version id, for a refusal that has to name what exists. */
    private List<String> registeredVersionIds() {
        List<String> ids = new ArrayList<>();
        for (FeeRuleSet set : feeRuleSets.all()) {
            ids.add(set.version().id());
        }
        return ids;
    }

    // ============================================================ request reading

    /**
     * The path below a collection context, with no leading or trailing slash.
     *
     * <p>{@code /api/routing-tables} and {@code /api/routing-tables/} both yield {@code ""}, because
     * a trailing slash is not a different resource and a caller should not have to know which form
     * this server prefers.
     */
    private static String subPath(HttpExchange exchange, String base) {
        String path = exchange.getRequestURI().getPath();
        String tail = path.length() > base.length() ? path.substring(base.length()) : "";
        while (tail.startsWith("/")) {
            tail = tail.substring(1);
        }
        while (tail.endsWith("/")) {
            tail = tail.substring(0, tail.length() - 1);
        }
        return tail;
    }

    /**
     * A GET's query string, read through {@link FormBody}.
     *
     * <p>Reused rather than reimplemented: a query string and a form body are the same encoding, and
     * {@code FormBody}'s accessors already refuse rather than default, with the 400 that produces.
     * {@code getRawQuery} and not {@code getQuery} — the latter has already percent-decoded, and
     * decoding twice turns a literal {@code %2B} in a fee code into a {@code +} and then into a
     * space.
     */
    private static FormBody query(HttpExchange exchange) {
        return FormBody.parse(exchange.getRequestURI().getRawQuery());
    }

    /** A required ISO-8601 date field. */
    private static LocalDate date(FormBody body, String key) {
        String raw = body.text(key);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            // 400, not a refusal: a date that is not a date is the caller having sent nonsense, and
            // nothing about the engine's policy is being declined.
            throw new FormBody.BadRequest(
                "'" + key + "' must be an ISO-8601 date, e.g. 2029-04-01; got '" + raw + "'");
        }
    }

    /** One enum constant by exact name, or a 400 listing what the vocabulary is. */
    private static <E extends Enum<E>> E constant(E[] values, String name, String key) {
        for (E candidate : values) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        // Exact match only, for the reason RoutingTableFormat gives about its own vocabulary:
        // accepting 'reset' or 'Reset' would make the surface's vocabulary a matter of taste and
        // the meaning of a stored request ambiguous.
        List<String> known = new ArrayList<>(values.length);
        for (E value : values) {
            known.add(value.name());
        }
        throw new FormBody.BadRequest(
            "'" + key + "' must be one of " + known + " exactly; got '" + name + "'");
    }
}
