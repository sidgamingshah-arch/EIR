package com.crisil.eir.api.modules.runs;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Route registration for endpoints the {@link Routes} seam cannot express — a path parameter, a
 * method chosen per path, and one status code that is not 200.
 *
 * <p><b>Why this exists at all, since a seam already does.</b> {@link Routes} takes a
 * {@code Function<HttpExchange, Json.Obj>} for GET and a {@code Function<FormBody, Json.Obj>} for
 * POST, and {@code EirServer} answers every one of them {@code 200}. That covers the console's flat
 * endpoints exactly, and it covers <b>none</b> of 06 § 4:
 *
 * <ul>
 *   <li>{@code POST /api/runs/{id}/replay} and {@code POST /api/periods/{id}/close} carry their
 *       subject <b>in the path</b>, and a POST handler through the seam receives the form body
 *       only. It cannot see which run or which period it was asked about.</li>
 *   <li>{@code GET /api/periods/{id}} and {@code POST /api/periods/{id}/close} share the path
 *       prefix {@code /api/periods}, and the JDK's server dispatches on the longest matching
 *       prefix. The seam registers a context that is GET-only or POST-only, so one of those two
 *       endpoints would answer 405 to every request whatever was registered.</li>
 *   <li><b>{@code POST /api/periods/{id}/close} must answer 409 when a gate fails</b> (06 § 4,
 *       07 § 4.3), and the seam's handler signature has nowhere to put a status.</li>
 * </ul>
 *
 * <p>So this type registers directly on the {@link HttpServer} instead, with the exchange in hand:
 * one context per resource prefix, dispatching on method and on the path tail, and a handler that
 * returns the status alongside the body. Everything else it copies from {@code EirServer}
 * deliberately — the two headers, the three mappings ({@code FormBody.BadRequest} → 400, an
 * unexpected {@link RuntimeException} → 500 <em>with its own message</em>, otherwise the handler's
 * status) — because an edge where two halves of one API disagree about what a 400 means is worse
 * than either convention.
 *
 * <p><b>The one uncomfortable part, in full.</b> The server is reached by reflecting through the
 * {@link Routes} instance the module was handed: {@code EirServer} registers its modules with an
 * anonymous implementation that closes over the server, and this walks that object graph looking for
 * the {@link HttpServer}. It is a type-directed search over classpath (unnamed-module) classes only,
 * bounded in depth and in nodes, so it does not depend on a field name and never reaches into the
 * JDK. It is still a private field being read, and the honest description is: this module needs a
 * capability the shared seam does not offer, and the alternative available to it was to answer 200
 * on a refused close and let a REST client believe the period had closed. <b>The proper fix is a
 * seam change</b> — {@code Routes} gaining a raw registration, or a status-carrying return type —
 * and it is a change to a file eleven modules share, so it belongs to whoever owns that file rather
 * than to this unit. {@link #behind} fails loudly and says exactly this if the shape it needs is not
 * there, because a silent fall back to the seam would turn the 409 into a 200 at the moment the seam
 * changed, and nothing would be red.
 */
public final class ExchangeRoutes {

    /** How deep the search for the server may go, and how many objects it may touch. */
    private static final int MAX_DEPTH = 4;
    private static final int MAX_NODES = 64;

    private final HttpServer server;

    private ExchangeRoutes(HttpServer server) {
        this.server = server;
    }

    /**
     * The routes of the server behind a {@link Routes} registrar.
     *
     * @throws IllegalStateException if no {@link HttpServer} can be reached, naming the fix
     */
    public static ExchangeRoutes behind(Routes registrar) {
        Objects.requireNonNull(registrar, "registrar");
        HttpServer found = search(registrar);
        if (found == null) {
            throw new IllegalStateException(
                "no com.sun.net.httpserver.HttpServer is reachable from the Routes registrar "
                    + registrar.getClass().getName() + ", so the endpoints of 06 § 4 cannot be"
                    + " registered with a status code of their own — and POST /periods/{id}/close"
                    + " answering 200 on a refused close is not an acceptable degradation (06 § 4:"
                    + " the 409 'is the intended behaviour, not an error path to route around')."
                    + " The fix is to give Routes a raw registration or a status-carrying response"
                    + " type and register RunsAndPeriodsModule's five endpoints through it; see"
                    + " ExchangeRoutes' class note.");
        }
        return new ExchangeRoutes(found);
    }

    /**
     * Registers one resource prefix.
     *
     * <p>The handler owns every path at or below {@code prefix}: it receives the tail and the method
     * and decides between an answer, a 404 and a 405. That is the arrangement a path parameter
     * forces — {@code /api/periods/202805} and {@code /api/periods/202805/close} are one context —
     * and it is why the tail is handed over rather than matched here against a pattern language this
     * module would then have to own.
     *
     * @param prefix a path with no trailing slash, e.g. {@code /api/periods}
     */
    public void prefix(String prefix, Function<Request, Answer> handler) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(handler, "handler");
        if (!prefix.startsWith("/") || prefix.endsWith("/")) {
            throw new IllegalArgumentException(
                "a resource prefix is absolute and carries no trailing slash, got '" + prefix + "'");
        }
        server.createContext(prefix, exchange -> {
            String path = exchange.getRequestURI().getPath();
            // The JDK matches a context by string prefix, so /api/runsomething would arrive at the
            // /api/runs context. A resource answers for its own path and its children, nothing else.
            if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
                write(exchange, Answer.notFound(path, prefix));
                return;
            }
            answer(exchange, handler, path.substring(prefix.length()));
        });
    }

    // ---- one request, one answer -----------------------------------------------------------

    /**
     * What a handler is asked.
     *
     * @param method the HTTP verb, uppercase
     * @param path   the full request path, for a 404 that names what was asked for
     * @param tail   the path below the resource prefix, {@code ""} for the collection itself
     * @param form   the request body for a POST, or the query string for a GET — parsed either way
     *               through {@code FormBody}, so a missing field refuses rather than defaults
     */
    public record Request(String method, String path, String tail, FormBody form) {

        public Request {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(tail, "tail");
            Objects.requireNonNull(form, "form");
        }

        /** The tail, split on {@code /} with empty segments dropped. */
        public List<String> segments() {
            List<String> segments = new ArrayList<>(2);
            for (String segment : tail.split("/")) {
                if (!segment.isBlank()) {
                    segments.add(segment);
                }
            }
            return List.copyOf(segments);
        }

        public boolean isGet() {
            return "GET".equals(method);
        }

        public boolean isPost() {
            return "POST".equals(method);
        }
    }

    /**
     * A status and a body.
     *
     * <p><b>The status is part of the answer here and nowhere else in this engine.</b> Every other
     * endpoint returns 200 for every answer the engine gives, refusals included, because a refusal
     * is a value and the whole list comes back. 06 § 4 makes exactly one exception and gives the
     * reason: a REST client of {@code POST /periods/{id}/close} needs to know from the status line
     * that the period did not close, while the operator console needs the list of why. Both are
     * served by a 409 whose body is the complete refusal set, and neither is served by a 200 that
     * says {@code mayClose: false} to a client that only checked the status.
     */
    public record Answer(int status, Json.Obj body) {

        public Answer {
            Objects.requireNonNull(body, "body");
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("not an HTTP status: " + status);
            }
        }

        /** The engine answered. */
        public static Answer ok(Json.Obj body) {
            return new Answer(200, body);
        }

        /**
         * A gate refused (06 § 4). The body carries every reason, and the caller is expected to
         * read all of them.
         */
        public static Answer conflict(Json.Obj body) {
            return new Answer(409, body);
        }

        /**
         * The resource does not exist.
         *
         * <p>Not a refusal, and the difference is worth keeping: a refusal is an answer about
         * something that exists, and this is the absence of the thing. {@code EirServer} answers
         * an unknown route the same way.
         */
        public static Answer notFound(String path, String detail) {
            return new Answer(404, Json.object()
                .str("error", "no route " + path)
                .str("detail", detail));
        }

        /** The path exists and the verb does not belong to it. */
        public static Answer methodNotAllowed(String allow, String detail) {
            return new Answer(405, Json.object()
                .str("error", allow + " only")
                .str("detail", detail));
        }
    }

    private static void answer(
        HttpExchange exchange, Function<Request, Answer> handler, String tail) throws IOException {
        try {
            Request request = new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                tail,
                "POST".equals(exchange.getRequestMethod())
                    ? FormBody.parse(readBody(exchange))
                    : FormBody.parse(exchange.getRequestURI().getRawQuery()));
            write(exchange, handler.apply(request));
        } catch (FormBody.BadRequest malformed) {
            write(exchange, new Answer(400, Json.object()
                .str("error", "bad request")
                .str("detail", malformed.getMessage())));
        } catch (RuntimeException defect) {
            // Reported with its own message, exactly as EirServer does it: a defect in this layer
            // that arrives as a generic 500 becomes a data-quality ticket against a contract that
            // is fine.
            write(exchange, new Answer(500, Json.object()
                .str("error", defect.getClass().getSimpleName())
                .str("detail", defect.getMessage() == null ? "(no message)" : defect.getMessage())));
        }
    }

    private static String readBody(HttpExchange exchange) {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new FormBody.BadRequest(
                "the request body could not be read: " + unreadable.getMessage());
        }
    }

    private static void write(HttpExchange exchange, Answer answer) throws IOException {
        byte[] bytes = answer.body().toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- finding the server ----------------------------------------------------------------

    /**
     * A breadth-first, type-directed, bounded search for the {@link HttpServer}.
     *
     * <p>Type-directed rather than by field name: {@code this$0} and {@code server} are javac's
     * choice and the registrar's respectively, and a search keyed on either would break silently on
     * a rename that changed nothing. Bounded in depth and node count, and it steps only through
     * classes in the unnamed module — the classpath — so it neither wanders into the object graph
     * hanging off {@code EirService} nor attempts a {@code setAccessible} on a JDK internal, which
     * would throw {@code InaccessibleObjectException} rather than return a server.
     *
     * <p>The server sits two hops from the registrar ({@code Routes} implementation → enclosing
     * {@code EirServer} → its {@code HttpServer}), and the breadth-first order finds it before it
     * touches anything else.
     */
    private static HttpServer search(Object from) {
        Deque<Node> queue = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(new Node(from, 0));
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            Node node = queue.poll();
            if (node.value == null || !seen.add(node.value)) {
                continue;
            }
            visited++;
            if (node.value instanceof HttpServer server) {
                return server;
            }
            if (node.depth >= MAX_DEPTH) {
                continue;
            }
            for (Class<?> type = node.value.getClass();
                type != null && type != Object.class;
                type = type.getSuperclass()) {
                if (type.getModule().isNamed()) {
                    continue;
                }
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        queue.add(new Node(field.get(node.value), node.depth + 1));
                    } catch (ReflectiveOperationException | RuntimeException unreachable) {
                        // A field this JVM will not open is not a field the server is behind. The
                        // search continues; behind() is what reports total failure.
                        continue;
                    }
                }
            }
        }
        return null;
    }

    private record Node(Object value, int depth) {
    }
}
