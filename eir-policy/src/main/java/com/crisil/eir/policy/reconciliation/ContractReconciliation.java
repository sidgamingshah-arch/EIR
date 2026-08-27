package com.crisil.eir.policy.reconciliation;

import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * One contract's C-14 line: what the engine projected, what the CBS billed, what was attributed to
 * a stated cause, and what is left (FR-804, invariant RC-1).
 *
 * <p><b>The one piece of arithmetic worth reading carefully</b> is
 * {@link #unexplainedDifference()}. The difference and the explained amount are both carried at
 * working precision and the <em>residual</em> is reduced to paise once, rather than a rounded
 * difference being compared against a rounded explanation. That is section 1.3's rule and
 * {@code InvariantResult.ofMoney}'s: rounding both operands first rounds twice, and the error that
 * admits has no floor. It is not a cosmetic point here. A difference of 0.0031 straddling a
 * rounding boundary against an explanation of the same 0.0031 presents as 0.00 and 0.01 and
 * reports a one-paise break on a contract where the explanation is exactly right — a control
 * exception blocking a close on nothing, which is the fastest way to teach a close team to
 * suppress this control.
 *
 * <p><b>Nothing is refused at construction except a line that names no source.</b> A difference is
 * constructible, an explanation that does not add up is constructible, a self-approved explanation
 * is constructible. Every one of those is what RC-1 detects; a guard here would be the guard the
 * invariant asserts, and the project's standing rule is that such a guard converts a control into a
 * tautology. The single exception is a line with no figure on either side, which is not a
 * reconciliation line at all — see {@link #of}.
 *
 * @param contractId               the shared join key, {@code contract.source_system_ref}
 * @param engineContractualInterest what the contractual leg accrued, or {@code null} where the
 *                                 engine presented nothing for this contract
 * @param cbsBilledInterest        what the CBS billed, or {@code null} where the feed carried no
 *                                 line for this contract
 * @param explanations             attributions offered against this contract's difference, in the
 *                                 order presented
 */
public record ContractReconciliation(
    String contractId,
    Money engineContractualInterest,
    Money cbsBilledInterest,
    List<DifferenceExplanation> explanations) {

    public ContractReconciliation {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(explanations, "explanations");
        contractId = contractId.strip();
        if (contractId.isEmpty()) {
            throw new IllegalArgumentException("contractId must not be blank");
        }
        if (engineContractualInterest == null && cbsBilledInterest == null) {
            // Not a data condition about the book — there is no book here. A line neither source
            // presented has no difference to measure and no contract to investigate; it can only
            // arise from a caller assembling lines from a third list, and the aggregator handles
            // an explanation for an unpresented contract as a dangling explanation instead.
            throw new IllegalArgumentException(
                "contract " + contractId + " was presented by neither source; a line with no"
                    + " figure on either side is not a reconciliation line");
        }
        if (engineContractualInterest != null && cbsBilledInterest != null
            && !engineContractualInterest.currency().equals(cbsBilledInterest.currency())) {
            // Money.minus would raise this a line later; raised here so the message names the
            // contract. A deviation measured across two currencies is a figure nobody can
            // reconcile, which is the argument InvariantResult.ofMoney makes for letting a
            // currency mismatch throw rather than be reported as a breach.
            throw new IllegalArgumentException(
                "contract " + contractId + " is "
                    + engineContractualInterest.currency().getCurrencyCode()
                    + " on the engine side and " + cbsBilledInterest.currency().getCurrencyCode()
                    + " on the CBS side; C-14 reconciles one control account, in one currency");
        }
        explanations = List.copyOf(explanations);
        for (DifferenceExplanation explanation : explanations) {
            if (!explanation.contractId().equals(contractId)) {
                // Only the aggregator guaranteed this pairing, by construction. The record is
                // public, so a direct construction could attach contract B's explanation to
                // contract A and it would reduce A's difference — and every test passed matching
                // ids, so nothing caught it.
                throw new IllegalArgumentException(
                    "contract " + contractId + " carries an explanation naming "
                        + explanation.contractId() + "; an explanation reduces the difference on"
                        + " the line it names and no other");
            }
        }
    }

    /**
     * A line from the two sides and the explanations offered.
     *
     * @param engine {@code null} where the engine presented nothing
     * @param cbs    {@code null} where the CBS feed presented nothing
     */
    public static ContractReconciliation of(
        String contractId,
        ContractualLegInterest engine,
        CbsBilledInterest cbs,
        List<DifferenceExplanation> explanations) {
        return new ContractReconciliation(
            contractId,
            engine == null ? null : engine.contractualInterest(),
            cbs == null ? null : cbs.billedInterest(),
            explanations);
    }

    /** Which sources presented a figure. */
    public SourcePresence presence() {
        if (engineContractualInterest == null) {
            return SourcePresence.CBS_ONLY;
        }
        if (cbsBilledInterest == null) {
            return SourcePresence.ENGINE_ONLY;
        }
        return SourcePresence.BOTH;
    }

    /** The currency of the line — from whichever side presented, which agree where both did. */
    public Currency currency() {
        return engineContractualInterest != null
            ? engineContractualInterest.currency()
            : cbsBilledInterest.currency();
    }

    /**
     * Engine less CBS, at working precision, with an absent side contributing nothing.
     *
     * <p>Signed engine-minus-CBS throughout this package, including on
     * {@link DifferenceExplanation#amount()}, so that a positive figure always means the same
     * thing: the engine projected more contractual interest than the borrower was billed.
     */
    public Money difference() {
        Money engine = engineContractualInterest == null
            ? Money.zero(currency()) : engineContractualInterest;
        Money cbs = cbsBilledInterest == null ? Money.zero(currency()) : cbsBilledInterest;
        return engine.minus(cbs);
    }

    /** The explanations entitled to reduce the difference. */
    public List<DifferenceExplanation> effectiveExplanations() {
        return explanations.stream().filter(DifferenceExplanation::isEffective).toList();
    }

    /**
     * The explanations offered and not entitled to reduce anything — unapproved, self-approved,
     * or carrying no statement of what was relied on.
     *
     * <p>Reported separately from "no explanation at all", because the two are different findings.
     * An unexplained difference with nothing attached says nobody has looked yet. An unexplained
     * difference with an ineffective explanation attached says somebody looked, wrote it down, and
     * the artefact does not carry the authority the close gate requires — which is the state that
     * reads as coverage.
     */
    public List<DifferenceExplanation> ineffectiveExplanations() {
        return explanations.stream().filter(explanation -> !explanation.isEffective()).toList();
    }

    /** The total the effective explanations claim, at working precision. */
    public Money explainedAmount() {
        Money total = Money.zero(currency());
        for (DifferenceExplanation explanation : effectiveExplanations()) {
            total = total.plus(explanation.amount());
        }
        return total;
    }

    /**
     * The difference no effective explanation accounts for, reduced to paise exactly once.
     *
     * <p>{@code difference - explained}, so an explanation that claims too little leaves the
     * shortfall, one that claims too much leaves the excess with the opposite sign, and one that
     * claims the right magnitude with the wrong sign leaves twice the difference. That last case is
     * the reason the arithmetic subtracts rather than compares magnitudes: an explanation offered
     * in the wrong direction is a misunderstanding of which system is high, and it must not be
     * allowed to look like a match.
     */
    public Money unexplainedDifference() {
        // NOT reduced to presentation scale, and that is the fix rather than the omission.
        // Reducing each line and then summing N of them erases up to N x 0.005 — 200 control
        // accounts each out by 0.004 summed to 0.80 of genuine unexplained difference and
        // reported nil. 03 section 5.7 is explicit: "Residue is never resolved by tolerance. A
        // tolerance hides exactly the class of defect this system exists to prevent." A per-line
        // rounding IS a tolerance of half a paise per contract, scaling with the chart of
        // accounts.
        //
        // The systematic sub-paise difference between the two systems is accounted for by a
        // rule, as section 5.7's second sentence requires, and that rule is applied where the
        // engine's figure BECOMES a billed figure — ContractualLegInterest.fromContractualLeg.
        // Read its javadoc; it records the two wrong answers this one replaced. Nothing rounds
        // after the subtraction here, so a genuine residue is reported in full.
        return difference().minus(explainedAmount());
    }

    /** Whether this contract needs nothing further: the difference is nil or fully attributed. */
    public boolean isTied() {
        return unexplainedDifference().isZero();
    }

    /**
     * Whether somebody attached an effective explanation to this contract that does not close the
     * difference it claims to explain.
     *
     * <p><b>A finding in its own right</b>, and the specific abuse the amount arithmetic exists to
     * prevent: without it, an explanation is a note, and any difference can be waved through by
     * attaching one. It is separated from the general unexplained population because the remedy is
     * different and more urgent — an unexplained difference with no note is work not yet done,
     * whereas a signed-off explanation whose figure is wrong is a completed control step that
     * produced a wrong answer, and the same preparer and checker are presumably applying the same
     * method to every other contract in the book.
     */
    public boolean hasMisstatedExplanation() {
        if (effectiveExplanations().isEmpty()) {
            return false;
        }
        // Partial attribution is NOT a misstatement, and treating it as one put the most
        // escalatory heading in the aggregator ("a signed-off explanation whose amount is not the
        // difference it explains, which is a finding in its own right") on the most ordinary
        // in-flight state: a 1,000.00 difference with 600.00 correctly attributed to timing and
        // 400.00 still under investigation. over()'s own javadoc says "several per contract is
        // normal", which makes partial coverage mid-close normal too.
        //
        // What is a misstatement is a claim that CANNOT be a partial account of the difference:
        // one that overshoots it, or one pointing the other way. Both mean the preparer's method
        // is wrong rather than incomplete — and that is the reading the escalation was written
        // for, since the same method is presumably being applied to every other contract.
        Money claimed = explainedAmount();
        Money difference = difference();
        if (claimed.signum() != 0 && difference.signum() != 0
            && claimed.signum() != difference.signum()) {
            return true;
        }
        return claimed.abs().compareTo(difference.abs()) > 0;
    }

    /**
     * Whether the difference is only partly attributed — work in progress, not a finding.
     *
     * <p>Published so a close can distinguish the two states it previously conflated: a line with
     * some of its difference accounted for and the rest outstanding is the normal mid-close
     * shape, and a line whose claim overshoots or contradicts the difference is a method error.
     */
    public boolean isPartlyAttributed() {
        return !effectiveExplanations().isEmpty() && !isTied() && !hasMisstatedExplanation();
    }

    /** A one-line report row. */
    public String describe() {
        Money engine = engineContractualInterest == null
            ? null : engineContractualInterest.atPresentationScale();
        Money cbs = cbsBilledInterest == null ? null : cbsBilledInterest.atPresentationScale();
        List<String> parts = new ArrayList<>();
        parts.add("engine " + (engine == null ? "(absent)" : engine.toString()));
        parts.add("CBS " + (cbs == null ? "(absent)" : cbs.toString()));
        parts.add("difference " + difference().atPresentationScale());
        if (!explanations.isEmpty()) {
            parts.add("explained " + explainedAmount().atPresentationScale()
                + " over " + effectiveExplanations().size() + " of " + explanations.size()
                + " explanations");
        }
        parts.add("unexplained " + unexplainedDifference());
        if (presence().isOneSided()) {
            parts.add(presence().statement());
        }
        if (hasMisstatedExplanation()) {
            parts.add("EXPLANATION DOES NOT ADD UP");
        }
        return contractId + ": " + String.join(", ", parts);
    }
}
