package com.crisil.eir.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import javax.sql.DataSource;

/**
 * One book, seeded so that the same rows answer two different questions at two different instants.
 *
 * <h2>The shape of the fixture, and why every timestamp is spelled out</h2>
 *
 * <p>The contract carries <b>two</b> {@code contract_version} rows with the <em>same</em> business
 * validity and different decision times — which is 04 § 5's backdated amendment exactly: "a row with
 * an earlier {@code valid_from} and a current {@code recorded_at}". Version 1 was recorded on
 * {@link #RECORDED_ORIGINAL} and superseded on {@link #RECORDED_CORRECTION}; version 2 was recorded
 * then and stands. Both are valid from 1 April 2027, so business time cannot separate them and only
 * decision time can.
 *
 * <p>Four other legs carry the same treatment, so the property is not demonstrated on one table and
 * assumed on the rest: the CBS billed-interest extract (restated), the EIR solve (superseded through
 * {@code superseded_by}), a second contract onboarded after the original instant, and a policy
 * version approved after it.
 *
 * <p>Every figure below is written out by hand. {@link #ORIGINAL_PRINCIPAL} is reference case 1's
 * ₹10,00,000.00; {@link #ORIGINAL_PERIODIC_RATE} is its solved periodic EIR at the twelve decimal
 * places {@code NUMERIC(20,12)} exists to hold; {@link #PRIOR_CLOSING_GCA} is its month 13 opening
 * gross carrying amount, 5,28,407.32, which is the closing figure of the period before. None is
 * produced by running the code under test.
 */
final class Fixtures {

    // ---- identities ------------------------------------------------------------------------

    static final String PRODUCT_ID = "11111111-1111-4111-8111-111111111111";
    /** The contract whose terms are corrected between the two instants. */
    static final String CONTRACT_ID = "22222222-2222-4222-8222-222222222222";
    /** Onboarded after the original instant; invisible to a read as at it. */
    static final String LATE_CONTRACT_ID = "33333333-3333-4333-8333-333333333333";
    /** Declared FVTPL, so FR-103 keeps it out of the EIR population entirely. */
    static final String FVTPL_CONTRACT_ID = "44444444-4444-4444-8444-444444444444";

    static final String VERSION_1_ID = "aaaaaaaa-0001-4000-8000-000000000001";
    static final String VERSION_2_ID = "aaaaaaaa-0002-4000-8000-000000000002";
    static final String LATE_VERSION_ID = "aaaaaaaa-0003-4000-8000-000000000003";
    static final String FVTPL_VERSION_ID = "aaaaaaaa-0004-4000-8000-000000000004";

    static final String SCHEDULE_1_ID = "bbbbbbbb-0001-4000-8000-000000000001";
    static final String SCHEDULE_2_ID = "bbbbbbbb-0002-4000-8000-000000000002";

    static final String RULE_SET_ID = "FEE-RULES-2027.04.1";
    /**
     * The routing table the April close resolved against; today it is SUPERSEDED.
     *
     * <p><b>A limitation worth naming.</b> {@code policy_version.status} has no temporal axis: the
     * column holds today's status, not the status as at a boundary. As at 2 May this version was
     * EFFECTIVE and as at 2 June it is SUPERSEDED, and only the second is representable. It does not
     * change what resolves — {@code PolicyVersionStatus.isOperative()} covers EFFECTIVE and
     * SUPERSEDED together, precisely so "a closed period still resolves against a superseded
     * version (invariant DT-1)" — but a report of the status a run saw cannot be reconstructed.
     */
    static final String ROUTING_APRIL_ID = "ROUTING-2027.04.1";
    /** Approved 1 June, so invisible to a boundary of 2 May. */
    static final String ROUTING_MAY_ID = "ROUTING-2027.05.1";

    static final String RUN_ID = "cccccccc-0001-4000-8000-000000000001";
    static final String GCA_ACCOUNT = "1301-LOANS-GCA";
    static final String BOOK_ID = "MAIN";

    // ---- the two instants that separate the two answers ------------------------------------

    /** The prior period's run completed here, before either version was recorded. */
    static final Instant RUN_COMPLETED = Instant.parse("2027-04-01T00:00:00Z");
    /** What the engine knew when the April 2027 close was originally computed. */
    static final Instant RECORDED_ORIGINAL = Instant.parse("2027-05-01T10:00:00Z");
    /** When the correction arrived: the amendment, the restated extract, the superseding solve. */
    static final Instant RECORDED_CORRECTION = Instant.parse("2027-06-01T10:00:00Z");

    /** A boundary instant strictly between the two — sees the original set. */
    static final Instant AS_AT_ORIGINAL = Instant.parse("2027-05-02T00:00:00Z");
    /** A boundary instant after the correction — sees the corrected set. */
    static final Instant AS_AT_CORRECTED = Instant.parse("2027-06-02T00:00:00Z");

    static final LocalDate PERIOD_END = LocalDate.of(2027, 4, 30);
    static final int PERIOD_ID = 202704;
    static final int PRIOR_PERIOD_ID = 202703;

    // ---- figures, by hand ------------------------------------------------------------------

    /** Reference case 1: ₹10,00,000.00, at the working scale the column stores. */
    static final String ORIGINAL_PRINCIPAL = "1000000.000000";
    /** The corrected principal, recorded later against the same business validity. */
    static final String CORRECTED_PRINCIPAL = "900000.000000";
    /** Reference case 1's periodic EIR, twelve decimal places. */
    static final String ORIGINAL_PERIODIC_RATE = "0.010421491800";
    /** The superseding solve's rate; different in the first decimal place, not the twelfth. */
    static final String CORRECTED_PERIODIC_RATE = "0.011500000000";
    /** Reference case 1's month 13 opening GCA — hence the prior period's closing GCA. */
    static final String PRIOR_CLOSING_GCA = "528407.320000";
    static final String PRIOR_CLOSING_CONTRACTUAL = "530000.000000";
    /** What the CBS originally said it billed for April 2027. */
    static final String ORIGINAL_BILLED = "5284.070000";
    /** What the restated extract says. */
    static final String CORRECTED_BILLED = "5000.000000";

    private static boolean seeded;

    private Fixtures() {
    }

    /** Seeds the live database once per JVM. */
    static synchronized void seed(DataSource dataSource) {
        if (seeded) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            // One transaction for the whole seed. Under autocommit a statement failing half way
            // through leaves the earlier inserts committed, and the next test class to call seed()
            // then fails on a duplicate key instead of on the statement that was actually wrong —
            // which is a slow way to find a typo.
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                for (String sql : statements()) {
                    s.execute(sql);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
            seeded = true;
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the live fixture", e);
        }
    }

    static String[] statements() {
        return new String[] {
            // ---- taxonomy and identities ---------------------------------------------------
            """
            INSERT INTO product (product_id, product_name, acpir_floor_category, default_tier,
                expected_life_method, b544_election, rollover_treatment, projection_strategy,
                day_count_default, compounding_default)
            VALUES ('%s', 'Term loan, wholesale', 'SECURED_ADVANCE', 1, 'CONTRACTUAL', false,
                'NEW_INSTRUMENT', 'ANNUITY', 'ACT_365F', 'MONTHLY')
            """.formatted(PRODUCT_ID),

            contract(CONTRACT_ID, "CBS-0001", "AMORTISED_COST", "'PASS'",
                "DATE '2026-03-31'", "'checker.one'"),
            contract(LATE_CONTRACT_ID, "CBS-0002", "AMORTISED_COST", "'PASS'",
                "DATE '2027-05-15'", "'checker.one'"),
            // FVTPL carries no SPPI triple, which V1 permits only because the category is FVTPL.
            contract(FVTPL_CONTRACT_ID, "CBS-0003", "FVTPL", "NULL", "NULL", "NULL"),

            // ---- the two versions of one contract: same business time, different decision time
            //
            // Version 1 is what the April close read. Version 2 is the backdated correction: same
            // valid_from, recorded a month later, with version 1 superseded at that instant. V1's
            // contract_version_no_overlap_ck excludes only unsuperseded rows, which is what makes
            // "correcting history is adding a row rather than rewriting one" expressible at all.
            contractVersion(VERSION_1_ID, CONTRACT_ID, 1, ORIGINAL_PRINCIPAL, "0.010000000000",
                RECORDED_ORIGINAL, RECORDED_CORRECTION),
            contractVersion(VERSION_2_ID, CONTRACT_ID, 2, CORRECTED_PRINCIPAL, "0.011000000000",
                RECORDED_CORRECTION, null),
            contractVersion(LATE_VERSION_ID, LATE_CONTRACT_ID, 1, "500000.000000",
                "0.009000000000", RECORDED_CORRECTION, null),
            contractVersion(FVTPL_VERSION_ID, FVTPL_CONTRACT_ID, 1, "250000.000000",
                "0.012000000000", RECORDED_ORIGINAL, null),

            anchor(VERSION_1_ID),
            anchor(VERSION_2_ID),
            anchor(LATE_VERSION_ID),
            anchor(FVTPL_VERSION_ID),

            // ---- schedules and one flow in the period --------------------------------------
            schedule(SCHEDULE_1_ID, VERSION_1_ID),
            schedule(SCHEDULE_2_ID, VERSION_2_ID),
            line(SCHEDULE_1_ID, CONTRACT_ID, "dddddddd-0001-4000-8000-000000000001",
                "2027-04-30", "47073.470000"),
            line(SCHEDULE_2_ID, CONTRACT_ID, "dddddddd-0002-4000-8000-000000000002",
                "2027-04-30", "47073.470000"),

            // ---- accounting calendar and the prior period's completed run -------------------
            """
            INSERT INTO accounting_period (period_id, fiscal_year_label, period_start_date,
                period_end_date, status)
            VALUES (202703, 'FY2026-27', DATE '2027-03-01', DATE '2027-03-31', 'OPEN'),
                   (202704, 'FY2027-28', DATE '2027-04-01', DATE '2027-04-30', 'OPEN')
            """,
            """
            INSERT INTO amortisation_run (run_id, period_id, book_id, status, started_at,
                completed_at, contracts_processed, exceptions_raised, is_replay)
            VALUES ('%s', %d, '%s', 'COMPLETED', TIMESTAMPTZ '2027-03-31 18:00:00+00',
                TIMESTAMPTZ '%s', 1, 0, false)
            """.formatted(RUN_ID, PRIOR_PERIOD_ID, BOOK_ID, timestamp(RUN_COMPLETED)),

            // ---- the prior period's closing balances: this run's opening position ------------
            //
            // fee_amortised and unamortised_fee are GENERATED ALWAYS in V2 and are deliberately not
            // listed: invariant INV-4 is a derivation the database performs, and an INSERT naming
            // them would be rejected.
            """
            INSERT INTO period_balance (balance_id, contract_id, period_id, book_id, run_id,
                product_id, opening_gca, closing_gca, opening_contractual, closing_contractual,
                eir_interest, contractual_interest, cash_received, catch_up_amount, stage,
                allowance, recognised_interest_income, suspense_movement, rate_periodic_used)
            VALUES ('eeeeeeee-0001-4000-8000-000000000001', '%s', %d, '%s', '%s', '%s',
                '570000.000000', '%s', '572000.000000', '%s',
                '5504.070000', '5284.070000', '47073.470000', '0.000000', 1,
                '0.000000', '5504.070000', '0.000000', '%s')
            """.formatted(CONTRACT_ID, PRIOR_PERIOD_ID, BOOK_ID, RUN_ID, PRODUCT_ID,
                PRIOR_CLOSING_GCA, PRIOR_CLOSING_CONTRACTUAL, ORIGINAL_PERIODIC_RATE),

            // ---- the ECL engine's verdict for the period -------------------------------------
            """
            INSERT INTO stage_assignment (stage_assignment_id, contract_id, period_id, stage,
                allowance, ecl_engine_version, ecl_model_run_ref, received_at)
            VALUES ('ffffffff-0001-4000-8000-000000000001', '%s', %d, 1, '1250.000000',
                'ECL-2027.04.1', 'ECL-RUN-88231', TIMESTAMPTZ '%s')
            """.formatted(CONTRACT_ID, PERIOD_ID, timestamp(RECORDED_ORIGINAL)),

            // ---- the fee rule set, and a posting classified under it --------------------------
            """
            INSERT INTO fee_rule_set (rule_set_version_id, fee_code, product_id, entity_id,
                effective_from, classification, maker, checker, approved_at, impact_preview_ref)
            VALUES ('%s', 'PROC_FEE', NULL, NULL, DATE '2027-04-01', 'INTEGRAL',
                'policy.maker', 'policy.checker', TIMESTAMPTZ '2027-03-20 09:00:00+00',
                'IMPACT-2027-04-A')
            """.formatted(RULE_SET_ID),
            """
            INSERT INTO fee_posting (posting_id, contract_id, fee_code, amount, posted_on, payer,
                cost_function, classification, rule_set_version_id)
            VALUES ('a1a1a1a1-0001-4000-8000-000000000001', '%s', 'PROC_FEE', '15000.000000',
                DATE '2026-04-01', 'BORROWER', 'PROCESSING', 'INTEGRAL', '%s')
            """.formatted(CONTRACT_ID, RULE_SET_ID),

            // ---- policy: one resolvable at both instants, one only at the later ---------------
            //
            // The May version is inserted first because superseded_by is a self-referencing foreign
            // key and the April version points forward at it. The April version carries
            // effective_to = 1 May because V2's ex_policy_version_no_overlap refuses two
            // open-ended versions of one kind — "the engine then recognises income under whichever
            // the planner returned first".
            //
            // Both statuses are OPERATIVE in eir-policy's sense (isOperative covers EFFECTIVE and
            // SUPERSEDED), which is what makes the April version still resolvable for a replay.
            // APPROVED would not be: it is signed off and not yet in force, so a version left in
            // that state would be invisible to inForceOn at BOTH boundaries and this fixture would
            // be testing the status vocabulary rather than the decision-time bound.
            policyVersion(ROUTING_MAY_ID, "ROUTING_TABLE", "2027.05.1", "2027-05-01", null,
                "EFFECTIVE", RECORDED_CORRECTION, null),
            policyVersion(ROUTING_APRIL_ID, "ROUTING_TABLE", "2027.04.1", "2027-04-01",
                "2027-05-01", "SUPERSEDED", RECORDED_ORIGINAL, ROUTING_MAY_ID),

            // ---- two solves, chained through superseded_by ------------------------------------
            //
            // The superseding row is inserted first, because superseded_by is a self-referencing
            // foreign key and points forward.
            solve("b1b1b1b1-0002-4000-8000-000000000002", CORRECTED_PERIODIC_RATE,
                RECORDED_CORRECTION, null),
            solve("b1b1b1b1-0001-4000-8000-000000000001", ORIGINAL_PERIODIC_RATE,
                RECORDED_ORIGINAL, "b1b1b1b1-0002-4000-8000-000000000002"),

            // ---- inbound feeds (V3), all with the system-time pair ----------------------------
            //
            // The CBS restatement is a new row with the old one's superseded_at closed, which is
            // what lets a replay read the extract the original run consumed.
            billed("c1c1c1c1-0001-4000-8000-000000000001", ORIGINAL_BILLED, "CBS-EOD-202704-V1",
                RECORDED_ORIGINAL, RECORDED_CORRECTION),
            billed("c1c1c1c1-0002-4000-8000-000000000002", CORRECTED_BILLED, "CBS-EOD-202704-V2",
                RECORDED_CORRECTION, null),
            """
            INSERT INTO gl_control_account_balance (gl_observation_id, account_code, book_id,
                period_id, balance, currency, source_ref, recorded_at)
            VALUES ('d1d1d1d1-0001-4000-8000-000000000001', '%s', '%s', %d, '%s', 'INR',
                'TB-202704-POSTED', TIMESTAMPTZ '%s')
            """.formatted(GCA_ACCOUNT, BOOK_ID, PERIOD_ID, PRIOR_CLOSING_GCA,
                timestamp(RECORDED_ORIGINAL)),
            """
            INSERT INTO cash_book_application (application_id, contract_id, period_id, book_id,
                applied_to_principal, applied_to_interest, source_ref, recorded_at)
            VALUES ('e1e1e1e1-0001-4000-8000-000000000001', '%s', %d, '%s',
                '41789.400000', '5284.070000', 'CASHBOOK-202704', TIMESTAMPTZ '%s')
            """.formatted(CONTRACT_ID, PERIOD_ID, BOOK_ID, timestamp(RECORDED_ORIGINAL)),
            """
            INSERT INTO suspense_movement (suspense_movement_id, contract_id, period_id, book_id,
                recovered, written_off, source_ref, recorded_at)
            VALUES ('f1f1f1f1-0001-4000-8000-000000000001', '%s', %d, '%s',
                '0.000000', '0.000000', 'COLLECTIONS-202704', TIMESTAMPTZ '%s')
            """.formatted(CONTRACT_ID, PERIOD_ID, BOOK_ID, timestamp(RECORDED_ORIGINAL)),
            """
            INSERT INTO contract_onboarding_attribute (attribute_id, contract_id,
                counterparty_segment, tier_features, exposure_at_origination, recorded_at)
            VALUES ('a2a2a2a2-0001-4000-8000-000000000001', '%s', 'WHOLESALE',
                ARRAY['PROJECT_FINANCE']::TEXT[], '%s', TIMESTAMPTZ '%s')
            """.formatted(CONTRACT_ID, ORIGINAL_PRINCIPAL, timestamp(RECORDED_ORIGINAL)),

            // ---- one lifecycle event inside the period ----------------------------------------
            """
            INSERT INTO lifecycle_event (event_id, contract_id, event_date, event_type, driver,
                routed_mechanism, prepayment_variant, ten_percent_test_ratio,
                ten_percent_test_result, substantiality_conclusion, decided_by, decided_at,
                sequence_within_date, recorded_at)
            VALUES ('b2b2b2b2-0001-4000-8000-000000000001', '%s', DATE '2027-04-15',
                'RESCHEDULE_REQUEST', 'NEGOTIATED', 'MODIFICATION_TEST', NULL,
                '0.041200000000', 'BELOW_THRESHOLD', 'NOT_SUBSTANTIAL', 'credit.officer',
                TIMESTAMPTZ '2027-04-16 11:00:00+00', 0, TIMESTAMPTZ '%s')
            """.formatted(CONTRACT_ID, timestamp(RECORDED_ORIGINAL)),
        };
    }

    private static String contract(String id, String sourceRef, String category, String sppiOutcome,
        String sppiDate, String sppiApprover) {

        return """
            INSERT INTO contract (contract_id, source_system_ref, entity_id, product_id, currency,
                measurement_category, instrument_class, sppi_outcome, sppi_assessed_on,
                sppi_approver, is_poci, materiality_tier, tier_basis, initial_recognition_date,
                book_id)
            VALUES ('%s', '%s', 'ENT-01', '%s', 'INR', '%s', 'LOAN', %s, %s, %s, false, 1,
                'Tier 1: wholesale exposure above the Board threshold', DATE '2026-04-01', '%s')
            """.formatted(id, sourceRef, PRODUCT_ID, category, sppiOutcome, sppiDate, sppiApprover,
            BOOK_ID);
    }

    private static String contractVersion(String versionId, String contractId, int versionNo,
        String principal, String rate, Instant recordedAt, Instant supersededAt) {

        return """
            INSERT INTO contract_version (contract_version_id, contract_id, version_no, valid_from,
                valid_to, recorded_at, superseded_at, principal, contractual_rate, rate_type,
                day_count_convention, compounding_basis, contractual_maturity_date,
                eir_expected_life_months, ecl_horizon_months, capitalises_interest,
                acpir_51_fallback)
            VALUES ('%s', '%s', %d, DATE '2027-04-01', NULL, TIMESTAMPTZ '%s', %s, '%s', '%s',
                'FIXED', 'ACT_365F', 'MONTHLY', DATE '2028-04-01', 24, 24, false, false)
            """.formatted(versionId, contractId, versionNo, timestamp(recordedAt),
            supersededAt == null ? "NULL" : "TIMESTAMPTZ '" + timestamp(supersededAt) + "'",
            principal, rate);
    }

    /** The V3 projector anchors: t = 0, the first instalment, and the term. */
    private static String anchor(String versionId) {
        return """
            INSERT INTO contract_version_schedule_anchor (contract_version_id, disbursement_date,
                first_due_date, term_periods)
            VALUES ('%s', DATE '2026-04-01', DATE '2026-05-01', 24)
            """.formatted(versionId);
    }

    private static String schedule(String scheduleId, String versionId) {
        return """
            INSERT INTO cashflow_schedule (schedule_id, contract_version_id, kind, source,
                residue_policy)
            VALUES ('%s', '%s', 'EXPECTED', 'DERIVED', 'FINAL_PERIOD_PLUG')
            """.formatted(scheduleId, versionId);
    }

    private static String line(String scheduleId, String contractId, String lineId, String flowDate,
        String amount) {

        return """
            INSERT INTO cashflow_line (line_id, schedule_id, contract_id, period_id, sequence_no,
                flow_date, amount, kind, is_contingent)
            VALUES ('%s', '%s', '%s', %d, 12, DATE '%s', '%s', 'COMBINED_EMI', false)
            """.formatted(lineId, scheduleId, contractId, PERIOD_ID, flowDate, amount);
    }

    private static String solve(String computationId, String periodicRate, Instant recordedAt,
        String supersededBy) {

        return """
            INSERT INTO eir_computation (computation_id, contract_id, pool_id, period_id,
                computed_as_of, recorded_at, trigger, rate_periodic, rate_effective_annual,
                rate_nominal_annual, rate_kind, convention, convention_precondition_result,
                opening_carrying_amount, solver_method, iterations, residual_at_stored_rate,
                status, flow_vector_ref, policy_version_id, rule_set_version_id, superseded_by)
            VALUES ('%s', '%s', NULL, %d, DATE '2026-04-01', TIMESTAMPTZ '%s',
                'INITIAL_RECOGNITION', '%s', '0.132600000000', '0.125057901600', 'EIR',
                'PERIODIC_INDEX', 'SATISFIED', '985000.000000', 'NEWTON', 4, '0.000001',
                'SOLVED', 'FV-%s', '%s', '%s', %s)
            """.formatted(computationId, CONTRACT_ID, PRIOR_PERIOD_ID, timestamp(recordedAt),
            periodicRate, computationId, ROUTING_APRIL_ID, RULE_SET_ID,
            supersededBy == null ? "NULL" : "'" + supersededBy + "'");
    }

    private static String policyVersion(String id, String kind, String label, String effectiveFrom,
        String effectiveTo, String status, Instant approvedAt, String supersededBy) {

        return """
            INSERT INTO policy_version (policy_version_id, policy_kind, version_label, description,
                effective_from, effective_to, status, maker, checker, approved_at,
                impact_preview_ref, superseded_by)
            VALUES ('%s', '%s', '%s', 'Driver-to-mechanism routing, %s', DATE '%s', %s, '%s',
                'policy.maker', 'policy.checker', TIMESTAMPTZ '%s', 'IMPACT-%s', %s)
            """.formatted(id, kind, label, label, effectiveFrom,
            effectiveTo == null ? "NULL" : "DATE '" + effectiveTo + "'", status,
            timestamp(approvedAt), label,
            supersededBy == null ? "NULL" : "'" + supersededBy + "'");
    }

    private static String billed(String lineId, String amount, String feedRef, Instant recordedAt,
        Instant supersededAt) {

        return """
            INSERT INTO cbs_billed_interest (feed_line_id, contract_id, period_id, billed_interest,
                feed_reference, recorded_at, superseded_at)
            VALUES ('%s', '%s', %d, '%s', '%s', TIMESTAMPTZ '%s', %s)
            """.formatted(lineId, CONTRACT_ID, PERIOD_ID, amount, feedRef, timestamp(recordedAt),
            supersededAt == null ? "NULL" : "TIMESTAMPTZ '" + timestamp(supersededAt) + "'");
    }

    /** {@code 2027-05-01 10:00:00+00} — the literal form PostgreSQL parses as an absolute instant. */
    private static String timestamp(Instant instant) {
        return instant.toString().replace("T", " ").replace("Z", "+00");
    }
}
