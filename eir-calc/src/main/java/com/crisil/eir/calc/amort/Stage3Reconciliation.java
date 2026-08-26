package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Invariant S3-1: the four quantities 03 § 7.3 requires the engine to maintain, reconciled every
 * period (FR-605).
 *
 * <p><b>What was here before, and why it was not this.</b> {@code InvariantId.S3_1} states "Stage
 * 3 four-way reconciliation" and was published from four places, none of which was one:
 *
 * <ul>
 *   <li>a claim that billed interest splits into recognised income and suspense — asserted three
 *       lines below the code that constructs the split, so it could not fail;</li>
 *   <li>the cure no-catch-up assertion, which is FR-607 and now carries
 *       {@link InvariantId#CR_1};</li>
 *   <li>the EIR being unchanged across a stage migration, which is FR-610 and now carries
 *       {@link InvariantId#SG_1} — with a <em>rate</em> deviation, under an id whose other
 *       results carried money;</li>
 *   <li>the gross carrying amount being unchanged across the same migration, now
 *       {@link InvariantId#SG_2}.</li>
 * </ul>
 *
 * <p>Each of those is a real control and each was correctly computed. What was missing is the one
 * the id names, and it was missing for a structural reason worth stating: the four quantities do
 * not live in one place. {@link Stage3Decomposition} can see the EIR accrual and the recognised
 * amount, and it cannot see the carrying-amount ledger or the suspense balance — so a
 * reconciliation of all four was not something it could assert, and what it asserted instead were
 * the claims it could. This type is the level at which all four are in view.
 *
 * <p><b>The four legs.</b> Every input comes from a different source, which is what makes this a
 * reconciliation rather than an identity restated:
 *
 * <ol>
 *   <li><b>Carrying-amount roll-forward.</b> {@code closingGross = openingGross + EIR accrual −
 *       cash applied}. Ties the ledger's own balances to the accrual the decomposition computed.
 *       Breaks if the roll-forward ran at a different rate, or cash was applied to the wrong
 *       leg.</li>
 *   <li><b>Suspense ledger movement.</b> The ledger's charge for the period equals the
 *       contractual interest billed while recognition is suppressed, and nil otherwise. Ties the
 *       suspense ledger to the billing system.</li>
 *   <li><b>Recognition.</b> Recognised income is nil while suppressed, and the whole gross-basis
 *       accrual otherwise. Ties the P&amp;L to the stage.</li>
 *   <li><b>Cash against suspense.</b> Cash applied to interest equals what came out of suspense
 *       as a recovery. This is the leg that catches double counting: interest cannot be both
 *       sitting in suspense and received.</li>
 * </ol>
 *
 * <p><b>One result, not four.</b> The four legs are published as a single
 * {@link InvariantId#S3_1} result whose deviation is the total absolute residual and whose detail
 * names every failing leg with its own residual. Four separate results under one id would lose
 * three of them: {@link InvariantResult#conjunction} keeps only the first breach's deviation
 * among results sharing an id. A reconciliation that reports one of its four breaks is worse than
 * one that reports none, because it looks like it has been read.
 *
 * @param fourWay        the S3-1 result; always present, always {@code S3_1}
 * @param residualsByLeg each leg's signed residual, in the order above, for a workpaper
 */
public record Stage3Reconciliation(
    Money openingGross,
    Money closingGross,
    Money cashAppliedToPrincipal,
    Money cashAppliedToInterest,
    Money contractualInterestBilled,
    Stage3Decomposition period,
    SuspenseLedger suspense,
    InvariantResult fourWay,
    List<Money> residualsByLeg) {

    /** The leg names, in the order {@link #residualsByLeg} reports them. */
    public static final List<String> LEGS = List.of(
        "carrying-amount roll-forward",
        "suspense ledger movement against billed interest",
        "recognised income against the stage",
        "cash applied to interest against suspense recovered");

    public Stage3Reconciliation {
        Objects.requireNonNull(openingGross, "openingGross");
        Objects.requireNonNull(closingGross, "closingGross");
        Objects.requireNonNull(cashAppliedToPrincipal, "cashAppliedToPrincipal");
        Objects.requireNonNull(cashAppliedToInterest, "cashAppliedToInterest");
        Objects.requireNonNull(contractualInterestBilled, "contractualInterestBilled");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(suspense, "suspense");
        Objects.requireNonNull(fourWay, "fourWay");
        residualsByLeg = List.copyOf(Objects.requireNonNull(residualsByLeg, "residualsByLeg"));
        if (fourWay.id() != InvariantId.S3_1) {
            // A gate defect. The whole point of this type is that S3-1 has exactly one publication
            // site, and a result carrying some other id here would mean it has two.
            throw new IllegalStateException(
                "the four-way reconciliation must publish S3_1, not " + fourWay.id());
        }
        if (residualsByLeg.size() != LEGS.size()) {
            throw new IllegalStateException(
                "expected one residual per leg (" + LEGS.size() + "), got "
                    + residualsByLeg.size());
        }
    }

    /**
     * Reconcile a period.
     *
     * <p>{@code contractualInterestBilled} is passed in rather than read off the decomposition,
     * and that is not redundancy. {@link Stage3Decomposition} keeps the billed amount only while
     * recognition is suppressed — outside Stage 3 its {@code toSuspense} is zero and the billed
     * figure is discarded — so leg 2 could not be checked in the periods where it is checkable
     * against nil. Taking it from the billing source also keeps the leg a reconciliation between
     * two systems rather than a comparison of one field with itself.
     *
     * @param openingGross           gross carrying amount brought forward, from the ledger
     * @param closingGross           gross carrying amount carried out, from the ledger
     * @param cashAppliedToPrincipal cash received and applied to principal this period
     * @param cashAppliedToInterest  cash received and applied to interest this period
     * @param contractualInterestBilled contractual interest billed this period, from billing
     * @param period                 the period's decomposition
     * @param suspense               the period's suspense ledger movement
     */
    public static Stage3Reconciliation over(
        Money openingGross,
        Money closingGross,
        Money cashAppliedToPrincipal,
        Money cashAppliedToInterest,
        Money contractualInterestBilled,
        Stage3Decomposition period,
        SuspenseLedger suspense) {
        Objects.requireNonNull(openingGross, "openingGross");
        Objects.requireNonNull(closingGross, "closingGross");
        Objects.requireNonNull(cashAppliedToPrincipal, "cashAppliedToPrincipal");
        Objects.requireNonNull(cashAppliedToInterest, "cashAppliedToInterest");
        Objects.requireNonNull(contractualInterestBilled, "contractualInterestBilled");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(suspense, "suspense");

        Money cash = cashAppliedToPrincipal.plus(cashAppliedToInterest);

        // Leg 1. The accrual drives the balance; the cash reduces it. Compared unrounded, for the
        // reason ST-2 is: rounding three figures independently manufactures a paise of deviation
        // that is not a break, and a control with a tolerance wide enough to absorb it is wide
        // enough to absorb a real one.
        Money expectedClose = openingGross.plus(period.grossBasisInterest()).minus(cash);
        Money leg1 = closingGross.minus(expectedClose);

        // Leg 2. While suppressed everything billed goes to suspense; otherwise nothing does,
        // because it was recognised. Note this is a statement about the LEDGER's charge, taken
        // from the suspense ledger, against the BILLED amount, taken from billing.
        Money expectedCharge = period.incomeSuppressed()
            ? contractualInterestBilled
            : Money.zero(contractualInterestBilled.currency());
        Money leg2 = suspense.chargedToSuspense().minus(expectedCharge);

        // Leg 3. Nil in Stage 3 (S3-2 states this on its own; here it is the leg that ties the
        // P&L figure to the stage the ledger says the contract was in).
        Money expectedRecognised = period.incomeSuppressed()
            ? Money.zero(contractualInterestBilled.currency())
            : period.grossBasisInterest();
        Money leg3 = period.recognisedIncome().minus(expectedRecognised);

        // Leg 4. The double-count leg. Interest cannot be sitting in suspense and received at the
        // same time: cash that reaches the interest leg must be matched by a recovery out of
        // suspense. A period that applies cash to interest while suspending it has counted the
        // same rupee twice, and neither of the other three legs would notice.
        Money leg4 = cashAppliedToInterest.minus(suspense.recovered());

        List<Money> residuals = List.of(leg1, leg2, leg3, leg4);
        return new Stage3Reconciliation(
            openingGross, closingGross, cashAppliedToPrincipal, cashAppliedToInterest,
            contractualInterestBilled, period, suspense, assemble(residuals), residuals);
    }

    /**
     * The single S3-1 result: pass when every leg is exactly nil, otherwise a fail naming each
     * failing leg with its residual.
     *
     * <p>The deviation is the sum of the <em>absolute</em> residuals. Summing signed residuals
     * would let two breaks in opposite directions net to nil and report a reconciled period — the
     * classic way a reconciliation control passes while being broken twice.
     */
    private static InvariantResult assemble(List<Money> residuals) {
        List<String> breaks = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int leg = 0; leg < residuals.size(); leg++) {
            Money residual = residuals.get(leg);
            if (residual.signum() != 0) {
                breaks.add(LEGS.get(leg) + " out by " + residual.atPresentationScale());
                total = total.add(residual.amount().abs());
            }
        }
        if (breaks.isEmpty()) {
            return InvariantResult.pass(InvariantId.S3_1,
                "all four legs reconcile: " + String.join("; ", LEGS));
        }
        return InvariantResult.fail(InvariantId.S3_1,
            breaks.size() + " of 4 legs broken — " + String.join("; ", breaks), total);
    }

    /** Whether every leg reconciled. */
    public boolean reconciles() {
        return fourWay.satisfied();
    }

    /** The residual on one leg, by its index in {@link #LEGS}. */
    public Money residualOn(int leg) {
        return residualsByLeg.get(leg);
    }

    /**
     * Every invariant the period carries: the decomposition's own (ST-2, S3-2 where recognition
     * is suppressed) followed by this reconciliation's S3-1.
     *
     * <p>Ordered decomposition-first because the decomposition's results are the narrower claims,
     * and a reader diagnosing a broken S3-1 wants to know whether ST-2 held before they start on
     * the ledger: an ST-2 break explains an S3-1 break and the converse is not true.
     */
    public List<InvariantResult> invariants() {
        List<InvariantResult> all = new ArrayList<>(period.invariants());
        all.add(fourWay);
        return List.copyOf(all);
    }
}
