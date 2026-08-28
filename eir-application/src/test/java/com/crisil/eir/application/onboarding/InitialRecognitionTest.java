package com.crisil.eir.application.onboarding;

import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE1_EIR_EFFECTIVE_ANNUAL_PERCENT;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE1_EIR_PERIODIC;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE1_INITIAL_GCA;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.DISBURSEMENT;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.NET_INTEGRAL_FEE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.PENAL_CHARGE_CODE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.PROCESSING_FEE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.UNMAPPED_CODE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.boundary;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Request;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Terms;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.feeRules;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.paise;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.percent;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.sppiFailingRequest;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.tierGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingSolver;
import com.crisil.eir.application.onboarding.OnboardingFixtures.IcBreachingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.NoSolutionSolver;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The initial-recognition pipeline of 05 § 3.1, and above all its <b>ordering</b>.
 *
 * <p>The test that matters most in this class is
 * {@link TheOrderingIsTheControl#nothingIsProjectedAndNothingIsSolvedForAnSppiFailingContract()}. It
 * asserts, on counting fakes wrapped around the real {@code eir-calc} seams, that a contract whose
 * SPPI assessment failed causes <b>zero</b> calls to the projector and <b>zero</b> calls to the
 * solver. 05 § 3.1: "Doing expensive work before that check is waste, and worse, produces a rate for
 * an instrument that should not have one."
 *
 * <p>The other assertions in that nest are the same claim reached from three more directions — the
 * work record, ST-12's work leg, and the outcome having no rate at all — because the ordering is the
 * one property of this unit that nothing downstream can detect if it is wrong.
 */
class InitialRecognitionTest {

    private static final String RUN = "RUN-2026-04";

    @Nested
    @DisplayName("*** the ordering is the control ***")
    class TheOrderingIsTheControl {

        @Test
        @DisplayName("an SPPI-failing contract causes ZERO projections and ZERO solves")
        void nothingIsProjectedAndNothingIsSolvedForAnSppiFailingContract() {
            CountingProjector projector = new CountingProjector();
            CountingSolver solver = new CountingSolver();
            InitialRecognition pipeline =
                new InitialRecognition(feeRules(), tierGate(), projector, solver);

            OnboardingOutcome outcome =
                pipeline.onboard(RUN, boundary(), sppiFailingRequest("C-SPPI-FAIL"));

            // THE assertion. Not "the outcome carries no rate" — a pipeline that projected, solved
            // and discarded the answer would satisfy that and would still be the defect 05 § 3.1
            // names. The collaborators were never called.
            assertThat(projector.calls())
                .as("05 § 3.1: the SPPI/measurement gate runs FIRST, before any projection. The"
                    + " projector must not have been invoked at all for an instrument that failed"
                    + " it")
                .isZero();
            assertThat(solver.calls())
                .as("05 § 3.1: ... before any projection or solve. An SPPI failure is a cliff, not"
                    + " a gradient: there is no reduced or provisional EIR on the far side of it,"
                    + " so the solver must not have been invoked")
                .isZero();
            assertThat(solver.lastRequest())
                .as("the same fact from the other side: no solve request was ever assembled")
                .isNull();
        }

        @Test
        @DisplayName("its work record reads exactly [MEASUREMENT_GATE] — the gate ran and nothing else did")
        void theWorkRecordShowsOnlyTheGate() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(),
                sppiFailingRequest("C-SPPI-FAIL"));

            assertThat(outcome.workPerformed())
                .as("the work record is the ordering made observable; a comment claiming the gate"
                    + " runs first is not a control because nothing about it can fail")
                .containsExactly(OnboardingWork.MEASUREMENT_GATE);
            assertThat(outcome.expensiveWorkPerformed()).isFalse();
            assertThat(outcome.performed(OnboardingWork.FEE_CLASSIFICATION))
                .as("fee classification is a lookup per posting against a dated, precedence-ordered"
                    + " rule set, and it is not free either")
                .isFalse();
            assertThat(outcome.performed(OnboardingWork.TIER_ASSIGNMENT)).isFalse();
        }

        @Test
        @DisplayName("ST-12's work leg passes for it, and its deviation is nil")
        void stTwelveHoldsForTheExcludedContract() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(),
                sppiFailingRequest("C-SPPI-FAIL"));

            InvariantResult stTwelve = only(outcome.invariants(), InvariantId.ST_12);
            assertThat(stTwelve.satisfied()).isTrue();
            assertThat(stTwelve.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stTwelve.detail())
                .as("the detail states both legs: the classification held and no expensive stage"
                    + " ran")
                .contains("no projection or solve was performed");
            assertThat(outcome.invariants())
                .as("IC-1 is absent, and that is correct: a contract that was never projected has"
                    + " no initial carrying amount for IC-1 to be about")
                .hasSize(1);
        }

        @Test
        @DisplayName("ST-12's work leg CAN fail: an excluded contract carrying a projection and a solve breaches by 2")
        void stTwelveBreachesWhereExpensiveWorkRanForAnExcludedContract() {
            // The control has an input that makes it fail, and this is it. An outcome for an
            // excluded contract, assembled with the two expensive stages recorded and their
            // artefacts attached — which is what a pipeline that gated after projecting would
            // produce. OnboardingOutcome deliberately does not refuse this: a control whose breach
            // cannot be constructed cannot be tested, and this codebase has shipped several of
            // those.
            OnboardingOutcome honest = pipeline().onboard(RUN, boundary(), case1Request("C-REAL"));
            MeasurementDecision excluded = MeasurementGate.assess(sppiFailingRequest("C-LATE"));

            OnboardingOutcome mistimed = new OnboardingOutcome("C-LATE", excluded, List.of(), null,
                honest.projection(), honest.solve(),
                List.of(OnboardingWork.MEASUREMENT_GATE, OnboardingWork.PROJECTION,
                    OnboardingWork.SOLVE),
                List.of(), null);

            InvariantResult stTwelve = only(mistimed.invariants(), InvariantId.ST_12);
            assertThat(stTwelve.satisfied())
                .as("a projection and a solve ran for an instrument the gate excluded")
                .isFalse();
            assertThat(stTwelve.deviation())
                .as("deviation is the count of expensive stages that ran: PROJECTION and SOLVE, so"
                    + " two. A count and never a signed sum, so two breaches cannot net to a pass")
                .isEqualByComparingTo(new BigDecimal("2"));
            assertThat(stTwelve.detail()).contains("PROJECTION", "SOLVE");
        }

        @Test
        @DisplayName("an outcome whose first stage is not the gate cannot be constructed")
        void aMisOrderedWorkRecordIsRefusedAtConstruction() {
            MeasurementDecision decision = MeasurementGate.assess(case1Request("C-BACKWARDS"));

            assertThatThrownBy(() -> new OnboardingOutcome("C-BACKWARDS", decision, List.of(),
                null, null, null,
                List.of(OnboardingWork.PROJECTION, OnboardingWork.MEASUREMENT_GATE),
                List.of(), null))
                .as("a mis-ordered pipeline is a defect in this module, not a malformed contract,"
                    + " so it fails loudly in one place rather than producing ten million queue"
                    + " entries with one cause")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runs FIRST");
        }

        @Test
        @DisplayName("an admitted contract projects exactly once and solves exactly once")
        void anAdmittedContractConsumesEachExpensiveStageOnce() {
            CountingProjector projector = new CountingProjector();
            CountingSolver solver = new CountingSolver();
            InitialRecognition pipeline =
                new InitialRecognition(feeRules(), tierGate(), projector, solver);

            OnboardingOutcome outcome = pipeline.onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(projector.calls())
                .as("once, not twice: a second projection would be a second answer to the same"
                    + " question and the two would eventually differ")
                .isEqualTo(1);
            assertThat(solver.calls()).isEqualTo(1);
            assertThat(outcome.workPerformed())
                .containsExactly(OnboardingWork.MEASUREMENT_GATE, OnboardingWork.FEE_CLASSIFICATION,
                    OnboardingWork.TIER_ASSIGNMENT, OnboardingWork.PROJECTION,
                    OnboardingWork.SOLVE);
        }

        @Test
        @DisplayName("an FVTPL contract with no SPPI assessment at all also does no expensive work")
        void aBusinessModelFvtplContractAlsoDoesNoExpensiveWork() {
            CountingProjector projector = new CountingProjector();
            CountingSolver solver = new CountingSolver();
            OnboardingRequest request = OnboardingRequest.of("C-TRADING", InstrumentClass.INVESTMENT,
                MeasurementCategory.FVTPL, null, case1Terms(), TierAssignmentSegment.TREASURY);

            OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(), projector,
                solver).onboard(RUN, boundary(), request);

            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.EXCLUDED_FROM_EIR);
            assertThat(projector.calls()).isZero();
            assertThat(solver.calls())
                .as("FR-103 words the obligation over the whole FVTPL population, not only the SPPI"
                    + " failures: an instrument at FVTPL 'must be excluded from EIR processing"
                    + " entirely'")
                .isZero();
        }

        @Test
        @DisplayName("a contract the gate refused does no expensive work either")
        void aRefusedGateLicensesNothing() {
            CountingProjector projector = new CountingProjector();
            CountingSolver solver = new CountingSolver();
            OnboardingRequest unassessed = OnboardingRequest.of("C-UNASSESSED",
                InstrumentClass.LOAN, MeasurementCategory.AMORTISED_COST, null, case1Terms(),
                TierAssignmentSegment.RETAIL);

            OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(), projector,
                solver).onboard(RUN, boundary(), unassessed);

            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.QUARANTINED);
            assertThat(projector.calls()).isZero();
            assertThat(solver.calls())
                .as("an unconcluded gate is not a licence to carry on; defaulting it would produce"
                    + " a rate for an instrument nobody has classified")
                .isZero();
        }
    }

    @Nested
    @DisplayName("the amortised-cost branch, on reference case 1's figures")
    class TheAmortisedCostBranch {

        @Test
        @DisplayName("IC-1: the initial gross carrying amount is 995,000.00")
        void theInitialCarryingAmountIsTheReferenceFigure() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(paise(outcome.initialCarryingAmount().orElseThrow()))
                .as("1,000,000 advanced, less 15,000 of fee received, plus 10,000 of commission"
                    + " paid — derived by hand and quoted from case 1's Derived table")
                .isEqualByComparingTo(paise(CASE1_INITIAL_GCA));
            assertThat(paise(outcome.projection().netCashAtInception()))
                .as("the other route to the same number, holder's sign: two independent derivations"
                    + " is what makes IC-1 able to fail")
                .isEqualByComparingTo(new BigDecimal("-995000.00"));

            InvariantResult icOne = only(outcome.invariants(), InvariantId.IC_1);
            assertThat(icOne.satisfied()).isTrue();
        }

        @Test
        @DisplayName("the EIR is 1.04214918% per month and 13.248094% effective p.a.")
        void theSolvedRateIsTheReferenceCaseRate() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(outcome.solve().status()).isEqualTo(SolveStatus.SOLVED);
            assertThat(outcome.eir().orElseThrow().periodic())
                .as("case 1's EIR at storage precision. Cross-checked independently of this engine:"
                    + " a 400-step bisection at 40 significant digits on"
                    + " sum(k=1..24) 47073.47/(1+r)^k = 995000 gives"
                    + " r = 0.01042149179024140180974841649...")
                .isEqualByComparingTo(CASE1_EIR_PERIODIC);
            assertThat(percent(outcome.eir().orElseThrow().effectiveAnnual(), 6))
                .as("case 1's headline effective annual rate")
                .isEqualByComparingTo(CASE1_EIR_EFFECTIVE_ANNUAL_PERCENT);
        }

        @Test
        @DisplayName("the net integral fee that moves the rate is 5,000, and the spread is 56.6bp")
        void theSpreadOverContractualIsTheFixturesSpread() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(paise(PROCESSING_FEE.minus(OnboardingFixtures.DSA_COMMISSION)))
                .as("the fee arithmetic stated by hand: 15,000 received less 10,000 paid")
                .isEqualByComparingTo(paise(NET_INTEGRAL_FEE));

            BigDecimal spreadBps = outcome.eir().orElseThrow().effectiveAnnualBps()
                .subtract(OnboardingFixtures.ONE_PERCENT_MONTHLY.effectiveAnnualBps());
            assertThat(spreadBps.setScale(1, java.math.RoundingMode.HALF_UP))
                .as("13.248094% less the 12.682503% contractual effective rate, in basis points —"
                    + " case 1's 'the 5,000 of net integral fee lifts reported yield 56.6 basis"
                    + " points'")
                .isEqualByComparingTo(new BigDecimal("56.6"));
        }

        @Test
        @DisplayName("both postings resolve to INTEGRAL and cite the rule set version")
        void theFeesAreClassifiedByTheRuleSet() {
            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(outcome.feeResolutions()).hasSize(2);
            assertThat(outcome.feeResolutions())
                .allSatisfy(resolution -> {
                    assertThat(resolution.isResolved()).isTrue();
                    assertThat(resolution.entersCarryingAmount()).isTrue();
                    assertThat(resolution.ruleSetVersionId())
                        .as("04 § 2.6 stores rule_set_version_id on every computation; without it"
                            + " replay is impossible (DT-1)")
                        .isEqualTo("FEE-2026.1");
                });
        }

        @Test
        @DisplayName("the tier is Tier 2 by tenor, and it selects the solver's tolerance")
        void theTierIsAssignedAndIsLoadBearing() {
            CountingSolver solver = new CountingSolver();
            OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(),
                new CountingProjector(), solver).onboard(RUN, boundary(), case1Request("C-CASE1"));

            assertThat(outcome.tier().tier())
                .as("retail at 24 months original tenor: § 10's Tier 2 row, and case 1's Terms"
                    + " table says 'Tier 2 illustrated at contract level'")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.tier().basis())
                .as("FR-107 requires the basis recorded, not only the tier")
                .isNotBlank();
            assertThat(solver.lastRequest().tolerance())
                .as("the tier changes the criterion the published rate is accepted under"
                    + " (FR-406), which is what stops it being a column written and never read")
                .isEqualTo(SolverTolerance.forTier(MaterialityTier.TIER_2));
            assertThat(paise(solver.lastRequest().target()))
                .as("the solve targets the carrying amount the projection derived, which IC-1 has"
                    + " already tied to the net cash at inception")
                .isEqualByComparingTo(paise(CASE1_INITIAL_GCA));
        }

        @Test
        @DisplayName("a Tier 1 contract tightens the tolerance instead")
        void aTierOneContractTightensTheTolerance() {
            CountingSolver solver = new CountingSolver();
            OnboardingRequest wholesale = case1Request("C-WHOLESALE")
                .with(com.crisil.eir.policy.tier.TierAssignmentFeature.PROJECT_FINANCE);

            OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(),
                new CountingProjector(), solver).onboard(RUN, boundary(), wholesale);

            assertThat(outcome.tier().tier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(solver.lastRequest().tolerance()).isEqualTo(SolverTolerance.tightened());
        }
    }

    @Nested
    @DisplayName("the five things that genuinely are exception-queue entries")
    class TheQueueEntries {

        @Test
        @DisplayName("an unmapped fee code refuses under UNMAPPED_FEE_CODE and never defaults a treatment")
        void anUnmappedFeeCodeIsRefused() {
            CountingProjector projector = new CountingProjector();
            CountingSolver solver = new CountingSolver();
            OnboardingRequest request = case1Request("C-UNMAPPED").withFees(
                List.of(FeeSubmission.received(UNMAPPED_CODE, PROCESSING_FEE, DISBURSEMENT)));

            OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(), projector,
                solver).onboard(RUN, boundary(), request);

            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.QUARANTINED);
            assertThat(outcome.exception().category())
                .isEqualTo(ExceptionCategory.UNMAPPED_FEE_CODE);
            assertThat(projector.calls())
                .as("the refusal happens before the projection, so no carrying amount is built"
                    + " from a fee set with a hole in it")
                .isZero();
            assertThat(outcome.exception().detail())
                .as("FR-202: the refusal names the key that could not be resolved, because the"
                    + " operator has to know which of the four components to configure")
                .contains(UNMAPPED_CODE);
        }

        @Test
        @DisplayName("a penal charge refuses under PENAL_CHARGE_REJECTED, not under the barrier's coarse fallback")
        void aPenalChargeIsRejectedAtTheBoundary() {
            OnboardingRequest request = case1Request("C-PENAL").withFees(
                List.of(FeeSubmission.received(PENAL_CHARGE_CODE, Money.inr("500"), DISBURSEMENT)));

            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), request);

            assertThat(outcome.exception().category())
                .as("checked before FeePosting is constructed: letting its constructor throw would"
                    + " file this under the barrier's MISSING_MANDATORY_FIELD fallback and send the"
                    + " operator to the wrong system")
                .isEqualTo(ExceptionCategory.PENAL_CHARGE_REJECTED);
            assertThat(outcome.exception().detail()).contains(InvariantId.PC_1.toString());
        }

        @Test
        @DisplayName("an integral cost with no cost function refuses under MISSING_COST_FUNCTION (ACPIR 53)")
        void anIntegralCostWithoutACostFunctionIsRefused() {
            OnboardingRequest request = case1Request("C-NOFUNCTION").withFees(List.of(
                new FeeSubmission(OnboardingFixtures.DSA_COMMISSION_CODE,
                    OnboardingFixtures.DSA_COMMISSION.negate(), DISBURSEMENT, null, null)));

            OnboardingOutcome outcome = pipeline().onboard(RUN, boundary(), request);

            assertThat(outcome.exception().category())
                .isEqualTo(ExceptionCategory.MISSING_COST_FUNCTION);
            assertThat(outcome.exception().detail())
                .as("ACPIR 53 capitalises a selling-agent incentive and excludes internal"
                    + " credit-appraisal cost, so the attribute is what places the posting")
                .contains("SELLING");
        }

        @Test
        @DisplayName("an IC-1 breach quarantines the contract and no solve is attempted")
        void anIcOneBreachStopsTheContractBeforeTheSolve() {
            CountingSolver solver = new CountingSolver();
            InitialRecognition pipeline = new InitialRecognition(feeRules(), tierGate(),
                new IcBreachingProjector(), solver);

            OnboardingOutcome outcome = pipeline.onboard(RUN, boundary(), case1Request("C-IC1"));

            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.QUARANTINED);
            assertThat(outcome.exception().category()).isEqualTo(ExceptionCategory.IC1_BREACH);
            assertThat(solver.calls())
                .as("the carrying amount the solver would target is wrong, so a rate solved"
                    + " against it would be precise and meaningless")
                .isZero();

            InvariantResult icOne = only(outcome.invariants(), InvariantId.IC_1);
            assertThat(icOne.satisfied()).isFalse();
            assertThat(icOne.deviation().abs())
                .as("the projector kept case 1's 15,000 processing fee out of the carrying amount"
                    + " while the vector kept it in — one of the two causes IC1_BREACH names")
                .isEqualByComparingTo(new BigDecimal("15000.00"));
        }

        @Test
        @DisplayName("a solve with no root refuses under NO_SOLUTION and never falls back to the contractual rate")
        void aFailedSolveReachesTheQueueRatherThanDefaulting() {
            InitialRecognition pipeline = new InitialRecognition(feeRules(), tierGate(),
                new CountingProjector(), new NoSolutionSolver());

            OnboardingOutcome outcome =
                pipeline.onboard(RUN, boundary(), case1Request("C-NOSOLUTION"));

            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.QUARANTINED);
            assertThat(outcome.exception().category()).isEqualTo(ExceptionCategory.NO_SOLUTION);
            assertThat(outcome.eir())
                .as("03 § 4.3 names a silent fallback the most damaging failure available to the"
                    + " engine, because it produces plausible numbers and leaves no trace")
                .isEmpty();
            assertThat(outcome.performed(OnboardingWork.SOLVE))
                .as("the solve was attempted, unlike in the excluded case — which is why the work"
                    + " record and the disposition are two different facts")
                .isTrue();
        }

        @Test
        @DisplayName("every queue entry carries a deterministic payload reference, so two runs file identically")
        void queueEntriesAreDeterministic() {
            OnboardingRequest request = case1Request("C-UNMAPPED").withFees(
                List.of(FeeSubmission.received(UNMAPPED_CODE, PROCESSING_FEE, DISBURSEMENT)));

            String first = pipeline().onboard(RUN, boundary(), request).exception().payloadRef();
            String second = pipeline().onboard(RUN, boundary(), request).exception().payloadRef();

            assertThat(first)
                .as("FR-903 requires byte-identical output from identical inputs; a payload"
                    + " reference built from a counter or a clock would break it")
                .isEqualTo(second)
                .isEqualTo(InitialRecognition.PAYLOAD_PREFIX + "/" + RUN + "/C-UNMAPPED");
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private static InitialRecognition pipeline() {
        return new InitialRecognition(feeRules(), tierGate(), new CountingProjector(),
            new CountingSolver());
    }

    /** The one result carrying {@code id}, or a failure naming what was published instead. */
    private static InvariantResult only(List<InvariantResult> results, InvariantId id) {
        List<InvariantResult> matching = results.stream()
            .filter(result -> result.id() == id)
            .toList();
        assertThat(matching)
            .as("exactly one result per invariant id: more than one means anything resolving the"
                + " invariant by name gets whichever comes first. Published: " + results)
            .hasSize(1);
        return matching.get(0);
    }
}
