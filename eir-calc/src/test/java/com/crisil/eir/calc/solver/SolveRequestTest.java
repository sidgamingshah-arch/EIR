package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.ANCHOR;
import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static com.crisil.eir.calc.solver.SolverFixtures.deepDiscount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The solve boundary: what a request means, and which failures are data conditions
 * rather than programming errors.
 *
 * <p>The distinction is the contract of {@link RateSolver#solve}. A vector with no
 * solution, or with several, is a <em>data</em> condition and comes back as a status
 * on a result, because a caller batching a million contracts needs its failures
 * back as values it can queue. A malformed <em>request</em> — a target in a
 * different currency from the vector, a seed at or below -100% — is a programming
 * error and throws from the constructor, before any solve is attempted.
 */
class SolveRequestTest {

    @Test
    @DisplayName("the inception target is the negated net cash flow on the anchor date")
    void inceptionTargetIsTheSignedNetAtInception() {
        // IC-1 restated as the solver sees it. The asset case: 1,000,000 out, 15,000 of
        // fee in, 10,000 of cost out, so 995,000 of net outflow and a target of
        // +995,000 — neither the 1,000,000 advanced nor the 985,000 the borrower
        // received.
        FlowVector asset = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR, 0, Money.inr("-1000000"), FlowKind.DISBURSEMENT),
            CashFlow.of(ANCHOR, 0, Money.inr("15000"), FlowKind.INTEGRAL_FEE_RECEIVED),
            CashFlow.of(ANCHOR, 0, Money.inr("-10000"), FlowKind.INTEGRAL_COST_PAID),
            CashFlow.of(ANCHOR.plusMonths(1), 1, Money.inr("1010000"), FlowKind.COMBINED_EMI)));

        assertThat(SolveRequest.inceptionTarget(asset).amount()).isEqualByComparingTo(bd("995000"));
        assertThat(SolveRequest.atInception(asset, MONTHLY, null).target().amount())
            .isEqualByComparingTo(bd("995000"));
    }

    @Test
    @DisplayName("flows on the anchor date are the target and are never also discounted")
    void inceptionFlowsAreNotDiscounted() {
        // Including them on both sides would double the inception leg: the advance is the
        // target, and the twenty-four instalments are what is discounted to it.
        FlowVector vector = deepDiscount();

        assertThat(vector.atInception()).hasSize(1);
        assertThat(vector.future()).hasSize(24);
        assertThat(vector.size()).isEqualTo(25);
    }

    @Test
    @DisplayName("a target in another currency is a programming error and throws")
    void targetCurrencyMustMatchTheVector() {
        Money dollars = Money.of(bd("780000"), Currency.getInstance("USD"));

        assertThatThrownBy(() -> SolveRequest.of(deepDiscount(), dollars, MONTHLY, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("INR");
    }

    @Test
    @DisplayName("a seed at or below -100% is a programming error and throws")
    void seedMustExceedMinusOneHundredPercent() {
        // At -100% the compounding identity is meaningless and every discount factor is
        // undefined, so this cannot be reported as a data condition on a result: there
        // is no calculation to report a status about.
        assertThatThrownBy(() -> SolveRequest.of(deepDiscount(),
            Money.inr("780000"), MONTHLY, BigDecimal.ONE.negate()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must exceed -100%");
    }

    @Test
    @DisplayName("a mid-life re-solve takes its target explicitly rather than from the vector")
    void midLifeResolveTargetsTheCurrentCarryingAmount() {
        // 6.2, a benchmark reset: the anchor is the event date, the vector holds only
        // the remaining flows, and the target is the current gross carrying amount. A
        // vector of remaining flows has nothing dated on its anchor, so atInception
        // would solve it to a target of zero — which is why the two factories are
        // separate and named.
        FlowVector remaining = FlowVector.of(ANCHOR, Money.INR, List.of(
            CashFlow.of(ANCHOR.plusMonths(1), 13, Money.inr("47073.47"), FlowKind.COMBINED_EMI),
            CashFlow.of(ANCHOR.plusMonths(2), 14, Money.inr("47073.47"), FlowKind.COMBINED_EMI)));

        assertThat(SolveRequest.inceptionTarget(remaining).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(SolveRequest.of(remaining, Money.inr("528407.32"), MONTHLY, null).target().amount())
            .isEqualByComparingTo(bd("528407.32"));
    }

    @Test
    @DisplayName("the absolute tolerance is scale-relative to the target")
    void toleranceIsScaleRelative() {
        // 4.2 step 4: tol_abs = max(1e-10, |target| * 1e-16). A fixed absolute tolerance
        // cannot serve both a 50,000 consumer-durable loan and a 40,000,000,000
        // infrastructure facility.
        SolveRequest small = SolveRequest.of(deepDiscount(), Money.inr("50000"), MONTHLY, null);
        SolveRequest large = SolveRequest.of(deepDiscount(), Money.inr("40000000000"), MONTHLY, null);

        assertThat(small.absoluteTolerance()).isEqualByComparingTo(bd("1E-10"));
        assertThat(large.absoluteTolerance()).isEqualByComparingTo(bd("0.000004"));
        assertThat(large.absoluteTolerance()).isGreaterThan(small.absoluteTolerance());
        // Signed target, unsigned tolerance: a liability's target is negative and its
        // tolerance is not.
        assertThat(SolveRequest.of(deepDiscount(), Money.inr("-40000000000"), MONTHLY, null)
            .absoluteTolerance()).isEqualByComparingTo(bd("0.000004"));
    }

    @Test
    @DisplayName("the with-ers replace one element and keep the rest")
    void withersAreNonDestructive() {
        SolveRequest base = SolveRequest.of(deepDiscount(), Money.inr("780000"), MONTHLY, null);

        assertThat(base.hasSeed()).isFalse();
        assertThat(base.withSeed(bd("0.01")).hasSeed()).isTrue();
        assertThat(base.withSeed(bd("0.01")).target()).isEqualTo(base.target());
        assertThat(base.withTolerance(SolverTolerance.forTier(MaterialityTier.TIER_1)).tolerance())
            .isEqualTo(SolverTolerance.tightened());
        assertThat(base.withBand(PlausibleBand.of("0", "1")).band())
            .isEqualTo(new PlausibleBand(bd("0"), bd("1")));
        assertThat(base.withBand(PlausibleBand.of("0", "1")).flows()).isEqualTo(base.flows());
    }

    @Test
    @DisplayName("a result cannot carry a status and a rate that contradict each other")
    void resultEnforcesThePairingInBothDirections() {
        // Three fields are null exactly when the status carries no rate. A zero in any of
        // them would read as a successful solve of a zero-rate instrument, and a rate
        // sitting on a NO_SOLUTION would be the guessed figure this engine refuses to
        // produce — so the record refuses to be constructed either way.
        assertThatThrownBy(() -> SolveResult.solved(
            null, SolverMethod.NEWTON, 1, 0, BigDecimal.ZERO, List.of(), "no rate supplied"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must carry a rate");
        assertThatThrownBy(() -> new SolveResult(Rate.monthly(bd("0.01")), SolveStatus.NO_SOLUTION,
            SolverMethod.NEWTON, 0, 0, BigDecimal.ZERO, List.of(), "guessed"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must carry no rate");
        assertThatThrownBy(() -> SolveResult.multipleRoots(List.of(bd("0.1")), -1, "negative iterations"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("iterations must be non-negative");
        // A safeguard count above the iteration count would be a telemetry figure that
        // cannot have happened, and a monitoring signal nobody can trust is worse than
        // none — so the record refuses it rather than publishing it.
        assertThatThrownBy(() -> SolveResult.solved(Rate.monthly(bd("0.01")), SolverMethod.NEWTON,
            3, 4, BigDecimal.ZERO, List.of(), "more safeguards than iterations"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safeguardSteps must lie in [0, 3]");
    }

    @Test
    @DisplayName("candidate roots on a result are immutable")
    void candidateRootsAreDefensivelyCopied() {
        // The computation record is evidence. A caller that could mutate the candidate
        // list after the fact could rewrite what the disambiguation chose between.
        List<BigDecimal> mutable = new java.util.ArrayList<>(List.of(bd("0.1"), bd("0.4")));
        SolveResult result = SolveResult.multipleRoots(mutable, 4, "two roots");
        mutable.clear();

        assertThat(result.candidateRoots()).hasSize(2);
        assertThatThrownBy(() -> result.candidateRoots().add(bd("0.9")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
