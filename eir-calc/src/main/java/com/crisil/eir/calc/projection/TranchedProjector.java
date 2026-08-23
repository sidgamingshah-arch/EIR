package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A facility drawn in tranches: draws over a drawdown period with interest
 * capitalising, then an annuity over the balance that leaves.
 *
 * <p>Project finance is the live case. Draws run over 24 to 48 months against a
 * projected schedule that never matches actual, so the rate struck at financial
 * closure is stale by first drawdown. Policy is to strike on the projected
 * schedule at closure and re-estimate when <b>cumulative</b> deviation breaches a
 * tolerance (FR-512) — not on every drawdown, which churns the book for no
 * informational gain.
 *
 * <p><b>This is the shape that produces multiple roots.</b> Draws after
 * {@code t=0} are outflows inside the vector, so where a draw lands after
 * repayment has begun — a large interim drawdown — the present-value function
 * changes sign more than once and admits more than one mathematically valid IRR.
 * The projector does not hide that: it emits the draws as the negative flows they
 * are and leaves {@link com.crisil.eir.calc.Discounting#signChanges} to report it,
 * so the solver applies its plausible-band disambiguation and records every
 * candidate root. Smoothing the draws into a single notional advance would remove
 * the sign changes and the disclosure with them.
 *
 * <p>The instalment is solved so that the contractual leg closes at zero over
 * <em>all</em> draws, including any that land inside the repayment period:
 *
 * <pre>
 * A = SUM_j  d_j (1+i)^(n-j)  /  SUM_p (1+i)^(n-p)
 * </pre>
 *
 * <p>which reduces exactly to the ordinary annuity when the only draw is at
 * inception. Stating it this way rather than as "annuity on the drawn balance"
 * is what lets an interim draw be amortised instead of left stranded at
 * maturity.
 *
 * <p>Interest during construction capitalises over whole periods, which is exact
 * where draws land on period boundaries and an approximation where they do not.
 * A milestone-dated draw is a broken period: the convention check will decline
 * periodic indexing for the discounting, but the capitalisation here still steps
 * period by period, so a facility whose draws are genuinely off-cycle should
 * supply its billed schedule (FR-102) rather than have one derived.
 */
public final class TranchedProjector implements CashflowProjector {

    private final List<Tranche> tranches;
    private final int repaymentStartsAfterPeriods;
    private final ResiduePolicy residuePolicy;
    private final int spreadPeriods;

    /** Repayment begins the period after the last projected draw. */
    public TranchedProjector(List<Tranche> tranches) {
        this(tranches, 0, ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    /**
     * @param repaymentStartsAfterPeriods the last period before repayment begins —
     *     the moratorium or construction period. 0 takes it from the last projected
     *     draw, which is the ordinary project-finance case; setting it earlier is
     *     how a facility that still draws after repayment has started is modelled,
     *     and that is where the multiple sign changes come from
     */
    public TranchedProjector(List<Tranche> tranches, int repaymentStartsAfterPeriods) {
        this(tranches, repaymentStartsAfterPeriods, ResiduePolicy.LMS_AUTHORITATIVE, 0);
    }

    public TranchedProjector(
        List<Tranche> tranches,
        int repaymentStartsAfterPeriods,
        ResiduePolicy residuePolicy,
        int spreadPeriods) {

        Objects.requireNonNull(tranches, "tranches");
        if (repaymentStartsAfterPeriods < 0) {
            throw new IllegalArgumentException(
                "repaymentStartsAfterPeriods must be >= 0, got " + repaymentStartsAfterPeriods);
        }
        this.repaymentStartsAfterPeriods = repaymentStartsAfterPeriods;
        if (tranches.isEmpty()) {
            throw new IllegalArgumentException("a tranched facility needs at least one drawdown");
        }
        List<Tranche> sorted = new ArrayList<>(tranches);
        sorted.sort(Comparator.comparingInt(Tranche::periodIndex).thenComparing(Tranche::drawnOn));
        this.tranches = List.copyOf(sorted);
        this.residuePolicy = Objects.requireNonNull(residuePolicy, "residuePolicy");
        this.spreadPeriods = spreadPeriods;
    }

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.TRANCHED
            && tranches.get(0).amount().currency().equals(terms.currency());
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        validate(terms);
        List<CashFlow> future = new ArrayList<>();
        for (Tranche tranche : tranches) {
            if (tranche.periodIndex() > 0) {
                future.add(CashFlow.of(tranche.drawnOn(), tranche.periodIndex(),
                    tranche.amount().negate().atPresentationScale(), FlowKind.DISBURSEMENT));
            }
        }
        future.addAll(ProjectionSupport.instalmentLeg(
            terms, billedInstalment(terms), repaymentStartPeriod(terms), terms.termPeriods(),
            FlowKind.COMBINED_EMI));
        Money residue = residue(terms, future);
        List<CashFlow> resolved = residuePolicy.apply(future, residue, terms.periodicRate(), spreadPeriods);
        return ProjectionSupport.assemble(terms, firstDraw(), fees, resolved, true);
    }

    /** The projected draws, in period order. */
    public List<Tranche> tranches() {
        return tranches;
    }

    /** The period ordinal of the last projected draw. */
    public int drawdownPeriods() {
        return tranches.get(tranches.size() - 1).periodIndex();
    }

    /** The first period carrying an instalment. */
    public int repaymentStartPeriod(ContractTerms terms) {
        int lastDeferred = repaymentStartsAfterPeriods > 0 ? repaymentStartsAfterPeriods : drawdownPeriods();
        return lastDeferred + 1;
    }

    /** The draw at initial recognition, which is the amount advanced on the anchor date. */
    public Money firstDraw() {
        Money advanced = tranches.get(0).amount();
        for (int index = 1; index < tranches.size(); index++) {
            if (tranches.get(index).periodIndex() == 0) {
                advanced = advanced.plus(tranches.get(index).amount());
            }
        }
        return advanced;
    }

    /**
     * Interest during construction: the draws made before repayment begins,
     * compounded to the period before the first instalment.
     *
     * <p>IDC is capitalised rather than expensed, so it is an EIR input. Where every
     * draw precedes repayment this is exactly the balance the instalment amortises,
     * and {@link #instalment} coincides with the annuity on it. Where a draw lands
     * inside the repayment period the instalment is solved over all draws instead,
     * and this figure is the construction-period balance only.
     */
    public Money capitalisedBalance(ContractTerms terms) {
        int lastDeferred = repaymentStartPeriod(terms) - 1;
        Money balance = Money.zero(terms.currency());
        for (Tranche tranche : tranches) {
            if (tranche.periodIndex() > lastDeferred) {
                continue;
            }
            BigDecimal growth = Precision.onePlusPow(
                terms.periodicRate(), lastDeferred - tranche.periodIndex());
            balance = balance.plus(tranche.amount().times(growth));
        }
        return balance;
    }

    /**
     * The level instalment that closes the contractual leg at zero over every
     * draw, at working precision.
     */
    public Money instalment(ContractTerms terms) {
        int maturity = terms.termPeriods();
        int start = repaymentStartPeriod(terms);
        if (start > maturity) {
            throw new IllegalArgumentException(
                "repayment starts in period " + start + " of a " + maturity
                    + "-period facility, leaving nothing to amortise");
        }
        BigDecimal rate = terms.periodicRate();
        Money drawsAtMaturity = Money.zero(terms.currency());
        for (Tranche tranche : tranches) {
            drawsAtMaturity = drawsAtMaturity.plus(
                tranche.amount().times(Precision.onePlusPow(rate, maturity - tranche.periodIndex())));
        }
        BigDecimal weight = BigDecimal.ZERO;
        for (int period = start; period <= maturity; period++) {
            weight = weight.add(Precision.onePlusPow(rate, maturity - period), Precision.WORKING);
        }
        return drawsAtMaturity.dividedBy(weight);
    }

    /** The post-drawdown instalment as billed. */
    public Money billedInstalment(ContractTerms terms) {
        return instalment(terms).atPresentationScale();
    }

    public ResiduePolicy residuePolicy() {
        return residuePolicy;
    }

    private void validate(ContractTerms terms) {
        Tranche first = tranches.get(0);
        if (first.periodIndex() != 0 || !first.drawnOn().equals(terms.disbursementDate())) {
            throw new IllegalArgumentException(
                "the first tranche must be drawn at initial recognition — period 0 on "
                    + terms.disbursementDate() + " — but is period " + first.periodIndex()
                    + " on " + first.drawnOn() + "; the anchor date and initial recognition are one date");
        }
        Money total = Money.zero(terms.currency());
        for (Tranche tranche : tranches) {
            if (!tranche.amount().currency().equals(terms.currency())) {
                throw new IllegalArgumentException(
                    "tranche drawn on " + tranche.drawnOn() + " is "
                        + tranche.amount().currency().getCurrencyCode()
                        + " and the facility is " + terms.currency().getCurrencyCode());
            }
            if (tranche.drawnOn().isBefore(terms.disbursementDate())) {
                throw new IllegalArgumentException(
                    "tranche dated " + tranche.drawnOn() + " precedes initial recognition "
                        + terms.disbursementDate());
            }
            total = total.plus(tranche.amount());
        }
        if (!total.atPresentationScale().equals(terms.principal().atPresentationScale())) {
            throw new IllegalArgumentException(
                "projected draws total " + total.atPresentationScale() + " against a sanctioned principal of "
                    + terms.principal().atPresentationScale()
                    + "; a draw schedule that does not sum to the facility is a data defect, not a"
                    + " deviation to be absorbed (FR-512 monitors deviation against actual, not against"
                    + " the projection)");
        }
        if (drawdownPeriods() >= terms.termPeriods()) {
            throw new IllegalArgumentException(
                "the last draw is in period " + drawdownPeriods() + " of a " + terms.termPeriods()
                    + "-period facility; a draw at or after maturity is a data defect");
        }
        if (repaymentStartPeriod(terms) > terms.termPeriods()) {
            throw new IllegalArgumentException(
                "repayment starts in period " + repaymentStartPeriod(terms) + " of a "
                    + terms.termPeriods() + "-period facility, leaving nothing to amortise");
        }
    }

    private Money residue(ContractTerms terms, List<CashFlow> leg) {
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), leg);
        return ContractualBalance.after(firstDraw(), terms.periodicRate(), provisional, terms.termPeriods());
    }
}
