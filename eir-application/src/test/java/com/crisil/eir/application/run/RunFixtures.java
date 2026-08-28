package com.crisil.eir.application.run;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolverMethod;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reference case 1 at month 13, in memory — the fixture every test in this package builds on.
 *
 * <p><b>Why month 13.</b> It is the one period whose figures three documents state independently,
 * so nothing here is derived from the code under test:
 *
 * <ul>
 *   <li>reference case 1's roll-forward table: opening 528,407.32, EIR interest 5,506.79, cash
 *       47,073.47, closing 486,840.64;</li>
 *   <li>reference case 5, which takes that same loan into Stage 3 at the start of month 13: a
 *       lifetime ECL allowance of 40% = 211,362.93, an ECL discount unwind of 2,202.72, an IFRS 9
 *       net-basis figure of 3,304.08, nil recognised, and 5,298.16 of contractual interest billed
 *       to interest-in-suspense;</li>
 *   <li>{@code eir-gl}'s own {@code JournalBatchTest}, which posts the same period as
 *       5,506.79 against 5,298.16 plus a 208.63 fee slice.</li>
 * </ul>
 *
 * <p>Two of those were cross-checked against a {@code python3} recomputation at 28 significant
 * digits, HALF_UP, from the stored 12dp rate of 0.010421491800:
 * {@code 528407.32 x 0.0104214918 = 5506.79255243997600} → 5,506.79, and
 * {@code 211362.93 x 0.0104214918 = 2202.71704181897400} → 2,202.72. The same script priced twelve
 * remaining instalments of 47,073.47 at that rate and got <b>528,407.32</b>, which is the month-13
 * opening balance recovered from the flows rather than from the table — so the rate, the balance and
 * the schedule agree with each other and with the published fixture.
 *
 * <p><b>The solver is a landmine by default.</b> {@link #EXPLODING_SOLVER} throws if it is ever
 * called. 05 § 3.2 puts the solve inside the event branch only, and the cheapest way to prove a
 * pipeline honoured that is to make the alternative impossible: a steady-state test that passes
 * with this solver wired in cannot have solved. The solve counts on {@link SolveAudit} are asserted
 * as well, because the landmine says nothing about a run that solves twice on one event.
 */
final class RunFixtures {

    static final String RUN_ID = "RUN-202805-01";
    static final int PERIOD_ID = 202805;
    static final String BOOK_ID = "MAIN";

    /** The month-13 accrual boundary: from the month-12 due date to the month-13 due date. */
    static final LocalDate PERIOD_START = LocalDate.of(2028, 4, 30);
    static final LocalDate PERIOD_END = LocalDate.of(2028, 5, 31);

    /**
     * The system-time boundary, supplied and not read from a clock.
     *
     * <p>A run that read {@code Instant.now()} at its own start could not be re-run to the same
     * boundary tomorrow, and "re-run the close" is an ordinary operational request (DT-1,
     * ADR-0003).
     */
    static final Instant KNOWN_AT = Instant.parse("2028-06-01T00:00:00Z");

    /** Reference case 1's EIR, per month, at the stored 12dp scale. */
    static final Rate EIR = Rate.periodic(new BigDecimal("0.010421491800"), 12);

    /** Month-13 opening gross carrying amount (reference case 1, period 13). */
    static final Money OPENING_GCA = Money.inr("528407.32");

    /** The billed EMI (reference case 1: the true annuity 47,073.472223 rounded to paise). */
    static final Money EMI = Money.inr("47073.47");

    /** Contractual interest billed in month 13 (reference case 5's suspense charge). */
    static final Money BILLED_INTEREST = Money.inr("5298.16");

    /** Reference case 5's lifetime ECL allowance: 40% of 528,407.32 = 211,362.928 → 211,362.93. */
    static final Money STAGE_3_ALLOWANCE = Money.inr("211362.93");

    /** Zero, spelled once. */
    static final Money NIL = Money.zero(Money.INR);

    /** Uniform monthly periods with every flow on a boundary — reference case 1's shape. */
    static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /**
     * Actual dating, which 03 § 3.10 makes the default and the fallback.
     *
     * <p>Under it the solved rate is annual effective and a monthly accrual has an exponent near
     * 1/12 but not equal to it — 31/365 here — so the figures diverge from reference case 1's
     * periodic-index ones. That divergence is the reason the accrual exponent has to be derived and
     * cannot be assumed to be one.
     */
    static final TimeConvention ACTUAL_365F =
        new TimeConvention.ActualDate(DayCountConvention.ACT_365F);

    /** Reference case 1's EIR expressed as 13.248094% p.a. effective, for the actual-date path. */
    static final Rate EIR_ANNUAL_EFFECTIVE =
        Rate.annualEffective(new BigDecimal("0.132480940000"));

    /** The engine's baseline reading of 03 § 6.1, effective 2026-05-01 and so in force here. */
    static final RoutingTableRegistry ROUTING =
        RoutingTableRegistry.of(RoutingTable.currentDefault());

    /** A solver that must never be reached. See the class javadoc. */
    static final RateSolver EXPLODING_SOLVER = request -> {
        throw new AssertionError(
            "the solver was called. 05 § 3.2 puts the solve inside the event branch only, and a"
                + " fixed-rate contract with no events never re-solves — the steady-state run is"
                + " roll-forward arithmetic, which is what makes the 10M-contract target reachable");
    };

    private RunFixtures() {
    }

    /** A solver that always returns {@code rate}, for exercising the B5.4.5 reset branch. */
    static RateSolver solverReturning(Rate rate) {
        return request -> SolveResult.solved(
            rate, SolverMethod.NEWTON, 3, 0, BigDecimal.ZERO, List.of(),
            "test fixture: fixed answer " + rate.periodic().toPlainString());
    }

    /** A solver that finds no root — 03 § 4.3's exception-queue path. */
    static RateSolver solverWithNoSolution() {
        return request -> SolveResult.noSolution(
            "test fixture: no sign change on the ladder");
    }

    /**
     * Reference case 1's terms.
     *
     * <p>{@code firstDueDate} is 2027-05-31, so {@code dueDate(12)} is 2028-04-30 and
     * {@code dueDate(13)} is 2028-05-31 — the period boundaries above. That correspondence is what
     * lets {@link ContractPipeline#scheduleAccrualExponent} derive the accrual length from the
     * schedule independently of the supplied vector's dates.
     */
    static ContractTerms case1Terms() {
        return ContractTerms.of(
            Money.inr("1000000.00"),
            Rate.periodic(new BigDecimal("0.010000000000"), 12),
            24,
            12,
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 31),
            DayCountConvention.THIRTY_360_BOND,
            ScheduleShape.ANNUITY_EMI,
            RateType.FIXED);
    }

    /** The same loan as a floating-rate instrument, so a market-movement driver can reach a reset. */
    static ContractTerms floatingTerms() {
        ContractTerms fixed = case1Terms();
        return new ContractTerms(
            fixed.principal(), fixed.contractualRate(), fixed.termPeriods(), fixed.periodsPerYear(),
            fixed.disbursementDate(), fixed.firstDueDate(), fixed.dayCount(), fixed.shape(),
            RateType.FLOATING, fixed.currency(), 0, false, null, null, null, 0, 0, null);
    }

    /**
     * The period's own flows: one boundary, one flow, carrying the 1-based ordinal 1 relative to the
     * period's anchor.
     *
     * <p>Relative to the anchor, not to the contract: {@code AmortisationEngine} differences tau
     * from zero at the anchor, so a flow labelled 13 in a one-period segment would declare a
     * thirteen-period accrual.
     */
    static FlowVector periodVector(Money cash) {
        return periodVector(cash, 1);
    }

    /** As {@link #periodVector(Money)}, with a deliberately wrong ordinal for the failure tests. */
    static FlowVector periodVector(Money cash, int periodIndex) {
        return FlowVector.of(PERIOD_START, Money.INR,
            List.of(CashFlow.of(PERIOD_END, periodIndex, cash, FlowKind.COMBINED_EMI)));
    }

    /** As {@link #periodVector(Money)}, with the flow on a chosen date — the actual-date tests. */
    static FlowVector periodVectorDated(LocalDate flowDate, Money cash) {
        return FlowVector.of(PERIOD_START, Money.INR,
            List.of(CashFlow.of(flowDate, 1, cash, FlowKind.COMBINED_EMI)));
    }

    /** The performing contract measured under actual dating at an annual effective rate. */
    static ContractStateSource.OpeningState actualDatedState() {
        return new ContractStateSource.OpeningState(
            case1Terms(), EIR_ANNUAL_EFFECTIVE, OPENING_GCA, Money.inr("529815.61"),
            Stage.STAGE_1, NIL, "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /** Twelve remaining instalments, anchored at the period start — case 1's own tail. */
    static FlowVector remainingInstalments(Money instalment) {
        List<CashFlow> flows = new java.util.ArrayList<>(12);
        for (int period = 1; period <= 12; period++) {
            flows.add(CashFlow.of(
                PERIOD_START.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(PERIOD_START, Money.INR, List.copyOf(flows));
    }

    static AsAtBoundary boundary() {
        return AsAtBoundary.live(PERIOD_END, KNOWN_AT);
    }

    /** A performing contract at month 13: Stage 1, no allowance, the EMI received. */
    static ContractStateSource.OpeningState performingState() {
        return new ContractStateSource.OpeningState(
            case1Terms(), EIR, OPENING_GCA, Money.inr("529815.61"), Stage.STAGE_1, NIL,
            "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /** Reference case 5: the same loan in Stage 3 with a 40% lifetime allowance. */
    static ContractStateSource.OpeningState stage3State() {
        return new ContractStateSource.OpeningState(
            case1Terms(), EIR, OPENING_GCA, Money.inr("529815.61"), Stage.STAGE_3,
            STAGE_3_ALLOWANCE, "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /** The performing state on a floating-rate instrument. */
    static ContractStateSource.OpeningState floatingState() {
        return new ContractStateSource.OpeningState(
            floatingTerms(), EIR, OPENING_GCA, Money.inr("529815.61"), Stage.STAGE_1, NIL,
            "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /** The steady-state period: the EMI received, applied 41,775.31 principal / 5,298.16 interest. */
    static ContractPeriod performingPeriod(String contractId) {
        // 47,073.47 − 5,298.16 = 41,775.31. Split by hand from the two published figures, so the
        // cash book's total ties to the vector's flow and the journal's cash block balances.
        return ContractPeriod.of(
            contractId, 13, periodVector(EMI), MONTHLY, Money.inr("41775.31"), BILLED_INTEREST);
    }

    /** A Stage 3 period in which the borrower paid nothing at all. */
    static ContractPeriod defaultedPeriod(String contractId) {
        // A zero-amount flow on the period date. It changes no present value and no balance; it
        // declares the accrual boundary, which is what AmortisationEngine's javadoc prescribes for
        // a caller that wants a row per accounting period.
        return ContractPeriod.of(contractId, 13, periodVector(NIL), MONTHLY, NIL, NIL);
    }

    static RunRequest request(
        List<String> population,
        Map<String, ContractStateSource.OpeningState> states) {
        return new RunRequest(
            RUN_ID, PERIOD_ID, BOOK_ID, boundary(),
            new FakeContracts(population), new FakeState(states), new FakeCoreBanking(),
            new FakeGeneralLedger(), new FakePolicy());
    }

    /**
     * The same request with a general ledger that reports one control-account balance.
     *
     * <p>{@code FakeGeneralLedger} and {@code FakeCoreBanking} both answer empty, which is what the
     * run path wants — nothing in {@code MonthEndRun} reads either. A close reads both, and reads
     * them as the independent sides of SL-1 and RC-1, so a seam test needs a request whose two
     * downstream ports have something to say. Only those two are substituted: the contract,
     * state and policy ports stay as the run had them, because a replacement there would be a
     * different population.
     *
     * <p>The CBS side is scoped to {@code cbsContracts} rather than to the population, because the
     * two scopes detect a dropped contract differently and the caller has to choose which it is
     * testing — see {@code RunCloseTest.aShortfallIsTheSilentOne}.
     */
    static RunRequest requestForClose(
        RunRequest request, String accountCode, Money balance, List<String> cbsContracts) {
        return new RunRequest(
            request.runId(), request.periodId(), request.bookId(), request.boundary(),
            request.contracts(), request.contractState(),
            boundary -> cbsContracts.stream()
                .map(id -> new com.crisil.eir.policy.reconciliation.CbsBilledInterest(
                    id, request.periodId(), BILLED_INTEREST,
                    "CBS-EOD-" + request.periodId()))
                .toList(),
            boundary -> List.of(com.crisil.eir.gl.posting.GlControlAccountBalance.of(
                accountCode, balance, "TB-" + request.periodId() + "-FINAL")),
            request.policy());
    }

    /** One contract, one state, one period — the shape most tests want. */
    static Harness harness(
        String contractId,
        ContractStateSource.OpeningState state,
        ContractPeriod period,
        RateSolver solver) {
        Map<String, ContractStateSource.OpeningState> states = new LinkedHashMap<>();
        states.put(contractId, state);
        Map<String, ContractPeriod> periods = new LinkedHashMap<>();
        periods.put(contractId, period);
        return harness(List.of(contractId), states, periods, solver);
    }

    static Harness harness(
        List<String> population,
        Map<String, ContractStateSource.OpeningState> states,
        Map<String, ContractPeriod> periods,
        RateSolver solver) {
        RunRequest request = request(population, states);
        SolveAudit audit = SolveAudit.over(solver);
        ContractPipeline pipeline =
            new ContractPipeline(request, new FakePeriods(periods), ROUTING, audit);
        return new Harness(request, audit, pipeline, new MonthEndRun(request, pipeline));
    }

    /** A wired-up run, so a test can assert on the audit as well as on the figures. */
    record Harness(
        RunRequest request, SolveAudit audit, ContractPipeline pipeline, MonthEndRun run) {
    }

    /** The population, in run order. */
    record FakeContracts(List<String> ids) implements ContractSource {
        @Override
        public List<String> contractIdsInScope(AsAtBoundary boundary) {
            return List.copyOf(ids);
        }
    }

    /**
     * Opening states by contract; a contract absent here has no state as at the boundary, which is
     * the data condition FR-905 quarantines rather than a reason to abandon the run.
     */
    record FakeState(Map<String, ContractStateSource.OpeningState> states)
        implements ContractStateSource {
        @Override
        public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
            return Optional.ofNullable(states.get(contractId));
        }
    }

    /** Period movements by contract. */
    record FakePeriods(Map<String, ContractPeriod> periods) implements ContractPeriodSource {
        @Override
        public ContractPeriod periodFor(String contractId, AsAtBoundary boundary) {
            ContractPeriod period = periods.get(contractId);
            if (period == null) {
                throw new IllegalStateException("no period movements for " + contractId);
            }
            return period;
        }
    }

    /**
     * Empty, and legitimately so: RC-1 is a run-level reconciliation the close gate owns, and the
     * inner loop under test never reads this port. Present because {@code RunRequest} makes every
     * source mandatory — a run that cannot close should be refused at assembly, not discovered at
     * the gate.
     */
    record FakeCoreBanking() implements CoreBankingFeed {
        @Override
        public List<com.crisil.eir.policy.reconciliation.CbsBilledInterest> billedInterest(
            AsAtBoundary boundary) {
            return List.of();
        }
    }

    /** Empty, for the same reason as {@link FakeCoreBanking}: SL-1 is the close gate's. */
    record FakeGeneralLedger() implements GeneralLedgerSource {
        @Override
        public List<com.crisil.eir.gl.posting.GlControlAccountBalance> controlAccountBalances(
            AsAtBoundary boundary) {
            return List.of();
        }
    }

    /** One effective routing-table policy version, so the run has something to stamp. */
    record FakePolicy() implements PolicySource {
        @Override
        public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
            return PolicyVersionRegistry.of(new PolicyVersion(
                "POL-RT-2028.1", PolicyKind.ROUTING_TABLE,
                "the bank's adoption of 03 § 6.1's baseline reading",
                LocalDate.of(2027, 4, 1), "policy.author", "policy.owner",
                LocalDate.of(2027, 3, 15), PolicyVersionStatus.EFFECTIVE));
        }
    }
}
