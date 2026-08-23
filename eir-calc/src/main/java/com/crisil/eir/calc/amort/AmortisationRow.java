package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One period of an amortised-cost roll-forward, carried at working precision and
 * readable at presentation scale (calculation specification sections 5.1, 5.7).
 *
 * <p>The four money columns are the movement schedule: {@code opening + interest
 * - cash = closing}. They are stored at {@link Precision#WORKING}, because the
 * roll-forward is the ledger the rate was solved against and rounding it every
 * period would make it something else. Rounding a 24-period retail loan at each
 * step moves the terminal balance from zero to a paise and drops a paise off the
 * lifetime interest total; over 360 periods it does worse. So the authoritative
 * ledger is unrounded and rounding happens on the way out, once per figure.
 *
 * <p><strong>Two precisions, one balance.</strong> Every {@code presented*}
 * accessor is the corresponding working figure at the currency's minor units, so
 * the presented balance column ties down the page for free: this row's presented
 * opening is the previous row's presented closing, because the same identity
 * holds at working precision first. What rounding can break is the
 * <em>horizontal</em> tie within a row — {@code 958,295.91 + 9,986.87 -
 * 47,073.47} is {@code 921,209.31} while the presented closing balance is
 * {@code 921,209.32}, a paise apart in reference case 1 period 2. That paise is
 * reported as {@link #presentedRoundingResidue()} rather than left for a reader
 * to find: a published movement schedule whose columns do not sum is a defect
 * even when every figure is individually correct (specification section 5.7), and
 * the residue column is the rule that accounts for the difference. Resolving it
 * by tolerance, or by re-deriving the balance from the rounded components and
 * letting the balance column drift away from the ledger, both hide the same
 * thing.
 *
 * <p>Signs follow {@link Money}: an asset opens positive and accretes positive
 * interest; a liability opens negative and everything inverts, with no separate
 * path.
 *
 * @param period           1-based period ordinal
 * @param openingGca       carrying amount at the start of the period
 * @param eirInterest      interest accreted over the period at the row's own rate
 * @param cashReceived     net cash received in the period, holder's sign
 * @param closingGca       {@code openingGca + eirInterest - cashReceived}
 * @param date             the period boundary the accrual runs to
 * @param accrualExponent  periods accreted: {@code 1} for a whole compounding
 *                         period, a day-counted fraction for a broken one
 */
public record AmortisationRow(
    int period,
    Money openingGca,
    Money eirInterest,
    Money cashReceived,
    Money closingGca,
    LocalDate date,
    BigDecimal accrualExponent) {

    public AmortisationRow {
        Objects.requireNonNull(openingGca, "openingGca");
        Objects.requireNonNull(eirInterest, "eirInterest");
        Objects.requireNonNull(cashReceived, "cashReceived");
        Objects.requireNonNull(closingGca, "closingGca");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(accrualExponent, "accrualExponent");
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, got " + period);
        }
        if (accrualExponent.signum() <= 0) {
            throw new IllegalArgumentException(
                "accrualExponent must be positive, got " + accrualExponent.toPlainString());
        }
        Money derived = openingGca.plus(eirInterest).minus(cashReceived);
        if (!derived.equals(closingGca)) {
            throw new IllegalArgumentException(
                "row does not roll forward: " + openingGca + " + " + eirInterest + " - " + cashReceived
                    + " = " + derived + ", not " + closingGca);
        }
    }

    /**
     * Builds a row from its opening position and its two movements, deriving the
     * closing balance. The engine only ever constructs rows this way, so a
     * closing balance that disagrees with the movements cannot be introduced.
     */
    public static AmortisationRow of(
        int period,
        LocalDate date,
        BigDecimal accrualExponent,
        Money openingGca,
        Money interest,
        Money cashReceived) {
        return new AmortisationRow(
            period, openingGca, interest, cashReceived,
            openingGca.plus(interest).minus(cashReceived), date, accrualExponent);
    }

    /**
     * The interest column under a neutral name.
     *
     * <p>The component is called {@code eirInterest} because the EIR leg is what
     * this row usually describes. The same type carries the contractual leg in
     * the two-leg reconciliation, where the figure is contractual interest — the
     * roll-forward arithmetic does not care which rate produced it.
     */
    public Money interestAccrued() {
        return eirInterest;
    }

    /** True where the period is a whole compounding period rather than a broken one. */
    public boolean wholePeriod() {
        return accrualExponent.compareTo(BigDecimal.ONE) == 0;
    }

    public Money presentedOpeningGca() {
        return openingGca.atPresentationScale();
    }

    public Money presentedEirInterest() {
        return eirInterest.atPresentationScale();
    }

    public Money presentedCashReceived() {
        return cashReceived.atPresentationScale();
    }

    public Money presentedClosingGca() {
        return closingGca.atPresentationScale();
    }

    /**
     * What the presented row is missing for its own columns to sum:
     * {@code presented closing - (presented opening + presented interest -
     * presented cash)}.
     *
     * <p>Zero on most rows and at most one minor unit on the rest. Publish it as
     * a rounding column; do not net it into a balance, because the balance column
     * is the one downstream reconciliations tie to.
     */
    public Money presentedRoundingResidue() {
        return presentedClosingGca()
            .minus(presentedOpeningGca().plus(presentedEirInterest()).minus(presentedCashReceived()));
    }

    /** True where the presented row needs no rounding column to sum. */
    public boolean presentedRowSums() {
        return presentedRoundingResidue().isZero();
    }
}
