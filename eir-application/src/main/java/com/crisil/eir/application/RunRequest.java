package com.crisil.eir.application;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import java.util.Objects;

/**
 * One amortisation run: what it is for, and everything it may read (05 § 3.2).
 *
 * <p><b>The ports are on the request, not injected into a service.</b> A run is a value here — a
 * request carrying its own boundary and its own sources — and that is what makes a replay the same
 * code path rather than a parallel one. 05 § 3.3 says a replay "is the same batch job with
 * {@code is_replay = true} and an as-at boundary"; if the sources were fields on a long-lived
 * object, the replay would need a second object configured differently, and the two paths would
 * drift in exactly the way DT-1 exists to detect.
 *
 * <p><b>Every source is mandatory, including the two the run only needs at the end.</b> The core
 * banking feed and the general ledger are read once, for RC-1 and SL-1, after every contract has
 * been processed. Making them optional would let a run be assembled that cannot close — the
 * expensive work all done, and then no way to answer two of the reconciliations the close gate
 * requires. Refusing at assembly is cheaper than discovering it at the gate.
 *
 * @param runId    the run's identity; what a journal and a period balance are stamped with
 * @param periodId the accounting period, {@code YYYYMM} per 04 § 2.13
 * @param bookId   the book being run
 * @param boundary the as-at boundary; carries whether this is a replay
 */
public record RunRequest(
    String runId,
    int periodId,
    String bookId,
    AsAtBoundary boundary,
    ContractSource contracts,
    ContractStateSource contractState,
    CoreBankingFeed coreBanking,
    GeneralLedgerSource generalLedger,
    PolicySource policy) {

    public RunRequest {
        runId = requireText(runId, "runId");
        bookId = requireText(bookId, "bookId");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(contractState, "contractState");
        Objects.requireNonNull(coreBanking, "coreBanking");
        Objects.requireNonNull(generalLedger, "generalLedger");
        Objects.requireNonNull(policy, "policy");
        if (periodId < 190001 || periodId > 999912 || periodId % 100 < 1 || periodId % 100 > 12) {
            // The same shape the schema's ck_pool_origination_month_shape checks. Validated here
            // because a period id is what every journal, balance and exception in the run is
            // stamped with, and a malformed one partitions the ledger into a partition nobody
            // will look in.
            throw new IllegalArgumentException(
                "period " + periodId + " is not a YYYYMM accounting period (04 § 2.13)");
        }
    }

    /** Whether this run is reproducing an earlier one. */
    public boolean isReplay() {
        return boundary.isReplay();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank on a run request");
        }
        return stripped;
    }
}
