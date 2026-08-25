package com.crisil.eir.policy.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The routing table's text format — the construction path that makes "the routing
 * table can be changed without a code deploy" true (ADR-0006, roadmap Phase 2 exit
 * gate).
 *
 * <p>Two things are asserted, and they pull in opposite directions.
 *
 * <p><strong>A hand-written file parses.</strong> The canonical artefact
 * {@code routing-table-2027-1.txt} and the deliberately untidy
 * {@code routing-table-hand-edited.txt} are the same approved table typed two ways —
 * aligned, indented, reordered, commented, one without a trailing newline. Both must
 * produce an equal {@link RoutingTable}, because the file is edited in a text editor
 * by the accounting policy owner and not only written by the emitter.
 *
 * <p><strong>A wrong file does not parse at all.</strong> Every hostile case below
 * would, if tolerated, change the P&amp;L of a portfolio silently: reference cases 3
 * and 4 are the same instrument in the same month, one carrying a 627.42 charge and
 * the other nothing, and the difference is one row of this file. So a duplicate row,
 * an absent row, a typo'd driver, a typo'd mechanism and a malformed date are all
 * refusals that name the line — never a default, a coercion, or a last-one-wins.
 *
 * <p>Expected values here are written out by hand: the eight rows are enumerated in
 * {@link #specDefaultMapping()} rather than taken from
 * {@code RoutingTable.ofSpecDefaults}, and the byte-for-byte emission test compares
 * against a checked-in file rather than against the emitter's own output. A
 * round-trip test whose expectation comes from the code under test asserts only that
 * the code is self-consistent, which a pair of matching bugs also satisfies.
 */
class RoutingTableFormatTest {

    /** What errors are reported against, so the assertions can look for it. */
    private static final String SOURCE = "policy/routing-table.txt";

    /**
     * The approved version recorded in the canonical test resource, restated here.
     *
     * <p>Deliberately not {@code RoutingTable.currentDefault().version()}: that
     * baseline belongs to eir-calc and its wording may move. This fixture is a
     * bank's own adoption of the specification reading — its own id, maker, checker
     * and effective date, which is the whole substance of the control (ADR-0006).
     */
    private static final RoutingTableVersion APPROVED = new RoutingTableVersion(
        "RT-2027.1",
        "Calculation specification 03 section 6.1, adopted as bank policy for the ACPIR 2026 "
            + "first year: re-estimations providing consideration for the time value of money or "
            + "for credit risk adjust the EIR (B5.4.5), and pre-determined adjustments "
            + "compensating for neither route to a B5.4.6 catch-up.",
        LocalDate.of(2027, 4, 1),
        "policy.author@bank.example",
        "policy.approver@bank.example",
        LocalDate.of(2027, 2, 26));

    /**
     * A minimal valid file, used as the base every hostile case mutates.
     *
     * <p>Line numbers matter to the assertions: {@code version.id} is line 1,
     * {@code version.approvedOn} line 6, {@code route.TIME_VALUE_OF_MONEY} line 7,
     * {@code route.ESG_LINKED} line 10 and {@code route.NEGOTIATED} line 14.
     */
    private static final String MINIMAL = """
        version.id = RT-2027.2
        version.description = a minimal table, for the reader's own tests
        version.effectiveFrom = 2027-04-01
        version.maker = maker@bank.example
        version.checker = checker@bank.example
        version.approvedOn = 2027-02-26
        route.TIME_VALUE_OF_MONEY = RESET
        route.CREDIT_RISK_MARKET = RESET
        route.CREDIT_RATCHET_PREDETERMINED = CATCH_UP
        route.ESG_LINKED = CATCH_UP
        route.STEP_UP_PREDETERMINED = CATCH_UP
        route.BEHAVIOURAL_ESTIMATE = CATCH_UP
        route.DISBURSEMENT_TIMING = CATCH_UP
        route.NEGOTIATED = MODIFICATION_TEST
        """;

    /**
     * The specification section 6.1 mapping, restated row by row.
     *
     * <p>The April 2026 IASB tentative decision reading: consideration for the time
     * value of money or for market credit risk adjusts the rate; a pre-determined
     * step compensating for neither, and the entity's own revised estimates, route to
     * a catch-up; renegotiation runs the substantiality test.
     */
    private static Map<RateDriver, Mechanism> specDefaultMapping() {
        Map<RateDriver, Mechanism> mapping = new EnumMap<>(RateDriver.class);
        mapping.put(RateDriver.TIME_VALUE_OF_MONEY, Mechanism.RESET);
        mapping.put(RateDriver.CREDIT_RISK_MARKET, Mechanism.RESET);
        mapping.put(RateDriver.CREDIT_RATCHET_PREDETERMINED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.ESG_LINKED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.STEP_UP_PREDETERMINED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.BEHAVIOURAL_ESTIMATE, Mechanism.CATCH_UP);
        mapping.put(RateDriver.DISBURSEMENT_TIMING, Mechanism.CATCH_UP);
        mapping.put(RateDriver.NEGOTIATED, Mechanism.MODIFICATION_TEST);
        return mapping;
    }

    /** The approved table the two checked-in resources both describe. */
    private static RoutingTable approvedTable() {
        return new RoutingTable(APPROVED, specDefaultMapping());
    }

    /** A checked-in artefact, read as UTF-8 exactly as {@code parse(Path)} would. */
    private static String resource(String name) {
        try (InputStream stream =
                 RoutingTableFormatTest.class.getResourceAsStream("/routing/" + name)) {
            assertThat(stream).as("test resource /routing/%s must be on the classpath", name)
                .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    /** Refuses, and hands back the refusal so its line number can be asserted. */
    private static RoutingTableFormatException faultFrom(String text) {
        Throwable thrown = catchThrowable(() -> RoutingTableFormat.parse(text, SOURCE));
        assertThat(thrown)
            .as("a routing table this wrong must be refused outright, never half-loaded")
            .isInstanceOf(RoutingTableFormatException.class);
        return (RoutingTableFormatException) thrown;
    }

    @Nested
    @DisplayName("reading an approved artefact")
    class Parsing {

        @Test
        @DisplayName("the canonical file parses to the approved table, version and all eight rows")
        void theCanonicalFileParsesToTheApprovedTable() {
            RoutingTable parsed = RoutingTableFormat.parse(resource("routing-table-2027-1.txt"));

            assertThat(parsed)
                .as("the file is the only construction path that needs no compiler; it must "
                    + "produce exactly the table ofSpecDefaults used to hard-code")
                .isEqualTo(approvedTable());
            assertThat(parsed.version().id())
                .as("the id every routed event stores, for replay under the same reading")
                .isEqualTo("RT-2027.1");
            assertThat(parsed.mapping())
                .as("total over RateDriver: a defaulted routing is a silently wrong routing")
                .containsExactlyInAnyOrderEntriesOf(specDefaultMapping());
        }

        @Test
        @DisplayName("a hand-edited file — aligned, indented, reordered, commented — parses the same")
        void theHandEditedFileParsesToTheSameTable() {
            // Alignment and row order are presentation. If they changed the meaning, a policy
            // owner tidying the file for review would silently re-route a portfolio.
            RoutingTable parsed =
                RoutingTableFormat.parse(resource("routing-table-hand-edited.txt"));

            assertThat(parsed).isEqualTo(approvedTable());
        }

        @Test
        @DisplayName("the two artefacts are the same table written two ways")
        void bothArtefactsAgree() {
            assertThat(RoutingTableFormat.parse(resource("routing-table-hand-edited.txt")))
                .isEqualTo(RoutingTableFormat.parse(resource("routing-table-2027-1.txt")));
        }

        @Test
        @DisplayName("a CRLF file does not leak a carriage return into a value")
        void crlfLineEndingsAreTolerated() {
            // The file will be edited on Windows at some point. A trailing '\r' inside
            // version.id would produce an id that does not match the one stored on events —
            // the failure would surface months later as an unresolvable version on replay.
            String crlf = MINIMAL.replace("\n", "\r\n");

            RoutingTable parsed = RoutingTableFormat.parse(crlf, SOURCE);

            assertThat(parsed.version().id()).isEqualTo("RT-2027.2");
            assertThat(parsed.mechanismFor(RateDriver.NEGOTIATED))
                .isEqualTo(Mechanism.MODIFICATION_TEST);
        }

        @Test
        @DisplayName("a description may contain '=', '#' and the four escapes")
        void awkwardTextInAValueSurvives() {
            String text = MINIMAL.replace(
                "version.description = a minimal table, for the reader's own tests",
                "version.description = rate = benchmark + spread\\nsee #6.1\\ttab\\\\slash");

            RoutingTable parsed = RoutingTableFormat.parse(text, SOURCE);

            // Only the FIRST '=' separates, '#' is a comment marker only at the start of a
            // line, and \n \t \\ decode. Written out in full rather than derived.
            assertThat(parsed.version().description())
                .isEqualTo("rate = benchmark + spread\nsee #6.1\ttab\\slash");
        }

        @Test
        @DisplayName("a driver may be routed to NONE: the format does not narrow the policy domain")
        void anyMechanismConstantIsAccepted() {
            // DISBURSEMENT_TIMING routed to NONE is a legitimate materiality election. The
            // format's job is to say what the file means, not to hold an accounting opinion
            // that RoutingTable itself does not hold.
            String text = MINIMAL.replace(
                "route.DISBURSEMENT_TIMING = CATCH_UP", "route.DISBURSEMENT_TIMING = NONE");

            assertThat(RoutingTableFormat.parse(text, SOURCE)
                .mechanismFor(RateDriver.DISBURSEMENT_TIMING)).isEqualTo(Mechanism.NONE);
        }

        @Test
        @DisplayName("parse(Path) reads UTF-8, so a non-ASCII approver name survives")
        void parseFromFileReadsUtf8(@TempDir Path directory) throws IOException {
            String text = MINIMAL.replace("checker@bank.example", "अनुमोदक@bank.example");
            Path file = directory.resolve("routing-table.txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);

            RoutingTable parsed = RoutingTableFormat.parse(file);

            assertThat(parsed.version().checker()).isEqualTo("अनुमोदक@bank.example");
        }
    }

    @Nested
    @DisplayName("emitting")
    class Emission {

        @Test
        @DisplayName("emit reproduces the checked-in canonical artefact byte for byte")
        void emitReproducesTheCanonicalArtefact() {
            // The expectation is a file written by hand, not this emitter's own output. That
            // is what makes the format a documented artefact rather than whatever the code
            // happens to produce today.
            assertThat(RoutingTableFormat.emit(approvedTable()))
                .isEqualTo(resource("routing-table-2027-1.txt"));
        }

        @Test
        @DisplayName("emission is LF-terminated and carries no timestamp, so two versions diff cleanly")
        void emissionIsDeterministic() {
            String first = RoutingTableFormat.emit(approvedTable());

            // Byte stability is a control requirement: what a checker approves is the diff
            // between two versions, and FR-903's replay compares artefacts byte for byte. A
            // CRLF or a generation timestamp would make every diff show every line.
            assertThat(first).doesNotContain("\r");
            assertThat(first).isEqualTo(RoutingTableFormat.emit(approvedTable()));
        }

        @Test
        @DisplayName("re-routing one driver changes exactly one emitted line")
        void aReroutingChangesOneRow() {
            RoutingTable before = approvedTable();
            RoutingTable after = before.reroute(
                RateDriver.ESG_LINKED,
                Mechanism.RESET,
                new RoutingTableVersion(
                    "RT-2027.2",
                    "Exposure Draft reading: an ESG margin ratchet is consideration for credit "
                        + "risk and adjusts the EIR.",
                    LocalDate.of(2027, 7, 1),
                    "policy.author@bank.example",
                    "policy.approver@bank.example",
                    LocalDate.of(2027, 6, 15)));

            String[] beforeLines = RoutingTableFormat.emit(before).split("\n", -1);
            String[] afterLines = RoutingTableFormat.emit(after).split("\n", -1);
            int changedRouteRows = 0;
            for (int index = 0; index < beforeLines.length; index++) {
                if (beforeLines[index].startsWith("route.")
                    && !beforeLines[index].equals(afterLines[index])) {
                    changedRouteRows++;
                }
            }

            assertThat(changedRouteRows)
                .as("the H2 2026 Exposure Draft response is one row and a new version, and the "
                    + "diff a checker approves must show exactly that")
                .isEqualTo(1);
            assertThat(afterLines).contains("route.ESG_LINKED = RESET");
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("the engine's own default table survives emit then parse")
        void theEngineDefaultRoundTrips() {
            RoutingTable table = RoutingTable.currentDefault();

            assertThat(RoutingTableFormat.parse(RoutingTableFormat.emit(table), SOURCE))
                .isEqualTo(table);
        }

        @Test
        @DisplayName("the canonical artefact survives parse then emit, unchanged")
        void theArtefactRoundTripsThroughTheParser() {
            String text = resource("routing-table-2027-1.txt");

            assertThat(RoutingTableFormat.emit(RoutingTableFormat.parse(text, SOURCE)))
                .isEqualTo(text);
        }

        @Test
        @DisplayName("a description carrying newlines, tabs, backslashes and rupees round trips")
        void awkwardTextRoundTrips() {
            // Emit escapes, parse unescapes. If the two disagreed, a policy description would
            // be silently truncated at the first newline — losing the sentence that records
            // why the version exists, which is the audit trail's whole content.
            RoutingTable table = new RoutingTable(
                new RoutingTableVersion(
                    "RT-2027.3",
                    "line one\nline two\twith a tab, a backslash \\, an = sign and ₹1,00,000",
                    LocalDate.of(2027, 4, 1),
                    "maker@bank.example",
                    "checker@bank.example",
                    LocalDate.of(2027, 3, 1)),
                specDefaultMapping());

            assertThat(RoutingTableFormat.parse(RoutingTableFormat.emit(table), SOURCE))
                .isEqualTo(table);
        }
    }

    @Nested
    @DisplayName("hostile input")
    class HostileInput {

        @Test
        @DisplayName("an unknown rate driver names the line and lists the known drivers")
        void anUnknownDriverIsRefused() {
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("route.ESG_LINKED", "route.ESG_LINK"));

            assertThat(fault.lineNumber()).as("route.ESG_LINK is line 10").isEqualTo(10);
            assertThat(fault).hasMessageContaining(SOURCE + ":10")
                .hasMessageContaining("unknown rate driver 'ESG_LINK'")
                .hasMessageContaining("Did you mean 'ESG_LINKED'?")
                .hasMessageContaining("route.ESG_LINK = CATCH_UP");
        }

        @Test
        @DisplayName("an unknown mechanism names the line and lists the known mechanisms")
        void anUnknownMechanismIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("route.CREDIT_RISK_MARKET = RESET",
                    "route.CREDIT_RISK_MARKET = RE_SOLVE"));

            assertThat(fault.lineNumber()).isEqualTo(8);
            assertThat(fault).hasMessageContaining("unknown mechanism 'RE_SOLVE'")
                .hasMessageContaining("CREDIT_RISK_MARKET")
                .hasMessageContaining("MODIFICATION_TEST");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"reset", "Catch_Up", "catch up"})
        @DisplayName("a mechanism in the wrong case is refused, with the constant it meant")
        void mechanismNamesAreCaseSensitive(String written) {
            // Accepting 'reset' would make the file's vocabulary a matter of taste and the
            // diff of an approved table ambiguous. Refused — but the message says what to type.
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("route.ESG_LINKED = CATCH_UP", "route.ESG_LINKED = " + written));

            assertThat(fault).hasMessageContaining("unknown mechanism").hasMessageContaining("Did you mean");
        }

        @Test
        @DisplayName("a missing driver row names the driver, not a Map the operator never saw")
        void aMissingRowNamesTheDriver() {
            // RoutingTable's constructor would also reject this. Its message is correct and
            // speaks about an unmapped key in a Map; the person who has to fix it is looking
            // at a text file with one line deleted.
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("route.ESG_LINKED = CATCH_UP\n", ""));

            assertThat(fault.lineNumber())
                .as("an omission has no line to point at, which is what makes it easy to miss")
                .isEqualTo(0);
            assertThat(fault).hasMessageContaining("ESG_LINKED")
                .hasMessageContaining("no route row")
                .hasMessageContaining("a defaulted routing is a silently wrong routing");
        }

        @Test
        @DisplayName("a file with only a comment is refused for its version block, before any row")
        void anEmptyFileIsRefused() {
            RoutingTableFormatException fault = faultFrom("# nothing but a comment\n");

            assertThat(fault).hasMessageContaining("version block is incomplete")
                .hasMessageContaining("version.id")
                .hasMessageContaining("version.checker");
            // The version block is checked first and reported alone. Listing eight absent
            // rows as well would bury the finding that matters: this is not an approved
            // table, so what it routes to is not yet a question.
            assertThat(fault).hasMessageNotContaining("no route row");
        }

        @Test
        @DisplayName("a UTF-8 byte order mark does not become part of the first key")
        void aByteOrderMarkIsTolerated() {
            // Notepad and PowerShell's Set-Content write one, and U+FEFF is not whitespace.
            // Without handling it the diagnostic is "unknown key 'version.id'. Known keys:
            // version.id, ..." — self-contradictory, because the character is invisible.
            RoutingTable parsed = RoutingTableFormat.parse('\uFEFF' + MINIMAL, SOURCE);

            assertThat(parsed.version().id()).isEqualTo("RT-2027.2");
        }

        @Test
        @DisplayName("a checker one escape away from the maker is still self-approval")
        void anEscapedWhitespaceCheckerIsStillSelfApproval() {
            // '\t' before the name decodes to a leading tab, which RoutingTableVersion then
            // strips — so this file names one identity twice. Caught at the line, not by the
            // record, because the operator has to be shown which line to change.
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("version.checker = checker@bank.example",
                    "version.checker = \\tmaker@bank.example"));

            assertThat(fault.lineNumber()).isEqualTo(5);
            assertThat(fault).hasMessageContaining("maker and checker are both 'maker@bank.example'");
        }

        @Test
        @DisplayName("a value that decodes to whitespace is empty, not a name")
        void anEscapedWhitespaceValueIsRefused() {
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("version.maker = maker@bank.example",
                    "version.maker = \\t"));

            assertThat(fault.lineNumber()).isEqualTo(4);
            assertThat(fault).hasMessageContaining("has an empty value");
        }

        @Test
        @DisplayName("a duplicate row for one driver names both lines rather than letting one win")
        void aDuplicateRowIsRefused() {
            // The two rows disagree here, but the refusal does not depend on that: 'the last
            // one wins' is a rule nobody applies in their head while reviewing a diff.
            RoutingTableFormatException fault = faultFrom(MINIMAL.replace(
                "route.NEGOTIATED = MODIFICATION_TEST",
                "route.NEGOTIATED = MODIFICATION_TEST\nroute.NEGOTIATED = DERECOGNITION"));

            assertThat(fault.lineNumber()).isEqualTo(15);
            assertThat(fault).hasMessageContaining("duplicate route for NEGOTIATED")
                .hasMessageContaining("already routed on line 14")
                .hasMessageContaining("one driver has exactly one mechanism");
        }

        @Test
        @DisplayName("a duplicate version key is refused even when the two agree")
        void aDuplicateVersionKeyIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("version.id = RT-2027.2", "version.id = RT-2027.2\nversion.id = RT-2027.2"));

            assertThat(fault.lineNumber()).isEqualTo(2);
            assertThat(fault).hasMessageContaining("duplicate key 'version.id'")
                .hasMessageContaining("already set on line 1");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "01/04/2027",
            "2027-4-1",
            "2027-02-30",
            "1 April 2027",
        })
        @DisplayName("a malformed or impossible date names the line and the expected form")
        void aMalformedDateIsRefused(String written) {
            // 2027-02-30 does not exist and 2027-4-1 is not ISO-8601. Coercing either would
            // move an effective date, and an effective date one month out selects the wrong
            // version in force for a whole close.
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("version.effectiveFrom = 2027-04-01",
                    "version.effectiveFrom = " + written));

            assertThat(fault.lineNumber()).isEqualTo(3);
            assertThat(fault).hasMessageContaining("is not an ISO-8601 date")
                .hasMessageContaining("yyyy-mm-dd")
                .hasMessageContaining(written);
        }

        @Test
        @DisplayName("an unknown key is refused rather than ignored: a typo is not an omission")
        void anUnknownKeyIsRefused() {
            // 'version.identifier' must not present itself as a missing 'version.id'. The
            // operator has to be told about the typo, not about its consequence.
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("version.id =", "version.identifier ="));

            assertThat(fault.lineNumber()).isEqualTo(1);
            assertThat(fault).hasMessageContaining("unknown key 'version.identifier'")
                .hasMessageContaining("route.<RATE_DRIVER>");
        }

        @Test
        @DisplayName("a missing version field names the field, because an unapproved table is not one")
        void aMissingVersionFieldIsRefused() {
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("version.checker = checker@bank.example\n", ""));

            assertThat(fault).hasMessageContaining("version.checker")
                .hasMessageContaining("not an approved table");
        }

        @Test
        @DisplayName("maker equal to checker is refused at the line, not at the record")
        void selfApprovalIsRefused() {
            // RoutingTableVersion enforces this too. Checked here as well so the message can
            // name the line to change and the line the maker is on.
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("checker@bank.example", "maker@bank.example"));

            assertThat(fault.lineNumber()).isEqualTo(5);
            assertThat(fault).hasMessageContaining("maker and checker are both 'maker@bank.example'")
                .hasMessageContaining("maker on line 4")
                .hasMessageContaining("is not approved");
        }

        @Test
        @DisplayName("a line with no '=' is refused")
        void aLineWithoutASeparatorIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("route.ESG_LINKED = CATCH_UP", "route.ESG_LINKED CATCH_UP"));

            assertThat(fault.lineNumber()).isEqualTo(10);
            assertThat(fault).hasMessageContaining("no '=' separator");
        }

        @Test
        @DisplayName("a blanked value is refused: an unwanted row is deleted, not emptied")
        void anEmptyValueIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("route.ESG_LINKED = CATCH_UP", "route.ESG_LINKED ="));

            assertThat(fault.lineNumber()).isEqualTo(10);
            assertThat(fault).hasMessageContaining("has an empty value");
        }

        @Test
        @DisplayName("an unknown escape is refused rather than passed through as a literal")
        void anUnknownEscapeIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("a minimal table, for the reader's own tests", "back\\slash"));

            assertThat(fault.lineNumber()).isEqualTo(2);
            assertThat(fault).hasMessageContaining("unknown escape '\\s'");
        }

        @Test
        @DisplayName("a dangling backslash is refused")
        void aDanglingBackslashIsRefused() {
            RoutingTableFormatException fault = faultFrom(
                MINIMAL.replace("a minimal table, for the reader's own tests", "trailing\\"));

            assertThat(fault.lineNumber()).isEqualTo(2);
            assertThat(fault).hasMessageContaining("dangling");
        }

        @Test
        @DisplayName("the refusal carries the source name, so a loader can report file:line itself")
        void theRefusalCarriesItsLocation() {
            RoutingTableFormatException fault =
                faultFrom(MINIMAL.replace("route.ESG_LINKED", "route.ESG_LINK"));

            assertThat(fault.sourceName()).isEqualTo(SOURCE);
            assertThat(fault.line()).isEqualTo("route.ESG_LINK = CATCH_UP");
        }
    }
}
