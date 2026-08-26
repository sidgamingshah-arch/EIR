package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.amort.Stage3Decomposition;
import com.crisil.eir.calc.amort.SuspenseLedger;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.calc.amort.FloorBasis;
import com.crisil.eir.calc.amort.FloorApplication;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;

/**
 * The two invariants that have no IFRS 9 analogue, and the one that guards against
 * a B5.4.6 event being routed as a B5.4.5 one.
 *
 * <p>ST-2 and S3-2 are the India-specific pair (section 7): ACPIR suppresses income
 * recognition on Stage 3 entirely, while ECL remains a present-value measure
 * discounted at the EIR, so the discount unwinds mechanically whether or not
 * anything is recognised. The engine's answer is that it does not need a separate
 * unwind model — it computes one gross-basis figure and decomposes it — and ST-2 is
 * the assertion that the decomposition is a decomposition. CU-1 is the cheapest
 * available detector of a catch-up discounted at a re-solved rate, which drives the
 * catch-up towards zero and quietly converts a re-estimation into a reset.
 *
 * <p>These properties are generated at the value level rather than through the
 * projector, because their inputs are a balance, an allowance and a rate — not a
 * contract. Sweeping them through a projector would spend the tries on the
 * projector's variation and cover less of theirs.
 */
class StageAndCatchUpPropertiesTest {

    private static final LocalDate EVENT_DATE = LocalDate.of(2027, 4, 1);
    private static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /**
     * Invariant ST-2, exactly, for any allowance between nil and the whole gross
     * carrying amount: {@code net-basis interest + ECL unwind = gross-basis
     * interest}.
     *
     * <p>Reference case 5 reads 3,304.08 + 2,202.72 = 5,506.79 on one balance at one
     * allowance ratio. The question a generator answers is whether that is arithmetic
     * or an artefact of those magnitudes, and it is arithmetic: the unwind is
     * {@code allowance x EIR} and the net-basis figure is the gross figure
     * <em>less that same unwind</em>, so the recomposition is an exact subtraction
     * reversed.
     *
     * <p>The independent derivation is asserted too — {@code (gross - allowance) x
     * EIR}, computed without reference to the engine's decomposition — and it is
     * exact rather than nearly exact for a reason worth recording: a balance at
     * currency scale carries at most twelve significant digits and a stored rate
     * twelve decimal places, so their product needs twenty-four and the working
     * precision keeps twenty-eight (1.2, ADR-0002). The headroom is what makes the
     * identity hold with no tolerance anywhere, and an allowance rounded to
     * presentation scale is exactly what a booked ECL figure is (1.3).
     */
    @Property(tries = 10000, seed = "20270406")
    void theStageThreeDecompositionIsExactForAnyAllowanceUpToTheGrossAmount(
            @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal grossAmount,
            @ForAll @BigRange(min = "0", max = "1") @Scale(6) BigDecimal allowanceFraction,
            @ForAll @BigRange(min = "0.000000000001", max = "0.5") @Scale(12) BigDecimal periodicRate,
            @ForAll Stage stage) {

        Money gross = Money.of(grossAmount, Money.INR);
        Money allowance = gross.times(allowanceFraction).atPresentationScale();
        Rate eir = Rate.monthly(periodicRate);
        Money billed = gross.times(periodicRate).atPresentationScale();

        Stage3Decomposition decomposition =
            Stage3Decomposition.forPeriod(gross, allowance, eir, billed, stage);

        assertThat(decomposition.netBasisInterest().amount()
                .add(decomposition.eclUnwind().amount()))
            .as("ST-2 recomposition on %s against an allowance of %s", gross, allowance)
            .isEqualByComparingTo(decomposition.grossBasisInterest().amount());
        assertThat(decomposition.netBasisInterest().amount())
            .as("ST-2 against the independent derivation (gross - allowance) x EIR")
            .isEqualByComparingTo(gross.minus(allowance).times(periodicRate).amount());
        assertThat(decomposition.eclUnwind().amount())
            .isEqualByComparingTo(allowance.times(periodicRate).amount());
        assertThat(decomposition.breaches())
            .as("decomposition invariants for %s at %s allowance", stage, allowance)
            .isEmpty();

        InvariantResult stageTwo = InvariantChecks.stageThreeDecomposition(
            decomposition.grossBasisInterest(), decomposition.netBasisInterest(),
            decomposition.eclUnwind());
        assertThat(stageTwo.satisfied()).isTrue();
        assertThat(stageTwo.deviation().signum())
            .as("ST-2 is asserted unrounded, so the deviation is not merely small")
            .isZero();
    }

    /**
     * Invariant S3-2: recognised interest income on a Stage 3 contract is nil, for
     * every balance and every allowance — and the gross-basis figure outside Stage 3.
     *
     * <p>The two limbs belong in one property because the divergence is the point.
     * ACPIR recognises nothing on a Stage 3 exposure; IFRS 9 would recognise the
     * net-basis figure; the engine computes both and publishes the ACPIR answer,
     * with the IFRS 9 figure retained as a comparative and the contractual amount
     * going to the suspense ledger. Staging never touches the rate or the gross
     * carrying amount (7.4), so nothing about this depends on how the exposure was
     * priced.
     */
    @Property(tries = 10000, seed = "20270407")
    void noInterestIncomeIsRecognisedInStageThreeAtAnyAllowance(
            @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal grossAmount,
            @ForAll @BigRange(min = "0", max = "1") @Scale(6) BigDecimal allowanceFraction,
            @ForAll @BigRange(min = "0.000000000001", max = "0.5") @Scale(12) BigDecimal periodicRate,
            @ForAll Stage stage) {

        Money gross = Money.of(grossAmount, Money.INR);
        Money allowance = gross.times(allowanceFraction).atPresentationScale();
        Rate eir = Rate.monthly(periodicRate);
        Money billed = gross.times(periodicRate).atPresentationScale();

        Stage3Decomposition decomposition =
            Stage3Decomposition.forPeriod(gross, allowance, eir, billed, stage);
        InvariantResult nilRecognition =
            InvariantChecks.stageThreeNilRecognition(stage, decomposition.recognisedIncome());
        assertThat(nilRecognition.satisfied())
            .as("S3-2 for %s: %s", stage, nilRecognition.detail())
            .isTrue();

        if (stage.suppressesIncomeRecognition()) {
            assertThat(decomposition.recognisedIncome().isZero())
                .as("recognised income in %s on %s", stage, gross)
                .isTrue();
            assertThat(decomposition.incomeSuppressed()).isTrue();
            assertThat(decomposition.toSuspense().atPresentationScale())
                .as("the suspense ledger absorbs the contractual amount, not the EIR amount")
                .isEqualTo(billed.atPresentationScale());
            assertThat(decomposition.shadowUnwind().amount())
                .as("the unwind is computed and retained even though nothing is recognised")
                .isEqualByComparingTo(decomposition.eclUnwind().amount());
        } else {
            assertThat(decomposition.recognisedIncome().amount())
                .as("Stage 1 and Stage 2 recognise the gross-basis figure (5.1)")
                .isEqualByComparingTo(decomposition.grossBasisInterest().amount());
            assertThat(decomposition.toSuspense().isZero()).isTrue();
        }
    }

    /**
     * Invariant CU-1 and CU-2: whatever the revised flows, the restatement discounts
     * them at the EIR that was already in force and the persisted rate does not move.
     *
     * <p>A frequently seen defect is discounting at a re-solved rate, which drives
     * the catch-up to approximately zero and quietly converts a B5.4.6 event into a
     * B5.4.5 one — see
     * {@link InvariantBoundaryCasesTest#aCatchUpDiscountedAtAReSolvedRateAllButVanishes()}
     * for that arithmetic worked through. CU-1 is asserted here on bit-identity of
     * the stored representation rather than on numeric equality, because
     * "the persisted rate did not change" is a statement about the stored value,
     * scale included.
     *
     * <p>The restated balance is also checked against an independent present value
     * and the roll-forward that follows it is checked to close at zero: the restated
     * carrying amount <em>is</em> the present value of those flows at that rate, so it
     * amortises to nothing over them by construction, and a residue would mean the
     * restatement and the ledger were handed different vectors.
     */
    @Property(tries = 5000, seed = "20270408")
    void aCatchUpRestatesTheBalanceAndLeavesTheRateBitIdentical(
            @ForAll @BigRange(min = "0.000000000001", max = "0.05") @Scale(12) BigDecimal periodicRate,
            @ForAll @BigRange(min = "1000", max = "10000000") @Scale(2) BigDecimal carryingAmount,
            @ForAll @IntRange(min = 1, max = 240) int remainingPeriods,
            @ForAll @BigRange(min = "10", max = "500000") @Scale(2) BigDecimal instalment,
            @ForAll @BigRange(min = "0", max = "2000000") @Scale(2) BigDecimal balloon) {

        Rate originalEir = Rate.monthly(periodicRate);
        Money gcaBefore = Money.of(carryingAmount, Money.INR);
        FlowVector revised = revisedFlows(remainingPeriods, instalment, balloon);

        CatchUpResult restatement =
            CatchUpCalculator.restate(originalEir, gcaBefore, revised, MONTHLY);

        assertThat(InvariantChecks.bitIdentical(restatement.eirBefore(), restatement.eirAfter()))
            .as("CU-1: the persisted EIR across the restatement of %s flows", revised.size())
            .isTrue();
        assertThat(restatement.eirAfter().periodic().toPlainString())
            .isEqualTo(originalEir.periodic().toPlainString());
        assertThat(breach(restatement, InvariantId.CU_1)).isNull();
        assertThat(breach(restatement, InvariantId.CU_2)).isNull();

        Money independentPresentValue = presentValue(originalEir, revised);
        assertThat(restatement.restatedGca().amount())
            .as("CU-2: the restated balance is the present value at the original rate")
            .isEqualByComparingTo(independentPresentValue.amount());
        assertThat(restatement.catchUp().amount())
            .as("CU-2: the catch-up is the movement in the balance and nothing else")
            .isEqualByComparingTo(independentPresentValue.minus(gcaBefore).amount());
        assertThat(restatement.isIncome()).isEqualTo(restatement.catchUp().isPositive());
        assertThat(restatement.isCharge()).isEqualTo(restatement.catchUp().isNegative());

        AmortisationResult forward =
            CatchUpCalculator.rollForwardRestated(restatement, revised, MONTHLY);
        assertThat(forward.isClean())
            .as("TR-1 on the restated schedule: %s", forward.breaches())
            .isTrue();
        assertThat(forward.presentedTerminalBalance().isZero()).isTrue();

        // The restated balance amortises the revised flows and nothing else, so the
        // interest it recognises over them is the balance's own accretion.
        assertThat(forward.totalInterest().plus(restatement.restatedGca()).atPresentationScale())
            .as("the restated leg's interest plus its opening balance is the revised cash")
            .isEqualTo(forward.totalCash().atPresentationScale());

        // And the same restatement, run against a rate that was re-solved instead of
        // retained, would be a CU-1 breach. Asserting that the detector fires is what
        // stops it becoming decoration.
        Rate reSolved = Rate.monthly(periodicRate.add(new BigDecimal("0.000000000001")));
        CatchUpResult misrouted =
            CatchUpCalculator.restate(originalEir, reSolved, gcaBefore, revised, MONTHLY);
        assertThat(breach(misrouted, InvariantId.CU_1))
            .as("CU-1 must fail where the pipeline persisted a re-solved rate")
            .isNotNull();
    }

    /**
     * The present value of the revised flows, summed here rather than delegated.
     *
     * <p>Calling {@link Discounting#presentValueMoney} would compare the engine
     * against itself. This walks the same flows in the same order with
     * {@link Precision#discountFactor}, which is the definition the specification
     * states at 4.1, so agreement means the restatement discounted the vector it was
     * given at the rate it was given.
     */
    private static Money presentValue(Rate rate, FlowVector vector) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow flow : vector.future()) {
            BigDecimal tau = MONTHLY.tau(vector.anchorDate(), flow);
            total = total.add(
                flow.amount().amount().multiply(
                    Precision.discountFactor(rate.periodic(), tau), Precision.WORKING),
                Precision.WORKING);
        }
        return Money.of(total, vector.currency());
    }

    /** A revised expected schedule anchored on the event date, with nothing on the anchor. */
    private static FlowVector revisedFlows(int periods, BigDecimal instalment, BigDecimal balloon) {
        List<CashFlow> flows = new ArrayList<>();
        for (int period = 1; period <= periods; period++) {
            flows.add(CashFlow.of(EVENT_DATE.plusMonths(period), period,
                Money.of(instalment, Money.INR), FlowKind.COMBINED_EMI));
        }
        if (balloon.signum() > 0) {
            flows.add(CashFlow.of(EVENT_DATE.plusMonths(periods), periods,
                Money.of(balloon, Money.INR), FlowKind.BALLOON));
        }
        return FlowVector.of(EVENT_DATE, Money.INR, flows);
    }

    private static InvariantResult breach(CatchUpResult result, InvariantId id) {
        for (InvariantResult candidate : result.breaches()) {
            if (candidate.id() == id) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Invariant S3-1 over generated balances: a period assembled consistently reconciles on all
     * four legs, exactly.
     *
     * <p><b>Why this property is worth its tries</b>, given that reference case 5 already asserts
     * the four-way on one balance at one allowance ratio. What that case cannot answer is whether
     * the legs hold as arithmetic or as an artefact of its magnitudes — and legs 1 and 4 are the
     * two that could plausibly be magnitude-sensitive, because they are the only ones that combine
     * a balance with a cash amount. A balance near ten billion and a cash receipt of a rupee is
     * the shape that would expose a leg computed at presentation scale.
     *
     * <p>The cash is generated as a fraction of the billed interest and applied to the interest
     * leg, matched by an equal recovery out of suspense. That matching is the ordinary case rather
     * than a contrivance: a Stage 3 borrower who pays something has paid interest that was
     * suspended, and leg 4 is precisely the assertion that the two records of that payment agree.
     */
    @Property(tries = 10000, seed = "20270901")
    void theFourWayReconciliationHoldsForAnyBalanceAndAnyPartPayment(
            @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal grossAmount,
            @ForAll @BigRange(min = "0", max = "1") @Scale(6) BigDecimal allowanceFraction,
            @ForAll @BigRange(min = "0.000000000001", max = "0.05") @Scale(12) BigDecimal rate,
            @ForAll @BigRange(min = "0", max = "1") @Scale(4) BigDecimal recoveredFraction) {
        Money gross = Money.inr(grossAmount.toPlainString());
        Money allowance = gross.times(allowanceFraction);
        Rate eir = Rate.monthly(rate);
        // Billed at the same rate on the same balance: the contractual and effective figures
        // differ in a real contract by the fee amortisation slice, and the legs are indifferent to
        // that difference, so generating one rate keeps the tries on what the legs turn on.
        Money billed = gross.times(rate);
        Money recovered = billed.times(recoveredFraction);

        Stage3Decomposition period = Stage3Decomposition.forPeriod(
            gross, allowance, eir, billed, Stage.STAGE_3);
        SuspenseLedger suspense = SuspenseLedger.forPeriod(
            Money.zero(Money.INR), billed, recovered, Money.zero(Money.INR));
        Money closing = gross.plus(period.grossBasisInterest()).minus(recovered);

        Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
            gross, closing, Money.zero(Money.INR), recovered, billed, period, suspense);

        assertThat(reconciliation.reconciles())
            .as("S3-1 on gross %s, allowance %s, rate %s, recovered %s: %s",
                gross, allowance, rate, recovered, reconciliation.fourWay().detail())
            .isTrue();
        assertThat(reconciliation.fourWay().id()).isEqualTo(InvariantId.S3_1);
        for (Money residual : reconciliation.residualsByLeg()) {
            assertThat(residual.signum())
                .as("exactly nil, not inside a tolerance")
                .isZero();
        }
    }

    /**
     * A single-leg break is detected at its own size, whatever the balance it sits on.
     *
     * <p>The property a reconciliation control actually has to have. Holding on the reconciled
     * case is the cheap half — a control that returned {@code pass} unconditionally would pass the
     * property above. This one perturbs the closing balance by a generated amount and requires the
     * deviation to come back as exactly that amount, which fails for any implementation that
     * rounds the legs, computes at presentation scale, or reports a count instead of a size.
     */
    @Property(tries = 10000, seed = "20270902")
    void aBrokenLegIsReportedAtItsOwnSize(
            @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal grossAmount,
            @ForAll @BigRange(min = "0.000000000001", max = "0.05") @Scale(12) BigDecimal rate,
            @ForAll @BigRange(min = "0.01", max = "1000000") @Scale(2) BigDecimal breakSize) {
        Money gross = Money.inr(grossAmount.toPlainString());
        Money billed = gross.times(rate);
        Money perturbation = Money.inr(breakSize.toPlainString());
        Rate eir = Rate.monthly(rate);

        Stage3Decomposition period = Stage3Decomposition.forPeriod(
            gross, gross.times(new BigDecimal("0.40")), eir, billed, Stage.STAGE_3);
        Money closing = gross.plus(period.grossBasisInterest()).minus(perturbation);

        Stage3Reconciliation reconciliation = Stage3Reconciliation.over(
            gross, closing, Money.zero(Money.INR), Money.zero(Money.INR), billed, period,
            SuspenseLedger.forPeriod(Money.zero(Money.INR), billed,
                Money.zero(Money.INR), Money.zero(Money.INR)));

        assertThat(reconciliation.reconciles()).isFalse();
        assertThat(reconciliation.fourWay().deviation())
            .as("a break of %s on a balance of %s", perturbation, gross)
            .isEqualByComparingTo(breakSize);
        assertThat(reconciliation.residualOn(0)).isEqualTo(perturbation.negate());
    }

    /**
     * Invariant PF-1 over generated pairs: the reported provision is the greater of the two
     * figures, and the pre-floor figure comes back untouched.
     *
     * <p>Generated independently rather than as a floor derived from the accounting figure,
     * because the pair a bank actually holds is two numbers from two engines and the interesting
     * cases are at the crossover — a floor a paise above and a paise below. Independent
     * generation puts tries on both sides of it and on the equality, which a derived floor would
     * never reach.
     */
    @Property(tries = 10000, seed = "20270903")
    void theFloorRaisesAndTheAccountingFigureSurvives(
            @ForAll @BigRange(min = "0", max = "10000000000") @Scale(2) BigDecimal accountingEcl,
            @ForAll @BigRange(min = "0", max = "10000000000") @Scale(2) BigDecimal floor) {
        Money accounting = Money.inr(accountingEcl.toPlainString());
        Money regulatory = Money.inr(floor.toPlainString());

        FloorApplication applied = FloorApplication.apply(
            accounting, regulatory, FloorBasis.ACCOUNT, Stage.STAGE_3);

        assertThat(applied.accountingEcl())
            .as("FR-609: the EIR-derived figure is never overwritten")
            .isEqualTo(accounting);
        assertThat(applied.reportedProvision().compareTo(accounting) >= 0)
            .as("a floor raises or does nothing; %s against %s", regulatory, accounting)
            .isTrue();
        assertThat(applied.reportedProvision().compareTo(regulatory) >= 0).isTrue();
        assertThat(applied.reportedProvision())
            .as("and it is one of the two, not something between them")
            .isIn(accounting, regulatory);
        assertThat(applied.flooredBy().isNegative())
            .as("the divergence is nil or positive, never a negative shortfall")
            .isFalse();
        assertThat(applied.invariants().getFirst().satisfied()).isTrue();
        assertThat(applied.invariants().getFirst().id()).isEqualTo(InvariantId.PF_1);
    }

    /**
     * Invariant PF-2 over every stage and basis: the pooled basis is refused in Stage 3 and
     * permitted everywhere else, and the account basis is permitted everywhere.
     *
     * <p>Exhaustive over the cross product rather than generated, since it is six cases, and
     * asserted as a cross product rather than on the two interesting ones so that adding a stage
     * or a basis fails here instead of acquiring a permission nobody decided on.
     */
    @Property(tries = 100, seed = "20270904")
    void theBasisRuleIsExhaustiveOverStagesAndBases(
            @ForAll @BigRange(min = "0.01", max = "10000000000") @Scale(2) BigDecimal provision) {
        Money reported = Money.inr(provision.toPlainString());
        for (Stage stage : Stage.values()) {
            for (FloorBasis basis : FloorBasis.values()) {
                boolean permitted = basis == FloorBasis.ACCOUNT || stage != Stage.STAGE_3;
                InvariantResult result =
                    FloorApplication.basisPermitted(basis, stage, reported);
                assertThat(result.id()).isEqualTo(InvariantId.PF_2);
                assertThat(result.satisfied())
                    .as("%s on a %s basis", stage, basis)
                    .isEqualTo(permitted);
                if (!permitted) {
                    assertThat(result.deviation())
                        .as("the provision on the wrong basis, which is what has to be restated")
                        .isEqualByComparingTo(provision);
                }
            }
        }
    }
}
