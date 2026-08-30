package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource.OpeningState;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one property nothing else in this repository can deliver: a read as at an earlier
 * {@code recordedAsAt} returns the version set that was recorded then.
 *
 * <h2>Why this test is the unit</h2>
 *
 * <p>ADR-0011 states the gap: the seven ports "are implemented in memory by {@code eir-api}, which is
 * enough to run the engine and not enough to make a replay a fair test of bitemporality — a map
 * answers the same thing at every as-at boundary". So a replay against the in-memory book reproduces
 * whatever the boundary, and reproducing proves nothing: the run would reproduce equally well if
 * {@link AsAtBoundary#recordedAsAt()} were discarded on entry. Invariant DT-1 has, until this class,
 * had nothing it could actually fail on.
 *
 * <p>Every assertion below is the same query, over the same rows, at two boundary instants, expecting
 * two different answers. A query that ignored the boundary would pass every single-instant test in
 * this module and fail here — which is what makes this the class to run after touching any SQL.
 *
 * <h2>The fixture, in one paragraph</h2>
 *
 * <p>One contract carries two {@code contract_version} rows with <b>identical business validity</b>
 * (both valid from 1 April 2027) and different decision times: version 1 recorded 1 May, superseded
 * 1 June; version 2 recorded 1 June. That is 04 § 5's backdated amendment exactly, and business time
 * cannot separate the two — only {@code recorded_at} / {@code superseded_at} can. Four other legs
 * carry the same treatment so the property is demonstrated rather than assumed: the CBS extract is
 * restated, the EIR solve is superseded through {@code superseded_by}, a second contract is onboarded
 * after the first instant, and a policy version is approved after it.
 *
 * <p>Expected values are reference case 1's own figures, written out by hand: principal
 * ₹10,00,000.00, periodic EIR {@code 0.010421491800} at the twelve decimal places
 * {@code NUMERIC(20,12)} exists to hold, and month 13 opening gross carrying amount 5,28,407.32 —
 * which, being an opening figure, is the closing figure of the period before.
 */
@Tag("live-db")
class BitemporalReadLiveTest {

    private static DataSource dataSource;
    private static JdbcPorts ports;

    /**
     * The boundary the April 2027 close was originally computed at.
     *
     * <p>A replay boundary, built through {@link AsAtBoundary#replaying}, because that is what a
     * replay of that close would hand every port.
     */
    private static final AsAtBoundary AS_ORIGINALLY_KNOWN = AsAtBoundary.replaying(
        Fixtures.PERIOD_END, Fixtures.AS_AT_ORIGINAL, "RUN-202704-ORIGINAL");

    /** The boundary a live run today would use: the same period end, current knowledge. */
    private static final AsAtBoundary AS_KNOWN_NOW = AsAtBoundary.live(
        Fixtures.PERIOD_END, Fixtures.AS_AT_CORRECTED);

    @BeforeAll
    static void seed() {
        dataSource = LiveDatabase.dataSource();
        Fixtures.seed(dataSource);
        ports = new JdbcPorts(dataSource, Fixtures.BOOK_ID);
    }

    @Nested
    @DisplayName("contract terms: the backdated amendment")
    class Terms {

        @Test
        @DisplayName("the earlier boundary reads the principal that was recorded then")
        void earlierBoundaryReadsTheOriginalPrincipal() {
            OpeningState state = require(AS_ORIGINALLY_KNOWN);

            // Ten lakh at the working scale. Version 2 replaced this with nine lakh on 1 June
            // against the SAME valid_from, so a query filtering only on business time would return
            // the corrected figure here and the replay would disagree with the published close.
            assertThat(state.terms().principal().amount().toPlainString())
                .as("as at 2 May 2027 the engine had only version 1 on file")
                .isEqualTo("1000000.000000");
            assertThat(state.terms().contractualRate().periodic().toPlainString())
                .isEqualTo("0.010000000000");
        }

        @Test
        @DisplayName("the later boundary reads the correction, from the same rows")
        void laterBoundaryReadsTheCorrectedPrincipal() {
            OpeningState state = require(AS_KNOWN_NOW);

            assertThat(state.terms().principal().amount().toPlainString())
                .as("as at 2 June 2027 version 1 is superseded and version 2 stands")
                .isEqualTo("900000.000000");
            assertThat(state.terms().contractualRate().periodic().toPlainString())
                .isEqualTo("0.011000000000");
        }

        @Test
        @DisplayName("and the two answers differ, which is the whole claim")
        void theTwoBoundariesDisagree() {
            // Stated as its own assertion so that a regression making both boundaries return the
            // same thing fails here with the right message, rather than making one of the two tests
            // above fail and reading as an arithmetic problem.
            assertThat(require(AS_ORIGINALLY_KNOWN).terms().principal())
                .as("a source answering the same thing at both boundaries would be the in-memory"
                    + " book with a database behind it, and DT-1 would again have nothing to check")
                .isNotEqualTo(require(AS_KNOWN_NOW).terms().principal());
        }
    }

    @Nested
    @DisplayName("the EIR solve: superseded through a forward pointer")
    class Solve {

        @Test
        @DisplayName("the earlier boundary reads the rate whose superseding solve was not yet recorded")
        void earlierBoundaryReadsTheOriginalRate() {
            // eir_computation has no superseded_at; V1 gives it superseded_by, a forward pointer.
            // A query filtering on `superseded_by IS NULL` would return today's belief at every
            // boundary — so this assertion is what proves the supersession chain is being walked.
            OpeningState state = require(AS_ORIGINALLY_KNOWN);

            // Checked before the rate is read, because the failure mode of a chain-walk regression
            // is that the EARLIER boundary sees no solve at all: today's row is recorded after it,
            // and the row that was current then has a non-null superseded_by. The pipeline would
            // then treat a contract solved a year ago as one needing onboarding.
            assertThat(state.hasBeenSolved())
                .as("a rate was in force at 2 May 2027 and the boundary must see it")
                .isTrue();
            assertThat(state.eir().periodic().toPlainString())
                .as("reference case 1's periodic EIR, at all twelve stored decimal places")
                .isEqualTo("0.010421491800");
        }

        @Test
        @DisplayName("the later boundary reads the superseding solve")
        void laterBoundaryReadsTheSupersedingRate() {
            assertThat(require(AS_KNOWN_NOW).eir().periodic().toPlainString())
                .isEqualTo("0.011500000000");
        }

        @Test
        @DisplayName("the rate keeps its twelve decimal places, which is why getBigDecimal is used")
        void theRateIsNotRounded() {
            // FR-404 makes the stored rate the one every downstream period uses. Read through
            // getDouble the twelfth place would be gone, the accretion would drift by a few paise a
            // month, no tolerance would fire, and the sub-ledger would stop tying to the general
            // ledger by an amount nobody could attribute.
            assertThat(require(AS_ORIGINALLY_KNOWN).eir().periodic().scale())
                .as("NUMERIC(20,12) must arrive with scale 12 intact")
                .isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("the CBS extract: restated after the close")
    class CoreBanking {

        @Test
        @DisplayName("opening state carries the billed figure visible at each boundary")
        void billedInterestFollowsTheBoundary() {
            assertThat(require(AS_ORIGINALLY_KNOWN).contractualInterestBilled()
                .atPresentationScale().amount().toPlainString())
                .as("the extract the original close consumed")
                .isEqualTo("5284.07");
            assertThat(require(AS_KNOWN_NOW).contractualInterestBilled()
                .atPresentationScale().amount().toPlainString())
                .as("the restated extract")
                .isEqualTo("5000.00");
        }

        @Test
        @DisplayName("the feed port agrees with the state port at both boundaries")
        void theFeedPortAgrees() {
            // Two different queries over the same table. They must agree, or RC-1 would compare the
            // engine's projection against one extract while the exception report named another.
            assertThat(ports.coreBanking().billedInterest(AS_ORIGINALLY_KNOWN))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.billedInterest().atPresentationScale().amount().toPlainString())
                        .isEqualTo("5284.07");
                    assertThat(line.feedReference()).isEqualTo("CBS-EOD-202704-V1");
                });
            assertThat(ports.coreBanking().billedInterest(AS_KNOWN_NOW))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.billedInterest().atPresentationScale().amount().toPlainString())
                        .isEqualTo("5000.00");
                    assertThat(line.feedReference()).isEqualTo("CBS-EOD-202704-V2");
                });
        }
    }

    @Nested
    @DisplayName("the population")
    class Population {

        @Test
        @DisplayName("a contract onboarded after the boundary is not in the replay's population")
        void aLaterContractIsInvisibleToTheEarlierBoundary() {
            // ContractSource's javadoc names this failure: "a contract onboarded since would appear
            // in the replay and not in the published figures". An in-memory map cannot express it.
            assertThat(ports.contracts().contractIdsInScope(AS_ORIGINALLY_KNOWN))
                .as("only the contract whose first version was recorded by 2 May")
                .containsExactly(Fixtures.CONTRACT_ID);

            assertThat(ports.contracts().contractIdsInScope(AS_KNOWN_NOW))
                .as("the later contract joins the population once its version is recorded")
                .containsExactlyInAnyOrder(Fixtures.CONTRACT_ID, Fixtures.LATE_CONTRACT_ID);
        }

        @Test
        @DisplayName("the FVTPL contract is in neither population, at either boundary")
        void fvtplIsExcludedAtTheIngestionBoundary() {
            // FR-103: a fair-value instrument carries no EIR and is excluded from EIR processing
            // entirely. V1 puts the exclusion at the ingestion boundary "because a cross-table rule
            // cannot be a row constraint", and this port IS that boundary — it defines the
            // population every later FR-905 count is taken against.
            assertThat(ports.contracts().contractIdsInScope(AS_ORIGINALLY_KNOWN))
                .doesNotContain(Fixtures.FVTPL_CONTRACT_ID);
            assertThat(ports.contracts().contractIdsInScope(AS_KNOWN_NOW))
                .doesNotContain(Fixtures.FVTPL_CONTRACT_ID);
        }

        @Test
        @DisplayName("the population is ordered, so two runs enumerate identically (FR-903)")
        void thePopulationIsOrdered() {
            List<String> ids = ports.contracts().contractIdsInScope(AS_KNOWN_NOW);
            assertThat(ids).isSorted();
            assertThat(ids).isEqualTo(ports.contracts().contractIdsInScope(AS_KNOWN_NOW));
        }
    }

    @Nested
    @DisplayName("policy versions")
    class Policy {

        @Test
        @DisplayName("a version approved after the boundary does not enter the replay")
        void aLaterApprovalIsInvisible() {
            // 05 § 3.3 requires a replay to read "the policy and rule-set versions effective then".
            // Without the approved_at bound, a routing-table amendment approved this morning would
            // enter the replay of every closed period: every figure internally consistent, none
            // reproducing, and DT-1 firing with nothing to point at.
            PolicyVersionRegistry asThen = ports.policySource().policyVersions(AS_ORIGINALLY_KNOWN);
            assertThat(asThen.historyOf(PolicyKind.ROUTING_TABLE))
                .as("only the version approved by 1 May")
                .extracting(PolicyVersion::id)
                .containsExactly(Fixtures.ROUTING_APRIL_ID);

            PolicyVersionRegistry asNow = ports.policySource().policyVersions(AS_KNOWN_NOW);
            assertThat(asNow.historyOf(PolicyKind.ROUTING_TABLE))
                .extracting(PolicyVersion::id)
                .containsExactlyInAnyOrder(
                    Fixtures.ROUTING_APRIL_ID, Fixtures.ROUTING_MAY_ID);
        }

        @Test
        @DisplayName("resolution on a May date changes with the boundary, not with the query date")
        void resolutionFollowsTheBoundary() {
            // The same business date asked of two boundaries. This is the assertion that would fail
            // if the adapter pre-filtered on effective_from and left decision time to chance.
            LocalDate inMay = LocalDate.of(2027, 5, 15);
            assertThat(ports.policySource().policyVersions(AS_ORIGINALLY_KNOWN)
                .inForceOn(PolicyKind.ROUTING_TABLE, inMay))
                .as("as at 2 May, the only routing table the engine had is the April one")
                .isPresent()
                .get()
                .extracting(PolicyVersion::id)
                .isEqualTo(Fixtures.ROUTING_APRIL_ID);

            assertThat(ports.policySource().policyVersions(AS_KNOWN_NOW)
                .inForceOn(PolicyKind.ROUTING_TABLE, inMay))
                .as("as at 2 June, latest-wins selects the May version")
                .isPresent()
                .get()
                .extracting(PolicyVersion::id)
                .isEqualTo(Fixtures.ROUTING_MAY_ID);
        }
    }

    @Nested
    @DisplayName("what does not move with the boundary, and should not")
    class Stable {

        @Test
        @DisplayName("the opening balance is the same at both, because the prior run predates both")
        void theOpeningBalanceIsStable() {
            // The prior period's run completed 1 April, before either instant, so both boundaries
            // see it. That this leg does NOT move is as important as the legs that do: a source
            // returning different balances at two boundaries for no recorded reason would be a
            // different defect wearing the same clothes.
            assertThat(require(AS_ORIGINALLY_KNOWN).openingGca()
                .atPresentationScale().amount().toPlainString())
                .as("reference case 1's month 13 opening GCA")
                .isEqualTo("528407.32");
            assertThat(require(AS_KNOWN_NOW).openingGca())
                .isEqualTo(require(AS_ORIGINALLY_KNOWN).openingGca());
        }

        @Test
        @DisplayName("the ECL engine version travels with the stage, at both boundaries")
        void theEclEngineVersionIsCarried() {
            // 04 § 2.9 makes it NOT NULL because "re-running a period with today's ECL output
            // produces a different answer and invariant DT-1 fails". OpeningState refuses to be
            // built without it, so this assertion also checks the column is being read.
            assertThat(require(AS_ORIGINALLY_KNOWN).eclEngineVersion()).isEqualTo("ECL-2027.04.1");
            assertThat(require(AS_KNOWN_NOW).eclEngineVersion()).isEqualTo("ECL-2027.04.1");
        }
    }

    @Nested
    @DisplayName("the Optional, honoured literally")
    class Absences {

        @Test
        @DisplayName("a contract the boundary cannot see has no opening state")
        void aLaterContractHasNoStateAtTheEarlierBoundary() {
            assertThat(ports.contractState()
                .openingState(Fixtures.LATE_CONTRACT_ID, AS_ORIGINALLY_KNOWN))
                .as("empty rather than an exception: FR-905 reports this per contract")
                .isEmpty();
        }

        @Test
        @DisplayName("a contract with no prior balance has no opening state, even when visible")
        void noPriorBalanceIsAnEmptyOptional() {
            // The late contract IS visible at the later boundary and still has no period_balance for
            // March 2027. That is the initial-recognition case, and it is reported rather than
            // defaulted to zero: a zero opening GCA on a live loan reconciles to nothing while
            // looking exactly like a repaid one.
            assertThat(ports.contracts().contractIdsInScope(AS_KNOWN_NOW))
                .contains(Fixtures.LATE_CONTRACT_ID);
            assertThat(ports.contractState()
                .openingState(Fixtures.LATE_CONTRACT_ID, AS_KNOWN_NOW))
                .isEmpty();
        }

        @Test
        @DisplayName("an id that names no contract is empty, not an error")
        void anUnknownContractIsEmpty() {
            assertThat(ports.contractState()
                .openingState("99999999-9999-4999-8999-999999999999", AS_KNOWN_NOW))
                .isEmpty();
        }
    }

    private static OpeningState require(AsAtBoundary boundary) {
        Optional<OpeningState> state =
            ports.contractState().openingState(Fixtures.CONTRACT_ID, boundary);
        assertThat(state)
            .as("the fixture contract must have an opening state at %s", boundary.recordedAsAt())
            .isPresent();
        return state.orElseThrow();
    }
}
