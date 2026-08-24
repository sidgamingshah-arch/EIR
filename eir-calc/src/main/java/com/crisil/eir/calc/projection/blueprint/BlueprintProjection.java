package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.calc.projection.ConventionSelector;
import com.crisil.eir.calc.projection.ProjectionResult;
import com.crisil.eir.domain.InvariantResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Everything one run of the blueprint pipeline produced, not only the part the
 * existing {@link ProjectionResult} has a field for.
 *
 * <p>{@code ProjectionResult} is the contract the solver, the amortisation engine and
 * every Phase 1 test already consume, and it is deliberately not widened — a bridge
 * that changes the far bank is not a bridge. But three of the five stages produce
 * something a controller needs and that record has nowhere to put:
 *
 * <ul>
 *   <li>the <b>contractual ladder</b>, which is what reconciles to the lending
 *       system. The vector reconciles to nothing: a reviewer handed a list of signed
 *       amounts cannot tell a 47,073.47 instalment from a 46,607.46 principal plus a
 *       466.07 interest line that happen to sum to it.
 *   <li>the <b>expected-life determination</b>, which carries every exercise policy
 *       computed and the divergence between them. On a callable bond that divergence
 *       is 49.1 bp in one direction at a premium and 52.3 bp in the other at a
 *       discount, and the whole reason the engine computes both is so the number goes
 *       in front of the committee rather than being decided globally on prudence
 *       grounds.
 *   <li>the <b>behavioural adjustment</b>, which carries the recorded basis of the
 *       expectation. 09 § 2.7 is explicit that an assumption without its derivation
 *       and an absence of assumption are indistinguishable in an audit file even when
 *       they produce identical figures.
 * </ul>
 *
 * <p>So the projector returns this, and {@link #projection()} is the narrow view the
 * existing callers take. Nothing is recomputed here: every field is the value a stage
 * returned, and {@link #invariants()} is the union of what each of them asserted.
 *
 * @param projection        the bridged result, IC-1 computed inside it
 * @param contractualLadder the contractual schedule, before optionality or behaviour
 * @param expectedLife      the chosen policy, every alternative costed, and the
 *     divergence quantified; absent where the blueprint carries no optionality and no
 *     solver was configured — see {@code BlueprintProjector}
 * @param behaviour         the overlay applied and its recorded basis; absent where
 *     the expected leg was not derived from a ladder
 * @param assembly          the contractual assembly, carrying the amount advanced at
 *     inception and the initial carrying amount the projection was built on
 * @param conventionChoice  the convention and the basis on which it was chosen
 */
public record BlueprintProjection(
    ProjectionResult projection,
    InstalmentLadder contractualLadder,
    ExpectedLifeDetermination expectedLife,
    BehaviouralAdjustment behaviour,
    FlowVectorAssembler.Assembly assembly,
    ConventionSelector.Choice conventionChoice) {

    public BlueprintProjection {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(contractualLadder, "contractualLadder");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(conventionChoice, "conventionChoice");
    }

    /** Whether an expected-life determination was made at all. */
    public boolean hasExpectedLife() {
        return expectedLife != null;
    }

    /** Whether a behavioural overlay was applied to a ladder. */
    public boolean hasBehaviouralAdjustment() {
        return behaviour != null;
    }

    /**
     * The widest divergence across the computed exercise policies, in basis points of
     * effective annual rate.
     *
     * <p>Zero where no determination was made or only one policy was costable. On an
     * optioned instrument that zero is the ST-7 finding rather than the answer, and
     * {@link #invariants()} carries the failure that says so.
     */
    public BigDecimal optionalityDivergenceBps() {
        return expectedLife == null ? BigDecimal.ZERO : expectedLife.widestDivergenceBps();
    }

    /**
     * Every invariant any stage asserted, in pipeline order: IC-1 first because
     * {@link ProjectionResult} computes it at construction, then ST-3 and ST-5 from
     * the ladder, ST-6 from the assembly, ST-7 and ST-8 from the determination, ST-3
     * again across the behavioural legs, then PC-1 and ST-10.
     *
     * <p>Read off the bridged result rather than re-gathered from the stages. Every
     * stage's assertions are handed to {@code ProjectionResult} on the way in
     * precisely so that a caller holding only the narrow view is not blind to a ladder
     * that does not tie — and gathering them twice would let the two lists disagree
     * about what was asserted, which is worse than either list alone.
     *
     * <p>Ordered by stage rather than by identifier so that the first breach is the
     * earliest stage that went wrong. A reviewer seeing ST-7 fail after ST-3 has failed
     * should be looking at the ladder, and an ordering by identifier would have hidden
     * that.
     */
    public List<InvariantResult> invariants() {
        return projection.invariants();
    }

    public boolean allInvariantsSatisfied() {
        return projection.allInvariantsSatisfied();
    }

    /** Throws on the first breach, in pipeline order. */
    public BlueprintProjection requireInvariantsSatisfied() {
        projection.requireInvariantsSatisfied();
        return this;
    }
}
