package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static com.crisil.eir.calc.projection.CaseFixtures.shaped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The IFRS 9 B5.4.4 next-repricing-date shortcut (calculation specification 5.6,
 * reference case 8).
 *
 * <p>Where a premium, discount or fee relates to a variable repriced to market rates
 * before maturity, it amortises to the next repricing date rather than over expected
 * life. Mechanically the delegate's vector is truncated at the reset and one
 * synthetic {@link FlowKind#NOTIONAL_REDEMPTION} equal to the <em>contractual</em>
 * balance there closes it.
 *
 * <p>Two properties carry the whole mechanism and both are asserted below. The
 * synthetic flow must be the contractual balance — 529,815.61 on the Case 1 loan
 * repriced at month 12 — because the borrower owes the contractual balance, and using
 * the EIR-leg balance instead would put the unamortised fee inside the flow the fee
 * is being amortised against. And it must share its period ordinal with the month-12
 * instalment, which is why {@code periodIndex} is explicit on a
 * {@link com.crisil.eir.domain.CashFlow} rather than inferred from position: two flows
 * on one date are two flows, and the discounting has to see both.
 */
class RepricingShortcutProjectorTest {

    private static final int RESET_PERIOD = 12;

    private final RepricingShortcutProjector shortcut =
        new RepricingShortcutProjector(new AnnuityProjector(), RESET_PERIOD);

    private static ContractTerms floatingCase1() {
        return shaped(ScheduleShape.ANNUITY_EMI, 24, RateType.FLOATING);
    }

    @Test
    @DisplayName("exactly one notional redemption is appended, at the reset date, for the contractual balance")
    void appendsOneNotionalRedemptionAtTheReset() {
        ProjectionResult result = shortcut.project(floatingCase1(), case1Fees());
        List<CashFlow> synthetic = result.contractual().flows().stream()
            .filter(flow -> flow.kind() == FlowKind.NOTIONAL_REDEMPTION)
            .toList();

        assertThat(synthetic).hasSize(1);
        CashFlow redemption = synthetic.get(0);
        assertThat(redemption.periodIndex()).isEqualTo(RESET_PERIOD);
        assertThat(redemption.date()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(redemption.date()).isEqualTo(floatingCase1().dueDate(RESET_PERIOD));
        // The figure that ties three ways: Case 1's INV-4 at month 12, Case 2's
        // prepayment acceleration, and the fee this shortcut eliminates.
        assertThat(redemption.amount().atPresentationScale().amount())
            .isEqualByComparingTo(bd("529815.61"));
        // Carried at working precision, not presented. The instrument does not actually
        // mature at the reset and nobody is ever billed this amount, so there is no cash
        // event at which currency scale attaches. Presenting it before the solve moves
        // Case 8's published monthly EIR from 1.05614730% to 1.05614735%.
        assertThat(redemption.amount().amount())
            .isEqualByComparingTo(bd("529815.6050153324520158584563"));
        assertThat(redemption.amount().amount().scale())
            .as("a synthetic flow is an accounting construct, so it is not rounded to paise")
            .isGreaterThan(2);
    }

    @Test
    @DisplayName("the synthetic amount is the contractual balance, computed by the one routine that produces it")
    void syntheticAmountIsTheContractualBalance() {
        FlowVector full = new AnnuityProjector().project(floatingCase1(), case1Fees()).contractual();

        Money atReset = shortcut.contractualBalanceAtReset(floatingCase1(), full);

        assertThat(atReset.atPresentationScale().amount()).isEqualByComparingTo(bd("529815.61"));
        // Same number from the behavioural-truncation path, which is the cross-check the
        // shared routine exists to make possible: 529,815.61 is the contractual balance
        // at month 12 whether it is reached as a notional redemption or as an expected
        // prepayment.
        assertThat(ContractualBalance.presentedAfter(floatingCase1().principal(),
            floatingCase1().periodicRate(), full, RESET_PERIOD).amount())
            .isEqualByComparingTo(bd("529815.61"));
    }

    @Test
    @DisplayName("the truncated vector keeps twelve real instalments and nothing past the reset")
    void truncatesAtTheReset() {
        ProjectionResult result = shortcut.project(floatingCase1(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(13);
        assertThat(future.stream().filter(flow -> flow.kind() == FlowKind.COMBINED_EMI).toList())
            .hasSize(12);
        assertThat(result.contractual().maxPeriodIndex()).isEqualTo(RESET_PERIOD);
        // The instalments are the delegate's — the same amounts the lender bills — rather
        // than a schedule re-derived over twelve periods, which would bill a different
        // EMI and reconcile to nothing.
        assertThat(future).filteredOn(flow -> flow.kind() == FlowKind.COMBINED_EMI)
            .allSatisfy(flow -> assertThat(flow.amount().amount()).isEqualByComparingTo(bd("47073.47")));
        // A B5.4.4 vector is not "wholly synthetic": it carries real instalments up to
        // the reset and one synthetic flow at it, and a caller must not label it an
        // approximation.
        assertThat(result.futureLegIsWhollySynthetic()).isFalse();
        assertThat(result.initialCarryingAmount().amount()).isEqualByComparingTo(bd("995000.00"));
        assertThat(result.initialRecognitionCheck().satisfied()).isTrue();
    }

    @Test
    @DisplayName("two flows share the reset date and both are discounted")
    void bothFlowsOnTheResetDateAreDiscounted() {
        ProjectionResult result = shortcut.project(floatingCase1(), case1Fees());
        FlowVector vector = result.contractual();
        TimeConvention convention = result.recommendedConvention();
        BigDecimal rate = bd("0.0105614735");

        List<CashFlow> atReset = vector.future().stream()
            .filter(flow -> flow.periodIndex() == RESET_PERIOD)
            .toList();
        assertThat(atReset).hasSize(2);
        assertThat(atReset).extracting(CashFlow::date).containsOnly(LocalDate.of(2027, 4, 1));

        // The assertion that matters: the present value counts both flows at period 12.
        // A vector that silently collapsed them, or that dropped one because a period
        // ordinal was treated as a key, would discount 47,073.47 less and solve to a
        // materially lower rate.
        BigDecimal wholeVector = Discounting.presentValue(rate, vector, convention);
        BigDecimal termByTerm = BigDecimal.ZERO;
        for (CashFlow flow : vector.future()) {
            termByTerm = termByTerm.add(flow.amount().amount().multiply(
                Precision.discountFactor(rate, convention.tau(vector.anchorDate(), flow)),
                Precision.WORKING), Precision.WORKING);
        }
        assertThat(wholeVector).isEqualByComparingTo(termByTerm);
        assertThat(wholeVector.setScale(2, Precision.MODE))
            .as("the truncated vector discounts to the initial carrying amount at the shortcut EIR")
            .isEqualByComparingTo(bd("995000.00"));
    }

    @Test
    @DisplayName("the shortcut still licenses the periodic index on a clean monthly schedule")
    void truncationDoesNotVoidPeriodicIndexing() {
        ProjectionResult result = shortcut.project(floatingCase1(), case1Fees());

        // Two flows on one boundary date is not a broken period: every ordinal from 1 to
        // 12 is present and every flow sits on its own boundary.
        assertThat(result.recommendedConvention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
        assertThat(result.contractual().periodicIndexEligible(12)).isTrue();
    }

    @Test
    @DisplayName("the shortcut is guarded to floating-rate instruments")
    void onlyFloatingRateInstrumentsReprice() {
        // A fixed-rate loan does not reprice to market by its own terms, so B5.4.4 has
        // nothing to attach to, and a renegotiated fixed rate is a modification rather
        // than a reset (FR-507).
        assertThat(shortcut.supports(floatingCase1())).isTrue();
        assertThat(shortcut.supports(case1())).isFalse();
        assertThatThrownBy(() -> shortcut.project(case1(), case1Fees()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reprices to market rates by its own terms")
            .hasMessageContaining("a renegotiated fixed rate is a modification, not a reset");
    }

    @Test
    @DisplayName("a reset at or beyond maturity is declined rather than silently ignored")
    void aResetBeyondMaturityIsDeclined() {
        RepricingShortcutProjector atMaturity =
            new RepricingShortcutProjector(new AnnuityProjector(), 24);

        assertThat(atMaturity.supports(floatingCase1())).isFalse();
        assertThatThrownBy(() -> atMaturity.project(floatingCase1(), case1Fees()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nothing to truncate");
        assertThatThrownBy(() -> new RepricingShortcutProjector(new AnnuityProjector(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("periodsToNextRepricing must be >= 1");
    }

    @Test
    @DisplayName("the election is a product configuration, carried by the projector and not by the contract")
    void theElectionLivesOnTheProjector() {
        // Applied inconsistently the shortcut is indefensible: two identical mortgages,
        // one shortcut and one not, differ by 39.2% in year-one fee recognition on
        // nothing but a projection setting. So the election is constructor state on the
        // projector the product configuration builds (FR-508), and no attribute of
        // ContractTerms can switch it on or off.
        assertThat(shortcut.periodsToNextRepricing()).isEqualTo(RESET_PERIOD);
        assertThat(shortcut.delegate()).isInstanceOf(AnnuityProjector.class);

        RepricingShortcutProjector quarterly =
            new RepricingShortcutProjector(new AnnuityProjector(), 3);
        assertThat(quarterly.project(floatingCase1(), case1Fees()).contractual().maxPeriodIndex())
            .isEqualTo(3);
        assertThat(shortcut.project(floatingCase1(), case1Fees()).contractual().maxPeriodIndex())
            .isEqualTo(RESET_PERIOD);
    }

    @Test
    @DisplayName("the truncated vector opens at the same carrying amount as the full one")
    void theCarryingAmountIsUnchangedByTruncation() {
        // The shortcut changes the horizon the fee amortises over, not the amount
        // recognised at inception. A carrying amount that moved with the election would
        // mean the election was changing the balance sheet at day one.
        ProjectionResult full = new AnnuityProjector().project(floatingCase1(), case1Fees());
        ProjectionResult truncated = shortcut.project(floatingCase1(), case1Fees());

        assertThat(truncated.initialCarryingAmount()).isEqualTo(full.initialCarryingAmount());
        assertThat(truncated.contractual().atInception())
            .containsExactlyElementsOf(full.contractual().atInception());
        assertThat(truncated.contractual().anchorDate()).isEqualTo(DISBURSEMENT);
    }
}
