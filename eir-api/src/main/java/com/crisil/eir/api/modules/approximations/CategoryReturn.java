package com.crisil.eir.api.modules.approximations;

import com.crisil.eir.domain.InvariantResult;
import java.util.List;
import java.util.Objects;

/**
 * What the register found for one {@link ApproximationCategory} — <b>or that it could not look</b>.
 *
 * <p><b>This type exists for one distinction and would not otherwise exist at all.</b> An empty
 * list means "no such shortcut is in force". A missing source means "nobody knows". Rendered the
 * same way — as {@code "rows": []} — the second reads as the first, and a report that answers
 * "no approximations in force" when it means "I have no way to tell" is worse than no report,
 * because the reader stops looking. 06 § 7's whole argument for this endpoint is that "making the
 * shortcuts visible is what keeps them defensible", and an invisible gap defends nothing.
 *
 * <p>So {@link Status} has three values, not two, and the constructor refuses every combination
 * that would let one masquerade as another: {@link Status#NOT_AVAILABLE} <em>must</em> carry a
 * reason and <em>must</em> carry no rows; {@link Status#NONE_IN_FORCE} must carry neither rows nor
 * a reason; {@link Status#REPORTED} must carry rows and no reason. The refusal is at construction
 * because a malformed {@code CategoryReturn} is a defect in this package, not a data condition —
 * there is no run of the engine that can produce one.
 *
 * @param category  the category reported
 * @param status    whether the register looked, and what it found
 * @param rows      the shortcuts found; empty for both non-{@link Status#REPORTED} statuses
 * @param gap       why the register could not look; non-blank only for {@link Status#NOT_AVAILABLE}
 * @param invariant the invariant result over this category, or null where the category has none
 * @param notes     facts about the category that are not themselves shortcuts — an evidence
 *                  record that matches no subject, a threshold nobody approved. Published rather
 *                  than dropped: an orphaned back-test usually means a join key is wrong, and a
 *                  wrong join key makes a properly evidenced pool read as un-evidenced, which is
 *                  a false positive in the one direction this report must not produce quietly
 */
public record CategoryReturn(
    ApproximationCategory category,
    CategoryReturn.Status status,
    List<ApproximationRow> rows,
    String gap,
    InvariantResult invariant,
    List<String> notes) {

    /** Whether the register looked at this category, and what came back. */
    public enum Status {

        /** The source answered and named at least one shortcut. Read {@code rows}. */
        REPORTED("the source answered and this category's shortcuts are listed"),

        /**
         * The source answered and there is nothing to report.
         *
         * <p>A positive claim, and the only status that licenses the reading "no such shortcut is
         * in force in this period". It is available only where a source was actually consulted.
         */
        NONE_IN_FORCE("the source answered and no shortcut of this kind is in force"),

        /**
         * No source. <b>A gap, not a nil return.</b>
         *
         * <p>This status makes no claim at all about whether the shortcut is in force. It says
         * the engine holds nothing this register can read, names what would fill it, and makes
         * the whole report {@code complete: false}. Treating it as "none in force" is the exact
         * misreading the three-valued status exists to prevent.
         */
        NOT_AVAILABLE("no source — this is a GAP and asserts nothing about whether the shortcut"
            + " is in force");

        private final String meaning;

        Status(String meaning) {
            this.meaning = meaning;
        }

        /** Rendered beside the status on the wire, so the distinction survives the JSON. */
        public String meaning() {
            return meaning;
        }
    }

    public CategoryReturn {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(notes, "notes");
        rows = List.copyOf(rows);
        notes = List.copyOf(notes);
        boolean hasGap = gap != null && !gap.isBlank();
        switch (status) {
            case REPORTED -> {
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException(
                        category + " is REPORTED with no rows; an empty REPORTED block is"
                            + " indistinguishable from NONE_IN_FORCE, and the two are different"
                            + " claims");
                }
                if (hasGap) {
                    throw new IllegalArgumentException(
                        category + " is REPORTED and also carries a gap: " + gap);
                }
            }
            case NONE_IN_FORCE -> {
                if (!rows.isEmpty()) {
                    throw new IllegalArgumentException(
                        category + " claims NONE_IN_FORCE while carrying " + rows.size()
                            + " rows");
                }
                if (hasGap) {
                    throw new IllegalArgumentException(
                        category + " claims NONE_IN_FORCE — a positive statement that no such"
                            + " shortcut is in force — while also reporting the gap \"" + gap
                            + "\"; a category with a gap has not established that nothing is in"
                            + " force, and publishing both lets the weaker claim be read as the"
                            + " stronger one");
                }
            }
            case NOT_AVAILABLE -> {
                if (!rows.isEmpty()) {
                    throw new IllegalArgumentException(
                        category + " is NOT_AVAILABLE while carrying " + rows.size() + " rows");
                }
                if (!hasGap) {
                    // The single most important check in this package. A NOT_AVAILABLE with no
                    // reason renders as an empty list with a status nobody reads, which is the
                    // silent omission 06 § 7 says this endpoint exists to prevent.
                    throw new IllegalArgumentException(
                        category + " is NOT_AVAILABLE with no reason given; a gap that cannot"
                            + " say what is missing renders as an empty list, and an empty list"
                            + " reads as \"none in force\" — which is the failure this endpoint"
                            + " exists to prevent");
                }
            }
            default -> throw new IllegalStateException("unhandled status " + status);
        }
    }

    /** Rows found, empty for both non-reported statuses. */
    public static CategoryReturn reported(
        ApproximationCategory category,
        List<ApproximationRow> rows,
        InvariantResult invariant,
        List<String> notes) {
        return new CategoryReturn(category, Status.REPORTED, rows, null, invariant, notes);
    }

    /** The source answered and there is genuinely nothing in force. */
    public static CategoryReturn noneInForce(
        ApproximationCategory category, InvariantResult invariant, List<String> notes) {
        return new CategoryReturn(
            category, Status.NONE_IN_FORCE, List.of(), null, invariant, notes);
    }

    /**
     * No source. The reason is mandatory and is combined with the category's own statement of
     * what would populate it, so the gap is actionable rather than an apology.
     *
     * <p><b>The blank check is here and not only in the constructor, and it has to be.</b> This
     * factory appends the category's {@code wouldBePopulatedBy} sentence, so a blank reason
     * produces a non-blank gap and sails past the constructor's guard — which would leave a
     * category labelled NOT_AVAILABLE whose stated reason is nothing but boilerplate every other
     * gap also carries. That is a control that cannot fail on the only path anybody uses, so the
     * refusal is at the door: found by writing the test for the constructor's guard and
     * discovering it could not be reached through this method.
     */
    public static CategoryReturn notAvailable(ApproximationCategory category, String reason) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(category, "category");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                category + " is NOT_AVAILABLE with no reason given; a gap that cannot say what is"
                    + " missing renders as an empty list, and an empty list reads as \"none in"
                    + " force\" — which is the failure this endpoint exists to prevent");
        }
        return new CategoryReturn(category, Status.NOT_AVAILABLE, List.of(),
            reason.strip() + " Would be populated by: " + category.wouldBePopulatedBy() + ".",
            null, List.of());
    }

    /** Whether this block leaves the register unable to claim coverage of its category. */
    public boolean isGap() {
        return status == Status.NOT_AVAILABLE;
    }

    /** Shortcuts actually applied in the period — what the book is measured on. */
    public long inForceCount() {
        return rows.stream().filter(ApproximationRow::inForce).count();
    }

    /**
     * Shortcuts proposed, whether or not a gate then refused them.
     *
     * <p>Larger than {@link #inForceCount()} exactly where a gate demoted a population, which for
     * Tier 3 is every unevidenced one. Published beside the in-force count because the difference
     * between the two is the amount of work the gates did this period.
     */
    public long soughtCount() {
        return rows.stream().filter(ApproximationRow::sought).count();
    }

    /** Shortcuts sought with nothing on file to defend them — FR-809's finding. */
    public long undocumentedCount() {
        return rows.stream().filter(ApproximationRow::undocumented).count();
    }
}
