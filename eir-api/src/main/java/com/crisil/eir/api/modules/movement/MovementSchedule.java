package com.crisil.eir.api.modules.movement;

import com.crisil.eir.api.store.Book;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The period movement schedule of FR-805, per product and in total, with the columns-sum check.
 *
 * <h2>The requirement is that the columns SUM, and it is asserted rather than rendered</h2>
 *
 * <p>06 § 7 asks for a movement schedule whose <b>published columns sum</b>. A schedule whose
 * opening balance plus accrual less cash does not come to the closing balance is the single most
 * visible failure a finance reader can find in a disclosure — it is one subtraction, on the page,
 * with no system access needed — and it is therefore the one defect that must never leave this
 * engine undetected. So the columns are computed here, and then <em>checked</em> here, and the
 * deviation is published beside them. Rendering a schedule and trusting the arithmetic is what
 * FR-805 exists to forbid.
 *
 * <h2>The five columns, and where each is sourced</h2>
 *
 * <p>{@code AmortisationRow}'s own javadoc names the movement schedule as its four money columns —
 * {@code opening + interest - cash = closing} — and states the reason the presented row can fail to
 * sum while every figure in it is individually correct: the ledger is carried at
 * {@code Precision.WORKING} because the roll-forward is what the rate was solved against, and
 * rounding happens once, on the way out. Reference case 1 period 2 is the documented example:
 * {@code 958,295.91 + 9,986.87 - 47,073.47} is {@code 921,209.31} while the presented closing is
 * {@code 921,209.32}. The paise is published as a <b>rounding column</b> —
 * {@link AmortisationRow#presentedRoundingResidue()} — because the alternatives are a tolerance
 * (which hides a real break as readily as a rounding one) and re-deriving the balance from the
 * rounded components (which walks the balance column away from the ledger every reconciliation
 * downstream ties to).
 *
 * <p><b>The closing column is sourced from the spine, not from the row.</b> The four movement
 * columns come from {@link ContractComputation#row()} — the amortisation ledger. The closing
 * balance column comes from {@link ContractResult#closingGca()} — the figure the run published, the
 * one {@code RunAggregate.totalClosingGca()} sums for SL-1's sub-ledger side and the one
 * {@code EirService.post} writes to the general ledger. Two sources, deliberately, so that leg 1
 * below is a comparison rather than a restatement.
 *
 * <h2>What can actually go wrong here, stated before the check is described</h2>
 *
 * <p>Worth being exact about, because the obvious reading of FR-805 leads to a control that cannot
 * fail. {@code AmortisationRow}'s constructor <b>refuses</b> a row whose closing balance disagrees
 * with its movements, at working precision, unconditionally — so a contract whose accrual is wrong
 * by a lakh still has columns that sum. Checking the four columns of one contract against its own
 * closing balance is therefore a tautology dressed as a control, and this class does not pretend
 * otherwise.
 *
 * <p>What is genuinely at risk is everything <em>between</em> that row and the published page, and
 * all of it is this class's own work: the per-contract figures being rounded and then added
 * (legs 1 and 2), the product rows being added into a total (leg 3), and every contract the run
 * computed reaching exactly one row (leg 4). Leg 4 is the one that catches the disclosure failure
 * with no visible symptom — a schedule short one contract has columns that sum perfectly and a
 * closing balance that is simply wrong. See {@link #caveats()} for the honest limit of leg 1 on this
 * pipeline.
 *
 * <h2>Two closing totals, both published, because they are a paise apart and both are real</h2>
 *
 * <p>{@code closingGca} on a row is the sum of the contracts' <b>presented</b> closing balances, so
 * that the page ties to its own detail — a reader adding the contract lines gets the row total.
 * {@code ledgerClosingGca} is the sum of the <b>working</b> balances, presented once, which is the
 * sub-ledger figure SL-1 ties to. On the seed book those are {@code 1,020,754.75} and
 * {@code 1,020,754.76}: two contracts each carrying a working tail of {@code ...2552439976} round
 * down individually and up in aggregate. Publishing only the first would produce a schedule that
 * does not tie to the general ledger; publishing only the second would produce a schedule whose own
 * contract lines do not add up. Both are stated, and {@code aggregationResidue} is the difference.
 *
 * <h2>The check: four legs, one result, total ABSOLUTE deviation</h2>
 *
 * <p>The idiom is {@code Stage3Reconciliation.fourWay()}'s: several legs, one published result,
 * whose deviation is the sum of the <em>absolute</em> leg residuals. Summing signed residuals would
 * let two breaks in opposite directions net to nil and report a reconciled schedule, which is the
 * classic way a reconciliation control passes while being broken twice. The same rule is applied
 * one level down, inside leg 2: the rounding column is aggregated <b>both</b> signed (as the
 * accounting column that makes the row tie) and absolute (as the figure the leg is measured on), so
 * a contract a paise light and a contract a paise heavy cannot cancel into a clean report.
 *
 * <ul>
 *   <li><b>Leg 1 — the columns sum with the rounding column stated.</b> For every published row:
 *       {@code closing - (opening + interest - cash + residue)}. Red where the spine's published
 *       closing balance and the amortisation ledger's roll-forward disagree about a contract, and
 *       where this class's own aggregation drops or duplicates a figure between the two.</li>
 *   <li><b>Leg 2 — the rounding column is rounding and nothing else.</b> The absolute residue on a
 *       row is bounded by {@link #RESIDUE_BOUND_PER_CONTRACT} for each contract in it, and that
 *       bound is derived from the rounding rather than chosen. Red on anything larger, and red
 *       <em>even when two residues in opposite directions have netted the signed accounting column
 *       back to nil</em> — which is the whole reason the row carries both aggregations. It cannot
 *       fail on figures reaching it through {@link #over}, and its javadoc says why; it catches this
 *       class summing the wrong field into the rounding column, and a future ledger that stops
 *       enforcing the working identity.</li>
 *   <li><b>Leg 3 — the total row ties to the product rows, column by column.</b> The total is
 *       summed from the contract lines and the leg re-sums the product rows, so these are two
 *       independent reductions of one detail; red on a bucketing defect — a column dropped from a
 *       product's sum, a line counted into two products. Six columns, not seven —
 *       {@code ledgerClosingGca} is deliberately excluded, because the total's is the working sum
 *       presented once and the product rows' are each presented once, so they legitimately differ by
 *       rounding and asserting equality would be a control red by construction.</li>
 *   <li><b>Leg 4 — every contract with figures is placed exactly once, under a product on file.</b>
 *       The census is taken by walking the published rows and counting occurrences, which is
 *       independent of the loop that placed them. Deviation is the closing balance of every
 *       contract placed zero times, placed twice, or carrying no product id at all — in rupees,
 *       because "how much of this schedule is unaccounted for" is the question a reader has. Red on
 *       a contract that computed and reached no row, which is the failure a schedule cannot show
 *       you: the columns of what is present sum perfectly. Note this leg is about attribution and
 *       completeness, not arithmetic, so a red leg 4 leaves {@code Check.columnsSum()} green — the
 *       two claims are published separately for exactly that reason.</li>
 * </ul>
 *
 * <h2>What is deliberately NOT in the check</h2>
 *
 * <p>Quarantined contracts. They carry no figures at all — {@code ContractResult.isolated} has no
 * closing balance by construction — so they are absent from every column, and this report names
 * them in {@link #excluded()} rather than pretending to a movement it does not have. Refusing over
 * them belongs to {@code PeriodCloseGate}, which already does it; a second, weaker gate beside the
 * one that owns the question is worse than no second gate.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7, FR-805.
 *
 * @param runId          the run whose figures these are
 * @param periodId       the accounting period, {@code YYYYMM}
 * @param productFilter  the {@code productId} query parameter, or null for every product
 * @param rows           one row per product in scope, in the order the population presented them
 * @param total          the TOTAL row; all-nil columns where nothing is in scope
 * @param excluded       contracts the run accounted for and published no figures for
 * @param productsOnFile every product id in the run's population, filtered or not
 * @param contractsInScope how many contracts the run published a closing balance for and this
 *                       scope covers — the denominator of the schedule, distinct from the run's own
 *                       population, which is book-wide and does not narrow with the filter
 * @param check          the FR-805 columns-sum check, one result with a total absolute deviation
 */
public record MovementSchedule(
    String runId,
    int periodId,
    String productFilter,
    List<Row> rows,
    Row total,
    List<Excluded> excluded,
    List<String> productsOnFile,
    int contractsInScope,
    Check check) {

    /**
     * The bound on one contract's presented rounding residue: two minor units, derived.
     *
     * <p><b>Not a tolerance, and the difference matters.</b> A tolerance is a figure chosen wide
     * enough to keep a control quiet. This one is arithmetic. {@code AmortisationRow}'s constructor
     * enforces {@code opening + interest - cash == closing} exactly, at
     * {@code Precision.WORKING}, so with {@code p(x) = x + e(x)} and {@code |e| <= half a minor
     * unit} the presented residue is
     * {@code p(closing) - (p(opening) + p(interest) - p(cash)) = e(closing) - e(opening) -
     * e(interest) + e(cash)}, four half-minor-units — two paise. A residue larger than that did not
     * come from rounding.
     *
     * <p>{@code presentedRoundingResidue()}'s own javadoc says "at most one minor unit", and that is
     * a statement about what is observed rather than what is possible: the four errors are not
     * independent in practice because three of the four figures usually round the same way. The
     * bound here is the possible one, because a control that false-refuses on a legitimate two-paise
     * row is a control that gets argued down to a soft one within a quarter.
     */
    public static final Money RESIDUE_BOUND_PER_CONTRACT = Money.inr("0.02");

    /**
     * The name the columns-sum check publishes under: {@link InvariantId#MV_1}'s own name.
     *
     * <p>Was the bare string {@code MOVEMENT-COLUMNS-SUM}, because {@code InvariantId} carried no id
     * for this control and {@code eir-domain} was another unit's file to change. The id now exists,
     * so the check publishes under it rather than beside it — which is what lets a run-level
     * aggregator see a movement break at all. A control publishing under a name no aggregator knows
     * is visible only to whoever reads this one endpoint.
     */
    public static final String CHECK_NAME = InvariantId.MV_1.name();

    /** How many legs the check has. Fixed, because {@code Check.columnsSum()} reads leg 1. */
    public static final int LEGS = 4;

    /**
     * The statement {@link InvariantId#MV_1} carries, for the response to publish alongside the id.
     *
     * <p>This constant used to be named {@code INVARIANT_ID_REQUESTED} and held the id this check
     * wanted and did not have. No existing id fitted: SL-2 is a journal's two sides, S3-1 is the
     * Stage 3 four-way, ST-2 is the decomposition against the ledger, and publishing under any of
     * them would give a named invariant a second claim — {@code InvariantResult.conjunction} keeps
     * only the FIRST breach's deviation among results sharing an id, so a movement break and a
     * journal break filed together would report one deviation and drop the other. MV-1 now exists
     * and this holds its statement.
     */
    public static final String INVARIANT_STATEMENT = InvariantId.MV_1.statement();

    public MovementSchedule {
        Objects.requireNonNull(runId, "runId");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Objects.requireNonNull(total, "total");
        excluded = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        productsOnFile = List.copyOf(Objects.requireNonNull(productsOnFile, "productsOnFile"));
        Objects.requireNonNull(check, "check");
        if (contractsInScope < 0) {
            throw new IllegalArgumentException(
                "contractsInScope must be non-negative, got " + contractsInScope);
        }
    }

    /**
     * The bucket a computed contract with no product id lands in.
     *
     * <p>Two conditions reach it and they are different facts: a contract in the run's population
     * with no holding at all, and a holding whose {@code productId} is null —
     * {@code Book.Holding}'s compact constructor requires the contract id, the state and the period,
     * and not the product. Leg 4 reports which, because "the master does not carry this contract"
     * and "the master carries it under no product" go to different desks.
     */
    public static final String NO_PRODUCT_ON_FILE = "(no product on file)";

    /** The product id of the total row. */
    public static final String TOTAL = "TOTAL";

    /**
     * One contract's line: the detail a product row's columns are summed from.
     *
     * @param openingGca      presented, from the amortisation ledger's row
     * @param eirInterest     presented gross EIR interest for the period, from the same row
     * @param cashReceived    presented net cash received, from the same row
     * @param roundingResidue the row's own presented rounding residue — signed
     * @param closingGca      presented, from the spine's published {@code ContractResult}
     */
    public record Line(
        String contractId,
        String productId,
        Money openingGca,
        Money eirInterest,
        Money cashReceived,
        Money roundingResidue,
        Money closingGca,
        Money workingClosingGca) {

        public Line {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(openingGca, "openingGca");
            Objects.requireNonNull(eirInterest, "eirInterest");
            Objects.requireNonNull(cashReceived, "cashReceived");
            Objects.requireNonNull(roundingResidue, "roundingResidue");
            Objects.requireNonNull(closingGca, "closingGca");
            Objects.requireNonNull(workingClosingGca, "workingClosingGca");
        }
    }

    /**
     * One published row of the schedule: a product, or the total.
     *
     * @param roundingResidue         the signed sum of the lines' residues — the accounting column
     *                                that makes this row's five columns tie
     * @param absoluteRoundingResidue the ABSOLUTE sum of the lines' residues — what leg 2 measures,
     *                                so that opposite-direction breaks cannot net to nil
     * @param closingGca              the sum of the lines' PRESENTED closing balances, so the row
     *                                ties to its own detail
     * @param ledgerClosingGca        the sum of the lines' WORKING closing balances presented once
     *                                — the sub-ledger figure SL-1 ties to, a paise from the above
     * @param lines                   the contract detail; empty on the total row, whose detail is
     *                                the product rows
     */
    public record Row(
        String productId,
        int contracts,
        Money openingGca,
        Money eirInterest,
        Money cashReceived,
        Money roundingResidue,
        Money absoluteRoundingResidue,
        Money closingGca,
        Money ledgerClosingGca,
        List<Line> lines) {

        public Row {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(openingGca, "openingGca");
            Objects.requireNonNull(eirInterest, "eirInterest");
            Objects.requireNonNull(cashReceived, "cashReceived");
            Objects.requireNonNull(roundingResidue, "roundingResidue");
            Objects.requireNonNull(absoluteRoundingResidue, "absoluteRoundingResidue");
            Objects.requireNonNull(closingGca, "closingGca");
            Objects.requireNonNull(ledgerClosingGca, "ledgerClosingGca");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }

        /** What the four movement columns and the rounding column come to. */
        public Money derivedClosingGca() {
            return openingGca.plus(eirInterest).minus(cashReceived).plus(roundingResidue);
        }

        /** The signed gap between the two closing totals — presentation aggregation, not a break. */
        public Money aggregationResidue() {
            return ledgerClosingGca.minus(closingGca);
        }
    }

    /**
     * A contract the run accounted for and published no figures for.
     *
     * <p>Named rather than dropped. A movement schedule silently short one contract has columns that
     * sum perfectly, which is exactly why FR-905's per-contract accounting exists.
     */
    public record Excluded(String contractId, String productId, String reason) {

        public Excluded {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One leg of the columns-sum check. Deviation is always non-negative. */
    public record Leg(String name, boolean satisfied, String detail, Money deviation) {

        public Leg {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(deviation, "deviation");
            if (deviation.isNegative()) {
                throw new IllegalStateException(
                    "leg " + name + " reported a negative deviation " + deviation + "; a leg's"
                        + " deviation is the ABSOLUTE size of its break, and a signed one would let"
                        + " two legs cancel");
            }
        }
    }

    /**
     * The published check: one result, in the shape {@code InvariantResult} carries, under
     * {@link InvariantId#MV_1}.
     *
     * @param proves whether the check proves anything — false over an empty scope, where a pass is
     *               a statement about no figures. The distinction {@code ReplayVerification} draws
     *               between {@code dtOneSatisfied()} and {@code provesReproduction()}.
     */
    public record Check(
        String name,
        boolean satisfied,
        String detail,
        BigDecimal deviation,
        boolean proves,
        List<Leg> legs) {

        public Check {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(deviation, "deviation");
            legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
            if (deviation.signum() < 0) {
                throw new IllegalStateException(
                    "the columns-sum check reported a negative total deviation "
                        + deviation.toPlainString() + "; deviations aggregate absolute");
            }
            if (legs.size() != LEGS) {
                // columnsSum() reads leg 1 by index, and a leg list of some other length would have
                // it answering about whichever leg happened to be first.
                throw new IllegalStateException(
                    "the columns-sum check has " + LEGS + " legs, got " + legs.size());
            }
        }

        /** The legs that broke; empty on a clean schedule. */
        public List<Leg> breaches() {
            return legs.stream().filter(leg -> !leg.satisfied()).toList();
        }

        /**
         * Leg 1 alone: whether the published columns sum, which is the narrow FR-805 claim.
         *
         * <p>Separate from {@link #satisfied()} because they are different statements and a caller
         * that conflates them publishes a false one. A schedule whose working papers failed to write
         * has columns that sum perfectly and is short a contract: leg 1 green, leg 4 red,
         * {@code satisfied()} false. Reporting that as "the columns do not sum" would send a reader
         * to check arithmetic that is correct.
         *
         * <p>The index is safe because {@link #checkOver} builds the leg list positionally and this
         * constructor refuses any other length.
         */
        public boolean columnsSum() {
            return legs.get(0).satisfied();
        }
    }

    // ============================================================== construction

    /**
     * Builds the schedule for one run.
     *
     * @param productFilter the {@code productId} query parameter, or null for every product
     */
    public static MovementSchedule over(
        String runId,
        int periodId,
        RunAggregate aggregate,
        Map<String, ContractComputation> computations,
        List<Book.Holding> holdings,
        String productFilter) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(computations, "computations");
        Objects.requireNonNull(holdings, "holdings");

        // Two facts, kept apart on purpose: whether the master carries the contract at all, and
        // what product it carries it under. Collapsing them into one nullable product id had leg 4
        // reporting "no holding on the book" for a holding that exists and names no product —
        // Book.Holding's compact constructor requires the contract id, the state and the period,
        // and not the product. A true refusal with a false reason sends the wrong desk to look.
        Map<String, Book.Holding> onFile = new LinkedHashMap<>();
        for (Book.Holding holding : holdings) {
            onFile.put(holding.contractId(), holding);
        }

        List<String> productsOnFile = new ArrayList<>();
        Map<String, List<Line>> byProduct = new LinkedHashMap<>();
        List<Excluded> excluded = new ArrayList<>();
        // The census leg 4 is measured against: every contract in scope the run published a closing
        // balance for, whether or not it reached a row. Built in this loop and consumed by a walk
        // over the published rows, so that a placement defect between the two is visible.
        Map<String, Money> withFigures = new LinkedHashMap<>();
        // The contracts the schedule cannot attribute to a product, with which of the two
        // conditions each is. Leg 4 names them; a nullable product id could not tell them apart.
        List<String> unattributed = new ArrayList<>();

        for (ContractResult result : aggregate.results()) {
            String contractId = result.contractId();
            Book.Holding holding = onFile.get(contractId);
            String product = holding == null ? null : holding.productId();
            // Recorded before the filter, so that a filter matching nothing can say what it could
            // have matched instead of returning an empty page with no explanation.
            if (product != null && !productsOnFile.contains(product)) {
                productsOnFile.add(product);
            }

            if (productFilter != null && !productFilter.equals(product)) {
                // Out of scope — except for a computed contract carrying no product id at all,
                // which no filter is entitled to exclude silently: a filter answers "is this
                // contract's product the one you asked for", and for this contract there is no
                // answer. Dropping it left a filtered schedule short exactly its balance with all
                // four legs green, which is the failure leg 4 exists to catch, reached by the one
                // route that ran before leg 4's census was taken.
                if (result.isComputed() && product == null) {
                    excluded.add(new Excluded(contractId, NO_PRODUCT_ON_FILE,
                        "the run published a closing balance of "
                            + result.closingGca().atPresentationScale() + " for it and "
                            + (holding == null
                                ? "the book carries no holding for it"
                                : "its holding names no product")
                            + ", so a filter on productId=" + productFilter + " cannot say whether"
                            + " it belongs in this scope. Named here rather than dropped, because a"
                            + " filtered schedule short one contract has columns that sum"
                            + " perfectly."));
                }
                continue;
            }
            if (!result.isComputed()) {
                excluded.add(new Excluded(contractId, product,
                    "the run published no figures for it — "
                        + result.exception().category().name()
                        + ": " + result.exception().describe()));
                continue;
            }
            // Recorded BEFORE the working papers are looked for, and that ordering is the control.
            // A computed contract whose working papers are missing is exactly the contract that
            // must still be accounted for: its balance is in the run's sub-ledger total and in none
            // of this schedule's columns. Recording it only on the happy path would have leg 4
            // taking its census from the same loop that dropped it.
            withFigures.put(contractId, result.closingGca().atPresentationScale());
            if (product == null) {
                unattributed.add(contractId + (holding == null
                    ? " (the book carries no holding for it)"
                    : " (its holding names no product)"));
            }

            ContractComputation computation = computations.get(contractId);
            if (computation == null) {
                // A computed contract with no working papers. Reported rather than skipped: the
                // closing balance is in the spine's total and would be missing from every movement
                // column, and the columns of what remained would sum.
                excluded.add(new Excluded(contractId, product,
                    "the run published a closing balance of "
                        + result.closingGca().atPresentationScale() + " for it and no working"
                        + " papers, so its movement columns cannot be stated; this is a defect in"
                        + " the run record, not a data condition"));
                continue;
            }

            AmortisationRow row = computation.row();
            String bucket = product == null ? NO_PRODUCT_ON_FILE : product;
            Line line = new Line(contractId, bucket,
                row.presentedOpeningGca(),
                row.presentedEirInterest(),
                row.presentedCashReceived(),
                // From the LEDGER's own closing balance (AmortisationRow), while the closing column
                // below comes from the SPINE's published figure. Two sources — see the class
                // javadoc, and the caveat about what that comparison can and cannot catch here.
                row.presentedRoundingResidue(),
                result.closingGca().atPresentationScale(),
                result.closingGca());
            byProduct.computeIfAbsent(bucket, key -> new ArrayList<>()).add(line);
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Line>> entry : byProduct.entrySet()) {
            rows.add(rowOver(entry.getKey(), entry.getValue()));
        }
        Row total = totalOver(rows);

        return new MovementSchedule(runId, periodId, productFilter, List.copyOf(rows), total,
            List.copyOf(excluded), List.copyOf(productsOnFile), withFigures.size(),
            checkOver(rows, total, withFigures, List.copyOf(unattributed)));
    }

    /** One product's row: the columns are the sum of its lines'. */
    public static Row rowOver(String productId, List<Line> lines) {
        Money nil = Money.zero(Money.INR);
        Money opening = nil;
        Money interest = nil;
        Money cash = nil;
        Money residue = nil;
        Money absoluteResidue = nil;
        Money closing = nil;
        Money working = nil;
        for (Line line : lines) {
            opening = opening.plus(line.openingGca());
            interest = interest.plus(line.eirInterest());
            cash = cash.plus(line.cashReceived());
            residue = residue.plus(line.roundingResidue());
            absoluteResidue = absoluteResidue.plus(line.roundingResidue().abs());
            closing = closing.plus(line.closingGca());
            working = working.plus(line.workingClosingGca());
        }
        return new Row(productId, lines.size(), opening, interest, cash, residue, absoluteResidue,
            closing, working.atPresentationScale(), List.copyOf(lines));
    }

    /**
     * The total row, summed from the contract LINES rather than from the product rows.
     *
     * <p><b>Two aggregation paths, on purpose, and this is the one thing that keeps leg 3 from
     * being a tautology.</b> A total summed from the rows would be the same arithmetic leg 3 then
     * re-performs to check it, so no input could ever make them disagree — the exact shape of
     * control this repository has recorded seventeen of. Summed from the lines, the total and the
     * product rows are two independent reductions of the same detail, and a defect in
     * {@link #rowOver} — a column left out of a product's sum, a line counted twice into one
     * product — makes them differ. {@code ContractPipeline} states the same reasoning for deriving
     * a period's accrual length twice.
     *
     * <p>{@code ledgerClosingGca} is likewise re-derived from the lines' WORKING balances rather
     * than summed from the product rows' presented ledger totals, because summing those would round
     * twice — once per product and once again here — and 03 § 1.3's rule is that a figure is reduced
     * where it is persisted, once. On a two-product book the double rounding is worth up to a paise
     * per product, and the total is the figure a reader ties to the general ledger.
     */
    public static Row totalOver(List<Row> rows) {
        Money nil = Money.zero(Money.INR);
        Money opening = nil;
        Money interest = nil;
        Money cash = nil;
        Money residue = nil;
        Money absoluteResidue = nil;
        Money closing = nil;
        Money working = nil;
        int contracts = 0;
        for (Row row : rows) {
            for (Line line : row.lines()) {
                contracts += 1;
                opening = opening.plus(line.openingGca());
                interest = interest.plus(line.eirInterest());
                cash = cash.plus(line.cashReceived());
                residue = residue.plus(line.roundingResidue());
                absoluteResidue = absoluteResidue.plus(line.roundingResidue().abs());
                closing = closing.plus(line.closingGca());
                working = working.plus(line.workingClosingGca());
            }
        }
        return new Row(TOTAL, contracts, opening, interest, cash, residue, absoluteResidue,
            closing, working.atPresentationScale(), List.of());
    }

    // ==================================================================== the check

    /**
     * The four-leg columns-sum check over a published schedule.
     *
     * <p><b>Public, and that is the point.</b> "A control that cannot fail is worse than an absent
     * one" is this repository's most repeated finding — seventeen recorded — and a check reachable
     * only through {@link #over} could only ever be exercised on figures this engine produced, which
     * are the figures that make it pass. Exposed, a test can hand it a schedule with a break in it
     * and assert the deviation, which is the only evidence the check does anything. {@link #over}
     * calls exactly this method with exactly these arguments.
     *
     * @param rows        the product rows as published
     * @param total       the total row as published — passed in rather than re-derived from
     *                    {@code rows}, because leg 3's whole job is to compare the two
     * @param withFigures  every contract in scope the run published a closing balance for, contract
     *                     id to presented balance; leg 4's census
     * @param unattributed the contracts in that census the schedule cannot put under a product,
     *                     each already carrying which of the two conditions it is — no holding at
     *                     all, or a holding naming no product. Supplied rather than inferred from
     *                     the rows, because the two conditions are indistinguishable by the time a
     *                     line has been bucketed and they go to different desks.
     */
    public static Check checkOver(
        List<Row> rows, Row total, Map<String, Money> withFigures, List<String> unattributed) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(withFigures, "withFigures");
        Objects.requireNonNull(unattributed, "unattributed");
        List<Row> published = new ArrayList<>(rows);
        published.add(total);

        List<Leg> legs = List.of(
            columnsSum(published),
            roundingIsOnlyRounding(published),
            totalTiesToProducts(rows, total),
            everyContractPlacedOnce(rows, withFigures, unattributed));

        // Total ABSOLUTE deviation, the Stage3Reconciliation.fourWay idiom. Signed would let two
        // legs in opposite directions report a clean schedule.
        BigDecimal deviation = BigDecimal.ZERO;
        List<String> breaks = new ArrayList<>();
        for (Leg leg : legs) {
            if (!leg.satisfied()) {
                deviation = deviation.add(leg.deviation().amount().abs());
                breaks.add(leg.name() + " — " + leg.detail());
            }
        }
        boolean satisfied = breaks.isEmpty();
        String detail = satisfied
            ? "all four legs hold across " + rows.size() + " product row(s) and the total: the"
                + " published columns sum"
            : breaks.size() + " of 4 legs broken — " + String.join("; ", breaks);
        return new Check(CHECK_NAME, satisfied, detail, deviation,
            satisfied && total.contracts() > 0, legs);
    }

    /**
     * Leg 1. Every published row's columns come to its closing balance.
     *
     * <p>{@code closing - (opening + interest - cash + residue)}, absolute-summed over the product
     * rows and the total. Non-zero where the spine's published closing balance and the amortisation
     * ledger's roll-forward disagree about a contract — the two independent sources of the closing
     * figure — and where this class dropped or duplicated a figure between the line and the row.
     */
    private static Leg columnsSum(List<Row> published) {
        Money total = Money.zero(Money.INR);
        List<String> breaks = new ArrayList<>();
        for (Row row : published) {
            Money residual = row.closingGca().minus(row.derivedClosingGca());
            if (!residual.isZero()) {
                breaks.add(row.productId() + ": " + row.openingGca().atPresentationScale()
                    + " + " + row.eirInterest().atPresentationScale()
                    + " − " + row.cashReceived().atPresentationScale()
                    + " + " + row.roundingResidue().atPresentationScale()
                    + " = " + row.derivedClosingGca().atPresentationScale()
                    + ", not " + row.closingGca().atPresentationScale()
                    + " (out by " + residual.atPresentationScale() + ")");
                total = total.plus(residual.abs());
            }
        }
        return new Leg("the published columns sum to the closing balance", breaks.isEmpty(),
            breaks.isEmpty()
                ? "every row: opening + interest − cash + rounding = closing, exactly"
                : String.join("; ", breaks),
            total);
    }

    /**
     * Leg 2. The rounding column is rounding and nothing else.
     *
     * <p>Measured on the ABSOLUTE residue, against one minor unit per contract. That bound is
     * {@link AmortisationRow#presentedRoundingResidue()}'s documented one, not a tolerance chosen
     * to keep this quiet: the presented row admits exactly one rounding step per figure, so a
     * contract cannot be out by two paise for a rounding reason.
     *
     * <p><b>What this leg does and does not catch, stated plainly.</b> It cannot fail on figures
     * reaching it through {@link #over}: {@code Line.roundingResidue} is
     * {@code AmortisationRow.presentedRoundingResidue()}, computed from that row's own four figures,
     * and the row's constructor already refuses any row where {@code opening + interest - cash}
     * differs from {@code closing} at working precision — so the presented residue is bounded at two
     * paise by construction and a wrong accrual leaves this leg green and trips <b>leg 1</b>
     * instead. What it does catch is this class assembling the rounding column from the wrong field,
     * and a future ledger that stops enforcing the working identity, at which point it is the only
     * leg that would notice.
     *
     * <p>The absolute aggregation is what makes it worth having at all: a contract ₹500 light and a
     * contract ₹500 heavy net the signed accounting column back to nil, and this leg reads ₹1,000.
     */
    private static Leg roundingIsOnlyRounding(List<Row> published) {
        Money total = Money.zero(Money.INR);
        List<String> breaks = new ArrayList<>();
        for (Row row : published) {
            Money bound = RESIDUE_BOUND_PER_CONTRACT.times(new BigDecimal(row.contracts()));
            Money excess = row.absoluteRoundingResidue().minus(bound);
            if (excess.isPositive()) {
                breaks.add(row.productId() + ": absolute rounding column "
                    + row.absoluteRoundingResidue().atPresentationScale() + " exceeds "
                    + bound.atPresentationScale() + " (two minor units for each of "
                    + row.contracts() + " contract(s)) by " + excess.atPresentationScale()
                    + "; the signed column reads "
                    + row.roundingResidue().atPresentationScale()
                    + ", so this is not rounding");
                total = total.plus(excess);
            }
        }
        return new Leg("the rounding column is rounding only, aggregated absolute",
            breaks.isEmpty(),
            breaks.isEmpty()
                ? "every row's absolute rounding column is within two minor units per contract"
                : String.join("; ", breaks),
            total);
    }

    /**
     * Leg 3. The total row ties to the product rows, column by column.
     *
     * <p><b>Not a tautology, and {@link #totalOver} is why.</b> The total is summed from the
     * contract lines and this leg re-sums the product rows, so the two are independent reductions of
     * the same detail: a column dropped in {@link #rowOver}, or a line counted into two products,
     * makes them differ. A total summed from the rows would make this leg re-perform the arithmetic
     * it is checking, and no input could fail it.
     *
     * <p>Six columns. {@code ledgerClosingGca} is excluded on purpose — see {@link #totalOver} for
     * why the total's is not the sum of the rows'.
     */
    private static Leg totalTiesToProducts(List<Row> rows, Row total) {
        Money nil = Money.zero(Money.INR);
        Money opening = nil;
        Money interest = nil;
        Money cash = nil;
        Money residue = nil;
        Money absoluteResidue = nil;
        Money closing = nil;
        for (Row row : rows) {
            opening = opening.plus(row.openingGca());
            interest = interest.plus(row.eirInterest());
            cash = cash.plus(row.cashReceived());
            residue = residue.plus(row.roundingResidue());
            absoluteResidue = absoluteResidue.plus(row.absoluteRoundingResidue());
            closing = closing.plus(row.closingGca());
        }
        Map<String, Money> expected = new LinkedHashMap<>();
        expected.put("openingGca", opening);
        expected.put("eirInterest", interest);
        expected.put("cashReceived", cash);
        expected.put("roundingResidue", residue);
        expected.put("absoluteRoundingResidue", absoluteResidue);
        expected.put("closingGca", closing);
        Map<String, Money> published = new LinkedHashMap<>();
        published.put("openingGca", total.openingGca());
        published.put("eirInterest", total.eirInterest());
        published.put("cashReceived", total.cashReceived());
        published.put("roundingResidue", total.roundingResidue());
        published.put("absoluteRoundingResidue", total.absoluteRoundingResidue());
        published.put("closingGca", total.closingGca());

        Money deviation = nil;
        List<String> breaks = new ArrayList<>();
        for (Map.Entry<String, Money> column : expected.entrySet()) {
            Money residual = published.get(column.getKey()).minus(column.getValue());
            if (!residual.isZero()) {
                breaks.add("total " + column.getKey() + " is "
                    + published.get(column.getKey()).atPresentationScale() + " and the "
                    + rows.size() + " product row(s) come to "
                    + column.getValue().atPresentationScale()
                    + " (out by " + residual.atPresentationScale() + ")");
                deviation = deviation.plus(residual.abs());
            }
        }
        return new Leg("the total row ties to the product rows", breaks.isEmpty(),
            breaks.isEmpty()
                ? "all six presented columns of the total equal the sum of the product rows'"
                : String.join("; ", breaks),
            deviation);
    }

    /**
     * Leg 4. Every contract with figures is placed exactly once, under a product on file.
     *
     * <p>The census walks the published rows and counts, which is independent of the loop that put
     * the lines there — the point being that a contract computed by the run and reaching no row is
     * the one failure a movement schedule cannot show a reader: the columns of what is present sum
     * perfectly, and the schedule is simply short.
     */
    private static Leg everyContractPlacedOnce(
        List<Row> rows, Map<String, Money> withFigures, List<String> unattributed) {
        Map<String, Integer> placements = new LinkedHashMap<>();
        Money deviation = Money.zero(Money.INR);
        List<String> breaks = new ArrayList<>();
        for (Row row : rows) {
            for (Line line : row.lines()) {
                placements.merge(line.contractId(), 1, Integer::sum);
                if (!withFigures.containsKey(line.contractId())) {
                    breaks.add("contract " + line.contractId() + " is on the " + row.productId()
                        + " row carrying " + line.closingGca().atPresentationScale()
                        + " and is not a contract this run published a closing balance for");
                    deviation = deviation.plus(line.closingGca().abs());
                }
            }
        }
        for (Map.Entry<String, Money> contract : withFigures.entrySet()) {
            String contractId = contract.getKey();
            Money closing = contract.getValue();
            int placed = placements.getOrDefault(contractId, 0);
            if (placed != 1) {
                breaks.add("contract " + contractId + " carries " + closing.atPresentationScale()
                    + " and appears on " + placed + " product row(s), not exactly one");
                deviation = deviation.plus(closing.abs());
            }
        }
        for (String contract : unattributed) {
            // Reported with the balance at risk, and the message says which of the two conditions
            // it is. The deviation is that balance because the question a reader has is how much of
            // the schedule is not attributable to a product, and the answer is in rupees.
            String contractId = contract.split(" ")[0];
            Money closing = withFigures.getOrDefault(contractId, Money.zero(Money.INR));
            breaks.add("contract " + contract + " carries " + closing.atPresentationScale()
                + " and cannot be attributed to a product, so the schedule presents it under "
                + NO_PRODUCT_ON_FILE + " rather than under a product a reader can reconcile");
            deviation = deviation.plus(closing.abs());
        }
        return new Leg("every contract with figures is placed exactly once, under a product on file",
            breaks.isEmpty(),
            breaks.isEmpty()
                ? withFigures.size() + " contract(s) with a published closing balance, each on"
                    + " exactly one product row"
                : String.join("; ", breaks),
            deviation);
    }

    // ==================================================================== reading

    /** Whether the query's {@code productId} matched anything in the run's population. */
    public boolean filterMatched() {
        return productFilter == null || !rows.isEmpty() || !excluded.isEmpty();
    }

    /**
     * The limits of this report, stated rather than left for a reader to discover.
     *
     * <p>Every one of these is a thing a reader would otherwise reasonably assume and be wrong
     * about. The first is the honest limit of a control this class publishes, which the codebase's
     * own record of "seventeen controls that read as coverage and could not fail" says has to be
     * written down beside it.
     */
    public List<String> caveats() {
        return List.of(
            "What the check does NOT prove: that the figures are right. AmortisationRow's"
                + " constructor refuses a row whose closing balance disagrees with its movements at"
                + " working precision, so a contract whose accrual is wrong by a lakh still has"
                + " columns that sum. This check is about everything between that row and the"
                + " page — rounding, aggregation into products and a total, and completeness of the"
                + " population. Leg 4 is the one that catches the disclosure failure with no other"
                + " symptom: a schedule short one contract has columns that sum perfectly.",
            "Leg 1 compares the spine's published closing balance (RunAggregate.results()) against"
                + " the amortisation ledger's roll-forward (ContractComputation.row()). Those are"
                + " two sources by design, and in the pipeline as it stands ContractPipeline"
                + " populates both from one figure — so on this book leg 1 only fails if this"
                + " report mis-assembles a row or a run record pairs a contract's result with"
                + " another contract's working papers.",
            "Leg 2 cannot fail on figures reaching it through this report's own path. The rounding"
                + " column is AmortisationRow.presentedRoundingResidue(), and that row's"
                + " constructor already refuses a roll-forward that does not tie at working"
                + " precision, which bounds the presented residue at two paise. It is retained"
                + " because it is the only leg that would notice a ledger that stopped enforcing"
                + " that identity, and because the absolute aggregation is what stops two"
                + " opposite-direction breaks netting to a clean rounding column. Stated here"
                + " rather than left to be discovered: a control that cannot fail is worse than an"
                + " absent one, and the honest remedy is to say which one it is.",
            "Quarantined contracts carry no figures at all and are absent from every column. This"
                + " schedule is the movement of the contracts that computed, not of the book;"
                + " 'excluded' names the rest, and refusing over them is PeriodCloseGate's job,"
                + " not this report's.",
            "closingGca is the sum of the contracts' PRESENTED balances, so the row ties to its own"
                + " detail. ledgerClosingGca is the sum of their WORKING balances presented once,"
                + " which is the sub-ledger figure SL-1 ties to. On the seed book they are"
                + " 1,020,754.75 and 1,020,754.76; aggregationResidue is the difference, and it is"
                + " presentation aggregation rather than a break.",
            "The columns-sum check publishes under InvariantId." + CHECK_NAME + " — \""
                + INVARIANT_STATEMENT + "\" — which is its own id and not a borrowed one. It had"
                + " none when this report was built and published under a bare string; borrowing"
                + " SL-2 or S3-1 instead would have given a named invariant a second claim, and"
                + " InvariantResult.conjunction keeps only the first breach's deviation among"
                + " results sharing an id.");
    }
}
