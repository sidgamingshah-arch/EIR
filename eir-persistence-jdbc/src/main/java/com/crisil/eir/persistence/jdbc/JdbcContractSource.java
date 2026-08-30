package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The run population, as at the boundary.
 *
 * <h2>The population is defined by CONTRACT_VERSION, not by CONTRACT</h2>
 *
 * <p>{@code contract} is immutable and carries no temporal columns — V1 says why: "there is no
 * history to keep of a fact that cannot change". So it cannot answer "was this contract in the
 * population as at that instant". {@code contract_version} can, and this query reaches the contract
 * through it: a contract is in scope where it has a version whose <em>business</em> time covers the
 * period end and whose <em>decision</em> time covers the boundary instant.
 *
 * <p><b>This is the property that makes a replay honest.</b> A contract onboarded after the original
 * run has its first {@code contract_version} recorded after the boundary, so it is invisible to a
 * replay as at that boundary and visible to a live run. {@code ContractSource}'s own javadoc names
 * the failure this prevents: "a contract onboarded since would appear in the replay and not in the
 * published figures". An in-memory map cannot express it, which is what ADR-0011 means by the
 * in-memory implementation not being a fair test of bitemporality.
 *
 * <h2>FVTPL is excluded here, at the ingestion boundary</h2>
 *
 * <p>FR-103: an instrument at fair value through profit or loss carries no EIR at all and must be
 * excluded from EIR processing entirely. V1 puts the exclusion here rather than in a constraint and
 * says so on the column: "the exclusion itself is an ingestion-boundary filter, because a cross-table
 * rule cannot be a row constraint". This port <em>is</em> that boundary — it is what defines the
 * population every later count is taken against.
 *
 * <p>The alternative, letting FVTPL contracts into the population and quarantining them per
 * contract, would be worse in a specific way: FR-905's isolation report would fill with contracts
 * that are not exceptions at all, and a genuine data condition would be one line among thousands of
 * correctly-excluded fair-value instruments. Note that {@link JdbcOnboardingSource} applies no such
 * filter, deliberately — {@code MeasurementGate} has to see the declared category to check it, and
 * FR-103's exclusion is from EIR processing rather than from the record.
 *
 * <h2>Ordered, because FR-903 requires the same run twice to be byte-identical</h2>
 *
 * <p>{@code ORDER BY c.contract_id}. Without it PostgreSQL is free to return rows in whatever order
 * a parallel sequential scan produced, and a partitioned run would process contracts in a different
 * order on each execution. Most of the arithmetic is order-independent; the run's own artefacts —
 * the exception sequence, the journal batch, the output digest of
 * {@code amortisation_run.output_digest} — are not.
 */
public final class JdbcContractSource extends JdbcAdapter implements ContractSource {

    /**
     * Binds: {@link Params#businessTime}, then {@link Params#systemTime}.
     *
     * <p>{@code DISTINCT} because a contract may have more than one version row visible on the
     * decision axis only if the business ranges differ — which the GiST exclusion constraint
     * {@code contract_version_no_overlap_ck} forbids for unsuperseded rows, but which superseded
     * rows can still produce at an earlier boundary. Two ids for one contract would make FR-905's
     * "every contract is either computed or quarantined" count wrong in the direction nobody checks.
     */
    static final String SELECT_POPULATION = """
        SELECT DISTINCT c.contract_id
          FROM contract c
          JOIN contract_version cv
            ON cv.contract_id = c.contract_id
         WHERE c.measurement_category <> 'FVTPL'
           AND %s
           AND %s
         ORDER BY c.contract_id
        """.formatted(TemporalReads.businessTime("cv"), TemporalReads.systemTime("cv"));

    public JdbcContractSource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcContractSource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public List<String> contractIdsInScope(AsAtBoundary boundary) {
        java.util.Objects.requireNonNull(boundary, "boundary");
        List<String> ids = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(SELECT_POPULATION)) {

            new Params(statement)
                .businessTime(boundary.businessAsOf())
                .systemTime(boundary.recordedAsAt());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(Rows.text(rs, "contract_id"));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not enumerate the population as at " + boundary.recordedAsAt()
                    + " for period end " + boundary.businessAsOf(), e);
        }
        return List.copyOf(ids);
    }
}
