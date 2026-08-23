package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static com.crisil.eir.calc.solver.SolverFixtures.deepDiscount;
import static com.crisil.eir.calc.solver.SolverFixtures.rootsAtTenAndFortyPercent;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.TimeConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant DT-1: identical inputs produce bit-identical results, however far apart
 * they are run.
 *
 * <p>A closed period gets re-run — for a restatement, for an audit re-performance,
 * for the nightly replay that asserts this invariant in production — and it must
 * reproduce its published figures exactly. Not to within a tolerance: exactly. The
 * comparisons below are therefore on record equality and on the plain strings of
 * every {@link java.math.BigDecimal} on the result, because two decimals that
 * compare equal numerically but carry different scales are two different published
 * figures the moment either is written down.
 *
 * <p>Three properties together give this: no clock, no randomness, and no state
 * carried between solves. The last is why one solver instance is asserted to agree
 * with a freshly constructed one — a solver that cached anything across calls would
 * pass a repeat-call test and fail this one.
 */
class SolverDeterminismTest {

    private static void assertBitIdentical(SolveResult first, SolveResult second) {
        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(second.status());
        assertThat(first.method()).isEqualTo(second.method());
        assertThat(first.iterations()).isEqualTo(second.iterations());
        assertThat(first.diagnostic()).isEqualTo(second.diagnostic());
        if (first.hasRate()) {
            assertThat(first.rate().periodic().toPlainString())
                .isEqualTo(second.rate().periodic().toPlainString());
            assertThat(first.residualAtStoredRate().toPlainString())
                .isEqualTo(second.residualAtStoredRate().toPlainString());
        }
        assertThat(first.candidateRoots()).hasSameSizeAs(second.candidateRoots());
        for (int index = 0; index < first.candidateRoots().size(); index++) {
            assertThat(first.candidateRoots().get(index).toPlainString())
                .isEqualTo(second.candidateRoots().get(index).toPlainString());
        }
    }

    @Test
    @DisplayName("the same request solved twice returns bit-identical results")
    void repeatedSolvesAreBitIdentical() {
        RateSolver solver = new BracketedNewtonSolver();
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        assertBitIdentical(solver.solve(request), solver.solve(request));
    }

    @Test
    @DisplayName("a fresh solver instance agrees with one that has already solved something else")
    void noStateIsCarriedBetweenSolves() {
        RateSolver used = new BracketedNewtonSolver();
        SolveRequest other = SolveRequest.atInception(rootsAtTenAndFortyPercent(12),
            new TimeConvention.PeriodicIndex(1), bd("0.12"));
        SolveRequest request = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);

        used.solve(other);
        SolveResult afterOtherWork = used.solve(request);
        SolveResult fromFresh = new BracketedNewtonSolver().solve(request);

        assertBitIdentical(afterOtherWork, fromFresh);
    }

    @Test
    @DisplayName("an equal request built independently solves identically")
    void equalRequestsSolveIdentically() {
        // The request is the whole input (3): two contracts with identical vectors must
        // solve identically whatever product, tier or policy version produced them,
        // because that is what makes a nightly replay reproduce a published figure.
        RateSolver solver = new BracketedNewtonSolver();
        SolveRequest first = SolveRequest.atInception(deepDiscount(), MONTHLY, ONE_PERCENT_MONTHLY);
        SolveRequest second = SolveRequest.atInception(
            deepDiscount(), TimeConvention.PeriodicIndex.monthly(), bd("0.010"));

        assertBitIdentical(solver.solve(first), solver.solve(second));
    }

    @Test
    @DisplayName("an unsolved vector fails identically, diagnostic included")
    void failureIsDeterministicToo() {
        // The exception-queue entry is a record. A diagnostic that varied between
        // identical runs would not be one, and the queue is where the reviewer reads
        // what happened.
        RateSolver solver = new BracketedNewtonSolver();
        SolveRequest request = SolveRequest.atInception(
            SolverFixtures.interimDrawdown("-1000000", "-1000", "-1000", 6), MONTHLY, ONE_PERCENT_MONTHLY);

        assertBitIdentical(solver.solve(request), solver.solve(request));
        assertThat(solver.solve(request).status()).isEqualTo(SolveStatus.NO_SOLUTION);
    }

    @Test
    @DisplayName("the bracket ladder is fixed and published")
    void theLadderIsFixedAndVisible() {
        // 4.2 step 1. Fixed rather than derived, and public rather than private,
        // because a bracket that varied with the vector would make two runs of the same
        // contract incomparable, and a reviewer cannot check a ladder they cannot see.
        assertThat(BracketedNewtonSolver.LADDER).containsExactly(
            bd("-0.9999"), bd("-0.5"), bd("-0.1"), bd("0"), bd("0.001"), bd("0.01"), bd("0.05"),
            bd("0.1"), bd("0.25"), bd("0.5"), bd("1.0"), bd("2.0"), bd("5.0"), bd("10.0"));
        assertThat(BracketedNewtonSolver.LADDER).isSortedAccordingTo(java.math.BigDecimal::compareTo);
    }
}
