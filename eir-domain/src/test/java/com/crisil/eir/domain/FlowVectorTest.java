package com.crisil.eir.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link FlowVector} is the boundary between projection and solving: everything
 * the solver knows about a contract arrives through it, so its invariants are the
 * solver's preconditions.
 *
 * <p>Three of those invariants carry most of the weight here. Order is
 * (date, periodIndex) and the period index is <em>explicit</em>, because two
 * flows can legitimately share a date and deriving the ordinal from position
 * would give the second one the wrong discount factor. Nothing may predate the
 * anchor, because a negative tau is a projection defect rather than a
 * compounding instruction. And periodic indexing is licensed by a checked
 * property of the vector, never assumed.
 */
class FlowVectorTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 4, 15);
    private static final Currency USD = Currency.getInstance("USD");

    private static CashFlow flow(LocalDate date, int periodIndex, String amount, FlowKind kind) {
        return CashFlow.of(date, periodIndex, Money.inr(amount), kind);
    }

    /** A clean five-year-shaped monthly vector: disbursement then {@code n} instalments. */
    private static FlowVector uniformMonthly(int instalments) {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT));
        for (int i = 1; i <= instalments; i++) {
            flows.add(flow(ANCHOR.plusMonths(i), i, "11122.22", FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(ANCHOR, Money.INR, flows);
    }

    // ---------------------------------------------------------------- ordering

    @Nested
    @DisplayName("order is (date, periodIndex) and the index is explicit, not positional")
    class Ordering {

        @Test
        void constructorSortsByDateThenPeriodIndex() {
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                flow(ANCHOR.plusMonths(3), 3, "11122.22", FlowKind.COMBINED_EMI),
                flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT),
                flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI),
                flow(ANCHOR.plusMonths(2), 2, "11122.22", FlowKind.COMBINED_EMI)));

            assertThat(vector.flows())
                .extracting(CashFlow::periodIndex)
                .containsExactly(0, 1, 2, 3);
            assertThat(vector.flows())
                .extracting(CashFlow::date)
                .containsExactly(ANCHOR, ANCHOR.plusMonths(1), ANCHOR.plusMonths(2), ANCHOR.plusMonths(3));
        }

        @Test
        @DisplayName("two flows on one date, different indices: both retained, in index order (B5.4.4)")
        void twoFlowsOnTheSameDateAreBothKept() {
            // The repricing shortcut puts an instalment and a notional redemption on
            // the same reset date. Deriving the ordinal from position in the vector
            // would discount the second at the first one's factor; the explicit
            // index is what stops that, and nothing here may collapse the pair.
            LocalDate reset = ANCHOR.plusMonths(6);
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                flow(reset, 7, "500000.00", FlowKind.NOTIONAL_REDEMPTION),
                flow(reset, 6, "11122.22", FlowKind.COMBINED_EMI),
                flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT)));

            assertThat(vector.size()).isEqualTo(3);
            assertThat(vector.flows())
                .extracting(CashFlow::periodIndex)
                .containsExactly(0, 6, 7);
            assertThat(vector.flows().get(1).kind()).isEqualTo(FlowKind.COMBINED_EMI);
            assertThat(vector.flows().get(2).kind()).isEqualTo(FlowKind.NOTIONAL_REDEMPTION);
            assertThat(vector.flows().get(1).date()).isEqualTo(vector.flows().get(2).date());
            assertThat(vector.maxPeriodIndex()).isEqualTo(7);
        }

        @Test
        @DisplayName("flows sharing a date and an index are both retained — no deduplication")
        void flowsSharingDateAndIndexAreBothKept() {
            // A fee received and an instalment can genuinely fall in the same period
            // on the same day; summing them here would destroy the audit trail of
            // what each figure was.
            LocalDate date = ANCHOR.plusMonths(1);
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                flow(date, 1, "11122.22", FlowKind.COMBINED_EMI),
                flow(date, 1, "2500.00", FlowKind.INTEGRAL_FEE_RECEIVED)));

            assertThat(vector.size()).isEqualTo(2);
            assertThat(vector.total().amount()).isEqualByComparingTo("13622.22");
        }
    }

    // -------------------------------------------------------------- validation

    @Nested
    class Validation {

        @Test
        void aFlowInTheWrongCurrencyIsRejected() {
            // A mixed-currency vector has no meaningful present value, and there is
            // no rate in this module to convert with.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVector.of(ANCHOR, Money.INR, List.of(
                    flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT),
                    CashFlow.of(ANCHOR.plusMonths(1), 1, Money.of("11122.22", USD),
                        FlowKind.COMBINED_EMI))))
                .withMessageContaining("USD")
                .withMessageContaining("INR");
        }

        @Test
        void aFlowBeforeTheAnchorIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> FlowVector.of(ANCHOR, Money.INR, List.of(
                    flow(ANCHOR.minusDays(1), 0, "-495000.00", FlowKind.DISBURSEMENT))))
                .withMessageContaining("precedes anchor")
                .withMessageContaining("2026-04-14")
                .withMessageContaining("2026-04-15");
        }

        @Test
        void nullsAreNamed() {
            assertThatNullPointerException()
                .isThrownBy(() -> new FlowVector(null, Money.INR, List.of()))
                .withMessageContaining("anchorDate");
            assertThatNullPointerException()
                .isThrownBy(() -> new FlowVector(ANCHOR, null, List.of()))
                .withMessageContaining("currency");
            assertThatNullPointerException()
                .isThrownBy(() -> new FlowVector(ANCHOR, Money.INR, null))
                .withMessageContaining("flows");
        }

        @Test
        void theVectorIsImmutableAndDoesNotAliasTheCallersList() {
            List<CashFlow> mutable = new ArrayList<>();
            mutable.add(flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT));
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, mutable);

            mutable.add(flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI));

            assertThat(vector.size()).isEqualTo(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> vector.flows()
                    .add(flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI)));
        }
    }

    // ------------------------------------------------------------- partitions

    @Nested
    @DisplayName("inception and future partition the vector — the discounting boundary")
    class Partitions {

        @Test
        void futureIsStrictlyAfterTheAnchorAndInceptionIsOnIt() {
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                flow(ANCHOR, 0, "-500000.00", FlowKind.DISBURSEMENT),
                flow(ANCHOR, 0, "7500.00", FlowKind.INTEGRAL_FEE_RECEIVED),
                flow(ANCHOR, 0, "-2500.00", FlowKind.INTEGRAL_COST_PAID),
                flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI),
                flow(ANCHOR.plusMonths(2), 2, "11122.22", FlowKind.COMBINED_EMI)));

            assertThat(vector.atInception()).hasSize(3);
            assertThat(vector.atInception()).allSatisfy(f -> assertThat(f.date()).isEqualTo(ANCHOR));
            assertThat(vector.future()).hasSize(2);
            assertThat(vector.future()).allSatisfy(f -> assertThat(f.date()).isAfter(ANCHOR));
            assertThat(vector.atInception().size() + vector.future().size()).isEqualTo(vector.size());

            // The inception net is the initial carrying amount; the future flows are
            // the ones that get discounted. Nothing is in both sets and nothing is in
            // neither.
            Money inceptionNet = vector.atInception().stream()
                .map(CashFlow::amount)
                .reduce(Money.zero(Money.INR), Money::plus);
            assertThat(inceptionNet.amount()).isEqualByComparingTo("-495000.00");
        }

        @Test
        void anEmptyVectorIsEmptyEverywhere() {
            FlowVector empty = FlowVector.of(ANCHOR, Money.INR, List.of());
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.size()).isZero();
            assertThat(empty.future()).isEmpty();
            assertThat(empty.atInception()).isEmpty();
            assertThat(empty.total().amount()).isEqualByComparingTo("0");
            assertThat(empty.maxPeriodIndex()).isZero();
            assertThat(empty.periodicIndexEligible(12)).isFalse();
        }

        @Test
        void totalIsTheSignedSumAndMaxPeriodIndexTheHighestOrdinal() {
            FlowVector vector = uniformMonthly(6);
            // -495,000 advanced against six instalments of 11,122.22.
            assertThat(vector.total().amount()).isEqualByComparingTo("-428266.68");
            assertThat(vector.maxPeriodIndex()).isEqualTo(6);
            assertThat(vector.size()).isEqualTo(7);
            assertThat(vector.isEmpty()).isFalse();
        }
    }

    // ------------------------------------------------------- contingent flows

    @Nested
    @DisplayName("contingent flows are excluded at the boundary, not at the projector's discretion")
    class ContingentFlows {

        @Test
        void aCleanVectorPassesThroughUnchanged() {
            FlowVector vector = uniformMonthly(3);
            assertThat(vector.requireNoContingentFlows()).isSameAs(vector);
        }

        @Test
        void aContingentFlowIsRejectedAndNamed() {
            // A prepayment penalty or bounce charge is contractually specified and
            // still excluded: sweeping every contractual charge into the projection
            // overstates yield across the whole book.
            CashFlow penalty = CashFlow.contingent(
                ANCHOR.plusMonths(2), 2, Money.inr("5000.00"), FlowKind.INTEGRAL_FEE_RECEIVED);
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT),
                flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI),
                penalty));

            assertThatIllegalStateException()
                .isThrownBy(vector::requireNoContingentFlows)
                .withMessageContaining("contingent flows must not enter the EIR projection")
                .withMessageContaining("2026-06-15")
                .withMessageContaining("INTEGRAL_FEE_RECEIVED")
                .withMessageContaining("5000.00");
        }

        @Test
        void everyContingentFlowIsNamedNotJustTheFirst() {
            FlowVector vector = FlowVector.of(ANCHOR, Money.INR, List.of(
                CashFlow.contingent(ANCHOR.plusMonths(1), 1, Money.inr("1000.00"),
                    FlowKind.INTEGRAL_FEE_RECEIVED),
                CashFlow.contingent(ANCHOR.plusMonths(2), 2, Money.inr("2000.00"),
                    FlowKind.EXPECTED_PREPAYMENT)));

            assertThatIllegalStateException()
                .isThrownBy(vector::requireNoContingentFlows)
                .withMessageContaining("1000.00")
                .withMessageContaining("2000.00");
        }
    }

    // ------------------------------------------------- periodic-index licence

    @Nested
    @DisplayName("periodic indexing is licensed by a checked property, never assumed")
    class PeriodicIndexLicence {

        @Test
        void aUniformMonthlyVectorIsEligible() {
            assertThat(uniformMonthly(6).periodicIndexEligible(12)).isTrue();
            assertThat(uniformMonthly(60).periodicIndexEligible(12)).isTrue();
        }

        @Test
        void aUniformQuarterlyVectorIsEligibleAtItsOwnFrequency() {
            List<CashFlow> flows = new ArrayList<>();
            flows.add(flow(ANCHOR, 0, "-1000000.00", FlowKind.DISBURSEMENT));
            for (int i = 1; i <= 8; i++) {
                flows.add(flow(ANCHOR.plusMonths(3L * i), i, "140000.00", FlowKind.COMBINED_EMI));
            }
            FlowVector quarterly = FlowVector.of(ANCHOR, Money.INR, flows);

            assertThat(quarterly.periodicIndexEligible(4)).isTrue();
            // The same vector is not a monthly vector: at twelve periods a year the
            // flows no longer sit on boundaries.
            assertThat(quarterly.periodicIndexEligible(12)).isFalse();
        }

        @Test
        @DisplayName("a broken first period voids the licence")
        void aBrokenFirstPeriodIsNotEligible() {
            // Disbursement on the 15th, first instalment on the following month's 5th:
            // the commonest real shape, and exactly the case where periodic indexing
            // would silently misdate every flow.
            List<CashFlow> flows = new ArrayList<>();
            flows.add(flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT));
            flows.add(flow(LocalDate.of(2026, 5, 5), 1, "11122.22", FlowKind.COMBINED_EMI));
            flows.add(flow(LocalDate.of(2026, 6, 5), 2, "11122.22", FlowKind.COMBINED_EMI));

            assertThat(FlowVector.of(ANCHOR, Money.INR, flows).periodicIndexEligible(12)).isFalse();
        }

        @Test
        @DisplayName("a mid-period flow voids the licence")
        void aFlowOffTheBoundaryIsNotEligible() {
            List<CashFlow> flows = new ArrayList<>();
            flows.add(flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT));
            flows.add(flow(ANCHOR.plusMonths(1), 1, "11122.22", FlowKind.COMBINED_EMI));
            // A part-prepayment ten days into period 2 — on a boundary date it would
            // be eligible; ten days in, it is not.
            flows.add(flow(ANCHOR.plusMonths(1).plusDays(10), 2, "50000.00",
                FlowKind.EXPECTED_PREPAYMENT));
            flows.add(flow(ANCHOR.plusMonths(2), 2, "11122.22", FlowKind.COMBINED_EMI));

            assertThat(FlowVector.of(ANCHOR, Money.INR, flows).periodicIndexEligible(12)).isFalse();
        }

        @Test
        @DisplayName("a missing period voids the licence — a moratorium is the live case")
        void aGapInThePeriodSequenceIsNotEligible() {
            // Periods 1, 2, 4, 5: no flow in period 3. The exponents would still be
            // arithmetically valid, so nothing but this check catches it.
            List<CashFlow> flows = new ArrayList<>();
            flows.add(flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT));
            for (int i : new int[] {1, 2, 4, 5}) {
                flows.add(flow(ANCHOR.plusMonths(i), i, "11122.22", FlowKind.COMBINED_EMI));
            }

            assertThat(FlowVector.of(ANCHOR, Money.INR, flows).periodicIndexEligible(12)).isFalse();
        }

        @ParameterizedTest(name = "periodsPerYear = {0}")
        @ValueSource(ints = {5, 7, 8, 9, 10, 11, 13, 24, 365})
        @DisplayName("a frequency that does not divide twelve cannot be a monthly-boundary index")
        void aNonDivisorFrequencyIsNotEligible(int periodsPerYear) {
            assertThat(uniformMonthly(6).periodicIndexEligible(periodsPerYear)).isFalse();
        }

        @ParameterizedTest(name = "periodsPerYear = {0}")
        @ValueSource(ints = {0, -1, -12})
        void aFrequencyBelowOneIsNotEligible(int periodsPerYear) {
            assertThat(uniformMonthly(6).periodicIndexEligible(periodsPerYear)).isFalse();
        }

        @Test
        void aFutureFlowWithoutAPeriodOrdinalIsNotEligible() {
            // periodIndex 0 means "at inception"; a future flow carrying it is a
            // projection defect and must not be handed a tau of zero.
            List<CashFlow> flows = List.of(
                flow(ANCHOR, 0, "-495000.00", FlowKind.DISBURSEMENT),
                flow(ANCHOR.plusMonths(1), 0, "11122.22", FlowKind.COMBINED_EMI));

            assertThat(FlowVector.of(ANCHOR, Money.INR, flows).periodicIndexEligible(12)).isFalse();
        }

        @Test
        @DisplayName("a month-end schedule is eligible only where the dates are anchor-plus-n-months")
        void monthEndSchedulesFollowThePlusMonthsClamp() {
            // Anchored on 31 January, the boundaries are 28 Feb, 31 Mar, 30 Apr:
            // LocalDate.plusMonths clamps to the month end, and a projector that
            // generates boundaries the same way stays eligible even though the
            // periods are 28 and 31 actual days long. Uniformity here is uniformity
            // in periods, which is what the periodic-index convention discounts on.
            LocalDate janEnd = LocalDate.of(2026, 1, 31);
            FlowVector clamped = FlowVector.of(janEnd, Money.INR, List.of(
                CashFlow.of(janEnd, 0, Money.inr("-100000.00"), FlowKind.DISBURSEMENT),
                CashFlow.of(LocalDate.of(2026, 2, 28), 1, Money.inr("34000.00"), FlowKind.COMBINED_EMI),
                CashFlow.of(LocalDate.of(2026, 3, 31), 2, Money.inr("34000.00"), FlowKind.COMBINED_EMI),
                CashFlow.of(LocalDate.of(2026, 4, 30), 3, Money.inr("34000.00"), FlowKind.COMBINED_EMI)));
            assertThat(clamped.periodicIndexEligible(12)).isTrue();

            // A projector that rolls forward from the PREVIOUS date instead of the
            // anchor drifts off the boundary after February and loses the licence —
            // which is the drift the check exists to catch.
            FlowVector drifted = FlowVector.of(janEnd, Money.INR, List.of(
                CashFlow.of(janEnd, 0, Money.inr("-100000.00"), FlowKind.DISBURSEMENT),
                CashFlow.of(LocalDate.of(2026, 2, 28), 1, Money.inr("34000.00"), FlowKind.COMBINED_EMI),
                CashFlow.of(LocalDate.of(2026, 3, 28), 2, Money.inr("34000.00"), FlowKind.COMBINED_EMI),
                CashFlow.of(LocalDate.of(2026, 4, 28), 3, Money.inr("34000.00"), FlowKind.COMBINED_EMI)));
            assertThat(drifted.periodicIndexEligible(12)).isFalse();
        }

        @Test
        @DisplayName("eligibility is a property of the vector, and the eligible case discounts identically")
        void whereEligibleTheTwoConventionsAgree() {
            // The claim the optimisation rests on, checked numerically on an eligible
            // vector: the periodic-index tau and the equivalent power of the annual
            // effective rate give the same discount factor.
            FlowVector vector = uniformMonthly(12);
            assertThat(vector.periodicIndexEligible(12)).isTrue();
            TimeConvention periodic = TimeConvention.PeriodicIndex.monthly();
            CashFlow last = vector.flows().get(vector.size() - 1);
            assertThat(periodic.tau(ANCHOR, last)).isEqualByComparingTo("12");
            assertThat(periodic.periodsPerYear()).isEqualTo(12);
        }
    }
}
