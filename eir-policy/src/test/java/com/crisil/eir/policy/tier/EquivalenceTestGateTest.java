package com.crisil.eir.policy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The TG-1 evaluator: FR-411's demotion and FR-412's refusal.
 *
 * <p>Two things this file is mainly about. First, the <em>ordering</em>: FR-412 is applied before
 * the equivalence test is consulted, and no quantity of current evidence reverses it, because on
 * a zero-coupon the lifetime solved-versus-approximated delta is nil — {@code Case 9}'s two
 * columns both total 684,758.30 exactly — so an equivalence test performed as 03 § 10.2 specifies
 * would <em>pass</em> on an instrument whose year-one error is 81.0%. Second, that a demotion is
 * returned rather than thrown, because 03 § 10.2's named consequence is Tier 2 measurement and
 * Tier 2 is the more expensive, more correct answer.
 */
class EquivalenceTestGateTest {

    private static final LocalDate MARCH_2028 = LocalDate.of(2028, 3, 31);
    private static final String WCDL = "WCDL-2027-Q1";

    /** A test on file for the WCDL population, 8.10% solved against 8.00% approximated. */
    private static EquivalenceTestRecord test(
        String populationId, LocalDate performedOn, String solved, String thresholdBps) {
        return new EquivalenceTestRecord(
            populationId, performedOn, 250,
            // periodsPerYear 1, so effective annual IS the stored periodic rate and every
            // basis-point figure below is that rate times 10,000.
            Rate.annualEffective(new BigDecimal(solved)),
            Rate.annualEffective(new BigDecimal("0.08")),
            new BigDecimal(thresholdBps),
            "board.audit.committee");
    }

    /** A 6-month WCDL at par with interest: the ordinary Tier 3 shape, clear of FR-412. */
    private static EquivalenceTestSubject wcdl() {
        return EquivalenceTestSubject.couponBearingAtPar(
            WCDL, MaterialityTier.TIER_3, 6, Money.inr("5000000.00"), Money.inr("225000.00"));
    }

    /** Case 9's 15-year zero-coupon: 315,241.70 paid, 1,000,000.00 at maturity, no coupon. */
    private static EquivalenceTestSubject caseNine() {
        return EquivalenceTestSubject.discountInstrument(
            "INV-ZCB-15Y", MaterialityTier.TIER_3, 180,
            Money.inr("315241.70"), Money.inr("1000000.00"));
    }

    @Nested
    @DisplayName("FR-411 — a current test permits the shortcut")
    class CurrentTest {

        @Test
        @DisplayName("in date and within threshold, so Tier 3 stands")
        void permitted() {
            // 8.10% against 8.00% is 10 bps, inside a 25 bps tolerance; performed 2027-06-30
            // and therefore in date until 2028-06-30, comfortably past the March close.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            EquivalenceTestOutcome outcome = gate.evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TIER_3_PERMITTED);
            assertThat(outcome.demoted()).isFalse();
            assertThat(outcome.tier3Permitted()).isTrue();
            assertThat(outcome.tierGateResult().id()).isEqualTo(InvariantId.TG_1);
            assertThat(outcome.tierGateResult().satisfied()).isTrue();
            assertThat(outcome.raisesException()).isFalse();
            assertThat(outcome.basis())
                .as("the recorded basis cites the test that permitted the shortcut (FR-107)")
                .contains("2027-06-30")
                .contains("board.audit.committee");
        }

        @Test
        @DisplayName("the anniversary of the test is still current")
        void anniversaryStillPermits() {
            // Performed 2027-03-31, evaluated on 2028-03-31: the last day of the annual window.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 3, 31), "0.081", "25")));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).effectiveTier())
                .isEqualTo(MaterialityTier.TIER_3);
        }
    }

    @Nested
    @DisplayName("FR-411 — demotion to Tier 2 where the test is out of date")
    class Demotion {

        @Test
        @DisplayName("one day past the window demotes, and says by how many days")
        void oneDayStaleDemotes() {
            // Performed 2027-03-30, so the window closed 2028-03-30 and the 2028-03-31 close is
            // one day late. Pinned at one day rather than at some comfortable margin because
            // an annual control that is a day out is the case a reviewer will argue about.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 3, 30), "0.081", "25")));
            EquivalenceTestOutcome outcome = gate.evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.effectiveTier())
                .as("03 § 10.2: an out-of-date test demotes the population to Tier 2")
                .isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.ground()).isEqualTo(EquivalenceTestOutcome.Ground.TEST_STALE);
            assertThat(outcome.demoted()).isTrue();
            assertThat(outcome.tierGateResult().satisfied()).isFalse();
            assertThat(outcome.tierGateResult().deviation())
                .as("the deviation is the day count, so a report says how stale, not merely that")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(outcome.exception())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
        }

        @Test
        @DisplayName("the demotion does not stop the contract, and does block the close")
        void demotionIsNotAStop() {
            // The reason this evaluator returns an InvariantResult instead of throwing. Tier 2
            // is the ACPIR 51 pool EIR — more expensive and more correct than contractual rate
            // plus straight-line accretion — so the right answer to missing evidence is to
            // measure properly, not to quarantine a contract that is perfectly measurable.
            // STALE_EQUIVALENCE_TEST is one of only two of the ten categories of 04 § 3 that
            // does not stop its contract, and it blocks the close all the same: a population
            // changing measurement basis between one close and the next is exactly what a close
            // should surface.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2026, 3, 31), "0.081", "25")));
            EquivalenceTestOutcome outcome = gate.evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.exception().stopsTheContract()).isFalse();
            assertThat(outcome.exception().blocksClose()).isTrue();
        }

        @Test
        @DisplayName("a badly stale test reports the whole overrun")
        void veryStaleReportsDayCount() {
            // Performed 2026-03-31, window closed 2027-03-31, evaluated 2028-03-31. From
            // 2027-03-31 to 2028-03-31 is 366 days: 2028 is a leap year and the interval spans
            // its 29 February.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2026, 3, 31), "0.081", "25")));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).tierGateResult().deviation())
                .isEqualByComparingTo(new BigDecimal("366"));
        }

        @Test
        @DisplayName("no test at all demotes, on its own ground")
        void noTestOnFileDemotes() {
            EquivalenceTestOutcome outcome = EquivalenceTestGate.empty()
                .evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.NO_TEST_ON_FILE);
            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.tierGateResult().satisfied()).isFalse();
            assertThat(outcome.tierGateResult().deviation())
                .as("a missing test has no measurable overrun; the breach is categorical")
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.exception())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
            assertThat(outcome.basis()).contains("no equivalence test on file");
        }

        @Test
        @DisplayName("a test on a different population is no test for this one")
        void anotherPopulationsTestDoesNotCount() {
            // The register is keyed by population because 03 § 10.2 requires the comparison
            // "per Tier 3 population". A packing-credit test says nothing about a WCDL book.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test("PACKING-CREDIT-2027", LocalDate.of(2027, 6, 30), "0.081", "25")));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.NO_TEST_ON_FILE);
        }
    }

    @Nested
    @DisplayName("03 § 10.2 item 2 — an in-date test that failed is not permission")
    class ThresholdBreach {

        @Test
        @DisplayName("a delta over the Board threshold demotes, reporting the excess in bps")
        void overThresholdDemotes() {
            // 8.50% solved against 8.00% approximated is 50 bps, against a 25 bps tolerance:
            // the excess is 25 bps. The test is in date — it is simply evidence that the
            // shortcut does not hold for this population.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 6, 30), "0.085", "25")));
            EquivalenceTestOutcome outcome = gate.evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_OVER_THRESHOLD);
            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.tierGateResult().deviation())
                .isEqualByComparingTo(new BigDecimal("25"));
            assertThat(outcome.basis()).contains("is in date to").contains("failed");
        }

        @Test
        @DisplayName("staleness is reported ahead of a threshold breach when both hold")
        void stalenessIsReportedFirst() {
            // A stale test that also failed its threshold. Staleness is the literal statement
            // of TG-1, and the delta of a test nobody has re-performed is not a figure to
            // reason about, so the report names the thing that has to be fixed first.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2026, 1, 1), "0.085", "25")));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_STALE);
        }
    }

    @Nested
    @DisplayName("FR-412 — refused at any tenor, and not curable by evidence")
    class ForbiddenApproximation {

        @Test
        @DisplayName("Case 9's zero-coupon is refused even with a perfect current test on file")
        void currentTestDoesNotRescueAZeroCoupon() {
            // The ordering that is the whole substance of FR-412. This gate is handed a test
            // performed yesterday, on a large sample, with a nil delta against a generous
            // threshold — and still refuses. On a zero-coupon the lifetime delta IS nil:
            // Case 9's straight-line and EIR columns both total 684,758.30 exactly, which is
            // "precisely what makes the error invisible over the life of the instrument and
            // glaring in any single period" (03 § 10.3). Straight line takes 45,650.55 a year
            // against EIR accretion of 25,219.34 in year one — 81.0% overstated — and 74,074.07
            // in year fifteen, 38.4% understated. So the evidence 03 § 10.2 prescribes would
            // point the wrong way here, and the refusal has to come before it is consulted.
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(new EquivalenceTestRecord(
                "INV-ZCB-15Y", LocalDate.of(2028, 3, 30), 10_000,
                Rate.annualEffective(new BigDecimal("0.08")),
                Rate.annualEffective(new BigDecimal("0.08")),
                new BigDecimal("100"), "board.audit.committee")));

            EquivalenceTestOutcome outcome = gate.evaluate(caseNine(), MARCH_2028);

            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
            assertThat(outcome.basis())
                .as("the recorded basis carries the Case 9 figures, since they are the argument")
                .contains("81.0%")
                .contains("38.4%")
                .contains("684,758.30")
                .contains("180 months");
        }

        @Test
        @DisplayName("an FR-412 refusal is a TG-1 pass and raises no exception")
        void refusalIsNotATierGateBreach() {
            // TG-1's statement is "Tier 3 equivalence test in date" — a claim about a
            // population TAKING the shortcut. Here the shortcut is refused before any test is
            // consulted, so there is nothing for TG-1 to be untrue about. Reporting a breach
            // would block a period close on a population the engine handled correctly, and
            // would bury the genuine breaches — the populations nobody re-performed — under a
            // list of correct refusals. The demotion is recorded in the tier basis instead,
            // which is where FR-107 wants it.
            EquivalenceTestOutcome outcome = EquivalenceTestGate.empty()
                .evaluate(caseNine(), MARCH_2028);

            assertThat(outcome.tierGateResult().satisfied()).isTrue();
            assertThat(outcome.tierGateResult().deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(outcome.raisesException()).isFalse();
            assertThat(outcome.demoted()).as("and it is still a demotion").isTrue();
        }

        @Test
        @DisplayName("a 91-day T-bill is refused, resolving 03 § 10's own tension")
        void treasuryBillIsRefusedDespiteTheTier3List() {
            // 03 § 10's Tier 3 row names "T-bills, CP, CD" by hand, and all three are discount
            // instruments with no coupon leg, so FR-412 refuses the very products the table
            // places in Tier 3. The conflict is resolved in favour of 03 § 10.3 and FR-412:
            // the section is headed "Where approximation is never permitted" and says "at any
            // tenor" twice, the table is a population sketch by tenor band, and specific beats
            // general. It is also the only reading under which "at any tenor" does any work —
            // every Tier 3 population is inside twelve months by construction, so a short-tenor
            // carve-out would empty FR-412 completely. A 91-day bill therefore lands in Tier 2,
            // which for a book as homogeneous as T-bills is a tractable pool.
            EquivalenceTestSubject bill = EquivalenceTestSubject.discountInstrument(
                "GSEC-TBILL-91D", MaterialityTier.TIER_3, 3,
                Money.inr("98.32"), Money.inr("100.00"));
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test("GSEC-TBILL-91D", LocalDate.of(2028, 1, 31), "0.08", "25")));

            EquivalenceTestOutcome outcome = gate.evaluate(bill, MARCH_2028);
            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.basis())
                .as("and the basis says the tenor was considered and rejected as a mitigant")
                .contains("at any tenor")
                .contains("3 months is not a mitigant");
        }

        @Test
        @DisplayName("a deep-discount instrument is refused on the accretion share")
        void deepDiscountIsRefused() {
            // Accretion 200,000 against coupon 150,000: an accretion share above 0.57 and so
            // above the 0.50 policy share of EquivalenceTestSubject.
            EquivalenceTestSubject deep = new EquivalenceTestSubject(
                "CORP-DEEP-DISCOUNT", MaterialityTier.TIER_3, 12,
                Money.inr("800000.00"), Money.inr("1000000.00"), Money.inr("150000.00"));
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test("CORP-DEEP-DISCOUNT", LocalDate.of(2027, 6, 30), "0.08", "25")));

            EquivalenceTestOutcome outcome = gate.evaluate(deep, MARCH_2028);
            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION);
            assertThat(outcome.basis()).contains("deep-discount");
        }
    }

    @Nested
    @DisplayName("which test governs a reporting date")
    class GoverningTest {

        @Test
        @DisplayName("the most recent test performed by the reporting date governs")
        void mostRecentGoverns() {
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(
                test(WCDL, LocalDate.of(2026, 3, 31), "0.085", "25"),
                test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            assertThat(gate.governingTest(WCDL, MARCH_2028).performedOn())
                .isEqualTo(LocalDate.of(2027, 6, 30));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).effectiveTier())
                .as("the superseded 50 bps test does not demote a population that re-performed")
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("superseded tests are retained, so a replay of an earlier close agrees")
        void supersededTestsAreRetained() {
            // DT-1: a re-run of a closed period must reproduce published figures. Discarding
            // superseded records would make a replay of the 2027 close read the 2027-06-30
            // test, which did not exist yet.
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(
                test(WCDL, LocalDate.of(2026, 3, 31), "0.085", "25"),
                test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            assertThat(gate.recordsFor(WCDL)).hasSize(2);
            assertThat(gate.governingTest(WCDL, LocalDate.of(2027, 3, 31)).performedOn())
                .as("as at the 2027 close, only the 2026 test had been performed")
                .isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("a test dated after the reporting date cannot evidence that close")
        void postdatedTestIsRefused() {
            // Admitting it would breach DT-1 in the most confusing way available: a replay of
            // the March close would now permit a Tier 3 measurement the original run demoted,
            // and it would look like an engine defect rather than a register one.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2028, 6, 30), "0.081", "25")));
            EquivalenceTestOutcome outcome = gate.evaluate(wcdl(), MARCH_2028);

            assertThat(outcome.ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_POSTDATED);
            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_2);
            assertThat(outcome.tierGateResult().deviation())
                .as("negative days, so a reader can tell a future date from a stale one at a"
                    + " glance: 2028-03-31 to 2028-06-30 is 30 + 31 + 30 = 91 days")
                .isEqualByComparingTo(new BigDecimal("-91"));
            assertThat(outcome.exception())
                .isEqualTo(ExceptionCategory.STALE_EQUIVALENCE_TEST);
        }

        @Test
        @DisplayName("a post-dated submission does not hide an older test that is still current")
        void postdatedDoesNotMaskAnInDateTest() {
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(
                test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25"),
                test(WCDL, LocalDate.of(2028, 6, 30), "0.081", "25")));
            assertThat(gate.evaluate(wcdl(), MARCH_2028).effectiveTier())
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("two tests on one date: the less favourable one governs, either order")
        void tieBreakTakesTheLessFavourable() {
            // A duplicate submission is a data-quality matter this register does not adjudicate,
            // and it must not become a way to launder a failed test by re-recording it the same
            // day with a better delta. 8.50% against 8.00% is 50 bps and fails a 25 bps
            // tolerance; 8.10% against 8.00% is 10 bps and passes it.
            EquivalenceTestRecord passing = test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25");
            EquivalenceTestRecord failing = test(WCDL, LocalDate.of(2027, 6, 30), "0.085", "25");

            assertThat(EquivalenceTestGate.of(List.of(passing, failing))
                .evaluate(wcdl(), MARCH_2028).ground())
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_OVER_THRESHOLD);
            assertThat(EquivalenceTestGate.of(List.of(failing, passing))
                .evaluate(wcdl(), MARCH_2028).ground())
                .as("order of submission must not change the answer")
                .isEqualTo(EquivalenceTestOutcome.Ground.TEST_OVER_THRESHOLD);
        }

        @Test
        @DisplayName("the register a caller can read is not a register it can rewrite")
        void recordsForIsImmutable() {
            // The evidence register decides a measurement basis, so handing back the live list
            // would let any caller flip a population from TIER_3_PERMITTED to NO_TEST_ON_FILE
            // with nothing in the audit trail to say why.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> gate.recordsFor(WCDL).clear());
            assertThat(gate.evaluate(wcdl(), MARCH_2028).effectiveTier())
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("whitespace around a population id does not break the join")
        void populationIdsAreStripped() {
            // Ids are stripped at construction on both sides, because the join is by exact
            // string: one leading space in a feed would otherwise demote a population to Tier 2
            // and raise a TG-1 breach that re-performing the test could never clear. Whitespace
            // only — two ids differing in case may be two populations, and merging them would
            // be the worse defect.
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(" " + WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            EquivalenceTestSubject padded = EquivalenceTestSubject.couponBearingAtPar(
                WCDL + "  ", MaterialityTier.TIER_3, 6,
                Money.inr("5000000.00"), Money.inr("225000.00"));

            assertThat(gate.recordsFor(WCDL)).hasSize(1);
            assertThat(padded.populationId()).isEqualTo(WCDL);
            assertThat(gate.evaluate(padded, MARCH_2028).effectiveTier())
                .isEqualTo(MaterialityTier.TIER_3);
        }

        @Test
        @DisplayName("no records for a population is an empty list, not a null")
        void unknownPopulationHasNoRecords() {
            assertThat(EquivalenceTestGate.empty().recordsFor("UNKNOWN")).isEmpty();
            assertThat(EquivalenceTestGate.empty().governingTest("UNKNOWN", MARCH_2028)).isNull();
        }
    }

    @Nested
    @DisplayName("anything not proposed for Tier 3")
    class NotTierThree {

        @Test
        @DisplayName("a Tier 1 proposal passes through untouched, and TG-1 passes vacuously")
        void tierOnePassesThrough() {
            // Unit boundary: FR-107 assignment proposes the tier from contract attributes, this
            // gate only disposes of a Tier 3 proposal. TG-1 still reports a result rather than
            // being absent, because a control report that omitted the invariant could not be
            // told apart from one where the gate never ran.
            EquivalenceTestSubject tierOne = EquivalenceTestSubject.couponBearingAtPar(
                "PROJECT-FINANCE-1", MaterialityTier.TIER_1, 180,
                Money.inr("500000000.00"), Money.inr("300000000.00"));
            EquivalenceTestOutcome outcome = EquivalenceTestGate.empty()
                .evaluate(tierOne, MARCH_2028);

            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(outcome.ground()).isEqualTo(EquivalenceTestOutcome.Ground.NOT_TIER_3);
            assertThat(outcome.demoted()).isFalse();
            assertThat(outcome.tierGateResult().satisfied()).isTrue();
            assertThat(outcome.raisesException()).isFalse();
        }

        @Test
        @DisplayName("a zero-coupon already at Tier 1 is not dragged down to Tier 2")
        void zeroCouponAtTierOneIsLeftAlone() {
            // FR-412 refuses Tier 3 TREATMENT. Tier 1 is the stricter measurement, so there is
            // nothing to refuse: demoting Case 9's bond from instrument-level EIR to a pool
            // would be the gate making the answer worse.
            EquivalenceTestSubject tierOneZeroCoupon =
                EquivalenceTestSubject.discountInstrument(
                    "INV-ZCB-15Y", MaterialityTier.TIER_1, 180,
                    Money.inr("315241.70"), Money.inr("1000000.00"));
            EquivalenceTestOutcome outcome = EquivalenceTestGate.empty()
                .evaluate(tierOneZeroCoupon, MARCH_2028);

            assertThat(outcome.effectiveTier()).isEqualTo(MaterialityTier.TIER_1);
            assertThat(outcome.ground()).isEqualTo(EquivalenceTestOutcome.Ground.NOT_TIER_3);
        }

        @Test
        @DisplayName("a Tier 2 proposal is left at Tier 2")
        void tierTwoPassesThrough() {
            EquivalenceTestSubject tierTwo = EquivalenceTestSubject.couponBearingAtPar(
                "HOUSING-2027-04", MaterialityTier.TIER_2, 240,
                Money.inr("3000000.00"), Money.inr("4200000.00"));
            assertThat(EquivalenceTestGate.empty().evaluate(tierTwo, MARCH_2028).effectiveTier())
                .isEqualTo(MaterialityTier.TIER_2);
        }
    }

    @Nested
    @DisplayName("one TG-1 result per run, conjoined")
    class Aggregation {

        @Test
        @DisplayName("one breach among many populations breaches the run")
        void oneBreachBreachesTheRun() {
            // InvariantResult.conjunction is what makes "TG-1 reported once" mean the
            // conjunction rather than whichever population came first. A control reporting TG-1
            // satisfied while one population's test was two years stale would be reporting
            // green on a book that breached.
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(
                test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25"),
                test("BILLS-2027", LocalDate.of(2025, 6, 30), "0.081", "25")));
            List<EquivalenceTestOutcome> outcomes = gate.evaluateAll(
                List.of(
                    wcdl(),
                    EquivalenceTestSubject.couponBearingAtPar(
                        "BILLS-2027", MaterialityTier.TIER_3, 3,
                        Money.inr("1000000.00"), Money.inr("20000.00"))),
                MARCH_2028);

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).effectiveTier()).isEqualTo(MaterialityTier.TIER_3);
            assertThat(outcomes.get(1).effectiveTier()).isEqualTo(MaterialityTier.TIER_2);

            InvariantResult aggregate = EquivalenceTestGate.tierGateInvariant(outcomes);
            assertThat(aggregate.id()).isEqualTo(InvariantId.TG_1);
            assertThat(aggregate.satisfied()).isFalse();
            assertThat(aggregate.detail())
                .as("both populations are named, so the breach is traceable to one of them")
                .contains(WCDL)
                .contains("BILLS-2027")
                .contains("1 of 2 populations breached TG-1");
        }

        @Test
        @DisplayName("the aggregate deviation counts breaching populations, in one unit")
        void aggregateDeviationIsAPopulationCount() {
            // The per-population deviations are in two units — days for the date grounds, basis
            // points for a threshold breach — and conjunction keeps the FIRST breach's figure.
            // A bare aggregate number would therefore depend on the order populations happened
            // to be gated in, and its unit would be unknowable from the value. Here BILLS-2027
            // is stale (a day count) and CORP-2027 fails its threshold by 50 bps, and the
            // aggregate reports 2 either way round.
            EquivalenceTestGate gate = EquivalenceTestGate.of(List.of(
                test("BILLS-2027", LocalDate.of(2025, 6, 30), "0.081", "25"),
                test("CORP-2027", LocalDate.of(2027, 6, 30), "0.085", "0")));
            EquivalenceTestSubject bills = EquivalenceTestSubject.couponBearingAtPar(
                "BILLS-2027", MaterialityTier.TIER_3, 3,
                Money.inr("1000000.00"), Money.inr("20000.00"));
            EquivalenceTestSubject corp = EquivalenceTestSubject.couponBearingAtPar(
                "CORP-2027", MaterialityTier.TIER_3, 12,
                Money.inr("1000000.00"), Money.inr("85000.00"));

            assertThat(EquivalenceTestGate
                .tierGateInvariant(gate.evaluateAll(List.of(bills, corp), MARCH_2028))
                .deviation())
                .isEqualByComparingTo(new BigDecimal("2"));
            assertThat(EquivalenceTestGate
                .tierGateInvariant(gate.evaluateAll(List.of(corp, bills), MARCH_2028))
                .deviation())
                .as("order of gating must not change the figure a control report reads")
                .isEqualByComparingTo(new BigDecimal("2"));
        }

        @Test
        @DisplayName("all current, so the run's single TG-1 result is satisfied")
        void allCurrentSatisfiesTheRun() {
            EquivalenceTestGate gate = EquivalenceTestGate.of(
                List.of(test(WCDL, LocalDate.of(2027, 6, 30), "0.081", "25")));
            InvariantResult aggregate = EquivalenceTestGate.tierGateInvariant(
                gate.evaluateAll(List.of(wcdl()), MARCH_2028));
            assertThat(aggregate.satisfied()).isTrue();
        }

        @Test
        @DisplayName("an empty run passes rather than throwing on an empty conjunction")
        void emptyRunPasses() {
            // conjunction refuses an empty list on purpose, so that this decision has to be
            // taken explicitly. No Tier 3 population presented is not a Tier 3 population
            // without evidence.
            InvariantResult aggregate = EquivalenceTestGate.tierGateInvariant(List.of());
            assertThat(aggregate.id()).isEqualTo(InvariantId.TG_1);
            assertThat(aggregate.satisfied()).isTrue();
        }
    }

    @Nested
    @DisplayName("the outcome record keeps the three answers coherent")
    class OutcomeCoherence {

        @Test
        @DisplayName("a demotion to anything but Tier 2 is a different rule")
        void demotionMustBeToTierTwo() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestOutcome(
                    "P", MaterialityTier.TIER_3, MaterialityTier.TIER_1,
                    EquivalenceTestOutcome.Ground.TEST_STALE,
                    InvariantResult.fail(InvariantId.TG_1, "stale", BigDecimal.ONE),
                    ExceptionCategory.STALE_EQUIVALENCE_TEST, "stale"))
                .withMessageContaining("name Tier 2 as the consequence");
        }

        @Test
        @DisplayName("the ground and the TG-1 result must agree")
        void groundAndResultMustAgree() {
            // The design decision of this unit — an FR-412 refusal passes TG-1, a stale test
            // fails it — enforced by the type rather than left to the gate to remember.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestOutcome(
                    "P", MaterialityTier.TIER_3, MaterialityTier.TIER_2,
                    EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION,
                    InvariantResult.fail(InvariantId.TG_1, "refused", BigDecimal.ZERO),
                    null, "refused"))
                .withMessageContaining("must agree");
        }

        @Test
        @DisplayName("a ground that raises no exception cannot carry one")
        void exceptionMustMatchTheGround() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestOutcome(
                    "P", MaterialityTier.TIER_3, MaterialityTier.TIER_2,
                    EquivalenceTestOutcome.Ground.FORBIDDEN_APPROXIMATION,
                    InvariantResult.pass(InvariantId.TG_1, "refused"),
                    ExceptionCategory.STALE_EQUIVALENCE_TEST, "refused"))
                .withMessageContaining("contradicting the ground");
        }

        @Test
        @DisplayName("the outcome carries the TG-1 result and no other invariant's")
        void mustCarryTierGateInvariant() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestOutcome(
                    "P", MaterialityTier.TIER_3, MaterialityTier.TIER_3,
                    EquivalenceTestOutcome.Ground.TIER_3_PERMITTED,
                    InvariantResult.pass(InvariantId.IC_1, "wrong invariant"),
                    null, "permitted"))
                .withMessageContaining("carries the TG-1 result");
        }

        @Test
        @DisplayName("an outcome with no recorded basis is not FR-107 evidence")
        void basisIsRequired() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestOutcome(
                    "P", MaterialityTier.TIER_3, MaterialityTier.TIER_3,
                    EquivalenceTestOutcome.Ground.TIER_3_PERMITTED,
                    InvariantResult.pass(InvariantId.TG_1, "permitted"), null, "  "))
                .withMessageContaining("no recorded tier basis");
        }

        @Test
        @DisplayName("describe() names what was proposed, what is measured and the exception")
        void describeCarriesTheAuditSentence() {
            EquivalenceTestOutcome outcome = EquivalenceTestGate.empty()
                .evaluate(wcdl(), MARCH_2028);
            assertThat(outcome.describe())
                .contains(WCDL)
                .contains("proposed TIER_3")
                .contains("measured TIER_2")
                .contains("STALE_EQUIVALENCE_TEST");
        }
    }
}
