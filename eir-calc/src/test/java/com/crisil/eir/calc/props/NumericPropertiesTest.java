package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.DayCount;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;

/**
 * The three value types every figure in the engine passes through, as properties.
 *
 * <p>These are the cheapest properties in the suite and the ones with the widest
 * blast radius: {@link Rate} decides what "the persisted rate" means,
 * {@link Money} decides whether a movement schedule adds up, and
 * {@link DayCountConvention} decides every discount factor on an actual-dated
 * vector. A defect in any of them is a defect in every figure downstream, and none
 * of them is complicated enough for a defect to be visible by reading.
 */
class NumericPropertiesTest {

    @Provide
    Arbitrary<DayCountConvention> dayCounts() {
        return Arbitraries.of(DayCountConvention.values());
    }

    // ------------------------------------------------------------------- Rate

    /**
     * Storing a rate is idempotent: the rate that comes back out of a
     * {@link Rate} is already stored, so re-storing it changes nothing — not its
     * value and not its scale.
     *
     * <p>This is the property that makes specification 1.4 implementable. "Solve at
     * 28 digits, round to 12, then roll forward with the rounded value" only
     * reproduces a published amortisation if the rounding is a fixed point: a
     * round-trip through the ledger, the database and back must not move the rate
     * again. Scale is asserted alongside value because a rate stored at scale 12 and
     * the same number at scale 9 are the same rate and not the same persisted
     * figure, and {@code Rate.equals} compares numerically by design.
     */
    @Property(tries = 10000, seed = "20270411")
    void storingARateIsIdempotentInValueAndInScale(
            @ForAll @BigRange(min = "-0.9", max = "5") @Scale(20) BigDecimal raw,
            @ForAll("compoundingFrequencies") int periodsPerYear) {

        Rate once = Rate.periodic(raw, periodsPerYear);
        Rate twice = Rate.periodic(once.periodic(), periodsPerYear);

        assertThat(twice.periodic().toPlainString())
            .as("re-storing %s must not move it again", once.periodic().toPlainString())
            .isEqualTo(once.periodic().toPlainString());
        assertThat(once.periodic().scale()).isEqualTo(Precision.RATE_SCALE);
        assertThat(once.periodic())
            .as("the constructor rounds HALF_UP to twelve places")
            .isEqualByComparingTo(raw.setScale(Precision.RATE_SCALE, RoundingMode.HALF_UP));
        assertThat(Precision.storedRate(Precision.storedRate(raw)).toPlainString())
            .isEqualTo(Precision.storedRate(raw).toPlainString());
    }

    /**
     * The two annualisations are the two formulae, exactly, and neither is derived
     * from the other.
     *
     * <p>Case 1's monthly 1.04214918% is 13.248094% effective and 12.505790%
     * nominal. Both persist, both are labelled, and interest is always computed from
     * the periodic rate — so the only way an annualisation can corrupt a figure is
     * if one of these two is quietly the other, which is what this checks.
     */
    @Property(tries = 10000, seed = "20270412")
    void theTwoAnnualisationsAreTheTwoFormulae(
            @ForAll @BigRange(min = "0", max = "0.5") @Scale(12) BigDecimal periodic,
            @ForAll("compoundingFrequencies") int periodsPerYear) {

        Rate rate = Rate.periodic(periodic, periodsPerYear);

        assertThat(rate.nominalAnnual())
            .as("nominal annual is the periodic rate times the frequency")
            .isEqualByComparingTo(rate.periodic().multiply(BigDecimal.valueOf(periodsPerYear)));
        assertThat(rate.effectiveAnnual())
            .as("effective annual is (1+r)^p - 1")
            .isEqualByComparingTo(
                Precision.onePlusPow(rate.periodic(), periodsPerYear).subtract(BigDecimal.ONE));
        assertThat(rate.effectiveAnnual().compareTo(rate.nominalAnnual()) >= 0)
            .as("compounding cannot make the effective rate the smaller of the two")
            .isTrue();
        assertThat(rate.effectiveAnnualBps())
            .as("basis points are the effective annual rate scaled, not a rounded report of it")
            .isEqualByComparingTo(rate.effectiveAnnual().multiply(new BigDecimal("10000")));

        Rate annual = Rate.annualEffective(rate.effectiveAnnual());
        assertThat(annual.periodsPerYear()).isEqualTo(1);
        assertThat(annual.effectiveAnnual()).isEqualByComparingTo(annual.periodic());
    }

    @Provide
    Arbitrary<Integer> compoundingFrequencies() {
        return Arbitraries.of(1, 2, 4, 12);
    }

    // ------------------------------------------------------------------ Money

    /**
     * Adding an amount and taking it away again returns the original, exactly.
     *
     * <p>The band matters and is stated rather than assumed: the working precision
     * keeps 28 significant digits, so a paise added to a quadrillion is not
     * recoverable — the sum has already lost it. No figure in this engine spans that
     * range. A 100,000,000 principal carries ten digits before the point and the
     * engine never carries more than six after it in a cash figure, so the widest
     * addition it performs needs seventeen and has eleven to spare. Generating
     * outside that band would test the arithmetic of a system nobody is building.
     */
    @Property(tries = 10000, seed = "20270413")
    void addingAndRemovingTheSameAmountIsExact(
            @ForAll @BigRange(min = "-10000000000", max = "10000000000") @Scale(6) BigDecimal left,
            @ForAll @BigRange(min = "-10000000000", max = "10000000000") @Scale(6) BigDecimal right) {

        Money first = Money.of(left, Money.INR);
        Money second = Money.of(right, Money.INR);

        assertThat(first.plus(second).minus(second))
            .as("%s + %s - %s", left.toPlainString(), right.toPlainString(), right.toPlainString())
            .isEqualTo(first);
        assertThat(first.plus(second)).isEqualTo(second.plus(first));
        assertThat(first.minus(second)).isEqualTo(second.minus(first).negate());
        assertThat(first.negate().negate()).isEqualTo(first);
        assertThat(first.abs().isNegative()).isFalse();
        assertThat(first.times(BigDecimal.ONE)).isEqualTo(first);
        assertThat(first.signum()).isEqualTo(left.signum());
        assertThat(first.isZero()).isEqualTo(left.signum() == 0);
    }

    /**
     * Reducing to currency scale is idempotent, and equality does not depend on
     * scale.
     *
     * <p>Presentation scale is the only place a money value loses precision (1.3) and
     * it is applied once per persisted figure. Idempotence is what lets a caller
     * apply it defensively — at the ledger boundary and again in a report — without
     * double-rounding a figure. Scale-insensitive equality is what lets a published
     * 0.00 compare equal to a computed zero.
     */
    @Property(tries = 10000, seed = "20270414")
    void reducingToPresentationScaleIsIdempotent(
            @ForAll @BigRange(min = "-10000000000", max = "10000000000") @Scale(9) BigDecimal amount,
            @ForAll @IntRange(min = 0, max = 6) int trailingZeros) {

        Money value = Money.of(amount, Money.INR);
        Money presented = value.atPresentationScale();

        assertThat(presented.atPresentationScale()).isEqualTo(presented);
        assertThat(presented.amount().scale()).isEqualTo(value.presentationScale());
        assertThat(presented.amount().scale()).isEqualTo(2);
        assertThat(presented.amount())
            .isEqualByComparingTo(amount.setScale(2, RoundingMode.HALF_UP));

        Money padded = Money.of(amount.setScale(amount.scale() + trailingZeros), Money.INR);
        assertThat(padded)
            .as("equality is by value: %s against %s", padded.amount(), value.amount())
            .isEqualTo(value);
        assertThat(padded.hashCode()).isEqualTo(value.hashCode());
    }

    // -------------------------------------------------------------- day counts

    /**
     * A year fraction is never negative, is nil over no time at all, and never
     * shrinks as the end date moves out — within each convention's own domain.
     *
     * <p>The domain qualifier is not a hedge, it is the definition of ACT/365L.
     * Its denominator is 366 where 29 February falls inside the interval and 365
     * otherwise, so admitting the leap day both adds a day to the numerator and adds
     * one to the denominator. For an interval already longer than 365 days the
     * denominator wins: {@code (d+1)/366 < d/365} exactly when {@code d > 365}. The
     * convention is defined for a coupon period, and a coupon period is not longer
     * than a year; over its own domain it is monotonic, and
     * {@link InvariantBoundaryCasesTest#actualThreeSixtyFiveLeapIsNotMonotonicBeyondOneYear()}
     * exhibits the pair of dates where it stops being so. Every other convention here
     * is monotonic over any window, which is asserted out to forty years.
     */
    @Property(tries = 10000, seed = "20270415")
    void yearFractionsAreNonNegativeAndMonotonicInTheEndDate(
            @ForAll("dayCounts") DayCountConvention convention,
            @ForAll @IntRange(min = 2020, max = 2032) int year,
            @ForAll @IntRange(min = 1, max = 12) int month,
            @ForAll @IntRange(min = 1, max = 28) int day,
            @ForAll @IntRange(min = 0, max = 14600) int firstOffset,
            @ForAll @IntRange(min = 0, max = 14600) int secondOffset) {

        LocalDate start = LocalDate.of(year, month, day);
        int nearer = Math.min(firstOffset, secondOffset);
        int further = Math.max(firstOffset, secondOffset);
        BigDecimal atNearer = convention.yearFraction(start, start.plusDays(nearer));
        BigDecimal atFurther = convention.yearFraction(start, start.plusDays(further));

        assertThat(convention.yearFraction(start, start).signum())
            .as("%s over no time at all", convention.conventionName())
            .isZero();
        assertThat(atNearer.signum()).as("%s is never negative", convention.conventionName())
            .isNotNegative();
        boolean insideItsOwnDomain =
            convention != DayCountConvention.ACT_365L || further <= 365;
        if (insideItsOwnDomain) {
            assertThat(atFurther)
                .as("%s from %s: %s days gives %s, %s days gives %s",
                    convention.conventionName(), start, nearer, atNearer.toPlainString(),
                    further, atFurther.toPlainString())
                .isGreaterThanOrEqualTo(atNearer);
        }
        assertThat(convention.conventionName()).isNotBlank();
    }

    /**
     * The conventions that agree on the numerator and differ on the denominator
     * stay in that order, and a whole year on a 30/360 basis is exactly one.
     *
     * <p>ACT/360 divides the same actual days by a shorter year, so it must return
     * the larger fraction — the reason a mixed and undocumented convention produces
     * a permanent, unexplained reconciliation difference between treasury and
     * finance (3.9). The 30/360 identity is the anchor of the other family: twelve
     * synthetic months of thirty days is a year, exactly, with nothing left over,
     * which is what makes the convention worth having on a term loan.
     */
    @Property(tries = 10000, seed = "20270416")
    void denominatorOrderingAndTheThirtyThreeSixtyYearHold(
            @ForAll @IntRange(min = 2020, max = 2032) int year,
            @ForAll @IntRange(min = 1, max = 12) int month,
            @ForAll @IntRange(min = 1, max = 28) int day,
            @ForAll @IntRange(min = 0, max = 3650) int days) {

        LocalDate start = LocalDate.of(year, month, day);
        LocalDate end = start.plusDays(days);

        assertThat(DayCountConvention.ACT_360.yearFraction(start, end))
            .as("ACT/360 divides the same days by a shorter year than ACT/365F")
            .isGreaterThanOrEqualTo(DayCountConvention.ACT_365F.yearFraction(start, end));

        for (DayCount thirtyThreeSixty : new DayCount[] {
                DayCountConvention.THIRTY_360_BOND, DayCountConvention.THIRTY_E_360}) {
            assertThat(thirtyThreeSixty.yearFraction(start, start.plusYears(1)))
                .as("%s over a whole year from %s", thirtyThreeSixty.conventionName(), start)
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(thirtyThreeSixty.yearFraction(start, start.plusMonths(6)))
                .as("%s over half a year from %s", thirtyThreeSixty.conventionName(), start)
                .isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(thirtyThreeSixty.yearFraction(start, start.plusMonths(3)))
                .as("%s over a quarter from %s", thirtyThreeSixty.conventionName(), start)
                .isEqualByComparingTo(new BigDecimal("0.25"));
        }
    }
}
