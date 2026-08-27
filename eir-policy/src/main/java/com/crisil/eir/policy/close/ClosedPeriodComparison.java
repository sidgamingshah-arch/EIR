package com.crisil.eir.policy.close;

import java.util.Objects;

/**
 * A closed period and the two statements CL-1 compares: what it published, and what the ledger says
 * now.
 *
 * <p>A named type rather than three loose arguments, because the three have to agree and the ways
 * they can disagree are all wiring defects that would produce a confidently wrong control answer.
 * The guards below therefore <b>throw</b> — the repository's rule is that a data condition is
 * reported as an {@link com.crisil.eir.domain.InvariantResult} and never thrown, and none of these
 * is a data condition. A published statement for one period compared against a current statement
 * for another is not a mutated ledger; it is a caller that has mixed up two periods, and reporting
 * it as a CL-1 breach would send an operator hunting for an {@code UPDATE} that never happened.
 *
 * @param period    the closed period; must be {@code CLOSED}, because an open period is supposed
 *                  to change and CL-1 asserted over one would be a false control
 * @param published the figures as at the period's {@code version_cutoff_at} — what was reported
 * @param current   the figures read now
 */
public record ClosedPeriodComparison(
    AccountingPeriod period, PeriodStatement published, PeriodStatement current) {

    public ClosedPeriodComparison {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(current, "current");
        if (!period.isClosed()) {
            throw new IllegalArgumentException(
                "CL-1 asserted over " + period.describe() + "; an OPEN or CLOSING period is"
                    + " supposed to change, so a control claiming its figures have not is not a"
                    + " control — it is a false pass or a false breach depending on the hour");
        }
        if (published.periodId() != period.periodId()
            || current.periodId() != period.periodId()) {
            throw new IllegalArgumentException(
                "CL-1 over period " + period.periodId() + " was handed a published statement for "
                    + published.periodId() + " and a current statement for " + current.periodId()
                    + "; comparing two periods reports every figure as mutated");
        }
        // THE ONE THAT KEEPS THIS CONTROL HONEST. Two empty statements compare equal, so CL-1
        // would pass — and the overwhelmingly likely cause of two empty statements is a caller
        // that failed to load either side. That is the "an empty dashboard is not a green one"
        // defect in its CL-1 form: a control reporting satisfied on a period it never read.
        // Note what is still allowed: an empty PUBLISHED statement against a non-empty current
        // one. That is a real and detectable mutation — figures inserted into a period that
        // published none — and refusing it here would remove a failure mode from the control.
        if (published.size() == 0 && current.size() == 0) {
            throw new IllegalArgumentException(
                "CL-1 over period " + period.periodId() + " was handed two empty statements;"
                    + " nothing can be compared, and a pass here would report a period nobody"
                    + " read as unmutated");
        }
        if (current.asAt().isBefore(published.asAt())) {
            throw new IllegalArgumentException(
                "CL-1 over period " + period.periodId() + " was handed a current statement as at "
                    + current.asAt() + ", earlier than the published statement as at "
                    + published.asAt() + "; the two are the wrong way round, and CL-1 would then"
                    + " report the correction of a mutation as a mutation");
        }
    }

    /** The period id both statements are about. */
    public int periodId() {
        return period.periodId();
    }
}
