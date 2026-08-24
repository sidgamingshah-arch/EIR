package com.crisil.eir.calc.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Routing one event: the table lookup, the one check the table cannot express, and the
 * version id that makes replay survive a change of reading (specification 6.2,
 * ADR-0006).
 *
 * <p>The trap row is the most important thing tested here. A fixed-rate loan whose rate
 * is renegotiated and a floating-rate loan whose benchmark moved are
 * <em>indistinguishable</em> from the observation that the rate changed, and they are a
 * modification and a reset respectively. Routed on the observation, the renegotiation
 * gets a reset — no catch-up, no substantiality test, no derecognition assessment — and
 * the entire modification question disappears from the ledger without anyone declining
 * it. No invariant detects that after the fact, which is why the discrimination has to
 * be structural: the driver tag is mandatory on the event and the rate type is read from
 * the instrument.
 */
class EventRouterTest {

    private final EventRouter router = DefaultEventRouter.INSTANCE;

    private final RoutingTable baseline = RoutingTable.currentDefault();

    @ParameterizedTest(name = "FIXED + {0} routes to MODIFICATION_TEST, not RESET")
    @EnumSource(value = RateDriver.class, names = {"TIME_VALUE_OF_MONEY", "CREDIT_RISK_MARKET"})
    @DisplayName("THE TRAP ROW: a market movement on a fixed-rate instrument is a modification")
    void fixedRateMarketMovementIsAModification(RateDriver driver) {
        RoutingDecision decision = router.route(driver, RateType.FIXED, baseline);

        // B5.4.5 covers instruments that reprice off a market benchmark *by their own
        // terms*. A fixed-rate instrument has no such term, so this combination cannot have
        // arisen from the contract — it can only have arisen because the parties
        // renegotiated. It is a modification and it runs the substantiality assessment.
        assertThat(decision.mechanism()).isEqualTo(Mechanism.MODIFICATION_TEST);
        assertThat(decision.mechanism()).isNotEqualTo(Mechanism.RESET);
        assertThat(decision.overriddenByRateTypeCheck()).isTrue();
        assertThat(decision.requiresModificationTest()).isTrue();
        assertThat(decision.resolvesRate()).isFalse();

        // The table still says RESET for this driver. The override is recorded as an
        // override rather than dressed up as the table's answer, because the auditor's
        // question is why, and "the row was not applicable to this instrument" is the why.
        assertThat(baseline.mechanismFor(driver)).isEqualTo(Mechanism.RESET);
        assertThat(decision.rationale()).contains("cannot experience by its own terms");
        assertThat(decision.rationale()).contains("overriding routing table");
        assertThat(decision.rationale()).contains("never the "
            + "observation that the rate moved (FR-507)");
    }

    @ParameterizedTest(name = "FLOATING + {0} routes to RESET")
    @EnumSource(value = RateDriver.class, names = {"TIME_VALUE_OF_MONEY", "CREDIT_RISK_MARKET"})
    @DisplayName("the same driver on a FLOATING instrument does route to RESET")
    void floatingRateMarketMovementResets(RateDriver driver) {
        RoutingDecision decision = router.route(driver, RateType.FLOATING, baseline);

        // The half of the pair that makes the previous test mean something. A floating
        // instrument repricing off its benchmark is doing exactly what its terms provide for,
        // so the check never fires and the table's row stands.
        assertThat(decision.mechanism()).isEqualTo(Mechanism.RESET);
        assertThat(decision.overriddenByRateTypeCheck()).isFalse();
        assertThat(decision.resolvesRate()).isTrue();
        assertThat(decision.requiresModificationTest()).isFalse();
        assertThat(decision.rationale()).contains("the rate-type check does not apply");
    }

    @Test
    @DisplayName("one instrument, one observation — 'the rate moved' — two treatments")
    void theTrapRowIsThePairOrNothing() {
        RoutingDecision renegotiatedFixed =
            router.route(RateDriver.TIME_VALUE_OF_MONEY, RateType.FIXED, baseline);
        RoutingDecision benchmarkReset =
            router.route(RateDriver.TIME_VALUE_OF_MONEY, RateType.FLOATING, baseline);

        // Same driver tag, same table, same version. The only difference is the instrument's
        // rate type, and it is the whole difference between a modification assessment and a
        // silent re-solve.
        assertThat(renegotiatedFixed.driver()).isEqualTo(benchmarkReset.driver());
        assertThat(renegotiatedFixed.routingTableVersionId())
            .isEqualTo(benchmarkReset.routingTableVersionId());
        assertThat(renegotiatedFixed.mechanism()).isNotEqualTo(benchmarkReset.mechanism());
        assertThat(renegotiatedFixed.resolvesRate()).isFalse();
        assertThat(benchmarkReset.resolvesRate()).isTrue();
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}, overridden={3}")
    @CsvSource({
        "FIXED,    TIME_VALUE_OF_MONEY,          MODIFICATION_TEST, true",
        "FIXED,    CREDIT_RISK_MARKET,           MODIFICATION_TEST, true",
        "FIXED,    CREDIT_RATCHET_PREDETERMINED, CATCH_UP,          false",
        "FIXED,    ESG_LINKED,                   CATCH_UP,          false",
        "FIXED,    STEP_UP_PREDETERMINED,        CATCH_UP,          false",
        "FIXED,    BEHAVIOURAL_ESTIMATE,         CATCH_UP,          false",
        "FIXED,    DISBURSEMENT_TIMING,          CATCH_UP,          false",
        "FIXED,    NEGOTIATED,                   MODIFICATION_TEST, false",
        "FLOATING, TIME_VALUE_OF_MONEY,          RESET,             false",
        "FLOATING, CREDIT_RISK_MARKET,           RESET,             false",
        "FLOATING, CREDIT_RATCHET_PREDETERMINED, CATCH_UP,          false",
        "FLOATING, ESG_LINKED,                   CATCH_UP,          false",
        "FLOATING, STEP_UP_PREDETERMINED,        CATCH_UP,          false",
        "FLOATING, BEHAVIOURAL_ESTIMATE,         CATCH_UP,          false",
        "FLOATING, DISBURSEMENT_TIMING,          CATCH_UP,          false",
        "FLOATING, NEGOTIATED,                   MODIFICATION_TEST, false",
    })
    @DisplayName("every rate-type and driver combination routes as the specification says")
    void everyCombination(RateType rateType, RateDriver driver, Mechanism mechanism, boolean overridden) {
        RoutingDecision decision = router.route(driver, rateType, baseline);

        assertThat(decision.mechanism()).isEqualTo(mechanism);
        assertThat(decision.overriddenByRateTypeCheck()).isEqualTo(overridden);
        assertThat(decision.rateType()).isEqualTo(rateType);
        assertThat(decision.driver()).isEqualTo(driver);
    }

    @Test
    @DisplayName("the check fires only for market-movement drivers on fixed-rate instruments")
    void theOverrideIsExactlyTheMarketMovementDrivers() {
        List<RateDriver> overridden = new ArrayList<>();
        for (RateDriver driver : RateDriver.values()) {
            if (router.route(driver, RateType.FIXED, baseline).overriddenByRateTypeCheck()) {
                overridden.add(driver);
            }
        }

        assertThat(overridden)
            .containsExactly(RateDriver.TIME_VALUE_OF_MONEY, RateDriver.CREDIT_RISK_MARKET);
        assertThat(overridden).allMatch(RateDriver::isMarketMovement);
        // And no floating-rate event is ever converted into a modification by the check.
        for (RateDriver driver : RateDriver.values()) {
            assertThat(router.route(driver, RateType.FLOATING, baseline).overriddenByRateTypeCheck())
                .isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(RateDriver.class)
    @DisplayName("every decision carries the routing table version id that produced it")
    void everyDecisionNamesItsVersion(RateDriver driver) {
        for (RateType rateType : RateType.values()) {
            RoutingDecision decision = router.route(driver, rateType, baseline);

            // This is the field that makes replay correct across a change of reading: the
            // event names the version that routed it rather than inviting the engine to look
            // the routing up again with today's table. A replay that re-derived routing from
            // current configuration would silently apply today's logic to yesterday's events,
            // and DT-1 would either fail or — worse — pass while being wrong.
            assertThat(decision.routingTableVersionId()).isEqualTo(baseline.version().id());
            assertThat(decision.routingTableVersionId()).isNotBlank();
            assertThat(decision.rationale()).contains(baseline.version().id());
        }
    }

    @Test
    @DisplayName("ADR-0006: a different table version gives a different mechanism for one driver")
    void aStandardsChangeIsAPolicyVersionNotARebuild() {
        // The demonstration. When the H2 2026 Exposure Draft lands and ESG ratchets become
        // consideration for credit risk, the response is a new approved version of the table
        // with an impact preview — not a code change, a regression cycle and a release.
        RoutingTable afterExposureDraft = baseline.reroute(
            RateDriver.ESG_LINKED,
            Mechanism.RESET,
            new RoutingTableVersion(
                "RT-ED-2027.1",
                "Post-Exposure-Draft reading: an ESG margin ratchet is consideration for credit "
                    + "risk and therefore adjusts the EIR.",
                LocalDate.of(2027, 4, 1),
                "group-accounting-policy",
                "chief-accountant",
                LocalDate.of(2027, 3, 15)));

        RoutingDecision underOldReading = router.route(RateDriver.ESG_LINKED, RateType.FLOATING, baseline);
        RoutingDecision underNewReading =
            router.route(RateDriver.ESG_LINKED, RateType.FLOATING, afterExposureDraft);

        // Same driver, same instrument, same router, same code. Different mechanism, and
        // therefore materially different P&L: a catch-up recognises the restatement
        // immediately, a reset recognises nothing.
        assertThat(underOldReading.mechanism()).isEqualTo(Mechanism.CATCH_UP);
        assertThat(underNewReading.mechanism()).isEqualTo(Mechanism.RESET);
        assertThat(underOldReading.resolvesRate()).isFalse();
        assertThat(underNewReading.resolvesRate()).isTrue();

        // And each decision records its own version, which is what lets the period closed
        // under the old reading replay under the old reading. Both versions remain readable
        // side by side; neither overwrote the other.
        assertThat(underOldReading.routingTableVersionId()).isEqualTo("RT-BASELINE-2026.1");
        assertThat(underNewReading.routingTableVersionId()).isEqualTo("RT-ED-2027.1");
        assertThat(underOldReading.routingTableVersionId())
            .isNotEqualTo(underNewReading.routingTableVersionId());
        assertThat(underOldReading.rationale()).contains("RT-BASELINE-2026.1");
        assertThat(underNewReading.rationale()).contains("RT-ED-2027.1");

        // Replaying the old event means routing it through the version it named, which still
        // gives the old answer however many versions have been approved since.
        assertThat(router.route(RateDriver.ESG_LINKED, RateType.FLOATING, baseline).mechanism())
            .isEqualTo(underOldReading.mechanism());
    }

    @Test
    @DisplayName("a table version can also move a driver the other way, and the trap row survives it")
    void therateTypeCheckIsIndependentOfTheTableVersion() {
        // A version that routes benchmark movement to a catch-up instead — a different house
        // view, and a permissible one. The rate-type check still fires on a fixed-rate
        // instrument, because its premise is the instrument's own terms rather than any
        // reading of B5.4.5.
        RoutingTable houseView = baseline.reroute(
            RateDriver.TIME_VALUE_OF_MONEY,
            Mechanism.CATCH_UP,
            new RoutingTableVersion(
                "RT-HOUSE-VIEW-2027.1",
                "House view: benchmark movement is a revision of estimated cash flows.",
                LocalDate.of(2027, 4, 1), "maker", "checker", LocalDate.of(2027, 3, 1)));

        assertThat(router.route(RateDriver.TIME_VALUE_OF_MONEY, RateType.FLOATING, houseView).mechanism())
            .isEqualTo(Mechanism.CATCH_UP);
        RoutingDecision fixed = router.route(RateDriver.TIME_VALUE_OF_MONEY, RateType.FIXED, houseView);
        assertThat(fixed.mechanism()).isEqualTo(Mechanism.MODIFICATION_TEST);
        assertThat(fixed.overriddenByRateTypeCheck()).isTrue();
        assertThat(fixed.rationale()).contains("RT-HOUSE-VIEW-2027.1");
    }

    @Test
    @DisplayName("the router holds no mapping of its own, so every row stays policy-changeable")
    void theRouterAddsNoHiddenRows() {
        // If a driver's treatment were named in the router, an approved policy version could
        // not change it — which is the thing ADR-0006 forbids. Re-routing every driver to one
        // mechanism must therefore move every non-overridden decision to that mechanism.
        Map<RateDriver, Mechanism> mapping = new EnumMap<>(RateDriver.class);
        for (RateDriver driver : RateDriver.values()) {
            mapping.put(driver, Mechanism.CATCH_UP);
        }
        RoutingTable allCatchUp = new RoutingTable(
            new RoutingTableVersion(
                "RT-ALL-CATCH-UP", "Every driver to a catch-up.",
                LocalDate.of(2027, 1, 1), "maker", "checker", LocalDate.of(2026, 12, 1)),
            mapping);

        for (RateDriver driver : RateDriver.values()) {
            assertThat(router.route(driver, RateType.FLOATING, allCatchUp).mechanism())
                .isEqualTo(Mechanism.CATCH_UP);
        }
        // The one exception is the rate-type check, which is not a row and is not meant to be
        // policy-changeable.
        assertThat(router.route(RateDriver.TIME_VALUE_OF_MONEY, RateType.FIXED, allCatchUp).mechanism())
            .isEqualTo(Mechanism.MODIFICATION_TEST);
    }

    @Test
    @DisplayName("routing is pure: same three arguments, same decision, forever")
    void routingIsDeterministic() {
        for (RateDriver driver : RateDriver.values()) {
            for (RateType rateType : RateType.values()) {
                RoutingDecision first = router.route(driver, rateType, baseline);
                RoutingDecision second = router.route(driver, rateType, baseline);
                assertThat(second).isEqualTo(first);
            }
        }
        // Stateless, so one instance serves every caller — and a second instance agrees.
        assertThat(new DefaultEventRouter().route(RateDriver.NEGOTIATED, RateType.FIXED, baseline))
            .isEqualTo(router.route(RateDriver.NEGOTIATED, RateType.FIXED, baseline));
    }

    @Test
    @DisplayName("the table is a parameter, not a field, so a replay can pass the old version")
    void theTableIsAParameter() {
        // An implementation that captured a table at construction would make historical
        // replay depend on deployment configuration, which is precisely the failure ADR-0006
        // exists to prevent. The interface takes the table per call.
        RoutingTable other = baseline.reroute(RateDriver.ESG_LINKED, Mechanism.RESET,
            new RoutingTableVersion("RT-OTHER", "d", LocalDate.of(2027, 1, 1), "m", "c",
                LocalDate.of(2026, 12, 1)));

        assertThat(router.route(RateDriver.ESG_LINKED, RateType.FIXED, baseline).mechanism())
            .isEqualTo(Mechanism.CATCH_UP);
        assertThat(router.route(RateDriver.ESG_LINKED, RateType.FIXED, other).mechanism())
            .isEqualTo(Mechanism.RESET);
    }

    @Test
    @DisplayName("a missing driver tag is rejected rather than defaulted")
    void anUntaggedEventIsRejected() {
        // FR-504. The check never rescues a missing driver: an untagged event is refused at
        // the API boundary, because a defaulted driver is a silently wrong routing.
        assertThatThrownBy(() -> router.route(null, RateType.FIXED, baseline))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("driver");
        assertThatThrownBy(() -> router.route(RateDriver.NEGOTIATED, null, baseline))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("rateType");
        assertThatThrownBy(() -> router.route(RateDriver.NEGOTIATED, RateType.FIXED, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("table");
    }

    @Test
    @DisplayName("a decision whose routing cannot be attributed to a version cannot be constructed")
    void aDecisionMustNameItsVersion() {
        assertThatThrownBy(() -> new RoutingDecision(
            RateDriver.NEGOTIATED, RateType.FIXED, Mechanism.MODIFICATION_TEST, "   ", "because", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be replayed");
        assertThatThrownBy(() -> new RoutingDecision(
            RateDriver.NEGOTIATED, RateType.FIXED, Mechanism.MODIFICATION_TEST, "RT-1", "  ", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rationale must not be blank");
    }

    @Test
    @DisplayName("only a RESET changes the persisted rate, which is CU-1's precondition")
    void onlyResetResolvesTheRate() {
        for (RateDriver driver : RateDriver.values()) {
            for (RateType rateType : RateType.values()) {
                RoutingDecision decision = router.route(driver, rateType, baseline);
                assertThat(decision.resolvesRate())
                    .isEqualTo(decision.mechanism() == Mechanism.RESET);
            }
        }
        // Its complement is what CU-1 asserts: for a catch-up the rate before and after must
        // be bit-identical, which is the cheapest detector of a catch-up quietly discounted at
        // a re-solved rate.
        assertThat(router.route(RateDriver.BEHAVIOURAL_ESTIMATE, RateType.FIXED, baseline).resolvesRate())
            .isFalse();
    }
}
