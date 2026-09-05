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

    /**
     * Flows dated inside a window whose {@code period_id} puts them outside it.
     *
     * <p><b>Why a second query rather than dropping the {@code period_id} predicate from the
     * first.</b> {@link #SELECT_LINES} bounds both axes: the dates because the window is a date
     * range, and {@code period_id} because {@code cashflow_line} is range-partitioned on it (04
     * § 4) and a predicate on {@code flow_date} alone prunes nothing. At the measured per-contract
     * cost in {@code tools/load-harness/RESULTS.md} that pruning is not optional on a
     * ten-million-contract book.
     *
     * <p>But bounding both axes means a row whose two axes <em>disagree</em> is invisible to every
     * period — dated in May, filed under April, matched by neither window. Nothing in V1 forbids
     * it: {@code period_id} is the partition key and <b>no constraint requires it to agree with
     * {@code flow_date}</b>, which is a finding recorded against the schema in its own right. And
     * the consequence was not an empty vector but a worse thing: {@link #boundaryOnly} substitutes
     * a synthetic zero-amount flow, so the pipeline received a well-formed vector describing a
     * period in which nothing was due, while a real instalment sat in the table. A nil flow is
     * indistinguishable from a genuinely payment-free period and passes every downstream check.
     *
     * <p>So the pruning stays and this asks the complementary question. It is cheap because it is
     * bounded by {@code schedule_id} — one contract's own lines, an index lookup rather than a
     * scan — and it runs once per contract per period, alongside a read that was already
     * happening. It cannot be folded into {@link #SELECT_LINES}, because the whole point is to
     * look where that query is not allowed to.
     *
     * <p>A schema constraint would be better still and is <b>deliberately not</b> what this is. The
     * obvious {@code CHECK (period_id = year*100 + month of flow_date)} hard-codes the Gregorian
     * assumption into the schema, and {@code JdbcContractPeriodSource.readPeriodDates} goes to
     * explicit trouble NOT to assume it — under a 4-4-5 or 52/53-week calendar a period ends in the
     * following month and such a CHECK would refuse correct data. The correct constraint compares
     * {@code flow_date} against the {@code accounting_period} row for {@code period_id}, which
     * references a second table and is a trigger rather than a CHECK. That is a migration and a
     * decision about calendars; this is the read-side guard that makes the condition visible
     * meanwhile, and it is stated as such rather than as the fix.
     *
     * <h2>Both directions of disagreement, and the one case left alone on purpose</h2>
     *
     * <p>A row can disagree with itself two ways, and both lose the flow:
     *
     * <ul>
     *   <li><b>Dated inside this window, filed under another period.</b> {@link #SELECT_LINES}
     *       bounds {@code period_id}, so this period does not see it; and the period it is filed
     *       under does not see it either, because that period's date window excludes it.</li>
     *   <li><b>Filed under this period, dated outside this window.</b> The mirror image, and the
     *       one the live fixture carries — dated 2027-05-15, filed under 202704. Reading period
     *       202704 matches the partition and fails the date bound; reading 202705 matches the date
     *       and fails the partition bound. Matched by neither.</li>
     * </ul>
     *
     * <p>The first draft of this query had only the first case, which is the direction that reads
     * naturally from the defect's description and is <em>not</em> the direction the fixture
     * exhibits. It found nothing and the test that expected a refusal failed, which is the test
     * doing its job.
     *
     * <p><b>The second clause is strict at both ends, and that is deliberate rather than tidy.</b>
     * It asks for {@code flow_date < start OR flow_date > end} — strictly outside the
     * <em>closed</em> window — while the read uses the half-open {@code (start, end]}. The gap
     * between them is exactly one date: a flow dated on {@code period_start_date} and filed under
     * that period. The read will not return it, so on the reasoning above it is "lost" — but
     * whether it is <em>misfiled</em> depends on whether adjacent periods share a boundary date,
     * and this repository's two fixtures disagree: {@code eir-api}'s {@code Seed} runs period
     * 202805 from 2028-04-30 to 2028-05-31, so a period's start IS the previous period's end,
     * while the live fixture runs 202704 from 2027-04-01 to 2027-04-30, so it is not. Under the
     * first convention a flow on the start date belongs to the previous period and filing it here
     * is an error; under the second there is no previous period that would claim it and filing it
     * here is the only sensible thing a feed could do.
     *
     * <p>So that one date is excluded. A guard that refused it would quarantine every monthly loan
     * due on the first of the month under the live fixture's calendar — the whole book, for a
     * convention question nobody has settled. The half-open read boundary against a calendar whose
     * periods do not abut is a real question and it is recorded as one; it is not something to
     * decide silently inside a validation query.
     *
     * <p>Binds: schedule id, anchor date, upper date, lower period id, upper period id,
     * lower period id, upper period id, anchor date, upper date.
     */
    static final String SELECT_MISFILED_LINES = """
        SELECT l.flow_date,
               l.period_id,
               l.amount
          FROM cashflow_line l
         WHERE l.schedule_id = ?
           AND ((l.flow_date > ?
                 AND l.flow_date <= ?
                 AND l.period_id NOT BETWEEN ? AND ?)
             OR (l.period_id BETWEEN ? AND ?
                 AND (l.flow_date < ? OR l.flow_date > ?)))
         ORDER BY l.flow_date, l.sequence_no
        """;

    private FlowVectorReader() {
    }

    /**
     * Refuses a schedule carrying a flow whose date and period disagree.
     *
     * <p>A {@link ContractDataCondition}, so FR-905's barrier quarantines <em>this contract</em>
     * and the close reports it with a reason an operator can act on — the message names the row,
     * both of its axes and the window it fell between, because "a flow is missing" is not a
     * diagnosis and "period_id 202704 on a flow dated 2027-05-15" is. The alternative shapes are
     * both worse: silently including the flow would move an instalment into a period the ledger
     * has closed, and reading on regardless is the current behaviour this replaces — a synthetic
     * nil boundary flow that publishes a payment-free period over a real instalment.
     *
     * <p>Called on the period read, before the vector is used, so that no computation happens on a
     * vector known to be short. See {@link #SELECT_MISFILED_LINES} for why this cannot be one
     * query with the read it accompanies.
     */
    static void refuseMisfiledLines(Connection connection, String scheduleId,
        LocalDate anchor, LocalDate upperInclusive) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_MISFILED_LINES)) {
            statement.setObject(1, java.util.UUID.fromString(scheduleId));
            // Clause one: dated inside the window, filed outside the period range.
            statement.setObject(2, anchor, java.sql.Types.DATE);
            statement.setObject(3, upperInclusive, java.sql.Types.DATE);
            statement.setInt(4, PeriodId.of(anchor));
            statement.setInt(5, PeriodId.of(upperInclusive));
            // Clause two: filed inside the period range, dated strictly outside the window. The
            // same four values in the other order, and the date comparisons are strict at both
            // ends -- see the query's javadoc for the single date that exclusion protects.
            statement.setInt(6, PeriodId.of(anchor));
            statement.setInt(7, PeriodId.of(upperInclusive));
            statement.setObject(8, anchor, java.sql.Types.DATE);
            statement.setObject(9, upperInclusive, java.sql.Types.DATE);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                throw new ContractDataCondition(
                    "cash flow schedule " + scheduleId + " carries a flow dated "
                        + Rows.date(rs, "flow_date") + " for " + rs.getBigDecimal("amount")
                        + " filed under period " + rs.getInt("period_id") + ", which is outside"
                        + " the period being read (" + anchor + " exclusive to " + upperInclusive
                        + " inclusive, periods " + PeriodId.of(anchor) + " to "
                        + PeriodId.of(upperInclusive) + "). period_id is the partition key and"
                        + " nothing in V1 requires it to agree with flow_date, so this flow is"
                        + " matched by no period's window at all: reading on would substitute a"
                        + " zero-amount boundary flow and publish a period in which nothing was"
                        + " due while the instalment sits in the table");
            }
        }
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
