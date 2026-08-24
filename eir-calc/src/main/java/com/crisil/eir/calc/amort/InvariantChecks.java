package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The amortisation invariants, in one place, as results rather than throws
 * (calculation specification section 9).
 *
 * <p>These are asserted in production, not only in tests: a breach raises a
 * control exception and blocks the period close. That is only workable if a
 * ten-million-contract run can collect breaches per contract and carry on
 * (FR-905), so every check returns an {@link InvariantResult} and the decision to
 * fail loudly belongs to the caller — {@link #requireAllSatisfied} where the
 * caller wants it, {@link #breaches} where it wants a report.
 *
 * <p>Two of these have no IFRS 9 analogue and are the ones an Indian auditor will
 * look at hardest: ST-2 and the Stage 3 recognition checks
 * ({@link Stage3Decomposition}), and the penal-charge exclusion, which is asserted
 * at the ingestion boundary rather than here.
 */
public final class InvariantChecks {

    /**
     * The effective-annual spread inside which INV-2's ordering is not resolvable.
     *
     * <p>Published rather than private because it is a policy figure, not an
     * implementation detail: it decides when a fee is too small for its sign to be
     * checkable. Derived in {@link #feeSignOrdering} — 0.0001% a year, sitting nineteen
     * times above the largest paise-rounding artefact measured on a zero-fee EMI loan
     * and five thousand times below the smallest spread the check has to catch.
     */
    public static final BigDecimal ORDERING_EPSILON = new BigDecimal("0.000001");

    private InvariantChecks() {
    }

    /**
     * Invariant IC-1: the initial gross carrying amount is the net cash flow at
     * inception.
     *
     * <p>Where this fails, a fee has been misclassified or a non-cash item has
     * entered the vector. For reference case 1 the figure is 995,000.00 — neither
     * the 1,000,000.00 advanced nor the 985,000.00 the borrower received.
     */
    public static InvariantResult initialCarryingAmount(Money openingGca, Money netCashAtInception) {
        return InvariantResult.ofMoney(
            InvariantId.IC_1, "initial gross carrying amount", netCashAtInception.negate(), openingGca);
    }

    /**
     * Invariant TR-1: the terminal EIR-leg carrying amount is zero on a full-term,
     * event-free contract.
     *
     * <p>Zero because the rate was solved against the same flow vector the ledger
     * consumed, so the roll-forward is that discount run backwards. A non-zero
     * terminal balance is therefore never an arithmetic slip — it means the solve
     * and the roll-forward disagreed about the flows.
     *
     * <p>Asserted at presentation scale. At working precision a residue of the
     * order of a millionth of a rupee survives on a 24-period retail loan, because
     * the specification requires the roll-forward to use the rate as
     * <em>persisted</em> at 12 decimal places (section 1.4) rather than as solved
     * at 28. That residue is the price of a published amortisation that is
     * reproducible from the published rate, and it is invisible at any scale that
     * gets reported. A residue that reaches presentation scale is a defect.
     */
    public static InvariantResult terminalEirLegZero(Money terminalBalance) {
        return InvariantResult.ofMoney(
            InvariantId.TR_1,
            "terminal EIR-leg carrying amount",
            Money.zero(terminalBalance.currency()),
            terminalBalance);
    }

    /**
     * Invariant INV-1: lifetime EIR interest equals lifetime contractual interest
     * plus the net integral fee, net of catch-ups.
     *
     * <p>The EIR method changes the <em>timing</em> of recognition and never the
     * total. Reference case 1: 134,763.28 of EIR interest against 129,763.28 of
     * contractual interest and 5,000.00 of net fee.
     *
     * <p>Catch-ups carry a minus sign on the contractual side. A B5.4.6
     * restatement moves the carrying amount without any cash moving, so the
     * interest the ledger subsequently accretes is smaller by exactly the
     * restatement — a 627.42 charge today is 627.42 of interest that will be
     * recognised later. Total recognised income over the life is unchanged, which
     * is what this invariant says.
     *
     * @param totalEirInterest          lifetime EIR interest recognised
     * @param totalContractualInterest  lifetime contractual interest on the billed
     *                                  flows: cash received less principal advanced
     * @param netIntegralFee            fees received less integral costs paid;
     *                                  positive is income
     * @param totalCatchUps             sum of catch-up adjustments recognised
     */
    public static InvariantResult lifetimeInterest(
        Money totalEirInterest,
        Money totalContractualInterest,
        Money netIntegralFee,
        Money totalCatchUps) {
        Money expected = totalContractualInterest.plus(netIntegralFee).minus(totalCatchUps);
        return InvariantResult.ofMoney(
            InvariantId.INV_1,
            "lifetime EIR interest = contractual interest " + totalContractualInterest.atPresentationScale()
                + " + net fee " + netIntegralFee.atPresentationScale()
                + " - catch-ups " + totalCatchUps.atPresentationScale(),
            expected,
            totalEirInterest);
    }

    /**
     * Invariant INV-2: the EIR exceeds the contractual rate exactly when the net
     * integral fee is income, falls short when it is a cost, and equals it when
     * there is none.
     *
     * <p>A one-line sanity check on the whole measurement. A net fee received
     * records the asset below par, so it must accrete back up and the yield must
     * exceed the coupon — reference case 1, 13.248094% against 12.682503%, 56.6
     * basis points for 5,000 of net fee. The check catches a sign error in fee
     * classification, and a solver that converged on the wrong root, in one
     * comparison.
     *
     * <p>Compared on the effective annual form, so it holds across conventions:
     * a monthly periodic rate and an actual-date annual rate are not comparable as
     * stored, and the effective annual figure is the one that means the same thing
     * in both.
     *
     * <p><strong>Why "equals it when there is none" needs a band.</strong> Taken as
     * exact equality it is not an invariant at all: it fails on every zero-fee contract
     * in the book. A borrower is billed an instalment rounded to the paise, so a
     * zero-fee EMI loan does not reprice exactly at its coupon, and the residual spread
     * is both tiny and arbitrarily signed — measured on 1,000,000 at 1% a month, it is
     * -5.3e-8 over 24 months, +5.1e-8 over 60 and -2.1e-8 over 240. Nothing about the
     * contract changed; only the direction the last paise rounded. A control that fires
     * on a whole legitimate population is worse than no control, because it teaches a
     * reviewer to dismiss INV-2 breaches.
     *
     * <p>{@link #ORDERING_EPSILON} is the band inside which the ordering is not
     * resolvable, and it is chosen with five orders of magnitude of daylight on both
     * sides: nineteen times the largest rounding artefact measured above, and one part
     * in five thousand of the smallest spread the check has to catch — reference case
     * 1's 5.66e-3, being 13.248094% against 12.682503%, 56.6 basis points for 5,000 of
     * net fee. Inside the band the result passes and says the ordering was
     * indistinguishable, which is the honest report: an immaterial fee produces an
     * immaterial spread, and its sign carries no information either way.
     */
    public static InvariantResult feeSignOrdering(Rate eir, Rate contractualRate, Money netIntegralFee) {
        Objects.requireNonNull(eir, "eir");
        Objects.requireNonNull(contractualRate, "contractualRate");
        BigDecimal spread = eir.effectiveAnnual().subtract(contractualRate.effectiveAnnual());
        int expected = netIntegralFee.signum();
        String detail = "EIR " + eir.effectiveAnnual().toPlainString() + " vs contractual "
            + contractualRate.effectiveAnnual().toPlainString() + " against net fee "
            + netIntegralFee.atPresentationScale();
        if (spread.abs().compareTo(ORDERING_EPSILON) <= 0) {
            return InvariantResult.pass(InvariantId.INV_2, detail
                + " — spread " + spread.toPlainString() + " is inside the resolvable band "
                + ORDERING_EPSILON.toPlainString() + ", so the ordering carries no information "
                + "and neither does its sign");
        }
        if (spread.signum() == expected) {
            return InvariantResult.pass(InvariantId.INV_2, detail);
        }
        return InvariantResult.fail(InvariantId.INV_2, detail + " — ordering contradicts the fee sign", spread);
    }

    /**
     * Invariant INV-3: cash received reconciles to principal plus contractual
     * interest, on the flows actually billed.
     *
     * <p>Stated against the billed flows and therefore independent of the rounding
     * residue policy (section 5.7). Where a plug policy applies, the terminal
     * contractual balance is zero and this reduces to principal plus interest
     * equals cash. Where {@code LMS_AUTHORITATIVE} applies and the billed EMI is the
     * true annuity payment rounded down, the uncollected residue — 0.059969 over
     * reference case 1's 24 periods — stands as the terminal contractual balance and
     * this invariant accounts for it rather than absorbing it.
     *
     * @param totalCashReceived          cash on the billed flows
     * @param principalAdvanced          par amount advanced
     * @param totalContractualInterest   contractual interest accrued on the leg
     * @param terminalContractualBalance what the billed schedule left uncollected
     */
    public static InvariantResult billedCashReconciliation(
        Money totalCashReceived,
        Money principalAdvanced,
        Money totalContractualInterest,
        Money terminalContractualBalance) {
        Money expected =
            principalAdvanced.plus(totalContractualInterest).minus(terminalContractualBalance);
        return InvariantResult.ofMoney(
            InvariantId.INV_3,
            "cash received = principal " + principalAdvanced.atPresentationScale()
                + " + contractual interest " + totalContractualInterest.atPresentationScale()
                + " - uncollected residue " + terminalContractualBalance.atPresentationScale(),
            expected,
            totalCashReceived);
    }

    /**
     * Invariant INV-4: the unamortised fee balance is the difference between the
     * two legs.
     *
     * <p>The workhorse. The unamortised fee is not an accumulator that happens to
     * agree with the balances — it is <em>defined</em> as the contractual carrying
     * amount less the EIR carrying amount, so it cannot drift from them. An
     * accumulator can, and does: one rounding decision applied to the fee movement
     * but not to the balances, or one event that adjusts a balance without touching
     * the accumulator, and the two disagree permanently with no single diagnosable
     * cause.
     *
     * <p>{@link TwoLegRow} derives the balance rather than storing it, so this
     * check is a boundary assertion against a figure some other system carries —
     * a core banking unamortised-fee balance, a migrated opening position — rather
     * than a check on the engine's own arithmetic. Reference case 1 at month 12:
     * 529,815.61 less 528,407.32 is 1,408.29.
     */
    public static InvariantResult unamortisedFeeIsLegDifference(
        Money contractualCarryingAmount, Money eirCarryingAmount, Money reportedUnamortisedFee) {
        return InvariantResult.ofMoney(
            InvariantId.INV_4,
            "unamortised fee = contractual " + contractualCarryingAmount.atPresentationScale()
                + " - EIR " + eirCarryingAmount.atPresentationScale(),
            contractualCarryingAmount.minus(eirCarryingAmount),
            reportedUnamortisedFee);
    }

    /**
     * Invariant CU-1: the persisted EIR is bit-identical across a catch-up
     * restatement.
     *
     * <p>See {@link CatchUpCalculator} for why this is the cheapest detector of
     * discounting at a re-solved rate.
     */
    public static InvariantResult eirUnchangedAcrossCatchUp(Rate before, Rate after) {
        String detail = "EIR across the restatement: " + before.periodic().toPlainString()
            + " (x" + before.periodsPerYear() + ")";
        if (bitIdentical(before, after)) {
            return InvariantResult.pass(InvariantId.CU_1, detail);
        }
        return InvariantResult.fail(
            InvariantId.CU_1,
            detail + " became " + after.periodic().toPlainString() + " (x" + after.periodsPerYear()
                + ") — a re-solved rate drives the catch-up towards zero and converts a B5.4.6 event"
                + " into a B5.4.5 one",
            after.periodic().subtract(before.periodic()));
    }

    /**
     * Invariant POCI-1: the credit-adjusted EIR is retained after a cure, never
     * reset.
     *
     * <p>A cure improves the expected cash flows; it does not retrospectively make
     * a distressed purchase price a par acquisition. Following the 2019 IFRS
     * Interpretations Committee direction, and ACPIR 24 and 50, the rate stands and
     * the improvement is recognised as an impairment gain.
     */
    public static InvariantResult creditAdjustedRateRetained(Rate atInitialRecognition, Rate afterCure) {
        String detail = "credit-adjusted EIR across a cure: " + atInitialRecognition.periodic().toPlainString();
        if (bitIdentical(atInitialRecognition, afterCure)) {
            return InvariantResult.pass(InvariantId.POCI_1, detail);
        }
        return InvariantResult.fail(
            InvariantId.POCI_1,
            detail + " was reset to " + afterCure.periodic().toPlainString(),
            afterCure.periodic().subtract(atInitialRecognition.periodic()));
    }

    /** Invariant ST-2. Delegates to {@link Stage3Decomposition#stageTwoIdentity}. */
    public static InvariantResult stageThreeDecomposition(Money gross, Money net, Money unwind) {
        return Stage3Decomposition.stageTwoIdentity(gross, net, unwind);
    }

    /** Invariant S3-2: nil recognised income in Stage 3. */
    public static InvariantResult stageThreeNilRecognition(Stage stage, Money recognisedIncome) {
        if (!stage.suppressesIncomeRecognition()) {
            return InvariantResult.pass(
                InvariantId.S3_2, "not in Stage 3 (" + stage + "), recognition on the gross basis");
        }
        return Stage3Decomposition.nilRecognition(recognisedIncome);
    }

    /**
     * Bit-identity of two persisted rates.
     *
     * <p>{@link Rate#equals} compares numerically, which is right for a value type
     * and too permissive here. A {@link Rate} always holds its value rounded to
     * storage scale, so comparing the stored {@link BigDecimal} representations
     * exactly — scale included — is what "the persisted rate did not change" means.
     */
    public static boolean bitIdentical(Rate left, Rate right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.periodsPerYear() == right.periodsPerYear()
            && left.periodic().equals(right.periodic());
    }

    /** The failures in a result set. Empty where everything held. */
    public static List<InvariantResult> breaches(List<InvariantResult> results) {
        return results.stream().filter(result -> !result.satisfied()).toList();
    }

    public static boolean allSatisfied(List<InvariantResult> results) {
        return breaches(results).isEmpty();
    }

    /**
     * @throws InvariantBreachException on the first breach, in the order asserted
     */
    public static List<InvariantResult> requireAllSatisfied(List<InvariantResult> results) {
        for (InvariantResult result : results) {
            result.orThrow();
        }
        return results;
    }

    /** Concatenates result lists, for a caller assembling one report from several stages. */
    public static List<InvariantResult> merge(List<InvariantResult> first, List<InvariantResult> second) {
        List<InvariantResult> merged = new ArrayList<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}
