package com.crisil.eir.application.onboarding;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentInput;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One {@code OnboardContract} call: everything 05 § 3.1 needs to recognise a contract for the first
 * time.
 *
 * <p>Assembled from the sequence diagram's first message — {@code CBS->>API: contract + schedule +
 * fee postings} — plus the three classification attributes 04 § 2.1 puts on the contract identity:
 * {@code instrument_class}, {@code measurement_category} and the SPPI triple.
 *
 * <p><b>The classification attributes are inputs to be checked, not conclusions to be obeyed.</b>
 * {@code declaredCategory} is what the source system asserted. {@link MeasurementGate} compares it
 * against the SPPI assessment and concludes; where the two disagree the assessment wins and ST-12
 * breaches. That is only possible because the two arrive as separate fields from separate sources —
 * collapsing them into one "effective category" at the ingestion boundary would make the comparison
 * a field against itself, the trap {@code CoreBankingFeed}'s javadoc names and this programme has
 * now found several times.
 *
 * <p><b>Terms are mandatory even for a contract that will be excluded.</b> 05 § 3.1's first message
 * carries the schedule, and FR-103's exclusion is from <em>EIR processing</em>, not from the record:
 * the FVTPL branch of the diagram still writes to the database. A request that could omit the terms
 * for an FVTPL instrument would also let the terms be omitted for one whose category turns out,
 * after the gate runs, to carry an EIR — and the pipeline would then have to fail at the projector
 * having already accepted the contract.
 *
 * <p><b>No clock.</b> Every date here is supplied: the posting dates on the fee submissions, the
 * disbursement and first-due dates inside {@code terms}, and {@code initialRecognitionDate}. 03
 * § 1.1 makes time an input, and ADR-0003 makes the consequence concrete — a run that reads
 * {@code Instant.now()} cannot be re-run, which is DT-1.
 *
 * @param contractId             the contract being recognised
 * @param instrumentClass        which side of FR-104 it sits on
 * @param declaredCategory       the category the source system asserted (04 § 2.1)
 * @param sppiAssessment         the SPPI assessment on file, or null where there is none
 * @param initialRecognitionDate 04 § 2.1's {@code initial_recognition_date}. For a commitment this
 *                               is the day the bank became party to the irrevocable commitment
 *                               (ACPIR 23), <b>not</b> the date of first drawdown — which is why it
 *                               is carried separately rather than read off
 *                               {@code terms.disbursementDate()}
 * @param productId              the product, for the fee rule set's second key component
 *                               (04 § 2.5); null where the feed carries none, which resolves
 *                               against the per-code default rather than a product carve-out
 * @param entityId               the booking entity, for the rule set's third key component; null
 *                               where the feed carries none
 * @param terms                  the contract terms the projector needs
 * @param feeSubmissions         the fee and cost postings, unclassified; may be empty
 * @param segment                the counterparty segment the materiality gate keys on (03 § 10)
 * @param tierFeatures           the § 10 attributes asserted for this contract
 * @param exposureAtOrigination  size at initial recognition for the Tier 1 wholesale Board-threshold
 *                               limb, or null where unsourced. Null rather than zero, deliberately:
 *                               {@code TierAssignmentInput}'s javadoc records that "a zero exposure
 *                               would read as below the Board threshold and escape Tier 1"
 */
public record OnboardingRequest(
    String contractId,
    InstrumentClass instrumentClass,
    MeasurementCategory declaredCategory,
    SppiAssessment sppiAssessment,
    LocalDate initialRecognitionDate,
    String productId,
    String entityId,
    ContractTerms terms,
    List<FeeSubmission> feeSubmissions,
    TierAssignmentSegment segment,
    Set<TierAssignmentFeature> tierFeatures,
    Money exposureAtOrigination) {

    public OnboardingRequest {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(instrumentClass, "instrumentClass");
        Objects.requireNonNull(declaredCategory, "declaredCategory");
        Objects.requireNonNull(initialRecognitionDate, "initialRecognitionDate");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(segment, "segment");
        contractId = contractId.strip();
        if (contractId.isEmpty()) {
            throw new IllegalArgumentException(
                "an onboarding request needs a contract id; FR-905 isolates per contract, and a"
                    + " record nobody can name cannot be quarantined, reported on, or excluded"
                    + " from the population");
        }
        feeSubmissions = List.copyOf(Objects.requireNonNull(feeSubmissions, "feeSubmissions"));
        tierFeatures = tierFeatures == null || tierFeatures.isEmpty()
            ? Collections.unmodifiableSet(EnumSet.noneOf(TierAssignmentFeature.class))
            : Collections.unmodifiableSet(EnumSet.copyOf(tierFeatures));
        if (exposureAtOrigination != null
            && !exposureAtOrigination.currency().equals(terms.currency())) {
            // Refused rather than reported, because the tier gate's own answer to a currency
            // mismatch is to fall to TIER_1_DEFAULT_CONTRACT_LEVEL silently — correct there,
            // because converting would need an FX policy that unit does not own, but here the
            // mismatch is between two fields of one request and is a wiring defect. Letting it
            // through would put every such contract in Tier 1 for a reason no report explains.
            throw new IllegalArgumentException(
                "contract " + contractId + " states an exposure in "
                    + exposureAtOrigination.currency().getCurrencyCode() + " and terms in "
                    + terms.currency().getCurrencyCode()
                    + "; the tier gate compares the exposure with a Board threshold and answers"
                    + " false on a currency mismatch, so this contract would fall to the"
                    + " conservative default with nothing on the record saying why");
        }
    }

    /**
     * The minimal request: no fee postings, no § 10 attributes, no sourced exposure.
     *
     * <p>The initial recognition date defaults to the disbursement date, which is right for an
     * advance and wrong for a commitment — hence the full constructor for the latter, and hence
     * ACPIR 23 being quoted on the component.
     */
    public static OnboardingRequest of(
        String contractId, InstrumentClass instrumentClass, MeasurementCategory declaredCategory,
        SppiAssessment sppiAssessment, ContractTerms terms, TierAssignmentSegment segment) {

        return new OnboardingRequest(contractId, instrumentClass, declaredCategory, sppiAssessment,
            terms.disbursementDate(), null, null, terms, List.of(), segment,
            EnumSet.noneOf(TierAssignmentFeature.class), null);
    }

    /** The same request with fee and cost postings attached. */
    public OnboardingRequest withFees(List<FeeSubmission> submissions) {
        return new OnboardingRequest(contractId, instrumentClass, declaredCategory, sppiAssessment,
            initialRecognitionDate, productId, entityId, terms, submissions, segment, tierFeatures,
            exposureAtOrigination);
    }

    /** The same request keyed to a product and entity, for the fee rule set's carve-outs. */
    public OnboardingRequest withRuleSetKey(String product, String entity) {
        return new OnboardingRequest(contractId, instrumentClass, declaredCategory, sppiAssessment,
            initialRecognitionDate, product, entity, terms, feeSubmissions, segment, tierFeatures,
            exposureAtOrigination);
    }

    /** The same request with a § 10 attribute asserted. */
    public OnboardingRequest with(TierAssignmentFeature feature) {
        EnumSet<TierAssignmentFeature> extended = EnumSet.noneOf(TierAssignmentFeature.class);
        extended.addAll(tierFeatures);
        extended.add(Objects.requireNonNull(feature, "feature"));
        return new OnboardingRequest(contractId, instrumentClass, declaredCategory, sppiAssessment,
            initialRecognitionDate, productId, entityId, terms, feeSubmissions, segment, extended,
            exposureAtOrigination);
    }

    /** The same request with a sourced exposure, for the wholesale Board-threshold limb. */
    public OnboardingRequest withExposure(Money exposure) {
        return new OnboardingRequest(contractId, instrumentClass, declaredCategory, sppiAssessment,
            initialRecognitionDate, productId, entityId, terms, feeSubmissions, segment,
            tierFeatures, exposure);
    }

    /**
     * The materiality gate's input, with the tenor derived from the terms rather than supplied.
     *
     * <p>Derived on purpose. {@code TierAssignmentInput.tenorMonthsBetween} rounds a part month
     * <em>up</em>, and its javadoc gives the reason and the worked case: the 364-day T-bill from
     * 1 Apr 2027 to 31 Mar 2028 is eleven whole months plus thirty days, returns 12, and is Tier 3
     * where § 10 puts it. A separately-supplied tenor field would be a second answer to a question
     * the terms already answer, and the two would eventually disagree — at which point the tier a
     * contract was measured under would depend on which field the caller populated.
     *
     * <p>A revolver has no contractual maturity and therefore no meaningful tenor; it declares
     * {@link TierAssignmentFeature#CARD_OR_KCC_REVOLVER}, which
     * {@code TierAssignmentRule.TIER_2_CARD_OR_KCC_REVOLVER} matches ahead of both tenor rules and
     * ahead of {@code TIER_1_TENOR_NOT_DETERMINABLE}.
     */
    public TierAssignmentInput tierInput() {
        Integer tenorMonths =
            TierAssignmentInput.tenorMonthsBetween(terms.disbursementDate(), terms.maturityDate());
        return new TierAssignmentInput(
            contractId, segment, tenorMonths, exposureAtOrigination, tierFeatures);
    }

    /** Whether any fee or cost posting accompanies this contract. */
    public boolean hasFeeSubmissions() {
        return !feeSubmissions.isEmpty();
    }
}
