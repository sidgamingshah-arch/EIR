package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.calc.projection.ContractTerms;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One contract whose EIR expected life fell back to the contractual term (FR-310, ACPIR 51).
 *
 * <p><b>The fallback is detected from {@link ContractTerms}, not asserted.</b>
 * {@code ContractTerms.expectedLifePeriods()} resolves an unstated {@code eirExpectedLifeMonths}
 * to the contractual term, and that is the fallback happening. Reading the same field here means
 * the register cannot report a fallback on a contract that did not take one, nor miss one that
 * did — which a hand-maintained list of "contracts on the fallback" certainly would.
 *
 * <p><b>Why a justification field at all.</b> {@code ContractTerms.expectedLifePeriods()}'s own
 * javadoc states the control: the fallback "is intended for rare cases and must be an explicit,
 * justified election on the contract", and the method "cannot tell an election from an omission",
 * so {@code ProjectionResult.expectedEqualsContractualByPolicy()} "records only that the two legs
 * coincide and the justification is held upstream". This record is the upstream holder. A
 * fallback with a blank justification is not a formatting problem — it is the difference between
 * an election somebody made and a field somebody left empty, and those are the two things the
 * projector explicitly cannot distinguish.
 *
 * @param contractId    the contract on the fallback
 * @param terms         its terms, from which the fallback is read
 * @param justification why expected life is taken as the contractual term; blank where none
 * @param electedOn     when the election was made and approved, or null where it was not
 */
public record ContractualLifeFallback(
    String contractId,
    ContractTerms terms,
    String justification,
    LocalDate electedOn) {

    public ContractualLifeFallback {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(terms, "terms");
        if (contractId.isBlank()) {
            throw new IllegalArgumentException("contractId must not be blank");
        }
        // Refused rather than reported. This record is the register of contracts on which FR-310's
        // fallback was actually taken; a contract with a stated expected life did not take it, and
        // listing it here would inflate the incidence of a shortcut that is not in force — which
        // is the mirror image of the omission this endpoint is a control over, and just as wrong.
        if (terms.eirExpectedLifeMonths() != 0) {
            throw new IllegalArgumentException(
                "contract " + contractId + " states an EIR expected life of "
                    + terms.eirExpectedLifeMonths() + " months, so it did not take FR-310's"
                    + " contractual fallback; the register of fallbacks must not carry contracts"
                    + " that made an explicit election");
        }
    }

    /**
     * The contractual term in months, which is what the expected life fell back to.
     *
     * <p>Computed as {@code termPeriods × 12 ÷ periodsPerYear} rather than through
     * {@code ContractTerms.monthsPerPeriod()}, which throws where the frequency does not divide
     * twelve. A report is the wrong place to raise that: a daily-compounding facility on the
     * fallback would answer this endpoint with a 500 and the whole register would be lost over
     * one row's tenor label. Multiplying first keeps the answer exact for every commensurable
     * frequency and truncates rather than fails for the rest.
     */
    public int contractualTermMonths() {
        return terms.termPeriods() * 12 / terms.periodsPerYear();
    }

    /** Whether the election is justified and dated — the two halves of an explicit election. */
    public boolean isJustified() {
        return justification != null && !justification.isBlank() && electedOn != null;
    }

    /** The audit sentence, naming the term the life fell back to and the election, or its absence. */
    public String describe() {
        String sentence = "expected life unstated, taken as the contractual term of "
            + contractualTermMonths() + " months (FR-310, ACPIR 51)";
        if (isJustified()) {
            return sentence + "; elected " + electedOn + " on the ground that " + justification;
        }
        return sentence + "; NO justified election on file — ContractTerms cannot tell an"
            + " election from an omission, and nothing upstream records which this is";
    }
}
