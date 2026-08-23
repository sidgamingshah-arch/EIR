package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import java.util.List;
import java.util.Objects;

/**
 * The EMI annuity — equal combined instalments of principal and interest.
 *
 * <pre>
 * EMI = P * i / (1 - (1+i)^-n)
 * </pre>
 *
 * <p>The dominant retail shape and the baseline for reference cases 1 to 5 and 8.
 *
 * <p><b>The billed instalment is the annuity rounded to presentation scale, and
 * the residue that creates is not an error.</b> On the Case 1 loan the annuity is
 * 47,073.472223 and the billed EMI is 47,073.47; over 24 periods that 0.002223
 * monthly shortfall compounds to 0.059969 of closing balance on the contractual
 * leg — the 0.06 that stands in the unamortised-fee column at period 24. Both
 * figures are exposed ({@link #annuityInstalment} and {@link #billedInstalment})
 * because they answer different questions, and the residue is resolved by an
 * explicit {@link ResiduePolicy}, never by a tolerance.
 *
 * <p>The EIR leg is untouched by any of this: the rate is solved against the
 * billed flows, so it amortises to exactly zero (invariant TR-1). A non-zero
 * terminal balance on the EIR leg would mean the solve and the roll-forward
 * disagreed about the flows.
 *
 * <p>The default policy is {@link ResiduePolicy#LMS_AUTHORITATIVE}: leave the
 * residue where the arithmetic puts it, because in production the schedule should
 * come from the lending system rather than from here (FR-102). That default also
 * reproduces Case 1 exactly, which is the point — a projector that quietly
 * plugged the residue would disagree with the golden fixture and with the core
 * banking system at the same time.
 */
public final class AnnuityProjector implements CashflowProjector {

    private final ResiduePolicy residuePolicy;
    private final int spreadPeriods;

    /** Derives the schedule and leaves the rounding residue visible. */
    public AnnuityProjector() {
        this(ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    /** Derives the schedule and resolves the residue under the given policy. */
    public AnnuityProjector(ResiduePolicy residuePolicy) {
        this(residuePolicy, 0);
    }

    /**
     * @param residuePolicy how the contractual-leg residue is resolved
     * @param spreadPeriods trailing instalments sharing the residue under
     *     {@link ResiduePolicy#SPREAD_LAST_N}; ignored otherwise
     */
    public AnnuityProjector(ResiduePolicy residuePolicy, int spreadPeriods) {
        this.residuePolicy = Objects.requireNonNull(residuePolicy, "residuePolicy");
        this.spreadPeriods = spreadPeriods;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A moratorium is explicitly not supported here even though it ends in an
     * annuity: interest capitalising through the moratorium changes the balance
     * the annuity is struck on, and it is
     * {@link MoratoriumProjector}'s to get right. Declining the terms rather than
     * relying on registry ordering means adding a projector cannot change what an
     * existing one answers.
     */
    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.ANNUITY_EMI && !terms.hasMoratorium();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        List<CashFlow> billed = billedLeg(terms);
        Money residue = residue(terms, billed);
        List<CashFlow> resolved = residuePolicy.apply(billed, residue, terms.periodicRate(), spreadPeriods);
        return ProjectionSupport.assemble(terms, terms.principal(), fees, resolved, true);
    }

    /** The annuity at working precision — 47,073.472223 on the Case 1 loan. */
    public Money annuityInstalment(ContractTerms terms) {
        return Annuity.instalment(terms.principal(), terms.periodicRate(), terms.termPeriods());
    }

    /** The instalment as billed — 47,073.47 on the Case 1 loan. */
    public Money billedInstalment(ContractTerms terms) {
        return annuityInstalment(terms).atPresentationScale();
    }

    /**
     * The residue the billed instalment leaves on the contractual leg before any
     * policy is applied — 0.059969 on the Case 1 loan.
     *
     * <p>Positive where the instalment rounded down and the lender under-collects.
     */
    public Money contractualResidue(ContractTerms terms) {
        return residue(terms, billedLeg(terms));
    }

    public ResiduePolicy residuePolicy() {
        return residuePolicy;
    }

    private List<CashFlow> billedLeg(ContractTerms terms) {
        return ProjectionSupport.instalmentLeg(
            terms, billedInstalment(terms), 1, terms.termPeriods(), FlowKind.COMBINED_EMI);
    }

    private Money residue(ContractTerms terms, List<CashFlow> leg) {
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), leg);
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), provisional, terms.termPeriods());
    }
}
