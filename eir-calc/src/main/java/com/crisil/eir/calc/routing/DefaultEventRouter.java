package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.util.Objects;

/**
 * Table lookup, plus the one check the table cannot express.
 *
 * <p>The lookup is the whole of the routing rule (calculation specification
 * section 6.1) and lives in {@link RoutingTable}, which is approved, versioned
 * data. This class deliberately contains no mapping of its own — a driver named
 * here would be a driver whose treatment could not be changed by an approved
 * policy version, which is the thing ADR-0006 forbids.
 *
 * <h2>The rate-type check — specification section 6.2, last row</h2>
 *
 * <p>A fixed-rate loan whose rate is renegotiated is <strong>not</strong> a
 * B5.4.5 reset. B5.4.5 covers instruments that reprice off a market benchmark
 * <em>by their own terms</em>. A fixed-rate instrument has no such term, so the
 * combination "fixed rate" and "the rate moved with the market" cannot arise from
 * the contract: it can only have arisen because the parties renegotiated. It is a
 * modification and it runs the substantiality assessment (5.4.3, section 6.4).
 *
 * <p>Hence the check: where {@link RateType#FIXED} meets a driver for which
 * {@link RateDriver#isMarketMovement()} holds, the table row is not applicable to
 * this instrument and the event routes to {@link Mechanism#MODIFICATION_TEST} with
 * {@link RoutingDecision#overriddenByRateTypeCheck()} set. The check fires
 * whatever the row says, in this table version and in every future one, because
 * its premise is the instrument's own terms rather than any reading of B5.4.5.
 *
 * <p><strong>Treatment keys off the instrument's rate type and the event's driver
 * tag — never off the observation that the rate moved</strong> (FR-507). This is
 * the highest-impact error available in this domain, and it is attractive because
 * an EBLR reset and a renegotiated fixed rate are indistinguishable from the
 * observation: both are "the rate changed". Routed on the observation, the
 * renegotiation gets a reset — no catch-up, no substantiality test, no
 * derecognition assessment — and the entire modification question disappears from
 * the ledger without anyone declining it. There is no invariant that detects this
 * after the fact, which is why the discrimination is structural: the driver tag is
 * mandatory on the event and the rate type is read from the instrument.
 *
 * <p>Note what the check does <em>not</em> do. It never converts a
 * {@link RateType#FLOATING} event into a modification, because a floating
 * instrument repricing off its benchmark is doing exactly what its terms provide
 * for. And it never rescues a missing driver tag: an untagged event is rejected at
 * the API boundary (FR-504) rather than defaulted here.
 */
public final class DefaultEventRouter implements EventRouter {

    /** Stateless and deterministic, so one instance serves every caller. */
    public static final DefaultEventRouter INSTANCE = new DefaultEventRouter();

    @Override
    public RoutingDecision route(RateDriver driver, RateType rateType, RoutingTable table) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(rateType, "rateType");
        Objects.requireNonNull(table, "table");

        Mechanism tabled = table.mechanismFor(driver);
        String versionId = table.version().id();

        if (rateType == RateType.FIXED && driver.isMarketMovement()) {
            String rationale = "driver " + driver + " is a market movement, which a " + RateType.FIXED
                + "-rate instrument cannot experience by its own terms: B5.4.5 covers instruments that "
                + "reprice off a benchmark contractually, so this combination can only have arisen from "
                + "renegotiation. Routed to " + Mechanism.MODIFICATION_TEST
                + " (5.4.3) by the rate-type check, overriding routing table " + versionId
                + " which maps " + driver + " to " + tabled
                + ". Treatment follows the instrument's rate type and the event's driver tag, never the "
                + "observation that the rate moved (FR-507).";
            return new RoutingDecision(
                driver, rateType, Mechanism.MODIFICATION_TEST, versionId, rationale, true);
        }

        String rationale = "routing table " + versionId + " maps driver " + driver + " to " + tabled
            + "; the instrument is " + rateType + "-rate, so the rate-type check does not apply.";
        return new RoutingDecision(driver, rateType, tabled, versionId, rationale, false);
    }
}
