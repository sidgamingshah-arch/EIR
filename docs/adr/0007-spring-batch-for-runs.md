# ADR-0007 — Spring Batch for amortisation and close runs

**Status:** Accepted

## Context

The month-end run processes ~10M contracts inside a 4-hour window: load contract, prior balance,
events, staging; route any event; roll forward; assert per-contract invariants; write balance and
journals. It must be restartable, must isolate per-contract failures without failing the run
(FR-905), and must then assert aggregate invariants across the whole set.

## Decision

Spring Batch, with partitioned steps keyed on `product × entity`, chunk-oriented processing, a skip
policy routing failed items to the exception queue, and job metadata persisted for run reporting.
Aggregate invariants run as a final step after all partitions complete.

## Rationale

**The requirements are Spring Batch's feature list.** Partitioning, restart from the last committed
chunk, skip policies with configurable limits, per-item fault barriers, run metadata. Building these
on a plain executor means reimplementing them, less well, on the critical path of a statutory
deadline.

**Restartability is a hard requirement, not a nicety.** A run that dies at hour three of a four-hour
window against a close deadline must resume, not restart. Chunk-level commit boundaries give that
directly.

**Per-contract isolation and aggregate gating are different things, and both are needed.** A skip
policy handles the first: one malformed contract routes to the exception queue and the chunk
continues. The final aggregate step handles the second: an SL-1 or PF-1 breach fails the *run*,
because it means the whole set is untrustworthy. Conflating them either fails a 10M-contract run on
one bad record, or lets a broken reconciliation through.

**Partitioning on `product × entity` matches the natural grain.** Policy, rule sets, tier assignment
and floor categories are all keyed on product and entity, so a partition shares its configuration
and its cache. It also gives operational visibility in the units the controller thinks in.

## Consequences

- Spring Batch's schema lives alongside the domain schema. Contained and well-understood.
- Partition skew must be managed: the largest retail products dominate. Handled by splitting the
  largest products into sub-partitions, tuned against the load test.
- The framework's abstractions are verbose. Acceptable — this is orchestration, and the mathematics
  lives in framework-free modules where verbosity would matter.
- The 10M-contract synthetic close becomes a Phase 5 exit gate ([08](../08-roadmap.md)), including a
  deliberate mid-run kill to prove restart.

## Alternatives rejected

**Hand-rolled executor with a work queue.** Fewer dependencies, and then a slow reimplementation of
restartability, skip policies and run metadata — the parts that are easy to get subtly wrong and
expensive to discover wrong at close.

**Streaming (Kafka / Flink).** A good fit for continuous event processing, and this is not that: it
is a periodic batch with a defined start, a defined end, and aggregate gates that need a completion
barrier. Streaming would impose eventual consistency on a close that requires a consistent cut.

**Database-resident batch (stored procedures).** Fast, and rejected for the same reason as in
[ADR-0001](0001-modular-monolith.md): the mathematics is the audit surface and belongs in
version-controlled, unit-testable code.

**Spark.** Justified if the computation were a distributed aggregation. It is not — it is embarrassingly
parallel per contract with a transactional write, which partitioned batch handles without adding a
cluster to operate.
