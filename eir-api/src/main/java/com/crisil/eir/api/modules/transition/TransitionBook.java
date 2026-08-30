package com.crisil.eir.api.modules.transition;

import com.crisil.eir.api.store.Seed;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.transition.BelowMarketOrigination;
import com.crisil.eir.policy.transition.ContractMigrationState;
import com.crisil.eir.policy.transition.DayOneDifferenceDestination;
import com.crisil.eir.policy.transition.DeemedEirBasis;
import com.crisil.eir.policy.transition.DeemedEirDerivation;
import com.crisil.eir.policy.transition.EclDiscountBasis;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationMethod;
import com.crisil.eir.policy.transition.MigrationTracker;
import com.crisil.eir.policy.transition.TransitionFairValue;
import com.crisil.eir.policy.transition.TransitionValuationRun;
import com.crisil.eir.policy.transition.ValuationTechnique;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The transition programme's state, as the five endpoints of 06 § 8 see it.
 *
 * <p><b>Why a store here and not in {@code EirService}.</b> The transition data has nothing to do
 * with a monthly run: it is a one-off valuation at 1 April 2027, a migration queue with a 2030
 * deadline, and a per-contract discount basis. None of it is read by {@code /api/run} or
 * {@code /api/close}, and putting it in the run's service would have made a programme dataset look
 * like part of the period's working papers.
 *
 * <p><b>Every figure here is a documented input, not a computed one.</b> 08 Phase 4 is explicit:
 * "the fair value is an input, not a computation … nothing here values a loan". So the amounts below
 * are what a valuation team recorded, stated once, and the engine's job is to compute the difference
 * to opening retained earnings, control the evidence, and refuse to publish where the evidence is
 * absent. The seed is chosen so every outcome the five endpoints can report is reachable from a cold
 * start — the same reason {@link Seed} carries a Stage 3 contract and a contract with no opening
 * state rather than three performing ones.
 *
 * <p><b>The seven-contract legacy population.</b> C-0001 to C-0007, of which the first three are the
 * demonstration book's own contracts — a legacy book at 1 April 2027 naturally contains the
 * exposures the engine is now running. Their fair values are not derived from the amortisation
 * engine and are not reference case 1's figures except where noted; they are a valuation team's
 * inputs.
 *
 * <table>
 *   <caption>The day-1 valuation at 1 April 2027</caption>
 *   <tr><th>Contract</th><th>Carrying</th><th>Fair value</th><th>Technique</th><th>Difference</th></tr>
 *   <tr><td>C-0001</td><td>1,000,000.00</td><td>962,500.00</td><td>DCF at 0.0115</td><td>−37,500.00</td></tr>
 *   <tr><td>C-0002</td><td>528,407.32</td><td>528,407.32</td><td>carrying cost, evidenced</td><td>0.00</td></tr>
 *   <tr><td>C-0003</td><td>250,000.00</td><td>250,000.00</td><td>carrying cost, <b>no evidence</b></td><td>withheld</td></tr>
 *   <tr><td>C-0004</td><td>400,000.00</td><td>412,000.00</td><td>quoted price</td><td>+12,000.00</td></tr>
 *   <tr><td>C-0005</td><td>750,000.00</td><td>731,250.00</td><td>DCF at 0.0120</td><td>−18,750.00</td></tr>
 *   <tr><td>C-0006</td><td>300,000.00</td><td>300,000.00</td><td>carrying cost, evidenced</td><td>0.00</td></tr>
 *   <tr><td>C-0007</td><td>150,000.00</td><td>150,000.00</td><td>carrying cost, <b>no evidence</b></td><td>withheld</td></tr>
 * </table>
 *
 * <p>The total, by hand: −37,500.00 + 0.00 + 0.00 + 12,000.00 − 18,750.00 + 0.00 + 0.00 =
 * <b>−44,250.00</b> to opening retained earnings. Negative, which for a legacy book measured at a
 * current market rate is the ordinary direction. It is published only once C-0003's and C-0007's
 * rebuttal evidence is on file, because until then two of the seven differences are nil for the
 * reason TF-1 exists to catch: nothing was measured.
 *
 * <p><b>Not thread-safe, for the same reason {@code EirService} is not.</b> {@code EirServer} runs a
 * single-threaded executor and says so; the mutations here — filing evidence, migrating a cohort —
 * are programme actions taken one at a time by a named person, and a lock in this class would be
 * inventing an answer to a question the server has already answered.
 */
public final class TransitionBook {

    /** ACPIR's effective date, and the date the day-1 valuation of the existing book is struck at. */
    public static final LocalDate TRANSITION_DATE = LocalDate.of(2027, 4, 1);

    /**
     * The below-market originations' contract ids, named here so {@link #seeded(List)} can refuse a
     * collision with them before it becomes a duplicate HTTP route.
     */
    private static final Set<String> BELOW_MARKET_IDS = Set.of("C-0008", "C-0009");

    /**
     * The Board position closing reference § 4's Silence 6 for staff loans.
     *
     * <p>{@link PolicyKind#POLICY_POSITION}, because ACPIR 19 and 20 give no guidance at all on the
     * day-1 difference and {@link BelowMarketOrigination} refuses any other kind of version as the
     * authority — the makers of a fee rule set were not deciding this.
     */
    private static final PolicyVersion DAY_ONE_POSITION = new PolicyVersion(
        "POS-DAY1-2027.1", PolicyKind.POLICY_POSITION,
        "day-1 difference on below-market staff lending is employee benefit cost, not a lending"
            + " loss (reference § 5 item 11, Silence 6)",
        LocalDate.of(2027, 4, 1),
        "policy.maker", "board.secretary", LocalDate.of(2027, 3, 20),
        PolicyVersionStatus.EFFECTIVE);

    /** The valuations, in population order, so a replacement keeps its place in the run. */
    private final Map<String, TransitionFairValue> valuations = new LinkedHashMap<>();

    /** Below-market originations, keyed by contract. Their own day 1, not the transition date. */
    private final Map<String, BelowMarketOrigination> originations = new LinkedHashMap<>();

    /** The migration queue, in the order the plan lists it. Mutated by a migrate call. */
    private final List<LegacyCohort> cohorts = new ArrayList<>();

    /** Derivations, keyed by cohort. Retained when a cohort is reconstructed — see {@link #migrate}. */
    private final Map<String, DeemedEirDerivation> derivations = new LinkedHashMap<>();

    /** Recorded ECL discount bases. One per contract; C-0007's is deliberately absent. */
    private final List<ContractMigrationState> migrationStates = new ArrayList<>();

    /**
     * Who migrated each cohort, keyed by cohort name.
     *
     * <p>A map rather than a set of names. The endpoint requires {@code migratedBy} and the first cut
     * recorded only that a migration had happened, so the cohort listing reported
     * {@code migrationApplied: true} with no trace of who did it — demanding an identity and then
     * discarding it, in a module whose every other record carries a maker and a checker.
     */
    private final Map<String, String> migratedBy = new LinkedHashMap<>();

    /** Evidence filed since the server started, newest last, for the run to report what changed. */
    private final List<String> evidenceFiled = new ArrayList<>();

    private final List<String> populationIds;

    private TransitionBook(List<String> populationIds) {
        this.populationIds = List.copyOf(populationIds);
    }

    // ================================================================= the seed

    /** The transition programme's opening position. See the class javadoc for every figure. */
    public static TransitionBook seeded() {
        return seeded(List.of());
    }

    /**
     * The seeded programme, plus contracts the contract master carries that were never presented.
     *
     * <p><b>Why this parameter exists.</b> 08 Phase 4's exit gate has two legs — the run "completes
     * over the full book" <em>and</em> carries a rebuttal reference wherever the presumption was
     * applied — and the run itself cannot check the first: it has no way to know that a contract
     * exists and was never presented to it, which is why 08 says "the completeness half needs a
     * caller that can see both". The module is that caller. But with the default seed the valued
     * population and the legacy book size are the same seven contracts, so
     * {@code completeOverTheBook} would be true for every input the endpoint could ever be given —
     * a leg of a gate that cannot fail, which is worse than an absent one because a reader counts it
     * as coverage. Naming contracts the master carries and the valuation run never saw is the
     * failing input, and it is a real data condition rather than a contrivance: it is the same shape
     * as {@link Seed#CONTRACT_WITHOUT_STATE}, a row the population names and the master does not
     * carry, with the two systems the other way round.
     *
     * @param contractsNeverPresented ids the contract master holds with no valuation and no recorded
     *     ECL discount basis; empty for the demonstration book
     */
    public static TransitionBook seeded(List<String> contractsNeverPresented) {
        Objects.requireNonNull(contractsNeverPresented, "contractsNeverPresented");
        List<String> population = new ArrayList<>(
            List.of("C-0001", "C-0002", "C-0003", "C-0004", "C-0005", "C-0006", "C-0007"));
        for (String contractId : contractsNeverPresented) {
            // Checked against the below-market ids as well as the valued ones. The first cut checked
            // only the valuation population, and a never-presented "C-0008" then collided with the
            // staff loan: the module registered one HTTP path twice, HttpServer.createContext
            // refused the duplicate, and the exception came out of the EirServer constructor — so
            // the whole server failed to start, not just this module's routes.
            if (population.contains(contractId) || BELOW_MARKET_IDS.contains(contractId)) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " is already in the seeded transition book (valued"
                        + " population " + population + ", below-market originations "
                        + BELOW_MARKET_IDS + "), so it cannot also be a contract that was never"
                        + " presented");
            }
            population.add(contractId);
        }
        TransitionBook book = new TransitionBook(population);
        book.seedValuations();
        book.seedBelowMarketOriginations();
        book.seedCohorts();
        book.seedMigrationStates();
        return book;
    }

    private void seedValuations() {
        // Discounted at 0.0115 monthly against a 0.0100 contractual coupon: measured below par,
        // which is what a legacy fixed-rate book does when current market rates have risen.
        put(dcf("C-0001", "1000000.00", "962500.00", "0.011500000000"));
        // The paragraph 19 presumption, applied properly: carrying cost taken as best evidence with
        // the retail housing rebuttal file named. 528,407.32 is reference case 1's month-13 opening
        // gross carrying amount, reused here as a plausible carrying figure and nothing more.
        put(presumed("C-0002", "528407.32", "WP-ACPIR19-HL-RETAIL-01", "valuation.reviewer"));
        // The presumption with nothing behind it. This is the TF-1 condition and the reason the
        // difference is withheld rather than published as 0.00: a nil difference because carrying
        // cost was assumed is indistinguishable from a nil difference that was measured, and the
        // rebuttal evidence reference is the only thing that separates the two.
        put(presumed("C-0003", "250000.00", null, "valuation.reviewer"));
        put(quoted("C-0004", "400000.00", "412000.00"));
        put(dcf("C-0005", "750000.00", "731250.00", "0.012000000000"));
        put(presumed("C-0006", "300000.00", "WP-ACPIR19-GOLD-02", "valuation.reviewer"));
        // Unevidenced and unreviewed: the worst row in the population, and the one a programme team
        // works first. Two unevidenced rows rather than one on purpose — TF-1 publishes a count as
        // its deviation, and a single bad row is exactly the fixture on which a control that always
        // reports 1 looks correct.
        put(presumed("C-0007", "150000.00", null, null));
    }

    private TransitionFairValue dcf(
        String contractId, String carrying, String fairValue, String monthlyRate) {
        return new TransitionFairValue(contractId, TRANSITION_DATE,
            Money.inr(carrying), Money.inr(fairValue),
            ValuationTechnique.DISCOUNTED_CASH_FLOW,
            Rate.periodic(new BigDecimal(monthlyRate), 12), null,
            "valuation.analyst", "valuation.reviewer");
    }

    private TransitionFairValue quoted(String contractId, String carrying, String fairValue) {
        return new TransitionFairValue(contractId, TRANSITION_DATE,
            Money.inr(carrying), Money.inr(fairValue),
            ValuationTechnique.QUOTED_PRICE, null, null,
            "valuation.analyst", "valuation.reviewer");
    }

    private TransitionFairValue presumed(
        String contractId, String carrying, String evidenceRef, String reviewedBy) {
        return new TransitionFairValue(contractId, TRANSITION_DATE,
            Money.inr(carrying), Money.inr(carrying),
            ValuationTechnique.CARRYING_COST_AS_BEST_EVIDENCE, null, evidenceRef,
            "valuation.analyst", reviewedBy);
    }

    private void put(TransitionFairValue valuation) {
        valuations.put(valuation.contractId(), valuation);
    }

    private void seedBelowMarketOriginations() {
        // A staff housing loan written in June 2027 — after the transition, so its day 1 is its own
        // and the shortfall is a current-period cost. 2,000,000.00 disbursed against a fair value of
        // 1,640,000.00 gives a shortfall of 360,000.00; the concession spread is
        // 0.008000000000 − 0.004250000000 = 0.003750000000 monthly. Destination approved, so BM-1
        // passes on this one.
        BelowMarketOrigination staffLoan = new BelowMarketOrigination(
            "C-0008", LocalDate.of(2027, 6, 30),
            Money.inr("2000000.00"), Money.inr("1640000.00"),
            Rate.periodic(new BigDecimal("0.008000000000"), 12),
            Rate.periodic(new BigDecimal("0.004250000000"), 12),
            DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, DAY_ONE_POSITION,
            "valuation.analyst", "valuation.reviewer");
        originations.put(staffLoan.contractId(), staffLoan);

        // Directed concessional lending, and the BM-1 failure: 5,000,000.00 disbursed against a fair
        // value of 4,750,000.00 is a shortfall of 250,000.00 with no destination chosen and no Board
        // position cited. The shortfall is measured and is published; where it goes is not decided,
        // and that is the whole distinction between this refusal and C-0003's.
        BelowMarketOrigination directed = new BelowMarketOrigination(
            "C-0009", LocalDate.of(2027, 9, 30),
            Money.inr("5000000.00"), Money.inr("4750000.00"),
            Rate.periodic(new BigDecimal("0.009000000000"), 12),
            Rate.periodic(new BigDecimal("0.007500000000"), 12),
            null, null,
            "valuation.analyst", "valuation.reviewer");
        originations.put(directed.contractId(), directed);
    }

    private void seedCohorts() {
        LocalDate struck = LocalDate.of(2026, 11, 30);
        // Priority 1, survives to 2034, fully reconstructed. The right answer in the right place.
        cohorts.add(new LegacyCohort("HL-PRE-2020",
            "housing loans disbursed before 1 April 2020, fixed rate, no restructure event",
            struck, LocalDate.of(2034, 3, 31), 1, MigrationMethod.FULL_RECONSTRUCTION,
            412_905L, "transition.committee"));
        // Priority 2 and the roadmap's exact trap, made concrete: 1,204,338 contracts — the largest
        // cohort in the book — queued second and running off in June 2029. Ordered by size it goes
        // first, and it is the one that never needs a reconstructed rate at all. LegacyCohort
        // reports it as wasted reconstruction effort; LC-1 fails on what it pushes behind it.
        cohorts.add(new LegacyCohort("VEHICLE-2021-2023",
            "vehicle loans disbursed between 1 April 2021 and 31 March 2023",
            struck, LocalDate.of(2029, 6, 30), 2, MigrationMethod.FULL_RECONSTRUCTION,
            1_204_338L, "transition.committee"));
        // Priority 3, survives to 2031, and therefore queued behind a cohort that will have gone:
        // the LC-1 inversion. Deemed, on a derivation nobody has approved: the DE-1 breach.
        cohorts.add(new LegacyCohort("GOLD-REVOLVING",
            "gold loans on the revolving renewal product, all vintages",
            struck, LocalDate.of(2031, 12, 31), 3, MigrationMethod.DEEMED_EIR,
            233_150L, "transition.committee"));
        // Runs off exactly ON 31 March 2030, which the deadline test treats as having met the
        // obligation — an exposure that ends on the deadline was on whatever basis it was on for its
        // whole life. Kept in the seed because a boundary nobody exercises is a boundary nobody
        // knows the sign of. Definition unapproved, so the listing has an unapproved row to show.
        cohorts.add(new LegacyCohort("SME-TERM-PRE-2018",
            "SME term loans disbursed before 1 April 2018 on the retired origination platform",
            struck, LegacyCohort.ACPIR_50_DEADLINE, 4, MigrationMethod.DEEMED_EIR,
            12_004L, null));

        // GOLD-REVOLVING's derivation: prepared, not approved. DE-1 is the assertion that no cohort
        // is MEASURED on a rate nobody signed, and an unapproved derivation in flight is the normal
        // state of one — which is why DeemedEirDerivation constructs it and the invariant reports it.
        derivations.put("GOLD-REVOLVING", new DeemedEirDerivation(
            "GOLD-REVOLVING", null,
            Rate.periodic(new BigDecimal("0.014500000000"), 12),
            DeemedEirBasis.ORIGINATION_PRICING_GRID,
            "the gold-loan origination system was retired in 2019 and its fee tables were not"
                + " migrated, so the fees integral to each renewal cannot be reconstructed",
            "the pricing grid in force for gold-loan renewals in FY2018-19, weighted by outstanding"
                + " at 31 March 2027",
            "WP-DEEMED-GOLD-01", "transition.analyst", null, null));
        derivations.put("SME-TERM-PRE-2018", new DeemedEirDerivation(
            "SME-TERM-PRE-2018", null,
            Rate.periodic(new BigDecimal("0.011200000000"), 12),
            DeemedEirBasis.WEIGHTED_AVERAGE_OF_RECONSTRUCTED_COHORT,
            "the origination platform was decommissioned in 2018 and its fee ledger was archived in"
                + " a form the bank cannot attest to",
            "the weighted average EIR of the SME term cohort disbursed 2018-2020, which was fully"
                + " reconstructed, applied to the pre-2018 cohort",
            "WP-DEEMED-SME-02", "transition.analyst",
            "transition.committee", LocalDate.of(2027, 2, 15)));
    }

    /**
     * The recorded ECL discount bases: six of the seven contracts, with C-0007's absent.
     *
     * <p>Chosen so that the two obligations produce <b>different</b> figures, because a population
     * where they happen to agree is one on which a report that merged them would look right. By
     * hand: ACPIR 21 is outstanding on C-0004 and C-0005 (two), ACPIR 50 on C-0002, C-0003 and
     * C-0004 (three), the concession population — met 21, awaiting 50 — is C-0002 and C-0003 (two),
     * and C-0005 has its ECL on the EIR while its interest is not, which is the reverse gap a phased
     * cutover produces. C-0007 has no recorded basis at all, which is the failure 04 § 6 gives the
     * discount basis its own table to prevent: its position is unknown, not outstanding.
     */
    private void seedMigrationStates() {
        migrationStates.add(ContractMigrationState.migrated("C-0001", Seed.PERIOD_ID,
            "EIRC-2027-000001", Seed.EIR, LocalDate.of(2027, 4, 1), "WP-ECL-BASIS-01"));
        migrationStates.add(
            ContractMigrationState.onInterimBasis("C-0002", Seed.PERIOD_ID, true));
        migrationStates.add(
            ContractMigrationState.onInterimBasis("C-0003", Seed.PERIOD_ID, true));
        migrationStates.add(
            ContractMigrationState.onInterimBasis("C-0004", Seed.PERIOD_ID, false));
        // ECL ahead of interest. Not refused by ContractMigrationState and not an invariant here: an
        // EIR has to exist before the ECL model can use it, so a contract can be reconstructed for
        // discounting before recognition is switched over. Surfaced as a sequencing signal.
        migrationStates.add(new ContractMigrationState("C-0005", Seed.PERIOD_ID, false,
            EclDiscountBasis.EIR, "EIRC-2027-000005", Seed.EIR,
            LocalDate.of(2027, 7, 31), "WP-ECL-BASIS-05"));
        migrationStates.add(ContractMigrationState.migrated("C-0006", Seed.PERIOD_ID,
            "EIRC-2027-000006", Seed.EIR, LocalDate.of(2028, 1, 31), "WP-ECL-BASIS-06"));
        // C-0007: nothing. See the method javadoc.
    }

    // ================================================================= reading

    /** The ACPIR 19 run over the whole valued population, at the transition date. */
    public TransitionValuationRun valuationRun() {
        return new TransitionValuationRun(TRANSITION_DATE, List.copyOf(valuations.values()));
    }

    /**
     * The contracts this book can answer a fair value for: valued, or a below-market origination.
     *
     * <p><b>Not the population.</b> The population includes contracts the contract master carries
     * that were never presented to the valuation run — that is the whole point of
     * {@link #seeded(List)} — and this book has nothing to say about those. Registering a route for
     * one produced a handler that could only throw, so a caller asking about a contract the engine
     * legitimately knows nothing about got a 500 naming an internal invariant instead of a 404.
     */
    public List<String> answerableContractIds() {
        List<String> ids = new ArrayList<>(valuations.keySet());
        for (String contractId : originations.keySet()) {
            if (!ids.contains(contractId)) {
                ids.add(contractId);
            }
        }
        return List.copyOf(ids);
    }

    /** The valuation recorded for a contract, if this book carries one. */
    public Optional<TransitionFairValue> valuation(String contractId) {
        return Optional.ofNullable(valuations.get(contractId));
    }

    /** The below-market origination recorded for a contract, if this book carries one. */
    public Optional<BelowMarketOrigination> origination(String contractId) {
        return Optional.ofNullable(originations.get(contractId));
    }

    /** Every below-market origination, for BM-1 over the population. */
    public List<BelowMarketOrigination> originations() {
        return List.copyOf(originations.values());
    }

    /**
     * How many contracts the legacy book holds, from the contract master.
     *
     * <p>Stated separately from the valuation count on purpose. {@code TransitionValuationRun}
     * cannot know that a contract exists and was never presented to it, so 08 Phase 4 says the
     * completeness half of the exit gate "needs a caller that can see both" — this module is that
     * caller, and this is the second number it supplies to
     * {@link TransitionValuationRun#coverageAgainst(long)}.
     */
    public long legacyBookSize() {
        return populationIds.size();
    }

    /** The contracts the legacy book holds, in order. */
    public List<String> populationIds() {
        return populationIds;
    }

    /** The migration queue and the derivations backing its deemed cohorts. */
    public LegacyMigrationPlan plan() {
        return LegacyMigrationPlan.of(List.copyOf(cohorts), List.copyOf(derivations.values()));
    }

    /** The cohorts, in queue-declaration order. */
    public List<LegacyCohort> cohorts() {
        return List.copyOf(cohorts);
    }

    /** The derivation on file for a cohort, if any — including one a reconstruction superseded. */
    public Optional<DeemedEirDerivation> derivationFor(String cohortName) {
        return Optional.ofNullable(derivations.get(cohortName));
    }

    /** Whether a migrate call has been applied to this cohort since the server started. */
    public boolean migrationApplied(String cohortName) {
        return migratedBy.containsKey(cohortName);
    }

    /** Who applied it, or null where no migrate call has been made against this cohort. */
    public String migratedBy(String cohortName) {
        return migratedBy.get(cohortName);
    }

    /** The ACPIR 21 and ACPIR 50 tracker over the legacy population. */
    public MigrationTracker tracker() {
        return MigrationTracker.over(List.copyOf(migrationStates), legacyBookSize());
    }

    /** The recorded bases, in population order. */
    public List<ContractMigrationState> migrationStates() {
        return List.copyOf(migrationStates);
    }

    /** Contracts in the population with no recorded ECL discount basis, named. */
    public List<String> untrackedContracts() {
        Set<String> recorded = new LinkedHashSet<>();
        for (ContractMigrationState state : migrationStates) {
            recorded.add(state.contractId());
        }
        List<String> untracked = new ArrayList<>();
        for (String contractId : populationIds) {
            if (!recorded.contains(contractId)) {
                untracked.add(contractId);
            }
        }
        return List.copyOf(untracked);
    }

    /** What has been filed through this server since it started, for the run to report. */
    public List<String> evidenceFiled() {
        return List.copyOf(evidenceFiled);
    }

    // ================================================================= writing

    /**
     * Files a paragraph 19 rebuttal evidence reference against a valuation.
     *
     * <p>08 Phase 4 is explicit that the evidence file is "built during FY27, <b>not</b> at the
     * transition date", so a valuation acquiring its reference after the run first refused is the
     * intended workflow rather than a repair. It is the same shape as working C-0003's exception
     * before a close: the control is red, a named person does the work, and the run is re-performed.
     *
     * @throws IllegalArgumentException where the contract is not valued here, or its technique does
     *     not rest on the presumption — a reference on a quoted-price row documents a decision that
     *     was never taken, and {@link TransitionFairValue} refuses it
     */
    public void fileParagraph19Evidence(String contractId, String evidenceRef) {
        Objects.requireNonNull(evidenceRef, "evidenceRef");
        TransitionFairValue existing = valuations.get(contractId);
        if (existing == null) {
            throw new IllegalArgumentException(
                "contract " + contractId + " carries no transition valuation, so there is nothing"
                    + " for evidence '" + evidenceRef + "' to support; the valued population is "
                    + valuations.keySet());
        }
        if (!existing.appliesParagraph19Presumption()) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is valued by " + existing.technique() + ", which does"
                    + " not rest on the paragraph 19 presumption; a rebuttal evidence reference on"
                    + " it would document a decision that was not taken");
        }
        // LinkedHashMap.put on an existing key keeps its position, so the run's population order —
        // and therefore the order of the per-contract evidence rows — does not move under a filing.
        valuations.put(contractId, new TransitionFairValue(
            existing.contractId(), existing.transitionDate(),
            existing.preTransitionCarryingAmount(), existing.fairValue(),
            existing.technique(), existing.discountRateUsed(), evidenceRef,
            existing.measuredBy(), existing.reviewedBy()));
        evidenceFiled.add(contractId + " -> " + evidenceRef);
    }

    /**
     * Migrates a cohort onto the EIR by full reconstruction or on a deemed rate (FR-908, FR-909).
     *
     * <p><b>A prior derivation is retained, not deleted.</b> Where a cohort moves from
     * {@code DEEMED_EIR} to {@code FULL_RECONSTRUCTION} the old derivation stops mattering to DE-1
     * on its own — the invariant asks only about cohorts <em>measured</em> on an assumption — so
     * there is no reason to destroy the record of what the bank did before it reconstructed the
     * cohort, and every reason not to.
     *
     * @param cohortName the cohort, which must already be in the plan
     * @param method     how it is brought onto the EIR
     * @param derivation the deemed rate's derivation, or null to keep whatever is on file
     * @param actor      who applied the migration; recorded, not merely required
     * @return the cohort in its new state
     * @throws IllegalArgumentException where the cohort is not in the plan, or a deemed migration
     *     has no derivation on file and none supplied
     */
    public LegacyCohort migrate(
        String cohortName, MigrationMethod method, DeemedEirDerivation derivation, String actor) {
        Objects.requireNonNull(cohortName, "cohortName");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(actor, "actor");
        int index = indexOf(cohortName);
        if (index < 0) {
            throw new IllegalArgumentException(
                "no cohort named " + cohortName + " is in the migration plan; the plan holds "
                    + cohorts.stream().map(LegacyCohort::cohortName).toList());
        }
        if (derivation != null) {
            derivations.put(cohortName, derivation);
        }
        if (method.restsOnAnAssumption() && !derivations.containsKey(cohortName)) {
            // Refused rather than reported. DE-1 asserts that a deemed cohort's derivation is
            // APPROVED; a deemed cohort with no derivation at all has no rate to recognise income
            // on, so there is no measurement to report an invariant about.
            throw new IllegalArgumentException(
                "cohort " + cohortName + " cannot be migrated on a deemed EIR with no derivation on"
                    + " file and none supplied; a deemed rate with no documented basis and no stated"
                    + " reason reconstruction failed is a rate nobody tried to reconstruct");
        }
        LegacyCohort existing = cohorts.get(index);
        LegacyCohort migrated = new LegacyCohort(
            existing.cohortName(), existing.definition(), existing.definedOn(),
            existing.expectedRunoffDate(), existing.migrationPriority(), method,
            existing.contractCount(), existing.approvedBy());
        cohorts.set(index, migrated);
        migratedBy.put(cohortName, actor);
        return migrated;
    }

    private int indexOf(String cohortName) {
        for (int index = 0; index < cohorts.size(); index++) {
            if (cohorts.get(index).cohortName().equals(cohortName)) {
                return index;
            }
        }
        return -1;
    }

    // ================================================================= coverage

    /**
     * ACPIR 21's position: the loan under the EIR regime for recognition.
     *
     * <p>Counted over the <em>tracked</em> population only, with the untracked contracts reported
     * beside it rather than folded in. A contract with no recorded basis is not outstanding on an
     * obligation — its position is unknown — and adding it to an outstanding count would make an
     * unknown look like a scheduled piece of work.
     */
    public DeadlineObligation acpir21Coverage() {
        List<String> outstanding = new ArrayList<>();
        for (ContractMigrationState state : migrationStates) {
            if (!state.satisfiesAcpir21()) {
                outstanding.add(state.contractId());
            }
        }
        outstanding.sort(String::compareTo);
        return new DeadlineObligation("ACPIR 21",
            "interest recognised on the effective interest rate",
            LegacyCohort.ACPIR_50_DEADLINE, migrationStates.size(), outstanding);
    }

    /**
     * ACPIR 50's position: the ECL discounted at the EIR rather than at the interim contractual rate.
     *
     * <p><b>Not {@link MigrationTracker#outstandingAcpir50Migrations()}.</b> That method counts the
     * <em>concession</em> population — contracts that satisfy ACPIR 21 and not ACPIR 50 — which is
     * what a programme schedules against, and it deliberately excludes a contract that is outside
     * the EIR regime altogether. This figure is the obligation: every tracked contract whose ECL is
     * not yet discounted at the EIR, whatever its ACPIR 21 position. Both are published, because
     * they answer different questions and on this population they differ — three against two.
     */
    public DeadlineObligation acpir50Coverage() {
        List<String> outstanding = new ArrayList<>();
        for (ContractMigrationState state : migrationStates) {
            if (!state.satisfiesAcpir50()) {
                outstanding.add(state.contractId());
            }
        }
        outstanding.sort(String::compareTo);
        return new DeadlineObligation("ACPIR 50",
            "expected credit loss discounted at the effective interest rate, not at the interim"
                + " contractual rate",
            LegacyCohort.ACPIR_50_DEADLINE, migrationStates.size(), outstanding);
    }
}
