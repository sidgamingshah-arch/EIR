package com.crisil.eir.application.onboarding;

import static com.crisil.eir.application.onboarding.OnboardingFixtures.DISBURSEMENT;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.boundary;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Fees;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Terms;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.feeRules;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.tierGate;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingSolver;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The gate and the ordering, over every combination of the three inputs, with the expected answer
 * <b>recomputed from FR-103, FR-104 and the schema's CHECK constraints</b> rather than from this
 * engine.
 *
 * <h2>How the expectation is derived without running the code under test</h2>
 *
 * <p>{@link #licensesEirByDefinition} is a five-line restatement of the requirement, written from the
 * documents and from
 * {@code eir-persistence/src/main/resources/db/migration/V1__core_entities.sql}:
 *
 * <ul>
 *   <li>FR-104 confines the SPPI test to the asset side, and both SPPI constraints exempt
 *       {@code LIABILITY} explicitly — so a liability's category stands as declared;
 *   <li>FR-103: an instrument at FVTPL "carries no EIR and must be excluded from EIR processing
 *       entirely";
 *   <li>FR-104: on the asset side an SPPI failure "sends the whole instrument to FVTPL — there is no
 *       bifurcation", which is {@code contract_sppi_fail_implies_fvtpl_ck};
 *   <li>{@code contract_amortised_cost_needs_sppi_ck}: an asset at amortised cost or FVOCI needs an
 *       assessment on file, and {@code IS NOT DISTINCT FROM} is there because a missing one must not
 *       read as a pass — so an unassessed asset claiming either is refused and licenses nothing.
 * </ul>
 *
 * <p>It never calls {@link MeasurementGate}. Where the two disagree the property does not say which
 * is wrong, which is the point of deriving it twice — the same discipline
 * {@code FeeRulePrecedencePropertiesTest} states one module down.
 *
 * <h2>Why the ordering is asserted as a property and not only as a case</h2>
 *
 * <p>The claim is universal: <em>for every input the gate does not license, the projector and the
 * solver are called zero times.</em> A handful of examples demonstrates it for the shapes somebody
 * thought of. A generator over the full cross-product — four instrument classes, three categories,
 * three SPPI states, thirty-six combinations, all reachable — demonstrates it for the shapes nobody
 * did, and it is the shape nobody thought of that produces a rate for an instrument that should not
 * have one.
 */
class OnboardingGatePropertiesTest {

    private static final String RUN = "RUN-PROP";

    /**
     * The SPPI states: passed, failed, and none on file.
     *
     * <p>{@code injectNull} rather than a three-valued enum, because the absence of an assessment is
     * modelled as the absence of the record — see {@link SppiOutcome}'s comment on why a
     * {@code NOT_ASSESSED} constant would be read as an outcome by every {@code switch} that handled
     * it.
     */
    @Provide
    Arbitrary<SppiAssessment> sppiStates() {
        return Arbitraries.of(
                SppiAssessment.passed(DISBURSEMENT, "classification.committee"),
                SppiAssessment.failed(DISBURSEMENT, "classification.committee"))
            .injectNull(0.34);
    }

    /**
     * The requirement, restated. Never asks the gate.
     *
     * @return whether an EIR arises and expensive work is licensed
     */
    private static boolean licensesEirByDefinition(
        InstrumentClass instrumentClass, MeasurementCategory declared, SppiAssessment assessment) {

        if (instrumentClass == InstrumentClass.LIABILITY) {
            // FR-104 confines the test to the asset side; FR-105 retains an EIR on the host of a
            // structured liability. So only FR-103's category rule applies.
            return declared != MeasurementCategory.FVTPL;
        }
        if (assessment == null) {
            // Either the declared category is FVTPL — no EIR by FR-103 — or the gate is refused for
            // want of the assessment. Neither licenses anything.
            return false;
        }
        if (assessment.outcome() == SppiOutcome.FAIL) {
            return false;
        }
        return declared != MeasurementCategory.FVTPL;
    }

    /** The refusal condition, restated from {@code contract_amortised_cost_needs_sppi_ck}. */
    private static boolean refusedByDefinition(
        InstrumentClass instrumentClass, MeasurementCategory declared, SppiAssessment assessment) {

        return instrumentClass != InstrumentClass.LIABILITY
            && assessment == null
            && declared != MeasurementCategory.FVTPL;
    }

    @Property
    void theGateLicensesExactlyWhatFr103AndFr104License(
        @ForAll InstrumentClass instrumentClass,
        @ForAll MeasurementCategory declared,
        @ForAll("sppiStates") SppiAssessment assessment) {

        MeasurementDecision decision =
            MeasurementGate.assess("C-PROP", instrumentClass, declared, assessment);

        assertThat(decision.eirApplies())
            .as("FR-103/FR-104 recomputed independently for (%s, %s, %s)",
                instrumentClass, declared, assessment)
            .isEqualTo(licensesEirByDefinition(instrumentClass, declared, assessment));
        assertThat(decision.isRefused())
            .as("contract_amortised_cost_needs_sppi_ck recomputed independently for (%s, %s, %s)",
                instrumentClass, declared, assessment)
            .isEqualTo(refusedByDefinition(instrumentClass, declared, assessment));
    }

    @Property
    void anAssetSideSppiFailureAlwaysLandsAtFvtpl(
        @ForAll InstrumentClass instrumentClass,
        @ForAll MeasurementCategory declared) {

        SppiAssessment failed = SppiAssessment.failed(DISBURSEMENT, "classification.committee");
        MeasurementDecision decision =
            MeasurementGate.assess("C-CLIFF", instrumentClass, declared, failed);

        if (instrumentClass == InstrumentClass.LIABILITY) {
            assertThat(decision.effectiveCategory())
                .as("the liability side is exempted rather than forced")
                .isEqualTo(declared);
            return;
        }
        assertThat(decision.effectiveCategory())
            .as("FR-104: a cliff, not a gradient — the WHOLE instrument goes to FVTPL, whatever the"
                + " contract master declared (%s)", declared)
            .isEqualTo(MeasurementCategory.FVTPL);
        assertThat(decision.classificationInvariant().map(InvariantResult::satisfied))
            .as("ST-12 breaches exactly where the declared category still carries an EIR")
            .isEqualTo(Optional.of(!declared.carriesEir()));
    }

    /**
     * The ordering, over the whole cross-product: nothing unlicensed is projected or solved.
     *
     * <p>The universal form of the single most important test in this unit. Note what is asserted on
     * the licensed side too — exactly one projection and exactly one solve — because a property that
     * only bounded the unlicensed side would be satisfied by a pipeline that never projected
     * anything at all.
     */
    @Property
    void nothingUnlicensedIsEverProjectedOrSolved(
        @ForAll InstrumentClass instrumentClass,
        @ForAll MeasurementCategory declared,
        @ForAll("sppiStates") SppiAssessment assessment) {

        CountingProjector projector = new CountingProjector();
        CountingSolver solver = new CountingSolver();
        OnboardingRequest request = OnboardingRequest.of("C-PROP", instrumentClass, declared,
                assessment, case1Terms(), TierAssignmentSegment.RETAIL)
            .withFees(case1Fees());

        OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(), projector, solver)
            .onboard(RUN, boundary(), request);

        boolean licensed = licensesEirByDefinition(instrumentClass, declared, assessment);
        assertThat(outcome.workPerformed().get(0))
            .as("05 § 3.1: the gate runs FIRST for every contract, licensed or not")
            .isEqualTo(OnboardingWork.MEASUREMENT_GATE);

        if (!licensed) {
            assertThat(projector.calls())
                .as("projector calls for an unlicensed (%s, %s, %s)",
                    instrumentClass, declared, assessment)
                .isZero();
            assertThat(solver.calls())
                .as("solver calls for an unlicensed (%s, %s, %s)",
                    instrumentClass, declared, assessment)
                .isZero();
            assertThat(outcome.workPerformed()).containsExactly(OnboardingWork.MEASUREMENT_GATE);
            assertThat(outcome.eir()).isEmpty();
            assertThat(outcome.disposition())
                .isEqualTo(refusedByDefinition(instrumentClass, declared, assessment)
                    ? OnboardingDisposition.QUARANTINED
                    : OnboardingDisposition.EXCLUDED_FROM_EIR);
        } else {
            assertThat(projector.calls())
                .as("a licensed contract projects exactly once")
                .isEqualTo(1);
            assertThat(solver.calls())
                .as("and solves exactly once")
                .isEqualTo(1);
            assertThat(outcome.disposition()).isEqualTo(OnboardingDisposition.RECOGNISED);
            assertThat(outcome.eir()).isPresent();
        }
    }

    /**
     * Every outcome publishes at most one result per invariant id.
     *
     * <p>{@code InvariantResult.conjunction}'s javadoc: more than one result under one identifier
     * means "anything resolving that invariant by name … gets whichever happens to come first",
     * and the defect has been found three times in this engine. Asserted over generated inputs
     * because it is a property of the assembly rather than of any one branch.
     */
    @Property
    void everyOutcomePublishesAtMostOneResultPerInvariant(
        @ForAll InstrumentClass instrumentClass,
        @ForAll MeasurementCategory declared,
        @ForAll("sppiStates") SppiAssessment assessment) {

        OnboardingRequest request = OnboardingRequest.of("C-PROP", instrumentClass, declared,
                assessment, case1Terms(), TierAssignmentSegment.RETAIL)
            .withFees(case1Fees());
        OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(),
            new CountingProjector(), new CountingSolver()).onboard(RUN, boundary(), request);

        List<InvariantId> ids = outcome.invariants().stream().map(InvariantResult::id).toList();
        assertThat(ids)
            .as("one answer per invariant, for (%s, %s, %s)", instrumentClass, declared, assessment)
            .doesNotHaveDuplicates();
        assertThat(ids)
            .as("and nothing outside the two ids this use case answers for")
            .isSubsetOf(OnboardingRun.POPULATION_INVARIANTS);
    }

    /**
     * A refused gate always files, and always under the category the schema's reasoning names.
     *
     * <p>The half of FR-905 that is easy to get wrong in the other direction: a refusal nobody filed
     * is a contract dropped from the population, and a population short by one contract reconciles
     * perfectly.
     */
    @Property
    void arefusedGateAlwaysFilesUnderMissingMandatoryField(
        @ForAll InstrumentClass instrumentClass,
        @ForAll MeasurementCategory declared) {

        OnboardingRequest request = OnboardingRequest.of("C-PROP", instrumentClass, declared, null,
            case1Terms(), TierAssignmentSegment.RETAIL);
        OnboardingOutcome outcome = new InitialRecognition(feeRules(), tierGate(),
            new CountingProjector(), new CountingSolver()).onboard(RUN, boundary(), request);

        if (refusedByDefinition(instrumentClass, declared, null)) {
            assertThat(outcome.exception()).isNotNull();
            assertThat(outcome.exception().category())
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(outcome.exception().payloadRef())
                .as("03 § 4.3: the stored payload is the only diagnostic a value-path failure will"
                    + " ever have, and it is derived from the run and the contract so two runs file"
                    + " identically")
                .isEqualTo(InitialRecognition.PAYLOAD_PREFIX + "/" + RUN + "/C-PROP");
        } else {
            assertThat(outcome.exception())
                .as("nothing else in this pipeline refuses on a clean fee set")
                .isNull();
        }
    }
}
