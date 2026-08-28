package com.crisil.eir.application.run;

import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.TimeConvention;
import java.util.Objects;

/**
 * One contract's movements for one accounting period — the half of 05 § 3.2's "load contract, prior
 * balance, events, staging" that {@link com.crisil.eir.application.port.ContractStateSource} does
 * not carry.
 *
 * <p><b>Why this is not on the committed port.</b> {@code ContractStateSource.OpeningState} answers
 * the balance-sheet question: terms, rate, opening balances, stage, allowance, ECL engine version,
 * billed interest. It does not carry the period's own flow vector, the cash book's
 * principal/interest split, the suspense balance brought forward, or the event. Those are the
 * period's movements rather than its opening position, and the spine's ports are not to be edited —
 * so the inner loop declares what it needs here, and {@link ContractPeriodSource} is the seam. The
 * gap is reported rather than papered over: see this package's own notes.
 *
 * <p><b>The cash split is a separate input from the flow vector, on purpose.</b> The vector says
 * what the schedule expects; the two cash fields say what the cash book actually applied and to
 * which leg. Taking both from one source would make the journal's cash block a field compared
 * against itself — the trap this programme has now found several times — and would remove the only
 * thing that can detect a receipt applied to the wrong leg. It is also what makes S3-1's fourth leg
 * (cash applied to interest against suspense recovered) a reconciliation rather than a restatement
 * of one number.
 *
 * <p><b>{@code periodOrdinal} is the schedule's own ordinal, not a loop counter.</b> It is what
 * lets {@link ContractPipeline} derive the accrual length from the <em>contract's</em> schedule
 * ({@code ContractTerms.dueDate}) independently of the length the roll-forward derives from the
 * <em>supplied vector's</em> dates. Two independent derivations of one quantity is what makes ST-2
 * against the ledger a control instead of a tautology.
 *
 * @param contractId               the contract
 * @param periodOrdinal            1-based ordinal of this period in the contract's own schedule
 * @param periodFlows              this period's flows, anchored at the period start, with the
 *                                 post-anchor flow carrying period index 1
 * @param convention               the same time convention the rate was solved under
 * @param cashAppliedToPrincipal   cash received and applied to principal, from the cash book
 * @param cashAppliedToInterest    cash received and applied to interest, from the cash book
 * @param suspenseOpeningBalance   interest-in-suspense brought forward; never negative
 * @param suspenseRecovered        suspended interest received in cash this period
 * @param suspenseWrittenOff       suspended interest written off this period
 * @param event                    the period's cash-flow-change event, or null
 */
public record ContractPeriod(
    String contractId,
    int periodOrdinal,
    FlowVector periodFlows,
    TimeConvention convention,
    Money cashAppliedToPrincipal,
    Money cashAppliedToInterest,
    Money suspenseOpeningBalance,
    Money suspenseRecovered,
    Money suspenseWrittenOff,
    PeriodEvent event) {

    public ContractPeriod {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(periodFlows, "periodFlows");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(cashAppliedToPrincipal, "cashAppliedToPrincipal");
        Objects.requireNonNull(cashAppliedToInterest, "cashAppliedToInterest");
        Objects.requireNonNull(suspenseOpeningBalance, "suspenseOpeningBalance");
        Objects.requireNonNull(suspenseRecovered, "suspenseRecovered");
        Objects.requireNonNull(suspenseWrittenOff, "suspenseWrittenOff");
        if (contractId.isBlank()) {
            throw new IllegalArgumentException(
                "a period movement with no contract id cannot be isolated per contract (FR-905)");
        }
        if (periodOrdinal < 1) {
            // Zero is the disbursement boundary, not a period. Refused because the accrual length
            // for ordinal n is derived as dueDate(n-1) to dueDate(n), and at n = 0 that reads
            // backwards off the end of the schedule.
            throw new IllegalArgumentException(
                "periodOrdinal must be >= 1 on contract " + contractId + ", got " + periodOrdinal);
        }
        if (periodFlows.future().isEmpty()) {
            throw new IllegalArgumentException(
                "contract " + contractId + " period " + periodOrdinal + " has no flow after its"
                    + " anchor " + periodFlows.anchorDate() + "; a period with no accrual boundary"
                    + " produces no row, and AmortisationEngine's javadoc names the fix — a"
                    + " zero-amount flow on the period date declares the boundary without changing"
                    + " a present value or a balance");
        }
    }

    /**
     * A period with no suspense movements and no event — the steady-state case, and the one the
     * 10M-contract target is sized on.
     */
    public static ContractPeriod of(
        String contractId,
        int periodOrdinal,
        FlowVector periodFlows,
        TimeConvention convention,
        Money cashAppliedToPrincipal,
        Money cashAppliedToInterest) {
        Money nil = Money.zero(periodFlows.currency());
        return new ContractPeriod(
            contractId, periodOrdinal, periodFlows, convention,
            cashAppliedToPrincipal, cashAppliedToInterest, nil, nil, nil, null);
    }

    /** This period with a suspense balance brought forward and its movements. */
    public ContractPeriod withSuspense(Money openingBalance, Money recovered, Money writtenOff) {
        return new ContractPeriod(
            contractId, periodOrdinal, periodFlows, convention, cashAppliedToPrincipal,
            cashAppliedToInterest, openingBalance, recovered, writtenOff, event);
    }

    /** This period carrying a cash-flow-change event. */
    public ContractPeriod withEvent(PeriodEvent periodEvent) {
        Objects.requireNonNull(periodEvent, "periodEvent");
        return new ContractPeriod(
            contractId, periodOrdinal, periodFlows, convention, cashAppliedToPrincipal,
            cashAppliedToInterest, suspenseOpeningBalance, suspenseRecovered, suspenseWrittenOff,
            periodEvent);
    }

    /** Whether the routing table has to be consulted at all this period. */
    public boolean hasEvent() {
        return event != null;
    }

    /** Total cash the cash book applied, both legs. */
    public Money cashApplied() {
        return cashAppliedToPrincipal.plus(cashAppliedToInterest);
    }
}
