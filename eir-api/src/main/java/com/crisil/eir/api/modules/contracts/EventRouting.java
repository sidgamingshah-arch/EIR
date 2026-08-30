package com.crisil.eir.api.modules.contracts;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.PeriodEvent;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.projection.AnnuityProjector;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.routing.ModificationTest;
import com.crisil.eir.calc.routing.ModificationTestResult;
import com.crisil.eir.calc.routing.QualitativeTrigger;
import com.crisil.eir.calc.routing.ReviewBand;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.solver.BracketedNewtonSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import com.crisil.eir.policy.routing.RoutingTableUnavailableException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Routes one event and renders what the routing produced (06 § 3, FR-504, FR-507, FR-511).
 *
 * <p><b>The two routing inputs are the driver tag and the instrument's rate type.</b> Nothing here
 * looks at whether the rate moved, and that is the whole point (FR-507). The event date is <em>not</em>
 * a routing input either — it selects which approved reading applies, through
 * {@link RoutingTableRegistry#route}, which is what makes a closed period replay under the reading
 * it closed under rather than under today's (ADR-0006).
 *
 * <p><b>The rate-type check is where the discrimination becomes visible.</b> Every contract in this
 * book is {@code RateType.FIXED}, so an event tagged {@code TIME_VALUE_OF_MONEY} —
 * a benchmark movement, which the baseline table maps to a B5.4.5 reset — does <em>not</em> reset:
 * {@code DefaultEventRouter} overrides the table row, because a fixed-rate instrument cannot
 * experience a market movement by its own terms, so the combination can only have come from
 * renegotiation. The response carries {@code overriddenByRateTypeCheck} and the router's own
 * rationale, so the override is on the record rather than being a silent difference between two
 * mechanisms that differ by the whole modification question.
 *
 * <p><b>The three mechanisms, and what each response asserts.</b>
 *
 * <ul>
 *   <li><b>RESET</b> (B5.4.5) — re-solve the rate over the revised remaining flows from the
 *       <em>current</em> carrying amount. The balance does not move and there is no catch-up, so the
 *       response carries {@code catchUpAmount} of {@code 0.00} and reports the balance before and
 *       after so a reader can see they are the same figure.
 *   <li><b>CATCH_UP</b> (B5.4.6) — retain the original rate, restate the balance to the present
 *       value of the revised flows at that rate, and recognise the difference. The response carries
 *       {@code eirUnchanged}, and it is <b>read off invariant CU-1</b> rather than written as a
 *       constant — see {@link #catchUp}.
 *   <li><b>MODIFICATION_TEST</b> (5.4.3) — compute the 10% test and the qualitative triggers as
 *       <em>evidence</em> and decline to conclude where the evidence does not settle it (FR-511).
 *       {@code substantialityConclusion} is {@code PENDING_APPROVAL} in that case and the response
 *       carries no rate and no restated balance, because none was produced.
 * </ul>
 *
 * <p><b>Nothing here moves the book.</b> The endpoint routes, computes and evidences; the balance
 * on the book moves when the period is rolled forward, which is {@code ContractPipeline}'s job and
 * the only place that also asserts the roll-forward invariants over the result. An event endpoint
 * that quietly restated the book would give the next run a balance no invariant had checked.
 */
public final class EventRouting {

    /**
     * The review band applied to the 10% test: 10% with a one-percentage-point band, so 9% to 11%
     * lands in review.
     *
     * <p>A policy input carried here rather than a request field, for the same reason
     * {@code EirService}'s fee rule set is: the threshold and the band are an accounting policy a
     * bank approves once, and a caller who could send their own band could move a restructuring out
     * of review by choosing a narrower one.
     */
    private static final ReviewBand BAND = ReviewBand.standard();

    private EventRouting() {
    }

    /** What one submission produced: the response, and the log row where a version was created. */
    public record Routed(Json.Obj response, RecordedEvent recorded) {

        public Routed {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Routes the submission against the holding, through the approved table in force on the event's
     * own date.
     *
     * @throws FormBody.BadRequest where the submission is incomplete for the mechanism it routes to
     */
    public static Routed route(
        Book.Holding holding, RoutingTableRegistry registry, EventSubmission submission) {

        Objects.requireNonNull(holding, "holding");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(submission, "submission");

        ContractStateSource.OpeningState state = holding.state();
        ContractTerms terms = state.terms();
        Rate eirBefore = state.eir();
        Money gcaBefore = state.openingGca();
        TimeConvention convention = new TimeConvention.PeriodicIndex(eirBefore.periodsPerYear());

        // Assembled through PeriodEvent rather than validated again here. Its constructor is the
        // engine's own statement of what an event must carry — a driver, a non-empty revised vector,
        // and an anchor on the event date — and re-implementing those three checks at the edge would
        // give the API a second opinion that can drift from the one the run enforces.
        FlowVector revised = submission.revisedFlows();
        try {
            PeriodEvent.of(submission.driver(), submission.eventDate(), revised);
        } catch (IllegalArgumentException malformed) {
            throw new FormBody.BadRequest(malformed.getMessage());
        }

        RoutingDecision decision;
        try {
            decision = registry.route(
                submission.driver(), terms.rateType(), submission.eventDate());
        } catch (RoutingTableUnavailableException noTable) {
            // A refusal, not a defect: the registry declines to substitute its compiled-in baseline
            // for a date no approved table governs, and an event dated outside the approved series
            // is a real condition an operator must see rather than a stack trace. 200, because the
            // engine answered — it answered "no approved reading governs that date".
            return new Routed(base(holding, submission, eirBefore, gcaBefore, convention)
                .bool("routed", false)
                .str("refusal", noTable.getMessage())
                .str("detail", "the routing table version is selected on the EVENT's date, never on"
                    + " today's, which is what makes a closed period replay under the reading it"
                    + " closed under (ADR-0006). No approved table governs "
                    + submission.eventDate() + ", so nothing was routed and no figure was"
                    + " published."), null);
        }

        Json.Obj response = base(holding, submission, eirBefore, gcaBefore, convention)
            .bool("routed", true)
            .str("routedMechanism", decision.mechanism().name())
            .str("routingTableVersionId", decision.routingTableVersionId())
            .bool("overriddenByRateTypeCheck", decision.overriddenByRateTypeCheck())
            .str("routingRationale", decision.rationale());

        return switch (decision.mechanism()) {
            case RESET -> reset(response, holding, submission, decision, eirBefore, gcaBefore,
                convention);
            case CATCH_UP -> catchUp(response, holding, submission, decision, eirBefore, gcaBefore,
                convention);
            case MODIFICATION_TEST -> modificationTest(response, holding, submission, decision,
                terms, eirBefore, gcaBefore, convention);
            case NONE -> none(response, holding, submission, decision, eirBefore, gcaBefore);
            case DERECOGNITION -> new Routed(response
                .bool("accepted", false)
                .str("refusal", "routed to " + Mechanism.DERECOGNITION + ", which no approved table"
                    + " may name — Mechanism.isRoutable() refuses it. Derecognition is reachable"
                    + " only as a substantiality CONCLUSION, per instrument and from evidence, never"
                    + " as a routing position a bank takes in advance."), null);
        };
    }

    // ---- B5.4.5, the reset -----------------------------------------------------------------

    private static Routed reset(
        Json.Obj response, Book.Holding holding, EventSubmission submission,
        RoutingDecision decision, Rate eirBefore, Money gcaBefore, TimeConvention convention) {

        // Seeded from the rate in force, not from the contractual rate: mid-life the contractual
        // rate is the wrong starting point and, under actual dating, the wrong units.
        SolveResult solved = new BracketedNewtonSolver().solve(SolveRequest.of(
            submission.revisedFlows(), gcaBefore, convention, eirBefore.periodic()));

        response
            .str("solveStatus", solved.status().name())
            .count("solveIterations", solved.iterations())
            .str("solveDiagnostic", solved.diagnostic());

        if (!solved.hasRate()) {
            // No fallback — not to zero, not to the contractual rate, not to the rate in force.
            // 03 § 4.3 names that the most damaging failure available to this engine, because it
            // publishes a plausible figure and leaves no trace that the reset never happened.
            response
                .bool("solved", false)
                .figure("newEir", null)
                .str("refusal", "the reset re-solve returned " + solved.status()
                    + " and no rate was published. Falling back to the rate in force would restate"
                    + " nothing and report a plausible EIR with nothing recording that the reset"
                    + " did not happen (03 § 4.3).");
            // No version is opened, so the response reads accepted:false. A RecordedEvent here
            // would put a boundary in the version history carrying a null rate and a null balance
            // with pendingApproval false — the "assessed event that changed nothing" shape
            // RecordedEvent's own constructor refuses for the pending case, arriving through the
            // one branch that does not go through that guard. A refused reset changed nothing and
            // the history must say nothing.
            return new Routed(response, null);
        }

        Rate candidate = solved.rate();
        response
            .figure("residualAtStoredRate", solved.residualAtStoredRate())
            // A reset re-prices the rate and leaves the balance where it is. Both figures are
            // published so that "the carrying amount did not move" is a statement a reader can
            // check rather than a claim the response makes about itself.
            .figure("carryingAmountAfter", gcaBefore.atPresentationScale().amount())
            .figure("catchUpAmount", Money.zero(gcaBefore.currency()).atPresentationScale().amount())
            .bool("eirUnchanged", false);

        if (solved.status().requiresApproval()) {
            // A rate the solver flagged is NOT the contract's new EIR, and publishing it as one is
            // the quiet half of 03 § 4.4(2). REQUIRES_REVIEW carries a usable figure and says it is
            // "flagged rather than published": the root sits outside the plausible band, or was
            // reached only by escalating the ladder, which the solver's own diagnostic reads as
            // either a very short tenor carrying a fee it cannot amortise or a recovery so far
            // below the advance that the answer is impairment and not interest. Both are approval
            // questions, and an engine that writes such a rate into newEir has answered one.
            //
            // Found by a test that meant to provoke NO_SOLUTION and got this instead — the module
            // published -0.999998107521 as the new EIR of a performing housing loan, on a 200, with
            // nothing in the response but a diagnostic to say it had been flagged.
            response
                .bool("solved", false)
                .bool("requiresApproval", true)
                .figure("newEir", null)
                .figure("candidateEir", candidate.periodic())
                .figure("candidateEirEffectiveAnnual", candidate.effectiveAnnual())
                .str("refusal", "the reset re-solve returned " + solved.status()
                    + ": the rate is computed and usable but flagged for approval, so it is reported"
                    + " as candidateEir and is not the contract's EIR. Writing a flagged rate into"
                    + " newEir would settle an approval question by publishing the figure"
                    + " (03 § 4.4(2)).")
                .str("basis", "IFRS 9 B5.4.5: the re-solve produced one root and the solver flagged"
                    + " it. No rate is published and no position is recorded until it is approved.");
            return new Routed(response, new RecordedEvent(
                RecordedEvent.idFor(holding.contractId(), submission.eventDate(),
                    submission.driver()),
                holding.contractId(), submission.eventDate(), submission.driver(),
                decision.mechanism(), decision.routingTableVersionId(), null, null, true,
                "B5.4.5 reset, driver " + submission.driver() + ": the re-solve returned "
                    + solved.status() + " and the candidate rate "
                    + candidate.periodic().toPlainString() + " awaits approval"));
        }

        response
            .bool("solved", true)
            .bool("requiresApproval", false)
            .figure("newEir", candidate.periodic())
            .figure("newEirEffectiveAnnual", candidate.effectiveAnnual())
            .str("basis", "IFRS 9 B5.4.5: re-solved over the revised remaining flows from the"
                + " carrying amount in force. The rate moves, the balance does not, and there is no"
                + " catch-up.");

        return new Routed(response, new RecordedEvent(
            RecordedEvent.idFor(holding.contractId(), submission.eventDate(), submission.driver()),
            holding.contractId(), submission.eventDate(), submission.driver(),
            decision.mechanism(), decision.routingTableVersionId(), candidate, gcaBefore, false,
            "B5.4.5 reset, driver " + submission.driver() + ", routing table version "
                + decision.routingTableVersionId()));
    }

    // ---- B5.4.6, the catch-up --------------------------------------------------------------

    /**
     * Restates the balance at the rate that does not move, and reports CU-1 as evidence that it
     * did not.
     *
     * <p><b>{@code eirUnchanged} is read off the invariant, not written as a constant.</b> The rate
     * handed to {@link CatchUpCalculator} as "the rate persisted after the event" is the local
     * {@code eirAfter}, which this branch sets — and it is the same variable the {@link #reset}
     * branch sets to a re-solved rate. So CU-1 compares two values that a change to this class
     * could genuinely make differ: make this branch re-solve, or take the rate from the solver, and
     * CU-1 goes red and {@code eirUnchanged} comes back false. A hardcoded {@code true} would be a
     * control that cannot fail, which is worse than an absent one — it reads exactly like evidence
     * and is not.
     */
    private static Routed catchUp(
        Json.Obj response, Book.Holding holding, EventSubmission submission,
        RoutingDecision decision, Rate eirBefore, Money gcaBefore, TimeConvention convention) {

        Rate eirAfter = eirBefore;
        CatchUpResult restatement = CatchUpCalculator.restate(
            eirBefore, eirAfter, gcaBefore, submission.revisedFlows(), convention);

        InvariantResult cuOne = invariant(restatement.invariants(), InvariantId.CU_1);
        response
            .figure("eirAfter", restatement.eirAfter().periodic())
            .bool("eirUnchanged", cuOne.satisfied())
            .figure("restatedCarryingAmount", restatement.restatedGca().atPresentationScale().amount())
            .figure("carryingAmountAfter", restatement.restatedGca().atPresentationScale().amount())
            .figure("catchUpAmount", restatement.presentedCatchUp().amount())
            .bool("catchUpIsCharge", restatement.isCharge())
            .bool("invariantsClean", restatement.isClean())
            .array("invariants", invariantRows(restatement.invariants()))
            .str("basis", "IFRS 9 B5.4.6: the original EIR is retained, the gross carrying amount is"
                + " restated to the present value of the revised flows at that rate, and the"
                + " difference is recognised immediately. eirUnchanged is invariant CU-1's own"
                + " result, not an assertion this response makes about itself.");

        return new Routed(response, new RecordedEvent(
            RecordedEvent.idFor(holding.contractId(), submission.eventDate(), submission.driver()),
            holding.contractId(), submission.eventDate(), submission.driver(),
            decision.mechanism(), decision.routingTableVersionId(), restatement.eirAfter(),
            restatement.restatedGca(), false,
            "IFRS 9 B5.4.6 catch-up, driver " + submission.driver() + ", routing table version "
                + decision.routingTableVersionId() + "; catch-up "
                + restatement.presentedCatchUp()));
    }

    // ---- 5.4.3, the substantiality assessment the engine does not decide -------------------

    /**
     * The 10% test and the qualitative triggers as <b>evidence</b>, and no conclusion where the
     * evidence does not settle it (FR-511).
     *
     * <p>Both present values are published, not just the ratio, for the reason
     * {@code ModificationTestResult} gives: the question an auditor asks about a 10.4% result is
     * which leg moved and by how much, and reconstructing that from the ratio means re-deriving the
     * projection. {@code originalLegSource} is published for a related reason — the ratio's
     * denominator is the counterfactual, and whether it came from the contract's own schedule or
     * from the caller is exactly the provenance a reviewer needs.
     */
    private static Routed modificationTest(
        Json.Obj response, Book.Holding holding, EventSubmission submission,
        RoutingDecision decision, ContractTerms terms, Rate eirBefore, Money gcaBefore,
        TimeConvention convention) {

        submission.requireQualitativeAssessment();
        FlowVector remaining = submission.suppliedOriginalFlows() != null
            ? submission.suppliedOriginalFlows()
            : remainingContractualLeg(terms, submission.eventDate());

        ModificationTestResult test;
        try {
            test = ModificationTest.evaluate(
                eirBefore.periodic(), remaining, submission.revisedFlows(), convention,
                submission.side(), BAND, submission.triggers());
        } catch (IllegalArgumentException unusable) {
            // The caller's two legs, not an engine defect: a zero-present-value denominator, a
            // currency mismatch, an anchor mismatch. 400 with the domain's own message.
            throw new FormBody.BadRequest(
                "the 10% test could not be evaluated on the vectors supplied: "
                    + unusable.getMessage());
        }

        List<String> triggerNames = new ArrayList<>(test.triggersFired().size());
        for (QualitativeTrigger trigger : test.triggersFired()) {
            triggerNames.add(trigger.name());
        }

        boolean pending = test.requiresApproval();
        response
            .obj("tenPercentTest", Json.object()
                .str("side", test.side().name())
                .bool("quantitativeTestIsAuthoritative", test.side().quantitativeTestIsAuthoritative())
                .figure("ratio", test.ratio())
                .figure("threshold", test.reviewBand().threshold())
                .figure("reviewBandLowerBound", test.reviewBand().lowerBound())
                .figure("reviewBandUpperBound", test.reviewBand().upperBound())
                .bool("breachesThreshold", test.breachesThreshold())
                .bool("withinReviewBand", test.withinReviewBand())
                .figure("distanceFromThreshold", test.distanceFromThreshold())
                .figure("presentValueRemaining",
                    test.presentValueRemaining().atPresentationScale().amount())
                .figure("presentValueRevised",
                    test.presentValueRevised().atPresentationScale().amount())
                .figure("presentValueDifference",
                    test.presentValueDifference().atPresentationScale().amount())
                .count("originalFlowCount", remaining.future().size())
                .count("revisedFlowCount", submission.revisedFlows().future().size())
                .str("originalLegSource", submission.suppliedOriginalFlows() != null
                    ? "SUPPLIED" : "CONTRACT_SCHEDULE"))
            .strings("qualitativeTriggers", triggerNames)
            .bool("anyQualitativeTriggerFired", test.anyTriggerFired())
            .bool("qualitativeAssessmentPerformed", true)
            // PENDING_APPROVAL rather than REQUIRES_APPROVAL: 06 § 3 names the wire value, and the
            // conclusion enum's own name belongs to the domain.
            .str("substantialityConclusion",
                pending ? "PENDING_APPROVAL" : test.conclusion().name())
            .bool("engineDecided", test.conclusion().isDecided())
            .bool("catchUpApplied", false)
            .figure("catchUpAmount", null)
            .figure("newEir", null)
            // Null whatever the conclusion, because nothing is applied here. Publishing the
            // pre-event balance on a SUBSTANTIAL conclusion said the carrying amount was unchanged
            // for an assessment whose implied mechanism is derecognition — the asset leaving the
            // book — and on NOT_SUBSTANTIAL it said the same for a catch-up that will move it. It
            // also disagreed with the RecordedEvent for the identical event, which stores null.
            .figure("carryingAmountAfter", null)
            .str("carryingAmountAfterNote", "null because no mechanism is applied here: the"
                + " conclusion's mechanism runs in the month-end roll-forward, which is the only"
                + " place the roll-forward invariants are asserted over it. carryingAmountBefore is"
                + " the balance the assessment measured from.")
            .str("basis", pending
                ? "IFRS 9 5.4.3: the 10% test and the qualitative triggers are recorded as EVIDENCE"
                    + " and the engine declines to conclude (FR-511). No rate and no restated"
                    + " balance were produced, because guessing between a catch-up and a"
                    + " derecognition is a judgement taken by an implementation detail on figures"
                    + " that differ by the whole balance. A person decides, and who decided, on what"
                    + " basis and under which policy version is recorded."
                : "IFRS 9 5.4.3: the assessment concluded " + test.conclusion()
                    + ", which implies " + test.conclusion().mechanism()
                    + ". Nothing is applied here — the mechanism runs in the month-end roll-forward,"
                    + " which is the only place the roll-forward invariants are asserted over it.");

        return new Routed(response, new RecordedEvent(
            RecordedEvent.idFor(holding.contractId(), submission.eventDate(), submission.driver()),
            holding.contractId(), submission.eventDate(), submission.driver(),
            decision.mechanism(), decision.routingTableVersionId(), null, null, pending,
            "IFRS 9 5.4.3 substantiality assessment, driver " + submission.driver()
                + ", ratio " + test.ratio().toPlainString() + ", conclusion "
                + (pending ? "PENDING_APPROVAL" : test.conclusion().name())));
    }

    // ---- an elected immateriality ----------------------------------------------------------

    private static Routed none(
        Json.Obj response, Book.Holding holding, EventSubmission submission,
        RoutingDecision decision, Rate eirBefore, Money gcaBefore) {

        response
            .figure("eirAfter", eirBefore.periodic())
            .bool("eirUnchanged", true)
            .figure("carryingAmountAfter", gcaBefore.atPresentationScale().amount())
            .figure("catchUpAmount", Money.zero(gcaBefore.currency()).atPresentationScale().amount())
            .str("basis", "Mechanism.NONE: no EIR consequence. The approved table records this"
                + " driver as an immateriality election the bank is entitled to take, so the period"
                + " rolls forward at the unchanged rate and nothing is restated.");

        return new Routed(response, new RecordedEvent(
            RecordedEvent.idFor(holding.contractId(), submission.eventDate(), submission.driver()),
            holding.contractId(), submission.eventDate(), submission.driver(),
            decision.mechanism(), decision.routingTableVersionId(), eirBefore, gcaBefore, false,
            "no EIR consequence, driver " + submission.driver() + " under routing table version "
                + decision.routingTableVersionId()));
    }

    // ---- shared ----------------------------------------------------------------------------

    private static Json.Obj base(
        Book.Holding holding, EventSubmission submission, Rate eirBefore, Money gcaBefore,
        TimeConvention convention) {

        ContractTerms terms = holding.state().terms();
        LocalDate eventDate = submission.eventDate();
        boolean onBoundary = onScheduleBoundary(terms, eventDate);
        Json.Obj response = Json.object()
            .str("eventId", RecordedEvent.idFor(
                holding.contractId(), eventDate, submission.driver()))
            .str("contractId", holding.contractId())
            .str("eventDate", eventDate.toString())
            .str("driver", submission.driver().name())
            .bool("driverIsMarketMovement", submission.driver().isMarketMovement())
            .str("rateType", terms.rateType().name())
            .str("timeConvention", convention.label())
            .figure("eirBefore", eirBefore.periodic())
            .figure("carryingAmountBefore", gcaBefore.atPresentationScale().amount())
            .count("revisedFlowCount", submission.revisedFlows().future().size())
            .bool("eventDateOnScheduleBoundary", onBoundary);
        if (!onBoundary) {
            // Reported rather than refused, in the style this engine reports every other
            // approximation (FR-809). Under PeriodicIndex tau comes straight from the flow's
            // ordinal, so a flow one period after an off-boundary event date declares a whole
            // period for a part-period gap. Actual dating would be exact; this book's contracts are
            // periodic-indexed, and stating the approximation is more use than hiding it.
            response.str("eventDateCaveat", "the event date " + eventDate + " is not a scheduled due"
                + " date of this contract. Under " + convention.label() + " the discount exponent is"
                + " the flow's period ordinal, so the first revised flow is discounted a whole"
                + " period from the event date whatever the actual gap. An actual-dated convention"
                + " would measure the gap in days.");
        }
        return response;
    }

    /**
     * The flows the original terms would still have produced, from the contract's own schedule.
     *
     * <p>Derived rather than asked for: the counterfactual leg of the 10% test is a fact about the
     * instrument, not an assertion the caller is entitled to make, and a caller who could state the
     * denominator could move any ratio across the threshold. It is re-projected through the same
     * {@code AnnuityProjector} the pipeline uses — including its residue policy, so the terminal
     * instalment is the one the contract actually bills — and then re-anchored on the event date
     * with fresh ordinals, because both legs of the test must be struck at the same date.
     *
     * @throws FormBody.BadRequest where the schedule shape is one this derivation cannot honour, in
     *     which case the caller must supply the leg explicitly
     */
    private static FlowVector remainingContractualLeg(ContractTerms terms, LocalDate eventDate) {
        AnnuityProjector projector = new AnnuityProjector();
        if (!projector.supports(terms)) {
            throw new FormBody.BadRequest(
                "the remaining original leg cannot be derived for a " + terms.shape()
                    + " schedule" + (terms.hasMoratorium() ? " with a moratorium" : "")
                    + ", so send it explicitly as 'originalFlows=YYYY-MM-DD:amount;...'."
                    + " Deriving it from an annuity this contract is not would put a counterfactual"
                    + " nobody projected into the denominator of the 10% test.");
        }
        List<CashFlow> remaining = new ArrayList<>();
        int ordinal = 0;
        for (CashFlow flow : projector.project(terms, List.of()).contractual().flows()) {
            if (flow.date().isAfter(eventDate)) {
                remaining.add(CashFlow.of(flow.date(), ++ordinal, flow.amount(), flow.kind()));
            }
        }
        if (remaining.isEmpty()) {
            throw new FormBody.BadRequest(
                "the contract has no scheduled flows after " + eventDate
                    + ", so the remaining original leg has zero present value and the 10% test is"
                    + " undefined; a fully repaid exposure is not a modification");
        }
        return FlowVector.of(eventDate, terms.currency(), remaining);
    }

    /**
     * One named invariant's result, by identifier rather than by list position.
     *
     * <p>By id because the position is not part of {@code CatchUpCalculator}'s contract: reading
     * CU-1 off index 0 would keep compiling and start reporting CU-2's result as
     * {@code eirUnchanged} the day a third assertion was prepended, and CU-2 is a claim about the
     * balances that holds in exactly the case CU-1 fails.
     */
    private static InvariantResult invariant(List<InvariantResult> results, InvariantId id) {
        for (InvariantResult result : results) {
            if (result.id() == id) {
                return result;
            }
        }
        throw new IllegalStateException(
            "the restatement published no " + id.name().replace('_', '-') + " result;"
                + " CatchUpCalculator asserts it on every call, so its absence is a defect here or"
                + " there and not a fact about this contract");
    }

    private static List<Json.Obj> invariantRows(List<InvariantResult> results) {
        List<Json.Obj> rows = new ArrayList<>(results.size());
        for (InvariantResult result : results) {
            rows.add(Json.object()
                .str("id", result.id().name().replace('_', '-'))
                .str("statement", result.id().statement())
                .bool("satisfied", result.satisfied())
                .figure("deviation", result.deviation())
                .str("detail", result.detail()));
        }
        return rows;
    }

    /** Whether the event lands on one of the contract's own scheduled due dates. */
    private static boolean onScheduleBoundary(ContractTerms terms, LocalDate eventDate) {
        for (int ordinal = 0; ordinal <= terms.termPeriods(); ordinal++) {
            if (terms.dueDate(ordinal).equals(eventDate)) {
                return true;
            }
        }
        return false;
    }
}
