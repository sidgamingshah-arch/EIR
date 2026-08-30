package com.crisil.eir.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The partition plan, and the one guarantee everything downstream rests on: every contract in the
 * population is in exactly one partition (ADR-0007, FR-905).
 *
 * <p>A contract in no partition is never computed and never quarantined. It is absent from both sides
 * of every total, so the run reconciles perfectly without it and nothing in the engine below this
 * class can notice. That makes the plan the earliest point at which the "processed 9,999,998 of
 * 10,000,000" shortfall can be introduced, and the earliest point at which it can be refused.
 */
@DisplayName("The partition plan (ADR-0007)")
class PartitionPlanTest {

    private static final PartitionGrainSource GRAINS = new BatchFixtures.PrefixGrains();

    @Nested
    @DisplayName("The shard split")
    class ShardSplit {

        @Test
        @DisplayName("a grain whose size is not a multiple of the shard size keeps its remainder")
        void aRemainderShardIsNotDiscarded() {
            // THE failing input. 25 contracts, shards of 10. `25 / 10` is 2, and a plan of two
            // shards holds 20 contracts and silently drops 5 — an off-by-one that is invisible in
            // every downstream figure, because the five have no results and no exception records
            // and appear nowhere. Ceiling division gives 3.
            List<String> population =
                BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 25);

            PartitionPlan plan = PartitionPlan.over(population, GRAINS, 10);

            assertThat(plan.slices()).as("ceil(25/10) = 3, not 25/10 = 2").hasSize(3);
            assertThat(plan.slices()).extracting(PopulationSlice::size)
                .as("10 + 10 + 5")
                .containsExactly(10, 10, 5);
            // The assertion that matters is the union, not the count: a plan with the right number
            // of shards and the wrong contents is the same defect.
            List<String> covered = new ArrayList<>();
            plan.slices().forEach(slice -> covered.addAll(slice.contractIds()));
            assertThat(covered)
                .as("the slices, unioned in order, are the population")
                .containsExactlyElementsOf(population);
        }

        @Test
        @DisplayName("a grain smaller than the shard size is one shard")
        void aSmallGrainIsOneShard() {
            List<String> population =
                BatchFixtures.contracts(BatchFixtures.CORP, BatchFixtures.DEL, 3);

            PartitionPlan plan = PartitionPlan.over(population, GRAINS, 10);

            assertThat(plan.slices()).hasSize(1);
            assertThat(plan.slices().getFirst().key().shard()).isZero();
            assertThat(plan.slices().getFirst().contractIds()).isEqualTo(population);
        }

        @Test
        @DisplayName("a grain that divides exactly produces no empty trailing shard")
        void anExactDivisionHasNoEmptyTail() {
            // The other half of the ceiling-division arithmetic: `(20 + 10 - 1) / 10` must be 2 and
            // not 3, because a third, empty shard would be a partition that completes instantly
            // having measured nothing and would inflate the partition count a run report is read
            // from. PopulationSlice refuses an empty slice outright, so this would throw.
            List<String> population =
                BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 20);

            PartitionPlan plan = PartitionPlan.over(population, GRAINS, 10);

            assertThat(plan.slices()).hasSize(2);
            assertThat(plan.largestSlice()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("a duplicate contract id in the population is refused before any grain lookup")
        void aDuplicateIsRefused() {
            String contractId = BatchFixtures.contractId(BatchFixtures.RETAIL, BatchFixtures.MUM, 1);

            assertThatThrownBy(() -> PartitionPlan.over(
                List.of(contractId, contractId), GRAINS, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate contract id")
                .hasMessageContaining("computed by two partitions");
        }

        @Test
        @DisplayName("a grain source that answers for fewer contracts than the population is"
            + " refused")
        void aShortGrainAnswerIsRefused() {
            List<String> population =
                BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 4);
            // The realistic shape of this defect: a grouped query joined to a contract master that
            // is missing rows. Zipped positionally against the population, the last contract lands
            // in no partition — and every total in the run ties without it.
            PartitionGrainSource short_ = contractIds ->
                GRAINS.grainsOf(contractIds.subList(0, contractIds.size() - 1));

            assertThatThrownBy(() -> PartitionPlan.over(population, short_, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answered for 3 contract(s)")
                .hasMessageContaining("every total would tie without them");
        }

        @Test
        @DisplayName("a grain source that assigns its own shard is refused")
        void aGrainSourceThatChoosesTheShardIsRefused() {
            List<String> population =
                BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 2);
            PartitionGrainSource sharding = contractIds -> contractIds.stream()
                .map(id -> new PartitionKey(BatchFixtures.RETAIL, BatchFixtures.MUM, 7))
                .toList();

            // The shard is the run's commit granularity and the restart boundary. A data lookup that
            // set it would be choosing both, and two sources disagreeing about it would produce two
            // partitions with one name and different contents — which a restart cannot tell apart.
            assertThatThrownBy(() -> PartitionPlan.over(population, sharding, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returned shard 7")
                .hasMessageContaining("commit granularity");
        }

        @Test
        @DisplayName("an empty population plans to no partitions rather than to one empty one")
        void anEmptyPopulationPlansToNothing() {
            PartitionPlan plan = PartitionPlan.over(List.of(), GRAINS, 10);

            // Not an error here: RunAggregate is what refuses to report a clean close over an empty
            // population, and it does so with a named blocking reason rather than an exception. The
            // plan's job is only to not invent a partition — "48 of 48 partitions completed" over a
            // population of nil reads exactly like a real close.
            assertThat(plan.slices()).isEmpty();
            assertThat(plan.populationSize()).isZero();
            assertThat(plan.largestSlice()).isZero();
        }
    }

    @Nested
    @DisplayName("Determinism and the fingerprint")
    class Determinism {

        @Test
        @DisplayName("two plans over the same population have the same partitions, in the same"
            + " order, with the same names")
        void thePlanIsAPureFunctionOfThePopulation() {
            List<String> population = BatchFixtures.standardPopulation();

            PartitionPlan first = PartitionPlan.over(population, GRAINS, 10);
            PartitionPlan second = PartitionPlan.over(population, GRAINS, 10);

            // Spring Batch matches a partition's completion record by NAME, so a plan whose names
            // moved between two executions of one run would re-run every completed partition and
            // recompute the whole population.
            assertThat(second.slices()).extracting(slice -> slice.key().name())
                .containsExactlyElementsOf(
                    first.slices().stream().map(slice -> slice.key().name()).toList());
            assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        }

        @Test
        @DisplayName("the fingerprint changes when the population grows inside an existing shard")
        void theFingerprintCatchesAGrowthThatMovesNoPartitionName() {
            // The case a name comparison alone would miss. Adding one contract to a grain that
            // already has a partial shard changes that shard's CONTENTS and no shard's NAME, so a
            // fingerprint made only of names would report the two plans identical — and a restart
            // would skip the "completed" shard whose contents had changed underneath it.
            List<String> before = BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 12);
            List<String> after = BatchFixtures.contracts(BatchFixtures.RETAIL, BatchFixtures.MUM, 13);

            PartitionPlan plan = PartitionPlan.over(before, GRAINS, 10);
            PartitionPlan grown = PartitionPlan.over(after, GRAINS, 10);

            assertThat(grown.slices()).extracting(slice -> slice.key().name())
                .as("both plans are shard0 and shard1 — no name moved")
                .containsExactlyElementsOf(
                    plan.slices().stream().map(slice -> slice.key().name()).toList());
            assertThat(grown.fingerprint())
                .as("and the fingerprint still differs, because it carries the sizes")
                .isNotEqualTo(plan.fingerprint());
        }
    }

    @Nested
    @DisplayName("Partition names")
    class Names {

        @Test
        @DisplayName("a grain component carrying the name separator is refused")
        void aSeparatorInAGrainComponentIsRefused() {
            // "RETAIL|EMI" x "MUM" and "RETAIL" x "EMI|MUM" would compose to one partition name, and
            // a restart matches completion by name: one grain would resume under the other's
            // completion record and the second grain's contracts would never be computed.
            assertThatThrownBy(() -> PartitionKey.grain("RETAIL|EMI", "IN-MUM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition-name separator");
            assertThatThrownBy(() -> PartitionKey.grain("RETAIL", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("the name carries the product, the entity and the shard")
        void theNameCarriesAllThree() {
            assertThat(new PartitionKey(BatchFixtures.RETAIL, BatchFixtures.MUM, 2).name())
                .isEqualTo("RETAIL-EMI|IN-MUM|shard2");
            assertThat(PartitionKey.grain(BatchFixtures.RETAIL, BatchFixtures.MUM).isGrain())
                .isTrue();
            assertThat(new PartitionKey(BatchFixtures.RETAIL, BatchFixtures.MUM, 2).grain())
                .isEqualTo(PartitionKey.grain(BatchFixtures.RETAIL, BatchFixtures.MUM));
        }
    }
}
