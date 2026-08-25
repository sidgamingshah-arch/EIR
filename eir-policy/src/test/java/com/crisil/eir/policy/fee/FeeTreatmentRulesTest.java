package com.crisil.eir.policy.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three inception-time fee treatment rules: FR-205 bifurcation, FR-206
 * contingent-fee timing, FR-209 government subvention. FR-208 is asserted in
 * {@link FeeTreatmentHedgingScreenTest}, because it is a per-period invariant
 * rather than an inception decision.
 *
 * <p>Every expected figure in the FR-205 block is worked by hand in a comment above
 * the assertion. That is not ceremony: the split is a division that does not
 * terminate for the ordinary consortium case, and a fixture taken from a run of the
 * code would agree with the code by construction — including when both are wrong in
 * the last twenty digits, which is exactly the residue that survives to a portfolio
 * total and shows up as a sub-ledger break nobody can attribute.
 */
class FeeTreatmentRulesTest {

    private static final LocalDate INCEPTION = LocalDate.of(2027, 4, 1);
    private static final LocalDate PREPAYMENT = LocalDate.of(2029, 9, 14);

    private static FeePosting received(String feeCode, String amount, FeeClassification treatment) {
        return FeePosting.received(feeCode, Money.inr(amount), INCEPTION, treatment);
    }

    @Nested
    @DisplayName("FR-205 — syndication fee bifurcation")
    class SyndicationFeeBifurcation {

        @Test
        @DisplayName("the excess goes to arrangement service income and the balance to the EIR")
        void excessToServiceIncomeBalanceToEir() {
            // Worked by hand. The arranger took 45% of the syndicate fee pool and kept 20%
            // of the facility.
            //   fee pool implied      = 4,500,000.00 / 0.45 = 10,000,000.00
            //   proportionate to 20%  = 10,000,000.00 x 0.20 = 2,000,000.00  -> the EIR limb
            //   excess                = 4,500,000.00 - 2,000,000.00 = 2,500,000.00 -> service
            // Equivalently, and this is the form the rule computes:
            //   (4,500,000.00 x 0.20) / 0.45 = 900,000.00 / 0.45 = 2,000,000.00
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("4500000.00"), new BigDecimal("0.45"), new BigDecimal("0.20"));

            assertThat(split.disproportionate())
                .as("a 45 per cent fee share against a 20 per cent retained share is disproportionate")
                .isTrue();
            assertThat(split.toEir())
                .as("the balance — what a participant holding the same 20 per cent slice earns")
                .isEqualTo(Money.inr("2000000.00"));
            assertThat(split.toArrangementServiceIncome())
                .as("the excess — payment for arranging, a distinct performance obligation")
                .isEqualTo(Money.inr("2500000.00"));
        }

        @Test
        @DisplayName("a non-terminating split still reconstitutes the whole fee exactly")
        void nonTerminatingSplitReconstitutesTheWhole() {
            // The ordinary consortium position, and the one that leaks. A 10% retention
            // against a 35% fee share makes the proportion 0.10/0.35 = 2/7, which does not
            // terminate in decimal.
            //   (1,000,000.00 x 0.10) / 0.35 = 100,000 / 0.35 = 285,714.285714...
            // At Precision.WORKING — 28 significant digits, HALF_UP — 285714 uses six of the
            // twenty-eight, leaving twenty-two decimals. The repeating block is 285714, so
            // the twenty-two decimals are 285714 285714 285714 2857 and the twenty-third
            // digit is 1, which rounds down and leaves the twenty-second alone:
            //   EIR limb = 285,714.2857142857142857142857
            // The excess is then the exact remainder, digit for digit:
            //   1,000,000 - 285,714.2857142857142857142857 = 714,285.7142857142857142857143
            // and the two add back to 1,000,000.0000000000000000000000 with nothing left over
            // (2857142857142857142857 + 7142857142857142857143 = 10^22).
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("1000000.00"), new BigDecimal("0.35"), new BigDecimal("0.10"));

            assertThat(split.toEir())
                .as("the EIR limb at working precision, rounded once at the division")
                .isEqualTo(Money.inr("285714.2857142857142857142857"));
            assertThat(split.toArrangementServiceIncome())
                .as("the excess as the exact remainder, not a second independently rounded limb")
                .isEqualTo(Money.inr("714285.7142857142857142857143"));
            // Exact addition, no MathContext — the guarantee, restated where a reader sees it.
            assertThat(split.toEir().amount().add(split.toArrangementServiceIncome().amount()))
                .as("no rounding leak: the limbs sum to the whole fee to the last digit")
                .isEqualByComparingTo(new BigDecimal("1000000.00"));
        }

        @Test
        @DisplayName("equal shares are proportionate — the boundary sits on strict inequality")
        void equalSharesAreProportionate() {
            // The pinned boundary. At fee share == retained share the arranger earns the same
            // effective yield on its slice as every other participant does on theirs, which is
            // IFRS 9 B5.4.3(c)'s own description of the case that is NOT bifurcated. So the
            // whole 2,000,000.00 is integral and the service limb is exactly zero — not a
            // residue of a few paise from computing 2,000,000 x 0.25 / 0.25.
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("2000000.00"), new BigDecimal("0.25"), new BigDecimal("0.25"));

            assertThat(split.disproportionate()).as("equality is not disproportion").isFalse();
            assertThat(split.toEir()).isEqualTo(Money.inr("2000000.00"));
            assertThat(split.toArrangementServiceIncome())
                .as("exactly zero at the boundary, with no rounding residue to argue about")
                .isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("an under-rewarded arranger has no service-income limb, and no negative one")
        void underRewardedArrangerKeepsTheWholeFeeInTheEir() {
            // 40% of the facility for 10% of the fee pool: under-rewarded, not over-rewarded.
            // Unguarded, the ratio 0.40/0.10 = 4 would put 4 x 3,000,000 = 12,000,000 into the
            // EIR and leave the service limb at -9,000,000 — an arranger reporting negative
            // arrangement service income on a fee it was underpaid. The rule caps instead.
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("3000000.00"), new BigDecimal("0.10"), new BigDecimal("0.40"));

            assertThat(split.disproportionate()).isFalse();
            assertThat(split.toEir()).isEqualTo(Money.inr("3000000.00"));
            assertThat(split.toArrangementServiceIncome()).isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("an arranger that retains nothing sends the whole fee to service income")
        void retainingNothingIsWhollyServiceIncome() {
            // IFRS 9 B5.4.3(c) first limb, and it needs no special case: retained share zero
            // makes the balance (3,000,000 x 0) / 0.30 = 0, so the whole fee is the excess.
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("3000000.00"), new BigDecimal("0.30"), BigDecimal.ZERO);

            assertThat(split.disproportionate()).isTrue();
            assertThat(split.toEir())
                .as("nothing retained, so no loan for any part of the fee to be integral to")
                .isEqualTo(Money.zero(Money.INR));
            assertThat(split.toArrangementServiceIncome()).isEqualTo(Money.inr("3000000.00"));
        }

        @Test
        @DisplayName("the two limbs become two postings, INTEGRAL and SEPARATE_SERVICE")
        void theLimbsBecomeTwoPostings() {
            FeePosting original =
                received("SYN-ARR-FEE", "4500000.00", FeeClassification.INTEGRAL);
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                original.amount(), new BigDecimal("0.45"), new BigDecimal("0.20"));

            List<FeePosting> limbs = split.postings(original);

            assertThat(limbs).as("one posting per non-zero limb").hasSize(2);
            assertThat(limbs.get(0).feeCode()).isEqualTo("SYN-ARR-FEE:EIR");
            assertThat(limbs.get(0).classification()).isEqualTo(FeeClassification.INTEGRAL);
            assertThat(limbs.get(0).amount()).isEqualTo(Money.inr("2000000.00"));
            assertThat(limbs.get(1).feeCode()).isEqualTo("SYN-ARR-FEE:ARRANGEMENT_SERVICE");
            assertThat(limbs.get(1).classification()).isEqualTo(FeeClassification.SEPARATE_SERVICE);
            assertThat(limbs.get(1).amount()).isEqualTo(Money.inr("2500000.00"));
            assertThat(limbs.get(0).entersInitialCarryingAmount())
                .as("only the balance enters the initial carrying amount")
                .isTrue();
            assertThat(limbs.get(1).entersInitialCarryingAmount()).isFalse();
        }

        @Test
        @DisplayName("a zero limb produces no posting")
        void aZeroLimbIsNotAPosting() {
            FeePosting original =
                received("SYN-ARR-FEE", "3000000.00", FeeClassification.INTEGRAL);
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                original.amount(), new BigDecimal("0.30"), BigDecimal.ZERO);

            // A zero-amount posting in the inception vector reads as a real fee of zero and
            // invites the conclusion that the split happened. The full trace stays on the
            // split record, which is where the two shares are.
            assertThat(split.postings(original)).hasSize(1);
            assertThat(split.postings(original).get(0).classification())
                .isEqualTo(FeeClassification.SEPARATE_SERVICE);
            assertThat(split.describe())
                .as("the trace still records the zero EIR limb and both shares")
                .contains("0.30", "is disproportionate", "INR 0");
        }

        @Test
        @DisplayName("splitting one amount and posting another is refused")
        void thePostingMustCarryTheAmountThatWasSplit() {
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.inr("4500000.00"), new BigDecimal("0.45"), new BigDecimal("0.20"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> split.postings(
                    received("SYN-ARR-FEE", "4500000.01", FeeClassification.INTEGRAL)))
                .withMessageContaining("but this split was computed on");
        }

        @Test
        @DisplayName("a fee received out of a nil fee share is incoherent, not a default")
        void aFeeWithNoFeeShareIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.bifurcateSyndicationFee(
                    Money.inr("1000000.00"), BigDecimal.ZERO, new BigDecimal("0.20")))
                .withMessageContaining("nil share of the syndicate fee pool");
        }

        @Test
        @DisplayName("no fee and no fee share splits to nothing rather than dividing by zero")
        void nilFeeAndNilShareIsTheEmptySplit() {
            FeeTreatmentSplit split = FeeTreatmentRules.bifurcateSyndicationFee(
                Money.zero(Money.INR), BigDecimal.ZERO, new BigDecimal("0.20"));

            assertThat(split.toEir()).isEqualTo(Money.zero(Money.INR));
            assertThat(split.toArrangementServiceIncome()).isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("a cost has no arrangement service income limb")
        void aNegativeWholeIsRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.bifurcateSyndicationFee(
                    Money.inr("-500000.00"), new BigDecimal("0.45"), new BigDecimal("0.20")))
                .withMessageContaining("is a cost paid");
        }

        @Test
        @DisplayName("a share outside [0,1] is refused before the division, not by it")
        void sharesMustBeProportions() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.bifurcateSyndicationFee(
                    Money.inr("1000000.00"), new BigDecimal("1.20"), new BigDecimal("0.20")))
                .withMessageContaining("feeShare must be a proportion in [0,1]");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.bifurcateSyndicationFee(
                    Money.inr("1000000.00"), new BigDecimal("0.45"), new BigDecimal("-0.01")))
                .withMessageContaining("retainedShare must be a proportion in [0,1]");
        }

        @Test
        @DisplayName("a split that does not reconstitute cannot be constructed")
        void theReconstitutionGuardIsReal() {
            // Constructed directly rather than through the rule, because the guard exists for
            // the benefit of every future caller and not only this one. One paisa short.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentSplit(
                    Money.inr("1000000.00"), Money.inr("285714.28"), Money.inr("714285.71"),
                    new BigDecimal("0.35"), new BigDecimal("0.10"), "hand-built"))
                .withMessageContaining("must sum to the whole fee exactly");
        }
    }

    @Nested
    @DisplayName("FR-206 — contingent fees excluded at inception, recognised on the event")
    class ContingentFeeTiming {

        @Test
        @DisplayName("a contingent fee is excluded from the inception projection")
        void excludedAtInception() {
            FeeTreatmentRecognition decision = FeeTreatmentRules.atInception(
                received("PREPAY-PENALTY", "15000.00", FeeClassification.AS_INCURRED),
                FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);

            assertThat(decision.entersEirCashFlows())
                .as("out of the inception vector, so it cannot move the solved rate")
                .isFalse();
            assertThat(decision.classification()).isEqualTo(FeeClassification.AS_INCURRED);
            assertThat(decision.awaitedEvent())
                .isEqualTo(FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);
            assertThat(decision.basis()).contains("FR-206");
        }

        @Test
        @DisplayName("the exclusion is only half the rule, and the other half is outstanding")
        void theExclusionLeavesAnObligation() {
            FeeTreatmentRecognition decision = FeeTreatmentRules.atInception(
                received("LATE-FEE", "500.00", FeeClassification.AS_INCURRED),
                FeeTreatmentContingentEvent.LATE_FEE);

            assertThat(decision.outstanding())
                .as("the engine still owes a recognition; FR-206's second limb is not discharged")
                .isTrue();
            assertThat(decision.recognisedOn())
                .as("no period yet, because the event has not happened")
                .isNull();
        }

        @Test
        @DisplayName("a fee excluded with nothing awaited and no date is income silently lost")
        void aBareExclusionIsUnrepresentable() {
            // The defect the record exists to prevent. An implementation of FR-206 that
            // returns only "excluded" produces exactly this state: the fee leaves the
            // projection, nothing downstream holds an obligation, and the prepayment penalty
            // the bank actually collects is never booked. Under-stated income raises no query.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentRecognition(
                    "PREPAY-PENALTY", Money.inr("15000.00"), FeeClassification.AS_INCURRED,
                    false, null, null, "excluded, and nothing more"))
                .withMessageContaining("income the engine has silently lost");
        }

        @Test
        @DisplayName("the event fixes the period, and the realised amount replaces any estimate")
        void recognisedInThePeriodTheEventOccurs() {
            FeeTreatmentRecognition outstanding = FeeTreatmentRules.atInception(
                received("PREPAY-PENALTY", "15000.00", FeeClassification.AS_INCURRED),
                FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);

            // 2% of the balance outstanding on the day the borrower prepaid. Neither the
            // 738,412.55 nor the 14 September 2029 existed at inception, which is precisely
            // why the fee could not be projected: 738,412.55 x 0.02 = 14,768.2510.
            FeeTreatmentRecognition recognised =
                outstanding.recognise(PREPAYMENT, Money.inr("14768.2510"));

            assertThat(recognised.recognisedOn())
                .as("the period the event occurred in, not the inception period")
                .isEqualTo(PREPAYMENT);
            assertThat(recognised.amount())
                .as("the amount realised, not the indicative 15,000.00 carried at inception")
                .isEqualTo(Money.inr("14768.2510"));
            assertThat(recognised.outstanding())
                .as("the obligation is discharged")
                .isFalse();
            assertThat(recognised.awaitedEvent())
                .as("which event fixed the period stays on the record for the audit trail")
                .isEqualTo(FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);
            assertThat(recognised.entersEirCashFlows())
                .as("recognising it in a later period never puts it back into the rate")
                .isFalse();
        }

        @Test
        @DisplayName("recognising the same contingent fee twice is refused")
        void recognisingTwiceIsRefused() {
            FeeTreatmentRecognition recognised = FeeTreatmentRules.atInception(
                    received("BOUNCE-CHARGE", "590.00", FeeClassification.AS_INCURRED),
                    FeeTreatmentContingentEvent.BOUNCE_CHARGE)
                .recognise(PREPAYMENT, Money.inr("590.00"));

            assertThatIllegalStateException()
                .isThrownBy(() -> recognised.recognise(PREPAYMENT, Money.inr("590.00")))
                .withMessageContaining("was already recognised on");
        }

        @Test
        @DisplayName("an integral fee cannot be recognised on an event — it amortises through the rate")
        void anIntegralFeeHasNoEventRecognition() {
            FeeTreatmentRecognition integral = FeeTreatmentRules.atInception(
                received("PROC-FEE", "15000.00", FeeClassification.INTEGRAL));

            assertThatIllegalStateException()
                .isThrownBy(() -> integral.recognise(PREPAYMENT, Money.inr("15000.00")))
                .withMessageContaining("amortises through the EIR");
        }

        @Test
        @DisplayName("an integral fee enters the projection and carries no single recognition date")
        void anIntegralFeeEntersTheProjection() {
            FeeTreatmentRecognition decision = FeeTreatmentRules.atInception(
                received("PROC-FEE", "15000.00", FeeClassification.INTEGRAL));

            assertThat(decision.entersEirCashFlows()).isTrue();
            assertThat(decision.recognisedOn())
                .as("recognised through the rate across the expected life; a date would"
                    + " recognise it twice")
                .isNull();
            assertThat(decision.outstanding()).isFalse();
        }

        @Test
        @DisplayName("giving an integral fee a recognition date is refused")
        void anIntegralFeeMayNotCarryADate() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentRecognition(
                    "PROC-FEE", Money.inr("15000.00"), FeeClassification.INTEGRAL, true, null,
                    INCEPTION, "in the projection and also booked on day one"))
                .withMessageContaining("would recognise it twice");
        }

        @Test
        @DisplayName("a servicing fee is out of the projection and recognised when incurred")
        void asIncurredIsRecognisedWhenIncurred() {
            FeeTreatmentRecognition decision = FeeTreatmentRules.atInception(
                received("SERVICING-FEE", "1200.00", FeeClassification.AS_INCURRED));

            assertThat(decision.entersEirCashFlows()).isFalse();
            assertThat(decision.recognisedOn()).isEqualTo(INCEPTION);
            assertThat(decision.outstanding())
                .as("nothing is awaited: it is already recognised, not deferred")
                .isFalse();
        }

        @Test
        @DisplayName("a commitment fee is refused by name — FR-204 decides it, not FR-206")
        void aCommitmentFeeIsRefusedByName() {
            FeePosting commitment = FeePosting.commitment(
                "COMMIT-FEE", Money.inr("50000.00"), INCEPTION,
                FeeClassification.OVER_COMMITMENT_PERIOD, new BigDecimal("0.30"));

            // Guessing here would put one decision in two rules. The refusal names the owner.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.atInception(commitment))
                .withMessageContaining("FR-204");
        }

        @Test
        @DisplayName("a distinct performance obligation is refused by name — Ind AS 115 dates it")
        void aSeparateServiceFeeIsRefusedByName() {
            // Defaulting it to the posting date would book a three-year advisory mandate
            // collected up front entirely in the inception period. Over-stating income is the
            // mirror of the understatement FR-206 exists to prevent, so the rule refuses
            // rather than guesses — the same stance it takes on a commitment fee.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.atInception(
                    received("ADVISORY-FEE", "900000.00", FeeClassification.SEPARATE_SERVICE)))
                .withMessageContaining("Ind AS 115");
        }

        @Test
        @DisplayName("a commitment fee mapped to a contingent event is refused through both doors")
        void aCommitmentFeeMappedContingentIsStillRefused() {
            // Without the guard on the two-argument form, the same posting is refused by name
            // through one entry point and silently reclassified AS_INCURRED through the other,
            // purely because somebody put its code in the contingency map. One question, two
            // answers, chosen by which overload the caller reached.
            FeePosting commitment = FeePosting.commitment(
                "COMMIT-FEE", Money.inr("50000.00"), INCEPTION,
                FeeClassification.OVER_COMMITMENT_PERIOD, new BigDecimal("0.30"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.atInception(
                    commitment, FeeTreatmentContingentEvent.LATE_FEE))
                .withMessageContaining("FR-204");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.atInception(
                    List.of(commitment),
                    Map.of("COMMIT-FEE", FeeTreatmentContingentEvent.LATE_FEE)))
                .withMessageContaining("FR-204");
        }

        @Test
        @DisplayName("an integral fee outside the inception projection is refused")
        void anIntegralFeeOutsideTheProjectionIsRefused() {
            // The double recognition of anIntegralFeeMayNotCarryADate, reached from the other
            // side: an integral fee amortises through the rate, so it cannot also sit outside
            // the projection with a recognition date.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeeTreatmentRecognition(
                    "PROC-FEE", Money.inr("15000.00"), FeeClassification.INTEGRAL, false, null,
                    INCEPTION, "outside the projection and booked on a date"))
                .withMessageContaining("INTEGRAL but outside the inception projection");
        }

        @Test
        @DisplayName("the trace line for an event-recognised fee reads as a sentence")
        void theTraceLineNamesTheEvent() {
            // The audit trail is a requirement in its own right (FR-907), so a trace line
            // reading "recognised on 2029-09-14 on PREPAYMENT_PENALTY" is a defect in it.
            FeeTreatmentRecognition recognised = FeeTreatmentRules.atInception(
                    received("PREPAY-PENALTY", "15000.00", FeeClassification.AS_INCURRED),
                    FeeTreatmentContingentEvent.PREPAYMENT_PENALTY)
                .recognise(PREPAYMENT, Money.inr("14768.2510"));

            assertThat(recognised.describe())
                .contains("recognised on 2029-09-14 on the PREPAYMENT_PENALTY event");
        }

        @Test
        @DisplayName("a contingent fee the fee master called INTEGRAL is still excluded")
        void theContingencyTestOverridesAMisclassification() {
            // The defect: a prepayment penalty mapped INTEGRAL puts prepayment income into
            // the initial carrying amount and moves the rate for the whole life of the
            // exposure. FR-206 says exclude it, not "exclude it if the fee master agrees".
            FeeTreatmentRecognition decision = FeeTreatmentRules.atInception(
                received("PREPAY-PENALTY", "15000.00", FeeClassification.INTEGRAL),
                FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);

            assertThat(decision.entersEirCashFlows()).isFalse();
            assertThat(decision.classification()).isEqualTo(FeeClassification.AS_INCURRED);
            assertThat(decision.basis())
                .as("the override is recorded, so the fee master defect is visible in the trace")
                .contains("fee master classified this code INTEGRAL");
        }

        @Test
        @DisplayName("the batch form accounts for every posting, contingent or not")
        void everyPostingGetsADecision() {
            List<FeePosting> inception = List.of(
                received("PROC-FEE", "15000.00", FeeClassification.INTEGRAL),
                received("PREPAY-PENALTY", "15000.00", FeeClassification.AS_INCURRED),
                received("SERVICING-FEE", "1200.00", FeeClassification.AS_INCURRED));

            List<FeeTreatmentRecognition> decisions = FeeTreatmentRules.atInception(
                inception,
                Map.of("PREPAY-PENALTY", FeeTreatmentContingentEvent.PREPAYMENT_PENALTY));

            // Total coverage is the point: a screen that returned only the contingent fees
            // would lose the outstanding group, which is the group FR-206 is about.
            assertThat(decisions).as("one decision per posting, in order").hasSize(3);
            assertThat(decisions.get(0).entersEirCashFlows()).isTrue();
            assertThat(decisions.get(1).outstanding()).isTrue();
            assertThat(decisions.get(2).outstanding()).isFalse();
            assertThat(decisions.get(2).recognisedOn()).isEqualTo(INCEPTION);
        }

        @Test
        @DisplayName("a contingency mapping matches a fee code that differs only in case")
        void feeCodesMatchAcrossCase() {
            // The fee master and the source feed are maintained by different teams. A silent
            // failure to match would leave a prepayment penalty in the projection, which is
            // the exact defect FR-206 prevents.
            List<FeeTreatmentRecognition> decisions = FeeTreatmentRules.atInception(
                List.of(received("prepay-penalty", "15000.00", FeeClassification.AS_INCURRED)),
                Map.of(" PREPAY-PENALTY ", FeeTreatmentContingentEvent.PREPAYMENT_PENALTY));

            assertThat(decisions.get(0).awaitedEvent())
                .isEqualTo(FeeTreatmentContingentEvent.PREPAYMENT_PENALTY);
        }

        @Test
        @DisplayName("one fee code mapping to two events is refused")
        void oneCodeCannotAwaitTwoEvents() {
            Map<String, FeeTreatmentContingentEvent> ambiguous = Map.of(
                "LATE-FEE", FeeTreatmentContingentEvent.LATE_FEE,
                "late-fee", FeeTreatmentContingentEvent.BOUNCE_CHARGE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeTreatmentRules.atInception(List.of(), ambiguous))
                .withMessageContaining("cannot say which event a fee awaits");
        }
    }

    @Nested
    @DisplayName("FR-209 — government interest subvention")
    class GovernmentSubvention {

        @Test
        @DisplayName("subvention is outside the EIR and accounted for separately")
        void excludedFromTheEir() {
            FeeTreatmentRecognition decision = FeeTreatmentRules.governmentSubvention(
                "AGRI-SUBVENTION", Money.inr("42000.00"), INCEPTION, false);

            assertThat(decision.entersEirCashFlows())
                .as("not a cash flow between the parties to the contract")
                .isFalse();
            assertThat(decision.classification()).isEqualTo(FeeClassification.AS_INCURRED);
            assertThat(decision.recognisedOn())
                .as("recognised separately as it accrues, and the date is an input")
                .isEqualTo(INCEPTION);
            assertThat(decision.basis()).contains("FR-209", "not a cash flow between the parties");
        }

        @Test
        @DisplayName("a contract that makes the borrower's own rate turn on it brings it inside")
        void aContractLinkedBorrowerRateBringsItIn() {
            // The borrower contractually pays 4% only because the 3% subvention exists, and
            // 7% if it lapses. The flow is then part of what this contract yields to its own
            // parties, and it belongs in the rate.
            FeeTreatmentRecognition decision = FeeTreatmentRules.governmentSubvention(
                "EDU-CSIS-SUBSIDY", Money.inr("42000.00"), INCEPTION, true);

            assertThat(decision.entersEirCashFlows()).isTrue();
            assertThat(decision.classification()).isEqualTo(FeeClassification.INTEGRAL);
            assertThat(decision.recognisedOn())
                .as("inside the EIR, so recognised through the rate and not on a single date")
                .isNull();
        }

        @Test
        @DisplayName("the same position applies across agriculture, education and MSME")
        void oneConsistentPositionAcrossTheThreeSegments() {
            // FR-209's consistency clause. The drift is historically per-scheme: schemes
            // arrive one at a time, each with its own circular and its own project team, and
            // three teams each reaching a defensible answer produce an indefensible set. Here
            // the three segments differ only in their fee code, and the treatment is identical
            // because the contract term is the only input the rule has.
            List<String> schemes = List.of(
                "AGRI-KCC-SUBVENTION", "EDU-CSIS-SUBSIDY", "MSME-INT-SUBVENTION");

            for (String scheme : schemes) {
                FeeTreatmentRecognition out = FeeTreatmentRules.governmentSubvention(
                    scheme, Money.inr("42000.00"), INCEPTION, false);
                FeeTreatmentRecognition in = FeeTreatmentRules.governmentSubvention(
                    scheme, Money.inr("42000.00"), INCEPTION, true);

                assertThat(out.entersEirCashFlows())
                    .as("%s is outside the EIR on the default position", scheme)
                    .isFalse();
                assertThat(out.classification())
                    .as("%s takes the same classification as every other scheme", scheme)
                    .isEqualTo(FeeClassification.AS_INCURRED);
                assertThat(in.entersEirCashFlows())
                    .as("%s comes inside on the same contract term as every other scheme", scheme)
                    .isTrue();
                assertThat(in.classification()).isEqualTo(FeeClassification.INTEGRAL);
            }
        }
    }
}
