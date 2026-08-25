package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * How the principal reaches the borrower — the first of the eight dimensions a
 * {@link ScheduleBlueprint} composes.
 *
 * <p>Separate from the repayment profile because the two vary independently: a
 * tranched facility can amortise as a level annuity, and a single-draw loan can be
 * sculpted. Collapsing them is what forces a shape enum to enumerate combinations.
 */
public sealed interface DisbursementProfile {

    /** Total principal the profile advances. */
    Money notional();

    /** A short label for the computation trace. */
    String label();

    /**
     * Whether the whole notional is in the borrower's hands on {@code valueDate}.
     *
     * <p>The par-pricing precondition ST-13 needs from this dimension, and the reason is
     * arithmetic rather than economic: the contractual leg rolls forward from the full
     * notional, so principal still undrawn at inception shows up in the par gap as itself.
     * Measured on a three-draw facility advancing 400,000 at inception and 600,000 over the
     * next six months, the gap is exactly 600,000.00 — the undrawn amount, to the paisa.
     * Nothing is wrong with that facility; par is simply not a claim about it.
     */
    boolean advancesInFullAtInception(LocalDate valueDate);

    /** One advance on one date. The ordinary case. */
    record Single(LocalDate drawnOn, Money amount) implements DisbursementProfile {

        public Single {
            Objects.requireNonNull(drawnOn, "drawnOn");
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a disbursement advances a positive amount, got " + amount);
            }
        }

        @Override
        public Money notional() {
            return amount;
        }

        @Override
        public String label() {
            return "SINGLE";
        }

        @Override
        public boolean advancesInFullAtInception(LocalDate valueDate) {
            // Deliberately conservative on a late single draw. Measured, such a schedule
            // still shows a par gap of 0.00, because the ladder anchors on the value date
            // and the draw date does not currently reach the contractual vector. Returning
            // true would therefore be correct today and would silently become a false
            // breach the moment a delayed draw is projected as the period-1 outflow it is.
            // A skipped check costs coverage on a rare case; a spurious one costs trust in
            // the control on every case.
            return drawnOn.equals(valueDate);
        }
    }

    /**
     * Milestone drawdowns — project and infrastructure finance.
     *
     * <p>Two consequences the engine has to carry, and they are the reason this is
     * its own variant rather than a list of {@link Single}s.
     *
     * <p>First, drawdowns produce <b>negative flows after inception</b>, so the
     * present-value function can change sign more than once and the solver may face
     * multiple real roots. That is not hypothetical here: it is the shape that
     * produces it in practice.
     *
     * <p>Second, the projected schedule never matches the actual one, so the rate
     * struck at financial closure is stale by first drawdown. Re-estimation triggers
     * on <b>cumulative</b> deviation past a tolerance, not on every draw — a
     * per-draw trigger churns the book for no informational gain, and every one of
     * those events carries a {@code DISBURSEMENT_TIMING} driver that routes to a
     * catch-up.
     *
     * @param projected the schedule struck at financial closure
     * @param actual    draws as they happened; empty before first drawdown
     * @param cumulativeDeviationTolerance fraction of notional by which cumulative
     *     actual may diverge from projected before a re-estimation is triggered
     */
    record Tranched(
        List<Tranche> projected,
        List<Tranche> actual,
        BigDecimal cumulativeDeviationTolerance) implements DisbursementProfile {

        public Tranched {
            Objects.requireNonNull(projected, "projected");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(cumulativeDeviationTolerance, "cumulativeDeviationTolerance");
            if (projected.isEmpty()) {
                throw new IllegalArgumentException(
                    "a tranched profile needs a projected schedule; with one draw use Single");
            }
            if (cumulativeDeviationTolerance.signum() < 0) {
                throw new IllegalArgumentException(
                    "tolerance must not be negative, got " + cumulativeDeviationTolerance.toPlainString());
            }
            projected = List.copyOf(projected);
            actual = List.copyOf(actual);
        }

        @Override
        public Money notional() {
            Money total = Money.zero(projected.get(0).amount().currency());
            for (Tranche tranche : projected) {
                total = total.plus(tranche.amount());
            }
            return total;
        }

        /** Cumulative actual draws to date. */
        public Money drawnToDate() {
            Money total = Money.zero(projected.get(0).amount().currency());
            for (Tranche tranche : actual) {
                total = total.plus(tranche.amount());
            }
            return total;
        }

        @Override
        public String label() {
            return "TRANCHED(" + projected.size() + " projected, " + actual.size() + " drawn)";
        }

        @Override
        public boolean advancesInFullAtInception(LocalDate valueDate) {
            for (Tranche tranche : projected) {
                if (!tranche.drawnOn().equals(valueDate)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A limit drawn and repaid at the borrower's discretion — cash credit,
     * overdraft, credit cards, KCC.
     *
     * <p>There is no drawdown schedule to project and no contractual repayment
     * schedule to amortise, so no conventional EIR can be struck on the funded
     * balance. ACPIR 54 contemplates exactly this and permits an approximation.
     * Carrying it as its own variant is what stops a revolver being bent into an
     * annuity shape to reuse code, which would produce a number with no meaning.
     *
     * @param limit             the sanctioned limit
     * @param averageUtilisation expected drawn fraction of the limit
     */
    record UtilisationDriven(Money limit, BigDecimal averageUtilisation) implements DisbursementProfile {

        public UtilisationDriven {
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(averageUtilisation, "averageUtilisation");
            if (!limit.isPositive()) {
                throw new IllegalArgumentException("limit must be positive, got " + limit);
            }
            if (averageUtilisation.signum() < 0 || averageUtilisation.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                    "averageUtilisation is a fraction in [0,1], got " + averageUtilisation.toPlainString());
            }
        }

        @Override
        public Money notional() {
            return limit.times(averageUtilisation);
        }

        @Override
        public String label() {
            return "UTILISATION_DRIVEN";
        }

        @Override
        public boolean advancesInFullAtInception(LocalDate valueDate) {
            // A revolver draws and repays at the borrower's discretion; there is no
            // inception advance to be in full. ACPIR 54 already contemplates that no
            // conventional EIR is struck here.
            return false;
        }
    }
}
