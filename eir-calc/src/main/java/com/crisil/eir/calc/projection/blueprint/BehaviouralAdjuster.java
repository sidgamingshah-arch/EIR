package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stage five of the blueprint pipeline
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 4</a>):
 * the contractual instalment ladder in, the <b>expected</b> ladder out, with the
 * behavioural assumption that produced it recorded alongside.
 *
 * <p>This class also owns <b>the par test</b> (09 § 3.4), which is the reason it
 * matters more than its arithmetic suggests. The arithmetic is a prepayment
 * roll-forward; the par test decides how much of the book the arithmetic is ever
 * run against, and getting that wrong is what makes a close window
 * unsurvivable. See {@link #reestimationIsAPnlEvent} below.
 *
 * <h2>The model</h2>
 *
 * <p><b>The instalment is fixed; the balance runs down faster.</b> A borrower who
 * prepays does not get a smaller EMI — the contract fixes the bill, and the effect
 * of the prepayment is that fewer bills are needed. So each period bills the
 * <em>contractual</em> instalment, interest accrues on whatever balance is actually
 * outstanding, scheduled principal is the residue of the two, and the prepayment
 * then takes its fraction of the post-scheduled balance. The ladder ends at the
 * period where the balance reaches zero, and that period's rung is a stub: the
 * balance plus its final period's interest, not a full instalment.
 *
 * <p>The alternative convention — recasting the instalment each period over the
 * remaining original term — is the one used to publish pool factors, and it is
 * wrong here. Under recasting the balance is {@code scheduled_factor(t) x
 * (1-SMM)^t} of the original, which reaches zero only at contractual maturity: a
 * 240-month mortgage at 25% CPR would report an expected life of 240 months.
 * Fixture O6 reports 64, and it reports it because the instalment is fixed.
 *
 * <p><b>CPR to single-period mortality.</b> An annual conditional prepayment rate
 * is a survival rate, so it converts by taking the periodic root and not by
 * dividing:
 *
 * <pre>
 *   SMM = 1 - (1 - CPR) ^ (1 / periodsPerYear)
 * </pre>
 *
 * <p>Dividing by twelve instead would overstate monthly prepayment on a 25% CPR by
 * 5.6% relative and compound that error over every period of the ladder. The power
 * goes through {@link Precision#onePlusPow(BigDecimal, BigDecimal)} with a
 * fractional exponent, which is the only route available: {@code Math.pow} is
 * banned outright by the numeric policy (ADR-0002) and would in any case take the
 * computation out of {@link Precision#WORKING} into binary floating point, where a
 * survival rate raised to a twelfth is exactly the kind of quantity that stops
 * reproducing between JVMs.
 *
 * <p><b>Frequency, not months.</b> The market name is SMM — <em>single monthly</em>
 * mortality — and the market means it, because the market's collateral is monthly.
 * The conversion here takes the root at the ladder's own frequency, because
 * applying a monthly mortality to a quarterly ladder prepays a third of what the
 * assumption says. At twelve periods a year the two are the same number and it is
 * the market's SMM exactly.
 *
 * <h2>Fixture O6</h2>
 *
 * <p>Mortgage of 5,000,000 at 9% p.a. (0.75% a month), 240 months, net integral fee
 * 50,000 received, contractual EMI 44,986.30:
 *
 * <table border="1">
 *   <caption>O6 — one mortgage, four prepayment speeds</caption>
 *   <tr><th>CPR</th><th>SMM</th><th>Expected life</th><th>Principal-WAL</th>
 *       <th>EIR p.a.</th></tr>
 *   <tr><td>0%</td><td>0.00000000%</td><td>240 months</td><td>12.88y</td>
 *       <td>9.533867%</td></tr>
 *   <tr><td>8%</td><td>0.69243826%</td><td>116 months</td><td>4.90y</td>
 *       <td>9.674777%</td></tr>
 *   <tr><td>15%</td><td>1.34519470%</td><td>86 months</td><td>3.30y</td>
 *       <td>9.785190%</td></tr>
 *   <tr><td>25%</td><td>2.36884242%</td><td>64 months</td><td>2.24y</td>
 *       <td>9.945464%</td></tr>
 * </table>
 *
 * <p>The EIR rises with speed because the 50,000 fee is <em>income</em> and a
 * shorter life amortises it faster — INV-2's ordering, holding across all four
 * rows. Had the 50,000 been a cost paid, every one of those four rates would sit
 * <em>below</em> the 9.380690% contractual effective rate and the ordering would
 * reverse, which is why no prepayment policy can be assumed conservative either.
 *
 * <h2>The par test — 09 § 3.4, invariant ST-9</h2>
 *
 * <p>A securitisation note, pool coupon 10% nominal monthly, 60-month level payment
 * of 21,247.04 fixed at origination, CPR assumption revised from 10% to 20% at
 * month 24. The pool balance at month 24 is 485,832.21 in all three cases; only the
 * purchase price differs:
 *
 * <table border="1">
 *   <caption>O7 — one revision, three purchase prices</caption>
 *   <tr><th>Bought at</th><th>EIR p.a.</th><th>GCA at m24</th>
 *       <th>Unamortised at m24</th><th>Catch-up</th></tr>
 *   <tr><td>1,030,000 (premium)</td><td>8.642478%</td><td>493,520.53</td>
 *       <td>7,688.32</td><td><b>-876.39</b> loss</td></tr>
 *   <tr><td>970,000 (discount)</td><td>12.411347%</td><td>477,984.62</td>
 *       <td>-7,847.59</td><td><b>+878.89</b> gain</td></tr>
 *   <tr><td>1,000,000 (<b>par</b>)</td><td>10.471307%</td><td>485,832.22</td>
 *       <td>0.0089</td><td><b>-0.01</b></td></tr>
 * </table>
 *
 * <p><b>The par row is nil, and it is not zero exactly.</b> This table said 0.00 and
 * said so emphatically, and it was reproducing docs/09's figures rather than this
 * class's: those were computed on an unrounded schedule, and a pool bills cash. The
 * level payment is 21,247.04 and not the exact annuity 21,247.0447110715, every flow
 * is paid to the paisa, and on that basis the 47 billed flows price to 999,999.9973
 * rather than to par. The par note therefore solves to 0.008333333204 against a
 * contractual 0.008333333333, and 0.0089 of instalment-rounding residue presents as
 * "unamortised" at month 24.
 *
 * <p>Which is why {@link #reestimationIsAPnlEvent} takes a measured band rather than
 * comparing at presentation scale: half a paisa is the wrong threshold for every tenor
 * but the shortest, and it made this note — bought <em>at par</em> — read as held away
 * from par and post a catch-up of -0.01, with {@link #catchUpIsNilAtPar} passing
 * vacuously through its away-from-par branch. The economics below are untouched. At par
 * the EIR solves to the contractual rate itself, and every expected ladder this class
 * produces satisfies
 * {@code B_t = B_(t-1) x (1 + r) - CF_t} by construction. Unrolling that recursion
 * gives {@code B_0 = sum CF_t / (1+r)^t} for any {@code CF} path whatever, which is
 * the statement that revised flows discount at the contractual rate to the
 * outstanding balance <b>regardless of the prepayment speed assumed</b>. There is
 * nothing for a change in speed to accelerate because there is nothing being
 * amortised: the balance is the balance.
 *
 * <p><b>Design consequence, and it is the whole point of this section.</b>
 * <b>Catch-up processing keys on the unamortised premium or discount balance, not
 * on an assumption having moved.</b> The two conditions look interchangeable and are
 * not: a curve refresh moves the assumption on every contract in a portfolio, while
 * only the away-from-par subset can produce a dime of P&amp;L. Keying on the
 * assumption churns the entire par-priced book on every refresh — at 10 million
 * contracts that is correctness-neutral and ruinous, and it also floods the movement
 * schedule with nil-effect restatements that a reviewer then has to read past to
 * find the ones that matter. The screen is {@link #reestimationIsAPnlEvent}, one
 * subtraction per contract, and it is the difference between re-solving ten million
 * rates and re-solving the few thousand that can move.
 *
 * <p>Note the same test read the other way, because it settles an argument that
 * otherwise runs and runs: <b>a floating-rate note bought at par raises no
 * reset-versus-catch-up question at all.</b> Whatever the benchmark does, the
 * revised flows discount to the balance and both treatments give the same number, so
 * the routing choice (ADR-0006) is unobservable. The same note bought in the
 * secondary market away from par raises the whole question — reference cases 3 and 4
 * are 627.42 of P&amp;L apart on one instrument in one month — and the reason is not
 * the benchmark. It is the premium.
 *
 * <h2>Discipline</h2>
 *
 * <p>Pure and clock-free like every other stage. Due dates are inputs or are derived
 * from the ladder's own dates and verified against them; nothing here reads a
 * calendar, a clock or a random source, and every amount is {@link BigDecimal} at
 * working precision until something else reduces it.
 */
public final class BehaviouralAdjuster {

    /**
     * Half of the currency's last published place — the most a billed instalment can
     * differ from the exact annuity. See {@link #roundingResidueBound}.
     */
    private static final BigDecimal HALF_A_MINOR_UNIT = new BigDecimal("0.005");

    /**
     * The compounding frequencies whose calendar step is unambiguous.
     *
     * <p>Ordered ascending so that a frequency mismatch is always reported against
     * the same candidate on repeated runs (invariant DT-1).
     */
    private static final int[] STEPPABLE_FREQUENCIES = {1, 2, 3, 4, 6, 12, 26, 52};

    private BehaviouralAdjuster() {
    }

    // ------------------------------------------------------------------ the stage

    /**
     * The expected ladder implied by an overlay.
     *
     * <p>Where the overlay alters nothing — {@code Contractual}, a nil CPR, a nil
     * CPR vector, a rollover assumption of none — the contractual ladder is returned
     * <em>as the same instance</em>. That is not an optimisation. Re-deriving an
     * unchanged ladder would recompute its interest from {@code contractualRate} and
     * could land a paisa away from the ladder the core banking system bills, giving a
     * two-leg reconciliation break on a contract carrying no assumption at all.
     *
     * @param contractual      the ladder from {@link ScheduleBuilder}
     * @param overlay          the behavioural assumption, from the blueprint
     * @param contractualRate  the periodic contractual rate the ladder was built at;
     *     its {@code periodsPerYear} is also the frequency the CPR root is taken at
     *     and the frequency any extension steps by
     * @throws IllegalArgumentException where the rate does not reproduce the ladder's
     *     own interest accrual, where its declared frequency is not the frequency the
     *     ladder's due dates step at, or where an extension needs due dates that cannot
     *     be derived from the ladder — see {@link #adjust(InstalmentLadder,
     *     BehaviouralOverlay, Rate, List)}
     */
    public static InstalmentLadder adjust(
        InstalmentLadder contractual, BehaviouralOverlay overlay, Rate contractualRate) {
        return adjust(contractual, overlay, contractualRate, List.of());
    }

    /**
     * {@link #adjust(InstalmentLadder, BehaviouralOverlay, Rate)} with due dates for
     * the periods past the contractual ladder.
     *
     * <p>Needed by {@code RolloverAssumption} and by a {@code RevolverBehaviour}
     * whose behavioural life runs past stated maturity, and needed only where the
     * ladder's own dates do not follow the frequency the rate implies — a
     * business-day-adjusted or seasonal schedule. The derivation is <em>verified
     * against the ladder it extends</em> before it is trusted, so a convention this
     * cannot reproduce is refused and named rather than guessed at. Date generation belongs to {@link ScheduleDates}, which
     * has the calendar; this is the minimum needed to lengthen a ladder that is
     * already dated.
     *
     * @param extendedDueDates dates for periods {@code L+1, L+2, ...} in ascending
     *     order, where {@code L} is the contractual ladder's last period; empty to
     *     derive them
     */
    public static InstalmentLadder adjust(
        InstalmentLadder contractual,
        BehaviouralOverlay overlay,
        Rate contractualRate,
        List<LocalDate> extendedDueDates) {
        return adjustment(contractual, overlay, contractualRate, extendedDueDates).expected();
    }

    /** The expected ladder together with the recorded basis of the expectation. */
    public static BehaviouralAdjustment adjustment(
        InstalmentLadder contractual, BehaviouralOverlay overlay, Rate contractualRate) {
        return adjustment(contractual, overlay, contractualRate, List.of());
    }

    /**
     * The expected ladder together with the recorded basis of the expectation.
     *
     * <p>Prefer this over {@link #adjust} wherever the result is going to be
     * disclosed, reviewed or challenged. The ladder is the number; the adjustment is
     * the number and its derivation, and 09 § 2.7 is explicit that an assumption
     * without its derivation and an absence of assumption are indistinguishable in an
     * audit file even when they produce identical figures.
     */
    public static BehaviouralAdjustment adjustment(
        InstalmentLadder contractual,
        BehaviouralOverlay overlay,
        Rate contractualRate,
        List<LocalDate> extendedDueDates) {

        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(contractualRate, "contractualRate");
        Objects.requireNonNull(extendedDueDates, "extendedDueDates");
        int periodsPerYear = contractualRate.periodsPerYear();

        if (!overlay.altersFlows()) {
            return BehaviouralAdjustment.of(
                overlay, contractual, contractual, unalteredBasis(overlay), periodsPerYear);
        }
        requireDeclaredFrequency(contractual, periodsPerYear);
        return switch (overlay) {
            // Unreachable: Contractual.altersFlows() is false for every instance, so the
            // branch above has already returned. Stated rather than defaulted so that a
            // future variant cannot fall silently into a prepayment roll-forward.
            case BehaviouralOverlay.Contractual ignored -> throw new IllegalStateException(
                "a Contractual overlay never alters flows");
            case BehaviouralOverlay.ConstantPrepaymentRate cpr ->
                prepayment(contractual, cpr, contractualRate);
            case BehaviouralOverlay.CprVector vector ->
                prepayment(contractual, vector, contractualRate);
            case BehaviouralOverlay.RolloverAssumption rollover ->
                rollover(contractual, rollover, contractualRate, extendedDueDates);
            case BehaviouralOverlay.RevolverBehaviour revolver ->
                revolver(contractual, revolver, contractualRate, extendedDueDates);
        };
    }

    // ------------------------------------------------------------- the par test

    /**
     * The unamortised premium or discount: EIR carrying amount less contractual
     * balance.
     *
     * <p><b>Positive is a premium</b> — an instrument bought above par, or one
     * carrying an unamortised integral <em>cost</em>, whose EIR-leg balance sits
     * above what the borrower owes. Negative is a discount, or an unamortised fee
     * received.
     *
     * <p>Note that this is the <em>negation</em> of INV-4's "unamortised fee", which
     * is defined contractual-less-EIR and is therefore positive for a fee received.
     * Both signs are conventional, both are used in the sources — 09 § 3.4 tabulates
     * a premium as {@code +7,688.30} — and the two quantities differ by nothing but
     * their sign, which is exactly what makes them dangerous to hold in one head.
     * They are named apart here for that reason: ask for a premium and get a premium.
     */
    public static Money unamortisedPremiumOrDiscount(
        Money contractualBalance, Money eirCarryingAmount) {
        Objects.requireNonNull(contractualBalance, "contractualBalance");
        Objects.requireNonNull(eirCarryingAmount, "eirCarryingAmount");
        return eirCarryingAmount.minus(contractualBalance);
    }

    /**
     * <b>The screen (09 § 3.4, invariant ST-9): is this contract's re-estimation
     * capable of hitting P&amp;L at all?</b>
     *
     * <p>{@code true} only where the instrument is held away from par. At par the
     * catch-up is exactly zero for any revision of any behavioural or option
     * assumption, because the revised flows discount at the contractual rate to the
     * outstanding balance whatever speed is assumed — so a restatement would post
     * nothing, disclose nothing, and cost a solve.
     *
     * <p>This is the method a batch calls before it calls
     * {@code CatchUpCalculator}, and calling it in that order is the difference
     * between a close that finishes and one that does not. Keying the catch-up on
     * "did an assumption move" instead re-estimates every contract a curve refresh
     * touches; at 10 million contracts that is correctness-neutral and ruinous, and
     * it fills the movement schedule with nil-effect restatements that bury the ones
     * a reviewer needs to see.
     *
     * <p><b>The band is measured, not assumed, and that is a correction.</b> This used
     * to compare at presentation scale — dust is not a premium, and comparing at working
     * precision would classify the whole par book as away-from-par on 1e-20 of
     * arithmetic. Both halves of that are right and the conclusion did not follow:
     * rounding to presentation scale sets the threshold at exactly half a paisa, and the
     * residue the screen must admit is not bounded by half a paisa.
     *
     * <p>A billed instalment sits within half a paisa of the exact annuity, and that
     * error accumulates at the contractual rate over the schedule's life —
     * {@code 0.005 x ((1+r)^n - 1)/r}. Which is small for a short loan and is not small
     * at all for a mortgage:
     *
     * <pre>
     *   24 periods at 1.0000%   residue up to 0.1349
     *   47 periods at 0.8333%   residue up to 0.2862   (fixture O7's note)
     *   60 periods at 0.8333%   residue up to 0.3872
     *   240 periods at 0.7500%  residue up to 3.3394
     *   360 periods at 0.7500%  residue up to 9.1537
     * </pre>
     *
     * <p>So the half-paisa threshold was wrong by two to three orders of magnitude on the
     * longest-tenor population, and no constant can replace it — a band clearing 3.34 at
     * 240 periods would swallow real premiums on a 24-period loan. Measured on fixture
     * O7's par note the residue is 0.0089 at month 24, which rounded up to 0.01 and made
     * a note bought AT PAR read as held away from par: it posted a catch-up of -0.01 and
     * {@link #catchUpIsNilAtPar} passed vacuously through its away-from-par branch. The
     * defect therefore defeated this method's own purpose on exactly the population it
     * exists to protect — every long-tenor par contract re-estimating on rounding dust is
     * the churn the screen is here to prevent.
     *
     * <p>Hence the explicit bound. {@link #roundingResidueBound} derives it from the rate
     * and the period count, which the caller has; there is deliberately no overload that
     * defaults it, because the default is what was wrong. The difference is still reduced
     * once rather than two operands rounded and subtracted, for the reason
     * {@link InvariantResult#ofMoney} sets out at length.
     *
     * @param roundingResidueBound the largest unamortised figure this schedule can show
     *     from instalment rounding alone — see {@link #roundingResidueBound}
     */
    public static boolean reestimationIsAPnlEvent(
        Money contractualBalance, Money eirCarryingAmount, Money roundingResidueBound) {
        Objects.requireNonNull(roundingResidueBound, "roundingResidueBound");
        if (roundingResidueBound.isNegative()) {
            throw new IllegalArgumentException(
                "the residue bound is a magnitude, got " + roundingResidueBound);
        }
        Money unamortised = unamortisedPremiumOrDiscount(contractualBalance, eirCarryingAmount);
        return unamortised.abs().compareTo(roundingResidueBound) > 0;
    }

    /**
     * The largest unamortised premium or discount a schedule can show from instalment
     * rounding alone: {@code 0.005 x ((1+r)^n - 1)/r}.
     *
     * <p>A worst case rather than an estimate, which is the right shape for a band. The
     * actual residue is whatever the particular instalment's rounding happened to be and
     * is typically a fraction of this; fixture O7's note carries 0.0089 against a bound
     * of 0.2862. A band that is loose by a factor of thirty on a figure three orders of
     * magnitude below any premium worth accelerating costs nothing, and a band that is
     * tight by a factor of two costs a spurious catch-up on every contract in a cohort.
     *
     * <p>Half a paisa is the currency's own rounding unit and is hard-coded as such: an
     * instalment is billed at presentation scale, so the error it carries is bounded by
     * half of that scale's last place. A currency with different minor units would need
     * this read off {@link Money} rather than written here, which is a change worth making
     * when a second currency arrives and not before.
     *
     * @param contractualRate the rate the schedule accrues at, in the schedule's own
     *     periodicity
     * @param periods         the schedule's length in those periods
     */
    public static Money roundingResidueBound(
        Rate contractualRate, int periods, java.util.Currency currency) {

        Objects.requireNonNull(contractualRate, "contractualRate");
        Objects.requireNonNull(currency, "currency");
        if (periods < 1) {
            throw new IllegalArgumentException("a schedule spans at least one period, got " + periods);
        }
        BigDecimal rate = contractualRate.periodic();
        BigDecimal accumulation;
        if (rate.signum() == 0) {
            // An interest-free advance accumulates its rounding linearly; the closed form
            // above divides by the rate and is undefined here rather than merely awkward.
            accumulation = BigDecimal.valueOf(periods);
        } else {
            accumulation = Precision.onePlusPow(rate, periods)
                .subtract(BigDecimal.ONE)
                .divide(rate, Precision.WORKING);
        }
        return Money.of(HALF_A_MINOR_UNIT.multiply(accumulation, Precision.WORKING), currency);
    }

    /**
     * Invariant ST-9: a re-estimation on an instrument with a nil unamortised
     * premium or discount produces a catch-up of exactly zero.
     *
     * <p>Asserted in production and not only in tests, because the failure it catches
     * is silent. A par-priced contract that posts a catch-up has been discounted at
     * something other than the rate its flows accrue at — a re-solved rate substituted
     * for the retained one, the CU-1 defect that {@code CatchUpCalculator} is built
     * to make impossible and that this invariant detects from the other end, on the
     * one population where the correct answer is known in advance without computing
     * it.
     *
     * <p>Away from par no claim is made and the result passes carrying the premium or
     * discount that licenses the posting. A non-zero catch-up there is the instrument
     * behaving correctly, and an invariant that flagged it would be an invariant
     * nobody leaves switched on.
     *
     * @param catchUp the catch-up the engine computed, from
     *     {@code CatchUpCalculator.restate(...).catchUp()}
     */
    public static InvariantResult catchUpIsNilAtPar(
        Money contractualBalance, Money eirCarryingAmount, Money catchUp,
        Money roundingResidueBound) {

        Objects.requireNonNull(catchUp, "catchUp");
        Money unamortised = unamortisedPremiumOrDiscount(contractualBalance, eirCarryingAmount);
        if (reestimationIsAPnlEvent(contractualBalance, eirCarryingAmount, roundingResidueBound)) {
            return InvariantResult.pass(InvariantId.ST_9,
                "held away from par by " + unamortised.atPresentationScale()
                    + " of unamortised premium or discount, so the catch-up of "
                    + catchUp.atPresentationScale() + " is a P&L event and ST-9 makes no claim"
                    + " about its size");
        }
        // Nil to within the same band, and for the same reason. docs 09 § 3.4 says the
        // catch-up at par is "exactly 0.00", which is true of an unrounded schedule and
        // cannot be true of a billed one: the residue that makes the balance look a hair
        // off par makes the restatement land a hair off the balance, and it is the one
        // quantity bounding both. Asserting an exact zero here failed on fixture O7's own
        // par note, whose catch-up is -0.0089 and presents as -0.01.
        //
        // The claim that survives is the one worth making. It is not "the arithmetic
        // produced a literal zero" — no billed schedule does — it is "nothing was
        // accelerated", and a posting inside the instalment-rounding residue accelerated
        // nothing. A re-solved rate substituted for the retained one, which is the CU-1
        // defect this detects from the other end, moves the catch-up by orders of magnitude
        // more than the band and is caught exactly as before.
        if (catchUp.abs().compareTo(roundingResidueBound) <= 0) {
            return InvariantResult.pass(InvariantId.ST_9,
                "re-estimation at par (unamortised premium or discount "
                    + unamortised.atPresentationScale() + ") accelerated nothing: catch-up "
                    + catchUp.atPresentationScale() + " is within the schedule's own"
                    + " instalment-rounding residue of " + roundingResidueBound.atPresentationScale()
                    + ", and the revised flows discount at the contractual rate to the outstanding"
                    + " balance whatever speed is assumed");
        }
        return InvariantResult.fail(InvariantId.ST_9,
            "re-estimation at par (unamortised premium or discount "
                + unamortised.atPresentationScale() + ") posted a catch-up of "
                + catchUp.atPresentationScale() + ", beyond the schedule's rounding residue of "
                + roundingResidueBound.atPresentationScale() + ". At par there is nothing to"
                + " accelerate, so a posting this size means the revised flows were discounted at"
                + " something other than the rate they accrue at — a re-solved rate substituted"
                + " for the retained one (CU-1)",
            catchUp.amount());
    }

    // --------------------------------------------------------------- conversions

    /**
     * {@code 1 - (1 - CPR) ^ (1 / periodsPerYear)} — the periodic mortality implied
     * by an annual conditional prepayment rate.
     *
     * <p>At twelve periods a year this is the market's single monthly mortality
     * exactly. At any other frequency it is the same construction at that frequency,
     * and using the monthly figure there instead would prepay by a factor of
     * {@code 12 / periodsPerYear} too little.
     *
     * <p>A CPR is a <em>survival</em> statement — 8% of the pool is gone by year end
     * — so it compounds and the conversion takes a root. {@code CPR / 12} is the
     * error to look out for: on a 25% CPR it gives 2.0833% against the correct
     * 2.3688%, an 11% relative understatement of monthly speed, compounding over
     * every period of the ladder.
     */
    public static BigDecimal singlePeriodMortality(BigDecimal annualCpr, int periodsPerYear) {
        Objects.requireNonNull(annualCpr, "annualCpr");
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        if (annualCpr.signum() < 0 || annualCpr.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(
                "an annual CPR is a fraction in [0,1); a CPR of 1 retires the pool within the year"
                    + " and has no periodic root, got " + annualCpr.toPlainString());
        }
        if (annualCpr.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal exponent =
            BigDecimal.ONE.divide(BigDecimal.valueOf(periodsPerYear), Precision.WORKING);
        return BigDecimal.ONE.subtract(Precision.onePlusPow(annualCpr.negate(), exponent));
    }

    /**
     * The balance outstanding before the ladder's first rung.
     *
     * <p>Reconstructed as {@code balanceAfter + principal} on the first rung rather
     * than taken from a notional the ladder does not carry. The identity holds on
     * every rung by the roll-forward's own construction, including a capitalising
     * moratorium rung where {@code principal} is negative and the opening balance is
     * therefore <em>below</em> the balance the rung leaves behind.
     */
    public static Money openingBalance(InstalmentLadder ladder) {
        Objects.requireNonNull(ladder, "ladder");
        InstalmentLadder.Rung first = ladder.rungs().get(0);
        return first.balanceAfter().plus(first.principal());
    }

    // ---------------------------------------------------------------- prepayment

    /**
     * The prepayment roll-forward: fixed instalment, faster balance.
     *
     * <p>Each period, in order:
     *
     * <ol>
     *   <li>interest accrues on the balance actually outstanding, at the contractual
     *       rate — <em>not</em> copied from the contractual rung, whose interest was
     *       computed on a balance the prepayments have since left behind;
     *   <li>scheduled principal is the contractual instalment less that interest, so
     *       the bill stays the bill;
     *   <li>the mortality takes its fraction of the post-scheduled balance. Prepaying
     *       <em>after</em> the scheduled movement rather than before is the market
     *       convention and it is also the only ordering that cannot prepay principal
     *       the instalment was about to collect anyway;
     *   <li>the balance carries forward, and the ladder ends at the first period whose
     *       instalment would take it to or past its floor.
     * </ol>
     *
     * <p><b>The final rung is a stub, not an instalment.</b> It bills the remaining
     * balance plus that period's interest, which is less than the contractual EMI —
     * on O6 at 8% CPR, 16,379.70 against 44,986.30. Billing a full instalment there
     * would over-collect and the {@code Rung} constructor would refuse the negative
     * balance it left, which is the correct failure but a late one.
     *
     * <p><b>The floor is the contractual terminal balance</b>, zero on a fully
     * amortising ladder and the balloon or residual amount otherwise. A residual value
     * is the lessor's interest in an asset, not principal the lessee can prepay, so a
     * prepaying lease amortises to the residual and stops — ST-5, holding across the
     * overlay and not only on the contractual leg.
     */
    private static BehaviouralAdjustment prepayment(
        InstalmentLadder contractual, BehaviouralOverlay overlay, Rate contractualRate) {

        int periodsPerYear = contractualRate.periodsPerYear();
        BigDecimal periodic = contractualRate.periodic();
        Money floor = contractual.terminalBalance();
        Money opening = openingBalance(contractual);
        int lastPeriod = contractual.rungs().get(contractual.rungs().size() - 1).periodIndex();

        BigDecimal[] speeds = mortalities(overlay, lastPeriod, periodsPerYear);
        List<InstalmentLadder.Rung> rungs = new ArrayList<>();
        Money balance = opening;
        for (InstalmentLadder.Rung rung : contractual.rungs()) {
            requireReproducible(rung, periodic, contractual);
            Money interest = balance.times(periodic);
            boolean isLastRung = rung.periodIndex() == lastPeriod;
            Money afterScheduled = isLastRung
                ? floor
                : balance.minus(rung.total().minus(interest));
            if (afterScheduled.compareTo(floor) <= 0) {
                // The instalment would take the balance to or past its floor, so this rung
                // settles: whatever is prepayable is repaid and the terminal lump stays
                // retained rather than folded into the flow, exactly as it is on the
                // contractual leg. The min guards the degenerate ladder that opens below
                // its own floor, where ST-5 should report the breach rather than have it
                // silently plugged here.
                Money retained = balance.compareTo(floor) < 0 ? balance : floor;
                Money repaid = balance.minus(retained);
                // The last rung's total INCLUDES the retained lump, which is the convention
                // ScheduleBuilder's closing roll sets and FlowVectorAssembler relies on:
                // fixture S2's final period is 432,244.08, being the 32,244.08 instalment
                // plus the 400,000 balloon, and the assembler subtracts the terminal back out
                // to recover the billed instalment. Excluding it here made the assembler
                // compute a billed instalment of 4,000.00 - 400,000 = -396,000.00 alongside a
                // +400,000 BALLOON flow. The net cash was right, so no invariant complained,
                // and the disclosed decomposition was not a schedule anybody could read.
                //
                // On an intermediate rung the lump is not yet due, so the total is the
                // interest alone.
                Money settled = repaid.plus(interest);
                rungs.add(new InstalmentLadder.Rung(rung.periodIndex(), rung.dueOn(),
                    repaid, interest, isLastRung ? settled.plus(retained) : settled, retained));
                balance = retained;
                // Settling the prepayable balance is not the end of the contract when a
                // lump is retained. This used to break unconditionally, which is right for
                // a fully amortising ladder and wrong for a balloon or a lease residual:
                // the comment below says prepayment stops ON the floor rather than through
                // it, and a ladder that ends the moment the floor is reached stops short of
                // saying so. The borrower still owes the lump at CONTRACTUAL maturity, and
                // still owes interest on it until then.
                //
                // Two figures went missing, and neither invariant could see it. On a
                // 400,000 balloon reached four periods early, four periods of interest on
                // the retained lump vanished from the expected leg — ST-3 cannot notice,
                // because the principal column telescopes to the advance whatever the
                // ladder's length. And FlowVectorAssembler dates the terminal flow at
                // rungs.get(size - 1).dueOn(), so a truncated ladder discounted the balloon
                // four months early — ST-5 cannot notice either, because the terminal
                // AMOUNT is right and only its date is wrong. Running the ladder to
                // maturity fixes both from one place: the last rung is then the contractual
                // maturity rung, so the assembler's dating is correct by construction.
                //
                // Continuing costs nothing on the fully amortising path: with a zero floor
                // there is no lump to carry, so the ladder ends here as it always did.
                if (isLastRung || retained.isZero() || balance.compareTo(floor) < 0) {
                    break;
                }
                continue;
            }
            // The mortality bites on the prepayable balance only. A residual value is the
            // lessor's interest in an asset and a balloon is a contractual lump; neither is
            // principal a borrower can prepay away, so the floor is a floor and prepayment
            // stops on it rather than through it.
            Money prepaid = afterScheduled.times(speeds[rung.periodIndex() - 1]);
            Money prepayable = afterScheduled.minus(floor);
            if (prepaid.compareTo(prepayable) > 0) {
                prepaid = prepayable;
            }
            balance = afterScheduled.minus(prepaid);
            rungs.add(new InstalmentLadder.Rung(rung.periodIndex(), rung.dueOn(),
                rung.total().minus(interest).plus(prepaid), interest,
                rung.total().plus(prepaid), balance));
        }

        InstalmentLadder expected =
            InstalmentLadder.of(contractual.currency(), rungs, opening, floor);
        return BehaviouralAdjustment.of(
            overlay, contractual, expected,
            prepaymentBasis(overlay, speeds[0], periodsPerYear, lastPeriod, rungs.size()),
            periodsPerYear);
    }

    /**
     * The mortality for each period, computed once.
     *
     * <p>Hoisted out of the roll-forward rather than taken per period, because the
     * root is an arbitrary-precision power and a constant CPR would otherwise raise
     * the same number to the same fractional exponent once for every rung — 240 times
     * on O6, and 240 times on each of ten million contracts. This class argues at
     * length that keying re-estimation on the wrong condition is a ruinous performance
     * mistake; recomputing a constant inside a loop is the same mistake in miniature.
     *
     * <p>A {@code CprVector} holds its last point flat past its end, which
     * {@link BehaviouralOverlay.CprVector#forPeriod} does: a curve fitted over
     * thirty-six months and applied to a 240-month mortgage extrapolates as a level
     * speed rather than falling to zero, because a vector running out is a gap in the
     * curve and not an assumption that prepayment stops.
     */
    private static BigDecimal[] mortalities(
        BehaviouralOverlay overlay, int periods, int periodsPerYear) {

        BigDecimal[] speeds = new BigDecimal[periods];
        switch (overlay) {
            case BehaviouralOverlay.ConstantPrepaymentRate constant -> {
                BigDecimal smm = singlePeriodMortality(constant.annualCpr(), periodsPerYear);
                for (int index = 0; index < periods; index++) {
                    speeds[index] = smm;
                }
            }
            case BehaviouralOverlay.CprVector vector -> {
                for (int period = 1; period <= periods; period++) {
                    speeds[period - 1] =
                        singlePeriodMortality(vector.forPeriod(period), periodsPerYear);
                }
            }
            default -> throw new IllegalStateException(
                overlay.label() + " is not a prepayment overlay and states no mortality");
        }
        return speeds;
    }

    // ------------------------------------------------------------------ rollover

    /**
     * A facility expected to roll: the ladder replayed, principal repaid once at the
     * end.
     *
     * <p>WCDL, gold loans, bill discounting. A rollover is not a new instrument in
     * substance where the renewal process is not substantive (ACPIR 46(2)(ii)), and
     * where it is not, the economics are one facility running the rolled term — so
     * the interest pattern repeats and the principal repayment moves to the last
     * cycle. Each intermediate cycle's closing rung gives up its principal repayment
     * and keeps its interest, which is precisely what a roll is.
     *
     * <p><b>Restricted to a ladder that repays principal only at its final rung.</b>
     * Rolling an <em>amortising</em> facility means re-advancing principal that has
     * already been repaid, which changes every instalment and is schedule
     * construction, not an overlay — the same boundary {@code OptionalityResolver}
     * draws when it refuses to extend an amortising term. And a facility that
     * amortises within its term and is then rolled is, on the 46(2)(ii) test, much
     * more likely to be a series of new instruments than one revolving facility.
     *
     * <p><b>The probability is recorded, not multiplied in.</b> Blending a rolled
     * outcome with an unrolled one at a probability is a probability-weighted
     * expected life, which {@link ExercisePolicy#PROBABILITY_WEIGHTED} already
     * expresses with a full distribution over exercise dates. Expressing it a second
     * time here from a single scalar would put two weighting mechanisms in the engine
     * for one judgement, with no way to say which produced a published rate. What the
     * probability does do is qualify the disclosure — a roll assumed at less than even
     * odds is not the most likely outcome, and the recorded basis says so.
     */
    private static BehaviouralAdjustment rollover(
        InstalmentLadder contractual,
        BehaviouralOverlay.RolloverAssumption overlay,
        Rate contractualRate,
        List<LocalDate> extendedDueDates) {

        int periodsPerYear = contractualRate.periodsPerYear();
        BigDecimal periodic = contractualRate.periodic();
        Money zero = Money.zero(contractual.currency());
        int cycle = requireContiguous(contractual, "a rolled");
        requireBullet(contractual, "rolled");

        int cycles = overlay.expectedRollovers() + 1;
        int periods = cycle * cycles;
        List<LocalDate> dates = dueDates(contractual, periodsPerYear, periods, extendedDueDates);
        Money opening = openingBalance(contractual);

        List<InstalmentLadder.Rung> rungs = new ArrayList<>(periods);
        Money balance = opening;
        for (int period = 1; period <= periods; period++) {
            InstalmentLadder.Rung template = contractual.rungs().get((period - 1) % cycle);
            if (period <= cycle) {
                requireReproducible(template, periodic, contractual);
            }
            Money interest = balance.times(periodic);
            boolean settles = period == periods;
            Money principal = settles ? balance : zero;
            rungs.add(new InstalmentLadder.Rung(period, dates.get(period - 1),
                principal, interest, principal.plus(interest), settles ? zero : balance));
            if (settles) {
                balance = zero;
            }
        }

        InstalmentLadder expected = InstalmentLadder.of(contractual.currency(), rungs, opening, zero);
        return BehaviouralAdjustment.of(overlay, contractual, expected,
            rolloverBasis(overlay, cycle, periods), periodsPerYear);
    }

    // ------------------------------------------------------------------ revolver

    /**
     * A revolver: the utilisation curve drives the balance, the behavioural life
     * closes it.
     *
     * <p>Cards, CC/OD, KCC. There is no contractual repayment schedule to reshape —
     * {@code UtilisationDriven} disbursement means there is no drawdown schedule
     * either (09 § 2.1) — so the curve is the schedule: the balance in each period is
     * what the curve says is drawn, interest accrues on it, and the principal column
     * is whatever movement that implies. A period where the curve rises bills a
     * <em>negative</em> principal and a negative total, which is a net advance, and it
     * is not an error to be smoothed away — a card book genuinely lends more in
     * November than in October.
     *
     * <p><b>The curve is anchored to the ladder's opening balance, not to a limit.</b>
     * The curve states drawn fractions of a limit and no limit reaches this stage: the
     * blueprint's notional is already {@code limit x averageUtilisation} and a ladder
     * carries a balance, not a facility. So the first point of the curve is read as the
     * utilisation <em>at inception</em>, which the opening balance measures, and the
     * rest of the curve moves the balance in proportion. That is the only reading which
     * leaves the opening balance untouched — an overlay that restated the amount
     * actually drawn at inception would break IC-1 against the disbursement that was
     * actually made, and it would do it silently. A one-point curve is therefore a flat
     * utilisation and the overlay reduces to a pure truncation, which is the right
     * degenerate case.
     *
     * <p><b>Behavioural life in months, converted at the ladder's frequency.</b>
     * ACPIR 46(2)(iii) states the life in months and the field says so; a quarterly
     * ladder needs it in quarters and a life that does not land on a period boundary
     * is refused rather than rounded, because rounding it silently bills a period the
     * analysis never supported.
     *
     * <p>The last period repays the balance in full. That is what a behavioural life
     * <em>is</em> — the point past which the facility is not expected to exist — and
     * the curve's value there is overridden rather than blended, since a curve
     * extending past the modelled life is a curve about a facility the model says has
     * closed.
     */
    private static BehaviouralAdjustment revolver(
        InstalmentLadder contractual,
        BehaviouralOverlay.RevolverBehaviour overlay,
        Rate contractualRate,
        List<LocalDate> extendedDueDates) {

        int periodsPerYear = contractualRate.periodsPerYear();
        BigDecimal periodic = contractualRate.periodic();
        Money zero = Money.zero(contractual.currency());
        int contractualPeriods = requireContiguous(contractual, "a revolving");
        int periods = behaviouralPeriods(overlay.behaviouralLifeMonths(), periodsPerYear);
        List<LocalDate> dates = dueDates(contractual, periodsPerYear, periods, extendedDueDates);

        List<BigDecimal> curve = overlay.utilisationCurve();
        BigDecimal anchor = curve.isEmpty() ? BigDecimal.ONE : curve.get(0);
        if (anchor.signum() <= 0) {
            throw new IllegalArgumentException(
                "the utilisation curve opens at " + anchor.toPlainString() + ". The first point"
                    + " anchors the curve to the ladder's opening balance, so a nil or negative"
                    + " opening utilisation gives the curve no scale and would restate the amount"
                    + " actually drawn at inception");
        }
        Money opening = openingBalance(contractual);

        List<InstalmentLadder.Rung> rungs = new ArrayList<>(periods);
        Money balance = opening;
        for (int period = 1; period <= periods; period++) {
            if (period <= contractualPeriods) {
                requireReproducible(contractual.rungs().get(period - 1), periodic, contractual);
            }
            Money interest = balance.times(periodic);
            Money target = period == periods
                ? zero
                : opening.times(utilisation(curve, period + 1).divide(anchor, Precision.WORKING));
            Money principal = balance.minus(target);
            rungs.add(new InstalmentLadder.Rung(period, dates.get(period - 1),
                principal, interest, principal.plus(interest), target));
            balance = target;
        }

        InstalmentLadder expected = InstalmentLadder.of(contractual.currency(), rungs, opening, zero);
        return BehaviouralAdjustment.of(overlay, contractual, expected,
            revolverBasis(overlay, periods, contractualPeriods), periodsPerYear);
    }

    /** The curve's 1-based point, held flat past its end. */
    private static BigDecimal utilisation(List<BigDecimal> curve, int point) {
        if (curve.isEmpty()) {
            return BigDecimal.ONE;
        }
        return curve.get(Math.min(point, curve.size()) - 1);
    }

    /**
     * A behavioural life stated in months, expressed in the ladder's own periods.
     *
     * <p>Refused rather than rounded where it does not land on a period boundary: a
     * 30-month life on a quarterly ladder is either 10 quarters or an analysis that
     * was not done at quarterly granularity, and the engine cannot tell which.
     */
    private static int behaviouralPeriods(int lifeMonths, int periodsPerYear) {
        long product = (long) lifeMonths * periodsPerYear;
        if (product % 12L != 0L) {
            throw new IllegalArgumentException(
                "a behavioural life of " + lifeMonths + " months does not land on a boundary of a"
                    + " ladder compounding " + periodsPerYear + " times a year (" + lifeMonths
                    + " x " + periodsPerYear + " / 12 is not whole). Restate the ACPIR 46(2)(iii)"
                    + " life at the schedule's own frequency rather than having it rounded here");
        }
        int periods = (int) (product / 12L);
        if (periods < 1) {
            throw new IllegalArgumentException(
                "a behavioural life of " + lifeMonths + " months is less than one period at "
                    + periodsPerYear + " periods a year, so the facility closes before its first"
                    + " due date and has no ladder");
        }
        return periods;
    }

    // -------------------------------------------------------------------- guards

    /**
     * Refuses a rate that does not reproduce the ladder it is being asked to reshape.
     *
     * <p>An overlay recomputes interest on a balance path the contractual ladder never
     * had, so it needs the rate that generated the ladder. If the rate it is given is
     * a different one, every figure it produces is plausible and wrong, and nothing
     * downstream can detect it — the expected leg simply amortises to zero at the
     * wrong speed. So the claim is checked where it is cheap: on each rung consumed,
     * the ladder's own opening balance times the supplied rate must reproduce the
     * ladder's own interest column.
     *
     * <p>It catches, from one comparison: an annual rate passed where a periodic one
     * was wanted; a periodic rate at the wrong frequency; and a stepped, floating or
     * indexed coupon, which has no single contractual rate at all and whose expected
     * leg must therefore be built from the rate profile through the blueprint pipeline
     * rather than from one scalar here. Checking only the first period would miss the
     * third, which is the one that produces a right-looking answer.
     *
     * <p>Tolerance is one minor unit, and it is a rounding allowance rather than a
     * fudge. On a ladder whose contract fixes the <em>principal</em> — equal-principal,
     * sculpted — {@code ScheduleBuilder} assigns the paise the bill rounds to the
     * interest column, so that column can sit up to a minor unit away from the raw
     * accrual while both figures are right (03 § 5.7). Anything beyond that is a
     * different rate and not a rounding artefact.
     */
    private static void requireReproducible(
        InstalmentLadder.Rung rung, BigDecimal periodic, InstalmentLadder ladder) {

        Money before = rung.balanceAfter().plus(rung.principal());
        Money deviation = before.times(periodic).minus(rung.interest()).atPresentationScale();
        Money tolerance = Money.of(
            BigDecimal.ONE.scaleByPowerOfTen(-ladder.currency().getDefaultFractionDigits()),
            ladder.currency());
        if (deviation.abs().compareTo(tolerance) > 0) {
            throw new IllegalArgumentException(
                "period " + rung.periodIndex() + " of the contractual ladder bills interest of "
                    + rung.interest().atPresentationScale() + " on an opening balance of "
                    + before.atPresentationScale() + ", but the supplied rate accrues "
                    + before.times(periodic).atPresentationScale() + " on it — a deviation of "
                    + deviation + ". A behavioural overlay recomputes interest on a balance path"
                    + " the contractual ladder never had, so it can only reshape a ladder it can"
                    + " reproduce at zero speed. Either the rate is not the one the ladder was"
                    + " built at, or the coupon steps, floats or is indexed and has no single"
                    + " contractual rate — in which case build the expected leg from the rate"
                    + " profile through the blueprint pipeline");
        }
    }

    /**
     * Refuses a rate whose <em>declared</em> frequency is not the ladder's.
     *
     * <p>{@code periodsPerYear} is load-bearing twice and invisibly: it is the root the
     * CPR is taken at, and the step an extension rolls by. A rate carrying the right
     * periodic value at the wrong declared frequency therefore passes
     * {@link #requireReproducible} — the interest column reproduces exactly, because the
     * periodic value is right — and then prepays by a factor of
     * {@code 12 / periodsPerYear} too much or too little. A monthly ladder declared
     * quarterly runs at roughly three times the intended speed, and every figure it
     * produces looks like a schedule.
     *
     * <p>The check is exact rather than approximate, and that is what makes it safe to
     * run on every ladder. Where the ladder's dates are reproducible by stepping its
     * first due date at the declared frequency, the declaration is confirmed and
     * nothing more is done. Where they are not, the other steppable frequencies are
     * tried, and a frequency that <em>does</em> reproduce them is a refusal naming both.
     * Where none reproduces them the ladder is business-day-adjusted, seasonal,
     * month-end-clamped or bespoke — nothing can be concluded from its dates in either
     * direction, so nothing is: a band-based approximation here would refuse a
     * crop-cycle KCC whose periods are genuinely unequal, which is precisely the
     * schedule 09 § 2.8 says must be supported.
     */
    private static void requireDeclaredFrequency(InstalmentLadder ladder, int periodsPerYear) {
        if (reproducesDates(ladder, periodsPerYear)) {
            return;
        }
        for (int candidate : STEPPABLE_FREQUENCIES) {
            if (candidate == periodsPerYear || !reproducesDates(ladder, candidate)) {
                continue;
            }
            throw new IllegalArgumentException(
                "the supplied rate declares " + periodsPerYear + " periods a year, but the ladder's"
                    + " due dates step at " + candidate + " periods a year (" + candidate
                    + " reproduces every one of the " + ladder.length() + " dates from "
                    + ladder.rungs().get(0).dueOn() + ", " + periodsPerYear + " does not). The"
                    + " declared frequency is the root a CPR is taken at and the step an extension"
                    + " rolls by, so an overlay run on it would prepay by a factor of "
                    + candidate + "/" + periodsPerYear + " and still look like a schedule");
        }
    }

    /** Whether stepping the first due date at this frequency reproduces every date. */
    private static boolean reproducesDates(InstalmentLadder ladder, int periodsPerYear) {
        LocalDate anchor = ladder.rungs().get(0).dueOn();
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            LocalDate derived = stepped(anchor, rung.periodIndex() - 1, periodsPerYear);
            if (derived == null || !derived.equals(rung.dueOn())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Requires the ladder's periods to run {@code 1..n} without a gap.
     *
     * <p>Only the lengthening overlays need this. A ladder may legitimately carry no
     * rung for a period — a moratorium boundary the builder did not emit, a season
     * with no due date — and truncating overlays are indifferent to it, but replaying
     * or extending a ladder by period ordinal is not: a gap would silently shift every
     * rolled date by the number of missing rows.
     */
    private static int requireContiguous(InstalmentLadder ladder, String what) {
        List<InstalmentLadder.Rung> rungs = ladder.rungs();
        for (int index = 0; index < rungs.size(); index++) {
            if (rungs.get(index).periodIndex() != index + 1) {
                throw new IllegalArgumentException(
                    what + " ladder must run periods 1.." + rungs.size() + " without a gap, but"
                        + " position " + (index + 1) + " carries period "
                        + rungs.get(index).periodIndex() + ". Replaying or extending a ladder by"
                        + " period ordinal over a gap shifts every subsequent due date");
            }
        }
        return rungs.size();
    }

    /** Requires principal to be repaid only at the final rung, and nothing left after it. */
    private static void requireBullet(InstalmentLadder ladder, String what) {
        if (!ladder.terminalBalance().isZero()) {
            throw new IllegalArgumentException(
                "the ladder still carries " + ladder.terminalBalance() + " after its final rung, so"
                    + " what a " + what + " facility would roll is undefined");
        }
        List<InstalmentLadder.Rung> rungs = ladder.rungs();
        for (int index = 0; index < rungs.size() - 1; index++) {
            if (!rungs.get(index).principal().isZero()) {
                throw new IllegalArgumentException(
                    "period " + rungs.get(index).periodIndex() + " repays "
                        + rungs.get(index).principal() + " of principal, so this ladder amortises"
                        + " within its term. Rolling it means re-advancing principal already"
                        + " repaid, which re-solves every instalment and belongs to schedule"
                        + " construction — and a facility that amortises and is then renewed is"
                        + " far more likely a series of new instruments on the ACPIR 46(2)(ii)"
                        + " substantive-renewal test than one revolving facility");
            }
        }
    }

    // --------------------------------------------------------------------- dates

    /**
     * Due dates for {@code periods} periods: the ladder's own, then supplied or
     * derived ones.
     *
     * <p>Derivation anchors on the ladder's <em>first</em> due date and steps by the
     * frequency the rate implies — anchored, never chained, because chaining
     * accumulates the end-of-month clamp and quietly turns a month-end schedule into a
     * 28th-of-the-month one from the second period on ({@link ScheduleDates#dueDates}
     * makes the same point).
     *
     * <p><b>And it is verified before it is trusted.</b> The generator must reproduce
     * every date the ladder already has; where it cannot — a business-day-adjusted
     * schedule, a seasonal one, a bespoke roll — the extension is refused and the
     * caller is asked for the dates rather than handed a guess. A wrong due date is
     * invisible in the instalment and visible only in the discount factors, so guessing
     * one produces a rate that is wrong in a way nothing reconciles.
     */
    private static List<LocalDate> dueDates(
        InstalmentLadder contractual, int periodsPerYear, int periods, List<LocalDate> supplied) {

        List<LocalDate> dates = new ArrayList<>(periods);
        for (InstalmentLadder.Rung rung : contractual.rungs()) {
            dates.add(rung.dueOn());
        }
        if (periods <= dates.size()) {
            return List.copyOf(dates.subList(0, periods));
        }
        int wanted = periods - dates.size();
        if (!supplied.isEmpty()) {
            if (supplied.size() < wanted) {
                throw new IllegalArgumentException(
                    "the expected leg runs " + periods + " periods against a contractual ladder of "
                        + dates.size() + ", so " + wanted + " further due dates are needed but "
                        + supplied.size() + " were supplied");
            }
            for (int index = 0; index < wanted; index++) {
                LocalDate next = supplied.get(index);
                if (!next.isAfter(dates.get(dates.size() - 1))) {
                    throw new IllegalArgumentException(
                        "supplied due date " + next + " does not follow "
                            + dates.get(dates.size() - 1) + "; the extended dates must ascend");
                }
                dates.add(next);
            }
            return List.copyOf(dates);
        }
        LocalDate anchor = dates.get(0);
        for (int period = 1; period <= dates.size(); period++) {
            LocalDate derived = stepped(anchor, period - 1, periodsPerYear);
            if (derived == null || !derived.equals(dates.get(period - 1))) {
                throw new IllegalArgumentException(
                    "the expected leg runs " + periods + " periods against a contractual ladder of "
                        + dates.size() + ", and its due dates cannot be extended: stepping the"
                        + " first due date " + anchor + " by " + periodsPerYear + " periods a year"
                        + " reaches " + derived + " for period " + period + " where the ladder has "
                        + dates.get(period - 1) + ". A business-day-adjusted, seasonal or bespoke"
                        + " roll cannot be inferred from a frequency — supply the extended due"
                        + " dates");
            }
        }
        for (int period = dates.size() + 1; period <= periods; period++) {
            dates.add(stepped(anchor, period - 1, periodsPerYear));
        }
        return List.copyOf(dates);
    }

    /**
     * The anchor advanced {@code steps} periods, or {@code null} where the frequency
     * has no derivable calendar step.
     *
     * <p>Fortnightly steps two weeks rather than half a month, for the reason
     * {@link ScheduleDates#anchor} gives: a fortnightly instalment is a fortnightly
     * instalment and expressing it in months would make its period length depend on
     * February.
     */
    private static LocalDate stepped(LocalDate anchor, int steps, int periodsPerYear) {
        return switch (periodsPerYear) {
            case 1 -> anchor.plusYears(steps);
            case 2 -> anchor.plusMonths(6L * steps);
            case 3 -> anchor.plusMonths(4L * steps);
            case 4 -> anchor.plusMonths(3L * steps);
            case 6 -> anchor.plusMonths(2L * steps);
            case 12 -> anchor.plusMonths(steps);
            case 26 -> anchor.plusWeeks(2L * steps);
            case 52 -> anchor.plusWeeks(steps);
            default -> null;
        };
    }

    // ------------------------------------------------------------ recorded basis

    /**
     * The basis recorded where the overlay changes nothing.
     *
     * <p>09 § 2.7 requires this to be a <em>statement</em>, because "we used
     * contractual life" and "we never considered life" are the same figures and
     * different audit outcomes. A nil CPR is the same case reached from the other
     * direction: a curve was fitted, it came out at zero, and that is a finding.
     */
    private static String unalteredBasis(BehaviouralOverlay overlay) {
        if (overlay instanceof BehaviouralOverlay.Contractual contractual) {
            return "expected life equals contractual life as a recorded policy choice, not as an"
                + " absence of assumption (09 § 2.7). Basis: " + contractual.basis();
        }
        return "overlay " + overlay.label() + " is present and assumes no deviation from the"
            + " contractual flows, so the expected leg is the contractual leg. The assumption was"
            + " formed and came out nil; it is recorded rather than dropped";
    }

    private static String prepaymentBasis(
        BehaviouralOverlay overlay,
        BigDecimal firstPeriod,
        int periodsPerYear,
        int contractualLife,
        int expectedLife) {

        return overlay.label() + " converted to a single-period mortality of "
            + Precision.round(firstPeriod, 10).toPlainString() + " at period 1 on a ladder"
            + " compounding " + periodsPerYear + " times a year"
            + (periodsPerYear == 12 ? " (the market's single monthly mortality)" : "")
            + ". The contractual instalment is held and the balance runs down faster, compressing "
            + contractualLife + " contractual periods to " + expectedLife
            + ". Driver on revision: " + overlay.driverOnRevision();
    }

    private static String rolloverBasis(
        BehaviouralOverlay.RolloverAssumption overlay, int cycle, int periods) {

        boolean belowEvens =
            overlay.rolloverProbability().compareTo(new BigDecimal("0.5")) < 0;
        return overlay.expectedRollovers() + " rollover(s) of a " + cycle + "-period facility"
            + " assumed, giving an expected life of " + periods + " periods with principal repaid"
            + " once at the end. Rollover probability "
            + overlay.rolloverProbability().toPlainString()
            + (belowEvens
                ? " is below even odds, so the rolled outcome is not the most likely one and the"
                    + " assumption rests on the ACPIR 46(2)(ii) substantive-renewal analysis rather"
                    + " than on the modal outcome"
                : " supports the roll as the expected outcome on the ACPIR 46(2)(ii)"
                    + " substantive-renewal test")
            + "; the probability is recorded and not weighted into the flows. Driver on revision: "
            + overlay.driverOnRevision();
    }

    private static String revolverBasis(
        BehaviouralOverlay.RevolverBehaviour overlay, int periods, int contractualPeriods) {

        return "behavioural life of " + overlay.behaviouralLifeMonths() + " months = " + periods
            + " periods against a " + contractualPeriods + "-period contractual ladder, with a "
            + overlay.utilisationCurve().size() + "-point utilisation curve anchored on the"
            + " ladder's opening balance at its first point. ACPIR 46(2)(iii) analysis: "
            + overlay.analysisReference() + ". The entire carrying amount of a revolver measured"
            + " this way is an estimate, which is why the analysis reference is mandatory and why"
            + " a revision is a policy version rather than a parameter update. Driver on revision: "
            + overlay.driverOnRevision();
    }
}
