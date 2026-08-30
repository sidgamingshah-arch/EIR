package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.movement.MovementSchedule;
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * GET /api/reports/movement — FR-805, and the published columns must SUM
 *
 * <p><b>What this endpoint is for.</b> A period movement schedule: opening gross carrying amount,
 * the period's EIR accrual, the cash received, and the closing balance, per product and in total.
 * It is the disclosure a finance reader checks first, because checking it needs no system access —
 * one subtraction on the page. So FR-805's requirement is not that the schedule be rendered but
 * that its columns <b>sum</b>, and {@link MovementSchedule} asserts that with a four-leg check whose
 * deviation is published beside the figures. A schedule whose columns do not sum is a defect even
 * when every figure in it is individually correct (03 § 5.7).
 *
 * <p><b>This handler decides nothing.</b> It reads two query parameters, asks
 * {@link EirService#lastRunFor(int)} for the run that published the figures, hands them to
 * {@code MovementSchedule}, and renders what came back — including the check when it is red, the
 * contracts the schedule could not carry, and every caveat. {@code ApiModule}'s contract is
 * that the moment a handler starts choosing which refusals to report, the distinction the engine is
 * built on is gone at the edge.
 *
 * <h2>The three answers, and their status codes</h2>
 *
 * <ul>
 *   <li><b>400</b> — {@code period} absent or not a number. That is the one malformed request this
 *       endpoint has, and {@code EirService}'s own javadoc names it as the example of a 4xx. A
 *       period is not defaulted to "the current one": a movement schedule is a statement about a
 *       named period, and answering for a period the caller did not ask about is worse than
 *       refusing.</li>
 *   <li><b>200, {@code "ran": false}</b> — a well-formed request for a period this process has not
 *       run. Not a 404: the endpoint exists and the engine has an answer, which is that there are no
 *       figures. Reporting a schedule of zeroes here would publish a clean nil movement for a book
 *       that was never rolled forward.</li>
 *   <li><b>200</b> — the schedule, with the check green or red. A red check is an answer, not an
 *       error; the whole list comes back.</li>
 * </ul>
 *
 * <p><b>{@code productId} is a filter and not a lookup.</b> A filter matching nothing returns 200
 * with {@code "filterMatched": false} and the products the run's population actually carries, rather
 * than an empty page. An empty movement schedule that looks like a clean nil is the failure mode
 * this endpoint is most likely to be misread as producing.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7.
 */
public final class MovementReportModule implements ApiModule {

    /** The route, named once so the test and the module cannot drift apart. */
    public static final String PATH = "/api/reports/movement";

    private final EirService service;

    public MovementReportModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** The engine this module reads through. */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        routes.get(PATH, this::movement);
    }

    @Override
    public String specSection() {
        return "06 § 7";
    }

    /**
     * Reads the query and renders the schedule.
     *
     * <p>The query string is parsed with {@link FormBody}, which is the same grammar — a query
     * string and a form body are both {@code application/x-www-form-urlencoded}. Reusing it means
     * this endpoint refuses a missing or non-numeric {@code period} with the same message, and
     * through the same {@link FormBody.BadRequest}, as every POST in this API.
     */
    private Json.Obj movement(HttpExchange exchange) {
        FormBody query = FormBody.parse(exchange.getRequestURI().getRawQuery());
        int periodId = query.integer("period");
        String productId = query.has("productId") ? query.text("productId") : null;

        Optional<EirService.RunSnapshot> snapshot = service.lastRunFor(periodId);
        if (snapshot.isEmpty()) {
            return Json.object()
                .bool("ran", false)
                .count("periodId", periodId)
                .str("productId", productId)
                .str("message", "no run for period " + periodId + " in this process; a movement"
                    + " schedule is derived from the figures a run published, and there are none."
                    + " Roll the period forward first. A schedule of zeroes here would publish a"
                    + " clean nil movement for a book that was never rolled forward.");
        }

        EirService.RunSnapshot run = snapshot.get();
        MovementSchedule schedule = MovementSchedule.over(
            run.runId(), run.periodId(), run.aggregate(), run.computations(), run.book(),
            productId);

        List<Json.Obj> products = new ArrayList<>(schedule.rows().size());
        for (MovementSchedule.Row row : schedule.rows()) {
            products.add(rowRow(row, true));
        }

        return Json.object()
            .bool("ran", true)
            .str("runId", schedule.runId())
            .count("periodId", schedule.periodId())
            .str("productId", productId)
            .str("scope", productId == null ? "every product" : "product " + productId)
            .bool("filterMatched", schedule.filterMatched())
            .strings("productsOnFile", schedule.productsOnFile())
            .count("contractsAccountedFor", run.aggregate().populationSize())
            .count("contractsWithFigures", run.aggregate().computedCount())
            .count("contractsInSchedule", schedule.total().contracts())
            .count("productRows", schedule.rows().size())
            .array("products", products)
            .obj("total", rowRow(schedule.total(), false))
            .obj("check", checkRow(schedule.check()))
            // Lifted out of the check so a caller polling this endpoint reads one boolean. Named
            // for the claim rather than for the outcome: "columnsSum": true over an empty scope
            // would be a true statement about no figures, which is why proves() is separate.
            .bool("columnsSum", schedule.check().satisfied())
            .bool("columnsSumProven", schedule.check().proves())
            .figure("deviation", schedule.check().deviation())
            .str("checkId", MovementSchedule.CHECK_NAME
                + " (no InvariantId — see invariantIdRequested)")
            .str("invariantIdRequested", MovementSchedule.INVARIANT_ID_REQUESTED)
            .array("excluded", excludedRows(schedule.excluded()))
            .strings("caveats", schedule.caveats());
    }

    /**
     * One published row, product or total.
     *
     * <p>Every money figure crosses as a JSON <b>string</b>. {@code Json}'s own javadoc is the
     * reason: JSON's number type is a double in every browser that will read this, and a closing
     * balance of 533914.11 becomes 533914.10999999997 often enough to be noticed in a control
     * report. The UI formats these; it never computes with them, and a string is the type that says
     * so.
     *
     * @param withDetail whether to render the contract lines — true on a product row, false on the
     *                   total, whose detail is the product rows
     */
    private static Json.Obj rowRow(MovementSchedule.Row row, boolean withDetail) {
        Json.Obj rendered = Json.object()
            .str("productId", row.productId())
            .count("contracts", row.contracts())
            .figure("openingGca", row.openingGca().atPresentationScale().amount())
            .figure("eirInterest", row.eirInterest().atPresentationScale().amount())
            .figure("cashReceived", row.cashReceived().atPresentationScale().amount())
            // Signed: this is the accounting column that makes the five columns tie.
            .figure("roundingResidue", row.roundingResidue().atPresentationScale().amount())
            // Absolute: what leg 2 is measured on, so that a contract a paise light and a contract
            // a paise heavy cannot cancel into a clean-looking rounding column.
            .figure("absoluteRoundingResidue", row.absoluteRoundingResidue().atPresentationScale().amount())
            .figure("closingGca", row.closingGca().atPresentationScale().amount())
            // Published so the reader can tie the page to the sub-ledger as well as to its own
            // detail. See the caveats: the two closing totals are legitimately a paise apart.
            .figure("ledgerClosingGca", row.ledgerClosingGca().atPresentationScale().amount())
            .figure("aggregationResidue", row.aggregationResidue().atPresentationScale().amount())
            // The arithmetic spelled out, so a reader can see the sum rather than trust it.
            .figure("derivedClosingGca", row.derivedClosingGca().atPresentationScale().amount())
            .bool("columnsSum", row.closingGca().equals(row.derivedClosingGca()));
        if (withDetail) {
            List<Json.Obj> lines = new ArrayList<>(row.lines().size());
            for (MovementSchedule.Line line : row.lines()) {
                lines.add(Json.object()
                    .str("contractId", line.contractId())
                    .figure("openingGca", line.openingGca().atPresentationScale().amount())
                    .figure("eirInterest", line.eirInterest().atPresentationScale().amount())
                    .figure("cashReceived", line.cashReceived().atPresentationScale().amount())
                    .figure("roundingResidue", line.roundingResidue().atPresentationScale().amount())
                    .figure("closingGca", line.closingGca().atPresentationScale().amount())
                    .figure("closingGcaWorking", line.workingClosingGca().amount()));
            }
            rendered.array("contracts_detail", lines);
        }
        return rendered;
    }

    /** The check, in the shape {@code EirService.invariantRows} renders an invariant result in. */
    private static Json.Obj checkRow(MovementSchedule.Check check) {
        List<Json.Obj> legs = new ArrayList<>(check.legs().size());
        for (MovementSchedule.Leg leg : check.legs()) {
            legs.add(Json.object()
                .str("name", leg.name())
                .bool("satisfied", leg.satisfied())
                .figure("deviation", leg.deviation().atPresentationScale().amount())
                .str("detail", leg.detail()));
        }
        return Json.object()
            .str("id", MovementSchedule.CHECK_NAME)
            .str("statement", "a period movement schedule's published columns sum: opening gross"
                + " carrying amount + EIR interest − cash received + rounding = closing gross"
                + " carrying amount, on every product row and in total (FR-805)")
            .bool("satisfied", check.satisfied())
            // The TOTAL absolute deviation across the four legs. Signed aggregation would let two
            // breaks in opposite directions report a reconciled schedule.
            .figure("deviation", check.deviation())
            .bool("proves", check.proves())
            .str("detail", check.detail())
            .count("legs", check.legs().size())
            .count("breaches", check.breaches().size())
            .array("legDetail", legs);
    }

    private static List<Json.Obj> excludedRows(List<MovementSchedule.Excluded> excluded) {
        List<Json.Obj> rows = new ArrayList<>(excluded.size());
        for (MovementSchedule.Excluded contract : excluded) {
            rows.add(Json.object()
                .str("contractId", contract.contractId())
                .str("productId", contract.productId())
                .str("reason", contract.reason()));
        }
        return rows;
    }
}
