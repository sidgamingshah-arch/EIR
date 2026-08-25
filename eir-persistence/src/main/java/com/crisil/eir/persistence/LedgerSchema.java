package com.crisil.eir.persistence;

import java.util.List;
import java.util.Set;

/**
 * The names and shapes the ledger half of the schema is built from, as constants.
 *
 * <p>There is no ORM in this module and there is not going to be one — the root enforcer bans
 * {@code jakarta.persistence}, Hibernate, Spring and Jackson across every module, so an entity
 * mapping here would require restructuring that ban. What is left is the part an ORM would have
 * given away for free and which is otherwise retyped as a string literal at every call site: the
 * table names, the two numeric shapes, the partition naming convention, and the vocabulary of the
 * exception queue.
 *
 * <p>This class exists to be <em>checked against the DDL</em>, not merely to be read from. The
 * accompanying tests assert that every name here appears in
 * {@code V2__ledger_and_transition.sql} and that the DDL's numeric shapes are the two named here.
 * A constant that has silently diverged from the schema it names is worse than no constant, so
 * the divergence is a build failure rather than a runtime surprise.
 *
 * <p>Framework-free by construction: no annotations, no dependencies, nothing that reads a clock.
 */
public final class LedgerSchema {

    /**
     * Classpath location of the migration this class describes. Numbered in Flyway's
     * {@code V<n>__<description>.sql} convention so that wiring Flyway later is an addition and
     * not a rewrite.
     */
    public static final String MIGRATION_RESOURCE = "/db/migration/V2__ledger_and_transition.sql";

    // -------------------------------------------------------------------------------------
    // The two numeric shapes of 04 § 4. There are exactly two, and a third is a defect.
    // -------------------------------------------------------------------------------------

    /** Precision of every money column: {@code NUMERIC(24,6)}. */
    public static final int MONEY_PRECISION = 24;

    /**
     * Scale of every money column. Six decimals holds working values above presentation scale
     * without binary floating point; a money column that came out {@code NUMERIC(24,2)} parses
     * fine and silently truncates working precision, which is the defect the check exists for.
     */
    public static final int MONEY_SCALE = 6;

    /** Precision of every rate column: {@code NUMERIC(20,12)}. */
    public static final int RATE_PRECISION = 20;

    /** Scale of every rate column — matches the 12dp storage policy of 03 § 1 exactly. */
    public static final int RATE_SCALE = 12;

    // -------------------------------------------------------------------------------------
    // Tables. Lower case, because that is how PostgreSQL folds an unquoted identifier and
    // therefore how they appear in the catalogue.
    // -------------------------------------------------------------------------------------

    /** 04 § 2.12 — the interpretive hierarchy as data. */
    public static final String POLICY_VERSION = "policy_version";

    /** 04 § 2.13 — accounting time; the range partition key of the two ledger tables. */
    public static final String ACCOUNTING_PERIOD = "accounting_period";

    /** 04 § 2.13 — one row per execution of the engine over a period. */
    public static final String AMORTISATION_RUN = "amortisation_run";

    /** 04 § 2.7 — the event stream and the routing decision taken on each event. */
    public static final String LIFECYCLE_EVENT = "lifecycle_event";

    /** 04 § 2.8 — the sub-ledger row. Range-partitioned by {@code period_id}. */
    public static final String PERIOD_BALANCE = "period_balance";

    /** 04 § 4 — the double-entry leg. Range-partitioned by {@code period_id}. */
    public static final String JOURNAL_ENTRY = "journal_entry";

    /** 04 § 2.9 — the ECL engine's verdict, carrying the engine version that produced it. */
    public static final String STAGE_ASSIGNMENT = "stage_assignment";

    /** 04 § 2.9 — interest in suspense as a first-class ledger object (FR-604). */
    public static final String SUSPENSE_ENTRY = "suspense_entry";

    /** 04 § 2.10 — Tier 2 pooled measurement over a closed cohort. */
    public static final String POOL = "pool";

    /** 04 § 2.11 — the hedge designation. */
    public static final String HEDGE_RELATIONSHIP = "hedge_relationship";

    /** 04 § 2.11 — the hedged item's fair-value adjustment and its run-off. */
    public static final String BASIS_ADJUSTMENT = "basis_adjustment";

    /** 04 § 3 — the exception queue. */
    public static final String EXCEPTION = "exception";

    /** 04 § 6 — the ACPIR 19 day-1 fair valuation. */
    public static final String TRANSITION_FAIR_VALUE = "transition_fair_value";

    /** 04 § 6 — legacy migration cohorts, prioritised by survival past 31 March 2030. */
    public static final String LEGACY_COHORT = "legacy_cohort";

    /** 04 § 6 — the documented basis for a deemed rate (FR-909). */
    public static final String DEEMED_EIR_DERIVATION = "deemed_eir_derivation";

    /** 04 § 6 — which rate the ECL was discounted at, tracked against ACPIR 50. */
    public static final String ECL_DISCOUNT_BASIS = "ecl_discount_basis";

    /** Every table this migration creates, in dependency order. */
    public static final List<String> TABLES = List.of(
            POLICY_VERSION,
            ACCOUNTING_PERIOD,
            AMORTISATION_RUN,
            LIFECYCLE_EVENT,
            PERIOD_BALANCE,
            JOURNAL_ENTRY,
            STAGE_ASSIGNMENT,
            SUSPENSE_ENTRY,
            POOL,
            HEDGE_RELATIONSHIP,
            BASIS_ADJUSTMENT,
            EXCEPTION,
            TRANSITION_FAIR_VALUE,
            LEGACY_COHORT,
            DEEMED_EIR_DERIVATION,
            ECL_DISCOUNT_BASIS);

    /**
     * The tables range-partitioned by {@code period_id} (04 § 4).
     *
     * <p>Partition pruning is what keeps close-period queries tractable at roughly 120M
     * {@code PERIOD_BALANCE} rows a year, and one partition per period is also what makes "a
     * closed period's partitions are set read-only" expressible at all.
     */
    public static final Set<String> PARTITIONED_TABLES = Set.of(PERIOD_BALANCE, JOURNAL_ENTRY);

    /**
     * The ten exception-queue categories of 04 § 3, in the order 04 lists them.
     *
     * <p>Duplicated here rather than depending on {@code eir-policy}'s {@code ExceptionCategory}:
     * this module carries no compile dependency at all, and a dependency added for a list of ten
     * strings would be the first crack in that. The tests assert this list against the SQL
     * {@code CHECK} constraint; keeping it aligned with the enum is a review obligation on all
     * three.
     */
    public static final List<String> EXCEPTION_CATEGORIES = List.of(
            "UNMAPPED_FEE_CODE",
            "MISSING_COST_FUNCTION",
            "NO_SOLUTION",
            "MULTIPLE_ROOTS",
            "MISSING_MANDATORY_FIELD",
            "PENAL_CHARGE_REJECTED",
            "IC1_BREACH",
            "STALE_EQUIVALENCE_TEST",
            "DISCONTINUED_HEDGE_NO_SCHEDULE",
            "POOL_BACKTEST_BREACH");

    /**
     * The two categories that do not quarantine their contract, mirroring
     * {@code ExceptionCategory.stopsTheContract()} returning false. Both still raise, and both
     * still block the close: a population that has quietly changed measurement basis is exactly
     * what a close should surface.
     */
    public static final Set<String> CATEGORIES_THAT_DO_NOT_STOP_THE_CONTRACT =
            Set.of("STALE_EQUIVALENCE_TEST", "POOL_BACKTEST_BREACH");

    /**
     * SQL type tokens that must never appear in this schema, as whole words.
     *
     * <p>ADR-0002 bans binary floating point: it cannot represent 0.01, and the error accumulated
     * over 360 compounding periods produces a reconciliation break with no single diagnosable
     * cause. PostgreSQL's {@code money} type is banned additionally because it is fixed-scale and
     * locale-dependent — its scale follows {@code lc_monetary}, so the same DDL means different
     * things on two servers.
     *
     * <p>Single tokens rather than full type names, matched as whole words: the two-word form of
     * the first entry is caught by its first token, and a token list cannot be defeated by
     * whitespace between the two words.
     */
    public static final List<String> BANNED_SQL_TYPE_TOKENS =
            List.of("double", "float", "float4", "float8", "real", "money");

    /** The first accounting period on the ACPIR basis: April 2027, as {@code YYYYMM}. */
    public static final int FIRST_ACPIR_PERIOD_ID = 202704;

    private LedgerSchema() {
        throw new AssertionError("constants only");
    }

    /**
     * Encodes a year and month as a {@code period_id}.
     *
     * <p>The {@code YYYYMM} encoding is not cosmetic: {@code period_id} is the range partition
     * key, so it has to be monotonic in reporting order, and a partition bound of
     * {@code FROM (202704) TO (202705)} says which period it holds without a lookup.
     *
     * @param year  calendar year
     * @param month calendar month, 1–12
     * @return the period id
     * @throws IllegalArgumentException if the month is out of range
     */
    public static int periodId(int year, int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month out of range: " + month);
        }
        if (year < 1900 || year > 9999) {
            throw new IllegalArgumentException("year out of range: " + year);
        }
        return year * 100 + month;
    }

    /**
     * The calendar year of a period id.
     *
     * @param periodId a {@code YYYYMM} period id
     * @return the year
     */
    public static int yearOf(int periodId) {
        requireWellFormed(periodId);
        return periodId / 100;
    }

    /**
     * The calendar month of a period id.
     *
     * @param periodId a {@code YYYYMM} period id
     * @return the month, 1–12
     */
    public static int monthOf(int periodId) {
        requireWellFormed(periodId);
        return periodId % 100;
    }

    /**
     * The period after this one, rolling December into the next January.
     *
     * <p>This is the upper bound of the period's partition, and getting the December roll wrong
     * would produce a partition covering {@code (202712, 202713)} — a bound PostgreSQL accepts
     * happily and which no row ever falls in on the far side.
     *
     * @param periodId a {@code YYYYMM} period id
     * @return the next period id
     */
    public static int nextPeriodId(int periodId) {
        requireWellFormed(periodId);
        return monthOf(periodId) == 12 ? periodId + 89 : periodId + 1;
    }

    /**
     * The partition of a range-partitioned ledger table that holds one period.
     *
     * <p>The convention is load-bearing rather than tidy: the migration's
     * {@code ledger_close_accounting_period} derives the partition names from the period id in
     * order to revoke write privileges on them, so a partition named any other way is invisible
     * to the close.
     *
     * @param parentTable one of {@link #PARTITIONED_TABLES}
     * @param periodId    a {@code YYYYMM} period id
     * @return the partition's relation name
     * @throws IllegalArgumentException if the table is not range-partitioned by period
     */
    public static String partitionName(String parentTable, int periodId) {
        if (!PARTITIONED_TABLES.contains(parentTable)) {
            throw new IllegalArgumentException(
                    "not partitioned by period_id: " + parentTable);
        }
        requireWellFormed(periodId);
        return parentTable + "_p" + periodId;
    }

    /**
     * The DEFAULT partition of a range-partitioned ledger table.
     *
     * <p>A safety net and not a destination. A row in here means a period's partition was not
     * created before the run wrote to it, and it will block the {@code ATTACH} of that partition
     * later.
     *
     * @param parentTable one of {@link #PARTITIONED_TABLES}
     * @return the default partition's relation name
     */
    public static String defaultPartitionName(String parentTable) {
        if (!PARTITIONED_TABLES.contains(parentTable)) {
            throw new IllegalArgumentException(
                    "not partitioned by period_id: " + parentTable);
        }
        return parentTable + "_p_default";
    }

    /**
     * Whether an exception of this category quarantines its contract, as against measuring it by
     * a different and more expensive route. Mirrors
     * {@code ExceptionCategory.stopsTheContract()}.
     *
     * @param category one of {@link #EXCEPTION_CATEGORIES}
     * @return true where the contract yields no figure at all
     */
    public static boolean stopsTheContract(String category) {
        if (!EXCEPTION_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("not an exception category: " + category);
        }
        return !CATEGORIES_THAT_DO_NOT_STOP_THE_CONTRACT.contains(category);
    }

    private static void requireWellFormed(int periodId) {
        int month = periodId % 100;
        if (month < 1 || month > 12 || periodId < 190001 || periodId > 999912) {
            throw new IllegalArgumentException("not a YYYYMM period id: " + periodId);
        }
    }
}
