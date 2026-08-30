package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.policy.access.AccessControl;
import com.crisil.eir.policy.access.AccessDecision;
import com.crisil.eir.policy.access.AuthorisationRequest;
import com.crisil.eir.policy.access.Capability;
import com.crisil.eir.policy.access.Principal;
import com.crisil.eir.policy.access.Role;
import com.crisil.eir.policy.access.RoleRegister;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * FR-906's role model at the edge: who may run, who may close, and who may approve an acceptance.
 *
 * <p><b>What this closes, and what it does not.</b> {@code eir-api}'s README and its own close
 * response said it plainly: "No authentication or authorisation. FR-906's role model is
 * unimplemented, so the maker-checker identity on a close is whatever the caller typed." The role
 * model now exists — {@code com.crisil.eir.policy.access} — and this module is where it meets HTTP.
 * What still does not exist is <b>authentication</b>. There is no identity provider in this
 * repository, and this module does not pretend otherwise: the identity arrives on the
 * {@value #IDENTITY_HEADER} request header, anyone can type anything into it, and <em>every</em>
 * response says so. A header-asserted identity presented as authentication would be worse than the
 * honest absence it replaces, because the absence at least invites the question.
 *
 * <p>07 § 7 specifies what a production deployment does instead: OAuth2 client credentials over TLS
 * 1.3, with scopes per resource group and an external secret manager. The terminator authenticates,
 * and the name it puts on this header is then worth something. That sentence is on every response as
 * {@code identityAssertedByCaller} and {@code productionNote}, not only here.
 *
 * <p><b>403, and why it is the one genuine client error in this API.</b> Everywhere else a refusal is
 * a value and comes back on a 200 — {@code EirServer}'s own javadoc: "200 for every answer the
 * engine gives, including every refusal, because a refusal is a value in this system and the whole
 * list comes back." An authorisation refusal is not that. Nothing about the portfolio was examined;
 * the caller is simply not entitled to ask. Answering it as a 200-with-refusal would make it
 * indistinguishable from "the period may not close", and answering it as a 404 would hide that the
 * route exists. So it is a 403, and the repo-wide 200 rule is precisely what makes the 403 legible.
 *
 * <p><b>Where the 403 is actually written, and the hook that would make it clean.</b>
 * {@code EirServer.answer} maps a handler's outcome onto exactly three codes — 200 for a returned
 * value, 400 for a {@link FormBody.BadRequest}, 500 for any other {@link RuntimeException} — and
 * there is no fourth. So {@link #forbid} writes the 403 onto the exchange itself, before the
 * server's own {@code respond} gets its turn; the server's later attempt to send a 200 over a
 * completed exchange fails with an {@link IOException} that the JDK's own handler loop swallows and
 * logs, after the client already holds a complete 403 with its body and {@code Content-Length}.
 * That works and is verified over a real socket, but it is a workaround and it is named as one. The
 * hook that would replace it is one clause in {@code EirServer.answer}:
 *
 * <pre>{@code
 * } catch (AccessRefusedException refused) {
 *     respond(exchange, 403, refused.asJson().toString());
 * }
 * }</pre>
 *
 * <p>{@code EirServer} is owned elsewhere, so this module does not add it. See
 * {@code /api/access/coverage}, which publishes the consequence rather than leaving it in a comment.
 *
 * <p><b>Only GET routes, and that is not a style choice.</b> {@code Routes.post} hands a handler a
 * {@link FormBody} and nothing else, so a POST handler registered through the module seam
 * <em>cannot see request headers at all</em> and therefore cannot read an identity. Every mutating
 * route in this API is a POST. That means no POST route in {@code eir-api} can be authorised until
 * the seam carries the exchange, and it is the single most important finding of this unit — 07 § 7's
 * audit log is specified over "every mutating call", which is exactly the set that cannot currently
 * be attributed. The coverage endpoint says so per route.
 *
 * <p>Specification: {@code docs/06-api-spec.md} § 1 (base path and auth), with the role model from
 * {@code docs/07-nfr-controls-audit.md} § 7 and § 4.2 and the automation mandate of § 5.
 */
public final class AccessControlModule implements ApiModule {

    /**
     * The header the caller's identity arrives on.
     *
     * <p>Prefixed {@code X-EIR-} rather than reusing {@code Authorization}, deliberately.
     * {@code Authorization} would say a credential was presented and validated, and nothing here
     * validates anything — a caller reading a working {@code Authorization} header would reasonably
     * conclude the API authenticates.
     */
    public static final String IDENTITY_HEADER = "X-EIR-Identity";

    /** The query parameter naming who started the run an act concerns. */
    private static final String RUN_MAKER_PARAM = "runStartedBy";

    /**
     * The routes this module registers and therefore can guard.
     *
     * <p>Declared once and used both to register and to report, so the coverage endpoint cannot
     * claim to guard a route that was never registered — which would be the coverage report itself
     * becoming the misleading thing it exists to prevent.
     */
    private static final List<String> GUARDED_ROUTES = List.of(
        "GET /api/access/roles", "GET /api/access/whoami",
        "GET /api/access/decisions", "GET /api/access/coverage");

    /** On every response, because a caveat that lives only in a README is a caveat nobody reads. */
    private static final String ASSERTED_NOTE =
        "The identity on this response was ASSERTED BY THE CALLER on the " + IDENTITY_HEADER
            + " request header. Nothing here authenticated it: this engine holds no identity"
            + " provider, and any caller can type any name into that header.";

    /** What a real deployment does instead. 07 § 7. */
    private static final String PRODUCTION_NOTE =
        "A production deployment terminates TLS 1.3 and authenticates upstream — OAuth2 client"
            + " credentials with scopes per resource group (07 § 7) — and only then is the name on"
            + " this header worth anything. This module is the AUTHORISATION half only: given a"
            + " name, what may it do, and is it already on the other side of the control.";

    private final EirService service;
    private final RoleRegister register;

    public AccessControlModule(EirService service) {
        this(service, demonstrationRegister());
    }

    /**
     * Test seam: the register is an argument so a test can build the grant it needs.
     *
     * <p>Package-private rather than public. The register is deployment configuration and a caller
     * that could swap it at runtime would be a caller that could grant itself anything.
     */
    AccessControlModule(EirService service, RoleRegister register) {
        this.service = Objects.requireNonNull(service, "service");
        this.register = Objects.requireNonNull(register, "register");
    }

    /**
     * The engine this module would read through, and the reason it does not.
     *
     * <p>The role model reads no engine state: a capability is a property of the caller, not of the
     * portfolio. There is one thing it <em>would</em> read if the engine recorded it — the identity
     * that started the run whose exceptions an acceptance covers, which is the segregation-of-duties
     * input. {@code EirService.run} takes no identity and stores none, so at this layer the run's
     * maker can only arrive as the {@code runStartedBy} query parameter. That is a demonstration
     * limitation and it is reported on the response: a caller-supplied run maker is a caller-supplied
     * control input, and the production form reads it off the run record.
     */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");

        // Every route here is a GET, because Routes.post cannot deliver a request header to its
        // handler. See the class javadoc — this is a limitation of the seam, not a design choice.
        routes.get("/api/access/roles", this::roles);
        routes.get("/api/access/whoami", this::whoami);
        routes.get("/api/access/decisions", this::decision);
        routes.get("/api/access/coverage", this::coverage);
    }

    @Override
    public String specSection() {
        return "06 § 1 auth; 07 § 7 and § 4.2";
    }

    // ---- the role model, made legible -----------------------------------------------------

    /**
     * The role model itself: the capabilities, the roles, the grant table, and the segregation
     * exceptions standing in it.
     *
     * <p>Guarded by {@link Capability#READ_FIGURES}, which in practice means "present an identity
     * the register holds". Not left open: an unauthenticated caller enumerating the roster is how a
     * caller-asserted identity scheme is turned into a list of names worth asserting.
     *
     * <p>The roster is published <em>to a known caller</em> because an operator driving the console
     * needs to know which identity to present, and because nothing here is secret — the register is
     * authorisation configuration, not credentials.
     */
    private Json.Obj roles(HttpExchange exchange) {
        AccessDecision guard = guard(exchange, Capability.READ_FIGURES);
        if (!guard.permitted()) {
            return forbid(exchange, guard);
        }

        List<Json.Obj> capabilityRows = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            capabilityRows.add(Json.object()
                .str("capability", capability.name())
                .str("description", capability.description())
                .bool("isApproval", capability.isApproval())
                .strings("checks", names(capability.checks())));
        }

        List<Json.Obj> roleRows = new ArrayList<>();
        for (Role role : Role.values()) {
            roleRows.add(Json.object()
                .str("role", role.name())
                .str("description", role.description())
                .strings("capabilities", names(role.capabilities())));
        }

        List<Json.Obj> grantRows = new ArrayList<>();
        for (Principal principal : register.principals()) {
            grantRows.add(Json.object()
                .str("identity", principal.identity())
                .strings("roles", roleNames(principal))
                .strings("toxicCombinations", principal.toxicCombinations()));
        }

        Map<String, List<String>> exceptions = register.segregationExceptions();
        List<Json.Obj> exceptionRows = new ArrayList<>();
        exceptions.forEach((identity, pairs) -> exceptionRows.add(Json.object()
            .str("identity", identity)
            .strings("pairs", pairs)));

        return Json.object()
            .array("capabilities", capabilityRows)
            .array("roles", roleRows)
            .array("grants", grantRows)
            .count("identities", register.size())
            // 07 § 4.1: a control is asserted AND reported. Refusing the act protects the close and
            // leaves the grant in place, so the grant is reported whether or not it was exercised.
            .array("segregationExceptions", exceptionRows)
            .count("segregationExceptionCount", exceptions.size())
            .str("segregationRule",
                "07 § 7: a maker cannot be the checker on the same version — enforced, not"
                    + " advisory. The limb this model adds is the run: the identity that started a"
                    + " run may not approve closing over the exceptions that run produced. The"
                    + " comparison is com.crisil.eir.domain.FourEyes.sameIdentity, borrowed and not"
                    + " restated — that rule was written four times in this codebase with two"
                    + " answers.")
            .str("noOverrideExists",
                "07 § 5: there is deliberately no manual rate override anywhere in this system, so"
                    + " no capability names one and no role could grant one. An action naming one"
                    + " is refused as NO_SUCH_CAPABILITY, not as a missing grant.")
            .str("identityAssertedByCaller", ASSERTED_NOTE)
            .str("productionNote", PRODUCTION_NOTE);
    }

    /**
     * Who the caller says it is, and what that identity may do.
     *
     * <p>403 rather than a 200 naming nobody when the header is absent or the identity is unknown.
     * A 200 answering "you are anonymous and may do nothing" would be this API telling the caller
     * that anonymous is a principal it recognises, and 07 § 7 requires every mutating call to carry
     * one.
     */
    private Json.Obj whoami(HttpExchange exchange) {
        AccessDecision guard = guard(exchange, Capability.READ_FIGURES);
        if (!guard.permitted()) {
            return forbid(exchange, guard);
        }
        Principal principal = guard.principal();

        List<Json.Obj> mayRows = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            mayRows.add(Json.object()
                .str("capability", capability.name())
                .bool("permitted", principal.may(capability))
                .bool("subjectToSegregation", AccessControl.segregationApplies(capability))
                .str("description", capability.description()));
        }

        return Json.object()
            // Echoed verbatim, so an operator can see the string that was actually sent rather than
            // the register's spelling of the same person.
            .str("assertedIdentity", guard.assertedIdentity())
            .str("identity", principal.identity())
            .strings("roles", roleNames(principal))
            .strings("capabilities", names(principal.capabilities()))
            // Reported on the caller's own response, not only in the roles listing: an operator
            // holding both sides of a control should be told so by the tool they use every day.
            .strings("toxicCombinations", principal.toxicCombinations())
            .bool("segregationExceptionStands", !principal.toxicCombinations().isEmpty())
            .array("mayIDo", mayRows)
            .str("identityAssertedByCaller", ASSERTED_NOTE)
            .str("productionNote", PRODUCTION_NOTE);
    }

    /**
     * One authorisation decision: 200 when permitted, 403 when not.
     *
     * <p>This is the endpoint the console asks before offering a button, and it is the shape the
     * {@code EirServer} hook would call. An operator screen that offered an act the gate refuses
     * would train its users to expect refusals, which is how a control stops being read —
     * {@code MakerCheckerGate.legalSuccessors} publishes its move set for the same reason.
     *
     * <p>An absent {@code action} is a 400 and not a 403: the caller has not said what it wants, so
     * there is nothing to be entitled or unentitled to. An {@code action} that names an act this
     * engine does not have is a 403 — see {@link com.crisil.eir.policy.access.AccessRefusal}'s
     * {@code NO_SUCH_CAPABILITY} — because the answer is not "retry properly", it is "never, for
     * anybody" (07 § 5).
     */
    private Json.Obj decision(HttpExchange exchange) {
        Map<String, String> query = query(exchange.getRequestURI());
        String action = query.get("action");
        if (action == null || action.isBlank()) {
            throw new FormBody.BadRequest(
                "action is absent; name the act to be authorised, one of " + names(
                    List.of(Capability.values())));
        }
        String runStartedBy = query.get(RUN_MAKER_PARAM);

        AccessDecision decided = AccessControl.decide(register,
            AuthorisationRequest.overRunStartedBy(header(exchange), action, runStartedBy));
        if (!decided.permitted()) {
            return forbid(exchange, decided);
        }
        return rendered(decided)
            .str("note", "permitted. This is an authorisation decision only: the engine's own gates"
                + " still apply — MakerCheckerGate still refuses a self-approval and a version with"
                + " no stored impact preview, and PeriodCloseGate still refuses a close over an"
                + " unresolved exception.");
    }

    /**
     * Which of this server's routes are actually guarded, and which are not.
     *
     * <p><b>Why this endpoint exists.</b> A half-applied filter that guards some routes and silently
     * misses others is worse than no filter, because a caller reading a 403 on one route reasonably
     * infers the others are guarded too. This module can only enforce on routes it registers, and it
     * registers four reads; every mutating route in this API is registered directly by
     * {@code EirServer} and every one of them is a POST, which the module seam cannot carry a header
     * into. So the gap is published as a value, in the same spirit as
     * {@code /api/reports/approximations} (FR-809): making a shortcut visible is what keeps it
     * defensible.
     *
     * <p>The route list is read from {@link EirServer#routes()} rather than restated, so a route
     * added there and not mapped here appears as {@code UNMAPPED} instead of quietly vanishing from
     * the report.
     */
    private Json.Obj coverage(HttpExchange exchange) {
        AccessDecision guard = guard(exchange, Capability.READ_FIGURES);
        if (!guard.permitted()) {
            return forbid(exchange, guard);
        }

        List<Json.Obj> rows = new ArrayList<>();
        for (String route : EirServer.routes()) {
            RouteRequirement requirement = requirementFor(route);
            rows.add(Json.object()
                .str("route", route)
                .str("requires", requirement.capability())
                .bool("enforced", false)
                .str("why", requirement.why()));
        }
        int unenforced = rows.size();
        for (String route : GUARDED_ROUTES) {
            rows.add(Json.object()
                .str("route", route)
                .str("requires", route.endsWith("/decisions")
                    ? "the action named in the query" : Capability.READ_FIGURES.name())
                .bool("enforced", true)
                .str("why", "registered by this module, which guards it before answering"));
        }

        return Json.object()
            .array("routes", rows)
            .count("unenforcedRoutes", unenforced)
            .count("enforcedRoutes", GUARDED_ROUTES.size())
            .str("notEnumerable",
                "This report covers EirServer's own registrations and this module's. The other ten"
                    + " modules of ApiModules register their routes through the same seam and"
                    + " nothing enumerates them, so their routes are absent from this list rather"
                    + " than reported as unguarded — which they also are. A route registry on"
                    + " Routes would close that, and it is another file's change.")
            .str("theGap",
                "Routes.post hands a handler a FormBody and nothing else, so a POST handler"
                    + " registered through the module seam cannot read a request header and cannot"
                    + " see an identity. Every mutating route in this API is a POST, and the"
                    + " console's routes are registered directly by EirServer rather than through"
                    + " the seam. Both facts have to change for the routes above to be guarded.")
            .strings("hooksNeeded", List.of(
                "EirServer.answer: a fourth clause mapping an access refusal onto 403, so a"
                    + " guarded handler does not have to write the response itself.",
                "Routes.post: hand the handler the HttpExchange alongside the FormBody (or a"
                    + " resolved principal), so a POST route can read " + IDENTITY_HEADER + ".",
                "EirServer's own route registrations: run each console route through the same"
                    + " guard, or move them behind the module seam — a filter that guards the"
                    + " module's routes and misses the console's is worse than none.",
                "EirService.run: record the identity that started the run, so the"
                    + " segregation-of-duties comparison reads the run record rather than a"
                    + " caller-supplied query parameter."))
            .str("identityAssertedByCaller", ASSERTED_NOTE)
            .str("productionNote", PRODUCTION_NOTE);
    }

    // ---- the guard ------------------------------------------------------------------------

    /** A route's required capability and the sentence explaining the mapping. */
    private record RouteRequirement(String capability, String why) {
    }

    /**
     * What a console route would require, per 07 § 7's scopes-per-resource-group.
     *
     * <p>Three routes map to no capability in this model, and they are reported as {@code UNMAPPED}
     * rather than assigned the nearest one. Contract onboarding (FR-101) is a CBS feed rather than
     * one of 02 § 3's four journeys; journal posting (FR-802) is a step inside a run; exception
     * <em>resolution</em> (06 § 6) is distinct from the acceptance 07 § 4.2 names. Guessing a
     * capability for each would put three grants in the model that no document supports, and an
     * invented scope is harder to remove than a named gap.
     */
    private static RouteRequirement requirementFor(String route) {
        return switch (route) {
            case "GET /" -> new RouteRequirement("none",
                "the operator page is a static asset and computes nothing");
            case "GET /api/book" -> new RouteRequirement(Capability.READ_FIGURES.name(),
                "a read of contract state");
            case "POST /api/run" -> new RouteRequirement(Capability.START_RUN.name(),
                "06 § 4's POST /runs");
            case "POST /api/close" -> new RouteRequirement(Capability.CLOSE_PERIOD.name(),
                "06 § 4's POST /periods/{id}/close; the act that makes figures immutable");
            case "POST /api/accept" -> new RouteRequirement(
                Capability.PROPOSE_EXCEPTION_ACCEPTANCE.name() + " + "
                    + Capability.APPROVE_EXCEPTION_ACCEPTANCE.name(),
                "this one call carries BOTH signatures (acceptedBy and approvedBy), so it needs"
                    + " both capabilities and the segregation check over the run's maker — it is"
                    + " the single most under-guarded route in this API, because acceptance is the"
                    + " only route past a queued exception");
            case "POST /api/replay" -> new RouteRequirement(Capability.READ_FIGURES.name(),
                "a shadow replay changes no published figure (DT-1)");
            case "POST /api/onboard" -> new RouteRequirement("UNMAPPED",
                "initial recognition arrives as a CBS feed, not as one of 02 § 3's journeys; this"
                    + " model draws no data-ingest scope and does not invent one");
            case "POST /api/post" -> new RouteRequirement("UNMAPPED",
                "posting journals (FR-802) is a step inside a run rather than a distinct act in"
                    + " 07 § 7's scope list");
            case "POST /api/repair" -> new RouteRequirement("UNMAPPED",
                "exception RESOLUTION (06 § 6's POST /exceptions/{id}/resolve) is distinct from the"
                    + " acceptance 07 § 4.2 names, and this model has no capability for it yet");
            default -> new RouteRequirement("UNMAPPED",
                "this route is not in this module's map; a route added to EirServer and not mapped"
                    + " here is reported rather than omitted");
        };
    }

    /** The register the demonstration console runs on. */
    private static RoleRegister demonstrationRegister() {
        return RoleRegister.of(List.of(
            Principal.of("reader.only", Role.AUDITOR),
            Principal.of("batch.operator", Role.BATCH_OPERATOR),
            Principal.of("financial.controller", Role.FINANCIAL_CONTROLLER),
            Principal.of("product.control", Role.PRODUCT_CONTROL),
            Principal.of("policy.approver", Role.APPROVER),
            // The grant that should not exist, in the register on purpose — the same reason the
            // seeded book contains a contract with no recorded opening balance. A segregation
            // control with no identity capable of breaching it is a control that cannot fail, and
            // this repository has found seventeen of those.
            Principal.of("ops.superuser", Role.BATCH_OPERATOR, Role.APPROVER)));
    }

    /** The identity the caller asserted, or {@code null}. */
    private static String header(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst(IDENTITY_HEADER);
    }

    /** The decision for a route whose requirement is a fixed capability. */
    private AccessDecision guard(HttpExchange exchange, Capability required) {
        return AccessControl.decide(register,
            AuthorisationRequest.of(header(exchange), required.name()));
    }

    /**
     * Writes a 403 and its body onto the exchange, and returns a value that never reaches the wire.
     *
     * <p><b>Read the class javadoc before touching this.</b> {@code EirServer.answer} has exactly
     * three outcomes and none of them is 403, and {@code EirServer} is owned elsewhere. So the
     * response is written here, first; the server's subsequent attempt to send a 200 over a
     * completed exchange throws an {@link IOException} which the JDK's own handler loop catches,
     * logs at trace level and closes the connection on — by which point the client already holds a
     * complete 403 with its body and {@code Content-Length}. Verified over a real socket by
     * {@code AccessControlModuleTest}, which asserts the status and the body a caller actually
     * receives.
     *
     * <p>The returned object is deliberately not empty. If the hook described in the class javadoc
     * is ever added and this method's own write removed, the value returned here is what the server
     * would render — so it carries the whole refusal rather than a placeholder that would then have
     * to be rediscovered.
     */
    private Json.Obj forbid(HttpExchange exchange, AccessDecision decision) {
        Json.Obj body = rendered(decision);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(403, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException unwritable) {
            // The connection died mid-refusal. Reported as a defect rather than swallowed: a
            // guarded route that fails to say it refused looks to a caller exactly like one that
            // was never guarded.
            throw new IllegalStateException(
                "the 403 for " + decision.describe() + " could not be written: "
                    + unwritable.getMessage(), unwritable);
        }
        return body;
    }

    /** A decision as JSON. The same shape whether permitted or refused, so a caller reads one. */
    private static Json.Obj rendered(AccessDecision decision) {
        Json.Obj rendered = Json.object()
            .bool("permitted", decision.permitted())
            .str("assertedIdentity", decision.assertedIdentity() == null
                ? "" : decision.assertedIdentity())
            .str("identity", decision.resolvedPrincipal()
                .map(Principal::identity).orElse(""))
            .strings("roles", decision.resolvedPrincipal()
                .map(AccessControlModule::roleNames).orElse(List.of()))
            .str("capability", decision.resolvedCapability()
                .map(Capability::name).orElse(""))
            .str("refusal", decision.reason().map(Enum::name).orElse(""))
            .bool("segregationBreach", decision.isSegregationBreach())
            // A permit whose segregation limb never ran must not read like one that cleared it.
            .bool("segregationEvaluated", decision.segregationEvaluated())
            .str("detail", decision.detail())
            .str("auditSentence", decision.describe())
            .str("identityAssertedByCaller", ASSERTED_NOTE)
            .str("productionNote", PRODUCTION_NOTE);
        if (decision.isSegregationBreach()) {
            rendered = rendered.str("segregationNote",
                "This is NOT a missing grant. The identity holds the capability; it is already on"
                    + " the other side of this control. Widening the grant would remove the"
                    + " control — a second person has to perform this act (07 § 7).");
        }
        return rendered;
    }

    private static List<String> names(Iterable<Capability> capabilities) {
        List<String> names = new ArrayList<>();
        for (Capability capability : capabilities) {
            names.add(capability.name());
        }
        return names;
    }

    private static List<String> roleNames(Principal principal) {
        List<String> names = new ArrayList<>();
        for (Role role : principal.roles()) {
            names.add(role.name());
        }
        return names;
    }

    /**
     * The query string, decoded.
     *
     * <p>Parsed here rather than through {@code FormBody}, which reads a POST body. The two formats
     * are the same and the duplication is four lines; sharing them would mean widening
     * {@code FormBody}'s contract, which is another module's file.
     */
    private static Map<String, String> query(URI uri) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return parsed;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parsed.put(decode(key), decode(value));
        }
        return parsed;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
