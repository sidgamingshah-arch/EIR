package com.crisil.eir.policy.close;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * One figure of a closed period that does not say what it published — the unit CL-1 counts.
 *
 * <p>Three shapes, and all three are mutations of a closed period. The middle one is the shape the
 * requirement is usually read as; the other two are how the same damage arrives through a delete or
 * an insert, and a control that only compared amounts present on both sides would miss them
 * entirely — a deleted row and a row that never existed look identical if you only join on keys
 * both statements have.
 *
 * @param kind      which of the three shapes
 * @param figureKey the figure
 * @param published what the period published, or null for {@link Kind#ADDED}
 * @param current   what the ledger says now, or null for {@link Kind#REMOVED}
 */
public record FigureMutation(
    Kind kind, String figureKey, Money published, Money current) {

    /** The three ways a closed period's figure can stop agreeing with what it published. */
    public enum Kind {

        /**
         * The figure exists on both sides at different amounts — the {@code UPDATE}.
         *
         * <p>The failure CL-1's javadoc calls the one that looks like diligence: somebody finds an
         * error in a closed period and fixes it, in place, because that is what fixing means
         * everywhere else. A replay of the corrected data is internally consistent, so DT-1 need
         * not notice; what is lost is the correspondence between what was published and what the
         * ledger says was published.
         */
        CHANGED,

        /**
         * The figure was published and is now absent — the {@code DELETE}, or an archive step that
         * dropped rows it was meant to copy.
         *
         * <p>Worse than a changed figure in one respect: a reader of the current statement has no
         * indication anything is missing, whereas a changed figure at least still ties to
         * something.
         */
        REMOVED,

        /**
         * A figure the period never published has appeared in it — the {@code INSERT}.
         *
         * <p>The shape a re-run takes when it writes into a closed partition instead of the open
         * one, and the shape a backdated amendment takes when its {@code period_id} is derived from
         * business time without checking whether that period is closed (04 § 5 requires the
         * amendment to produce a restatement in the <em>current</em> period).
         */
        ADDED
    }

    public FigureMutation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(figureKey, "figureKey");
        switch (kind) {
            case CHANGED -> {
                Objects.requireNonNull(published, "published");
                Objects.requireNonNull(current, "current");
            }
            case REMOVED -> {
                Objects.requireNonNull(published, "published");
                if (current != null) {
                    throw new IllegalArgumentException(
                        "figure " + figureKey + " is REMOVED but names a current amount " + current);
                }
            }
            case ADDED -> {
                Objects.requireNonNull(current, "current");
                if (published != null) {
                    throw new IllegalArgumentException(
                        "figure " + figureKey + " is ADDED but names a published amount "
                            + published);
                }
            }
            default -> throw new IllegalStateException("unhandled mutation kind " + kind);
        }
    }

    /** A figure whose amount moved. */
    public static FigureMutation changed(String figureKey, Money published, Money current) {
        return new FigureMutation(Kind.CHANGED, figureKey, published, current);
    }

    /** A published figure that is gone. */
    public static FigureMutation removed(String figureKey, Money published) {
        return new FigureMutation(Kind.REMOVED, figureKey, published, null);
    }

    /** A figure that appeared in a period that never published it. */
    public static FigureMutation added(String figureKey, Money current) {
        return new FigureMutation(Kind.ADDED, figureKey, null, current);
    }

    /** One audit line: the figure, the shape, and both amounts where both exist. */
    public String describe() {
        return switch (kind) {
            case CHANGED -> figureKey + " CHANGED from " + published.atPresentationScale()
                + " to " + current.atPresentationScale();
            case REMOVED -> figureKey + " REMOVED (published " + published.atPresentationScale()
                + ")";
            case ADDED -> figureKey + " ADDED (" + current.atPresentationScale()
                + ", never published)";
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
