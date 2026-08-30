package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code GET /api/contracts/{id}/trace?period=YYYYMM} — FR-808, the audit endpoint.
 *
 * <p><b>What this endpoint is for.</b> 06 § 2.3: it "resolves any published figure to its inputs",
 * target "under 30 seconds, self-service". The thirty seconds is the whole requirement. Every bank
 * can already answer "why is this number what it is" — by a week of somebody's time, a spreadsheet
 * reconstructed from a batch log, and an answer nobody can reproduce. This endpoint exists so that
 * the question is answered from the run that published the figure, in one request, by the person who
 * asked it. That is a different system, not a faster version of the same one.
 *
 * <p><b>The three things it will not do.</b>
 *
 * <ul>
 *   <li><b>It never recomputes to answer.</b> A trace that re-ran the pipeline would be describing a
 *       second computation. On a book that has not moved the two agree to the paise, which is
 *       precisely what makes the substitution invisible and worthless: the response would be
 *       evidence about the trace's own arithmetic and none at all about the published figure. It
 *       reads {@code EirService.runPapers}, which is the run's own working papers.
 *   <li><b>It never 404s a question it can answer part of.</b> Three genuinely different things
 *       produce no figures — this process has run no period, the run ran and quarantined this
 *       contract, or the book does not carry the contract at all — and an auditor needs to know
 *       which. A 404 collapses all three into "the endpoint is broken", which is how an audit
 *       endpoint stops being used. Each answers 200 with {@code answered:false} and says which.
 *   <li><b>It never fills a version id from a neighbouring kind.</b> This book registers no
 *       {@code POLICY_POSITION} version, so 06 § 2.3's {@code policyVersionId} comes back null. A
 *       figure attributed to a version that did not produce it is worse than a figure attributed to
 *       nothing, and it is the exact failure the version stamping exists to prevent.
 * </ul>
 *
 * <p><b>It derives two residuals rather than only echoing the row.</b> See
 * {@link TraceArithmetic}: the accrual re-derived at the stored rate, and the unamortised fee rolled
 * forward twice from two independently sourced cash figures. Echoing the stored row back would make
 * this endpoint a mirror, and a mirror cannot be evidence.
 *
 * <p><b>The route is a shared subtree, and this module claims only what is its own.</b> 06 § 2 and
 * § 3 put {@code GET /contracts/{id}}, {@code POST /contracts/{id}/events} and
 * {@code GET /contracts/{id}/trace} under one prefix, owned by different modules, and
 * {@code com.sun.net.httpserver} allows one handler per context. So {@link Routes#route} registers a
 * chain on {@code /api/contracts} and a handler returns {@code null} to decline a suffix that is not
 * its own. This handler claims exactly the paths ending {@code /trace}: a GET is the trace, any
 * other verb on that suffix is a 405 — a request it recognises and refuses, which the seam's own
 * javadoc distinguishes from one it does not recognise — and every other suffix is declined so the
 * contracts-and-events module gets it. Nothing here 404s on its own; the server does that when no
 * module claims a request, and it names the path.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 2.3.
 */
public final class TraceModule implements ApiModule {

    /** The subtree this module registers on, shared with the contracts-and-events module. */
    static final String PREFIX = "/api/contracts";

    /** The suffix that makes a request under {@link #PREFIX} this module's. */
    static final String SUFFIX = "/trace";

    /** {@code YYYYMM}, or {@code YYYY-MM} — the two spellings 06 uses. Nothing else. */
    private static final Pattern PERIOD = Pattern.compile("(\\d{4})-?(\\d{2})");

    private final EirService service;

    public TraceModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** The engine this module reads through. */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.route(PREFIX, this::trace);
    }

    @Override
    public String specSection() {
        return "06 § 2.3";
    }

    // ---- request ---------------------------------------------------------------------------

    /**
     * Claims a trace request, declines anything else on this subtree.
     *
     * <p>{@code null} means "not mine" and hands the request to the next module registered on the
     * prefix. It is used only for a suffix this module does not serve — never for a trace request it
     * refuses, because a refusal is a value here and comes back on a 200 with the whole reason.
     */
    private Routes.Answer trace(HttpExchange exchange, FormBody body) {
        String path = exchange.getRequestURI().getPath();
        if (!path.endsWith(SUFFIX)) {
            // /api/contracts, /api/contracts/{id}, /api/contracts/{id}/events, /api/contracts/
            // {id}/versions — all somebody else's. Declined, not refused.
            return null;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            // Recognised and refused, so an Answer rather than a decline: a 404 here would tell an
            // integrator the trace endpoint is not deployed, when it is and they used the wrong verb.
            return Routes.Answer.of(405, Json.object()
                .str("error", "GET only")
                .str("detail", "the trace is a read: GET " + PREFIX + "/{contractId}" + SUFFIX
                    + "?period=YYYYMM (06 § 2.3). Got " + exchange.getRequestMethod() + "."));
        }
        String contractId = contractIdIn(path);
        int periodId = periodIn(exchange.getRequestURI().getRawQuery());
        return Routes.Answer.ok(render(contractId, periodId));
    }

    /**
     * The contract id out of {@code /api/contracts/{id}/trace}.
     *
     * <p>A 400 rather than a decline where the shape is wrong past this point: the suffix already
     * said this is a trace request, so a missing or multi-segment id is the caller having sent a
     * malformed one, and handing it on would have the next module answering a question about a
     * contract nobody named.
     */
    private static String contractIdIn(String path) {
        String remainder = path.substring(
            Math.min(PREFIX.length(), path.length()), path.length() - SUFFIX.length());
        String id = remainder.startsWith("/") ? remainder.substring(1) : remainder;
        if (id.isBlank() || id.contains("/")) {
            throw new FormBody.BadRequest("a trace names exactly one contract; got '" + id
                + "' between " + PREFIX + "/ and " + SUFFIX);
        }
        return id.strip();
    }

    /**
     * The period, as {@code YYYYMM} or {@code YYYY-MM}.
     *
     * <p>Both spellings, because 06 § 2.3's own example writes {@code 2027-09} and the engine keys
     * everything on the {@code YYYYMM} integer {@code Book.periodIdOf} derives. Accepting one and
     * rejecting the other would make the specification and the implementation disagree in public.
     *
     * <p><b>Matched rather than stripped.</b> The first version deleted every hyphen and then
     * counted digits, which accepted {@code --202805}, {@code 2028-05-} and {@code 2-0-2-8-0-5} as
     * period 202805. A validator that normalises before it validates is not a validator; it accepts
     * a superset of what its own message promises, and on an audit endpoint the period is the one
     * input whose interpretation must be beyond argument.
     *
     * <p>Required, and not defaulted to the current period. A trace is evidence about a stated
     * period, and an endpoint that quietly answers about a different one than the caller typed is
     * the worst possible behaviour for this particular response.
     */
    private static int periodIn(String rawQuery) {
        String raw = FormBody.parse(rawQuery).text("period");
        Matcher matched = PERIOD.matcher(raw);
        if (!matched.matches()) {
            throw new FormBody.BadRequest("'period' must be YYYYMM or YYYY-MM, got '" + raw + "'");
        }
        int month = Integer.parseInt(matched.group(2));
        if (month < 1 || month > 12) {
            throw new FormBody.BadRequest("'period' names month " + month + " in '" + raw + "'");
        }
        return Integer.parseInt(matched.group(1)) * 100 + month;
    }

    // ---- response --------------------------------------------------------------------------

    private Json.Obj render(String contractId, int periodId) {
        Json.Obj response = Json.object()
            .str("contractId", contractId)
            .str("period", Integer.toString(periodId))
            // The figures come from the run's own papers; the inputs — terms, opening balances, the
            // flow vector, the cash book's split — come from the book as it stands now, because
            // that is the only place this in-memory implementation holds them. Those can differ:
            // POST /api/repair and POST /api/onboard both move the book without re-running the
            // period. Stamped rather than glossed, because an audit response that presents current
            // inputs as as-at-run inputs is the specific way a trace stops being evidence. A
            // production deployment reads both sides from eir-persistence at the run's recorded_at,
            // and then this field says AS_AT_RUN.
            .str("inputsAsAt", "CURRENT_BOOK")
            .str("inputsAsAtNote", "figures are the run's; inputs are the book's current ones. This"
                + " book is a map rather than a bitemporal store, so it cannot answer what it held"
                + " when the run read it (05 § 2).");

        Optional<Book.Holding> holding = service.holdingOnFile(contractId);
        Optional<EirService.RunPapers> papers = service.runPapers(periodId);

        if (papers.isEmpty()) {
            // No run, so no published figure, so nothing to resolve. Which of the two reasons it is
            // matters to the caller: a period nobody ran and a contract nobody carries are worked by
            // different desks.
            return response
                .bool("answered", false)
                .str("reason", "NO_RUN_FOR_PERIOD")
                .bool("contractOnBook", holding.isPresent())
                // No claim here about which period the book is positioned at. The first version
                // said "this book is positioned at period Seed.PERIOD_ID", a compile-time constant
                // from the demonstration seed, while EirService takes any Book — so on any other
                // book the audit endpoint stated a position it had never read. An endpoint that
                // asserts an unread fact is exactly what this one exists not to be.
                .str("detail", "no run for period " + periodId + " in this process, so no figure has"
                    + " been published for it and there is nothing to resolve to inputs. POST"
                    + " /api/run to roll the book's current period forward, then ask again for that"
                    + " period."
                    + (holding.isPresent() ? "" : " Separately, contract " + contractId
                        + " is not on the book at all."))
                .str("note", "an in-memory book holds only what this process ran. A production"
                    + " deployment answers from eir-persistence's AMORTISATION_RUN and"
                    + " PERIOD_BALANCE tables, which is what makes a trace of a period closed last"
                    + " March possible at all.");
        }

        EirService.RunPapers run = papers.orElseThrow();
        response.str("runId", run.runId());
        versionIds(run, response);

        Optional<ContractResult> result = run.resultFor(contractId);
        Optional<ContractComputation> computation = run.computationFor(contractId);

        if (result.isEmpty()) {
            return response
                .bool("answered", false)
                .str("reason", "CONTRACT_NOT_IN_POPULATION")
                .bool("contractOnBook", holding.isPresent())
                .str("detail", "run " + run.runId() + " accounted for "
                    + run.aggregate().populationSize() + " contract(s) and " + contractId
                    + " was not among them, so it published no figure for it."
                    + (holding.isPresent()
                        ? " The book carries the contract, so it joined after the run — roll the"
                            + " period forward again."
                        : " The book does not carry the contract either."));
        }

        if (computation.isEmpty()) {
            // The quarantine case, and the one this endpoint most has to get right. FR-905 isolates
            // a contract per contract; the run carried on and published figures for the others, and
            // for this one there is a reason rather than a figure. Reporting the reason under a 200
            // with answered:false is what lets an operator tell "quarantined" from "endpoint down".
            ExceptionRecord raised = liveExceptionFor(run, result.orElseThrow());
            response
                .bool("answered", false)
                .str("reason", "CONTRACT_QUARANTINED")
                .bool("contractOnBook", holding.isPresent())
                .str("exceptionCategory", raised.category().name())
                .str("exceptionStatus", raised.status().name())
                .bool("blocksClose", raised.blocksClose())
                .str("exceptionDetail", raised.describe())
                .str("acceptedOrResolvedBy", raised.resolvedBy())
                .str("detail", "run " + run.runId() + " isolated " + contractId + " and published"
                    + " no figure for it, so there is no figure to resolve to inputs. The inputs"
                    + " below are the book's CURRENT ones, not necessarily the ones the run read —"
                    + " see inputsAsAt.");
            holding.ifPresent(held -> inputsOnly(held, response));
            return response;
        }

        return figures(response, run, holding, computation.orElseThrow());
    }

    /**
     * The three version ids 06 § 1 requires on every response, plus the run's own stamp.
     *
     * <p><b>Both sources, and whether they agree.</b> {@code policyStamps} is what the run recorded
     * against itself; {@code aggregate.policyVersionIds()} is what {@code MonthEndRun} resolved from
     * the registry at the boundary. They are the same fact from two places, and {@code Seed}'s own
     * javadoc records the run in which they disagreed — a routing version stamped that the registry
     * did not carry, caught by DT-1 on a book where every figure was bit-identical. A trace that
     * printed one of them would have shown nothing.
     */
    private static void versionIds(EirService.RunPapers run, Json.Obj response) {
        String stampedRouting = run.policyStamps().get(PolicyKind.ROUTING_TABLE);
        List<String> fromRunRecord = run.aggregate().policyVersionIds();
        response
            // Null, deliberately. See the class javadoc: this book registers no POLICY_POSITION
            // version, and filling this from TIER_ASSIGNMENT or FEE_RULE_SET would attribute a
            // figure to a version that did not produce it.
            .str("policyVersionId", run.policyStamps().get(PolicyKind.POLICY_POSITION))
            .str("ruleSetVersionId", run.policyStamps().get(PolicyKind.FEE_RULE_SET))
            .str("routingTableVersionId", stampedRouting)
            .str("tierPolicyVersionId", run.policyStamps().get(PolicyKind.TIER_ASSIGNMENT))
            .strings("policyVersionsFromRunRecord", fromRunRecord)
            .bool("routingStampAgreesWithRunRecord",
                stampedRouting != null
                    && fromRunRecord.contains(PolicyKind.ROUTING_TABLE + "=" + stampedRouting));
    }

    /**
     * The exception as it now stands, not as the run froze it.
     *
     * <p><b>Why the live queue and not {@code ContractResult.exception()}.</b> {@code POST
     * /api/accept} records a four-eyes acceptance by rebuilding the run's queue; it leaves
     * {@code RunAggregate}'s own copy of each result untouched, because the aggregate is the run's
     * published arithmetic and an acceptance does not change a figure. So the frozen record still
     * reads {@code OPEN} and {@code BLOCKS CLOSE} after a valid acceptance, while
     * {@code PeriodCloseGate} weighs the accepted one and lets the close proceed. An audit endpoint
     * that contradicted the close gate on the single control that governs closing over an exception
     * would be worse than absent: it is the field an auditor would quote.
     *
     * <p>Falls back to the frozen record where the queue does not carry the contract, which is the
     * honest answer rather than a null — the run isolated it, so a record exists somewhere.
     */
    private static ExceptionRecord liveExceptionFor(EirService.RunPapers run, ContractResult result) {
        for (ExceptionRecord queued : run.exceptions()) {
            if (queued.contractId().equals(result.contractId())) {
                return queued;
            }
        }
        return result.exception();
    }

    /**
     * What the book carries for a contract the run published nothing for.
     *
     * <p><b>Named {@code Now}, because that is what it is.</b> These come from the live book, and
     * {@code POST /api/repair} can put an opening balance on file after the run that quarantined the
     * contract for not having one. Reported as {@code openingStateOnFile} the response would have
     * said "the run found no opening state" beside "the opening state is 528,407.32", which is a
     * self-contradicting audit answer. Reported as {@code Now}, with
     * {@code inputsRepairedSinceRun}, it is the most useful thing the endpoint can say: the input
     * has been fixed and the period has not been re-run.
     */
    private static void inputsOnly(Book.Holding holding, Json.Obj response) {
        response.bool("openingStateOnFileNow", holding.stateOnFile());
        if (holding.stateOnFile()) {
            ContractStateSource.OpeningState state = holding.state();
            response
                .bool("inputsRepairedSinceRun", true)
                .str("inputsRepairedNote", "the book now carries an opening balance for this"
                    + " contract and the run that quarantined it did not see one. Roll the period"
                    + " forward again: the contract will compute and the exception will clear.")
                .str("stageNow", state.stage().name())
                .figure("openingGcaNow", state.openingGca().atPresentationScale().amount());
        } else {
            response.bool("inputsRepairedSinceRun", false);
        }
        response.array("flowVector", flowRows(holding.period()))
            .str("flowVectorAnchor", holding.period().periodFlows().anchorDate().toString());
    }

    /**
     * The full answer: the computation, the flow vector, the roll-forward and the invariants.
     *
     * <p>The block names and their order follow 06 § 2.3's worked example, and the figure rendering
     * follows {@code EirService}'s: every figure a JSON string through {@code Json.Obj.figure},
     * every invariant through the same five fields the console reads. One shape, so that an operator
     * reading a trace beside a run response is reading the same fields.
     */
    private Json.Obj figures(
        Json.Obj response,
        EirService.RunPapers run,
        Optional<Book.Holding> holding,
        ContractComputation computation) {

        AmortisationRow row = computation.row();
        Rate stored = computation.eirAfter();

        response
            .bool("answered", true)
            .str("reason", "PUBLISHED")
            .obj("eirComputation", eirComputation(run, computation, row, stored))
            .str("stage", computation.decomposition().stage().name())
            .count("stageNumber", computation.decomposition().stage().ordinal() + 1);

        holding.ifPresent(held -> response
            .str("product", held.productId())
            .str("entity", held.entityId())
            .array("flowVector", flowRows(held.period()))
            .str("flowVectorAnchor", held.period().periodFlows().anchorDate().toString())
            .obj("cashBook", cashBook(held.period())));

        response.obj("rollForward", rollForward(holding, computation, row, stored));

        // Unconditional. The first version gated this on hasBalance() || charged != 0, and
        // hasBalance() is closingBalance.isPositive() — so a period whose only movements were a
        // recovery and a write-off (opening 5,000, recovered 5,000, closing nil, reachable through
        // ContractPeriod.withSuspense on a cured account) emitted no suspense block at all, and an
        // auditor asking why cash applied to interest does not tie to recognised income would find
        // the recovery leg nowhere on the response. Five figures is not worth a condition, and a
        // block that is sometimes absent is a block a caller has to guess about.
        SuspenseLedger suspense = computation.suspense();
        response.obj("suspense", Json.object()
            .figure("openingBalance", suspense.openingBalance().atPresentationScale().amount())
            .figure("chargedToSuspense",
                suspense.chargedToSuspense().atPresentationScale().amount())
            .figure("recovered", suspense.recovered().atPresentationScale().amount())
            .figure("writtenOff", suspense.writtenOff().atPresentationScale().amount())
            .figure("closingBalance", suspense.closingBalance().atPresentationScale().amount())
            .figure("netMovement", suspense.netMovement().atPresentationScale().amount())
            .str("detail", suspense.describe()));

        Stage3Reconciliation reconciliation = computation.reconciliation();
        if (reconciliation != null) {
            List<Json.Obj> legs = new ArrayList<>(Stage3Reconciliation.LEGS.size());
            for (int leg = 0; leg < Stage3Reconciliation.LEGS.size(); leg++) {
                legs.add(Json.object()
                    .str("leg", Stage3Reconciliation.LEGS.get(leg))
                    .figure("residual",
                        reconciliation.residualOn(leg).atPresentationScale().amount()));
            }
            response.obj("stageThreeFourWay", Json.object()
                .bool("reconciles", reconciliation.reconciles())
                .array("legs", legs)
                .obj("s3One", invariantRow(reconciliation.fourWay())));
        }

        RoutingDecision routing = computation.routing();
        response.obj("routing", routing == null ? null : Json.object()
            .str("driver", routing.driver().name())
            .str("rateType", routing.rateType().name())
            .str("mechanism", routing.mechanism().name())
            .str("routingTableVersionId", routing.routingTableVersionId())
            .bool("overriddenByRateTypeCheck", routing.overriddenByRateTypeCheck())
            .str("rationale", routing.rationale()));

        response.obj("catchUp", computation.catchUp() == null ? null : Json.object()
            .figure("amount", computation.catchUp().presentedCatchUp().amount())
            .bool("clean", computation.catchUp().isClean()));

        return response
            .array("invariantResults", invariantRows(computation.invariants()))
            .count("invariantsAsserted", computation.invariants().size())
            .count("invariantsBreached", computation.breaches().size())
            .array("runInvariantResults", invariantRows(run.aggregate().invariants()))
            .str("summary", computation.describe());
    }

    /**
     * 06 § 2.3's {@code eirComputation} block.
     *
     * <p><b>{@code solverMethod}, {@code iterations} and {@code residualAtStoredRate} are null on a
     * roll-forward, and that is the honest answer rather than a gap.</b> They belong to the solve
     * that produced the rate, and 05 § 3.2 puts the solve inside the event branch only — "a
     * fixed-rate contract with no events never re-solves", which is what makes the 10M-contract
     * target reachable. So a steady-state period has no iteration count to report, and inventing one
     * would describe a solve that did not happen. Where the rate <em>was</em> re-solved,
     * {@code solves} is non-zero and {@code trigger} names the routed mechanism that commissioned
     * it. What the persistent {@code EIR_COMPUTATION} row carries and this in-memory book does not
     * is said on the response rather than left to look like a defect.
     *
     * <p>{@code accrualAtStoredRate} is the part of this block that can fail — see
     * {@link TraceArithmetic}.
     */
    private static Json.Obj eirComputation(
        EirService.RunPapers run, ContractComputation computation, AmortisationRow row,
        Rate stored) {

        Money implied = TraceArithmetic.impliedEirInterest(
            row.openingGca(), stored, row.accrualExponent());
        Money residual = TraceArithmetic.accrualResidual(row.eirInterest(), implied);
        boolean reSolved = computation.solves() > 0;

        return Json.object()
            .str("computationId", "EIR-" + run.runId() + "-" + computation.contractId())
            .str("trigger", computation.routing() == null
                ? "PERIOD_ROLL_FORWARD"
                : computation.routing().mechanism().name())
            .figure("ratePeriodic", stored.periodic())
            // Rounded to RATE_SCALE, because 06 § 1 states rates as 12dp decimal strings and
            // effectiveAnnual() is a compounding of a 12dp rate, so it arrives at 27 places. The
            // twelve are the published ones; the rest are working precision leaking onto the wire.
            .figure("rateEffectiveAnnual",
                Precision.round(stored.effectiveAnnual(), Precision.RATE_SCALE))
            .figure("rateNominalAnnual",
                Precision.round(stored.nominalAnnual(), Precision.RATE_SCALE))
            .figure("ratePeriodicBroughtForward", computation.eirBefore().periodic())
            .bool("rateMoved", computation.rateMoved())
            .count("periodsPerYear", stored.periodsPerYear())
            .figure("accrualExponent", row.accrualExponent())
            .bool("wholePeriod", row.wholePeriod())
            .str("convention", row.wholePeriod()
                ? "PERIODIC_INDEX(x" + stored.periodsPerYear() + ")"
                : "BROKEN_PERIOD(tau=" + row.accrualExponent().toPlainString() + ")")
            .count("solves", computation.solves())
            .str("solverMethod", reSolved ? "BRACKETED_NEWTON" : null)
            .str("iterations", null)
            .figure("residualAtStoredRate", null)
            .str("status", reSolved ? "RE_SOLVED" : "CARRIED_FORWARD")
            .figure("openingCarryingAmount", row.openingGca().atPresentationScale().amount())
            .obj("accrualAtStoredRate", Json.object()
                .figure("impliedEirInterest", implied.atPresentationScale().amount())
                .figure("publishedEirInterest", row.presentedEirInterest().amount())
                .figure("residual", residual.atPresentationScale().amount())
                .figure("residualWorking", residual.amount())
                .bool("reproducesAtStoredRate", residual.isZero())
                .str("derivation", "openingGca * ((1 + ratePeriodic)^accrualExponent - 1),"
                    + " re-derived here from the row's own stated inputs. Non-nil means the run"
                    + " accrued at a rate or an exponent other than the one it published."))
            .str("note", reSolved
                ? "the rate was re-solved this period; the solver's iteration count and its own"
                    + " residual |f(r)| belong to the EIR_COMPUTATION row, which this in-memory"
                    + " book does not carry."
                : "no solve ran this period: the rate was carried forward from the contract's"
                    + " state (05 § 3.2). solverMethod, iterations and residualAtStoredRate are"
                    + " null because they describe a solve, and there was none — they are on the"
                    + " EIR_COMPUTATION row written at initial recognition.");
    }

    /**
     * 06 § 2.3's {@code rollForward} block, with the fee leg derived twice.
     *
     * <p>{@code contractualInterest} is the interest the CBS billed, from the contract's opening
     * state — not the cash the cash book applied to interest, which is reported separately under
     * {@code cashBook}. Those are two different facts and this engine's two-leg reconciliation
     * depends on them staying different.
     */
    private static Json.Obj rollForward(
        Optional<Book.Holding> holding, ContractComputation computation, AmortisationRow row,
        Rate stored) {

        Json.Obj block = Json.object()
            .figure("openingGca", row.presentedOpeningGca().amount())
            .figure("eirInterest", row.presentedEirInterest().amount())
            .figure("cashReceived", row.presentedCashReceived().amount())
            .figure("closingGca", row.presentedClosingGca().amount())
            .figure("closingGcaWorking", row.closingGca().amount())
            .figure("roundingResidue", row.presentedRoundingResidue().amount())
            .bool("presentedRowSums", row.presentedRowSums())
            // recognisedIncome is the ACPIR figure — nil where the stage suppresses recognition.
            // NOT ifrs9RecognisedIncome(), which is the net-basis figure retained for the
            // parallel-basis disclosure and is 3,304.08 on a Stage 3 contract that recognised
            // nothing. Publishing that under this name on an audit response would say the engine
            // recognised income it deliberately suppressed, beside an incomeSuppressed:true on the
            // same object. Both are here, each under its own name.
            .figure("recognisedIncome",
                computation.decomposition().recognisedIncome().atPresentationScale().amount())
            .bool("incomeSuppressed", computation.decomposition().incomeSuppressed())
            .figure("toSuspense",
                computation.decomposition().toSuspense().atPresentationScale().amount())
            .figure("ifrs9ParallelBasisIncome",
                computation.decomposition().ifrs9RecognisedIncome().atPresentationScale().amount())
            .figure("eclUnwind",
                computation.shadowUnwind().atPresentationScale().amount());

        if (holding.isEmpty() || !holding.orElseThrow().stateOnFile()) {
            return block.str("feeLegNote", "the contractual leg needs the contract's opening"
                + " contractual balance and the interest the CBS billed, and the book carries no"
                + " opening state for this contract, so the fee split is not derivable here.");
        }

        ContractStateSource.OpeningState state = holding.orElseThrow().state();
        ContractPeriod period = holding.orElseThrow().period();
        Money contractual = state.contractualInterestBilled();
        Money feeAmortised = TraceArithmetic.feeAmortised(row.eirInterest(), contractual);
        Money broughtForward = TraceArithmetic.unamortisedFeeBroughtForward(
            state.openingContractual(), state.openingGca());
        Money onSchedule = TraceArithmetic.unamortisedFeeCarriedForward(
            state.openingContractual(), state.openingGca(), row.eirInterest(), contractual);
        Money onCashBook = TraceArithmetic.unamortisedFeeFromCashBook(
            state.openingContractual(), contractual, period.cashApplied(), row.closingGca());
        Money legResidual = TraceArithmetic.feeLegResidual(onSchedule, onCashBook);

        return block
            .figure("contractualInterest", contractual.atPresentationScale().amount())
            .figure("feeAmortised", feeAmortised.atPresentationScale().amount())
            .figure("openingContractual", state.openingContractual().atPresentationScale().amount())
            .figure("unamortisedFeeBroughtForward",
                broughtForward.atPresentationScale().amount())
            .figure("unamortisedFee", onSchedule.atPresentationScale().amount())
            .figure("unamortisedFeeFromCashBook", onCashBook.atPresentationScale().amount())
            .figure("feeLegResidual", legResidual.atPresentationScale().amount())
            .figure("feeLegResidualWorking", legResidual.amount())
            .bool("feeLegsAgree", legResidual.isZero())
            .figure("cashAppliedByCashBook", period.cashApplied().atPresentationScale().amount())
            .str("feeLegDerivation", "feeAmortised = eirInterest - contractualInterest."
                + " unamortisedFee is rolled forward on the schedule's leg and again on the cash"
                + " book's leg from openingContractual; feeLegResidual is the gap, and it is the"
                + " amount misapplied where the cash book and the schedule disagree.")
            .str("rateApplied", stored.periodic().toPlainString());
    }

    private static List<Json.Obj> flowRows(ContractPeriod period) {
        List<CashFlow> flows = period.periodFlows().flows();
        List<Json.Obj> rows = new ArrayList<>(flows.size());
        for (CashFlow flow : flows) {
            rows.add(Json.object()
                .str("flowDate", flow.date().toString())
                .count("periodIndex", flow.periodIndex())
                .figure("amount", flow.amount().atPresentationScale().amount())
                .str("kind", flow.kind().name())
                .bool("contingent", flow.contingent()));
        }
        return rows;
    }

    private static Json.Obj cashBook(ContractPeriod period) {
        return Json.object()
            .figure("appliedToPrincipal",
                period.cashAppliedToPrincipal().atPresentationScale().amount())
            .figure("appliedToInterest",
                period.cashAppliedToInterest().atPresentationScale().amount())
            .figure("applied", period.cashApplied().atPresentationScale().amount())
            .count("periodOrdinal", period.periodOrdinal())
            .str("convention", period.convention().label())
            .bool("carriedAnEvent", period.hasEvent());
    }

    /**
     * The invariant rows, in {@code EirService}'s own shape plus {@code status}.
     *
     * <p>The five fields are the console's, so a trace and a run response are read the same way.
     * {@code status} is the sixth, because 06 § 2.3's worked example publishes
     * {@code {"id": "INV-4", "status": "PASS"}} and a caller written against the specification
     * should not have to translate a boolean.
     */
    private static List<Json.Obj> invariantRows(List<InvariantResult> results) {
        List<Json.Obj> rows = new ArrayList<>(results.size());
        for (InvariantResult result : results) {
            rows.add(invariantRow(result));
        }
        return rows;
    }

    private static Json.Obj invariantRow(InvariantResult result) {
        return Json.object()
            .str("id", result.id().name().replace('_', '-'))
            .str("status", result.satisfied() ? "PASS" : "FAIL")
            .str("statement", result.id().statement())
            .bool("satisfied", result.satisfied())
            .figure("deviation", result.deviation())
            .str("detail", result.detail());
    }
}
