package com.crisil.eir.application.transition;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.transition.ContractMigrationState;
import com.crisil.eir.policy.transition.TransitionFairValue;
import java.util.Objects;

/**
 * One contract's transition position: what it was valued at, where its two migration obligations
 * stand, and which rate it will be measured on (04 § 6).
 *
 * <p><b>Three facts, one record, and none of them derived from another.</b> The valuation is the
 * ACPIR 19 measurement; the migration state is the pair of positions against ACPIR 21 and ACPIR 50;
 * the rate assignment is the plan's method applied to the contract. They are kept together because
 * a programme team works one contract at a time, and kept separate as fields because merging any
 * two of them would recreate the single flag 04 § 6 gives the ECL discount basis its own table to
 * avoid.
 *
 * <p><b>There is no per-contract disposition enum here.</b> {@code OnboardingOutcome} needs one
 * because initial recognition has three genuinely different exits — recognised, excluded from the
 * EIR entirely, quarantined. This exercise has two, and the second is not a value on this record:
 * a contract the barrier isolated has no valuation and no recorded basis, so it is carried as an
 * {@code ExceptionRecord} on {@link TransitionRun} rather than as an outcome with null fields. A
 * synthetic outcome would make "every contract in the population was valued or isolated" a claim
 * nothing could falsify.
 *
 * @param contractId     the contract
 * @param valuation      its ACPIR 19 day-1 fair value at the transition date
 * @param migration      its ACPIR 21 and ACPIR 50 positions for the period
 * @param rateAssignment where its EIR comes from under the migration plan
 */
public record ContractTransitionOutcome(
    String contractId,
    TransitionFairValue valuation,
    ContractMigrationState migration,
    LegacyRateAssignment rateAssignment) {

    public ContractTransitionOutcome {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(valuation, "valuation");
        Objects.requireNonNull(migration, "migration");
        Objects.requireNonNull(rateAssignment, "rateAssignment");
        if (!valuation.contractId().equals(contractId)
            || !migration.contractId().equals(contractId)
            || !rateAssignment.contractId().equals(contractId)) {
            // Three facts about one contract, and a mismatch means one of them belongs to another.
            // Refused rather than reported: the figures would be individually valid and the
            // aggregate would silently value one contract twice and another not at all, which is
            // the shape of loss FR-905's accounting exists to make impossible.
            throw new IllegalArgumentException(
                "outcome for contract " + contractId + " carries a valuation for "
                    + valuation.contractId() + ", a migration state for " + migration.contractId()
                    + " and a rate assignment for " + rateAssignment.contractId()
                    + "; one of these belongs to a different contract");
        }
    }

    /**
     * The ACPIR 19 difference for this contract, to opening retained earnings.
     *
     * <p>Delegated, and named the way the policy type names it. There is deliberately no accessor
     * anywhere on this path that would let the figure be read as a period result — that is
     * {@code TransitionFairValue}'s stated control, and adding a plainly-named
     * {@code difference()} here would defeat it one layer up.
     */
    public Money differenceToOpeningRetainedEarnings() {
        return valuation.differenceToOpeningRetainedEarnings();
    }

    /**
     * This contract's TF-1 result, for a programme team working the paragraph 19 evidence file.
     *
     * <p>Per contract as well as per run because the two readings are used at different times, which
     * is {@code TransitionFairValue}'s own argument. The run-level count is the one
     * {@link TransitionRun} publishes; publishing one TF-1 per contract to a close gate would report
     * a deviation of 1 however many contracts were unevidenced, because
     * {@code InvariantResult.conjunction} keeps only the first breach's deviation among results
     * sharing an id.
     */
    public InvariantResult paragraph19Evidenced() {
        return valuation.paragraph19Evidenced();
    }

    /** Whether ACPIR 21 is satisfied: interest is recognised on the EIR. */
    public boolean satisfiesAcpir21() {
        return migration.satisfiesAcpir21();
    }

    /** Whether ACPIR 50 is satisfied: the ECL is discounted at the EIR. */
    public boolean satisfiesAcpir50() {
        return migration.satisfiesAcpir50();
    }

    /** A one-line audit sentence covering all three facts. */
    public String describe() {
        return valuation.describe() + "; " + migration.describe() + "; "
            + rateAssignment.describe();
    }
}
