package com.crisil.eir.application.onboarding;

import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.fee.rule.FeeClassificationResolution;
import com.crisil.eir.policy.tier.TierAssignmentResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one contract produced at initial recognition, and — the part that matters — <b>which stages
 * of 05 § 3.1 it actually consumed</b>.
 *
 * <h2>Why the work record is a field and not a log line</h2>
 *
 * <p>05 § 3.1 requires the measurement gate to run before any projection or solve, and says why:
 * "Doing expensive work before that check is waste, and worse, produces a rate for an instrument
 * that should not have one." A comment claiming the gate runs first is not a control — nothing about
 * it can fail. {@link #workPerformed()} is the same claim made checkable: it lists the stages this
 * contract went through, in order, and
 *
 * <ul>
 *   <li>the constructor refuses a list whose first stage is not
 *       {@link OnboardingWork#MEASUREMENT_GATE}, or whose stages are out of 05 § 3.1's order,
 *       because a pipeline that projects before it gates is a defect in this module rather than a
 *       fact about a contract;
 *   <li>the ST-12 work leg computed here <em>reports</em> a projection or a solve performed for a
 *       contract the gate did not license, and is deliberately not refused at construction — a
 *       control whose breach cannot be constructed cannot be tested, and this codebase has shipped
 *       several of those.
 * </ul>
 *
 * <p>Both matter, and they are not redundant. The refusal catches a mis-ordered pipeline in the one
 * place a mis-ordering can be seen structurally; the invariant catches a pipeline that ran the
 * stages in the right order and gated on the wrong answer.
 *
 * <h2>The invariants are computed here, not supplied</h2>
 *
 * <p>Same discipline and same reason as {@link ProjectionResult}, whose javadoc says "IC-1 is
 * computed here, not supplied … Computing it centrally means no projector can omit it, and any IC-1
 * result passed in is discarded in favour of the freshly computed one." Whatever a caller passes for
 * {@code invariants} is discarded: ST-12 is recomputed from the decision and the work record, and
 * IC-1 is read off the projection that this outcome carries. A caller cannot publish a pass it did
 * not earn, and cannot omit a breach.
 *
 * @param contractId      the contract
 * @param decision        what the gate concluded, which is present on every outcome because the
 *                        gate runs on every contract
 * @param feeResolutions  one resolution per submitted posting, in submission order; empty where the
 *                        contract never reached fee classification
 * @param tier            the materiality tier and its recorded basis (FR-107), or null where the
 *                        contract never reached the tier gate
 * @param projection      both legs and the IC-1 result, or null where no projection was run
 * @param solve           the rate, residual, method and candidate roots, or null where no solve was
 *                        run
 * @param workPerformed   the stages consumed, in 05 § 3.1 order, starting at the gate
 * @param invariants      <b>computed</b>: one result per id, ST-12 and IC-1 where each has evidence.
 *                        Any value supplied is discarded
 * @param exception       the queue entry, or null where the contract was not quarantined
 */
public record OnboardingOutcome(
    String contractId,
    MeasurementDecision decision,
    List<FeeClassificationResolution> feeResolutions,
    TierAssignmentResult tier,
    ProjectionResult projection,
    SolveResult solve,
    List<OnboardingWork> workPerformed,
    List<InvariantResult> invariants,
    ExceptionRecord exception) {

    public OnboardingOutcome {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(decision, "decision");
        feeResolutions = List.copyOf(Objects.requireNonNull(feeResolutions, "feeResolutions"));
        workPerformed = List.copyOf(Objects.requireNonNull(workPerformed, "workPerformed"));
        if (!contractId.equals(decision.contractId())) {
            throw new IllegalArgumentException(
                "outcome names contract " + contractId + " and carries a gate decision for "
                    + decision.contractId() + "; a decision filed against the wrong contract"
                    + " excludes the wrong instrument from the EIR regime");
        }
        requireOrderedFromTheGate(contractId, workPerformed);
        if (decision.isRefused() && exception == null) {
            // The gate refused and nothing was filed. That is the FR-905 failure mode
            // ContractResult's javadoc names from the other direction: the contract is neither
            // computed nor quarantined, so it is absent from both sides of every total.
            throw new IllegalArgumentException(
                "the gate refused contract " + contractId + " under " + decision.refusal()
                    + " and the outcome carries no exception record; a refusal nobody filed is a"
                    + " contract dropped from the population (FR-905)");
        }
        if (exception == null && decision.eirApplies() && (solve == null || !solve.hasRate())) {
            throw new IllegalArgumentException(
                "contract " + contractId + " was admitted to EIR processing and carries neither a"
                    + " rate nor an exception; 03 § 4.3 forbids a silent fallback, so a solve that"
                    + " produced nothing has to reach the queue rather than leaving the contract"
                    + " with no disposition at all");
        }
        if (exception != null && !contractId.equals(exception.contractId())) {
            throw new IllegalArgumentException(
                "outcome names contract " + contractId + " and carries an exception filed against "
                    + exception.contractId());
        }
        invariants = computeInvariants(decision, projection, solve, workPerformed);
    }

    /**
     * The stages must start at the gate and run in 05 § 3.1's order, each at most once.
     *
     * <p>Refused rather than reported because every branch of this module's own pipeline is under
     * its own control: a mis-ordered work record is not a malformed contract arriving from a feed,
     * it is {@link InitialRecognition} calling stages in the wrong sequence, and FR-905's barrier
     * exists for the former. Filing an engine defect as a per-contract exception would put one queue
     * entry per contract in a ten-million-contract run and hide the single cause behind ten million
     * symptoms.
     */
    private static void requireOrderedFromTheGate(String contractId, List<OnboardingWork> work) {
        if (work.isEmpty()) {
            throw new IllegalArgumentException(
                "contract " + contractId + " records no work at all; the measurement gate runs on"
                    + " every contract (05 § 3.1), so an empty work record is a contract that was"
                    + " never gated");
        }
        if (work.get(0) != OnboardingWork.MEASUREMENT_GATE) {
            throw new IllegalArgumentException(
                "contract " + contractId + " records " + work.get(0) + " as its first stage."
                    + " 05 § 3.1: the SPPI/measurement gate runs FIRST, before any projection or"
                    + " solve, because doing expensive work before that check produces a rate for"
                    + " an instrument that should not have one. Work recorded: " + work);
        }
        for (int i = 1; i < work.size(); i++) {
            if (!work.get(i - 1).precedes(work.get(i))) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " records " + work.get(i) + " after "
                        + work.get(i - 1) + "; 05 § 3.1's stages run in declaration order, each at"
                        + " most once. Work recorded: " + work);
            }
        }
    }

    /**
     * ST-12 and IC-1, one result each, published only where there is evidence behind them.
     *
     * <h3>ST-12 — "SPPI failure yields no EIR"</h3>
     *
     * <p>Two legs, summed into one result rather than conjoined, and the difference matters:
     * {@link InvariantResult#conjunction} keeps only the <em>first</em> breach's deviation among
     * results sharing an id, so two legs published under one id would report one number and silently
     * drop the other. The deviation here is the total count of breaches across both legs — a count,
     * absolute, so two breaches never net to a pass.
     *
     * <ol>
     *   <li><b>Classification leg</b>, from the gate. WHAT INPUT MAKES IT FAIL: an asset-side
     *       instrument assessed {@code FAIL} whose contract master declares amortised cost or FVOCI.
     *   <li><b>Work leg</b>, from this outcome. WHAT INPUT MAKES IT FAIL: an outcome for a contract
     *       the gate did not license whose work record shows {@link OnboardingWork#PROJECTION} or
     *       {@link OnboardingWork#SOLVE}, or which carries a projection or a solve artefact. That is
     *       the pipeline having gated too late, and it is the one condition this record does
     *       <em>not</em> refuse at construction, because a control that cannot be reached cannot be
     *       tested.
     * </ol>
     *
     * <p><b>The one place an id is asked to carry slightly more than its statement.</b> ST-12 is
     * worded "A conversion option, or any SPPI failure, yields no EIR at all", and the work leg is
     * applied to every contract the gate excluded — including one at FVTPL on the business-model
     * test, where no SPPI failure occurred. FR-103 states the obligation over that whole population
     * ("an instrument at FVTPL carries no EIR and must be excluded from EIR processing entirely"),
     * and ST-12 is the only id in {@link InvariantId} that asserts "yields no EIR". The alternative
     * was a new id, which is not this unit's to add.
     *
     * <p>ST-12 is <b>not published</b> where the gate had no SPPI outcome to examine and did license
     * the EIR — a liability, which FR-104 puts outside the test altogether. An absent result is
     * reported by {@link OnboardingRun#unassertedInvariants()}; a satisfied one would be a result no
     * input could turn into a breach.
     *
     * <h3>IC-1 — "GCA0 = net cash flow at inception"</h3>
     *
     * <p>Read off the projection, which computes it from two independent routes: the carrying amount
     * the projector derived from the contract terms against the net cash flow at inception in the
     * vector it built. WHAT INPUT MAKES IT FAIL: a fee misclassified into or out of the carrying
     * amount, or a non-cash item in the vector. On reference case 1 the two routes agree at
     * 995,000.00 — 1,000,000 advanced less 15,000 received plus 10,000 paid — and a single
     * misclassified 15,000 fee moves one route and not the other.
     *
     * <p>Absent where no projection ran, which is every excluded and every early-quarantined
     * contract. Not published as a pass in that case: a contract that was never projected has no
     * initial carrying amount for IC-1 to be about.
     */
    private static List<InvariantResult> computeInvariants(
        MeasurementDecision decision, ProjectionResult projection, SolveResult solve,
        List<OnboardingWork> workPerformed) {

        List<InvariantResult> assembled = new ArrayList<>(2);
        InvariantResult sppi = sppiExclusionResult(decision, projection, solve, workPerformed);
        if (sppi != null) {
            assembled.add(sppi);
        }
        if (projection != null) {
            assembled.add(projection.initialRecognitionCheck());
        }
        return List.copyOf(assembled);
    }

    /** ST-12 for one contract, or null where the invariant has no subject. */
    private static InvariantResult sppiExclusionResult(
        MeasurementDecision decision, ProjectionResult projection, SolveResult solve,
        List<OnboardingWork> workPerformed) {

        InvariantResult classification = decision.classificationCheck();
        boolean licensed = decision.eirApplies();
        if (classification == null && licensed) {
            return null;
        }
        List<String> details = new ArrayList<>(2);
        BigDecimal breaches = BigDecimal.ZERO;
        if (classification != null) {
            details.add(classification.detail());
            if (!classification.satisfied()) {
                breaches = breaches.add(BigDecimal.ONE);
            }
        }
        if (!licensed) {
            EnumSet<OnboardingWork> forbidden = EnumSet.noneOf(OnboardingWork.class);
            for (OnboardingWork stage : workPerformed) {
                if (stage.isExpensive()) {
                    forbidden.add(stage);
                }
            }
            if (projection != null) {
                forbidden.add(OnboardingWork.PROJECTION);
            }
            if (solve != null) {
                forbidden.add(OnboardingWork.SOLVE);
            }
            if (forbidden.isEmpty()) {
                details.add("contract " + decision.contractId() + " carries no EIR ("
                    + decision.detail() + ") and no projection or solve was performed: work"
                    + " recorded " + workPerformed);
            } else {
                details.add("contract " + decision.contractId() + " carries no EIR ("
                    + decision.detail() + ") and " + forbidden.size()
                    + " expensive stage(s) ran for it anyway: " + forbidden + ", work recorded "
                    + workPerformed + ". 05 § 3.1: the gate runs FIRST, before any projection or"
                    + " solve, because doing expensive work before that check produces a rate for"
                    + " an instrument that should not have one");
                breaches = breaches.add(BigDecimal.valueOf(forbidden.size()));
            }
        }
        String detail = String.join("; ", details);
        return breaches.signum() == 0
            ? InvariantResult.pass(InvariantId.ST_12, detail)
            : InvariantResult.fail(InvariantId.ST_12, detail, breaches);
    }

    /** A recognised contract: gated, classified, tiered, projected, solved. */
    public static OnboardingOutcome recognised(
        String contractId, MeasurementDecision decision,
        List<FeeClassificationResolution> feeResolutions, TierAssignmentResult tier,
        ProjectionResult projection, SolveResult solve, List<OnboardingWork> workPerformed) {

        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(solve, "solve");
        return new OnboardingOutcome(contractId, decision, feeResolutions, tier, projection, solve,
            workPerformed, List.of(), null);
    }

    /**
     * A contract recorded and excluded from EIR processing (FR-103).
     *
     * <p>No projection, no solve, no tier and no fee classification — none of them is licensed, and
     * none of them was run. The work record therefore reads {@code [MEASUREMENT_GATE]}, which is the
     * assertion the whole unit is built around.
     */
    public static OnboardingOutcome excluded(String contractId, MeasurementDecision decision) {
        if (decision.eirApplies()) {
            throw new IllegalArgumentException(
                "contract " + contractId + " was gated to " + decision.effectiveCategory()
                    + ", which carries an EIR, and cannot be recorded as excluded");
        }
        return new OnboardingOutcome(contractId, decision, List.of(), null, null, null,
            List.of(OnboardingWork.MEASUREMENT_GATE), List.of(), null);
    }

    /** A contract the barrier or a stage quarantined, with whatever work it had reached. */
    public static OnboardingOutcome quarantined(
        String contractId, MeasurementDecision decision,
        List<FeeClassificationResolution> feeResolutions, TierAssignmentResult tier,
        ProjectionResult projection, SolveResult solve, List<OnboardingWork> workPerformed,
        ExceptionRecord exception) {

        Objects.requireNonNull(exception, "exception");
        return new OnboardingOutcome(contractId, decision, feeResolutions, tier, projection, solve,
            workPerformed, List.of(), exception);
    }

    /** Which of the three answers this contract produced. */
    public OnboardingDisposition disposition() {
        if (exception != null) {
            return OnboardingDisposition.QUARANTINED;
        }
        return decision.eirApplies()
            ? OnboardingDisposition.RECOGNISED
            : OnboardingDisposition.EXCLUDED_FROM_EIR;
    }

    /** The solved rate, or empty on an excluded or quarantined contract. */
    public Optional<Rate> eir() {
        return solve == null ? Optional.empty() : Optional.ofNullable(solve.rate());
    }

    /** GCA at initial recognition, or empty where nothing was projected. */
    public Optional<Money> initialCarryingAmount() {
        return projection == null
            ? Optional.empty()
            : Optional.of(projection.initialCarryingAmount());
    }

    /** Whether either expensive stage of 05 § 3.1 was consumed for this contract. */
    public boolean expensiveWorkPerformed() {
        for (OnboardingWork stage : workPerformed) {
            if (stage.isExpensive()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this contract reached {@code stage}. */
    public boolean performed(OnboardingWork stage) {
        return workPerformed.contains(Objects.requireNonNull(stage, "stage"));
    }

    /** The invariants that failed; empty on a clean contract. */
    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /** The queue entry, or empty where the contract was not quarantined. */
    public Optional<ExceptionRecord> failure() {
        return Optional.ofNullable(exception);
    }

    /** One audit line: the disposition, the gate's reasoning, and the rate if there is one. */
    public String describe() {
        StringBuilder line = new StringBuilder(contractId).append(": ").append(disposition());
        line.append(" — ").append(decision.detail());
        eir().ifPresent(rate -> line.append("; EIR ").append(rate));
        if (exception != null) {
            line.append("; ").append(exception.describe());
        }
        return line.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
