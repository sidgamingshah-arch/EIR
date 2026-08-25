package com.crisil.eir.persistence;

import java.util.List;

/**
 * The names and numeric scales of the core schema, as constants.
 *
 * <p>This module carries no ORM and no annotations — the root enforcer bans
 * jakarta.persistence, Hibernate, Spring and Jackson across every module, and
 * this module deliberately declares no compile dependency at all. So the
 * schema's vocabulary is stated once here, in plain Java, rather than being
 * spelled out in string literals wherever a caller happens to need it. A
 * misspelled table name then fails to compile instead of failing at run time
 * against a database that is not present in the build.
 *
 * <p>The two numeric scales are the substantive content. They restate the
 * physical policy of {@code docs/04-data-model.md} section 4:
 *
 * <ul>
 *   <li>Money at {@code NUMERIC(24,6)}. Six decimals holds working values above
 *       presentation scale. A money column declared with two decimals parses
 *       without complaint and silently truncates every working value that passes
 *       through it, which cannot be recovered once a period has closed.
 *   <li>Rates at {@code NUMERIC(20,12)}. Twelve decimal places is the storage
 *       policy of the calculation specification exactly, and it is why the rate
 *       that was persisted is the rate every downstream period can use.
 * </ul>
 *
 * <p>These are the declared scales of the columns, not a rounding policy. The
 * engine's rounding policy lives in {@code Precision} in eir-domain and is the
 * only place a value is rounded.
 *
 * <p>Deliberately not represented here: physical types the schema may never use.
 * Binary floating point is banned by ADR-0002, and PostgreSQL's fixed-scale,
 * locale-dependent currency type with it. A constant naming a forbidden type
 * would only invite something to read it and decide; the ban lives in the
 * migration's own comments and in the test that reads the migration text.
 */
public final class CoreSchema {

    /** Classpath location of the core-entity migration. */
    public static final String CORE_MIGRATION_RESOURCE = "/db/migration/V1__core_entities.sql";

    /** Total digits in a money column. */
    public static final int MONEY_PRECISION = 24;

    /** Decimal places in a money column — the working scale, above presentation scale. */
    public static final int MONEY_SCALE = 6;

    /** Total digits in a rate column. */
    public static final int RATE_PRECISION = 20;

    /** Decimal places in a rate column — the storage policy of the calculation spec. */
    public static final int RATE_SCALE = 12;

    /** The stable contract identity. */
    public static final String CONTRACT = "contract";

    /** The product taxonomy, shared with the provisioning engine. */
    public static final String PRODUCT = "product";

    /** Bitemporal contract terms. */
    public static final String CONTRACT_VERSION = "contract_version";

    /** A contractual or expected schedule header. */
    public static final String CASHFLOW_SCHEDULE = "cashflow_schedule";

    /** The flow vector as rows; range-partitioned by {@link #PERIOD_ID}. */
    public static final String CASHFLOW_LINE = "cashflow_line";

    /** A fee or cost as posted, with its resolved classification. */
    public static final String FEE_POSTING = "fee_posting";

    /** The versioned, maker-checked fee classifier. */
    public static final String FEE_RULE_SET = "fee_rule_set";

    /** One row per solve — the audit anchor. */
    public static final String EIR_COMPUTATION = "eir_computation";

    /** Business time: from when the fact was true in the world. */
    public static final String VALID_FROM = "valid_from";

    /** Business time: until when the fact was true; null means still true. */
    public static final String VALID_TO = "valid_to";

    /** System time: when the engine learned the fact. */
    public static final String RECORDED_AT = "recorded_at";

    /** System time: when the engine stopped believing the fact. */
    public static final String SUPERSEDED_AT = "superseded_at";

    /** Accounting time: the reporting period a row belongs to, as {@code YYYYMM}. */
    public static final String PERIOD_ID = "period_id";

    /** The contract a row belongs to. */
    public static final String CONTRACT_ID = "contract_id";

    /**
     * The eight tables of {@code docs/04-data-model.md} sections 2.1 to 2.6, in
     * dependency order — a table appears after everything it references, so the
     * list doubles as a safe creation and truncation order.
     */
    public static final List<String> CORE_TABLES = List.of(
        PRODUCT,
        CONTRACT,
        CONTRACT_VERSION,
        CASHFLOW_SCHEDULE,
        CASHFLOW_LINE,
        FEE_RULE_SET,
        FEE_POSTING,
        EIR_COMPUTATION);

    /**
     * Tables carrying the full bitemporal column set. Only where section 2 of the
     * data model says so: business and system time are applied to the entity whose
     * facts change, and not blanket-applied to entities that have no history — an
     * identity row and a taxonomy row have nothing to supersede.
     */
    public static final List<String> BITEMPORAL_TABLES = List.of(CONTRACT_VERSION);

    /**
     * Tables range-partitioned by {@link #PERIOD_ID}. Partition pruning is what
     * keeps a close-period query tractable at portfolio scale, and it is why a
     * closed period's partitions can be frozen as a unit.
     */
    public static final List<String> PERIOD_PARTITIONED_TABLES = List.of(CASHFLOW_LINE);

    /**
     * The trace-path index columns, in order: the query an auditor's walk and every
     * reconciliation actually run.
     */
    public static final List<String> TRACE_INDEX_COLUMNS = List.of(CONTRACT_ID, PERIOD_ID);

    private CoreSchema() {
    }
}
