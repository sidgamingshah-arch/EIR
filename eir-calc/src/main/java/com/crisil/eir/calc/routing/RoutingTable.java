package com.crisil.eir.calc.routing;

import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The approved mapping from cash-flow driver to mechanism — the engine's routing
 * rule as data (calculation specification section 6.1, ADR-0006).
 *
 * <p>Two mechanisms are available when projected cash flows change, and they
 * produce materially different P&amp;L: a B5.4.5 {@link Mechanism#RESET} re-solves
 * the rate from the current carrying amount and books nothing, while a B5.4.6
 * {@link Mechanism#CATCH_UP} keeps the original rate, restates the carrying amount
 * and books the difference immediately. Reference cases 3 and 4 are the same
 * instrument in the same month: one yields a 627.42 charge, the other nothing.
 *
 * <p>Which applies is decided <em>here</em>, in an approved and versioned table,
 * and nowhere else. Build the switch, not the constant. Three reasons, all from
 * ADR-0006:
 *
 * <ul>
 *   <li><strong>ACPIR is silent.</strong> There is no Indian equivalent of B5.4.5
 *       or B5.4.6; the mechanics are adopted by election under the interpretive
 *       hierarchy, so the choice is the bank's and has to be defensible as its
 *       own accounting policy.
 *   <li><strong>The rule is moving.</strong> The IASB's April 2026 tentative
 *       decision would amend B5.4.5, with an Exposure Draft planned H2 2026. A
 *       table version absorbs that; a {@code switch} statement means a
 *       re-engineering event on a live ledger, under audit.
 *   <li><strong>Replay must stay correct across the change.</strong> Each event
 *       stores the {@link RoutingTableVersion#id()} that routed it, so a period
 *       closed under the old reading replays under the old reading.
 * </ul>
 *
 * <p>Every {@link RateDriver} constant must be mapped. A missing entry would have
 * to be resolved by a default, and a defaulted routing is a silently wrong one —
 * the same reason FR-504 makes the driver tag mandatory on the event rather than
 * inferring it. The constructor therefore rejects an incomplete map instead of
 * filling gaps, and copies what it is given so that the approved mapping cannot be
 * mutated behind an already-routed event's back.
 *
 * <p>Note what is <em>not</em> in this table: product type and observed rate
 * movement. Routing on product is wrong because the same product experiences both
 * mechanisms depending on why its flows moved; routing on the observation that a
 * rate moved is wrong because a renegotiated fixed-rate loan and an EBLR reset
 * look identical from there and are a modification and a reset respectively. See
 * {@link DefaultEventRouter} for the rate-type check that closes the second gap.
 *
 * @param version the approval trail; persisted on every event this table routes
 * @param mapping driver to mechanism, total over {@link RateDriver}
 */
public record RoutingTable(RoutingTableVersion version, Map<RateDriver, Mechanism> mapping) {

    /**
     * The baseline version shipped with the engine.
     *
     * <p>Its maker and checker are engine-baseline identifiers, not people. A
     * production deployment replaces this with {@link #ofSpecDefaults} under a
     * version bearing the bank's own maker, checker and impact preview — that
     * approval is the substance of the control, and the engine cannot supply it.
     */
    private static final RoutingTableVersion BASELINE_VERSION = new RoutingTableVersion(
        "RT-BASELINE-2026.1",
        "Calculation specification 03 section 6.1 baseline: the April 2026 IASB tentative decision "
            + "reading, under which re-estimations providing consideration for the time value of money "
            + "or for credit risk adjust the EIR and pre-determined adjustments route to a catch-up.",
        LocalDate.of(2026, 5, 1),
        "eir-engine-baseline",
        "accounting-policy-owner",
        LocalDate.of(2026, 4, 30));

    public RoutingTable {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mapping, "mapping");
        Map<RateDriver, Mechanism> copy = new EnumMap<>(RateDriver.class);
        for (Map.Entry<RateDriver, Mechanism> entry : mapping.entrySet()) {
            RateDriver driver = Objects.requireNonNull(entry.getKey(), "mapping key");
            copy.put(driver, Objects.requireNonNull(entry.getValue(), "mechanism for " + driver));
        }
        List<RateDriver> notRoutable = new ArrayList<>();
        for (Map.Entry<RateDriver, Mechanism> entry : copy.entrySet()) {
            if (!entry.getValue().isRoutable()) {
                notRoutable.add(entry.getKey());
            }
        }
        if (!notRoutable.isEmpty()) {
            // The companion to the totality check below, and the more dangerous of the two. A
            // table naming DERECOGNITION for a driver derecognises every event on that driver
            // without running the substantiality assessment — no 10% test, no qualitative
            // triggers, no evidence — and the assessment is the whole of the decision. Refused at
            // construction, so it is one refusal when the table is authored rather than a wrong
            // number per contract for as long as the version stays in force. See
            // Mechanism.isRoutable() for why the opinion lives on the mechanism.
            throw new IllegalArgumentException(
                "routing table " + version.id() + " routes " + notRoutable + " to a mechanism no"
                    + " routing may name; derecognition is the conclusion of the substantiality"
                    + " assessment, reached per modification through " + Mechanism.MODIFICATION_TEST
                    + ", never a treatment a driver carries");
        }
        List<RateDriver> missing = new ArrayList<>();
        for (RateDriver driver : RateDriver.values()) {
            if (!copy.containsKey(driver)) {
                missing.add(driver);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "routing table " + version.id() + " must map every driver; a defaulted routing is a "
                    + "silently wrong routing. Unmapped: " + missing);
        }
        mapping = Collections.unmodifiableMap(copy);
    }

    /**
     * The specification section 6.1 mapping under a caller-supplied approved
     * version.
     *
     * <p>This is how a bank adopts the shipped reading as its own: same mapping,
     * its own maker, checker and effective date. Deviating from the reading is a
     * different table, built through {@link #reroute} or constructed directly.
     */
    public static RoutingTable ofSpecDefaults(RoutingTableVersion version) {
        Map<RateDriver, Mechanism> mapping = new EnumMap<>(RateDriver.class);
        mapping.put(RateDriver.TIME_VALUE_OF_MONEY, Mechanism.RESET);
        mapping.put(RateDriver.CREDIT_RISK_MARKET, Mechanism.RESET);
        mapping.put(RateDriver.CREDIT_RATCHET_PREDETERMINED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.ESG_LINKED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.STEP_UP_PREDETERMINED, Mechanism.CATCH_UP);
        mapping.put(RateDriver.BEHAVIOURAL_ESTIMATE, Mechanism.CATCH_UP);
        mapping.put(RateDriver.DISBURSEMENT_TIMING, Mechanism.CATCH_UP);
        mapping.put(RateDriver.NEGOTIATED, Mechanism.MODIFICATION_TEST);
        return new RoutingTable(version, mapping);
    }

    /**
     * The engine's default table, carrying the specification section 6.1 mapping.
     *
     * <p>This encodes the <strong>April 2026 IASB tentative decision</strong>:
     * adjust the EIR for re-estimations providing consideration for the time value
     * of money or for credit risk. So benchmark movement
     * ({@link RateDriver#TIME_VALUE_OF_MONEY}) and market credit-spread repricing
     * ({@link RateDriver#CREDIT_RISK_MARKET}) reset the rate, while pre-determined
     * adjustments compensating for neither — ESG ratchets, credit ratchets,
     * step-ups — fall outside B5.4.5 and route to a B5.4.6 catch-up, as do the
     * entity's own revised behavioural estimates and tranche timing deviations.
     * {@link RateDriver#NEGOTIATED} runs the substantiality test rather than
     * either mechanism.
     *
     * <p><strong>An Exposure Draft is expected in H2 2026 and this reading may not
     * survive it.</strong> When the final wording lands, the correct response is a
     * <em>new version</em> of this table — approved, with a portfolio impact
     * preview — not an edit to this method. Editing it would re-route every
     * historical event that recorded this version's id and make closed periods
     * irreproducible. Firms' published manuals already diverge on the current
     * wording, so this mapping is also where the bank's auditor-facing house view
     * lives: it is an accounting policy choice that must be made, applied
     * consistently and disclosed.
     *
     * <p>The returned table's version carries engine-baseline maker and checker
     * identifiers. It is fit for computation and reference-case verification; it
     * is not a substitute for the bank's own approval, which enters through
     * {@link #ofSpecDefaults}.
     */
    public static RoutingTable currentDefault() {
        return ofSpecDefaults(BASELINE_VERSION);
    }

    /**
     * The mechanism this table maps {@code driver} to, before the rate-type check.
     *
     * <p>The lookup is total by construction, so a caller never has to supply a
     * fallback — and could not sensibly choose one. Routing also depends on the
     * instrument's rate type, which is why this is not the public entry point:
     * route through {@link EventRouter#route} instead.
     */
    public Mechanism mechanismFor(RateDriver driver) {
        Objects.requireNonNull(driver, "driver");
        Mechanism mechanism = mapping.get(driver);
        if (mechanism == null) {
            throw new IllegalStateException(
                "routing table " + version.id() + " has no mapping for " + driver);
        }
        return mechanism;
    }

    /**
     * A copy of this table with one driver re-routed, under a new version.
     *
     * <p>The new version is a required argument, not derived, because that is the
     * governed step: a change of reading is a maker–checker event with an impact
     * preview. Re-using the current version's id is rejected — it would leave two
     * different mappings claiming one identity, and every event routed by either
     * would be indistinguishable on replay.
     */
    public RoutingTable reroute(RateDriver driver, Mechanism mechanism, RoutingTableVersion newVersion) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(newVersion, "newVersion");
        if (newVersion.id().equals(version.id())) {
            throw new IllegalArgumentException(
                "a routing change is a new version, not an edit; version id " + version.id()
                    + " is already in use");
        }
        Map<RateDriver, Mechanism> revised = new EnumMap<>(mapping);
        revised.put(driver, mechanism);
        return new RoutingTable(newVersion, revised);
    }

    /** {@link RoutingTableVersion#isEffectiveOn} for this table's version. */
    public boolean isEffectiveOn(LocalDate asOf) {
        return version.isEffectiveOn(asOf);
    }
}
