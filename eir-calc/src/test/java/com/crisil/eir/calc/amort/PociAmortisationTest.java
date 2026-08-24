package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.amort.AmortFixtures.EMI;
import static com.crisil.eir.calc.amort.AmortFixtures.MONTHLY;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The credit-adjusted EIR (specification 8, reference case 6): expected losses live
 * inside the rate from inception.
 *
 * <p>Reference case 6 buys the case 1 loan's 24 contractual instalments for 700,000
 * with 20% of every receipt expected to be lost. Two rates solve against that price
 * and the whole of ACPIR 6(4), 24 and 50 is the choice between them:
 *
 * <ul>
 *   <li>against <em>expected</em> flows, 2.15405325% a month — 29.141920% a year, and
 *       the rate the standard requires;
 *   <li>against <em>contractual</em> flows, 4.24580927% a month — 64.703664% a year,
 *       arithmetically impeccable and economically fictional. It would accrue income
 *       the entity has no expectation of collecting and then reverse it through
 *       impairment.
 * </ul>
 *
 * <p>The contrast is tested rather than assumed, because the wrong rate is the easy one
 * to compute: contractual flows are what a source system supplies, and nothing about
 * a 64.7% yield fails a sanity check on a distressed-portfolio purchase.
 */
class PociAmortisationTest {

    /** The purchase price, and the amortised cost at initial recognition (ACPIR 6(4)). */
    private static final Money PURCHASE_PRICE = Money.inr("700000");

    /** 80% of the 47,073.47 contractual instalment: 20% of every receipt is expected lost. */
    /**
     * 80% of the billed EMI, carried at working precision.
     *
     * <p>NOT rounded to paise. The expected receipt is a derived estimate of
     * collections, not a billed amount — nobody is ever billed 80% of an
     * instalment — so no cash event attaches currency scale to it and calc-spec
     * 1.2 forbids rounding an intermediate before it enters a solve. Rounding it
     * first shifts the rate to 0.0215405325, a difference invisible in the
     * roll-forward and therefore exactly the kind that survives review. See the
     * note in docs/reference-cases/case-06-poci-credit-adjusted-eir.md.
     */
    private static final Money EXPECTED_RECEIPT = Money.inr("37658.7760");

    private static FlowVector pool(Money receipt) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, PURCHASE_PRICE.negate(), FlowKind.DISBURSEMENT));
        for (int period = 1; period <= 24; period++) {
            flows.add(CashFlow.of(DISBURSEMENT.plusMonths(period), period, receipt, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(DISBURSEMENT, Money.INR, flows);
    }

    private static Rate solve(FlowVector vector) {
        return new BracketedNewtonSolver()
            .solve(SolveRequest.atInception(vector, MONTHLY, CONTRACTUAL.periodic()))
            .rateOrThrow();
    }

    @Test
    @DisplayName("the credit-adjusted EIR is 2.15405325% a month, 29.141920% effective p.a.")
    void creditAdjustedRate() {
        // The expected receipt is the contractual one net of the day-1 lifetime ECL.
        // the carried estimate is exact; 37,658.78 is only its presentation
        assertThat(EMI.times(bd("0.80")).amount()).isEqualByComparingTo(bd("37658.7760"));
        assertThat(EMI.times(bd("0.80")).atPresentationScale().amount()).isEqualByComparingTo(bd("37658.78"));

        Rate creditAdjusted = solve(pool(EXPECTED_RECEIPT));

        assertThat(creditAdjusted.periodic().setScale(10, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0215405231"));
        assertThat(creditAdjusted.effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.291419"));
    }

    @Test
    @DisplayName("the rate solves to AMORTISED COST — the price paid, not a gross amount")
    void theRateSolvesToAmortisedCost() {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        PociAmortisation poci =
            PociAmortisation.atInitialRecognition(solve(expected), expected, MONTHLY);

        // Taken from the vector's own inception leg, which is what stops a gross figure
        // being used here: there is no gross-basis phase for a POCI asset, so a gross
        // opening balance would not merely be imprecise, it would be a different
        // measurement basis.
        assertThat(poci.amortisedCostAtInitialRecognition().atPresentationScale().amount())
            .isEqualByComparingTo(bd("700000.00"));

        // The gross figure the asset would have opened at if this were a par acquisition —
        // the sum of the expected receipts — is nowhere in the measurement.
        Money grossExpectedReceipts = EXPECTED_RECEIPT.times(bd("24"));
        assertThat(grossExpectedReceipts.atPresentationScale().amount()).isEqualByComparingTo(bd("903810.62"));
        assertThat(poci.amortisedCostAtInitialRecognition().amount())
            .isLessThan(grossExpectedReceipts.amount());

        // And there is no day-1 allowance: lifetime ECL is inside the rate, so recognising
        // an allowance as well would charge the same losses twice.
        assertThat(poci.dayOneAllowance().isZero()).isTrue();
    }

    @Test
    @DisplayName("the contrast: the same price against CONTRACTUAL flows yields 64.703664% p.a.")
    void solvingAgainstContractualFlowsIsTheWrongAnswer() {
        Rate onContractualFlows = solve(pool(EMI));

        assertThat(onContractualFlows.periodic().setScale(10, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0424580927"));
        assertThat(onContractualFlows.effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.647037"));

        // Arithmetically impeccable — it is a real root of a real polynomial, and the solver
        // converges on it cleanly — and economically fictional. Discounting flows the entity
        // does not expect to receive down to the distressed price it actually paid produces a
        // 64.7% yield that would be accrued into income and then reversed through impairment,
        // period after period, for the whole life of the pool.
        FlowVector contractual = pool(EMI);
        assertThat(Discounting.presentValueMoney(onContractualFlows.periodic(), contractual, MONTHLY)
            .atPresentationScale().amount()).isEqualByComparingTo(bd("700000.00"));

        Rate creditAdjusted = solve(pool(EXPECTED_RECEIPT));
        PociAmortisation poci =
            PociAmortisation.atInitialRecognition(creditAdjusted, pool(EXPECTED_RECEIPT), MONTHLY);

        // 3,556 basis points of pure fiction, retained as the disclosure that shows why the
        // credit-adjusted rate was used rather than merely asserting it.
        assertThat(poci.creditAdjustmentBps(onContractualFlows).setScale(0, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("3556"));
        assertThat(creditAdjusted.effectiveAnnual()).isLessThan(onContractualFlows.effectiveAnnual());
    }

    @ParameterizedTest(name = "period {0}: {1} + {2} - 37,658.78 = {3}")
    @CsvSource({
        "1, 700000.00, 15078.37, 677419.59",
        "2, 677419.59, 14591.97, 654352.79",
        "3, 654352.79, 14095.10, 630789.11",
        "6, 582128.11, 12539.34, 557008.68",
        "23, 72952.04,  1571.43,  36864.69",
        "24, 36864.69,   794.08,      0.00",
    })
    @DisplayName("the reference case 6 roll-forward at the credit-adjusted EIR")
    void rollForward(int period, String opening, String interest, String closing) {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        AmortisationRow row = PociAmortisation
            .atInitialRecognition(solve(expected), expected, MONTHLY)
            .amortise(expected)
            .row(period);

        assertThat(row.presentedOpeningGca().amount()).isEqualByComparingTo(bd(opening));
        assertThat(row.presentedEirInterest().amount()).isEqualByComparingTo(bd(interest));
        assertThat(row.presentedClosingGca().amount()).isEqualByComparingTo(bd(closing));
    }

    @Test
    @DisplayName("the POCI schedule amortises the purchase price to zero — TR-1 is asserted")
    void scheduleAmortisesToZero() {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        AmortisationResult schedule = PociAmortisation
            .atInitialRecognition(solve(expected), expected, MONTHLY)
            .amortise(expected);

        assertThat(schedule.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(schedule.invariants().stream().map(InvariantResult::id)).contains(InvariantId.TR_1);
        assertThat(schedule.isClean()).isTrue();
        // Lifetime interest is the expected receipts less the price paid: the whole of the
        // return, and none of the 20% never expected.
        assertThat(schedule.presentedTotalInterest().amount()).isEqualByComparingTo(bd("203810.62"));
    }

    @Test
    @DisplayName("POCI-1: the credit-adjusted EIR is retained after a cure, not reset")
    void rateIsRetainedAcrossACure() {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        Rate creditAdjusted = solve(expected);
        PociAmortisation poci = PociAmortisation.atInitialRecognition(creditAdjusted, expected, MONTHLY);

        assertThat(poci.assertRateRetained(creditAdjusted).satisfied()).isTrue();
        assertThat(poci.assertRateRetained(creditAdjusted).id()).isEqualTo(InvariantId.POCI_1);

        // A rate re-solved on the improved expectations — the plausible mistake, and the one
        // the 2019 IFRS Interpretations Committee direction rules out. A cure improves the
        // expected cash flows; it does not retrospectively make a distressed purchase price
        // a par acquisition.
        Rate resetOnCure = solve(pool(EMI));
        InvariantResult breach = poci.assertRateRetained(resetOnCure);
        assertThat(breach.satisfied()).isFalse();
        assertThat(breach.detail()).contains("was reset to");

        // Structurally there is nowhere for a re-solved rate to enter: the post-cure
        // schedule takes a carrying amount and a vector, and no rate at all.
        AmortisationResult afterCure = poci.afterCure(
            Money.inr("500000"), AmortFixtures.remainingAt(DISBURSEMENT.plusMonths(6), EMI, 18));
        assertThat(afterCure.row(1).presentedEirInterest().amount())
            .isEqualByComparingTo(Money.inr("500000").times(creditAdjusted.periodic())
                .atPresentationScale().amount());
        // No terminal assertion after a cure: the carrying amount comes from the impairment
        // remeasurement and is not the present value of the revised flows at this rate.
        assertThat(afterCure.invariants()).isEmpty();
    }

    @Test
    @DisplayName("the cure improvement is an impairment gain, so it does not run through interest")
    void cureIsProspectiveAtTheRetainedRate() {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        Rate creditAdjusted = solve(expected);
        PociAmortisation poci = PociAmortisation.atInitialRecognition(creditAdjusted, expected, MONTHLY);

        // Balance at the end of period 6 on the original expectations, then the expectations
        // improve to full contractual collection from period 7. The improvement is recognised
        // as the cumulative change in lifetime ECL against the initial estimate — an
        // impairment gain — so the caller supplies the remeasured carrying amount and the
        // schedule continues at the same rate rather than re-solving one.
        Money balanceAtCure = poci.amortise(expected).row(6).closingGca();
        assertThat(balanceAtCure.atPresentationScale().amount()).isEqualByComparingTo(bd("557008.68"));

        AmortisationResult continued = poci.afterCure(
            balanceAtCure, AmortFixtures.remainingAt(DISBURSEMENT.plusMonths(6), EMI, 18));

        assertThat(continued.periods()).isEqualTo(18);
        assertThat(continued.row(1).presentedEirInterest().amount())
            .isEqualByComparingTo(balanceAtCure.times(creditAdjusted.periodic()).atPresentationScale().amount());
        // Collecting the contractual amount at the credit-adjusted rate runs the balance
        // negative before maturity — the asset is over-recovered relative to what was priced
        // in, and that surplus is an impairment gain rather than extra interest. The engine
        // reports it instead of absorbing it into the yield.
        assertThat(continued.terminalBalance().isNegative()).isTrue();
    }

    @Test
    @DisplayName("a rate whose frequency contradicts the convention cannot be held")
    void rateAndConventionMustAgree() {
        assertThatThrownBy(() -> new PociAmortisation(
            Rate.annualEffective(bd("0.291419")), PURCHASE_PRICE, MONTHLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compounds 1 times a year");
    }

    @Test
    @DisplayName("the rate is held rather than passed, so every operation uses the same one")
    void theRateIsAField() {
        FlowVector expected = pool(EXPECTED_RECEIPT);
        Rate creditAdjusted = solve(expected);
        PociAmortisation poci =
            new PociAmortisation(creditAdjusted, PURCHASE_PRICE, MONTHLY);

        // POCI-1 is not something a caller has to remember: there is no parameter on
        // amortise or afterCure through which a different rate could be supplied, so the
        // initial schedule, the cure and every later segment run on one rate by construction.
        assertThat(poci.creditAdjustedEir()).isEqualTo(creditAdjusted);
        assertThat(poci.convention()).isEqualTo((TimeConvention) MONTHLY);
        assertThat(poci.amortise(expected).row(1).eirInterest().amount())
            .isEqualByComparingTo(PURCHASE_PRICE.times(creditAdjusted.periodic()).amount());
    }
}
