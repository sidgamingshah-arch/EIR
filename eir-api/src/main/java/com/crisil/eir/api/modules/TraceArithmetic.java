package com.crisil.eir.api.modules;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The quantities FR-808's trace <em>derives</em> rather than reads, kept out of the rendering.
 *
 * <p><b>Why an audit endpoint derives anything at all.</b> A trace that only echoed the stored row
 * would answer "the engine says 5,506.79 because the engine stored 5,506.79", which is not evidence
 * — it is the same number twice. 06 § 2.3's target is that a queried figure resolves to its inputs
 * in under thirty seconds, self-service, and resolving means the reader can see the arithmetic close.
 * So this class re-derives two things from the published row's own stated inputs, independently of
 * the engine that produced it, and the trace publishes the residual.
 *
 * <p><b>The two derivations, and what makes each one fail.</b>
 *
 * <ul>
 *   <li><b>The accrual at the stored rate.</b> {@link #impliedEirInterest} applies the rate the
 *       computation says it carried out, over the exponent the row says it accrued, to the balance
 *       the row says it opened on. It disagrees with the published interest when the run accrued at
 *       a rate other than the one it published — {@code InvariantId.SG_1}'s javadoc names exactly
 *       that defect for pipelines of this shape, and a quiet re-solve on a roll-forward produces a
 *       plausible figure at a rate nobody stamped — or when the accrual exponent the row records is
 *       not the one the interest was computed over. Both are invisible to every figure-comparison
 *       test, because both sides of such a test come from the same run.
 *   <li><b>The unamortised fee, twice.</b> The fee not yet taken to income is the gap between the
 *       contractual carrying amount and the gross carrying amount — 529,815.61 − 528,407.32 =
 *       1,408.29 on reference case 1 at month 13, which is the figure 06 § 2.3's own worked example
 *       carries. {@link #unamortisedFeeCarriedForward} rolls it forward on the <em>schedule's</em>
 *       leg, from the flow vector's cash; {@link #unamortisedFeeFromCashBook} rolls the contractual
 *       balance forward on the <em>cash book's</em> leg and differences it against the published
 *       closing balance. Those are two independent inputs by construction —
 *       {@code ContractPeriod}'s javadoc is explicit that the vector says what the schedule expects
 *       and the cash fields say what the cash book actually applied, "taking both from one source
 *       would make the journal's cash block a field compared against itself". So a receipt applied
 *       short, or applied to the wrong leg, opens {@link #feeLegResidual} by exactly the amount
 *       misapplied, and the trace names the figure instead of a tolerance.
 * </ul>
 *
 * <p><b>Not published under an invariant id.</b> These are figures on an audit response, not
 * assertions in the close gate's evidence set. An id would have to be added to
 * {@code InvariantId} and asserted over every contract in every run for it to mean anything there,
 * and a control asserted in one place and named in another is worse than either alone.
 *
 * <p>All arithmetic is {@link BigDecimal} through {@link Money} at {@link Precision#WORKING}, per
 * ADR-0002. Comparisons are made at working scale rather than presentation scale on purpose: two
 * figures each correctly rounded to paise differ by up to a paisa for reasons that are not defects,
 * and a residual that reports rounding as a break is a residual an operator learns to ignore.
 */
final class TraceArithmetic {

    private TraceArithmetic() {
    }

    /**
     * The interest the stored rate implies over the row's own accrual exponent:
     * {@code openingGca * ((1 + r)^tau - 1)}.
     *
     * @param openingGca      the balance the row says it accrued on
     * @param storedRate      the rate the computation says it carried out
     * @param accrualExponent the row's own tau: 1 for a whole compounding period
     */
    static Money impliedEirInterest(Money openingGca, Rate storedRate, BigDecimal accrualExponent) {
        Objects.requireNonNull(openingGca, "openingGca");
        Objects.requireNonNull(storedRate, "storedRate");
        Objects.requireNonNull(accrualExponent, "accrualExponent");
        BigDecimal compound = Precision.onePlusPow(storedRate.periodic(), accrualExponent);
        return openingGca.times(compound.subtract(BigDecimal.ONE, Precision.WORKING));
    }

    /**
     * How far the published interest is from the interest the stored rate implies, absolute.
     *
     * <p>Absolute because a shortfall and an overstatement are the same finding to whoever has to
     * work it, and a signed residual summed across a population nets two real breaks to nil.
     */
    static Money accrualResidual(Money publishedEirInterest, Money impliedEirInterest) {
        Objects.requireNonNull(publishedEirInterest, "publishedEirInterest");
        Objects.requireNonNull(impliedEirInterest, "impliedEirInterest");
        return publishedEirInterest.minus(impliedEirInterest).abs();
    }

    /**
     * The fee taken to income this period: the EIR leg's interest less the contractual leg's.
     *
     * <p>This is the whole economic content of an effective interest rate in one subtraction. On
     * reference case 1 at month 13 it is 5,506.79 − 5,298.16 = 208.63.
     */
    static Money feeAmortised(Money eirInterest, Money contractualInterest) {
        Objects.requireNonNull(eirInterest, "eirInterest");
        Objects.requireNonNull(contractualInterest, "contractualInterest");
        return eirInterest.minus(contractualInterest);
    }

    /**
     * The fee still unamortised at the start of the period: contractual carrying amount less gross.
     *
     * <p>1,408.29 on reference case 1 at month 13, which is the figure 06 § 2.3's worked example
     * publishes. The sign follows {@link Money}: an up-front fee received reduces the gross carrying
     * amount below the contractual balance, so the gap is positive on an asset.
     */
    static Money unamortisedFeeBroughtForward(Money openingContractual, Money openingGca) {
        Objects.requireNonNull(openingContractual, "openingContractual");
        Objects.requireNonNull(openingGca, "openingGca");
        return openingContractual.minus(openingGca);
    }

    /**
     * The unamortised fee carried out, on the schedule's leg: what was brought forward, less what
     * the period amortised.
     */
    static Money unamortisedFeeCarriedForward(
        Money openingContractual, Money openingGca, Money eirInterest, Money contractualInterest) {
        return unamortisedFeeBroughtForward(openingContractual, openingGca)
            .minus(feeAmortised(eirInterest, contractualInterest));
    }

    /**
     * The unamortised fee carried out, on the cash book's leg: the contractual balance rolled
     * forward at the interest the CBS billed and the cash the cash book actually applied, less the
     * gross carrying amount the run published.
     *
     * <p>The cash figure here must be the cash book's total — {@code ContractPeriod.cashApplied()},
     * the sum of the two legs it recorded — and not the flow vector's amount. Substituting the
     * vector's cash is what turns {@link #feeLegResidual} from a control into a tautology, and it is
     * the single edit that would make this class useless without changing a published figure.
     */
    static Money unamortisedFeeFromCashBook(
        Money openingContractual, Money contractualInterest, Money cashApplied, Money closingGca) {
        Objects.requireNonNull(openingContractual, "openingContractual");
        Objects.requireNonNull(contractualInterest, "contractualInterest");
        Objects.requireNonNull(cashApplied, "cashApplied");
        Objects.requireNonNull(closingGca, "closingGca");
        return openingContractual.plus(contractualInterest).minus(cashApplied).minus(closingGca);
    }

    /**
     * The gap between the two unamortised-fee derivations, absolute.
     *
     * <p>Nil where the cash book applied what the schedule expected. Where it did not, this is the
     * amount misapplied: a 47,000.00 receipt against a 47,073.47 instalment opens it to exactly
     * 73.47.
     */
    static Money feeLegResidual(Money onScheduleLeg, Money onCashBookLeg) {
        Objects.requireNonNull(onScheduleLeg, "onScheduleLeg");
        Objects.requireNonNull(onCashBookLeg, "onCashBookLeg");
        return onScheduleLeg.minus(onCashBookLeg).abs();
    }
}
