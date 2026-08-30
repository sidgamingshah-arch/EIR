package com.crisil.eir.batch;

import java.util.Objects;

/**
 * One partition of a run's population: a {@code product × entity} grain, and a shard within it
 * (ADR-0007).
 *
 * <p><b>Why {@code product × entity} and not a hash of the contract id.</b> ADR-0007: "Policy, rule
 * sets, tier assignment and floor categories are all keyed on product and entity, so a partition
 * shares its configuration and its cache. It also gives operational visibility in the units the
 * controller thinks in." A hash partition would spread one product's contracts across every
 * partition, so every partition would resolve every product's policy and no partition's cache would
 * hit. It would also make a partition a meaningless unit in a run report — "partition 7 failed" is
 * not a sentence a financial controller can act on, and "retail EMI, Mumbai, shard 2 of 4" is.
 *
 * <p><b>Why the shard exists.</b> Also ADR-0007, in Consequences: "Partition skew must be managed:
 * the largest retail products dominate. Handled by splitting the largest products into
 * sub-partitions." A single grain covering four million retail EMI loans would leave one worker
 * running for the whole close window while the others idled, so a grain larger than
 * {@code maxShardSize} is split and the shard ordinal names the piece. A run with no skew has one
 * shard per grain and the ordinal is always zero.
 *
 * <p><b>The shard is also the restart boundary,</b> which is the other reason its size is a tuning
 * parameter rather than an implementation detail — see {@link PartitionPlan} and
 * {@link AmortisationBatchJob}.
 *
 * <p><b>{@link #name()} is load-bearing and must be stable.</b> Spring Batch identifies a partition
 * by the name its {@code Partitioner} gave it, and a restart re-runs the partitions whose last
 * execution did not complete — matched <em>by that name</em>. A name derived from anything that
 * varies between two executions of the same run (an iteration index over a hash map, a timestamp, a
 * thread id) would make every partition look new on restart, so every completed partition would run
 * again and the run would recompute the whole population. Composed here from the grain and the
 * shard ordinal only, both of which {@link PartitionPlan} derives deterministically from the
 * population.
 *
 * @param product the product code, in whatever vocabulary the bank's product master uses
 * @param entity  the legal or reporting entity the contract belongs to
 * @param shard   0-based ordinal of this shard within the grain
 */
public record PartitionKey(String product, String entity, int shard) {

    /**
     * The separator inside a partition name.
     *
     * <p>A vertical bar rather than a colon, because Spring Batch composes its own step-execution
     * names with a colon and a name containing one is harder to read in a run report.
     */
    private static final String SEPARATOR = "|";

    public PartitionKey {
        product = require(product, "product");
        entity = require(entity, "entity");
        if (shard < 0) {
            throw new IllegalArgumentException(
                "shard ordinal must be non-negative, got " + shard + " for " + product + "/"
                    + entity);
        }
    }

    /**
     * The grain itself — the {@code product × entity} pair, before any skew splitting.
     *
     * <p>Shard zero, which is also what an unsharded grain is. {@link PartitionPlan} treats a grain
     * returned by a {@link PartitionGrainSource} as a claim about which product and entity a
     * contract belongs to and nothing more; assigning the shard is the plan's job, and a grain
     * source that returned a shard of its own choosing would be deciding the run's commit
     * granularity from inside a data lookup. {@link PartitionPlan#over} refuses one.
     */
    public static PartitionKey grain(String product, String entity) {
        return new PartitionKey(product, entity, 0);
    }

    /** Whether this key is a bare grain — shard zero, as a {@link PartitionGrainSource} returns. */
    public boolean isGrain() {
        return shard == 0;
    }

    /** This key's grain, discarding the shard. */
    public PartitionKey grain() {
        return shard == 0 ? this : new PartitionKey(product, entity, 0);
    }

    /**
     * The partition's stable name — what Spring Batch matches a restart against.
     *
     * <p>See the class javadoc: this string is an identity, not a label.
     */
    public String name() {
        return product + SEPARATOR + entity + SEPARATOR + "shard" + shard;
    }

    /** A one-line description for a run report, in the units a controller thinks in. */
    public String describe() {
        return "product " + product + ", entity " + entity + ", shard " + shard;
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            // A blank component would make two different grains produce the same partition name,
            // and a partition name is an identity a restart is matched on: two grains sharing a
            // name means a restart re-runs one of them under the other's completion record and
            // the second grain's contracts are never computed. That is the shortfall this whole
            // module is accounted against.
            throw new IllegalArgumentException(
                field + " must not be blank on a partition key; a blank component would let two"
                    + " grains share one partition name, and a restart matches completion by"
                    + " name");
        }
        if (stripped.contains(SEPARATOR)) {
            // Same argument. "RETAIL|EMI" x "MUM" and "RETAIL" x "EMI|MUM" would compose to one
            // name if the separator were permitted inside a component.
            throw new IllegalArgumentException(
                field + " '" + stripped + "' contains the partition-name separator '" + SEPARATOR
                    + "'; two different grains would compose to one partition name, which a"
                    + " restart cannot tell apart");
        }
        return stripped;
    }
}
