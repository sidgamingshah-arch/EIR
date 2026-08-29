package com.crisil.eir.api.store;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.application.run.ContractPeriodSource;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bank, in memory: one implementation of all seven ports over a mutable book of contracts.
 *
 * <p><b>What this is standing in for, and where the seam is.</b> 05 § 2 puts persistence behind
 * these seven interfaces precisely so that the engine cannot tell a database from a map, and this
 * class is the cheapest possible proof of that: {@code eir-persistence} holds verified PostgreSQL
 * DDL for the same entities, and replacing this with a JDBC-backed implementation changes nothing
 * above it. Every method takes an {@link AsAtBoundary} because the interfaces do — see
 * {@link #openingState} for the one place this implementation actually honours it and the one place
 * it cannot.
 *
 * <p><b>What it deliberately does not pretend.</b> A real {@code ContractStateSource} is
 * bitemporal: it answers as at a {@code recorded_at}, so a replay reads the version set the close
 * read. A map holds one version of each row, so this book answers the same thing at every boundary
 * except for contracts onboarded after a recorded point, which it does track. That is enough to
 * make a replay reproduce and not enough to make it a fair test of bitemporality, and the API says
 * so on the replay response rather than letting the UI imply otherwise.
 */
public final class Book {

    /** The control account the gross carrying amount sits in, for SL-1. */
    public static final String GCA_ACCOUNT = "1301-LOANS-GCA";

    private final Map<String, Holding> holdings = new LinkedHashMap<>();
    private final PolicyVersionRegistry policies;

    /**
     * What the general ledger currently shows on the control account.
     *
     * <p>Null until asked, then the sum of opening positions — which is what a GL shows
     * <em>before</em> the period's journals are posted. {@link #postToGl} moves it, and that
     * sequence is the point: SL-1 compares the sub-ledger's closing detail against the GL's posted
     * balance, so a close attempted before posting is red, and correctly. The first version of this
     * class summed opening balances and let the sub-ledger carry closing ones, which produced a
     * 36,059.88 break on a book where nothing was wrong — a fixture bug wearing a control finding,
     * and the worst kind, because an operator would go looking for the 36,059.88.
     */
    private Money postedGlBalance;

    /** Contracts recognised during this process's life, in the order they arrived. */
    private final List<String> onboardedHere = new ArrayList<>();

    public Book(PolicyVersionRegistry policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    /** One contract: its opening position and the movements the period brought. */
    public record Holding(
        String contractId,
        String productId,
        String entityId,
        String description,
        ContractStateSource.OpeningState state,
        ContractPeriod period,
        boolean stateOnFile) {

        public Holding {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(period, "period");
        }

        /** A contract the master carries in full. */
        public static Holding onFile(String contractId, String productId, String entityId,
            String description, ContractStateSource.OpeningState state, ContractPeriod period) {
            return new Holding(contractId, productId, entityId, description, state, period, true);
        }

        /**
         * A contract with period movements and no opening balance recorded.
         *
         * <p>A flag rather than a null state, because the condition being modelled is a
         * <em>port</em> declining to answer, not a field being empty. {@code Book.contractState()}
         * returns {@code Optional.empty()} for this holding, which is what a real master does for a
         * row it does not carry, and the state carried here is never read — it exists only so the
         * period movements have somewhere to live.
         */
        public static Holding movementsOnly(String contractId, String productId, String entityId,
            String description, ContractStateSource.OpeningState placeholder,
            ContractPeriod period) {
            return new Holding(
                contractId, productId, entityId, description, placeholder, period, false);
        }
    }

    public void put(Holding holding) {
        holdings.put(holding.contractId(), holding);
    }

    /** Records a contract as recognised in this session, so the UI can show what it just did. */
    public void recordOnboarded(String contractId) {
        if (!onboardedHere.contains(contractId)) {
            onboardedHere.add(contractId);
        }
    }

    public List<String> onboardedHere() {
        return List.copyOf(onboardedHere);
    }

    public List<Holding> holdings() {
        return List.copyOf(holdings.values());
    }

    public Optional<Holding> holding(String contractId) {
        return Optional.ofNullable(holdings.get(contractId));
    }

    public boolean holds(String contractId) {
        return holdings.containsKey(contractId);
    }

    public PolicyVersionRegistry policies() {
        return policies;
    }

    // ---- the seven ports -------------------------------------------------------------------

    /** The population, in insertion order — which is the run order the engine is entitled to. */
    public ContractSource contracts() {
        return boundary -> List.copyOf(holdings.keySet());
    }

    /**
     * Opening state, or empty.
     *
     * <p>Empty rather than a throw is the port's own contract, and it is what FR-905 quarantines
     * per contract. This implementation returns empty for a contract id the book does not hold,
     * which is exactly the condition a population naming a contract the master does not carry
     * produces.
     */
    public ContractStateSource contractState() {
        return (contractId, boundary) -> holding(contractId)
            .filter(Holding::stateOnFile)
            .map(Holding::state);
    }

    /**
     * The period's movements, non-optional.
     *
     * <p>The asymmetry with {@code openingState} is the port's, not this class's: a source that
     * cannot answer at all is a defect in the source, so this throws for an unknown contract rather
     * than inventing a period of no movement — which would publish a contract that accrued nothing
     * and reconcile perfectly.
     */
    public ContractPeriodSource periods() {
        return (contractId, boundary) -> holding(contractId)
            .map(Holding::period)
            .orElseThrow(() -> new IllegalStateException(
                "no period movements on file for contract " + contractId
                    + "; a period of no movement is not the same as a period nobody recorded"));
    }

    /**
     * What core banking billed, per contract.
     *
     * <p>Scoped to the whole population rather than to whatever the engine computed, and that
     * choice is the reason RC-1 can catch a dropped contract at all: a feed scoped to the engine's
     * own output makes a shortfall invisible to every reconciliation.
     */
    public CoreBankingFeed coreBanking() {
        return boundary -> {
            List<CbsBilledInterest> billed = new ArrayList<>(holdings.size());
            for (Holding holding : holdings.values()) {
                billed.add(new CbsBilledInterest(
                    holding.contractId(),
                    periodIdOf(boundary),
                    holding.state().contractualInterestBilled(),
                    "CBS-EOD-" + periodIdOf(boundary)));
            }
            return List.copyOf(billed);
        };
    }

    /**
     * The general ledger's posted balance on the control account.
     *
     * <p><b>Summed from the book's own closing positions, and that is a real limitation stated
     * plainly.</b> SL-1 compares the sub-ledger's detail against the GL's posted balance, and its
     * whole value is that the two are independently sourced. Here the second side is derived from
     * the first, so SL-1 is <em>reached with a figure on each side</em> and cannot fail on this
     * book. A production GeneralLedgerSource reads the bank's trial balance, and then it can.
     *
     * <p>Said on the close response too, so an operator is never shown a green SL-1 that could not
     * have gone red.
     */
    public GeneralLedgerSource generalLedger() {
        return boundary -> List.of(GlControlAccountBalance.of(
            GCA_ACCOUNT, glBalance(),
            "TB-" + periodIdOf(boundary) + (posted ? "-POSTED" : "-PRE-POSTING")));
    }

    /** Whether this period's journals have been posted to the ledger. */
    private boolean posted;

    /** The ledger's balance: opening positions until the journals are posted. */
    public Money glBalance() {
        if (postedGlBalance == null) {
            Money total = Money.zero(Money.INR);
            for (Holding holding : holdings.values()) {
                if (holding.stateOnFile()) {
                    total = total.plus(holding.state().openingGca());
                }
            }
            postedGlBalance = total;
        }
        return postedGlBalance;
    }

    public boolean posted() {
        return posted;
    }

    /**
     * Posts a period's journals: the ledger now carries what the sub-ledger says it should.
     *
     * <p><b>The balance is supplied rather than computed here, and that is the honest limitation.</b>
     * A real posting engine writes {@code JournalBatch}'s lines to the GL and the GL reports its own
     * total back; here the caller hands over the sub-ledger's figure, so both sides of SL-1 still
     * come from one place and the tie cannot go red on a posted book. What the sequence does buy is
     * that it cannot go red on an <em>unposted</em> one either by accident — the two states are now
     * distinguishable, and an operator sees the tie move.
     */
    public void postToGl(Money subLedgerClosingTotal) {
        this.postedGlBalance = Objects.requireNonNull(subLedgerClosingTotal, "balance");
        this.posted = true;
    }

    public PolicySource policySource() {
        return boundary -> policies;
    }

    /** {@code YYYYMM} from the boundary's business date. */
    public static int periodIdOf(AsAtBoundary boundary) {
        return boundary.businessAsOf().getYear() * 100 + boundary.businessAsOf().getMonthValue();
    }
}
