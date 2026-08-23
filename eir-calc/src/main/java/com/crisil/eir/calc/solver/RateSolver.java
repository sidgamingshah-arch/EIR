package com.crisil.eir.calc.solver;

/**
 * Solves {@code f(r) = PV(flows at r) - target = 0} for the effective interest
 * rate (4.1).
 *
 * <p>Two obligations on every implementation, both separately tested:
 *
 * <ul>
 *   <li><strong>{@link #solve} does not throw for a data condition.</strong> A
 *       vector with no solution, or with several, comes back as a
 *       {@link SolveStatus} on a {@link SolveResult}. Callers process portfolios in
 *       batches and need failures back as values they can queue, not as exceptions
 *       that abandon the batch. A malformed <em>request</em> — mismatched currency,
 *       a rate at or below -100% — is a programming error and still throws, from
 *       {@link SolveRequest}'s own constructor.
 *   <li><strong>No silent fallback.</strong> Not to zero, not to the contractual
 *       rate, not to the previous period's rate. 4.3 names this the most damaging
 *       failure mode available to the engine, because it produces plausible numbers
 *       and leaves no trace.
 * </ul>
 *
 * <p>Implementations are pure: no clock, no randomness, no state carried between
 * solves. Identical requests produce bit-identical results however far apart they
 * are run, which is what invariant DT-1 asserts every night.
 */
public interface RateSolver {

    /** Solves the request, reporting failure as a status rather than as an exception. */
    SolveResult solve(SolveRequest request);
}
