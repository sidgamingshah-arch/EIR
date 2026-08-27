package com.crisil.eir.policy.close;

import com.crisil.eir.domain.FourEyes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The {@code ACCOUNTING_PERIOD} row of 04 § 2.13, in Java — one reporting period, its status,
 * and the attestation a closed one carries.
 *
 * <p><b>The field list and every guard below are the DDL's.</b> From
 * {@code V2__ledger_and_transition.sql}: nine columns, and four {@code CHECK} constraints that
 * are restated here rather than left to the database, because a period that only becomes
 * invalid on {@code INSERT} is a period the engine will have already acted on.
 *
 * <table>
 *   <caption>Which guard is which constraint</caption>
 *   <tr><th>Constraint</th><th>Guard</th></tr>
 *   <tr><td>{@code ck_accounting_period_id_shape}</td>
 *       <td>{@code periodId} in 190001..999912, month part 01..12</td></tr>
 *   <tr><td>{@code ck_accounting_period_id_matches_dates}</td>
 *       <td>{@code periodId == YYYY*100 + MM} of {@code periodStartDate}</td></tr>
 *   <tr><td>{@code ck_accounting_period_dates}</td>
 *       <td>{@code periodEndDate >= periodStartDate}</td></tr>
 *   <tr><td>{@code ck_accounting_period_closure_attested}</td>
 *       <td>{@code CLOSED} names {@code closedBy}, {@code closedAt}, {@code versionCutoffAt}</td></tr>
 * </table>
 *
 * <p><b>The two kinds of time here are 04 § 5's two axes, and mixing them would break replay.</b>
 * {@code periodStartDate} and {@code periodEndDate} are business time and are {@link LocalDate},
 * following 03 § 1.1 — a reporting period is a range of dates in the world, with no zone.
 * {@code closingStartedAt}, {@code closedAt} and {@code versionCutoffAt} are system time, are
 * {@link Instant}, and match the DDL's {@code TIMESTAMPTZ}: when the engine learned something has
 * to be orderable to better than a day, or a replay cannot tell which of two versions recorded on
 * the same date it was supposed to read. Nothing here reads a clock — every instant arrives as an
 * argument, which is 04 § 5's "time is an input, always".
 *
 * <p><b>Why {@code versionCutoffAt} is a column and not a computation.</b> The DDL comment says
 * it: "as at the close" is otherwise a wall-clock guess, and a replay that guesses reads today's
 * version set and fails DT-1. It is the system-time boundary of 04 § 5 — the {@code recorded_at}
 * horizon a replay of this period must read as at — and it is the single field that makes
 * FR-903's bit-identical replay of a closed period a checkable claim rather than a hope.
 *
 * <p><b>On this record being immutable, and what that does and does not buy.</b> Java immutability
 * here means nothing to FR-902. A closed period is not made immutable by the record that names it
 * being a {@code record}; it is made immutable by nobody rewriting the <em>figures</em> the period
 * published, and those live in {@code PERIOD_BALANCE} and {@code JOURNAL_ENTRY}, not here. That is
 * why invariant CL-1 is asserted by {@link ClosedPeriodImmutability} over two
 * {@link PeriodStatement}s — what the period published and what the ledger says now — and never
 * over this type. A control that read only this record could not fail on any input, and a control
 * that cannot fail is worse than an absent one because it reads as coverage.
 *
 * @param periodId          {@code YYYYMM}; the range-partition key of {@code PERIOD_BALANCE} and
 *                          {@code JOURNAL_ENTRY} (04 § 2.13, § 4)
 * @param fiscalYearLabel   the Indian fiscal year this period falls in, e.g. {@code "FY2027-28"};
 *                          a label, deliberately unparsed — the engine keys off {@code periodId}
 * @param periodStartDate   business time, inclusive
 * @param periodEndDate     business time, inclusive
 * @param status            {@link PeriodStatus}
 * @param closingStartedAt  system time the close began, or null while {@code OPEN}
 * @param closedAt          system time the period was locked, or null unless {@code CLOSED}
 * @param closedBy          who locked it, or null unless {@code CLOSED}
 * @param versionCutoffAt   the {@code recorded_at} boundary a replay reads as at (04 § 5), or
 *                          null unless {@code CLOSED}
 */
public record AccountingPeriod(
    int periodId,
    String fiscalYearLabel,
    LocalDate periodStartDate,
    LocalDate periodEndDate,
    PeriodStatus status,
    Instant closingStartedAt,
    Instant closedAt,
    String closedBy,
    Instant versionCutoffAt) {

    public AccountingPeriod {
        Objects.requireNonNull(fiscalYearLabel, "fiscalYearLabel");
        Objects.requireNonNull(periodStartDate, "periodStartDate");
        Objects.requireNonNull(periodEndDate, "periodEndDate");
        Objects.requireNonNull(status, "status");
        if (fiscalYearLabel.isBlank()) {
            throw new IllegalArgumentException(
                "period " + periodId + " carries no fiscal year label; the statutory reporting"
                    + " unit is the fiscal year and a period naming none cannot be rolled up");
        }
        // ck_accounting_period_id_shape.
        if (periodId < 190001 || periodId > 999912 || periodId % 100 < 1 || periodId % 100 > 12) {
            throw new IllegalArgumentException(
                "period id " + periodId + " is not a YYYYMM in 190001..999912 with a month part"
                    + " of 01..12 (ck_accounting_period_id_shape)");
        }
        // ck_accounting_period_id_matches_dates. "The encoding is only useful if it cannot drift
        // from the dates it encodes" — the DDL's own words. A period_id of 202704 against a start
        // date in May files April's rows into May's partition, and every total still ties.
        if (periodId != periodIdOf(periodStartDate)) {
            throw new IllegalArgumentException(
                "period id " + periodId + " does not encode its own start date "
                    + periodStartDate + " (ck_accounting_period_id_matches_dates); expected "
                    + periodIdOf(periodStartDate));
        }
        // ck_accounting_period_dates.
        if (periodEndDate.isBefore(periodStartDate)) {
            throw new IllegalArgumentException(
                "period " + periodId + " ends " + periodEndDate + ", before it starts "
                    + periodStartDate + " (ck_accounting_period_dates)");
        }
        // ck_accounting_period_closure_attested — FR-902. A CLOSED period names who closed it,
        // when, and the replay boundary, or it is not closed. Note which way this points: it
        // refuses an unattested CLOSED row and says nothing about whether FR-901's gates were
        // met. Those are PeriodCloseGate's question, answered over a request rather than over a
        // row, precisely so that an operator gets the whole list of what is wrong at once.
        if (status.isClosed()) {
            if (closedAt == null || closedBy == null || closedBy.isBlank()
                || versionCutoffAt == null) {
                throw new IllegalArgumentException(
                    "period " + periodId + " is CLOSED but names "
                        + (closedBy == null || closedBy.isBlank() ? "nobody" : "'" + closedBy + "'")
                        + " at " + closedAt + " with cutoff " + versionCutoffAt
                        + "; FR-902 requires all three (ck_accounting_period_closure_attested)");
            }
        } else if (closedAt != null || closedBy != null || versionCutoffAt != null) {
            // Not in the DDL, and the DDL cannot express it usefully: a CLOSING row carrying a
            // closed_at reads as closed to anything that tests the timestamp instead of the
            // status, and both readings exist in any real reporting stack.
            throw new IllegalArgumentException(
                "period " + periodId + " is " + status + " but carries closure attestation"
                    + " (closedBy=" + closedBy + ", closedAt=" + closedAt + ", cutoff="
                    + versionCutoffAt + "); attestation and status move together");
        }
        if (closedAt != null && closingStartedAt != null && closedAt.isBefore(closingStartedAt)) {
            throw new IllegalArgumentException(
                "period " + periodId + " was closed at " + closedAt + ", before its close began "
                    + closingStartedAt);
        }
    }

    /**
     * The {@code YYYYMM} period id a business date falls in.
     *
     * <p>The DDL's {@code EXTRACT(YEAR ...) * 100 + EXTRACT(MONTH ...)}, in Java, in one place.
     * Published because {@link RestatementArtefact} needs the same arithmetic to check that a
     * correction's business-time {@code valid_from} really does fall in the period it claims to
     * correct, and two spellings of a partition key is how a restatement lands in the wrong
     * partition.
     */
    public static int periodIdOf(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.getYear() * 100 + date.getMonthValue();
    }

    /** A newly opened period, taking postings. */
    public static AccountingPeriod open(
        int periodId, String fiscalYearLabel, LocalDate start, LocalDate end) {
        return new AccountingPeriod(
            periodId, fiscalYearLabel, start, end, PeriodStatus.OPEN, null, null, null, null);
    }

    /**
     * The same period, moved to {@code CLOSING} — 02 § 3.1 has begun.
     *
     * @param at system time the close began; recorded because an abandoned close leaves it behind
     *           as the only evidence the attempt happened
     */
    public AccountingPeriod startClosing(Instant at) {
        Objects.requireNonNull(at, "at");
        requireEdgeTo(PeriodStatus.CLOSING);
        return new AccountingPeriod(
            periodId, fiscalYearLabel, periodStartDate, periodEndDate, PeriodStatus.CLOSING,
            at, null, null, null);
    }

    /**
     * The same period, back to {@code OPEN} — the close was abandoned.
     *
     * <p>The ordinary consequence of step 4 finding red on an invariant whose cause is upstream:
     * the period has to reopen to receive the corrected feed. {@code closingStartedAt} is cleared
     * with it, because the next close is a new one and its elapsed time is not measured from the
     * abandoned attempt.
     */
    public AccountingPeriod abandonClose() {
        requireEdgeTo(PeriodStatus.OPEN);
        return new AccountingPeriod(
            periodId, fiscalYearLabel, periodStartDate, periodEndDate, PeriodStatus.OPEN,
            null, null, null, null);
    }

    /**
     * The same period, {@code CLOSED} and attested — <b>the gate's output, and package-private on
     * purpose.</b>
     *
     * <p>{@link PeriodCloseGate} is the only route from {@code CLOSING} to {@code CLOSED}, in the
     * same sense that {@code MakerCheckerGate} is the only route into {@code EFFECTIVE}. A public
     * method here would be a second route with none of FR-901's gates on it, and the sentence at
     * the top of the gate claiming otherwise would be false.
     *
     * <p>The canonical constructor still accepts a {@code CLOSED} row, and must: persistence has
     * to rehydrate periods closed in earlier runs, and a type that could not represent a closed
     * period would leave CL-1 with nothing to assert over.
     */
    AccountingPeriod attestedClose(String by, Instant at, Instant cutoff) {
        requireEdgeTo(PeriodStatus.CLOSED);
        return new AccountingPeriod(
            periodId, fiscalYearLabel, periodStartDate, periodEndDate, PeriodStatus.CLOSED,
            closingStartedAt, at,
            FourEyes.requireIdentity(by, "closedBy",
                "FR-902 requires a closed period to name who closed it"),
            cutoff);
    }

    /** Whether {@code date} falls in this period, both ends inclusive. */
    public boolean covers(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(periodStartDate) && !date.isAfter(periodEndDate);
    }

    /** Whether this period is locked (FR-902). */
    public boolean isClosed() {
        return status.isClosed();
    }

    /** One audit sentence naming the period, its status and its attestation. */
    public String describe() {
        return "period " + periodId + " (" + fiscalYearLabel + ", " + periodStartDate + ".."
            + periodEndDate + ") " + status
            + (status.isClosed()
                ? ", closed by " + closedBy + " at " + closedAt + ", replay cutoff "
                    + versionCutoffAt
                : "");
    }

    @Override
    public String toString() {
        return describe();
    }

    private void requireEdgeTo(PeriodStatus target) {
        if (!status.canMoveTo(target)) {
            // A throw, not a refusal value: the gate answers policy conditions as values, but a
            // caller asking a CLOSED period to close again has a wiring defect that no list of
            // refusals downstream can compensate for. PeriodCloseGate tests the status first and
            // returns PERIOD_ALREADY_CLOSED, so this is unreachable through the gate.
            throw new IllegalStateException(
                "period " + periodId + " is " + status + " and cannot move to " + target
                    + "; the legal moves are " + status.legalSuccessors()
                    + (status.isClosed()
                        ? " — CLOSED is terminal (FR-902); a correction is a restatement artefact"
                        : ""));
        }
    }
}
