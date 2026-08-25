package com.crisil.eir.policy.fee.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.FeeClassification;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The property the whole unit rests on: <b>precedence is total</b>. For any rule set and any lookup
 * there is exactly one winner, or none, and never two the resolver has to choose between.
 *
 * <p>Asserted as a property rather than as cases because the claim is about the wildcard-matching
 * argument in {@link FeeRule#PRECEDENCE}, and that argument is a paragraph of reasoning: candidates
 * of equal specificity must agree on the whole key except the date, and {@link FeeRuleSet} forbids
 * two rules on one key, so equal specificity implies distinct dates. Reasoning of that shape is
 * exactly what a generator checks better than examples — a missed shape would show up here as a
 * genuine tie, and in production as an arbitrary choice between two treatments appearing in the
 * ledger as an ordinary classification.
 *
 * <h2>How the expectation is derived without running the resolver</h2>
 *
 * <p>Rules are built from generated {@code (product, entity, date)} tuples that the test keeps hold
 * of, so specificity is recomputed here from the raw tuple — 2 for a named product plus 1 for a
 * named entity — and matching is re-derived from the raw strings and dates. The winner is then the
 * unique maximum of {@code (specificity, date)} over the matching tuples, computed by this test's
 * own comparison and never by asking {@code FeeRule.PRECEDENCE}. Where the resolver and this
 * re-derivation disagree, one of the two is wrong and the test does not say which — which is the
 * point of deriving it twice.
 */
class FeeRulePrecedencePropertiesTest {

    private static final String FEE_CODE = "PROC_FEE";

    /** Two named products and the wildcard: three values, so all four specificity shapes arise. */
    private static final List<String> PRODUCTS = List.of("HOME_LOAN", "PERSONAL_LOAN", FeeRuleKey.ANY);

    /** Two named entities and the wildcard. */
    private static final List<String> ENTITIES = List.of("BANK_IN", "NBFC_IN", FeeRuleKey.ANY);

    /**
     * Three rule dates. The rule set version takes effect before all of them, so version-level
     * dating never interferes with rule-level dating.
     */
    private static final List<LocalDate> DATES = List.of(
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), LocalDate.of(2028, 1, 1));

    private static final LocalDate VERSION_EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);

    /**
     * The five treatments, cycled by tuple index so that neighbouring rules disagree.
     *
     * <p>A generator that gave every rule the same classification would pass whichever rule the
     * resolver picked, which would make the property vacuous.
     */
    private static final List<FeeClassification> MARKERS = List.of(
        FeeClassification.INTEGRAL,
        FeeClassification.AS_INCURRED,
        FeeClassification.OVER_COMMITMENT_PERIOD,
        FeeClassification.SEPARATE_SERVICE,
        FeeClassification.EXCLUDED_BY_DIRECTION);

    /** The 27 {@code (product, entity, date)} combinations, in a fixed order so an index names one. */
    private static final List<Combination> COMBINATIONS = enumerate();

    /** One generated rule or lookup, kept as raw components so the expectation can be re-derived. */
    private record Combination(String product, String entity, LocalDate date, int index) {

        /** Specificity from the raw tuple: 2 for a named product, 1 for a named entity. */
        int specificity() {
            return (FeeRuleKey.ANY.equals(product) ? 0 : 2) + (FeeRuleKey.ANY.equals(entity) ? 0 : 1);
        }

        /** Whether this combination, read as a rule, covers {@code lookup}. Re-derived from the raw values. */
        boolean covers(Combination lookup) {
            boolean productOk = FeeRuleKey.ANY.equals(product) || product.equals(lookup.product());
            boolean entityOk = FeeRuleKey.ANY.equals(entity) || entity.equals(lookup.entity());
            return productOk && entityOk && !date.isAfter(lookup.date());
        }

        FeeClassification marker() {
            return MARKERS.get(index % MARKERS.size());
        }

        FeeRule asRule() {
            return FeeRule.of(FEE_CODE, product, entity, date, marker(),
                "generated rule " + index + " for the precedence property");
        }
    }

    private static List<Combination> enumerate() {
        List<Combination> combinations = new ArrayList<>();
        int index = 0;
        for (String product : PRODUCTS) {
            for (String entity : ENTITIES) {
                for (LocalDate date : DATES) {
                    combinations.add(new Combination(product, entity, date, index++));
                }
            }
        }
        return List.copyOf(combinations);
    }

    /**
     * For any rule set drawn from the 27 shapes and any lookup, the resolver's answer is the unique
     * maximum of {@code (specificity, date)} over the covering rules — and there is always exactly
     * one maximum.
     */
    @Property(tries = 500)
    void precedenceIsTotalAndPicksTheUniqueMaximum(
        @ForAll @Size(min = 1, max = 12) List<@IntRange(min = 0, max = 26) Integer> ruleIndices,
        @ForAll @IntRange(min = 0, max = 26) int lookupIndex) {

        // Deduplicate: two rules on one key are refused by FeeRuleSet, deliberately, so a generated
        // list with repeats is not a rule set the engine would ever hold.
        Set<Integer> distinct = new LinkedHashSet<>(ruleIndices);
        List<Combination> chosen = new ArrayList<>(distinct.size());
        List<FeeRule> rules = new ArrayList<>(distinct.size());
        for (int index : distinct) {
            Combination combination = COMBINATIONS.get(index);
            chosen.add(combination);
            rules.add(combination.asRule());
        }

        Combination lookup = COMBINATIONS.get(lookupIndex);
        FeeClassificationResolver resolver = new FeeClassificationResolver(new FeeRuleSet(
            FeeRuleFixtures.approved("FEE-PROP.1", VERSION_EFFECTIVE_FROM), rules));
        FeeClassificationResolution resolution = resolver.resolve(
            FEE_CODE, lookup.product(), lookup.entity(), lookup.date());

        // Independently: which of the chosen rules cover this lookup, and which of those is highest?
        List<Combination> covering = new ArrayList<>();
        for (Combination combination : chosen) {
            if (combination.covers(lookup)) {
                covering.add(combination);
            }
        }

        if (covering.isEmpty()) {
            assertThat(resolution.isResolved())
                .as("nothing covers %s, so FR-202 refuses rather than reaching for the nearest rule",
                    lookup)
                .isFalse();
            return;
        }

        Combination expected = covering.get(0);
        int maxima = 1;
        for (int i = 1; i < covering.size(); i++) {
            Combination candidate = covering.get(i);
            int bySpecificity = Integer.compare(candidate.specificity(), expected.specificity());
            int comparison = bySpecificity != 0
                ? bySpecificity
                : candidate.date().compareTo(expected.date());
            if (comparison > 0) {
                expected = candidate;
                maxima = 1;
            } else if (comparison == 0) {
                maxima++;
            }
        }

        // Totality, stated over the whole generated space rather than argued in a comment. A tie
        // here would mean the resolver had to choose, and any choice it made would be arbitrary.
        assertThat(maxima)
            .as("exactly one covering rule is maximal under (specificity, date) for lookup %s", lookup)
            .isEqualTo(1);

        assertThat(resolution.isResolved())
            .as("%d rules cover %s, so one of them governs", covering.size(), lookup)
            .isTrue();
        assertThat(resolution.classification())
            .as("the unique maximum is specificity %d dated %s", expected.specificity(), expected.date())
            .isEqualTo(expected.marker());
        assertThat(resolution.rule().key().product())
            .as("and the winning rule is that rule, not merely one with the same treatment")
            .isEqualTo(expected.product());
        assertThat(resolution.rule().key().entity()).isEqualTo(expected.entity());
        assertThat(resolution.rule().key().effectiveFrom()).isEqualTo(expected.date());
    }

    /**
     * Resolution is deterministic: the same rule set and the same lookup give the same answer, in
     * whatever order the rules were registered.
     *
     * <p>Order-independence is the operative half. A stored {@code rule_set_version_id} identifies a
     * reading, and a reading that depends on the order a configuration file happened to be read in
     * is not one — invariant DT-1 would fail on a replay that shuffled the rows.
     */
    @Property(tries = 300)
    void resolutionDoesNotDependOnRuleOrder(
        @ForAll @Size(min = 1, max = 10) List<@IntRange(min = 0, max = 26) Integer> ruleIndices,
        @ForAll @IntRange(min = 0, max = 26) int lookupIndex) {

        Set<Integer> distinct = new LinkedHashSet<>(ruleIndices);
        List<FeeRule> forward = new ArrayList<>(distinct.size());
        for (int index : distinct) {
            forward.add(COMBINATIONS.get(index).asRule());
        }
        List<FeeRule> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);

        Combination lookup = COMBINATIONS.get(lookupIndex);
        FeeClassificationResolution first = new FeeClassificationResolver(new FeeRuleSet(
            FeeRuleFixtures.approved("FEE-PROP.1", VERSION_EFFECTIVE_FROM), forward))
            .resolve(FEE_CODE, lookup.product(), lookup.entity(), lookup.date());
        FeeClassificationResolution second = new FeeClassificationResolver(new FeeRuleSet(
            FeeRuleFixtures.approved("FEE-PROP.1", VERSION_EFFECTIVE_FROM), reversed))
            .resolve(FEE_CODE, lookup.product(), lookup.entity(), lookup.date());

        assertThat(second.classification())
            .as("the same taxonomy read in the opposite order classifies %s identically", lookup)
            .isEqualTo(first.classification());
        assertThat(second.isResolved()).isEqualTo(first.isResolved());
    }
}
