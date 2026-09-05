package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.domain.Money;
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
 * The four reconciliation reports of 06 § 7, driven over a real socket.
 *
 * <h2>Where every expected figure comes from</h2>
 *
 * <p><b>Nothing below was read off a run of this code.</b> Reference case 1 is a 10,00,000.00 loan
 * at 1% monthly over 24 months with a 15,000.00 processing fee, whose EIR is published as
 * {@code 0.010421491800} periodic, and the seed book positions three contracts at month 13. Every
 * figure asserted here is derived from those two published inputs by hand:
 *
 * <ul>
 *   <li><b>Gross EIR interest</b> = 528,407.32 × 0.010421491800 = <b>5,506.792552439976</b>,
 *       presented 5,506.79. Working precision is 28 significant digits (ADR-0002 /
 *       {@code Precision.WORKING}), so the unrounded figure is what carries into a balance and the
 *       rounding happens once, at presentation.</li>
 *   <li><b>C-0001 closing</b> = 528,407.32 + 5,506.792552… − 47,073.47 = 486,840.642552… →
 *       <b>486,840.64</b>. <b>C-0002 closing</b> (Stage 3, nothing received) = 528,407.32 +
 *       5,506.792552… = 533,914.112552… → <b>533,914.11</b>.</li>
 *   <li><b>SL-1's deviation before posting</b> = the two closing balances against the ledger's two
 *       opening ones: (2 × 5,506.792552439976) − 47,073.47 = −36,059.884895120048, reduced once to
 *       presentation scale = <b>36,059.88</b>. The sub-ledger side totals 1,020,754.755104879952 →
 *       1,020,754.76 and the GL side is 2 × 528,407.32 = <b>1,056,814.64</b>.</li>
 *   <li><b>RC-1's deviation before C-0003 is repaired</b> = the whole of C-0003's billed interest,
 *       because the engine presented no line for it: <b>5,298.16</b>. The engine's side totals
 *       2 × 5,298.16 = 10,596.32 and the CBS feed's 3 × 5,298.16 = <b>15,894.48</b>.</li>
 *   <li><b>The shadow unwind</b> = allowance × EIR = 211,362.93 × 0.010421491800 = 2,202.717042…
 *       → <b>2,202.72</b>, which is 40% of the gross accrual because the allowance is 40% of the
 *       balance it accrues on.</li>
 *   <li><b>The sub-ledger after C-0003 is repaired and the period re-run</b> = (3 × 528,407.32) +
 *       (3 × 5,506.792552439976) − (2 × 47,073.47) = 1,507,595.397657319928 →
 *       <b>1,507,595.40</b>. C-0003 is reference case 1 again, so it closes where C-0001 does.</li>
 * </ul>
 *
 * <h2>Why over a socket, and why on substrings</h2>
 *
 * <p>{@code EirServerTest} sets out the argument and it applies unchanged: the defects this layer
 * introduces live between the service and the wire — a figure emitted as a JSON number and rounded
 * by the reader, an engine refusal mapped onto a 4xx, a route registered at the wrong path. Port 0
 * lets the OS pick. Assertions are on substrings because this module has no JSON parser and writing
 * one for the test would be the largest untested thing in it; the substrings chosen are ones a
 * formatting change cannot accidentally satisfy — a specific rupee figure, a specific invariant id,
 * a specific refusal phrase.
 *
 * <p>The HTTP plumbing below is the same shape as {@code EirServerTest}'s. Duplicated rather than
 * shared, because a test fixture extracted into a base class is a fixture two test classes then
 * cannot change independently, and this one is fifteen lines.
 */
class ReconciliationReportsModuleTest {

    /** Reference case 1's gross EIR interest at month 13, presented. See the class javadoc. */
    private static final String GROSS_EIR_INTEREST = "5506.79";

    /** The two sides of SL-1 before the period's journals are posted. */
    private static final String SUB_LEDGER_TWO_CONTRACTS = "1020754.76";
    private static final String GL_OPENING_TWO_CONTRACTS = "1056814.64";

    /** SL-1's deviation before posting: 47,073.47 − 2 × 5,506.7926, reduced once. */
    private static final String SL_ONE_BREAK = "36059.88";

    /** RC-1's deviation while C-0003 is quarantined: the whole of its billed interest. */
    private static final String RC_ONE_BREAK = "5298.16";

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

    /**
     * Replaces the running server with one over a different book, on a fresh port.
     *
     * <p>The book is fixed at construction — {@code EirService} takes it and the run reads it — so
     * a test that needs a perturbed input replaces the server rather than mutating one in flight.
     * Restarting is also the honest shape: a book whose CBS feed changed mid-run is a condition
     * this system does not model, and simulating it would test something no deployment does.
     */
    private void restartOn(Book book) throws IOException {
        server.stop();
        server = new EirServer(0, new EirService(book));
        server.start();
        base = "http://localhost:" + server.port();
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

    // ---- the four report paths, and the operator steps that move them ----------------------

    private static final String GL = "/api/reports/reconciliation/gl?period=202805";
    private static final String CBS = "/api/reports/reconciliation/cbs?period=202805";
    private static final String STAGE_3 = "/api/reports/reconciliation/stage3?period=202805";
    private static final String FLOOR = "/api/reports/ecl-floor-duality?period=202805";

    private void rollThePeriodForward() throws IOException {
        post("/api/run", "");
    }

    private void postTheJournals() throws IOException {
        post("/api/post", "");
    }

    private void repairAndRerun() throws IOException {
        post("/api/repair", "contractId=" + Seed.CONTRACT_WITHOUT_STATE);
        post("/api/run", "");
    }

    @Nested
    @DisplayName("before any run, all four say so instead of reporting a clean nothing")
    class BeforeAnyRunHasPublished {

        /**
         * The defect this covers is the one this codebase is most careful about: every total over
         * an empty population is nil and every reconciliation over it ties, so an empty report and
         * a reconciled one render identically unless the absence is its own answer.
         */
        @Test
        @DisplayName("each report answers 200 with available:false and names the remedy")
        void noRunIsADistinctAnswer() throws IOException {
            for (String path : new String[] {GL, CBS, STAGE_3, FLOOR}) {
                Response response = get(path);

                assertThat(response.status())
                    .as("a period nobody ran is an answer, not a client error: %s", path)
                    .isEqualTo(200);
                assertThat(response.body())
                    .as("%s must distinguish 'nothing ran' from 'everything ties'", path)
                    .contains("\"available\":false")
                    .contains("NOT the same answer as a period whose reconciliations tie")
                    .contains("POST /api/run");
                // No figure of any kind, so nothing can be read as a tie.
                assertThat(response.body())
                    .as("%s must publish no totals over a population that does not exist", path)
                    .doesNotContain("Total")
                    .doesNotContain("\"satisfied\":true");
            }
        }

        @Test
        @DisplayName("a period that is not the book's is not the book's answer either")
        void anotherPeriodIsNotAnsweredFromThisOne() throws IOException {
            rollThePeriodForward();

            Response response = get("/api/reports/reconciliation/gl?period=202804");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("April must not be answered with May's run — that is a report filed against"
                    + " the wrong month")
                .contains("\"periodId\":202804")
                .contains("\"available\":false");
        }
    }

    @Nested
    @DisplayName("SL-1: the sub-ledger against the general ledger")
    class SubLedgerToGl {

        @Test
        @DisplayName("before posting, red by 36,059.88 with both sides shown")
        void redBeforeTheJournalsArePosted() throws IOException {
            rollThePeriodForward();

            Response response = get(GL);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"report\":\"SL-1\"")
                .contains("\"specSection\":\"06 § 7\"")
                .contains("\"available\":true");
            // The deviation, hand-derived in the class javadoc: 47,073.47 − 2 × 5,506.7926.
            assertThat(response.body())
                .as("SL-1 must be red by the period's own movement before the journals are posted")
                .contains("\"id\":\"SL-1\"")
                .contains("\"satisfied\":false")
                .contains("\"deviation\":\"" + SL_ONE_BREAK + "\"");
            // Both sides, because a difference of nil and a tie of 1,056,814.64 against
            // 1,056,814.64 are the same fact and only the second shows the magnitude reconciled.
            assertThat(response.body())
                .contains("\"subLedgerTotal\":\"" + SUB_LEDGER_TWO_CONTRACTS + "\"")
                .contains("\"glTotal\":\"" + GL_OPENING_TWO_CONTRACTS + "\"")
                .contains("\"accountsBroken\":1")
                .contains("1301-LOANS-GCA");
            // The state the ledger is in, which is what makes a red tie readable as a sequence
            // rather than as a break.
            assertThat(response.body())
                .as("the trial-balance reference must say the ledger has not taken the journals")
                .contains("TB-202805-PRE-POSTING");
        }

        @Test
        @DisplayName("the unexplained residual is reduced once, not differenced from two roundings")
        void theResidualIsReducedOnce() throws IOException {
            rollThePeriodForward();

            Response response = get(GL);

            // 1,020,754.755104879952 − 1,056,814.64. Both operands presented separately would give
            // 1,020,754.76 − 1,056,814.64 = −36,059.88 here too, so the working figure is asserted
            // as well: it is the one that proves nothing rounded on the way through a double.
            assertThat(response.body())
                .as("a figure through a double loses this tail; that is what the string is for")
                .contains("\"differenceWorking\":\"-36059.88489512004800\"");
        }

        /**
         * The two portfolio sides are summed here from the evaluator's own per-account figures,
         * and {@code RunClose.sumOf} computes the same pair privately for the close gate's tie.
         * This is what bounds that duplication: the published sides must differ by exactly the
         * difference SL-1 itself reports, so a sum that stopped agreeing with the evaluator fails
         * a test rather than reaching a report.
         */
        @Test
        @DisplayName("the two published sides differ by exactly the difference SL-1 reports")
        void theTwoSidesAgreeWithTheEvaluatorsOwnDifference() throws IOException {
            rollThePeriodForward();

            String body = get(GL).body();

            // 1,020,754.76 − 1,056,814.64 = −36,059.88, which is the account line's difference and
            // (absolute) SL-1's deviation. All three are asserted so none can drift alone.
            assertThat(body)
                .contains("\"subLedgerTotal\":\"" + SUB_LEDGER_TWO_CONTRACTS + "\"")
                .contains("\"glTotal\":\"" + GL_OPENING_TWO_CONTRACTS + "\"")
                .contains("\"difference\":\"-" + SL_ONE_BREAK + "\"")
                .contains("\"unexplainedTotal\":\"" + SL_ONE_BREAK + "\"")
                .contains("\"deviation\":\"" + SL_ONE_BREAK + "\"");
        }

        @Test
        @DisplayName("after posting, the account ties and the source reference moves with it")
        void greenOnceTheJournalsArePosted() throws IOException {
            rollThePeriodForward();
            postTheJournals();

            Response response = get(GL);

            assertThat(response.body())
                .contains("\"id\":\"SL-1\"")
                .contains("\"satisfied\":true")
                .contains("\"accountsBroken\":0")
                // The ledger now carries the sub-ledger's closing detail, so both sides read
                // 1,020,754.76 and there is nothing left to explain.
                .contains("\"glTotal\":\"" + SUB_LEDGER_TWO_CONTRACTS + "\"")
                .contains("TB-202805-POSTED");
        }

        /**
         * The caveat is load-bearing, not decoration. On this book the ledger is handed the
         * sub-ledger's own total, so a green SL-1 is green by construction; a reader who does not
         * know that will take it as evidence the two books agree.
         */
        @Test
        @DisplayName("a green SL-1 is labelled as green by construction, not by agreement")
        void theGreenTieIsLabelled() throws IOException {
            rollThePeriodForward();
            postTheJournals();

            Response response = get(GL);

            assertThat(response.body())
                .as("a tie that could not have gone red must never be presented as one that did")
                .contains("NOT independently sourced")
                .contains("green by construction")
                .contains("reads the bank's trial balance");
        }

        /**
         * <b>One answer per identifier.</b> SL-1 is published by the close and by this report, and
         * this is the check that they are one answer rather than two that happen to agree today.
         * Both render an invariant result as {@code id, statement, satisfied, deviation, detail} in
         * that order, so the whole SL-1 object is byte-identical when the two agree — and the
         * moment either side's input assembly drifts, this fails.
         */
        @Test
        @DisplayName("the report's SL-1 is the close's SL-1, byte for byte")
        void theReportAndTheCloseAreOneAnswer() throws IOException {
            rollThePeriodForward();

            String closeBody = post("/api/close", "closedBy=financial.controller").body();
            String reportBody = get(GL).body();

            String slOne = "\"id\":\"SL-1\",\"statement\":\"sub-ledger ties to GL\","
                + "\"satisfied\":false,\"deviation\":\"" + SL_ONE_BREAK + "\"";
            assertThat(closeBody)
                .as("the close must still publish SL-1 for this comparison to mean anything")
                .contains(slOne);
            assertThat(reportBody)
                .as("a report that re-derived SL-1 would be a second answer under an id entitled"
                    + " to one — the defect docs/08 records finding three times")
                .contains(slOne);
        }
    }

    @Nested
    @DisplayName("RC-1: the contractual leg against core banking")
    class ContractualLegToCbs {

        /**
         * <b>The proof that RC-1's amount leg can fail at all.</b>
         *
         * <p>This control was built with both of its legs reading one field. {@code OpeningState}
         * documents {@code contractualInterestBilled} as "what the borrower was billed, from the
         * CBS", the {@code CoreBankingFeed} reads it because that is its job, and the engine's leg
         * used to read it too because nothing in the month-end run computed a contractual accrual.
         * Two figures for one contract were the same figure twice, so the deviation was nil for
         * every value that column could hold. Confirmed against a live cluster before the fix: a
         * single {@code UPDATE cbs_billed_interest SET billed_interest = 99999.99} moved both legs
         * together.
         *
         * <p>So this test moves the CBS figure and nothing else, and requires that exactly one leg
         * follows it. It is the mutation this repository's convention demands for every published
         * invariant — name the input that makes the control fail and construct it — and it is the
         * mutation that would have caught the original wiring on the day it was written.
         *
         * <h2>The derivation</h2>
         *
         * <p>C-0001's engine leg is its own: {@code openingContractual x contractual rate} =
         * 529,815.61 x 0.01 = <b>5,298.1561</b>, expressed at the scale the borrower was billed
         * (paise, once, on the way in) = <b>5,298.16</b>. The CBS figure is replaced with
         * <b>6,000.00</b>, a break of 5,298.16 - 6,000.00 = <b>-701.84</b>: negative because this
         * package signs every difference engine-minus-CBS, so a negative figure means the borrower
         * was billed more than the engine projected.
         *
         * <p>C-0002 is untouched and must stay tied at 5,298.16 both ways. That matters as much as
         * the break: a change that moved every line would show a control responding to the book
         * rather than to the contract whose input moved.
         */
        @Test
        @DisplayName("moving the CBS figure alone breaks exactly one line — RC-1 can fail")
        void movingTheCbsFigureAloneBreaksOneLine() throws IOException {
            Book perturbed = Seed.book();
            Book.Holding original = perturbed.holding("C-0001").orElseThrow();
            ContractStateSource.OpeningState state = original.state();
            // Only contractualInterestBilled moves. openingContractual — which the engine's own
            // leg accrues on — is left exactly as the seed has it, so the engine's figure is
            // unchanged by construction and any movement in it is this test failing, not passing.
            perturbed.put(Book.Holding.onFile(
                original.contractId(), original.productId(), original.entityId(),
                original.description(),
                new ContractStateSource.OpeningState(
                    state.terms(), state.eir(), state.openingGca(), state.openingContractual(),
                    state.stage(), state.allowance(), state.eclEngineVersion(),
                    Money.inr("6000.00")),
                original.period()));

            restartOn(perturbed);
            rollThePeriodForward();

            Response response = get(CBS);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("RC-1 must go red on an amount, not only on a presence break")
                .contains("\"id\":\"RC-1\"")
                .contains("\"satisfied\":false");
            assertThat(response.body())
                .as("the engine's leg is derived from openingContractual and must NOT follow the"
                    + " CBS column; if it does, the two legs are still one figure")
                .contains("\"contractId\":\"C-0001\",\"engineContractualInterest\":\"5298.16\","
                    + "\"cbsBilledInterest\":\"6000.00\",\"presence\":\"BOTH\","
                    + "\"difference\":\"-701.84\"");
            assertThat(response.body())
                .as("C-0002's input did not move, so its line must still tie — a control that"
                    + " reddened every line would be responding to the book, not the contract")
                .contains("\"contractId\":\"C-0002\",\"engineContractualInterest\":\"5298.16\","
                    + "\"cbsBilledInterest\":\"5298.16\",\"presence\":\"BOTH\","
                    + "\"difference\":\"0.00\"");
        }

        @Test
        @DisplayName("a contract the CBS billed and the engine never projected is a presence break")
        void thePresenceBreakIsRedByTheWholeBilledAmount() throws IOException {
            rollThePeriodForward();

            Response response = get(CBS);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"report\":\"RC-1\"")
                .contains("\"id\":\"RC-1\"")
                .contains("\"satisfied\":false")
                // The whole of C-0003's billed interest, because the engine presented no figure at
                // all for it. An amount comparison could never find this.
                .contains("\"deviation\":\"" + RC_ONE_BREAK + "\"")
                .contains("\"presenceBreaks\":1");
            // 2 × 5,298.16 against 3 × 5,298.16.
            assertThat(response.body())
                .contains("\"totalEngineContractualInterest\":\"10596.32\"")
                .contains("\"totalCbsBilledInterest\":\"15894.48\"");
            // The diagnosis on the line, not just the money: null on the engine side and CBS_ONLY.
            assertThat(response.body())
                .as("which side is missing is the finding; a nil on both sides would tie")
                .contains("\"contractId\":\"C-0003\",\"engineContractualInterest\":null")
                .contains("\"presence\":\"CBS_ONLY\"");
        }

        @Test
        @DisplayName("repairing the input and re-running ties it, rather than accepting it")
        void greenAfterTheInputIsFixed() throws IOException {
            rollThePeriodForward();
            repairAndRerun();

            Response response = get(CBS);

            assertThat(response.body())
                .contains("\"id\":\"RC-1\"")
                .contains("\"satisfied\":true")
                .contains("\"presenceBreaks\":0")
                .contains("\"contractsBroken\":0")
                // Three contracts on each side now: 3 × 5,298.16 both ways.
                .contains("\"totalEngineContractualInterest\":\"15894.48\"")
                .contains("\"totalCbsBilledInterest\":\"15894.48\"");
        }

        /**
         * <b>What this test used to assert, and why the change is the point.</b> It required the
         * report to carry the caveat "AMOUNT leg cannot disagree on this book / PRESENCE leg can",
         * which was the honest label while both legs read one field. The label was correct and the
         * control it described was not, and a caveat is not a substitute for a working control —
         * it is what a system says instead of fixing one. Now that the engine derives its own leg
         * ({@code movingTheCbsFigureAloneBreaksOneLine} proves it can break), the old text would
         * be a false statement about a control that works, so the report must no longer carry it.
         *
         * <p>The remaining limit is real and narrower, and the report must still disclose it: the
         * engine accrues at 28 significant digits and the CBS bills in paise, so a difference
         * below half a paise per contract is absorbed by the rule that expresses the accrual at
         * the billed scale. Asserting the absence of the old claim as well as the presence of the
         * new one, because a report that carried both would be telling an operator two things.
         */
        @Test
        @DisplayName("the report discloses the sub-paise limit and no longer claims the amount"
            + " leg cannot disagree")
        void theAmountLegsRemainingLimitIsLabelled() throws IOException {
            rollThePeriodForward();

            Response response = get(CBS);

            assertThat(response.body())
                .as("the amount leg works now; a caveat saying otherwise would send an operator"
                    + " to investigate the feed for a break the engine had genuinely found")
                .doesNotContain("AMOUNT leg cannot disagree on this book");
            assertThat(response.body())
                .as("the leg that replaced it must be named, or a reader cannot tell whether the"
                    + " engine's figure is a derivation or a second read of the feed")
                .contains("RC-1's two legs are now two derivations")
                .contains("ContractPipeline's own contractual accretion");
            assertThat(response.body())
                .as("03 § 5.7 allows a rule accounting for a structural difference and forbids a"
                    + " tolerance on the residue; which of the two this is must be on the report")
                .contains("cannot see is a difference below half a paise per contract")
                .contains("not a tolerance on the residue");
        }

        @Test
        @DisplayName("the report's RC-1 is the close's RC-1, byte for byte")
        void theReportAndTheCloseAreOneAnswer() throws IOException {
            rollThePeriodForward();

            String closeBody = post("/api/close", "").body();
            String reportBody = get(CBS).body();

            String rcOne = "\"id\":\"RC-1\",\"statement\":\"contractual leg ties to core banking\","
                + "\"satisfied\":false,\"deviation\":\"" + RC_ONE_BREAK + "\"";
            assertThat(closeBody).contains(rcOne);
            assertThat(reportBody)
                .as("RC-1's engine side is assembled once, by EirService, precisely so that the"
                    + " close and this report cannot be two answers")
                .contains(rcOne);
        }
    }

    @Nested
    @DisplayName("S3-1: the four-way — carrying amount, shadow unwind, suspense, recognised")
    class StageThreeFourWay {

        @Test
        @DisplayName("all four quantities are published for the suppressed contract")
        void thefourQuantitiesArePublished() throws IOException {
            rollThePeriodForward();

            Response response = get(STAGE_3);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"report\":\"S3-1\"")
                .contains("\"contractsWithRecognitionSuppressed\":1")
                .contains("\"contractId\":\"C-0002\"")
                .contains("\"stage\":\"STAGE_3\"");
            assertThat(response.body())
                .as("the four quantities 03 § 7.3 requires the engine to maintain")
                // (1) the carrying-amount roll-forward: 528,407.32 + 5,506.79 − nil = 533,914.11
                .contains("\"openingGrossCarryingAmount\":\"528407.32\"")
                .contains("\"grossBasisInterest\":\"" + GROSS_EIR_INTEREST + "\"")
                .contains("\"closingGrossCarryingAmount\":\"533914.11\"")
                // (2) the ECL discount unwind: 211,362.93 × 0.010421491800 = 2,202.717042…
                .contains("\"shadowUnwind\":\"2202.72\"")
                // (3) the suspense ledger: the whole billed 5,298.16 charged, nothing recovered
                .contains("\"chargedToSuspense\":\"5298.16\"")
                .contains("\"recoveredFromSuspense\":\"0.00\"")
                .contains("\"suspenseClosing\":\"5298.16\"")
                // (4) recognised income: nil, because the stage suppresses it
                .contains("\"recognisedIncome\":\"0.00\"")
                .contains("\"incomeSuppressed\":true");
        }

        @Test
        @DisplayName("each of the four legs carries its own residual, and all four reconcile")
        void everyLegIsPublishedSeparately() throws IOException {
            rollThePeriodForward();

            Response response = get(STAGE_3);

            // Named, not numbered only: a reconciliation that reports one of its four breaks is
            // worse than one that reports none, because it looks like it has been read.
            assertThat(response.body())
                .contains("carrying-amount roll-forward")
                .contains("suspense ledger movement against billed interest")
                .contains("recognised income against the stage")
                .contains("cash applied to interest against suspense recovered");
            assertThat(response.body())
                .contains("\"leg\":1").contains("\"leg\":2")
                .contains("\"leg\":3").contains("\"leg\":4");
            assertThat(response.body())
                .as("C-0002 accrues 5,506.79, receives nothing, suspends the whole 5,298.16 billed"
                    + " and recognises nil — every leg is exactly nil")
                .contains("\"fourWaysReconciling\":1")
                .contains("\"fourWaysBroken\":0")
                .contains("\"reconciles\":true");
        }

        /**
         * S3-1 has exactly one publication site per contract — {@code Stage3Reconciliation}'s own
         * constructor refuses a result carrying any other id — so a portfolio-level S3-1 row would
         * be a fifth answer. The portfolio figure is a total of the per-contract deviations and is
         * published under its own name.
         */
        @Test
        @DisplayName("there is one S3-1 result per suppressed contract and no portfolio-level one")
        void s3OneIsPublishedOncePerContract() throws IOException {
            rollThePeriodForward();

            String body = get(STAGE_3).body();

            assertThat(body.split("\"id\":\"S3-1\"", -1).length - 1)
                .as("one S3-1 per suppressed contract, and this book has one such contract")
                .isEqualTo(1);
            assertThat(body)
                // At presentation scale, like every other figure in the response: a portfolio
                // total rendered at working precision beside per-leg residuals at two places
                // visibly fails to add up to the rows above it.
                .contains("\"totalAbsoluteResidual\":\"0.00\"")
                .contains("It is not a fifth S3-1")
                .as("the total must be absolute, or two contracts broken in opposite directions"
                    + " net to a reconciled period")
                .contains("The total is ABSOLUTE");
        }

        @Test
        @DisplayName("a quarantined contract has no four-way, and the report says which")
        void aQuarantinedContractIsNamedRatherThanDropped() throws IOException {
            rollThePeriodForward();

            Response response = get(STAGE_3);

            assertThat(response.body())
                .as("C-0002 is reconciled and C-0003 was never computed; a report that showed only"
                    + " the first would foot perfectly over two thirds of the book")
                .contains("\"populationSize\":3")
                .contains("\"computed\":2")
                .contains("\"quarantined\":1")
                .contains("\"quarantinedContracts\":[\"C-0003\"]")
                .contains("a tie over a partial population");
        }
    }

    @Nested
    @DisplayName("PF-1: the pre-floor and post-floor provisions side by side")
    class EclFloorDuality {

        @Test
        @DisplayName("both columns are published, and the nil floor is labelled not hidden")
        void bothColumnsAndTheCaveat() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"report\":\"PF-1\"")
                // C-0002's 40% lifetime allowance, and nil on the performing contract.
                .contains("\"totalPreFloorAccountingEcl\":\"211362.93\"")
                .contains("\"totalPostFloorReportedProvision\":\"211362.93\"")
                .contains("\"totalFlooredBy\":\"0.00\"")
                .contains("\"exposuresWhereTheFloorBinds\":0")
                .contains("\"regulatoryFloorSupplied\":false");
            assertThat(response.body())
                .as("a nil-against-nil duality reads as tied on every run ever made, which is"
                    + " exactly why RunClose refuses to synthesise this tie")
                .contains("NO regulatory floor is supplied by this book")
                .contains("Do not read these equal columns as agreement")
                .contains("cannot go red");
        }

        @Test
        @DisplayName("the pre-floor figure survives, per exposure, with the model that measured it")
        void thePreFloorFigureSurvives() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR);

            assertThat(response.body())
                .as("03 § 7.5's forbidden implementation is compute, floor, store the result —"
                    + " after which the EIR-derived figure exists nowhere")
                .contains("\"contractId\":\"C-0002\"")
                .contains("\"preFloorAccountingEcl\":\"211362.93\"")
                .contains("\"regulatoryFloor\":\"0.00\"")
                .contains("\"postFloorReportedProvision\":\"211362.93\"")
                .contains("\"eclEngineVersion\":\"ECL-MODEL-2028.05\"");
        }

        @Test
        @DisplayName("PF-2 is withheld while no basis is stated, rather than defaulted to a pass")
        void pfTwoIsNotInvented() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR);

            assertThat(response.body())
                .as("ACCOUNT is permitted in every stage, so a defaulted basis would make PF-2"
                    + " pass on every row — a control asserted over a fabricated input")
                .contains("\"pf2Asserted\":false")
                .contains("\"basisPermitted\":null")
                .contains("PF-2 is NOT asserted")
                .doesNotContain("\"id\":\"PF-2\"");
        }

        /**
         * {@code FloorApplication.apply} cannot be reached without a basis, so an unstated one is
         * reached with a placeholder — and the record it returns then carries that placeholder on
         * {@code basis()}, inside {@code describe()} and as a PF-2 result. Publishing any of them
         * told a reader the ACPIR 90 floor had been applied account by account on a Stage 3
         * exposure, in the same response that said no basis was stated. Found in review.
         */
        @Test
        @DisplayName("the placeholder basis apply() is reached with never reaches the response")
        void thePlaceholderBasisDoesNotLeak() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR);

            assertThat(response.body())
                .as("a basis nobody stated must not appear as a basis the floor was applied on")
                .contains("\"basisApplied\":null")
                .doesNotContain("\"basisApplied\":\"ACCOUNT\"")
                .doesNotContain("on a ACCOUNT basis")
                .doesNotContain("on a PORTFOLIO basis");
            // The stated case still publishes it, or the field would be useless.
            assertThat(get(FLOOR + "&basis=ACCOUNT").body())
                .contains("\"basisApplied\":\"ACCOUNT\"");
        }

        /**
         * <b>The failing input, constructed.</b> ACPIR 90 makes account-level flooring mandatory
         * for Stage 3, so a caller stating a {@code PORTFOLIO} basis breaks PF-2 on C-0002 with the
         * reported provision as the deviation — a pooled floor averages the shortfall on accounts
         * that have one against accounts that do not. This is the one control on these four
         * endpoints that can go red on this book without the book being wrong, and it is why the
         * basis is taken from the request rather than defaulted.
         */
        @Test
        @DisplayName("a PORTFOLIO basis breaks PF-2 on the Stage 3 exposure, by 211,362.93")
        void pfTwoFailsOnAPooledFloorInStageThree() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR + "&basis=PORTFOLIO");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"pf2Asserted\":true")
                .contains("\"floorBasisStatedAs\":\"PORTFOLIO\"")
                .contains("\"breaches\":1")
                .contains("\"id\":\"PF-2\"")
                .contains("\"satisfied\":false")
                // The deviation is the provision to be restated, not a count: one breach of ten
                // rupees and one of ten crore are not the same finding.
                .contains("\"deviation\":\"211362.93\"")
                .contains("where ACCOUNT is mandatory");
        }

        @Test
        @DisplayName("an ACCOUNT basis is asserted and passes, in both stages")
        void pfTwoPassesOnAccountLevel() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR + "&basis=ACCOUNT");

            assertThat(response.body())
                .as("ACCOUNT is permitted everywhere — flooring a performing exposure account by"
                    + " account is more expensive and not wrong")
                .contains("\"pf2Asserted\":true")
                .contains("\"breaches\":0")
                .contains("\"id\":\"PF-2\"")
                .contains("\"floorBasisStatedAs\":\"ACCOUNT\"");
        }

        @Test
        @DisplayName("an exposure the master carries no state for is named, not silently absent")
        void anExposureWithoutStateIsNamed() throws IOException {
            rollThePeriodForward();

            Response response = get(FLOOR);

            assertThat(response.body())
                .as("an exposure absent from a provision report is absent from both of its columns"
                    + " and from every total, and the report would foot perfectly without it")
                .contains("\"exposures\":2")
                .contains("\"exposuresWithoutStateOnFile\":[\"C-0003\"]");
        }
    }

    @Nested
    @DisplayName("every report carries the population it was over")
    class PopulationHonesty {

        @Test
        @DisplayName("while a contract is quarantined, all four warn that the tie is partial")
        void aPartialPopulationIsDeclaredOnEveryReport() throws IOException {
            rollThePeriodForward();
            postTheJournals();

            for (String path : new String[] {GL, CBS, STAGE_3, FLOOR}) {
                Response response = get(path);

                assertThat(response.body())
                    .as("%s ties over 2 of 3 contracts; the third is absent from both sides of"
                        + " every figure and a reader must be told before believing a green row",
                        path)
                    .contains("\"populationSize\":3")
                    .contains("This reconciliation is over 2 of 3 contracts")
                    .contains("POST /api/repair");
            }
        }

        @Test
        @DisplayName("once the input is repaired the warning goes away, because it is no longer true")
        void theWarningIsConditional() throws IOException {
            rollThePeriodForward();
            repairAndRerun();
            postTheJournals();

            Response response = get(GL);

            assertThat(response.body())
                .as("a caveat printed unconditionally is a caveat nobody reads")
                .contains("\"quarantined\":0")
                .contains("\"quarantinedContracts\":[]")
                .doesNotContain("a tie over a partial population");
            // Three contracts now: (3 × 528,407.32) + (3 × 5,506.792552…) − (2 × 47,073.47).
            assertThat(response.body())
                .contains("\"subLedgerTotal\":\"1507595.40\"")
                .contains("\"contractsOnTheSubLedgerSide\":3");
        }
    }

    @Nested
    @DisplayName("the edge keeps a malformed request and a refusal apart")
    class RequestHandling {

        @Test
        @DisplayName("an absent period is a 400 naming the parameter, not the book's own period")
        void anAbsentPeriodIsRefused() throws IOException {
            rollThePeriodForward();

            Response response = get("/api/reports/reconciliation/gl");

            assertThat(response.status())
                .as("defaulting to whatever period is loaded files the report against the wrong"
                    + " month, and nothing downstream would notice")
                .isEqualTo(400);
            assertThat(response.body()).contains("period").contains("absent");
        }

        @Test
        @DisplayName("a period that is not a number is a 400")
        void aNonNumericPeriodIsRefused() throws IOException {
            Response response = get("/api/reports/reconciliation/cbs?period=May-2028");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("whole number");
        }

        @Test
        @DisplayName("a period that is not a YYYYMM accounting period is a 400")
        void aMalformedPeriodIdIsRefused() throws IOException {
            Response response = get("/api/reports/reconciliation/stage3?period=202813");

            assertThat(response.status())
                .as("month 13 partitions the ledger into a partition nobody will look in")
                .isEqualTo(400);
            assertThat(response.body()).contains("YYYYMM").contains("202813");
        }

        @Test
        @DisplayName("a basis that names no ACPIR 90 basis is a 400 that names both")
        void anUnknownBasisIsRefused() throws IOException {
            Response response = get(FLOOR + "&basis=POOL");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("POOL")
                .contains("ACCOUNT")
                .contains("PORTFOLIO");
        }

        @Test
        @DisplayName("the wrong verb on a report is a 405, not a 404 that misdirects an integrator")
        void theWrongVerbIsRefused() throws IOException {
            Response response = post("/api/reports/reconciliation/gl", "period=202805");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("GET only");
        }
    }
}
