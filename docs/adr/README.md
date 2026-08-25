# Architecture Decision Records

One decision per file. Format: context, decision, consequences, alternatives rejected.

A decision is never edited to reverse it. A new ADR **supersedes** the old one and the old one is
marked `Superseded by ADR-nnnn`, keeping the reasoning trail intact — the same discipline the engine
itself applies to closed accounting periods.

| # | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith on Spring Boot, not microservices | Accepted |
| [0002](0002-precision-policy.md) | `BigDecimal` throughout; 28-digit working precision; `HALF_UP` | Accepted |
| [0003](0003-event-sourced-recompute.md) | Event-sourced contract timeline; materialised period balances | Accepted |
| [0004](0004-delta-over-contractual-ledger.md) | The engine computes an overlay; the CBS stays the book of record | Accepted |
| [0005](0005-contract-level-default.md) | Contract-level measurement by default; pooling as a governed election | Accepted |
| [0006](0006-configurable-event-routing.md) | Reset-versus-catch-up routing as versioned configuration | Accepted |
| [0007](0007-spring-batch-for-runs.md) | Spring Batch for amortisation and close runs | Accepted |
| [0008](0008-no-manual-rate-override.md) | No manual rate or balance override, anywhere, ever | Accepted |
| [0009](0009-par-gap-as-the-ordering-baseline.md) | INV-2 orders against the schedule's par gap, not the annualised coupon | Accepted |
