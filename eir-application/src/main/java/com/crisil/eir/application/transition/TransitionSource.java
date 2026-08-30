package com.crisil.eir.application.transition;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.transition.ContractMigrationState;
import com.crisil.eir.policy.transition.EclDiscountBasis;
import com.crisil.eir.policy.transition.TransitionFairValue;
import com.crisil.eir.policy.transition.ValuationTechnique;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The two transition facts no existing port carries: what a contract was measured at on day 1, and
 * which rate its ECL is discounted at (04 § 6, FR-908).
 *
 * <p><b>Why a port of this use case's own rather than a widening of {@link ContractStateSource}.</b>
 * The same argument {@code OnboardingSource} makes. {@code OpeningState} answers "what is this
 * contract's state at the start of a period" and every field on it exists for the amortisation
 * path. A transition fair value is not a period state — it is a one-off measurement at a single
 * date, performed by a named person and reviewed by another — and an ECL discount basis is per
 * contract per period in a table of its own precisely so that the gap between the two obligations
 * stays visible (04 § 6). Widening {@code OpeningState} to carry both would make every field
 * optional on both paths, and an optional ECL discount basis on the month-end path is the single
 * merged flag 04 § 6 exists to prevent.
 *
 * <p><b>The boundary is an argument, for the reason it is everywhere else.</b> The FY27 exercise is
 * re-run repeatedly across the year as the paragraph 19 evidence file is built, and a source that
 * answered from current state would make yesterday's transition pack unreproducible — the drift
 * would look like a valuation being revised rather than like a port reading a clock. See the
 * {@code port} package javadoc, which states the argument in full.
 */
public interface TransitionSource {

    /**
     * The contract's day-1 fair value measurement, or empty where the boundary does not see one.
     *
     * <p><b>Empty rather than an exception</b>, exactly as {@code ContractStateSource.openingState}
     * and {@code OnboardingSource.onboardingRequest} do, and for FR-905's reason: a contract the
     * population names with no measurement on file is a data condition the exercise reports per
     * contract, not a reason to abandon a ten-million-contract valuation. It is emphatically not a
     * skip — {@link TransitionExercise} isolates it and {@link TransitionRun} refuses to be
     * assembled unless the population partitions across the valued and the isolated.
     */
    Optional<FairValueMeasurement> fairValueMeasurement(String contractId, AsAtBoundary boundary);

    /** The contract's ECL discount position, or empty where none is recorded at the boundary. */
    Optional<EclDiscountPosition> eclDiscountPosition(String contractId, AsAtBoundary boundary);

    /**
     * What a valuer measured, and how — everything {@link TransitionFairValue} needs except the
     * side the book already knows.
     *
     * <p><b>The pre-transition carrying amount is deliberately not here.</b> It comes from
     * {@code ContractStateSource.OpeningState.openingGca()}, and the split is the control. The
     * ACPIR 19 difference is {@code fairValue - preTransitionCarryingAmount} and it goes to opening
     * retained earnings; a source that supplied both sides could set that difference to any figure
     * it liked, nil included, by moving the side the ledger already carries. One side from the
     * book, one from the valuation, and the difference is then a fact about the measurement rather
     * than a number somebody chose.
     *
     * @param fairValue               the measured fair value
     * @param technique               how it was arrived at (04 § 6's three)
     * @param discountRateUsed        required for {@code DISCOUNTED_CASH_FLOW}, else null
     * @param paragraph19EvidenceRef  the rebuttal evidence reference where the presumption was
     *                                applied; null where it was not, and null-with-presumption is
     *                                what TF-1 reports rather than what this refuses
     * @param measuredBy              who performed the valuation
     * @param reviewedBy              who reviewed it, or null while unreviewed
     */
    record FairValueMeasurement(
        Money fairValue,
        ValuationTechnique technique,
        Rate discountRateUsed,
        String paragraph19EvidenceRef,
        String measuredBy,
        String reviewedBy) {

        public FairValueMeasurement {
            Objects.requireNonNull(fairValue, "fairValue");
            Objects.requireNonNull(technique, "technique");
            Objects.requireNonNull(measuredBy, "measuredBy");
        }

        /**
         * The measurement as a {@link TransitionFairValue}, against the carrying amount the book
         * brought forward.
         *
         * <p>Nothing is validated here that {@code TransitionFairValue} validates — a discounted
         * cash flow with no rate, a self-reviewed valuation and an evidence reference on a
         * technique that did not apply the presumption are all refused there, once. Restating any
         * of them would put the same rule in two places, and only one of them would be kept
         * current.
         */
        public TransitionFairValue at(
            String contractId, LocalDate transitionDate, Money preTransitionCarryingAmount) {
            return new TransitionFairValue(contractId, transitionDate, preTransitionCarryingAmount,
                fairValue, technique, discountRateUsed, paragraph19EvidenceRef, measuredBy,
                reviewedBy);
        }
    }

    /**
     * Which rate a contract's ECL is discounted at, and the evidence for a migration that happened
     * (ACPIR 50).
     *
     * <p><b>Whether interest is recognised on the EIR is deliberately not here.</b> That is ACPIR
     * 21's question, and {@link TransitionExercise} reads it off whether a rate is in force
     * ({@code OpeningState.hasBeenSolved()}) rather than off a flag supplied alongside the ECL
     * basis. The reason is 04 § 6's own: if one record carried both positions, the party recording
     * the discount basis would also be asserting the interest position, and the two obligations
     * would have a single source — which is the merged flag that hides the gap, wearing two field
     * names.
     *
     * @param basis                the recorded basis: interim contractual, or the EIR
     * @param eirComputationId     which solve supplied the rate; required when the basis is EIR
     * @param rateUsed             the rate actually used to discount, where recorded
     * @param eclMigratedOn        when the basis moved; required when the basis is EIR
     * @param migrationEvidenceRef a working-paper reference, where one exists
     */
    record EclDiscountPosition(
        EclDiscountBasis basis,
        String eirComputationId,
        Rate rateUsed,
        LocalDate eclMigratedOn,
        String migrationEvidenceRef) {

        public EclDiscountPosition {
            Objects.requireNonNull(basis, "basis");
        }

        /** A contract still on the interim contractual basis, with nothing yet to point at. */
        public static EclDiscountPosition interim() {
            return new EclDiscountPosition(
                EclDiscountBasis.CONTRACTUAL_INTERIM, null, null, null, null);
        }

        /** A contract whose ECL discounting has moved to the EIR. */
        public static EclDiscountPosition migrated(
            String eirComputationId, Rate rateUsed, LocalDate migratedOn, String evidenceRef) {
            return new EclDiscountPosition(
                EclDiscountBasis.EIR, eirComputationId, rateUsed, migratedOn, evidenceRef);
        }

        /**
         * This position as a {@link ContractMigrationState}, against the ACPIR 21 position the
         * engine knows.
         *
         * <p>The EIR-with-no-computation and EIR-with-no-date refusals live on
         * {@code ContractMigrationState} and are not restated here. A contract claiming a migrated
         * basis with nothing identifying what it migrated to therefore throws from inside the
         * barrier and is isolated with the rest — which is the right outcome: its position is one
         * nobody can verify later, and TM-1 then counts it as untracked rather than as migrated.
         */
        public ContractMigrationState stateOf(
            String contractId, int periodId, boolean interestRecognisedOnEir) {
            return new ContractMigrationState(contractId, periodId, interestRecognisedOnEir, basis,
                eirComputationId, rateUsed, eclMigratedOn, migrationEvidenceRef);
        }
    }
}
