/**
 * Input assembly for the four reconciliation reports of {@code docs/06-api-spec.md} 06 § 7.
 *
 * <p><b>What is here and what deliberately is not.</b> Two classes, both of which gather a
 * period's inputs and hand them to an evaluator that already exists:
 * {@link com.crisil.eir.api.modules.reconciliation.PeriodReconciliations} for SL-1, RC-1 and the
 * Stage 3 four-way, and {@link com.crisil.eir.api.modules.reconciliation.FloorDuality} for the
 * pre-/post-floor duality. <b>Neither contains a reconciliation.</b> The residual arithmetic lives
 * in {@code GlReconciliation}, {@code CoreBankingReconciliation}, {@code Stage3Reconciliation} and
 * {@code FloorApplication} — each of which publishes its own invariant result and each of which is
 * entitled to exactly one answer per period.
 *
 * <p>That separation is the whole reason this package exists rather than the reports being written
 * inline. An endpoint that gathered inputs <em>and</em> differenced them would be a second answer
 * under an identifier that admits one — the defect docs/08 records this codebase finding three
 * times — and it would be invisible, because a second answer computed the same way agrees until the
 * day it does not.
 *
 * <p>Rendering is not here either: {@code ReconciliationReportsModule} turns these results into
 * JSON. So the assembly is testable without a socket and the module is a renderer with nothing to
 * decide.
 */
package com.crisil.eir.api.modules.reconciliation;
