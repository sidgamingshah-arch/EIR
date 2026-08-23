package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The annuity instalment, and the distinction between the instalment that is
 * <em>calculated</em> and the instalment that is <em>billed</em>.
 *
 * <pre>
 * A = P * i / (1 - (1+i)^-n)
 * </pre>
 *
 * <p>On the Case 1 loan that is 47,073.472223 and the billed instalment is
 * 47,073.47. The difference is not an error to be hidden: a lender bills in
 * paise, so the schedule the borrower pays is the rounded one, the EIR must be
 * solved against those billed flows, and the residue the rounding leaves on the
 * contractual leg is resolved by an explicit {@link ResiduePolicy} rather than by
 * a tolerance.
 *
 * <p>Kept as a separate type from the projectors because five shapes need it —
 * annuity, moratorium, balloon, step and tranched all reduce to an annuity over
 * some balance for some number of periods — and one formula in one place cannot
 * drift between them.
 */
public final class Annuity {

    private Annuity() {
    }

    /**
     * The instalment at working precision.
     *
     * <p>A zero rate is not a degenerate case to reject: interest-free instalment
     * credit exists, and the annuity there is simply the principal spread evenly.
     *
     * @param principal the balance being amortised
     * @param periodicRate the rate for one instalment period
     * @param periods number of instalments
     */
    public static Money instalment(Money principal, BigDecimal periodicRate, int periods) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(periodicRate, "periodicRate");
        if (periods < 1) {
            throw new IllegalArgumentException("periods must be >= 1, got " + periods);
        }
        if (periodicRate.signum() == 0) {
            return principal.dividedBy(BigDecimal.valueOf(periods));
        }
        BigDecimal discount = Precision.discountFactor(periodicRate, BigDecimal.valueOf(periods));
        BigDecimal denominator = BigDecimal.ONE.subtract(discount, Precision.WORKING);
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException(
                "annuity denominator vanished at rate " + periodicRate.toPlainString()
                    + " over " + periods + " periods");
        }
        return principal.times(periodicRate).dividedBy(denominator);
    }

    /** The instalment as billed — presentation scale, which is what the borrower pays. */
    public static Money billedInstalment(Money principal, BigDecimal periodicRate, int periods) {
        return instalment(principal, periodicRate, periods).atPresentationScale();
    }
}
