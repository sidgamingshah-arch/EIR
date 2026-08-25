package com.crisil.eir.policy.fee.rule;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * Inputs shared by the fee rule set tests. Inputs only — no expected outcome is computed here, so
 * that no test can pass by agreeing with a fixture that was itself derived from the resolver.
 *
 * <h2>The four-rank rule set, and why its classifications are accounting nonsense</h2>
 *
 * <p>{@link #fourRanks()} maps each of the four specificity shapes to a <em>different</em> one of
 * the five FR-201 treatments. As accounting policy that is absurd — one processing fee cannot be
 * integral on a product, a separate service in an entity and a commitment-period deferral by
 * default. It is chosen precisely because it is absurd: each treatment is a distinct marker, so the
 * resolved classification alone identifies which rank won, with no need to inspect the returned
 * rule. A fixture that used the realistic classification for every rank would pass whichever rank
 * the resolver picked.
 */
final class FeeRuleFixtures {

    static final String PROC_FEE = "PROC_FEE";
    static final String HOME_LOAN = "HOME_LOAN";
    static final String PERSONAL_LOAN = "PERSONAL_LOAN";
    static final String BANK = "BANK_IN";
    static final String NBFC = "NBFC_IN";

    /** All four ranks share this date, so the four-rank tests isolate specificity from recency. */
    static final LocalDate APRIL_2027 = LocalDate.of(2027, 4, 1);

    /** A date comfortably inside the effective range of {@link #approved}. */
    static final LocalDate JUNE_2027 = LocalDate.of(2027, 6, 30);

    private FeeRuleFixtures() {
    }

    /** An {@code EFFECTIVE} fee rule set version, maker and checker distinct as PolicyVersion demands. */
    static PolicyVersion approved(String id, LocalDate effectiveFrom) {
        return approved(id, effectiveFrom, PolicyVersionStatus.EFFECTIVE);
    }

    /** The same, at a caller-chosen status, for the in-force and replay tests. */
    static PolicyVersion approved(String id, LocalDate effectiveFrom, PolicyVersionStatus status) {
        return new PolicyVersion(
            id, PolicyKind.FEE_RULE_SET, "fee taxonomy under test", effectiveFrom,
            "policy.author", "accounting.policy.owner", effectiveFrom.minusDays(1), status);
    }

    /**
     * One fee code carrying all four specificity shapes on one date.
     *
     * <p>Rank 3 {@code SEPARATE_SERVICE}, rank 2 {@code INTEGRAL}, rank 1 {@code AS_INCURRED},
     * rank 0 {@code OVER_COMMITMENT_PERIOD} — markers, not policy. See the class comment.
     */
    static FeeRuleSet fourRanks() {
        return new FeeRuleSet(
            approved("FEE-2027.1", APRIL_2027),
            List.of(
                FeeRule.of(PROC_FEE, HOME_LOAN, BANK, APRIL_2027,
                    FeeClassification.SEPARATE_SERVICE, "rank 3 marker: exact carve-out"),
                FeeRule.forProduct(PROC_FEE, HOME_LOAN, APRIL_2027,
                    FeeClassification.INTEGRAL, "rank 2 marker: the product's own reading"),
                FeeRule.forEntity(PROC_FEE, BANK, APRIL_2027,
                    FeeClassification.AS_INCURRED, "rank 1 marker: entity-level override"),
                FeeRule.catchAll(PROC_FEE, APRIL_2027,
                    FeeClassification.OVER_COMMITMENT_PERIOD, "rank 0 marker: per-code default")));
    }

    /** A resolver over {@link #fourRanks()}. */
    static FeeClassificationResolver fourRankResolver() {
        return new FeeClassificationResolver(fourRanks());
    }
}
