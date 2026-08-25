package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.util.Objects;

/**
 * What the tier gate decided, and why: the tier that will actually be measured, the TG-1 result,
 * and the sentence that goes into {@code CONTRACT.tier_basis} (04 § 2.1, FR-107).
 *
 * <p>Four things a caller needs and none of them is derivable from the others:
 *
 * <ul>
 *   <li>{@link #effectiveTier()} — what to measure. This is the answer.
 *   <li>{@link #ground()} — which rule produced it, switchable rather than parsed out of prose.
 *   <li>{@link #tierGateResult()} — the TG-1 invariant result, for the aggregate control report
 *       of 05 § 5's period-close batch.
 *   <li>{@link #exception()} — a queue entry, where there is one. Not every demotion is an
 *       exception; see {@link Ground#raisesException()}.
 * </ul>
 *
 * <p>The record enforces the coherence of the four at construction rather than trusting the gate
 * to keep them aligned, because the three-way relationship between ground, tier and invariant
 * result is the design decision of this unit and not an implementation convenience. In
 * particular it is what makes an FR-412 refusal a TG-1 <em>pass</em> — see {@link Ground} — and
 * a type that permitted the other combination would let that decision be quietly reversed by a
 * later edit to the gate.
 *
 * @param populationId    the population gated
 * @param proposedTier    what FR-107 assignment proposed
 * @param effectiveTier   what will be measured
 * @param ground          which rule decided it
 * @param tierGateResult  the TG-1 result; always carries {@link InvariantId#TG_1}
 * @param exception       the queue category, or null where the decision raises none
 * @param basis           the recorded basis, one sentence, for the audit trail
 */
public record EquivalenceTestOutcome(
    String populationId,
    MaterialityTier proposedTier,
    MaterialityTier effectiveTier,
    EquivalenceTestOutcome.Ground ground,
    InvariantResult tierGateResult,
    ExceptionCategory exception,
    String basis) {

    /**
     * The mutually exclusive grounds on which the gate can dispose of a proposed tier.
     *
     * <p><b>On why an FR-412 refusal does not breach TG-1.</b> {@code Case 9} says of a
     * zero-coupon instrument that "the Tier 3 equivalence test cannot be passed here, because
     * the error is not small at any horizon", and the tempting reading is that TG-1 should
     * therefore fail. It should not, and the distinction is worth being precise about. TG-1's
     * statement is "Tier 3 equivalence test in date" — it asserts that a population <em>taking
     * the shortcut</em> has current evidence for doing so. Under
     * {@link #FORBIDDEN_APPROXIMATION} the shortcut is not taken: the gate refuses Tier 3
     * before any test is consulted, so the test is neither passed nor failed and there is
     * nothing for TG-1 to be untrue about. Reporting a breach there would block a period close
     * (09 § 9: an invariant breach raises a control exception and blocks the close) on a
     * population where the engine did exactly the right thing, and would bury the genuine TG-1
     * breaches — the populations where somebody forgot to re-perform — in a list of correct
     * refusals.
     */
    public enum Ground {

        /**
         * Not a Tier 3 proposal, so the gate has nothing to gate. TG-1 passes vacuously: it is
         * a claim about Tier 3 populations and this is not one.
         */
        NOT_TIER_3(false, false, false),

        /**
         * FR-412: zero-coupon or deep-discount, refused at any tenor. Demotes to Tier 2 and is
         * <em>not</em> curable by an equivalence test, however current — see 03 § 10.3 and the
         * class comment above.
         */
        FORBIDDEN_APPROXIMATION(true, false, false),

        /** No equivalence test exists for the population at all. TG-1 fails (03 § 10.2). */
        NO_TEST_ON_FILE(true, true, true),

        /**
         * The only tests on file were performed after the reporting date. Treated as no
         * evidence rather than as evidence: a test dated 2028-06-30 says nothing about whether
         * the shortcut was defensible when the March close was struck, and admitting it would
         * break DT-1 — a replay of a closed period would now permit a Tier 3 measurement that
         * the original run demoted, so the replay would not reproduce the published figures.
         */
        TEST_POSTDATED(true, true, true),

        /** The latest test is older than its annual window. FR-411's named consequence. */
        TEST_STALE(true, true, true),

        /**
         * The latest test is in date but its documented delta exceeds the Board-approved
         * threshold (03 § 10.2 item 2). An in-date test that failed is not permission; it is
         * evidence that the shortcut does not hold for this population.
         */
        TEST_OVER_THRESHOLD(true, true, true),

        /** A current test, within threshold. The shortcut of FR-411 is permitted. */
        TIER_3_PERMITTED(false, false, false);

        private final boolean demotes;
        private final boolean breachesTierGate;
        private final boolean raisesException;

        Ground(boolean demotes, boolean breachesTierGate, boolean raisesException) {
            this.demotes = demotes;
            this.breachesTierGate = breachesTierGate;
            this.raisesException = raisesException;
        }

        /** Whether this ground forces measurement at Tier 2 instead of the proposed Tier 3. */
        public boolean demotes() {
            return demotes;
        }

        /** Whether this ground is a TG-1 breach, as against a correct refusal. */
        public boolean breachesTierGate() {
            return breachesTierGate;
        }

        /**
         * Whether this ground puts an entry in the exception queue.
         *
         * <p>Exactly the TG-1 breaches. An FR-412 refusal does not, and the asymmetry is the
         * point: 04 § 3's queue is for contracts the engine could not compute or was fed an
         * input it refuses to trust, and neither describes a zero-coupon bond correctly
         * assigned to Tier 2 by a deterministic policy rule. The four grounds that do raise one
         * all say the same thing — a population is taking, or was going to take, a shortcut
         * nobody has current evidence for — and that is a control failure somebody has to
         * clear.
         */
        public boolean raisesException() {
            return raisesException;
        }
    }

    public EquivalenceTestOutcome {
        Objects.requireNonNull(populationId, "populationId");
        Objects.requireNonNull(proposedTier, "proposedTier");
        Objects.requireNonNull(effectiveTier, "effectiveTier");
        Objects.requireNonNull(ground, "ground");
        Objects.requireNonNull(tierGateResult, "tierGateResult");
        Objects.requireNonNull(basis, "basis");
        if (tierGateResult.id() != InvariantId.TG_1) {
            throw new IllegalArgumentException(
                "a tier-gate outcome carries the TG-1 result, got " + tierGateResult.id()
                    + "; anything resolving TG-1 by name would read the wrong invariant");
        }
        if (ground == Ground.NOT_TIER_3 && proposedTier == MaterialityTier.TIER_3) {
            throw new IllegalArgumentException(
                "population " + populationId + " proposed TIER_3 and was disposed of as"
                    + " NOT_TIER_3");
        }
        if (ground.demotes() && effectiveTier != MaterialityTier.TIER_2) {
            throw new IllegalArgumentException(
                "population " + populationId + " was demoted on ground " + ground + " to "
                    + effectiveTier + "; FR-411 and 03 § 10.2 name Tier 2 as the consequence,"
                    + " and a demotion to anything else is a different rule");
        }
        if (!ground.demotes() && effectiveTier != proposedTier) {
            throw new IllegalArgumentException(
                "population " + populationId + " was not demoted yet moved from " + proposedTier
                    + " to " + effectiveTier);
        }
        if (ground.breachesTierGate() == tierGateResult.satisfied()) {
            throw new IllegalArgumentException(
                "population " + populationId + " on ground " + ground + " reports TG-1 "
                    + (tierGateResult.satisfied() ? "satisfied" : "breached")
                    + "; the ground and the invariant result must agree, because one of them"
                    + " goes into the control report and the other into the tier basis");
        }
        if (ground.raisesException() != (exception != null)) {
            throw new IllegalArgumentException(
                "population " + populationId + " on ground " + ground
                    + (exception == null ? " raises no exception" : " raises " + exception)
                    + ", contradicting the ground");
        }
        if (exception != null) {
            if (exception != ExceptionCategory.STALE_EQUIVALENCE_TEST) {
                throw new IllegalArgumentException(
                    "population " + populationId + " raised " + exception
                        + "; the tier gate's only queue category is STALE_EQUIVALENCE_TEST");
            }
            // Asserted rather than assumed. The whole reason this evaluator returns an
            // InvariantResult instead of throwing is that the specified consequence is a
            // demotion to a more expensive and correct measurement, not a stop, and
            // ExceptionCategory records that as data. If that ever flipped, this unit's
            // contract would have changed underneath it.
            if (exception.stopsTheContract()) {
                throw new IllegalArgumentException(
                    "STALE_EQUIVALENCE_TEST now stops the contract; the tier gate demotes to"
                        + " Tier 2 and must not quarantine a contract that is measurable");
            }
        }
        if (basis.isBlank()) {
            throw new IllegalArgumentException(
                "population " + populationId + " has no recorded tier basis; FR-107 requires the"
                    + " basis, not only the tier");
        }
    }

    /** Whether the proposed tier was refused. */
    public boolean demoted() {
        return effectiveTier != proposedTier;
    }

    /** Whether the Tier 3 shortcut of FR-411 may be applied. */
    public boolean tier3Permitted() {
        return effectiveTier == MaterialityTier.TIER_3;
    }

    /** Whether this outcome puts an entry in the 04 § 3 exception queue. */
    public boolean raisesException() {
        return exception != null;
    }

    /** A one-line audit sentence: what was proposed, what will be measured, and why. */
    public String describe() {
        return populationId + ": proposed " + proposedTier + ", measured " + effectiveTier
            + " — " + basis
            + (exception == null ? "" : " [exception " + exception + "]");
    }
}
