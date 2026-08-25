package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.MaterialityTier;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The materiality tier gate: assigns every contract a {@link MaterialityTier} per 03 § 10 and
 * records the basis (FR-107).
 *
 * <p>Until now nothing in the engine computed a tier — callers passed one in, and
 * {@code SolverTolerance.forTier} in {@code eir-calc} was its only production consumer. This is
 * the computation. It is the whole of § 10's table and nothing else: the tier selects the
 * computation strategy, and the strategies themselves (pool EIR under ACPIR 51, the straight-line
 * approximation, the equivalence test that licenses it) live elsewhere.
 *
 * <p><b>Order of evaluation is the design.</b> § 10's three rows are populations, not branches of
 * a decision tree, and they overlap: a six-month exposure inside a hedge relationship sits in the
 * Tier 1 row and the Tier 3 row simultaneously. The Tier 1 limbs are stated with no tenor
 * condition, so they are overrides and are tested first; the tenor rule decides what is left. Get
 * that backwards and the engine applies a straight-line approximation to a hedged instrument
 * without anything looking wrong — no exception, no invariant breach, a plausible tier on the
 * record — which is the shape of the failure the whole gate exists to prevent. The ladder is
 * declared once, in {@link TierAssignmentRule}, in the order it is applied, and
 * {@link TierAssignmentResult#suppressedRules()} publishes what the winner beat so that the order
 * is auditable from the output rather than only from this file.
 *
 * <p><b>Recording the basis is half of FR-107, not a courtesy.</b> ADR-0005 makes tier assignment
 * "a governed decision with a recorded basis (FR-107) — a policy artefact requiring
 * maker–checker", and 04 § 2.1 pairs {@code materiality_tier} with {@code tier_basis} for the same
 * reason. So {@link #assign} returns a {@link TierAssignmentResult} and there is deliberately no
 * overload returning a bare enum: an assignment whose reasoning was discarded at the call site
 * cannot be defended later, and "we approximated because it was immaterial", per § 10.2, "is a
 * complete answer only when the materiality assessment exists on paper with a number attached".
 *
 * <p><b>Where this unit stops.</b> It assigns and explains. It does not decide whether a Tier 3
 * assignment may be <em>used</em>: that is the equivalence test (§ 10.2, invariant TG-1, FR-411
 * and FR-412), which refuses the approximation outright for zero-coupon and deep-discount
 * instruments at any tenor and demotes any population whose test is out of date. A Tier 3 handed
 * out here can therefore be refused there, and that is the correct division — the tenor rule fired
 * correctly on the tenor it was given, and the permission is a separate gate with its own
 * evidence.
 *
 * <p>Stateless and deterministic. The same input under the same policy version yields the same
 * result and the same basis string, which is what invariant DT-1 requires of a closed period
 * replayed.
 */
public final class TierAssignment {

    private final PolicyVersion policyVersion;
    private final Money wholesaleBoardThreshold;

    /**
     * @param policyVersion           the governing {@link PolicyKind#TIER_ASSIGNMENT} version
     * @param wholesaleBoardThreshold the § 10 Tier 1 threshold this version puts in force
     */
    public TierAssignment(PolicyVersion policyVersion, Money wholesaleBoardThreshold) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(wholesaleBoardThreshold, "wholesaleBoardThreshold");
        if (policyVersion.kind() != PolicyKind.TIER_ASSIGNMENT) {
            // Wrong kind is a wiring defect, not a data condition. A tier assigned under a fee
            // rule set's version number is unreplayable: the id on the row points at a version
            // whose history has nothing to do with the threshold that was applied.
            throw new IllegalArgumentException(
                "tier assignment must be governed by a " + PolicyKind.TIER_ASSIGNMENT
                    + " version, got " + policyVersion.kind() + " (" + policyVersion.id() + ")");
        }
        if (wholesaleBoardThreshold.isNegative()) {
            throw new IllegalArgumentException(
                "wholesale Board threshold must not be negative, got " + wholesaleBoardThreshold);
        }
        this.policyVersion = policyVersion;
        this.wholesaleBoardThreshold = wholesaleBoardThreshold;
    }

    /**
     * Named factory, for call sites where the two arguments read better labelled.
     *
     * <p>There is no no-argument or default-threshold factory, and that absence is deliberate.
     * The threshold separating Tier 1 from the rest is a Board decision with a date and an
     * approver — which is why {@link PolicyKind#TIER_ASSIGNMENT} exists as a versioned kind at
     * all — and a convenience default would be an unapproved policy figure classifying live
     * exposures under a version id that never mentioned it.
     */
    public static TierAssignment under(PolicyVersion policyVersion, Money wholesaleBoardThreshold) {
        return new TierAssignment(policyVersion, wholesaleBoardThreshold);
    }

    /**
     * Assigns {@code input} to a tier and records why.
     *
     * <p>Walks {@link TierAssignmentRule#values()} in declaration order, collects every rule that
     * matches, and takes the first as decisive. Collecting rather than short-circuiting costs one
     * pass over twelve enum constants and buys the audit record: the assignment can then state
     * that the hedge override fired <em>and</em> that the short-tenor rule matched and was
     * overruled, which is the sentence that evidences the evaluation order. Short-circuiting would
     * make the two indistinguishable on the record.
     *
     * <p>Where nothing matches, {@link TierAssignmentRule#TIER_1_DEFAULT_CONTRACT_LEVEL} answers
     * with ADR-0005's default of contract-level measurement. That is not a Tier 3 fallback and must
     * never become one: Tier 3 is a permission granted against a current equivalence test, and a
     * fallback cannot grant a permission nobody has evidenced.
     */
    public TierAssignmentResult assign(TierAssignmentInput input) {
        Objects.requireNonNull(input, "input");
        List<TierAssignmentRule> matched = new ArrayList<>();
        for (TierAssignmentRule rule : TierAssignmentRule.values()) {
            if (rule.fires(input, wholesaleBoardThreshold)) {
                matched.add(rule);
            }
        }
        TierAssignmentRule decisive = matched.isEmpty()
            ? TierAssignmentRule.TIER_1_DEFAULT_CONTRACT_LEVEL
            : matched.get(0);
        List<TierAssignmentRule> suppressed = matched.isEmpty()
            ? List.of()
            : List.copyOf(matched.subList(1, matched.size()));
        return new TierAssignmentResult(
            input.contractId(),
            decisive.tier(),
            decisive,
            decisive.firedOn(input, wholesaleBoardThreshold),
            suppressed,
            policyVersion.id(),
            !policyVersion.status().isOperative());
    }

    /**
     * Assigns a batch, preserving order.
     *
     * <p>One method for the ten-million-contract run of FR-905, and it returns results rather than
     * throwing on the first oddity for the same reason the invariant checks return results: a run
     * that stops at the first unreadable tenor classifies nothing, while a run that records
     * {@link TierAssignmentResult#isResidualDefault()} against those contracts classifies
     * everything conservatively and hands the data-quality problem to a control report.
     */
    public List<TierAssignmentResult> assignAll(List<TierAssignmentInput> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        List<TierAssignmentResult> results = new ArrayList<>(inputs.size());
        for (TierAssignmentInput input : inputs) {
            results.add(assign(input));
        }
        return List.copyOf(results);
    }

    /**
     * Whether the governing version is in force on {@code date}.
     *
     * <p>Delegated to {@link PolicyVersion#isEffectiveOn}; selecting <em>which</em> version governs
     * a given run date is the policy registry's job, not this class's. Asked rather than enforced
     * inside {@link #assign} on purpose: FR-210 requires a quantified impact preview before a
     * threshold change goes effective, and a preview is by definition a run under a version that
     * is not yet operative. Refusing to assign under a non-operative version would block the
     * control it was meant to serve, so the assignment proceeds and is marked
     * {@link TierAssignmentResult#provisional()} instead — visible on the record, and never
     * mistakable for a governing assignment.
     */
    public boolean governs(LocalDate date) {
        return policyVersion.isEffectiveOn(date);
    }

    /** The § 10 Tier 1 wholesale threshold in force under this version. */
    public Money wholesaleBoardThreshold() {
        return wholesaleBoardThreshold;
    }

    /** The governing version, for callers persisting the approval trail alongside the tier. */
    public PolicyVersion policyVersion() {
        return policyVersion;
    }

    /** One audit line naming the threshold and the version that approved it. */
    public String describe() {
        return "tier gate (03 § 10) with wholesale Board threshold "
            + wholesaleBoardThreshold.atPresentationScale() + " under " + policyVersion.describe();
    }
}
