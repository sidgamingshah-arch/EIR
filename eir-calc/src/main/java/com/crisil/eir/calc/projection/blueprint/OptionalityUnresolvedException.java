package com.crisil.eir.calc.projection.blueprint;

/**
 * Raised where the <em>chosen</em> exercise policy cannot be evaluated.
 *
 * <p>Three things reach here, and they share one property: in every case the
 * alternative to failing is publishing a figure produced by a policy other than
 * the one recorded.
 *
 * <ul>
 *   <li>The policy's judgement inputs are absent — a most-likely date nobody
 *       supplied, a distribution nobody built, a market view for a model nobody
 *       parameterised. {@link OptionalityJudgements} exists so those arrive as
 *       data; when they do not, the engine has no honest output.
 *   <li>The policy has no anchor on this contract — a next-repricing election on a
 *       fixed-rate instrument, an earliest-call truncation at a date at or beyond
 *       maturity. {@code ScheduleBlueprint} already rejects the first at
 *       construction; this catches the shapes that only become visible once the
 *       ladder is in hand.
 *   <li>The solve failed. Section 4.3 names the silent fallback — to zero, to the
 *       contractual rate, to last period's rate — as the most damaging failure
 *       available to the engine, because it produces plausible numbers and leaves
 *       no trace. A failed solve on the chosen policy therefore propagates.
 * </ul>
 *
 * <p>An <em>unchosen</em> policy that cannot be evaluated does not raise this. It
 * is simply left out of the alternatives, and invariant ST-7 then records that
 * fewer than two policies were costed — which is the finding rather than an error,
 * and belongs in the determination where a reviewer will see it.
 */
public class OptionalityUnresolvedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExercisePolicy policy;

    public OptionalityUnresolvedException(ExercisePolicy policy, String because) {
        super("exercise policy " + policy + " cannot be evaluated: " + because
            + ". The engine does not substitute another policy for the one recorded — the"
            + " recorded policy exists to prevent exactly that substitution.");
        this.policy = policy;
    }

    /** The policy that could not be evaluated. */
    public ExercisePolicy policy() {
        return policy;
    }
}
