package com.crisil.eir.policy.reconciliation;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * The CBS's side of one C-14 line: the interest one contract was billed in one period, as the core
 * banking feed states it.
 *
 * <p><b>This is the book of record and the engine's figure is the projection</b> (ADR-0004). The
 * asymmetry matters when a break is being cleared: the CBS number is what the borrower was billed
 * and what the customer statement, the cash book and the overdue calculation all agree on, so
 * "either the engine mis-projected the schedule or the feed is wrong" (RC-1's javadoc) is not two
 * symmetric possibilities. The engine is the first place to look.
 *
 * <p><b>{@code feedReference} is not decoration.</b> The first of the close gates in 07 § 4.3 is
 * "every upstream feed received and version-recorded", and a reconciliation is only reproducible
 * against a named feed version: DT-1 replays a closed period and byte-compares it, which requires
 * the CBS side of C-14 to be identifiable rather than merely present. It is carried per line rather
 * than per run because a real close consumes several extracts — a nightly file per entity, a
 * correction file behind it — and a break traced to one extract should not implicate the others.
 *
 * @param contractId    the CBS account reference, joining on {@code contract.source_system_ref}
 * @param periodId      the accounting period, {@code YYYYMM}
 * @param billedInterest interest billed to the borrower in that period, at the feed's own precision
 * @param feedReference which extract this line came from; blank is refused, because an
 *                      unattributable figure cannot be re-fetched when the break is investigated
 */
public record CbsBilledInterest(
    String contractId, int periodId, Money billedInterest, String feedReference) {

    public CbsBilledInterest {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(billedInterest, "billedInterest");
        Objects.requireNonNull(feedReference, "feedReference");
        contractId = contractId.strip();
        if (contractId.isEmpty()) {
            throw new IllegalArgumentException("contractId must not be blank");
        }
        feedReference = feedReference.strip();
        if (feedReference.isEmpty()) {
            throw new IllegalArgumentException(
                "feedReference must not be blank: 07 section 4.3 gate 1 requires every upstream"
                    + " feed version-recorded, and a CBS figure nobody can re-fetch is not"
                    + " evidence");
        }
        ContractualLegInterest.requirePeriodId(periodId);
    }

    /** The figure as the feed presents it, at the currency's minor units. */
    public Money presentedBilledInterest() {
        return billedInterest.atPresentationScale();
    }
}
