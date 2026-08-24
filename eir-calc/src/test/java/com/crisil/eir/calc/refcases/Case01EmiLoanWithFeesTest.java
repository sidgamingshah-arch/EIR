package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.CASE1_EMI;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.NET_INTEGRAL_FEE;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.nominalAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.amort.TwoLegRow;
import com.crisil.eir.calc.projection.AnnuityProjector;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 1 — fixed-rate EMI term loan with integral fees.
 *
 * <p>The baseline. It establishes the EIR, the two-leg reconciliation and the fee
 * amortisation profile, and the other eight cases either continue it or are read
 * against it. Every figure asserted here is quoted from
 * {@code docs/reference-cases/case-01-emi-loan-with-fees.md}.
 *
 * <p>The case is a merge gate, so the assertions are stated at the granularity the
 * fixture publishes: the rate to eight places of percent, every money figure at
 * presentation scale, and the roll-forward row by row rather than by its totals. A
 * total can be right while every row inside it is wrong, which is exactly the
 * failure mode reference case 9 exists to name.
 */
class Case01EmiLoanWithFeesTest {

    private final ReferenceCaseFixtures.Baseline case1 = baseline();

    // ------------------------------------------------------------- derived inputs

    @Test
    @DisplayName("the EMI is the annuity 47,073.472223, billed at 47,073.47")
    void theBilledInstalmentIsTheAnnuityRoundedToPaise() {
        AnnuityProjector projector = new AnnuityProjector();

        assertThat(projector.annuityInstalment(case1.terms()).amount().setScale(6, RoundingMode.HALF_UP))
            .as("unrounded annuity payment")
            .isEqualByComparingTo(bd("47073.472223"));
        assertThat(paise(projector.billedInstalment(case1.terms())))
            .as("EMI as billed")
            .isEqualByComparingTo(bd("47073.47"));
        assertThat(paise(CASE1_EMI))
            .as("the fixture's EMI input is the figure Case 1 derives")
            .isEqualByComparingTo(paise(projector.billedInstalment(case1.terms())));
    }

    @Test
    @DisplayName("IC-1: initial gross carrying amount 995,000.00 equals the net cash flow at inception")
    void initialCarryingAmountIsTheNetCashFlowAtInception() {
        ProjectionResult projection = case1.projection();

        assertThat(paise(projection.initialCarryingAmount()))
            .as("GCA at initial recognition: 1,000,000 advanced less the 5,000 net integral fee")
            .isEqualByComparingTo(bd("995000.00"));
        // Two independent routes to one number. The projector derived the carrying amount
        // from the contract terms; the vector carries the cash. Where they disagree either
        // a fee is misclassified or a non-cash item has entered the vector.
        assertThat(paise(projection.netCashAtInception()))
            .as("net cash outflow at inception, holder's sign")
            .isEqualByComparingTo(bd("-995000.00"));
        assertThat(projection.initialRecognitionCheck().id()).isEqualTo(InvariantId.IC_1);
        assertThat(projection.initialRecognitionCheck().satisfied()).isTrue();
        assertThat(projection.allInvariantsSatisfied()).isTrue();
    }

    // -------------------------------------------------------------------- the rate

    @Test
    @DisplayName("EIR 1.04214918% per month, 12.505790% nominal p.a., 13.248094% effective p.a.")
    void theSolvedRateIsTheFixturesRate() {
        assertThat(case1.solve().status()).isEqualTo(SolveStatus.SOLVED);

        assertThat(periodicPercent(case1.eir()))
            .as("EIR per month")
            .isEqualByComparingTo(bd("1.04214918"));
        assertThat(nominalAnnualPercent(case1.eir()))
            .as("EIR nominal p.a. (x12)")
            .isEqualByComparingTo(bd("12.505790"));
        assertThat(effectiveAnnualPercent(case1.eir()))
            .as("EIR effective p.a.")
            .isEqualByComparingTo(bd("13.248094"));
    }

    @Test
    @DisplayName("the contractual effective rate is 12.682503% p.a. and the EIR stands 56.6bp above it")
    void theSpreadOverContractualIsFiftySixPointSixBasisPoints() {
        assertThat(effectiveAnnualPercent(ONE_PERCENT_MONTHLY))
            .as("contractual effective p.a. — 1% a month compounded, not 12%")
            .isEqualByComparingTo(bd("12.682503"));

        BigDecimal spreadBps =
            case1.eir().effectiveAnnualBps().subtract(ONE_PERCENT_MONTHLY.effectiveAnnualBps());
        assertThat(spreadBps.setScale(4, RoundingMode.HALF_UP))
            .as("13.248094% less 12.682503% expressed in basis points")
            .isEqualByComparingTo(bd("56.5591"));
        assertThat(spreadBps.setScale(1, RoundingMode.HALF_UP))
            .as("the fixture's headline: the 5,000 net fee lifts reported yield 56.6 basis points")
            .isEqualByComparingTo(bd("56.6"));
    }

    @Test
    @DisplayName("the residual at the stored 12dp rate is 2.85e-6, which is half the rounding floor and 3,504x inside a paisa")
    void theStoredRateReproducesTheTarget() {
        // The published rate must reproduce the published amortisation, so what matters is
        // the residual at the *stored* twelve-place rate rather than at the raw solved
        // value. At the raw root |f| is 7.4e-33; rounding the rate to twelve places moves
        // it by 2.41e-13 and brings the residual up to 2.85e-6. The fixture prints the
        // rate as 0E-12 and the residual is nil at presentation scale.
        BigDecimal residual = case1.solve().residualAtStoredRate();
        assertThat(residual.abs())
            .as("|f(r)| at the stored rate must not reach a paisa of the 995,000 target")
            .isLessThan(bd("0.01"));

        // That bound alone is 3,504 times the residual's actual magnitude, so on its own it
        // would pass through a solver regression three orders of magnitude wide. The bound
        // that means something is the rounding floor: rounding the rate to RATE_SCALE can
        // displace it by at most half a unit in the last place, so the residual it leaves
        // cannot exceed |f'| * 1e-12 / 2. Here that is 5.91e-6 against an actual 2.85e-6 —
        // the displacement came out at 2.41e-13 against a half-ULP of 5e-13, so the
        // residual sits at 48% of its ceiling, which is where a correctly rounded rate
        // should sit. A solve that stopped short of the root would breach this; a solve
        // that merely rounded would not.
        BigDecimal slope = Discounting.derivative(case1.eir().periodic(),
            case1.projection().expected(), case1.projection().recommendedConvention());
        BigDecimal roundingFloor = SolverTolerance.standard().attainableResidual(slope);
        assertThat(roundingFloor.round(new MathContext(3)))
            .as("the ceiling a 12dp rate can leave on this vector")
            .isEqualByComparingTo(bd("0.00000591"));
        assertThat(residual.abs())
            .as("the residual must sit under the rounding floor, not merely under a paisa")
            .isLessThan(roundingFloor);
        assertThat(residual.abs().round(new MathContext(3)))
            .as("and it is this figure; it moves only if the discounting does")
            .isEqualByComparingTo(bd("0.00000285"));

        // And the tolerance the solver was actually held to is nowhere near either: 1e-10
        // on a 995,000 target. It is met at working precision and missed by four orders of
        // magnitude once the rate is rounded for publication, which is the ordinary case
        // on every instrument in the book and is why SOLVED does not turn on it
        // (03 §4.2.1).
        assertThat(residual.abs())
            .as("the published residual misses tol_abs, as it does on every real instrument")
            .isGreaterThan(SolverTolerance.standard().absoluteFor(bd("995000")));
    }

    // -------------------------------------------------------- the roll-forward

    @Test
    @DisplayName("EIR-leg roll-forward: period 1 opens at 995,000.00, accretes 10,369.38, closes at 958,295.91")
    void periodOne() {
        assertRow(1, "995000.00", "10369.38", "47073.47", "958295.91");
    }

    @Test
    @DisplayName("period 2 accretes 9,986.87 and closes at 921,209.32")
    void periodTwo() {
        assertRow(2, "958295.91", "9986.87", "47073.47", "921209.32");
    }

    @Test
    @DisplayName("period 12 accretes 5,935.51 and closes at 528,407.32 — the figure five other cases start from")
    void periodTwelve() {
        assertRow(12, "569545.28", "5935.51", "47073.47", "528407.32");
    }

    @Test
    @DisplayName("period 23 accretes 966.02 and closes at 46,587.95")
    void periodTwentyThree() {
        assertRow(23, "92695.40", "966.02", "47073.47", "46587.95");
    }

    @Test
    @DisplayName("TR-1: period 24 accretes 485.52 and closes at exactly zero")
    void periodTwentyFourClosesAtZero() {
        assertRow(24, "46587.95", "485.52", "47073.47", "0.00");

        AmortisationResult eirLeg = case1.eirLeg();
        assertThat(eirLeg.periods()).isEqualTo(24);
        assertThat(paise(eirLeg.terminalBalance()))
            .as("terminal EIR-leg carrying amount — invariant TR-1")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(satisfied(eirLeg, InvariantId.TR_1))
            .as("TR-1 must be asserted, not merely happen to hold")
            .isTrue();
        assertThat(eirLeg.isClean()).isTrue();
    }

    @Test
    @DisplayName("every row of the schedule rolls forward and the 24 rows chain without a break")
    void everyRowRollsForward() {
        AmortisationResult eirLeg = case1.eirLeg();
        Money previousClosing = eirLeg.openingGca();
        for (AmortisationRow row : eirLeg.rows()) {
            assertThat(row.openingGca())
                .as("period %d opens where period %d closed", row.period(), row.period() - 1)
                .isEqualTo(previousClosing);
            assertThat(row.wholePeriod())
                .as("period %d is a whole compounding period under monthly indexing", row.period())
                .isTrue();
            assertThat(paise(row.cashReceived()))
                .as("period %d bills the EMI", row.period())
                .isEqualByComparingTo(bd("47073.47"));
            previousClosing = row.closingGca();
        }
    }

    // --------------------------------------------------- two-leg reconciliation

    @Test
    @DisplayName("INV-1: 134,763.28 of EIR interest is 129,763.28 contractual plus the 5,000.00 net fee")
    void lifetimeInterestTiesToContractualPlusTheNetFee() {
        TwoLegResult twoLeg = case1.twoLeg();

        assertThat(paise(twoLeg.totalEirInterest()))
            .as("total EIR interest recognised")
            .isEqualByComparingTo(bd("134763.28"));
        assertThat(paise(twoLeg.contractualInterestBilled()))
            .as("total contractual interest on the billed flows")
            .isEqualByComparingTo(bd("129763.28"));
        assertThat(paise(twoLeg.netFeeRecognised()))
            .as("difference — the net integral fee, recognised over the life")
            .isEqualByComparingTo(bd("5000.00"));
        assertThat(paise(twoLeg.netIntegralFee()))
            .isEqualByComparingTo(bd("5000.00"));
        assertThat(paise(twoLeg.totalCashReceived()))
            .as("24 EMIs of 47,073.47")
            .isEqualByComparingTo(bd("1129763.28"));

        // INV-1 read as an identity rather than as a subtraction: the EIR method changes
        // the timing of recognition, never the total.
        assertThat(satisfied(twoLeg, InvariantId.INV_1)).isTrue();
        assertThat(twoLeg.isClean())
            .as("breaches: %s", twoLeg.breaches())
            .isTrue();
    }

    @Test
    @DisplayName("INV-2: the EIR exceeds the contractual rate because the net fee is income")
    void theFeeSignOrdersTheTwoRates() {
        assertThat(case1.eir().effectiveAnnual())
            .as("the asset is recorded below par and must accrete back up")
            .isGreaterThan(ONE_PERCENT_MONTHLY.effectiveAnnual());
        assertThat(satisfied(case1.twoLeg(), InvariantId.INV_2)).isTrue();
    }

    @Test
    @DisplayName("fee amortisation is front-loaded: 369.38 in month 1, 223.74 in month 12, 19.44 in month 24")
    void thePerPeriodFeeAmortisationProfile() {
        assertFeeRow(1, "10369.38", "10000.00", "369.38", "4630.62");
        assertFeeRow(2, "9986.87", "9629.27", "357.61", "4273.01");
        assertFeeRow(3, "9600.38", "9254.82", "345.55", "3927.46");
        assertFeeRow(4, "9209.85", "8876.64", "333.21", "3594.24");
        assertFeeRow(5, "8815.25", "8494.67", "320.59", "3273.66");
        assertFeeRow(6, "8416.55", "8108.88", "307.67", "2965.99");
        assertFeeRow(12, "5935.51", "5711.77", "223.74", "1408.29");
        assertFeeRow(18, "3295.24", "3167.20", "128.05", "394.79");
        assertFeeRow(23, "966.02", "927.53", "38.49", "19.50");
        assertFeeRow(24, "485.52", "466.07", "19.44", "0.06");
    }

    @Test
    @DisplayName("straight-lining the same fee would recognise 208.33 every month — the difference is the method")
    void straightLineIsReportedForComparisonAndNeverUsed() {
        assertThat(paise(case1.twoLeg().straightLineFeePerPeriod()))
            .as("5,000.00 over 24 periods")
            .isEqualByComparingTo(bd("208.33"));
        // Front-loaded because the balance the fee accretes on is largest early. Month 1
        // recognises 1.77x the straight line and month 24 recognises 0.09x of it.
        assertThat(paise(case1.twoLeg().rows().getFirst().feeAmortised()))
            .isGreaterThan(bd("208.33"));
        assertThat(paise(case1.twoLeg().rows().getLast().feeAmortised()))
            .isLessThan(bd("208.33"));
    }

    @Test
    @DisplayName("INV-4: the month-12 unamortised fee 1,408.29 is the gap between the two legs")
    void theUnamortisedFeeIsTheLegDifference() {
        TwoLegResult twoLeg = case1.twoLeg();
        TwoLegRow month12 = twoLeg.rows().get(11);

        assertThat(paise(month12.contractualCarryingAmount()))
            .as("contractual outstanding principal at month 12")
            .isEqualByComparingTo(bd("529815.61"));
        assertThat(paise(month12.eirCarryingAmount()))
            .as("EIR gross carrying amount at month 12")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(paise(twoLeg.presentedUnamortisedFeeAt(12)))
            .as("529,815.61 less 528,407.32 — the figure Cases 2 and 8 recover independently")
            .isEqualByComparingTo(bd("1408.29"));
        assertThat(satisfied(twoLeg, InvariantId.INV_4)).isTrue();
    }

    // ---------------------------------------------------- the contractual residue

    @Test
    @DisplayName("the billed EMI leaves 0.059969 of residue on the contractual leg and none on the EIR leg")
    void theContractualLegCarriesTheBilledScheduleResidue() {
        // 47,073.47 billed against a true annuity payment of 47,073.472223: a 0.002223
        // monthly shortfall that compounds to 0.059969 over 24 periods. Real, expected,
        // and resolved by the rounding-residue policy — preferably LMS_AUTHORITATIVE,
        // which consumes the schedule the core banking system actually billed.
        assertThat(case1.contractualLeg().terminalBalance().amount().setScale(6, RoundingMode.HALF_UP))
            .as("uncollected residue on the contractual leg at period 24")
            .isEqualByComparingTo(bd("0.059969"));
        assertThat(paise(case1.twoLeg().contractualResidue()))
            .as("the 0.06 standing in the unamortised-fee column at period 24")
            .isEqualByComparingTo(bd("0.06"));

        // The EIR leg is unaffected: it was solved against these same billed flows, so it
        // amortises to exactly zero over them.
        assertThat(paise(case1.eirLeg().terminalBalance()))
            .as("the residue is a contractual-leg phenomenon and must not leak into the EIR leg")
            .isEqualByComparingTo(bd("0.00"));

        // Accrued basis and billed basis differ by exactly that residue: 4,999.94 against
        // 5,000.00. Both are right; a reconciliation that mixes them is short by 0.06.
        assertThat(paise(case1.twoLeg().totalFeeAmortisedOnAccruedBasis()))
            .isEqualByComparingTo(bd("4999.94"));
    }

    @Test
    @DisplayName("the reported interest total is the working-precision sum, within a paisa of its own column")
    void theInterestColumnResidueIsAtMostOnePaisa() {
        AmortisationResult eirLeg = case1.eirLeg();
        assertThat(paise(eirLeg.presentedTotalInterest()))
            .isEqualByComparingTo(bd("134763.28"));
        assertThat(paise(eirLeg.interestColumnResidue()).abs())
            .as("a rounding line, never netted into a balance")
            .isLessThanOrEqualTo(bd("0.01"));
    }

    @Test
    @DisplayName("the EIR is persisted as a Rate at storage precision")
    void theRateIsStoredAtTwelvePlaces() {
        Rate eir = case1.eir();
        assertThat(eir.periodic().scale())
            .as("a rate a ledger rolls forward with must be reproducible from the persisted figure")
            .isEqualTo(12);
        assertThat(eir.periodsPerYear()).isEqualTo(12);
    }

    // ------------------------------------------------------------------ helpers

    private void assertRow(int period, String opening, String interest, String cash, String closing) {
        AmortisationRow row = case1.eirLeg().row(period);
        assertThat(paise(row.openingGca())).as("period %d opening GCA", period)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("period %d EIR interest", period)
            .isEqualByComparingTo(bd(interest));
        assertThat(paise(row.cashReceived())).as("period %d cash received", period)
            .isEqualByComparingTo(bd(cash));
        assertThat(paise(row.closingGca())).as("period %d closing GCA", period)
            .isEqualByComparingTo(bd(closing));
    }

    private void assertFeeRow(
        int period, String eirInterest, String contractualInterest, String fee, String carriedForward) {
        TwoLegRow row = case1.twoLeg().rows().get(period - 1);
        assertThat(row.period()).isEqualTo(period);
        assertThat(paise(row.eirInterest())).as("period %d EIR interest", period)
            .isEqualByComparingTo(bd(eirInterest));
        assertThat(paise(row.contractualInterest())).as("period %d contractual interest", period)
            .isEqualByComparingTo(bd(contractualInterest));
        assertThat(paise(row.feeAmortised())).as("period %d fee amortised", period)
            .isEqualByComparingTo(bd(fee));
        assertThat(paise(row.unamortisedFee())).as("period %d unamortised fee c/f", period)
            .isEqualByComparingTo(bd(carriedForward));
    }

    private static boolean satisfied(AmortisationResult result, InvariantId id) {
        return satisfied(result.invariants(), id);
    }

    private static boolean satisfied(TwoLegResult result, InvariantId id) {
        return satisfied(result.invariants(), id);
    }

    private static boolean satisfied(List<InvariantResult> results, InvariantId id) {
        return results.stream().anyMatch(result -> result.id() == id && result.satisfied());
    }
}
