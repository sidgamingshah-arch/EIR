package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.calc.projection.RevolvingApproximation;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One product's ACPIR 54 election: which revolving approximation applies, and on what evidence.
 *
 * <p>{@code RevolvingApproximation}'s javadoc fixes the grain: which approximation applies "turns
 * on whether utilisation is modellable, and that is a product-level assessment with evidence
 * behind it, not a per-contract choice made at projection time". So the reportable unit is the
 * product, and the evidence is the assessment date.
 *
 * <p><b>The check worth having.</b> {@code RevolvingProjector} implements
 * {@link RevolvingApproximation#FEE_OVER_RENEWAL} and refuses
 * {@link RevolvingApproximation#EIR_OVER_UTILISATION} in its constructor, because the latter needs
 * a utilisation profile — for cards, the ACPIR 46(2)(iii) behavioural analysis of historical
 * default patterns, drawdown behaviour and the effectiveness of limit reduction, suspension or
 * cancellation (FR-308) — and that profile is a schedule, so the honest route is
 * {@code ExternalScheduleProjector}. An election of {@code EIR_OVER_UTILISATION} with no
 * utilisation profile named is therefore a policy election this engine cannot honour, and
 * {@link #honourable()} reports it as unevidenced rather than letting it read as an approximation
 * in force. That distinction is the whole point: a book whose policy says one thing and whose
 * projector does another is measuring on a basis nobody approved.
 *
 * @param productCode         the product the election covers, in {@code FeeRuleKey}'s vocabulary
 * @param approximation       the elected ACPIR 54 approximation
 * @param renewalPeriodMonths the renewal or sanction period the fee defers over; 0 where the
 *                            election is {@code EIR_OVER_UTILISATION} and no renewal leg applies
 * @param utilisationProfile  the named utilisation profile artefact, or null where none is held
 * @param assessedOn          when the product-level assessment was performed, or null where it
 *                            was not
 * @param policyBasis         the recorded ground for the election; blank where none
 */
public record RevolvingElection(
    String productCode,
    RevolvingApproximation approximation,
    int renewalPeriodMonths,
    String utilisationProfile,
    LocalDate assessedOn,
    String policyBasis) {

    public RevolvingElection {
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(approximation, "approximation");
        productCode = productCode.strip();
        if (productCode.isBlank()) {
            throw new IllegalArgumentException(
                "an ACPIR 54 election names the product it covers; the assessment is"
                    + " product-level and an unnamed election cannot be matched to a book");
        }
        if (renewalPeriodMonths < 0) {
            throw new IllegalArgumentException(
                "election for " + productCode + " defers over " + renewalPeriodMonths
                    + " months; a negative renewal period is a feed error, not a facility");
        }
        if (approximation == RevolvingApproximation.FEE_OVER_RENEWAL && renewalPeriodMonths < 1) {
            // FEE_OVER_RENEWAL defers the integral fee over the renewal or sanction period. A
            // period of zero is not a very short renewal; it is the field that says how long the
            // deferral runs arriving empty, and the deferral then either divides by zero or
            // recognises the whole fee at once — which is the treatment ACPIR 53 exists to
            // prevent, arriving through a missing attribute.
            throw new IllegalArgumentException(
                "election for " + productCode + " is FEE_OVER_RENEWAL with no renewal period;"
                    + " the fee defers over that period, so an absent one is not a short"
                    + " renewal but the absence of the deferral");
        }
    }

    /**
     * Whether this engine can actually apply the elected approximation.
     *
     * <p>False for {@code EIR_OVER_UTILISATION}, always, and the reason is in
     * {@code RevolvingProjector}: its constructor accepts only {@code FEE_OVER_RENEWAL}, so the
     * election cannot be honoured however well evidenced it is. Reported rather than refused at
     * construction, because a product whose policy elects an approximation the engine does not
     * implement is a real and reportable state of the bank — refusing it here would make the
     * state unrepresentable and therefore silent.
     */
    public boolean honourable() {
        return approximation == RevolvingApproximation.FEE_OVER_RENEWAL;
    }

    /** Whether the product-level assessment ACPIR 54 requires is on file, dated and reasoned. */
    public boolean isAssessed() {
        return assessedOn != null && policyBasis != null && !policyBasis.isBlank();
    }

    /** Whether the utilisation profile {@code EIR_OVER_UTILISATION} computes over is named. */
    public boolean hasUtilisationProfile() {
        return utilisationProfile != null && !utilisationProfile.isBlank();
    }

    /**
     * Whether the evidence ACPIR 54 requires for <em>this</em> election is on file.
     *
     * <p>Evidence only. {@link #honourable()} is a separate question and is deliberately not
     * folded in here: an election this engine cannot apply may still be perfectly well evidenced,
     * and conflating the two would report a policy gap where the gap is in the engine — the
     * remediation for one is a policy assessment and for the other a projector. The register
     * requires both and says which is missing.
     *
     * <p>{@code EIR_OVER_UTILISATION} additionally needs the utilisation profile named, because
     * without it the approximation has no drawdown and repayment profile to compute an EIR over,
     * and there is nothing for the assessment to have concluded about.
     */
    public boolean evidenced() {
        if (!isAssessed()) {
            return false;
        }
        return approximation != RevolvingApproximation.EIR_OVER_UTILISATION
            || hasUtilisationProfile();
    }

    /** The audit sentence: what was elected, over what period, and on what evidence. */
    public String describe() {
        StringBuilder sentence = new StringBuilder("ACPIR 54 election ")
            .append(approximation).append(" for ").append(productCode);
        if (approximation == RevolvingApproximation.FEE_OVER_RENEWAL) {
            sentence.append(", integral fee deferred over a ").append(renewalPeriodMonths)
                .append("-month renewal period");
        }
        if (isAssessed()) {
            sentence.append("; assessed ").append(assessedOn).append(" — ").append(policyBasis);
        } else {
            sentence.append("; NO dated product-level assessment on file, and ACPIR 54 makes the"
                + " choice of approximation a product-level assessment with evidence behind it");
        }
        if (!honourable()) {
            sentence.append("; NOT APPLICABLE in this engine — RevolvingProjector implements only"
                + " FEE_OVER_RENEWAL and refuses EIR_OVER_UTILISATION, which needs a utilisation"
                + " profile supplied as a schedule (FR-308, ACPIR 46(2)(iii))");
        }
        return sentence.toString();
    }
}
