package com.crisil.eir.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;

/**
 * The two claims worth recomputing from their definitions rather than from the engine.
 *
 * <p>Both properties derive their expected answers independently of the code under test: the first
 * from 03 § 5.1's roll-forward and 03 § 7's net-basis definition, written out literally; the second
 * from the arithmetic of a total absolute residual. Neither reads a figure back out of the pipeline
 * and compares it with itself.
 */
class MonthEndRunPropertiesTest {

    private static final String RUN = "RUN-202805-01";
    private static final int PERIOD = 202805;
    private static final LocalDate POSTED = LocalDate.of(2028, 5, 31);

    /**
     * The roll-forward and the net-basis decomposition, recomputed from the specification.
     *
     * <p>03 § 5.1: {@code interest_p = opening_p x ((1 + r)^dtau_p - 1)} and
     * {@code closing_p = opening_p + interest_p - cash_p}, with {@code dtau} exactly 1 for a whole
     * compounding period under periodic indexing. 03 § 7: the IFRS 9 net-basis figure is amortised
     * cost — gross less allowance — at the same rate.
     *
     * <p>The net-basis leg is the interesting half, because the engine deliberately does <em>not</em>
     * compute it this way: it takes {@code gross - unwind} as an exact subtraction so that ST-2 stays
     * an identity rather than a near-miss, and 03 § 7 notes that
     * {@code (gross - allowance) x EIR} is mathematically the same and numerically not. So this
     * property is the independent check that the two derivations agree, with the tolerance stated as
     * a bound on 28-significant-digit rounding rather than as a licence.
     *
     * <p>It also carries the 05 § 3.2 claim over every generated contract: with no event, no solve.
     * The solver in the fixture throws, so a passing property cannot have solved.
     */
    @Property(tries = 400)
    void theRollForwardIsRecomputedFromItsDefinition(
        @ForAll @BigRange(min = "1000.00", max = "50000000.00") @Scale(2) BigDecimal opening,
        @ForAll @BigRange(min = "0.000100000000", max = "0.030000000000") @Scale(12)
            BigDecimal periodicRate,
        @ForAll @IntRange(min = 0, max = 9900) int allowanceBasisPoints,
        @ForAll @BigRange(min = "0.00", max = "100000.00") @Scale(2) BigDecimal billed) {

        Money gca = Money.of(opening, Money.INR);
        // The allowance as a proportion of the balance, reduced once to paise. Capped below 100% so
        // it never exceeds the balance, which Stage3Decomposition refuses outright — an allowance
        // above the carrying amount would give a negative net-basis figure with ST-2, S3-1 and S3-2
        // all still reporting satisfied.
        Money allowance = gca
            .times(BigDecimal.valueOf(allowanceBasisPoints).movePointLeft(4))
            .atPresentationScale();
        Rate eir = Rate.periodic(periodicRate, 12);
        Money billedInterest = Money.of(billed, Money.INR);

        ContractStateSource.OpeningState state = new ContractStateSource.OpeningState(
            RunFixtures.case1Terms(), eir, gca, gca, Stage.STAGE_3, allowance,
            "ECL-MODEL-2028.05", billedInterest);
        // No cash: the boundary is declared by a zero-amount flow, which changes no present value
        // and no balance. That keeps S3-1's fourth leg — cash on interest against suspense
        // recovered — reconciling at nil on both sides, so the property is about the arithmetic.
        RunFixtures.Harness harness = RunFixtures.harness(
            "C-P", state, RunFixtures.defaultedPeriod("C-P"), RunFixtures.EXPLODING_SOLVER);

        ContractComputation computation = harness.pipeline().compute("C-P");

        // ---- 03 § 5.1, written out ------------------------------------------------------------
        BigDecimal accretion = BigDecimal.ONE.add(periodicRate)
            .pow(1, Precision.WORKING)
            .subtract(BigDecimal.ONE);
        Money expectedInterest =
            Money.of(gca.amount().multiply(accretion, Precision.WORKING), Money.INR);
        Money expectedClosing = gca.plus(expectedInterest);

        assertThat(computation.row().eirInterest().atPresentationScale())
            .as("interest = opening x ((1+r)^1 - 1)")
            .isEqualTo(expectedInterest.atPresentationScale());
        assertThat(computation.closingGca().atPresentationScale())
            .as("closing = opening + interest - cash, with no cash this period")
            .isEqualTo(expectedClosing.atPresentationScale());

        // ---- 03 § 7, written out --------------------------------------------------------------
        Money amortisedCost = gca.minus(allowance);
        BigDecimal independentNet =
            amortisedCost.amount().multiply(accretion, Precision.WORKING);
        BigDecimal engineNet = computation.decomposition().netBasisInterest().amount();
        assertThat(engineNet.subtract(independentNet).abs())
            .as("amortised cost x EIR, derived independently of gross - unwind")
            .isLessThan(new BigDecimal("0.000000000001"));

        // ---- 05 § 3.2 -------------------------------------------------------------------------
        assertThat(computation.solves()).as("no event, so no solve").isZero();
        assertThat(harness.audit().solveCount()).isZero();

        // ---- The controls hold on every generated contract ------------------------------------
        for (InvariantResult result : computation.invariants()) {
            assertThat(result.satisfied()).as("%s: %s", result.id(), result.detail()).isTrue();
        }
        assertThat(computation.invariants()).extracting(InvariantResult::id)
            .containsExactly(InvariantId.SL_2, InvariantId.ST_2, InvariantId.S3_1);
    }

    /**
     * The population aggregation totals absolute residuals, recomputed from the definition.
     *
     * <p>The rule the aggregation has to obey: the deviation an invariant reports over a population
     * is {@code sum |r_i|}, never {@code |sum r_i|}. The two coincide whenever every break points
     * the same way, which is why a signed sum survives most hand-written fixtures and fails on the
     * one population that matters — a book with offsetting errors. Generating mixed signs is the
     * only way to state the difference.
     */
    @Property(tries = 400)
    void theAggregateTotalsAbsoluteResidualsAndNeverNets(
        @ForAll @Size(min = 1, max = 15) List<@IntRange(min = -5, max = 5) Integer> skews) {

        List<String> population = new ArrayList<>(skews.size());
        List<ContractResult> results = new ArrayList<>(skews.size());
        BigDecimal expectedAbsolute = BigDecimal.ZERO;
        BigDecimal signedSum = BigDecimal.ZERO;

        for (int index = 0; index < skews.size(); index++) {
            String contractId = "C-" + index;
            // Whole rupees, so the arithmetic is exact and the property is about the aggregation.
            Money residual = Money.inr(String.valueOf(skews.get(index)));
            Money debit = Money.inr("1000");
            Money credit = debit.minus(residual);
            JournalEntry entry = new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
                JournalLine.debit("1401-EIR-RECEIVABLE", debit, "accrual"),
                JournalLine.credit("4101-INTEREST-INCOME", credit, "income")));
            population.add(contractId);
            results.add(ContractResult.computed(
                contractId, Money.inr("486840.64"), entry, List.of(entry.sidesBalance())));
            expectedAbsolute = expectedAbsolute.add(residual.amount().abs());
            signedSum = signedSum.add(residual.amount());
        }

        RunAggregate aggregate =
            RunAggregate.of(RUN, PERIOD, population, results, List.of());
        InvariantResult slTwo = aggregate.invariants().stream()
            .filter(result -> result.id() == InvariantId.SL_2)
            .findFirst()
            .orElseThrow();

        boolean anyBreak = expectedAbsolute.signum() != 0;
        assertThat(slTwo.satisfied()).as("satisfied iff every contract balanced").isEqualTo(!anyBreak);
        assertThat(slTwo.deviation())
            .as("sum of absolute residuals, not the absolute of the sum")
            .isEqualByComparingTo(anyBreak ? expectedAbsolute : BigDecimal.ZERO);
        assertThat(aggregate.reportsCleanClose())
            .as("a clean close needs a full population and no breach")
            .isEqualTo(!anyBreak);

        if (anyBreak && signedSum.signum() == 0) {
            // The population this property exists for: every break offset by another. A signed sum
            // would report nil here and the run would read as reconciled.
            assertThat(slTwo.deviation()).isGreaterThan(BigDecimal.ZERO);
        }
    }
}
