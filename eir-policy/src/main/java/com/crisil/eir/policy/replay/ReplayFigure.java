package com.crisil.eir.policy.replay;

import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One published figure, and the two comparisons a replay needs to keep apart.
 *
 * <h2>The trap this type exists to close</h2>
 *
 * <p>{@link Money#equals} compares by <em>numeric value</em> and ignores scale. Its own javadoc
 * says so and defends it: record default equality would use {@link BigDecimal#equals}, which is
 * scale-sensitive, and {@code 1.0 != 1.00} is a footgun in a type whose job is comparing amounts.
 * That is the right decision for accounting arithmetic and it is the wrong decision for DT-1.
 *
 * <p>FR-903 asks for a <b>bit-identical</b> replay. The artefact a period publishes is a rendered
 * figure — {@code 1.00} in a statement, in a disclosure, in an extract handed to an auditor. A
 * replay that produces {@code 1.0} has published something different. Nothing about it is
 * numerically wrong, which is exactly why it needs a control: every downstream numeric check
 * passes, {@code Money.equals} reports a match, and the difference surfaces as a diff on a file
 * somebody is comparing byte for byte at 2am ("Sampled closed period replayed and byte-compared",
 * control C-12).
 *
 * <p>A scale drift is not a hypothetical. {@code Money.atPresentationScale} is "the only place a
 * money value loses precision, and it is called once per persisted figure" — so a figure's scale
 * is a property of <em>where in the pipeline it was reduced</em>. A replay that reduces at a
 * different point, or that skips the reduction because the value was already round, produces the
 * same number at a different scale. So does a {@code BigDecimal.stripTrailingZeros()} added to
 * tidy up a log line.
 *
 * <h2>Which comparison, where, and why</h2>
 *
 * <ul>
 *   <li><b>VALUE questions use {@link BigDecimal#compareTo}.</b> "Is this the same amount of
 *       money?" — {@link #numericallyEqual}. Used only to <em>classify</em> a difference: a pair
 *       that differs and is numerically equal is a scale-only difference, which is a distinct
 *       diagnosis with a distinct remedy (find the rounding point that moved) from a pair whose
 *       value moved (find the arithmetic that changed).</li>
 *   <li><b>IDENTITY questions use the rendered decimal string.</b> "Did this replay publish the
 *       same figure?" — {@link #bitIdentical}. This is deliberately <em>not</em> {@code equals}
 *       on the {@code Money} record, and it is the direct counterpart of
 *       {@code eir-calc InvariantChecks.bitIdentical(Rate, Rate)}, which compares two persisted
 *       rates by {@code left.periodic().equals(right.periodic())} for the same reason stated the
 *       same way: {@code Rate.equals} compares numerically, "which is right for a value type and
 *       too permissive here".</li>
 * </ul>
 *
 * <h2>Why {@code toPlainString} rather than {@code BigDecimal.equals}</h2>
 *
 * <p>The brief sanctions either. They agree on every scale a persisted figure can have and part
 * on exactly one shape, so the choice is worth stating rather than leaving to whichever was
 * typed first.
 *
 * <p>{@link BigDecimal#equals} compares unscaled value and scale, so it separates
 * {@code 1E+2} (unscaled 1, scale -2) from {@code 100} (unscaled 100, scale 0). Both render as
 * {@code "100"}. {@code toPlainString} therefore calls them identical and {@code equals} does not.
 *
 * <p>{@code toPlainString} is the one that answers the question FR-903 asks. The control is
 * "byte-compared" against a <em>published artefact</em>, and two values that render to the same
 * characters published the same artefact whatever their internal scale. The stricter test would
 * raise a DT-1 breach on a pair no reader could distinguish, and DT-1 blocks a period close
 * (03 § 9) — a control exception on a contract where nothing is wrong is the failure mode
 * {@code InvariantResult.ofMoney} spends four paragraphs guarding against.
 *
 * <p>The divergence is also unreachable through the persistence layer: the figure columns are
 * {@code NUMERIC(24,6)} and {@code NUMERIC(20,12)}, which cannot hold a negative scale, so a
 * round-trip through the database normalises the one shape the two tests disagree about. The
 * choice matters only for a figure compared in memory before it is stored, and there the artefact
 * reading is the one that corresponds to the requirement.
 *
 * @param key    what this figure is — contract, period and figure name, as the published extract
 *               names it; the join key of the comparison and never itself compared for value
 * @param amount the figure as published, <b>at the scale it was published at</b>
 */
public record ReplayFigure(String key, Money amount) {

    public ReplayFigure {
        Objects.requireNonNull(amount, "amount");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                "a replay figure needs a key; an unnamed figure cannot be matched to the"
                    + " published one it is supposed to reproduce");
        }
        key = key.strip();
    }

    public static ReplayFigure of(String key, Money amount) {
        return new ReplayFigure(key, amount);
    }

    /**
     * Whether two amounts are the same published figure — currency and rendered decimal both.
     *
     * <p>The IDENTITY question. Scale-sensitive by construction: {@code 1.0} and {@code 1.00}
     * render to {@code "1.0"} and {@code "1.00"} and are not the same figure.
     *
     * <p>Currency is compared by code rather than by {@link java.util.Currency} identity for no
     * reason other than that the message it produces names something a reader recognises;
     * {@code Currency} instances are interned, so the two tests never disagree.
     */
    public static boolean bitIdentical(Money left, Money right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.currency().getCurrencyCode().equals(right.currency().getCurrencyCode())
            && left.amount().toPlainString().equals(right.amount().toPlainString());
    }

    /**
     * Whether two amounts are the same amount of money, scale disregarded.
     *
     * <p>The VALUE question, and {@link BigDecimal#compareTo} is the only correct way to ask it.
     * Used here solely to classify a difference that {@link #bitIdentical} has already found:
     * numerically equal means the value survived and the presentation did not, which is a
     * different defect with a different fix.
     *
     * <p>This is what {@code Money.equals} answers, and it is answered here directly rather than
     * by calling it — partly so that this file states which comparison it is making, and partly so
     * that a future change to {@code Money.equals} cannot quietly redefine what DT-1 means.
     */
    public static boolean numericallyEqual(Money left, Money right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.currency().getCurrencyCode().equals(right.currency().getCurrencyCode())
            && left.amount().compareTo(right.amount()) == 0;
    }

    /**
     * How an amount renders in a published artefact: {@code "INR 1.00"}.
     *
     * <p>{@code toPlainString} and not {@code toString}, and not {@code Money.toString} either.
     * {@code BigDecimal.toString} emits scientific notation for a sufficiently negative scale, so a
     * discrepancy report built on it could render the two sides of a scale-only breach in different
     * notations and leave a reader unable to see what differed. This is the same rendering
     * {@link #bitIdentical} compares, which is the point: what the report shows is what the control
     * compared.
     */
    public static String render(Money amount) {
        Objects.requireNonNull(amount, "amount");
        return amount.currency().getCurrencyCode() + " " + amount.amount().toPlainString();
    }

    /** How this figure renders in a published artefact: {@code "INR 1.00"}. */
    public String rendered() {
        return render(amount);
    }

    /** The decimal places this figure was published at — the quantity a scale drift moves. */
    public int publishedScale() {
        return amount.amount().scale();
    }
}
