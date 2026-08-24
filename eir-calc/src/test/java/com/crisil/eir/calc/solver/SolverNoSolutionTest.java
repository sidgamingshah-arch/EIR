package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.ANCHOR;
import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Must raise, never guess (calculation specification 4.3, reference case 7).
 *
 * <p><strong>These are the most important tests in the solver suite, and they are
 * regression tripwires rather than behaviour tests.</strong> Silently falling back
 * to the contractual rate on non-convergence reproduces the pre-ACPIR position
 * while appearing to have implemented EIR: the figures are plausible, they tie to
 * nothing, and nothing in the record says the solve failed. The specification names
 * it the single most damaging failure mode available to this engine. A fallback to
 * zero is the same defect wearing a different number.
 *
 * <p>Every test here therefore asserts three things and not one: that the status is
 * {@link SolveStatus#NO_SOLUTION}, that <em>no</em> rate accompanies it, and
 * explicitly that the rate is neither zero nor the contractual rate. The last pair
 * is redundant today — the rate is null, so nothing could equal either — and it is
 * written anyway, because the point of these assertions is to fail loudly and by
 * name on the day someone "fixes" a null-pointer downstream by returning the
 * contractual rate from here. If that change is ever made, these tests must be the
 * thing that stops it, and the failure message must say why.
 *
 * <p>A failed solve is a <em>value</em>, not an exception: {@link RateSolver#solve}
 * does not throw, because a caller batching ten million contracts needs its
 * failures back as data it can queue. The throwing accessor
 * ({@link SolveResult#rateOrThrow()}) is separate and is asserted to throw here.
 */
class SolverNoSolutionTest {

    private final RateSolver solver = new BracketedNewtonSolver();

    /**
     * The three assertions that must hold on every unsolved vector, together with
     * the two tripwires described in the class comment.
     */
    private void assertNothingWasGuessed(SolveResult result, BigDecimal contractualSeed) {
        assertThat(result.status()).isEqualTo(SolveStatus.NO_SOLUTION);
        assertThat(result.status().routesToExceptionQueue()).isTrue();
        assertThat(result.status().carriesRate()).isFalse();
        assertThat(result.hasRate()).isFalse();

        assertThat(result.rate())
            .as("an unsolved vector carries no rate at all: not zero, not the contractual rate, "
                + "not the previous period's (4.3)")
            .isNull();
        assertThat(result.method())
            .as("no method produced a rate, and a method recorded here would read as a successful solve")
            .isNull();
        assertThat(result.residualAtStoredRate())
            .as("a residual of zero on a failed solve reads as a perfect solve")
            .isNull();

        // The tripwires. If a regression ever defaults the rate, the assertion above
        // fails first and these name the two values it would have defaulted to.
        assertThat(result.rate())
            .as("REGRESSION GUARD: a silent fallback to the contractual rate reproduces the "
                + "pre-ACPIR position while appearing to have implemented EIR")
            .isNotEqualTo(Rate.monthly(contractualSeed));
        assertThat(result.rate())
            .as("REGRESSION GUARD: a silent fallback to a zero rate is the same defect wearing "
                + "a different number")
            .isNotEqualTo(Rate.monthly(BigDecimal.ZERO));

        assertThatThrownBy(result::rateOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no EIR was solved")
            .hasMessageContaining("NO_SOLUTION");
    }

    @Test
    @DisplayName("total inflows far below the outflow: the rate exists, is -99.995% a month, and is never published")
    void tokenRecoveryAgainstTheAdvanceIsFlaggedNotDefaulted() {
        // 1,000,000 advanced against a single token receipt of 50.00 a month later.
        //
        // This vector used to be the repo's example of "no economically meaningful rate
        // exists", and that was wrong. f(r) tends to +infinity as r approaches -100% from
        // above, because discounting at a negative rate inflates, and to -1,000,000 as r
        // grows; it is continuous between them, so it must cross zero. It does, exactly
        // once, at 50/1,000,000 - 1 = -99.995% per month. What the standard ladder could
        // not do was reach it: its floor of -0.9999 multiplies a flow one period out by
        // ten thousand, and this one needs twenty thousand.
        //
        // So the honest report is the rate, flagged. -99.995% a month is not an interest
        // rate anyone books — the answer is an impairment — but telling a reviewer "your
        // vector implies -99.995% a month" is strictly more actionable than telling them
        // no rate exists and sending them to look for a fee misclassification that is not
        // there.
        FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(1), 1, Money.inr("50"), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(vector, MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(result.status())
            .as("computed and flagged, never published")
            .isEqualTo(SolveStatus.REQUIRES_REVIEW);
        assertThat(result.status().requiresApproval()).isTrue();
        assertThat(result.rateOrThrow().periodic())
            .as("50 recovered on 1,000,000 one month out is exactly -99.995% per month")
            .isEqualByComparingTo(bd("-0.999950000000"));
        assertThat(result.rateOrThrow().periodic())
            .as("and nothing near the contractual seed, which is the defect this guards")
            .isNotEqualByComparingTo(ONE_PERCENT_MONTHLY);
        assertThat(result.diagnostic())
            .contains("escalating the ladder")
            .contains("impairment, not interest");
    }

    @Test
    @DisplayName("a malformed vector of outflows only has no rate")
    void outflowsOnlyHasNoRate() {
        // A projection that produced further drawdowns and no repayments at all. This
        // is the "or the vector is malformed" limb of 4.3, and in production it is a
        // data defect on a tranched facility rather than an economic outcome.
        FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(12), 12, Money.inr("-100000"), FlowKind.DISBURSEMENT)));

        SolveResult result = solver.solve(SolveRequest.atInception(vector, MONTHLY, ONE_PERCENT_MONTHLY));

        assertNothingWasGuessed(result, ONE_PERCENT_MONTHLY);
        // This is the case that genuinely has no root: f's coefficient sequence never
        // changes sign, so it cannot cross zero at any rate. The diagnostic now says so
        // in those terms rather than offering "total inflows do not exceed the initial
        // outflow" as an alternative explanation — that limb described a vector whose
        // root is merely out of reach, which is a different report and a different
        // status. See tokenRecoveryAgainstTheAdvanceIsFlaggedNotDefaulted.
        assertThat(result.diagnostic())
            .contains("never changes sign")
            .contains("The vector is malformed");
    }

    @Test
    @DisplayName("a vector with nothing after the anchor has no rate")
    void noFutureFlowsHasNoRate() {
        FlowVector inceptionOnly = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT)));

        SolveResult result = solver.solve(
            SolveRequest.atInception(inceptionOnly, MONTHLY, ONE_PERCENT_MONTHLY));

        assertNothingWasGuessed(result, ONE_PERCENT_MONTHLY);
        assertThat(result.diagnostic()).contains("nothing to discount");
        assertThat(result.iterations()).isZero();
    }

    @Test
    @DisplayName("a vector whose future flows are all zero has no rate")
    void allZeroFutureFlowsHasNoRate() {
        // f(r) is then constant in r, so no rate is determined — as distinct from an
        // interest-free advance, which is determined and is exactly zero
        // (see SolverCalibrationTest).
        FlowVector zeros = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(6), 6, Money.zero(Money.INR), FlowKind.PRINCIPAL),
            CashFlow.of(ANCHOR.plusMonths(12), 12, Money.zero(Money.INR), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(zeros, MONTHLY, ONE_PERCENT_MONTHLY));

        assertNothingWasGuessed(result, ONE_PERCENT_MONTHLY);
        assertThat(result.diagnostic()).contains("is zero, so f(r) is constant");
    }

    @Test
    @DisplayName("a loan that loses money solves to a negative rate rather than failing")
    void aLossMakingAdvanceSolvesToANegativeRate() {
        // The counterpart to the tests above, and the reason they are stated on vectors
        // where no root exists rather than on vectors where the root is merely
        // unwelcome. 950,000 recovered against 1,000,000 advanced has a perfectly good
        // IRR: it is negative, it is inside the plausible band, and reporting it is the
        // engine doing its job. Failing it into the exception queue would be the mirror
        // image of guessing.
        FlowVector lossMaking = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(1), 1, Money.inr("475000"), FlowKind.PRINCIPAL),
            CashFlow.of(ANCHOR.plusMonths(2), 2, Money.inr("475000"), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(lossMaking, MONTHLY, ONE_PERCENT_MONTHLY));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic()).isNegative();
        assertThat(result.rate().periodic()).isEqualByComparingTo(bd("-0.033523834409"));
        assertThat(PlausibleBand.standard().contains(result.rate().periodic(), 12)).isTrue();
    }
}
