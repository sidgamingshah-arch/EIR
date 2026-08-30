package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Seed;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-906's role model at the HTTP edge, driven over a real socket.
 *
 * <p><b>Why over a socket and not by calling the module.</b> Every defect this unit can introduce
 * lives between the two, and the most important one is the status code. A 403 that the module
 * believes it produced and that reaches the caller as a 200 is not a control — the whole point of
 * the repo-wide "engine refusals are 200s" rule is that an authorisation failure stands out, and only
 * a socket can tell you whether it did. {@code EirServer.answer} maps a handler's outcome onto 200,
 * 400 or 500 and has no fourth clause, so the 403 is written onto the exchange by the module itself
 * (see {@code AccessControlModule.forbid}); whether that actually works is a question about bytes on
 * a wire, and this file is the only thing that can answer it.
 *
 * <p>Port 0 lets the OS pick, so this runs anywhere and never collides with a developer's own
 * server. Assertions are on substrings of the JSON, following {@code EirServerTest}: this module has
 * no JSON parser and writing one for the test would be the largest untested thing in it. The
 * substrings are refusal constants and identity strings, which a formatting change cannot
 * accidentally satisfy.
 *
 * <p><b>Where the expected values come from.</b> The role table in
 * {@code com.crisil.eir.policy.access.Role}, read off 02 § 3's journeys, and the refusal constants in
 * {@code AccessRefusal}. No expected value here was obtained by running the code.
 *
 * <p><b>The failing input the segregation case is built from.</b> {@code ops.superuser} is granted
 * {@code BATCH_OPERATOR} and {@code APPROVER} in the demonstration register — the composed grant a
 * deployment drifts into the first time somebody needs cover over a quarter-end. No single role in
 * the model holds both {@code START_RUN} and {@code APPROVE_EXCEPTION_ACCEPTANCE}, so read as a
 * property of the role table the breach would be unreachable and the control could not fail. It is
 * reachable through the grant, and this is where it is reached.
 */
class AccessControlModuleTest {

    private EirServer server;
    private String base;

    @BeforeEach
    void startOnAnEphemeralPort() throws IOException {
        // The real wiring: EirServer builds ApiModules.all(service), which includes this module with
        // its demonstration register. Nothing is stubbed, so a module that failed to register would
        // show up here as a 404 rather than as a passing test against a hand-built object.
        server = new EirServer(0, new EirService(Seed.book()));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private record Response(int status, String body) {
    }

    /** A GET with an asserted identity. A {@code null} identity sends no header at all. */
    private Response get(String path, String identity) throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        if (identity != null) {
            connection.setRequestProperty(AccessControlModule.IDENTITY_HEADER, identity);
        }
        return read(connection);
    }

    private Response post(String path, String identity, String form) throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        if (identity != null) {
            connection.setRequestProperty(AccessControlModule.IDENTITY_HEADER, identity);
        }
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        try (var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            return new Response(status, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("an unauthorised call is a 403, and the distinction survives the wire")
    class TheStatusCode {

        @Test
        @DisplayName("the read-only identity is refused START_RUN with a 403, not a 200 or a 404")
        void areadOnlyIdentityIsForbidden() throws IOException {
            Response response = get("/api/access/decisions?action=START_RUN", "reader.only");

            assertThat(response.status())
                .as("403 is the one genuine client error in this API; engine refusals are 200s"
                    + " precisely so that this stands out")
                .isEqualTo(403);
            assertThat(response.body())
                .contains("\"permitted\":false")
                .contains("ROLE_LACKS_CAPABILITY")
                .contains("READ_FIGURES");
        }

        @Test
        @DisplayName("the batch operator is permitted START_RUN with a 200")
        void thebatchOperatorIsPermitted() throws IOException {
            Response response = get("/api/access/decisions?action=START_RUN", "batch.operator");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"permitted\":true")
                .contains("\"capability\":\"START_RUN\"")
                .contains("\"refusal\":\"\"");
        }

        @Test
        @DisplayName("no identity at all is a 403; there is no anonymous role")
        void noIdentityIsForbidden() throws IOException {
            Response response = get("/api/access/whoami", null);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body())
                .contains("NO_IDENTITY_ASSERTED")
                .contains("no anonymous role");
        }

        @Test
        @DisplayName("an unknown identity is a 403 and gets no read-only default")
        void anunknownIdentityIsForbidden() throws IOException {
            Response response = get("/api/access/whoami", "intruder");

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body())
                .contains("UNKNOWN_IDENTITY")
                .contains("no default role");
        }

        @Test
        @DisplayName("a 403 does not damage the server: the next request is answered normally")
        void the403LeavesTheServerUsable() throws IOException {
            // The 403 is written onto the exchange before EirServer's own respond() runs, and the
            // server's later attempt to send a 200 over a completed exchange fails. If that failure
            // ever escaped the JDK's handler loop it would take the single executor thread with it,
            // and every subsequent request would hang. This is the test that would notice.
            assertThat(get("/api/access/whoami", "intruder").status()).isEqualTo(403);
            assertThat(get("/api/access/whoami", "reader.only").status()).isEqualTo(200);
            assertThat(get("/api/book", null).status())
                .as("an unrelated route must still answer after a refusal")
                .isEqualTo(200);
        }

        @Test
        @DisplayName("the wrong verb on a guarded route is a 405, not a 403 and not a 404")
        void thewrongVerbIsStill405() throws IOException {
            Response response = post("/api/access/whoami", "reader.only", "");

            assertThat(response.status())
                .as("a method error is not an authorisation error; conflating them sends an"
                    + " integrator looking in the wrong place")
                .isEqualTo(405);
        }

        @Test
        @DisplayName("an absent action is a 400: nothing was asked, so nothing was refused")
        void anabsentActionIsABadRequest() throws IOException {
            Response response = get("/api/access/decisions", "batch.operator");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("bad request").contains("action is absent");
        }
    }

    @Nested
    @DisplayName("segregation of duties, over the wire (07 § 7)")
    class SegregationOfDuties {

        @Test
        @DisplayName("the identity that started the run may not approve closing over its exceptions")
        void therunMakerMayNotApproveOverItsOwnRun() throws IOException {
            Response response = get(
                "/api/access/decisions?action=APPROVE_EXCEPTION_ACCEPTANCE"
                    + "&runStartedBy=ops.superuser", "ops.superuser");

            assertThat(response.status())
                .as("this is the hole FR-906 left open: the maker-checker identity on a close was"
                    + " whatever the caller typed")
                .isEqualTo(403);
            assertThat(response.body())
                .contains("SEGREGATION_OF_DUTIES")
                .contains("\"segregationBreach\":true")
                .contains("started the run");
            assertThat(response.body())
                .as("the refusal must not read as a missing grant, or it gets closed by widening"
                    + " the grant — which is the control being removed by the ticket reporting it")
                .contains("This is NOT a missing grant")
                .doesNotContain("ROLE_LACKS_CAPABILITY");
        }

        @Test
        @DisplayName("the same breach under a different capitalisation is still a 403")
        void caseDoesNotDefeatTheComparison() throws IOException {
            // The run record says "Ops.Superuser"; the caller asserts "ops.superuser". A comparison
            // by equals — the fourth spelling this codebase found, the one guarding the routing
            // table — would permit this. FourEyes.sameIdentity does not.
            Response response = get(
                "/api/access/decisions?action=APPROVE_EXCEPTION_ACCEPTANCE"
                    + "&runStartedBy=Ops.Superuser", "ops.superuser");

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body()).contains("SEGREGATION_OF_DUTIES");
        }

        @Test
        @DisplayName("a second person approving the same run's exceptions is a 200")
        void asecondPersonMayApprove() throws IOException {
            Response response = get(
                "/api/access/decisions?action=APPROVE_EXCEPTION_ACCEPTANCE"
                    + "&runStartedBy=batch.operator", "policy.approver");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"permitted\":true")
                .contains("\"segregationEvaluated\":true");
        }

        @Test
        @DisplayName("a permit with no run named says the check was skipped, not cleared")
        void askippedCheckIsReportedAsSkipped() throws IOException {
            Response response = get(
                "/api/access/decisions?action=APPROVE_EXCEPTION_ACCEPTANCE", "policy.approver");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("an invariant nobody evaluated reads exactly like one that passed")
                .contains("\"segregationEvaluated\":false")
                .contains("SEGREGATION NOT EVALUATED");
        }

        @Test
        @DisplayName("the composed grant is reported on the operator's own whoami")
        void thegrantIsReportedToItsHolder() throws IOException {
            Response response = get("/api/access/whoami", "ops.superuser");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"segregationExceptionStands\":true")
                .contains("APPROVE_EXCEPTION_ACCEPTANCE checks START_RUN");
        }
    }

    @Nested
    @DisplayName("the automation mandate at the edge (07 § 5)")
    class NoOverrideExists {

        @Test
        @DisplayName("an override is refused as an act that does not exist")
        void anoverrideIsRefusedAsNonexistent() throws IOException {
            // Attempted by the identity holding the most in the register. "You lack the capability"
            // would say somebody could hold it.
            Response response = get("/api/access/decisions?action=OVERRIDE_RATE", "ops.superuser");

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.body())
                .contains("NO_SUCH_CAPABILITY")
                .contains("not an act this engine has")
                .contains("07 § 5");
        }

        @Test
        @DisplayName("the role listing says no capability can override a computed figure")
        void therolesListingStatesTheMandate() throws IOException {
            Response response = get("/api/access/roles", "reader.only");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("no manual rate override")
                .doesNotContain("OVERRIDE_RATE");
        }
    }

    @Nested
    @DisplayName("the role model is legible: who am I, and what may I do")
    class Legibility {

        @Test
        @DisplayName("whoami names the identity, its roles and every capability answer")
        void whoamiIsComplete() throws IOException {
            Response response = get("/api/access/whoami", "financial.controller");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"identity\":\"financial.controller\"")
                .contains("FINANCIAL_CONTROLLER")
                // 02 § 3.1: triggers the run, works the queue, and locks the period...
                .contains("START_RUN")
                .contains("CLOSE_PERIOD")
                .contains("PROPOSE_EXCEPTION_ACCEPTANCE")
                // ...and 07 § 4.2: but does not sign for its own acceptance.
                .contains("\"capability\":\"APPROVE_EXCEPTION_ACCEPTANCE\",\"permitted\":false");
        }

        @Test
        @DisplayName("the asserted identity is echoed as sent, not as the register spells it")
        void theassertedIdentityIsEchoedVerbatim() throws IOException {
            Response response = get("/api/access/whoami", "READER.ONLY");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"assertedIdentity\":\"READER.ONLY\"")
                .contains("\"identity\":\"reader.only\"");
        }

        @Test
        @DisplayName("the roles listing carries the whole model and the standing exceptions")
        void therolesListingIsComplete() throws IOException {
            Response response = get("/api/access/roles", "reader.only");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("AUDITOR").contains("BATCH_OPERATOR")
                .contains("FINANCIAL_CONTROLLER").contains("PRODUCT_CONTROL").contains("APPROVER")
                .contains("\"identities\":6")
                .contains("\"segregationExceptionCount\":1")
                .contains("ops.superuser");
        }

        @Test
        @DisplayName("every response says the identity is asserted and not authenticated")
        void everyResponseCarriesTheCaveat() throws IOException {
            // The caveat is the point. A header-asserted identity presented as authentication would
            // be worse than the honest absence it replaces, so it is on the responses themselves
            // rather than only in the README.
            for (String path : new String[] {
                "/api/access/roles", "/api/access/whoami", "/api/access/coverage",
                "/api/access/decisions?action=READ_FIGURES"}) {
                assertThat(get(path, "reader.only").body())
                    .as("%s must say the identity is asserted by the caller", path)
                    .contains("ASSERTED BY THE CALLER")
                    .contains("terminates TLS 1.3 and authenticates upstream");
            }
            assertThat(get("/api/access/whoami", null).body())
                .as("a refusal must carry it too, or a 403 reads as a failed authentication")
                .contains("ASSERTED BY THE CALLER");
        }
    }

    @Nested
    @DisplayName("the coverage report, which publishes what is NOT guarded")
    class Coverage {

        @Test
        @DisplayName("the four routes this module registers are reported as enforced")
        void thefourGuardedRoutesAreReported() throws IOException {
            Response response = get("/api/access/coverage", "reader.only");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"enforcedRoutes\":4")
                .contains("\"route\":\"GET /api/access/whoami\",\"requires\":\"READ_FIGURES\","
                    + "\"enforced\":true");
        }

        @Test
        @DisplayName("every console route is reported unguarded, with the capability it needs")
        void theconsoleRoutesAreReportedUnguarded() throws IOException {
            Response response = get("/api/access/coverage", "reader.only");

            // EirServer.routes() lists nine; all nine are unguarded because EirServer registers
            // them directly and every mutating one is a POST.
            assertThat(response.body()).contains("\"unenforcedRoutes\":9");
            assertThat(response.body())
                .contains("\"route\":\"POST /api/run\",\"requires\":\"START_RUN\","
                    + "\"enforced\":false")
                .contains("\"route\":\"POST /api/close\",\"requires\":\"CLOSE_PERIOD\","
                    + "\"enforced\":false");
            assertThat(response.body())
                .as("the acceptance route carries both signatures in one call, which is why it is"
                    + " the most under-guarded route in the API")
                .contains("PROPOSE_EXCEPTION_ACCEPTANCE + APPROVE_EXCEPTION_ACCEPTANCE");
            assertThat(response.body())
                .as("the three routes no capability in this model covers are named, not guessed at")
                .contains("UNMAPPED");
            assertThat(response.body())
                .contains("cannot read a request header")
                .contains("hooksNeeded");
        }

        @Test
        @DisplayName("the report is itself guarded, so the gap is not published to anonymous")
        void thereportIsGuarded() throws IOException {
            assertThat(get("/api/access/coverage", null).status()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("the gap, pinned so it cannot be forgotten")
    class TheUnguardedRoutes {

        @Test
        @DisplayName("POST /api/run is NOT guarded: a read-only identity still starts a run")
        void therunRouteIsUnguarded() throws IOException {
            // This test asserts the state of the world rather than the state we want, and it is here
            // deliberately. EirServer registers /api/run directly and is owned elsewhere; a
            // half-applied filter that guarded some routes and silently missed others would be
            // worse than none, so the miss is written down as an executable fact. When the hook in
            // EirServer.answer and the exchange on Routes.post land, THIS test must be changed to
            // expect 403 — and it failing is the signal that the work is done.
            Response response = post("/api/run", "reader.only", "");

            assertThat(response.status())
                .as("still 200: FR-906's enforcement does not reach EirServer's own registrations."
                    + " See GET /api/access/coverage.")
                .isEqualTo(200);
        }

        @Test
        @DisplayName("POST /api/accept still takes both identities from the caller's own body")
        void theacceptRouteStillTrustsTheCaller() throws IOException {
            post("/api/run", null, "");
            Response response = post("/api/accept", "reader.only",
                "acceptedBy=ops.lead&approvedBy=someone.else&reason=demonstration");

            assertThat(response.status())
                .as("the acceptance's two signatures are still whatever the caller typed; the role"
                    + " model exists and this route does not consult it")
                .isEqualTo(200);
            assertThat(response.body()).contains("\"approvedBy\":\"someone.else\"");
        }
    }
}
