package com.crisil.eir.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The server, driven over real HTTP on a real socket.
 *
 * <p><b>Why over a socket and not by calling the service directly.</b> Every defect this layer can
 * introduce lives between the two: a response body written before the status line, a figure emitted
 * as a JSON number and rounded by the reader, an engine refusal mapped onto a 500 so the UI shows a
 * stack trace where a control report belongs. A test that calls {@code EirService} misses all four.
 * Port 0 lets the OS pick, so this runs anywhere and never collides with a developer's own server.
 *
 * <p>Assertions are on substrings of the JSON rather than on a parsed tree, because this module has
 * no JSON parser — writing one for the test would be the largest untested thing in the module. The
 * substrings are chosen to be ones a formatting change cannot accidentally satisfy: a specific
 * rupee figure, a specific invariant id, a specific refusal phrase.
 */
class EirServerTest {

    private EirServer server;
    private String base;

    @BeforeEach
    void startOnAnEphemeralPort() throws IOException {
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

    private Response get(String path) throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        return read(connection);
    }

    private Response post(String path, String form) throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        connection.setRequestMethod("POST");
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
    @DisplayName("the four stages, over HTTP, in the order an operator drives them")
    class TheCycle {

        @Test
        @DisplayName("the page is served, and it is the page and not a JSON error")
        void thePageIsServed() throws IOException {
            Response response = get("/");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("the operator page must be on the classpath, or the tool has no front door")
                .contains("<title>")
                .contains("/api/run");
        }

        @Test
        @DisplayName("the book reports three contracts, with case 1's figures intact")
        void theBookIsServed() throws IOException {
            Response response = get("/api/book");

            assertThat(response.status()).isEqualTo(200);
            // Reference case 1's own figures, as strings. If either appears with a trailing digit
            // lost or a 999999 tail, the figure went through a double somewhere in this layer.
            assertThat(response.body())
                .contains("\"openingGca\":\"528407.32\"")
                .contains("\"eir\":\"0.010421491800\"")
                .contains("\"contracts\":3")
                .contains("STAGE_3");
        }

        @Test
        @DisplayName("a run isolates the stateless contract and accounts for all three")
        void theRunAccountsForThePopulation() throws IOException {
            Response response = post("/api/run", "");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"populationSize\":3")
                .contains("\"computed\":2")
                .contains("\"quarantined\":1")
                .contains("\"unaccountedFor\":0");
            // C-0001's closing balance, by hand: 528,407.32 + 5,506.79 − 47,073.47 = 486,840.64.
            assertThat(response.body()).contains("486840.64");
            // FR-905 working: the run did not abandon the other two.
            assertThat(response.body()).contains("C-0003");
            // The steady state does not solve. A run that solved every period would still produce
            // these figures, so the count is the only place the defect is visible.
            assertThat(response.body()).contains("\"solves\":0");
        }

        @Test
        @DisplayName("a close refuses while a contract is quarantined, and says every reason")
        void theCloseRefusesOnAnUnworkedException() throws IOException {
            post("/api/run", "");
            Response response = post("/api/close", "closedBy=financial.controller");

            assertThat(response.status())
                .as("a refusal is an answer, not a client error — it comes back on a 200")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"mayClose\":false")
                .contains("\"gateRefused\":true");
            // Both halves report: the run's own population arithmetic and the gate's queue leg.
            assertThat(response.body()).contains("runRefusals");
            assertThat(response.body()).contains("quarantined");
            // The three controls this whole layer exists to give a caller.
            assertThat(response.body())
                .contains("SL-2").contains("SL-1").contains("RC-1");
        }

        @Test
        @DisplayName("a replay of the run reproduces, and reports coverage rather than just DT-1")
        void theReplayReproduces() throws IOException {
            post("/api/run", "");
            Response response = post("/api/replay", "");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"dtOneSatisfied\":true")
                .contains("\"coverage\":\"FIGURES_COMPARED\"")
                .contains("\"provesReproduction\":true");
        }

        @Test
        @DisplayName("a close before any run says so, instead of reporting a clean nothing")
        void aCloseWithNoRunRefuses() throws IOException {
            Response response = post("/api/close", "");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"ran\":false")
                .contains("a close reads the figures a run published");
        }
    }

    @Nested
    @DisplayName("initial recognition, and the two refusals worth seeing")
    class Onboarding {

        @Test
        @DisplayName("a passing contract is recognised and gets a solved rate")
        void aPassingContractIsRecognised() throws IOException {
            Response response = post("/api/onboard",
                "contractId=C-0100&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeCode=PROC_FEE&feeAmount=15000.00");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"recognised\":true")
                .contains("\"measuredAt\":\"AMORTISED_COST\"");
            // All five steps ran, in order, because none of them refused.
            assertThat(response.body())
                .contains("MEASUREMENT_GATE").contains("FEE_CLASSIFICATION")
                .contains("TIER_ASSIGNMENT").contains("PROJECTION").contains("SOLVE");
        }

        @Test
        @DisplayName("a Tier 3 proposal with no equivalence test on file is demoted, and says so")
        void aTierThreeProposalWithNoTestIsDemotedAndReported() throws IOException {
            // FR-411/FR-412 through HTTP, and the defect this pins is the one a review found in
            // this very method: EirService called InitialRecognition.onboard(), which returns the
            // disposition alone and DROPS the TierPermission. So the gate ran, NO_TEST_ON_FILE
            // demoted the contract to Tier 2, the solve used Tier 2's tolerance -- and the response
            // said nothing at all about a tier, leaving a caller to assume the Tier 3 shortcut it
            // had asked for. That is the Cambodia failure mode arriving through a dropped return
            // value rather than through a decision, and Case 9 measures the cost of the shortcut
            // taken without a test at 81.0% year-one income overstatement on a zero-coupon.
            //
            // Twelve periods, so 03 § 10's short-tenor row proposes Tier 3. The service's register
            // is EquivalenceTestGate.empty() -- a real register with nothing in it -- so no test is
            // on file and the only lawful answer is a demotion with an exception raised.
            Response response = post("/api/onboard",
                "contractId=C-0110&principal=2000000.00&rate=0.010000000000"
                    + "&termPeriods=12&feeCode=PROC_FEE&feeAmount=20000.00");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("the proposal and the measurement are different facts and both must be on the"
                    + " response: reporting only the effective tier loses which § 10 row proposed"
                    + " Tier 3, which is what a Board reviewing the refusals needs")
                .contains("\"tierProposed\":\"TIER_3\"")
                .contains("\"tierMeasured\":\"TIER_2\"")
                .contains("\"tierDemoted\":true")
                .contains("\"tierGateConsulted\":true");
            assertThat(response.body())
                .as("a demotion that raised no exception is a control nobody has to clear, and it"
                    + " must block the close: 03 § 10.2's consequence is a more expensive basis,"
                    + " not a stop, so nothing else would ever surface it")
                .contains("\"category\":\"STALE_EQUIVALENCE_TEST\"")
                .contains("no equivalence test on file")
                .contains("\"blocksClose\":true");
            assertThat(response.body())
                .as("the population the permission was judged at, which is productId:segment per"
                    + " the recorded decision -- a caller cannot obtain the test without it")
                .contains("\"equivalenceTestPopulation\":\"HL:RETAIL\"");
            assertThat(response.body())
                .as("TG-1 lives on the permission, not on the outcome, so a response reading the"
                    + " outcome's invariant list showed a clean panel for a refused permission")
                .contains("\"id\":\"TG-1\"")
                .contains("\"satisfied\":false");
        }

        @Test
        @DisplayName("a Tier 1 contract consults no gate, and TG-1 is absent rather than passing")
        void aTierOneContractHasNoTierGateResult() throws IOException {
            // The other half, and it is not symmetry for its own sake. TG-1 is a claim about Tier 3
            // populations; publishing a vacuous pass for every Tier 1 and Tier 2 contract would put
            // a green TG-1 on the overwhelming majority of the book and make a real one unfindable
            // in a control report. Twenty-four periods, so the short-tenor row does not fire.
            Response response = post("/api/onboard",
                "contractId=C-0111&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeCode=PROC_FEE&feeAmount=15000.00");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"tierDemoted\":false")
                .contains("\"tierGateConsulted\":false")
                .doesNotContain("STALE_EQUIVALENCE_TEST")
                .doesNotContain("TG-1");
            assertThat(response.body())
                .as("and the proposal is measured, which is the point of not demoting it")
                .contains("\"recognised\":true");
        }

        @Test
        @DisplayName("an SPPI failure is excluded, and nothing below the gate runs")
        void anSppiFailureStopsAtTheGate() throws IOException {
            Response response = post("/api/onboard",
                "contractId=C-0101&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeAmount=15000.00&sppi=fail");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"recognised\":false")
                .contains("\"eirApplies\":false")
                .contains("\"measuredAt\":\"FVTPL\"");
            // The claim that matters is about COST, not about the answer: 05 § 3.1 calls a pipeline
            // that computes everything and then discards it "waste, and worse".
            assertThat(response.body())
                .contains("\"expensiveWorkPerformed\":false")
                .doesNotContain("\"SOLVE\"")
                .doesNotContain("\"PROJECTION\"");
        }

        @Test
        @DisplayName("an unmapped fee code is quarantined, never defaulted")
        void anUnmappedFeeCodeRefuses() throws IOException {
            Response response = post("/api/onboard",
                "contractId=C-0102&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeCode=MYSTERY_FEE&feeAmount=15000.00");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"recognised\":false")
                .contains("MYSTERY_FEE");
            // The rate was never solved, because the classification was never resolved.
            assertThat(response.body()).doesNotContain("\"SOLVE\"");
        }

        @Test
        @DisplayName("a recognised contract joins the book and shows up in the next run")
        void aRecognisedContractJoinsTheBook() throws IOException {
            post("/api/onboard", "contractId=C-0103&principal=500000.00&rate=0.009000000000"
                + "&termPeriods=12&feeCode=PROC_FEE&feeAmount=5000.00");

            assertThat(get("/api/book").body()).contains("C-0103");
            assertThat(post("/api/run", "").body())
                .as("four contracts now, and the population accounting has to say so")
                .contains("\"populationSize\":4");
        }
    }

    @Nested
    @DisplayName("the edge does not destroy the distinction the engine is built on")
    class StatusCodes {

        @Test
        @DisplayName("a malformed request is a 400, and names the field")
        void aMalformedRequestIsABadRequest() throws IOException {
            Response response = post("/api/onboard", "contractId=C-0104&principal=not-a-number");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("bad request")
                .contains("principal");
        }

        @Test
        @DisplayName("a missing required field is a 400 rather than a defaulted zero")
        void aMissingFieldIsRefused() throws IOException {
            Response response = post("/api/onboard", "principal=1000.00");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("contractId").contains("absent");
        }

        @Test
        @DisplayName("the wrong verb is a 405, not a silent 404")
        void theWrongVerbIsRefused() throws IOException {
            Response response = get("/api/run");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("POST only");
        }

        @Test
        @DisplayName("an unknown route is a 404 that names the path")
        void anUnknownRouteIsANotFound() throws IOException {
            Response response = get("/nope");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).contains("/nope");
        }
    }
}
