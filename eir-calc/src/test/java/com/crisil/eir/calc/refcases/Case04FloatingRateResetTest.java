package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTH_12;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.THIRTEEN_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.remainingFrom;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.calc.routing.DefaultEventRouter;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 4 — floating-rate benchmark reset: prospective, no catch-up.
 *
 * <p>The case 1 loan priced instead at a repo-linked EBLR reset annually. At the
 * end of month 12 the benchmark rises 100bps, taking the contractual rate from 12%
 * to 13% p.a. IFRS 9 B5.4.5 mechanics: the rate is re-solved so that the revised
 * flows discount to the carrying amount <em>already on the books</em>, and nothing
 * hits P&amp;L.
 *
 * <p>The unamortised fee balance of 1,408.29 is not released early. It is absorbed
 * into the new rate and continues to amortise over the remaining life, which is
 * precisely why case 8's shortcut — which leaves no such balance — removes the
 * reset-loop cost.
 *
 * <p>Read against {@link Case03ReestimationCatchUpTest}. Figures from
 * {@code docs/reference-cases/case-04-floating-rate-reset.md}.
 */
class Case04FloatingRateResetTest {

    private final ReferenceCaseFixtures.Baseline case1 = baseline();

    /** Carrying amount at the reset: case 1's month-12 closing balance, untouched. */
    private Money carryingAmountAtReset() {
        return case1.eirLeg().row(12).closingGca();
    }

    private Money revisedInstalment() {
        return Annuity.billedInstalment(
            case1.contractualLeg().row(12).closingGca(), THIRTEEN_PERCENT_MONTHLY.periodic(), 12);
    }

    private FlowVector revisedFlows() {
        return remainingFrom(MONTH_12, revisedInstalment(), 12);
    }

    private SolveResult resolve() {
        return new BracketedNewtonSolver().solve(SolveRequest.of(
            revisedFlows(), carryingAmountAtReset(), MONTHLY, THIRTEEN_PERCENT_MONTHLY.periodic()));
    }

    @Test
    @DisplayName("TIME_VALUE_OF_MONEY on a floating-rate instrument routes to RESET")
    void theDriverTagRoutesToAReset() {
        RoutingDecision decision = DefaultEventRouter.INSTANCE.route(
            RateDriver.TIME_VALUE_OF_MONEY, RateType.FLOATING, RoutingTable.currentDefault());

        assertThat(decision.mechanism()).isEqualTo(Mechanism.RESET);
        assertThat(decision.overriddenByRateTypeCheck()).isFalse();
        assertThat(RateDriver.TIME_VALUE_OF_MONEY.isMarketMovement()).isTrue();
    }

    @Test
    @DisplayName("the same driver on a FIXED-rate loan is a modification, not a reset")
    void aRenegotiatedFixedRateIsNotThisTreatment() {
        // B5.4.5 applies to instruments repricing off a market benchmark by their own
        // terms. A negotiated rate change on a fixed-rate loan runs the substantiality
        // test instead, and the rate-type check is what stops the benchmark path from
        // swallowing it.
        RoutingDecision decision = DefaultEventRouter.INSTANCE.route(
            RateDriver.TIME_VALUE_OF_MONEY, RateType.FIXED, RoutingTable.currentDefault());

        assertThat(decision.mechanism()).isEqualTo(Mechanism.MODIFICATION_TEST);
        assertThat(decision.overriddenByRateTypeCheck()).isTrue();
    }

    @Test
    @DisplayName("13% p.a. nominal is 1.08333333% per month and reprices the remaining 12 EMIs to 47,321.69")
    void theRevisedContractualTerms() {
        assertThat(periodicPercent(THIRTEEN_PERCENT_MONTHLY))
            .as("contractual rate after reset, per month")
            .isEqualByComparingTo(bd("1.08333333"));
        assertThat(paise(case1.contractualLeg().row(12).closingGca()))
            .as("contractual balance at reset")
            .isEqualByComparingTo(bd("529815.61"));
        assertThat(paise(revisedInstalment()))
            .as("revised EMI for the remaining 12 months")
            .isEqualByComparingTo(bd("47321.69"));
    }

    @Test
    @DisplayName("the carrying amount at the reset is unchanged at 528,407.32")
    void theCarryingAmountIsNotTouched() {
        assertThat(paise(carryingAmountAtReset()))
            .as("a benchmark reset changes future flows, never the balance already recognised")
            .isEqualByComparingTo(bd("528407.32"));
    }

    @Test
    @DisplayName("the revised EIR is 1.12558515% per month, 14.375386% effective p.a.")
    void theRateIsResolvedProspectively() {
        SolveResult resolved = resolve();

        assertThat(resolved.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(resolved.rateOrThrow()))
            .as("revised EIR per month")
            .isEqualByComparingTo(bd("1.12558515"));
        assertThat(effectiveAnnualPercent(resolved.rateOrThrow()))
            .as("revised EIR effective p.a.")
            .isEqualByComparingTo(bd("14.375386"));
        assertThat(resolved.rateOrThrow().effectiveAnnual())
            .as("the reset absorbs the unamortised fee into a higher rate rather than releasing it")
            .isGreaterThan(case1.eir().effectiveAnnual());
    }

    @Test
    @DisplayName("the catch-up adjustment is exactly 0.00")
    void nothingHitsProfitAndLoss() {
        // There is nothing to correct retrospectively: the benchmark moved for reasons
        // wholly outside the entity's estimation process. The rate is chosen so that the
        // revised flows discount to the balance already on the books, so the restatement
        // is the balance itself and the difference is nil.
        Rate revisedEir = resolve().rateOrThrow();
        Money restated = Discounting.presentValueMoney(revisedEir.periodic(), revisedFlows(), MONTHLY);

        assertThat(paise(restated))
            .as("PV of the revised flows at the revised EIR")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(paise(restated.minus(carryingAmountAtReset())))
            .as("catch-up adjustment to P&L")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("post-reset roll-forward: 12 rows from 528,407.32 to zero, 39,452.96 of interest")
    void thePostResetSchedule() {
        Rate revisedEir = resolve().rateOrThrow();
        AmortisationResult leg = AmortisationEngine.eirLeg(
            carryingAmountAtReset(), revisedEir, revisedFlows(), MONTHLY);

        assertThat(leg.periods()).isEqualTo(12);
        assertRow(leg, 1, "528407.32", "5947.67", "487033.30");
        assertRow(leg, 2, "487033.30", "5481.97", "445193.59");
        assertRow(leg, 3, "445193.59", "5011.03", "402882.93");
        assertRow(leg, 4, "402882.93", "4534.79", "360096.03");
        assertRow(leg, 5, "360096.03", "4053.19", "316827.53");
        assertRow(leg, 6, "316827.53", "3566.16", "273072.00");
        assertRow(leg, 7, "273072.00", "3073.66", "228823.97");
        assertRow(leg, 8, "228823.97", "2575.61", "184077.89");
        assertRow(leg, 9, "184077.89", "2071.95", "138828.15");
        assertRow(leg, 10, "138828.15", "1562.63", "93069.09");
        assertRow(leg, 11, "93069.09", "1047.57", "46794.97");
        assertRow(leg, 12, "46794.97", "526.72", "0.00");

        assertThat(paise(leg.presentedTotalInterest()))
            .as("total interest over the remaining life")
            .isEqualByComparingTo(bd("39452.96"));
        assertThat(paise(leg.totalCash()))
            .as("12 revised EMIs")
            .isEqualByComparingTo(bd("567860.28"));
        assertThat(paise(leg.terminalBalance()))
            .as("TR-1: terminal closing balance")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(leg.invariants())
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
    }

    @Test
    @DisplayName("the discriminator: this event re-solves the rate and holds the balance")
    void theDiscriminatorAgainstCaseThree() {
        // Case 4 column of the fixture's comparison table. Case 3 asserts the other.
        assertThat(periodicPercent(resolve().rateOrThrow()))
            .as("EIR re-solved")
            .isEqualByComparingTo(bd("1.12558515"));
        assertThat(paise(carryingAmountAtReset()))
            .as("carrying amount unchanged")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(periodicPercent(case1.eir()))
            .as("and the original rate is genuinely superseded, not retained as in Case 3")
            .isNotEqualByComparingTo(periodicPercent(resolve().rateOrThrow()));
    }

    private void assertRow(AmortisationResult result, int period, String opening, String interest,
        String closing) {
        AmortisationRow row = result.row(period);
        assertThat(paise(row.openingGca())).as("period %d opening", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash", period)
            .isEqualByComparingTo(bd("47321.69"));
        assertThat(paise(row.closingGca())).as("period %d closing", period)
            .isEqualByComparingTo(bd(closing));
    }
}
