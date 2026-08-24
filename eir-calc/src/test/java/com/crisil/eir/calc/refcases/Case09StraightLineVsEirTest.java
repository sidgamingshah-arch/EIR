package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ANNUAL;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.percentChange;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.DiscountInstrumentProjector;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 9 — straight-line versus EIR on a long zero-coupon: the wedge.
 *
 * <p>A 15-year zero-coupon bond, 1,000,000 of face at an 8% yield, priced at
 * 315,241.70. The whole return is the 684,758.30 of discount to accrete.
 *
 * <p><b>The life totals tie to the paisa and every single year is wrong.</b> That
 * combination is the point of the case: an error that nets to zero over fifteen
 * years is invisible to any control that looks only at cumulative figures, and
 * material in every individual reporting period — front-loading income in the early
 * years and starving the later ones. Straight-line overstates year one by 81.0% and
 * understates year fifteen by 38.4%.
 *
 * <p>It is also the documented failure mode from the Cambodian CIFRS 9 transition,
 * and the argument that makes selective EIR adoption on the investment book
 * indefensible: the Tier 3 equivalence test cannot be passed on a zero-coupon at
 * any tenor, because the error is not small at any horizon.
 *
 * <p>Figures from {@code docs/reference-cases/case-09-straight-line-vs-eir.md}.
 */
class Case09StraightLineVsEirTest {

    private static final int YEARS = 15;

    private final DiscountInstrumentProjector projector = new DiscountInstrumentProjector();

    /**
     * The bond: face at maturity, an 8% annual yield, fifteen annual periods.
     *
     * <p>{@code principal} on a {@link ScheduleShape#DISCOUNT_INSTRUMENT} is the
     * <em>face value at maturity</em> and the price is derived, which is how the
     * market quotes it. 30/360 bond basis makes each year exactly one period, so
     * the accretion table is the compounding table and nothing is hidden in a day
     * count.
     */
    private ContractTerms bond() {
        return ContractTerms.of(Money.inr("1000000"), Rate.periodic(bd("0.08"), 1), YEARS, 1,
            DISBURSEMENT, DISBURSEMENT.plusYears(1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.DISCOUNT_INSTRUMENT, RateType.FIXED);
    }

    /**
     * The instrument as an annual reporting schedule.
     *
     * <p>A zero-coupon has one contractual flow, so the projected vector has one
     * accrual boundary and the engine would produce a single fifteen-year row. The
     * fixture publishes a year-by-year table, so the vector is restated with an
     * annual boundary carrying no cash — which is what a reporting year <em>is</em>
     * on an instrument that pays nothing until maturity — and the redemption at
     * year fifteen. Discounting is indifferent to the added zeroes, so the rate
     * solved over this vector is the rate solved over the projected one; what the
     * boundaries buy is the fifteen rows the fixture asserts.
     */
    private FlowVector annualReportingVector() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, projector.issuePrice(bond()).negate(),
            FlowKind.DISBURSEMENT));
        for (int year = 1; year < YEARS; year++) {
            flows.add(CashFlow.of(DISBURSEMENT.plusYears(year), year, Money.zero(Money.INR),
                FlowKind.PRINCIPAL));
        }
        flows.add(CashFlow.of(DISBURSEMENT.plusYears(YEARS), YEARS,
            projector.faceValue(bond()), FlowKind.PRINCIPAL));
        return FlowVector.of(DISBURSEMENT, Money.INR, flows);
    }

    private SolveResult solve() {
        return new BracketedNewtonSolver().solve(SolveRequest.atInception(
            annualReportingVector(), ANNUAL, bd("0.08")));
    }

    private AmortisationResult eirAccretion() {
        return AmortisationEngine.eirLeg(projector.issuePrice(bond()), solve().rateOrThrow(),
            annualReportingVector(), ANNUAL);
    }

    /** {@code discount / 15} — what a straight line would recognise every year. */
    private Money straightLinePerYear() {
        return projector.discountToAccrete(bond()).dividedBy(BigDecimal.valueOf(YEARS));
    }

    @Test
    @DisplayName("the instrument: 1,000,000 of face at 8% for 15 years prices at 315,241.70")
    void theInstrument() {
        ContractTerms bond = bond();

        assertThat(paise(projector.faceValue(bond)))
            .as("face value at maturity")
            .isEqualByComparingTo(bd("1000000.00"));
        assertThat(paise(projector.issuePrice(bond)))
            .as("purchase price — face discounted at the contractual yield over 15 years")
            .isEqualByComparingTo(bd("315241.70"));
        assertThat(paise(projector.discountToAccrete(bond)))
            .as("total discount to accrete — the entire return")
            .isEqualByComparingTo(bd("684758.30"));
        assertThat(paise(straightLinePerYear()))
            .as("straight-line accretion per year")
            .isEqualByComparingTo(bd("45650.55"));
    }

    @Test
    @DisplayName("the yield recovers at 8% and the schedule runs 15 annual rows to zero")
    void theRateAndTheShapeOfTheSchedule() {
        SolveResult result = solve();
        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rateOrThrow().effectiveAnnual().setScale(6, java.math.RoundingMode.HALF_UP))
            .as("the yield the price was struck at")
            .isEqualByComparingTo(bd("0.080000"));

        AmortisationResult accretion = eirAccretion();
        assertThat(accretion.periods()).isEqualTo(YEARS);
        assertThat(paise(accretion.terminalBalance()))
            .as("TR-1: terminal carrying amount 1,000,000.00 against face 1,000,000.00")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(accretion.invariants())
            .anyMatch(invariant -> invariant.id() == InvariantId.TR_1 && invariant.satisfied());
        assertThat(paise(accretion.row(YEARS).openingGca().plus(accretion.row(YEARS).eirInterest())))
            .as("the closing carrying amount before the redemption is applied")
            .isEqualByComparingTo(bd("1000000.00"));
    }

    /**
     * The fixture's year-by-year table.
     *
     * <p>The fixture prints the price as 315,241.70 and accretes the exact present
     * value 315,241.704965890215858589382597 behind it. Those are the same number
     * presented and unpresented, not two different prices: the price is derived from
     * the quoted 8% yield, so it is an intermediate and carries working precision
     * until a figure is persisted (1.2, 1.3). All fifteen openings, all fifteen
     * accretions and all fifteen overstatement figures reproduce from it, and the
     * instrument closes at exactly 1,000,000.00 against face.
     *
     * <p>Rounding the derived price first — which the engine used to do — moves eight
     * opening balances (years 4, 5, 6, 7, 9, 10, 11 and 15) and year 15's accretion
     * (74,074.08 against 74,074.07) by a paisa each, and leaves the bond redeeming at
     * 999,999.98, breaching TR-1 by two paise. That last consequence is what settles
     * it: a price that cannot recover the face value it was discounted from is an
     * artefact, not a better model of a traded instrument.
     */
    @Test
    @DisplayName("EIR accretion year by year: 25,219.34 in year 1 rising to 74,074.07 in year 15")
    void theYearByYearAccretion() {
        AmortisationResult accretion = eirAccretion();

        assertYear(accretion, 1, "315241.70", "25219.34");
        assertYear(accretion, 2, "340461.04", "27236.88");
        assertYear(accretion, 3, "367697.92", "29415.83");
        assertYear(accretion, 4, "397113.76", "31769.10");
        assertYear(accretion, 5, "428882.86", "34310.63");
        assertYear(accretion, 6, "463193.49", "37055.48");
        assertYear(accretion, 7, "500248.97", "40019.92");
        assertYear(accretion, 8, "540268.88", "43221.51");
        assertYear(accretion, 9, "583490.40", "46679.23");
        assertYear(accretion, 10, "630169.63", "50413.57");
        assertYear(accretion, 11, "680583.20", "54446.66");
        assertYear(accretion, 12, "735029.85", "58802.39");
        assertYear(accretion, 13, "793832.24", "63506.58");
        assertYear(accretion, 14, "857338.82", "68587.11");
        assertYear(accretion, 15, "925925.93", "74074.07");
    }

    @Test
    @DisplayName("the life totals are identical at 684,758.30 — which is what hides the error")
    void theLifeTotalsTie() {
        AmortisationResult accretion = eirAccretion();
        Money straightLineLifeTotal = straightLinePerYear().times(BigDecimal.valueOf(YEARS));

        assertThat(paise(accretion.presentedTotalInterest()))
            .as("EIR accretion over the life")
            .isEqualByComparingTo(bd("684758.30"));
        assertThat(paise(straightLineLifeTotal))
            .as("straight-line accretion over the life")
            .isEqualByComparingTo(bd("684758.30"));
        assertThat(paise(straightLineLifeTotal.minus(accretion.presentedTotalInterest())))
            .as("an error that nets to zero over fifteen years is invisible to any control "
                + "that looks only at cumulative figures")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("and every individual year diverges — the combination is the whole point")
    void everyYearDiverges() {
        AmortisationResult accretion = eirAccretion();
        Money straightLine = straightLinePerYear();

        for (AmortisationRow row : accretion.rows()) {
            assertThat(paise(row.eirInterest()))
                .as("year %d must not coincide with the straight line; a year that did would "
                    + "make the fixture's 'invisible over the life, material in every period' "
                    + "claim false", row.period())
                .isNotEqualByComparingTo(paise(straightLine));
        }
    }

    @Test
    @DisplayName("straight-line overstates year 1 by 81.0% and understates year 15 by 38.4%")
    void theTwoHeadlineErrors() {
        AmortisationResult accretion = eirAccretion();
        BigDecimal straightLine = paise(straightLinePerYear());

        assertThat(percentChange(straightLine, paise(accretion.row(1).eirInterest())))
            .as("45,650.55 against 25,219.34 of EIR accretion in year 1")
            .isEqualByComparingTo(bd("81.0"));
        assertThat(percentChange(straightLine, paise(accretion.row(YEARS).eirInterest())))
            .as("45,650.55 against 74,074.07 of EIR accretion in year 15")
            .isEqualByComparingTo(bd("-38.4"));
    }

    @Test
    @DisplayName("the crossover is year 9: EIR accretion first exceeds the straight line there")
    void theCrossover() {
        AmortisationResult accretion = eirAccretion();
        BigDecimal straightLine = paise(straightLinePerYear());

        assertThat(paise(accretion.row(8).eirInterest()))
            .as("year 8 is still overstated by the straight line — the fixture's +2,429.04")
            .isLessThan(straightLine);
        assertThat(paise(accretion.row(9).eirInterest()))
            .as("year 9 is the first understated year — the fixture's -1,028.68")
            .isGreaterThan(straightLine);

        int firstUnderstated = 0;
        for (AmortisationRow row : accretion.rows()) {
            if (paise(row.eirInterest()).compareTo(straightLine) > 0) {
                firstUnderstated = row.period();
                break;
            }
        }
        assertThat(firstUnderstated).isEqualTo(9);
    }

    @Test
    @DisplayName("the fixture's overstatement column: 20,431.22 in year 1 down to -28,423.52 in year 15")
    void theOverstatementColumn() {
        AmortisationResult accretion = eirAccretion();
        Money straightLine = straightLinePerYear();

        assertOverstatement(accretion, straightLine, 1, "20431.22");
        assertOverstatement(accretion, straightLine, 2, "18413.67");
        assertOverstatement(accretion, straightLine, 3, "16234.72");
        assertOverstatement(accretion, straightLine, 8, "2429.04");
        assertOverstatement(accretion, straightLine, 9, "-1028.68");
        assertOverstatement(accretion, straightLine, 14, "-22936.55");
        assertOverstatement(accretion, straightLine, 15, "-28423.52");
    }

    private void assertYear(AmortisationResult accretion, int year, String opening, String accreted) {
        AmortisationRow row = accretion.row(year);
        assertThat(paise(row.openingGca())).as("year %d opening carrying amount", year)
            .isEqualByComparingTo(bd(opening));
        assertThat(paise(row.eirInterest())).as("year %d EIR accretion", year)
            .isEqualByComparingTo(bd(accreted));
        assertThat(row.wholePeriod())
            .as("year %d is one whole annual period under 30/360", year)
            .isTrue();
    }

    private void assertOverstatement(
        AmortisationResult accretion, Money straightLine, int year, String expected) {
        assertThat(paise(straightLine.minus(accretion.row(year).eirInterest())))
            .as("year %d: straight line less EIR accretion", year)
            .isEqualByComparingTo(bd(expected));
    }
}
