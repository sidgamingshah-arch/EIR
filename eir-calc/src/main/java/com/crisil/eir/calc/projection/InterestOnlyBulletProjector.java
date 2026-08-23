package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodic interest serviced, principal repaid with the final interest
 * instalment.
 *
 * <p>The wholesale term-loan and bond shape, and Case 7's second stress
 * instrument: 59 monthly payments of 10,000 then 1,010,000 at month 60 against
 * 950,000 advanced solves to 1.11473209% per month. The 50,000 of net integral fee
 * is 5% of principal, which is where a Newton step seeded from the contractual
 * rate can overshoot — the projection is unremarkable, the solve is not.
 *
 * <p>Interest is {@code P * i} every period, computed from the periodic rate and
 * rounded once to presentation scale because that is what the borrower is billed.
 * Since the principal does not amortise there is no residue to resolve: every
 * instalment is identical and the final period repays the principal exactly.
 */
public final class InterestOnlyBulletProjector implements CashflowProjector {

    @Override
    public boolean supports(ContractTerms terms) {
        return terms.shape() == ScheduleShape.INTEREST_ONLY_BULLET && !terms.hasMoratorium();
    }

    @Override
    public ProjectionResult project(ContractTerms terms, List<FeePosting> fees) {
        int maturity = terms.termPeriods();
        Money coupon = periodicInterest(terms);
        List<CashFlow> future = new ArrayList<>(
            ProjectionSupport.instalmentLeg(terms, coupon, 1, maturity, FlowKind.INTEREST));
        future.add(CashFlow.of(
            terms.dueDate(maturity), maturity, terms.principal().atPresentationScale(), FlowKind.PRINCIPAL));
        return ProjectionSupport.assemble(terms, terms.principal(), fees, future, true);
    }

    /** {@code P * i} at presentation scale — 10,000.00 on the Case 7 instrument. */
    public Money periodicInterest(ContractTerms terms) {
        return terms.principal().times(terms.periodicRate()).atPresentationScale();
    }
}
