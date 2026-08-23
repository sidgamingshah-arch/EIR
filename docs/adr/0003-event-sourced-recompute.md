# ADR-0003 — Event-sourced contract timeline, materialised period balances

**Status:** Accepted

## Context

Any published figure must be reproducible years later, under the accounting policy and input
versions then in force — not today's. At the same time closed-period figures are published financial
statements: they must be retrievable exactly as published, cheaply, on demand.

These pull in opposite directions. Full recompute-on-read gives fidelity to history but makes every
audit query a replay and risks drift the moment a code path changes. Pure stored state is cheap but
loses the ability to explain how a figure arose.

## Decision

**Event-sourced for the contract timeline; materialised and immutable for the published figures.**

- `LIFECYCLE_EVENT` is the source of truth for everything that happens to a contract.
- `EIR_COMPUTATION` rows are immutable and stamped with the policy version, fee rule-set version and
  routing table version that produced them. Superseded, never updated.
- `PERIOD_BALANCE` is a **materialised projection**, stored — not recomputed on read.
- Closed-period partitions are set read-only at close.
- A replay recomputes into a **shadow table** and byte-compares against the published figures
  (invariant DT-1), run nightly against a sampled period.
- The data model is bitemporal: business time separate from system time
  ([04 §5](../04-data-model.md#5-temporal-design)).

## Rationale

**Determinism is a design property, not a feature.** It requires: no wall-clock reads in the
calculation path, no unseeded randomness, no ambient state, deterministic iteration order, and every
input version recorded. Retrofitting those is not feasible — which is why this is decided up front.

**The ECL version stamp is what makes Stage 3 replayable.** Stage 3 interest depends on the
allowance, which is an ECL engine output that moves every period. Without recording the version
consumed, a replay computes a different answer and DT-1 fails for reasons that have nothing to do
with this engine.

**Nightly replay converts an assumption into a measurement.** Determinism claimed is worthless;
determinism verified continuously is the audit position.

## Consequences

- Storage grows monotonically: ~120M `PERIOD_BALANCE` rows per year at target volume. Partitioning by
  period and archiving closed partitions to columnar storage handles it. `EIR_COMPUTATION` is never
  archived — it is the audit anchor and is small relative to the balance tables.
- Every policy artefact needs versioning and approval infrastructure. Cost paid once, in `eir-policy`.
- Corrections are more elaborate: a restatement artefact, not an `UPDATE`. Correct, and it is what
  makes the whole position defensible.
- `LocalDate` rather than `Instant` throughout the calculation path, so that reading a clock is
  awkward by construction rather than merely discouraged.

## Alternatives rejected

**Recompute everything on read.** Maximum fidelity, but every audit query becomes a full replay and
any code change silently alters historical figures. The published number must not depend on the
current build.

**Stored state with an audit log.** Cheap, and the common choice. Rejected: an audit log records
*that* a value changed, not enough to reproduce *why* the value was what it was under a superseded
policy.

**Snapshot-only versioning (no bitemporality).** Cannot represent a backdated amendment — the case
where business time and system time genuinely differ, which is exactly when replay matters most.
