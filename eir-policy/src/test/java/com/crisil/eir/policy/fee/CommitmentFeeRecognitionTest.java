package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The half of FR-204 that fires when nothing happens: what becomes of a commitment fee at
 * drawdown, and what becomes of it when the commitment simply lapses.
 *
 * <p><b>The fixture is chosen so every expected figure is arithmetic anyone can check without
 * running the engine.</b> A commitment fee of 365,000.00 runs from 1 January 2027 to 1 January
 * 2028. 2027 is not a leap year — 2027 = 4 x 506 + 3 — so the commitment period is 365 days and
 * the time-proportion accrual is exactly 1,000.00 a day. Every recognised figure below is a
 * day count multiplied by 1,000, and the day counts are summed from month lengths in the
 * comments.
 */
class CommitmentFeeRecognitionTest {

    private static final LocalDate START = LocalDate.of(2027, 1, 1);
    private static final LocalDate EXPIRY = LocalDate.of(2028, 1, 1);
    private static final LocalDate MID_YEAR = LocalDate.of(2027, 7, 1);
    private static final Money FEE = Money.inr("365000.00");

    /** 1 Jan to 1 Jul 2027: 31 + 28 + 31 + 30 + 31 + 30 = 181 days, at 1,000.00 a day. */
    private static final Money RECOGNISED_TO_MID_YEAR = Money.inr("181000.00");

    /** 1 Jul 2027 to 1 Jan 2028: 31 + 31 + 30 + 31 + 30 + 31 = 184 days, at 1,000.00 a day. */
    private static final Money DEFERRED_AT_MID_YEAR = Money.inr("184000.00");

    private static CommitmentFeeDeferral deferral(FeeClassification classification) {
        return new CommitmentFeeDeferral("COMMITMENT_FEE", "WCDL", FEE, START, EXPIRY,
            classification, "CFT-2027.1");
    }

    @Nested
    @DisplayName("the time-proportion limb — drawdown assessed as not probable")
    class OverCommitmentPeriod {

        private final CommitmentFeeDeferral deferral =
            deferral(FeeClassification.OVER_COMMITMENT_PERIOD);

        @Test
        @DisplayName("the fee accrues to income by actual days across the commitment period")
        void accruesByActualDays() {
            // IFRS 9 B5.4.3(b): the fee is revenue for standing ready to lend, earned by the
            // passage of the period. Actual days rather than whole months, because a sanction
            // letter does not respect month boundaries and rounding to one moves income between
            // reporting periods.
            assertThat(deferral.commitmentDays())
                .as("2027 is not a leap year, so 1 Jan 2027 to 1 Jan 2028 is 365 days")
                .isEqualTo(365L);
            assertThat(deferral.recognisedThrough(MID_YEAR))
                .as("181 of 365 days at 1,000.00 a day")
                .isEqualTo(RECOGNISED_TO_MID_YEAR);
            assertThat(deferral.deferredAt(MID_YEAR))
                .as("the complement — 184 days still to run")
                .isEqualTo(DEFERRED_AT_MID_YEAR);
        }

        @Test
        @DisplayName("nothing accrues before the commitment starts and nothing beyond the fee")
        void clampedAtBothEnds() {
            // The upper clamp is the one that matters: a recognition run dated after the period
            // closed, or an expiry notified late, must not recognise 103% of a fee.
            assertThat(deferral.recognisedThrough(START.minusDays(1)))
                .isEqualTo(Money.zero(Money.INR));
            assertThat(deferral.recognisedThrough(START))
                .as("day zero of the period has earned nothing")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(deferral.recognisedThrough(EXPIRY.plusMonths(3)))
                .as("capped at the fee itself")
                .isEqualTo(FEE);
        }

        @Test
        @DisplayName("expiry on the stated date recognises nothing further, and does not re-take it")
        void expiryAfterFullAccrualIsNotADoubleCount() {
            // The trap in FR-204's wording. "Recognise on expiry if undrawn" reads like an
            // instruction to book the fee at expiry, and on this limb the period has already
            // booked it: an expiry hook that recognises the fee again would double-count a whole
            // year of commitment-fee income, and the two entries would sit in the same account
            // with nothing left to reconcile them against.
            CommitmentFeeRecognition recognition = deferral.expiresUndrawn(EXPIRY);

            assertThat(recognition.toProfitOrLoss())
                .as("the residual at the stated expiry of a fully accrued fee is nil")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.alreadyRecognised()).isEqualTo(FEE);
            assertThat(recognition.totalAccountedFor())
                .as("365,000.00 recognised once, not twice")
                .isEqualTo(FEE);
            assertThat(recognition.trigger())
                .isEqualTo(CommitmentFeeRecognition.Trigger.EXPIRED_UNDRAWN);
        }

        @Test
        @DisplayName("a commitment cancelled early recognises the unearned residual there and then")
        void earlyLapseRecognisesTheResidual() {
            // A sanction withdrawn or reduced at mid-year. 181 days were earned by the passage of
            // the period; the remaining 184 days' worth was never going to be earned by standing
            // ready, and it becomes revenue on the lapse date rather than continuing to sit in
            // deferred income against a commitment that no longer exists.
            CommitmentFeeRecognition recognition = deferral.expiresUndrawn(MID_YEAR);

            assertThat(recognition.toProfitOrLoss()).isEqualTo(DEFERRED_AT_MID_YEAR);
            assertThat(recognition.alreadyRecognised()).isEqualTo(RECOGNISED_TO_MID_YEAR);
            assertThat(recognition.toCarryingAmount())
                .as("there is no asset: nothing was drawn")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.totalAccountedFor()).isEqualTo(FEE);
        }

        @Test
        @DisplayName("drawdown moves the unearned residual into the loan, not into income")
        void drawdownTransfersTheResidual() {
            // The assessment said drawdown was unlikely and the facility drew anyway. The 181
            // days already earned stay earned; the residual belongs to the loan's EIR from the
            // drawdown date — the EBA's commitment-fee position. Recognising it as fee income here
            // instead would front-load 184,000.00 into the drawdown period, which is precisely
            // what B5.4.2(b) spreads across the life of the loan.
            CommitmentFeeRecognition recognition = deferral.drawnDown(MID_YEAR);

            assertThat(recognition.toCarryingAmount()).isEqualTo(DEFERRED_AT_MID_YEAR);
            assertThat(recognition.toProfitOrLoss())
                .as("nothing is recognised in income by the drawdown event itself")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.alreadyRecognised()).isEqualTo(RECOGNISED_TO_MID_YEAR);
            assertThat(recognition.entersCarryingAmount()).isTrue();
            assertThat(recognition.totalAccountedFor()).isEqualTo(FEE);
        }

        @Test
        @DisplayName("the monthly accrual ladder sums to the fee exactly, with no leak")
        void monthlyLadderSumsToTheFee() {
            // Derived from the identity rather than from the engine: the increments between
            // consecutive month starts telescope to recognisedThrough(expiry) minus
            // recognisedThrough(start), which by the two clamps above is the fee minus nil. The
            // check is worth making because it is where a per-period rounding would show up — a
            // ladder rounded to paise each month recognises 364,999.98 or 365,000.02 of a
            // 365,000.00 fee, and the difference has nowhere to go.
            Money summed = Money.zero(Money.INR);
            for (int month = 0; month < 12; month++) {
                Money increment = deferral.recognisedThrough(START.plusMonths(month + 1))
                    .minus(deferral.recognisedThrough(START.plusMonths(month)));
                assertThat(increment.isNegative())
                    .as("recognition never reverses; month index " + month)
                    .isFalse();
                summed = summed.plus(increment);
            }

            assertThat(summed)
                .as("twelve monthly increments of a 365-day accrual, summed")
                .isEqualTo(FEE);
        }

        @Test
        @DisplayName("a non-terminating accrual ratio is not rounded until presentation")
        void ratioIsCarriedAtWorkingPrecision() {
            // 100,000.00 over a 90-day commitment period (31 + 28 + 31 for Jan, Feb, Mar 2027),
            // recognised to 1 February: 31 days. 100,000 x 31 = 3,100,000 exactly, and
            // 3,100,000 / 90 = 34,444.44... recurring. At WORKING precision (28 significant
            // digits, ADR-0002) that is 34,444 followed by 23 fours: five integer digits leaves 23
            // of the 28 for the fraction, and the 29th digit is a 4, so HALF_UP keeps them all.
            // Rounding here instead of at presentation would put a rounding error into the
            // deferred balance, which the terminal event then clears to a figure that does not tie.
            CommitmentFeeDeferral quarter = new CommitmentFeeDeferral("COMMITMENT_FEE", "WCDL",
                Money.inr("100000.00"), START, LocalDate.of(2027, 4, 1),
                FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1");

            Money recognised = quarter.recognisedThrough(LocalDate.of(2027, 2, 1));

            assertThat(quarter.commitmentDays()).isEqualTo(90L);
            assertThat(recognised.amount())
                .as("3,100,000 / 90 at 28 significant digits")
                .isEqualByComparingTo("34444." + "4".repeat(23));
            assertThat(recognised.atPresentationScale())
                .as("and only the published figure is reduced to paise")
                .isEqualTo(Money.inr("34444.44"));
            assertThat(recognised.plus(quarter.deferredAt(LocalDate.of(2027, 2, 1))))
                .as("recognised and deferred still exhaust the fee exactly")
                .isEqualTo(Money.inr("100000.00"));
        }
    }

    @Nested
    @DisplayName("the integral limb — drawdown assessed as probable")
    class Integral {

        private final CommitmentFeeDeferral deferral = deferral(FeeClassification.INTEGRAL);

        @Test
        @DisplayName("nothing is recognised across the commitment period")
        void nothingAccruesWhileWaiting() {
            // ACPIR 52 and B5.4.2(b) defer the fee in full into the EIR of the loan expected to
            // result, and there is no asset yet to amortise it against — the EBA's Q&A puts the
            // interim balance in other assets, not in income. A time-proportion accrual on this
            // limb would recognise income the loan's EIR is then also going to recognise.
            assertThat(deferral.recognisedThrough(MID_YEAR)).isEqualTo(Money.zero(Money.INR));
            assertThat(deferral.recognisedThrough(EXPIRY))
                .as("not even at the end of the commitment period")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(deferral.deferredAt(MID_YEAR)).isEqualTo(FEE);
        }

        @Test
        @DisplayName("expiry undrawn recognises the whole fee as revenue on the expiry date")
        void expiryUndrawnRecognisesEverything() {
            // The case FR-204 is easiest to forget, because it fires when nothing happens: a fee
            // deferred against a drawdown that was assessed as probable and never came. There is
            // no EIR to amortise it through, so B5.4.2(b) makes it revenue on expiry — 365,000.00
            // of income arriving in a period nothing scheduled.
            CommitmentFeeRecognition recognition = deferral.expiresUndrawn(EXPIRY);

            assertThat(recognition.toProfitOrLoss()).isEqualTo(FEE);
            assertThat(recognition.alreadyRecognised()).isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.toCarryingAmount()).isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.on()).isEqualTo(EXPIRY);
            assertThat(recognition.totalAccountedFor()).isEqualTo(FEE);
            assertThat(recognition.describe())
                .as("the audit sentence says why, not just how much")
                .contains("B5.4.2(b)");
        }

        @Test
        @DisplayName("drawdown puts the whole fee into the resulting loan's carrying amount")
        void drawdownCapitalisesEverything() {
            CommitmentFeeRecognition recognition = deferral.drawnDown(MID_YEAR);

            assertThat(recognition.toCarryingAmount()).isEqualTo(FEE);
            assertThat(recognition.toProfitOrLoss()).isEqualTo(Money.zero(Money.INR));
            assertThat(recognition.trigger()).isEqualTo(CommitmentFeeRecognition.Trigger.DRAWN);
            assertThat(recognition.totalAccountedFor()).isEqualTo(FEE);
        }
    }

    @Nested
    @DisplayName("what the deferral refuses to represent")
    class DeferralGuards {

        @Test
        @DisplayName("a commitment period with no length has no time-proportion denominator")
        void zeroLengthPeriodIsRefused() {
            // Without the guard this divides by zero inside a recognition run, and the failure
            // names an arithmetic operation instead of the contract whose dates are wrong.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeDeferral("CF", "WCDL", FEE, START, START,
                    FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1"))
                .withMessageContaining("no denominator");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeDeferral("CF", "WCDL", FEE, EXPIRY, START,
                    FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1"))
                .withMessageContaining("no denominator");
        }

        @Test
        @DisplayName("a negative posting is a cost paid, not a commitment fee received")
        void negativeAmountIsRefused() {
            // ACPIR 52's limb is a fee received to originate a loan; an outflow is ACPIR 53
            // territory and routes through cost_function (FR-203). Refused rather than abs()'d,
            // because silently flipping the sign would recognise a payment as income at expiry.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeDeferral("CF", "WCDL", Money.inr("-1000.00"),
                    START, EXPIRY, FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1"))
                .withMessageContaining("ACPIR 53");
        }

        @Test
        @DisplayName("only the two FR-204 classifications have a recognition pattern here")
        void otherClassificationsAreRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeDeferral("CF", "WCDL", FEE, START, EXPIRY,
                    FeeClassification.AS_INCURRED, "CFT-2027.1"))
                .withMessageContaining("FR-204");
        }

        @Test
        @DisplayName("a commitment cannot expire outside its own commitment period")
        void expiryOutsideThePeriodIsRefused() {
            CommitmentFeeDeferral deferral = deferral(FeeClassification.INTEGRAL);

            assertThatIllegalArgumentException()
                .as("a commitment cannot expire before the bank became party to it (ACPIR 23)")
                .isThrownBy(() -> deferral.expiresUndrawn(START.minusDays(1)))
                .withMessageContaining("outside its");
            // The date offered after the stated end is a notification date, not an expiry date.
            // On the integral limb nothing is recognised until the terminal event, so accepting a
            // sweep's own run date would move a whole 365,000.00 out of the period the commitment
            // actually ended in and into a later one — in the single case where no event exists to
            // contradict it, which is what makes it worth a guard rather than a convention.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> deferral.expiresUndrawn(EXPIRY.plusMonths(3)))
                .withMessageContaining("notification date, not an expiry date");
        }

        @Test
        @DisplayName("the parameterless expiry dates the recognition at the stated end")
        void statedExpiryNeedsNoDate() {
            // The sweep that finds a lapsed commitment supplies no date, because the date is a
            // property of the commitment. Asserted as an equality with the explicit form so the
            // two cannot drift apart.
            CommitmentFeeDeferral deferral = deferral(FeeClassification.INTEGRAL);

            assertThat(deferral.expiresUndrawn())
                .isEqualTo(deferral.expiresUndrawn(EXPIRY));
            assertThat(deferral.expiresUndrawn().on()).isEqualTo(EXPIRY);
        }

        @Test
        @DisplayName("a draw dated after expiry is a new commitment, not a late draw on this one")
        void drawdownOutsideThePeriodIsRefused() {
            // An extended facility gets a new commitment period with its own dates and its own
            // deferral. Accepting the late date here would transfer to a loan's carrying amount a
            // fee whose commitment had already lapsed — and on the integral limb it would do so
            // instead of recognising the expiry revenue that was actually due.
            CommitmentFeeDeferral deferral = deferral(FeeClassification.INTEGRAL);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> deferral.drawnDown(EXPIRY.plusDays(1)))
                .withMessageContaining("new commitment period");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> deferral.drawnDown(START.minusDays(1)))
                .withMessageContaining("outside its");
        }
    }

    @Nested
    @DisplayName("the recognition legs must exhaust the fee")
    class RecognitionGuards {

        @Test
        @DisplayName("three legs that do not sum to the fee are refused")
        void legsMustFootToTheFee() {
            // The identity this engine wants in its invariant register as CF-1: the
            // deferred-income account is cleared by the terminal event, so a residue has nothing
            // left to tie against and surfaces, if at all, as an unexplained movement in fee
            // income.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeRecognition("CF", "WCDL", EXPIRY,
                    Money.inr("100000.00"), Money.zero(Money.INR), Money.inr("200000.00"), FEE,
                    CommitmentFeeRecognition.Trigger.EXPIRED_UNDRAWN, "CFT-2027.1", "short"))
                .withMessageContaining("must exhaust the fee");
        }

        @Test
        @DisplayName("an expiry cannot capitalise into an asset that does not exist")
        void expiryCannotEnterACarryingAmount() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeRecognition("CF", "WCDL", EXPIRY,
                    Money.zero(Money.INR), FEE, Money.zero(Money.INR), FEE,
                    CommitmentFeeRecognition.Trigger.EXPIRED_UNDRAWN, "CFT-2027.1", "no asset"))
                .withMessageContaining("no asset to carry it into");
        }

        @Test
        @DisplayName("a drawdown cannot recognise fee income at the drawdown date")
        void drawdownCannotRecogniseIncome() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeRecognition("CF", "WCDL", MID_YEAR,
                    FEE, Money.zero(Money.INR), Money.zero(Money.INR), FEE,
                    CommitmentFeeRecognition.Trigger.DRAWN, "CFT-2027.1", "front-loaded"))
                .withMessageContaining("front-loads the yield");
        }

        @Test
        @DisplayName("a negative leg is over-recognition somewhere else")
        void negativeLegIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommitmentFeeRecognition("CF", "WCDL", EXPIRY,
                    Money.inr("400000.00"), Money.zero(Money.INR), Money.inr("-35000.00"), FEE,
                    CommitmentFeeRecognition.Trigger.EXPIRED_UNDRAWN, "CFT-2027.1", "negative"))
                .withMessageContaining("negative leg");
        }
    }

    @Nested
    @DisplayName("publishing the legs at paise scale")
    class Publication {

        /**
         * A fee of 100.05 over a two-day commitment period, drawn after one day. Half of 100.05 is
         * 50.025 on each side, and 50.025 rounds HALF_UP to 50.03 — so reducing the two legs
         * independently publishes 50.03 + 50.03 = 100.06 against a fee of 100.05. One paisa, posted
         * against a deferred-income account the drawdown has just cleared.
         */
        private CommitmentFeeDeferral halfPaiseSplit() {
            return new CommitmentFeeDeferral("COMMITMENT_FEE", "WCDL", Money.inr("100.05"),
                START, START.plusDays(2), FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1");
        }

        @Test
        @DisplayName("the published legs foot to the published fee, to the paisa")
        void publishedLegsFootToTheFee() {
            CommitmentFeeRecognition published =
                halfPaiseSplit().drawnDown(START.plusDays(1)).atPresentationScale();

            // 50.025 already earned presents as 50.03 (HALF_UP), and the leg this event creates
            // takes the remainder: 100.05 - 50.03 = 50.02. Independently: the published figures
            // must sum to 100.05, and the earned leg is the one prior periods already reported, so
            // the 50.02 falls to the transfer.
            assertThat(published.alreadyRecognised()).isEqualTo(Money.inr("50.03"));
            assertThat(published.toCarryingAmount())
                .as("the terminal leg absorbs the residue, as a final-period plug does")
                .isEqualTo(Money.inr("50.02"));
            assertThat(published.totalAccountedFor()).isEqualTo(Money.inr("100.05"));
            assertThat(published.fee()).isEqualTo(Money.inr("100.05"));
        }

        @Test
        @DisplayName("the audit sentence's own figures add up")
        void describeFootsToTheFee() {
            // Worth its own assertion because describe() is what a reviewer reads. Before the plug
            // rule this sentence said "50.03 to carrying amount, 50.03 already recognised, of
            // 100.05" — three published figures that contradict each other in one line.
            String described = halfPaiseSplit().drawnDown(START.plusDays(1)).describe();

            assertThat(described)
                .contains("INR 50.02 to carrying amount")
                .contains("INR 50.03 already recognised")
                .contains("INR 100.05");
        }

        @Test
        @DisplayName("reducing an already-reduced recognition changes nothing")
        void presentationIsIdempotent() {
            CommitmentFeeRecognition once =
                halfPaiseSplit().drawnDown(START.plusDays(1)).atPresentationScale();

            assertThat(once.atPresentationScale()).isEqualTo(once);
        }

        @Test
        @DisplayName("an expiry publishes its residue as revenue, not against a carrying amount")
        void expiryKeepsItsLegEmpty() {
            // The plug follows the trigger: on an expiry there is no asset, so the residue can only
            // fall to the leg this event creates in income. 100.05 accrued to nil by the stated end
            // leaves nothing to plug, which is the case that must publish as zero rather than as a
            // paisa of unexplained income.
            CommitmentFeeRecognition published =
                halfPaiseSplit().expiresUndrawn().atPresentationScale();

            assertThat(published.toCarryingAmount()).isEqualTo(Money.zero(Money.INR));
            assertThat(published.alreadyRecognised()).isEqualTo(Money.inr("100.05"));
            assertThat(published.toProfitOrLoss()).isEqualTo(Money.zero(Money.INR));
            assertThat(published.totalAccountedFor()).isEqualTo(Money.inr("100.05"));
        }
    }

    @Nested
    @DisplayName("the bridge from classification to recognition")
    class FromDecision {

        @Test
        @DisplayName("the deferral inherits the classification the decision reached")
        void deferralCarriesTheDecision() {
            // Built through the decision rather than independently, so that an INTEGRAL fee cannot
            // be handed to a deferral that spreads it over the commitment period — the
            // misrecognition FR-204 exists to prevent.
            CommitmentFeeDecision decision = CommitmentFeeDecision.classified("COMMITMENT_FEE",
                "WCDL", new BigDecimal("0.20"), new BigDecimal("0.50"),
                FeeClassification.OVER_COMMITMENT_PERIOD, "CFT-2027.1", "0.20 is below 0.50");

            CommitmentFeeDeferral deferral = decision.defer(FEE, START, EXPIRY);

            assertThat(deferral.classification())
                .isEqualTo(FeeClassification.OVER_COMMITMENT_PERIOD);
            assertThat(deferral.policyVersionId()).isEqualTo("CFT-2027.1");
            assertThat(deferral.recognisedThrough(MID_YEAR)).isEqualTo(RECOGNISED_TO_MID_YEAR);
        }
    }
}
