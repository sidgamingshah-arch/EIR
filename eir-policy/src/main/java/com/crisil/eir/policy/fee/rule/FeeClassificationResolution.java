package com.crisil.eir.policy.fee.rule;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.util.Objects;

/**
 * The outcome of one resolution: either a classification with the rule and version that produced
 * it, or a refusal naming the key that could not be resolved (FR-201, FR-202).
 *
 * <p><b>A returned refusal, not a thrown exception.</b> An unmapped fee code is a fact about the
 * data, not a defect in the caller, and it has to be reportable per contract: FR-905 requires
 * per-contract failure isolation, so a ten-million-contract run collects refusals and carries on
 * rather than unwinding. This is the same shape and the same reason as
 * {@link com.crisil.eir.domain.InvariantResult} — the decision to fail loudly belongs to the
 * caller, and the resolver's job is to state what it found.
 *
 * <p><b>Why the refusal carries no classification at all.</b> Both available defaults are wrong,
 * and wrong in the direction nobody checks. Defaulting an unmapped fee to {@code INTEGRAL} moves
 * it into the gross carrying amount and amortises it over the life of the instrument, so the
 * error appears as a small yield difference spread across sixty periods. Defaulting to
 * {@code AS_INCURRED} keeps it out, so the error appears as period-one income that nobody
 * questions. Neither shows up as a reconciliation break, neither trips an invariant, and the
 * misclassification survives until an auditor samples the code. The measurable side of it: on
 * reference case 1, 5,000 of net fee on a million moves the EIR 56.6 basis points, and 03 § 8's
 * assumed-life table shows year-one fee recognition moving 3.73× on a change of assumption alone.
 * A fee that should never have been in the carrying amount carries the same leverage. So the
 * refusal is the answer, and {@link #classification()} is null — there is nothing a caller could
 * responsibly read out of it.
 *
 * @param requested        the key that was looked up; named in the refusal
 * @param classification   the resolved treatment, or null on a refusal
 * @param rule             the rule that won precedence, or null on a refusal
 * @param ruleSetVersionId the version that classified it, for {@code EIR_COMPUTATION}
 *                         ({@code rule_set_version_id}, 04 § 2.6), or null on a refusal
 * @param exception        the exception-queue category on a refusal, or null when resolved
 * @param detail           what happened, in one sentence; never blank
 */
public record FeeClassificationResolution(
    FeeRuleKey requested,
    FeeClassification classification,
    FeeRule rule,
    String ruleSetVersionId,
    ExceptionCategory exception,
    String detail) {

    public FeeClassificationResolution {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "a resolution states what it found; " + requested.describe() + " carries no detail");
        }
        // Exactly one of the two outcomes, enforced rather than trusted. A resolution carrying
        // both a classification and an exception category is not a partially-good answer, it is
        // an answer whose meaning depends on which field the reader happens to consult — and the
        // two readers here are the projector and the exception queue.
        boolean resolved = classification != null;
        if (resolved == (exception != null)) {
            throw new IllegalArgumentException(
                "resolution of " + requested.describe() + " must be a classification or a refusal"
                    + " and not both or neither; got classification=" + classification
                    + ", exception=" + exception);
        }
        if (resolved && rule == null) {
            throw new IllegalArgumentException(
                "resolution of " + requested.describe() + " to " + classification + " names no rule."
                    + " The rule is the evidence: without it the classification cannot be traced to"
                    + " an approved row and the computation cannot be replayed");
        }
        if (resolved && (ruleSetVersionId == null || ruleSetVersionId.isBlank())) {
            throw new IllegalArgumentException(
                "resolution of " + requested.describe() + " names no rule set version."
                    + " 04 § 2.6 stores rule_set_version_id on every computation; without it replay"
                    + " is impossible (invariant DT-1)");
        }
        // The mirror of the two checks above, and it matters for the same reason. A refusal that
        // carries a rule and a version id reads as a refusal to the exception queue and as
        // "classified by an approved row of version X" to anything that renders the trace — one
        // record, two meanings, decided by which field the reader consults.
        if (!resolved && (rule != null || ruleSetVersionId != null)) {
            throw new IllegalArgumentException(
                "refusal of " + requested.describe() + " cites rule=" + rule + " and version="
                    + ruleSetVersionId + ". A refusal has no evidence to cite by definition; citing"
                    + " any would let a trace read it as classified");
        }
    }

    /**
     * A resolved classification, citing the rule that won precedence and the version that
     * approved it.
     */
    public static FeeClassificationResolution resolved(FeeRuleKey requested, FeeRule rule,
        String ruleSetVersionId) {
        Objects.requireNonNull(rule, "rule");
        return new FeeClassificationResolution(
            requested, rule.classification(), rule, ruleSetVersionId, null,
            "resolved " + requested.describe() + " to " + rule.classification() + " by rule "
                + rule.key().describe() + " of rule set version " + ruleSetVersionId);
    }

    /**
     * A refusal under {@link ExceptionCategory#UNMAPPED_FEE_CODE} (FR-202).
     *
     * <p>{@code detail} is supplied by the resolver rather than composed here, because the three
     * ways a lookup fails need three different sentences and the operator reading the queue has to
     * know which one it was: no rule set in force on the date, no rule at all for the code, or
     * rules for the code but none covering this product, entity and date. The first is a
     * configuration gap in the policy register, the second in the fee master, the third in the
     * product rollout — three different teams.
     */
    public static FeeClassificationResolution unmapped(FeeRuleKey requested, String detail) {
        return new FeeClassificationResolution(
            requested, null, null, null, ExceptionCategory.UNMAPPED_FEE_CODE, detail);
    }

    /** Whether a classification was found. */
    public boolean isResolved() {
        return classification != null;
    }

    /**
     * The classification, or {@link IllegalStateException} naming the unresolved key.
     *
     * <p>For a caller that has already dealt with the refusal branch and wants the value without
     * a null check — the counterpart of {@link com.crisil.eir.domain.InvariantResult#orThrow()}.
     * A caller that has <em>not</em> dealt with the refusal branch gets an exception naming the
     * key rather than a default, which is the whole point: reaching for the value of a refusal is
     * a defect in the caller, and the one thing it must never do is produce a treatment.
     */
    public FeeClassification requireClassification() {
        if (classification == null) {
            throw new IllegalStateException(
                "fee code " + requested.feeCode() + " is unmapped at " + requested.describe()
                    + " — " + detail + ". Raise " + exception + " rather than reading a"
                    + " classification; both defaults are wrong in the direction nobody checks");
        }
        return classification;
    }

    /**
     * Whether this posting enters the initial carrying amount.
     *
     * <p>False on a refusal, and that is not a default — it is the absence of a resolution, and a
     * caller that treats it as an answer will build a carrying amount that excludes a fee that may
     * well belong in it. Guard on {@link #isResolved()} first; this exists so that a resolved
     * caller need not reach through to {@link FeeClassification#entersCarryingAmount()} itself.
     */
    public boolean entersCarryingAmount() {
        return classification != null && classification.entersCarryingAmount();
    }

    /** One audit line — the detail, which already names the key and the version. */
    public String describe() {
        return isResolved() ? detail : exception + ": " + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
