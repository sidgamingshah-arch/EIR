package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTH_12;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.NET_INTEGRAL_FEE;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.case1Fees;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.case1Terms;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.percentChange;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.projection.AnnuityProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.projection.RepricingShortcutProjector;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 8 — the IFRS 9 B5.4.4 next-repricing-date shortcut.
 *
 * <p>The case 1 loan priced as floating and reset annually. For fee-amortisation
 * purposes the instrument is treated as maturing at the next reset: the projector
 * appends a synthetic {@code NOTIONAL_REDEMPTION} equal to the contractual balance
 * at that date and solves over the truncated vector.
 *
 * <p>Operationally this is the important case. It removes the reset-loop cost —
 * with no unamortised fee to carry forward, a reset reprices the interest leg and
 * re-solves nothing for the fee — and it dissolves the behavioural-life estimation
 * problem for the floating-rate book, where observed life is far shorter than the
 * contractual tenor because of balance-transfer churn. It is an accounting policy
 * election per product, not a per-contract optimisation.
 *
 * <p>Figures from {@code docs/reference-cases/case-08-b544-repricing-shortcut.md}.
 */
class Case08RepricingShortcutTest {

    private static final Money NOTIONAL_REDEMPTION = Money.inr("529815.61");

    private final ReferenceCaseFixtures.Baseline fullExpectedLife = baseline();

    private ContractTerms floatingTerms() {
        return case1Terms(RateType.FLOATING);
    }

    /** The shortcut is layered on as a per-product election over the shape's own projector. */
    private ProjectionResult shortcutProjection() {
        return ProjectorRegistry.standard()
            .prepend(new RepricingShortcutProjector(new AnnuityProjector(), 12))
            .project(floatingTerms(), case1Fees());
    }

    private SolveResult shortcutSolve() {
        return ReferenceCaseFixtures.solve(shortcutProjection(), floatingTerms());
    }

    private AmortisationResult shortcutEirLeg() {
        return ReferenceCaseFixtures.eirLeg(shortcutProjection(), shortcutSolve().rateOrThrow());
    }

    private TwoLegResult shortcutTwoLeg() {
        ProjectionResult projection = shortcutProjection();
        return TwoLegResult.reconcile(
            shortcutEirLeg(),
            ReferenceCaseFixtures.contractualLeg(projection, floatingTerms()),
            shortcutSolve().rateOrThrow(),
            floatingTerms().contractualRate(),
            NET_INTEGRAL_FEE);
    }

    @Test
    @DisplayName("the truncated vector is 12 EMIs of 47,073.47 plus a notional redemption of 529,815.61")
    void theSyntheticRedemptionClosesTheVectorAtTheReset() {
        ProjectionResult projection = shortcutProjection();

        assertThat(paise(projection.initialCarryingAmount()))
            .as("initial gross carrying amount is unchanged by the election")
            .isEqualByComparingTo(bd("995000.00"));
        assertThat(projection.expected().maxPeriodIndex())
            .as("the vector ends at the reset, not at contractual maturity")
            .isEqualTo(12);

        CashFlow notional = projection.expected().flows().stream()
            .filter(flow -> flow.kind() == FlowKind.NOTIONAL_REDEMPTION)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no notional redemption was appended"));
        assertThat(notional.date()).isEqualTo(MONTH_12);
        assertThat(paise(notional.amount()))
            .as("the contractual balance at the reset — what the borrower would owe if the "
                + "instrument matured there. Contractual, not the EIR-leg balance: using the "
                + "latter would put the unamortised fee inside the flow the fee is amortised "
                + "against")
            .isEqualByComparingTo(bd("529815.61"));
        assertThat(paise(NOTIONAL_REDEMPTION)).isEqualByComparingTo(paise(notional.amount()));

        assertThat(projection.futureLegIsWhollySynthetic())
            .as("a B5.4.4-truncated vector carries real instalments up to the reset and one "
                + "synthetic flow at it, so it is not an approximation in the ACPIR 54 sense")
            .isFalse();
    }

    /**
     * The fixture's monthly figure.
     *
     * <p>The fixture states the flows as "12 EMIs of 47,073.47 + notional redemption of
     * 529,815.61 at month 12" and the rate as 1.05614730%. Those two statements are
     * reconciled by the rounding discipline rather than in tension with it: the
     * contractual balance at the reset is 529,815.605015332452 at working precision,
     * which <em>presents</em> as the fixture's printed 529,815.61 and <em>solves</em> to
     * the fixture's printed 1.05614730%. Rounding the synthetic flow before solving —
     * which the engine used to do — lands on 1.05614735% instead and lifts five of the
     * twelve published closing balances by a paisa. A notional redemption is never
     * billed to anybody, so there is no cash event at which currency scale attaches;
     * see {@code RepricingShortcutProjector.contractualBalanceAtReset}.
     */
    @Test
    @DisplayName("the shortcut EIR is 1.05614730% per month")
    void theShortcutRatePerMonth() {
        SolveResult result = shortcutSolve();

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(result.rateOrThrow()))
            .as("EIR per month over the truncated vector")
            .isEqualByComparingTo(bd("1.05614730"));
    }

    @Test
    @DisplayName("the shortcut EIR is 13.436507% effective p.a., above the full-life 13.248094%")
    void theShortcutRateAnnualised() {
        SolveResult result = shortcutSolve();

        assertThat(effectiveAnnualPercent(result.rateOrThrow()))
            .as("EIR effective p.a. under the shortcut")
            .isEqualByComparingTo(bd("13.436507"));
        assertThat(effectiveAnnualPercent(fullExpectedLife.eir()))
            .as("against full expected life over 24 months")
            .isEqualByComparingTo(bd("13.248094"));
        assertThat(result.rateOrThrow().effectiveAnnual())
            .as("the same 5,000 of fee spread over half the horizon must yield more")
            .isGreaterThan(fullExpectedLife.eir().effectiveAnnual());
    }

    /**
     * The fixture's roll-forward.
     *
     * <p>All twelve rows reproduce at the stored rate 0.010561473001. They are sensitive
     * to the rate's tenth decimal place rather than to anything in the roll-forward
     * arithmetic: solving over a presentation-rounded notional redemption moves five
     * closing balances (periods 3, 5, 8, 10 and 11) up by a paisa while leaving every
     * accretion figure and the terminal zero intact, which is what makes this table a
     * sharper check on the synthetic flow than the rate assertion alone.
     */
    @Test
    @DisplayName("roll-forward under the shortcut: the notional redemption closes the vector at zero")
    void theRollForward() {
        AmortisationResult leg = shortcutEirLeg();

        assertThat(leg.periods()).isEqualTo(12);
        assertRow(leg, 1, "995000.00", "10508.67", "47073.47", "958435.20");
        assertRow(leg, 2, "958435.20", "10122.49", "47073.47", "921484.21");
        assertRow(leg, 3, "921484.21", "9732.23", "47073.47", "884142.97");
        assertRow(leg, 4, "884142.97", "9337.85", "47073.47", "846407.36");
        assertRow(leg, 5, "846407.36", "8939.31", "47073.47", "808273.19");
        assertRow(leg, 6, "808273.19", "8536.56", "47073.47", "769736.28");
        assertRow(leg, 7, "769736.28", "8129.55", "47073.47", "730792.36");
        assertRow(leg, 8, "730792.36", "7718.24", "47073.47", "691437.13");
        assertRow(leg, 9, "691437.13", "7302.59", "47073.47", "651666.26");
        assertRow(leg, 10, "651666.26", "6882.56", "47073.47", "611475.34");
        assertRow(leg, 11, "611475.34", "6458.08", "47073.47", "570859.95");
        assertRow(leg, 12, "570859.95", "6029.12", "576889.08", "0.00");

        assertThat(paise(leg.terminalBalance()))
            .as("terminal closing balance — the notional redemption closes the vector")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(leg.invariants())
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
    }

    @Test
    @DisplayName("the shortcut recognises the FULL 5,000.00 of net fee in months 1-12")
    void theWholeFeeIsRecognisedBeforeTheReset() {
        TwoLegResult twoLeg = shortcutTwoLeg();

        assertThat(paise(twoLeg.netFeeRecognised()))
            .as("net fee recognised, months 1-12, under the shortcut")
            .isEqualByComparingTo(bd("5000.00"));
        assertThat(paise(twoLeg.presentedUnamortisedFeeAt(12)))
            .as("unamortised fee past the reset — nothing to carry across")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(twoLeg.isClean())
            .as("breaches: %s", twoLeg.breaches())
            .isTrue();
    }

    @Test
    @DisplayName("against full expected life: 3,591.71 becomes 5,000.00, a 39.2% acceleration")
    void theComparisonAgainstFullExpectedLife() {
        Money fullLifeYearOne = NET_INTEGRAL_FEE.minus(fullExpectedLife.twoLeg().unamortisedFeeAt(12));

        assertThat(paise(fullLifeYearOne))
            .as("net fee recognised months 1-12 over full expected life")
            .isEqualByComparingTo(bd("3591.71"));
        assertThat(paise(fullExpectedLife.twoLeg().presentedUnamortisedFeeAt(12)))
            .as("unamortised fee past the reset under full expected life")
            .isEqualByComparingTo(bd("1408.29"));

        assertThat(percentChange(bd("5000.00"), paise(fullLifeYearOne)))
            .as("the shortcut accelerates year-one fee recognition by 39.2%")
            .isEqualByComparingTo(bd("39.2"));
    }

    @Test
    @DisplayName("cross-check: the 1,408.29 the shortcut eliminates is Cases 1 and 2's figure")
    void theFigureAppearsThreeTimes() {
        // Three independent routes to one number: Case 1's INV-4 at month 12, Case 2's
        // prepayment acceleration, and the balance this shortcut removes.
        Money legDifferenceAtMonth12 = fullExpectedLife.contractualLeg().row(12).closingGca()
            .minus(fullExpectedLife.eirLeg().row(12).closingGca());

        assertThat(paise(legDifferenceAtMonth12)).isEqualByComparingTo(bd("1408.29"));
        assertThat(paise(fullExpectedLife.twoLeg().presentedUnamortisedFeeAt(12)))
            .isEqualByComparingTo(bd("1408.29"));
        assertThat(paise(shortcutTwoLeg().presentedUnamortisedFeeAt(12)))
            .as("and the shortcut leaves none of it")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("the election is guarded to floating-rate instruments")
    void aFixedRateLoanHasNothingToRepriceTo() {
        // A fixed-rate loan does not reprice to market by its own terms, so B5.4.4 has
        // nothing to attach to and a renegotiated fixed rate is a modification rather
        // than a reset (FR-507).
        RepricingShortcutProjector shortcut =
            new RepricingShortcutProjector(new AnnuityProjector(), 12);

        assertThat(shortcut.supports(case1Terms(RateType.FIXED))).isFalse();
        assertThat(shortcut.supports(case1Terms(RateType.FLOATING))).isTrue();
        assertThatThrownBy(() -> shortcut.project(case1Terms(RateType.FIXED), case1Fees()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FR-507");

        // And a reset at or beyond maturity is declined rather than silently returning
        // the full vector under a name that claims otherwise.
        assertThat(new RepricingShortcutProjector(new AnnuityProjector(), 24)
            .supports(case1Terms(RateType.FLOATING))).isFalse();
    }

    @Test
    @DisplayName("the full expected life projection is unchanged by the existence of the election")
    void theFullLifeProjectionIsUntouched() {
        // The shortcut is a per-product election layered on with prepend(); the shipped
        // registry still projects the full 24-period vector for a product that has not
        // elected it.
        ProjectionResult unelected =
            ProjectorRegistry.standard().project(floatingTerms(), case1Fees());

        assertThat(unelected.expected().maxPeriodIndex()).isEqualTo(24);
        assertThat(unelected.expected().flows())
            .noneMatch(flow -> flow.kind() == FlowKind.NOTIONAL_REDEMPTION);
        assertThat(paise(unelected.initialCarryingAmount())).isEqualByComparingTo(bd("995000.00"));
        assertThat(unelected.recommendedConvention().periodsPerYear()).isEqualTo(12);
    }

    private void assertRow(AmortisationResult result, int period, String opening, String interest,
        String cash, String closing) {
        AmortisationRow row = result.row(period);
        assertThat(paise(row.openingGca())).as("period %d opening", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash", period)
            .isEqualByComparingTo(bd(cash));
        assertThat(paise(row.closingGca())).as("period %d closing", period)
            .isEqualByComparingTo(bd(closing));
    }
}
