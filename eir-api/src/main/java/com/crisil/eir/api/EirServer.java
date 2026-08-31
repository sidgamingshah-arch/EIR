package com.crisil.eir.api;

import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.ApiModules;
import com.crisil.eir.api.store.Seed;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The server: five JSON endpoints and one page, on the JDK's own HTTP server.
 *
 * <p><b>No framework, and no ADR-0010 exemption.</b> ADR-0010 exists so that a module which
 * genuinely needs a framework can take one with the authority written down. This module does not
 * need one — {@code com.sun.net.httpserver} has shipped in the JDK since 6 and covers five routes
 * and a static page. Two things follow that are worth more here than the convenience: the whole
 * repository still builds and runs with {@code -o}, so an air-gapped CI needs no network, and there
 * is nothing between an HTTP request and an invariant result.
 *
 * <p><b>Single-threaded, on purpose and not as an oversight.</b> {@link EirService} holds the last
 * run so that a close can read the figures a run published, and making that concurrent would mean
 * deciding what two simultaneous closes of one period mean. The domain already has an answer —
 * {@code PeriodCloseGate} refuses a second close of a closed period — so the decision belongs to the
 * period's own status rather than to a lock here. One executor thread keeps this layer from
 * inventing a second answer.
 *
 * <p><b>Status codes.</b> 200 for every answer the engine gives, including every refusal, because a
 * refusal is a value in this system and the whole list comes back. 400 only for a malformed request.
 * 500 only for a defect, with the exception's own message, because a stack trace swallowed at the
 * edge is how a defect becomes a data-quality ticket.
 */
public final class EirServer {

    /** The default port. Overridden by the first command-line argument. */
    public static final int DEFAULT_PORT = 8080;

    private final HttpServer server;
    private final EirService service;

    /** Handlers per shared subtree, in registration order. See {@link #route}. */
    private final Map<String, List<Routes.PathHandler>> subtreeHandlers = new LinkedHashMap<>();

    /**
     * Every route registered on this server, as {@code "VERB /path"}, in registration order.
     *
     * <p>Recorded rather than restated. {@link #routes()} used to be a hand-maintained list of the
     * original nine endpoints, and {@code AccessControlModule}'s coverage report read it — so once
     * ten modules had registered roughly thirty more routes, the report named nine as owing a guard
     * and said nothing about the others, which are also unguarded. A control that reads as coverage
     * is worse than an absent one, and the only fix is for the inventory to come from the
     * registration rather than from somebody remembering to update a list.
     *
     * <p>A subtree route is recorded as {@code "ANY /path"}: the handler owns every verb and
     * everything below the path, and it chooses which shapes it claims. Naming a verb it does not
     * restrict would be a more precise-looking and less true entry.
     */
    private final List<String> registrations = new ArrayList<>();

    public EirServer(int port, EirService service) throws IOException {
        this.service = Objects.requireNonNull(service, "service");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newSingleThreadExecutor());

        get("/api/book", exchange -> service.book());
        post("/api/run", service::run);
        post("/api/repair", service::repair);
        post("/api/accept", service::accept);
        post("/api/post", service::post);
        post("/api/close", service::close);
        post("/api/replay", service::replay);
        post("/api/onboard", service::onboard);

        // Every module in docs/06's surface, each registering its own routes. The list is in
        // ApiModules; nothing here knows what any module does, which is what lets the surface be
        // built by several hands without any of them editing this file.
        Routes routes = new Routes() {
            @Override
            public void get(String path, Function<HttpExchange, Json.Obj> handler) {
                EirServer.this.get(path, handler);
            }

            @Override
            public void post(String path, Function<FormBody, Json.Obj> handler) {
                EirServer.this.post(path, handler);
            }

            @Override
            public void route(String path, Routes.PathHandler handler) {
                EirServer.this.route(path, handler);
            }

            @Override
            public java.util.Optional<List<String>> registeredRoutes() {
                // Live, and read at request time: a module holding this seam and asking during
                // register() would see only the modules registered before it.
                return java.util.Optional.of(List.copyOf(registrations));
            }
        };
        for (ApiModule module : ApiModules.all(service)) {
            module.register(routes);
        }

        // The operator page, last so that every /api prefix above wins the longest-prefix match.
        // Recorded in the inventory like everything else: a report of what is exposed that omitted
        // the one route a browser reaches first would be a strange kind of inventory, and the
        // access-control coverage report has a row for it saying it owes no guard.
        registrations.add("GET /");
        server.createContext("/", this::page);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        EirServer server = new EirServer(port, new EirService(Seed.book()));
        server.start();
        System.out.println("EIR engine on http://localhost:" + server.port());
        System.out.println("Book: period " + Seed.PERIOD_ID + ", "
            + Seed.PERIOD_START + " to " + Seed.PERIOD_END);
    }

    // ---- routing ---------------------------------------------------------------------------

    private void get(String path, Function<HttpExchange, Json.Obj> handler) {
        registrations.add("GET " + path);
        server.createContext(path, exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Json.object().str("error", "GET only").toString());
                return;
            }
            answer(exchange, () -> handler.apply(exchange));
        });
    }

    private void post(String path, Function<FormBody, Json.Obj> handler) {
        registrations.add("POST " + path);
        server.createContext(path, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, Json.object().str("error", "POST only").toString());
                return;
            }
            answer(exchange, () -> handler.apply(FormBody.parse(readBody(exchange))));
        });
    }

    /**
     * A subtree route whose handler picks its own status.
     *
     * <p>Registered with {@code createContext} on the path, so the handler also owns everything
     * below it — which is how a path parameter is read at all. The three failure mappings are the
     * same as everywhere else in this class: a malformed request is a 400, an engine defect is a
     * 500 with its own message, and anything the handler returns is written as it asked.
     */
    private void route(String path, Routes.PathHandler handler) {
        // Several modules may share one prefix -- docs/06 puts POST /contracts/{id}/events and
        // GET /contracts/{id}/trace under one, owned by different modules -- and the JDK's server
        // throws on a duplicate createContext. So the context is created once per path and the
        // handlers registered on it are tried in registration order until one claims the request.
        List<Routes.PathHandler> chain =
            subtreeHandlers.computeIfAbsent(path, key -> new ArrayList<>());
        chain.add(handler);
        if (chain.size() > 1) {
            // A second module on the same prefix. Not recorded again: the inventory answers "what
            // is exposed", and one path is one exposure however many modules take turns claiming
            // requests under it. Recording it twice would inflate the coverage report's own
            // denominator, on the endpoint whose purpose is an accurate count.
            return;
        }
        registrations.add("ANY " + path);
        server.createContext(path, exchange -> {
            try {
                String body = "POST".equals(exchange.getRequestMethod()) ? readBody(exchange) : "";
                FormBody parsed = FormBody.parse(body);
                Routes.Answer answer = null;
                for (Routes.PathHandler candidate : subtreeHandlers.get(path)) {
                    answer = candidate.handle(exchange, parsed);
                    if (answer != null) {
                        break;
                    }
                }
                if (answer == null) {
                    // Nobody claimed it. A 404 naming the path, never a silent 200 -- an endpoint
                    // that answers cleanly while doing nothing is the shape docs/06's own reader
                    // cannot distinguish from one that works.
                    respond(exchange, 404, Json.object()
                        .str("error", "no handler claimed " + exchange.getRequestURI().getPath())
                        .str("detail", "the path prefix " + path + " is registered, but no module"
                            + " recognised this method and suffix")
                        .toString());
                    return;
                }
                respond(exchange, answer.status(), answer.body().toString());
            } catch (FormBody.BadRequest malformed) {
                respond(exchange, 400, Json.object()
                    .str("error", "bad request")
                    .str("detail", malformed.getMessage())
                    .toString());
            } catch (RuntimeException defect) {
                respond(exchange, 500, Json.object()
                    .str("error", defect.getClass().getSimpleName())
                    .str("detail", defect.getMessage() == null
                        ? "(no message)" : defect.getMessage())
                    .toString());
            }
        });
    }

    /**
     * Runs one handler and maps its outcome onto a status code.
     *
     * <p>The three cases are the three genuinely different things that can happen, and collapsing
     * any two of them is how an HTTP layer destroys the distinction the engine is built on: the
     * engine answered (200, whatever the answer), the caller sent nonsense (400), or something is
     * broken (500).
     */
    private void answer(HttpExchange exchange, Supplier handler) throws IOException {
        try {
            respond(exchange, 200, handler.get().toString());
        } catch (FormBody.BadRequest malformed) {
            respond(exchange, 400, Json.object()
                .str("error", "bad request")
                .str("detail", malformed.getMessage())
                .toString());
        } catch (RuntimeException defect) {
            // Reported, not swallowed. An engine defect that reaches here is a bug in the engine or
            // in this layer's wiring, and the message names which — the alternative is an operator
            // filing it as a data-quality issue against a contract that is fine.
            respond(exchange, 500, Json.object()
                .str("error", defect.getClass().getSimpleName())
                .str("detail", defect.getMessage() == null ? "(no message)" : defect.getMessage())
                .toString());
        }
    }

    /** A handler's body, so {@link #answer} can wrap it. */
    @FunctionalInterface
    private interface Supplier {
        Json.Obj get();
    }

    private static String readBody(HttpExchange exchange) {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new FormBody.BadRequest("the request body could not be read: "
                + unreadable.getMessage());
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The operator page, from the module's own resources. */
    private void page(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/favicon.ico".equals(path)) {
            // 204 rather than 404. A browser asks for this unprompted, and a console full of
            // red 404s trains an operator to ignore the console — which is where this tool
            // reports the things that matter.
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            respond(exchange, 404, Json.object().str("error", "no route " + path).toString());
            return;
        }
        byte[] html;
        try (InputStream resource =
                EirServer.class.getResourceAsStream("/web/index.html")) {
            if (resource == null) {
                respond(exchange, 500, Json.object()
                    .str("error", "the operator page is missing from the jar")
                    .toString());
                return;
            }
            html = resource.readAllBytes();
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(html);
        }
    }

    /**
     * Every route this server registered, as {@code "VERB /path"}, in registration order.
     *
     * <p><b>An instance method, and that is the fix rather than a refactor.</b> This was a static
     * hand-maintained list of the nine endpoints the console needed, and
     * {@code AccessControlModule}'s coverage report read it to decide which routes owe an
     * authorisation guard. Ten modules then registered roughly thirty more, and the report went on
     * naming nine — reporting a gap of nine over a surface of forty, which is a control that reads
     * as coverage. A static method could not be right: what is registered depends on which modules
     * {@code ApiModules} returns, which is per-server state.
     *
     * <p>The operator page is included as {@code GET /}, registered last so that every {@code /api}
     * prefix wins the longest-prefix match. A subtree appears as {@code ANY /path} — see
     * {@link #registrations}.
     */
    public List<String> routes() {
        return List.copyOf(registrations);
    }
}
