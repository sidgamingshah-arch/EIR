package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * The threshold test across the whole {@code [0,1]} grid, against an oracle that shares none of
 * its arithmetic.
 *
 * <p>Probabilities are generated as integer tenths of a percent and scaled with
 * {@link BigDecimal#movePointLeft}, which is exact. The oracle then compares the two
 * <em>integers</em>: 725 against 600 rather than 0.725 against 0.60. That is the point of the
 * exercise — a property whose expectation is computed by the same {@code BigDecimal.compareTo}
 * chain as the code under test would agree with a wrong boundary as readily as a right one.
 */
class CommitmentFeeThresholdPropertiesTest {

    private static final String PRODUCT = "WCDL";

    private static CommitmentFeePolicy policyWithThreshold(int thresholdPerMille) {
        PolicyVersion version = new PolicyVersion(
            "CFT-2027.1", PolicyKind.COMMITMENT_THRESHOLD, "generated threshold",
            LocalDate.of(2027, 4, 1), "policy.author", "accounting.policy.owner",
            LocalDate.of(2027, 3, 15), PolicyVersionStatus.EFFECTIVE);
        return CommitmentFeePolicy.of(version,
            Map.of(PRODUCT, BigDecimal.valueOf(thresholdPerMille).movePointLeft(3)));
    }

    /**
     * The classification is {@code INTEGRAL} exactly where the assessment is at least the
     * threshold, and {@code OVER_COMMITMENT_PERIOD} everywhere else. Never a refusal, never a
     * third classification, on any pair in the grid.
     */
    @Property
    void classificationFollowsTheIntegerComparison(
        @ForAll @IntRange(min = 0, max = 1000) int thresholdPerMille,
        @ForAll @IntRange(min = 0, max = 1000) int assessmentPerMille) {

        CommitmentFeeDecision decision = policyWithThreshold(thresholdPerMille)
            .classify(PRODUCT, "COMMITMENT_FEE",
                BigDecimal.valueOf(assessmentPerMille).movePointLeft(3));

        FeeClassification expected = assessmentPerMille >= thresholdPerMille
            ? FeeClassification.INTEGRAL
            : FeeClassification.OVER_COMMITMENT_PERIOD;

        assertThat(decision.isRefused())
            .as("both figures were present, so nothing can refuse")
            .isFalse();
        assertThat(decision.classification())
            .as("assessment " + assessmentPerMille + "/1000 against threshold "
                + thresholdPerMille + "/1000")
            .isEqualTo(expected);
    }

    /**
     * Every threshold in the domain is satisfiable, because the interval is closed at 1 and the
     * boundary is inclusive.
     *
     * <p>The property a strict {@code >} test fails, and it fails it at exactly one point — a
     * threshold of 1 — which is why it survives a hand-picked example set. A product whose policy
     * says "only a certainty counts as probable" would route every commitment fee to
     * {@code OVER_COMMITMENT_PERIOD} with nothing in the output to show the threshold could never
     * be met.
     */
    @Property
    void certaintyMeetsEveryThreshold(
        @ForAll @IntRange(min = 0, max = 1000) int thresholdPerMille) {
        CommitmentFeeDecision decision = policyWithThreshold(thresholdPerMille)
            .classify(PRODUCT, "COMMITMENT_FEE", BigDecimal.ONE);

        assertThat(decision.classification())
            .as("a certain drawdown is probable under any definition of probable")
            .isEqualTo(FeeClassification.INTEGRAL);
    }

    /**
     * Trailing zeros do not move a fee between treatments.
     *
     * <p>{@code 0.6} and {@code 0.600000} are one probability and two {@code BigDecimal}s, and
     * source systems store the assessment at whatever scale their column has. An equality-based
     * comparison passes this everywhere except on the boundary, which is the only place it
     * matters.
     */
    @Property
    void classificationIsScaleInvariant(
        @ForAll @IntRange(min = 0, max = 1000) int thresholdPerMille,
        @ForAll @IntRange(min = 0, max = 1000) int assessmentPerMille,
        @ForAll @IntRange(min = 0, max = 6) int extraScale) {

        CommitmentFeePolicy policy = policyWithThreshold(thresholdPerMille);
        BigDecimal assessment = BigDecimal.valueOf(assessmentPerMille).movePointLeft(3);

        BigDecimal rescaled = assessment.setScale(3 + extraScale);

        assertThat(policy.classify(PRODUCT, "CF", rescaled).classification())
            .as("the same probability written to " + (3 + extraScale) + " decimal places")
            .isEqualTo(policy.classify(PRODUCT, "CF", assessment).classification());
    }

    /**
     * Raising the assessment never moves a fee out of {@code INTEGRAL}.
     *
     * <p>Monotonicity is what makes the threshold a threshold. Its failure mode is not a wrong
     * figure but an incoherent policy — two facilities on one product, the more likely to draw
     * treated as the less likely — and it is the shape of defect a reversed comparison or a
     * subtraction with the operands the wrong way round produces.
     */
    @Property
    void moreProbableIsNeverLessIntegral(
        @ForAll @IntRange(min = 0, max = 1000) int thresholdPerMille,
        @ForAll @IntRange(min = 0, max = 1000) int lowerPerMille,
        @ForAll @IntRange(min = 0, max = 1000) int higherPerMille) {

        int low = Math.min(lowerPerMille, higherPerMille);
        int high = Math.max(lowerPerMille, higherPerMille);
        CommitmentFeePolicy policy = policyWithThreshold(thresholdPerMille);

        boolean lowIsIntegral = policy
            .classify(PRODUCT, "CF", BigDecimal.valueOf(low).movePointLeft(3))
            .drawdownProbable();
        boolean highIsIntegral = policy
            .classify(PRODUCT, "CF", BigDecimal.valueOf(high).movePointLeft(3))
            .drawdownProbable();

        assertThat(lowIsIntegral && !highIsIntegral)
            .as(low + "/1000 probable but " + high + "/1000 not, against threshold "
                + thresholdPerMille + "/1000")
            .isFalse();
    }
}
