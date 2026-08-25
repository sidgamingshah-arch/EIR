package com.crisil.eir.policy.fee;

import com.crisil.eir.policy.exception.ExceptionCategory;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of putting one posting's {@code cost_function} attribute to the ACPIR 53
 * question: either a resolved {@link CostFunction}, or a named reason the posting cannot
 * be placed on either side of the selling-versus-appraisal line.
 *
 * <p><b>Why a value and not an exception.</b> {@code FeePosting}'s constructor throws on an
 * integral cost with no {@code costFunction}, and that is right for a boundary filter — an
 * unattributed cost must not be constructible. But a ten-million-contract run has to collect
 * what it could not classify and carry on (FR-905), and the engine's standing rule is that a
 * data condition is reported rather than thrown. So this type is the queue-shaped counterpart
 * of that throw: same requirement (FR-203), same conclusion, delivered as something a batch
 * can accumulate and an exception report can print.
 *
 * <p><b>Cause and category are both carried, deliberately.</b> {@link Cause} says precisely
 * what went wrong; {@link #exception()} says which of the ten existing exception-queue
 * categories (04 § 3) it currently maps to. They are not one field because the mapping is
 * lossy today: all four rejection causes land on
 * {@link ExceptionCategory#MISSING_COST_FUNCTION}, which is defined as "an {@code INTEGRAL}
 * cost posting with no {@code cost_function} attribute" and so is a literal fit for exactly one
 * of them. Keeping the cause separate means the day the queue grows a category for an
 * <em>ambiguous</em> attribute (see {@link Cause#INDETERMINATE}), the remap is a change to one
 * switch and not an archaeology exercise over free-text detail strings. The exception queue
 * itself is another unit's; this type does not presume to extend its vocabulary.
 *
 * @param function  the resolved cost function, or null where the posting was rejected
 * @param cause     precisely what was decided, including {@link Cause#ACCEPTED}
 * @param exception the queue category to raise, or null where the posting was accepted
 * @param detail    a statement naming the defect, for the exception-queue entry
 */
public record CostFunctionResolution(
    CostFunction function,
    Cause cause,
    ExceptionCategory exception,
    String detail) {

    /**
     * Precisely why a {@code cost_function} attribute did or did not answer ACPIR 53.
     *
     * <p>Four rejection causes rather than one flag, because they are four different
     * remediations and land on four different desks. An absent attribute is a feed gap; a
     * value outside the vocabulary is a mapping bug in the ingestion adapter; an indeterminate
     * value is the months-long HR and cost-centre re-attribution of 08 § 0; and a cost
     * function ACPIR 53 excludes, arriving on a posting the rule set called {@code INTEGRAL},
     * is a defect in the rule set. An exception report that says only "cost function problem"
     * routes all four to whoever reads the queue first.
     */
    public enum Cause {

        /** The attribute named a cost function the engine recognises. */
        ACCEPTED,

        /**
         * Null, empty, or whitespace. FR-203's literal limb: "Reject a posting whose
         * {@code cost_function} attribute is absent." The absence is not neutral — defaulting
         * it to capitalisable overstates the asset and defers cost, defaulting it to excluded
         * expenses a genuine selling-agent commission in year one, and the direction of the
         * error is invisible either way.
         */
        ABSENT,

        /**
         * Present, but not one of the four values 04 § 2.5 permits. A defect in whatever
         * populated it rather than in the accounting: an ingestion adapter emitting
         * {@code "DSA"} or {@code "SALES"} has silently invented a fifth cost function, and
         * accepting it would make the vocabulary whatever the last feed said it was.
         */
        OUTSIDE_VOCABULARY,

        /**
         * A recognised function that ACPIR 53 cannot place — {@code PROCESSING} or
         * {@code OTHER}. <b>This is the cause the existing four-value vocabulary makes
         * unavoidable</b>, and the one for which the queue has no category of its own: the
         * attribute is present and valid, and still does not answer the question. See
         * {@link CostFunctionCapitalisability#INDETERMINATE}.
         */
        INDETERMINATE,

        /**
         * A recognised function that ACPIR 53 excludes, on a posting the caller intends to
         * capitalise. A contradiction between the rule set's classification and the cost
         * function, not a gap: {@code ADMIN} means internal administrative cost, which
         * paragraph 53 excludes by name, so an {@code INTEGRAL} classification on it cannot
         * both be right. {@code FeePosting}'s own javadoc names this case a rule-set defect
         * and declines to re-decide it in the projector; this is where it gets detected.
         */
        EXCLUDED_BY_ACPIR_53;

        /** Whether this cause admits the posting to the carrying amount. */
        public boolean isAccepted() {
            return this == ACCEPTED;
        }
    }

    public CostFunctionResolution {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException(
                "a resolution with no detail is not an exception-queue entry; name the defect");
        }
        // The two invariants that keep this record from carrying a contradiction: an accepted
        // resolution has a function and raises nothing, a rejection has a category and no
        // function. Without them a caller could read function() on a rejection, get null, and
        // discover the problem three stack frames away from its cause.
        if (cause == Cause.ACCEPTED) {
            Objects.requireNonNull(function, "an accepted resolution must carry its cost function");
            if (exception != null) {
                throw new IllegalArgumentException(
                    "an accepted cost function raises no exception, got " + exception);
            }
        } else {
            Objects.requireNonNull(exception, "a rejected resolution must name its queue category");
            // The category is a function of the cause, and this is what makes that true of every
            // instance rather than only of the ones built through rejected(). A record's canonical
            // constructor is public whether or not the factory is the intended door, so without
            // this check a caller could route an ABSENT attribute to MISSING_MANDATORY_FIELD and
            // send one defect to two queues — which is the same defect as an unstable rejection
            // message, one level up: an exception category that varies by call site is not a
            // category.
            if (exception != categoryFor(cause)) {
                throw new IllegalArgumentException(
                    "cause " + cause + " routes to " + categoryFor(cause) + ", not " + exception
                        + "; the queue category is derived from the cause, never chosen per call"
                        + " site");
            }
            if (cause == Cause.ABSENT || cause == Cause.OUTSIDE_VOCABULARY) {
                if (function != null) {
                    throw new IllegalArgumentException(
                        "cause " + cause + " means no function was recognised, got " + function);
                }
            } else {
                Objects.requireNonNull(function,
                    "cause " + cause + " concerns a recognised function; it must be carried so the"
                        + " exception report can say which one");
            }
        }
    }

    /** An accepted attribute, with the function ACPIR 53 places it on. */
    public static CostFunctionResolution accepted(CostFunction function, String detail) {
        return new CostFunctionResolution(function, Cause.ACCEPTED, null, detail);
    }

    /**
     * A rejection. The queue category is derived from the cause rather than supplied, so that
     * every rejection of the same kind reaches the queue under the same category however many
     * call sites raise it — a category that varies by call site is not a category.
     */
    public static CostFunctionResolution rejected(CostFunction function, Cause cause, String detail) {
        if (cause == Cause.ACCEPTED) {
            throw new IllegalArgumentException("ACCEPTED is not a rejection cause");
        }
        return new CostFunctionResolution(function, cause, categoryFor(cause), detail);
    }

    /**
     * The exception-queue category each cause currently maps to.
     *
     * <p>All four map to {@link ExceptionCategory#MISSING_COST_FUNCTION}, and that is reported
     * as a gap rather than presented as a design. Its javadoc reads "An {@code INTEGRAL} cost
     * posting with no {@code cost_function} attribute... Without the attribute the posting
     * cannot be placed on either side of that boundary" — which describes {@link Cause#ABSENT}
     * exactly and the other three only by extension. The alternative was to borrow a category
     * that means something else ({@code UNMAPPED_FEE_CODE} is about fee codes,
     * {@code MISSING_MANDATORY_FIELD} about a field the computation cannot proceed without),
     * and a borrowed category is worse than an overloaded one: it sends the item to the wrong
     * queue. Every category blocks the close and stops the contract, so no item escapes on
     * this account; what is lost is only the routing, which {@link Cause} preserves.
     */
    private static ExceptionCategory categoryFor(Cause cause) {
        // Exhaustive over Cause rather than a bare return, so that adding a cause is a
        // compile error here and not a silent inheritance of whatever the last one mapped to.
        return switch (cause) {
            case ABSENT, OUTSIDE_VOCABULARY, INDETERMINATE, EXCLUDED_BY_ACPIR_53 ->
                ExceptionCategory.MISSING_COST_FUNCTION;
            case ACCEPTED -> throw new IllegalArgumentException("ACCEPTED raises no exception");
        };
    }

    /** Whether the posting may proceed on this attribute. */
    public boolean isAccepted() {
        return cause.isAccepted();
    }

    /** The resolved function, empty on a rejection that recognised nothing. */
    public Optional<CostFunction> resolved() {
        return Optional.ofNullable(function);
    }

    /**
     * Whether the posting may enter the initial carrying amount on this attribute.
     *
     * <p>Note that acceptance and capitalisability are different questions.
     * {@link CostFunction#resolve} accepts {@code ADMIN} — the attribute is populated and
     * valid, which is all FR-203's literal limb asks — and {@code ADMIN} still capitalises
     * nothing. A call site that conflated the two would capitalise every cost whose attribute
     * merely parsed.
     */
    public boolean capitalises() {
        return isAccepted() && function.isCapitalisable();
    }

    /** One line for an exception-queue entry or a computation trace. */
    public String describe() {
        if (isAccepted()) {
            return "cost_function " + function.name() + " accepted — " + detail;
        }
        return "cost_function rejected [" + cause + " -> " + exception + "] — " + detail;
    }
}
