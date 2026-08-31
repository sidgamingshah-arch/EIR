package com.crisil.eir.application.onboarding;

import static com.crisil.eir.application.onboarding.OnboardingFixtures.BOARD_THRESHOLD_BPS;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE9_DISCOUNT_TO_ACCRETE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE9_FACE_VALUE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.CASE9_ISSUE_PRICE;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.WCDL_POPULATION;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.ZERO_COUPON_POPULATION;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.boundary;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case1Request;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case9ZeroCouponRequest;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.case9ZeroCouponTerms;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.equivalenceTest;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.feeRules;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.paise;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.register;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.tierGate;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.wcdlShortTenorRequest;
import static com.crisil.eir.application.onboarding.OnboardingFixtures.wcdlShortTenorTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingProjector;
import com.crisil.eir.application.onboarding.OnboardingFixtures.CountingSolver;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.projection.ProjectorRegistry;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionQueue;
import com.crisil.eir.policy.tier.EquivalenceTestGate;
import com.crisil.eir.policy.tier.EquivalenceTestOutcome;
import com.crisil.eir.policy.tier.EquivalenceTestSubject;
import com.crisil.eir.policy.tier.TierAssignmentResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Tier 3 equivalence-test permission gate, wired: FR-411, FR-412, invariant TG-1.
 *
 * <h2>The defect these tests exist to catch</h2>
 *
 * <p>{@code EquivalenceTestGate} was complete and had no caller outside its own package.
 * {@code TierAssignmentResult.requiresEquivalenceTest()} was true for every Tier 3 assignment and
 * nothing asked it. So a fifteen-year zero-coupon instrument was assigned Tier 3 by
 * {@code TIER_3_FULLY_COLLATERALISED_LOW_FEE}, solved on Tier 3's tolerance and recognised, with
 * nothing recording that the permission was never sought. Reference case 9 measures the
 * straight-line error on that instrument at <b>81.0% overstatement of year-one income</b> and 38.4%
 * understatement of the final year, the two columns tying at 684,758.30 exactly — the 08 risk
 * register's Cambodia failure mode arriving through an unwired gate rather than through a decision.
 *
 * <p>Every expected figure below is quoted from reference case 9 or derived by hand in the comment
 * beside it. None comes from running the pipeline.
 *
 * <h2>What is deliberately not asserted, and why</h2>
 *
 * <p><b>The solver's tolerance.</b> {@code SolverTolerance.forTier} maps {@code TIER_2} and
 * {@code TIER_3} to the same {@code standard()} tolerance, so a Tier 3 → Tier 2 demotion does not
 * move the convergence criterion and an assertion on the tolerance object would pass whichever tier
 * the pipeline passed. That is a test that cannot fail, which is the shape this codebase has found
 * seventeen instances of, so it is not written. What is asserted instead is the effective tier on
 * the permission — the figure 04 § 2.1's {@code materiality_tier} column takes, and the figure a
 * future FR-406 election of {@code tightened()} for a long-tenor Tier 2 pool would read — plus one
 * test that the tolerance helper really does discriminate the tier it is handed, so the argument
 * being passed is at least load-bearing in principle.
 */
@DisplayName("The Tier 3 equivalence-test permission gate (FR-411, FR-412, TG-1)")
class TierPermissionTest {

    private static final String RUN = "RUN-TG1";

    /** Case 1's inception date, which {@link OnboardingFixtures#boundary()} reports as at. */
    private static final LocalDate AS_OF = LocalDate.of(2026, 4, 1);

    private static InitialRecognition pipeline(EquivalenceTestGate register) {
        return new InitialRecognition(feeRules(), tierGate(), register, new CountingProjector(),
            new CountingSolver());
    }

    @Nested
    @DisplayName("FR-411: a Tier 3 population with no current test is demoted")
    class Fr411 {

        @Test
        @DisplayName("no test on file: measured at Tier 2, TG-1 breached, entry raised")
        void noTestOnFile() {
            // WHAT INPUT MAKES THIS FAIL: a Tier 3 assignment reaching the solve without the
            // register being consulted. Before this unit that was every Tier 3 assignment.
            OnboardingRecognition recognition = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL"));

            TierPermission permission = recognition.tierPermission();
            assertThat(permission.proposed().tier())
                .as("FR-107 proposed Tier 3 — 03 § 10's Tier 3 row, original tenor exactly 12"
                    + " months, which is the '<= 12' limb")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.NO_TEST_ON_FILE);
            assertThat(permission.effectiveTier())
                .as("03 § 10.2: an out-of-date or absent test demotes the population to Tier 2,"
                    + " which is EquivalenceTestGate.DEMOTION_TIER")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.demoted()).isTrue();

            assertThat(permission.tierGateResult()).isPresent();
            assertThat(permission.tierGateResult().orElseThrow().id()).isEqualTo(InvariantId.TG_1);
            assertThat(permission.tierGateResult().orElseThrow().satisfied())
                .as("a population taking the Tier 3 shortcut with no documented"
                    + " solved-versus-approximated comparison is what TG-1 is untrue about")
                .isFalse();

            assertThat(permission.failure()).isPresent();
            assertThat(permission.failure().orElseThrow().category())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            assertThat(permission.failure().orElseThrow().payloadRef())
                .isEqualTo(InitialRecognition.PAYLOAD_PREFIX + "/" + RUN + "/C-WCDL");
        }

        @Test
        @DisplayName("the contract is still recognised, at Tier 2, and is not quarantined")
        void demotionIsNotAQuarantine() {
            // ExceptionCategory.STALE_EQUIVALENCE_TEST carries stopsTheContract() == false, and
            // EquivalenceTestOutcome asserts that fact at construction precisely so the gate
            // "must not quarantine a contract that is measurable". The contract has a Tier 2 rate.
            OnboardingRecognition recognition = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL"));

            assertThat(recognition.outcome().disposition())
                .isEqualTo(OnboardingDisposition.RECOGNISED);
            assertThat(recognition.outcome().eir()).isPresent();
            assertThat(recognition.tierPermission().failure().orElseThrow().stopsTheContract())
                .isFalse();
            assertThat(recognition.tierPermission().failure().orElseThrow().blocksClose())
                .as("04 § 3: unresolved exceptions block the close unless explicitly accepted"
                    + " with approval — a population changing measurement basis between one close"
                    + " and the next is exactly what a close should surface")
                .isTrue();
        }

        @Test
        @DisplayName("a stale test is reported as stale, with the days overdue as the deviation")
        void staleTest() {
            // Performed 2025-03-01, so the annual window of 03 § 10.2 item 3 expired 2026-03-01.
            // The reporting date is 2026-04-01. March has 31 days, so the test expired 31 days
            // before the close: 2026-03-01 to 2026-04-01 is 31 days, by hand.
            EquivalenceTestGate stale = register(
                equivalenceTest(WCDL_POPULATION, LocalDate.of(2025, 3, 1), "5"));

            TierPermission permission = pipeline(stale)
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL")).tierPermission();

            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_STALE);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.tierGateResult().orElseThrow().deviation())
                .as("days beyond the annual window, 2026-03-01 to 2026-04-01")
                .isEqualByComparingTo("31");
        }

        @Test
        @DisplayName("a test dated after the close is no evidence for it")
        void postdatedTest() {
            // 2026-06-30 is 90 days after the 2026-04-01 reporting date: April 29 remaining + May
            // 31 + June 30 = 90. The gate reports it as a negative day count, and demotes.
            EquivalenceTestGate postdated = register(
                equivalenceTest(WCDL_POPULATION, LocalDate.of(2026, 6, 30), "5"));

            TierPermission permission = pipeline(postdated)
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL")).tierPermission();

            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_POSTDATED);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.tierGateResult().orElseThrow().deviation())
                .isEqualByComparingTo("-90");
        }

        @Test
        @DisplayName("an in-date test that failed is evidence against the shortcut, not for it")
        void overThreshold() {
            // Delta 40 bps against a Board-approved 25: 15 bps of excess, by subtraction.
            EquivalenceTestGate failed = register(
                equivalenceTest(WCDL_POPULATION, LocalDate.of(2025, 10, 1), "40"));

            TierPermission permission = pipeline(failed)
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL")).tierPermission();

            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_OVER_THRESHOLD);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.tierGateResult().orElseThrow().deviation())
                .as("40 bps documented less the " + BOARD_THRESHOLD_BPS.toPlainString()
                    + " bps threshold")
                .isEqualByComparingTo("15");
        }

        @Test
        @DisplayName("a current test within threshold permits Tier 3 and raises nothing")
        void permitted() {
            // Performed 2025-10-01, so in date to 2026-10-01, and 5 bps against a 25 bps
            // threshold. Both limbs of 03 § 10.2 satisfied.
            EquivalenceTestGate current = register(
                equivalenceTest(WCDL_POPULATION, LocalDate.of(2025, 10, 1), "5"));

            TierPermission permission = pipeline(current)
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL")).tierPermission();

            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TIER_3_PERMITTED);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(permission.demoted()).isFalse();
            assertThat(permission.tierGateResult().orElseThrow().satisfied()).isTrue();
            assertThat(permission.failure()).isEmpty();
        }

        @Test
        @DisplayName("a test on a different population does not license this one")
        void wrongPopulation() {
            // The key is product AND segment. A current, passing test performed over the wholesale
            // zero-coupon population says nothing about the retail WCDL population, and a gate that
            // joined on segment alone — or on product alone — would grant the permission here.
            EquivalenceTestGate elsewhere = register(
                equivalenceTest(ZERO_COUPON_POPULATION, LocalDate.of(2025, 10, 1), "5"));

            TierPermission permission = pipeline(elsewhere)
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL")).tierPermission();

            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.NO_TEST_ON_FILE);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
        }
    }

    @Nested
    @DisplayName("FR-412: the Case 9 shape is refused Tier 3 outright")
    class Fr412 {

        @Test
        @DisplayName("a 15-year zero-coupon is refused, and no test can cure it")
        void zeroCouponRefused() {
            // WHAT INPUT MAKES THIS FAIL: the Case 9 instrument reaching a Tier 3 measurement.
            // Note the register: a current test, within threshold, performed over exactly this
            // population. FR-412 is checked FIRST and is not curable by evidence, because on a
            // zero-coupon the lifetime solved-versus-approximated delta is zero — so a test
            // performed as 03 § 10.2 specifies would PASS on an instrument whose year-one error is
            // 81.0%. That ordering is the whole substance of FR-412 and this test is what holds it.
            EquivalenceTestGate current = register(
                equivalenceTest(ZERO_COUPON_POPULATION, LocalDate.of(2025, 10, 1), "5"));

            TierPermission permission = pipeline(current)
                .recognise(RUN, boundary(), case9ZeroCouponRequest("C-ZCB")).tierPermission();

            assertThat(permission.proposed().tier())
                .as("assigned Tier 3 by the § 10 collateral limb, which carries no tenor condition")
                .isEqualTo(MaterialityTier.TIER_3);
            assertThat(permission.gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.gateOutcome().basis())
                .contains("zero-coupon")
                .contains("180 months");
        }

        @Test
        @DisplayName("the refusal is a TG-1 pass and raises no queue entry")
        void refusalIsNotABreach() {
            // EquivalenceTestOutcome.Ground argues this at length: under FORBIDDEN_APPROXIMATION
            // the shortcut is not taken, so the test is neither passed nor failed and there is
            // nothing for TG-1 to be untrue about. Reporting a breach would block a close on a
            // population where the engine did exactly the right thing, and would bury the genuine
            // TG-1 breaches — the populations where somebody forgot to re-perform — in a list of
            // correct refusals. 04 § 3's queue does not describe a bond correctly sent to Tier 2
            // by a deterministic policy rule either.
            TierPermission permission = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), case9ZeroCouponRequest("C-ZCB")).tierPermission();

            assertThat(permission.tierGateResult().orElseThrow().satisfied()).isTrue();
            assertThat(permission.failure()).isEmpty();
            assertThat(permission.demoted()).isTrue();
        }

        @Test
        @DisplayName("the subject is derived from the vector: 684,758.30 of accretion, no coupon")
        void subjectArithmetic() {
            // The arithmetic an earlier attempt at this wiring judged unobtainable. It is a sum
            // over the contractual leg the pipeline already holds:
            //   inception   = 1,000,000.00 / 1.08^15 = 315,241.704965890..., presenting 315,241.70
            //   redemption  = 1,000,000.00 face
            //   accretion   = 1,000,000.00 - 315,241.70 = 684,758.30  (reference case 9)
            //   coupon      = nil — the vector carries no interest flow at all
            ProjectionResult projection =
                ProjectorRegistry.standard().project(case9ZeroCouponTerms(), List.of());
            EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
                case9ZeroCouponRequest("C-ZCB"), projection, MaterialityTier.TIER_3);

            assertThat(subject.populationId()).isEqualTo(ZERO_COUPON_POPULATION);
            assertThat(subject.originalTenorMonths())
                .as("fifteen annual periods = 180 months; FR-412 reports it and refuses at any"
                    + " tenor regardless")
                .isEqualTo(180);
            assertThat(paise(subject.inceptionAmount())).isEqualByComparingTo(
                paise(CASE9_ISSUE_PRICE));
            assertThat(paise(subject.redemptionAmount())).isEqualByComparingTo(
                paise(CASE9_FACE_VALUE));
            assertThat(subject.contractualCouponTotal().isZero()).isTrue();
            assertThat(paise(subject.accretion()))
                .isEqualByComparingTo(paise(CASE9_DISCOUNT_TO_ACCRETE));
            assertThat(subject.isZeroCoupon()).isTrue();
            assertThat(subject.approximationForbidden()).isTrue();
        }

        @Test
        @DisplayName("a deep-discount bond wearing a token coupon is refused too")
        void deepDiscountRefused() {
            // The limb the first version of this mapper could not reach, and the reason it could
            // not: presenting the subject through EquivalenceTestSubject.couponBearingAtPar forces
            // redemption equal to inception, so accretion reads nil and the accretion share reads
            // nil however deep the discount is. A 15-year bond bought at 315,241.70 against
            // 1,000,000 of face with 10,000 a year of coupon is the Case 9 economics wearing just
            // enough of a coupon to fail a zero-coupon test, and it is an ordinary way to write a
            // bond rather than a contrivance.
            //
            // By hand:  accretion    = 1,000,000.00 - 315,241.70 = 684,758.30
            //           coupon       = 15 x 10,000.00            =   150,000.00
            //           total return =                              834,758.30
            //           share        = 684,758.30 / 834,758.30  =        0.8203  (4dp HALF_UP)
            // 0.8203 >= the 0.50 policy share, so 03 § 10.3 refuses Tier 3.
            ProjectionResult projection = OnboardingFixtures.deepDiscountProjector()
                .project(OnboardingFixtures.deepDiscountTerms(), List.of());
            EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
                OnboardingFixtures.deepDiscountRequest("C-DEEP"), projection,
                MaterialityTier.TIER_3);

            assertThat(paise(subject.inceptionAmount()))
                .isEqualByComparingTo(paise(CASE9_ISSUE_PRICE));
            assertThat(paise(subject.redemptionAmount()))
                .isEqualByComparingTo(paise(CASE9_FACE_VALUE));
            assertThat(paise(subject.contractualCouponTotal()))
                .isEqualByComparingTo(paise(OnboardingFixtures.DEEP_DISCOUNT_COUPON_TOTAL));
            assertThat(paise(subject.accretion()))
                .isEqualByComparingTo(paise(CASE9_DISCOUNT_TO_ACCRETE));
            assertThat(paise(subject.totalReturn())).isEqualByComparingTo("834758.30");
            assertThat(subject.accretionShareOfReturn().setScale(4, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("0.8203");
            assertThat(subject.isZeroCoupon())
                .as("it has a coupon leg, so the zero-coupon limb cannot be what refuses it")
                .isFalse();
            assertThat(subject.isDeepDiscount()).isTrue();
            assertThat(subject.approximationForbidden()).isTrue();
        }

        @Test
        @DisplayName("the pipeline refuses it, with the share in the recorded basis")
        void deepDiscountRefusedByThePipeline() {
            InitialRecognition pipeline = new InitialRecognition(feeRules(), tierGate(),
                register(equivalenceTest(ZERO_COUPON_POPULATION, LocalDate.of(2025, 10, 1), "5")),
                OnboardingFixtures.deepDiscountProjector(), new CountingSolver());

            TierPermission permission = pipeline
                .recognise(RUN, boundary(), OnboardingFixtures.deepDiscountRequest("C-DEEP"))
                .tierPermission();

            assertThat(permission.proposed().tier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(permission.gateOutcome().ground())
                .as("refused before the current, passing test on file is even consulted")
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.gateOutcome().basis())
                .contains("deep-discount")
                .contains("0.8203");
            assertThat(permission.failure())
                .as("a correct policy refusal is not a queue entry")
                .isEmpty();
        }

        @Test
        @DisplayName("an undecomposed bullet at maturity is capital, not coupon")
        void undecomposedBulletIsNotReadAsPar() {
            // WHAT INPUT MAKES THIS FAIL: reading any COMBINED_EMI vector as par-and-coupon without
            // checking that the instalments amortise. Case 9's bond arriving from an LMS feed as one
            // undecomposed 1,000,000 line against 315,241.70 paid would then report 684,758.30 of
            // "coupon", accretion of nil and an accretion share of nil — FR-412 silent on the
            // instrument the whole unit exists for. This was a live hole after the first fix, found
            // in review, and it is production-reachable: ExternalScheduleProjector is layered into
            // the registry by prepend for the LMS_AUTHORITATIVE path and Instalment.of defaults to
            // COMBINED_EMI.
            //
            // One flow, in the last period, is not an instalment schedule. By hand:
            //   redemption = 1,000,000.00 (the terminal undecomposed line, read as capital)
            //   coupon     = nil
            //   accretion  = 1,000,000.00 - 315,241.70 = 684,758.30, share 1.0
            ProjectionResult projection = OnboardingFixtures.undecomposedBulletProjector()
                .project(OnboardingFixtures.deepDiscountTerms(), List.of());
            EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
                OnboardingFixtures.deepDiscountRequest("C-LMS"), projection,
                MaterialityTier.TIER_3);

            assertThat(paise(subject.redemptionAmount()))
                .isEqualByComparingTo(paise(CASE9_FACE_VALUE));
            assertThat(subject.contractualCouponTotal().isZero())
                .as("an undecomposed terminal lump is not evidence of a coupon leg")
                .isTrue();
            assertThat(paise(subject.accretion()))
                .isEqualByComparingTo(paise(CASE9_DISCOUNT_TO_ACCRETE));
            assertThat(subject.isZeroCoupon()).isTrue();
            assertThat(subject.approximationForbidden()).isTrue();

            InitialRecognition pipeline = new InitialRecognition(feeRules(), tierGate(),
                register(equivalenceTest(ZERO_COUPON_POPULATION, LocalDate.of(2025, 10, 1), "5")),
                OnboardingFixtures.undecomposedBulletProjector(), new CountingSolver());
            assertThat(pipeline.recognise(RUN, boundary(),
                    OnboardingFixtures.deepDiscountRequest("C-LMS"))
                .tierPermission().gateOutcome().ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
        }

        @Test
        @DisplayName("a coupon-bearing exposure at par is not caught by FR-412")
        void couponBearingIsNotRefused() {
            // The other side of the discriminant, and the one that matters for false positives: a
            // WCDL paying 1% a month on 1,000,000 over twelve months returns 12 x 10,000 =
            // 120,000 of interest and redeems at par, so accretion is nil and the accretion share
            // is nil — nowhere near the 0.50 deep-discount policy share. A discriminant that read
            // only FlowKind.INTEREST and missed COMBINED_EMI would read every EMI loan in the book
            // as zero-coupon and refuse it here.
            ProjectionResult projection =
                ProjectorRegistry.standard().project(wcdlShortTenorTerms(), List.of());
            EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
                wcdlShortTenorRequest("C-WCDL"), projection, MaterialityTier.TIER_3);

            assertThat(subject.populationId()).isEqualTo(WCDL_POPULATION);
            assertThat(subject.originalTenorMonths()).isEqualTo(12);
            assertThat(paise(subject.contractualCouponTotal()))
                .as("twelve monthly coupons of 1% of 1,000,000")
                .isEqualByComparingTo("120000.00");
            assertThat(subject.accretion().isZero()).as("advanced and repayable at par").isTrue();
            assertThat(subject.isZeroCoupon()).isFalse();
            assertThat(subject.isDeepDiscount()).isFalse();
            assertThat(subject.approximationForbidden()).isFalse();
        }

        @Test
        @DisplayName("an EMI loan's combined instalment counts as a coupon leg")
        void combinedEmiCountsAsInterest() {
            // Reference case 1's annuity vector carries no separate INTEREST flow — the whole
            // instalment is one COMBINED_EMI flow. 24 x 47,073.47 = 1,129,763.28 of instalments
            // less 1,000,000.00 advanced = 129,763.28 of lifetime interest, by hand. Asserted
            // through the subject rather than through the pipeline, because case 1 is Tier 2 and
            // the gate never sees it — but the mapper must still read it correctly, or the day
            // somebody asserts FULLY_COLLATERALISED_LOW_FEE on a mortgage it would be refused as a
            // zero-coupon instrument.
            ProjectionResult projection = ProjectorRegistry.standard()
                .project(OnboardingFixtures.case1Terms(), List.of());
            EquivalenceTestSubject subject = EquivalenceTestSubjects.subjectFor(
                case1Request("C-1"), projection, MaterialityTier.TIER_3);

            assertThat(paise(subject.contractualCouponTotal()))
                .isEqualByComparingTo("129763.28");
            assertThat(subject.isZeroCoupon()).isFalse();
            assertThat(subject.approximationForbidden()).isFalse();
        }
    }

    @Nested
    @DisplayName("The gate is not consulted where TG-1 has no subject")
    class NotTier3 {

        @Test
        @DisplayName("a Tier 2 contract: no gate outcome, and no TG-1 result fabricated for it")
        void tier2IsNotGated() {
            // Reference case 1: retail, 24 months, Tier 2 by TIER_2_RETAIL_OR_MSME_LONG_TENOR.
            // WHAT INPUT MAKES THIS FAIL: publishing EquivalenceTestGate's vacuous NOT_TIER_3 pass
            // per contract. That would be a TG-1 result on every retail mortgage in the book that
            // no input could turn into a breach — "a control that reads as coverage and cannot
            // fail is worse [than an absent one], because nobody looks at it again"
            // (OnboardingRun). The gate's own vacuous pass is right at population level, where
            // evaluateAll needs a result per subject to conjoin; it is wrong here.
            OnboardingRecognition recognition = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), case1Request("C-1"));

            TierPermission permission = recognition.tierPermission();
            assertThat(permission.proposed().tier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.gateConsulted()).isFalse();
            assertThat(permission.gateOutcome()).isNull();
            assertThat(permission.tierGateResult()).isEmpty();
            assertThat(permission.failure()).isEmpty();
            assertThat(permission.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(permission.populationId()).isEmpty();
            assertThat(recognition.invariants())
                .as("ST-12 and IC-1 only; no TG-1")
                .extracting(result -> result.id())
                .containsExactly(InvariantId.ST_12, InvariantId.IC_1);
        }

        @Test
        @DisplayName("an excluded contract carries no permission at all")
        void fvtplIsNotGated() {
            OnboardingRecognition recognition = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), OnboardingFixtures.sppiFailingRequest("C-FVTPL"));

            assertThat(recognition.outcome().disposition())
                .isEqualTo(OnboardingDisposition.EXCLUDED_FROM_EIR);
            assertThat(recognition.permission())
                .as("the tier stage never ran, so the permission is absent rather than defaulted")
                .isEmpty();
            assertThat(recognition.queueEntries()).isEmpty();
        }
    }

    @Nested
    @DisplayName("TierPermission refuses the state the pipeline used to be in")
    class TheStructuralControl {

        @Test
        @DisplayName("a Tier 3 assignment with no gate outcome cannot be constructed")
        void tier3WithoutAGateOutcome() {
            // This is the defect, expressed as a type. Before this unit, every Tier 3 assignment
            // in the engine was in exactly this state: proposed, measured, and never permitted.
            TierAssignmentResult tier3 = tierGate()
                .assign(case9ZeroCouponRequest("C-ZCB").tierInput());
            assertThat(tier3.tier()).isEqualTo(MaterialityTier.TIER_3);

            assertThatThrownBy(() -> TierPermission.notRequired(tier3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no equivalence-test outcome")
                .hasMessageContaining("81.0%");
        }

        @Test
        @DisplayName("a gate outcome cannot be attached to a Tier 1 or Tier 2 assignment")
        void tier2WithAGateOutcome() {
            TierAssignmentResult tier2 = tierGate().assign(case1Request("C-1").tierInput());
            assertThat(tier2.tier()).isEqualTo(MaterialityTier.TIER_2);
            EquivalenceTestOutcome outcomeForSomeoneElse = EquivalenceTestGate.empty().evaluate(
                EquivalenceTestSubject.discountInstrument(WCDL_POPULATION, MaterialityTier.TIER_3,
                    12, CASE9_ISSUE_PRICE, CASE9_FACE_VALUE),
                AS_OF);

            assertThatThrownBy(() ->
                TierPermission.gated(tier2, outcomeForSomeoneElse, RUN, "payload/ref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries an equivalence-test outcome anyway");
        }
    }

    @Nested
    @DisplayName("The population run files both kinds of entry")
    class ThePopulation {

        @Test
        @DisplayName("onboardAll raises the TG-1 entry on the queue, tier gate first")
        void queueEntriesAreFiled() {
            ExceptionQueue queue = new ExceptionQueue();
            OnboardingRun run = pipeline(EquivalenceTestGate.empty()).onboardAll(RUN, boundary(),
                new OnboardingFixtures.FixedPopulation(List.of("C-WCDL", "C-ZCB", "C-1")),
                new OnboardingFixtures.MapOnboardingSource()
                    .with(wcdlShortTenorRequest("C-WCDL"))
                    .with(case9ZeroCouponRequest("C-ZCB"))
                    .with(case1Request("C-1")),
                queue);

            assertThat(run.recognisedCount())
                .as("all three produce a rate; a TG-1 demotion is not a quarantine")
                .isEqualTo(3);
            assertThat(queue.records())
                .as("exactly one entry: the WCDL population has no test on file. The zero-coupon"
                    + " refusal raises none (correct policy application) and case 1 is Tier 2")
                .hasSize(1);
            assertThat(queue.records().get(0).contractId()).isEqualTo("C-WCDL");
            assertThat(queue.records().get(0).category())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            assertThat(queue.demotedContracts()).containsExactly("C-WCDL");
            assertThat(queue.quarantinedContracts())
                .as("STALE_EQUIVALENCE_TEST does not stop the contract, so nothing is quarantined")
                .isEmpty();
            assertThat(queue.blocksClose())
                .as("04 § 3: the entry blocks the close until somebody accepts the demotion with"
                    + " approval")
                .isTrue();

            // This assertion was .isFalse() when the unit that built the gate wrote it, pinning a
            // divergence it could not close: OnboardingRun.blocksClose() read breaches, the
            // quarantine count and assertedNothing, and a TG-1 demotion trips none of the three.
            // TG-1 is deliberately not a population obligation, the disposition stays RECOGNISED
            // because 03 § 10.2's consequence is Tier 2 measurement rather than a halt, and the run
            // asserted plenty. So describeClose() printed "close may proceed" over a population
            // whose measurement basis had changed while the queue said otherwise -- two answers to
            // one question. The run now carries what it raised and has a fourth reason reading it.
            assertThat(run.blocksClose())
                .as("the run and the queue must agree; the run knowing less than the queue about"
                    + " its own entries is how an operator reads a clean close over a demotion")
                .isTrue();
            assertThat(run.blockingEntries())
                .as("and it is the demotion that blocks, not something incidental")
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.contractId()).isEqualTo("C-WCDL");
                    assertThat(entry.category())
                        .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
                });
            assertThat(run.quarantinedCount())
                .as("nothing was quarantined, which is exactly why the other three reasons could"
                    + " not see this and a test on quarantine alone would pass")
                .isZero();
            assertThat(run.breaches())
                .as("and no invariant breached either, for the same reason")
                .isEmpty();
            assertThat(run.describeClose())
                .as("an operator reading one line must see the block AND what to act on")
                .contains("CLOSE BLOCKED")
                .contains("C-WCDL")
                .doesNotContain("close may proceed");
        }

        @Test
        @DisplayName("TG-1 is not an obligation of the population, and is not reported as one")
        void tg1IsNotAPopulationObligation() {
            // The trap this unit was explicitly told to avoid. A population with no Tier 3
            // contracts asserts TG-1 nowhere, so declaring it in POPULATION_INVARIANTS would make
            // unassertedInvariants() name it — and blocksClose() true — on every book without Tier
            // 3 exposure. RunAggregate.POPULATION_INVARIANTS records the same reasoning for
            // leaving S3-1 out: "An obligation red on every performing book is a control that gets
            // argued down to a soft one within a quarter."
            assertThat(OnboardingRun.POPULATION_INVARIANTS)
                .containsExactly(InvariantId.ST_12, InvariantId.IC_1)
                .doesNotContain(InvariantId.TG_1);

            ExceptionQueue queue = new ExceptionQueue();
            OnboardingRun run = pipeline(EquivalenceTestGate.empty()).onboardAll(RUN, boundary(),
                new OnboardingFixtures.FixedPopulation(List.of("C-1")),
                new OnboardingFixtures.MapOnboardingSource().with(case1Request("C-1")),
                queue);

            assertThat(run.unassertedInvariants())
                .as("a book with no Tier 3 exposure is not a book with a missing control")
                .isEmpty();
            assertThat(run.blocksClose()).isFalse();
        }
    }

    @Nested
    @DisplayName("The tier the solver is configured from")
    class TheSolveConfiguration {

        @Test
        @DisplayName("the tolerance helper discriminates the tier it is handed")
        void toleranceReadsItsArgument() {
            // The honest statement of what passing the effective tier buys today. Tier 1 tightens
            // (FR-406: residual floor 1e-10 -> 1e-12); Tier 2 and Tier 3 are the same standard()
            // tolerance, so a Tier 3 -> Tier 2 demotion does not move the criterion and no
            // assertion on the tolerance could detect which tier the pipeline passed. The effective
            // tier is passed anyway because a call site correct by coincidence stops being correct
            // the day FR-406's long-tenor Tier 2 election is made.
            assertThat(SolverTolerance.forTier(MaterialityTier.TIER_1))
                .isNotEqualTo(SolverTolerance.forTier(MaterialityTier.TIER_2));
            assertThat(SolverTolerance.forTier(MaterialityTier.TIER_2))
                .isEqualTo(SolverTolerance.forTier(MaterialityTier.TIER_3));
        }

        @Test
        @DisplayName("a failed solve names the tier it was actually configured on")
        void theSolveDiagnosticNamesTheEffectiveTier() {
            // The one place the tier that reached SolverTolerance.forTier is observable from
            // outside, and the reason it is worth having: the two tolerances coincide, so no
            // assertion on the SolveRequest can distinguish TIER_2 from TIER_3. The solve's own
            // diagnostic can. WHAT INPUT MAKES THIS FAIL: recognise() passing the FR-107 proposal
            // to the solve instead of the permitted tier — which is the demotion buying nothing.
            InitialRecognition pipeline = new InitialRecognition(feeRules(), tierGate(),
                EquivalenceTestGate.empty(), new CountingProjector(),
                new OnboardingFixtures.NoSolutionSolver());

            OnboardingRecognition recognition =
                pipeline.recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL"));

            assertThat(recognition.outcome().disposition())
                .isEqualTo(OnboardingDisposition.QUARANTINED);
            assertThat(recognition.outcome().failure().orElseThrow().detail())
                .as("the demoted tier, not the proposed TIER_3")
                .contains("under tier TIER_2")
                .doesNotContain("under tier TIER_3");
            assertThat(recognition.queueEntries())
                .as("both entries, tier gate first because the gate runs before the solve")
                .extracting(entry -> entry.category())
                .containsExactly(ExceptionCategory.STALE_EQUIVALENCE_TEST,
                    ExceptionCategory.NO_SOLUTION);
        }

        @Test
        @DisplayName("the outcome keeps the FR-107 proposal; the permission carries what is measured")
        void theOutcomeKeepsTheProposal() {
            // Stated as a test because it is the one hand-off a reader of this module has to get
            // right: OnboardingOutcome.tier() is the proposal — it cannot be the demoted tier,
            // because TierAssignmentResult refuses a tier that disagrees with the rule that
            // assigned it and there is no TierAssignmentRule for "demoted by the equivalence test".
            // 04 § 2.1's materiality_tier column takes TierPermission.effectiveTier().
            OnboardingRecognition recognition = pipeline(EquivalenceTestGate.empty())
                .recognise(RUN, boundary(), wcdlShortTenorRequest("C-WCDL"));

            assertThat(recognition.outcome().tier().tier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(recognition.tierPermission().effectiveTier())
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(recognition.tierPermission().describe())
                .contains("TIER_3")
                .contains("TG-1 gate:");
        }
    }

    @Nested
    @DisplayName("The population key")
    class ThePopulationKey {

        @Test
        @DisplayName("product then segment, colon separated")
        void productAndSegment() {
            assertThat(EquivalenceTestSubjects.populationIdFor(wcdlShortTenorRequest("C-WCDL")))
                .isEqualTo("WCDL:RETAIL");
            assertThat(EquivalenceTestSubjects.populationIdFor(case9ZeroCouponRequest("C-ZCB")))
                .isEqualTo("ZCB:WHOLESALE");
        }

        @Test
        @DisplayName("a contract with no product code gets a key of its own, not the segment's")
        void unkeyedProduct() {
            // case1Request carries no product code. Folding it into a segment-only key would let a
            // product-code outage silently widen every Tier 3 permission in the book; giving it a
            // key nobody registers evidence against demotes it loudly instead.
            assertThat(EquivalenceTestSubjects.populationIdFor(case1Request("C-1")))
                .isEqualTo(EquivalenceTestSubjects.UNKEYED_PRODUCT + ":RETAIL");
        }
    }
}
