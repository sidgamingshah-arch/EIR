package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The ACPIR product matrix as executable code rather than prose
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 5</a>,
 * from
 * <a href="../../../../../../../../../docs/reference/acpir-2026-eir-application-reference.md">reference § 4</a>).
 *
 * <p>The thirty-eight instrument families in the reference are not thirty-eight
 * shapes. They are combinations of the eight dimensions, and stating them as code
 * rather than as a table does three things a table cannot. It makes each family's
 * composition <b>checkable</b> — {@link ScheduleBlueprint} rejects an incoherent one
 * at construction and names the conflict (ST-11), so a family that was written down
 * wrong fails here rather than three stages later on a live contract. It makes the
 * composition <b>diffable</b>, so a policy decision that changes a family's shape
 * shows up in a review as a code change with an author and a date. And it removes the
 * step where somebody reads the table and types the dimensions in by hand, which is
 * where product taxonomies go to die.
 *
 * <h2>A template is a starting point, never a constraint</h2>
 *
 * <p>Every factory returns a value. A contract may override any dimension of it, and
 * the overriding contract is <em>right</em> — the template is what the product looked
 * like when the family was defined, and an individual facility is negotiated. Nothing
 * here is enforced on a contract and nothing here is consulted at projection time:
 * {@link BlueprintProjector} takes a blueprint and has no idea whether a template
 * produced it. Templates are versioned policy artefacts like the routing table and
 * the fee rule set, and the version that produced a published figure is what a
 * recomputation needs, not the current one.
 *
 * <h2>No numbers</h2>
 *
 * <p>Not one rate, tenor, fee, tolerance, curve or utilisation assumption is written
 * into this file. Every one of them is a parameter. A template that carried a rate
 * would be a template that had priced somebody's loan, and a default that is wrong is
 * worse than an argument that is missing — the missing argument fails at compile time
 * and the wrong default fails at the audit.
 *
 * <p>What the templates <em>do</em> encode is structure: which dimension variant the
 * family uses, which dimensions compose, and which combinations the family cannot
 * coherently take. That is product design, it is stable, and it is the part that is
 * expensive to rediscover.
 *
 * <h2>What a template cannot decide, and says so</h2>
 *
 * <p>Each factory's documentation names the ACPIR paragraph or matrix entry it
 * implements and then states, under <b>what the bank must still decide</b>, the
 * judgements the template deliberately leaves open. Those are Board or sub-committee
 * positions — thirty of them are enumerated in reference § 9 — and encoding a default
 * for one of them here would be the engine quietly taking a policy decision. The
 * pattern throughout: the mechanism is built ahead of the position, and the position
 * is an input.
 *
 * <p>Pure and clock-free. Every date is a parameter; nothing here reads a wall clock.
 */
public final class ProductTemplates {

    private ProductTemplates() {
    }

    // ================================================================= 1. LOANS

    /**
     * Retail housing loan, floating — reference § 4 item 8, ACPIR 51 with IFRS 9
     * B5.4.4; matrix row "Retail housing, floating".
     *
     * <p>{@code LevelAnnuity} + {@code ServicedEachPeriod} + {@code Floating} +
     * {@code PREPAYMENT} option + {@code CprVector} + {@code NEXT_REPRICING}.
     *
     * <p><b>The largest single EIR estimation judgement in an Indian bank's book</b>,
     * and the one where the mechanism and the policy pull in opposite directions. Two
     * designs are defensible: contractual life with a B5.4.5 reset at every EBLR
     * repricing, or a modelled behavioural life driven by a prepayment curve. Observed
     * behavioural life on Indian retail mortgages is materially shorter than the
     * fifteen-to-twenty-year contractual tenor because of balance-transfer churn, so
     * the curve is real. But the instrument reprices to market, which makes the
     * B5.4.4 shortcut available — and the shortcut is not only simpler, it removes the
     * reset-loop cost entirely by leaving no unamortised fee to carry across a
     * monthly or quarterly reset. At Indian retail volumes that is the difference
     * between a batch window that closes and one that does not.
     *
     * <p>So the template carries <em>both</em>: {@code NEXT_REPRICING} as the recorded
     * exercise policy and the CPR curve as the recorded behavioural assumption. That
     * is not indecision. The curve is needed regardless — it feeds the ACPIR 46(1)
     * horizon, the ECL profile and the disclosure — and the engine computes every
     * applicable policy and reports the divergence, which is what puts the cost of
     * the election in front of the committee instead of leaving it implicit.
     *
     * <p>RBI's restrictions on foreclosure charges for floating-rate individual loans
     * mean there is almost no prepayment-penalty cash flow left to model, which
     * removes a variable that dominates the equivalent calculation elsewhere. The
     * option is therefore struck at par: the borrower may leave, and leaving costs
     * them nothing.
     *
     * <p><b>What the bank must still decide.</b> Whether B5.4.4 is elected for this
     * product at all — it is an accounting policy election <em>per product</em>, not a
     * per-contract optimisation, and applied inconsistently it is indefensible.
     * Whether the CPR curve is segmented by vintage, ticket size or channel. And the
     * pooling criteria under the ACPIR 51 group presumption, which have to be
     * documented and validated under Chapter V.
     *
     * @param advance             sum advanced to the borrower
     * @param annuityRate         the contractual rate in force, at the schedule's own
     *                            frequency
     * @param benchmarkId         the repricing benchmark — EBLR, MCLR, repo-linked
     * @param spreadBps           the contractual spread over the benchmark
     * @param resetDates          contractual repricing dates; at least one must fall
     *                            after the value date, or {@code NEXT_REPRICING} has
     *                            no anchor to amortise to
     * @param prepaymentFrom      first date the borrower may prepay
     * @param annualCprByPeriod   the prepayment curve, one annual CPR per period,
     *                            held flat beyond the last point
     */
    public static ScheduleBlueprint retailHousingFloating(
        TemplateBasis basis,
        Money advance,
        Rate annuityRate,
        String benchmarkId,
        BigDecimal spreadBps,
        List<LocalDate> resetDates,
        LocalDate prepaymentFrom,
        List<BigDecimal> annualCprByPeriod) {

        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(prepaymentFrom, "prepaymentFrom");
        Objects.requireNonNull(resetDates, "resetDates");
        RateProfile.Floating floating =
            new RateProfile.Floating(benchmarkId, spreadBps, resetDates, annuityRate);
        if (floating.nextResetAfter(basis.valueDate()).isEmpty()) {
            throw new IllegalArgumentException(
                "the NEXT_REPRICING election amortises to a repricing date and none of the "
                    + resetDates.size() + " supplied reset dates falls after the value date "
                    + basis.valueDate() + ". Supply the schedule, or record a different exercise"
                    + " policy — falling back to expected life would apply a policy other than the"
                    + " one on record");
        }
        return blueprint(
            basis,
            advance,
            new DisbursementProfile.Single(basis.valueDate(), advance),
            new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            floating,
            new OptionSchedule(
                List.of(new OptionSchedule.EmbeddedOption(
                    OptionSchedule.OptionType.PREPAYMENT,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    prepaymentFrom,
                    basis.statedMaturity(),
                    BigDecimal.ONE,
                    false)),
                ExercisePolicy.NEXT_REPRICING),
            new BehaviouralOverlay.CprVector(annualCprByPeriod));
    }

    /**
     * Education loan with a course-plus-grace moratorium — reference § 4 item 9,
     * ACPIR 9(6)(i); matrix row "Education loan".
     *
     * <p>{@code LevelAnnuity} + {@code FULL_INTEREST_CAPITALISED} moratorium +
     * {@code EXTEND_TERM}.
     *
     * <p><b>ACPIR 9(6)(i) produces two consequences from one feature and they must not
     * be allowed to imply one another.</b> Interest becomes due only after the
     * moratorium, so it is <em>not overdue</em> in the interim — a staging input. The
     * capitalisation increases the gross carrying amount — an EIR input. A system that
     * lets the non-overdue status suppress the accretion, or lets the accretion imply
     * an overdue, is wrong in one of two different directions, and both are silent.
     *
     * <p>{@code EXTEND_TERM} is the contractual reading of an education loan and it is
     * not a formality: the borrower gets the full repayment term <em>after</em> the
     * holiday, so the ladder runs longer than stated maturity by exactly the holiday.
     * The alternative, compressing the remaining instalments, produces a different
     * flow vector and therefore a different rate from the same concession — which is
     * why the term effect is a contractual input and not a builder default. On the
     * S5 fixture a twelve-period fully-capitalising holiday on a 24-period loan bills
     * 24 instalments starting in period 13 on a balance grown to 1,126,825.03, and the
     * EIR is 12.965053% against a contractual 12.682503%.
     *
     * <p>Capitalising is worth materially more to the lender than deferring simple —
     * 126,825.03 against 120,000.00 on that fixture, and 34.6 basis points of rate —
     * so this family cannot share a code path with the deferred-simple restructuring
     * shape even though both describe "interest not paid during a holiday".
     *
     * <p><b>What the bank must still decide.</b> Whether the Central Sector Interest
     * Subsidy is a cash flow "between the parties to the contract". The recommended
     * position is no — account for the subvention separately and keep it out of the
     * EIR — unless the loan contract makes the borrower's own rate contingent on it.
     * Settle it once and apply the same answer to the agricultural interest
     * subvention, which raises the identical question.
     *
     * @param coursePeriods    the study period, in schedule periods
     * @param gracePeriods     the grace period after course completion
     * @param expectationBasis why contractual life is the expectation — recorded as a
     *     policy choice, because "we used contractual life" and "we never considered
     *     life" produce identical numbers and very different audit outcomes
     */
    public static ScheduleBlueprint educationLoan(
        TemplateBasis basis,
        Money advance,
        Rate rate,
        int coursePeriods,
        int gracePeriods,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        if (coursePeriods < 0 || gracePeriods < 0) {
            throw new IllegalArgumentException(
                "the course and grace phases are non-negative period counts, got course "
                    + coursePeriods + " and grace " + gracePeriods);
        }
        int holiday = coursePeriods + gracePeriods;
        if (holiday < 1) {
            throw new IllegalArgumentException(
                "an education loan with neither a course nor a grace period is an ordinary"
                    + " annuity term loan; use that family rather than a moratorium of zero");
        }
        return blueprint(
            basis,
            advance,
            new DisbursementProfile.Single(basis.valueDate(), advance),
            new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.CapitalisedEachPeriod(),
            Moratorium.fullyCapitalised(holiday),
            new RateProfile.Fixed(rate),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * Project finance, pre-COD — reference § 4 item 12, ACPIR 82(xii) with IFRS 9
     * B5.4.6; matrix row "Project finance".
     *
     * <p>{@code Tranched} + {@code Sculpted} + {@code CapitalisedEachPeriod} pre-COD +
     * {@code Floating}.
     *
     * <p>Four features compose here and each of them is individually awkward, which is
     * why the flat shape enum broke on this family first. Draws run over twenty-four
     * to forty-eight months against a schedule struck at financial closure that never
     * matches actual, so <b>the rate is stale by first drawdown</b>. Interest during
     * construction capitalises, so the balance the ladder amortises is not the balance
     * that was advanced. The principal ladder is sized to projected free cash flow, so
     * no formula reproduces it and it is supplied. And the rate floats.
     *
     * <p>The interim draws are emitted as the negative flows they are, which is what
     * gives the present-value function more than one sign change and hands the solver
     * genuinely multiple valid roots. That is disclosed rather than smoothed away:
     * a single notional advance would remove the sign changes and the disclosure with
     * them, and Newton-Raphson fails on this shape, which is why the bisection
     * fallback is mandatory rather than optional.
     *
     * <p><b>Re-estimation triggers on cumulative deviation, not on every draw.</b> A
     * per-draw trigger churns the book for no informational gain — and because a
     * disbursement-timing change compensates for neither the time value of money nor
     * credit risk, the April 2026 tentative decision routes it to a catch-up under
     * B5.4.6 rather than to a reset. The tolerance is a parameter because it is a
     * policy threshold.
     *
     * <p><b>What the bank must still decide.</b> Whether a DCCO deferment is itself a
     * modification requiring a catch-up. ACPIR is silent and this is the
     * highest-value single position to get right for a wholesale bank — note that the
     * additional 0.375% and 0.5625% per-quarter provisions the deferment triggers are
     * a <em>provisioning</em> consequence and not an EIR one, and conflating the two
     * is the common error. Also the cumulative-deviation tolerance itself, and whether
     * the construction-phase capitalisation is modelled on whole periods or on actual
     * milestone dates — an off-cycle draw is a broken period, and a facility whose
     * draws genuinely land mid-period should supply its billed schedule rather than
     * have one derived.
     *
     * @param projectedDraws               the drawdown schedule agreed at financial
     *     closure; the rate is struck on this and not on actuals, which is also what
     *     leaves a baseline for the drift measurement
     * @param cumulativeDeviationTolerance fraction of notional by which cumulative
     *     actual may diverge before a {@code DISBURSEMENT_TIMING} re-estimation fires
     * @param principalLadder              principal due per period, sized to projected
     *     free cash flow; whatever it leaves outstanding is retained as the terminal
     *     balance rather than plugged, because a ladder that does not clear the
     *     facility has told the engine something
     * @param constructionPeriods          periods before commercial operation, over
     *     which interest capitalises
     */
    public static ScheduleBlueprint projectFinance(
        TemplateBasis basis,
        Money notional,
        List<Tranche> projectedDraws,
        BigDecimal cumulativeDeviationTolerance,
        List<PrincipalProfile.PrincipalStep> principalLadder,
        int constructionPeriods,
        String benchmarkId,
        BigDecimal spreadBps,
        List<LocalDate> resetDates,
        Rate currentRate,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        if (constructionPeriods < 1) {
            throw new IllegalArgumentException(
                "interest during construction capitalises over the construction phase, and a"
                    + " phase of " + constructionPeriods + " periods has nothing to capitalise."
                    + " A facility already in commercial operation is a sculpted term loan");
        }
        return blueprint(
            basis,
            notional,
            new DisbursementProfile.Tranched(
                projectedDraws, List.of(), cumulativeDeviationTolerance),
            new PrincipalProfile.Sculpted(principalLadder),
            new InterestServicing.CapitalisedEachPeriod(),
            Moratorium.fullyCapitalised(constructionPeriods),
            new RateProfile.Floating(benchmarkId, spreadBps, resetDates, currentRate),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * Commercial vehicle and construction-equipment finance — reference § 4A mid-tenor
     * volume centre, ACPIR 53; matrix row "CV / equipment".
     *
     * <p>{@code Balloon} carrying the residual value + {@code ServicedEachPeriod} +
     * {@code Fixed}.
     *
     * <p>The instalments amortise <em>toward</em> the residual, not to zero: the
     * schedule is sized so that the instalments and the residual together discount to
     * the amount financed at the contractual rate. Sizing the instalments on the full
     * amount and then adding the residual on top is the standard error and it
     * over-collects by the residual's present value. Invariant ST-5 exists to catch
     * the mirror image — a residual-value ladder that reaches zero has silently
     * amortised the lump the borrower still owes.
     *
     * <p>This family is also the cheapest place in the engine to catch a
     * fee-classification error, because the sign of the net integral amount is visible
     * in the rate. On fixture O8 — 1,000,000 over 36 months at 11% with a 200,000
     * residual — a 12,000 fee <em>received</em> gives a GCA of 988,000 and an EIR of
     * 12.377404%, above the 11.571884% contractual effective rate; a 12,000 cost
     * <em>paid</em> gives 1,012,000 and 10.784759%, below it; and no integral amount
     * gives the contractual rate back to the sixth decimal. That is invariant INV-2 in
     * both directions, and it catches a whole class of classification errors for the
     * price of a comparison.
     *
     * <p>Project this template with
     * {@code BlueprintProjector.withTerminalLumpAs(FlowKind.RESIDUAL_VALUE)}. The
     * arithmetic is identical to a balloon and the two are different assets to a
     * controller, which is why the label is carried rather than derived.
     *
     * <p><b>What the bank must still decide.</b> Whether the DSA or dealer payout is a
     * selling cost that capitalises under ACPIR 53 or an internal processing cost that
     * does not — the Direction draws the line at <em>selling</em>, and source HR and
     * cost-centre data is structured along neither. And the prepayment curve for the
     * mid-tenor book, where balance-transfer behaviour is genuine but the fee quantum
     * is small enough that a simple curve suffices.
     *
     * @param residualValue the guaranteed or contracted terminal amount
     */
    public static ScheduleBlueprint commercialVehicle(
        TemplateBasis basis,
        Money financedAmount,
        Rate rate,
        Money residualValue,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            financedAmount,
            new DisbursementProfile.Single(basis.valueDate(), financedAmount),
            new PrincipalProfile.Balloon(residualValue),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(rate),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * The label to project a {@link #commercialVehicle} or lease blueprint under.
     *
     * <p>Named rather than left to the caller to remember, because the default is
     * {@code BALLOON} and a lease residual booked as a balloon is a reconciliation
     * break with no arithmetic cause — the figures all tie and the wrong asset is
     * disclosed.
     */
    public static FlowKind leaseTerminalKind() {
        return FlowKind.RESIDUAL_VALUE;
    }

    /**
     * Gold loan — reference § 4 item 7, ACPIR 92 Explanation; matrix row "Gold loan".
     *
     * <p>{@code BulletAtMaturity} + {@code ServicedEachPeriod} +
     * {@code RolloverAssumption}.
     *
     * <p>A six-to-twelve-month bullet where interest is serviced and the principal
     * repays at maturity, so the EIR is approximately the contractual rate plus the
     * fee accreted over the bullet tenor. What makes it more than a short term loan is
     * that <b>rollovers are routine</b>, and each one poses the
     * modification-versus-new-instrument question. The rollover assumption is
     * therefore a recorded behavioural input rather than an operational detail: it
     * feeds the ACPIR 46(2)(ii) test on whether the renewal process is genuinely
     * substantive, which is the same question as whether a rolled facility is a series
     * of new instruments or one revolving facility in substance.
     *
     * <p>One system requirement that is easy to miss: ACPIR 92 <em>requires</em> gold
     * loans to sit in their own product category and forbids grouping them under
     * secured retail for provisioning-floor purposes. So the product tag this template
     * is registered against must be the same tag the floor engine reads. One taxonomy,
     * two consumers — and a taxonomy that diverges between them is a control failure
     * that neither engine can detect on its own.
     *
     * <p><b>What the bank must still decide.</b> Whether a rollover is a modification
     * or a new instrument, answered <em>by product in policy</em> and not case by case
     * at the branch. And the rollover probability itself, which is an estimate and
     * therefore a model input under Chapter V if it is derived rather than assumed.
     *
     * @param expectedRollovers    renewals the bank expects, beyond the stated tenor
     * @param rolloverProbability  likelihood of each, in {@code [0,1]}
     */
    public static ScheduleBlueprint goldLoan(
        TemplateBasis basis,
        Money advance,
        Rate rate,
        int expectedRollovers,
        BigDecimal rolloverProbability) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            advance,
            new DisbursementProfile.Single(basis.valueDate(), advance),
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(rate),
            OptionSchedule.none(),
            new BehaviouralOverlay.RolloverAssumption(expectedRollovers, rolloverProbability));
    }

    /**
     * Kisan Credit Card and agricultural revolving credit — reference § 4 item 10,
     * ACPIR 9(7) and 46(2); matrix row "KCC".
     *
     * <p>{@code UtilisationDriven} + {@code SEASONAL} calendar +
     * {@code RevolverBehaviour}.
     *
     * <p><b>The seasonal calendar is not a nicety.</b> KCC due dates align to the crop
     * cycle, so the periods are genuinely unequal, and a monthly-index approximation
     * on a crop-cycle loan is not a slightly rough rate — it is a wrong one, because
     * period ordinals are not a measure of time on a schedule whose periods differ.
     * Invariant ST-10 makes actual-date discounting mandatory here, and this factory
     * refuses a calendar that is not explicit-date rather than accepting one and
     * letting the convention check quietly decide. The refusal is the point: the two
     * mistakes this family invites — a monthly calendar, and a monthly rate applied to
     * a six-month harvest gap — both produce a schedule that looks orderly and bills a
     * fraction of the interest.
     *
     * <p>Because it revolves there is no contractual drawdown schedule to project and
     * no contractual repayment schedule to amortise, so ACPIR 54 permits an
     * approximation on the funded balance rather than requiring a conventional EIR.
     * The template models the expected drawn balance as the limit times a utilisation
     * assumption and repays it at the behavioural horizon, which is an approximation
     * stated as one.
     *
     * <p><b>What the bank must still decide.</b> The interest subvention and
     * prompt-repayment-incentive question, which is the education-loan subsidy
     * question again and should get the same answer. Whether the fee quantum clears a
     * documented materiality threshold at all — for KCC it is typically de minimis and
     * a documented threshold is the proportionate response, but reference § 4A is
     * emphatic that "we approximated because it was immaterial" is a complete answer
     * only if the assessment exists on paper with a number attached. And the
     * behavioural life, which needs the ACPIR 46(2)(iii)-style analysis behind it.
     *
     * @param limit               the sanctioned limit
     * @param averageUtilisation  expected drawn fraction of the limit, in {@code [0,1]}
     * @param behaviouralLifeMonths modelled life of the facility
     * @param utilisationCurve    expected drawn fraction by period
     * @param analysisReference   the analysis supporting the modelled life
     */
    public static ScheduleBlueprint kisanCreditCard(
        TemplateBasis basis,
        Money limit,
        BigDecimal averageUtilisation,
        Rate rate,
        int behaviouralLifeMonths,
        List<BigDecimal> utilisationCurve,
        String analysisReference) {

        Objects.requireNonNull(basis, "basis");
        if (!basis.calendar().frequency().requiresExplicitDates()) {
            throw new IllegalArgumentException(
                "a KCC schedule is aligned to the crop cycle, so its periods are unequal and its"
                    + " due dates cannot be derived from a frequency; calendar "
                    + basis.calendar().frequency() + " would license periodic indexing on a"
                    + " schedule where period ordinals are not a measure of time (ST-10). Supply"
                    + " ScheduleCalendar.seasonal(dueDates)");
        }
        return revolver(basis, limit, averageUtilisation, rate,
            behaviouralLifeMonths, utilisationCurve, analysisReference);
    }

    /**
     * Credit cards — reference § 4 item 6, ACPIR 46(2)(iii); matrix row "Credit card".
     *
     * <p>{@code UtilisationDriven} + {@code RevolverBehaviour}, with the
     * <b>ACPIR 46(2)(iii) analysis reference mandatory</b> —
     * {@link BehaviouralOverlay.RevolverBehaviour} rejects a blank one at
     * construction, and that rejection is the control this family most needs.
     *
     * <p>ACPIR 46(2)(iii) is an explicit behavioural-modelling mandate: it requires
     * analysis of historical default patterns, drawdown behaviour, and the
     * effectiveness of limit reduction, suspension and cancellation. It is not
     * satisfied by asserting a life. The UK experience is the cautionary tale —
     * spreading card economics over a modelled behavioural life creates a
     * balance-sheet item whose <em>entire magnitude</em> is an estimate, and it has
     * been a recurring source of restatement among consumer lenders. An unevidenced
     * life is precisely the defect the mandatory reference exists to prevent, which is
     * why the field is a constructor requirement rather than a documentation
     * convention.
     *
     * <p>A contractually auto-renewing revolver has no maturity to amortise to, so
     * expected life must be determined behaviourally and interest accretes only on
     * revolving balances — not on transactions settled inside the interest-free
     * period, which produce no interest at all and must not be modelled as though they
     * did.
     *
     * <p><b>What the bank must still decide.</b> The fee split: a joining fee has an
     * origination character and defers, while the annual fee, interchange and late
     * fees are service income. And the behavioural life itself, which under Chapter V
     * is a model — inventory, tiering, documentation and independent validation before
     * it may be used in production.
     */
    public static ScheduleBlueprint creditCard(
        TemplateBasis basis,
        Money limit,
        BigDecimal averageUtilisation,
        Rate cardRate,
        int behaviouralLifeMonths,
        List<BigDecimal> utilisationCurve,
        String analysisReference) {

        Objects.requireNonNull(basis, "basis");
        return revolver(basis, limit, averageUtilisation, cardRate,
            behaviouralLifeMonths, utilisationCurve, analysisReference);
    }

    /**
     * Working capital demand loan — reference § 4 item 4, ACPIR 51; Tier 3 territory
     * under reference § 4A.
     *
     * <p>{@code BulletAtMaturity} + {@code ServicedEachPeriod} + {@code Fixed}, over a
     * seven-to-one-hundred-and-eighty-day tenor. Pair it with
     * {@link TemplateBasis#moneyMarket}, which gives one flow on one supplied date and
     * forces actual-date discounting.
     *
     * <p><b>This family belongs in Tier 3, and the tier is the design decision.</b>
     * Original tenor of twelve months or less puts it where the EIR may be taken as
     * the contractual rate plus straight-line accretion of net fees — but only after
     * an equivalence test has demonstrated, on a representative sample and with a
     * number written down, that the difference from a solved EIR is immaterial.
     * Reference § 4A is unambiguous that an undocumented shortcut is the failure mode,
     * not the proportionate answer. This template exists so that the solved figure is
     * <em>available</em> to be the benchmark that test is run against.
     *
     * <p>Two things about short tenor that catch teams out. Day-count sensitivity runs
     * <b>inversely</b> to tenor: a three-day convention error here is a materially
     * wrong rate where the same error on a twenty-year mortgage is noise. And the
     * annualisation optic — a 1% fee on a thirty-day loan amortised over thirty days
     * produces an annualised EIR far above the card rate. The arithmetic is right and
     * the MIS looks broken, so the presentation convention has to be agreed with
     * finance and the business <em>before</em> go-live rather than during the first
     * quarter's variance review.
     *
     * <p><b>What the bank must still decide.</b> Whether continuous rollover makes the
     * substance a revolving facility rather than a series of new instruments — the
     * dominant question for this family, answered by product in policy and feeding the
     * ACPIR 46(2)(ii) substantive-renewal test. And the Tier 3 threshold itself, with
     * the equivalence test re-run annually.
     */
    public static ScheduleBlueprint workingCapitalDemandLoan(
        TemplateBasis basis,
        Money advance,
        Rate rate,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            advance,
            new DisbursementProfile.Single(basis.valueDate(), advance),
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(rate),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * Staff housing and concessional employee loans — reference § 4 item 11, ACPIR 19,
     * 20 and 9(6)(ii) with IFRS 9 B5.1.1.
     *
     * <p>{@code LevelAnnuity} + {@code ServicedEachPeriod} + {@code Fixed} at the
     * <em>concessional</em> rate. The shape is an ordinary retail mortgage. What is not
     * ordinary is the measurement at initial recognition, and this is the one family in
     * the matrix where <b>the EIR is not solved from the cash flows at all</b>.
     *
     * <p>The loan is priced below market, so its <b>fair value at initial recognition
     * is below the amount advanced</b>. ACPIR 19 and 20 require fair value at initial
     * recognition, which makes this bite — it is not optional. The consequences, in
     * order:
     *
     * <ol>
     *   <li>The instrument is recognised at fair value, which is the contractual flows
     *       discounted at a <b>market</b> rate for an equivalent instrument. That
     *       market rate <em>is</em> the EIR going forward. It is an input, supplied;
     *       there is nothing to solve, and solving the concessional flows against the
     *       amount advanced would simply recover the concessional rate and report a
     *       below-market loan as though it were fairly priced.
     *   <li>The day-one difference between the amount advanced and that fair value is
     *       <b>employee compensation, not a lending loss</b>. It belongs in staff cost,
     *       and it is not an integral fee, not a transaction cost and not a credit
     *       impairment. Booking it as any of those three misstates both the expense
     *       line and the asset's subsequent income, and the third also puts a
     *       performing loan into the impairment machinery.
     *   <li>The shortfall unwinds as interest income over the life at the market rate,
     *       which is why the asset accretes faster than the borrower's own instalments
     *       would suggest.
     * </ol>
     *
     * <p>The blueprint therefore carries the concessional rate — that is what the
     * contract bills and what the ladder and the contractual leg must reproduce — and
     * the market rate is supplied where the vector is discounted. The shortfall is
     * {@code amountAdvanced - PV(contractual flows at the market rate)}, computable
     * from this template's assembled vector through
     * {@code Discounting.presentValueMoney}.
     *
     * <p>ACPIR 9(6)(ii) separately carves staff loans out of the normal overdue test
     * where interest is payable only after principal recovery. That is a staging
     * carve-out and it has no bearing on the measurement above — the same discipline as
     * the education-loan moratorium, where one contractual feature has a staging
     * consequence and an EIR consequence that must not imply one another.
     *
     * <p><b>What the bank must still decide.</b> Which market rate is "equivalent" —
     * the same tenor and security on the ordinary retail book is the defensible
     * reading, and it needs to be a documented, dated reference rather than a
     * judgement per loan. For a public sector bank with a large staff book the day-one
     * retained-earnings adjustment on transition can be material, so the population
     * and the aggregate need sizing before the position is taken.
     *
     * @param concessionalRate the rate the contract actually bills the employee
     */
    public static ScheduleBlueprint staffHousingLoan(
        TemplateBasis basis,
        Money advance,
        Rate concessionalRate,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            advance,
            new DisbursementProfile.Single(basis.valueDate(), advance),
            new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(concessionalRate),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    // =========================================================== 2. INVESTMENTS

    /**
     * Treasury bills, commercial paper, certificates of deposit and bills discounted —
     * reference § 4 items 5 and 23, ACPIR 9(1) and 51; matrix row "T-bill / CP / CD".
     *
     * <p>{@code NoneUntilMaturity} + {@code DiscountedUpfront} + {@code ACT_365F}.
     *
     * <p>The cleanest products in the book, because <b>the EIR is already implicit in
     * existing practice</b>: the discount collected upfront <em>is</em> the interest,
     * and accretion is the entire return.
     *
     * <p>Which settles how the discount reaches the vector, and it is worth being
     * explicit because it looks like a trick and is not. The blueprint's notional is
     * the <b>face value redeemed at maturity</b>, the disbursement is that same face
     * value, and the discount is posted as an <b>integral amount received at
     * inception</b>. Then the initial carrying amount is face less discount — the price
     * actually paid — the single future flow is the face value, and the solved rate is
     * the yield the instrument was priced at. Nothing is fabricated: interest collected
     * at inception and a fee received at inception are the same cash on the same date,
     * both reduce the carrying amount, and both accrete back to face over the tenor.
     * Modelling the discount as a smaller advance instead leaves the ladder redeeming
     * what it advanced, which is an instrument with no return at all.
     *
     * <p>The quoted yield is carried on the rate profile even though no rung bills
     * interest. It is not decoration: the solver seeds from the contractual rate and
     * converges in three or four iterations from it, and on a deeply discounted
     * instrument the seed is what keeps the bracket scan from being the whole cost.
     *
     * <p><b>Approximation is never permitted for this profile at any tenor.</b> The
     * entire return is accretion and the straight-line error compounds with tenor — on
     * a fifteen-year zero-coupon instrument it overstates year-one income by 81% and
     * understates the final year by 38.4%, while the life totals tie to the paisa. An
     * error that nets to zero over fifteen years is invisible to any control looking at
     * cumulative figures and material in every single reporting period. That is what
     * makes selective EIR adoption on the investment book indefensible, and this family
     * the wedge for a full migration.
     *
     * <p><b>What the bank must still decide.</b> Nothing about the mechanics, and one
     * thing about the convention: actual/365 is Indian money-market practice, EIR
     * mathematics is compounding-convention dependent, and the convention must be
     * fixed in policy, applied uniformly and documented — otherwise treasury and
     * finance reconcile to different numbers indefinitely.
     *
     * @param faceValue     the amount redeemed at maturity
     * @param quotedYield   the yield the instrument was priced at, retained as the
     *     solver seed and for the trace
     */
    public static ScheduleBlueprint treasuryBill(
        TemplateBasis basis, Money faceValue, Rate quotedYield) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            faceValue,
            new DisbursementProfile.Single(basis.valueDate(), faceValue),
            new PrincipalProfile.NoneUntilMaturity(),
            new InterestServicing.DiscountedUpfront(),
            Moratorium.none(),
            new RateProfile.Fixed(quotedYield),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual(
                "a discount instrument has one flow at maturity and no prepayment right, so"
                    + " expected life is contractual life as a matter of the instrument's terms"
                    + " rather than as an estimate"));
    }

    /**
     * Callable and puttable corporate bonds — reference § 4 item 27, RBI Investment
     * FAQs against IFRS 9 Appendix A; matrix row "Callable corporate bond".
     *
     * <p>{@code BulletAtMaturity} + a {@code CALL} schedule + an <b>explicit</b>
     * {@link ExercisePolicy}.
     *
     * <p><b>A direct divergence, and one of the few in this domain that can be measured
     * exactly rather than argued.</b> RBI's Investment Directions amortise a discount
     * or premium over residual <em>contractual</em> maturity even for a security with a
     * call or a put. IFRS 9 Appendix A considers all contractual terms including the
     * call and runs over <em>expected</em> life. Both are computable, so the engine
     * computes both and reports the difference — which is why the policy is a required
     * parameter of this factory and not a default.
     *
     * <p>The size and the <b>direction</b> of the difference are not intuitive. On a
     * ten-year 9% bond callable at par at year five (fixtures O1 and O2):
     *
     * <ul>
     *   <li>bought at a <b>premium</b> of 1,050,000, the earliest-call basis reports
     *       7.755768% against 8.246545% to maturity — 49.1 bp and 5,153.16 of year-one
     *       income <em>less</em>;
     *   <li>bought at a <b>discount</b> of 950,000, it reports 10.330130% against
     *       9.806992% — 52.3 bp and 4,969.81 <em>more</em>.
     * </ul>
     *
     * <p>A premium amortised over five years instead of ten is expensed about twice as
     * fast, so the call basis reports less; a discount accreted over five instead of ten
     * recognises income faster, so it reports more. <b>The sign of the divergence
     * depends on whether the instrument was bought above or below par</b>, which means
     * no blanket policy can be assumed conservative and the choice cannot be made once,
     * globally, on prudence grounds. It has to be made per portfolio with the number in
     * front of the committee. A put reverses the holder: fixture O4's put at par at
     * year three is 115.5 bp from the same bond's maturity basis, and a put held by the
     * bank is the bank's own exercise judgement while a call held by the issuer requires
     * the bank to model someone else's rational behaviour — a different estimation
     * problem with different evidence requirements.
     *
     * <p>The premium or discount enters as an integral amount at inception, exactly as
     * for {@link #treasuryBill}: notional is the face value redeemed and the difference
     * between face and price is posted against initial recognition.
     *
     * <p><b>What the bank must still decide.</b> The exercise policy, per portfolio,
     * with both figures computed — divergence register items 7 and 13. If the answer is
     * {@code ECONOMIC_RATIONALITY} or {@code PROBABILITY_WEIGHTED} it is a model under
     * Chapter V and needs independent validation before use. Note also that RBI's FAQ
     * treats exercise of a put before maturity as a sale from HTM unless triggered by
     * credit downgrade or default, which links this accounting position to the
     * portfolio-classification position.
     *
     * @param firstCall       earliest exercise date
     * @param lastCall        latest exercise date; equal to {@code firstCall} for a
     *     one-shot call
     * @param strikePctOfPar  exercise price as a fraction of par — {@code ONE} for par,
     *     above for a call premium. The par balance redeemed is principal and any
     *     premium over par is interest: a call at 102 does not return 102% of the
     *     principal advanced, it returns the principal and pays 2% for early
     *     termination
     * @param policy          the recorded exercise policy; required, because the whole
     *     point is that the engine does not choose
     */
    public static ScheduleBlueprint callableCorporateBond(
        TemplateBasis basis,
        Money faceValue,
        Rate coupon,
        LocalDate firstCall,
        LocalDate lastCall,
        BigDecimal strikePctOfPar,
        ExercisePolicy policy,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(policy, "policy");
        return blueprint(
            basis,
            faceValue,
            new DisbursementProfile.Single(basis.valueDate(), faceValue),
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(coupon),
            new OptionSchedule(
                List.of(new OptionSchedule.EmbeddedOption(
                    OptionSchedule.OptionType.CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    firstCall,
                    lastCall,
                    strikePctOfPar,
                    false)),
                policy),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * Perpetual debt securities — reference § 4 item 26, RBI Investment FAQs; matrix
     * row "Perpetual (SPPI-passing)".
     *
     * <p>{@code BulletAtMaturity} with a {@code CALL} at the first call date and
     * {@code EARLIEST_CALL}.
     *
     * <p>RBI's FAQ position is that a discount or premium on a perpetual debt security
     * is amortised up to the <b>earliest call date</b>, which is why the policy is
     * fixed here rather than parameterised — this family is defined by its exercise
     * policy in a way the callable bond is not. Fixture O3: an 8% perpetual bought at
     * 1,020,000 with a first call at year five gives 7.505597% to earliest call,
     * against a 7.843137% running yield.
     *
     * <p><b>Run the SPPI screen before applying the amortisation rule.</b> The
     * perimeter matters more than the mechanics here: an AT1-style instrument with
     * discretionary coupons and loss-absorption features generally fails SPPI, which
     * sends the whole instrument to FVTPL where <em>no EIR arises at all</em>. That is
     * a cliff, not a gradient, and the classification gate must run before any EIR work
     * is commissioned rather than after (ST-12). The earliest-call rule is therefore
     * relevant only to perpetual debt that is not AT1-like in its cash-flow
     * characteristics.
     *
     * <p>A perpetual has no stated maturity, so {@code basis.statedMaturity()} is the
     * horizon the bank's policy uses to stand in for one — and it is a policy input,
     * not a fact about the instrument. It matters for exactly one thing: it is the
     * contractual-maturity alternative the earliest-call figure is measured against, so
     * ST-7 has two policies to compare and the divergence is quantified rather than
     * asserted.
     *
     * <p><b>What the bank must still decide.</b> The stated horizon convention for a
     * perpetual, documented once and applied uniformly. And the SPPI screen's
     * boundary — which instruments in the book are AT1-like in substance regardless of
     * their label.
     */
    public static ScheduleBlueprint perpetualDebt(
        TemplateBasis basis,
        Money faceValue,
        Rate coupon,
        LocalDate firstCall,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(firstCall, "firstCall");
        return blueprint(
            basis,
            faceValue,
            new DisbursementProfile.Single(basis.valueDate(), faceValue),
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(coupon),
            new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(
                    OptionSchedule.OptionType.CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    firstCall)),
                ExercisePolicy.EARLIEST_CALL),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * Securitisation notes and pass-through certificates — reference § 4 item 29,
     * IFRS 9 B5.4.6; matrix row "Securitisation PTC".
     *
     * <p>{@code Sculpted} from the pool + {@code CprVector}.
     *
     * <p><b>The canonical B5.4.6 case, and the one where the reset-versus-catch-up
     * distinction produces the largest numbers.</b> The note's cash flows amortise on
     * the underlying pool's prepayment behaviour, so the EIR requires a conditional
     * prepayment rate assumption — and a revision to that assumption is <em>not</em> a
     * movement in market rates of interest. It routes to a cumulative catch-up through
     * P&amp;L, not to a reset.
     *
     * <p>The principal ladder is supplied rather than derived because no formula
     * reproduces a pool's amortisation profile. Whatever the ladder leaves outstanding
     * is retained as the terminal balance rather than plugged: a ladder that does not
     * clear the note has told the engine something about the pool, and silently
     * amortising it away discards it.
     *
     * <p><b>The par test decides whether any of this hits P&amp;L at all</b>
     * (09 § 3.4, invariant ST-9), and it is the single most useful result for sizing
     * this workstream. On a note with a 10% pool coupon whose CPR assumption is revised
     * from 10% to 20% at month 24: bought at 1,030,000 the catch-up is a loss of
     * 876.38; bought at 970,000 it is a gain of 878.90; bought at <b>par it is exactly
     * zero</b>. With no premium or discount there is nothing for a change in prepayment
     * speed to accelerate, because the revised flows always discount to the outstanding
     * balance at the contractual rate. So catch-up processing keys on the
     * <b>unamortised premium or discount balance</b> and not on the fact that an
     * assumption moved — keying it on the assumption churns the entire par-priced book
     * on every curve refresh for no P&amp;L effect, which at ten million contracts is
     * correctness-neutral and ruinous, and floods the movement schedule with nil-effect
     * restatements that bury the ones a reviewer needs to see.
     *
     * <p>The purchase premium or discount enters as an integral amount at inception,
     * and it is the quantity ST-9 keys on.
     *
     * <p><b>What the bank must still decide.</b> The CPR curve and its revision
     * calendar, which is a model under Chapter V. Whether derecognition leaves anything
     * to measure at all — resolve that first, and note that the minimum retention
     * requirement means a residual exposure almost always survives. And the tranche
     * perimeter: equity tranches sit at FVTPL under the Investment Directions and carry
     * no EIR, while senior and mezzanine tranches at amortised cost need the full
     * machinery.
     *
     * @param notePar           par value of the note
     * @param poolLadder        principal due per period, from the pool's amortisation
     *     profile
     * @param annualCprByPeriod the prepayment curve, held flat beyond its last point
     */
    public static ScheduleBlueprint securitisationNote(
        TemplateBasis basis,
        Money notePar,
        Rate poolCoupon,
        List<PrincipalProfile.PrincipalStep> poolLadder,
        List<BigDecimal> annualCprByPeriod) {

        Objects.requireNonNull(basis, "basis");
        return blueprint(
            basis,
            notePar,
            new DisbursementProfile.Single(basis.valueDate(), notePar),
            new PrincipalProfile.Sculpted(poolLadder),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(poolCoupon),
            OptionSchedule.none(),
            new BehaviouralOverlay.CprVector(annualCprByPeriod));
    }

    // =========================================================== 3. LIABILITIES

    /**
     * Non-convertible debentures, Tier 2 and infrastructure bonds issued — reference
     * § 4 item 37, Ind AS 109 with ITFG Bulletin 14; matrix row "NCD issued".
     *
     * <p>{@code BulletAtMaturity} + issue costs integral + a {@code CALL} to first
     * call.
     *
     * <p><b>Project this template with
     * {@code BlueprintProjector.withSide(InstrumentSide.LIABILITY)}.</b> The
     * cash-flow shape of an issued bond and a purchased bond are the same shape seen
     * from opposite sides, which is why one blueprint describes both — but the signs
     * are not. Proceeds are an inflow, coupons and redemption are outflows, and the
     * carrying amount is negative, because the roll-forward is
     * {@code closing = opening + interest - cash} and a liability raised has to accrete
     * negative finance cost and be cleared to zero by negative payments. Stating it
     * positive reverses the roll and grows the balance without limit.
     *
     * <p>Note the one asymmetry in the signing, because it is where liability
     * projections usually go wrong: the proceeds flip and the <b>issue costs do
     * not</b>. A {@link com.crisil.eir.calc.projection.FeePosting} is already signed
     * from the holder's perspective, and the holder of an issued bond is the issuer,
     * which genuinely receives the proceeds and genuinely pays the arranger.
     *
     * <p>Issue expenses — arranger fees, rating fees, listing, trustee, stamp duty —
     * are transaction costs amortised into the EIR of the liability. Most Indian banks
     * currently expense them upfront; Ind AS practice is settled that origination fees
     * paid on issuing a financial liability at amortised cost are integral to its EIR,
     * and the ITFG has confirmed the mechanic in the Indian context including the
     * interaction with borrowing-cost capitalisation under Ind AS 23. Call options
     * drive expected life to first call, which is why the call is part of the template
     * rather than an optional extra.
     *
     * <p><b>What the bank must still decide.</b> Whether EIR runs symmetrically on the
     * liability side at all. ACPIR is asset-scoped — paragraph 20 addresses assets and
     * the 6(6) definition references only the gross carrying amount of an asset — so
     * this is the bank's own election. The recommendation is yes: an asset-only EIR
     * produces a net interest margin that is internally inconsistent, and reconciling
     * it afterwards is harder than running both sides. Also which issue expenses are
     * genuinely transaction costs of the instrument as against corporate overhead of
     * the issuance programme.
     *
     * @param proceeds  face value of the issue; the amount raised before issue costs
     * @param firstCall first date the issuer may redeem
     */
    public static ScheduleBlueprint ncdIssued(
        TemplateBasis basis,
        Money proceeds,
        Rate coupon,
        LocalDate firstCall,
        String expectationBasis) {

        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(firstCall, "firstCall");
        return blueprint(
            basis,
            proceeds,
            new DisbursementProfile.Single(basis.valueDate(), proceeds),
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(coupon),
            new OptionSchedule(
                List.of(OptionSchedule.EmbeddedOption.atPar(
                    OptionSchedule.OptionType.CALL,
                    OptionSchedule.OptionHolder.ISSUER_BORROWER,
                    firstCall)),
                ExercisePolicy.EARLIEST_CALL),
            new BehaviouralOverlay.Contractual(expectationBasis));
    }

    /**
     * The side to project an issued instrument under.
     *
     * <p>Named for the same reason as {@link #leaseTerminalKind()}: the default is the
     * asset side, and a liability projected on the asset side produces a positive
     * carrying amount that rolls the wrong way. The failure is not subtle once it has
     * run for a period, and it is completely silent on the first one.
     */
    public static InstrumentSide issuedSide() {
        return InstrumentSide.LIABILITY;
    }

    // ================================================================ internals

    /**
     * The shared revolver composition behind {@link #creditCard} and
     * {@link #kisanCreditCard}.
     *
     * <p>One private helper rather than two near-identical bodies, because the two
     * families differ in exactly one respect that matters to the engine — the calendar
     * — and their ACPIR positions differ in ways that belong in documentation rather
     * than in code. Merging the code and keeping the documentation apart is the right
     * split: a change to how a revolver is approximated should not have to be made
     * twice, and a change to the card book's evidence requirements should not touch
     * KCC.
     *
     * <p>The notional is the limit times the utilisation assumption, computed rather
     * than passed, so that the expected drawn balance the ladder amortises and the
     * notional invariant ST-3 measures against cannot disagree. Left unrounded on
     * purpose: {@code ScheduleBuilder} opens its roll at the profile's own unrounded
     * figure, and a presented notional here would put a sub-paise ST-3 deviation on
     * every revolver in the book.
     *
     * <p>{@code BulletAtMaturity} is the only principal profile a utilisation-driven
     * facility can coherently take, and {@link ScheduleBlueprint} enforces that: a
     * revolver has no contractual repayment schedule, ACPIR 54 contemplates exactly
     * that and permits an approximation, and bending a revolver into an annuity shape
     * to reuse code produces a number with no meaning.
     */
    private static ScheduleBlueprint revolver(
        TemplateBasis basis,
        Money limit,
        BigDecimal averageUtilisation,
        Rate rate,
        int behaviouralLifeMonths,
        List<BigDecimal> utilisationCurve,
        String analysisReference) {

        DisbursementProfile.UtilisationDriven drawn =
            new DisbursementProfile.UtilisationDriven(limit, averageUtilisation);
        return blueprint(
            basis,
            drawn.notional(),
            drawn,
            new PrincipalProfile.BulletAtMaturity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(rate),
            OptionSchedule.none(),
            new BehaviouralOverlay.RevolverBehaviour(
                behaviouralLifeMonths, utilisationCurve, analysisReference));
    }

    /**
     * Assembles the blueprint from the frame and the eight dimensions.
     *
     * <p>Every factory funnels through here so that the currency is always taken from
     * the amount rather than passed alongside it — a blueprint whose notional and
     * currency disagree is rejected at construction, and the only way to guarantee
     * they cannot is to derive one from the other in one place.
     */
    private static ScheduleBlueprint blueprint(
        TemplateBasis basis,
        Money notional,
        DisbursementProfile disbursement,
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        RateProfile rate,
        OptionSchedule options,
        BehaviouralOverlay behaviour) {

        Objects.requireNonNull(notional, "notional");
        return new ScheduleBlueprint(
            notional,
            notional.currency(),
            basis.valueDate(),
            basis.statedMaturity(),
            disbursement,
            principal,
            servicing,
            moratorium,
            rate,
            options,
            behaviour,
            basis.calendar(),
            basis.dayCount(),
            basis.residuePolicy(),
            basis.eclHorizonPeriods());
    }

    /**
     * The day count this engine defaults term lending to, stated once.
     *
     * <p>Indian convention discipline, fixed in policy: actual/365 for money market,
     * 30/360 commonly for term loans. It is exposed as a method rather than written
     * into each factory because it is a policy default that a product may override,
     * and a constant repeated fourteen times is a constant that will eventually be
     * changed thirteen times.
     */
    public static DayCountConvention termLoanDayCount() {
        return DayCountConvention.THIRTY_360_BOND;
    }
}
