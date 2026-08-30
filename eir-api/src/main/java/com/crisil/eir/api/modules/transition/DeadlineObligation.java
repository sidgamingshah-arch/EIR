package com.crisil.eir.api.modules.transition;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One migration obligation's position against its own deadline (04 § 6, 06 § 8).
 *
 * <p><b>This type exists so that the coverage report cannot merge the two obligations.</b> ACPIR 21
 * requires the loan under the EIR regime; ACPIR 50 requires its ECL discounting to move from the
 * interim contractual rate to the EIR. 04 § 6 is explicit that they are "two obligations", and that
 * "tracking them in one field would hide a gap" — the gap being a contract recognising interest on
 * the EIR while discounting its ECL at the contractual rate, which is the population ACPIR 50's
 * deadline exists to close.
 *
 * <p>A report that published one {@code migrated} figure would let one obligation's progress mask
 * the other's, and it would do so in the direction that flatters: ACPIR 21 is satisfied first and in
 * bulk, so a merged percentage climbs while the ECL basis has not moved at all. So the report
 * carries <em>one of these per obligation</em> and no combined figure anywhere. Making the two
 * separate values of one type is what makes the merge impossible to write by accident rather than
 * merely discouraged in a comment.
 *
 * <p><b>Each obligation carries its own deadline field, and today they hold the same date.</b> 04
 * § 6 describes ACPIR 21 and ACPIR 50 as two obligations with a <em>common</em> deadline of
 * 31 March 2030, and {@link com.crisil.eir.policy.transition.LegacyCohort#ACPIR_50_DEADLINE} is
 * the single statutory literal both are measured against. The field is nonetheless per obligation
 * rather than one field on the report: the two are separate legal requirements, a report that
 * printed one shared date would have to be restructured if either moved, and — the reason that
 * matters more — a reader who sees one date is one step from believing there is one obligation.
 *
 * <p><b>{@link #satisfied()} is derived, not supplied.</b> The first cut carried it as a component
 * and checked at construction that satisfied plus outstanding equalled tracked. That check could
 * never fail: every caller computed satisfied as tracked minus the outstanding list's size, so the
 * assertion re-derived its own input and passed by construction — a control that cannot fail, which
 * is worse than an absent one because a reader counts it as coverage. Deriving the figure makes the
 * inconsistency unrepresentable instead, the same move {@link
 * com.crisil.eir.policy.transition.TransitionFairValue} makes for the paragraph 19 presumption flag.
 *
 * @param obligation           "ACPIR 21" or "ACPIR 50"
 * @param requirement          what satisfying it means, in a sentence
 * @param deadline             the date this obligation must be met by
 * @param tracked              contracts with a recorded position on this obligation
 * @param outstandingContracts the contracts that do not meet it, named and sorted
 */
public record DeadlineObligation(
    String obligation,
    String requirement,
    LocalDate deadline,
    long tracked,
    List<String> outstandingContracts) {

    public DeadlineObligation {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(deadline, "deadline");
        outstandingContracts =
            List.copyOf(Objects.requireNonNull(outstandingContracts, "outstandingContracts"));
        if (outstandingContracts.size() > tracked) {
            // An argument check, not a control: a caller cannot have more contracts failing an
            // obligation than it tracked positions for, and letting it through would make
            // satisfied() negative and read as an over-achieved obligation. Named as a precondition
            // so nobody counts it as assurance about the data.
            throw new IllegalArgumentException(
                obligation + " names " + outstandingContracts.size() + " outstanding contracts"
                    + " against only " + tracked + " tracked positions; satisfied() would come out"
                    + " negative and read as an obligation more than met");
        }
    }

    /** How many contracts do not yet meet this obligation. */
    public long outstanding() {
        return outstandingContracts.size();
    }

    /** How many do. Derived from the other two, so the three cannot disagree — see the javadoc. */
    public long satisfied() {
        return tracked - outstandingContracts.size();
    }

    /** Whether every tracked contract meets it. Says nothing about untracked ones — see the report. */
    public boolean met() {
        return outstandingContracts.isEmpty();
    }

    /**
     * Whether the deadline has passed with the obligation unmet as at {@code asOf}.
     *
     * <p>Strictly after, matching {@link com.crisil.eir.policy.transition.LegacyCohort} and the
     * schema's generated column: the obligation is to be compliant <em>by</em> 31 March 2030, so a
     * position taken on that date has met it and 1 April 2030 is the first day of breach.
     */
    public boolean inBreach(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !met() && asOf.isAfter(deadline);
    }

    /** A one-line position, for an audit note. */
    public String describe() {
        return obligation + " (" + requirement + ") by " + deadline + ": " + satisfied() + " of "
            + tracked + " tracked contracts satisfy it, " + outstanding() + " outstanding"
            + (outstandingContracts.isEmpty() ? "" : " " + outstandingContracts);
    }
}
