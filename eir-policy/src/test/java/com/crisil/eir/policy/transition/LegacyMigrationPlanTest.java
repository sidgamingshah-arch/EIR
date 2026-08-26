package com.crisil.eir.policy.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Legacy cohort segmentation and the deemed EIR (FR-908, FR-909, 04 § 6, 08 Phase 4).
 *
 * <p><b>The fixture is the failure mode.</b> Four cohorts, built so that the wrong prioritisation
 * is the one that looks obviously right:
 *
 * <pre>
 *   AUTO-2024      1,200,000 contracts  runs off 2029-08-31  before the deadline
 *   MORTGAGE-2015    400,000 contracts  runs off 2035-06-30  SURVIVES
 *   PERSONAL-2026     90,000 contracts  runs off 2030-03-31  the boundary — does NOT survive
 *   PROJECT-2019         800 contracts  runs off 2038-12-31  SURVIVES, deemed EIR
 * </pre>
 *
 * <p>Ordered by size, AUTO-2024 goes first and PROJECT-2019 last. Ordered by survival it is the
 * reverse, and the 1.2 million-contract cohort is the one that never needed a reconstructed rate at
 * all. That is the whole content of the roadmap's warning, and it is why LC-1 is a control rather
 * than advice: the wrong ordering is defensible-looking.
 */
class LegacyMigrationPlanTest {

    private static final LocalDate DEFINED = LocalDate.of(2027, 4, 1);

    private static LegacyCohort cohort(
        String name, String runoff, int priority, MigrationMethod method, long contracts) {
        return new LegacyCohort(name, name + " definition", DEFINED,
            LocalDate.parse(runoff), priority, method, contracts, "programme.owner");
    }

    private static LegacyCohort auto(int priority) {
        return cohort("AUTO-2024", "2029-08-31", priority,
            MigrationMethod.FULL_RECONSTRUCTION, 1_200_000);
    }

    private static LegacyCohort mortgage(int priority) {
        return cohort("MORTGAGE-2015", "2035-06-30", priority,
            MigrationMethod.FULL_RECONSTRUCTION, 400_000);
    }

    private static LegacyCohort personal(int priority) {
        return cohort("PERSONAL-2026", "2030-03-31", priority,
            MigrationMethod.FULL_RECONSTRUCTION, 90_000);
    }

    private static LegacyCohort project(int priority) {
        return cohort("PROJECT-2019", "2038-12-31", priority, MigrationMethod.DEEMED_EIR, 800);
    }

    private static DeemedEirDerivation derivation(String approver) {
        return new DeemedEirDerivation("PROJECT-2019", null,
            Rate.monthly(new BigDecimal("0.0091")),
            DeemedEirBasis.ORIGINATION_PRICING_GRID,
            "the 2019 disbursement schedules were held by an acquired entity and were not migrated",
            "the pricing grid in force at sanction, plus the arrangement fee from the sanction letter",
            "WP-2027-PF-011", "transition.analyst",
            approver, approver == null ? null : LocalDate.of(2027, 5, 20));
    }

    @Nested
    @DisplayName("the 2030 deadline, and where its boundary falls")
    class Deadline {

        @Test
        @DisplayName("running off ON 31 March 2030 has met the deadline; a day later has not")
        void boundaryIsStrictlyAfter() {
            // The obligation is to be on the EIR BY that date, so an exposure ending on it was on
            // whatever basis it was on for its whole life. The schema draws the line the same way
            // (expected_runoff_date > DATE '2030-03-31'), and a one-day disagreement would put a
            // cohort in the priority queue in one place and out of it in the other.
            assertThat(personal(1).survivesAcpir50Deadline()).isFalse();
            assertThat(cohort("EDGE", "2030-04-01", 1,
                MigrationMethod.FULL_RECONSTRUCTION, 1).survivesAcpir50Deadline()).isTrue();
            assertThat(LegacyCohort.ACPIR_50_DEADLINE).isEqualTo(LocalDate.of(2030, 3, 31));
        }

        @Test
        @DisplayName("only the survivors have to be migrated, and the count says how many")
        void migrationScope() {
            // 400,000 + 800 = 400,800. The 1.29 million contracts in AUTO-2024 and PERSONAL-2026
            // never need a reconstructed rate, which is the point of segmenting at all.
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(auto(3), mortgage(1), personal(4), project(2)),
                List.of(derivation("programme.director")));
            assertThat(plan.contractsRequiringMigration()).isEqualTo(400_800L);
            assertThat(plan.survivingCohortsInQueueOrder())
                .extracting(LegacyCohort::cohortName)
                .containsExactly("MORTGAGE-2015", "PROJECT-2019");
        }
    }

    @Nested
    @DisplayName("LC-1: prioritise by survival, not size")
    class Prioritisation {

        @Test
        @DisplayName("ordering by size puts two survivors behind a cohort that runs off first")
        void sizeOrderingBreaches() {
            // The defensible-looking wrong answer: biggest first.
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(auto(1), mortgage(2), personal(3), project(4)),
                List.of(derivation("programme.director")));

            InvariantResult result = plan.prioritisedBySurvival();
            assertThat(result.id()).isEqualTo(InvariantId.LC_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("MORTGAGE-2015 and PROJECT-2019, both behind AUTO-2024 at priority 1")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(result.detail())
                .contains("MORTGAGE-2015 (priority 2, 400000 contracts, runs off 2035-06-30)")
                .contains("exposures that will have gone");
        }

        @Test
        @DisplayName("ordering by survival passes, whatever the sizes")
        void survivalOrderingPasses() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(1), project(2), auto(3), personal(4)),
                List.of(derivation("programme.director")));

            InvariantResult result = plan.prioritisedBySurvival();
            assertThat(result.satisfied())
                .as("%s", result.detail())
                .isTrue();
            assertThat(result.detail()).contains("4 cohorts queued");
        }

        @Test
        @DisplayName("equal priorities run together and are not an inversion")
        void equalPrioritiesAreFine() {
            // The plan is not required to be sorted. Cohorts at the same priority run together,
            // and the assertion is about one specific inversion rather than about sortedness — so
            // a survivor level with a non-survivor is permitted.
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(1), auto(1), project(1), personal(1)),
                List.of(derivation("programme.director")));
            assertThat(plan.prioritisedBySurvival().satisfied()).isTrue();
        }

        @Test
        @DisplayName("a plan with no cohort running off early cannot breach")
        void allSurvivorsPasses() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(9), project(1)),
                List.of(derivation("programme.director")));
            assertThat(plan.prioritisedBySurvival().satisfied())
                .as("nothing to be queued behind")
                .isTrue();
        }

        @Test
        @DisplayName("wasted reconstruction effort is reported as data, not as an invariant")
        void wastedEffortIsNotABreach() {
            // Spending effort badly is not an accounting breach, and an id on it would put a
            // programme management question in the same list as a figure that does not tie —
            // which is how an invariant dashboard stops being read. Reported, biggest first,
            // because that is the order somebody would act on.
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(auto(1), mortgage(2), personal(3), project(4)),
                List.of(derivation("programme.director")));

            assertThat(plan.wastedReconstructionEffort())
                .extracting(LegacyCohort::cohortName)
                .as("both are queued for full reconstruction and both run off before the deadline")
                .containsExactly("AUTO-2024", "PERSONAL-2026");
            assertThat(plan.invariants())
                .extracting(InvariantResult::id)
                .as("and neither appears in the invariants")
                .containsExactly(InvariantId.LC_1, InvariantId.DE_1);
        }
    }

    @Nested
    @DisplayName("DE-1: a deemed rate recognises income on an assumption")
    class DeemedRates {

        @Test
        @DisplayName("a deemed cohort with no derivation at all fails")
        void noDerivation() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(1), project(2)));

            InvariantResult result = plan.deemedRatesApproved();
            assertThat(result.id()).isEqualTo(InvariantId.DE_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail())
                .contains("PROJECT-2019 (no derivation)")
                .contains("unsigned assumption");
        }

        @Test
        @DisplayName("a prepared but unapproved derivation still fails, and says which")
        void unapprovedDerivation() {
            // Two failure shapes, one count, named differently in the detail because the remedies
            // differ: no derivation needs one prepared, an unapproved one needs it signed.
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(1), project(2)), List.of(derivation(null)));

            InvariantResult result = plan.deemedRatesApproved();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail())
                .contains("derivation prepared by transition.analyst, unapproved");
        }

        @Test
        @DisplayName("an approved derivation passes, and the plan finds it by cohort name")
        void approvedPasses() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(
                List.of(mortgage(1), project(2)), List.of(derivation("programme.director")));

            assertThat(plan.deemedRatesApproved().satisfied()).isTrue();
            assertThat(plan.derivationFor("PROJECT-2019")).isPresent();
            assertThat(plan.derivationFor("MORTGAGE-2015"))
                .as("a reconstructed cohort has no derivation and needs none")
                .isEmpty();
        }

        @Test
        @DisplayName("a plan with no deemed cohorts passes without needing derivations")
        void noDeemedCohorts() {
            LegacyMigrationPlan plan = LegacyMigrationPlan.of(List.of(mortgage(1), auto(2)));
            InvariantResult result = plan.deemedRatesApproved();
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail()).contains("0 of 2 cohorts are on a deemed EIR");
        }

        @Test
        @DisplayName("a fee loading of nil reproduces the contractual rate, and is surfaced")
        void contractualRateReproduced() {
            // CONTRACTUAL_RATE_PLUS_FEE_LOADING with a nil loading is the pre-ACPIR position
            // wearing the language of an EIR. Surfaced as a question, not refused: a facility with
            // no integral fees or costs genuinely has an EIR equal to its contractual rate, which
            // is the right answer. What is not defensible is that outcome arriving without anyone
            // having looked.
            Rate contractual = Rate.monthly(new BigDecimal("0.0100"));
            DeemedEirDerivation nilLoading = new DeemedEirDerivation(
                "AUTO-2024", null, Rate.monthly(new BigDecimal("0.0100")),
                DeemedEirBasis.CONTRACTUAL_RATE_PLUS_FEE_LOADING,
                "fee records predate the core migration",
                "contractual rate with a fee loading estimated at nil",
                null, "transition.analyst", "programme.director", LocalDate.of(2027, 5, 20));

            assertThat(nilLoading.reproducesTheContractualRate(contractual)).isTrue();
            assertThat(derivation("programme.director")
                .reproducesTheContractualRate(contractual))
                .as("a pricing-grid basis is not this shape whatever the rate comes out at")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("what a derivation and a cohort refuse to represent")
    class Refusals {

        @Test
        @DisplayName("a derivation with neither cohort nor contract applies to nothing")
        void noSubject() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeemedEirDerivation(null, null,
                    Rate.monthly(new BigDecimal("0.009")),
                    DeemedEirBasis.ORIGINATION_PRICING_GRID, "why", "how", null,
                    "transition.analyst", null, null))
                .withMessageContaining("a rate with no subject cannot be the basis of any"
                    + " measurement");
        }

        @Test
        @DisplayName("the preparer is not a second opinion on their own assumption")
        void selfApprovalRefused() {
            for (String variant : new String[] {
                "transition.analyst", "Transition.Analyst", " transition.analyst "}) {
                assertThatIllegalArgumentException()
                    .as("approver '%s'", variant)
                    .isThrownBy(() -> derivation(variant))
                    .withMessageContaining("not a second opinion on recognising income from it");
            }
        }

        @Test
        @DisplayName("an approval is a person and a date, and half of one is neither")
        void halfAnApproval() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeemedEirDerivation("PROJECT-2019", null,
                    Rate.monthly(new BigDecimal("0.009")),
                    DeemedEirBasis.ORIGINATION_PRICING_GRID, "why", "how", null,
                    "transition.analyst", "programme.director", null))
                .withMessageContaining("half of one is neither an approval nor an absence");
        }

        @Test
        @DisplayName("both halves of the justification are mandatory")
        void bothJustificationsMandatory() {
            // Why reconstruction failed, and how the rate was arrived at instead. Either one alone
            // leaves the other assumed, and the two are what separate a legitimate deemed rate
            // from an unwillingness to look.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeemedEirDerivation("C", null,
                    Rate.monthly(new BigDecimal("0.009")),
                    DeemedEirBasis.ORIGINATION_PRICING_GRID, "  ", "how", null,
                    "transition.analyst", null, null))
                .withMessageContaining("a rate nobody tried to reconstruct");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeemedEirDerivation("C", null,
                    Rate.monthly(new BigDecimal("0.009")),
                    DeemedEirBasis.ORIGINATION_PRICING_GRID, "why", "   ", null,
                    "transition.analyst", null, null))
                .withMessageContaining("instead of the flows that no longer exist");
        }

        @Test
        @DisplayName("priority 0 has nothing before it, and a cohort cannot run off before it exists")
        void cohortShape() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> cohort("X", "2035-01-01", 0,
                    MigrationMethod.FULL_RECONSTRUCTION, 1))
                .withMessageContaining("1 is first and there is nothing before it");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> cohort("X", "2026-01-01", 1,
                    MigrationMethod.FULL_RECONSTRUCTION, 1))
                .withMessageContaining("before it was defined on");
        }

        @Test
        @DisplayName("two cohorts of one name give the plan two answers")
        void duplicateNameRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> LegacyMigrationPlan.of(List.of(mortgage(1), mortgage(2))))
                .withMessageContaining("the name is how a derivation and a plan refer to the same"
                    + " cohort");
        }
    }
}
