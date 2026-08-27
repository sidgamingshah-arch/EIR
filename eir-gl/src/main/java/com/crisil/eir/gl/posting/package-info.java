/**
 * The GL boundary: summarising contract-level journals into GL postings (FR-802), and reconciling
 * the sub-ledger to the GL control accounts at close (FR-803, invariant SL-1).
 *
 * <h2>Two things, deliberately kept apart</h2>
 *
 * <p>This package does two jobs that look like one job, and the whole design is the seam between
 * them.
 *
 * <p><b>(1) Summarisation is a group-and-sum, and is lossless by construction.</b>
 * {@link com.crisil.eir.gl.posting.GlSummary} takes a
 * {@link com.crisil.eir.gl.journal.JournalBatch} — contract-level postings — and aggregates it by
 * account code and side, which is the grain the schema itself thinks in: {@code journal_entry}
 * carries {@code ix_journal_entry_account ON journal_entry (period_id, account_code)} alongside the
 * per-contract index, and {@code vw_journal_entry_unbalanced} groups by
 * {@code (run_id, contract_id, period_id)} for SL-2. Movements go into the summary; movements come
 * out.
 *
 * <p><b>No invariant is published over that step, and that is not an omission.</b> Comparing the
 * summary's totals against the batch it was computed from asserts that a group-and-sum performed a
 * group-and-sum: there is no input on which it can fail, so it would be the fifth tautology this
 * codebase has found wearing an invariant id, and a control that reads as coverage while being
 * incapable of firing is worse than an absent one. The losslessness claim is real and worth
 * establishing, so it is established where a claim about an implementation belongs — as jqwik
 * properties over generated batches in {@code GlSummaryPropertiesTest}: total debits and total
 * credits preserved, every account's net preserved, every line accounted for, and the result
 * independent of the order the lines arrived in.
 *
 * <p><b>(2) SL-1 compares two independent sources.</b>
 * {@link com.crisil.eir.gl.posting.GlReconciliation} compares the engine's sub-ledger contract
 * balances ({@code period_balance} closing figures, 04 § 2.8) against the balances the general
 * ledger reports for its control accounts. Both sides are supplied by the caller. Neither is
 * derived from the other, and no factory here offers to derive one from the other, because the
 * moment the GL side is computed from the sub-ledger side the control becomes the tautology
 * above — with the added hazard that it would look exactly like a real reconciliation.
 *
 * <p>Note that the two jobs are not even measuring the same quantity. A summary is a period's
 * <em>movements</em>; SL-1 is <em>balances</em> at close. Same accounts, different numbers. That
 * is the plainest statement of why SL-1 is not the summarisation check.
 *
 * <h2>"Zero unexplained difference", not zero</h2>
 *
 * <p>FR-803 and control C-13 (07 § 3) both say <em>unexplained</em>. A control account can
 * genuinely differ from the sub-ledger for reasons that are not defects — a suspense posting that
 * landed after the GL cutoff, a manual journal somebody put through the GL outside the engine — and
 * a control that fired on those would be red every close and would then be suppressed. So an
 * explanation is a value ({@link com.crisil.eir.gl.posting.ExplainedDifference}) carrying an
 * account, a signed amount and a stated cause, and SL-1's deviation is the total <em>absolute
 * unexplained</em> amount across the account set.
 *
 * <p>Absolute, because two control accounts broken in opposite directions must not net to a pass —
 * and here that is the likely case rather than the exotic one, since the two halves of one
 * mis-mapped posting land on two accounts.
 *
 * @see com.crisil.eir.gl.posting.GlReconciliation#tiesToGl() what makes SL-1 fail
 */
package com.crisil.eir.gl.posting;
