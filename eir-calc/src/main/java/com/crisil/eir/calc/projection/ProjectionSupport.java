package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * The assembly every projector shares: the inception leg, the initial carrying
 * amount, the expected leg, and the convention check.
 *
 * <p>Package-private on purpose. These steps are identical across shapes and must
 * stay identical — an inception leg assembled slightly differently by one
 * projector is an IC-1 breach on one product only, which is the hardest kind of
 * defect to find. Routing every projector through one assembly makes that
 * impossible rather than unlikely.
 */
final class ProjectionSupport {

    private ProjectionSupport() {
    }

    /**
     * The net integral fee, signed: positive where the net is income.
     *
     * <p>Only {@code INTEGRAL} postings count. A commitment fee routed to
     * {@code OVER_COMMITMENT_PERIOD}, an as-incurred servicing charge and a
     * separate performance obligation are all real postings that simply do not
     * belong to the initial carrying amount, and dropping them here is the whole
     * point of having the rule set resolve classification first.
     */
    static Money netIntegralFee(List<FeePosting> fees, Currency currency) {
        Money net = Money.zero(currency);
        for (FeePosting fee : fees) {
            if (!fee.entersInitialCarryingAmount()) {
                continue;
            }
            if (!fee.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                    "fee " + fee.feeCode() + " is " + fee.amount().currency().getCurrencyCode()
                        + " and the contract is " + currency.getCurrencyCode());
            }
            net = net.plus(fee.amount());
        }
        return net;
    }

    /**
     * The flows dated on the anchor: the amount advanced, and each integral
     * posting.
     *
     * <p>Integral postings are dated at initial recognition rather than at their
     * own posting date, and that is a deliberate choice with two alternatives that
     * are both worse. Discounting an origination cost as though it were a future
     * flow understates the initial carrying amount; dropping it breaks IC-1. A
     * posting that genuinely arises later is a subsequent cost and a different
     * event — not an input to the inception projection. {@code postedOn} is
     * retained on the posting for the audit trail.
     */
    static List<CashFlow> inceptionLeg(ContractTerms terms, Money amountAdvanced, List<FeePosting> fees) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(CashFlow.of(
            terms.disbursementDate(), 0, amountAdvanced.negate().atPresentationScale(), FlowKind.DISBURSEMENT));
        for (FeePosting fee : fees) {
            if (!fee.entersInitialCarryingAmount() || fee.amount().isZero()) {
                continue;
            }
            FlowKind kind = fee.amount().isPositive()
                ? FlowKind.INTEGRAL_FEE_RECEIVED
                : FlowKind.INTEGRAL_COST_PAID;
            flows.add(CashFlow.of(
                terms.disbursementDate(), 0, fee.amount().atPresentationScale(), kind));
        }
        return flows;
    }

    /**
     * The gross carrying amount at initial recognition: the amount advanced less
     * the net integral fee.
     *
     * <p>Case 1: 1,000,000 advanced less 5,000 net fee income is 995,000, which is
     * also the net cash outflow at inception. Where the net fee is income the
     * asset is recorded below par and accretes back up, which is why the EIR
     * exceeds the contractual rate (invariant INV-2).
     */
    static Money initialCarryingAmount(Money amountAdvanced, Money netIntegralFee) {
        return amountAdvanced.minus(netIntegralFee).atPresentationScale();
    }

    /** A run of equal instalments over an inclusive period range. */
    static List<CashFlow> instalmentLeg(
        ContractTerms terms, Money instalment, int fromPeriod, int toPeriod, FlowKind kind) {

        List<CashFlow> flows = new ArrayList<>();
        for (int period = fromPeriod; period <= toPeriod; period++) {
            flows.add(CashFlow.of(terms.dueDate(period), period, instalment, kind));
        }
        return flows;
    }

    /**
     * Assembles both legs, checks the convention and computes the result.
     *
     * @param behaviouralLife whether this shape admits behavioural truncation of
     *                        the contractual leg; false where there is nothing to
     *                        truncate, as on a single-flow discount instrument
     */
    static ProjectionResult assemble(
        ContractTerms terms,
        Money amountAdvanced,
        List<FeePosting> fees,
        List<CashFlow> futureFlows,
        boolean behaviouralLife) {

        List<CashFlow> all = new ArrayList<>(inceptionLeg(terms, amountAdvanced, fees));
        all.addAll(futureFlows);
        FlowVector contractual = FlowVector.of(terms.disbursementDate(), terms.currency(), all)
            .requireNoContingentFlows();
        boolean truncate = behaviouralLife && !terms.expectedLifeEqualsContractual();
        FlowVector expected = truncate ? expectedLeg(terms, contractual, amountAdvanced) : contractual;
        expected.requireNoContingentFlows();
        TimeConvention convention = convention(terms, contractual, expected);
        Money carryingAmount = initialCarryingAmount(
            amountAdvanced, netIntegralFee(fees, terms.currency()));
        return ProjectionResult.of(contractual, expected, carryingAmount, convention, !truncate);
    }

    /**
     * The expected leg: the contractual leg truncated at expected life, with the
     * balance outstanding at that date carried as an expected prepayment.
     *
     * <p>This is the single highest-leverage assumption in the model. Compressing
     * assumed life on a 20-year mortgage to 8 years multiplies year-one fee
     * recognition by 3.73 times — more than the inverse of the life ratio, because
     * declining-balance amortisation front-loads on top of the shorter horizon.
     * That is why a curve change is governed as a policy change with a mandatory
     * impact preview and not as a parameter update.
     *
     * <p>The prepayment is the <em>contractual</em> balance at that date, so the
     * expected leg differs from the contractual leg in timing only. Expected
     * credit losses are excluded for every non-POCI instrument (ACPIR 51); POCI is
     * the sole exception and its credit-adjusted flows are supplied rather than
     * derived here.
     *
     * @param openingBalance the contractual balance at inception — the first
     *     amount advanced, which on a tranched facility is the first draw and not
     *     the sanctioned limit
     */
    static FlowVector expectedLeg(ContractTerms terms, FlowVector contractual, Money openingBalance) {
        int life = terms.expectedLifePeriods();
        Money outstanding = ContractualBalance.presentedAfter(
            openingBalance, terms.periodicRate(), contractual, life);
        List<CashFlow> kept = new ArrayList<>();
        for (CashFlow flow : contractual.flows()) {
            if (flow.periodIndex() <= life) {
                kept.add(flow);
            }
        }
        if (outstanding.isPositive()) {
            kept.add(CashFlow.of(terms.dueDate(life), life, outstanding, FlowKind.EXPECTED_PREPAYMENT));
        }
        return FlowVector.of(contractual.anchorDate(), contractual.currency(), kept);
    }

    /**
     * The convention both legs license.
     *
     * <p>Periodic indexing is taken only where <em>both</em> legs pass the check.
     * The two legs are discounted by different consumers — the EIR is solved over
     * the expected leg, the 10% test and the catch-up run over the contractual one
     * — and letting them carry different conventions would put a convention
     * difference inside a comparison that is supposed to isolate a cash-flow
     * difference.
     */
    static TimeConvention convention(ContractTerms terms, FlowVector contractual, FlowVector expected) {
        ConventionSelector.Choice onContractual =
            ConventionSelector.choose(contractual, terms.periodsPerYear(), terms.dayCount());
        if (contractual == expected) {
            return onContractual.convention();
        }
        ConventionSelector.Choice onExpected =
            ConventionSelector.choose(expected, terms.periodsPerYear(), terms.dayCount());
        if (onContractual.periodicIndexEligible() && onExpected.periodicIndexEligible()) {
            return onContractual.convention();
        }
        return new TimeConvention.ActualDate(terms.dayCount());
    }

    /**
     * The rate to use with a convention. See
     * {@link ConventionSelector#rateUnder} for why the pairing has to be made
     * explicit; this is the unrounded form, for arithmetic internal to a
     * projection.
     */
    static BigDecimal rateUnder(TimeConvention convention, Rate rate) {
        return ConventionSelector.rateValueUnder(convention, rate);
    }
}
