package com.crisil.eir.calc.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.calc.amort.TwoLegRow;
import com.crisil.eir.calc.solver.SolveStatus;
import com.crisil.eir.calc.solver.SolverTolerance;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The invariant sweep: every reconciliation invariant, on every instrument the
 * projectors can represent.
 *
 * <p>The reference cases prove the engine right on nine instruments whose every
 * intermediate figure is known by hand. They cannot prove it is not wrong
 * somewhere else, and "somewhere else" is where a production defect lives — a
 * quarterly balloon on 30E/360 with a month-end drawdown is nobody's worked
 * example and is somebody's actual loan. This class runs the published pipeline
 * over ten thousand generated contracts and asserts the closing conditions the
 * specification states in section 9, at every period rather than only at
 * maturity.
 *
 * <h2>Two invariants are asserted against a derived bound, and why that is not a
 * weakening</h2>
 *
 * <p>TR-1 says the terminal EIR-leg carrying amount is <em>exactly</em> zero, and
 * on a retail mortgage it is. It is not zero on every representable instrument,
 * and the reason is not arithmetic drift — it is specification 1.4, which requires
 * the roll-forward to use the rate as <em>persisted</em> at twelve decimal places
 * rather than as solved at twenty-eight. The persisted rate is therefore up to half
 * a unit in its last place from the root, and the roll-forward compounds that
 * pricing error to maturity. Where {@code (1+r)^n} is large — a 30-year schedule
 * at 15% and above — the compounded error passes half a paise and no rate stored
 * at twelve places can make the leg close at zero. INV-1 carries the same
 * deviation, exactly, because the EIR-leg terminal balance <em>is</em> the INV-1
 * deviation:
 *
 * <pre>
 * terminal = opening + interest - cash        (the roll-forward)
 * opening  = par - fee                        (IC-1)
 * =&gt; interest - (cash - par) - fee = terminal  (INV-1's deviation)
 * </pre>
 *
 * <p>So this class asserts TR-1 and INV-1 <em>exactly, at presentation scale, on
 * every instrument where the stored-rate policy admits an exact answer</em>, and
 * asserts the derived bound on the rest. Both halves are checked on every try, so
 * a real defect — a roll-forward that disagrees with the solve about the flows —
 * fails the bound rather than hiding inside it. {@link InvariantBoundaryCasesTest}
 * pins the boundary itself with worked instruments.
 */
@Tag("sweep")
class ReconciliationSweepTest {

    /** Half of INR's minor unit: the largest residue that rounds away at presentation scale. */
    private static final BigDecimal HALF_A_PAISE = Generators.HALF_A_PAISE;

    @Provide
    Arbitrary<Generators.Contract> contracts() {
        return Generators.contracts();
    }

    /**
     * IC-1, TR-1, INV-1, INV-3, the solver's residual and the shape of the balance
     * path, on one pipeline per generated contract.
     *
     * <p>Asserted together rather than as six properties over six separately
     * generated populations because they are six statements about <em>one</em>
     * computation: a contract whose IC-1 holds and whose TR-1 fails is a different
     * finding from two contracts that each fail one, and the pipeline costs more
     * than all six assertions put together.
     */
    @Property(tries = 10000, seed = "20270401")
    void everyGeneratedContractReconcilesFromInceptionToMaturity(
            @ForAll("contracts") Generators.Contract contract) {

        Generators.Pipeline pipeline = Generators.run(contract);

        // A plausible instrument always has an economically meaningful rate. The failure
        // mode this guards is not a missing feature: it is 4.3, where a vector with no
        // sign change must reach the exception queue and must never be defaulted to the
        // contractual rate. If the generator ever produces one, the exception queue is
        // where it belongs and this assertion is how we find out.
        assertThat(pipeline.solve().status())
            .as("solve status for %s", pipeline.label())
            .isEqualTo(SolveStatus.SOLVED);

        assertInitialCarryingAmount(pipeline);
        assertTerminalResidue(pipeline);
        assertLifetimeInterest(pipeline);
        assertBilledCashReconciles(pipeline);
        assertResidualIsWithinTheStoredRateBound(pipeline);
        if (pipeline.underPeriodicIndex()) {
            assertBalanceNeverRisesAgainAfterItsPeak(pipeline);
        }

        // Everything the engine asserted for itself, less the breaches this package
        // accounts for elsewhere and bounds above.
        assertThat(pipeline.unaccountedBreaches())
            .as("engine-asserted invariants for %s", pipeline.label())
            .isEmpty();
    }

    /**
     * INV-4 at every period, against an independently accumulated fee balance.
     *
     * <p>{@link TwoLegRow#unamortisedFee()} <em>derives</em> the balance as the leg
     * difference, so asserting that it equals the leg difference asserts a
     * definition. The invariant has content only against a figure that could
     * disagree, which is what a core banking system's own unamortised-fee balance
     * is: an accumulator, rolled forward by adding this period's amortisation to
     * last period's balance. This property builds exactly that accumulator —
     * opening net fee, less each period's recognised fee — and checks it against
     * the leg difference at every period, which is what INV-4 is for and where a
     * one-off rounding decision inside a fee column would show up.
     *
     * <p>Ten thousand tries over the full space rather than a cheaper population:
     * the actual-date path is where a fee column would drift, because a broken
     * period recognises a fee amount that no per-period formula produces.
     */
    @Property(tries = 10000, seed = "20270402")
    void theUnamortisedFeeIsTheLegDifferenceAtEveryPeriod(
            @ForAll("contracts") Generators.Contract contract) {

        Generators.Pipeline pipeline = Generators.run(contract);
        assertThat(pipeline.solved()).as("solved: %s", pipeline.label()).isTrue();

        Money accumulator = contract.netIntegralFee();
        Money previousBalance = accumulator;
        for (TwoLegRow row : pipeline.twoLeg().rows()) {
            assertThat(row.openingUnamortisedFee().atPresentationScale())
                .as("period %s opening fee balance continues the previous close for %s",
                    row.period(), pipeline.label())
                .isEqualTo(previousBalance.atPresentationScale());

            accumulator = accumulator.minus(row.feeAmortised());
            InvariantResult invariantFour = InvariantChecks.unamortisedFeeIsLegDifference(
                row.contractualCarryingAmount(), row.eirCarryingAmount(), accumulator);
            assertThat(invariantFour.satisfied())
                .as("INV-4 at period %s for %s: %s", row.period(), pipeline.label(),
                    invariantFour.detail())
                .isTrue();
            previousBalance = row.unamortisedFee();
        }

        // By maturity the fee is fully amortised: what is left of the leg difference is
        // the contractual leg's own uncollected residue, plus whatever the stored rate
        // left on the EIR leg. Both are accounted for elsewhere; neither is fee.
        Money allowed = pipeline.twoLeg().contractualResidue().abs()
            .plus(pipeline.storedRateTerminalBound().abs())
            .plus(Money.of(HALF_A_PAISE, contract.terms().currency()));
        assertThat(accumulator.abs().amount())
            .as("unamortised fee left at maturity for %s", pipeline.label())
            .isLessThanOrEqualTo(allowed.amount());
    }

    // ------------------------------------------------------------- assertions

    /**
     * IC-1. The gross carrying amount at initial recognition is the net cash flow
     * at inception — 995,000 on reference case 1, neither the 1,000,000 advanced
     * nor the 985,000 the borrower received.
     *
     * <p>Asserted in the ledger's signed form and again as the absolute figure the
     * specification states. The signed form is the one that generalises: a
     * liability raised carries a negative carrying amount and the same solver and
     * the same roll-forward run over it unchanged (section 11).
     */
    private void assertInitialCarryingAmount(Generators.Pipeline pipeline) {
        Money netAtInception = pipeline.projection().netCashAtInception();
        assertThat(pipeline.projection().initialCarryingAmount().atPresentationScale())
            .as("IC-1 signed for %s", pipeline.label())
            .isEqualTo(netAtInception.negate().atPresentationScale());
        assertThat(pipeline.projection().initialCarryingAmount().abs().atPresentationScale())
            .as("IC-1 absolute for %s", pipeline.label())
            .isEqualTo(netAtInception.abs().atPresentationScale());
        assertThat(pipeline.projection().allInvariantsSatisfied())
            .as("projection invariants for %s: %s", pipeline.label(),
                pipeline.projection().invariants())
            .isTrue();

        // The as-incurred servicing charge every generated contract carries must not have
        // reached the carrying amount. IC-1 is an assertion about classification, not
        // about addition (3.2), and this is the half of it that arithmetic cannot see.
        Money par = pipeline.projection().initialCarryingAmount()
            .plus(pipeline.contract().netIntegralFee());
        assertThat(par.atPresentationScale())
            .as("par advanced for %s", pipeline.label())
            .isEqualTo(pipeline.contract().amountAdvanced().atPresentationScale());
    }

    /**
     * TR-1, exactly where the stored-rate policy admits an exact answer and against
     * the compounded rounding bound elsewhere.
     */
    private void assertTerminalResidue(Generators.Pipeline pipeline) {
        Money terminal = pipeline.eirLeg().terminalBalance();
        BigDecimal bound = pipeline.storedRateTerminalBound().abs().amount();
        if (pipeline.exactTerminalBalanceIsAttainable()) {
            assertThat(pipeline.eirLeg().presentedTerminalBalance().isZero())
                .as("TR-1 exactly zero at presentation scale for %s (terminal %s, bound %s)",
                    pipeline.label(), terminal.amount().toPlainString(), bound.toPlainString())
                .isTrue();
            assertThat(pipeline.eirLeg().breaches())
                .as("TR-1 as the engine asserts it for %s", pipeline.label())
                .isEmpty();
            return;
        }
        assertThat(terminal.abs().amount())
            .as("TR-1 within the stored-rate bound for %s", pipeline.label())
            .isLessThanOrEqualTo(bound);
    }

    /**
     * INV-1: lifetime EIR interest equals lifetime contractual interest on the
     * billed flows plus the net integral fee. The EIR method changes the timing of
     * recognition and never the total.
     */
    private void assertLifetimeInterest(Generators.Pipeline pipeline) {
        Money expected = pipeline.twoLeg().contractualInterestBilled()
            .plus(pipeline.contract().netIntegralFee());
        Money actual = pipeline.twoLeg().totalEirInterest();
        BigDecimal deviation = actual.minus(expected).abs().amount();
        BigDecimal bound = pipeline.storedRateTerminalBound().abs().amount().max(HALF_A_PAISE);
        assertThat(deviation)
            .as("INV-1 for %s: EIR interest %s against contractual %s plus net fee %s",
                pipeline.label(), actual.atPresentationScale(),
                pipeline.twoLeg().contractualInterestBilled().atPresentationScale(),
                pipeline.contract().netIntegralFee().atPresentationScale())
            .isLessThanOrEqualTo(bound);
        if (pipeline.exactTerminalBalanceIsAttainable()) {
            // The net fee recognised over the life is the same statement with one
            // rounding instead of two, and it is the form that holds exactly: the
            // difference is taken at working precision and reduced once. INV-1 as the
            // engine states it rounds both totals first, which on a 240-million-rupee
            // interest total can report a paise that is not there — see
            // InvariantBoundaryCasesTest.
            assertThat(pipeline.twoLeg().netFeeRecognised().atPresentationScale())
                .as("net fee recognised over the life for %s", pipeline.label())
                .isEqualTo(pipeline.contract().netIntegralFee().atPresentationScale());
        }
    }

    /**
     * INV-3: cash received reconciles to principal plus contractual interest on the
     * flows actually billed, with the uncollected residue accounted for rather than
     * absorbed.
     *
     * <p>Also checks the ledger against the vector it consumed. The engine's own
     * INV-3 is stated between figures the amortisation produced; summing the
     * projected vector independently is what catches a boundary that dropped a
     * flow or counted one twice, which is the failure the actual-date path makes
     * possible by grouping flows that share a date.
     */
    private void assertBilledCashReconciles(Generators.Pipeline pipeline) {
        Money summed = Money.zero(pipeline.contract().terms().currency());
        for (CashFlow flow : pipeline.projection().contractual().future()) {
            summed = summed.plus(flow.amount());
        }
        assertThat(pipeline.contractualLeg().totalCash().atPresentationScale())
            .as("the contractual leg consumed every projected flow, once, for %s", pipeline.label())
            .isEqualTo(summed.atPresentationScale());
        assertThat(invariant(pipeline, InvariantId.INV_3).satisfied())
            .as("INV-3 for %s: %s", pipeline.label(), invariant(pipeline, InvariantId.INV_3).detail())
            .isTrue();
        assertThat(pipeline.twoLeg().totalCashReceived().atPresentationScale())
            .as("INV-3 restated: cash = principal + billed contractual interest for %s",
                pipeline.label())
            .isEqualTo(pipeline.twoLeg().principalAdvanced()
                .plus(pipeline.twoLeg().contractualInterestBilled()).atPresentationScale());
    }

    /**
     * The residual recorded at the <em>stored</em> rate, against what the stored
     * rate can achieve.
     *
     * <p>Specification 4.2 step 5 rounds the solved rate to twelve places and
     * re-evaluates {@code |f|} there. That residual is not the solver's convergence
     * tolerance and cannot be: half a unit in the last place of the rate moves the
     * present value by {@code |f'(r)| x 5e-13}, which on a long schedule is five
     * orders of magnitude above the 1e-10 floor. Asserting the tolerance alone
     * would be asserting something false; asserting nothing would leave the
     * published rate unchecked. The bound is the sum of the two, and it is tight —
     * across the sampled space the recorded residual sits just inside it.
     */
    private void assertResidualIsWithinTheStoredRateBound(Generators.Pipeline pipeline) {
        BigDecimal roundingAllowance = pipeline.storedRatePresentValueBound();
        BigDecimal tolerance = SolverTolerance.standard()
            .absoluteFor(pipeline.projection().initialCarryingAmount());
        assertThat(pipeline.solve().residualAtStoredRate().abs())
            .as("residual at the stored rate for %s (tolerance %s, rounding allowance %s)",
                pipeline.label(), tolerance.toPlainString(), roundingAllowance.toPlainString())
            .isLessThanOrEqualTo(tolerance.add(roundingAllowance));
    }

    /**
     * The carrying amount rises at most once and never rises again after its peak.
     *
     * <p>Stated this way because "the carrying amount declines" is false for half
     * the instruments here and the falsehood is not a defect: a fee received records
     * the asset below par, so an interest-only bullet accretes <em>up</em> to par
     * over its life, and a moratorium capitalises interest into a balance with no
     * cash to reduce it (5.5). What is true of a fully amortising schedule under
     * <em>periodic indexing</em> is that the balance has one hump: once a period's
     * cash exceeds the period's accrual it exceeds it in every later period, because
     * the accrual is proportional to a balance that is now falling. A second hump
     * would mean a schedule that stopped covering its own interest half way through
     * and still closed at zero, which no projector here can produce.
     *
     * <p>Under actual dating it is false, and the calendar is why: {@code dtau}
     * alternates with the length of the accrual period — 182 days then 183 on a
     * semi-annual schedule under ACT/360 — so a level receipt covers more than the
     * accrual in a short period and less in a long one, and the balance zig-zags on
     * its way down. The property is therefore asserted where it is a statement about
     * the method and not about the calendar;
     * {@link InvariantBoundaryCasesTest#theCarryingAmountZigZagsUnderActualDatingWithLevelReceipts()}
     * exhibits the zig-zag.
     */
    private void assertBalanceNeverRisesAgainAfterItsPeak(Generators.Pipeline pipeline) {
        List<AmortisationRow> rows = pipeline.eirLeg().rows();
        int peak = 0;
        for (int index = 1; index < rows.size(); index++) {
            if (rows.get(index).closingGca().compareTo(rows.get(peak).closingGca()) > 0) {
                peak = index;
            }
        }
        for (int index = peak + 1; index < rows.size(); index++) {
            assertThat(rows.get(index).closingGca().compareTo(rows.get(index - 1).closingGca()))
                .as("carrying amount at period %s against period %s (peak at period %s) for %s",
                    rows.get(index).period(), rows.get(index - 1).period(),
                    rows.get(peak).period(), pipeline.label())
                .isLessThanOrEqualTo(0);
        }
    }

    private static InvariantResult invariant(Generators.Pipeline pipeline, InvariantId id) {
        for (InvariantResult result : pipeline.twoLeg().invariants()) {
            if (result.id() == id) {
                return result;
            }
        }
        throw new AssertionError(id + " was not asserted by the reconciliation");
    }
}
