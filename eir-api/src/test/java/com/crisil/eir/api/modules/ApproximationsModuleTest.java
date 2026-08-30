package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.modules.approximations.ApproximationCategory;
import com.crisil.eir.api.modules.approximations.ApproximationRegister;
import com.crisil.eir.api.modules.approximations.ApproximationRow;
import com.crisil.eir.api.modules.approximations.ApproximationSources;
import com.crisil.eir.api.modules.approximations.CategoryReturn;
import com.crisil.eir.api.modules.approximations.ContractualLifeFallback;
import com.crisil.eir.api.modules.approximations.DisclosureSection;
import com.crisil.eir.api.modules.approximations.DisclosureSources;
import com.crisil.eir.api.modules.approximations.EngineSources;
import com.crisil.eir.api.modules.approximations.PoolBackTest;
import com.crisil.eir.api.modules.approximations.RevolvingElection;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.RevolvingApproximation;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.pool.PoolDefinition;
import com.crisil.eir.policy.pool.SuspensionPools;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestRecord;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-809's approximations register and FR-806's Ind AS 107 extract, over a real socket and over
 * hand-built sources.
 *
 * <h2>What these tests are actually about</h2>
 *
 * <p>Almost none of them assert a figure. The defect this endpoint can have is not a wrong number
 * — it is a category rendered as {@code "rows": []} when the truth is "nobody knows", which reads
 * as coverage. 06 § 7: "an undocumented approximation drifting quietly across a portfolio is the
 * failure this endpoint exists to prevent." So the assertions are mostly about the difference
 * between a gap and a nil return, and several of them assert that a string is <em>absent</em> —
 * {@code doesNotContain("NONE_IN_FORCE")} on a register with no sources is the sharpest test in
 * this file, because the wrong implementation passes every other one.
 *
 * <h2>Where each shape is exercised</h2>
 *
 * <p>The socket tests drive {@code EirServer} on port 0, which is the only way to catch a route
 * that never registered, a query parameter never read, and an engine refusal mapped onto the wrong
 * status. They run over {@link EngineSources}, which is the wiring {@code ApiModules} uses and
 * which reports four gaps — the true state of this engine.
 *
 * <p>The populated-register tests drive {@code ApproximationsModule.approximations(int)} and
 * {@code indAs107(int)} in process. Those methods evaluate exactly the expression the two route
 * handlers evaluate — {@code register.render(register.assemble(periodId))} — so the body is the
 * same body; what they cannot cover is the socket, which the tests above do. Driving them in
 * process is what lets a Tier 3 population with a stale equivalence test exist at all, since
 * {@code ApiModules} constructs every module with an {@code EirService} and no way to inject a
 * source.
 *
 * <p>Every expected figure below is derived by hand from the constructor arguments, and the
 * derivation is stated at the assertion. No expected value was read off a run of this code.
 */
class ApproximationsModuleTest {

    /** The demonstration book's period: May 2028, so the reporting date is 2028-05-31. */
    private static final int PERIOD = Seed.PERIOD_ID;

    /**
     * A policy version of the one kind {@code PoolDefinition} accepts, effective before the
     * period end so that {@code SuspensionPools.inForceOn(2028-05-31)} resolves it.
     */
    private static PolicyVersion poolVersion(String id) {
        return new PolicyVersion(id, PolicyKind.POOL_DEFINITION,
            "card suspension pool, FY2028-29", LocalDate.of(2028, 4, 1),
            "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
            PolicyVersionStatus.EFFECTIVE);
    }

    /**
     * Reference case 1's terms, which leave both lives unstated.
     *
     * <p>{@code ContractTerms.of(...)} is the nine-argument factory and its javadoc says it leaves
     * "the two lives left unstated" — it passes 0 for {@code eirExpectedLifeMonths}. That zero is
     * precisely what makes FR-310's contractual fallback apply, so this is a contract on the
     * fallback and {@link ContractualLifeFallback} will accept it.
     */
    private static ContractTerms unstatedLifeTerms() {
        return ContractTerms.of(
            Money.inr("1000000.00"),
            Rate.periodic(new BigDecimal("0.010000000000"), 12),
            24, 12,
            LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 31),
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, RateType.FIXED);
    }

    /**
     * An equivalence test whose delta is exactly derivable in basis points.
     *
     * <p>{@code Rate.annualEffective} sets {@code periodsPerYear = 1}, so
     * {@code effectiveAnnual() = (1 + r)^1 − 1 = r} exactly and
     * {@code effectiveAnnualBps() = r × 10000}. With {@code solved = 0.0850} and
     * {@code approximated = 0.0830} the two are 850 and 830 bps and {@code deltaBps()} is their
     * difference, <b>20</b>. Choosing annual-effective rates rather than monthly ones is what
     * makes that figure hand-derivable instead of the output of a 12th-power compounding.
     *
     * <p><b>At twelve decimal places, so 20.000000000000.</b> {@code Rate}'s constructor rounds
     * the periodic rate to {@code Precision.RATE_SCALE}, which is 12 — the scale 06 § 1's
     * conventions table publishes for a rate on the wire. So {@code 0.0850} is stored as
     * {@code 0.085000000000} at scale 12, {@code (1 + r)^1 − 1} keeps that scale, and the exact
     * multiplication by 10000 keeps it again. The trailing zeros are information: they say the
     * delta is stated to twelve places, which is why {@code Json} emits every figure as a string.
     */
    private static EquivalenceTestRecord test(
        String populationId, LocalDate performedOn, String solved, String approximated,
        String thresholdBps) {
        return new EquivalenceTestRecord(populationId, performedOn, 250,
            Rate.annualEffective(new BigDecimal(solved)),
            Rate.annualEffective(new BigDecimal(approximated)),
            new BigDecimal(thresholdBps), "board.risk.committee");
    }

    /** A short-tenor coupon-bearing exposure at par — the WCDL shape FR-412 does not refuse. */
    private static EquivalenceTestSubject wcdl(String populationId) {
        return EquivalenceTestSubject.couponBearingAtPar(
            populationId, MaterialityTier.TIER_3, 12,
            Money.inr("1000000.00"), Money.inr("90000.00"));
    }

    /**
     * Reference case 9's instrument: a 15-year zero-coupon bought at 315,241.70 against a face of
     * 1,000,000.00, so 684,758.30 of the return is accretion and none of it is coupon.
     */
    private static EquivalenceTestSubject caseNineZeroCoupon() {
        return EquivalenceTestSubject.discountInstrument(
            "ZCB-15Y", MaterialityTier.TIER_3, 180,
            Money.inr("315241.70"), Money.inr("1000000.00"));
    }

    /** Sources whose four answers are supplied one by one; anything unset is a gap. */
    private static final class Wired implements ApproximationSources {
        private Answer<Tier3Submission> tier3 = Answer.unavailable("not wired in this test");
        private Answer<List<ContractualLifeFallback>> fallbacks =
            Answer.unavailable("not wired in this test");
        private Answer<PoolSubmission> pools = Answer.unavailable("not wired in this test");
        private Answer<List<RevolvingElection>> revolving =
            Answer.unavailable("not wired in this test");

        Wired tier3(List<EquivalenceTestSubject> populations, EquivalenceTestGate gate) {
            this.tier3 = Answer.of(new Tier3Submission(populations, gate));
            return this;
        }

        Wired fallbacks(List<ContractualLifeFallback> rows) {
            this.fallbacks = Answer.of(rows);
            return this;
        }

        Wired pools(SuspensionPools definitions, List<PoolBackTest> backTests) {
            this.pools = Answer.of(new PoolSubmission(definitions, backTests));
            return this;
        }

        Wired revolving(List<RevolvingElection> rows) {
            this.revolving = Answer.of(rows);
            return this;
        }

        /** Every category sourced and answering nothing — the NONE_IN_FORCE state. */
        Wired allEmpty() {
            this.tier3 = Answer.of(new Tier3Submission(List.of(), EquivalenceTestGate.empty()));
            this.fallbacks = Answer.of(List.of());
            this.pools = Answer.of(new PoolSubmission(SuspensionPools.of(List.of()), List.of()));
            this.revolving = Answer.of(List.of());
            return this;
        }

        @Override
        public Answer<Tier3Submission> tier3(int periodId) {
            return tier3;
        }

        @Override
        public Answer<List<ContractualLifeFallback>> contractualLifeFallbacks(int periodId) {
            return fallbacks;
        }

        @Override
        public Answer<PoolSubmission> pools(int periodId) {
            return pools;
        }

        @Override
        public Answer<List<RevolvingElection>> revolvingElections(int periodId) {
            return revolving;
        }
    }

    /** A disclosure source that answers every figure section with one line. */
    private static final class WiredDisclosures implements DisclosureSources {
        @Override
        public ApproximationSources.Answer<List<DisclosureLine>> section(
            DisclosureSection section, int periodId) {
            return ApproximationSources.Answer.of(List.of(new DisclosureLine(
                section.title(), new BigDecimal("5506.79"),
                "supplied by this test's sub-ledger stub")));
        }
    }

    private static ApproximationsModule moduleOver(
        ApproximationSources approximations, DisclosureSources disclosures) {
        return new ApproximationsModule(
            new EirService(Seed.book()), approximations, disclosures);
    }

    // ============================================================== over a real socket

    @Nested
    @DisplayName("the two endpoints, over HTTP, wired the way ApiModules wires them")
    class OverTheSocket {

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
            int status = connection.getResponseCode();
            try (var stream =
                    status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return new Response(status,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        @Test
        @DisplayName("a category the engine cannot populate is a NAMED GAP, not an empty list")
        void anUnpopulatableCategoryIsANamedGapAndNotAnEmptyList() throws IOException {
            Response response = get("/api/reports/approximations?period=202805");

            assertThat(response.status())
                .as("an incomplete register is an answer somebody has to act on, not a client"
                    + " error — 200 with the whole list, like every other refusal in this engine")
                .isEqualTo(200);

            // This is the assertion the endpoint exists for. All four of FR-809's categories are
            // unpopulatable on this engine, and every one of them must say NOT_AVAILABLE with a
            // reason. The wrong implementation — four empty row arrays — passes every assertion
            // about counts and fails this one.
            assertThat(response.body())
                .as("a gap must be labelled as a gap, four times over")
                .contains("\"status\":\"NOT_AVAILABLE\"")
                .contains("\"complete\":false")
                .contains("\"categoriesNotAvailable\":4")
                .contains("\"categoriesInFr809\":4")
                .contains("\"categoriesReported\":4");

            // The other half of the same claim, and the sharper one: nothing in this response may
            // assert that a shortcut is NOT in force. NONE_IN_FORCE is a positive statement and
            // the engine is in no position to make it about any of the four.
            assertThat(response.body())
                .as("an engine that holds no source must never claim that no shortcut is in force")
                .doesNotContain("\"status\":\"NONE_IN_FORCE\"");

            // A gap that cannot say what is missing is an apology. Each of the four names the
            // class a reader can open.
            assertThat(response.body())
                .contains("EquivalenceTestGate implements the whole of FR-411 and FR-412")
                .contains("has no caller outside its own package")
                .contains("ContractTerms.expectedLifePeriods() resolves an unstated")
                .contains("PoolDefinition and SuspensionPools exist in eir-policy")
                .contains("ProjectorRegistry wires one RevolvingProjector");

            // Every category still names its own invariant and specification section, so an
            // unpopulated register still tells a reader what the control would have been.
            assertThat(response.body())
                .contains("\"invariantId\":\"TG-1\"")
                .contains("\"invariantId\":\"PL-2\"")
                .contains("FR-809 · FR-411 · FR-412 · 03 § 10.2 · 03 § 10.3 · 06 § 7");
        }

        @Test
        @DisplayName("the reading note says a zero on an incomplete register proves nothing")
        void theReadingNoteQualifiesTheCounts() throws IOException {
            Response response = get("/api/reports/approximations?period=202805");

            // undocumentedShortcuts is 0 here and that figure is true and misleading on its own:
            // it counts only the categories that had a source, and none did. The response has to
            // say so, because the reader of a JSON body is not reading the javadoc.
            assertThat(response.body()).contains("\"undocumentedShortcuts\":0");
            assertThat(response.body())
                .as("a true figure that invites a false conclusion has to be qualified in the"
                    + " response itself")
                .contains("This register is INCOMPLETE — 4 of 4 categories had no source")
                .contains("a zero here is not a statement that no undocumented approximation is"
                    + " in force");
        }

        @Test
        @DisplayName("the 0.50 deep-discount share is published as an unapproved policy default")
        void theDeepDiscountShareIsPublishedWithItsProvenance() throws IOException {
            Response response = get("/api/reports/approximations?period=202805");

            // EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE decides which instruments
            // FR-412's deep-discount limb catches, and neither 03 § 10.3 nor reference case 9
            // publishes a number. A register that quoted it as a specification figure would be
            // laundering a policy default through a control report.
            assertThat(response.body())
                .contains("EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE")
                .contains("\"value\":\"0.50\"")
                .contains("\"provenance\":\"POLICY_DEFAULT_AWAITING_BOARD_APPROVAL\"");
            // Two parameters nobody has approved: this share, and 03 § 10.1's pool back-test
            // materiality threshold, for which this engine deliberately takes no default.
            assertThat(response.body())
                .contains("\"parametersRequiringBoardAttention\":2")
                .contains("\"provenance\":\"NOT_SET_NO_DEFAULT_TAKEN\"");
            // And the specification figures are labelled as such, so a reader can tell them apart.
            assertThat(response.body())
                .contains("EquivalenceTestRecord.ANNUAL_WINDOW")
                .contains("\"provenance\":\"SPECIFICATION\"");
        }

        @Test
        @DisplayName("the disclosure extract refuses sign-off and enumerates every reason")
        void theDisclosureExtractRefusesSignOff() throws IOException {
            Response response = get("/api/reports/disclosure/ind-as-107?period=202805");

            assertThat(response.status()).isEqualTo(200);
            // Five sections in the extract, four of which are figure-bearing and unsourced; the
            // fifth is the measurement-basis note, which is fed by the FR-809 register and is
            // therefore always present — its content here is that the register is incomplete.
            assertThat(response.body())
                .contains("\"sectionsReported\":5")
                .contains("\"sectionsNotAvailable\":4")
                .contains("\"signOffPermitted\":false");
            // Refusals name the standard paragraph, so closing them is a list of tasks.
            assertThat(response.body())
                .contains("Ind AS 107.20(b)(i)")
                .contains("Ind AS 107.35H")
                .contains("Ind AS 107.35M")
                .contains("Ind AS 107.24A–24C")
                .contains("the FR-809 approximations register is incomplete — 4 of 4 categories"
                    + " had no source");
            // The measurement-basis section is present and carries the register's gaps rather
            // than being itself reported as a gap — the disclosure of an incomplete register is
            // that it is incomplete, which is the Ind AS 1.125 statement.
            assertThat(response.body())
                .contains("\"section\":\"MEASUREMENT_BASIS_AND_APPROXIMATIONS\"")
                .contains("\"fedByTheApproximationsRegister\":true")
                .contains("Completeness of the approximations register (FR-809)")
                .contains("\"narrative\":true");
            // The disclosure vocabulary, not the register's: a loss allowance section must not be
            // described in terms of a shortcut being in force.
            assertThat(response.body())
                .contains("the section is undisclosable rather than empty")
                .doesNotContain("asserts nothing about whether the shortcut is in force");
        }

        @Test
        @DisplayName("an absent period is a 400 naming the field, never a defaulted period")
        void anAbsentPeriodIsRefused() throws IOException {
            Response response = get("/api/reports/approximations");

            assertThat(response.status())
                .as("every window in the register is measured against the period end, so a"
                    + " defaulted period would answer a question nobody asked")
                .isEqualTo(400);
            assertThat(response.body()).contains("bad request").contains("period");
        }

        @Test
        @DisplayName("a period that is not a yyyyMM is a 400, not a 500 from deeper in")
        void aMalformedPeriodIsRefused() throws IOException {
            // Month 13 parses as an integer and is not a period. Caught at the edge so the
            // caller gets the field name instead of an IllegalArgumentException rendered as a
            // defect.
            Response thirteenthMonth = get("/api/reports/approximations?period=202813");
            assertThat(thirteenthMonth.status()).isEqualTo(400);
            assertThat(thirteenthMonth.body()).contains("is not a yyyyMM reporting period");

            Response notANumber = get("/api/reports/disclosure/ind-as-107?period=May");
            assertThat(notANumber.status()).isEqualTo(400);
            assertThat(notANumber.body()).contains("must be a whole number");
        }

        @Test
        @DisplayName("both routes are registered, and neither is the page handler's 404")
        void bothRoutesAreRegistered() throws IOException {
            // A module that registered nothing would 404 through EirServer's page handler with a
            // body naming the path. Asserting the negative catches a route that silently never
            // registered, which no assertion on a successful body can.
            assertThat(get("/api/reports/approximations?period=202805").body())
                .doesNotContain("no route");
            assertThat(get("/api/reports/disclosure/ind-as-107?period=202805").body())
                .doesNotContain("no route");
        }
    }

    // ====================================================== the register over a populated book

    @Nested
    @DisplayName("Tier 3, gated through EquivalenceTestGate rather than re-derived")
    class TierThree {

        @Test
        @DisplayName("a current in-threshold test reports the shortcut in force with its date")
        void aCurrentTestEvidencesTheShortcut() {
            // Performed 2028-01-31, so in date to 2029-01-31 and current at the 2028-05-31
            // reporting date. Delta 850.0000 − 830.0000 = 20.0000 bps against a threshold of 25,
            // so within.
            String body = moduleOver(
                new Wired().tier3(
                    List.of(wcdl("WCDL-RETAIL")),
                    EquivalenceTestGate.of(List.of(
                        test("WCDL-RETAIL", LocalDate.of(2028, 1, 31), "0.0850", "0.0830", "25")))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            assertThat(body)
                .contains("\"category\":\"TIER_3_APPROXIMATION\"")
                .contains("\"status\":\"REPORTED\"")
                .contains("\"subject\":\"WCDL-RETAIL\"")
                .contains("\"inForce\":true")
                .contains("\"evidenced\":true")
                .contains("\"undocumented\":false");
            assertThat(body)
                .as("06 § 7 asks for Tier 3 populations WITH equivalence-test dates; the date and"
                    + " the annual window are the whole of TG-1")
                .contains("\"evidenceDate\":\"2028-01-31\"")
                .contains("\"evidenceExpires\":\"2029-01-31\"");
            // Derived above: annual-effective rates make the delta exactly r × 10000, stated at
            // Precision.RATE_SCALE = 12 places. 850 − 830 = 20.
            assertThat(body)
                .contains("\"varianceBps\":\"20.000000000000\"")
                .contains("\"thresholdBps\":\"25\"");
            assertThat(body).contains("TG-1").contains("\"satisfied\":true");
        }

        @Test
        @DisplayName("a stale test demotes to Tier 2, breaches TG-1 and raises the exception")
        void aStaleTestDemotesAndBreaches() {
            // Performed 2026-12-31 expires 2027-12-31. From 2027-12-31 to 2028-05-31 is
            // 31 (Jan) + 29 (Feb, 2028 is a leap year) + 31 (Mar) + 30 (Apr) + 31 (May) = 152
            // days, which is the deviation FR-411's demotion is reported with.
            String body = moduleOver(
                new Wired().tier3(
                    List.of(wcdl("WCDL-RETAIL")),
                    EquivalenceTestGate.of(List.of(
                        test("WCDL-RETAIL", LocalDate.of(2026, 12, 31), "0.0850", "0.0830", "25")))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            assertThat(body)
                .as("the shortcut is not applied, because 03 § 10.2 demotes an out-of-date"
                    + " population to Tier 2 — which is more expensive and more correct")
                .contains("\"inForce\":false")
                .contains("\"evidenced\":false")
                .contains("measured at TIER_2");
            assertThat(body)
                .contains("152 days before the reporting date 2028-05-31")
                .contains("\"exception\":\"STALE_EQUIVALENCE_TEST\"")
                .contains("\"satisfied\":false");
        }

        @Test
        @DisplayName("an in-date test that FAILED its threshold is not evidence of permission")
        void anInDateFailedTestIsNotPermission() {
            // Performed 2028-01-31 so in date, but delta 900 − 830 = 70 bps against a threshold
            // of 25. An in-date failed test is evidence that the shortcut does NOT hold;
            // counting it as evidence would turn a failure into a permission.
            String body = moduleOver(
                new Wired().tier3(
                    List.of(wcdl("WCDL-RETAIL")),
                    EquivalenceTestGate.of(List.of(
                        test("WCDL-RETAIL", LocalDate.of(2028, 1, 31), "0.0900", "0.0830", "25")))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            assertThat(body)
                .contains("\"evidenced\":false")
                .contains("\"varianceBps\":\"70.000000000000\"")
                .contains("delta 70.000000000000 bps against a threshold of 25 bps")
                .contains("measured at TIER_2");
        }

        @Test
        @DisplayName("FR-412 refuses a zero-coupon outright, and a perfect test does not save it")
        void frFourTwelveRefusesAZeroCouponEvenWithACurrentTest() {
            // The ordering is the substance of FR-412. This population has a current, in-date,
            // within-threshold test on file — and is still refused, because on a zero-coupon the
            // lifetime solved-versus-approximated delta is zero, so a test performed exactly as
            // 03 § 10.2 specifies PASSES on an instrument whose year-one error is 81.0%.
            String body = moduleOver(
                new Wired().tier3(
                    List.of(caseNineZeroCoupon()),
                    EquivalenceTestGate.of(List.of(
                        test("ZCB-15Y", LocalDate.of(2028, 1, 31), "0.0850", "0.0850", "25")))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            assertThat(body)
                .as("evidence must not reverse an absolute prohibition")
                .contains("\"inForce\":false")
                .contains("FR-412 refuses Tier 3 at any tenor")
                .contains("measured at TIER_2");
            // 684,758.30 of accretion against a face of 1,000,000.00 bought at 315,241.70, and
            // the accretion share of total return is therefore exactly 1.
            assertThat(body)
                .contains("zero-coupon: the entire return of INR 684758.30 is accretion")
                .contains("accretion share of total return 1.0000");
            // FR-412 is a correct refusal, not a control failure, so TG-1 must NOT breach on it.
            // 09 § 9 blocks a close on an invariant breach, and blocking a close over a
            // population the engine handled correctly would bury the real TG-1 breaches.
            assertThat(body)
                .as("an FR-412 refusal is the gate working; reporting it as a TG-1 breach would"
                    + " block a close on a population the engine got right")
                .contains("\"exception\":null");
            assertThat(body).contains("81.0% overstatement of year-one income")
                .contains("684758.30 exactly");
        }

        @Test
        @DisplayName("no test on file is a row that says so, never a missing category")
        void noTestOnFileIsARowNotAGap() {
            String body = moduleOver(
                new Wired().tier3(List.of(wcdl("WCDL-RETAIL")), EquivalenceTestGate.empty()),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            // An empty gate is not an absent source. The population was presented, the gate
            // answered NO_TEST_ON_FILE, and that is a finding with a row — reporting it as a
            // category-level gap would lose the population id.
            assertThat(body)
                .contains("\"status\":\"REPORTED\"")
                .contains("no equivalence test on file")
                .contains("\"subject\":\"WCDL-RETAIL\"")
                .contains("\"exception\":\"STALE_EQUIVALENCE_TEST\"");
        }
    }

    @Nested
    @DisplayName("the other three categories, and the count that matters")
    class TheOtherThree {

        @Test
        @DisplayName("an unjustified ACPIR 51 fallback is in force and undocumented")
        void anUnjustifiedFallbackIsUndocumented() {
            String body = moduleOver(
                new Wired().fallbacks(List.of(
                    new ContractualLifeFallback("C-0001", unstatedLifeTerms(), null, null),
                    new ContractualLifeFallback("C-0002", unstatedLifeTerms(),
                        "a 24-month term loan with no prepayment history on the cohort",
                        LocalDate.of(2028, 3, 31)))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            // 24 periods at 12 a year is 24 × 12 ÷ 12 = 24 months, which is what the expected
            // life fell back to.
            assertThat(body).contains("expected life taken as the contractual term (FR-310),"
                + " being 24 months");
            assertThat(body)
                .as("ContractTerms cannot tell an election from an omission, so the blank"
                    + " justification is the finding")
                .contains("NO justified election on file")
                .contains("\"undocumented\":true");
            assertThat(body).contains("\"evidenceDate\":\"2028-03-31\"");
            // One of the two is undocumented, on a register where only this category has a
            // source: two subjects, both in force, one undocumented.
            assertThat(body)
                .contains("\"shortcutsInForce\":2")
                .contains("\"undocumentedShortcuts\":1");
            // No InvariantId covers an unjustified ACPIR 51 election. Reported as null rather
            // than borrowed from TG-1 or PL-2, which have never looked at this.
            assertThat(body).contains("\"invariantResult\":null");
        }

        @Test
        @DisplayName("a pool with no in-date back-test is measured collectively on no evidence")
        void aPoolWithoutABackTestIsUndocumented() {
            PoolDefinition cards = new PoolDefinition(poolVersion("POL-POOL-2028.1"),
                "POOL-CARDS", "CARD", Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER),
                Set.of("E-1", "E-2"));
            // Performed 2027-12-31, so the quarterly window expired 2028-03-31. From 2028-03-31
            // to 2028-05-31 is 30 (Apr) + 31 (May) = 61 days.
            PoolBackTest stale = new PoolBackTest("POOL-CARDS", LocalDate.of(2027, 12, 31), 400,
                new BigDecimal("12"), new BigDecimal("25"), "board.risk.committee");

            String body = moduleOver(
                new Wired().pools(SuspensionPools.of(cards), List.of(stale)),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            assertThat(body)
                .contains("\"category\":\"POOL_LEVEL_MEASUREMENT\"")
                .contains("\"subject\":\"POOL-CARDS\"")
                .contains("\"inForce\":true")
                .contains("\"evidenced\":false")
                .contains("STALE by 61 days as at 2028-05-31")
                .contains("\"exception\":\"POOL_BACKTEST_BREACH\"");
            // Pooling for suspension does not change the measurement tier, and publishing both in
            // one place is how a book pooled for one and measured account by account for the
            // other becomes visible.
            assertThat(body).contains("measured at TIER_2 over 2 members");
            // PL-2 passes: a card book is exactly the portfolio-managed product pool-level
            // treatment exists for.
            assertThat(body).contains("PL-2").contains("\"satisfied\":true");
        }

        @Test
        @DisplayName("a back-test matching no pool in force is published, not dropped")
        void anOrphanBackTestIsPublished() {
            PoolDefinition cards = new PoolDefinition(poolVersion("POL-POOL-2028.1"),
                "POOL-CARDS", "CARD", Set.of(TierAssignmentFeature.CARD_OR_KCC_REVOLVER),
                Set.of("E-1"));
            PoolBackTest orphan = new PoolBackTest("POOL-CARD", LocalDate.of(2028, 4, 30), 400,
                new BigDecimal("12"), new BigDecimal("25"), "board.risk.committee");

            String body = moduleOver(
                new Wired().pools(SuspensionPools.of(cards), List.of(orphan)),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            // POOL-CARD against POOL-CARDS is the join-key failure EquivalenceTestRecord strips
            // whitespace to avoid, and it fails in the dangerous direction: a properly
            // back-tested pool publishes as unevidenced. Dropping the orphan silently would make
            // the two indistinguishable.
            assertThat(body)
                .contains("back-test on file for pool 'POOL-CARD' performed 2028-04-30 matches no"
                    + " pool in force on 2028-05-31")
                .contains("\"evidenced\":false");
        }

        @Test
        @DisplayName("an ACPIR 54 election this engine cannot honour is in force and unevidenced")
        void anUnhonourableRevolvingElectionIsUndocumented() {
            String body = moduleOver(
                new Wired().revolving(List.of(
                    new RevolvingElection("CARD", RevolvingApproximation.EIR_OVER_UTILISATION, 0,
                        null, LocalDate.of(2028, 1, 31),
                        "utilisation on the card book is stable and modellable"),
                    new RevolvingElection("WCDL", RevolvingApproximation.FEE_OVER_RENEWAL, 12,
                        null, LocalDate.of(2028, 1, 31),
                        "the drawn balance is volatile and the fee compensates renewal"))),
                new WiredDisclosures())
                .approximations(PERIOD).toString();

            // The card election is assessed and dated and still unevidenced, because
            // RevolvingProjector refuses EIR_OVER_UTILISATION in its constructor: the facility is
            // projected on FEE_OVER_RENEWAL regardless, so the book is measured on an
            // approximation the product assessment did not choose.
            assertThat(body)
                .contains("NOT APPLICABLE in this engine")
                .contains("which RevolvingProjector refuses in its constructor")
                .contains("\"undocumented\":true");
            assertThat(body)
                .as("the WCDL election is honourable and assessed, so it is evidenced")
                .contains("integral fee deferred over a 12-month renewal period");
            assertThat(body)
                .contains("\"shortcutsInForce\":2")
                .contains("\"undocumentedShortcuts\":1");
        }

        @Test
        @DisplayName("a source that answers nothing is NONE_IN_FORCE, and the register is complete")
        void aSourcedEmptyCategoryIsAPositiveClaim() {
            String body = moduleOver(new Wired().allEmpty(), new WiredDisclosures())
                .approximations(PERIOD).toString();

            // The mirror image of the gap test. Here somebody looked, four times, and there is
            // genuinely nothing in force — which is a claim the register is entitled to make, and
            // the report is complete.
            assertThat(body)
                .contains("\"complete\":true")
                .contains("\"categoriesNotAvailable\":0")
                .contains("\"status\":\"NONE_IN_FORCE\"")
                .contains("the source answered and no shortcut of this kind is in force")
                .doesNotContain("\"status\":\"NOT_AVAILABLE\"");
            assertThat(body)
                .contains("\"gaps\":[]")
                .contains("This register is complete: every one of FR-809's four categories had"
                    + " a source");
        }
    }

    @Nested
    @DisplayName("the disclosure sign-off control, which must be able to both pass and fail")
    class SignOff {

        @Test
        @DisplayName("sign-off is permitted only with every section sourced and no shortcut bare")
        void signOffIsPermittedOnACompleteExtract() {
            String body = moduleOver(new Wired().allEmpty(), new WiredDisclosures())
                .indAs107(PERIOD).toString();

            assertThat(body)
                .contains("\"signOffPermitted\":true")
                .contains("\"signOffRefusals\":[]")
                .contains("\"complete\":true");
            // Figures cross the wire as strings, including inside a disclosure line.
            assertThat(body).contains("\"amount\":\"5506.79\"").contains("\"narrative\":false");
        }

        @Test
        @DisplayName("one undocumented shortcut alone refuses sign-off, citing Ind AS 1.122")
        void oneBareShortcutRefusesSignOff() {
            // Every section sourced and the register complete — but one ACPIR 51 fallback is in
            // force with no justification. A disclosure of judgements cannot be signed while a
            // judgement is undocumented, and this refusal must be separable from a missing
            // section so that closing one does not close the other.
            Wired sources = new Wired().allEmpty().fallbacks(List.of(
                new ContractualLifeFallback("C-0001", unstatedLifeTerms(), null, null)));
            String body = moduleOver(sources, new WiredDisclosures()).indAs107(PERIOD).toString();

            assertThat(body)
                .contains("\"signOffPermitted\":false")
                .contains("\"sectionsNotAvailable\":0")
                .contains("1 approximation(s) are in force with no evidence on file")
                .contains("Ind AS 1.122");
        }
    }

    // ============================================ shapes that must not be representable at all

    @Nested
    @DisplayName("the states that would let an omission read as coverage are unconstructable")
    class Unconstructable {

        @Test
        @DisplayName("a gap that cannot say what is missing is refused at both doors")
        void aReasonlessGapIsRefused() {
            // The factory. It appends the category's wouldBePopulatedBy sentence, so a blank
            // reason would produce a non-blank gap and slip past the constructor's guard — the
            // check has to be here as well, or the only path anybody uses is uncontrolled.
            assertThatThrownBy(() -> CategoryReturn.notAvailable(
                ApproximationCategory.TIER_3_APPROXIMATION, "   "))
                .as("a NOT_AVAILABLE with no reason renders as an empty list, and an empty list"
                    + " reads as \"none in force\"")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which is the failure this endpoint exists to prevent");

            // And the constructor, for a caller assembling one directly.
            assertThatThrownBy(() -> new CategoryReturn(
                ApproximationCategory.TIER_3_APPROXIMATION, CategoryReturn.Status.NOT_AVAILABLE,
                List.of(), null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which is the failure this endpoint exists to prevent");
        }

        @Test
        @DisplayName("NONE_IN_FORCE with a gap, and REPORTED with no rows, are both refused")
        void theStatusesCannotBeBlurred() {
            assertThatThrownBy(() -> new CategoryReturn(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT,
                CategoryReturn.Status.NONE_IN_FORCE, List.of(), "the pool feed did not load",
                null, List.of()))
                .as("a category with a gap has not established that nothing is in force")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lets the weaker claim be read as the stronger one");

            assertThatThrownBy(() -> CategoryReturn.reported(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT, List.of(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indistinguishable from NONE_IN_FORCE");
        }

        @Test
        @DisplayName("an approximation cannot be evidenced by an undated document")
        void undatedEvidenceIsRefused() {
            assertThatThrownBy(() -> new ApproximationRow(
                ApproximationCategory.POOL_LEVEL_MEASUREMENT, "POOL-CARDS", "pool EIR",
                true, true, "back-tested at some point", null, null, null, null, "PL-2", null))
                .as("currency is date arithmetic; an undated document can be neither current nor"
                    + " stale, and the row would satisfy every count in the report")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claims documented evidence with no date on it");
        }

        @Test
        @DisplayName("an answer that is both, or neither, is refused")
        void anAmbiguousAnswerIsRefused() {
            assertThatThrownBy(() -> new ApproximationSources.Answer<String>(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither was given");
            assertThatThrownBy(() -> new ApproximationSources.Answer<>(
                "rows", new ApproximationSources.Unavailable("and also a reason")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both were");
            assertThatThrownBy(() -> new ApproximationSources.Unavailable(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must say what is missing");
        }

        @Test
        @DisplayName("a register carrying fewer than FR-809's four categories is refused")
        void aShortRegisterIsRefused() {
            assertThatThrownBy(() -> new ApproximationRegister.Assembled(
                PERIOD, LocalDate.of(2028, 5, 31),
                List.of(CategoryReturn.notAvailable(
                    ApproximationCategory.TIER_3_APPROXIMATION, "no source in this test")),
                List.of()))
                .as("a register missing a category reads as coverage of it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FR-809 names 4");
        }

        @Test
        @DisplayName("a contract with a stated expected life is not on the ACPIR 51 fallback")
        void aStatedExpectedLifeIsNotAFallback() {
            // 24 months stated against a 24-month term is an explicit election that happens to
            // coincide, not FR-310's fallback. Listing it would inflate the incidence of a
            // shortcut that is not in force — the mirror of the omission this endpoint prevents.
            ContractTerms stated = unstatedLifeTerms().withLives(24, 24, null);

            assertThatThrownBy(() ->
                new ContractualLifeFallback("C-0001", stated, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not take FR-310's contractual fallback");
        }

        @Test
        @DisplayName("a back-test with no sample and one with a negative threshold are refused")
        void aBackTestMustBeAComparison() {
            assertThatThrownBy(() -> new PoolBackTest("POOL-CARDS", LocalDate.of(2028, 4, 30), 0,
                new BigDecimal("12"), new BigDecimal("25"), "board.risk.committee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a small sample but the absence of the comparison");

            assertThatThrownBy(() -> new PoolBackTest("POOL-CARDS", LocalDate.of(2028, 4, 30), 400,
                new BigDecimal("12"), new BigDecimal("-1"), "board.risk.committee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a threshold nothing can satisfy");
        }

        @Test
        @DisplayName("a FEE_OVER_RENEWAL election with no renewal period is refused")
        void aDeferralNeedsAPeriodToDeferOver() {
            assertThatThrownBy(() -> new RevolvingElection("WCDL",
                RevolvingApproximation.FEE_OVER_RENEWAL, 0, null, LocalDate.of(2028, 1, 31),
                "the drawn balance is volatile"))
                .as("the fee defers over that period, so an absent one recognises the whole fee"
                    + " at once — the treatment ACPIR 53 exists to prevent")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a short renewal but the absence of the deferral");
        }

        @Test
        @DisplayName("the engine's disclosure source refuses to answer the register-fed section")
        void theRegisterFedSectionIsNeverSourced() {
            assertThatThrownBy(() -> new EngineSources().section(
                DisclosureSection.MEASUREMENT_BASIS_AND_APPROXIMATIONS, PERIOD))
                .as("routing it to a figure source would render the measurement-basis note as a"
                    + " gap and hide the register's findings inside the gap mechanism")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be sourced here");
        }
    }
}
