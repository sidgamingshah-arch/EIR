package com.crisil.eir.api.modules.runs;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One request to a resource subtree, and the four answers a resource gives that are not the engine's.
 *
 * <p><b>What this is and is not.</b> {@code Routes.route} registers a subtree and hands the handler
 * the exchange, which is exactly the primitive 06 § 4 needs: {@code GET /api/periods/{id}} and
 * {@code POST /api/periods/{id}/close} are one context, the id is in the path, and the close has to
 * choose a status. This type does the small, dull part of that — split the tail into segments, name
 * the method, and give the resource a 404 and a 405 that read like the rest of the API — so that
 * {@code RunsAndPeriodsModule} contains only decisions about runs and periods.
 *
 * <p><b>It decides nothing the engine decides.</b> Every {@link Routes.Answer} it produces is about
 * the <em>request</em>: a path this resource does not own, a verb that does not belong to a path.
 * Nothing about an EIR figure or a refusal passes through here. That distinction is the reason the
 * status codes below are safe: 200 remains the answer to every question the engine answers, and the
 * one documented exception — 409 on a refused close — is a decision the module makes with the gate's
 * verdict in hand, which is why {@link #conflict} takes a body and nothing else.
 */
public final class Resource {

    private Resource() {
    }

    /**
     * A request, relative to the resource that owns it.
     *
     * @param prefix   the resource path, e.g. {@code /api/periods}
     * @param method   the HTTP verb, uppercase
     * @param path     the full request path, for a 404 that names what was asked for
     * @param segments the path below the prefix, split on {@code /} with empty segments dropped —
     *                 empty for the collection itself, so a trailing slash is not a different
     *                 resource
     * @param form     the parsed body for a POST, or the query string for anything else
     */
    public record Request(
        String prefix, String method, String path, List<String> segments, FormBody form) {

        public Request {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
            Objects.requireNonNull(form, "form");
        }

        /**
         * Whether this request is actually for this resource.
         *
         * <p>The JDK's server matches a context by string prefix, so {@code /api/runsomething}
         * arrives at the {@code /api/runs} context. A resource answers for its own path and its
         * children and 404s anything that merely shares its opening characters — otherwise
         * {@code /api/runsomething} would be read as the run named {@code omething}.
         */
        public boolean belongs() {
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }

        public boolean isGet() {
            return "GET".equals(method);
        }

        public boolean isPost() {
            return "POST".equals(method);
        }
    }

    /** Reads one request against a resource prefix. */
    public static Request of(String prefix, HttpExchange exchange, FormBody body) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(exchange, "exchange");
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        // The body is already parsed for a POST; for every other verb the parameters that exist are
        // in the query string, and FormBody is how this API refuses a missing field rather than
        // defaulting it.
        FormBody form = "POST".equals(method)
            ? Objects.requireNonNull(body, "body")
            : FormBody.parse(exchange.getRequestURI().getRawQuery());
        return new Request(prefix, method, path, segmentsBelow(prefix, path), form);
    }

    private static List<String> segmentsBelow(String prefix, String path) {
        if (!path.startsWith(prefix)) {
            return List.of();
        }
        List<String> segments = new ArrayList<>(2);
        for (String segment : path.substring(prefix.length()).split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /** 200 — the ordinary case, including every engine refusal. */
    public static Routes.Answer ok(Json.Obj body) {
        return Routes.Answer.ok(body);
    }

    /**
     * 409 — a gate refused (06 § 4).
     *
     * <p>The one status in this module that is about the engine's answer rather than the request, and
     * it is the one 06 § 4 specifies: "returning {@code 409} is the intended behaviour, not an error
     * path to route around". The body is still the complete refusal set — both lists — because the
     * status is for the client that branches and the body is for the desk that fixes.
     */
    public static Routes.Answer conflict(Json.Obj body) {
        return Routes.Answer.of(409, body);
    }

    /**
     * 404 — the resource does not exist.
     *
     * <p>Not a refusal, and the difference is worth keeping: a refusal is an answer about something
     * that exists, and this is the absence of the thing. {@code EirServer} answers an unknown route
     * the same way, and the detail says what does exist so an integrator is not left guessing.
     */
    public static Routes.Answer notFound(String path, String detail) {
        return Routes.Answer.of(404, Json.object()
            .str("error", "no route " + path)
            .str("detail", detail));
    }

    /** 405 — the path exists and the verb does not belong to it. */
    public static Routes.Answer methodNotAllowed(String allow, String detail) {
        return Routes.Answer.of(405, Json.object()
            .str("error", allow + " only")
            .str("detail", detail));
    }
}
