package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.onboarding.OnboardingSource;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.application.run.ContractPeriodSource;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * All seven ports over one database and one book.
 *
 * <p>The seam ADR-0011 describes: "replacing this with a JDBC-backed implementation changes nothing
 * above it", said by {@code eir-api}'s in-memory {@code Book} about itself. This class is the
 * replacement, and it exists so that a caller wires one object rather than seven and cannot
 * accidentally wire six of them to one book and the seventh to another — which would produce a
 * sub-ledger tying to a general ledger from a different set of accounts, and a break equal to the
 * whole of one book.
 *
 * <p><b>Not a connection pool and not a transaction manager.</b> It holds a {@link DataSource} and
 * hands it to seven adapters, each of which opens and closes a read-only connection per call. 05 § 2
 * puts transaction boundaries in {@code eir-application}, so a run that wants one consistent snapshot
 * across all seven ports takes it there — and the mechanism it would use is not a shared connection
 * but the {@link com.crisil.eir.application.port.AsAtBoundary} itself, which is exactly why the
 * boundary is an argument on every port. Two reads at one boundary see one version set whether or not
 * they share a connection, and that is a stronger guarantee than a transaction: it survives a restart.
 *
 * @param bookId which of FR-109's parallel books every read is scoped to
 */
public final class JdbcPorts {

    private final ContractSource contracts;
    private final ContractStateSource contractState;
    private final ContractPeriodSource periods;
    private final CoreBankingFeed coreBanking;
    private final GeneralLedgerSource generalLedger;
    private final PolicySource policies;
    private final OnboardingSource onboarding;
    private final String bookId;

    /** The seven ports over {@code dataSource}, scoped to the default book. */
    public JdbcPorts(DataSource dataSource) {
        this(dataSource, JdbcAdapter.DEFAULT_BOOK);
    }

    public JdbcPorts(DataSource dataSource, String bookId) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.bookId = Objects.requireNonNull(bookId, "bookId");
        this.contracts = new JdbcContractSource(dataSource, bookId);
        this.contractState = new JdbcContractStateSource(dataSource, bookId);
        this.periods = new JdbcContractPeriodSource(dataSource, bookId);
        this.coreBanking = new JdbcCoreBankingFeed(dataSource, bookId);
        this.generalLedger = new JdbcGeneralLedgerSource(dataSource, bookId);
        this.policies = new JdbcPolicySource(dataSource, bookId);
        this.onboarding = new JdbcOnboardingSource(dataSource, bookId);
    }

    public ContractSource contracts() {
        return contracts;
    }

    public ContractStateSource contractState() {
        return contractState;
    }

    public ContractPeriodSource periods() {
        return periods;
    }

    public CoreBankingFeed coreBanking() {
        return coreBanking;
    }

    public GeneralLedgerSource generalLedger() {
        return generalLedger;
    }

    public PolicySource policySource() {
        return policies;
    }

    public OnboardingSource onboarding() {
        return onboarding;
    }

    public String bookId() {
        return bookId;
    }
}
