package com.crisil.eir.policy.close;

import com.crisil.eir.domain.Money;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a period's figures were, read at one instant in system time.
 *
 * <p><b>This type exists so that CL-1 can fail.</b> FR-902 says a closed period is immutable and
 * invariant CL-1 asserts it, and the tempting implementation is an immutable Java type: make
 * {@code ClosedPeriod} a record, and no closed period can ever be mutated. That implementation
 * makes CL-1 a tautology. Java immutability constrains a process's own object graph for the
 * lifetime of one JVM; the figures a period published live in {@code PERIOD_BALANCE} and
 * {@code JOURNAL_ENTRY}, and what CL-1 catches is an {@code UPDATE} against them — applied by a
 * correction script, a re-run writing into a closed partition, or somebody fixing an error in the
 * place where fixing normally happens. A control that reads only this process's objects has no
 * input that makes it fail, and this engine treats a control that cannot fail as worse than an
 * absent one because it reads as coverage.
 *
 * <p>So the control needs <em>two</em> statements of the same period: what it published at close,
 * and what the ledger says now. {@link ClosedPeriodImmutability} compares them. Both are inputs
 * read from storage, so all three failure shapes — a changed figure, a deleted one, an added one —
 * are representable, and the control is real.
 *
 * <p><b>{@code asAt} is system time, and it is the axis the whole mechanism turns on</b> (04 § 5).
 * The published statement is read as at the period's {@code version_cutoff_at}; the current one is
 * read as at now. A correction done the way FR-902 requires — a new version in system time,
 * business time untouched — moves the current statement and leaves the published one alone, so the
 * period still replays to the figures it published (DT-1) and the correction is a separate, dated
 * fact in a later period. A correction done in place moves both, and this comparison is what
 * notices.
 *
 * <p>Figure keys are opaque strings and deliberately not an enum: what a period publishes ranges
 * from four portfolio totals in a control report to one row per contract per column, and the
 * control is the same comparison at either grain. The convention used by the tests is
 * {@code ENTITY:id:column} for a row-level figure and a bare name for a portfolio total.
 *
 * @param periodId the {@code YYYYMM} period these figures belong to
 * @param asAt     the system-time instant these figures were read as at (04 § 5)
 * @param figures  figure key to amount; empty is legal, and meaningful — see
 *                 {@link ClosedPeriodImmutability} for which side of the comparison is allowed to
 *                 be empty and why
 */
public record PeriodStatement(int periodId, Instant asAt, Map<String, Money> figures) {

    public PeriodStatement {
        Objects.requireNonNull(asAt, "asAt");
        Objects.requireNonNull(figures, "figures");
        Map<String, Money> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Money> entry : figures.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "figure key");
            if (key.isBlank()) {
                throw new IllegalArgumentException(
                    "period " + periodId + " statement carries a blank figure key; a figure"
                        + " nobody can name cannot be compared against its published value");
            }
            if (copied.put(key, Objects.requireNonNull(entry.getValue(), "figure " + key))
                != null) {
                // Unreachable through a Map argument, which cannot hold a duplicate key. Stated
                // so the invariant of this type is written down: one amount per key, because a
                // second amount for one key would make "what the period published" ambiguous.
                throw new IllegalArgumentException("duplicate figure key " + key);
            }
        }
        // Order-preserving and unmodifiable. Map.copyOf would drop the order, and the order is
        // part of the answer: the first mutated figure in a report is where an errant UPDATE
        // started, which is what somebody triaging one looks for first.
        figures = Collections.unmodifiableMap(copied);
    }

    /** An empty statement as at {@code asAt} — a period that published nothing, or a wiped one. */
    public static PeriodStatement empty(int periodId, Instant asAt) {
        return new PeriodStatement(periodId, asAt, Map.of());
    }

    /** The amount published for {@code figureKey}, or empty if this statement has no such figure. */
    public Optional<Money> figure(String figureKey) {
        Objects.requireNonNull(figureKey, "figureKey");
        return Optional.ofNullable(figures.get(figureKey));
    }

    /** The figure keys, in the order they were presented. */
    public Set<String> figureKeys() {
        return figures.keySet();
    }

    /** How many figures this statement holds. */
    public int size() {
        return figures.size();
    }

    /**
     * A second statement, as at a later instant, with one figure at a different amount.
     *
     * <p><b>Not a mutation of this statement</b> — it produces a new one, and that distinction is
     * the whole point. This is how a caller (and every test of CL-1) represents "somebody ran an
     * {@code UPDATE} against a closed period": the published statement stands untouched and the
     * current statement differs from it. Without a way to build the second statement, CL-1 would
     * have nothing to detect and would be the tautology this package is written to avoid.
     */
    public PeriodStatement withFigure(String figureKey, Money amount, Instant readAt) {
        Objects.requireNonNull(figureKey, "figureKey");
        Objects.requireNonNull(amount, "amount");
        Map<String, Money> revised = new LinkedHashMap<>(figures);
        revised.put(figureKey, amount);
        return new PeriodStatement(periodId, readAt, revised);
    }

    /** A second statement, as at a later instant, with one figure gone. */
    public PeriodStatement withoutFigure(String figureKey, Instant readAt) {
        Objects.requireNonNull(figureKey, "figureKey");
        Map<String, Money> revised = new LinkedHashMap<>(figures);
        revised.remove(figureKey);
        return new PeriodStatement(periodId, readAt, revised);
    }

    /** One audit sentence: how many figures, for which period, as at when. */
    public String describe() {
        return figures.size() + " figure(s) for period " + periodId + " as at " + asAt;
    }

    @Override
    public String toString() {
        return describe();
    }
}
