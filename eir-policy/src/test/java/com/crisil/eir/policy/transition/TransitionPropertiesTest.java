package com.crisil.eir.policy.transition;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;

/**
 * The five Phase 4 invariants over generated populations.
 *
 * <p>Phase 4's unit tests assert each control on a hand-built fixture, which is the right way to
 * pin a specific accounting reading and the wrong way to establish that a <em>count</em> is
 * correct. Four of the five publish a count as their deviation, and a count is exactly the kind of
 * figure that is right on a four-row fixture and wrong on a population — an off-by-one at a
 * boundary, a filter that drops a category, a shortcut that holds for the ordering the fixture
 * happens to use.
 *
 * <p>No expected value here comes from running the code under test. Each property recomputes the
 * answer from the invariant's <em>definition</em>, independently and usually less efficiently, and
 * compares. The clearest case is {@link #lcOneMatchesThePairwiseDefinition}: LC-1 is defined
 * pairwise — no survivor of the 2030 deadline is queued behind any cohort that runs off before it —
 * and {@code LegacyMigrationPlan} implements it by comparing against the minimum non-survivor
 * priority. Those are the same set, and the property is what establishes that the shortcut computes
 * it: a {@code max} for a {@code min}, or a {@code >=} for a {@code >}, passes every fixture whose
 * priorities happen to be distinct and ascending.
 */
class TransitionPropertiesTest {

    private static final LocalDate DEFINED = LocalDate.of(2027, 4, 1);
    private static final LocalDate TRANSITION = LocalDate.of(2027, 4, 1);
    private static final Rate MARKET = Rate.monthly(new BigDecimal("0.0075"));
    private static final Rate CONCESSIONAL = Rate.monthly(new BigDecimal("0.005"));

    // ---------------------------------------------------------------- LC-1

    /**
     * LC-1 counts exactly the survivors that the pairwise definition says are queued behind a
     * cohort running off before the deadline.
     *
     * <p>Run-off offsets span 0 to 3,300 days from 1 April 2027, which straddles 31 March 2030 —
     * so a generated plan contains survivors and non-survivors in arbitrary proportion, including
     * all of one kind, which is the case where a min over an empty set has to behave.
     */
    @Property(tries = 500)
    void lcOneMatchesThePairwiseDefinition(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 3300) Integer> runoffOffsets,
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 10) Integer> priorities) {
        List<LegacyCohort> cohorts = cohorts(runoffOffsets, priorities);
        LegacyMigrationPlan plan = LegacyMigrationPlan.of(cohorts);

        // The definition, computed pairwise and deliberately not the way the code does it.
        long expected = cohorts.stream()
            .filter(LegacyCohort::survivesAcpir50Deadline)
            .filter(survivor -> cohorts.stream()
                .filter(other -> !other.survivesAcpir50Deadline())
                .anyMatch(other -> other.migrationPriority() < survivor.migrationPriority()))
            .count();

        InvariantResult result = plan.prioritisedBySurvival();
        assertThat(result.id()).isEqualTo(InvariantId.LC_1);
        assertThat(result.satisfied())
            .as("%d inversions by the pairwise definition; detail: %s", expected, result.detail())
            .isEqualTo(expected == 0);
        if (expected > 0) {
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(expected));
        }
    }

    /**
     * The deadline boundary, over arbitrary run-off dates: survival is strictly after
     * 31 March 2030.
     *
     * <p>Asserted against a date comparison written out here rather than against the constant's
     * own accessor, because the boundary is the one place a day either way changes which cohorts
     * are in the priority queue — and the schema's generated column draws it independently, so a
     * drift here would put a cohort inside the queue in Java and outside it in SQL.
     */
    @Property(tries = 400)
    void survivalIsStrictlyAfterTheDeadline(
        @ForAll @IntRange(min = 0, max = 3300) int runoffOffset) {
        LegacyCohort cohort = cohort("C", runoffOffset, 1, MigrationMethod.FULL_RECONSTRUCTION);
        boolean expected = cohort.expectedRunoffDate().isAfter(LocalDate.of(2030, 3, 31));
        assertThat(cohort.survivesAcpir50Deadline()).isEqualTo(expected);
        assertThat(cohort.reconstructionEffortIsWasted())
            .as("full reconstruction on a cohort that has gone by the deadline")
            .isEqualTo(!expected);
    }

    // ---------------------------------------------------------------- DE-1

    /**
     * DE-1 counts exactly the deemed cohorts without an approved derivation, over arbitrary mixes
     * of method and approval state.
     */
    @Property(tries = 400)
    void deOneCountsUnbackedDeemedCohorts(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 2) Integer> shapes) {
        List<LegacyCohort> cohorts = new ArrayList<>();
        List<DeemedEirDerivation> derivations = new ArrayList<>();
        long expected = 0;
        for (int index = 0; index < shapes.size(); index++) {
            String name = "COHORT-" + index;
            int shape = shapes.get(index);
            if (shape == 0) {
                // Reconstructed: needs no derivation, and must not be counted.
                cohorts.add(cohort(name, 3000, index + 1, MigrationMethod.FULL_RECONSTRUCTION));
                continue;
            }
            cohorts.add(cohort(name, 3000, index + 1, MigrationMethod.DEEMED_EIR));
            if (shape == 1) {
                // Deemed with an approved derivation.
                derivations.add(derivation(name, "programme.director"));
            } else {
                // Deemed with a prepared-but-unapproved one.
                derivations.add(derivation(name, null));
                expected++;
            }
        }

        InvariantResult result = LegacyMigrationPlan.of(cohorts, derivations).deemedRatesApproved();
        assertThat(result.id()).isEqualTo(InvariantId.DE_1);
        assertThat(result.satisfied())
            .as("%d unbacked; detail: %s", expected, result.detail())
            .isEqualTo(expected == 0);
        if (expected > 0) {
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(expected));
        }
    }

    // ---------------------------------------------------------------- TF-1

    /**
     * TF-1 counts exactly the paragraph 19 presumptions with no evidence named, and the run total
     * is the unrounded sum of the per-contract differences.
     *
     * <p>Two claims in one property because they need the same generated population and the
     * summation claim is cheap. The total matters as much as the count: it goes to opening
     * retained earnings, and rounding each contract before summing accumulates the error into a
     * figure posted to equity.
     */
    @Property(tries = 500)
    void tfOneCountsUnevidencedPresumptionsAndTheTotalIsExact(
        @ForAll @Size(min = 1, max = 10) List<@IntRange(min = 0, max = 3) Integer> shapes,
        @ForAll @BigRange(min = "0.000001", max = "10000000") @Scale(6) BigDecimal delta) {
        List<TransitionFairValue> valuations = new ArrayList<>();
        long expectedUnevidenced = 0;
        Money expectedTotal = Money.zero(Money.INR);

        for (int index = 0; index < shapes.size(); index++) {
            String id = "C" + index;
            Money carrying = Money.inr("1000000.00");
            switch (shapes.get(index)) {
                case 0 -> {
                    // A quoted price above carrying: the difference is the generated delta.
                    Money fair = carrying.plus(Money.of(delta, Money.INR));
                    valuations.add(new TransitionFairValue(id, TRANSITION, carrying, fair,
                        ValuationTechnique.QUOTED_PRICE, null, null, "valuation.analyst", null));
                    expectedTotal = expectedTotal.plus(fair.minus(carrying));
                }
                case 1 -> {
                    // A discounted cash flow below carrying.
                    Money fair = carrying.minus(Money.of(delta, Money.INR));
                    valuations.add(new TransitionFairValue(id, TRANSITION, carrying, fair,
                        ValuationTechnique.DISCOUNTED_CASH_FLOW, MARKET, null,
                        "valuation.analyst", null));
                    expectedTotal = expectedTotal.plus(fair.minus(carrying));
                }
                case 2 -> valuations.add(new TransitionFairValue(id, TRANSITION, carrying, carrying,
                    ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null, "EVID-" + index,
                    "valuation.analyst", null));
                default -> {
                    valuations.add(new TransitionFairValue(id, TRANSITION, carrying, carrying,
                        ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null, null,
                        "valuation.analyst", null));
                    expectedUnevidenced++;
                }
            }
        }

        TransitionValuationRun run = TransitionValuationRun.over(valuations);

        InvariantResult result = run.paragraph19Evidenced();
        assertThat(result.id()).isEqualTo(InvariantId.TF_1);
        assertThat(result.satisfied()).isEqualTo(expectedUnevidenced == 0);
        if (expectedUnevidenced > 0) {
            assertThat(result.deviation())
                .isEqualByComparingTo(BigDecimal.valueOf(expectedUnevidenced));
        }

        assertThat(run.totalDifferenceToOpeningRetainedEarnings())
            .as("summed unrounded: a per-contract round would drift on %d contracts at a delta"
                + " of %s", valuations.size(), delta)
            .isEqualTo(expectedTotal);
        assertThat(run.techniqueMix().values().stream().mapToLong(Long::longValue).sum())
            .as("every valuation is in exactly one technique bucket")
            .isEqualTo(valuations.size());
    }

    // ---------------------------------------------------------------- TM-1

    /**
     * TM-1's deviation is untracked plus past-deadline, and the two kinds are counted together
     * without either masking the other.
     *
     * <p>The date is generated across the deadline, which is what makes this more than a count
     * check: the same population has to pass before 31 March 2030 and fail after it, for any
     * population containing an interim contract.
     */
    @Property(tries = 500)
    void tmOneCountsUntrackedPlusPastDeadline(
        @ForAll @Size(min = 1, max = 10) List<@IntRange(min = 0, max = 2) Integer> shapes,
        @ForAll @IntRange(min = 0, max = 5) int untracked,
        @ForAll @IntRange(min = -400, max = 400) int daysFromDeadline) {
        List<ContractMigrationState> states = new ArrayList<>();
        long interimCount = 0;
        for (int index = 0; index < shapes.size(); index++) {
            String id = "C" + index;
            switch (shapes.get(index)) {
                case 0 -> states.add(ContractMigrationState.migrated(
                    id, 202809, "SOLVE-" + id, MARKET, LocalDate.of(2028, 6, 30), null));
                case 1 -> {
                    states.add(ContractMigrationState.onInterimBasis(id, 202809, true));
                    interimCount++;
                }
                default -> {
                    states.add(ContractMigrationState.onInterimBasis(id, 202809, false));
                    interimCount++;
                }
            }
        }
        LocalDate asOf = LegacyCohort.ACPIR_50_DEADLINE.plusDays(daysFromDeadline);
        MigrationTracker tracker = MigrationTracker.over(states, states.size() + untracked);

        boolean pastDeadline = asOf.isAfter(LegacyCohort.ACPIR_50_DEADLINE);
        long expected = untracked + (pastDeadline ? interimCount : 0);

        InvariantResult result = tracker.migrationTracked(asOf);
        assertThat(result.id()).isEqualTo(InvariantId.TM_1);
        assertThat(result.satisfied())
            .as("as at %s with %d untracked and %d interim; detail: %s",
                asOf, untracked, interimCount, result.detail())
            .isEqualTo(expected == 0);
        if (expected > 0) {
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(expected));
        }

        // And the progress figure is never the deviation: it counts only contracts that have met
        // ACPIR 21 and not ACPIR 50, whatever the date.
        long underConcession = states.stream()
            .filter(ContractMigrationState::isUnderTheAcpir50Concession)
            .count();
        assertThat(tracker.outstandingAcpir50Migrations()).isEqualTo(underConcession);
        assertThat(tracker.untrackedContracts()).isEqualTo(untracked);
    }

    // ---------------------------------------------------------------- BM-1

    /**
     * BM-1's deviation counts unapproved destinations, and the shortfall it reports is the sum of
     * exactly those.
     *
     * <p>The shortfall is generated as a fraction of the amount disbursed rather than as an
     * absolute, so the property covers a concession of a rupee and one of nearly the whole
     * advance — and the constructor's premise check (fair value never above the amount disbursed)
     * holds by construction rather than by the generator being lucky.
     */
    @Property(tries = 400)
    void bmOneSumsOnlyTheUnapprovedShortfalls(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 2) Integer> shapes,
        @ForAll @BigRange(min = "0.000001", max = "0.999999") @Scale(6) BigDecimal shortfallShare) {
        Money disbursed = Money.inr("5000000.00");
        Money shortfall = disbursed.times(shortfallShare);
        Money fairValue = disbursed.minus(shortfall);

        List<BelowMarketOrigination> originations = new ArrayList<>();
        long expectedUnapproved = 0;
        Money expectedExposure = Money.zero(Money.INR);

        for (int index = 0; index < shapes.size(); index++) {
            String id = "STAFF-" + index;
            PolicyVersion inForce = position(TRANSITION);
            switch (shapes.get(index)) {
                case 0 -> originations.add(new BelowMarketOrigination(id, TRANSITION, disbursed,
                    fairValue, MARKET, CONCESSIONAL,
                    DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, inForce,
                    "valuation.analyst", null));
                case 1 -> {
                    // No destination chosen: the Silence 6 state.
                    originations.add(new BelowMarketOrigination(id, TRANSITION, disbursed,
                        fairValue, MARKET, CONCESSIONAL, null, null, "valuation.analyst", null));
                    expectedUnapproved++;
                    expectedExposure = expectedExposure.plus(shortfall);
                }
                default -> {
                    // A position that takes effect after the loan was written.
                    originations.add(new BelowMarketOrigination(id, TRANSITION, disbursed,
                        fairValue, MARKET, CONCESSIONAL,
                        DayOneDifferenceDestination.OTHER_OPERATING_EXPENSE,
                        position(TRANSITION.plusMonths(3)), "valuation.analyst", null));
                    expectedUnapproved++;
                    expectedExposure = expectedExposure.plus(shortfall);
                }
            }
        }

        InvariantResult result = BelowMarketOrigination.destinationsApproved(originations);
        assertThat(result.id()).isEqualTo(InvariantId.BM_1);
        assertThat(result.satisfied()).isEqualTo(expectedUnapproved == 0);
        if (expectedUnapproved > 0) {
            assertThat(result.deviation())
                .isEqualByComparingTo(BigDecimal.valueOf(expectedUnapproved));
            assertThat(result.detail())
                .as("only the unapproved shortfalls are exposed, at share %s", shortfallShare)
                .contains(expectedExposure.atPresentationScale().toString());
        }

        // The shortfall is positive for every generated share, and the EIR is never the concession.
        for (BelowMarketOrigination origination : originations) {
            assertThat(origination.dayOneShortfall().isNegative()).isFalse();
            assertThat(origination.effectiveInterestRate()).isEqualTo(MARKET);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static List<LegacyCohort> cohorts(List<Integer> offsets, List<Integer> priorities) {
        int size = Math.min(offsets.size(), priorities.size());
        List<LegacyCohort> cohorts = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            cohorts.add(cohort("COHORT-" + index, offsets.get(index), priorities.get(index),
                MigrationMethod.FULL_RECONSTRUCTION));
        }
        return cohorts;
    }

    private static LegacyCohort cohort(
        String name, int runoffOffsetDays, int priority, MigrationMethod method) {
        return new LegacyCohort(name, name + " definition", DEFINED,
            DEFINED.plusDays(runoffOffsetDays), priority, method, 1, "programme.owner");
    }

    private static DeemedEirDerivation derivation(String cohortName, String approver) {
        return new DeemedEirDerivation(cohortName, null,
            Rate.monthly(new BigDecimal("0.0091")), DeemedEirBasis.ORIGINATION_PRICING_GRID,
            "source records not migrated", "pricing grid at sanction", null,
            "transition.analyst", approver,
            approver == null ? null : LocalDate.of(2027, 5, 20));
    }

    private static PolicyVersion position(LocalDate from) {
        return new PolicyVersion("POS-SILENCE-06", PolicyKind.POLICY_POSITION,
            "day-1 destination", from, "accounting.policy.owner", "board.secretary",
            LocalDate.of(2027, 3, 1), PolicyVersionStatus.EFFECTIVE);
    }
}
