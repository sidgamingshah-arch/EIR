/**
 * The period close: FR-901's hard gates, and FR-902's immutability of what they lock.
 *
 * <p>Two requirements, and they are two halves of one mechanism. FR-901 decides whether a period
 * may be locked; FR-902 says what "locked" means afterwards. 02 § 3.1 walks the whole thing as a
 * financial controller does, in seven steps, and this package owns steps 4, 6 and 7 — the invariant
 * dashboard ("any red blocks the close"), the reconciliations, and the approve-and-lock that makes
 * the period immutable.
 *
 * <h2>The gate is a decision, not an invariant</h2>
 *
 * <p>{@link com.crisil.eir.policy.close.PeriodCloseGate} publishes no invariant identifier and
 * {@link com.crisil.eir.policy.close.CloseDecision} deliberately has no {@code asInvariantResult()}.
 * FR-901's gate is the point at which invariants are <em>read</em> — it consumes
 * {@link com.crisil.eir.domain.InvariantResult}s the engines that produced the figures have already
 * asserted — and it makes no claim about a figure of its own. An id meaning "the close gate held"
 * would be a control whose only input is the gate's own output: green exactly when the gate said
 * permitted, and unfalsifiable by anything a ledger could contain. Four such ids have been found in
 * this codebase and they are treated as worse than absent controls, because they read as coverage.
 *
 * <p>So the gate's output is a value with a refusal vocabulary
 * ({@link com.crisil.eir.policy.close.CloseGateRefusal}), in the style of
 * {@code approval.TransitionRefusal}, and every refusal comes back at once. A close presents a red
 * S3-1, forty unworked exceptions and a sub-ledger break simultaneously, worked by three different
 * desks; a gate that threw on the first would turn a day's parallel work into a week of serial
 * re-runs, each costing a full amortisation pass over ten million contracts.
 *
 * <h2>Two absences that are refusals</h2>
 *
 * <p>An empty invariant dashboard is not a green one, and a reconciliation nobody presented is not a
 * tied one. Both are the shape a hard gate takes when it is written as "no evidence of failure"
 * instead of "evidence of success", and both would pass the one case where nothing at all is known:
 * the close where the step never ran. Hence
 * {@link com.crisil.eir.policy.close.CloseGateRefusal#NO_INVARIANT_RESULTS} and
 * {@link com.crisil.eir.policy.close.CloseGateRefusal#RECONCILIATION_NOT_PRESENTED}, and hence
 * {@link com.crisil.eir.policy.close.ReconciliationScope} enumerating step 6's four reconciliations
 * rather than accepting whichever ties a caller hands in.
 *
 * <h2>Acceptance is the control</h2>
 *
 * <p>FR-901's second limb is "all exceptions cleared <b>or accepted with approval</b>", and the
 * second half is the interesting one. Every one of 04 § 3's ten categories answers
 * {@link com.crisil.eir.policy.exception.ExceptionCategory#blocksClose()} true, so acceptance is the
 * only route past a queued exception — which makes acceptance the way past every other control in
 * the queue, and therefore the thing that needs two people.
 *
 * <p>{@code EXCEPTION.resolved_by} is one column and holds one name, so the queue row can record
 * that somebody signed but cannot record that somebody <em>else</em> did. A proper approval and a
 * self-approval are indistinguishable on that row. So
 * {@link com.crisil.eir.policy.close.ExceptionAcceptance} carries both parties, the gate matches it
 * against the row, and the identity comparison is
 * {@link com.crisil.eir.domain.FourEyes#isSelfApproval} — not a fourth hand-written spelling of the
 * rule, because the one place it was written a fourth time with a plain {@code equals} was the place
 * that let a maker approve their own artefact under a different capitalisation.
 *
 * <h2>FR-902, and why CL-1 needs two readings of the same period</h2>
 *
 * <p>The tempting implementation of "closed periods are immutable" is a Java type: make the closed
 * period a record and nothing can mutate it. That makes invariant CL-1 a tautology. Java
 * immutability constrains one process's object graph for the life of one JVM; CL-1 is about an
 * {@code UPDATE} against {@code PERIOD_BALANCE} or {@code JOURNAL_ENTRY} in a closed partition —
 * applied by a correction script, an archive step, or a re-run that wrote into the wrong period.
 *
 * <p>So the control compares what the period <em>published</em> against what the ledger says
 * <em>now</em>: two {@link com.crisil.eir.policy.close.PeriodStatement}s, read at two instants in
 * system time, compared by {@link com.crisil.eir.policy.close.ClosedPeriodImmutability}. Three
 * failure shapes are representable — a changed figure, a deleted one, an inserted one — and the
 * deviation is the count of mutated figures, as CL-1's own javadoc specifies.
 *
 * <p>The legitimate alternative is 04 § 5's bitemporality, and
 * {@link com.crisil.eir.policy.close.RestatementArtefact} is its shape: business time
 * ({@code valid_from}) keeps the date the amendment was really true from, system time
 * ({@code recorded_at}) records when the engine learned it, and accounting time
 * ({@code period_id}) moves — the movement is recognised in a later, open period. The closed period
 * therefore still replays to the figures it published (DT-1) and the correction is a separate,
 * dated, attributable fact. A restatement on file does <em>not</em> excuse a mutated figure: both
 * existing at once means the movement has been recognised twice.
 *
 * <h2>Alignment with the schema</h2>
 *
 * <p>{@link com.crisil.eir.policy.close.AccountingPeriod} is {@code ACCOUNTING_PERIOD} of
 * {@code V2__ledger_and_transition.sql}, column for column, and restates all four of its
 * {@code CHECK} constraints as constructor guards — including
 * {@code ck_accounting_period_closure_attested}, which is FR-902 in the database's words: a closed
 * period names who closed it, when, and the system-time boundary a replay must read as at, or it is
 * not closed. The guards are restated in Java rather than left to the database because a period that
 * only becomes invalid on {@code INSERT} is a period the engine will already have acted on.
 *
 * <h2>What this package does not do</h2>
 *
 * <p>It computes no figure and asserts no invariant other than CL-1. The reconciliation residuals it
 * reads are asserted by SL-1, RC-1, S3-1 and PF-1 elsewhere, and
 * {@link com.crisil.eir.policy.close.ReconciliationScope} records the correspondence so an operator
 * can get from a refusal to the control that quantifies it. It does not persist anything: 04 § 2.13's
 * {@code ACCOUNTING_PERIOD} row and the restatement store are {@code eir-persistence}'s concern. It
 * does not replay a period — FR-903 and DT-1 are a separate unit, and this package's contribution to
 * them is {@code version_cutoff_at} being mandatory on a close.
 */
package com.crisil.eir.policy.close;
