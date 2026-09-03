package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.batch.PartitionGrainSource;
import com.crisil.eir.batch.PartitionKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The {@code product × entity} grain ADR-0007 partitions on, read out of {@code contract}.
 *
 * <p><b>Why the grain is read rather than derived from the contract id.</b> {@code PartitionKey}'s
 * own javadoc gives the reason a partition is that pair: a policy version and a fee rule set are
 * resolved per {@code (product, entity, effective_date)}, so a partition whose contracts share the
 * pair shares one resolution, and a partition that mixed them would resolve the rule set per
 * contract. The pair is a fact about the contract recorded in {@code contract.product_id} and
 * {@code contract.entity_id}; anything inferred from the id instead would be a second source for it.
 *
 * <p><b>One query for the whole population, not one per contract.</b> A run of ten million contracts
 * asking the database for one grain each is ten million round trips before any arithmetic happens.
 * The ids arrive as a single {@code ANY(?)} array and come back keyed, then are re-ordered to the
 * caller's own ordering — {@link PartitionGrainSource} requires positional alignment, and the order
 * of a {@code SELECT} without a total {@code ORDER BY} is not the order of the argument.
 *
 * <p><b>A contract the query does not answer for is a refusal, not a skipped contract.</b>
 * {@code PartitionPlan.over} refuses a short answer for the reason its javadoc states — "a contract
 * with no partition is a contract in the population that no worker runs, and that is the 'processed
 * 9,999,998' shortfall arriving before any arithmetic has happened". This class therefore fails loudly
 * rather than returning fewer grains than it was asked for, and it names the contracts it could not
 * place. It cannot happen through {@link JdbcContractSource}, whose population comes from the same
 * table; it can happen if the two are pointed at different boundaries, which is exactly the
 * misconfiguration worth a message.
 *
 * <p><b>The book.</b> Scoped to the adapter's book, unlike {@code JdbcContractSource.SELECT_POPULATION}
 * — which holds a book id and does not use it, a defect recorded in {@code docs/08}. Adding the
 * predicate here does not fix that one: an unscoped population still enumerates another book's
 * contracts, and this class will then refuse to place them, which turns a silent cross-book run into
 * a named failure. That is the better failure of the two and it is not the fix.
 */
public final class JdbcPartitionGrains extends JdbcAdapter implements PartitionGrainSource {

    static final String SELECT_GRAINS = """
        SELECT c.contract_id,
               c.product_id,
               c.entity_id
          FROM contract c
         WHERE c.contract_id = ANY (?)
           AND c.book_id = ?
        """;

    public JdbcPartitionGrains(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public List<PartitionKey> grainsOf(List<String> contractIds) {
        List<String> ids = List.copyOf(contractIds);
        if (ids.isEmpty()) {
            // An empty population is a real answer and not an error here. It is the RUN's business
            // to refuse a population of nothing -- RunAggregate.computedCount() == 0 and
            // OnboardingRun.assertedNothing() both do -- and a refusal here would move that
            // decision into the partitioner, where nothing downstream could report it as the
            // aggregation-over-nothing case it is.
            return List.of();
        }
        Map<String, PartitionKey> byContract = new LinkedHashMap<>(ids.size() * 2);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(SELECT_GRAINS)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            statement.setString(2, bookId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byContract.put(
                        rs.getString("contract_id"),
                        PartitionKey.grain(rs.getString("product_id"), rs.getString("entity_id")));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read the partition grain for " + ids.size() + " contract(s) of book "
                    + bookId() + "; the run cannot be partitioned and must not proceed unpartitioned"
                    + " -- a single-partition fallback would silently serialise a ten-million"
                    + " contract close", e);
        }

        List<PartitionKey> grains = new ArrayList<>(ids.size());
        List<String> unplaced = new ArrayList<>();
        for (String id : ids) {
            PartitionKey grain = byContract.get(id);
            if (grain == null) {
                unplaced.add(id);
            } else {
                grains.add(grain);
            }
        }
        if (!unplaced.isEmpty()) {
            throw new PersistenceFailure(
                unplaced.size() + " of " + ids.size() + " contract(s) in the population have no"
                    + " product/entity grain on book " + bookId() + " and so cannot be assigned to a"
                    + " partition: " + truncate(unplaced) + ". FR-905 requires every contract in the"
                    + " population to be computed, excluded or quarantined, and a contract no worker"
                    + " runs is absent from both sides of every total -- so it reconciles perfectly."
                    + " The usual cause is a population read at a different book or boundary from"
                    + " this one");
        }
        return grains;
    }

    /** A bounded rendering, so a ten-million-contract mismatch does not produce a message that size. */
    private static String truncate(List<String> ids) {
        int limit = 10;
        if (ids.size() <= limit) {
            return ids.toString();
        }
        return ids.subList(0, limit) + " and " + (ids.size() - limit) + " more";
    }
}
