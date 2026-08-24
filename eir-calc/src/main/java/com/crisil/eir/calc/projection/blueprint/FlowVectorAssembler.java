package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Step 6 of the blueprint pipeline
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 4</a>): an
 * {@link InstalmentLadder}, the fee postings and the {@link DisbursementProfile}
 * resolved into the {@link FlowVector} the solver discounts.
 *
 * <p>This is the last stage at which a cash flow can be got wrong <em>quietly</em>.
 * Everything upstream produces a schedule that a person can read and reconcile
 * against the lending system; everything downstream sees only signed amounts and
 * dates. So the three decisions this class makes are each made once, here, and each
 * of them is a decision the existing shape projectors already make the same way —
 * the arithmetic is not restated, only the input type is different.
 *
 * <h2>1. Sign is the holder's, and the holder is not always the lender</h2>
 *
 * <p>On the asset side the advance is an <b>outflow</b> and every rung is an inflow.
 * That is not a convention this class is free to choose: {@code ProjectionResult}
 * computes IC-1 as {@code initialCarryingAmount == -(net cash at inception)}, and the
 * solver takes the same negated figure as its target, so a vector signed the other
 * way produces a rate solved against a mirror of the carrying amount.
 *
 * <p>{@link InstrumentSide#LIABILITY} flips the advance and the rungs and
 * <em>leaves the fee postings alone</em>. That asymmetry is deliberate and it is
 * where a liability projection usually goes wrong. A {@link FeePosting} is already
 * signed from the holder's perspective, and the holder of an issued bond is the
 * issuer: it genuinely receives the proceeds and genuinely pays the arranger. So the
 * proceeds flip and the issue cost does not. Negating the whole vector instead
 * capitalises the issue cost as though it had been received, and on a 1,000,000 NCD
 * with 10,000 of issue costs it carries the liability at 1,010,000 rather than
 * 990,000 — a 2% error in the opening balance, in the wrong direction, on every
 * issuance in the book.
 *
 * <h2>2. A tranched facility keeps its interim draws as negative flows</h2>
 *
 * <p>Draws after inception are emitted at their own date and period ordinal, signed
 * as the outflows they are. This is the shape that gives the present-value function
 * more than one sign change and therefore more than one mathematically valid IRR
 * (03 § 4.4), and hiding it by smoothing the draws into one notional advance would
 * remove the multiple-root disclosure along with the multiple roots. Draws falling
 * on the value date are aggregated into the single inception advance instead, for
 * the same reason {@code ProjectionSupport} dates an integral posting at initial
 * recognition rather than at its own posting date: a flow discounted against the
 * anchor it is the anchor of has no meaning, and IC-1 is computed on the flows the
 * anchor carries.
 *
 * <h2>3. Whether a rung is one flow or two follows the contract, not the arithmetic</h2>
 *
 * <p>Kind never affects discounting — only amount and date do — so this choice
 * cannot change a rate. It changes whether the contractual interest leg and INV-3
 * can be read off the vector without re-deriving them, and whether the two
 * reconciliation legs can tell a principal receipt from an interest receipt. The
 * split follows the same division {@code ScheduleBuilder} makes when it decides
 * which component the contract fixes to the paise:
 *
 * <ul>
 *   <li><b>Instalment is the primitive</b> — {@code LevelAnnuity}, {@code Balloon},
 *       {@code StepLadder}. One bill, one flow, {@link FlowKind#COMBINED_EMI}. The
 *       borrower is charged an EMI, not two lines.
 *   <li><b>Principal is the primitive</b> — {@code EqualPrincipal}, {@code Sculpted},
 *       {@code BulletAtMaturity}, {@code NoneUntilMaturity}. Two flows sharing a date
 *       and a period ordinal: {@link FlowKind#PRINCIPAL} and
 *       {@link FlowKind#INTEREST}.
 * </ul>
 *
 * <p>A split that does not sum to the bill is not a split, so where the two
 * components do not add up to the billed total at presentation scale the rung falls
 * back to one combined flow. That guard is not theoretical: a residue policy adjusts
 * a billed instalment without touching the balance path, and a published schedule
 * whose columns do not sum to the bill is a defect even where every figure in it is
 * individually right (03 § 5.7). It is better to carry the bill and lose the split
 * than to carry a split that misstates the bill.
 *
 * <p>A rung billing nothing — a capitalising moratorium period — emits exactly one
 * zero-amount flow rather than its {@code +accrual} and {@code -accrual} components.
 * The two would net to zero in the present value and add two spurious sign changes
 * to {@code Discounting.signChanges}, which is the input to multiple-root detection.
 * A zero flow declares the accrual boundary so that the holiday shows as a row per
 * period, which is what {@code OptionalityResolver} does with the same rung.
 *
 * <h2>The terminal lump</h2>
 *
 * <p>{@code ScheduleBuilder} folds the terminal amount into the final rung's billed
 * total, and leaves it in {@link InstalmentLadder#terminalBalance()} rather than in
 * the principal column, because ST-3 reads the terminal as principal still
 * outstanding. This class takes it back out and emits it as its own
 * {@link FlowKind#BALLOON} or {@link FlowKind#RESIDUAL_VALUE} flow at the last
 * period. The cash total is identical either way; what the separation buys is that a
 * lease residual and a balloon stay distinguishable, and they are different assets
 * to a controller even though the arithmetic that sizes them is the same.
 *
 * <p>Pure and clock-free: every date is an input, nothing here reads a wall clock,
 * and identical inputs produce a bit-identical vector forever (DT-1).
 */
public final class FlowVectorAssembler {

    /**
     * What a terminal lump is called when the caller does not say.
     *
     * <p>{@code BALLOON} rather than {@code RESIDUAL_VALUE} because
     * {@link PrincipalProfile.Balloon} is the variant that carries a terminal amount
     * and a balloon is the more common instrument. A lease or a commercial-vehicle
     * facility says so explicitly — see
     * {@link #assemble(ScheduleBlueprint, InstalmentLadder, List, FlowKind, InstrumentSide)}.
     */
    public static final FlowKind DEFAULT_TERMINAL_KIND = FlowKind.BALLOON;

    private FlowVectorAssembler() {
    }

    // ------------------------------------------------------------------ the stage

    /**
     * The asset-side vector for a ladder, with any terminal lump called a balloon.
     *
     * <p>The ordinary case: a loan advanced by the bank, amortising to zero or to a
     * balloon.
     */
    public static Assembly assemble(
        ScheduleBlueprint blueprint, InstalmentLadder ladder, List<FeePosting> fees) {
        return assemble(blueprint, ladder, fees, DEFAULT_TERMINAL_KIND, InstrumentSide.ASSET);
    }

    /**
     * The vector, the amount advanced at inception, the net integral fee, the initial
     * carrying amount and the invariants the assembly itself can assert.
     *
     * <p>Returned as a value rather than as a bare vector because the carrying amount
     * is derived here and must not be derived again anywhere else. IC-1 exists to
     * catch a carrying amount and a vector that disagree, and it can only catch it if
     * the two come from one place: a caller that recomputes {@code notional - fee}
     * for itself has re-implemented the very statement the invariant is comparing,
     * and the comparison then passes by construction on exactly the instruments where
     * it should fail — a tranched facility whose inception advance is the first draw
     * and not the sanctioned amount.
     *
     * @param blueprint    the composed dimensions; supplies the anchor, the currency
     *                     and the disbursement profile
     * @param ladder       the contractual ladder, or the optionality-shaped and
     *                     behaviourally-adjusted one for the expected leg
     * @param fees         resolved postings; only {@code INTEGRAL} ones enter the
     *                     carrying amount, and a contingent charge never does (FR-206)
     * @param terminalKind {@link FlowKind#BALLOON} or {@link FlowKind#RESIDUAL_VALUE}
     * @param side         whose perspective the signs are taken from
     */
    public static Assembly assemble(
        ScheduleBlueprint blueprint,
        InstalmentLadder ladder,
        List<FeePosting> fees,
        FlowKind terminalKind,
        InstrumentSide side) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(ladder, "ladder");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(terminalKind, "terminalKind");
        Objects.requireNonNull(side, "side");
        requireTerminalKind(terminalKind);
        requireLadderCurrency(blueprint, ladder);

        Money advanced = amountAdvancedAtInception(blueprint);
        Money netFee = netIntegralFee(fees, blueprint.currency());
        Money carryingAmount = initialCarryingAmount(advanced, netFee, side);

        List<CashFlow> flows = new ArrayList<>(inceptionLeg(blueprint, fees, side));
        flows.addAll(futureLeg(blueprint, ladder, terminalKind, side));
        FlowVector vector = FlowVector.of(blueprint.valueDate(), blueprint.currency(), flows)
            .requireNoContingentFlows();

        List<InvariantResult> invariants = new ArrayList<>();
        if (blueprint.disbursement() instanceof DisbursementProfile.Tranched) {
            invariants.add(tranchesSumToNotional(blueprint));
        }
        return new Assembly(vector, advanced, netFee, carryingAmount, side, invariants);
    }

    /** {@link #assemble(ScheduleBlueprint, InstalmentLadder, List)} where only the vector is wanted. */
    public static FlowVector vector(
        ScheduleBlueprint blueprint, InstalmentLadder ladder, List<FeePosting> fees) {
        return assemble(blueprint, ladder, fees).vector();
    }

    // ------------------------------------------------------------- the two legs

    /**
     * The flows dated on the anchor: the advance drawn at inception, and each
     * integral posting.
     *
     * <p>Integral postings are dated at initial recognition rather than at their own
     * posting date, which is the same choice {@code ProjectionSupport} makes and for
     * the same two reasons: discounting an origination cost as though it were a
     * future flow understates the initial carrying amount, and dropping it breaks
     * IC-1. A posting that genuinely arises later is a subsequent cost and a
     * different event. {@code postedOn} survives on the posting for the audit trail.
     *
     * <p>An {@code INTEGRAL} posting of exactly zero is dropped. It carries no
     * information into the vector and a zero flow at the anchor is never discounted,
     * so the only thing keeping it would do is put a line in the computation trace
     * that a reviewer has to read before concluding it says nothing.
     */
    public static List<CashFlow> inceptionLeg(
        ScheduleBlueprint blueprint, List<FeePosting> fees, InstrumentSide side) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(side, "side");
        Money advanced = amountAdvancedAtInception(blueprint);
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(
            blueprint.valueDate(), 0,
            signedAdvance(advanced, side).atPresentationScale(),
            FlowKind.DISBURSEMENT));
        for (FeePosting fee : fees) {
            if (!fee.entersInitialCarryingAmount() || fee.amount().isZero()) {
                continue;
            }
            requireFeeCurrency(fee, blueprint.currency());
            FlowKind kind = fee.amount().isPositive()
                ? FlowKind.INTEGRAL_FEE_RECEIVED
                : FlowKind.INTEGRAL_COST_PAID;
            flows.add(CashFlow.of(
                blueprint.valueDate(), 0, fee.amount().atPresentationScale(), kind));
        }
        return List.copyOf(flows);
    }

    /**
     * The flows after the anchor: interim draws, one or two flows per rung, and the
     * terminal lump.
     *
     * <p>Draws past the ladder's last rung are dropped rather than carried. On the
     * contractual leg there are none — the ladder spans the whole term — but on an
     * expected leg truncated to a call or a prepayment there can be, and a drawdown
     * scheduled for after the instrument is expected to have been redeemed is not an
     * expected cash flow. Carrying it would put an outflow beyond the last inflow and
     * hand the solver a vector whose final sign change is an artefact of an
     * assumption rather than a feature of the instrument.
     */
    public static List<CashFlow> futureLeg(
        ScheduleBlueprint blueprint,
        InstalmentLadder ladder,
        FlowKind terminalKind,
        InstrumentSide side) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(ladder, "ladder");
        Objects.requireNonNull(terminalKind, "terminalKind");
        Objects.requireNonNull(side, "side");
        requireTerminalKind(terminalKind);

        List<InstalmentLadder.Rung> rungs = ladder.rungs();
        int lastPeriod = rungs.get(rungs.size() - 1).periodIndex();
        List<CashFlow> flows = new ArrayList<>();

        for (Tranche draw : ScheduleBuilder.drawSchedule(
            blueprint.disbursement(), blueprint.valueDate())) {
            if (draw.periodIndex() == 0 || draw.periodIndex() > lastPeriod) {
                continue;
            }
            flows.add(CashFlow.of(
                draw.drawnOn(), draw.periodIndex(),
                signedAdvance(draw.amount(), side).atPresentationScale(),
                FlowKind.DISBURSEMENT));
        }

        Money terminal = ladder.terminalBalance();
        boolean splits = billsSeparateComponents(blueprint);
        for (InstalmentLadder.Rung rung : rungs) {
            Money billed = rung.periodIndex() == lastPeriod && terminal.isPositive()
                ? rung.total().minus(terminal)
                : rung.total();
            flows.addAll(rungFlows(rung, billed, splits, side));
        }
        if (terminal.isPositive()) {
            flows.add(CashFlow.of(
                rungs.get(rungs.size() - 1).dueOn(), lastPeriod,
                signedReceipt(terminal, side).atPresentationScale(), terminalKind));
        }
        return List.copyOf(flows);
    }

    // ----------------------------------------------------------- derived amounts

    /**
     * The net integral fee, signed: positive where the net is income.
     *
     * <p>Only {@code INTEGRAL} postings count. A commitment fee amortised over the
     * commitment period, an as-incurred servicing charge and a separate performance
     * obligation are all real postings that simply do not belong to the initial
     * carrying amount, and dropping them here is the whole point of having the rule
     * set resolve classification before the projection runs.
     */
    public static Money netIntegralFee(List<FeePosting> fees, Currency currency) {
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(currency, "currency");
        Money net = Money.zero(currency);
        for (FeePosting fee : fees) {
            if (!fee.entersInitialCarryingAmount()) {
                continue;
            }
            requireFeeCurrency(fee, currency);
            net = net.plus(fee.amount());
        }
        return net;
    }

    /**
     * The principal actually advanced on the anchor date.
     *
     * <p><b>Not the notional</b>, and the difference is the whole reason this is a
     * method. On a tranched facility the amount advanced at initial recognition is
     * the first draw; the sanctioned total arrives over the drawdown period. Using
     * the notional here would open the roll-forward at a balance the borrower does
     * not owe yet and would make IC-1 pass on a figure that is wrong by every
     * undrawn tranche.
     *
     * <p>Every draw the profile dates at period ordinal zero is aggregated, because
     * more than one can land at closure and each of them is an advance on the anchor.
     */
    public static Money amountAdvancedAtInception(ScheduleBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        Money advanced = Money.zero(blueprint.currency());
        for (Tranche draw : ScheduleBuilder.drawSchedule(
            blueprint.disbursement(), blueprint.valueDate())) {
            if (draw.periodIndex() == 0) {
                advanced = advanced.plus(draw.amount());
            }
        }
        return advanced;
    }

    /**
     * The gross carrying amount at initial recognition, in the ledger's signed
     * convention.
     *
     * <p>{@code sign x advanced - netIntegralFee}, which is exactly
     * {@code -(net cash flow at inception)} on the vector this class builds — the
     * figure the solver takes as its target and the figure the amortisation engine
     * opens its roll-forward with.
     *
     * <p>On an asset: 1,000,000 advanced less 5,000 of net fee income is 995,000, the
     * asset is recorded below par, and it accretes back up — which is why the EIR
     * exceeds the contractual rate (INV-2). On a liability the sign reverses and the
     * fee term does not: 1,000,000 of proceeds with 10,000 of issue costs paid gives
     * {@code -1,000,000 - (-10,000) = -990,000}. Both readings reduce the magnitude
     * of the carrying amount by the net integral amount received, which is the same
     * economic statement said twice.
     */
    public static Money initialCarryingAmount(
        Money amountAdvanced, Money netIntegralFee, InstrumentSide side) {

        Objects.requireNonNull(amountAdvanced, "amountAdvanced");
        Objects.requireNonNull(netIntegralFee, "netIntegralFee");
        Objects.requireNonNull(side, "side");
        return signedReceipt(amountAdvanced, side).minus(netIntegralFee).atPresentationScale();
    }

    /**
     * Whether the profile bills principal and interest as separate contractual lines.
     *
     * <p>True where the <em>principal</em> schedule is the contractual primitive and
     * the instalment is its consequence; false where the instalment is fixed and the
     * principal falls out as the residual after interest. That is the same division
     * {@code ScheduleBuilder} uses to decide which component it rounds to the paise,
     * and keeping the two answers derived from one predicate is what stops the vector
     * disagreeing with the ladder it was built from.
     */
    public static boolean billsSeparateComponents(ScheduleBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        return blueprint.principal() instanceof PrincipalProfile.EqualPrincipal
            || blueprint.principal() instanceof PrincipalProfile.Sculpted
            || blueprint.principal() instanceof PrincipalProfile.BulletAtMaturity
            || blueprint.principal() instanceof PrincipalProfile.NoneUntilMaturity;
    }

    /**
     * Invariant ST-6: the projected tranches sum to the notional, and none is dated
     * before the value date.
     *
     * <p>Two claims in one result because they fail together in practice. A facility
     * loaded with a missing tranche and a facility loaded with a tranche dated before
     * financial closure are the same data-quality defect seen from two sides, and the
     * consequence is identical: the ladder amortises a balance the draws never
     * produced. The date limb is checked first because a draw before the anchor is
     * rejected outright by {@link FlowVector} and would surface as an exception rather
     * than as a breach, which is a worse audit record than a named invariant failure.
     */
    public static InvariantResult tranchesSumToNotional(ScheduleBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        List<Tranche> draws = ScheduleBuilder.drawSchedule(
            blueprint.disbursement(), blueprint.valueDate());
        int early = 0;
        for (Tranche draw : draws) {
            if (draw.drawnOn().isBefore(blueprint.valueDate())) {
                early++;
            }
        }
        if (early > 0) {
            return InvariantResult.fail(
                InvariantId.ST_6,
                early + " of " + draws.size() + " projected draws are dated before the value date "
                    + blueprint.valueDate() + "; a drawdown cannot precede initial recognition, and"
                    + " the anchor is what every discount factor in the vector is measured from",
                BigDecimal.valueOf(early));
        }
        Money drawn = Money.zero(blueprint.currency());
        for (Tranche draw : draws) {
            drawn = drawn.plus(draw.amount());
        }
        return InvariantResult.ofMoney(
            InvariantId.ST_6,
            "the " + draws.size() + " projected draws sum to the notional",
            blueprint.notional(),
            drawn);
    }

    /**
     * The convention both legs license, checked on the vectors that will actually be
     * discounted.
     *
     * <p>Delegates the rule to {@link ConventionSelector} rather than restating it —
     * the periodic-index precondition is stated once in the engine and this is not
     * the place it gets a second opinion. What is decided here is only the
     * <em>combination</em>: periodic indexing is taken only where <b>both</b> legs
     * pass. The two legs are discounted by different consumers — the EIR is solved
     * over the expected leg, the 10% test and the catch-up run over the contractual
     * one — and letting them carry different conventions would put a convention
     * difference inside a comparison built to isolate a cash-flow difference.
     *
     * <p>A calendar with no whole number of periods in a year — weekly, fortnightly,
     * seasonal, custom — reports {@code periodsPerYear() == 0}, which
     * {@link FlowVector#periodicIndexEligible(int)} declines without arithmetic. That
     * is ST-10 falling out of the check rather than being special-cased, and it is why
     * a crop-cycle loan cannot be discounted on ordinals.
     */
    public static ConventionSelector.Choice convention(
        ScheduleBlueprint blueprint, FlowVector contractual, FlowVector expected) {

        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(expected, "expected");
        int periodsPerYear = blueprint.calendar().frequency().periodsPerYear();
        ConventionSelector.Choice onContractual =
            ConventionSelector.choose(contractual, periodsPerYear, blueprint.dayCount());
        if (contractual.equals(expected)) {
            return onContractual;
        }
        ConventionSelector.Choice onExpected =
            ConventionSelector.choose(expected, periodsPerYear, blueprint.dayCount());
        if (onContractual.periodicIndexEligible() && onExpected.periodicIndexEligible()) {
            return onContractual;
        }
        // Return the leg that declined, not an arbitrary one. Both choices carry the
        // same actual-date convention, and only the declining leg's basis says which
        // leg voided the optimisation — which is the one fact a reviewer asking why a
        // vector was discounted on dates actually needs.
        return onContractual.periodicIndexEligible() ? onExpected : onContractual;
    }

    // ------------------------------------------------------------------ internals

    private static List<CashFlow> rungFlows(
        InstalmentLadder.Rung rung, Money billed, boolean splits, InstrumentSide side) {

        LocalDate dueOn = rung.dueOn();
        int period = rung.periodIndex();
        if (splits && !billed.isZero() && !rung.principal().isZero() && !rung.interest().isZero()
            && sumsToBill(rung, billed)) {
            return List.of(
                CashFlow.of(dueOn, period,
                    signedReceipt(rung.principal(), side).atPresentationScale(), FlowKind.PRINCIPAL),
                CashFlow.of(dueOn, period,
                    signedReceipt(rung.interest(), side).atPresentationScale(), FlowKind.INTEREST));
        }
        return List.of(CashFlow.of(
            dueOn, period, signedReceipt(billed, side).atPresentationScale(), kindOf(rung)));
    }

    /**
     * Whether the rung's components add up to what it bills, at presentation scale.
     *
     * <p>Compared at presentation scale because that is the scale a bill is stated
     * at. A sub-paise difference between the columns and the total is the rounding
     * residue and belongs in the reconciliation, not in a decision about how many
     * flows to emit; a difference that survives rounding to paise is a real
     * discrepancy and the bill wins.
     */
    private static boolean sumsToBill(InstalmentLadder.Rung rung, Money billed) {
        return rung.principal().plus(rung.interest()).atPresentationScale()
            .compareTo(billed.atPresentationScale()) == 0;
    }

    /**
     * What a rung's single flow represents, for traceability only.
     *
     * <p>Deliberately the same rule {@code OptionalityResolver} applies to the same
     * rung, so that a vector this class publishes and a vector the resolver solved an
     * alternative over describe the flows the same way. Kind never affects
     * discounting, so the two could differ without changing a rate — which is exactly
     * why they should not: a reviewer comparing the published leg against the
     * alternative it was chosen over should not have to work out whether a label
     * changed meaning between two stages.
     */
    private static FlowKind kindOf(InstalmentLadder.Rung rung) {
        if (rung.principal().isZero()) {
            return FlowKind.INTEREST;
        }
        return rung.interest().isZero() ? FlowKind.PRINCIPAL : FlowKind.COMBINED_EMI;
    }

    /** An advance: an outflow to the holder of an asset, an inflow to the holder of a liability. */
    private static Money signedAdvance(Money amount, InstrumentSide side) {
        return side == InstrumentSide.LIABILITY ? amount : amount.negate();
    }

    /** A receipt under the contract: an inflow on an asset, an outflow on a liability. */
    private static Money signedReceipt(Money amount, InstrumentSide side) {
        return side == InstrumentSide.LIABILITY ? amount.negate() : amount;
    }

    private static void requireTerminalKind(FlowKind terminalKind) {
        if (terminalKind != FlowKind.BALLOON && terminalKind != FlowKind.RESIDUAL_VALUE) {
            throw new IllegalArgumentException(
                "a terminal lump is a BALLOON or a RESIDUAL_VALUE, got " + terminalKind
                    + ". The two are arithmetically identical and are different assets to a"
                    + " controller, which is why the label is required rather than derived");
        }
    }

    private static void requireLadderCurrency(ScheduleBlueprint blueprint, InstalmentLadder ladder) {
        if (!ladder.currency().equals(blueprint.currency())) {
            throw new IllegalArgumentException(
                "the ladder is " + ladder.currency().getCurrencyCode() + " and the blueprint is "
                    + blueprint.currency().getCurrencyCode());
        }
    }

    private static void requireFeeCurrency(FeePosting fee, Currency currency) {
        if (!fee.amount().currency().equals(currency)) {
            throw new IllegalArgumentException(
                "fee " + fee.feeCode() + " is " + fee.amount().currency().getCurrencyCode()
                    + " and the contract is " + currency.getCurrencyCode());
        }
    }

    /**
     * One assembled leg: the vector, the figures it was derived from, and what the
     * assembly could assert.
     *
     * <p>The derived figures are carried rather than recomputed by the caller because
     * IC-1 is a comparison between two independent routes to the carrying amount, and
     * a caller that derives its own second route has replaced the comparison with a
     * tautology.
     *
     * @param vector                  the flows, inception leg included
     * @param amountAdvancedAtInception principal advanced on the anchor date — the
     *     first draw on a tranched facility, not the sanctioned total
     * @param netIntegralFee          signed net of the {@code INTEGRAL} postings;
     *     positive where the net is income
     * @param initialCarryingAmount   gross carrying amount at initial recognition,
     *     signed: positive for an asset advanced, negative for a liability raised
     * @param side                    whose perspective the signs were taken from
     * @param invariants              ST-6 where the profile is tranched
     */
    public record Assembly(
        FlowVector vector,
        Money amountAdvancedAtInception,
        Money netIntegralFee,
        Money initialCarryingAmount,
        InstrumentSide side,
        List<InvariantResult> invariants) {

        public Assembly {
            Objects.requireNonNull(vector, "vector");
            Objects.requireNonNull(amountAdvancedAtInception, "amountAdvancedAtInception");
            Objects.requireNonNull(netIntegralFee, "netIntegralFee");
            Objects.requireNonNull(initialCarryingAmount, "initialCarryingAmount");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(invariants, "invariants");
            invariants = List.copyOf(invariants);
        }

        public boolean allSatisfied() {
            return invariants.stream().allMatch(InvariantResult::satisfied);
        }
    }
}
