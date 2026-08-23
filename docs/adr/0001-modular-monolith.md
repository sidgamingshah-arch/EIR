# ADR-0001 — Modular monolith on Spring Boot, not microservices

**Status:** Accepted

## Context

The engine must compute an EIR per contract over ~10M contracts, roll amortised cost forward every
period, and produce a set of figures that **tie** — sub-ledger to GL, contractual leg to core
banking, Stage 3 four-way, pre-floor to post-floor. The defining requirement is not throughput or
deployment agility; it is that the numbers reconcile and that any published figure can be reproduced
years later.

## Decision

A single deployable Spring Boot application, internally decomposed into Maven modules with
dependencies pointing strictly inward. Horizontal scaling by running more instances of the same
artefact, with batch workers and API instances separated.

## Rationale

**The workload is one transactional close over one consistent dataset.** A month-end run reads
contracts, prior balances, events, staging and allowances, and writes balances and journals that must
balance. Distributing that means distributed transactions or eventual consistency in a financial
ledger — the wrong trade for something whose purpose is that the numbers tie.

**The invariants are cross-cutting.** SL-1 spans every contract and the GL. ST-2 spans the EIR leg,
the allowance and the ECL engine's input. A microservice boundary drawn anywhere through that set
turns an in-process assertion into a distributed query, and a failed assertion into a partial close.

**Module boundaries deliver the actual benefit.** What is wanted from microservices here is
enforced separation between the mathematics and the plumbing. `maven-enforcer` banned-dependencies
rules give that at build time, in-process, with none of the operational cost.

**Batch is the dominant workload.** Spring Batch's partitioned steps, restartability and skip
policies solve the scaling problem directly, without service decomposition.

## Consequences

- One deployment unit, one release cadence. Acceptable: there is one team and one statutory clock.
- Scaling is by instance, and partitioning is within the batch job rather than across services.
- Module discipline must be enforced mechanically. A build rule, not a code review convention.
- If a component ever genuinely needs independent scaling — a heavy reporting surface, say — it can
  be extracted later. Extracting from clean module boundaries is tractable; the reverse is not.

## Alternatives rejected

**Microservices per capability** (projection, solver, amortisation, ledger). Distributed transactions
across a financial close; cross-service invariant checking; network cost inside the inner loop of a
10M-contract run. No compensating benefit.

**Serverless per-contract computation.** Attractive for the embarrassingly-parallel solve, but the
close is a transactional unit with aggregate gates, and `BigDecimal`-heavy cold starts at this
volume are not economical.

**Database-resident computation** (stored procedures / PL/pgSQL). Fast, and genuinely tempting for
the roll-forward. Rejected because the mathematics is the audit surface and must be readable,
unit-testable and version-controlled as ordinary code. Reference cases running as plain JUnit is
worth more than the I/O saved.
