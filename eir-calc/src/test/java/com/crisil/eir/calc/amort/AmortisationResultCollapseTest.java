package com.crisil.eir.calc.amort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AmortisationResult#asOneAccrualPeriod(int)} — several accrual periods reported as one
 * accounting period.
 *
 * <h2>Where every expected figure comes from</h2>
 *
 * <p><b>Nothing below was read off a run of this code.</b> The fixture is four weekly accruals on a
 * 100,000.00 balance at a weekly periodic rate of exactly {@code 0.0025} — chosen as a round figure
 * so the arithmetic is checkable by eye rather than only by a calculator — with a 500.00 receipt in
 * the third week and nothing in the others. Each row is stated at working precision and every
 * expected total is the hand sum:
 *
 * <table>
 *   <caption>The four weekly rows, derived by hand</caption>
 *   <tr><th>Week</th><th>Opening</th><th>Interest = opening × 0.0025</th><th>Cash</th>
 *       <th>Closing</th></tr>
 *   <tr><td>1</td><td>100,000.00</td><td>250.00</td><td>0.00</td><td>100,250.00</td></tr>
 *   <tr><td>2</td><td>100,250.00</td><td>250.625</td><td>0.00</td><td>100,500.625</td></tr>
 *   <tr><td>3</td><td>100,500.625</td><td>251.2515625</td><td>500.00</td>
 *       <td>100,251.8765625</td></tr>
 *   <tr><td>4</td><td>100,251.8765625</td><td>250.62969140625</td><td>0.00</td>
 *       <td>100,502.50625390625</td></tr>
 * </table>
 *
 * <ul>
 *   <li><b>Interest summed</b> = 250.00 + 250.625 + 251.2515625 + 250.62969140625 =
 *       <b>1,002.50625390625</b>.</li>
 *   <li><b>Cash summed</b> = <b>500.00</b>, the single third-week receipt.</li>
 *   <li><b>The collapsed closing</b> = 100,000.00 + 1,002.50625390625 − 500.00 =
 *       <b>100,502.50625390625</b>, which is week 4's closing. That equality is the telescoping
 *       property and it is the whole point: it is asserted, not assumed.</li>
 *   <li><b>The accrual exponent</b> = 1 + 1 + 1 + 1 = <b>4</b>. Four fifty-secondths of a year is
 *       what elapsed; the last row's exponent alone would report one week for a month's accrual.</li>
 * </ul>
 *
 * <p>Note that the interest column does <em>not</em> equal 4 × 250.00. It compounds, and the
 * receipt in week 3 reduces the balance the fourth week accrues on. A fixture whose weeks were all
 * equal would pass a test that summed anything four times.
 */
class AmortisationResultCollapseTest {

    private static final LocalDate WEEK_1 = LocalDate.of(2027, 4, 7);
    private static final LocalDate WEEK_4 = LocalDate.of(2027, 4, 28);

    /** Interest summed across the four weeks. See the class javadoc for the derivation. */
    private static final String INTEREST_SUM = "1002.50625390625";

    /** Week 4's closing balance, which the collapsed row must reproduce exactly. */
    private static final String CLOSING = "100502.50625390625";

    private static AmortisationRow week(
        int ordinal, LocalDate date, String opening, String interest, String cash) {
        return AmortisationRow.of(
            ordinal, date, BigDecimal.ONE,
            Money.inr(opening), Money.inr(interest), Money.inr(cash));
    }

    /** The four weekly rows of the class javadoc's table, in order. */
    private static List<AmortisationRow> fourWeeks() {
        return List.of(
            week(1, WEEK_1, "100000.00", "250.00", "0.00"),
            week(2, LocalDate.of(2027, 4, 14), "100250.00", "250.625", "0.00"),
            week(3, LocalDate.of(2027, 4, 21), "100500.625", "251.2515625", "500.00"),
            week(4, WEEK_4, "100251.8765625", "250.62969140625", "0.00"));
    }

    private static AmortisationResult resultOf(List<AmortisationRow> rows) {
        Money interest = Money.zero(Money.INR);
        Money cash = Money.zero(Money.INR);
        for (AmortisationRow row : rows) {
            interest = interest.plus(row.interestAccrued());
            cash = cash.plus(row.cashReceived());
        }
        return new AmortisationResult(
            rows, interest, cash, rows.getLast().closingGca(), List.<InvariantResult>of());
    }

    @Nested
    @DisplayName("four weekly accruals inside one accounting month")
    class AWeeklyContract {

        @Test
        @DisplayName("the collapsed row carries the first opening, the last closing and both sums")
        void theCollapsedRowIsTheWholeMonth() {
            AmortisationRow month = resultOf(fourWeeks()).asOneAccrualPeriod(7);

            assertThat(month.period())
                .as("the ACCOUNTING period's ordinal, not any row's; the rows count accrual"
                    + " boundaries from inception and the close counts accounting periods")
                .isEqualTo(7);
            assertThat(month.openingGca().amount())
                .as("week 1's opening, because that is where the month started")
                .isEqualByComparingTo(new BigDecimal("100000.00"));
            assertThat(month.interestAccrued().amount())
                .as("the four weeks summed at working precision, NOT 4 x 250.00: the balance"
                    + " compounds weekly and the week-3 receipt reduces what week 4 accrues on")
                .isEqualByComparingTo(new BigDecimal(INTEREST_SUM));
            assertThat(month.cashReceived().amount())
                .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(month.closingGca().amount())
                .as("week 4's closing, reached by summing the movements rather than by reading it"
                    + " -- the telescoping property, which is what makes the summary meaningful")
                .isEqualByComparingTo(new BigDecimal(CLOSING));
            assertThat(month.date())
                .as("the last boundary's date, because that is when the closing balance IS the"
                    + " closing balance")
                .isEqualTo(WEEK_4);
            assertThat(month.accrualExponent())
                .as("elapsed accrual time summed: four weekly periods are four of them. The last"
                    + " row's exponent alone would report one week for a month's accrual")
                .isEqualByComparingTo(new BigDecimal("4"));
        }

        @Test
        @DisplayName("no precision is lost, so a collapsed month carries the same balance forward")
        void theBalanceCarriedForwardIsIdentical() {
            // The property that makes this safe to publish: a contract closed as one monthly
            // movement must open the next month exactly where four weekly movements would have
            // left it. Any rounding inside the summation would break this by fractions of a paise
            // per period, compounding for the life of the contract.
            AmortisationResult weekly = resultOf(fourWeeks());

            assertThat(weekly.asOneAccrualPeriod(7).closingGca())
                .as("the collapsed closing must EQUAL the roll's terminal balance, not merely"
                    + " round to it")
                .isEqualTo(weekly.terminalBalance());
        }

        @Test
        @DisplayName("the four rows survive on the result, for FR-808's trace")
        void theRowsAreNotCollapsedAway() {
            AmortisationResult weekly = resultOf(fourWeeks());
            weekly.asOneAccrualPeriod(7);

            assertThat(weekly.periods())
                .as("asOneAccrualPeriod returns a projection for the close; a reader asking how one"
                    + " monthly figure arose must still be able to see the four weekly accruals")
                .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("the cases that must not be summarised")
    class Refusals {

        @Test
        @DisplayName("a single row for the same ordinal comes back untouched")
        void oneRowIsItself() {
            // The ordinary monthly contract, which is the overwhelming majority of the book. It
            // must take the identity path: constructing a new row for it would re-derive a figure
            // that is already correct, and any difference between the two would be a defect
            // introduced by a summary of one thing.
            AmortisationRow only = week(7, WEEK_4, "100000.00", "250.00", "0.00");

            assertThat(resultOf(List.of(only)).asOneAccrualPeriod(7))
                .as("identical object, not an equal reconstruction")
                .isSameAs(only);
        }

        @Test
        @DisplayName("a single row under a different ordinal is re-stamped, not returned as-is")
        void oneRowUnderAnotherOrdinalIsRestamped() {
            // The accrual boundary is period 3 of the contract's own schedule and the close is
            // reporting accounting period 7. Returning the row unchanged would publish a movement
            // labelled with the wrong period, which is the mapping error the ordinal argument
            // exists to make visible.
            AmortisationRow contractPeriodThree =
                week(3, WEEK_4, "100000.00", "250.00", "0.00");

            AmortisationRow month = resultOf(List.of(contractPeriodThree)).asOneAccrualPeriod(7);

            assertThat(month.period()).isEqualTo(7);
            assertThat(month.closingGca()).isEqualTo(contractPeriodThree.closingGca());
        }

        @Test
        @DisplayName("no accrual boundary at all is refused, not reported as a nil movement")
        void noRowsIsRefused() {
            AmortisationResult empty = new AmortisationResult(
                List.of(), Money.zero(Money.INR), Money.zero(Money.INR),
                Money.inr("100000.00"), List.<InvariantResult>of());

            assertThatThrownBy(() -> empty.asOneAccrualPeriod(7))
                .as("a nil row would publish a contract that accrued nothing and reconciled"
                    + " perfectly, which is the shape of break nobody investigates")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no accrual boundaries")
                .hasMessageContaining("reconciled perfectly");
        }

        /**
         * <b>The guard that makes the summation exact lives in the constructor, not in
         * {@code asOneAccrualPeriod}, and this test is where that fact is pinned.</b>
         *
         * <p>A telescoping check was written inside {@code asOneAccrualPeriod} first — compare the
         * collapsed closing against the last row's — and it proved unreachable: this record's
         * constructor already refuses a rows list whose balances do not chain, so no
         * {@code AmortisationResult} carrying a discontinuity can be constructed at all. The
         * unreachable check was removed rather than kept as reassurance, and the real control is
         * asserted here.
         *
         * <p>It is worth asserting precisely because the collapse depends on it. Each of the two
         * rows below satisfies its <em>own</em> roll-forward identity, so {@link AmortisationRow}
         * admits both and the defect is invisible one row at a time. Summed, they would give
         * 100,000.00 + 500.00 = 100,500.00 against the last row's 200,250.00 — a published balance
         * that came from nowhere. "The rows agree with their movements" and "the rows form one
         * roll-forward" are two different claims; every row's constructor enforces the first, and
         * only this enforces the second.
         */
        @Test
        @DisplayName("a result whose rows do not form one continuous roll cannot be built at all")
        void aDiscontinuityIsRefused() {
            List<AmortisationRow> broken = List.of(
                week(1, WEEK_1, "100000.00", "250.00", "0.00"),
                week(2, WEEK_4, "200000.00", "250.00", "0.00"));

            assertThatThrownBy(() -> resultOf(broken))
                .as("refused at construction, which is stronger than refusing the summary: the"
                    + " discontinuity cannot reach any consumer, not merely this one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance chain broken at period 2")
                .hasMessageContaining("does not continue");
        }
    }
}
