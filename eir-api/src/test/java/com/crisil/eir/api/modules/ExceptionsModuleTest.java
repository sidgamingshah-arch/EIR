package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.PathOnlyExchange;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.exception.ExceptionStatus;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 06 § 6's exception work queue, driven over real HTTP on a real socket.
 *
 * <p><b>Why over a socket rather than by calling the module.</b> Every defect this layer can
 * introduce lives between the two: a filter that silently ignores what it does not recognise, an
 * engine refusal mapped onto a 4xx so a work queue looks like a client error, a mandatory note that
 * a form parser quietly defaults. A test that calls the handler misses all three. Port 0 lets the
 * OS pick, so this runs anywhere and never collides with a developer's own server.
 *
 * <p>Assertions are on substrings of the JSON rather than on a parsed tree, because this module has
 * no JSON parser. The substrings are chosen so that a formatting change cannot accidentally satisfy
 * them: a specific exception id, a specific gate refusal name, a specific field with its value.
 *
 * <p><b>Where the expected values come from.</b> The seeded book of {@code Seed} holds three
 * contracts, and C-0003 arrives with period movements and no recorded opening balance — that is
 * what {@code Seed.CONTRACT_WITHOUT_STATE} names. A roll-forward cannot start from a balance nobody
 * recorded, so 04 § 3's category for it is {@code MISSING_MANDATORY_FIELD}, and FR-905 isolates it
 * per contract rather than failing the run: one row, on C-0003, and the other two contracts
 * compute. So the queue holds exactly one exception and its id is
 * {@code C-0003:MISSING_MANDATORY_FIELD}. None of that is read off a run of the code under test; it
 * is the seed book's own description plus 04 § 3's category list.
 *
 * <p>The two cases the seeded book cannot produce — one contract with two rows of one category, and
 * a queue of more than one row — are driven through the module directly with a hand-built queue,
 * because a control whose condition no test can construct is a control nobody has seen fire.
 */
class ExceptionsModuleTest {

    /** The one exception the seeded book raises. See the class javadoc for the derivation. */
    private static final String QUARANTINED_ID = "C-0003:MISSING_MANDATORY_FIELD";

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
    @DisplayName("GET /api/exceptions — the work queue, filterable on status and category")
    class TheWorkQueue {

        @Test
        @DisplayName("the queue names the quarantined contract, its category and its id")
        void theQueueNamesTheQuarantinedContract() throws IOException {
            post("/api/run", "");

            Response response = get("/api/exceptions");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("C-0003 has movements and no opening balance, so 04 § 3 files it under"
                    + " MISSING_MANDATORY_FIELD and FR-905 isolates it rather than failing the run")
                .contains("\"id\":\"" + QUARANTINED_ID + "\"")
                .contains("\"contractId\":\"C-0003\"")
                .contains("\"category\":\"MISSING_MANDATORY_FIELD\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"queuedHere\":1");
            // The row's three separate answers, asserted as the adjacent triple they are written
            // as. An accepted exception stops blocking and does NOT lift the quarantine, so
            // reporting one "worked" flag over the three would let an operator conclude the
            // contract's figure came back.
            assertThat(response.body())
                .contains("\"blocksClose\":true,\"quarantinesContract\":true,"
                    + "\"demotesContract\":false");
            // The whole-queue arithmetic, which a filtered read must not be able to hide — and
            // every aggregate named for the queue it was computed over, with the response saying
            // what to ask instead. An unscoped "blocksClose" at the top of this response would
            // read as the close gate's answer, and this derived snapshot is not it.
            assertThat(response.body())
                .contains("\"closeBlockersOnThisQueue\":1")
                .contains("\"blocksCloseOnThisQueue\":true")
                .contains("\"quarantinedContractsOnThisQueue\":[\"C-0003\"]")
                .contains("CLOSE BLOCKED by 1 of 1 exception(s)")
                .contains("\"authoritativeCloseGate\":\"POST /api/close.");
        }

        @Test
        @DisplayName("the diagnostic survives: a captured failure carries its throwable")
        void theDiagnosticSurvives() throws IOException {
            post("/api/run", "");

            Response response = get("/api/exceptions");

            // FR-905's cheap satisfaction is to swallow the throwable and file a category, which
            // turns a run-stopping defect into queue entries nobody can work. The detail has to
            // say what happened, not just which of the ten categories it was.
            assertThat(response.body())
                .contains("\"hasDiagnostic\":true")
                .contains("has no state as at");
        }

        @Test
        @DisplayName("both filters apply, and a category that raised nothing returns nothing")
        void bothFiltersApply() throws IOException {
            post("/api/run", "");

            assertThat(get("/api/exceptions?status=OPEN").body())
                .contains("\"statusFilter\":\"OPEN\"")
                .contains("\"returned\":1")
                .contains(QUARANTINED_ID);

            assertThat(get("/api/exceptions?status=OPEN&category=MISSING_MANDATORY_FIELD").body())
                .as("06 § 6's own example shape, on the category this book does raise")
                .contains("\"categoryFilter\":\"MISSING_MANDATORY_FIELD\"")
                .contains("\"returned\":1");

            Response nothingRaised = get("/api/exceptions?status=OPEN&category=NO_SOLUTION");
            assertThat(nothingRaised.body())
                .as("06 § 6's literal example. No solve failed on this book, so the answer is an"
                    + " empty list — and the queue arithmetic still reports the blocker, because a"
                    + " work queue read through a filter is the easiest place to conclude that"
                    + " nothing is wrong")
                .contains("\"categoryFilter\":\"NO_SOLUTION\"")
                .contains("\"returned\":0")
                .contains("\"queuedHere\":1")
                .contains("\"blocksCloseOnThisQueue\":true")
                .doesNotContain("\"id\":\"" + QUARANTINED_ID + "\"");

            assertThat(get("/api/exceptions?status=RESOLVED").body())
                .as("nothing has been worked yet, so the RESOLVED page is empty")
                .contains("\"returned\":0");
        }

        @Test
        @DisplayName("a filter sent blank refuses rather than meaning 'every status'")
        void aBlankFilterRefuses() throws IOException {
            Response response = get("/api/exceptions?status=");

            assertThat(response.status())
                .as("FormBody.has answers false for a present-but-blank value, so reading presence"
                    + " that way would make ?status= mean no filter and answer the whole queue to"
                    + " a caller who asked for a slice of it — the same defect as ignoring an"
                    + " unrecognised value")
                .isEqualTo(400);
            assertThat(response.body())
                .contains("'status' is named in the query with no value");

            assertThat(get("/api/exceptions?category=").status()).isEqualTo(400);

            Response noEquals = get("/api/exceptions?status");
            assertThat(noEquals.status())
                .as("a bare ?status names the filter as plainly as ?status= does, and one message"
                    + " covers both — 'arrived absent' would contradict a query that names it")
                .isEqualTo(400);
            assertThat(noEquals.body())
                .contains("'status' is named in the query with no value");

            Response encodedName = get("/api/exceptions?%73tatus=RESOLVED");
            assertThat(encodedName.body())
                .as("the parameter NAME is percent-decoded before presence is decided; comparing"
                    + " the raw form would drop the filter silently and answer the whole queue"
                    + " labelled statusFilter:null, which is the defect this check exists for")
                .contains("\"statusFilter\":\"RESOLVED\"")
                .contains("\"returned\":0");
        }

        @Test
        @DisplayName("an unrecognised filter value is a 400 that names every accepted value")
        void anUnrecognisedFilterIsRefused() throws IOException {
            Response badStatus = get("/api/exceptions?status=OPENN");

            assertThat(badStatus.status())
                .as("a filter that ignored what it did not recognise would answer ?status=OPENN"
                    + " with the whole queue, and the caller would read accepted rows as open ones")
                .isEqualTo(400);
            assertThat(badStatus.body())
                .contains("OPENN")
                .contains("ACCEPTED_WITH_APPROVAL");

            Response badCategory = get("/api/exceptions?category=NOT_A_CATEGORY");

            assertThat(badCategory.status()).isEqualTo(400);
            assertThat(badCategory.body())
                .contains("NOT_A_CATEGORY")
                .contains("POOL_BACKTEST_BREACH");
        }

        @Test
        @DisplayName("the queue is readable before any run, and says which run raised it")
        void theQueueTiesBackToARun() throws IOException {
            Response response = get("/api/exceptions");

            assertThat(response.status()).isEqualTo(200);
            // 04 § 2.13 requires an exception to tie back to a run. Two ids for one roll-forward
            // would make the tie a lie, so this is the id the console's own run defaults to.
            assertThat(response.body())
                .contains("\"raisedByRunId\":\"RUN-202805-01\"")
                .contains("\"periodId\":" + Seed.PERIOD_ID);
        }
    }

    @Nested
    @DisplayName("POST /api/exceptions/resolve — 04 § 3's note is mandatory")
    class Resolution {

        @Test
        @DisplayName("a blank note refuses, because a resolved row with no note is unworked")
        void aBlankNoteRefuses() throws IOException {
            post("/api/run", "");

            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=dq.desk&note=");

            assertThat(response.status())
                .as("04 § 3 makes the note mandatory: a resolved row with no note is"
                    + " indistinguishable from a row nobody looked at")
                .isEqualTo(400);
            assertThat(response.body())
                .contains("'note' is required and arrived blank");

            assertThat(get("/api/exceptions?status=OPEN").body())
                .as("the refusal left the row alone — a half-applied resolution reads as unworked"
                    + " in every report and as worked to anyone reading the columns")
                .contains("\"returned\":1")
                .contains(QUARANTINED_ID);
        }

        @Test
        @DisplayName("an absent note refuses too, rather than defaulting one")
        void anAbsentNoteRefuses() throws IOException {
            post("/api/run", "");

            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=dq.desk");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("'note' is required and arrived absent");
        }

        @Test
        @DisplayName("an unsigned resolution refuses: a fix nobody signed is not evidence")
        void anUnsignedResolutionRefuses() throws IOException {
            post("/api/run", "");

            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "note=sourced from the CBS extract");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("'resolvedBy' is required");
        }

        @Test
        @DisplayName("a signed resolution with a note lifts the quarantine and empties the queue")
        void aSignedResolutionWorksTheRow() throws IOException {
            post("/api/run", "");

            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=dq.desk"
                    + "&note=opening balance sourced from the CBS extract");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"worked\":true")
                .contains("\"status\":\"RESOLVED\"")
                .contains("\"resolvedBy\":\"dq.desk\"")
                .contains("opening balance sourced from the CBS extract");
            // Resolution asserts the defect was FIXED, so it lifts the quarantine — which is the
            // half that makes claiming it for a defect that stands worse than accepting. These
            // three sit under thisQueue, not at the top of the response, because they are this
            // module's queue reporting on its own row and not the close gate's answer.
            assertThat(response.body())
                .contains("\"thisQueue\":{\"blocksClose\":false,\"quarantinesContract\":false,"
                    + "\"defectFixed\":true");

            assertThat(get("/api/exceptions?status=OPEN").body()).contains("\"returned\":0");
            assertThat(get("/api/exceptions?status=RESOLVED").body())
                .contains("\"returned\":1")
                .contains(QUARANTINED_ID);
        }

        @Test
        @DisplayName("the resolution corrects the input on the engine, and the close then permits")
        void theResolutionCorrectsTheInputAndTheCloseThenPermits() throws IOException {
            post("/api/run", "");

            Response resolved = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=dq.desk"
                    + "&note=opening balance sourced from the CBS extract");

            // The half that makes the resolution more than a label. PeriodCloseGate reads the
            // ENGINE's queue, so a row marked resolved here and nothing else would leave the close
            // refusing with EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED. C-0003's defect is a missing
            // opening balance and the engine's one input-correction path supplies it, so that is
            // what this endpoint delegates — and 528,407.32 is reference case 1's month-13
            // opening, which is the balance that goes on file.
            assertThat(resolved.body())
                .as("the engine's own answer, nested verbatim")
                .contains("\"engine\":{\"repaired\":true")
                .contains("\"openingGca\":\"528407.32\"");

            Response rerun = post("/api/run", "");
            assertThat(rerun.body())
                .as("with the balance on file all three contracts compute and the queue empties")
                .contains("\"populationSize\":3")
                .contains("\"computed\":3")
                .contains("\"quarantined\":0");

            post("/api/post", "");
            Response close = post("/api/close", "closedBy=financial.controller");

            assertThat(close.body())
                .as("the whole point of resolution over acceptance: the input was corrected, so"
                    + " there is nothing left for anybody to sign for")
                .contains("\"mayClose\":true")
                .contains("\"refusalCount\":0")
                .doesNotContain("EXCEPTION_NEITHER_CLEARED_NOR_ACCEPTED");
        }

        @Test
        @DisplayName("a second resolution is allowed and names the working it replaced")
        void aSecondResolutionNamesWhatItReplaced() throws IOException {
            post("/api/run", "");
            post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=dq.desk&note=first, and signed");

            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/resolve",
                "resolvedBy=someone.else&note=second");

            assertThat(response.status()).isEqualTo(200);
            // Allowed rather than refused, because every POST /api/run rebuilds the engine's queue
            // and this module's worked state outlives it. An endpoint that refused a second
            // working would leave a row that had been worked before a re-run unworkable for the
            // life of the process, and for acceptance that is the only route past the exception.
            assertThat(response.body())
                .contains("\"worked\":true")
                .contains("\"replacedWorking\":\"RESOLVED by dq.desk\"")
                .contains("\"resolvedBy\":\"someone.else\"");
        }

        @Test
        @DisplayName("the category half of an id case-folds and the contract half does not")
        void theIdFoldsTheCategoryAndNotTheContract() throws IOException {
            post("/api/run", "");

            Response folded = post("/api/exceptions/C-0003:missing_mandatory_field/resolve",
                "resolvedBy=dq.desk&note=lower case category");

            assertThat(folded.status())
                .as("the category is an enum and the query filters already take it in any case")
                .isEqualTo(200);
            assertThat(folded.body()).contains("\"id\":\"" + QUARANTINED_ID + "\"");

            Response wrongCase = post("/api/exceptions/c-0003:MISSING_MANDATORY_FIELD/resolve",
                "resolvedBy=dq.desk&note=lower case contract");

            assertThat(wrongCase.status())
                .as("two contract ids differing in case are two contracts — folding this half"
                    + " would let a caller work C-0003's exception by typing c-0003")
                .isEqualTo(400);
            assertThat(wrongCase.body()).contains("no queued exception has id 'c-0003:");

            assertThat(post("/api/exceptions/C-0003/resolve",
                "resolvedBy=dq.desk&note=no category at all").status())
                .as("an id with no category addresses every category on that contract, and one"
                    + " signature must not clear two exceptions that need two decisions")
                .isEqualTo(400);
        }

        @Test
        @DisplayName("an id no row carries is a 400 that lists the ids the queue holds")
        void anUnknownIdIsRefused() throws IOException {
            post("/api/run", "");

            Response response = post("/api/exceptions/C-9999:NO_SOLUTION/resolve",
                "resolvedBy=dq.desk&note=nothing to fix");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("no queued exception has id 'C-9999:NO_SOLUTION'")
                .contains(QUARANTINED_ID);
        }
    }

    @Nested
    @DisplayName("POST /api/exceptions/accept — the only route past a queued exception")
    class Acceptance {

        @Test
        @DisplayName("the same identity on both sides makes the close refuse"
            + " SELF_APPROVED_ACCEPTANCE")
        void aSelfApprovedAcceptanceMakesTheCloseRefuse() throws IOException {
            post("/api/run", "");

            // The same person, spelled two ways. FourEyes strips and case-folds at Locale.ROOT,
            // which is the comparison the lower(btrim(...)) constraints in the DDL perform, so
            // 'ops.analyst' and 'Ops.Analyst' are one identity here as they are in the database.
            // A plain equals() here is the defect that let a maker approve their own artefact
            // under a different capitalisation.
            Response accepted = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=Ops.Analyst&reason=immaterial this month");

            assertThat(accepted.status()).isEqualTo(200);
            assertThat(accepted.body())
                .as("recorded, not rejected: manufacturing the refusal here would leave"
                    + " PeriodCloseGate's SELF_APPROVED_ACCEPTANCE unreachable")
                .contains("\"worked\":true")
                .contains("\"engine\":{\"ran\":true,\"accepted\":1");
            // The neighbouring key is part of the substring on purpose. This response nests the
            // engine's own answer, which carries its own selfApproved computed by its own call to
            // FourEyes — so a bare "selfApproved":true is satisfied by the nested object even when
            // this module compares the two identities with a plain equals(). The reason field is
            // this module's and the engine's response has none, so the pair pins the right one.
            // That is not a hypothetical: replacing FourEyes.isSelfApproval with equals() here
            // left the bare substring passing.
            assertThat(accepted.body())
                .as("this module's own four-eyes verdict, not the engine's nested one")
                .contains("\"reason\":\"immaterial this month\",\"selfApproved\":true");
            // The acceptance lives on the engine's queue and nowhere else. A copy here would have
            // this module's queue reporting a clear close gate on a period where the engine had
            // recorded nothing at all — see anAcceptanceBeforeAnyRunSaysSo.
            assertThat(accepted.body())
                .contains("\"recordedOn\":\"the engine's queue only.");
            assertThat(get("/api/exceptions?status=OPEN").body())
                .as("understating a worked row is safe; reporting a clear gate is not")
                .contains("\"returned\":1");
            assertThat(accepted.body())
                .as("the acceptance the gate weighs lives on the engine, so the engine's own"
                    + " answer comes back nested verbatim — and first, because engine.ran is the"
                    + " field that says whether it reached the list the gate weighs")
                .startsWith("{\"engine\":{\"ran\":true");

            Response close = post("/api/close", "closedBy=financial.controller");

            assertThat(close.status()).isEqualTo(200);
            assertThat(close.body())
                .as("every one of 04 § 3's ten categories blocks the close, so acceptance is the"
                    + " only route past a queued exception — and an exception accepted by the"
                    + " person who put it forward is not accepted")
                .contains("SELF_APPROVED_ACCEPTANCE")
                .contains("maker and approver of the acceptance are the same identity")
                .contains("\"mayClose\":false");
        }

        @Test
        @DisplayName("two different people leave the close with no self-approval to report")
        void aProperlyApprovedAcceptanceIsNotSelfApproved() throws IOException {
            post("/api/run", "");

            Response accepted = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=fin.controller&reason=immaterial");

            assertThat(accepted.body())
                .contains("\"worked\":true")
                // Pinned by its neighbour for the same reason as the test above.
                .contains("\"reason\":\"immaterial\",\"selfApproved\":false");

            Response close = post("/api/close", "closedBy=financial.controller");

            // The pair that proves the check above can fail rather than always firing. This close
            // still refuses — the sub-ledger break and the quarantine are untouched by an
            // acceptance — but it refuses for reasons that are not this one.
            assertThat(close.body())
                .as("the self-approval refusal must be absent when two people signed, or the"
                    + " control fires on every close and says nothing")
                .doesNotContain("SELF_APPROVED_ACCEPTANCE");
            assertThat(close.body()).contains("\"mayClose\":false");
        }

        @Test
        @DisplayName("both signatures and the reason are mandatory, none of them defaulted")
        void bothSignaturesAndTheReasonAreMandatory() throws IOException {
            post("/api/run", "");

            assertThat(post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "approvedBy=fin.controller&reason=x").status())
                .isEqualTo(400);
            assertThat(post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&reason=x").status())
                .isEqualTo(400);

            Response noReason = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=fin.controller");

            assertThat(noReason.status())
                .as("the reason is the whole audit trail of why a close proceeded over a known"
                    + " defect; the console defaults it and this endpoint must not")
                .isEqualTo(400);
            assertThat(noReason.body()).contains("'reason' is required");
        }

        @Test
        @DisplayName("an acceptance survives a re-run by being posted again, and still refuses")
        void anAcceptanceCanBePostedAgainAfterARerun() throws IOException {
            post("/api/run", "");
            post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=Ops.Analyst&reason=first pass");

            // Every run rebuilds the engine's queue and clears its acceptance list on purpose —
            // the gate refuses a carried-forward acceptance as ACCEPTANCE_WITHOUT_AN_EXCEPTION.
            // So after a re-run the acceptance is gone from the only place the gate reads, and it
            // has to be possible to post it again. An endpoint that refused a second signature
            // would close 06 § 6's only route past a queued exception for the life of the process.
            post("/api/run", "");

            Response again = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=Ops.Analyst&reason=second pass");

            assertThat(again.status()).isEqualTo(200);
            assertThat(again.body())
                .contains("\"worked\":true")
                .contains("\"engine\":{\"ran\":true,\"accepted\":1")
                .contains("\"reason\":\"second pass\",\"selfApproved\":true");

            assertThat(post("/api/close", "closedBy=financial.controller").body())
                .as("re-posting is a convenience for a cleared engine list, never a way round the"
                    + " four-eyes comparison — the gate refuses it however many times it is posted")
                .contains("SELF_APPROVED_ACCEPTANCE")
                .contains("\"mayClose\":false");
        }

        @Test
        @DisplayName("an acceptance posted before any run says so on the engine's own answer")
        void anAcceptanceBeforeAnyRunSaysSo() throws IOException {
            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=fin.controller&reason=too early");

            assertThat(response.status()).isEqualTo(200);
            // The engine has nothing to accept until a run has been rolled forward, and it says so
            // rather than pretending. Reported by nesting its answer first rather than by this
            // layer parsing its own JSON back to find out.
            assertThat(response.body())
                .startsWith("{\"engine\":{\"ran\":false")
                .contains("no run for period " + Seed.PERIOD_ID + " yet")
                .contains("engine.ran is the field that says whether the acceptance");
        }
    }

    @Nested
    @DisplayName("the edge keeps the engine's distinctions, and states its own deviation")
    class TheEdge {

        @Test
        @DisplayName("06 § 6's path-id shape is served, and the id comes off the path")
        void theSpecifiedPathShapeIsServed() throws IOException {
            // This test used to assert the opposite -- a 400 explaining that Routes.post hands a
            // handler the form body and not the exchange, so one prefix context could not tell
            // resolve from accept and the action would have to be inferred from which fields the
            // body carried. Routes.route now exists and the module dispatches on the path, so the
            // shape 06 § 6 specifies is answered rather than explained away. Kept rather than
            // deleted because the refusal it replaced is the reason the seam was built.
            Response response = post("/api/exceptions/" + QUARANTINED_ID + "/accept",
                "acceptedBy=ops.analyst&approvedBy=ops.analyst&reason=x");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("the id in the response must be the one from the path, not one read from a"
                    + " body field that is no longer sent")
                .contains("\"id\":\"" + QUARANTINED_ID + "\"");
        }

        @Test
        @DisplayName("a mistyped action under the prefix is a 404, not the mutation next door")
        void aMistypedActionIsDeclinedRatherThanSwallowed() throws IOException {
            // The defect a prefix context has and a subtree dispatcher does not. A context
            // registered on /api/exceptions/ matches by longest prefix, so every one of these
            // reached the resolution handler, which could not see the path it arrived by and
            // performed the mutation anyway. A caller who mistyped the action would be told the
            // row was worked -- and it would have been, under an action nobody asked for.
            for (String path : List.of(
                "/api/exceptions/resolveXYZ",
                "/api/exceptions/" + QUARANTINED_ID + "/resolve/anything",
                "/api/exceptions/" + QUARANTINED_ID + "/approve")) {
                Response response = post(path, "resolvedBy=dq.desk&note=mistyped");

                assertThat(response.status())
                    .as("%s must be declined, not routed to a mutation", path)
                    .isEqualTo(404);
            }

            // And the row is untouched: a 404 that had already mutated would be the worst of both.
            assertThat(get("/api/exceptions?status=RESOLVED").body())
                .as("nothing was worked by any of the three declined paths")
                .contains("\"returned\":0");
        }

        @Test
        @DisplayName("the wrong verb on the queue is a 405, not a silent 404")
        void theWrongVerbOnTheQueueIsRefused() throws IOException {
            Response response = post("/api/exceptions", "");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("GET only");
        }

        @Test
        @DisplayName("the module declares the specification section it implements")
        void theModuleDeclaresItsSection() {
            assertThat(new ExceptionsModule(new EirService(Seed.book())).specSection())
                .isEqualTo("06 § 6");
        }
    }

    @Nested
    @DisplayName("the two conditions the seeded book cannot raise, over a hand-built queue")
    class ConditionsTheSeedBookCannotRaise {

        /** Registers a module's routes so a handler can be called without a server. */
        private static final class RecordedRoutes implements Routes {
            private final Map<String, Function<HttpExchange, Json.Obj>> gets =
                new LinkedHashMap<>();
            private final Map<String, Function<FormBody, Json.Obj>> posts = new LinkedHashMap<>();
            private final Map<String, Routes.PathHandler> subtrees = new LinkedHashMap<>();

            @Override
            public void get(String path, Function<HttpExchange, Json.Obj> handler) {
                gets.put(path, handler);
            }

            @Override
            public void post(String path, Function<FormBody, Json.Obj> handler) {
                posts.put(path, handler);
            }

            @Override
            public void route(String path, Routes.PathHandler handler) {
                subtrees.put(path, handler);
            }

            /**
             * Drives the registered subtree at {@code rawPath} the way {@link EirServer} would.
             *
             * <p>The path is what dispatches: the module reads the id and the action off it, so a
             * test that handed the handler a form and no path would exercise none of that. Asserts
             * that a handler was actually found, because {@code subtrees.get} returning null on a
             * path the module was supposed to claim is exactly the failure this harness exists to
             * notice, and a NullPointerException reports it as a test defect instead.
             */
            Json.Obj post(String rawPath, String form) {
                Routes.PathHandler handler = subtrees.entrySet().stream()
                    .filter(entry -> rawPath.startsWith(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "no subtree registered covering " + rawPath + "; registered: "
                            + subtrees.keySet()));
                Routes.Answer answer = handler.handle(
                    PathOnlyExchange.post(rawPath), FormBody.parse(form));
                if (answer == null) {
                    throw new AssertionError(
                        "the module declined " + rawPath + " — a declined path is a 404 on the"
                            + " running server, not an answer this assertion can read");
                }
                return answer.body();
            }
        }

        private static RecordedRoutes moduleOver(ExceptionQueue queue) {
            RecordedRoutes routes = new RecordedRoutes();
            new ExceptionsModule(new EirService(Seed.book()), () -> queue).register(routes);
            return routes;
        }

        @Test
        @DisplayName("an id addressing two rows refuses rather than working one of them")
        void anAmbiguousIdRefuses() {
            // The queue does not de-duplicate, on purpose: one contract can legitimately raise an
            // unmapped fee code on two different postings, and collapsing them would hide work
            // that has to be done separately. Two rows then share contractId:CATEGORY, which is
            // the pair ExceptionAcceptance matches on and therefore the id 06 § 6 addresses.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(ExceptionRecord.raise("C-0007", "RUN-202805-01",
                ExceptionCategory.UNMAPPED_FEE_CODE, "fee code LEGAL_FEE on posting 11",
                "PAYLOAD-11"));
            queue.raise(ExceptionRecord.raise("C-0007", "RUN-202805-01",
                ExceptionCategory.UNMAPPED_FEE_CODE, "fee code VALUATION_FEE on posting 12",
                "PAYLOAD-12"));
            RecordedRoutes routes = moduleOver(queue);

            assertThatThrownBy(() -> routes.post(
                ExceptionsModule.SUBTREE_PATH + "C-0007:UNMAPPED_FEE_CODE/resolve",
                "resolvedBy=dq.desk&note=mapped"))
                .as("answering with the first would have the note signed against whichever of the"
                    + " two the iteration order happened to reach")
                .isInstanceOf(FormBody.BadRequest.class)
                .hasMessageContaining("addresses 2 queued exceptions")
                .hasMessageContaining("posting 11")
                .hasMessageContaining("posting 12");
        }

        @Test
        @DisplayName("a resolution refuses to write over an approval, and says how to mean it")
        void aResolutionWillNotSilentlyEraseAnApproval() {
            // The back door out of the four-eyes control, if it were open: accept with a colleague,
            // then resolve over it alone. RESOLVED carries defectFixed = true, so the resolution
            // would discard the approver's name — the row holds ONE signatory column and that is
            // all the audit file will ever show — and lift the quarantine, claiming the contract
            // back into the reported population on one unverified signature. ExceptionQueue.resolve
            // refuses to re-work a worked row for exactly this reason; find() hands back the live
            // record, so the queue's own guard cannot see it and the module's has to.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(ExceptionRecord.raise("C-0007", "RUN-202805-01",
                    ExceptionCategory.NO_SOLUTION, "solve ended NO_SOLUTION on the reset leg",
                    "PAYLOAD-31")
                .acceptWithApproval("fin.controller", "immaterial for May"));
            RecordedRoutes routes = moduleOver(queue);

            String refused = routes.post(
                ExceptionsModule.SUBTREE_PATH + "C-0007:NO_SOLUTION/resolve",
                "resolvedBy=ops.analyst&note=calling it fixed").toString();

            assertThat(refused)
                .contains("\"worked\":false")
                .contains("is ACCEPTED_WITH_APPROVAL by fin.controller")
                .contains("replacing=ACCEPTED_WITH_APPROVAL");
            assertThat(queue.records().get(0).status())
                .as("the approval stands and the quarantine with it")
                .isEqualTo(ExceptionStatus.ACCEPTED_WITH_APPROVAL);
            assertThat(queue.records().get(0).resolvedBy()).isEqualTo("fin.controller");

            String acknowledged = routes.post(
                ExceptionsModule.SUBTREE_PATH + "C-0007:NO_SOLUTION/resolve",
                "resolvedBy=ops.analyst&note=schedule corrected at source"
                    + "&replacing=ACCEPTED_WITH_APPROVAL").toString();

            assertThat(acknowledged)
                .as("a defect genuinely fixed after being accepted is a real sequence, so the"
                    + " transition is available — deliberately, not silently")
                .contains("\"worked\":true")
                .contains("\"replacedWorking\":\"ACCEPTED_WITH_APPROVAL by fin.controller\"");
        }

        @Test
        @DisplayName("a queue of more than one row refuses to delegate a single signature")
        void aMultiRowQueueRefusesToDelegate() {
            // The engine's acceptance path signs for every row in its queue at once, which is
            // right for a console driving a one-contract queue and wrong for a signature that
            // names one exception. Forty open rows and one signature would become forty accepted
            // rows nobody put forward.
            ExceptionQueue queue = new ExceptionQueue();
            queue.raise(ExceptionRecord.raise("C-0007", "RUN-202805-01",
                ExceptionCategory.NO_SOLUTION, "solve ended NO_SOLUTION on the reset leg",
                "PAYLOAD-21"));
            queue.raise(ExceptionRecord.raise("C-0008", "RUN-202805-01",
                ExceptionCategory.MULTIPLE_ROOTS, "solve ended MULTIPLE_ROOTS on the step-up leg",
                "PAYLOAD-22"));
            RecordedRoutes routes = moduleOver(queue);

            String response = routes.post(
                ExceptionsModule.SUBTREE_PATH + "C-0007:NO_SOLUTION/accept",
                "acceptedBy=ops.analyst&approvedBy=fin.controller"
                    + "&reason=immaterial").toString();

            assertThat(response)
                .contains("\"worked\":false")
                .contains("this queue holds 2 exceptions")
                .contains("would accept 1 exception(s) nobody put forward");
            assertThat(queue.records().get(0).isOpen())
                .as("refused wholly: the row is untouched, because an acceptance recorded here and"
                    + " not on the engine's queue would move nothing at the close anyway")
                .isTrue();
        }
    }
}
