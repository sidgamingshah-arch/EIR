package com.crisil.eir.application.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.DiscrepancyKind;
import com.crisil.eir.policy.replay.ReplayRun;
import com.crisil.eir.policy.replay.ReplaySamplingBasis;
import com.crisil.eir.policy.replay.SampleOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The replay use case: the same batch job, an as-at boundary, and DT-1 (05 § 3.3, FR-903, C-12).
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>None of them from running this code. The amounts are reference case 1's roll-forward, quoted
 * and hand-checked in {@link ReplayFixtures}. Every expected deviation is a <em>count</em> derived
 * by reading the fixture, and each test states the arithmetic: three figures per computed contract
 * (a closing balance and two journal lines), so two contracts publish six figures, and one
 * perturbed contract balance is one discrepancy.
 *
 * <p>The published figure maps are written out key by key in {@link ReplayFixtures}, never built
 * with {@link ShadowRun}. Building both sides with the code under test would make a key-convention
 * defect cancel out and every test here would pass on it.
 *
 * <h2>The two tests that matter most</h2>
 *
 * <p>{@link UnderThePolicyThenInForce} is the more valuable half of this file, and
 * {@link UnderThePolicyThenInForce#figuresReproduceExactlyAndThePolicyVersionDiverges()} is the
 * single most valuable test in the unit. Every figure is bit-identical and the replay resolved the
 * <em>superseding</em> fee rule set — the concrete defect being a harness that resolves policy at
 * {@code LocalDate.now()} rather than at the period end, which works perfectly until the first
 * supersession. Reproducing the right number from the wrong rule is luck; next period it will not
 * hold, and by then the divergence will be blamed on whatever changed most recently. Nothing else
 * in the engine can see it, because every downstream numeric check agrees.
 *
 * <p>{@link WhenTheComparisonComparedNothing} is the second. A replay whose every contract was
 * quarantined compares no figure and {@code ReplayComparison} — correctly, given what it can see —
 * reports a DT-1 pass. A pass there would be this codebase's signature defect in its purest form:
 * a control reporting coverage for having looked at nothing.
 */
class ReplayUseCaseTest {

    private static final String SHADOW_ID =
        ReplayRequest.shadowRunId(ReplayFixtures.PUBLISHED_RUN_ID, ReplayFixtures.NIGHT_OF);

    /** The boundary 05 § 3.3 prescribes: period end for business time, the run's recorded_at. */
    private static final AsAtBoundary EXPECTED_BOUNDARY = AsAtBoundary.replaying(
        ReplayFixtures.APRIL_2027.endDate(), ReplayFixtures.RECORDED_AT,
        ReplayFixtures.PUBLISHED_RUN_ID);

    private static ReplayVerification replay(
        ReplayFixtures.Ports ports, PublishedRun published,
        List<ContractResult> replayResults, Map<PolicyKind, String> replayStamps) {
        ReplayFixtures.FakeBatchJob job = ReplayFixtures.jobReturning(replayResults, replayStamps);
        ReplayRequest request = new ReplayRequest(
            published, ports.liveTemplate(published.periodId()));
        return new ReplayUseCase(job).replay(
            request, ReplayRequest.shadowRunId(published.runId(), ReplayFixtures.NIGHT_OF));
    }

    /** The ordinary case: two contracts, reproduced exactly, under April's own policy. */
    private static ReplayVerification faithfulAprilReplay() {
        return replay(
            ReplayFixtures.aprilPorts(),
            ReplayFixtures.publishedApril(),
            ReplayFixtures.aprilResults(SHADOW_ID),
            ReplayFixtures.aprilStamps());
    }

    @Nested
    @DisplayName("05 § 3.3: the same batch job, with an as-at boundary")
    class TheSameBatchJob {

        @Test
        @DisplayName("the shadow request is the live request with the run id and boundary moved,"
            + " and nothing else")
        void theShadowRequestDiffersOnlyInTheBoundaryAndTheRunId() {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            RunRequest live = ports.liveTemplate(ReplayFixtures.APRIL_2027.periodId());
            RunRequest shadow = new ReplayRequest(ReplayFixtures.publishedApril(), live)
                .shadowRunRequest(SHADOW_ID);

            assertThat(shadow.isReplay())
                .as("is_replay = true, which is not a label on the row: it is the statement that"
                    + " the boundary was moved")
                .isTrue();
            assertThat(shadow.boundary()).isEqualTo(EXPECTED_BOUNDARY);
            assertThat(shadow.runId()).isEqualTo(SHADOW_ID).isNotEqualTo(live.runId());

            // Every remaining component, checked by substituting the live run id and boundary back
            // and asserting the result IS the live request. Component by component, so a replay
            // that quietly swapped a port — a parallel pipeline with one line of difference is
            // still a parallel pipeline — fails here rather than in a figure six months later.
            RunRequest reconstructed = new RunRequest(
                live.runId(), shadow.periodId(), shadow.bookId(), live.boundary(),
                shadow.contracts(), shadow.contractState(), shadow.coreBanking(),
                shadow.generalLedger(), shadow.policy());
            assertThat(reconstructed)
                .as("the replay changed exactly two fields of the job it was handed")
                .isEqualTo(live);
        }

        @Test
        @DisplayName("every port is asked as at the original run's recorded_at, not as at now")
        void everyPortIsAskedAsAtTheOriginalRunsRecordedAt() {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            ReplayFixtures.FakeBatchJob job = ReplayFixtures.jobReturning(
                ReplayFixtures.aprilResults(SHADOW_ID), ReplayFixtures.aprilStamps());
            new ReplayUseCase(job).replay(
                new ReplayRequest(
                    ReplayFixtures.publishedApril(),
                    ports.liveTemplate(ReplayFixtures.APRIL_2027.periodId())),
                SHADOW_ID);

            assertThat(ports.allBoundaries())
                .as("05 § 3.3: the contract version set, the policy versions and the ECL input"
                    + " version are all read as at the original run's recorded_at. A port asked"
                    + " as at anything else returns figures that are internally consistent and"
                    + " reproduce nothing")
                .isNotEmpty()
                .allSatisfy(boundary -> assertThat(boundary).isEqualTo(EXPECTED_BOUNDARY));
            assertThat(ports.policy.asked)
                .as("the policy timeline too — FR-903's second half is read at the same boundary")
                .containsExactly(EXPECTED_BOUNDARY);
        }

        @Test
        @DisplayName("the job runs once; the replay composes it rather than reimplementing it")
        void theJobRunsExactlyOnce() {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            ReplayFixtures.FakeBatchJob job = ReplayFixtures.jobReturning(
                ReplayFixtures.aprilResults(SHADOW_ID), ReplayFixtures.aprilStamps());
            new ReplayUseCase(job).replay(
                new ReplayRequest(
                    ReplayFixtures.publishedApril(),
                    ports.liveTemplate(ReplayFixtures.APRIL_2027.periodId())),
                SHADOW_ID);

            assertThat(job.invocations).isEqualTo(1);
            assertThat(job.received.boundary().replayOf())
                .as("and it was told which run it is reproducing")
                .isEqualTo(ReplayFixtures.PUBLISHED_RUN_ID);
        }

        @Test
        @DisplayName("the shadow run id is derived from the night and the published run, not a"
            + " clock")
        void theShadowRunIdIsDerivedFromItsInputs() {
            assertThat(ReplayRequest.shadowRunId("RUN-1", ReplayFixtures.NIGHT_OF))
                .isEqualTo("REPLAY-2027-08-10-OF-RUN-1")
                .as("two executions of tonight's job derive the same id, so the shadow table's"
                    + " writes are idempotent; last night's replay keeps a distinct row")
                .isEqualTo(ReplayRequest.shadowRunId("RUN-1", ReplayFixtures.NIGHT_OF));
        }
    }

    @Nested
    @DisplayName("A faithful replay")
    class WhenTheReplayReproduces {

        @Test
        @DisplayName("DT-1 passes over six compared figures and the reproduction is proved")
        void dtOnePassesAndTheReproductionIsProved() {
            ReplayVerification verification = faithfulAprilReplay();
            InvariantResult dtOne = verification.dtOne();

            assertThat(dtOne.id()).isEqualTo(InvariantId.DT_1);
            assertThat(dtOne.satisfied()).isTrue();
            assertThat(dtOne.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            // Two computed contracts x (one closing balance + two journal lines) = six figures.
            assertThat(verification.comparison().figuresCompared()).isEqualTo(6);
            assertThat(verification.comparison().policyKindsCompared()).isEqualTo(2);
            assertThat(verification.coverage()).isEqualTo(ReplayCoverage.FIGURES_COMPARED);
            assertThat(verification.provesReproduction()).isTrue();
            assertThat(verification.population().describe())
                .isEqualTo("2 contract(s) in scope = 2 computed + 0 quarantined");
        }

        @Test
        @DisplayName("the shadow run names the run it reproduces and carries the shadow id")
        void theShadowRunNamesTheRunItReproduces() {
            ReplayVerification verification = faithfulAprilReplay();
            assertThat(verification.shadow().isReplay()).isTrue();
            assertThat(verification.shadow().replayOf())
                .contains(ReplayFixtures.PUBLISHED_RUN_ID);
            assertThat(verification.shadowRunId()).isEqualTo(SHADOW_ID);
            assertThat(verification.periodId()).isEqualTo(202704);
        }

        @Test
        @DisplayName("the shadow figures are keyed exactly as the published extract names them")
        void theShadowFiguresUseThePublishedKeyConvention() {
            ReplayVerification verification = faithfulAprilReplay();
            assertThat(verification.shadow().figureKeys())
                .containsExactlyInAnyOrderElementsOf(
                    ReplayFixtures.publishedFigures().keySet());
        }
    }

    /**
     * FR-903's second half, and the reason this file exists.
     *
     * <p>Every test in here has <b>bit-identical figures</b>. If the policy leg could not fail,
     * every one of them would pass, and DT-1 would be reporting that a period reproduces when the
     * rule that produced it has moved.
     */
    @Nested
    @DisplayName("FR-903: under the policy then in force")
    class UnderThePolicyThenInForce {

        @Test
        @DisplayName("*** figures reproduce exactly and the policy version diverges: DT-1 fails")
        void figuresReproduceExactlyAndThePolicyVersionDiverges() {
            // The April close cited FEE-2027.1, which is what the timeline resolves for
            // 2027-04-30. The replay job reports FEE-2027.2 — the July supersession — which is
            // what a harness resolving at LocalDate.now() in August 2027 would pick up. The
            // routing table did not change, so it agrees on both sides and with the timeline.
            //
            // Expected deviation, by reading the fixture: 0 figure discrepancies (all six figures
            // are the same objects on both sides) + 1 policy kind diverging = 1.
            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(),
                ReplayFixtures.publishedApril(),
                ReplayFixtures.aprilResults(SHADOW_ID),
                ReplayFixtures.stampsResolvedAtTheReplayDate());

            InvariantResult dtOne = verification.dtOne();
            assertThat(verification.comparison().figureDiscrepancyCount())
                .as("every figure reproduced — this is not a numeric defect")
                .isZero();
            assertThat(verification.comparison().figuresCompared())
                .as("and six of them were actually compared, so the pass is not vacuous")
                .isEqualTo(6);
            assertThat(verification.comparison().policyDiscrepancyCount()).isEqualTo(1);
            assertThat(verification.comparison()
                .discrepanciesOf(DiscrepancyKind.POLICY_VERSION_DIFFERS))
                .as("the replay resolved a different fee rule set than the close cited")
                .hasSize(1);
            assertThat(dtOne.satisfied())
                .as("reproducing the right number from the wrong rule is luck, and next period it"
                    + " will not hold")
                .isFalse();
            assertThat(dtOne.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(verification.provesReproduction()).isFalse();
            assertThat(dtOne.detail()).contains("FEE_RULE_SET");
        }

        @Test
        @DisplayName("both runs citing a version that was not in force for the period: DT-1 fails")
        void bothRunsCiteAVersionThatWasNeverInForce() {
            // Here the two runs AGREE — so comparing the runs against each other says nothing.
            // Only the timeline can see it: the period ended 2027-04-30 and FEE-2027.2 does not
            // take effect until 2027-07-01, so the close's own record cites a rule written after
            // the period it governed. 1 policy discrepancy, 0 figure discrepancies.
            Map<PolicyKind, String> wrongStamps = ReplayFixtures.stampsResolvedAtTheReplayDate();
            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(),
                ReplayFixtures.publishedRun(
                    ReplayFixtures.APRIL_2027, ReplayFixtures.publishedFigures(), wrongStamps),
                ReplayFixtures.aprilResults(SHADOW_ID),
                wrongStamps);

            assertThat(verification.comparison()
                .discrepanciesOf(DiscrepancyKind.POLICY_NOT_IN_FORCE_AT_PERIOD_END))
                .hasSize(1);
            assertThat(verification.dtOne().satisfied()).isFalse();
            assertThat(verification.dtOne().deviation())
                .isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a job that reports no policy stamps fails; the harness does not fill them in")
        void aJobThatReportsNoStampsIsNotQuietlyFilledIn() {
            // The defect this guards against is the partly-wired job: the harness resolves the
            // registry itself, stamps the replay with its own answer, and then compares that
            // answer against itself. The policy leg becomes a no-op that reports a pass — which
            // ReplayComparison's javadoc records as a shipped defect. Here the run reports
            // nothing and the published run cites nothing, and the TIMELINE raises one finding
            // per kind it says governed the period: FEE_RULE_SET and ROUTING_TABLE = 2.
            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(),
                ReplayFixtures.publishedRun(
                    ReplayFixtures.APRIL_2027, ReplayFixtures.publishedFigures(), Map.of()),
                ReplayFixtures.aprilResults(SHADOW_ID),
                Map.of());

            assertThat(verification.comparison().policyKindsCompared())
                .as("neither run consulted anything, so the runs alone had nothing to compare")
                .isZero();
            assertThat(verification.comparison()
                .discrepanciesOf(DiscrepancyKind.POLICY_KIND_NOT_CONSULTED))
                .hasSize(2);
            assertThat(verification.dtOne().satisfied()).isFalse();
            assertThat(verification.dtOne().deviation())
                .isEqualByComparingTo(BigDecimal.valueOf(2));
        }

        @Test
        @DisplayName("a blank stamp is refused: absent means not consulted, blank means unwritten")
        void aBlankStampIsRefused() {
            Map<PolicyKind, String> blank = new LinkedHashMap<>();
            blank.put(PolicyKind.FEE_RULE_SET, "  ");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RunOutput.of(List.of(), blank))
                .withMessageContaining("blank policy version id");
        }
    }

    @Nested
    @DisplayName("Byte-for-byte: the comparison Money.equals cannot make")
    class ByteForByte {

        @Test
        @DisplayName("a scale drift is a DT-1 breach although Money.equals reports a match")
        void aScaleDriftIsABreach() {
            // The trap, asserted first: the two amounts are the same amount of money and the
            // type's own equality says the replay reproduced the period exactly.
            assertThat(Money.inr(ReplayFixtures.C1_CLOSING_GCA)
                .equals(Money.inr(ReplayFixtures.C1_CLOSING_GCA + "0")))
                .as("Money.equals compares by value and ignores scale — correctly, for a type"
                    + " whose job is accounting arithmetic, and too permissively for DT-1")
                .isTrue();

            List<ContractResult> drifted = List.of(
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, SHADOW_ID,
                    ReplayFixtures.C1_CLOSING_GCA + "0", ReplayFixtures.C1_EIR_INTEREST),
                ReplayFixtures.computed(ReplayFixtures.C2, 202704, SHADOW_ID,
                    ReplayFixtures.C2_CLOSING_GCA, ReplayFixtures.C2_EIR_INTEREST));

            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(), drifted,
                ReplayFixtures.aprilStamps());

            assertThat(verification.comparison().discrepanciesOf(DiscrepancyKind.SCALE_ONLY))
                .as("958295.910 against 958295.91: zero rupees of difference, and a different"
                    + " published artefact. ShadowRun never rescales, so the drift survives")
                .hasSize(1);
            assertThat(verification.dtOne().satisfied()).isFalse();
            assertThat(verification.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a value drift on one contract is one discrepancy")
        void aValueDriftIsOneDiscrepancy() {
            // 958,295.91 published against 958,295.92 replayed: one paise, one figure, one finding.
            List<ContractResult> drifted = List.of(
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, SHADOW_ID,
                    "958295.92", ReplayFixtures.C1_EIR_INTEREST),
                ReplayFixtures.computed(ReplayFixtures.C2, 202704, SHADOW_ID,
                    ReplayFixtures.C2_CLOSING_GCA, ReplayFixtures.C2_EIR_INTEREST));

            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(), drifted,
                ReplayFixtures.aprilStamps());

            assertThat(verification.comparison().discrepanciesOf(DiscrepancyKind.VALUE_DIFFERS))
                .hasSize(1);
            assertThat(verification.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a journal line that moved is caught, not netted away by the balance")
        void aJournalLineThatMovedIsCaught() {
            // The entry stays balanced — both legs move together — so SL-2 would pass and the
            // closing balance is untouched. Two figures differ: the debit and the credit.
            List<ContractResult> drifted = List.of(
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, SHADOW_ID,
                    ReplayFixtures.C1_CLOSING_GCA, "10369.39"),
                ReplayFixtures.computed(ReplayFixtures.C2, 202704, SHADOW_ID,
                    ReplayFixtures.C2_CLOSING_GCA, ReplayFixtures.C2_EIR_INTEREST));

            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(), drifted,
                ReplayFixtures.aprilStamps());

            assertThat(verification.dtOne().deviation())
                .isEqualByComparingTo(BigDecimal.valueOf(2));
        }
    }

    @Nested
    @DisplayName("FR-905: every contract computed or quarantined, and the count adds up")
    class PerContractIsolation {

        @Test
        @DisplayName("a contract quarantined on replay but computed at close is three figures"
            + " missing from the replay")
        void aContractQuarantinedOnReplayIsMissingFromTheReplay() {
            // FR-905's cheap reading — drop it and carry on — is what makes this invisible: the
            // dropped contract is absent from both sides of every total. It is not absent from a
            // byte comparison. C1 published three figures and the replay produced none of them.
            List<ContractResult> results = List.of(
                ReplayFixtures.quarantined(ReplayFixtures.C1, SHADOW_ID),
                ReplayFixtures.computed(ReplayFixtures.C2, 202704, SHADOW_ID,
                    ReplayFixtures.C2_CLOSING_GCA, ReplayFixtures.C2_EIR_INTEREST));

            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(), results,
                ReplayFixtures.aprilStamps());

            assertThat(verification.comparison()
                .discrepanciesOf(DiscrepancyKind.MISSING_FROM_REPLAY))
                .hasSize(3);
            assertThat(verification.dtOne().deviation())
                .isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(verification.population().quarantined())
                .containsExactly(ReplayFixtures.C1);
            assertThat(verification.population().addsUp())
                .as("the count still adds up: one computed, one quarantined, two in scope")
                .isTrue();
        }

        @Test
        @DisplayName("a contract quarantined on both sides reproduces, and is still counted")
        void aContractQuarantinedOnBothSidesReproduces() {
            // The close quarantined C2 too, so it published nothing for it. The replay reproduced
            // that. Three figures compared, no discrepancy — and the population still names both.
            Map<String, Money> publishedOnlyC1 = new LinkedHashMap<>();
            ReplayFixtures.putPublishedFigures(publishedOnlyC1, ReplayFixtures.C1,
                ReplayFixtures.C1_CLOSING_GCA, ReplayFixtures.C1_EIR_INTEREST);

            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(),
                ReplayFixtures.publishedRun(
                    ReplayFixtures.APRIL_2027, publishedOnlyC1, ReplayFixtures.aprilStamps()),
                List.of(
                    ReplayFixtures.computed(ReplayFixtures.C1, 202704, SHADOW_ID,
                        ReplayFixtures.C1_CLOSING_GCA, ReplayFixtures.C1_EIR_INTEREST),
                    ReplayFixtures.quarantined(ReplayFixtures.C2, SHADOW_ID)),
                ReplayFixtures.aprilStamps());

            assertThat(verification.comparison().figuresCompared()).isEqualTo(3);
            assertThat(verification.dtOne().satisfied()).isTrue();
            assertThat(verification.provesReproduction()).isTrue();
            assertThat(verification.population().describe())
                .isEqualTo("2 contract(s) in scope = 1 computed + 1 quarantined");
        }

        @Test
        @DisplayName("a run that dropped a contract is refused, not reported as a drift")
        void aRunThatDroppedAContractIsRefused() {
            // A dropped contract surfaces in the comparison as MISSING_FROM_REPLAY, which is
            // indistinguishable from a determinism drift — so the night would be spent tracing
            // arithmetic for a row that was never computed. Named for what it is instead.
            assertThatIllegalStateException()
                .isThrownBy(() -> replay(
                    ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(),
                    List.of(ReplayFixtures.computed(ReplayFixtures.C1, 202704, SHADOW_ID,
                        ReplayFixtures.C1_CLOSING_GCA, ReplayFixtures.C1_EIR_INTEREST)),
                    ReplayFixtures.aprilStamps()))
                .withMessageContaining("does not account for its own population")
                .withMessageContaining("1 unaccounted for (" + ReplayFixtures.C2 + ")");
        }

        @Test
        @DisplayName("a run that returned a contract nobody asked for is refused too")
        void aRunThatInventedAContractIsRefused() {
            List<ContractResult> results = new ArrayList<>(
                ReplayFixtures.aprilResults(SHADOW_ID));
            results.add(ReplayFixtures.computed("LN-9999999", 202704, SHADOW_ID,
                "1000.00", "10.00"));
            assertThatIllegalStateException()
                .isThrownBy(() -> replay(
                    ReplayFixtures.aprilPorts(), ReplayFixtures.publishedApril(), results,
                    ReplayFixtures.aprilStamps()))
                .withMessageContaining("returned but not in scope (LN-9999999)");
        }

        @Test
        @DisplayName("the account can represent a population that does not add up")
        void theAccountCanRepresentAFailure() {
            // Not a tautology: PopulationAccount is able to say no, which is what gives the
            // refusal above something to say. Hand-derived: two in scope, one result, so one
            // unaccounted for and nothing extraneous.
            PopulationAccount account = PopulationAccount.of(
                List.of(ReplayFixtures.C1, ReplayFixtures.C2),
                List.of(ReplayFixtures.computed(ReplayFixtures.C1, 202704, "R", "1.00", "1.00")));
            assertThat(account.addsUp()).isFalse();
            assertThat(account.unaccounted()).containsExactly(ReplayFixtures.C2);
            assertThat(account.populationSize()).isEqualTo(2);
        }
    }

    /**
     * The trap this module's corollary of the signature defect names: an aggregation over an empty
     * population.
     */
    @Nested
    @DisplayName("A comparison that compared nothing is not a reproduction")
    class WhenTheComparisonComparedNothing {

        @Test
        @DisplayName("every contract quarantined on both sides: DT-1 fails although the"
            + " comparison alone would pass")
        void aPopulationThatProducedNoFigureFailsDtOne() {
            ReplayVerification verification = replay(
                ReplayFixtures.aprilPorts(),
                ReplayFixtures.publishedRun(
                    ReplayFixtures.APRIL_2027, Map.of(), ReplayFixtures.aprilStamps()),
                List.of(
                    ReplayFixtures.quarantined(ReplayFixtures.C1, SHADOW_ID),
                    ReplayFixtures.quarantined(ReplayFixtures.C2, SHADOW_ID)),
                ReplayFixtures.aprilStamps());

            assertThat(verification.comparison().figuresCompared()).isZero();
            assertThat(verification.comparison().isVacuous())
                .as("not vacuous by the upstream test — the policy leg compared two kinds — so"
                    + " ReplayComparison alone reports a pass, and that is the gap")
                .isFalse();
            assertThat(verification.comparison().dtOne().satisfied()).isTrue();

            assertThat(verification.coverage())
                .isEqualTo(ReplayCoverage.POPULATION_PRODUCED_NO_FIGURE);
            assertThat(verification.dtOne().satisfied())
                .as("two contracts in scope and not one figure compared: the period was not"
                    + " reproduced, it was not measured")
                .isFalse();
            assertThat(verification.dtOne().deviation())
                .as("one thing needs a remedy, and it is the comparison itself")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(verification.provesReproduction()).isFalse();
        }

        @Test
        @DisplayName("an empty period passes DT-1 and is still not a reproduction")
        void anEmptyPeriodPassesButProvesNothing() {
            ReplayFixtures.Ports ports = new ReplayFixtures.Ports(
                List.of(), ReplayFixtures.supersededTimeline());
            ReplayVerification verification = replay(
                ports,
                ReplayFixtures.publishedRun(
                    ReplayFixtures.APRIL_2027, Map.of(), ReplayFixtures.aprilStamps()),
                List.of(),
                ReplayFixtures.aprilStamps());

            assertThat(verification.coverage()).isEqualTo(ReplayCoverage.NOTHING_PUBLISHED);
            assertThat(verification.dtOne().satisfied())
                .as("nothing failed, and a control that is red by design gets suppressed")
                .isTrue();
            assertThat(verification.provesReproduction())
                .as("but 'the control found no exception' and 'the control ran' are not the same"
                    + " sentence")
                .isFalse();
        }

        @Test
        @DisplayName("a wholly vacuous comparison fails, as ReplayComparison already says")
        void aWhollyVacuousComparisonFails() {
            ReplayFixtures.Ports ports = new ReplayFixtures.Ports(
                List.of(), PolicyVersionRegistry.of());
            ReplayVerification verification = replay(
                ports,
                ReplayFixtures.publishedRun(ReplayFixtures.APRIL_2027, Map.of(), Map.of()),
                List.of(),
                Map.of());

            assertThat(verification.dtOne().satisfied()).isFalse();
            assertThat(verification.dtOne().detail()).contains("compared nothing");
            assertThat(verification.dtOne().deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Nested
    @DisplayName("Refusals: harness defects, not data conditions")
    class Refusals {

        @Test
        @DisplayName("DT-1's reference side cannot itself be a replay")
        void theReferenceSideCannotBeAReplay() {
            PublishedRun notAPublication = new PublishedRun(
                ReplayRun.replayOf("REPLAY-EARLIER", "RUN-202704-CLOSE", 202704,
                    ReplayFixtures.publishedFigures(), ReplayFixtures.aprilStamps()),
                ReplayFixtures.APRIL_2027, ReplayFixtures.RECORDED_AT, ReplayFixtures.BOOK);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> replay(
                    ReplayFixtures.aprilPorts(), notAPublication,
                    ReplayFixtures.aprilResults(SHADOW_ID), ReplayFixtures.aprilStamps()))
                .as("two replays agreeing with each other proves they agree with each other")
                .withMessageContaining("itself a replay");
        }

        @Test
        @DisplayName("the shadow run cannot carry the published run's own id")
        void theShadowRunIdMustDiffer() {
            ReplayRequest request = new ReplayRequest(
                ReplayFixtures.publishedApril(),
                ReplayFixtures.aprilPorts().liveTemplate(202704));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> request.shadowRunRequest(ReplayFixtures.PUBLISHED_RUN_ID))
                .withMessageContaining("cannot fail");
        }

        @Test
        @DisplayName("a template for another period, or another book, is refused")
        void aMismatchedTemplateIsRefused() {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayRequest(
                    ReplayFixtures.publishedApril(), ports.liveTemplate(202705)))
                .withMessageContaining("would read the wrong contract version set");
        }

        @Test
        @DisplayName("a verification cannot be built from a run that carried a live boundary")
        void aVerificationNeedsAReplayBoundary() {
            ReplayVerification good = faithfulAprilReplay();
            RunRequest live = ReplayFixtures.aprilPorts().liveTemplate(202704);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReplayVerification(
                    good.published(), live, good.shadow(), good.comparison(),
                    good.population(), good.coverage()))
                .withMessageContaining("reproduce nothing");
        }

        @Test
        @DisplayName("a published run paired with another period's record is refused")
        void aPublishedRunMustMatchItsPeriod() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishedRun(
                    ReplayRun.published("RUN-X", 202704, Map.of(), Map.of()),
                    ReplayFixtures.MAY_2027, ReplayFixtures.RECORDED_AT, ReplayFixtures.BOOK))
                .withMessageContaining("measured against the figures of the other");
        }

        @Test
        @DisplayName("two figures under one key is a defect in the run, not a finding")
        void twoFiguresUnderOneKeyIsRefused() {
            // Reachable only if the run returns one contract twice, which FailureIsolation.runBatch
            // refuses before any work starts. Refused here as well because the consequence — a
            // byte comparison whose answer depends on which figure was found first — is silent.
            List<ContractResult> twice = List.of(
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, "R", "1.00", "1.00"),
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, "R", "2.00", "1.00"));
            assertThatIllegalStateException()
                .isThrownBy(() -> ShadowRun.figures(twice))
                .withMessageContaining("two figures under key");
        }
    }

    @Nested
    @DisplayName("Control C-12: nightly, against a sampled period")
    class NightlyControl {

        /** One candidate: a published run for {@code period}, and a live template for it. */
        private ReplayRequest candidateFor(ClosedPeriod period) {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            return new ReplayRequest(
                ReplayFixtures.publishedRun(
                    period, ReplayFixtures.publishedFigures(), ReplayFixtures.aprilStamps()),
                ports.liveTemplate(period.periodId()));
        }

        private NightlyReplayReport night(
            ReplaySamplingBasis basis, Map<PolicyKind, String> replayStamps,
            List<ClosedPeriod> periods) {
            List<ReplayRequest> candidates = new ArrayList<>();
            for (ClosedPeriod period : periods) {
                candidates.add(candidateFor(period));
            }
            ReplayFixtures.FakeBatchJob job = ReplayFixtures.jobReturning(
                ReplayFixtures.aprilResults("SHADOW"), replayStamps);
            return new ReplayUseCase(job)
                .replayNightly(ReplayFixtures.NIGHT_OF, basis, candidates);
        }

        @Test
        @DisplayName("one period a night, one DT-1 result, and full coverage in three nights")
        void oneNightReplaysOnePeriod() {
            NightlyReplayReport report = night(
                ReplaySamplingBasis.nightlyDefault(), ReplayFixtures.aprilStamps(),
                List.of(ReplayFixtures.APRIL_2027, ReplayFixtures.MAY_2027,
                    ReplayFixtures.JUNE_2027));

            assertThat(report.sample().outcome()).isEqualTo(SampleOutcome.PERIOD_SELECTED);
            assertThat(report.verifications()).hasSize(1);
            assertThat(report.sample().nightsToCoverEligible())
                .as("three eligible periods at one a night: the rotation visits every one within"
                    + " three nights, which a uniform random draw cannot promise")
                .isEqualTo(3);
            assertThat(report.controlRan()).isTrue();
            assertThat(report.allReproduced()).isTrue();
            assertThat(report.dtOne()).isPresent();
            assertThat(report.dtOne().orElseThrow().satisfied()).isTrue();
            assertThat(report.invariantResults())
                .as("one result per invariant, and DT-1 is the only one this control publishes")
                .hasSize(1);
        }

        @Test
        @DisplayName("the night's deviation is the sum across periods, not the first breach's")
        void theNightsDeviationIsTheSumAcrossPeriods() {
            // Two periods replayed, each with the same policy divergence: 1 + 1 = 2.
            // InvariantResult.conjunction would have reported 1 — it keeps only the first
            // breach's deviation among results sharing an id — and the second period's finding
            // would have been silently dropped.
            NightlyReplayReport report = night(
                new ReplaySamplingBasis(2, 12, "a deeper sample for this test"),
                ReplayFixtures.stampsResolvedAtTheReplayDate(),
                List.of(ReplayFixtures.APRIL_2027, ReplayFixtures.MAY_2027));

            assertThat(report.verifications()).hasSize(2);
            assertThat(report.breaches()).hasSize(2);
            InvariantResult dtOne = report.dtOne().orElseThrow();
            assertThat(dtOne.id()).isEqualTo(InvariantId.DT_1);
            assertThat(dtOne.satisfied()).isFalse();
            assertThat(dtOne.deviation()).isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(report.allReproduced()).isFalse();
        }

        @Test
        @DisplayName("a basis dialled to zero publishes no DT-1 and is not a clean night")
        void anInertBasisPublishesNoDtOne() {
            NightlyReplayReport report = night(
                new ReplaySamplingBasis(0, 12, "dialled down during a release freeze"),
                ReplayFixtures.aprilStamps(),
                List.of(ReplayFixtures.APRIL_2027, ReplayFixtures.MAY_2027));

            assertThat(report.sample().outcome()).isEqualTo(SampleOutcome.CONTROL_INERT);
            assertThat(report.controlRan()).isFalse();
            assertThat(report.dtOne())
                .as("a pass would be a tautology on a control that did not run, and a fail would"
                    + " report 'the replay differed' about a replay that never happened")
                .isEmpty();
            assertThat(report.invariantResults()).isEmpty();
            assertThat(report.allReproduced())
                .as("an empty night is not a clean night")
                .isFalse();
            assertThat(report.coverageWarning()).isPresent();
            assertThat(report.coverageWarning().orElseThrow())
                .contains("replays 0 periods a night");
        }

        @Test
        @DisplayName("a stale lookback is named as a control that has stopped running")
        void aStaleLookbackIsNamed() {
            // The lookback was right when it was chosen. Tonight is 2027-08-10, its own period is
            // 2027-08, and a lookback of 1 admits only 2027-07 and 2027-08 — so April, May and
            // June are all excluded and nothing is selected.
            NightlyReplayReport report = night(
                new ReplaySamplingBasis(1, 1, "six months, chosen in 2027 and never revisited"),
                ReplayFixtures.aprilStamps(),
                List.of(ReplayFixtures.APRIL_2027, ReplayFixtures.MAY_2027,
                    ReplayFixtures.JUNE_2027));

            assertThat(report.sample().eligible()).isEmpty();
            assertThat(report.controlRan()).isFalse();
            assertThat(report.coverageWarning()).isPresent();
            assertThat(report.coverageWarning().orElseThrow()).contains("has gone stale");
        }

        @Test
        @DisplayName("a book with nothing closed yet is a fact, and still publishes no DT-1")
        void nothingClosedYetIsNotADefect() {
            NightlyReplayReport report = night(
                ReplaySamplingBasis.nightlyDefault(), ReplayFixtures.aprilStamps(), List.of());

            assertThat(report.sample().outcome()).isEqualTo(SampleOutcome.NOTHING_CLOSED_YET);
            assertThat(report.dtOne()).isEmpty();
            assertThat(report.allReproduced()).isFalse();
            assertThat(report.coverageWarning()).isPresent();
            assertThat(report.coverageWarning().orElseThrow()).contains("NOTHING_CLOSED_YET");
        }

        @Test
        @DisplayName("two published runs for one period are refused")
        void twoPublishedRunsForOnePeriodAreRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> night(
                    ReplaySamplingBasis.nightlyDefault(), ReplayFixtures.aprilStamps(),
                    List.of(ReplayFixtures.APRIL_2027, ReplayFixtures.APRIL_2027)))
                .withMessageContaining("a period has one published artefact");
        }

        @Test
        @DisplayName("the report's shape has to match its own sample")
        void theReportMustMatchItsSample() {
            NightlyReplayReport ran = night(
                ReplaySamplingBasis.nightlyDefault(), ReplayFixtures.aprilStamps(),
                List.of(ReplayFixtures.APRIL_2027));
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new NightlyReplayReport(ran.sample(), List.of()))
                .withMessageContaining("C-12's evidence is the basis as much as the outcome");
        }
    }

    @Nested
    @DisplayName("The audit sentence states the denominators")
    class TheAuditSentence {

        @Test
        @DisplayName("a pass names how much was compared, so it cannot read like a pass over"
            + " nothing")
        void thePassNamesItsDenominators() {
            String sentence = faithfulAprilReplay().describe();
            assertThat(sentence)
                .contains("6 figures and 2 policy kinds compared")
                .contains("2 contract(s) in scope = 2 computed + 0 quarantined")
                .contains("FIGURES_COMPARED")
                .contains(ReplayFixtures.RECORDED_AT.toString());
        }

        @Test
        @DisplayName("an inert night says so in one sentence")
        void anInertNightSaysSo() {
            ReplayFixtures.Ports ports = ReplayFixtures.aprilPorts();
            ReplayRequest candidate = new ReplayRequest(
                ReplayFixtures.publishedApril(), ports.liveTemplate(202704));
            NightlyReplayReport report = new ReplayUseCase(
                ReplayFixtures.jobReturning(List.of(), Map.of()))
                .replayNightly(ReplayFixtures.NIGHT_OF,
                    new ReplaySamplingBasis(0, 12, "frozen"), List.of(candidate));
            assertThat(report.describe()).contains("reads as coverage and is not");
        }
    }

    @Nested
    @DisplayName("Guards on the seams")
    class Seams {

        @Test
        @DisplayName("a run output with no contracts still reports its stamps")
        void anEmptyOutputStillCarriesItsStamps() {
            RunOutput output = RunOutput.of(List.of(), ReplayFixtures.aprilStamps());
            assertThat(output.computedCount()).isZero();
            assertThat(output.quarantinedCount()).isZero();
            assertThat(output.policyVersionsConsulted())
                .containsEntry(PolicyKind.FEE_RULE_SET, "FEE-2027.1");
        }

        @Test
        @DisplayName("the counts of a mixed output add up")
        void theCountsOfAMixedOutputAddUp() {
            RunOutput output = RunOutput.of(
                List.of(
                    ReplayFixtures.computed(ReplayFixtures.C1, 202704, "R", "1.00", "1.00"),
                    ReplayFixtures.quarantined(ReplayFixtures.C2, "R")),
                ReplayFixtures.aprilStamps());
            assertThat(output.computedCount()).isEqualTo(1);
            assertThat(output.quarantinedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the closing-balance key and the journal-line key are the documented shapes")
        void theFigureKeysAreTheDocumentedShapes() {
            assertThat(ShadowRun.closingGcaKey("LN-1"))
                .isEqualTo("CONTRACT:LN-1:closing_gca");
            assertThat(ShadowRun.journalLineKey("LN-1", 2, "4101-INTEREST-INCOME", "CR"))
                .isEqualTo("CONTRACT:LN-1:journal:2:4101-INTEREST-INCOME:CR");
        }

        @Test
        @DisplayName("the shadow figures carry the scale the run produced, unrescaled")
        void theShadowFiguresAreNotRescaled() {
            // Money.atPresentationScale() would make this 2dp. A reduction applied in ShadowRun
            // would erase the SCALE_ONLY finding the package exists to raise, so the four-decimal
            // amount has to survive verbatim.
            Map<String, Money> figures = ShadowRun.figures(List.of(
                ReplayFixtures.computed(ReplayFixtures.C1, 202704, "R", "958295.9100", "1.00")));
            Optional<Money> balance = Optional.ofNullable(
                figures.get("CONTRACT:" + ReplayFixtures.C1 + ":closing_gca"));
            assertThat(balance).isPresent();
            assertThat(balance.orElseThrow().amount().toPlainString()).isEqualTo("958295.9100");
        }
    }
}
