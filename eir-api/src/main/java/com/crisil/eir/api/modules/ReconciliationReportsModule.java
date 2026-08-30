package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.reconciliation.FloorDuality;
import com.crisil.eir.api.modules.reconciliation.PeriodReconciliations;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.calc.amort.FloorBasis;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.posting.AccountReconciliation;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.gl.posting.GlReconciliation;
import com.crisil.eir.policy.reconciliation.ContractReconciliation;
import com.crisil.eir.policy.reconciliation.CoreBankingReconciliation;
import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The four reconciliation reports of 06 § 7: sub-ledger to GL, contractual leg to CBS, the Stage 3
 * four-way, and the pre-/post-floor duality.
 *
 * <table>
 *   <caption>The four endpoints and the evaluator behind each</caption>
 *   <tr><th>Endpoint</th><th>Control</th><th>Evaluator</th></tr>
 *   <tr><td>{@code GET /api/reports/reconciliation/gl?period=}</td><td>SL-1</td>
 *       <td>{@link GlReconciliation#tiesToGl()}</td></tr>
 *   <tr><td>{@code GET /api/reports/reconciliation/cbs?period=}</td><td>RC-1</td>
 *       <td>{@link CoreBankingReconciliation#tiesToCoreBanking()}</td></tr>
 *   <tr><td>{@code GET /api/reports/reconciliation/stage3?period=}</td><td>S3-1</td>
 *       <td>{@link Stage3Reconciliation}, one per suppressed contract</td></tr>
 *   <tr><td>{@code GET /api/reports/ecl-floor-duality?period=}</td><td>PF-1 (and PF-2 on request)
 *       </td><td>{@code FloorApplication.preFloorRetained} / {@code basisPermitted}</td></tr>
 * </table>
 *
 * <h2>This module renders; it does not decide</h2>
 *
 * <p>Every figure below is published by an evaluator that already existed and already had exactly
 * one right to publish it. {@link PeriodReconciliations} and {@link FloorDuality} gather the inputs
 * and invoke them; this class turns the results into JSON and adds not one subtraction of its own.
 * That division is the point: a report that differenced two sides itself would put a second answer
 * under an identifier entitled to one, which docs/08 records this codebase finding three times, and
 * which is why {@code RunClose} throws rather than accepts a caller-supplied {@code SUBLEDGER_TO_GL}
 * tie even when the second answer would agree.
 *
 * <h2>Two caveats these responses carry, because a reader must not mistake either for a tie</h2>
 *
 * <p><b>SL-1's two sides are not independently sourced on this book.</b> {@code Book.generalLedger}
 * sums the book's own positions, so the comparison SL-1 exists to make — the sub-ledger's detail
 * against what the general ledger independently says its control account stands at — is not
 * available here. It is still <em>reached with a figure on each side</em>, and the sequence is real:
 * before {@code POST /api/post} the ledger carries opening balances and SL-1 is red by the period's
 * own movement, which is the correct answer to a close attempted before posting; after posting the
 * ledger is handed the sub-ledger's own closing total and SL-1 is green <em>by construction</em>. A
 * production {@code GeneralLedgerSource} reads the bank's trial balance, and then a green SL-1 means
 * something. Every {@code /gl} response says so.
 *
 * <p><b>No regulatory floor is supplied, so PF-1 is nil against nil.</b> A nil-against-nil duality
 * reads as tied on every run ever made — which is exactly why {@code RunClose} refuses to
 * synthesise the pre-/post-floor tie — so every {@code /ecl-floor-duality} response labels it
 * rather than presenting two equal columns as agreement. {@link FloorDuality}'s javadoc carries the
 * longer form, including why PF-2 is withheld unless the caller states the basis.
 *
 * <h2>Status codes</h2>
 *
 * <p>200 for every engine answer, including "no run has published this period" — a refusal is a
 * value here and the whole answer comes back. 400 only for a malformed request: an absent
 * {@code period}, one that is not a number, one that is not a {@code YYYYMM} accounting period, or
 * a {@code basis} naming no ACPIR 90 basis. Nothing here maps a refusal onto a 4xx, because a
 * report that says "your request was bad" when the answer is "that period never ran" sends an
 * integrator looking in the wrong place.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7.
 */
public final class ReconciliationReportsModule implements ApiModule {

    /** The section every response stamps itself with, so a drifting report is visibly wrong. */
    private static final String SPEC_SECTION = "06 § 7";

    private final EirService service;

    public ReconciliationReportsModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get("/api/reports/reconciliation/gl", this::subLedgerToGl);
        routes.get("/api/reports/reconciliation/cbs", this::contractualLegToCbs);
        routes.get("/api/reports/reconciliation/stage3", this::stageThreeFourWay);
        routes.get("/api/reports/ecl-floor-duality", this::floorDuality);
    }

    @Override
    public String specSection() {
        return SPEC_SECTION;
    }

    // ============================================================ SL-1: sub-ledger to GL

    /**
     * SL-1 for one period: the sub-ledger's contract detail against the GL's control accounts.
     *
     * <p>On the seed book, before the journals are posted: the sub-ledger carries C-0001's closing
     * 486,840.64 and C-0002's 533,914.11 while the ledger still carries the two opening balances of
     * 528,407.32 each, and SL-1 is red by 36,059.88 — which is 2 × 5,506.7926 of EIR interest less
     * the 47,073.47 instalment, the period's own movement and nothing wrong. After
     * {@code POST /api/post} the ledger carries 1,020,754.76 and the account ties.
     */
    private Json.Obj subLedgerToGl(HttpExchange exchange) {
        int periodId = period(exchange);
        Optional<EirService.PublishedFigures> published = service.publishedRun(periodId);
        if (published.isEmpty()) {
            return notPublished("SL-1", periodId, "sub-ledger to general ledger");
        }
        PeriodReconciliations reconciliations = PeriodReconciliations.over(published.get());
        GlReconciliation gl = reconciliations.subLedgerToGl();

        List<Json.Obj> accounts = new ArrayList<>();
        for (AccountReconciliation line : gl.lines()) {
            accounts.add(Json.object()
                .str("accountCode", line.accountCode())
                .count("subLedgerBalances", line.contractCount())
                .figure("subLedgerTotal", presented(line.subLedgerTotal()))
                // Null rather than nil where the GL supplied nothing: a control account the ledger
                // reported at zero and one it never reported give the same arithmetic and are very
                // different findings, and the second is usually a missing mapping.
                .figure("glBalance",
                    line.glBalanceSupplied() ? presented(line.glBalance()) : null)
                .bool("glBalanceSupplied", line.glBalanceSupplied())
                .figure("difference", presented(line.difference()))
                .figure("differenceWorking", line.difference().amount())
                .figure("explained", presented(line.explained()))
                .figure("unexplained", presented(line.unexplained()))
                .bool("ties", line.ties())
                .bool("agreesBeforeExplanation", line.agreesBeforeExplanation())
                .count("explanations", line.explanations().size())
                .bool("carriesUnmatchedExplanation", line.carriesUnmatchedExplanation()));
        }

        List<Json.Obj> reported = new ArrayList<>();
        for (GlControlAccountBalance balance : reconciliations.glBalancesReported()) {
            reported.add(Json.object()
                .str("accountCode", balance.accountCode())
                .str("bookId", balance.bookId())
                .figure("balance", presented(balance.balance()))
                // The source reference is what distinguishes a ledger that has taken the period's
                // journals from one that has not. Without it a red SL-1 before posting and a red
                // SL-1 after it look identical on the report, and only one of them is a break.
                .str("sourceRef", balance.sourceRef()));
        }

        return header("SL-1", periodId, "sub-ledger to general ledger", published.get())
            .str("bookId", gl.bookId())
            .str("currency", gl.currency().getCurrencyCode())
            .obj("invariant", invariant(gl.tiesToGl()))
            .figure("subLedgerTotal", presented(reconciliations.subLedgerToGlSide(true)))
            .figure("glTotal", presented(reconciliations.subLedgerToGlSide(false)))
            .figure("unexplainedTotal", presented(gl.unexplainedTotal()))
            .figure("explainedTotal", presented(gl.explainedTotal()))
            .figure("selfReversingTotal", presented(gl.selfReversingTotal()))
            .count("explanationCount", gl.explanationCount())
            .count("controlAccounts", gl.lines().size())
            .count("accountsBroken", gl.breaks().size())
            .count("contractsOnTheSubLedgerSide", gl.contractCount())
            .array("accounts", accounts)
            .array("glBalancesReported", reported)
            .obj("population", population(published.get().aggregate()))
            .strings("caveats", withPopulationCaveat(published.get().aggregate(), List.of(
                "SL-1's two sides are NOT independently sourced on this book. Book.generalLedger"
                    + " sums the book's own positions, so a green SL-1 here is green by"
                    + " construction rather than by agreement: POST /api/post hands the ledger the"
                    + " sub-ledger's own closing total. A production GeneralLedgerSource reads the"
                    + " bank's trial balance, and then this control can genuinely go red.",
                "Before the journals are posted the tie IS red — by the period's own movement, not"
                    + " by a disagreement. Read sourceRef on each glBalancesReported entry:"
                    + " -PRE-POSTING means the ledger has not taken this period's journals yet,"
                    + " and a close attempted in that state is refused with SL-1 red, correctly.",
                "This book files no explained differences, so explainedTotal is nil on every"
                    + " account. An explanation can only ever reduce a difference, so a nil"
                    + " explained total means the unexplained figure is the whole difference.")));
    }

    // ==================================================== RC-1: contractual leg to core banking

    /**
     * RC-1 for one period: the engine's contractual interest leg against what the CBS billed.
     *
     * <p>On the seed book, before C-0003 is repaired: the engine presents lines for the two
     * contracts it computed and the CBS feed presents all three, so C-0003 is a one-sided line
     * worth the whole of its billed 5,298.16 and RC-1 is red by exactly that. That is a
     * <em>presence</em> break — the borrower was billed and the engine never projected the contract
     * — and it is the break an amount comparison could never find. After
     * {@code POST /api/repair} and another run, all three tie.
     */
    private Json.Obj contractualLegToCbs(HttpExchange exchange) {
        int periodId = period(exchange);
        Optional<EirService.PublishedFigures> published = service.publishedRun(periodId);
        if (published.isEmpty()) {
            return notPublished("RC-1", periodId, "contractual leg to core banking");
        }
        PeriodReconciliations reconciliations = PeriodReconciliations.over(published.get());
        CoreBankingReconciliation cbs = reconciliations.contractualLegToCbs();

        List<Json.Obj> contracts = new ArrayList<>();
        for (ContractReconciliation line : cbs.lines()) {
            contracts.add(Json.object()
                .str("contractId", line.contractId())
                // Both nullable, and null is the finding: it says which source did not present
                // this contract at all. presence() names the same fact in one word.
                .figure("engineContractualInterest", line.engineContractualInterest() == null
                    ? null : presented(line.engineContractualInterest()))
                .figure("cbsBilledInterest", line.cbsBilledInterest() == null
                    ? null : presented(line.cbsBilledInterest()))
                .str("presence", line.presence().name())
                .figure("difference", presented(line.difference()))
                .figure("differenceWorking", line.difference().amount())
                .figure("explained", presented(line.explainedAmount()))
                .figure("unexplained", presented(line.unexplainedDifference()))
                .bool("tied", line.isTied())
                .count("explanations", line.explanations().size())
                .count("ineffectiveExplanations", line.ineffectiveExplanations().size())
                .bool("hasMisstatedExplanation", line.hasMisstatedExplanation()));
        }

        return header("RC-1", periodId, "contractual leg to core banking", published.get())
            .str("currency", cbs.currency().getCurrencyCode())
            .obj("invariant", invariant(cbs.tiesToCoreBanking()))
            .figure("totalEngineContractualInterest",
                presented(cbs.totalEngineContractualInterest()))
            .figure("totalCbsBilledInterest", presented(cbs.totalCbsBilledInterest()))
            .figure("netUnexplainedDifference", presented(cbs.netUnexplainedDifference()))
            .figure("totalAbsoluteUnexplainedDifference",
                presented(cbs.totalAbsoluteUnexplainedDifference()))
            .figure("totalExplainedDifference", presented(cbs.totalExplainedDifference()))
            .count("contractsReconciled", cbs.contractsReconciled())
            .count("contractsBroken", cbs.breaks().size())
            .count("presenceBreaks", cbs.presenceBreaks().size())
            .count("misstatedExplanations", cbs.misstatedExplanations().size())
            .count("danglingExplanations", cbs.danglingExplanations().size())
            .array("contracts", contracts)
            .obj("population", population(published.get().aggregate()))
            .strings("caveats", withPopulationCaveat(published.get().aggregate(), List.of(
                "RC-1's AMOUNT leg cannot disagree on this book, and its PRESENCE leg can. The"
                    + " engine's contractual leg and the CBS feed both read"
                    + " OpeningState.contractualInterestBilled, so two figures for one contract are"
                    + " the same figure twice; a production CoreBankingFeed is an extract from the"
                    + " core banking system and then the amounts can differ too.",
                "The CBS side is scoped to the whole population rather than to what the engine"
                    + " computed, and that is the only reason a dropped contract is visible here at"
                    + " all: a feed scoped to the engine's own output makes a shortfall invisible"
                    + " to every reconciliation.",
                "ADR-0004 makes the CBS the book of record for what the borrower was billed. A"
                    + " positive difference means the engine projected MORE contractual interest"
                    + " than the borrower was billed, on every line and on every explanation.")));
    }

    // ================================================================ S3-1: the four-way

    /**
     * S3-1 for one period: the four quantities 03 § 7.3 requires, per suppressed contract.
     *
     * <p>The four are the gross carrying amount roll-forward, the ECL shadow unwind, the suspense
     * ledger and recognised income. They do not live in one place — which is exactly why S3-1 was
     * once published from four sites, none of which was the reconciliation the id names — and
     * {@link Stage3Reconciliation} is the level at which all four are in view.
     *
     * <p>On the seed book, C-0002: opening gross 528,407.32 accrues 5,506.79 on the gross basis and
     * receives nothing, closing 533,914.11; the whole 5,298.16 of billed contractual interest is
     * charged to suspense and nil is recognised. All four legs are nil and S3-1 passes.
     */
    private Json.Obj stageThreeFourWay(HttpExchange exchange) {
        int periodId = period(exchange);
        Optional<EirService.PublishedFigures> published = service.publishedRun(periodId);
        if (published.isEmpty()) {
            return notPublished("S3-1", periodId, "the Stage 3 four-way");
        }
        PeriodReconciliations reconciliations = PeriodReconciliations.over(published.get());

        List<Json.Obj> rows = new ArrayList<>();
        for (PeriodReconciliations.StageThreeLine line : reconciliations.stageThree()) {
            Stage3Reconciliation fourWay = line.fourWay();

            // The four legs, each with its own signed residual. residualOn is indexed from 0 and
            // LEGS names them in the same order — reading residualsByLeg from 1 was the off-by-one
            // that the first close request turned into an ArrayIndexOutOfBounds.
            List<Json.Obj> legs = new ArrayList<>(Stage3Reconciliation.LEGS.size());
            for (int leg = 0; leg < Stage3Reconciliation.LEGS.size(); leg++) {
                Money residual = fourWay.residualOn(leg);
                legs.add(Json.object()
                    .count("leg", leg + 1)
                    .str("name", Stage3Reconciliation.LEGS.get(leg))
                    .figure("residual", presented(residual))
                    .figure("residualWorking", residual.amount())
                    .bool("reconciles", residual.signum() == 0));
            }

            rows.add(Json.object()
                .str("contractId", line.contractId())
                .str("stage", fourWay.period().stage().name())
                // Quantity 1: the carrying-amount roll-forward, from the ledger.
                .figure("openingGrossCarryingAmount", presented(fourWay.openingGross()))
                .figure("closingGrossCarryingAmount", presented(fourWay.closingGross()))
                .figure("grossBasisInterest", presented(fourWay.period().grossBasisInterest()))
                .figure("cashAppliedToPrincipal", presented(fourWay.cashAppliedToPrincipal()))
                .figure("cashAppliedToInterest", presented(fourWay.cashAppliedToInterest()))
                // Quantity 2: the ECL discount unwind. Computed, retained, and posted nowhere —
                // ACPIR 50 requires it because ECL is a present value discounted at the EIR, and
                // says nothing about where it goes. It is never P&L (FR-603).
                .figure("shadowUnwind", presented(fourWay.period().shadowUnwind()))
                // Quantity 3: the suspense ledger, every movement a positive magnitude with the
                // direction in the field's name.
                .figure("suspenseOpening", presented(fourWay.suspense().openingBalance()))
                .figure("chargedToSuspense", presented(fourWay.suspense().chargedToSuspense()))
                .figure("recoveredFromSuspense", presented(fourWay.suspense().recovered()))
                .figure("writtenOffFromSuspense", presented(fourWay.suspense().writtenOff()))
                .figure("suspenseClosing", presented(fourWay.suspense().closingBalance()))
                // Quantity 4: recognised income, nil while recognition is suppressed.
                .figure("recognisedIncome", presented(fourWay.period().recognisedIncome()))
                .figure("contractualInterestBilled",
                    presented(fourWay.contractualInterestBilled()))
                .bool("incomeSuppressed", fourWay.period().incomeSuppressed())
                .array("legs", legs)
                .obj("invariant", invariant(fourWay.fourWay()))
                .bool("reconciles", fourWay.reconciles()));
        }

        int suppressed = reconciliations.stageThree().size();
        return header("S3-1", periodId, "the Stage 3 four-way", published.get())
            .strings("quantities", List.of(
                "gross carrying amount", "ECL shadow unwind",
                "interest in suspense", "recognised income"))
            .strings("legs", Stage3Reconciliation.LEGS)
            .count("contractsWithRecognitionSuppressed", suppressed)
            .count("fourWaysReconciling", reconciliations.stageThreeReconciling())
            .count("fourWaysBroken", suppressed - reconciliations.stageThreeReconciling())
            .figure("totalAbsoluteResidual", reconciliations.stageThreeTotalAbsoluteResidual())
            .array("contracts", rows)
            .obj("population", population(published.get().aggregate()))
            .strings("caveats", withPopulationCaveat(published.get().aggregate(), List.of(
                "There is no portfolio-level S3-1 row here, deliberately. S3-1 has exactly one"
                    + " publication site per contract — Stage3Reconciliation's own constructor"
                    + " refuses a result carrying any other id — and totalAbsoluteResidual is the"
                    + " sum of those results' deviations, which is the figure the close gate's"
                    + " Stage 3 tie carries. It is not a fifth S3-1.",
                "The total is ABSOLUTE. Two contracts broken in opposite directions must not net"
                    + " to a reconciled period, which is the classic way this control passes while"
                    + " being broken twice.",
                suppressed == 0
                    ? "NO contract in this period had recognition suppressed, so no S3-1 was"
                        + " asserted at all. That is an absence, not a tie: a period with no"
                        + " Stage 3 exposure has nothing to reconcile, and an empty report and a"
                        + " clean one are different answers."
                    : suppressed + " contract(s) had recognition suppressed, so S3-1 is asserted"
                        + " over that many exposures and over no others. A contract the run"
                        + " quarantined has no four-way at all — see population.")));
    }

    // ======================================================= PF-1: the pre-/post-floor duality

    /**
     * PF-1 for one period: the accounting ECL measured at the EIR and the reported provision, side
     * by side, per exposure.
     *
     * <p>03 § 7.5's ordering is compute at the EIR, apply the floor, report <em>both</em>. The
     * forbidden implementation is the natural one — compute, floor, store the result — after which
     * the figure the EIR produced does not exist anywhere and the divergence cannot be disclosed.
     *
     * <p>On this book the floor is nil on every exposure, so the two columns hold the same figure
     * and PF-1 is nil against nil. See {@link FloorDuality} for why that is labelled rather than
     * reported as agreement, and why PF-2 appears only when {@code ?basis=} states one.
     */
    private Json.Obj floorDuality(HttpExchange exchange) {
        int periodId = period(exchange);
        FormBody query = query(exchange);
        Optional<FloorBasis> basis;
        try {
            basis = FloorDuality.basisNamed(query.textOr("basis", null));
        } catch (IllegalArgumentException notABasis) {
            // A malformed request, not a refusal: the caller named something that is not an ACPIR
            // 90 basis, and defaulting it would assert PF-2 over an input nobody supplied.
            throw new FormBody.BadRequest(notABasis.getMessage());
        }

        Optional<EirService.PublishedFigures> published = service.publishedRun(periodId);
        if (published.isEmpty()) {
            return notPublished("PF-1", periodId, "the pre-/post-floor duality");
        }
        FloorDuality duality = FloorDuality.over(published.get(), basis);

        List<Json.Obj> exposures = new ArrayList<>();
        for (FloorDuality.Exposure exposure : duality.exposures()) {
            Json.Obj row = Json.object()
                .str("contractId", exposure.contractId())
                .str("stage", exposure.stage().name())
                .str("eclEngineVersion", exposure.eclEngineVersion())
                .figure("preFloorAccountingEcl",
                    presented(exposure.application().accountingEcl()))
                .figure("regulatoryFloor", presented(exposure.application().regulatoryFloor()))
                .figure("postFloorReportedProvision",
                    presented(exposure.application().reportedProvision()))
                .figure("flooredBy", presented(exposure.application().flooredBy()))
                .bool("floorBinds", exposure.application().floorBinds())
                .str("basisApplied", exposure.application().basis().name())
                .str("duality", exposure.application().describe())
                .obj("preFloorRetained", invariant(exposure.preFloorRetained()));
            // Present only when the caller stated the basis. A PF-2 row over a basis this module
            // chose would be a control asserted about its own default.
            row.obj("basisPermitted", exposure.basisPermitted() == null
                ? null : invariant(exposure.basisPermitted()));
            exposures.add(row);
        }

        return header("PF-1", periodId, "the pre-/post-floor duality", published.get())
            .bool("regulatoryFloorSupplied", false)
            .bool("floorBasisStated", duality.basisStated())
            .str("floorBasisStatedAs",
                duality.basisStated() ? duality.statedBasis().name() : null)
            .bool("pf2Asserted", duality.basisStated())
            .figure("totalPreFloorAccountingEcl", presented(duality.totalAccountingEcl()))
            .figure("totalPostFloorReportedProvision",
                presented(duality.totalReportedProvision()))
            .figure("totalFlooredBy", presented(duality.totalFlooredBy()))
            .count("exposures", duality.exposures().size())
            .count("exposuresWhereTheFloorBinds", duality.bindingCount())
            .count("resultsPublished", duality.results().size())
            .count("breaches", duality.breaches().size())
            .array("exposureRows", exposures)
            .strings("exposuresWithoutStateOnFile", duality.exposuresWithoutState())
            .obj("population", population(published.get().aggregate()))
            .strings("caveats", withPopulationCaveat(published.get().aggregate(), List.of(
                "NO regulatory floor is supplied by this book, so the floor is nil on every"
                    + " exposure and the pre-floor and post-floor columns hold the same figure."
                    + " A nil-against-nil duality reads as tied on every run ever made, which is"
                    + " why RunClose refuses to synthesise the pre-/post-floor tie. Do not read"
                    + " these equal columns as agreement — nothing was floored.",
                "PF-1 here is asserted over a pair this report computed, so it cannot go red."
                    + " preFloorRetained becomes a real control the moment a reported provision is"
                    + " read back from a store rather than from the arithmetic that produced it —"
                    + " which is what its own javadoc means by a caller asserting it about the"
                    + " pair of columns it wrote. Nothing in this engine persists one yet.",
                duality.basisStated()
                    ? "PF-2 IS asserted, against the basis you stated (" + duality.statedBasis()
                        + "). It is a control that can fail: ACPIR 90 makes account-level"
                        + " mandatory in Stage 3, so a PORTFOLIO basis breaks it on every"
                        + " suppressed exposure with the reported provision as the deviation."
                    : "PF-2 is NOT asserted, because no floor basis was stated and this book"
                        + " records none. Defaulting it to ACCOUNT would pass in every stage —"
                        + " a control asserted over a fabricated input, which is worse than an"
                        + " absent one. Pass ?basis=ACCOUNT or ?basis=PORTFOLIO to assert it.",
                "The ECL itself is consumed, not computed (00 § 4). eclEngineVersion names the"
                    + " model that measured each pre-floor figure, because 04 § 2.9 requires the"
                    + " version to travel with the figure or no replay can reproduce it.")));
    }

    // ================================================================= rendering helpers

    /**
     * The fields every one of the four responses opens with.
     *
     * <p>{@code specSection} is stamped rather than derived so that a report which drifts from its
     * specification section is visibly wrong instead of quietly renamed, which is the same argument
     * {@link ApiModule#specSection()} makes.
     */
    private static Json.Obj header(
        String report, int periodId, String purpose, EirService.PublishedFigures figures) {
        return Json.object()
            .str("report", report)
            .str("purpose", purpose)
            .str("specSection", SPEC_SECTION)
            .count("periodId", periodId)
            .bool("available", true)
            .str("runId", figures.runId());
    }

    /**
     * The answer for a period no run has published.
     *
     * <p><b>200, and {@code available:false} rather than an empty report.</b> Every total over an
     * empty population is nil and every reconciliation over it ties, so a report that rendered its
     * usual shape with nil figures would be indistinguishable from a period in which everything
     * reconciled. That is the defect this whole codebase is most careful about, and at a reporting
     * endpoint it is at its most dangerous, because the reader is looking for a green row.
     */
    private static Json.Obj notPublished(String report, int periodId, String purpose) {
        return Json.object()
            .str("report", report)
            .str("purpose", purpose)
            .str("specSection", SPEC_SECTION)
            .count("periodId", periodId)
            .bool("available", false)
            .str("message", "no run has published figures for period " + periodId + " in this"
                + " process, so there is nothing to reconcile — which is NOT the same answer as a"
                + " period whose reconciliations tie. Every total over an empty population is nil"
                + " and every reconciliation over it agrees. Roll the period forward first"
                + " (POST /api/run).");
    }

    /** One invariant result, rendered exactly as the evaluator published it. */
    private static Json.Obj invariant(InvariantResult result) {
        return Json.object()
            .str("id", result.id().name().replace('_', '-'))
            .str("statement", result.id().statement())
            .bool("satisfied", result.satisfied())
            .figure("deviation", result.deviation())
            .str("detail", result.detail());
    }

    /**
     * The population accounting, on every response.
     *
     * <p><b>Why a reconciliation report has to carry it.</b> A run over ten million contracts that
     * silently processed 9,999,998 reconciles perfectly: the two it dropped are absent from both
     * sides of every total. So the counts travel with the figures, and a reader can see what the
     * reconciliation was over before believing what it says.
     */
    private static Json.Obj population(RunAggregate aggregate) {
        return Json.object()
            .count("populationSize", aggregate.populationSize())
            .count("computed", aggregate.computedCount())
            .count("quarantined", aggregate.quarantinedCount())
            .count("unaccountedFor", aggregate.unaccountedFor())
            .bool("runReportsCleanClose", aggregate.reportsCleanClose())
            .strings("quarantinedContracts", aggregate.quarantinedContracts());
    }

    /**
     * The report's own caveats, plus the population one where the run did not measure everything.
     *
     * <p>Appended rather than left to the {@code population} block, because a caveat list is what a
     * reader of a control report actually reads. A quarantined contract is absent from both sides
     * of every figure above it.
     */
    private static List<String> withPopulationCaveat(
        RunAggregate aggregate, List<String> own) {
        int missing = aggregate.quarantinedCount() + aggregate.unaccountedFor();
        if (missing == 0) {
            return own;
        }
        List<String> caveats = new ArrayList<>(own);
        caveats.add("This reconciliation is over " + aggregate.computedCount() + " of "
            + aggregate.populationSize() + " contracts. " + aggregate.quarantinedCount()
            + " quarantined and " + aggregate.unaccountedFor() + " unaccounted for are absent from"
            + " both sides of every figure above, so a tie here is a tie over a partial"
            + " population: " + aggregate.quarantinedContracts()
            + ". Work the exception (POST /api/repair) rather than reading the tie.");
        return List.copyOf(caveats);
    }

    /** A figure at the currency's presentation scale, which is what a report column shows. */
    private static BigDecimal presented(Money amount) {
        return amount.atPresentationScale().amount();
    }

    // ================================================================= request parsing

    /**
     * The {@code period} query parameter, as a {@code YYYYMM} accounting period.
     *
     * <p>Required and never defaulted to the book's own period. A report that answered for whatever
     * period it happened to hold when the caller asked for another is a report filed against the
     * wrong month, and 04 § 2.13's shape is checked here for the reason {@code RunRequest} checks
     * it: a malformed period id partitions the ledger into a partition nobody will look in.
     */
    private static int period(HttpExchange exchange) {
        int periodId = query(exchange).integer("period");
        if (periodId < 190001 || periodId > 999912
            || periodId % 100 < 1 || periodId % 100 > 12) {
            throw new FormBody.BadRequest("'period' must be a YYYYMM accounting period"
                + " (04 § 2.13), got " + periodId);
        }
        return periodId;
    }

    /**
     * The query string, parsed by the same reader the POST bodies use.
     *
     * <p>A GET's query string and a form body are the same grammar — {@code k=v&amp;k=v},
     * percent-encoded — so this reuses {@link FormBody} rather than adding a second parser with a
     * second set of edge cases. It also inherits the property that matters: every accessor refuses
     * rather than defaults.
     */
    private static FormBody query(HttpExchange exchange) {
        return FormBody.parse(exchange.getRequestURI().getRawQuery());
    }
}
