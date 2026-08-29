package com.crisil.eir.api.store;

import com.crisil.eir.application.port.ContractStateSource;
import com.crisil.eir.application.run.ContractPeriod;
import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.calc.projection.ScheduleShape;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The opening book the server starts with: three contracts, chosen so that every outcome the tool
 * can show is reachable on the first click.
 *
 * <p><b>Every figure here is reference case 1's, and it is documented arithmetic rather than
 * anything read off this engine.</b> A 10,00,000.00 loan at 1% monthly over 24 months, disbursed
 * 2027-04-30 with the first instalment 2027-05-31, carrying a 15,000.00 processing fee. Its EIR
 * solves to 0.010421491800 periodic. At month 13 the opening gross carrying amount is 528,407.32,
 * the EMI is 47,073.47 of which 5,298.16 is contractual interest, and the closing gross carrying
 * amount is 486,840.64.
 *
 * <p><b>Why three and not one.</b> A demonstration book of one performing contract shows a green
 * close and teaches an operator nothing about what the controls are for:
 *
 * <ul>
 *   <li><b>C-0001</b> performs. Stage 1, no allowance, the instalment received in full.
 *   <li><b>C-0002</b> is in Stage 3 with a 40% lifetime allowance and paid nothing. It accrues on
 *       the gross basis like every other stage — ACPIR presents provisions separately rather than
 *       netting them — and its recognition is suppressed to the suspense ledger instead. It is the
 *       contract that makes the Stage 3 four-way and the suspense movement visible.
 *   <li><b>C-0003</b> has period movements and <em>no opening state</em>. It is the data condition
 *       FR-905 exists for: the population names it, the contract master does not carry it, and the
 *       run must isolate it and carry on rather than abandoning the other two. Without a contract
 *       like this the exception queue is an empty panel and the population accounting is a row of
 *       zeroes.
 * </ul>
 *
 * <p>An operator who wants a clean close deletes nothing — they work C-0003's exception, which is
 * the workflow the close gate is built around.
 */
public final class Seed {

    /** The period this book is positioned at the end of: May 2028, month 13 of the loan. */
    public static final LocalDate PERIOD_START = LocalDate.of(2028, 4, 30);
    public static final LocalDate PERIOD_END = LocalDate.of(2028, 5, 31);
    public static final int PERIOD_ID = 202805;

    /** Reference case 1's solved EIR, periodic, at 12 periods a year. */
    public static final Rate EIR = Rate.periodic(new BigDecimal("0.010421491800"), 12);

    public static final Money OPENING_GCA = Money.inr("528407.32");
    public static final Money OPENING_CONTRACTUAL = Money.inr("529815.61");
    public static final Money EMI = Money.inr("47073.47");
    public static final Money BILLED_INTEREST = Money.inr("5298.16");
    public static final Money PRINCIPAL_SLICE = Money.inr("41775.31");
    /** 40% of the gross carrying amount, case 5's lifetime allowance. */
    public static final Money STAGE_3_ALLOWANCE = Money.inr("211362.93");
    public static final Money NIL = Money.zero(Money.INR);

    private Seed() {
    }

    /** Reference case 1's terms. */
    public static ContractTerms caseOneTerms() {
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
     * The period's flow vector: one boundary, one flow.
     *
     * <p>The ordinal is 1 <em>relative to the period's anchor</em>, not 13 relative to the
     * contract. {@code AmortisationEngine} differences tau from zero at the anchor, so a flow
     * labelled 13 in a one-period segment would declare a thirteen-period accrual and the exponent
     * leg of ST-2 would break — which is the check working, on a caller's mistake.
     */
    private static FlowVector periodVector(Money cash) {
        return FlowVector.of(PERIOD_START, Money.INR,
            List.of(CashFlow.of(PERIOD_END, 1, cash, FlowKind.COMBINED_EMI)));
    }

    private static ContractPeriod performingPeriod(String contractId) {
        // 47,073.47 − 5,298.16 = 41,775.31. Split by hand from the two published figures, so the
        // cash book's total ties to the vector's flow and the journal's cash block balances.
        return ContractPeriod.of(contractId, 13, periodVector(EMI),
            TimeConvention.PeriodicIndex.monthly(), PRINCIPAL_SLICE, BILLED_INTEREST);
    }

    /**
     * A Stage 3 period in which the borrower paid nothing.
     *
     * <p>A zero-amount flow on the period date rather than an empty vector: it changes no present
     * value and no balance, and it declares the accrual boundary, which is what the projector
     * requires of a caller wanting one row per accounting period.
     */
    private static ContractPeriod defaultedPeriod(String contractId) {
        return ContractPeriod.of(contractId, 13, periodVector(NIL),
            TimeConvention.PeriodicIndex.monthly(), NIL, NIL);
    }

    private static ContractStateSource.OpeningState performingState() {
        return new ContractStateSource.OpeningState(
            caseOneTerms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL,
            Stage.STAGE_1, NIL, "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    private static ContractStateSource.OpeningState stageThreeState() {
        return new ContractStateSource.OpeningState(
            caseOneTerms(), EIR, OPENING_GCA, OPENING_CONTRACTUAL,
            Stage.STAGE_3, STAGE_3_ALLOWANCE, "ECL-MODEL-2028.05", BILLED_INTEREST);
    }

    /**
     * The policy versions in force over this book, for the run record and DT-1's policy leg.
     *
     * <p><b>The routing table's version is taken from the table itself, not written out here.</b>
     * The first run of this server stamped a routing version the registry did not carry, and DT-1
     * caught it: "the version used was not in force for the period — expected (absent), found
     * RT-BASELINE-2026.1". That is precisely the discrepancy DT-1 exists for, on a book where every
     * figure was bit-identical, and it would have been invisible to every other control. Deriving
     * the id from {@code RoutingTable.currentDefault()} means the registry and the run cannot
     * disagree about which table governed the period.
     */
    public static PolicyVersionRegistry policies() {
        RoutingTableVersion routing = RoutingTable.currentDefault().version();
        return PolicyVersionRegistry.of(
            new PolicyVersion("POL-FEE-2028.1", PolicyKind.FEE_RULE_SET,
                "fee and cost taxonomy, FY2028-29", LocalDate.of(2028, 4, 1),
                "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
                PolicyVersionStatus.EFFECTIVE),
            new PolicyVersion("POL-TIER-2028.1", PolicyKind.TIER_ASSIGNMENT,
                "materiality tier thresholds, FY2028-29", LocalDate.of(2028, 4, 1),
                "policy.maker", "policy.checker", LocalDate.of(2028, 3, 15),
                PolicyVersionStatus.EFFECTIVE),
            new PolicyVersion(routing.id(), PolicyKind.ROUTING_TABLE,
                routing.description(), routing.effectiveFrom(),
                routing.maker(), routing.checker(), routing.approvedOn(),
                PolicyVersionStatus.EFFECTIVE));
    }

    /** The opening book. */
    public static Book book() {
        Book book = new Book(policies());

        book.put(Book.Holding.onFile("C-0001", "HL", "IN-MUM",
            "Housing loan, performing — reference case 1 at month 13",
            performingState(), performingPeriod("C-0001")));

        book.put(Book.Holding.onFile("C-0002", "HL", "IN-MUM",
            "Housing loan, Stage 3 — no receipt, recognition suppressed to suspense",
            stageThreeState(), defaultedPeriod("C-0002")));

        // No opening state on purpose, and the state source genuinely declines for it. See the
        // class javadoc: this is the contract that makes FR-905's barrier and the exception queue
        // visible instead of theoretical.
        book.put(Book.Holding.movementsOnly(CONTRACT_WITHOUT_STATE, "HL", "IN-PUN",
            "Housing loan, movements on file and no opening balance recorded",
            performingState(), performingPeriod(CONTRACT_WITHOUT_STATE)));

        return book;
    }

    /**
     * The book with C-0003's opening state genuinely absent.
     *
     * <p>{@link Book} holds one {@code Holding} per contract and a holding requires a state, so the
     * missing-state condition is produced by a state source that declines to answer for C-0003
     * rather than by a null field. That is the honest shape: the port returns
     * {@code Optional.empty()}, which is exactly what a real master would do for a row it does not
     * carry, and the pipeline's own message names the boundary it could not find a balance at.
     */
    public static final String CONTRACT_WITHOUT_STATE = "C-0003";
}
