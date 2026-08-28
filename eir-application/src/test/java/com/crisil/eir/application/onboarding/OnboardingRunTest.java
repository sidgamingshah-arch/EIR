package com.crisil.eir.application.onboarding;

import static com.crisil.eir.application.onboarding.OnboardingFixtures.DISBURSEMENT;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.PROCESSING_FEE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.UNMAPPED_CODE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.boundary;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Request;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.feeRules;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.sppiFailingRequest;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.tierGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingSolver;
import com.crisil.eir.application.onboarding.OnboardingFixtures.FixedPopulation;
import com.crisil.eir.application.onboarding.OnboardingFixtures.IcBreachingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.MapOnboardingSource;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The population level: the count that has to add up (FR-905), and the aggregation traps.
 *
 * <p>Two failure families are under test here and both have been shipped by this programme before.
 *
 * <p><b>The dropped contract.</b> {@code ContractResult}'s javadoc: "a run over 10,000,000 contracts
 * that silently processed 9,999,998 reconciles perfectly, because the two it dropped are absent from
 * both sides of every total." {@link OnboardingRun} refuses to exist unless the population partitions
 * exactly across its outcomes and its unreadable entries, and the tests below drive both directions
 * of that.
 *
 * <p><b>The aggregation over nothing.</b> A control whose loop iterates whatever its inputs happened
 * to mention returns a pass when the inputs are empty. The most recent instance in this codebase was
 * found in Phase 5 by review rather than by test. Here {@link OnboardingRun#POPULATION_INVARIANTS} is
 * fixed rather than derived, an id with no evidence is published nowhere and reported as unasserted,
 * and a run that established nothing blocks the close.
 */
class OnboardingRunTest {

    private static final String RUN = "RUN-2026-04";

    private static InitialRecognition pipeline() {
        return new InitialRecognition(feeRules(), tierGate(), new CountingProjector(),
            new CountingSolver());
    }

    @Nested
    @DisplayName("the three-way count adds up, or the run does not exist")
    class ThePopulationArithmetic {

        @Test
        @DisplayName("one recognised, one excluded, one quarantined: 3 = 1 + 1 + 1")
        void everyContractGetsExactlyOneDisposition() {
            MapOnboardingSource source = new MapOnboardingSource()
                .with(case1Request("C-RECOGNISED"))
                .with(sppiFailingRequest("C-EXCLUDED"))
                .with(case1Request("C-QUARANTINED").withFees(
                    List.of(FeeSubmission.received(UNMAPPED_CODE, PROCESSING_FEE, DISBURSEMENT))));
            ExceptionQueue queue = new ExceptionQueue();

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(source.ids()), source, queue);

            assertThat(run.populationSize()).isEqualTo(3);
            assertThat(run.recognisedCount()).isEqualTo(1);
            assertThat(run.excludedCount())
                .as("the FVTPL answer is its own disposition: filing it as a quarantine would put a"
                    + " correctly-measured instrument behind 04 § 3's close gate, and filing it as"
                    + " computed would imply a figure that does not exist")
                .isEqualTo(1);
            assertThat(run.quarantinedCount()).isEqualTo(1);
            assertThat(run.recognisedCount() + run.excludedCount() + run.quarantinedCount())
                .as("FR-905: every contract in the population is computed, excluded or quarantined,"
                    + " and the count has to add up")
                .isEqualTo(run.populationSize());
            assertThat(queue.size())
                .as("the quarantined contract, and only it, reached the queue")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a contract the population names and the source does not hold is quarantined, not skipped")
        void anAbsentRecordIsQuarantinedAndCounted() {
            MapOnboardingSource source = new MapOnboardingSource().with(case1Request("C-PRESENT"));
            ExceptionQueue queue = new ExceptionQueue();

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(List.of("C-PRESENT", "C-MISSING")), source, queue);

            assertThat(run.populationSize()).isEqualTo(2);
            assertThat(run.unreadable())
                .as("it never reached the gate, so there is no MeasurementDecision to build an"
                    + " outcome around — carried as its own list rather than fabricated, because"
                    + " 'the gate ran on every contract' is a claim this module makes and a"
                    + " synthetic decision would make it unfalsifiable")
                .hasSize(1);
            assertThat(run.unreadable().get(0).contractId()).isEqualTo("C-MISSING");
            assertThat(run.unreadable().get(0).category())
                .isEqualTo(ExceptionCategory.MISSING_MANDATORY_FIELD);
            assertThat(run.quarantinedCount()).isEqualTo(1);
            assertThat(run.recognisedCount() + run.excludedCount() + run.quarantinedCount())
                .isEqualTo(2);
            assertThat(queue.isQuarantined("C-MISSING")).isTrue();
        }

        @Test
        @DisplayName("a run missing one of its population's contracts cannot be constructed")
        void aShortRunIsRefused() {
            OnboardingOutcome one = pipeline().onboard(RUN, boundary(), case1Request("C-1"));

            assertThatThrownBy(() -> new OnboardingRun(RUN, boundary(), List.of("C-1", "C-2"),
                List.of(one), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unaccounted for")
                .hasMessageContaining("C-2");
        }

        @Test
        @DisplayName("a run reporting a contract that was not in scope cannot be constructed either")
        void anOutOfScopeContractIsRefused() {
            OnboardingOutcome stranger = pipeline().onboard(RUN, boundary(), case1Request("C-9"));

            assertThatThrownBy(() -> new OnboardingRun(RUN, boundary(), List.of(), List.of(stranger),
                List.of()))
                .as("on a replay this is the worse direction: 05 § 3.3 requires the replay to see"
                    + " the population the original run saw, and a contract onboarded since would"
                    + " appear in the replay and not in the published figures")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reported but not in scope");
        }

        @Test
        @DisplayName("a duplicated contract id in the population is refused before anything is computed")
        void aDuplicateInThePopulationIsRefused() {
            MapOnboardingSource source = new MapOnboardingSource().with(case1Request("C-1"));

            assertThatThrownBy(() -> pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(List.of("C-1", "C-1")), source, new ExceptionQueue()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
        }
    }

    @Nested
    @DisplayName("the aggregation, and the trap of aggregating over nothing")
    class TheAggregation {

        @Test
        @DisplayName("an empty population publishes no invariants, reports both as unasserted, and blocks the close")
        void anEmptyPopulationDoesNotReportACleanClose() {
            ExceptionQueue queue = new ExceptionQueue();

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(List.of()), new MapOnboardingSource(), queue);

            assertThat(run.populationSize()).isZero();
            assertThat(run.invariants())
                .as("an aggregation over an empty population is the trap: a loop over nothing"
                    + " compares nothing and returns a pass. Nothing is published at all rather"
                    + " than published as satisfied")
                .isEmpty();
            assertThat(run.unassertedInvariants())
                .as("POPULATION_INVARIANTS is fixed, not derived from what the outcomes happened to"
                    + " mention, so an id with no evidence is a NAMED gap rather than quietly"
                    + " absent from a report that then reads as complete")
                .containsExactly(InvariantId.ST_12, InvariantId.IC_1);
            assertThat(run.assertedNothing()).isTrue();
            assertThat(run.blocksClose())
                .as("a run that processed no contracts must not report a clean close — and it is"
                    + " reported as a property of the run, not as a fabricated breach of an"
                    + " invariant that is vacuously true")
                .isTrue();
            assertThat(run.describeClose()).contains("CLOSE BLOCKED", "established none of");
            assertThat(queue.isEmpty())
                .as("and there are no exceptions either, which is exactly why the emptiness has to"
                    + " be caught somewhere else")
                .isTrue();
        }

        @Test
        @DisplayName("an all-FVTPL population asserts ST-12 and reports IC-1 as unasserted")
        void icOneIsUnassertedWhereNothingWasProjected() {
            MapOnboardingSource source = new MapOnboardingSource()
                .with(sppiFailingRequest("C-F1"))
                .with(sppiFailingRequest("C-F2"));

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(source.ids()), source, new ExceptionQueue());

            assertThat(run.invariants()).hasSize(1);
            assertThat(run.invariants().get(0).id()).isEqualTo(InvariantId.ST_12);
            assertThat(run.invariants().get(0).satisfied()).isTrue();
            assertThat(run.invariants().get(0).detail())
                .as("the aggregate states its own denominator, so a reader can tell 'no breaches'"
                    + " from 'nothing looked at'")
                .contains("asserted over 2 contract(s) with evidence");
            assertThat(run.unassertedInvariants())
                .as("IC-1 is a claim about a projection and nothing was projected; publishing it as"
                    + " a pass would be coverage this run did not earn")
                .containsExactly(InvariantId.IC_1);
            assertThat(run.blocksClose())
                .as("nothing breached and nothing was quarantined, and ST-12 was genuinely"
                    + " established over the whole population, so the close may proceed")
                .isFalse();
        }

        @Test
        @DisplayName("ST-12's population deviation is a COUNT of breaching contracts, and two breaches make two")
        void stTwelveAggregatesAsACount() {
            // Two contracts whose classification file says FAIL and whose contract master says
            // amortised cost — the schema's contract_sppi_fail_implies_fvtpl_ck, twice.
            MapOnboardingSource source = new MapOnboardingSource()
                .with(OnboardingRequest.of("C-BAD1", InstrumentClass.LOAN,
                    MeasurementCategory.AMORTISED_COST,
                    SppiAssessment.failed(DISBURSEMENT, "classification.committee"),
                    OnboardingFixtures.case1Terms(),
                    com.crisil.eir.policy.tier.TierAssignmentSegment.RETAIL))
                .with(OnboardingRequest.of("C-BAD2", InstrumentClass.INVESTMENT,
                    MeasurementCategory.FVOCI,
                    SppiAssessment.failed(DISBURSEMENT, "classification.committee"),
                    OnboardingFixtures.case1Terms(),
                    com.crisil.eir.policy.tier.TierAssignmentSegment.TREASURY));

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(source.ids()), source, new ExceptionQueue());

            InvariantResult stTwelve = run.invariants().get(0);
            assertThat(stTwelve.id()).isEqualTo(InvariantId.ST_12);
            assertThat(stTwelve.satisfied()).isFalse();
            assertThat(stTwelve.deviation())
                .as("one breach per contract, summed as an absolute count. Never a signed sum:"
                    + " InvariantResult.conjunction would have kept only the first breach's"
                    + " deviation and silently dropped the second")
                .isEqualByComparingTo(new BigDecimal("2"));
            assertThat(stTwelve.detail()).contains("C-BAD1", "C-BAD2");
            assertThat(run.excludedCount())
                .as("both are still measured correctly, at FVTPL, and neither is an exception")
                .isEqualTo(2);
            assertThat(run.blocksClose())
                .as("03 § 9: an invariant breach blocks the close, which is a stronger consequence"
                    + " than a queue entry that can be accepted with approval")
                .isTrue();
        }

        @Test
        @DisplayName("IC-1's population deviation is a TOTAL ABSOLUTE residual: two breaks 15,000 apart in OPPOSITE directions report 30,000, not nil")
        void icOneAggregatesAsAnAbsoluteTotalRatherThanNetting() {
            // The netting trap, set deliberately. One contract is projected 15,000 BELOW its net
            // cash at inception and one 15,000 ABOVE it, so the signed residuals are -15,000 and
            // +15,000 and a signed sum is exactly nil — a population-level control reporting a
            // clean IC-1 over two contracts that both breach. Two pipelines rather than one,
            // because the two directions need two projectors; the run is then assembled from both
            // outcomes, which is what OnboardingRun is for.
            OnboardingOutcome low = new InitialRecognition(feeRules(), tierGate(),
                new IcBreachingProjector(PROCESSING_FEE.negate()), new CountingSolver())
                .onboard(RUN, boundary(), case1Request("C-IC1-LOW"));
            OnboardingOutcome high = new InitialRecognition(feeRules(), tierGate(),
                new IcBreachingProjector(PROCESSING_FEE), new CountingSolver())
                .onboard(RUN, boundary(), case1Request("C-IC1-HIGH"));

            assertThat(low.invariants().stream()
                .filter(result -> result.id() == InvariantId.IC_1)
                .findFirst().orElseThrow().deviation()
                .add(high.invariants().stream()
                    .filter(result -> result.id() == InvariantId.IC_1)
                    .findFirst().orElseThrow().deviation()))
                .as("the two per-contract deviations really do sum to nil, which is what makes this"
                    + " a test of the aggregation rather than of the arithmetic")
                .isEqualByComparingTo(BigDecimal.ZERO);

            OnboardingRun run = new OnboardingRun(RUN, boundary(),
                List.of("C-IC1-LOW", "C-IC1-HIGH"), List.of(low, high), List.of());

            InvariantResult icOne = run.invariants().stream()
                .filter(result -> result.id() == InvariantId.IC_1)
                .findFirst()
                .orElseThrow();
            assertThat(icOne.satisfied()).isFalse();
            assertThat(icOne.deviation())
                .as("15,000 + 15,000 as ABSOLUTE values. A signed sum would report nil here and the"
                    + " aggregate would pass over two breaching contracts — the failure a"
                    + " population-level control exists to catch")
                .isEqualByComparingTo(new BigDecimal("30000.00"));
            assertThat(run.quarantinedCount())
                .as("an IC-1 breach stops the contract: ExceptionCategory.IC1_BREACH")
                .isEqualTo(2);
            assertThat(run.blocksClose()).isTrue();
        }

        @Test
        @DisplayName("the counts and the gaps read as one line an operator can act on")
        void theCloseDescriptionNamesTheCountsAndTheGaps() {
            MapOnboardingSource source = new MapOnboardingSource()
                .with(case1Request("C-OK"))
                .with(sppiFailingRequest("C-FVTPL"));

            OnboardingRun run = pipeline().onboardAll(RUN, boundary(),
                new FixedPopulation(source.ids()), source, new ExceptionQueue());

            assertThat(run.describePopulation())
                .contains("2 contract(s) in scope as at 2026-04-01",
                    "1 recognised",
                    "1 recorded and excluded from EIR processing",
                    "0 quarantined");
            assertThat(run.blocksClose()).isFalse();
            assertThat(run.describeClose()).contains("close may proceed");
        }
    }
}
