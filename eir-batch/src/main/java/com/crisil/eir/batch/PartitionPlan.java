package com.crisil.eir.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How a run's population is divided into partitions, and the proof that the division lost nothing
 * (ADR-0007).
 *
 * <h2>The one thing this class exists to guarantee</h2>
 *
 * <p><b>Every contract in the population is in exactly one slice.</b> Not "roughly" and not "as far
 * as the grouping code goes" — the plan is refused unless the slices' contract ids, unioned, are the
 * population, with no duplicate and no omission. That guarantee is upstream of everything the rest of
 * this module says about accounting: {@code RunAggregate.unaccountedFor()} subtracts results from a
 * population, and if a contract was never in any partition it was never computed, never quarantined,
 * and the run's own report is the last place it could have been noticed. A partitioner that drops
 * contracts produces a run over 9,999,998 of 10,000,000 that reconciles perfectly, because the two
 * it dropped are absent from both sides of every total.
 *
 * <p><b>The specific input that makes this fail,</b> and the reason the check is not academic: the
 * shard split. Splitting a grain of {@code n} contracts into shards of at most {@code m} needs
 * {@code ceil(n/m)} shards, and the arithmetic that is written by reflex is {@code n / m} — which
 * for {@code n = 25, m = 10} gives 2 shards holding 20 contracts and silently discards 5. The
 * off-by-one is invisible in every downstream figure. {@code PartitionPlanTest} constructs exactly
 * that population, and the assertion is on the union, not on the shard count.
 *
 * <h2>Determinism</h2>
 *
 * <p>The plan is a pure function of the population list and the grain source's answers. Grains are
 * ordered by first appearance in the population and shards by ordinal, so two constructions over the
 * same population produce the same slices with the same names in the same order. That is what makes
 * a restart possible at all: Spring Batch matches a partition's completion record by name, so a plan
 * whose names moved between two executions of one run would re-run every completed partition — see
 * {@link PartitionKey#name()}.
 *
 * <p>{@link #fingerprint()} is the stronger version of the same claim, for the case the plan cannot
 * defend against on its own: a population that <em>changed</em> between the failed run and its
 * restart. See its javadoc.
 */
public final class PartitionPlan {

    private final List<String> population;
    private final List<PopulationSlice> slices;
    private final int maxShardSize;

    private PartitionPlan(List<String> population, List<PopulationSlice> slices, int maxShardSize) {
        this.population = population;
        this.slices = slices;
        this.maxShardSize = maxShardSize;
    }

    /**
     * Plans a run.
     *
     * @param population   every contract id the run must account for, in run order, as
     *                     {@code ContractSource.contractIdsInScope} returned it
     * @param grains       where each contract's {@code product × entity} grain comes from
     * @param maxShardSize the largest number of contracts one partition may hold — the skew control
     *                     of ADR-0007 and, because a slice is written to the progress store as one
     *                     unit, the run's restart granularity
     * @throws IllegalArgumentException where the population holds a duplicate, where the grain
     *                                  source's answer does not line up with the population, or
     *                                  where the resulting slices do not reconstitute the
     *                                  population exactly
     */
    public static PartitionPlan over(
        List<String> population, PartitionGrainSource grains, int maxShardSize) {
        Objects.requireNonNull(population, "population");
        Objects.requireNonNull(grains, "grains");
        if (maxShardSize < 1) {
            throw new IllegalArgumentException(
                "maxShardSize must be at least 1, got " + maxShardSize);
        }
        List<String> ordered = List.copyOf(population);

        // Duplicates first, in a pass of their own, before any grain is looked up — the same order
        // FailureIsolation.runBatch uses and for the same reason. A duplicate contract id would put
        // one contract in two slices, both would compute it, and RunAggregate would then report
        // "contract X has more than one result" from a defect three layers upstream of where the
        // reader is looking.
        Set<String> unique = new LinkedHashSet<>(ordered);
        if (unique.size() != ordered.size()) {
            throw new IllegalArgumentException(
                "the population holds " + (ordered.size() - unique.size()) + " duplicate contract"
                    + " id(s) among " + ordered.size() + " entries; a duplicate would be computed"
                    + " by two partitions and one result would overwrite the other by whichever"
                    + " partition finished second");
        }

        List<PartitionKey> assigned = grains.grainsOf(ordered);
        Objects.requireNonNull(assigned, "grainsOf returned null");
        if (assigned.size() != ordered.size()) {
            // Refused, never truncated to the shorter of the two. A grain source that answered for
            // 9,999,998 of 10,000,000 contracts, zipped positionally against the population, would
            // leave the last two contracts in no partition at all — and every total in the run
            // would tie without them.
            throw new IllegalArgumentException(
                "the grain source answered for " + assigned.size() + " contract(s) and the"
                    + " population holds " + ordered.size() + "; positional alignment is how a"
                    + " contract is matched to its partition, so a short answer would leave"
                    + " contracts in no partition and every total would tie without them");
        }

        Map<PartitionKey, List<String>> byGrain = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            PartitionKey grain = assigned.get(i);
            Objects.requireNonNull(grain, "grain for contract " + ordered.get(i));
            if (!grain.isGrain()) {
                // See PartitionKey.grain(): the shard is the run's commit granularity and belongs
                // to the plan. A grain source that set it would be choosing the restart boundary
                // from inside a product-master lookup, and two sources disagreeing about it would
                // produce two partitions with the same name and different contents.
                throw new IllegalArgumentException(
                    "the grain source returned shard " + grain.shard() + " for contract "
                        + ordered.get(i) + "; a grain source names the product and the entity, and"
                        + " the plan assigns the shard — a shard chosen there is the run's commit"
                        + " granularity chosen by a data lookup");
            }
            byGrain.computeIfAbsent(grain, key -> new ArrayList<>()).add(ordered.get(i));
        }

        List<PopulationSlice> planned = new ArrayList<>();
        for (Map.Entry<PartitionKey, List<String>> entry : byGrain.entrySet()) {
            List<String> contracts = entry.getValue();
            // Ceiling division, spelled out. This is the off-by-one named in the class javadoc:
            // `contracts.size() / maxShardSize` silently discards the final partial shard.
            int shards = (contracts.size() + maxShardSize - 1) / maxShardSize;
            for (int shard = 0; shard < shards; shard++) {
                int from = shard * maxShardSize;
                int to = Math.min(contracts.size(), from + maxShardSize);
                planned.add(new PopulationSlice(
                    new PartitionKey(entry.getKey().product(), entry.getKey().entity(), shard),
                    contracts.subList(from, to)));
            }
        }

        PartitionPlan plan = new PartitionPlan(ordered, List.copyOf(planned), maxShardSize);
        plan.refuseUnlessTheDivisionLostNothing();
        return plan;
    }

    /**
     * The check the whole class is for. Run once, at construction, over the plan's own output.
     *
     * <p>Deliberately <em>not</em> a comparison of counts alone. A plan that dropped one contract
     * and duplicated another has the right total, and the population it would run is not the
     * population it was given — so the identities are compared, not the cardinality.
     */
    private void refuseUnlessTheDivisionLostNothing() {
        Set<String> covered = new LinkedHashSet<>();
        List<String> twice = new ArrayList<>();
        for (PopulationSlice slice : slices) {
            for (String contractId : slice.contractIds()) {
                if (!covered.add(contractId)) {
                    twice.add(contractId);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String contractId : population) {
            if (!covered.contains(contractId)) {
                missing.add(contractId);
            }
        }
        List<String> strangers = new ArrayList<>();
        Set<String> inPopulation = new LinkedHashSet<>(population);
        for (String contractId : covered) {
            if (!inPopulation.contains(contractId)) {
                strangers.add(contractId);
            }
        }
        if (missing.isEmpty() && twice.isEmpty() && strangers.isEmpty()) {
            return;
        }
        // Thrown rather than reported, and this is the one place in the module where that is the
        // right split. RunAggregate reports a short population because it must be able to
        // represent one — a run really can come up short and the close has to see by how much.
        // A plan is not a measurement of anything; it is arithmetic over a list this method has in
        // its hand, and a wrong answer here is a defect in this file. Letting a broken plan run
        // would spend the whole close window producing figures over the wrong population.
        throw new IllegalArgumentException(
            "the partition plan does not reconstitute its population: " + missing.size()
                + " contract(s) in no partition " + name(missing) + ", " + twice.size()
                + " in more than one " + name(twice) + ", " + strangers.size()
                + " in a partition and not in the population " + name(strangers)
                + "; a contract in no partition is never computed and never quarantined, and every"
                + " total in the run ties without it");
    }

    /** The population the plan was built over, in run order. */
    public List<String> population() {
        return population;
    }

    /** The partitions, grain by grain in first-appearance order and shard by shard within each. */
    public List<PopulationSlice> slices() {
        return slices;
    }

    /** The slice a partition name identifies, or empty where this plan has no such partition. */
    public Optional<PopulationSlice> slice(String partitionName) {
        Objects.requireNonNull(partitionName, "partitionName");
        for (PopulationSlice slice : slices) {
            if (slice.key().name().equals(partitionName)) {
                return Optional.of(slice);
            }
        }
        return Optional.empty();
    }

    /** How many contracts the run must account for. */
    public int populationSize() {
        return population.size();
    }

    /** The largest partition, in contracts — the skew figure ADR-0007 says must be managed. */
    public int largestSlice() {
        int largest = 0;
        for (PopulationSlice slice : slices) {
            largest = Math.max(largest, slice.size());
        }
        return largest;
    }

    /**
     * A fingerprint of the plan, for the defect a deterministic plan cannot defend against on its
     * own: a population that <b>changed</b> between a failed run and its restart.
     *
     * <p>The scenario is ordinary rather than exotic. A close run dies at 02:00 on the 1st; by the
     * time an operator restarts it at 06:00 the onboarding feed has landed and
     * {@code ContractSource.contractIdsInScope} answers with four hundred contracts that were not in
     * the run when it started. Every partition Spring Batch skips as complete belongs to the old
     * plan; every partition it runs belongs to the new one; the aggregate step then subtracts the
     * union of both from the new population and reports a clean, reconciled run over a population
     * that never existed as a single set. Nothing downstream can see it, because both halves are
     * internally consistent.
     *
     * <p>So the plan's identity is stamped into the job's execution context on the first execution
     * and compared on every subsequent one ({@link AmortisationBatchJob}).
     *
     * <h4>What is in the stamp, and why each part had to be</h4>
     *
     * <p>The population size, the shard size, and per partition its name, its size <em>and a
     * checksum of its membership</em>. The first three are the readable part: "48 partitions became
     * 52" is a sentence an operator can act on, which a bare digest of the whole population is not.
     * The per-partition checksum is there because the readable part alone is <b>blind to a swap</b>,
     * and a swap is the ordinary overnight case rather than an exotic one:
     *
     * <pre>
     *   run dies 02:00 --- one contract closes, one is onboarded, in the SAME grain ---&gt; restart
     * </pre>
     *
     * <p>Every count survives that: same population size, same grains, same shard names, same shard
     * sizes. With names and sizes only, the two plans stamp identically, {@code verify-plan} passes,
     * and the restart skips the completed shard that <em>used to</em> contain the departed contract —
     * so the newly onboarded one is never computed by anybody, while the departed one's stale result
     * stands in for it. The population then nets: one contract missing, one result extraneous, and
     * any check on the difference of the two totals comes out at nil. That is the
     * "processed 9,999,998" shortfall in its most invisible form, and it is why the membership is in
     * here and why {@link AmortisationBatchJob} gates on identities rather than on a net.
     *
     * <p>A CRC32 of the ids rather than a cryptographic digest: this is a corruption check between
     * two executions of one run, not a defence against a forged plan, and the failure message keeps
     * the partition name so the digest never has to be the actionable part.
     *
     * <p><b>The boundary is deliberately not here.</b> A plan is a statement about how the work is
     * divided, and the knowledge cut a run reads at is a property of the run —
     * {@link AmortisationBatchJob#runFingerprint} composes the two, so that a restart at a moved
     * {@code recordedAsAt} is refused without this class acquiring an opinion about system time.
     */
    public String fingerprint() {
        StringBuilder stamp = new StringBuilder()
            .append("population=").append(population.size())
            .append(";maxShardSize=").append(maxShardSize)
            .append(";partitions=").append(slices.size());
        for (PopulationSlice slice : slices) {
            stamp.append(';').append(slice.key().name())
                .append('=').append(slice.size())
                .append('@').append(membershipChecksum(slice));
        }
        return stamp.toString();
    }

    /**
     * A checksum of one partition's membership, in order.
     *
     * <p>Order-sensitive on purpose. The slice's contract order is the order
     * {@code FailureIsolation.runBatch} computes in and therefore the order the results come back in,
     * so two plans that assign one partition the same contracts in a different order are not the
     * same plan for FR-903's purposes.
     *
     * <p>The separator is fed into the checksum along with the ids, so that {@code ["AB", "C"]} and
     * {@code ["A", "BC"]} cannot collide.
     */
    private static String membershipChecksum(PopulationSlice slice) {
        java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
        for (String contractId : slice.contractIds()) {
            checksum.update(contractId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            checksum.update('\n');
        }
        return Long.toHexString(checksum.getValue());
    }

    /** A one-line description for a run report. */
    public String describe() {
        return slices.size() + " partition(s) over " + population.size() + " contract(s), largest "
            + largestSlice() + ", shard size " + maxShardSize;
    }

    /**
     * At most five names, then a count — {@code RunAggregate.name}'s convention, and the same
     * reason: a message naming ten million contracts is a message nobody reads, and one naming none
     * is a message nobody can act on.
     */
    private static String name(List<String> ids) {
        if (ids.isEmpty()) {
            return "()";
        }
        if (ids.size() <= 5) {
            return "(" + String.join(", ", ids) + ")";
        }
        return "(" + String.join(", ", ids.subList(0, 5)) + " and " + (ids.size() - 5) + " more)";
    }
}
