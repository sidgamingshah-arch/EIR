package com.crisil.eir.api.http;

import com.sun.net.httpserver.HttpExchange;
import java.util.function.Function;

/**
 * Where an {@link ApiModule} registers its endpoints.
 *
 * <p><b>Why this interface exists at all.</b> Every route used to be registered inline in
 * {@code EirServer}'s constructor, which is fine for one author and impossible for several: ten
 * people adding endpoints to one constructor is ten conflicts on one file. A module registers
 * through this and owns no shared line.
 *
 * <p>The two verbs are the two this API has. A third belongs here when something genuinely needs
 * it, not in advance.
 */
public interface Routes {

    /**
     * A GET route. The handler receives the exchange so it can read query parameters and the path.
     *
     * <p>A non-GET request to this path answers 405 rather than falling through to the page
     * handler, because a 404 on a route that exists sends an integrator looking in the wrong place.
     */
    void get(String path, Function<HttpExchange, Json.Obj> handler);

    /**
     * A POST route. The handler receives the parsed form body.
     *
     * <p>See {@code FormBody} for why the request format is form-encoded while the response is
     * JSON: writing JSON by hand cannot be wrong in a way that matters, and parsing it by hand is a
     * tokeniser, a number grammar and a string-escape state machine.
     */
    void post(String path, Function<FormBody, Json.Obj> handler);

    /**
     * A route over a path subtree that chooses its own status code and reads its own path.
     *
     * <p><b>Added because three units needed it and one reflected into this interface to get it.</b>
     * {@link #get} and {@link #post} cover the console's shape — one verb, one fixed path, always
     * 200 — and {@code docs/06} does not have that shape. Section 4's period close is specified to
     * answer <b>409</b> with the failing gates enumerated; section 5 puts {@code GET} and
     * {@code POST} on the same {@code /policy-versions} path and a path parameter on
     * {@code POST .../{id}/approve}; section 8 needs a genuine 404 for an unknown contract rather
     * than a 200 carrying {@code found: false}. None of that is expressible above.
     *
     * <p>The handler receives the exchange — so it can read the method, the remaining path segments
     * and the query — and the parsed body, which is empty for a GET. It returns an {@link Answer}
     * carrying the status it wants.
     *
     * <p><b>This does not licence a 4xx for an engine refusal.</b> The rule this API is built on
     * still holds: 200 for every answer the engine gives, including every refusal, because a
     * refusal is a value here and the whole list comes back. The status codes this primitive exists
     * to express are the ones {@code docs/06} specifies for a REST client that has no other way to
     * ask — a close that did not happen, a resource that is not there — and each one must still
     * carry the complete refusal list in its body.
     */
    void route(String path, PathHandler handler);

    /** A handler that reads the exchange and chooses its own status. */
    @FunctionalInterface
    interface PathHandler {
        Answer handle(HttpExchange exchange, FormBody body);
    }

    /**
     * A status and a body.
     *
     * @param status the HTTP status; the body is still the engine's complete answer
     * @param body   the response object, never null — a status with no explanation is not an answer
     */
    record Answer(int status, Json.Obj body) {
        public Answer {
            if (body == null) {
                throw new IllegalArgumentException(
                    "status " + status + " with no body; a caller told only a number cannot act on"
                        + " it, and this API's whole discipline is that the reasons come back");
            }
        }

        /** 200 — the ordinary case, including every engine refusal. */
        public static Answer ok(Json.Obj body) {
            return new Answer(200, body);
        }

        /** Any other status {@code docs/06} specifies, with the reasons still in the body. */
        public static Answer of(int status, Json.Obj body) {
            return new Answer(status, body);
        }
    }
}
