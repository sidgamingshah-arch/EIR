package com.crisil.eir.api.modules.movement;

import com.crisil.eir.api.store.Book;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.calc.amort.AmortisationRow;
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
 *       back to nil</em> — which is the whole reason the row carries both aggregations. Catches this
 *       class summing the wrong field into the rounding column, and a future ledger that stops
 *       enforcing the working identity.</li>
 *   <li><b>Leg 3 — the total row ties to the product rows, column by column.</b> Red on a bucketing
 *       defect: a product row left out of the total, a column summed from the wrong field. Six
 *       columns, not seven — {@code ledgerClosingGca} is deliberately excluded, because the total's
 *       is the working sum presented once and the product rows' are each presented once, so they
 *       legitimately differ by rounding and asserting equality would be a control red by
 *       construction.</li>
 *   <li><b>Leg 4 — every contract with figures is placed exactly once, under a product on file.</b>
 *       The census is taken by walking the published rows and counting occurrences, which is
 *       independent of the loop that placed them. Deviation is the closing balance of every
 *       contract placed zero times, placed twice, or placed under no product id — in rupees,
 *       because "how much of the book is this schedule out by" is the question a reader has. Red on
 *       a contract that computed and reached no row, which is the failure a schedule cannot show
 *       you: the columns of what is present sum perfectly.</li>
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

    /** The name the columns-sum check publishes under. See {@link #INVARIANT_ID_REQUESTED}. */
    public static final String CHECK_NAME = "MOVEMENT-COLUMNS-SUM";

    /**
     * The invariant id this check wants and does not have.
     *
     * <p>{@code InvariantId} carries no id for the movement schedule's columns, and no existing id
     * fits: SL-2 is a journal's two sides, S3-1 is the Stage 3 four-way, ST-2 is the decomposition
     * against the ledger. Publishing under one of those would give a named invariant a second claim,
     * and {@code InvariantResult.conjunction} keeps only the FIRST breach's deviation among results
     * sharing an id — so a movement break and a journal break under one id would report one
     * deviation and hide the other. So the check publishes under this name as a string, the
     * response says so, and the id is requested rather than borrowed.
     */
    public static final String INVARIANT_ID_REQUESTED = "MV_1(\"movement schedule columns sum\")";

    public MovementSchedule {
        Objects.requireNonNull(runId, "runId");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Objects.requireNonNull(total, "total");
        excluded = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        productsOnFile = List.copyOf(Objects.requireNonNull(productsOnFile, "productsOnFile"));
        Objects.requireNonNull(check, "check");
    }

    /** The product id used for a computed contract with no holding on the book. */
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
     * The published check: one result, the shape {@code InvariantResult} would have if
     * {@link MovementSchedule#INVARIANT_ID_REQUESTED} existed.
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
        }

        /** The legs that broke; empty on a clean schedule. */
        public List<Leg> breaches() {
            return legs.stream().filter(leg -> !leg.satisfied()).toList();
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
        Book book,
        String productFilter) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(computations, "computations");
        Objects.requireNonNull(book, "book");

        List<String> productsOnFile = new ArrayList<>();
        Map<String, List<Line>> byProduct = new LinkedHashMap<>();
        List<Excluded> excluded = new ArrayList<>();
        // The census leg 4 is measured against: every contract in scope the run published a closing
        // balance for, whether or not it reached a row. Built in this loop and consumed by a walk
        // over the published rows, so that a placement defect between the two is visible.
        Map<String, Money> withFigures = new LinkedHashMap<>();

        for (ContractResult result : aggregate.results()) {
            String contractId = result.contractId();
            String product = book.holding(contractId)
                .map(Book.Holding::productId)
                .orElse(null);
            // Recorded before the filter, so that a filter matching nothing can say what it could
            // have matched instead of returning an empty page with no explanation.
            if (product != null && !productsOnFile.contains(product)) {
                productsOnFile.add(product);
            }
            if (productFilter != null && !productFilter.equals(product)) {
                continue;
            }
            if (!result.isComputed()) {
                excluded.add(new Excluded(contractId, product,
                    "the run published no figures for it — " + result.exception().category().name()
                        + ": " + result.exception().describe()));
                continue;
            }
            // Recorded BEFORE the working papers are looked for, and that ordering is the control.
            // A computed contract whose working papers are missing is exactly the contract that
            // must still be accounted for: its balance is in the run's sub-ledger total and in none
            // of this schedule's columns. Recording it only on the happy path would have leg 4
            // taking its census from the same loop that dropped it.
            withFigures.put(contractId, result.closingGca().atPresentationScale());

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
            List.copyOf(excluded), List.copyOf(productsOnFile),
            checkOver(rows, total, withFigures));
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
     * The total row: the sum of the product rows, except for the ledger closing total.
     *
     * <p>{@code ledgerClosingGca} is re-derived from the lines' WORKING balances rather than summed
     * from the product rows' presented ledger totals, because summing those would round twice —
     * once per product and once again here — and 03 § 1.3's rule is that a figure is reduced where
     * it is persisted, once. On a two-product book the double rounding is worth up to a paise per
     * product, and the total is the figure a reader ties to the general ledger.
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
            contracts += row.contracts();
            opening = opening.plus(row.openingGca());
            interest = interest.plus(row.eirInterest());
            cash = cash.plus(row.cashReceived());
            residue = residue.plus(row.roundingResidue());
            absoluteResidue = absoluteResidue.plus(row.absoluteRoundingResidue());
            closing = closing.plus(row.closingGca());
            for (Line line : row.lines()) {
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
     * @param withFigures every contract in scope the run published a closing balance for, contract
     *                    id to presented balance; leg 4's census
     */
    public static Check checkOver(List<Row> rows, Row total, Map<String, Money> withFigures) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(withFigures, "withFigures");
        List<Row> published = new ArrayList<>(rows);
        published.add(total);

        List<Leg> legs = List.of(
            columnsSum(published),
            roundingIsOnlyRounding(published),
            totalTiesToProducts(rows, total),
            everyContractPlacedOnce(rows, withFigures));

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
     * <p><b>This is the leg that fails on bad figures.</b> A contract whose accrual is wrong by
     * ₹1,000 puts ₹1,000 in its residue, and the row's five columns still tie because the residue
     * column absorbs it — leg 1 stays green and this one goes red. And because the aggregation is
     * absolute, a contract ₹500 light and a contract ₹500 heavy do not cancel: the signed accounting
     * column reads nil and this leg reads ₹1,000.
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
    private static Leg everyContractPlacedOnce(List<Row> rows, Map<String, Money> withFigures) {
        Map<String, Integer> placements = new LinkedHashMap<>();
        List<String> underNoProduct = new ArrayList<>();
        Money deviation = Money.zero(Money.INR);
        List<String> breaks = new ArrayList<>();
        for (Row row : rows) {
            for (Line line : row.lines()) {
                placements.merge(line.contractId(), 1, Integer::sum);
                if (NO_PRODUCT_ON_FILE.equals(row.productId())) {
                    underNoProduct.add(line.contractId());
                }
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
            if (underNoProduct.contains(contractId)) {
                breaks.add("contract " + contractId + " carries " + closing.atPresentationScale()
                    + " and has no holding on the book, so the schedule cannot say which product"
                    + " its movement belongs to");
                deviation = deviation.plus(closing.abs());
            }
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
            "Quarantined contracts carry no figures at all and are absent from every column. This"
                + " schedule is the movement of the contracts that computed, not of the book;"
                + " 'excluded' names the rest, and refusing over them is PeriodCloseGate's job,"
                + " not this report's.",
            "closingGca is the sum of the contracts' PRESENTED balances, so the row ties to its own"
                + " detail. ledgerClosingGca is the sum of their WORKING balances presented once,"
                + " which is the sub-ledger figure SL-1 ties to. On the seed book they are"
                + " 1,020,754.75 and 1,020,754.76; aggregationResidue is the difference, and it is"
                + " presentation aggregation rather than a break.",
            "The columns-sum check has no InvariantId. It publishes under the name "
                + CHECK_NAME + " and asks for " + INVARIANT_ID_REQUESTED + "; borrowing SL-2 or"
                + " S3-1 would give a named invariant a second claim, and"
                + " InvariantResult.conjunction keeps only the first breach's deviation among"
                + " results sharing an id.");
    }
}
