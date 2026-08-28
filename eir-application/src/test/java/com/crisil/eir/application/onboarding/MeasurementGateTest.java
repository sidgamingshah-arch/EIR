package com.crisil.eir.application.onboarding;

import static com.crisil.eir.application.onboarding.OnboardingFixtures.DISBURSEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The measurement-category and SPPI gate (FR-103, FR-104), row by row against the CHECK constraints
 * in {@code V1__core_entities.sql}.
 *
 * <p>The gate is a decision table and it is tested as one: every combination of instrument class,
 * SPPI state and declared category that the schema admits, with the schema constraint each case
 * corresponds to named in the display name. That is a small number of cases and they are all here,
 * because the failure this gate exists to prevent — a rate produced for an instrument that should
 * not have one — leaves no trace anywhere downstream.
 */
class MeasurementGateTest {

    private static final String APPROVER = "classification.committee";

    @Nested
    @DisplayName("the asset side, where SPPI decides")
    class AssetSide {

        @Test
        @DisplayName("SPPI PASS and amortised cost declared: the EIR arises and the pipeline continues")
        void passAtAmortisedCostAdmitsTheContract() {
            MeasurementDecision decision = MeasurementGate.assess("C-PASS", InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST, SppiAssessment.passed(DISBURSEMENT, APPROVER));

            assertThat(decision.eirApplies())
                .as("an asset with a passed SPPI assessment at amortised cost is the ordinary"
                    + " EIR-bearing case (05 § 3.1's 'else Amortised cost or FVOCI' branch)")
                .isTrue();
            assertThat(decision.effectiveCategory()).isEqualTo(MeasurementCategory.AMORTISED_COST);
            assertThat(decision.isRefused()).isFalse();
            assertThat(decision.classificationInvariant())
                .as("ST-12 is asserted because the gate examined a real SPPI outcome")
                .isPresent();
            assertThat(decision.classificationInvariant().orElseThrow().satisfied()).isTrue();
        }

        @Test
        @DisplayName("SPPI PASS and FVOCI declared: FVOCI carries an EIR, so it is the same branch")
        void passAtFvociAlsoAdmitsTheContract() {
            MeasurementDecision decision = MeasurementGate.assess("C-FVOCI",
                InstrumentClass.INVESTMENT, MeasurementCategory.FVOCI,
                SppiAssessment.passed(DISBURSEMENT, APPROVER));

            assertThat(decision.eirApplies())
                .as("FVOCI recognises interest and fee amortisation through profit or loss on the"
                    + " effective-interest basis; only the remeasurement goes to OCI, which is why"
                    + " 05 § 3.1 brackets it with amortised cost")
                .isTrue();
            assertThat(decision.effectiveCategory()).isEqualTo(MeasurementCategory.FVOCI);
        }

        @Test
        @DisplayName("SPPI FAIL and FVTPL declared: excluded from EIR processing, and NOT an exception")
        void failAtFvtplExcludesWithoutRaisingAnException() {
            MeasurementDecision decision = MeasurementGate.assess("C-FAIL", InstrumentClass.LOAN,
                MeasurementCategory.FVTPL, SppiAssessment.failed(DISBURSEMENT, APPROVER));

            assertThat(decision.eirApplies())
                .as("FR-104: on the asset side an SPPI failure sends the whole instrument to FVTPL"
                    + " and no EIR arises")
                .isFalse();
            assertThat(decision.isExcludedFromEir()).isTrue();
            assertThat(decision.isRefused())
                .as("an SPPI failure is a measurement outcome, not a queue entry — filing it would"
                    + " put a correctly-measured instrument behind 04 § 3's close gate")
                .isFalse();
            assertThat(decision.refusal()).isNull();

            InvariantResult check = decision.classificationInvariant().orElseThrow();
            assertThat(check.id()).isEqualTo(InvariantId.ST_12);
            assertThat(check.satisfied())
                .as("the failure did yield no EIR, which is exactly what " + InvariantId.ST_12
                    + " claims")
                .isTrue();
        }

        @Test
        @DisplayName("SPPI FAIL and amortised cost declared: FVTPL anyway, and ST-12 breaches by one instrument")
        void failAtAmortisedCostBreachesStTwelveAndStillExcludes() {
            // The Java form of contract_sppi_fail_implies_fvtpl_ck. The two operands come from two
            // independent sources — the classification file says FAIL, the contract master says
            // AMORTISED_COST — which is the only reason a comparison between them can disagree at
            // all, and the only input that makes this control fail.
            MeasurementDecision decision = MeasurementGate.assess("C-DISAGREE",
                InstrumentClass.LOAN, MeasurementCategory.AMORTISED_COST,
                SppiAssessment.failed(DISBURSEMENT, APPROVER));

            assertThat(decision.effectiveCategory())
                .as("the assessment is decisive and the declared category is the thing being"
                    + " checked; FR-104 admits no bifurcation on the asset side")
                .isEqualTo(MeasurementCategory.FVTPL);
            assertThat(decision.eirApplies()).isFalse();

            InvariantResult check = decision.classificationInvariant().orElseThrow();
            assertThat(check.satisfied())
                .as("the contract master carries a category the assessment forbids")
                .isFalse();
            assertThat(check.deviation())
                .as("deviation is a count of instruments, not a money amount: there is no figure"
                    + " here, only a misclassified instrument")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(check.detail()).contains("contract_sppi_fail_implies_fvtpl_ck");
        }

        @Test
        @DisplayName("SPPI PASS and FVTPL declared: a passing assessment does not compel amortised cost")
        void passAtFvtplStaysAtFvtpl() {
            MeasurementDecision decision = MeasurementGate.assess("C-TRADING",
                InstrumentClass.INVESTMENT, MeasurementCategory.FVTPL,
                SppiAssessment.passed(DISBURSEMENT, APPROVER));

            assertThat(decision.eirApplies())
                .as("SPPI is necessary and not sufficient — the business-model test is the other"
                    + " limb, and a held-for-trading bond passes SPPI and is still FVTPL."
                    + " Promoting it would be the mirror image of the defect the gate prevents:"
                    + " an instrument that should be remeasured quietly accreting at a rate")
                .isFalse();
            assertThat(decision.effectiveCategory()).isEqualTo(MeasurementCategory.FVTPL);
            assertThat(decision.isRefused()).isFalse();
        }

        @Test
        @DisplayName("no assessment and amortised cost declared: MISSING_MANDATORY_FIELD, the row the migration's first live run accepted")
        void amortisedCostWithNoAssessmentIsRefused() {
            MeasurementDecision decision = MeasurementGate.assess("C-UNASSESSED",
                InstrumentClass.LOAN, MeasurementCategory.AMORTISED_COST, null);

            assertThat(decision.isRefused())
                .as("contract_amortised_cost_needs_sppi_ck uses IS NOT DISTINCT FROM precisely"
                    + " because 'sppi_outcome = PASS' is satisfied when the column is NULL; the"
                    + " Java analogue is a null-checked read that short-circuits to 'passed'")
                .isTrue();
            assertThat(decision.refusal())
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(decision.effectiveCategory())
                .as("the gate did not conclude, so there is no category to act on")
                .isNull();
            assertThat(decision.eirApplies())
                .as("an unconcluded gate licenses nothing; defaulting to 'carry on' is how a rate"
                    + " gets produced for an instrument that should not have one")
                .isFalse();
            assertThat(decision.classificationInvariant())
                .as("ST-12 has no subject when there is no SPPI outcome; a pass here would be a"
                    + " result no input could ever turn into a breach")
                .isEmpty();
        }

        @Test
        @DisplayName("no assessment and FVTPL declared: nothing needs assessing to stay out of the EIR regime")
        void fvtplWithNoAssessmentIsExcludedRatherThanRefused() {
            MeasurementDecision decision = MeasurementGate.assess("C-FVTPL-BARE",
                InstrumentClass.LOAN, MeasurementCategory.FVTPL, null);

            assertThat(decision.isRefused())
                .as("the first disjunct of contract_amortised_cost_needs_sppi_ck is"
                    + " measurement_category = 'FVTPL', so the schema accepts this row too")
                .isFalse();
            assertThat(decision.effectiveCategory()).isEqualTo(MeasurementCategory.FVTPL);
            assertThat(decision.eirApplies()).isFalse();
        }

        @Test
        @DisplayName("an off-balance-sheet commitment is on the asset side of the gate")
        void offBalanceSheetIsSubjectToSppi() {
            assertThat(InstrumentClass.OFF_BALANCE_SHEET.subjectToSppi())
                .as("only LIABILITY is exempted by contract_sppi_fail_implies_fvtpl_ck and"
                    + " contract_amortised_cost_needs_sppi_ck")
                .isTrue();

            MeasurementDecision decision = MeasurementGate.assess("C-COMMITMENT",
                InstrumentClass.OFF_BALANCE_SHEET, MeasurementCategory.AMORTISED_COST, null);
            assertThat(decision.refusal()).isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
        }
    }

    @Nested
    @DisplayName("the liability side, which FR-104 puts outside the test")
    class LiabilitySide {

        @Test
        @DisplayName("a liability at amortised cost with no assessment keeps its EIR (FR-105)")
        void liabilityNeedsNoAssessment() {
            MeasurementDecision decision = MeasurementGate.assess("D-DEPOSIT",
                InstrumentClass.LIABILITY, MeasurementCategory.AMORTISED_COST, null);

            assertThat(decision.eirApplies())
                .as("FR-105 bifurcates an embedded derivative to FVTPL and RETAINS an EIR on the"
                    + " host; an asset-side gate here would destroy a rate the requirement says"
                    + " must exist")
                .isTrue();
            assertThat(decision.isRefused()).isFalse();
            assertThat(decision.classificationInvariant())
                .as("no SPPI test applies, so ST-12 has nothing to say — reported as unasserted"
                    + " rather than published as a pass")
                .isEmpty();
        }

        @Test
        @DisplayName("a liability whose assessment says FAIL is not sent to FVTPL by it")
        void liabilityIsNotDrivenByASppiFailure() {
            MeasurementDecision decision = MeasurementGate.assess("D-STRUCTURED",
                InstrumentClass.LIABILITY, MeasurementCategory.AMORTISED_COST,
                SppiAssessment.failed(DISBURSEMENT, APPROVER));

            assertThat(decision.effectiveCategory())
                .as("contract_sppi_fail_implies_fvtpl_ck exempts LIABILITY explicitly: 'A liability"
                    + " is outside the SPPI test, so the liability side is exempted rather than"
                    + " forced'")
                .isEqualTo(MeasurementCategory.AMORTISED_COST);
            assertThat(decision.eirApplies()).isTrue();
            assertThat(decision.detail())
                .as("the assessment is recorded even though it decides nothing, because"
                    + " contract_sppi_complete_ck permits the triple on any instrument class")
                .contains("recorded but decides nothing");
        }

        @Test
        @DisplayName("a liability declared FVTPL still carries no EIR")
        void liabilityAtFvtplCarriesNoEir() {
            MeasurementDecision decision = MeasurementGate.assess("D-TRADING",
                InstrumentClass.LIABILITY, MeasurementCategory.FVTPL, null);

            assertThat(decision.eirApplies()).isFalse();
            assertThat(decision.isExcludedFromEir()).isTrue();
        }
    }

    @Nested
    @DisplayName("the assessment triple: all three or none")
    class AssessmentCompleteness {

        @Test
        @DisplayName("an outcome with a blank approver is refused, not recorded")
        void aBlankApproverIsRefused() {
            assertThatThrownBy(() ->
                new SppiAssessment(SppiOutcome.PASS, DISBURSEMENT, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an assessment, it")
                .hasMessageContaining("contract_sppi_complete_ck");
        }

        @Test
        @DisplayName("an outcome with no date cannot be constructed at all")
        void aMissingDateIsRefused() {
            assertThatThrownBy(() -> new SppiAssessment(SppiOutcome.FAIL, null, "somebody"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the approver is stripped, so a whitespace variant is not a second person")
        void theApproverIsNormalised() {
            assertThat(new SppiAssessment(SppiOutcome.PASS, DISBURSEMENT, "  a.person  ").approver())
                .isEqualTo("a.person");
        }
    }

    @Nested
    @DisplayName("a decision states exactly one of a category and a refusal")
    class DecisionShape {

        @Test
        @DisplayName("neither is refused at construction")
        void neitherConcludedNorRefusedIsRejected() {
            assertThatThrownBy(() -> new MeasurementDecision("C-1", InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST, null, null, null, "detail", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("it is neither");
        }

        @Test
        @DisplayName("both is refused at construction")
        void bothConcludedAndRefusedIsRejected() {
            assertThatThrownBy(() -> new MeasurementDecision("C-1", InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST, null, MeasurementCategory.FVTPL,
                ExceptionCategory.MISSING_MANDATORY_FIELD, "detail", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("it is both");
        }

        @Test
        @DisplayName("an invariant result under any id but ST-12 is refused")
        void theWrongInvariantIdIsRejected() {
            assertThatThrownBy(() -> new MeasurementDecision("C-1", InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST, null, MeasurementCategory.FVTPL, null, "detail",
                InvariantResult.pass(InvariantId.IC_1, "wrong id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(InvariantId.ST_12.toString());
        }
    }
}
