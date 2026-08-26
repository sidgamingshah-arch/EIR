package com.crisil.eir.calc.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The routing table as approved, versioned data (specification 6.1, ADR-0006).
 *
 * <p>Two properties carry the weight. The mapping must be <strong>total</strong> over
 * {@link RateDriver}, because a missing entry would have to be resolved by a default
 * and a defaulted routing is a silently wrong one — the same reason FR-504 makes the
 * driver tag mandatory on the event rather than inferring it. And a change of reading
 * must be a <strong>new version</strong>, because every routed event stores the version
 * id that routed it and two different mappings claiming one identity would make every
 * event routed by either indistinguishable on replay.
 */
class RoutingTableTest {

    @ParameterizedTest
    @EnumSource(RateDriver.class)
    @DisplayName("every driver has a mapping in the default table")
    void everyDriverIsMapped(RateDriver driver) {
        // Not "most drivers", and not "the ones we have events for". Total, so a caller can
        // never be in the position of choosing a fallback — and could not sensibly choose one.
        assertThat(RoutingTable.currentDefault().mechanismFor(driver)).isNotNull();
        assertThat(RoutingTable.currentDefault().mapping()).containsKey(driver);
    }

    @ParameterizedTest(name = "{0} routes to {1}")
    @CsvSource({
        "TIME_VALUE_OF_MONEY,          RESET",
        "CREDIT_RISK_MARKET,           RESET",
        "CREDIT_RATCHET_PREDETERMINED, CATCH_UP",
        "ESG_LINKED,                   CATCH_UP",
        "STEP_UP_PREDETERMINED,        CATCH_UP",
        "BEHAVIOURAL_ESTIMATE,         CATCH_UP",
        "DISBURSEMENT_TIMING,          CATCH_UP",
        "NEGOTIATED,                   MODIFICATION_TEST",
    })
    @DisplayName("the default table is the specification 6.1 mapping, row for row")
    void specificationSixOneMapping(RateDriver driver, Mechanism mechanism) {
        // The April 2026 IASB tentative decision reading: re-estimations providing
        // consideration for the time value of money or for credit risk adjust the EIR, and
        // pre-determined adjustments compensating for neither route to a catch-up.
        assertThat(RoutingTable.currentDefault().mechanismFor(driver)).isEqualTo(mechanism);
    }

    @Test
    @DisplayName("the eight rows are the whole table: no driver is left over and none is invented")
    void theTableIsExactlyTheEightRows() {
        Map<RateDriver, Mechanism> mapping = RoutingTable.currentDefault().mapping();

        assertThat(mapping).hasSize(RateDriver.values().length);
        assertThat(mapping.keySet()).containsExactlyInAnyOrder(RateDriver.values());
        // Only three mechanisms appear. DERECOGNITION and NONE are outcomes of the
        // substantiality assessment and of a part-prepayment variant respectively, not
        // routings a driver tag can produce.
        assertThat(mapping.values()).allMatch(mechanism ->
            mechanism == Mechanism.RESET
                || mechanism == Mechanism.CATCH_UP
                || mechanism == Mechanism.MODIFICATION_TEST);
    }

    @Test
    @DisplayName("a table missing one driver is rejected: a defaulted routing is a silently wrong one")
    void anIncompleteTableIsRejected() {
        Map<RateDriver, Mechanism> incomplete = new EnumMap<>(RoutingTable.currentDefault().mapping());
        incomplete.remove(RateDriver.ESG_LINKED);

        assertThatThrownBy(() ->
            new RoutingTable(RoutingTable.currentDefault().version(), incomplete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must map every driver")
            .hasMessageContaining("a defaulted routing is a silently wrong routing")
            .hasMessageContaining("ESG_LINKED");
    }

    @Test
    @DisplayName("an almost-empty table is rejected and names every driver it is missing")
    void aSingleRowTableIsRejected() {
        Map<RateDriver, Mechanism> oneRow = new EnumMap<>(RateDriver.class);
        oneRow.put(RateDriver.TIME_VALUE_OF_MONEY, Mechanism.RESET);

        assertThatThrownBy(() -> new RoutingTable(RoutingTable.currentDefault().version(), oneRow))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CREDIT_RISK_MARKET")
            .hasMessageContaining("NEGOTIATED")
            .hasMessageContaining("BEHAVIOURAL_ESTIMATE");
    }

    @Test
    @DisplayName("a null mechanism for a driver is rejected as loudly as a missing one")
    void aNullMechanismIsRejected() {
        // A HashMap will hold a null value where an EnumMap will not, so this is reachable
        // from a caller assembling a mapping from configuration.
        Map<RateDriver, Mechanism> withNull = new HashMap<>(RoutingTable.currentDefault().mapping());
        withNull.put(RateDriver.NEGOTIATED, null);

        assertThatThrownBy(() -> new RoutingTable(RoutingTable.currentDefault().version(), withNull))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("NEGOTIATED");
    }

    @Test
    @DisplayName("the approved mapping cannot be mutated behind an already-routed event's back")
    void theMappingIsCopiedAndUnmodifiable() {
        Map<RateDriver, Mechanism> supplied = new EnumMap<>(RoutingTable.currentDefault().mapping());
        RoutingTable table = new RoutingTable(RoutingTable.currentDefault().version(), supplied);

        supplied.put(RateDriver.ESG_LINKED, Mechanism.RESET);
        assertThat(table.mechanismFor(RateDriver.ESG_LINKED)).isEqualTo(Mechanism.CATCH_UP);

        assertThatThrownBy(() -> table.mapping().put(RateDriver.ESG_LINKED, Mechanism.RESET))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a bank adopts the shipped reading under its own approval, same mapping")
    void ofSpecDefaultsCarriesTheCallersVersion() {
        RoutingTableVersion bankVersion = new RoutingTableVersion(
            "BANK-RT-2027.1",
            "Bank's adoption of the specification 6.1 reading, unchanged.",
            LocalDate.of(2027, 4, 1),
            "group-accounting-policy",
            "chief-accountant",
            LocalDate.of(2027, 3, 20));

        RoutingTable adopted = RoutingTable.ofSpecDefaults(bankVersion);

        assertThat(adopted.version().id()).isEqualTo("BANK-RT-2027.1");
        assertThat(adopted.mapping()).isEqualTo(RoutingTable.currentDefault().mapping());
        // The engine's baseline carries engine identifiers and is not a substitute for the
        // bank's approval — which is the substance of the control.
        assertThat(RoutingTable.currentDefault().version().maker()).isEqualTo("eir-engine-baseline");
    }

    @Test
    @DisplayName("a re-routing is a new version, and reusing the current id is rejected")
    void reroutingRequiresANewVersion() {
        RoutingTable baseline = RoutingTable.currentDefault();

        assertThatThrownBy(() -> baseline.reroute(
            RateDriver.ESG_LINKED, Mechanism.RESET, baseline.version()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a routing change is a new version, not an edit");
    }

    @Test
    @DisplayName("a re-routing leaves the original table untouched")
    void reroutingIsNonDestructive() {
        RoutingTable baseline = RoutingTable.currentDefault();
        RoutingTable revised = baseline.reroute(RateDriver.ESG_LINKED, Mechanism.RESET, exposureDraft());

        assertThat(revised.mechanismFor(RateDriver.ESG_LINKED)).isEqualTo(Mechanism.RESET);
        // Editing the approved version in place would destroy the only record of what was in
        // force when a closed period was computed.
        assertThat(baseline.mechanismFor(RateDriver.ESG_LINKED)).isEqualTo(Mechanism.CATCH_UP);
        assertThat(revised.version().id()).isNotEqualTo(baseline.version().id());
        // Every other row survives the change: a version is a delta on one reading, not a
        // rebuild of the policy.
        for (RateDriver driver : RateDriver.values()) {
            if (driver != RateDriver.ESG_LINKED) {
                assertThat(revised.mechanismFor(driver)).isEqualTo(baseline.mechanismFor(driver));
            }
        }
    }

    @Test
    @DisplayName("maker and checker must differ: a table approved by its own maker is not approved")
    void fourEyesIsEnforced() {
        assertThatThrownBy(() -> new RoutingTableVersion(
            "RT-SELF-APPROVED", "no", LocalDate.of(2027, 1, 1),
            "same-person", "same-person", LocalDate.of(2027, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maker and checker must differ");
    }

    @Test
    @DisplayName("a case or whitespace variant of the maker is still the maker")
    void fourEyesIsCaseAndWhitespaceInsensitive() {
        // The defect this pins. The guard was a raw equals(), so every one of these constructed
        // successfully — a routing table approved by its own maker under a different
        // capitalisation. That matters more here than almost anywhere: ADR-0006 makes the routing
        // table the artefact that changes how every event is treated without a code deploy, and
        // four eyes is the only thing standing in front of it.
        //
        // It was also inconsistent with the database that stores the row. The DDL's four-eyes
        // constraint compares lower(btrim(...)), so PostgreSQL rejects exactly the rows this type
        // used to accept — a version could be in force in the engine and unstorable.
        for (String variant : new String[] {
            "Same-Person", "SAME-PERSON", "same-person ", " same-person"}) {
            assertThatThrownBy(() -> new RoutingTableVersion(
                "RT-CASE-VARIANT", "no", LocalDate.of(2027, 1, 1),
                "same-person", variant, LocalDate.of(2027, 1, 1)))
                .as("checker '%s' against maker 'same-person'", variant)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maker and checker must differ");
        }

        // And two genuinely different people still construct.
        assertThatCode(() -> new RoutingTableVersion(
            "RT-OK", "yes", LocalDate.of(2027, 1, 1),
            "product.control", "accounting.policy.owner", LocalDate.of(2027, 1, 1)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unattributable version is rejected: blank id, description, maker or checker")
    void versionFieldsAreMandatory() {
        assertThatThrownBy(() -> new RoutingTableVersion(
            "  ", "d", LocalDate.of(2027, 1, 1), "m", "c", LocalDate.of(2027, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id must not be blank");
        assertThatThrownBy(() -> new RoutingTableVersion(
            "RT", "  ", LocalDate.of(2027, 1, 1), "m", "c", LocalDate.of(2027, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description must not be blank");
        assertThatThrownBy(() -> new RoutingTableVersion(
            "RT", "d", LocalDate.of(2027, 1, 1), " ", "c", LocalDate.of(2027, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maker must not be blank");
    }

    @Test
    @DisplayName("effective dating selects new routings only, and a replay never re-selects by date")
    void effectiveDating() {
        RoutingTable baseline = RoutingTable.currentDefault();
        LocalDate effectiveFrom = baseline.version().effectiveFrom();

        assertThat(baseline.isEffectiveOn(effectiveFrom)).isTrue();
        assertThat(baseline.isEffectiveOn(effectiveFrom.plusYears(5))).isTrue();
        assertThat(baseline.isEffectiveOn(effectiveFrom.minusDays(1))).isFalse();
    }

    @Test
    @DisplayName("a retrospective approval is flagged, not rejected")
    void retrospectiveApprovalIsVisible() {
        // A baseline approved part-way through a parallel-run build legitimately takes effect
        // from the start of that build. It is permitted because events already routed keep
        // their own version id and continue to replay under it, so a retrospective version
        // can only ever be the basis of an explicit restatement — never a silent re-route.
        RoutingTableVersion retrospective = new RoutingTableVersion(
            "RT-PARALLEL-RUN", "Approved mid-build, effective from build start.",
            LocalDate.of(2027, 1, 1), "maker", "checker", LocalDate.of(2027, 6, 30));

        assertThat(retrospective.isRetrospective()).isTrue();
        assertThat(RoutingTable.currentDefault().version().isRetrospective()).isFalse();
    }

    @Test
    @DisplayName("the baseline version identifies itself and states which reading it encodes")
    void theBaselineVersionIsSelfDescribing() {
        RoutingTableVersion version = RoutingTable.currentDefault().version();

        // The auditor's question is which reading was in force, so the version says so in
        // the policy's own words rather than leaving it to be inferred from the mapping.
        assertThat(version.id()).isEqualTo("RT-BASELINE-2026.1");
        assertThat(version.description()).contains("April 2026 IASB tentative decision");
        assertThat(version.maker()).isNotEqualTo(version.checker());
    }

    private static RoutingTableVersion exposureDraft() {
        return new RoutingTableVersion(
            "RT-ED-2027.1",
            "Post-Exposure-Draft reading: ESG ratchets are consideration for credit risk and "
                + "therefore adjust the EIR.",
            LocalDate.of(2027, 4, 1),
            "group-accounting-policy",
            "chief-accountant",
            LocalDate.of(2027, 3, 15));
    }
}
