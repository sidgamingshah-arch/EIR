package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code YYYYMM} encoding and, mainly, the year boundary.
 *
 * <p>Every expected value below is written out by hand. The January case is the one that matters: the
 * opening position for a period is the closing position of the period before it, and the naive
 * {@code periodId - 1} gives 202700 for January 2027 — a period id V1 and V2 both refuse. The
 * consequence would not look like an encoding bug. {@link JdbcContractStateSource} returns
 * {@code Optional.empty()} where no prior balance is visible, so a wrong prior period would
 * quarantine every contract in the book under FR-905, once a year, in January.
 *
 * <p>The other half of the class — {@link PeriodId#elapsedPeriods} — places a business date in the
 * CONTRACT's own schedule, which is a different calendar from the reporting one of 04 § 2.13. Every
 * count in that nest is derived from a single rule, applied by listing the due dates on paper: due
 * date <i>k</i> is {@code firstDueDate + (k − 1) × step}, and the answer is how many of them fall
 * <b>strictly before</b> the business date. {@code JdbcContractPeriodSource.periodOrdinal} adds one,
 * so the ordinal names the accrual window {@code ContractTerms.dueDate(ordinal − 1)} to
 * {@code dueDate(ordinal)} — and {@code dueDate(0)} is the disbursement date, which is why the
 * period a date at or before the first instalment sits in is 1 and not 0. An ordinal off by one
 * makes {@code ContractPipeline}'s accrual length disagree with the roll-forward's for every period
 * of every contract whose due day is not the 1st, which is exactly the disagreement invariant ST-2
 * exists to catch — so it must not be a disagreement the engine manufactures itself.
 */
class PeriodIdTest {

    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        @DisplayName("April 2027 — the first ACPIR period — encodes as 202704")
        void encodesTheFirstAcpirPeriod() {
            // ACPIR is effective 1 April 2027; V2 spells out period_balance_p202704 for that reason.
            assertThat(PeriodId.of(LocalDate.of(2027, 4, 30))).isEqualTo(202704);
            assertThat(PeriodId.of(LocalDate.of(2027, 4, 1))).isEqualTo(202704);
        }

        @Test
        @DisplayName("a single-digit month is zero-padded by the arithmetic, not by a format")
        void singleDigitMonthsEncodeWithoutAFormatter() {
            assertThat(PeriodId.of(LocalDate.of(2027, 1, 31))).isEqualTo(202701);
            assertThat(PeriodId.of(LocalDate.of(2027, 9, 30))).isEqualTo(202709);
        }

        @Test
        @DisplayName("the period's first and last calendar days")
        void startAndEnd() {
            assertThat(PeriodId.startOf(202704)).isEqualTo(LocalDate.of(2027, 4, 1));
            assertThat(PeriodId.endOf(202704)).isEqualTo(LocalDate.of(2027, 4, 30));
            // February 2028 is a leap February: 29 days. Written out by hand because a hard-coded
            // 28 is the classic form of this bug and it survives every test that uses April.
            assertThat(PeriodId.endOf(202802)).isEqualTo(LocalDate.of(2028, 2, 29));
        }
    }

    @Nested
    @DisplayName("the previous period")
    class Previous {

        @Test
        @DisplayName("within a year it is one less")
        void withinAYear() {
            assertThat(PeriodId.previous(202704)).isEqualTo(202703);
            assertThat(PeriodId.previous(202712)).isEqualTo(202711);
        }

        @Test
        @DisplayName("January's previous period is the previous December, not YYYY00")
        void januaryRollsBackAYear() {
            // The whole reason this method exists. 202701 - 1 is 202700, which fails
            // ck_accounting_period_id_shape and matches no partition.
            assertThat(PeriodId.previous(202701)).isEqualTo(202612);
            assertThat(PeriodId.previous(202801)).isEqualTo(202712);
        }

        @Test
        @DisplayName("stepping back twelve times lands on the same month a year earlier")
        void twelveStepsIsOneYear() {
            int period = 202704;
            for (int i = 0; i < 12; i++) {
                period = PeriodId.previous(period);
            }
            assertThat(period).isEqualTo(202604);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a month of 00 or 13 is refused, as V2's shape check refuses it")
        void refusesAMonthOutsideOneToTwelve() {
            assertThatThrownBy(() -> PeriodId.previous(202700))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("202700")
                .hasMessageContaining("YYYYMM");
            assertThatThrownBy(() -> PeriodId.startOf(202713))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("202713");
        }

        @Test
        @DisplayName("a year before 1900 is refused, matching the DDL's lower bound")
        void refusesAnImplausibleYear() {
            // V1: period_id BETWEEN 190001 AND 999912. A four-digit id like 2704 would otherwise
            // decode as year 27, month 4 and silently address a partition nobody provisioned.
            assertThatThrownBy(() -> PeriodId.startOf(2704))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2704");
        }
    }

    @Nested
    @DisplayName("placing a date in the contract's own schedule")
    class ElapsedPeriods {

        @Test
        @DisplayName("monthly: 30 April 2027 to 30 April 2028 is twelve closed periods")
        void monthlyPeriods() {
            // Due dates from 30 April 2027, monthly: 30 Apr, 30 May, 30 Jun, 30 Jul, 30 Aug,
            // 30 Sep, 30 Oct, 30 Nov, 30 Dec 2027, 30 Jan 2028, 29 Feb 2028 (a leap February has
            // 29 days, so the 30th clamps to it), 30 Mar 2028. Twelve dates strictly before
            // 30 April 2028. The thirteenth IS 30 April 2028: a due date falling on the business
            // date has not closed by it. So 12, and an ordinal of 13.
            //
            // The input that breaks it: counting the due date that lands on the business date as
            // closed answers 13, and the accrual window would then begin where the boundary ends.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2028, 4, 30),
                CompoundingBasis.stepOf("MONTHLY"))).isEqualTo(12);
        }

        @Test
        @DisplayName("quarterly: the count moves at the due date, not at the month")
        void quarterlyMovesAtTheDueDate() {
            Period quarterly = CompoundingBasis.stepOf("QUARTERLY");
            // Due dates from 30 April 2027, quarterly: 30 Apr 2027, 30 Jul 2027, 30 Oct 2027.
            //
            // 30 June 2027 is two months in, inside the quarter running 30 Apr to 30 Jul. One due
            // date — 30 Apr — is strictly before it, so one period has closed and the ordinal is 2:
            // ContractTerms.dueDate(1) = 30 Apr to dueDate(2) = 30 Jul is the window containing the
            // boundary.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 30), quarterly)).isEqualTo(1);
            // THE OLD TEST ASSERTED 0 FOR THIS INPUT, under the comment "a part quarter has not
            // elapsed". That is a contract change and not a rename. periodsBetween(first, business,
            // months) divided elapsed months by months-per-period — 2 / 3 = 0 — which counts whole
            // STEPS since the first due date and so numbered the quarter 30 Apr to 30 Jul as period
            // 1. But period 1 is the stub from disbursement to the first instalment:
            // ContractTerms.dueDate(0) is the disbursement date, not the first due date. Under the
            // old count the accrual window for this boundary read disbursement to 30 Apr, which
            // does not contain 30 June at all.
            //
            // 30 July 2027 is due date 2 itself, and a due date on the business date has not closed
            // by it, so the count is still 1. This is the truncation the old assertion was reaching
            // for, and it happens at the due date rather than at a month end.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 7, 30), quarterly)).isEqualTo(1);
            // 31 July 2027 is one day past due date 2, so 30 Apr and 30 Jul have both closed: 2.
            // A month-truncating implementation cannot tell 30 July from 31 July — both truncate to
            // YearMonth 2027-07 — and answers 1 here. That is the input this line catches.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 7, 31), quarterly)).isEqualTo(2);
        }

        @Test
        @DisplayName("a date at or before the first due date gives 0 — clamped here, not reported")
        void atOrBeforeTheFirstDueDate() {
            Period monthly = CompoundingBasis.stepOf("MONTHLY");
            // 28 February 2027 is before the first instalment of 30 April 2027, so no due date at
            // all is strictly before it. Zero, by the same rule as every other case here rather
            // than by a special case.
            //
            // THE OLD TEST ASSERTED -2 FOR THIS INPUT, under the comment "Reported rather than
            // clamped here, so that the clamp lives in one place
            // (JdbcContractPeriodSource.periodOrdinal) and is visible as a decision". The clamp
            // moved into elapsedPeriods and the negative is gone. The new javadoc gives the reason:
            // a date at or before the first due date sits inside the contract's first period, the
            // one running from disbursement to the first instalment, so the ordinal derived from it
            // is 1 and never 0 — and ContractPeriod refuses 0 with the right reason ("zero is the
            // disbursement boundary, not a period"). periodOrdinal now only adds one, so a reported
            // -2 would arrive there as an ordinal of -1 and be refused with a message about a
            // negative ordinal instead of about a boundary before the schedule begins.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 2, 28), monthly)).isEqualTo(0);
            // The first due date itself is still 0: due date 1 falls on the business date, so it
            // has not closed by it, and this boundary is the CLOSING one of the stub period.
            // The input that breaks it: an implementation counting due dates at-or-before the
            // business date answers 1 here and skips the stub period for every contract.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 4, 30), monthly)).isEqualTo(0);
            // A year before the first due date is 0 too, not -12. The clamp is on the count, so the
            // size of the shortfall does not leak out and become an ordinal of -11.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2026, 4, 30), monthly)).isEqualTo(0);
        }

        @Test
        @DisplayName("instalments on the 20th, read at a period end of the 30th: three, not two")
        void respectsTheDayOfMonthWithinTheMonth() {
            Period monthly = CompoundingBasis.stepOf("MONTHLY");
            // The defect the implementation was changed to fix, in its javadoc's own words: an
            // earlier version truncated both dates to YearMonth and divided, so "a monthly contract
            // with instalments on the 20th, read at a period end of the 30th, came out one period
            // short".
            //
            // Due dates from 20 April 2027, monthly: 20 Apr, 20 May, 20 Jun, 20 Jul 2027. The
            // business date is 30 June 2027 — PeriodId.endOf(202706), the period end a boundary
            // actually carries. Strictly before it: 20 Apr, 20 May and 20 Jun. Three, so the
            // ordinal is 4 and ContractTerms.dueDate(3) = 20 Jun to dueDate(4) = 20 Jul is the
            // window holding 30 June.
            //
            // The truncating version answered YearMonth 2027-06 minus 2027-04 = 2 — one short — and
            // its ordinal 3 named the window 20 May to 20 Jun, which ENDS ten days before the
            // boundary it is supposed to contain. That is the input this assertion exists to catch.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 20), LocalDate.of(2027, 6, 30), monthly)).isEqualTo(3);
            // 19 June 2027 is one day BEFORE instalment 3, so only 20 Apr and 20 May have closed:
            // two. The pair pins the count to the due day; both dates are in the same YearMonth,
            // ten days apart, and they must not give the same answer.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 20), LocalDate.of(2027, 6, 19), monthly)).isEqualTo(2);
        }

        @Test
        @DisplayName("30 April to 1 May: one period, because an instalment fell — not because the month changed")
        void aOneDayStepAcrossAMonthBoundary() {
            // The javadoc's other named symptom of the YearMonth division: it "counts a one-day step
            // across a month boundary as a whole month — 30 April to 1 May came out as one".
            //
            // Due dates from 30 April 2027, monthly: 30 Apr, 30 May. Strictly before 1 May 2027:
            // just 30 Apr. One. One is also the right answer, and the two reach it differently —
            // the count is one because instalment 1 fell on 30 April, not because April became May.
            // The 30 April case in atOrBeforeTheFirstDueDate() is what separates the readings: the
            // due date sits between two dates one day apart whose answers are 0 and 1.
            //
            // The input that breaks this line: an implementation measuring whole STEPS elapsed since
            // the first due date — the old periodsBetween contract — answers 0 for 1 May, because no
            // whole month has passed. This is the regression it catches.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 1),
                CompoundingBasis.stepOf("MONTHLY"))).isEqualTo(1);
            // The same one-day step on a quarterly schedule, where "a whole month" and "a whole
            // period" come apart and the old division is caught outright: due dates 30 Apr and
            // 30 Jul 2027, so 1 May is still exactly one closed period. YearMonth 2027-05 minus
            // 2027-04 is 1 month, divided by 3 months a period gives 0 — the answer that would
            // place a contract one day into its second period back in the disbursement stub.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 1),
                CompoundingBasis.stepOf("QUARTERLY"))).isEqualTo(1);
        }

        @Test
        @DisplayName("weekly: a schedule the old int-month argument could not express at all")
        void weeklySchedule() {
            Period weekly = CompoundingBasis.stepOf("WEEKLY");
            // WEEKLY is Period.ofDays(7). The reporting period of 04 § 2.13 is still a month, but
            // the CONTRACT's schedule need not be, and V1's contract_version_compounding_basis_ck
            // admits WEEKLY and FORTNIGHTLY — daily-wage microfinance and KCC collection. The old
            // int months-per-period argument had no value for either: 0 is not a step and 1 is a
            // different schedule, which is why every weekly contract used to be quarantined (not,
            // as this comment once said, why the run aborted -- see CompoundingBasis.stepOf).
            //
            // Weekly due dates from 30 April 2027, counted in days because seven days is an exact
            // calendar step: 30 Apr, 7 May (April has 30 days, so 30 + 7 lands on the 7th), 14 May,
            // 21 May, 28 May, 4 Jun (May has 31, so 28 + 7 = 35 - 31 = the 4th), 11 Jun.
            //
            // 4 June 2027 is exactly due date 6, so five are strictly before it. 30 April to 4 June
            // is 35 days = 5 x 7 exactly, so the count sits on a step boundary here.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 4), weekly)).isEqualTo(5);
            // One day later, six: the 4 June instalment has now closed. 36 days divided by 7 floors
            // to 5, so this is the assertion that catches a division that ignores the part week.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 5), weekly)).isEqualTo(6);
        }

        @Test
        @DisplayName("fortnightly: fourteen-day steps across two month ends")
        void fortnightlySchedule() {
            // Fortnightly due dates from 1 January 2027, in days: 1 Jan, 15 Jan, 29 Jan, 12 Feb
            // (January has 31, so 29 + 14 = 43 - 31 = the 12th), 26 Feb, 12 Mar (2027 is not a leap
            // year, so February has 28: 26 + 14 = 40 - 28 = the 12th), 26 Mar, 9 Apr (March has 31:
            // 26 + 14 = 40 - 31 = the 9th).
            //
            // Strictly before 1 April 2027: 1 Jan, 15 Jan, 29 Jan, 12 Feb, 26 Feb, 12 Mar, 26 Mar.
            // Seven. 1 January to 1 April is 31 + 28 + 31 = 90 days, and 90 / 14 is 6 remainder 6,
            // so an implementation that stopped at the division answers 6 and this line fails it —
            // 26 March is strictly before 1 April and is the seventh instalment to have closed.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 1),
                CompoundingBasis.stepOf("FORTNIGHTLY"))).isEqualTo(7);
            // Constructed directly as well. Period.ofDays(14) is the same step as
            // stepOf("FORTNIGHTLY"), so it must give the same count; if these two ever disagree,
            // one of them is expressing a fortnight in the wrong unit, and 14 read as months would
            // put every fortnightly contract's second instalment years out.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 1),
                Period.ofDays(14))).isEqualTo(7);
        }

        @Test
        @DisplayName("due on the 31st: the forward walk corrects the month the clamp shortened")
        void theWalkCorrectsAClampedDayOfMonth() {
            Period monthly = CompoundingBasis.stepOf("MONTHLY");
            // Due dates from 31 January 2027, monthly, each computed from the FIRST due date and
            // clamped only where the target month is short: 31 Jan, 28 Feb (February 2027 has 28
            // days — 2027 is not a leap year), 31 Mar, 30 Apr, 31 May, ... The 31st comes BACK in
            // March, because due date k is firstDueDate + (k - 1) months rather than the previous
            // due date plus one month.
            //
            // 28 February 2027 is exactly due date 2, so one due date — 31 January — is strictly
            // before it. One.
            //
            // This is the input MAX_WALK exists for. The closed form measures whole months from
            // 31 January to 28 February and finds ZERO, because the 28th is short of the 31st, so
            // it under-counts by one; the walk steps forward to the first due date not before the
            // business date and lands on 1. The clamp can only ever shorten a step, which is why
            // the walk is forward-only and why an allowance of four steps is generous rather than
            // load-bearing. Without the walk this answers 0 and a contract standing at its second
            // instalment would be reported as still inside its disbursement stub.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), monthly)).isEqualTo(1);
            // One day later, two: the 28 February instalment has closed. The closed form finds one
            // whole month from 31 January to 1 March and the walk again supplies the one it missed.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 1), monthly)).isEqualTo(2);
            // Twelve months on, exactly twelve — the clamp must not accumulate. The due dates
            // strictly before 31 January 2028 are 31 Jan, 28 Feb, 31 Mar, 30 Apr, 31 May, 30 Jun,
            // 31 Jul, 31 Aug, 30 Sep, 31 Oct, 30 Nov and 31 Dec 2027: twelve, with the thirteenth
            // falling on the business date. An implementation that stepped plusMonths(1) from each
            // due date in turn would drift 31 Jan -> 28 Feb -> 28 Mar -> ... -> 28 Jan 2028, and
            // 28 January 2028 IS strictly before 31 January 2028, so it answers 13. That drift is
            // the defect this line names.
            assertThat(PeriodId.elapsedPeriods(
                LocalDate.of(2027, 1, 31), LocalDate.of(2028, 1, 31), monthly)).isEqualTo(12);
        }

        @Test
        @DisplayName("a step that does not advance is refused rather than walked")
        void refusesAStepThatDoesNotAdvance() {
            // No value of contract_version.compounding_basis produces this step: stepOf returns 7
            // or 14 days, or 1, 3, 6 or 12 months, and refuses SEASONAL and CUSTOM outright as
            // having no fixed step. So a zero step is an engine defect nothing downstream can
            // compensate for, which is the one case this repository throws for rather than
            // returning an invariant as a value. Without the check the closed form divides by zero
            // and the close dies naming an arithmetic operation instead of the step.
            assertThatThrownBy(() -> PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 30), Period.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one month or one day");
            // A backwards step reaches the same refusal, and must: it has no positive month or day
            // count, and a schedule whose instalments run backwards has no ordinal to derive.
            assertThatThrownBy(() -> PeriodId.elapsedPeriods(
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 6, 30), Period.ofMonths(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one month or one day");
        }
    }
}
