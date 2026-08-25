package com.crisil.eir.policy.fee.rule;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * The four-part key FR-201 resolves on: {@code (fee_code, product, entity, effective_date)}
 * (03 § 3.2, 04 § 2.5).
 *
 * <p>One type serves both sides of a lookup, and the fourth component reads differently on each:
 *
 * <ul>
 *   <li>On a {@link FeeRule} it is the <em>first date the rule governs</em>. Rule sets are
 *       immutable once approved (03 § 3.2), so repricing a fee is a new rule with a later date,
 *       not an edit to the old one.
 *   <li>On a lookup it is the <em>date being asked about</em> — the posting date, or the
 *       as-of date of a replay. A closed period must resolve against what was in force when it
 *       closed or it cannot be reproduced (invariant DT-1), which is why the date is an argument
 *       to resolution rather than {@code LocalDate.now()} read inside it.
 * </ul>
 *
 * <h2>Wildcards, and why the fee code cannot be one</h2>
 *
 * <p>{@code product} and {@code entity} accept {@link #ANY} — the mandatory per-fee-code default
 * of 04 § 2.5 is the row {@code (code, *, *)}. The <b>fee code never can</b>. A global
 * {@code (*, *, *)} row would classify everything, and then no code is ever unmapped and FR-202
 * is unenforceable by construction: the exception queue would stay empty not because the taxonomy
 * is complete but because nothing can miss. So a blank code, or the literal {@code "*"} as a
 * code, is rejected here rather than accepted and quietly widened. This is the narrow place where
 * that guarantee lives; everything downstream of it inherits the guarantee for free.
 *
 * <p>The same reason keeps {@code effectiveFrom} out of the wildcard scheme. An open-ended
 * "any date" rule is a rule whose supersession cannot be dated, and undatable supersession makes
 * replay of a closed period impossible.
 *
 * <h2>Specificity, the more significant half of the precedence order</h2>
 *
 * <p>Resolution is most-specific-wins (03 § 3.2). {@link #specificity()} ranks the four shapes a
 * rule can take, and the order is <b>product over entity</b>:
 *
 * <table border="1">
 *   <caption>Specificity ranks</caption>
 *   <tr><th>Rank</th><th>Shape</th><th>What it expresses</th></tr>
 *   <tr><td>3</td><td>{@code (code, product, entity)}</td>
 *       <td>An exact carve-out: this fee, on this product, in this legal entity.</td></tr>
 *   <tr><td>2</td><td>{@code (code, product, *)}</td>
 *       <td>The product's own fee structure — the group-wide reading.</td></tr>
 *   <tr><td>1</td><td>{@code (code, *, entity)}</td>
 *       <td>An entity-level override applying across that entity's products.</td></tr>
 *   <tr><td>0</td><td>{@code (code, *, *)}</td>
 *       <td>The mandatory per-code default of 04 § 2.5.</td></tr>
 * </table>
 *
 * <p><b>Product outranks entity</b> because the classification is a fact about what the fee is
 * for, and the product is what fixes that. An origination fee is integral because of the
 * instrument it originates; the numeric thresholds the specification requires are defined "per
 * product in policy" (03 § 3.2, FR-204); and 04 § 2.5 writes the key with {@code product_id}
 * ahead of {@code entity_id}. An entity dimension exists for the narrower thing — a
 * jurisdictional or licensing carve-out inside a legal entity — so it refines within a product
 * rather than displacing the product's reading across the book. The order matters only where a
 * bank writes both a product rule and an entity rule for one code and neither an exact rule nor
 * a defensible intent; ranking it here, once, is what stops that case resolving differently in
 * two runs.
 *
 * @param feeCode       the posting's fee code, normalised; never a wildcard
 * @param product       product identifier, or {@link #ANY}
 * @param entity        legal-entity identifier, or {@link #ANY}
 * @param effectiveFrom on a rule, the first date it governs; on a lookup, the date asked about
 */
public record FeeRuleKey(String feeCode, String product, String entity, LocalDate effectiveFrom) {

    /**
     * The wildcard token for {@code product} and {@code entity}.
     *
     * <p>A sentinel rather than {@code null}, so that a refusal reads
     * {@code (PENAL_CHG, HL, *, 2027-06-30)} on every run. An exception-queue entry is a record,
     * and a record whose text varies between identical runs is not one — the same reason
     * {@code FeePosting.COST_FUNCTIONS} is an ordered list.
     */
    public static final String ANY = "*";

    /** Rank of {@code (code, product, entity)} — an exact carve-out. */
    public static final int SPECIFICITY_EXACT = 3;

    /** Rank of {@code (code, product, *)} — the product's own reading. */
    public static final int SPECIFICITY_PRODUCT = 2;

    /** Rank of {@code (code, *, entity)} — an entity-level override. */
    public static final int SPECIFICITY_ENTITY = 1;

    /** Rank of {@code (code, *, *)} — the mandatory per-code default (04 § 2.5). */
    public static final int SPECIFICITY_DEFAULT = 0;

    public FeeRuleKey {
        Objects.requireNonNull(feeCode, "feeCode");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        feeCode = normaliseFeeCode(feeCode);
        product = normaliseDimension(product);
        entity = normaliseDimension(entity);
    }

    /**
     * A rule key naming both dimensions.
     *
     * <p>{@code product} or {@code entity} may be passed as null, blank or {@link #ANY} — all
     * three mean the same thing and normalise to {@link #ANY}. Source configuration files spell
     * an absent dimension all three ways, and treating them as different values would put two
     * rules in the set that a reader cannot tell apart.
     */
    public static FeeRuleKey of(String feeCode, String product, String entity, LocalDate effectiveFrom) {
        return new FeeRuleKey(feeCode, product, entity, effectiveFrom);
    }

    /** A rule for one product across every entity — rank {@value #SPECIFICITY_PRODUCT}. */
    public static FeeRuleKey forProduct(String feeCode, String product, LocalDate effectiveFrom) {
        return new FeeRuleKey(feeCode, product, ANY, effectiveFrom);
    }

    /** A rule for one entity across every product — rank {@value #SPECIFICITY_ENTITY}. */
    public static FeeRuleKey forEntity(String feeCode, String entity, LocalDate effectiveFrom) {
        return new FeeRuleKey(feeCode, ANY, entity, effectiveFrom);
    }

    /**
     * The mandatory per-fee-code default — rank {@value #SPECIFICITY_DEFAULT}, 04 § 2.5.
     *
     * <p>Mandatory <em>per code</em>, which is not the same as a global default and is why FR-202
     * still bites: a code with no rule of any shape is unmapped and refuses, and a code carrying
     * only narrower rules refuses on the combinations they do not cover. See
     * {@link FeeRuleSet#feeCodesWithoutCatchAll()}.
     */
    public static FeeRuleKey catchAll(String feeCode, LocalDate effectiveFrom) {
        return new FeeRuleKey(feeCode, ANY, ANY, effectiveFrom);
    }

    /**
     * A lookup for one posting.
     *
     * <p>Named apart from {@link #of} because the date means something different — see the class
     * comment — and because a reader of a resolution site should be able to see at a glance that
     * it is asking rather than declaring.
     */
    public static FeeRuleKey query(String feeCode, String product, String entity, LocalDate asOf) {
        return new FeeRuleKey(feeCode, product, entity, asOf);
    }

    /** Whether this key names a product rather than {@link #ANY}. */
    public boolean isProductSpecific() {
        return !ANY.equals(product);
    }

    /** Whether this key names an entity rather than {@link #ANY}. */
    public boolean isEntitySpecific() {
        return !ANY.equals(entity);
    }

    /**
     * The specificity rank, 0 to 3, with product the more significant dimension.
     *
     * <p>Two bits read as one number: product is worth 2 and entity 1. See the class comment for
     * the table and for why product outranks entity.
     */
    public int specificity() {
        return (isProductSpecific() ? SPECIFICITY_PRODUCT : 0)
            + (isEntitySpecific() ? SPECIFICITY_ENTITY : 0);
    }

    /**
     * Whether this key, read as a rule, is a candidate for {@code lookup}.
     *
     * <p>Three conditions, and the third is the effective-dating one:
     *
     * <ul>
     *   <li>same fee code — never wildcarded, so this is plain equality;
     *   <li>each dimension either wildcarded on the rule or equal to the lookup's;
     *   <li>the rule's {@code effectiveFrom} is not after the lookup date. A rule takes effect
     *       <em>on</em> its date, inclusive, matching
     *       {@link com.crisil.eir.policy.PolicyVersion#isEffectiveOn}. A rule dated tomorrow is
     *       not a candidate today, however specific — which is what makes a pre-loaded future
     *       repricing safe to hold in the same set as the rule it will replace.
     * </ul>
     *
     * <p>Note the asymmetry: a <em>rule</em> wildcard matches anything, a <em>lookup</em>
     * wildcard matches only a wildcard rule. So a posting arriving with no entity attribute
     * resolves against the product rule and the per-code default and never against an
     * entity-specific carve-out. That is the conservative reading — an entity override claimed
     * for a posting that never named an entity would be a guess — and it means a missing
     * attribute surfaces as a refusal on codes that have only entity rules, rather than as a
     * silently different classification.
     */
    public boolean matches(FeeRuleKey lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (!feeCode.equals(lookup.feeCode())) {
            return false;
        }
        if (isProductSpecific() && !product.equals(lookup.product())) {
            return false;
        }
        if (isEntitySpecific() && !entity.equals(lookup.entity())) {
            return false;
        }
        return !effectiveFrom.isAfter(lookup.effectiveFrom());
    }

    /**
     * The key as one parenthesised tuple, for a refusal message or an audit line.
     *
     * <p>FR-202's refusal has to name the key that could not be resolved, because the operator
     * reading the exception queue has to know which of the four components to go and configure.
     * "Unmapped fee code" alone sends them to the fee master when the gap may well be a product
     * the code was never extended to.
     */
    public String describe() {
        return "(" + feeCode + ", " + product + ", " + entity + ", " + effectiveFrom + ")";
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * Trim and upper-case, and refuse the two spellings that would defeat FR-202.
     *
     * <p>Upper-cased because fee masters are exported from core banking in whatever case the
     * originating screen used, and {@code "PROC_FEE"} resolving while {@code "proc_fee"} raises
     * an exception is a control that fires on data entry rather than on classification.
     */
    private static String normaliseFeeCode(String raw) {
        String code = normaliseLookupCode(raw);
        if (code.isEmpty()) {
            throw new IllegalArgumentException(
                "a fee rule key needs a fee code; a blank code cannot be classified or refused by"
                    + " name, and FR-202 requires the refusal to name the key");
        }
        if (ANY.equals(code)) {
            throw new IllegalArgumentException(
                "'" + ANY + "' is not a fee code. A wildcard fee code would classify every posting"
                    + " and leave no code unmapped, which makes FR-202 unenforceable: the exception"
                    + " queue would be empty because nothing can miss, not because the taxonomy is"
                    + " complete. Write a default per fee code instead (04 § 2.5)");
        }
        return code;
    }

    /**
     * Trim and upper-case a fee code for <em>lookup</em>, without validating it.
     *
     * <p>Shared with {@link FeeRuleSet#rulesFor} so that the normalisation a stored rule went
     * through and the normalisation a lookup goes through are the same code and cannot drift
     * apart. Kept separate from {@link #normaliseFeeCode} because the two callers want different
     * things from an unusable code: constructing a key with a blank code is a defect and raises,
     * while <em>asking</em> whether a blank code is mapped has an honest answer — no — and a
     * predicate that throws on the input it exists to say "no" to is a sharp edge for a caller
     * screening feed data.
     */
    static String normaliseLookupCode(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Null, blank and {@link #ANY} all mean "any"; anything else is trimmed and upper-cased. */
    private static String normaliseDimension(String raw) {
        if (raw == null) {
            return ANY;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return value.isEmpty() ? ANY : value;
    }
}
