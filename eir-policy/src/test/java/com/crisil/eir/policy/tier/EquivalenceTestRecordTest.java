package com.crisil.eir.policy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The equivalence-test evidence record of 03 § 10.2 — the annual window and the Board threshold.
 *
 * <p>Every expected figure here is either a calendar fact or arithmetic done by hand in the
 * comment beside it. Nothing is taken from running the code.
 */
class EquivalenceTestRecordTest {

    /** A financial-year-end performance date, which is when a bank would actually do this. */
    private static final LocalDate PERFORMED = LocalDate.of(2027, 3, 31);

    private static EquivalenceTestRecord record(
        LocalDate performedOn, String solvedRate, String approximatedRate, String thresholdBps) {
        return new EquivalenceTestRecord(
            "WCDL-2027-Q1",
            performedOn,
            250,
            // periodsPerYear 1, so the periodic rate IS the effective annual rate and the
            // basis-point arithmetic below is a multiplication by 10,000 and nothing else.
            Rate.annualEffective(new BigDecimal(solvedRate)),
            Rate.annualEffective(new BigDecimal(approximatedRate)),
            new BigDecimal(thresholdBps),
            "board.audit.committee");
    }

    @Nested
    @DisplayName("the annual window of 03 § 10.2 item 3, pinned on both sides")
    class AnnualWindow {

        @Test
        @DisplayName("expiry is the anniversary of the performance date")
        void expiryIsTheAnniversary() {
            assertThat(record(PERFORMED, "0.08", "0.08", "25").expiresOn())
                .as("a test performed 2027-03-31 runs to 2028-03-31")
                .isEqualTo(LocalDate.of(2028, 3, 31));
        }

        @Test
        @DisplayName("the anniversary itself is IN date")
        void anniversaryIsInDate() {
            // The boundary decision, and it is not arbitrary. If the anniversary were already
            // stale, a bank re-performing every test on 31 March — exactly the annual cadence
            // 03 § 10.2 item 3 and control C-10 ask for — would be out of date for the one day
            // before the new test lands, and would have to work to a 364-day cycle to stay
            // clean. A rule that penalises the schedule it prescribes is the wrong reading.
            EquivalenceTestRecord test = record(PERFORMED, "0.08", "0.08", "25");
            assertThat(test.isInDateOn(LocalDate.of(2028, 3, 31)))
                .as("2028-03-31 is the last day of the window")
                .isTrue();
            assertThat(test.daysOverdueOn(LocalDate.of(2028, 3, 31)))
                .as("nothing is overdue on the last day of the window")
                .isZero();
        }

        @Test
        @DisplayName("the day after the anniversary is OUT of date, by one day")
        void dayAfterAnniversaryIsStale() {
            EquivalenceTestRecord test = record(PERFORMED, "0.08", "0.08", "25");
            assertThat(test.isInDateOn(LocalDate.of(2028, 4, 1))).isFalse();
            assertThat(test.daysOverdueOn(LocalDate.of(2028, 4, 1)))
                .as("2028-04-01 is one day past 2028-03-31")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("the day before expiry is in date, so the window is not off by one")
        void dayBeforeExpiryIsInDate() {
            assertThat(record(PERFORMED, "0.08", "0.08", "25")
                .isInDateOn(LocalDate.of(2028, 3, 30)))
                .isTrue();
        }

        @Test
        @DisplayName("a leap day expires on 28 February, not on an invalid 29th")
        void leapDayWindow() {
            // Period.ofYears(1) rather than 365 days, so java.time resolves 2028-02-29 plus one
            // year to 2029-02-28. A 365-day window would put expiry at 2029-02-28 in a common
            // year and drift a day every time a leap day intervened, eventually pushing an
            // on-schedule test out of date on arithmetic alone.
            EquivalenceTestRecord leapDay = record(LocalDate.of(2028, 2, 29), "0.08", "0.08", "25");
            assertThat(leapDay.expiresOn()).isEqualTo(LocalDate.of(2029, 2, 28));
            assertThat(leapDay.isInDateOn(LocalDate.of(2029, 2, 28))).isTrue();
            assertThat(leapDay.isInDateOn(LocalDate.of(2029, 3, 1))).isFalse();
        }

        @Test
        @DisplayName("days overdue counts the calendar, so a badly stale test says how badly")
        void daysOverdueIsMeasured() {
            // Expiry 2028-03-31 to 2028-12-31, counted by hand: Apr 30 + May 31 + Jun 30
            // + Jul 31 + Aug 31 + Sep 30 + Oct 31 + Nov 30 + Dec 31 = 275 days.
            assertThat(record(PERFORMED, "0.08", "0.08", "25")
                .daysOverdueOn(LocalDate.of(2028, 12, 31)))
                .isEqualTo(275L);
        }

        @Test
        @DisplayName("a test dated after the reporting date had not been performed yet")
        void notYetPerformed() {
            EquivalenceTestRecord june = record(LocalDate.of(2028, 6, 30), "0.08", "0.08", "25");
            assertThat(june.wasPerformedBy(LocalDate.of(2028, 3, 31)))
                .as("June evidence for a March close is not evidence")
                .isFalse();
            assertThat(june.wasPerformedBy(LocalDate.of(2028, 6, 30)))
                .as("the performance date itself counts as performed")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("the delta against the Board-approved threshold, 03 § 10.2 item 2")
    class Threshold {

        @Test
        @DisplayName("the delta is the effective-annual spread in basis points, signed")
        void deltaInBasisPoints() {
            // 8.50% solved against 8.00% approximated. At periodsPerYear 1 the effective annual
            // rate is the stored periodic rate, so the spread is 0.0050 x 10,000 = 50 bps.
            EquivalenceTestRecord test = record(PERFORMED, "0.085", "0.08", "25");
            assertThat(test.deltaBps()).isEqualByComparingTo(new BigDecimal("50"));
            assertThat(test.absoluteDeltaBps()).isEqualByComparingTo(new BigDecimal("50"));
        }

        @Test
        @DisplayName("a delta equal to the threshold is within it")
        void thresholdIsInclusive() {
            // 8.25% against 8.00% is 25 bps, against a 25 bps tolerance.
            assertThat(record(PERFORMED, "0.0825", "0.08", "25").isWithinThreshold()).isTrue();
        }

        @Test
        @DisplayName("a hair over the threshold is over it, and the excess is reported")
        void aHairOverIsOver() {
            // 8.251% against 8.00% is 25.1 bps against a 25 bps tolerance: excess 0.1 bps.
            EquivalenceTestRecord test = record(PERFORMED, "0.08251", "0.08", "25");
            assertThat(test.isWithinThreshold()).isFalse();
            assertThat(test.excessOverThresholdBps())
                .isEqualByComparingTo(new BigDecimal("0.1"));
        }

        @Test
        @DisplayName("the sign of the delta is discarded by the threshold comparison")
        void signIsDiscarded() {
            // 7.75% solved against 8.00% approximated: the shortcut OVERSTATES the rate by
            // 25 bps. A shortcut that overstates income is exactly as indefensible as one that
            // understates it, and a signed comparison would wave half the failures through.
            EquivalenceTestRecord test = record(PERFORMED, "0.0775", "0.08", "20");
            assertThat(test.deltaBps())
                .as("the sign is kept on the reported delta, for the workpaper")
                .isEqualByComparingTo(new BigDecimal("-25"));
            assertThat(test.isWithinThreshold())
                .as("and discarded by the threshold test")
                .isFalse();
            assertThat(test.excessOverThresholdBps())
                .isEqualByComparingTo(new BigDecimal("5"));
        }

        @Test
        @DisplayName("excess is zero, not negative, while inside the tolerance")
        void noNegativeExcess() {
            assertThat(record(PERFORMED, "0.081", "0.08", "25").excessOverThresholdBps())
                .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the comparison is effective annual, so compounding cannot hide a spread")
        void comparisonIsEffectiveAnnual() {
            // A solved rate of 1% a month against an approximated 12% a year. Compared
            // nominally the two are identical — 0.01 x 12 = 0.12 — and the delta would read
            // zero. Compared at effective annual, 1.01^12 = 1.1268250301..., so the true
            // spread is about 1268.25 - 1200 = 68.25 bps. Rate's own javadoc is explicit that
            // the two annualisations are not interchangeable; this is what that costs when the
            // wrong one is used to set a materiality threshold.
            EquivalenceTestRecord test = new EquivalenceTestRecord(
                "MIXED-COMPOUNDING", PERFORMED, 100,
                Rate.monthly(new BigDecimal("0.01")),
                Rate.annualEffective(new BigDecimal("0.12")),
                new BigDecimal("25"), "board.audit.committee");
            assertThat(test.deltaBps())
                .as("1.01^12 - 1 is about 12.6825%, so the spread is about 68 bps, not nil")
                .isGreaterThan(new BigDecimal("68"))
                .isLessThan(new BigDecimal("69"));
            assertThat(test.isWithinThreshold())
                .as("68 bps against a 25 bps tolerance fails")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("what cannot be recorded as evidence at all")
    class Validation {

        @Test
        @DisplayName("a sample of no contracts is not a representative sample")
        void emptySampleRefused() {
            // 03 § 10.2 item 1 requires "a representative sample". Zero is not a small sample,
            // it is the absence of the comparison, and storing it would let a population claim
            // a test on file that never compared anything.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestRecord(
                    "P", PERFORMED, 0,
                    Rate.annualEffective(new BigDecimal("0.08")),
                    Rate.annualEffective(new BigDecimal("0.08")),
                    new BigDecimal("25"), "board"))
                .withMessageContaining("not a representative sample");
        }

        @Test
        @DisplayName("an unnamed population cannot be matched to a Tier 3 population")
        void blankPopulationRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestRecord(
                    "  ", PERFORMED, 10,
                    Rate.annualEffective(new BigDecimal("0.08")),
                    Rate.annualEffective(new BigDecimal("0.08")),
                    new BigDecimal("25"), "board"))
                .withMessageContaining("names the population");
        }

        @Test
        @DisplayName("a negative threshold is a tolerance nothing can satisfy")
        void negativeThresholdRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestRecord(
                    "P", PERFORMED, 10,
                    Rate.annualEffective(new BigDecimal("0.08")),
                    Rate.annualEffective(new BigDecimal("0.08")),
                    new BigDecimal("-1"), "board"))
                .withMessageContaining("negative threshold");
        }

        @Test
        @DisplayName("a threshold nobody approved is not a Board-approved threshold")
        void unapprovedThresholdRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new EquivalenceTestRecord(
                    "P", PERFORMED, 10,
                    Rate.annualEffective(new BigDecimal("0.08")),
                    Rate.annualEffective(new BigDecimal("0.08")),
                    new BigDecimal("25"), ""))
                .withMessageContaining("names nobody who approved");
        }

        @Test
        @DisplayName("a zero threshold is permitted — it means no tolerance, not no approval")
        void zeroThresholdIsAllowed() {
            EquivalenceTestRecord exact = record(PERFORMED, "0.08", "0.08", "0");
            assertThat(exact.isWithinThreshold())
                .as("a nil delta satisfies even a nil tolerance")
                .isTrue();
        }
    }

    @Test
    @DisplayName("describe() names the delta, the threshold, the approver and the window")
    void describeCarriesTheAuditSentence() {
        assertThat(record(PERFORMED, "0.085", "0.08", "25").describe())
            .contains("WCDL-2027-Q1")
            .contains("2027-03-31")
            .contains("250 contracts")
            .contains("board.audit.committee")
            .contains("in date to 2028-03-31");
    }
}
