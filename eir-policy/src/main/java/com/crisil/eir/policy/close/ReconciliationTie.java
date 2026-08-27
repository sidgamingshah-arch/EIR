package com.crisil.eir.policy.close;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * One of step 6's reconciliations, as presented to the close: two sides and where they came from.
 *
 * <p><b>Two sides, not a boolean.</b> A {@code ReconciliationTie(scope, tied)} would be a control
 * that cannot fail in any way the gate could detect — whoever computed the boolean decided the
 * answer, and the close would be recording a claim rather than checking one. Holding both sides
 * means the residual is derived here, from figures a caller cannot make agree by asserting that
 * they do.
 *
 * <p><b>The residual is reduced once, not two rounded operands differenced.</b> That is section
 * 1.3's rule and {@code InvariantResult.ofMoney} sets out the measured case at length: two legs of
 * 242,103,892.5032 and 242,103,892.5063 — three thousandths of a paise apart — present as
 * ...892.50 and ...892.51 and produce a one-paise break on figures that agree. In production that
 * is a close blocked on a contract where nothing is wrong, and a hard gate that blocks closes for
 * nothing is a hard gate that gets argued down to a soft one.
 *
 * <p><b>No tolerance parameter.</b> A tie either nets to zero at the scale the figures are
 * published at, or somebody has to explain it. A configurable tolerance on a reconciliation is how
 * a systematic break gets carried forward for four quarters at 40% of the tolerance each time.
 *
 * @param scope   which of step 6's four reconciliations this is
 * @param expected the side taken as authority — the GL balance, the CBS figure, the pre-floor ECL
 * @param actual   the side being reconciled to it — the sub-ledger total, the engine's leg
 * @param source   what produced the two sides, for the audit sentence; never blank, because a
 *                 residual whose provenance is unrecorded cannot be investigated
 */
public record ReconciliationTie(
    ReconciliationScope scope, Money expected, Money actual, String source) {

    public ReconciliationTie {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                "reconciliation " + scope + " names no source; a residual nobody can trace to two"
                    + " systems cannot be investigated, only accepted");
        }
        if (!expected.currency().equals(actual.currency())) {
            // A throw, not a break: a residual measured across two currencies is a figure nobody
            // can reconcile, and the mismatch is a defect in the caller rather than a fact about
            // the book. Money.minus takes the same position for the same reason.
            throw new IllegalArgumentException(
                "reconciliation " + scope + " compares " + expected.currency() + " against "
                    + actual.currency() + "; a cross-currency residual reconciles nothing");
        }
    }

    /**
     * {@code actual - expected}, at presentation scale, signed.
     *
     * <p>Signed because the direction is the first thing an investigator asks: a sub-ledger above
     * the GL and one below it have different causes. Note that the close's own aggregate deviation
     * must <em>not</em> be a sum of these — see {@link #absoluteResidual()}.
     */
    public Money residual() {
        return actual.minus(expected).atPresentationScale();
    }

    /**
     * The unsigned size of the break.
     *
     * <p>What an aggregate over several reconciliations must be built from. A signed sum lets a
     * sub-ledger 4,00,000 over the GL and a contractual leg 4,00,000 under the CBS net to zero and
     * report as tied — two breaks in opposite directions netting to a pass, which is the specific
     * arithmetic every deviation in this engine is written to avoid.
     */
    public Money absoluteResidual() {
        return residual().abs();
    }

    /**
     * Whether the two sides agree at the scale the figures are published at.
     *
     * <p>What input makes this false: any two sides that differ by at least half a minor unit —
     * a GL balance of 12,00,000.00 against a sub-ledger total of 12,00,000.01. The sides are
     * inputs, so this is decidable from data and not from how the record was constructed.
     */
    public boolean isTied() {
        return residual().isZero();
    }

    /** One audit sentence naming the scope, the two sides and the residual. */
    public String describe() {
        return scope.label() + " (" + source + "): expected " + expected.atPresentationScale()
            + ", got " + actual.atPresentationScale()
            + (isTied() ? " — tied" : " — residual " + residual() + ", asserted by "
                + scope.assertedBy());
    }

    @Override
    public String toString() {
        return describe();
    }
}
