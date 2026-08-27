package com.crisil.eir.gl.journal;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every journal entry a run produced for a period, and invariant SL-2 over them (FR-802).
 *
 * <p><b>SL-2's statement is "per run and per contract", and those are not two checks.</b> Every
 * entry belongs to exactly one contract and a batch is the union of its entries, so the run's
 * residual is by construction the sum of the contracts' residuals: if every contract balances, the
 * run balances, arithmetically and with nothing left to verify. Computing a separate run-level
 * comparison and publishing it as a second leg would be the tautology this codebase keeps
 * finding — a control that cannot fail while the finer one passes.
 *
 * <p>So {@link #sidesBalance()} is one result at the contract grain, with the run figure reported
 * as what it is: the sum of the same residuals. The implication is asserted as a property rather
 * than assumed, in {@code JournalBatchPropertiesTest}.
 *
 * <p><b>Where the run grain does become independent</b> is the step after this one. Summarising
 * contract-level journals into GL postings groups and aggregates them, and grouping can lose an
 * entry — so the sub-ledger-to-GL comparison is a genuine second check on genuinely different
 * numbers. That is invariant SL-1 and it lives in {@code gl.posting}.
 *
 * @param runId    the run
 * @param periodId the period
 * @param entries  the entries; may be empty, since a run over a book with nothing to post is a
 *                 real and uninteresting outcome rather than an error
 */
public record JournalBatch(String runId, int periodId, List<JournalEntry> entries) {

    public JournalBatch {
        Objects.requireNonNull(runId, "runId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        for (JournalEntry entry : entries) {
            if (!entry.runId().equals(runId) || entry.periodId() != periodId) {
                // The schema says the same thing with a composite foreign key on
                // (run_id, period_id): a posting cannot be stamped with a period other than the
                // one its run was for.
                throw new IllegalArgumentException(
                    "entry for contract " + entry.contractId() + " is stamped run "
                        + entry.runId() + " period " + entry.periodId() + " in a batch for run "
                        + runId + " period " + periodId);
            }
        }
    }

    /** A batch from entries that all agree on their run and period. */
    public static JournalBatch of(String runId, int periodId, Collection<JournalEntry> entries) {
        return new JournalBatch(runId, periodId, List.copyOf(entries));
    }

    /**
     * Invariant SL-2 over the batch: one result at the contract grain.
     *
     * <p>Deviation is the total <b>absolute</b> residual. Signed would let a contract over-debited
     * by 100 and one over-credited by 100 net to nil and report a balanced run — the same failure
     * the Stage 3 four-way reconciliation guards against, and here it is more likely, because two
     * halves of one mis-posted transfer land on two contracts.
     */
    public InvariantResult sidesBalance() {
        List<String> breaks = new ArrayList<>();
        BigDecimal absolute = BigDecimal.ZERO;
        BigDecimal signed = BigDecimal.ZERO;
        for (JournalEntry entry : entries) {
            Money residual = entry.residual();
            signed = signed.add(residual.amount());
            if (residual.signum() != 0) {
                breaks.add(entry.contractId() + " out by " + residual.atPresentationScale());
                absolute = absolute.add(residual.amount().abs());
            }
        }
        if (breaks.isEmpty()) {
            return InvariantResult.pass(InvariantId.SL_2,
                entries.size() + " entries in run " + runId + " period " + periodId
                    + " all balance, so the run balances — the run figure is the sum of the same"
                    + " residuals and is not a second check");
        }
        return InvariantResult.fail(InvariantId.SL_2,
            breaks.size() + " of " + entries.size() + " entries in run " + runId
                + " do not balance (run residual " + signed.toPlainString()
                + ", absolute " + absolute.toPlainString() + "): "
                + (breaks.size() > 20 ? breaks.subList(0, 20) + " …" : breaks),
            absolute);
    }

    /**
     * The run's own residual: debits less credits across every entry.
     *
     * <p>Published so a caller can see it, and explicitly <em>not</em> asserted as a second SL-2
     * leg. It equals the sum of the per-entry residuals by construction.
     */
    public Money runResidual() {
        if (entries.isEmpty()) {
            return Money.zero(Money.INR);
        }
        Money total = Money.zero(entries.getFirst().currency());
        for (JournalEntry entry : entries) {
            total = total.plus(entry.residual());
        }
        return total;
    }

    /** Total debits across the batch. */
    public Money totalDebits() {
        return fold(true);
    }

    /** Total credits across the batch. */
    public Money totalCredits() {
        return fold(false);
    }

    /** Every line in the batch, in entry order. */
    public List<JournalLine> allLines() {
        List<JournalLine> all = new ArrayList<>();
        for (JournalEntry entry : entries) {
            all.addAll(entry.lines());
        }
        return List.copyOf(all);
    }

    /** Entries by contract; one contract may have more than one entry in a run. */
    public Map<String, List<JournalEntry>> byContract() {
        Map<String, List<JournalEntry>> grouped = new LinkedHashMap<>();
        for (JournalEntry entry : entries) {
            grouped.computeIfAbsent(entry.contractId(), key -> new ArrayList<>()).add(entry);
        }
        return Map.copyOf(grouped);
    }

    /** How many contracts the batch touches. */
    public int contractCount() {
        return byContract().size();
    }

    private Money fold(boolean debits) {
        if (entries.isEmpty()) {
            return Money.zero(Money.INR);
        }
        Money total = Money.zero(entries.getFirst().currency());
        for (JournalEntry entry : entries) {
            total = total.plus(debits ? entry.totalDebits() : entry.totalCredits());
        }
        return total;
    }
}
