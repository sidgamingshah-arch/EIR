package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Invariant INV-2 and the shape of the interest profile: two properties about the
 * <em>direction</em> of things, which is where sign errors live.
 *
 * <p>INV-2 is the cheapest check in the specification — one comparison catches a
 * fee classified with the wrong sign and a solver that converged on the wrong root
 * — and it is also the one whose statement needs the most care, because it compares
 * two rates that are not always measured on the same basis.
 *
 * <h2>The two preconditions, and the evidence for each</h2>
 *
 * <p><b>The comparison must be well posed.</b> INV-2 orders the solved EIR against
 * the <em>contractual</em> rate. A contract quotes its rate per period; the solver
 * returns a rate in the units its convention implies. Where the vector licenses
 * periodic indexing those are the same units and the comparison means what it says.
 * Where the vector falls back to actual dating — a moratorium, a broken first
 * period, a month-end drawdown, a bullet with nothing in between — the solved rate
 * is an annual effective rate struck over day-counted time, and the difference
 * between it and the contractual rate's annual effective form contains the
 * convention change as well as the fee. On a 45-day first period the convention
 * term alone is worth seven basis points, which swamps 5,000 of fee on a million:
 * the ordering then reverses with nothing wrong anywhere. So the strict form of
 * INV-2 is asserted over the periodic-index population, and the general population
 * gets the stronger statement below, which has no convention term in it at all.
 *
 * <p><b>The fee must dominate the billed schedule's own rounding.</b> See
 * {@link Generators#materiallyFeeBearingPeriodicIndexContracts()}: under
 * {@code LMS_AUTHORITATIVE} the billed instalment is the true annuity payment
 * rounded to paise, so the schedule's own yield already sits a few parts in 10^8
 * below the contractual rate before any fee is considered.
 * {@link InvariantBoundaryCasesTest} shows the zero-fee case failing INV-2 for
 * exactly that reason, and the same instrument passing under a plug policy.
 */
@Tag("sweep")
class RateOrderingPropertiesTest {

    @Provide
    Arbitrary<Generators.Contract> periodicIndexFeeBearing() {
        return Generators.materiallyFeeBearingPeriodicIndexContracts();
    }

    @Provide
    Arbitrary<Generators.Contract> feeBearing() {
        return Generators.feeBearingContracts();
    }

    @Provide
    Arbitrary<Generators.Contract> levelAnnuities() {
        return Generators.levelAnnuities();
    }

    /**
     * INV-2 as the specification states it: the EIR exceeds the contractual rate
     * when the net integral fee is income and falls short when it is a cost.
     *
     * <p>A net fee received records the asset below par, so it must accrete back up
     * and the yield must exceed the coupon — reference case 1, 13.248094% against
     * 12.682503%, 56.6 basis points for 5,000 of net fee on a million. A net cost
     * paid records it above par and the yield must fall below. Getting this
     * backwards would flatter or understate yield on an entire book while every
     * individual figure looked plausible.
     */
    @Property(tries = 10000, seed = "20270403")
    void theEirIsOrderedAgainstTheContractualRateByTheSignOfTheNetFee(
            @ForAll("periodicIndexFeeBearing") Generators.Contract contract) {

        Generators.Pipeline pipeline = Generators.run(contract);
        assertThat(pipeline.solved()).as("solved: %s", pipeline.label()).isTrue();
        assertThat(pipeline.underPeriodicIndex())
            .as("the population's precondition holds for %s", pipeline.label())
            .isTrue();

        InvariantResult ordering = InvariantChecks.feeSignOrdering(
            pipeline.eir(), contract.terms().contractualRate(), contract.netIntegralFee());
        assertThat(ordering.satisfied())
            .as("INV-2 for %s: %s", pipeline.label(), ordering.detail())
            .isTrue();

        // And directly, so that a change to the invariant helper cannot make the
        // property vacuous: the same ordering read off the two effective annual rates.
        int spread = pipeline.eir().effectiveAnnual()
            .compareTo(contract.terms().contractualRate().effectiveAnnual());
        assertThat(spread)
            .as("EIR %s against contractual %s for net fee %s on %s",
                pipeline.eir().effectiveAnnual().toPlainString(),
                contract.terms().contractualRate().effectiveAnnual().toPlainString(),
                contract.netIntegralFee().atPresentationScale(), pipeline.label())
            .isEqualTo(contract.netIntegralFee().signum());
        assertThat(pipeline.unaccountedBreaches())
            .as("the rest of the reconciliation for %s", pipeline.label())
            .isEmpty();
    }

    /**
     * The convention-free form of INV-2, and the stronger claim: the fee moves the
     * yield away from the yield the same billed schedule produces on its own.
     *
     * <p>Solving the identical vector twice — once with the integral fees and once
     * with them stripped out — removes every term that is not the fee. The billed
     * rounding residue is in both solves, the convention is the same in both, the
     * schedule is the same in both, so the difference between the two rates is the
     * fee and nothing else. That makes this the form of INV-2 that holds on the
     * whole instrument space, actual-dated vectors included, with no floor on the
     * fee at all.
     *
     * <p>The strict inequality is claimed only where the fee is worth more than one
     * unit in the last place of a rate stored at twelve decimal places (1.4). Below
     * that the two solves land on the same persisted rate, which is not a defect: it
     * is the storage precision the specification chose, and a fee too small to move
     * the twelfth decimal is too small to move any published figure.
     */
    @Property(tries = 2000, seed = "20270404")
    void theFeeMovesTheYieldAwayFromTheSchedulesOwnYield(
            @ForAll("feeBearing") Generators.Contract contract) {

        Generators.Pipeline withFee = Generators.run(contract);
        Generators.Pipeline withoutFee = Generators.run(Generators.withoutIntegralFees(contract));
        assertThat(withFee.solved() && withoutFee.solved())
            .as("both solves land for %s", withFee.label())
            .isTrue();

        int direction = withFee.eir().periodic().compareTo(withoutFee.eir().periodic());
        int feeSign = contract.netIntegralFee().signum();
        assertThat(direction * feeSign)
            .as("fee %s moved the yield from %s to %s on %s",
                contract.netIntegralFee().atPresentationScale(),
                withoutFee.eir().periodic().toPlainString(),
                withFee.eir().periodic().toPlainString(), withFee.label())
            .isGreaterThanOrEqualTo(0);

        BigDecimal oneUlpInPresentValue = withoutFee.storedRatePresentValueBound()
            .multiply(BigDecimal.valueOf(2), Precision.WORKING);
        if (contract.netIntegralFee().abs().amount().compareTo(oneUlpInPresentValue) > 0) {
            assertThat(direction)
                .as("fee %s exceeds one stored-rate ULP (%s) and must move the yield on %s",
                    contract.netIntegralFee().atPresentationScale(),
                    oneUlpInPresentValue.toPlainString(), withFee.label())
                .isEqualTo(feeSign);
        }
    }

    /**
     * On a level annuity the carrying amount never rises, and the interest column is
     * monotone in the direction of the yield's own sign.
     *
     * <p>The precondition is stated as narrowly as the theorem needs, and the
     * theorem is worth stating because it is what makes the declining-balance
     * method recognisable in the output at all:
     *
     * <ul>
     *   <li><b>Level instalments.</b> Every future flow is the same amount, which is
     *       checked rather than assumed — a plug policy or a balloon breaks it.</li>
     *   <li><b>Periodic indexing.</b> Interest is {@code balance x ((1+r)^dtau - 1)}
     *       and under actual dating {@code dtau} varies with the calendar, so a
     *       28-day February accrues less than the 31-day March that follows it from a
     *       larger balance. The interest column then wobbles by construction and
     *       monotonicity is a statement about the calendar, not about the method.</li>
     *   <li><b>The yield's sign.</b> Interest is proportional to a falling balance, so
     *       it falls when the rate is positive and <em>rises towards zero</em> when
     *       the rate is negative. A negative EIR is not pathological: 5% of costs
     *       paid up front on a 0.5% two-period advance is an instrument that returns
     *       less cash than it consumed, and the engine is right to price it below
     *       zero ({@link InvariantBoundaryCasesTest}).</li>
     * </ul>
     */
    @Property(tries = 10000, seed = "20270405")
    void theBalanceFallsAndTheInterestColumnIsMonotoneOnALevelAnnuity(
            @ForAll("levelAnnuities") Generators.Contract contract) {

        Generators.Pipeline pipeline = Generators.run(contract);
        assertThat(pipeline.solved()).as("solved: %s", pipeline.label()).isTrue();
        assertThat(pipeline.convention()).isInstanceOf(TimeConvention.PeriodicIndex.class);
        assertThat(instalmentsAreLevel(pipeline))
            .as("the generated annuity bills one amount throughout: %s", pipeline.label())
            .isTrue();

        int yieldSign = pipeline.eir().periodic().signum();
        List<AmortisationRow> rows = pipeline.eirLeg().rows();
        for (int index = 1; index < rows.size(); index++) {
            AmortisationRow previous = rows.get(index - 1);
            AmortisationRow current = rows.get(index);
            assertThat(current.closingGca().compareTo(previous.closingGca()))
                .as("carrying amount at period %s for %s", current.period(), pipeline.label())
                .isLessThanOrEqualTo(0);
            int interestMovement = current.eirInterest().compareTo(previous.eirInterest());
            if (yieldSign > 0) {
                assertThat(interestMovement)
                    .as("interest at period %s (%s) against period %s (%s) at a positive yield on %s",
                        current.period(), current.eirInterest().atPresentationScale(),
                        previous.period(), previous.eirInterest().atPresentationScale(),
                        pipeline.label())
                    .isLessThanOrEqualTo(0);
            } else if (yieldSign < 0) {
                assertThat(interestMovement)
                    .as("interest at period %s rises towards zero at a negative yield on %s",
                        current.period(), pipeline.label())
                    .isGreaterThanOrEqualTo(0);
            } else {
                assertThat(current.eirInterest().isZero())
                    .as("a zero yield recognises no interest at period %s on %s",
                        current.period(), pipeline.label())
                    .isTrue();
            }
        }
    }

    private static boolean instalmentsAreLevel(Generators.Pipeline pipeline) {
        Money first = null;
        for (CashFlow flow : pipeline.projection().contractual().future()) {
            if (first == null) {
                first = flow.amount();
            } else if (!first.equals(flow.amount())) {
                return false;
            }
        }
        return first != null;
    }
}
