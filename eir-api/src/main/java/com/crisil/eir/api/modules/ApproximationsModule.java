package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.approximations.ApproximationRegister;
import com.crisil.eir.api.modules.approximations.ApproximationSources;
import com.crisil.eir.api.modules.approximations.DisclosureSources;
import com.crisil.eir.api.modules.approximations.EngineSources;
import com.crisil.eir.api.modules.approximations.IndAs107Extract;
import com.sun.net.httpserver.HttpExchange;
import java.util.Objects;

/**
 * GET /api/reports/approximations — FR-809, the incidence of every shortcut in force — and the Ind
 * AS 107 disclosure extract of FR-806.
 *
 * <p>06 § 7 singles the first one out, and the sentence is the specification for this module:
 * "{@code /reports/approximations} deserves note. It reports Tier 3 populations with
 * equivalence-test dates, ACPIR 51 contractual fallbacks with justifications, pool-level
 * measurement with back-test variances, and revolving approximations. Making the shortcuts
 * <i>visible</i> is what keeps them defensible — an undocumented approximation drifting quietly
 * across a portfolio is the failure this endpoint exists to prevent."
 *
 * <p><b>The register is complete or explicitly incomplete, never quietly either.</b> An
 * approximation in force that this report omitted would be worse than no report, because the
 * report reads as coverage. So every category the engine cannot populate comes back as a named gap
 * — {@code NOT_AVAILABLE} with the reason, the class involved and the artefact that would fill it
 * — rather than as an empty list that reads as "none in force". The whole of
 * {@code ApproximationSources}, {@code CategoryReturn.Status} and {@code EngineSources} exists for
 * that one distinction; see {@link ApproximationRegister}.
 *
 * <p><b>What this endpoint reports on this engine, today.</b> Four named gaps and no rows. That is
 * not a stub: it is the true state of the engine, published. {@code EquivalenceTestGate} has no
 * caller so FR-411 and FR-412's Tier 3 permission is never sought; every contract takes FR-310's
 * contractual-life fallback because {@code ContractTerms.of(...)} leaves both lives unstated and
 * nothing holds a justification; no pool is defined and 03 § 10.1's quarterly back-test has no
 * artefact anywhere; and no ACPIR 54 election is recorded. {@code EngineSources} says each of
 * those in the response, naming the class a reader can open.
 *
 * <p><b>Why the module holds an {@link EirService} it never reads.</b> {@code ApiModules}
 * constructs every module with the service and nothing else, and the service's public surface is
 * eight methods that each return a rendered {@code Json.Obj} — there is no reader for the book,
 * the last run's computations or the tier assignments. So the field is the wiring contract and not
 * a data source, which is exactly why {@code EngineSources} reports gaps instead of rows.
 * Inventing rows from a statically reachable fixture would answer the endpoint with figures that
 * are not this server's book, and a fabricated approximations register is the failure of 06 § 7
 * with a report on top of it.
 *
 * <p><b>Status codes.</b> 200 for every answer including a wholly incomplete register, because a
 * refusal is a value in this engine and an incomplete register is an answer somebody has to act
 * on. 400 only for a malformed or absent {@code period}, through {@code FormBody.BadRequest},
 * which {@code EirServer} maps.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7.
 */
public final class ApproximationsModule implements ApiModule {

    /** {@code GET /api/reports/approximations?period=} — FR-809. */
    public static final String APPROXIMATIONS_PATH = "/api/reports/approximations";

    /** {@code GET /api/reports/disclosure/ind-as-107?period=} — FR-806. */
    public static final String DISCLOSURE_PATH = "/api/reports/disclosure/ind-as-107";

    private final EirService service;
    private final ApproximationRegister register;
    private final IndAs107Extract disclosure;

    /**
     * The wiring {@code ApiModules} uses: the engine's own sources, which report gaps.
     *
     * <p>{@link EngineSources} implements both source interfaces, so the two endpoints agree about
     * what the engine holds. Two implementations would eventually disagree, and then the
     * disclosure note would claim a measurement basis the approximations register said was
     * unknown.
     */
    public ApproximationsModule(EirService service) {
        this(service, new EngineSources());
    }

    /**
     * A module over supplied sources.
     *
     * <p>Public because it is how the register is driven with a populated book — by a test today,
     * and by whichever unit eventually records tier assignments, pool definitions and ACPIR 54
     * elections. Both interfaces are taken separately rather than as one combined type, so a
     * caller that can source the approximations but not the disclosure figures does not have to
     * fake the half it lacks.
     */
    public ApproximationsModule(
        EirService service, ApproximationSources approximations, DisclosureSources disclosures) {
        this.service = Objects.requireNonNull(service, "service");
        this.register = new ApproximationRegister(
            Objects.requireNonNull(approximations, "approximations"));
        this.disclosure = new IndAs107Extract(
            Objects.requireNonNull(disclosures, "disclosures"));
    }

    /** A module over one object supplying both, which {@link EngineSources} does. */
    public <T extends ApproximationSources & DisclosureSources> ApproximationsModule(
        EirService service, T sources) {
        this(service, sources, sources);
    }

    /**
     * The engine this module is wired to. See the class javadoc on why it is not read.
     *
     * <p>Kept and null-checked rather than dropped: the constructor signature is
     * {@code ApiModules}' contract, and a module that silently discarded the service would be
     * indistinguishable from one that had been given the wrong one.
     */
    public EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get(APPROXIMATIONS_PATH,
            exchange -> register.render(register.assemble(period(exchange))));
        routes.get(DISCLOSURE_PATH,
            exchange -> disclosure.render(register.assemble(period(exchange))));
    }

    /**
     * The {@code period} query parameter, as a {@code yyyyMM}.
     *
     * <p><b>Required, never defaulted.</b> Both reports are per-period — every window in the
     * register is measured against the period end — and defaulting to whatever period the book
     * happens to be positioned at would answer a question nobody asked with figures that look
     * like an answer to the one they did. {@code FormBody} is reused to parse it because a query
     * string is form encoding: the same decoder, the same refusal, and no second parser to get
     * wrong. A malformed or absent value therefore raises {@code FormBody.BadRequest} and
     * {@code EirServer} answers 400 naming the field.
     */
    private static int period(HttpExchange exchange) {
        FormBody query = FormBody.parse(exchange.getRequestURI().getQuery());
        int periodId = query.integer("period");
        try {
            // Validated here rather than left to the register, so a bad period is a 400 naming
            // the field instead of a 500 from an IllegalArgumentException deeper in.
            ApproximationRegister.periodEnd(periodId);
        } catch (IllegalArgumentException notAPeriod) {
            throw new FormBody.BadRequest(notAPeriod.getMessage());
        }
        return periodId;
    }

    /** For a caller assembling the register without a server. */
    public Json.Obj approximations(int periodId) {
        return register.render(register.assemble(periodId));
    }

    /** For a caller assembling the disclosure extract without a server. */
    public Json.Obj indAs107(int periodId) {
        return disclosure.render(register.assemble(periodId));
    }

    @Override
    public String specSection() {
        return "06 § 7";
    }
}
