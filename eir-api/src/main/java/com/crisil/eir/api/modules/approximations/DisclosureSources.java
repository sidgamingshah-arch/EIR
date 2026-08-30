package com.crisil.eir.api.modules.approximations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Where {@link IndAs107Extract} reads each figure-bearing section from.
 *
 * <p>The same two-level emptiness as {@link ApproximationSources}, for the same reason. A
 * disclosure section rendered as an empty list of lines reads as "nil this period" — which for a
 * loss allowance reconciliation or an interest revenue line is a substantive and usually false
 * statement. So a source either answers, possibly with nothing, or says it has no data and why.
 *
 * <p>{@link DisclosureSection#MEASUREMENT_BASIS_AND_APPROXIMATIONS} is not asked of this
 * interface: it is fed by the FR-809 register. An implementation may return anything for it and
 * the extract will not call it.
 */
public interface DisclosureSources {

    /**
     * One line of a disclosure section.
     *
     * <p>{@code amount} is nullable because two of the sections this extract renders are prose —
     * the measurement basis and the approximations within it are judgements, not figures, and Ind
     * AS 1.122 asks for the judgements. A line with no amount is a narrative line, and rendering
     * it as {@code "amount": null} rather than as {@code "0.00"} is the difference between "this
     * line has no figure" and "this line's figure is nil".
     *
     * @param caption the line item, as it would appear in the note
     * @param amount  the figure, or null for a narrative line
     * @param note    the supporting sentence; never blank, because an undocumented disclosure
     *                line is what an auditor asks about first
     */
    record DisclosureLine(String caption, BigDecimal amount, String note) {
        public DisclosureLine {
            Objects.requireNonNull(caption, "caption");
            Objects.requireNonNull(note, "note");
            if (caption.isBlank()) {
                throw new IllegalArgumentException("a disclosure line names its line item");
            }
            if (note.isBlank()) {
                throw new IllegalArgumentException(
                    "disclosure line '" + caption + "' carries no supporting note; a figure in a"
                        + " note to the accounts with nothing said about it is the disclosure an"
                        + " auditor asks about first");
            }
        }

        /** A narrative line, with no figure. */
        public static DisclosureLine narrative(String caption, String note) {
            return new DisclosureLine(caption, null, note);
        }
    }

    /**
     * The lines for one section, or the reason there are none to be had.
     *
     * <p>Reuses {@link ApproximationSources.Answer} rather than declaring a second identical
     * type: the gap-versus-nil distinction is one idea and having two names for it would let the
     * two drift.
     */
    ApproximationSources.Answer<List<DisclosureLine>> section(
        DisclosureSection section, int periodId);
}
