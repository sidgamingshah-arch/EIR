package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.DrCr;
import com.crisil.eir.gl.journal.JournalBatch;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A run's contract-level journals summarised to GL postings, grouped by account code and side
 * (FR-802, 04 § 2.7).
 *
 * <p>The step the GL actually consumes. {@code journal_entry} is written at contract grain — one row
 * per contract per account per side, which is what {@code vw_journal_entry_unbalanced} groups by and
 * what {@code ix_journal_entry_contract_period} indexes — and a general ledger does not want a
 * million rows. It wants the period's movement per control account, which is
 * {@code ix_journal_entry_account ON journal_entry (period_id, account_code)}: the schema carries
 * that index because this aggregate is a thing somebody asks for.
 *
 * <h2>No invariant is published here, on purpose</h2>
 *
 * <p><b>Summarisation is lossless by construction.</b> The postings are the batch's lines grouped
 * and summed, so "the summary's total debits equal the batch's total debits" cannot fail on any
 * input — group-and-sum did a group-and-sum. Publishing that under an invariant id would be a
 * control incapable of firing, and this codebase has found four of those and treats them as worse
 * than absent controls, because they read as coverage. So the claim is asserted where a claim about
 * an implementation belongs: as jqwik properties over generated batches in
 * {@code GlSummaryPropertiesTest} — totals preserved, every account's net preserved, every line
 * counted, and the output independent of arrival order.
 *
 * <p><b>Nor is SL-2 republished here.</b> {@link #residual()} is nil exactly when the batch balances,
 * so a summary-level SL-2 leg could not fail while the contract-level one passed — the same argument
 * {@code JournalBatch} makes for not asserting the run grain separately, and
 * {@code InvariantResult.conjunction} keeps only the first breach's deviation among results sharing
 * an id, so a second leg would make one of the two uninterpretable. The residual is published as
 * plain data.
 *
 * <p><b>And SL-1 is not this.</b> SL-1 compares the sub-ledger's <em>balances</em> against the
 * balances the GL reports for its control accounts — two independently sourced sets of numbers that
 * can genuinely differ. This is a period's <em>movements</em>, derived from one source. See
 * {@link GlReconciliation}.
 *
 * @param runId    the run the batch came from
 * @param periodId the accounting period, in the schema's {@code YYYYMM} form
 * @param currency the one currency every posting is in
 * @param postings one per (account code, side) that any line touched, ordered by {@link
 *                 GlPostingKey}
 */
public record GlSummary(String runId, int periodId, Currency currency, List<GlPosting> postings) {

    public GlSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(currency, "currency");
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        Set<GlPostingKey> seen = new LinkedHashSet<>();
        for (GlPosting posting : postings) {
            if (!seen.add(posting.key())) {
                // Two totals for one (account, side) is not a summary — it is an unfinished one,
                // and whichever a reader picks up first becomes the figure that goes to the GL.
                throw new IllegalArgumentException(
                    "summary for run " + runId + " carries two postings for " + posting.key()
                        + "; a summary has one total per account and side, or it has not summarised");
            }
            if (!posting.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                    "summary for run " + runId + " is in " + currency.getCurrencyCode()
                        + " but posting " + posting.key() + " is in "
                        + posting.amount().currency().getCurrencyCode());
            }
        }
    }

    /**
     * Group the batch's lines by account code and side, and sum.
     *
     * <p>Every line is visited and none is filtered, including zero-amount lines: the group exists
     * because lines landed in it. The output is ordered by {@link GlPostingKey} rather than by
     * arrival, so the same postings in a different order produce the same feed — which FR-903's
     * bit-identical replay needs and which is asserted as a property rather than assumed.
     *
     * @throws IllegalArgumentException if the batch mixes currencies across its entries. Not an
     *     imbalance and not a summary defect: a cross-currency total is not a number, the same
     *     refusal {@code JournalEntry} makes within an entry.
     */
    public static GlSummary summarise(JournalBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Currency currency = currencyOf(batch);
        Map<GlPostingKey, Money> totals = new LinkedHashMap<>();
        Map<GlPostingKey, Integer> counts = new LinkedHashMap<>();
        for (JournalEntry entry : batch.entries()) {
            for (JournalLine line : entry.lines()) {
                GlPostingKey key = new GlPostingKey(line.accountCode(), line.side());
                totals.merge(key, line.amount(), Money::plus);
                counts.merge(key, 1, Integer::sum);
            }
        }
        List<GlPosting> postings = new ArrayList<>(totals.size());
        for (Map.Entry<GlPostingKey, Money> total : totals.entrySet()) {
            GlPostingKey key = total.getKey();
            postings.add(
                new GlPosting(key.accountCode(), key.side(), total.getValue(), counts.get(key)));
        }
        postings.sort((left, right) -> left.key().compareTo(right.key()));
        return new GlSummary(batch.runId(), batch.periodId(), currency, postings);
    }

    /** Sum of every debit posting. */
    public Money totalDebits() {
        return total(DrCr.DR);
    }

    /** Sum of every credit posting. */
    public Money totalCredits() {
        return total(DrCr.CR);
    }

    /**
     * Debits less credits over the summary.
     *
     * <p>Nil exactly when the batch balanced, which is why it is data here and SL-2 at the contract
     * grain on {@code JournalEntry}. Computed unrounded — rounding the two sides before subtracting
     * manufactures a residual on a batch that balances exactly (03 § 1.2).
     */
    public Money residual() {
        return totalDebits().minus(totalCredits());
    }

    /**
     * The net movement on an account: its debit total less its credit total.
     *
     * <p>The figure a caller wanting a signed movement per account asks for, computed from the two
     * gross postings rather than stored in place of them — the same "derived, not accumulated"
     * discipline 04 § 2.8 applies to {@code unamortised_fee} for invariant INV-4, and for the same
     * reason: an accumulator can drift from the figures it describes, a derivation cannot.
     */
    public Money netFor(String accountCode) {
        Objects.requireNonNull(accountCode, "accountCode");
        Money net = Money.zero(currency);
        for (GlPosting posting : postings) {
            if (posting.accountCode().equals(accountCode)) {
                net = net.plus(posting.signed());
            }
        }
        return net;
    }

    /** The posting for one account and side, if any line touched it. */
    public Optional<GlPosting> postingFor(String accountCode, DrCr side) {
        GlPostingKey wanted = new GlPostingKey(accountCode, side);
        return postings.stream().filter(posting -> posting.key().equals(wanted)).findFirst();
    }

    /**
     * Every account code the summary touches, in code order.
     *
     * <p>A {@link SortedSet} rather than {@code Set.copyOf(...)}: the immutable copy factories
     * discard iteration order, so a method promising code order and returning one of them is
     * promising something it does not deliver.
     */
    public SortedSet<String> accountCodes() {
        SortedSet<String> codes = new TreeSet<>();
        for (GlPosting posting : postings) {
            codes.add(posting.accountCode());
        }
        return Collections.unmodifiableSortedSet(codes);
    }

    /** Net movement per account, in code order — the signed form of the whole summary. */
    public SortedMap<String, Money> netByAccount() {
        SortedMap<String, Money> nets = new TreeMap<>();
        for (GlPosting posting : postings) {
            nets.merge(posting.accountCode(), posting.signed(), Money::plus);
        }
        return Collections.unmodifiableSortedMap(nets);
    }

    /**
     * How many journal lines were summarised into this.
     *
     * <p>The audit figure. It must equal {@code batch.allLines().size()}, and it is what makes
     * "no line was dropped" checkable rather than assumed.
     */
    public int contributingLines() {
        int lines = 0;
        for (GlPosting posting : postings) {
            lines += posting.contributingLines();
        }
        return lines;
    }

    /** How many summarised postings the GL receives. */
    public int postingCount() {
        return postings.size();
    }

    /** A one-line summary naming the compression achieved and the residual. */
    public String describe() {
        return "run " + runId + " period " + periodId + ": " + contributingLines()
            + " journal lines summarised to " + postings.size() + " postings across "
            + accountCodes().size() + " accounts, DR " + totalDebits().atPresentationScale()
            + " CR " + totalCredits().atPresentationScale()
            + (residual().signum() == 0 ? " (balanced)"
                : " (residual " + residual().atPresentationScale() + ")");
    }

    private Money total(DrCr side) {
        Money total = Money.zero(currency);
        for (GlPosting posting : postings) {
            if (posting.side() == side) {
                total = total.plus(posting.amount());
            }
        }
        return total;
    }

    /**
     * The batch's one currency.
     *
     * <p>INR for an empty batch, matching {@code JournalBatch.runResidual()} — a run over a book
     * with nothing to post is a real outcome, and it has to have a currency to report zero in.
     */
    private static Currency currencyOf(JournalBatch batch) {
        if (batch.entries().isEmpty()) {
            return Money.INR;
        }
        Currency currency = batch.entries().getFirst().currency();
        for (JournalEntry entry : batch.entries()) {
            if (!entry.currency().equals(currency)) {
                throw new IllegalArgumentException(
                    "batch for run " + batch.runId() + " mixes "
                        + currency.getCurrencyCode() + " and "
                        + entry.currency().getCurrencyCode() + " (contract " + entry.contractId()
                        + "); one summary is one currency, or its totals are not numbers");
            }
        }
        return currency;
    }
}
