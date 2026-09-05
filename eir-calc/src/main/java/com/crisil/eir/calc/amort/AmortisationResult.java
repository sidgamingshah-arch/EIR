package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A completed roll-forward: the movement schedule, its totals, the terminal
 * balance and the invariants that were asserted while producing it.
 *
 * <p>Invariants are carried rather than thrown so that a ten-million-contract run
 * can report every breach it found instead of dying on the first one (FR-905).
 * A caller that wants the strict behaviour asks for it with {@link #orThrow()}.
 *
 * <p>The constructor re-derives the totals and the balance chain from the rows.
 * Totals are the figures a controller adds up, and an accumulator that has
 * drifted from the schedule it summarises is the single most common way a
 * movement report goes wrong — so the accumulation is checked against the rows
 * rather than trusted.
 *
 * @param rows            one row per accrual boundary, in order
 * @param totalInterest   lifetime interest at working precision
 * @param totalCash       lifetime net cash received at working precision
 * @param terminalBalance closing carrying amount of the last row
 * @param invariants      what was asserted; may contain breaches
 */
public record AmortisationResult(
    List<AmortisationRow> rows,
    Money totalInterest,
    Money totalCash,
    Money terminalBalance,
    List<InvariantResult> invariants) {

    public AmortisationResult {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(totalInterest, "totalInterest");
        Objects.requireNonNull(totalCash, "totalCash");
        Objects.requireNonNull(terminalBalance, "terminalBalance");
        Objects.requireNonNull(invariants, "invariants");
        rows = List.copyOf(rows);
        invariants = List.copyOf(invariants);
        if (!rows.isEmpty()) {
            Money interest = Money.zero(totalInterest.currency());
            Money cash = Money.zero(totalCash.currency());
            AmortisationRow previous = null;
            for (AmortisationRow row : rows) {
                if (previous != null) {
                    if (row.period() <= previous.period()) {
                        throw new IllegalArgumentException(
                            "rows must be in ascending period order, got " + previous.period()
                                + " then " + row.period());
                    }
                    if (!row.openingGca().equals(previous.closingGca())) {
                        throw new IllegalArgumentException(
                            "balance chain broken at period " + row.period() + ": opening " + row.openingGca()
                                + " does not continue " + previous.closingGca());
                    }
                }
                interest = interest.plus(row.eirInterest());
                cash = cash.plus(row.cashReceived());
                previous = row;
            }
            requireTie("totalInterest", interest, totalInterest);
            requireTie("totalCash", cash, totalCash);
            requireTie("terminalBalance", previous.closingGca(), terminalBalance);
        }
    }

    /**
     * Checks a declared total against the rows it claims to summarise.
     *
     * <p>Compared at presentation scale, while the row-to-row balance chain above is
     * compared at working precision through {@code Money.equals}. The asymmetry is
     * deliberate: the chain is a ledger identity where one row's opening <em>is</em>
     * the previous row's closing and any difference at all is a broken chain, whereas
     * a declared total is a reported figure and the claim is that it reports these
     * rows. A caller may therefore declare a total up to half a minor unit from the
     * row sum and be accepted; that is not the residue tolerance section 5.7 forbids,
     * because no residue is being absorbed — the rows themselves are unchanged and
     * remain the authority.
     *
     * <p>The difference is reduced <em>once</em>, not two rounded figures compared,
     * for the reason {@code InvariantResult.ofMoney} and
     * {@code TwoLegRow.presentedUnamortisedFee} give: rounding both sides first
     * rounds twice, and the error that admits has no floor. A declared total a
     * thousandth of a rupee from the row sum would be rejected whenever the pair
     * happened to straddle a rounding boundary, which is a false alarm on a figure
     * that is right.
     */
    private static void requireTie(String name, Money fromRows, Money declared) {
        if (!declared.minus(fromRows).atPresentationScale().isZero()) {
            throw new IllegalArgumentException(
                name + " does not tie to the rows: rows give " + fromRows.atPresentationScale()
                    + ", result declares " + declared.atPresentationScale());
        }
    }

    /** The opening carrying amount of the first period. */
    public Money openingGca() {
        if (rows.isEmpty()) {
            return terminalBalance;
        }
        return rows.getFirst().openingGca();
    }

    public int periods() {
        return rows.size();
    }

    /**
     * The row for a period ordinal.
     *
     * @throws IllegalArgumentException if no row carries that ordinal
     */
    public AmortisationRow row(int period) {
        for (AmortisationRow row : rows) {
            if (row.period() == period) {
                return row;
            }
        }
        throw new IllegalArgumentException("no row for period " + period);
    }

    /**
     * The whole roll-forward as a single row, for a caller reporting over one
     * accounting period.
     *
     * <h2>Why an accounting period may contain several accrual periods</h2>
     *
     * <p><b>Because a contract's accrual calendar is its own and the ledger's is
     * the bank's.</b> A weekly or fortnightly facility accrues four or two times
     * inside one accounting month; those are real accrual boundaries and the
     * engine is right to produce a row for each. What the month-end close
     * publishes is nevertheless one movement per contract per period, because
     * that is what a period balance, a journal and a reconciliation are
     * statements about.
     *
     * <p>{@code ContractPipeline} used to refuse this outright — "produced N
     * accrual boundaries from its vector; the month-end loop computes exactly
     * one" — which reinstated one layer up exactly the abort
     * {@code CompoundingBasis.stepOf} goes to explicit trouble to avoid for
     * these two frequencies. The refusal's stated reason was sound: the
     * decomposition, the suspense movement and the reconciliation would
     * otherwise describe the first boundary while the balance moved over all of
     * them. The answer is to give them a row that describes all of them, not to
     * refuse the contract.
     *
     * <h2>Why the summation is exact rather than approximately right</h2>
     *
     * <p>{@link AmortisationRow}'s constructor enforces
     * {@code opening + interest - cash == closing} on every row it admits, and
     * that identity is preserved by telescoping: with the opening of the first
     * row, the closing of the last, and the two columns summed, the intermediate
     * balances cancel term by term. So this is not a re-derivation that might
     * disagree with the rows — the row it returns satisfies the same invariant on
     * the same figures, and the constructor would reject it if it did not. No
     * rounding happens here: both columns are summed at working precision, which
     * is why an accounting period of four weekly accruals carries the same
     * balance forward as four separate periods would.
     *
     * <p><b>The accrual exponent is summed, and that is the substantive choice.</b>
     * The exponent measures elapsed accrual time, so four weekly periods under
     * {@code PERIODIC_INDEX(52)} give {@code 4} — four fifty-secondths of a year,
     * which is what elapsed. Taking the last row's exponent instead would report
     * one week for a month's accrual, and taking a mean would report a figure no
     * period had. The date is the last boundary's, because that is when the
     * closing balance is the closing balance.
     *
     * <p><b>What this deliberately does not do</b> is collapse the rows away.
     * They stay on this result, so FR-808's trace can still show a reader the
     * four weekly accruals behind one published monthly figure. This returns a
     * projection for the close; it is not a lossy transformation of the roll.
     *
     * @param periodOrdinal the accounting period's own ordinal, which is not any
     *                      row's: the rows count accrual boundaries from
     *                      inception and the close counts accounting periods.
     *                      Passed in rather than derived, so that a wrong
     *                      mapping is visible at the call site instead of buried
     *                      in an offset. {@code ContractualLegInterest} in
     *                      {@code eir-policy} states the same rule for the same
     *                      pairing; named in prose rather than linked, because
     *                      that module is the layer above this one.
     * @throws IllegalStateException where there are no rows at all. A vector that
     *                      produced no accrual boundary is not a period of no
     *                      movement: it is a contract the projector could not
     *                      place in this period, and returning a nil row would
     *                      publish an exposure that accrued nothing and
     *                      reconciled perfectly.
     */
    public AmortisationRow asOneAccrualPeriod(int periodOrdinal) {
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                "no accrual boundaries to report as one accounting period; a vector that produced"
                    + " no boundary cannot be summarised into a movement, and a nil row would"
                    + " publish a contract that accrued nothing and reconciled perfectly");
        }
        if (rows.size() == 1 && rows.getFirst().period() == periodOrdinal) {
            return rows.getFirst();
        }
        AmortisationRow first = rows.getFirst();
        AmortisationRow last = rows.getLast();
        Money interest = Money.zero(first.openingGca().currency());
        Money cash = Money.zero(first.openingGca().currency());
        BigDecimal exponent = BigDecimal.ZERO;
        for (AmortisationRow row : rows) {
            interest = interest.plus(row.interestAccrued());
            cash = cash.plus(row.cashReceived());
            exponent = exponent.add(row.accrualExponent());
        }
        // No check that the collapsed closing equals the last row's, and its absence is
        // deliberate. One was written here first -- "the intermediate balances did not
        // telescope" -- and it turned out to be unreachable: this record's own constructor
        // already refuses a rows list whose balances do not chain ("balance chain broken at
        // period N: opening X does not continue Y"). No AmortisationResult can exist with a
        // discontinuity, so no call to this method can encounter one, and a control that cannot
        // fail is worse than an absent one. The constructor's guard is the strictly stronger
        // statement, and AmortisationResultCollapseTest pins it there instead -- because the
        // exactness of this summation depends on it.
        //
        // AmortisationRow.of derives the closing from the movements rather than taking it, so the
        // row returned cannot carry a closing that disagrees with the two columns above either.
        return AmortisationRow.of(
            periodOrdinal, last.date(), exponent, first.openingGca(), interest, cash);
    }

    /**
     * Lifetime interest as it is reported: the working-precision total, rounded
     * once. This is the figure the reference cases print.
     */
    public Money presentedTotalInterest() {
        return totalInterest.atPresentationScale();
    }

    /** The sum of the presented interest column, which is what a reader adds up. */
    public Money presentedInterestColumnSum() {
        Money sum = Money.zero(totalInterest.currency());
        for (AmortisationRow row : rows) {
            sum = sum.plus(row.presentedEirInterest());
        }
        return sum;
    }

    /**
     * {@link #presentedTotalInterest()} less {@link #presentedInterestColumnSum()}
     * — the rounding difference between the reported total and the column that
     * appears to produce it. A paise on a 24-period loan, and it belongs in a
     * rounding line rather than inside either figure.
     */
    public Money interestColumnResidue() {
        return presentedTotalInterest().minus(presentedInterestColumnSum());
    }

    public Money presentedTotalCash() {
        return totalCash.atPresentationScale();
    }

    public Money presentedTerminalBalance() {
        return terminalBalance.atPresentationScale();
    }

    /** Invariants that failed. Empty on a clean run. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    public boolean isClean() {
        return breaches().isEmpty();
    }

    /**
     * Returns this result, or throws on the first breach.
     *
     * @throws InvariantBreachException if any asserted invariant failed
     */
    public AmortisationResult orThrow() {
        for (InvariantResult result : invariants) {
            result.orThrow();
        }
        return this;
    }
}
