package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.transition.DeadlineObligation;
import com.crisil.eir.api.modules.transition.TransitionBook;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.DayOneDifferenceDestination;
import com.crisil.eir.policy.transition.DeemedEirBasis;
import com.crisil.eir.policy.transition.DeemedEirDerivation;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationMethod;
import com.crisil.eir.policy.transition.MigrationTracker;
import com.crisil.eir.policy.transition.TransitionFairValue;
import com.crisil.eir.policy.transition.TransitionValuationRun;
import com.crisil.eir.policy.transition.ValuationTechnique;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The five transition endpoints of 06 § 8: the ACPIR 19 day-1 fair-value run, per-contract fair
 * value with the paragraph 19 rebuttal reference, legacy cohorts, migration, and coverage against
 * the ACPIR 21 and ACPIR 50 deadlines tracked SEPARATELY.
 *
 * <table>
 *   <caption>The routes, and 06 § 8's own words for each</caption>
 *   <tr><th>Route</th><th>Purpose</th></tr>
 *   <tr><td>{@code POST /api/transition/fair-value-run}</td>
 *       <td>ACPIR 19 day-1 fair valuation of the book</td></tr>
 *   <tr><td>{@code GET /api/transition/fair-value/{contractId}}</td>
 *       <td>pre-transition carrying amount, fair value, difference to opening retained earnings, and
 *           the <b>paragraph 19 rebuttal evidence reference</b></td></tr>
 *   <tr><td>{@code GET /api/transition/legacy-cohorts}</td>
 *       <td>cohorts with expected run-off vs 31 March 2030 and migration method</td></tr>
 *   <tr><td>{@code POST /api/transition/legacy-cohorts/{id}/migrate}</td>
 *       <td>full reconstruction or deemed EIR</td></tr>
 *   <tr><td>{@code GET /api/transition/coverage}</td>
 *       <td>migration coverage against <b>both</b> the ACPIR 21 and ACPIR 50 deadlines, tracked
 *           separately</td></tr>
 * </table>
 *
 * <p><b>The two deadlines are tracked separately, and that is the substance of this module.</b>
 * 04 § 6 treats the ECL discount-basis migration as two obligations rather than one — the loan must
 * come under the EIR regime (ACPIR 21) and its ECL discounting must move to the EIR (ACPIR 50) —
 * and says in terms that "tracking them in one field would hide a gap". A coverage report that
 * merged them would let one deadline's progress mask the other's, in the flattering direction:
 * ACPIR 21 is satisfied first and in bulk, so a merged figure climbs while the ECL basis has not
 * moved. So {@code /coverage} publishes one {@link DeadlineObligation} per obligation with its own
 * deadline, satisfied count and named outstanding contracts, and publishes <b>no combined migration
 * figure at all</b>. The seed makes the merge detectable rather than merely forbidden: ACPIR 21 is
 * outstanding on two contracts and ACPIR 50 on three, so no single number can stand for both.
 *
 * <p><b>Two refusals, and the difference between them is the point.</b>
 *
 * <ul>
 *   <li><b>A fair value resting on the paragraph 19 presumption with no rebuttal evidence does not
 *       publish its difference.</b> Carrying cost taken as best evidence produces a difference to
 *       opening retained earnings of exactly nil, and a nil that means "nothing was measured" is
 *       indistinguishable from a nil that was measured and found no difference. The evidence
 *       reference is the only thing that separates them, so where it is absent the figure is
 *       withheld and TF-1 says why. Publishing 0.00 there would put an unmeasured number into
 *       equity.</li>
 *   <li><b>A below-market day-1 shortfall with no approved destination publishes the figure and
 *       refuses the destination.</b> The shortfall was measured — the fair value was arrived at by
 *       discounting the concessional flows at a market rate — so withholding it would hide a real
 *       number. What is missing is the Board position saying where it goes, which ACPIR 19 and 20
 *       are silent on (reference § 4 Silence 6, {@code [MED-HIGH]}), and BM-1 is the assertion that
 *       one was taken.</li>
 * </ul>
 *
 * <p><b>Why the ids are bound at registration rather than parsed per request.</b> Two different
 * reasons, and they are worth separating because the first cut conflated them.
 *
 * <ul>
 *   <li><b>The cohort id: because a POST handler cannot read its own path.</b> {@link Routes#post}
 *       hands a handler the parsed {@link FormBody} and no exchange. Moving the id into the request
 *       body would let a caller POST to {@code .../HL-PRE-2020/migrate} with a different cohort
 *       named in the body and migrate the wrong 1,204,338 contracts, so the module registers one
 *       migrate route per cohort in the plan and closes over the name.</li>
 *   <li><b>The contract id: because an unknown resource has to be a 404.</b> A GET handler
 *       <em>does</em> receive the exchange and could parse its own path — but a handler returns a
 *       {@code Json.Obj}, so every answer it can give is a 200, and the honest status for a contract
 *       this book has never valued is 404. Binding the ids means an unknown contract has no route
 *       and falls through to the server's own handler, which 404s and names the path. A 200 carrying
 *       {@code "found": false} would be indistinguishable, to a caller keying on the status, from a
 *       contract whose fair value is genuinely nil.</li>
 * </ul>
 *
 * <p><b>What that costs, and where it stops working.</b> One {@code HttpServer} context per
 * answerable contract is fine for a transition book held in memory and would not be for the ten
 * million exposures 07 sizes the engine for — {@code HttpServer} scans its context list per request.
 * A production surface needs a {@code Routes} variant that hands a GET handler its path parameters
 * and can express 404, which is a change to a shared seam this unit does not own. Recorded here
 * rather than worked around, because a single prefix route would silently trade the correct status
 * code for a scaling property this module does not yet need.
 *
 * <p><b>Status codes.</b> 200 for every answer including every refusal, per {@code EirServer}. 400
 * where the caller sent something the domain refuses to construct at all — an unknown migration
 * method, a self-approved derivation, an approver with no approval date — because those are
 * malformed requests rather than engine answers: the record throws, so there is no value to return,
 * and mapping them to 500 would report a caller's mistake as an engine defect.
 *
 * <p><b>Where an application-layer transition use case would arrive.</b> The five handlers read
 * the programme's state through exactly one collaborator, {@link TransitionBook}, supplied by the
 * two-argument constructor. So a use case in {@code eir-application} — which cannot depend on this
 * module, the graph runs inward — replaces that one seam and no route, no rendering and no status
 * mapping moves. Nothing here waits for it: the module works with its own store today, and the
 * {@link EirService} it is handed by {@code ApiModules} is deliberately not read, because the
 * transition dataset is a one-off valuation and a migration queue rather than anything a monthly
 * run publishes.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 8. Data model: {@code 04 § 6}. Controls TF-1,
 * BM-1, LC-1, DE-1 and TM-1 are the transition set, and {@code 07 § 4.1.1} records that they are
 * deliberately <em>not</em> numbered C-16 to C-20 — they are programme controls with an end date,
 * and putting five expiring rows in a table whose premise is that each row gates every close
 * indefinitely would leave five rows to be explained away in 2030.
 */
public final class TransitionModule implements ApiModule {

    /** The base path, so the five routes cannot drift apart by a typo. */
    private static final String BASE = "/api/transition";

    /**
     * Where the ACPIR 19 difference goes. Named in every response and never a choice.
     *
     * <p>ACPIR 19 is a transition adjustment against the opening balance, not a period result, and
     * {@link TransitionFairValue} deliberately offers no accessor that would let it be read as one.
     * Naming the destination in the response is the same control at the edge: a caller who wires
     * this figure into a profit-and-loss line is doing so against an explicit statement.
     */
    private static final String DIFFERENCE_DESTINATION = "OPENING_RETAINED_EARNINGS";

    private final EirService service;
    private final TransitionBook transition;

    public TransitionModule(EirService service) {
        this(service, TransitionBook.seeded());
    }

    /**
     * For a test that wants a transition book of its own.
     *
     * <p>The seeded book is deliberately not clean — two unevidenced presumptions, an LC-1
     * inversion, an unapproved derivation and an untracked contract — because a demonstration book
     * on which every control passes teaches an operator nothing about what the controls are for.
     * A test asserting the green side of a control supplies its own population.
     */
    public TransitionModule(EirService service, TransitionBook transition) {
        this.service = Objects.requireNonNull(service, "service");
        this.transition = Objects.requireNonNull(transition, "transition");
    }

    /**
     * The run engine, held and deliberately unread — see the class javadoc.
     *
     * <p>The constructor signature is fixed by {@code ApiModules.all(service)}, which hands every
     * module the same collaborator. Kept rather than dropped because it is the seam an
     * {@code eir-application} transition use case would be wired through, and because a module that
     * quietly discarded its argument would read as a wiring mistake.
     */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");

        routes.post(BASE + "/fair-value-run", this::fairValueRun);

        // ONE subtree route, and the contract id comes off the path.
        //
        // This was a loop registering one route per contract the book could answer for, because
        // Routes.get takes a fixed path and could not read a path parameter — so the id had to be
        // baked into the registration for an unknown contract to 404. Routes.route now exists and
        // hands the handler its exchange. The loop was not merely inelegant: it created one JDK
        // HttpServer context per contract, so a book of ten million would have registered ten
        // million contexts at construction. It also made the surface inventory
        // (Routes.registeredRoutes(), which the access-control coverage report reads) grow with the
        // population, so a report of "what is exposed" counted nine transition routes on the seed
        // book and would have counted millions on a real one.
        routes.route(BASE + "/fair-value/", this::fairValueByPath);

        routes.get(BASE + "/legacy-cohorts", exchange -> legacyCohorts());
        // Same change, same reason. The declining branch also replaces a handler registered only to
        // stop an unknown cohort answering 405 "GET only" — which told an integrator the verb was
        // wrong when the cohort name was — and it can now name the offending id, because a subtree
        // handler receives the exchange that a POST handler did not.
        routes.route(BASE + "/legacy-cohorts/", this::migrateByPath);

        routes.get(BASE + "/coverage", this::coverage);
    }

    @Override
    public String specSection() {
        return "06 § 8";
    }

    /** The routes this module registers, for a caller that wants the surface listed. */
    public List<String> routes() {
        List<String> paths = new ArrayList<>();
        paths.add("POST " + BASE + "/fair-value-run");
        paths.add("GET " + BASE + "/fair-value/{contractId}");
        paths.add("GET " + BASE + "/legacy-cohorts");
        paths.add("POST " + BASE + "/legacy-cohorts/{cohortName}/migrate");
        paths.add("GET " + BASE + "/coverage");
        return List.copyOf(paths);
    }

    // ============================================ POST /api/transition/fair-value-run

    /**
     * The ACPIR 19 day-1 fair valuation of the book, and Phase 4's exit gate as a computed answer.
     *
     * <p>08 Phase 4 states the gate as: "the fair-valuation run completes over the full book with a
     * rebuttal evidence reference on every contract where the presumption was applied." Those are
     * two claims and this endpoint answers both — the completeness leg by reconciling the valued
     * population against the legacy book size, which the run itself cannot do because it has no way
     * to know that a contract exists and was never presented to it, and the evidence leg with TF-1
     * over the population.
     *
     * <p><b>The total is withheld while TF-1 fails.</b> A total containing a nil difference from a
     * contract where nothing was measured is not the ACPIR 19 adjustment, and it is a figure that
     * goes to equity. So {@code published} is false and the figure is null until the evidence is on
     * file.
     *
     * <p><b>Optional: file evidence and re-perform the run.</b> {@code evidenceForContract} and
     * {@code paragraph19EvidenceRef} record a rebuttal reference before the run is computed. 08
     * Phase 4 is explicit that the evidence file is "built during FY27, <b>not</b> at the transition
     * date", so a valuation acquiring its reference after the run first refused is the intended
     * workflow — the same shape as working an exception before a close, and the only way the caller
     * can see this control's green side over HTTP as well as its red one.
     */
    private Json.Obj fairValueRun(FormBody body) {
        String filedNow = null;
        if (body.has("evidenceForContract")) {
            String contractId = body.text("evidenceForContract");
            String evidenceRef = body.text("paragraph19EvidenceRef");
            try {
                transition.fileParagraph19Evidence(contractId, evidenceRef);
            } catch (IllegalArgumentException refused) {
                // A reference against a contract this book does not value, or against a technique
                // that does not rest on the presumption. Both are the caller naming the wrong row,
                // not the engine declining to answer.
                throw new FormBody.BadRequest(refused.getMessage());
            }
            filedNow = contractId + " -> " + evidenceRef;
        }

        TransitionValuationRun run = transition.valuationRun();
        InvariantResult evidenced = run.paragraph19Evidenced();
        InvariantResult destinations =
            BelowMarketOrigination.destinationsApproved(transition.originations());

        long unvalued = run.coverageAgainst(transition.legacyBookSize());
        boolean completeOverTheBook = unvalued == 0;
        boolean published = evidenced.satisfied();

        Map<ValuationTechnique, Long> mix = run.techniqueMix();
        List<Json.Obj> techniques = new ArrayList<>();
        for (ValuationTechnique technique : ValuationTechnique.values()) {
            techniques.add(Json.object()
                .str("technique", technique.name())
                .count("contracts", count(mix.get(technique)))
                .bool("appliesParagraph19Presumption", technique.appliesParagraph19Presumption()));
        }

        long presumptionsApplied = run.valuations().stream()
            .filter(TransitionFairValue::appliesParagraph19Presumption)
            .count();
        List<String> unevidenced = run.valuations().stream()
            .filter(TransitionFairValue::presumptionIsUnevidenced)
            .map(TransitionFairValue::contractId)
            .sorted()
            .toList();

        Json.Obj response = Json.object()
            .str("transitionDate", run.transitionDate().toString())
            .count("legacyBookSize", count(transition.legacyBookSize()))
            .count("valuedContracts", run.valuedContractCount())
            .count("unvaluedContracts", count(unvalued))
            .bool("completeOverTheBook", completeOverTheBook)
            .array("techniqueMix", techniques)
            .count("paragraph19PresumptionsApplied", count(presumptionsApplied))
            .count("paragraph19PresumptionsUnevidenced", unevidenced.size())
            .strings("paragraph19UnevidencedContracts", unevidenced)
            .str("differenceDestination", DIFFERENCE_DESTINATION)
            .bool("published", published);

        if (published) {
            response.figure("totalDifferenceToOpeningRetainedEarnings",
                run.totalDifferenceToOpeningRetainedEarnings().atPresentationScale().amount());
            response.str("withheld", null);
        } else {
            // Explicitly null rather than absent. An absent key reads as a formatting accident; a
            // null beside a reason reads as a decision, which is what it is.
            response.figure("totalDifferenceToOpeningRetainedEarnings", null);
            response.str("withheld",
                "the total is withheld because " + unevidenced.size() + " of "
                    + presumptionsApplied + " paragraph 19 presumptions name no rebuttal evidence"
                    + " (TF-1): a nil difference arrived at by presuming carrying cost is"
                    + " indistinguishable from one that was measured, and this figure goes to"
                    + " opening retained earnings");
        }

        return response
            .bool("exitGateMet", published && completeOverTheBook)
            .str("exitGate", "08 Phase 4: the fair-valuation run completes over the full book with a"
                + " rebuttal evidence reference on every contract where the presumption was applied")
            .count("belowMarketOriginations", transition.originations().size())
            .str("belowMarketNote", "BM-1 is reported here because a below-market origination is a"
                + " day-1 fair valuation on its own date (04 § 6 keeps one row shape for both), but"
                + " it is NOT part of Phase 4's exit gate: concessional lending does not stop at the"
                + " transition, so BM-1 never ends (07 § 4.1.1)")
            .array("invariants", invariantRows(List.of(evidenced, destinations)))
            .array("perContractEvidence", invariantRows(run.perContractEvidence()))
            .str("evidenceFiledThisCall", filedNow)
            .strings("evidenceFiledSinceStart", transition.evidenceFiled())
            // run.describe() states the total to opening retained earnings, so it is published
            // only where the total itself is. A withheld figure handed back inside a prose field is
            // still published, and a caller scraping "describe" would never know the difference.
            .str("describe", published
                ? run.describe()
                : "ACPIR 19 valuation at " + run.transitionDate() + ": "
                    + run.valuedContractCount() + " contracts, techniques " + mix
                    + "; the total to opening retained earnings is withheld pending "
                    + unevidenced.size() + " paragraph 19 rebuttal evidence references");
    }

    // ============================================ GET /api/transition/fair-value/{contractId}

    /**
     * One contract's day-1 fair value: carrying amount, fair value, the difference to opening
     * retained earnings, and the paragraph 19 rebuttal evidence reference.
     *
     * <p>Serves both shapes, because 04 § 6 deliberately does not constrain the valuation's date to
     * 1 April 2027 so that a below-market origination uses the same row: an ACPIR 19 transition
     * valuation of the existing book, or a concessional loan measured at fair value on its own day 1
     * (FR-909). {@code kind} says which, and the two refuse differently — see the class javadoc.
     */
    /**
     * {@code GET /api/transition/fair-value/{contractId}} — one route, the id read off the path.
     *
     * <p>Declines anything that is not a single-segment GET under the prefix, so a sibling module on
     * the same prefix can claim it and an unclaimed shape becomes the server's 404 naming the path.
     * The path is split raw and the segment decoded afterwards: decoding first would let an id
     * carrying {@code %2F} split into two segments and address something the caller did not name.
     *
     * <p>A contract the transition book cannot answer for is a 404 with the reason, not a 500. That
     * was previously guaranteed by the registration — a route existed only for answerable contracts
     * — and now has to be a decision in the handler, which is the honest place for it: "this book
     * holds no ACPIR 19 valuation and no origination for that contract" is an answer about the
     * book, and the per-route version could not distinguish it from a path nobody serves.
     */
    private Routes.Answer fairValueByPath(HttpExchange exchange, FormBody body) {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return null;
        }
        String prefix = BASE + "/fair-value/";
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!rawPath.startsWith(prefix)) {
            return null;
        }
        String[] segments = rawPath.substring(prefix.length()).split("/");
        if (segments.length != 1 || segments[0].isBlank()) {
            return null;
        }
        String contractId = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
        if (!transition.answerableContractIds().contains(contractId)) {
            return Routes.Answer.of(404, Json.object()
                .str("contractId", contractId)
                .bool("answered", false)
                .str("reason", "NO_TRANSITION_RECORD")
                .strings("answerableContracts", transition.answerableContractIds())
                .str("detail", "the transition book holds neither an ACPIR 19 day-1 valuation nor a"
                    + " below-market origination for " + contractId + ". A contract the master"
                    + " carries and the valuation run never saw has no fair value to report, and"
                    + " reporting one would be inventing the figure this endpoint exists to"
                    + " evidence"));
        }
        return Routes.Answer.ok(fairValue(contractId));
    }

    /**
     * {@code POST /api/transition/legacy-cohorts/{cohortName}/migrate} — one route, id off the path.
     *
     * <p>The shape is checked exactly: two segments, the second {@code migrate}. Anything else is
     * declined rather than swallowed, which is the difference between a mistyped action and a
     * migration nobody asked for. An unknown cohort name is a 404 that <em>names</em> it and lists
     * the plan's cohorts — the per-cohort registration it replaces could not, because a
     * {@code Routes.post} handler receives no exchange and so could not see the path it arrived by.
     */
    private Routes.Answer migrateByPath(HttpExchange exchange, FormBody body) {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return null;
        }
        String prefix = BASE + "/legacy-cohorts/";
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!rawPath.startsWith(prefix)) {
            return null;
        }
        String[] segments = rawPath.substring(prefix.length()).split("/");
        if (segments.length != 2 || !"migrate".equals(segments[1])) {
            return null;
        }
        String cohortName = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
        List<String> known = transition.cohorts().stream()
            .map(LegacyCohort::cohortName)
            .toList();
        if (!known.contains(cohortName)) {
            return Routes.Answer.of(404, Json.object()
                .str("cohortName", cohortName)
                .bool("migrated", false)
                .str("reason", "NO_SUCH_COHORT")
                .strings("cohortsInThePlan", known)
                .str("detail", "the migration plan holds no cohort named " + cohortName
                    + "; migrating a cohort nobody segmented would record progress against the"
                    + " ACPIR 21 deadline for a population that does not exist"));
        }
        return Routes.Answer.ok(migrate(cohortName, body));
    }

    private Json.Obj fairValue(String contractId) {
        Optional<TransitionFairValue> valued = transition.valuation(contractId);
        if (valued.isPresent()) {
            return transitionValuation(valued.get());
        }
        // fairValueByPath has already established the contract is answerable, so the only way to
        // reach this branch is an origination.
        return belowMarket(transition.origination(contractId).orElseThrow(
            () -> new IllegalStateException("route registered for " + contractId
                + " but the transition book carries neither a valuation nor an origination for it")));
    }

    private Json.Obj transitionValuation(TransitionFairValue valuation) {
        InvariantResult evidenced = valuation.paragraph19Evidenced();
        boolean withhold = valuation.presumptionIsUnevidenced();

        Json.Obj response = Json.object()
            .str("contractId", valuation.contractId())
            .str("kind", "ACPIR_19_TRANSITION")
            .str("transitionDate", valuation.transitionDate().toString())
            .figure("preTransitionCarryingAmount",
                valuation.preTransitionCarryingAmount().atPresentationScale().amount())
            .figure("fairValue", valuation.fairValue().atPresentationScale().amount())
            .str("valuationTechnique", valuation.technique().name())
            .figure("discountRateUsed", valuation.discountRateUsed() == null
                ? null : valuation.discountRateUsed().periodic())
            .bool("appliesParagraph19Presumption", valuation.appliesParagraph19Presumption())
            .str("paragraph19EvidenceRef", valuation.paragraph19EvidenceRef())
            .str("differenceDestination", DIFFERENCE_DESTINATION)
            .bool("published", !withhold);

        if (withhold) {
            // The figure is NOT published. It would be 0.00 — the fair value equals the carrying
            // amount by construction when carrying cost is taken as best evidence — and 0.00 here
            // means "nothing was measured", which is the one reading a reader cannot recover from
            // the number. TF-1's detail is returned in its place.
            response.figure("differenceToOpeningRetainedEarnings", null)
                .str("withheld", "the difference is withheld: this valuation takes carrying cost as"
                    + " the best evidence of fair value with no paragraph 19 rebuttal evidence"
                    + " named, so its nil difference records that nothing was measured rather than"
                    + " that nothing was found (TF-1). The carrying amount and the fair value are"
                    + " both published because 06 § 8 requires them, so the subtraction is available"
                    + " to anyone who wants to perform it — what is withheld is this engine's"
                    + " assertion that the result is the ACPIR 19 adjustment, and its inclusion in"
                    + " the run's total. File the evidence reference through POST"
                    + " /api/transition/fair-value-run and the figure publishes.");
        } else {
            response.figure("differenceToOpeningRetainedEarnings",
                valuation.differenceToOpeningRetainedEarnings().atPresentationScale().amount())
                .str("withheld", null);
        }

        return response
            .str("measuredBy", valuation.measuredBy())
            .str("reviewedBy", valuation.reviewedBy())
            .bool("reviewed", valuation.reviewedBy() != null)
            .array("invariants", invariantRows(List.of(evidenced)))
            // As above: TransitionFairValue.describe() names the difference, so it is published
            // only where the figure is.
            .str("describe", withhold
                ? "contract " + valuation.contractId() + " at " + valuation.transitionDate()
                    + ": carrying " + valuation.preTransitionCarryingAmount().atPresentationScale()
                    + " -> fair value " + valuation.fairValue().atPresentationScale() + " by "
                    + valuation.technique() + ", measured by " + valuation.measuredBy()
                    + (valuation.reviewedBy() == null
                        ? " (unreviewed)" : ", reviewed by " + valuation.reviewedBy())
                    + " [PARAGRAPH 19 PRESUMPTION, NO EVIDENCE — difference to opening retained"
                    + " earnings withheld]"
                : valuation.describe());
    }

    private Json.Obj belowMarket(BelowMarketOrigination origination) {
        DayOneDifferenceDestination destination = origination.destination();
        TransitionFairValue asMeasured = origination.asFairValueMeasurement();
        return Json.object()
            .str("contractId", origination.contractId())
            .str("kind", "BELOW_MARKET_ORIGINATION")
            .str("originationDate", origination.originationDate().toString())
            .figure("preTransitionCarryingAmount",
                origination.amountDisbursed().atPresentationScale().amount())
            .figure("amountDisbursed", origination.amountDisbursed().atPresentationScale().amount())
            .str("carryingAmountNote", "for an origination the pre-transition carrying amount IS the"
                + " amount disbursed — the loan had no prior carrying amount, since its day 1 is its"
                + " own and not 1 April 2027. Both keys are published so a caller reading this"
                + " endpoint's ACPIR 19 shape gets the same field back, and the mapping is"
                + " BelowMarketOrigination.asFairValueMeasurement()'s own (04 § 6 keeps one row"
                + " shape for both cases)")
            .figure("fairValue", origination.fairValue().atPresentationScale().amount())
            // Published even where the destination is unapproved, and that is deliberate: the
            // shortfall WAS measured, by discounting the concessional flows at a market rate. What
            // is undecided is where it goes. Withholding it — the treatment C-0003's nil difference
            // gets — would hide a real figure to report an approval gap.
            .figure("dayOneShortfall", origination.dayOneShortfall().atPresentationScale().amount())
            .bool("published", true)
            .str("valuationTechnique", asMeasured.technique().name())
            .figure("effectiveInterestRate", origination.effectiveInterestRate().periodic())
            .figure("contractualRate", origination.contractualRate().periodic())
            .figure("concessionSpread", origination.concessionSpread())
            .str("dayOneDifferenceDestination",
                destination == null ? null : destination.name())
            .bool("destinationApproved", origination.destinationIsApproved())
            // A string rather than a boolean, because there are three states and false would
            // have read as "outside the current period's result" for a shortfall whose destination
            // nobody has chosen — which is a claim about the accounting, not an absence of one.
            .str("differenceLands", destination == null ? null
                : destination.affectsCurrentPeriodResult()
                    ? "CURRENT_PERIOD_RESULT" : "OPENING_BALANCE")
            .str("boardPosition",
                origination.position() == null ? null : origination.position().id())
            .str("silence", "ACPIR 19 and 20 require fair value at initial recognition and give no"
                + " guidance on the day-1 difference (reference § 4 Silence 6, MED-HIGH), so the"
                + " destination is a Board-approved position and BM-1 asserts that one was taken —"
                + " not that any particular destination is right")
            .str("measuredBy", origination.measuredBy())
            .str("reviewedBy", origination.reviewedBy())
            .array("invariants",
                invariantRows(List.of(BelowMarketOrigination.destinationsApproved(
                    List.of(origination)))))
            .str("describe", origination.describe());
    }

    // ============================================ GET /api/transition/legacy-cohorts

    /**
     * The migration queue: expected run-off against 31 March 2030, and the migration method.
     *
     * <p><b>The contract count is supplied, not derived, and the response says so.</b>
     * {@link LegacyCohort#definition()} carries what the cohort selects as free text — matching the
     * schema's JSONB column — and <em>nothing in this engine evaluates it</em>. So
     * {@code contractCount} is a figure a programme team stated, and no code here has confirmed that
     * the definition selects that many exposures. Publishing it beside {@code membershipEvaluated:
     * false} is the honest shape: a reader who takes the count as a computed reconciliation of the
     * definition against the contract master would be relying on something that does not exist.
     */
    private Json.Obj legacyCohorts() {
        LegacyMigrationPlan plan = transition.plan();
        List<Json.Obj> rows = new ArrayList<>();
        for (LegacyCohort cohort : plan.cohorts()) {
            Optional<DeemedEirDerivation> derivation = transition.derivationFor(cohort.cohortName());
            rows.add(Json.object()
                .str("cohortName", cohort.cohortName())
                .str("definition", cohort.definition())
                .bool("definitionEvaluated", false)
                .str("definedOn", cohort.definedOn().toString())
                .str("expectedRunoffDate", cohort.expectedRunoffDate().toString())
                .bool("survivesAcpir50Deadline", cohort.survivesAcpir50Deadline())
                .count("migrationPriority", cohort.migrationPriority())
                .str("migrationMethod", cohort.method().name())
                .bool("restsOnAnAssumption", cohort.method().restsOnAnAssumption())
                .count("contractCount", count(cohort.contractCount()))
                .bool("contractCountIsSupplied", true)
                .bool("definitionApproved", cohort.isApproved())
                .str("definitionApprovedBy", cohort.approvedBy())
                .bool("reconstructionEffortIsWasted", cohort.reconstructionEffortIsWasted())
                .bool("migrationApplied", transition.migrationApplied(cohort.cohortName()))
                .str("migratedBy", transition.migratedBy(cohort.cohortName()))
                .obj("deemedEirDerivation", derivation.map(TransitionModule::derivationRow)
                    .orElse(null))
                .str("describe", cohort.describe()));
        }

        List<String> wasted = plan.wastedReconstructionEffort().stream()
            .map(cohort -> cohort.cohortName() + " (" + cohort.contractCount()
                + " contracts, runs off " + cohort.expectedRunoffDate() + ")")
            .toList();
        List<String> queueOrder = plan.survivingCohortsInQueueOrder().stream()
            .map(cohort -> cohort.migrationPriority() + ": " + cohort.cohortName())
            .toList();

        return Json.object()
            .str("acpir50Deadline", LegacyCohort.ACPIR_50_DEADLINE.toString())
            .count("cohortCount", rows.size())
            .count("contractsRequiringMigration", count(plan.contractsRequiringMigration()))
            .bool("membershipEvaluated", false)
            .str("membershipNote", "a cohort's definition is free text — the schema carries it as"
                + " JSONB and nothing in this engine evaluates it — so contractCount is a figure the"
                + " programme supplied and no code here has confirmed that the definition selects"
                + " that many exposures")
            .array("cohorts", rows)
            .strings("wastedReconstructionEffort", wasted)
            .str("wastedEffortNote", "plain data, not an invariant: 08 says 'reconstructing an EIR"
                + " for a loan maturing in 2029 is wasted effort', but spending effort badly is not"
                + " an accounting breach and an invariant id on it would put a programme management"
                + " question in the same list as a figure that does not tie")
            .strings("survivingCohortsInQueueOrder", queueOrder)
            .array("invariants", invariantRows(plan.invariants()));
    }

    // ================================ POST /api/transition/legacy-cohorts/{id}/migrate

    /**
     * Migrates one cohort onto the EIR: full reconstruction, or a deemed EIR (FR-908, FR-909).
     *
     * <p>Form fields: {@code method} ({@code FULL_RECONSTRUCTION} or {@code DEEMED_EIR}) and
     * {@code migratedBy}. A deemed migration takes {@code deemedRate}, {@code basis},
     * {@code infeasibilityReason}, {@code documentedBasis}, {@code preparedBy} and optionally
     * {@code evidenceRef}, {@code approvedBy} and {@code approvedOn} — or none of them, in which
     * case the derivation already on file governs.
     *
     * <p><b>What this endpoint refuses, and how.</b> A deemed migration with an <em>unapproved</em>
     * derivation is applied and reported: DE-1 fails, {@code measurableOnThisBasis} is false, and the
     * refusal is named. That is the honest treatment, because an unapproved derivation in flight is
     * the normal state of one and refusing to record it would make the remaining work invisible
     * rather than absent. A deemed migration with <em>no</em> derivation at all is a 400 — there is
     * no rate to recognise income on, so there is no measurement to report an invariant about. A
     * derivation prepared and approved by one person is also a 400: {@link DeemedEirDerivation}
     * refuses to construct it, so there is no value to return.
     */
    private Json.Obj migrate(String cohortName, FormBody body) {
        MigrationMethod method = enumField(body, "method", MigrationMethod.class);
        String migratedBy = body.text("migratedBy");

        LegacyCohort before = transition.cohorts().stream()
            .filter(cohort -> cohort.cohortName().equals(cohortName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "route registered for cohort " + cohortName + " which the plan does not hold"));

        DeemedEirDerivation supplied = null;
        if (namesADerivation(body)) {
            if (!method.restsOnAnAssumption()) {
                // A derivation on a FULL_RECONSTRUCTION request is either the wrong method recorded
                // or a working that was abandoned, and both are things a reader would take as the
                // basis of the rate — the same argument TransitionFairValue makes for a discount
                // rate on a quoted-price row.
                throw new FormBody.BadRequest("this request names derivation fields and migrates"
                    + " cohort " + cohortName + " by " + method + ", which rests on reconstructed"
                    + " flows rather than on an assumption; either the method is wrong or the"
                    + " derivation belongs to a working that was not used");
            }
            // Any derivation field triggers the whole build, so body.text() raises the 400 for
            // whichever one is missing. See DERIVATION_FIELDS for what gating on one field cost.
            supplied = derivationFrom(cohortName, body);
        }

        LegacyCohort after;
        try {
            after = transition.migrate(cohortName, method, supplied, migratedBy);
        } catch (IllegalArgumentException refused) {
            throw new FormBody.BadRequest(refused.getMessage());
        }

        LegacyMigrationPlan plan = transition.plan();
        InvariantResult prioritised = plan.prioritisedBySurvival();
        InvariantResult approved = plan.deemedRatesApproved();
        Optional<DeemedEirDerivation> derivation = transition.derivationFor(cohortName);
        boolean measurable = !method.restsOnAnAssumption()
            || derivation.map(DeemedEirDerivation::isApproved).orElse(false);

        List<String> refusals = new ArrayList<>();
        if (!measurable) {
            refusals.add("cohort " + cohortName + " is now measured on a deemed EIR of "
                + derivation.map(record -> record.deemedRate().periodic().toPlainString())
                    .orElse("(none)")
                + " whose derivation nobody has approved; a deemed rate recognises income on an"
                + " assumption every period for the rest of the exposure's life, so preparing one is"
                + " analysis and measuring a cohort on it is a decision (DE-1)");
        }
        if (after.reconstructionEffortIsWasted()) {
            refusals.add("cohort " + cohortName + " is queued for full reconstruction and runs off "
                + after.expectedRunoffDate() + ", before the 31 March 2030 deadline; that exposure"
                + " never needs a reconstructed rate, so the capacity buys nothing");
        }

        return Json.object()
            .str("cohortName", cohortName)
            .str("migratedBy", migratedBy)
            .str("previousMethod", before.method().name())
            .str("migrationMethod", after.method().name())
            .bool("methodChanged", before.method() != after.method())
            .bool("survivesAcpir50Deadline", after.survivesAcpir50Deadline())
            .str("expectedRunoffDate", after.expectedRunoffDate().toString())
            .count("contractCount", count(after.contractCount()))
            .bool("contractCountIsSupplied", true)
            .bool("measurableOnThisBasis", measurable)
            .strings("refusals", refusals)
            .obj("deemedEirDerivation", derivation.map(TransitionModule::derivationRow).orElse(null))
            .bool("derivationSupplied", supplied != null)
            .array("invariants", invariantRows(List.of(prioritised, approved)))
            .str("describe", after.describe());
    }

    private static Json.Obj derivationRow(DeemedEirDerivation derivation) {
        return Json.object()
            .str("subject", derivation.subject())
            .figure("deemedRate", derivation.deemedRate().periodic())
            .str("basis", derivation.basis().name())
            .str("infeasibilityReason", derivation.infeasibilityReason())
            .str("documentedBasis", derivation.documentedBasis())
            .str("evidenceRef", derivation.evidenceRef())
            .str("preparedBy", derivation.preparedBy())
            .str("approvedBy", derivation.approvedBy())
            .str("approvedOn",
                derivation.approvedOn() == null ? null : derivation.approvedOn().toString())
            .bool("approved", derivation.isApproved())
            .str("describe", derivation.describe());
    }

    private DeemedEirDerivation derivationFrom(String cohortName, FormBody body) {
        DeemedEirBasis basis = enumField(body, "basis", DeemedEirBasis.class);
        String approvedBy = body.textOr("approvedBy", null);
        LocalDate approvedOn = body.has("approvedOn")
            ? isoDate(body.text("approvedOn"), "approvedOn") : null;
        try {
            // Inside the try, because Rate refuses a periodic rate at or below minus 100% and the
            // first cut constructed it above: a caller sending deemedRate=-2 got a 500 naming a
            // domain message, which is this layer reporting a caller's mistake as an engine defect.
            Rate deemedRate = Rate.periodic(body.decimal("deemedRate"), 12);
            return new DeemedEirDerivation(cohortName, null, deemedRate, basis,
                body.text("infeasibilityReason"), body.text("documentedBasis"),
                body.textOr("evidenceRef", null), body.text("preparedBy"),
                approvedBy, approvedOn);
        } catch (IllegalArgumentException refused) {
            // Self-approval, an approver with no date, or a rate outside Rate's domain. The record
            // refuses to exist, so there is no engine answer to return on a 200 — the caller sent a
            // combination this system does not represent, which is what 400 means here.
            throw new FormBody.BadRequest(refused.getMessage());
        }
    }

    /**
     * Every field a supplied derivation is built from.
     *
     * <p>Named as a list because the derivation branch has to trigger on <em>any</em> of them. The
     * first cut gated on {@code deemedRate} alone, so a caller who misspelled that one field had a
     * complete, approved derivation silently discarded and got a 200 reporting the old unapproved
     * one as governing — an operator who came to sign off a rate told the rate was unsigned, with
     * nothing in the response saying their input had been dropped.
     */
    private static final List<String> DERIVATION_FIELDS = List.of(
        "deemedRate", "basis", "infeasibilityReason", "documentedBasis", "evidenceRef",
        "preparedBy", "approvedBy", "approvedOn");

    /** Whether the caller sent any part of a derivation. */
    private static boolean namesADerivation(FormBody body) {
        for (String field : DERIVATION_FIELDS) {
            if (body.has(field)) {
                return true;
            }
        }
        return false;
    }

    // ============================================ GET /api/transition/coverage

    /**
     * Migration coverage against both deadlines, tracked separately.
     *
     * <p>Reported as at the book's own business date rather than a wall clock, so the answer is a
     * function of the book and not of when somebody asked — the same reason the run's policy
     * resolution is pinned to the period end. {@code ?asOf=YYYY-MM-DD} moves it, which is how an
     * operator asks the question that matters: what does this report say on 1 April 2030, the first
     * day on which the interim concession is a breach?
     *
     * <p><b>There is no combined migration figure in this response, and there must never be.</b> See
     * the class javadoc: one obligation's progress masking the other's is exactly what 04 § 6 gives
     * the ECL discount basis its own table to prevent.
     */
    private Json.Obj coverage(HttpExchange exchange) {
        LocalDate asOf = asOfFrom(exchange);
        MigrationTracker tracker = transition.tracker();
        DeadlineObligation acpir21 = transition.acpir21Coverage();
        DeadlineObligation acpir50 = transition.acpir50Coverage();

        List<String> eclAheadOfInterest = tracker.eclAheadOfInterest().stream()
            .map(state -> state.contractId())
            .sorted()
            .toList();

        return Json.object()
            .str("asOf", asOf.toString())
            .str("populationSource", "the legacy loan book at " + TransitionBook.TRANSITION_DATE)
            .count("contractsInPopulation", count(transition.legacyBookSize()))
            .count("trackedContracts", tracker.trackedContracts())
            .count("untrackedContracts", count(tracker.untrackedContracts()))
            .strings("untracked", transition.untrackedContracts())
            .str("untrackedNote", "a contract with no recorded ECL discount basis is not a contract"
                + " that has not migrated — it is one whose position nobody knows, and it is"
                + " deliberately excluded from both obligations' outstanding counts so that an"
                + " unknown does not read as a scheduled piece of work")
            .obj("acpir21", obligationRow(acpir21, asOf))
            .obj("acpir50", obligationRow(acpir50, asOf))
            .bool("deadlinesCoincide", acpir21.deadline().equals(acpir50.deadline()))
            .bool("combinedMigrationFigurePublished", false)
            .str("separatelyTrackedNote", "ACPIR 21 and ACPIR 50 are two obligations, not one:"
                + " the loan must come under the EIR regime, and its ECL discounting must migrate to"
                + " the EIR. 04 § 6 — 'tracking them in one field would hide a gap'. No combined"
                + " figure is published, because ACPIR 21 is satisfied first and in bulk, so a"
                + " merged percentage would climb while the ECL basis had not moved at all. On this"
                + " population the two outstanding counts differ, " + acpir21.outstanding()
                + " against " + acpir50.outstanding() + ", so no single number can stand for both.")
            .count("underTheAcpir50Concession", count(tracker.outstandingAcpir50Migrations()))
            .str("concessionNote", "the concession population — ACPIR 21 met, ACPIR 50 not — which"
                + " is what a programme schedules against. It is smaller than ACPIR 50's outstanding"
                + " count because it excludes contracts that are not yet under the EIR regime at"
                + " all, and before 31 March 2030 it is progress rather than a defect: it is the"
                + " shape ACPIR 50's interim concession describes")
            .strings("eclAheadOfInterest", eclAheadOfInterest)
            .str("eclAheadNote", "the reverse gap, reported rather than refused: an EIR has to exist"
                + " before the ECL model can use it, so a contract can be reconstructed for"
                + " discounting before recognition is switched over. A sequencing signal, not an"
                + " impossible state")
            .array("invariants", invariantRows(List.of(tracker.migrationTracked(asOf))))
            .str("tmOneNote", "TM-1 asserts that both obligations are TRACKED and that the interim"
                + " concession has not outlived its deadline. It deliberately does not fail while a"
                + " contract is merely still on the interim basis before 31 March 2030: that"
                + " formulation would be red continuously from 2027 to 2030 while describing a state"
                + " ACPIR 50 explicitly permits, and a control that is red by design gets suppressed"
                + " and is then not there for the year it matters (07 § 4.1.1)")
            // Not tracker.describe(asOf): its "ACPIR 50 outstanding" figure is the concession
            // population (ACPIR 21 met, 50 not), which is 2 here against the obligation's 3, and
            // two different numbers under one label in one response is how a reader learns to
            // distrust all of them. The tracker's figure is published under its own name above.
            .str("describe", "as at " + asOf + ": " + tracker.trackedContracts() + " of "
                + transition.legacyBookSize() + " contracts tracked, "
                + acpir21.describe() + "; " + acpir50.describe());
    }

    private static Json.Obj obligationRow(DeadlineObligation obligation, LocalDate asOf) {
        return Json.object()
            .str("obligation", obligation.obligation())
            .str("requirement", obligation.requirement())
            .str("deadline", obligation.deadline().toString())
            .count("tracked", count(obligation.tracked()))
            .count("satisfied", count(obligation.satisfied()))
            .count("outstanding", count(obligation.outstanding()))
            .strings("outstandingContracts", obligation.outstandingContracts())
            .bool("met", obligation.met())
            .bool("inBreach", obligation.inBreach(asOf))
            .str("describe", obligation.describe());
    }

    /**
     * The date the coverage report is made as at.
     *
     * <p>Defaults to {@link Seed#PERIOD_END}, the book's business date. Deliberately not a clock: a
     * control report whose answer changes because the machine's date changed is not reproducible,
     * and eir-api is allowed a clock but has no reason to take one here.
     */
    private static LocalDate asOfFrom(HttpExchange exchange) {
        String raw = queryParameter(exchange, "asOf");
        return raw == null ? Seed.PERIOD_END : isoDate(raw, "asOf");
    }

    /**
     * One query parameter, decoded by the JDK's own URI parsing.
     *
     * <p>Deliberately narrow: this API's only query parameter is a date, so there is no need for a
     * general parser and every line of one would be untested surface.
     */
    private static String queryParameter(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && key.equals(pair.substring(0, split))) {
                // The raw value, blank included. Mapping blank to null made ?asOf= answer 200
                // as at the period end while ?asOf=next-tuesday answered 400 — so a caller whose
                // date variable interpolated empty got a plausible answer for the wrong date.
                return pair.substring(split + 1).strip();
            }
        }
        return null;
    }

    // ============================================ shared rendering

    private static List<Json.Obj> invariantRows(List<InvariantResult> results) {
        List<Json.Obj> rows = new ArrayList<>(results.size());
        for (InvariantResult result : results) {
            rows.add(Json.object()
                .str("id", result.id().name().replace('_', '-'))
                .str("statement", result.id().statement())
                .bool("satisfied", result.satisfied())
                .figure("deviation", result.deviation())
                .str("detail", result.detail()));
        }
        return List.copyOf(rows);
    }

    private static <E extends Enum<E>> E enumField(FormBody body, String key, Class<E> type) {
        String raw = body.text(key);
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new FormBody.BadRequest("'" + key + "' must be one of "
                + Arrays.toString(type.getEnumConstants()) + ", got '" + raw + "'");
        }
    }

    private static LocalDate isoDate(String raw, String key) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException unparseable) {
            throw new FormBody.BadRequest("'" + key + "' must be an ISO-8601 date such as"
                + " 2030-04-01, got '" + raw + "'");
        }
    }

    /**
     * A {@code long} count as the {@code int} the JSON writer emits as a number.
     *
     * <p>{@link Math#toIntExact} rather than a cast, because a silently wrapped contract count on a
     * ten-million-exposure book would report a negative population and read as a clean migration.
     * A count that does not fit is a defect and comes back as a 500, which is what a defect is.
     * Nothing here goes near a floating-point type — a count is exact and stays exact (ADR-0002).
     */
    private static int count(long value) {
        return Math.toIntExact(value);
    }
}
