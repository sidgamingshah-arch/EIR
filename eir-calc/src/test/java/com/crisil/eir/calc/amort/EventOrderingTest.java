package com.crisil.eir.calc.amort;

import static com.crisil.eir.calc.amort.AmortFixtures.MONTH_12;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.amort.EventOrdering.AccrualSegment;
import com.crisil.eir.calc.amort.EventOrdering.EventPrecedence;
import com.crisil.eir.calc.amort.EventOrdering.OrderedEvent;
import com.crisil.eir.calc.amort.EventOrdering.Step;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Intra-period event ordering (specification 5.4): total, deterministic, and
 * independent of the order the source system happened to hand the events over in.
 *
 * <p>Ordering ambiguity here does not produce a wrong number so much as an
 * irreproducible one, which is worse: invariant DT-1 requires a re-run of a closed
 * period to reproduce published figures bit-identically, and a period that replays in
 * a different order does not. So the tests below do not merely check that a sorted
 * list comes back sorted. They enumerate every permutation of a same-date event set
 * and assert one answer, because a comparator that is only a partial order will sort
 * stably — and stability over an <em>unordered</em> input is not determinism, it is
 * dependence on arrival sequence.
 */
class EventOrderingTest {

    private static final LocalDate DAY = MONTH_12;

    private static OrderedEvent event(EventPrecedence precedence, int sequence, String reference) {
        return OrderedEvent.of(DAY, precedence, sequence, reference);
    }

    @Test
    @DisplayName("same-date events order by type precedence, then receipt sequence, then reference")
    void sameDateEventsOrderByPrecedenceThenSequence() {
        List<OrderedEvent> events = List.of(
            event(EventPrecedence.BENCHMARK_RESET, 1, "RESET-1"),
            event(EventPrecedence.PART_PREPAYMENT, 9, "PP-9"),
            event(EventPrecedence.PART_PREPAYMENT, 4, "PP-4"),
            event(EventPrecedence.SCHEDULED_RECEIPT, 7, "EMI-7"),
            event(EventPrecedence.RE_ESTIMATION, 2, "REEST-2"),
            event(EventPrecedence.STAGE_CHANGE, 0, "STAGE-0"));

        assertThat(EventOrdering.order(events)).extracting(OrderedEvent::reference)
            .containsExactly("EMI-7", "PP-4", "PP-9", "REEST-2", "RESET-1", "STAGE-0");
    }

    @Test
    @DisplayName("cash before events: an event measures the balance after the day's receipts")
    void cashPrecedesEvents() {
        // Steps 2 and 3 of section 5.4 in enum form. An event that measured the balance
        // before the day's contractual receipt would restate or re-solve against a balance
        // one instalment too high.
        assertThat(EventPrecedence.SCHEDULED_RECEIPT.ordinal())
            .isLessThan(EventPrecedence.RE_ESTIMATION.ordinal());
        assertThat(EventPrecedence.SCHEDULED_RECEIPT.ordinal())
            .isLessThan(EventPrecedence.BENCHMARK_RESET.ordinal());
        // And a voluntary prepayment lands after the instalment contractually due, not
        // before it: the borrower owes the instalment either way.
        assertThat(EventPrecedence.SCHEDULED_RECEIPT.ordinal())
            .isLessThan(EventPrecedence.PART_PREPAYMENT.ordinal());
        // A disbursement precedes everything, because the balance the day's events measure
        // has to include the tranche drawn that morning.
        assertThat(EventPrecedence.DISBURSEMENT.ordinal()).isEqualTo(0);
    }

    @Test
    @DisplayName("restatements before re-solves — the CU-1 defect arriving through ordering")
    void restatementsPrecedeResolves() {
        // A B5.4.6 catch-up is measured at the *original* EIR. Let a same-day B5.4.5 reset
        // go first and the original EIR is already gone, so the catch-up gets computed at
        // the new rate and collapses towards zero. That is the CU-1 defect reached from
        // ordering rather than from discounting, and no invariant on the catch-up itself
        // would see it: the rate it was given genuinely was the persisted rate.
        assertThat(EventPrecedence.RE_ESTIMATION.ordinal())
            .isLessThan(EventPrecedence.BENCHMARK_RESET.ordinal());
        assertThat(EventPrecedence.MODIFICATION.ordinal())
            .isLessThan(EventPrecedence.BENCHMARK_RESET.ordinal());

        List<OrderedEvent> events = List.of(
            event(EventPrecedence.BENCHMARK_RESET, 1, "EBLR-RESET"),
            event(EventPrecedence.RE_ESTIMATION, 1, "CPR-REVISION"));
        assertThat(EventOrdering.order(events)).extracting(OrderedEvent::reference)
            .containsExactly("CPR-REVISION", "EBLR-RESET");
    }

    @Test
    @DisplayName("derecognition follows every measurement event; staging and allowance come last")
    void terminalAndNonMeasuringEventsComeLast() {
        // Derecognition removes the asset that the measurement events measure, so it cannot
        // precede them. Staging and allowance remeasurement touch neither the EIR nor the
        // gross carrying amount and both want the day's final balance.
        assertThat(EventPrecedence.DERECOGNITION.ordinal())
            .isGreaterThan(EventPrecedence.MODIFICATION.ordinal());
        assertThat(EventPrecedence.DERECOGNITION.ordinal())
            .isGreaterThan(EventPrecedence.BENCHMARK_RESET.ordinal());
        assertThat(EventPrecedence.STAGE_CHANGE.ordinal())
            .isGreaterThan(EventPrecedence.DERECOGNITION.ordinal());
        assertThat(EventPrecedence.ALLOWANCE_REMEASUREMENT.ordinal())
            .isGreaterThan(EventPrecedence.STAGE_CHANGE.ordinal());
        assertThat(EventPrecedence.WRITE_OFF.ordinal())
            .isEqualTo(EventPrecedence.values().length - 1);

        assertThat(event(EventPrecedence.FULL_PREPAYMENT, 0, "CLOSURE").terminal()).isTrue();
        assertThat(event(EventPrecedence.DERECOGNITION, 0, "SUBSTANTIAL").terminal()).isTrue();
        assertThat(event(EventPrecedence.WRITE_OFF, 0, "WO").terminal()).isTrue();
        assertThat(event(EventPrecedence.BENCHMARK_RESET, 0, "RESET").terminal()).isFalse();
    }

    @Test
    @DisplayName("the order is identical under every permutation of the input")
    void orderIsIndependentOfInputOrder() {
        List<OrderedEvent> canonical = List.of(
            event(EventPrecedence.SCHEDULED_RECEIPT, 1, "EMI"),
            event(EventPrecedence.PART_PREPAYMENT, 1, "PP-1"),
            event(EventPrecedence.PART_PREPAYMENT, 2, "PP-2"),
            event(EventPrecedence.RE_ESTIMATION, 1, "REEST"),
            event(EventPrecedence.BENCHMARK_RESET, 1, "RESET"),
            event(EventPrecedence.STAGE_CHANGE, 1, "STAGE"));
        List<String> expected = EventOrdering.order(canonical).stream().map(OrderedEvent::reference).toList();

        // All 720 permutations, enumerated rather than shuffled: nothing in this engine may
        // depend on a random seed, and an exhaustive sweep is also the only version of this
        // assertion that a partial order cannot pass by luck.
        List<List<OrderedEvent>> permutations = permutations(canonical);
        assertThat(permutations).hasSize(720);
        for (List<OrderedEvent> permutation : permutations) {
            assertThat(EventOrdering.order(permutation).stream().map(OrderedEvent::reference).toList())
                .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("repeated runs give the same order: no clock, no randomness")
    void orderIsStableAcrossRuns() {
        List<OrderedEvent> events = List.of(
            event(EventPrecedence.STAGE_CHANGE, 3, "STAGE"),
            event(EventPrecedence.SCHEDULED_RECEIPT, 3, "EMI"),
            event(EventPrecedence.MODIFICATION, 3, "MOD"));

        List<OrderedEvent> first = EventOrdering.order(events);
        for (int run = 0; run < 5; run++) {
            assertThat(EventOrdering.order(events)).isEqualTo(first);
        }
        assertThat(first).extracting(OrderedEvent::reference).containsExactly("EMI", "MOD", "STAGE");
    }

    @Test
    @DisplayName("the comparator is total: two events of one type and sequence still order")
    void theComparatorIsTotal() {
        // Same date, same type, same receipt sequence — the case a partial order leaves to
        // arrival sequence. The source system's reference is the final tiebreaker, so the
        // answer is fixed rather than inherited from the input list.
        OrderedEvent alpha = event(EventPrecedence.PART_PREPAYMENT, 1, "PP-A");
        OrderedEvent beta = event(EventPrecedence.PART_PREPAYMENT, 1, "PP-B");

        assertThat(EventOrdering.comparator().compare(alpha, beta)).isNegative();
        assertThat(EventOrdering.comparator().compare(beta, alpha)).isPositive();
        assertThat(EventOrdering.comparator().compare(alpha, alpha)).isZero();
        assertThat(alpha.compareTo(beta)).isNegative();
        assertThat(EventOrdering.order(List.of(beta, alpha))).containsExactly(alpha, beta);
        assertThat(EventOrdering.order(List.of(alpha, beta))).containsExactly(alpha, beta);
    }

    @Test
    @DisplayName("two part-prepayments on one day are not interchangeable")
    void receiptSequenceBreaksTiesWithinAType() {
        List<OrderedEvent> events = List.of(
            event(EventPrecedence.PART_PREPAYMENT, 2, "SECOND"),
            event(EventPrecedence.PART_PREPAYMENT, 1, "FIRST"));

        assertThat(EventOrdering.order(events)).extracting(OrderedEvent::receiptSequence)
            .containsExactly(1, 2);
    }

    @Test
    @DisplayName("dates dominate: an earlier event sorts first whatever its type")
    void dateDominatesPrecedence() {
        OrderedEvent earlierWriteOff = OrderedEvent.of(
            DAY.minusDays(1), EventPrecedence.WRITE_OFF, 0, "EARLY-WO");
        OrderedEvent laterDisbursement = event(EventPrecedence.DISBURSEMENT, 0, "LATE-DISB");

        assertThat(EventOrdering.order(List.of(laterDisbursement, earlierWriteOff)))
            .containsExactly(earlierWriteOff, laterDisbursement);
    }

    @Test
    @DisplayName("ordering does not mutate the caller's list, and the result is immutable")
    void orderingIsPure() {
        List<OrderedEvent> input = new ArrayList<>(List.of(
            event(EventPrecedence.STAGE_CHANGE, 1, "STAGE"),
            event(EventPrecedence.SCHEDULED_RECEIPT, 1, "EMI")));
        List<OrderedEvent> snapshot = List.copyOf(input);

        List<OrderedEvent> ordered = EventOrdering.order(input);

        assertThat(input).isEqualTo(snapshot);
        assertThatThrownBy(() -> ordered.add(event(EventPrecedence.WRITE_OFF, 1, "WO")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the five intra-period steps are the enum's own order, and nothing else encodes it")
    void theStepSequenceIsTheOrdinal() {
        assertThat(Step.sequence()).containsExactly(
            Step.ACCRUE_TO_EVENT_DATE,
            Step.APPLY_CASH,
            Step.APPLY_EVENT,
            Step.RESOLVE_OR_RESTATE,
            Step.ACCRUE_TO_PERIOD_END);
    }

    @Test
    @DisplayName("a period is cut at each event date, and always ends with a period-end segment")
    void segmentsCutThePeriodAtEventDates() {
        LocalDate start = DAY;
        LocalDate end = DAY.plusMonths(1);
        List<OrderedEvent> events = List.of(
            OrderedEvent.of(start.plusDays(10), EventPrecedence.PART_PREPAYMENT, 1, "PP"),
            OrderedEvent.of(start.plusDays(10), EventPrecedence.SCHEDULED_RECEIPT, 1, "EMI"),
            OrderedEvent.of(start.plusDays(20), EventPrecedence.BENCHMARK_RESET, 1, "RESET"));

        List<AccrualSegment> segments = EventOrdering.segments(start, end, events);

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).from()).isEqualTo(start);
        assertThat(segments.get(0).to()).isEqualTo(start.plusDays(10));
        // Both same-date events are on one segment, in applied order — cash first.
        assertThat(segments.get(0).eventsAtEnd()).extracting(OrderedEvent::reference)
            .containsExactly("EMI", "PP");
        assertThat(segments.get(0).periodEnd()).isFalse();
        assertThat(segments.get(1).from()).isEqualTo(start.plusDays(10));
        assertThat(segments.get(1).to()).isEqualTo(start.plusDays(20));
        // The trailing segment always exists, so the caller drives the five steps in one
        // loop with no special case — and a trailing special case is exactly where an
        // accrual gets dropped.
        assertThat(segments.getLast().periodEnd()).isTrue();
        assertThat(segments.getLast().from()).isEqualTo(start.plusDays(20));
        assertThat(segments.getLast().to()).isEqualTo(end);
        assertThat(segments.getLast().eventsAtEnd()).isEmpty();
    }

    @Test
    @DisplayName("an event-free period is one segment covering the whole period")
    void quietPeriodIsOneSegment() {
        List<AccrualSegment> segments = EventOrdering.segments(DAY, DAY.plusMonths(1), List.of());

        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().periodEnd()).isTrue();
        assertThat(segments.getFirst().instantaneous()).isFalse();
    }

    @Test
    @DisplayName("an event on the period boundary gives an instantaneous segment, which earns nothing")
    void eventOnTheBoundaryIsLegitimate() {
        // A Stage 3 migration at the start of a month. The accrue-to-event-date step still
        // runs and correctly earns nothing, which is a better answer than a special case.
        List<AccrualSegment> atStart = EventOrdering.segments(DAY, DAY.plusMonths(1),
            List.of(OrderedEvent.of(DAY, EventPrecedence.STAGE_CHANGE, 1, "STAGE")));

        assertThat(atStart).hasSize(2);
        assertThat(atStart.getFirst().instantaneous()).isTrue();
        assertThat(atStart.getFirst().eventsAtEnd()).hasSize(1);

        // And one on the closing boundary leaves an instantaneous trailing segment rather
        // than dropping the segment altogether.
        List<AccrualSegment> atEnd = EventOrdering.segments(DAY, DAY.plusMonths(1),
            List.of(OrderedEvent.of(DAY.plusMonths(1), EventPrecedence.SCHEDULED_RECEIPT, 1, "EMI")));
        assertThat(atEnd).hasSize(2);
        assertThat(atEnd.getLast().instantaneous()).isTrue();
        assertThat(atEnd.getLast().periodEnd()).isTrue();
    }

    @Test
    @DisplayName("an event outside the period is rejected rather than folded into it")
    void eventsMustBelongToThePeriod() {
        OrderedEvent nextMonth = OrderedEvent.of(
            DAY.plusMonths(2), EventPrecedence.SCHEDULED_RECEIPT, 1, "NEXT-MONTH");

        assertThatThrownBy(() -> EventOrdering.segments(DAY, DAY.plusMonths(1), List.of(nextMonth)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("falls outside period");
    }

    @Test
    @DisplayName("a period that runs backwards is rejected")
    void periodMustNotRunBackwards() {
        assertThatThrownBy(() -> EventOrdering.segments(DAY.plusMonths(1), DAY, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("precedes start");
    }

    @Test
    @DisplayName("segments are identical under every permutation of the events handed in")
    void segmentsAreIndependentOfInputOrder() {
        LocalDate start = DAY;
        List<OrderedEvent> canonical = List.of(
            OrderedEvent.of(start.plusDays(5), EventPrecedence.SCHEDULED_RECEIPT, 1, "EMI"),
            OrderedEvent.of(start.plusDays(5), EventPrecedence.RE_ESTIMATION, 1, "REEST"),
            OrderedEvent.of(start.plusDays(5), EventPrecedence.BENCHMARK_RESET, 1, "RESET"),
            OrderedEvent.of(start.plusDays(12), EventPrecedence.STAGE_CHANGE, 1, "STAGE"));
        List<AccrualSegment> expected = EventOrdering.segments(start, start.plusMonths(1), canonical);

        for (List<OrderedEvent> permutation : permutations(canonical)) {
            assertThat(EventOrdering.segments(start, start.plusMonths(1), permutation)).isEqualTo(expected);
        }
        // And on the day three events share, the restatement is applied before the reset.
        assertThat(expected.getFirst().eventsAtEnd()).extracting(OrderedEvent::reference)
            .containsExactly("EMI", "REEST", "RESET");
    }

    private static <T> List<List<T>> permutations(List<T> source) {
        if (source.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            List<T> remainder = new ArrayList<>(source);
            T head = remainder.remove(index);
            for (List<T> tail : permutations(remainder)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(List.copyOf(permutation));
            }
        }
        return List.copyOf(result);
    }
}
