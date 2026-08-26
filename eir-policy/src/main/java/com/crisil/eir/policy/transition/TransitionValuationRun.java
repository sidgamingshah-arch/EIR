package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ACPIR 19 valuation over a book, and Phase 4's exit gate as a computed answer (FR-908).
 *
 * <p>08 Phase 4 states the gate as: "the fair-valuation run completes over the full book with a
 * rebuttal evidence reference on every contract where the presumption was applied." Those are two
 * claims and this type answers both — the total adjustment to opening retained earnings, and TF-1
 * over the population.
 *
 * <p><b>The aggregate is the point, not a convenience.</b> A per-contract TF-1 tells a programme
 * team what to file next; it cannot tell anyone whether the gate is met, because the gate is a
 * statement about a population and no contract knows the population. And the count has to be
 * published once: {@link InvariantResult#conjunction} keeps only the first breach's deviation among
 * results sharing an id, so a run emitting one TF-1 per contract would publish a deviation of 1 and
 * read as a single unevidenced contract however many there are.
 *
 * <p><b>What "completes over the full book" cannot be checked here.</b> This type sees the
 * valuations it was handed. It has no way to know that a contract exists and was never valued, and
 * saying so is more useful than a control that implies otherwise: completeness against the book is
 * a reconciliation between this population and the contract master, which belongs where both are
 * in view. {@link #valuedContractCount()} is published so that reconciliation has a number to
 * compare, and {@link #coverageAgainst(long)} performs it when the caller supplies the book size.
 */
public record TransitionValuationRun(
    LocalDate transitionDate,
    List<TransitionFairValue> valuations) {

    public TransitionValuationRun {
        Objects.requireNonNull(transitionDate, "transitionDate");
        valuations = List.copyOf(Objects.requireNonNull(valuations, "valuations"));
        for (TransitionFairValue valuation : valuations) {
            if (!valuation.transitionDate().equals(transitionDate)) {
                // A run is one date. Mixing dates would make the total adjustment a sum across
                // two transitions, and there is exactly one opening balance to post it against.
                throw new IllegalArgumentException(
                    "contract " + valuation.contractId() + " is valued at "
                        + valuation.transitionDate() + " in a run dated " + transitionDate
                        + "; one run is one transition date, because there is one opening balance");
            }
        }
    }

    /** A run over a set of valuations, taking its date from them. */
    public static TransitionValuationRun over(List<TransitionFairValue> valuations) {
        Objects.requireNonNull(valuations, "valuations");
        if (valuations.isEmpty()) {
            throw new IllegalArgumentException(
                "an empty valuation run has no transition date to take; supply the date explicitly"
                    + " if the intent is a run over a book that produced nothing");
        }
        return new TransitionValuationRun(valuations.getFirst().transitionDate(), valuations);
    }

    /**
     * The total ACPIR 19 adjustment to opening retained earnings.
     *
     * <p>Summed unrounded, at working precision, and only then available at presentation scale.
     * Rounding each contract's difference before summing over a ten-million-contract book
     * accumulates the rounding into a figure that goes to equity — the same error class
     * [03 § 1.2](../../../../../../../docs/03-calculation-spec.md) forbids for a solve input.
     */
    public Money totalDifferenceToOpeningRetainedEarnings() {
        if (valuations.isEmpty()) {
            return Money.zero(Money.INR);
        }
        Money total = Money.zero(valuations.getFirst().fairValue().currency());
        for (TransitionFairValue valuation : valuations) {
            total = total.plus(valuation.differenceToOpeningRetainedEarnings());
        }
        return total;
    }

    /**
     * Invariant TF-1 over the population: one result, deviation the count of contracts claiming
     * the paragraph 19 presumption with no evidence named.
     */
    public InvariantResult paragraph19Evidenced() {
        List<String> unevidenced = valuations.stream()
            .filter(TransitionFairValue::presumptionIsUnevidenced)
            .map(TransitionFairValue::contractId)
            .sorted()
            .toList();
        long applied = valuations.stream()
            .filter(TransitionFairValue::appliesParagraph19Presumption)
            .count();
        if (unevidenced.isEmpty()) {
            return InvariantResult.pass(InvariantId.TF_1,
                applied + " of " + valuations.size() + " valuations at " + transitionDate
                    + " apply the paragraph 19 presumption, all on named evidence");
        }
        return InvariantResult.fail(InvariantId.TF_1,
            unevidenced.size() + " of " + applied + " paragraph 19 presumptions at "
                + transitionDate + " name no rebuttal evidence"
                + (unevidenced.size() > 20
                    ? " (first 20: " + unevidenced.subList(0, 20) + ")"
                    : ": " + unevidenced),
            BigDecimal.valueOf(unevidenced.size()));
    }

    /**
     * How the book was valued, by technique.
     *
     * <p>Published because the mix is the disclosure. A run that is 99.9% carrying cost has
     * measured almost nothing at fair value, and that is a fact about the transition rather than
     * about the engine — but it is invisible in a total adjustment, which is exactly the figure
     * a reader would look at.
     */
    public Map<ValuationTechnique, Long> techniqueMix() {
        Map<ValuationTechnique, Long> mix = new EnumMap<>(ValuationTechnique.class);
        for (ValuationTechnique technique : ValuationTechnique.values()) {
            mix.put(technique, valuations.stream()
                .filter(valuation -> valuation.technique() == technique)
                .count());
        }
        return Map.copyOf(mix);
    }

    /** How many contracts this run valued. */
    public int valuedContractCount() {
        return valuations.size();
    }

    /**
     * The completeness half of the exit gate, performed against a book size the caller supplies.
     *
     * <p>Separate from TF-1 and reported as plain data rather than under an invariant id, because
     * the gap it measures is not this run's defect — a contract missing from the population was
     * never presented, and an id published from here would attribute a data-feed problem to the
     * valuation. The caller that knows both numbers is the one that can raise it.
     *
     * @param contractsInBook the book size, from the contract master
     * @return the number of contracts in the book that this run did not value; never negative
     */
    public long coverageAgainst(long contractsInBook) {
        return Math.max(0L, contractsInBook - valuations.size());
    }

    /** Every per-contract TF-1 result, for a programme team working the evidence file. */
    public List<InvariantResult> perContractEvidence() {
        List<InvariantResult> results = new ArrayList<>(valuations.size());
        for (TransitionFairValue valuation : valuations) {
            results.add(valuation.paragraph19Evidenced());
        }
        return List.copyOf(results);
    }

    /** A one-line run summary. */
    public String describe() {
        return "ACPIR 19 valuation at " + transitionDate + ": " + valuations.size()
            + " contracts, to opening retained earnings "
            + totalDifferenceToOpeningRetainedEarnings().atPresentationScale()
            + ", techniques " + techniqueMix();
    }
}
