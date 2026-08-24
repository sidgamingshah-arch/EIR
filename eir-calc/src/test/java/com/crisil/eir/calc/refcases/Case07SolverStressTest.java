package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.CASE1_EMI;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.FIRST_DUE;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.effectiveAnnualPercent;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.levelReceipts;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.projection.BulletProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.PlausibleBand;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.calc.solver.SolverMethod;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Reference case 7 — solver stress instruments.
 *
 * <p>Two halves. The first three instruments must converge to the stated figure;
 * the rest must <b>raise rather than guess</b>.
 *
 * <p>The second half is the more important. Silently falling back to the
 * contractual rate on non-convergence reproduces the pre-ACPIR position while
 * appearing to have implemented EIR: it produces plausible numbers and leaves no
 * trace, which makes it the most damaging failure mode available to this engine.
 * So every negative test here asserts not only the status but that no rate
 * accompanies it, and names the two values a regression would default to.
 *
 * <p>Figures from {@code docs/reference-cases/case-07-solver-stress.md}.
 */
class Case07SolverStressTest {

    private final BracketedNewtonSolver solver = new BracketedNewtonSolver();

    // ------------------------------------------------------------ must converge

    /**
     * The calibration instrument: 1,000,000 advanced, one receipt at month 60, no
     * fees. A single flow, a closed-form answer, no room for the solver to hide.
     */
    private ContractTerms zeroCouponTerms() {
        return ContractTerms.of(Money.inr("1000000"), ONE_PERCENT_MONTHLY, 60, 12,
            DISBURSEMENT, FIRST_DUE, DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.BULLET, RateType.FIXED);
    }

    @Test
    @DisplayName("zero-coupon 5-year bullet: the EIR recovers 0.010000000000 exactly at twelve places")
    void theZeroCouponRecoversTheContractualYieldExactly() {
        ContractTerms terms = zeroCouponTerms();
        ProjectionResult projection = ProjectorRegistry.standard().project(terms, List.of());

        assertThat(paise(new BulletProjector().redemptionAmount(terms)))
            .as("single receipt at month 60 — 1,000,000 x 1.01^60")
            .isEqualByComparingTo(bd("1816696.70"));
        assertThat(paise(projection.initialCarryingAmount()))
            .as("no fees, so the carrying amount is the amount advanced")
            .isEqualByComparingTo(bd("1000000.00"));

        // The fixture quotes the recovered rate per month, so the solve is stated in
        // monthly units. A zero-coupon carries one terminal flow and no intermediate
        // period boundaries, so the periodic-index precondition — every period ordinal
        // present — cannot hold and the projector recommends actual dating; under monthly
        // indexing tau is the ordinal 60 and the discount factor is exactly (1+r)^-60,
        // which is the arithmetic the fixture's closed form describes.
        SolveResult monthly = solver.solve(SolveRequest.of(projection.expected(),
            projection.initialCarryingAmount(), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(monthly.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(monthly.rateOrThrow().periodic().toPlainString())
            .as("exact to twelve decimal places: this is the solver's calibration test, "
                + "a single flow with a closed-form answer and no room to hide")
            .isEqualTo("0.010000000000");
        assertThat(monthly.rateOrThrow().periodsPerYear()).isEqualTo(12);
        // The flows were generated from this rate, so the rounded rate reprices them
        // exactly. A residual here would mean the discounting used by the solve and the
        // compounding used to build the flow disagree.
        assertThat(monthly.residualAtStoredRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the same instrument under the convention the projector recommends is the same yield, annualised")
    void theZeroCouponUnderActualDatingIsTheSameEconomics() {
        ContractTerms terms = zeroCouponTerms();
        ProjectionResult projection = ProjectorRegistry.standard().project(terms, List.of());

        assertThat(projection.recommendedConvention().periodsPerYear())
            .as("one terminal flow voids periodic indexing, so actual dating is recommended "
                + "and the solved rate is the annual effective rate")
            .isEqualTo(1);

        SolveResult asProjected = ReferenceCaseFixtures.solve(projection, terms);

        assertThat(effectiveAnnualPercent(asProjected.rateOrThrow()))
            .as("1% a month compounded — the same rate the monthly solve recovers, restated")
            .isEqualByComparingTo(bd("12.682503"));
        assertThat(effectiveAnnualPercent(asProjected.rateOrThrow()))
            .isEqualByComparingTo(effectiveAnnualPercent(ONE_PERCENT_MONTHLY));
    }

    @Test
    @DisplayName("interest-only bullet with a large upfront fee: 1.11473209% per month, 14.228172% p.a.")
    void theInterestOnlyBulletWithAnUpfrontFee() {
        // 59 monthly interest payments of 10,000.00 then 1,010,000.00 at month 60,
        // against 950,000.00 advanced.
        ContractTerms terms = ContractTerms.of(Money.inr("1000000"), ONE_PERCENT_MONTHLY, 60, 12,
            DISBURSEMENT, FIRST_DUE, DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.INTEREST_ONLY_BULLET, RateType.FIXED);
        List<FeePosting> fees = List.of(FeePosting.received(
            "PROCESSING_FEE", Money.inr("50000"), DISBURSEMENT, FeeClassification.INTEGRAL));

        ProjectionResult projection = ProjectorRegistry.standard().project(terms, fees);
        assertThat(paise(projection.initialCarryingAmount()))
            .as("1,000,000 advanced less the 50,000 integral fee received")
            .isEqualByComparingTo(bd("950000.00"));

        SolveResult result = ReferenceCaseFixtures.solve(projection, terms);

        assertThat(periodicPercent(result.rateOrThrow())).isEqualByComparingTo(bd("1.11473209"));
        assertThat(effectiveAnnualPercent(result.rateOrThrow())).isEqualByComparingTo(bd("14.228172"));
    }

    @Test
    @DisplayName("deep-discount instrument, fee above 20% of principal: 3.20369894% per month, 45.996740% p.a.")
    void theDeepDiscountInstrument() {
        SolveResult result = solver.solve(SolveRequest.atInception(
            deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(result.rateOrThrow())).isEqualByComparingTo(bd("3.20369894"));
        assertThat(effectiveAnnualPercent(result.rateOrThrow())).isEqualByComparingTo(bd("45.996740"));
    }

    /** The 24 case 1 EMIs against 780,000 advanced — a fee above 20% of principal. */
    private FlowVector deepDiscount() {
        return levelReceipts(Money.inr("780000"), CASE1_EMI, 24);
    }

    // -------------------------------------------------------- must raise, never guess

    @Test
    @DisplayName("a root the ladder cannot reach is reported and flagged, never published and never guessed")
    void aRootOutsideTheLadderIsFlaggedNotDefaulted() {
        // The fixture's own premise, corrected. "Total inflows below the initial outflow"
        // is not the no-solution case: for a vector of one outflow then receipts, f(r)
        // runs from +infinity at r -> -100% to -target as r grows and is continuous, so a
        // unique root above -100% always exists. This one is at -99.995% per month, and
        // the ladder's -0.9999 floor is simply not low enough to bracket it. See the
        // "Why total inflows <= initial outflow is not the no-solution case" section of
        // docs/reference-cases/case-07-solver-stress.md.
        FlowVector tokenRecovery = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(DISBURSEMENT.plusMonths(1), 1, Money.inr("50"), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(
            tokenRecovery, MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(result.status())
            .as("a rate exists, so NO_SOLUTION would be false; it is nowhere near bookable, "
                + "so SOLVED would be worse")
            .isEqualTo(SolveStatus.REQUIRES_REVIEW);
        assertThat(result.status().requiresApproval()).isTrue();
        assertThat(result.rateOrThrow().periodic()).isEqualByComparingTo(bd("-0.999950000000"));
        // The thing this case is actually for: whatever happens, not the contractual rate.
        assertThat(result.rateOrThrow().periodic())
            .isNotEqualByComparingTo(ONE_PERCENT_MONTHLY.periodic());
        assertThat(result.rateOrThrow().periodic()).isNotEqualByComparingTo(bd("0"));
    }

    @Test
    @DisplayName("a facility drawn twice and never repaid has no root at all: NO_SOLUTION, and no rate")
    void aVectorWithNoSignChangeAtAllIsNoSolution() {
        // What the row above used to test, on a vector where it is actually true. f's
        // coefficient sequence never changes sign here, so f cannot cross zero at any
        // rate and no escalation of the ladder can invent one.
        FlowVector neverRepaid = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(DISBURSEMENT.plusMonths(12), 12, Money.inr("-100000"), FlowKind.DISBURSEMENT)));

        SolveResult result = solver.solve(SolveRequest.atInception(
            neverRepaid, MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(result.status()).isEqualTo(SolveStatus.NO_SOLUTION);
        assertThat(result.status().routesToExceptionQueue())
            .as("the exception queue, with the flow vector attached")
            .isTrue();
        assertNothingWasGuessed(result);
        assertThatThrownBy(result::rateOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NO_SOLUTION");
    }

    @Test
    @DisplayName("multiple sign changes with exactly one root in band: taken, with every candidate recorded")
    void multipleRootsDisambiguatedByTheBand() {
        // (-1000, +5100, -4400) read as annual periods: roots at exactly 0.1 and 3.0.
        // 300% a year is outside the default (-0.5, 2.0) band and 10% is inside it, so
        // the band alone disambiguates and no contractual rate is needed.
        FlowVector interimDrawdown = interimDrawdown("-1000", "5100", "-4400");
        assertThat(Discounting.signChanges(interimDrawdown)).isEqualTo(2);

        SolveResult result = solver.solve(SolveRequest.atInception(
            interimDrawdown, new TimeConvention.PeriodicIndex(1), null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rateOrThrow().periodic()).isEqualByComparingTo(bd("0.1"));
        assertThat(result.candidateRootCount())
            .as("all candidate roots are logged, not just the chosen one")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("several roots in band with a contractual rate: nearest taken, flagged REQUIRES_REVIEW")
    void severalRootsInBandAreFlaggedForApproval() {
        // (-1000, +2500, -1540): roots at exactly 0.1 and 0.4, both inside the band.
        FlowVector interimDrawdown = interimDrawdown("-1000", "2500", "-1540");

        SolveResult result = solver.solve(SolveRequest.atInception(
            interimDrawdown, new TimeConvention.PeriodicIndex(1), bd("0.12")));

        assertThat(result.status()).isEqualTo(SolveStatus.REQUIRES_REVIEW);
        assertThat(result.status().requiresApproval()).isTrue();
        assertThat(result.status().routesToExceptionQueue())
            .as("computed and usable: flagged, not blocked")
            .isFalse();
        assertThat(result.rateOrThrow().periodic())
            .as("10% is nearer the 12% contractual rate than 40% is")
            .isEqualByComparingTo(bd("0.1"));
        assertThat(result.candidateRootCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("several roots in band with no contractual rate: MULTIPLE_ROOTS, exception queue")
    void severalRootsWithNoTieBreakRouteToTheExceptionQueue() {
        SolveResult result = solver.solve(SolveRequest.atInception(
            interimDrawdown("-1000", "2500", "-1540"), new TimeConvention.PeriodicIndex(1), null));

        assertThat(result.status()).isEqualTo(SolveStatus.MULTIPLE_ROOTS);
        assertThat(result.status().routesToExceptionQueue()).isTrue();
        assertNothingWasGuessed(result);
        assertThat(result.candidateRootCount())
            .as("every candidate root is on the record even when none is taken")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("no root in the plausible band: exception queue, with every candidate recorded")
    void noRootInBandRoutesToTheExceptionQueue() {
        // The same three amounts read as monthly periods: 10% and 40% a month annualise
        // to 214% and 5,569% effective, so the band excludes both.
        SolveResult result = solver.solve(SolveRequest.atInception(
            interimDrawdown("-1000", "2500", "-1540"), MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(result.status()).isEqualTo(SolveStatus.MULTIPLE_ROOTS);
        assertNothingWasGuessed(result);
        assertThat(PlausibleBand.standard().contains(bd("0.1"), 12))
            .as("10% a month is 214% a year — outside the band by design")
            .isFalse();
    }

    @Test
    @Timeout(60)
    @DisplayName("a Newton phase that runs out falls back to 200 bisection iterations and reaches the same rate")
    void theBisectionFallbackGuaranteesTermination() {
        // At the deep-discount magnitude a raw tangent step from the contractual seed
        // overshoots the bracket. Forcing the Newton cap to one iteration reproduces the
        // condition the specification's cap of 100 produces on a genuinely ill-conditioned
        // vector, without needing a vector whose ill-conditioning must itself be defended.
        SolverTolerance oneNewtonStep = new SolverTolerance(
            bd("1E-10"), bd("1E-16"), Precision.RATE_EPSILON, 1,
            SolverTolerance.MAX_BISECTION_ITERATIONS);
        SolveRequest request = SolveRequest.atInception(
            deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY.periodic());

        SolveResult newton = solver.solve(request);
        SolveResult bisected = solver.solve(request.withTolerance(oneNewtonStep));

        assertThat(bisected.method()).isEqualTo(SolverMethod.BISECTION_FALLBACK);
        assertThat(bisected.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(periodicPercent(bisected.rateOrThrow()))
            .as("a bracket containing a sign change cannot fail to converge under bisection, "
                + "which is why the fallback is mandatory rather than optional")
            .isEqualByComparingTo(bd("3.20369894"));
        assertThat(bisected.rateOrThrow()).isEqualTo(newton.rateOrThrow());
        assertThat(bisected.iterations())
            .isLessThanOrEqualTo(1 + SolverTolerance.MAX_BISECTION_ITERATIONS);
        assertThat(SolverTolerance.MAX_NEWTON_ITERATIONS).isEqualTo(100);
        assertThat(SolverTolerance.MAX_BISECTION_ITERATIONS).isEqualTo(200);
    }

    @Test
    @DisplayName("an EXCLUDED_BY_DIRECTION penal charge is rejected at ingestion, naming invariant PC-1")
    void aPenalChargeCannotEnterTheVector() {
        // Under RBI's 2023 framework penal amounts are charges, not penal interest: not
        // capitalised, bearing no further interest, and therefore incapable of entering
        // the amortisation schedule or the gross carrying amount. Legacy core banking
        // systems routinely book them into the interest ledger, which is why this is a
        // hard boundary filter and not a configurable rule.
        assertThatThrownBy(() -> FeePosting.received("PENAL_CHARGE", Money.inr("2500"),
            DISBURSEMENT, FeeClassification.EXCLUDED_BY_DIRECTION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EXCLUDED_BY_DIRECTION")
            .hasMessageContaining(InvariantId.PC_1.name());
        assertThat(FeeClassification.EXCLUDED_BY_DIRECTION.entersCarryingAmount()).isFalse();
    }

    @Test
    @DisplayName("a fee posting the rule set could not resolve never reaches the solver")
    void anUnmappedFeeCodeFailsAtTheBoundary() {
        // FR-202: an unmapped fee code fails into the exception queue and is never
        // defaulted to either treatment. Resolution itself is a versioned rule set
        // upstream of these modules, so what the calculation boundary can assert is that
        // an unresolved posting is unconstructible — there is no classification to
        // default to, and no projector can be handed one.
        assertThatThrownBy(() -> FeePosting.received(
            "UNKNOWN_FEE_CODE", Money.inr("5000"), DISBURSEMENT, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("classification");
        assertThatThrownBy(() -> FeePosting.received(
            "", Money.inr("5000"), DISBURSEMENT, FeeClassification.INTEGRAL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("feeCode must not be blank");

        // The ACPIR 53 companion: an integral cost with no cost function fails rather
        // than defaulting to either capitalisation or exclusion (FR-203).
        assertThatThrownBy(() -> new FeePosting("DSA_COMMISSION", Money.inr("-10000"),
            DISBURSEMENT, FeeClassification.INTEGRAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cost_function");
    }

    @Test
    @DisplayName("the one that matters most: a failed solve never carries the contractual rate")
    void thereIsNoSilentContractualRateFallback() {
        // Stated once more on its own because it is the assertion the fixture calls out.
        // If a regression ever "fixes" a null rate downstream by returning the contractual
        // rate from here, this is the test that must stop it.
        FlowVector outflowsOnly = FlowVector.of(DISBURSEMENT, Money.INR, List.of(
            CashFlow.of(DISBURSEMENT, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(DISBURSEMENT.plusMonths(12), 12, Money.inr("-100000"),
                FlowKind.DISBURSEMENT)));

        SolveResult result = solver.solve(SolveRequest.atInception(
            outflowsOnly, MONTHLY, ONE_PERCENT_MONTHLY.periodic()));

        assertThat(result.status()).isEqualTo(SolveStatus.NO_SOLUTION);
        assertThat(result.rate())
            .as("REGRESSION GUARD: a silent fallback to the contractual rate reproduces the "
                + "pre-ACPIR position while appearing to have implemented EIR")
            .isNotEqualTo(ONE_PERCENT_MONTHLY);
        assertThat(result.rate())
            .as("REGRESSION GUARD: a silent fallback to zero is the same defect wearing a "
                + "different number")
            .isNotEqualTo(Rate.monthly(BigDecimal.ZERO));
        assertThat(result.rate()).isNull();
    }

    private static void assertNothingWasGuessed(SolveResult result) {
        assertThat(result.status().carriesRate()).isFalse();
        assertThat(result.hasRate()).isFalse();
        assertThat(result.rate())
            .as("an unsolved vector carries no rate at all: not zero, not the contractual rate")
            .isNull();
        assertThat(result.method())
            .as("a method recorded here would read as a successful solve")
            .isNull();
        assertThat(result.residualAtStoredRate())
            .as("a residual of zero on a failed solve reads as a perfect solve")
            .isNull();
        assertThat(result.diagnostic()).isNotBlank();
    }

    /**
     * A stylised interim-drawdown profile: an advance, one large receipt, then a
     * further drawdown — the shape that gives the present-value function more than
     * one sign change and hence more than one mathematically valid IRR. In an
     * Indian bank's book it comes from tranched project finance with large interim
     * drawdowns.
     */
    private static FlowVector interimDrawdown(String advance, String receipt, String drawdown) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, Money.inr(advance), FlowKind.DISBURSEMENT));
        flows.add(CashFlow.of(DISBURSEMENT.plusMonths(12), 1, Money.inr(receipt), FlowKind.PRINCIPAL));
        flows.add(CashFlow.of(DISBURSEMENT.plusMonths(24), 2, Money.inr(drawdown),
            FlowKind.DISBURSEMENT));
        return FlowVector.of(DISBURSEMENT, Money.INR, flows);
    }
}
