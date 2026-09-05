package com.crisil.eir.application.run;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.calc.amort.AmortisationEngine;
import com.crisil.eir.calc.amort.AmortisationResult;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.Stage3Decomposition;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.calc.amort.SuspenseLedger;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The inner loop of the month-end amortisation run: one contract, one period (05 § 3.2).
 *
 * <p>The sequence diagram in 05 § 3.2 is implemented here step for step — load contract, prior
 * balance, events and staging; route any event by driver tag; roll forward on the gross basis;
 * compute the shadow unwind and suppress income where the stage says so; assert the per-contract
 * invariants; produce the period balance and the journal.
 *
 * <h2>Where the solve sits</h2>
 *
 * <p>05 § 3.2's own note: "<b>inside the event branch only</b>. A fixed-rate contract with no events
 * never re-solves. The steady-state run is overwhelmingly roll-forward arithmetic, which is what
 * makes the 10M-contract target reachable." That is a correctness requirement about cost, and cost
 * is invisible to a test that compares figures — a pipeline re-solving every contract every period
 * produces the same rate (the solve returns what is already stored), the same balance and the same
 * journal. So the solver is unreachable from here except through {@link SolveAudit}, which counts
 * and attributes every call; the count travels on {@link ContractComputation}; and
 * {@code ContractComputation}'s constructor refuses a computation whose solve count is positive
 * without a rate-resolving routing decision behind it. A contract with no event that solved anyway
 * is therefore not a slow success — it is a quarantined contract.
 *
 * <h2>Two derivations of the accrual length, on purpose</h2>
 *
 * <p>{@link AmortisationEngine} derives a row's accrual exponent from the supplied vector's dates
 * under the time convention. {@link #scheduleAccrualExponent} derives it from the <em>contract's
 * own schedule</em> — {@code ContractTerms.dueDate(n-1)} to {@code dueDate(n)} — and that figure is
 * what the decomposition is given. {@code Stage3Decomposition.againstLedger} then compares them,
 * which is the whole reason ST-2 is a control here rather than a restatement: ST-2, S3-1 and S3-2
 * are algebraic identities between figures derived from the same two inputs, so they hold however
 * wrong the accrual factor is. The two derivations disagree exactly when the period's flow was
 * mis-dated, when the loop fed two periods' flows into one boundary, or when a broken first period
 * met a mis-selected day count — and nothing else in the engine would attribute those to here.
 *
 * <h2>What is asserted, and what input makes each of them fail</h2>
 *
 * <ul>
 *   <li><b>SL-2</b> (every contract) — the journal's debits against its credits. Fails on a cash
 *       receipt the cash book and the flow vector disagree about, and on an accrual the
 *       roll-forward ledger and the decomposition disagree about. See {@link PeriodJournal} for how
 *       each block is sourced from two systems to make that so.</li>
 *   <li><b>ST-2</b> (every contract) — the accrual length and the gross-basis interest against the
 *       ledger's own row. Fails on a mis-dated period flow, as above.</li>
 *   <li><b>S3-1</b> (where recognition is suppressed) — the four-way reconciliation of FR-605.
 *       Fails when the closing balance does not tie to opening plus accrual less cash, and when
 *       cash reaches the interest leg without a matching recovery out of suspense, which is the
 *       double-count leg.</li>
 * </ul>
 *
 * <p><b>Passes that cannot fail are not republished.</b> {@code Stage3Decomposition}'s own ST-2
 * identity is an exact subtraction and its S3-2 is set two lines after the suppression decision;
 * {@code CatchUpResult}'s CU-1 compares a rate this pipeline hands in twice. Each is a correct
 * control at the level that owns it and none of them can be failed by any input reaching this file,
 * so they are folded in <em>only where they breached</em> — a breach is always published, a
 * tautological pass never is. Publishing them as passes would add coverage without adding a
 * control, and this codebase has recorded four controls that read as coverage and could not fail.
 *
 * <h2>Where S3-1 is not run</h2>
 *
 * <p>Only where the stage suppresses recognition. Its fourth leg asserts that cash applied to
 * interest equals what came out of suspense, which is true by construction of a suspense regime and
 * false for every performing contract in the book: a Stage 1 borrower paying an EMI applies cash to
 * interest with nothing in suspense to recover. Running it on performing contracts would put a
 * guaranteed breach on ten million rows, and the response to a control that is red by design is to
 * suppress it — after which it is not there for the contracts it was written for.
 */
public final class ContractPipeline {

    private final RunRequest request;
    private final ContractPeriodSource periods;
    private final RoutingTableRegistry routing;
    private final SolveAudit solves;

    /**
     * @param request the run, carrying its own boundary and sources (see {@code RunRequest})
     * @param periods where the period's movements are read from
     * @param routing the approved routing table series; a change of reading is an appended table,
     *                never an edit to a switch (ADR-0006, FR-504)
     * @param solves  the only route to a solver from this package
     */
    public ContractPipeline(
        RunRequest request,
        ContractPeriodSource periods,
        RoutingTableRegistry routing,
        SolveAudit solves) {
        this.request = Objects.requireNonNull(request, "request");
        this.periods = Objects.requireNonNull(periods, "periods");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.solves = Objects.requireNonNull(solves, "solves");
    }

    /**
     * Computes one contract's period.
     *
     * <p>Throws for a data condition the contract cannot be computed without, and returns
     * {@link InvariantResult} values for everything that can be measured — the split the whole
     * codebase keeps. The throws are what {@link MonthEndRun}'s barrier turns into a quarantined
     * contract, and every one of them is a condition under which publishing a figure would be
     * worse than publishing none.
     */
    public ContractComputation compute(String contractId) {
        Objects.requireNonNull(contractId, "contractId");
        ContractPeriod period = periods.periodFor(contractId, request.boundary());
        if (!period.contractId().equals(contractId)) {
            // A source that answers about a different contract would put one contract's movements
            // on another's balance, and both would reconcile — the shape of loss FR-905's
            // per-contract accounting exists to make impossible.
            throw new IllegalStateException(
                "period source answered for contract " + period.contractId() + " when asked for "
                    + contractId);
        }
        ContractStateSource.OpeningState state =
            request.contractState().openingState(contractId, request.boundary())
                .orElseThrow(() -> new IllegalStateException(
                    "contract " + contractId + " is in the run's population but has no state as at "
                        + request.boundary().recordedAsAt() + "; a period cannot be rolled forward"
                        + " from a balance nobody recorded, and inventing an opening of nil would"
                        + " publish a contract with no exposure and reconcile perfectly"));
        if (!state.hasBeenSolved()) {
            // Onboarding is 05 § 3.1 and a different use case: it runs the SPPI gate first, then
            // classifies fees, then projects, then solves at inception against IC-1. Doing any of
            // that from the month-end loop would solve a rate with no fee classification and no
            // measurement-category check behind it.
            throw new IllegalStateException(
                "contract " + contractId + " has no EIR in force; initial recognition is 05 § 3.1"
                    + " and cannot be performed from the month-end roll-forward, which has neither"
                    + " the SPPI gate nor the fee classification in front of it");
        }

        Rate eirBefore = state.eir();
        Rate eirAfter = eirBefore;
        Money base = state.openingGca();
        RoutingDecision decision = null;
        CatchUpResult catchUp = null;
        // Measured as a delta rather than read as a total, so that an audit shared across several
        // periods of the same contract — a replay walking a year, a back-test — still reports what
        // THIS period cost rather than what the contract has cost since the audit was created.
        int solvesBefore = solves.solveCountFor(contractId);

        if (period.hasEvent()) {
            PeriodEvent event = period.event();
            // Routed by driver tag and rate type, through the approved table in force on the
            // EVENT's date — not today's. That is what makes a closed period replay under the
            // reading it closed under (ADR-0006), and RoutingTableRegistry refuses to substitute
            // its compiled-in baseline where no approved table governs the date.
            decision = routing.route(event.driver(), state.terms().rateType(), event.eventDate());
            switch (decision.mechanism()) {
                case RESET -> eirAfter = reSolve(contractId, event, decision, base, period, eirBefore);
                case CATCH_UP -> {
                    catchUp = restate(eirBefore, base, event, period);
                    base = catchUp.restatedGca();
                }
                case MODIFICATION_TEST -> {
                    catchUp = onModificationTest(contractId, event, base, period, eirBefore);
                    base = catchUp.restatedGca();
                }
                case NONE -> {
                    // No EIR consequence: roll the period forward at the unchanged rate over its
                    // own flows and restate nothing. That is Mechanism.NONE's definition and what
                    // ModificationConclusion's javadoc predicts a downstream reader will do with
                    // the value.
                    //
                    // This branch used to throw alongside DERECOGNITION, on the reasoning that
                    // "neither is a roll-forward". For DERECOGNITION that holds. For NONE it is
                    // the opposite: NONE is precisely a roll-forward, and quarantining it lost
                    // every contract whose driver the bank had elected as immaterial —
                    // RoutingTableFormatTest pins that "DISBURSEMENT_TIMING routed to NONE is a
                    // legitimate materiality election", and RoutingTable requires every driver
                    // mapped, so an approved table can carry it. A retail book with a late-drawdown
                    // election would have quarantined every affected contract, every month, with a
                    // message telling the operator the routing was not a roll-forward.
                    //
                    // Guarded rather than trusted, in the style of this method's two other
                    // structural throws: the two claims NONE makes about this period are that the
                    // balance was not restated and that nothing re-solved, and both are decidable
                    // right here from locals the switch above may have moved.
                    if (!base.equals(state.openingGca())) {
                        throw new IllegalStateException(
                            "contract " + contractId + " routed to " + Mechanism.NONE + " under"
                                + " table version " + decision.routingTableVersionId()
                                + " and its balance was restated from " + state.openingGca()
                                + " to " + base + "; NONE means no EIR consequence, so a"
                                + " restatement here is a branch reached by accident");
                    }
                    if (solves.solveCountFor(contractId) != solvesBefore) {
                        throw new IllegalStateException(
                            "contract " + contractId + " routed to " + Mechanism.NONE + " and"
                                + " reached a solver " + (solves.solveCountFor(contractId)
                                    - solvesBefore) + " time(s); a mechanism with no EIR"
                                + " consequence cannot re-solve the rate");
                    }
                }
                case DERECOGNITION -> throw new IllegalStateException(
                    "contract " + contractId + " routed to " + decision.mechanism() + " under table"
                        + " version " + decision.routingTableVersionId() + ", which is not a"
                        + " roll-forward: 05 § 3.2's loop has two calc branches, a reset and a"
                        + " catch-up, and rolling a derecognised contract forward at its old rate"
                        + " would carry a balance that should have left the book. Reachable only"
                        + " from a ModificationConclusion, never from a table —"
                        + " Mechanism.isRoutable() refuses a table that names it");
            }
        }

        // ---- Roll forward on the GROSS basis ---------------------------------------------------
        // Gross for every stage, including Stage 3: ACPIR presents provisions separately rather
        // than netting them, and FR-610 makes staging not an EIR event. Stage 3 gets a different
        // recognition decision on the same figures, never a different roll-forward.
        AmortisationResult roll = AmortisationEngine.segment(
            base, eirAfter, period.periodFlows(), period.convention());
        // ONE published movement per contract per accounting period, however many accrual
        // boundaries the contract's own calendar puts inside it.
        //
        // This used to refuse roll.periods() != 1 outright, and the reason it gave was sound: the
        // decomposition, the suspense movement and the reconciliation are all statements about the
        // period, so a vector that produced two boundaries would have them describing the first
        // while the balance moved by both. But refusing is the wrong remedy, and it cost the two
        // frequencies CompoundingBasis.stepOf goes to explicit trouble to support -- a WEEKLY
        // facility has four accrual boundaries in an accounting month and a FORTNIGHTLY one has
        // two, so the refusal stepOf was written to avoid reappeared one layer up. Confirmed on a
        // live cluster: four April instalments came back as four flows in one period's vector.
        //
        // What it cost, stated accurately, because the surrounding documentation had it wrong and
        // this comment repeated it once: NOT an abort. FailureIsolation.isolate catches every
        // RuntimeException and rethrows only a run-level InvariantBreachException, so the throw
        // below was filed as a quarantine and the run completed -- FailureIsolationTest shows
        // exactly that for an IllegalStateException. The cost was that every weekly and
        // fortnightly loan in the book was quarantined every period, which blocks the close
        // (RunAggregate refuses while quarantined exceptions are unresolved) and hands an operator
        // an arithmetic precondition to work instead of a schedule frequency to support.
        //
        // The answer is to give those three consumers a row that describes ALL the boundaries.
        // asOneAccrualPeriod does that by telescoping -- first opening, last closing, both columns
        // and the accrual exponent summed at working precision -- and AmortisationRow's own
        // constructor re-checks the roll-forward identity on the result. The rows stay on `roll`,
        // so FR-808's trace can still show the four weekly accruals behind one monthly figure.
        //
        // One incidental change, stated because it is a change and not a fix: the row now carries
        // period.periodOrdinal() -- the contract's ordinal on its own schedule, 13 for a month-13
        // contract -- where it used to carry whatever AmortisationEngine.segment numbered a fresh
        // segment's first row, which is 1. Nothing in eir-api, eir-gl or eir-batch reads
        // AmortisationRow.period(), so this is unobserved downstream; it is nonetheless the right
        // way round, because a published movement's ordinal should name the period it describes and
        // "1" named nothing on a seasoned contract.
        AmortisationRow row = roll.asOneAccrualPeriod(period.periodOrdinal());

        // ---- The CONTRACTUAL leg, rolled independently of the EIR leg ---------------------------
        //
        // RC-1's engine side, and the reason it is computed here rather than read. The invariant
        // compares what the ENGINE says the borrower was contractually charged against what the CBS
        // says it billed, and ContractualLegInterest's javadoc names the mis-wiring precisely:
        // "taking the CBS instead of the contractual leg is the single most plausible mis-wiring in
        // this control". It had been made -- EirService built the engine leg from
        // OpeningState.contractualInterestBilled, which IS the CBS figure by design ("what the
        // borrower was billed, from the CBS"), so both sides of RC-1 came from one column of one
        // row and its deviation was structurally nil. Demonstrated on a live cluster: one UPDATE of
        // cbs_billed_interest moved both legs together.
        //
        // Nothing in the month-end run computed a contractual leg, which is why that field was the
        // only thing available to reach for. It is computable from what this method already holds:
        // the contractual-leg balance brought forward and the contract's own contractual rate, over
        // the same accrual exponent the EIR leg used. AmortisationEngine.segment rather than
        // contractualLeg() because the leg starts from the balance carried forward, not from par,
        // and makes no terminal claim -- the contractual leg's residue is real (FR-804).
        Money contractualInterest = contractualLegInterest(state, period, scheduleExponentFor(state, period));

        // ---- Decompose, and suppress where the stage says so -----------------------------------
        BigDecimal scheduleExponent = scheduleExponentFor(state, period);
        Stage3Decomposition decomposition = Stage3Decomposition.forAccrualPeriod(
            base, state.allowance(), eirAfter, state.contractualInterestBilled(), state.stage(),
            scheduleExponent);
        SuspenseLedger suspense = SuspenseLedger.forPeriod(
            period.suspenseOpeningBalance(), decomposition.toSuspense(),
            period.suspenseRecovered(), period.suspenseWrittenOff());
        Stage3Reconciliation reconciliation = decomposition.incomeSuppressed()
            ? Stage3Reconciliation.over(
                base, row.closingGca(), period.cashAppliedToPrincipal(),
                period.cashAppliedToInterest(), state.contractualInterestBilled(), decomposition,
                suspense)
            : null;

        JournalEntry journal =
            PeriodJournal.of(request, period, row, decomposition, suspense, catchUp);

        return new ContractComputation(
            contractId, eirBefore, eirAfter, base, row.closingGca(), row, contractualInterest,
            decomposition, suspense, reconciliation, decision, catchUp,
            solves.solveCountFor(contractId) - solvesBefore, journal,
            assertions(journal, row, decomposition, reconciliation, catchUp));
    }

    /**
     * The per-contract invariant set, one result per id.
     *
     * <p>Collapsed with {@link InvariantResult#oneResultPerInvariant} because a named invariant is
     * entitled to exactly one answer: more than one result under one id means anything resolving it
     * by name gets whichever happens to come first, and where they disagree that is a control
     * reporting satisfied on a leg that breached.
     */
    private static List<InvariantResult> assertions(
        JournalEntry journal,
        AmortisationRow row,
        Stage3Decomposition decomposition,
        Stage3Reconciliation reconciliation,
        CatchUpResult catchUp) {
        List<InvariantResult> assembled = new ArrayList<>();

        // SL-2. Fails where the cash book and the flow vector disagree about what was received, or
        // where the ledger's accretion and the decomposition's disagree about the accrual.
        assembled.add(journal.sidesBalance());

        // ST-2. Fails where the accrual length derived from the contract's schedule and the one
        // derived from the supplied vector's dates disagree — the exponent leg is compared first
        // because it is the cause, which localises the failure instead of leaving one figure to
        // explain.
        assembled.addAll(decomposition.againstLedger(row));

        // Breaches only, never tautological passes — see the class javadoc.
        assembled.addAll(decomposition.breaches());
        if (catchUp != null) {
            assembled.addAll(catchUp.breaches());
        }

        // S3-1. Four legs, one result, whose deviation is the total ABSOLUTE residual: two breaks
        // in opposite directions must not net to a reconciled period.
        if (reconciliation != null) {
            assembled.add(reconciliation.fourWay());
        }
        return InvariantResult.oneResultPerInvariant(assembled);
    }

    /**
     * The B5.4.5 branch: re-solve the rate over the revised flows from the current carrying amount.
     *
     * <p>The one place in this package that reaches a solver, and it is reached through
     * {@link SolveAudit} so that the reaching is counted.
     */
    private Rate reSolve(
        String contractId,
        PeriodEvent event,
        RoutingDecision decision,
        Money base,
        ContractPeriod period,
        Rate eirBefore) {
        // Seeded from the rate in force rather than from the contractual rate. 03 § 4.2 names the
        // contractual rate as the seed at inception, and mid-life it is the wrong units: under
        // actual dating the solved rate is annual effective while ContractTerms.periodicRate() is
        // per schedule period, so a contractual seed would start the search an order of magnitude
        // away. The rate in force is always in the convention's own units, because
        // AmortisationEngine refuses a rate and a convention that disagree about periodicity.
        SolveResult solved = solves.solve(
            contractId,
            "B5.4.5 reset, driver " + event.driver() + ", routing table version "
                + decision.routingTableVersionId(),
            SolveRequest.of(
                event.revisedFlows(), base, period.convention(), eirBefore.periodic()));
        if (!solved.hasRate()) {
            // No silent fallback: not to zero, not to the contractual rate, not to the previous
            // period's rate. 03 § 4.3 names that the most damaging failure available to the engine,
            // because it produces plausible numbers and leaves no trace. The contract is
            // quarantined instead, and the solver's own diagnostic travels with it.
            throw new IllegalStateException(
                "contract " + contractId + " routed to a B5.4.5 reset and the solve returned "
                    + solved.status() + ": " + solved.diagnostic()
                    + ". Falling back to the rate in force would publish a plausible figure with"
                    + " nothing recording that the reset never happened");
        }
        return solved.rate();
    }

    /** The B5.4.6 branch: restate the carrying amount at the ORIGINAL rate, which does not move. */
    /**
     * The accrual exponent from the contract's own schedule, memo-free.
     *
     * <p>Extracted only so the contractual roll and the decomposition provably use the SAME
     * exponent. Two independent derivations of one quantity is the pattern that makes ST-2 a
     * control; two independent derivations of the ACCRUAL LENGTH is just a way for them to
     * disagree.
     */
    private static BigDecimal scheduleExponentFor(
        ContractStateSource.OpeningState state, ContractPeriod period) {
        return scheduleAccrualExponent(state.terms(), period);
    }

    /**
     * Interest at the CONTRACTUAL rate on the contractual-leg balance, for the period.
     *
     * <p>RC-1's engine side, and genuinely a second derivation: a different rate on a different
     * balance from the EIR leg, so the two can disagree and RC-1 can fail. Before this the engine
     * leg was {@code OpeningState.contractualInterestBilled} — the CBS figure by design — so both
     * sides of RC-1 were one column of one row. Demonstrated on a live cluster: one
     * {@code UPDATE cbs_billed_interest} moved both legs together.
     *
     * <h2>Computed directly rather than rolled through the engine, and the reason is a real guard</h2>
     *
     * <p>The first version called {@link AmortisationEngine#segment}, and it threw for every
     * {@code ACTUAL_DATE} contract: "rate compounds 12 times a year but convention
     * ACTUAL_DATE(ACT/365F) implies 1". That guard is right and the call was wrong. Under actual
     * dating the EIR is an annual-effective rate and the roll is measured in years, whereas
     * {@code ContractTerms.contractualRate} is stated at the <em>schedule's</em> periodicity — the
     * two are not interchangeable, which is exactly what the engine refuses.
     *
     * <p>{@link #scheduleAccrualExponent} is already measured in that same schedule periodicity —
     * {@code ContractTerms.dueDate(n−1)} to {@code dueDate(n)} — so the contractual rate and this
     * exponent are the matched pair, and it is the pair {@code Stage3Decomposition.forAccrualPeriod}
     * is given for the same reason. Accreting them directly is therefore not a shortcut around the
     * engine; it is the only pairing that is dimensionally correct.
     *
     * <p><b>No terminal assertion, and none is wanted.</b> The contractual leg carries the billed
     * schedule's residue and that residue is real: where the rounding policy is
     * {@code LMS_AUTHORITATIVE} the schedule is what the core banking system actually billed, which
     * is the only way this leg reconciles to the CBS every month (FR-804).
     *
     * <p>Nil where the contract carries no contractual-leg balance forward — a contract recognised
     * this period has none. Nil is the right engine leg there rather than a refusal: RC-1 then
     * compares nil against whatever the CBS billed, which is a comparison that can fail, and
     * failing is the point.
     */
    private static Money contractualLegInterest(
        ContractStateSource.OpeningState state, ContractPeriod period, BigDecimal exponent) {

        Money opening = state.openingContractual();
        if (opening.isZero()) {
            return Money.zero(opening.currency());
        }
        BigDecimal accretion = Precision.onePlusPow(
            state.terms().contractualRate().periodic(), exponent).subtract(BigDecimal.ONE);
        return opening.times(accretion);
    }

    private CatchUpResult restate(
        Rate eirBefore, Money base, PeriodEvent event, ContractPeriod period) {
        // The rate is passed twice — as the rate the restatement discounts at and as the rate the
        // pipeline persists after the event — which is CU-1's evidence that the catch-up was not
        // quietly discounted at a re-solved rate.
        return CatchUpCalculator.restate(
            eirBefore, eirBefore, base, event.revisedFlows(), period.convention());
    }

    /**
     * The substantiality branch, which the engine is not entitled to decide.
     *
     * <p>05 § 3.2 shows a calc call for a reset and a calc call for a catch-up and <em>none</em> for
     * a modification test, and that absence is the requirement. {@code ModificationConclusion} has
     * three outcomes precisely so that "the evidence does not settle it" is expressible, so a
     * pipeline that picked one when nothing had been recorded would be answering a judgement
     * question with an implementation detail. Both wrong answers cost real money: treating a
     * substantial modification as a catch-up keeps a balance that should have been derecognised,
     * and treating a non-substantial one as substantial books a disposal that did not happen.
     *
     * <p>So an undecided event quarantines the contract. That is FR-905 working as intended — the
     * run completes, the contract is named, and the close gates on the count — and it is not the
     * silent default FR-202 forbids, because no figure is published at all.
     */
    private CatchUpResult onModificationTest(
        String contractId, PeriodEvent event, Money base, ContractPeriod period, Rate eirBefore) {
        if (!event.hasDecidedConclusion()) {
            throw new IllegalStateException(
                "contract " + contractId + " has a " + event.driver() + " event on "
                    + event.eventDate() + " which routes to a substantiality assessment, and no"
                    + " conclusion has been recorded"
                    + (event.conclusion() == null ? "" : " (" + event.conclusion() + ")")
                    + "; the engine computes the 10% test as evidence and does not decide, and"
                    + " guessing between a catch-up and a derecognition is a judgement taken by an"
                    + " implementation detail on figures that differ by the whole balance");
        }
        Mechanism concluded = event.conclusion().mechanism();
        if (concluded != Mechanism.CATCH_UP) {
            throw new IllegalStateException(
                "contract " + contractId + " was assessed " + event.conclusion() + ", which means "
                    + concluded + "; derecognition recognises a new asset at fair value with a"
                    + " fresh EIR and is initial recognition (05 § 3.1), not a roll-forward");
        }
        return restate(eirBefore, base, event, period);
    }

    /**
     * The accrual length for the period, derived from the <b>contract's own schedule</b>.
     *
     * <p>Independent of the length {@link AmortisationEngine} derives from the supplied vector's
     * dates, which is the point: see the class javadoc. Under {@link TimeConvention.PeriodicIndex}
     * the exponent is exactly one, because that convention's precondition <em>is</em> uniform
     * periods with every flow on a boundary — so a vector whose flow carries period index 2 is
     * declaring an accrual this schedule does not have, and ST-2 says so. Under
     * {@link TimeConvention.ActualDate} it is the day-counted fraction between the previous
     * scheduled due date and this one, which is also what makes a broken first period come out
     * right: at ordinal 1 the interval starts at {@code dueDate(0)}, the disbursement date.
     */
    static BigDecimal scheduleAccrualExponent(ContractTerms terms, ContractPeriod period) {
        if (period.convention() instanceof TimeConvention.ActualDate actual) {
            return actual.dayCount().yearFraction(
                terms.dueDate(period.periodOrdinal() - 1), terms.dueDate(period.periodOrdinal()));
        }
        return BigDecimal.ONE;
    }
}
