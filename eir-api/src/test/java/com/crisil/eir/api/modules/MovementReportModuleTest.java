package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.modules.movement.MovementSchedule;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.domain.Money;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The movement schedule, over real HTTP and then at the check itself (FR-805, 06 § 7).
 *
 * <h2>Every expected figure below is derived by hand from reference case 1, not read off the engine</h2>
 *
 * <p>The book is {@code Seed.book()}: three contracts, all product {@code HL}, positioned at the end
 * of period 202805 — month 13 of a ₹10,00,000.00 loan at 1% monthly over 24 months with a
 * ₹15,000.00 processing fee, whose EIR solves to {@code 0.010421491800} periodic. C-0001 performs,
 * C-0002 is Stage 3 and paid nothing, C-0003 has movements and no opening balance and is
 * quarantined.
 *
 * <p><b>The accrual.</b> Both computed contracts open at ₹528,407.32 and accrete one whole period,
 * so {@code AmortisationEngine}'s {@code opening x ((1+r)^1 - 1)} is a plain multiplication:
 *
 * <pre>
 *   528,407.32 x 0.010421491800 = 5,506.792552439976     (exact; 18 significant digits, and
 *                                                         Precision.WORKING carries 28)
 *   presented                   = 5,506.79               (matches the figure 03 and Seed both
 *                                                         publish for month 13)
 * </pre>
 *
 * <p><b>The two closing balances.</b>
 *
 * <pre>
 *   C-0001: 528,407.32 + 5,506.792552439976 - 47,073.47 = 486,840.642552439976 -> 486,840.64
 *   C-0002: 528,407.32 + 5,506.792552439976 -      0.00 = 533,914.112552439976 -> 533,914.11
 * </pre>
 *
 * <p>Both are the figures the specification states independently — 486,840.64 for the performing
 * contract at month 13, 533,914.11 for the Stage 3 contract that received nothing.
 *
 * <p><b>The rounding column is nil on this book, and that is arithmetic rather than luck.</b>
 *
 * <pre>
 *   C-0001: 486,840.64 - (528,407.32 + 5,506.79 - 47,073.47) = 486,840.64 - 486,840.64 = 0.00
 *   C-0002: 533,914.11 - (528,407.32 + 5,506.79 -      0.00) = 533,914.11 - 533,914.11 = 0.00
 * </pre>
 *
 * <p><b>The HL row, which on this book is also the total.</b>
 *
 * <pre>
 *   opening    528,407.32 + 528,407.32 = 1,056,814.64
 *   interest     5,506.79 +   5,506.79 =    11,013.58
 *   cash        47,073.47 +       0.00 =    47,073.47
 *   rounding         0.00 +       0.00 =         0.00
 *   closing    486,840.64 + 533,914.11 = 1,020,754.75
 *
 *   and the column sum FR-805 is about:
 *   1,056,814.64 + 11,013.58 - 47,073.47 + 0.00 = 1,020,754.75    <- ties
 * </pre>
 *
 * <p><b>The ledger total is a paise higher, and both figures are published.</b> Summing the WORKING
 * balances and presenting once:
 *
 * <pre>
 *   486,840.642552439976 + 533,914.112552439976 = 1,020,754.755104879952 -> 1,020,754.76
 *   aggregationResidue = 1,020,754.76 - 1,020,754.75 = 0.01
 * </pre>
 *
 * <p>That paise is why the schedule publishes {@code closingGca} (ties to its own contract detail)
 * and {@code ledgerClosingGca} (ties to the sub-ledger, which is what {@code /api/post} writes to
 * the GL) side by side rather than picking one and being wrong about the other.
 *
 * <h2>Why the second nest exists</h2>
 *
 * <p>Every assertion in the first nest is green, and a green control proves nothing about whether
 * the control works. {@code TheCheckGoesRed} constructs three inputs that break three different
 * legs and asserts the deviation each produces, hand-derived. Without it this test file would be
 * the eighteenth entry in this repository's list of controls that read as coverage and could not
 * fail.
 *
 * <p>Assertions are on substrings of the JSON rather than on a parsed tree, for the reason
 * {@code EirServerTest} gives: this module has no JSON parser, and writing one for the test would be
 * the largest untested thing in the module. The substrings are specific rupee figures and specific
 * key/value pairs, which a formatting change cannot accidentally satisfy.
 */
class MovementReportModuleTest {

    /** Reference case 1 at month 13, opening. Both computed contracts start here. */
    private static final String OPENING = "528407.32";

    private EirServer server;
    private String base;

    @BeforeEach
    void startOnAnEphemeralPort() throws IOException {
        // Port 0: the OS picks, so this runs anywhere and never collides with a developer's own
        // server. Same choice, and the same reason, as EirServerTest.
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
        connection.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        return read(connection);
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        try (var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            return new Response(status, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("the schedule over HTTP, on the seed book")
    class OverHttp {

        @Test
        @DisplayName("the columns sum, and every figure is reference case 1's")
        void theColumnsSum() throws IOException {
            post("/api/run", "");

            Response response = get("/api/reports/movement?period=202805");

            assertThat(response.status()).isEqualTo(200);
            String body = response.body();

            // The five published columns of the HL row and of the total — identical on this book,
            // because all three contracts are product HL. Every one derived in the class javadoc.
            assertThat(body)
                .as("the opening column is both contracts' opening gross carrying amount")
                .contains("\"openingGca\":\"1056814.64\"");
            assertThat(body)
                .as("2 x 5,506.79 gross EIR interest for the period")
                .contains("\"eirInterest\":\"11013.58\"");
            assertThat(body)
                .as("only C-0001 paid; C-0002 is Stage 3 and received nothing")
                .contains("\"cashReceived\":\"47073.47\"");
            assertThat(body)
                .as("both presented rows sum on their own, so the rounding column is nil")
                .contains("\"roundingResidue\":\"0.00\"")
                .contains("\"absoluteRoundingResidue\":\"0.00\"");
            assertThat(body)
                .as("486,840.64 + 533,914.11, the sum of the PRESENTED balances")
                .contains("\"closingGca\":\"1020754.75\"");

            // FR-805 itself: 1,056,814.64 + 11,013.58 - 47,073.47 + 0.00 = 1,020,754.75.
            assertThat(body)
                .as("the arithmetic is published, not merely trusted")
                .contains("\"derivedClosingGca\":\"1020754.75\"")
                .contains("\"columnsSum\":true")
                .contains("\"columnsSumProven\":true")
                .contains("\"deviation\":\"0\"");

            // The sub-ledger figure, a paise higher, and the difference named.
            assertThat(body)
                .as("summing the WORKING balances and presenting once gives 1,020,754.76")
                .contains("\"ledgerClosingGca\":\"1020754.76\"")
                .contains("\"aggregationResidue\":\"0.01\"");

            // Both contract lines, at the figures the specification states.
            assertThat(body)
                .contains("\"contractId\":\"C-0001\"")
                .contains("\"closingGca\":\"486840.64\"")
                .contains("\"contractId\":\"C-0002\"")
                .contains("\"closingGca\":\"533914.11\"")
                .contains("\"openingGca\":\"" + OPENING + "\"");

            // The working balance travels beside the presented one, which is the only way a reader
            // can see where the aggregation paise came from. The scale is 14, not 12: the accrual
            // is a scale-2 balance times a scale-12 rate, so the exact product has scale 14 and
            // Precision.WORKING's 28 significant digits do not round it, which is why the figure
            // ends in two zeros that carry no information and are nonetheless the truth about what
            // the ledger holds.
            assertThat(body).contains("\"closingGcaWorking\":\"486840.64255243997600\"");
            assertThat(body).contains("\"closingGcaWorking\":\"533914.11255243997600\"");
        }

        @Test
        @DisplayName("the quarantined contract is named, not silently dropped")
        void theQuarantinedContractIsNamed() throws IOException {
            post("/api/run", "");

            String body = get("/api/reports/movement?period=202805").body();

            // Three contracts accounted for, two with figures, two in the schedule. A schedule
            // short one contract sums perfectly, which is why the counts are published beside it.
            assertThat(body)
                .contains("\"contractsAccountedFor\":3")
                .contains("\"contractsWithFigures\":2")
                .contains("\"contractsInSchedule\":2")
                .contains("\"productRows\":1");
            assertThat(body)
                .as("FR-905's contract has no opening balance, so no column can carry it")
                .contains("\"excluded\":[{\"contractId\":\"" + Seed.CONTRACT_WITHOUT_STATE + "\"");
            assertThat(body).contains("the run published no figures for it");
        }

        @Test
        @DisplayName("productId filters to the product and leaves the figures alone")
        void theProductFilterNarrows() throws IOException {
            post("/api/run", "");

            String body = get("/api/reports/movement?period=202805&productId=HL").body();

            // All three seed contracts are HL, so the filtered schedule is the whole schedule.
            assertThat(body)
                .contains("\"filterMatched\":true")
                .contains("\"productId\":\"HL\"")
                .contains("\"scope\":\"product HL\"")
                .contains("\"openingGca\":\"1056814.64\"")
                .contains("\"closingGca\":\"1020754.75\"")
                .contains("\"columnsSum\":true");
        }

        @Test
        @DisplayName("a filter matching nothing says so, and names what it could have matched")
        void aFilterMatchingNothingSaysSo() throws IOException {
            post("/api/run", "");

            String body = get("/api/reports/movement?period=202805&productId=LAP").body();

            // The failure mode this guards: an empty movement schedule read as a clean nil period.
            assertThat(body)
                .contains("\"filterMatched\":false")
                .contains("\"productRows\":0")
                .contains("\"contractsInSchedule\":0")
                .contains("\"productsOnFile\":[\"HL\"]");
            assertThat(body)
                .as("a green check over no figures is a true statement about nothing")
                .contains("\"columnsSumProven\":false");
        }

        @Test
        @DisplayName("before a run there are no figures, and the answer says that rather than nil")
        void beforeARunThereAreNoFigures() throws IOException {
            Response response = get("/api/reports/movement?period=202805");

            assertThat(response.status())
                .as("an engine answer, including 'there is nothing', is a 200")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"ran\":false")
                .contains("Roll the period forward first")
                .doesNotContain("\"openingGca\"");
        }

        @Test
        @DisplayName("a period this process never ran is an answer, not a 404")
        void anUnrunPeriodIsAnAnswer() throws IOException {
            post("/api/run", "");

            Response response = get("/api/reports/movement?period=202804");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"ran\":false")
                .contains("no run for period 202804");
        }
    }

    @Nested
    @DisplayName("the edge: a malformed query is the only 4xx")
    class Requests {

        @Test
        @DisplayName("a missing period is a 400, and names the field")
        void aMissingPeriodIsABadRequest() throws IOException {
            Response response = get("/api/reports/movement");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("bad request").contains("period");
        }

        @Test
        @DisplayName("a period that is not a number is a 400, not a defaulted period")
        void aNonNumericPeriodIsABadRequest() throws IOException {
            Response response = get("/api/reports/movement?period=last-month");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("must be a whole number")
                .contains("last-month");
        }

        @Test
        @DisplayName("the wrong verb is a 405, not a silent 404")
        void theWrongVerbIsRefused() throws IOException {
            Response response = post("/api/reports/movement?period=202805", "");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("GET only");
        }
    }

    @Nested
    @DisplayName("the check goes red: three inputs, three legs, three hand-derived deviations")
    class TheCheckGoesRed {

        /** A run of the seed book, so the tests below can break its figures deliberately. */
        private EirService.RunSnapshot run() {
            EirService service = new EirService(Seed.book());
            service.run(FormBody.parse(""));
            return service.lastRunFor(Seed.PERIOD_ID).orElseThrow();
        }

        @Test
        @DisplayName("leg 4: working papers missing for both contracts — the schedule is empty and"
            + " says the whole book is unaccounted for")
        void legFourCatchesAScheduleThatLostTheWholeBook() {
            EirService.RunSnapshot run = run();

            // The failing input: the run published closing balances for C-0001 and C-0002 and the
            // working papers for neither. This is the shape of a run record whose period-balance
            // rows failed to write — the sub-ledger total is right and the movement schedule is
            // empty, and the columns of an empty schedule sum perfectly.
            MovementSchedule schedule = MovementSchedule.over(
                run.runId(), Seed.PERIOD_ID, run.aggregate(), Map.of(), run.book(), null);

            assertThat(schedule.check().satisfied())
                .as("two contracts with balances and no rows must not read as a clean schedule")
                .isFalse();
            // 486,840.64 + 533,914.11 = 1,020,754.75, the whole book, absolute.
            assertThat(schedule.check().deviation().toPlainString()).isEqualTo("1020754.75");
            assertThat(schedule.check().breaches()).hasSize(1);
            assertThat(schedule.check().breaches().get(0).name())
                .contains("placed exactly once");
            assertThat(schedule.check().breaches().get(0).detail())
                .contains("C-0001")
                .contains("appears on 0 product row(s), not exactly one");
            assertThat(schedule.check().proves())
                .as("nothing is in scope, so even a pass would prove nothing")
                .isFalse();
        }

        @Test
        @DisplayName("leg 4: working papers missing for one contract — the columns of what remains"
            + " still sum, and the deviation is that contract's balance")
        void legFourCatchesAScheduleShortOneContract() {
            EirService.RunSnapshot run = run();

            // The failing input: C-0002's working papers are dropped. This is the one that matters,
            // because the resulting schedule is internally perfect — one product row, one contract,
            // 528,407.32 + 5,506.79 - 47,073.47 = 486,840.64 — and short 533,914.11.
            Map<String, ContractComputation> partial = new LinkedHashMap<>(run.computations());
            partial.remove("C-0002");

            MovementSchedule schedule = MovementSchedule.over(
                run.runId(), Seed.PERIOD_ID, run.aggregate(), partial, run.book(), null);

            assertThat(schedule.total().contracts()).isEqualTo(1);
            assertThat(schedule.total().closingGca()).isEqualTo(Money.inr("486840.64"));
            assertThat(schedule.total().closingGca())
                .as("legs 1 to 3 are green: the columns of what is present sum exactly")
                .isEqualTo(schedule.total().derivedClosingGca());

            assertThat(schedule.check().satisfied()).isFalse();
            assertThat(schedule.check().deviation().toPlainString())
                .as("C-0002's closing balance, the amount the schedule is short")
                .isEqualTo("533914.11");
            assertThat(schedule.check().breaches().get(0).detail()).contains("C-0002");
        }

        @Test
        @DisplayName("leg 1: a contract paired with another contract's working papers — the row"
            + " stops summing, by the EMI")
        void legOneCatchesMismatchedWorkingPapers() {
            EirService.RunSnapshot run = run();

            // The failing input: C-0001's result paired with C-0002's working papers. The movement
            // columns then come from a contract that received nothing while the closing balance
            // comes from one that received 47,073.47, so the row is out by exactly the EMI. At the
            // row level:
            //   opening  528,407.32 + 528,407.32 = 1,056,814.64
            //   interest   5,506.79 +   5,506.79 =    11,013.58
            //   cash           0.00 +       0.00 =         0.00   <- C-0002's cash column, twice
            //   derived  1,056,814.64 + 11,013.58 - 0.00 = 1,067,828.22
            //   published closing (from the spine, unchanged)     = 1,020,754.75
            //   out by 1,020,754.75 - 1,067,828.22 = -47,073.47, the EMI C-0001 actually paid
            ContractComputation cTwo = run.computations().get("C-0002");
            Map<String, ContractComputation> crossed = new LinkedHashMap<>();
            crossed.put("C-0001", cTwo);
            crossed.put("C-0002", cTwo);

            MovementSchedule schedule = MovementSchedule.over(
                run.runId(), Seed.PERIOD_ID, run.aggregate(), crossed, run.book(), null);

            assertThat(schedule.check().satisfied()).isFalse();
            // Two published rows are out by 47,073.47 each — the HL row and the total — and the
            // deviation is their ABSOLUTE sum, because each published row is its own claim.
            assertThat(schedule.check().deviation().toPlainString()).isEqualTo("94146.94");
            assertThat(schedule.check().breaches()).hasSize(1);
            assertThat(schedule.check().breaches().get(0).name())
                .contains("the published columns sum");
            assertThat(schedule.check().breaches().get(0).detail())
                .as("both the product row and the total have to name their own break")
                .contains("HL: INR 1056814.64")
                .contains("= INR 1067828.22, not INR 1020754.75 (out by INR -47073.47)")
                .contains("TOTAL: INR 1056814.64");
        }

        @Test
        @DisplayName("leg 2: two residues in opposite directions must not net to a clean report")
        void legTwoRefusesToLetOppositeBreaksNetOff() {
            // Two products, each one contract, each a rupee out — one over, one under. The signed
            // accounting column nets to nil, which is exactly the arithmetic every deviation in
            // this engine is written to avoid, and the absolute column reports 1.00.
            //
            // Constructed rather than run: a real AmortisationRow cannot carry a residue this size
            // (its constructor enforces opening + interest - cash = closing at working precision,
            // which bounds the presented residue at two minor units), so the only honest way to
            // exercise leg 2 is to hand it the rows a defect upstream would produce.
            MovementSchedule.Line over = new MovementSchedule.Line(
                "C-A", "A", Money.inr("100.00"), Money.inr("10.00"), Money.zero(Money.INR),
                Money.inr("0.50"), Money.inr("110.50"), Money.inr("110.50"));
            MovementSchedule.Line under = new MovementSchedule.Line(
                "C-B", "B", Money.inr("200.00"), Money.inr("20.00"), Money.zero(Money.INR),
                Money.inr("-0.50"), Money.inr("219.50"), Money.inr("219.50"));

            List<MovementSchedule.Row> rows = List.of(
                MovementSchedule.rowOver("A", List.of(over)),
                MovementSchedule.rowOver("B", List.of(under)));
            MovementSchedule.Row total = MovementSchedule.totalOver(rows);

            assertThat(total.roundingResidue())
                .as("+0.50 and -0.50: the signed accounting column reads nil")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(total.absoluteRoundingResidue())
                .as("and the absolute column reads 1.00, which is the point")
                .isEqualTo(Money.inr("1.00"));

            Map<String, Money> withFigures = new LinkedHashMap<>();
            withFigures.put("C-A", Money.inr("110.50"));
            withFigures.put("C-B", Money.inr("219.50"));
            MovementSchedule.Check check =
                MovementSchedule.checkOver(rows, total, withFigures);

            assertThat(check.satisfied())
                .as("a schedule whose rounding column is a rupee is not a rounding column")
                .isFalse();
            // Leg 1 is green (each row's five columns still tie); leg 2 is the only breach:
            //   row A: 0.50 - (1 x 0.02) = 0.48
            //   row B: 0.50 - (1 x 0.02) = 0.48
            //   total: 1.00 - (2 x 0.02) = 0.96
            //   0.48 + 0.48 + 0.96 = 1.92
            assertThat(check.breaches()).hasSize(1);
            assertThat(check.breaches().get(0).name()).contains("rounding only");
            assertThat(check.deviation().toPlainString()).isEqualTo("1.92");
            assertThat(check.breaches().get(0).detail())
                .as("the detail has to say the signed column looked clean")
                .contains("the signed column reads INR 0.00");
        }

        @Test
        @DisplayName("leg 3: a total that does not tie to its product rows")
        void legThreeCatchesATotalThatDoesNotTie() {
            MovementSchedule.Line line = new MovementSchedule.Line(
                "C-A", "A", Money.inr("100.00"), Money.inr("10.00"), Money.zero(Money.INR),
                Money.zero(Money.INR), Money.inr("110.00"), Money.inr("110.00"));
            List<MovementSchedule.Row> rows =
                List.of(MovementSchedule.rowOver("A", List.of(line)));

            // The failing input: a total row whose opening column is a rupee light. A bucketing
            // defect — a product row left out of one column's sum — looks exactly like this, and
            // it is invisible on the page because the total's own five columns still tie.
            MovementSchedule.Row honest = MovementSchedule.totalOver(rows);
            MovementSchedule.Row wrong = new MovementSchedule.Row(
                MovementSchedule.TOTAL, honest.contracts(),
                Money.inr("99.00"), honest.eirInterest(), honest.cashReceived(),
                honest.roundingResidue(), honest.absoluteRoundingResidue(),
                Money.inr("109.00"), honest.ledgerClosingGca(), List.of());

            assertThat(wrong.closingGca())
                .as("the wrong total still sums on its own: 99.00 + 10.00 - 0.00 = 109.00")
                .isEqualTo(wrong.derivedClosingGca());

            Map<String, Money> withFigures = Map.of("C-A", Money.inr("110.00"));
            MovementSchedule.Check check =
                MovementSchedule.checkOver(rows, wrong, withFigures);

            assertThat(check.satisfied()).isFalse();
            // opening out by 1.00 and closing out by 1.00, absolute: 2.00.
            assertThat(check.deviation().toPlainString()).isEqualTo("2.00");
            assertThat(check.breaches().get(0).name()).contains("total row ties");
            assertThat(check.breaches().get(0).detail())
                .contains("total openingGca")
                .contains("total closingGca");
        }
    }

    @Nested
    @DisplayName("the check has no invariant id, and says so")
    class TheMissingInvariantId {

        @Test
        @DisplayName("the response names the id it wants rather than borrowing one")
        void theResponseAsksForAnId() throws IOException {
            post("/api/run", "");

            String body = get("/api/reports/movement?period=202805").body();

            // InvariantId belongs to another unit. Borrowing SL-2 or S3-1 would give a named
            // invariant a second claim, and InvariantResult.conjunction keeps only the FIRST
            // breach's deviation among results sharing an id — so a movement break and a journal
            // break under one id would report one deviation and hide the other.
            assertThat(body)
                .contains("\"id\":\"MOVEMENT-COLUMNS-SUM\"")
                .contains("no InvariantId")
                .contains("MV_1");
            assertThat(body)
                .as("the four legs are each reported, so a reader sees which one broke")
                .contains("\"legs\":4")
                .contains("\"breaches\":0");
        }

        @Test
        @DisplayName("the caveats state what the check cannot prove")
        void theCaveatsAreHonest() throws IOException {
            post("/api/run", "");

            String body = get("/api/reports/movement?period=202805").body();

            assertThat(body)
                .as("a control's limits belong beside it, not in a reviewer's memory")
                .contains("What the check does NOT prove")
                .contains("AmortisationRow's constructor refuses a row")
                .contains("Leg 4 is the one that catches the disclosure failure");
        }
    }

    @Nested
    @DisplayName("the module is registered where ApiModules says it is")
    class Registration {

        @Test
        @DisplayName("the route and the spec section are the ones 06 § 7 names")
        void theRouteIsTheSpecified() {
            MovementReportModule module = new MovementReportModule(new EirService(Seed.book()));

            assertThat(MovementReportModule.PATH).isEqualTo("/api/reports/movement");
            assertThat(module.specSection()).isEqualTo("06 § 7");

            List<String> registered = new ArrayList<>();
            module.register(new com.crisil.eir.api.http.Routes() {
                @Override
                public void get(String path,
                    java.util.function.Function<com.sun.net.httpserver.HttpExchange,
                        com.crisil.eir.api.http.Json.Obj> handler) {
                    registered.add("GET " + path);
                }

                @Override
                public void post(String path,
                    java.util.function.Function<FormBody,
                        com.crisil.eir.api.http.Json.Obj> handler) {
                    registered.add("POST " + path);
                }
            });

            assertThat(registered)
                .as("one GET and nothing else: a report does not mutate a run")
                .containsExactly("GET /api/reports/movement");
        }
    }
}
