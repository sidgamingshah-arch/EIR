package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every query in this module is checked for the decision-time predicate, by name.
 *
 * <p><b>Why a test over SQL text is worth having.</b> The whole claim of this module is that a read as
 * at an earlier {@code recordedAsAt} returns the version set that was recorded then. The way that
 * claim fails is not dramatic: somebody adds a query, or copies one, and leaves out the system-time
 * clause. The query then runs, returns rows, and returns <em>today's</em> rows at every boundary —
 * which is precisely the in-memory behaviour ADR-0011 says is "not enough to make a replay a fair
 * test of bitemporality". No result is empty, no exception is thrown, and every figure is internally
 * consistent.
 *
 * <p>The live-cluster suite catches this too, and it needs a PostgreSQL 16 server. This test needs
 * nothing, so it runs on every build, which is where a regression of this shape has to be caught.
 *
 * <p><b>Expected values are written out by hand</b> from 04 § 5's column names —
 * {@code valid_from} / {@code valid_to} for business time, {@code recorded_at} /
 * {@code superseded_at} for system time — and never read back from {@link TemporalReads}. A test that
 * asks the class under test what it says and then agrees with it asserts nothing.
 */
class PortQueryTemporalCoverageTest {

    /**
     * Every query this module issues that reads a table with a system-time axis.
     *
     * <p>The three that are absent are absent for stated reasons and are asserted separately below:
     * {@code SELECT_PERIOD_DATES} reads the accounting calendar (which is not versioned),
     * {@code SELECT_SCHEDULE} and {@code SELECT_LINES} reach the contract version through a key that
     * has already been resolved as at the boundary, and {@code SELECT_FEE_POSTINGS} reads a table 04
     * gives no system-time column.
     */
    private static Map<String, String> queriesWithSystemTimeAxis() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("ContractTermsReader.SELECT_BY_CONTRACT_ID",
            ContractTermsReader.SELECT_BY_CONTRACT_ID);
        queries.put("JdbcContractSource.SELECT_POPULATION",
            JdbcContractSource.SELECT_POPULATION);
        queries.put("JdbcContractStateSource.SELECT_BILLED",
            JdbcContractStateSource.SELECT_BILLED);
        queries.put("JdbcCoreBankingFeed.SELECT_BILLED_FOR_PERIOD",
            JdbcCoreBankingFeed.SELECT_BILLED_FOR_PERIOD);
        queries.put("JdbcGeneralLedgerSource.SELECT_CONTROL_BALANCES",
            JdbcGeneralLedgerSource.SELECT_CONTROL_BALANCES);
        queries.put("JdbcContractPeriodSource.SELECT_CASH_SPLIT",
            JdbcContractPeriodSource.SELECT_CASH_SPLIT);
        queries.put("JdbcContractPeriodSource.SELECT_SUSPENSE_MOVEMENT",
            JdbcContractPeriodSource.SELECT_SUSPENSE_MOVEMENT);
        queries.put("JdbcContractPeriodSource.SELECT_EVENT",
            JdbcContractPeriodSource.SELECT_EVENT);
        queries.put("JdbcOnboardingSource.SELECT_ONBOARDING_ATTRIBUTE",
            JdbcOnboardingSource.SELECT_ONBOARDING_ATTRIBUTE);
        return queries;
    }

    @Nested
    @DisplayName("the system-time predicate")
    class SystemTime {

        @Test
        @DisplayName("every query over a versioned table bounds recorded_at at the boundary")
        void everyQueryBoundsRecordedAt() {
            queriesWithSystemTimeAxis().forEach((name, sql) ->
                assertThat(sql)
                    .as("%s must bound recorded_at at the boundary; without it the query returns"
                        + " today's rows at every recordedAsAt and a replay proves nothing", name)
                    .contains("recorded_at <= ?"));
        }

        @Test
        @DisplayName("every query over a versioned table excludes rows superseded by the boundary")
        void everyQueryExcludesSupersededRows() {
            queriesWithSystemTimeAxis().forEach((name, sql) ->
                assertThat(sql)
                    .as("%s must exclude rows superseded before the boundary. recorded_at alone is"
                        + " not enough: an amendment recorded last year is still visible today, so"
                        + " a query with only the lower bound returns both the original and its"
                        + " correction and the answer is whichever the planner returned first",
                        name)
                    .contains("superseded_at IS NULL")
                    .contains("superseded_at > ?"));
        }

        @Test
        @DisplayName("the two solve queries walk the supersession chain, not superseded_by IS NULL")
        void solveQueriesWalkTheSupersessionChain() {
            // eir_computation has no superseded_at; V1 gives it a forward pointer. Filtering on
            // `superseded_by IS NULL` alone would return today's belief at every boundary — the
            // exact defect this module exists to remove — so the visible solve is one whose
            // SUPERSEDING row was not yet recorded. Both queries that read a solve must do this.
            assertThat(JdbcContractStateSource.SELECT_RATE_IN_FORCE)
                .as("the rate in force must be resolved through the supersession chain")
                .contains("NOT EXISTS")
                .contains("s.computation_id = e.superseded_by")
                .contains("s.recorded_at <= ?");
            assertThat(JdbcContractPeriodSource.SELECT_SOLVED_CONVENTION)
                .as("the stored convention must be resolved through the same chain, or the"
                    + " amortisation would use a convention the rate was not solved under")
                .contains("NOT EXISTS")
                .contains("s.computation_id = e.superseded_by")
                .contains("s.recorded_at <= ?");
        }

        @Test
        @DisplayName("the stage read bounds received_at, which is V2's system-time column")
        void stageReadBoundsReceivedAt() {
            // 04 § 2.9: received_at "is when the engine learned it (system time, 04 § 5) and is
            // supplied, not defaulted". Reading a stage assignment received after the boundary
            // would make a Stage 3 replay consume an ECL output the original run never saw, which
            // is the failure that column exists to prevent.
            assertThat(JdbcContractStateSource.SELECT_STAGE)
                .as("stage_assignment.received_at must be bounded at the boundary")
                .contains("sa.received_at <= ?");
        }

        @Test
        @DisplayName("the policy read bounds approved_at, which is policy_version's system axis")
        void policyReadBoundsApprovedAt() {
            assertThat(JdbcPolicySource.SELECT_VISIBLE_VERSIONS)
                .as("a policy version approved after the boundary was not known then; 05 § 3.3"
                    + " requires a replay to read 'the policy and rule-set versions effective then'")
                .contains("pv.approved_at <= ?");
            assertThat(JdbcPolicySource.SELECT_VISIBLE_VERSIONS)
                .as("drafts and pending submissions must not resolve; V2 says so on its own"
                    + " exclusion constraint, and SUPERSEDED must remain because a closed period"
                    + " still resolves against it (DT-1)")
                .contains("'APPROVED', 'EFFECTIVE', 'SUPERSEDED'");
        }

        @Test
        @DisplayName("the prior-period balance read bounds the run's completion instant")
        void priorBalanceBoundsRunCompletion() {
            // The decision-time axis on the balance leg. A re-run of period P-1 that completed
            // after this boundary must be invisible, or a prior-period correction silently changes
            // every replay downstream of it.
            assertThat(JdbcContractStateSource.SELECT_PRIOR_CLOSING)
                .as("the prior period's closing balances must come from a run completed by the"
                    + " boundary")
                .contains("r.status = 'COMPLETED'")
                .contains("r.completed_at <= ?");
        }
    }

    @Nested
    @DisplayName("the business-time predicate")
    class BusinessTime {

        @Test
        @DisplayName("both contract_version reads bound valid_from and valid_to")
        void contractVersionReadsBoundBusinessTime() {
            // Business time and system time answer different questions and neither substitutes for
            // the other: a backdated amendment is one row with an earlier valid_from and a current
            // recorded_at, and only the conjunction reproduces the closed period.
            assertThat(ContractTermsReader.SELECT_BY_CONTRACT_ID)
                .contains("cv.valid_from <= ?")
                .contains("cv.valid_to IS NULL")
                .contains("cv.valid_to > ?");
            assertThat(JdbcContractSource.SELECT_POPULATION)
                .contains("cv.valid_from <= ?")
                .contains("cv.valid_to IS NULL")
                .contains("cv.valid_to > ?");
        }

        @Test
        @DisplayName("the upper bound is exclusive, matching the DDL's own half-open ranges")
        void upperBoundsAreExclusive() {
            // V1's contract_version_no_overlap_ck excludes on daterange(valid_from, valid_to),
            // which is '[)'. A read using >= would see two versions on the single day one ends and
            // its successor begins.
            assertThat(TemporalReads.businessTime("cv"))
                .as("business time must not use >= on the upper bound")
                .doesNotContain("valid_to >= ?");
            assertThat(TemporalReads.systemTime("cv"))
                .as("system time must not use >= on the upper bound")
                .doesNotContain("superseded_at >= ?");
        }
    }

    @Nested
    @DisplayName("the population query")
    class Population {

        @Test
        @DisplayName("reaches the contract through contract_version, which is the only temporal one")
        void populationIsDefinedByContractVersion() {
            // CONTRACT carries no temporal columns — V1: "there is no history to keep of a fact
            // that cannot change" — so a population query over CONTRACT alone could not answer
            // "was this contract in scope as at that instant" at all.
            assertThat(JdbcContractSource.SELECT_POPULATION)
                .contains("JOIN contract_version cv")
                .contains("ON cv.contract_id = c.contract_id");
        }

        @Test
        @DisplayName("excludes FVTPL at the ingestion boundary, per FR-103")
        void populationExcludesFvtpl() {
            assertThat(JdbcContractSource.SELECT_POPULATION)
                .as("FR-103 excludes a fair-value instrument from EIR processing entirely, and V1"
                    + " puts the exclusion at the ingestion boundary because a cross-table rule"
                    + " cannot be a row constraint")
                .contains("c.measurement_category <> 'FVTPL'");
        }

        @Test
        @DisplayName("is ordered, because FR-903 requires the same run twice to be identical")
        void populationIsOrdered() {
            assertThat(JdbcContractSource.SELECT_POPULATION).contains("ORDER BY c.contract_id");
        }

        @Test
        @DisplayName("the onboarding read does NOT exclude FVTPL, so the gate can check it")
        void onboardingDoesNotPreFilterCategory() {
            // MeasurementGate compares the declared category against the SPPI assessment; where
            // they disagree the assessment wins and ST-12 breaches. Filtering FVTPL out here would
            // make that comparison impossible and the breach undetectable.
            assertThat(ContractTermsReader.SELECT_BY_CONTRACT_ID)
                .as("the terms read is shared by onboarding and must not filter on category")
                .doesNotContain("measurement_category <>");
        }
    }

    @Nested
    @DisplayName("no clock, anywhere")
    class NoClock {

        @Test
        @DisplayName("no query names now() or current_timestamp")
        void noQueryReadsTheDatabaseClock() {
            // The database's clock is a clock. A query defaulting a boundary to now() would be
            // ADR-0003's defect moved one process to the left, and it would be invisible in Java.
            queriesWithSystemTimeAxis().forEach((name, sql) -> {
                String lower = sql.toLowerCase(java.util.Locale.ROOT);
                assertThat(lower)
                    .as("%s must take its instant from the AsAtBoundary, never from the server", name)
                    .doesNotContain("now()")
                    .doesNotContain("current_timestamp")
                    .doesNotContain("current_date")
                    .doesNotContain("clock_timestamp");
            });
        }
    }
}
