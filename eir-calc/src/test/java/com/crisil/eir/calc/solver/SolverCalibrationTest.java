package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.ANCHOR;
import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.ONE_PERCENT_MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static com.crisil.eir.calc.solver.SolverFixtures.zeroCouponBullet;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The solver's calibration test: one flow, a closed-form answer, nowhere to hide
 * (reference case 7).
 *
 * <p>1,000,000 advanced against a single receipt at month 60 of
 * {@code 1,000,000 * (1.01)^60} has exactly one root and it is exactly 1% per
 * month. Everything else the solver does is judgement about brackets, bands and
 * tolerances; this is the one instrument where the right answer is known in closed
 * form to any number of places, so it is asserted at the twelfth decimal — the
 * precision at which a rate is stored and at which a published amortisation must be
 * reproducible from the published rate (calculation specification 1.4).
 *
 * <p>The assertions are on the <em>stored</em> string, not on a numeric comparison,
 * so the scale is asserted too. A rate carrying 0.01 at scale 2 would compare equal
 * numerically and would not be the twelve-decimal figure the specification requires
 * to be persisted.
 */
class SolverCalibrationTest {

    private final RateSolver solver = new BracketedNewtonSolver();

    @Test
    @DisplayName("zero-coupon bullet recovers the contractual yield exactly at 12dp, seeded")
    void recoversContractualYieldExactly() {
        FlowVector vector = zeroCouponBullet();
        SolveRequest request = SolveRequest.atInception(vector, MONTHLY, ONE_PERCENT_MONTHLY);

        SolveResult result = solver.solve(request);

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic().toPlainString()).isEqualTo("0.010000000000");
        assertThat(result.rate().periodic().scale()).isEqualTo(Precision.RATE_SCALE);
        assertThat(result.rate().periodsPerYear()).isEqualTo(12);
        // The flows were generated from this rate, so the rounded rate reprices them
        // exactly. A residual here is not a tolerance question: it would mean the
        // discounting used by the solve and the compounding used to build the flow
        // disagree, which is failure mode 01 §11 #9.
        assertThat(result.residualAtStoredRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the recovered rate does not depend on the seed — an unseeded solve lands on the same 12dp figure")
    void seedDoesNotMoveTheAnswer() {
        FlowVector vector = zeroCouponBullet();

        SolveResult unseeded = solver.solve(SolveRequest.atInception(vector, MONTHLY, null));
        SolveResult misleadingSeed = solver.solve(
            SolveRequest.atInception(vector, MONTHLY, bd("0.05")));

        // A solver that returned its seed, or drifted towards it, would pass the
        // seeded calibration above and fail here. That is the whole reason this case
        // is asserted three ways rather than once.
        assertThat(unseeded.rate().periodic().toPlainString()).isEqualTo("0.010000000000");
        assertThat(misleadingSeed.rate().periodic().toPlainString()).isEqualTo("0.010000000000");
        assertThat(unseeded.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(misleadingSeed.status()).isEqualTo(SolveStatus.SOLVED);
    }

    @Test
    @DisplayName("both annualisations are derived from the periodic rate, and they differ")
    void bothAnnualisationsAreAvailableAndDistinct() {
        Rate rate = solver.solve(SolveRequest.atInception(zeroCouponBullet(), MONTHLY, ONE_PERCENT_MONTHLY))
            .rateOrThrow();

        // 4.5: effective annual is (1+r)^p - 1 and nominal is r*p. Neither is called
        // "the annual EIR", and the gap between them on a 1%-a-month instrument is
        // 68 basis points — which is why the specification refuses to let one stand
        // in for the other.
        assertThat(rate.effectiveAnnual().setScale(12, Precision.MODE))
            .isEqualByComparingTo(bd("0.126825030132"));
        assertThat(rate.nominalAnnual().setScale(12, Precision.MODE))
            .isEqualByComparingTo(bd("0.120000000000"));
        assertThat(rate.effectiveAnnual()).isGreaterThan(rate.nominalAnnual());
    }

    @Test
    @DisplayName("an exact root on a ladder node is taken as a root, not iterated towards")
    void ladderNodeThatIsItselfARootNeedsNoIteration() {
        SolveResult result = solver.solve(
            SolveRequest.atInception(zeroCouponBullet(), MONTHLY, ONE_PERCENT_MONTHLY));

        // 1% per month is a ladder node (4.2 step 1) and this instrument's root. The
        // scan records a degenerate bracket rather than looking only for crossings:
        // the interest-free loan solves to exactly 0, also a ladder node, and a scan
        // that missed it would report NO_SOLUTION on a perfectly ordinary instrument.
        assertThat(result.iterations()).isZero();
        assertThat(result.diagnostic()).contains("exact root");
        assertThat(result.candidateRoots()).hasSize(1);
    }

    @Test
    @DisplayName("an interest-free advance solves to exactly zero rather than to no solution")
    void interestFreeAdvanceSolvesToZero() {
        // Interest-free instalment credit and intra-group advances exist, and their
        // EIR is zero where no fee is integral. Zero is a ladder node, so this only
        // works because the scan treats a node with f = 0 as a root.
        FlowVector interestFree = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-120000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR.plusMonths(1), 1, Money.inr("60000"), FlowKind.PRINCIPAL),
            CashFlow.of(ANCHOR.plusMonths(2), 2, Money.inr("60000"), FlowKind.PRINCIPAL)));

        SolveResult result = solver.solve(SolveRequest.atInception(interestFree, MONTHLY, BigDecimal.ZERO));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic().toPlainString()).isEqualTo("0.000000000000");
        assertThat(result.rate().effectiveAnnual()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the Tier 1 tightened tolerance recovers the identical stored rate")
    void tightenedToleranceDoesNotMoveTheStoredRate() {
        SolveRequest standard = SolveRequest.atInception(zeroCouponBullet(), MONTHLY, ONE_PERCENT_MONTHLY);
        SolveRequest tier1 = standard.withTolerance(SolverTolerance.forTier(MaterialityTier.TIER_1));

        assertThat(tier1.absoluteTolerance()).isLessThan(standard.absoluteTolerance());
        // FR-406: the tightening buys residual accuracy on exposures where the EIR is
        // also the ECL discount rate (ACPIR 50). What it must never do is change the
        // figure on an instrument that was already inside tolerance, because two tiers
        // reporting different rates for one flow vector is indefensible.
        assertThat(solver.solve(tier1).rateOrThrow())
            .isEqualTo(solver.solve(standard).rateOrThrow());
    }
}
