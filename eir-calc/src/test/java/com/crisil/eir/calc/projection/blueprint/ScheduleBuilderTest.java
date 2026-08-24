package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Step 3 of the blueprint pipeline: {@link ScheduleBuilder} turning a
 * {@link ScheduleBlueprint} into the contractual {@link InstalmentLadder}
 * (docs 09 § 8, fixtures S1–S6).
 *
 * <p><b>Every figure asserted below is a fixture from docs 09 § 6, or closed-form
 * arithmetic stated in the comment that uses it.</b> The base loan throughout is
 * doc 09's: 1,000,000 at 12% nominal monthly — 1.00% per period, never 12% divided
 * by 12 — over 24 monthly periods from 1 April 2026. That loan makes the fixtures
 * directly comparable: the same principal under six different combinations of the
 * repayment, servicing and moratorium dimensions, so a figure that moves has moved
 * because a dimension changed and not because the fixture did.
 *
 * <p>Four kinds of assertion are made here, and they catch four different defects.
 *
 * <p><b>1. The instalment itself</b>, against the fixture. 60,982.05 rather than
 * 47,073.47 is the whole content of {@code COMPRESS_REMAINING}; 53,043.57 rather
 * than 47,073.47 is the whole content of capitalising rather than deferring. These
 * are single numbers that a mis-sized annuity, a wrong phase length or a rate
 * applied to the wrong balance all move.
 *
 * <p><b>2. The principal/interest split and where the rounded paise land.</b> The
 * columns must sum to the billed total (03 § 5.7), and which component absorbs the
 * paise depends on which one the contract fixes. On fixture S1 the two readings are
 * an interest total of 125,000.00 and one of 124,999.99 — the first ties
 * 1,125,000.00 of cash against 1,000,000 of principal, the second ties to nothing.
 * That one paise is asserted because it is the difference between a movement
 * schedule that reconciles and one that does not.
 *
 * <p><b>3. The residue, at its true size.</b> The true annuity is 47,073.472223 and
 * the billed instalment is 47,073.47; over 24 periods the shortfall compounds to
 * 0.059969 of terminal balance. That figure is derived here independently as
 * {@code 1,000,000 × 1.01^24 − 47,073.47 × ((1.01^24 − 1) / 0.01)} and asserted to
 * six decimal places, because a residue rounded, absorbed or tolerated away is the
 * defect class ADR-0002 exists to refuse.
 *
 * <p><b>4. An independent price check.</b> {@link #presentValueAtOnePercent} is
 * arithmetic this engine has no routine for: discount the ladder's own billed totals
 * at the contractual 1% and the answer must be the 1,000,000 advanced. It is the
 * assertion that catches the classic sizing errors — a balloon whose instalments
 * were sized on the full principal over-collects by the balloon's present value, and
 * a step ladder sized on its first rung over-collects by whatever the ladder adds —
 * neither of which shows up in any single instalment.
 *
 * <p><b>What is deliberately not asserted.</b> {@code ladder.allSatisfied()} is
 * never used as evidence. ST-3 and ST-5 cannot fail on a ladder this builder
 * produced: the final roll closes the last rung on the intended terminal, which
 * forces ST-5, and the principal column then telescopes to the advance net of that
 * terminal, which forces ST-3 given the ST-6 notional check that already ran. An
 * assertion no input can falsify is worth less than the comment explaining why, so
 * where those invariants carry real content — the capitalising holiday, where
 * −126,825.03 of holiday principal and +1,126,825.03 of amortising principal have to
 * sum to the 1,000,000 advanced — the two component sums are asserted instead, and
 * those can and do disagree if the capitalisation is wrong.
 *
 * <p>The default residue policy in these fixtures is {@link
 * ResiduePolicy#LMS_AUTHORITATIVE}, which derives nothing and plugs nothing, so the
 * instalments come out as doc 09 quotes them. The plug policies get their own
 * section.
 */
@DisplayName("ScheduleBuilder: a blueprint resolved into the contractual ladder")
class ScheduleBuilderTest {

    /** Doc 09 § 6's base loan, and reference case 1's principal. */
    private static final Money NOTIONAL = Money.inr("1000000");

    /** Reference case 1's disbursement date. Nothing in this file reads a clock. */
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** Twenty-four monthly periods after the value date. */
    private static final LocalDate STATED_MATURITY = LocalDate.of(2028, 4, 1);

    /** The due date of period 12 — the end of a twelve-period holiday. */
    private static final LocalDate PERIOD_12_DUE = LocalDate.of(2027, 4, 1);

    /** The due date of period 18, for the settlement date that is later than the default. */
    private static final LocalDate PERIOD_18_DUE = LocalDate.of(2027, 10, 1);

    /** 1% per period. 12% p.a. nominal with monthly compounding, not 12% / 12. */
    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");

    /**
     * The ACPIR 46(1) horizon. A separate input from expected life and never derived
     * from it; large enough here to cover the 36-period extended ladders.
     */
    private static final int ECL_HORIZON_PERIODS = 36;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ----------------------------------------------------------- dimension defaults

    private static DisbursementProfile singleDraw() {
        return new DisbursementProfile.Single(VALUE_DATE, NOTIONAL);
    }

    private static RateProfile onePercentFixed() {
        return new RateProfile.Fixed(Rate.monthly(ONE_PERCENT));
    }

    /**
     * A plain monthly calendar: no business-day convention, no holidays, no
     * month-end rule to fire. The value date is the 1st, so every due date is the 1st
     * and the dating is not what any assertion below is measuring.
     */
    private static ScheduleCalendar plainMonthly() {
        return new ScheduleCalendar(
            ScheduleCalendar.Frequency.MONTHLY,
            ScheduleCalendar.BusinessDayConvention.NONE,
            Set.of(),
            ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
            List.of());
    }

    private static ScheduleBlueprint compose(
        DisbursementProfile disbursement,
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        RateProfile rate,
        ResiduePolicy residuePolicy) {

        return new ScheduleBlueprint(
            NOTIONAL, Money.INR, VALUE_DATE, STATED_MATURITY,
            disbursement, principal, servicing, moratorium, rate,
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual("contractual leg only; no behavioural overlay"),
            plainMonthly(), DayCountConvention.THIRTY_360_BOND, residuePolicy,
            ECL_HORIZON_PERIODS);
    }

    /** The three dimensions most fixtures vary, on the base loan, residue left visible. */
    private static ScheduleBlueprint compose(
        PrincipalProfile principal, InterestServicing servicing, Moratorium moratorium) {

        return compose(singleDraw(), principal, servicing, moratorium, onePercentFixed(),
            ResiduePolicy.LMS_AUTHORITATIVE);
    }

    /** The same, with an explicit residue policy. */
    private static ScheduleBlueprint compose(
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        ResiduePolicy residuePolicy) {

        return compose(singleDraw(), principal, servicing, moratorium, onePercentFixed(),
            residuePolicy);
    }

    /** Reference case 1: the 24-period EMI loan, interest serviced, no holiday. */
    private static ScheduleBlueprint case1() {
        return compose(new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(), Moratorium.none());
    }

    // ------------------------------------------------------------ derived quantities

    /**
     * The ladder's billed cash discounted at the contractual 1% per period.
     *
     * <p>Arithmetic the engine has no routine for, which is the point: the ladder is
     * the contract, so at the contractual rate it must price back to the principal
     * advanced. Nothing in {@link ScheduleBuilder} computes this, so it cannot agree
     * with a wrong instalment by construction.
     */
    private static BigDecimal presentValueAtOnePercent(InstalmentLadder ladder) {
        BigDecimal present = BigDecimal.ZERO;
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            BigDecimal factor = Precision.discountFactor(
                ONE_PERCENT, BigDecimal.valueOf(rung.periodIndex()));
            present = present.add(
                rung.total().amount().multiply(factor, Precision.WORKING), Precision.WORKING);
        }
        return present;
    }

    /**
     * The residue the billed instalments leave, as the final rung surfaces it:
     * {@code principal + interest − total} at working precision.
     *
     * <p>Not a derived convenience but the specified reporting point. The final rung's
     * principal closes the balance on the intended terminal rather than being derived
     * from the billed instalment, so the rounding residue has nowhere else to appear —
     * and appearing here at its true size is what stops it landing in the closing
     * balance as either an unpaid remainder or, where the instalment rounded up, a
     * negative balance {@link InstalmentLadder.Rung} rightly refuses.
     */
    private static Money terminalResidue(InstalmentLadder ladder) {
        InstalmentLadder.Rung last = ladder.rungs().get(ladder.length() - 1);
        return last.principal().plus(last.interest()).minus(last.total());
    }

    /** Sum of the principal column over a period range, inclusive. */
    private static Money principalOver(InstalmentLadder ladder, int fromPeriod, int toPeriod) {
        Money sum = Money.zero(ladder.currency());
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            if (rung.periodIndex() >= fromPeriod && rung.periodIndex() <= toPeriod) {
                sum = sum.plus(rung.principal());
            }
        }
        return sum;
    }

    /** Sum of the interest column over a period range, inclusive. */
    private static Money interestOver(InstalmentLadder ladder, int fromPeriod, int toPeriod) {
        Money sum = Money.zero(ladder.currency());
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            if (rung.periodIndex() >= fromPeriod && rung.periodIndex() <= toPeriod) {
                sum = sum.plus(rung.interest());
            }
        }
        return sum;
    }

    // ================================================ 1. the principal profile

    @Nested
    @DisplayName("each principal profile produces its own principal pattern")
    class PrincipalProfiles {

        @Test
        @DisplayName("a level annuity bills 47,073.47 and closes the last rung on the balance")
        void levelAnnuityBillsTheCase1Instalment() {
            InstalmentLadder ladder = ScheduleBuilder.build(case1());

            // A = P i / (1 - (1+i)^-n) = 1,000,000 x 0.01 / (1 - 1.01^-24) = 47,073.472223,
            // billed at 47,073.47 (Annuity, reference case 1). Every rung bills the same
            // amount: the instalment is the primitive here and the principal falls out of it.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("47073.47"));

            // Period 1: interest is the accrual on the whole 1,000,000 and principal is the
            // remainder of the bill. 1,000,000 x 0.01 = 10,000.00, so 47,073.47 - 10,000.00
            // = 37,073.47 of principal. Not the reverse: rounding the principal first and
            // treating interest as the remainder would put the paise in the wrong column.
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(1).principal().amount()).isEqualByComparingTo(bd("37073.47"));
            assertThat(ladder.rung(1).balanceAfter().amount())
                .isEqualByComparingTo(bd("962926.53"));

            // The balance is rolled at working precision and never re-rounded on the way:
            // period 2's principal is 47,073.47 - 9,629.2653 = 37,444.2047 and the closing
            // balance 925,482.3253. A balance re-rounded to paise every period reaches
            // 529,815.59 at month 12 against the golden 529,815.61 (ContractualBalance).
            assertThat(ladder.rung(2).interest().amount())
                .isEqualByComparingTo(bd("9629.2653"));
            assertThat(ladder.rung(2).balanceAfter().amount())
                .isEqualByComparingTo(bd("925482.3253"));

            // The last rung's principal closes the ladder on its terminal rather than being
            // derived from the billed instalment: 46,607.4554 against 466.0746 of interest,
            // both at working precision, and a closing balance of exactly zero.
            assertThat(ladder.rung(24).principal().atPresentationScale().amount())
                .isEqualByComparingTo(bd("46607.46"));
            assertThat(ladder.rung(24).interest().atPresentationScale().amount())
                .isEqualByComparingTo(bd("466.07"));
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);

            assertThat(presentValueAtOnePercent(ladder))
                .as("the ladder is the contract, so at the contractual rate it prices to par")
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("equal principal bills the principal to the paise and interest takes the rest (S1)")
        void equalPrincipalBillsPrincipalAndInterestIsTheRemainder() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.EqualPrincipal(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            // S1: 1,000,000 / 24 = 41,666.6667 billed as 41,666.67, plus 10,000.00 of
            // interest on the opening balance, so the first instalment is 51,666.67 and the
            // ladder declines from there as the balance falls. The principal is the
            // primitive here, and it is billed to the paise: 41,666.6667 would tie to
            // nothing on a repayment schedule.
            assertThat(ladder.rung(1).principal().amount()).isEqualByComparingTo(bd("41666.67"));
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("51666.67"));
            assertThat(ladder.rung(2).total().amount()).isEqualByComparingTo(bd("51250.00"));

            // Twenty-three bills of 41,666.67 repay 958,333.41, leaving 41,666.59 for the
            // last rung — not another 41,666.67. The 0.08 those bills over-repay is absorbed
            // by the rung that closes the balance, so the final instalment is 41,666.59 +
            // 416.67 = 42,083.26 and not the 42,083.34 an unrounded principal would bill.
            assertThat(ladder.rung(23).balanceAfter().amount())
                .isEqualByComparingTo(bd("41666.59"));
            assertThat(ladder.rung(24).principal().amount()).isEqualByComparingTo(bd("41666.59"));
            assertThat(ladder.rung(24).interest().amount()).isEqualByComparingTo(bd("416.67"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("42083.26"));

            // S1's total interest, and the reason the split is arranged this way. Here the
            // paise the bill rounds belong to the interest column, so the columns sum on
            // every rung and the interest total is 125,000.00 — which ties 1,125,000.00 of
            // cash against 1,000,000 of principal. Recording the raw accrual instead sums to
            // 124,999.9908, publishes as 124,999.99, and ties to nothing.
            assertThat(ladder.totalInterest().atPresentationScale().amount())
                .isEqualByComparingTo(bd("125000.00"));
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1125000.00"));
            for (InstalmentLadder.Rung rung : ladder.rungs()) {
                assertThat(rung.principal().plus(rung.interest()).amount())
                    .as("columns sum to the bill on rung " + rung.periodIndex())
                    .isEqualByComparingTo(rung.total().amount());
            }
        }

        @Test
        @DisplayName("a balloon amortises toward the lump and retains it, rather than to zero (S2)")
        void balloonAmortisesTowardItsTerminalAmount() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.Balloon(Money.inr("400000")),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            // S2: the instalments amortise the principal less the present value of the lump.
            // 400,000 x 1.01^-24 = 315,026.45, so the annuity runs over 684,973.55 and bills
            // 32,244.08. Sizing the instalments on the full 1,000,000 and adding the balloon
            // on top is the classic error and over-collects by the balloon's present value —
            // which is what the price check below refuses.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("32244.08"));
            assertThat(ladder.rung(23).total().amount()).isEqualByComparingTo(bd("32244.08"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("432244.08"));

            // ST-5: the ladder amortises to exactly 400,000, not to zero. A balloon schedule
            // that reaches zero has silently amortised the lump the borrower still owes; the
            // terminal is retained here and FlowVectorAssembler takes it back out as its own
            // BALLOON flow.
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(bd("400000"));
            assertThat(ladder.rung(23).balanceAfter().atPresentationScale().amount())
                .isEqualByComparingTo(bd("427964.52"));
            assertThat(ladder.rung(24).principal().atPresentationScale().amount())
                .isEqualByComparingTo(bd("27964.52"));

            assertThat(presentValueAtOnePercent(ladder))
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("a step ladder steps the billed instalment, not the unrounded base (S3)")
        void stepLadderStepsTheBilledInstalment() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.StepLadder(
                        bd("1.10"), 6, PrincipalProfile.StepDirection.UP),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            // S3: base 40,861.10, +10% every six periods. The base is solved by summation
            // over f^k(t) D_t rather than from a closed form, because the closed form exists
            // only for a step at every period and this product steps every sixth one.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("40861.10"));
            assertThat(ladder.rung(6).total().amount()).isEqualByComparingTo(bd("40861.10"));
            assertThat(ladder.rung(7).total().amount()).isEqualByComparingTo(bd("44947.21"));
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("49441.93"));
            assertThat(ladder.rung(19).total().amount()).isEqualByComparingTo(bd("54386.12"));

            // The two paise that decide whether the ladder steps the bill or the base. The
            // contract says "+10% every six months" of the 40,861.10 on the repayment
            // schedule, so 40,861.10 x 1.21 = 49,441.931 -> 49,441.93 and x 1.331 =
            // 54,386.1241 -> 54,386.12. Stepping the unrounded 40,861.104010 instead gives
            // 49,441.94 and 54,386.13, and nobody was ever billed the unrounded base for the
            // step to be a percentage of. The fixture governs (09 preamble).
            //
            // Rung 24 is stated positively rather than as "not 54,386.13". The negative form
            // stood here and passed on every wrong value but one — including a ladder that
            // stopped stepping after period 19, or that mis-sized the final band — and the
            // companion "rung 13 is not 49,441.94" could not fail at all, rung 13 having
            // been pinned to 49,441.93 nine lines above.
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("54386.12"));

            // 6 x (40,861.10 + 44,947.21 + 49,441.93 + 54,386.12) = 6 x 189,636.36.
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1137818.16"));
            assertThat(presentValueAtOnePercent(ladder))
                .as("a ladder sized on its first rung over-collects by whatever the ladder adds")
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("a bullet bills interest each period and the whole principal at maturity")
        void bulletBillsInterestThenTheWholePrincipal() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.BulletAtMaturity(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            // Principal is the primitive and it is zero until maturity, so every rung before
            // the last bills the accrual alone: 1,000,000 x 0.01 = 10,000.00, twenty-four
            // times over, and the balance never moves.
            assertThat(ladder.rung(1).principal().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(23).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));
            // 1,000,000 of principal plus its last 10,000.00 of interest.
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("1010000.00"));
            assertThat(ladder.totalInterest().amount()).isEqualByComparingTo(bd("240000.00"));
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1240000.00"));
        }

        @Test
        @DisplayName("NONE_UNTIL_MATURITY accretes every period and bills the lot once")
        void noneUntilMaturityAccretesIntoOneRedemption() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.NoneUntilMaturity(),
                    new InterestServicing.CapitalisedEachPeriod(), Moratorium.none()));

            // The zero-coupon profile. Interest joins the balance rather than being billed,
            // so the principal movement is the negative of the accrual: -10,000.00 in period
            // one, and a balance of 1,010,000 after it.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(1).principal().amount()).isEqualByComparingTo(bd("-10000.00"));
            assertThat(ladder.rung(1).balanceAfter().amount()).isEqualByComparingTo(bd("1010000"));

            // The final period is the one period on which the interest is charged rather
            // than capitalised: 1,000,000 x 1.01^24 = 1,269,734.6485, billed 1,269,734.65.
            // Without that switch the redemption rung would bill nothing and the balance
            // would have to be plugged to zero.
            assertThat(ladder.rung(23).balanceAfter().atPresentationScale().amount())
                .isEqualByComparingTo(bd("1257163.02"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("1269734.65"));
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1269734.65"));
            assertThat(ladder.totalInterest().atPresentationScale().amount())
                .isEqualByComparingTo(bd("269734.65"));
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a sculpted ladder is billed as supplied, and its shortfall is retained")
        void sculptedLadderIsTakenAsSuppliedAndItsShortfallRetained() {
            // Project finance sized to projected free cash flow: 250,000 of principal every
            // sixth period, nothing in between. No formula reproduces this, which is why it
            // is supplied.
            List<PrincipalProfile.PrincipalStep> full = List.of(
                new PrincipalProfile.PrincipalStep(6, Money.inr("250000")),
                new PrincipalProfile.PrincipalStep(12, Money.inr("250000")),
                new PrincipalProfile.PrincipalStep(18, Money.inr("250000")),
                new PrincipalProfile.PrincipalStep(24, Money.inr("250000")));
            InstalmentLadder clears = ScheduleBuilder.build(
                compose(new PrincipalProfile.Sculpted(full),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            // Periods 1-5 bill the accrual on 1,000,000 alone; period 6 bills 250,000 of
            // principal on top of it and drops the balance to 750,000, so period 7's
            // interest is 7,500.00 and not 10,000.00.
            assertThat(clears.rung(5).total().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(clears.rung(5).principal().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(clears.rung(6).total().amount()).isEqualByComparingTo(bd("260000.00"));
            assertThat(clears.rung(6).balanceAfter().amount()).isEqualByComparingTo(bd("750000"));
            assertThat(clears.rung(7).interest().amount()).isEqualByComparingTo(bd("7500.00"));
            assertThat(clears.rung(18).total().amount()).isEqualByComparingTo(bd("255000.00"));
            assertThat(clears.rung(24).total().amount()).isEqualByComparingTo(bd("252500.00"));
            // The interest column is six periods on each of the four declining balances:
            // 6 x 10,000 + 6 x 7,500 + 6 x 5,000 + 6 x 2,500 = 150,000.00, against
            // 1,000,000 of principal, for 1,150,000 of cash.
            assertThat(clears.totalInterest().amount()).isEqualByComparingTo(bd("150000.00"));
            assertThat(clears.totalCash().amount()).isEqualByComparingTo(bd("1150000.00"));
            assertThat(clears.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);

            // The same ladder with its last step removed repays only 750,000. What it leaves
            // behind is retained rather than plugged: a ladder sized to projected free cash
            // flow that does not clear the facility has told the engine something, and
            // silently amortising it away would discard it.
            InstalmentLadder retains = ScheduleBuilder.build(
                compose(new PrincipalProfile.Sculpted(full.subList(0, 3)),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none()));

            assertThat(retains.terminalBalance().amount()).isEqualByComparingTo(bd("250000"));
            assertThat(principalOver(retains, 1, 24).amount())
                .as("the principal column repays what the ladder supplied, not the facility")
                .isEqualByComparingTo(bd("750000"));
            assertThat(retains.rung(24).principal().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ================================================ 2. the interest servicing profile

    @Nested
    @DisplayName("each interest-servicing profile decides what happens to unpaid interest")
    class InterestServicingProfiles {

        /** The twelve-period holiday doc 09 quotes S5 and S6 on. */
        private static ScheduleBlueprint capitalisingHoliday(
            Moratorium.MoratoriumTermEffect termEffect) {

            return compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                    termEffect));
        }

        /** The same holiday, deferring simple to a settlement rather than compounding. */
        private static ScheduleBlueprint deferringHoliday(
            Moratorium.MoratoriumTermEffect termEffect, LocalDate settlementDate) {

            return compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DeferredSimple(settlementDate),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    termEffect));
        }

        @Test
        @DisplayName("capitalised interest is negative principal and compounds into the balance (S5)")
        void capitalisedInterestJoinsTheCarryingAmount() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                capitalisingHoliday(Moratorium.MoratoriumTermEffect.EXTEND_TERM));

            // Twelve periods on which nothing is paid, so nothing is billed and the accrual
            // joins the balance instead: rung 1 bills zero, records 10,000.00 of interest and
            // -10,000.00 of principal movement.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(1).principal().amount()).isEqualByComparingTo(bd("-10000.00"));

            // S5: the balance grows to 1,000,000 x 1.01^12 = 1,126,825.0301, of which
            // 126,825.03 is capitalised interest — and the compounding is real, so period 2
            // accrues on 1,010,000 and not on 1,000,000.
            assertThat(ladder.rung(2).interest().amount()).isEqualByComparingTo(bd("10100.00"));
            assertThat(ladder.rung(12).balanceAfter().atPresentationScale().amount())
                .isEqualByComparingTo(bd("1126825.03"));
            assertThat(interestOver(ladder, 1, 12).atPresentationScale().amount())
                .isEqualByComparingTo(bd("126825.03"));

            // Then 24 EMIs of 53,043.57 on the grown balance. Servicing says what happens to
            // interest that is not billed; the moratorium says which periods those are — so
            // the capitalisation stops when the holiday does. Letting the servicing profile
            // suppress billing for the whole life would turn every education loan into a
            // zero-coupon bond, and the instalment would not exist.
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("53043.57"));
            assertThat(ladder.rung(36).total().amount()).isEqualByComparingTo(bd("53043.57"));
            assertThat(ladder.rung(13).interest().atPresentationScale().amount())
                .isEqualByComparingTo(bd("11268.25"));

            // ST-3 carries real content on a capitalising instrument, so its two components
            // are asserted rather than the invariant flag: -126,825.03 of holiday principal
            // against +1,126,825.03 over the 24 instalments, summing to the 1,000,000
            // advanced. Either figure alone can be wrong; both being wrong in step requires
            // the capitalisation and the annuity to agree on the same error.
            assertThat(principalOver(ladder, 1, 12).atPresentationScale().amount())
                .isEqualByComparingTo(bd("-126825.03"));
            assertThat(principalOver(ladder, 13, 36).atPresentationScale().amount())
                .isEqualByComparingTo(bd("1126825.03"));
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);

            assertThat(presentValueAtOnePercent(ladder))
                .as("capitalisation at the contractual rate is value-neutral: still par")
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("deferred-simple interest accrues without compounding and settles as one lump (S6)")
        void deferredSimpleAccruesWithoutCompounding() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                deferringHoliday(Moratorium.MoratoriumTermEffect.EXTEND_TERM, PERIOD_12_DUE));

            // S6: the accrual does not join the balance, so every holiday period accrues
            // 1,000,000 x 0.01 = 10,000.00 on the same 1,000,000 — twelve times, 120,000.00,
            // and not the 126,825.03 the compounding variant reaches.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ladder.rung(2).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(12).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));
            assertThat(interestOver(ladder, 1, 12).amount())
                .isEqualByComparingTo(bd("120000.00"));

            // ACPIR 9(6)(i): interest deferred through a holiday becomes due once the holiday
            // ends, so the lump settles on the last holiday rung. Rungs 1-11 bill nothing at
            // all; rung 12 bills the whole 120,000.00 while still recording only its own
            // 10,000.00 of interest.
            assertThat(ladder.rung(11).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(12).total().amount()).isEqualByComparingTo(bd("120000.00"));
            assertThat(ladder.rung(12).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(12).principal().amount()).isEqualByComparingTo(BigDecimal.ZERO);

            // The balance never grew, so the amortising phase runs over the original
            // 1,000,000 and bills the plain 24-period annuity: 47,073.47.
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(ladder.rung(36).total().amount()).isEqualByComparingTo(bd("47073.47"));
        }

        @Test
        @DisplayName("a stated settlement date later than the end of the holiday is honoured")
        void aLaterSettlementDateIsHonouredAsStated() {
            // The end of the holiday is the default, not a ceiling. A contract that says the
            // deferred lump falls due six periods into the amortising phase has said so, and
            // snapping it back to period 12 would move a due date the contract fixed.
            InstalmentLadder ladder = ScheduleBuilder.build(
                deferringHoliday(Moratorium.MoratoriumTermEffect.EXTEND_TERM, PERIOD_18_DUE));

            assertThat(ladder.rung(12).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            // 47,073.47 of instalment plus the 120,000.00 lump riding on the same bill.
            assertThat(ladder.rung(18).total().amount()).isEqualByComparingTo(bd("167073.47"));
            assertThat(ladder.rung(19).total().amount()).isEqualByComparingTo(bd("47073.47"));
        }

        @Test
        @DisplayName("capitalising and deferring the same holiday are economically different (ST-4)")
        void capitalisingIsWorthMoreThanDeferringSimple() {
            InstalmentLadder capitalised = ScheduleBuilder.build(
                capitalisingHoliday(Moratorium.MoratoriumTermEffect.EXTEND_TERM));
            InstalmentLadder deferred = ScheduleBuilder.build(
                deferringHoliday(Moratorium.MoratoriumTermEffect.EXTEND_TERM, PERIOD_12_DUE));

            // ST-4, on the same loan with the same holiday and the same term effect. The one
            // dimension that differs is what happens to interest nobody paid, and doc 09 § 6
            // prices the difference at 126,825.03 against 120,000.00 — 6,825.03 — which then
            // shows up in the instalment as 53,043.57 against 47,073.47.
            assertThat(interestOver(capitalised, 1, 12)
                .minus(interestOver(deferred, 1, 12)).atPresentationScale().amount())
                .isEqualByComparingTo(bd("6825.03"));
            assertThat(capitalised.rung(13).total().amount()).isEqualByComparingTo(bd("53043.57"));
            assertThat(deferred.rung(13).total().amount()).isEqualByComparingTo(bd("47073.47"));

            // And the difference is economic rather than presentational, which is the claim
            // that matters. Both ladders are the same instrument's contractual cash;
            // discounted at the contractual 1% the capitalising one prices to the 1,000,000
            // advanced while the deferring one prices to 1,120,000 x 1.01^-12 = 993,943.09.
            // A lender who accepted simple deferral gave away 6,056.91 of present value —
            // which is the 34.6 bp of EIR doc 09 § 6 reports once the fee is added, and the
            // reason DeferredSimple cannot share a code path with CapitalisedEachPeriod.
            assertThat(presentValueAtOnePercent(capitalised))
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
            assertThat(presentValueAtOnePercent(deferred))
                .isCloseTo(bd("993943.09"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("SERVICED_THEN_COMBINED bills an interest-only prefix, then amortises")
        void servicedThenCombinedBillsAnInterestOnlyPrefix() {
            ScheduleBlueprint blueprint = compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedThenCombined(6), Moratorium.none());
            InstalmentLadder ladder = ScheduleBuilder.build(blueprint);

            // Six contractual interest-only periods inside the 24-period stated term leave
            // eighteen to amortise, so the annuity runs over 18 and not 24: A = 1,000,000 x
            // 0.01 / (1 - 1.01^-18) = 60,982.0479, billed 60,982.05.
            assertThat(ScheduleTerm.of(blueprint).firstAmortisingPeriod()).isEqualTo(7);
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(6).total().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(6).principal().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(6).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));
            assertThat(ladder.rung(7).total().amount()).isEqualByComparingTo(bd("60982.05"));

            // The prefix is held apart from a repayment holiday even though the two bill
            // identically — this ladder is rung-for-rung fixture S4's — because a contractual
            // interest-only phase is priced in at origination while a granted holiday is a
            // concession that may be a modification. ScheduleTerm is where that distinction
            // survives: nothing here suspended anything.
            assertThat(ScheduleTerm.of(blueprint).moratoriumPeriods()).isEqualTo(0);
            assertThat(ScheduleTerm.of(blueprint).interestOnlyPeriods()).isEqualTo(6);
        }

        @Test
        @DisplayName("DISCOUNTED_UPFRONT bills no interest at all: the discount was the interest")
        void discountedUpfrontBillsOnlyTheFace() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.NoneUntilMaturity(),
                    new InterestServicing.DiscountedUpfront(), Moratorium.none()));

            // A T-bill, commercial paper, a bill discounted. The interest was collected at
            // inception, so no period accrues anything and the ladder bills the face once.
            // Note the ordering this pins down: NONE_UNTIL_MATURITY on its own accretes and
            // would bill 1,269,734.65, so a discount instrument that fell through to that
            // branch would bill its interest twice over.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(23).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("1000000.00"));
            assertThat(ladder.totalInterest().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1000000.00"));
        }

        @Test
        @DisplayName("DEFERRED_SIMPLE on a bullet defers every period to one settlement (FITL)")
        void deferredSimpleOnABulletDefersEverything() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.BulletAtMaturity(),
                    new InterestServicing.DeferredSimple(STATED_MATURITY), Moratorium.none()));

            // The funded-interest-term-loan shape: nothing leaves for 23 periods, then
            // principal and the whole simple accrual settle together. 24 x 10,000.00 =
            // 240,000.00 of interest on an unmoving 1,000,000, plus the principal.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(23).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("1240000.00"));
            assertThat(ladder.totalInterest().amount()).isEqualByComparingTo(bd("240000.00"));

            // Same totals as the serviced bullet above — 240,000.00 of interest, 1,240,000.00
            // of cash — and a completely different instrument, because the timing is the
            // whole difference. Treating the deferral as servicing would bill 10,000.00
            // twenty-three times and leave the totals untouched, which is exactly why the
            // per-rung figures rather than the totals are the assertion.
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1240000.00"));

            // And the timing is worth money: one flow of 1,240,000 at period 24 discounts at
            // 1% to 1,240,000 x 1.01^-24 = 976,582.00, some 23,418 below the 1,000,000
            // advanced. Simple deferral over a two-year bullet is a long way from par, which
            // is the same statement as ST-4's on the holiday and the reason the FITL shape
            // needs its own servicing variant rather than a flag on ServicedEachPeriod.
            assertThat(presentValueAtOnePercent(ladder))
                .isCloseTo(bd("976582.00"), Offset.offset(bd("1")));
        }
    }

    // ================================================ 3. the moratorium term effect

    @Nested
    @DisplayName("the moratorium term effect decides whether the holiday lengthens the instrument")
    class MoratoriumTermEffects {

        private static ScheduleBlueprint capitalising(
            Moratorium.MoratoriumTermEffect termEffect) {

            return compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED,
                    termEffect));
        }

        @Test
        @DisplayName("EXTEND_TERM grants the full amortisation term after the holiday (S5)")
        void extendTermRunsTheLadderPastStatedMaturity() {
            ScheduleBlueprint blueprint =
                capitalising(Moratorium.MoratoriumTermEffect.EXTEND_TERM);
            InstalmentLadder ladder = ScheduleBuilder.build(blueprint);

            // Extending a term means the borrower gets the whole original amortisation term
            // after the holiday, so a 24-period loan with a 12-period holiday bills 24
            // instalments starting in period 13 — 36 rungs, a year past the stated maturity
            // of 1 April 2028. Stated maturity says where the contract said it ends, not what
            // the restructuring did to it, which is why the term effect is a contractual
            // input rather than something reconstructed from dates.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ScheduleTerm.of(blueprint).extendsPastStatedMaturity()).isTrue();
            assertThat(ladder.rung(36).dueOn()).isEqualTo(LocalDate.of(2029, 4, 1));

            List<LocalDate> due = ScheduleBuilder.dueDates(blueprint);
            assertThat(due).hasSize(36);
            assertThat(due.get(0)).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(due.get(11)).isEqualTo(PERIOD_12_DUE);
            assertThat(due.get(35)).isEqualTo(LocalDate.of(2029, 4, 1));
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("53043.57"));
        }

        @Test
        @DisplayName("COMPRESS_REMAINING holds maturity, so the instalment rises instead")
        void compressRemainingRaisesTheInstalment() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                capitalising(Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING));

            // Maturity held at 1 April 2028, so the holiday eats repayment periods: twelve
            // instalments, not 24, over the same grown balance of 1,126,825.0301. A =
            // 1,126,825.0301 x 0.01 / (1 - 1.01^-12) = 100,117.0390, billed 100,117.04
            // against the 53,043.57 the same holiday bills under EXTEND_TERM. Same loan, same
            // concession, an instalment nearly twice the size — this is a contractual term
            // with a real effect on the rate, not an accounting convenience.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(24).dueOn()).isEqualTo(STATED_MATURITY);
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("100117.04"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("100117.04"));
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(presentValueAtOnePercent(ladder))
                .isCloseTo(bd("1000000"), Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("BALLOON_ARREARS holds maturity and sends the capitalised arrears to a lump")
        void balloonArrearsSendsTheArrearsToMaturity() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                capitalising(Moratorium.MoratoriumTermEffect.BALLOON_ARREARS));

            // Maturity held exactly as under COMPRESS_REMAINING — the two differ only in
            // where the holiday's unpaid interest goes. Here the 126,825.03 of arrears
            // becomes the terminal lump, so the twelve instalments amortise toward it rather
            // than through it: A over 1,126,825.0301 - 126,825.0301 x 1.01^-12 = 1,014,274.26
            // is 90,117.0390, billed 90,117.04.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("90117.04"));
            assertThat(ladder.terminalBalance().atPresentationScale().amount())
                .isEqualByComparingTo(bd("126825.03"));
            // The final rung bills its instalment and the lump together: 90,117.04 +
            // 126,825.03.
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("216942.07"));

            // The instalment is exactly 10,000.00 below the COMPRESS_REMAINING one, and that
            // is not a coincidence to be checked against the engine: the arrears are
            // 1,000,000 x (1.01^12 - 1), so their annuity over twelve periods is
            // arrears x 0.01 / (1.01^12 - 1) = 1,000,000 x 0.01 exactly. Deferring the
            // arrears to maturity therefore relieves precisely one period's interest on the
            // advance, which is a closed-form fact about the arithmetic.
            assertThat(ladder.rung(13).total().plus(Money.inr("10000")).amount())
                .isEqualByComparingTo(bd("100117.04"));

            // Because the arrears are held back, the instalments amortise exactly the
            // 1,000,000 advanced — which is what "the arrears go to a terminal lump" means,
            // and what distinguishes this from the compressed case where they are amortised.
            assertThat(principalOver(ladder, 13, 24).atPresentationScale().amount())
                .isEqualByComparingTo(bd("1000000.00"));
        }

        @Test
        @DisplayName("BALLOON_ARREARS moves a deferred lump to maturity rather than to the holiday's end")
        void balloonArrearsMovesTheDeferredSettlementToMaturity() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.DeferredSimple(PERIOD_12_DUE),
                    new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                        Moratorium.MoratoriumTermEffect.BALLOON_ARREARS)));

            // Sending arrears to a terminal lump is what this term effect is, so it overrides
            // the ACPIR 9(6)(i) default even though the settlement date says period 12. The
            // holiday bills nothing anywhere, including on rung 12.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(12).total().amount()).isEqualByComparingTo(BigDecimal.ZERO);

            // Maturity is held, so twelve instalments amortise the untouched 1,000,000: A =
            // 1,000,000 x 0.01 / (1 - 1.01^-12) = 88,848.7887, billed 88,848.79. The
            // 120,000.00 of simple accrual rides on the final bill instead of the terminal
            // balance, because deferring simple never grew the balance and there is no
            // capitalised arrear for the terminal to hold.
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("88848.79"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("208848.79"));
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a principal-only holiday leaves the balance alone, so nothing is in arrears (S4)")
        void principalOnlyHolidayServicesItsInterest() {
            ScheduleBlueprint compressed = compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING));
            InstalmentLadder ladder = ScheduleBuilder.build(compressed);

            // S4: six periods of serviced interest at 10,000.00 with the principal untouched,
            // then eighteen EMIs of 60,982.05 — the instalment rising from 47,073.47 because
            // eighteen periods now carry what 24 would have.
            assertThat(ladder.length()).isEqualTo(24);
            assertThat(ladder.rung(6).total().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(6).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));
            assertThat(ladder.rung(7).total().amount()).isEqualByComparingTo(bd("60982.05"));
            assertThat(ladder.totalCash().amount())
                .as("6 x 10,000.00 + 18 x 60,982.05")
                .isEqualByComparingTo(bd("1157676.90"));

            // BALLOON_ARREARS on the same holiday produces the identical ladder, and must:
            // the interest was serviced as it fell due, so the balance never grew and there
            // are no arrears to balloon. A term effect that manufactured a terminal lump here
            // would be inventing principal.
            InstalmentLadder ballooned = ScheduleBuilder.build(
                compose(new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                        Moratorium.MoratoriumTermEffect.BALLOON_ARREARS)));
            assertThat(ballooned.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ballooned.rung(7).total().amount()).isEqualByComparingTo(bd("60982.05"));
        }
    }

    // ================================================ 4. the residue policy

    @Nested
    @DisplayName("the residue policy governs the billed instalment, never the balance")
    class Residue {

        private static InstalmentLadder underPolicy(ResiduePolicy policy) {
            return ScheduleBuilder.build(compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(), policy));
        }

        @Test
        @DisplayName("LMS_AUTHORITATIVE leaves the residue visible at exactly 0.059969")
        void lmsAuthoritativeLeavesTheResidueVisible() {
            InstalmentLadder ladder = underPolicy(ResiduePolicy.LMS_AUTHORITATIVE);

            // The residue is a fact about the loan, not a defect: 47,073.472223 is billed as
            // 47,073.47, and the 0.002223 monthly shortfall compounds over 24 periods to
            //   1,000,000 x 1.01^24 - 47,073.47 x ((1.01^24 - 1) / 0.01)
            //   = 1,269,734.648532 - 1,269,734.588563 = 0.059969152489.
            // Under this policy nothing is adjusted, by design — the lending system's
            // schedule is authoritative and a derived schedule carrying this policy is a
            // misconfiguration whose safe failure is to leave the difference in plain sight.
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(Precision.round(terminalResidue(ladder).amount(), 6))
                .isEqualByComparingTo(bd("0.059969"));

            // And it is surfaced on the final rung rather than in the closing balance. That
            // is the whole reason the last rung's principal closes the balance instead of
            // being derived from the bill: a derived last principal would leave 0.059969
            // outstanding after the borrower's final payment, or — had the instalment rounded
            // up — a negative balance the Rung constructor rightly refuses as principal
            // nobody owed.
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("FINAL_PERIOD_PLUG bills 47,073.53 last and reduces the residue to sub-paise")
        void finalPeriodPlugLandsOnTheLastInstalment() {
            InstalmentLadder ladder = underPolicy(ResiduePolicy.FINAL_PERIOD_PLUG);

            // 47,073.47 + 0.059969 = 47,073.529969, billed 47,073.53. The plug is rounded
            // once, at presentation scale, because an instalment is only billable to the
            // paise; what is left is 0.000031 and unpresentable rather than untreated.
            assertThat(ladder.rung(23).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("47073.53"));

            // The residue that is left, signed and to eight places rather than bounded by a
            // paise. Derivation, independent of the plug: the policy moves the BILL and
            // never the balance path, so periods 1-23 bill the same 47,073.47 as under
            // LMS_AUTHORITATIVE and the final rung's principal + interest is the same
            // 47,073.47 + 0.059969152489 that test derives from
            // 1,000,000 x 1.01^24 − 47,073.47 x ((1.01^24 − 1) / 0.01). The plug bills the
            // paise-rounded 47,073.53, so what is left is exactly the rounding-up:
            //   0.059969152489 − 0.06 = −0.000030847511.
            // The sign is the content. An `.abs() < 0.01` bound stood here, 324x the figure
            // it bounded, and it admitted a plug that left 0.009 outstanding — 292x the
            // correct residue — in EITHER direction, and over-collection is what the
            // sibling anOverCollectingResidueReducesTheFinalInstalment exists to refuse.
            assertThat(Precision.round(terminalResidue(ladder).amount(), 8))
                .isEqualByComparingTo(bd("-0.00003085"));

            // The plug moved the bill, not the balance: the closing balance is still exactly
            // zero and the principal column is untouched, which is what keeps the residue a
            // billing decision rather than a balance adjustment.
            assertThat(ladder.terminalBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(24).principal().atPresentationScale().amount())
                .isEqualByComparingTo(bd("46607.46"));
        }

        @Test
        @DisplayName("FIRST_PERIOD_PLUG discounts the residue back to the first instalment")
        void firstPeriodPlugDiscountsTheResidueBack() {
            InstalmentLadder ladder = underPolicy(ResiduePolicy.FIRST_PERIOD_PLUG);

            // A residue is a dated amount, so moving it 23 periods earlier without
            // discounting it would change the schedule's economics. 0.059969 x 1.01^-23 =
            // 0.047702, so the first instalment becomes 47,073.517702 -> 47,073.52 and every
            // later one is untouched. Adding the undiscounted 0.06 to period 1 instead would
            // over-collect by a third of a paise by maturity.
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("47073.52"));
            assertThat(ladder.rung(2).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("47073.47"));

            // And what the extra 0.05 billed in period 1 leaves behind, signed and exact.
            // The residue any billing pattern leaves is
            //   1,000,000 x 1.01^24 − sum(billed_t x 1.01^(24−t)),
            // so relative to LMS_AUTHORITATIVE's 0.059969152489 this schedule collects
            // 0.05 extra 23 periods before maturity:
            //   0.059969152489 − 0.05 x 1.01^23
            //     = 0.059969152489 − 0.05 x 1.257163018348 = −0.002888998428.
            // Negative, and pinned rather than bounded. An `.abs() < 0.01` bound stood
            // here — 3.5x the figure it bounded, and blind to the sign, so it admitted a
            // plug that over-collected by three times as much as this one under-collects.
            // Anything that changes which periods carry the residue, or how much of it they
            // carry, moves this figure; the exact form says so and the bound did not.
            assertThat(Precision.round(terminalResidue(ladder).amount(), 8))
                .isEqualByComparingTo(bd("-0.00288900"));
        }

        @Test
        @DisplayName("SPREAD_LAST_N shares the residue over the named tail, and needs to be told the width")
        void spreadLastNSharesTheResidueOverTheNamedTail() {
            ScheduleBlueprint blueprint = compose(new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                ResiduePolicy.SPREAD_LAST_N);

            // The width belongs to the lending system's billing convention, not to the
            // instrument, so there is no default: silently spreading over one period would
            // run FINAL_PERIOD_PLUG under a different policy name, and a substituted policy
            // is what a recorded policy exists to prevent.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(blueprint))
                .withMessageContaining("SPREAD_LAST_N needs the number of trailing instalments")
                .withMessageContaining("build(blueprint, spreadPeriods)");

            // Each increment is equal in nominal terms and the increments are compounded to
            // the final period to size them: 0.059969 / (1.01^2 + 1.01 + 1) = 0.019791, so
            // periods 22, 23 and 24 each bill 47,073.489791 -> 47,073.49 and period 21 is
            // untouched.
            InstalmentLadder ladder = ScheduleBuilder.build(blueprint, 3);
            assertThat(ladder.rung(21).total().amount()).isEqualByComparingTo(bd("47073.47"));
            assertThat(ladder.rung(22).total().amount()).isEqualByComparingTo(bd("47073.49"));
            assertThat(ladder.rung(23).total().amount()).isEqualByComparingTo(bd("47073.49"));
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("47073.49"));

            // The residue the three-period spread leaves, signed and exact. Same
            // derivation as the other two policies — 1,000,000 x 1.01^24 less each billed
            // instalment compounded to period 24 — and here three periods each bill 0.02
            // more than the 47,073.47 base:
            //   0.059969152489 − 0.02 x (1.01^2 + 1.01 + 1)
            //     = 0.059969152489 − 0.02 x 3.0301 = −0.000632847511.
            // Which is what distinguishes a spread from a plug: FINAL_PERIOD_PLUG leaves
            // −0.00003085 on the same loan, twenty times smaller, so a SPREAD_LAST_N that
            // quietly ran as a one-period plug would move this figure by a factor of
            // twenty. The `.abs() < 0.01` bound that stood here admitted both.
            assertThat(Precision.round(terminalResidue(ladder).amount(), 8))
                .isEqualByComparingTo(bd("-0.00063285"));

            // A width past the number of instalments is a misconfiguration, not something to
            // clamp: it would spread over instalments that do not exist.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(blueprint, 25))
                .withMessageContaining("spreadPeriods must be between 1 and 24");
        }

        @Test
        @DisplayName("a fixed-principal profile has no billed instalment to plug, so the last rung absorbs it")
        void aFixedPrincipalProfileIsClosedRatherThanPlugged() {
            // The residue only exists where the instalment is the primitive. Under equal
            // principal the principal is the primitive, so offering the policy an instalment
            // to adjust would adjust a bill that was already exact — and here there is no
            // billed instalment to offer at all. The 0.08 that 24 bills of 41,666.67
            // over-repay is absorbed by the rung that closes the balance, under every policy.
            InstalmentLadder plugged = ScheduleBuilder.build(
                compose(new PrincipalProfile.EqualPrincipal(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                    ResiduePolicy.FINAL_PERIOD_PLUG));
            InstalmentLadder untouched = ScheduleBuilder.build(
                compose(new PrincipalProfile.EqualPrincipal(),
                    new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                    ResiduePolicy.LMS_AUTHORITATIVE));

            assertThat(plugged.rung(24).total().amount()).isEqualByComparingTo(bd("42083.26"));
            assertThat(plugged.rung(24).principal().amount()).isEqualByComparingTo(bd("41666.59"));
            assertThat(untouched.rung(24).total().amount()).isEqualByComparingTo(bd("42083.26"));
            assertThat(plugged.totalCash().amount()).isEqualByComparingTo(bd("1125000.00"));
            assertThat(untouched.totalCash().amount()).isEqualByComparingTo(bd("1125000.00"));
        }

        @Test
        @DisplayName("an interest-free ladder plugs a negative residue by reducing the final bill")
        void anOverCollectingResidueReducesTheFinalInstalment() {
            // Interest-free instalment credit is a real product and a zero rate is not a
            // degenerate case: the annuity is simply the principal spread evenly, 1,000,000 /
            // 24 = 41,666.6667 billed at 41,666.67. Rounding up means 24 bills collect
            // 1,000,000.08, so the residue is negative and the plug has to *reduce* the final
            // instalment to 41,666.59. A sign error here bills 41,666.75 and over-collects.
            ScheduleBlueprint zeroRate = compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(BigDecimal.ZERO)),
                ResiduePolicy.FINAL_PERIOD_PLUG);
            InstalmentLadder ladder = ScheduleBuilder.build(zeroRate);

            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("41666.67"));
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.rung(24).total().amount()).isEqualByComparingTo(bd("41666.59"));
            assertThat(ladder.totalInterest().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1000000.00"));
            assertThat(terminalResidue(ladder).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ================================================ 5. disbursement

    @Nested
    @DisplayName("draws are not rungs, and interest accrues on what has actually been advanced")
    class Drawdowns {

        private static final LocalDate DRAW_2 = LocalDate.of(2026, 10, 1);
        private static final LocalDate DRAW_3 = PERIOD_12_DUE;

        /** 400,000 at closure, 350,000 at period 6, 250,000 at period 12. */
        private static List<Tranche> projectedDraws() {
            return List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(DRAW_2, 6, Money.inr("350000")),
                Tranche.of(DRAW_3, 12, Money.inr("250000")));
        }

        private static DisbursementProfile.Tranched tranched(List<Tranche> actual) {
            return new DisbursementProfile.Tranched(projectedDraws(), actual, bd("0.05"));
        }

        @Test
        @DisplayName("a tranched facility accrues on the drawn balance, and the draw lands after the accrual")
        void interestAccruesOnWhatHasBeenAdvanced() {
            // A construction period: the drawdowns complete inside a twelve-period principal
            // holiday, with the interest during construction serviced as it falls due.
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(tranched(List.of()), new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    new Moratorium(12, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                        Moratorium.MoratoriumTermEffect.EXTEND_TERM),
                    onePercentFixed(), ResiduePolicy.LMS_AUTHORITATIVE));

            // 400,000 x 0.01 = 4,000.00 for the first six periods — the facility bills
            // interest on the money that left the bank, not on the sanctioned 1,000,000.
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("4000.00"));
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("4000.00"));

            // Period 6 is the one that pins the within-period ordering: the 350,000 arrives in
            // period 6, and period 6's interest is still 4,000.00 on the 400,000 outstanding
            // before it. Applying the draw first would bill 7,500.00 — a month of interest on
            // money the borrower had not yet had.
            assertThat(ladder.rung(6).interest().amount()).isEqualByComparingTo(bd("4000.00"));
            assertThat(ladder.rung(6).balanceAfter().amount()).isEqualByComparingTo(bd("750000"));
            assertThat(ladder.rung(7).interest().amount()).isEqualByComparingTo(bd("7500.00"));
            assertThat(ladder.rung(12).interest().amount()).isEqualByComparingTo(bd("7500.00"));
            assertThat(ladder.rung(12).balanceAfter().amount())
                .isEqualByComparingTo(bd("1000000"));

            // Once the facility is fully drawn the amortising phase is the plain 24-period
            // annuity over 1,000,000: 47,073.47. Anything else means a draw was lost,
            // double-counted, or dated into the wrong period.
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("47073.47"));
        }

        @Test
        @DisplayName("a drawdown is not a rung: the ladder carries no negative instalment for it")
        void drawsStayOutOfTheLadder() {
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(tranched(List.of()), new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    new Moratorium(12, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                        Moratorium.MoratoriumTermEffect.EXTEND_TERM),
                    onePercentFixed(), ResiduePolicy.LMS_AUTHORITATIVE));

            // A rung is something the borrower owes; a draw is money going the other way.
            // Signing a draw as a negative rung would make totalCash() and ST-3 both
            // meaningless, so the ladder's cash is receipts only and the draws reach the flow
            // vector through drawSchedule().
            for (InstalmentLadder.Rung rung : ladder.rungs()) {
                assertThat(rung.total().isNegative())
                    .as("rung " + rung.periodIndex() + " bills a non-negative amount")
                    .isFalse();
            }
            // 6 x 4,000.00 + 6 x 7,500.00 + 24 x 47,073.47 = 24,000 + 45,000 + 1,129,763.28.
            assertThat(ladder.totalCash().amount()).isEqualByComparingTo(bd("1198763.28"));

            List<Tranche> schedule = ScheduleBuilder.drawSchedule(
                tranched(List.of(projectedDraws().get(0))), VALUE_DATE);
            assertThat(schedule).hasSize(3);
            assertThat(schedule.get(1).amount().amount()).isEqualByComparingTo(bd("350000"));

            // The projected schedule is what the ladder was struck on, and it stays the
            // schedule after the first draw lands: policy is to strike the rate at financial
            // closure and re-estimate only when cumulative actual deviates past tolerance.
            assertThat(schedule).isEqualTo(projectedDraws());
        }

        @Test
        @DisplayName("a single advance and a revolver both draw at inception")
        void singleAndRevolverDrawSchedules() {
            List<Tranche> single = ScheduleBuilder.drawSchedule(singleDraw(), VALUE_DATE);
            assertThat(single).hasSize(1);
            assertThat(single.get(0).periodIndex()).isEqualTo(0);
            assertThat(single.get(0).amount().amount()).isEqualByComparingTo(bd("1000000"));

            // A revolver has no drawdown schedule at all, so its expected drawn balance is
            // advanced at the value date: 1,000,000 x 0.60 = 600,000. Striking the ladder on
            // the sanctioned limit instead would spread the fee over money that never left
            // the bank.
            DisbursementProfile revolver =
                new DisbursementProfile.UtilisationDriven(NOTIONAL, bd("0.60"));
            List<Tranche> drawn = ScheduleBuilder.drawSchedule(revolver, VALUE_DATE);
            assertThat(drawn).hasSize(1);
            assertThat(drawn.get(0).drawnOn()).isEqualTo(VALUE_DATE);
            assertThat(drawn.get(0).amount().amount()).isEqualByComparingTo(bd("600000"));

            assertThatNullPointerException()
                .isThrownBy(() -> ScheduleBuilder.drawSchedule(null, VALUE_DATE));
        }

        @Test
        @DisplayName("drift is cumulative, so a late tranche cancels against an early one")
        void driftIsCumulativeNeverPerDraw() {
            // Drawn 400,000 by 1 October against 750,000 projected: the second tranche is a
            // month late, so cumulative drift is -350,000 and the 50,000 tolerance
            // (1,000,000 x 0.05) is breached.
            DisbursementProfile.Tranched late = tranched(List.of(
                projectedDraws().get(0),
                Tranche.of(LocalDate.of(2026, 11, 1), 7, Money.inr("350000"))));
            assertThat(ScheduleBuilder.drawnBy(late, DRAW_2).amount())
                .isEqualByComparingTo(bd("400000"));
            assertThat(ScheduleBuilder.projectedBy(late, DRAW_2).amount())
                .isEqualByComparingTo(bd("750000"));
            assertThat(ScheduleBuilder.cumulativeDrift(late, DRAW_2).amount())
                .isEqualByComparingTo(bd("-350000"));
            assertThat(ScheduleBuilder.disbursementTimingTriggered(late, DRAW_2)).isTrue();

            // A month later the same facility is exactly on schedule. Project-finance draws
            // run over 24-48 months and individually never match; what carries information is
            // the running total, and a tranche a month late followed by one on time has not
            // changed the facility's economics. A per-draw comparison would have reported a
            // deviation and churned the book for nothing.
            LocalDate november = LocalDate.of(2026, 11, 1);
            assertThat(ScheduleBuilder.cumulativeDrift(late, november).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ScheduleBuilder.disbursementTimingTriggered(late, november)).isFalse();

            // Drawing ahead of schedule is the same test with the sign reversed.
            DisbursementProfile.Tranched early = tranched(List.of(
                projectedDraws().get(0),
                Tranche.of(LocalDate.of(2026, 9, 1), 5, Money.inr("350000"))));
            assertThat(ScheduleBuilder.cumulativeDrift(early, LocalDate.of(2026, 9, 1)).amount())
                .isEqualByComparingTo(bd("350000"));
            assertThat(ScheduleBuilder.disbursementTimingTriggered(
                early, LocalDate.of(2026, 9, 1))).isTrue();

            // Exactly at tolerance is within it: 300,000 drawn against 350,000 projected is a
            // 50,000 deviation and the test is strictly greater. A boundary that fired here
            // would re-estimate on the tolerance rather than past it.
            DisbursementProfile.Tranched atTolerance = tranched(List.of(
                projectedDraws().get(0),
                Tranche.of(DRAW_2, 6, Money.inr("300000"))));
            assertThat(ScheduleBuilder.cumulativeDrift(atTolerance, DRAW_2).amount())
                .isEqualByComparingTo(bd("-50000"));
            assertThat(ScheduleBuilder.disbursementTimingTriggered(atTolerance, DRAW_2)).isFalse();
        }

        @Test
        @DisplayName("only a tranched facility can raise DISBURSEMENT_TIMING at all")
        void onlyATranchedFacilityHasAScheduleToDeviateFrom() {
            // A single advance has no schedule to deviate from and a revolver has no drawdown
            // schedule at all, so reporting a timing deviation on either would be reporting
            // on a comparison that was never made.
            DisbursementProfile revolver =
                new DisbursementProfile.UtilisationDriven(NOTIONAL, bd("0.60"));
            assertThat(ScheduleBuilder.disbursementTimingTriggered(singleDraw(), DRAW_3)).isFalse();
            assertThat(ScheduleBuilder.disbursementTimingTriggered(revolver, DRAW_3)).isFalse();
            assertThat(ScheduleBuilder.cumulativeDrift(singleDraw(), DRAW_3).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);

            // A single advance is drawn on its date and not before, which is what makes the
            // drift on it zero rather than undefined.
            assertThat(ScheduleBuilder.drawnBy(singleDraw(), VALUE_DATE.minusDays(1)).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ScheduleBuilder.drawnBy(singleDraw(), VALUE_DATE).amount())
                .isEqualByComparingTo(bd("1000000"));

            // A revolver's drawn balance carries no date sensitivity: the limit is drawn and
            // repaid at the borrower's discretion, which is why ACPIR 54 contemplates an
            // approximation here rather than a projection.
            assertThat(ScheduleBuilder.drawnBy(revolver, VALUE_DATE.minusYears(5)).amount())
                .isEqualByComparingTo(bd("600000"));
        }
    }

    // ================================================ 6. the rate ladder

    @Nested
    @DisplayName("the period rate is taken as the rate profile gives it, per period")
    class RateLadders {

        @Test
        @DisplayName("a step coupon is solved by summation, not from the first period's rate")
        void aStepCouponSolvesTheInstalmentBySummation() {
            // A step-up bond amortising on an annuity: 1.00% for periods 1-12 and 1.25%
            // thereafter. No closed form exists for a per-period rate ladder, so the
            // instalment is the amortising balance over the sum of the cumulative discount
            // factors: sum of D_j for j = 1..24, where D_j is 1.01^-j to period 12 and
            // 1.01^-12 x 1.0125^-(j-12) after it, comes to 21.0874042942, and
            // 1,000,000 / 21.0874042942 = 47,421.6734, billed 47,421.67.
            ScheduleBlueprint stepped = compose(singleDraw(), new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                new RateProfile.StepCoupon(List.of(
                    new RateProfile.CouponStep(1, Rate.monthly(ONE_PERCENT)),
                    new RateProfile.CouponStep(13, Rate.monthly(bd("0.0125"))))),
                ResiduePolicy.LMS_AUTHORITATIVE);
            InstalmentLadder ladder = ScheduleBuilder.build(stepped);

            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("47421.67"));

            // Applying period 1's rate uniformly would bill the 47,073.47 of the flat 1% loan
            // and under-collect the whole coupon step — a schedule that looks right and bills
            // the wrong interest for half the life. That figure is excluded by the equality
            // above, which is why the `isNotEqualByComparingTo(47,073.47)` line that stood
            // here was removed: it restated the same accessor on the same ladder and no
            // input could satisfy one and break the other. The independent statement about
            // the step is the period-13 interest below, which a uniformly applied period-1
            // rate moves and which the instalment assertion does not constrain.

            // Each period's interest is that period's own rate on that period's balance. The
            // balance after twelve bills of 47,421.67 at 1% is 525,399.5575, and period 13
            // charges 1.25% of it: 6,567.4945. At the pre-step 1% it would have been
            // 5,254.00, so this is where the coupon step reaches the borrower.
            assertThat(ladder.rung(1).interest().amount()).isEqualByComparingTo(bd("10000.00"));
            assertThat(ladder.rung(12).balanceAfter().atPresentationScale().amount())
                .isEqualByComparingTo(bd("525399.56"));
            assertThat(ladder.rung(13).interest().atPresentationScale().amount())
                .isEqualByComparingTo(bd("6567.49"));
        }
    }

    // ================================================ 7. what the builder refuses

    @Nested
    @DisplayName("combinations that are coherent but not derivable in one pass are refused by name")
    class Refusals {

        @Test
        @DisplayName("a null blueprint is refused before anything is derived")
        void nullBlueprintIsRefused() {
            assertThatNullPointerException()
                .isThrownBy(() -> ScheduleBuilder.build(null))
                .withMessageContaining("blueprint");
        }

        @Test
        @DisplayName("PARTIAL_SERVICING carries no reduction fraction to bill the concession from")
        void partialServicingHasNothingToBillFrom() {
            // The dimension is coherent — a reduced instalment during a holiday is a real
            // concession — but Moratorium carries no fraction to bill it from, and a builder
            // default would make the amount billed a builder decision rather than a
            // contractual term.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.ServicedEachPeriod(),
                        new Moratorium(6, Moratorium.MoratoriumKind.PARTIAL_SERVICING,
                            Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING))))
                .withMessageContaining("PARTIAL_SERVICING")
                .withMessageContaining("carries no reduction fraction")
                .withMessageContaining("PRINCIPAL_ONLY");
        }

        @Test
        @DisplayName("a deferring holiday without a settlement date is an accrual with nowhere to go")
        void aDeferringHolidayNeedsASettlementDate() {
            // ServicedEachPeriod does not compound, so ScheduleBlueprint's coherence pass
            // lets this through: the contradiction is not that the holiday compounds, it is
            // that nothing says when the lump falls due.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.ServicedEachPeriod(),
                        new Moratorium(12,
                            Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                            Moratorium.MoratoriumTermEffect.EXTEND_TERM))))
                .withMessageContaining("needs InterestServicing.DeferredSimple")
                .withMessageContaining("accrual with nowhere to go");
        }

        @Test
        @DisplayName("servicing that never bills needs a profile that never amortises")
        void capitalisingOrDeferringEveryPeriodNeedsANonAmortisingProfile() {
            // Capitalising every period with no holiday bills nothing ever, while a level
            // annuity bills interest as part of each instalment. The two cannot both be true,
            // and the message says which two dimensions collided and what to state instead.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.CapitalisedEachPeriod(), Moratorium.none())))
                .withMessageContaining("CAPITALISED_EACH_PERIOD with no moratorium")
                .withMessageContaining("NONE_UNTIL_MATURITY");

            // Deferring every period to one settlement is something only a non-amortising
            // instrument can do; a level annuity amortises before then.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.DeferredSimple(STATED_MATURITY),
                        Moratorium.none())))
                .withMessageContaining("DEFERRED_SIMPLE with no moratorium")
                .withMessageContaining("BULLET_AT_MATURITY");
        }

        @Test
        @DisplayName("NONE_UNTIL_MATURITY refuses both an interest-only phase and simple deferral")
        void noneUntilMaturityHasNoPhaseToDescribeAndAccretes() {
            // A profile that services nothing before maturity has no interest-only phase for
            // SERVICED_THEN_COMBINED to describe. These are two different instruments, not
            // one with an option.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.NoneUntilMaturity(),
                        new InterestServicing.ServicedThenCombined(6), Moratorium.none())))
                .withMessageContaining("no interest-only phase to describe");

            // And it accretes its interest into the redemption, which compounds; simple
            // deferral does not. The two refusals chain, which is why both are asserted here.
            // Without a holiday the broader guard fires first and names the remedy — pair the
            // deferral with a moratorium, or with a bullet for the FITL shape:
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.NoneUntilMaturity(),
                        new InterestServicing.DeferredSimple(STATED_MATURITY),
                        Moratorium.none())))
                .withMessageContaining("DEFERRED_SIMPLE with no moratorium");

            // Take that remedy on this profile and the narrower guard refuses it with the
            // figure: on a twelve-period holiday accreting and deferring simple differ by
            // 6,825.03 and 34.6 bp, so the engine will not pick one on the reader's behalf.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.NoneUntilMaturity(),
                        new InterestServicing.DeferredSimple(PERIOD_12_DUE),
                        new Moratorium(12,
                            Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                            Moratorium.MoratoriumTermEffect.EXTEND_TERM))))
                .withMessageContaining("6,825.03")
                .withMessageContaining("34.6 bp");
        }

        @Test
        @DisplayName("a tranche inside the amortising phase re-sizes the instalment mid-schedule (ST-6)")
        void aTrancheInsideTheAmortisingPhaseIsRefused() {
            // A draw after amortisation starts re-sizes the instalment mid-schedule, which is
            // a DISBURSEMENT_TIMING re-estimation against a revised blueprint rather than a
            // ladder derivable in one pass. The message names the remedy: lengthen the
            // holiday to cover the drawdown period, which is what a construction period is.
            DisbursementProfile lateDraw = new DisbursementProfile.Tranched(
                List.of(Tranche.of(VALUE_DATE, 0, Money.inr("600000")),
                    Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("400000"))),
                List.of(), bd("0.05"));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(lateDraw, new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                        onePercentFixed(), ResiduePolicy.LMS_AUTHORITATIVE)))
                .withMessageContaining("lands inside the amortising phase")
                .withMessageContaining("DISBURSEMENT_TIMING");

            // ST-6, the other half: every tranche is dated on or after the value date.
            DisbursementProfile backdated = new DisbursementProfile.Tranched(
                List.of(Tranche.of(VALUE_DATE.minusDays(1), 0, Money.inr("600000")),
                    Tranche.of(LocalDate.of(2026, 10, 1), 0, Money.inr("400000"))),
                List.of(), bd("0.05"));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(backdated, new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                        onePercentFixed(), ResiduePolicy.LMS_AUTHORITATIVE)))
                .withMessageContaining("invariant ST-6")
                .withMessageContaining("before the value date");
        }

        @Test
        @DisplayName("a disbursement profile that does not advance the notional is refused (ST-6)")
        void theDrawScheduleMustSumToTheNotional() {
            // Sum of tranche draws = notional. A facility whose schedule advances 900,000
            // against a 1,000,000 notional would amortise a balance nobody lent.
            DisbursementProfile short900 = new DisbursementProfile.Tranched(
                List.of(Tranche.of(VALUE_DATE, 0, Money.inr("900000"))), List.of(), bd("0.05"));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(short900, new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none(),
                        onePercentFixed(), ResiduePolicy.LMS_AUTHORITATIVE)))
                .withMessageContaining("invariant ST-6")
                .withMessageContaining("INR 900000");
        }

        @Test
        @DisplayName("a sculpted step outside the amortising window, or over-amortising, is refused")
        void sculptedLaddersAreCheckedAgainstTheWindowAndTheBalance() {
            // A step inside a repayment holiday repays principal the holiday suspended.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.Sculpted(List.of(
                            new PrincipalProfile.PrincipalStep(3, Money.inr("500000")),
                            new PrincipalProfile.PrincipalStep(12, Money.inr("500000")))),
                        new InterestServicing.ServicedEachPeriod(),
                        new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                            Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING))))
                .withMessageContaining("outside the amortising window [7, 24]");

            // A step past maturity is never billed, so it is a data error rather than a
            // schedule.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.Sculpted(List.of(
                            new PrincipalProfile.PrincipalStep(12, Money.inr("500000")),
                            new PrincipalProfile.PrincipalStep(30, Money.inr("500000")))),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none())))
                .withMessageContaining("outside the amortising window [1, 24]");

            // And a ladder repaying more than the balance it starts from over-amortises:
            // 1,100,000 against 1,000,000 is 100,000 of principal the borrower never owed,
            // which the Rung constructor would otherwise refuse as a negative balance three
            // steps later with nothing left to say which contract produced it.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.Sculpted(List.of(
                            new PrincipalProfile.PrincipalStep(12, Money.inr("600000")),
                            new PrincipalProfile.PrincipalStep(24, Money.inr("500000")))),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none())))
                .withMessageContaining("over-amortises by")
                .withMessageContaining("principal the borrower never owed");
        }

        @Test
        @DisplayName("a balloon at least the outstanding balance is a bullet, not a balloon")
        void aBalloonMustLeaveRoomForAPositiveInstalment() {
            // No positive instalment exists once the terminal amount reaches the balance at
            // the start of amortisation, and the message says what the instrument actually
            // is rather than reporting an arithmetic failure from inside the annuity.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.Balloon(NOTIONAL),
                        new InterestServicing.ServicedEachPeriod(), Moratorium.none())))
                .withMessageContaining("is at least the")
                .withMessageContaining("bullet or a discount instrument");
        }

        @Test
        @DisplayName("interest cannot be both serviced through the holiday and deferred to a lump")
        void servicingThatContradictsTheMoratoriumKindIsRefused() {
            // This was @Disabled: the contradiction used to be accepted and then resolved
            // silently in favour of the moratorium kind. holidaySteps reads the kind alone
            // — PRINCIPAL_ONLY maps to Accrual.CHARGE — and amortisingAccrual returns DEFER
            // only where the moratorium is ABSENT, so with a PRINCIPAL_ONLY holiday nothing
            // deferred anywhere: deferredAccrual() stayed zero, settlementPeriod() was never
            // consulted, and the settlement date the contract states was discarded without a
            // word. The ladder came out rung-for-rung the one ServicedEachPeriod produces,
            // so the blueprint declared that interest accrues to a lump while the schedule
            // billed it every period.
            //
            // Now refused as an ST-11 conflict, beside the two rules that already cross-check
            // the kind against servicing.compounds(). It needed a new predicate:
            // DeferredSimple neither compounds nor pays as it accrues, so compounds() could
            // not express "interest is actually being serviced" — see
            // InterestServicing.leavesAsCashEachPeriod.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.DeferredSimple(PERIOD_12_DUE),
                        new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                            Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING))))
                .withMessageContaining("PRINCIPAL_ONLY")
                .withMessageContaining("DEFERRED_SIMPLE");
        }

        @Test
        @DisplayName("the mirror case is the same defect: capitalising through a PRINCIPAL_ONLY holiday")
        void capitalisingThatContradictsTheMoratoriumKindIsRefused() {
            // Named in the original defect report but never asserted, so it would have been
            // left live by a fix that only handled DeferredSimple. CapitalisedEachPeriod with
            // a PRINCIPAL_ONLY holiday capitalised nothing, and the requireSupported guard
            // that would have caught it only fires when the moratorium is absent altogether.
            // Education loans and IDC are exactly this shape, and capitalising is worth
            // 34.6 bp more than deferring simple on the same loan (doc 09 S5 against S6).
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleBuilder.build(
                    compose(new PrincipalProfile.LevelAnnuity(),
                        new InterestServicing.CapitalisedEachPeriod(),
                        new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                            Moratorium.MoratoriumTermEffect.EXTEND_TERM))))
                .withMessageContaining("PRINCIPAL_ONLY")
                .withMessageContaining("CAPITALISED_EACH_PERIOD");
        }

        @Test
        @DisplayName("and the coherent pairing still builds: PRINCIPAL_ONLY with interest serviced")
        void aPrincipalOnlyHolidayWithServicedInterestIsCoherent() {
            // The falsifiability half. A guard that refused every PRINCIPAL_ONLY holiday
            // would satisfy both tests above and destroy fixture S4, whose whole point is
            // that interest of 10,000.00 is serviced for six periods while principal is
            // untouched.
            InstalmentLadder ladder = ScheduleBuilder.build(
                compose(new PrincipalProfile.LevelAnnuity(),
                    new InterestServicing.ServicedEachPeriod(),
                    new Moratorium(6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                        Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING)));

            assertThat(ladder.rung(6).principal().amount()).isEqualByComparingTo("0");
            assertThat(ladder.rung(6).interest().atPresentationScale().amount())
                .isEqualByComparingTo("10000.00");
        }
    }
}
