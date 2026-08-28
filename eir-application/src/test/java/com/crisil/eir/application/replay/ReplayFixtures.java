package com.crisil.eir.application.replay;

import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionRecord;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.replay.ClosedPeriod;
import com.crisil.eir.policy.replay.ReplayRun;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory ports and hand-built figures for the replay tests.
 *
 * <h2>Where the amounts come from</h2>
 *
 * <p>Reference case 1, {@code docs/reference-cases/case-01-emi-loan-with-fees.md} — a fixed-rate
 * 24-month EMI loan of 1,000,000.00 with 15,000.00 of processing fee received and 10,000.00 of DSA
 * commission paid, GCA₀ 995,000.00, EIR 1.04214918% per month. Two rows of its roll-forward are
 * used:
 *
 * <ul>
 *   <li>period 1 — opening 995,000.00, EIR interest 10,369.38, closing GCA 958,295.91. Hand check
 *       of the roll-forward: {@code 995000.00 + 10369.38 - 47073.47 = 958295.91}.</li>
 *   <li>period 12 — opening 569,545.28, EIR interest 5,935.51, closing GCA 528,407.32. Hand check:
 *       {@code 569545.28 + 5935.51 - 47073.47 = 528407.32}.</li>
 * </ul>
 *
 * <p>These are fixtures, not computed quantities. This package compares published figures; it does
 * not produce them. Using the real ones keeps the scales realistic, which is the entire subject of
 * a byte comparison.
 *
 * <h2>The published figure maps are written out by hand, key by key</h2>
 *
 * <p>Deliberately, and it is the most important decision in this file. Building the published side
 * with {@link ShadowRun#figures} would compare {@code ShadowRun}'s output against
 * {@code ShadowRun}'s output, so any defect in the key convention would cancel out on both sides
 * and every test would pass — the "derived value compared against the thing it was derived from"
 * trap this programme has now found several times. So {@link #publishedFigureKey} spells the
 * convention out in string literals, and a change to {@code ShadowRun}'s keys breaks these tests.
 */
final class ReplayFixtures {

    static final String BOOK = "IN-RETAIL";

    /** April 2027 — the first period under ACPIR 20 — closed 5 May 2027. */
    static final ClosedPeriod APRIL_2027 = ClosedPeriod.month(
        YearMonth.of(2027, 4), LocalDate.of(2027, 5, 5), "financial.controller");
    static final ClosedPeriod MAY_2027 = ClosedPeriod.month(
        YearMonth.of(2027, 5), LocalDate.of(2027, 6, 4), "financial.controller");
    static final ClosedPeriod JUNE_2027 = ClosedPeriod.month(
        YearMonth.of(2027, 6), LocalDate.of(2027, 7, 6), "financial.controller");

    /** The original run's {@code recorded_at} — the system-time half of a replay boundary. */
    static final Instant RECORDED_AT = Instant.parse("2027-05-05T18:30:00Z");

    /** A night in the following fiscal year, so the replay is nowhere near the close. */
    static final LocalDate NIGHT_OF = LocalDate.of(2027, 8, 10);

    static final String PUBLISHED_RUN_ID = "RUN-202704-CLOSE";

    static final String LOAN_ASSET = "1401-LOAN-ASSET";
    static final String INTEREST_INCOME = "4101-INTEREST-INCOME";

    /** Reference case 1, period 1. */
    static final String C1 = "LN-0000001";
    static final String C1_CLOSING_GCA = "958295.91";
    static final String C1_EIR_INTEREST = "10369.38";

    /** Reference case 1, period 12. */
    static final String C2 = "LN-0000012";
    static final String C2_CLOSING_GCA = "528407.32";
    static final String C2_EIR_INTEREST = "5935.51";

    private static final LocalDate APPROVED_ON = LocalDate.of(2027, 2, 15);

    private ReplayFixtures() {
    }

    // ------------------------------------------------------------------ policy

    static PolicyVersion version(
        String id, PolicyKind kind, LocalDate from, PolicyVersionStatus status) {
        return new PolicyVersion(id, kind, "replay fixture", from,
            "policy.author", "accounting.policy.owner", APPROVED_ON, status);
    }

    /**
     * The timeline as a replay in, say, October 2027 sees it: the April fee rule set has been
     * superseded by a July one, and the routing table has not changed.
     *
     * <p>The supersession is what makes the policy leg testable at all. With one version of each
     * kind, a replay that resolved at the period end and one that resolved at
     * {@code LocalDate.now()} give the same answer — which is precisely why a harness with that
     * defect passes its own tests until the first supersession.
     */
    static PolicyVersionRegistry supersededTimeline() {
        return PolicyVersionRegistry.of(List.of(
            version("FEE-2027.1", PolicyKind.FEE_RULE_SET,
                LocalDate.of(2027, 4, 1), PolicyVersionStatus.SUPERSEDED),
            version("FEE-2027.2", PolicyKind.FEE_RULE_SET,
                LocalDate.of(2027, 7, 1), PolicyVersionStatus.EFFECTIVE),
            version("ROUTE-2027.1", PolicyKind.ROUTING_TABLE,
                LocalDate.of(2027, 4, 1), PolicyVersionStatus.EFFECTIVE)));
    }

    /** What the April close cited, and what a correct replay of April must cite. */
    static Map<PolicyKind, String> aprilStamps() {
        Map<PolicyKind, String> stamps = new LinkedHashMap<>();
        stamps.put(PolicyKind.FEE_RULE_SET, "FEE-2027.1");
        stamps.put(PolicyKind.ROUTING_TABLE, "ROUTE-2027.1");
        return stamps;
    }

    /** What a replay that resolved policy at {@code LocalDate.now()} in August 2027 would cite. */
    static Map<PolicyKind, String> stampsResolvedAtTheReplayDate() {
        Map<PolicyKind, String> stamps = new LinkedHashMap<>();
        stamps.put(PolicyKind.FEE_RULE_SET, "FEE-2027.2");
        stamps.put(PolicyKind.ROUTING_TABLE, "ROUTE-2027.1");
        return stamps;
    }

    // ------------------------------------------------------------------ figures

    /**
     * The published figure key convention, written out rather than delegated.
     *
     * <p>{@code CONTRACT:id:column} for a row-level figure, which is the convention
     * {@code PeriodStatement}'s javadoc records. See the class comment for why this is not
     * {@link ShadowRun#closingGcaKey}.
     */
    static String publishedFigureKey(String contractId, String column) {
        return "CONTRACT:" + contractId + ":" + column;
    }

    /** The three figures one computed contract publishes: its closing balance and two postings. */
    static void putPublishedFigures(
        Map<String, Money> into, String contractId, String closingGca, String eirInterest) {
        into.put(publishedFigureKey(contractId, "closing_gca"), Money.inr(closingGca));
        into.put(publishedFigureKey(contractId, "journal:1:" + LOAN_ASSET + ":DR"),
            Money.inr(eirInterest));
        into.put(publishedFigureKey(contractId, "journal:2:" + INTEREST_INCOME + ":CR"),
            Money.inr(eirInterest));
    }

    /** What the April close published for the two-contract book. */
    static Map<String, Money> publishedFigures() {
        Map<String, Money> figures = new LinkedHashMap<>();
        putPublishedFigures(figures, C1, C1_CLOSING_GCA, C1_EIR_INTEREST);
        putPublishedFigures(figures, C2, C2_CLOSING_GCA, C2_EIR_INTEREST);
        return figures;
    }

    static PublishedRun publishedRun(
        ClosedPeriod period, Map<String, Money> figures, Map<PolicyKind, String> stamps) {
        return new PublishedRun(
            ReplayRun.published(
                "RUN-" + period.periodId() + "-CLOSE", period.periodId(), figures, stamps),
            period, RECORDED_AT, BOOK);
    }

    /** The ordinary April fixture: two contracts, figures as published, April's policy stamps. */
    static PublishedRun publishedApril() {
        return publishedRun(APRIL_2027, publishedFigures(), aprilStamps());
    }

    // ------------------------------------------------------------------ results

    /**
     * One computed contract: a closing balance and a balanced two-line accrual.
     *
     * <p>The amounts are passed as strings and turned into {@link Money} with no rescaling, so a
     * test can hand {@code "958295.910"} and see the scale drift survive into the comparison.
     */
    static ContractResult computed(
        String contractId, int periodId, String runId, String closingGca, String eirInterest) {
        JournalEntry journal = new JournalEntry(
            contractId, periodId, runId, BOOK, LocalDate.of(2027, 4, 30),
            List.of(
                JournalLine.debit(LOAN_ASSET, Money.inr(eirInterest), "EIR interest accrued"),
                JournalLine.credit(
                    INTEREST_INCOME, Money.inr(eirInterest), "interest income, EIR basis")));
        return ContractResult.computed(contractId, Money.inr(closingGca), journal, List.of());
    }

    /** One contract the barrier quarantined — figures absent, exception present (FR-905). */
    static ContractResult quarantined(String contractId, String runId) {
        return ContractResult.isolated(contractId, ExceptionRecord.raise(
            contractId, runId, ExceptionCategory.MISSING_MANDATORY_FIELD,
            "tenor arrived empty from the source extract", "payload/" + contractId));
    }

    /** The two contracts as the April close computed them. */
    static List<ContractResult> aprilResults(String runId) {
        return List.of(
            computed(C1, APRIL_2027.periodId(), runId, C1_CLOSING_GCA, C1_EIR_INTEREST),
            computed(C2, APRIL_2027.periodId(), runId, C2_CLOSING_GCA, C2_EIR_INTEREST));
    }

    // ------------------------------------------------------------------ ports

    /**
     * A contract source that records every boundary it is asked as at.
     *
     * <p>The recording is the test: 05 § 3.3 says a replay "reads the contract version set as at
     * the original run's {@code recorded_at}", and a port asked as at anything else — today, the
     * close date, an instant of its own choosing — makes the replay unreproducible while every
     * figure it returns stays internally consistent.
     */
    static final class RecordingContractSource implements ContractSource {
        private final List<String> ids;
        final List<AsAtBoundary> asked = new ArrayList<>();

        RecordingContractSource(List<String> ids) {
            this.ids = List.copyOf(ids);
        }

        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            asked.add(boundary);
            return ids;
        }
    }

    /**
     * A contract state source that records its boundaries and holds nothing.
     *
     * <p>Empty on purpose. This package does not read contract state — the batch job does — so the
     * fake exists to satisfy {@link RunRequest}'s mandatory-port guard and to record that it was
     * asked as at the replay boundary rather than at some other instant.
     */
    static final class RecordingContractStateSource implements ContractStateSource {
        final List<AsAtBoundary> asked = new ArrayList<>();

        @Override
        public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
            asked.add(boundary);
            return Optional.empty();
        }
    }

    /** A core banking feed with nothing in it; RC-1 is not this unit's control. */
    static final class RecordingCoreBankingFeed implements CoreBankingFeed {
        final List<AsAtBoundary> asked = new ArrayList<>();

        @Override
        public List<CbsBilledInterest> billedInterest(AsAtBoundary boundary) {
            asked.add(boundary);
            return List.of();
        }
    }

    /** A general ledger with nothing in it; SL-1 is not this unit's control. */
    static final class RecordingGeneralLedgerSource implements GeneralLedgerSource {
        final List<AsAtBoundary> asked = new ArrayList<>();

        @Override
        public List<GlControlAccountBalance> controlAccountBalances(AsAtBoundary boundary) {
            asked.add(boundary);
            return List.of();
        }
    }

    /** A policy source that hands back one registry and records the boundaries it was asked at. */
    static final class RecordingPolicySource implements PolicySource {
        private final PolicyVersionRegistry registry;
        final List<AsAtBoundary> asked = new ArrayList<>();

        RecordingPolicySource(PolicyVersionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
            asked.add(boundary);
            return registry;
        }
    }

    /** Every port a run needs, kept together so a test can assert on each after the run. */
    static final class Ports {
        final RecordingContractSource contracts;
        final RecordingContractStateSource contractState = new RecordingContractStateSource();
        final RecordingCoreBankingFeed coreBanking = new RecordingCoreBankingFeed();
        final RecordingGeneralLedgerSource generalLedger = new RecordingGeneralLedgerSource();
        final RecordingPolicySource policy;

        Ports(List<String> population, PolicyVersionRegistry registry) {
            this.contracts = new RecordingContractSource(population);
            this.policy = new RecordingPolicySource(registry);
        }

        /** Every boundary any port was asked as at, across all five. */
        List<AsAtBoundary> allBoundaries() {
            List<AsAtBoundary> all = new ArrayList<>();
            all.addAll(contracts.asked);
            all.addAll(contractState.asked);
            all.addAll(coreBanking.asked);
            all.addAll(generalLedger.asked);
            all.addAll(policy.asked);
            return all;
        }

        /**
         * A request for a <em>live</em> run of this period — the template a replay is derived from.
         *
         * <p>Its boundary is a live one, read as at an instant well after the close: a replay that
         * carried this boundary through would read the world as it is now, which is the defect
         * {@link ReplayVerification}'s constructor refuses.
         */
        RunRequest liveTemplate(int periodId) {
            return new RunRequest(
                "RUN-" + periodId + "-LIVE", periodId, BOOK,
                AsAtBoundary.live(
                    LocalDate.of(2027, 4, 30), Instant.parse("2027-08-10T20:00:00Z")),
                contracts, contractState, coreBanking, generalLedger, policy);
        }
    }

    /** The two-contract April book, against the superseded timeline. */
    static Ports aprilPorts() {
        return new Ports(List.of(C1, C2), supersededTimeline());
    }

    // ------------------------------------------------------------------ the job

    /**
     * A batch job that records the request it was given and returns pre-canned output.
     *
     * <p>It reads all five ports first, as a real job would, so the recording fakes can attest
     * that every one of them was asked as at the replay boundary. What it returns is supplied by
     * the test: this is a test of the replay's assembly and comparison, not of the arithmetic.
     */
    static final class FakeBatchJob implements AmortisationRun {
        private final RunOutput output;
        RunRequest received;
        int invocations;

        FakeBatchJob(RunOutput output) {
            this.output = output;
        }

        @Override
        public RunOutput execute(RunRequest request) {
            received = request;
            invocations++;
            for (String contractId : request.contracts().contractIdsInScope(request.boundary())) {
                request.contractState().openingState(contractId, request.boundary());
            }
            request.coreBanking().billedInterest(request.boundary());
            request.generalLedger().controlAccountBalances(request.boundary());
            return output;
        }
    }

    /** A job returning the two April contracts, computed, and the stamps it says it consulted. */
    static FakeBatchJob jobReturning(
        List<ContractResult> results, Map<PolicyKind, String> stamps) {
        return new FakeBatchJob(RunOutput.of(results, stamps));
    }
}
