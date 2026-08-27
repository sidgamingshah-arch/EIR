package com.crisil.eir.gl.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Balanced journals and invariant SL-2 (FR-802).
 *
 * <p><b>The fixture is reference case 1's month-13 figures</b>, so the amounts are ones this
 * codebase can already account for: EIR interest of 5,506.79 against contractual interest of
 * 5,298.16, with the 208.63 difference being the period's fee amortisation slice. Posting that as
 * a journal is three lines, and it balances: debit the EIR receivable 5,506.79, credit interest
 * income 5,298.16, credit unamortised fee 208.63.
 */
class JournalBatchTest {

    private static final LocalDate POSTED = LocalDate.of(2028, 4, 30);
    private static final String RUN = "RUN-202804-01";
    private static final int PERIOD = 202804;

    private static JournalEntry balanced(String contractId) {
        return new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("5506.79"), "EIR accrual"),
            JournalLine.credit("4101-INTEREST-INCOME", Money.inr("5298.16"), "contractual"),
            JournalLine.credit("2301-UNAMORTISED-FEE", Money.inr("208.63"), "fee amortisation")));
    }

    private static JournalEntry outBy(String contractId, String amount) {
        return new JournalEntry(contractId, PERIOD, RUN, "MAIN", POSTED, List.of(
            JournalLine.debit("1401-EIR-RECEIVABLE", Money.inr("5506.79"), "EIR accrual"),
            JournalLine.credit("4101-INTEREST-INCOME",
                Money.inr("5506.79").minus(Money.inr(amount)), "short")));
    }

    @Nested
    @DisplayName("an entry knows whether it balances, and is constructible either way")
    class Entries {

        @Test
        @DisplayName("the reference-case posting balances to the paise")
        void balances() {
            // 5,506.79 DR against 5,298.16 + 208.63 CR = 5,506.79. The 208.63 is the fee
            // amortisation slice, which is why the EIR leg debits more than interest income
            // credits — INV-4's unamortised fee balance is the other side of this posting.
            JournalEntry entry = balanced("C1");
            assertThat(entry.totalDebits()).isEqualTo(Money.inr("5506.79"));
            assertThat(entry.totalCredits()).isEqualTo(Money.inr("5506.79"));
            assertThat(entry.residual()).isEqualTo(Money.zero(Money.INR));

            InvariantResult result = entry.sidesBalance();
            assertThat(result.id()).isEqualTo(InvariantId.SL_2);
            assertThat(result.satisfied()).isTrue();
        }

        @Test
        @DisplayName("an unbalanced entry CONSTRUCTS, and that is the design")
        void unbalancedConstructs() {
            // Refusing it in the constructor is the obvious design and would make SL-2 unfailable.
            // A control asserting a property its type cannot violate is a tautology, and this
            // codebase has recorded four of those wearing invariant ids. FR-802 puts the
            // requirement on the EMISSION and names SL-2 as the check; the close gate is what
            // refuses to publish a period whose SL-2 is red.
            //
            // The layering also gets the number right. A constructor guard turns the failure into
            // an exception in a batch, and what an accountant needs is BY HOW MUCH.
            JournalEntry entry = outBy("C1", "100.00");
            assertThat(entry.residual()).isEqualTo(Money.inr("100.00"));

            InvariantResult result = entry.sidesBalance();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.detail()).contains("out by INR 100.00");
        }

        @Test
        @DisplayName("a negative amount is refused; the side is the direction")
        void negativeAmountRefused() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> JournalLine.debit(
                    "1401", Money.inr("-100.00"), "wrong way round"))
                .withMessageContaining("post the opposite side rather than a negative amount");
        }

        @Test
        @DisplayName("an entry with no lines, and a cross-currency entry, are both refused")
        void unsummableEntriesRefused() {
            // Neither is an unbalanced journal. An empty entry posts nothing, and a
            // cross-currency one has no single residual — so SL-2 could not answer for it at all.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry("C1", PERIOD, RUN, "MAIN", POSTED, List.of()))
                .withMessageContaining("it is an absent one");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> new JournalEntry("C1", PERIOD, RUN, "MAIN", POSTED, List.of(
                    JournalLine.debit("1401", Money.inr("100.00"), ""),
                    JournalLine.credit("4101",
                        Money.of("100.00", java.util.Currency.getInstance("USD")), ""))))
                .withMessageContaining("or its residual is not a number");
        }
    }

    @Nested
    @DisplayName("SL-2 over a batch is one check at the contract grain")
    class Batches {

        @Test
        @DisplayName("all contracts balancing means the run balances, stated as one result")
        void allBalance() {
            JournalBatch batch = JournalBatch.of(RUN, PERIOD,
                List.of(balanced("C1"), balanced("C2"), balanced("C3")));

            InvariantResult result = batch.sidesBalance();
            assertThat(result.id()).isEqualTo(InvariantId.SL_2);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.detail())
                .as("the reason the run figure is published but not asserted")
                .contains("is not a second check");
            assertThat(batch.runResidual()).isEqualTo(Money.zero(Money.INR));
            assertThat(batch.totalDebits()).isEqualTo(Money.inr("16520.37"));
        }

        @Test
        @DisplayName("two contracts out in OPPOSITE directions do not net to a balanced run")
        void oppositeBreaksDoNotNet() {
            // The failure this is really for, and it is likelier here than in the Stage 3
            // reconciliation: two halves of one mis-posted transfer land on two different
            // contracts. The run residual is nil and the run is not balanced.
            JournalBatch batch = JournalBatch.of(RUN, PERIOD,
                List.of(outBy("C1", "100.00"), outBy("C2", "-100.00"), balanced("C3")));

            assertThat(batch.runResidual())
                .as("nil, which is exactly why a signed run-level check would pass")
                .isEqualTo(Money.zero(Money.INR));

            InvariantResult result = batch.sidesBalance();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("200.00 absolute, not nil signed")
                .isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(result.detail()).contains("2 of 3 entries");
        }

        @Test
        @DisplayName("an entry stamped with another run or period is refused")
        void stampMismatchRefused() {
            // The schema says the same thing with a composite foreign key on (run_id, period_id).
            assertThatIllegalArgumentException()
                .isThrownBy(() -> JournalBatch.of("RUN-OTHER", PERIOD, List.of(balanced("C1"))))
                .withMessageContaining("in a batch for run RUN-OTHER");
        }

        @Test
        @DisplayName("an empty batch is a real outcome, not an error")
        void emptyBatch() {
            // A run over a book with nothing to post — a period in which no contract had an
            // event — is uninteresting rather than wrong.
            JournalBatch empty = JournalBatch.of(RUN, PERIOD, List.of());
            assertThat(empty.sidesBalance().satisfied()).isTrue();
            assertThat(empty.contractCount()).isZero();
            assertThat(empty.runResidual()).isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("a contract may have more than one entry in a run")
        void multipleEntriesPerContract() {
            JournalBatch batch = JournalBatch.of(RUN, PERIOD,
                List.of(balanced("C1"), balanced("C1"), balanced("C2")));
            assertThat(batch.entries()).hasSize(3);
            assertThat(batch.contractCount()).isEqualTo(2);
            assertThat(batch.byContract().get("C1")).hasSize(2);
        }
    }
}
