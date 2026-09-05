package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The rate in force and the convention it was struck under, from one row of one solve.
 *
 * <h2>Why these two columns cannot be read separately</h2>
 *
 * <p><b>They were, and it aborted runs.</b> {@code JdbcContractStateSource} wrapped
 * {@code eir_computation.rate_periodic} as {@code Rate.periodic(value, terms.periodsPerYear())} —
 * the periodicity from the contract's schedule — while {@code JdbcContractPeriodSource} read
 * {@code eir_computation.convention} from the same solve and built a {@link TimeConvention} from
 * that. The two then met in {@link com.crisil.eir.calc.amort.AmortisationEngine}, whose first guard
 * is {@code rate.periodsPerYear() != convention.periodsPerYear()}.
 *
 * <p>For a {@code PERIODIC_INDEX} solve they agree, which is why nothing caught this: the live
 * fixture stores {@code PERIODIC_INDEX} on every solve. For an {@code ACTUAL_DATE} solve the
 * convention implies <b>1</b> period a year — the rate is annual effective, and
 * {@code TimeConvention.ActualDate} says so — while a monthly contract's terms imply 12. The engine
 * then threw {@code IllegalArgumentException}, which is not a data condition and not caught by
 * FR-905's per-contract barrier: <b>one contract with an actual-date solve aborted the whole
 * run</b>, and the message named an arithmetic precondition rather than the two disagreeing
 * columns somebody has to go and reconcile.
 *
 * <p>The fix is not a validation. Validating a pair that two readers construct independently leaves
 * the pair constructible, and a later reader adds a third route. Here the rate is wrapped with
 * {@code convention.periodsPerYear()} — the convention's own answer, from the convention read out
 * of the same row — so the engine's guard is <b>structurally unfailable on this path</b>. There is
 * no value either column can hold that makes the two disagree, because only one of them is
 * consulted for the periodicity.
 *
 * <h2>Why one query rather than two identical ones</h2>
 *
 * <p>The two queries this replaces were byte-identical in their predicate — same status filter, same
 * two-part supersession test, same {@code computed_as_of} bound, same three-column ordering, same
 * {@code LIMIT 1} — and lived in two classes. Two copies of a "which row is in force" rule
 * eventually give two answers, because only one gets amended; and the specific failure here needs
 * no amendment at all to appear. Two {@code LIMIT 1} queries with an unstable tie in the ordering
 * can select different rows in the same run, and then the rate is from one solve and its convention
 * from another. The ordering is in fact total ({@code computation_id} breaks every tie), so that was
 * latent rather than live — but it is latent only for as long as nobody edits one copy.
 */
final class SolvedRateReader {

    /**
     * Binds: contract id, {@code recordedAsAt}, {@code recordedAsAt}, {@code businessAsOf}.
     *
     * <p>{@code ORDER BY} is total: {@code computed_as_of} is the business-time answer,
     * {@code recorded_at} breaks a same-day re-solve, and {@code computation_id} breaks the
     * remainder so that {@code LIMIT 1} is deterministic. A non-total ordering here would make a
     * replay of a contract re-solved twice in one day reproduce a different rate, which DT-1 would
     * report as a reproduction failure with nothing in the data to explain it.
     */
    static final String SELECT_SOLVE_IN_FORCE = """
        SELECT e.rate_periodic,
               e.convention
          FROM eir_computation e
         WHERE e.contract_id = ?
           AND e.status = 'SOLVED'
           AND e.recorded_at <= ?
           AND (e.superseded_by IS NULL
                OR NOT EXISTS (SELECT 1
                                 FROM eir_computation s
                                WHERE s.computation_id = e.superseded_by
                                  AND s.recorded_at <= ?))
           AND e.computed_as_of <= ?
         ORDER BY e.computed_as_of DESC, e.recorded_at DESC, e.computation_id DESC
         LIMIT 1
        """;

    private SolvedRateReader() {
    }

    /**
     * The solve in force for a contract, or {@code null} where none is visible.
     *
     * @param rate       the stored periodic rate, already wrapped at the convention's periodicity.
     *                   Null where the solve exists with {@code status <> 'SOLVED'} cannot happen —
     *                   V1's {@code CHECK ((status = 'NO_SOLUTION') = (rate_periodic IS NULL))}
     *                   makes a SOLVED row's rate NOT NULL — but the column is nullable in the
     *                   schema, so it is read as nullable here rather than trusted.
     * @param convention the convention that solve was struck under, and the sole source of the
     *                   periodicity {@code rate} carries
     */
    record Solve(Rate rate, TimeConvention convention) {

        Solve {
            Objects.requireNonNull(convention, "convention");
        }
    }

    /**
     * Reads the solve in force.
     *
     * <p>Returns {@code null} — not an empty {@link java.util.Optional} and not a default — where
     * no solve is visible. A contract with no solve has never been through initial recognition, and
     * the callers say different things about that: an opening state has no EIR to publish, and a
     * period's convention falls back to {@code ACTUAL_DATE} because
     * {@link TimeConvention}'s own javadoc makes it "required for correctness everywhere else, and
     * the default" — safe when unverified, as against {@code PeriodicIndex}, which is valid "only
     * when periods are uniform and every flow sits on a boundary". Deciding that here would impose
     * one caller's answer on the other.
     *
     * @param termPeriodsPerYear the contract schedule's periodicity, used <em>only</em> to build a
     *                           {@code PERIODIC_INDEX} convention, which is defined in terms of it
     * @param dayCount           the contract's day count, used only to build an
     *                           {@code ACTUAL_DATE} convention
     */
    static Solve inForce(Connection connection, String contractId, Instant recordedAsAt,
        LocalDate businessAsOf, int termPeriodsPerYear,
        com.crisil.eir.domain.DayCountConvention dayCount) throws SQLException {

        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(recordedAsAt, "recordedAsAt");
        Objects.requireNonNull(businessAsOf, "businessAsOf");
        Objects.requireNonNull(dayCount, "dayCount");

        try (PreparedStatement statement = connection.prepareStatement(SELECT_SOLVE_IN_FORCE)) {
            new Params(statement)
                .contractId(contractId)
                .instant(recordedAsAt)
                .instant(recordedAsAt)
                .date(businessAsOf);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                TimeConvention convention =
                    conventionOf(Rows.text(rs, "convention"), termPeriodsPerYear, dayCount);
                // periodsPerYear comes from the CONVENTION, never from the terms. That single
                // choice is what makes AmortisationEngine's first guard unfailable on this path.
                return new Solve(
                    Rows.rateOrNull(rs, "rate_periodic", convention.periodsPerYear()),
                    convention);
            }
        }
    }

    /**
     * Maps the stored text onto a convention.
     *
     * <p>A value V1's {@code eir_computation_convention_ck} does not admit is a
     * {@link ContractDataCondition} rather than a {@link PersistenceFailure}: it is one row that
     * cannot be interpreted, so FR-905 quarantines that contract and the close reports it, instead
     * of an outage-shaped abort. This is the change of kind the finding asked for — the previous
     * code raised a bare {@code PersistenceFailure} here, which the state source's catch does not
     * convert, so a single unrecognised value stopped every contract in the run.
     */
    private static TimeConvention conventionOf(String stored, int termPeriodsPerYear,
        com.crisil.eir.domain.DayCountConvention dayCount) {

        return switch (stored) {
            case "ACTUAL_DATE" -> new TimeConvention.ActualDate(dayCount);
            case "PERIODIC_INDEX" -> new TimeConvention.PeriodicIndex(termPeriodsPerYear);
            default -> throw new ContractDataCondition(
                "eir_computation.convention '" + stored + "' is neither PERIODIC_INDEX nor"
                    + " ACTUAL_DATE, which V1's eir_computation_convention_ck admits. The"
                    + " convention decides how every discount exponent is built and what"
                    + " periodicity the stored rate carries, so it cannot be defaulted for a"
                    + " contract that has a solved rate");
        };
    }
}
