package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-204: the numeric definition of "probable drawdown", per product, and the three ways it can
 * fail to be applied.
 *
 * <p>Every expected classification here is derived from the stated threshold by reading the
 * policy sentence — "probable where the assessment is at least the threshold" — and never from
 * running the policy. The boundary cases are the point of the file: a threshold is a cliff, and
 * an off-by-one at it reclassifies a whole product's commitment fees into the other treatment
 * without changing a single figure anyone reconciles.
 */
class CommitmentFeePolicyTest {

    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2027, 4, 1);
    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 3, 15);

    /** Home loans draw at 60% or more historically; the threshold is written {@code 0.60}. */
    private static final BigDecimal HOME_LOAN_THRESHOLD = new BigDecimal("0.60");

    private static PolicyVersion thresholdVersion() {
        return new PolicyVersion(
            "CFT-2027.1", PolicyKind.COMMITMENT_THRESHOLD,
            "drawdown-probability thresholds from three years of product drawdown rates",
            EFFECTIVE_FROM, "policy.author", "accounting.policy.owner", APPROVED_ON,
            PolicyVersionStatus.EFFECTIVE);
    }

    /**
     * Four products chosen to cover the interior and both endpoints of the threshold domain.
     * PROJECT-FINANCE at 1 says "only a certainty counts as probable" and RETAIL-OD at 0 says
     * "every commitment on this product is probable"; both are legitimate policy positions and
     * both are unreachable under one of the two boundary conventions.
     */
    private static CommitmentFeePolicy policy() {
        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        thresholds.put("HOME-LOAN", HOME_LOAN_THRESHOLD);
        thresholds.put("WCDL", new BigDecimal("0.50"));
        thresholds.put("PROJECT-FINANCE", BigDecimal.ONE);
        thresholds.put("RETAIL-OD", BigDecimal.ZERO);
        return CommitmentFeePolicy.of(thresholdVersion(), thresholds);
    }

    @Nested
    @DisplayName("the threshold boundary, which is inclusive")
    class Boundary {

        @Test
        @DisplayName("a probability exactly at the threshold is probable, so the fee is INTEGRAL")
        void equalToThresholdIsProbable() {
            // The threshold is the numeric definition of a word, and the sentence reads "at
            // least". A policy evidencing 0.60 from historical drawdown rates is saying that a
            // facility assessed at 60% draws; classifying it OVER_COMMITMENT_PERIOD would take to
            // income over the commitment period a fee ACPIR 52 defers into the loan's EIR.
            CommitmentFeeDecision decision =
                policy().classify("HOME-LOAN", "COMMITMENT_FEE", new BigDecimal("0.60"));

            assertThat(decision.classification())
                .as("0.60 against a threshold of 0.60 is at the threshold and therefore probable")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(decision.drawdownProbable()).isTrue();
            assertThat(decision.threshold())
                .as("the threshold tested against is retained for the audit trail")
                .isEqualByComparingTo(HOME_LOAN_THRESHOLD);
        }

        @Test
        @DisplayName("one unit in the last place below the threshold is not probable")
        void justBelowThresholdIsNotProbable() {
            // 0.599999999999 is a twelve-decimal-place assessment one ulp below 0.60 at the
            // engine's rate scale. The pair with the test above pins the cliff: these two inputs
            // differ by 1e-12 and must land in different treatments, and any implementation that
            // put them in the same one has moved the boundary.
            CommitmentFeeDecision decision =
                policy().classify("HOME-LOAN", "COMMITMENT_FEE", new BigDecimal("0.599999999999"));

            assertThat(decision.classification())
                .as("below the definition of probable, so IFRS 9 B5.4.3(b) applies")
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
            assertThat(decision.drawdownProbable()).isFalse();
            assertThat(decision.detail()).contains("is below 0.60");
        }

        @Test
        @DisplayName("scale is not value: 0.6 and 0.600 both sit at a threshold of 0.60")
        void comparisonIsByValueNotByEquals() {
            // The defect this catches: BigDecimal.equals("0.6", "0.60") is false, so an equality
            // test at the boundary falls through to the below-threshold branch and reclassifies
            // precisely the fees that sit on the policy line. Source systems store the same
            // assessment at whatever scale their column happens to have.
            CommitmentFeePolicy policy = policy();

            assertThat(policy.classify("HOME-LOAN", "CF", new BigDecimal("0.6")).classification())
                .as("0.6 is the same probability as the 0.60 threshold")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(policy.classify("HOME-LOAN", "CF", new BigDecimal("0.600")).classification())
                .as("0.600 likewise")
                .isEqualTo(FeeClassification.INTEGRAL);
        }

        @Test
        @DisplayName("a threshold of 1 is satisfiable — the endpoint a strict test would lose")
        void certaintyOnlyThresholdIsReachable() {
            // This is the concrete argument for the inclusive rule. Probabilities are confined to
            // [0,1] by FeePosting, so under a strict > test no admissible assessment could ever
            // meet a threshold of 1: every commitment fee on PROJECT-FINANCE would route to
            // OVER_COMMITMENT_PERIOD while the approved policy table said certainty counts as
            // probable, and nothing in the output would show the threshold was unmeetable.
            CommitmentFeePolicy policy = policy();

            assertThat(policy.classify("PROJECT-FINANCE", "CF", BigDecimal.ONE).classification())
                .as("a certainty meets a certainty-only threshold")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(policy.classify("PROJECT-FINANCE", "CF", new BigDecimal("0.999999999999"))
                .classification())
                .as("all but certain is still not certain")
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
        }

        @Test
        @DisplayName("a threshold of 0 makes every assessment probable, including 0")
        void everythingProbableThresholdIsReachable() {
            // The other endpoint, and the deliberate reading of a zero threshold: the product's
            // policy is that commitment fees here always defer into the resulting loan. Zero is
            // "at least zero", so even an assessment of zero classifies INTEGRAL — which is the
            // policy position the table is stating, not an accident of the comparison.
            assertThat(policy().classify("RETAIL-OD", "CF", BigDecimal.ZERO).classification())
                .as("at least zero, on a product whose threshold is zero")
                .isEqualTo(FeeClassification.INTEGRAL);
        }
    }

    @Nested
    @DisplayName("obligation 1: the assessment must be present and numeric")
    class AssessmentPresence {

        @Test
        @DisplayName("no drawdown_probability is refused, not read as a low probability")
        void absentAssessmentIsRefused() {
            CommitmentFeeDecision decision = policy().classify("HOME-LOAN", "COMMITMENT_FEE", null);

            assertThat(decision.isRefused()).isTrue();
            assertThat(decision.refusal())
                .as("an unassessed commitment fee is not a low-probability one")
                .isEqualTo(CommitmentFeeRefusal.PROBABILITY_NOT_ASSESSED);
            assertThat(decision.classification())
                .as("no treatment at all; both defaults would be wrong and neither leaves a trace")
                .isNull();
            assertThat(decision.exceptionCategory())
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(decision.refusal().stopsTheContract())
                .as("FR-905: a hard stop for that contract, never a silent default")
                .isTrue();
            assertThat(decision.threshold())
                .as("the threshold that was available is still retained, or the queue entry is"
                    + " unactionable")
                .isEqualByComparingTo(HOME_LOAN_THRESHOLD);
        }

        @Test
        @DisplayName("reading the classification off a refusal throws rather than inventing one")
        void refusalHasNoClassification() {
            CommitmentFeeDecision decision = policy().classify("HOME-LOAN", "CF", null);

            assertThatIllegalStateException()
                .isThrownBy(decision::classificationOrThrow)
                .withMessageContaining("PROBABILITY_NOT_ASSESSED");
        }

        @Test
        @DisplayName("a refused decision cannot be deferred into a recognition pattern")
        void refusalCannotDefer() {
            // The hard stop has to hold all the way through. A refusal that quietly produced a
            // deferral would recognise the fee on some pattern nobody chose.
            CommitmentFeeDecision decision = policy().classify("HOME-LOAN", "CF", null);

            assertThatIllegalStateException()
                .isThrownBy(() -> decision.defer(Money.inr("100000.00"),
                    LocalDate.of(2027, 4, 1), LocalDate.of(2028, 4, 1)));
        }
    }

    @Nested
    @DisplayName("obligation 2: the threshold is per product and its absence is a gap")
    class ThresholdLookup {

        @Test
        @DisplayName("a product the table does not define is refused, not given a global default")
        void unknownProductIsRefused() {
            // Gold loans have their own ACPIR 82 floor category and must never be grouped under
            // secured retail (FR-106); the same separateness is why their drawdown behaviour
            // cannot borrow another product's threshold. A global fallback would classify this fee
            // and leave no sign that the table had never been extended.
            CommitmentFeeDecision decision =
                policy().classify("GOLD-LOAN", "COMMITMENT_FEE", new BigDecimal("0.90"));

            assertThat(decision.refusal())
                .isEqualTo(CommitmentFeeRefusal.THRESHOLD_NOT_DEFINED_FOR_PRODUCT);
            assertThat(decision.exceptionCategory())
                .contains(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(decision.assessedProbability())
                .as("the assessment that could not be tested is retained")
                .isEqualByComparingTo(new BigDecimal("0.90"));
            assertThat(decision.threshold()).isNull();
            assertThat(decision.detail())
                .as("the queue entry names the policy version whose table has the gap")
                .contains("CFT-2027.1");
        }

        @Test
        @DisplayName("thresholds are per product, so two products classify one probability apart")
        void thresholdIsKeyedOnProduct() {
            // 0.55 is above WCDL's 0.50 and below HOME-LOAN's 0.60. One assessment, two products,
            // two treatments — which is the whole reason the threshold is a keyed lookup and not a
            // constant, and it is asserted rather than assumed because a constant would make both
            // of these agree and nothing else in the output would look different.
            CommitmentFeePolicy policy = policy();
            BigDecimal assessment = new BigDecimal("0.55");

            assertThat(policy.classify("WCDL", "CF", assessment).classification())
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(policy.classify("HOME-LOAN", "CF", assessment).classification())
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
        }

        @Test
        @DisplayName("both gaps at once reports the missing assessment first")
        void assessmentIsReportedBeforeThreshold() {
            // Order is a deliberate choice, not an accident of the if-chain: the assessment is
            // what the front office can supply against this facility, while the threshold is a
            // policy change with a maker and a checker. A queue entry naming both would be closed
            // by fixing whichever was cheaper.
            CommitmentFeeDecision decision = policy().classify("GOLD-LOAN", "CF", null);

            assertThat(decision.refusal())
                .isEqualTo(CommitmentFeeRefusal.PROBABILITY_NOT_ASSESSED);
        }

        @Test
        @DisplayName("product ids are matched case- and whitespace-insensitively")
        void productIdIsNormalised() {
            // The same product arrives as "WCDL" from the core banking extract and " wcdl " from a
            // spreadsheet-fed limits system. Two spellings would be one threshold and one refusal.
            CommitmentFeePolicy policy = policy();

            assertThat(policy.thresholdFor(" wcdl "))
                .as("the lookup normalises before keying")
                .hasValueSatisfying(t -> assertThat(t).isEqualByComparingTo("0.50"));
            assertThat(policy.classify(" wcdl ", "CF", new BigDecimal("0.55")).productId())
                .as("and the decision records the normalised form, so two spellings report as one")
                .isEqualTo("WCDL");
        }

        @Test
        @DisplayName("no product is not a product")
        void blankProductIsCallerDefect() {
            // Not a refusal: a queue entry keyed on the empty string is not actionable, and the
            // caller resolving the contract knows its product. This is its defect, so it is loud.
            CommitmentFeePolicy policy = policy();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.classify("   ", "CF", new BigDecimal("0.55")))
                .withMessageContaining("no threshold for no product");
        }
    }

    @Nested
    @DisplayName("the table is a versioned artefact")
    class TableConstruction {

        @Test
        @DisplayName("only a COMMITMENT_THRESHOLD version may carry these thresholds")
        void kindIsEnforced() {
            // PolicyKind exists to keep the clocks separate: binding the thresholds to a
            // FEE_RULE_SET version would force a product repricing to re-approve them, and every
            // forced re-approval is an invitation to approve without reading.
            PolicyVersion feeRules = new PolicyVersion(
                "FEE-2027.1", PolicyKind.FEE_RULE_SET, "ACPIR 53 selling-agent split",
                EFFECTIVE_FROM, "policy.author", "accounting.policy.owner", APPROVED_ON,
                PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitmentFeePolicy.of(feeRules,
                    Map.of("HOME-LOAN", HOME_LOAN_THRESHOLD)))
                .withMessageContaining("COMMITMENT_THRESHOLD");
        }

        @Test
        @DisplayName("a definition of probable that is not a probability is refused")
        void thresholdDomainIsEnforced() {
            PolicyVersion version = thresholdVersion();

            assertThatIllegalArgumentException()
                .as("above one: no assessment could ever meet it")
                .isThrownBy(() -> CommitmentFeePolicy.of(version,
                    Map.of("HOME-LOAN", new BigDecimal("1.5"))))
                .withMessageContaining("must be a probability");
            assertThatIllegalArgumentException()
                .as("below zero: every assessment meets it, so the product has no test at all")
                .isThrownBy(() -> CommitmentFeePolicy.of(version,
                    Map.of("HOME-LOAN", new BigDecimal("-0.01"))))
                .withMessageContaining("must be a probability");
        }

        @Test
        @DisplayName("two spellings of one product with different thresholds is a table defect")
        void conflictingThresholdsAreRefused() {
            // Without this the surviving threshold would depend on map iteration order, and the
            // same table would classify the same fee differently between runs — the opposite of
            // deterministic replay (DT-1).
            PolicyVersion version = thresholdVersion();
            Map<String, BigDecimal> conflicting = new LinkedHashMap<>();
            conflicting.put("WCDL", new BigDecimal("0.50"));
            conflicting.put("wcdl", new BigDecimal("0.70"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitmentFeePolicy.of(version, conflicting))
                .withMessageContaining("two different thresholds");
        }

        @Test
        @DisplayName("effectiveness is reported, not used to gate classification")
        void effectivenessIsAQuery() {
            // A DRAFT table must still classify, or FR-210's impact preview — what does this
            // change do to the book — cannot be produced for it. Version selection belongs to the
            // registry that resolves versions by date.
            CommitmentFeePolicy policy = policy();

            assertThat(policy.governs(EFFECTIVE_FROM)).isTrue();
            assertThat(policy.governs(EFFECTIVE_FROM.minusDays(1)))
                .as("approved in March to take effect in April is not in force in March")
                .isFalse();
            assertThat(policy.version().kind()).isEqualTo(PolicyKind.COMMITMENT_THRESHOLD);
        }

        @Test
        @DisplayName("the product list reads the same on every run, whatever map it was built from")
        void productOrderIsDeterministic() {
            // DT-1. Map.of randomises its iteration with a per-JVM salt, so a table that inherited
            // the caller's order would list its products differently on two runs of the same close
            // — and the product count and product list reach the operator inside an
            // exception-queue entry, which is a record and cannot vary between identical runs.
            // Ascending id order is established by the table itself, so both spellings of the same
            // table agree.
            CommitmentFeePolicy fromLinked = policy();
            CommitmentFeePolicy fromUnordered = CommitmentFeePolicy.of(thresholdVersion(),
                Map.of("RETAIL-OD", BigDecimal.ZERO, "WCDL", new BigDecimal("0.50"),
                    "PROJECT-FINANCE", BigDecimal.ONE, "HOME-LOAN", HOME_LOAN_THRESHOLD));

            assertThat(fromLinked.products())
                .containsExactly("HOME-LOAN", "PROJECT-FINANCE", "RETAIL-OD", "WCDL");
            assertThat(fromUnordered.products())
                .as("same four products, built from a map with no order of its own")
                .containsExactlyElementsOf(fromLinked.products());
        }
    }

    @Nested
    @DisplayName("reading a FeePosting")
    class FromPosting {

        private static final LocalDate POSTED_ON = LocalDate.of(2027, 4, 15);

        @Test
        @DisplayName("the posting's assessment is the input, and its own classification is not")
        void postingClassificationIsNotRedecided() {
            // FeePosting's classification arrives already resolved by the rule set, keyed on
            // (fee_code, product, entity, effective_date). This policy answers a different
            // question — is drawdown probable — and re-deciding the rule set's answer here would
            // put one judgement in two places and licence them to disagree. The posting below
            // carries a deliberately wrong AS_INCURRED and the drawdown test still runs.
            FeePosting posting = FeePosting.commitment("COMMITMENT_FEE", Money.inr("250000.00"),
                POSTED_ON, FeeClassification.AS_INCURRED, new BigDecimal("0.75"));

            CommitmentFeeDecision decision = policy().classify("HOME-LOAN", posting);

            assertThat(decision.classification())
                .as("0.75 is at least 0.60, whatever classification the posting arrived carrying")
                .isEqualTo(FeeClassification.INTEGRAL);
            assertThat(decision.feeCode()).isEqualTo("COMMITMENT_FEE");
        }

        @Test
        @DisplayName("a commitment fee built through the general factory carries no assessment")
        void generalFactoryPostingIsRefused() {
            // FeePosting.commitment refuses a null assessment, so the refusal is unreachable
            // through it. The general factories leave the attribute null, because it is
            // meaningless on the overwhelming majority of fees — which is exactly how a commitment
            // fee reaches the engine unassessed.
            FeePosting posting = FeePosting.received("COMMITMENT_FEE", Money.inr("250000.00"),
                POSTED_ON, FeeClassification.OVER_COMMITMENT_PERIOD);

            assertThat(posting.drawdownProbability()).isNull();
            assertThat(policy().classify("HOME-LOAN", posting).refusal())
                .isEqualTo(CommitmentFeeRefusal.PROBABILITY_NOT_ASSESSED);
        }
    }

    @Nested
    @DisplayName("the decision as an audit record")
    class DecisionRecord {

        @Test
        @DisplayName("a classified decision names both figures and the policy version")
        void describeIsSelfContained() {
            // The audit answer to "why is this fee in the carrying amount" has to survive two
            // years and two threshold changes, so it is the assessment, the threshold it was
            // tested against and the version that supplied it — not just the outcome.
            String described =
                policy().classify("HOME-LOAN", "COMMITMENT_FEE", new BigDecimal("0.72")).describe();

            assertThat(described)
                .contains("COMMITMENT_FEE")
                .contains("HOME-LOAN")
                .contains("INTEGRAL")
                .contains("0.72")
                .contains("0.60")
                .contains("CFT-2027.1");
        }

        @Test
        @DisplayName("a decision cannot hold both a classification and a refusal")
        void exactlyOneOutcome() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeDecision("CF", "HOME-LOAN",
                    new BigDecimal("0.72"), HOME_LOAN_THRESHOLD, FeeClassification.INTEGRAL,
                    CommitmentFeeRefusal.PROBABILITY_NOT_ASSESSED, "CFT-2027.1", "both"))
                .withMessageContaining("exactly one");
            assertThatIllegalArgumentException()
                .as("and neither is no decision at all")
                .isThrownBy(() -> new CommitmentFeeDecision("CF", "HOME-LOAN", null, null, null,
                    null, "CFT-2027.1", "neither"))
                .withMessageContaining("exactly one");
        }

        @Test
        @DisplayName("the drawdown test yields two classifications and no third")
        void onlyTheTwoOutcomes() {
            // A commitment fee reaching AS_INCURRED or SEPARATE_SERVICE means the drawdown test
            // was bypassed rather than applied, and the record refuses to represent it.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitmentFeeDecision.classified("CF", "HOME-LOAN",
                    new BigDecimal("0.72"), HOME_LOAN_THRESHOLD, FeeClassification.AS_INCURRED,
                    "CFT-2027.1", "wrong"))
                .withMessageContaining("INTEGRAL or OVER_COMMITMENT_PERIOD");
        }
    }
}
