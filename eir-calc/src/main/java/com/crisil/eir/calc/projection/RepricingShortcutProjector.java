package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.TimeConvention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The IFRS 9 B5.4.4 next-repricing-date shortcut, applied over another projector.
 *
 * <p>Where a premium, discount or fee relates to a variable repriced to market
 * rates before maturity, B5.4.4 amortises it to the <b>next repricing date</b>
 * rather than over expected life. Mechanically the instrument is treated, for
 * fee-amortisation purposes only, as maturing at that reset: the delegate's vector
 * is truncated there and a synthetic {@link FlowKind#NOTIONAL_REDEMPTION} equal to
 * the <em>contractual</em> balance at that date closes it.
 *
 * <p>On the Case 1 loan repriced annually (Case 8):
 *
 * <table border="1">
 *   <caption>Full expected life against the shortcut</caption>
 *   <tr><th>Basis</th><th>EIR per month</th><th>Net fee, months 1-12</th>
 *       <th>Unamortised past the reset</th></tr>
 *   <tr><td>Full expected life, 24m</td><td>1.04214918%</td><td>3,591.71</td><td>1,408.29</td></tr>
 *   <tr><td>Shortcut to the m12 reset</td><td>1.05614730%</td><td>5,000.00</td><td>0.00</td></tr>
 * </table>
 *
 * <p><b>This is an accounting policy election per product, not a per-contract
 * optimisation.</b> Applied inconsistently it is indefensible: two identical
 * mortgages, one shortcut and one not, differ by 39.2% in year-one fee recognition
 * on nothing but a projection setting. The election therefore belongs to the
 * product configuration that constructs this projector (FR-508), and no
 * contract-level attribute may switch it on or off.
 *
 * <p>Two consequences worth stating. It dissolves the behavioural-life estimation
 * problem for the floating-rate book, where observed life is far shorter than the
 * contractual tenor because of balance-transfer churn and where life is the
 * highest-leverage assumption in the model. And it removes the reset-loop cost
 * entirely: with no unamortised fee to carry across the reset, a repricing does not
 * have to re-solve anything for the fee, only reprice the interest leg — which
 * matters because naively re-solving a full schedule on every benchmark reset for
 * every retail account is computationally brutal at Indian volumes.
 *
 * <p>Guarded to floating-rate instruments. A fixed-rate loan does not reprice to
 * market by its own terms, so B5.4.4 has nothing to attach to, and a renegotiated
 * fixed rate is a modification rather than a reset (FR-507).
 */
public final class RepricingShortcutProjector implements CashflowProjector {

    private final CashflowProjector delegate;
    private final int periodsToNextRepricing;

    /**
     * @param delegate               the projector that builds the full contractual
     *                               schedule; the shortcut truncates its output
     *                               rather than re-deriving one, so the truncated
     *                               instalments are the same instalments the
     *                               lender bills
     * @param periodsToNextRepricing periods from initial recognition to the next
     *                               reset — 12 on an annually repriced monthly loan
     */
    public RepricingShortcutProjector(CashflowProjector delegate, int periodsToNextRepricing) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (periodsToNextRepricing < 1) {
            throw new IllegalArgumentException(
                "periodsToNextRepricing must be >= 1, got " + periodsToNextRepricing);
        }
        this.periodsToNextRepricing = periodsToNextRepricing;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declines a reset at or beyond maturity: there the shortcut is a no-op and
     * saying so is better than silently returning the full vector under a name that
     * claims otherwise.
     */
    @Override
    public boolean supports(ContractTerms terms) {
        return terms.rateType() == RateType.FLOATING
            && periodsToNextRepricing < terms.termPeriods()
            && delegate.supports(terms);
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        if (terms.rateType() != RateType.FLOATING) {
            throw new IllegalArgumentException(
                "the B5.4.4 shortcut applies to an instrument that reprices to market rates by its own"
                    + " terms; this contract is " + terms.rateType()
                    + ", and a renegotiated fixed rate is a modification, not a reset (FR-507)");
        }
        if (periodsToNextRepricing >= terms.termPeriods()) {
            throw new IllegalArgumentException(
                "the next repricing is in period " + periodsToNextRepricing + " of a "
                    + terms.termPeriods() + "-period contract, so there is nothing to truncate");
        }
        ProjectionResult full = delegate.project(terms, fees);
        FlowVector contractual = full.contractual();
        Money balance = contractualBalanceAtReset(terms, contractual);
        List<CashFlow> kept = new ArrayList<>();
        for (CashFlow flow : contractual.flows()) {
            if (flow.periodIndex() <= periodsToNextRepricing) {
                kept.add(flow);
            }
        }
        if (balance.isPositive()) {
            kept.add(CashFlow.of(
                terms.dueDate(periodsToNextRepricing), periodsToNextRepricing,
                balance, FlowKind.NOTIONAL_REDEMPTION));
        }
        FlowVector truncated = FlowVector.of(contractual.anchorDate(), contractual.currency(), kept)
            .requireNoContingentFlows();
        TimeConvention convention = ProjectionSupport.convention(terms, truncated, truncated);
        return ProjectionResult.of(
            truncated, truncated, full.initialCarryingAmount(), convention, true);
    }

    /** The projector whose schedule is being truncated. */
    public CashflowProjector delegate() {
        return delegate;
    }

    /** Periods from initial recognition to the next reset. */
    public int periodsToNextRepricing() {
        return periodsToNextRepricing;
    }

    /**
     * The synthetic redemption amount: the contractual balance at the reset —
     * 529,815.605015332452 on the Case 8 loan, presented as 529,815.61.
     *
     * <p>Contractual, not the EIR-leg balance. The synthetic flow stands in for the
     * amount the borrower would owe if the instrument matured at the reset, and the
     * borrower owes the contractual balance. Using the EIR-leg balance instead would
     * put the unamortised fee inside the flow the fee is being amortised against.
     * This is also the figure that ties three ways: Case 1's INV-4 at month 12,
     * Case 2's prepayment acceleration, and the fee this shortcut eliminates.
     *
     * <p><b>At working precision, not presented.</b> A notional redemption is an
     * accounting construct that is never billed to anybody, so there is no cash
     * event at which currency scale attaches, and rounding it to paise here would
     * round an intermediate — which the calculation specification forbids (1.2, and
     * 1.3's "exactly once, where a figure is persisted as a reportable amount").
     * The rounding is not free: presenting the balance before solving moves the
     * Case 8 monthly EIR from the published 1.05614730% to 1.05614735% and lifts
     * five of the twelve published closing balances by a paisa. Presenting it costs
     * five paise of accuracy in the rate's eighth decimal place to buy a flow figure
     * that nobody ever receives. {@link ContractualBalance} states the same
     * discipline for the roll itself — working precision throughout, presented once
     * where the schedule is rendered.
     */
    public Money contractualBalanceAtReset(ContractTerms terms, FlowVector contractual) {
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), contractual, periodsToNextRepricing);
    }
}
