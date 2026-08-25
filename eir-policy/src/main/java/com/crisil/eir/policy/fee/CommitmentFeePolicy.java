package com.crisil.eir.policy.fee;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The per-product numeric definition of "probable drawdown", and the test FR-204 applies with it.
 *
 * <p><b>The silence being filled.</b> IFRS 9 B5.4.2(b) makes a commitment fee integral to the EIR
 * only where it is probable the entity will enter into a specific lending arrangement; B5.4.3(b)
 * sends the rest to revenue over the commitment period. ACPIR 52 states the positive limb —
 * "commitment fees received to originate a loan" — and drops the probability condition
 * (01 § the ACPIR silences; 03 § 3.2). Read literally, every commitment fee defers, including on
 * facilities that were never going to draw, which capitalises fee income into loans that will
 * never exist. The engine restores the condition as configured policy and makes "probable" a
 * number, per product, evidenced by historical drawdown rates — the auditable way to define it.
 *
 * <p><b>A keyed lookup, not a constant.</b> The threshold lives on {@code PRODUCT} as
 * {@code drawdown_probability_threshold} (04 § 2.2) because the evidence is per product: a
 * working capital demand loan facility and a project finance sanction draw at rates with nothing
 * in common, and one bank-wide figure would misclassify at least one of them. A product missing
 * from the table is refused rather than defaulted
 * ({@link CommitmentFeeRefusal#THRESHOLD_NOT_DEFINED_FOR_PRODUCT}) — a global fallback would make
 * the omission invisible, and an invisible omission is the failure mode this whole module exists
 * to prevent.
 *
 * <p><b>The boundary is inclusive: a probability equal to the threshold is probable.</b> Stated
 * here because an off-by-one at the threshold silently reclassifies every commitment fee on a
 * product, in whichever direction nobody is reconciling.
 *
 * <ol>
 *   <li>The threshold is the numeric <em>definition</em> of a word, and the sentence it stands in
 *       is "drawdown is probable where the assessed probability is at least the threshold". A
 *       policy document evidencing a threshold from historical experience says "products drawing
 *       at 60% or more", not "products drawing at more than 60%".
 *   <li>Only the inclusive rule keeps the whole threshold domain usable. Probabilities live in the
 *       closed interval {@code [0,1]} — {@code FeePosting} enforces that at the ingestion
 *       boundary — so under a strict {@code >} test a threshold of 1, meaning "only a certainty
 *       counts as probable", could never be satisfied by any admissible assessment: every
 *       commitment fee on that product would route to {@code OVER_COMMITMENT_PERIOD} while the
 *       policy table says something else entirely. Under {@code >=}, threshold 1 means certainty
 *       only and threshold 0 means everything is probable, and both are reachable and both mean
 *       what they say. The interval is closed at both ends, so the choice is not symmetric: the
 *       strict rule loses an endpoint and the inclusive rule loses none.
 *   <li>The comparison is {@code compareTo}, never {@code equals}. An assessment stored as
 *       {@code 0.60} against a threshold of {@code 0.6} is the same probability and a different
 *       {@code BigDecimal}; an equality test would call it unequal, fall through to the
 *       below-threshold branch, and reclassify exactly the fees that sit on the policy boundary.
 * </ol>
 *
 * <p><b>What this class does not do.</b> It does not decide whether a fee code <em>is</em> a
 * commitment fee: that is the versioned rule set's key lookup on
 * {@code (fee_code, product, entity, effective_date)} (FR-201/202, 03 § 3.2), and this class is
 * what that rule set consults once it has established the fee is one. It therefore never reads
 * the incoming posting's own {@code classification} — re-deciding it here would put one judgement
 * in two places, the defect {@code FeePosting} names when it declines to re-test the rule set's
 * answer.
 *
 * <p>Nor does it select which policy version is in force. It holds the version it was built from
 * and will say whether that version {@linkplain #governs governs a date}, but the choice among
 * versions belongs to the registry that resolves them: gating {@link #classify} on effectiveness
 * would make a {@code DRAFT} table refuse every fee, and a draft table that classifies nothing is
 * a draft whose impact preview (FR-210) cannot be produced.
 */
public final class CommitmentFeePolicy {

    private final PolicyVersion version;

    /**
     * Normalised product id to threshold, held in ascending key order.
     *
     * <p>Sorted rather than in the caller's iteration order, because the caller's order is not a
     * property this class can rely on: {@code Map.of} deliberately randomises iteration with a
     * per-JVM salt, so a table built from one would list its products differently on two runs of
     * the same close. Everything read off this map — {@link #products()}, the product count and
     * the product list in a refusal message — is part of an exception-queue entry, and a record
     * that varies between identical runs is not one (invariant DT-1; the reason
     * {@code FeePosting} orders its cost-function vocabulary).
     */
    private final Map<String, BigDecimal> thresholdsByProduct;

    private CommitmentFeePolicy(PolicyVersion version, Map<String, BigDecimal> thresholds) {
        this.version = version;
        this.thresholdsByProduct = thresholds;
    }

    /**
     * Builds a threshold table against the policy version that carries it.
     *
     * <p>Every rejection here is a defect in the table rather than in a contract's data, so each
     * throws: a threshold outside {@code [0,1]} is not a probability, and a table that carries one
     * would classify by comparing an assessment against a number that cannot mean anything.
     *
     * @param version           the {@link PolicyKind#COMMITMENT_THRESHOLD} version this table
     *                          belongs to; not required to be approved yet, so that a draft's
     *                          impact preview (FR-210) can be produced from it
     * @param thresholdsByProduct product id to threshold, each in {@code [0,1]}
     */
    public static CommitmentFeePolicy of(PolicyVersion version,
        Map<String, BigDecimal> thresholdsByProduct) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(thresholdsByProduct, "thresholdsByProduct");
        if (version.kind() != PolicyKind.COMMITMENT_THRESHOLD) {
            // The kind exists for this table (FR-204). Accepting a FEE_RULE_SET version would put
            // the commitment thresholds under a version number that moves when a product is
            // repriced, and every such forced re-approval is an invitation to approve without
            // reading — the argument PolicyKind is built on.
            throw new IllegalArgumentException(
                "commitment-fee thresholds must be carried by a " + PolicyKind.COMMITMENT_THRESHOLD
                    + " policy version; " + version.id() + " is " + version.kind());
        }
        // TreeMap: ascending key order, established here rather than inherited from the input.
        Map<String, BigDecimal> normalised = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : thresholdsByProduct.entrySet()) {
            String productId = normalise(entry.getKey(), "a threshold key");
            BigDecimal threshold = entry.getValue();
            if (threshold == null) {
                throw new IllegalArgumentException(
                    "product " + productId + " has a null threshold in policy version "
                        + version.id() + "; an absent threshold is refused at classification"
                        + " (FR-204) and a null entry pretending to be one hides that");
            }
            if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                    "product " + productId + " has threshold " + threshold.toPlainString()
                        + " in policy version " + version.id() + "; a definition of \"probable\""
                        + " must be a probability, in [0,1]");
            }
            BigDecimal existing = normalised.put(productId, threshold);
            if (existing != null && existing.compareTo(threshold) != 0) {
                // Product ids arriving from two source systems as "WCDL" and "wcdl" normalise to
                // one key, and if they disagree the surviving entry would depend on map iteration
                // order. Two thresholds for one product is a table defect, not a merge.
                throw new IllegalArgumentException(
                    "product " + productId + " has two different thresholds in policy version "
                        + version.id() + ": " + existing.toPlainString() + " and "
                        + threshold.toPlainString());
            }
        }
        return new CommitmentFeePolicy(version, Collections.unmodifiableMap(normalised));
    }

    /** The version this table belongs to, for the computation trace. */
    public PolicyVersion version() {
        return version;
    }

    /** The products this table defines "probable" for, in ascending id order. */
    public Set<String> products() {
        return thresholdsByProduct.keySet();
    }

    /**
     * Whether this version governs {@code date}.
     *
     * <p>A query, not a gate on {@link #classify} — see the note on the type about impact
     * previews of unapproved tables.
     */
    public boolean governs(LocalDate date) {
        return version.isEffectiveOn(date);
    }

    /**
     * The product's numeric definition of "probable", or empty where the table has none.
     *
     * <p>{@link Optional} rather than a nullable {@code BigDecimal} or a global default, so that a
     * caller cannot reach a threshold it does not have without saying what it intends to do about
     * the absence.
     */
    public Optional<BigDecimal> thresholdFor(String productId) {
        return Optional.ofNullable(thresholdsByProduct.get(normalise(productId, "a product id")));
    }

    /**
     * Applies FR-204 to a commitment-fee posting.
     *
     * <p>Reads the posting's {@code feeCode} and {@code drawdownProbability} and nothing else —
     * in particular not its {@code classification}, which the rule set owns.
     */
    public CommitmentFeeDecision classify(String productId, FeePosting posting) {
        Objects.requireNonNull(posting, "posting");
        return classify(productId, posting.feeCode(), posting.drawdownProbability());
    }

    /**
     * Applies FR-204 to an assessment.
     *
     * <p>Three obligations, each able to fail on its own, and the order they are tested in is the
     * order they can be fixed in: the contract's assessment first, then the product's threshold,
     * then the comparison. A posting with neither the assessment nor a threshold reports the
     * missing assessment, because that is the one the front office can supply; a queue entry
     * naming both would be resolved by fixing whichever was easier.
     *
     * <p>The assessment's numeric domain is taken on trust from the ingestion boundary, where
     * {@code FeePosting} already refuses anything outside {@code [0,1]}. Re-testing it here would
     * be a second copy of one judgement, free to disagree with the first — and the second copy is
     * the one that would be missed when the interval changes.
     *
     * @param productId           the contract's product; the key of the threshold lookup
     * @param feeCode             the posting's fee code, retained on the decision
     * @param drawdownProbability the assessment, in {@code [0,1]} per the ingestion boundary, or
     *                            null where none was made
     */
    public CommitmentFeeDecision classify(String productId, String feeCode,
        BigDecimal drawdownProbability) {
        // A blank product id is not a data gap this policy can report against a product; there is
        // no product to report it against. The caller resolving the contract knows its product, so
        // this is its defect and it fails loudly rather than becoming a queue entry keyed on "".
        String product = normalise(productId, "a product id");
        Objects.requireNonNull(feeCode, "feeCode");
        BigDecimal threshold = thresholdsByProduct.get(product);

        if (drawdownProbability == null) {
            // Obligation 1. Absence is not zero. A commitment fee with no assessment is an
            // unassessed one, and reading it as "not probable" would recognise over the commitment
            // period income that ACPIR 52 defers into the loan's EIR.
            return CommitmentFeeDecision.refused(feeCode, product, null, threshold,
                CommitmentFeeRefusal.PROBABILITY_NOT_ASSESSED, version.id(),
                "no numeric drawdown_probability on the posting; FR-204 requires the assessment"
                    + " and absence is not a low probability");
        }
        if (threshold == null) {
            // Obligation 2. The lookup missed. Refused rather than falling back to a global
            // figure, so that the gap in the policy table is visible as a gap.
            return CommitmentFeeDecision.refused(feeCode, product, drawdownProbability, null,
                CommitmentFeeRefusal.THRESHOLD_NOT_DEFINED_FOR_PRODUCT, version.id(),
                "product " + product + " has no drawdown_probability_threshold in policy version "
                    + version.id() + ", which defines " + thresholdsByProduct.size()
                    + " product(s); FR-204 makes the threshold per product and there is no global"
                    + " definition of \"probable\" to fall back to");
        }

        // Obligation 3. compareTo, and >= at the boundary — both argued on the type.
        boolean probable = drawdownProbability.compareTo(threshold) >= 0;
        FeeClassification classification = probable
            ? FeeClassification.INTEGRAL
            : FeeClassification.OVER_COMMITMENT_PERIOD;
        String detail = "assessed drawdown probability " + drawdownProbability.toPlainString()
            + (probable ? " is at least " : " is below ") + threshold.toPlainString()
            + " for product " + product
            + (probable
                ? "; drawdown is probable, so the fee is integral to the EIR of the loan expected"
                    + " to result (ACPIR 52, IFRS 9 B5.4.2(b))"
                : "; drawdown is not probable, so the fee is recognised over the commitment period"
                    + " and any residual on expiry if undrawn (IFRS 9 B5.4.3(b))");
        return CommitmentFeeDecision.classified(feeCode, product, drawdownProbability, threshold,
            classification, version.id(), detail);
    }

    /**
     * Trims and upper-cases a product id.
     *
     * <p>Case-folded because the same product reaches the engine as {@code WCDL} from one source
     * system and {@code wcdl} from another, and two spellings of one product would be two
     * thresholds — or, worse, one threshold and one refusal. Locale.ROOT because a Turkish default
     * locale upper-cases {@code i} to a dotted capital and would split a product id on the
     * server's locale setting.
     */
    private static String normalise(String productId, String what) {
        Objects.requireNonNull(productId, "productId");
        String trimmed = productId.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                what + " must not be blank; FR-204's threshold lookup is keyed on the product and"
                    + " there is no threshold for no product");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "CommitmentFeePolicy[" + version.id() + ", " + thresholdsByProduct.size()
            + " product(s)]";
    }
}
