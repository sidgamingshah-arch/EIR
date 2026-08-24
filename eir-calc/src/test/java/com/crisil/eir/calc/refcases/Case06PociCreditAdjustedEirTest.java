package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.CASE1_EMI;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.levelReceipts;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.PociAmortisation;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 6 — POCI asset: the credit-adjusted EIR.
 *
 * <p>A distressed pool acquired for 700,000. Contractual flows are the 24 EMIs of
 * case 1, but 20% of every receipt is expected to be lost. ACPIR 6(3)(v) makes
 * acquisition at a discount reflecting inherent credit losses an explicit
 * credit-impairment indicator, so this is POCI on acquisition terms alone; ACPIR 24
 * requires the day-1 lifetime ECL to sit <em>inside</em> the cash flows used to
 * compute the rate, with no separate day-1 allowance.
 *
 * <p>The comparison is the point. Discounting <em>contractual</em> flows to a
 * distressed purchase price yields 64.703664% a year — a figure that is
 * arithmetically impeccable and economically fictional, because it accrues income
 * the bank has no expectation of collecting and then reverses it through
 * impairment.
 *
 * <p>Figures from {@code docs/reference-cases/case-06-poci-credit-adjusted-eir.md}.
 */
class Case06PociCreditAdjustedEirTest {

    private static final Money PRICE = Money.inr("700000");

    /** 80% of the contractual receipt: what the pool is expected to pay. */
    private static final Money EXPECTED_RECEIPT = Money.inr("37658.78");

    private final BracketedNewtonSolver solver = new BracketedNewtonSolver();

    private FlowVector expectedFlows() {
        return levelReceipts(PRICE, EXPECTED_RECEIPT, 24);
    }

    private FlowVector contractualFlows() {
        return levelReceipts(PRICE, CASE1_EMI, 24);
    }

    private SolveResult solveExpected() {
        return solver.solve(SolveRequest.atInception(
            expectedFlows(), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));
    }

    @Test
    @DisplayName("the expected monthly receipt is 80% of the 47,073.47 contractual EMI")
    void theExpectedReceipt() {
        assertThat(paise(CASE1_EMI.times(bd("0.80"))))
            .as("contractual receipt less the 20% expected loss")
            .isEqualByComparingTo(bd("37658.78"));
        assertThat(paise(EXPECTED_RECEIPT)).isEqualByComparingTo(bd("37658.78"));
    }

    @Test
    @DisplayName("the credit-adjusted EIR is 2.15405325% per month, 29.141920% effective p.a.")
    void theCreditAdjustedRateIsSolvedOnExpectedFlows() {
        SolveResult result = solveExpected();

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(result.rateOrThrow()))
            .as("credit-adjusted EIR per month")
            .isEqualByComparingTo(bd("2.15405325"));
        assertThat(effectiveAnnualPercent(result.rateOrThrow()))
            .as("credit-adjusted EIR effective p.a.")
            .isEqualByComparingTo(bd("29.141920"));
    }

    @Test
    @DisplayName("an EIR on contractual flows would be 4.24580927% per month, 64.703664% p.a. — the wrong answer")
    void theNaiveRateOnContractualFlows() {
        SolveResult naive = solver.solve(SolveRequest.atInception(
            contractualFlows(), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(naive.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(naive.rateOrThrow()))
            .as("EIR on contractual flows, per month — wrong")
            .isEqualByComparingTo(bd("4.24580927"));
        assertThat(effectiveAnnualPercent(naive.rateOrThrow()))
            .as("EIR on contractual flows, effective p.a. — wrong")
            .isEqualByComparingTo(bd("64.703664"));

        // The credit adjustment is the gap between the two, and it is the disclosure that
        // shows why the credit-adjusted rate was used.
        PociAmortisation poci = PociAmortisation.atInitialRecognition(
            solveExpected().rateOrThrow(), expectedFlows(), MONTHLY);
        BigDecimal adjustmentBps = poci.creditAdjustmentBps(naive.rateOrThrow());
        assertThat(adjustmentBps)
            .as("64.703664% less 29.141920%, in basis points")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat(adjustmentBps.setScale(0, java.math.RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("3556"));
    }

    @Test
    @DisplayName("the rate solves to amortised cost, and there is no day-1 allowance")
    void theRateSolvesToAmortisedCostWithNoDayOneAllowance() {
        PociAmortisation poci = PociAmortisation.atInitialRecognition(
            solveExpected().rateOrThrow(), expectedFlows(), MONTHLY);

        assertThat(paise(poci.amortisedCostAtInitialRecognition()))
            .as("purchase price = amortised cost at initial recognition (ACPIR 6(4)); there is "
                + "no gross-basis phase for a POCI asset")
            .isEqualByComparingTo(bd("700000.00"));
        assertThat(paise(poci.dayOneAllowance()))
            .as("lifetime ECL is inside the rate; an allowance as well would charge the same "
                + "losses twice")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("roll-forward at the credit-adjusted EIR: 700,000.00 accretes 15,078.37 and closes at 677,419.59")
    void theRollForward() {
        AmortisationResult schedule = PociAmortisation.atInitialRecognition(
            solveExpected().rateOrThrow(), expectedFlows(), MONTHLY).amortise(expectedFlows());

        assertThat(schedule.periods()).isEqualTo(24);
        assertRow(schedule, 1, "700000.00", "15078.37", "677419.59");
        assertRow(schedule, 2, "677419.59", "14591.98", "654352.79");
        assertRow(schedule, 3, "654352.79", "14095.11", "630789.12");
        assertRow(schedule, 4, "630789.12", "13587.53", "606717.87");
        assertRow(schedule, 5, "606717.87", "13069.03", "582128.12");
        assertRow(schedule, 6, "582128.12", "12539.35", "557008.69");
        assertRow(schedule, 23, "72952.05", "1571.43", "36864.69");
        assertRow(schedule, 24, "36864.69", "794.09", "0.00");

        assertThat(paise(schedule.terminalBalance()))
            .as("TR-1: terminal closing balance")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(schedule.invariants())
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
    }

    @Test
    @DisplayName("POCI-1: the credit-adjusted EIR is retained after cure, not reset")
    void theRateIsRetainedOnCure() {
        Rate creditAdjusted = solveExpected().rateOrThrow();
        PociAmortisation poci =
            PociAmortisation.atInitialRecognition(creditAdjusted, expectedFlows(), MONTHLY);

        // A cure improves the expected cash flows; it does not retrospectively make the
        // purchase price a par acquisition. Following the 2019 IFRS Interpretations
        // Committee direction.
        assertThat(poci.assertRateRetained(creditAdjusted).satisfied()).isTrue();
        assertThat(poci.assertRateRetained(creditAdjusted).id()).isEqualTo(InvariantId.POCI_1);

        // And the tripwire: a re-solve on the improved flows would be a POCI-1 breach.
        SolveResult reSolvedOnContractual = solver.solve(SolveRequest.atInception(
            contractualFlows(), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));
        assertThat(poci.assertRateRetained(reSolvedOnContractual.rateOrThrow()).satisfied())
            .as("resetting the rate on cure must be reported as a breach, not absorbed")
            .isFalse();
    }

    private void assertRow(AmortisationResult result, int period, String opening, String interest,
        String closing) {
        AmortisationRow row = result.row(period);
        assertThat(paise(row.openingGca())).as("period %d opening", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash", period)
            .isEqualByComparingTo(bd("37658.78"));
        assertThat(paise(row.closingGca())).as("period %d closing", period)
            .isEqualByComparingTo(bd(closing));
    }
}
