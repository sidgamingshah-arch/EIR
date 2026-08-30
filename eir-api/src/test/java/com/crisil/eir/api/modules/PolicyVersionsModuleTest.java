package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.policy.PolicyVersionsSurface;
import com.crisil.eir.api.store.Seed;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 06 § 5's policy-version lifecycle and FR-210's mandatory impact-preview gate, driven over real
 * HTTP on a real socket.
 *
 * <p><b>Why over a socket.</b> Same reason as {@code EirServerTest}, and one more that is specific
 * to this section: the deliverable here is a <em>status code</em>. 06 § 5 says "approval without an
 * impact preview returns 409", and a test that called the handler directly would assert on a field
 * of a return value while the byte on the wire could still be a 200 — which is exactly the failure
 * this section cannot afford, because a caller that trusts the code would file an unpreviewed
 * approval as done. Port 0 lets the OS pick, so this never collides with a developer's own server.
 *
 * <p>Assertions are on substrings of the JSON, because this module has no JSON parser. The
 * substrings are chosen to be ones a formatting change cannot accidentally satisfy: a named refusal
 * reason from {@code ActivationRefusalReason} or {@code TransitionRefusal}, an invariant id, a
 * specific rupee figure.
 *
 * <h2>Where the expected figures come from</h2>
 *
 * <p>None of them was read off a run of this code. The book is {@code Seed.book()}: three
 * contracts, of which <b>C-0001 and C-0002 each carry reference case 1's month-13 opening gross
 * carrying amount of 528,407.32</b> and each carries its solved EIR of 0.010421491800 periodic,
 * while C-0003 has no opening state at all (the FR-905 condition the seeded book exists to show).
 * So:
 *
 * <ul>
 *   <li>the measured portfolio is <b>1,056,814.64</b> — 528,407.32 + 528,407.32, C-0003 excluded
 *       because the contract master carries no balance for it;
 *   <li>the gross-carrying-amount-weighted average EIR is <b>0.010421491800</b> exactly: both
 *       weights carry the same rate, so (528,407.32·r + 528,407.32·r) / 1,056,814.64 = r for any r;
 *   <li>the checker's sign-off date is <b>2028-05-31</b>, the book's business date
 *       ({@code Seed.PERIOD_END}); and
 *   <li>a preview is stamped <b>2028-06-01T00:00:00Z</b>, the first instant after that date in UTC
 *       — see {@code PolicyVersionsSurface} on why this surface runs on the book's own as-at
 *       instead of a wall clock.
 * </ul>
 */
class PolicyVersionsModuleTest {

    /** A draft that takes effect after the book's position, so its honest preview is nil. */
    private static final String PROSPECTIVE_DRAFT =
        "id=POL-FEE-2029.1&kind=FEE_RULE_SET&description=fee+taxonomy+FY2029-30"
            + "&effectiveFrom=2029-04-01&maker=policy.maker";

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

    private Response draft(String form) throws IOException {
        return post(PolicyVersionsSurface.BASE, form);
    }

    private Response impactPreview(String id) throws IOException {
        return post(PolicyVersionsSurface.BASE + "/" + id + "/impact-preview", "");
    }

    private Response approve(String id, String checker) throws IOException {
        return post(PolicyVersionsSurface.BASE + "/" + id + "/approve", "checker=" + checker);
    }

    @Nested
    @DisplayName("FR-210's gate: approval without a stored impact preview is a 409")
    class TheMandatoryGate {

        @Test
        @DisplayName("approval with nothing on file is a 409 that names what is missing")
        void approvalWithNoStoredPreviewIsAConflict() throws IOException {
            draft(PROSPECTIVE_DRAFT);

            Response response = approve("POL-FEE-2029.1", "policy.checker");

            assertThat(response.status())
                .as("06 § 5: approval without an impact preview returns 409, and the code is the"
                    + " deliverable — a 200 carrying \"approved\":false would be filed as done")
                .isEqualTo(409);
            assertThat(response.body())
                .as("the refusal must be named, not merely counted: NO_PREVIEW_STORED is the one"
                    + " reason of the seven whose remedy is 'go and run the preview'")
                .contains("\"approved\":false")
                .contains("NO_PREVIEW_STORED")
                .contains("409 CONFLICT")
                .contains("/impact-preview");
            assertThat(response.body())
                .as("the gate's answer is asserted as invariant PG-1, so a control report has"
                    + " something to collect rather than a boolean nobody evaluated")
                .contains("\"id\":\"PG-1\"")
                .contains("\"satisfied\":false")
                .contains("no policy version effective without a current impact preview");
        }

        @Test
        @DisplayName("the refused approval writes nothing: the version is still a DRAFT after it")
        void aRefusedApprovalLeavesNoSignature() throws IOException {
            draft(PROSPECTIVE_DRAFT);
            approve("POL-FEE-2029.1", "policy.checker");

            Response listed = get(PolicyVersionsSurface.BASE);

            // A half-applied refusal is the dangerous shape: a version whose status claims a
            // checker signed it and whose evidence says nobody did. The checker offered on the
            // refused call must not appear as this version's checker.
            assertThat(listed.body())
                .contains("\"id\":\"POL-FEE-2029.1\"")
                .contains("\"status\":\"DRAFT\"");
            assertThat(listed.body())
                .as("no approval date may have been recorded by a refused approval")
                .doesNotContain("\"approvedOn\":\"2028-05-31\"");
        }

        @Test
        @DisplayName("approval after the preview is allowed, and records the checker and the date")
        void approvalAfterThePreviewIsAllowed() throws IOException {
            draft(PROSPECTIVE_DRAFT);
            impactPreview("POL-FEE-2029.1");

            Response response = approve("POL-FEE-2029.1", "policy.checker");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"approved\":true")
                .contains("\"status\":\"APPROVED\"")
                .contains("\"checker\":\"policy.checker\"")
                // The book's business date, Seed.PERIOD_END — not a wall-clock date, which would
                // make the gate compare a 2028 preview against a present-day approval.
                .contains("\"approvedOn\":\"2028-05-31\"");
            assertThat(response.body())
                .as("PG-1 passes once the preview is on file, and says which preview it relied on")
                .contains("\"id\":\"PG-1\"")
                .contains("\"satisfied\":true")
                .contains("ACTIVATION PERMITTED");
        }

        @Test
        @DisplayName("a self-approval is refused even with the preview on file, and case does not save it")
        void aSelfApprovalIsRefusedEvenWithAPreview() throws IOException {
            draft(PROSPECTIVE_DRAFT);
            impactPreview("POL-FEE-2029.1");

            // "Policy.Maker" against a maker of "policy.maker". Not somebody typing their own name
            // into the checker box — a case variant of one directory identity arriving through a
            // second channel, which is the route to self-approval that survives a four-eyes
            // control in a real bank (FourEyes.identityKey, ApprovalRecord.isSelfApprovalOf).
            Response response = approve("POL-FEE-2029.1", "Policy.Maker");

            assertThat(response.status())
                .as("the impact-preview limb permitted this, so the code stays 200: 06 § 5 attaches"
                    + " 409 to the missing preview and to nothing else")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"approved\":false")
                .contains("SELF_APPROVAL")
                .contains("\"permitted\":true");

            assertThat(get(PolicyVersionsSurface.BASE).body())
                .as("and it must not have been signed: still a DRAFT")
                .contains("\"id\":\"POL-FEE-2029.1\"")
                .contains("\"status\":\"DRAFT\"");
        }

        @Test
        @DisplayName("a retrospective draft is not certified, and its approval stays blocked")
        void aRetrospectiveDraftIsNotCertified() throws IOException {
            // Effective 2028-04-01, before the book's position of 2028-05-31: this draft would
            // restate periods already recognised, and this API carries no numeric draft content to
            // compute the catch-up from. A nil preview here would be the record the gate exists to
            // reject — stored, correctly named, and false.
            draft("id=POL-CURVE-2028.2&kind=BEHAVIOURAL_CURVE&description=CPR+revision"
                + "&effectiveFrom=2028-04-01&maker=policy.maker");

            Response preview = impactPreview("POL-CURVE-2028.2");

            assertThat(preview.status())
                .as("a refusal to certify is an answer, not a client error")
                .isEqualTo(200);
            assertThat(preview.body())
                .contains("\"previewed\":false")
                .contains("RETROSPECTIVE_DRAFT_NOT_QUANTIFIED")
                .contains("restates periods already recognised");

            assertThat(approve("POL-CURVE-2028.2", "policy.checker").status())
                .as("nothing was stored, so the gate must still refuse — a preview endpoint that"
                    + " declined to certify and then let the approval through would be worse than"
                    + " no endpoint")
                .isEqualTo(409);
        }

        @Test
        @DisplayName("approving twice does not take a second signature")
        void aSecondApprovalIsRefused() throws IOException {
            draft(PROSPECTIVE_DRAFT);
            impactPreview("POL-FEE-2029.1");
            approve("POL-FEE-2029.1", "policy.checker");

            Response again = approve("POL-FEE-2029.1", "second.checker");

            assertThat(again.status()).isEqualTo(200);
            assertThat(again.body())
                .as("only one signature can be stored, so a second checker believing they signed"
                    + " something is refused by name")
                .contains("\"approved\":false")
                .contains("NOT_A_TRANSITION");
        }
    }

    @Nested
    @DisplayName("the list and the draft: 06 § 5's first two rows")
    class DraftAndList {

        @Test
        @DisplayName("the list carries the versions in force over the book, with their status")
        void theListCarriesTheVersionsInForce() throws IOException {
            Response response = get(PolicyVersionsSurface.BASE);

            assertThat(response.status()).isEqualTo(200);
            // Seed.policies(): the fee taxonomy, the tier thresholds and the routing table, all
            // EFFECTIVE. A list endpoint that showed only what this process had drafted would
            // present an empty policy file for a book whose every figure cites one.
            assertThat(response.body())
                .contains("\"count\":3")
                .contains("POL-FEE-2028.1")
                .contains("POL-TIER-2028.1")
                .contains("\"status\":\"EFFECTIVE\"")
                .contains("\"specSection\":\"06 § 5\"");
        }

        @Test
        @DisplayName("a new version is drafted in DRAFT, and told what stands between it and approval")
        void aDraftIsCreatedInDraftStatus() throws IOException {
            Response response = draft(PROSPECTIVE_DRAFT);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"drafted\":true")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"impactPreviewRequired\":true")
                .contains("\"impactPreviewsStored\":0")
                .contains("NO_PREVIEW_STORED");
        }

        @Test
        @DisplayName("a duplicate version id is a refusal value, not a second kind of 409")
        void aDuplicateIdIsRefusedAsAValue() throws IOException {
            draft(PROSPECTIVE_DRAFT);

            Response again = draft(PROSPECTIVE_DRAFT);

            assertThat(again.status())
                .as("409 on this surface must mean one thing — approval without a preview — or a"
                    + " caller cannot act on the code without parsing the body")
                .isEqualTo(200);
            assertThat(again.body())
                .contains("\"drafted\":false")
                .contains("VERSION_ID_ALREADY_HELD");
        }

        @Test
        @DisplayName("the preview reports the portfolio it measured, every figure as a string")
        void thePreviewReportsTheMeasuredPortfolio() throws IOException {
            draft(PROSPECTIVE_DRAFT);

            Response response = impactPreview("POL-FEE-2029.1");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"previewed\":true")
                .contains("\"generatedAt\":\"2028-06-01T00:00:00Z\"")
                .contains("\"portfolioAsOf\":\"2028-05-31\"");
            // 528,407.32 + 528,407.32 = 1,056,814.64, and C-0003 excluded because the master
            // carries no opening balance for it. Three measured, two on file: both counts are
            // published, and the difference is the part a checker should ask about (FR-905).
            assertThat(response.body())
                .contains("\"contractsMeasured\":3")
                .contains("\"openingStatesOnFile\":2")
                .contains("\"totalGrossCarryingAmount\":\"1056814.64\"")
                .contains("\"weightedAverageEir\":\"0.010421491800\"");
            // A nil movement, stated against a real portfolio rate rather than as two zeroes.
            assertThat(response.body())
                .contains("\"contractsAffected\":0")
                .contains("\"grossCarryingAmountDelta\":\"0.00\"")
                .contains("\"weightedAverageEirBefore\":\"0.010421491800\"")
                .contains("\"weightedAverageEirAfter\":\"0.010421491800\"")
                .contains("\"noMovement\":true")
                .contains("\"coherent\":true");
            assertThat(response.body())
                .as("figures cross the wire as JSON strings: an unquoted rate would have gone"
                    + " through a double in the reader and lost the twelve-place statement")
                .doesNotContain("\"weightedAverageEir\":0.0104");
        }

        @Test
        @DisplayName("the preview binds to the draft's content, so the id alone cannot satisfy the gate")
        void thePreviewBindsToTheDraftContent() throws IOException {
            Response first = draft(PROSPECTIVE_DRAFT);
            Response second = draft(
                "id=POL-FEE-2029.2&kind=FEE_RULE_SET&description=fee+taxonomy+FY2029-30"
                    + "&effectiveFrom=2029-04-01&maker=policy.maker&content=DSA_COMM%3DSELLING");

            // Two drafts differing only in their content text must fingerprint differently, or an
            // edit would be invisible to the staleness limb of the gate and a preview of the old
            // content would go on looking current (ActivationRefusalReason.STALE_DRAFT_PREVIEW).
            String firstPrint = between(first.body(), "\"draftFingerprint\":\"", "\"");
            String secondPrint = between(second.body(), "\"draftFingerprint\":\"", "\"");
            assertThat(firstPrint).hasSize(64).isNotEqualTo(secondPrint);
            assertThat(secondPrint).hasSize(64);
        }
    }

    @Nested
    @DisplayName("the edge does not invent answers it was not given")
    class TheEdge {

        @Test
        @DisplayName("an unknown version id is a 404, not a refusal a caller would read as assessed")
        void anUnknownVersionIsANotFound() throws IOException {
            Response response = approve("POL-DOES-NOT-EXIST", "policy.checker");

            assertThat(response.status())
                .as("a 200 saying \"not approved\" would let a caller with a typo conclude their"
                    + " version had been assessed and rejected")
                .isEqualTo(404);
            assertThat(response.body()).contains("POL-DOES-NOT-EXIST");
        }

        @Test
        @DisplayName("the wrong verb on an approval path is a 405, not a silent 404")
        void theWrongVerbIsRefused() throws IOException {
            Response response = get(PolicyVersionsSurface.BASE + "/POL-FEE-2029.1/approve");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("POST only");
        }

        @Test
        @DisplayName("an unmapped policy kind is a 400 that lists the kinds, never a default")
        void anUnknownKindIsRefused() throws IOException {
            Response response = draft("id=POL-X&kind=MYSTERY&description=d"
                + "&effectiveFrom=2029-04-01&maker=policy.maker");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .as("defaulting the kind would put a fee taxonomy change into force as a"
                    + " behavioural curve")
                .contains("bad request")
                .contains("FEE_RULE_SET")
                .contains("BEHAVIOURAL_CURVE");
        }

        @Test
        @DisplayName("a version with no maker is a 400: an unattributed change has no author")
        void aDraftWithNoMakerIsRefused() throws IOException {
            Response response = draft("id=POL-Y&kind=FEE_RULE_SET&description=d"
                + "&effectiveFrom=2029-04-01");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("maker");
        }

        @Test
        @DisplayName("a malformed effective date is a 400 rather than a defaulted today")
        void aMalformedDateIsRefused() throws IOException {
            Response response = draft("id=POL-Z&kind=FEE_RULE_SET&description=d"
                + "&effectiveFrom=next+April&maker=policy.maker");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("effectiveFrom").contains("2029-04-01");
        }

        @Test
        @DisplayName("an unknown action under a version id is a 404 listing the four routes")
        void anUnknownActionIsANotFound() throws IOException {
            Response response = post(PolicyVersionsSurface.BASE + "/POL-FEE-2029.1/activate", "");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body())
                .contains("impact-preview")
                .contains("approve");
        }
    }

    @Nested
    @DisplayName("the route seam this section cannot be served through")
    class TheSeam {

        /**
         * A {@code Routes} with nothing behind it — a static class, so it holds no reference to the
         * enclosing test instance.
         *
         * <p>Static on purpose, and the reason is the control being tested. An anonymous or inner
         * class declared here would capture this test object, which holds the {@code EirServer},
         * which holds the shared {@code HttpServer} — so the recovery would <em>succeed</em>
         * through the test's own field and this case would prove nothing.
         */
        private static final class BareRoutes implements Routes {
            @Override
            public void get(String path, Function<HttpExchange, Json.Obj> handler) {
            }

            @Override
            public void post(String path, Function<FormBody, Json.Obj> handler) {
            }
        }

        @Test
        @DisplayName("a seam hiding no server is fatal at construction, with the remedy in the message")
        void anUnrecoverableSeamIsFatal() {
            PolicyVersionsModule module =
                new PolicyVersionsModule(new EirService(Seed.book()));

            assertThat(PolicyVersionsModule.sharedServerBehind(new BareRoutes())).isEmpty();
            assertThatThrownBy(() -> module.register(new BareRoutes()))
                .as("registering nothing would 404 the approval endpoint, and a 404 there is"
                    + " indistinguishable from an approval gate that is present and permissive")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("route(path")
                .hasMessageContaining("FR-210");
        }

        @Test
        @DisplayName("the real seam does hide the shared server, so the section is actually served")
        void theRealSeamIsRecovered() throws IOException {
            // If the recovery had failed, EirServer's constructor would have thrown in @BeforeEach
            // and every test here would error. Asserted directly as well, so the reason a failure
            // appears is named rather than inferred from a stack trace in setup.
            assertThat(get(PolicyVersionsSurface.BASE).status()).isEqualTo(200);
            assertThat(new PolicyVersionsModule(new EirService(Seed.book())).specSection())
                .isEqualTo("06 § 5");
        }
    }

    /** The text between two markers, for pulling one field out of a body with no JSON parser. */
    private static String between(String body, String after, String before) {
        int start = body.indexOf(after);
        assertThat(start).as("'%s' must appear in %s", after, body).isGreaterThanOrEqualTo(0);
        start += after.length();
        int end = body.indexOf(before, start);
        assertThat(end).as("'%s' must close in %s", before, body).isGreaterThan(start);
        return body.substring(start, end);
    }
}
