package com.crisil.eir.application.port;

import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import java.util.List;

/**
 * The core banking system's billed interest for the period — the other side of RC-1 (FR-804, C-14).
 *
 * <p>A separate port from {@link ContractStateSource} even though both carry a billed figure, and
 * the separation is the control. RC-1 compares the engine's projected contractual leg against what
 * the CBS says it billed, and ADR-0004 makes the CBS the book of record for billing. If the run
 * took both numbers from one port, the comparison would be a field against itself — the "derived
 * value compared against the thing it was derived from" trap that this programme has now found
 * several times.
 */
public interface CoreBankingFeed {

    /** What the CBS billed, per contract, as at the boundary. */
    List<CbsBilledInterest> billedInterest(AsAtBoundary boundary);
}
