package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CASE1_EIR;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.GCA_AT_MONTH_12;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static com.crisil.eir.calc.amort.AmortFixtures.case1EirLeg;
import static com.crisil.eir.calc.amort.AmortFixtures.case1TwoLeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The India divergence (specification 7, reference case 5): compute the unwind,
 * suppress the income.
 *
 * <p>Reference case 5 puts the case 1 loan into Stage 3 at the start of month 13 with
 * a 40% allowance. The one figure the engine computes is the gross-basis interest the
 * Stage 1/2 ledger would have recognised, and everything else is a decomposition of
 * it — which is what makes the Indian treatment tractable and what invariant ST-2
 * says. So ST-2 is asserted exactly, not at presentation scale: round the three
 * components independently and reference case 5 reads
 * {@code 3,304.08 + 2,202.72 = 5,506.80} against a gross figure of {@code 5,506.79}.
 * A paise of rounding, not a broken identity, and asserting the rounded form would
 * either fail on sound inputs or need a tolerance — and a tolerance here would hide
 * the one class of defect this decomposition exists to prevent.
 *
 * <p>The property sweep across allowance percentages is enumerated rather than
 * randomised. A ledger engine's tests are also its replay evidence (invariant DT-1),
 * and a failing case that cannot be reproduced from the test source is worth less than
 * one that can.
 */
class Stage3DecompositionTest {

    /** Reference case 5's 40% lifetime ECL allowance on the month-13 opening balance. */
    private static final Money ALLOWANCE_40_PCT = Money.inr("211362.93");

    /** Contractual interest billed in month 13: 529,815.61 x 1%. What suspense absorbs. */
    private static final Money CONTRACTUAL_INTEREST_BILLED = Money.inr("5298.16");

    @Test
    @DisplayName("the ledger cross-check catches a wrong accrual length, which ST-2's own identity cannot")
    void theLedgerCrossCheckCatchesAWrongAccrualLength() {
        // Why this test exists at all. ST-2, S3-1 and S3-2 are identities between figures
        // the decomposition derives from the same two inputs, so they hold however wrong
        // the accrual factor is — they check the decomposition, never the magnitude. That
        // was already documented. What was NOT true was the fix: a cross-check called
        // accrualConsistency recomputed the gross interest as
        // grossCarryingAmount.times(accretion(eir, exponent)) and compared it to the
        // grossInterest computed as grossCarryingAmount.amount().multiply(accretion,
        // WORKING). Same balance, same accretion, same multiplication, same MathContext:
        // bit-identical by construction, and with a wrong exponent both sides were wrong
        // by the same factor. A second tautology, asserting under the same ST-2 id as the
        // first, added in the belief that it fixed the first.
        //
        // The ledger row is the independent derivation. AmortisationEngine computes its
        // accrualExponent from the flow vector's dates and the convention; a caller of
        // forAccrualPeriod supplies its own. A disagreement between them is a broken
        // period or a mis-selected day count.
        AmortisationRow month13 = case1EirLeg().row(13);
        assertThat(month13.accrualExponent())
            .as("a whole monthly period under PeriodicIndex")
            .isEqualByComparingTo(BigDecimal.ONE);

        Stage3Decomposition sound = Stage3Decomposition.forAccrualPeriod(
            month13.openingGca(), ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED,
            Stage.STAGE_3, BigDecimal.ONE);
        assertThat(sound.againstLedger(month13))
            .as("a sound decomposition agrees with the row it decomposes")
            .allMatch(InvariantResult::satisfied);

        // Half a month's accrual on a monthly rate: every internal identity still holds,
        // and the interest is wrong by nearly half.
        Stage3Decomposition halfPeriod = Stage3Decomposition.forAccrualPeriod(
            month13.openingGca(), ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED,
            Stage.STAGE_3, bd("0.5"));
        assertThat(halfPeriod.invariants())
            .as("ST-2's own identity cannot see this, which is the whole point")
            .allMatch(InvariantResult::satisfied);
        assertThat(halfPeriod.grossBasisInterest().amount())
            .as("and yet the magnitude is materially wrong")
            .isLessThan(sound.grossBasisInterest().amount().multiply(bd("0.51")));

        List<InvariantResult> breaches = halfPeriod.againstLedger(month13).stream()
            .filter(result -> !result.satisfied())
            .toList();
        assertThat(breaches)
            .as("the ledger cross-check must see both the cause and the effect")
            .hasSize(2);
        assertThat(breaches).allMatch(result -> result.id() == InvariantId.ST_2);
        assertThat(breaches.get(0).detail())
            .contains("accrued over 0.5 period(s)")
            .contains("ledger row accrued over 1")
            .contains("broken period or a mis-selected day count");
    }

    @Test
    @DisplayName("exactly one ST-2 travels on a decomposition, not two under the same id")
    void oneStageTwoIdentityPerDecomposition() {
        // The duplicate-control defect, in the place it was introduced. Two results under
        // one invariant id means anything reading ST-2 by name gets whichever comes first,
        // and ST-2 is the invariant an ACPIR auditor looks at hardest.
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED,
            Stage.STAGE_3);

        assertThat(decomposition.invariants().stream()
            .filter(result -> result.id() == InvariantId.ST_2)
            .count())
            .isEqualTo(1);
    }

    @Test
    @DisplayName("reference case 5: 5,506.79 gross splits into 3,304.08 net and 2,202.72 unwind")
    void referenceCaseFive() {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        // The allowance is 40% of the gross carrying amount, and the amortised cost the
        // other 60% — both figures the reference case states.
        assertThat(GCA_AT_MONTH_12.times(bd("0.40")).atPresentationScale().amount())
            .isEqualByComparingTo(bd("211362.93"));
        assertThat(GCA_AT_MONTH_12.minus(ALLOWANCE_40_PCT).atPresentationScale().amount())
            .isEqualByComparingTo(bd("317044.39"));

        assertThat(decomposition.grossBasisInterest().atPresentationScale().amount())
            .isEqualByComparingTo(bd("5506.79"));
        assertThat(decomposition.netBasisInterest().atPresentationScale().amount())
            .isEqualByComparingTo(bd("3304.08"));
        assertThat(decomposition.eclUnwind().atPresentationScale().amount())
            .isEqualByComparingTo(bd("2202.72"));
        assertThat(decomposition.recognisedIncome().atPresentationScale().amount())
            .isEqualByComparingTo(bd("0.00"));
        assertThat(decomposition.toSuspense().atPresentationScale().amount())
            .isEqualByComparingTo(bd("5298.16"));

        // The gross-basis figure is the same 5,506.79 the case 1 EIR leg recognises in
        // period 13 — because staging changed neither the balance nor the rate.
        assertThat(case1EirLeg().row(13).presentedEirInterest().amount())
            .isEqualByComparingTo(decomposition.grossBasisInterest().atPresentationScale().amount());
        assertThat(decomposition.breaches()).isEmpty();
        assertThat(decomposition.orThrow()).isSameAs(decomposition);
    }

    @Test
    @DisplayName("ST-2 is exact: net + unwind = gross with a zero deviation, unrounded")
    void stageTwoIdentityIsExactAndNotAToleranceCheck() {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        BigDecimal recomposed =
            decomposition.netBasisInterest().amount().add(decomposition.eclUnwind().amount());
        assertThat(recomposed).isEqualByComparingTo(decomposition.grossBasisInterest().amount());
        assertThat(recomposed.subtract(decomposition.grossBasisInterest().amount()).signum()).isEqualTo(0);

        InvariantResult stageTwo = invariant(decomposition, InvariantId.ST_2);
        assertThat(stageTwo.satisfied()).isTrue();
        assertThat(stageTwo.deviation().signum()).isEqualTo(0);

        // And the rounded components do not add up, which is exactly why the invariant is
        // stated unrounded: 3,304.08 + 2,202.72 is 5,506.80 against a gross 5,506.79.
        Money presentedSum = decomposition.netBasisInterest().atPresentationScale()
            .plus(decomposition.eclUnwind().atPresentationScale());
        assertThat(presentedSum.amount()).isEqualByComparingTo(bd("5506.80"));
        assertThat(decomposition.grossBasisInterest().atPresentationScale().amount())
            .isEqualByComparingTo(bd("5506.79"));
    }

    @ParameterizedTest(name = "allowance {0}% of the gross carrying amount")
    @MethodSource("allowancePercentages")
    @DisplayName("ST-2 holds as a property across every allowance from 0% to 100%")
    void stageTwoIdentityAcrossTheAllowanceRange(String percentage) {
        Money allowance = GCA_AT_MONTH_12.times(bd(percentage).divide(bd("100"), Precision.WORKING));
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, allowance, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        // Both sides are the same rate on complementary slices of one balance, so the
        // identity is arithmetic and holds at every allowance level, endpoints included.
        assertThat(decomposition.netBasisInterest().amount().add(decomposition.eclUnwind().amount()))
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());
        assertThat(invariant(decomposition, InvariantId.ST_2).satisfied()).isTrue();
        assertThat(invariant(decomposition, InvariantId.ST_2).deviation().signum()).isEqualTo(0);
        assertThat(decomposition.recognisedIncome().isZero()).isTrue();
    }

    private static Stream<String> allowancePercentages() {
        List<String> values = new ArrayList<>();
        for (int half = 0; half <= 200; half++) {
            values.add(bd(String.valueOf(half)).divide(bd("2")).toPlainString());
        }
        return values.stream();
    }

    @Test
    @DisplayName("at a nil allowance the net basis is the gross basis and the unwind is zero")
    void nilAllowanceEndpoint() {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, Money.zero(Money.INR), CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        assertThat(decomposition.eclUnwind().isZero()).isTrue();
        assertThat(decomposition.netBasisInterest().amount())
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());
        assertThat(decomposition.netBasisInterest().atPresentationScale().amount())
            .isEqualByComparingTo(bd("5506.79"));
        // Recognition is still nil: ACPIR suppresses on the staging, not on the allowance.
        assertThat(decomposition.recognisedIncome().isZero()).isTrue();
        assertThat(decomposition.breaches()).isEmpty();
    }

    @Test
    @DisplayName("at a 100% allowance the unwind is the whole gross figure and the net basis is nil")
    void fullAllowanceEndpoint() {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, GCA_AT_MONTH_12, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        assertThat(decomposition.netBasisInterest().isZero()).isTrue();
        assertThat(decomposition.eclUnwind().atPresentationScale().amount())
            .isEqualByComparingTo(bd("5506.79"));
        assertThat(decomposition.eclUnwind().amount())
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());
        assertThat(invariant(decomposition, InvariantId.ST_2).satisfied()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    @DisplayName("S3-2: recognition is nil in Stage 3 and the gross-basis figure in Stages 1 and 2")
    void recognitionFollowsTheStage(Stage stage) {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, stage);

        if (stage == Stage.STAGE_3) {
            assertThat(decomposition.incomeSuppressed()).isTrue();
            assertThat(decomposition.recognisedIncome().isZero()).isTrue();
            assertThat(invariant(decomposition, InvariantId.S3_2).satisfied()).isTrue();
            // The contractual amount billed goes to suspense — a first-class ledger object,
            // and the contractual figure rather than the EIR one, because what is billed is
            // what the borrower owes.
            assertThat(decomposition.toSuspense().atPresentationScale().amount())
                .isEqualByComparingTo(bd("5298.16"));
            assertThat(invariant(decomposition, InvariantId.S3_1).satisfied()).isTrue();
        } else {
            assertThat(decomposition.incomeSuppressed()).isFalse();
            assertThat(decomposition.recognisedIncome().isZero()).isFalse();
            assertThat(decomposition.recognisedIncome().atPresentationScale().amount())
                .isEqualByComparingTo(bd("5506.79"));
            // Nothing to suspense outside Stage 3, and no S3-2 to assert: the check is
            // vacuously satisfied and the decomposition does not pretend otherwise.
            assertThat(decomposition.toSuspense().isZero()).isTrue();
            assertThat(decomposition.invariants().stream().map(InvariantResult::id))
                .doesNotContain(InvariantId.S3_2);
            assertThat(InvariantChecks.stageThreeNilRecognition(stage, decomposition.recognisedIncome())
                .satisfied()).isTrue();
        }

        // Whatever the stage, IFRS 9's answer is retained for the parallel-basis
        // disclosure, and the unwind is retained for the ECL roll-forward. Neither is P&L
        // under ACPIR and neither depends on the staging.
        assertThat(decomposition.ifrs9RecognisedIncome().atPresentationScale().amount())
            .isEqualByComparingTo(bd("3304.08"));
        assertThat(decomposition.shadowUnwind().atPresentationScale().amount())
            .isEqualByComparingTo(bd("2202.72"));
    }

    @Test
    @DisplayName("S3-2 catches recognition leaking into a Stage 3 period")
    void nilRecognitionCheckDetectsALeak() {
        InvariantResult breach = InvariantChecks.stageThreeNilRecognition(Stage.STAGE_3, Money.inr("3304.08"));

        assertThat(breach.satisfied()).isFalse();
        assertThat(breach.id()).isEqualTo(InvariantId.S3_2);
        // The IFRS 9 net-basis figure recognised under ACPIR is the specific mistake this
        // catches: a correct number from the wrong framework.
        assertThat(breach.deviation()).isEqualByComparingTo(bd("3304.08"));
    }

    @Test
    @DisplayName("staging changes neither the EIR nor the gross carrying amount")
    void stagingIsNotAnEirEvent() {
        List<InvariantResult> results = Stage3Decomposition.stagingIsNotAnEirEvent(
            CASE1_EIR, CASE1_EIR, GCA_AT_MONTH_12, GCA_AT_MONTH_12);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.satisfied()).isTrue();
            assertThat(result.id()).isEqualTo(InvariantId.S3_1);
        });

        // The decomposition has no channel through which either could move: it returns no
        // rate and no carrying amount, only the recognition answer.
        Stage3Decomposition inStageThree = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);
        Stage3Decomposition performing = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_1);
        assertThat(inStageThree.grossBasisInterest().amount())
            .isEqualByComparingTo(performing.grossBasisInterest().amount());
    }

    @Test
    @DisplayName("a staged-down rate or a net-of-allowance balance is a detected breach, not a rounding")
    void stagingCheckDetectsAReSolveOrARestatement() {
        // The failure mode: a stage change routed through a general event handler that
        // re-solves by default, or a ledger that nets the allowance off the balance.
        Rate resolved = Rate.monthly(bd("0.008"));
        List<InvariantResult> rateMoved = Stage3Decomposition.stagingIsNotAnEirEvent(
            CASE1_EIR, resolved, GCA_AT_MONTH_12, GCA_AT_MONTH_12);
        assertThat(rateMoved.getFirst().satisfied()).isFalse();

        List<InvariantResult> balanceNetted = Stage3Decomposition.stagingIsNotAnEirEvent(
            CASE1_EIR, CASE1_EIR, GCA_AT_MONTH_12, GCA_AT_MONTH_12.minus(ALLOWANCE_40_PCT));
        assertThat(balanceNetted.getLast().satisfied()).isFalse();
        assertThat(balanceNetted.getLast().deviation().abs()).isEqualByComparingTo(bd("211362.93"));
    }

    @Test
    @DisplayName("the gross carrying amount keeps rolling forward through Stage 3, unchanged")
    void theGrossLedgerIsIndifferentToStaging() {
        // Periods 13 to 16 of case 1 are the suppressed months in this scenario. Their
        // gross-basis interest and balances are the case 1 table's own: the roll-forward
        // has no stage parameter, which is the structural version of the assertion.
        AmortisationResult leg = case1EirLeg();

        assertThat(leg.row(13).presentedOpeningGca().amount()).isEqualByComparingTo(bd("528407.32"));
        assertThat(leg.row(16).presentedClosingGca().amount()).isEqualByComparingTo(bd("359523.38"));
        for (int period = 13; period <= 16; period++) {
            Stage3Decomposition suppressed = Stage3Decomposition.forPeriod(
                leg.row(period).openingGca(),
                leg.row(period).openingGca().times(bd("0.40")),
                CASE1_EIR,
                case1TwoLeg().rows().get(period - 1).contractualInterest(),
                Stage.STAGE_3);
            assertThat(suppressed.grossBasisInterest().amount())
                .isEqualByComparingTo(leg.row(period).eirInterest().amount());
            assertThat(suppressed.recognisedIncome().isZero()).isTrue();
        }
    }

    @Test
    @DisplayName("cure resumes recognition prospectively and produces no catch-up amount")
    void cureBooksNothingForTheSuppressedPeriods() {
        AmortisationResult leg = case1EirLeg();
        // Suppressed through months 13 to 15, cured into Stage 1 for month 16.
        Money suppressed = Money.zero(Money.INR);
        for (int period = 13; period <= 15; period++) {
            suppressed = suppressed.plus(leg.row(period).eirInterest());
        }
        assertThat(suppressed.atPresentationScale().amount()).isEqualByComparingTo(bd("15216.30"));

        Money openingAtCure = leg.row(16).openingGca();
        Stage3Decomposition cured = Stage3Decomposition.onCure(
            openingAtCure,
            openingAtCure.times(bd("0.40")),
            CASE1_EIR,
            case1TwoLeg().rows().get(15).contractualInterest(),
            Stage.STAGE_1);

        // The cure period recognises its own gross-basis interest and nothing more. Not
        // 15,216.30 + 4,193.64, and not any part of the 15,216.30 — booking one would
        // recognise income that was correctly never recognised, restating a period that was
        // not wrong.
        assertThat(cured.recognisedIncome().atPresentationScale().amount()).isEqualByComparingTo(bd("4193.64"));
        assertThat(cured.recognisedIncome().amount())
            .isEqualByComparingTo(cured.grossBasisInterest().amount());
        assertThat(cured.recognisedIncome().amount()).isEqualByComparingTo(leg.row(16).eirInterest().amount());
        assertThat(cured.recognisedIncome().minus(cured.grossBasisInterest()).isZero()).isTrue();
        assertThat(cured.recognisedIncome().amount()).isLessThan(suppressed.amount());

        // The cure records that assertion rather than leaving it implicit, and nothing goes
        // to suspense in a cured period.
        assertThat(cured.invariants().stream().map(InvariantResult::id)).contains(InvariantId.S3_1);
        assertThat(cured.breaches()).isEmpty();
        assertThat(cured.toSuspense().isZero()).isTrue();
        assertThat(cured.incomeSuppressed()).isFalse();
    }

    @Test
    @DisplayName("a cure into Stage 3 is a contradiction and is rejected")
    void cureCannotLandInStageThree() {
        assertThatThrownBy(() -> Stage3Decomposition.onCure(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a cure cannot land in STAGE_3");
    }

    @Test
    @DisplayName("a negative allowance is rejected rather than producing a yield above the gross basis")
    void negativeAllowanceIsRejected() {
        assertThatThrownBy(() -> Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, Money.inr("-1"), CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allowance must not be negative");
    }

    @Test
    @DisplayName("mixed currencies are rejected: the three figures describe one exposure")
    void currenciesMustAgree() {
        Money usdAllowance = Money.of(bd("211362.93"), Currency.getInstance("USD"));

        assertThatThrownBy(() -> Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, usdAllowance, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must agree");
    }

    @Test
    @DisplayName("the four Stage 3 quantities reconcile — control S3-1")
    void theFourQuantitiesReconcile() {
        Stage3Decomposition decomposition = Stage3Decomposition.forPeriod(
            GCA_AT_MONTH_12, ALLOWANCE_40_PCT, CASE1_EIR, CONTRACTUAL_INTEREST_BILLED, Stage.STAGE_3);

        // 1. The gross carrying amount rolls forward on the gross basis: 528,407.32 accretes
        //    5,506.79, the same as any performing period.
        // 2. The shadow unwind is retained: 2,202.72, never P&L.
        // 3. Interest-in-suspense absorbs the contractual amount billed: 5,298.16.
        // 4. Recognised interest income is nil.
        assertThat(decomposition.recognisedIncome().plus(decomposition.toSuspense()).amount())
            .isEqualByComparingTo(CONTRACTUAL_INTEREST_BILLED.amount());
        assertThat(decomposition.grossBasisInterest().amount())
            .isEqualByComparingTo(decomposition.netBasisInterest().plus(decomposition.shadowUnwind()).amount());

        // The suspense figure is the contractual one, and it is not the EIR figure: 5,298.16
        // billed against 5,506.79 accreted. Suspending the EIR amount would suspend a number
        // the borrower was never billed and the suspense ledger would stop reconciling to the
        // customer statement.
        assertThat(CONTRACTUAL_INTEREST_BILLED.amount())
            .isNotEqualByComparingTo(decomposition.grossBasisInterest().atPresentationScale().amount());
        assertThat(AmortFixtures.CONTRACTUAL_BALANCE_AT_MONTH_12.times(CONTRACTUAL.periodic())
            .atPresentationScale().amount()).isEqualByComparingTo(bd("5298.16"));
    }

    private static InvariantResult invariant(Stage3Decomposition decomposition, InvariantId id) {
        return decomposition.invariants().stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " was not asserted"));
    }
}
