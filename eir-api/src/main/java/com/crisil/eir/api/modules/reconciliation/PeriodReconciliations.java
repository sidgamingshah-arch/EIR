package com.crisil.eir.api.modules.reconciliation;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.application.ContractResult;
import com.crisil.eir.application.RunRequest;
import com.crisil.eir.application.run.ContractComputation;
import com.crisil.eir.application.run.RunAggregate;
import com.crisil.eir.calc.amort.Stage3Reconciliation;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.posting.GlControlAccountBalance;
import com.crisil.eir.gl.posting.GlReconciliation;
import com.crisil.eir.gl.posting.SubLedgerBalance;
import com.crisil.eir.policy.reconciliation.CoreBankingReconciliation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Three of 06 § 7's four reconciliations, resolved for one period from the run that published it:
 * SL-1 against the general ledger, RC-1 against the core banking system, and the Stage 3 four-way.
 *
 * <h2>This class invokes evaluators; it does not contain one</h2>
 *
 * <p><b>Not one subtraction in this file is part of a reconciliation.</b> The residual arithmetic
 * for all three lives in {@link GlReconciliation#tiesToGl()},
 * {@link CoreBankingReconciliation#tiesToCoreBanking()} and {@link Stage3Reconciliation}, each of
 * which already publishes its own invariant result, and each of which is entitled to exactly one
 * answer per period. A report that differenced the two sides itself would be a second answer under
 * an identifier that admits one — the defect docs/08 records this codebase finding three times, and
 * the reason {@code RunClose} refuses a caller-supplied {@code SUBLEDGER_TO_GL} tie even when the
 * two would agree. So the totals published here are sums of figures the evaluators put on their own
 * lines, and every satisfied/deviation/detail comes back verbatim.
 *
 * <p><b>What is assembled here, and the one duplication that remains.</b> Two of the three need
 * their inputs gathered from the run:
 *
 * <ul>
 *   <li><b>SL-1</b> needs the sub-ledger side — one closing balance per computed contract on the
 *       gross-carrying-amount control account — and the GL side, read back through the run's own
 *       {@code GeneralLedgerSource}. {@code RunClose.present} gathers the same sub-ledger side for
 *       the close, privately, so this is the one input assembly stated in two places. It is not
 *       left to trust: {@code ReconciliationReportsModuleTest} pins this report's SL-1 deviation
 *       against the deviation the close endpoint publishes for the same run, so the two drifting
 *       apart fails a test rather than reaching an operator as two figures.</li>
 *   <li><b>RC-1</b> needs the engine's contractual leg. That is <em>not</em> assembled here — it
 *       arrives on {@link EirService.PublishedFigures#engineContractualLeg()}, built once by the
 *       service that the close also reads, precisely so RC-1's engine side cannot be sourced twice.
 *       The CBS side is read back through the run's own {@code CoreBankingFeed}.</li>
 *   <li><b>S3-1</b> needs nothing assembled at all. The pipeline already built one
 *       {@link Stage3Reconciliation} per contract whose recognition was suppressed and it travels
 *       on {@link ContractComputation#reconciliation()}; this class filters and orders them.</li>
 * </ul>
 *
 * <p><b>The ports are read now, not when the run ran, and that is the mechanism rather than a
 * bug.</b> SL-1's whole value is that the GL is a different system in a different state: before
 * the period's journals are posted the ledger still carries opening balances and the tie is red;
 * after {@code POST /api/post} it carries the sub-ledger's closing detail and the tie is green.
 * A report that had cached the GL side at run time could not show an operator that movement.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 7.
 */
public final class PeriodReconciliations {

    private final EirService.PublishedFigures figures;
    private final GlReconciliation subLedgerToGl;
    private final List<GlControlAccountBalance> glBalancesReported;
    private final CoreBankingReconciliation contractualLegToCbs;
    private final List<StageThreeLine> stageThree;

    private PeriodReconciliations(
        EirService.PublishedFigures figures,
        GlReconciliation subLedgerToGl,
        List<GlControlAccountBalance> glBalancesReported,
        CoreBankingReconciliation contractualLegToCbs,
        List<StageThreeLine> stageThree) {
        this.figures = figures;
        this.subLedgerToGl = subLedgerToGl;
        this.glBalancesReported = glBalancesReported;
        this.contractualLegToCbs = contractualLegToCbs;
        this.stageThree = stageThree;
    }

    /** One contract's Stage 3 four-way, as the pipeline computed it. */
    public record StageThreeLine(String contractId, Stage3Reconciliation fourWay) {

        public StageThreeLine {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(fourWay, "fourWay");
        }
    }

    /**
     * Resolve the three reconciliations for the period these figures were published for.
     *
     * @param figures the run's working papers and the read ports it was assembled with
     */
    public static PeriodReconciliations over(EirService.PublishedFigures figures) {
        Objects.requireNonNull(figures, "figures");
        RunRequest request = figures.request();
        RunAggregate aggregate = figures.aggregate();

        // SL-1's sub-ledger side: the closing gross carrying amount of every contract the run
        // actually computed, on the control account it sits in. Quarantined contracts contribute
        // nothing — which is why every response from this module publishes the population counts
        // beside the figures. A reconciliation over two of three contracts can tie perfectly.
        List<SubLedgerBalance> subLedger = new ArrayList<>();
        for (ContractResult result : aggregate.results()) {
            if (result.isComputed()) {
                subLedger.add(SubLedgerBalance.of(
                    result.contractId(), Book.GCA_ACCOUNT, result.closingGca()));
            }
        }

        // The GL side, through the port, at the run's own boundary. Explanations are empty: this
        // book files none, and an empty list is the honest input rather than a placeholder that
        // could silence a break.
        List<GlControlAccountBalance> reported =
            List.copyOf(request.generalLedger().controlAccountBalances(request.boundary()));
        GlReconciliation gl = GlReconciliation.of(
            request.periodId(), request.bookId(), subLedger, reported, List.of());

        CoreBankingReconciliation cbs = CoreBankingReconciliation.over(
            request.periodId(), currencyOf(aggregate), figures.engineContractualLeg(),
            request.coreBanking().billedInterest(request.boundary()), List.of());

        // In population order rather than map order, so two requests over one run render the same
        // document — the same argument GlReconciliation makes for sorting its account set.
        List<StageThreeLine> stageThree = new ArrayList<>();
        for (ContractResult result : aggregate.results()) {
            ContractComputation computation = figures.computations().get(result.contractId());
            if (computation != null && computation.reconciliation() != null) {
                stageThree.add(
                    new StageThreeLine(result.contractId(), computation.reconciliation()));
            }
        }

        return new PeriodReconciliations(
            figures, gl, reported, cbs, List.copyOf(stageThree));
    }

    /** The run's published figures, for the population counts every response carries. */
    public EirService.PublishedFigures figures() {
        return figures;
    }

    /** SL-1's evaluator, over this period's two sides. */
    public GlReconciliation subLedgerToGl() {
        return subLedgerToGl;
    }

    /**
     * What the general ledger reported, verbatim, including each balance's source reference.
     *
     * <p>Published because the source reference is what tells an operator which state the ledger
     * was in — {@code TB-202805-PRE-POSTING} against {@code TB-202805-POSTED} — and SL-1's own
     * lines do not carry it. A red SL-1 against a pre-posting trial balance is a sequence, not a
     * break, and without the reference the two are indistinguishable on the report.
     */
    public List<GlControlAccountBalance> glBalancesReported() {
        return glBalancesReported;
    }

    /** RC-1's evaluator, over the engine's contractual leg and the CBS feed. */
    public CoreBankingReconciliation contractualLegToCbs() {
        return contractualLegToCbs;
    }

    /** One four-way per contract whose recognition was suppressed, in population order. */
    public List<StageThreeLine> stageThree() {
        return stageThree;
    }

    /** Each contract's S3-1 result, in the same order. */
    public List<InvariantResult> stageThreeResults() {
        List<InvariantResult> results = new ArrayList<>(stageThree.size());
        for (StageThreeLine line : stageThree) {
            results.add(line.fourWay().fourWay());
        }
        return List.copyOf(results);
    }

    /**
     * The portfolio's total absolute S3-1 residual — the figure the close gate's Stage 3 tie
     * carries.
     *
     * <p><b>Absolute, and summed from each contract's own published deviation.</b> Each
     * {@link Stage3Reconciliation} already sums the absolute residuals of its four legs into the
     * deviation on its S3-1 result, so totalling those deviations is the same number
     * {@code EirService.portfolioTies} puts to the gate and is reached without touching a leg.
     * Signed netting is what would let a contract broken one way and a contract broken the other
     * report a reconciled period, which is the classic way this control passes while being broken
     * twice.
     *
     * <p><b>Returned as {@link Money} and not as a bare {@link BigDecimal},</b> so that the report
     * can reduce it to presentation scale exactly once, the way every other figure in these four
     * responses is reduced. An {@code InvariantResult}'s deviation is a plain decimal at working
     * precision; published raw beside per-leg residuals at two places, a broken period's portfolio
     * total renders at twelve decimals and visibly fails to add up to the rows above it.
     */
    public Money stageThreeTotalAbsoluteResidual() {
        return Money.of(EirService.totalDeviation(stageThreeResults()), currency());
    }

    /** The currency the reconciliations are in, as the GL side reported it. */
    public Currency currency() {
        return subLedgerToGl.currency();
    }

    /** How many of the four-ways reconcile on every leg. */
    public int stageThreeReconciling() {
        int reconciling = 0;
        for (StageThreeLine line : stageThree) {
            if (line.fourWay().reconciles()) {
                reconciling++;
            }
        }
        return reconciling;
    }

    /**
     * One side of SL-1, totalled across control accounts.
     *
     * <p>Totalled from the per-account figures the evaluator published rather than from the
     * balances handed in, for the reason {@code RunClose.sumOf} states: a tie reported as
     * "difference nil" and a tie reported as "1,056,814.64 against 1,056,814.64" are the same fact
     * and only the second lets a reader see the magnitude being reconciled.
     *
     * <p><b>A known duplication, recorded rather than hidden.</b> {@code RunClose.sumOf} computes
     * the same two sides for the close gate's {@code SUBLEDGER_TO_GL} tie, privately, so this is
     * the second implementation of one sum. It cannot be shared from here — the method is private
     * in another module, and {@link GlReconciliation} publishes its explained, unexplained and
     * self-reversing totals but not the two raw sides. The right fix is a pair of accessors on
     * {@code GlReconciliation}, which is another unit's file. Until then the drift is bounded by a
     * test: {@code ReconciliationReportsModuleTest} asserts that the two sides published here
     * differ by exactly the difference SL-1 itself reports, so a sum that stopped agreeing with the
     * evaluator fails rather than reaching a report.
     *
     * @param subLedgerSide true for the sub-ledger's total, false for the general ledger's
     */
    public Money subLedgerToGlSide(boolean subLedgerSide) {
        Money total = Money.zero(subLedgerToGl.currency());
        for (var line : subLedgerToGl.lines()) {
            total = total.plus(subLedgerSide ? line.subLedgerTotal() : line.glBalance());
        }
        return total;
    }

    /**
     * The currency the run is in, from its first journal.
     *
     * <p>The same rule {@code RunClose} applies, and stated the same way: an empty batch has no
     * currency to read and INR is this book's, so a run that computed nothing still produces a
     * well-formed reconciliation rather than throwing on the way to reporting that it computed
     * nothing.
     */
    private static Currency currencyOf(RunAggregate aggregate) {
        return aggregate.journals().isEmpty()
            ? Money.INR
            : aggregate.journals().getFirst().currency();
    }
}
