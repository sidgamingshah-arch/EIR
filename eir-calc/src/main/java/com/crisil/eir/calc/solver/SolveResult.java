package com.crisil.eir.calc.solver;

import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a solve, successful or not.
 *
 * <p>Everything an auditor needs to re-perform the solve, and everything the
 * exception queue needs to act on a failed one, in one immutable value. A result is
 * self-describing on purpose: the alternative is a rate with no provenance, which
 * is indistinguishable from a rate that was guessed.
 *
 * <p>Three fields are {@code null} exactly when {@link SolveStatus#carriesRate()}
 * is false — {@link #rate}, {@link #method} and {@link #residualAtStoredRate}. A
 * failed solve has no rate to report, no method that produced one, and no residual
 * to measure, and a zero in any of those places would read as a successful solve of
 * a zero-rate instrument. The constructor enforces the pairing in both directions.
 *
 * @param rate                  the stored rate, already rounded to 12dp by
 *                              {@link Rate}; {@code null} when unsolved
 * @param status                how the solve ended — the only failure channel
 * @param method                which phase produced the rate; {@code null} when unsolved
 * @param iterations            total refinement iterations across every bracket the
 *                              solve examined, Newton and bisection together. Cost
 *                              telemetry for the 10M-contract close window (4.6), not
 *                              a correctness signal
 * @param safeguardSteps        how many of those iterations replaced a rejected
 *                              tangent step with a bisection of the current bracket
 *                              (4.2 step 3). Unlike {@link SolverMethod}, this
 *                              <em>does</em> move with the shape of a vector — step
 *                              EMIs, moratoria with capitalisation, balloons and
 *                              tranched drawdowns raise it while still terminating in
 *                              the Newton phase — so it is the profile signal worth
 *                              monitoring across a population. Zero on a well-behaved
 *                              vector, and zero whenever no rate was solved
 * @param residualAtStoredRate  {@code |f(r)|} re-evaluated at the <em>rounded</em>
 *                              12dp rate, not at the raw solved value. It is the
 *                              published rate that must reproduce the published
 *                              amortisation (1.4), so it is the published rate whose
 *                              residual is worth recording
 * @param candidateRoots        every root found, ascending, in the convention's own
 *                              units. Populated on every multiple-root path, whether
 *                              disambiguation succeeded or not (4.4)
 * @param diagnostic            how the solve went, in plain words, for the computation
 *                              record and the exception queue
 */
public record SolveResult(
    Rate rate,
    SolveStatus status,
    SolverMethod method,
    int iterations,
    int safeguardSteps,
    BigDecimal residualAtStoredRate,
    List<BigDecimal> candidateRoots,
    String diagnostic) {

    public SolveResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnostic, "diagnostic");
        candidateRoots = candidateRoots == null ? List.of() : List.copyOf(candidateRoots);
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be non-negative, got " + iterations);
        }
        if (safeguardSteps < 0 || safeguardSteps > iterations) {
            throw new IllegalArgumentException("safeguardSteps must lie in [0, " + iterations
                + "], got " + safeguardSteps);
        }
        if (status.carriesRate()) {
            if (rate == null || method == null || residualAtStoredRate == null) {
                throw new IllegalArgumentException(
                    status + " must carry a rate, a method and a residual");
            }
        } else if (rate != null || method != null || residualAtStoredRate != null) {
            throw new IllegalArgumentException(
                status + " must carry no rate, no method and no residual");
        }
    }

    /** A unique root, or a multiple-root vector disambiguated to exactly one (4.4(1)). */
    public static SolveResult solved(Rate rate, SolverMethod method, int iterations,
            int safeguardSteps, BigDecimal residualAtStoredRate, List<BigDecimal> candidateRoots,
            String diagnostic) {
        return new SolveResult(rate, SolveStatus.SOLVED, method, iterations, safeguardSteps,
            residualAtStoredRate, candidateRoots, diagnostic);
    }

    /** Several roots in the plausible band; the nearest to contractual, flagged (4.4(2)). */
    public static SolveResult requiresReview(Rate rate, SolverMethod method, int iterations,
            int safeguardSteps, BigDecimal residualAtStoredRate, List<BigDecimal> candidateRoots,
            String diagnostic) {
        return new SolveResult(rate, SolveStatus.REQUIRES_REVIEW, method, iterations, safeguardSteps,
            residualAtStoredRate, candidateRoots, diagnostic);
    }

    /** No sign change on the ladder. Exception queue, never a defaulted rate (4.3). */
    public static SolveResult noSolution(String diagnostic) {
        return new SolveResult(null, SolveStatus.NO_SOLUTION, null, 0, 0, null, List.of(), diagnostic);
    }

    /** Multiple roots that the band could not resolve to one. Exception queue (4.4(3)). */
    public static SolveResult multipleRoots(List<BigDecimal> candidateRoots, int iterations,
            String diagnostic) {
        return new SolveResult(null, SolveStatus.MULTIPLE_ROOTS, null, iterations, 0, null,
            candidateRoots, diagnostic);
    }

    /** Whether the solve produced an unambiguous rate needing no approval. */
    public boolean isSolved() {
        return status == SolveStatus.SOLVED;
    }

    /**
     * Whether a usable rate is present. True for {@link SolveStatus#REQUIRES_REVIEW}
     * as well: that figure is computed and usable, flagged rather than blocked.
     */
    public boolean hasRate() {
        return rate != null;
    }

    /**
     * The rate, or a failure carrying the diagnostic.
     *
     * <p>The throwing accessor is separate from {@link RateSolver#solve}'s return
     * value by design. {@code solve} never throws — a caller batching a million contracts
     * needs the failures back as data so it can queue them — but a caller that has
     * already decided a rate must exist should not have to defend against a silent
     * {@code null}.
     *
     * @throws IllegalStateException if the solve produced no rate
     */
    public Rate rateOrThrow() {
        if (rate == null) {
            throw new IllegalStateException("no EIR was solved (" + status + "): " + diagnostic);
        }
        return rate;
    }

    /** How many mathematically valid roots the ladder scan turned up. */
    public int candidateRootCount() {
        return candidateRoots.size();
    }
}
