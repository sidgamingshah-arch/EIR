package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * {@code cashflow_line} rows to a {@link FlowVector}.
 *
 * <h2>EXPECTED wins over CONTRACTUAL, and the choice is not arbitrary</h2>
 *
 * <p>V1 keeps exactly two schedules per contract version and the uniqueness constraint is what makes
 * "the contractual leg" a well-defined phrase. The amortisation runs on the <em>expected</em> flows —
 * ACPIR 51 expected life is what the EIR is struck over — and the contractual schedule is the leg
 * that ties to the CBS. So {@link #selectSchedule} prefers {@code EXPECTED} and falls back to
 * {@code CONTRACTUAL} for a contract where no expected schedule has been derived, which is the
 * ACPIR 51 fallback case ({@code contract_version.acpir_51_fallback}) and is legitimate.
 *
 * <p>Reversing the preference would be a silent, book-wide change of basis: every contract with a
 * behavioural prepayment curve would amortise over its full contractual term instead, understating
 * the periodic yield and overstating the unamortised fee balance, with no figure out of place.
 *
 * <h2>The date interval is half-open at the bottom, inclusive at the top</h2>
 *
 * <p>{@code flow_date > anchor AND flow_date <= upperInclusive}. The anchor is t = 0: a flow landing
 * on it is not discounted, it is part of the opening position, and {@link FlowVector#future()}
 * excludes it. Including it here would put the previous period's closing receipt into this period's
 * accrual, counting one instalment twice across the boundary — which is the same double-count
 * {@code PeriodEvent} guards against when it insists an event's revised vector is anchored on the
 * event date.
 *
 * <h2>{@code period_id} bounds the scan as well as the dates, for partition pruning</h2>
 *
 * <p>{@code cashflow_line} is range-partitioned by {@code period_id} (04 § 4) and the partitioning is
 * load-bearing: "partition pruning is what keeps a close-period query tractable" at roughly 120M rows
 * a year. A predicate on {@code flow_date} alone prunes nothing, so the period range is passed too.
 *
 * <p>That relies on {@code period_id} encoding the month of {@code flow_date}, which is what the
 * column means — "accounting time, and the partition key". A row whose {@code period_id} disagreed
 * with its {@code flow_date} would be missed here, and it would equally be missed by the close, by
 * the read-only switch on the closed period's partitions and by every reconciliation that prunes: it
 * is in the wrong partition, which is a load defect and not a query one.
 */
final class FlowVectorReader {

    /**
     * Binds: contract version id.
     *
     * <p>Ordered so EXPECTED sorts first. {@code LIMIT 1} is safe because
     * {@code cashflow_schedule_kind_uq} allows at most one schedule of each kind per version.
     */
    static final String SELECT_SCHEDULE = """
        SELECT s.schedule_id,
               s.kind
          FROM cashflow_schedule s
         WHERE s.contract_version_id = ?
         ORDER BY CASE s.kind WHEN 'EXPECTED' THEN 0 ELSE 1 END
         LIMIT 1
        """;

    /** Binds: schedule id, lower period id, upper period id, anchor date, upper date. */
    static final String SELECT_LINES = """
        SELECT l.flow_date,
               l.amount,
               l.kind,
               l.is_contingent,
               l.sequence_no
          FROM cashflow_line l
         WHERE l.schedule_id = ?
           AND l.period_id BETWEEN ? AND ?
           AND l.flow_date > ?
           AND l.flow_date <= ?
         ORDER BY l.flow_date, l.sequence_no
        """;

    /**
     * Every flow after the anchor, with no upper bound. Binds: schedule id, lower period id,
     * anchor date.
     *
     * <p>Used for the vector a re-solve discounts. There is no upper date, and no maturity date is
     * consulted to invent one: {@code ContractTerms.maturityDate()} needs period-anniversary
     * arithmetic, which {@code WEEKLY} and {@code FORTNIGHTLY} schedules do not have, and a bound
     * derived from {@code contractual_maturity_date} would silently truncate the vector of any
     * contract whose expected life runs past its contractual maturity — which is most of the
     * behavioural book.
     *
     * <p>The {@code period_id} lower bound still prunes every partition before the anchor's period,
     * which is the pruning that matters: the flows before the anchor are the ones already amortised
     * and they are the bulk of a seasoned contract's schedule.
     */
    static final String SELECT_REMAINING_LINES = """
        SELECT l.flow_date,
               l.amount,
               l.kind,
               l.is_contingent,
               l.sequence_no
          FROM cashflow_line l
         WHERE l.schedule_id = ?
           AND l.period_id >= ?
           AND l.flow_date > ?
         ORDER BY l.flow_date, l.sequence_no
        """;

    private FlowVectorReader() {
    }

    /** The schedule the amortisation reads, or null where the version has none. */
    static String selectSchedule(Connection connection, String contractVersionId)
        throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_SCHEDULE)) {
            statement.setObject(1, java.util.UUID.fromString(contractVersionId));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Rows.text(rs, "schedule_id") : null;
            }
        }
    }

    /**
     * The vector of flows in {@code (anchor, upperInclusive]}, indexed from 1 in date order.
     *
     * <p><b>{@code periodIndex} is assigned by position in this vector, not read from the row.</b>
     * {@code cashflow_line.sequence_no} is the schedule's own ordinal from inception and would put
     * flow number 37 at exponent 37 in a vector anchored at period 36 — inflating every discount
     * factor by 36 periods. The index a discounting exponent needs is measured from the vector's own
     * anchor, which is what {@code TimeConvention.PeriodicIndex.tau} reads, so it is derived here.
     * {@code sequence_no} still drives the tie-break ordering within a date, because two flows on one
     * date must be ordered reproducibly (FR-903).
     */
    static FlowVector read(Connection connection, String scheduleId, Currency currency,
        LocalDate anchor, LocalDate upperInclusive) throws SQLException {

        List<CashFlow> flows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_LINES)) {
            statement.setObject(1, java.util.UUID.fromString(scheduleId));
            statement.setInt(2, PeriodId.of(anchor));
            statement.setInt(3, PeriodId.of(upperInclusive));
            statement.setObject(4, anchor, java.sql.Types.DATE);
            statement.setObject(5, upperInclusive, java.sql.Types.DATE);

            try (ResultSet rs = statement.executeQuery()) {
                collect(rs, currency, flows);
            }
        }
        return FlowVector.of(anchor, currency, flows);
    }

    /**
     * Every flow strictly after {@code anchor}, indexed from 1 in date order.
     *
     * <p>The vector a re-solve discounts. See {@link #SELECT_REMAINING_LINES} for why there is no
     * upper bound and why no maturity date is consulted to supply one.
     */
    static FlowVector readRemaining(Connection connection, String scheduleId, Currency currency,
        LocalDate anchor) throws SQLException {

        List<CashFlow> flows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REMAINING_LINES)) {
            statement.setObject(1, java.util.UUID.fromString(scheduleId));
            statement.setInt(2, PeriodId.of(anchor));
            statement.setObject(3, anchor, java.sql.Types.DATE);

            try (ResultSet rs = statement.executeQuery()) {
                collect(rs, currency, flows);
            }
        }
        return FlowVector.of(anchor, currency, flows);
    }

    private static void collect(ResultSet rs, Currency currency, List<CashFlow> flows)
        throws SQLException {

        int index = 0;
        while (rs.next()) {
            index++;
            LocalDate flowDate = Rows.date(rs, "flow_date");
            Money amount = Rows.money(rs, "amount", currency);
            FlowKind kind = flowKind(Rows.text(rs, "kind"));
            flows.add(rs.getBoolean("is_contingent")
                ? CashFlow.contingent(flowDate, index, amount, kind)
                : CashFlow.of(flowDate, index, amount, kind));
        }
    }

    /**
     * A single zero-amount flow on {@code periodDate}, declaring the accrual boundary.
     *
     * <p>{@code ContractPeriodSource}'s javadoc prescribes exactly this for a period with nothing in
     * it: "an implementation with nothing to say should return a period whose cash is nil and whose
     * vector carries a zero-amount flow on the period date, which is how {@code AmortisationEngine} is
     * documented to be told that a boundary exists". {@code ContractPeriod} refuses a vector with no
     * flow after its anchor, so the alternative is no row at all for the period — and a period that
     * produces no row is a period whose accrual is silently omitted from the sub-ledger.
     *
     * <p>{@link FlowKind#PRINCIPAL} carrying zero, because a zero flow changes no present value and
     * no balance whatever its kind, and PRINCIPAL is the kind that cannot be mistaken for recognised
     * income in a trace.
     */
    static FlowVector boundaryOnly(Currency currency, LocalDate anchor, LocalDate periodDate) {
        return FlowVector.of(anchor, currency,
            List.of(CashFlow.of(periodDate, 1, Money.zero(currency), FlowKind.PRINCIPAL)));
    }

    private static FlowKind flowKind(String value) {
        try {
            return FlowKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "cash flow kind '" + value + "' is not a FlowKind. V1's cashflow_line_kind_ck admits"
                    + " ten values and mirrors the enum exactly; a value outside both means the row"
                    + " cannot be placed on a leg, and dropping it would remove cash from the"
                    + " projection without removing it from the schedule");
        }
    }
}
