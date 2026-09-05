package com.crisil.eir.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The shared fixture's rows plus the four awkward conditions the shared fixture cannot hold.
 *
 * <p>Seeded into {@link LatentDefectDatabase}, never into the shared one. Each addition exists to
 * make one recorded latent defect observable, and every one of them is <b>legal data</b> — accepted
 * by every CHECK and every foreign key in V1, V2 and V3. That is the point: none of these is a
 * corrupt row that a constraint should have caught. They are the shapes a real feed produces.
 *
 * <table>
 *   <tr><th>Contract</th><th>What is awkward</th><th>Which defect it exposes</th></tr>
 *   <tr><td>{@link #OTHER_BOOK_ID}</td><td>book_id 'IGAAP', same facility reference</td>
 *       <td>{@code SELECT_POPULATION} carries no book predicate</td></tr>
 *   <tr><td>{@link #ACTUAL_DATE_ID}</td><td>MONTHLY schedule, solve stored ACTUAL_DATE</td>
 *       <td>{@code readRateInForce} ignores {@code eir_computation.convention}</td></tr>
 *   <tr><td>{@link #BOUNDARY_FLOW_ID}</td><td>flow dated 2027-04-01, ON period 202704's start
 *       date, filed under 202704 — the shape the misfiling guard must NOT refuse</td></tr>
 *   <tr><td>{@link #MISFILED_FLOW_ID}</td><td>flow dated 2027-05-15 filed under period 202704</td>
 *       <td>the flow window is the calendar month, so the vector comes back empty</td></tr>
 *   <tr><td>{@link #WEEKLY_ID}</td><td>WEEKLY schedule, four flows inside April</td>
 *       <td>several accrual boundaries in one period, which the pipeline refuses</td></tr>
 * </table>
 *
 * <p>The fifth recorded defect — both sides of RC-1 reading one {@code cbs_billed_interest} row —
 * needs no awkward data at all. It is observable on the shared fixture's own ordinary contract, which
 * is exactly why it is the most serious of the five: nothing about the data has to be unusual.
 */
final class LatentDefectFixture {

    /** Same facility as the shared fixture's contract, on a second accounting book (FR-109). */
    static final String OTHER_BOOK_ID = "dddddddd-0001-4000-8000-000000000001";
    static final String OTHER_BOOK_VERSION = "dddddddd-0002-4000-8000-000000000002";
    static final String OTHER_BOOK = "IGAAP";

    /** MONTHLY schedule whose stored solve claims an ACTUAL_DATE convention. */
    static final String ACTUAL_DATE_ID = "dddddddd-0011-4000-8000-000000000011";
    static final String ACTUAL_DATE_VERSION = "dddddddd-0012-4000-8000-000000000012";
    static final String ACTUAL_DATE_SOLVE = "dddddddd-0013-4000-8000-000000000013";
    static final String ACTUAL_DATE_SCHEDULE = "dddddddd-0014-4000-8000-000000000014";

    /** A flow filed under period 202704 and dated in May — legal, since nothing ties the two. */
    static final String MISFILED_FLOW_ID = "dddddddd-0021-4000-8000-000000000021";
    static final String MISFILED_VERSION = "dddddddd-0022-4000-8000-000000000022";
    static final String MISFILED_SOLVE = "dddddddd-0023-4000-8000-000000000023";
    static final String MISFILED_SCHEDULE = "dddddddd-0024-4000-8000-000000000024";

    /**
     * A flow dated exactly on {@code period_start_date} and filed under that period.
     *
     * <p><b>The case the misfiling guard must NOT refuse</b>, and it is here because a mutation
     * proved nothing tested it: making the guard's date comparison {@code <=} instead of
     * {@code <} left the whole live suite green, so the strictness that protects this shape was an
     * unverified claim.
     *
     * <p>The read window is half-open, {@code (start, end]}, so a flow dated on the start date is
     * not returned for this period. Whether that makes it <em>misfiled</em> depends on whether
     * adjacent periods share a boundary date, and this repository's two fixtures disagree — see
     * {@code FlowVectorReader.SELECT_MISFILED_LINES}. Under the live fixture's calendar (202704
     * runs 2027-04-01 to 2027-04-30) there is no adjacent period that would claim it, so filing it
     * here is the only sensible thing a feed could do, and refusing it would quarantine every
     * monthly loan due on the first of the month.
     */
    static final String BOUNDARY_FLOW_ID = "dddddddd-0041-4000-8000-000000000041";
    static final String BOUNDARY_VERSION = "dddddddd-0042-4000-8000-000000000042";
    static final String BOUNDARY_SOLVE = "dddddddd-0043-4000-8000-000000000043";
    static final String BOUNDARY_SCHEDULE = "dddddddd-0044-4000-8000-000000000044";

    /** WEEKLY schedule with four instalments inside one accounting month. */
    static final String WEEKLY_ID = "dddddddd-0031-4000-8000-000000000031";
    static final String WEEKLY_VERSION = "dddddddd-0032-4000-8000-000000000032";
    static final String WEEKLY_SOLVE = "dddddddd-0033-4000-8000-000000000033";
    static final String WEEKLY_SCHEDULE = "dddddddd-0034-4000-8000-000000000034";

    private static boolean seeded;

    private LatentDefectFixture() {
    }

    static synchronized void seed(DataSource dataSource) {
        if (seeded) {
            return;
        }
        List<String> all = new ArrayList<>(List.of(Fixtures.statements()));
        all.addAll(extras());
        try (Connection connection = dataSource.getConnection()) {
            // One transaction, for the reason Fixtures.seed gives: under autocommit a statement
            // failing half way leaves the earlier inserts committed, and the next call fails on a
            // duplicate key rather than on the statement that was actually wrong.
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                for (String sql : all) {
                    s.execute(sql);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
            seeded = true;
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the latent-defect fixture", e);
        }
    }

    private static List<String> extras() {
        List<String> sql = new ArrayList<>();

        // ---- the second book. Same source_system_ref as the shared contract, which is what makes
        // it the SAME FACILITY under a different accounting basis -- V1's contract_source_ref_uq is
        // on (source_system_ref, entity_id, book_id) precisely so that this is legal.
        sql.add(contract(OTHER_BOOK_ID, "CBS-CONTRACT-0001", OTHER_BOOK));
        sql.add(version(OTHER_BOOK_VERSION, OTHER_BOOK_ID, "MONTHLY"));
        sql.add(anchor(OTHER_BOOK_VERSION, "2026-05-01", 24));

        // ---- MONTHLY schedule, solve stored as ACTUAL_DATE. TimeConvention.ActualDate reports one
        // period a year by construction, so a rate wrapped at the schedule's 12 and rolled under
        // this convention is the mismatched pair AmortisationEngine.roll refuses outright.
        sql.add(contract(ACTUAL_DATE_ID, "CBS-CONTRACT-ACTUAL", Fixtures.BOOK_ID));
        sql.add(version(ACTUAL_DATE_VERSION, ACTUAL_DATE_ID, "MONTHLY"));
        sql.add(anchor(ACTUAL_DATE_VERSION, "2026-05-01", 24));
        sql.add(schedule(ACTUAL_DATE_SCHEDULE, ACTUAL_DATE_VERSION));
        sql.add(line(ACTUAL_DATE_SCHEDULE, ACTUAL_DATE_ID, "2027-04-30", 202704, 12,
            "47073.470000"));
        sql.add(solve(ACTUAL_DATE_SOLVE, ACTUAL_DATE_ID, "ACTUAL_DATE"));
        sql.add(stage(ACTUAL_DATE_ID));
        sql.add(billed(ACTUAL_DATE_ID, "5298.160000"));
        sql.add(balance(ACTUAL_DATE_ID, ACTUAL_DATE_SOLVE));

        // ---- a flow filed under 202704 and dated 2027-05-15. Legal: no constraint ties flow_date
        // to period_id, which is itself worth knowing, since period_id is a PARTITION KEY.
        sql.add(contract(MISFILED_FLOW_ID, "CBS-CONTRACT-MISFILED", Fixtures.BOOK_ID));
        sql.add(version(MISFILED_VERSION, MISFILED_FLOW_ID, "MONTHLY"));
        sql.add(anchor(MISFILED_VERSION, "2026-05-01", 24));
        sql.add(schedule(MISFILED_SCHEDULE, MISFILED_VERSION));
        sql.add(line(MISFILED_SCHEDULE, MISFILED_FLOW_ID, "2027-05-15", 202704, 12,
            "47073.470000"));
        sql.add(solve(MISFILED_SOLVE, MISFILED_FLOW_ID, "PERIODIC_INDEX"));
        sql.add(stage(MISFILED_FLOW_ID));
        sql.add(billed(MISFILED_FLOW_ID, "5298.160000"));
        sql.add(balance(MISFILED_FLOW_ID, MISFILED_SOLVE));

        // ---- a flow dated exactly on period 202704's start date (2027-04-01) and filed under
        // 202704. The read window is (start, end], so it is not returned -- but it is NOT misfiled
        // under this calendar, and the guard must leave it alone. See BOUNDARY_FLOW_ID.
        sql.add(contract(BOUNDARY_FLOW_ID, "CBS-CONTRACT-BOUNDARY", Fixtures.BOOK_ID));
        sql.add(version(BOUNDARY_VERSION, BOUNDARY_FLOW_ID, "MONTHLY"));
        sql.add(anchor(BOUNDARY_VERSION, "2026-05-01", 24));
        sql.add(schedule(BOUNDARY_SCHEDULE, BOUNDARY_VERSION));
        sql.add(line(BOUNDARY_SCHEDULE, BOUNDARY_FLOW_ID, "2027-04-01", 202704, 12,
            "47073.470000"));
        sql.add(solve(BOUNDARY_SOLVE, BOUNDARY_FLOW_ID, "PERIODIC_INDEX"));
        sql.add(stage(BOUNDARY_FLOW_ID));
        sql.add(billed(BOUNDARY_FLOW_ID, "5298.160000"));
        sql.add(balance(BOUNDARY_FLOW_ID, BOUNDARY_SOLVE));

        // ---- WEEKLY, four instalments inside April 2027. V1 admits WEEKLY and CompoundingBasis
        // goes to explicit trouble to support it, so this is an ordinary retail microfinance shape.
        sql.add(contract(WEEKLY_ID, "CBS-CONTRACT-WEEKLY", Fixtures.BOOK_ID));
        sql.add(version(WEEKLY_VERSION, WEEKLY_ID, "WEEKLY"));
        sql.add(anchor(WEEKLY_VERSION, "2026-05-01", 104));
        sql.add(schedule(WEEKLY_SCHEDULE, WEEKLY_VERSION));
        List<String> weeklyDates =
            List.of("2027-04-07", "2027-04-14", "2027-04-21", "2027-04-28");
        for (int i = 0; i < weeklyDates.size(); i++) {
            sql.add(line(WEEKLY_SCHEDULE, WEEKLY_ID, weeklyDates.get(i), 202704, 49 + i,
                "11768.370000"));
        }
        sql.add(solve(WEEKLY_SOLVE, WEEKLY_ID, "PERIODIC_INDEX"));
        sql.add(stage(WEEKLY_ID));
        sql.add(billed(WEEKLY_ID, "5298.160000"));
        sql.add(balance(WEEKLY_ID, WEEKLY_SOLVE));

        return sql;
    }

    private static String contract(String id, String sourceRef, String bookId) {
        return """
            INSERT INTO contract (contract_id, source_system_ref, entity_id, product_id, currency,
                measurement_category, instrument_class, sppi_outcome, sppi_assessed_on,
                sppi_approver, is_poci, materiality_tier, tier_basis, initial_recognition_date,
                book_id)
            VALUES ('%s', '%s', 'ENT-01', '%s', 'INR', 'AMORTISED_COST', 'LOAN', 'PASS',
                DATE '2026-04-01', 'credit.analyst', false, 1, 'Tier 1', DATE '2026-04-01', '%s')
            """.formatted(id, sourceRef, Fixtures.PRODUCT_ID, bookId);
    }

    private static String version(String versionId, String contractId, String compounding) {
        return """
            INSERT INTO contract_version (contract_version_id, contract_id, version_no, valid_from,
                valid_to, recorded_at, superseded_at, principal, contractual_rate, rate_type,
                day_count_convention, compounding_basis, contractual_maturity_date,
                eir_expected_life_months, ecl_horizon_months, capitalises_interest,
                acpir_51_fallback)
            VALUES ('%s', '%s', 1, DATE '2027-04-01', NULL,
                TIMESTAMPTZ '2027-05-01 10:00:00+00', NULL, '1000000.000000', '0.010000000000',
                'FIXED', 'ACT_365F', '%s', DATE '2028-04-01', 24, 24, false, false)
            """.formatted(versionId, contractId, compounding);
    }

    private static String anchor(String versionId, String firstDue, int termPeriods) {
        return """
            INSERT INTO contract_version_schedule_anchor (contract_version_id, disbursement_date,
                first_due_date, term_periods)
            VALUES ('%s', DATE '2026-04-01', DATE '%s', %d)
            """.formatted(versionId, firstDue, termPeriods);
    }

    private static String schedule(String scheduleId, String versionId) {
        return """
            INSERT INTO cashflow_schedule (schedule_id, contract_version_id, kind, source,
                residue_policy)
            VALUES ('%s', '%s', 'EXPECTED', 'DERIVED', 'FINAL_PERIOD_PLUG')
            """.formatted(scheduleId, versionId);
    }

    /**
     * One scheduled flow.
     *
     * <p>{@code sequenceNo} is a parameter and not a constant because V1 carries
     * {@code UNIQUE (schedule_id, sequence_no, period_id)} on every {@code cashflow_line} partition —
     * so a weekly schedule's four instalments in one accounting month need four distinct ordinals.
     * The first attempt used 1 for all of them and the insert failed on the FY2028 partition's copy
     * of that constraint, which is the schema doing its job.
     */
    private static String line(String scheduleId, String contractId, String flowDate, int periodId,
        int sequenceNo, String amount) {

        return """
            INSERT INTO cashflow_line (line_id, schedule_id, contract_id, period_id, sequence_no,
                flow_date, amount, kind, is_contingent)
            VALUES (gen_random_uuid(), '%s', '%s', %d, %d, DATE '%s', '%s', 'COMBINED_EMI', false)
            """.formatted(scheduleId, contractId, periodId, sequenceNo, flowDate, amount);
    }

    private static String solve(String computationId, String contractId, String convention) {
        return """
            INSERT INTO eir_computation (computation_id, contract_id, pool_id, period_id,
                computed_as_of, recorded_at, trigger, rate_periodic, rate_effective_annual,
                rate_nominal_annual, rate_kind, convention, convention_precondition_result,
                opening_carrying_amount, solver_method, iterations, residual_at_stored_rate,
                status, flow_vector_ref, policy_version_id, rule_set_version_id, superseded_by)
            VALUES ('%s', '%s', NULL, 202703, DATE '2026-04-01',
                TIMESTAMPTZ '2027-05-01 10:00:00+00', 'INITIAL_RECOGNITION', '0.010421491800',
                '0.132480940855', '0.125057901600', 'EIR', '%s', 'SATISFIED', '985000.000000',
                'NEWTON', 4, '0.000001', 'SOLVED', 'FV-%s', '%s', '%s', NULL)
            """.formatted(computationId, contractId, convention, computationId,
            Fixtures.ROUTING_APRIL_ID, Fixtures.RULE_SET_ID);
    }

    /**
     * The stage and the ECL allowance for the period.
     *
     * <p>Needed because {@code JdbcContractStateSource.openingState} returns empty when any of its
     * four reads finds nothing — terms, the prior closing, the stage, the billed interest — and
     * honours that literally rather than substituting a default. The first version of this fixture
     * seeded only terms and the balance, and every one of these contracts came back empty, which is
     * the port keeping its promise and not a defect.
     */
    private static String stage(String contractId) {
        return """
            INSERT INTO stage_assignment (stage_assignment_id, contract_id, period_id, stage,
                allowance, ecl_engine_version, ecl_model_run_ref, received_at)
            VALUES (gen_random_uuid(), '%s', 202704, 1, '1250.000000', 'ECL-2027.04.1',
                'ECL-RUN-88231', TIMESTAMPTZ '2027-05-01 10:00:00+00')
            """.formatted(contractId);
    }

    /** What the core banking system says it billed for the period (ADR-0004's book of record). */
    private static String billed(String contractId, String amount) {
        return """
            INSERT INTO cbs_billed_interest (feed_line_id, contract_id, period_id, billed_interest,
                feed_reference, recorded_at, superseded_at)
            VALUES (gen_random_uuid(), '%s', 202704, '%s', 'CBS-FEED-APR', 
                TIMESTAMPTZ '2027-05-01 10:00:00+00', NULL)
            """.formatted(contractId, amount);
    }

    /** The prior period's closing position, which is this period's opening one. */
    private static String balance(String contractId, String computationId) {
        return """
            INSERT INTO period_balance (balance_id, contract_id, product_id, period_id, book_id,
                run_id, eir_computation_id, opening_gca, closing_gca, opening_contractual,
                closing_contractual, eir_interest, contractual_interest, cash_received,
                catch_up_amount, stage, allowance, recognised_interest_income, suspense_movement,
                basis_adjustment_amortised)
            VALUES (gen_random_uuid(), '%s', '%s', 202703, '%s', '%s', '%s',
                '569545.280000', '528407.320000', '571000.000000', '530000.000000',
                '5935.510000', '5700.000000', '47073.470000', '0.000000', 1, '0.000000',
                '5935.510000', '0.000000', '0.000000')
            """.formatted(contractId, Fixtures.PRODUCT_ID, Fixtures.BOOK_ID, Fixtures.RUN_ID,
            computationId);
    }
}
