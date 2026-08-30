package com.crisil.eir.api.modules.runs;

import com.crisil.eir.policy.close.AccountingPeriod;
import com.crisil.eir.policy.close.PeriodStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The period statuses {@code GET /api/periods} reports, and the one place a close is recorded.
 *
 * <p><b>Why this state lives here rather than in {@code EirService}.</b> The console's own
 * {@code POST /api/close} builds an {@code AccountingPeriod} per request — open, moved to
 * {@code CLOSING}, handed to the gate — and forgets it, which is right for a screen whose question
 * is "would this close?" and wrong for the resource of 06 § 4, whose whole content is a status of
 * {@code OPEN} / {@code CLOSING} / {@code CLOSED} that survives between requests. Without somewhere
 * for the status to live, {@code POST /periods/{id}/close} could be called twice and permit twice,
 * and FR-902's "a closed period is immutable" would be a sentence in a document.
 *
 * <p><b>The status is the domain's own type, not an enum invented at the edge.</b>
 * {@link AccountingPeriod} carries the DDL's four check constraints — the {@code YYYYMM} shape, the
 * id matching its own start date, the date ordering, and {@code ck_accounting_period_closure_attested}
 * — and {@link PeriodStatus} carries the legal edges, including the one that matters here: CLOSED has
 * no successor. So a reopen cannot be added to this register by accident; it would have to add an
 * edge to the enum, which is a file in {@code eir-policy} whose javadoc says why there isn't one.
 *
 * <p><b>On a refused close leaving the period OPEN.</b> {@link PeriodStatus#legalSuccessors} keeps
 * {@code CLOSING → OPEN} deliberately: "An abandoned close is the ordinary outcome of step 4 finding
 * red: the fix is upstream, in data the period has to reopen to receive." That is exactly the arc
 * 06 § 4 describes — refused, repair, re-run, post, permitted — and the repair is a correction to the
 * book, which a period that stayed in {@code CLOSING} would not be taking. The evidence that the
 * attempt happened is not thrown away with the status: {@link #attempts} and
 * {@link #lastOutcome} keep it, because {@code abandonClose} clears {@code closingStartedAt} and an
 * operator asking why a period is still open deserves an answer better than silence.
 *
 * <p>Not thread-safe. See {@code EirService}'s note on why nothing at this edge takes a lock.
 */
public final class PeriodRegister {

    private final Map<Integer, AccountingPeriod> byId = new LinkedHashMap<>();
    private final Map<Integer, Attempt> attempts = new LinkedHashMap<>();

    /**
     * What is known about the close attempts on a period.
     *
     * @param count      how many times a close has been put to the gate
     * @param lastOutcome what the last one did, in a phrase, or null where none has been attempted
     */
    private record Attempt(int count, String lastOutcome) {
    }

    public PeriodRegister(List<AccountingPeriod> periods) {
        for (AccountingPeriod period : Objects.requireNonNull(periods, "periods")) {
            byId.put(period.periodId(), period);
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException(
                "a periods resource over no periods would answer an empty list to every question,"
                    + " which reads exactly like a book with nothing to close");
        }
    }

    public Optional<AccountingPeriod> find(int periodId) {
        return Optional.ofNullable(byId.get(periodId));
    }

    /** Every period, in the order they were registered. */
    public List<AccountingPeriod> all() {
        return List.copyOf(byId.values());
    }

    /** The period ids on file, for a 404 that says what does exist. */
    public List<Integer> ids() {
        return List.copyOf(byId.keySet());
    }

    /**
     * Moves a period to {@code CLOSING} and counts the attempt.
     *
     * <p>Called before the gate is asked, because {@code CLOSING} is the state the gate runs in
     * ({@link PeriodStatus#isCloseable}) and a period still {@code OPEN} is one whose figures can
     * move underneath the invariant results being read.
     *
     * @throws IllegalStateException if the period is {@code CLOSED} — the caller must refuse that
     *                               with a 409 rather than ask, and reaching here is a defect in
     *                               the endpoint's own check
     */
    public AccountingPeriod beginClose(int periodId, Instant startedAt) {
        AccountingPeriod period = require(periodId);
        AccountingPeriod closing = period.status() == PeriodStatus.CLOSING
            ? period
            : period.startClosing(Objects.requireNonNull(startedAt, "startedAt"));
        byId.put(periodId, closing);
        Attempt before = attempts.get(periodId);
        attempts.put(periodId,
            new Attempt(before == null ? 1 : before.count() + 1, "in progress"));
        return closing;
    }

    /** Returns a period to {@code OPEN} after a refused or impossible close, recording why. */
    public AccountingPeriod abandonClose(int periodId, String outcome) {
        AccountingPeriod period = require(periodId);
        AccountingPeriod reopened = period.status() == PeriodStatus.CLOSING
            ? period.abandonClose()
            : period;
        byId.put(periodId, reopened);
        Attempt before = attempts.get(periodId);
        attempts.put(periodId, new Attempt(before == null ? 1 : before.count(),
            Objects.requireNonNull(outcome, "outcome")));
        return reopened;
    }

    /**
     * Records a close the gate permitted.
     *
     * <p><b>This does not decide anything, and the distinction is the whole safety of the method.</b>
     * {@code PeriodCloseGate} is the only route from {@code CLOSING} to {@code CLOSED}, which is why
     * {@code AccountingPeriod.attestedClose} is package-private in {@code eir-policy}. The row is
     * built here through the canonical constructor — the same path {@code eir-persistence} uses to
     * rehydrate a period closed in an earlier run — and only ever from a presentation whose
     * {@code mayClose()} was true. The constructor still enforces
     * {@code ck_accounting_period_closure_attested}: a closed period names who closed it, when, and
     * the system-time boundary a replay reads as at, or it will not be constructed.
     *
     * @param by     the closer, from the gate's own answer rather than from the request, so that
     *               what is recorded is what the gate weighed
     * @param at     system time the period was locked
     * @param cutoff the {@code recorded_at} horizon a replay of this period must read as at (04 § 5)
     */
    public AccountingPeriod recordClose(int periodId, String by, Instant at, Instant cutoff) {
        AccountingPeriod period = require(periodId);
        if (period.status() != PeriodStatus.CLOSING) {
            throw new IllegalStateException(
                "period " + periodId + " is " + period.status() + "; a close is recorded from"
                    + " CLOSING only, and this one was never moved there");
        }
        AccountingPeriod closed = new AccountingPeriod(
            period.periodId(), period.fiscalYearLabel(), period.periodStartDate(),
            period.periodEndDate(), PeriodStatus.CLOSED, period.closingStartedAt(),
            Objects.requireNonNull(at, "at"), by, Objects.requireNonNull(cutoff, "cutoff"));
        byId.put(periodId, closed);
        Attempt before = attempts.get(periodId);
        attempts.put(periodId, new Attempt(before == null ? 1 : before.count(),
            "closed by " + by));
        return closed;
    }

    /** How many closes have been put to the gate for this period. */
    public int attempts(int periodId) {
        Attempt attempt = attempts.get(periodId);
        return attempt == null ? 0 : attempt.count();
    }

    /** What the last close attempt did, or null where none has been made. */
    public String lastOutcome(int periodId) {
        Attempt attempt = attempts.get(periodId);
        return attempt == null ? null : attempt.lastOutcome();
    }

    private AccountingPeriod require(int periodId) {
        AccountingPeriod period = byId.get(periodId);
        if (period == null) {
            throw new IllegalStateException(
                "no period " + periodId + " on file; the periods are " + ids());
        }
        return period;
    }

    /** The periods a {@code PeriodRegister} is seeded with, as a list, for readability at the call. */
    public static List<AccountingPeriod> of(AccountingPeriod... periods) {
        List<AccountingPeriod> all = new ArrayList<>(periods.length);
        for (AccountingPeriod period : periods) {
            all.add(Objects.requireNonNull(period, "period"));
        }
        return List.copyOf(all);
    }
}
