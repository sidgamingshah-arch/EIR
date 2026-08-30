package com.crisil.eir.application.load;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.port.CoreBankingFeed;
import com.crisil.eir.application.port.GeneralLedgerSource;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.application.run.ContractPeriodSource;
import com.crisil.eir.application.run.PeriodEvent;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.domain.Stage;
import com.crisil.eir.domain.TimeConvention;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.reconciliation.CbsBilledInterest;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A synthetic book of N contracts at reference case 1's month 13, supplied through all six ports.
 *
 * <h2>What this is for</h2>
 *
 * <p>Phase 5's exit gate in {@code docs/08} asks for "a 10M-contract synthetic close inside 4
 * hours" and "a replay of that close is byte-identical". The roadmap records both as unmet because
 * "there is no run to load, so there is no 10M-contract close to time and no close to replay", and
 * separately that "the 4-hour figure is untested and remains an estimate, as does ADR-0009's
 * core-hour costing". This class is the population that makes the two measurable;
 * {@code tools/load-harness/LoadHarness} drives it and {@code tools/load-harness/RESULTS.md}
 * carries the measured figures.
 *
 * <h2>Nothing is stored per contract, and that is deliberate</h2>
 *
 * <p>Every {@link ContractStateSource.OpeningState}, {@link ContractTerms}, {@link FlowVector} and
 * {@link PeriodEvent} is a per-archetype singleton, and the only per-contract allocation is the
 * {@link ContractPeriod} wrapper that has to carry the contract id. A generator holding N states in
 * a map would put its own footprint into the heap figure the harness reports, and the heap figure is
 * the finding — so the retained set the harness measures is the engine's own and no part of it is
 * this class's. The consequence to state honestly in any reading of the numbers: a real JDBC
 * adapter would add its own per-contract cost on top of what is measured here, so the measured heap
 * is a <b>lower bound</b> on a production close, not an estimate of one.
 *
 * <p>The one exception is {@link #contractIdsInScope}, which materialises an
 * {@code ArrayList<String>} of N ids because that is what the port's signature is —
 * {@code List<String>}, not a stream or a cursor. That cost belongs to the engine's port contract
 * rather than to this class, and the harness times and reports it as a phase of its own.
 *
 * <h2>Where the figures come from</h2>
 *
 * <p>All of them are published, and none is computed by the code under measurement:
 *
 * <ul>
 *   <li>reference case 1 at month 13 — opening GCA 528,407.32, EMI 47,073.47, EIR 1.04214918% per
 *       month at 12dp, gross EIR interest 5,506.79, closing GCA 486,840.64;</li>
 *   <li>reference case 5, the same loan in Stage 3 from the start of month 13 — a 40% lifetime ECL
 *       allowance of 211,362.93 and 5,298.16 of contractual interest billed to suspense;</li>
 *   <li>the cash split 41,775.31 / 5,298.16, which is 47,073.47 less the billed interest.</li>
 * </ul>
 *
 * <p>These are the same values {@code RunFixtures} uses in the run package's tests, where each is
 * cross-checked against a {@code python3} recomputation at 28 significant digits. They are repeated
 * here rather than shared because {@code RunFixtures} is package-private to
 * {@code com.crisil.eir.application.run} and widening it for a harness would loosen a test fixture
 * for a non-test reason.
 *
 * <h2>No clock</h2>
 *
 * <p>{@link #KNOWN_AT} and {@link #PERIOD_END} are constants and the boundary is built from them.
 * Nothing here reads {@code Instant.now()} or {@code LocalDate.now()}: the harness times the engine
 * from outside it, and the engine is handed its instants (DT-1, ADR-0003). That is also what makes
 * the replay measurable at all — a run whose boundary moved could not be replayed to the same
 * figures however deterministic its arithmetic.
 */
public final class SyntheticBook
    implements ContractSource, ContractStateSource, ContractPeriodSource, CoreBankingFeed,
    GeneralLedgerSource, PolicySource {

    /** The run id every live close in this harness carries. */
    public static final String RUN_ID = "LOAD-202805-LIVE";

    /** {@code YYYYMM} of the period being closed. */
    public static final int PERIOD_ID = 202805;

    /**
     * The book every close in this harness runs on, and it cannot be anything else today.
     *
     * <p><b>Reported, not worked around silently.</b> {@code RunClose.present} builds its
     * sub-ledger side with {@code SubLedgerBalance.of(contractId, account, balance)}, whose
     * two-argument factory hard-codes the book id {@code "MAIN"}, and then hands
     * {@code GlReconciliation.of} the run's own {@code request.bookId()}. Where the two differ,
     * {@code GlReconciliation.requireBook} throws {@code IllegalArgumentException} — so a close on
     * any book but {@code MAIN} fails with an exception rather than returning a refusal as a value,
     * which is the one thing this codebase's controls otherwise never do. That is a defect in
     * {@code eir-application/src/main}, outside this unit's remit to fix; the harness names the
     * book {@code MAIN} so that the timing measurement can proceed, and
     * {@code tools/load-harness/RESULTS.md} records the finding.
     */
    public static final String BOOK_ID = "MAIN";

    /** The GL control account the close reconciles the sub-ledger against. */
    public static final String GCA_ACCOUNT = "1301-LOANS-GCA";

    /** The month-13 accrual boundary: the month-12 due date to the month-13 due date. */
    public static final LocalDate PERIOD_START = LocalDate.of(2028, 4, 30);
    public static final LocalDate PERIOD_END = LocalDate.of(2028, 5, 31);

    /** The system-time half of the boundary. Supplied, never read from a clock. */
    public static final Instant KNOWN_AT = Instant.parse("2028-06-01T00:00:00Z");

    /** Reference case 1's EIR, per month, at the stored 12dp scale. */
    public static final Rate EIR = Rate.periodic(new BigDecimal("0.010421491800"), 12);

    /** Reference case 1's EIR expressed as 13.248094% p.a. effective, for the actual-date path. */
    public static final Rate EIR_ANNUAL_EFFECTIVE =
        Rate.annualEffective(new BigDecimal("0.132480940000"));

    /** Month-13 opening gross carrying amount (reference case 1, period 13). */
    public static final Money OPENING_GCA = Money.inr("528407.32");

    /** The contractual-leg balance brought forward alongside it. */
    public static final Money OPENING_CONTRACTUAL = Money.inr("529815.61");

    /** The billed EMI (reference case 1: the annuity 47,073.472223 rounded to paise). */
    public static final Money EMI = Money.inr("47073.47");

    /** Contractual interest billed in month 13 (reference case 5's suspense charge). */
    public static final Money BILLED_INTEREST = Money.inr("5298.16");

    /** 47,073.47 − 5,298.16, split by hand so the cash book ties to the flow vector. */
    public static final Money CASH_TO_PRINCIPAL = Money.inr("41775.31");

    /** Reference case 5's lifetime ECL allowance: 40% of 528,407.32 = 211,362.928 → 211,362.93. */
    public static final Money STAGE_3_ALLOWANCE = Money.inr("211362.93");

    /** The revised instalment a reset or a catch-up event carries — a rate cut, twelve to run. */
    public static final Money REVISED_INSTALMENT = Money.inr("45000.00");

    private static final Money NIL = Money.zero(Money.INR);

    private static final String ECL_VERSION = "ECL-MODEL-2028.05";

    /** Uniform monthly periods with every flow on a boundary — reference case 1's own shape. */
    private static final TimeConvention MONTHLY = TimeConvention.PeriodicIndex.monthly();

    /** 03 § 3.10's default and fallback dating. */
    private static final TimeConvention ACTUAL_365F =
        new TimeConvention.ActualDate(DayCountConvention.ACT_365F);

    /** The engine's baseline reading of 03 § 6.1, effective 2026-05-01 and so in force here. */
    public static final RoutingTableRegistry ROUTING =
        RoutingTableRegistry.of(RoutingTable.currentDefault());

    /**
     * The one policy version this book stamps.
     *
     * <p>A single-version timeline, and the reader of the replay numbers has to know it: the policy
     * leg of DT-1 asks whether the replay cited the version in force at the period end, and with
     * one version of one kind on the timeline that leg cannot fail. The figure leg — every closing
     * balance and every journal line, byte for byte — is the leg this harness actually exercises,
     * and it is the leg the gate is about. {@code ReplayUseCasePropertiesTest} is where a
     * superseded timeline is exercised.
     */
    private static final PolicyVersionRegistry POLICY = PolicyVersionRegistry.of(new PolicyVersion(
        "POL-RT-2028.1", PolicyKind.ROUTING_TABLE,
        "the bank's adoption of 03 § 6.1's baseline reading",
        LocalDate.of(2027, 4, 1), "policy.author", "policy.owner",
        LocalDate.of(2027, 3, 15), PolicyVersionStatus.EFFECTIVE));

    /** How many slots the interleave table holds — one per permille, so shares are exact. */
    private static final int SLOTS = 1000;

    private final int size;
    private final Archetype[] interleave;
    private final ContractStateSource.OpeningState[] states;
    private final ContractPeriod[] periodTemplates;
    private final int[] counts;

    /**
     * A book of {@code size} contracts under the baseline mix in {@link Archetype}.
     */
    public SyntheticBook(int size) {
        this(size, Archetype.EVENT_RESET.defaultPermille());
    }

    /**
     * A book of {@code size} contracts with the reset share overridden.
     *
     * <p>The override exists because the reset share is the least defensible number in
     * {@link Archetype} and a single point estimate of it would make the whole extrapolation a
     * guess. Whatever is taken from or given to the reset is taken from or given to
     * {@link Archetype#PERFORMING_PERIODIC}, so the two performing conventions keep their relative
     * weight and the Stage 3 and catch-up shares are untouched — a sweep over this argument
     * therefore measures the marginal cost of a solve and nothing else.
     *
     * @param size            contracts in the population
     * @param resetPermille   the share of the book routing to a B5.4.5 reset, in parts per thousand
     */
    public SyntheticBook(int size, int resetPermille) {
        this(size, sharesWithResetAt(resetPermille));
    }

    /**
     * A book of one archetype only, for attributing cost per path.
     *
     * <p>Used by the harness's attribution pass, and read with one caveat stated in
     * {@code RESULTS.md}: a book of a single archetype gives the JIT a monomorphic loop it does not
     * get on a mixed book, so the per-contract figures from a pure book are a <em>floor</em> on
     * what that path costs inside a real close. They are still the only way to separate the cost of
     * a fractional power from the cost of a solve, which is the comparison ADR-0009's 0.2
     * core-hours per 10M-contract close needs to be checked against.
     */
    public static SyntheticBook pure(int size, Archetype only) {
        int[] shares = new int[Archetype.values().length];
        shares[only.ordinal()] = SLOTS;
        return new SyntheticBook(size, shares);
    }

    private SyntheticBook(int size, int[] permille) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative, got " + size);
        }
        this.size = size;
        this.interleave = interleave(permille);
        this.counts = new int[Archetype.values().length];
        for (int i = 0; i < size; i++) {
            counts[interleave[i % SLOTS].ordinal()]++;
        }
        this.states = new ContractStateSource.OpeningState[Archetype.values().length];
        this.periodTemplates = new ContractPeriod[Archetype.values().length];
        for (Archetype archetype : Archetype.values()) {
            states[archetype.ordinal()] = stateFor(archetype);
            periodTemplates[archetype.ordinal()] = periodTemplateFor(archetype);
        }
    }

    /**
     * The baseline mix with the reset share moved to {@code resetPermille}.
     *
     * <p>The slots the reset gains or loses come out of {@link Archetype#PERFORMING_PERIODIC}, so a
     * sweep over this argument changes one thing: how many contracts reach a solver. That is what
     * makes the sweep a measurement of the marginal cost of a B5.4.5 solve rather than of a
     * different book.
     */
    private static int[] sharesWithResetAt(int resetPermille) {
        Archetype[] values = Archetype.values();
        int[] share = new int[values.length];
        int declared = 0;
        for (Archetype archetype : values) {
            share[archetype.ordinal()] = archetype.defaultPermille();
            declared += archetype.defaultPermille();
        }
        if (declared != SLOTS) {
            throw new IllegalStateException(
                "the archetype shares declare " + declared + " permille, not " + SLOTS + "; a mix"
                    + " that does not add up makes every extrapolation from this harness wrong by"
                    + " the difference, in a direction the numbers do not show");
        }
        if (resetPermille < 0 || resetPermille > SLOTS) {
            throw new IllegalArgumentException(
                "resetPermille must be within [0, " + SLOTS + "], got " + resetPermille);
        }
        int delta = resetPermille - Archetype.EVENT_RESET.defaultPermille();
        share[Archetype.EVENT_RESET.ordinal()] += delta;
        share[Archetype.PERFORMING_PERIODIC.ordinal()] -= delta;
        if (share[Archetype.PERFORMING_PERIODIC.ordinal()] < 0) {
            throw new IllegalArgumentException(
                "a reset share of " + resetPermille + " permille leaves the periodic performing"
                    + " block negative; the sweep takes its slots from there and there are only "
                    + Archetype.PERFORMING_PERIODIC.defaultPermille() + " permille to take");
        }
        return share;
    }

    /**
     * The archetype of every slot in a thousand, spread rather than blocked.
     *
     * <p>Spread by the highest-averages (Sainte-Laguë) rule, in integers: at each slot the
     * archetype furthest behind its target share takes it. Two properties matter and neither is
     * cosmetic.
     *
     * <p><b>Every prefix of the population carries the mix.</b> Blocked assignment — the first 600
     * contracts periodic, the next 320 actual-dated, and so on — would give a 10,000-contract run
     * and a 1,000,000-contract run different mixes at any point short of the end, so the two
     * timings could not be compared and the scaling question the gate turns on would be
     * unanswerable. With the interleave, {@code size % 1000} contracts of drift is the whole error.
     *
     * <p><b>The expensive paths are not contiguous.</b> A thousand consecutive resets would let the
     * JIT specialise the loop on one branch and would measure a book nobody has, in the direction
     * that flatters the result.
     *
     * <p>Integer arithmetic throughout, per ADR-0002 — the comparison
     * {@code share[a] * (taken[b] + 1)} against {@code share[b] * (taken[a] + 1)} is the
     * highest-averages test with the division cleared, and it needs no floating point to be exact.
     */
    private static Archetype[] interleave(int[] share) {
        Archetype[] values = Archetype.values();
        int total = 0;
        for (int permille : share) {
            total += permille;
        }
        if (total != SLOTS) {
            throw new IllegalArgumentException(
                "the mix declares " + total + " permille, not " + SLOTS);
        }

        Archetype[] slots = new Archetype[SLOTS];
        int[] taken = new int[values.length];
        for (int slot = 0; slot < SLOTS; slot++) {
            int best = -1;
            for (int candidate = 0; candidate < values.length; candidate++) {
                if (share[candidate] == 0) {
                    continue;
                }
                if (best < 0
                    || (long) share[candidate] * (taken[best] + 1)
                        > (long) share[best] * (taken[candidate] + 1)) {
                    best = candidate;
                }
            }
            slots[slot] = values[best];
            taken[best]++;
        }
        return slots;
    }

    // ------------------------------------------------------------------ the population

    /** The archetype the contract at {@code index} carries. */
    public Archetype archetypeAt(int index) {
        return interleave[index % SLOTS];
    }

    /** How many contracts of {@code archetype} this book holds. */
    public int countOf(Archetype archetype) {
        return counts[archetype.ordinal()];
    }

    /** How many contracts in this book reach the solver — one solve each. */
    public int expectedSolves() {
        int solves = 0;
        for (Archetype archetype : Archetype.values()) {
            if (archetype.solves()) {
                solves += counts[archetype.ordinal()];
            }
        }
        return solves;
    }

    /** Contracts in the book. */
    public int size() {
        return size;
    }

    /**
     * The contract id at {@code index}: {@code LN-} and eight digits, as reference case 1's own.
     *
     * <p>Zero-padded to a fixed width so that every id is the same length, which keeps the
     * population's string footprint uniform and makes the per-contract heap figure the harness
     * reports a single number rather than a distribution.
     *
     * <p>Built by hand rather than with {@code String.format}, which measurably matters here and
     * nowhere else in this codebase: formatting ten million ids costs of the order of a
     * microsecond each, so a {@code String.format} would put ten seconds of the harness's own
     * cost into a phase whose whole purpose is to attribute cost to the engine.
     */
    public static String contractId(int index) {
        char[] id = {'L', 'N', '-', '0', '0', '0', '0', '0', '0', '0', '0'};
        int remaining = index;
        for (int digit = id.length - 1; digit >= 3 && remaining > 0; digit--) {
            id[digit] = (char) ('0' + remaining % 10);
            remaining /= 10;
        }
        return new String(id);
    }

    /** The index a {@link #contractId} encodes. */
    public static int indexOf(String contractId) {
        return Integer.parseInt(contractId, 3, contractId.length(), 10);
    }

    @Override
    public List<String> contractIdsInScope(AsAtBoundary boundary) {
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(contractId(i));
        }
        return ids;
    }

    @Override
    public Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary) {
        int index = indexOf(contractId);
        if (index < 0 || index >= size) {
            return Optional.empty();
        }
        return Optional.of(states[archetypeAt(index).ordinal()]);
    }

    @Override
    public ContractPeriod periodFor(String contractId, AsAtBoundary boundary) {
        int index = indexOf(contractId);
        if (index < 0 || index >= size) {
            throw new IllegalStateException("no period movements for " + contractId);
        }
        ContractPeriod template = periodTemplates[archetypeAt(index).ordinal()];
        // The only per-contract allocation in this class. Ten reference fields; the vector, the
        // convention and every Money are the archetype's own singletons.
        return new ContractPeriod(
            contractId, template.periodOrdinal(), template.periodFlows(), template.convention(),
            template.cashAppliedToPrincipal(), template.cashAppliedToInterest(),
            template.suspenseOpeningBalance(), template.suspenseRecovered(),
            template.suspenseWrittenOff(), template.event());
    }

    /**
     * One CBS line per contract, all carrying the billed interest the states carry.
     *
     * <p>RC-1 compares the engine's contractual leg against this, so both sides are 5,298.16 and
     * the control ties. That makes the harness's RC-1 an assertion that the control was
     * <em>reached with a figure on each side</em> at population scale, and no fixture can make it
     * more than that: two independent systems agreeing is not something a synthetic book can
     * arrange. {@code RunCloseTest} is where a disagreement is exercised. What this port is for
     * here is cost — it is one of four full-population lists a close builds, and the harness
     * reports its share of the close's heap.
     */
    @Override
    public List<CbsBilledInterest> billedInterest(AsAtBoundary boundary) {
        List<CbsBilledInterest> lines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lines.add(new CbsBilledInterest(
                contractId(i), PERIOD_ID, BILLED_INTEREST, "CBS-EOD-" + PERIOD_ID));
        }
        return lines;
    }

    /**
     * The GL's control-account balance, which the harness has to be handed rather than derive.
     *
     * <p>Set by {@link #withGlBalance}: the sub-ledger total is only known once the run has
     * finished, and SL-1 compares the two sides. Handing the run's own total back is again a
     * statement that the control was reached, not that two systems agree.
     */
    private Money glBalance = NIL;

    /** The GL side of SL-1, for a close over a run whose sub-ledger total is now known. */
    public void withGlBalance(Money balance) {
        this.glBalance = balance;
    }

    @Override
    public List<GlControlAccountBalance> controlAccountBalances(AsAtBoundary boundary) {
        return List.of(
            GlControlAccountBalance.of(GCA_ACCOUNT, glBalance, "TB-" + PERIOD_ID + "-FINAL"));
    }

    @Override
    public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
        return POLICY;
    }

    // ------------------------------------------------------------------ assembly

    /** The live boundary: the period end for business time, {@link #KNOWN_AT} for system time. */
    public static AsAtBoundary boundary() {
        return AsAtBoundary.live(PERIOD_END, KNOWN_AT);
    }

    /** A live run request over this book, with this book behind all five ports. */
    public RunRequest liveRequest(String runId) {
        return new RunRequest(runId, PERIOD_ID, BOOK_ID, boundary(), this, this, this, this, this);
    }

    /** The stamps a run over this book resolves — {@code ROUTING_TABLE=POL-RT-2028.1}. */
    public Map<PolicyKind, String> policyStamps() {
        Map<PolicyKind, String> stamps = new EnumMap<>(PolicyKind.class);
        POLICY.inForceOn(PERIOD_END).forEach((kind, version) -> stamps.put(kind, version.id()));
        return stamps;
    }

    // ------------------------------------------------------------------ the archetypes

    private static ContractTerms case1Terms() {
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

    /**
     * The same loan as a floating instrument.
     *
     * <p>Needed for the reset archetype and not optional: {@code DefaultEventRouter}'s rate-type
     * check sends a market-movement driver on a FIXED instrument to the substantiality test
     * whatever the table says, because a fixed-rate loan has no term that reprices off a benchmark
     * (FR-507). A reset archetype built on {@code case1Terms} would therefore quarantine every one
     * of its contracts for want of a recorded conclusion, and the harness would report a solve
     * count of nil and a close blocked on 1% of the book.
     */
    private static ContractTerms floatingTerms() {
        ContractTerms fixed = case1Terms();
        return new ContractTerms(
            fixed.principal(), fixed.contractualRate(), fixed.termPeriods(), fixed.periodsPerYear(),
            fixed.disbursementDate(), fixed.firstDueDate(), fixed.dayCount(), fixed.shape(),
            RateType.FLOATING, fixed.currency(), 0, false, null, null, null, 0, 0, null);
    }

    private static ContractStateSource.OpeningState stateFor(Archetype archetype) {
        return switch (archetype) {
            case PERFORMING_PERIODIC, EVENT_CATCH_UP -> new ContractStateSource.OpeningState(
                case1Terms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL, Stage.STAGE_1, NIL,
                ECL_VERSION, BILLED_INTEREST);
            case PERFORMING_ACTUAL -> new ContractStateSource.OpeningState(
                case1Terms(), EIR_ANNUAL_EFFECTIVE, OPENING_GCA, OPENING_CONTRACTUAL,
                Stage.STAGE_1, NIL, ECL_VERSION, BILLED_INTEREST);
            case STAGE_3_SUPPRESSED -> new ContractStateSource.OpeningState(
                case1Terms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL, Stage.STAGE_3,
                STAGE_3_ALLOWANCE, ECL_VERSION, BILLED_INTEREST);
            case EVENT_RESET -> new ContractStateSource.OpeningState(
                floatingTerms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL, Stage.STAGE_1, NIL,
                ECL_VERSION, BILLED_INTEREST);
        };
    }

    /**
     * The period's own flows: one boundary, one flow, ordinal 1 relative to the period's anchor.
     *
     * <p>Relative to the anchor and not to the contract, because {@code AmortisationEngine}
     * differences tau from zero at the anchor — a flow labelled 13 in a one-period segment would
     * declare a thirteen-period accrual and ST-2 would say so.
     */
    private static FlowVector periodVector(LocalDate flowDate, Money cash) {
        return FlowVector.of(PERIOD_START, Money.INR,
            List.of(CashFlow.of(flowDate, 1, cash, FlowKind.COMBINED_EMI)));
    }

    /** Twelve remaining instalments anchored at the period start — case 1's own tail, revised. */
    private static FlowVector remainingInstalments(Money instalment) {
        List<CashFlow> flows = new ArrayList<>(12);
        for (int period = 1; period <= 12; period++) {
            flows.add(CashFlow.of(
                PERIOD_START.plusMonths(period), period, instalment, FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(PERIOD_START, Money.INR, List.copyOf(flows));
    }

    private static ContractPeriod periodTemplateFor(Archetype archetype) {
        // The contract id on a template is never read: periodFor copies every other field onto the
        // id it was asked about. A placeholder is used rather than null because ContractPeriod
        // refuses a blank one, and rightly — a period movement that cannot be attributed to a
        // contract cannot be isolated per contract (FR-905).
        String template = "TEMPLATE";
        return switch (archetype) {
            case PERFORMING_PERIODIC -> ContractPeriod.of(
                template, 13, periodVector(PERIOD_END, EMI), MONTHLY,
                CASH_TO_PRINCIPAL, BILLED_INTEREST);
            // The flow is dated, not indexed: under actual dating the exponent is the day count
            // between the schedule's month-12 and month-13 due dates, 31/365, and the vector has
            // to carry the same dates for ST-2's two derivations to agree.
            case PERFORMING_ACTUAL -> ContractPeriod.of(
                template, 13, periodVector(PERIOD_END, EMI), ACTUAL_365F,
                CASH_TO_PRINCIPAL, BILLED_INTEREST);
            // A Stage 3 borrower who paid nothing. The zero-amount flow on the period date changes
            // no present value and no balance; it declares the accrual boundary, which is what
            // AmortisationEngine's javadoc prescribes for a caller wanting a row per period. S3-1's
            // fourth leg then holds: no cash reached the interest leg and nothing came out of
            // suspense.
            case STAGE_3_SUPPRESSED -> ContractPeriod.of(
                template, 13, periodVector(PERIOD_END, NIL), MONTHLY, NIL, NIL);
            // ESG_LINKED is a pre-determined adjustment compensating for neither the time value of
            // money nor credit risk, so 03 § 6.1's baseline routes it to a B5.4.6 catch-up: the
            // rate is retained and the balance is restated by discounting the revised flows at it.
            case EVENT_CATCH_UP -> ContractPeriod.of(
                template, 13, periodVector(PERIOD_END, EMI), MONTHLY,
                CASH_TO_PRINCIPAL, BILLED_INTEREST)
                .withEvent(PeriodEvent.of(
                    RateDriver.ESG_LINKED, PERIOD_START,
                    remainingInstalments(REVISED_INSTALMENT)));
            // A benchmark movement on a floating instrument: 03 § 6.1 routes it to a B5.4.5 reset
            // and the rate is re-solved over the revised flows from the current carrying amount.
            // The revised instalment is 45,000.00 against a billed 47,073.47, so the solve has to
            // travel a long way from its seed of 1.0421% — which is conservative on iteration
            // count, since a real repo-linked reset moves the rate by tens of basis points and
            // converges in fewer steps.
            case EVENT_RESET -> ContractPeriod.of(
                template, 13, periodVector(PERIOD_END, EMI), MONTHLY,
                CASH_TO_PRINCIPAL, BILLED_INTEREST)
                .withEvent(PeriodEvent.of(
                    RateDriver.TIME_VALUE_OF_MONEY, PERIOD_START,
                    remainingInstalments(REVISED_INSTALMENT)));
        };
    }
}
