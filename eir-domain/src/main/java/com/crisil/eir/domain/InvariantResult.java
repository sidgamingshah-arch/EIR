package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The outcome of asserting one invariant, retained so that a run's invariant
 * results are reportable rather than merely thrown.
 *
 * @param id        which invariant
 * @param satisfied whether it held
 * @param detail    human-readable statement of what was compared
 * @param deviation the signed size of the breach, or zero when satisfied
 */
public record InvariantResult(InvariantId id, boolean satisfied, String detail, BigDecimal deviation) {

    public InvariantResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(deviation, "deviation");
    }

    public static InvariantResult pass(InvariantId id, String detail) {
        return new InvariantResult(id, true, detail, BigDecimal.ZERO);
    }

    public static InvariantResult fail(InvariantId id, String detail, BigDecimal deviation) {
        return new InvariantResult(id, false, detail, deviation);
    }

    /**
     * Asserts an expected equality of money amounts at presentation scale.
     *
     * <p>Comparison is at presentation scale on purpose: the invariant is a claim
     * about the figures that get published, and a working-precision comparison
     * would fail on a difference no reader could ever see.
     *
     * <p><b>The difference is reduced once, not two reduced operands compared.</b>
     * That is the general rule of section 1.3 — round where a figure is persisted,
     * once — and it is the rule the reference cases themselves follow: at period 23
     * of reference case 1 the working difference 19.4966 publishes as 19.50, while
     * the two published balances differenced give 19.51, and the fixture states
     * 19.50. {@code TwoLegRow.presentedUnamortisedFee} makes the same choice for the
     * same reason.
     *
     * <p>Rounding both operands first rounds twice, and the error it admits is
     * unbounded below. On a 30-year quarterly exposure of 20,036,296.57 the two legs
     * came to 242,103,892.5032 and 242,103,892.5063 — a difference of thirty-one ten
     * thousandths of a rupee, three thousandths of a paise — and because the pair
     * straddles a rounding boundary the operands present as 242,103,892.50 and
     * 242,103,892.51 and INV-1 reported a one-paise breach. In production that is a
     * control exception blocking a period close on a contract where nothing is wrong,
     * and no bound on the true difference makes it go away: a difference of 1e-20
     * breaches just as readily. Reducing once bounds the report honestly — a breach
     * is raised only where the quantities really do differ by at least half a minor
     * unit, which is a difference and not a rounding artefact.
     *
     * <p>A currency mismatch between the two amounts raises
     * {@link IllegalArgumentException} from {@link Money#minus} rather than being
     * reported as a breach. That is a defect in the caller, not a fact about the
     * book: a deviation measured across two currencies would be a figure nobody
     * could reconcile, and a loud failure naming the mismatch is more use than a
     * breach report that cannot be actioned. Note that it is therefore <em>not</em>
     * caught by a caller that wraps an invariant set in
     * {@link InvariantBreachException} handling.
     */
    public static InvariantResult ofMoney(InvariantId id, String detail, Money expected, Money actual) {
        Money difference = actual.minus(expected).atPresentationScale();
        if (difference.isZero()) {
            return pass(id, detail + " (" + actual.atPresentationScale() + ")");
        }
        return fail(id,
            detail + " — expected " + expected.atPresentationScale()
                + ", got " + actual.atPresentationScale(),
            difference.amount());
    }

    /**
     * Conjoins several assertions of the <em>same</em> invariant into the one result
     * that invariant is entitled to.
     *
     * <p><strong>Why a named invariant gets exactly one answer.</strong> More than one
     * result under one identifier means anything resolving that invariant by name — a
     * movement schedule, a control report, an auditor's query — gets whichever happens
     * to come first in the list. Where the results agree that is merely noise in the
     * audit record. Where they disagree it is a control reporting satisfied on a
     * projection that breached, and the conjunction over the whole set is still enforced
     * so nothing looks wrong until someone reads the report.
     *
     * <p>This has now been found three times in this engine and the third is what
     * generalised it. PC-1 was asserted twice per LMS projection, the classified-fee
     * route passing while the billed-schedule route failed. ST-2 was asserted twice per
     * Stage 3 decomposition, the second a tautology added in the belief that it fixed the
     * first. And ST-3 is published three times per blueprint projection, two of them the
     * same ladder result and the third a genuinely different claim — the ladder's
     * "scheduled principal + terminal = advanced" against the behavioural "principal
     * recovered on the expected leg = on the contractual leg", which disagree under a
     * prepayment curve that moves principal in amount rather than in time.
     *
     * <p>Distinct details are concatenated rather than replaced, because which routes were
     * asserted is the part worth keeping: a pass on one route means something different
     * from a pass on all of them. Identical details collapse, so the common case of the
     * same result gathered twice reads as one statement instead of a stutter.
     *
     * @param results one or more results, all carrying the same {@link #id}
     * @throws IllegalArgumentException if the list is empty or the ids differ
     */
    public static InvariantResult conjunction(List<InvariantResult> results) {
        Objects.requireNonNull(results, "results");
        if (results.isEmpty()) {
            throw new IllegalArgumentException("a conjunction needs at least one result");
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        InvariantId id = results.get(0).id();
        List<String> details = new ArrayList<>();
        BigDecimal breach = null;
        boolean satisfied = true;
        for (InvariantResult result : results) {
            if (result.id() != id) {
                throw new IllegalArgumentException("cannot conjoin " + result.id()
                    + " with " + id + "; a conjunction is one invariant's own results");
            }
            if (!details.contains(result.detail())) {
                details.add(result.detail());
            }
            if (!result.satisfied()) {
                satisfied = false;
                if (breach == null) {
                    breach = result.deviation();
                }
            }
        }
        String detail = String.join("; ", details);
        return satisfied ? pass(id, detail) : fail(id, detail, breach);
    }

    /**
     * Collapses a mixed list so that each invariant appears exactly once, in the order
     * it first appeared.
     *
     * <p>The shape a caller assembling results from several stages wants: each stage
     * asserts what it can see, and the caller publishes one answer per invariant. Order
     * is first-appearance rather than declaration order so that the reading order of a
     * report follows the order the engine computed things in, which is what makes a
     * breach traceable.
     *
     * <p>See {@link #conjunction} for why this is not merely tidiness.
     */
    /**
     * The invariants a run was answerable for and produced no result under.
     *
     * <p><b>Why this is a method and not two copies of a loop.</b> Two runs in this engine publish
     * population-level invariant sets, and both must answer "did anything actually assert this?"
     * rather than "is there a failure in the list". A set derived only from what the subjects
     * happened to publish cannot distinguish <em>no breaches</em> from <em>nothing looked at</em>,
     * and the close gate's only absence check is that the whole list is empty — so a run that
     * asserted one invariant and silently skipped the rest reads as clean. The two runs were
     * written independently and only one of them had this; that is the shape of the defect this
     * codebase records finding most often, and the remedy is one statement of the rule.
     *
     * <p><b>Order follows {@code answerableFor}, not the results.</b> A gap list is read by a
     * person against a declared list of obligations, so it has to arrive in the order that list is
     * written in; deriving it from result order would make the same gap render differently on two
     * runs of the same book (FR-903 wants the opposite).
     *
     * <p>Note what this deliberately does not do: it does not publish a failed
     * {@link InvariantResult} for a gap. An invariant's statement is a claim about figures, and
     * marking S3-1 <em>breached</em> because nobody evaluated it is a false statement about the
     * book in service of a true statement about the run. The caller reports the gap as a property
     * of the run.
     *
     * @param answerableFor the invariants the run is obliged to answer, declared rather than
     *                      derived; duplicates are ignored
     * @param results       what it actually published
     * @return the ids in {@code answerableFor} with no result of any kind, in that list's order
     */
    public static List<InvariantId> idsWithoutEvidence(
        List<InvariantId> answerableFor, List<InvariantResult> results) {
        Objects.requireNonNull(answerableFor, "answerableFor");
        Objects.requireNonNull(results, "results");
        Set<InvariantId> asserted = EnumSet.noneOf(InvariantId.class);
        for (InvariantResult result : results) {
            asserted.add(result.id());
        }
        List<InvariantId> gaps = new ArrayList<>();
        for (InvariantId id : answerableFor) {
            if (!asserted.contains(id) && !gaps.contains(id)) {
                gaps.add(id);
            }
        }
        return List.copyOf(gaps);
    }

    public static List<InvariantResult> oneResultPerInvariant(List<InvariantResult> results) {
        Objects.requireNonNull(results, "results");
        Map<InvariantId, List<InvariantResult>> grouped = new LinkedHashMap<>();
        for (InvariantResult result : results) {
            grouped.computeIfAbsent(result.id(), key -> new ArrayList<>()).add(result);
        }
        List<InvariantResult> collapsed = new ArrayList<>(grouped.size());
        for (List<InvariantResult> group : grouped.values()) {
            collapsed.add(conjunction(group));
        }
        return List.copyOf(collapsed);
    }

    /** Throws if this result is a breach. */
    public InvariantResult orThrow() {
        if (!satisfied) {
            throw new InvariantBreachException(this);
        }
        return this;
    }
}
