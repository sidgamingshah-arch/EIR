package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Steps 6, 7 and 8 of the blueprint pipeline: {@link FlowVectorAssembler} resolving a
 * ladder into the vector the solver discounts, {@link BlueprintProjector} bridging
 * that to {@link com.crisil.eir.calc.projection.ProjectionResult}, and
 * {@link ProductTemplates} naming the ACPIR product families in dimension terms
 * (docs 09 §§ 4–6).
 *
 * <p>This is the last stage at which a cash flow can be got wrong <em>quietly</em>,
 * which is what shapes the assertions below. Upstream of here a person can read the
 * ladder and reconcile it against the lending system; downstream of here nothing sees
 * anything but signed amounts and dates. So each test targets one of the four
 * decisions the assembly makes that cannot be recovered from its output.
 *
 * <p><b>1. Sign, and whose perspective it is.</b> Asserted through the absolute
 * carrying amount, never through a sign predicate. {@code isNegative()} on a
 * liability passes on -990,000 and on -1,010,000 alike, and the second is the actual
 * defect this package documents: negating the whole vector capitalises the issue cost
 * as though it had been received. The figure is the assertion; the sign is a
 * consequence of it.
 *
 * <p><b>2. IC-1, as a comparison between two routes.</b> The carrying amount is
 * derived once, in the assembler, from {@code advanced − netIntegralFee}; the vector
 * carries the advance and the postings as separate flows. IC-1 compares
 * {@code −(net at inception)} against the derived figure, and the comparison has
 * content only where the two routes can disagree — a fee the one includes and the
 * other drops. The zero-posting test below is that case, deliberately.
 *
 * <p><b>3. How many flows a rung becomes.</b> Kind never affects discounting, so no
 * assertion here can move a rate. What it can move is whether the interest leg and
 * INV-3 are readable off the vector at all, and whether the holiday of a capitalising
 * moratorium adds spurious sign changes to the input of multiple-root detection —
 * which it does, two per period, if the {@code +accrual}/{@code −accrual} pair is
 * emitted instead of one zero flow.
 *
 * <p><b>4. That the composition round-trips.</b> The strongest assertion available
 * over an assembled vector is arithmetic this engine has no routine for: discount
 * every flow the assembly produced at the ladder's own contractual rate and the
 * present value must be the net integral amount and nothing else. Nothing in
 * {@link FlowVectorAssembler} computes a present value, so this cannot agree with a
 * wrong vector by construction. It catches a mis-signed leg, a dropped or
 * double-counted terminal lump, an interim draw folded into inception, and a split
 * that misstates the bill — none of which shows up in any single flow.
 *
 * <p><b>Figures.</b> Every number asserted below is a fixture from docs 09 § 6
 * (S1, S2, S5, O8) or closed-form arithmetic stated in the comment that uses it.
 * Nothing was read off a run of the code.
 *
 * <p><b>What is deliberately not asserted.</b> {@code vector.requireNoContingentFlows()}
 * is never used as evidence on its own. The assembler builds every flow through
 * {@link CashFlow#of}, which sets {@code contingent} false, so no input to this class
 * can make that call throw — an assertion no input can falsify is worth less than the
 * comment saying so. The exclusion that can fail is the one on the way in, and that is
 * what {@link Contingency} tests: a contingent charge classified {@code AS_INCURRED}
 * must reach neither the vector nor the net integral fee (FR-206).
 */
@DisplayName("FlowVectorAssembler, BlueprintProjector and the ACPIR product templates")
class FlowVectorAssemblerTest {

    /** Reference case 1's disbursement date. Nothing in this file reads a clock. */
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** Twenty-four monthly periods after the value date — docs 09 § 6's base loan. */
    private static final LocalDate MATURITY_24 = LocalDate.of(2028, 4, 1);

    /** Thirty-six monthly periods after the value date — fixture O8's lease term. */
    private static final LocalDate MATURITY_36 = LocalDate.of(2029, 4, 1);

    /** Docs 09 § 6's base loan, and reference case 1's principal. */
    private static final Money NOTIONAL = Money.inr("1000000");

    /** 1% per period. 12% p.a. nominal with monthly compounding, not 12% / 12. */
    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");

    /**
     * 11% p.a. nominal with monthly compounding — fixture O8's lease rate.
     *
     * <p>{@link Rate} stores at twelve decimal places, so the periodic figure held is
     * 0.009166666667 rather than the exact eleven-twelfths of a percent. The 3.3e-13
     * truncation moves the rental by 3.3e-7 of a rupee and nothing this file asserts.
     */
    private static final BigDecimal ELEVEN_TWELFTHS_PERCENT =
        new BigDecimal("0.11").divide(BigDecimal.valueOf(12), Precision.WORKING);

    /**
     * The ACPIR 46(1) horizon. A separate input from expected life and never derived
     * from it; large enough here to cover the 36-period extended ladders.
     */
    private static final int ECL_HORIZON_PERIODS = 48;

    // ---------------------------------------------------- fixture O8's four figures

    /**
     * O8's contractual effective annual rate: {@code (1 + 0.11/12)^12 − 1}.
     * Docs 09 § 6 quotes 11.571884%.
     */
    private static final BigDecimal O8_CONTRACTUAL_EFFECTIVE = new BigDecimal("0.11571884");

    /** O8 with no integral amount: GCA 1,000,000, EIR 11.571890% (docs 09 § 6). */
    private static final BigDecimal O8_EIR_NO_INTEGRAL_AMOUNT = new BigDecimal("0.11571890");

    /** O8 with 12,000 of fee received: GCA 988,000, EIR 12.377404% (docs 09 § 6). */
    private static final BigDecimal O8_EIR_FEE_RECEIVED = new BigDecimal("0.12377404");

    /** O8 with 12,000 of cost paid: GCA 1,012,000, EIR 10.784759% (docs 09 § 6). */
    private static final BigDecimal O8_EIR_COST_PAID = new BigDecimal("0.10784759");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------------- fixtures

    /**
     * A plain monthly calendar: no business-day convention, no holidays, no month-end
     * rule to fire. The value date is the 1st, so every due date is the 1st and the
     * dating is not what any assertion below is measuring.
     */
    private static ScheduleCalendar plainMonthly() {
        return new ScheduleCalendar(
            ScheduleCalendar.Frequency.MONTHLY,
            ScheduleCalendar.BusinessDayConvention.NONE,
            Set.of(),
            ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
            List.of());
    }

    private static TemplateBasis monthlyBasis(LocalDate maturity) {
        return TemplateBasis.of(VALUE_DATE, maturity, plainMonthly(),
            ProductTemplates.termLoanDayCount(), ECL_HORIZON_PERIODS);
    }

    private static ScheduleBlueprint compose(
        Money notional,
        LocalDate maturity,
        DisbursementProfile disbursement,
        PrincipalProfile principal,
        InterestServicing servicing,
        Moratorium moratorium,
        RateProfile rate) {

        return new ScheduleBlueprint(
            notional, notional.currency(), VALUE_DATE, maturity,
            disbursement, principal, servicing, moratorium, rate,
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual("contractual leg only; no behavioural overlay"),
            plainMonthly(), DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE,
            ECL_HORIZON_PERIODS);
    }

    /** Reference case 1: the 24-period EMI loan at 1% per month, interest serviced. */
    private static ScheduleBlueprint case1() {
        return compose(NOTIONAL, MATURITY_24,
            new DisbursementProfile.Single(VALUE_DATE, NOTIONAL),
            new PrincipalProfile.LevelAnnuity(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));
    }

    /** Fixture S1: the same loan billing equal principal, so the columns are the primitive. */
    private static ScheduleBlueprint equalPrincipal() {
        return compose(NOTIONAL, MATURITY_24,
            new DisbursementProfile.Single(VALUE_DATE, NOTIONAL),
            new PrincipalProfile.EqualPrincipal(),
            new InterestServicing.ServicedEachPeriod(),
            Moratorium.none(),
            new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));
    }

    /**
     * The reference case 1 fee set: 15,000 of processing fee received (ACPIR 52)
     * against 10,000 of DSA commission paid (ACPIR 53), netting to 5,000 of integral
     * fee income and an initial carrying amount of 995,000 (docs 09 § 6).
     */
    private static List<FeePosting> case1Fees() {
        return List.of(
            FeePosting.received("PROCESSING_FEE", Money.inr("15000"), VALUE_DATE,
                FeeClassification.INTEGRAL),
            FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), VALUE_DATE,
                FeeClassification.INTEGRAL, "SELLING"));
    }

    // ---------------------------------------------------------- derived quantities

    /**
     * Every flow the assembly produced, discounted at a periodic rate against its own
     * period ordinal.
     *
     * <p>Arithmetic this engine has no routine for, which is the point. The ladder is
     * the contract, so at the contractual rate the whole vector — the advance, the
     * interim draws, every rung and the terminal lump — must price to the net integral
     * amount and to nothing else. Nothing in {@link FlowVectorAssembler} discounts
     * anything, so this figure cannot agree with a wrong vector by construction.
     *
     * <p>Why it ties exactly, so that the tolerance below is a rounding tolerance and
     * not a fudge. Rolling {@code B_k = B_(k−1)(1+i) + draw_k − billed_k} over the
     * ladder and multiplying through by {@code (1+i)^−k} telescopes to
     * {@code B_N (1+i)^−N = B_0 + Σ (draw_k − billed_k)(1+i)^−k}, which rearranged is
     * exactly {@code PV(vector) = 0} once the terminal {@code B_N} is emitted as its
     * own flow. The only residual is the paise by which each billed total was rounded.
     */
    private static BigDecimal presentValueAt(FlowVector vector, BigDecimal periodicRate) {
        BigDecimal present = BigDecimal.ZERO;
        for (CashFlow flow : vector.flows()) {
            BigDecimal factor = Precision.discountFactor(
                periodicRate, BigDecimal.valueOf(flow.periodIndex()));
            present = present.add(
                flow.amount().amount().multiply(factor, Precision.WORKING), Precision.WORKING);
        }
        return present;
    }

    private static List<CashFlow> flowsAt(FlowVector vector, int periodIndex) {
        List<CashFlow> matching = new ArrayList<>();
        for (CashFlow flow : vector.flows()) {
            if (flow.periodIndex() == periodIndex) {
                matching.add(flow);
            }
        }
        return matching;
    }

    private static List<CashFlow> flowsOfKind(FlowVector vector, FlowKind kind) {
        List<CashFlow> matching = new ArrayList<>();
        for (CashFlow flow : vector.flows()) {
            if (flow.kind() == kind) {
                matching.add(flow);
            }
        }
        return matching;
    }

    private static long countOf(List<InvariantResult> results, InvariantId id) {
        return results.stream().filter(result -> result.id() == id).count();
    }

    private static InvariantResult only(List<InvariantResult> results, InvariantId id) {
        List<InvariantResult> matching =
            results.stream().filter(result -> result.id() == id).toList();
        assertThat(matching).as("results carrying %s", id).hasSize(1);
        return matching.get(0);
    }

    // ================================================ 1. inception and IC-1

    @Nested
    @DisplayName("the inception leg: sign, the integral postings, and IC-1")
    class InceptionAndCarryingAmount {

        @Test
        @DisplayName("an asset advances an outflow, and IC-1 ties the derived 995,000 to the vector")
        void anAssetAdvancesAnOutflowAndIcOneTies() {
            FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                case1(), ScheduleBuilder.build(case1()), case1Fees());

            // Docs 09 § 6: 1,000,000 advanced, net integral fee 5,000 of income,
            // initial carrying amount 995,000. The asset is recorded below par and
            // accretes back up, which is why the EIR exceeds the contractual rate
            // (INV-2). 15,000 received less 10,000 paid = 5,000.
            assertThat(assembly.amountAdvancedAtInception()).isEqualTo(Money.inr("1000000"));
            assertThat(assembly.netIntegralFee()).isEqualTo(Money.inr("5000"));
            assertThat(assembly.initialCarryingAmount()).isEqualTo(Money.inr("995000"));
            assertThat(assembly.side()).isEqualTo(InstrumentSide.ASSET);

            // IC-1 is a comparison between two independent routes to one number: the
            // assembler derived 995,000 as advanced less net fee, and the vector carries
            // the advance and the two postings as three separate flows. -(net at
            // inception) is the figure the solver takes as its target and the figure the
            // amortisation engine opens its roll-forward with; a carrying amount that
            // does not match those is not one this engine can use.
            assertThat(Discounting.netAtInception(assembly.vector()).negate())
                .isEqualTo(assembly.initialCarryingAmount());
            assertThat(SolveRequest.inceptionTarget(assembly.vector()))
                .isEqualTo(Money.inr("995000"));

            // Three flows on the anchor, each with its own kind so that the trace can
            // say which posting produced which amount. The advance is negative: an
            // advance is an outflow to the holder of an asset.
            List<CashFlow> inception = assembly.vector().atInception();
            assertThat(inception).hasSize(3);
            assertThat(inception).allSatisfy(flow ->
                assertThat(flow.date()).isEqualTo(VALUE_DATE));
            assertThat(inception).allSatisfy(flow ->
                assertThat(flow.periodIndex()).isZero());
            assertThat(flowsOfKind(assembly.vector(), FlowKind.DISBURSEMENT))
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("-1000000")));
            assertThat(flowsOfKind(assembly.vector(), FlowKind.INTEGRAL_FEE_RECEIVED))
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("15000")));
            assertThat(flowsOfKind(assembly.vector(), FlowKind.INTEGRAL_COST_PAID))
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("-10000")));
        }

        @Test
        @DisplayName("a zero integral posting leaves the vector but not the carrying amount, and IC-1 still ties")
        void aZeroIntegralPostingLeavesTheVectorButNotTheCarryingAmount() {
            // This is the one case where the two routes to the carrying amount are
            // *deliberately* built from different sets of postings: netIntegralFee sums
            // every INTEGRAL posting, and the inception leg drops the ones that are
            // exactly zero because a zero flow at the anchor is never discounted and
            // would only put a line in the trace that says nothing. IC-1 has to survive
            // that divergence, and it is the only input under which IC-1 is comparing
            // two genuinely different derivations rather than one restated.
            List<FeePosting> withZero = new ArrayList<>(case1Fees());
            withZero.add(FeePosting.received("WAIVED_DOCUMENTATION_FEE", Money.zero(Money.INR),
                VALUE_DATE, FeeClassification.INTEGRAL));

            FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                case1(), ScheduleBuilder.build(case1()), withZero);

            // Same three inception flows as the two-posting set, not four.
            assertThat(assembly.vector().atInception()).hasSize(3);
            assertThat(assembly.netIntegralFee()).isEqualTo(Money.inr("5000"));
            assertThat(assembly.initialCarryingAmount()).isEqualTo(Money.inr("995000"));
            assertThat(Discounting.netAtInception(assembly.vector()).negate())
                .isEqualTo(assembly.initialCarryingAmount());
        }

        @Test
        @DisplayName("an issued liability flips the proceeds and leaves the issue cost alone: -990,000, not -1,010,000")
        void anIssuedLiabilityFlipsTheProceedsAndNotTheIssueCost() {
            // The asymmetry this package documents as where liability projections go
            // wrong. A FeePosting is already signed from the holder's perspective and
            // the holder of an issued bond is the issuer: it genuinely receives the
            // proceeds and genuinely pays the arranger. So on the way to the liability
            // side the proceeds flip and the issue cost does not.
            //
            // -1,000,000 - (-10,000) = -990,000. Negating the whole vector instead gives
            // -1,010,000 — the issue cost capitalised as though it had been received, a
            // 2% error in the opening balance, in the wrong direction, on every issuance
            // in the book. Both figures are negative, which is why the assertion is on
            // the amount and not on isNegative().
            ScheduleBlueprint ncd = ProductTemplates.ncdIssued(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                LocalDate.of(2028, 4, 1),
                "expected life is first call; the issuer's call is its own exercise judgement");
            List<FeePosting> issueCosts = List.of(FeePosting.paid(
                "ARRANGER_FEE", Money.inr("10000"), VALUE_DATE,
                FeeClassification.INTEGRAL, "OTHER"));

            FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                ncd, ScheduleBuilder.build(ncd), issueCosts,
                FlowVectorAssembler.DEFAULT_TERMINAL_KIND, ProductTemplates.issuedSide());

            assertThat(assembly.side()).isEqualTo(InstrumentSide.LIABILITY);
            assertThat(assembly.netIntegralFee()).isEqualTo(Money.inr("-10000"));
            // (An `isNotEqualTo(-1,010,000)` line stood here and was removed: it is
            // entailed by the equality above it. The figure it excluded — the issue cost
            // capitalised as though it had been received — is in the comment above, and the
            // two flow-level assertions below are the independent statement of the same
            // asymmetry, since a whole-vector negation moves the arranger-fee sign too.)
            assertThat(assembly.initialCarryingAmount()).isEqualTo(Money.inr("-990000"));
            assertThat(Discounting.netAtInception(assembly.vector()).negate())
                .isEqualTo(assembly.initialCarryingAmount());

            // The proceeds are an inflow to the issuer; the arranger fee stays an
            // outflow. Those two signs are the whole content of the asymmetry.
            assertThat(flowsOfKind(assembly.vector(), FlowKind.DISBURSEMENT))
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("1000000")));
            assertThat(flowsOfKind(assembly.vector(), FlowKind.INTEGRAL_COST_PAID))
                .singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("-10000")));

            // Every coupon and the redemption are outflows: the roll-forward is
            // closing = opening + interest - cash, so a liability raised is carried
            // negative, accretes negative finance cost and is cleared to zero by
            // negative payments. Stating it positive reverses the roll.
            assertThat(assembly.vector().future()).isNotEmpty();
            assertThat(assembly.vector().future()).allSatisfy(flow ->
                assertThat(flow.amount().isNegative()).isTrue());
        }
    }

    // ================================================ 2. the contingency screen

    @Nested
    @DisplayName("what never reaches the vector")
    class Contingency {

        @Test
        @DisplayName("a contingent charge and a separate service reach neither the vector nor the net fee")
        void contingentAndNonIntegralPostingsNeverReachTheVector() {
            // FR-206. A contingent charge is a real posting that does not belong to the
            // initial carrying amount, and the classification that says so is resolved
            // by the versioned rule set before the projection runs — which is the whole
            // point of resolving it there. Sweeping every contractual charge into the
            // projection overstates yield across the entire book.
            //
            // Note what is being asserted and what is not. requireNoContingentFlows()
            // cannot fail on an assembled vector: every flow is built through
            // CashFlow.of, which sets contingent false, so no input to this class can
            // make that call throw. The assertion that can fail is that the excluded
            // postings left no trace in the amounts, and that is the one below.
            List<FeePosting> mixed = new ArrayList<>(case1Fees());
            mixed.add(FeePosting.received("PREPAYMENT_PENALTY", Money.inr("25000"),
                VALUE_DATE, FeeClassification.AS_INCURRED));
            mixed.add(FeePosting.received("INSURANCE_COMMISSION", Money.inr("7500"),
                VALUE_DATE, FeeClassification.SEPARATE_SERVICE));
            mixed.add(FeePosting.commitment("COMMITMENT_FEE", Money.inr("4000"), VALUE_DATE,
                FeeClassification.OVER_COMMITMENT_PERIOD, bd("0.40")));

            FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                case1(), ScheduleBuilder.build(case1()), mixed);

            // Still 5,000 and still 995,000: three extra postings totalling 36,500
            // changed neither figure.
            assertThat(assembly.netIntegralFee()).isEqualTo(Money.inr("5000"));
            assertThat(assembly.initialCarryingAmount()).isEqualTo(Money.inr("995000"));
            assertThat(assembly.vector().atInception()).hasSize(3);
            assertThat(Discounting.netAtInception(assembly.vector()))
                .isEqualTo(Money.inr("-995000"));

            // And the vector is clean, which is the guarantee the ProjectionResult
            // constructor and the solver both rely on.
            assertThat(assembly.vector().requireNoContingentFlows())
                .isSameAs(assembly.vector());
            assertThat(assembly.vector().flows()).allSatisfy(flow ->
                assertThat(flow.contingent()).isFalse());
        }
    }

    // ================================================ 3. the future leg

    @Nested
    @DisplayName("the future leg: rungs, the terminal lump, and interim draws")
    class TheFutureLeg {

        @Test
        @DisplayName("O8's residual leaves the final rung and becomes its own 200,000 flow")
        void theTerminalLumpLeavesTheFinalRungAndBecomesItsOwnFlow() {
            // Fixture O8: 1,000,000 at 11% p.a. over 36 months with a 200,000 residual.
            // The instalments amortise *toward* the residual, so the rental is
            // (1,000,000 - PV(200,000)) x i / (1 - (1+i)^-36) = 28,024.307027, billed at
            // 28,024.31, and the ladder folds the residual into the final rung at
            // 228,024.31. ScheduleBuilder leaves it there because ST-3 reads the
            // terminal as principal still outstanding; this class takes it back out.
            ScheduleBlueprint lease = ProductTemplates.commercialVehicle(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ELEVEN_TWELFTHS_PERCENT),
                Money.inr("200000"),
                "contractual life; the lessee has no early-termination right");
            InstalmentLadder ladder = ScheduleBuilder.build(lease);
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ladder.rung(1).total().amount()).isEqualByComparingTo(bd("28024.31"));
            assertThat(ladder.rung(36).total().amount()).isEqualByComparingTo(bd("228024.31"));
            assertThat(ladder.terminalBalance()).isEqualTo(Money.inr("200000"));

            FlowVector vector = FlowVectorAssembler.assemble(
                lease, ladder, List.of(), ProductTemplates.leaseTerminalKind(),
                InstrumentSide.ASSET).vector();

            // Two flows at period 36, not one and not three: the rental the borrower is
            // billed and the residual, kept apart so that a lease residual and a balloon
            // stay distinguishable. They are different assets to a controller even
            // though the arithmetic that sizes them is the same.
            List<CashFlow> last = flowsAt(vector, 36);
            assertThat(last).hasSize(2);
            assertThat(last).allSatisfy(flow ->
                assertThat(flow.date()).isEqualTo(MATURITY_36));
            assertThat(flowsOfKind(vector, FlowKind.RESIDUAL_VALUE))
                .singleElement()
                .satisfies(flow -> {
                    assertThat(flow.amount()).isEqualTo(Money.inr("200000"));
                    assertThat(flow.periodIndex()).isEqualTo(36);
                });
            assertThat(flowsOfKind(vector, FlowKind.BALLOON)).isEmpty();

            // The cash total at period 36 is identical either way, which is what makes
            // the separation safe — and what makes the two defects it guards against
            // silent. Leaving the residual folded in *and* emitting it collects
            // 428,024.31; dropping it collects 28,024.31.
            Money atLastPeriod = Money.zero(Money.INR);
            for (CashFlow flow : last) {
                atLastPeriod = atLastPeriod.plus(flow.amount());
            }
            assertThat(atLastPeriod).isEqualTo(Money.inr("228024.31"));

            // And the whole vector prices back to what was advanced, at the contractual
            // rate: no fee, so PV = 0. A dropped residual moves this by its present
            // value, 144,001.06 (docs 09 § 6).
            assertThat(presentValueAt(vector, Rate.monthly(ELEVEN_TWELFTHS_PERCENT).periodic()))
                .isCloseTo(BigDecimal.ZERO, Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("a rung whose principal schedule is the primitive splits into two flows sharing a date")
        void aRungWhosePrincipalScheduleIsThePrimitiveSplitsIntoTwoFlows() {
            // The division follows the contract, not the arithmetic, and it is the same
            // division ScheduleBuilder makes when it decides which component the
            // contract fixes to the paise. Fixture S1: equal principal of 41,666.67 per
            // period, so period 1 bills 41,666.67 + 10,000.00 = 51,666.67 and the
            // principal column is the primitive. Two flows: the reconciliation legs have
            // to be able to tell a principal receipt from an interest receipt.
            assertThat(FlowVectorAssembler.billsSeparateComponents(equalPrincipal())).isTrue();
            FlowVector split = FlowVectorAssembler.vector(
                equalPrincipal(), ScheduleBuilder.build(equalPrincipal()), List.of());

            List<CashFlow> first = flowsAt(split, 1);
            assertThat(first).hasSize(2);
            assertThat(first).extracting(CashFlow::kind)
                .containsExactlyInAnyOrder(FlowKind.PRINCIPAL, FlowKind.INTEREST);
            assertThat(first).extracting(CashFlow::date)
                .containsOnly(LocalDate.of(2026, 5, 1));
            assertThat(flowsOfKind(split, FlowKind.PRINCIPAL).get(0).amount())
                .isEqualTo(Money.inr("41666.67"));
            assertThat(flowsOfKind(split, FlowKind.INTEREST).get(0).amount())
                .isEqualTo(Money.inr("10000.00"));
            assertThat(flowsOfKind(split, FlowKind.COMBINED_EMI)).isEmpty();

            // Reference case 1 on the same loan: the instalment is the primitive and the
            // borrower is charged one EMI of 47,073.47, not two lines. Same principal,
            // same rate, same term — only the dimension that says which component the
            // contract fixes has changed, and with it the number of flows per rung.
            assertThat(FlowVectorAssembler.billsSeparateComponents(case1())).isFalse();
            FlowVector combined =
                FlowVectorAssembler.vector(case1(), ScheduleBuilder.build(case1()), List.of());
            assertThat(flowsAt(combined, 1)).hasSize(1);
            assertThat(flowsOfKind(combined, FlowKind.COMBINED_EMI)).hasSize(24);
            assertThat(flowsAt(combined, 1).get(0).amount()).isEqualTo(Money.inr("47073.47"));
            assertThat(flowsOfKind(combined, FlowKind.PRINCIPAL)).isEmpty();
            assertThat(flowsOfKind(combined, FlowKind.INTEREST)).isEmpty();
        }

        @Test
        @DisplayName("a split that does not sum to the bill falls back to one combined flow carrying the bill")
        void aSplitThatDoesNotSumToTheBillFallsBackToOneCombinedFlow() {
            // A split that does not sum to the bill is not a split. This is not a
            // theoretical guard: a residue policy adjusts a billed instalment without
            // touching the balance path, and a published schedule whose columns do not
            // sum to the bill is a defect even where every figure in it is individually
            // right (03 § 5.7). It is better to carry the bill and lose the split than
            // to carry a split that misstates the bill.
            //
            // The ladder here is hand-built precisely because ScheduleBuilder will not
            // produce one: 60,000 of principal and 10,000 of interest against a billed
            // total of 65,000, a 5,000 discrepancy that survives rounding to paise. The
            // single rung closes its balance, so there is no terminal lump for the
            // assembler to lift out of the bill and the split is the only variable.
            InstalmentLadder inconsistent = InstalmentLadder.of(
                Money.INR,
                List.of(new InstalmentLadder.Rung(
                    1, LocalDate.of(2026, 5, 1),
                    Money.inr("60000"), Money.inr("10000"), Money.inr("65000"),
                    Money.zero(Money.INR))),
                Money.inr("60000"),
                Money.zero(Money.INR));

            FlowVector vector = FlowVectorAssembler.vector(
                equalPrincipal(), inconsistent, List.of());

            // One flow, and it carries 65,000 — the bill — rather than the 70,000 the
            // columns claim. Emitting the two components would have the vector collect
            // 5,000 the borrower was never charged.
            List<CashFlow> emitted = flowsAt(vector, 1);
            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).amount()).isEqualTo(Money.inr("65000"));
            assertThat(emitted.get(0).kind()).isEqualTo(FlowKind.COMBINED_EMI);

            // A sub-paise difference is the rounding residue and belongs in the
            // reconciliation, not in a decision about how many flows to emit. So the
            // same ladder with the components off by a thousandth of a rupee still
            // splits: the comparison is at presentation scale, because that is the scale
            // a bill is stated at.
            InstalmentLadder subPaise = InstalmentLadder.of(
                Money.INR,
                List.of(new InstalmentLadder.Rung(
                    1, LocalDate.of(2026, 5, 1),
                    Money.inr("60000.001"), Money.inr("10000"), Money.inr("70000"),
                    Money.zero(Money.INR))),
                Money.inr("60000.001"),
                Money.zero(Money.INR));
            assertThat(flowsAt(FlowVectorAssembler.vector(equalPrincipal(), subPaise, List.of()), 1))
                .hasSize(2);
        }

        @Test
        @DisplayName("S5's capitalising holiday emits one zero flow per period and adds no sign change")
        void aCapitalisingHolidayEmitsOneZeroFlowPerPeriod() {
            // Fixture S5, the education-loan shape: twelve periods in which nothing is
            // paid and interest compounds into the balance, then 24 EMIs of 53,043.57 on
            // a balance grown to 1,126,825.03. Under EXTEND_TERM the ladder runs 36
            // periods — the borrower gets the full repayment term *after* the holiday.
            ScheduleBlueprint education = ProductTemplates.educationLoan(
                monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT), 12, 0,
                "contractual life; ACPIR 9(6)(i) defers the instalments but grants no"
                    + " prepayment right, so there is no behavioural life to model");
            InstalmentLadder ladder = ScheduleBuilder.build(education);
            assertThat(ladder.length()).isEqualTo(36);
            assertThat(ladder.rung(13).total().amount()).isEqualByComparingTo(bd("53043.57"));

            FlowVector vector = FlowVectorAssembler.vector(education, ladder, List.of());

            // One advance plus 12 holiday rows plus 24 instalments. The holiday rung
            // emits exactly one zero-amount flow rather than its +accrual and -accrual
            // components: the two would net to zero in the present value and add two
            // spurious sign changes per period to Discounting.signChanges, which is the
            // input to multiple-root detection. A zero flow declares the accrual
            // boundary so the holiday shows as a row per period.
            assertThat(vector.size()).isEqualTo(1 + 12 + 24);
            for (int period = 1; period <= 12; period++) {
                List<CashFlow> holiday = flowsAt(vector, period);
                assertThat(holiday).as("flows in holiday period %d", period).hasSize(1);
                assertThat(holiday.get(0).amount().isZero())
                    .as("holiday period %d bills nothing", period).isTrue();
            }
            assertThat(flowsAt(vector, 13)).singleElement()
                .satisfies(flow -> assertThat(flow.amount()).isEqualTo(Money.inr("53043.57")));

            // One sign change: the advance, then 36 non-negative rows. Emitting the
            // accrual pair instead would give 24 more flows and a sign change on every
            // one of them, and the solver would report a vector with 25 sign changes as
            // potentially multi-rooted on an instrument that has exactly one root.
            assertThat(Discounting.signChanges(vector)).isEqualTo(1);

            // The holiday's capitalisation is worth 126,825.03 to the lender against
            // 120,000.00 for a simple deferral (S5 against S6), and the vector has to
            // carry that difference through the zero rows: PV at the contractual 1% is
            // 1,126,825.03 / 1.01^12 = 1,000,000 of instalments against 1,000,000
            // advanced, so PV = 0.
            assertThat(presentValueAt(vector, ONE_PERCENT))
                .isCloseTo(BigDecimal.ZERO, Offset.offset(bd("1")));
        }

        @Test
        @DisplayName("interim draws keep their own date and ordinal, and the inception advance is the draws at ordinal zero")
        void interimDrawsKeepTheirOwnDateAndOrdinal() {
            // 400,000 + 350,000 + 250,000 = 1,000,000 projected at financial closure,
            // with the second and third draws six and twelve periods out. The amount
            // advanced at initial recognition is the *first draw*, not the sanctioned
            // total: using the notional would open the roll-forward at a balance the
            // borrower does not owe yet and would make IC-1 pass on a figure wrong by
            // every undrawn tranche.
            List<Tranche> projected = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("350000")),
                Tranche.of(LocalDate.of(2027, 4, 1), 12, Money.inr("250000")));
            ScheduleBlueprint facility = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(projected, List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));

            // 400,000 and not the 1,000,000 notional. (An `isNotEqualTo(NOTIONAL)` line
            // stood here and was removed: it is entailed by the equality above it, so no
            // input could break one while satisfying the other.)
            assertThat(FlowVectorAssembler.amountAdvancedAtInception(facility))
                .isEqualTo(Money.inr("400000"));

            // futureLeg is exercised directly rather than through a built ladder,
            // because ScheduleBuilder refuses a tranche that lands inside the
            // amortising phase — that re-sizes the instalment mid-schedule and is a
            // DISBURSEMENT_TIMING re-estimation, not a ladder. The shape this method
            // has to emit correctly is nonetheless real: it is what an expected leg
            // truncated past a construction holiday looks like.
            InstalmentLadder ladder = ScheduleBuilder.build(equalPrincipal());
            List<CashFlow> future = FlowVectorAssembler.futureLeg(
                facility, ladder, FlowKind.BALLOON, InstrumentSide.ASSET);

            List<CashFlow> draws = new ArrayList<>();
            for (CashFlow flow : future) {
                if (flow.kind() == FlowKind.DISBURSEMENT) {
                    draws.add(flow);
                }
            }
            assertThat(draws).hasSize(2);
            assertThat(draws.get(0).amount()).isEqualTo(Money.inr("-350000"));
            assertThat(draws.get(0).periodIndex()).isEqualTo(6);
            assertThat(draws.get(0).date()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(draws.get(1).amount()).isEqualTo(Money.inr("-250000"));
            assertThat(draws.get(1).periodIndex()).isEqualTo(12);
            assertThat(draws.get(1).date()).isEqualTo(LocalDate.of(2027, 4, 1));

            // This is the shape that gives the present-value function more than one
            // sign change and therefore more than one mathematically valid IRR
            // (03 § 4.4). Smoothing the draws into one notional advance would remove the
            // multiple-root disclosure along with the multiple roots, and Newton-Raphson
            // fails here — which is why the bisection fallback is mandatory rather than
            // optional.
            //
            // FIVE sign changes, counted rather than bounded, because the count is the
            // disclosure. FlowVector holds its flows in date-then-ordinal order and a draw
            // sorts ahead of the rung sharing its date and ordinal, so the sequence is:
            // −400,000 at inception (1) up to the period-1 rung, (2) down at the period-6
            // draw, (3) up at the period-6 rung, (4) down at the period-12 draw, (5) up at
            // the period-12 rung. Every rung of this equal-principal ladder is positive, so
            // there is nothing else to reverse.
            //
            // A `> 1` bound stood here and gated nothing: the draws-smoothed vector below
            // scores 3 and would have passed it, and so would every count from 2 upward.
            FlowVector withDraws = FlowVector.of(VALUE_DATE, Money.INR, concat(
                FlowVectorAssembler.inceptionLeg(facility, List.of(), InstrumentSide.ASSET),
                future));
            assertThat(Discounting.signChanges(withDraws)).isEqualTo(5);

            // The negative control, and the defect the paragraph above names. The same
            // 1,000,000 facility with the two interim draws merged into one — 400,000 at
            // inception and 600,000 at period 6 — reverses only three times, because one
            // reversal pair has gone with the draw. A projection that lost or merged an
            // interim draw would hand multiple-root detection a sign structure that is an
            // artefact of the smoothing rather than a feature of the instrument, and the
            // count is what separates the two.
            ScheduleBlueprint smoothed = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(List.of(
                    Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                    Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("600000"))),
                    List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));
            FlowVector merged = FlowVector.of(VALUE_DATE, Money.INR, concat(
                FlowVectorAssembler.inceptionLeg(smoothed, List.of(), InstrumentSide.ASSET),
                FlowVectorAssembler.futureLeg(
                    smoothed, ladder, FlowKind.BALLOON, InstrumentSide.ASSET)));
            assertThat(Discounting.signChanges(merged)).isEqualTo(3);
        }

        @Test
        @DisplayName("a draw past the ladder's last rung is dropped rather than carried")
        void aDrawPastTheLastRungIsDropped() {
            // On the contractual leg there are none — the ladder spans the whole term —
            // but on an expected leg truncated to a call or a prepayment there can be,
            // and a drawdown scheduled for after the instrument is expected to have been
            // redeemed is not an expected cash flow. Carrying it would put an outflow
            // beyond the last inflow and hand the solver a vector whose final sign
            // change is an artefact of an assumption rather than a feature of the
            // instrument.
            // A facility whose second draw is scheduled for period 30, on the due date
            // of period 30 of a monthly schedule anchored on 1 April 2026.
            List<Tranche> projected = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("600000")),
                Tranche.of(LocalDate.of(2028, 10, 1), 30, Money.inr("400000")));
            ScheduleBlueprint facility = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(projected, List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));

            // A 24-period leg: the period-30 draw is past its last rung and is dropped.
            List<CashFlow> truncated = FlowVectorAssembler.futureLeg(
                facility, ScheduleBuilder.build(equalPrincipal()), FlowKind.BALLOON,
                InstrumentSide.ASSET);
            for (CashFlow flow : truncated) {
                assertThat(flow.kind())
                    .as("no draw survives past the last rung")
                    .isNotEqualTo(FlowKind.DISBURSEMENT);
            }

            // The same draw against a 36-period leg — the education loan's extended
            // ladder — does survive, so the assertion above is measuring the truncation
            // and not a draw this method never emits at all.
            ScheduleBlueprint education = ProductTemplates.educationLoan(
                monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT), 12, 0,
                "contractual");
            List<CashFlow> reaching = FlowVectorAssembler.futureLeg(
                facility, ScheduleBuilder.build(education), FlowKind.BALLOON,
                InstrumentSide.ASSET);
            assertThat(reaching).anySatisfy(flow -> {
                assertThat(flow.kind()).isEqualTo(FlowKind.DISBURSEMENT);
                assertThat(flow.periodIndex()).isEqualTo(30);
                assertThat(flow.amount()).isEqualTo(Money.inr("-400000"));
            });
        }
    }

    private static List<CashFlow> concat(List<CashFlow> first, List<CashFlow> second) {
        List<CashFlow> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    // ================================================ 4. ST-6 and the refusals

    @Nested
    @DisplayName("ST-6, and what the assembler refuses outright")
    class InvariantsAndRefusals {

        @Test
        @DisplayName("ST-6 fails with the shortfall where the projected draws do not sum to the notional")
        void st6FailsWhereTheDrawsDoNotSumToTheNotional() {
            // A facility loaded with a missing tranche amortises a balance the draws
            // never produced. 400,000 + 350,000 = 750,000 against a 1,000,000 notional:
            // ST-6 reports the 250,000 gap rather than the projection quietly running on
            // a notional no drawdown supports.
            List<Tranche> short750 = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("350000")));
            ScheduleBlueprint understated = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(short750, List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));

            InvariantResult st6 = FlowVectorAssembler.tranchesSumToNotional(understated);
            assertThat(st6.id()).isEqualTo(InvariantId.ST_6);
            assertThat(st6.satisfied()).isFalse();
            assertThat(st6.deviation().abs()).isEqualByComparingTo(bd("250000"));

            // The date limb, and it is checked first: a draw before the anchor is what
            // every discount factor in the vector is measured from, so it is reported as
            // a named invariant failure rather than left to surface as an arithmetic
            // exception several frames away.
            List<Tranche> earlyDraw = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("600000")),
                Tranche.of(LocalDate.of(2026, 3, 1), 6, Money.inr("400000")));
            ScheduleBlueprint predated = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(earlyDraw, List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));

            InvariantResult dated = FlowVectorAssembler.tranchesSumToNotional(predated);
            assertThat(dated.satisfied()).isFalse();
            assertThat(dated.detail()).contains("dated before the value date");
            assertThat(dated.deviation()).isEqualByComparingTo(BigDecimal.ONE);

            // Worth pinning the tension rather than leaving it implied. The named
            // failure above is only reachable by consulting this method directly:
            // assemble() builds the FlowVector before it evaluates any invariant, and
            // FlowVector rejects a flow dated before its anchor, so a caller going
            // through the stage gets the exception the invariant text says is the worse
            // audit record. ScheduleBuilder.build refuses the same blueprint for the
            // same reason. Documented, not asserted as desirable.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(
                    predated, ScheduleBuilder.build(equalPrincipal()), List.of()))
                .withMessageContaining("precedes anchor");
        }

        @Test
        @DisplayName("ST-6 passes on a complete drawdown schedule and is absent where the profile is not tranched")
        void st6IsAssertedOnlyWhereItHasSomethingToMeasure() {
            List<Tranche> complete = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("350000")),
                Tranche.of(LocalDate.of(2027, 4, 1), 12, Money.inr("250000")));
            ScheduleBlueprint facility = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Tranched(complete, List.of(), bd("0.05")),
                new PrincipalProfile.EqualPrincipal(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)));
            assertThat(FlowVectorAssembler.tranchesSumToNotional(facility).satisfied()).isTrue();

            // A single advance has no drawdown schedule to deviate from, so the assembly
            // asserts nothing rather than fabricating an ST-6 pass. A fabricated pass is
            // worse than no result: it puts a satisfied invariant in the audit record
            // for a comparison that was never made.
            FlowVectorAssembler.Assembly single = FlowVectorAssembler.assemble(
                case1(), ScheduleBuilder.build(case1()), case1Fees());
            assertThat(single.invariants()).isEmpty();
            assertThat(single.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("the terminal label is required rather than derived, and currencies must agree")
        void theTerminalLabelIsRequiredAndCurrenciesMustAgree() {
            ScheduleBlueprint blueprint = case1();
            InstalmentLadder ladder = ScheduleBuilder.build(blueprint);

            // A balloon and a lease residual are arithmetically identical and are
            // different assets to a controller, so the label is carried rather than
            // derived. Passing any other kind is a programming error, not a data
            // condition: there is no third answer for the engine to pick.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(
                    blueprint, ladder, List.of(), FlowKind.PRINCIPAL, InstrumentSide.ASSET))
                .withMessageContaining("a terminal lump is a BALLOON or a RESIDUAL_VALUE")
                .withMessageContaining("different assets to a");
            assertThat(FlowVectorAssembler.DEFAULT_TERMINAL_KIND).isEqualTo(FlowKind.BALLOON);

            // A ladder in the wrong currency would produce a vector FlowVector rejects
            // flow by flow, with nothing left to say which stage mismatched. Caught here
            // with both codes named.
            Currency usdollar = Currency.getInstance("USD");
            InstalmentLadder usd = InstalmentLadder.of(
                usdollar,
                List.of(new InstalmentLadder.Rung(
                    1, LocalDate.of(2026, 5, 1),
                    Money.of("1000", usdollar),
                    Money.of("10", usdollar),
                    Money.of("1010", usdollar),
                    Money.of("0", usdollar))),
                Money.of("1000", usdollar),
                Money.of("0", usdollar));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(blueprint, usd, List.of()))
                .withMessageContaining("the ladder is USD and the blueprint is INR");

            // A fee in the wrong currency names the fee code, because the posting is
            // what has to be corrected in the feed.
            List<FeePosting> foreign = List.of(FeePosting.received("ARRANGER_FEE",
                Money.of("15000", usdollar), VALUE_DATE, FeeClassification.INTEGRAL));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(blueprint, ladder, foreign))
                .withMessageContaining("fee ARRANGER_FEE is USD and the contract is INR");

            assertThatNullPointerException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(null, ladder, List.of()));
            assertThatNullPointerException()
                .isThrownBy(() -> FlowVectorAssembler.assemble(blueprint, ladder, null));
        }

        @Test
        @DisplayName("periodic indexing is taken only where both legs license it")
        void periodicIndexingIsTakenOnlyWhereBothLegsLicenseIt() {
            ScheduleBlueprint blueprint = case1();
            FlowVector clean =
                FlowVectorAssembler.vector(blueprint, ScheduleBuilder.build(blueprint), List.of());

            // A clean monthly ladder: every flow on a period boundary, no broken period.
            // Periodic indexing is exactly equivalent and cheaper, so it is taken.
            ConventionSelector.Choice both =
                FlowVectorAssembler.convention(blueprint, clean, clean);
            assertThat(both.periodicIndexEligible()).isTrue();
            assertThat(both.convention()).isInstanceOf(TimeConvention.PeriodicIndex.class);

            // The same contractual leg against an expected leg carrying one flow off the
            // period boundary — an expected prepayment landing mid-month, which is the
            // ordinary case on a CPR overlay. The two legs are discounted by different
            // consumers (the EIR over the expected leg, the 10% test and the catch-up
            // over the contractual one), so letting them carry different conventions
            // would put a convention difference inside a comparison built to isolate a
            // cash-flow difference. Both must pass, or neither takes the optimisation.
            List<CashFlow> shifted = new ArrayList<>(clean.flows());
            shifted.add(CashFlow.of(LocalDate.of(2026, 5, 15), 1, Money.inr("100000"),
                FlowKind.EXPECTED_PREPAYMENT));
            FlowVector broken = FlowVector.of(VALUE_DATE, Money.INR, shifted);
            assertThat(broken.periodicIndexEligible(12)).isFalse();

            ConventionSelector.Choice mixed =
                FlowVectorAssembler.convention(blueprint, clean, broken);
            assertThat(mixed.periodicIndexEligible()).isFalse();
            assertThat(mixed.convention()).isInstanceOf(TimeConvention.ActualDate.class);
            // The declining leg's basis is returned, not the passing leg's: it is the
            // only one that says which leg voided the optimisation, which is the one
            // fact a reviewer asking why a vector was discounted on dates needs.
            assertThat(mixed.basis()).contains("precondition not met");
        }
    }

    // ================================================ 5. the projector bridge

    @Nested
    @DisplayName("BlueprintProjector: the bridge, and the invariants it hands on")
    class TheProjectorBridge {

        @Test
        @DisplayName("PC-1 is asserted exactly once per projection")
        void pc1IsAssertedExactlyOncePerProjection() {
            // The defect this guards is on the record in PenalChargeScreen.combine: two
            // PC-1 results were emitted for one period, and on an unattested LMS feed
            // they disagreed — the fee route passing because the rule set had resolved
            // every posting, the schedule route failing because nothing attested to the
            // feed. allInvariantsSatisfied still came out false so the breach was not
            // lost, but anything looking PC-1 up *by name* got whichever of the two
            // contradictory answers came first in the list. PC-1 is a named control an
            // auditor asks for by name; it gets one answer per period.
            //
            // Counted across a projection that gathers invariants from four stages —
            // the ladder's ST-3 and ST-5, the assembly's ST-6, the behavioural stage's,
            // and the projector's own PC-1 and ST-10 — because a second PC-1 arriving
            // from any of them is exactly the shape of the original defect.
            BlueprintProjection projection =
                new BlueprintProjector(case1()).projectBlueprint(case1Fees());

            assertThat(countOf(projection.invariants(), InvariantId.PC_1)).isEqualTo(1);
            InvariantResult pc1 = only(projection.invariants(), InvariantId.PC_1);
            assertThat(pc1.satisfied()).isTrue();
            assertThat(pc1.detail()).contains("2 posting(s) screened");

            // And once again on a tranched, capitalising, sculpted projection, which
            // runs more stages and therefore has more opportunities to contribute a
            // second one. The count is the assertion; the pass is incidental.
            BlueprintProjection heavier =
                new BlueprintProjector(projectFinanceFixture()).projectBlueprint(List.of());
            assertThat(countOf(heavier.invariants(), InvariantId.PC_1)).isEqualTo(1);
            assertThat(heavier.invariants()).hasSizeGreaterThan(4);
            assertThat(countOf(heavier.invariants(), InvariantId.ST_6)).isEqualTo(1);

            // PC-1 and ST-6 are unique. ST-3 and ST-5 are not — see the disabled test
            // below, which is the same defect on two other identifiers.
        }

        @Test
        @Disabled("DEFECT: ST-3 is emitted three times and ST-5 twice under one invariant"
            + " id per projection, which is the defect PenalChargeScreen.combine exists to"
            + " prevent. See the comment below.")
        @DisplayName("DEFECT: every invariant identifier carries exactly one result per projection")
        void everyInvariantIdentifierCarriesExactlyOneResultPerProjection() {
            // The generalisation of the rule PC-1 already follows, and the reason PC-1
            // follows it: "PC-1 is a named control that an auditor asks for by name; it
            // gets one answer per period, and the answer is the conjunction"
            // (PenalChargeScreen.combine). Nothing about that argument is specific to
            // PC-1. ST-3 is asked for by name too.
            //
            // What BlueprintProjector.projectBlueprint assembles for a contractual-overlay
            // projection, in the order it assembles it:
            //
            //   contractualLadder.invariants()  ->  ST-3 (ladder), ST-5 (ladder)
            //   assembly.invariants()           ->  ST-6 where tranched
            //   behaviour.invariants()          ->  ST-3 (behavioural), and then, because
            //                                       BehaviouralAdjustment.of appends
            //                                       expected.invariants() and the expected
            //                                       ladder *is* the contractual one under a
            //                                       Contractual overlay, ST-3 (ladder) and
            //                                       ST-5 (ladder) a second time
            //   PC-1, ST-10
            //
            // So a plain reference case 1 projection publishes eight invariant results of
            // which three are ST-3 and two are ST-5.
            //
            // Two of the three ST-3s are the identical ladder result, which is merely
            // noise in the audit record. The third is a *different claim* under the same
            // identifier: the ladder's ST-3 says scheduled principal plus the terminal
            // balance equals the principal advanced, and the behavioural ST-3 says
            // principal recovered on the expected leg equals principal recovered on the
            // contractual leg. Those are independent, and on a CPR or rollover overlay
            // they genuinely disagree — a prepayment curve that moved principal in amount
            // rather than in time fails the second while the first still passes.
            //
            // The consequence is exactly the one PenalChargeScreen.combine records:
            // allInvariantsSatisfied() still comes out false so the breach is not lost,
            // but anything resolving ST-3 by identifier gets whichever of the two
            // contradictory answers happens to come first in the list — here the ladder's,
            // which is the one that passes. A movement schedule or a control report keyed
            // on the identifier reports ST-3 satisfied on a projection whose behavioural
            // leg does not tie.
            //
            // The fix is the one already built for PC-1: conjoin the results sharing an
            // identifier, concatenating the evidence, so that which routes were asserted
            // survives and the answer is the conjunction. PenalChargeScreen.combine is
            // PC-1-specific by construction (it rejects any other id), so the general form
            // belongs beside it or on InvariantResult. Deduplicating the two identical
            // ladder results is a separate and smaller fix: BehaviouralAdjustment.of
            // appending expected.invariants() is correct in isolation, and the double
            // counting only arises because the projector also gathers them itself.
            //
            // Not fixed here: this is a test-writing change, and a silent fix buried in
            // one would be worse than a documented failure.
            BlueprintProjection projection =
                new BlueprintProjector(case1()).projectBlueprint(case1Fees());

            for (InvariantId id : InvariantId.values()) {
                assertThat(countOf(projection.invariants(), id))
                    .as("results carrying %s", id)
                    .isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("IC-1 appears once, is computed on the assembled vector, and ties to the assembly's own figure")
        void ic1AppearsOnceAndTiesToTheAssemblysOwnFigure() {
            BlueprintProjection projection =
                new BlueprintProjector(case1()).projectBlueprint(case1Fees());

            // IC-1 is computed inside ProjectionResult from the vector the assembler
            // built, and the assembly carries the figure it derived. The whole reason
            // the assembly returns the carrying amount rather than letting the caller
            // recompute notional - fee is that a caller with its own second route has
            // replaced the comparison with a tautology.
            assertThat(countOf(projection.invariants(), InvariantId.IC_1)).isEqualTo(1);
            assertThat(projection.invariants().get(0).id())
                .as("IC-1 is first because ProjectionResult computes it at construction")
                .isEqualTo(InvariantId.IC_1);
            assertThat(projection.projection().initialRecognitionCheck().satisfied()).isTrue();
            assertThat(projection.assembly().initialCarryingAmount())
                .isEqualTo(Money.inr("995000"));
            assertThat(projection.projection().initialCarryingAmount())
                .isEqualTo(Money.inr("995000"));
            assertThat(projection.projection().netCashAtInception())
                .isEqualTo(Money.inr("-995000"));
            assertThat(projection.allInvariantsSatisfied()).isTrue();

            // No solver, so no optionality was measured and the divergence is zero
            // rather than unreported. On an unoptioned instrument that zero is the
            // answer; the constructor refuses the optioned case outright.
            assertThat(projection.hasExpectedLife()).isFalse();
            assertThat(projection.optionalityDivergenceBps()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(projection.projection().expectedEqualsContractualByPolicy()).isTrue();
            assertThat(projection.contractualLadder().length()).isEqualTo(24);
        }

        @Test
        @DisplayName("the projector refuses terms that are not the ones its blueprint describes")
        void theProjectorRefusesTermsItDoesNotDescribe() {
            BlueprintProjector projector = new BlueprintProjector(case1());

            // Three attributes are checked and the rest of ContractTerms deliberately is
            // not: currency, the recognition date, and the rate's compounding frequency.
            // Those are the three whose mismatch changes a number without changing
            // anything visible — a vector anchored a day away from the carrying amount's
            // own date, or an annual rate applied to a monthly schedule.
            ContractTerms matching = ContractTerms.of(
                NOTIONAL, Rate.monthly(ONE_PERCENT), 24, 12, VALUE_DATE,
                LocalDate.of(2026, 5, 1), DayCountConvention.THIRTY_360_BOND,
                ScheduleShape.ANNUITY_EMI, RateType.FIXED);
            assertThat(projector.supports(matching)).isTrue();
            assertThat(projector.label()).startsWith("Blueprint[SINGLE + LEVEL_ANNUITY");

            ContractTerms wrongAnchor = ContractTerms.of(
                NOTIONAL, Rate.monthly(ONE_PERCENT), 24, 12, LocalDate.of(2026, 4, 2),
                LocalDate.of(2026, 5, 2), DayCountConvention.THIRTY_360_BOND,
                ScheduleShape.ANNUITY_EMI, RateType.FIXED);
            assertThat(projector.supports(wrongAnchor)).isFalse();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> projector.project(wrongAnchor, case1Fees()))
                .withMessageContaining("not the ones this blueprint describes")
                .withMessageContaining("ProjectorRegistry.prepend");

            // A blueprint projector is per-contract configuration, so it goes at the
            // front of a registry rather than into the standard list where two of them
            // would both claim the same terms. project() on matching terms is the same
            // projection as projectBlueprint, narrowed.
            assertThat(projector.project(matching, case1Fees()).initialCarryingAmount())
                .isEqualTo(Money.inr("995000"));
        }

        @Test
        @DisplayName("an optioned blueprint with no solver, and a rate quoted at the wrong frequency, are refused at construction")
        void refusalsAtConstruction() {
            // Refusing early matters: a registry that accepted the projector and then
            // failed on the tenth contract of a batch has already published nine.
            // Reporting an optioned instrument's life without the divergence is the
            // ST-7 finding rather than the answer.
            ScheduleBlueprint callable = ProductTemplates.callableCorporateBond(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                LocalDate.of(2028, 4, 1), LocalDate.of(2028, 4, 1), BigDecimal.ONE,
                ExercisePolicy.EARLIEST_CALL,
                "RBI Investment FAQ amortises to earliest call for this portfolio");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlueprintProjector(callable))
                .withMessageContaining("Supply a")
                .withMessageContaining("RateSolver")
                .withMessageContaining("ST-7");
            assertThat(new BlueprintProjector(callable, new BracketedNewtonSolver())
                .measuresOptionality()).isTrue();
            assertThat(new BlueprintProjector(case1()).measuresOptionality()).isFalse();

            // A 12% annual rate handed to a monthly ladder bills twelve times the
            // interest and produces a schedule that looks perfectly orderly. Interest
            // must be computed from the rate for the period the schedule is built on,
            // never by dividing an annual rate.
            ScheduleBlueprint annualRateMonthlyCalendar = compose(
                NOTIONAL, MATURITY_24,
                new DisbursementProfile.Single(VALUE_DATE, NOTIONAL),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.annualEffective(bd("0.12"))));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlueprintProjector(annualRateMonthlyCalendar))
                .withMessageContaining("is quoted x1 but calendar MONTHLY bills x12")
                .withMessageContaining("never by dividing an annual rate");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlueprintProjector(case1(), null,
                    OptionalityJudgements.none(), -1, FlowKind.BALLOON, InstrumentSide.ASSET))
                .withMessageContaining("spreadPeriods must be >= 0");
        }

        @Test
        @DisplayName("ST-10 runs one way only, and a breach throws rather than being reported")
        void st10RunsOneWayOnlyAndABreachThrows() {
            // The claim ST-10 makes is asymmetric, and the asymmetry is the content.
            // Where the calendar admits periodic indexing the optimisation is still not
            // granted — the vector's own flows decide, and a moratorium or a mid-period
            // draw voids it on a perfectly regular monthly calendar. What ST-10 forbids
            // is the reverse: taking period ordinals as a measure of time on a schedule
            // whose periods are not equal.
            ScheduleBlueprint monthly = case1();
            assertThat(BlueprintProjector.calendarForcesActualDating(
                monthly, new TimeConvention.PeriodicIndex(12)).satisfied()).isTrue();
            assertThat(BlueprintProjector.calendarForcesActualDating(
                monthly, new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND))
                .satisfied())
                .as("a uniform calendar imposes no constraint in either direction")
                .isTrue();

            // A modified-following convention can move a due date, so the periods are no
            // longer equal by construction. Actual dating passes; a periodic index on
            // the same calendar is the breach.
            ScheduleBlueprint adjusted = new ScheduleBlueprint(
                NOTIONAL, Money.INR, VALUE_DATE, MATURITY_24,
                new DisbursementProfile.Single(VALUE_DATE, NOTIONAL),
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.ServicedEachPeriod(),
                Moratorium.none(),
                new RateProfile.Fixed(Rate.monthly(ONE_PERCENT)),
                OptionSchedule.none(),
                new BehaviouralOverlay.Contractual("contractual leg only"),
                new ScheduleCalendar(
                    ScheduleCalendar.Frequency.MONTHLY,
                    ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING,
                    Set.of(),
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
                    List.of()),
                DayCountConvention.THIRTY_360_BOND, ResiduePolicy.LMS_AUTHORITATIVE,
                ECL_HORIZON_PERIODS);

            InvariantResult breach = BlueprintProjector.calendarForcesActualDating(
                adjusted, new TimeConvention.PeriodicIndex(12));
            assertThat(breach.id()).isEqualTo(InvariantId.ST_10);
            assertThat(breach.satisfied()).isFalse();
            assertThat(breach.detail()).contains("period ordinals are not a valid measure of time");
            assertThat(BlueprintProjector.calendarForcesActualDating(
                adjusted, new TimeConvention.ActualDate(DayCountConvention.THIRTY_360_BOND))
                .satisfied()).isTrue();

            // Projected end to end, the adjusted calendar reaches actual dating on its
            // own — ST-10 is not special-cased, it falls out of the convention check.
            // A wrong convention here is not a flagged rate, it is a wrong one, and
            // nothing downstream can compensate for it, so nothing downstream is given
            // the chance: the projector asserts rather than reports.
            BlueprintProjection projected =
                new BlueprintProjector(adjusted).projectBlueprint(List.of());
            assertThat(projected.conventionChoice().periodicIndexEligible()).isFalse();
            assertThat(projected.conventionChoice().convention())
                .isInstanceOf(TimeConvention.ActualDate.class);
            assertThat(only(projected.invariants(), InvariantId.ST_10).satisfied()).isTrue();
        }
    }

    // ================================================ 6. the product templates

    /**
     * The project-finance fixture: draws over a twelve-period construction phase with
     * interest capitalising, then a sculpted ladder over the 24 amortising periods.
     *
     * <p>400,000 + 350,000 + 250,000 = 1,000,000 projected at financial closure, which
     * is what ST-6 measures. The sculpted steps repay 40,000 a period from period 13,
     * totalling 960,000 against a balance grown by the capitalisation to roughly
     * 1,064,000 — so the ladder does not clear the facility and what it leaves is
     * retained as the terminal balance rather than plugged. That retention is the
     * point: a ladder sized to projected free cash flow that does not clear the
     * facility has told the engine something.
     */
    private static ScheduleBlueprint projectFinanceFixture() {
        List<PrincipalProfile.PrincipalStep> ladder = new ArrayList<>();
        for (int period = 13; period <= 36; period++) {
            ladder.add(new PrincipalProfile.PrincipalStep(period, Money.inr("40000")));
        }
        return ProductTemplates.projectFinance(
            monthlyBasis(MATURITY_24),
            NOTIONAL,
            List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("350000")),
                Tranche.of(LocalDate.of(2027, 4, 1), 12, Money.inr("250000"))),
            bd("0.05"),
            ladder,
            12,
            "MCLR-1Y",
            bd("250"),
            List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)),
            Rate.monthly(ONE_PERCENT),
            "contractual life; a DCCO deferment would be assessed separately");
    }

    @Nested
    @DisplayName("ProductTemplates: the ACPIR product matrix as composed dimensions")
    class TheProductMatrix {

        @Test
        @DisplayName("every family composes the dimension variants its ACPIR entry claims")
        void everyFamilyComposesTheVariantsItsEntryClaims() {
            // Constructing each of these is itself an assertion: ScheduleBlueprint
            // rejects an incoherent combination at construction and names the conflict
            // (ST-11), so a family written down wrong fails here rather than three
            // stages later on a live contract. What the assertions add is *which*
            // variant, because the coherence rules do not distinguish between two
            // variants that both compose. An education loan on DeferredSimple instead of
            // CapitalisedEachPeriod composes perfectly and is 34.6 bp wrong; a lease on
            // LevelAnnuity instead of Balloon composes and silently amortises away the
            // residual the borrower still owes.

            ScheduleBlueprint housing = ProductTemplates.retailHousingFloating(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                "EBLR", bd("250"),
                List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)),
                LocalDate.of(2027, 4, 1),
                List.of(bd("0.08"), bd("0.12")));
            // Both the B5.4.4 election and the curve, because the curve is needed
            // regardless — it feeds the 46(1) horizon, the ECL profile and the
            // disclosure — and the engine reports the divergence between the policies.
            assertThat(housing.options().exercisePolicy()).isEqualTo(ExercisePolicy.NEXT_REPRICING);
            assertThat(housing.options().ofType(OptionSchedule.OptionType.PREPAYMENT)).hasSize(1);
            assertThat(housing.behaviour()).isInstanceOf(BehaviouralOverlay.CprVector.class);
            assertThat(housing.principal()).isInstanceOf(PrincipalProfile.LevelAnnuity.class);
            assertThat(housing.nextRepricingIsAvailable()).isTrue();
            // Struck at par: RBI's restrictions on foreclosure charges for floating-rate
            // individual loans leave almost no prepayment-penalty cash flow to model.
            assertThat(housing.options().options().get(0).strikePctOfPar())
                .isEqualByComparingTo(BigDecimal.ONE);

            ScheduleBlueprint education = ProductTemplates.educationLoan(
                monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT), 9, 3, "contractual");
            // 9 + 3 = 12 periods of holiday, capitalising, extending the term. Both
            // consequences of ACPIR 9(6)(i) and neither implying the other.
            assertThat(education.moratorium().periods()).isEqualTo(12);
            assertThat(education.moratorium().kind())
                .isEqualTo(Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED);
            assertThat(education.moratorium().termEffect())
                .isEqualTo(Moratorium.MoratoriumTermEffect.EXTEND_TERM);
            assertThat(education.servicing().compounds()).isTrue();

            ScheduleBlueprint pf = projectFinanceFixture();
            assertThat(pf.disbursement()).isInstanceOf(DisbursementProfile.Tranched.class);
            assertThat(pf.principal()).isInstanceOf(PrincipalProfile.Sculpted.class);
            assertThat(pf.servicing()).isInstanceOf(InterestServicing.CapitalisedEachPeriod.class);
            assertThat(pf.rate()).isInstanceOf(RateProfile.Floating.class);
            assertThat(pf.moratorium().periods()).isEqualTo(12);

            ScheduleBlueprint lease = ProductTemplates.commercialVehicle(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ELEVEN_TWELFTHS_PERCENT),
                Money.inr("200000"), "contractual");
            assertThat(lease.principal())
                .isEqualTo(new PrincipalProfile.Balloon(Money.inr("200000")));
            assertThat(lease.principal().hasTerminalLump()).isTrue();
            assertThat(ProductTemplates.leaseTerminalKind()).isEqualTo(FlowKind.RESIDUAL_VALUE);

            ScheduleBlueprint gold = ProductTemplates.goldLoan(
                monthlyBasis(LocalDate.of(2026, 10, 1)), NOTIONAL,
                Rate.monthly(ONE_PERCENT), 2, bd("0.70"));
            assertThat(gold.principal()).isInstanceOf(PrincipalProfile.BulletAtMaturity.class);
            assertThat(gold.behaviour())
                .isEqualTo(new BehaviouralOverlay.RolloverAssumption(2, bd("0.70")));

            ScheduleBlueprint card = ProductTemplates.creditCard(
                monthlyBasis(MATURITY_24), NOTIONAL, bd("0.60"), Rate.monthly(bd("0.03")),
                18, List.of(bd("0.60"), bd("0.65")), "MODEL-CARD-LIFE-2026-01");
            // The notional is the limit times the utilisation assumption, computed
            // rather than passed, so that the expected drawn balance the ladder
            // amortises and the notional ST-3 measures against cannot disagree.
            // 1,000,000 x 0.60 = 600,000, and it is the drawn part, not the limit:
            // striking a rate on the undrawn limit spreads the fee over money that
            // never left the bank.
            assertThat(card.notional()).isEqualTo(Money.inr("600000"));
            assertThat(card.disbursement())
                .isInstanceOf(DisbursementProfile.UtilisationDriven.class);
            assertThat(card.principal()).isInstanceOf(PrincipalProfile.BulletAtMaturity.class);

            ScheduleBlueprint wcdl = ProductTemplates.workingCapitalDemandLoan(
                TemplateBasis.moneyMarket(VALUE_DATE, LocalDate.of(2026, 7, 1)),
                NOTIONAL, Rate.periodic(bd("0.0225"), 4), "contractual");
            // Paired with the money-market frame: one flow, one supplied date, and an
            // explicit-date calendar that cannot license periodic indexing. Day-count
            // sensitivity runs inversely to tenor, so this is where it matters.
            assertThat(wcdl.dayCount()).isEqualTo(DayCountConvention.ACT_365F);
            assertThat(wcdl.calendar().frequency().requiresExplicitDates()).isTrue();
            assertThat(wcdl.calendar().admitsPeriodicIndexing()).isFalse();

            ScheduleBlueprint staff = ProductTemplates.staffHousingLoan(
                monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(bd("0.005")), "contractual");
            // The blueprint carries the *concessional* rate, because that is what the
            // contract bills and what the ladder must reproduce. The market rate is an
            // input where the vector is discounted; solving the concessional flows
            // against the amount advanced would recover the concessional rate and report
            // a below-market loan as though it were fairly priced.
            assertThat(staff.rate().rateForPeriod(1).periodic()).isEqualByComparingTo(bd("0.005"));
            assertThat(staff.principal()).isInstanceOf(PrincipalProfile.LevelAnnuity.class);

            ScheduleBlueprint bill = ProductTemplates.treasuryBill(
                TemplateBasis.moneyMarket(VALUE_DATE, LocalDate.of(2026, 7, 1)),
                NOTIONAL, Rate.periodic(bd("0.0175"), 4));
            assertThat(bill.principal()).isInstanceOf(PrincipalProfile.NoneUntilMaturity.class);
            assertThat(bill.servicing()).isInstanceOf(InterestServicing.DiscountedUpfront.class);
            // The notional is the face value redeemed at maturity, and the disbursement
            // is that same face value. Modelling the discount as a smaller advance
            // instead leaves the ladder redeeming what it advanced, which is an
            // instrument with no return at all.
            assertThat(bill.disbursement().notional()).isEqualTo(NOTIONAL);

            ScheduleBlueprint callable = ProductTemplates.callableCorporateBond(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                LocalDate.of(2028, 4, 1), LocalDate.of(2028, 10, 1), bd("1.02"),
                ExercisePolicy.CONTRACTUAL_MATURITY, "RBI Investment Directions reading");
            // The policy is a required parameter of this factory and not a default,
            // because the sign of the divergence depends on whether the instrument was
            // bought above or below par: 49.1 bp one way at a premium and 52.3 bp the
            // other at a discount (fixtures O1 and O2). No blanket policy can be
            // assumed conservative.
            assertThat(callable.options().exercisePolicy())
                .isEqualTo(ExercisePolicy.CONTRACTUAL_MATURITY);
            assertThat(callable.options().options().get(0).strikePctOfPar())
                .isEqualByComparingTo(bd("1.02"));
            assertThat(callable.options().earliestExercise())
                .contains(LocalDate.of(2028, 4, 1));

            ScheduleBlueprint perpetual = ProductTemplates.perpetualDebt(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                LocalDate.of(2028, 4, 1), "RBI FAQ: earliest call");
            // Fixed here rather than parameterised: this family is *defined* by its
            // exercise policy in a way the callable bond is not.
            assertThat(perpetual.options().exercisePolicy())
                .isEqualTo(ExercisePolicy.EARLIEST_CALL);
            assertThat(perpetual.options().options().get(0).strikePctOfPar())
                .isEqualByComparingTo(BigDecimal.ONE);

            ScheduleBlueprint ptc = ProductTemplates.securitisationNote(
                monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT),
                List.of(new PrincipalProfile.PrincipalStep(1, Money.inr("400000")),
                    new PrincipalProfile.PrincipalStep(12, Money.inr("400000"))),
                List.of(bd("0.10"), bd("0.20")));
            assertThat(ptc.principal()).isInstanceOf(PrincipalProfile.Sculpted.class);
            assertThat(ptc.behaviour()).isInstanceOf(BehaviouralOverlay.CprVector.class);

            ScheduleBlueprint ncd = ProductTemplates.ncdIssued(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                LocalDate.of(2028, 4, 1), "expected life is first call");
            assertThat(ncd.options().exercisePolicy()).isEqualTo(ExercisePolicy.EARLIEST_CALL);
            assertThat(ProductTemplates.issuedSide()).isEqualTo(InstrumentSide.LIABILITY);

            ScheduleBlueprint kcc = ProductTemplates.kisanCreditCard(
                TemplateBasis.of(VALUE_DATE, LocalDate.of(2027, 4, 1),
                    ScheduleCalendar.seasonal(List.of(
                        LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15))),
                    DayCountConvention.ACT_365F, ECL_HORIZON_PERIODS),
                NOTIONAL, bd("0.60"), Rate.monthly(bd("0.007")), 18,
                List.of(bd("0.60")), "ACPIR-46(2)(iii)-KCC-2026");
            // The seasonal calendar is not a nicety: KCC due dates align to the crop
            // cycle, so the periods are genuinely unequal and ST-10 makes actual-date
            // discounting mandatory here.
            assertThat(kcc.calendar().frequency())
                .isEqualTo(ScheduleCalendar.Frequency.SEASONAL);
            assertThat(kcc.calendar().admitsPeriodicIndexing()).isFalse();
            assertThat(kcc.behaviour())
                .isInstanceOf(BehaviouralOverlay.RevolverBehaviour.class);

            // The policy default, stated once rather than written into fourteen
            // factories: a constant repeated fourteen times is a constant that will
            // eventually be changed thirteen times.
            assertThat(ProductTemplates.termLoanDayCount())
                .isEqualTo(DayCountConvention.THIRTY_360_BOND);
        }

        @Test
        @DisplayName("every family refuses the judgement it cannot make for the bank")
        void everyFamilyRefusesTheJudgementItCannotMake() {
            // The NEXT_REPRICING election amortises the unamortised fee *to* a repricing
            // date. With no reset after the value date there is no anchor, and falling
            // back to expected life would apply a policy other than the one on record —
            // which is exactly the substitution a recorded policy exists to prevent.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.retailHousingFloating(
                    monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                    "EBLR", bd("250"),
                    List.of(LocalDate.of(2026, 1, 1)),
                    LocalDate.of(2027, 4, 1), List.of(bd("0.08"))))
                .withMessageContaining("falls after the value date")
                .withMessageContaining("record a different exercise");

            // A moratorium of zero is not a degenerate education loan, it is an ordinary
            // annuity term loan, and the message says which family to use. Two spellings
            // of one product is how a shape taxonomy starts to grow combinations again.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.educationLoan(
                    monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT), 0, 0, "c"))
                .withMessageContaining("ordinary")
                .withMessageContaining("annuity term loan");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.educationLoan(
                    monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT), -1, 3, "c"))
                .withMessageContaining("non-negative period counts");

            // A facility already in commercial operation is a sculpted term loan: a
            // construction phase of zero periods has nothing to capitalise.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.projectFinance(
                    monthlyBasis(MATURITY_24), NOTIONAL,
                    List.of(Tranche.of(VALUE_DATE, 0, Money.inr("600000")),
                        Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("400000"))),
                    bd("0.05"),
                    List.of(new PrincipalProfile.PrincipalStep(1, NOTIONAL)),
                    0, "MCLR-1Y", bd("250"),
                    List.of(LocalDate.of(2026, 10, 1)), Rate.monthly(ONE_PERCENT), "c"))
                .withMessageContaining("nothing to capitalise")
                .withMessageContaining("sculpted term loan");

            // This factory refuses a calendar that is not explicit-date rather than
            // accepting one and letting the convention check quietly decide. The two
            // mistakes the family invites — a monthly calendar, and a monthly rate
            // applied to a six-month harvest gap — both produce a schedule that looks
            // orderly and bills a fraction of the interest.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.kisanCreditCard(
                    monthlyBasis(MATURITY_24), NOTIONAL, bd("0.60"),
                    Rate.monthly(bd("0.007")), 18, List.of(bd("0.60")), "REF"))
                .withMessageContaining("aligned to the crop cycle")
                .withMessageContaining("ScheduleCalendar.seasonal(dueDates)");

            // The card family's one mandatory control: ACPIR 46(2)(iii) is an explicit
            // behavioural-modelling mandate and is not satisfied by asserting a life.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ProductTemplates.creditCard(
                    monthlyBasis(MATURITY_24), NOTIONAL, bd("0.60"),
                    Rate.monthly(bd("0.03")), 18, List.of(bd("0.60")), "   "))
                .withMessageContaining("reference the analysis supporting it");
        }

        @Test
        @DisplayName("every derivable family's assembled vector prices back to its net integral amount")
        void everyDerivableFamilyPricesBackToItsNetIntegralAmount() {
            // The round trip, run over ten families at once. Each blueprint comes from a
            // template, is built into a ladder, is assembled into a vector, and is then
            // priced by arithmetic the engine has no routine for: at the ladder's own
            // contractual rate the whole vector must discount to zero, because with no
            // integral amount the contract prices to what it advanced by definition.
            //
            // What this catches, none of which shows up in any single flow: a mis-signed
            // leg (off by twice the notional), a dropped or double-counted terminal lump
            // (off by its present value — 144,001.06 on the lease), an interim draw
            // folded into inception (off by the draw's own discount), a split that
            // misstates the bill (off by the discrepancy), and a rounded intermediate
            // anywhere in the sizing.
            //
            // Two families are deliberately absent. A *discount instrument* has no
            // coupon, so its contractual leg does not price to the advance at any rate —
            // its entire return is the integral amount, which is the next test. And a
            // *seasonal* revolver is excluded because on unequal periods a present value
            // struck on period ordinals is not the instrument's economics at all
            // (ST-10); its refusal is tested above instead.
            record Family(String name, ScheduleBlueprint blueprint, BigDecimal periodicRate) {}

            List<Family> families = List.of(
                new Family("retail housing, floating",
                    ProductTemplates.retailHousingFloating(
                        monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                        "EBLR", bd("250"),
                        List.of(LocalDate.of(2026, 10, 1)), LocalDate.of(2027, 4, 1),
                        List.of(bd("0.08"))),
                    ONE_PERCENT),
                new Family("education loan",
                    ProductTemplates.educationLoan(
                        monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT),
                        12, 0, "contractual"),
                    ONE_PERCENT),
                new Family("project finance", projectFinanceFixture(), ONE_PERCENT),
                new Family("CV / equipment",
                    ProductTemplates.commercialVehicle(
                        monthlyBasis(MATURITY_36), NOTIONAL,
                        Rate.monthly(ELEVEN_TWELFTHS_PERCENT), Money.inr("200000"), "contractual"),
                    Rate.monthly(ELEVEN_TWELFTHS_PERCENT).periodic()),
                new Family("gold loan",
                    ProductTemplates.goldLoan(
                        monthlyBasis(LocalDate.of(2026, 10, 1)), NOTIONAL,
                        Rate.monthly(ONE_PERCENT), 2, bd("0.70")),
                    ONE_PERCENT),
                new Family("credit card",
                    ProductTemplates.creditCard(
                        monthlyBasis(MATURITY_24), NOTIONAL, bd("0.60"),
                        Rate.monthly(bd("0.03")), 18, List.of(bd("0.60")), "MODEL-CARD-2026"),
                    bd("0.03")),
                new Family("WCDL",
                    ProductTemplates.workingCapitalDemandLoan(
                        TemplateBasis.moneyMarket(VALUE_DATE, LocalDate.of(2026, 7, 1)),
                        NOTIONAL, Rate.periodic(bd("0.0225"), 4), "contractual"),
                    bd("0.0225")),
                new Family("staff housing",
                    ProductTemplates.staffHousingLoan(
                        monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(bd("0.005")),
                        "contractual"),
                    bd("0.005")),
                new Family("callable corporate bond",
                    ProductTemplates.callableCorporateBond(
                        monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ONE_PERCENT),
                        LocalDate.of(2028, 4, 1), LocalDate.of(2028, 4, 1), BigDecimal.ONE,
                        ExercisePolicy.CONTRACTUAL_MATURITY, "contractual maturity"),
                    ONE_PERCENT),
                new Family("securitisation PTC",
                    ProductTemplates.securitisationNote(
                        monthlyBasis(MATURITY_24), NOTIONAL, Rate.monthly(ONE_PERCENT),
                        List.of(new PrincipalProfile.PrincipalStep(1, Money.inr("400000")),
                            new PrincipalProfile.PrincipalStep(12, Money.inr("400000"))),
                        List.of(bd("0.10"))),
                    ONE_PERCENT));

            for (Family family : families) {
                InstalmentLadder ladder = ScheduleBuilder.build(family.blueprint());
                FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                    family.blueprint(), ladder, List.of());

                // The tolerance is the paise by which the billed instalments were
                // rounded, compounded over the ladder — five rupees on a million-rupee
                // instrument. Every defect listed above moves this by thousands at
                // least, and most of them by six figures.
                assertThat(presentValueAt(assembly.vector(), family.periodicRate()))
                    .as("%s prices back to its advance at the contractual rate", family.name())
                    .isCloseTo(BigDecimal.ZERO, Offset.offset(bd("5")));

                // With no integral amount the carrying amount is the advance itself, and
                // IC-1 still has to tie the two routes to it.
                assertThat(assembly.netIntegralFee())
                    .as("%s posts no integral amount", family.name())
                    .isEqualTo(Money.zero(family.blueprint().currency()));
                assertThat(Discounting.netAtInception(assembly.vector()).negate())
                    .as("%s satisfies IC-1", family.name())
                    .isEqualTo(assembly.initialCarryingAmount());
                assertThat(assembly.initialCarryingAmount())
                    .as("%s is carried at what it advanced", family.name())
                    .isEqualTo(assembly.amountAdvancedAtInception());
                assertThat(assembly.vector().flows())
                    .as("%s carries no contingent flow", family.name())
                    .allSatisfy(flow -> assertThat(flow.contingent()).isFalse());
            }
        }

        @Test
        @DisplayName("a discount instrument carries its whole return as an integral amount at inception")
        void aDiscountInstrumentCarriesItsReturnAsAnIntegralAmount() {
            // It looks like a trick and it is not. The notional is the face value
            // redeemed at maturity, the disbursement is that same face value, and the
            // discount is posted as an integral amount *received* at inception. Then the
            // initial carrying amount is face less discount — the price actually paid —
            // the single future flow is the face value, and the solved rate is the yield
            // the instrument was priced at. Interest collected at inception and a fee
            // received at inception are the same cash on the same date.
            ScheduleBlueprint bill = ProductTemplates.treasuryBill(
                TemplateBasis.moneyMarket(VALUE_DATE, LocalDate.of(2026, 7, 1)),
                NOTIONAL, Rate.periodic(bd("0.0175"), 4));
            List<FeePosting> discount = List.of(FeePosting.received(
                "DISCOUNT_ON_ISSUE", Money.inr("15000"), VALUE_DATE, FeeClassification.INTEGRAL));

            FlowVectorAssembler.Assembly assembly = FlowVectorAssembler.assemble(
                bill, ScheduleBuilder.build(bill), discount);

            assertThat(assembly.initialCarryingAmount()).isEqualTo(Money.inr("985000"));
            assertThat(assembly.amountAdvancedAtInception()).isEqualTo(NOTIONAL);
            assertThat(Discounting.netAtInception(assembly.vector()).negate())
                .isEqualTo(Money.inr("985000"));

            // Exactly one future flow, and it is the face value. No rung bills interest
            // — accretion is the entire return — so a ladder that redeemed less than
            // face would be an instrument with no return at all.
            assertThat(assembly.vector().future()).hasSize(1);
            assertThat(assembly.vector().future().get(0).amount()).isEqualTo(NOTIONAL);
            assertThat(assembly.vector().future().get(0).date())
                .isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(flowsOfKind(assembly.vector(), FlowKind.INTEREST)).isEmpty();

            // An explicit-date calendar cannot license periodic indexing, so the
            // instrument is discounted on actual dates — which is exactly where it
            // matters, because day-count sensitivity runs inversely to tenor.
            ConventionSelector.Choice choice = FlowVectorAssembler.convention(
                bill, assembly.vector(), assembly.vector());
            assertThat(choice.periodicIndexEligible()).isFalse();
            assertThat(choice.convention()).isInstanceOf(TimeConvention.ActualDate.class);
            assertThat(BlueprintProjector.calendarForcesActualDating(
                bill, choice.convention()).satisfied()).isTrue();
        }

        @Test
        @DisplayName("INV-2 on fixture O8: the sign of the net integral amount is visible in the rate, both ways")
        void inv2HoldsInBothDirectionsOnFixtureO8() {
            // Docs 09 § 6, O8. The cheapest sign check in the engine, and it catches a
            // whole class of fee-classification errors for the price of a comparison.
            // One asset, 1,000,000 at 11% p.a. over 36 months with a 200,000 residual,
            // projected three times with the integral amount as the only difference:
            //
            //   fee received 12,000  GCA   988,000  EIR 12.377404%  above contractual
            //   cost paid    12,000  GCA 1,012,000  EIR 10.784759%  below contractual
            //   none                 GCA 1,000,000  EIR 11.571890%  equals contractual
            //
            // The rate is solved here rather than in the projector, which is the correct
            // division: a projector never solves the instrument's own EIR, and the
            // vectors it returns are what the caller's SolveRequest is built from.
            RateSolver solver = new BracketedNewtonSolver();
            ScheduleBlueprint lease = ProductTemplates.commercialVehicle(
                monthlyBasis(MATURITY_36), NOTIONAL, Rate.monthly(ELEVEN_TWELFTHS_PERCENT),
                Money.inr("200000"), "contractual life; no early-termination right");
            BlueprintProjector projector = new BlueprintProjector(lease)
                .withTerminalLumpAs(ProductTemplates.leaseTerminalKind());

            BigDecimal contractualPeriodic = Rate.monthly(ELEVEN_TWELFTHS_PERCENT).periodic();

            BlueprintProjection received = projector.projectBlueprint(List.of(
                FeePosting.received("LEASE_ARRANGEMENT_FEE", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL)));
            BlueprintProjection paid = projector.projectBlueprint(List.of(
                FeePosting.paid("DEALER_PAYOUT", Money.inr("12000"), VALUE_DATE,
                    FeeClassification.INTEGRAL, "SELLING")));
            BlueprintProjection neither = projector.projectBlueprint(List.of());

            assertThat(received.assembly().initialCarryingAmount())
                .isEqualTo(Money.inr("988000"));
            assertThat(paid.assembly().initialCarryingAmount())
                .isEqualTo(Money.inr("1012000"));
            assertThat(neither.assembly().initialCarryingAmount()).isEqualTo(NOTIONAL);
            assertThat(received.requireInvariantsSatisfied()).isSameAs(received);
            assertThat(paid.requireInvariantsSatisfied()).isSameAs(paid);
            assertThat(neither.requireInvariantsSatisfied()).isSameAs(neither);

            BigDecimal eirReceived = solvedEffectiveAnnual(solver, received, contractualPeriodic);
            BigDecimal eirPaid = solvedEffectiveAnnual(solver, paid, contractualPeriodic);
            BigDecimal eirNeither = solvedEffectiveAnnual(solver, neither, contractualPeriodic);

            // The figures, to the last digit docs 09 § 6 quotes.
            assertThat(eirReceived).isCloseTo(O8_EIR_FEE_RECEIVED, Offset.offset(bd("5E-9")));
            assertThat(eirPaid).isCloseTo(O8_EIR_COST_PAID, Offset.offset(bd("5E-9")));
            assertThat(eirNeither)
                .isCloseTo(O8_EIR_NO_INTEGRAL_AMOUNT, Offset.offset(bd("5E-9")));

            // And the ordering, which is what INV-2 actually asserts. A fee received
            // records the asset below par so it accretes back up and the EIR exceeds the
            // contractual rate; a cost paid records it above par and the EIR falls
            // below. Swap the sign convention on integral postings anywhere upstream and
            // this ordering inverts while all three rates stay individually plausible —
            // which is the whole reason the invariant is stated as an ordering.
            assertThat(eirReceived).isGreaterThan(O8_CONTRACTUAL_EFFECTIVE);
            assertThat(eirPaid).isLessThan(O8_CONTRACTUAL_EFFECTIVE);
            assertThat(eirReceived).isGreaterThan(eirNeither);
            assertThat(eirNeither).isGreaterThan(eirPaid);

            // With no integral amount the solved rate *is* the contractual rate. Docs 09
            // § 6 quotes 11.571890% against a contractual 11.571884%: the residual
            // 6e-8 is the rounded rental — 28,024.31 billed against a true annuity of
            // 28,024.307027, which over-collects 0.09 of present value — and not an
            // engine defect. Note that ProductTemplates' javadoc claims the contractual
            // rate comes back "to the sixth decimal"; the fixture shows the agreement
            // running to the fifth decimal of a percentage, one place short of the
            // claim, for exactly that reason.
            assertThat(eirNeither)
                .isCloseTo(O8_CONTRACTUAL_EFFECTIVE, Offset.offset(bd("0.0000001")));
            assertThat(eirNeither).isNotEqualByComparingTo(O8_CONTRACTUAL_EFFECTIVE);

            // The terminal lump kept its lease label all the way through the projector.
            // A lease residual booked as a balloon is a reconciliation break with no
            // arithmetic cause: the figures all tie and the wrong asset is disclosed.
            assertThat(flowsOfKind(neither.projection().contractual(), FlowKind.RESIDUAL_VALUE))
                .hasSize(1);
            assertThat(flowsOfKind(neither.projection().contractual(), FlowKind.BALLOON))
                .isEmpty();
        }

        /**
         * The EIR the caller solves over the expected leg, annualised.
         *
         * <p>{@link SolveRequest#atInception} takes its target from the vector's own
         * inception leg, which is the same {@code −(net at inception)} that IC-1
         * compared against the carrying amount — so a vector that failed IC-1 would be
         * solved against the figure the vector carries rather than the one the ledger
         * holds, and the two would silently diverge. The seed is the contractual rate,
         * which is what the solver converges from in three or four iterations.
         */
        private static BigDecimal solvedEffectiveAnnual(
            RateSolver solver, BlueprintProjection projection, BigDecimal contractualPeriodic) {

            SolveResult result = solver.solve(SolveRequest.atInception(
                projection.projection().expected(),
                projection.conventionChoice().convention(),
                contractualPeriodic));
            assertThat(result.isSolved())
                .as("solve status was %s: %s", result.status(), result.diagnostic())
                .isTrue();
            assertThat(result.rateOrThrow().periodsPerYear())
                .as("a monthly ladder licenses the monthly periodic index")
                .isEqualTo(12);
            return result.rateOrThrow().effectiveAnnual();
        }
    }
}
