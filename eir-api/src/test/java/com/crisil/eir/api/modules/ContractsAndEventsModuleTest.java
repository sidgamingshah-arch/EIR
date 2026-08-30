package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contracts and events, driven over real HTTP on a real socket (06 §§ 2, 3).
 *
 * <p><b>Over a socket, and on port 0, for {@code EirServerTest}'s reasons.</b> Every defect this
 * layer can introduce lives between the service and the wire — a figure emitted as a JSON number
 * and rounded by the reader, an engine refusal mapped onto a 500, a 400 that should have been a
 * routed answer — and a test that calls the module directly misses all three. Port 0 lets the OS
 * pick, so this never collides with a developer's own server.
 *
 * <p><b>The URLs are 06 § 2 and § 3's own.</b> An earlier version of this test drove the reads at a
 * singular {@code /api/contract/{id}} and sent the contract id in the event form, because the route
 * seam gave a POST handler no path and allowed one verb per path prefix. {@code Routes.route} removed
 * both limitations, and the test that pinned the resulting 405 is deleted rather than kept: a test
 * asserting a limitation that has been removed is worse than no test, because it reads as a
 * documented behaviour.
 *
 * <p><b>The test that matters most is {@link TheDriverTag#anUntaggedEventIsRefused}.</b> Everything
 * else here is a figure or a path; that one is the control. An untagged event must be refused at the
 * boundary (FR-504) rather than defaulted, because routing on the observation that the rate moved
 * gives a renegotiated fixed rate a B5.4.5 reset — no catch-up, no substantiality test, no
 * derecognition assessment — and no downstream invariant can detect it afterwards. To prove the
 * check can fail, delete the {@code if (!body.has(DRIVER))} block at the top of
 * {@code EventSubmission.parse}: the submission then reaches {@code body.text(DRIVER)}, which is
 * {@code FormBody}'s generic "required and arrived absent" refusal, and this test fails on the
 * missing FR-504 citation while the request still 400s. Delete the check <em>and</em> default the
 * driver to {@code TIME_VALUE_OF_MONEY} and the request returns 200 with a routed mechanism —
 * which is the defect, and this test is the only thing standing in front of it.
 *
 * <p><b>Expected figures are derived by hand and the derivation is stated at each assertion.</b>
 * Two derivations carry most of them, and both are exact because a common discount factor cancels
 * or divides out:
 *
 * <ul>
 *   <li><b>The catch-up.</b> Reference case 1 at month 13 carries 528,407.32 at a periodic EIR of
 *       0.010421491800. A single revised flow of 500,000.00 one period after the event discounts to
 *       500000 / 1.0104214918 = 494,842.99776, which publishes as <b>494,843.00</b>; the catch-up is
 *       494,842.99776 − 528,407.32 = <b>−33,564.32</b>.
 *   <li><b>The 10% ratio.</b> Both legs are single flows on one date, so the discount factor
 *       cancels entirely: |452,500 − 500,000| / 500,000 = <b>0.095</b> and
 *       |440,000 − 500,000| / 500,000 = <b>0.12</b>, whatever the rate.
 * </ul>
 */
class ContractsAndEventsModuleTest {

    /** Case 1's month-13 opening balance, and the event date that lands on its due date 13. */
    private static final String EVENT_DATE = "2028-05-31";

    /** A floating-rate twin of case 1, so the rate-type check can be seen doing nothing. */
    private static final String FLOATING = "C-0900";

    private EirServer server;
    private String base;

    @BeforeEach
    void startOnAnEphemeralPort() throws IOException {
        Book book = Seed.book();
        // The seeded book is entirely FIXED-rate, and EirService.onboard hardcodes RateType.FIXED,
        // so without this holding the RESET branch is unreachable over HTTP and the FR-507 pair —
        // same driver, two rate types, two mechanisms — could not be shown at all.
        book.put(Book.Holding.onFile(FLOATING, "HL", "IN-MUM",
            "Housing loan, floating — reference case 1's figures on a FLOATING instrument",
            floatingState(), period(FLOATING)));
        server = new EirServer(0, new EirService(book));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** Case 1's terms, with the one attribute that changes the routing. */
    private static ContractTerms floatingTerms() {
        return ContractTerms.of(
            Money.inr("1000000.00"),
            Rate.periodic(new BigDecimal("0.010000000000"), 12),
            24,
            12,
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 31),
            DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI,
            RateType.FLOATING);
    }

    private static ContractStateSource.OpeningState floatingState() {
        return new ContractStateSource.OpeningState(
            floatingTerms(), Seed.EIR, Seed.OPENING_GCA, Seed.OPENING_CONTRACTUAL,
            Stage.STAGE_1, Seed.NIL, "ECL-MODEL-2028.05", Seed.BILLED_INTEREST);
    }

    private static ContractPeriod period(String contractId) {
        return ContractPeriod.of(contractId, 13,
            FlowVector.of(Seed.PERIOD_START, Money.INR,
                List.of(CashFlow.of(Seed.PERIOD_END, 1, Seed.EMI, FlowKind.COMBINED_EMI))),
            TimeConvention.PeriodicIndex.monthly(), Seed.PRINCIPAL_SLICE, Seed.BILLED_INTEREST);
    }

    // ---- transport ------------------------------------------------------------------------

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
     * One JSON string field's value.
     *
     * <p>A regex rather than a parser, for the reason {@code EirServerTest} gives: this module has
     * no JSON parser and writing one for the test would be the largest untested thing in it. It is
     * used only where a figure has to be compared numerically — a ratio computed at 28 significant
     * digits is not a string a test may pin — and the boolean and identifier assertions stay as
     * substring matches a formatting change cannot satisfy by accident.
     */
    private static BigDecimal figure(String body, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("the response carries a '%s' field", key).isTrue();
        return new BigDecimal(matcher.group(1));
    }

    /**
     * Every value of a repeated JSON key, in document order, with {@code "null"} for a JSON null.
     *
     * <p>Needed because the version chain's whole claim is about ORDER: that {@code validFrom} is
     * non-decreasing down the rows and each {@code validTo} equals the next {@code validFrom}. A
     * substring match cannot see order, and it was a substring match that let a chain with a
     * backwards interval pass.
     */
    private static List<String> allOf(String body, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\":(?:\"([^\"]*)\"|(null))").matcher(body);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1) == null ? "null" : matcher.group(1));
        }
        return found;
    }

    /**
     * The event form, minus whatever the test is proving the absence of.
     *
     * <p>No {@code contractId}: it is a path parameter, which is where 06 § 3 puts it and where
     * {@code Routes.route} lets this module read it from.
     */
    private static String event(String driver, String extra) {
        return "eventDate=" + EVENT_DATE
            + (driver == null ? "" : "&driver=" + driver)
            + (extra.isEmpty() ? "" : "&" + extra);
    }

    private static String eventsPath(String contractId) {
        return "/api/contracts/" + contractId + "/events";
    }

    // =======================================================================================

    @Nested
    @DisplayName("the driver tag is required and is never defaulted (FR-504)")
    class TheDriverTag {

        @Test
        @DisplayName("an untagged event is a 400 that cites FR-504 and says why the tag matters")
        void anUntaggedEventIsRefused() throws IOException {
            // The recipe's own call: an event date, and no driver.
            Response response = post(eventsPath("C-0001"), "eventDate=2028-05-15");

            assertThat(response.status())
                .as("a missing driver tag is a malformed event, so 400 — not a refusal value on a 200")
                .isEqualTo(400);
            assertThat(response.body())
                .as("the refusal must name the field, the requirement and the consequence")
                .contains("'driver' is required")
                .contains("FR-504")
                .contains("FR-507")
                .contains("no catch-up, no substantiality test");
            // The refusal must be about the driver and NOTHING ELSE, even though this request is
            // also missing a contractId and a revised vector. If contractId were validated first
            // the caller would fix the id, get past that refusal, and receive a routed answer for
            // an event they never tagged.
            assertThat(response.body())
                .as("the driver check runs before every other check, so it is the one reported")
                .doesNotContain("'contractId' is required")
                .doesNotContain("revisedFlows");
            assertThat(response.body())
                .as("no mechanism may be reported for an untagged event")
                .doesNotContain("routedMechanism");
        }

        @Test
        @DisplayName("an unrecognised driver is refused, not mapped to the nearest match")
        void anUnknownDriverIsRefused() throws IOException {
            // CREDIT_RISK_MARKET and CREDIT_RATCHET_PREDETERMINED differ by one word and route to a
            // reset and a catch-up respectively, so a near-miss must not be resolved by proximity.
            Response response = post(eventsPath("C-0001"), event("CREDIT_RISK", ""));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("'driver' must be one of")
                .contains("CREDIT_RISK_MARKET")
                .contains("CREDIT_RATCHET_PREDETERMINED");
        }

        @Test
        @DisplayName("a tagged event carrying no revised flows has nothing to route")
        void anEventWithNoRevisedFlowsIsRefused() throws IOException {
            Response response =
                post(eventsPath("C-0001"), event("STEP_UP_PREDETERMINED", ""));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("revisedFlows")
                .contains("nothing for the routing table");
        }

        @Test
        @DisplayName("a form contract id that contradicts the path is refused, never resolved")
        void aContradictoryContractIdIsRefused() throws IOException {
            Response response = post(eventsPath("C-0001"),
                "contractId=C-0002&eventDate=" + EVENT_DATE
                    + "&driver=STEP_UP_PREDETERMINED&revisedFlows=2028-06-30:500000.00");

            assertThat(response.status()).isEqualTo(400);
            // Two sources for one identifier is the same defect as two sources for one figure.
            // Whichever the engine preferred would decide WHICH INSTRUMENT'S BALANCE gets restated,
            // so it decides neither and names both.
            assertThat(response.body())
                .contains("path names contract C-0001")
                .contains("form names C-0002")
                .contains("will not choose between two identifiers");
            assertThat(response.body())
                .as("nothing may be routed while it is unclear which contract this is")
                .doesNotContain("routedMechanism");
        }
    }

    @Nested
    @DisplayName("routing keys off the driver tag and the instrument's rate type (FR-507)")
    class RoutingInputs {

        /** The revised leg both halves of the pair use, so only the rate type differs. */
        private static final String REVISED = "revisedFlows=2028-06-30:533691.3932";

        @Test
        @DisplayName("a market driver on a FIXED instrument is overridden to a modification test")
        void aMarketDriverOnAFixedInstrumentIsAModification() throws IOException {
            Response response = post(eventsPath("C-0001"), event("TIME_VALUE_OF_MONEY",
                REVISED + "&side=ASSET&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            // The baseline table maps TIME_VALUE_OF_MONEY to RESET. It does not reset here, because
            // a FIXED-rate instrument cannot experience a market movement by its own terms: B5.4.5
            // covers instruments that reprice off a benchmark contractually, so the combination can
            // only have arisen from renegotiation, and it runs the substantiality assessment.
            assertThat(response.body())
                .contains("\"rateType\":\"FIXED\"")
                .contains("\"routedMechanism\":\"MODIFICATION_TEST\"")
                .contains("\"overriddenByRateTypeCheck\":true")
                .contains("RT-BASELINE-2026.1");
            assertThat(response.body())
                .as("the override must be explained, not merely flagged")
                .contains("which maps TIME_VALUE_OF_MONEY to RESET");
        }

        @Test
        @DisplayName("the same driver on a FLOATING instrument resets, and the balance holds still")
        void theSameDriverOnAFloatingInstrumentResets() throws IOException {
            Response response = post(eventsPath(FLOATING),
                event("TIME_VALUE_OF_MONEY", REVISED));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"rateType\":\"FLOATING\"")
                .contains("\"routedMechanism\":\"RESET\"")
                .contains("\"overriddenByRateTypeCheck\":false");

            // Derived by hand: the balance is 528,407.32 and the single revised flow is
            // 528,407.32 × 1.01 = 533,691.3932 one period later, so the rate that discounts the
            // flow back to the balance satisfies 1 + r = 533691.3932 / 528407.32 = 1.01 exactly.
            // Hence r = 0.010000000000. The tolerance is the solver's, not the arithmetic's: it
            // stops on an absolute residual in rupees, which on a 528,407.32 target admits a rate
            // error of order 1e-8.
            assertThat(figure(response.body(), "newEir"))
                .as("the re-solved periodic rate, from 528,407.32 to 533,691.3932 one period on")
                .isCloseTo(new BigDecimal("0.010000000000"), within(new BigDecimal("0.0000001")));
            // A reset re-prices the rate and leaves the carrying amount alone — that is the whole
            // difference from a catch-up, and both figures are published so it can be checked.
            assertThat(response.body())
                .contains("\"carryingAmountBefore\":\"528407.32\"")
                .contains("\"carryingAmountAfter\":\"528407.32\"")
                .contains("\"catchUpAmount\":\"0.00\"");
        }

        @Test
        @DisplayName("the routing table is chosen on the event's date, so an uncovered date refuses")
        void theTableIsSelectedOnTheEventDate() throws IOException {
            // RT-BASELINE-2026.1 is effective from 2026-05-01. An event the day before it is a date
            // no approved reading governs, and the registry declines to substitute its baseline.
            Response response = post(eventsPath("C-0001"),
                "contractId=C-0001&eventDate=2026-04-30&driver=STEP_UP_PREDETERMINED"
                    + "&revisedFlows=2026-05-31:500000.00");

            assertThat(response.status())
                .as("a policy-coverage gap is an engine answer, so it comes back on a 200")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"routed\":false")
                .contains("\"accepted\":false")
                .contains("2026-04-30");
            assertThat(response.body())
                .as("nothing may be published for an event nothing routed")
                .doesNotContain("\"routedMechanism\"")
                .doesNotContain("\"catchUpAmount\"");
        }

        @Test
        @DisplayName("an event off a schedule boundary reports the approximation rather than hiding it")
        void anOffBoundaryEventDateIsCaveated() throws IOException {
            Response response = post(eventsPath("C-0001"),
                "contractId=C-0001&eventDate=2028-05-15&driver=STEP_UP_PREDETERMINED"
                    + "&revisedFlows=2028-06-30:500000.00");

            assertThat(response.status()).isEqualTo(200);
            // Due date 13 of case 1 is 2028-05-31, so 2028-05-15 is between boundaries. Under
            // PeriodicIndex the discount exponent is the flow's ordinal, so the first revised flow
            // is discounted a whole period across a 46-day gap. Reported, as FR-809 reports every
            // other approximation in force.
            assertThat(response.body())
                .contains("\"eventDateOnScheduleBoundary\":false")
                .contains("eventDateCaveat")
                .contains("not a scheduled due date");
        }
    }

    @Nested
    @DisplayName("a catch-up restates the balance at the rate that does not move (CU-1)")
    class TheCatchUp {

        @Test
        @DisplayName("the restated balance and the catch-up are case 1's arithmetic")
        void theRestatementTiesToHandArithmetic() throws IOException {
            Response response = post(eventsPath("C-0001"), event("STEP_UP_PREDETERMINED",
                "revisedFlows=2028-06-30:500000.00"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("a pre-determined step compensates for neither the time value of money nor"
                    + " credit risk, so it falls outside B5.4.5 and routes to a B5.4.6 catch-up")
                .contains("\"routedMechanism\":\"CATCH_UP\"")
                .contains("\"overriddenByRateTypeCheck\":false");

            // By hand: 500,000.00 one period after the event, discounted at the retained periodic
            // EIR of 0.010421491800, is 500000 / 1.0104214918 = 494,842.99776 — published at
            // presentation scale as 494,843.00. The catch-up is the movement in the balance,
            // 494,842.99776 − 528,407.32 = −33,564.32224, published as −33,564.32. Negative, so it
            // is a charge, which is the ordinary case for a restructured asset.
            assertThat(response.body())
                .contains("\"carryingAmountBefore\":\"528407.32\"")
                .contains("\"restatedCarryingAmount\":\"494843.00\"")
                .contains("\"carryingAmountAfter\":\"494843.00\"")
                .contains("\"catchUpAmount\":\"-33564.32\"")
                .contains("\"catchUpIsCharge\":true");
            // The rate is unchanged and stated twice — before and after — which is the cheapest
            // audit evidence that the event was routed as a catch-up and not quietly as a reset.
            assertThat(response.body())
                .contains("\"eirBefore\":\"0.010421491800\"")
                .contains("\"eirAfter\":\"0.010421491800\"");
        }

        @Test
        @DisplayName("eirUnchanged is CU-1's own result, and CU-2 ties the published balances")
        void theInvariantsAreReportedNotAsserted() throws IOException {
            Response response = post(eventsPath("C-0001"), event("ESG_LINKED",
                "revisedFlows=2028-06-30:500000.00"));

            assertThat(response.status()).isEqualTo(200);
            // eirUnchanged is read off CU-1 rather than written as a constant. To prove the control
            // can fail, make EventRouting.catchUp set eirAfter from a re-solve — for instance
            //     Rate eirAfter = new BracketedNewtonSolver().solve(SolveRequest.of(
            //         submission.revisedFlows(), gcaBefore, convention, eirBefore.periodic())).rate();
            // and CU-1 goes red, invariantsClean turns false and eirUnchanged comes back false,
            // failing this test. A hardcoded true would be a control that cannot fail, which reads
            // exactly like evidence and is not.
            assertThat(response.body())
                .contains("\"eirUnchanged\":true")
                .contains("\"invariantsClean\":true")
                .contains("\"id\":\"CU-1\"")
                .contains("\"id\":\"CU-2\"")
                .contains("EIR unchanged across catch-up");
            assertThat(response.body())
                .as("both invariants are reported as results, satisfied or not — never thrown")
                .contains("\"satisfied\":true");
        }
    }

    @Nested
    @DisplayName("the 10% test is evidence and the engine does not decide (FR-511)")
    class TheSubstantialityAssessment {

        /**
         * Both legs are single flows on one date, so the discount factor divides out and the ratio
         * is exact whatever the rate: |452,500 − 500,000| / 500,000 = 0.095.
         */
        private static final String LEGS_AT_NINE_AND_A_HALF =
            "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:452500.00";

        @Test
        @DisplayName("on the asset side a ratio inside the band is PENDING_APPROVAL, with no figures")
        void theAssetSideDefersToAPerson() throws IOException {
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                LEGS_AT_NINE_AND_A_HALF + "&side=ASSET&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"routedMechanism\":\"MODIFICATION_TEST\"");
            assertThat(figure(response.body(), "ratio"))
                .as("47,500 / 500,000 = 0.095; the common discount factor cancels")
                .isCloseTo(new BigDecimal("0.095"), within(new BigDecimal("0.000000000001")));
            assertThat(response.body())
                .contains("\"breachesThreshold\":false")
                .contains("\"withinReviewBand\":true")
                .contains("\"quantitativeTestIsAuthoritative\":false")
                // IFRS 9 sets no bright line for a financial asset, so 9.5% against a 10% line
                // inside a 9–11% band is exactly the case the engine must not settle.
                .contains("\"substantialityConclusion\":\"PENDING_APPROVAL\"")
                .contains("\"engineDecided\":false");
            // And it must publish nothing it did not produce. Writing the pre-event balance into
            // carryingAmountAfter would read as an assessment that concluded immaterial.
            assertThat(response.body())
                .contains("\"catchUpApplied\":false")
                .contains("\"newEir\":null")
                .contains("\"catchUpAmount\":null")
                .contains("\"carryingAmountAfter\":null");
            // Both present values, not just the ratio: the question about a borderline result is
            // which leg moved and by how much.
            assertThat(response.body())
                .contains("\"presentValueRemaining\":\"494843.00\"")
                .contains("\"originalLegSource\":\"SUPPLIED\"");
        }

        @Test
        @DisplayName("on the liability side the same evidence is decided, because B3.3.6 is a line")
        void theLiabilitySideIsDecidedByTheRatio() throws IOException {
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                LEGS_AT_NINE_AND_A_HALF + "&side=LIABILITY&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            // Same ratio, same triggers, opposite outcome: 9.5% does not reach B3.3.6's 10%, and on
            // a liability that IS the answer. This asymmetry is the most consequential single gap in
            // the standard for a bank with a restructuring book, which is why `side` is required.
            assertThat(response.body())
                .contains("\"quantitativeTestIsAuthoritative\":true")
                .contains("\"substantialityConclusion\":\"NOT_SUBSTANTIAL\"")
                .contains("\"engineDecided\":true");
        }

        @Test
        @DisplayName("a liability breaching the threshold concludes SUBSTANTIAL")
        void aBreachOnALiabilityIsSubstantial() throws IOException {
            // |440,000 − 500,000| / 500,000 = 0.12, above the 11% top of the band, so no review.
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:440000.00"
                    + "&side=LIABILITY&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(figure(response.body(), "ratio"))
                .isCloseTo(new BigDecimal("0.12"), within(new BigDecimal("0.000000000001")));
            assertThat(response.body())
                .contains("\"breachesThreshold\":true")
                .contains("\"withinReviewBand\":false")
                .contains("\"substantialityConclusion\":\"SUBSTANTIAL\"")
                .contains("\"engineDecided\":true");
        }

        @Test
        @DisplayName("a qualitative trigger sends a passing ratio to approval on its own")
        void aQualitativeTriggerOverridesAPassingRatio() throws IOException {
            // |490,000 − 500,000| / 500,000 = 0.02 — nowhere near the line — but the obligor
            // changed, which is an IASB February 2025 indicative factor and not answerable by a
            // present-value ratio.
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:490000.00"
                    + "&side=ASSET&triggers=CHANGE_OF_OBLIGOR,REVOLVING_TO_TERM_CONVERSION"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"breachesThreshold\":false")
                .contains("\"withinReviewBand\":false")
                .contains("\"substantialityConclusion\":\"PENDING_APPROVAL\"")
                // A list, not a boolean: "a qualitative trigger fired" is not a reviewable
                // statement; "the obligor changed and the facility converted to term" is.
                .contains("CHANGE_OF_OBLIGOR")
                .contains("REVOLVING_TO_TERM_CONVERSION")
                .contains("\"anyQualitativeTriggerFired\":true");
        }

        @Test
        @DisplayName("a modification test with no qualitative statement at all is refused")
        void anUnassessedModificationIsRefused() throws IOException {
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                LEGS_AT_NINE_AND_A_HALF + "&side=ASSET"));

            assertThat(response.status()).isEqualTo(400);
            // An absent field is not the statement "assessed, nothing fired". Collapsing the two
            // would have the response report a clean qualitative result nobody produced.
            assertThat(response.body())
                .contains("'triggers' is required")
                .contains("assessed on the 10% ratio alone is not assessed");
        }

        @Test
        @DisplayName("a modification test with no side is refused rather than defaulted")
        void anUnsidedModificationIsRefused() throws IOException {
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                LEGS_AT_NINE_AND_A_HALF + "&triggers=NONE"));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("'side' is required")
                .contains("B3.3.6");
        }

        @Test
        @DisplayName("the counterfactual leg comes from the contract's own schedule by default")
        void theOriginalLegIsDerivedFromTheSchedule() throws IOException {
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                "revisedInstalment=45000.00&revisedPeriods=11&side=ASSET&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            // Case 1 runs 24 monthly periods from a first due date of 2027-05-31, so due dates 14
            // to 24 — 2028-06-30 through 2029-04-30 — are the eleven flows still to come after an
            // event on due date 13. The provenance is published because the denominator of the
            // ratio is the counterfactual, and a caller who could state it could move any ratio
            // across the threshold.
            assertThat(response.body())
                .contains("\"originalLegSource\":\"CONTRACT_SCHEDULE\"")
                .contains("\"originalFlowCount\":11")
                .contains("\"revisedFlowCount\":11");
        }
    }

    @Nested
    @DisplayName("onboarding and the contract reads (06 § 2)")
    class ContractsResource {

        @Test
        @DisplayName("POST /api/contracts recognises a contract and the read shows its figures")
        void anOnboardedContractReadsBack() throws IOException {
            Response onboarded = post("/api/contracts",
                "contractId=C-0300&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeCode=PROC_FEE&feeAmount=15000.00");

            assertThat(onboarded.status()).isEqualTo(200);
            assertThat(onboarded.body())
                .as("the same initial recognition the console drives, not a second copy of it")
                .contains("\"recognised\":true")
                .contains("\"measuredAt\":\"AMORTISED_COST\"")
                .contains("MEASUREMENT_GATE").contains("FEE_CLASSIFICATION")
                .contains("TIER_ASSIGNMENT").contains("PROJECTION").contains("SOLVE")
                .contains("/api/contracts/C-0300");

            Response read = get("/api/contracts/C-0300");
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.body())
                .contains("\"contractId\":\"C-0300\"")
                .contains("\"onFile\":true")
                .contains("\"rateType\":\"FIXED\"")
                // Every figure crosses the wire as a JSON string. A 1,000,000.00 principal that
                // arrived as a JSON number would come back as 1000000 and lose the statement that
                // it is denominated in paise.
                .contains("\"principal\":\"1000000.00\"")
                .contains("\"carryingAmount\":\"1000000.00\"")
                .contains("\"contractualRate\":\"0.010000000000\"")
                .contains("\"termPeriods\":24")
                .contains("RT-BASELINE-2026.1");
        }

        @Test
        @DisplayName("an unmapped fee code quarantines through this path too (FR-201)")
        void anUnmappedFeeCodeQuarantines() throws IOException {
            Response response = post("/api/contracts",
                "contractId=C-0301&principal=1000000.00&rate=0.010000000000"
                    + "&termPeriods=24&feeCode=MYSTERY_FEE&feeAmount=15000.00");

            assertThat(response.status())
                .as("a fee the rule set does not map is a refusal, and a refusal is a value")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"recognised\":false")
                .contains("MYSTERY_FEE")
                .doesNotContain("\"SOLVE\"");
        }

        @Test
        @DisplayName("case 1 reads back at its month-13 position, to twelve places and two paise")
        void caseOneReadsBackIntact() throws IOException {
            Response response = get("/api/contracts/C-0001");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"eir\":\"0.010421491800\"")
                .contains("\"carryingAmount\":\"528407.32\"")
                .contains("\"stage\":\"STAGE_1\"")
                .contains("\"contractualMaturityDate\":\"2029-04-30\"");
        }

        @Test
        @DisplayName("a read of a contract the book does not hold is a genuine 404")
        void anUnknownContractIsANotFound() throws IOException {
            Response response = get("/api/contracts/C-9999");

            // A real 404 now that Routes.Answer can carry one. This was a 400 under the old seam,
            // which was the closest honest code available and was wrong about what had happened:
            // the request was well formed and the resource was absent. What has never been
            // acceptable is a 200 carrying onFile:false — it makes "no such contract"
            // indistinguishable from "a contract holding nothing" to any caller that reads the
            // status line, and integration clients read the status line.
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body())
                .contains("C-9999")
                .contains("no such contract")
                .contains("\"reason\":\"NOT_ON_BOOK\"")
                .contains("\"onFile\":false");
        }

        @Test
        @DisplayName("a GET on the collection is not this module's, and nobody else claims it")
        void theCollectionIsNotRead() throws IOException {
            // 06 § 2 specifies POST on the collection and no GET; the book's own listing is
            // GET /api/book. Declined rather than answered, so the server's own 404 names the path
            // instead of this module inventing a second way to ask for the book.
            Response response = get("/api/contracts");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).contains("no handler claimed");
        }

        @Test
        @DisplayName("the Stage 3 contract reads back at the documented URL")
        void theStageThreeContractReadsBack() throws IOException {
            Response response = get("/api/contracts/C-0002");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"contractId\":\"C-0002\"")
                .contains("\"stage\":\"STAGE_3\"");
        }
    }

    @Nested
    @DisplayName("the version history says what it can and cannot evidence")
    class Versions {

        @Test
        @DisplayName("the sole version is the opening position on file, and does not claim to be"
            + " the position at initial recognition")
        void aContractWithNoEventsHasOneVersion() throws IOException {
            Response response = get("/api/contracts/C-0001/versions");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"versionCount\":1")
                .contains("\"validTo\":null");
            // This row used to be labelled INITIAL_RECOGNITION and dated 2027-04-30, the
            // disbursement date, while carrying figures read from holding.state() — which is the
            // opening position of the CURRENT period. It therefore told a reader the balance on the
            // disbursement date was 528,407.32, month 13's figure, when the position at recognition
            // was a GCA0 of 990,000.00 (the onboarding response's own IC-1 states it). The row now
            // says what it is and dates itself where the book strikes it.
            assertThat(response.body())
                .as("the label and the date must describe the figures the row actually carries")
                .contains("\"basis\":\"OPENING_POSITION_ON_FILE\"")
                .contains("\"validFrom\":\"2028-04-30\"")
                .contains("\"carryingAmount\":\"528407.32\"")
                .doesNotContain("INITIAL_RECOGNITION");
            // The recognition date is still reported — as a date, not as a claim about the figures.
            assertThat(response.body()).contains("\"initialRecognitionDate\":\"2027-04-30\"");
        }

        @Test
        @DisplayName("each routed event opens a version, carrying the table that routed it")
        void eachRoutedEventOpensAVersion() throws IOException {
            post(eventsPath("C-0001"), event("STEP_UP_PREDETERMINED",
                "revisedFlows=2028-06-30:500000.00"));
            Response response = get("/api/contracts/C-0001/versions");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"versionCount\":2")
                // Version 1 now closes on the event date rather than running open-ended.
                .contains("\"validTo\":\"2028-05-31\"")
                .contains("\"validFrom\":\"2028-05-31\"")
                .contains("\"routedMechanism\":\"CATCH_UP\"")
                // The version records the table that routed it, which is what makes a closed period
                // replay under the reading it closed under rather than under today's (ADR-0006).
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"")
                .contains("\"carryingAmount\":\"494843.00\"");
        }

        @Test
        @DisplayName("a pending assessment opens a version carrying no rate and no balance")
        void aPendingAssessmentPublishesNoPosition() throws IOException {
            post(eventsPath("C-0001"), event("NEGOTIATED",
                "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:452500.00"
                    + "&side=ASSET&triggers=NONE"));
            Response response = get("/api/contracts/C-0001/versions");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"versionCount\":2")
                .contains("\"routedMechanism\":\"MODIFICATION_TEST\"")
                .contains("\"pendingApproval\":true")
                // RecordedEvent's constructor refuses a pending event that carries either, so this
                // is a guard and not a rendering choice: a pre-event balance in these fields would
                // read as an assessment that concluded immaterial.
                .contains("\"eir\":null")
                .contains("\"carryingAmount\":null");
        }

        @Test
        @DisplayName("the response refuses to claim a bitemporality this book does not have")
        void bitemporalityIsNotClaimed() throws IOException {
            Response response = get("/api/contracts/C-0001/versions");

            assertThat(response.body())
                .contains("\"bitemporalityDemonstrated\":false")
                .contains("\"recordedAtSeries\":\"NOT_CARRIED\"")
                .contains("caveat");
            // An invented recorded_at is indistinguishable from a recorded one, which makes it the
            // worst available answer for an endpoint an auditor reads.
            assertThat(response.body()).doesNotContain("\"recordedAt\":\"2");
        }
    }

    @Nested
    @DisplayName("the path parameter is read, and a suffix that is not ours is declined")
    class ThePathParameter {

        @Test
        @DisplayName("the contract acted on is the one named in the path")
        void theContractComesFromThePath() throws IOException {
            // This nested class replaces one called SeamLimits, which asserted that
            // GET /api/contracts/{id} answered 405 because a POST context owned the whole subtree.
            // Routes.route removed that limitation, so the test that pinned it is deleted rather
            // than left asserting a constraint that no longer exists — which would be worse than no
            // test, because it would read as a documented behaviour.
            Response response = post(eventsPath("C-0001"),
                event("STEP_UP_PREDETERMINED", "revisedFlows=2028-06-30:500000.00"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("no contractId was sent in the form at all")
                .contains("\"contractId\":\"C-0001\"")
                .contains("\"routedMechanism\":\"CATCH_UP\"");
        }

        @Test
        @DisplayName("the trace suffix is declined, so unit 1's module can claim it")
        void theTraceSuffixIsDeclined() throws IOException {
            // 06 § 2's audit endpoint (FR-808) is TraceModule's, and it registers on this same
            // prefix. This module returns null for the suffix so the next handler on the prefix is
            // tried; with no TraceModule registered in this fixture nobody claims it and the
            // server's own 404 names the path. Claiming it — with a 404 or a refusal of our own —
            // would make the collision silent and TraceModule unreachable.
            Response response = get("/api/contracts/C-0001/trace?period=2028-05");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body())
                .contains("no handler claimed")
                .contains("/api/contracts/C-0001/trace");
        }

        @Test
        @DisplayName("a suffix nobody serves is a 404 naming the path, never a silent 200")
        void anUnknownSuffixIsNotFound() throws IOException {
            Response response = get("/api/contracts/C-0001/versions/anything-at-all");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).contains("no handler claimed");
        }

        @Test
        @DisplayName("a verb this module does not serve on a path it owns is declined, not answered")
        void anUnservedVerbIsDeclined() throws IOException {
            Response response = post("/api/contracts/C-0001",
                "eventDate=" + EVENT_DATE + "&driver=STEP_UP_PREDETERMINED");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).contains("no handler claimed");
        }
    }

    @Nested
    @DisplayName("a contract the master carries no balance for has nothing to read or route")
    class NoOpeningStateOnFile {

        @Test
        @DisplayName("the read is refused rather than answered from the placeholder state")
        void theReadIsRefused() throws IOException {
            // Seed.CONTRACT_WITHOUT_STATE is FR-905's data condition: the population names C-0003,
            // the contract master does not carry it, and Book.Holding.movementsOnly parks a
            // PLACEHOLDER state alongside its movements purely so they have somewhere to live. That
            // placeholder is a COPY OF C-0001's performing state, so a read that trusted it served
            // C-0001's EIR, principal and 528,407.32 balance under C-0003's id — with one boolean
            // among twenty fields to say so.
            Response response = get("/api/contracts/" + Seed.CONTRACT_WITHOUT_STATE);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body())
                .contains(Seed.CONTRACT_WITHOUT_STATE)
                .contains("no opening balance")
                .contains("FR-905")
                // A distinct machine-readable reason, so a client can tell "the master does not
                // carry this row" from "there is no such contract" without parsing prose.
                .contains("\"reason\":\"NO_OPENING_STATE_ON_FILE\"");
            assertThat(response.body())
                .as("not one figure from the placeholder may reach the wire")
                .doesNotContain("528407.32")
                .doesNotContain("0.010421491800");
        }

        @Test
        @DisplayName("an event against it publishes no restatement and no clean invariant")
        void anEventIsRefused() throws IOException {
            Response response = post(eventsPath(Seed.CONTRACT_WITHOUT_STATE),
                event("STEP_UP_PREDETERMINED",
                    "revisedFlows=2028-06-30:500000.00"));

            assertThat(response.status()).isEqualTo(404);
            // The worst version of this defect: a restated balance and a satisfied CU-1 for a
            // contract with no balance on file — a wrong number wearing a green control result.
            assertThat(response.body())
                .doesNotContain("restatedCarryingAmount")
                .doesNotContain("invariantsClean")
                .doesNotContain("CU-1");
        }
    }

    @Nested
    @DisplayName("the version chain is coherent whatever order events arrive in")
    class TheVersionChain {

        @Test
        @DisplayName("out-of-order submissions still produce intervals that run forwards")
        void outOfOrderSubmissionsAreChainedByEventDate() throws IOException {
            // Submitted late first, then early. Chained on the append order this produced a validTo
            // two months BEFORE its own validFrom on version 2, and left version 1 closing at
            // 2028-07-31 while version 3 opened at 2028-05-31 — two overlapping versions. An
            // interval that runs backwards is not a caveat, it is a wrong answer.
            post(eventsPath("C-0002"), "contractId=C-0002&eventDate=2028-07-31"
                + "&driver=STEP_UP_PREDETERMINED&revisedFlows=2028-08-31:500000.00");
            post(eventsPath("C-0002"), "contractId=C-0002&eventDate=2028-05-31"
                + "&driver=ESG_LINKED&revisedFlows=2028-06-30:500000.00");

            Response response = get("/api/contracts/C-0002/versions");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"versionCount\":3");

            // Version 1 opens at the period start and closes at the EARLIER event; version 2 runs
            // from that event to the later one; version 3 runs open-ended from the later one.
            List<String> from = allOf(response.body(), "validFrom");
            List<String> to = allOf(response.body(), "validTo");
            assertThat(from)
                .as("validFrom, in row order, must be non-decreasing")
                .containsExactly("2028-04-30", "2028-05-31", "2028-07-31");
            assertThat(to)
                .as("each version closes where the next one opens, and the last stays open")
                .containsExactly("2028-05-31", "2028-07-31", "null");
            assertThat(response.body())
                .contains("\"chainedInEventDateOrder\":true")
                .contains("orderCaveat");
        }

        @Test
        @DisplayName("two events on one contract do not compose, and the response says so")
        void successiveEventsDoNotCompose() throws IOException {
            post(eventsPath("C-0001"), event("ESG_LINKED",
                "revisedFlows=2028-06-30:500000.00"));
            Response second = post(eventsPath("C-0001"),
                "contractId=C-0001&eventDate=2028-06-30&driver=STEP_UP_PREDETERMINED"
                    + "&revisedFlows=2028-07-31:500000.00");

            assertThat(second.status()).isEqualTo(200);
            // Nothing here moves the book, so BOTH events are measured from the same 528,407.32
            // opening position — the second is not measured from the 494,843.00 the first restated
            // to. A validFrom/validTo series whose figures did not compose and did not say so would
            // be read as one that did.
            assertThat(second.body())
                .contains("\"carryingAmountBefore\":\"528407.32\"")
                .contains("\"carryingAmountBasis\":\"OPENING_POSITION_ON_FILE\"")
                .contains("compositionCaveat");
            assertThat(get("/api/contracts/C-0001/versions").body())
                .contains("\"figuresCompose\":false")
                .contains("compositionCaveat");
        }

        @Test
        @DisplayName("resubmitting one event is refused, not appended under a duplicate id")
        void aResubmissionIsRefused() throws IOException {
            String form = event("CREDIT_RATCHET_PREDETERMINED",
                "revisedFlows=2028-06-30:500000.00");
            assertThat(post(eventsPath("C-0001"), form).status()).isEqualTo(200);

            // RecordedEvent.idFor is deterministic, so a retry is the same event. Appended, it gave
            // two versions one eventId and made the first of them zero-length — its validTo equal
            // to its own validFrom — and anything resolving a figure by event id would get
            // whichever row came first.
            Response again = post(eventsPath("C-0001"), form);
            // A 409, not a 400: the request is well formed and names a real contract, and what is
            // wrong is the state of the resource it would create.
            assertThat(again.status()).isEqualTo(409);
            assertThat(again.body())
                .contains("EV-C-0001-2028-05-31-CREDIT_RATCHET_PREDETERMINED")
                .contains("already on record");
            assertThat(get("/api/contracts/C-0001/versions").body())
                .as("the log still holds one event, so the chain still has two versions")
                .contains("\"versionCount\":2");
        }
    }

    @Nested
    @DisplayName("nothing is published that the engine did not produce")
    class NothingUnproduced {

        @Test
        @DisplayName("a triggers value naming no trigger is refused, NONE alone asserts the assessment")
        void aTriggersValueOfSeparatorsIsRefused() throws IOException {
            // 'triggers=,' passed the has() check and parsed to an empty list, so the response came
            // back with qualitativeAssessmentPerformed true and an empty trigger list — the exact
            // state the class javadoc says must be inexpressible, because it reports a clean
            // qualitative result nobody produced.
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:452500.00"
                    + "&side=ASSET&triggers=,"));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("names no trigger")
                .contains("NONE");
            assertThat(response.body()).doesNotContain("qualitativeAssessmentPerformed");
        }

        @Test
        @DisplayName("a DECIDED modification test still publishes no post-event balance")
        void aDecidedAssessmentPublishesNoBalance() throws IOException {
            // |440,000 - 500,000| / 500,000 = 0.12 on a liability: B3.3.6's bright line is reached
            // and the engine concludes SUBSTANTIAL, whose implied mechanism is DERECOGNITION — the
            // asset leaving the book. Publishing the pre-event balance as carryingAmountAfter said
            // the carrying amount was unchanged for exactly that event, and disagreed with the
            // RecordedEvent for the same event, which stores null.
            Response response = post(eventsPath("C-0001"), event("NEGOTIATED",
                "originalFlows=2028-06-30:500000.00&revisedFlows=2028-06-30:440000.00"
                    + "&side=LIABILITY&triggers=NONE"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"substantialityConclusion\":\"SUBSTANTIAL\"")
                .contains("\"engineDecided\":true")
                .contains("\"carryingAmountAfter\":null")
                .contains("carryingAmountAfterNote")
                // The balance the assessment measured FROM is still reported, under its own name.
                .contains("\"carryingAmountBefore\":\"528407.32\"");
        }

        @Test
        @DisplayName("a reset whose re-solve found no rate records nothing and opens no version")
        void aRefusedResetIsNotAccepted() throws IOException {
            // A revised leg the lender PAYS: every present value is negative, the target balance is
            // +528,407.32, so f(r) never changes sign at any rate on the ladder or off it. That is
            // NO_SOLUTION, and 03 § 4.3 names a defaulted rate here the most damaging failure
            // available to this engine, because it publishes a plausible figure and leaves no trace.
            Response response = post(eventsPath(FLOATING),
                event("TIME_VALUE_OF_MONEY", "revisedFlows=2028-06-30:-500000.00"));

            assertThat(response.status())
                .as("a solver refusal is an engine answer, so it comes back on a 200")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"routedMechanism\":\"RESET\"")
                .contains("\"solveStatus\":\"NO_SOLUTION\"")
                .contains("\"solved\":false")
                .contains("\"newEir\":null")
                // Recorded, it put a boundary in the history carrying a null rate and a null balance
                // with pendingApproval false — the "assessed event that changed nothing" shape
                // RecordedEvent's constructor refuses for the pending case, reached through the one
                // branch that bypasses that guard.
                .contains("\"accepted\":false")
                .contains("\"eventsOnRecord\":0");
            assertThat(get("/api/contracts/" + FLOATING + "/versions").body())
                .as("a refused reset changed nothing, so the history says nothing")
                .contains("\"versionCount\":1");
        }

        @Test
        @DisplayName("a re-solve the solver flagged is a candidate rate, never the contract's EIR")
        void aFlaggedResolveIsNotPublishedAsTheNewEir() throws IOException {
            // A single revised flow of 1.00 against a 528,407.32 balance. There IS a root — about
            // -99.9998% periodic — and the solver finds it, but only by escalating the ladder, and
            // it lands outside the plausible band, so the status is REQUIRES_REVIEW: computed and
            // usable, flagged rather than published (03 § 4.4(2)).
            //
            // This test exists because the module got this wrong. It keyed off hasRate(), which is
            // true for REQUIRES_REVIEW, and published -0.999998107521 as the new EIR of a performing
            // housing loan on a 200 — with nothing but a prose diagnostic to say it had been
            // flagged. Revert the requiresApproval() branch in EventRouting.reset to a plain
            // hasRate() check and this test fails on newEir being a figure.
            Response response = post(eventsPath(FLOATING),
                event("TIME_VALUE_OF_MONEY", "revisedFlows=2028-06-30:1.00"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"solveStatus\":\"REQUIRES_REVIEW\"")
                .contains("\"requiresApproval\":true")
                .contains("\"solved\":false")
                .as("a flagged rate is reported under its own name and is not the contract's EIR")
                .contains("\"newEir\":null")
                .contains("\"candidateEir\":\"-0.999998107521\"");
            // The version the event opens carries no position, because none was approved.
            assertThat(get("/api/contracts/" + FLOATING + "/versions").body())
                .contains("\"versionCount\":2")
                .contains("\"pendingApproval\":true")
                .contains("\"eir\":null")
                .contains("\"carryingAmount\":null");
        }

        @Test
        @DisplayName("rupee figures on the reads are at presentation scale, like every other figure")
        void figuresAreAtPresentationScale() throws IOException {
            Response response = get("/api/contracts/C-0001");

            assertThat(response.status()).isEqualTo(200);
            // A nil allowance used to reach the wire as "0" beside a "528407.32" balance in the same
            // object. Json's own argument is that the stated scale is information, so a control
            // report reading one field at two paise and its neighbour at none is reading two
            // different statements about one book.
            assertThat(response.body())
                .contains("\"allowance\":\"0.00\"")
                .contains("\"carryingAmount\":\"528407.32\"")
                .contains("\"contractualInterestBilled\":\"5298.16\"");
        }
    }
}
