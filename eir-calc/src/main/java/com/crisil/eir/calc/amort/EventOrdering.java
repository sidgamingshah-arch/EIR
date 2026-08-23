package com.crisil.eir.calc.amort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic intra-period ordering of events (calculation specification
 * section 5.4).
 *
 * <p>Ordering ambiguity here produces small, irreproducible differences that are
 * miserable to diagnose, and it defeats invariant DT-1: a period that replays in
 * a different order does not replay bit-identically. So the order is total — no
 * two events can tie — and it is expressed once, as enum ordinals, rather than
 * as a comparator that a later change can quietly reorder.
 *
 * <p>Within a period:
 *
 * <ol>
 *   <li>accrue interest to the event date (a broken period,
 *       {@link BrokenPeriodAccrual});
 *   <li>apply cash received;
 *   <li>apply the lifecycle event;
 *   <li>re-solve or restate as the event type dictates;
 *   <li>accrue from the event date to period end.
 * </ol>
 *
 * <p>{@link #segments} mechanises steps 1 and 5 by cutting the period at each
 * event date; the caller applies steps 2 to 4 at each cut.
 */
public final class EventOrdering {

    private EventOrdering() {
    }

    /**
     * The five intra-period steps. The ordinal <em>is</em> the sequence, and
     * nothing else in the engine encodes it.
     */
    public enum Step {

        /** Broken-period accrual from the last boundary up to the event date. */
        ACCRUE_TO_EVENT_DATE,

        /** Cash received on the event date reduces the carrying amount. */
        APPLY_CASH,

        /** The lifecycle event itself: reset, re-estimation, modification, staging. */
        APPLY_EVENT,

        /** Re-solve (B5.4.5) or restate at the original EIR (B5.4.6), per the routed mechanism. */
        RESOLVE_OR_RESTATE,

        /** Broken-period accrual from the event date to period end. */
        ACCRUE_TO_PERIOD_END;

        /** The steps in the order they are applied. */
        public static List<Step> sequence() {
            return List.of(values());
        }
    }

    /**
     * Event-type precedence for events sharing a date. The ordinal is the
     * precedence; there is no second table.
     *
     * <p>The order is not arbitrary and two adjacencies carry most of the weight:
     *
     * <ul>
     *   <li><strong>Cash before events.</strong> Steps 2 and 3 of section 5.4 in
     *       enum form: an event measures the balance after the day's contractual
     *       receipts have been applied, not before.
     *   <li><strong>Restatements before re-solves.</strong> A B5.4.6 catch-up is
     *       measured at the <em>original</em> EIR. Let a same-day B5.4.5 reset go
     *       first and the original EIR is already gone, so the catch-up gets
     *       computed at the new rate and collapses towards zero — the CU-1 defect
     *       arriving through the back door, from ordering rather than from
     *       discounting. So {@link #RE_ESTIMATION} and {@link #MODIFICATION}
     *       precede {@link #BENCHMARK_RESET}.
     * </ul>
     *
     * <p>Derecognition follows every measurement event, because those events
     * measure the asset that derecognition is about to remove. Staging and
     * allowance remeasurement come last of the substantive entries: neither
     * touches the EIR or the gross carrying amount (FR-610), and both want to see
     * the day's final balance.
     */
    public enum EventPrecedence {

        /** A tranche or further advance: cash out, balance up. */
        DISBURSEMENT,

        /** The contractual instalment due on the date. */
        SCHEDULED_RECEIPT,

        /** A voluntary part-prepayment, applied after the instalment contractually due. */
        PART_PREPAYMENT,

        /** Closure. Accelerates the whole unamortised fee to P&amp;L (FR-510). */
        FULL_PREPAYMENT,

        /** Revised estimate of cash flows — CPR, tenor re-profile. Restates at the original EIR. */
        RE_ESTIMATION,

        /** Contractual change under the substantiality test. Non-substantial restates at the original EIR. */
        MODIFICATION,

        /** Benchmark or market-spread repricing. Re-solves; carrying amount untouched. */
        BENCHMARK_RESET,

        /** Substantial modification: the old asset ends here. */
        DERECOGNITION,

        /** Stage migration or cure. Changes recognition only, never the EIR or the GCA. */
        STAGE_CHANGE,

        /** ECL remeasurement. No consequence in this engine (ACPIR 6(12)). */
        ALLOWANCE_REMEASUREMENT,

        /** Terminal. */
        WRITE_OFF
    }

    /**
     * An event placed in the total order.
     *
     * @param date            when it happens
     * @param precedence      its type, which fixes its place among same-date events
     * @param receiptSequence the order it was received in from the source system,
     *                        breaking ties within a type — two part-prepayments on
     *                        one day are not interchangeable
     * @param reference       the source system's identifier, and the final
     *                        tiebreaker: a stable sort over an unordered input is
     *                        not determinism, so the comparator is made total
     *                        rather than left to depend on list order
     */
    public record OrderedEvent(
        LocalDate date,
        EventPrecedence precedence,
        int receiptSequence,
        String reference) implements Comparable<OrderedEvent> {

        public OrderedEvent {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(precedence, "precedence");
            Objects.requireNonNull(reference, "reference");
        }

        public static OrderedEvent of(
            LocalDate date, EventPrecedence precedence, int receiptSequence, String reference) {
            return new OrderedEvent(date, precedence, receiptSequence, reference);
        }

        /** True where the event ends the instrument and makes later same-date events moot. */
        public boolean terminal() {
            return precedence == EventPrecedence.FULL_PREPAYMENT
                || precedence == EventPrecedence.DERECOGNITION
                || precedence == EventPrecedence.WRITE_OFF;
        }

        @Override
        public int compareTo(OrderedEvent other) {
            return COMPARATOR.compare(this, other);
        }
    }

    private static final Comparator<OrderedEvent> COMPARATOR =
        Comparator.comparing(OrderedEvent::date)
            .thenComparing(OrderedEvent::precedence)
            .thenComparingInt(OrderedEvent::receiptSequence)
            .thenComparing(OrderedEvent::reference);

    /** The total order: date, then type precedence, then receipt sequence, then reference. */
    public static Comparator<OrderedEvent> comparator() {
        return COMPARATOR;
    }

    /** The events in applied order. The input is not modified. */
    public static List<OrderedEvent> order(List<OrderedEvent> events) {
        Objects.requireNonNull(events, "events");
        List<OrderedEvent> ordered = new ArrayList<>(events);
        ordered.sort(COMPARATOR);
        return List.copyOf(ordered);
    }

    /**
     * One accrual stretch within a period, ending either at an event date or at
     * period end.
     *
     * <p>{@code from} equal to {@code to} is legitimate and expected — an event on
     * the period boundary, such as a Stage 3 migration at the start of a month.
     * The accrual runs and earns nothing, which is the correct answer and a
     * better one than a special case.
     *
     * @param from         start of the accrual
     * @param to           end of the accrual, and the date the events apply on
     * @param eventsAtEnd  events applying at {@code to}, in applied order
     * @param periodEnd    true for the final stretch of the period
     */
    public record AccrualSegment(
        LocalDate from, LocalDate to, List<OrderedEvent> eventsAtEnd, boolean periodEnd) {

        public AccrualSegment {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(eventsAtEnd, "eventsAtEnd");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("segment end " + to + " precedes start " + from);
            }
            eventsAtEnd = List.copyOf(eventsAtEnd);
        }

        /** True where no time passes, so the accrual is nil. */
        public boolean instantaneous() {
            return from.equals(to);
        }
    }

    /**
     * Cuts a period into accrual segments at its event dates.
     *
     * <p>The result always ends with a {@code periodEnd} segment, even where the
     * last event falls on the period end and that segment is instantaneous. A
     * uniform shape is what lets the caller drive the five steps in a single loop
     * without a trailing special case — and a trailing special case is exactly
     * where an accrual gets dropped.
     *
     * @throws IllegalArgumentException if the period runs backwards, or an event
     *     falls outside {@code [periodStart, periodEnd]} — an event belonging to
     *     another period must be applied in that period, not folded into this one
     */
    public static List<AccrualSegment> segments(
        LocalDate periodStart, LocalDate periodEnd, List<OrderedEvent> events) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(events, "events");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("period end " + periodEnd + " precedes start " + periodStart);
        }
        List<OrderedEvent> ordered = order(events);
        for (OrderedEvent event : ordered) {
            if (event.date().isBefore(periodStart) || event.date().isAfter(periodEnd)) {
                throw new IllegalArgumentException(
                    "event " + event.reference() + " dated " + event.date() + " falls outside period "
                        + periodStart + ".." + periodEnd);
            }
        }

        List<AccrualSegment> segments = new ArrayList<>();
        LocalDate from = periodStart;
        int index = 0;
        while (index < ordered.size()) {
            LocalDate date = ordered.get(index).date();
            List<OrderedEvent> sameDate = new ArrayList<>();
            while (index < ordered.size() && ordered.get(index).date().equals(date)) {
                sameDate.add(ordered.get(index));
                index++;
            }
            segments.add(new AccrualSegment(from, date, sameDate, false));
            from = date;
        }
        segments.add(new AccrualSegment(from, periodEnd, List.of(), true));
        return List.copyOf(segments);
    }
}
