package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Behavioural life: CPR truncation, rollover, revolver, and the par test
 * (docs 09 § 2.7, § 3.4, § 6 O6/O7, invariants ST-3, ST-5, ST-9).
 *
 * <p>Three types are exercised here because they are one mechanism.
 * {@link BehaviouralOverlay} names the five ways expected flows may differ from
 * contractual ones, {@link BehaviouralAdjuster} does the arithmetic and owns the
 * par screen, and {@link BehaviouralAdjustment} is what comes out — the two ladders,
 * the recorded basis, and the cross-leg ST-3 result.
 *
 * <h2>The instrument every prepayment figure below is measured on</h2>
 *
 * <p>Fixture O6 of docs 09 § 6: a 5,000,000 mortgage at 9% p.a. nominal with monthly
 * compounding — 0.75% a month, never 9% divided by twelve at the point of accrual —
 * over 240 months at an EMI of 44,986.30. The ladder is built by hand in
 * {@link #mortgage()} rather than through {@code ScheduleBuilder}, so that a builder
 * defect cannot silently move a figure this test attributes to the adjuster. Its
 * final rung is a stub that closes the balance on zero, which is what
 * {@code ScheduleBuilder} does for the same reason.
 *
 * <p><b>Where the expected values come from.</b> The four lives, the four
 * principal-weighted average lives and the three single-period mortalities are
 * tabulated in docs 09 § 6 O6 and in the {@link BehaviouralAdjuster} class comment.
 * Everything else numeric was computed by an independent decimal reimplementation of
 * the roll-forward <em>as the class comment describes it in prose</em> — fixed
 * instalment, interest on the balance actually outstanding, mortality applied after
 * the scheduled movement — and that reimplementation is trusted here only because it
 * reproduces, unaided, every one of the twelve figures docs 09 § 6 publishes for O6:
 *
 * <table border="1">
 *   <caption>O6, and what the independent reimplementation returns for it</caption>
 *   <tr><th>CPR</th><th>SMM</th><th>Life</th><th>Principal-WAL</th><th>EIR p.a.</th></tr>
 *   <tr><td>0%</td><td>0.00000000%</td><td>240</td><td>12.88y</td><td>9.533867%</td></tr>
 *   <tr><td>8%</td><td>0.69243826%</td><td>116</td><td>4.90y</td><td>9.674777%</td></tr>
 *   <tr><td>15%</td><td>1.34519470%</td><td>86</td><td>3.30y</td><td>9.785190%</td></tr>
 *   <tr><td>25%</td><td>2.36884242%</td><td>64</td><td>2.24y</td><td>9.945464%</td></tr>
 * </table>
 *
 * <p>No expected value below was obtained by running this codebase and recording what
 * it printed. Where a figure is neither in the docs nor recomputable in one line, the
 * arithmetic that produces it is stated in the comment on the assertion.
 *
 * <p>One test is {@link Disabled} and it names a production defect in the prepayment
 * roll-forward's treatment of a retained terminal balance. It is not fixed here; see
 * {@link ABalloonOrResidualIsNotPrepayable#aRetainedBalloonKeepsAccruingToContractualMaturity()}.
 */
class BehaviouralAdjusterTest {

    // -------------------------------------------------------------- O6, the mortgage

    /** O6 principal: 5,000,000. */
    private static final Money MORTGAGE = Money.inr("5000000");

    /** 9% p.a. nominal, monthly compounding: 0.75% a month. */
    private static final Rate MORTGAGE_RATE = Rate.monthly(new BigDecimal("0.0075"));

    /** O6's contractual EMI over 240 months. */
    private static final Money EMI = Money.inr("44986.30");

    private static final int MORTGAGE_PERIODS = 240;

    // ------------------------------------------- the round-number 1% monthly fixtures

    private static final Money LAKH_TEN = Money.inr("1000000");

    /** 12% p.a. nominal, monthly compounding: 1% a month. */
    private static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(new BigDecimal("0.01"));

    /** 12% p.a. nominal, quarterly compounding: 3% a quarter. */
    private static final Rate THREE_PERCENT_QUARTERLY =
        Rate.periodic(new BigDecimal("0.03"), 4);

    /** The balloon of the {@link #balloon()} ladder: 400,000 of 1,000,000 retained. */
    private static final Money BALLOON = Money.inr("400000");

    /**
     * The level instalment that amortises 1,000,000 down to a 400,000 balloon over 24
     * periods at 1% a month.
     *
     * <p>{@code (1,000,000 - 400,000 / 1.01^24) / a(24, 1%)} = {@code (1,000,000 -
     * 315,024.75) / 21.243387} = 32,244.083334, billed as 32,244.08.
     */
    private static final Money BALLOON_INSTALMENT = Money.inr("32244.08");

    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** No holidays, no business-day adjustment: due dates land on exact month ends. */
    private static final ScheduleCalendar MONTHLY = new ScheduleCalendar(
        ScheduleCalendar.Frequency.MONTHLY,
        ScheduleCalendar.BusinessDayConvention.NONE,
        Set.of(),
        ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
        List.of());

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** The due date of period {@code k} on a monthly schedule. */
    private static LocalDate monthlyDue(int period) {
        return VALUE_DATE.plusMonths(period);
    }

    /** The due date of period {@code k} on a quarterly schedule. */
    private static LocalDate quarterlyDue(int period) {
        return VALUE_DATE.plusMonths(3L * period);
    }

    // ------------------------------------------------------------------- the ladders

    /**
     * Fixture O6: 5,000,000 at 0.75% a month, 240 EMIs of 44,986.30.
     *
     * <p>Interest is the accrual on the balance actually outstanding and principal is
     * the residue of the bill, which is the {@code LevelAnnuity} split
     * ({@code ScheduleBuilder} rule 1). The final rung is a stub closing the balance
     * on exactly zero rather than a 240th EMI, because 44,986.30 is the EMI rounded
     * <em>up</em> and billing it 240 times would leave a negative balance that
     * {@link InstalmentLadder.Rung} rightly refuses.
     */
    private static InstalmentLadder mortgage() {
        BigDecimal periodic = MORTGAGE_RATE.periodic();
        List<InstalmentLadder.Rung> rungs = new ArrayList<>(MORTGAGE_PERIODS);
        Money balance = MORTGAGE;
        for (int period = 1; period <= MORTGAGE_PERIODS; period++) {
            Money interest = balance.times(periodic);
            boolean last = period == MORTGAGE_PERIODS;
            Money principal = last ? balance : EMI.minus(interest);
            Money total = last ? principal.plus(interest) : EMI;
            balance = balance.minus(principal);
            rungs.add(new InstalmentLadder.Rung(
                period, monthlyDue(period), principal, interest, total, balance));
        }
        return InstalmentLadder.of(Money.INR, rungs, MORTGAGE, Money.zero(Money.INR));
    }

    /**
     * An interest-only ladder repaying principal in one flow at the end.
     *
     * <p>The shape a rollover and a revolver both need: no principal moves before the
     * final rung, so the balance outstanding after every earlier rung is the whole
     * advance.
     */
    private static InstalmentLadder bullet(int periods, Money face, Rate rate, boolean monthly) {
        BigDecimal periodic = rate.periodic();
        Money interest = face.times(periodic);
        Money zero = Money.zero(face.currency());
        List<InstalmentLadder.Rung> rungs = new ArrayList<>(periods);
        for (int period = 1; period < periods; period++) {
            rungs.add(new InstalmentLadder.Rung(period,
                monthly ? monthlyDue(period) : quarterlyDue(period),
                zero, interest, interest, face));
        }
        rungs.add(new InstalmentLadder.Rung(periods,
            monthly ? monthlyDue(periods) : quarterlyDue(periods),
            face, interest, face.plus(interest), zero));
        return InstalmentLadder.of(face.currency(), rungs, face, zero);
    }

    private static InstalmentLadder monthlyBullet(int periods) {
        return bullet(periods, LAKH_TEN, ONE_PERCENT_MONTHLY, true);
    }

    /**
     * 1,000,000 at 1% a month over 24 periods, amortising down to a 400,000 balloon.
     *
     * <p>The final rung follows {@code ScheduleBuilder}: its principal is whatever
     * brings the balance to the terminal amount exactly, and the <b>terminal rides on
     * the billed total</b> ({@code ScheduleBuilder} attaches it as an add-on), so the
     * columns of that one rung deliberately do not sum to the bill. Getting that
     * convention wrong here would make the fixture disagree with what
     * {@link FlowVectorAssembler} expects to subtract back out.
     */
    private static InstalmentLadder balloon() {
        BigDecimal periodic = ONE_PERCENT_MONTHLY.periodic();
        List<InstalmentLadder.Rung> rungs = new ArrayList<>(24);
        Money balance = LAKH_TEN;
        for (int period = 1; period <= 24; period++) {
            Money interest = balance.times(periodic);
            boolean last = period == 24;
            Money principal = last ? balance.minus(BALLOON) : BALLOON_INSTALMENT.minus(interest);
            Money total = last ? BALLOON_INSTALMENT.plus(BALLOON) : BALLOON_INSTALMENT;
            balance = balance.minus(principal);
            rungs.add(new InstalmentLadder.Rung(
                period, monthlyDue(period), principal, interest, total, balance));
        }
        return InstalmentLadder.of(Money.INR, rungs, LAKH_TEN, BALLOON);
    }

    // ----------------------------------------------------------- assertion helpers

    private static InvariantResult resultFor(BehaviouralAdjustment adjustment, InvariantId id) {
        return adjustment.invariants().stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + id + " result on the adjustment"));
    }

    private static BigDecimal presented(Money money) {
        return money.atPresentationScale().amount();
    }

    /**
     * The share of a ladder's whole interest-accrual base that falls in its first
     * twelve periods.
     *
     * <p>The accrual base of a period is the balance outstanding at its start, and
     * effective interest — and therefore the integral fee riding inside it — accrues
     * in proportion to that base. So this ratio is the fraction of the instrument's
     * <em>lifetime</em> fee recognition that lands in year one, and comparing it
     * across two legs is the front-loading effect docs 09 § 2.7 talks about, measured
     * without a solve.
     */
    private static BigDecimal yearOneAccrualShare(InstalmentLadder ladder) {
        BigDecimal whole = BigDecimal.ZERO;
        BigDecimal yearOne = BigDecimal.ZERO;
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            BigDecimal opening = rung.balanceAfter().plus(rung.principal()).amount();
            whole = whole.add(opening, Precision.WORKING);
            if (rung.periodIndex() <= 12) {
                yearOne = yearOne.add(opening, Precision.WORKING);
            }
        }
        return yearOne.divide(whole, Precision.WORKING);
    }

    // ==================================================== 1. the unaltered path

    @Nested
    @DisplayName("an overlay that alters nothing returns the ladder itself, and says why (09 § 2.7)")
    class TheUnalteredPath {

        @Test
        @DisplayName("a Contractual overlay returns the same ladder instance, not an equal one")
        void aContractualOverlayReturnsTheSameInstance() {
            // Identity, not equality, and the class comment is explicit that this is not an
            // optimisation. Re-deriving an unchanged ladder would recompute its interest
            // column from the supplied rate, and on an equal-principal or sculpted schedule
            // that column carries the paise the bill rounds (03 § 5.7) — so the re-derived
            // ladder can land a paisa away from the one the core banking system bills. That
            // is a two-leg reconciliation break on a contract carrying no assumption at all,
            // which is the worst possible place to find one.
            InstalmentLadder contractual = mortgage();
            BehaviouralOverlay overlay =
                new BehaviouralOverlay.Contractual("no prepayment permitted under the sanction");

            assertThat(BehaviouralAdjuster.adjust(contractual, overlay, MORTGAGE_RATE))
                .isSameAs(contractual);
            assertThat(BehaviouralAdjuster.adjustment(contractual, overlay, MORTGAGE_RATE).expected())
                .isSameAs(contractual);
        }

        @Test
        @DisplayName("a nil CPR is recorded as an assumption that came out nil, not as no assumption")
        void aNilCprIsRecordedAsAFinding() {
            // 09 § 2.7's distinction, and the whole reason BehaviouralAdjustment exists as a
            // value rather than a returned ladder. Three cases produce byte-identical
            // ladders and must produce three different sentences: contractual life chosen as
            // policy, a curve fitted that came out at zero, and (below) a curve that bites.
            // A engine that collapsed the first two would make an audit file unable to
            // distinguish "we looked and found nothing" from "we never looked".
            InstalmentLadder contractual = mortgage();

            BehaviouralAdjustment nilCpr = BehaviouralAdjuster.adjustment(
                contractual, new BehaviouralOverlay.ConstantPrepaymentRate(BigDecimal.ZERO),
                MORTGAGE_RATE);
            BehaviouralAdjustment policy = BehaviouralAdjuster.adjustment(
                contractual, new BehaviouralOverlay.Contractual("gold loan, no prepayment right"),
                MORTGAGE_RATE);

            assertThat(nilCpr.expected()).isSameAs(contractual);
            assertThat(policy.expected()).isSameAs(contractual);
            assertThat(nilCpr.altered()).isFalse();
            assertThat(policy.altered()).isFalse();

            assertThat(nilCpr.recordedBasis())
                .contains("assumption was formed and came out nil")
                .contains("CPR(0)");
            assertThat(policy.recordedBasis())
                .contains("not as an absence of assumption")
                .contains("gold loan, no prepayment right");
            assertThat(nilCpr.recordedBasis()).isNotEqualTo(policy.recordedBasis());
        }

        @Test
        @DisplayName("an all-zero CPR vector and a nil rollover also pass the ladder through")
        void everyNilShapedOverlayPassesThrough() {
            // altersFlows() is the single gate on the pass-through, and each variant answers
            // it differently: a vector must scan every point rather than test its first, and
            // a rollover count of zero means one cycle, which is the contractual ladder. A
            // variant that answered this wrong would fall into the roll-forward and recompute
            // an unchanged schedule — the paisa-drift case above — or, for the rollover,
            // replay a single cycle and report the contractual life as an assumption.
            InstalmentLadder contractual = monthlyBullet(6);

            assertThat(BehaviouralAdjuster.adjust(contractual,
                new BehaviouralOverlay.CprVector(List.of(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                ONE_PERCENT_MONTHLY)).isSameAs(contractual);
            assertThat(BehaviouralAdjuster.adjust(contractual,
                new BehaviouralOverlay.RolloverAssumption(0, bd("0.95")),
                ONE_PERCENT_MONTHLY)).isSameAs(contractual);
        }

        @Test
        @DisplayName("the unaltered path is not gated on the rate, because it recomputes nothing")
        void theUnalteredPathDoesNotCheckTheRate() {
            // A deliberate asymmetry worth pinning in both directions. The rate guards exist
            // because an overlay recomputes interest on a balance path the contractual ladder
            // never had; where nothing is recomputed there is nothing for a wrong rate to
            // corrupt, and refusing here would mean a mortgage on contractual life could not
            // be processed without a rate nobody uses.
            //
            // The test is the boundary itself: the same wrong rate on the same ladder is
            // accepted with Contractual and refused with a live CPR. If the guards were ever
            // hoisted above the altersFlows() check, the first assertion fails and the
            // reviewer is told that the same-instance promise now depends on the rate.
            InstalmentLadder contractual = mortgage();
            Rate wrong = Rate.monthly(bd("0.02"));

            assertThat(BehaviouralAdjuster.adjust(contractual,
                new BehaviouralOverlay.Contractual("contractual life, immaterial prepayment"),
                wrong)).isSameAs(contractual);

            assertThatIllegalArgumentException().isThrownBy(() -> BehaviouralAdjuster.adjust(
                contractual, new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), wrong));
        }

        @Test
        @DisplayName("the pass-through still reports a life, a ratio of exactly one and ST-3")
        void thePassThroughStillReportsTheFullAdjustment() {
            // The disclosure consequence of 09 § 2.7 is the opposite of the performance one:
            // the figures must still be produced, because "the overlay changed nothing" and
            // "the overlay was never evaluated" are the same ladder and different audit
            // outcomes. So a pass-through carries a life, a WAL, a ratio and a satisfied
            // ST-3, not a shrug.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                mortgage(), new BehaviouralOverlay.Contractual("securitised pool, seasoned"),
                MORTGAGE_RATE);

            assertThat(adjustment.expectedLifePeriods()).isEqualTo(240);
            assertThat(adjustment.contractualLifePeriods()).isEqualTo(240);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(adjustment.periodsPerYear()).isEqualTo(12);
            // docs 09 § 6 O6, the 0% CPR row: 12.88 years against a 20-year contractual term.
            assertThat(Precision.round(adjustment.principalWeightedLifeYears(), 2))
                .isEqualByComparingTo(bd("12.88"));
            assertThat(resultFor(adjustment, InvariantId.ST_3).satisfied()).isTrue();
            assertThat(adjustment.allSatisfied()).isTrue();
        }
    }

    // ============================================ 2. CPR to single-period mortality

    @Nested
    @DisplayName("an annual CPR is a survival rate, so it converts by a root and not a division")
    class CprToSinglePeriodMortality {

        @ParameterizedTest(name = "CPR {0} at 12 periods a year is SMM {1}")
        @CsvSource({
            "0.08, 0.0069243826",
            "0.15, 0.0134519470",
            "0.25, 0.0236884242",
        })
        @DisplayName("the three documented O6 mortalities, to ten decimal places")
        void theDocumentedMortalities(String cpr, String smm) {
            // The BehaviouralAdjuster class comment tabulates these as percentages —
            // 0.69243826%, 1.34519470%, 2.36884242% — against the corresponding O6 lives.
            // Ten decimal places of a fraction is those eight significant figures exactly,
            // which is as far as the source states them.
            assertThat(Precision.round(
                BehaviouralAdjuster.singlePeriodMortality(bd(cpr), 12), 10))
                .isEqualByComparingTo(bd(smm));
        }

        @Test
        @DisplayName("dividing the CPR by twelve UNDERSTATES monthly speed, by 12.1% at a 25% CPR")
        void dividingByTwelveUnderstatesTheSpeed() {
            // The named defect: CPR / 12 instead of the periodic root. A CPR is a statement
            // that 25% of the pool is gone by year end, so it compounds, and the arithmetic
            // mean of a compounding series sits below its geometric root. 0.25 / 12 is
            // 2.0833333% against a correct 2.3688424% — the divided figure is 12.05% short,
            // and the error compounds over every period of a 240-rung ladder.
            //
            // The direction is asserted, not just the magnitude, because the
            // BehaviouralAdjuster class comment states it BACKWARDS: "dividing by twelve
            // instead would overstate monthly prepayment on a 25% CPR by 5.6% relative". The
            // singlePeriodMortality comment on the same page has it right ("an 11% relative
            // understatement"). Only one of the two sentences can survive this assertion, and
            // it is the class-level one that is wrong — see the note in the report.
            BigDecimal smm = BehaviouralAdjuster.singlePeriodMortality(bd("0.25"), 12);
            BigDecimal divided = bd("0.25").divide(bd("12"), Precision.WORKING);

            assertThat(smm).isGreaterThan(divided);
            assertThat(Precision.round(
                BigDecimal.ONE.subtract(divided.divide(smm, Precision.WORKING)), 4))
                .isEqualByComparingTo(bd("0.1205"));
        }

        @Test
        @DisplayName("the root is taken at the ladder's frequency, so a quarterly SMM is not monthly")
        void theRootIsTakenAtTheLaddersFrequency() {
            // The market says "single MONTHLY mortality" and means it, because the market's
            // collateral is monthly. A quarterly ladder fed the monthly figure prepays a
            // third of what the assumption says.
            //
            // Independent arithmetic: surviving a year is (1-SMM)^n whatever n is, so
            // (1 - smm4)^1 must equal (1 - smm12)^3 exactly — one quarter is three months of
            // the same survival. A division-based conversion fails this (1 - 0.25/4 = 0.9375
            // against (1 - 0.25/12)^3 = 0.93876), and so does taking the monthly root on a
            // quarterly ladder.
            BigDecimal quarterly = BehaviouralAdjuster.singlePeriodMortality(bd("0.25"), 4);
            BigDecimal monthly = BehaviouralAdjuster.singlePeriodMortality(bd("0.25"), 12);

            assertThat(quarterly).isGreaterThan(monthly);
            assertThat(Precision.round(BigDecimal.ONE.subtract(quarterly), 18))
                .isEqualByComparingTo(Precision.round(
                    Precision.onePlusPow(monthly.negate(), 3), 18));
            // 1 - (1 - 0.25)^(1/4) = 1 - 0.930604859 = 6.9395141% a quarter.
            assertThat(Precision.round(quarterly, 9)).isEqualByComparingTo(bd("0.069395141"));
        }

        @Test
        @DisplayName("an annual ladder's mortality is the CPR itself, and a nil CPR is exactly zero")
        void theDegenerateFrequenciesAreExact() {
            // At one period a year the root is the first root and the SMM is the CPR. Worth
            // pinning because it is the one frequency where the fractional-power path must
            // not be taken: Precision.onePlusPow dispatches an exponent of 1 to exact integer
            // pow, and a result of 0.2499999999 here would be a signal that it had not.
            assertThat(BehaviouralAdjuster.singlePeriodMortality(bd("0.25"), 1))
                .isEqualByComparingTo(bd("0.25"));
            assertThat(BehaviouralAdjuster.singlePeriodMortality(BigDecimal.ZERO, 12))
                .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a CPR of 1 or more has no periodic root and is refused, not clamped")
        void aFullyRetiringPoolIsRefused() {
            // A CPR of 1 retires the pool inside the year, so there is no survival fraction
            // to take a root of: 1 - (1 - 1)^(1/12) is 1 at every frequency, which would
            // report a pool prepaying entirely in month one at any assumption at or above
            // 100%. Clamping is the wrong answer because the input is not a speed at all.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.singlePeriodMortality(BigDecimal.ONE, 12))
                .withMessageContaining("retires the pool within the year")
                .withMessageContaining("has no periodic root");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.singlePeriodMortality(bd("1.5"), 12));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.singlePeriodMortality(bd("-0.01"), 12))
                .withMessageContaining("fraction in [0,1)");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.singlePeriodMortality(bd("0.08"), 0))
                .withMessageContaining("periodsPerYear must be >= 1");
            assertThatNullPointerException()
                .isThrownBy(() -> BehaviouralAdjuster.singlePeriodMortality(null, 12));
        }
    }

    // ================================================ 3. the O6 prepayment roll-forward

    @Nested
    @DisplayName("fixed instalment, faster balance: the O6 prepayment roll-forward (09 § 6)")
    class TheO6RollForward {

        @ParameterizedTest(name = "CPR {0} compresses 240 months to {1}, principal-WAL {2}y")
        @CsvSource({
            "0.08, 116, 4.90",
            "0.15,  86, 3.30",
            "0.25,  64, 2.24",
        })
        @DisplayName("the three documented O6 speeds reproduce their published life and WAL")
        void theDocumentedO6Rows(String cpr, int life, String wal) {
            // docs 09 § 6 O6. These four numbers per row are the whole reason the convention
            // matters, and they are what a recasting roll-forward cannot produce: see the
            // test below.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                mortgage(), new BehaviouralOverlay.ConstantPrepaymentRate(bd(cpr)), MORTGAGE_RATE);

            assertThat(adjustment.expectedLifePeriods()).isEqualTo(life);
            assertThat(adjustment.expected().length()).isEqualTo(life);
            assertThat(adjustment.contractualLifePeriods()).isEqualTo(240);
            assertThat(Precision.round(adjustment.principalWeightedLifeYears(), 2))
                .isEqualByComparingTo(bd(wal));
            assertThat(adjustment.altered()).isTrue();
            assertThat(adjustment.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("the instalment is fixed and the prepayment rides on top of it, never inside it")
        void theInstalmentIsFixedAndThePrepaymentRidesOnTop() {
            // The convention the class comment defends at length, and the single assertion
            // that separates it from the alternative. A borrower who prepays does not get a
            // smaller EMI; the contract fixes the bill and the effect of prepaying is that
            // fewer bills are needed. So period 1 of the expected leg bills the contractual
            // 44,986.30 PLUS the prepayment, and the prepayment enters the principal column.
            //
            // Independent arithmetic at an 8% CPR (SMM 0.0069243826282994397782802805):
            //   interest    5,000,000 x 0.0075                        = 37,500.00
            //   scheduled   44,986.30 - 37,500.00                     =  7,486.30
            //   balance     5,000,000 - 7,486.30                      = 4,992,513.70
            //   prepaid     4,992,513.70 x 0.00692438262829944        = 34,570.08
            //   principal   7,486.30 + 34,570.08                      = 42,056.38
            //   billed      44,986.30 + 34,570.08                     = 79,556.38
            //   balance     4,992,513.70 - 34,570.08                  = 4,957,943.62
            // A recasting engine would instead bill less than 44,986.30 here, and an engine
            // that netted the prepayment against the instalment would bill exactly 44,986.30.
            InstalmentLadder expected = BehaviouralAdjuster.adjust(
                mortgage(), new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                MORTGAGE_RATE);
            InstalmentLadder.Rung first = expected.rung(1);

            assertThat(presented(first.interest())).isEqualByComparingTo(bd("37500.00"));
            assertThat(presented(first.principal())).isEqualByComparingTo(bd("42056.38"));
            assertThat(presented(first.total())).isEqualByComparingTo(bd("79556.38"));
            assertThat(presented(first.balanceAfter())).isEqualByComparingTo(bd("4957943.62"));
            assertThat(first.total().amount()).isGreaterThan(EMI.amount());
            assertThat(first.dueOn()).isEqualTo(monthlyDue(1));
        }

        @Test
        @DisplayName("interest accrues on the balance actually outstanding, not on the contractual one")
        void interestIsRecomputedAndNotCopiedFromTheContractualRung() {
            // The defect this catches is the cheapest one to write and the hardest to see: an
            // implementation that copies rung.interest() straight across from the contractual
            // leg. Period 1 cannot detect it — both legs open on 5,000,000, so both bill
            // 37,500.00, which is precisely why an assertion on period 1 alone would gate
            // nothing. Period 2 can: the prepayment in period 1 has left the expected leg on
            // a smaller balance.
            //
            // Independent arithmetic at a 12% CPR (SMM 0.0105962410353190023974723010):
            //   expected     4,939,611.82 x 0.0075 = 37,047.09
            //   contractual  4,992,513.70 x 0.0075 = 37,443.85
            // A copying implementation reports 37,443.85 on both legs and then amortises to
            // zero at the wrong speed, with every figure it prints looking like a schedule.
            InstalmentLadder contractual = mortgage();
            InstalmentLadder expected = BehaviouralAdjuster.adjust(
                contractual, new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.12")),
                MORTGAGE_RATE);

            assertThat(presented(expected.rung(1).interest()))
                .isEqualByComparingTo(presented(contractual.rung(1).interest()));
            assertThat(presented(expected.rung(2).interest()))
                .isEqualByComparingTo(bd("37047.09"));
            assertThat(presented(contractual.rung(2).interest()))
                .isEqualByComparingTo(bd("37443.85"));
            assertThat(expected.rung(2).interest().amount())
                .isLessThan(contractual.rung(2).interest().amount());
        }

        @Test
        @DisplayName("the last rung is a stub of balance plus interest, not a 116th full instalment")
        void theLastRungIsAStub() {
            // The BehaviouralAdjuster comment quotes this figure: 16,379.70 against the
            // 44,986.30 EMI. Billing a full instalment at period 116 would over-collect, and
            // Rung would refuse the negative balance it left — the correct failure, but a
            // late one, arriving as an arithmetic complaint from inside a constructor rather
            // than as a schedule that ends where the balance does.
            //
            // Independent arithmetic: the balance entering period 116 is 16,257.76 and
            // 16,257.76 x 0.0075 = 121.93, so the stub is 16,379.69/70 depending on nothing —
            // 16,257.76 + 121.93 = 16,379.69 from the presented components and 16,379.70 from
            // the working-precision sum reduced once, which is the reduce-once rule of
            // 03 § 1.3 and the figure the class comment publishes.
            InstalmentLadder expected = BehaviouralAdjuster.adjust(
                mortgage(), new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                MORTGAGE_RATE);
            InstalmentLadder.Rung stub = expected.rung(116);

            assertThat(presented(stub.total())).isEqualByComparingTo(bd("16379.70"));
            assertThat(stub.total().amount()).isLessThan(EMI.amount());
            assertThat(presented(stub.principal())).isEqualByComparingTo(bd("16257.76"));
            assertThat(presented(stub.interest())).isEqualByComparingTo(bd("121.93"));
            assertThat(stub.balanceAfter().isZero()).isTrue();
            assertThat(expected.terminalBalance().isZero()).isTrue();
        }

        @Test
        @DisplayName("the balance is not recast: a 25% CPR ends the ladder at 64, not at 240")
        void theBalanceIsNotRecastOverTheOriginalTerm() {
            // The wrong convention, stated so it cannot creep back. Recasting the instalment
            // each period over the remaining original term is what pool factors are published
            // on, and under it the balance is scheduled_factor(t) x (1-SMM)^t of the original
            // — which reaches zero only at contractual maturity. A recasting engine reports
            // an expected life of 240 months for a 240-month mortgage at a 25% CPR and calls
            // it a behavioural estimate.
            //
            // The second assertion is what makes this a test of the convention rather than of
            // the truncation: under recasting every instalment falls below the contractual
            // EMI, so a mid-ladder rung billing MORE than 44,986.30 can only come from a
            // fixed-instalment roll-forward.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                mortgage(), new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.25")),
                MORTGAGE_RATE);

            assertThat(adjustment.expectedLifePeriods()).isEqualTo(64);
            assertThat(adjustment.expected().rung(32).total().amount()).isGreaterThan(EMI.amount());
            assertThat(adjustment.recordedBasis())
                .contains("compressing 240 contractual periods to 64");
        }

        @Test
        @DisplayName("faster is shorter: life, WAL and total interest all fall monotonically with CPR")
        void fasterIsShorter() {
            // A direction test rather than a level test, and it catches a sign error the
            // level tests above cannot: an implementation that applied (1 + SMM) somewhere,
            // or that read the mortality as a survival rather than a mortality, reproduces
            // plausible-looking ladders whose ordering runs backwards. Interest is included
            // because it is the P&L consequence — a pool prepaying at 25% bills 1,008,301.88
            // of interest against 5,796,710.53 contractually, and an engine with the ordering
            // reversed would be forecasting more interest from faster prepayment.
            List<BehaviouralAdjustment> bySpeed = List.of(
                BehaviouralAdjuster.adjustment(mortgage(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), MORTGAGE_RATE),
                BehaviouralAdjuster.adjustment(mortgage(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.15")), MORTGAGE_RATE),
                BehaviouralAdjuster.adjustment(mortgage(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.25")), MORTGAGE_RATE));

            assertThat(bySpeed).extracting(BehaviouralAdjustment::expectedLifePeriods)
                .containsExactly(116, 86, 64);
            for (int index = 1; index < bySpeed.size(); index++) {
                BehaviouralAdjustment faster = bySpeed.get(index);
                BehaviouralAdjustment slower = bySpeed.get(index - 1);
                assertThat(faster.principalWeightedLifeYears())
                    .as("WAL must fall as speed rises")
                    .isLessThan(slower.principalWeightedLifeYears());
                assertThat(faster.lifeRatio())
                    .as("life ratio must fall as speed rises")
                    .isLessThan(slower.lifeRatio());
                assertThat(faster.expected().totalInterest().amount())
                    .as("total interest must fall as speed rises")
                    .isLessThan(slower.expected().totalInterest().amount());
            }
            assertThat(presented(bySpeed.get(2).expected().totalInterest()))
                .isEqualByComparingTo(bd("1008301.88"));
        }

        @Test
        @DisplayName("a CPR vector holds its last point flat, so a short curve is not a stop")
        void aCprVectorHoldsItsLastPointFlat() {
            // A curve fitted over three months and applied to a 240-month mortgage must
            // extrapolate as a level speed. A vector running out is a gap in the curve, not
            // an assumption that prepayment ceases, and an implementation that read a missing
            // point as zero would report a 237-month tail of contractual behaviour on a pool
            // the analysis says prepays.
            //
            // The assertion is an equality against a different overlay rather than an
            // inequality against 240, and that is what gives it teeth: a one-point vector at
            // 8% must produce the ladder a ConstantPrepaymentRate of 8% produces, rung for
            // rung. Zeroing the tail gives 240 periods; holding the first point for three
            // periods and then zeroing gives some third number.
            InstalmentLadder viaVector = BehaviouralAdjuster.adjust(mortgage(),
                new BehaviouralOverlay.CprVector(List.of(bd("0.08"))), MORTGAGE_RATE);
            InstalmentLadder viaConstant = BehaviouralAdjuster.adjust(mortgage(),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), MORTGAGE_RATE);

            assertThat(viaVector.length()).isEqualTo(116);
            assertThat(viaVector.rungs()).isEqualTo(viaConstant.rungs());
        }

        @Test
        @DisplayName("a vector with a nil first point reproduces the contractual rung exactly")
        void aNilFirstPointReproducesTheContractualRung() {
            // The zero-speed reproduction claim that requireReproducible exists to make
            // possible, exercised where it is observable. A CPR of zero returns the ladder by
            // identity and so proves nothing about the roll-forward; a vector of [0, 8%]
            // sends the same ladder THROUGH the roll-forward with a nil period-1 mortality,
            // and period 1 of the result must then be indistinguishable from the contractual
            // rung — same principal, same interest, same bill, same closing balance.
            //
            // Anything that fails here is an off-by-one in the mortality vector's indexing,
            // which is otherwise invisible: applying speeds[period] rather than
            // speeds[period-1] shifts a whole curve by one month and changes the life by one
            // or two periods, which nobody would question.
            InstalmentLadder contractual = mortgage();
            InstalmentLadder expected = BehaviouralAdjuster.adjust(contractual,
                new BehaviouralOverlay.CprVector(List.of(BigDecimal.ZERO, bd("0.08"))),
                MORTGAGE_RATE);

            assertThat(expected.rung(1)).isEqualTo(contractual.rung(1));
            // One nil month at the head pushes the 116-month life out by exactly one month.
            assertThat(expected.length()).isEqualTo(117);
            assertThat(expected.rung(2).total().amount()).isGreaterThan(EMI.amount());
        }
    }

    // ============================== 4. life compression and where the money moves

    @Nested
    @DisplayName("compressing a 20-year mortgage to 8 years (09 § 2.7)")
    class LifeCompression {

        /**
         * The 12% CPR that closes the O6 mortgage at exactly month 96.
         *
         * <p>Any speed in {@code [11.98%, 12.23%]} lands on 96, so 12% is comfortably
         * inside the band rather than balanced on its edge — the assertions below would
         * otherwise be a test of the boundary rather than of the compression.
         */
        private final BehaviouralOverlay eightYearLife =
            new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.12"));

        @Test
        @DisplayName("240 contractual periods become 96, a life ratio of exactly 0.4")
        void twentyYearsBecomesEight() {
            // 09 § 2.7's instrument, in this engine's terms. Independently computed: at a 12%
            // CPR (SMM 0.0105962410353190023974723010) the fixed-instalment roll-forward on
            // the O6 mortgage reaches zero in period 96, billing a 41,227.22 stub against the
            // 44,986.30 EMI.
            BehaviouralAdjustment adjustment =
                BehaviouralAdjuster.adjustment(mortgage(), eightYearLife, MORTGAGE_RATE);

            assertThat(adjustment.expectedLifePeriods()).isEqualTo(96);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(bd("0.4"));
            assertThat(presented(adjustment.expected().rung(96).total()))
                .isEqualByComparingTo(bd("41227.22"));
            assertThat(Precision.round(adjustment.principalWeightedLifeYears(), 6))
                .isEqualByComparingTo(bd("3.835340"));
        }

        @Test
        @DisplayName("year-one recognition rises by MORE than the inverse life ratio: 3.16 against 2.5")
        void feeRecognitionRisesByMoreThanTheInverseLifeRatio() {
            // The claim 09 § 2.7 makes and the one BehaviouralAdjustment.lifeRatio()'s
            // comment repeats: compressing assumed life multiplies year-one fee recognition
            // by more than the inverse of the life ratio, because declining-balance
            // amortisation front-loads ON TOP OF the shorter horizon. The ratio is therefore
            // a LOWER bound on the income effect and must never be quoted as the effect.
            //
            // Measured on the interest-accrual base, which is what an integral fee is
            // amortised in proportion to, so no solve is needed and no solver defect can
            // reach this figure. Independently computed sums of period-opening balances:
            //   contractual  year 1 / whole 240-period ladder = 0.076975
            //   expected     year 1 / whole  96-period ladder = 0.243595
            //   multiple                                        3.164617
            // against an inverse life ratio of 240/96 = 2.5. The direction and the ordering
            // are both asserted; a horizon effect alone would give exactly 2.5 and an engine
            // that had lost the front-loading would land there.
            //
            // Note on the 3.73 in docs 09 § 2.7: that figure is quoted for an unspecified
            // mortgage and is not reproducible from O6 in either measure — 3.16 on the
            // accrual base above, 4.43 on a full EIR fee amortisation of the same two legs.
            // Asserting it would be asserting a number this fixture does not produce, so what
            // is pinned here is the inequality the documents actually assert, plus the level
            // this fixture does produce.
            BehaviouralAdjustment adjustment =
                BehaviouralAdjuster.adjustment(mortgage(), eightYearLife, MORTGAGE_RATE);

            BigDecimal contractualShare = yearOneAccrualShare(adjustment.contractual());
            BigDecimal expectedShare = yearOneAccrualShare(adjustment.expected());
            BigDecimal multiple = expectedShare.divide(contractualShare, Precision.WORKING);
            BigDecimal inverseLifeRatio =
                BigDecimal.ONE.divide(adjustment.lifeRatio(), Precision.WORKING);

            assertThat(Precision.round(contractualShare, 6)).isEqualByComparingTo(bd("0.076975"));
            assertThat(Precision.round(expectedShare, 6)).isEqualByComparingTo(bd("0.243595"));
            assertThat(Precision.round(multiple, 6)).isEqualByComparingTo(bd("3.164617"));

            assertThat(inverseLifeRatio).isEqualByComparingTo(bd("2.5"));
            assertThat(multiple)
                .as("the income effect must exceed the inverse life ratio, never equal it")
                .isGreaterThan(inverseLifeRatio);
        }

        @Test
        @DisplayName("the recorded basis carries the mortality, the frequency and the compression")
        void theRecordedBasisIsTheDerivationAndNotJustTheFigure() {
            // 09 § 2.7 again: "expected life is 96 months" is a number, and a number is not
            // auditable. The basis has to carry the assumption, its conversion, the frequency
            // it was converted at, and the driver a revision emits — because a reviewer
            // challenging the life needs all four to re-perform it, and a disclosure that
            // omits the frequency omits the one input that silently scales the answer.
            BehaviouralAdjustment adjustment =
                BehaviouralAdjuster.adjustment(mortgage(), eightYearLife, MORTGAGE_RATE);

            assertThat(adjustment.recordedBasis())
                .contains("CPR(0.12)")
                .contains("0.0105962410")
                .contains("compounding 12 times a year")
                .contains("the market's single monthly mortality")
                .contains("compressing 240 contractual periods to 96")
                .contains("Driver on revision: BEHAVIOURAL_ESTIMATE");
        }

        @Test
        @DisplayName("a quarterly ladder says so in its basis, and is not called a monthly mortality")
        void aQuarterlyLadderIsNotDescribedAsMonthly() {
            // The parenthetical is suppressed off twelve periods a year, and that matters
            // more than a phrasing preference: "single monthly mortality" printed against a
            // quarterly conversion is a disclosure that invites a reader to re-perform the
            // figure at the wrong frequency and conclude the engine is wrong.
            BehaviouralAdjustment quarterly = BehaviouralAdjuster.adjustment(
                bullet(8, LAKH_TEN, THREE_PERCENT_QUARTERLY, false),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.25")),
                THREE_PERCENT_QUARTERLY);

            assertThat(quarterly.periodsPerYear()).isEqualTo(4);
            assertThat(quarterly.recordedBasis())
                .contains("compounding 4 times a year")
                .doesNotContain("single monthly mortality");
        }
    }

    // =============================== 5. timing, not amount — ST-3 and ACPIR 51

    @Nested
    @DisplayName("an overlay moves principal in time, never in amount (ST-3, ACPIR 51)")
    class TimingNotAmount {

        @Test
        @DisplayName("every prepayment speed recovers the whole 5,000,000: no expected loss is deducted")
        void expectedCreditLossesAreExcludedFromEveryNonPociLeg() {
            // ACPIR 51 excludes expected credit losses from the EIR cash flows of every
            // non-POCI instrument; POCI is the sole exception and it is measured by
            // PociAmortisation against a credit-adjusted rate, not here (03 § 4, § 8). So the
            // expected leg this class produces must recover the advance in full at every
            // speed. A leg netted down by a 1% lifetime ECL on a 5,000,000 mortgage recovers
            // 4,950,000 and this fails by 50,000.
            //
            // The comparison is against the STATED notional rather than against the
            // contractual leg, and that is deliberate. Both legs telescope — each rung's
            // principal is its opening balance less its closing balance, so each leg's
            // recovery collapses to its own opening — which means the cross-leg ST-3 result
            // cannot detect an overlay that took a haircut off the opening balance itself.
            // 5,000,000 is a figure this test knows independently of anything the engine did.
            for (String cpr : List.of("0.08", "0.12", "0.15", "0.25")) {
                BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                    mortgage(), new BehaviouralOverlay.ConstantPrepaymentRate(bd(cpr)),
                    MORTGAGE_RATE);

                assertThat(presented(BehaviouralAdjustment.principalRecovered(adjustment.expected())))
                    .as("principal recovered at a %s CPR", cpr)
                    .isEqualByComparingTo(bd("5000000.00"));
                assertThat(resultFor(adjustment, InvariantId.ST_3).satisfied()).isTrue();
                assertThat(resultFor(adjustment, InvariantId.ST_3).detail())
                    .contains("moves principal in time, not in amount");
            }
        }

        @Test
        @DisplayName("a rollover and a revolver recover the advance in full as well")
        void theLengtheningAndTruncatingOverlaysAlsoRecoverInFull() {
            // The same ACPIR 51 claim on the other three variants, because they reach the
            // recovery by different routes: a rollover moves the single principal flow later,
            // a revolver's curve moves principal both ways within the life, and neither may
            // lose a rupee doing it. A revolver is where a haircut would be least visible —
            // the curve is an estimate, so a leg that recovered 990,000 of a 1,000,000 limit
            // would look like a modelling choice.
            List<BehaviouralOverlay> overlays = List.of(
                new BehaviouralOverlay.RolloverAssumption(2, bd("0.85")),
                new BehaviouralOverlay.RevolverBehaviour(12, List.of(BigDecimal.ONE), "CARDS-2026-Q1"),
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(bd("0.5"), bd("0.6")), "CARDS-2026-Q1"));

            for (BehaviouralOverlay overlay : overlays) {
                BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                    monthlyBullet(24), overlay, ONE_PERCENT_MONTHLY);

                assertThat(presented(BehaviouralAdjustment.principalRecovered(adjustment.expected())))
                    .as("principal recovered under %s", overlay.label())
                    .isEqualByComparingTo(bd("1000000.00"));
                assertThat(adjustment.allSatisfied()).isTrue();
            }
        }

        @Test
        @DisplayName("every overlay emits BEHAVIOURAL_ESTIMATE, so a curve refresh routes to catch-up")
        void everyOverlayEmitsTheBehaviouralDriver() {
            // The join between projection and routing (ADR-0006): the projector does not
            // decide reset-versus-catch-up, it supplies the tag the routing table maps. Every
            // behavioural variant must emit BEHAVIOURAL_ESTIMATE, because what moved is the
            // entity's own estimate and not a market rate. A variant that emitted
            // TIME_VALUE_OF_MONEY would route a curve refresh to a prospective reset, which
            // recognises none of the catch-up the revision earned or cost.
            List<BehaviouralOverlay> variants = List.of(
                new BehaviouralOverlay.Contractual("no prepayment right"),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                new BehaviouralOverlay.CprVector(List.of(bd("0.05"), bd("0.09"))),
                new BehaviouralOverlay.RolloverAssumption(1, bd("0.9")),
                new BehaviouralOverlay.RevolverBehaviour(24, List.of(bd("0.6")), "KCC-2026"));

            assertThat(variants).allSatisfy(overlay ->
                assertThat(overlay.driverOnRevision())
                    .as("driver for %s", overlay.label())
                    .isEqualTo(RateDriver.BEHAVIOURAL_ESTIMATE));
        }
    }

    // ================= 6. a balloon or residual value is not prepayable principal

    @Nested
    @DisplayName("prepayment stops on the terminal lump, not through it (ST-5)")
    class ABalloonOrResidualIsNotPrepayable {

        private final BehaviouralOverlay tenPercent =
            new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.10"));

        @Test
        @DisplayName("the retained 400,000 survives the overlay: prepayment amortises to it, not past it")
        void theFloorIsTheContractualTerminalBalance() {
            // ST-5 across the overlay and not only on the contractual leg. A residual value
            // is the lessor's interest in an asset and a balloon is a contractual lump;
            // neither is principal a borrower can prepay away. An implementation that
            // amortised to zero would report a lease as fully recovered by rentals, which
            // deletes the asset the lessor still owns.
            //
            // Independently computed at a 10% CPR (SMM 0.0087416109546967057639004391): the
            // prepayable balance is exhausted in period 20, where the balance entering the
            // period is 408,759.77 and the rung repays 8,759.77 of principal against 4,087.60
            // of interest, leaving exactly the 400,000 balloon.
            BehaviouralAdjustment adjustment =
                BehaviouralAdjuster.adjustment(balloon(), tenPercent, ONE_PERCENT_MONTHLY);
            InstalmentLadder expected = adjustment.expected();

            assertThat(presented(expected.terminalBalance())).isEqualByComparingTo(bd("400000.00"));
            assertThat(expected.terminalBalance()).isEqualTo(BALLOON);
            assertThat(presented(expected.rung(20).principal())).isEqualByComparingTo(bd("8759.77"));
            assertThat(presented(expected.rung(20).interest())).isEqualByComparingTo(bd("4087.60"));
            assertThat(presented(expected.rung(20).balanceAfter()))
                .isEqualByComparingTo(bd("400000.00"));
            assertThat(resultFor(adjustment, InvariantId.ST_5).satisfied()).isTrue();
            assertThat(presented(BehaviouralAdjustment.principalRecovered(expected)))
                .isEqualByComparingTo(bd("1000000.00"));

            // The expected LIFE is not asserted here: the value this engine reports for it is
            // wrong, and the assertion belongs to the disabled test below.
        }

        @Test
        @DisplayName("the mortality never bites the terminal lump, even at a speed that would consume it")
        void theMortalityIsCappedAtThePrepayableBalance() {
            // The cap, exercised where it is load-bearing. At a 40% CPR the single-period
            // mortality is 4.16% and the post-scheduled balance passes within one period's
            // prepayment of the floor, so an uncapped mortality would take its fraction of
            // the balloon as well and leave a terminal below 400,000. ST-5 would then report
            // the breach — the correct failure — but the schedule would already have billed
            // the lessee for the lessor's asset.
            InstalmentLadder expected =
                BehaviouralAdjuster.adjust(balloon(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.40")),
                    ONE_PERCENT_MONTHLY);

            assertThat(expected.terminalBalance()).isEqualTo(BALLOON);
            assertThat(expected.rungs()).allSatisfy(rung ->
                assertThat(rung.balanceAfter().compareTo(BALLOON) >= 0)
                    .as("balance after period %d must never fall below the balloon",
                        rung.periodIndex())
                    .isTrue());
        }

        @Test
        @DisplayName("a retained balloon keeps accruing interest to contractual maturity")
        void aRetainedBalloonKeepsAccruingToContractualMaturity() {
            // What a correct roll-forward produces on the same fixture. Once the prepayable
            // balance is exhausted in period 20 the ladder must continue on the retained
            // 400,000 to the contractual last period: three interest-only rungs of
            // 400,000 x 0.01 = 4,000.00 with no principal movement, then period 24 retaining
            // the balloon. The expected life is then 24 periods and equals the contractual
            // life, which is the right answer — a prepaying balloon loan does not mature
            // early, it simply stops amortising.
            BehaviouralAdjustment adjustment =
                BehaviouralAdjuster.adjustment(balloon(), tenPercent, ONE_PERCENT_MONTHLY);
            InstalmentLadder expected = adjustment.expected();

            assertThat(expected.length()).isEqualTo(24);
            assertThat(adjustment.expectedLifePeriods()).isEqualTo(24);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(BigDecimal.ONE);
            for (int period = 21; period <= 24; period++) {
                assertThat(presented(expected.rung(period).interest()))
                    .as("interest on the retained balloon in period %d", period)
                    .isEqualByComparingTo(bd("4000.00"));
                assertThat(expected.rung(period).principal().isZero())
                    .as("no principal moves in period %d", period)
                    .isTrue();
                assertThat(expected.rung(period).balanceAfter()).isEqualTo(BALLOON);
            }
            assertThat(expected.rung(24).dueOn()).isEqualTo(monthlyDue(24));
            assertThat(expected.terminalBalance()).isEqualTo(BALLOON);

            // The dating half, stated as the figure rather than left to the length. The
            // ladder used to stop at period 20 and FlowVectorAssembler books the retained
            // terminal at rungs.get(size - 1).dueOn(), so the 400,000 balloon was dated
            // 2027-12-01 instead of 2028-04-01 and discounted four months early. ST-5 could
            // not see it: the terminal AMOUNT was right and only its date was wrong.
            assertThat(expected.rung(expected.length()).dueOn())
                .as("the last rung is contractual maturity, so the assembler dates the balloon there")
                .isEqualTo(monthlyDue(24));

            // And the decomposition. The final rung's total includes the retained lump —
            // ScheduleBuilder's convention, which the assembler relies on when it subtracts
            // the terminal to recover the billed instalment. With the lump excluded the
            // assembler produced a billed instalment of 4,000.00 - 400,000 = -396,000.00
            // beside a +400,000 BALLOON flow: net cash right, schedule unreadable, and no
            // invariant able to object.
            assertThat(presented(expected.rung(24).total()))
                .as("4,000.00 of interest plus the 400,000 balloon")
                .isEqualByComparingTo(bd("404000.00"));
            assertThat(presented(expected.rung(23).total()))
                .as("an intermediate rung bills the interest alone; the lump is not yet due")
                .isEqualByComparingTo(bd("4000.00"));
        }
    }

    // ================================================== 7. the rate and frequency guards

    @Nested
    @DisplayName("an overlay only reshapes a ladder it can reproduce at zero speed")
    class TheRateGuards {

        /** The 6-period 1%-monthly bullet with one rung's interest column perturbed. */
        private InstalmentLadder bulletWithInterestAt(int period, String interest) {
            List<InstalmentLadder.Rung> rungs = new ArrayList<>(monthlyBullet(6).rungs());
            InstalmentLadder.Rung original = rungs.get(period - 1);
            rungs.set(period - 1, new InstalmentLadder.Rung(
                original.periodIndex(), original.dueOn(), original.principal(),
                Money.inr(interest), original.principal().plus(Money.inr(interest)),
                original.balanceAfter()));
            return InstalmentLadder.of(Money.INR, rungs, LAKH_TEN, Money.zero(Money.INR));
        }

        @Test
        @DisplayName("an annual rate passed where a periodic one was wanted is refused, not used")
        void aWrongRateIsRefused() {
            // The defect this guard exists for: every figure an overlay produces from the
            // wrong rate is plausible, and nothing downstream can detect it. The expected leg
            // simply amortises to zero at the wrong speed, which is what an expected leg is
            // supposed to do. So the claim is checked where it is cheap — the ladder's own
            // opening balance times the supplied rate must reproduce the ladder's own
            // interest column — and the refusal names both figures so the caller can see
            // which of the two is wrong.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(mortgage(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                    Rate.monthly(bd("0.09"))))
                .withMessageContaining("bills interest of INR 37500.00")
                .withMessageContaining("the supplied rate accrues INR 450000.00")
                .withMessageContaining("can only reshape a ladder it can reproduce at zero speed");
        }

        @Test
        @DisplayName("a coupon that steps AFTER period one is still refused: every rung is checked")
        void everyConsumedRungIsCheckedAndNotOnlyTheFirst() {
            // The one case a first-period check misses, and the class comment says so: a
            // stepped, floating or indexed coupon has no single contractual rate at all, and
            // its first period reproduces perfectly because the first period is where the
            // rate was read from. Checking period 1 alone therefore produces a right-looking
            // answer on precisely the instruments whose expected leg must instead be built
            // from the rate profile through the blueprint pipeline.
            //
            // The fixture is the 1%-monthly bullet with period 3's interest at 12,500.00 — a
            // 1.25% coupon from period 3, which is what a step-up looks like on one rung.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(bulletWithInterestAt(3, "12500.00"),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                    ONE_PERCENT_MONTHLY))
                .withMessageContaining("period 3 of the contractual ladder")
                .withMessageContaining("the coupon steps, floats or is indexed")
                .withMessageContaining("build the expected leg from the rate profile");
        }

        @Test
        @DisplayName("one minor unit of deviation is a rounding allowance; two is a different rate")
        void theToleranceIsExactlyOneMinorUnit() {
            // 03 § 5.7: on a ladder whose contract fixes the PRINCIPAL — equal-principal,
            // sculpted, bullet, interest-only — ScheduleBuilder assigns the paise the bill
            // rounds to the interest column, so that column can legitimately sit up to a
            // minor unit away from the raw accrual while both figures are right. A guard
            // tighter than that would refuse the schedules the builder itself produces.
            //
            // Both sides are asserted because a one-sided test cannot distinguish a tolerance
            // of one paisa from a tolerance of a rupee, and a tolerance of a rupee would admit
            // a wrong rate on a large exposure. 10,000.00 is the exact accrual on 1,000,000
            // at 1%.
            assertThat(BehaviouralAdjuster.adjust(bulletWithInterestAt(2, "10000.01"),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), ONE_PERCENT_MONTHLY)
                .length()).isEqualTo(6);
            assertThat(BehaviouralAdjuster.adjust(bulletWithInterestAt(2, "9999.99"),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), ONE_PERCENT_MONTHLY)
                .length()).isEqualTo(6);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(bulletWithInterestAt(2, "10000.02"),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")), ONE_PERCENT_MONTHLY))
                .withMessageContaining("a deviation of");
        }

        @Test
        @DisplayName("a right periodic value at a wrong DECLARED frequency is refused, naming both")
        void aMisdeclaredFrequencyIsRefused() {
            // The guard that exists because requireReproducible cannot possibly catch this.
            // periodsPerYear is load-bearing twice and invisibly — it is the root the CPR is
            // taken at and the step an extension rolls by — and a rate carrying the right
            // periodic value at the wrong declared frequency reproduces the interest column
            // exactly, then prepays by a factor of 12/4 too much. Every figure it produces
            // looks like a schedule.
            //
            // The fixture is the monthly O6 mortgage with 0.75% declared as a quarterly rate.
            // 0.75% a month is the right periodic value, so the interest check passes on all
            // 240 rungs; only the due dates give it away, and they give it away exactly: 12
            // reproduces every one of them from 2026-05-01 and 4 reproduces none.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(mortgage(),
                    new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                    Rate.periodic(bd("0.0075"), 4)))
                .withMessageContaining("declares 4 periods a year")
                .withMessageContaining("due dates step at 12 periods a year")
                .withMessageContaining("12 reproduces every one of the 240 dates from 2026-05-01")
                .withMessageContaining("would prepay by a factor of 12/4");
        }

        @Test
        @DisplayName("a seasonal KCC ladder is not refused: nothing is concluded from bespoke dates")
        void aBespokeCalendarIsNotRefused() {
            // The pass side, and it is what makes the frequency check safe to run on every
            // ladder. Where no steppable frequency reproduces the dates, the schedule is
            // business-day-adjusted, seasonal, month-end-clamped or bespoke, and nothing can
            // be concluded about the declared frequency in either direction — so nothing is.
            //
            // A band-based approximation here would refuse a crop-cycle KCC whose periods are
            // genuinely unequal, which is precisely the schedule 09 § 2.8 says must be
            // supported. The fixture is three harvest-aligned due dates that no candidate
            // frequency reaches from 2026-05-01: not 2026-06-01 (monthly), 2026-07-01
            // (bi-monthly), 2026-08-01 (quarterly), 2026-09-01 (four-monthly), 2026-11-01
            // (half-yearly), 2027-05-01 (annual), 2026-05-15 (fortnightly) or 2026-05-08
            // (weekly).
            Money interest = LAKH_TEN.times(ONE_PERCENT_MONTHLY.periodic());
            Money zero = Money.zero(Money.INR);
            List<InstalmentLadder.Rung> rungs = List.of(
                new InstalmentLadder.Rung(1, LocalDate.of(2026, 5, 1),
                    zero, interest, interest, LAKH_TEN),
                new InstalmentLadder.Rung(2, LocalDate.of(2026, 6, 15),
                    zero, interest, interest, LAKH_TEN),
                new InstalmentLadder.Rung(3, LocalDate.of(2026, 9, 3),
                    LAKH_TEN, interest, LAKH_TEN.plus(interest), zero));
            InstalmentLadder seasonal = InstalmentLadder.of(Money.INR, rungs, LAKH_TEN, zero);

            InstalmentLadder expected = BehaviouralAdjuster.adjust(seasonal,
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.25")), ONE_PERCENT_MONTHLY);

            assertThat(expected.length()).isEqualTo(3);
            assertThat(expected.rung(1).total().amount()).isGreaterThan(interest.amount());
        }

        @Test
        @DisplayName("the four arguments are null-checked before anything is derived from them")
        void theArgumentsAreNullChecked() {
            // Cheap, and the reason it is worth the four lines: adjustment() derives
            // periodsPerYear from the rate on its third statement, so a null rate would
            // otherwise surface as an NPE from Rate.periodsPerYear() with no indication of
            // which of four arguments the caller omitted.
            InstalmentLadder ladder = monthlyBullet(6);
            BehaviouralOverlay overlay = new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08"));

            assertThatNullPointerException().isThrownBy(() ->
                BehaviouralAdjuster.adjustment(null, overlay, ONE_PERCENT_MONTHLY));
            assertThatNullPointerException().isThrownBy(() ->
                BehaviouralAdjuster.adjustment(ladder, null, ONE_PERCENT_MONTHLY));
            assertThatNullPointerException().isThrownBy(() ->
                BehaviouralAdjuster.adjustment(ladder, overlay, null));
            assertThatNullPointerException().isThrownBy(() ->
                BehaviouralAdjuster.adjustment(ladder, overlay, ONE_PERCENT_MONTHLY, null));
        }
    }

    // ============================================================ 8. rollover

    @Nested
    @DisplayName("a facility expected to roll: the ladder replayed, principal repaid once (46(2)(ii))")
    class Rollover {

        /** A 3-month WCDL of 1,000,000 at 1% a month, interest serviced, principal at the end. */
        private final InstalmentLadder wcdl = monthlyBullet(3);

        @Test
        @DisplayName("one rollover of a 3-month WCDL gives six periods with principal only at the sixth")
        void oneRolloverDoublesTheLifeAndMovesThePrincipal() {
            // What a roll IS, in ladder terms: the interest pattern repeats and the principal
            // repayment moves to the last cycle, because a renewal that is not substantive
            // under ACPIR 46(2)(ii) means the economics are one facility running the rolled
            // term. Each intermediate cycle's closing rung gives up its principal repayment
            // and keeps its interest.
            //
            // Independent arithmetic: 1,000,000 outstanding throughout, so every period bills
            // 1,000,000 x 1% = 10,000.00 and period 6 bills 1,010,000.00. The dates extend by
            // stepping the FIRST due date monthly — anchored, never chained — so period 6
            // falls on 2026-05-01 plus five months.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(
                wcdl, new BehaviouralOverlay.RolloverAssumption(1, bd("0.85")),
                ONE_PERCENT_MONTHLY);
            InstalmentLadder expected = adjustment.expected();

            assertThat(expected.length()).isEqualTo(6);
            assertThat(adjustment.expectedLifePeriods()).isEqualTo(6);
            assertThat(adjustment.contractualLifePeriods()).isEqualTo(3);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(bd("2"));

            assertThat(expected.rungs().subList(0, 5)).allSatisfy(rung -> {
                assertThat(rung.principal().isZero()).isTrue();
                assertThat(presented(rung.interest())).isEqualByComparingTo(bd("10000.00"));
                assertThat(rung.balanceAfter()).isEqualTo(LAKH_TEN);
            });
            assertThat(expected.rung(6).principal()).isEqualTo(LAKH_TEN);
            assertThat(presented(expected.rung(6).total())).isEqualByComparingTo(bd("1010000.00"));
            assertThat(expected.rung(6).balanceAfter().isZero()).isTrue();
            assertThat(expected.rung(4).dueOn()).isEqualTo(monthlyDue(4));
            assertThat(expected.rung(6).dueOn()).isEqualTo(monthlyDue(6));

            // Principal-WAL of a bullet is its life: 1,000,000 weighted at period 6 over
            // 1,000,000, in years, is 6/12 = 0.5.
            assertThat(adjustment.principalWeightedLifeYears()).isEqualByComparingTo(bd("0.5"));
            assertThat(presented(expected.totalInterest())).isEqualByComparingTo(bd("60000.00"));
        }

        @Test
        @DisplayName("the probability is recorded, not weighted in: 30% and 90% give identical flows")
        void theProbabilityIsRecordedAndNotWeightedIntoTheFlows() {
            // The design claim, and the one an implementer is most likely to get wrong,
            // because multiplying a scalar probability into the flows looks like prudence. It
            // is not: it is a probability-weighted expected life, which
            // ExercisePolicy.PROBABILITY_WEIGHTED already expresses with a full distribution
            // over exercise dates. Expressing it a second time here would put two weighting
            // mechanisms in the engine for one judgement, with no way afterwards to say which
            // of them produced a published rate.
            //
            // So the flows must be byte-identical at 30% and at 90%, and only the recorded
            // basis may differ — a roll assumed at less than even odds is not the most likely
            // outcome and the disclosure has to say so. An engine that weighted the flows
            // fails the first assertion; one that ignored the probability entirely fails the
            // second.
            BehaviouralAdjustment unlikely = BehaviouralAdjuster.adjustment(
                wcdl, new BehaviouralOverlay.RolloverAssumption(1, bd("0.30")),
                ONE_PERCENT_MONTHLY);
            BehaviouralAdjustment likely = BehaviouralAdjuster.adjustment(
                wcdl, new BehaviouralOverlay.RolloverAssumption(1, bd("0.90")),
                ONE_PERCENT_MONTHLY);

            assertThat(unlikely.expected().rungs()).isEqualTo(likely.expected().rungs());
            assertThat(unlikely.expectedLifePeriods()).isEqualTo(likely.expectedLifePeriods());

            assertThat(unlikely.recordedBasis())
                .contains("0.30")
                .contains("is below even odds, so the rolled outcome is not the most likely one")
                .contains("substantive-renewal analysis")
                .contains("the probability is recorded and not weighted into the flows");
            assertThat(likely.recordedBasis())
                .contains("0.90")
                .contains("supports the roll as the expected outcome")
                .doesNotContain("below even odds");
        }

        @Test
        @DisplayName("rolling an amortising facility is refused: it would re-advance repaid principal")
        void rollingAnAmortisingLadderIsRefused() {
            // The boundary between an overlay and schedule construction, and it is the same
            // boundary OptionalityResolver draws when it refuses to extend an amortising
            // term. Re-advancing principal already repaid changes every instalment, which is
            // a new schedule and not a reshaping of this one. And the substance test points
            // the same way: a facility that amortises within its term and is then renewed is
            // far more likely a series of new instruments under ACPIR 46(2)(ii) than one
            // revolving facility, so the right answer is a refusal rather than a longer
            // ladder.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(mortgage(),
                    new BehaviouralOverlay.RolloverAssumption(1, bd("0.9")), MORTGAGE_RATE))
                .withMessageContaining("period 1 repays")
                .withMessageContaining("this ladder amortises within its term")
                .withMessageContaining("series of new instruments");
        }

        @Test
        @DisplayName("a ladder still carrying a terminal lump has nothing well-defined to roll")
        void rollingALadderWithATerminalBalanceIsRefused() {
            // A balloon or residual value left outstanding after the final rung makes the
            // question incoherent rather than hard: rolling the facility either rolls the
            // lump, which is re-advancing a contractual maturity payment, or leaves it
            // outstanding past a maturity that has moved. Neither is an assumption anybody
            // recorded, so the engine refuses instead of choosing one.
            //
            // The fixture is a bullet ladder truncated before its principal rung, so it is
            // NOT amortising — which is what makes this a test of the terminal-balance check
            // and not a second run of the amortisation check above.
            List<InstalmentLadder.Rung> heldOpen = monthlyBullet(4).through(3);
            InstalmentLadder unredeemed =
                InstalmentLadder.of(Money.INR, heldOpen, LAKH_TEN, Money.zero(Money.INR));

            assertThat(unredeemed.terminalBalance()).isEqualTo(LAKH_TEN);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(unredeemed,
                    new BehaviouralOverlay.RolloverAssumption(2, bd("0.8")), ONE_PERCENT_MONTHLY))
                .withMessageContaining("still carries")
                .withMessageContaining("what a rolled facility would roll is undefined");
        }

        @Test
        @DisplayName("a gap in the period ordinals is refused, because replaying shifts every date")
        void aGappedLadderIsRefused() {
            // Only the lengthening overlays need contiguity, and this is why. A ladder may
            // legitimately carry no rung for a period — a moratorium boundary the builder did
            // not emit, a season with no due date — and a truncating overlay is indifferent
            // to it. Replaying a cycle by period ordinal is not: a gap shifts every rolled
            // date by the number of missing rows, and a wrong due date is invisible in the
            // instalment and visible only in the discount factors.
            Money interest = LAKH_TEN.times(ONE_PERCENT_MONTHLY.periodic());
            Money zero = Money.zero(Money.INR);
            List<InstalmentLadder.Rung> gapped = List.of(
                new InstalmentLadder.Rung(1, monthlyDue(1), zero, interest, interest, LAKH_TEN),
                new InstalmentLadder.Rung(2, monthlyDue(2), zero, interest, interest, LAKH_TEN),
                new InstalmentLadder.Rung(4, monthlyDue(4),
                    LAKH_TEN, interest, LAKH_TEN.plus(interest), zero));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(
                    InstalmentLadder.of(Money.INR, gapped, LAKH_TEN, zero),
                    new BehaviouralOverlay.RolloverAssumption(1, bd("0.8")), ONE_PERCENT_MONTHLY))
                .withMessageContaining("must run periods 1..3 without a gap")
                .withMessageContaining("position 3 carries period 4")
                .withMessageContaining("shifts every subsequent due date");
        }

        @Test
        @DisplayName("an extension that cannot be dated asks for the dates rather than guessing one")
        void anUnderivableExtensionAsksForTheDates() {
            // A wrong due date is invisible in the instalment and visible only in the
            // discount factors, so guessing one produces a rate that is wrong in a way
            // nothing reconciles. The generator is therefore verified against the ladder it
            // extends BEFORE it is trusted: it must reproduce every date the ladder already
            // has, and where it cannot — a business-day-adjusted schedule, a seasonal roll —
            // the extension is refused and the caller is asked.
            //
            // The fixture's period 2 falls on the 3rd rather than the 1st, which is what a
            // business-day adjustment looks like when the 1st is a Sunday.
            Money interest = LAKH_TEN.times(ONE_PERCENT_MONTHLY.periodic());
            Money zero = Money.zero(Money.INR);
            List<InstalmentLadder.Rung> adjusted = List.of(
                new InstalmentLadder.Rung(1, LocalDate.of(2026, 5, 1),
                    zero, interest, interest, LAKH_TEN),
                new InstalmentLadder.Rung(2, LocalDate.of(2026, 6, 3),
                    LAKH_TEN, interest, LAKH_TEN.plus(interest), zero));
            InstalmentLadder ladder = InstalmentLadder.of(Money.INR, adjusted, LAKH_TEN, zero);
            BehaviouralOverlay overlay = new BehaviouralOverlay.RolloverAssumption(1, bd("0.8"));

            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    BehaviouralAdjuster.adjust(ladder, overlay, ONE_PERCENT_MONTHLY))
                .withMessageContaining("its due dates cannot be extended")
                .withMessageContaining("supply the extended due dates");

            // Supplied, it rolls: the caller has the calendar, this class does not.
            InstalmentLadder rolled = BehaviouralAdjuster.adjust(ladder, overlay,
                ONE_PERCENT_MONTHLY,
                List.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 3)));
            assertThat(rolled.length()).isEqualTo(4);
            assertThat(rolled.rung(4).dueOn()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(rolled.rung(4).principal()).isEqualTo(LAKH_TEN);
        }

        @Test
        @DisplayName("supplied dates must ascend and must be enough of them")
        void suppliedDatesAreValidated() {
            // Two refusals over one input, and both are the same class of defect: a silently
            // short or out-of-order date list produces a ladder whose rungs no longer ascend
            // in time, which InstalmentLadder would then either refuse for the wrong reason
            // or accept with a discount factor running backwards.
            InstalmentLadder ladder = monthlyBullet(3);
            BehaviouralOverlay overlay = new BehaviouralOverlay.RolloverAssumption(1, bd("0.8"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(ladder, overlay, ONE_PERCENT_MONTHLY,
                    List.of(monthlyDue(4), monthlyDue(5))))
                .withMessageContaining("6 periods against a contractual ladder of 3")
                .withMessageContaining("3 further due dates are needed but 2 were supplied");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(ladder, overlay, ONE_PERCENT_MONTHLY,
                    List.of(monthlyDue(4), monthlyDue(3), monthlyDue(6))))
                .withMessageContaining("does not follow")
                .withMessageContaining("the extended dates must ascend");
        }
    }

    // ========================================================= 9. revolver behaviour

    @Nested
    @DisplayName("a revolver: the utilisation curve drives the balance, the behavioural life closes it")
    class TheRevolverOverlay {

        /** A 24-month CC/OD limit drawn to 1,000,000 at 1% a month. */
        private final InstalmentLadder facility = monthlyBullet(24);

        @Test
        @DisplayName("the contractual leg truncated at expected life, the balance carried as prepayment")
        void truncationCarriesTheBalanceAsAnExpectedPrepayment() {
            // What a behavioural life IS: the point past which the facility is not expected
            // to exist. So the expected leg is the contractual leg through period 12 with the
            // whole outstanding balance repaid there — and the two legs then differ in TIMING
            // only, which the assertions below establish from three directions at once:
            //
            //   * every rung the two legs share is identical, rung for rung, so nothing was
            //     reshaped inside the truncation;
            //   * the whole 1,000,000 is recovered on both legs, so nothing left or entered
            //     the schedule (ACPIR 51 — no expected credit loss is deducted for a non-POCI
            //     instrument, and a revolver is where such a deduction would look most like a
            //     modelling choice);
            //   * only the DATE of the principal flow moved, from 2028-04-01 to 2027-04-01,
            //     which is the entire economic content of the assumption.
            //
            // Independent arithmetic: 1,000,000 drawn throughout at 1% a month bills 10,000.00
            // of interest in each of periods 1 to 11, and period 12 bills 1,010,000.00.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(BigDecimal.ONE), "CARDS/BEH/2026-Q1 v3"),
                ONE_PERCENT_MONTHLY);
            InstalmentLadder expected = adjustment.expected();

            assertThat(expected.length()).isEqualTo(12);
            assertThat(adjustment.expectedLifePeriods()).isEqualTo(12);
            assertThat(adjustment.contractualLifePeriods()).isEqualTo(24);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(bd("0.5"));

            assertThat(expected.rungs().subList(0, 11))
                .isEqualTo(adjustment.contractual().rungs().subList(0, 11));
            assertThat(expected.rung(12).principal()).isEqualTo(LAKH_TEN);
            assertThat(presented(expected.rung(12).interest())).isEqualByComparingTo(bd("10000.00"));
            assertThat(presented(expected.rung(12).total())).isEqualByComparingTo(bd("1010000.00"));
            assertThat(expected.rung(12).balanceAfter().isZero()).isTrue();
            assertThat(expected.terminalBalance().isZero()).isTrue();

            assertThat(presented(BehaviouralAdjustment.principalRecovered(expected)))
                .isEqualByComparingTo(bd("1000000.00"));
            assertThat(presented(BehaviouralAdjustment.principalRecovered(adjustment.contractual())))
                .isEqualByComparingTo(bd("1000000.00"));
            assertThat(expected.rung(12).dueOn()).isEqualTo(LocalDate.of(2027, 4, 1));
            assertThat(adjustment.contractual().rung(24).dueOn())
                .isEqualTo(LocalDate.of(2028, 4, 1));
            assertThat(adjustment.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("the curve is anchored to the opening balance, so its level cannot restate a drawdown")
        void theCurveIsAnchoredAndNotAbsolute() {
            // The specific defect: reading the curve as an absolute drawn fraction. The curve
            // states drawn fractions of a LIMIT and no limit reaches this stage — the
            // blueprint's notional is already limit x averageUtilisation and a ladder carries
            // a balance, not a facility. So the first point is the utilisation at inception,
            // which the opening balance already measures, and the rest of the curve moves the
            // balance in proportion.
            //
            // An implementation that multiplied the opening balance by the curve directly
            // would restate the amount actually drawn at inception — 600,000 against a
            // 1,000,000 disbursement on a [0.6, 0.6] curve — breaking IC-1 against the
            // disbursement that was actually made, and doing it silently.
            //
            // The assertion is an equality between two curves of different LEVEL and the same
            // SHAPE: [0.6, 0.6] must produce exactly the ladder [1.0, 1.0] produces. The
            // absolute reading gives two different ladders, neither opening on 1,000,000.
            InstalmentLadder atSixty = BehaviouralAdjuster.adjust(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(bd("0.6"), bd("0.6")), "CC-OD/2026"),
                ONE_PERCENT_MONTHLY);
            InstalmentLadder atPar = BehaviouralAdjuster.adjust(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(BigDecimal.ONE, BigDecimal.ONE), "CC-OD/2026"),
                ONE_PERCENT_MONTHLY);

            assertThat(BehaviouralAdjuster.openingBalance(atSixty)).isEqualTo(LAKH_TEN);
            assertThat(atSixty.rungs()).isEqualTo(atPar.rungs());
        }

        @Test
        @DisplayName("a rising curve bills a negative principal: a card book lends more in November")
        void aRisingCurveBillsANetAdvance() {
            // Not an error to be smoothed away. Where the curve rises the facility is drawn
            // further, the period's principal movement is negative and so is its total, and
            // that is a net advance — which is what a revolver does. An implementation that
            // floored the principal at zero would silently forecast a card book that never
            // lends again after inception.
            //
            // Independent arithmetic on a [0.5, 0.6] curve, opening 1,000,000, 1% a month:
            //   period 1   interest   1,000,000 x 1%                  =    10,000.00
            //              target     1,000,000 x 0.6 / 0.5           = 1,200,000.00
            //              principal  1,000,000 - 1,200,000           =  -200,000.00
            //              billed     -200,000 + 10,000               =  -190,000.00
            //   periods 2-11 interest 1,200,000 x 1%                  =    12,000.00, no move
            //   period 12  principal  1,200,000, billed 1,212,000.00
            // Total interest 10,000 + 10 x 12,000 + 12,000 = 142,000.00.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(bd("0.5"), bd("0.6")), "CARDS/SEASONAL/2026"),
                ONE_PERCENT_MONTHLY);
            InstalmentLadder expected = adjustment.expected();

            assertThat(presented(expected.rung(1).principal())).isEqualByComparingTo(bd("-200000.00"));
            assertThat(presented(expected.rung(1).total())).isEqualByComparingTo(bd("-190000.00"));
            assertThat(presented(expected.rung(1).balanceAfter())).isEqualByComparingTo(bd("1200000.00"));
            assertThat(presented(expected.rung(2).interest())).isEqualByComparingTo(bd("12000.00"));
            assertThat(expected.rung(6).principal().isZero()).isTrue();
            assertThat(presented(expected.rung(12).principal())).isEqualByComparingTo(bd("1200000.00"));
            assertThat(presented(expected.totalInterest())).isEqualByComparingTo(bd("142000.00"));

            // Principal-WAL exceeds the behavioural life, and legitimately: a net advance in
            // period 1 carries a NEGATIVE weight, so (-200,000 x 1 + 1,200,000 x 12) /
            // 1,000,000 = 14.2 periods = 1.183333... years against a 12-period life. Any
            // downstream assumption that WAL is bounded by life is wrong on a revolver.
            assertThat(Precision.round(adjustment.principalWeightedLifeYears(), 6))
                .isEqualByComparingTo(bd("1.183333"));
            assertThat(adjustment.principalWeightedLifeYears()).isGreaterThan(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a behavioural life past stated maturity extends the ladder and dates the extension")
        void aLifeBeyondStatedMaturityExtendsTheLadder() {
            // A revolver's behavioural life is not bounded by its stated maturity — a card
            // limit reviewed annually has a modelled life of years — so the overlay must be
            // able to lengthen the ladder as well as truncate it, deriving the dates it needs
            // by stepping the FIRST due date at the declared frequency. Anchored, never
            // chained: chaining accumulates the end-of-month clamp and quietly turns a
            // month-end schedule into a 28th-of-the-month one from the second period on.
            InstalmentLadder expected = BehaviouralAdjuster.adjust(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    36, List.of(BigDecimal.ONE), "CARDS/BEH/2026-Q1 v3"),
                ONE_PERCENT_MONTHLY);

            assertThat(expected.length()).isEqualTo(36);
            assertThat(expected.rung(25).dueOn()).isEqualTo(monthlyDue(25));
            assertThat(expected.rung(36).dueOn()).isEqualTo(LocalDate.of(2029, 4, 1));
            assertThat(expected.rung(36).principal()).isEqualTo(LAKH_TEN);
            assertThat(expected.rung(35).principal().isZero()).isTrue();
        }

        @Test
        @DisplayName("a life that does not land on a period boundary is refused, never rounded")
        void aLifeOffThePeriodBoundaryIsRefused() {
            // ACPIR 46(2)(iii) states the life in months and the field says so, but a
            // quarterly ladder needs it in quarters. A 10-month life on a quarterly schedule
            // is either 3.33 quarters, which is not a schedule, or an analysis that was not
            // performed at quarterly granularity — and the engine cannot tell which. Rounding
            // it silently bills a period the analysis never supported, on an instrument whose
            // entire carrying amount is already an estimate.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(
                    bullet(8, LAKH_TEN, THREE_PERCENT_QUARTERLY, false),
                    new BehaviouralOverlay.RevolverBehaviour(
                        10, List.of(BigDecimal.ONE), "KCC/2026"),
                    THREE_PERCENT_QUARTERLY))
                .withMessageContaining("a behavioural life of 10 months does not land on a boundary")
                .withMessageContaining("10 x 4 / 12 is not whole")
                .withMessageContaining("rather than having it rounded here");

            // 9 months is 3 quarters and is accepted, which is what makes the refusal above a
            // statement about the boundary rather than about quarterly ladders.
            assertThat(BehaviouralAdjuster.adjust(
                bullet(8, LAKH_TEN, THREE_PERCENT_QUARTERLY, false),
                new BehaviouralOverlay.RevolverBehaviour(9, List.of(BigDecimal.ONE), "KCC/2026"),
                THREE_PERCENT_QUARTERLY).length()).isEqualTo(3);
        }

        @Test
        @DisplayName("a curve opening at nil or negative gives the curve no scale and is refused")
        void aCurveOpeningAtNilIsRefused() {
            // The anchor is a divisor, so a nil opening point is not a low utilisation — it is
            // an undefined curve. And a curve whose first point is meaningless cannot be
            // anchored to the opening balance at all, which means the only way to use it is
            // to restate the amount actually drawn at inception.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(facility,
                    new BehaviouralOverlay.RevolverBehaviour(
                        12, List.of(BigDecimal.ZERO, bd("0.5")), "CARDS/2026"),
                    ONE_PERCENT_MONTHLY))
                .withMessageContaining("the utilisation curve opens at 0")
                .withMessageContaining("would restate the amount actually drawn at inception");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.adjust(facility,
                    new BehaviouralOverlay.RevolverBehaviour(
                        12, List.of(bd("-0.4"), bd("0.5")), "CARDS/2026"),
                    ONE_PERCENT_MONTHLY))
                .withMessageContaining("the utilisation curve opens at -0.4");
        }

        @Test
        @DisplayName("a curve that turns negative fails on the balance, not on a plausible schedule")
        void aNegativeCurvePointIsNotSilentlyAccepted() {
            // Neither RevolverBehaviour nor the adjuster validates the interior of the curve —
            // only its first point, which is the divisor. A negative interior point is
            // therefore caught downstream, by the Rung constructor refusing the negative
            // balance it implies. Pinned here so the refusal is known to exist and known to
            // be the LAST line of defence: if Rung's guard were ever relaxed, this test fails
            // and the reviewer is told that a negative utilisation now produces a schedule.
            assertThatThrownBy(() -> BehaviouralAdjuster.adjust(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(bd("0.5"), bd("-0.2")), "CARDS/2026"),
                ONE_PERCENT_MONTHLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance after period 1 is negative")
                .hasMessageContaining("principal the borrower never owed");
        }

        @Test
        @DisplayName("the recorded basis carries the ACPIR 46(2)(iii) analysis reference verbatim")
        void theBasisCarriesTheMandatoryAnalysisReference() {
            // The entire carrying amount of a revolver measured this way is an estimate,
            // which is why the analysis reference is mandatory on the overlay and why a
            // revision is a policy version rather than a parameter update. A basis that
            // dropped the reference would leave a balance-sheet item whose whole magnitude is
            // a judgement with nothing in the audit file pointing at the judgement.
            BehaviouralAdjustment adjustment = BehaviouralAdjuster.adjustment(facility,
                new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(bd("0.5"), bd("0.6")), "CARDS/BEH/2026-Q1 v3"),
                ONE_PERCENT_MONTHLY);

            assertThat(adjustment.recordedBasis())
                .contains("behavioural life of 12 months = 12 periods")
                .contains("against a 24-period contractual ladder")
                .contains("2-point utilisation curve anchored on the ladder's opening balance")
                .contains("ACPIR 46(2)(iii) analysis: CARDS/BEH/2026-Q1 v3")
                .contains("a revision is a policy version rather than a parameter update");
        }

        @Test
        @DisplayName("a revolver overlay applied to an amortising ladder re-advances the amortisation")
        void aRevolverOverlayOnAnAmortisingLadderReadvancesPrincipal() {
            // The asymmetry, pinned as it stands rather than as it ought to be. rollover()
            // refuses an amortising ladder outright, because rolling one means re-advancing
            // principal already repaid; revolver() has no equivalent guard, and applying it
            // to the O6 mortgage replaces 240 rungs of amortisation with a curve-driven
            // bullet — period 1 repays nothing where the contract repaid 7,486.30, and the
            // whole 5,000,000 arrives at the truncation period instead.
            //
            // ST-3 cannot see it: principal telescopes to the opening balance whatever path
            // the balance took, so both legs recover 5,000,000 and the invariant passes.
            //
            // Why this is pinned as-is rather than reported as a live defect: the composition
            // is unreachable through a constructed blueprint. ScheduleBlueprint's ST-11 check
            // requires a RevolverBehaviour to sit on a UtilisationDriven disbursement, and a
            // UtilisationDriven disbursement to carry BulletAtMaturity or NoneUntilMaturity
            // principal — so no amortising ladder can be paired with this overlay by any
            // route the pipeline offers, and no published figure can be wrong because of it.
            // The next test asserts that gate, so that if it is ever relaxed the reviewer is
            // told that revolver()'s missing amortisation guard has become load-bearing.
            // Closing the hole directly would mean giving revolver() the requireBullet check
            // that rollover() already has.
            InstalmentLadder expected = BehaviouralAdjuster.adjust(mortgage(),
                new BehaviouralOverlay.RevolverBehaviour(
                    24, List.of(BigDecimal.ONE), "not a revolver at all"),
                MORTGAGE_RATE);

            assertThat(expected.length()).isEqualTo(24);
            assertThat(expected.rung(1).principal().isZero()).isTrue();
            assertThat(presented(mortgage().rung(1).principal())).isEqualByComparingTo(bd("7486.30"));
            assertThat(expected.rung(24).principal()).isEqualTo(MORTGAGE);
            assertThat(presented(BehaviouralAdjustment.principalRecovered(expected)))
                .isEqualByComparingTo(bd("5000000.00"));
        }

        @Test
        @DisplayName("the blueprint refuses the composition, so no published figure can reach it")
        void theBlueprintRefusesARevolverOverlayOnANonRevolvingFacility() {
            // The reachability argument for the test above, asserted rather than assumed. If
            // this ST-11 check were ever relaxed, this test fails and the reviewer is told
            // that revolver()'s missing amortisation guard has become load-bearing.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduleBlueprint(
                    LAKH_TEN, Money.INR, VALUE_DATE, monthlyDue(24),
                    new DisbursementProfile.Single(VALUE_DATE, LAKH_TEN),
                    new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    Moratorium.none(),
                    new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
                    OptionSchedule.none(),
                    new BehaviouralOverlay.RevolverBehaviour(
                        12, List.of(BigDecimal.ONE), "CARDS/2026"),
                    MONTHLY, DayCountConvention.THIRTY_360_BOND,
                    ResiduePolicy.FINAL_PERIOD_PLUG, 24))
                .withMessageContaining("incoherent blueprint (ST-11)")
                .withMessageContaining("REVOLVER_BEHAVIOUR describes a drawn-and-repaid limit");
        }
    }

    // ============================================== 10. the par test — docs 09 § 3.4

    @Nested
    @DisplayName("a re-estimation is a P&L event only away from par (docs 09 § 3.4, ST-9)")
    class TheParTest {

        @Test
        @DisplayName("premium is positive and discount negative: the sign is the opposite of INV-4's")
        void theSignConventionIsThePremiumConvention() {
            // Two quantities differing by nothing but their sign, both used in the sources,
            // and therefore dangerous to hold in one head. This method returns the
            // PREMIUM — EIR carrying amount less contractual balance — which is positive for
            // an instrument bought above par or carrying an unamortised integral COST. INV-4's
            // "unamortised fee" is defined the other way round, contractual less EIR, and is
            // positive for a fee received. docs 09 § 3.4 tabulates a premium as +7,688.30,
            // which is this method's sign.
            //
            // An implementation with the subtraction reversed passes every zero-at-par test
            // and then reports every premium as a discount, which flips the direction of
            // every catch-up the engine posts.
            assertThat(presented(BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                Money.inr("1000000"), Money.inr("1007688.30"))))
                .isEqualByComparingTo(bd("7688.30"));
            assertThat(presented(BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                Money.inr("1000000"), Money.inr("992152.40"))))
                .isEqualByComparingTo(bd("-7847.60"));
            assertThat(BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                Money.inr("485832.21"), Money.inr("485832.21")).isZero()).isTrue();
        }

        @Test
        @DisplayName("the screen is false at par and true either side of it, by one paisa")
        void theScreenKeysOnTheUnamortisedBalance() {
            // The method a batch calls before it calls CatchUpCalculator, and calling it in
            // that order is the difference between a close that finishes and one that does
            // not. At par the catch-up is exactly zero for any revision of any assumption,
            // because the revised flows discount at the contractual rate to the outstanding
            // balance whatever speed is assumed — so a restatement would post nothing,
            // disclose nothing, and cost a solve. Keying on "did an assumption move" instead
            // re-estimates every contract a curve refresh touches: at 10 million contracts
            // that is correctness-neutral and ruinous.
            //
            // Both directions are asserted, one paisa either side, because a premium and a
            // discount are separately capable of reading as zero by accident.
            Money contractual = Money.inr("485832.21");

            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(contractual, contractual))
                .isFalse();
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.22"))).isTrue();
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.20"))).isTrue();
            // docs 09 § 3.4's premium and discount rows on the O7 note.
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("493520.52"))).isTrue();
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("477984.62"))).isTrue();
        }

        @Test
        @DisplayName("arithmetic dust is not a premium: 1e-20 of residue stays at par")
        void workingPrecisionDustDoesNotLeavePar() {
            // Measured at presentation scale, deliberately, and this is the assertion that
            // justifies it. A residue of 1e-20 left by 28-digit intermediates is not a
            // premium, and the catch-up it can produce is bounded by it — there is no
            // arrangement of revised flows that accelerates a premium of nothing into a
            // posting of something.
            //
            // Comparing at working precision would classify the ENTIRE par book as
            // away-from-par on dust and reintroduce the exact churn the screen exists to
            // prevent, on the one population where the correct answer is known in advance
            // without computing it. A sub-half-paisa residue is the same case at a scale a
            // reader could nearly see: 0.004 is not a premium either.
            Money contractual = Money.inr("485832.21");

            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.21000000000000000001"))).isFalse();
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.214"))).isFalse();
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.206"))).isFalse();
            // Half a paisa rounds away from zero under HALF_UP, so it does leave par. The
            // boundary is asserted so the tolerance cannot be widened without a test failing.
            assertThat(BehaviouralAdjuster.reestimationIsAPnlEvent(
                contractual, Money.inr("485832.215"))).isTrue();
        }

        @Test
        @DisplayName("ST-9 fails on a par instrument that posts a catch-up, and names the deviation")
        void st9CatchesACatchUpAtPar() {
            // The invariant asserted in production and not only in tests, because the failure
            // it catches is silent. A par-priced contract that posts a catch-up has been
            // discounted at something other than the rate its flows accrue at — a re-solved
            // rate substituted for the retained one, which is the CU-1 defect CatchUpCalculator
            // is built to make impossible and that this invariant detects from the other end.
            //
            // 876.38 is docs 09 § 3.4's premium-row catch-up: the right number on the wrong
            // instrument, which is exactly how the substitution presents.
            Money contractual = Money.inr("485832.21");
            InvariantResult breach = BehaviouralAdjuster.catchUpIsNilAtPar(
                contractual, contractual, Money.inr("-876.38"));

            assertThat(breach.satisfied()).isFalse();
            assertThat(breach.id()).isEqualTo(InvariantId.ST_9);
            assertThat(breach.deviation()).isEqualByComparingTo(bd("-876.38"));
            assertThat(breach.detail())
                .contains("re-estimation at par")
                .contains("discount at the contractual rate to the outstanding balance whatever"
                    + " speed is assumed");

            InvariantResult held = BehaviouralAdjuster.catchUpIsNilAtPar(
                contractual, contractual, Money.zero(Money.INR));
            assertThat(held.satisfied()).isTrue();
            assertThat(held.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("away from par ST-9 makes no claim, so a real catch-up is not reported as a breach")
        void st9MakesNoClaimAwayFromPar() {
            // An invariant that flagged a legitimate posting is an invariant nobody leaves
            // switched on, and switching this one off is how the par-book defect above gets
            // back in. So the away-from-par branch passes carrying the premium that licenses
            // the posting, and says in as many words that it is making no claim about its
            // size.
            //
            // The figures are docs 09 § 3.4's premium row: 493,520.52 of EIR carrying amount
            // against a 485,832.21 pool balance is 7,688.31 of unamortised premium, and the
            // -876.38 catch-up is what the CPR revision from 10% to 20% costs there.
            InvariantResult result = BehaviouralAdjuster.catchUpIsNilAtPar(
                Money.inr("485832.21"), Money.inr("493520.52"), Money.inr("-876.38"));

            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .contains("held away from par by")
                .contains("ST-9 makes no claim about its size");
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);

            // And a nil catch-up away from par is not a breach either: ST-9 is a claim about
            // the par population only, in both directions.
            assertThat(BehaviouralAdjuster.catchUpIsNilAtPar(
                Money.inr("485832.21"), Money.inr("493520.52"), Money.zero(Money.INR))
                .satisfied()).isTrue();
        }

        @Test
        @DisplayName("a dust catch-up at par is not a breach: the difference is reduced once")
        void aDustCatchUpAtParIsNotABreach() {
            // The reduce-once rule of 03 § 1.3, reaching ST-9 through InvariantResult.ofMoney.
            // A catch-up of 0.004 at par is a working-precision residue, not a posting, and
            // reporting it as a control exception would block a period close on a contract
            // where nothing is wrong. The failure mode is not hypothetical: on a 30-year
            // quarterly exposure the two legs came to 242,103,892.5032 and 242,103,892.5063 —
            // three thousandths of a paise apart — and rounding the operands before
            // differencing them straddled a boundary and reported a one-paise breach.
            Money contractual = Money.inr("485832.21");

            assertThat(BehaviouralAdjuster.catchUpIsNilAtPar(
                contractual, contractual, Money.inr("0.004")).satisfied()).isTrue();
            assertThat(BehaviouralAdjuster.catchUpIsNilAtPar(
                contractual, contractual, Money.inr("0.01")).satisfied()).isFalse();
        }

        @Test
        @DisplayName("a currency mismatch is a caller defect and raises, rather than reporting a breach")
        void aCurrencyMismatchRaisesRatherThanReporting() {
            // A deviation measured across two currencies is a figure nobody could reconcile,
            // so it is not reported as a breach at all. It also means a caller wrapping an
            // invariant set in breach handling will NOT catch this — worth pinning, because a
            // control that silently reclassifies a defect as a breach is worse than one that
            // fails loudly.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> BehaviouralAdjuster.unamortisedPremiumOrDiscount(
                    Money.inr("1000000"),
                    Money.of("1000000", Currency.getInstance("USD"))))
                .withMessageContaining("currency mismatch");
            assertThatNullPointerException()
                .isThrownBy(() -> BehaviouralAdjuster.catchUpIsNilAtPar(
                    Money.inr("1"), Money.inr("1"), null));
        }
    }

    // ========================== 11. opening balance and principal-weighted life

    @Nested
    @DisplayName("the two reporting helpers, and the conventions they must not confuse")
    class ReportingHelpers {

        @Test
        @DisplayName("the opening balance is reconstructed from the first rung, capitalisation included")
        void theOpeningBalanceHandlesACapitalisingRung() {
            // Reconstructed as balanceAfter + principal rather than taken from a notional the
            // ladder does not carry, and the identity holds on every rung by the
            // roll-forward's own construction — INCLUDING a capitalising moratorium rung,
            // where principal is negative and the opening balance is therefore BELOW the
            // balance the rung leaves behind.
            //
            // That last case is the one a naive implementation gets wrong: reading the first
            // rung's balanceAfter as the opening, or adding the interest instead of the
            // principal, gives 1,010,000 on the fixture below and every subsequent figure
            // then scales off a notional 10,000 too large.
            Money capitalised = Money.inr("10000");
            Money grown = Money.inr("1010000");
            Money zero = Money.zero(Money.INR);
            List<InstalmentLadder.Rung> rungs = List.of(
                new InstalmentLadder.Rung(1, monthlyDue(1),
                    capitalised.negate(), capitalised, zero, grown),
                new InstalmentLadder.Rung(2, monthlyDue(2),
                    grown, grown.times(ONE_PERCENT_MONTHLY.periodic()),
                    grown.plus(grown.times(ONE_PERCENT_MONTHLY.periodic())), zero));
            InstalmentLadder ladder = InstalmentLadder.of(Money.INR, rungs, LAKH_TEN, zero);

            assertThat(BehaviouralAdjuster.openingBalance(ladder)).isEqualTo(LAKH_TEN);
            assertThat(ladder.rung(1).balanceAfter().amount()).isGreaterThan(LAKH_TEN.amount());
            assertThatNullPointerException()
                .isThrownBy(() -> BehaviouralAdjuster.openingBalance(null));
        }

        @Test
        @DisplayName("the average life is weighted by principal, not by cash: 12.88y, not 10.04y")
        void theAverageLifeIsPrincipalWeightedAndNotCashWeighted() {
            // The market convention, and the two computations are easy to confuse and not
            // close. Cash-weighting drags the figure towards the interest-heavy early periods
            // of an amortising loan; docs 09 § 6 O6 quotes 12.88 years against a 240-month
            // term, which is the principal-weighted figure, and a cash-weighted computation on
            // the same ladder reports something else entirely and compares to no published
            // WAL anywhere.
            //
            // Independent arithmetic for the contrast: on the O6 ladder every period bills the
            // same 44,986.30, so sum(t x total_t) / sum(total_t) collapses to very nearly the
            // average period ordinal — 241/2 = 120.5 months = 10.04 years, pulled down a
            // whisker by the smaller final stub. 12.88 against 10.04 is a 2.8-year difference
            // on one instrument, so an engine that mislabelled one as the other would publish
            // a WAL almost three years short and compare it to no published figure anywhere.
            InstalmentLadder ladder = mortgage();
            BigDecimal principalWeighted =
                BehaviouralAdjustment.principalWeightedLifeYears(ladder, 12);

            BigDecimal cashWeighted = BigDecimal.ZERO;
            BigDecimal cash = BigDecimal.ZERO;
            for (InstalmentLadder.Rung rung : ladder.rungs()) {
                BigDecimal periods = BigDecimal.valueOf(rung.periodIndex());
                cashWeighted = cashWeighted.add(
                    rung.total().amount().multiply(periods, Precision.WORKING), Precision.WORKING);
                cash = cash.add(rung.total().amount(), Precision.WORKING);
            }
            cashWeighted = cashWeighted
                .divide(cash, Precision.WORKING)
                .divide(BigDecimal.valueOf(12), Precision.WORKING);

            // 12.88 is the gate: it is docs 09 § 6 O6's published figure and it is the
            // engine's output. The ordering below is a second gate on the same output, and
            // it is one-sided on purpose — cashWeighted is the test's own loop over the
            // test's own fixture, so an engine that returned a cash-weighted figure from
            // principalWeightedLifeYears fails on 12.88 first and on the ordering second.
            //
            // A `Precision.round(cashWeighted, 2) == 10.04` assertion stood here and was
            // removed. No engine routine computes a cash-weighted life, so both sides of it
            // were the test's arithmetic over the test's fixture: nothing in the code under
            // test could disagree with it, and it made the test read as a two-sided
            // discrimination between two conventions when only one side is production code.
            // 10.04 stays in the comment above, which is where a contrast figure belongs.
            assertThat(Precision.round(principalWeighted, 2)).isEqualByComparingTo(bd("12.88"));
            assertThat(principalWeighted).isGreaterThan(cashWeighted);
        }

        @Test
        @DisplayName("the retained terminal balance is weighted at the final period, not omitted")
        void theTerminalBalanceIsWeightedAndNotDropped() {
            // A residual value is principal the lessor recovers on the last day and nowhere
            // else, so omitting it would shorten the reported life of every balloon and every
            // residual-value structure by exactly the weight of the LARGEST principal flow in
            // the schedule.
            //
            // Independent arithmetic on the balloon() fixture: the 24 rungs carry 600,000 of
            // principal between them and the retained lump is 400,000 at period 24, so
            // dropping it changes the divisor from 1,000,000 to 600,000 and removes 9,600,000
            // of period-weight. Including it gives 1.448817 years; excluding it gives
            // 1.081361. The engine must report the longer figure.
            InstalmentLadder ladder = balloon();
            BigDecimal withTerminal = BehaviouralAdjustment.principalWeightedLifeYears(ladder, 12);

            BigDecimal weighted = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            for (InstalmentLadder.Rung rung : ladder.rungs()) {
                BigDecimal periods = BigDecimal.valueOf(rung.periodIndex());
                weighted = weighted.add(
                    rung.principal().amount().multiply(periods, Precision.WORKING),
                    Precision.WORKING);
                total = total.add(rung.principal().amount(), Precision.WORKING);
            }
            BigDecimal withoutTerminal = weighted
                .divide(total, Precision.WORKING)
                .divide(BigDecimal.valueOf(12), Precision.WORKING);

            // 1.448817 is the engine's figure and the gate. The ordering is the second gate:
            // an implementation that dropped the retained lump would produce withoutTerminal
            // exactly, failing both.
            //
            // A `Precision.round(withoutTerminal, 6) == 1.081361` assertion stood here and
            // was removed. withoutTerminal is this test's own loop over this test's own
            // fixture and no engine routine omits the terminal balance, so no production
            // change could turn that line red — it checked that the fixture is still the
            // fixture, while reading as a third independent gate. 1.081361 stays in the
            // comment above as the contrast figure it is.
            assertThat(Precision.round(withTerminal, 6)).isEqualByComparingTo(bd("1.448817"));
            assertThat(withTerminal).isGreaterThan(withoutTerminal);
        }

        @Test
        @DisplayName("a ladder recovering no net principal has no weighted life, and says zero")
        void aNilNetPrincipalHasNoWeightedLife() {
            // A revolver whose expected drawdown exactly offsets its expected repayment over
            // the behavioural life — a real, if uncommon, curve. The average is genuinely
            // undefined there, and zero is returned rather than a division raised from inside
            // a reporting helper: a disclosure routine that threw would take down the batch
            // over a figure nobody needed.
            //
            // The fixture is one advance of 500,000 and one repayment of 500,000, closing on
            // a nil balance, so the weights are +500,000 x 2 and -500,000 x 1 over a total of
            // zero.
            Money half = Money.inr("500000");
            Money zero = Money.zero(Money.INR);
            List<InstalmentLadder.Rung> rungs = List.of(
                new InstalmentLadder.Rung(1, monthlyDue(1), half.negate(), zero, half.negate(), half),
                new InstalmentLadder.Rung(2, monthlyDue(2), half, zero, half, zero));
            InstalmentLadder offsetting = new InstalmentLadder(Money.INR, rungs, zero, List.of());

            assertThat(BehaviouralAdjustment.principalWeightedLifeYears(offsetting, 12))
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    BehaviouralAdjustment.principalWeightedLifeYears(mortgage(), 0))
                .withMessageContaining("periodsPerYear must be >= 1");
        }
    }

    // ================================================ 12. the two value types

    @Nested
    @DisplayName("the overlay and the adjustment refuse what cannot be disclosed")
    class TheValueTypes {

        @Test
        @DisplayName("a Contractual overlay with no stated basis is refused, blank as well as null")
        void aContractualOverlayNeedsAStatedBasis() {
            // The whole point of the variant. "We used contractual life" and "we never
            // considered life" produce identical numbers and very different audit outcomes,
            // and a blank basis is indistinguishable from the second. Immateriality, the
            // absence of a reliable estimate, or a product where prepayment is not permitted
            // are all acceptable answers; nothing is not.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.Contractual("   "))
                .withMessageContaining("indistinguishable from never having considered it");
            assertThatNullPointerException()
                .isThrownBy(() -> new BehaviouralOverlay.Contractual(null));
        }

        @Test
        @DisplayName("every overlay validates its own domain at construction")
        void theOverlayVariantsValidateTheirInputs() {
            // Each of these is a range that cannot be repaired downstream. A CPR of 1 has no
            // periodic root; an empty vector is a curve with no points, which is not the same
            // object as a nil curve; a negative rollover count is not a shorter life; a
            // probability above 1 is not a probability; and a behavioural life of zero months
            // is a facility that closes before its first due date.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.ConstantPrepaymentRate(BigDecimal.ONE))
                .withMessageContaining("fraction in [0,1)");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.CprVector(List.of()))
                .withMessageContaining("needs at least one point");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.CprVector(List.of(bd("0.1"), bd("1.2"))))
                .withMessageContaining("each CPR is a fraction in [0,1)");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RolloverAssumption(-1, bd("0.5")))
                .withMessageContaining("expectedRollovers must not be negative");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RolloverAssumption(1, bd("1.5")))
                .withMessageContaining("rolloverProbability is a fraction in [0,1]");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RevolverBehaviour(
                    0, List.of(BigDecimal.ONE), "CARDS/2026"))
                .withMessageContaining("behaviouralLifeMonths must be >= 1");
        }

        @Test
        @DisplayName("a modelled behavioural life without an analysis reference is refused")
        void aModelledLifeMustReferenceItsAnalysis() {
            // ACPIR 46(2)(iii) mandates the supporting analysis for a card book — historical
            // default patterns, drawdown behaviour, and the effectiveness of limit reduction,
            // suspension or cancellation. The UK experience is the cautionary tale: spreading
            // card economics over a modelled behavioural life creates a balance-sheet item
            // whose entire magnitude is an estimate, and it has been a recurring source of
            // restatement. An unevidenced life is the defect this field exists to prevent, so
            // it is refused at construction rather than reported later.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RevolverBehaviour(
                    12, List.of(BigDecimal.ONE), " "))
                .withMessageContaining("must reference the analysis supporting it")
                .withMessageContaining("an unevidenced life is the defect this field exists"
                    + " to prevent");
        }

        @Test
        @DisplayName("altersFlows scans the whole vector, and a label carries the assumption")
        void altersFlowsAndLabelsAreDerivedFromTheAssumption() {
            // altersFlows() is the single gate on the pass-through, so a vector must scan
            // every point rather than test its first: [0, 0, 0.09] alters flows and [0, 0, 0]
            // does not, and an implementation testing only the head would send the first
            // through the identity path and report contractual life on a pool that prepays
            // from month three.
            //
            // The labels are asserted because they are what a recorded basis is built out of
            // — a basis reading "CPR_VECTOR converted to a mortality of..." with no point
            // count is not re-performable.
            assertThat(new BehaviouralOverlay.CprVector(
                List.of(BigDecimal.ZERO, BigDecimal.ZERO, bd("0.09"))).altersFlows()).isTrue();
            assertThat(new BehaviouralOverlay.CprVector(
                List.of(BigDecimal.ZERO, BigDecimal.ZERO)).altersFlows()).isFalse();
            assertThat(new BehaviouralOverlay.ConstantPrepaymentRate(BigDecimal.ZERO)
                .altersFlows()).isFalse();
            assertThat(new BehaviouralOverlay.RolloverAssumption(0, bd("0.9"))
                .altersFlows()).isFalse();
            // A revolver always alters flows: even a life equal to contractual maturity
            // restates the balance path from the curve rather than from the schedule.
            assertThat(new BehaviouralOverlay.RevolverBehaviour(
                24, List.of(BigDecimal.ONE), "CARDS/2026").altersFlows()).isTrue();

            assertThat(new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")).label())
                .isEqualTo("CPR(0.08)");
            assertThat(new BehaviouralOverlay.CprVector(List.of(bd("0.1"), bd("0.2"))).label())
                .isEqualTo("CPR_VECTOR(2 points)");
            assertThat(new BehaviouralOverlay.RolloverAssumption(2, bd("0.75")).label())
                .isEqualTo("ROLLOVER(2 @ 0.75)");
            assertThat(new BehaviouralOverlay.RevolverBehaviour(
                18, List.of(BigDecimal.ONE), "KCC/2026").label())
                .isEqualTo("REVOLVER_BEHAVIOUR(18m)");
        }

        @Test
        @DisplayName("a CPR vector holds its last point flat past its end, and refuses period zero")
        void forPeriodHoldsFlatAndIsOneBased() {
            // The behaviour the roll-forward depends on, asserted on the accessor as well as
            // through the ladder, because they fail differently: a vector that returned zero
            // past its end would extrapolate a fitted curve as "prepayment stops", and a
            // vector that treated periodIndex as 0-based would apply the whole curve one
            // month early.
            BehaviouralOverlay.CprVector vector =
                new BehaviouralOverlay.CprVector(List.of(bd("0.30"), bd("0.20"), bd("0.10")));

            assertThat(vector.forPeriod(1)).isEqualByComparingTo(bd("0.30"));
            assertThat(vector.forPeriod(3)).isEqualByComparingTo(bd("0.10"));
            assertThat(vector.forPeriod(4)).isEqualByComparingTo(bd("0.10"));
            assertThat(vector.forPeriod(240)).isEqualByComparingTo(bd("0.10"));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> vector.forPeriod(0))
                .withMessageContaining("periodIndex is 1-based");
        }

        @Test
        @DisplayName("an adjustment with a blank basis or an impossible life is refused")
        void theAdjustmentRefusesWhatCannotBeDisclosed() {
            // The same 09 § 2.7 argument, enforced on the output value rather than on the
            // input overlay, because the two are separately constructible: BehaviouralAdjuster
            // always supplies a basis, and this guard is what stops a future caller building
            // the record directly and shipping a figure with no derivation attached.
            InstalmentLadder ladder = monthlyBullet(6);
            BehaviouralOverlay overlay = new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralAdjustment(overlay, ladder, ladder, "  ",
                    6, 6, 12, BigDecimal.ONE, List.of()))
                .withMessageContaining("indistinguishable from never having formed one");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralAdjustment(overlay, ladder, ladder, "basis",
                    6, 6, 0, BigDecimal.ONE, List.of()))
                .withMessageContaining("periodsPerYear must be >= 1");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralAdjustment(overlay, ladder, ladder, "basis",
                    0, 6, 12, BigDecimal.ONE, List.of()))
                .withMessageContaining("lives are 1-based period ordinals");
        }

        @Test
        @DisplayName("the lives are read off the ladders, so a caller cannot report one they do not have")
        void theLivesAreReadOffTheLadders() {
            // BehaviouralAdjustment.of derives both lives from the ladders it is given rather
            // than accepting them, which is the difference between a life that is a fact
            // about the schedule and a life that is an assertion about it. The fixture pairs
            // a 24-period contractual ladder with a 6-period expected one and asserts the
            // pair that comes out, including the ratio — 6/24 = 0.25 — which is the figure a
            // behavioural policy committee actually argues about.
            BehaviouralAdjustment adjustment = BehaviouralAdjustment.of(
                new BehaviouralOverlay.RevolverBehaviour(
                    6, List.of(BigDecimal.ONE), "CARDS/2026"),
                monthlyBullet(24), monthlyBullet(6), "stated basis", 12);

            assertThat(adjustment.contractualLifePeriods()).isEqualTo(24);
            assertThat(adjustment.expectedLifePeriods()).isEqualTo(6);
            assertThat(adjustment.lifeRatio()).isEqualByComparingTo(bd("0.25"));
            assertThat(adjustment.altered()).isTrue();
            // The cross-leg ST-3 comes first, then the expected ladder's own two results, so
            // a movement schedule can report the leg tie and the ladder's integrity apart.
            assertThat(adjustment.invariants()).extracting(InvariantResult::id)
                .containsExactly(InvariantId.ST_3, InvariantId.ST_3, InvariantId.ST_5);
            assertThat(adjustment.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("the cross-leg ST-3 fails where the expected leg does not tie to the contractual one")
        void theCrossLegSt3CanFail() {
            // Without this the ST-3 assertions everywhere above are assertions that cannot
            // fail, which is worse than no assertion: they would read as coverage of the
            // leg-tie while gating nothing. So the failing case is constructed explicitly —
            // a 1,000,000 contractual ladder against a 500,000 expected one, which is an
            // overlay that lost half the principal in time-shifting it.
            //
            // Note what this does and does not catch. Both legs telescope, so ST-3 here
            // detects an expected leg built on a DIFFERENT OPENING BALANCE from the
            // contractual leg — which is what an ECL haircut or a mis-anchored utilisation
            // curve would produce — and it cannot detect a wrong balance PATH between two
            // correct endpoints. The path is what the level assertions in
            // TheO6RollForward are for.
            BehaviouralAdjustment mismatched = BehaviouralAdjustment.of(
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                monthlyBullet(24),
                bullet(12, Money.inr("500000"), ONE_PERCENT_MONTHLY, true),
                "stated basis", 12);

            InvariantResult crossLeg = mismatched.invariants().get(0);
            assertThat(crossLeg.id()).isEqualTo(InvariantId.ST_3);
            assertThat(crossLeg.satisfied()).isFalse();
            assertThat(crossLeg.deviation()).isEqualByComparingTo(bd("-500000.00"));
            assertThat(mismatched.allSatisfied()).isFalse();
        }
    }
}
