package com.crisil.eir.policy.fee.rule;

import com.crisil.eir.domain.FeeClassification;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;

/**
 * One row of the versioned fee rule set: a {@link FeeRuleKey} and the treatment it resolves to
 * (FR-201, 03 § 3.2, 04 § 2.5).
 *
 * <p>Carries a mandatory {@code rationale}. The same reason
 * {@link com.crisil.eir.policy.PolicyVersion} refuses a version with no description: ACPIR 52
 * states only the positive limb and carries no negative list, so every classification that is not
 * an origination or commitment fee is the bank adopting IFRS 9 B5.4.2 and B5.4.3 as its own
 * configured policy. The rationale is where that election is written down, one row at a time. An
 * auditor asking why a code is {@code AS_INCURRED} is asking this field, and a rule set of five
 * hundred unexplained rows is not an accounting policy.
 *
 * <p><b>{@link FeeClassification#EXCLUDED_BY_DIRECTION} is a legitimate rule outcome</b> and is
 * deliberately not refused here, even though
 * {@link com.crisil.eir.calc.projection.FeePosting} rejects it outright. 03 § 3.4 puts the penal
 * charge exclusion at the ingestion boundary as a filter, "not a judgement in the rule set" — but
 * the filter can only fire on postings whose code <em>resolves</em> to
 * {@code EXCLUDED_BY_DIRECTION}, so the rule set is exactly where that resolution has to be
 * expressible. The division of labour is: the rule set says which codes are penal charges, and
 * the boundary refuses to let them through. Legacy core banking systems route penal amounts
 * through the interest ledger, which is why the exclusion is asserted positively each period
 * (invariant PC-1) rather than assumed.
 *
 * <p>What is deliberately <em>not</em> on this record: the {@code cost_function} attribute
 * (FR-203) and the commitment-fee drawdown threshold (FR-204). Both are per-posting inputs or
 * per-product numerics owned elsewhere, and neither is a component of the key FR-201 resolves on.
 * A rule that also carried a cost function would be a second place for the ACPIR 53
 * selling-versus-processing line to live, and two places for one judgement is two places that can
 * disagree — the defect {@code FeePosting}'s own comment declines to introduce.
 *
 * @param key            the four-part key this rule answers for
 * @param classification the treatment, one of the five of FR-201
 * @param rationale      why — the written form of the policy election, never blank
 */
public record FeeRule(FeeRuleKey key, FeeClassification classification, String rationale) {

    /**
     * The total precedence order on rules, most-preferred last, so that
     * {@code candidates.stream().max(PRECEDENCE)} is the resolution.
     *
     * <p>Two levels, and the order between them is the substantive decision:
     *
     * <ol>
     *   <li><b>Specificity first</b> — most-specific-wins is what 03 § 3.2 requires.
     *   <li><b>Then the later effective date</b> — within one shape, the most recent rule not yet
     *       in the future governs.
     * </ol>
     *
     * <p><b>Specificity dominates recency, and that is not the obvious way round.</b> A bank that
     * issues a new per-code default in 2028 does <em>not</em> thereby change a product-specific
     * rule written in 2027: the 2027 product rule still wins, because it is still the most
     * specific statement about that product. Superseding it takes a new rule at the same
     * specificity. The alternative order — newest wins, specificity breaking ties — would mean
     * every broad default silently overrode every narrow carve-out beneath it, and the carve-outs
     * are precisely the India-specific positions of 03 § 3.2 that somebody argued for.
     *
     * <p><b>The order is total over any one lookup's candidate set</b>, so no two rules can tie
     * and resolution cannot depend on iteration order. The argument is short. Candidates all share
     * the fee code. At rank 3 and rank 2 a candidate's product equals the lookup's, so all
     * candidates of that rank share one product; at rank 3 and rank 1 likewise for entity; rank 0
     * fixes both to {@link FeeRuleKey#ANY}. So candidates of equal rank agree on the whole key
     * except the date — and {@link FeeRuleSet} rejects two rules sharing a key, which includes the
     * date. Hence equal rank implies distinct dates, and the second comparator separates them.
     */
    public static final Comparator<FeeRule> PRECEDENCE =
        Comparator.comparingInt((FeeRule rule) -> rule.key().specificity())
            .thenComparing(rule -> rule.key().effectiveFrom());

    public FeeRule {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(rationale, "rationale");
        if (rationale.isBlank()) {
            throw new IllegalArgumentException(
                "fee rule " + key.describe() + " resolves to " + classification + " with no"
                    + " rationale. ACPIR 52 carries no negative list, so every classification"
                    + " outside the positive limb is a policy election, and an unwritten election"
                    + " is not an accounting policy an auditor can read");
        }
    }

    /** A rule naming both dimensions — an exact carve-out. */
    public static FeeRule of(String feeCode, String product, String entity, LocalDate effectiveFrom,
        FeeClassification classification, String rationale) {
        return new FeeRule(FeeRuleKey.of(feeCode, product, entity, effectiveFrom), classification, rationale);
    }

    /** A rule for one product across every entity. */
    public static FeeRule forProduct(String feeCode, String product, LocalDate effectiveFrom,
        FeeClassification classification, String rationale) {
        return new FeeRule(FeeRuleKey.forProduct(feeCode, product, effectiveFrom), classification, rationale);
    }

    /** A rule for one entity across every product. */
    public static FeeRule forEntity(String feeCode, String entity, LocalDate effectiveFrom,
        FeeClassification classification, String rationale) {
        return new FeeRule(FeeRuleKey.forEntity(feeCode, entity, effectiveFrom), classification, rationale);
    }

    /** The mandatory per-fee-code default (04 § 2.5). */
    public static FeeRule catchAll(String feeCode, LocalDate effectiveFrom,
        FeeClassification classification, String rationale) {
        return new FeeRule(FeeRuleKey.catchAll(feeCode, effectiveFrom), classification, rationale);
    }

    /** The fee code this rule answers for. */
    public String feeCode() {
        return key.feeCode();
    }

    /** Whether this rule is the per-fee-code default rather than a narrower statement. */
    public boolean isCatchAll() {
        return key.specificity() == FeeRuleKey.SPECIFICITY_DEFAULT;
    }

    /** Whether this rule is a candidate for {@code lookup} — see {@link FeeRuleKey#matches}. */
    public boolean matches(FeeRuleKey lookup) {
        return key.matches(lookup);
    }

    /** One audit line naming the key, the outcome and the reason. */
    public String describe() {
        return key.describe() + " -> " + classification + " [" + rationale + "]";
    }
}
