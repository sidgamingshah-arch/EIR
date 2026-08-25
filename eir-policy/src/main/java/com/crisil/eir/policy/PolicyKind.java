package com.crisil.eir.policy;

/**
 * What a policy version governs.
 *
 * <p>Versioned separately per kind rather than as one monolithic "policy version", because the
 * kinds move on entirely different clocks and for different reasons. A fee rule set changes
 * when the bank reprices a product; a routing table changes when the IASB amends B5.4.5
 * (roadmap Phase 7) — which is the whole point of ADR-0006. Binding them into one version
 * number would force a fee repricing to re-approve the routing table and vice versa, and every
 * such forced re-approval is an invitation to approve without reading.
 *
 * <p>Each kind names the requirement that makes it versioned, because "why is this
 * configuration rather than code" is the first question an auditor asks about a policy engine.
 */
public enum PolicyKind {

    /**
     * Fee and cost classification, keyed on {@code (fee_code, product, entity, effective_date)}.
     * FR-201. The longest-lead item in the programme (roadmap risk register).
     */
    FEE_RULE_SET,

    /**
     * Driver-to-mechanism routing. FR-504, ADR-0006. Versioned so that an IASB amendment to
     * B5.4.5 is a table change and not a re-engineering event.
     */
    ROUTING_TABLE,

    /**
     * Materiality tier assignment and its thresholds. FR-107, spec 03 § 10. Versioned because
     * the Board threshold that separates Tier 1 from Tier 2 is a Board decision with a date.
     */
    TIER_ASSIGNMENT,

    /**
     * Pool definitions and homogeneity criteria for Tier 2. Spec 03 § 10.1 requires pool
     * definition to be "a versioned, approved artefact with explicit homogeneity criteria".
     */
    POOL_DEFINITION,

    /**
     * Behavioural curves — prepayment speeds, expected life, utilisation. Versioned because
     * ACPIR Chapter V requires independent validation, and because a curve revision moves
     * recognised income: the UK restatement pattern carries 3.73x leverage on year-one fee
     * recognition (roadmap risk register).
     */
    BEHAVIOURAL_CURVE,

    /**
     * Commitment-fee drawdown-probability thresholds, per product. FR-204.
     */
    COMMITMENT_THRESHOLD;

    /**
     * Whether a change of this kind moves already-recognised income and therefore cannot go
     * effective on a portfolio without a quantified preview first.
     *
     * <p>All of them do, today. The method exists rather than a constant {@code true} because
     * the impact-preview gate should ask the question of the kind rather than assume the
     * answer — a future kind governing, say, a reporting label would answer false, and the
     * gate reading a hard-coded true would then demand a preview nobody can produce.
     */
    public boolean movesRecognisedIncome() {
        return true;
    }
}
