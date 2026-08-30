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
}
