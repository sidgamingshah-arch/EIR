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
 * Instalments sized against a terminal lump sum — a balloon repayment, or a lease
 * residual.
 *
 * <p>The instalment amortises the principal less the present value of the lump:
 *
 * <pre>
 * A = annuity( P - L * (1+i)^-n , i, n )
 * </pre>
 *
 * <p>which is the same statement as "the instalments and the lump together
 * discount to the principal at the contractual rate". Sizing the instalments on
 * the full principal and then adding a balloon on top is the common error, and it
 * over-collects by the balloon's present value.
 *
 * <p>The lump is emitted as its own flow at maturity, sharing the period ordinal
 * with the final instalment. A lease residual is emitted separately from a balloon
 * even though the arithmetic is identical, because the two are different assets to
 * a controller and the reconciliation legs need to tell them apart.
 *
 * <p>Balloon profiles are one of the shapes where Newton-Raphson is unreliable —
 * a large terminal flow flattens the present-value curve — and are part of why the
 * bisection fallback is mandatory rather than optional.
 */
public final class BalloonProjector implements CashflowProjector {

    private final ResiduePolicy residuePolicy;
    private final int spreadPeriods;

    public BalloonProjector() {
        this(ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    public BalloonProjector(ResiduePolicy residuePolicy) {
        this(residuePolicy, 0);
    }

    public BalloonProjector(ResiduePolicy residuePolicy, int spreadPeriods) {
        this.residuePolicy = Objects.requireNonNull(residuePolicy, "residuePolicy");
        this.spreadPeriods = spreadPeriods;
    }

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.BALLOON
            && !terms.hasMoratorium()
            && !terminalLumpSum(terms).isZero();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        int maturity = terms.termPeriods();
        List<CashFlow> future = new ArrayList<>(ProjectionSupport.instalmentLeg(
            terms, billedInstalment(terms), 1, maturity, FlowKind.COMBINED_EMI));
        if (terms.balloonOrZero().isPositive()) {
            future.add(CashFlow.of(terms.dueDate(maturity), maturity,
                terms.balloonOrZero().atPresentationScale(), FlowKind.BALLOON));
        }
        if (terms.residualOrZero().isPositive()) {
            future.add(CashFlow.of(terms.dueDate(maturity), maturity,
                terms.residualOrZero().atPresentationScale(), FlowKind.RESIDUAL_VALUE));
        }
        Money residue = residue(terms, future);
        List<CashFlow> resolved = residuePolicy.apply(future, residue, terms.periodicRate(), spreadPeriods);
        return ProjectionSupport.assemble(terms, terms.principal(), fees, resolved, true);
    }

    /** Balloon plus lease residual — the whole amount falling due at maturity. */
    public Money terminalLumpSum(ContractTerms terms) {
        return terms.balloonOrZero().plus(terms.residualOrZero());
    }

    /** The instalment at working precision. */
    public Money instalment(ContractTerms terms) {
        Money lump = terminalLumpSum(terms);
        BigDecimal discount = Precision.discountFactor(
            terms.periodicRate(), BigDecimal.valueOf(terms.termPeriods()));
        Money amortising = terms.principal().minus(lump.times(discount));
        if (!amortising.isPositive()) {
            throw new IllegalArgumentException(
                "the terminal lump sum " + lump + " discounts to at least the principal "
                    + terms.principal() + ", so no positive instalment exists; this is a bullet or a"
                    + " discount instrument, not a balloon");
        }
        return Annuity.instalment(amortising, terms.periodicRate(), terms.termPeriods());
    }

    /** The instalment as billed. */
    public Money billedInstalment(ContractTerms terms) {
        return instalment(terms).atPresentationScale();
    }

    public ResiduePolicy residuePolicy() {
        return residuePolicy;
    }

    private Money residue(ContractTerms terms, List<CashFlow> leg) {
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), leg);
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), provisional, terms.termPeriods());
    }
}
