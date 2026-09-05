package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * One contract's opening position, as at the boundary — five reads, five temporal predicates.
 *
 * <h2>The Optional is the port's contract and it is honoured literally</h2>
 *
 * <p>{@code openingState} returns {@link Optional#empty()} and never throws for a data condition,
 * because "a contract in the population that has no state as at the boundary is a data condition the
 * run reports per contract — FR-905 — and not a reason to abandon a ten-million-contract close".
 * Five distinct absences produce empty here, and each is a real condition rather than a defensive
 * default:
 *
 * <ol>
 *   <li><b>No visible contract version.</b> The population named the contract and the terms as at
 *       this boundary are not there.
 *   <li><b>No schedule anchor</b> (V3). {@code disbursement_date}, {@code first_due_date} and
 *       {@code term_periods} are joined inside {@link ContractTermsReader}, and V3's header explains
 *       why they are refused rather than derived.
 *   <li><b>No prior period balance.</b> See below — this is the initial-recognition case.
 *   <li><b>No stage assignment for the period.</b> 04 § 2.9 makes {@code ecl_engine_version} NOT
 *       NULL because "re-running a period with today's ECL output produces a different answer and
 *       invariant DT-1 fails", and {@code OpeningState} refuses to be built without it. A contract
 *       the ECL engine has not spoken about for the period cannot be measured; it can only be
 *       reported.
 *   <li><b>No CBS billed line for the period.</b> RC-1 compares the projected contractual leg
 *       against what the CBS says it billed, so a contract with no CBS line has one side of that
 *       reconciliation missing. Substituting zero would make RC-1 pass on a contract nobody billed.
 * </ol>
 *
 * <h2>Opening balances are the prior period's closing balances, from a completed run</h2>
 *
 * <p>{@code period_balance} carries {@code opening_gca} as well as {@code closing_gca}, and reading
 * the boundary period's own row would be simpler and wrong: that row is this run's output, so the
 * opening position would be derived from the figure it is supposed to produce. So the read is the
 * <em>preceding</em> period's closing pair, and {@link PeriodId#previous} is why January reaches the
 * previous December rather than a period id ending in 00.
 *
 * <p>The run is joined and filtered, which is the second temporal axis on this leg:
 * {@code status = 'COMPLETED'} and {@code completed_at <= recordedAsAt}. A re-run of the prior period
 * that completed <em>after</em> this boundary is invisible here, so a replay of period P consumes the
 * closing balances period P&nbsp;−&nbsp;1 had when P was originally computed — not the restated ones.
 * That is 05 § 3.3's requirement applied to the balance leg, and without the {@code completed_at}
 * bound a prior-period correction would silently change every replay downstream of it.
 *
 * <p>A contract with no such row has no opening position on this book. That is the initial
 * recognition case — the contract must go through {@code InitialRecognition} before it can roll
 * forward — and it is reported per contract rather than defaulted to zero: a zero opening gross
 * carrying amount on a live loan reconciles to nothing while looking exactly like a repaid one.
 *
 * <h2>The rate is read through the supersession chain, not through {@code superseded_by IS NULL}</h2>
 *
 * <p>{@code eir_computation} has no {@code superseded_at}; V1 gives it a forward pointer instead, and
 * "never updated in place" only supports replay if the chain of beliefs can be ordered — which is
 * what {@code recorded_at} is for. So the visible solve as at an instant is one that was recorded by
 * then and <em>whose superseding row was not</em>. Filtering on {@code superseded_by IS NULL} alone
 * would return today's belief at every boundary, which is exactly the in-memory behaviour this module
 * exists to replace. The subquery is the difference between a replay that reproduces and a replay
 * that proves nothing.
 *
 * <p>{@code status = 'SOLVED'} because V1's {@code eir_computation_no_solution_has_no_rate_ck} makes
 * a {@code NO_SOLUTION} row carry no rate at all — FR-402 forbids the fallback to the contractual
 * rate — and a {@code REQUIRES_REVIEW} rate has not been accepted. Where no solve is visible the
 * state is still returned, with {@code eir} null: the port documents that as "the contract has never
 * been solved and this run must onboard it", and {@code OpeningState.hasBeenSolved()} is how the
 * pipeline asks.
 */
public final class JdbcContractStateSource extends JdbcAdapter implements ContractStateSource {

    /** Binds: contract id, prior period id, book id, {@code recordedAsAt}. */
    static final String SELECT_PRIOR_CLOSING = """
        SELECT pb.closing_gca,
               pb.closing_contractual
          FROM period_balance pb
          JOIN amortisation_run r
            ON r.run_id = pb.run_id
           AND r.period_id = pb.period_id
         WHERE pb.contract_id = ?
           AND pb.period_id = ?
           AND pb.book_id = ?
           AND r.status = 'COMPLETED'
           AND r.completed_at <= ?
         ORDER BY r.completed_at DESC, r.run_id DESC
         LIMIT 1
        """;

    /** Binds: contract id, period id, {@code recordedAsAt}. */
    static final String SELECT_STAGE = """
        SELECT sa.stage,
               sa.allowance,
               sa.ecl_engine_version
          FROM stage_assignment sa
         WHERE sa.contract_id = ?
           AND sa.period_id = ?
           AND %s
        """.formatted(TemporalReads.recordedNoLaterThan("sa", "received_at"));
    /** Binds: contract id, period id, {@link Params#systemTime}. */
    static final String SELECT_BILLED = """
        SELECT f.billed_interest
          FROM cbs_billed_interest f
         WHERE f.contract_id = ?
           AND f.period_id = ?
           AND %s
        """.formatted(TemporalReads.systemTime("f"));

    public JdbcContractStateSource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcContractStateSource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(boundary, "boundary");

        int periodId = PeriodId.of(boundary.businessAsOf());
        int priorPeriodId = PeriodId.previous(periodId);

        try (Connection connection = open()) {
            ContractTermsReader.Row terms = readTerms(connection, contractId, boundary);
            if (terms == null) {
                return Optional.empty();
            }
            Currency currency = terms.currency();

            ClosingPair opening = readPriorClosing(connection, contractId, priorPeriodId,
                boundary, currency);
            if (opening == null) {
                return Optional.empty();
            }

            StageRow staging = readStage(connection, contractId, periodId, boundary, currency);
            if (staging == null) {
                return Optional.empty();
            }

            Money billed = readBilled(connection, contractId, periodId, boundary, currency);
            if (billed == null) {
                return Optional.empty();
            }

            // The rate AND the convention it was struck under, from ONE row of ONE solve.
            // These used to be read by two classes from two identical queries, and the rate was
            // wrapped at the schedule's periodicity rather than the convention's -- so an
            // ACTUAL_DATE solve on a monthly contract met AmortisationEngine's first guard as
            // "rate compounds 12 times a year but convention ACTUAL_DATE(ACT/365F) implies 1"
            // and aborted the whole run on an IllegalArgumentException FR-905 does not catch.
            // SolvedRateReader's javadoc carries the argument.
            SolvedRateReader.Solve solve = SolvedRateReader.inForce(
                connection, contractId, boundary.recordedAsAt(), boundary.businessAsOf(),
                terms.periodsPerYear(), terms.terms().dayCount());
            Rate eir = solve == null ? null : solve.rate();

            return Optional.of(new OpeningState(
                terms.terms(),
                eir,
                opening.gca(),
                opening.contractual(),
                staging.stage(),
                staging.allowance(),
                staging.eclEngineVersion(),
                billed));

        } catch (ContractDataCondition e) {
            // A row that is present and cannot be interpreted — an unmapped projection strategy, an
            // unrecognised day count, a stage outside 1..3. FR-905's isolation covers this as much as
            // it covers an absent row, and the class javadoc's promise ("never throws for a data
            // condition") would otherwise have been true only of absence. See ContractDataCondition
            // for why it is a distinct type: a dropped connection must NOT come out as an empty
            // Optional, or an outage reads as ten million quarantined contracts.
            return Optional.empty();
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read opening state for contract " + contractId + " as at "
                    + boundary.recordedAsAt() + " for period end " + boundary.businessAsOf(), e);
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

    /** The two closing balances, or null where no completed prior run is visible. */
    private ClosingPair readPriorClosing(Connection connection, String contractId,
        int priorPeriodId, AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_PRIOR_CLOSING)) {
            new Params(statement)
                .contractId(contractId)
                .integer(priorPeriodId)
                .text(bookId())
                .instant(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ClosingPair(
                    Rows.money(rs, "closing_gca", currency),
                    Rows.money(rs, "closing_contractual", currency));
            }
        }
    }

    private StageRow readStage(Connection connection, String contractId, int periodId,
        AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_STAGE)) {
            new Params(statement)
                .contractId(contractId)
                .integer(periodId)
                .instant(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new StageRow(
                    stage(Rows.integer(rs, "stage")),
                    Rows.money(rs, "allowance", currency),
                    Rows.text(rs, "ecl_engine_version"));
            }
        }
    }

    private Money readBilled(Connection connection, String contractId, int periodId,
        AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(SELECT_BILLED)) {
            new Params(statement)
                .contractId(contractId)
                .integer(periodId)
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Rows.money(rs, "billed_interest", currency) : null;
            }
        }
    }

    /**
     * {@code stage_assignment.stage} to {@link Stage}.
     *
     * <p>V2 constrains the column to 1, 2 or 3 and the enum spells the same three
     * {@code STAGE_1..STAGE_3}. Refused rather than defaulted for an out-of-range value, because the
     * conservative-looking default is Stage 3 and the convenient one is Stage 1: the first suppresses
     * income recognition on a performing contract, the second recognises income on an impaired one
     * in breach of invariant S3-2. Neither is a safe guess, so there is no guess.
     */
    private static Stage stage(int value) {
        return switch (value) {
            case 1 -> Stage.STAGE_1;
            case 2 -> Stage.STAGE_2;
            case 3 -> Stage.STAGE_3;
            default -> throw new ContractDataCondition(
                "stage " + value + " is outside 1..3, which V2's ck_stage_assignment_stage refuses."
                    + " There is no defensible default: Stage 1 would recognise income on an"
                    + " impaired contract against invariant S3-2, and Stage 3 would suppress it on"
                    + " a performing one");
        };
    }

    private record ClosingPair(Money gca, Money contractual) {
    }

    private record StageRow(Stage stage, Money allowance, String eclEngineVersion) {
    }
}
