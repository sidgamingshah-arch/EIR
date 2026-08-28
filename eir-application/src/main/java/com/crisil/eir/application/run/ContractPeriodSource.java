package com.crisil.eir.application.run;

import com.crisil.eir.application.port.AsAtBoundary;

/**
 * Where the inner loop reads one contract's period movements (05 § 3.2).
 *
 * <p><b>Declared here rather than beside the committed ports, and that is a statement about scope
 * rather than a preference.</b> {@link com.crisil.eir.application.port.ContractStateSource} answers
 * the opening-position question and is not to be edited; nothing committed answers the
 * period-movement question — the flow vector, the cash book's leg split, the suspense balance
 * brought forward, the event. So the seam lives with the use case that needs it, and the fact that
 * it is not yet part of the spine's vocabulary is on the record here instead of hidden in a
 * constructor argument.
 *
 * <p>Takes the boundary for the same reason every committed port does: a replay must read the
 * movements the original run saw, not today's (05 § 3.3, DT-1). A source that answered from current
 * state would make the replay internally consistent and irreproducible, which is the failure DT-1
 * exists to detect and the one it cannot diagnose.
 */
public interface ContractPeriodSource {

    /**
     * The contract's movements for the period the boundary describes.
     *
     * <p>Non-optional, unlike {@code ContractStateSource.openingState}. The asymmetry is
     * deliberate: a contract in the population with no opening state as at the boundary is a data
     * condition the run reports per contract, whereas a contract with no <em>movements</em> is
     * still a contract with an accrual — a period with no cash is a period, and 05 § 3.2's loop
     * has to produce a row for it. An implementation with nothing to say should return a period
     * whose cash is nil and whose vector carries a zero-amount flow on the period date, which is
     * how {@code AmortisationEngine} is documented to be told that a boundary exists.
     */
    ContractPeriod periodFor(String contractId, AsAtBoundary boundary);
}
