package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The {@code LMS_AUTHORITATIVE} path: consume the schedule the lending system
 * actually billed rather than deriving one (FR-102, ADR-0004).
 *
 * <p><b>Strongly preferred in production, whatever the shape.</b> A derived
 * schedule the core banking system did not bill guarantees a reconciliation break
 * every month, which turns the schedule reconciliation control into noise and
 * trains everyone to ignore it. The break is not caused by an error in either
 * system: a lender bills in paise and rounds where its own product configuration
 * says to, and no formula reproduces that for every product, every rounding rule
 * and every mid-life event. So the engine takes the billed flows as given.
 *
 * <p>That is also why this projector accepts any {@link ScheduleShape}: it is not
 * a shape strategy, it is the refusal to guess. It answers {@code supports} on
 * whether a schedule was supplied, so a registry that places it first uses the
 * billed schedule wherever one exists and falls through to a derived shape only
 * where none does.
 *
 * <p>The residue question does not arise. There is no derived instalment to round,
 * so there is nothing to plug: any terminal difference is what the lender billed,
 * and {@link #terminalContractualBalance} exposes it for the reconciliation rather
 * than adjusting it away.
 */
public final class ExternalScheduleProjector implements CashflowProjector {

    private final List<Instalment> billed;

    /**
     * @param billedSchedule the lines the lending system billed, in any order;
     *     split principal and interest lines sharing a date and ordinal are fine
     */
    public ExternalScheduleProjector(List<Instalment> billedSchedule) {
        Objects.requireNonNull(billedSchedule, "billedSchedule");
        if (billedSchedule.isEmpty()) {
            throw new IllegalArgumentException(
                "an external schedule with no lines is not a schedule; either supply the billed lines or"
                    + " let a shape projector derive them");
        }
        List<Instalment> sorted = new ArrayList<>(billedSchedule);
        sorted.sort(Comparator.comparing(Instalment::dueOn).thenComparingInt(Instalment::periodIndex));
        this.billed = List.copyOf(sorted);
    }

    @Override
    public boolean supports(ContractTerms terms) {
        for (Instalment line : billed) {
            if (!line.amount().currency().equals(terms.currency())
                || line.dueOn().isBefore(terms.disbursementDate())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        List<CashFlow> future = new ArrayList<>();
        for (Instalment line : billed) {
            if (line.dueOn().isBefore(terms.disbursementDate())) {
                throw new IllegalArgumentException(
                    "billed line dated " + line.dueOn() + " precedes initial recognition "
                        + terms.disbursementDate());
            }
            future.add(line.toCashFlow());
        }
        return ProjectionSupport.assemble(terms, terms.principal(), fees, future, true);
    }

    /** The billed lines, in date then period order. */
    public List<Instalment> billedSchedule() {
        return billed;
    }

    /** Always {@link ResiduePolicy#LMS_AUTHORITATIVE} — that is what this path means. */
    public ResiduePolicy residuePolicy() {
        return ResiduePolicy.LMS_AUTHORITATIVE;
    }

    /**
     * The contractual balance left after the last billed line.
     *
     * <p>Retained, not plugged. A non-zero figure here is the lender's own residue
     * and belongs in the reconciliation; adjusting it would recreate exactly the
     * break this path exists to avoid.
     */
    public Money terminalContractualBalance(ContractTerms terms) {
        List<CashFlow> future = new ArrayList<>();
        for (Instalment line : billed) {
            future.add(line.toCashFlow());
        }
        FlowVector provisional = FlowVector.of(terms.disbursementDate(), terms.currency(), future);
        int lastPeriod = billed.get(billed.size() - 1).periodIndex();
        return ContractualBalance.after(
            terms.principal(), terms.periodicRate(), provisional, lastPeriod);
    }
}
