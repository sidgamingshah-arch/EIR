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
    /**
     * The population, scoped to the book this adapter was constructed for.
     *
     * <p><b>{@code c.book_id = ?} was missing, and its absence was confirmed against a live
     * cluster.</b> This class held a {@code bookId}, validated it, and never put it in the
     * predicate, so a run of book {@code MAIN} enumerated every book's contracts. FR-109 is the
     * reason that matters: the same facility is measured on the ACPIR basis, on IGAAP and on a tax
     * basis, and V1's {@code UNIQUE (entity_id, book_id, source_system_ref)} exists precisely so it
     * can appear once per book. Every balance read is scoped by book and the population was not.
     *
     * <p>The consequence was worse than the over-enumeration. A contract from another book has no
     * balance in this one, so FR-905 quarantined it with a message about a missing opening state —
     * and an operator reads "no state recorded" for a contract that is fully recorded in the book
     * it belongs to. The finding presents as a data-quality problem in the master, which is the
     * wrong desk and the wrong remedy.
     *
     * <p>{@code book_id} is on {@code contract} (V1 § CONTRACT) and indexed as
     * {@code contract_entity_book_ix (entity_id, book_id)}, so the predicate is served by an index
     * rather than filtering a whole-table scan.
     *
     * <p>Bound LAST, after both temporal pairs, because {@link Params} binds sequentially and the
     * two {@code %s} fragments each take two placeholders. Placing it after them in the SQL and
     * after them in the binding is what keeps those two facts checkable against each other by
     * reading down the page.
     */
    static final String SELECT_POPULATION = """
        SELECT DISTINCT c.contract_id
          FROM contract c
          JOIN contract_version cv
            ON cv.contract_id = c.contract_id
         WHERE c.measurement_category <> 'FVTPL'
           AND %s
           AND %s
           AND c.book_id = ?
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
                .systemTime(boundary.recordedAsAt())
                .text(bookId());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(Rows.text(rs, "contract_id"));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not enumerate the population of book " + bookId() + " as at "
                    + boundary.recordedAsAt() + " for period end " + boundary.businessAsOf(), e);
        }
        return List.copyOf(ids);
    }
}
