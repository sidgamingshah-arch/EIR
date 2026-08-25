package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.MaterialityTier;
import java.util.List;
import java.util.Objects;

/**
 * One contract's tier and <b>why</b> — the second half of FR-107, which asks for the tier "and the
 * basis", and 04 § 2.1's paired {@code materiality_tier} / {@code tier_basis} columns.
 *
 * <p><b>Why the return type is not the enum.</b> A bare {@link MaterialityTier} is a conclusion
 * with its reasoning discarded, and the reasoning is the controlled artefact: § 10 permits Tier 3
 * "only against a current equivalence test", and ADR-0005 makes tier assignment "a governed
 * decision with a recorded basis (FR-107) — a policy artefact requiring maker–checker". An
 * auditor's question is never which tier the engine chose but on what. A Tier 3 with no recorded
 * basis is indistinguishable from the roadmap risk register's Cambodia failure mode: an
 * undocumented shortcut applied to products that need a full solver.
 *
 * <p>{@link #suppressedRules} is the part that could most easily have been left out and is the
 * part that proves the ordering. A six-month hedged exposure records Tier 1 by the hedge override
 * <em>and</em> records that the short-tenor rule matched and lost. Without it the record cannot
 * distinguish an exposure the tenor rule never considered from one it considered and was overruled
 * on, and the evaluation order — the thing most likely to be got wrong — becomes unauditable from
 * the output.
 *
 * @param contractId       the contract assigned
 * @param tier             the assigned tier; always {@code rule.tier()}
 * @param rule             the rule that decided, first match in {@link TierAssignmentRule} order
 * @param basis            what the deciding rule fired on, with the value it fired on
 * @param suppressedRules  rules that also matched but lost on precedence, in evaluation order
 * @param policyVersionId  the {@code TIER_ASSIGNMENT} policy version that governed this assignment
 * @param provisional      true when that version is not yet operative — an FR-210 impact preview
 *                         rather than a governing assignment
 */
public record TierAssignmentResult(
    String contractId,
    MaterialityTier tier,
    TierAssignmentRule rule,
    String basis,
    List<TierAssignmentRule> suppressedRules,
    String policyVersionId,
    boolean provisional) {

    public TierAssignmentResult {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(suppressedRules, "suppressedRules");
        if (basis.strip().isEmpty()) {
            throw new IllegalArgumentException(
                "tier basis must not be blank; FR-107 requires the basis recorded, and a tier with"
                    + " no basis is the undocumented shortcut the tier gate exists to prevent");
        }
        if (policyVersionId.strip().isEmpty()) {
            throw new IllegalArgumentException(
                "policyVersionId must not be blank; an assignment that cannot name the threshold"
                    + " version that governed it cannot be replayed (invariant DT-1)");
        }
        if (tier != rule.tier()) {
            // A defect in this package, not a data condition: the tier and the rule that assigned
            // it cannot disagree, or the persisted materiality_tier stops meaning what tier_basis
            // says it means and every downstream reconciliation of the two is meaningless.
            throw new IllegalArgumentException(
                "rule " + rule + " assigns " + rule.tier() + " but the result carries " + tier);
        }
        if (suppressedRules.contains(rule)) {
            throw new IllegalArgumentException(
                "deciding rule " + rule + " must not also be listed as suppressed");
        }
        suppressedRules = List.copyOf(suppressedRules);
    }

    /**
     * Whether the assignment is only usable once the equivalence test has passed.
     *
     * <p>True for Tier 3, always. <b>This does not assert that Tier 3 is permitted</b> — this unit
     * assigns the tier and records why; whether the approximation may actually be used is the
     * equivalence test's question (03 § 10.2, FR-411, FR-412, invariant TG-1), evaluated
     * separately in this package. That separation is deliberate and the division has a
     * consequence worth stating plainly: a Tier 3 proposed here can be <em>refused</em> there.
     * § 10.3 refuses it outright for zero-coupon and deep-discount instruments at any tenor —
     * reference case 9's 15-year zero-coupon at 8% overstates year-one income by 81.0% on the
     * straight-line basis and understates the final year by 38.4% — and § 10.2 demotes any Tier 3
     * population whose test is out of date to Tier 2. Neither is a correction of the assignment.
     * The tenor rule fired on the tenor it was given; the permission is a second gate.
     */
    public boolean requiresEquivalenceTest() {
        return tier == MaterialityTier.TIER_3;
    }

    /**
     * Whether the solver must run on the tightened tolerance.
     *
     * <p>Delegated to {@link MaterialityTier#requiresTightenedTolerance()} rather than restated,
     * because {@code SolverTolerance.forTier} in {@code eir-calc} reads the same method and the
     * two answers must be the same one. This is the whole downstream consequence of the
     * assignment today: Tier 1 moves the residual floor from 1e-10 to 1e-12 and the relative
     * factor from 1e-16 to 1e-18, which matters because ACPIR 50 makes the EIR the ECL discount
     * rate and a rate error on a 20-year exposure propagates into lifetime ECL.
     */
    public boolean requiresTightenedTolerance() {
        return tier.requiresTightenedTolerance();
    }

    /**
     * Whether no row of § 10's table applied and the ADR-0005 contract-level default answered.
     *
     * <p>Worth counting rather than merely recording. A rising count of
     * {@link TierAssignmentRule#TIER_1_TENOR_NOT_DETERMINABLE} is a data-sourcing problem in the
     * tenor field, which the 08 risk register puts on the critical path; a large stable count of
     * {@link TierAssignmentRule#TIER_1_DEFAULT_CONTRACT_LEVEL} is instead a population § 10's
     * table does not describe, and the answer to that is an amended table, not a code change.
     */
    public boolean isResidualDefault() {
        return rule.isResidualDefault();
    }

    /**
     * Whether a § 10 Tier 1 override decided this, beating a tenor rule that also matched.
     *
     * <p>The exact case the evaluation order exists for: true only where the override fired
     * <em>and</em> something later would otherwise have taken the contract to Tier 2 or Tier 3.
     *
     * <p>The second condition is "a suppressed rule that is not itself an override", not merely
     * "something was suppressed". A POCI project-finance loan suppresses one Tier 1 override with
     * another and no tenor rule was ever in contention; reporting that as an override of the tenor
     * rule would inflate the count of the one case this is meant to make visible.
     */
    public boolean overrodeATenorRule() {
        return rule.isOverride() && suppressedRules.stream().anyMatch(other -> !other.isOverride());
    }

    /**
     * The audit sentence, and what 04 § 2.1's {@code tier_basis} column holds.
     *
     * <p>Composed rather than stored so that it cannot drift from the fields it describes. It
     * names the rule, the § 10 row behind it, the value it fired on, every rule it overrode, and
     * the policy version whose Board threshold was in force — which is what makes a closed period
     * replayable under the threshold it closed on rather than under today's (ADR-0006, invariant
     * DT-1).
     */
    public String describe() {
        StringBuilder sentence = new StringBuilder()
            .append(contractId).append(": ").append(tier)
            .append(" by ").append(rule)
            .append(" [").append(rule.specRow()).append("] — ")
            .append(basis);
        if (!suppressedRules.isEmpty()) {
            sentence.append("; also matched and lost on precedence: ");
            for (int i = 0; i < suppressedRules.size(); i++) {
                if (i > 0) {
                    sentence.append(" and ");
                }
                TierAssignmentRule suppressed = suppressedRules.get(i);
                sentence.append(suppressed).append(" (").append(suppressed.tier()).append(')');
            }
        }
        sentence.append("; per policy version ").append(policyVersionId);
        if (provisional) {
            sentence.append(" (PROVISIONAL — version not yet operative, impact preview only)");
        }
        if (requiresEquivalenceTest()) {
            sentence.append("; Tier 3 permitted only against a current equivalence test (TG-1)");
        }
        return sentence.toString();
    }
}
