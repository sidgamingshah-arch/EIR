package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.application.run.ContractPeriodSource;
import com.crisil.eir.application.run.PeriodEvent;
import com.crisil.eir.calc.routing.ModificationConclusion;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.TimeConvention;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * One contract's movements for one accounting period.
 *
 * <h2>Non-optional, and the asymmetry with {@code openingState} is honoured rather than smoothed</h2>
 *
 * <p>{@code ContractPeriodSource} states it: "a contract in the population with no opening state as
 * at the boundary is a data condition the run reports per contract, whereas a contract with no
 * <em>movements</em> is still a contract with an accrual — a period with no cash is a period". So this
 * adapter never returns a period of no movement for a contract it cannot find: an unknown contract,
 * an absent contract version, a version with no schedule, an accounting period the ledger does not
 * know about — each throws, because each is a defect in the source and not a fact about a contract.
 * A contract that genuinely moved nothing gets a period whose cash is nil and whose vector carries
 * the zero-amount boundary flow the port's javadoc prescribes.
 *
 * <h2>Five inputs, five sources, and the separations are the controls</h2>
 *
 * <ul>
 *   <li><b>The flow vector</b> — {@code cashflow_line} through the visible {@code contract_version}.
 *   <li><b>The cash split</b> — {@code cash_book_application} (V3), the cash book. Deliberately not
 *       {@code period_balance.cash_received}: that column is one unsplit total <em>and</em> the
 *       engine's own output, so using it would make the journal's cash block a field compared
 *       against itself and would remove the only thing that can detect a receipt applied to the
 *       wrong leg.
 *   <li><b>The suspense balance brought forward</b> — the <em>prior</em> period's
 *       {@code suspense_entry.closing_balance}, for the same reason the opening GCA is the prior
 *       period's closing GCA.
 *   <li><b>The suspense movements</b> — {@code suspense_movement} (V3), the collections system.
 *       Separate from the cash book because invariant S3-1's fourth leg compares cash applied to
 *       interest <em>against</em> suspended interest recovered; one source for both would make that
 *       leg a restatement of one figure.
 *   <li><b>The event</b> — {@code lifecycle_event}, with its own {@code recorded_at} /
 *       {@code superseded_at} pair, so a backdated event is invisible to a replay of a period that
 *       closed before it was recorded.
 * </ul>
 *
 * <h2>The period ordinal is derived from the contract's schedule, not from a loop counter</h2>
 *
 * <p>{@code ContractPeriod}'s javadoc is explicit that this is what makes invariant ST-2 a control:
 * the ordinal lets {@code ContractPipeline} derive the accrual length from the <em>contract's</em>
 * schedule while the roll-forward derives it from the <em>supplied vector's</em> dates, and "two
 * independent derivations of one quantity is what makes ST-2 against the ledger a control instead of
 * a tautology". So it is computed here from {@code first_due_date} and the compounding frequency, and
 * never handed down by the caller.
 *
 * <p>{@link PeriodId#elapsedPeriods} counts the contract's own due dates rather than subtracting
 * months, which is what makes the ordinal right for a contract whose instalments do not fall on the
 * 1st, and it floors at 0 — a boundary before the first due date is <em>inside</em> the contract's
 * first period, the one running from disbursement to the first instalment, so the ordinal is 1.
 * {@code ContractPeriod} refuses 0 with the right reason ("zero is the disbursement boundary, not a
 * period").
 */
public final class JdbcContractPeriodSource extends JdbcAdapter implements ContractPeriodSource {

    /** Binds: period id. */
    static final String SELECT_PERIOD_DATES = """
        SELECT ap.period_start_date,
               ap.period_end_date
          FROM accounting_period ap
         WHERE ap.period_id = ?
        """;

    /** Binds: contract id, period id, book id, then {@link Params#systemTime}. */
    static final String SELECT_CASH_SPLIT = """
        SELECT cb.applied_to_principal,
               cb.applied_to_interest
          FROM cash_book_application cb
         WHERE cb.contract_id = ?
           AND cb.period_id = ?
           AND cb.book_id = ?
           AND %s
        """.formatted(TemporalReads.systemTime("cb"));

    /** Binds: contract id, prior period id, book id. */
    static final String SELECT_PRIOR_SUSPENSE = """
        SELECT se.closing_balance
          FROM suspense_entry se
         WHERE se.contract_id = ?
           AND se.period_id = ?
           AND se.book_id = ?
        """;

    /** Binds: contract id, period id, book id, then {@link Params#systemTime}. */
    static final String SELECT_SUSPENSE_MOVEMENT = """
        SELECT sm.recovered,
               sm.written_off
          FROM suspense_movement sm
         WHERE sm.contract_id = ?
           AND sm.period_id = ?
           AND sm.book_id = ?
           AND %s
        """.formatted(TemporalReads.systemTime("sm"));

    /**
     * Binds: contract id, period start, period end, then {@link Params#systemTime}.
     *
     * <p>{@code routed_mechanism <> 'NONE'} because V2 admits {@code NONE} for an event the routing
     * table decided needs no treatment — a rate observation on a fixed-rate contract, say — and such
     * an event has no revised cash flow vector to carry. {@code PeriodEvent} would refuse it: "an
     * event that changes no future cash flow has nothing for the routing table to route".
     *
     * <p>Ordered by {@code event_date} then {@code sequence_within_date}, which V2 makes a total
     * order: {@code uq_lifecycle_event_ordering} is unique on
     * {@code (contract_id, event_date, sequence_within_date)}, and its comment gives the reason —
     * "two events on one date must amortise in a fixed order or the run is not reproducible
     * (FR-903)".
     *
     * <p><b>Not {@code LIMIT 1}, deliberately.</b> {@code ContractPeriod} carries one
     * {@code PeriodEvent}, and V2 permits a contract to carry two routed events inside one
     * accounting period — a negotiated modification on the 15th and an ESG-linked reset on the 25th,
     * both with {@code routed_mechanism <> 'NONE'}. Taking the first and discarding the rest would
     * leave the second with no modification test and no re-solve, and nothing would report it: the
     * same silent-drop failure {@link #readEvent} refuses for an event whose revised vector is
     * empty. Worse, the revised vector handed to the first event is the boundary-resolved version's
     * schedule, which already reflects the second amendment — so the first event would be routed
     * against the second's cash flows. The rows are counted instead, and more than one is refused by
     * name.
     */
    static final String SELECT_EVENT = """
        SELECT le.driver,
               le.event_date,
               le.substantiality_conclusion,
               le.sequence_within_date
          FROM lifecycle_event le
         WHERE le.contract_id = ?
           AND le.event_date >= ?
           AND le.event_date <= ?
           AND le.routed_mechanism <> 'NONE'
           AND %s
         ORDER BY le.event_date, le.sequence_within_date
        """.formatted(TemporalReads.systemTime("le"));


    public JdbcContractPeriodSource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcContractPeriodSource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public ContractPeriod periodFor(String contractId, AsAtBoundary boundary) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(boundary, "boundary");

        int periodId = PeriodId.of(boundary.businessAsOf());

        try (Connection connection = open()) {
            ContractTermsReader.Row terms = readTerms(connection, contractId, boundary);
            if (terms == null) {
                throw new PersistenceFailure(
                    "no contract version visible for contract " + contractId + " as at "
                        + boundary.recordedAsAt() + " on " + boundary.businessAsOf()
                        + "; ContractPeriodSource is non-optional because 'a source that cannot"
                        + " answer at all is a defect in the source, not a data condition' —"
                        + " inventing a period of no movement here would publish a contract that"
                        + " accrued nothing and would reconcile perfectly");
            }
            Currency currency = terms.currency();

            PeriodDates dates = readPeriodDates(connection, periodId, boundary.businessAsOf());
            String scheduleId = FlowVectorReader.selectSchedule(connection,
                terms.contractVersionId());
            if (scheduleId == null) {
                throw new PersistenceFailure(
                    "contract version " + terms.contractVersionId() + " carries no cash flow"
                        + " schedule. V1 keeps exactly two per version (CONTRACTUAL and EXPECTED);"
                        + " a version with neither cannot be projected, and an empty vector would"
                        + " solve to a rate for a contract with no remaining flows");
            }

            // Before reading the vector, not after: a flow whose period_id disagrees with its
            // flow_date is matched by no period's window, and the vector would come back short
            // with a synthetic nil boundary flow standing in for a real instalment. Quarantining
            // the contract is the honest answer; computing on a vector known to be incomplete is
            // not. See FlowVectorReader.SELECT_MISFILED_LINES for why this is a second query and
            // why it is not a schema CHECK.
            FlowVectorReader.refuseMisfiledLines(connection, scheduleId, dates.start(),
                dates.end());
            FlowVector inPeriod = FlowVectorReader.read(connection, scheduleId, currency,
                dates.start(), dates.end());
            FlowVector periodFlows = inPeriod.future().isEmpty()
                ? FlowVectorReader.boundaryOnly(currency, dates.start(), dates.end())
                : inPeriod;

            CashSplit cash = readCashSplit(connection, contractId, periodId, boundary, currency);
            Money suspenseOpening = readPriorSuspenseClosing(connection, contractId,
                PeriodId.previous(periodId), currency);
            SuspenseMovement movement = readSuspenseMovement(connection, contractId, periodId,
                boundary, currency);

            ContractPeriod period = new ContractPeriod(
                contractId,
                periodOrdinal(terms, boundary.businessAsOf()),
                periodFlows,
                convention(connection, contractId, boundary, terms),
                cash.principal(),
                cash.interest(),
                suspenseOpening,
                movement.recovered(),
                movement.writtenOff(),
                null);

            PeriodEvent event = readEvent(connection, contractId, dates, boundary, terms,
                scheduleId, currency);
            return event == null ? period : period.withEvent(event);

        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read period movements for contract " + contractId + " period "
                    + periodId + " as at " + boundary.recordedAsAt(), e);
        }
    }

    private ContractTermsReader.Row readTerms(
        Connection connection, String contractId, AsAtBoundary boundary) throws SQLException {

        try (PreparedStatement statement =
                 connection.prepareStatement(ContractTermsReader.SELECT_BY_CONTRACT_ID)) {
            new Params(statement)
                .contractId(contractId)
                .businessTime(boundary.businessAsOf())
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? ContractTermsReader.read(rs) : null;
            }
        }
    }

    /**
     * The period's own start and end dates, from the ledger's calendar.
     *
     * <p>Read rather than computed from the {@code YYYYMM} encoding. V2 constrains
     * {@code period_start_date} to agree with the encoded year and month
     * ({@code ck_accounting_period_id_matches_dates}) and leaves {@code period_end_date} free, so a
     * period ending on the 2nd of the following month is representable. Deriving
     * {@code PeriodId.endOf(periodId)} instead would place the accrual boundary on the last calendar
     * day of the month regardless, moving a day of interest between two periods.
     *
     * <h3>The Gregorian assumption, made explicit rather than left implied</h3>
     *
     * <p>Every adapter in this module derives its {@code period_id} as
     * {@code PeriodId.of(boundary.businessAsOf())} — the calendar year and month of the business
     * date. <b>That is load-bearing and it is an assumption</b>: under a 4-4-5 or 52/53-week calendar
     * a period end falls in the following month, so a business date of 2 May 2027 closing period
     * {@code 202704} would be read as {@code 202705}. The run would then read the wrong period's
     * dates, the wrong flow window, the wrong prior period for the opening balance and suspense, and
     * would write against the wrong partition — every figure internally consistent and every one for
     * the wrong month.
     *
     * <p>So the assumption is checked here rather than documented away: the business date must fall
     * inside the period the encoding named. That converts a whole run of misfiled figures into one
     * named refusal on the first contract, and it is the cheapest possible place to catch it because
     * this is the only adapter that reads the calendar at all.
     *
     * @throws PersistenceFailure where the ledger has no such period, which is a defect (every
     *     {@code period_balance} and {@code journal_entry} row has a foreign key to this table, so a
     *     run over an unknown period could not write its output even if it computed it), or where the
     *     business date falls outside the period the {@code YYYYMM} encoding selected
     */
    private PeriodDates readPeriodDates(Connection connection, int periodId, LocalDate businessAsOf)
        throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_PERIOD_DATES)) {
            new Params(statement).integer(periodId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new PersistenceFailure(
                        "accounting period " + periodId + " does not exist. PERIOD_BALANCE and"
                            + " JOURNAL_ENTRY both have a foreign key to ACCOUNTING_PERIOD, so a run"
                            + " over an unopened period cannot write its own output — and the"
                            + " partitions the close sets read-only are created per period by"
                            + " ledger_create_period_partitions");
                }
                PeriodDates dates = new PeriodDates(
                    Rows.date(rs, "period_start_date"), Rows.date(rs, "period_end_date"));
                if (businessAsOf.isBefore(dates.start()) || businessAsOf.isAfter(dates.end())) {
                    throw new PersistenceFailure(
                        "business date " + businessAsOf + " encodes period " + periodId + ", whose"
                            + " ledger calendar runs " + dates.start() + " to " + dates.end()
                            + " and does not contain it. Every adapter here derives the period id"
                            + " from the calendar year and month of the business date, which assumes"
                            + " the accounting period IS the Gregorian month; under a 4-4-5 or"
                            + " 52/53-week calendar it is not, and the run would read one period's"
                            + " movements while writing against another's partition");
                }
                return dates;
            }
        }
    }

    private CashSplit readCashSplit(Connection connection, String contractId, int periodId,
        AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_CASH_SPLIT)) {
            new Params(statement)
                .contractId(contractId)
                .integer(periodId)
                .text(bookId())
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    // No cash book line is a period in which no cash was applied, which is
                    // ordinary — a Stage 3 contract in default receives nothing for months. Zero is
                    // the right answer here and is not a substituted figure: the absence of a
                    // receipt IS a receipt of nothing, unlike the absence of a balance.
                    return new CashSplit(Money.zero(currency), Money.zero(currency));
                }
                return new CashSplit(
                    Rows.money(rs, "applied_to_principal", currency),
                    Rows.money(rs, "applied_to_interest", currency));
            }
        }
    }

    /**
     * The suspense balance brought forward.
     *
     * <p><b>A stated limitation.</b> {@code suspense_entry} carries no system-time columns — V2 gives
     * it none — so this read has a business-time axis (the prior period) and no decision-time axis. A
     * correction to a closed period's suspense balance would therefore be visible to a replay of a
     * later period, where the GCA leg's equivalent correction would not be. That is a gap in the
     * schema rather than in this adapter, and it is reported rather than papered over with a guess at
     * which column was meant to carry the instant.
     */
    private Money readPriorSuspenseClosing(Connection connection, String contractId,
        int priorPeriodId, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_PRIOR_SUSPENSE)) {
            new Params(statement)
                .contractId(contractId)
                .integer(priorPeriodId)
                .text(bookId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                    ? Rows.money(rs, "closing_balance", currency)
                    : Money.zero(currency);
            }
        }
    }

    private SuspenseMovement readSuspenseMovement(Connection connection, String contractId,
        int periodId, AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_SUSPENSE_MOVEMENT)) {
            new Params(statement)
                .contractId(contractId)
                .integer(periodId)
                .text(bookId())
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new SuspenseMovement(Money.zero(currency), Money.zero(currency));
                }
                return new SuspenseMovement(
                    Rows.money(rs, "recovered", currency),
                    Rows.money(rs, "written_off", currency));
            }
        }
    }

    /**
     * The period's cash-flow-change event, or null.
     *
     * <p>The revised vector is the visible schedule's remaining flows anchored on the event date, and
     * that is coherent rather than convenient: an amendment produces a new
     * {@code contract_version} whose {@code valid_from} is the event date, so the version this
     * boundary already resolved to <em>is</em> the amended one and its schedule <em>is</em> the
     * revised schedule. {@code PeriodEvent} insists the anchor equals the event date, because "the
     * anchor is the date both the re-solve and the restatement measure from, so a mismatch shifts
     * every exponent by the gap and neither TR-1 nor CU-2 would attribute the break to here".
     *
     * <p>A routed event whose revised vector comes back empty is refused rather than downgraded to a
     * period with no event. Silently dropping it would leave the contract amortising against a
     * superseded schedule for the rest of its life, and no invariant looks for an event that is not
     * there.
     */
    private PeriodEvent readEvent(Connection connection, String contractId, PeriodDates dates,
        AsAtBoundary boundary, ContractTermsReader.Row terms, String scheduleId, Currency currency)
        throws SQLException {

        LocalDate eventDate;
        RateDriver driver;
        String conclusionText;
        int routedEvents = 0;
        StringBuilder found = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENT)) {
            new Params(statement)
                .contractId(contractId)
                .date(dates.start())
                .date(dates.end())
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                driver = driver(Rows.text(rs, "driver"));
                eventDate = Rows.date(rs, "event_date");
                conclusionText = Rows.textOrNull(rs, "substantiality_conclusion");
                routedEvents = 1;
                found.append(eventDate).append('#')
                    .append(Rows.integer(rs, "sequence_within_date")).append(' ').append(driver);
                while (rs.next()) {
                    routedEvents++;
                    found.append(", ").append(Rows.date(rs, "event_date")).append('#')
                        .append(Rows.integer(rs, "sequence_within_date")).append(' ')
                        .append(Rows.text(rs, "driver"));
                }
            }
        }
        if (routedEvents > 1) {
            // ContractPeriod carries one event and this adapter will not choose between two. See
            // SELECT_EVENT: taking the earliest would route it against the LATER amendment's cash
            // flows, because the revised vector below comes from the version the boundary resolved
            // to — which already reflects both. Refused by name so the contract can be quarantined
            // rather than published on a routing nobody chose.
            throw new PersistenceFailure(
                "contract " + contractId + " carries " + routedEvents + " routed lifecycle events"
                    + " inside period " + dates.start() + ".." + dates.end() + " (" + found + ")."
                    + " ContractPeriod carries one, and the revised vector available here is the"
                    + " boundary-resolved schedule — which already reflects every one of them, so"
                    + " routing the earliest against it would measure the first event using the"
                    + " last one's cash flows. Two events in one period need the intra-period"
                    + " ordering of 05 § 3.2 applied by the pipeline, which this seam cannot do");
        }

        // Every remaining flow, not merely this period's, and with no upper bound at all: a re-solve
        // after a modification discounts the whole remaining vector, so bounding it at the period end
        // would solve the rate against one month of a twenty-year contract. The bound is left off
        // rather than set to the maturity date, because ContractTerms.maturityDate() needs
        // period-anniversary arithmetic that WEEKLY and FORTNIGHTLY schedules do not have — and a
        // weekly-collection loan must not be quarantined on its way past this line.
        FlowVector revised = FlowVectorReader.readRemaining(
            connection, scheduleId, currency, eventDate);
        if (revised.future().isEmpty()) {
            throw new PersistenceFailure(
                "lifecycle event on " + eventDate + " for contract " + contractId + " is routed to"
                    + " a mechanism but the visible schedule carries no flow after the event date."
                    + " ADR-0006 routes on the driver tag, and a routed event with no revised vector"
                    + " cannot be routed at all; returning a period with no event instead would"
                    + " leave the contract amortising against the superseded schedule with nothing"
                    + " looking for the missing event");
        }
        return conclusionText == null
            ? PeriodEvent.of(driver, eventDate, revised)
            : PeriodEvent.assessed(driver, eventDate, revised, conclusion(conclusionText));
    }

    /**
     * The convention the rate in force was solved under.
     *
     * <p>Read from {@code eir_computation.convention} rather than chosen here, because the two
     * conventions "agree only where the preconditions hold" and the amortisation must use the one the
     * rate was struck under. Supplying {@code PERIODIC_INDEX} for a rate solved on actual dates would
     * reinterpret an annual effective rate as a per-period one — an error of a factor of roughly
     * twelve on a monthly contract, and one that produces a clean-looking schedule.
     *
     * <p>Where no solve is visible the contract has never been solved and this run must onboard it
     * ({@code OpeningState.hasBeenSolved()} is how the pipeline asks).
     * {@link TimeConvention.ActualDate} is used then, because {@code TimeConvention}'s own javadoc
     * makes it "required for correctness everywhere else, and the default" — a convention that is
     * safe when unverified, as against {@code PeriodicIndex}, which is valid "only when periods are
     * uniform and every flow sits on a boundary".
     */
    private TimeConvention convention(Connection connection, String contractId,
        AsAtBoundary boundary, ContractTermsReader.Row terms) throws SQLException {

        // One reader for the whole solve, shared with JdbcContractStateSource. This method used to
        // run its own copy of the "which solve is in force" query -- byte-identical to that class's
        // SELECT_RATE_IN_FORCE -- and return only the convention, while the other returned only the
        // rate and wrapped it at the schedule's periodicity. See SolvedRateReader's javadoc for
        // what that pairing did to an ACTUAL_DATE solve.
        SolvedRateReader.Solve solve = SolvedRateReader.inForce(
            connection, contractId, boundary.recordedAsAt(), boundary.businessAsOf(),
            terms.periodsPerYear(), terms.terms().dayCount());
        return solve == null
            ? new TimeConvention.ActualDate(terms.terms().dayCount())
            : solve.convention();
    }

    /**
     * 1-based ordinal of this period in the contract's own schedule.
     *
     * <p>Periods closed by the business date, plus one. {@link PeriodId#elapsedPeriods} counts the
     * contract's due dates rather than subtracting months, which is what makes the ordinal right for
     * a contract whose instalments do not fall on the 1st — see its javadoc for what an off-by-one
     * here does to invariant ST-2.
     */
    private static int periodOrdinal(ContractTermsReader.Row terms, LocalDate businessAsOf) {
        long elapsed = PeriodId.elapsedPeriods(
            terms.terms().firstDueDate(),
            businessAsOf,
            CompoundingBasis.stepOf(terms.compoundingBasis()));
        return (int) (elapsed + 1);
    }

    private static RateDriver driver(String value) {
        try {
            return RateDriver.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PersistenceFailure(
                "lifecycle event driver '" + value + "' is not a RateDriver. V2's"
                    + " ck_lifecycle_event_driver admits eight values taken from the enum, and"
                    + " ADR-0006 routes on this tag: an unrecognised driver would have to fall back"
                    + " to routing on the observation that a rate moved, which FR-507 forbids");
        }
    }

    private static ModificationConclusion conclusion(String value) {
        try {
            return ModificationConclusion.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PersistenceFailure(
                "substantiality conclusion '" + value + "' is not a ModificationConclusion. FR-511"
                    + " makes the conclusion a human decision with a named owner; a value the engine"
                    + " cannot read would either be dropped — making the engine appear to have"
                    + " decided — or guessed, which is worse");
        }
    }

    private record PeriodDates(LocalDate start, LocalDate end) {
    }

    private record CashSplit(Money principal, Money interest) {
    }

    private record SuspenseMovement(Money recovered, Money writtenOff) {
    }
}
