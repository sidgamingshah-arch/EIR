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
 * annualised root, where the units are unambiguous.
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
 * discounted twice; the only deliberate re-evaluation is at the two ends of the
 * ladder when composing a no-solution diagnostic, where an exception-queue entry that
 * shows its working is worth two present values. On the common path the fourteen-node
 * ladder scan therefore costs more than the four Newton iterations that follow it, and
 * it is still scanned in full — see {@link #scan}. The incremental discount-factor
 * recurrence of 4.6 is deliberately not taken: see the note on {@link Residual}.
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
        List<Bracket> brackets = scan(residual);
        if (brackets.isEmpty()) {
            return SolveResult.noSolution(noSolutionDiagnostic(request, residual));
        }
        if (brackets.size() == 1) {
            return single(request, residual, brackets.get(0), toleranceAbsolute);
        }
        return disambiguate(request, residual, brackets, toleranceAbsolute);
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
    private static List<Bracket> scan(Residual residual) {
        List<Bracket> brackets = new ArrayList<>();
        BigDecimal previousNode = null;
        BigDecimal previousValue = null;
        for (BigDecimal node : LADDER) {
            BigDecimal value = residual.at(node);
            if (value.signum() == 0) {
                brackets.add(new Bracket(node, node, value));
            } else if (previousValue != null && previousValue.signum() != 0
                && previousValue.signum() != value.signum()) {
                brackets.add(new Bracket(previousNode, node, previousValue));
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
     */
    private static Refinement refine(Residual residual, Bracket bracket, BigDecimal seed,
            SolverTolerance tolerance, BigDecimal toleranceAbsolute) {
        if (bracket.isDegenerate()) {
            return new Refinement(bracket, bracket.low(), SolverMethod.NEWTON, 0, false);
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
        while (iterations < tolerance.maxNewtonIterations()) {
            if (converged(value, toleranceAbsolute) || narrowerThan(low, high, tolerance.rateEpsilon())) {
                return new Refinement(bracket, rate, SolverMethod.NEWTON, iterations, seeded);
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
                return new Refinement(bracket, rate, SolverMethod.NEWTON, iterations, seeded);
            }
        }
        for (int step = 0; step < tolerance.maxBisectionIterations(); step++) {
            iterations++;
            BigDecimal middle = midpoint(low, high);
            BigDecimal middleValue = residual.at(middle);
            if (converged(middleValue, toleranceAbsolute)
                || narrowerThan(low, high, tolerance.rateEpsilon())) {
                return new Refinement(bracket, middle, SolverMethod.BISECTION_FALLBACK, iterations, seeded);
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
        // rather than resting on that arithmetic.
        return new Refinement(bracket, midpoint(low, high), SolverMethod.BISECTION_FALLBACK,
            iterations, seeded);
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
            BigDecimal toleranceAbsolute) {
        Refinement refinement = refine(residual, bracket, seedIn(request, bracket),
            request.tolerance(), toleranceAbsolute);
        StringBuilder note = new StringBuilder(context(request))
            .append(": a single root on the ladder; ")
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
            List.of(refinement.root()), refinement.iterations(), note.toString());
    }

    // ------------------------------------------------- multiple roots, 4.4

    /**
     * The multiple-root policy of 4.4, in order: one root in the band takes it; several
     * take the one nearest contractual and are flagged; none goes to the exception
     * queue. Every candidate root is recorded on every path.
     */
    private static SolveResult disambiguate(SolveRequest request, Residual residual,
            List<Bracket> brackets, BigDecimal toleranceAbsolute) {
        List<Refinement> refinements = new ArrayList<>(brackets.size());
        List<BigDecimal> candidates = new ArrayList<>(brackets.size());
        int iterations = 0;
        for (Bracket bracket : brackets) {
            Refinement refinement = refine(residual, bracket, seedIn(request, bracket),
                request.tolerance(), toleranceAbsolute);
            refinements.add(refinement);
            candidates.add(refinement.root());
            iterations += refinement.iterations();
        }
        int periodsPerYear = request.convention().periodsPerYear();
        List<Refinement> inBand = new ArrayList<>(refinements.size());
        for (Refinement refinement : refinements) {
            if (request.band().contains(refinement.root(), periodsPerYear)) {
                inBand.add(refinement);
            }
        }
        String head = context(request) + ": " + candidates.size() + " roots on the ladder, "
            + describe(candidates) + ", refined in " + iterations + " iterations across "
            + brackets.size() + " brackets";
        if (inBand.size() == 1) {
            Refinement chosen = inBand.get(0);
            String note = head + "; disambiguated per 4.4(1) — exactly one root lies in the "
                + "plausible band " + request.band().label() + ": " + brief(chosen.root())
                + " (" + brief(PlausibleBand.annualEffective(chosen.root(), periodsPerYear))
                + " annual effective); " + chosen.pathNote();
            return finish(request, residual, chosen, SolveStatus.SOLVED, candidates, iterations, note);
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
                iterations, note);
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
            SolveStatus status, List<BigDecimal> candidates, int iterations, String diagnostic) {
        Rate rate = request.convention().toRate(refinement.root());
        BigDecimal atStored = residual.at(rate.periodic()).abs();
        return new SolveResult(rate, status, refinement.method(), iterations, atStored,
            candidates, diagnostic);
    }

    // ----------------------------------------------------------- diagnostics

    private static String noSolutionDiagnostic(SolveRequest request, Residual residual) {
        BigDecimal lowest = LADDER.get(0);
        BigDecimal highest = LADDER.get(LADDER.size() - 1);
        return context(request) + ": no sign change over the ladder ["
            + lowest.toPlainString() + " .. " + highest.toPlainString() + "], f("
            + lowest.toPlainString() + ") = " + brief(residual.at(lowest)) + ", f("
            + highest.toPlainString() + ") = " + brief(residual.at(highest))
            + ". No economically meaningful rate exists: either total inflows do not exceed the "
            + "initial outflow or the vector is malformed. Exception queue with the flow vector "
            + "attached — never defaulted to zero and never to the contractual rate (4.3).";
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
    private record Bracket(BigDecimal low, BigDecimal high, BigDecimal valueLow) {

        boolean isDegenerate() {
            return low.compareTo(high) == 0;
        }

        String label() {
            return isDegenerate()
                ? "{" + low.toPlainString() + "}"
                : "[" + low.toPlainString() + ", " + high.toPlainString() + "]";
        }
    }

    /** One bracket refined to a root, with how it got there. */
    private record Refinement(
        Bracket bracket,
        BigDecimal root,
        SolverMethod method,
        int iterations,
        boolean contractualSeed) {

        String pathNote() {
            if (bracket.isDegenerate()) {
                return "ladder node " + bracket.label() + " is itself an exact root, "
                    + "so no iteration was required";
            }
            return method + " settled in " + iterations + " iteration(s) within bracket "
                + bracket.label() + (contractualSeed
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
