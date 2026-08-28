package com.crisil.eir.application.close;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalBatch;
import com.crisil.eir.gl.posting.GlReconciliation;
import com.crisil.eir.gl.posting.GlSummary;
import com.crisil.eir.gl.posting.SubLedgerBalance;
import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.CloseDecision;
import com.crisil.eir.policy.close.CloseRequest;
import com.crisil.eir.policy.close.ExceptionAcceptance;
import com.crisil.eir.policy.close.PeriodCloseGate;
import com.crisil.eir.policy.close.ReconciliationScope;
import com.crisil.eir.policy.close.ReconciliationTie;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.reconciliation.ContractualLegInterest;
import com.crisil.eir.policy.reconciliation.CoreBankingReconciliation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The run-level close: the point at which every Phase 5 control finally has a caller (05 § 3.2).
 *
 * <p><b>What this closes.</b> docs/08 recorded, as Phase 5's honest qualification, that "every
 * Phase 5 control is live code with no caller — nothing in {@code src/main} constructs a
 * {@code GlReconciliation}, a {@code PeriodCloseGate}, a {@code ReplayComparison} or a
 * {@code CoreBankingReconciliation}". SL-1, SL-2 and RC-1 were evaluators nobody invoked, and an
 * evaluator nobody invokes is indistinguishable from an absent one. This class invokes them, in the
 * order 05 § 3.2's tail states: aggregate the invariants, summarise the postings, then close.
 *
 * <p><b>The trap this type is mostly about.</b> An aggregation over an empty or partial population
 * reporting a clean close is the defect this codebase has found more often than any other, and it
 * is at its most dangerous here — a close is the one place a green result is signed. A run that
 * processed nothing has <em>asserted</em> nothing, and every total over it is nil and every
 * reconciliation over it ties. So {@link #present} refuses to be satisfied by a population it did
 * not measure, and the three cases are kept distinct because their remedies are:
 *
 * <ul>
 *   <li><b>no population at all</b> — the contract source named nothing, which is a feed failure
 *       and not a period with no activity;</li>
 *   <li><b>a population, nothing computed</b> — every contract quarantined, which reconciles
 *       perfectly and means the run failed entirely;</li>
 *   <li><b>a shortfall</b> — contracts named and neither computed nor quarantined, which is the
 *       silent one, because those contracts are absent from both sides of every total;</li>
 *   <li><b>a population that measured itself incompletely</b> — an invariant the run is answerable
 *       for and produced no result under. Found reviewing the seam between two independently
 *       written units: {@code OnboardingRun} declared its obligations and reported the gaps, and
 *       {@code RunAggregate} derived its invariant list from whatever the contracts happened to
 *       publish. The close gate cannot catch it, because its only absence check is that the whole
 *       list is empty.</li>
 * </ul>
 *
 * <p>{@code RunAggregate.unaccountedFor()} already measures the third; this class is what makes it
 * block a close rather than merely being available.
 *
 * <p><b>What is deliberately not here.</b> FR-901's gate is not an invariant and gets no id — it
 * consumes {@link InvariantResult}s and returns a decision with refusal reasons, and
 * {@code PeriodCloseGate} owns it. This class assembles the evidence and hands it over; it does not
 * re-decide anything the gate decides, and it does not invent a control for "the close held".
 */
public final class RunClose {

    /**
     * The reconciliation scopes this class computes from the run's own figures.
     *
     * <p>Held as a set so that {@link #present} can refuse a caller-supplied duplicate rather than
     * silently accept a second answer. The complement — what a caller must supply — is not listed,
     * because {@code ReconciliationScope.requiredForClose()} is the authority on which scopes a
     * close needs and duplicating that list here would be the second statement of one rule.
     */
    private static final Set<ReconciliationScope> OWNED_SCOPES = Set.of(
        ReconciliationScope.SUBLEDGER_TO_GL, ReconciliationScope.CONTRACTUAL_LEG_TO_CBS);

    private RunClose() {
    }

    /**
     * Assemble a run's evidence and put it to the close gate.
     *
     * @param request        the run, carrying its ports and its boundary
     * @param aggregate      what the per-contract loop produced
     * @param gcaAccountCode the control account the gross carrying amount sits in; supplied
     *                       because a chart of accounts is bank-specific, the same reason
     *                       {@code JournalLine} validates an account code's shape and not its value
     * @param engineLines    the engine's contractual-leg interest per contract, for RC-1. Supplied
     *                       separately from the CBS feed on purpose: RC-1 compares two independently
     *                       sourced figures, and deriving both from one place would make it a field
     *                       against itself
     * @param portfolioTies  step 6's remaining reconciliations — the Stage 3 four-way and the
     *                       pre-/post-floor duality — which this class cannot source and must not
     *                       fabricate. See the note on {@link #present} for why they are a
     *                       parameter and why passing none is left to fail at the gate
     * @param period         the accounting period being closed
     * @param closedBy       who is closing it
     * @param closedAt       when; taken from the caller rather than a clock, per 03 § 1.1
     * @param exceptions     the run's exception queue contents
     * @param acceptances    four-eyes acceptances for exceptions being closed over
     */
    public static ClosePresentation present(
        RunRequest request,
        RunAggregate aggregate,
        String gcaAccountCode,
        List<ContractualLegInterest> engineLines,
        List<ReconciliationTie> portfolioTies,
        AccountingPeriod period,
        String closedBy,
        Instant closedAt,
        List<ExceptionRecord> exceptions,
        List<ExceptionAcceptance> acceptances) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(gcaAccountCode, "gcaAccountCode");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(closedAt, "closedAt");
        engineLines = List.copyOf(Objects.requireNonNull(engineLines, "engineLines"));
        portfolioTies = List.copyOf(Objects.requireNonNull(portfolioTies, "portfolioTies"));
        exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
        acceptances = List.copyOf(Objects.requireNonNull(acceptances, "acceptances"));

        List<InvariantResult> evidence = new ArrayList<>();
        List<String> refusals = new ArrayList<>();

        // (1) The run's own refusals, before anything is measured. Everything below is a total
        // over `results`, and every one of them is nil-and-tying on an empty list. Read from the
        // aggregate rather than recomputed — see runRefusals.
        refusals.addAll(runRefusals(aggregate));

        // (2) SL-2 over the batch. JournalBatch deliberately permits an unbalanced entry to exist
        // so that SL-2 has something to detect; this is where the detection reaches a decision.
        JournalBatch batch = JournalBatch.of(
            request.runId(), request.periodId(), aggregate.journals());
        evidence.add(batch.sidesBalance());

        // (3) SL-1: the sub-ledger's closing balances against the GL's control account, read
        // through the port. Two independently sourced sides — the summarisation from the batch is
        // lossless by construction and is NOT the comparison, because comparing a group-and-sum
        // against its own input could not fail.
        GlSummary summary = GlSummary.summarise(batch);
        List<SubLedgerBalance> subLedger = new ArrayList<>();
        // No null guard on the closing balance, deliberately. One was here, and it was what made
        // a computed contract with no balance vanish from this side of SL-1 while its journal
        // balanced on the other. ContractResult.computed refuses that contract now, so the rule is
        // stated where the contract is built rather than absorbed where it is read.
        aggregate.results().stream()
            .filter(ContractResult::isComputed)
            .forEach(result -> subLedger.add(SubLedgerBalance.of(
                result.contractId(), gcaAccountCode, result.closingGca())));
        GlReconciliation glReconciliation = GlReconciliation.of(
            request.periodId(), request.bookId(), subLedger,
            request.generalLedger().controlAccountBalances(request.boundary()), List.of());
        evidence.add(glReconciliation.tiesToGl());

        // (4) RC-1: the contractual leg against the core banking system, ADR-0004's book of record
        // for what the borrower was billed.
        CoreBankingReconciliation cbs = CoreBankingReconciliation.over(
            request.periodId(), currencyOf(aggregate), engineLines,
            request.coreBanking().billedInterest(request.boundary()), List.of());
        evidence.add(cbs.tiesToCoreBanking());

        // (5) The per-contract invariants, collapsed to one result per id. Without this the close
        // gate would receive ten million results and conjunction would keep the first breach's
        // deviation per id — which is correct behaviour and the wrong place to rely on it.
        evidence.addAll(InvariantResult.oneResultPerInvariant(aggregate.invariants()));

        // (6) Step 6's four reconciliations. Two of them this class sources itself, from the
        // figures it just reconciled; two it cannot, and the difference is not a matter of effort.
        // A ContractResult carries a closing gross carrying amount and a journal — it carries no
        // ECL allowance, no suspense balance and no stage, so a Stage 3 four-way and a pre-/post-
        // floor duality are not derivable from anything in this aggregate. The alternative to a
        // parameter would be synthesising a tie of nil against nil, which would present as tied on
        // every run in the book's history and is the exact shape ReconciliationScope's javadoc
        // exists to refuse: "a close in which the Stage 3 four-way was never run".
        List<ReconciliationTie> ties = new ArrayList<>();
        ties.add(new ReconciliationTie(ReconciliationScope.SUBLEDGER_TO_GL,
            sumOf(glReconciliation, true), sumOf(glReconciliation, false),
            "GL control account"));
        ties.add(new ReconciliationTie(ReconciliationScope.CONTRACTUAL_LEG_TO_CBS,
            cbs.totalEngineContractualInterest(), cbs.totalCbsBilledInterest(),
            "core banking feed"));
        for (ReconciliationTie tie : portfolioTies) {
            if (OWNED_SCOPES.contains(tie.scope())) {
                // A throw, not a refusal. One scope must have one answer, and a second answer for
                // a scope this class computed is a wiring defect in the caller, not a fact about
                // the book: the gate checks every tie it is handed, so a caller-supplied tied
                // SUBLEDGER_TO_GL alongside a broken one would not hide the break — but it would
                // put two residuals for one reconciliation in front of whoever has to fix it, and
                // leave which one the close report shows decided by list position.
                throw new IllegalArgumentException("a " + tie.scope().label()
                    + " reconciliation was supplied, but this close computes that scope itself"
                    + " from the run's own figures; supplying a second answer for it leaves the"
                    + " residual an operator sees decided by list order");
            }
            ties.add(tie);
        }

        CloseDecision decision = PeriodCloseGate.evaluate(new CloseRequest(
            period, closedBy, closedAt, request.boundary().recordedAsAt(),
            List.copyOf(evidence), exceptions, acceptances, List.copyOf(ties)));

        return new ClosePresentation(
            List.copyOf(evidence), List.copyOf(refusals), decision, batch, glReconciliation, cbs);
    }

    /**
     * Every reason this run must not be reported as a clean close, taken from the run itself.
     *
     * <p><b>Delegated, not restated.</b> This method used to recompute three of
     * {@code RunAggregate}'s reasons — empty population, nothing computed, and a shortfall between
     * what was named and what came back. It was the fourth instance in this codebase of one rule
     * stated in two places where only one place has it, and the two the copy was missing are the
     * dangerous kind: a result naming a contract the population does not, and two results for one
     * contract, where one contract's figures overwrote another reading of the same contract and
     * whichever survived did so by iteration order. Both were caught by the aggregate, reported in
     * its {@code blockingReasons}, and closed anyway.
     *
     * <p><b>What this list therefore contains, and what the gate contains.</b> These are the run's
     * own facts — its population arithmetic, its unasserted obligations, and its per-contract
     * invariant breaches. The gate holds the run-level ones: SL-2 over the batch, SL-1 against the
     * GL, RC-1 against the CBS, the four reconciliation ties, the period's attestation and the
     * exception queue. A per-contract breach appears in both, once as a reason the run blocked and
     * once as a {@code CloseGateRefusal}; that is duplication in a report and not two answers to
     * one question, and it is the price of the two lists being sourced from the two places that
     * actually know.
     *
     * <p>None of these is published as an {@link InvariantResult}. "This run measured nothing" is
     * not an invariant breach — it is a reason the invariants cannot be believed — and giving it an
     * id would put it in the same list as a figure that does not tie, where a reader would try to
     * reconcile it.
     */
    private static List<String> runRefusals(RunAggregate aggregate) {
        return aggregate.blockingReasons();
    }

    /**
     * One side of the sub-ledger-to-GL tie, summed across control accounts.
     *
     * <p>Summed here rather than read off {@code GlReconciliation}, which publishes its explained,
     * unexplained and self-reversing totals but not the two raw sides — reasonably, since its own
     * job is the difference. The close gate's {@code ReconciliationTie} wants the pair, because a
     * tie reported as "difference nil" and a tie reported as "4,250,000.00 against 4,250,000.00"
     * are the same fact and only one of them lets a reader see the magnitude being reconciled.
     */
    private static Money sumOf(GlReconciliation reconciliation, boolean subLedgerSide) {
        Money total = Money.zero(reconciliation.currency());
        for (var line : reconciliation.lines()) {
            total = total.plus(subLedgerSide ? line.subLedgerTotal() : line.glBalance());
        }
        return total;
    }

    /** The currency the run is in, from the first computed journal. */
    private static Currency currencyOf(RunAggregate aggregate) {
        return aggregate.journals().isEmpty()
            ? Money.INR
            : aggregate.journals().getFirst().currency();
    }

    /**
     * What a close was presented with, and what the gate made of it.
     *
     * @param evidence            every invariant result put to the gate
     * @param runRefusals         reasons the run itself cannot support a close — its population
     *                            arithmetic, its unasserted obligations and its per-contract
     *                            breaches; empty on a run that measured what it was given
     * @param decision            the gate's answer
     */
    public record ClosePresentation(
        List<InvariantResult> evidence,
        List<String> runRefusals,
        CloseDecision decision,
        JournalBatch batch,
        GlReconciliation glReconciliation,
        CoreBankingReconciliation coreBanking) {

        public ClosePresentation {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            runRefusals = List.copyOf(Objects.requireNonNull(runRefusals, "runRefusals"));
            Objects.requireNonNull(decision, "decision");
        }

        /**
         * Whether the period may be closed.
         *
         * <p><b>Both halves, and the run's own half comes first.</b> The gate answers on the
         * evidence it was given; it cannot know that the evidence covers nothing. A run over an
         * empty population produces a clean gate decision, which is exactly why the run's own
         * verdict is asked separately and not inferred from the gate's.
         */
        public boolean mayClose() {
            return runRefusals.isEmpty() && !decision.isRefused();
        }

        /** The invariant results that failed. */
        public List<InvariantResult> breaches() {
            return evidence.stream().filter(result -> !result.satisfied()).toList();
        }

        /** Whether a given invariant was actually put to the gate — not whether it passed. */
        public boolean asserted(InvariantId id) {
            return evidence.stream().anyMatch(result -> result.id() == id);
        }

        /** The total deviation across every breach, for a one-line close report. */
        public BigDecimal totalDeviation() {
            return breaches().stream()
                .map(result -> result.deviation().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
