package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Revolving facilities: cash credit, overdraft, credit cards, KCC,
 * working-capital limits.
 *
 * <p><b>There is no contractual repayment schedule, so there is nothing to solve a
 * conventional EIR over.</b> ACPIR 54 accepts exactly that and permits an
 * approximation. This projector implements {@link RevolvingApproximation#FEE_OVER_RENEWAL}:
 * the integral fee defers over the renewal or sanction period and the facility is
 * treated as squared at renewal.
 *
 * <p>What it deliberately does not do is manufacture an annuity. A revolver bent
 * into an EMI shape to reuse the annuity code yields a number that looks like an
 * EIR, reconciles to nothing, and carries no disclosure that it was an
 * approximation. So the projected vector carries <em>no instalment leg at all</em>:
 * the drawn balance goes out at inception, the integral fee lands with it, and a
 * single synthetic {@link FlowKind#NOTIONAL_REDEMPTION} closes the vector at the
 * renewal date. {@link ProjectionResult#futureLegIsWhollySynthetic()} is therefore
 * true, which is the machine-readable statement that any rate struck over this
 * vector is an approximation and must be labelled as one.
 *
 * <p>The fee accretion the approximation actually prescribes is
 * {@link #feeOverRenewal(ContractTerms, List)} — straight-line over the renewal
 * period, which is a different thing from an EIR and is named differently on
 * purpose.
 *
 * <p>Interest on a revolver accretes on actual utilisation, and on cards only on
 * revolving balances, never on transactions settled inside the interest-free
 * period (FR-308). None of that is projectable from contract terms, which is the
 * whole reason the approximation exists.
 *
 * <p>{@code termPeriods} is read as the renewal or sanction period. That is the
 * horizon the fee defers over and the ACPIR 46(2)(ii) substantive-renewal test
 * attaches to; where a facility is rolled continuously the rollover-versus-new-
 * instrument question is a product-level policy attribute (FR-513), not something
 * this projector may infer.
 */
public final class RevolvingProjector implements CashflowProjector {

    private final RevolvingApproximation approximation;

    /** The fee-over-renewal approximation. */
    public RevolvingProjector() {
        this(RevolvingApproximation.FEE_OVER_RENEWAL);
    }

    /**
     * @throws IllegalArgumentException for
     *     {@link RevolvingApproximation#EIR_OVER_UTILISATION}, which needs a
     *     utilisation profile this projector is not given. Failing at construction
     *     rather than at projection means a misconfigured product cannot reach a
     *     close and quietly produce the other approximation's number.
     */
    public RevolvingProjector(RevolvingApproximation approximation) {
        Objects.requireNonNull(approximation, "approximation");
        if (approximation != RevolvingApproximation.FEE_OVER_RENEWAL) {
            throw new IllegalArgumentException(
                approximation + " needs an expected utilisation profile, which is a supplied schedule and"
                    + " not something a term-loan formula can reconstruct; project it through"
                    + " ExternalScheduleProjector (FR-102, FR-307)");
        }
        this.approximation = approximation;
    }

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.REVOLVING;
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        CashFlow squaring = CashFlow.of(
            renewalDate(terms), terms.termPeriods(),
            terms.principal().atPresentationScale(), FlowKind.NOTIONAL_REDEMPTION);
        return ProjectionSupport.assemble(terms, terms.principal(), fees, List.of(squaring), false);
    }

    /** Which ACPIR 54 approximation this projector applies. */
    public RevolvingApproximation approximation() {
        return approximation;
    }

    /** The renewal or sanction date the fee defers to. */
    public LocalDate renewalDate(ContractTerms terms) {
        return terms.dueDate(terms.termPeriods());
    }

    /**
     * The straight-line fee accretion the approximation prescribes.
     *
     * <p>Straight-line is correct <em>here</em> and wrong on a term loan, and that
     * is not an inconsistency: EIR amortisation front-loads on a declining balance,
     * and a revolver has no declining balance to amortise on.
     * The final period absorbs the rounding difference so that the periods sum to
     * the fee exactly — a movement schedule whose column does not add up is a
     * defect even when every row is individually correct.
     *
     * @param fees the resolved postings; only {@code INTEGRAL} ones defer
     */
    public RenewalFeeAccretion feeOverRenewal(ContractTerms terms, List<FeePosting> fees) {
        Money deferred = ProjectionSupport.netIntegralFee(fees, terms.currency()).atPresentationScale();
        int periods = terms.termPeriods();
        Money perPeriod = deferred.dividedBy(BigDecimal.valueOf(periods)).atPresentationScale();
        Money finalPeriod = deferred.minus(perPeriod.times(BigDecimal.valueOf(periods - 1L)))
            .atPresentationScale();
        return new RenewalFeeAccretion(approximation, deferred, periods, perPeriod, finalPeriod);
    }

    /**
     * The approximation, stated as figures.
     *
     * @param approximation which ACPIR 54 approximation produced this
     * @param deferredFee   net integral fee deferring over the renewal period
     * @param renewalPeriods the renewal or sanction period, in periods
     * @param perPeriod     recognised in each period but the last
     * @param finalPeriod   recognised in the last period, absorbing the rounding
     *                      difference
     */
    public record RenewalFeeAccretion(
        RevolvingApproximation approximation,
        Money deferredFee,
        int renewalPeriods,
        Money perPeriod,
        Money finalPeriod) {

        public RenewalFeeAccretion {
            Objects.requireNonNull(approximation, "approximation");
            Objects.requireNonNull(deferredFee, "deferredFee");
            Objects.requireNonNull(perPeriod, "perPeriod");
            Objects.requireNonNull(finalPeriod, "finalPeriod");
            if (renewalPeriods < 1) {
                throw new IllegalArgumentException("renewalPeriods must be >= 1, got " + renewalPeriods);
            }
        }
    }
}
