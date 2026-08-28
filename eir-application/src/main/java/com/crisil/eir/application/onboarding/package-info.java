/**
 * Initial recognition — 05 § 3.1's {@code OnboardContract}, and the measurement gate that runs
 * before any of it.
 *
 * <h2>What this package is for</h2>
 *
 * <p>05 § 3.1 is one sequence diagram and one paragraph of prose, and the paragraph is the
 * requirement:
 *
 * <blockquote>The SPPI/measurement gate runs <b>first</b>, before any projection or solve. On the
 * asset side an SPPI failure is a cliff, not a gradient: the whole instrument goes to FVTPL and no
 * EIR arises. Doing expensive work before that check is waste, and worse, produces a rate for an
 * instrument that should not have one.</blockquote>
 *
 * <p>So the package is organised around making that ordering <em>observable</em> rather than
 * asserted. {@link com.crisil.eir.application.onboarding.MeasurementGate} decides on three attribute
 * reads and a decision table; {@link com.crisil.eir.application.onboarding.InitialRecognition}
 * returns from the FVTPL branch before the projector or the solver has been touched, both of them
 * supplied as the published {@code eir-calc} seams so a test can count zero calls; and
 * {@link com.crisil.eir.application.onboarding.OnboardingOutcome#workPerformed()} records which
 * stages a contract consumed, with ST-12's work leg breaching on any excluded contract that shows a
 * projection or a solve.
 *
 * <h2>The three answers, and which of them is an exception</h2>
 *
 * <p>{@link com.crisil.eir.application.onboarding.OnboardingDisposition} has three members where the
 * month-end run's {@link com.crisil.eir.application.ContractResult} has two, and the extra one is the
 * point: an FVTPL instrument is <b>recorded and excluded from EIR processing</b> (FR-103), which is
 * neither a computed figure nor a failure. An SPPI failure is that middle answer. It is not an
 * exception-queue entry, and {@link com.crisil.eir.application.onboarding.MeasurementDecision}
 * argues why at length — filing it would put a correctly-measured instrument in a queue that blocks
 * the close under 04 § 3, and none of the ten categories describes it.
 *
 * <h2>What the schema already said</h2>
 *
 * <p>{@link com.crisil.eir.application.onboarding.MeasurementCategory},
 * {@link com.crisil.eir.application.onboarding.InstrumentClass} and
 * {@link com.crisil.eir.application.onboarding.SppiOutcome} carry, character for character, the
 * values of the CHECK constraints in
 * {@code eir-persistence/src/main/resources/db/migration/V1__core_entities.sql}, and
 * {@code MeasurementGate}'s decision table is those constraints as branches. Deliberately: a gate the
 * application enforces and the schema does not is a gate a bulk load goes around, and a gate the
 * schema enforces and the application does not is a run that dies at the writer having done all the
 * work. The migration's own comment on
 * {@code contract_amortised_cost_needs_sppi_ck} records that its first live run accepted a row with a
 * NULL {@code sppi_outcome}, because a CHECK is satisfied when its expression is NULL; the Java
 * analogue of that defect is a null-checked read that short-circuits to "passed", and the gate tests
 * for the absence of the assessment before it reads the outcome.
 *
 * <h2>Time</h2>
 *
 * <p>Nothing here reads a clock. Dates arrive on the {@link com.crisil.eir.application.port.AsAtBoundary}
 * for the population and the source, on each {@link com.crisil.eir.application.onboarding.FeeSubmission}
 * for the rule-set lookup, and on
 * {@link com.crisil.eir.application.onboarding.OnboardingRequest#initialRecognitionDate()} for the
 * tier policy's governance question — which for a commitment is the day the bank became party to the
 * irrevocable commitment (ACPIR 23) and not the date of first drawdown. 03 § 1.1 and ADR-0003: a run
 * that reads {@code Instant.now()} cannot be re-run, which is DT-1.
 */
package com.crisil.eir.application.onboarding;
