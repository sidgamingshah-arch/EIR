package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
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
