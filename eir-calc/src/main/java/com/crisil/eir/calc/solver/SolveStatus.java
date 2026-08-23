package com.crisil.eir.calc.solver;

/**
 * How a solve ended — and the only channel through which a failed solve is
 * reported.
 *
 * <p>These statuses exist because the alternative is the most damaging defect
 * available to this engine (calculation specification 4.3). A solver that returns
 * a number whatever happens, falling back to zero or to the contractual rate when
 * it cannot converge, reproduces the pre-ACPIR position while appearing to have
 * implemented EIR: the figures are plausible, they tie to nothing, and nothing in
 * the record says the solve failed. So failure is a <em>value</em> here. It
 * travels with the computation, it reaches the exception queue, and the caller has
 * to decide what to do with it.
 */
public enum SolveStatus {

    /** A unique root was found inside tolerance. The ordinary outcome. */
    SOLVED,

    /**
     * A usable rate, but only after choosing between several roots that all lie
     * inside the plausible band (4.4(2)). Routed for approval: the figure is
     * computed and usable, so it is flagged rather than blocked.
     */
    REQUIRES_REVIEW,

    /**
     * No sign change anywhere on the bracket ladder, so no economically meaningful
     * rate exists — total inflows do not exceed the initial outflow, or the vector
     * is malformed. Exception queue, with the flow vector attached (4.3).
     */
    NO_SOLUTION,

    /**
     * Several mathematically valid roots, and either none of them inside the
     * plausible band or no contractual rate to choose between the ones that are.
     * Exception queue (4.4(3)).
     */
    MULTIPLE_ROOTS;

    /** Whether a usable {@code Rate} accompanies this status. */
    public boolean carriesRate() {
        return this == SOLVED || this == REQUIRES_REVIEW;
    }

    /** Whether the computation must be failed into the exception queue. */
    public boolean routesToExceptionQueue() {
        return this == NO_SOLUTION || this == MULTIPLE_ROOTS;
    }

    /** Whether the figure is usable but needs approval before it is relied on. */
    public boolean requiresApproval() {
        return this == REQUIRES_REVIEW;
    }
}
