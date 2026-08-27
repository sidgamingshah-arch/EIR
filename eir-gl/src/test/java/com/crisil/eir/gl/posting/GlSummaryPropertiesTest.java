package com.crisil.eir.gl.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.DrCr;
import com.crisil.eir.gl.journal.JournalBatch;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Losslessness of summarisation, asserted as properties because it must not be an invariant.
 *
 * <p><b>This file is where the claim belongs.</b> {@link GlSummary} groups a
 * {@link JournalBatch}'s lines by account code and side and sums them, so a published invariant
 * comparing the summary's totals against the batch's would assert that a group-and-sum performed a
 * group-and-sum: no input could make it fail, and this codebase has found four controls in that
 * shape and treats them as worse than absent ones because they read as coverage. The claim is still
 * worth establishing — grouping code drops things — so it is established over generated batches,
 * against a recomputation that does not use the summary at all.
 *
 * <p><b>No expected value here comes from running the code under test.</b> Every property
 * re-derives the answer from the definition of a group-and-sum by walking
 * {@code batch.entries()} and accumulating plain {@link BigDecimal}s, which shares no code path with
 * {@code GlSummary}'s {@code Money}-and-{@code LinkedHashMap} implementation.
 *
 * <p>The generator deliberately makes accounts collide: eight account codes across up to eight
 * entries of up to six lines each, so a single (account, side) group routinely receives lines from
 * several contracts and both sides of one account are frequently populated. A generator with unique
 * accounts would pass every property below while grouping did nothing at all.
 */
class GlSummaryPropertiesTest {

    private static final LocalDate POSTED = LocalDate.of(2028, 4, 30);
    private static final String RUN = "RUN-202804-01";
    private static final int PERIOD = 202804;

    /**
     * A small chart of accounts, so that collisions are the common case rather than the exception.
     * The codes are the ones the reference-case journals use plus filler.
     */
    private static final List<String> ACCOUNTS = List.of(
        "1301-LOANS-GCA",
        "1401-EIR-RECEIVABLE",
        "1409-INTEREST-SUSPENSE",
        "1499-SUNDRY",
        "2301-UNAMORTISED-FEE",
        "4101-INTEREST-INCOME",
        "4109-FEE-INCOME",
        "5201-BASIS-ADJUSTMENT");

    @Provide
    Arbitrary<JournalBatch> batches() {
        Arbitrary<JournalLine> lines = Combinators.combine(
                Arbitraries.of(ACCOUNTS),
                Arbitraries.of(DrCr.DR, DrCr.CR),
                // Zero included on purpose: a nil line is a line, and dropping it is the tidy-up
                // that makes the line count stop reconciling.
                Arbitraries.bigDecimals()
                    .between(BigDecimal.ZERO, new BigDecimal("9999999.99"))
                    .ofScale(2))
            .as((account, side, amount) ->
                new JournalLine(account, side, Money.of(amount, Money.INR), "generated"));
        Arbitrary<JournalEntry> entries = Combinators.combine(
                Arbitraries.integers().between(1, 30),
                lines.list().ofMinSize(1).ofMaxSize(6))
            .as((contract, entryLines) ->
                new JournalEntry("C" + contract, PERIOD, RUN, "MAIN", POSTED, entryLines));
        return entries.list().ofMinSize(0).ofMaxSize(8)
            .map(list -> JournalBatch.of(RUN, PERIOD, list));
    }

    /**
     * Total debits and total credits survive summarisation.
     *
     * <p>Recomputed by walking every line and adding to one of two {@link BigDecimal} accumulators —
     * the definition of "total debits", written out.
     */
    @Property(tries = 600)
    void totalDebitsAndCreditsArePreserved(@ForAll("batches") JournalBatch batch) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JournalEntry entry : batch.entries()) {
            for (JournalLine line : entry.lines()) {
                if (line.side() == DrCr.DR) {
                    debits = debits.add(line.amount().amount());
                } else {
                    credits = credits.add(line.amount().amount());
                }
            }
        }

        GlSummary summary = GlSummary.summarise(batch);
        assertThat(summary.totalDebits().amount())
            .as("%d lines summarised to %d postings", summary.contributingLines(),
                summary.postingCount())
            .isEqualByComparingTo(debits);
        assertThat(summary.totalCredits().amount()).isEqualByComparingTo(credits);
        assertThat(summary.residual().amount())
            .as("the residual survives too, which is why SL-2 need not be republished here")
            .isEqualByComparingTo(debits.subtract(credits));
    }

    /**
     * Every account's net movement survives, and so does each gross side.
     *
     * <p>The property that a net-only summary would pass and a correct one must also satisfy: it is
     * asserted alongside the gross figures precisely so that a change to netting the two sides
     * together would fail on the gross assertion rather than pass silently.
     */
    @Property(tries = 600)
    void everyAccountsNetAndGrossSurvive(@ForAll("batches") JournalBatch batch) {
        Map<String, BigDecimal> nets = new LinkedHashMap<>();
        Map<GlPostingKey, BigDecimal> gross = new LinkedHashMap<>();
        for (JournalEntry entry : batch.entries()) {
            for (JournalLine line : entry.lines()) {
                BigDecimal signed = line.side() == DrCr.DR
                    ? line.amount().amount()
                    : line.amount().amount().negate();
                nets.merge(line.accountCode(), signed, BigDecimal::add);
                gross.merge(new GlPostingKey(line.accountCode(), line.side()),
                    line.amount().amount(), BigDecimal::add);
            }
        }

        GlSummary summary = GlSummary.summarise(batch);
        for (Map.Entry<String, BigDecimal> expected : nets.entrySet()) {
            assertThat(summary.netFor(expected.getKey()).amount())
                .as("net on %s", expected.getKey())
                .isEqualByComparingTo(expected.getValue());
        }
        for (Map.Entry<GlPostingKey, BigDecimal> expected : gross.entrySet()) {
            GlPostingKey key = expected.getKey();
            GlPosting actual = summary.postingFor(key.accountCode(), key.side())
                .orElseThrow(() -> new AssertionError("no posting for " + key));
            assertThat(actual.amount().amount())
                .as("gross posting %s", key)
                .isEqualByComparingTo(expected.getValue());
        }
        // And the summary invents no account the batch never touched.
        assertThat(summary.accountCodes()).isEqualTo(new java.util.TreeSet<>(nets.keySet()));
    }

    /**
     * No line is dropped, and no group appears that no line landed in.
     *
     * <p>The line count is the audit figure: a summary whose money is all correct and whose count is
     * one short has lost a posting, and that is the failure mode {@code JournalBatch}'s javadoc names
     * when it says "grouping can lose an entry".
     */
    @Property(tries = 600)
    void everyLineIsCountedExactlyOnce(@ForAll("batches") JournalBatch batch) {
        Map<GlPostingKey, Integer> counts = new LinkedHashMap<>();
        int lines = 0;
        for (JournalEntry entry : batch.entries()) {
            for (JournalLine line : entry.lines()) {
                counts.merge(new GlPostingKey(line.accountCode(), line.side()), 1, Integer::sum);
                lines++;
            }
        }

        GlSummary summary = GlSummary.summarise(batch);
        assertThat(summary.contributingLines())
            .as("total lines summarised")
            .isEqualTo(lines);
        assertThat(summary.postingCount())
            .as("one posting per distinct (account, side) that received a line — no more, no fewer")
            .isEqualTo(counts.size());
        Set<GlPostingKey> keys = new LinkedHashSet<>();
        for (GlPosting posting : summary.postings()) {
            keys.add(posting.key());
            assertThat(posting.contributingLines())
                .as("lines behind %s", posting.key())
                .isEqualTo(counts.get(posting.key()));
        }
        assertThat(keys).isEqualTo(counts.keySet());
    }

    /**
     * The summary does not depend on the order the lines arrived in.
     *
     * <p>FR-903 requires a replay to reproduce published figures bit-identically, and a GL feed whose
     * line order follows iteration order is not bit-identical even when every number is right. The
     * batch is reversed at both levels — entries, and the lines within each entry — so a stable-sort
     * implementation that happened to preserve arrival order within a group would still be exercised.
     */
    @Property(tries = 600)
    void summarisationIsIndependentOfArrivalOrder(@ForAll("batches") JournalBatch batch) {
        List<JournalEntry> reversed = new ArrayList<>();
        for (int index = batch.entries().size() - 1; index >= 0; index--) {
            JournalEntry entry = batch.entries().get(index);
            List<JournalLine> flipped = new ArrayList<>(entry.lines());
            java.util.Collections.reverse(flipped);
            reversed.add(new JournalEntry(entry.contractId(), entry.periodId(), entry.runId(),
                entry.bookId(), entry.postedOn(), flipped));
        }

        GlSummary forward = GlSummary.summarise(batch);
        GlSummary backward = GlSummary.summarise(JournalBatch.of(RUN, PERIOD, reversed));
        assertThat(backward.postings())
            .as("same postings, same order, same counts")
            .isEqualTo(forward.postings());
    }
}
