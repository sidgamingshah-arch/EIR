package com.crisil.eir.application.onboarding;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.calc.projection.CashflowProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolver;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleSet;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestRecord;
import com.crisil.eir.policy.tier.TierAssignment;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs and in-memory fakes for the onboarding tests. <b>Inputs only.</b>
 *
 * <p>No expected figure is computed here, and none is computed by asking the pipeline. Every money
 * amount and every rate this package asserts is quoted from
 * {@code docs/reference-cases/case-01-emi-loan-with-fees.md} and cross-checked independently — see
 * {@link #CASE1_EIR_PERIODIC} for the cross-check and its arithmetic. That is the discipline
 * {@code ReferenceCaseFixtures} states one module down: "a silent edit to an expected value is how a
 * regression becomes a specification."
 *
 * <h2>The counting fakes are the point of this file</h2>
 *
 * <p>{@link CountingProjector} and {@link CountingSolver} wrap the real {@code eir-calc} seams and
 * count calls. They exist so that "the measurement gate runs before any projection or solve" can be
 * asserted as a fact about what was invoked rather than inferred from what came back. A test that
 * only checked the output would pass on a pipeline that projected, solved, and then threw the answer
 * away — which is the shape 05 § 3.1 calls "waste, and worse".
 *
 * <h2>Dates are constants</h2>
 *
 * <p>Nothing here reads a clock, for the reason 03 § 1.1 gives and ADR-0003 makes concrete: a
 * fixture that derived a disbursement date from today would be asserting a moving target. The
 * {@link AsAtBoundary} is built from explicit constants for the same reason.
 */
final class OnboardingFixtures {

    /** Reference case 1's inception date, and the anchor of every vector here. */
    static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);

    /** One whole period after disbursement — the condition periodic indexing needs. */
    static final LocalDate FIRST_DUE = LocalDate.of(2026, 5, 1);

    /**
     * The system-time boundary the fakes are read at.
     *
     * <p>An explicit instant, not {@code Instant.now()}. A test that pinned "now" would still pass
     * and would stop proving that the pipeline never reads one.
     */
    static final Instant KNOWN_AT = Instant.parse("2026-04-01T18:30:00Z");

    /** The fee rule set version's effective date: before every posting date used here. */
    static final LocalDate RULES_EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);

    /** Reference case 1's principal advanced. */
    static final Money PRINCIPAL = Money.inr("1000000");

    /** 12% p.a. nominal, monthly compounding = 1% per month. Never 12% divided by 12. */
    static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(new BigDecimal("0.01"));

    /** Case 1's processing fee received (ACPIR 52, integral). */
    static final Money PROCESSING_FEE = Money.inr("15000");

    /** Case 1's DSA commission paid (ACPIR 53, integral, SELLING). */
    static final Money DSA_COMMISSION = Money.inr("10000");

    /** 15,000 received less 10,000 paid: 5,000 of integral fee income. */
    static final Money NET_INTEGRAL_FEE = Money.inr("5000");

    /**
     * Case 1's initial gross carrying amount: <b>995,000.00</b>.
     *
     * <p>Quoted from the fixture's Derived table. Derived by hand: 1,000,000 advanced, less 15,000
     * of fee received, plus 10,000 of commission paid = 995,000 of net cash outflow at inception,
     * which is GCA₀ by invariant IC-1.
     */
    static final Money CASE1_INITIAL_GCA = Money.inr("995000.00");

    /**
     * Case 1's billed EMI: <b>47,073.47</b>.
     *
     * <p>The unrounded annuity is 47,073.472223 — {@code 1,000,000 * 0.01 / (1 - 1.01^-24)} — billed
     * down to paise. Both figures are in the fixture's Derived table.
     */
    static final Money CASE1_EMI = Money.inr("47073.47");

    /**
     * Case 1's EIR per month, at storage precision: <b>0.010421491790</b> (1.04214918%).
     *
     * <p>Quoted from the fixture, and cross-checked against the definition rather than against this
     * engine. The rate is the {@code r} solving
     *
     * <pre>
     *   sum(k = 1..24) 47073.47 / (1 + r)^k = 995000
     * </pre>
     *
     * <p>A 400-step bisection at 40 significant digits in Python gives
     * {@code r = 0.01042149179024140180974841649...}, i.e. 1.042149179% per month, 12.505790% nominal
     * p.a. (x12) and 13.248094% effective p.a. — the three figures the fixture prints. At
     * {@code Precision.RATE_SCALE} (12dp) that stores as 0.010421491790.
     */
    static final BigDecimal CASE1_EIR_PERIODIC = new BigDecimal("0.010421491790");

    /** Case 1's EIR expressed as an effective annual percentage: <b>13.248094</b>. */
    static final BigDecimal CASE1_EIR_EFFECTIVE_ANNUAL_PERCENT = new BigDecimal("13.248094");

    /** A fee code the rule set maps to {@code INTEGRAL} for a fee received. */
    static final String PROCESSING_FEE_CODE = "PROCESSING_FEE";

    /** A fee code the rule set maps to {@code INTEGRAL} for a cost paid. */
    static final String DSA_COMMISSION_CODE = "DSA_COMMISSION";

    /** A fee code the rule set maps to {@code EXCLUDED_BY_DIRECTION} — RBI's 2023 direction. */
    static final String PENAL_CHARGE_CODE = "PENAL_CHG";

    /** A fee code no version of the rule set states. FR-202's refusal. */
    static final String UNMAPPED_CODE = "MYSTERY_FEE";

    // ------------------------------------------- the tier gate's second gate (FR-411, FR-412)

    /** The product code of the Tier 3 short-tenor exposure below. */
    static final String WCDL_PRODUCT = "WCDL";

    /**
     * The population key the equivalence-test register is loaded under for that exposure.
     *
     * <p>Written out in full rather than computed, so that the test states the key it expects
     * instead of asking the code under test what key it produced. Product then segment, colon
     * separated, per {@code EquivalenceTestSubjects.populationIdFor}.
     */
    static final String WCDL_POPULATION = "WCDL:RETAIL";

    /** The product code of the Case 9 zero-coupon bond below. */
    static final String ZERO_COUPON_PRODUCT = "ZCB";

    /** Its population key: a wholesale holding, so not the retail population above. */
    static final String ZERO_COUPON_POPULATION = "ZCB:WHOLESALE";

    /** Reference case 9's face value at maturity: <b>1,000,000.00</b>. */
    static final Money CASE9_FACE_VALUE = Money.inr("1000000");

    /**
     * Reference case 9's issue price: <b>315,241.70</b>.
     *
     * <p>Quoted from the reference case and independently derivable: 1,000,000 discounted at 8%
     * effective annual over fifteen whole years is {@code 1,000,000 / 1.08^15}. {@code 1.08^15 =
     * 3.172169113645344...}, so the price is 315,241.704965890... which presents as 315,241.70.
     */
    static final Money CASE9_ISSUE_PRICE = Money.inr("315241.70");

    /**
     * Reference case 9's discount to accrete: <b>684,758.30</b>.
     *
     * <p>1,000,000.00 face less 315,241.70 paid, by hand. The whole of the instrument's return, all
     * of it accretion — which is why FR-412 refuses Tier 3 for it at any tenor, and why straight
     * line takes 45,650.55 a year (684,758.30 / 15) against the EIR method's 25,219.34 in year one:
     * an 81.0% overstatement of year-one income.
     */
    static final Money CASE9_DISCOUNT_TO_ACCRETE = Money.inr("684758.30");

    /** 8.00% effective annual, the yield reference case 9's bond is quoted at. */
    static final Rate EIGHT_PERCENT_ANNUAL = Rate.annualEffective(new BigDecimal("0.08"));

    /**
     * The Board-approved equivalence-test tolerance used here: <b>25 bps</b>.
     *
     * <p>Arbitrary, and only ever compared against a delta this file also states, so no assertion
     * depends on the figure itself. 03 § 10.2 item 2 requires the threshold to be Board approved and
     * {@code EquivalenceTestRecord} requires an approver named, which is what the constant pair
     * models.
     */
    static final BigDecimal BOARD_THRESHOLD_BPS = new BigDecimal("25");

    /** Who approved it. {@code EquivalenceTestRecord} refuses a blank approver. */
    static final String THRESHOLD_APPROVER = "board.audit.committee";

    private OnboardingFixtures() {
    }

    /** A live boundary at Case 1's inception date. */
    static AsAtBoundary boundary() {
        return AsAtBoundary.live(DISBURSEMENT, KNOWN_AT);
    }

    /**
     * Reference case 1's loan: 1,000,000 at 12% p.a. nominal monthly over 24 EMIs.
     *
     * <p>30/360 bond basis is the Indian term-loan convention; on this clean monthly schedule the
     * periodic index is exactly equivalent and the day count never gets used.
     */
    static ContractTerms case1Terms() {
        return ContractTerms.of(PRINCIPAL, ONE_PERCENT_MONTHLY, 24, 12, DISBURSEMENT, FIRST_DUE,
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.ANNUITY_EMI, RateType.FIXED);
    }

    /** Case 1's two postings, unclassified, as a feed sends them. */
    static List<FeeSubmission> case1Fees() {
        return List.of(
            FeeSubmission.received(PROCESSING_FEE_CODE, PROCESSING_FEE, DISBURSEMENT),
            FeeSubmission.paid(DSA_COMMISSION_CODE, DSA_COMMISSION, DISBURSEMENT, "SELLING"));
    }

    /**
     * The fee taxonomy: four codes, four outcomes.
     *
     * <p>{@link #PROCESSING_FEE_CODE} and {@link #DSA_COMMISSION_CODE} are integral, per ACPIR 52
     * and 53 and reference case 1. {@link #PENAL_CHARGE_CODE} is
     * {@code EXCLUDED_BY_DIRECTION}, which is what RBI's 2023 direction requires and what the
     * pipeline must reject at the boundary rather than let {@code FeePosting} throw over.
     * {@link #UNMAPPED_CODE} is deliberately absent so FR-202's refusal is reachable — a rule set
     * with a global catch-all would make the exception queue empty because nothing can miss.
     */
    static FeeRuleSet ruleSet() {
        return new FeeRuleSet(
            approved("FEE-2026.1", PolicyKind.FEE_RULE_SET, RULES_EFFECTIVE_FROM),
            List.of(
                FeeRule.catchAll(PROCESSING_FEE_CODE, RULES_EFFECTIVE_FROM,
                    FeeClassification.INTEGRAL, "ACPIR 52: origination fee integral to the yield"),
                FeeRule.catchAll(DSA_COMMISSION_CODE, RULES_EFFECTIVE_FROM,
                    FeeClassification.INTEGRAL,
                    "ACPIR 53: selling-agent incentive capitalised"),
                FeeRule.catchAll(PENAL_CHARGE_CODE, RULES_EFFECTIVE_FROM,
                    FeeClassification.EXCLUDED_BY_DIRECTION,
                    "RBI 2023 penal charges direction: excluded from the EIR entirely")));
    }

    /** A resolver over {@link #ruleSet()}. */
    static FeeClassificationResolver feeRules() {
        return new FeeClassificationResolver(ruleSet());
    }

    /**
     * The § 10 materiality gate with a 500,000,000 wholesale Board threshold.
     *
     * <p>The figure is arbitrary and only the wholesale limb reads it; reference case 1 is retail at
     * 24 months, which reaches Tier 2 through {@code TIER_2_RETAIL_OR_MSME_LONG_TENOR} and never
     * consults a threshold. The fixture's Terms table says "Tier 2 illustrated at contract level".
     */
    static TierAssignment tierGate() {
        return TierAssignment.under(
            approved("TIER-2026.1", PolicyKind.TIER_ASSIGNMENT, RULES_EFFECTIVE_FROM),
            Money.inr("500000000"));
    }

    /** An {@code EFFECTIVE} policy version, maker and checker distinct as {@code PolicyVersion} demands. */
    static PolicyVersion approved(String id, PolicyKind kind, LocalDate effectiveFrom) {
        return new PolicyVersion(id, kind, "under test", effectiveFrom,
            "policy.author", "accounting.policy.owner", effectiveFrom.minusDays(1),
            PolicyVersionStatus.EFFECTIVE);
    }

    /** Reference case 1 as an onboarding request: SPPI passed, amortised cost, retail, 24 months. */
    static OnboardingRequest case1Request(String contractId) {
        return OnboardingRequest.of(contractId, InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST,
                SppiAssessment.passed(DISBURSEMENT, "classification.committee"),
                case1Terms(), TierAssignmentSegment.RETAIL)
            .withFees(case1Fees());
    }

    /** The same loan whose SPPI assessment failed and whose master correctly says FVTPL. */
    static OnboardingRequest sppiFailingRequest(String contractId) {
        return OnboardingRequest.of(contractId, InstrumentClass.LOAN,
            MeasurementCategory.FVTPL,
            SppiAssessment.failed(DISBURSEMENT, "classification.committee"),
            case1Terms(), TierAssignmentSegment.RETAIL)
            .withFees(case1Fees());
    }

    /**
     * A 12-month working-capital demand loan: interest monthly, principal at maturity.
     *
     * <p>Tier 3 by {@code TIER_3_SHORT_TENOR}, and by exactly the boundary § 10 draws. Disbursed
     * 1 Apr 2026 with twelve monthly periods, so {@code maturityDate()} is 1 Apr 2027 and
     * {@code TierAssignmentInput.tenorMonthsBetween} reads twelve months — "≤ 12", which is Tier 3,
     * while {@code TIER_2_RETAIL_OR_MSME_LONG_TENOR}'s "> 12" does not fire. § 10's Tier 3 row names
     * WCDL first.
     *
     * <p>It carries a coupon leg, deliberately: twelve interest flows of 1% of 1,000,000 = 10,000
     * each, 120,000 over the life, repayable at par. So FR-412's absolute refusal does <em>not</em>
     * apply to it and the decision falls to FR-411 and the register — which is what makes it the
     * contract that distinguishes "no test on file" from "test on file and current".
     */
    static ContractTerms wcdlShortTenorTerms() {
        return ContractTerms.of(PRINCIPAL, ONE_PERCENT_MONTHLY, 12, 12, DISBURSEMENT, FIRST_DUE,
            DayCountConvention.THIRTY_360_BOND, ScheduleShape.INTEREST_ONLY_BULLET, RateType.FIXED);
    }

    /** That loan as an onboarding request, keyed to the {@link #WCDL_POPULATION} population. */
    static OnboardingRequest wcdlShortTenorRequest(String contractId) {
        return OnboardingRequest.of(contractId, InstrumentClass.LOAN,
                MeasurementCategory.AMORTISED_COST,
                SppiAssessment.passed(DISBURSEMENT, "classification.committee"),
                wcdlShortTenorTerms(), TierAssignmentSegment.RETAIL)
            .withRuleSetKey(WCDL_PRODUCT, "IN-MUM");
    }

    /**
     * Reference case 9's bond: 1,000,000 of face, 8% effective annual, fifteen annual periods.
     *
     * <p>{@code DISCOUNT_INSTRUMENT} quotes the face as {@code principal} and derives the price, so
     * the projected leg is one outflow of 315,241.704965890... at inception and one receipt of
     * 1,000,000.00 at maturity, with <b>no interest flow at all</b>. That is the shape FR-412
     * refuses: the entire return is accretion.
     *
     * <p>Annual periods, so {@code dueDate(15)} is 1 Apr 2027 advanced by fourteen years — 1 Apr
     * 2041 — and the original tenor is 180 months.
     *
     * <p><b>30/360 bond basis, and the choice is load-bearing for the price.</b> Case 9 discounts
     * over fifteen <em>whole</em> years, and a single terminal flow makes
     * {@code FlowVector.periodicIndexEligible} false — it requires every period index from 1 to the
     * maximum to be present, and this vector has only period 15 — so the projector prices under
     * actual dating with whatever day count the contract carries. 30/360 reads 1 Apr 2026 to 1 Apr
     * 2041 as 5,400/360 = exactly 15.0 and reproduces the reference case's 315,241.70. ACT/365F
     * reads the same interval as 5,479/365 = 15.0110 — the four leap days — and prices the bond at
     * 314,975.94, which is a defensible money-market convention and is not the figure case 9
     * publishes. The fixture reproduces the reference case.
     */
    static ContractTerms case9ZeroCouponTerms() {
        return ContractTerms.of(CASE9_FACE_VALUE, EIGHT_PERCENT_ANNUAL, 15, 1, DISBURSEMENT,
            DISBURSEMENT.plusYears(1), DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.DISCOUNT_INSTRUMENT, RateType.FIXED);
    }

    /**
     * That bond as an onboarding request, and <b>assigned Tier 3</b>.
     *
     * <p>Wholesale, so the retail long-tenor rule cannot catch it; no sourced exposure, so the
     * wholesale Board-threshold override cannot; and {@code FULLY_COLLATERALISED_LOW_FEE} asserted,
     * which is the § 10 Tier 3 limb carrying no tenor condition. That combination is not contrived —
     * {@code TierAssignmentRule.TIER_3_FULLY_COLLATERALISED_LOW_FEE}'s own javadoc names it as the
     * limb that "read in isolation would sweep a 20-year fully collateralised housing loan into the
     * straight-line approximation", and it is how a fifteen-year zero-coupon reaches Tier 3.
     */
    static OnboardingRequest case9ZeroCouponRequest(String contractId) {
        return OnboardingRequest.of(contractId, InstrumentClass.INVESTMENT,
                MeasurementCategory.AMORTISED_COST,
                SppiAssessment.passed(DISBURSEMENT, "classification.committee"),
                case9ZeroCouponTerms(), TierAssignmentSegment.WHOLESALE)
            .withRuleSetKey(ZERO_COUPON_PRODUCT, "IN-MUM")
            .with(TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE);
    }

    /**
     * One equivalence test on file, at the stated performance date and delta.
     *
     * @param populationId the population it was performed over
     * @param performedOn  the date the comparison was struck; the annual window runs from here
     * @param deltaBps     the documented spread, in basis points of effective annual rate, applied
     *                     as {@code solved = 8.00%} against {@code approximated = 8.00% - delta}.
     *                     A delta of 5 is within the 25 bps threshold; one of 40 is not
     */
    static EquivalenceTestRecord equivalenceTest(
        String populationId, LocalDate performedOn, String deltaBps) {
        BigDecimal solved = new BigDecimal("0.08");
        BigDecimal approximated = solved.subtract(
            new BigDecimal(deltaBps).movePointLeft(4));
        return new EquivalenceTestRecord(populationId, performedOn, 40,
            Rate.annualEffective(solved), Rate.annualEffective(approximated),
            BOARD_THRESHOLD_BPS, THRESHOLD_APPROVER);
    }

    /** A register holding exactly the tests supplied. */
    static EquivalenceTestGate register(EquivalenceTestRecord... records) {
        return EquivalenceTestGate.of(List.of(records));
    }

    /** A percentage at {@code scale} places, for comparing a rate with the fixture's printed figure. */
    static BigDecimal percent(BigDecimal fraction, int scale) {
        return fraction.multiply(new BigDecimal("100")).setScale(scale, RoundingMode.HALF_UP);
    }

    /** A money amount at presentation scale, for comparing with the fixture's printed figure. */
    static BigDecimal paise(Money money) {
        return money.atPresentationScale().amount();
    }

    // ------------------------------------------------------------------ the counting fakes

    /**
     * The real projection seam, counting calls.
     *
     * <p>Delegating rather than stubbing, so that a test which expects a projection gets the real
     * reference-case figures out of it and a test which expects none can assert zero. A stub would
     * force every pipeline test to assert against invented numbers.
     */
    static final class CountingProjector implements CashflowProjector {

        private final CashflowProjector delegate;
        private int calls;

        CountingProjector() {
            this(new RegistryDelegate(ProjectorRegistry.standard()));
        }

        CountingProjector(CashflowProjector delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public boolean supports(ContractTerms terms) {
            return delegate.supports(terms);
        }

        @Override
        public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
            calls++;
            return delegate.project(terms, fees);
        }

        /** How many times the pipeline asked for a projection. Zero is the assertion that matters. */
        int calls() {
            return calls;
        }
    }

    /** The real solve seam, counting calls. */
    static final class CountingSolver implements RateSolver {

        private final RateSolver delegate;
        private int calls;
        private SolveRequest lastRequest;

        CountingSolver() {
            this(new BracketedNewtonSolver());
        }

        CountingSolver(RateSolver delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public SolveResult solve(SolveRequest request) {
            calls++;
            lastRequest = request;
            return delegate.solve(request);
        }

        /** How many times the pipeline asked for a rate. Zero is the assertion that matters. */
        int calls() {
            return calls;
        }

        /**
         * The last request, so a test can read the target and the tolerance the tier selected.
         *
         * <p>Null until {@link #solve} has run, which is itself a usable assertion: a null request
         * on a contract the gate excluded is the same fact {@link #calls()} reports, arrived at from
         * the other side.
         */
        SolveRequest lastRequest() {
            return lastRequest;
        }
    }

    /** {@link ProjectorRegistry} behind the {@link CashflowProjector} interface. */
    static final class RegistryDelegate implements CashflowProjector {

        private final ProjectorRegistry registry;

        RegistryDelegate(ProjectorRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        @Override
        public boolean supports(ContractTerms terms) {
            for (CashflowProjector candidate : registry.projectors()) {
                if (candidate.supports(terms)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
            return registry.project(terms, fees);
        }
    }

    /**
     * A projector that shifts the carrying amount away from the vector's own net cash at inception,
     * so IC-1 breaches by a known, <b>signed</b> amount.
     *
     * <p>The default offset is 15,000 low rather than an arbitrary number: it is reference case 1's
     * processing fee, so the broken figure is exactly what a single misclassified fee would produce —
     * the projector having kept the fee out of the carrying amount while the vector kept it in. That
     * is one of the two causes {@code ExceptionCategory.IC1_BREACH} names.
     *
     * <p>The offset is signed and configurable because the population aggregate has to be tested
     * against the <em>netting</em> trap, not merely against a wrong total: two contracts breaching
     * 15,000 in opposite directions sum to nil signed and to 30,000 absolute, and only the second
     * reading is a control.
     */
    static final class IcBreachingProjector implements CashflowProjector {

        private final CashflowProjector delegate = new RegistryDelegate(ProjectorRegistry.standard());
        private final Money offset;

        /** 15,000 low — a single misclassified processing fee. */
        IcBreachingProjector() {
            this(PROCESSING_FEE.negate());
        }

        IcBreachingProjector(Money offset) {
            this.offset = Objects.requireNonNull(offset, "offset");
        }

        @Override
        public boolean supports(ContractTerms terms) {
            return delegate.supports(terms);
        }

        @Override
        public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
            ProjectionResult honest = delegate.project(terms, fees);
            return new ProjectionResult(honest.contractual(), honest.expected(),
                honest.initialCarryingAmount().plus(offset),
                honest.recommendedConvention(), honest.expectedEqualsContractualByPolicy(),
                List.of());
        }
    }

    /** A solver that finds no root — 03 § 4.3's {@code NO_SOLUTION}, never a defaulted rate. */
    static final class NoSolutionSolver implements RateSolver {

        @Override
        public SolveResult solve(SolveRequest request) {
            return SolveResult.noSolution(
                "no sign change on the ladder for a target of "
                    + request.target().atPresentationScale());
        }
    }

    // ------------------------------------------------------------------------- the port fakes

    /** A population the test states outright, in the order it states it. */
    static final class FixedPopulation implements ContractSource {

        private final List<String> ids;

        FixedPopulation(List<String> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            Objects.requireNonNull(boundary, "boundary");
            return ids;
        }
    }

    /**
     * An in-memory onboarding source.
     *
     * <p>A {@link LinkedHashMap} rather than a {@code HashMap}: FR-903 requires byte-identical
     * output from identical inputs, and a fixture whose iteration order varies between runs cannot
     * demonstrate that.
     */
    static final class MapOnboardingSource implements OnboardingSource {

        private final Map<String, OnboardingRequest> byId = new LinkedHashMap<>();

        MapOnboardingSource with(OnboardingRequest request) {
            byId.put(request.contractId(), request);
            return this;
        }

        @Override
        public Optional<OnboardingRequest> onboardingRequest(
            String contractId, AsAtBoundary boundary) {
            Objects.requireNonNull(boundary, "boundary");
            return Optional.ofNullable(byId.get(contractId));
        }

        /** The ids this source holds, in insertion order — a convenient population. */
        List<String> ids() {
            return new ArrayList<>(byId.keySet());
        }
    }
}
