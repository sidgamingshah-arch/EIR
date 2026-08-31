package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.PathOnlyExchange;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.transition.TransitionBook;
import com.crisil.eir.api.store.Seed;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The five transition endpoints of 06 § 8, driven over real HTTP on a real socket.
 *
 * <p><b>Over a socket, on port 0, for the reasons {@code EirServerTest} gives.</b> Every defect this
 * layer can introduce lives between the service and the wire: a figure emitted as a JSON number and
 * rounded by the reader, an engine refusal mapped onto a 500, a route registered on a path nobody
 * asked for. A test that calls the module's methods misses all three. Port 0 lets the OS pick, so
 * this runs anywhere and never collides with a developer's own server.
 *
 * <p>Assertions are on substrings of the JSON rather than a parsed tree, because this module has no
 * JSON parser and writing one for the test would be the largest untested thing in it. Every
 * substring is chosen so a formatting change cannot accidentally satisfy it: a specific rupee
 * figure, a specific invariant id, a specific refusal phrase.
 *
 * <p><b>Every expected figure is derived by hand from {@link TransitionBook}'s stated inputs.</b> The
 * derivations are written out at each assertion. None of them was read off a run of the code — the
 * total to opening retained earnings in particular is the sum of seven differences computed by
 * subtraction below, and if the code and the arithmetic disagree the code is wrong.
 *
 * <p><b>The seeded programme is deliberately not clean.</b> Two of the seven valuations rest on the
 * paragraph 19 presumption with no rebuttal evidence, one surviving cohort is queued behind a
 * 1,204,338-contract cohort that runs off in 2029, one deemed cohort's derivation is unsigned, and
 * one contract has no recorded ECL discount basis at all. A demonstration book on which every
 * control passes would prove that the controls run and nothing about what they are for.
 */
class TransitionModuleTest {

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

    /** How many times a substring occurs — used to assert a field appears once per obligation. */
    private static int occurrences(String body, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = body.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** Files both missing rebuttal references, which is what turns TF-1 green. */
    private void fileBothRebuttalReferences() throws IOException {
        post("/api/transition/fair-value-run",
            "evidenceForContract=C-0003&paragraph19EvidenceRef=WP-ACPIR19-PL-03");
        post("/api/transition/fair-value-run",
            "evidenceForContract=C-0007&paragraph19EvidenceRef=WP-ACPIR19-PL-04");
    }

    @Nested
    @DisplayName("POST /api/transition/fair-value-run — the ACPIR 19 day-1 valuation of the book")
    class TheFairValueRun {

        @Test
        @DisplayName("the total to opening retained earnings is withheld while two presumptions"
            + " name no rebuttal evidence")
        void theRunWithholdsTheTotalWhileTwoPresumptionsAreUnevidenced() throws IOException {
            Response response = post("/api/transition/fair-value-run", "");

            assertThat(response.status())
                .as("a refusal is a value in this engine: it comes back on a 200, not a 4xx")
                .isEqualTo(200);
            assertThat(response.body())
                .as("C-0003 and C-0007 take carrying cost as best evidence with no file behind it,"
                    + " so the run has not met 08 Phase 4's exit gate")
                .contains("\"published\":false")
                .contains("\"exitGateMet\":false")
                .contains("\"totalDifferenceToOpeningRetainedEarnings\":null")
                .contains("\"paragraph19PresumptionsApplied\":4")
                .contains("\"paragraph19PresumptionsUnevidenced\":2")
                .contains("C-0003")
                .contains("C-0007");
            // TF-1 publishes a COUNT as its deviation, and two unevidenced rows rather than one is
            // the point: an implementation that reported the first breach's 1 would look correct on
            // a single-bad-row fixture. TransitionValuationRun aggregates for exactly this reason.
            assertThat(response.body())
                .contains("\"id\":\"TF-1\"")
                .contains("\"deviation\":\"2\"");
            // The figure the run would have published is −44,250.00 (derived in the next test). It
            // must appear NOWHERE in this response — not in the figure, and not inside the prose
            // "describe" field, which is where TransitionValuationRun.describe() would leak it.
            assertThat(response.body())
                .as("a withheld figure handed back inside a prose field is still published")
                .doesNotContain("44250.00");
            // The completeness leg of the gate is separately reported and separately met: all seven
            // contracts on the legacy master were valued.
            assertThat(response.body())
                .contains("\"legacyBookSize\":7")
                .contains("\"valuedContracts\":7")
                .contains("\"unvaluedContracts\":0")
                .contains("\"completeOverTheBook\":true");
        }

        @Test
        @DisplayName("the total publishes once both rebuttal references are on file, and it is"
            + " −44,250.00")
        void theTotalPublishesOnceBothRebuttalReferencesAreOnFile() throws IOException {
            post("/api/transition/fair-value-run", "");

            post("/api/transition/fair-value-run",
                "evidenceForContract=C-0003&paragraph19EvidenceRef=WP-ACPIR19-PL-03");
            Response response = post("/api/transition/fair-value-run",
                "evidenceForContract=C-0007&paragraph19EvidenceRef=WP-ACPIR19-PL-04");

            assertThat(response.status()).isEqualTo(200);
            // By hand, from TransitionBook's seven stated valuations, fair value less carrying:
            //   C-0001    962,500.00 − 1,000,000.00 = −37,500.00
            //   C-0002    528,407.32 −   528,407.32 =       0.00
            //   C-0003    250,000.00 −   250,000.00 =       0.00
            //   C-0004    412,000.00 −   400,000.00 = +12,000.00
            //   C-0005    731,250.00 −   750,000.00 = −18,750.00
            //   C-0006    300,000.00 −   300,000.00 =       0.00
            //   C-0007    150,000.00 −   150,000.00 =       0.00
            //   total     −37,500.00 + 12,000.00 − 18,750.00        = −44,250.00
            // Negative, which for a legacy fixed-rate book measured at a higher current market rate
            // is the ordinary direction. A trailing 999999 tail or a lost paise digit here means the
            // figure went through a double somewhere in this layer.
            assertThat(response.body())
                .contains("\"published\":true")
                .contains("\"totalDifferenceToOpeningRetainedEarnings\":\"-44250.00\"")
                .contains("\"withheld\":null")
                .contains("\"paragraph19PresumptionsUnevidenced\":0")
                .contains("\"exitGateMet\":true");
            // The destination is named in the response, because ACPIR 19's whole accounting content
            // is that this figure goes to the opening balance and not to a period result.
            assertThat(response.body())
                .contains("\"differenceDestination\":\"OPENING_RETAINED_EARNINGS\"");
        }

        @Test
        @DisplayName("the technique mix is published: four of seven rest on the presumption")
        void theTechniqueMixIsPublished() throws IOException {
            Response response = post("/api/transition/fair-value-run", "");

            // One quoted price (C-0004), two discounted cash flows (C-0001, C-0005) and four
            // carrying-cost presumptions (C-0002, C-0003, C-0006, C-0007). The mix is the
            // disclosure: a run that is almost all presumption has measured almost nothing at fair
            // value, and that fact is invisible in a total adjustment.
            assertThat(response.body())
                .contains("\"technique\":\"QUOTED_PRICE\",\"contracts\":1")
                .contains("\"technique\":\"DISCOUNTED_CASH_FLOW\",\"contracts\":2")
                .contains("\"technique\":\"CARRYING_COST_AS_BEST_EVIDENCE\",\"contracts\":4");
        }

        @Test
        @DisplayName("BM-1 comes back with the run and is deliberately not part of the exit gate")
        void belowMarketDestinationsAreReportedButNotFoldedIntoTheExitGate() throws IOException {
            fileBothRebuttalReferences();
            Response response = post("/api/transition/fair-value-run", "");

            // C-0009's 250,000.00 day-1 shortfall has no destination and no Board position, so BM-1
            // fails — and the exit gate is met anyway. 07 § 4.1.1: concessional lending does not stop
            // at the transition, so BM-1 never ends and cannot be a leg of a phase's exit gate.
            assertThat(response.body())
                .contains("\"id\":\"BM-1\"")
                .contains("C-0009")
                .contains("\"exitGateMet\":true")
                .contains("\"belowMarketOriginations\":2");
        }

        @Test
        @DisplayName("a rebuttal reference against a discounted-cash-flow row is a 400")
        void evidenceAgainstADiscountedCashFlowRowIsRefused() throws IOException {
            Response response = post("/api/transition/fair-value-run",
                "evidenceForContract=C-0001&paragraph19EvidenceRef=WP-X");

            // C-0001 was modelled, not presumed. A rebuttal reference on it documents a decision
            // that was never taken, which TransitionFairValue refuses to construct at all — so there
            // is no engine answer to return and 400 is the honest status.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("DISCOUNTED_CASH_FLOW")
                .contains("a decision that was not taken");
        }

        @Test
        @DisplayName("a rebuttal reference for a contract this book does not value is a 400")
        void evidenceForAnUnvaluedContractIsRefused() throws IOException {
            Response response = post("/api/transition/fair-value-run",
                "evidenceForContract=C-9999&paragraph19EvidenceRef=WP-X");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("C-9999")
                .contains("carries no transition valuation");
        }

        @Test
        @DisplayName("a reference with no contract named is a 400 rather than a silent no-op")
        void aReferenceWithNoContractIsRefused() throws IOException {
            Response response = post("/api/transition/fair-value-run",
                "evidenceForContract=C-0003");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("paragraph19EvidenceRef");
        }
    }

    @Nested
    @DisplayName("GET /api/transition/fair-value/{contractId} — and the two different refusals")
    class OneContractsFairValue {

        @Test
        @DisplayName("an evidenced valuation publishes its difference: C-0001 at −37,500.00")
        void anEvidencedValuationPublishesItsDifference() throws IOException {
            Response response = get("/api/transition/fair-value/C-0001");

            assertThat(response.status()).isEqualTo(200);
            // 962,500.00 − 1,000,000.00 = −37,500.00, by hand from the two stated inputs. Both
            // figures cross the wire as JSON strings; a bare number here would be an IEEE 754 double
            // in every browser that reads it.
            assertThat(response.body())
                .contains("\"kind\":\"ACPIR_19_TRANSITION\"")
                .contains("\"preTransitionCarryingAmount\":\"1000000.00\"")
                .contains("\"fairValue\":\"962500.00\"")
                .contains("\"differenceToOpeningRetainedEarnings\":\"-37500.00\"")
                .contains("\"published\":true")
                .contains("\"valuationTechnique\":\"DISCOUNTED_CASH_FLOW\"")
                .contains("\"discountRateUsed\":\"0.011500000000\"")
                .contains("\"appliesParagraph19Presumption\":false");
        }

        @Test
        @DisplayName("an unevidenced paragraph 19 presumption refuses rather than publishing")
        void anUnevidencedPresumptionRefusesRatherThanPublishing() throws IOException {
            Response response = get("/api/transition/fair-value/C-0003");

            assertThat(response.status())
                .as("still 200: the engine answered, and its answer is a refusal")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"appliesParagraph19Presumption\":true")
                .contains("\"paragraph19EvidenceRef\":null")
                .contains("\"published\":false")
                .contains("\"differenceToOpeningRetainedEarnings\":null")
                .contains("\"id\":\"TF-1\"")
                .contains("\"satisfied\":false");
            // The figure NOT published is 0.00: fair value equals the carrying amount by
            // construction when carrying cost is taken as best evidence. A nil that means "nothing
            // was measured" is indistinguishable from a nil that was measured and found nothing, and
            // this figure goes to equity. The assertion is on the exact rendering the published
            // branch would have emitted, so removing the withholding branch turns this test red.
            assertThat(response.body())
                .as("0.00 here would put an unmeasured figure into opening retained earnings")
                .doesNotContain("\"differenceToOpeningRetainedEarnings\":\"0.00\"")
                .doesNotContain("to opening retained earnings INR 0.00");
        }

        @Test
        @DisplayName("a below-market day-1 shortfall publishes the figure and refuses the"
            + " destination — the opposite treatment, on purpose")
        void aBelowMarketShortfallPublishesTheFigureAndRefusesTheDestination() throws IOException {
            Response response = get("/api/transition/fair-value/C-0009");

            assertThat(response.status()).isEqualTo(200);
            // 5,000,000.00 disbursed less a fair value of 4,750,000.00 = a 250,000.00 shortfall, and
            // the concession spread is 0.009000000000 − 0.007500000000 = 0.001500000000 monthly. The
            // shortfall WAS measured, by discounting the concessional flows at a market rate, so it
            // is published. What is missing is the Board position saying where it goes.
            assertThat(response.body())
                .contains("\"kind\":\"BELOW_MARKET_ORIGINATION\"")
                .contains("\"dayOneShortfall\":\"250000.00\"")
                .contains("\"concessionSpread\":\"0.001500000000\"")
                .contains("\"published\":true")
                .contains("\"dayOneDifferenceDestination\":null")
                .contains("\"destinationApproved\":false")
                .contains("\"boardPosition\":null")
                .contains("\"id\":\"BM-1\"")
                .contains("\"satisfied\":false");
            // The destination is undecided, so the response must not claim the shortfall lands
            // anywhere — a boolean false would have read as "outside the current period's result",
            // which is a claim about the accounting rather than an absence of one.
            assertThat(response.body()).contains("\"differenceLands\":null");
        }

        @Test
        @DisplayName("an approved below-market origination cites the Board position that authorises"
            + " its destination")
        void anApprovedBelowMarketOriginationCitesItsBoardPosition() throws IOException {
            Response response = get("/api/transition/fair-value/C-0008");

            // 2,000,000.00 − 1,640,000.00 = a 360,000.00 shortfall; the spread is
            // 0.008000000000 − 0.004250000000 = 0.003750000000 monthly. Reference § 5 item 11: the
            // shortfall is employee compensation, not a lending loss, and it lands in the current
            // period because a June 2027 origination is a current-period event.
            assertThat(response.body())
                .contains("\"dayOneShortfall\":\"360000.00\"")
                .contains("\"concessionSpread\":\"0.003750000000\"")
                .contains("\"dayOneDifferenceDestination\":\"EMPLOYEE_BENEFIT_COST\"")
                .contains("\"destinationApproved\":true")
                .contains("\"boardPosition\":\"POS-DAY1-2027.1\"")
                .contains("\"differenceLands\":\"CURRENT_PERIOD_RESULT\"")
                .contains("\"id\":\"BM-1\"")
                .contains("\"satisfied\":true");
            // The loan is measured at the MARKET rate, never the concession: amortising the
            // concessional rate would recognise interest income the bank never priced for.
            assertThat(response.body())
                .contains("\"effectiveInterestRate\":\"0.008000000000\"")
                .contains("\"contractualRate\":\"0.004250000000\"");
        }

        @Test
        @DisplayName("a contract the transition book does not carry is a 404 naming the path")
        void anUnknownContractIsANotFound() throws IOException {
            Response response = get("/api/transition/fair-value/C-9999");

            // Not a 200 carrying "found": false. The per-contract routes are bound at registration,
            // so an unknown contract has no route and falls through to the server's own handler.
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).contains("C-9999");
        }
    }

    @Nested
    @DisplayName("GET /api/transition/legacy-cohorts — run-off against 31 March 2030, and method")
    class TheLegacyMigrationQueue {

        @Test
        @DisplayName("LC-1 fails because a survivor is queued behind a 1.2-million-contract cohort"
            + " that runs off in 2029")
        void lcOneFailsBecauseASurvivorIsQueuedBehindACohortThatRunsOffFirst() throws IOException {
            Response response = get("/api/transition/legacy-cohorts");

            assertThat(response.status()).isEqualTo(200);
            // The lowest priority among cohorts that do NOT survive is 2 (VEHICLE-2021-2023, running
            // off 2029-06-30). GOLD-REVOLVING survives to 2031-12-31 and sits at priority 3, so it
            // is queued behind a cohort that will have gone: exactly one inversion, deviation 1.
            // Ordered by contract count the 1,204,338-contract vehicle book would go first, and it
            // is the one that never needs a reconstructed rate at all.
            assertThat(response.body())
                .contains("\"id\":\"LC-1\"")
                .contains("\"satisfied\":false")
                .contains("\"deviation\":\"1\"")
                .contains("GOLD-REVOLVING");
            assertThat(response.body())
                .as("the roadmap's own sentence, reported as plain data rather than an invariant")
                .contains("\"wastedReconstructionEffort\":[\"VEHICLE-2021-2023");
        }

        @Test
        @DisplayName("the contract count is published as supplied, never as derived from the"
            + " cohort definition")
        void theContractCountIsPublishedAsSuppliedRatherThanDerived() throws IOException {
            Response response = get("/api/transition/legacy-cohorts");

            // LegacyCohort.definition carries what the cohort selects as free text — the schema
            // holds it as JSONB — and nothing in this engine evaluates it. So the count is a figure
            // the programme stated, and the response says so rather than letting a reader take it as
            // a reconciliation of the definition against the contract master.
            assertThat(response.body())
                .contains("\"membershipEvaluated\":false")
                .contains("\"definitionEvaluated\":false")
                .contains("\"contractCountIsSupplied\":true")
                .contains("\"contractCount\":1204338")
                .contains("nothing in this engine evaluates it");
        }

        @Test
        @DisplayName("a cohort running off ON 31 March 2030 has met the deadline; one running to"
            + " 2034 has not")
        void aCohortRunningOffOnTheDeadlineHasMetIt() throws IOException {
            Response response = get("/api/transition/legacy-cohorts");

            // SME-TERM-PRE-2018 runs off exactly on 2030-03-31. The obligation is to be on the EIR
            // BY that date, and an exposure that ends on it was on whatever basis it was on for its
            // whole life — so it does not survive, and reconstruction capacity spent on it buys
            // nothing. The schema draws the boundary the same way (> DATE '2030-03-31'); having the
            // two disagree by a day would put a cohort in the queue in one place and out of it in
            // the other. A boundary nobody exercises is a boundary nobody knows the sign of.
            assertThat(response.body()).contains(
                "\"expectedRunoffDate\":\"2030-03-31\",\"survivesAcpir50Deadline\":false");
            assertThat(response.body()).contains(
                "\"expectedRunoffDate\":\"2034-03-31\",\"survivesAcpir50Deadline\":true");
        }

        @Test
        @DisplayName("contractsRequiringMigration counts only the survivors: 646,055")
        void contractsRequiringMigrationCountsOnlySurvivors() throws IOException {
            Response response = get("/api/transition/legacy-cohorts");

            // By hand: the two surviving cohorts are HL-PRE-2020 (412,905) and GOLD-REVOLVING
            // (233,150). 412,905 + 233,150 = 646,055. VEHICLE-2021-2023's 1,204,338 and
            // SME-TERM-PRE-2018's 12,004 are excluded because those exposures have gone by the
            // deadline — which is why this figure, and not the book's 1,862,397, is the one the
            // migration has to work through.
            assertThat(response.body())
                .contains("\"contractsRequiringMigration\":646055")
                .contains("\"cohortCount\":4")
                .contains("\"acpir50Deadline\":\"2030-03-31\"");
        }

        @Test
        @DisplayName("DE-1 fails on the gold cohort's unsigned derivation, and names the preparer")
        void deOneFailsOnTheUnapprovedGoldDerivation() throws IOException {
            Response response = get("/api/transition/legacy-cohorts");

            // Two cohorts are on a deemed basis. SME-TERM-PRE-2018's derivation was approved by
            // transition.committee on 2027-02-15; GOLD-REVOLVING's was prepared by
            // transition.analyst and signed by nobody. A deemed rate recognises income on an
            // assumption every period for the rest of the exposure's life, so preparing one is
            // analysis and measuring 233,150 contracts on it is a decision.
            assertThat(response.body())
                .contains("\"id\":\"DE-1\"")
                .contains("1 of 2 deemed-EIR cohorts would recognise income on an unsigned")
                .contains("transition.analyst");
        }
    }

    @Nested
    @DisplayName("POST /api/transition/legacy-cohorts/{id}/migrate — reconstruct, or deem")
    class MigratingACohort {

        @Test
        @DisplayName("a deemed migration on an unsigned derivation is recorded and refused as"
            + " unmeasurable")
        void aDeemedMigrationOnAnUnsignedDerivationIsRefusedAsUnmeasurable() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/GOLD-REVOLVING/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead");

            assertThat(response.status())
                .as("recorded, not rejected: an unapproved derivation in flight is the normal state"
                    + " of one, and refusing to record it would make the remaining work invisible")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"measurableOnThisBasis\":false")
                .contains("\"deemedRate\":\"0.014500000000\"")
                .contains("\"approved\":false")
                .contains("\"id\":\"DE-1\"")
                .contains("\"satisfied\":false");
        }

        @Test
        @DisplayName("a deemed migration on an approved derivation turns DE-1 green")
        void aDeemedMigrationOnAnApprovedDerivationTurnsDeOneGreen() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/GOLD-REVOLVING/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRate=0.014500000000&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=the+source+system+was+retired+in+2019"
                    + "&documentedBasis=FY19+renewal+pricing+grid"
                    + "&preparedBy=transition.analyst"
                    + "&approvedBy=transition.committee&approvedOn=2027-03-31");

            assertThat(response.status()).isEqualTo(200);
            // Both deemed cohorts now have a signed derivation, so DE-1's deviation is 0. This is
            // the green side of the control, reached over HTTP: a control only ever seen red is a
            // control nobody knows can pass.
            assertThat(response.body())
                .contains("\"measurableOnThisBasis\":true")
                .contains("\"refusals\":[]")
                .contains("\"derivationSupplied\":true")
                .contains("\"id\":\"DE-1\"")
                .contains("all on an approved derivation");
            // LC-1 is untouched: the queue's ORDER is wrong, and migrating a cohort does not
            // reorder it. Two controls, two remedies, and the response does not conflate them.
            assertThat(response.body())
                .contains("\"id\":\"LC-1\"")
                .contains("\"deviation\":\"1\"");
        }

        @Test
        @DisplayName("a derivation prepared and approved by one person is a 400")
        void aSelfApprovedDerivationIsABadRequest() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/SME-TERM-PRE-2018/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRate=0.011200000000&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=records+archived"
                    + "&documentedBasis=pricing+grid&preparedBy=transition.analyst"
                    + "&approvedBy=transition.analyst&approvedOn=2027-03-31");

            // DeemedEirDerivation refuses to construct: the person who assumed the rate is not a
            // second opinion on recognising income from it. The record throws, so there is no engine
            // answer to return on a 200 — the caller sent a combination this system does not
            // represent, which is what 400 means here.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("prepared and approved by")
                .contains("transition.analyst");
        }

        @Test
        @DisplayName("an approver with no approval date is a 400: half an approval is neither")
        void anApproverWithNoDateIsABadRequest() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/SME-TERM-PRE-2018/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRate=0.011200000000&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=records+archived"
                    + "&documentedBasis=pricing+grid&preparedBy=transition.analyst"
                    + "&approvedBy=transition.committee");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("an approval is a person and a date");
        }

        @Test
        @DisplayName("a deemed rate outside Rate's domain is a 400, not a 500")
        void aDeemedRateOutsideTheDomainIsABadRequest() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/GOLD-REVOLVING/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRate=-2&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=records+archived"
                    + "&documentedBasis=pricing+grid&preparedBy=transition.analyst");

            // Rate refuses a periodic rate at or below minus 100%. The first cut built the Rate
            // above the try/catch, so this answered 500 with a domain message in it — this layer
            // reporting a caller's mistake as an engine defect, which is exactly what EirServer's
            // three-way status mapping exists to prevent.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("bad request").contains("-2");
        }

        @Test
        @DisplayName("a derivation whose rate field is misspelled is a 400, never a silent drop")
        void aDerivationWithAMisspelledRateFieldIsRefused() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/GOLD-REVOLVING/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRAte=0.014500000000&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=the+source+system+was+retired+in+2019"
                    + "&documentedBasis=FY19+renewal+pricing+grid"
                    + "&preparedBy=transition.analyst"
                    + "&approvedBy=transition.committee&approvedOn=2027-03-31");

            // The worst possible outcome here is a 200. The first cut gated the derivation branch on
            // deemedRate alone, so this request — a complete, APPROVED derivation with one field
            // misspelled — was discarded whole and answered 200 reporting the old unsigned
            // derivation as governing and DE-1 still red. An operator who came to sign off the rate
            // would have been told the rate was unsigned, with nothing saying their input was
            // dropped. Any derivation field now triggers the build, so the missing one is named.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("deemedRate").contains("absent");
        }

        @Test
        @DisplayName("derivation fields on a full-reconstruction request are a 400, not ignored")
        void derivationFieldsOnAReconstructionAreRefused() throws IOException {
            Response response = post(
                "/api/transition/legacy-cohorts/HL-PRE-2020/migrate",
                "method=FULL_RECONSTRUCTION&migratedBy=transition.lead"
                    + "&deemedRate=0.012000000000&basis=ORIGINATION_PRICING_GRID"
                    + "&infeasibilityReason=x&documentedBasis=y&preparedBy=transition.analyst");

            // Either the method is wrong or the derivation belongs to a working that was not used,
            // and both are things a reader would take as the basis of the rate — the same argument
            // TransitionFairValue makes for a discount rate on a quoted-price row.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("FULL_RECONSTRUCTION")
                .contains("a working that was not used");
        }

        @Test
        @DisplayName("the cohort listing names who migrated the cohort, not merely that somebody did")
        void theCohortListingNamesWhoMigratedIt() throws IOException {
            assertThat(get("/api/transition/legacy-cohorts").body())
                .as("nothing has been migrated yet")
                .contains("\"migrationApplied\":false,\"migratedBy\":null");

            post("/api/transition/legacy-cohorts/HL-PRE-2020/migrate",
                "method=FULL_RECONSTRUCTION&migratedBy=transition.lead");

            // migratedBy is required by the endpoint. The first cut echoed it back and recorded only
            // that a migration had happened, so the listing reported migrationApplied: true with
            // nobody's name against it — demanding an identity and discarding it, in a module whose
            // every other record carries a maker and a checker.
            assertThat(get("/api/transition/legacy-cohorts").body())
                .contains("\"migrationApplied\":true,\"migratedBy\":\"transition.lead\"");
        }

        @Test
        @DisplayName("an unknown migration method is a 400 that lists the two that exist")
        void anUnknownMigrationMethodIsABadRequest() throws IOException {
            Response response = post("/api/transition/legacy-cohorts/HL-PRE-2020/migrate",
                "method=SOMEHOW&migratedBy=transition.lead");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("FULL_RECONSTRUCTION")
                .contains("DEEMED_EIR")
                .contains("SOMEHOW");
        }

        @Test
        @DisplayName("a path naming no cohort in the plan names the four that are")
        void aPathNamingNoCohortNamesTheFourThatExist() throws IOException {
            Response response = post("/api/transition/legacy-cohorts/NOT-A-COHORT/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead");

            // Was a 400 that could name only the VALID cohorts: the module registered one
            // Routes.post per cohort plus a catch-all, and a post handler receives no exchange, so
            // the catch-all could not see which name it had been reached by. One subtree route now
            // reads the name off the path, so the answer is a 404 that names the id that is wrong as
            // well as the ones that are right -- and a 404 rather than a 400, because a cohort
            // nobody segmented is a resource that is not there, not a malformed request.
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body())
                .as("the whole gain of reading the path: the offending name is in the answer")
                .contains("NOT-A-COHORT")
                .contains("NO_SUCH_COHORT");
            assertThat(response.body())
                .contains("HL-PRE-2020")
                .contains("VEHICLE-2021-2023")
                .contains("GOLD-REVOLVING")
                .contains("SME-TERM-PRE-2018");
        }

        @Test
        @DisplayName("reconstructing a cohort that runs off before the deadline reports the wasted"
            + " capacity; deeming the same cohort does not")
        void reconstructingACohortThatRunsOffFirstReportsWastedEffort() throws IOException {
            Response reconstructed = post(
                "/api/transition/legacy-cohorts/VEHICLE-2021-2023/migrate",
                "method=FULL_RECONSTRUCTION&migratedBy=transition.lead");

            // 1,204,338 vehicle loans running off 2029-06-30. Reconstruction capacity spent here
            // buys nothing, because that exposure never needs a reconstructed rate.
            assertThat(reconstructed.body())
                .contains("\"survivesAcpir50Deadline\":false")
                .contains("never needs a reconstructed rate");

            Response deemed = post(
                "/api/transition/legacy-cohorts/VEHICLE-2021-2023/migrate",
                "method=DEEMED_EIR&migratedBy=transition.lead"
                    + "&deemedRate=0.013000000000&basis=PORTFOLIO_AVERAGE_AT_ORIGINATION"
                    + "&infeasibilityReason=cohort+runs+off+before+the+deadline"
                    + "&documentedBasis=portfolio+average+at+origination"
                    + "&preparedBy=transition.analyst"
                    + "&approvedBy=transition.committee&approvedOn=2027-03-31");

            // The same cohort on a deemed rate is not wasted effort — that is the whole point of
            // having the two methods — so the refusal disappears while the run-off date does not
            // move. Both sides of the same predicate, on one cohort.
            assertThat(deemed.body())
                .contains("\"survivesAcpir50Deadline\":false")
                .contains("\"methodChanged\":true")
                .contains("\"refusals\":[]")
                .doesNotContain("never needs a reconstructed rate");
        }
    }

    @Nested
    @DisplayName("GET /api/transition/coverage — the two deadlines, tracked separately")
    class CoverageAgainstBothDeadlines {

        @Test
        @DisplayName("the two obligations come back as separate figures, and on this population"
            + " they differ: two outstanding against three")
        void theTwoObligationsComeBackAsSeparateFiguresAndTheyDiffer() throws IOException {
            Response response = get("/api/transition/coverage");

            assertThat(response.status()).isEqualTo(200);
            // By hand from TransitionBook's six recorded bases:
            //   ACPIR 21 (interest on the EIR) is outstanding on C-0004 and C-0005 — TWO.
            //   ACPIR 50 (ECL at the EIR)      is outstanding on C-0002, C-0003 and C-0004 — THREE.
            // Satisfied is therefore 6 − 2 = 4 and 6 − 3 = 3. The two counts differ, which is what
            // makes a merged figure detectable rather than merely forbidden: no single number can
            // stand for both.
            assertThat(response.body())
                .contains("\"obligation\":\"ACPIR 21\"")
                .contains("\"obligation\":\"ACPIR 50\"")
                .contains("\"satisfied\":4")
                .contains("\"outstanding\":2")
                .contains("\"satisfied\":3")
                .contains("\"outstanding\":3")
                .contains("\"outstandingContracts\":[\"C-0004\",\"C-0005\"]")
                .contains("\"outstandingContracts\":[\"C-0002\",\"C-0003\",\"C-0004\"]");
            // Structural, not cosmetic: the deadline appears once per obligation, because each
            // obligation carries its own. One occurrence would mean one obligation.
            assertThat(occurrences(response.body(), "\"deadline\":\"2030-03-31\""))
                .as("one deadline field per obligation — 04 § 6: 'tracking them in one field would"
                    + " hide a gap'")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("no combined migration figure is published, in any form")
        void noCombinedMigrationFigureIsPublished() throws IOException {
            Response response = get("/api/transition/coverage");

            assertThat(response.body()).contains("\"combinedMigrationFigurePublished\":false");
            // The two shapes a merge would take on this population: 2 + 3 = 5 contracts outstanding,
            // or 4 + 3 = 7 satisfied. Neither may appear as a figure, because ACPIR 21 is satisfied
            // first and in bulk, so a merged percentage climbs while the ECL basis has not moved.
            assertThat(response.body())
                .doesNotContain("\"outstanding\":5")
                .doesNotContain("\"satisfied\":7")
                .doesNotContain("\"migrated\":");
        }

        @Test
        @DisplayName("the concession population is smaller than ACPIR 50's outstanding count, and"
            + " both are published")
        void theConcessionPopulationIsSmallerThanAcpirFiftysOutstandingCount() throws IOException {
            Response response = get("/api/transition/coverage");

            // The concession population — ACPIR 21 met and ACPIR 50 not — is C-0002 and C-0003:
            // TWO. ACPIR 50's obligation is outstanding on THREE, because C-0004 is not under the
            // EIR regime at all and so is not "under the concession". Two different questions, two
            // figures, and taking MigrationTracker.outstandingAcpir50Migrations() as the obligation
            // count would understate it by one on this population.
            assertThat(response.body())
                .contains("\"underTheAcpir50Concession\":2")
                .contains("\"outstanding\":3");
            // The reverse gap: C-0005's ECL is on the EIR while its interest is not. Possible during
            // a phased cutover, so surfaced rather than refused.
            assertThat(response.body()).contains("\"eclAheadOfInterest\":[\"C-0005\"]");
        }

        @Test
        @DisplayName("TM-1 fails on the untracked contract and NOT on the concession population")
        void tmOneFailsOnTheUntrackedContractAndNotOnTheConcession() throws IOException {
            Response response = get("/api/transition/coverage");

            // As at the book's own business date, 2028-05-31 — inside the concession window. Two
            // contracts sit on the interim basis legitimately, and TM-1 does not fail on them: the
            // obvious formulation, "fail while any contract is still on the interim basis", would be
            // red continuously from 2027 to 2030 while describing a state ACPIR 50 explicitly
            // permits, and a control that is red by design gets suppressed and is then not there for
            // the year it matters. It fails on C-0007, whose position nobody recorded at all.
            assertThat(response.body())
                .contains("\"asOf\":\"2028-05-31\"")
                .contains("\"untrackedContracts\":1")
                .contains("\"untracked\":[\"C-0007\"]")
                .contains("\"id\":\"TM-1\"")
                .contains("\"deviation\":\"1\"")
                .contains("no recorded ECL discount basis");
            assertThat(response.body())
                .as("before the deadline, sitting on the interim basis is progress, not a breach")
                .doesNotContain("after the 31 March 2030 deadline")
                .contains("\"inBreach\":false");
        }

        @Test
        @DisplayName("as at 1 April 2030 TM-1 counts the interim contracts too: deviation 4")
        void pastTheDeadlineTmOneCountsTheInterimContractsToo() throws IOException {
            Response response = get("/api/transition/coverage?asOf=2030-04-01");

            // 1 untracked plus 3 still on the interim contractual basis (C-0002, C-0003, C-0004) =
            // a deviation of 4. 31 March 2030 is the last compliant day, so 1 April is the first day
            // of breach — the same strictly-after boundary LegacyCohort and the schema use.
            assertThat(response.body())
                .contains("\"asOf\":\"2030-04-01\"")
                .contains("\"id\":\"TM-1\"")
                .contains("\"deviation\":\"4\"")
                .contains("after the 31 March 2030 deadline");
            // Both obligations are now in breach, separately.
            assertThat(occurrences(response.body(), "\"inBreach\":true"))
                .as("each obligation reports its own breach")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("31 March 2030 is the last compliant day: neither obligation is in breach on"
            + " the deadline itself")
        void theDeadlineItselfIsNotYetABreach() throws IOException {
            Response response = get("/api/transition/coverage?asOf=2030-03-31");

            // The obligation is to be compliant BY 31 March 2030, so a position taken on that date
            // has met it — strictly after, the same boundary LegacyCohort and the schema's generated
            // column draw. Written because the off-by-one is invisible from either side: an
            // inclusive test reads correctly at 2028-05-31 (still false) and at 2030-04-01 (still
            // true), so only the deadline date itself distinguishes them. Confirmed by mutation:
            // isAfter(deadline) changed to !isBefore(deadline) survived every other test in this
            // class and is caught here.
            assertThat(response.body())
                .contains("\"asOf\":\"2030-03-31\"")
                .doesNotContain("\"inBreach\":true")
                .doesNotContain("after the 31 March 2030 deadline");
            // TM-1 still fails, on the untracked contract alone — the deadline has not passed, so
            // the three interim contracts are not yet breaches.
            assertThat(response.body())
                .contains("\"id\":\"TM-1\"")
                .contains("\"deviation\":\"1\"");
        }

        @Test
        @DisplayName("an empty asOf is a 400, not a quiet fall back to the period end")
        void anEmptyAsOfIsABadRequest() throws IOException {
            Response response = get("/api/transition/coverage?asOf=");

            // The dangerous case, and the one the first cut got wrong: ?asOf=next-tuesday answered
            // 400 while ?asOf= answered 200 as at 2028-05-31. A caller whose date variable
            // interpolated empty got a plausible control report for a date they did not ask about,
            // and nothing in the response said so.
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("asOf");
        }

        @Test
        @DisplayName("an unparseable asOf is a 400 rather than a silent fall back to today")
        void anUnparseableAsOfIsABadRequest() throws IOException {
            Response response = get("/api/transition/coverage?asOf=next-tuesday");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("asOf")
                .contains("next-tuesday");
        }
    }

    /**
     * The completeness leg of 08 Phase 4's exit gate, which the demonstration book cannot exercise.
     *
     * <p>With the default seed the valued population and the legacy book size are the same seven
     * contracts, so {@code completeOverTheBook} is true for every request the endpoint can be given
     * — a leg of a gate that cannot fail. The failing input is a contract the contract master carries
     * that the valuation run was never shown, which {@link TransitionBook#seeded(List)} constructs.
     *
     * <p>Driven through a captured {@link Routes} rather than a socket, because {@code EirServer}
     * assembles its modules from {@code ApiModules} and there is no seam to hand it a different
     * transition book. {@code ApiModule}'s own contract says a module is testable without a server:
     * it receives {@code Routes} and nothing else. Only the POST handler is invoked here, and a POST
     * handler takes a parsed body and no exchange, so nothing is stubbed.
     */
    @Nested
    @DisplayName("the completeness leg of the exit gate, on a book with an unvalued contract")
    class TheCompletenessLegOfTheExitGate {

        /** A {@link Routes} that records what a module registered, so a test can invoke it. */
        private static final class CapturedRoutes implements Routes {
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

            /** Drives the registered subtree at {@code rawPath} the way EirServer would. */
            Routes.Answer at(String verb, String rawPath) {
                Routes.PathHandler handler = subtrees.entrySet().stream()
                    .filter(entry -> rawPath.startsWith(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                        "no subtree covering " + rawPath + "; registered: " + subtrees.keySet()));
                return handler.handle(
                    PathOnlyExchange.method(verb, rawPath), FormBody.parse(""));
            }
        }

        private String runWith(TransitionBook book, String... forms) {
            TransitionModule module = new TransitionModule(new EirService(Seed.book()), book);
            CapturedRoutes routes = new CapturedRoutes();
            module.register(routes);
            Function<FormBody, Json.Obj> run =
                routes.posts.get("/api/transition/fair-value-run");
            assertThat(run).as("the run route must be registered under 06 § 8's path").isNotNull();
            Json.Obj last = null;
            for (String form : forms) {
                last = run.apply(FormBody.parse(form));
            }
            return last == null ? run.apply(FormBody.parse("")).toString() : last.toString();
        }

        @Test
        @DisplayName("a contract the master carries and the run never saw fails the gate even with"
            + " every presumption evidenced")
        void anUnvaluedContractFailsTheGateEvenWithEveryPresumptionEvidenced() {
            String body = runWith(TransitionBook.seeded(List.of("C-0010")),
                "evidenceForContract=C-0003&paragraph19EvidenceRef=WP-ACPIR19-PL-03",
                "evidenceForContract=C-0007&paragraph19EvidenceRef=WP-ACPIR19-PL-04");

            // TF-1 is satisfied and the total publishes at −44,250.00 — C-0010 has no valuation, so
            // it contributes nothing to the sum. The gate is still not met, because the run did not
            // complete over the full book. The run itself cannot see this: it has no way to know a
            // contract exists and was never presented to it, which is why 08 Phase 4 says the
            // completeness half "needs a caller that can see both". This module is that caller.
            assertThat(body)
                .contains("\"published\":true")
                .contains("\"totalDifferenceToOpeningRetainedEarnings\":\"-44250.00\"")
                .contains("\"legacyBookSize\":8")
                .contains("\"valuedContracts\":7")
                .contains("\"unvaluedContracts\":1")
                .contains("\"completeOverTheBook\":false")
                .contains("\"exitGateMet\":false");
        }

        @Test
        @DisplayName("a contract the run never saw is a 404 that says why, not a 500")
        void aNeverPresentedContractIsARefusalThatSaysWhy() {
            TransitionModule module = new TransitionModule(
                new EirService(Seed.book()), TransitionBook.seeded(List.of("C-0010")));
            CapturedRoutes routes = new CapturedRoutes();
            module.register(routes);

            // C-0010 is in the population and has no valuation, so this module has nothing to say
            // about it. Three shapes of this answer, in order of how they were reached:
            //
            //  1. a route per POPULATION id, whose handler could only throw -- 500 naming an
            //     internal invariant, where the honest answer is about the book;
            //  2. a route per ANSWERABLE id, so C-0010 had no route and the server's own 404
            //     answered -- honest, but it could not say why, and it registered one HTTP context
            //     per contract, which on a real book is millions of contexts and a surface
            //     inventory that grows with the population;
            //  3. one subtree route reading the id off the path, which is this: a 404 naming the
            //     contract and the reason, and one context however large the book.
            assertThat(routes.gets)
                .as("no per-contract registration remains: a context per contract is not a routing"
                    + " table, it is the population")
                .doesNotContainKey("/api/transition/fair-value/C-0010")
                .doesNotContainKey("/api/transition/fair-value/C-0001");

            Routes.Answer absent = routes.at("GET", "/api/transition/fair-value/C-0010");
            assertThat(absent.status()).isEqualTo(404);
            assertThat(absent.body().toString())
                .as("the reason has to be about the book -- an integrator seeing a bare 404 cannot"
                    + " tell a missing valuation from a missing deployment")
                .contains("C-0010")
                .contains("NO_TRANSITION_RECORD")
                .contains("neither an ACPIR 19 day-1 valuation nor a below-market origination");

            // The seven valued contracts and the two below-market originations still answer.
            for (String answerable : List.of("C-0001", "C-0007", "C-0008", "C-0009")) {
                assertThat(routes.at("GET", "/api/transition/fair-value/" + answerable).status())
                    .as("%s is answerable and must still be served", answerable)
                    .isEqualTo(200);
            }

            assertThat(routes.at("POST", "/api/transition/fair-value/C-0001"))
                .as("a verb this subtree does not serve is declined, so a sibling module on the"
                    + " prefix can claim it rather than being shadowed")
                .isNull();
        }

        @Test
        @DisplayName("a never-presented id colliding with a below-market origination is refused at"
            + " construction, because the duplicate route would kill the whole server")
        void aCollisionWithABelowMarketOriginationIsRefused() {
            // C-0008 is the staff housing loan. Left unchecked, the module registered
            // /api/transition/fair-value/C-0008 twice, HttpServer.createContext refused the
            // duplicate path, and the IllegalArgumentException came out of the EirServer
            // constructor — so /api/book and /api/run died too, for a transition seed mistake.
            // One subtree route removes that failure mode entirely; the guard is kept because a
            // contract in two halves of the transition book is still a seeding error, and now it
            // would silently resolve to whichever half the handler consults first.
            assertThatThrownBy(() -> TransitionBook.seeded(List.of("C-0008")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C-0008")
                .hasMessageContaining("already in the seeded transition book");
            // And the ordinary collision, with a valued contract, is refused the same way.
            assertThatThrownBy(() -> TransitionBook.seeded(List.of("C-0003")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C-0003");
        }

        @Test
        @DisplayName("the same run over the whole book meets both legs")
        void theSameRunOverTheWholeBookMeetsBothLegs() {
            String body = runWith(TransitionBook.seeded(),
                "evidenceForContract=C-0003&paragraph19EvidenceRef=WP-ACPIR19-PL-03",
                "evidenceForContract=C-0007&paragraph19EvidenceRef=WP-ACPIR19-PL-04");

            assertThat(body)
                .contains("\"unvaluedContracts\":0")
                .contains("\"completeOverTheBook\":true")
                .contains("\"exitGateMet\":true");
        }
    }
}
