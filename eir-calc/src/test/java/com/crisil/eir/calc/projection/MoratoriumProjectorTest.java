package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static com.crisil.eir.calc.projection.CaseFixtures.case1;
import static com.crisil.eir.calc.projection.CaseFixtures.case1Fees;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A moratorium followed by an annuity, with and without interest capitalisation
 * (calculation specification 5.5, ACPIR 9(6)(i)).
 *
 * <p>Where interest capitalises — education loans, project-finance IDC before
 * commercial operation date — the accrued interest increases the gross carrying
 * amount and the annuity is struck on the grown balance. The EIR is computed over
 * expected life <em>inclusive</em> of the moratorium, which is why the term counts
 * the moratorium periods and an expected life ending inside the moratorium is
 * rejected: it would exclude the capitalisation from the very rate the
 * capitalisation feeds.
 *
 * <p>One contractual feature, two separate consequences, and the engine must not let
 * either imply the other. ACPIR 9(6)(i) confirms interest becomes due only after the
 * moratorium, so the exposure is not overdue in the interim: the capitalisation is an
 * EIR input and the non-overdue status is a staging input. Nothing in this projector
 * touches staging, and the tests below assert only the EIR half.
 */
class MoratoriumProjectorTest {

    private static final int MORATORIUM = 6;

    private final MoratoriumProjector projector = new MoratoriumProjector();

    private static ContractTerms capitalising() {
        return case1().withMoratorium(MORATORIUM, true);
    }

    private static ContractTerms servicing() {
        return case1().withMoratorium(MORATORIUM, false);
    }

    @Test
    @DisplayName("interest capitalisation increases the balance the annuity is struck on")
    void capitalisationIncreasesTheCarryingBalance() {
        // P * (1+i)^m: 1,000,000 at 1% a month over six months is 1,061,520.150601.
        // Compound accretion, not a simple-interest pro-rata — pro-rating understates a
        // long deferral, and an education-loan moratorium can run five years.
        Money capitalised = projector.capitalisedBalance(capitalising());

        assertThat(capitalised.amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("1061520.150601"));
        assertThat(capitalised.amount()).isGreaterThan(case1().principal().amount());
        assertThat(capitalised.amount()).isEqualByComparingTo(
            case1().principal().times(Precision.onePlusPow(bd("0.01"), MORATORIUM)).amount());
        // Interest serviced through the moratorium leaves the balance where it started.
        assertThat(projector.capitalisedBalance(servicing()).amount())
            .isEqualByComparingTo(bd("1000000"));
    }

    @Test
    @DisplayName("the capitalised balance shows up as a growing contractual balance through the moratorium")
    void theBalanceGrowsThroughTheMoratorium() {
        ProjectionResult capitalisingResult = projector.project(capitalising(), case1Fees());
        ProjectionResult servicingResult = projector.project(servicing(), case1Fees());

        // Rolled from the vector rather than from the formula: no cash arrives in periods
        // 1 to 6 on the capitalising profile, so the accrual has nowhere to go but the
        // balance. This is the ledger consequence of the arithmetic above, and it is what
        // makes the capitalisation an EIR input rather than a disclosure.
        assertThat(ContractualBalance.after(capitalising().principal(), capitalising().periodicRate(),
            capitalisingResult.contractual(), MORATORIUM).amount().setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("1061520.150601"));
        assertThat(ContractualBalance.after(servicing().principal(), servicing().periodicRate(),
            servicingResult.contractual(), MORATORIUM).amount().setScale(2, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("1000000.00"));
    }

    @Test
    @DisplayName("the term covers the moratorium: no flows during it, instalments from period 7 to 24")
    void theTermCoversTheMoratorium() {
        ProjectionResult result = projector.project(capitalising(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(18);
        assertThat(future).extracting(CashFlow::periodIndex)
            .containsExactlyElementsOf(IntStream.rangeClosed(7, 24).boxed().toList());
        assertThat(future).allSatisfy(flow ->
            assertThat(flow.kind()).isEqualTo(FlowKind.COMBINED_EMI));
        // termPeriods counts the moratorium, so maturity is 24 months out and not 30: the
        // EIR is computed over expected life inclusive of the moratorium, and a projector
        // that extended the term instead would lengthen the amortisation of every fee.
        assertThat(capitalising().maturityDate()).isEqualTo(LocalDate.of(2028, 4, 1));
        assertThat(capitalising().repaymentPeriods()).isEqualTo(18);
        assertThat(result.contractual().maxPeriodIndex()).isEqualTo(24);
    }

    @Test
    @DisplayName("the post-moratorium instalment amortises the capitalised balance over the periods that remain")
    void instalmentIsTheAnnuityOnTheCapitalisedBalance() {
        // Annuity on 1,061,520.150601 at 1% over the 18 remaining periods.
        assertThat(projector.billedInstalment(capitalising()).amount())
            .isEqualByComparingTo(bd("64733.67"));
        assertThat(projector.instalment(capitalising()).amount())
            .isEqualByComparingTo(Annuity.instalment(projector.capitalisedBalance(capitalising()),
                bd("0.01"), 18).amount());
        // The servicing profile defers principal only, so its annuity is struck on the
        // original principal and its instalment is lower.
        assertThat(projector.billedInstalment(servicing()).amount())
            .isEqualByComparingTo(bd("60982.05"));
        assertThat(projector.servicedInterest(servicing()).amount()).isEqualByComparingTo(bd("10000.00"));
        assertThat(projector.servicedInterest(capitalising()).isZero()).isTrue();
    }

    @Test
    @DisplayName("the servicing profile bills interest through the moratorium")
    void servicingProfileBillsInterestDuringTheMoratorium() {
        ProjectionResult result = projector.project(servicing(), case1Fees());
        List<CashFlow> future = result.contractual().future();

        assertThat(future).hasSize(24);
        assertThat(future.subList(0, MORATORIUM)).allSatisfy(flow -> {
            assertThat(flow.kind()).isEqualTo(FlowKind.INTEREST);
            assertThat(flow.amount().amount()).isEqualByComparingTo(bd("10000.00"));
        });
        assertThat(future.get(MORATORIUM).kind()).isEqualTo(FlowKind.COMBINED_EMI);
        // Every ordinal is present and every flow is on its boundary, so this profile
        // keeps the periodic index while the capitalising one loses it.
        assertThat(result.recommendedConvention()).isEqualTo(new TimeConvention.PeriodicIndex(12));
    }

    @Test
    @DisplayName("a capitalising moratorium voids periodic indexing without anyone electing it")
    void capitalisingMoratoriumFallsBackToActualDating() {
        ProjectionResult result = projector.project(capitalising(), case1Fees());

        assertThat(result.contractual().periodicIndexEligible(12)).isFalse();
        assertThat(result.recommendedConvention()).isInstanceOf(TimeConvention.ActualDate.class);
    }

    @Test
    @DisplayName("an expected life ending inside the moratorium is rejected")
    void expectedLifeMustCoverTheMoratorium() {
        // Six months of expected life on a six-month moratorium would solve the rate over
        // a vector with no instalments in it, excluding the capitalisation from the rate
        // the capitalisation feeds — which understates yield on precisely the products
        // where the deferral is largest.
        ContractTerms tooShort = capitalising().withLives(6, 24, "behavioural life shorter than term");

        assertThatThrownBy(() -> projector.project(tooShort, case1Fees()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ends inside the 6-period moratorium")
            .hasMessageContaining("ACPIR 9(6)(i)");
    }

    @Test
    @DisplayName("the moratorium projector claims the terms the annuity projector declines")
    void selectionDoesNotDependOnRegistryOrder() {
        assertThat(projector.supports(capitalising())).isTrue();
        assertThat(projector.supports(servicing())).isTrue();
        assertThat(projector.supports(case1())).isFalse();
        assertThat(new AnnuityProjector().supports(capitalising())).isFalse();
        // A structured schedule with a moratorium is also this projector's, because the
        // deferral is the feature that decides the arithmetic.
        assertThat(projector.supports(CaseFixtures.shaped(ScheduleShape.STRUCTURED, 24, RateType.FIXED)
            .withMoratorium(MORATORIUM, true))).isTrue();
    }

    @Test
    @DisplayName("the carrying amount at inception is unaffected by capitalisation")
    void carryingAmountAtInceptionIsUnchanged() {
        // Capitalisation happens after initial recognition, so day one is still the net
        // cash flow at inception. The fee is 5,000 of income against 1,000,000 advanced
        // whether or not the first six months are deferred.
        BigDecimal expected = bd("995000.00");

        assertThat(projector.project(capitalising(), case1Fees()).initialCarryingAmount().amount())
            .isEqualByComparingTo(expected);
        assertThat(projector.project(servicing(), case1Fees()).initialCarryingAmount().amount())
            .isEqualByComparingTo(expected);
        assertThat(projector.project(capitalising(), case1Fees())
            .initialRecognitionCheck().satisfied()).isTrue();
    }
}
