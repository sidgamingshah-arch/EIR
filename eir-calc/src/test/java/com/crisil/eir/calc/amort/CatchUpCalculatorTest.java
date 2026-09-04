package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CASE1_EIR;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL_BALANCE_AT_MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.EMI;
import static com.crisil.eir.calc.amort.AmortFixtures.GCA_AT_MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.MONTHLY;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static com.crisil.eir.calc.amort.AmortFixtures.case1RemainingAtMonth12;
import static com.crisil.eir.calc.amort.AmortFixtures.case3RevisedFlows;
import static com.crisil.eir.calc.amort.AmortFixtures.remainingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The B5.4.6 catch-up restatement (specification 6.3, reference case 3), and the one
 * defect it exists to prevent.
 *
 * <p>Reference case 3 re-profiles the case 1 loan's remaining 12 instalments into 18
 * smaller ones at the end of month 12: the rate is retained, the carrying amount is
 * restated to the present value of the revised flows at that rate, and the 627.42
 * difference is charged immediately.
 *
 * <p>The anti-test below is the point of the class. Re-solving the rate over the
 * revised flows from the current carrying amount reproduces that carrying amount
 * almost exactly — by construction, since that is what a solve does — so the catch-up
 * collapses to approximately zero and the B5.4.6 event has silently become a B5.4.5
 * one. No P&amp;L, no trace, and a rate that no longer reconciles to the original
 * measurement. Reference cases 3 and 4 are the same instrument in the same month and
 * differ by the whole 627.42, so this is not a rounding question: it is the highest-
 * impact error available in this domain, and CU-1 is the cheapest detector of it.
 */
class CatchUpCalculatorTest {

    @Test
    @DisplayName("reference case 3: 528,407.32 restates to 527,779.90, a 627.42 charge")
    void referenceCaseThree() {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);

        // The revised instalment is the annuity on the contractual balance at the unchanged
        // contractual rate over 18 periods — the borrower's schedule, not the accounting one.
        assertThat(Annuity.billedInstalment(CONTRACTUAL_BALANCE_AT_MONTH_12, CONTRACTUAL.periodic(), 18).amount())
            .isEqualByComparingTo(bd("32309.24"));

        assertThat(restatement.gcaBefore().amount()).isEqualByComparingTo(bd("528407.32"));
        assertThat(restatement.restatedGca().atPresentationScale().amount())
            .isEqualByComparingTo(bd("527779.90"));
        assertThat(restatement.presentedCatchUp().amount()).isEqualByComparingTo(bd("-627.42"));
        assertThat(restatement.isCharge()).isTrue();
        assertThat(restatement.isIncome()).isFalse();
        assertThat(restatement.isClean()).isTrue();
        assertThat(restatement.orThrow()).isSameAs(restatement);
    }

    @Test
    @DisplayName("CU-1: the EIR before and after the restatement is bit-identical")
    void theRateDoesNotMove() {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);

        // Not merely numerically equal: the same stored value at the same scale, because a
        // Rate always holds its 12-decimal-place stored form and "the persisted rate did not
        // change" is a statement about that representation.
        assertThat(restatement.eirAfter().periodic()).isEqualTo(restatement.eirBefore().periodic());
        assertThat(restatement.eirAfter().periodic().scale())
            .isEqualTo(restatement.eirBefore().periodic().scale());
        assertThat(InvariantChecks.bitIdentical(restatement.eirBefore(), restatement.eirAfter())).isTrue();
        assertThat(invariant(restatement, InvariantId.CU_1).satisfied()).isTrue();
        assertThat(invariant(restatement, InvariantId.CU_2).satisfied()).isTrue();
    }

    @Test
    @DisplayName("the anti-test: discounting at a RE-SOLVED rate drives the catch-up to zero")
    void discountingAtAResolvedRateCollapsesTheCatchUp() {
        FlowVector revised = case3RevisedFlows();

        // The defect, performed. Solve a rate over the revised flows to the *current*
        // carrying amount — which is what a B5.4.5 reset does — and then use that rate to
        // discount the same flows.
        Rate resolved = new BracketedNewtonSolver()
            .solve(SolveRequest.of(revised, GCA_AT_MONTH_12, MONTHLY, CONTRACTUAL.periodic()))
            .rateOrThrow();
        assertThat(resolved.periodic().setScale(10, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.0102913375"));

        CatchUpResult atTheResolvedRate =
            CatchUpCalculator.restate(resolved, GCA_AT_MONTH_12, revised, MONTHLY);

        // Approximately zero, and it could not be otherwise: the rate was chosen so that
        // these flows discount to this balance. Nothing reaches the P&L and nothing in the
        // resulting figures looks wrong.
        assertThat(atTheResolvedRate.presentedCatchUp().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(atTheResolvedRate.catchUp().amount().abs()).isLessThan(bd("0.000001"));

        // The calculator does not do that. Given the original rate it produces the 627.42
        // charge, and it is given only one rate to discount at, so the three-argument form
        // cannot commit the defect at all.
        CatchUpResult correct = CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, revised, MONTHLY);
        assertThat(correct.presentedCatchUp().amount()).isEqualByComparingTo(bd("-627.42"));
        assertThat(correct.eirBefore().periodic()).isEqualTo(CASE1_EIR.periodic());

        // And where a pipeline persists a re-solved rate of its own, CU-1 catches it. This
        // is the whole detection surface for the defect: the catch-up figure itself looks
        // perfectly reasonable — zero is a plausible answer for a re-profiling — so the
        // evidence is the rate, not the amount. Left undetected, a B5.4.6 event has become
        // a B5.4.5 one and the 627.42 charge has simply not been recognised.
        CatchUpResult breached =
            CatchUpCalculator.restate(CASE1_EIR, resolved, GCA_AT_MONTH_12, revised, MONTHLY);
        assertThat(breached.isClean()).isFalse();
        InvariantResult breach = invariant(breached, InvariantId.CU_1);
        assertThat(breach.satisfied()).isFalse();
        assertThat(breach.detail()).contains("converts a B5.4.6 event into a B5.4.5 one");
        assertThat(breach.deviation()).isEqualByComparingTo(
            resolved.periodic().subtract(CASE1_EIR.periodic()));
        assertThatThrownBy(breached::orThrow).isInstanceOf(InvariantBreachException.class);
        // The restated balance is still the correct one — the breach is in what was
        // persisted, not in the arithmetic, which is exactly why it needs an invariant
        // rather than a reconciliation.
        assertThat(breached.restatedGca().atPresentationScale().amount())
            .isEqualByComparingTo(bd("527779.90"));
    }

    @Test
    @DisplayName("the reset alternative leaves the carrying amount alone, and that is the 627.42")
    void resetAndCatchUpAreTheSameEventTreatedTwoWays() {
        FlowVector revised = case3RevisedFlows();
        Rate resolved = new BracketedNewtonSolver()
            .solve(SolveRequest.of(revised, GCA_AT_MONTH_12, MONTHLY, CONTRACTUAL.periodic()))
            .rateOrThrow();

        // Reference case 4's shape: the rate moves and the balance does not. Reference case
        // 3's: the balance moves and the rate does not. The difference between the two
        // treatments of one changed schedule is the entire 627.42.
        CatchUpResult catchUp = CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, revised, MONTHLY);

        assertThat(InvariantChecks.bitIdentical(resolved, CASE1_EIR)).isFalse();
        assertThat(GCA_AT_MONTH_12.minus(catchUp.restatedGca()).atPresentationScale().amount())
            .isEqualByComparingTo(bd("627.42"));
        assertThat(resolved.effectiveAnnual()).isLessThan(CASE1_EIR.effectiveAnnual());
    }

    @ParameterizedTest(name = "period {0}: {1} + {2} - 32,309.24 = {3}")
    @CsvSource({
        "1, 527779.90, 5500.25, 500970.91",
        "2, 500970.91, 5220.86, 473882.54",
        "3, 473882.54, 4938.56, 446511.86",
        "6, 390911.80, 4073.88, 362676.45",
        "17, 63622.20,  663.04,  31976.00",
        "18, 31976.00,  333.24,      0.00",
    })
    @DisplayName("the post-modification schedule rolls the restated balance at the retained EIR")
    void postModificationRollForward(int period, String opening, String interest, String closing) {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);
        AmortisationRow row = CatchUpCalculator
            .rollForwardRestated(restatement, case3RevisedFlows(), MONTHLY)
            .row(period);

        assertThat(row.presentedOpeningGca().amount()).isEqualByComparingTo(bd(opening));
        assertThat(row.presentedEirInterest().amount()).isEqualByComparingTo(bd(interest));
        assertThat(row.presentedClosingGca().amount()).isEqualByComparingTo(bd(closing));
    }

    @Test
    @DisplayName("the restated balance amortises to zero over the revised flows — TR-1 again")
    void restatedBalanceAmortisesToZero() {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);
        AmortisationResult post =
            CatchUpCalculator.rollForwardRestated(restatement, case3RevisedFlows(), MONTHLY);

        // Zero by construction: the restated balance *is* the present value of these flows
        // at this rate, so a residue would mean the restatement and the roll-forward were
        // given different vectors.
        assertThat(post.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(post.presentedTotalInterest().amount()).isEqualByComparingTo(bd("53786.42"));
        assertThat(post.presentedTotalCash().amount()).isEqualByComparingTo(bd("581566.32"));
        assertThat(post.isClean()).isTrue();
        assertThat(post.periods()).isEqualTo(18);
    }

    @Test
    @DisplayName("INV-1 survives the catch-up: a charge today is interest not recognised later")
    void catchUpsEnterLifetimeInterestWithAMinusSign() {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);
        AmortisationResult post =
            CatchUpCalculator.rollForwardRestated(restatement, case3RevisedFlows(), MONTHLY);

        // Interest recognised in the first 12 periods, plus the restated leg's interest,
        // plus the catch-up, must equal contractual interest billed plus the net fee.
        // 12 periods on the case 1 EIR leg:
        Money firstTwelve = Money.zero(Money.INR);
        for (int period = 1; period <= 12; period++) {
            firstTwelve = firstTwelve.plus(AmortFixtures.case1EirLeg().row(period).eirInterest());
        }
        Money eirInterestOverLife = firstTwelve.plus(post.totalInterest()).plus(restatement.catchUp());
        Money cashReceived = EMI.times(bd("12")).plus(post.totalCash());
        Money contractualInterestBilled = cashReceived.minus(AmortFixtures.PRINCIPAL);

        InvariantResult inv1 = InvariantChecks.lifetimeInterest(
            eirInterestOverLife,
            contractualInterestBilled,
            AmortFixtures.NET_INTEGRAL_FEE,
            Money.zero(Money.INR));
        // The restatement's own P&L is inside the EIR-interest total here, so the catch-up
        // argument is nil: a 627.42 charge today is 627.42 of interest that will simply not
        // be accreted later, and total recognised income over the life is unchanged.
        assertThat(inv1.satisfied()).isTrue();
        assertThat(inv1.id()).isEqualTo(InvariantId.INV_1);
    }

    @Test
    @DisplayName("CU-2: the catch-up is the restated balance less the balance before, as published")
    void catchUpTiesToThePublishedBalances() {
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);

        assertThat(restatement.catchUp().amount())
            .isEqualByComparingTo(restatement.restatedGca().minus(restatement.gcaBefore()).amount());
        assertThat(restatement.restatedGca().atPresentationScale()
            .minus(restatement.gcaBefore().atPresentationScale()).amount())
            .isEqualByComparingTo(restatement.presentedCatchUp().amount());
        assertThat(invariant(restatement, InvariantId.CU_2).satisfied()).isTrue();
    }

    @Test
    @DisplayName("a re-estimation that improves the flows produces income, not a charge")
    void anImprovingReEstimationIsIncome() {
        // The mirror of case 3: the revised estimate is of larger receipts over the same
        // tenor, so the remaining flows are worth more at the retained rate. Same mechanism,
        // opposite sign — a catch-up is not a charge by nature, it is a difference.
        FlowVector accelerated = remainingAt(MONTH_12, Money.inr("50000"), 12);
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, accelerated, MONTHLY);

        assertThat(restatement.isIncome()).isTrue();
        assertThat(restatement.catchUp().isPositive()).isTrue();
        assertThat(restatement.isClean()).isTrue();
    }

    @Test
    @DisplayName("a restatement over the unchanged flows is a no-op, which is the calibration")
    void restatingTheOriginalFlowsChangesNothing() {
        // Discounting the flows that were already expected, at the rate they were already
        // discounted at, must return the balance already carried. If this were not zero the
        // month-12 balance and the month-12 flows would not be the same measurement, and
        // every catch-up computed from them would be wrong by that amount.
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case1RemainingAtMonth12(), MONTHLY);

        assertThat(restatement.presentedCatchUp().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(restatement.restatedGca().atPresentationScale().amount())
            .isEqualByComparingTo(bd("528407.32"));
    }

    @Test
    @DisplayName("a fee settled on the event date belongs in the carrying amount, not in the vector")
    void anchorDatedFlowsAreNotDiscountedIntoTheRestatement() {
        // Only flows strictly after the anchor are discounted, so a 2,000 restructuring fee
        // dated on the modification date would vanish from the restatement without trace.
        // IFRS 9 5.4.3 requires it to adjust the carrying amount, so the caller nets it into
        // gcaBefore — and the catch-up then carries it correctly. Note the deliberate
        // asymmetry with the 10% test, which does include anchor-dated flows because B3.3.6
        // requires the test to be net of fees.
        Money restructuringFee = Money.inr("2000");
        CatchUpResult withFeeInBalance = CatchUpCalculator.restate(
            CASE1_EIR, GCA_AT_MONTH_12.plus(restructuringFee), case3RevisedFlows(), MONTHLY);
        CatchUpResult withoutFee =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);

        assertThat(withFeeInBalance.catchUp().minus(withoutFee.catchUp()).amount())
            .isEqualByComparingTo(restructuringFee.negate().amount());
        assertThat(withFeeInBalance.presentedCatchUp().amount()).isEqualByComparingTo(bd("-2627.42"));
    }

    @Test
    @DisplayName("a rate whose frequency contradicts the convention is rejected")
    void rateAndConventionMustAgree() {
        Rate annual = Rate.annualEffective(bd("0.13248094"));

        assertThatThrownBy(() ->
            CatchUpCalculator.restate(annual, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compounds 1 times a year");
    }

    @Test
    @DisplayName("a carrying amount in another currency is rejected")
    void currencyMustAgree() {
        Money usd = Money.of(bd("528407.32"), Currency.getInstance("USD"));

        assertThatThrownBy(() -> CatchUpCalculator.restate(CASE1_EIR, usd, case3RevisedFlows(), MONTHLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("the restatement is deterministic and reads no clock")
    void deterministic() {
        List<CatchUpResult> runs = List.of(
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY),
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY));

        assertThat(runs.get(1).catchUp().amount()).isEqualByComparingTo(runs.get(0).catchUp().amount());
        assertThat(runs.get(1).restatedGca().amount()).isEqualByComparingTo(runs.get(0).restatedGca().amount());
    }

    @Test
    @DisplayName("TR-1 is asserted by restate itself, so no caller can omit the one real check")
    void restateAssertsTheTerminalBalance() {
        // The finding this test exists for. CatchUpCalculator.rollForwardRestated has claimed
        // "TR-1 is asserted" in its javadoc since this class was written, and NOTHING in
        // eir-application ever called it -- so the claim was true of the method and false of the
        // engine. A close driven through the JDBC ports published a restatement carrying CU-1 and
        // CU-2 only. TR-1 is now folded into restate, which is the difference between a control and
        // an available control.
        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, case3RevisedFlows(), MONTHLY);

        assertThat(restatement.invariants())
            .extracting(InvariantResult::id)
            .as("every restatement carries all three, including the ones nobody has written yet")
            .contains(InvariantId.CU_1, InvariantId.CU_2, InvariantId.TR_1);
        InvariantResult terminal = invariant(restatement, InvariantId.TR_1);
        assertThat(terminal.satisfied())
            .as("reference case 3's restatement amortises to nil over its own revised flows")
            .isTrue();

        // AND THE RESULT MUST COME FROM THE EVALUATOR, not be fabricated. This assertion exists
        // because the first version of these tests did not have it, and a mutation proved them
        // worthless: replacing the whole terminal check with
        // `List.of(InvariantResult.pass(InvariantId.TR_1, "vacuous"))` left all three green. A
        // vacuous pass under a real identifier is the precise defect this change set out to end, so
        // reproducing it inside the fix and not noticing would have been the worst outcome available.
        //
        // AmortisationEngine.roll is the only thing that phrases TR-1 this way, and it can only
        // phrase it after walking the vector -- so the period count in the detail is evidence that
        // the roll actually happened. Reference case 3's revised schedule is 18 periods.
        assertThat(terminal.detail())
            .as("the detail carries the roll's own period count, which a fabricated pass cannot")
            .contains("terminal EIR-leg carrying amount after 18 periods");
    }

    @Test
    @DisplayName("TR-1 catches a restatement CU-2 cannot: a balance that is not the flows' PV")
    void terminalBalanceCatchesAWrongRestatement() {
        // WHY THIS IS NOT THE TAUTOLOGY CU-2 IS. CU-2 compares restated - gcaBefore against
        // catchUp, and catchUp is DEFINED as restated - gcaBefore, so it can only ever report a
        // sub-paisa rounding residue. A review proved that by replacing the whole present-value
        // calculation with `restated = gcaBefore` -- deleting the arithmetic -- and CU-1 and CU-2
        // both stayed green.
        //
        // TR-1 is a SECOND, INDEPENDENT derivation: presentValueMoney sums discounted flows, while
        // AmortisationEngine.eirLeg iterates B*(1+r)^dtau - CF and asserts the terminal balance is
        // nil. The two agree only if both arithmetics are right and both were handed the same rate,
        // vector and convention.
        //
        // Performed here the way that mutation would be seen from outside: restate a balance that
        // is NOT the present value of the flows, by handing it a carrying amount and then rolling
        // that same carrying amount forward. Derived by hand from reference case 3's own figures:
        // the true restated balance is 527,779.90, so rolling 528,407.32 forward over the revised
        // flows instead must leave a terminal residue of 528,407.32 - 527,779.90 = 627.42 accreted
        // over the remaining 18 periods -- decidedly not nil, whatever its exact size.
        FlowVector revised = case3RevisedFlows();
        AmortisationResult wrong =
            AmortisationEngine.eirLeg(GCA_AT_MONTH_12, CASE1_EIR, revised, MONTHLY);

        InvariantResult terminal = wrong.invariants().stream()
            .filter(entry -> entry.id() == InvariantId.TR_1)
            .findFirst()
            .orElseThrow(() -> new AssertionError("eirLeg must assert TR-1"));
        assertThat(terminal.satisfied())
            .as("a balance that is not the PV of these flows does not amortise to nil, and TR-1"
                + " is the only control in the restatement that can say so")
            .isFalse();
        assertThat(wrong.terminalBalance().atPresentationScale().amount())
            .as("and the residue is the restatement error itself, accreted over 18 periods")
            .isNotEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("TR-1 does NOT catch a truncated revised vector, and that boundary is the point")
    void terminalBalanceCannotCatchATruncatedVector() {
        // The honest limit of the fix above, pinned so nobody reads TR-1 as more than it is.
        //
        // A close through the JDBC ports restated a contract from 528,407.32 to 46,538.28 -- a 91%
        // write-down in one period -- because the revised vector held one flow where the term
        // implied twelve. TR-1 passes on that, and it must: the restatement and the roll-forward
        // consume the SAME truncated vector, so they agree with each other. TR-1 detects an
        // inconsistency between two derivations; it cannot detect a wrong input to both.
        //
        // Reproduced on reference case 3's own numbers: keep only the FIRST revised flow, restate
        // to its present value, and TR-1 is satisfied while the carrying amount collapses.
        FlowVector full = case3RevisedFlows();
        FlowVector truncated = FlowVector.of(
            full.anchorDate(), full.currency(), List.of(full.future().get(0)));

        CatchUpResult restatement =
            CatchUpCalculator.restate(CASE1_EIR, GCA_AT_MONTH_12, truncated, MONTHLY);

        InvariantResult truncatedTerminal = invariant(restatement, InvariantId.TR_1);
        assertThat(truncatedTerminal.satisfied())
            .as("satisfied, because one flow's PV does amortise to nil over that one flow")
            .isTrue();
        assertThat(truncatedTerminal.detail())
            .as("and it is the real evaluator saying so over a ONE-period roll, not a fabrication")
            .contains("terminal EIR-leg carrying amount after 1 periods");
        assertThat(invariant(restatement, InvariantId.CU_2).satisfied())
            .as("and CU-2 too, as ever")
            .isTrue();
        assertThat(restatement.restatedGca().atPresentationScale().amount())
            .as("while the carrying amount collapses to a single instalment's present value")
            .isLessThan(bd("40000.00"));
        assertThat(restatement.isCharge())
            .as("a write-down of most of the balance, with every invariant green -- which is why"
                + " the materiality threshold is docs/10 DR-06a and not a code default")
            .isTrue();
    }

    private static InvariantResult invariant(CatchUpResult result, InvariantId id) {
        return result.invariants().stream()
            .filter(entry -> entry.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " was not asserted"));
    }
}
