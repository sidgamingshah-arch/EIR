/**
 * The month-end amortisation run's inner loop — one contract, one period (05 § 3.2).
 *
 * <p><b>Where the solve sits.</b> 05 § 3.2's own note on its sequence diagram is the design
 * constraint this package exists to honour: the solve is "<em>inside the event branch only</em>. A
 * fixed-rate contract with no events never re-solves. The steady-state run is overwhelmingly
 * roll-forward arithmetic, which is what makes the 10M-contract target reachable". A pipeline that
 * re-solved every contract every period would produce identical figures for a contract with no
 * events and still be wrong, because the cost is the requirement. So the solver is not reachable
 * from this package except through {@link com.crisil.eir.application.run.SolveAudit}, which counts
 * and attributes every call — the absence of a solve is a fact the run can state rather than an
 * absence a reader has to trust.
 *
 * <p><b>What is here and what is not.</b> The diagram's inner loop is: load state, route any event
 * by driver tag, roll forward on the gross basis, decompose and suppress where the stage says so,
 * assert per-contract invariants, and emit a period balance and a journal. That is
 * {@link com.crisil.eir.application.run.ContractPipeline}, wrapped per contract in FR-905's
 * barrier by {@link com.crisil.eir.application.run.MonthEndRun}, and aggregated over the population
 * by {@link com.crisil.eir.application.run.RunAggregate}. The two steps the diagram puts
 * <em>outside</em> the loop are outside this package too: the upstream-feed verification that
 * precedes it, and the run-level conjunction of SL-1, PF-1, HB-1 and TG-1 that follows it — the
 * latter already has a home in {@code eir-policy.close.PeriodCloseGate}.
 *
 * <p><b>Time is an input.</b> Nothing here reads a clock. Every date and instant comes from
 * {@link com.crisil.eir.application.port.AsAtBoundary} on the request, which is what makes 05 § 3.3's
 * replay the same code path rather than a parallel one (DT-1, ADR-0003).
 */
package com.crisil.eir.application.run;
