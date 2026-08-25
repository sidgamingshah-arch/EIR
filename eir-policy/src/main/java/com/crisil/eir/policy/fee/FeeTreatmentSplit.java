package com.crisil.eir.policy.fee;

import com.crisil.eir.calc.projection.FeePosting;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A syndication fee split into its two limbs under FR-205: the excess to
 * arrangement service income, the balance to the EIR.
 *
 * <p><strong>The record exists to hold one guarantee.</strong> The two limbs sum
 * to the whole <em>exactly</em>, and the compact constructor refuses any triple
 * that does not. Without that guard the split is a place where money can leak: the
 * proportion that determines it is {@code retainedShare / feeShare}, which for the
 * ordinary case of a 10% retention against a 35% fee share is 2/7 and does not
 * terminate in decimal. Rounding both limbs independently at
 * {@link com.crisil.eir.domain.Precision#WORKING} leaves a residue of the order of
 * 1e-22 of a rupee on each contract, and a residue that never reaches presentation
 * scale is exactly the kind that survives to the portfolio total and shows up as a
 * sub-ledger-to-GL break (invariant SL-1) that no single contract explains.
 *
 * <p>So {@link FeeTreatmentRules#bifurcateSyndicationFee} computes one limb and
 * <em>derives</em> the other by exact subtraction. That is a deliberate departure
 * from the usual "every intermediate at working precision" rule: the derived limb
 * may carry more than 28 significant digits. It is the right trade here because the
 * alternative breaks an equality that is checked, while the extra digits are far
 * below anything that is ever published — the limbs pass through
 * {@link Money#atPresentationScale()} before they reach a ledger like every other
 * figure.
 *
 * @param whole                        the arranger's fee as received, before the split
 * @param toEir                        the balance: integral to the EIR of the retained tranche
 * @param toArrangementServiceIncome   the excess: a distinct performance obligation
 * @param feeShare                     the arranger's share of the syndicate fee pool, in {@code [0,1]}
 * @param retainedShare                the arranger's retained share of the facility, in {@code [0,1]}
 * @param basis                        the cited reason, retained for the computation trace
 */
public record FeeTreatmentSplit(
    Money whole,
    Money toEir,
    Money toArrangementServiceIncome,
    BigDecimal feeShare,
    BigDecimal retainedShare,
    String basis) {

    /** Suffix marking the EIR limb of a bifurcated fee code in the trace. */
    public static final String EIR_LIMB_SUFFIX = ":EIR";

    /** Suffix marking the arrangement-service limb of a bifurcated fee code in the trace. */
    public static final String SERVICE_LIMB_SUFFIX = ":ARRANGEMENT_SERVICE";

    public FeeTreatmentSplit {
        Objects.requireNonNull(whole, "whole");
        Objects.requireNonNull(toEir, "toEir");
        Objects.requireNonNull(toArrangementServiceIncome, "toArrangementServiceIncome");
        Objects.requireNonNull(feeShare, "feeShare");
        Objects.requireNonNull(retainedShare, "retainedShare");
        Objects.requireNonNull(basis, "basis");
        if (basis.isBlank()) {
            throw new IllegalArgumentException("basis must state why the fee split this way");
        }
        requireProportion(feeShare, "feeShare");
        requireProportion(retainedShare, "retainedShare");
        if (!whole.currency().equals(toEir.currency())
            || !whole.currency().equals(toArrangementServiceIncome.currency())) {
            throw new IllegalArgumentException(
                "a split cannot cross currencies: whole " + whole.currency().getCurrencyCode()
                    + ", EIR limb " + toEir.currency().getCurrencyCode()
                    + ", service limb " + toArrangementServiceIncome.currency().getCurrencyCode());
        }
        if (whole.isNegative()) {
            throw new IllegalArgumentException(
                "FR-205 bifurcates a fee the arranger has received; a negative whole of " + whole
                    + " is a cost, and there is no arrangement service income limb of a cost");
        }
        // Neither limb may be negative. The check earns its place on the case where the
        // arranger's fee share is SMALLER than its retained share: computing the balance as
        // fee x retainedShare / feeShare would then exceed the whole fee and drive the excess
        // negative — an arranger reporting negative service income on a fee it was
        // under-rewarded for. The rule caps that case instead (see bifurcateSyndicationFee),
        // and this guard is what makes the cap non-optional.
        if (toEir.isNegative() || toArrangementServiceIncome.isNegative()) {
            throw new IllegalArgumentException(
                "neither limb of a split may be negative: EIR limb " + toEir
                    + ", arrangement service income limb " + toArrangementServiceIncome);
        }
        // Exact addition — no MathContext. The whole point of the record.
        BigDecimal reconstituted = toEir.amount().add(toArrangementServiceIncome.amount());
        if (reconstituted.compareTo(whole.amount()) != 0) {
            throw new IllegalArgumentException(
                "the two limbs must sum to the whole fee exactly: " + toEir + " + "
                    + toArrangementServiceIncome + " = " + reconstituted.toPlainString()
                    + ", not " + whole.amount().toPlainString()
                    + ". A split that does not reconstitute is a money leak that no single"
                    + " contract explains at the portfolio total (invariant SL-1)");
        }
    }

    /**
     * A share is a proportion of something, so it lives in {@code [0,1]}.
     *
     * <p>Package-private rather than private so {@link FeeTreatmentRules} can reject a
     * malformed share <em>before</em> dividing by it. Validating only here would mean
     * the division happened first and the message arrived second, and on a fee share
     * of zero the division is the failure.
     */
    static void requireProportion(BigDecimal share, String name) {
        if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                name + " must be a proportion in [0,1], got " + share.toPlainString());
        }
    }

    /**
     * Whether the arranger's fee share exceeds its retained share — the FR-205 test,
     * derived rather than stored so there is one definition of it.
     *
     * <p>The comparison is <b>strict</b>, and the boundary is pinned there
     * deliberately. At exact equality the fee is proportionate: the arranger earns
     * the same effective yield on its retained slice as every other participant does
     * on theirs, which is IFRS 9 B5.4.3(c)'s own description of the non-bifurcated
     * case, so the whole fee is integral and the service limb is zero.
     *
     * <p>No materiality band is applied. A band would be a policy figure nobody has
     * approved, and it would fail in the dangerous direction: at the boundary it
     * would keep a genuinely disproportionate fee inside the EIR, where it moves the
     * rate for the whole life of the exposure. A de minimis excess, by contrast,
     * produces a de minimis service-income limb and harms nothing.
     */
    public boolean disproportionate() {
        return feeShare.compareTo(retainedShare) > 0;
    }

    /**
     * The split expressed as the postings the projector consumes: the EIR limb
     * {@code INTEGRAL}, the excess {@code SEPARATE_SERVICE}.
     *
     * <p>A zero limb produces no posting. That is not tidiness — a zero-amount
     * posting in the inception vector is indistinguishable from a real fee of zero
     * and invites the reader to conclude the split happened when it did not. The full
     * trace, including the zero limb and the two shares that produced it, stays on
     * this record, which is where an auditor asking "why is this fee only partly in
     * the rate" should be looking.
     *
     * @param original the unsplit posting; its amount must be this split's whole
     */
    public List<FeePosting> postings(FeePosting original) {
        Objects.requireNonNull(original, "original");
        if (!original.amount().equals(whole)) {
            throw new IllegalArgumentException(
                "posting " + original.feeCode() + " carries " + original.amount()
                    + " but this split was computed on " + whole
                    + "; splitting one amount and posting another is how a reconciliation breaks");
        }
        List<FeePosting> limbs = new ArrayList<>(2);
        if (!toEir.isZero()) {
            limbs.add(FeePosting.received(
                original.feeCode() + EIR_LIMB_SUFFIX, toEir, original.postedOn(),
                FeeClassification.INTEGRAL));
        }
        if (!toArrangementServiceIncome.isZero()) {
            limbs.add(FeePosting.received(
                original.feeCode() + SERVICE_LIMB_SUFFIX, toArrangementServiceIncome,
                original.postedOn(), FeeClassification.SEPARATE_SERVICE));
        }
        return List.copyOf(limbs);
    }

    /** One line for the computation trace. */
    public String describe() {
        return "FR-205 syndication fee " + whole + ": fee share " + feeShare.toPlainString()
            + " against retained share " + retainedShare.toPlainString()
            + (disproportionate() ? " is disproportionate" : " is proportionate")
            + " — " + toEir + " to the EIR, " + toArrangementServiceIncome
            + " to arrangement service income. " + basis;
    }
}
