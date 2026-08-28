package com.crisil.eir.application.run;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.Stage3Decomposition;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.calc.amort.SuspenseLedger;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.gl.journal.JournalEntry;
import java.util.List;
import java.util.Objects;

/**
 * Everything one contract's period produced — the working papers behind one
 * {@link ContractResult}.
 *
 * <p><b>Why both types exist.</b> {@link ContractResult} is the spine's vocabulary: the four things
 * the run-level aggregation and the ledger writer need, and nothing else. This record is what the
 * period balance of 04 § 2.9 is written from and what an auditor asks for when a figure is queried —
 * the row, the decomposition, the suspense movement, the routing decision, the restatement, and the
 * count of solves. Collapsing the two would either starve the writer or bloat the spine, and the
 * spine is shared vocabulary that this use case is not entitled to widen.
 *
 * <p><b>{@code solves} is a first-class field, not a diagnostic.</b> 05 § 3.2: the solve is inside
 * the event branch only, and "the steady-state run is overwhelmingly roll-forward arithmetic, which
 * is what makes the 10M-contract target reachable". A pipeline that solved every period would
 * produce an identical {@link ContractResult} for any contract with no event — same rate, same
 * balance, same journal — so the only way the defect is visible at all is if the count travels with
 * the figures. It is populated from {@link SolveAudit}, which is the only route to a solver in this
 * package.
 *
 * <p><b>{@code eirBefore} and {@code eirAfter} are both here for the reason {@code CatchUpResult}
 * records a rate that did not change.</b> Recording the rate on both sides of a period whose
 * defining property is usually that the rate does not move looks redundant, and it is the cheapest
 * audit evidence available that a roll-forward did not quietly pick up a re-solve on the way past —
 * the defect {@code InvariantId.SG_1}'s javadoc names for pipelines exactly like this one.
 *
 * @param contractId     the contract
 * @param eirBefore      the rate loaded from the contract's state
 * @param eirAfter       the rate carried out; identical to {@code eirBefore} unless a B5.4.5 reset
 *                       re-solved it
 * @param openingGca     the balance the period accrued on — restated where a catch-up ran
 * @param closingGca     the balance carried out
 * @param row            the single accrual boundary the period covers
 * @param decomposition  the period's three-way interest decomposition, at every stage
 * @param suspense       the period's interest-in-suspense movement
 * @param reconciliation the four-way S3-1 reconciliation, or null where recognition is not
 *                       suppressed — see {@link ContractPipeline} for why it is not run then
 * @param routing        the routing decision, or null where the period carried no event
 * @param catchUp        the B5.4.6 restatement, or null where no catch-up ran
 * @param solves         how many times the solver was invoked for this contract this period
 * @param journal        the period's postings
 * @param invariants     one result per invariant id, in the order the loop computed them
 */
public record ContractComputation(
    String contractId,
    Rate eirBefore,
    Rate eirAfter,
    Money openingGca,
    Money closingGca,
    AmortisationRow row,
    Stage3Decomposition decomposition,
    SuspenseLedger suspense,
    Stage3Reconciliation reconciliation,
    RoutingDecision routing,
    CatchUpResult catchUp,
    int solves,
    JournalEntry journal,
    List<InvariantResult> invariants) {

    public ContractComputation {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(eirBefore, "eirBefore");
        Objects.requireNonNull(eirAfter, "eirAfter");
        Objects.requireNonNull(openingGca, "openingGca");
        Objects.requireNonNull(closingGca, "closingGca");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(decomposition, "decomposition");
        Objects.requireNonNull(suspense, "suspense");
        Objects.requireNonNull(journal, "journal");
        invariants = List.copyOf(Objects.requireNonNull(invariants, "invariants"));
        if (solves < 0) {
            throw new IllegalArgumentException("solves must be non-negative, got " + solves);
        }
        if (solves > 0 && (routing == null || !routing.resolvesRate())) {
            // The structural half of 05 § 3.2's note. A solve outside a rate-resolving routing is
            // the defect the note forbids, and it is refused here rather than reported because a
            // figure produced by an unauthorised solve should not exist to be reported: the rate
            // it returns is plausible, the journal it produces balances, and nothing downstream
            // would ever look twice. The barrier one level up turns this into a quarantined
            // contract, which is the outcome that gets the contract looked at.
            throw new IllegalStateException(
                "contract " + contractId + " performed " + solves + " solve(s) but its period "
                    + (routing == null ? "carried no event at all" : "routed to "
                        + routing.mechanism() + ", which does not resolve a rate")
                    + "; 05 § 3.2 puts the solve inside the event branch only, and a run that"
                    + " re-solves every contract every period produces the same figures and misses"
                    + " the 10M-contract target by orders of magnitude");
        }
    }

    /** The spine's view of this contract: what crosses into the run-level aggregation. */
    public ContractResult toContractResult() {
        return ContractResult.computed(contractId, closingGca, journal, invariants);
    }

    /** Whether the rate moved this period. True only where a B5.4.5 reset re-solved it. */
    public boolean rateMoved() {
        return !eirBefore.equals(eirAfter);
    }

    /** Whether the stage suppressed recognition, and therefore whether S3-1 was reconciled. */
    public boolean incomeSuppressed() {
        return decomposition.incomeSuppressed();
    }

    /**
     * The ECL discount unwind for the period — retained for the ECL roll-forward, never P&amp;L
     * (FR-603, ACPIR 50).
     *
     * <p>Carried as data and posted nowhere. ACPIR requires the unwind to be computed, because ECL
     * is a present-value measure discounted at the EIR, and says nothing about where it goes; that
     * silence is closed by Board-approved policy, not by this pipeline.
     */
    public Money shadowUnwind() {
        return decomposition.shadowUnwind();
    }

    /** The invariants that failed; empty on a clean contract. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /** A one-line summary for the run log. */
    public String describe() {
        return "contract " + contractId + ": " + openingGca.atPresentationScale() + " + "
            + row.presentedEirInterest() + " − " + row.presentedCashReceived() + " = "
            + closingGca.atPresentationScale()
            + (routing == null ? ", no event" : ", " + routing.mechanism() + " on "
                + routing.driver())
            + ", " + solves + " solve(s), " + breaches().size() + " breach(es)";
    }
}
