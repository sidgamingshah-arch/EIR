package com.crisil.eir.calc.projection;

import java.util.List;

/**
 * Projects contract terms into the flow vectors the solver consumes.
 *
 * <p>Projection is a separate, independently testable stage: the solver never
 * reads contract terms, and a projector never solves a rate. That boundary is
 * what makes the nine reference cases checkable figure by figure — a wrong number
 * is either a projection defect or a solver defect, never ambiguously both.
 *
 * <p>An implementation is a value with configuration, not a service: it holds the
 * policy elections that belong to it (a residue policy, a revolving
 * approximation, a repricing interval, a supplied schedule) and is otherwise
 * stateless, deterministic, and free of any clock. Given the same terms and
 * postings it must produce the same vectors forever, because a closed period gets
 * re-run and must reproduce its published figures bit-identically (invariant
 * DT-1).
 */
public interface CashflowProjector {

    /**
     * Whether this projector can project these terms.
     *
     * <p>Implementations answer on the shape <em>and</em> on the features they
     * need — a moratorium, a step factor, a balloon — so that selection does not
     * depend on registry ordering. Two projectors both claiming the same terms is
     * a configuration error the registry cannot detect; neither claiming them is
     * one it can.
     */
    boolean supports(ContractTerms terms);

    /**
     * Projects both legs.
     *
     * <p>Only {@code INTEGRAL} postings enter the initial carrying amount.
     * Contingent charges are excluded from the inception projection regardless of
     * being contractually specified (FR-206), and every implementation asserts
     * that on its output before returning it.
     *
     * @param terms the contract terms
     * @param fees  the resolved fee and cost postings; may be empty, never null
     */
    ProjectionResult project(ContractTerms terms, List<FeePosting> fees);

    /** A short label for the computation trace and for registry diagnostics. */
    default String label() {
        return getClass().getSimpleName();
    }
}
