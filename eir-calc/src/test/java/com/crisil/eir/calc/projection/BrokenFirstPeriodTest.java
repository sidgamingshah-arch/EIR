package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A broken first period: disbursement to first due date is not a whole month.
 *
 * <p>The near-universal case in Indian retail lending — a loan disbursed on the 17th
 * with instalments due on the 5th — and the case where the engine's two halves could
 * most plausibly disagree about time. A derived-schedule projector sizes the
 * instalment from the periodic rate and the period count, which assumes every period
 * is a whole one; the amortisation measures each period by the convention's own tau.
 * This class pins down what actually happens between them.
 */
class BrokenFirstPeriodTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 4, 17);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 6, 5);
    private static final TimeConvention.ActualDate ACTUAL =
        new TimeConvention.ActualDate(DayCountConvention.ACT_365F);

    private final BracketedNewtonSolver solver = new BracketedNewtonSolver();

    /** Case 1's economics, disbursed mid-month against a fixed 5th-of-month due date. */
    private static ContractTerms brokenFirstPeriod() {
        return ContractTerms.of(Money.inr("1000000"), Rate.monthly(bd("0.01")), 24, 12,
            DISBURSED, FIRST_DUE, DayCountConvention.ACT_365F, ScheduleShape.ANNUITY_EMI,
            RateType.FIXED);
    }

    @Test
    @DisplayName("a broken first period forces ActualDate, because the periodic-index precondition fails")
    void theConventionFallsThroughToActualDating() {
        ProjectionResult projection =
            new AnnuityProjector().project(brokenFirstPeriod(), CaseFixtures.case1Fees());

        // periodicIndexEligible requires every flow to sit exactly n months from the
        // anchor. The first instalment here sits 49 days out, so the precondition fails
        // and the selector falls through — which is the design working, not failing:
        // 3.10 makes actual dating the default and the fallback for exactly this reason.
        assertThat(projection.contractual().periodicIndexEligible(12))
            .as("49 days is not one month, so the uniform-period precondition cannot hold")
            .isFalse();
        assertThat(projection.recommendedConvention())
            .isInstanceOf(TimeConvention.ActualDate.class);
    }

    @Test
    @DisplayName("the instalment is sized without the day count, and the engine stays internally consistent anyway")
    void theTwoHalvesDisagreeAboutTimeWithoutBreakingAnything() {
        ContractTerms terms = brokenFirstPeriod();
        ProjectionResult projection =
            new AnnuityProjector().project(terms, CaseFixtures.case1Fees());

        // The instalment is the equal-period annuity: the projector never consults the
        // day count, so a 49-day first period is billed the same instalment as a 30-day
        // one. That is deliberate rather than overlooked — it is what a lender bills, and
        // a lender charges the broken stub separately as pre-EMI interest. ADR-0004 makes
        // the engine an overlay over what was billed, not a re-derivation of it.
        assertThat(new AnnuityProjector().billedInstalment(terms).amount())
            .as("the same annuity the whole-period case produces")
            .isEqualByComparingTo(
                new AnnuityProjector().billedInstalment(CaseFixtures.case1()).amount());

        SolveResult solved = solver.solve(SolveRequest.of(projection.expected(),
            projection.initialCarryingAmount(), projection.recommendedConvention(),
            terms.periodicRate()));
        assertThat(solved.status()).isEqualTo(SolveStatus.SOLVED);

        // And the halves do not in fact disagree about anything the ledger can see. The
        // solver discounts over day-counted tau; the roll-forward accretes over the same
        // day-counted tau, taken from the same convention; so the roll-forward reverses
        // exactly the discount the solve applied and TR-1 closes at zero. A vector whose
        // first period is longer than the rest is not an inconsistency — it is a vector,
        // and the EIR is whatever discounts it to the carrying amount.
        AmortisationResult schedule = AmortisationEngine.eirLeg(projection.initialCarryingAmount(),
            solved.rateOrThrow(), projection.expected(), projection.recommendedConvention());
        assertThat(schedule.invariants())
            .anyMatch(result -> result.id() == InvariantId.TR_1 && result.satisfied());
        assertThat(schedule.terminalBalance().atPresentationScale().isZero()).isTrue();
        assertThat(projection.initialRecognitionCheck().satisfied()).isTrue();

        // The first period accretes over more than one twelfth of a year, and the engine
        // charges for it. That is the day count reaching the ledger through the
        // convention, which is the only route it needs.
        BigDecimal firstExponent = schedule.row(1).accrualExponent();
        assertThat(firstExponent)
            .as("49 days on ACT/365F, as a fraction of a year")
            .isEqualByComparingTo(bd("49").divide(bd("365"), Precision.WORKING));
        assertThat(schedule.row(1).eirInterest().amount())
            .as("a 49-day stub accrues more than a 31-day one would")
            .isGreaterThan(schedule.row(2).eirInterest().amount());
    }

    @Test
    @DisplayName("INV-2 on a non-uniform schedule: the fee lifts the yield, the par gap exceeds it, and a negative spread is correct")
    void invariantTwoOrdersByTheParGapOnANonUniformSchedule() {
        ContractTerms terms = brokenFirstPeriod();
        ProjectionResult withFee =
            new AnnuityProjector().project(terms, CaseFixtures.case1Fees());
        ProjectionResult withoutFee = new AnnuityProjector().project(terms, List.of());

        // Under ActualDate the solved rate is annual effective and periodsPerYear is 1, so
        // no annualisation round-trip enters the amortisation.
        Rate eir = solve(withFee, terms);
        Rate noFeeYield = solve(withoutFee, terms);
        assertThat(eir.periodsPerYear()).isEqualTo(1);

        // The fee did what a fee received must do: it lifted the yield, by 53.3 basis
        // points, measured against the yield THIS billed schedule produces on its own.
        // That is the economic content of INV-2 and it holds.
        assertThat(noFeeYield.effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .as("the same schedule, no fee")
            .isEqualByComparingTo(bd("0.120209"));
        assertThat(eir.effectiveAnnual().setScale(6, RoundingMode.HALF_UP))
            .as("the same schedule, 5,000 of net fee received")
            .isEqualByComparingTo(bd("0.125544"));
        assertThat(eir.effectiveAnnual())
            .as("INV-2's economic claim, against the right baseline")
            .isGreaterThan(noFeeYield.effectiveAnnual());

        // And INV-2 now agrees, because its baseline is the par gap rather than the naive
        // annualised coupon. This test used to assert the OPPOSITE — that a sound
        // fee-received contract was reported as an INV-2 breach — because the check compared
        // the EIR to (1.01)^12 - 1 = 12.682503%, which annualises the quoted periodic rate as
        // though every period were a whole month. Stretch the schedule with a 49-day first
        // period and this contract's own contractual-only yield is 12.020935%; the EIR lands
        // 12.8 bp BELOW the naive figure and the ordering read backwards.
        //
        // G = par - PV(billed flows at the contractual rate) = 6,192.66 here, against
        // F = 5,000, so F - G = -1,192.66 and a negative spread is the arithmetically correct
        // answer. sign(EIR - coupon) = sign(F - G) holds, and the invariant passes.
        // 6,192.66 is a reference figure, computed independently as a direct P - PV. The
        // engine's own derivation — the contractual leg's terminal balance discounted over
        // the accumulated tau — is asserted against it here, because the two agreeing is what
        // licenses reusing the leg instead of solving a second time.
        Money parGap = Money.inr("6192.66");
        TimeConvention convention = withFee.recommendedConvention();
        Rate couponUnderConvention =
            ConventionSelector.rateUnder(convention, terms.contractualRate());
        AmortisationResult contractualLeg = AmortisationEngine.contractualLeg(
            terms.principal(), couponUnderConvention, withFee.contractual(), convention);
        assertThat(TwoLegResult.parGap(contractualLeg, couponUnderConvention)
            .atPresentationScale())
            .as("the engine's par gap against the independently computed one")
            .isEqualTo(parGap);

        InvariantResult ordering = InvariantChecks.feeSignOrdering(
            eir, terms.contractualRate(), Money.inr("5000"), parGap);
        assertThat(ordering.satisfied())
            .as("a sound fee-received contract, now reported as sound: %s", ordering.detail())
            .isTrue();

        // The gap the old baseline produced, kept as the measurement that motivated the fix.
        // It is over a hundred thousand times the resolvable band in money terms, so no
        // widening of a tolerance could ever have absorbed it — the baseline had to change.
        BigDecimal spreadAgainstNaiveCoupon = terms.contractualRate().effectiveAnnual()
            .subtract(eir.effectiveAnnual());
        assertThat(spreadAgainstNaiveCoupon.setScale(6, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("0.001281"));
        assertThat(parGap.minus(Money.inr("5000")).abs())
            .isGreaterThan(InvariantChecks.ORDERING_EPSILON.times(bd("1000")));

        // Assuming the gap away is what the old check did on every non-par schedule, and it
        // is asserted here so the correction cannot be quietly reverted.
        assertThat(InvariantChecks.feeSignOrdering(
            eir, terms.contractualRate(), Money.inr("5000"), Money.zero(Money.INR)).satisfied())
            .as("the old baseline, on the same sound contract")
            .isFalse();

        // RateOrderingPropertiesTest states the same law as a property — "the fee moves the
        // yield away from the yield the same billed schedule produces on its own" — and
        // generates only uniform-period contracts, which is why that property passed
        // throughout while the invariant it duplicates was wrong here.
    }

    private Rate solve(ProjectionResult projection, ContractTerms terms) {
        SolveResult solved = solver.solve(SolveRequest.of(projection.expected(),
            projection.initialCarryingAmount(), projection.recommendedConvention(),
            terms.periodicRate()));
        assertThat(solved.status()).isEqualTo(SolveStatus.SOLVED);
        return solved.rateOrThrow();
    }

    @Test
    @DisplayName("BrokenPeriodAccrual computes the same accretion the engine already applies")
    void theStandaloneHelperAgreesWithTheEngine() {
        // Why BrokenPeriodAccrual has no production caller: it is not a missing piece, it
        // is a duplicate one. AmortisationEngine.accretion(rate, deltaTau) with deltaTau
        // from TimeConvention.tau is the same computation — (1+R)^f - 1 on the same
        // fractional-power routine — reached through the convention rather than through a
        // second day-count call. Two routes to one figure is how they come to disagree, so
        // the ledger uses one, and the helper stands as the specification's 5.3 stated in
        // isolation and independently tested.
        ContractTerms terms = brokenFirstPeriod();
        ProjectionResult projection =
            new AnnuityProjector().project(terms, CaseFixtures.case1Fees());
        SolveResult solved = solver.solve(SolveRequest.of(projection.expected(),
            projection.initialCarryingAmount(), projection.recommendedConvention(),
            terms.periodicRate()));
        AmortisationResult schedule = AmortisationEngine.eirLeg(projection.initialCarryingAmount(),
            solved.rateOrThrow(), projection.expected(), projection.recommendedConvention());

        Money viaHelper = com.crisil.eir.calc.amort.BrokenPeriodAccrual.interest(
            projection.initialCarryingAmount(), solved.rateOrThrow().effectiveAnnual(),
            schedule.row(1).accrualExponent());

        assertThat(viaHelper.amount())
            .as("the standalone 5.3 form and the ledger's own accretion are one computation")
            .isEqualByComparingTo(schedule.row(1).eirInterest().amount());
    }
}
