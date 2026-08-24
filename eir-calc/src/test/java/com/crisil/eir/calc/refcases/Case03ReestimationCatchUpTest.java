package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTH_12;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.remainingFrom;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.calc.routing.DefaultEventRouter;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.calc.routing.ModificationConclusion;
import com.crisil.eir.calc.routing.ModificationTest;
import com.crisil.eir.calc.routing.ModificationTestResult;
import com.crisil.eir.calc.routing.ReviewBand;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 3 — re-estimation of cash flows: retrospective catch-up at the
 * <em>original</em> EIR.
 *
 * <p>Continues case 1. At the end of month 12 the remaining 12 EMIs are
 * re-profiled into 18 smaller ones. IFRS 9 B5.4.6 mechanics: the rate does not
 * move, the balance sheet is restated to the present value of the revised flows at
 * the rate already in force, and the difference hits P&amp;L at once.
 *
 * <p>Read against {@link Case04FloatingRateResetTest}. Same instrument, same month,
 * and in both cases "the schedule changed" — one produces a 627.42 charge with the
 * rate held, the other nothing with the rate re-solved. Getting the pair the wrong
 * way round is the highest-impact error available in this domain, which is why the
 * engine routes on the driver tag carried on the event and never on the
 * observation that the rate or the schedule moved.
 *
 * <p>Figures from {@code docs/reference-cases/case-03-b546-reestimation.md}.
 */
class Case03ReestimationCatchUpTest {

    private final ReferenceCaseFixtures.Baseline case1 = baseline();

    /** The contractual balance the re-profiled annuity is sized against. */
    private Money contractualBalanceAtMonth12() {
        return case1.contractualLeg().row(12).closingGca();
    }

    /** The carrying amount after the day's cash and before restatement. */
    private Money carryingAmountBefore() {
        return case1.eirLeg().row(12).closingGca();
    }

    private Money revisedInstalment() {
        return Annuity.billedInstalment(contractualBalanceAtMonth12(), ONE_PERCENT_MONTHLY.periodic(), 18);
    }

    private FlowVector revisedFlows() {
        return remainingFrom(MONTH_12, revisedInstalment(), 18);
    }

    private CatchUpResult restatement() {
        return CatchUpCalculator.restate(case1.eir(), carryingAmountBefore(), revisedFlows(), MONTHLY);
    }

    @Test
    @DisplayName("BEHAVIOURAL_ESTIMATE routes to CATCH_UP")
    void theDriverTagRoutesToACatchUp() {
        RoutingDecision decision = DefaultEventRouter.INSTANCE.route(
            RateDriver.BEHAVIOURAL_ESTIMATE, RateType.FIXED, RoutingTable.currentDefault());

        assertThat(decision.mechanism()).isEqualTo(Mechanism.CATCH_UP);
        assertThat(decision.overriddenByRateTypeCheck())
            .as("the entity's own revised estimate is not a market movement, so no override applies")
            .isFalse();
        assertThat(RateDriver.BEHAVIOURAL_ESTIMATE.isMarketMovement()).isFalse();
    }

    @Test
    @DisplayName("the six-month extension re-profiles 529,815.61 into 18 EMIs of 32,309.24")
    void theRevisedInstalment() {
        assertThat(paise(contractualBalanceAtMonth12()))
            .as("contractual balance at month 12")
            .isEqualByComparingTo(bd("529815.61"));
        assertThat(paise(revisedInstalment()))
            .as("revised EMI, contractual rate unchanged at 1.00%/month over 18 periods")
            .isEqualByComparingTo(bd("32309.24"));
    }

    @Test
    @DisplayName("the revised flows discount at the ORIGINAL EIR to 527,779.90, a 627.42 charge")
    void theCatchUpIsAChargeOfSixHundredAndTwentySeven() {
        CatchUpResult restatement = restatement();

        assertThat(periodicPercent(restatement.eirBefore()))
            .as("original EIR — retained, not re-solved")
            .isEqualByComparingTo(bd("1.04214918"));
        assertThat(paise(restatement.gcaBefore()))
            .as("carrying amount before restatement")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(paise(restatement.restatedGca()))
            .as("PV of the revised flows at the original EIR")
            .isEqualByComparingTo(bd("527779.90"));
        assertThat(paise(restatement.catchUp()))
            .as("catch-up adjustment to P&L")
            .isEqualByComparingTo(bd("-627.42"));
        assertThat(restatement.isCharge()).isTrue();
        assertThat(restatement.isIncome()).isFalse();
    }

    @Test
    @DisplayName("CU-1: the persisted EIR before and after the event is bit-identical")
    void theRateDoesNotMoveAcrossTheCatchUp() {
        CatchUpResult restatement = restatement();

        assertThat(InvariantChecks.bitIdentical(restatement.eirBefore(), restatement.eirAfter()))
            .as("discounting the revised flows at a re-solved rate would drive the catch-up to "
                + "approximately zero and silently convert this into a Case 4")
            .isTrue();
        assertThat(restatement.invariants())
            .anyMatch(result -> result.id() == InvariantId.CU_1 && result.satisfied());
        assertThat(restatement.invariants())
            .anyMatch(result -> result.id() == InvariantId.CU_2 && result.satisfied());
        assertThat(restatement.isClean())
            .as("breaches: %s", restatement.breaches())
            .isTrue();
    }

    @Test
    @DisplayName("post-modification roll-forward at the unchanged EIR, terminating at zero")
    void thePostModificationSchedule() {
        AmortisationResult restated =
            CatchUpCalculator.rollForwardRestated(restatement(), revisedFlows(), MONTHLY);

        assertThat(restated.periods()).isEqualTo(18);
        assertRow(restated, 1, "527779.90", "5500.25", "500970.91");
        assertRow(restated, 2, "500970.91", "5220.86", "473882.54");
        assertRow(restated, 3, "473882.54", "4938.56", "446511.86");
        assertRow(restated, 4, "446511.86", "4653.32", "418855.94");
        assertRow(restated, 5, "418855.94", "4365.10", "390911.80");
        assertRow(restated, 6, "390911.80", "4073.88", "362676.45");
        assertRow(restated, 17, "63622.20", "663.04", "31976.00");
        assertRow(restated, 18, "31976.00", "333.24", "0.00");

        assertThat(paise(restated.terminalBalance()))
            .as("TR-1: the restated balance IS the PV of these flows at this rate, so it "
                + "amortises to zero over them by construction")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(restated.invariants())
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
    }

    @Test
    @DisplayName("the fixture's Total row sums the eight printed rows: 29,748.26 of interest on 258,473.92 of cash")
    void thePrintedRowsSumToTheFixturesTotal() {
        // The fixture omits periods 7 to 16, so its Total row is the sum of the eight rows
        // it prints rather than the life total (which is 53,786.42). Asserted as printed,
        // because a fixture figure that cannot be reproduced is not a fixture figure.
        AmortisationResult restated =
            CatchUpCalculator.rollForwardRestated(restatement(), revisedFlows(), MONTHLY);
        int[] printed = {1, 2, 3, 4, 5, 6, 17, 18};

        Money interest = Money.zero(Money.INR);
        Money cash = Money.zero(Money.INR);
        for (int period : printed) {
            interest = interest.plus(restated.row(period).eirInterest());
            cash = cash.plus(restated.row(period).cashReceived());
        }

        assertThat(paise(interest)).isEqualByComparingTo(bd("29748.26"));
        assertThat(paise(cash)).isEqualByComparingTo(bd("258473.92"));
    }

    @Test
    @DisplayName("the extension is assessed as not a substantial modification, so no derecognition")
    void theQuantitativeTestConcludesNotSubstantial() {
        FlowVector remainingOriginal = remainingFrom(MONTH_12, ReferenceCaseFixtures.CASE1_EMI, 12);

        ModificationTestResult result = ModificationTest.evaluate(
            case1.eir().periodic(), remainingOriginal, revisedFlows(), MONTHLY,
            InstrumentSide.ASSET, ReviewBand.standard());

        assertThat(paise(result.presentValueRevised()))
            .as("PV of the revised flows at the original EIR")
            .isEqualByComparingTo(bd("527779.90"));
        assertThat(paise(result.presentValueRemaining()))
            .as("PV of the flows that would have remained — the carrying amount")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(result.ratio().setScale(6, RoundingMode.HALF_UP))
            .as("627.42 on 528,407.32 — nowhere near the 10% threshold")
            .isEqualByComparingTo(bd("0.001187"));
        assertThat(result.conclusion()).isEqualTo(ModificationConclusion.NOT_SUBSTANTIAL);
        assertThat(result.conclusion().mechanism()).isEqualTo(Mechanism.CATCH_UP);
    }

    @Test
    @DisplayName("the discriminator: this event restates the balance and holds the rate")
    void theDiscriminatorAgainstCaseFour() {
        CatchUpResult restatement = restatement();

        // Case 3 column of the fixture's comparison table. Case 4 asserts the other.
        assertThat(periodicPercent(restatement.eirAfter()))
            .as("EIR unchanged")
            .isEqualByComparingTo(bd("1.04214918"));
        assertThat(paise(restatement.restatedGca()))
            .as("carrying amount restated")
            .isEqualByComparingTo(bd("527779.90"));
        assertThat(paise(restatement.catchUp()))
            .as("P&L now")
            .isEqualByComparingTo(bd("-627.42"));
    }

    private void assertRow(AmortisationResult result, int period, String opening, String interest,
        String closing) {
        AmortisationRow row = result.row(period);
        assertThat(paise(row.openingGca())).as("period %d opening", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash", period)
            .isEqualByComparingTo(bd("32309.24"));
        assertThat(paise(row.closingGca())).as("period %d closing", period)
            .isEqualByComparingTo(bd(closing));
    }

    @Test
    @DisplayName("the revised EMI is derived from the contractual rate, which the event does not touch")
    void theContractualRateIsUnchanged() {
        BigDecimal unrounded = Annuity.instalment(
            contractualBalanceAtMonth12(), ONE_PERCENT_MONTHLY.periodic(), 18).amount();
        assertThat(unrounded.setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo(bd("32309.24"));
        assertThat(periodicPercent(ONE_PERCENT_MONTHLY)).isEqualByComparingTo(bd("1.00000000"));
    }
}
