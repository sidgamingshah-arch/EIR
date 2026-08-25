package com.crisil.eir.policy.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * The format's round-trip law, over the space of tables rather than the two
 * checked-in artefacts.
 *
 * <p>{@code parse(emit(t))} must equal {@code t} for <em>every</em> approved table,
 * not only for the specification 6.1 mapping. That matters because the mapping this
 * engine ships is expected to change: the IASB's April 2026 tentative decision
 * reaches an Exposure Draft in H2 2026, and the whole point of ADR-0006 is that the
 * response is a new table version rather than a release. A format that round-trips
 * today's eight rows and drops one when {@code ESG_LINKED} moves to {@code RESET}
 * would fail at exactly the moment it is needed.
 *
 * <p>The generated mechanisms range over all five constants, {@code DERECOGNITION}
 * and {@code NONE} included. Those two are not routings the shipped table produces,
 * and this format deliberately does not narrow the domain {@code RoutingTable}
 * itself accepts — a materiality election routing {@code DISBURSEMENT_TIMING} to
 * {@code NONE} is a policy choice, not a malformed file.
 *
 * <p>Text is generated from an explicit alphabet rather than jqwik's default,
 * because the interesting characters are known and few: the {@code =} that must not
 * split a description twice, the {@code #} that is a comment marker only at the
 * start of a line, the four escapes, and a rupee sign to keep the file honest about
 * being UTF-8.
 *
 * <p>Named {@code ...PropertiesTest}: surefire's default includes are
 * {@code Test*}, {@code *Test}, {@code *Tests} and {@code *TestCase}, and the parent
 * POM sets no {@code <includes>}. Under a shorter name this class would compile, be
 * packaged, and never run — with nothing in the build output to say so.
 */
class RoutingTableFormatPropertiesTest {

    /** One valid approval trail; the first property varies the mapping, not the version. */
    private static final RoutingTableVersion VERSION = new RoutingTableVersion(
        "RT-PROP-1",
        "generated table, for the round-trip property",
        LocalDate.of(2027, 4, 1),
        "maker@bank.example",
        "checker@bank.example",
        LocalDate.of(2027, 3, 1));

    /** Where a generated table is said to have come from, for the error messages. */
    private static final String SOURCE = "<generated>";

    @Property(tries = 200)
    void anyTotalMappingSurvivesEmitThenParse(
            @ForAll("oneMechanismPerDriver") List<Mechanism> mechanisms) {
        RoutingTable table = new RoutingTable(VERSION, mappingOf(mechanisms));

        assertThat(RoutingTableFormat.parse(RoutingTableFormat.emit(table), SOURCE))
            .as("a routing table must survive a write and a read exactly, or a policy reload "
                + "silently changes the mapping it was meant to preserve")
            .isEqualTo(table);
    }

    @Property(tries = 200)
    void anyApprovalTrailSurvivesEmitThenParse(
            @ForAll("policyText") String id,
            @ForAll("policyText") String description,
            @ForAll @IntRange(min = 1, max = 3650) int daysFromApprovalToEffect) {
        LocalDate approvedOn = LocalDate.of(2027, 2, 26);
        RoutingTable table = new RoutingTable(
            new RoutingTableVersion(
                id,
                description,
                approvedOn.plusDays(daysFromApprovalToEffect),
                "maker@bank.example",
                "checker@bank.example",
                approvedOn),
            mappingOf(Collections.nCopies(RateDriver.values().length, Mechanism.RESET)));

        // The description is the audit trail's whole content — why this version exists, in the
        // policy's own words. A format that truncated it at the first newline or at the first
        // '=' would lose the sentence an auditor asks for and nothing would report the loss.
        assertThat(RoutingTableFormat.parse(RoutingTableFormat.emit(table), SOURCE))
            .isEqualTo(table);
    }

    /**
     * One mechanism per rate driver, sized from {@code RateDriver.values().length}.
     *
     * <p>Sized from the enum rather than written as {@code @Size(8)}: a ninth driver
     * is precisely the change ADR-0006 exists to absorb, and under a hard-coded 8
     * this property would fail with a bare {@code IndexOutOfBoundsException} in the
     * fixture instead of telling anyone what broke.
     */
    @Provide
    Arbitrary<List<Mechanism>> oneMechanismPerDriver() {
        return Arbitraries.of(Mechanism.values()).list().ofSize(RateDriver.values().length);
    }

    /**
     * Text as a policy editor produces it: prose, an {@code =}, a {@code #}, the four
     * escapes and a rupee sign.
     *
     * <p>Filtered to what {@code RoutingTableVersion} will accept — it strips its own
     * text fields and rejects a blank one, so an all-whitespace id is not a table
     * this format has to be able to represent.
     */
    @Provide
    Arbitrary<String> policyText() {
        return Arbitraries.strings()
            .withChars('a', 'e', 'i', 'o', 'u', 'B', 'Z', '5', '.', ' ', '=', '#', '\\', '\n',
                '\r', '\t', '₹', '—')
            .ofMinLength(1)
            .ofMaxLength(120)
            .filter(text -> !text.strip().isEmpty());
    }

    /** The generated mechanisms, positioned by {@link RateDriver} declaration order. */
    private static Map<RateDriver, Mechanism> mappingOf(List<Mechanism> mechanisms) {
        Map<RateDriver, Mechanism> mapping = new EnumMap<>(RateDriver.class);
        RateDriver[] drivers = RateDriver.values();
        for (int index = 0; index < drivers.length; index++) {
            mapping.put(drivers[index], mechanisms.get(index));
        }
        return mapping;
    }
}
