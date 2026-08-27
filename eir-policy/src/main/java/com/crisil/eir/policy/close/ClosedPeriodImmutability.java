package com.crisil.eir.policy.close;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Invariant CL-1 — "a closed period is never mutated" (FR-902), asserted by comparing what a closed
 * period published against what the ledger says now.
 *
 * <p><b>What input makes CL-1 fail.</b> Two statements of the same closed period whose figures do
 * not agree. Concretely, the three shapes of {@link FigureMutation.Kind}: a published
 * {@code NET_INTEREST_INCOME} of 10,00,000.00 that now reads 10,00,250.00; a published figure that
 * is now absent; a figure present now that the period never published. Both statements are read
 * from storage and handed in, so no Java type stands between the ledger and a breach.
 *
 * <p><b>Why it is built this way, at length, because the obvious design is a tautology.</b> The
 * tempting implementation of FR-902 is a Java type: make the closed period immutable, and a closed
 * period can never be mutated. Then CL-1 has no input that makes it fail — the deviation would be
 * permanently zero, and the control would appear in every control report as green forever. This
 * engine has found four controls wearing invariant identifiers that could not fail and treats them
 * as worse than absent controls, because they read as coverage. Java immutability constrains one
 * process's object graph; CL-1 is about an {@code UPDATE} against
 * {@code PERIOD_BALANCE} or {@code JOURNAL_ENTRY} in a closed partition, applied by a correction
 * script, an archive step, or a re-run that wrote into the wrong period. The only control that can
 * see that is one holding both readings, which is why {@link PeriodStatement} exists and why
 * {@link ClosedPeriodComparison} carries two of them.
 *
 * <p><b>The failure it catches is the one that looks like diligence</b>, in CL-1's own words:
 * somebody finds an error in a closed period and fixes it, in place, because that is what fixing
 * means everywhere else. A replay of the corrected data is internally consistent, so DT-1 need not
 * notice; what is lost is the correspondence between what was published and what the ledger says
 * was published. 04 § 5's bitemporality is the alternative — a correction records a new version in
 * system time and leaves business time alone, so the period still replays to what it published and
 * the restatement is a separate, dated fact in a later period. That is
 * {@link RestatementArtefact}.
 *
 * <p><b>The deviation is a count of mutated figures</b>, exactly as CL-1's javadoc specifies, and
 * one result is published per assertion however large the population. Both halves matter.
 * {@code InvariantResult.conjunction} keeps only the <em>first</em> breach's deviation among results
 * sharing an id, so a control that published one result per figure would report the count from
 * whichever figure happened to come first and lose the rest. And the aggregate is a count rather
 * than a money total because a signed money total lets a figure overstated by 5,00,000 and another
 * understated by 5,00,000 net to zero and report as unmutated — two breaks in opposite directions
 * netting to a pass. {@link #mutatedAmount} publishes the money size separately, as the
 * <em>absolute</em> total, for whoever needs to know how big the damage is.
 *
 * <p><b>Restatements on file do not excuse a mutation.</b> The register is consulted only to
 * sharpen the detail. A mutated figure that also carries a restatement artefact is the worst case,
 * not the excused one: the movement has been recognised twice, once inside the closed period where
 * it should never have appeared and once in the open period the artefact points at, so the
 * correction has been double-counted and both periods are now wrong.
 */
public final class ClosedPeriodImmutability {

    private ClosedPeriodImmutability() {
    }

    /**
     * CL-1 over one closed period, with no restatement register.
     *
     * <p>The register only enriches the detail, so a caller that has none loses nothing but a
     * sentence.
     */
    public static InvariantResult check(ClosedPeriodComparison comparison) {
        return check(comparison, RestatementRegister.empty());
    }

    /** CL-1 over one closed period. */
    public static InvariantResult check(
        ClosedPeriodComparison comparison, RestatementRegister restatements) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(restatements, "restatements");
        return checkAll(List.of(comparison), restatements);
    }

    /**
     * CL-1 over several closed periods, as <b>one</b> result whose deviation counts every mutated
     * figure in every period.
     *
     * <p>The signature a nightly control sweep wants, and the reason it exists rather than leaving
     * a caller to conjoin per-period results: conjunction keeps only the first breach's deviation
     * among results sharing an id, so a caller conjoining CL-1 over April, May and June would
     * publish April's count and silently discard May's and June's. The aggregation has to happen
     * before the results do.
     *
     * @throws IllegalArgumentException if the list is empty — a sweep over no periods that reported
     *                                  CL-1 satisfied would be the emptiest possible false pass
     */
    public static InvariantResult checkAll(
        List<ClosedPeriodComparison> comparisons, RestatementRegister restatements) {
        Objects.requireNonNull(comparisons, "comparisons");
        Objects.requireNonNull(restatements, "restatements");
        if (comparisons.isEmpty()) {
            throw new IllegalArgumentException(
                "CL-1 was asserted over no periods at all; a satisfied result here would state"
                    + " that nothing has been mutated on the evidence of nothing having been read");
        }

        List<String> details = new ArrayList<>();
        int mutatedFigures = 0;
        int figuresCompared = 0;
        int alsoRestated = 0;
        for (ClosedPeriodComparison comparison : comparisons) {
            List<FigureMutation> mutations = mutations(comparison);
            mutatedFigures += mutations.size();
            figuresCompared += comparedKeys(comparison).size();
            StringBuilder line = new StringBuilder("period ")
                .append(comparison.periodId());
            if (mutations.isEmpty()) {
                line.append(": ").append(comparedKeys(comparison).size())
                    .append(" figure(s) unchanged since publication (closed by ")
                    .append(comparison.period().closedBy())
                    .append(", replay cutoff ")
                    .append(comparison.period().versionCutoffAt())
                    .append(')');
            } else {
                line.append(": ").append(mutations.size()).append(" of ")
                    .append(comparedKeys(comparison).size())
                    .append(" figure(s) no longer say what was published");
                for (FigureMutation mutation : mutations) {
                    line.append(" [").append(mutation.describe());
                    if (restatements.holdsRestatementOf(
                        comparison.periodId(), mutation.figureKey())) {
                        alsoRestated++;
                        // Not an excuse. Both the in-place edit and the restatement exist, so the
                        // movement is now recognised in the closed period AND in the open one the
                        // artefact points at, and both periods are wrong.
                        line.append("; a restatement artefact is ALSO on file, so this movement is"
                            + " recognised twice");
                    }
                    line.append(']');
                }
            }
            details.add(line.toString());
        }

        String detail = String.join("; ", details);
        if (mutatedFigures == 0) {
            return InvariantResult.pass(InvariantId.CL_1, detail);
        }
        return InvariantResult.fail(InvariantId.CL_1,
            detail + " — " + mutatedFigures + " mutated figure(s) across " + comparisons.size()
                + " closed period(s) of " + figuresCompared + " compared"
                + (alsoRestated > 0
                    ? ", " + alsoRestated + " of them also carrying a restatement artefact"
                    : ""),
            BigDecimal.valueOf(mutatedFigures));
    }

    /**
     * Every figure of one closed period that no longer says what it published, in published order
     * and then in current order for figures that were never published.
     *
     * <p>Published separately from the invariant result because a breach of CL-1 is investigated
     * rather than merely reported: the first mutated figure in this list is where an errant
     * {@code UPDATE} started, which is what somebody triaging one looks for first.
     *
     * <p><b>A currency change is a mutation, not an error.</b> Comparing two amounts in different
     * currencies would throw out of {@code Money.minus}, and throwing here would turn a redenominated
     * figure in a closed period — a real and serious mutation — into a crash that reports nothing.
     * So it is reported as {@code CHANGED}, and the detail carries both amounts with their
     * currencies.
     */
    public static List<FigureMutation> mutations(ClosedPeriodComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        PeriodStatement published = comparison.published();
        PeriodStatement current = comparison.current();
        List<FigureMutation> mutations = new ArrayList<>();
        for (String key : comparedKeys(comparison)) {
            Money was = published.figures().get(key);
            Money now = current.figures().get(key);
            if (was == null) {
                mutations.add(FigureMutation.added(key, now));
            } else if (now == null) {
                mutations.add(FigureMutation.removed(key, was));
            } else if (!was.currency().equals(now.currency())) {
                mutations.add(FigureMutation.changed(key, was, now));
            } else if (!now.minus(was).atPresentationScale().isZero()) {
                // Reduced once, not two rounded operands differenced — section 1.3's rule, and the
                // measured case is in InvariantResult.ofMoney: two figures three thousandths of a
                // paise apart present as ...892.50 and ...892.51 and would otherwise report a
                // one-paise mutation on a figure nobody changed. CL-1 is a claim about published
                // figures, so presentation scale is the right scale to compare at.
                mutations.add(FigureMutation.changed(key, was, now));
            }
        }
        return List.copyOf(mutations);
    }

    /**
     * The absolute money size of one period's mutations, at presentation scale.
     *
     * <p>Not the deviation — CL-1's deviation is a count, as its javadoc specifies. This is the
     * figure whoever has to explain the breach needs, and it is absolute for the reason every
     * aggregate in this engine is: a figure overstated by 5,00,000 and another understated by
     * 5,00,000 sum to zero, and a "size of damage" of zero on two wrong figures is worse than no
     * figure at all. A {@code REMOVED} figure contributes its published amount and an
     * {@code ADDED} one its current amount, because that is the size of the movement each
     * introduced.
     */
    public static Money mutatedAmount(ClosedPeriodComparison comparison, Currency in) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(in, "in");
        Money total = Money.zero(in);
        for (FigureMutation mutation : mutations(comparison)) {
            Money contribution = switch (mutation.kind()) {
                case CHANGED -> mutation.current().currency().equals(mutation.published().currency())
                    ? mutation.current().minus(mutation.published()).abs()
                    // A redenomination has no meaningful magnitude; the current amount is the
                    // figure now standing in the closed period, so that is what is reported.
                    : mutation.current().abs();
                case REMOVED -> mutation.published().abs();
                case ADDED -> mutation.current().abs();
            };
            if (!contribution.currency().equals(in)) {
                // Skipped rather than thrown, and named in no total: a multi-currency book would
                // otherwise make this method unusable, and the count — which is the deviation —
                // has already recorded the mutation.
                continue;
            }
            total = total.plus(contribution);
        }
        return total.atPresentationScale();
    }

    /**
     * The union of the two statements' figure keys: published order first, then figures that appear
     * only now.
     *
     * <p>The union and not the intersection. An intersection would compare only figures both
     * statements have, which silently excuses exactly the two mutations nobody notices — a deleted
     * figure and an inserted one.
     */
    private static Set<String> comparedKeys(ClosedPeriodComparison comparison) {
        Set<String> keys = new LinkedHashSet<>(comparison.published().figureKeys());
        keys.addAll(comparison.current().figureKeys());
        return keys;
    }
}
