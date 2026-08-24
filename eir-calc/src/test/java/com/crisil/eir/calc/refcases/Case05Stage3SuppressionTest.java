package com.crisil.eir.calc.refcases;

import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.baseline;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.bd;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.paise;
import static com.crisil.eir.calc.refcases.ReferenceCaseFixtures.periodicPercent;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.Stage3Decomposition;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reference case 5 — Stage 3 under ACPIR: compute the unwind, suppress the income.
 *
 * <p>The India divergence, and the reason an off-the-shelf IFRS 9 sub-ledger does
 * not solve this problem. IFRS 9 recognises 3,304.08 on the net basis; ACPIR
 * recognises nothing. But ECL is a present-value measure discounted at the EIR
 * (ACPIR 50), so the discount unwinds mechanically whether or not anything is
 * recognised — which is why the engine must compute a figure it will never post.
 *
 * <p>The identity that makes the Indian treatment tractable is ST-2: the ECL
 * discount unwind is <em>precisely</em> the interest the gross basis would have
 * recognised on the allowance portion of the balance. So the engine needs no
 * separate unwind model; it computes one gross-basis figure and decomposes it.
 *
 * <p>Figures from {@code docs/reference-cases/case-05-stage-3-acpir-suppression.md}.
 */
class Case05Stage3SuppressionTest {

    private final ReferenceCaseFixtures.Baseline case1 = baseline();

    /** Gross carrying amount at month-13 opening — case 1's month-12 closing balance. */
    private Money grossCarryingAmount() {
        return case1.eirLeg().row(13).openingGca();
    }

    /** Lifetime ECL allowance at 40% of the gross carrying amount, as a ledger holds it. */
    private Money allowance() {
        return grossCarryingAmount().times(bd("0.40")).atPresentationScale();
    }

    /** Contractual interest billed for month 13: 1% on the contractual balance. */
    private Money contractualInterestBilled() {
        return case1.contractualLeg().row(13).interestAccrued();
    }

    private Stage3Decomposition stage3() {
        return Stage3Decomposition.forPeriod(
            grossCarryingAmount(), allowance(), case1.eir(), contractualInterestBilled(),
            Stage.STAGE_3);
    }

    @Test
    @DisplayName("position: GCA 528,407.32, allowance 211,362.93, amortised cost 317,044.39")
    void thePosition() {
        assertThat(paise(grossCarryingAmount()))
            .as("gross carrying amount, month 13 opening")
            .isEqualByComparingTo(bd("528407.32"));
        assertThat(paise(allowance()))
            .as("lifetime ECL allowance at 40%")
            .isEqualByComparingTo(bd("211362.93"));
        assertThat(paise(grossCarryingAmount().minus(allowance())))
            .as("amortised cost, net of allowance")
            .isEqualByComparingTo(bd("317044.39"));
        assertThat(periodicPercent(case1.eir()))
            .as("EIR unchanged — staging is not an EIR event")
            .isEqualByComparingTo(bd("1.04214918"));
    }

    @Test
    @DisplayName("the three-way decomposition: 5,506.79 gross, 3,304.08 net, 2,202.72 unwind")
    void theThreeWayDecomposition() {
        Stage3Decomposition decomposition = stage3();

        assertThat(paise(decomposition.grossBasisInterest()))
            .as("(a) gross-basis interest, GCA x EIR — what Stage 1/2 would recognise")
            .isEqualByComparingTo(bd("5506.79"));
        assertThat(paise(decomposition.netBasisInterest()))
            .as("(b) IFRS 9 Stage 3 net-basis interest, AC x EIR")
            .isEqualByComparingTo(bd("3304.08"));
        assertThat(paise(decomposition.eclUnwind()))
            .as("(c) ECL discount unwind, allowance x EIR")
            .isEqualByComparingTo(bd("2202.72"));
    }

    @Test
    @DisplayName("ST-2 holds exactly: (b) + (c) = (a), unrounded")
    void theStageTwoIdentityIsExact() {
        Stage3Decomposition decomposition = stage3();

        // Asserted unrounded on purpose. Round the three components independently and the
        // fixture reads 3,304.08 + 2,202.72 = 5,506.80 against a gross figure of
        // 5,506.79 — a paisa of rounding, not a broken identity.
        BigDecimal recomposed = decomposition.netBasisInterest().amount()
            .add(decomposition.eclUnwind().amount());
        assertThat(recomposed)
            .as("the identity holds at working precision, which is where it is asserted")
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());

        InvariantResult stageTwo = only(decomposition.invariants(), InvariantId.ST_2);
        assertThat(stageTwo.satisfied()).isTrue();
        assertThat(stageTwo.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("ACPIR recognises 0.00 and sends 5,298.16 of billed interest to suspense")
    void acpirSuppressesRecognitionEntirely() {
        Stage3Decomposition decomposition = stage3();

        assertThat(paise(decomposition.recognisedIncome()))
            .as("recognised interest income under ACPIR — invariant S3-2")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(paise(decomposition.toSuspense()))
            .as("interest-in-suspense: the contractual interest billed, a first-class ledger object")
            .isEqualByComparingTo(bd("5298.16"));
        assertThat(paise(contractualInterestBilled()))
            .as("and that figure is 1% on the contractual balance, not the EIR figure — what "
                + "is billed is what the borrower owes")
            .isEqualByComparingTo(bd("5298.16"));
        assertThat(decomposition.incomeSuppressed()).isTrue();
        assertThat(only(decomposition.invariants(), InvariantId.S3_2).satisfied()).isTrue();
    }

    @Test
    @DisplayName("the shadow unwind of 2,202.72 is retained for the ECL roll-forward and never P&L")
    void theShadowUnwindIsRetainedButNeverRecognised() {
        Stage3Decomposition decomposition = stage3();

        assertThat(paise(decomposition.shadowUnwind()))
            .as("ACPIR does not say where the unwind goes; the engine still has to compute it")
            .isEqualByComparingTo(bd("2202.72"));
        assertThat(paise(decomposition.recognisedIncome()))
            .as("and it is not this")
            .isEqualByComparingTo(bd("0.00"));
    }

    @Test
    @DisplayName("the IFRS 9 comparative of 3,304.08 is retained for the parallel-basis disclosure")
    void theIfrs9ComparativeIsRetained() {
        assertThat(paise(stage3().ifrs9RecognisedIncome()))
            .as("what IFRS 9 would have recognised on the net basis")
            .isEqualByComparingTo(bd("3304.08"));
    }

    @Test
    @DisplayName("S3-1: the four maintained quantities reconcile and the decomposition is clean")
    void theFourWayReconciliation() {
        Stage3Decomposition decomposition = stage3();

        // 1. Gross carrying amount continues rolling on the gross basis.
        // 2. Shadow EIR unwind, retained for the ECL roll-forward.
        // 3. Interest-in-suspense.
        // 4. Recognised interest income: nil.
        assertThat(paise(decomposition.recognisedIncome().plus(decomposition.toSuspense())))
            .as("billed interest splits into recognised income and suspense with nothing unallocated")
            .isEqualByComparingTo(paise(contractualInterestBilled()));
        assertThat(decomposition.breaches())
            .as("breaches: %s", decomposition.breaches())
            .isEmpty();
    }

    @Test
    @DisplayName("staging moves neither the EIR nor the gross carrying amount")
    void stagingIsNotAnEirEvent() {
        List<InvariantResult> results = Stage3Decomposition.stagingIsNotAnEirEvent(
            case1.eir(), case1.eir(), grossCarryingAmount(), grossCarryingAmount());

        assertThat(results).allMatch(InvariantResult::satisfied);
        assertThat(results).allMatch(result -> result.id() == InvariantId.S3_1);
    }

    @Test
    @DisplayName("on cure, recognition resumes on the gross basis prospectively with no catch-up")
    void cureRecognisesOnlyItsOwnPeriod() {
        Stage3Decomposition cured = Stage3Decomposition.onCure(
            grossCarryingAmount(), allowance(), case1.eir(), contractualInterestBilled(),
            Stage.STAGE_1);

        assertThat(paise(cured.recognisedIncome()))
            .as("the cure period recognises its own gross-basis interest and nothing more; "
                + "booking a catch-up would recognise income that was correctly never recognised")
            .isEqualByComparingTo(bd("5506.79"));
        assertThat(paise(cured.toSuspense()))
            .as("nothing goes to suspense once recognition has resumed")
            .isEqualByComparingTo(bd("0.00"));
        assertThat(cured.incomeSuppressed()).isFalse();
        assertThat(cured.breaches()).isEmpty();
    }

    private static InvariantResult only(List<InvariantResult> results, InvariantId id) {
        return results.stream()
            .filter(result -> result.id() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + id + " result was recorded"));
    }
}
