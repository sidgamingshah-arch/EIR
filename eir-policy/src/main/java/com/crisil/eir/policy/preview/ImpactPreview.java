package com.crisil.eir.policy.preview;

import com.crisil.eir.domain.AnywhereOnEarth;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The portfolio-level movement a draft policy version would cause, measured before it goes
 * effective. FR-210's "stored portfolio-level impact preview"; 04 § 2.12's
 * {@code impact_preview_ref} points at one of these.
 *
 * <p>Four figures, and each is here because the other three can hide the thing it shows:
 *
 * <ul>
 *   <li>{@link #contractsAffected()} — a movement of ten crore is a different fact when it is
 *       one exposure and when it is forty thousand.
 *   <li>{@link #grossCarryingAmountDelta()} — the balance-sheet restatement.
 *   <li>{@link #recognisedInterestDelta()} — the P&amp;L movement for the period, which is where
 *       03 § 3.6's 3.73x leverage lands: year-one net fee recognition of 2,525.04 becomes
 *       9,410.03 on the same exposure when assumed life compresses from 240 months to 96.
 *   <li>{@link #largestSingleContractMovement()} — the concentration. A portfolio total can net
 *       to nearly nothing while one contract moves materially, and a preview reporting only the
 *       total would read as immaterial.
 * </ul>
 *
 * <p>Plus the rate terms, as a weighted-average EIR before and after, because a portfolio whose
 * carrying amount barely moves can still have been repriced: the money deltas for a given period
 * are small when the effective date is close to a period end, while the rate shift persists for
 * the remaining life of every affected contract.
 *
 * <p><b>{@code largestSingleContractMovement} is movement in gross carrying amount, signed.</b>
 * Stated because "largest movement" has two candidate meanings here and the ambiguity would be
 * unresolvable from a persisted figure. Gross carrying amount, because the concentration
 * question the field exists to answer — is this portfolio total actually one exposure? — is a
 * balance-sheet question, and because tying it to the same quantity as
 * {@link #grossCarryingAmountDelta()} makes {@link #incoherences()} able to check the two
 * against each other. Signed rather than absolute, because a 12-crore uplift on one contract and
 * a 12-crore writedown on one contract are not the same disclosure. Which contract it was is
 * deliberately not carried: this is a portfolio-level record, and the drill-down is the
 * computation trace (FR-808).
 *
 * <p>The record throws only for what makes it structurally unusable — a null, a blank id, a
 * negative count, mixed currencies. Everything that is a <em>statement about the numbers</em>
 * is reported by {@link #incoherences()} as text, never thrown, because a preview arriving with
 * self-contradictory figures is a data condition the gate must be able to refuse and name rather
 * than a defect in the caller's code.
 *
 * @param policyVersionId    the version this previews, e.g. {@code "FEE-2027.1"}
 * @param draftFingerprint   content identity of the draft it was computed against
 * @param generatedAt        when it was run; the age axis of staleness
 * @param portfolioAsOf      the book position the deltas were measured against
 * @param contractsAffected  how many contracts move at all
 * @param grossCarryingAmountDelta portfolio change in gross carrying amount
 * @param recognisedInterestDelta  portfolio change in interest recognised for the period
 * @param largestSingleContractMovement largest single-contract change in gross carrying amount
 * @param weightedAverageEirBefore weighted-average EIR under the version in force
 * @param weightedAverageEirAfter  weighted-average EIR under the draft
 */
public record ImpactPreview(
    String policyVersionId,
    DraftFingerprint draftFingerprint,
    Instant generatedAt,
    LocalDate portfolioAsOf,
    long contractsAffected,
    Money grossCarryingAmountDelta,
    Money recognisedInterestDelta,
    Money largestSingleContractMovement,
    Rate weightedAverageEirBefore,
    Rate weightedAverageEirAfter) {

    public ImpactPreview {
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        Objects.requireNonNull(draftFingerprint, "draftFingerprint");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(portfolioAsOf, "portfolioAsOf");
        Objects.requireNonNull(grossCarryingAmountDelta, "grossCarryingAmountDelta");
        Objects.requireNonNull(recognisedInterestDelta, "recognisedInterestDelta");
        Objects.requireNonNull(largestSingleContractMovement, "largestSingleContractMovement");
        Objects.requireNonNull(weightedAverageEirBefore, "weightedAverageEirBefore");
        Objects.requireNonNull(weightedAverageEirAfter, "weightedAverageEirAfter");
        if (policyVersionId.isBlank()) {
            throw new IllegalArgumentException(
                "an impact preview must name the policy version it previews, or it cannot be"
                    + " matched to one");
        }
        if (contractsAffected < 0) {
            throw new IllegalArgumentException(
                "contractsAffected must be non-negative, got " + contractsAffected);
        }
        // Mixed currencies would make the three deltas incomparable and the concentration ratio
        // meaningless. Thrown rather than reported: a cross-currency portfolio total is not a
        // figure anybody can act on, and the caller has an aggregation defect, not a book with a
        // surprise in it. Money.minus takes the same position for the same reason.
        Currency currency = grossCarryingAmountDelta.currency();
        requireSameCurrency(currency, recognisedInterestDelta, "recognisedInterestDelta");
        requireSameCurrency(currency, largestSingleContractMovement, "largestSingleContractMovement");
    }

    private static void requireSameCurrency(Currency expected, Money actual, String field) {
        if (!expected.equals(actual.currency())) {
            throw new IllegalArgumentException(
                "impact preview currency mismatch: " + field + " is "
                    + actual.currency().getCurrencyCode() + ", grossCarryingAmountDelta is "
                    + expected.getCurrencyCode());
        }
    }

    /** The one currency all three deltas are stated in. */
    public Currency currency() {
        return grossCarryingAmountDelta.currency();
    }

    /**
     * The weighted-average EIR shift, in basis points of effective annual rate.
     *
     * <p>Effective annual rather than nominal, and basis points rather than a fraction, because
     * this is the figure a reader compares against a repricing decision. {@link Rate} exposes
     * both annualisations under distinct names precisely so that neither is called "the annual
     * rate"; the comparison here is convention-free, so a draft that also changes compounding
     * frequency still nets out to a single comparable shift.
     */
    public BigDecimal weightedAverageEirShiftBps() {
        return weightedAverageEirAfter.effectiveAnnualBps()
            .subtract(weightedAverageEirBefore.effectiveAnnualBps(), Precision.WORKING);
    }

    /**
     * Whether this preview says the draft moves nothing at all.
     *
     * <p>A legitimate and useful answer — a clarifying rewording that maps no live fee code
     * previews to zero — and not a licence to skip the preview: a nil preview is a quantified
     * claim that somebody can be held to, which is the entire difference between it and an
     * absent one. Also the state that most deserves a second look on a
     * {@code BEHAVIOURAL_CURVE} version, where a genuinely nil movement is unusual.
     */
    public boolean isNoMovement() {
        return contractsAffected == 0
            && grossCarryingAmountDelta.isZero()
            && recognisedInterestDelta.isZero()
            && largestSingleContractMovement.isZero()
            && weightedAverageEirShiftBps().signum() == 0;
    }

    /**
     * Whether contracts moved while the portfolio total nets to <em>exactly</em> zero.
     *
     * <p>The extreme case of {@link #concealsOffsettingMovement()}, kept separate because it is
     * the one that reads most misleadingly: a portfolio delta of nil is the figure a reader is
     * most likely to stop at.
     */
    public boolean isFullyOffsetting() {
        return contractsAffected > 0
            && grossCarryingAmountDelta.isZero()
            && !largestSingleContractMovement.isZero();
    }

    /**
     * Whether the portfolio total is smaller in magnitude than the largest single contract's
     * movement, so netting is demonstrably concealing gross movement.
     *
     * <p>The reason {@link #largestSingleContractMovement()} is carried at all, and the general
     * form of the check {@link #isFullyOffsetting()} makes only at the boundary. A portfolio net
     * of 1.00 against a single contract moving 12,00,00,000.00 is the same concealment as a
     * portfolio net of nil, and reporting only the total makes both read as immaterial.
     *
     * <p><b>Threshold-free, and therefore not a refusal.</b> {@code |largest| > |total|} is
     * arithmetic, not judgement: if one contract moves more than the whole portfolio nets, other
     * contracts must be moving the other way. Whether the gross movement is <em>material</em> is
     * a Board threshold (03 § 10) and not this gate's number to invent, so this is flagged in
     * {@link #describe()} and left to the checker. What the gate guarantees is that the figure
     * exists and is honest; what it must not do is quietly decide the answer.
     */
    public boolean concealsOffsettingMovement() {
        return contractsAffected > 0
            && largestSingleContractMovement.abs().compareTo(grossCarryingAmountDelta.abs()) > 0;
    }

    /**
     * How stale this preview is at {@code at}, taking the <em>older</em> of its generation
     * instant and the book position it measured.
     *
     * <p>{@link #ageAt} is not sufficient for the horizon and the difference is exploitable
     * without anybody intending to. A maker can re-run a preview today against a portfolio
     * extract pulled a year ago; the generation instant is then minutes old while every figure
     * in it describes a book that no longer exists. 02 § 3.2 requires the preview to be run
     * "against the live portfolio", and {@link #portfolioAsOf()} is the only field that records
     * which book it actually saw — so the horizon is applied to whichever anchor is older.
     *
     * <p>The book date is widened to the last instant it could still be current anywhere
     * (see {@code AnywhereOnEarth}), which is the direction that avoids false refusals.
     */
    public Duration stalenessAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return Duration.between(stalenessAnchor(), at);
    }

    /** The older of the generation instant and the end of the book date. */
    Instant stalenessAnchor() {
        Instant bookAnchor = AnywhereOnEarth.firstInstantAfter(portfolioAsOf);
        return bookAnchor.isBefore(generatedAt) ? bookAnchor : generatedAt;
    }

    /**
     * Largest single-contract movement as a fraction of the portfolio movement.
     *
     * <p>Empty where the portfolio movement is zero, because the ratio is then undefined — and
     * that is the case where the total conceals the most, so {@link #isFullyOffsetting()} exists
     * to be asked alongside rather than a sentinel being returned here. A ratio at or near 1
     * means the portfolio figure is one contract.
     */
    public Optional<BigDecimal> concentration() {
        if (grossCarryingAmountDelta.isZero()) {
            return Optional.empty();
        }
        return Optional.of(largestSingleContractMovement.amount().abs()
            .divide(grossCarryingAmountDelta.amount().abs(), Precision.WORKING));
    }

    /** How old this preview is at {@code at}; negative durations are possible on clock skew. */
    public Duration ageAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return Duration.between(generatedAt, at);
    }

    /** Whether this previews {@code versionId}. */
    public boolean previews(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        return policyVersionId.equals(versionId);
    }

    /** Whether this was computed against exactly the draft content {@code fingerprint} names. */
    public boolean coversDraft(DraftFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return draftFingerprint.equals(fingerprint);
    }

    /**
     * Ways in which this preview contradicts itself, empty when it is coherent.
     *
     * <p>Five checks, each naming a defect that produces a preview which passes a "is there a
     * preview?" control while stating something arithmetically impossible. The first four have
     * the same root cause in practice — a portfolio aggregation that counted one population and
     * summed another — and that defect is invisible in the individual figures.
     *
     * <p>Returned as text rather than as a thrown exception because this is a fact about
     * supplied data, and the gate's job is to refuse it by name.
     */
    public List<String> incoherences() {
        List<String> found = new ArrayList<>();

        // (1) Nothing moved, yet something moved. A zero affected-contract count with a non-zero
        // delta means the count and the sum were taken over different populations — typically an
        // "affected" filter applied to one leg only.
        if (contractsAffected == 0) {
            boolean anyMovement = !grossCarryingAmountDelta.isZero()
                || !recognisedInterestDelta.isZero()
                || !largestSingleContractMovement.isZero()
                || weightedAverageEirShiftBps().signum() != 0;
            if (anyMovement) {
                found.add("no contracts affected, yet the preview reports movement:"
                    + " gross carrying amount " + grossCarryingAmountDelta
                    + ", recognised interest " + recognisedInterestDelta
                    + ", largest single contract " + largestSingleContractMovement
                    + ", EIR shift " + weightedAverageEirShiftBps().toPlainString() + " bps");
            }
        }

        // (2) The portfolio's gross carrying amount moved but no single contract did. If the sum
        // over contracts is non-zero then at least one term of that sum is non-zero, so a zero
        // largest movement means the largest was never computed — it defaulted.
        if (!grossCarryingAmountDelta.isZero() && largestSingleContractMovement.isZero()) {
            found.add("portfolio gross carrying amount moves by " + grossCarryingAmountDelta
                + " while the largest single-contract movement is nil; the sum of a set of zeros"
                + " is zero, so the largest was not computed");
        }

        // (3) The mirror of (1): contracts counted as affected while nothing moved. Same
        // aggregation defect with the filter dropped from the summing leg instead of the
        // counting leg, and it is the more dangerous half — a nil movement on a thousand
        // contracts reads as a change with no consequences, which is precisely the conclusion a
        // checker would act on. "Affected" means moved: a contract that does not move is not
        // affected, and a reclassification with no numeric effect previews as nil contracts and
        // nil movement (see isNoMovement).
        if (contractsAffected > 0
            && grossCarryingAmountDelta.isZero()
            && recognisedInterestDelta.isZero()
            && largestSingleContractMovement.isZero()
            && weightedAverageEirShiftBps().signum() == 0) {
            found.add(contractsAffected + " contracts affected, yet no figure in the preview"
                + " moves; a contract that does not move is not affected, so the count and the"
                + " sums were taken over different populations");
        }

        // (4) With exactly one contract affected the two figures are the same figure. The
        // sharpest available check on the aggregation, and it catches the common case of the
        // largest movement being taken over the whole book rather than the affected subset.
        if (contractsAffected == 1
            && !largestSingleContractMovement.equals(grossCarryingAmountDelta)) {
            found.add("one contract affected, so the largest single-contract movement must equal"
                + " the portfolio movement, but they are " + largestSingleContractMovement
                + " and " + grossCarryingAmountDelta);
        }

        // (5) The book position postdates the computation. A preview cannot have measured a book
        // that did not exist when it ran; what it measured was a projection, and FR-210 asks for
        // the portfolio, not a forecast of it. Compared against the first instant the book date
        // has begun anywhere on earth, so a same-day pull in any time zone is never flagged —
        // the check fires only where no clock can reconcile the two fields.
        if (generatedAt.isBefore(AnywhereOnEarth.earliestInstantOf(portfolioAsOf))) {
            found.add("generated " + generatedAt + " but claims to have measured the book at "
                + portfolioAsOf + ", which had not begun in any time zone; a preview cannot"
                + " measure a book position that did not yet exist");
        }

        return List.copyOf(found);
    }

    /** Whether {@link #incoherences()} is empty. */
    public boolean isCoherent() {
        return incoherences().isEmpty();
    }

    /**
     * One audit sentence. The line that goes next to the approval in the policy file, so it
     * states every figure a reader would otherwise have to go and fetch.
     */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("impact preview of ").append(policyVersionId)
            .append(" (").append(draftFingerprint).append(')')
            .append(" generated ").append(generatedAt)
            .append(" against the book at ").append(portfolioAsOf)
            .append(": ").append(contractsAffected).append(" contracts affected")
            .append(", gross carrying amount ").append(grossCarryingAmountDelta.atPresentationScale())
            .append(", recognised interest ").append(recognisedInterestDelta.atPresentationScale())
            .append(", largest single contract ")
            .append(largestSingleContractMovement.atPresentationScale())
            .append(", weighted-average EIR ")
            .append(weightedAverageEirBefore.effectiveAnnualBps().stripTrailingZeros().toPlainString())
            .append(" to ")
            .append(weightedAverageEirAfter.effectiveAnnualBps().stripTrailingZeros().toPlainString())
            .append(" bps (")
            .append(signed(weightedAverageEirShiftBps()))
            .append(')');
        if (isNoMovement()) {
            out.append(" — NO MOVEMENT");
        }
        if (isFullyOffsetting()) {
            out.append(" — FULLY OFFSETTING, the portfolio total conceals the movement");
        } else if (concealsOffsettingMovement()) {
            // Stated in the sentence rather than left to a reader to divide two figures. The
            // portfolio total is the number that gets quoted, and on this preview it is smaller
            // than a single contract's movement, so quoting it alone understates the gross.
            out.append(" — NETTING CONCEALS MOVEMENT, one contract moves ")
                .append(largestSingleContractMovement.abs().atPresentationScale())
                .append(" against a portfolio net of ")
                .append(grossCarryingAmountDelta.abs().atPresentationScale());
        }
        List<String> incoherences = incoherences();
        if (!incoherences.isEmpty()) {
            out.append(" — INCOHERENT: ").append(String.join("; ", incoherences));
        }
        return out.toString();
    }

    private static String signed(BigDecimal value) {
        BigDecimal trimmed = value.stripTrailingZeros();
        return (trimmed.signum() >= 0 ? "+" : "") + trimmed.toPlainString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
