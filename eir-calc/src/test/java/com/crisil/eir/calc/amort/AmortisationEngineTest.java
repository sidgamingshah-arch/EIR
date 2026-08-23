package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.CASE1_EIR;
import static com.crisil.eir.calc.amort.AmortFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.amort.AmortFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.amort.AmortFixtures.EMI;
import static com.crisil.eir.calc.amort.AmortFixtures.INITIAL_GCA;
import static com.crisil.eir.calc.amort.AmortFixtures.MONTHLY;
import static com.crisil.eir.calc.amort.AmortFixtures.PRINCIPAL;
import static com.crisil.eir.calc.amort.AmortFixtures.bd;
import static com.crisil.eir.calc.amort.AmortFixtures.case1Billed;
import static com.crisil.eir.calc.amort.AmortFixtures.case1ContractualLeg;
import static com.crisil.eir.calc.amort.AmortFixtures.case1EirLeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The gross-basis roll-forward of specification 5.1 against the reference case 1
 * movement schedule, and the terminal-residue policy of 5.7 on both legs.
 *
 * <p>Every figure asserted here is the reference case's own. The roll-forward is
 * where a rate stops being a number and becomes a ledger, so the test is written
 * against the published table rather than against a recomputation of it: a test that
 * re-derived the expected interest from {@code opening x r} would agree with the
 * engine by construction and could not detect the two things that actually go wrong
 * — the wrong opening balance, and an accrual exponent taken from somewhere other
 * than the convention the rate was solved under.
 */
class AmortisationEngineTest {

    @Test
    @DisplayName("case 1 period 1: 995,000.00 opening accretes 10,369.38 and closes at 958,295.91")
    void firstPeriodOfCaseOne() {
        AmortisationRow row = case1EirLeg().row(1);

        assertThat(row.presentedOpeningGca().amount()).isEqualByComparingTo(bd("995000.00"));
        assertThat(row.presentedEirInterest().amount()).isEqualByComparingTo(bd("10369.38"));
        assertThat(row.presentedCashReceived().amount()).isEqualByComparingTo(bd("47073.47"));
        assertThat(row.presentedClosingGca().amount()).isEqualByComparingTo(bd("958295.91"));

        // The opening balance is the net cash flow at inception, not the 1,000,000
        // advanced and not the 985,000 the borrower received (invariant IC-1).
        assertThat(row.openingGca().amount()).isEqualByComparingTo(bd("995000"));
        assertThat(row.date()).isEqualTo(DISBURSEMENT.plusMonths(1));
    }

    @ParameterizedTest(name = "period {0}: {1} + {2} - 47,073.47 = {3}")
    @CsvSource({
        "1,  995000.00, 10369.38, 958295.91",
        "2,  958295.91,  9986.87, 921209.32",
        "3,  921209.32,  9600.38, 883736.22",
        "12, 569545.28,  5935.51, 528407.32",
        "13, 528407.32,  5506.79, 486840.64",
        "18, 316196.68,  3295.24, 272418.45",
        "23,  92695.40,   966.02,  46587.95",
        "24,  46587.95,   485.52,      0.00",
    })
    @DisplayName("the whole case 1 EIR-leg schedule reproduces, period by period")
    void schedule(int period, String opening, String interest, String closing) {
        AmortisationRow row = case1EirLeg().row(period);

        assertThat(row.presentedOpeningGca().amount()).isEqualByComparingTo(bd(opening));
        assertThat(row.presentedEirInterest().amount()).isEqualByComparingTo(bd(interest));
        assertThat(row.presentedClosingGca().amount()).isEqualByComparingTo(bd(closing));
    }

    @Test
    @DisplayName("TR-1: the terminal EIR-leg balance is zero, and it is asserted, not merely true")
    void terminalResidueOnTheEirLegIsZero() {
        AmortisationResult leg = case1EirLeg();

        assertThat(leg.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(leg.presentedTotalInterest().amount()).isEqualByComparingTo(bd("134763.28"));
        assertThat(leg.presentedTotalCash().amount()).isEqualByComparingTo(bd("1129763.28"));

        // Zero because the rate was solved against these same flows: the roll-forward
        // is that discount run backwards. So TR-1 has to be recorded on the result and
        // satisfied — a leg that merely happens to end at zero has proved nothing.
        List<InvariantResult> terminal =
            leg.invariants().stream().filter(result -> result.id() == InvariantId.TR_1).toList();
        assertThat(terminal).hasSize(1);
        assertThat(terminal.getFirst().satisfied()).isTrue();
        assertThat(leg.isClean()).isTrue();
        assertThat(leg.orThrow()).isSameAs(leg);
    }

    @Test
    @DisplayName("TR-1 breaches where the roll-forward is given a rate the flows were not solved to")
    void terminalResidueDetectsADisagreementBetweenSolveAndRoll() {
        // One basis point off the solved rate. Nothing about the arithmetic changed;
        // the solve and the roll-forward now disagree about the flows, and that is
        // exactly what TR-1 exists to catch.
        Rate wrong = Rate.monthly(CASE1_EIR.periodic().add(bd("0.0001")));
        AmortisationResult leg = AmortisationEngine.eirLeg(INITIAL_GCA, wrong, case1Billed(), MONTHLY);

        assertThat(leg.isClean()).isFalse();
        assertThat(leg.breaches()).singleElement()
            .satisfies(breach -> assertThat(breach.id()).isEqualTo(InvariantId.TR_1));
        assertThat(leg.presentedTerminalBalance().isZero()).isFalse();
    }

    @Test
    @DisplayName("the contractual leg carries the billed-schedule residue, and no terminal assertion")
    void contractualLegResidueIsRealAndUnasserted() {
        AmortisationResult leg = case1ContractualLeg();

        // 47,073.47 billed against a true annuity payment of 47,073.472223 leaves
        // 0.002223 a month uncollected, compounding to 0.059969 over 24 periods
        // (specification 5.7). Real, not a defect, and not resolved by tolerance.
        assertThat(leg.terminalBalance().amount()).isEqualByComparingTo(bd("0.05996915248945321934883"));
        assertThat(leg.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.06"));
        assertThat(leg.row(1).presentedEirInterest().amount()).isEqualByComparingTo(bd("10000.00"));
        assertThat(leg.presentedTotalInterest().amount()).isEqualByComparingTo(bd("129763.34"));

        // No TR-1 on this leg. Asserting a zero terminal balance here would fail every
        // contract whose lender billed a rounded instalment, which is all of them.
        assertThat(leg.invariants()).isEmpty();
        assertThat(leg.isClean()).isTrue();
    }

    @Test
    @DisplayName("under periodic indexing every accrual exponent is exactly 1, so interest is opening x r")
    void periodicIndexingAccruesWholePeriods() {
        AmortisationResult leg = case1EirLeg();

        assertThat(leg.rows()).allSatisfy(row -> {
            assertThat(row.wholePeriod()).isTrue();
            assertThat(row.accrualExponent()).isEqualByComparingTo(BigDecimal.ONE);
        });

        // (1+r)^1 - 1 is r on the exact integer path, so the whole-period accrual loses
        // nothing to the fractional-power routine: this is the literal form in 5.1.
        Money direct = INITIAL_GCA.times(CASE1_EIR.periodic());
        assertThat(leg.row(1).eirInterest().amount()).isEqualByComparingTo(direct.amount());
    }

    @Test
    @DisplayName("a period with no flow folds into the next boundary and capitalises — specification 5.5")
    void moratoriumNeedsNoMoratoriumMode() {
        // Three months with nothing due, then two receipts. The projector declares the
        // boundaries; where there is no cash to apply the interest stays in the balance,
        // which is capitalisation.
        List<CashFlow> folded = new ArrayList<>();
        folded.add(CashFlow.of(DISBURSEMENT, 0, Money.inr("-100000"), FlowKind.DISBURSEMENT));
        folded.add(CashFlow.of(DISBURSEMENT.plusMonths(3), 3, Money.inr("50000"), FlowKind.COMBINED_EMI));
        folded.add(CashFlow.of(DISBURSEMENT.plusMonths(4), 4, Money.inr("60000"), FlowKind.COMBINED_EMI));
        AmortisationResult moratorium = AmortisationEngine.segment(
            Money.inr("100000"), CONTRACTUAL, FlowVector.of(DISBURSEMENT, Money.INR, folded), MONTHLY);

        assertThat(moratorium.periods()).isEqualTo(2);
        AmortisationRow first = moratorium.row(3);
        assertThat(first.accrualExponent()).isEqualByComparingTo(bd("3"));
        assertThat(first.wholePeriod()).isFalse();
        // 100,000 x ((1.01)^3 - 1) = 3,030.10, capitalised into the balance.
        assertThat(first.presentedEirInterest().amount()).isEqualByComparingTo(bd("3030.10"));
        assertThat(first.presentedClosingGca().amount()).isEqualByComparingTo(bd("53030.10"));

        // Declaring each moratorium month as a zero-amount boundary flow gives a row per
        // accounting period and the identical ledger — compounding is associative, so the
        // granularity decision belongs to the projector and changes no number.
        List<CashFlow> declared = new ArrayList<>(folded);
        declared.add(CashFlow.of(DISBURSEMENT.plusMonths(1), 1, Money.zero(Money.INR), FlowKind.INTEREST));
        declared.add(CashFlow.of(DISBURSEMENT.plusMonths(2), 2, Money.zero(Money.INR), FlowKind.INTEREST));
        AmortisationResult perPeriod = AmortisationEngine.segment(
            Money.inr("100000"), CONTRACTUAL, FlowVector.of(DISBURSEMENT, Money.INR, declared), MONTHLY);

        assertThat(perPeriod.periods()).isEqualTo(4);
        assertThat(perPeriod.terminalBalance().amount())
            .isEqualByComparingTo(moratorium.terminalBalance().amount());
        assertThat(perPeriod.totalInterest().amount()).isEqualByComparingTo(moratorium.totalInterest().amount());
    }

    @Test
    @DisplayName("a published row that does not sum reports its rounding residue rather than hiding it")
    void presentedRowsCarryTheirRoundingResidue() {
        AmortisationResult leg = case1EirLeg();

        // 958,295.91 + 9,986.87 - 47,073.47 is 921,209.31 while the presented closing
        // balance is 921,209.32. Both figures are correct roundings; the paise is
        // published as a residue, because a movement schedule whose columns do not sum
        // is a defect even when every figure is individually right (5.7).
        AmortisationRow row2 = leg.row(2);
        assertThat(row2.presentedRowSums()).isFalse();
        assertThat(row2.presentedRoundingResidue().amount()).isEqualByComparingTo(bd("0.01"));

        // The reported lifetime total and the sum of the presented column differ by the
        // same class of paise, and that too is reported rather than netted into either.
        assertThat(leg.presentedInterestColumnSum().amount()).isEqualByComparingTo(bd("134763.27"));
        assertThat(leg.interestColumnResidue().amount()).isEqualByComparingTo(bd("0.01"));
        // The balance column still ties down the page: this row's presented opening is
        // the previous row's presented closing.
        assertThat(row2.presentedOpeningGca().amount())
            .isEqualByComparingTo(leg.row(1).presentedClosingGca().amount());
    }

    @Test
    @DisplayName("the ledger is carried unrounded: rounding every period would move the terminal balance")
    void theLedgerIsNotRoundedEveryPeriod() {
        AmortisationResult leg = case1EirLeg();

        // Rolling the same flows at the same rate but rounding each period's interest and
        // balance to paise — what a ledger built on presented figures would do — leaves a
        // terminal balance that is not zero. The engine's own terminal balance is.
        Money balance = INITIAL_GCA;
        for (int period = 1; period <= 24; period++) {
            Money interest = balance.times(CASE1_EIR.periodic()).atPresentationScale();
            balance = balance.plus(interest).minus(EMI).atPresentationScale();
        }
        assertThat(balance.isZero()).isFalse();
        assertThat(leg.presentedTerminalBalance().isZero()).isTrue();
    }

    @Test
    @DisplayName("a rate whose compounding frequency contradicts the convention is rejected, not adapted")
    void rateAndConventionMustAgreeOnFrequency() {
        // An annual effective rate rolled on monthly period ordinals would accrete a
        // year's interest every month and still amortise to something, which is why this
        // has to fail loudly rather than be reconciled by a units guess.
        Rate annual = Rate.annualEffective(bd("0.13248094"));

        assertThatThrownBy(() -> AmortisationEngine.eirLeg(INITIAL_GCA, annual, case1Billed(), MONTHLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compounds 1 times a year")
            .hasMessageContaining("implies 12");
    }

    @Test
    @DisplayName("an opening balance in another currency is rejected")
    void currencyMustAgree() {
        Money usd = Money.of(bd("995000"), Currency.getInstance("USD"));

        assertThatThrownBy(() -> AmortisationEngine.eirLeg(usd, CASE1_EIR, case1Billed(), MONTHLY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("INR");
    }

    @Test
    @DisplayName("the eirLeg overload that derives its opening balance derives 995,000.00")
    void openingBalanceDerivedFromTheVector() {
        AmortisationResult derived = AmortisationEngine.eirLeg(CASE1_EIR, case1Billed(), MONTHLY);

        assertThat(derived.openingGca().amount()).isEqualByComparingTo(bd("995000"));
        assertThat(derived.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(derived.presentedTotalInterest().amount())
            .isEqualByComparingTo(case1EirLeg().presentedTotalInterest().amount());
    }

    @Test
    @DisplayName("a row cannot be constructed with a closing balance that contradicts its movements")
    void rowsCannotLieAboutTheirOwnArithmetic() {
        assertThatThrownBy(() -> new AmortisationRow(
            1,
            INITIAL_GCA,
            Money.inr("10369.38"),
            EMI,
            Money.inr("958295.00"),
            DISBURSEMENT.plusMonths(1),
            BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not roll forward");
    }

    @Test
    @DisplayName("a result cannot declare totals its rows do not produce")
    void totalsAreCheckedAgainstTheRows() {
        AmortisationResult leg = case1EirLeg();

        // An accumulator that has drifted from the schedule it summarises is the most
        // common way a movement report goes wrong, so the accumulation is checked.
        assertThatThrownBy(() -> new AmortisationResult(
            leg.rows(),
            leg.totalInterest().plus(Money.inr("1")),
            leg.totalCash(),
            leg.terminalBalance(),
            List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalInterest does not tie to the rows");
    }

    @Test
    @DisplayName("a liability rolls forward on the same path with every sign inverted")
    void liabilitiesNeedNoSeparatePath() {
        // 995,000 raised rather than advanced: the inception leg is positive, the
        // instalments are outflows, and the carrying amount is negative throughout.
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, PRINCIPAL, FlowKind.DISBURSEMENT));
        flows.add(CashFlow.of(DISBURSEMENT, 0, Money.inr("-15000"), FlowKind.INTEGRAL_COST_PAID));
        flows.add(CashFlow.of(DISBURSEMENT, 0, Money.inr("10000"), FlowKind.INTEGRAL_FEE_RECEIVED));
        for (int period = 1; period <= 24; period++) {
            flows.add(CashFlow.of(
                DISBURSEMENT.plusMonths(period), period, EMI.negate(), FlowKind.COMBINED_EMI));
        }
        FlowVector borrowing = FlowVector.of(DISBURSEMENT, Money.INR, flows);

        AmortisationResult leg = AmortisationEngine.eirLeg(INITIAL_GCA.negate(), CASE1_EIR, borrowing, MONTHLY);

        assertThat(leg.row(1).presentedOpeningGca().amount()).isEqualByComparingTo(bd("-995000.00"));
        assertThat(leg.row(1).presentedEirInterest().amount()).isEqualByComparingTo(bd("-10369.38"));
        assertThat(leg.row(1).presentedClosingGca().amount()).isEqualByComparingTo(bd("-958295.91"));
        assertThat(leg.presentedTotalInterest().amount()).isEqualByComparingTo(bd("-134763.28"));
        assertThat(leg.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(leg.isClean()).isTrue();
    }

    @Test
    @DisplayName("the roll-forward reads no clock: the same inputs give the same ledger every time")
    void deterministic() {
        AmortisationResult first = case1EirLeg();
        AmortisationResult second = case1EirLeg();

        assertThat(second.totalInterest().amount()).isEqualByComparingTo(first.totalInterest().amount());
        assertThat(second.terminalBalance().amount()).isEqualByComparingTo(first.terminalBalance().amount());
        for (int period = 1; period <= 24; period++) {
            assertThat(second.row(period).eirInterest().amount())
                .isEqualByComparingTo(first.row(period).eirInterest().amount());
        }
        // Dates come from the vector, never from a clock: the last row is 24 months
        // after the fixture's own disbursement date.
        assertThat(first.row(24).date()).isEqualTo(DISBURSEMENT.plusMonths(24));
    }

    @Test
    @DisplayName("an actual-dated vector accretes fractional periods, and still amortises to zero")
    void actualDatedRollForward() {
        // A broken first period: the first instalment falls 45 days after drawdown rather
        // than a whole month. Under actual dating the solved rate is an annual effective
        // rate and each dtau is a day-counted fraction (5.3), so the ledger is only the
        // exact inverse of the discount if the roll-forward takes its exponents from the
        // same convention. TR-1 is the test of that.
        TimeConvention actual = new TimeConvention.ActualDate(DayCountConvention.ACT_365F);
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(DISBURSEMENT, 0, Money.inr("-100000"), FlowKind.DISBURSEMENT));
        flows.add(CashFlow.of(DISBURSEMENT.plusDays(45), 1, Money.inr("52000"), FlowKind.COMBINED_EMI));
        flows.add(CashFlow.of(DISBURSEMENT.plusDays(75), 2, Money.inr("52000"), FlowKind.COMBINED_EMI));
        FlowVector vector = FlowVector.of(DISBURSEMENT, Money.INR, flows);

        Rate solved = new BracketedNewtonSolver()
            .solve(SolveRequest.atInception(vector, actual, null)).rateOrThrow();
        // Under actual dating the solved rate is the annual effective rate itself.
        assertThat(solved.periodsPerYear()).isEqualTo(1);

        AmortisationResult leg = AmortisationEngine.eirLeg(Money.inr("100000"), solved, vector, actual);

        assertThat(leg.row(1).wholePeriod()).isFalse();
        assertThat(leg.row(1).accrualExponent()).isEqualByComparingTo(
            DayCountConvention.ACT_365F.yearFraction(DISBURSEMENT, DISBURSEMENT.plusDays(45)));
        assertThat(leg.presentedTerminalBalance().amount()).isEqualByComparingTo(bd("0.00"));
        assertThat(leg.isClean()).isTrue();
    }
}
