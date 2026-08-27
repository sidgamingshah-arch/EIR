package com.crisil.eir.policy.close;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The close gate's answer: may this period be locked, and if not, everything that is wrong with it.
 *
 * <p><b>Every refusal, not the first.</b> A month-end close presents its problems simultaneously
 * and they are worked by different people — the red S3-1 goes to the impairment team, the unmapped
 * fee codes to the fee master owner, the sub-ledger break to financial control. A gate returning
 * one problem at a time turns a day's parallel work into a week of serial re-runs, and each re-run
 * costs a full amortisation pass over the book. {@code MakerCheckerGate.applyAll} makes the same
 * argument for a batch of policy transitions.
 *
 * <p><b>There is deliberately no {@code asInvariantResult()} here.</b> Several decision types in
 * this module have one — {@code ActivationDecision} publishes PG-1 — and this one must not, which
 * is worth stating explicitly because the omission otherwise looks like an oversight. FR-901's gate
 * is the point at which invariants are <em>read</em>; it is not a claim about a figure. An id
 * meaning "the close gate held" would be a control whose only input is this object, so it would
 * pass exactly when the gate returned permitted and fail exactly when it did not — true by
 * construction, unfalsifiable by any ledger content, and indistinguishable in a control report
 * from a control that checks something. This engine has found four such ids wearing invariant
 * identifiers and treats them as worse than absent controls, because they read as coverage. The
 * invariants this close depends on are published by the engines that computed the figures, and they
 * arrive here as {@link CloseRequest#invariantResults()}.
 *
 * <p>The one invariant this package does publish is CL-1, and it is a different claim entirely: not
 * "the gate held" but "the figures a closed period published are still the figures it says now" —
 * see {@link ClosedPeriodImmutability}.
 *
 * @param permitted     whether the period may be locked
 * @param refusals      everything wrong, in evaluation order; empty when permitted
 * @param closedPeriod  the locked, attested period when permitted; null when refused
 */
public record CloseDecision(
    boolean permitted, List<CloseRefusal> refusals, AccountingPeriod closedPeriod) {

    public CloseDecision {
        refusals = List.copyOf(Objects.requireNonNull(refusals, "refusals"));
        // The three fields must agree. A caller trusting the boolean and a caller trusting the
        // refusal list must reach the same conclusion, or one of them publishes a set of accounts
        // the other believes is blocked.
        if (permitted) {
            if (!refusals.isEmpty()) {
                throw new IllegalArgumentException(
                    "a permitted close cannot also carry refusals: " + refusals);
            }
            if (closedPeriod == null) {
                throw new IllegalArgumentException(
                    "a permitted close must produce the locked period; the whole output of step 7"
                        + " is the CLOSED, attested row");
            }
            if (!closedPeriod.isClosed()) {
                throw new IllegalArgumentException(
                    "a permitted close produced a period in status " + closedPeriod.status()
                        + "; step 7 locks the period (FR-902)");
            }
        } else {
            if (refusals.isEmpty()) {
                throw new IllegalArgumentException(
                    "a refused close must name what is wrong; an unexplained refusal cannot be"
                        + " worked or appealed, and the operator's only recourse is to try again");
            }
            if (closedPeriod != null) {
                throw new IllegalArgumentException(
                    "a refused close cannot also produce a closed period: "
                        + closedPeriod.describe());
            }
        }
    }

    /** The period may be locked; {@code closedPeriod} is the attested {@code CLOSED} row. */
    public static CloseDecision permit(AccountingPeriod closedPeriod) {
        return new CloseDecision(true, List.of(), closedPeriod);
    }

    /** The period may not be locked, for every reason in {@code refusals}. */
    public static CloseDecision refuse(List<CloseRefusal> refusals) {
        return new CloseDecision(false, refusals, null);
    }

    /** Whether this is a refusal. */
    public boolean isRefused() {
        return !permitted;
    }

    /** How many distinct things are wrong. */
    public int refusalCount() {
        return refusals.size();
    }

    /**
     * The distinct reasons, in first-appearance order.
     *
     * <p>What a summary line shows. Forty unworked exceptions are forty refusals and one reason,
     * and an operator triaging a close wants the reason list first and the forty afterwards.
     */
    public Set<CloseGateRefusal> reasons() {
        Set<CloseGateRefusal> distinct = new LinkedHashSet<>();
        for (CloseRefusal refusal : refusals) {
            distinct.add(refusal.reason());
        }
        return Collections.unmodifiableSet(distinct);
    }

    /** Whether the close was refused for this reason at least once. */
    public boolean refusedFor(CloseGateRefusal reason) {
        return reasons().contains(reason);
    }

    /**
     * The refusals describing evidence that exists and does not hold, rather than evidence that is
     * missing.
     *
     * <p>Separated because the two escalate differently, and the ones that look like diligence are
     * the ones a checkbox control would have passed. See
     * {@link CloseGateRefusal#looksLikeDiligence()}.
     */
    public List<CloseRefusal> refusalsThatLookLikeDiligence() {
        return refusals.stream().filter(CloseRefusal::looksLikeDiligence).toList();
    }

    /** One audit sentence, then one line per refusal. */
    public String describe() {
        if (permitted) {
            return "CLOSE PERMITTED — " + closedPeriod.describe();
        }
        StringBuilder rendered = new StringBuilder("CLOSE REFUSED by ")
            .append(refusals.size())
            .append(" gate(s) ")
            .append(reasons());
        for (CloseRefusal refusal : refusals) {
            rendered.append(System.lineSeparator()).append("  - ").append(refusal.describe());
        }
        return rendered.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
