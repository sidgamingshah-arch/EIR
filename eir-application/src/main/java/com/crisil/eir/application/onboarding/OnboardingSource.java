package com.crisil.eir.application.onboarding;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractStateSource;
import java.util.Optional;

/**
 * One contract's initial-recognition input, as at the boundary — 05 § 3.1's {@code CBS->>API:
 * contract + schedule + fee postings} plus the classification attributes of 04 § 2.1.
 *
 * <p><b>Why a port of its own rather than {@link ContractStateSource}.</b> That port answers "what
 * is this contract's state at the start of a period" and its {@code OpeningState} carries an opening
 * balance, a stage, an allowance and an ECL engine version — none of which exists for a contract
 * being recognised for the first time. What initial recognition needs and it does not have is the
 * classification triple ({@code instrument_class}, {@code measurement_category}, the SPPI
 * assessment) and the unclassified fee postings. Widening {@code OpeningState} to carry both use
 * cases' fields would make every field optional on both paths, and an optional SPPI outcome on the
 * month-end path is exactly the "gate was skipped" state FR-104 exists to prevent.
 *
 * <p><b>The boundary is an argument, for the same reason it is everywhere else.</b> 05 § 3.3 makes a
 * replay read "the contract version set as at the original run's {@code recorded_at}"; a source that
 * answered from current state would produce an onboarding that is internally consistent and does not
 * reproduce, and DT-1 would fail with no way to say which input drifted. See the {@code port}
 * package javadoc, which states the argument in full.
 *
 * <p>Declared here rather than in {@code com.crisil.eir.application.port} because it is this use
 * case's own input and no other use case reads it; the shared package holds the ports a run's
 * vocabulary is built from and is not this unit's to extend.
 */
public interface OnboardingSource {

    /**
     * The contract's initial-recognition input, or empty where the boundary does not see it.
     *
     * <p><b>Empty rather than an exception</b>, and the choice is FR-905's. A contract that the
     * population names and the master does not carry is a data condition the run reports per
     * contract — {@link InitialRecognition} quarantines it under
     * {@link com.crisil.eir.policy.exception.ExceptionCategory#MISSING_MANDATORY_FIELD} — and not a
     * reason to abandon the batch. {@code ContractStateSource.openingState} makes the same choice
     * for the same reason.
     *
     * <p><b>And emphatically not a null or a skip.</b> Returning nothing for a contract in the
     * population, and having the pipeline quietly move on, is the failure
     * {@link com.crisil.eir.application.ContractResult}'s javadoc describes: "a run over 10,000,000
     * contracts that silently processed 9,999,998 reconciles perfectly, because the two it dropped
     * are absent from both sides of every total." Every id the population names gets an outcome, and
     * {@link OnboardingRun} refuses to be assembled otherwise.
     */
    Optional<OnboardingRequest> onboardingRequest(String contractId, AsAtBoundary boundary);
}
