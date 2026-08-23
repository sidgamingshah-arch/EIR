package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A moratorium followed by an annuity, with or without interest capitalisation.
 *
 * <p>Two profiles from one contractual feature:
 *
 * <ul>
 *   <li><b>Capitalising</b> — education loans under ACPIR 9(6)(i), project finance
 *       IDC before commercial operation date. Nothing is paid during the
 *       moratorium; interest accrues, capitalises into the gross carrying amount,
 *       and the annuity is struck on {@code P * (1+i)^m} over the remaining
 *       periods.
 *   <li><b>Servicing</b> — interest is billed and paid through the moratorium and
 *       only principal is deferred, so the annuity is struck on the original
 *       principal.
 * </ul>
 *
 * <p><b>The EIR is computed over expected life inclusive of the moratorium.</b>
 * {@code termPeriods} therefore counts the moratorium periods, and an expected
 * life that would end inside the moratorium is rejected: it would exclude the
 * capitalisation from the very rate the capitalisation feeds, which understates
 * yield on precisely the products where the deferral is largest.
 *
 * <p>One contractual feature, two separate consequences, and the engine must not
 * let either imply the other. ACPIR 9(6)(i) confirms interest becomes due only
 * after the moratorium, so the exposure is not overdue in the interim: the
 * capitalisation is an EIR input and the non-overdue status is a staging input.
 * Nothing here touches staging.
 *
 * <p>A capitalising moratorium leaves the early periods with no flows at all,
 * which the periodic-index precondition detects and declines — a moratorium voids
 * periodic indexing, and the vector falls back to actual dating without anyone
 * having to remember to say so.
 */
public final class MoratoriumProjector implements CashflowProjector {

    private final ResiduePolicy residuePolicy;
    private final int spreadPeriods;

    public MoratoriumProjector() {
        this(ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    public MoratoriumProjector(ResiduePolicy residuePolicy) {
        this(residuePolicy, 0);
    }

    public MoratoriumProjector(ResiduePolicy residuePolicy, int spreadPeriods) {
        this.residuePolicy = Objects.requireNonNull(residuePolicy, "residuePolicy");
        this.spreadPeriods = spreadPeriods;
    }

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.hasMoratorium()
            && (terms.shape() == ScheduleShape.ANNUITY_EMI || terms.shape() == ScheduleShape.STRUCTURED);
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        requireLifeCoversMoratorium(terms);
        int moratorium = terms.moratoriumPeriods();
        List<CashFlow> future = new ArrayList<>();
        if (!terms.capitalisesInterestDuringMoratorium()) {
            Money serviced = servicedInterest(terms);
            if (serviced.isPositive()) {
                future.addAll(
                    ProjectionSupport.instalmentLeg(terms, serviced, 1, moratorium, FlowKind.INTEREST));
            }
        }
        future.addAll(ProjectionSupport.instalmentLeg(
            terms, billedInstalment(terms), moratorium + 1, terms.termPeriods(), FlowKind.COMBINED_EMI));
        Money residue = residue(terms, future);
        List<CashFlow> resolved = residuePolicy.apply(future, residue, terms.periodicRate(), spreadPeriods);
        return ProjectionSupport.assemble(terms, terms.principal(), fees, resolved, true);
    }

    /**
     * The balance the annuity is struck on: the principal grown by capitalised
     * interest, or the principal itself where interest is serviced.
     *
     * <p>Compound accretion over the moratorium, not a simple-interest pro-rata.
     * Pro-rating understates a long deferral, and an education-loan moratorium can
     * run five years.
     */
    public Money capitalisedBalance(ContractTerms terms) {
        if (!terms.capitalisesInterestDuringMoratorium()) {
            return terms.principal();
        }
        BigDecimal growth = Precision.onePlusPow(terms.periodicRate(), terms.moratoriumPeriods());
        return terms.principal().times(growth);
    }

    /** Interest billed in each moratorium period where interest is serviced. */
    public Money servicedInterest(ContractTerms terms) {
        if (terms.capitalisesInterestDuringMoratorium()) {
            return Money.zero(terms.currency());
        }
        return terms.principal().times(terms.periodicRate()).atPresentationScale();
    }

    /** The post-moratorium instalment at working precision. */
    public Money instalment(ContractTerms terms) {
        return Annuity.instalment(
            capitalisedBalance(terms), terms.periodicRate(), terms.repaymentPeriods());
    }

    /** The post-moratorium instalment as billed. */
    public Money billedInstalment(ContractTerms terms) {
        return instalment(terms).atPresentationScale();
    }

    public ResiduePolicy residuePolicy() {
        return residuePolicy;
    }

    private void requireLifeCoversMoratorium(ContractTerms terms) {
        if (terms.expectedLifePeriods() <= terms.moratoriumPeriods()) {
            throw new IllegalArgumentException(
                "expected life of " + terms.expectedLifePeriods() + " periods ends inside the "
                    + terms.moratoriumPeriods() + "-period moratorium; ACPIR 9(6)(i) requires the EIR to be"
                    + " computed over expected life inclusive of the moratorium");
        }
    }

    private Money residue(ContractTerms terms, List<CashFlow> leg) {
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), leg);
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), provisional, terms.termPeriods());
    }
}
