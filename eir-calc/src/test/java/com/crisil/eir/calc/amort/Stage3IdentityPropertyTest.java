package com.crisil.eir.calc.amort;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;

/**
 * Invariant ST-2 as a generated property, over arbitrary balances, allowances and rates.
 *
 * <p>{@link Stage3DecompositionTest} pins the reference case 5 figures and sweeps the
 * allowance from 0% to 100% on that one balance. This class asks the broader question:
 * whether the identity is arithmetic or merely true for the fixture's magnitudes. It is
 * arithmetic — the unwind is {@code allowance x EIR} and the net-basis figure is the gross
 * figure <em>less that same unwind</em>, unrounded, so the recomposition is an exact
 * subtraction whatever the inputs. Computing {@code (gross - allowance) x EIR}
 * independently would be mathematically identical and numerically not: at 28 significant
 * digits two balances of very different magnitude round differently, and the invariant
 * would then fail on inputs that are perfectly sound. That is exactly the case a generator
 * finds and a fixture does not — a 90-crore exposure against a 12-rupee allowance.
 *
 * <p>The seed is fixed. A property test in this engine is also replay evidence (invariant
 * DT-1), and a failure that cannot be reproduced from the source is worth much less than
 * one that can.
 */
class Stage3IdentityPropertyTest {

    @Property(tries = 500, seed = "20270401")
    void stageTwoIdentityHoldsForAnyBalanceAllowanceAndRate(
        @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal grossAmount,
        @ForAll @BigRange(min = "0", max = "1") @Scale(6) BigDecimal allowanceFraction,
        @ForAll @BigRange(min = "0.0000000001", max = "0.5") @Scale(12) BigDecimal periodicRate) {

        Money gross = Money.inr(grossAmount.toPlainString());
        Money allowance = gross.times(allowanceFraction);
        Rate eir = Rate.monthly(periodicRate);

        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            gross, allowance, eir, gross.times(periodicRate), Stage.STAGE_3);

        // net + unwind = gross, exactly, with no tolerance and no rounding.
        assertThat(decomposition.netBasisInterest().amount().add(decomposition.eclUnwind().amount()))
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());

        InvariantResult stageTwo = decomposition.invariants().stream()
            .filter(result -> result.id() == InvariantId.ST_2)
            .findFirst()
            .orElseThrow();
        assertThat(stageTwo.satisfied()).isTrue();
        assertThat(stageTwo.deviation().signum()).isEqualTo(0);

        // The unwind is the interest the gross basis would have earned on the allowance
        // portion of the balance — which is what makes the decomposition a decomposition and
        // not a second model to reconcile against.
        assertThat(decomposition.eclUnwind().amount())
            .isEqualByComparingTo(allowance.amount().multiply(eir.periodic(), Precision.WORKING));
        // And recognition is nil throughout Stage 3, whatever the numbers are.
        assertThat(decomposition.recognisedIncome().isZero()).isTrue();
        assertThat(decomposition.incomeSuppressed()).isTrue();
    }

    @Property(tries = 300, seed = "20270401")
    void netBasisNeverExceedsGrossBasisAndTheUnwindIsNeverNegative(
        @ForAll @BigRange(min = "1", max = "100000000") @Scale(2) BigDecimal grossAmount,
        @ForAll @BigRange(min = "0", max = "1") @Scale(6) BigDecimal allowanceFraction,
        @ForAll @BigRange(min = "0.000001", max = "0.25") @Scale(12) BigDecimal periodicRate) {

        Money gross = Money.inr(grossAmount.toPlainString());
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            gross, gross.times(allowanceFraction), Rate.monthly(periodicRate),
            Money.zero(Money.INR), Stage.STAGE_3);

        // Ordering, on an asset: the allowance can only reduce the amortised cost, so the
        // net-basis figure can only be smaller than the gross-basis one, and the unwind can
        // only be non-negative. A sign error in the split shows up here rather than as a
        // credit to interest income three months into a parallel run.
        assertThat(decomposition.netBasisInterest().amount())
            .isLessThanOrEqualTo(decomposition.grossBasisInterest().amount());
        assertThat(decomposition.eclUnwind().signum()).isNotNegative();
        assertThat(decomposition.grossBasisInterest().signum()).isPositive();
    }
}
