package com.crisil.eir.policy.pool;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * A pool of exposures that ACPIR income suspension is applied to as one, and the approved
 * versioned artefact that authorises it (FR-608, 03 § 7.4).
 *
 * <p><b>Why a pool exists at all.</b> Account-level suspension analysis is impractical for credit
 * cards and KCC at volume. ACPIR provides no portfolio carve-out, so 03 § 7.4 has policy supply
 * one on the HKMA precedent of an explicit portfolio treatment for portfolio-managed products —
 * and makes the pool definition the approved artefact. That word is the requirement: suspending
 * income is suppressing recognised revenue, and a pool assembled outside the approval gate would
 * suppress it with nothing on the record.
 *
 * <p>So a definition carries a {@link PolicyVersion} of kind {@link PolicyKind#POOL_DEFINITION},
 * and carrying the wrong kind is refused at construction rather than reported. A fee rule set's
 * approval is not authority to suspend income on a card book: the maker and checker who signed it
 * were looking at fee classifications, and treating their signature as cover for this is the
 * failure the kinds exist to prevent (see {@code PolicyKind}, on why a fee repricing must not
 * re-approve the routing table).
 *
 * @param version           the approving version; must be {@link PolicyKind#POOL_DEFINITION}
 * @param poolId            stable identity across versions of this pool
 * @param productCode       the product the pool covers, in the vocabulary {@code FeeRuleKey} uses
 * @param productFeatures   what the pool's members are, for the eligibility control
 * @param memberExposureIds the exposures in the pool
 */
public record PoolDefinition(
    PolicyVersion version,
    String poolId,
    String productCode,
    Set<TierAssignmentFeature> productFeatures,
    Set<String> memberExposureIds) {

    public PoolDefinition {
        Objects.requireNonNull(version, "version");
        if (version.kind() != PolicyKind.POOL_DEFINITION) {
            throw new IllegalArgumentException(
                "pool " + poolId + " is authorised by a " + version.kind() + " version ("
                    + version.id() + "); the maker and checker who signed that were not looking"
                    + " at a suspension pool, and their signature is not authority to suspend"
                    + " income on one");
        }
        poolId = requireText(poolId, "poolId");
        productCode = requireText(productCode, "productCode");
        productFeatures = Set.copyOf(Objects.requireNonNull(productFeatures, "productFeatures"));
        memberExposureIds =
            Set.copyOf(Objects.requireNonNull(memberExposureIds, "memberExposureIds"));
        if (memberExposureIds.isEmpty()) {
            // An empty pool suspends nothing, so this is not a safety question — it is that a
            // definition with no members is almost always a load that failed silently, and it
            // would sit in the registry looking like cover for a pool that has none.
            throw new IllegalArgumentException(
                "pool " + poolId + " has no members; an empty definition is a failed load, not a"
                    + " pool that happens to be empty");
        }
    }

    /** Whether this definition governs {@code date} — the version's own question. */
    public boolean isEffectiveOn(LocalDate date) {
        return version.isEffectiveOn(date);
    }

    /** Whether {@code exposureId} is in this pool. */
    public boolean contains(String exposureId) {
        return memberExposureIds.contains(exposureId);
    }

    /**
     * Invariant PL-2: the pool covers a portfolio-managed product.
     *
     * <p>Eligibility is {@link TierAssignmentFeature#CARD_OR_KCC_REVOLVER} and not a second
     * product list. "Is this a portfolio-managed revolver" is a question the tier assignment
     * already answers, off the same feature set, and two answers to it would eventually
     * disagree — at which point a book would be Tier 2 for measurement and account-level for
     * suspension, or the reverse, with nothing saying which is right.
     *
     * <p>Reported rather than refused at construction. A pool whose product is ineligible is a
     * policy error to escalate, not a malformed record: the members exist, the version exists, and
     * an operator has to be told which pool to dissolve. Refusing it would also make the state
     * unrepresentable and therefore untestable.
     */
    public InvariantResult eligibility() {
        String detail = "pool " + poolId + " covers " + productCode + " with features "
            + productFeatures;
        if (productFeatures.contains(TierAssignmentFeature.CARD_OR_KCC_REVOLVER)) {
            return InvariantResult.pass(InvariantId.PL_2, detail);
        }
        return InvariantResult.fail(InvariantId.PL_2,
            detail + " — pool-level suspension exists because account-level analysis is"
                + " impractical for cards and KCC, and this product is neither, so the analysis"
                + " it avoids is one that can be done",
            BigDecimal.ONE);
    }

    /** A one-line description naming the pool, its size and its authority. */
    public String describe() {
        return "pool " + poolId + " (" + productCode + ", " + memberExposureIds.size()
            + " members) under " + version.describe();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }
}
