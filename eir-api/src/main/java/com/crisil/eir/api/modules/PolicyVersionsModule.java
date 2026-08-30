package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.policy.PolicyVersionsSurface;
import com.crisil.eir.api.store.Seed;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * policy-version lifecycle: draft, the MANDATORY impact preview, and approve — which must 409
 * without a stored preview (FR-210).
 *
 * <p>The endpoints themselves are {@link PolicyVersionsSurface}; this class is the wiring, and the
 * wiring needs explaining because it does not go through {@link Routes}.
 *
 * <h2>Why not through the route seam</h2>
 *
 * <p>{@link Routes} offers two primitives — a GET whose handler receives the exchange, and a POST
 * whose handler receives only a parsed form body — and 06 § 5 needs three things neither can
 * supply:
 *
 * <ol>
 *   <li><b>A status code that is not 200, 400 or 500.</b> {@code EirServer.answer} calls
 *       {@code respond(exchange, 200, …)} for every value a handler returns; 400 and 500 are its
 *       own two catch blocks. There is no way for a handler to say {@code 409}, and 06 § 5's whole
 *       requirement is a {@code 409}.
 *   <li><b>A path parameter on a POST.</b> A POST handler never sees the request path, so
 *       {@code /{id}/approve} and {@code /{id}/impact-preview} are indistinguishable from each
 *       other, and the {@code id} in either is unreachable. Inferring the endpoint from which form
 *       fields happen to be present is the kind of guess this engine refuses everywhere else
 *       (FR-507: treatment is never inferred from the observation that a value moved).
 *   <li><b>Both verbs on one path.</b> Each registration creates a JDK {@code HttpContext} and a
 *       second context at the same path is rejected outright, so {@code GET /policy-versions} and
 *       {@code POST /policy-versions} — both in 06 § 5's table — cannot both be registered.
 * </ol>
 *
 * <p>So this module takes one JDK context for the whole {@code /api/policy-versions} subtree and
 * the surface does its own dispatch, mirroring {@code EirServer}'s status mapping exactly.
 * {@code EirServer} and {@code ApiModules} are shared files this unit may not edit, and the shared
 * server is not reachable from anything a module is handed, so it is recovered from the
 * {@code Routes} instance itself — see {@link #sharedServerBehind}.
 *
 * <p><b>What would make that unnecessary.</b> One primitive on {@code Routes}:
 * {@code void route(String path, BiFunction<HttpExchange, FormBody, Answer> handler)} where
 * {@code Answer} carries a status code alongside the body, with {@code EirServer} writing
 * {@code answer.status()} instead of a literal 200. {@code RunsAndPeriodsModule} needs the same
 * thing — 06 § 4's {@code POST /periods/{id}/close} is also a {@code 409} on a path parameter — so
 * this is a seam gap and not a quirk of this section. Until the seam grows that method, the
 * recovery below is the only way this section can be served at the paths and status codes its
 * specification states.
 *
 * <h2>Why an unrecoverable server is fatal rather than skipped</h2>
 *
 * <p>{@code ApiModule} says a module registering nothing is legitimate, and for a stub it is. It is
 * not legitimate here. If this module quietly registered nothing, {@code POST
 * /api/policy-versions/{id}/approve} would 404 — and a 404 on an approval endpoint is
 * indistinguishable, from outside, from an approval gate that is present and permissive. This
 * repository has the sentence for it in its own history: an invariant nobody evaluated reads
 * exactly like one that passed. So the failure is loud, at construction, with the remedy in the
 * message.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 5.
 */
public final class PolicyVersionsModule implements ApiModule {

    /**
     * How far to walk from the {@code Routes} instance looking for the shared server.
     *
     * <p>Two hops is what the current shape needs — the anonymous {@code Routes} holds its
     * enclosing {@code EirServer}, which holds the {@code HttpServer}. Three is allowed so that one
     * extra layer of indirection does not break the recovery, and no more, because an unbounded
     * walk over an object graph is how a helper like this starts reaching into things nobody
     * intended it to see.
     */
    private static final int MAX_SEAM_DEPTH = 3;

    private final EirService service;

    public PolicyVersionsModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * The engine this module was handed.
     *
     * <p><b>Unused by the policy-version surface, and that is a limitation worth naming rather than
     * hiding.</b> {@link EirService} publishes its answers as JSON and exposes no accessor for the
     * {@code Book}, so a module cannot read the live portfolio through it. The impact preview
     * therefore measures a book positioned identically to the server's — {@code Seed.book()} — and
     * says so on its own response. A contract onboarded through {@code POST /api/onboard} during
     * this process is consequently not in the population a preview reports. Closing that needs one
     * accessor on {@code EirService}, which is a shared file this unit may not edit.
     */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        HttpServer server = sharedServerBehind(routes).orElseThrow(
            () -> new IllegalStateException(
                "PolicyVersionsModule cannot serve 06 § 5: the Routes seam cannot express a 409,"
                    + " a path parameter on a POST, or two verbs on one path, and the shared"
                    + " HttpServer was not reachable from the Routes instance"
                    + " (" + routes.getClass().getName() + ") to register a context directly."
                    + " Give Routes a status-carrying primitive — route(path, (exchange, form) ->"
                    + " Answer(status, body)) with EirServer writing answer.status() — and wire"
                    + " this module through it. Failing loudly rather than registering nothing:"
                    + " a 404 on POST " + PolicyVersionsSurface.BASE + "/{id}/approve is"
                    + " indistinguishable from an approval gate that is present and permissive"
                    + " (FR-210)"));
        // One context for the subtree. The JDK routes by longest matching path prefix, so this
        // receives /api/policy-versions and everything beneath it, which is what lets the surface
        // read {id} out of the path.
        server.createContext(PolicyVersionsSurface.BASE, PolicyVersionsSurface.over(Seed.book()));
    }

    @Override
    public String specSection() {
        return "06 § 5";
    }

    /**
     * The {@code HttpServer} the seam writes to, found by walking out of the {@code Routes}
     * instance.
     *
     * <p><b>Reflective, contained, and read-only.</b> It reads field values; it sets nothing, calls
     * no non-public method, and touches no JDK internal — {@code HttpServer.createContext} is
     * public API. The walk is breadth-first from the {@code Routes} object, bounded to
     * {@value #MAX_SEAM_DEPTH} hops, and returns the first {@code HttpServer} it finds. Every
     * failure mode — a field that will not open, a getter that throws — is skipped rather than
     * propagated, because a single unreadable field must not decide the answer.
     *
     * <p>This is a bridge over a gap in a shared file, not a pattern to copy. It exists because
     * 06 § 5's status codes and paths are specification, {@code EirServer} is frozen for parallel
     * work, and the alternative is a surface that answers at the wrong paths with the wrong codes.
     * The method above says what would delete it.
     */
    static Optional<HttpServer> sharedServerBehind(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> level = new ArrayDeque<>();
        level.add(routes);
        visited.add(routes);

        for (int depth = 0; depth < MAX_SEAM_DEPTH && !level.isEmpty(); depth++) {
            Deque<Object> next = new ArrayDeque<>();
            while (!level.isEmpty()) {
                for (Object value : fieldValuesOf(level.poll())) {
                    if (value instanceof HttpServer found) {
                        return Optional.of(found);
                    }
                    if (visited.add(value)) {
                        next.add(value);
                    }
                }
            }
            level = next;
        }
        return Optional.empty();
    }

    /** Every non-null instance-field value of {@code holder} that could be read. */
    private static List<Object> fieldValuesOf(Object holder) {
        List<Object> values = new ArrayList<>();
        Class<?> type = holder.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value != null) {
                        values.add(value);
                    }
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    // Skipped on purpose. A field the runtime will not open says nothing about
                    // whether the server is behind some other field, and letting one refusal end
                    // the walk would make the recovery depend on field declaration order.
                    continue;
                }
            }
            type = type.getSuperclass();
        }
        return values;
    }
}
