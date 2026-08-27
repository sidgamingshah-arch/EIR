package com.crisil.eir.policy.replay;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * A closed accounting period — the unit a replay replays (04 § 2.13, FR-902).
 *
 * <p>Field list taken from {@code accounting_period} in
 * {@code V2__ledger_and_transition.sql}, reduced to what a replay comparison needs: the
 * {@code YYYYMM} {@code period_id}, the two business-time dates, and who/when closed it. The
 * {@code status} column is not carried because this type <em>is</em> the closed case — an open
 * period has nothing published to replay against, and a nullable status would invite a caller to
 * replay one.
 *
 * <p>The schema's {@code ck_accounting_period_id_matches_dates} and
 * {@code ck_accounting_period_dates} are restated here as construction guards, and
 * {@code ck_accounting_period_closure_attested} as the requirement that {@code closedOn} and
 * {@code closedBy} are present. Those are not what DT-1 asserts — DT-1 is about whether a replay
 * reproduces figures — so guarding them at construction does not disarm any control in this
 * package. The distinction matters, and the tree has an example of getting it right the other way:
 * {@code JournalEntry} deliberately allows an unbalanced entry to be constructed so that SL-2 has
 * something to detect.
 *
 * @param periodId  {@code YYYYMM}, monotonic in reporting order and the range partition key of
 *                  {@code PERIOD_BALANCE} and {@code JOURNAL_ENTRY}
 * @param startDate first day of the period, business time
 * @param endDate   last day of the period, business time — <b>and the date a replay resolves
 *                  policy at</b>; see {@link #policyResolutionDate()}
 * @param closedOn  the day the close was attested ({@code closed_at}), which is <em>not</em> the
 *                  policy resolution date
 * @param closedBy  who attested it ({@code closed_by})
 */
public record ClosedPeriod(
    int periodId,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate closedOn,
    String closedBy) {

    public ClosedPeriod {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(closedOn, "closedOn");
        // ck_accounting_period_closure_attested: a period with no attesting identity is not
        // closed, and replaying against figures nobody signed off is not evidence of anything.
        if (closedBy == null || closedBy.isBlank()) {
            throw new IllegalArgumentException(
                "period " + periodId + " names no closer; a closed period names who closed it"
                    + " and when, or it is not closed (FR-902,"
                    + " ck_accounting_period_closure_attested)");
        }
        closedBy = closedBy.strip();
        // ck_accounting_period_dates.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                "period " + periodId + " ends " + endDate + " before it starts " + startDate);
        }
        // ck_accounting_period_id_matches_dates. The YYYYMM encoding is only useful if it cannot
        // drift from the dates it encodes, which is the schema's own stated reason for the CHECK.
        int encoded = startDate.getYear() * 100 + startDate.getMonthValue();
        if (encoded != periodId) {
            throw new IllegalArgumentException(
                "period id " + periodId + " does not encode its own start date " + startDate
                    + " (expected " + encoded + ")");
        }
        if (closedOn.isBefore(endDate)) {
            throw new IllegalArgumentException(
                "period " + periodId + " was closed " + closedOn + ", before it ended " + endDate
                    + "; a period cannot be attested before the facts it reports have happened");
        }
    }

    /** A calendar month, the ordinary case: 2027-04 running 1 to 30 April, closed 5 May. */
    public static ClosedPeriod month(YearMonth month, LocalDate closedOn, String closedBy) {
        Objects.requireNonNull(month, "month");
        return new ClosedPeriod(
            month.getYear() * 100 + month.getMonthValue(),
            month.atDay(1),
            month.atEndOfMonth(),
            closedOn,
            closedBy);
    }

    /**
     * The date a replay resolves its policy versions at — the period <b>end</b>, never
     * {@link #closedOn}.
     *
     * <p>This is a one-line method carrying an off-by-one that is worth the file it is stated in.
     * A close runs after the period it closes: March 2027 closes on, say, 5 April 2027. Policy
     * versions take effect on dates, and 1 April is the single most likely effective date in the
     * Indian fiscal calendar — every rule set in this codebase's fixtures takes effect on it. So
     * a replay that resolves "the policy in force when the period closed" literally, at
     * {@code closedOn}, picks up the <em>April</em> version and applies a rule written for the new
     * fiscal year to a period that ended in the old one.
     *
     * <p>The figures would then differ from the published ones and DT-1 would fail — which sounds
     * like the control working, except the defect is in the replay harness rather than in the
     * engine, and the investigation starts from "the March close is not reproducible". Resolving
     * at the period end is what "the policy then in force" means: the policy that governed the
     * facts, not the policy that happened to be live on the day somebody pressed close.
     *
     * <p>{@code accounting_period.version_cutoff_at} is the system-time counterpart of the same
     * idea — the schema holds it explicitly because "as at the close" is otherwise a wall-clock
     * guess. This is the business-time half, and it is derivable, so it is derived.
     */
    public LocalDate policyResolutionDate() {
        return endDate;
    }

    /** Months since epoch, for the lookback arithmetic in {@link ReplaySamplingBasis}. */
    int monthOrdinal() {
        return startDate.getYear() * 12 + (startDate.getMonthValue() - 1);
    }

    /** Whether {@code date} falls inside this period, business time. */
    public boolean covers(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    /** A one-line audit sentence. */
    public String describe() {
        return "period " + periodId + " (" + startDate + " to " + endDate + ", closed " + closedOn
            + " by " + closedBy + ")";
    }
}
