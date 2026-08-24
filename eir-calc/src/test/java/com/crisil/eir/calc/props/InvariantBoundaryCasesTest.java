package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.projection.AnnuityProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The boundaries the sweep found, pinned as worked instruments.
 *
 * <p>Every property in this package that carries a precondition carries it because
 * of a case in this class. A precondition asserted without an exhibit is
 * indistinguishable from a property weakened until it passed, so each one is
 * exhibited: the instrument, the figure, and the reason the invariant as literally
 * stated cannot hold on it. None of these is a defect in the arithmetic. Three are
 * consequences of specification choices that are correct and have a stated cost —
 * the billed schedule is the lending system's, not the engine's (5.7); the
 * persisted rate carries twelve decimal places (1.4) — and one is a property of a
 * day-count convention used outside the period it is defined for.
 *
 * <p>These are the cases to reach for when someone asks why the sweep does not
 * simply assert section 9 verbatim.
 */
class InvariantBoundaryCasesTest {

    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);
    private static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /**
     * INV-2's equality limb cannot hold on a billed schedule, under either rounding
     * policy, and the policy chooses the sign of the failure.
     *
     * <p>The reference case 1 loan with no integral fee at all. INV-2 says the EIR
     * <em>equals</em> the contractual rate when the net fee is nil. It does not,
     * because the schedule the borrower is billed is not the schedule the
     * contractual rate prices:
     *
     * <ul>
     *   <li>Under {@code LMS_AUTHORITATIVE} the true annuity payment of 47,073.472223
     *       is billed as 47,073.47, the lender under-collects 0.059969 over 24 periods,
     *       and the schedule's own yield lands 5.3e-8 <em>below</em> the contractual
     *       rate.</li>
     *   <li>Under {@code FINAL_PERIOD_PLUG} the final instalment absorbs the residue
     *       and becomes 47,073.53, so the schedule over-collects at the end and its
     *       yield lands 2.7e-11 <em>above</em>.</li>
     * </ul>
     *
     * <p>Neither is a defect and neither is zero. What follows for the invariant is
     * that its equality limb is a statement about an unrounded schedule, and its two
     * ordering limbs need a fee large enough to dominate the rounding — which is the
     * precondition {@link RateOrderingPropertiesTest} states and
     * {@link Generators#materiallyFeeBearingPeriodicIndexContracts()} derives. A
     * production assertion of INV-2 as written would raise a control exception on
     * every zero-fee contract in the book.
     */
    @Test
    void invariantTwoCannotHoldWithNoFeeOnAnyBilledSchedule() {
        ContractTerms terms = caseOneTerms();
        BigDecimal underLmsAuthoritative = spreadOverContractual(
            ProjectorRegistry.standard(), terms, Money.zero(Money.INR));
        BigDecimal underFinalPeriodPlug = spreadOverContractual(
            ProjectorRegistry.standard().prepend(new AnnuityProjector(ResiduePolicy.FINAL_PERIOD_PLUG)),
            terms, Money.zero(Money.INR));

        assertThat(underLmsAuthoritative.signum())
            .as("billing the annuity rounded down yields fractionally less than the coupon")
            .isNegative();
        assertThat(underLmsAuthoritative.abs())
            .isBetween(new BigDecimal("1E-9"), new BigDecimal("1E-6"));
        assertThat(underFinalPeriodPlug.signum())
            .as("plugging the residue into the final instalment yields fractionally more")
            .isPositive();

        InvariantResult ordering = InvariantChecks.feeSignOrdering(
            solve(ProjectorRegistry.standard(), terms, List.of()), terms.contractualRate(),
            Money.zero(Money.INR));
        assertThat(ordering.satisfied())
            .as("INV-2 as literally stated, on a zero-fee contract: %s", ordering.detail())
            .isFalse();

        // One rupee of fee is already enough to restore the ordering on this instrument,
        // which is why the derived floor is stated in rupees against the period count
        // rather than as a percentage of principal.
        InvariantResult withOneRupee = InvariantChecks.feeSignOrdering(
            solve(ProjectorRegistry.standard(), terms, integralFee(Money.inr("1"))),
            terms.contractualRate(), Money.inr("1"));
        assertThat(withOneRupee.satisfied()).isTrue();
    }

    /**
     * TR-1 cannot hold at presentation scale once the compounded stored-rate rounding
     * exceeds half a paise — and the achievable minimum is not zero.
     *
     * <p>A million over 360 monthly periods at 36% per annum nominal. The EIR solves
     * to 3.0150793444% per month and the leg closes at 0.54 rather than nil. Moving
     * the stored rate by one unit in its twelfth decimal place moves that closing
     * balance to 1.99 or to −0.92: the grid of representable rates has a spacing
     * worth about a rupee and a half at this tenor, so no rate stored at twelve places
     * closes this leg at zero. The engine is not drifting — it is doing what 1.4
     * requires, which is to roll forward with the rate that was published.
     *
     * <p>Two consequences worth recording. TR-1 and INV-1 fail together and by
     * exactly the same amount, because the EIR-leg terminal balance <em>is</em> the
     * INV-1 deviation. And the Tier 1 tightened solver tolerance does not help: the
     * tolerance governs where the solver stops, and the residue is created after it
     * stops, by rounding the answer for storage. What would help is storing more
     * places for long-dated high-rate instruments, which is a specification decision
     * and not an engine one.
     */
    @Test
    void terminalResidueExceedsHalfAPaiseOnALongHighRateSchedule() {
        BigDecimal periodic = new BigDecimal("0.36").divide(new BigDecimal("12"), Precision.WORKING);
        ContractTerms terms = ContractTerms.of(Money.inr("1000000"), Rate.monthly(periodic), 360, 12,
            DISBURSEMENT, DISBURSEMENT.plusMonths(1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI, RateType.FIXED);
        Generators.Pipeline pipeline = Generators.run(
            new Generators.Contract(terms, integralFee(Money.inr("5000")), Money.inr("5000"),
                terms.principal()));

        Money terminal = pipeline.eirLeg().terminalBalance();
        assertThat(terminal.atPresentationScale().isZero())
            .as("TR-1 as literally stated, on a 30-year 36% schedule: terminal %s",
                terminal.atPresentationScale())
            .isFalse();
        assertThat(terminal.abs().amount())
            .as("and it is inside the bound the stored-rate policy implies")
            .isLessThanOrEqualTo(pipeline.storedRateTerminalBound().abs().amount());

        InvariantResult lifetimeInterest = pipeline.twoLeg().invariants().stream()
            .filter(result -> result.id() == InvariantId.INV_1)
            .findFirst()
            .orElseThrow();
        assertThat(lifetimeInterest.deviation().abs())
            .as("INV-1 carries the same deviation as TR-1, not a different one")
            .isEqualByComparingTo(terminal.atPresentationScale().amount().abs());

        for (String step : List.of("1E-12", "-1E-12")) {
            Rate perturbed = Rate.monthly(pipeline.eir().periodic().add(new BigDecimal(step)));
            AmortisationResult leg = AmortisationEngine.segment(
                pipeline.projection().initialCarryingAmount(), perturbed,
                pipeline.projection().expected(), pipeline.convention());
            assertThat(leg.presentedTerminalBalance().abs().amount())
                .as("one unit in the last place of the stored rate is worth more than a rupee here,"
                    + " so no representable rate closes this leg at zero (step %s)", step)
                .isGreaterThan(new BigDecimal("0.50"));
        }
    }

    /**
     * An up-front cost large enough to outweigh a small coupon over a short tenor
     * gives a negative EIR, and the interest column then rises towards zero.
     *
     * <p>10,000 advanced for two annual instalments at 0.5% per annum, against 500 of
     * integral cost paid. The asset is recorded at 10,500 and returns 10,075.06 in
     * cash, so the effective rate is −2.7104441406% and the ledger recognises negative
     * interest: −284.60 then −140.34. Both are correct — the entity paid more for the
     * exposure than the exposure returns — and both are why
     * {@link RateOrderingPropertiesTest} states the interest-monotonicity property in
     * the direction of the yield's sign rather than as a decline. The magnitude falls
     * throughout, which is the declining-balance method working exactly as it does on
     * a positive yield.
     */
    @Test
    void interestRisesTowardsZeroWhenAnUpFrontCostDrivesTheYieldNegative() {
        ContractTerms terms = ContractTerms.of(Money.inr("10000"),
            Rate.periodic(new BigDecimal("0.005"), 1), 2, 1, DISBURSEMENT,
            DISBURSEMENT.plusMonths(12), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI, RateType.FIXED);
        List<FeePosting> fees = List.of(FeePosting.paid("DSA_COMMISSION", Money.inr("500"),
            DISBURSEMENT, FeeClassification.INTEGRAL, "SELLING"));
        Generators.Pipeline pipeline = Generators.run(
            new Generators.Contract(terms, fees, Money.inr("-500"), terms.principal()));

        assertThat(pipeline.projection().initialCarryingAmount().atPresentationScale())
            .isEqualTo(Money.inr("10500.00"));
        assertThat(pipeline.eir().periodic().signum())
            .as("the yield is negative: %s", pipeline.eir().periodic().toPlainString())
            .isNegative();

        List<AmortisationRow> rows = pipeline.eirLeg().rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).eirInterest().isNegative()).isTrue();
        assertThat(rows.get(1).eirInterest().isNegative()).isTrue();
        assertThat(rows.get(1).eirInterest().compareTo(rows.get(0).eirInterest()))
            .as("interest rises towards zero: %s then %s",
                rows.get(0).eirInterest().atPresentationScale(),
                rows.get(1).eirInterest().atPresentationScale())
            .isPositive();
        assertThat(rows.get(1).eirInterest().abs().compareTo(rows.get(0).eirInterest().abs()))
            .as("while its magnitude falls, on a falling balance, as it should")
            .isNegative();
        assertThat(pipeline.eirLeg().presentedTerminalBalance().isZero())
            .as("TR-1 still closes the leg at zero")
            .isTrue();
    }

    /**
     * ACT/365L is not monotonic in the end date once the interval passes a year.
     *
     * <p>Its denominator is 366 where 29 February falls inside the interval and 365
     * otherwise, so admitting the leap day adds one day to the numerator and one to
     * the denominator. For an interval already longer than 365 days the denominator
     * wins: from 24 January 2023, four hundred days gives 1.095890410958904109589041096
     * and four hundred and one days gives 1.095628415300546448087431694 — a year
     * fraction that <em>falls</em> as the end date moves out.
     *
     * <p>This is a property of the convention, not of the implementation: ACT/365L is
     * defined for a coupon period, a coupon period is not longer than a year, and
     * inside that domain it is monotonic. It is worth pinning because the convention
     * is selectable per instrument, and selecting it for a multi-year discount factor
     * would produce a discount curve that is not monotone in time — which is the
     * kind of thing that shows up as an inexplicable reconciliation difference on one
     * instrument and nowhere else.
     */
    @Test
    void actualThreeSixtyFiveLeapIsNotMonotonicBeyondOneYear() {
        LocalDate start = LocalDate.of(2023, 1, 24);
        BigDecimal fourHundredDays =
            DayCountConvention.ACT_365L.yearFraction(start, LocalDate.of(2024, 2, 28));
        BigDecimal fourHundredAndOneDays =
            DayCountConvention.ACT_365L.yearFraction(start, LocalDate.of(2024, 2, 29));

        assertThat(fourHundredDays.toPlainString()).isEqualTo("1.095890410958904109589041096");
        assertThat(fourHundredAndOneDays)
            .as("the leap day moves the denominator to 366 and the fraction falls")
            .isLessThan(fourHundredDays);

        // Inside the convention's own domain the ordering is the expected one.
        assertThat(DayCountConvention.ACT_365L.yearFraction(start, start.plusDays(200)))
            .isLessThan(DayCountConvention.ACT_365L.yearFraction(start, start.plusDays(201)));
    }

    /**
     * Under actual dating a level receipt does not amortise monotonically: the
     * carrying amount zig-zags with the length of the accrual period.
     *
     * <p>An interest-only bullet, 51,538.45 at 12.96% per annum semi-annual on
     * ACT/360, drawn on 2 April 2028 against a first coupon on 5 October. The
     * accrual exponent alternates between 182/360 and 183/360, so the accretion
     * alternates between 6,657 and 6,693 against a level coupon of 6,679.38 — the
     * balance falls in a short period and rises in a long one, every period, all the
     * way to maturity.
     *
     * <p>Nothing is wrong. The vector failed the periodic-index precondition, so
     * period ordinals are not a valid measure of time on it, and interest accrues for
     * the fraction of a year that actually elapsed (5.3). What follows is that any
     * report, control or property that expects a monotone carrying amount has to say
     * "under periodic indexing" or be wrong on every actual-dated instrument in the
     * book — which is why
     * {@link ReconciliationSweepTest} asserts the single-hump property only there.
     */
    @Test
    void theCarryingAmountZigZagsUnderActualDatingWithLevelReceipts() {
        ContractTerms terms = ContractTerms.of(Money.inr("51538.45"),
            Rate.periodic(new BigDecimal("0.1296"), 2), 35, 2, LocalDate.of(2028, 4, 2),
            LocalDate.of(2028, 10, 5), DayCountConvention.ACT_360,
            ScheduleShape.INTEREST_ONLY_BULLET, RateType.FIXED);
        Generators.Pipeline pipeline = Generators.run(
            new Generators.Contract(terms, List.of(), Money.zero(Money.INR), terms.principal()));

        assertThat(pipeline.convention()).isInstanceOf(TimeConvention.ActualDate.class);
        List<AmortisationRow> rows = pipeline.eirLeg().rows();

        assertThat(rows.get(0).accrualExponent())
            .as("the first accrual runs 186 days over 360")
            .isNotEqualByComparingTo(rows.get(1).accrualExponent());
        assertThat(rows.get(1).closingGca().compareTo(rows.get(0).closingGca()))
            .as("a short period over-collects and the balance falls")
            .isNegative();
        assertThat(rows.get(2).closingGca().compareTo(rows.get(1).closingGca()))
            .as("the next, longer period under-collects and it rises again")
            .isPositive();
        assertThat(pipeline.unaccountedBreaches())
            .as("and the reconciliation still holds throughout")
            .isEmpty();
    }

    /**
     * INV-1 must not report a paise that is not there. The regression guard for the
     * double-rounding defect, kept as the worked instrument that exposed it.
     *
     * <p>A thirty-year quarterly zero-coupon bullet of 20,036,296.57 at 8.67% per
     * annum nominal with 492,892.89 of integral cost. The EIR leg closes at
     * −0.0032, comfortably inside half a paise, and TR-1 passes. Lifetime EIR
     * interest is 242,103,892.5032 and contractual interest plus the net fee is
     * 242,103,892.5063 — three thousandths of a paise apart, straddling a rounding
     * boundary. Rounded independently they present as 242,103,892.50 and
     * 242,103,892.51, and INV-1 used to fail by 0.01 on that alone: a control
     * exception blocking a period close on a 240-million-rupee interest total where
     * nothing is wrong, with no floor on how small the true difference could be.
     *
     * <p>{@code InvariantResult.ofMoney} now reduces the difference once, which is
     * the discipline the codebase already stated elsewhere in almost these words —
     * {@code TwoLegRow.presentedUnamortisedFee} differences the working balances
     * rather than the rounded ones, "because rounding the inputs and then subtracting
     * rounds twice" — and which the reference cases publish. The cross-check is
     * {@code TwoLegResult.netFeeRecognised()}, which rounds once and has always read
     * −492,892.89 exactly.
     */
    @Test
    void invariantOneDoesNotReportAPaiseThatIsNotThere() {
        ContractTerms terms = ContractTerms.of(Money.inr("20036296.57"),
            Rate.periodic(new BigDecimal("0.021675"), 4), 120, 4, LocalDate.of(2024, 1, 9),
            LocalDate.of(2024, 4, 26), DayCountConvention.THIRTY_E_360, ScheduleShape.BULLET,
            RateType.FIXED);
        List<FeePosting> fees = List.of(FeePosting.paid("DSA_COMMISSION", Money.inr("492892.89"),
            LocalDate.of(2024, 1, 9), FeeClassification.INTEGRAL, "SELLING"));
        Generators.Pipeline pipeline = Generators.run(
            new Generators.Contract(terms, fees, Money.inr("-492892.89"), terms.principal()));

        assertThat(pipeline.eirLeg().terminalBalance().abs().amount())
            .as("the leg closes well inside half a paise")
            .isLessThan(new BigDecimal("0.005"));
        assertThat(pipeline.eirLeg().presentedTerminalBalance().isZero())
            .as("so TR-1 passes")
            .isTrue();

        InvariantResult lifetimeInterest = pipeline.twoLeg().invariants().stream()
            .filter(result -> result.id() == InvariantId.INV_1)
            .findFirst()
            .orElseThrow();
        assertThat(lifetimeInterest.satisfied())
            .as("and INV-1 holds, because the difference is reduced once: %s",
                lifetimeInterest.detail())
            .isTrue();
        assertThat(lifetimeInterest.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(pipeline.unaccountedBreaches())
            .as("nothing anywhere in the reconciliation reports a breach on this contract")
            .isEmpty();

        assertThat(pipeline.twoLeg().netFeeRecognised().atPresentationScale())
            .as("the same statement, rounded once, is exact")
            .isEqualTo(Money.inr("-492892.89"));
    }

    /**
     * The defect CU-1 exists to catch, worked through: discounting a re-estimation at
     * a re-solved rate drives the catch-up to exactly zero.
     *
     * <p>Reference case 1's loan at month 12, carrying 528,407.32, with the remaining
     * twelve instalments revised down from 47,073.47 to 44,000. Discounted at the
     * original 1.0421491790% per month the restated balance produces a charge of
     * −34,500.20. Re-solve the rate over the revised flows instead — it drops to
     * −0.0118616909% per month — and the same restatement produces 0.00, because a
     * re-solved rate is by construction the rate that discounts the revised flows to
     * the balance already on the books.
     *
     * <p>That is what makes the defect so dangerous: it does not produce a wrong
     * catch-up, it produces no catch-up at all, and a B5.4.6 re-estimation silently
     * becomes a B5.4.5 reset with no figure anywhere to notice. CU-1 catches it with
     * one comparison, and here it does.
     */
    @Test
    void aCatchUpDiscountedAtAReSolvedRateAllButVanishes() {
        Generators.Pipeline baseline = Generators.run(new Generators.Contract(
            caseOneTerms(), integralFee(Money.inr("15000"), Money.inr("10000")),
            Money.inr("5000"), Money.inr("1000000")));
        Money carryingAmount = baseline.eirLeg().row(12).closingGca();
        LocalDate event = DISBURSEMENT.plusMonths(12);
        FlowVector revised = revisedFlows(event, Money.inr("44000"), 12);

        CatchUpResult atTheOriginalRate =
            CatchUpCalculator.restate(baseline.eir(), carryingAmount, revised, MONTHLY);
        SolveResult reSolve = new BracketedNewtonSolver().solve(
            SolveRequest.of(revised, carryingAmount, MONTHLY, baseline.eir().periodic()));
        CatchUpResult atTheReSolvedRate = CatchUpCalculator.restate(
            reSolve.rateOrThrow(), baseline.eir(), carryingAmount, revised, MONTHLY);

        assertThat(atTheOriginalRate.isCharge()).isTrue();
        assertThat(atTheOriginalRate.presentedCatchUp().abs().amount())
            .as("a real re-estimation moves a real number")
            .isGreaterThan(new BigDecimal("1000"));
        assertThat(atTheOriginalRate.breaches())
            .as("and it leaves the rate alone")
            .isEmpty();

        assertThat(atTheReSolvedRate.presentedCatchUp().isZero())
            .as("re-solving drives the catch-up to nil: %s",
                atTheReSolvedRate.presentedCatchUp())
            .isTrue();
        assertThat(atTheReSolvedRate.breaches())
            .as("CU-1 is the one figure that notices")
            .isNotEmpty();
        assertThat(atTheReSolvedRate.breaches().getFirst().id()).isEqualTo(InvariantId.CU_1);
    }

    // ------------------------------------------------------------------ helpers

    private static ContractTerms caseOneTerms() {
        return ContractTerms.of(Money.inr("1000000"), Rate.monthly(new BigDecimal("0.01")), 24, 12,
            DISBURSEMENT, DISBURSEMENT.plusMonths(1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI, RateType.FIXED);
    }

    private static List<FeePosting> integralFee(Money received) {
        return List.of(FeePosting.received("PROCESSING_FEE", received, DISBURSEMENT,
            FeeClassification.INTEGRAL));
    }

    private static List<FeePosting> integralFee(Money received, Money paid) {
        return List.of(
            FeePosting.received("PROCESSING_FEE", received, DISBURSEMENT, FeeClassification.INTEGRAL),
            FeePosting.paid("DSA_COMMISSION", paid, DISBURSEMENT, FeeClassification.INTEGRAL, "SELLING"));
    }

    private static Rate solve(ProjectorRegistry registry, ContractTerms terms, List<FeePosting> fees) {
        ProjectionResult projection = registry.project(terms, fees);
        return new BracketedNewtonSolver().solve(SolveRequest.of(projection.expected(),
                projection.initialCarryingAmount(), projection.recommendedConvention(),
                terms.periodicRate()))
            .rateOrThrow();
    }

    private static BigDecimal spreadOverContractual(
        ProjectorRegistry registry, ContractTerms terms, Money netFee) {

        List<FeePosting> fees = netFee.isPositive() ? integralFee(netFee) : List.of();
        return solve(registry, terms, fees).effectiveAnnual()
            .subtract(terms.contractualRate().effectiveAnnual());
    }

    private static FlowVector revisedFlows(LocalDate anchor, Money instalment, int periods) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(anchor.plusMonths(period), period, instalment,
                FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(anchor, Money.INR, flows);
    }
}
