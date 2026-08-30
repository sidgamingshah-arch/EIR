package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.modules.runs.JsonView;
import com.crisil.eir.api.store.Seed;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 06 § 4 over real HTTP, on a real socket — and the 409 above all.
 *
 * <p><b>Over a socket, not against the module, because the status code is the thing under test.</b>
 * The whole point of this unit is that {@code POST /periods/{id}/close} answers 409 where a gate
 * failed and 200 where it did not, and a test that called the handler and inspected the returned
 * {@code Answer} would pass just as happily if nothing ever put that number on a status line. It
 * would also miss the two defects this layer can actually introduce: a response written before the
 * status, and a body sent under a code the client branches on the other way. Port 0 lets the OS pick,
 * so this runs anywhere and never collides with a developer's own server.
 *
 * <p>Assertions are on substrings of the JSON, following {@code EirServerTest}: this module has no
 * JSON parser and writing one for the test would be the largest untested thing in it. The substrings
 * are chosen so a formatting change cannot accidentally satisfy them — a named refusal enum, a
 * specific rupee figure, a specific invariant id, a count in the gate's own sentence.
 *
 * <p><b>Every expected figure below is derived by hand from reference case 1 and the seed book, and
 * the derivation is stated where it is used.</b> Nothing here was read off a run.
 */
class RunsAndPeriodsModuleTest {

    /** Reference case 1's month 13, by hand: 528,407.32 + 5,506.79 − 47,073.47 = 486,840.64. */
    private static final String CASE_ONE_CLOSING_GCA = "486840.64";

    /** {@code EirService}'s own documented defaults for a close nobody attested. */
    private static final String CLOSED_BY = "financial.controller";
    private static final String CLOSED_AT = "2028-06-05T09:00:00Z";

    /**
     * {@code EirService.BOUNDARY}'s {@code recordedAsAt}: the system-time horizon the book's figures
     * were known at, and therefore the {@code recorded_at} boundary a replay of the closed period
     * must read as at (04 § 5). It is published on {@code GET /api/book} as {@code recordedAsAt}.
     */
    private static final String VERSION_CUTOFF = "2028-06-01T00:00:00Z";

    private EirServer server;
    private String base;
    private int port;

    @BeforeEach
    void startOnAnEphemeralPort() throws IOException {
        server = new EirServer(0, new EirService(Seed.book()));
        server.start();
        port = server.port();
        base = "http://localhost:" + port;
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

    /**
     * One request over a bare socket, for a verb {@code HttpURLConnection} refuses to send.
     *
     * <p>Needed for exactly one assertion and it is an important one: {@code PATCH} is the verb an
     * integrator reaches for to reopen a period, {@code HttpURLConnection} throws
     * {@code ProtocolException} rather than send it, and "the client library would not let me" is not
     * evidence that the server refuses.
     */
    private int statusOfRaw(String method, String path) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            assertThat(statusLine).as("the server answered nothing at all").isNotNull();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private static String runOf(int sequence) {
        return "/api/runs/API-RUN-" + Seed.PERIOD_ID + "-0" + sequence;
    }

    private static final String RUNS = "/api/runs";
    private static final String PERIOD = "/api/periods/" + Seed.PERIOD_ID;
    private static final String START = "periodId=" + Seed.PERIOD_ID + "&bookId=MAIN";

    // ==================================================================== runs

    @Nested
    @DisplayName("POST /api/runs, GET /api/runs/{id}, POST /api/runs/{id}/replay")
    class TheRunResource {

        @Test
        @DisplayName("a run is started for {periodId, bookId} and accounts for the whole population")
        void aRunIsStartedAndAccountsForThePopulation() throws IOException {
            Response response = post(RUNS, START);

            assertThat(response.status())
                .as("06 § 4 says 202; this engine walks the population inline, so the run is"
                    + " finished before the response is written and 202 would tell a caller to poll"
                    + " for something that already happened")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"started\":true")
                .contains("\"runId\":\"API-RUN-" + Seed.PERIOD_ID + "-01\"")
                .contains("\"status\":\"COMPLETED\"");
            // The seed book is three contracts: C-0001 performing, C-0002 Stage 3 with recognition
            // suppressed, C-0003 with period movements and no opening state. The third cannot be
            // rolled forward from a balance nobody recorded, so it is isolated (FR-905) — two
            // computed, one quarantined, and NOTHING unaccounted for, which is the count that
            // catches a run that dropped a contract and reconciled perfectly without it.
            assertThat(response.body())
                .contains("\"populationSize\":3")
                .contains("\"contractsProcessed\":2")
                .contains("\"quarantined\":1")
                .contains("\"unaccountedFor\":0")
                .contains("\"exceptionCount\":1");
            // Reference case 1's month 13 closing gross carrying amount, as a string. A trailing
            // digit lost or a 99999 tail means a figure went through a double in this layer.
            assertThat(response.body()).contains(CASE_ONE_CLOSING_GCA);
        }

        @Test
        @DisplayName("the period id and the book id are required, and neither is defaulted")
        void bothIdentifiersAreRequired() throws IOException {
            assertThat(post(RUNS, "bookId=MAIN").status())
                .as("a missing period id is not 'the only period on file'; defaulting it would run"
                    + " May for a caller who thought they were running June")
                .isEqualTo(400);
            assertThat(post(RUNS, "bookId=MAIN").body()).contains("periodId").contains("absent");

            Response notANumber = post(RUNS, "periodId=last-month&bookId=MAIN");
            assertThat(notANumber.status()).isEqualTo(400);
            assertThat(notANumber.body()).contains("whole number").contains("last-month");
        }

        @Test
        @DisplayName("an unknown period and an unknown book are refused as values, on a 200")
        void anUnknownSubjectIsRefusedAsAValue() throws IOException {
            Response wrongPeriod = post(RUNS, "periodId=202806&bookId=MAIN");

            assertThat(wrongPeriod.status())
                .as("06 § 4 gives one endpoint a status code and this is not it; a refusal is a"
                    + " value here and the caller needs the reason, not a code")
                .isEqualTo(200);
            assertThat(wrongPeriod.body())
                .contains("\"started\":false")
                .contains("no period 202806")
                .contains("202805");

            Response wrongBook = post(RUNS, "periodId=" + Seed.PERIOD_ID + "&bookId=RETAIL");
            assertThat(wrongBook.status()).isEqualTo(200);
            assertThat(wrongBook.body())
                .contains("\"started\":false")
                .contains("no book 'RETAIL'");
        }

        @Test
        @DisplayName("the run resource reports the same run, and its invariant results")
        void theRunResourceReportsTheRun() throws IOException {
            post(RUNS, START);
            Response response = get(runOf(1));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"contractsProcessed\":2")
                .contains("\"exceptionCount\":1")
                .contains("\"latestOfThisResource\":true");
            // Three distinct invariant ids, and the derivation is the pipeline's: SL-2 (journals
            // balance) and ST-2 (suppressed recognition against the ledger) are published for every
            // contract that computes, and S3-1 (the Stage 3 four-way) by the one whose recognition
            // was suppressed — C-0002. RunAggregate keeps one row per id, so three.
            assertThat(response.body()).contains("\"invariantResults\":3");
            assertThat(response.body()).contains("SL-2").contains("ST-2").contains("S3-1");
            // NONE of them is red, and the period still cannot close. That is the trap this whole
            // engine is arranged against: C-0003 published no invariant at all because it never
            // computed, so a green dashboard says nothing about it. reportsCleanClose is the field
            // that knows, and it is false.
            assertThat(response.body())
                .as("a green invariant set over a population that lost a contract is not a"
                    + " closeable run")
                .contains("\"invariantBreaches\":0")
                .contains("\"reportsCleanClose\":false");
            // One partition, complete, and it says so rather than implying a progress model.
            assertThat(response.body())
                .contains("\"partitions\":1")
                .contains("\"partitionId\":\"MAIN/" + Seed.PERIOD_ID + "\"")
                .contains("\"status\":\"COMPLETE\"");
        }

        @Test
        @DisplayName("a run this deployment never started is a 404 that says what it does have")
        void anUnknownRunIsNotFound() throws IOException {
            post(RUNS, START);
            Response response = get("/api/runs/RUN-202804-01");

            assertThat(response.status())
                .as("an absence, not a refusal: there is nothing for a gate to weigh")
                .isEqualTo(404);
            assertThat(response.body())
                .contains("RUN-202804-01")
                .contains("RUN-" + Seed.PERIOD_ID + "-01");
        }

        @Test
        @DisplayName("the wrong verb on a run is a 405 that names the one write there is")
        void theWrongVerbOnARunIsRefused() throws IOException {
            post(RUNS, START);

            Response wrongVerbOnTheRun = post(runOf(1), "");
            assertThat(wrongVerbOnTheRun.status()).isEqualTo(405);
            assertThat(wrongVerbOnTheRun.body()).contains("GET only").contains("replay");

            Response wrongVerbOnTheCollection = get(RUNS);
            assertThat(wrongVerbOnTheCollection.status()).isEqualTo(405);
            assertThat(wrongVerbOnTheCollection.body()).contains("POST only");
        }

        @Test
        @DisplayName("a replay reports DT-1, its coverage, and which field to read")
        void aReplayReportsTheByteComparison() throws IOException {
            post(RUNS, START);
            Response response = post(runOf(1) + "/replay", "");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"replayed\":true")
                .contains("\"dtOneSatisfied\":true")
                .contains("\"coverage\":\"FIGURES_COMPARED\"")
                .contains("\"provesReproduction\":true")
                // DT-1 passes over a period that published nothing, correctly, because nothing
                // failed. Coverage is what says the comparison had anything in it.
                .contains("\"readThisOne\":\"provesReproduction\"");
            assertThat(response.body()).contains("\"shadowRunId\":\"SHADOW-API-RUN-"
                + Seed.PERIOD_ID + "-01\"");
        }

        @Test
        @DisplayName("a replay of a superseded run is refused, not answered with the latest run's DT-1")
        void aSupersededRunIsNotReplayedAsAnother() throws IOException {
            post(RUNS, START);
            post("/api/repair", "");
            post(RUNS, START);

            Response response = post(runOf(1) + "/replay", "");

            // The failing input, named: two runs of one period, and a replay asked about the first.
            // The engine holds the LATEST run's working papers, so it would happily replay run 02
            // and this endpoint would publish its byte comparison under run 01's id — a
            // reproduction claim about figures nothing compared. That is the only way a DT-1
            // control fails silently, so it is refused rather than answered.
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"replayed\":false")
                .contains("\"latestRunId\":\"API-RUN-" + Seed.PERIOD_ID + "-02\"")
                .contains("is not the latest run for its period");
            assertThat(response.body())
                .as("no byte comparison may be reported for a run that was not compared")
                .doesNotContain("\"dtOneSatisfied\"");
        }

        @Test
        @DisplayName("a re-run gets its own id, so two runs of one period stay distinguishable")
        void aRerunGetsItsOwnId() throws IOException {
            post(RUNS, START);
            Response second = post(RUNS, START);

            assertThat(second.body()).contains("\"runId\":\"API-RUN-" + Seed.PERIOD_ID + "-02\"");
            assertThat(get(runOf(1)).status()).isEqualTo(200);
            assertThat(get(runOf(2)).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("the resource assigns the run id and refuses to be handed one")
        void theResourceAssignsTheRunId() throws IOException {
            // The failing input, measured: runId=RUN-202805-01 — which is exactly what the console's
            // own POST /api/run mints when nobody names a run. With that id in this register, a
            // console run published under the same id, and the replay's replayOf check could not
            // tell the two runs apart: it reported dtOneSatisfied for a comparison of a run with
            // three computed contracts under the id of a run that had two. 06 § 4's request is
            // {periodId, bookId} and the id comes back in the response, so there is nothing to
            // accept here.
            Response supplied = post(RUNS, START + "&runId=RUN-" + Seed.PERIOD_ID + "-01");

            assertThat(supplied.status()).isEqualTo(200);
            assertThat(supplied.body())
                .contains("\"started\":false")
                .contains("this resource assigns run ids and does not accept one");
            assertThat(get(PERIOD).body())
                .as("and no run was started, so nothing is on file under a colliding id")
                .contains("\"runs\":[]");

            // The ids it does mint are in a namespace the console cannot produce.
            assertThat(post(RUNS, START).body())
                .contains("\"runId\":\"API-RUN-" + Seed.PERIOD_ID + "-01\"");
        }

        @Test
        @DisplayName("a console run cannot masquerade as this resource's run in a replay")
        void aConsoleRunCannotMasqueradeInAReplay() throws IOException {
            // The whole scenario, measured: start a run here (two computed, one quarantined), repair
            // C-0003, then run the CONSOLE's own POST /api/run, which replaces the engine's working
            // papers with a run of three computed contracts under an id this resource never saw.
            post(RUNS, START);
            post("/api/repair", "");
            post("/api/run", "");

            Response replay = post(runOf(1) + "/replay", "");

            // The register still holds run 01 and it is still this period's latest here, so the
            // first guard passes. The second one — the engine's own replayOf against the id asked
            // for — is what refuses, and it must, because the byte comparison the engine just
            // performed is of a different run's figures.
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.body())
                .contains("\"replayed\":false")
                .contains("\"engineReplayed\":\"RUN-" + Seed.PERIOD_ID + "-01\"")
                .contains("published a run this resource never saw");
            assertThat(replay.body())
                .as("no DT-1 may be reported under the id of a run that was not the one compared")
                .doesNotContain("\"dtOneSatisfied\"");

            // And the run resource does not claim to be the engine's current run either.
            assertThat(get(runOf(1)).body())
                .contains("\"latestOfThisResource\":true")
                .doesNotContain("\"replayable\"");
        }
    }

    // ================================================================= periods

    @Nested
    @DisplayName("GET /api/periods and GET /api/periods/{id}")
    class ThePeriodResource {

        @Test
        @DisplayName("the period is OPEN, and says which statuses it may move to")
        void thePeriodIsOpen() throws IOException {
            Response response = get("/api/periods");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"count\":1")
                .contains("\"periodId\":" + Seed.PERIOD_ID)
                .contains("\"status\":\"OPEN\"")
                .contains("\"closed\":false")
                // OPEN is not closeable: the gate runs from CLOSING, because an OPEN period's
                // figures can still move underneath the invariant results being read.
                .contains("\"closeable\":false")
                .contains("\"legalSuccessors\":[\"CLOSING\"]");
            // ck_accounting_period_id_matches_dates: 202805 encodes May, so the row starts on the
            // 1st — NOT on Seed.PERIOD_START (2028-04-30), which is the flow index date.
            assertThat(response.body())
                .contains("\"periodStart\":\"2028-05-01\"")
                .contains("\"periodEnd\":\"2028-05-31\"");
        }

        @Test
        @DisplayName("the period lists the runs made against it")
        void thePeriodListsItsRuns() throws IOException {
            post(RUNS, START);
            post(RUNS, START);

            assertThat(get(PERIOD).body())
                .contains("\"runs\":[\"API-RUN-" + Seed.PERIOD_ID + "-01\",\"API-RUN-"
                    + Seed.PERIOD_ID + "-02\"]");
        }

        @Test
        @DisplayName("a period the engine does not have is a 404; a period id that is not one is a 400")
        void anUnknownPeriodAndAMalformedOne() throws IOException {
            Response unknown = get("/api/periods/202806");
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.body()).contains("no period 202806").contains("202805");

            Response malformed = get("/api/periods/last-month");
            assertThat(malformed.status())
                .as("not a period that happens not to exist — a request that is not about a period")
                .isEqualTo(400);
            assertThat(malformed.body()).contains("YYYYMM").contains("last-month");
        }
    }

    // =================================================================== the close

    @Nested
    @DisplayName("POST /api/periods/{id}/close — the 409, and the arc through it")
    class TheClose {

        @Test
        @DisplayName("a refused close is a 409 carrying BOTH refusal lists, whole")
        void aRefusedCloseIsAConflictWithEveryReason() throws IOException {
            post(RUNS, START);
            Response response = post(PERIOD + "/close", "");

            assertThat(response.status())
                .as("06 § 4: the 409 'is the intended behaviour, not an error path to route"
                    + " around'. A scheduler branches on this line, and a 200 saying mayClose:false"
                    + " is a period believed closed that is not.")
                .isEqualTo(409);
            assertThat(response.body())
                .contains("\"mayClose\":false")
                .contains("\"gateAsked\":true");

            // List one — the GATE's refusals, one to a line, enumerated. Five of them, and the
            // derivation is the state of the book: nothing has been posted to the GL, so SL-1 is
            // red and its tie does not tie; C-0003 was billed by the CBS and never projected, so
            // RC-1 is red and its tie does not tie; and C-0003's exception is neither cleared nor
            // accepted. Two INVARIANT_BREACH, two RECONCILIATION_NOT_TIED, one exception refusal.
            assertThat(response.body())
                .contains("\"gateRefused\":true")
                .contains("\"gateRefusalCount\":5")
                .contains("CLOSE REFUSED by 5 gate(s)")
                .contains("INVARIANT_BREACH")
                .contains("EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED")
                .contains("RECONCILIATION_NOT_TIED")
                .contains("SL_1")
                .contains("RC_1");

            // List two — the RUN's own refusals, which the gate cannot know: it weighs the evidence
            // it was given and cannot know the evidence covers nothing. Sourced from
            // RunAggregate.blockingReasons and passed through untouched.
            assertThat(response.body())
                .as("both lists come back and neither is collapsed into the other")
                .contains("\"runRefused\":true")
                .contains("\"runRefusals\":[\"1 contract(s) were quarantined")
                .contains("C-0003");

            // And the body says which of the two lists is the reason, because either alone can
            // refuse a close: mayClose() is runRefusals.isEmpty() && !decision.isRefused(). A
            // client that read only the gate's count would see 0 on a 409 where the gate permitted
            // and the run did not — the case RunClose documents for an empty population.
            assertThat(response.body())
                .contains("\"refusalSources\":[\"the close gate")
                .contains("the run's own verdict — close.runRefusals");

            // And the size of the problem, not just its existence: SL-1 is out by 36,059.88 and
            // RC-1 by 5,298.16 — C-0002's billed interest, the contract the CBS billed and the
            // engine never projected. 36,059.88 + 5,298.16 = 41,358.04.
            assertThat(response.body())
                .contains("\"totalDeviation\":\"41358.04\"");
        }

        @Test
        @DisplayName("a refused close leaves the period OPEN, with the attempt on the record")
        void aRefusedCloseReopensThePeriod() throws IOException {
            post(RUNS, START);
            post(PERIOD + "/close", "");

            Response period = get(PERIOD);
            // CLOSING → OPEN is a legal edge on purpose: the fix for a red step 4 is upstream, in
            // data the period has to reopen to receive — which is exactly what the repair below
            // posts. The attempt is not lost with the status, because abandonClose clears
            // closingStartedAt and a period that reads like one nobody tried to close is not an
            // answer to an operator asking why the month is open.
            assertThat(period.body())
                .contains("\"status\":\"OPEN\"")
                .contains("\"closeAttempts\":1")
                .contains("\"lastCloseOutcome\":\"refused by the close gate and the run's own"
                    + " verdict\"");
        }

        @Test
        @DisplayName("a close before any run is a 409 saying a close reads a run's figures")
        void aCloseWithNoRunIsAConflict() throws IOException {
            Response response = post(PERIOD + "/close", "");

            assertThat(response.status())
                .as("FR-901 step 4 has nothing to be green about, which is a gate failing")
                .isEqualTo(409);
            assertThat(response.body())
                .contains("\"mayClose\":false")
                .contains("a close reads the figures a run published");
            assertThat(get(PERIOD).body()).contains("\"status\":\"OPEN\"");
        }

        @Test
        @DisplayName("a close that fails part-way is a 400 and does not leave the period CLOSING")
        void aFailedCloseDoesNotStrandThePeriod() throws IOException {
            post(RUNS, START);

            // The failing input, measured: EirService parses closedAt itself, so this throws
            // DateTimeParseException from inside the close. Two defects came out of that one input.
            // The period had already been moved to CLOSING to put it to the gate and nothing put it
            // back — status CLOSING, lastCloseOutcome "in progress", no close under way, and runs
            // still publishing into a period that had stopped taking postings. And the exception
            // reached the caller as a 500, telling an operator the engine was broken when the
            // request was.
            Response malformed = post(PERIOD + "/close", "closedAt=not-an-instant");

            assertThat(malformed.status())
                .as("a caller's malformed field is a 400 here as everywhere else in this API")
                .isEqualTo(400);
            assertThat(malformed.body())
                .contains("bad request")
                .contains("closedAt")
                .contains("ISO-8601");

            Response period = get(PERIOD);
            assertThat(period.body())
                .as("a status must not outlive the operation it describes")
                .contains("\"status\":\"OPEN\"")
                .contains("\"lastCloseOutcome\":\"abandoned: the close failed with"
                    + " DateTimeParseException\"");

            // And the period is closeable again, rather than needing a restart to get out of
            // CLOSING: the arc still works afterwards.
            post("/api/repair", "");
            post(RUNS, START);
            post("/api/post", "");
            assertThat(post(PERIOD + "/close", "").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("refused, repair, re-run, post, permitted — the whole arc, and the 409 at one end")
        void theWholeArc() throws IOException {
            // 1. Run, and close: refused, because C-0003 is quarantined and nothing is posted.
            post(RUNS, START);
            assertThat(post(PERIOD + "/close", "").status()).isEqualTo(409);

            // 2. Repair, which is the remediation FR-905's queue exists to route somebody to: the
            //    fix for a missing opening balance is to CORRECT THE INPUT, not to accept the
            //    exception. C-0003's balance goes on file.
            assertThat(post("/api/repair", "").body()).contains("\"repaired\":true");

            // 3. Re-run. All three contracts compute now, the queue is empty, and RC-1's presence
            //    break goes with it because the engine now projects what the CBS billed.
            Response rerun = post(RUNS, START);
            assertThat(rerun.body())
                .contains("\"contractsProcessed\":3")
                .contains("\"quarantined\":0")
                .contains("\"exceptionCount\":0")
                .contains("\"reportsCleanClose\":true");

            // 4. Post the journals, so SL-1 has two sides in the same state. A close attempted
            //    before this is refused with SL-1 red, which is the correct answer.
            assertThat(post("/api/post", "").body()).contains("\"ran\":true");

            // 5. Close: permitted, on a 200, with the attestation FR-902 requires.
            Response closed = post(PERIOD + "/close", "");
            assertThat(closed.status())
                .as("the other end of the arc: a permitted close is an ordinary 200")
                .isEqualTo(200);
            assertThat(closed.body())
                .contains("\"mayClose\":true")
                .contains("\"closed\":true")
                .contains("\"periodStatus\":\"CLOSED\"")
                // The same two flags the refusal carries, both false — a field that appears on only
                // one branch is one a client learns to read as optional, and then its absence and
                // its being false say the same thing.
                .contains("\"gateRefused\":false")
                .contains("\"gateRefusalCount\":0")
                .contains("\"runRefused\":false")
                .contains("CLOSE PERMITTED");
            // ck_accounting_period_closure_attested: a CLOSED period names who closed it, when, and
            // the system-time boundary a replay reads as at, or it is not closed.
            assertThat(closed.body())
                .contains("\"closedBy\":\"" + CLOSED_BY + "\"")
                .contains("\"closedAt\":\"" + CLOSED_AT + "\"")
                .contains("\"versionCutoffAt\":\"" + VERSION_CUTOFF + "\"");

            // And the period resource says so afterwards, which is the whole point of there being
            // a period resource: the status survives the request that changed it.
            Response after = get(PERIOD);
            assertThat(after.body())
                .contains("\"status\":\"CLOSED\"")
                .contains("\"closed\":true")
                .contains("\"legalSuccessors\":[]")
                .contains("replay cutoff " + VERSION_CUTOFF);
        }
    }

    // ============================================================== immutability

    @Nested
    @DisplayName("a closed period is immutable — FR-902, and there is no way round it here")
    class Immutability {

        private void closeTheperiod() throws IOException {
            post(RUNS, START);
            post("/api/repair", "");
            post(RUNS, START);
            post("/api/post", "");
            assertThat(post(PERIOD + "/close", "").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a second close is a 409 and the gate is never asked")
        void aSecondCloseIsRefusedWithoutAskingTheGate() throws IOException {
            closeTheperiod();

            Response again = post(PERIOD + "/close", "");

            // The failing input, named: a period that closed cleanly a moment ago, closed again.
            // EirService.close builds its own OPEN period per request and moves it to CLOSING, so a
            // gate asked here is shown a period that is open in its view and closed in this
            // register's — and it PERMITS. The refusal has to be made before the gate is asked, and
            // gateAsked records that it was.
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.body())
                .contains("\"mayClose\":false")
                .contains("\"gateAsked\":false")
                .contains("\"periodStatus\":\"CLOSED\"")
                .contains("is already CLOSED")
                .contains("CLOSED is terminal (FR-902)")
                .contains("POST /restatements");
            assertThat(again.body())
                .as("if the gate had been asked it would have answered CLOSE PERMITTED, and a"
                    + " second attestation of one period would be in the body")
                .doesNotContain("CLOSE PERMITTED");
        }

        @Test
        @DisplayName("a run cannot publish into a closed period either")
        void aRunIntoAClosedPeriodIsRefused() throws IOException {
            closeTheperiod();

            Response response = post(RUNS, START);

            // Refused here rather than left to the close: a run publishes figures into a period,
            // and a run that overwrote a closed period's carrying amounts would have done the
            // damage before any gate looked at it.
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"started\":false")
                .contains("is CLOSED")
                .contains("FR-902");
        }

        @Test
        @DisplayName("there is no PATCH and no reopen: the verb is refused, not quietly ignored")
        void thereIsNoPatchAndNoReopen() throws IOException {
            assertThat(statusOfRaw("PATCH", PERIOD))
                .as("PATCH is the verb an integrator reaches for to reopen a period; 06 § 4 has"
                    + " none, because reopening is the mutation FR-902 forbids wearing a status"
                    + " change")
                .isEqualTo(405);
            assertThat(statusOfRaw("PUT", PERIOD)).isEqualTo(405);
            assertThat(statusOfRaw("DELETE", PERIOD)).isEqualTo(405);

            Response refused = post(PERIOD, "");
            assertThat(refused.status()).isEqualTo(405);
            assertThat(refused.body())
                .contains("no PATCH")
                .contains("no reopen endpoint")
                .contains("POST /restatements");

            assertThat(get("/api/periods/" + Seed.PERIOD_ID + "/reopen").status())
                .as("and no reopen route exists to find")
                .isEqualTo(404);
        }
    }

    // ================================================== reading the engine's answer

    @Nested
    @DisplayName("JsonView: the close's verdict is read back, and a body that changed shape is loud")
    class ReadingTheEnginesAnswer {

        @Test
        @DisplayName("the value's shape disambiguates a key that appears as two kinds of thing")
        void theShapeDisambiguates() {
            // This is the run body's actual shape: a population count at the top and a boolean per
            // contract row, both under 'computed'. Reading "the first one" would make a population
            // figure a matter of writing order.
            JsonView view = JsonView.ofRendered(
                "{\"computed\":2,\"contracts\":[{\"contractId\":\"C-0001\",\"computed\":true}]}");

            assertThat(view.count("computed")).isEqualTo(2);
            assertThat(view.flag("computed")).isTrue();
        }

        @Test
        @DisplayName("two fields of one shape refuse rather than pick, and the message says why")
        void twoOfOneShapeRefuse() {
            JsonView view = JsonView.ofRendered("{\"computed\":2,\"nested\":{\"computed\":5}}");

            assertThatThrownBy(() -> view.count("computed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than once")
                .hasMessageContaining("coin toss with a period close on it");
        }

        @Test
        @DisplayName("a field that is not there is a defect, not a default")
        void anAbsentFieldIsNeverDefaulted() {
            JsonView view = JsonView.ofRendered("{\"ran\":true}");

            // A flag defaulting to false would answer 409 for a body that never said the gate
            // refused; defaulting to true would close a period over a refusal.
            assertThatThrownBy(() -> view.flag("mayClose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no 'mayClose' field");
        }

        @Test
        @DisplayName("a quoted flag is not a flag, and a quoted figure is not a count")
        void theTypeIsNotGuessed() {
            JsonView quoted = JsonView.ofRendered(
                "{\"mayClose\":\"false\",\"deviation\":\"5298.16\"}");

            assertThatThrownBy(() -> quoted.flag("mayClose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boolean");
            assertThatThrownBy(() -> quoted.count("deviation"))
                .as("every figure crosses the wire as a string; reading one as a number is the"
                    + " precision policy leaking away at the edge")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole number");
        }

        @Test
        @DisplayName("a field name inside a refusal sentence is not a field")
        void contentIsNeverMistakenForAField() {
            // The refusal sentences in this engine are long prose carrying ids, figures and — as
            // here — the names of fields. Json.quote escapes every quote inside a value, so an
            // unescaped quote in the rendered body is always a delimiter.
            String rendered = Json.object()
                .str("detail", "the response said \"mayClose\":true and it was wrong")
                .toString();

            assertThatThrownBy(() -> JsonView.ofRendered(rendered).flag("mayClose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no 'mayClose' field");
        }

        @Test
        @DisplayName("the gate's multi-line verdict round-trips through the reader unchanged")
        void theVerdictRoundTrips() {
            // CloseDecision.describe() is one sentence then one line per refusal, so it arrives
            // escaped. Read naively it would put a raw newline in a response body, which is how a
            // console renders nothing.
            String verdict = "CLOSE REFUSED by 2 gate(s) [INVARIANT_BREACH]\n"
                + "  - INVARIANT_BREACH: SL_1 is red, deviation 36059.88\n"
                + "  - RECONCILIATION_NOT_TIED: \"sub-ledger to GL\"\ttabbed";
            String rendered = Json.object().str("gateVerdict", verdict).toString();

            assertThat(JsonView.ofRendered(rendered).text("gateVerdict")).isEqualTo(verdict);
            assertThat(Json.object()
                .str("gateVerdict", JsonView.ofRendered(rendered).text("gateVerdict"))
                .toString())
                .as("read and re-emitted, the same bytes")
                .isEqualTo(rendered);
        }

        @Test
        @DisplayName("rows are counted on a key, never on a delimiter")
        void rowsAreCountedOnAKey() {
            // Counting commas would count the ones inside "5,298.16" and inside every sentence.
            String rendered = Json.object()
                .array("contracts", java.util.List.of(
                    Json.object().str("contractId", "C-0001"),
                    Json.object().str("contractId", "C-0003")
                        .str("exceptionCategory", "MISSING_MANDATORY_FIELD")
                        .str("exceptionDetail", "no state on file, out by 5,298.16")))
                .toString();

            JsonView view = JsonView.ofRendered(rendered);
            assertThat(view.occurrencesOf("contractId")).isEqualTo(2);
            assertThat(view.occurrencesOf("exceptionCategory"))
                .as("one contract carries an exception, and the commas in the prose do not")
                .isEqualTo(1);
            assertThat(view.occurrencesOf("satisfied", "false")).isZero();
        }
    }

    // ================================================================ the seam

    @Nested
    @DisplayName("the registration: two subtrees, and every path below them owned")
    class TheRegistrationSeam {

        @Test
        @DisplayName("both resources register as subtrees, and each owns exactly its own paths")
        void bothResourcesRegisterAsSubtrees() throws IOException {
            // Routes.route registers a subtree and the JDK matches a context by string prefix, so
            // /api/runsomething arrives at the /api/runs context. Read as a path parameter it would
            // be the run named 'omething'; it is a 404 that says why.
            Response nearMiss = get("/api/runsomething");
            assertThat(nearMiss.status()).isEqualTo(404);
            assertThat(nearMiss.body()).contains("only shares its opening characters");

            // And the console's own singular routes, which share the opening characters the other
            // way, still answer for themselves.
            assertThat(post("/api/run", "").status())
                .as("/api/run is EirServer's own route and must not be swallowed by /api/runs")
                .isEqualTo(200);
            assertThat(post("/api/close", "").status()).isEqualTo(200);

            // A trailing slash is the collection, not a child with a blank name.
            assertThat(get("/api/periods/").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("the module names its specification section, declared rather than derived")
        void theModuleNamesItsSection() {
            assertThat(new RunsAndPeriodsModule(new EirService(Seed.book())).specSection())
                .as("a module that drifts from its specification section should be visibly wrong")
                .isEqualTo("06 § 4");
        }
    }
}
