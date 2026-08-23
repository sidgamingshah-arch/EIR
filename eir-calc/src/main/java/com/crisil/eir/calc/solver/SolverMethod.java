package com.crisil.eir.calc.solver;

/**
 * Which phase of the algorithm produced the returned rate.
 *
 * <p>Recorded on the computation because it is the cheapest available signal about
 * the shape of a flow vector. Newton–Raphson is in the algorithm for speed;
 * bisection is in it for correctness (4.2). A population that suddenly starts
 * reporting {@link #BISECTION_FALLBACK} has changed profile — step EMIs, a
 * moratorium with interest capitalisation, a restructured tranche — and that is
 * worth knowing before the close, not after it.
 */
public enum SolverMethod {

    /**
     * The Newton–Raphson phase produced the root. Safeguard bisection steps taken
     * <em>within</em> that phase — where the tangent step left the bracket, the
     * derivative was flat, or the residual grew — still count as {@code NEWTON}:
     * the distinction being recorded is whether the iteration cap was reached, not
     * whether every individual step was a tangent step.
     */
    NEWTON,

    /**
     * The Newton phase hit its hard iteration cap and pure bisection over the
     * bracket finished the job. Not a failure: a bracket containing a sign change
     * cannot fail to converge under bisection, which is exactly why the fallback is
     * mandatory rather than optional.
     */
    BISECTION_FALLBACK
}
