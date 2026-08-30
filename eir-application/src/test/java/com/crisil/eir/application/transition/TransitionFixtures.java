package com.crisil.eir.application.transition;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.DayOneDifferenceDestination;
import com.crisil.eir.policy.transition.DeemedEirBasis;
import com.crisil.eir.policy.transition.DeemedEirDerivation;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.MigrationMethod;
import com.crisil.eir.policy.transition.ValuationTechnique;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The transition population in memory, with every expected figure derived by hand.
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>The pre-transition carrying amount is reference case 1's month-13 opening gross carrying
 * amount, <b>528,407.32</b>, which the shared reference states independently of this engine
 * (₹10,00,000.00 at 1% monthly over 24 months, EIR 0.010421491800, month 13 opening 528,407.32,
 * closing 486,840.64). Using a published balance rather than a round number keeps the arithmetic
 * below checkable against a document.
 *
 * <p>The three fair values are chosen, and the three ACPIR 19 differences follow by subtraction:
 *
 * <table border="1">
 *   <caption>Hand-derived day-1 differences to opening retained earnings</caption>
 *   <tr><th>contract</th><th>technique</th><th>fair value</th><th>carrying</th>
 *       <th>difference</th></tr>
 *   <tr><td>C-QUOTED</td><td>QUOTED_PRICE</td><td>500,000.00</td><td>528,407.32</td>
 *       <td>−28,407.32</td></tr>
 *   <tr><td>C-DCF</td><td>DISCOUNTED_CASH_FLOW</td><td>520,000.00</td><td>528,407.32</td>
 *       <td>−8,407.32</td></tr>
 *   <tr><td>C-PRESUMED</td><td>CARRYING_COST_AS_BEST_EVIDENCE</td><td>528,407.32</td>
 *       <td>528,407.32</td><td>0.00</td></tr>
 * </table>
 *
 * <p>Total: {@code −28,407.32 + −8,407.32 + 0.00 = −36,814.64}, and the addition is
 * {@code 28,407.32 + 8,407.32 = 36,814.64} carried negative. Negative is the ordinary direction for
 * a legacy book measured at a current market rate, and it goes to <em>opening retained earnings</em>,
 * never to a period result.
 *
 * <h2>Where the migration positions come from</h2>
 *
 * <p>Chosen so that the two obligations' coverage figures <b>differ</b>, which is the case 04 § 6
 * gives the ECL discount basis its own table for: all three contracts have a rate in force, so ACPIR
 * 21 is 3 of 3; only C-QUOTED's ECL has moved to the EIR, so ACPIR 50 is 1 of 3. A single merged
 * "migrated" figure over this book would be 4 of 6 and describe neither obligation.
 *
 * <h2>No clock</h2>
 *
 * <p>{@link #ASSERTED_AS_OF} is a fixed date in FY29 — before the 31 March 2030 deadline, which is
 * the state TM-1 must not be red in — and {@link #KNOWN_AT} a fixed instant. Nothing here reads
 * {@code now()}, so every assertion below holds in 2027 and in 2031.
 */
final class TransitionFixtures {

    static final String RUN_ID = "TRANSITION-202903-01";

    /** The period the ECL discount basis is recorded for; YYYYMM, as 04 § 2.13 requires. */
    static final int PERIOD_ID = 202903;

    /** ACPIR's transition date: 1 April 2027, the effective date of the framework. */
    static final LocalDate TRANSITION_DATE = LocalDate.of(2027, 4, 1);

    /**
     * The date the exercise is asserted at: 31 March 2029, a year before the deadline.
     *
     * <p>Deliberately inside the migration window. TM-1 is formulated so that a contract still on
     * the interim ECL basis before 31 March 2030 is <em>not</em> a breach, because a control red for
     * three years gets suppressed and is then not there for the year it matters
     * ({@code InvariantId.TM_1}, 03 § 9). Every clean-population assertion below is made at this
     * date, so a tightening of that formulation breaks the tests rather than the close.
     */
    static final LocalDate ASSERTED_AS_OF = LocalDate.of(2029, 3, 31);

    /** The system-time boundary, supplied rather than read from a clock (DT-1, ADR-0003). */
    static final Instant KNOWN_AT = Instant.parse("2029-04-05T00:00:00Z");

    /** Reference case 1's month-13 opening gross carrying amount. */
    static final Money CARRYING_AMOUNT = Money.inr("528407.32");

    /** Reference case 1's EIR, per month, at the stored 12dp scale. */
    static final Rate EIR = Rate.periodic(new BigDecimal("0.010421491800"), 12);

    /** A current market rate for the discounted-cash-flow valuation: 1.5% monthly. */
    static final Rate MARKET_RATE = Rate.periodic(new BigDecimal("0.015000000000"), 12);

    /** The three contracts of the clean population, in the order the source names them. */
    static final String QUOTED = "C-QUOTED";
    static final String DCF = "C-DCF";
    static final String PRESUMED = "C-PRESUMED";

    /** The total ACPIR 19 difference over the clean population, derived in the class javadoc. */
    static final Money TOTAL_DIFFERENCE = Money.inr("-36814.64");

    static final String SURVIVOR_COHORT = "RETAIL-HOME-2019";
    static final String RUNOFF_COHORT = "AUTO-2024-SHORT";

    /** After 31 March 2030 — the case the whole deadline tracking exists for. */
    static final LocalDate SURVIVES_DEADLINE = LocalDate.of(2032, 3, 31);

    /** Before it: this cohort has run off and never needs a reconstructed rate. */
    static final LocalDate RUNS_OFF_EARLY = LocalDate.of(2029, 6, 30);

    private TransitionFixtures() {
    }

    /** A live boundary at {@link #ASSERTED_AS_OF}. */
    static AsAtBoundary boundary() {
        return AsAtBoundary.live(ASSERTED_AS_OF, KNOWN_AT);
    }

    /** Reference case 1's terms, for the opening state's sake. */
    static ContractTerms case1Terms() {
        return ContractTerms.of(
            Money.inr("1000000.00"),
            Rate.periodic(new BigDecimal("0.010000000000"), 12),
            24,
            12,
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 31),
            DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI,
            RateType.FIXED);
    }

    /**
     * An opening state carrying reference case 1's balance and, optionally, a rate in force.
     *
     * <p>The rate is what the exercise reads ACPIR 21 off — a contract with no rate is not under the
     * EIR regime for recognition — so a null here is a legacy contract awaiting reconstruction and
     * not a defect.
     */
    static ContractStateSource.OpeningState state(Rate eir) {
        return new ContractStateSource.OpeningState(
            case1Terms(), eir, CARRYING_AMOUNT, CARRYING_AMOUNT, Stage.STAGE_1,
            Money.zero(Money.INR), "ECL-2029.1", Money.inr("5298.16"));
    }

    /** The clean three-contract population's states: all three have a rate in force. */
    static MapStateSource cleanStates() {
        return new MapStateSource()
            .with(QUOTED, state(EIR))
            .with(DCF, state(EIR))
            .with(PRESUMED, state(EIR));
    }

    /**
     * The clean three-contract population's transition facts.
     *
     * <p>C-PRESUMED applies the paragraph 19 presumption <em>with</em> a rebuttal evidence
     * reference, so TF-1 passes. The unevidenced variant is built in the test that needs it, because
     * a fixture that was unevidenced by default would make every other assertion here run against a
     * breaching population.
     */
    static MapTransitionSource cleanSource() {
        return new MapTransitionSource()
            .withMeasurement(QUOTED, new TransitionSource.FairValueMeasurement(
                Money.inr("500000.00"), ValuationTechnique.QUOTED_PRICE, null, null,
                "valuer.one", "reviewer.one"))
            .withMeasurement(DCF, new TransitionSource.FairValueMeasurement(
                Money.inr("520000.00"), ValuationTechnique.DISCOUNTED_CASH_FLOW, MARKET_RATE, null,
                "valuer.one", "reviewer.one"))
            .withMeasurement(PRESUMED, new TransitionSource.FairValueMeasurement(
                CARRYING_AMOUNT, ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null,
                "WP/FY27/PARA19/RETAIL-HOME", "valuer.one", "reviewer.one"))
            // ACPIR 50 met on one contract only, so that the two obligations' coverage differs.
            .withEcl(QUOTED, TransitionSource.EclDiscountPosition.migrated(
                "EIRC-000001", EIR, LocalDate.of(2028, 9, 30), "WP/FY29/ECL-MIGRATION/1"))
            .withEcl(DCF, TransitionSource.EclDiscountPosition.interim())
            .withEcl(PRESUMED, TransitionSource.EclDiscountPosition.interim());
    }

    /**
     * A cohort surviving 31 March 2030, queued first, on full reconstruction.
     *
     * <p>{@code contractCount} is 2 and is <b>supplied</b>: nothing in this engine evaluates
     * {@code definition}, so the count is the plan author's assertion — which is exactly what
     * {@link AssertedCohortMembership#BASIS} says and what the coverage report has to print beside
     * the figure.
     */
    static LegacyCohort survivorCohort(int priority) {
        return new LegacyCohort(SURVIVOR_COHORT, "product = HOME_LOAN and vintage <= 2019",
            LocalDate.of(2028, 4, 1), SURVIVES_DEADLINE, priority,
            MigrationMethod.FULL_RECONSTRUCTION, 2L, "programme.head");
    }

    /** A cohort that runs off before the deadline, on a deemed rate. */
    static LegacyCohort runoffCohort(int priority) {
        return new LegacyCohort(RUNOFF_COHORT, "product = AUTO_LOAN and maturity < 2030-01-01",
            LocalDate.of(2028, 4, 1), RUNS_OFF_EARLY, priority,
            MigrationMethod.DEEMED_EIR, 1L, "programme.head");
    }

    /** An approved derivation for the deemed cohort — the DE-1-satisfying case. */
    static DeemedEirDerivation approvedDerivation() {
        return new DeemedEirDerivation(RUNOFF_COHORT, null, MARKET_RATE,
            DeemedEirBasis.ORIGINATION_PRICING_GRID,
            "origination fee postings for 2024 auto disbursals were archived in 2026 and the"
                + " archive is unreadable",
            "the 2024 auto pricing grid, weighted by the cohort's disbursal months",
            "WP/FY29/DEEMED/AUTO-2024", "analyst.two", "programme.head", LocalDate.of(2029, 1, 31));
    }

    /** The same derivation with nobody's signature on it — the DE-1 breach case. */
    static DeemedEirDerivation unapprovedDerivation() {
        DeemedEirDerivation approved = approvedDerivation();
        return new DeemedEirDerivation(approved.cohortName(), null, approved.deemedRate(),
            approved.basis(), approved.infeasibilityReason(), approved.documentedBasis(),
            approved.evidenceRef(), approved.preparedBy(), null, null);
    }

    /** The clean population's asserted membership: two survivors, one run-off. */
    static AssertedCohortMembership cleanMembership() {
        Map<String, String> byContract = new LinkedHashMap<>();
        byContract.put(QUOTED, SURVIVOR_COHORT);
        byContract.put(DCF, SURVIVOR_COHORT);
        byContract.put(PRESUMED, RUNOFF_COHORT);
        return AssertedCohortMembership.asserted(byContract);
    }

    /**
     * A Board position <em>in force</em> on the origination date, for the BM-1-satisfying case.
     *
     * <p>{@code EFFECTIVE}, not {@code APPROVED}, and the difference is the whole of
     * {@code PolicyVersionStatus}'s argument for having five states rather than a boolean: approval
     * and effect are separated by the effective date, so {@code isOperative()} is false for an
     * {@code APPROVED} version and {@code BelowMarketOrigination.destinationIsApproved()} — which
     * asks {@code isEffectiveOn(originationDate)} — reports it as not in force. That is correct: a
     * position signed in March to take effect in April does not authorise a destination in March.
     * {@link #approvedButNotInForcePosition()} is the same version one step back, for the test that
     * drives that case.
     */
    static PolicyVersion staffLoanPosition() {
        return new PolicyVersion("POS-STAFF-2027.1", PolicyKind.POLICY_POSITION,
            "day-1 shortfall on staff concessional loans is employee benefit cost, not a lending"
                + " loss (reference § 5 item 11, Silence 6)",
            LocalDate.of(2027, 4, 1), "policy.author", "board.secretary",
            LocalDate.of(2027, 3, 20), PolicyVersionStatus.EFFECTIVE);
    }

    /** The same position signed off but not yet in force — approved is not the same as operative. */
    static PolicyVersion approvedButNotInForcePosition() {
        PolicyVersion inForce = staffLoanPosition();
        return new PolicyVersion(inForce.id(), inForce.kind(), inForce.description(),
            inForce.effectiveFrom(), inForce.maker(), inForce.checker(), inForce.approvedOn(),
            PolicyVersionStatus.APPROVED);
    }

    /**
     * A staff housing loan written below market: 10,00,000.00 advanced, fair value 8,00,000.00.
     *
     * <p>Day-1 shortfall by subtraction: {@code 1,000,000.00 − 800,000.00 = 200,000.00}, which is
     * 20% of the amount disbursed — the reference's own order of magnitude for a public sector
     * bank's staff book, and material enough that where it lands changes a reported result.
     *
     * @param position the Board position, or null to leave the destination unapproved
     */
    static BelowMarketOrigination staffLoan(String contractId, PolicyVersion position) {
        return new BelowMarketOrigination(contractId, LocalDate.of(2027, 6, 30),
            Money.inr("1000000.00"), Money.inr("800000.00"), MARKET_RATE,
            Rate.periodic(new BigDecimal("0.004000000000"), 12),
            DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, position,
            "valuer.one", "reviewer.one");
    }

    /** A population that names exactly the ids it is given, in that order. */
    record FixedPopulation(List<String> ids) implements ContractSource {

        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            return List.copyOf(ids);
        }
    }

    /** An in-memory {@link ContractStateSource}: empty for anything it was not given. */
    static final class MapStateSource implements ContractStateSource {

        private final Map<String, OpeningState> states = new LinkedHashMap<>();

        MapStateSource with(String contractId, OpeningState state) {
            states.put(contractId, state);
            return this;
        }

        MapStateSource without(String contractId) {
            states.remove(contractId);
            return this;
        }

        @Override
        public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
            return Optional.ofNullable(states.get(contractId));
        }
    }

    /**
     * An in-memory {@link TransitionSource}, and a counter of what was asked.
     *
     * <p>The counters are here so a test can assert the exercise asked <em>once</em> per contract.
     * A pipeline that read a measurement twice would produce the same figures and hit the source
     * twice per contract over ten million contracts, and the only cheap way to prove it does not is
     * to count.
     */
    static final class MapTransitionSource implements TransitionSource {

        private final Map<String, FairValueMeasurement> measurements = new LinkedHashMap<>();
        private final Map<String, EclDiscountPosition> positions = new LinkedHashMap<>();
        private final Set<String> measurementsAsked = new LinkedHashSet<>();
        private int measurementCalls;

        MapTransitionSource withMeasurement(String contractId, FairValueMeasurement measurement) {
            measurements.put(contractId, measurement);
            return this;
        }

        MapTransitionSource withEcl(String contractId, EclDiscountPosition position) {
            positions.put(contractId, position);
            return this;
        }

        MapTransitionSource withoutMeasurement(String contractId) {
            measurements.remove(contractId);
            return this;
        }

        MapTransitionSource withoutEcl(String contractId) {
            positions.remove(contractId);
            return this;
        }

        int measurementCalls() {
            return measurementCalls;
        }

        Set<String> measurementsAsked() {
            return Set.copyOf(measurementsAsked);
        }

        @Override
        public Optional<FairValueMeasurement> fairValueMeasurement(
            String contractId, AsAtBoundary boundary) {
            measurementCalls++;
            measurementsAsked.add(contractId);
            return Optional.ofNullable(measurements.get(contractId));
        }

        @Override
        public Optional<EclDiscountPosition> eclDiscountPosition(
            String contractId, AsAtBoundary boundary) {
            return Optional.ofNullable(positions.get(contractId));
        }
    }
}
