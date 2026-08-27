package com.crisil.eir.gl.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.DrCr;
import com.crisil.eir.gl.journal.JournalBatch;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Summarising contract-level journals to GL postings (FR-802).
 *
 * <p><b>The fixture is reference case 1's month-13 figures</b>, the same ones {@code JournalBatchTest}
 * uses, so the amounts are ones this codebase can already account for: EIR interest of 5,506.79
 * against contractual interest of 5,298.16, with the 208.63 difference the period's fee amortisation
 * slice. Three contracts post that journal, and a fourth entry posts a 1,000.00 reversal that
 * <em>credits</em> the EIR receivable — present specifically so the DR and CR sides of one account
 * are exercised and it is visible that they are not netted.
 *
 * <p><b>Every expected figure below was derived by hand and cross-checked in python3</b>, never read
 * off a run of the code. The derivations:
 *
 * <pre>
 *   DR 1401-EIR-RECEIVABLE   = 3 × 5,506.79        = 16,520.37   (3 lines)
 *   CR 1401-EIR-RECEIVABLE   = 1,000.00            =  1,000.00   (1 line)
 *   CR 2301-UNAMORTISED-FEE  = 3 ×   208.63        =    625.89   (3 lines)
 *   DR 4101-INTEREST-INCOME  = 1,000.00            =  1,000.00   (1 line)
 *   CR 4101-INTEREST-INCOME  = 3 × 5,298.16        = 15,894.48   (3 lines)
 *
 *   total debits  = 16,520.37 + 1,000.00                        = 17,520.37
 *   total credits =  1,000.00 +   625.89 + 15,894.48            = 17,520.37
 *   net 1401 = 16,520.37 − 1,000.00 = 15,520.37
 *   net 4101 =  1,000.00 − 15,894.48 = −14,894.48
 *   net 2301 =              −625.89
 *   lines = 3 entries × 3 lines + 1 entry × 2 lines = 11
 * </pre>
 *
 * <p>Note what is <em>not</em> asserted anywhere in this file: an invariant. Summarisation is a
 * group-and-sum and its losslessness cannot fail on any input, so publishing it under an id would
 * be a tautology. The losslessness claim is established in {@link GlSummaryPropertiesTest} over
 * generated batches, which is where a claim about an implementation belongs.
 */
class GlSummaryTest {

    private static final LocalDate POSTED = LocalDate.of(2028, 4, 30);
    private static final String RUN = "RUN-202804-01";
    private static final int PERIOD = 202804;
    private static final String EIR_RECEIVABLE = "1401-EIR-RECEIVABLE";
    private static final String UNAMORTISED_FEE = "2301-UNAMORTISED-FEE";
    private static final String INTEREST_INCOME = "4101-INTEREST-INCOME";

    /** Reference case 1 month 13: 5,506.79 DR against 5,298.16 + 208.63 CR. */
    private static JournalEntry accrual(String contractId) {
        return new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit(EIR_RECEIVABLE, Money.inr("5506.79"), "EIR accrual"),
            JournalLine.credit(INTEREST_INCOME, Money.inr("5298.16"), "contractual"),
            JournalLine.credit(UNAMORTISED_FEE, Money.inr("208.63"), "fee amortisation")));
    }

    /** A reversal, so that 1401 is touched on both sides and 4101 likewise. */
    private static JournalEntry reversal(String contractId) {
        return new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit(INTEREST_INCOME, Money.inr("1000.00"), "reversal of over-accrual"),
            JournalLine.credit(EIR_RECEIVABLE, Money.inr("1000.00"), "reversal of over-accrual")));
    }

    private static JournalBatch batch() {
        return JournalBatch.of(RUN, PERIOD,
            List.of(accrual("C1"), accrual("C2"), accrual("C3"), reversal("C4")));
    }

    @Nested
    @DisplayName("a batch summarises to one posting per account and side")
    class Grouping {

        @Test
        @DisplayName("the five postings carry the hand-derived totals and line counts")
        void totalsAndCounts() {
            GlSummary summary = GlSummary.summarise(batch());

            // 11 lines across 4 entries compress to 5 postings. Both figures are counted by hand
            // above; the compression ratio is the whole reason the GL wants a summary at all.
            assertThat(summary.postingCount()).as("five (account, side) pairs were touched")
                .isEqualTo(5);
            assertThat(summary.contributingLines())
                .as("every one of the batch's 11 lines is accounted for in some posting")
                .isEqualTo(11);
            assertThat(summary.contributingLines())
                .as("and that is the batch's own line count")
                .isEqualTo(batch().allLines().size());

            assertThat(summary.postingFor(EIR_RECEIVABLE, DrCr.DR))
                .as("3 × 5,506.79 = 16,520.37 over 3 lines")
                .contains(new GlPosting(EIR_RECEIVABLE, DrCr.DR, Money.inr("16520.37"), 3));
            assertThat(summary.postingFor(EIR_RECEIVABLE, DrCr.CR))
                .as("the single 1,000.00 reversal credit, NOT netted against the debits")
                .contains(new GlPosting(EIR_RECEIVABLE, DrCr.CR, Money.inr("1000.00"), 1));
            assertThat(summary.postingFor(UNAMORTISED_FEE, DrCr.CR))
                .as("3 × 208.63 = 625.89")
                .contains(new GlPosting(UNAMORTISED_FEE, DrCr.CR, Money.inr("625.89"), 3));
            assertThat(summary.postingFor(INTEREST_INCOME, DrCr.CR))
                .as("3 × 5,298.16 = 15,894.48")
                .contains(new GlPosting(INTEREST_INCOME, DrCr.CR, Money.inr("15894.48"), 3));
            assertThat(summary.postingFor(INTEREST_INCOME, DrCr.DR))
                .contains(new GlPosting(INTEREST_INCOME, DrCr.DR, Money.inr("1000.00"), 1));
            assertThat(summary.postingFor(UNAMORTISED_FEE, DrCr.DR))
                .as("no line ever debited the fee account, so there is no posting for it — an"
                    + " absent group and a nil one are different facts")
                .isEmpty();
        }

        @Test
        @DisplayName("the two sides of one account are kept apart, and the net is derived from them")
        void sidesAreNotNetted() {
            GlSummary summary = GlSummary.summarise(batch());

            // The DDL's own argument: signed amounts "make 'total debits posted this run' a filtered
            // aggregate instead of a sum". Both gross figures survive, and the net is a subtraction
            // over them rather than a stored accumulator (the INV-4 discipline of 04 § 2.8).
            assertThat(summary.netFor(EIR_RECEIVABLE))
                .as("16,520.37 DR − 1,000.00 CR = 15,520.37")
                .isEqualTo(Money.inr("15520.37"));
            assertThat(summary.netFor(INTEREST_INCOME))
                .as("1,000.00 DR − 15,894.48 CR = −14,894.48")
                .isEqualTo(Money.inr("-14894.48"));
            assertThat(summary.netFor(UNAMORTISED_FEE)).isEqualTo(Money.inr("-625.89"));
            assertThat(summary.netFor("9999-NOT-POSTED-TO"))
                .as("an untouched account nets to nil rather than being absent from arithmetic")
                .isEqualTo(Money.zero(Money.INR));

            assertThat(summary.netByAccount())
                .containsExactly(
                    org.assertj.core.api.Assertions.entry(EIR_RECEIVABLE, Money.inr("15520.37")),
                    org.assertj.core.api.Assertions.entry(UNAMORTISED_FEE, Money.inr("-625.89")),
                    org.assertj.core.api.Assertions.entry(
                        INTEREST_INCOME, Money.inr("-14894.48")));
        }

        @Test
        @DisplayName("postings are ordered by account code then debits before credits")
        void deterministicOrder() {
            // Order is part of the output, not an accident of iteration: FR-903 requires a replay to
            // reproduce published figures bit-identically, and a feed whose line order depends on
            // HashMap iteration is not bit-identical even when every number is right.
            assertThat(GlSummary.summarise(batch()).postings())
                .extracting(GlPosting::accountCode, GlPosting::side)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple(EIR_RECEIVABLE, DrCr.DR),
                    org.assertj.core.groups.Tuple.tuple(EIR_RECEIVABLE, DrCr.CR),
                    org.assertj.core.groups.Tuple.tuple(UNAMORTISED_FEE, DrCr.CR),
                    org.assertj.core.groups.Tuple.tuple(INTEREST_INCOME, DrCr.DR),
                    org.assertj.core.groups.Tuple.tuple(INTEREST_INCOME, DrCr.CR));
            assertThat(GlSummary.summarise(batch()).accountCodes())
                .containsExactly(EIR_RECEIVABLE, UNAMORTISED_FEE, INTEREST_INCOME);
        }

        @Test
        @DisplayName("a zero-amount line creates a posting and is counted, not filtered away")
        void zeroLinesSurvive() {
            // The tidy-up that breaks the audit trail: dropping a nil posting leaves the line count
            // one short of the batch's, on a summary whose money figures are all correct. This is
            // exactly the "grouping can lose an entry" failure JournalBatch's javadoc names.
            JournalEntry withNil = new JournalEntry("C9", PERIOD, RUN, "MAIN", POSTED, List.of(
                JournalLine.debit(EIR_RECEIVABLE, Money.inr("100.00"), "accrual"),
                JournalLine.credit(INTEREST_INCOME, Money.inr("100.00"), "income"),
                JournalLine.debit("1499-SUNDRY", Money.zero(Money.INR), "nil movement")));
            GlSummary summary = GlSummary.summarise(JournalBatch.of(RUN, PERIOD, List.of(withNil)));

            assertThat(summary.postingFor("1499-SUNDRY", DrCr.DR))
                .contains(new GlPosting("1499-SUNDRY", DrCr.DR, Money.zero(Money.INR), 1));
            assertThat(summary.contributingLines()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the summary reports the batch's residual as data, and asserts no invariant")
    class ResidualIsData {

        @Test
        @DisplayName("a balanced batch summarises to 17,520.37 on each side")
        void balanced() {
            GlSummary summary = GlSummary.summarise(batch());
            // Hand-derived: 16,520.37 + 1,000.00 = 17,520.37 debits; 1,000.00 + 625.89 + 15,894.48
            // = 17,520.37 credits.
            assertThat(summary.totalDebits()).isEqualTo(Money.inr("17520.37"));
            assertThat(summary.totalCredits()).isEqualTo(Money.inr("17520.37"));
            assertThat(summary.residual()).isEqualTo(Money.zero(Money.INR));
            assertThat(summary.describe()).contains("11 journal lines summarised to 5 postings");
        }

        @Test
        @DisplayName("an unbalanced batch summarises to a non-nil residual, still with no breach")
        void unbalancedSummarises() {
            // JournalEntry deliberately allows an unbalanced entry so SL-2 has something to detect;
            // summarisation must carry that through rather than refusing it. 5,506.79 DR against
            // 5,298.16 CR is out by 208.63 — the omitted fee slice.
            JournalEntry short2301 = new JournalEntry("C1", PERIOD, RUN, "MAIN", POSTED, List.of(
                JournalLine.debit(EIR_RECEIVABLE, Money.inr("5506.79"), "EIR accrual"),
                JournalLine.credit(INTEREST_INCOME, Money.inr("5298.16"), "contractual")));
            GlSummary summary =
                GlSummary.summarise(JournalBatch.of(RUN, PERIOD, List.of(short2301)));

            assertThat(summary.residual())
                .as("5,506.79 − 5,298.16 = 208.63, the fee slice nobody credited")
                .isEqualTo(Money.inr("208.63"));
            assertThat(summary.describe()).contains("residual INR 208.63");
        }

        @Test
        @DisplayName("an empty batch summarises to an empty INR summary")
        void emptyBatch() {
            GlSummary summary = GlSummary.summarise(JournalBatch.of(RUN, PERIOD, List.of()));
            assertThat(summary.postings()).isEmpty();
            assertThat(summary.contributingLines()).isZero();
            assertThat(summary.totalDebits()).isEqualTo(Money.zero(Money.INR));
            assertThat(summary.currency()).isEqualTo(Money.INR);
        }
    }

    @Nested
    @DisplayName("inputs with no single total are refused, and that is not a breach")
    class Refusals {

        @Test
        @DisplayName("a batch mixing currencies across entries is refused")
        void mixedCurrency() {
            Currency usd = Currency.getInstance("USD");
            JournalEntry inr = accrual("C1");
            JournalEntry dollars = new JournalEntry("C2", PERIOD, RUN, "MAIN", POSTED, List.of(
                JournalLine.debit(EIR_RECEIVABLE, Money.of("100.00", usd), "accrual"),
                JournalLine.credit(INTEREST_INCOME, Money.of("100.00", usd), "income")));

            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    GlSummary.summarise(JournalBatch.of(RUN, PERIOD, List.of(inr, dollars))))
                .withMessageContaining("mixes INR and USD");
        }

        @Test
        @DisplayName("two postings for one account and side is not a summary")
        void duplicateKey() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new GlSummary(RUN, PERIOD, Money.INR, List.of(
                    new GlPosting(EIR_RECEIVABLE, DrCr.DR, Money.inr("1.00"), 1),
                    new GlPosting(EIR_RECEIVABLE, DrCr.DR, Money.inr("2.00"), 1))))
                .withMessageContaining("two postings for DR " + EIR_RECEIVABLE);
        }

        @Test
        @DisplayName("a negative total means the sides were collapsed with signs")
        void negativePosting() {
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    new GlPosting(EIR_RECEIVABLE, DrCr.DR, Money.inr("-1.00"), 1))
                .withMessageContaining("the side is the direction");
        }

        @Test
        @DisplayName("a posting nothing contributed to is not a summary of anything")
        void zeroLineCount() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new GlPosting(EIR_RECEIVABLE, DrCr.DR, Money.inr("1.00"), 0))
                .withMessageContaining("claims 0 contributing lines");
        }

        @Test
        @DisplayName("a blank account code posts nowhere")
        void blankAccountCode() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new GlPostingKey("   ", DrCr.DR))
                .withMessageContaining("posts nowhere");
        }
    }
}
