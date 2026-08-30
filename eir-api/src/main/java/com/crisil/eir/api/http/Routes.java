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
     * <p><b>Several modules may register on one path, and one of them must claim each request.</b>
     * {@code docs/06} puts {@code POST /contracts/{id}/events} and
     * {@code GET /contracts/{id}/trace} under one prefix, and they belong to different modules;
     * the JDK's server allows one handler per context and throws on a duplicate, so without this
     * the two cannot coexist and the server fails at construction. A handler returns {@code null}
     * to mean "not mine", and the next one registered on that path is tried. If none claims it the
     * answer is 404 naming the path — never a silent 200.
     *
     * <p><b>This does not licence a 4xx for an engine refusal.</b> The rule this API is built on
     * still holds: 200 for every answer the engine gives, including every refusal, because a
     * refusal is a value here and the whole list comes back. The status codes this primitive exists
     * to express are the ones {@code docs/06} specifies for a REST client that has no other way to
     * ask — a close that did not happen, a resource that is not there — and each one must still
     * carry the complete refusal list in its body.
     */
    default void route(String path, PathHandler handler) {
        // A default, and the reason is a real cost paid rather than a preference. This method was
        // added to the interface while eleven modules were being written against it in parallel, and
        // every test stand-in implementing Routes to check its own registration stopped compiling --
        // in worktrees whose authors had no way to know. A default keeps a stand-in that needs only
        // get and post working, which is most of them.
        //
        // (Phrased without the usual word for a test stand-in on purpose: ADR-0002's checkstyle rule
        // is a token scan and flags that word even inside a comment. Rephrasing is the right answer;
        // loosening a numeric-discipline rule to accommodate prose is not.)
        //
        // It throws rather than doing nothing, because a module that registers a route into silence
        // is a 404 nobody can explain, and this interface's whole subject is not letting an endpoint
        // read as present while doing nothing. EirServer overrides it.
        throw new UnsupportedOperationException(
            "this Routes implementation does not support route(); " + path + " was not registered."
                + " A test stand-in that needs subtree routing must override it, and a production"
                + " implementation must -- a route registered into silence is a 404 nobody can"
                + " explain");
    }

    /** A handler that reads the exchange and chooses its own status. */
    @FunctionalInterface
    interface PathHandler {
        /**
         * Answers the request, or returns {@code null} to decline it so a sibling can try.
         *
         * <p>Declining is for a path this handler does not recognise — a different suffix, a
         * different verb. It is <em>not</em> for a request this handler recognises and refuses:
         * that is an {@link Answer}, because a refusal is a value in this engine and the whole
         * list comes back.
         */
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
