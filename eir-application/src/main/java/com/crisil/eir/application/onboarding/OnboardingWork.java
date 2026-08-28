package com.crisil.eir.application.onboarding;

/**
 * The stages of 05 § 3.1, in the order the sequence diagram runs them — and the record of which of
 * them a contract actually consumed.
 *
 * <p><b>This enum exists to make the ordering observable rather than merely asserted.</b> 05 § 3.1
 * requires the measurement gate to run before any projection or solve, and the cheap way to satisfy
 * a requirement like that is a comment saying the gate is checked first. A comment is not a control.
 * {@link OnboardingOutcome#workPerformed()} carries the stages a contract went through, in order,
 * and ST-12's work leg fails on an outcome that shows {@link #PROJECTION} or {@link #SOLVE} for a
 * contract the gate excluded. That is a control: it has an input that makes it fail — a pipeline
 * that projects before it gates — and a test asserts the list is exactly
 * {@code [MEASUREMENT_GATE]} for an SPPI-failing contract.
 *
 * <p><b>Declaration order is the pipeline order</b>, and {@link #precedes} reads it. That makes
 * "the gate ran first" a comparison rather than an assumption, and it means adding a stage in the
 * wrong place is visible in one file rather than distributed across the pipeline.
 */
public enum OnboardingWork {

    /**
     * The measurement-category and SPPI gate (FR-103, FR-104).
     *
     * <p>First, always, for every contract. Cheap — three attribute reads and a decision table —
     * and decisive.
     */
    MEASUREMENT_GATE,

    /**
     * Fee classification through the versioned rule set (FR-201, FR-202).
     *
     * <p>Second, because a fee's treatment is an input to the initial carrying amount and therefore
     * to the projection. Not free: a lookup per posting against a dated, precedence-ordered rule
     * set.
     */
    FEE_CLASSIFICATION,

    /**
     * Materiality tier assignment (FR-107, 03 § 10).
     *
     * <p>Third, because the tier decides the solver's tolerance — {@code SolverTolerance.forTier} —
     * so it has to be known before the solve is configured. That is also what stops the tier being
     * a decorative field: it changes the convergence criterion the rate is accepted under.
     */
    TIER_ASSIGNMENT,

    /**
     * Cash-flow projection, and the IC-1 assertion that comes with it.
     *
     * <p>The first expensive stage: a schedule of up to 360 instalments, both legs, and the
     * behavioural overlay. This is the "expensive work" 05 § 3.1 says must not precede the gate.
     */
    PROJECTION,

    /**
     * The EIR solve.
     *
     * <p>The most expensive stage — Newton–Raphson with an analytic derivative inside a bracket,
     * bisection fallback, up to 100 Newton and 200 bisection iterations
     * ({@code SolverTolerance.MAX_NEWTON_ITERATIONS}, {@code MAX_BISECTION_ITERATIONS}) — and the
     * one that produces the number that must not exist for an instrument outside the EIR regime.
     */
    SOLVE;

    /** Whether this stage runs before {@code other} in 05 § 3.1's sequence. */
    public boolean precedes(OnboardingWork other) {
        return ordinal() < other.ordinal();
    }

    /**
     * Whether this stage is one of the two 05 § 3.1 calls "expensive work" and forbids ahead of the
     * gate.
     *
     * <p>Named rather than inlined as {@code this == PROJECTION || this == SOLVE}, because the
     * invariant that reads it is asserting the sentence from the architecture document and a reader
     * should find that sentence attached to the predicate.
     */
    public boolean isExpensive() {
        return this == PROJECTION || this == SOLVE;
    }
}
