package com.crisil.eir.application.onboarding;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One contract's initial recognition: the outcome, and the tier permission the outcome has no slot
 * for.
 *
 * <h2>Why this pair exists rather than a field on {@link OnboardingOutcome}</h2>
 *
 * <p>{@link OnboardingOutcome} is where a contract's disposition lives, and two of its own rules
 * make it the wrong carrier for a TG-1 demotion:
 *
 * <ul>
 *   <li>Its {@code exception} component determines {@code disposition()} — any outcome carrying an
 *       exception reports {@code QUARANTINED}. A TG-1 demotion is the opposite of a quarantine:
 *       {@code ExceptionCategory.STALE_EQUIVALENCE_TEST} carries
 *       {@code stopsTheContract() == false}, and {@code EquivalenceTestOutcome} asserts that fact at
 *       construction precisely so that "the tier gate demotes to Tier 2 and must not quarantine a
 *       contract that is measurable". Filing the entry there would report a contract that has a
 *       perfectly good Tier 2 rate as one the engine could not compute.
 *   <li>Its {@code tier} component is a {@code TierAssignmentResult}, which refuses a tier that
 *       disagrees with the rule that assigned it — so the demoted tier is not expressible in it. See
 *       {@link TierPermission} on why inventing a {@code TierAssignmentRule} for the demotion would
 *       be wrong rather than merely inconvenient.
 * </ul>
 *
 * <p>So the outcome keeps saying what it has always said — including {@link OnboardingOutcome#tier()}
 * being the FR-107 <em>proposal</em>, unaltered — and the permission travels alongside it. A caller
 * persisting 04 § 2.1's {@code materiality_tier} takes {@link TierPermission#effectiveTier()} and
 * not {@code outcome.tier().tier()}; that is the one hand-off a reader of this module has to get
 * right, and it is stated on both types.
 *
 * <p><b>What a future change to {@code OnboardingOutcome} should do</b>, named here so the follow-up
 * is not guesswork: add a {@code TierPermission} component, publish its TG-1 result through
 * {@code computeInvariants} the way IC-1 is published, and separate "carries a queue entry" from
 * "is quarantined" so that {@code disposition()} reads the category's {@code stopsTheContract()}
 * rather than the presence of a record. That is a change to a type this unit does not own.
 *
 * @param outcome        the contract's disposition; never null
 * @param tierPermission the second gate's decision, or null where the contract never reached the
 *                       tier stage — an excluded contract, or one quarantined at the measurement
 *                       gate, the fee stage or the IC-1 assertion
 */
public record OnboardingRecognition(OnboardingOutcome outcome, TierPermission tierPermission) {

    public OnboardingRecognition {
        Objects.requireNonNull(outcome, "outcome");
        if (tierPermission != null
            && !outcome.contractId().equals(tierPermission.contractId())) {
            throw new IllegalArgumentException(
                "recognition pairs an outcome for " + outcome.contractId()
                    + " with a tier permission for " + tierPermission.contractId()
                    + "; a permission read against the wrong contract licenses the wrong"
                    + " approximation");
        }
    }

    /** A recognition that never reached the tier stage. */
    public static OnboardingRecognition ungated(OnboardingOutcome outcome) {
        return new OnboardingRecognition(outcome, null);
    }

    /** The contract this recognition is about. */
    public String contractId() {
        return outcome.contractId();
    }

    /** The second gate's decision, or empty where the contract never reached it. */
    public Optional<TierPermission> permission() {
        return Optional.ofNullable(tierPermission);
    }

    /**
     * Every queue entry this recognition produced, in the order the stages that raised them ran.
     *
     * <p>The tier gate's entry first, because the gate runs before the solve. A contract can carry
     * both: a Tier 3 population with no test on file, demoted to Tier 2, whose Tier 2 solve then
     * finds no root. Two entries for one contract is correct there — one is a control failure
     * somebody has to clear and the other is a computation that produced no figure — and a report
     * that kept only one of them would lose whichever the author of the loop thought less
     * important.
     *
     * <p>Ordered deterministically rather than by iteration over a set, because FR-903 requires two
     * runs of one population to file byte-identically.
     */
    public List<ExceptionRecord> queueEntries() {
        List<ExceptionRecord> entries = new ArrayList<>(2);
        if (tierPermission != null) {
            tierPermission.failure().ifPresent(entries::add);
        }
        outcome.failure().ifPresent(entries::add);
        return List.copyOf(entries);
    }

    /**
     * The invariant results this recognition established: the outcome's, plus TG-1 where the tier
     * gate had a subject.
     *
     * <p>TG-1 last rather than interleaved, because the outcome computes its own list and this
     * method must not reorder it — {@code InvariantResult.conjunction} keeps the first breach's
     * deviation among results sharing an id, so the order of results is load-bearing for anything
     * that aggregates them.
     */
    public List<InvariantResult> invariants() {
        List<InvariantResult> assembled = new ArrayList<>(outcome.invariants());
        if (tierPermission != null) {
            tierPermission.tierGateResult().ifPresent(assembled::add);
        }
        return List.copyOf(assembled);
    }

    @Override
    public String toString() {
        return tierPermission == null
            ? outcome.describe()
            : outcome.describe() + " | " + tierPermission.describe();
    }
}
