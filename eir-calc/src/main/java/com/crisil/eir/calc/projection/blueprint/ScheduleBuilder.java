package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Step 3 of the blueprint pipeline: a {@link ScheduleBlueprint} resolved into the
 * contractual {@link InstalmentLadder}
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 8</a>,
 * dimensions § 2.1–§ 2.4 and § 2.8).
 *
 * <p>Nothing here is a policy choice. Optionality, behaviour and expected life are
 * later stages by design — this stage answers only "what does the contract say is
 * due, and when", which is the one question in the pipeline with a single right
 * answer. That separation is what lets the contractual leg be reconciled against
 * the lending system independently of every estimate layered on top of it
 * (ADR-0004).
 *
 * <h2>The five things this class decides, and why each is decided here</h2>
 *
 * <p><b>1. The balance is rolled at working precision and presented once.</b> The
 * running balance is carried at 28 digits, because a balance re-rounded to paise
 * every period drifts: on the Case 1 loan the re-rounded roll gives 529,815.59 at
 * month 12 against the golden 529,815.61 ({@code ContractualBalance}). What is
 * reduced to currency scale is what a lender bills —
 * {@link InstalmentLadder.Rung#total()} always, and whichever of the principal and
 * interest components the contract fixes.
 *
 * <p>Which component that is follows from the profile, and the split is arranged so
 * that <b>the columns sum to the bill</b>: a published movement schedule whose
 * columns do not add up is a defect even where every figure is individually correct
 * (<a href="../../../../../../../../../docs/03-calculation-spec.md">03 § 5.7</a>).
 * Where the instalment is the primitive — an annuity, a balloon, a step ladder — the
 * interest is the period's accrual at working precision and the principal is the
 * remainder of the bill. Where the <em>principal</em> is the primitive — equal
 * principal, a sculpted ladder, a bullet, an interest-only period — the principal is
 * billed to the paise and the interest is the remainder. On fixture S1 that second
 * reading is the difference between an interest total of 125,000.00, which ties to
 * 1,125,000.00 of cash against 1,000,000 of principal, and 124,999.99, which ties to
 * nothing. Interest that is not billed at all — capitalised, or deferred — is always
 * the accrual, which is what keeps ST-4 a real comparison between the two.
 *
 * <p><b>2. The last rung closes the ladder on its intended terminal.</b> Its
 * principal is set to whatever brings the balance to the terminal amount exactly,
 * rather than being derived from the billed instalment like every other rung. This
 * is not a plug and it is not optional. A rounded instalment leaves a residue —
 * 47,073.472223 billed as 47,073.47 compounds to 0.059969 over 24 periods — and
 * that residue has to land somewhere it can be seen. Deriving the last principal
 * from the billed instalment lands it in the closing balance, where it is either a
 * balance the borrower still owes after their final payment or, when the instalment
 * rounded <em>up</em>, a negative balance that {@code Rung} rightly refuses as
 * principal the borrower never owed. Closing the balance instead surfaces the
 * residue as {@code principal + interest - total} on the final rung, at working
 * precision and at exactly its true size. On Case 1 that difference is 0.059969 to
 * the digit — the final rung bills 47,073.47 against 46,607.4554 of principal and
 * 466.0746 of interest.
 *
 * <p><b>3. The residue policy governs the billed instalment, not the balance.</b>
 * {@link ResiduePolicy} is applied to the amortising instalment stream before the
 * ladder is materialised, so a plug shows up where a lender would actually bill it.
 * Under {@code FINAL_PERIOD_PLUG} the final instalment becomes 47,073.53 and the
 * residue reduces to an unpresentable sub-paise difference; under
 * {@code LMS_AUTHORITATIVE} the residue stays fully visible, which is the
 * documented safe failure for a schedule the engine derived rather than consumed.
 * A residue is never resolved by tolerance (ADR-0002).
 *
 * <p><b>4. Capitalised interest is negative principal.</b> During a
 * {@code FULL_INTEREST_CAPITALISED} holiday the accrual joins the balance, so the
 * rung's principal movement is the negative of the accrual. That keeps invariant
 * ST-3 — scheduled principal plus the terminal balance equals principal advanced —
 * a true statement about a capitalising instrument rather than one that has to be
 * excused. On fixture S5 the twelve holiday rungs carry -126,825.03 of principal
 * between them and the 24 instalments carry +1,126,825.03, and the two sum to the
 * 1,000,000 advanced.
 *
 * <p><b>5. Disbursements are not rungs.</b> A rung is something the borrower owes;
 * a drawdown is money going the other way. Signing a draw as a negative rung would
 * make {@link InstalmentLadder#totalCash()} and ST-3 both meaningless, so draws
 * stay out of the ladder and reach the flow vector through
 * {@link #drawSchedule(DisbursementProfile, LocalDate)}. What they do contribute is
 * the balance the ladder amortises: it is built from the <b>cumulative</b> drawn
 * balance, and interest accrues on what has actually been advanced.
 *
 * <h2>One thing this class does not do</h2>
 *
 * <p>It does not convert an annual rate to a period rate.
 * {@link RateProfile#rateForPeriod(int)} is asked for period {@code t}'s rate and
 * that rate is used as-is, which is the only safe reading where the periods are not
 * uniform: on a seasonal crop-cycle calendar the gaps between due dates genuinely
 * differ, so "the periodic rate" is a different number in each period and only the
 * rate profile knows which. Dividing an annual rate by a period count here would
 * quietly apply a monthly rate to a six-month harvest gap, and produce a schedule
 * that looks right and bills a third of the interest.
 *
 * <h2>What this class refuses</h2>
 *
 * <p>{@link ScheduleBlueprint} rejects combinations that contradict themselves
 * (ST-11). This class rejects the narrower set that are coherent but that no
 * single-pass derivation can express — a tranche landing inside the amortisation
 * phase re-sizes the instalment mid-schedule, which is a {@code DISBURSEMENT_TIMING}
 * re-estimation and not a ladder — and it says which dimensions collided. It never
 * falls back to a shape that "nearly" fits: that produces a plausible number with
 * no trace, which is the failure class the engine exists to refuse.
 */
public final class ScheduleBuilder {

    private ScheduleBuilder() {
    }

    /**
     * The contractual ladder for a blueprint.
     *
     * @throws IllegalArgumentException where the combination of dimensions cannot
     *     be derived in one pass, naming the dimensions that collided; and where
     *     the residue policy is {@link ResiduePolicy#SPREAD_LAST_N}, which needs
     *     the spread width that only {@link #build(ScheduleBlueprint, int)} carries
     */
    public static InstalmentLadder build(ScheduleBlueprint blueprint) {
        return build(blueprint, 0);
    }

    /**
     * The contractual ladder, with an explicit width for
     * {@link ResiduePolicy#SPREAD_LAST_N}.
     *
     * <p>The width is a parameter rather than a blueprint field because it belongs
     * to the lending system's billing convention, not to the instrument: two
     * contracts with identical terms on different platforms can spread over
     * different tails. It is also why there is no default — silently spreading over
     * one period turns {@code SPREAD_LAST_N} into {@code FINAL_PERIOD_PLUG}, which
     * is a different recorded policy from the one in force, and a substituted
     * policy is exactly what a recorded policy exists to prevent.
     *
     * @param spreadPeriods trailing instalments that share the residue; ignored by
     *     every other policy
     */
    public static InstalmentLadder build(ScheduleBlueprint blueprint, int spreadPeriods) {
        Objects.requireNonNull(blueprint, "blueprint");
        Currency currency = blueprint.currency();
        Money zero = Money.zero(currency);

        ScheduleTerm term = ScheduleTerm.of(blueprint);
        List<LocalDate> dueDates = ScheduleDates.dueDates(
            blueprint.calendar(), blueprint.valueDate(), term.ladderPeriods());
        BigDecimal[] rates = periodicRates(blueprint, term.ladderPeriods());
        List<Tranche> draws = drawSchedule(blueprint.disbursement(), blueprint.valueDate());
        requireSupported(blueprint, term, draws, spreadPeriods);

        Money[] drawByPeriod = drawsByPeriod(term, draws, zero);
        Money advanced = blueprint.notional();

        // Phase A. The holiday's balance path depends on nothing downstream, so it
        // resolves first and hands the amortising phase the balance it starts from.
        List<Step> holiday = holidaySteps(blueprint, term, dueDates, rates, drawByPeriod, zero);
        Roll holidayRoll = roll(holiday, drawByPeriod[0], zero, false, zero);
        Money balanceAtAmortStart = holidayRoll.terminal();

        Money expectedTerminal =
            expectedTerminal(blueprint, balanceAtAmortStart, advanced, zero);

        List<Step> steps = new ArrayList<>(holiday);
        steps.addAll(repaymentSteps(
            blueprint, term, dueDates, rates, drawByPeriod, balanceAtAmortStart,
            expectedTerminal, zero));
        int last = steps.size() - 1;
        steps.set(last, steps.get(last).plusAddOn(expectedTerminal.atPresentationScale()));

        // A probe roll serves two purposes at once, and can, because the balance
        // path is independent of anything added to a billed total: it measures the
        // terminal residue the billed instalments leave, and it totals the interest
        // a deferred-simple holiday accrues so the lump can be placed.
        Roll probe = roll(steps, drawByPeriod[0], zero, false, zero);
        if (probe.deferredAccrual().isPositive()) {
            int settlement = settlementPeriod(blueprint, term, dueDates);
            steps.set(settlement - 1, steps.get(settlement - 1).plusAddOn(
                probe.deferredAccrual().atPresentationScale()));
        }
        Money residue = probe.terminal().minus(expectedTerminal);
        steps = applyResiduePolicy(blueprint, term, steps, residue, rates, spreadPeriods);

        Roll finalRoll = roll(steps, drawByPeriod[0], expectedTerminal, true, zero);
        List<InstalmentLadder.Rung> rungs = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            rungs.add(new InstalmentLadder.Rung(
                step.periodIndex(),
                step.dueOn(),
                finalRoll.principal().get(index),
                finalRoll.interest().get(index),
                finalRoll.total().get(index),
                finalRoll.balanceAfter().get(index)));
        }
        return InstalmentLadder.of(currency, rungs, advanced, expectedTerminal);
    }

    /** The adjusted due dates the ladder will use, without building it. */
    public static List<LocalDate> dueDates(ScheduleBlueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        return ScheduleDates.dueDates(
            blueprint.calendar(),
            blueprint.valueDate(),
            ScheduleTerm.of(blueprint).ladderPeriods());
    }

    // ------------------------------------------------------------------ draws

    /**
     * The draws to sign as negative flows in the vector.
     *
     * <p>Exposed rather than folded into the ladder because a rung is a receipt.
     * Tranched draws are the {@code projected} schedule, not the actual one, and
     * deliberately: policy is to strike the rate on the schedule agreed at
     * financial closure and re-estimate only when cumulative actual deviates past
     * tolerance
     * (<a href="../../../../../../../../../docs/03-calculation-spec.md">03 § 3.8</a>).
     * Rebuilding the contractual ladder off actuals the moment the first draw lands
     * would re-strike the rate on every drawdown, which is precisely the churn the
     * cumulative test exists to avoid — and it would leave no baseline for
     * {@link #cumulativeDrift} to measure against.
     */
    public static List<Tranche> drawSchedule(DisbursementProfile disbursement, LocalDate valueDate) {
        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(valueDate, "valueDate");
        return switch (disbursement) {
            case DisbursementProfile.Single single ->
                List.of(new Tranche(single.drawnOn(), 0, single.amount()));
            case DisbursementProfile.Tranched tranched -> tranched.projected();
            case DisbursementProfile.UtilisationDriven revolver ->
                List.of(new Tranche(valueDate, 0, revolver.notional()));
        };
    }

    /**
     * Cumulative principal actually advanced on or before {@code asOf}.
     *
     * <p>For a revolver this is the expected drawn balance and carries no date
     * sensitivity, because there is no drawdown schedule to be at a point in: the
     * limit is drawn and repaid at the borrower's discretion, which is why ACPIR 54
     * contemplates an approximation here rather than a projection.
     */
    public static Money drawnBy(DisbursementProfile disbursement, LocalDate asOf) {
        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(asOf, "asOf");
        return switch (disbursement) {
            case DisbursementProfile.Single single -> asOf.isBefore(single.drawnOn())
                ? Money.zero(single.amount().currency())
                : single.amount();
            case DisbursementProfile.Tranched tranched -> sumThrough(tranched.actual(), asOf,
                tranched.projected().get(0).amount().currency());
            case DisbursementProfile.UtilisationDriven revolver -> revolver.notional();
        };
    }

    /** Cumulative principal the schedule struck at closure said would be advanced by {@code asOf}. */
    public static Money projectedBy(DisbursementProfile disbursement, LocalDate asOf) {
        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(asOf, "asOf");
        if (disbursement instanceof DisbursementProfile.Tranched tranched) {
            return sumThrough(tranched.projected(), asOf,
                tranched.projected().get(0).amount().currency());
        }
        return drawnBy(disbursement, asOf);
    }

    /**
     * Signed cumulative deviation of actual drawdown from projected at
     * {@code asOf} — positive where the facility has drawn ahead of schedule.
     *
     * <p><b>Cumulative, never per draw.</b> Project-finance draws run over 24–48
     * months and individually never match the schedule; a per-draw comparison
     * reports a deviation on essentially every one of them, so keying re-estimation
     * to it churns the whole book for no informational gain and floods the movement
     * schedule with restatements that net to nothing. What carries information is
     * the running total: a tranche a month early followed by one a month late has
     * not changed the facility's economics.
     */
    public static Money cumulativeDrift(DisbursementProfile disbursement, LocalDate asOf) {
        return drawnBy(disbursement, asOf).minus(projectedBy(disbursement, asOf));
    }

    /**
     * Whether cumulative drift has breached the profile's tolerance — the
     * {@code DISBURSEMENT_TIMING} trigger (09 § 2.1, FR-512).
     *
     * <p>Only a tranched facility can fire it. A single advance has no schedule to
     * deviate from, and a revolver has no drawdown schedule at all, so reporting a
     * timing deviation on either would be reporting on a comparison that was never
     * made.
     *
     * <p>The driver this raises is a tag, not a decision. Whether
     * {@code DISBURSEMENT_TIMING} routes to a catch-up or to a prospective reset is
     * the versioned routing table's business (ADR-0006), and the projector's job
     * ends at saying the cumulative test failed.
     */
    public static boolean disbursementTimingTriggered(
        DisbursementProfile disbursement, LocalDate asOf) {

        Objects.requireNonNull(disbursement, "disbursement");
        Objects.requireNonNull(asOf, "asOf");
        if (!(disbursement instanceof DisbursementProfile.Tranched tranched)) {
            return false;
        }
        Money allowed = tranched.notional().times(tranched.cumulativeDeviationTolerance());
        return cumulativeDrift(tranched, asOf).abs().compareTo(allowed.abs()) > 0;
    }

    private static Money sumThrough(List<Tranche> tranches, LocalDate asOf, Currency currency) {
        Money total = Money.zero(currency);
        for (Tranche tranche : tranches) {
            if (!tranche.drawnOn().isAfter(asOf)) {
                total = total.plus(tranche.amount());
            }
        }
        return total;
    }

    // ------------------------------------------------------- validation

    /**
     * The combinations that are coherent but not derivable in a single pass.
     *
     * <p>Distinct from {@link ScheduleBlueprint}'s coherence check, which rejects
     * combinations that contradict themselves. These contradict nothing; they
     * simply need something this stage does not have — a reduction fraction, a
     * re-solve mid-schedule, a second instrument. Each message names the dimensions
     * that collided, because "unsupported" on its own sends a configuration analyst
     * back to the matrix rather than to the field they set wrongly.
     */
    private static void requireSupported(
        ScheduleBlueprint blueprint, ScheduleTerm term, List<Tranche> draws, int spreadPeriods) {

        Moratorium moratorium = blueprint.moratorium();
        InterestServicing servicing = blueprint.servicing();
        PrincipalProfile principal = blueprint.principal();

        if (moratorium.kind() == Moratorium.MoratoriumKind.PARTIAL_SERVICING) {
            throw new IllegalArgumentException(
                "Moratorium kind PARTIAL_SERVICING bills a reduced instalment during the holiday,"
                    + " and Moratorium carries no reduction fraction to bill it from. Model the"
                    + " concession explicitly — a PRINCIPAL_ONLY holiday where the reduced"
                    + " instalment is the interest, or a Sculpted principal ladder where it is"
                    + " not — so that the amount billed is a contractual term rather than a"
                    + " builder default");
        }
        if (moratorium.kind() == Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE
            && !(servicing instanceof InterestServicing.DeferredSimple)) {
            throw new IllegalArgumentException(
                "a FULL_INTEREST_DEFERRED_SIMPLE holiday needs InterestServicing.DeferredSimple to"
                    + " say when the accrued lump falls due; servicing is " + servicing.label()
                    + ". Deferral without a settlement date is an accrual with nowhere to go");
        }
        if (servicing.compounds() && !moratorium.isPresent()
            && !(principal instanceof PrincipalProfile.NoneUntilMaturity
                || principal instanceof PrincipalProfile.BulletAtMaturity)) {
            throw new IllegalArgumentException(
                "InterestServicing.CAPITALISED_EACH_PERIOD with no moratorium capitalises every"
                    + " period's interest, so nothing is ever billed; principal profile "
                    + principal.label() + " bills interest as part of each instalment. Capitalise"
                    + " through a FULL_INTEREST_CAPITALISED holiday for a construction or study"
                    + " phase, or state NONE_UNTIL_MATURITY for a wholly accreting instrument");
        }
        if (servicing instanceof InterestServicing.DeferredSimple && !moratorium.isPresent()
            && !(principal instanceof PrincipalProfile.BulletAtMaturity)) {
            throw new IllegalArgumentException(
                "InterestServicing.DEFERRED_SIMPLE with no moratorium defers every period's"
                    + " interest to one settlement, which only a non-amortising instrument can"
                    + " do; principal profile " + principal.label() + " amortises before then."
                    + " Pair the deferral with a moratorium, or with BULLET_AT_MATURITY for the"
                    + " FITL shape");
        }
        if (principal instanceof PrincipalProfile.NoneUntilMaturity
            && servicing instanceof InterestServicing.ServicedThenCombined) {
            throw new IllegalArgumentException(
                "principal profile NONE_UNTIL_MATURITY services nothing before maturity, so"
                    + " InterestServicing.SERVICED_THEN_COMBINED has no interest-only phase to"
                    + " describe. These are two different instruments, not one with an option");
        }
        if (principal instanceof PrincipalProfile.NoneUntilMaturity
            && servicing instanceof InterestServicing.DeferredSimple) {
            throw new IllegalArgumentException(
                "principal profile NONE_UNTIL_MATURITY accretes its interest into the redemption,"
                    + " which compounds; InterestServicing.DEFERRED_SIMPLE accrues simple. On a"
                    + " 12-period holiday the two differ by 6,825.03 and 34.6 bp, so the engine"
                    + " will not pick one");
        }
        if (blueprint.residuePolicy() == ResiduePolicy.SPREAD_LAST_N && spreadPeriods < 1) {
            throw new IllegalArgumentException(
                "residue policy SPREAD_LAST_N needs the number of trailing instalments to spread"
                    + " over; call build(blueprint, spreadPeriods). Defaulting it to one would"
                    + " quietly run FINAL_PERIOD_PLUG under a different policy name");
        }
        if (!blueprint.notional().atPresentationScale()
            .equals(blueprint.disbursement().notional().atPresentationScale())) {
            throw new IllegalArgumentException(
                "invariant ST-6: disbursement profile " + blueprint.disbursement().label()
                    + " advances " + blueprint.disbursement().notional() + " but the blueprint"
                    + " notional is " + blueprint.notional());
        }
        int firstAmortising = term.firstAmortisingPeriod();
        for (Tranche tranche : draws) {
            if (tranche.drawnOn().isBefore(blueprint.valueDate())) {
                throw new IllegalArgumentException(
                    "invariant ST-6: tranche of " + tranche.amount() + " is dated "
                        + tranche.drawnOn() + ", before the value date " + blueprint.valueDate());
            }
            if (tranche.periodIndex() >= firstAmortising) {
                throw new IllegalArgumentException(
                    "a tranche drawn in period " + tranche.periodIndex() + " lands inside the"
                        + " amortising phase, which begins at period " + firstAmortising
                        + " under principal profile " + blueprint.principal().label()
                        + ". A draw after amortisation starts re-sizes the instalment"
                        + " mid-schedule, which is a DISBURSEMENT_TIMING re-estimation against a"
                        + " revised blueprint — not a ladder this builder can derive in one pass."
                        + " Lengthen the moratorium or the interest-only phase to cover the"
                        + " drawdown period, which is what a construction period is");
            }
        }
        if (principal instanceof PrincipalProfile.Sculpted sculpted) {
            for (PrincipalProfile.PrincipalStep step : sculpted.ladder()) {
                if (step.periodIndex() < firstAmortising
                    || step.periodIndex() > term.ladderPeriods()) {
                    throw new IllegalArgumentException(
                        "sculpted principal step at period " + step.periodIndex() + " falls"
                            + " outside the amortising window [" + firstAmortising + ", "
                            + term.ladderPeriods() + "]. A step inside a repayment holiday repays"
                            + " principal the holiday suspended; a step past maturity is never"
                            + " billed");
                }
            }
        }
    }

    // ------------------------------------------------------------- phases

    /** Rungs for the repayment holiday; empty where there is none. */
    private static List<Step> holidaySteps(
        ScheduleBlueprint blueprint,
        ScheduleTerm term,
        List<LocalDate> dueDates,
        BigDecimal[] rates,
        Money[] drawByPeriod,
        Money zero) {

        Accrual accrual = switch (blueprint.moratorium().kind()) {
            case PRINCIPAL_ONLY -> Accrual.CHARGE;
            case FULL_INTEREST_CAPITALISED -> Accrual.COMPOUND;
            case FULL_INTEREST_DEFERRED_SIMPLE -> Accrual.DEFER;
            case NONE, PARTIAL_SERVICING -> Accrual.CHARGE;
        };
        List<Step> steps = new ArrayList<>();
        for (int period = 1; period <= term.moratoriumPeriods(); period++) {
            steps.add(new Step(period, dueDates.get(period - 1), rates[period],
                drawByPeriod[period], accrual, zero, null, zero));
        }
        return steps;
    }

    /**
     * Rungs from the end of the holiday to maturity: the interest-only prefix, then
     * the principal profile.
     *
     * <p>The two are one list rather than two phases with their own sizing because
     * an interest-only period leaves the balance untouched — principal zero,
     * interest billed — so the amortising leg starts from the same balance either
     * way and there is nothing to hand between them.
     */
    private static List<Step> repaymentSteps(
        ScheduleBlueprint blueprint,
        ScheduleTerm term,
        List<LocalDate> dueDates,
        BigDecimal[] rates,
        Money[] drawByPeriod,
        Money balanceAtStart,
        Money expectedTerminal,
        Money zero) {

        List<Step> steps = new ArrayList<>();
        int first = term.firstAmortisingPeriod();
        for (int period = term.moratoriumPeriods() + 1; period < first; period++) {
            steps.add(new Step(period, dueDates.get(period - 1), rates[period],
                drawByPeriod[period], Accrual.CHARGE, zero, null, zero));
        }

        int count = term.amortisingPeriods();
        int lastPeriod = term.ladderPeriods();
        BigDecimal[] discount = cumulativeDiscountFactors(rates, first, count);
        Accrual amortisingAccrual = amortisingAccrual(blueprint);
        Money[] billed = billedInstalments(
            blueprint, term, rates, discount, balanceAtStart, expectedTerminal, count);
        Money[] fixed = fixedPrincipals(blueprint, term, balanceAtStart, expectedTerminal, count);

        for (int offset = 0; offset < count; offset++) {
            int period = first + offset;
            boolean isLast = period == lastPeriod;
            // A wholly accreting instrument accretes right up to the redemption and
            // then bills the lot. The final period is therefore the one period on
            // which its interest is charged rather than capitalised — without that
            // switch the redemption rung would bill nothing and the balance would
            // have to be plugged to zero.
            Accrual accrual = amortisingAccrual == Accrual.COMPOUND && isLast
                ? Accrual.CHARGE
                : amortisingAccrual;
            steps.add(new Step(period, dueDates.get(period - 1), rates[period],
                drawByPeriod[period], accrual,
                fixed == null ? null : fixed[offset],
                billed == null ? null : billed[offset],
                zero));
        }
        return steps;
    }

    /**
     * How interest behaves once the holiday is over.
     *
     * <p>A capitalising servicing profile does <em>not</em> keep capitalising past
     * the holiday, and that is the substance of fixture S5: twelve periods where
     * nothing is paid and interest compounds into the balance, then 24 instalments
     * of 53,043.57 which service interest in the ordinary way on the grown balance.
     * Servicing says what happens to interest that is not billed; the moratorium
     * says which periods those are. Letting the servicing profile suppress billing
     * for the whole life would turn every education loan into a zero-coupon bond.
     */
    private static Accrual amortisingAccrual(ScheduleBlueprint blueprint) {
        if (blueprint.servicing() instanceof InterestServicing.DiscountedUpfront) {
            return Accrual.NONE;
        }
        if (blueprint.principal() instanceof PrincipalProfile.NoneUntilMaturity) {
            return Accrual.COMPOUND;
        }
        if (blueprint.servicing() instanceof InterestServicing.DeferredSimple
            && !blueprint.moratorium().isPresent()) {
            return Accrual.DEFER;
        }
        return Accrual.CHARGE;
    }

    /**
     * The terminal balance the ladder is sized to reach — invariant ST-5.
     *
     * <p>Zero for a fully-amortising profile, and non-zero for three reasons that
     * compose. A balloon or lease residual states its own terminal. A sculpted
     * ladder implies one, as whatever its supplied steps leave behind — retained
     * rather than plugged, because a ladder sized to projected free cash flow that
     * does not clear the facility has told the engine something and silently
     * amortising it away would discard it. And a {@code BALLOON_ARREARS} holiday
     * sends its capitalised arrears to maturity instead of amortising them.
     *
     * <p>The arrears term is deliberately not added to a sculpted terminal: a
     * sculpted terminal is already measured against the balance the holiday
     * produced, arrears included, so adding them would count them twice.
     */
    private static Money expectedTerminal(
        ScheduleBlueprint blueprint, Money balanceAtAmortStart, Money advanced, Money zero) {

        if (blueprint.principal() instanceof PrincipalProfile.Sculpted sculpted) {
            Money terminal = balanceAtAmortStart.minus(sculpted.total());
            if (terminal.isNegative()) {
                throw new IllegalArgumentException(
                    "sculpted ladder repays " + sculpted.total() + " against a balance of "
                        + balanceAtAmortStart + " at the start of amortisation, so it over-amortises"
                        + " by " + terminal.negate() + " — principal the borrower never owed");
            }
            return terminal;
        }
        Money terminal = blueprint.principal() instanceof PrincipalProfile.Balloon balloon
            ? balloon.terminalAmount()
            : zero;
        if (blueprint.moratorium().termEffect()
            == Moratorium.MoratoriumTermEffect.BALLOON_ARREARS) {
            Money arrears = balanceAtAmortStart.minus(advanced);
            if (arrears.isPositive()) {
                terminal = terminal.plus(arrears);
            }
        }
        if (terminal.compareTo(balanceAtAmortStart) >= 0) {
            throw new IllegalArgumentException(
                "terminal amount " + terminal + " is at least the " + balanceAtAmortStart
                    + " outstanding when amortisation begins, so no positive instalment exists."
                    + " That instrument is a bullet or a discount instrument, not a balloon");
        }
        return terminal;
    }

    // ---------------------------------------------------------- arithmetic

    /**
     * The billed instalment per amortising period, or {@code null} for a profile
     * whose instalment is the consequence of a principal schedule rather than the
     * cause of it.
     *
     * <p>The split matters because the rounding residue only exists on this side.
     * Where the instalment is the primitive — an annuity, a balloon, a step ladder —
     * it is billed to the paise and the principal falls out as the remainder, so the
     * schedule cannot close exactly and a residue policy has to say where the
     * difference goes. Where the <em>principal</em> is the primitive — equal
     * principal, a bullet, a sculpted ladder — the instalment is derived from it and
     * the only rounding is the paise on each bill.
     */
    private static Money[] billedInstalments(
        ScheduleBlueprint blueprint,
        ScheduleTerm term,
        BigDecimal[] rates,
        BigDecimal[] discount,
        Money balanceAtStart,
        Money expectedTerminal,
        int count) {

        PrincipalProfile profile = blueprint.principal();
        boolean instalmentIsPrimitive = profile instanceof PrincipalProfile.LevelAnnuity
            || profile instanceof PrincipalProfile.Balloon
            || profile instanceof PrincipalProfile.StepLadder;
        if (!instalmentIsPrimitive) {
            return null;
        }
        int first = term.firstAmortisingPeriod();
        Money amortising = balanceAtStart.minus(expectedTerminal.times(discount[count]));
        if (profile instanceof PrincipalProfile.StepLadder ladder) {
            return stepped(ladder, amortising, discount, count);
        }
        return level(amortising, rates, discount, first, count);
    }

    /**
     * {@code A = P * i / (1 - (1+i)^-n)} over the amortising balance.
     *
     * <p>Delegated to {@link Annuity} wherever the rate is uniform across the
     * amortising phase, which is every fixed-rate instrument and every floating one
     * projected on its current rate. One formula in one place cannot drift between
     * the five shapes that need it.
     *
     * <p>The general form is the same statement written so that a per-period rate
     * ladder can be honoured: the instalment is the amortising balance divided by
     * the sum of the cumulative discount factors, which for a constant rate is
     * exactly {@code (1-(1+i)^-n)/i}. A step-coupon bond amortising on an annuity
     * needs it, and a closed form does not exist for it.
     */
    private static Money[] level(
        Money amortising, BigDecimal[] rates, BigDecimal[] discount, int first, int count) {

        if (!amortising.isPositive()) {
            throw new IllegalArgumentException(
                "the amortising balance came to " + amortising + "; the terminal amount discounts"
                    + " to at least the outstanding balance, so the instalments would have to be"
                    + " negative");
        }
        Money instalment;
        if (uniform(rates, first, count)) {
            instalment = Annuity.instalment(amortising, rates[first], count);
        } else {
            BigDecimal annuityFactor = BigDecimal.ZERO;
            for (int offset = 1; offset <= count; offset++) {
                annuityFactor = annuityFactor.add(discount[offset], Precision.WORKING);
            }
            instalment = amortising.dividedBy(annuityFactor);
        }
        Money billed = instalment.atPresentationScale();
        Money[] schedule = new Money[count];
        for (int offset = 0; offset < count; offset++) {
            schedule[offset] = billed;
        }
        return schedule;
    }

    /**
     * The step ladder, solved for its base instalment by direct summation.
     *
     * <pre>
     * A0 = (P - PV(terminal)) / SUM_t ( f^k(t) * D_t )     k(t) = (t-1) / everyN
     * </pre>
     *
     * <p>Summation rather than a closed form, following
     * {@code StepScheduleProjector}: the closed form exists only for a step at
     * every period and would have to be abandoned the first time a product steps
     * every sixth one, which fixture S3 does. {@code n} terms at working precision
     * is cheap, and the projection runs once per contract rather than once per
     * period.
     *
     * <p><b>The ladder steps the instalment the borrower is billed, not the base
     * before it was rounded.</b> A contract that says "+10% every six months" steps
     * the 40,861.10 on the repayment schedule, so the second step is 44,947.21 and
     * the fourth is 54,386.12 — which is fixture S3, and a total cash of
     * 1,137,818.16 over the ladder. Compounding the unrounded base instead gives
     * 49,441.94 and 54,386.13, two paise higher on the upper rungs, and there is no
     * reading of the contract under which the borrower owes them: nobody was ever
     * billed the unrounded base for the step to be a percentage of. This differs by
     * those two paise from the pre-blueprint {@code StepScheduleProjector}, and the
     * fixture governs (09 preamble).
     */
    private static Money[] stepped(
        PrincipalProfile.StepLadder ladder, Money amortising, BigDecimal[] discount, int count) {

        if (!amortising.isPositive()) {
            throw new IllegalArgumentException(
                "the amortising balance came to " + amortising + "; a step ladder cannot amortise"
                    + " a non-positive balance");
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (int offset = 1; offset <= count; offset++) {
            BigDecimal multiplier = stepMultiplier(ladder, offset);
            weighted = weighted.add(multiplier.multiply(discount[offset], Precision.WORKING),
                Precision.WORKING);
        }
        if (weighted.signum() <= 0) {
            throw new IllegalArgumentException(
                "the step ladder discounts to a non-positive weight; factor "
                    + ladder.factor().toPlainString() + " over " + count
                    + " periods is not a schedule");
        }
        Money base = amortising.dividedBy(weighted).atPresentationScale();
        Money[] schedule = new Money[count];
        for (int offset = 1; offset <= count; offset++) {
            schedule[offset - 1] = base.times(stepMultiplier(ladder, offset)).atPresentationScale();
        }
        return schedule;
    }

    /**
     * {@code f^k} for the {@code offset}-th amortising period, 1-based.
     *
     * <p>Not routed through {@link Precision#onePlusPow}: a ladder multiplier is a
     * multiplier, not a rate, and writing it as {@code (1 + (f-1))^k} would disguise
     * that.
     */
    private static BigDecimal stepMultiplier(PrincipalProfile.StepLadder ladder, int offset) {
        return ladder.factor().pow((offset - 1) / ladder.everyNPeriods(), Precision.WORKING);
    }

    /**
     * The billed principal per amortising period, or {@code null} where the
     * instalment is the primitive instead.
     *
     * <p>Equal principal is billed to the paise like any other component, so
     * 1,000,000 over 24 periods bills 41,666.67 and not 41,666.6667 — and the 0.08
     * that 24 such bills over-repay is absorbed by the last rung, which closes on
     * the terminal amount. That is fixture S1's 42,083.26 final instalment: a
     * principal of 41,666.59 plus 416.67 of interest on it, rather than the
     * 42,083.34 an unrounded principal would bill.
     */
    private static Money[] fixedPrincipals(
        ScheduleBlueprint blueprint,
        ScheduleTerm term,
        Money balanceAtStart,
        Money expectedTerminal,
        int count) {

        int first = term.firstAmortisingPeriod();
        Money zero = Money.zero(blueprint.currency());
        PrincipalProfile profile = blueprint.principal();
        if (profile instanceof PrincipalProfile.EqualPrincipal) {
            Money each = balanceAtStart.minus(expectedTerminal)
                .dividedBy(BigDecimal.valueOf(count))
                .atPresentationScale();
            Money[] schedule = new Money[count];
            for (int offset = 0; offset < count; offset++) {
                schedule[offset] = each;
            }
            return schedule;
        }
        if (profile instanceof PrincipalProfile.Sculpted sculpted) {
            Money[] schedule = allZero(count, zero);
            for (PrincipalProfile.PrincipalStep step : sculpted.ladder()) {
                int offset = step.periodIndex() - first;
                schedule[offset] = schedule[offset].plus(step.amount());
            }
            return schedule;
        }
        if (profile instanceof PrincipalProfile.BulletAtMaturity
            || profile instanceof PrincipalProfile.NoneUntilMaturity) {
            return allZero(count, zero);
        }
        return null;
    }

    private static Money[] allZero(int count, Money zero) {
        Money[] schedule = new Money[count];
        for (int offset = 0; offset < count; offset++) {
            schedule[offset] = zero;
        }
        return schedule;
    }

    /**
     * {@code D_j = 1 / PROD (1 + i_s)} for the first {@code count} amortising
     * periods, with {@code D_0 = 1}.
     *
     * <p>Built from the per-period rates rather than from a single rate so that a
     * step coupon and a collar that binds mid-schedule discount correctly. Under a
     * uniform rate every entry is {@code (1+i)^-j} exactly, which is what keeps the
     * general path and {@link Annuity} in agreement.
     */
    private static BigDecimal[] cumulativeDiscountFactors(
        BigDecimal[] rates, int first, int count) {

        BigDecimal[] discount = new BigDecimal[count + 1];
        discount[0] = BigDecimal.ONE;
        for (int offset = 1; offset <= count; offset++) {
            BigDecimal onePlus = BigDecimal.ONE.add(rates[first + offset - 1]);
            if (onePlus.signum() <= 0) {
                throw new IllegalArgumentException(
                    "period " + (first + offset - 1) + " carries a rate of "
                        + rates[first + offset - 1].toPlainString() + "; 1 + rate must be positive");
            }
            discount[offset] = discount[offset - 1].divide(onePlus, Precision.WORKING);
        }
        return discount;
    }

    private static boolean uniform(BigDecimal[] rates, int first, int count) {
        for (int offset = 1; offset < count; offset++) {
            if (rates[first + offset].compareTo(rates[first]) != 0) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal[] periodicRates(ScheduleBlueprint blueprint, int periods) {
        BigDecimal[] rates = new BigDecimal[periods + 1];
        for (int period = 1; period <= periods; period++) {
            rates[period] = blueprint.rate().rateForPeriod(period).periodic();
        }
        return rates;
    }

    private static Money[] drawsByPeriod(ScheduleTerm term, List<Tranche> draws, Money zero) {

        Money[] byPeriod = new Money[term.ladderPeriods() + 1];
        for (int period = 0; period <= term.ladderPeriods(); period++) {
            byPeriod[period] = zero;
        }
        for (Tranche tranche : draws) {
            byPeriod[tranche.periodIndex()] = byPeriod[tranche.periodIndex()].plus(tranche.amount());
        }
        return byPeriod;
    }

    /**
     * Where a deferred-simple accrual settles.
     *
     * <p>ACPIR 9(6)(i) is explicit that interest deferred through a holiday becomes
     * due only once the holiday ends, so the end of the holiday is the default and
     * a settlement date on that due date says the same thing. A
     * {@code BALLOON_ARREARS} term effect overrides it to maturity, because sending
     * arrears to a terminal lump is precisely what that term effect is. A
     * settlement date later than either is honoured as stated.
     */
    private static int settlementPeriod(
        ScheduleBlueprint blueprint, ScheduleTerm term, List<LocalDate> dueDates) {

        int settlement = blueprint.moratorium().termEffect()
            == Moratorium.MoratoriumTermEffect.BALLOON_ARREARS
            || !blueprint.moratorium().isPresent()
            ? term.ladderPeriods()
            : term.moratoriumPeriods();
        if (blueprint.servicing() instanceof InterestServicing.DeferredSimple deferred) {
            int stated = ScheduleDates.periodOnOrAfter(dueDates, deferred.settlementDate());
            if (stated > settlement) {
                settlement = stated;
            }
        }
        return settlement;
    }

    // ------------------------------------------------------------- residue

    /**
     * The residue policy applied to the amortising instalment stream.
     *
     * <p>Only the amortising instalments are offered to the policy. The residue is
     * created by rounding <em>them</em>, so that is where a plug belongs; a plug
     * landing on an interest-only rung or on a holiday's serviced interest would
     * adjust a bill that was already exact.
     *
     * <p>Under {@link ResiduePolicy#LMS_AUTHORITATIVE} nothing is adjusted, by
     * design. That policy means the lending system's own schedule is authoritative,
     * and a derived schedule carrying it is a misconfiguration whose safe failure is
     * to leave the residue visible — it then shows up in the unamortised-fee column
     * and in ST-5 rather than being quietly absorbed.
     */
    private static List<Step> applyResiduePolicy(
        ScheduleBlueprint blueprint,
        ScheduleTerm term,
        List<Step> steps,
        Money residue,
        BigDecimal[] rates,
        int spreadPeriods) {

        if (residue.isZero()) {
            return steps;
        }
        List<CashFlow> instalments = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (step.billed() != null && step.billed().isPositive()) {
                instalments.add(CashFlow.of(
                    step.dueOn(), step.periodIndex(), step.billed(), FlowKind.COMBINED_EMI));
                positions.add(index);
            }
        }
        if (instalments.isEmpty()) {
            return steps;
        }
        List<CashFlow> resolved = blueprint.residuePolicy().apply(
            instalments, residue, rates[term.ladderPeriods()], spreadPeriods);
        List<Step> adjusted = new ArrayList<>(steps);
        for (int slot = 0; slot < positions.size(); slot++) {
            int index = positions.get(slot);
            adjusted.set(index, adjusted.get(index).withBilled(resolved.get(slot).amount()));
        }
        return adjusted;
    }

    // ---------------------------------------------------------------- roll

    /**
     * Rolls the plan forward, accruing then applying cash — the deterministic
     * within-period ordering the specification fixes for every event
     * (<a href="../../../../../../../../../docs/03-calculation-spec.md">03 § 5.4</a>).
     *
     * <p>{@code close} is what separates the two calls this class makes. The probe
     * roll leaves the last rung's principal derived, so that the terminal balance it
     * reports is the residue the billed instalments actually leave. The final roll
     * closes the last rung on the intended terminal, which is the only way a rounded
     * schedule reaches a balance that is neither an unpaid remainder nor negative.
     *
     * <p>Signed arithmetic handles a drawdown with no special case: a draw raises the
     * balance in the period it lands, after that period's interest has accrued on
     * what was outstanding before it.
     */
    private static Roll roll(
        List<Step> steps, Money opening, Money expectedTerminal, boolean close, Money zero) {

        List<Money> interest = new ArrayList<>(steps.size());
        List<Money> principal = new ArrayList<>(steps.size());
        List<Money> balanceAfter = new ArrayList<>(steps.size());
        List<Money> total = new ArrayList<>(steps.size());
        Money balance = opening;
        Money deferred = zero;

        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            boolean isLast = index == steps.size() - 1;
            Money accrual = step.accrual() == Accrual.NONE ? zero : balance.times(step.rate());

            Money move;
            if (close && isLast) {
                move = balance.plus(step.draw()).minus(expectedTerminal);
            } else if (step.accrual() == Accrual.COMPOUND) {
                move = accrual.negate();
            } else if (step.billed() != null) {
                move = step.billed().minus(accrual);
            } else {
                move = step.fixedPrincipal();
            }

            Money billedTotal;
            Money recorded;
            if (step.accrual() == Accrual.COMPOUND) {
                billedTotal = step.addOn();
                recorded = accrual;
            } else if (step.billed() != null || step.accrual() == Accrual.DEFER) {
                billedTotal = (step.billed() == null ? move.atPresentationScale() : step.billed())
                    .plus(step.addOn());
                recorded = accrual;
            } else {
                // The bill here is principal plus interest, and principal is the
                // component the contract fixes to the paise. The paise the bill
                // rounds therefore belong to the interest column, which is what
                // makes principal + interest = total hold on every such rung: a
                // published movement schedule whose columns do not sum is a defect
                // even where every figure is individually right (03 § 5.7). On
                // fixture S1 that is the difference between an interest total of
                // 125,000.00, which ties to 1,125,000.00 of cash against 1,000,000
                // of principal, and 124,999.99, which ties to nothing.
                Money cash = move.plus(accrual).atPresentationScale();
                billedTotal = cash.plus(step.addOn());
                recorded = step.accrual() == Accrual.NONE ? zero : cash.minus(move);
            }

            if (step.accrual() == Accrual.DEFER) {
                deferred = deferred.plus(accrual);
            }
            balance = balance.plus(step.draw()).minus(move);

            interest.add(recorded);
            principal.add(move);
            total.add(billedTotal.atPresentationScale());
            balanceAfter.add(balance);
        }
        return new Roll(interest, principal, balanceAfter, total, balance, deferred);
    }

    /** What happens to a period's accrual. */
    private enum Accrual {
        /** Billed as part of this period's instalment. */
        CHARGE,
        /** Added to the balance, where it thereafter bears interest. */
        COMPOUND,
        /** Accrued without compounding, to settle as one lump. */
        DEFER,
        /** No accrual — the interest was collected at inception as a discount. */
        NONE
    }

    /**
     * One period's instructions, resolved before any balance is rolled.
     *
     * <p>Exactly one of {@code fixedPrincipal} and {@code billed} is set, and which
     * one says which quantity the contract fixes: a level instalment fixes the bill
     * and lets principal fall out of it, an equal-principal or sculpted ladder fixes
     * the principal and lets the bill fall out of that. {@code addOn} carries the
     * amounts that ride on a period's bill without being part of its instalment —
     * a balloon at maturity, a deferred lump at settlement — which is why they can
     * be attached after the instalment has been sized.
     */
    private record Step(
        int periodIndex,
        LocalDate dueOn,
        BigDecimal rate,
        Money draw,
        Accrual accrual,
        Money fixedPrincipal,
        Money billed,
        Money addOn) {

        Step plusAddOn(Money extra) {
            return new Step(periodIndex, dueOn, rate, draw, accrual, fixedPrincipal, billed,
                addOn.plus(extra));
        }

        Step withBilled(Money replacement) {
            return new Step(periodIndex, dueOn, rate, draw, accrual, fixedPrincipal, replacement,
                addOn);
        }
    }

    /** The result of one roll, in period order. */
    private record Roll(
        List<Money> interest,
        List<Money> principal,
        List<Money> balanceAfter,
        List<Money> total,
        Money terminal,
        Money deferredAccrual) {
    }
}
