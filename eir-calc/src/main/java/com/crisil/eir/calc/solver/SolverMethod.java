package com.crisil.eir.calc.solver;

/**
 * Which phase of the algorithm produced the returned rate.
 *
 * <p>Newton–Raphson is in the algorithm for speed; bisection is in it for
 * correctness (4.2). This enum records which phase the refinement terminated in.
 *
 * <p><strong>It is not the profile signal it looks like.</strong> The obvious
 * reading — that a population which starts reporting {@link #BISECTION_FALLBACK}
 * has changed shape, and that this is worth knowing before the close — does not
 * hold, and saying so would be a monitoring claim that can never fire. Under the
 * specification's cap of 100 Newton iterations the fallback is unreachable: every
 * accepted point replaces a bracket endpoint, so even a Newton phase that bisects
 * on all 100 iterations has halved the bracket 100 times, and 100 halvings of the
 * widest ladder interval leave an interval far inside {@code rateEpsilon}. The
 * bracket-width test therefore always fires first, and the phase reported is always
 * {@code NEWTON}. {@code BISECTION_FALLBACK} appears only where the Newton cap has
 * been lowered deliberately — which is how it is tested, and why the fallback still
 * has to exist: it is the termination guarantee, not a diagnostic.
 *
 * <p>{@link SolveResult#safeguardSteps()} is the signal that does move with the shape
 * of a vector — how many times the tangent step was rejected and replaced by a
 * bisection of the current bracket — but the claim made for it here is deliberately
 * narrower than the one made above for this enum, because it was measured rather than
 * assumed. Over an eighty-eight vector sweep of level annuities at every plausible
 * rate and tenor, balloons, moratoria with and without a seed, and Case 7's deep
 * discount, the only profile that raised the count was an unseeded step-up EMI. So:
 * it fires on irregular instalment profiles and is zero on regular ones. That is worth
 * monitoring; it is not a rich signal, and this comment does not pretend it is one.
 */
public enum SolverMethod {

    /**
     * The Newton–Raphson phase produced the root. Safeguard bisection steps taken
     * <em>within</em> that phase — where the tangent step left the bracket, the
     * derivative was flat, or the residual grew — still count as {@code NEWTON}:
     * the distinction being recorded is whether the iteration cap was reached, not
     * whether every individual step was a tangent step. Those steps are counted
     * separately, on {@link SolveResult#safeguardSteps()}.
     *
     * <p>Under the specification's tolerance this is the only value that occurs.
     */
    NEWTON,

    /**
     * The Newton phase hit its hard iteration cap and pure bisection over the
     * bracket finished the job. Not a failure: a bracket containing a sign change
     * cannot fail to converge under bisection, which is exactly why the fallback is
     * mandatory rather than optional.
     *
     * <p>Unreachable under {@link SolverTolerance#standard()} and
     * {@link SolverTolerance#tightened()} — see the note on this enum. It is kept
     * because the guarantee has to be real, not because the case is expected.
     */
    BISECTION_FALLBACK
}
