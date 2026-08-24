package com.crisil.eir.calc.projection.blueprint;

import java.time.LocalDate;
import java.util.Objects;

/**
 * When interest is paid — and, critically, whether unpaid interest compounds.
 *
 * <p>This is the dimension most often collapsed into the moratorium, and they are
 * not the same thing. Servicing says when <em>interest</em> leaves; the
 * {@link Moratorium} says when <em>principal</em> does. A principal holiday with
 * interest serviced and a full holiday with interest capitalising differ only here.
 *
 * <p>The distinction is worth real money. On a 1,000,000 loan at 12% with a
 * 12-period holiday, capitalising the interest accrues 126,825.03 while deferring
 * it simple accrues 120,000.00 — and the resulting EIRs differ by 34.6 basis
 * points. Compounding versus simple deferral is an economic difference, not a
 * presentational one, which is why {@link DeferredSimple} cannot share a code path
 * with {@link CapitalisedEachPeriod}.
 */
public sealed interface InterestServicing {

    String label();

    /** Whether unpaid interest is added to the balance and itself bears interest. */
    boolean compounds();

    /** Interest leaves as cash every period. */
    record ServicedEachPeriod() implements InterestServicing {
        @Override
        public String label() {
            return "SERVICED_EACH_PERIOD";
        }

        @Override
        public boolean compounds() {
            return false;
        }
    }

    /**
     * Interest is added to the gross carrying amount and itself bears interest.
     *
     * <p>Education loans through the course-and-grace period; project finance
     * interest during construction pre-COD.
     *
     * <p>ACPIR 9(6)(i) produces two consequences from this one feature and they
     * must not be allowed to imply one another: the interest becomes due only after
     * the holiday, so it is <b>not overdue</b> in the interim — a staging input —
     * while the capitalisation is an <b>EIR</b> input. A system that lets the
     * non-overdue status suppress the accretion, or lets the accretion imply an
     * overdue, is wrong in one of two different directions.
     */
    record CapitalisedEachPeriod() implements InterestServicing {
        @Override
        public String label() {
            return "CAPITALISED_EACH_PERIOD";
        }

        @Override
        public boolean compounds() {
            return true;
        }
    }

    /**
     * Interest accrues without compounding and settles as a single lump.
     *
     * <p>The FITL shape, and the treatment where a holiday defers interest without
     * capitalising it. Worth materially less to the lender than capitalising —
     * see the class comment.
     *
     * @param settlementDate when the accrued lump falls due
     */
    record DeferredSimple(LocalDate settlementDate) implements InterestServicing {

        public DeferredSimple {
            Objects.requireNonNull(settlementDate, "settlementDate");
        }

        @Override
        public String label() {
            return "DEFERRED_SIMPLE(" + settlementDate + ")";
        }

        @Override
        public boolean compounds() {
            return false;
        }
    }

    /**
     * Interest collected at inception: the discount <em>is</em> the interest.
     *
     * <p>T-bills, commercial paper, certificates of deposit, bills purchased and
     * discounted. The cleanest products in the book, because the EIR is already
     * implicit in existing practice.
     */
    record DiscountedUpfront() implements InterestServicing {
        @Override
        public String label() {
            return "DISCOUNTED_UPFRONT";
        }

        @Override
        public boolean compounds() {
            return false;
        }
    }

    /**
     * An interest-only period, then combined instalments.
     *
     * @param interestOnlyPeriods periods serviced on interest alone before
     *     principal begins to amortise
     */
    record ServicedThenCombined(int interestOnlyPeriods) implements InterestServicing {

        public ServicedThenCombined {
            if (interestOnlyPeriods < 1) {
                throw new IllegalArgumentException(
                    "an interest-only phase spans at least one period; for none use"
                        + " ServicedEachPeriod, got " + interestOnlyPeriods);
            }
        }

        @Override
        public String label() {
            return "SERVICED_THEN_COMBINED(" + interestOnlyPeriods + ")";
        }

        @Override
        public boolean compounds() {
            return false;
        }
    }
}
