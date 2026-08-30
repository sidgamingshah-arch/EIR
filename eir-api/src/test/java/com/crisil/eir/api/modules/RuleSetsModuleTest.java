package com.crisil.eir.api.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Seed;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The fee-rule-set and routing-table surface, over real HTTP on a real socket (06 § 5, ADR-0006).
 *
 * <p><b>What this test exists to prove.</b> ADR-0006's Phase 2 exit gate is one sentence — "the
 * routing table can be changed without a code deploy" — and before this module it was structurally
 * true and unobservable: the only path into {@code RoutingTableRegistry} was Java code, which is the
 * deploy. {@link TheExitGate} is that sentence as a test. One server, started once, never restarted:
 * a driver routes one way, a table routing it differently is posted and approved, and the same
 * question answers the other way. If the assertions on either side of the approval could both pass
 * with the routing frozen, this test would be worthless, so the before-figure is asserted as well as
 * the after-figure.
 *
 * <p><b>Driven over a socket, not by calling the module.</b> For {@code EirServerTest}'s reasons:
 * every defect this layer can introduce lives between the two — a route registered on a path already
 * taken (the JDK's server throws on that and takes the whole server down), a refusal mapped onto a
 * 500 so an operator sees a stack trace where a control report belongs, a query string decoded twice.
 * A test that called {@code RuleSetsModule} directly would miss all three. Port 0, so it runs
 * anywhere and never collides with a developer's own server.
 *
 * <p><b>Assertions are on substrings of the JSON</b>, because this module has no JSON parser and
 * writing one for the test would be the largest untested thing in it. The substrings are chosen so a
 * formatting change cannot accidentally satisfy them: a specific mechanism under a specific version
 * id, a specific refusal code, a specific accounting sentence.
 *
 * <p><b>Expected values are derived, never read back from the code under test.</b> The routing
 * expectations come from the two tables themselves: {@code RoutingTable.currentDefault()} maps
 * {@code ESG_LINKED} to {@code CATCH_UP} (03 § 6.1's baseline — an ESG margin ratchet compensates for
 * neither the time value of money nor credit risk, so it falls outside B5.4.5 and books a B5.4.6
 * catch-up), and the artefact posted here maps it to {@code RESET}, which is the H2 2026 Exposure
 * Draft reading ADR-0006 anticipates. The mechanism before is therefore {@code CATCH_UP} under
 * {@code RT-BASELINE-2026.1} and after is {@code RESET} under {@code RT-2029.1}, and the difference
 * is not presentational: reference cases 3 and 4 are the same instrument in the same month, and one
 * produces a 627.42 charge while the other produces nothing.
 */
class RuleSetsModuleTest {

    /** Where the routing-table collection lives. */
    private static final String ROUTING = "/api/routing-tables";

    /** Where the fee-rule-set collection lives. */
    private static final String FEES = "/api/fee-rule-sets";

    /**
     * A complete, approvable routing table that differs from the baseline in two rows.
     *
     * <p>{@code ESG_LINKED = RESET} is the change the exit gate is observed through — ADR-0006's own
     * example of what the Exposure Draft would move. {@code DISBURSEMENT_TIMING = NONE} is here to
     * pin the other half of {@code Mechanism.isRoutable()}: {@code NONE} <em>is</em> routable,
     * because a driver a bank has elected as immaterial is a position it is entitled to hold, and a
     * guard that refused it would move an accounting election into a build-time constraint.
     *
     * <p>Effective 2029-04-01, which is after the baseline's 2026-05-01. That is a requirement, not
     * a detail: {@code RoutingTableRegistry.with} is strictly an append, because inserting a reading
     * behind an existing version would re-route events in an already-closed period.
     */
    private static final String EXPOSURE_DRAFT_TABLE = """
        # The H2 2026 Exposure Draft reading: an ESG margin ratchet is consideration for credit
        # risk, so it adjusts the EIR under B5.4.5 rather than booking a B5.4.6 catch-up.
        version.id            = RT-2029.1
        version.description   = Exposure Draft reading: ESG_LINKED resets the EIR
        version.effectiveFrom = 2029-04-01
        version.maker         = policy.maker
        version.checker       = policy.checker
        version.approvedOn    = 2029-03-15

        route.TIME_VALUE_OF_MONEY          = RESET
        route.CREDIT_RISK_MARKET           = RESET
        route.CREDIT_RATCHET_PREDETERMINED = CATCH_UP
        route.ESG_LINKED                   = RESET
        route.STEP_UP_PREDETERMINED        = CATCH_UP
        route.BEHAVIOURAL_ESTIMATE         = CATCH_UP
        route.DISBURSEMENT_TIMING          = NONE
        route.NEGOTIATED                   = MODIFICATION_TEST
        """;

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

    // ================================================================= the exit gate

    @Nested
    @DisplayName("ADR-0006's exit gate: the routing table changes without a code deploy")
    class TheExitGate {

        @Test
        @DisplayName("a driver routes CATCH_UP, an approved table moves it to RESET, and nothing restarted")
        void anApprovedTableChangesASubsequentRouting() throws IOException {
            // Before. RT-BASELINE-2026.1 is 03 § 6.1's mapping: an ESG ratchet compensates for
            // neither the time value of money nor credit risk, so it falls outside B5.4.5.
            Response before = get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING"
                + "&on=2029-06-15");
            assertThat(before.status()).isEqualTo(200);
            assertThat(before.body())
                .as("the compiled-in baseline must route an ESG ratchet to a B5.4.6 catch-up; if"
                    + " this were already RESET the after-assertion below would prove nothing")
                .contains("\"mechanism\":\"CATCH_UP\"")
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"");

            Response proposed = post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));
            assertThat(proposed.status()).isEqualTo(200);
            assertThat(proposed.body())
                .as("the artefact is in the format RoutingTableFormat reads, so it parses")
                .contains("\"accepted\":true")
                .contains("\"versionId\":\"RT-2029.1\"")
                .contains("ESG_LINKED -> RESET");

            // Still the old reading. A draft is not an approved table, and the whole content of the
            // control is that it routes nothing until a second identity signs it.
            assertThat(get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING&on=2029-06-15")
                .body())
                .as("a pending draft must route nothing; if it routed, the four-eyes gate would be"
                    + " decoration")
                .contains("\"mechanism\":\"CATCH_UP\"")
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"");

            Response approved =
                post(ROUTING + "/approvals", form("id", "RT-2029.1", "checker", "policy.checker"));
            assertThat(approved.status()).isEqualTo(200);
            assertThat(approved.body())
                .contains("\"approved\":true")
                .contains("\"approvedVersions\":2")
                // The registry's own changeover sentence. The baseline is now bounded above by the
                // day before its successor takes effect, which is the fact an auditor reconciling a
                // changeover-date event needs.
                .contains("RT-BASELINE-2026.1 in force 2026-05-01 until 2029-03-31");

            // After. Same server, same process, no restart between the two assertions.
            Response after = get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING"
                + "&on=2029-06-15");
            assertThat(after.status()).isEqualTo(200);
            assertThat(after.body())
                .as("the exit gate: an approved table changed a subsequent routing with no deploy")
                .contains("\"mechanism\":\"RESET\"")
                .contains("\"routingTableVersionId\":\"RT-2029.1\"")
                .contains("\"resolvesRate\":true");
        }

        @Test
        @DisplayName("the superseded reading still governs its own dates, so a closed period replays")
        void theOldReadingStillGovernsItsOwnDates() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));
            post(ROUTING + "/approvals", form("id", "RT-2029.1", "checker", "policy.checker"));

            // 2029-03-31 is the day before RT-2029.1 takes effect. Latest-wins over the series
            // therefore still selects the baseline: adopting a new reading must not re-route a
            // period that closed under the old one, which is what invariant DT-1 detects.
            assertThat(get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING&on=2029-03-31")
                .body())
                .as("the changeover is a boundary, not a retrospective rewrite")
                .contains("\"mechanism\":\"CATCH_UP\"")
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"");

            // And a replay, which consults the recorded version id and no date at all. Asked for
            // the baseline while the new version is in force, it must answer the baseline's reading.
            assertThat(get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING"
                + "&version=RT-BASELINE-2026.1").body())
                .as("a replay keyed on the event's own version id, not the calendar")
                .contains("\"mechanism\":\"CATCH_UP\"")
                .contains("the version id recorded on the event");
        }

        @Test
        @DisplayName("a routing needs a date or a recorded version and never both")
        void aRoutingSelectsByDateOrByVersionAndNeverBoth() throws IOException {
            Response both = get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING"
                + "&on=2029-06-15&version=RT-BASELINE-2026.1");

            // 400, because the caller has asked two different questions at once and this layer is
            // not entitled to choose between two readings of a closed period.
            assertThat(both.status()).isEqualTo(400);
            assertThat(both.body()).contains("exactly one of 'on'").contains("got both");
            assertThat(get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING").status())
                .isEqualTo(400);
        }

        @Test
        @DisplayName("the emitted artefact round-trips: what comes back can be resubmitted")
        void theArtefactRoundTrips() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));

            Response artefact = get(ROUTING + "/artefacts?version=RT-2029.1");

            assertThat(artefact.status()).isEqualTo(200);
            assertThat(artefact.body())
                .as("RoutingTableFormat.emit is deterministic, so two approved artefacts diff to"
                    + " exactly the rows that changed — which is what a checker approves against")
                .contains("\"found\":true")
                .contains("version.id = RT-2029.1")
                .contains("route.ESG_LINKED = RESET")
                .contains("route.DISBURSEMENT_TIMING = NONE");
        }
    }

    // ================================================================= the refusals

    @Nested
    @DisplayName("a table naming DERECOGNITION is refused for the accounting reason, not as a parse fault")
    class DerecognitionIsNotARouting {

        /** The same artefact with one row changed, so nothing else can explain the refusal. */
        private static final String DERECOGNITION_TABLE = EXPOSURE_DRAFT_TABLE
            .replace("route.ESG_LINKED                   = RESET",
                     "route.ESG_LINKED                   = DERECOGNITION")
            .replace("RT-2029.1", "RT-2029.9");

        @Test
        @DisplayName("refused with the substantiality-assessment reason and labelled ACCOUNTING")
        void derecognitionIsRefusedWithTheAccountingReason() throws IOException {
            Response response = post(ROUTING + "/proposals", form("table", DERECOGNITION_TABLE));

            // 200: a refusal is a value in this engine, and the whole reason comes back.
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("DERECOGNITION is the conclusion of the substantiality assessment, reached per"
                    + " modification through MODIFICATION_TEST — never a treatment a driver carries."
                    + " Mechanism.isRoutable() holds that rule and RoutingTable's constructor"
                    + " enforces it")
                .contains("\"accepted\":false")
                .contains("\"fault\":\"ACCOUNTING\"")
                .contains("a mechanism no routing may name")
                .contains("derecognition is the conclusion of the substantiality assessment")
                .contains("never a treatment a driver carries")
                .contains("not a parse error");
            assertThat(response.body())
                .as("a line number here would be the wrong diagnosis: the file has no syntax error")
                .doesNotContain("\"fault\":\"FORMAT\"");
        }

        @Test
        @DisplayName("and a real parse fault is labelled FORMAT with its line, so the two are told apart")
        void aParseFaultIsLabelledSeparately() throws IOException {
            // A control that cannot distinguish its two cases is not a control. This is the negative
            // half: text that genuinely cannot be read must come back FORMAT, or ACCOUNTING would
            // just be the label every bad submission gets.
            Response response = post(ROUTING + "/proposals",
                form("table", "version.id = RT-BAD\nversion.description no equals sign\n",
                     "sourceName", "candidate.txt"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"fault\":\"FORMAT\"")
                .contains("candidate.txt:2")
                .contains("no '=' separator");
        }

        @Test
        @DisplayName("NONE is accepted and routes, because a materiality election is a real position")
        void noneIsRoutable() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));
            post(ROUTING + "/approvals", form("id", "RT-2029.1", "checker", "policy.checker"));

            // DISBURSEMENT_TIMING = NONE in the approved table. A late tranche drawdown whose
            // present-value effect a bank has elected not to restate for is a position it is
            // entitled to hold; the engine's obligation is then to roll the period forward at the
            // unchanged rate and restate nothing, which is what NONE means.
            assertThat(get(ROUTING + "/routings?driver=DISBURSEMENT_TIMING&rateType=FLOATING"
                + "&on=2029-06-15").body())
                .as("if NONE were refused alongside DERECOGNITION, an accounting election would"
                    + " have become a build-time constraint")
                .contains("\"routed\":true")
                .contains("\"mechanism\":\"NONE\"")
                .contains("\"routingTableVersionId\":\"RT-2029.1\"");
        }
    }

    @Nested
    @DisplayName("four eyes on a routing table, which is not ceremony")
    class FourEyesOnARoutingTable {

        @Test
        @DisplayName("the maker cannot approve their own table, even under a different capitalisation")
        void aSelfApprovalIsRefused() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));

            // 'Policy.Maker' against a maker of 'policy.maker'. FourEyes folds case to Locale.ROOT,
            // and this is the exact evasion that used to work: RoutingTableVersion compared raw
            // strings while the DDL column it is stored in compares lower(btrim(...)), so a table
            // could be self-approved by capitalising a letter and the row would then be rejected by
            // the database that stores it.
            Response response = post(ROUTING + "/approvals",
                form("id", "RT-2029.1", "checker", "Policy.Maker"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"approved\":false")
                .contains("\"refusal\":\"SELF_APPROVAL\"")
                .contains("is the same identity")
                .contains("627.42");

            // And the refusal has to be effective, not merely reported: the series must not have
            // grown, and the routing must not have moved.
            assertThat(get(ROUTING).body()).contains("\"approvedVersions\":1");
            assertThat(get(ROUTING + "/routings?driver=ESG_LINKED&rateType=FLOATING&on=2029-06-15")
                .body())
                .contains("\"routingTableVersionId\":\"RT-BASELINE-2026.1\"");
        }

        @Test
        @DisplayName("an approval by somebody the table was not routed to is refused")
        void anApprovalByTheWrongCheckerIsRefused() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));

            Response response = post(ROUTING + "/approvals",
                form("id", "RT-2029.1", "checker", "someone.else"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("the version record is immutable and its checker field is what every audit"
                    + " sentence reads, so this approval would be published in policy.checker's name")
                .contains("\"refusal\":\"CHECKER_CONFLICT\"")
                .contains("names checker 'policy.checker'")
                .contains("the approval is by 'someone.else'");
        }

        @Test
        @DisplayName("approving an id nobody drafted refuses and names what is pending")
        void anUnknownDraftIsRefused() throws IOException {
            Response response = post(ROUTING + "/approvals",
                form("id", "RT-NOBODY-WROTE-THIS", "checker", "policy.checker"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"refusal\":\"UNKNOWN_DRAFT\"")
                .contains("already approved: [RT-BASELINE-2026.1]");
        }

        @Test
        @DisplayName("a table effective on or before the version in force is refused as a restatement")
        void insertingATableBehindTheSeriesIsRefused() throws IOException {
            // Effective 2026-04-01, one month before the baseline's 2026-05-01. Adopting a reading
            // is an append; sliding one in behind an existing version would silently re-route
            // events in an already-closed month on the next recompute.
            String retrospective = EXPOSURE_DRAFT_TABLE
                .replace("version.effectiveFrom = 2029-04-01", "version.effectiveFrom = 2026-04-01")
                .replace("version.approvedOn    = 2029-03-15",
                         "version.approvedOn    = 2026-03-15")
                .replace("RT-2029.1", "RT-2026.0");
            post(ROUTING + "/proposals", form("table", retrospective));

            Response response = post(ROUTING + "/approvals",
                form("id", "RT-2026.0", "checker", "policy.checker"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"refusal\":\"NOT_AN_APPEND\"")
                .contains("which is a restatement decision and not an adoption");
        }

        @Test
        @DisplayName("a version id already approved is refused at submission, before a checker sees it")
        void aDuplicateVersionIdIsRefused() throws IOException {
            String reusingTheBaselineId =
                EXPOSURE_DRAFT_TABLE.replace("RT-2029.1", "RT-BASELINE-2026.1");

            Response response = post(ROUTING + "/proposals", form("table", reusingTheBaselineId));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("two mappings under one id make every event routed by either indistinguishable"
                    + " on replay — the failure DT-1 detects after the fact and cannot repair")
                .contains("\"fault\":\"DUPLICATE_ID\"")
                .contains("is already approved");
        }
    }

    // ================================================================= fee rule sets

    @Nested
    @DisplayName("the fee rule set: the same lifecycle, over the taxonomy that classifies a posting")
    class FeeRuleSets {

        /**
         * Four rows exercising three of the four specificity shapes and one exclusion.
         *
         * <p>{@code DSA_COMM} carries both a product rule and a per-code default on purpose: 03 §
         * 3.2 resolves most-specific-first, so a housing-loan posting must take the
         * {@code (code, HL, *)} row and a personal-loan posting the {@code (code, *, *)} default.
         */
        private static final String ROWS = """
            # fee_code | product | entity | effective_from | classification | rationale
            PROC_FEE  | *  | * | 2029-04-01 | INTEGRAL              | origination fee, ACPIR 53
            DSA_COMM  | HL | * | 2029-04-01 | INTEGRAL              | incremental selling cost
            DSA_COMM  | *  | * | 2029-04-01 | AS_INCURRED           | not demonstrably incremental
            PENAL_CHG | *  | * | 2029-04-01 | EXCLUDED_BY_DIRECTION | excluded by RBI direction
            """;

        @Test
        @DisplayName("an unmapped fee code refuses into the exception queue and carries no classification")
        void anUnmappedFeeCodeRefuses() throws IOException {
            // The baseline taxonomy states one rule for one code, deliberately: a demonstration set
            // covering every code an operator might type would hide the single most important
            // behaviour in the fee rule set.
            Response response = get(FEES + "/classifications?feeCode=DSA_COMM&product=HL"
                + "&entity=IN-MUM&asOf=2028-05-31");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("both available defaults are wrong in the direction nobody checks: INTEGRAL"
                    + " spreads the error over sixty periods as a small yield difference,"
                    + " AS_INCURRED books it as period-one income nobody questions. On reference"
                    + " case 1, 5,000 of net fee on a million moves the EIR 56.6 basis points")
                .contains("\"resolved\":false")
                .contains("\"classification\":null")
                .contains("\"exceptionCategory\":\"UNMAPPED_FEE_CODE\"")
                .contains("rather than defaulting to either treatment");
        }

        @Test
        @DisplayName("an approved version classifies, and most-specific-wins decides which rule")
        void anApprovedVersionClassifies() throws IOException {
            Response proposed = post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.1",
                "description", "fee and cost taxonomy, FY2029-30",
                "effectiveFrom", "2029-04-01",
                "maker", "policy.maker",
                "checker", "policy.checker",
                "rules", ROWS));
            assertThat(proposed.status()).isEqualTo(200);
            assertThat(proposed.body())
                .contains("\"accepted\":true")
                .contains("\"status\":\"PENDING_APPROVAL\"")
                .contains("\"operative\":false")
                // Invariant RS-1: every one of the three codes carries a per-code default, so the
                // completeness control passes with a deviation of zero.
                .contains("\"catchAllCoverage\":\"RS_1 PASS\"")
                .contains("\"catchAllDeviation\":\"0\"");

            // Pending, so it classifies nothing — the taxonomy in force is still the baseline.
            assertThat(get(FEES + "/classifications?feeCode=PENAL_CHG&product=HL&entity=IN-MUM"
                + "&asOf=2029-06-30").body())
                .as("a version awaiting a checker must not classify a posting")
                .contains("\"resolved\":false");

            assertThat(post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.1", "checker", "policy.checker",
                "approvedOn", "2029-03-15")).body())
                .contains("\"approved\":true")
                .contains("\"status\":\"EFFECTIVE\"");

            // Most-specific-wins: the (DSA_COMM, HL, *) row outranks the (DSA_COMM, *, *) default,
            // because product is the more significant dimension — the classification is a fact about
            // what the fee is for, and the product is what fixes that (03 § 3.2, FR-201).
            assertThat(get(FEES + "/classifications?feeCode=DSA_COMM&product=HL&entity=IN-MUM"
                + "&asOf=2029-06-30").body())
                .contains("\"classification\":\"INTEGRAL\"")
                .contains("(DSA_COMM, HL, *, 2029-04-01)")
                .contains("\"ruleSetVersionId\":\"POL-FEE-2029.1\"");

            // The same code on a product nobody wrote a carve-out for takes the per-code default.
            assertThat(get(FEES + "/classifications?feeCode=DSA_COMM&product=PL&entity=IN-MUM"
                + "&asOf=2029-06-30").body())
                .contains("\"classification\":\"AS_INCURRED\"")
                .contains("(DSA_COMM, *, *, 2029-04-01)");

            // And a lookup before the effective date still refuses. Approval is the status; the
            // date is what keeps an earlier posting off the new reading.
            assertThat(get(FEES + "/classifications?feeCode=DSA_COMM&product=HL&entity=IN-MUM"
                + "&asOf=2029-03-31").body())
                .as("a version approved for next quarter must classify nothing this quarter")
                .contains("\"resolved\":false")
                .contains("POL-FEE-2028.1");
        }

        @Test
        @DisplayName("the maker cannot approve their own rule set, and the gate's own reason comes back")
        void aSelfApprovalIsRefused() throws IOException {
            post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.1", "description", "fee and cost taxonomy, FY2029-30",
                "effectiveFrom", "2029-04-01", "maker", "policy.maker",
                "checker", "policy.checker", "rules", ROWS));

            Response response = post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.1", "checker", "POLICY.MAKER", "approvedOn", "2029-03-15"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("MakerCheckerGate owns this refusal and its vocabulary is passed through, so a"
                    + " batch can separate 'send it to a different checker' from 'a defect in the"
                    + " caller' without matching on English")
                .contains("\"approved\":false")
                .contains("\"gateRefusal\":\"SELF_APPROVAL\"")
                .contains("a self-approval is the absence of one");

            // Effective, not merely reported: the version must still classify nothing.
            assertThat(get(FEES + "/classifications?feeCode=PENAL_CHG&product=HL&entity=IN-MUM"
                + "&asOf=2029-06-30").body()).contains("\"resolved\":false");
        }

        @Test
        @DisplayName("a wildcard fee code is refused in the domain's words, not as a bad row")
        void aWildcardFeeCodeIsRefused() throws IOException {
            Response response = post(FEES + "/proposals", form(
                "id", "POL-FEE-GLOBAL", "description", "one row to classify everything",
                "effectiveFrom", "2030-04-01", "maker", "policy.maker",
                "checker", "policy.checker",
                "rules", "* | * | * | 2030-04-01 | INTEGRAL | classify everything"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("a (*, *, *) row would classify every posting, so no code could ever be"
                    + " unmapped and FR-202 would be unenforceable by construction — the exception"
                    + " queue empty because nothing can miss, not because the taxonomy is complete")
                .contains("\"fault\":\"ACCOUNTING\"")
                .contains("'*' is not a fee code")
                .contains("makes FR-202 unenforceable");
        }

        @Test
        @DisplayName("a malformed row is refused with its row number")
        void aMalformedRowIsRefused() throws IOException {
            Response response = post(FEES + "/proposals", form(
                "id", "POL-FEE-BADROW", "description", "one field short",
                "effectiveFrom", "2030-04-01", "maker", "policy.maker",
                "checker", "policy.checker",
                "rules", "PROC_FEE | * | * | 2030-04-01 | INTEGRAL"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"fault\":\"FORMAT\"")
                .contains(":1: expected 6 fields")
                .contains("and found 5");
        }

        @Test
        @DisplayName("two operative versions cannot share an effective date, and it is caught at approval")
        void anEffectiveDateCollisionIsRefusedAtApproval() throws IOException {
            post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.1", "description", "fee and cost taxonomy, FY2029-30",
                "effectiveFrom", "2029-04-01", "maker", "policy.maker",
                "checker", "policy.checker", "rules", ROWS));
            post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.1", "checker", "policy.checker", "approvedOn", "2029-03-15"));
            post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.2", "description", "a second reading of the same first day",
                "effectiveFrom", "2029-04-01", "maker", "second.maker",
                "checker", "second.checker",
                "rules", "PROC_FEE | * | * | 2029-04-01 | AS_INCURRED | a different reading"));

            Response response = post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.2", "checker", "second.checker", "approvedOn", "2029-03-20"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("nothing orders two operative readings sharing a first day, so which one"
                    + " classified a posting would depend on registration order. Refused at the"
                    + " approval, because FeeClassificationResolver refuses it by throwing on the"
                    + " next classification — long after the approval, and naming neither")
                .contains("\"refusal\":\"EFFECTIVE_DATE_COLLISION\"")
                .contains("the same day as operative version 'POL-FEE-2029.1'");

            // The taxonomy is still usable, which is the point of refusing here rather than there.
            assertThat(get(FEES + "/classifications?feeCode=PROC_FEE&product=HL&entity=IN-MUM"
                + "&asOf=2029-06-30").body())
                .contains("\"classification\":\"INTEGRAL\"")
                .contains("\"ruleSetVersionId\":\"POL-FEE-2029.1\"");
        }

        @Test
        @DisplayName("pinning an unregistered version is 400 and pinning an unapproved one refuses")
        void aPinnedVersionIsAnsweredAndNeverDefects() throws IOException {
            // Both used to be 500s. FeeClassificationResolver.resolveAgainstVersion throws for an
            // unregistered id and for an unapproved one, and an uncaught throw at this layer becomes
            // "the engine is broken" for what is a caller's typo in the first case and a plain
            // consequence of the approval control in the second.
            Response unregistered = get(FEES + "/classifications?feeCode=PROC_FEE"
                + "&asOf=2028-05-31&version=NO-SUCH-VERSION");
            assertThat(unregistered.status())
                .as("a typo'd version id is the caller sending nonsense, not an engine defect")
                .isEqualTo(400);
            assertThat(unregistered.body())
                .contains("no fee rule set version 'NO-SUCH-VERSION' is registered")
                .contains("POL-FEE-2028.1");

            post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.1", "description", "fee and cost taxonomy, FY2029-30",
                "effectiveFrom", "2029-04-01", "maker", "policy.maker",
                "checker", "policy.checker", "rules", ROWS));

            Response pending = get(FEES + "/classifications?feeCode=PROC_FEE"
                + "&asOf=2029-06-30&version=POL-FEE-2029.1");
            assertThat(pending.status())
                .as("a version nobody has signed has classified nothing, which is an answer")
                .isEqualTo(200);
            assertThat(pending.body())
                .contains("\"resolved\":false")
                .contains("\"refusal\":\"VERSION_NOT_APPROVED\"")
                .contains("\"classification\":null")
                .contains("no computation can cite an unapproved version");

            // And once approved, the same pinned lookup resolves — so the refusal above is about
            // the approval status and not about pinning itself.
            post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.1", "checker", "policy.checker", "approvedOn", "2029-03-15"));
            assertThat(get(FEES + "/classifications?feeCode=PROC_FEE&asOf=2029-06-30"
                + "&version=POL-FEE-2029.1").body())
                .contains("\"classification\":\"INTEGRAL\"")
                .contains("\"ruleSetVersionId\":\"POL-FEE-2029.1\"");
        }

        @Test
        @DisplayName("approving a successor supersedes its predecessor, so no two versions read EFFECTIVE")
        void theApprovedVersionSupersedesItsPredecessor() throws IOException {
            post(FEES + "/proposals", form(
                "id", "POL-FEE-2029.1", "description", "fee and cost taxonomy, FY2029-30",
                "effectiveFrom", "2029-04-01", "maker", "policy.maker",
                "checker", "policy.checker", "rules", ROWS));

            Response approved = post(FEES + "/approvals", form(
                "id", "POL-FEE-2029.1", "checker", "policy.checker", "approvedOn", "2029-03-15"));

            assertThat(approved.body())
                .as("SUPERSEDED means replaced and names its successor; a version marked superseded"
                    + " with nothing in its place leaves the dates it governed resolving against"
                    + " nothing")
                .contains("version 'POL-FEE-2028.1' marked SUPERSEDED by 'POL-FEE-2029.1'");

            Response listing = get(FEES);
            assertThat(listing.body())
                .as("two versions both EFFECTIVE is not a state 04 § 2.12's life cycle describes,"
                    + " and a listing read as an audit trail would not say which governed")
                .contains("\"versionId\":\"POL-FEE-2028.1\",\"status\":\"SUPERSEDED\"")
                .contains("\"versionId\":\"POL-FEE-2029.1\",\"status\":\"EFFECTIVE\"");

            // Superseded stays operative, and that is the point: a posting dated inside the old
            // version's window must still resolve against it (invariant DT-1).
            assertThat(get(FEES + "/classifications?feeCode=PROC_FEE&product=HL&entity=IN-MUM"
                + "&asOf=2028-05-31").body())
                .as("a closed period replays against the reading it closed under")
                .contains("\"classification\":\"INTEGRAL\"")
                .contains("\"ruleSetVersionId\":\"POL-FEE-2028.1\"");
        }

        @Test
        @DisplayName("reusing a version id is refused; a taxonomy change is a new version")
        void aDuplicateVersionIdIsRefused() throws IOException {
            Response response = post(FEES + "/proposals", form(
                "id", "POL-FEE-2028.1", "description", "an edit to a version in force",
                "effectiveFrom", "2029-04-01", "maker", "policy.maker",
                "checker", "policy.checker", "rules", ROWS));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .as("04 § 2.12: immutable once approved. Every EIR_COMPUTATION row stores the"
                    + " rule_set_version_id that classified its postings (04 § 2.6)")
                .contains("\"fault\":\"DUPLICATE_ID\"")
                .contains("already exists (EFFECTIVE)");
        }
    }

    // ================================================================= the surface itself

    @Nested
    @DisplayName("the surface: what the collections say about themselves, and the three status codes")
    class TheSurface {

        @Test
        @DisplayName("the routing-table collection lists the baseline in full and names the POST paths")
        void theCollectionDescribesItself() throws IOException {
            Response response = get(ROUTING);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"specSection\":\"06 § 5\"")
                .contains("\"approvedVersions\":1")
                .contains("\"versionId\":\"RT-BASELINE-2026.1\"")
                // Every driver, always. A response listing only the rows that differ from some
                // notional default would invite a reader to assume a default, and the table is
                // total by construction precisely because a defaulted routing is a silently wrong
                // one.
                .contains("TIME_VALUE_OF_MONEY -> RESET")
                .contains("NEGOTIATED -> MODIFICATION_TEST")
                // The collection path cannot itself accept a POST — one JDK context per path, and
                // the verb check runs before any handler — so the listing has to say where the POSTs
                // live or an integrator is left guessing.
                .contains("POST /api/routing-tables/proposals")
                .contains("POST /api/routing-tables/approvals");
        }

        @Test
        @DisplayName("the fee-rule-set collection asserts RS-1 per version")
        void theFeeCollectionAssertsCompleteness() throws IOException {
            Response response = get(FEES);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"versionId\":\"POL-FEE-2028.1\"")
                .contains("\"catchAllCoverage\":\"RS_1 PASS\"")
                .contains("every fee code has a per-code default in force")
                .contains("POST /api/fee-rule-sets/proposals");
        }

        @Test
        @DisplayName("the surface states what an approval does not change, and what its identities are worth")
        void theSurfaceStatesItsOwnLimits() throws IOException {
            // Two claims this module must not make silently. Both were review findings, and both are
            // the kind of thing that is only a defect because a response reads as a guarantee.
            assertThat(get(ROUTING).body())
                .as("an approved table here does NOT change what POST /api/run routes: EirService"
                    + " holds its own private series, and an operator told only 'approved' would"
                    + " reasonably expect otherwise")
                .contains("\"notYetInForceFor\"")
                .contains("POST /api/run")
                .contains("still see only RT-BASELINE-2026.1");
            assertThat(get(ROUTING).body())
                .as("the four-eyes gate compares identities the caller supplies, and eir-api"
                    + " authenticates nobody; 'four-eyes is not ceremony here' unqualified would be"
                    + " the more misleading of the two available failures")
                .contains("\"identityAssurance\"")
                .contains("authenticated nowhere");

            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));
            assertThat(post(ROUTING + "/approvals",
                form("id", "RT-2029.1", "checker", "policy.checker")).body())
                .as("the caveats travel with the approval, not only with the listing")
                .contains("\"notYetInForceFor\"")
                .contains("\"identityAssurance\"");

            assertThat(get(FEES).body()).contains("\"identityAssurance\"");
        }

        @Test
        @DisplayName("a missing field is 400 and an unknown sub-path is 400, not a silent default")
        void aMalformedRequestIs400() throws IOException {
            Response noTable = post(ROUTING + "/proposals", "");
            assertThat(noTable.status()).isEqualTo(400);
            assertThat(noTable.body()).contains("'table' is required and arrived absent");

            Response unknownPath = get(ROUTING + "/nonsense");
            assertThat(unknownPath.status()).isEqualTo(400);
            assertThat(unknownPath.body()).contains("no route /api/routing-tables/nonsense");

            Response badDate = post(FEES + "/approvals",
                form("id", "POL-FEE-2028.1", "checker", "x", "approvedOn", "15/03/2029"));
            assertThat(badDate.status()).isEqualTo(400);
            assertThat(badDate.body()).contains("must be an ISO-8601 date");

            Response badDriver =
                get(ROUTING + "/routings?driver=esg_linked&rateType=FLOATING&on=2029-06-15");
            assertThat(badDriver.status())
                .as("exact match only: accepting 'esg_linked' would make the vocabulary a matter of"
                    + " taste and the meaning of a stored request ambiguous")
                .isEqualTo(400);
            assertThat(badDriver.body()).contains("must be one of").contains("exactly");
        }

        @Test
        @DisplayName("a GET on a POST path is 405 and vice versa, so nothing 404s a route that exists")
        void theWrongVerbIs405() throws IOException {
            // A 404 on a route that exists sends an integrator looking in the wrong place, which is
            // why EirServer answers 405 rather than falling through to the page handler.
            assertThat(get(ROUTING + "/proposals").status()).isEqualTo(405);
            assertThat(post(ROUTING, "x=1").status()).isEqualTo(405);
            assertThat(get(FEES + "/approvals").status()).isEqualTo(405);
        }

        @Test
        @DisplayName("every registered route answers, so no path was silently shadowed")
        void everyRegisteredRouteAnswers() throws IOException {
            // The JDK's server matches the LONGEST context prefix and pays no attention to the
            // verb, so a POST context at /api/routing-tables/proposals would shadow a GET at
            // /api/routing-tables/proposals/anything. This asserts the six routes as a set: if a
            // future sub-path were added under a POST context, one of these would flip to 405 and
            // the shadowing would be a test failure rather than a 405 an integrator reports months
            // later.
            assertThat(get(ROUTING).status()).isEqualTo(200);
            assertThat(get(ROUTING + "/routings?driver=NEGOTIATED&rateType=FIXED&on=2029-06-15")
                .status()).isEqualTo(200);
            assertThat(get(ROUTING + "/artefacts?version=RT-BASELINE-2026.1").status())
                .isEqualTo(200);
            assertThat(get(FEES).status()).isEqualTo(200);
            assertThat(get(FEES + "/classifications?feeCode=PROC_FEE&asOf=2028-05-31").status())
                .isEqualTo(200);
            assertThat(post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE)).status())
                .isEqualTo(200);
            assertThat(post(ROUTING + "/approvals",
                form("id", "RT-2029.1", "checker", "policy.checker")).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("the rate-type check overrides the table, in this version and every future one")
        void theRateTypeCheckSurvivesEveryTableVersion() throws IOException {
            post(ROUTING + "/proposals", form("table", EXPOSURE_DRAFT_TABLE));
            post(ROUTING + "/approvals", form("id", "RT-2029.1", "checker", "policy.checker"));

            // TIME_VALUE_OF_MONEY is RESET in both tables. On a FIXED-rate instrument it must still
            // route to MODIFICATION_TEST: a fixed-rate instrument has no term that reprices off a
            // benchmark, so the combination can only have arisen from renegotiation. That rule is
            // in code deliberately, because its premise is the instrument's own terms rather than
            // any reading of B5.4.5 — and putting it in data would let a future table version
            // quietly route a renegotiated fixed-rate loan to a reset, so the whole modification
            // question would disappear from the ledger without anyone declining it (FR-507).
            Response response = get(ROUTING + "/routings?driver=TIME_VALUE_OF_MONEY"
                + "&rateType=FIXED&on=2029-06-15");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body())
                .contains("\"mechanism\":\"MODIFICATION_TEST\"")
                .contains("\"overriddenByRateTypeCheck\":true")
                .contains("\"routingTableVersionId\":\"RT-2029.1\"")
                .contains("never the observation that the rate moved");
        }
    }

    // ================================================================= plumbing

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
     * Alternating keys and values, form-encoded.
     *
     * <p>Encoded rather than concatenated, because the routing artefact is multi-line text with
     * {@code =} signs in it, and a raw body would be parsed as a dozen malformed pairs. This is what
     * {@code curl --data-urlencode} does, and the point of routing the test through the same
     * encoding is that the artefact crossing the wire here is byte-identical to the one crossing it
     * in the operator recipe.
     */
    private static String form(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("form() takes alternating keys and values");
        }
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            if (index > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(keysAndValues[index], StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(keysAndValues[index + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }
}
