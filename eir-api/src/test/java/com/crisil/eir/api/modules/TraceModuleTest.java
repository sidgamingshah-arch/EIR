package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-808's trace, driven over real HTTP on a real socket, and its arithmetic driven directly.
 *
 * <p><b>Why over a socket.</b> The same reason {@code EirServerTest} gives: every defect this layer
 * can introduce lives between the service and the wire — a figure emitted as a JSON number and
 * rounded by the reader, an engine refusal mapped onto a 4xx so a quarantined contract is
 * indistinguishable from a broken endpoint, a path parsed one segment short. A test that called the
 * module directly would miss all three. Port 0 lets the OS pick.
 *
 * <p><b>Where the expected figures come from.</b> Reference case 1, by hand, and nothing here was
 * read off this engine. A 10,00,000.00 loan at 1% monthly over 24 months with a 15,000.00 processing
 * fee solves to 0.010421491800 periodic; at month 13 the opening gross carrying amount is 528,407.32
 * and the contractual balance is 529,815.61. Every other figure asserted below is derived from those
 * in a comment beside the assertion. Two of them — the 1,408.29 unamortised fee and the 528,407.32
 * opening — appear independently in 06 § 2.3's own worked example, which is a corroboration from
 * outside this repository's code.
 *
 * <p>Assertions are on substrings of the JSON rather than on a parsed tree, because this module has
 * no JSON parser. The substrings include the field name and the quotes, so a figure that arrived as
 * a JSON number, or under a neighbouring key, does not satisfy them.
 */
class TraceModuleTest {

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

    /** A run first, because a trace resolves a figure a run published. */
    private Response traceAfterARun(String query) throws IOException {
        post("/api/run", "");
        return get("/api/contracts/" + query);
    }

    @Nested
    @DisplayName("the derived quantities, and the inputs that make each one fail")
    class TheArithmetic {

        private static final Money OPENING_GCA = Money.inr("528407.32");
        private static final Money OPENING_CONTRACTUAL = Money.inr("529815.61");
        private static final Money BILLED = Money.inr("5298.16");
        private static final Rate EIR =
            Rate.periodic(new BigDecimal("0.010421491800"), 12);
        private static final BigDecimal ONE_WHOLE_PERIOD = BigDecimal.ONE;

        /**
         * 528,407.32 x 0.010421491800, by long multiplication:
         *
         * <pre>
         *   52840732 x 104214918 = 5,506,792,552,439,976   (integer, both operands scaled up)
         *   scaled back by 1e-12                 = 5,506.792552439976
         * </pre>
         *
         * which is 5,506.79 at paise — the gross EIR interest reference case 1 publishes for month
         * 13. The exact working value matters below, because the fee split is a subtraction from it.
         */
        private static final Money IMPLIED_INTEREST = Money.inr("5506.792552439976");

        @Test
        @DisplayName("the accrual at the stored rate reproduces reference case 1's 5,506.79")
        void theImpliedAccrualIsCaseOnes() {
            Money implied = TraceArithmetic.impliedEirInterest(
                OPENING_GCA, EIR, ONE_WHOLE_PERIOD);

            assertThat(implied.amount())
                .as("528,407.32 x 0.010421491800 = 5,506.792552439976, by hand")
                .isEqualByComparingTo(IMPLIED_INTEREST.amount());
            assertThat(implied.atPresentationScale().amount().toPlainString())
                .isEqualTo("5506.79");
        }

        /**
         * The failing input, constructed. A run that accrued at 2% monthly and published the 1%
         * rate it was carrying: 1,000,000.00 x 0.01 = 10,000.00 implied against a published
         * 20,000.00, so the residual is exactly 10,000.00. That is
         * {@code InvariantId.SG_1}'s defect — a rate that moved without being stamped — and no
         * figure-comparison test can see it, because both sides of such a test come from the run.
         */
        @Test
        @DisplayName("an accrual at a rate other than the stored one opens the residual")
        void theAccrualResidualCatchesTheWrongRate() {
            Money implied = TraceArithmetic.impliedEirInterest(
                Money.inr("1000000.00"), Rate.periodic(new BigDecimal("0.010000000000"), 12),
                ONE_WHOLE_PERIOD);

            assertThat(implied.atPresentationScale().amount().toPlainString())
                .as("1,000,000.00 at 1% for one whole period is exactly 10,000.00")
                .isEqualTo("10000.00");
            assertThat(TraceArithmetic.accrualResidual(Money.inr("20000.00"), implied)
                    .atPresentationScale().amount().toPlainString())
                .as("a period accrued at 2% and published at 1% is out by the whole 10,000.00")
                .isEqualTo("10000.00");
            assertThat(TraceArithmetic.accrualResidual(Money.inr("10000.00"), implied).isZero())
                .as("and nil where the two agree, or the control would refuse every clean period")
                .isTrue();
        }

        /**
         * The second failing input: the right rate over the wrong exponent. A row declaring a
         * two-period accrual implies (1.01)^2 - 1 = 0.0201, so 1,000,000.00 x 0.0201 = 20,100.00,
         * and one period's interest published against it is out by 10,100.00. Reached the same way
         * as {@code Seed.periodVector}'s own note: a flow labelled 13 in a one-period segment
         * declares a thirteen-period accrual.
         */
        @Test
        @DisplayName("an accrual over the wrong exponent opens the residual too")
        void theAccrualResidualCatchesTheWrongExponent() {
            Money impliedOverTwo = TraceArithmetic.impliedEirInterest(
                Money.inr("1000000.00"), Rate.periodic(new BigDecimal("0.010000000000"), 12),
                new BigDecimal("2"));

            assertThat(impliedOverTwo.atPresentationScale().amount().toPlainString())
                .as("(1.01)^2 - 1 = 0.0201, so 1,000,000.00 accretes 20,100.00 over two periods")
                .isEqualTo("20100.00");
            assertThat(TraceArithmetic.accrualResidual(Money.inr("10000.00"), impliedOverTwo)
                    .atPresentationScale().amount().toPlainString())
                .isEqualTo("10100.00");
        }

        @Test
        @DisplayName("the fee split is 06 § 2.3's own worked figures: 208.63 out of 1,408.29")
        void theFeeSplitIsTheSpecificationsOwn() {
            // 5,506.792552439976 - 5,298.16 = 208.632552439976, which is 208.63 at paise.
            assertThat(TraceArithmetic.feeAmortised(IMPLIED_INTEREST, BILLED)
                    .atPresentationScale().amount().toPlainString())
                .isEqualTo("208.63");
            // 529,815.61 - 528,407.32 = 1,408.29. 06 § 2.3's example publishes exactly this as the
            // unamortised fee, which is a figure sourced from the specification and not from here.
            assertThat(TraceArithmetic
                    .unamortisedFeeBroughtForward(OPENING_CONTRACTUAL, OPENING_GCA)
                    .atPresentationScale().amount().toPlainString())
                .isEqualTo("1408.29");
            // 1,408.29 - 208.632552439976 = 1,199.657447560024, which is 1,199.66 at paise.
            assertThat(TraceArithmetic.unamortisedFeeCarriedForward(
                    OPENING_CONTRACTUAL, OPENING_GCA, IMPLIED_INTEREST, BILLED)
                    .atPresentationScale().amount().toPlainString())
                .isEqualTo("1199.66");
        }

        @Test
        @DisplayName("the two fee legs tie where the cash book applied what the schedule expected")
        void theTwoFeeLegsTie() {
            // Closing GCA: 528,407.32 + 5,506.792552439976 - 47,073.47 = 486,840.642552439976.
            Money closingGca = Money.inr("486840.642552439976");
            Money onSchedule = TraceArithmetic.unamortisedFeeCarriedForward(
                OPENING_CONTRACTUAL, OPENING_GCA, IMPLIED_INTEREST, BILLED);
            // Cash book leg: 529,815.61 + 5,298.16 - 47,073.47 - 486,840.642552439976
            //              = 535,113.77 - 47,073.47 - 486,840.642552439976
            //              = 1,199.657447560024. The same figure, from the other side.
            Money onCashBook = TraceArithmetic.unamortisedFeeFromCashBook(
                OPENING_CONTRACTUAL, BILLED, Money.inr("47073.47"), closingGca);

            assertThat(onCashBook.amount()).isEqualByComparingTo(onSchedule.amount());
            assertThat(TraceArithmetic.feeLegResidual(onSchedule, onCashBook).isZero()).isTrue();
        }

        /**
         * The third failing input. The cash book applied 47,000.00 against a 47,073.47 instalment —
         * a receipt short by 73.47, which the flow vector knows nothing about because the vector
         * carries what the schedule expected. The residual is the shortfall itself:
         *
         * <pre>
         *   529,815.61 + 5,298.16 - 47,000.00 - 486,840.642552439976 = 1,273.127447560024
         *   1,273.127447560024 - 1,199.657447560024                  =        73.47
         * </pre>
         *
         * <p>What this control does <em>not</em> catch, stated so nobody relies on it for the wrong
         * thing: a receipt of the right total applied to the wrong leg. {@code cashApplied()} is the
         * sum of the two legs, so the split cancels. That case is S3-1's fourth leg, which
         * reconciles cash applied to interest against suspense recovered.
         */
        @Test
        @DisplayName("a receipt short by 73.47 opens the fee-leg residual to exactly 73.47")
        void theFeeLegResidualNamesTheMisappliedAmount() {
            Money closingGca = Money.inr("486840.642552439976");
            Money onSchedule = TraceArithmetic.unamortisedFeeCarriedForward(
                OPENING_CONTRACTUAL, OPENING_GCA, IMPLIED_INTEREST, BILLED);
            Money onCashBook = TraceArithmetic.unamortisedFeeFromCashBook(
                OPENING_CONTRACTUAL, BILLED, Money.inr("47000.00"), closingGca);

            assertThat(TraceArithmetic.feeLegResidual(onSchedule, onCashBook).amount())
                .as("the residual is the amount misapplied, not a tolerance breach")
                .isEqualByComparingTo(new BigDecimal("73.47"));
        }
    }

    @Nested
    @DisplayName("a published figure resolves to its inputs")
    class ThePublishedCase {

        @Test
        @DisplayName("C-0001's trace carries the versions, the computation and the roll-forward")
        void theTraceResolvesCaseOne() throws IOException {
            Response response = traceAfterARun("C-0001/trace?period=202805");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"answered\":true")
                .contains("\"reason\":\"PUBLISHED\"")
                .contains("\"runId\":\"RUN-202805-01\"");

            // 06 § 1: every response carries the versions that produced it. The rule set and the
            // routing table are stamped; policyVersionId is null because this book registers no
            // POLICY_POSITION version, and filling it from a neighbour would attribute the figure
            // to a version that did not produce it.
            assertThat(response.body())
                .contains("\"ruleSetVersionId\":\"POL-FEE-2028.1\"")
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"")
                .contains("\"tierPolicyVersionId\":\"POL-TIER-2028.1\"")
                .contains("\"policyVersionId\":null")
                .contains("\"routingStampAgreesWithRunRecord\":true");

            // The stored rate, at the twelve places that say it is stated to twelve places.
            // 0.010421491800 x 12 = 0.1250579016, which is 0.125057901600 at RATE_SCALE.
            assertThat(response.body())
                .contains("\"ratePeriodic\":\"0.010421491800\"")
                .contains("\"rateNominalAnnual\":\"0.125057901600\"")
                // 06 § 2.3's example gives the effective annual as 0.13248094...; asserted to the
                // eight places the specification states, so a rate emitted at 27 places of working
                // precision — which is what effectiveAnnual() returns unrounded — fails here.
                .contains("\"rateEffectiveAnnual\":\"0.13248094")
                .doesNotContain("\"rateEffectiveAnnual\":\"0.132480940854651034845383422\"");

            // 05 § 3.2: the steady state does not solve, so there is no method and no iteration
            // count to report, and the trace says null rather than inventing one.
            assertThat(response.body())
                .contains("\"solves\":0")
                .contains("\"status\":\"CARRIED_FORWARD\"")
                .contains("\"trigger\":\"PERIOD_ROLL_FORWARD\"")
                .contains("\"solverMethod\":null")
                .contains("\"iterations\":null")
                .contains("\"rateMoved\":false");

            // The roll-forward block, every figure derived by hand in the class javadoc:
            //   528,407.32 + 5,506.79 - 47,073.47 = 486,840.64
            //   5,506.79 - 5,298.16 = 208.63 amortised out of 1,408.29 brought forward
            //   1,408.29 - 208.63 = 1,199.66 carried forward
            assertThat(response.body())
                .contains("\"openingGca\":\"528407.32\"")
                .contains("\"eirInterest\":\"5506.79\"")
                .contains("\"contractualInterest\":\"5298.16\"")
                .contains("\"feeAmortised\":\"208.63\"")
                .contains("\"cashReceived\":\"47073.47\"")
                .contains("\"closingGca\":\"486840.64\"")
                .contains("\"unamortisedFeeBroughtForward\":\"1408.29\"")
                .contains("\"unamortisedFee\":\"1199.66\"")
                .contains("\"presentedRowSums\":true");

            // The two derived residuals, both nil on a clean period.
            assertThat(response.body())
                .contains("\"reproducesAtStoredRate\":true")
                .contains("\"feeLegsAgree\":true");

            // The flow vector and the cash book, as two separate inputs.
            assertThat(response.body())
                .contains("\"flowDate\":\"2028-05-31\"")
                .contains("\"kind\":\"COMBINED_EMI\"")
                .contains("\"appliedToPrincipal\":\"41775.31\"")
                .contains("\"appliedToInterest\":\"5298.16\"");

            // The stage and the invariants, with 06 § 2.3's own status spelling.
            assertThat(response.body())
                .contains("\"stage\":\"STAGE_1\"")
                .contains("\"stageNumber\":1")
                .contains("\"id\":\"SL-2\",\"status\":\"PASS\"")
                .contains("\"id\":\"ST-2\",\"status\":\"PASS\"")
                .contains("\"invariantsBreached\":0");
        }

        @Test
        @DisplayName("every figure is a JSON string, so no reader can put one through a double")
        void everyFigureIsAString() throws IOException {
            Response response = traceAfterARun("C-0001/trace?period=202805");

            // The unquoted forms. ADR-0002 at the wire: 528407.32 through a browser's JSON.parse
            // is a double, and Json's own javadoc records 533914.10999999997 as the shape of the
            // failure. Only counts are JSON numbers here.
            assertThat(response.body())
                .doesNotContain("\"openingGca\":528407.32")
                .doesNotContain("\"closingGca\":486840.64")
                .doesNotContain("\"ratePeriodic\":0.010421491800")
                .doesNotContain("\"feeAmortised\":208.63");
        }

        @Test
        @DisplayName("06 § 2.3's YYYY-MM spelling resolves to the same period as YYYYMM")
        void bothPeriodSpellingsAnswerTheSamePeriod() throws IOException {
            Response dashed = traceAfterARun("C-0001/trace?period=2028-05");

            assertThat(dashed.status()).isEqualTo(200);
            assertThat(dashed.body())
                .as("the specification writes 2027-09 and the engine keys on 202805; both are"
                    + " accepted and both name the same period on the response")
                .contains("\"period\":\"202805\"")
                .contains("\"answered\":true")
                .contains("\"closingGca\":\"486840.64\"");
        }

        @Test
        @DisplayName("C-0002's trace shows recognition suppressed, not income of 3,304.08")
        void theStageThreeTraceDoesNotReportSuppressedIncomeAsRecognised() throws IOException {
            Response response = traceAfterARun("C-0002/trace?period=202805");

            assertThat(response.status()).isEqualTo(200);
            // The borrower paid nothing, so 528,407.32 + 5,506.79 - 0.00 = 533,914.11. Gross basis,
            // like every other stage: ACPIR presents provisions separately rather than netting.
            assertThat(response.body())
                .contains("\"stage\":\"STAGE_3\"")
                .contains("\"stageNumber\":3")
                .contains("\"closingGca\":\"533914.11\"")
                .contains("\"eirInterest\":\"5506.79\"")
                .contains("\"cashReceived\":\"0.00\"");

            // The distinction the response must not blur. ACPIR recognises nil here; the net-basis
            // figure is the IFRS 9 parallel-basis disclosure and travels under its own name.
            assertThat(response.body())
                .contains("\"incomeSuppressed\":true")
                .contains("\"recognisedIncome\":\"0.00\"")
                .contains("\"ifrs9ParallelBasisIncome\":")
                .doesNotContain("\"recognisedIncome\":\"3304.08\"");

            // The 5,298.16 the CBS billed goes to suspense rather than to income, and the ECL
            // unwind is 211,362.93 x 0.010421491800 = 2,202.7170416, which is 2,202.72 at paise.
            assertThat(response.body())
                .contains("\"toSuspense\":\"5298.16\"")
                .contains("\"chargedToSuspense\":\"5298.16\"")
                .contains("\"closingBalance\":\"5298.16\"")
                .contains("\"eclUnwind\":\"2202.72\"");

            // S3-1 has exactly one publication site and the trace shows all four of its legs.
            assertThat(response.body())
                .contains("\"id\":\"S3-1\",\"status\":\"PASS\"")
                .contains("cash applied to interest against suspense recovered")
                .contains("\"reconciles\":true");
        }
    }

    @Nested
    @DisplayName("the fee-leg control, over HTTP, on a book where the cash book disagrees")
    class TheFeeLegControlOverTheWire {

        private EirServer own;
        private String ownBase;

        /**
         * Reference case 1's month 13, with one input changed: the cash book applied 41,701.84 to
         * principal instead of 41,775.31, so it recorded 47,000.00 received against an instalment
         * the schedule says was 47,073.47.
         *
         * <p><b>Why this book has to be built here rather than reusing {@code Seed}.</b> On the seed
         * book the flow vector's cash and the cash book's total are the same 47,073.47, so passing
         * the wrong one of the two into {@code TraceArithmetic.unamortisedFeeFromCashBook} produces
         * an identical response — the control is present, nil, and untested as wiring. That is the
         * shape of defect this repository has recorded seventeen times. Here the two figures differ
         * by 73.47, so the module's choice of which to read is observable on the wire.
         */
        private static Book aBookWhoseCashBookIsShortBy7347() {
            Book book = new Book(Seed.policies());
            book.put(Book.Holding.onFile("C-0001", "HL", "IN-MUM",
                "reference case 1 at month 13, with a receipt short by 73.47",
                new ContractStateSource.OpeningState(
                    Seed.caseOneTerms(), Seed.EIR, Seed.OPENING_GCA, Seed.OPENING_CONTRACTUAL,
                    Stage.STAGE_1, Seed.NIL, "ECL-MODEL-2028.05", Seed.BILLED_INTEREST),
                ContractPeriod.of("C-0001", 13,
                    FlowVector.of(Seed.PERIOD_START, Money.INR,
                        List.of(CashFlow.of(Seed.PERIOD_END, 1, Seed.EMI, FlowKind.COMBINED_EMI))),
                    TimeConvention.PeriodicIndex.monthly(),
                    // 41,775.31 - 73.47 = 41,701.84, so the two legs total 47,000.00.
                    Money.inr("41701.84"), Seed.BILLED_INTEREST)));
            return book;
        }

        @BeforeEach
        void startOverTheAlteredBook() throws IOException {
            own = new EirServer(0, new EirService(aBookWhoseCashBookIsShortBy7347()));
            own.start();
            ownBase = "http://localhost:" + own.port();
        }

        @AfterEach
        void stopOwn() {
            own.stop();
        }

        @Test
        @DisplayName("a receipt short by 73.47 shows on the trace as a 73.47 fee-leg residual")
        void theResidualReachesTheWire() throws IOException {
            HttpURLConnection seed = (HttpURLConnection)
                URI.create(ownBase + "/api/run").toURL().openConnection();
            seed.setRequestMethod("POST");
            seed.setDoOutput(true);
            try (OutputStream out = seed.getOutputStream()) {
                out.write(new byte[0]);
            }
            read(seed);

            HttpURLConnection connection = (HttpURLConnection) URI
                .create(ownBase + "/api/contracts/C-0001/trace?period=202805").toURL()
                .openConnection();
            connection.setRequestMethod("GET");
            Response response = read(connection);

            assertThat(response.status()).isEqualTo(200);
            // The roll-forward is unchanged, because the carrying amount rolls on the schedule's
            // cash: 528,407.32 + 5,506.79 - 47,073.47 = 486,840.64. Nothing in the published
            // figures moved, which is exactly why the two-legged derivation is the only thing that
            // can see the misapplication.
            assertThat(response.body())
                .contains("\"closingGca\":\"486840.64\"")
                .contains("\"cashReceived\":\"47073.47\"")
                .contains("\"cashAppliedByCashBook\":\"47000.00\"");
            // 529,815.61 + 5,298.16 - 47,000.00 - 486,840.642552439976 = 1,273.127447560024
            // against 1,199.657447560024 on the schedule's leg: out by exactly 73.47.
            assertThat(response.body())
                .contains("\"unamortisedFee\":\"1199.66\"")
                .contains("\"unamortisedFeeFromCashBook\":\"1273.13\"")
                .contains("\"feeLegResidual\":\"73.47\"")
                .contains("\"feeLegsAgree\":false");
        }
    }

    @Nested
    @DisplayName("the three ways there is no figure, each named rather than 404'd")
    class TheHonestRefusals {

        @Test
        @DisplayName("a quarantined contract says so, and shows the inputs that exist")
        void aQuarantinedContractIsNamed() throws IOException {
            Response response = traceAfterARun(Seed.CONTRACT_WITHOUT_STATE + "/trace?period=202805");

            assertThat(response.status())
                .as("a refusal is an answer in this engine, and it comes back on a 200")
                .isEqualTo(200);
            assertThat(response.body())
                .contains("\"answered\":false")
                .contains("\"reason\":\"CONTRACT_QUARANTINED\"")
                .contains("\"exceptionCategory\":\"MISSING_MANDATORY_FIELD\"")
                .contains("\"blocksClose\":true")
                .contains("no state as at");
            // The inputs that do exist are still shown — the population named the contract and the
            // period movements are on file, which is the whole content of FR-905's data condition.
            assertThat(response.body())
                .contains("\"openingStateOnFile\":false")
                .contains("\"flowVector\":[")
                .contains("\"flowVectorAnchor\":\"2028-04-30\"");
            // And no figures, because none were published. A rollForward block here would be the
            // trace inventing the arithmetic the run refused to perform.
            assertThat(response.body())
                .doesNotContain("\"rollForward\"")
                .doesNotContain("\"eirComputation\"");
        }

        @Test
        @DisplayName("a period this process never ran says which period it is positioned at")
        void aPeriodWithNoRunIsNamed() throws IOException {
            post("/api/run", "");
            Response response = get("/api/contracts/C-0001/trace?period=202804");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"answered\":false")
                .contains("\"reason\":\"NO_RUN_FOR_PERIOD\"")
                .contains("no run for period 202804")
                .contains("This book is positioned at period 202805")
                .contains("\"contractOnBook\":true");
        }

        @Test
        @DisplayName("a trace before any run refuses rather than reporting a clean nothing")
        void aTraceBeforeAnyRunRefuses() throws IOException {
            Response response = get("/api/contracts/C-0001/trace?period=202805");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"reason\":\"NO_RUN_FOR_PERIOD\"")
                .contains("POST /api/run");
        }

        @Test
        @DisplayName("a contract the run never saw is distinguished from one it quarantined")
        void aContractOutsideThePopulationIsNamed() throws IOException {
            Response response = traceAfterARun("C-9999/trace?period=202805");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"reason\":\"CONTRACT_NOT_IN_POPULATION\"")
                .contains("\"contractOnBook\":false")
                .contains("accounted for 3 contract(s)");
        }
    }

    @Nested
    @DisplayName("only a malformed request is a 4xx")
    class TheStatusCodes {

        @Test
        @DisplayName("a missing period is a 400 rather than a trace of whatever ran last")
        void anAbsentPeriodIsRefused() throws IOException {
            Response response = get("/api/contracts/C-0001/trace");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("period").contains("absent");
        }

        @Test
        @DisplayName("a period that is not YYYYMM is a 400 that shows what arrived")
        void aMalformedPeriodIsRefused() throws IOException {
            Response response = get("/api/contracts/C-0001/trace?period=May-2028");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .contains("must be YYYYMM or YYYY-MM")
                .contains("May-2028");
        }

        @Test
        @DisplayName("a thirteenth month is a 400, not a period id nothing will ever match")
        void anImpossibleMonthIsRefused() throws IOException {
            Response response = get("/api/contracts/C-0001/trace?period=202813");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).contains("month 13");
        }

        @Test
        @DisplayName("a path under the prefix that is not a trace names the shape this route serves")
        void aNonTracePathIsRefused() throws IOException {
            Response response = get("/api/contracts/C-0001");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body())
                .as("a 404 here would send an integrator looking for a missing deployment")
                .contains("is not a trace request")
                .contains("/api/contracts/{contractId}/trace");
        }

        @Test
        @DisplayName("POST to the trace is a 405, not a silent 404")
        void theWrongVerbIsRefused() throws IOException {
            Response response = post("/api/contracts/C-0001/trace?period=202805", "");

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.body()).contains("GET only");
        }

        @Test
        @DisplayName("the module names the specification section it implements")
        void theModuleNamesItsSection() {
            assertThat(new TraceModule(new EirService(Seed.book())).specSection())
                .isEqualTo("06 § 2.3");
        }
    }
}
