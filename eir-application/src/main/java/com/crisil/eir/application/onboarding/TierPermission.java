package com.crisil.eir.application.onboarding;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.tier.EquivalenceTestOutcome;
import com.crisil.eir.policy.tier.TierAssignmentResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the tier FR-107 proposed may actually be measured — the second gate of 03 § 10, applied.
 *
 * <h2>The defect this type exists to close</h2>
 *
 * <p>{@code TierAssignmentResult.requiresEquivalenceTest()} returns true for every Tier 3
 * assignment, its javadoc says a Tier 3 assignment "is only usable once the equivalence test has
 * passed", and until this type existed <b>nothing asked it</b>. {@code EquivalenceTestGate} in
 * {@code eir-policy} implemented the whole second gate — FR-411's staleness limb, FR-412's absolute
 * refusal, the demotion to Tier 2, the TG-1 result and the queue category — and had no caller
 * outside its own package. The tier reached exactly one consumer, {@code SolverTolerance.forTier},
 * and a permission nobody sought was indistinguishable from a permission granted.
 *
 * <p>The measured consequence, from reference case 9: a 15-year zero-coupon bought at 315,241.70
 * against a face of 1,000,000.00 has 684,758.30 of discount, and the straight-line accretion Tier 3
 * licenses takes 45,650.55 a year against the EIR method's 25,219.34 in year one and 74,074.07 in
 * year fifteen — <b>81.0% overstatement of year-one income and 38.4% understatement of the final
 * year</b>, with the two columns tying at 684,758.30 exactly. The exact tie is the defect and not
 * the reassurance: an error that nets to zero over the life is invisible to every control that looks
 * at cumulative figures and material in every single reporting period. That instrument was assigned
 * Tier 3 by {@code TIER_3_FULLY_COLLATERALISED_LOW_FEE}, solved on Tier 3's tolerance, and
 * recognised, with nothing on the record saying the permission was never sought. That is the 08 risk
 * register's Cambodia failure mode arriving through an unwired gate rather than through a decision.
 *
 * <h2>What makes this a control rather than a field</h2>
 *
 * <p><b>A Tier 3 proposal with no gate outcome cannot be constructed.</b> The compact constructor
 * refuses it, and that refusal is the whole point of the type: it is the exact state the pipeline
 * was in before this unit, so the defect cannot come back by an edit that forgets to call the gate.
 * The complement is refused too — a gate outcome attached to a Tier 1 or Tier 2 proposal — because a
 * TG-1 result fabricated for a population the invariant is not about is the "control that reads as
 * coverage and cannot fail" that {@code OnboardingRun}'s javadoc names, and it would bury the real
 * TG-1 breaches in a list of vacuous passes.
 *
 * <p>The gate is therefore consulted <b>only</b> where {@code proposed.requiresEquivalenceTest()},
 * and {@link #tierGateResult()} is empty for everything else rather than a pass. That is a
 * deliberate divergence from {@code EquivalenceTestGate.evaluate}, which answers a non-Tier-3
 * subject with a vacuous {@code NOT_TIER_3} pass so that a population-level report "could be
 * distinguished from one where the gate had never run". Both readings are right for their level: at
 * population level the gate's own {@code evaluateAll} needs a result per subject to conjoin, and at
 * contract level an unevidenced pass on every retail mortgage in the book is noise that makes the
 * few real breaches unfindable. {@code OnboardingRun.unassertedInvariants()} is the mechanism that
 * keeps the absence honest at the level where it matters.
 *
 * <h2>Why the effective tier is not a {@code TierAssignmentResult}</h2>
 *
 * <p>It cannot be one. {@code TierAssignmentResult} refuses a tier that disagrees with the rule that
 * assigned it — "the persisted materiality_tier stops meaning what tier_basis says it means" — and
 * there is no {@code TierAssignmentRule} for "demoted by the equivalence test", nor should there be:
 * the demotion is not a correction of the assignment. So {@link #proposed()} stays the FR-107
 * assignment exactly as the tier gate made it, and the tier that must actually be measured is
 * {@link #effectiveTier()}. 04 § 2.1's {@code materiality_tier} column takes
 * {@link #effectiveTier()} and its {@code tier_basis} takes {@link #describe()}, which names both.
 *
 * <h2>Why the exception is filed and the contract is not quarantined</h2>
 *
 * <p>{@code ExceptionCategory.STALE_EQUIVALENCE_TEST} carries
 * {@code stopsTheContract() == false} — one of only two of the ten categories that does — and
 * {@code FailureIsolation}'s javadoc states the consequence for this exact case: "A demotion belongs
 * on the value path — {@code ExceptionRecord.raise} alongside a successful Tier 2 recomputation."
 * The contract is measurable and is measured, at Tier 2, which is more expensive and more correct
 * than the Tier 3 it asked for. The entry is still raised, because a population silently changing
 * measurement basis between one close and the next is precisely what a close should surface, and it
 * still blocks the close until somebody acknowledges it.
 *
 * @param contractId the contract this permission is about
 * @param proposed   the FR-107 assignment, unaltered
 * @param gateOutcome what {@code EquivalenceTestGate} decided, or null where the proposal was not
 *                   Tier 3 and the gate does not arise
 * @param exception  the queue entry, or null where the ground raises none — which is every
 *                   non-Tier-3 proposal, a permitted Tier 3, and an FR-412 refusal
 */
public record TierPermission(
    String contractId,
    TierAssignmentResult proposed,
    EquivalenceTestOutcome gateOutcome,
    ExceptionRecord exception) {

    public TierPermission {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(proposed, "proposed");
        if (!contractId.equals(proposed.contractId())) {
            throw new IllegalArgumentException(
                "permission names contract " + contractId + " and carries an FR-107 assignment for "
                    + proposed.contractId() + "; a tier basis filed against the wrong contract"
                    + " licenses the wrong approximation");
        }
        if (gateOutcome == null) {
            // THE control. This is the state the pipeline was in before this unit existed: a Tier 3
            // assignment that nobody put to the equivalence test. It is refused structurally rather
            // than reported, because it is a defect in InitialRecognition's own sequencing and not
            // a fact about a contract — the same reasoning OnboardingOutcome applies to a
            // mis-ordered work record.
            if (proposed.requiresEquivalenceTest()) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " was assigned " + proposed.tier() + " by "
                        + proposed.rule() + " and carries no equivalence-test outcome. 03 § 10.2"
                        + " permits Tier 3 only against a current test and FR-412 refuses it"
                        + " outright for zero-coupon and deep-discount instruments at any tenor;"
                        + " reference case 9 measures the straight-line error at 81.0% of year-one"
                        + " income. A Tier 3 measurement with no permission on record is the"
                        + " Cambodia failure mode (08 risk register)");
            }
            if (exception != null) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " raised " + exception.category()
                        + " from a tier gate that was never consulted");
            }
        } else {
            if (!proposed.requiresEquivalenceTest()) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " was assigned " + proposed.tier()
                        + " and carries an equivalence-test outcome anyway. TG-1 asserts that a"
                        + " population taking the Tier 3 shortcut has current evidence for doing"
                        + " so, and a result published for a population that is not taking it is a"
                        + " control that no input can turn into a breach");
            }
            if (gateOutcome.proposedTier() != proposed.tier()) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " was assigned " + proposed.tier()
                        + " and the gate was asked about " + gateOutcome.proposedTier()
                        + "; the gate must dispose of the proposal that was actually made");
            }
            if (gateOutcome.raisesException() != (exception != null)) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " on ground " + gateOutcome.ground()
                        + (exception == null ? " filed no queue entry" : " filed " + exception
                            .category())
                        + ", contradicting the ground. EquivalenceTestOutcome.Ground.raisesException"
                        + " is the single statement of which demotions somebody has to clear");
            }
            if (exception != null && gateOutcome.exception() != exception.category()) {
                throw new IllegalArgumentException(
                    "contract " + contractId + " filed " + exception.category()
                        + " for a gate outcome whose category is " + gateOutcome.exception());
            }
        }
        if (exception != null && !contractId.equals(exception.contractId())) {
            throw new IllegalArgumentException(
                "permission names contract " + contractId + " and carries an exception filed"
                    + " against " + exception.contractId());
        }
    }

    /**
     * A proposal the second gate does not arise for: Tier 1 or Tier 2.
     *
     * <p>No TG-1 result and no queue entry. See the class comment on why a vacuous pass is refused
     * here even though {@code EquivalenceTestGate} publishes one at population level.
     */
    public static TierPermission notRequired(TierAssignmentResult proposed) {
        Objects.requireNonNull(proposed, "proposed");
        return new TierPermission(proposed.contractId(), proposed, null, null);
    }

    /**
     * A Tier 3 proposal put to the gate, with the queue entry the ground calls for.
     *
     * <p>The entry is built here rather than by the caller so that the ground and the entry cannot
     * drift: {@code EquivalenceTestOutcome.Ground.raisesException()} decides whether there is one,
     * and the outcome's own {@code exception()} decides the category. The detail is the gate's
     * recorded basis verbatim — it already names the population, the rule, the dates and the
     * measured excess, and paraphrasing it here would put a second, worse sentence in the queue.
     *
     * @param runId      the run the entry is stamped with (04 § 2.13)
     * @param payloadRef the deterministic payload reference; 03 § 4.3 makes the stored input the
     *                   only diagnostic a value-path failure will ever have
     */
    public static TierPermission gated(
        TierAssignmentResult proposed, EquivalenceTestOutcome gateOutcome, String runId,
        String payloadRef) {

        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(gateOutcome, "gateOutcome");
        ExceptionRecord entry = gateOutcome.raisesException()
            ? ExceptionRecord.raise(proposed.contractId(), runId, gateOutcome.exception(),
                InvariantId.TG_1 + " (" + InvariantId.TG_1.statement() + ") breached — "
                    + gateOutcome.basis() + ". Measured at " + gateOutcome.effectiveTier()
                    + " instead of the proposed " + gateOutcome.proposedTier()
                    + "; the contract is not quarantined, because 03 § 10.2's consequence is a"
                    + " demotion to a more expensive and more correct basis, not a stop",
                payloadRef)
            : null;
        return new TierPermission(proposed.contractId(), proposed, gateOutcome, entry);
    }

    /** Whether the equivalence test arose for this contract at all. */
    public boolean gateConsulted() {
        return gateOutcome != null;
    }

    /**
     * The tier that must actually be measured — and the tier that must reach
     * {@code SolverTolerance.forTier}.
     *
     * <p>The proposed tier where the gate did not arise or permitted it, {@code TIER_2} where it
     * demoted. A pipeline that read {@link #proposed()} here instead would have applied the whole
     * gate and then thrown the answer away, which is a more elaborate version of the defect this
     * type closes.
     */
    public MaterialityTier effectiveTier() {
        return gateOutcome == null ? proposed.tier() : gateOutcome.effectiveTier();
    }

    /** Whether the proposed tier was refused. */
    public boolean demoted() {
        return effectiveTier() != proposed.tier();
    }

    /**
     * The TG-1 result, or empty where the invariant has no subject.
     *
     * <p>Empty for every Tier 1 and Tier 2 contract. Present — and always carrying
     * {@link InvariantId#TG_1}, which {@code EquivalenceTestOutcome} enforces at construction — for
     * every Tier 3 proposal. Note that an FR-412 refusal is a TG-1 <b>pass</b>: the shortcut was not
     * taken, so the test was neither passed nor failed and there is nothing for TG-1 to be untrue
     * about. {@code EquivalenceTestOutcome.Ground} argues that at length.
     */
    public Optional<InvariantResult> tierGateResult() {
        return gateOutcome == null ? Optional.empty() : Optional.of(gateOutcome.tierGateResult());
    }

    /** The queue entry, or empty where the decision raises none. */
    public Optional<ExceptionRecord> failure() {
        return Optional.ofNullable(exception);
    }

    /** The population the permission was evidenced at, or empty where the gate did not arise. */
    public Optional<String> populationId() {
        return gateOutcome == null ? Optional.empty() : Optional.of(gateOutcome.populationId());
    }

    /**
     * The sentence 04 § 2.1's {@code tier_basis} column holds: the FR-107 assignment, and what the
     * second gate did with it.
     *
     * <p>Both halves, always. FR-107 asks for the tier "and the basis", and a basis that recorded
     * only the demotion would lose which § 10 row proposed Tier 3 in the first place — which is the
     * thing a Board reviewing the FR-412 refusals actually needs, because a rule proposing Tier 3
     * for instruments FR-412 refuses is an amendment to § 10's table rather than a per-contract
     * event.
     */
    public String describe() {
        if (gateOutcome == null) {
            return proposed.describe();
        }
        return proposed.describe() + "; TG-1 gate: " + gateOutcome.describe();
    }

    @Override
    public String toString() {
        return describe();
    }
}
