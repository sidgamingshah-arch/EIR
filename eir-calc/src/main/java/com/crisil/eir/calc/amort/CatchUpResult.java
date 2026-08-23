package com.crisil.eir.calc.amort;

import com.crisil.eir.domain.InvariantBreachException;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a B5.4.6 restatement: the balance before, the balance after, the
 * difference that hits the P&amp;L, and the rate that did not move.
 *
 * <p>The rate appears twice on purpose. Recording the EIR before and after an
 * event whose defining property is that the EIR does <em>not</em> change looks
 * redundant, and it is the cheapest audit evidence available that the event was
 * routed as a catch-up and not quietly as a reset (invariant CU-1).
 *
 * @param eirBefore    the EIR in force before the event
 * @param eirAfter     the EIR persisted after it; must be bit-identical
 * @param gcaBefore    carrying amount before restatement
 * @param restatedGca  present value of the revised flows at {@code eirBefore}
 * @param catchUp      {@code restatedGca - gcaBefore}; positive income, negative a charge
 * @param invariants   CU-1 and CU-2
 */
public record CatchUpResult(
    Rate eirBefore,
    Rate eirAfter,
    Money gcaBefore,
    Money restatedGca,
    Money catchUp,
    List<InvariantResult> invariants) {

    public CatchUpResult {
        Objects.requireNonNull(eirBefore, "eirBefore");
        Objects.requireNonNull(eirAfter, "eirAfter");
        Objects.requireNonNull(gcaBefore, "gcaBefore");
        Objects.requireNonNull(restatedGca, "restatedGca");
        Objects.requireNonNull(catchUp, "catchUp");
        Objects.requireNonNull(invariants, "invariants");
        invariants = List.copyOf(invariants);
    }

    /** True where the restatement increases the carrying amount, so the catch-up is income. */
    public boolean isIncome() {
        return catchUp.isPositive();
    }

    /** True where the restatement reduces the carrying amount, as in reference case 3. */
    public boolean isCharge() {
        return catchUp.isNegative();
    }

    /** The catch-up as it is posted. */
    public Money presentedCatchUp() {
        return catchUp.atPresentationScale();
    }

    public List<InvariantResult> breaches() {
        return invariants.stream().filter(result -> !result.satisfied()).toList();
    }

    /**
     * Whether CU-1 and CU-2 both held.
     *
     * <p>The same reporting-rather-than-throwing shape as {@link
     * AmortisationResult#isClean()}, {@link TwoLegResult#isClean()} and {@link
     * Stage3Decomposition#breaches()}: a ten-million-contract run collects
     * breaches per contract and carries on (FR-905), so a caller needs to ask
     * without being thrown at. {@link #orThrow()} is the blocking form.
     */
    public boolean isClean() {
        return breaches().isEmpty();
    }

    /**
     * @throws InvariantBreachException on the first breach — which for CU-1 means
     *     the event was routed, or computed, as something other than a catch-up
     */
    public CatchUpResult orThrow() {
        for (InvariantResult result : invariants) {
            result.orThrow();
        }
        return this;
    }
}
