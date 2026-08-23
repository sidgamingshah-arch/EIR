package com.crisil.eir.calc.projection;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.calc.amort.InvariantChecks;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.TimeConvention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a projector produces: two legs, the amount the rate will be solved to, the
 * time convention the vector licenses, and the invariant results the projection
 * itself can assert.
 *
 * <p>Both legs are emitted always (FR-301):
 *
 * <ul>
 *   <li><b>contractual</b> — the face schedule. Reconciles to the core banking
 *       system, drives the contractual interest leg and the 10% test.
 *   <li><b>expected</b> — contractual adjusted for behavioural assumptions.
 *       Drives EIR determination.
 * </ul>
 *
 * <p>Where policy sets expected life to contractual the two coincide, and
 * {@link #expectedEqualsContractualByPolicy()} records that this was a
 * <em>policy choice</em>. "We used contractual life" and "we never considered
 * life" produce identical numbers and very different audit outcomes, so the flag
 * exists to keep the two distinguishable in the computation record.
 *
 * <p><b>IC-1 is computed here, not supplied.</b> The constructor discounts
 * nothing: it compares the initial carrying amount the projector derived from the
 * contract terms against the net cash flow at inception in the vector it built.
 * Those are two independent routes to one number, and where they disagree either a
 * fee has been misclassified or a non-cash item has entered the vector. Computing
 * it centrally means no projector can omit it, and any IC-1 result passed in is
 * discarded in favour of the freshly computed one.
 *
 * <p>The comparison delegates to {@link InvariantChecks#initialCarryingAmount},
 * which is the engine's single statement of IC-1, rather than restating it. The
 * two had drifted apart while the packages were written in parallel: a magnitude
 * comparison here and a signed one there agreed on every asset and disagreed on
 * every liability. Signed is the one that is right, because
 * {@code -(net at inception)} is the figure the solver takes as its target
 * (SolveRequest.inceptionTarget) and the figure the amortisation engine opens its
 * roll-forward with, and a carrying amount that does not match those is not a
 * carrying amount this engine can use. On the liability side that number is
 * negative, and it has to be: the roll-forward is
 * {@code closing = opening + interest - cash}, so a liability raised is carried
 * negative, accretes negative finance cost and is cleared to zero by negative
 * payments. Stating it positive instead reverses the roll and grows the balance
 * without limit.
 *
 * @param contractual  the face schedule
 * @param expected     the behaviourally adjusted schedule; may be the same vector
 * @param initialCarryingAmount GCA at initial recognition, in the ledger's signed
 *                     convention: {@code -(net cash flow at inception)}, so
 *                     positive for an asset advanced and negative for a liability
 *                     raised
 * @param recommendedConvention the convention whose precondition this vector was
 *                     checked against — a recommendation the solver may override
 *                     only towards actual dating, never away from it
 * @param expectedEqualsContractualByPolicy whether the legs coincide by election
 * @param invariants   IC-1 first, then whatever the projector asserted
 */
public record ProjectionResult(
    FlowVector contractual,
    FlowVector expected,
    Money initialCarryingAmount,
    TimeConvention recommendedConvention,
    boolean expectedEqualsContractualByPolicy,
    List<InvariantResult> invariants) {

    public ProjectionResult {
        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(initialCarryingAmount, "initialCarryingAmount");
        Objects.requireNonNull(recommendedConvention, "recommendedConvention");
        Objects.requireNonNull(invariants, "invariants");
        contractual.requireNoContingentFlows();
        expected.requireNoContingentFlows();
        if (!contractual.currency().equals(expected.currency())) {
            throw new IllegalArgumentException(
                "contractual leg is " + contractual.currency().getCurrencyCode()
                    + " and expected leg is " + expected.currency().getCurrencyCode());
        }
        if (!initialCarryingAmount.currency().equals(contractual.currency())) {
            throw new IllegalArgumentException(
                "initialCarryingAmount is " + initialCarryingAmount.currency().getCurrencyCode()
                    + " and the flow vector is " + contractual.currency().getCurrencyCode());
        }
        List<InvariantResult> assembled = new ArrayList<>();
        assembled.add(InvariantChecks.initialCarryingAmount(
            initialCarryingAmount, Discounting.netAtInception(contractual)));
        for (InvariantResult supplied : invariants) {
            if (supplied.id() != InvariantId.IC_1) {
                assembled.add(supplied);
            }
        }
        invariants = List.copyOf(assembled);
    }

    /** A projection with no invariants beyond the IC-1 the constructor computes. */
    public static ProjectionResult of(
        FlowVector contractual,
        FlowVector expected,
        Money initialCarryingAmount,
        TimeConvention recommendedConvention,
        boolean expectedEqualsContractualByPolicy) {

        return new ProjectionResult(contractual, expected, initialCarryingAmount, recommendedConvention,
            expectedEqualsContractualByPolicy, List.of());
    }

    /** The IC-1 result, which is always present. */
    public InvariantResult initialRecognitionCheck() {
        for (InvariantResult result : invariants) {
            if (result.id() == InvariantId.IC_1) {
                return result;
            }
        }
        throw new IllegalStateException("IC-1 is computed at construction and cannot be absent");
    }

    /** The signed net cash flow on the anchor date. */
    public Money netCashAtInception() {
        return Discounting.netAtInception(contractual);
    }

    public boolean allInvariantsSatisfied() {
        return invariants.stream().allMatch(InvariantResult::satisfied);
    }

    /**
     * Throws on the first breach.
     *
     * <p>An invariant breach is a control exception, not a rounding nuisance:
     * nothing downstream may continue with a substituted value. Callers that
     * report rather than block read {@link #invariants()} instead.
     */
    public ProjectionResult requireInvariantsSatisfied() {
        for (InvariantResult result : invariants) {
            result.orThrow();
        }
        return this;
    }

    /**
     * Whether every future flow is synthetic — a notional redemption or an
     * expected prepayment the engine inserted, with no contractual receipt behind
     * it.
     *
     * <p>True for a revolving facility projected under the ACPIR 54
     * approximation, where no contractual repayment schedule exists to project.
     * It is the machine-readable form of "this is an approximation, not an EIR",
     * and a caller that solves a rate over such a vector must label the output as
     * an approximation. A B5.4.4-truncated vector is not synthetic in this sense:
     * it carries real instalments up to the reset and one synthetic flow at it.
     */
    public boolean futureLegIsWhollySynthetic() {
        List<CashFlow> future = contractual.future();
        if (future.isEmpty()) {
            return true;
        }
        for (CashFlow flow : future) {
            if (flow.kind() != FlowKind.NOTIONAL_REDEMPTION && flow.kind() != FlowKind.EXPECTED_PREPAYMENT) {
                return false;
            }
        }
        return true;
    }
}
