package com.crisil.eir.application.onboarding;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.policy.exception.ExceptionCategory;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * What the measurement gate concluded about one contract, before any EIR work was commissioned
 * (FR-103, FR-104, 05 § 3.1).
 *
 * <p><b>Three outcomes, and only one of them is an exception.</b>
 *
 * <ol>
 *   <li><b>Admitted.</b> {@link #effectiveCategory()} carries an EIR, so the pipeline goes on to
 *       classify fees, assign a tier, project and solve.
 *   <li><b>Excluded.</b> {@link MeasurementCategory#FVTPL}. The contract is <em>recorded</em> and
 *       excluded from EIR processing — 05 § 3.1's {@code APP->>DB: record, exclude from EIR
 *       processing}. This is not a failure of any kind. It is the answer.
 *   <li><b>Refused.</b> The gate could not conclude, because an asset claims amortised cost or
 *       FVOCI with no SPPI assessment on file. That one goes to the exception queue under
 *       {@link ExceptionCategory#MISSING_MANDATORY_FIELD}, because a mandatory input is absent.
 * </ol>
 *
 * <p><b>An SPPI failure is emphatically not the third one.</b> It is the second. Filing it as an
 * exception would put a correctly-classified FVTPL instrument in a queue somebody has to work, and
 * 04 § 3 makes every category "a hard stop for that contract" that "blocks the close unless
 * explicitly accepted with approval" — so a bank with a trading book would open every period with a
 * queue of instruments whose classification is right, and the acceptance-with-approval that
 * unblocks the close would become the rubber stamp {@code ExceptionQueue} warns about. Nothing in
 * {@link ExceptionCategory}'s ten members describes it either: read them and the closest is
 * {@code MISSING_MANDATORY_FIELD}, which is about an absent input rather than a present and
 * decisive one.
 *
 * <p>Exactly one of {@link #effectiveCategory()} and {@link #refusal()} is populated, the same
 * discipline {@link com.crisil.eir.policy.fee.rule.FeeClassificationResolution} and
 * {@link com.crisil.eir.application.ContractResult} enforce, and for the same reason: a value whose
 * meaning depends on which field the reader consults has two meanings.
 *
 * @param contractId        the contract gated
 * @param instrumentClass   which side of FR-104 it sits on
 * @param declaredCategory  the category the source system asserted — an input to be checked, never
 *                          the conclusion
 * @param assessment        the SPPI assessment on file, or null where there is none
 * @param effectiveCategory the category the gate concluded, or null on a refusal
 * @param refusal           the exception-queue category, or null where the gate concluded
 * @param detail            what the gate found, in one sentence; never blank
 * @param classificationCheck the ST-12 result for the classification leg, or null where the gate
 *                          had nothing to assert — see {@link #classificationInvariant()}
 */
public record MeasurementDecision(
    String contractId,
    InstrumentClass instrumentClass,
    MeasurementCategory declaredCategory,
    SppiAssessment assessment,
    MeasurementCategory effectiveCategory,
    ExceptionCategory refusal,
    String detail,
    InvariantResult classificationCheck) {

    public MeasurementDecision {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(instrumentClass, "instrumentClass");
        Objects.requireNonNull(declaredCategory, "declaredCategory");
        Objects.requireNonNull(detail, "detail");
        if (contractId.isBlank()) {
            throw new IllegalArgumentException(
                "a measurement decision names the contract it gated; FR-905 isolates per contract"
                    + " and a decision nobody can file cannot exclude anything from anything");
        }
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "measurement decision on contract " + contractId + " states no detail; the"
                    + " conclusion alone tells an auditor nothing about which limb of FR-104 it"
                    + " came from");
        }
        if ((effectiveCategory == null) == (refusal == null)) {
            throw new IllegalArgumentException(
                "measurement decision on contract " + contractId + " is exactly one of concluded"
                    + " and refused; it is "
                    + (effectiveCategory == null ? "neither" : "both"));
        }
        if (classificationCheck != null && classificationCheck.id() != InvariantId.ST_12) {
            throw new IllegalArgumentException(
                "the gate's own invariant is " + InvariantId.ST_12 + " ("
                    + InvariantId.ST_12.statement() + "), got " + classificationCheck.id()
                    + " on contract " + contractId);
        }
    }

    /**
     * A contract admitted to EIR processing, or excluded from it, with the ST-12 classification
     * result the gate computed.
     */
    static MeasurementDecision concluded(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment assessment, MeasurementCategory effectiveCategory, String detail,
        InvariantResult classificationCheck) {
        Objects.requireNonNull(effectiveCategory, "effectiveCategory");
        return new MeasurementDecision(contractId, instrumentClass, declaredCategory, assessment,
            effectiveCategory, null, detail, classificationCheck);
    }

    /**
     * A contract the gate could not conclude on, bound for the exception queue.
     *
     * <p>No ST-12 result: see {@link #classificationInvariant()} for why an absent result is the
     * honest report and a passing one would not be.
     */
    static MeasurementDecision refused(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment assessment, ExceptionCategory refusal, String detail) {
        Objects.requireNonNull(refusal, "refusal");
        return new MeasurementDecision(contractId, instrumentClass, declaredCategory, assessment,
            null, refusal, detail, null);
    }

    /**
     * Whether an effective interest rate arises, and therefore whether any projection or solve is
     * licensed for this contract.
     *
     * <p>The predicate the pipeline branches on, and the one 05 § 3.1's {@code alt FVTPL} block is.
     * False on a refusal too: a contract whose category could not be established has not been
     * licensed for expensive work either, and defaulting an unconcluded gate to "carry on" is how
     * a rate gets produced for an instrument that should not have one.
     */
    public boolean eirApplies() {
        return effectiveCategory != null && effectiveCategory.carriesEir();
    }

    /** Whether the gate refused rather than concluding. */
    public boolean isRefused() {
        return refusal != null;
    }

    /**
     * Whether the contract is recorded and excluded from EIR processing — the FVTPL branch
     * (FR-103).
     */
    public boolean isExcludedFromEir() {
        return effectiveCategory == MeasurementCategory.FVTPL;
    }

    /**
     * The ST-12 result for the classification leg, or empty where the gate refused.
     *
     * <p><b>Why empty rather than a pass.</b> ST-12's statement is "SPPI failure yields no EIR". On
     * a refusal there is no SPPI outcome at all, so the claim has no subject; publishing it as
     * satisfied would add a result to the population conjunction that no input could ever have
     * turned into a breach. This codebase has shipped several of those and treats them as worse
     * than absent controls because they read as coverage — so the absence is reported instead, by
     * {@link OnboardingRun#unassertedInvariants()}.
     *
     * <p><b>What input makes the published result fail.</b> An instrument on the asset side whose
     * SPPI assessment reads {@code FAIL} and whose source system nonetheless declared it at
     * amortised cost or FVOCI: {@code InstrumentClass.LOAN}, {@code SppiOutcome.FAIL},
     * {@code MeasurementCategory.AMORTISED_COST}. That is the Java form of the schema's
     * {@code contract_sppi_fail_implies_fvtpl_ck}, and the two inputs it compares are independent —
     * the assessment comes from the classification file and the category from the contract master,
     * which is why the comparison can disagree at all.
     */
    public Optional<InvariantResult> classificationInvariant() {
        return Optional.ofNullable(classificationCheck);
    }

    /** The deviation ST-12 carries when the classification legs disagree: one instrument. */
    static final BigDecimal ONE_INSTRUMENT = BigDecimal.ONE;

    /** One audit line. */
    public String describe() {
        return contractId + ": " + detail;
    }

    @Override
    public String toString() {
        return describe();
    }
}
