package com.crisil.eir.policy.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Below-market origination (FR-909, reference § 5 item 11, reference § 4 Silence 6).
 *
 * <p><b>The fixture is a staff housing loan, derived independently.</b> 5,000,000.00 over 240
 * months at a concessional 0.5% per month (6% p.a.) when the market rate for the same risk is
 * 0.75% per month (9% p.a.). Cross-checked in Python at 28 significant digits rather than through
 * the engine:
 *
 * <pre>
 *   EMI at 0.005 over 240 = 5,000,000 / annuity(0.005, 240)   =    35,821.55
 *   fair value = EMI x annuity(0.0075, 240)                   = 3,981,384.85
 *   day-1 shortfall = 5,000,000.00 − 3,981,384.85             = 1,018,615.15
 *   concession spread = 0.0075 − 0.005                        =       0.0025
 * </pre>
 *
 * <p>The shortfall is 20.4% of the amount disbursed. That is the reason the reference marks
 * Silence 6 {@code [MED-HIGH]} and says it "is not theoretical — and for public sector banks the
 * staff book is large": the destination of that figure is not a rounding decision.
 */
class BelowMarketOriginationTest {

    private static final LocalDate ORIGINATED = LocalDate.of(2027, 6, 15);
    private static final Money DISBURSED = Money.inr("5000000.00");
    private static final Money FAIR_VALUE = Money.inr("3981384.85");
    private static final Rate MARKET = Rate.monthly(new BigDecimal("0.0075"));
    private static final Rate CONCESSIONAL = Rate.monthly(new BigDecimal("0.005"));

    private static PolicyVersion position(LocalDate from) {
        return new PolicyVersion("POS-SILENCE-06", PolicyKind.POLICY_POSITION,
            "day-1 below-market difference to employee benefit cost (reference Silence 6)",
            from, "accounting.policy.owner", "board.secretary", LocalDate.of(2027, 5, 1),
            PolicyVersionStatus.EFFECTIVE);
    }

    private static BelowMarketOrigination staffLoan(
        DayOneDifferenceDestination destination, PolicyVersion position) {
        return new BelowMarketOrigination("STAFF-0001", ORIGINATED, DISBURSED, FAIR_VALUE,
            MARKET, CONCESSIONAL, destination, position,
            "valuation.analyst", "valuation.reviewer");
    }

    @Nested
    @DisplayName("the two facts the reference position turns on")
    class Measurement {

        @Test
        @DisplayName("the shortfall is what was given up on day 1")
        void shortfall() {
            // 5,000,000.00 − 3,981,384.85 = 1,018,615.15, derived above and not from the engine.
            BelowMarketOrigination loan = staffLoan(
                DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, position(ORIGINATED));
            assertThat(loan.dayOneShortfall()).isEqualTo(Money.inr("1018615.15"));
        }

        @Test
        @DisplayName("the EIR is the market rate, never the concession")
        void eirIsTheMarketRate() {
            // The half that fails silently. Using the contractual rate as the EIR would amortise
            // the concession back into interest income over twenty years — recognising revenue the
            // bank never priced for — and every period of it would tie, because the arithmetic is
            // internally consistent at the wrong rate.
            BelowMarketOrigination loan = staffLoan(
                DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, position(ORIGINATED));
            assertThat(loan.effectiveInterestRate()).isEqualTo(MARKET);
            assertThat(loan.effectiveInterestRate()).isNotEqualTo(CONCESSIONAL);
            assertThat(loan.concessionSpread())
                .as("0.0075 − 0.005; published because the shortfall alone does not show how far"
                    + " below market the loan is priced")
                .isEqualByComparingTo(new BigDecimal("0.0025"));
        }

        @Test
        @DisplayName("it stores as a fair value measurement, by DCF at the market rate")
        void oneStoragePath() {
            // 04 section 6 leaves transition_fair_value.transition_date unconstrained so a
            // below-market origination uses the same row shape on its own date. The technique is
            // never the paragraph 19 presumption: carrying cost as best evidence is precisely the
            // answer this case contradicts, since the whole point is that fair value differs from
            // the amount advanced.
            TransitionFairValue stored = staffLoan(
                DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, position(ORIGINATED))
                .asFairValueMeasurement();

            assertThat(stored.technique()).isEqualTo(ValuationTechnique.DISCOUNTED_CASH_FLOW);
            assertThat(stored.appliesParagraph19Presumption()).isFalse();
            assertThat(stored.discountRateUsed()).isEqualTo(MARKET);
            assertThat(stored.transitionDate())
                .as("its own day 1, not 1 April 2027")
                .isEqualTo(ORIGINATED);
            assertThat(stored.differenceToOpeningRetainedEarnings())
                .as("the measurement is the same; where the figure GOES is the destination's job,"
                    + " and for a staff loan it is not retained earnings")
                .isEqualTo(Money.inr("-1018615.15"));
            assertThat(stored.paragraph19Evidenced().satisfied()).isTrue();
        }
    }

    @Nested
    @DisplayName("BM-1: the destination is chosen, not defaulted")
    class Destination {

        @Test
        @DisplayName("an approved position in force passes")
        void approved() {
            InvariantResult result = BelowMarketOrigination.destinationsApproved(List.of(
                staffLoan(DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, position(ORIGINATED))));
            assertThat(result.id()).isEqualTo(InvariantId.BM_1);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("no destination chosen fails, and the detail carries the exposed amount")
        void noDestination() {
            // ACPIR says nothing about the day-1 difference (Silence 6), so an unresolved position
            // is the real state of the question while it sits with the sub-committee. Reported
            // rather than refused for that reason — and the figure is what makes it urgent.
            InvariantResult result = BelowMarketOrigination.destinationsApproved(List.of(
                staffLoan(null, null)));
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail())
                .contains("STAFF-0001 (no destination chosen)")
                .contains("totalling INR 1018615.15");
        }

        @Test
        @DisplayName("a position not yet in force on the origination date fails")
        void positionNotInForce() {
            // Approved in May 2027 to take effect in September, on a loan written in June. The
            // version exists and is approved; it did not govern this loan's day 1.
            InvariantResult result = BelowMarketOrigination.destinationsApproved(List.of(
                staffLoan(DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST,
                    position(LocalDate.of(2027, 9, 1)))));
            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail()).contains("POS-SILENCE-06 not in force");
        }

        @Test
        @DisplayName("the count aggregates and the exposure sums")
        void aggregates() {
            // 1,018,615.15 x 2 = 2,037,230.30. One result, because conjunction keeps only the
            // first breach's deviation among same-id results.
            InvariantResult result = BelowMarketOrigination.destinationsApproved(List.of(
                staffLoan(null, null),
                new BelowMarketOrigination("STAFF-0002", ORIGINATED, DISBURSED, FAIR_VALUE,
                    MARKET, CONCESSIONAL, null, null, "valuation.analyst", null),
                staffLoan(DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST,
                    position(ORIGINATED))));

            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(result.detail()).contains("totalling INR 2037230.30");
        }

        @Test
        @DisplayName("retained earnings is inside the transition and outside a current origination")
        void destinationSemantics() {
            // Correct for the day-1 valuation of an existing book at 1 April 2027, wrong for a
            // loan written in June: routing a current-period cost to opening retained earnings
            // puts it outside the current result entirely. The enum states which destinations
            // touch the period; nothing here decides for the entity.
            assertThat(DayOneDifferenceDestination.OPENING_RETAINED_EARNINGS
                .affectsCurrentPeriodResult()).isFalse();
            assertThat(DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST
                .affectsCurrentPeriodResult()).isTrue();
            assertThat(DayOneDifferenceDestination.OTHER_OPERATING_EXPENSE
                .affectsCurrentPeriodResult()).isTrue();
        }
    }

    @Nested
    @DisplayName("premises that contradict themselves are refused")
    class Refusals {

        @Test
        @DisplayName("a fair value above the amount disbursed is not a concession")
        void aboveMarketRefused() {
            // Letting it through would produce a negative "shortfall" reading as compensation
            // recovered from an employee.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BelowMarketOrigination("X", ORIGINATED,
                    Money.inr("1000000.00"), Money.inr("1050000.00"), MARKET, CONCESSIONAL,
                    null, null, "valuation.analyst", null))
                .withMessageContaining("a day-1 gain, not a below-market concession");
        }

        @Test
        @DisplayName("a market rate below the contractual rate contradicts the premise")
        void marketBelowContractualRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BelowMarketOrigination("X", ORIGINATED,
                    DISBURSED, FAIR_VALUE, CONCESSIONAL, MARKET,
                    null, null, "valuation.analyst", null))
                .withMessageContaining("a loan priced above market is not a concession");
        }

        @Test
        @DisplayName("the silence is closed by a policy position, not by another kind of version")
        void wrongKindRefused() {
            // The makers of a fee rule set were not deciding where a day-1 staff-loan shortfall
            // goes. Same argument PoolDefinition makes, and the reason POLICY_POSITION had to be
            // added to PolicyKind: a Board position closing an ACPIR silence was previously
            // representable only by borrowing a kind whose approvers were deciding something else.
            PolicyVersion feeRuleSet = new PolicyVersion("FEE-2027.1", PolicyKind.FEE_RULE_SET,
                "unrelated", ORIGINATED, "policy.author", "accounting.policy.owner",
                LocalDate.of(2027, 5, 1), PolicyVersionStatus.EFFECTIVE);

            assertThatIllegalArgumentException()
                .isThrownBy(() -> staffLoan(
                    DayOneDifferenceDestination.EMPLOYEE_BENEFIT_COST, feeRuleSet))
                .withMessageContaining("were not deciding this");
        }

        @Test
        @DisplayName("a position approving no destination in particular is not an approval")
        void positionWithoutDestination() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> staffLoan(null, position(ORIGINATED)))
                .withMessageContaining("approving nothing in particular is not an approval");
        }

        @Test
        @DisplayName("a valuation reviewed by whoever performed it is refused, through FourEyes")
        void selfReviewRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BelowMarketOrigination("X", ORIGINATED,
                    DISBURSED, FAIR_VALUE, MARKET, CONCESSIONAL, null, null,
                    "valuation.analyst", "Valuation.Analyst"))
                .withMessageContaining("the same single judgement it started with");
        }
    }
}
