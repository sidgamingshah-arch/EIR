package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.ANCHOR;
import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The escalated bracket ladder, and why a fixed ladder cannot serve a book that
 * contains both a twenty-year term loan and a one-day money-market drawing.
 *
 * <p>A ladder node caps the money multiple the scan can bracket at
 * {@code (1+node)^tau}. Under {@code PeriodicIndex} tau is a period ordinal and the
 * cap is enormous. Under {@code ActualDate} tau is a year fraction, and as it falls
 * the cap collapses toward 1 — the top node of 10.0 brackets a multiple of 11 over a
 * year and only 1.006591 over a single day. Everything short is therefore out of
 * reach of the standard ladder, and "short" is exactly the Tier 3 population.
 *
 * <p>What this class pins down is that the engine reaches those roots and does not
 * publish them. Both halves matter. Reporting {@code NO_SOLUTION} for a root that
 * demonstrably exists sends a reviewer looking for a fee misclassification that is
 * not there; reporting it {@code SOLVED} books 4,092% as a yield.
 */
class SolverLadderEscalationTest {

    private static final TimeConvention ACTUAL =
        new TimeConvention.ActualDate(DayCountConvention.ACT_365F);

    private final BracketedNewtonSolver solver = new BracketedNewtonSolver();

    /**
     * A one-day money-market drawing: 10,000,000 advanced net of a 100,000 integral
     * fee, repaid next day with one day of 6.75% ACT/365F interest.
     */
    private static FlowVector oneDayDrawingWithFee() {
        BigDecimal dayOfInterest = bd("10000000")
            .multiply(bd("0.0675"), Precision.WORKING)
            .divide(bd("365"), Precision.WORKING);
        return FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-10000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR, 0, Money.inr("100000"), FlowKind.INTEGRAL_FEE_RECEIVED),
            CashFlow.of(ANCHOR.plusDays(1), 1,
                Money.of(bd("10000000").add(dayOfInterest, Precision.WORKING), Money.INR),
                FlowKind.COMBINED_EMI)));
    }

    @Test
    @DisplayName("the standard ladder cannot bracket a one-day drawing carrying a 1% fee, and the escalation can")
    void aOneDayDrawingIsBracketedByTheEscalation() {
        FlowVector vector = oneDayDrawingWithFee();

        // The arithmetic first, so the test does not rest on the solver to establish what
        // the answer is: 9,900,000 out, 10,001,849.32 back one day later, a multiple of
        // 1.010288 over tau = 1/365. The ladder's top node of 10.0 brackets 1.006591 over
        // that tau, so the root is above it and the standard rungs never change sign.
        assertThat(SolveRequest.inceptionTarget(vector).amount())
            .isEqualByComparingTo(bd("9900000"));
        assertThat(vector.future().get(0).amount().atPresentationScale().amount())
            .isEqualByComparingTo(bd("10001849.32"));
        assertThat(Precision.onePlusPow(bd("10"), bd("1").divide(bd("365"), Precision.WORKING))
            .setScale(6, RoundingMode.HALF_UP))
            .as("the most the top of the standard ladder can bracket over one day")
            .isEqualByComparingTo(bd("1.006591"));

        SolveResult result = solver.solve(SolveRequest.atInception(vector, ACTUAL, null));

        assertThat(result.hasRate())
            .as("a unique root exists and the engine must say what it is")
            .isTrue();
        assertThat(result.rateOrThrow().periodic().multiply(bd("100"), Precision.WORKING)
            .setScale(4, RoundingMode.HALF_UP))
            .as("annual effective: 1.010288 compounded 365 times")
            .isEqualByComparingTo(bd("4092.4331"));
    }

    @Test
    @DisplayName("a root only the escalation could reach is never published, whatever it is")
    void anEscalatedRootIsFlaggedForApproval() {
        SolveResult result = solver.solve(
            SolveRequest.atInception(oneDayDrawingWithFee(), ACTUAL, null));

        assertThat(result.status()).isEqualTo(SolveStatus.REQUIRES_REVIEW);
        assertThat(result.status().requiresApproval()).isTrue();
        assertThat(result.isSolved())
            .as("4,092% is arithmetically right and is not a yield anyone books unread")
            .isFalse();
        assertThat(result.diagnostic())
            .contains("escalating the ladder")
            .contains("a fee it cannot amortise");
    }

    @Test
    @DisplayName("escalation changes nothing for a vector the standard ladder already brackets")
    void theStandardPathIsUntouched() {
        // The guarantee that matters for the other ten million contracts: escalation is
        // reached only when the standard scan finds no sign change, so it cannot move a
        // figure that already had one. Case 7's calibration instrument solves to exactly
        // 1% a month and stays SOLVED.
        SolveResult result = solver.solve(SolveRequest.atInception(
            SolverFixtures.zeroCouponBullet(), MONTHLY, null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rateOrThrow().periodic()).isEqualByComparingTo(bd("0.010000000000"));
        assertThat(result.diagnostic()).doesNotContain("escalating the ladder");
    }

    @Test
    @DisplayName("the escalated ladder is fixed, ordered and published, so two runs agree")
    void theEscalationIsFixedAndDeterministic() {
        // DT-1: a bracket that varied with the vector would make two runs of the same
        // contract incomparable, and that applies to the escalation as much as to the
        // standard rungs — which is why it is a published constant and not a search.
        assertThat(BracketedNewtonSolver.ESCALATION).isSortedAccordingTo(BigDecimal::compareTo);
        assertThat(BracketedNewtonSolver.ESCALATION.get(0))
            .as("the closest node to -100% that survives rounding to 12dp without becoming -1")
            .isEqualByComparingTo(bd("-0.999999999999"));
        assertThat(BracketedNewtonSolver.ESCALATION.get(0)
            .setScale(Precision.RATE_SCALE, RoundingMode.HALF_UP))
            .isNotEqualByComparingTo(bd("-1"));

        SolveResult first = solver.solve(
            SolveRequest.atInception(oneDayDrawingWithFee(), ACTUAL, null));
        SolveResult second = solver.solve(
            SolveRequest.atInception(oneDayDrawingWithFee(), ACTUAL, null));
        assertThat(second.rateOrThrow()).isEqualTo(first.rateOrThrow());
        assertThat(second.diagnostic()).isEqualTo(first.diagnostic());
        assertThat(second.status()).isEqualTo(first.status());
    }
}
