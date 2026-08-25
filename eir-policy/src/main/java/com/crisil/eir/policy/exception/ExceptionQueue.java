package com.crisil.eir.policy.exception;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * The exception queue of <a href="../../../../../../../../../docs/04-data-model.md">04 § 3</a>:
 * every contract a run refused to compute, and the gate the accounting close passes through.
 *
 * <p>It answers three questions, and they are three and not one:
 *
 * <ol>
 *   <li><b>Does this block the close?</b> 04 § 3: "Unresolved exceptions block the close unless
 *       explicitly accepted with approval." {@link #blocksClose()} and
 *       {@link #closeBlockers()}.
 *   <li><b>Which contracts have no figure?</b> {@link #quarantinedContracts()}. Not the same
 *       set: eight of the ten categories stop their contract, and two do not.
 *   <li><b>What did the run raise?</b> {@link #countByCategory()}, feeding
 *       {@code AMORTISATION_RUN.exceptions_raised} (04 § 2.13).
 * </ol>
 *
 * <p><b>The two non-stopping categories are not quarantines.</b>
 * {@code STALE_EQUIVALENCE_TEST} and {@code POOL_BACKTEST_BREACH} demote a population to a more
 * expensive and correct measurement — Tier 2, contract level (03 § 10.1–10.2) — rather than
 * withholding a figure. A queue that treated them as quarantines would drop from the reported
 * population precisely the contracts that had just been measured most carefully, and the
 * resulting shortfall would look like missing data rather than like a tiering decision. The
 * asymmetry lives in {@link ExceptionCategory#stopsTheContract()} and this class defers to it
 * rather than restating it.
 *
 * <p><b>Thread-safe by synchronisation, not by hope.</b> The run is a partitioned batch
 * (ADR-0007) and several partitions raise into one queue at once. A dropped record is a contract
 * that failed and that nobody will ever know failed — it is absent from the results and absent
 * from the queue, so the reported population is short by one and every control still ties. That
 * is the worst available failure of this class, so every mutator and every read is synchronised
 * on the queue itself, and the reads return snapshots rather than live views.
 */
public final class ExceptionQueue {

    private final List<ExceptionRecord> records = new ArrayList<>();

    /**
     * Contract id to that contract's positions in {@link #records}.
     *
     * <p>An index rather than a scan, because of the scale this package is written for. The
     * queue's natural consumer is the population-exclusion step, which asks
     * {@link #isQuarantined} once per contract; at FR-905's ten million contracts against a
     * hundred thousand exceptions a linear scan is of the order of 10^12 comparisons, every one
     * of them holding the monitor that the still-running partitions need in order to
     * {@link #raise}. The exclusion step would take longer than the amortisation it is excluding
     * from.
     *
     * <p>Positions, not records: {@link #replace} swaps a record for its worked successor at the
     * same position, so the index survives resolution untouched.
     */
    private final Map<String, List<Integer>> positionsByContract = new HashMap<>();

    /**
     * Files an exception.
     *
     * <p>No de-duplication. One contract can legitimately raise several exceptions in one run —
     * an unmapped fee code and a missing {@code cost_function} on different postings — and
     * collapsing them would hide work that has to be done separately.
     */
    public synchronized void raise(ExceptionRecord record) {
        Objects.requireNonNull(record, "record");
        positionsByContract
            .computeIfAbsent(record.contractId(), contract -> new ArrayList<>())
            .add(records.size());
        records.add(record);
    }

    /** Files several exceptions, in the order given. */
    public synchronized void raiseAll(Collection<ExceptionRecord> raised) {
        Objects.requireNonNull(raised, "raised");
        for (ExceptionRecord record : raised) {
            raise(record);
        }
    }

    /** Every record, in the order raised. A snapshot: later raises do not appear in it. */
    public synchronized List<ExceptionRecord> records() {
        return List.copyOf(records);
    }

    /** How many exceptions this run raised — {@code AMORTISATION_RUN.exceptions_raised}. */
    public synchronized int size() {
        return records.size();
    }

    /** Whether the run raised nothing at all. */
    public synchronized boolean isEmpty() {
        return records.isEmpty();
    }

    /** Every record still {@link ExceptionStatus#OPEN}, in the order raised. */
    public synchronized List<ExceptionRecord> openRecords() {
        return records.stream().filter(ExceptionRecord::isOpen).toList();
    }

    /** Every record raised against one contract, in the order raised. */
    public synchronized List<ExceptionRecord> forContract(String contractId) {
        Objects.requireNonNull(contractId, "contractId");
        return positionsByContract.getOrDefault(contractId, List.of()).stream()
            .map(records::get)
            .toList();
    }

    /**
     * Whether the accounting close is blocked (04 § 3).
     *
     * <p>The gate, stated as one boolean because that is how a close workflow consumes it. True
     * while any record is unresolved and unaccepted — including the two categories that do not
     * stop their contract, because a population that has moved measurement basis is exactly the
     * kind of thing a close should surface before it publishes.
     */
    public synchronized boolean blocksClose() {
        return records.stream().anyMatch(ExceptionRecord::blocksClose);
    }

    /**
     * The records blocking the close, in the order raised.
     *
     * <p>What the close workflow shows a human. A single boolean tells them they cannot close;
     * this tells them what to work.
     */
    public synchronized List<ExceptionRecord> closeBlockers() {
        return records.stream().filter(ExceptionRecord::blocksClose).toList();
    }

    /**
     * The contracts that have no computed figure, in the order first quarantined.
     *
     * <p>The set that must be excluded from the reported population. Note what is <em>in</em> it:
     * a contract whose exception was {@link ExceptionStatus#ACCEPTED_WITH_APPROVAL} is still
     * here, because acceptance unblocked the close without fixing anything — nobody signed a
     * figure into existence. Only {@link ExceptionStatus#RESOLVED} lifts the quarantine, and it
     * does so on the strength of the claim that a recomputation will now succeed.
     */
    public synchronized Set<String> quarantinedContracts() {
        Set<String> quarantined = new LinkedHashSet<>();
        for (ExceptionRecord record : records) {
            if (record.quarantinesContract()) {
                quarantined.add(record.contractId());
            }
        }
        // Unmodifiable but order-preserving. Set.copyOf would drop the order, and the order is
        // part of the answer: the first quarantined contract is where a systemic data problem
        // started, which is what somebody triaging ten thousand entries looks for first.
        return Collections.unmodifiableSet(quarantined);
    }

    /** Whether this contract is quarantined and must be left out of the reported population. */
    public synchronized boolean isQuarantined(String contractId) {
        Objects.requireNonNull(contractId, "contractId");
        return positionsByContract.getOrDefault(contractId, List.of()).stream()
            .map(records::get)
            .anyMatch(ExceptionRecord::quarantinesContract);
    }

    /**
     * The contracts an exception demoted to a more expensive measurement rather than stopped.
     *
     * <p>The {@code STALE_EQUIVALENCE_TEST} and {@code POOL_BACKTEST_BREACH} population. These
     * contracts are still measured and still reported — the exception records that they were
     * measured by a different route than the one their tier assignment claimed, which is a
     * disclosure item and a cost, not a gap. Kept separate from
     * {@link #quarantinedContracts()} so that no caller can accidentally exclude them.
     *
     * <p><b>Disjoint from {@link #quarantinedContracts()} by construction.</b> A contract can
     * raise both kinds — a stale equivalence test <em>and</em> an unmapped fee code — and it then
     * has no figure at all, so the stopping exception decides. Without the subtraction a caller
     * reading this set as "still measured, still reported" would report a contract that was never
     * computed, which is the mirror image of the exclusion mistake the two-set split exists to
     * prevent.
     *
     * <p>A resolved demotion drops out, for the same reason a resolved quarantine does: once the
     * equivalence test is refreshed or the pool passes its back-test, the population is back on
     * the basis its tier assignment claims.
     */
    public synchronized Set<String> demotedContracts() {
        Set<String> demoted = new LinkedHashSet<>();
        for (ExceptionRecord record : records) {
            if (record.demotesContract()) {
                demoted.add(record.contractId());
            }
        }
        demoted.removeAll(quarantinedContracts());
        return Collections.unmodifiableSet(demoted);
    }

    /**
     * How many exceptions were raised in each category, for the run record and the control
     * report.
     *
     * <p>An {@link EnumMap}, so the report is in declaration order — the order 04 § 3 lists the
     * categories in — rather than in whatever order the run happened to raise them. Categories
     * with no exceptions are absent rather than zero: a report of ten rows of which eight are
     * zero buries the two that matter.
     */
    public synchronized Map<ExceptionCategory, Integer> countByCategory() {
        Map<ExceptionCategory, Integer> counts = new EnumMap<>(ExceptionCategory.class);
        for (ExceptionRecord record : records) {
            counts.merge(record.category(), 1, Integer::sum);
        }
        // Not Map.copyOf: that returns an unordered map and would throw away the EnumMap's
        // declaration order, which is the whole reason an EnumMap was used.
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Records that the defect behind {@code raised} was <em>fixed</em>, and returns the resolved
     * record.
     *
     * <p>Replaces in place rather than appending, so the queue holds one row per exception —
     * 04 § 3's {@code EXCEPTION} carries {@code status}, {@code resolved_by} and
     * {@code resolution_note} on the row itself rather than as a separate history table.
     *
     * <p>Matching is by record equality, which includes {@code status}: passing a record that
     * has already been worked will not match, and that is the intended behaviour rather than an
     * inconvenience. Re-resolving an accepted exception, or resolving one twice, would overwrite
     * an approval nobody agreed to overwrite.
     *
     * @throws NoSuchElementException if this exact record is not queued in this exact state
     */
    public synchronized ExceptionRecord resolve(
        ExceptionRecord raised, String resolvedBy, String resolutionNote) {
        return replace(raised, raised.resolve(resolvedBy, resolutionNote));
    }

    /**
     * Records 04 § 3's "explicitly accepted with approval" against {@code raised}, and returns
     * the accepted record.
     *
     * <p>Unblocks the close and changes nothing else. The defect stands, the contract stays
     * quarantined if its category stops the contract, and the next run raises the same exception
     * on the same input. The approver and the reason are mandatory —
     * {@link ExceptionRecord} refuses to construct an accepted record without them — because
     * this is the only point in the process where a named person's decision stands between a
     * known defect and a published set of accounts.
     *
     * @throws NoSuchElementException if this exact record is not queued in this exact state
     */
    public synchronized ExceptionRecord acceptWithApproval(
        ExceptionRecord raised, String approvedBy, String approvalNote) {
        return replace(raised, raised.acceptWithApproval(approvedBy, approvalNote));
    }

    /**
     * Attaches the payload store's reference to a queued record, and returns the updated record.
     *
     * <p>Completes the hand-off {@link ExceptionRecord#captured} starts: the throwable is the
     * diagnostic at the moment of capture, the persistence layer writes the payload afterwards,
     * and the reference has to get back onto the queued row. Without this the queue's copy keeps
     * a null {@code payload_ref} for the life of the run, and — because record equality includes
     * {@code payload_ref} — a later {@link #resolve} passed the updated record would report it as
     * never raised.
     *
     * @throws NoSuchElementException if this exact record is not queued in this exact state
     */
    public synchronized ExceptionRecord attachPayloadRef(
        ExceptionRecord raised, String payloadRef) {
        return replace(raised, raised.withPayloadRef(payloadRef));
    }

    /**
     * A one-line close-gate sentence: whether the close may proceed, over how many exceptions,
     * and how many contracts are left without a figure.
     */
    public synchronized String describeCloseGate() {
        List<ExceptionRecord> blockers = closeBlockers();
        int quarantined = quarantinedContracts().size();
        if (records.isEmpty()) {
            // Said separately, because this sentence is read by an auditor. "All resolved or
            // accepted with approval" over nothing raised asserts an approval nobody gave.
            return "close not blocked: no exceptions raised";
        }
        if (blockers.isEmpty()) {
            return "close not blocked: " + records.size() + " exception(s) raised, all resolved or"
                + " accepted with approval; " + quarantined + " contract(s) without a figure";
        }
        return "CLOSE BLOCKED by " + blockers.size() + " of " + records.size()
            + " exception(s); " + quarantined + " contract(s) without a figure";
    }

    private ExceptionRecord replace(ExceptionRecord raised, ExceptionRecord worked) {
        Objects.requireNonNull(raised, "raised");
        int at = records.indexOf(raised);
        if (at < 0) {
            throw new NoSuchElementException(
                "no queued exception matches " + raised.describe()
                    + "; it was never raised, or it has already been worked");
        }
        records.set(at, worked);
        return worked;
    }
}
