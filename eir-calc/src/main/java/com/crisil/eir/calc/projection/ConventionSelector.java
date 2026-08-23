package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.DayCount;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Chooses the time convention for a projected vector (calculation specification
 * section 3.10, FR-303).
 *
 * <pre>
 * if all periods uniform AND all flows on period boundaries AND no broken period:
 *       periodic-index          # cheaper, and exactly equivalent here
 * else: actual-date             # required for correctness
 * </pre>
 *
 * <p>Actual dating is the <b>default and the fallback</b>. Periodic indexing is an
 * optimisation whose precondition is <em>checked</em> — by
 * {@link FlowVector#periodicIndexEligible(int)}, on the vector that will actually
 * be discounted — and never assumed. A moratorium, a mid-period disbursement, an
 * actual-dated 28/31-day month pair or a broken first period all void it. The
 * check is cheap; guessing is not, and guessing wrong is a wrong rate rather than
 * a slow one.
 *
 * <p>The asymmetry is deliberate. Falling back to actual dating where periodic
 * indexing would have been valid costs arithmetic. Taking periodic indexing where
 * it is invalid costs correctness, silently, on the instruments where day-count
 * sensitivity is highest — short tenors, where a three-day error is a materially
 * wrong rate.
 */
public final class ConventionSelector {

    private ConventionSelector() {
    }

    /**
     * The convention and why it was chosen, for the computation trace.
     *
     * @param convention             what to discount with
     * @param periodicIndexEligible  whether the cheaper convention's precondition held
     * @param basis                  the reason, in words, for the audit record
     */
    public record Choice(TimeConvention convention, boolean periodicIndexEligible, String basis) {

        public Choice {
            Objects.requireNonNull(convention, "convention");
            Objects.requireNonNull(basis, "basis");
        }
    }

    /**
     * Checks the periodic-index precondition and returns the convention with its
     * basis.
     *
     * @param vector         the vector that will be discounted, inception leg included
     * @param periodsPerYear the schedule's compounding frequency
     * @param dayCount       the instrument's day count, used by the fallback
     */
    public static Choice choose(FlowVector vector, int periodsPerYear, DayCount dayCount) {
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(dayCount, "dayCount");
        if (vector.periodicIndexEligible(periodsPerYear)) {
            return new Choice(
                new TimeConvention.PeriodicIndex(periodsPerYear),
                true,
                "periods uniform at x" + periodsPerYear + ", every flow on a period boundary,"
                    + " no broken period: periodic indexing is exactly equivalent and cheaper");
        }
        return new Choice(
            new TimeConvention.ActualDate(dayCount),
            false,
            "periodic-index precondition not met at x" + periodsPerYear
                + ": actual dating under " + dayCount.conventionName() + " is required for correctness");
    }

    /** {@link #choose} where only the convention is wanted. */
    public static TimeConvention select(FlowVector vector, int periodsPerYear, DayCount dayCount) {
        return choose(vector, periodsPerYear, dayCount).convention();
    }

    /**
     * The same rate restated in the units a convention implies.
     *
     * <p>A {@link Rate} carries its compounding frequency, and a
     * {@link TimeConvention} carries the units tau is measured in. The two must
     * agree, because under periodic indexing tau is an ordinal and the rate is the
     * per-period rate, while under actual dating tau is a year fraction and the
     * rate is the annual effective one. Pairing a per-period rate with a year
     * fraction is an annualisation round-trip in disguise and produces a
     * plausible, wrong number, so both the amortisation roll-forward and the
     * discounting path reject a mismatched pair outright rather than coercing it.
     *
     * <p>This is the seam that makes them agree, and it exists because the two
     * halves of a projection are quoted differently. A contract's rate is quoted
     * in the schedule's own frequency — 1% per month — but the convention selected
     * for its vector is not always the matching periodic index: a moratorium, a
     * capitalising drawdown period or a single-flow bullet all fail the
     * periodic-index precondition ({@link #choose}) and fall back to actual
     * dating, whose periodsPerYear is one. A caller rolling the <em>contractual</em>
     * leg of such a vector holds a monthly rate and an actual-date convention, and
     * needs the annual effective form of the same rate to do it. Converting is
     * exact and it is not optional: the precondition failed, so period ordinals
     * are not a valid measure of time on this vector at all.
     *
     * <p>The returned rate is at storage precision, twelve decimal places
     * (specification 1.4), because it is a rate a ledger rolls forward with and
     * therefore one that a replay must reproduce from the persisted figure.
     *
     * @param convention the convention the vector will be discounted or rolled under
     * @param rate       the rate as quoted on the contract
     * @return {@code rate} unchanged where the convention already matches its
     *     frequency, otherwise its annual effective equivalent
     * @throws IllegalArgumentException where the convention is a periodic index at
     *     a different frequency from the rate. That is not a units mismatch this
     *     method can repair — it means the schedule and the rate disagree about
     *     the compounding period, which is a projection defect.
     */
    public static Rate rateUnder(TimeConvention convention, Rate rate) {
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(rate, "rate");
        return switch (convention) {
            case TimeConvention.PeriodicIndex periodic -> {
                requireSameFrequency(periodic, rate);
                yield rate;
            }
            case TimeConvention.ActualDate ignored -> Rate.annualEffective(rate.effectiveAnnual());
        };
    }

    /**
     * {@link #rateUnder} as a bare value, for arithmetic that is not persisting a
     * rate.
     *
     * <p>Unrounded under actual dating, where {@link #rateUnder} rounds to storage
     * precision. The distinction matters in one direction only: a rate a ledger
     * rolls with must be the rate that was published, so it is rounded; an
     * intermediate used to derive a cash flow inside one projection is not a
     * published rate and rounding it would import a twelfth-decimal error into a
     * figure the fixtures assert to more places than that.
     */
    static BigDecimal rateValueUnder(TimeConvention convention, Rate rate) {
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(rate, "rate");
        return switch (convention) {
            case TimeConvention.PeriodicIndex periodic -> {
                requireSameFrequency(periodic, rate);
                yield rate.periodic();
            }
            case TimeConvention.ActualDate ignored -> rate.effectiveAnnual();
        };
    }

    private static void requireSameFrequency(TimeConvention.PeriodicIndex periodic, Rate rate) {
        if (periodic.periodsPerYear() != rate.periodsPerYear()) {
            throw new IllegalArgumentException(
                "convention compounds x" + periodic.periodsPerYear()
                    + " but the rate is quoted x" + rate.periodsPerYear());
        }
    }
}
