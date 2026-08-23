package com.crisil.eir.calc.projection;

/**
 * The two approximations ACPIR 54 permits for a revolving facility, selected by
 * policy per product.
 *
 * <p>ACPIR 54 explicitly contemplates that the EIR cannot be determined directly
 * for a revolver, which is what makes these approximations legitimate rather than
 * expedient. Which one applies turns on whether utilisation is modellable, and
 * that is a product-level assessment with evidence behind it, not a per-contract
 * choice made at projection time.
 */
public enum RevolvingApproximation {

    /**
     * The integral fee defers over the renewal or sanction period.
     *
     * <p>For facilities whose drawn balance is volatile — cash credit, overdraft,
     * working-capital limits. The defensible split is between the component
     * compensating credit assessment and documentation at each renewal, which
     * defers over the renewal period, and the component compensating
     * <em>availability</em> of the undrawn limit, which is service income. That
     * split is a classification decision the rule set makes before projection, so
     * whatever arrives here as {@code INTEGRAL} is already the deferring
     * component.
     */
    FEE_OVER_RENEWAL,

    /**
     * An EIR over an expected drawdown and repayment profile, where utilisation is
     * stable and modellable.
     *
     * <p>Needs a utilisation profile as an input — for credit cards, the ACPIR
     * 46(2)(iii) behavioural analysis of historical default patterns, drawdown
     * behaviour and the effectiveness of limit reduction, suspension or
     * cancellation (FR-308). That profile is a schedule, so the honest route is to
     * supply it through {@link ExternalScheduleProjector} rather than to
     * reconstruct it from a term-loan formula.
     */
    EIR_OVER_UTILISATION
}
