package com.crisil.eir.calc.refcases;

import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.TwoLegResult;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The nine reference cases as inputs, and the Case 1 pipeline they hang off.
 *
 * <p><b>Every expected figure in this package comes from
 * {@code docs/reference-cases/case-0*.md} and from nowhere else.</b> The reference
 * cases are the specification and the engine is the thing under test, so a
 * disagreement is a defect in the engine (or, rarely and reportably, in the
 * fixture) and never a licence to move the expected value. That is the whole point
 * of a merge gate: a silent edit to an expected value is how a regression becomes
 * a specification.
 *
 * <p>What this fixture therefore holds is <em>inputs</em> — principal, rate, tenor,
 * fee postings, dates — and the canonical pipeline that turns them into a
 * projection, a rate and two legs. It holds no expected outputs at all. The one
 * apparent exception is {@link #CASE1_EMI}, which is an input to the cases that
 * continue from month 12 rather than a figure under test; Case 1 itself derives the
 * instalment from the annuity and asserts it.
 *
 * <p>Dates are explicit constants. Nothing in the calculation path may read a
 * clock, so a fixture that derived a disbursement date from today would be
 * asserting a moving target — and the cases that continue at "month 12" would
 * silently move with it.
 *
 * <h2>The pipeline</h2>
 *
 * <p>Projection, solve, then two legs, in that order and through the published
 * seams:
 *
 * <pre>
 * ProjectionResult p = ProjectorRegistry.standard().project(terms, fees);
 * SolveResult r      = solver.solve(SolveRequest.of(
 *                          p.expected(), p.initialCarryingAmount(),
 *                          p.recommendedConvention(), terms.periodicRate()));
 * AmortisationResult eir = AmortisationEngine.eirLeg(
 *                          p.initialCarryingAmount(), r.rateOrThrow(),
 *                          p.expected(), p.recommendedConvention());
 * AmortisationResult ct  = AmortisationEngine.contractualLeg(
 *                          terms.principal(),
 *                          ConventionSelector.rateUnder(p.recommendedConvention(),
 *                                                       terms.contractualRate()),
 *                          p.contractual(), p.recommendedConvention());
 * </pre>
 *
 * <p>The contractual leg goes through {@link ConventionSelector#rateUnder} because
 * a contract quotes its rate in the schedule's frequency while the convention the
 * vector licenses may be actual dating, whose {@code periodsPerYear} is one.
 * Pairing a monthly rate with a year fraction is an annualisation round-trip in
 * disguise, so the seam is mandatory rather than tidy. The EIR leg needs no
 * adaptation: the solver already returns the rate in the convention's units.
 */
final class ReferenceCaseFixtures {

    /** Case 1's inception date, and the anchor of every inception vector here. */
    static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);

    /** One whole period after disbursement — the condition periodic indexing needs. */
    static final LocalDate FIRST_DUE = LocalDate.of(2026, 5, 1);

    /** The month-12 due date: the event date for Cases 2, 3, 4, 5 and 8. */
    static final LocalDate MONTH_12 = LocalDate.of(2027, 4, 1);

    /** Monthly periodic indexing: tau is the period ordinal, so every dtau is exactly 1. */
    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /** Annual periodic indexing, for the Case 9 zero-coupon bond. */
    static final TimeConvention ANNUAL = new TimeConvention.PeriodicIndex(1);

    /** 12% p.a. nominal with monthly compounding: 1% per month, never 12% divided by 12. */
    static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(bd("0.01"));

    /**
     * The Case 4 rate after the benchmark rises 100bps: 13% p.a. nominal monthly.
     *
     * <p>Divided at working precision and stored at twelve places, which is
     * 1.08333333% per month as Case 4 states it. Never 13% applied to a monthly
     * balance and never 13/12 taken as a decimal shortcut.
     */
    static final Rate THIRTEEN_PERCENT_MONTHLY =
        Rate.monthly(bd("13").divide(bd("1200"), Precision.WORKING));

    /** Case 1's principal advanced. */
    static final Money PRINCIPAL = Money.inr("1000000");

    /**
     * Case 1's billed EMI — an <em>input</em> to Cases 2 to 8, which continue from
     * month 12 of a loan that billed this amount. Case 1 derives it and asserts it.
     */
    static final Money CASE1_EMI = Money.inr("47073.47");

    /** 15,000 received less 10,000 paid. Positive is income. */
    static final Money NET_INTEGRAL_FEE = Money.inr("5000");

    private static final BigDecimal HUNDRED = bd("100");

    private ReferenceCaseFixtures() {
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ---------------------------------------------------------------- Case 1

    /**
     * The Case 1 loan: 1,000,000 at 12% p.a. nominal monthly over 24 EMIs.
     *
     * <p>30/360 bond basis is the Indian term-loan convention. It matters only
     * where a vector falls back to actual dating; on this clean monthly schedule
     * the periodic index is exactly equivalent and the day count never gets used.
     */
    static ContractTerms case1Terms() {
        return case1Terms(RateType.FIXED);
    }

    /** The same loan priced as floating — Cases 4 and 8. */
    static ContractTerms case1Terms(RateType rateType) {
        return ContractTerms.of(PRINCIPAL, ONE_PERCENT_MONTHLY, 24, 12, DISBURSEMENT, FIRST_DUE,
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, rateType);
    }

    /**
     * Case 1's fee set: 15,000 of processing fee received (ACPIR 52) against 10,000
     * of DSA commission paid (ACPIR 53), netting to 5,000 of integral fee income.
     *
     * <p>The commission carries {@code SELLING} because that is the ACPIR 53
     * dividing line — a selling-agent incentive capitalises, internal
     * credit-appraisal cost does not — and the posting cannot be constructed
     * without it.
     */
    static List<FeePosting> case1Fees() {
        return List.of(
            FeePosting.received("PROCESSING_FEE", Money.inr("15000"), DISBURSEMENT,
                FeeClassification.INTEGRAL),
            FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), DISBURSEMENT,
                FeeClassification.INTEGRAL, "SELLING"));
    }

    /** The whole Case 1 pipeline, computed once and shared by the cases that continue it. */
    static Baseline baseline() {
        return BaselineHolder.INSTANCE;
    }

    /**
     * Case 1 end to end: the projection, the solve, both legs and the
     * reconciliation.
     *
     * <p>Held whole rather than as separate accessors so that no test can pair an
     * EIR leg against a contractual leg from a different projection — which
     * produces a fee profile that looks plausible and reconciles to nothing.
     */
    record Baseline(
        ContractTerms terms,
        ProjectionResult projection,
        SolveResult solve,
        Rate eir,
        AmortisationResult eirLeg,
        AmortisationResult contractualLeg,
        TwoLegResult twoLeg) {
    }

    private static final class BaselineHolder {
        private static final Baseline INSTANCE = computeBaseline();
    }

    private static Baseline computeBaseline() {
        ContractTerms terms = case1Terms();
        ProjectionResult projection = ProjectorRegistry.standard().project(terms, case1Fees());
        SolveResult solve = solve(projection, terms);
        Rate eir = solve.rateOrThrow();
        AmortisationResult eirLeg = eirLeg(projection, eir);
        AmortisationResult contractualLeg = contractualLeg(projection, terms);
        TwoLegResult twoLeg = TwoLegResult.reconcile(
            eirLeg, contractualLeg, eir, terms.contractualRate(), NET_INTEGRAL_FEE);
        return new Baseline(terms, projection, solve, eir, eirLeg, contractualLeg, twoLeg);
    }

    // ------------------------------------------------------- pipeline seams

    /** The solver the reference cases are asserted against. */
    static SolveResult solve(ProjectionResult projection, ContractTerms terms) {
        return new BracketedNewtonSolver().solve(SolveRequest.of(
            projection.expected(), projection.initialCarryingAmount(),
            projection.recommendedConvention(), terms.periodicRate()));
    }

    /** The EIR leg over the expected vector the rate was solved against. */
    static AmortisationResult eirLeg(ProjectionResult projection, Rate eir) {
        return AmortisationEngine.eirLeg(projection.initialCarryingAmount(), eir,
            projection.expected(), projection.recommendedConvention());
    }

    /** The contractual leg from par, with the rate restated in the convention's units. */
    static AmortisationResult contractualLeg(ProjectionResult projection, ContractTerms terms) {
        return AmortisationEngine.contractualLeg(
            terms.principal(),
            ConventionSelector.rateUnder(projection.recommendedConvention(), terms.contractualRate()),
            projection.contractual(),
            projection.recommendedConvention());
    }

    // ------------------------------------------------------------ vectors

    /**
     * {@code count} monthly receipts of {@code instalment} against
     * {@code advanced} on the anchor date, periods 1..count.
     *
     * <p>Used where a case states its instrument as flows rather than as contract
     * terms — Case 6's expected-loss haircut, Case 7's stress profiles — because
     * that is how those cases state them.
     */
    static FlowVector levelReceipts(Money advanced, Money instalment, int count) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, advanced.negate(), FlowKind.DISBURSEMENT));
        for (int period = 1; period <= count; period++) {
            flows.add(CashFlow.of(DISBURSEMENT.plusMonths(period), period, instalment,
                FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(DISBURSEMENT, Money.INR, flows);
    }

    /**
     * {@code count} monthly instalments anchored on an event date, with nothing on
     * the anchor.
     *
     * <p>Cash received <em>on</em> the event date has already been applied by the
     * time a restatement runs, so discounting it into the restated balance would
     * count it twice.
     */
    static FlowVector remainingFrom(LocalDate anchor, Money instalment, int count) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= count; period++) {
            flows.add(CashFlow.of(anchor.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(anchor, Money.INR, flows);
    }

    // ------------------------------------------------------------ assertions

    /** A money figure as the reference cases print it: presentation scale, 2dp. */
    static BigDecimal paise(Money money) {
        return money.atPresentationScale().amount();
    }

    /**
     * A periodic rate as a percentage to eight places — {@code 1.04214918} for
     * Case 1.
     *
     * <p>Eight places of percent is ten of the rate itself, two inside the twelve
     * a {@link Rate} stores, so this is the published figure and not a truncation
     * of it.
     */
    static BigDecimal periodicPercent(Rate rate) {
        return rate.periodic().multiply(HUNDRED, Precision.WORKING).setScale(8, RoundingMode.HALF_UP);
    }

    /** The effective annual rate as a percentage to six places — {@code 13.248094}. */
    static BigDecimal effectiveAnnualPercent(Rate rate) {
        return rate.effectiveAnnual().multiply(HUNDRED, Precision.WORKING)
            .setScale(6, RoundingMode.HALF_UP);
    }

    /** The nominal annual rate as a percentage to six places — {@code 12.505790}. */
    static BigDecimal nominalAnnualPercent(Rate rate) {
        return rate.nominalAnnual().multiply(HUNDRED, Precision.WORKING)
            .setScale(6, RoundingMode.HALF_UP);
    }

    /** A percentage change, {@code (actual / baseline - 1) * 100}, to one place. */
    static BigDecimal percentChange(BigDecimal actual, BigDecimal baseline) {
        return actual.divide(baseline, Precision.WORKING).subtract(BigDecimal.ONE)
            .multiply(HUNDRED, Precision.WORKING).setScale(1, RoundingMode.HALF_UP);
    }
}
