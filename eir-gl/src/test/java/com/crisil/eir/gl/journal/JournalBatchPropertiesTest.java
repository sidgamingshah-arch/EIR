package com.crisil.eir.gl.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;

/**
 * The claim {@link JournalBatch} makes about SL-2's two grains, asserted rather than assumed.
 *
 * <p>SL-2's statement is "per run and per contract". {@code JournalBatch} publishes one result at
 * the contract grain and reports the run figure without asserting it, on the argument that the run
 * residual is by construction the sum of the contracts' — so a separate run-level leg could not
 * fail while the finer one passed, and would be a tautology.
 *
 * <p>That argument is arithmetic and it is worth checking rather than trusting, because it is
 * exactly the kind of reasoning that stops holding when somebody adds an entry with no contract, or
 * a batch that filters. If the implication ever breaks, the run grain becomes a real second check
 * and this property is what says so.
 */
class JournalBatchPropertiesTest {

    private static final LocalDate POSTED = LocalDate.of(2028, 4, 30);
    private static final String RUN = "RUN-202804-01";
    private static final int PERIOD = 202804;

    /**
     * If every entry balances, the run residual is nil — and if any entry does not, the absolute
     * deviation is the sum of the individual absolute residuals.
     */
    @Property(tries = 800)
    void theRunGrainFollowsFromTheContractGrain(
        @ForAll @Size(min = 1, max = 12) List<@IntRange(min = -3, max = 3) Integer> skews,
        @ForAll @BigRange(min = "0.01", max = "50000000") @Scale(2) BigDecimal base) {
        Money debit = Money.of(base, Money.INR);
        List<JournalEntry> entries = new ArrayList<>(skews.size());
        BigDecimal expectedAbsolute = BigDecimal.ZERO;
        BigDecimal expectedSigned = BigDecimal.ZERO;

        for (int index = 0; index < skews.size(); index++) {
            // The skew is a whole number of rupees so the arithmetic stays exact and the property
            // is about the aggregation rather than about rounding.
            Money skew = Money.inr(String.valueOf(skews.get(index)));
            Money credit = debit.minus(skew);
            List<JournalLine> lines = new ArrayList<>();
            lines.add(JournalLine.debit("1401-EIR-RECEIVABLE", debit, "accrual"));
            if (credit.isNegative()) {
                // A negative credit is unrepresentable, and correctly so; post it as a debit and
                // the residual becomes debit + |credit|.
                lines.add(JournalLine.debit("4101-INTEREST-INCOME", credit.negate(), "inverted"));
                expectedSigned = expectedSigned.add(debit.amount()).add(credit.negate().amount());
                expectedAbsolute = expectedAbsolute
                    .add(debit.amount().add(credit.negate().amount()).abs());
            } else {
                lines.add(JournalLine.credit("4101-INTEREST-INCOME", credit, "contractual"));
                expectedSigned = expectedSigned.add(skew.amount());
                expectedAbsolute = expectedAbsolute.add(skew.amount().abs());
            }
            entries.add(new JournalEntry("C" + index, PERIOD, RUN, "MAIN", POSTED, lines));
        }

        JournalBatch batch = JournalBatch.of(RUN, PERIOD, entries);

        assertThat(batch.runResidual().amount())
            .as("the run residual is the sum of the entries', which is why it is not asserted"
                + " separately")
            .isEqualByComparingTo(expectedSigned);

        boolean allBalance = expectedAbsolute.signum() == 0;
        assertThat(batch.sidesBalance().satisfied())
            .as("skews %s on a base of %s", skews, base)
            .isEqualTo(allBalance);
        if (!allBalance) {
            assertThat(batch.sidesBalance().deviation())
                .as("absolute, so opposite breaks cannot net")
                .isEqualByComparingTo(expectedAbsolute);
        }

        // And the sum of debits less the sum of credits is the same figure, whichever way it is
        // folded — the property that lets the batch report a run figure at all.
        assertThat(batch.totalDebits().minus(batch.totalCredits()).amount())
            .isEqualByComparingTo(expectedSigned);
    }

    /**
     * A balanced entry stays balanced however its lines are split.
     *
     * <p>One credit of 5,506.79 or two hundred credits summing to it are the same journal, and an
     * implementation that compared line <em>counts</em> per side, or assumed one line per side,
     * breaks here while passing every three-line fixture.
     */
    @Property(tries = 500)
    void splittingALineDoesNotChangeTheBalance(
        @ForAll @BigRange(min = "0.02", max = "10000000") @Scale(2) BigDecimal total,
        @ForAll @IntRange(min = 1, max = 40) int parts) {
        Money debit = Money.of(total, Money.INR);
        // Split into `parts` equal pieces plus the remainder on the last, so the pieces sum
        // exactly to the total with no rounding slack.
        BigDecimal each = total.divide(BigDecimal.valueOf(parts), 2, java.math.RoundingMode.DOWN);
        BigDecimal remainder = total.subtract(each.multiply(BigDecimal.valueOf(parts)));

        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit("1401", debit, "one debit"));
        for (int part = 0; part < parts; part++) {
            BigDecimal piece = part == parts - 1 ? each.add(remainder) : each;
            if (piece.signum() > 0) {
                lines.add(JournalLine.credit("4101-" + part, Money.of(piece, Money.INR), "piece"));
            }
        }

        JournalEntry entry = new JournalEntry("C1", PERIOD, RUN, "MAIN", POSTED, lines);
        assertThat(entry.residual().signum())
            .as("%s split across %d credits", total, parts)
            .isZero();
        assertThat(entry.sidesBalance().satisfied()).isTrue();
    }
}
