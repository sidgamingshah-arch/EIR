package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * What the core banking system says it billed, per contract, for the boundary's period.
 *
 * <h2>Read from a landing table, and that is what keeps RC-1 a control</h2>
 *
 * <p>ADR-0004 makes the CBS the book of record for billing, and this port's own javadoc names the
 * trap it exists to avoid: if the run took the engine's projected contractual leg and the CBS's
 * billed figure "from one port, the comparison would be a field against itself — the 'derived value
 * compared against the thing it was derived from' trap that this programme has now found several
 * times".
 *
 * <p>The obvious shortcut in a database-backed adapter is {@code period_balance.contractual_interest}
 * — it exists, it is the right shape, and it is the engine's own output. Reading it here would make
 * RC-1 compare the engine's number against the engine's number, on every contract, for ever. So
 * {@code cbs_billed_interest} (V3) is a separate table fed by the CBS extract, and V3's header states
 * why the engine's schema does not carry it: 08's scope table puts the CBS outside this engine.
 *
 * <h2>Scoped to the period and to the recorded boundary, not to the engine's output</h2>
 *
 * <p>Every visible line for the period is returned, including for contracts the engine did not
 * compute. That is deliberate and it is what lets RC-1 catch a dropped contract at all: a feed scoped
 * to the engine's own population makes a shortfall invisible to every reconciliation, because the
 * missing contract is absent from both sides of every total.
 *
 * <p>The decision-time predicate matters as much here as on the terms. A CBS restatement arrives as a
 * new row with the old one's {@code superseded_at} closed, so a replay as at the original run's
 * instant reads the extract that run consumed and a live run reads the restated one. Without it, a
 * restated CBS extract would make every historical replay disagree with its published figures and
 * DT-1 would fire with nothing to point at.
 *
 * <h2>Currency is joined from the contract, not assumed</h2>
 *
 * <p>{@code CbsBilledInterest} carries a {@link com.crisil.eir.domain.Money}, and 04 § 2.1 puts the
 * currency on the contract because it "drives presentation scale". Defaulting to INR would put a
 * foreign-currency contract's billed interest on the wrong presentation scale and give RC-1 two
 * figures in different units that compare numerically.
 */
public final class JdbcCoreBankingFeed extends JdbcAdapter implements CoreBankingFeed {

    /** Binds: period id, then {@link Params#systemTime}. */
    static final String SELECT_BILLED_FOR_PERIOD = """
        SELECT f.contract_id,
               f.period_id,
               f.billed_interest,
               f.feed_reference,
               c.currency
          FROM cbs_billed_interest f
          JOIN contract c
            ON c.contract_id = f.contract_id
         WHERE f.period_id = ?
           AND %s
         ORDER BY f.contract_id
        """.formatted(TemporalReads.systemTime("f"));

    public JdbcCoreBankingFeed(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcCoreBankingFeed(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public List<CbsBilledInterest> billedInterest(AsAtBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        int periodId = PeriodId.of(boundary.businessAsOf());

        List<CbsBilledInterest> lines = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement =
                 connection.prepareStatement(SELECT_BILLED_FOR_PERIOD)) {

            new Params(statement)
                .integer(periodId)
                .systemTime(boundary.recordedAsAt());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Currency currency = Rows.currency(rs, "currency");
                    lines.add(new CbsBilledInterest(
                        Rows.text(rs, "contract_id"),
                        Rows.integer(rs, "period_id"),
                        Rows.money(rs, "billed_interest", currency),
                        Rows.text(rs, "feed_reference")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read the CBS billed-interest feed for period " + periodId + " as at "
                    + boundary.recordedAsAt(), e);
        }
        return List.copyOf(lines);
    }
}
