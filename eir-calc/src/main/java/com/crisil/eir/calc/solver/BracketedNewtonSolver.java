package com.crisil.eir.calc.solver;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Newton–Raphson with an analytic derivative, inside a guaranteed bracket, falling
 * back to bisection. The algorithm of calculation specification 4.2, in order.
 *
 * <p><strong>Why the bracket comes first.</strong> Unbracketed Newton–Raphson is a
 * fine root finder for well-behaved functions and the flow vectors in an Indian
 * bank's book are not reliably well-behaved: step-up and step-down EMIs, balloons,
 * moratoria with interest capitalisation, IDC on project finance, FITL creation on
 * restructuring, tranched disbursement against milestones. On those profiles a
 * tangent step can leave the region of interest entirely, and an unbracketed solver
 * either diverges or returns a root nobody wants. Establishing a bracket first
 * turns the whole solve into a bounded search, and bisection inside a bracket that
 * contains a sign change cannot fail to converge. Newton–Raphson is here for speed;
 * bisection is here for correctness, which is why the fallback is mandatory rather
 * than optional.
 *
 * <p><strong>The ladder is in the convention's own units.</strong> Under
 * {@code PeriodicIndex} the values are per-period, so the top of the ladder is 10.0
 * <em>per month</em> — economically absurd, and harmless: a bracket is not a
 * forecast, it only has to contain the root. The ladder carries no economic
 * judgement at all. That lives in {@link PlausibleBand}, which is applied to the
 * annualised root, where the units are unambiguous. Under {@code ActualDate} the
 * units are annual effective and tau is a year fraction, which makes the same ladder
 * reach very differently on a one-day drawing than on a twenty-year term loan — see
 * {@link #ESCALATION}, which closes that gap without charging the whole book for it.
 *
 * <p><strong>What counts as a root.</strong> An adjacent pair of ladder nodes with
 * opposite signs, or a node where {@code f} is exactly zero — the interest-free
 * loan solves to a rate of exactly 0, which is a ladder node, and a scan looking
 * only for crossings would miss it. A near-zero that never crosses is deliberately
 * <em>not</em> treated as a root: a repeated root touching the axis means the rate
 * is not determined by a sign change, and the honest answer for it is the exception
 * queue with the vector attached, not a rate inferred from a tangency.
 *
 * <p>Stateless and thread-safe; one instance can serve an entire close run.
 *
 * <p><strong>On performance</strong> (4.6). Discounting routes through
 * {@link Discounting} rather than an inlined loop, so the solve and the B5.4.6
 * catch-up restatement cannot drift apart in the last digits — a catch-up computed
 * with subtly different discounting from the solve that produced the rate is the
 * defect that quietly converts a B5.4.6 event into a B5.4.5 one. Inside a refinement
 * every present value is kept for as long as it can be used again, so no point is
 * discounted twice. The deliberate re-evaluations are all on failure paths, where an
 * exception-queue entry that shows its working is worth a few present values: the two
 * ends of the ladder when composing a no-solution diagnostic, the escalation nodes of
 * {@link #ESCALATION}, and the residual at a root that stopped on bracket width. On
 * the common path the fourteen-node ladder scan therefore costs more than the four
 * Newton iterations that follow it, and it is still scanned in full — see
 * {@link #scan}. The incremental discount-factor recurrence of 4.6 is deliberately
 * not taken: see the note on {@link Residual}.
 */
public final class BracketedNewtonSolver implements RateSolver {

    /**
     * The fixed bracket ladder of 4.2 step 1, ascending, in the convention's units.
     *
     * <p>Fixed rather than derived, and published rather than private, because a
     * bracket that varies with the vector makes two runs of the same contract
     * incomparable and a reviewer cannot check a ladder they cannot see.
     */
    public static final List<BigDecimal> LADDER = List.of(
        new BigDecimal("-0.9999"),
        new BigDecimal("-0.5"),
        new BigDecimal("-0.1"),
        new BigDecimal("0"),
        new BigDecimal("0.001"),
        new BigDecimal("0.01"),
        new BigDecimal("0.05"),
        new BigDecimal("0.1"),
        new BigDecimal("0.25"),
        new BigDecimal("0.5"),
        new BigDecimal("1.0"),
        new BigDecimal("2.0"),
        new BigDecimal("5.0"),
        new BigDecimal("10.0"));

    /**
     * Nodes consulted only when {@link #LADDER} finds no sign change at all.
     *
     * <p><strong>Why a fixed ladder is not enough on its own.</strong> Under
     * {@code ActualDate} the solved rate is annual effective and tau is a year
     * fraction, so a ladder node caps the <em>money multiple</em> the scan can
     * bracket at {@code (1+node)^tau} — and as tau falls that cap collapses toward 1.
     * The top node 10.0 brackets a multiple of 11 over a year, but only 1.006591 over
     * one day and 1.047061 over seven. A single-day money-market drawing of
     * 10,000,000 with a 100,000 integral fee and 6.75% ACT/365F contractual interest
     * repays 10,001,849.32 against a 9,900,000 outflow — a multiple of 1.010288,
     * which is a perfectly ordinary instrument and a mathematically unambiguous
     * annual effective rate of 4,092.4331%. It sits above the top of the ladder. The
     * same collapse happens downward: the bottom node -0.9999 brackets a loss of only
     * 2.49% over one day.
     *
     * <p>So the whole short-tenor population — which is exactly the Tier 3 population,
     * where a documented approximation is applied to instruments too small or too
     * short to solve individually (10) — would come back {@code NO_SOLUTION} for
     * vectors whose root exists, is unique, and is simply out of reach. That is the
     * defect this list closes.
     *
     * <p><strong>Why escalation rather than a longer ladder.</strong> The scan runs on
     * every solve and already costs more than the Newton iterations that follow it
     * (4.6), so nodes added to {@link #LADDER} are paid for on all ten million
     * contracts. These are paid for only on a vector the ladder failed, which is a
     * vector otherwise headed for the exception queue — where twenty extra present
     * values are free. Escalation is still fixed, ordered and published, so two runs
     * of the same contract remain comparable.
     *
     * <p>The extremes are set by what a stored rate can express, not by economics.
     * {@code -0.999999999999} is the closest node to -100% that survives rounding to
     * {@link Precision#RATE_SCALE} without becoming exactly -1, where the discount
     * factor is undefined; it brackets a 7.29% loss over one day. The top 1e12
     * brackets a 7.86% gain over one day. Nothing here is a forecast — a bracket only
     * has to contain the root, and economic judgement lives in {@link PlausibleBand},
     * applied to the annualised result.
     */
    public static final List<BigDecimal> ESCALATION = List.of(
        new BigDecimal("-0.999999999999"),
        new BigDecimal("-0.9999999"),
        new BigDecimal("-0.99999"),
        new BigDecimal("100"),
        new BigDecimal("10000"),
        new BigDecimal("1000000"),
        new BigDecimal("1000000000000"));

    /**
     * Below this the tangent is flat enough that {@code f/f'} is numerically
     * meaningless, so the step is rejected and the iteration bisects instead
     * (4.2 step 3).
     */
    private static final BigDecimal DERIVATIVE_FLOOR = BigDecimal.ONE.scaleByPowerOfTen(-20);

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** Enough digits to diagnose a failure, few enough to read. Trace only. */
    private static final MathContext TRACE = new MathContext(8, Precision.MODE);

    @Override
    public SolveResult solve(SolveRequest request) {
        Objects.requireNonNull(request, "request");
        List<CashFlow> future = request.flows().future();
        if (future.isEmpty()) {
            return SolveResult.noSolution(context(request)
                + ": no flow falls after the anchor " + request.flows().anchorDate()
                + ", so there is nothing to discount and no rate is determined. Exception queue (4.3).");
        }
        if (allZero(future)) {
            return SolveResult.noSolution(context(request)
                + ": every flow after the anchor " + request.flows().anchorDate()
                + " is zero, so f(r) is constant and no rate is determined. Exception queue (4.3).");
        }
        Residual residual = new Residual(request);
        BigDecimal toleranceAbsolute = request.absoluteTolerance();
        List<Bracket> brackets = scan(residual, LADDER);
        boolean escalated = false;
        if (brackets.isEmpty()) {
            brackets = scan(residual, extendedLadder());
            escalated = true;
            if (brackets.isEmpty()) {
                return SolveResult.noSolution(noSolutionDiagnostic(request, residual));
            }
        }
        if (brackets.size() == 1) {
            return single(request, residual, brackets.get(0), toleranceAbsolute, escalated);
        }
        return disambiguate(request, residual, brackets, toleranceAbsolute, escalated);
    }

    /**
     * {@link #LADDER} and {@link #ESCALATION} merged and sorted ascending.
     *
     * <p>Rebuilt rather than cached because it is reached only on the failure path,
     * and a mutable static that a hot path never reads is a worse trade than the
     * allocation.
     */
    private static List<BigDecimal> extendedLadder() {
        List<BigDecimal> nodes = new ArrayList<>(LADDER.size() + ESCALATION.size());
        nodes.addAll(LADDER);
        nodes.addAll(ESCALATION);
        nodes.sort(BigDecimal::compareTo);
        return nodes;
    }

    // ---------------------------------------------------------------- step 1

    /**
     * Scans the whole ladder, every time.
     *
     * <p>Stopping at the first sign change would be cheaper and would defeat the
     * purpose: multiple-root detection (4.4) exists precisely because a tranched
     * facility with large interim drawdowns has more than one mathematically valid
     * IRR, and a scan that stops early would return the first one it met without
     * ever knowing the others were there.
     */
    private static List<Bracket> scan(Residual residual, List<BigDecimal> ladder) {
        List<Bracket> brackets = new ArrayList<>();
        BigDecimal previousNode = null;
        BigDecimal previousValue = null;
        for (BigDecimal node : ladder) {
            BigDecimal value = residual.at(node);
            if (value.signum() == 0) {
                brackets.add(new Bracket(node, node, value, value));
            } else if (previousValue != null && previousValue.signum() != 0
                && previousValue.signum() != value.signum()) {
                brackets.add(new Bracket(previousNode, node, previousValue, value));
            }
            previousNode = node;
            previousValue = value;
        }
        return brackets;
    }

    // ------------------------------------------------------------ steps 2-4

    /**
     * Refines one bracket to a root: seeded Newton–Raphson with bisection
     * safeguards, then pure bisection if the Newton phase hits its cap.
     *
     * <p>The safeguards are the three of 4.2 step 3 — a tangent step that leaves the
     * bracket, a derivative below {@link #DERIVATIVE_FLOOR}, or a residual that grew
     * — and each of them replaces the step with a bisection of the current bracket
     * rather than abandoning the solve. Every accepted point then replaces the
     * bracket endpoint whose residual sign it shares, so the bracket only ever
     * shrinks and never loses its sign change. That invariant is what lets the
     * bisection fallback start from the narrowed bracket instead of the original one.
     *
     * <p><strong>Two different reasons to stop, and they are not the same claim.</strong>
     * Every phase terminates either because the residual came inside {@code tol_abs} or
     * because the bracket became narrower than {@code rateEpsilon}. The first says the
     * root was found; the second says only that no further iteration could move a rate
     * stored to twelve decimal places. Here, at 28 significant digits, {@code tol_abs} is
     * comfortably reachable and the residual test is normally what fires — three to seven
     * iterations on an ordinary instrument. It is step 5's rounding, not this loop, that
     * puts the <em>published</em> residual back outside {@code tol_abs}; see
     * {@link SolverTolerance#attainableResidual}.
     *
     * <p>Which test fired travels out on {@link Refinement#withinTolerance}, and it is
     * deliberately not on its own grounds to flag a solve — flagging every width exit was
     * tried and it flagged the whole long-tenor book, which
     * {@code SolverRoundTripPropertiesTest} caught. What {@link #finish} flags is a
     * residual outside the rounding floor as well, which a width exit on a
     * well-conditioned vector cannot produce and which therefore means the refinement did
     * not reach the root at all. Both facts travel so the diagnostic can say which
     * happened instead of leaving a reviewer to infer it.
     */
    private static Refinement refine(Residual residual, Bracket bracket, BigDecimal seed,
            SolverTolerance tolerance, BigDecimal toleranceAbsolute) {
        if (bracket.isDegenerate()) {
            // f is exactly zero at the node, so the residual test is satisfied outright.
            return new Refinement(bracket, bracket.low(), SolverMethod.NEWTON, 0, 0, true, false,
                true, bracket.valueLow());
        }
        BigDecimal settledEndpoint = bracket.endpointWithinTolerance(toleranceAbsolute);
        if (settledEndpoint != null) {
            BigDecimal settledValue = settledEndpoint.compareTo(bracket.low()) == 0
                ? bracket.valueLow() : bracket.valueHigh();
            // See Bracket.endpointWithinTolerance: the scan already found the root, and
            // iterating toward a root that sits on the bracket's own endpoint is this
            // algorithm's slowest path rather than its fastest.
            return new Refinement(bracket, settledEndpoint, SolverMethod.NEWTON, 0, 0, true, false,
                true, settledValue);
        }
        boolean seeded = seed != null;
        BigDecimal low = bracket.low();
        BigDecimal high = bracket.high();
        BigDecimal valueLow = bracket.valueLow();
        BigDecimal rate = seeded ? seed : midpoint(low, high);
        BigDecimal value = residual.at(rate);
        // Fold the starting point into the bracket before iterating. Until it is in,
        // bisecting the bracket can land back on the point already being stood on,
        // which presents as a zero-length step — and a zero-length step must never be
        // read as convergence. That is a silent wrong answer, which is the one class
        // of defect this solver exists to make impossible.
        if (strictlyInside(rate, low, high)) {
            if (value.signum() == valueLow.signum()) {
                low = rate;
                valueLow = value;
            } else {
                high = rate;
            }
        }
        int iterations = 0;
        int safeguards = 0;
        while (iterations < tolerance.maxNewtonIterations()) {
            if (converged(value, toleranceAbsolute) || narrowerThan(low, high, tolerance.rateEpsilon())) {
                return new Refinement(bracket, rate, SolverMethod.NEWTON, iterations, safeguards,
                    converged(value, toleranceAbsolute), seeded, false, value);
            }
            iterations++;
            BigDecimal next = null;
            BigDecimal nextValue = null;
            BigDecimal slope = residual.slopeAt(rate);
            if (slope.abs().compareTo(DERIVATIVE_FLOOR) > 0) {
                BigDecimal candidate = rate.subtract(
                    value.divide(slope, Precision.WORKING), Precision.WORKING);
                if (strictlyInside(candidate, low, high)) {
                    BigDecimal candidateValue = residual.at(candidate);
                    if (candidateValue.abs().compareTo(value.abs()) <= 0) {
                        next = candidate;
                        nextValue = candidateValue;
                    }
                }
            }
            if (next == null) {
                safeguards++;
                next = midpoint(low, high);
                nextValue = residual.at(next);
            }
            BigDecimal movement = next.subtract(rate, Precision.WORKING).abs();
            rate = next;
            value = nextValue;
            if (value.signum() == valueLow.signum()) {
                low = rate;
                valueLow = value;
            } else {
                high = rate;
            }
            if (movement.compareTo(tolerance.rateEpsilon()) <= 0) {
                return new Refinement(bracket, rate, SolverMethod.NEWTON, iterations, safeguards,
                    converged(value, toleranceAbsolute), seeded, false, value);
            }
        }
        for (int step = 0; step < tolerance.maxBisectionIterations(); step++) {
            iterations++;
            BigDecimal middle = midpoint(low, high);
            BigDecimal middleValue = residual.at(middle);
            if (converged(middleValue, toleranceAbsolute)
                || narrowerThan(low, high, tolerance.rateEpsilon())) {
                return new Refinement(bracket, middle, SolverMethod.BISECTION_FALLBACK, iterations,
                    safeguards, converged(middleValue, toleranceAbsolute), seeded, false, middleValue);
            }
            if (middleValue.signum() == valueLow.signum()) {
                low = middle;
                valueLow = middleValue;
            } else {
                high = middle;
            }
        }
        // Unreachable in practice: 200 halvings shrink the widest possible bracket
        // to under 1e-59, far inside the rate epsilon, so the width test above
        // always fires first. Returning the midpoint keeps the guarantee explicit
        // rather than resting on that arithmetic. The residual is measured rather
        // than assumed even here, because the one thing this path must not do is
        // claim convergence it cannot demonstrate.
        BigDecimal settled = midpoint(low, high);
        BigDecimal settledValue = residual.at(settled);
        return new Refinement(bracket, settled, SolverMethod.BISECTION_FALLBACK, iterations,
            safeguards, converged(settledValue, toleranceAbsolute), seeded, false, settledValue);
    }

    private static boolean converged(BigDecimal value, BigDecimal toleranceAbsolute) {
        return value.signum() == 0 || value.abs().compareTo(toleranceAbsolute) <= 0;
    }

    private static BigDecimal midpoint(BigDecimal low, BigDecimal high) {
        return low.add(high, Precision.WORKING).divide(TWO, Precision.WORKING);
    }

    private static boolean strictlyInside(BigDecimal rate, BigDecimal low, BigDecimal high) {
        return rate.compareTo(low) > 0 && rate.compareTo(high) < 0;
    }


    /** A bracket this narrow already locates the root well inside storage scale. */
    private static boolean narrowerThan(BigDecimal low, BigDecimal high, BigDecimal epsilon) {
        return high.subtract(low, Precision.WORKING).abs().compareTo(epsilon) <= 0;
    }

    /**
     * The contractual seed, but only for the bracket that actually contains it.
     *
     * <p>The interval is closed. A contractual rate of 1% per month <em>is</em> the
     * ladder node 0.01, and that is the single most common instrument in the book:
     * rejecting a seed for sitting on a bracket endpoint would throw away the
     * contractual seed on exactly the population it converges fastest on. Starting on
     * an endpoint is safe because a non-degenerate bracket has a non-zero residual at
     * both ends.
     */
    private static BigDecimal seedIn(SolveRequest request, Bracket bracket) {
        BigDecimal seed = request.seed();
        if (seed == null || bracket.isDegenerate()) {
            return null;
        }
        return seed.compareTo(bracket.low()) >= 0 && seed.compareTo(bracket.high()) <= 0 ? seed : null;
    }

    // --------------------------------------------------- single root, step 5

    private static SolveResult single(SolveRequest request, Residual residual, Bracket bracket,
            BigDecimal toleranceAbsolute, boolean escalated) {
        Refinement refinement = refine(residual, bracket, seedIn(request, bracket),
            request.tolerance(), toleranceAbsolute);
        StringBuilder note = new StringBuilder(context(request))
            .append(escalated
                ? ": a single root, bracketed only after escalating the ladder to ["
                    + ESCALATION.get(0).toPlainString() + " .. "
                    + ESCALATION.get(ESCALATION.size() - 1).toPlainString()
                    + "] because the standard ladder found no sign change; "
                : ": a single root on the ladder; ")
            .append(refinement.pathNote());
        if (!request.band().contains(refinement.root(), request.convention().periodsPerYear())) {
            // Not an error and not a downgrade. The band is a tie-break between
            // several valid roots (4.4); a unique root is the answer whether or not it
            // looks comfortable. A 1% processing fee amortised over a 30-day WCDL
            // annualises far above any plausible band and the arithmetic is still
            // correct (4.5) — the presentation convention for sub-year instruments is
            // a policy question, not a solver one. Recorded so that a reviewer looks at
            // the projection, which is where an implausible unique root comes from.
            note.append("; the root is unique but outside the plausible band ")
                .append(request.band().label())
                .append(", which disambiguates between roots and does not invalidate one");
        }
        return finish(request, residual, refinement, SolveStatus.SOLVED,
            List.of(refinement.root()), refinement.iterations(), refinement.safeguardSteps(),
            note.toString(), escalated);
    }

    // ------------------------------------------------- multiple roots, 4.4

    /**
     * The multiple-root policy of 4.4, in order: one root in the band takes it; several
     * take the one nearest contractual and are flagged; none goes to the exception
     * queue. Every candidate root is recorded on every path.
     */
    private static SolveResult disambiguate(SolveRequest request, Residual residual,
            List<Bracket> brackets, BigDecimal toleranceAbsolute, boolean escalated) {
        List<Refinement> refinements = new ArrayList<>(brackets.size());
        List<BigDecimal> candidates = new ArrayList<>(brackets.size());
        int iterations = 0;
        int safeguards = 0;
        for (Bracket bracket : brackets) {
            Refinement refinement = refine(residual, bracket, seedIn(request, bracket),
                request.tolerance(), toleranceAbsolute);
            refinements.add(refinement);
            candidates.add(refinement.root());
            iterations += refinement.iterations();
            safeguards += refinement.safeguardSteps();
        }
        int periodsPerYear = request.convention().periodsPerYear();
        List<Refinement> inBand = new ArrayList<>(refinements.size());
        for (Refinement refinement : refinements) {
            if (request.band().contains(refinement.root(), periodsPerYear)) {
                inBand.add(refinement);
            }
        }
        String head = context(request) + ": " + candidates.size() + " roots on the "
            + (escalated ? "escalated ladder, " : "ladder, ")
            + describe(candidates) + ", refined in " + iterations + " iterations across "
            + brackets.size() + " brackets";
        if (inBand.size() == 1) {
            Refinement chosen = inBand.get(0);
            String note = head + "; disambiguated per 4.4(1) — exactly one root lies in the "
                + "plausible band " + request.band().label() + ": " + brief(chosen.root())
                + " (" + brief(PlausibleBand.annualEffective(chosen.root(), periodsPerYear))
                + " annual effective); " + chosen.pathNote();
            return finish(request, residual, chosen, SolveStatus.SOLVED, candidates, iterations,
                safeguards, note, escalated);
        }
        if (inBand.size() > 1) {
            if (!request.hasSeed()) {
                // 4.4(2) tie-breaks on the contractual rate. Without one there is no
                // tie-break, and the solver does not invent a preference between two
                // equally valid IRRs — that is a judgement, and judgements leave the
                // engine through the exception queue.
                return SolveResult.multipleRoots(candidates, iterations, head + "; " + inBand.size()
                    + " lie in the plausible band " + request.band().label()
                    + " and no contractual rate was supplied to choose between them. 4.4(2) has no "
                    + "tie-break without one. Exception queue.");
            }
            Refinement chosen = nearest(inBand, request.seed());
            String note = head + "; " + inBand.size() + " lie in the plausible band "
                + request.band().label() + "; took the one nearest the contractual rate "
                + brief(request.seed()) + " per 4.4(2): " + brief(chosen.root())
                + ". Computed and usable, flagged for approval rather than blocked; "
                + chosen.pathNote();
            return finish(request, residual, chosen, SolveStatus.REQUIRES_REVIEW, candidates,
                iterations, safeguards, note, escalated);
        }
        return SolveResult.multipleRoots(candidates, iterations, head
            + "; none lies in the plausible band " + request.band().label()
            + ". Exception queue per 4.4(3).");
    }

    /**
     * The root nearest the contractual rate, compared in the convention's own units.
     *
     * <p>Ties keep the lower root. Arbitrary, but fixed and documented: an
     * order-dependent tie-break would make the same vector solve differently between
     * runs, which invariant DT-1 forbids.
     */
    private static Refinement nearest(List<Refinement> candidates, BigDecimal seed) {
        Refinement best = candidates.get(0);
        BigDecimal bestDistance = best.root().subtract(seed, Precision.WORKING).abs();
        for (Refinement candidate : candidates) {
            BigDecimal distance = candidate.root().subtract(seed, Precision.WORKING).abs();
            if (distance.compareTo(bestDistance) < 0) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- step 5

    /**
     * Rounds to storage scale and re-measures the residual there (4.2 step 5).
     *
     * <p>{@link Rate}'s constructor does the rounding, and the residual is
     * deliberately re-evaluated at {@link Rate#periodic()} rather than at the raw
     * solved value. The published amortisation is rolled forward with the published
     * 12dp rate, so the residual that matters is the one that rate leaves behind —
     * reporting the residual at 28 digits would flatter the engine and describe a
     * calculation nobody performs.
     */
    private static SolveResult finish(SolveRequest request, Residual residual, Refinement refinement,
            SolveStatus status, List<BigDecimal> candidates, int iterations, int safeguardSteps,
            String diagnostic, boolean escalated) {
        Rate rate = request.convention().toRate(refinement.root());
        BigDecimal atStored = residual.at(rate.periodic()).abs();
        SolveStatus reported = status;
        StringBuilder note = new StringBuilder(diagnostic);
        if (escalated) {
            // A root the standard ladder could not bracket lies, by construction, outside
            // [-99.99%, +1000%] in the convention's units — far outside anything the
            // plausible band admits. Two very different instruments arrive here and both
            // want the same treatment. A one-day drawing carrying a 1% fee really does
            // yield 4,092% annual effective and the arithmetic is impeccable, but whether
            // that figure should be booked as presented is the sub-year presentation
            // question of 4.5, which is policy and not the solver's. A token recovery of
            // 50 against 1,000,000 advanced really does yield -99.995% per month, and
            // what it needs is an impairment, not an interest rate. In both cases a rate
            // exists, the engine should say what it is rather than claim none exists — and
            // in neither case should it publish it unread.
            reported = SolveStatus.REQUIRES_REVIEW;
            note.append("; reached only by escalating the ladder, so the root lies outside the "
                + "standard bracket entirely and is flagged for approval rather than published: a "
                + "rate this far out is either a very short tenor carrying a fee it cannot amortise "
                + "(4.5) or a recovery so far below the advance that the answer is impairment, not "
                + "interest");
        }
        // The residual bound a published rate must satisfy, which is not tol_abs alone:
        // see SolverTolerance.attainableResidual. Ordered so the common path costs nothing.
        // The residual at the root is carried on the refinement, and the derivative — one
        // full-vector evaluation — is taken only where the cheap test has already failed,
        // which is where the answer might actually change.
        BigDecimal atRoot = refinement.residualAtRoot().abs();
        if (atRoot.compareTo(request.absoluteTolerance()) > 0) {
            BigDecimal slope = residual.slopeAt(refinement.root());
            BigDecimal publishable =
                request.tolerance().publishableResidual(request.target().amount(), slope);
            if (atRoot.compareTo(publishable) > 0) {
                reported = SolveStatus.REQUIRES_REVIEW;
                note.append("; the residual at the returned root, ").append(brief(atRoot))
                    .append(", exceeds even what a rate stored to ").append(Precision.RATE_SCALE)
                    .append("dp could leave behind on this vector (").append(brief(publishable))
                    .append("), so the refinement did not reach the root")
                    .append(refinement.withinTolerance() ? "" : " and stopped on bracket width")
                    .append(". Computed and usable, flagged for approval rather than reported as "
                        + "converged");
            }
        }
        return new SolveResult(rate, reported, refinement.method(), iterations, safeguardSteps,
            atStored, candidates, note.toString());
    }

    // ----------------------------------------------------------- diagnostics

    private static String noSolutionDiagnostic(SolveRequest request, Residual residual) {
        List<BigDecimal> extended = extendedLadder();
        BigDecimal lowest = extended.get(0);
        BigDecimal highest = extended.get(extended.size() - 1);
        String head = context(request) + ": no sign change over the ladder or its escalation ["
            + lowest.toPlainString() + " .. " + highest.toPlainString() + "], f("
            + lowest.toPlainString() + ") = " + brief(residual.at(lowest)) + ", f("
            + highest.toPlainString() + ") = " + brief(residual.at(highest)) + ". ";
        // Whether a root exists at all is decided by the sign pattern of the residual's
        // own coefficient sequence, and it is worth saying which case this is. With a
        // single sign change Descartes gives exactly one root in (-100%, infinity), so
        // the vector does have a rate and the honest report is that it lies beyond the
        // escalated ladder — not the older wording, which asserted that no rate existed
        // and sent a reviewer looking for a fee misclassification that is not there.
        int changes = residualSignChanges(request);
        String cause = changes == 1
            ? "The residual sequence changes sign exactly once, so a unique rate above -100% "
                + "does exist and it lies outside even the escalated bracket. Under a short tau "
                + "that is what an ordinary instrument looks like: the bracketable money "
                + "multiple is (1+node)^tau, which collapses toward 1 as tau falls. Treat this "
                + "as an out-of-reach root, not as a missing one — and read it as a signal about "
                + "the projection, most often a fee that has been loaded onto a tenor too short "
                + "to carry it."
            : changes == 0
                ? "The residual sequence never changes sign, so f(r) cannot cross zero and no "
                    + "rate is determined: every flow after the anchor points the same way as "
                    + "the target. The vector is malformed."
                : "The residual sequence changes sign " + changes + " times, so any root that "
                    + "exists is one of several and none was reachable. Tranched drawdowns and "
                    + "restructurings with fresh disbursement produce this shape.";
        return head + cause + " Exception queue with the flow vector attached — never defaulted "
            + "to zero and never to the contractual rate (4.3).";
    }

    /**
     * Sign changes in {@code f}'s own coefficient sequence: {@code -target} at the
     * anchor, then the future flows in date order.
     *
     * <p>Not {@link Discounting#signChanges}, which counts over the vector. The two
     * agree at initial recognition, where the inception leg <em>is</em> the negated
     * target by invariant IC-1, and they part company on a mid-life re-solve (6.2),
     * where the vector holds only the remaining flows and the target arrives
     * separately. It is {@code f} whose roots are being counted, so it is {@code f}'s
     * sequence that decides.
     */
    private static int residualSignChanges(SolveRequest request) {
        int changes = 0;
        int previous = -request.target().amount().signum();
        for (CashFlow flow : request.flows().future()) {
            int signum = flow.amount().signum();
            if (signum == 0) {
                continue;
            }
            if (previous != 0 && signum != previous) {
                changes++;
            }
            previous = signum;
        }
        return changes;
    }

    /**
     * The head of every diagnostic: what was being solved, in what units.
     *
     * <p>The target is shown at presentation scale. A trace is read by people
     * reconciling it against a ledger, and 9.95E+5 is not a number anyone reconciles.
     */
    private static String context(SolveRequest request) {
        return "f(r) = PV(r) - "
            + request.target().atPresentationScale().amount().toPlainString() + " "
            + request.target().currency().getCurrencyCode() + " under "
            + request.convention().label();
    }

    private static String describe(List<BigDecimal> roots) {
        StringBuilder text = new StringBuilder("{");
        for (int index = 0; index < roots.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(brief(roots.get(index)));
        }
        return text.append("}").toString();
    }

    /** Trace formatting only. Never feeds a computation. */
    private static String brief(BigDecimal value) {
        return value.round(TRACE).stripTrailingZeros().toString();
    }

    private static boolean allZero(List<CashFlow> flows) {
        for (CashFlow flow : flows) {
            if (!flow.amount().isZero()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------- internal values

    /**
     * A ladder interval known to contain a root, with the residual at its lower end.
     *
     * <p>Only the lower end's residual is carried: the bracket invariant is that the
     * two ends have opposite signs, so one sign determines the other, and keeping one
     * value means the update rule cannot get them out of step.
     *
     * <p>Degenerate where a ladder node is itself an exact root.
     */
    private record Bracket(BigDecimal low, BigDecimal high, BigDecimal valueLow,
        BigDecimal valueHigh) {

        boolean isDegenerate() {
            return low.compareTo(high) == 0;
        }

        /**
         * An endpoint whose residual is already inside {@code tol_abs}, or {@code null}.
         *
         * <p>The scan has both residuals in hand, so this costs nothing, and it is worth
         * asking because the answer is very often yes on the commonest instrument in the
         * book: an EMI loan priced at its contractual rate has its root exactly on a
         * ladder node, since 1% a month <em>is</em> the node 0.01. {@code f} there is
         * around 1e-21 — not exactly zero, so the bracket is not degenerate, and the
         * root is the bracket's own lower endpoint.
         *
         * <p>Left to iterate, that vector is close to the worst case for a bracketed
         * Newton method rather than the best. {@code f} is convex and decreasing, so a
         * tangent step taken from anywhere inside the bracket lands <em>past</em> the
         * root and outside the bracket — 0.00639, then 0.00913, then 0.00979, creeping
         * up on 0.01 from below and never once landing inside it. Every step is
         * therefore refused by the safeguard, and pure bisection walks the bracket down
         * to {@code rateEpsilon}: 41 iterations, measured, for a root the scan had
         * already found. Answering the question here costs nothing and settles it in
         * none.
         *
         * <p><strong>Safe, with the bound stated rather than asserted.</strong> A residual
         * inside {@code tol_abs} puts the node within about {@code tol_abs / |f'|} of the
         * root, and the node and the true root round to the same published rate as long as
         * that is below half a unit in the last place of a 12dp rate. So the condition is
         * {@code |f'| > 2e12 * tol_abs} — which is {@code |f'| > 200} under the standard
         * tolerance on a small exposure, {@code > 200,000} on a billion-rupee facility
         * where {@code tol_abs} has scaled up with it, and {@code > 2} under the tightened
         * Tier 1 setting. The smallest slope any real instrument produces is around 3.4e5,
         * on a 50,000 twelve-month consumer durable — five orders of magnitude of headroom
         * on the tightest of those thresholds, and {@code f'} only grows with exposure and
         * tenor from there. The slope is deliberately not measured here to prove it per
         * vector: that would cost a derivative evaluation on the majority of all solves,
         * to buy a guarantee against a vector holding a few rupees spread over twenty
         * periods.
         *
         * <p>This is not the near-zero tangency the class comment refuses — that is a node
         * with the same sign on both sides and no bracket at all, and it still goes to the
         * exception queue.
         */
        BigDecimal endpointWithinTolerance(BigDecimal toleranceAbsolute) {
            if (valueLow.abs().compareTo(toleranceAbsolute) <= 0) {
                return low;
            }
            return valueHigh.abs().compareTo(toleranceAbsolute) <= 0 ? high : null;
        }

        String label() {
            return isDegenerate()
                ? "{" + low.toPlainString() + "}"
                : "[" + low.toPlainString() + ", " + high.toPlainString() + "]";
        }
    }

    /**
     * One bracket refined to a root, with how it got there.
     *
     * @param safeguardSteps  iterations in the Newton phase where the tangent step was
     *                        rejected and a bisection of the current bracket was taken
     *                        instead (4.2 step 3) — the profile signal
     *                        {@link SolverMethod} is not
     * @param withinTolerance whether the phase stopped because {@code |f|} came inside
     *                        {@code tol_abs}, as against merely because the bracket
     *                        became narrower than {@code rateEpsilon}
     * @param settledOnScan   whether the scan had already located the root — an exactly
     *                        zero node, or an endpoint inside {@code tol_abs} — so that
     *                        no refinement ran and the seed never came into it
     * @param residualAtRoot  {@code f} at {@link #root}. Carried rather than recomputed:
     *                        every return site has just evaluated it, and {@link #finish}
     *                        needs it. Two full-vector evaluations per solve is not a
     *                        rounding error on ten million contracts (4.6)
     */
    private record Refinement(
        Bracket bracket,
        BigDecimal root,
        SolverMethod method,
        int iterations,
        int safeguardSteps,
        boolean withinTolerance,
        boolean contractualSeed,
        boolean settledOnScan,
        BigDecimal residualAtRoot) {

        String pathNote() {
            if (bracket.isDegenerate()) {
                return "ladder node " + bracket.label() + " is itself an exact root, "
                    + "so no iteration was required";
            }
            if (settledOnScan) {
                return "ladder node " + root.toPlainString() + " of bracket " + bracket.label()
                    + " already sits inside the residual tolerance, so the scan had located "
                    + "the root and no iteration was required — the case of a loan priced at "
                    + "its contractual rate, where that rate is itself a ladder node";
            }
            return method + " settled in " + iterations + " iteration(s) within bracket "
                + bracket.label()
                + (safeguardSteps > 0 ? ", " + safeguardSteps + " of them safeguarded" : "")
                + (contractualSeed
                    ? ", seeded from the contractual rate"
                    : ", seeded from the bracket midpoint as no contractual rate was supplied");
        }
    }

    /**
     * {@code f(r) = PV(r) - target} and its derivative, over one fixed vector.
     *
     * <p>Both delegate to {@link Discounting}, which is the single place discounting
     * happens in this engine. The temptation is to inline the loop here and apply the
     * incremental recurrence of 4.6 — under a uniform periodic-index vector each
     * discount factor is the previous one times {@code (1+r)^-1}, replacing n powers
     * with n multiplications. It is not taken, for one reason: a chain of n rounded
     * multiplications does not reproduce {@link Precision#discountFactor} digit for
     * digit, so the same vector would solve to marginally different last digits
     * depending on which path ran. This engine's obligations are bit-identical replay
     * (invariant DT-1) and a catch-up restatement that discounts exactly as the solve
     * did (6.3), and both outrank an order of magnitude of speed on the periodic-index
     * path. The cheap half of 4.6 is taken instead — no point is ever evaluated twice
     * within a solve — and the expensive half is bought back where 4.6 says the real
     * win is: re-solve only on a triggering event, never on every period.
     */
    private static final class Residual {

        private final FlowVector flows;
        private final TimeConvention convention;
        private final BigDecimal target;

        Residual(SolveRequest request) {
            this.flows = request.flows();
            this.convention = request.convention();
            this.target = request.target().amount();
        }

        BigDecimal at(BigDecimal rate) {
            return Discounting.presentValue(rate, flows, convention)
                .subtract(target, Precision.WORKING);
        }

        /** {@code f'(r) = -sum(tau_t * CF_t * (1+r)^(-tau_t-1))}; the target is constant. */
        BigDecimal slopeAt(BigDecimal rate) {
            return Discounting.derivative(rate, flows, convention);
        }
    }
}
