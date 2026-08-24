package com.crisil.eir.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The outcome of asserting one invariant, retained so that a run's invariant
 * results are reportable rather than merely thrown.
 *
 * @param id        which invariant
 * @param satisfied whether it held
 * @param detail    human-readable statement of what was compared
 * @param deviation the signed size of the breach, or zero when satisfied
 */
public record InvariantResult(InvariantId id, boolean satisfied, String detail, BigDecimal deviation) {

    public InvariantResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(deviation, "deviation");
    }

    public static InvariantResult pass(InvariantId id, String detail) {
        return new InvariantResult(id, true, detail, BigDecimal.ZERO);
    }

    public static InvariantResult fail(InvariantId id, String detail, BigDecimal deviation) {
        return new InvariantResult(id, false, detail, deviation);
    }

    /**
     * Asserts an expected equality of money amounts at presentation scale.
     *
     * <p>Comparison is at presentation scale on purpose: the invariant is a claim
     * about the figures that get published, and a working-precision comparison
     * would fail on a difference no reader could ever see.
     *
     * <p><b>The difference is reduced once, not two reduced operands compared.</b>
     * That is the general rule of section 1.3 — round where a figure is persisted,
     * once — and it is the rule the reference cases themselves follow: at period 23
     * of reference case 1 the working difference 19.4966 publishes as 19.50, while
     * the two published balances differenced give 19.51, and the fixture states
     * 19.50. {@code TwoLegRow.presentedUnamortisedFee} makes the same choice for the
     * same reason.
     *
     * <p>Rounding both operands first rounds twice, and the error it admits is
     * unbounded below. On a 30-year quarterly exposure of 20,036,296.57 the two legs
     * came to 242,103,892.5032 and 242,103,892.5063 — a difference of thirty-one ten
     * thousandths of a rupee, three thousandths of a paise — and because the pair
     * straddles a rounding boundary the operands present as 242,103,892.50 and
     * 242,103,892.51 and INV-1 reported a one-paise breach. In production that is a
     * control exception blocking a period close on a contract where nothing is wrong,
     * and no bound on the true difference makes it go away: a difference of 1e-20
     * breaches just as readily. Reducing once bounds the report honestly — a breach
     * is raised only where the quantities really do differ by at least half a minor
     * unit, which is a difference and not a rounding artefact.
     *
     * <p>A currency mismatch between the two amounts raises
     * {@link IllegalArgumentException} from {@link Money#minus} rather than being
     * reported as a breach. That is a defect in the caller, not a fact about the
     * book: a deviation measured across two currencies would be a figure nobody
     * could reconcile, and a loud failure naming the mismatch is more use than a
     * breach report that cannot be actioned. Note that it is therefore <em>not</em>
     * caught by a caller that wraps an invariant set in
     * {@link InvariantBreachException} handling.
     */
    public static InvariantResult ofMoney(InvariantId id, String detail, Money expected, Money actual) {
        Money difference = actual.minus(expected).atPresentationScale();
        if (difference.isZero()) {
            return pass(id, detail + " (" + actual.atPresentationScale() + ")");
        }
        return fail(id,
            detail + " — expected " + expected.atPresentationScale()
                + ", got " + actual.atPresentationScale(),
            difference.amount());
    }

    /** Throws if this result is a breach. */
    public InvariantResult orThrow() {
        if (!satisfied) {
            throw new InvariantBreachException(this);
        }
        return this;
    }
}
