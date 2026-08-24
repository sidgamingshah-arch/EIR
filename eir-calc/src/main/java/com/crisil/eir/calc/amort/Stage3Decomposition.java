package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The India divergence: one interest figure, decomposed three ways, recognised as
 * nil (calculation specification section 7, reference case 5).
 *
 * <p>ACPIR does not recognise interest income on a Stage 3 asset at all. IFRS 9
 * recognises it on the net basis — the effective rate applied to gross less
 * allowance — and books the remainder inside impairment. The engine has to
 * produce both, because ECL under ACPIR 50 is a present-value measure discounted
 * at the EIR, so the discount unwinds mechanically every period whether or not
 * anything reaches the P&amp;L. What ACPIR does <em>not</em> say is where the
 * unwind goes; that is an open question the entity closes by Board-approved
 * policy, and this type computes the number rather than deciding its destination.
 *
 * <p><strong>Why there is no separate unwind model.</strong> Invariant ST-2:
 *
 * <pre>
 *   net-basis interest + ECL discount unwind = gross-basis interest
 * </pre>
 *
 * <p>The unwind is <em>precisely</em> the interest the gross basis would have
 * earned on the allowance portion of the balance. Both sides are the same rate on
 * complementary slices of the same balance, so the identity is not an
 * approximation that a tolerance has to absorb — it is arithmetic. That is what
 * makes the Indian treatment tractable: the engine computes one gross-basis
 * figure and splits it, instead of running an impairment-unwind model alongside
 * an interest model and reconciling two answers every month. On the reference case
 * 5 figures, {@code 3,304.08 + 2,202.72 = 5,506.79}.
 *
 * <p>To keep ST-2 an identity rather than a near-miss, the split is an
 * <em>exact</em> subtraction: the unwind is {@code allowance x EIR} at working
 * precision and the net-basis figure is the gross figure less the unwind,
 * unrounded. Computing {@code (gross - allowance) x EIR} independently is
 * mathematically the same and numerically not — at 28 significant digits two
 * balances of very different magnitude round differently, and the invariant would
 * then fail on inputs that are perfectly sound.
 *
 * <p><strong>Staging is not an EIR event.</strong> Nothing here returns a rate or
 * a carrying amount. A Stage 3 exposure keeps rolling forward on the gross basis
 * through {@link AmortisationEngine}, at the same EIR, with the same balance
 * (FR-610); this type answers only the recognition question. That is deliberate:
 * the type has no output channel through which a staged-down rate or a
 * net-of-allowance balance could leak into the ledger.
 *
 * @param grossBasisInterest gross carrying amount x EIR — the Stage 1/2 amount
 * @param netBasisInterest   amortised cost x EIR — what IFRS 9 would recognise
 * @param eclUnwind          allowance x EIR — mechanical, ACPIR 50; never P&amp;L
 * @param recognisedIncome   nil in Stage 3; the gross-basis figure otherwise
 * @param toSuspense         contractual interest billed but not recognised
 * @param stage              the stage the period was in
 * @param invariants         ST-2 always; S3-2 and S3-1 where recognition is suppressed
 */
public record Stage3Decomposition(
    Money grossBasisInterest,
    Money netBasisInterest,
    Money eclUnwind,
    Money recognisedIncome,
    Money toSuspense,
    Stage stage,
    BigDecimal accrualExponent,
    List<InvariantResult> invariants) {

    public Stage3Decomposition {
        Objects.requireNonNull(grossBasisInterest, "grossBasisInterest");
        Objects.requireNonNull(netBasisInterest, "netBasisInterest");
        Objects.requireNonNull(eclUnwind, "eclUnwind");
        Objects.requireNonNull(recognisedIncome, "recognisedIncome");
        Objects.requireNonNull(toSuspense, "toSuspense");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(accrualExponent, "accrualExponent");
        Objects.requireNonNull(invariants, "invariants");
        if (accrualExponent.signum() <= 0) {
            throw new IllegalArgumentException(
                "accrualExponent must be positive, got " + accrualExponent.toPlainString());
        }
        invariants = List.copyOf(invariants);
    }

    /**
     * Decomposes one period's interest for an exposure at the given stage.
     *
     * <p>The gross carrying amount and the EIR are inputs and stay inputs. Outside
     * Stage 3 the net-basis figure is retained as a comparative — it is what IFRS 9
     * would have recognised had the exposure been credit-impaired — and the
     * recognised figure is the gross-basis one, which is what ACPIR requires for
     * Stage 1 and Stage 2.
     *
     * @param grossCarryingAmount     opening gross carrying amount for the period
     * @param allowance               ECL allowance at the same date; nil is allowed
     * @param eir                     the contract's EIR, unchanged by staging
     * @param contractualInterestBilled contractual interest billed for the period,
     *                                  which is what the suspense ledger absorbs —
     *                                  not the EIR figure, because what is billed
     *                                  is what the borrower owes
     * @param stage                   the stage in force for the period
     */
    public static Stage3Decomposition forPeriod(
        Money grossCarryingAmount,
        Money allowance,
        Rate eir,
        Money contractualInterestBilled,
        Stage stage) {
        return forAccrualPeriod(
            grossCarryingAmount, allowance, eir, contractualInterestBilled, stage, BigDecimal.ONE);
    }

    /**
     * The general form: one accrual period of length {@code accrualExponent},
     * expressed in the rate's own periodicity.
     *
     * <p>The exponent exists because multiplying a balance by
     * {@link Rate#periodic()} flat is only correct for a <em>whole</em> compounding
     * period. Section 3.10 makes actual dating the default and the fallback, and
     * under actual dating the rate is annual while a monthly accrual has an
     * exponent near {@code 1/12}; a broken period has an exponent that is not a
     * whole number under any convention. A flat multiply in either case computes
     * the wrong gross-basis interest, the wrong ECL unwind and the wrong IFRS 9
     * comparative.
     *
     * <p>Nothing downstream would catch that. ST-2, S3-1 and S3-2 are algebraic
     * identities between figures this method derives from the same two inputs, so
     * they hold no matter how wrong the accrual factor is — they check the
     * <em>decomposition</em>, never the <em>magnitude</em>. Hence the exponent is a
     * required parameter of the general form rather than an optional refinement,
     * and hence {@link #accrualConsistency} cross-checks the result against the
     * roll-forward's own accretion rather than trusting it.
     *
     * @param accrualExponent the accrual length in the rate's periodicity —
     *     {@link BigDecimal#ONE} for a whole period, {@code delta tau} from the
     *     roll-forward otherwise. Must be positive.
     */
    public static Stage3Decomposition forAccrualPeriod(
        Money grossCarryingAmount,
        Money allowance,
        Rate eir,
        Money contractualInterestBilled,
        Stage stage,
        BigDecimal accrualExponent) {
        Objects.requireNonNull(grossCarryingAmount, "grossCarryingAmount");
        Objects.requireNonNull(allowance, "allowance");
        Objects.requireNonNull(eir, "eir");
        Objects.requireNonNull(contractualInterestBilled, "contractualInterestBilled");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(accrualExponent, "accrualExponent");
        if (accrualExponent.signum() <= 0) {
            throw new IllegalArgumentException(
                "accrual exponent must be positive, got " + accrualExponent.toPlainString());
        }
        if (!allowance.currency().equals(grossCarryingAmount.currency())
            || !contractualInterestBilled.currency().equals(grossCarryingAmount.currency())) {
            throw new IllegalArgumentException("gross carrying amount, allowance and billed interest must agree"
                + " in currency");
        }
        if (allowance.isNegative()) {
            throw new IllegalArgumentException("allowance must not be negative, got " + allowance);
        }
        if (allowance.compareTo(grossCarryingAmount) > 0) {
            throw new IllegalArgumentException(
                "allowance " + allowance + " exceeds the gross carrying amount " + grossCarryingAmount
                    + "; every control in this method is an algebraic identity between figures derived"
                    + " from the same two inputs, so an allowance above the balance — or the two"
                    + " arguments supplied the wrong way round — would yield a negative amortised-cost"
                    + " interest and an inflated shadow unwind with ST-2, S3-1 and S3-2 all reporting"
                    + " satisfied. The bound is checked here because no downstream control can catch it.");
        }

        // The same accretion the roll-forward uses, so the Stage 3 figures and the
        // gross-basis ledger cannot diverge. For a whole period this reduces to the
        // periodic rate exactly, on BigDecimal's exact integer power path.
        BigDecimal accretion = AmortisationEngine.accretion(eir.periodic(), accrualExponent);
        BigDecimal gross = grossCarryingAmount.amount().multiply(accretion, Precision.WORKING);
        BigDecimal unwind = allowance.amount().multiply(accretion, Precision.WORKING);
        BigDecimal net = gross.subtract(unwind);

        Money grossInterest = Money.of(gross, grossCarryingAmount.currency());
        Money unwindAmount = Money.of(unwind, grossCarryingAmount.currency());
        Money netInterest = Money.of(net, grossCarryingAmount.currency());
        boolean suppressed = stage.suppressesIncomeRecognition();
        Money recognised = suppressed ? Money.zero(grossCarryingAmount.currency()) : grossInterest;
        Money suspense = suppressed ? contractualInterestBilled : Money.zero(grossCarryingAmount.currency());

        List<InvariantResult> invariants = new ArrayList<>();
        invariants.add(stageTwoIdentity(grossInterest, netInterest, unwindAmount));
        if (suppressed) {
            invariants.add(nilRecognition(recognised));
            invariants.add(InvariantResult.ofMoney(
                InvariantId.S3_1,
                "Stage 3 billed interest splits into recognised income and suspense",
                contractualInterestBilled,
                recognised.plus(suspense)));
        }
        return new Stage3Decomposition(grossInterest, netInterest, unwindAmount, recognised,
            suspense, stage, accrualExponent, invariants);
    }

    /**
     * The first period after a cure. Recognition resumes on the gross basis
     * <em>prospectively</em>.
     *
     * <p>There is no catch-up for interest not recognised while the exposure was in
     * Stage 3, and that is not a simplification. Booking one would recognise income
     * that was correctly never recognised — the suppression was the right answer at
     * the time, so reversing it later would restate a period that was not wrong.
     * The EIR is unchanged throughout, because staging never was an EIR event
     * (section 7.4).
     *
     * <p>The recorded S3-1 result is the assertion that no catch-up crept in: the
     * cure period recognises exactly its own gross-basis interest and nothing more.
     *
     * @throws IllegalArgumentException if the cured stage is still Stage 3
     */
    public static Stage3Decomposition onCure(
        Money grossCarryingAmount,
        Money allowance,
        Rate eir,
        Money contractualInterestBilled,
        Stage curedStage) {
        Objects.requireNonNull(curedStage, "curedStage");
        if (curedStage.suppressesIncomeRecognition()) {
            throw new IllegalArgumentException("a cure cannot land in " + curedStage
                + " — recognition resumes only where it is not suppressed");
        }
        Stage3Decomposition cured =
            forPeriod(grossCarryingAmount, allowance, eir, contractualInterestBilled, curedStage);
        List<InvariantResult> invariants = new ArrayList<>(cured.invariants());
        invariants.add(InvariantResult.ofMoney(
            InvariantId.S3_1,
            "cure recognises the period's gross-basis interest only, with no catch-up for suppressed periods",
            cured.grossBasisInterest(),
            cured.recognisedIncome()));
        return new Stage3Decomposition(
            cured.grossBasisInterest(),
            cured.netBasisInterest(),
            cured.eclUnwind(),
            cured.recognisedIncome(),
            cured.toSuspense(),
            cured.stage(),
            cured.accrualExponent(),
            invariants);
    }

    /**
     * Invariant ST-2, asserted exactly rather than at presentation scale.
     *
     * <p>Presentation scale is the wrong place for this one. Round the three
     * figures independently and reference case 5 reads
     * {@code 3,304.08 + 2,202.72 = 5,506.80} against a gross figure of
     * {@code 5,506.79} — a paise of rounding, not a broken identity. The identity
     * holds unrounded, so it is asserted unrounded, and the presented components
     * are published as they round.
     */
    public static InvariantResult stageTwoIdentity(Money gross, Money net, Money unwind) {
        BigDecimal recomposed = net.amount().add(unwind.amount());
        BigDecimal deviation = recomposed.subtract(gross.amount());
        String detail = "net-basis " + net.atPresentationScale() + " + ECL unwind "
            + unwind.atPresentationScale() + " = gross-basis " + gross.atPresentationScale();
        if (deviation.signum() == 0) {
            return InvariantResult.pass(InvariantId.ST_2, detail);
        }
        return InvariantResult.fail(InvariantId.ST_2, detail + " — exact deviation "
            + deviation.toPlainString(), deviation);
    }

    /** Invariant S3-2: recognised interest income on a Stage 3 contract is nil. */
    public static InvariantResult nilRecognition(Money recognisedIncome) {
        return InvariantResult.ofMoney(
            InvariantId.S3_2,
            "Stage 3 recognised interest income",
            Money.zero(recognisedIncome.currency()),
            recognisedIncome);
    }

    /**
     * Asserts that a stage migration left the EIR and the gross carrying amount
     * alone (FR-610, section 7.4).
     *
     * <p>Kept as an explicit check for pipelines that route staging through a
     * general event handler, where a stage change can reach the same code path as
     * a reset and pick up a re-solve on the way past.
     */
    public static List<InvariantResult> stagingIsNotAnEirEvent(
        Rate eirBefore, Rate eirAfter, Money gcaBefore, Money gcaAfter) {
        List<InvariantResult> results = new ArrayList<>();
        String rateDetail = "EIR across a stage migration: " + eirBefore.periodic().toPlainString();
        if (InvariantChecks.bitIdentical(eirBefore, eirAfter)) {
            results.add(InvariantResult.pass(InvariantId.S3_1, rateDetail));
        } else {
            results.add(InvariantResult.fail(
                InvariantId.S3_1,
                rateDetail + " became " + eirAfter.periodic().toPlainString(),
                eirAfter.periodic().subtract(eirBefore.periodic())));
        }
        results.add(InvariantResult.ofMoney(
            InvariantId.S3_1, "gross carrying amount across a stage migration", gcaBefore, gcaAfter));
        return List.copyOf(results);
    }

    /** True where ACPIR suppresses recognition for the period. */
    public boolean incomeSuppressed() {
        return stage.suppressesIncomeRecognition();
    }

    /**
     * The unwind that ACPIR leaves homeless: retained for the ECL roll-forward,
     * never recognised in the P&amp;L (FR-603).
     */
    public Money shadowUnwind() {
        return eclUnwind;
    }

    /** What IFRS 9 would have recognised, retained for the parallel-basis disclosure. */
    public Money ifrs9RecognisedIncome() {
        return netBasisInterest;
    }

    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /**
     * @throws InvariantBreachException on the first breach
     */
    public Stage3Decomposition orThrow() {
        for (InvariantResult result : invariants) {
            result.orThrow();
        }
        return this;
    }

    /**
     * Cross-checks this decomposition against the amortisation row it decomposes.
     *
     * <p><strong>This replaces a check that could not fail.</strong> The previous
     * version recomputed the gross-basis interest as
     * {@code grossCarryingAmount.times(accretion(eir, accrualExponent))} and compared
     * it to the {@code grossInterest} the decomposition had computed as
     * {@code grossCarryingAmount.amount().multiply(accretion, WORKING)}. Those are the
     * same balance, the same accretion and the same multiplication under the same
     * {@link Precision#WORKING} context, so the comparison was bit-identical by
     * construction — and if the {@code accrualExponent} was wrong, both sides were
     * wrong by exactly the same factor and it still passed. It was a second tautology
     * added to fix the first, and it claimed in its own comment to be the assertion
     * that could detect a wrong accrual factor. It could not.
     *
     * <p>This one can, because the row is not derived from the same inputs.
     * {@link AmortisationEngine} computes a row's {@code accrualExponent} from the flow
     * vector's dates and the time convention; a caller of
     * {@link #forAccrualPeriod} supplies its exponent separately. Those are two
     * independent derivations of the same quantity, and a mismatch between them is
     * exactly the defect a broken first period or a mis-selected day count produces.
     * So the exponent is compared first — it is the cause — and the interest second,
     * which localises a failure rather than leaving one figure to explain.
     *
     * <p>Caller-invoked rather than folded into the decomposition, because the
     * decomposition does not have the row: it is handed a balance and a rate, and the
     * ledger is the pipeline's. That is the same reason it can be checked at all.
     *
     * @param row the EIR-leg row for the accrual period this decomposition covers
     */
    public List<InvariantResult> againstLedger(AmortisationRow row) {
        Objects.requireNonNull(row, "row");
        List<InvariantResult> results = new ArrayList<>();
        if (row.accrualExponent().compareTo(accrualExponent) != 0) {
            results.add(InvariantResult.fail(
                InvariantId.ST_2,
                "the decomposition accrued over " + accrualExponent.toPlainString()
                    + " period(s) but the ledger row accrued over "
                    + row.accrualExponent().toPlainString()
                    + ". The two derive the accrual length independently — the ledger from the"
                    + " vector's dates and the convention, the decomposition from its caller — so"
                    + " a disagreement is a broken period or a mis-selected day count, not a"
                    + " rounding matter",
                row.accrualExponent().subtract(accrualExponent)));
        } else {
            results.add(InvariantResult.pass(
                InvariantId.ST_2,
                "the decomposition and the ledger row agree on an accrual length of "
                    + accrualExponent.toPlainString() + " period(s)"));
        }
        results.add(InvariantResult.ofMoney(
            InvariantId.ST_2,
            "Stage 3 gross-basis interest against the ledger's own accretion for the period",
            row.eirInterest(),
            grossBasisInterest));
        return results;
    }
}
