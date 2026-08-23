# EIR — Effective Interest Rate Engine

A calculation and sub-ledger platform for **effective interest rate accounting** under
**IFRS 9 / Ind AS 109**: EIR determination, amortised-cost roll-forward, integral fee and
transaction-cost amortisation, lifecycle event handling, and audit-grade traceability over
large lending portfolios.

> **Status: design phase.** This repository currently contains the product and technical
> specification only. No implementation code has been written yet. The target stack is
> Java 21 / Spring Boot — see [ADR-0001](docs/adr/0001-modular-monolith.md).

---

## The problem in one paragraph

Under Ind AS 109, a lender cannot recognise interest income at the rate written on the loan
agreement. Fees and costs that are *integral* to originating the loan — processing fees
collected, DSA commissions paid, direct incremental origination costs — must be folded into
the carrying amount at inception and released to the P&L across the instrument's expected
life at a single constant rate: the effective interest rate. That rate has to be solved for
per contract, re-derived when floating benchmarks reset, restated with a catch-up when
cash-flow estimates are revised, switched to a net basis when the asset becomes
credit-impaired, and reconciled back to the contractual interest the borrower is actually
billed. Most lenders do this in spreadsheets that no auditor can replay and no controller
can explain. This product replaces those spreadsheets.

## What this system does

| Capability | Summary |
|---|---|
| **EIR determination** | Solves the effective rate per contract (or per pool) from the projected cash-flow schedule net of integral fees and costs. Actual-date and periodic-index conventions both supported. |
| **Fee classification** | Rules engine deciding which fees and costs are integral to the EIR versus recognised as incurred, with a versioned, auditable policy. |
| **Amortisation** | Period-by-period amortised-cost roll-forward producing EIR interest income and the delta against contractual interest. |
| **Lifecycle events** | Prepayment, part-payment, rate reset, restructuring, moratorium, stage transfer, write-off, derecognition — each with the correct prospective or catch-up treatment. |
| **Sub-ledger & GL** | Contract-level balances and summarised, reconciled journal postings to the general ledger. |
| **Controls** | Maker–checker on policy, period close and lock, immutable closed periods, deterministic replay for restatement. |
| **Disclosure** | Movement schedules and reconciliations supporting Ind AS 107 / IFRS 7 disclosure. |

## What this system deliberately does not do

It is **not** a loan management system and does not become the customer-facing book of
record. The LMS remains authoritative for contractual balances, billing and borrower
statements; this engine computes the *accounting overlay* on top of it. See
[ADR-0004](docs/adr/0004-delta-over-contractual-ledger.md) — this is the single most
consequential decision in the design.

It is also not an ECL/impairment engine. It consumes staging and allowance figures as
inputs. The boundary is drawn in [§ Scope](docs/00-product-vision.md#4-scope).

## Documentation map

Read in order on a first pass. Each document stands alone on a second.

| # | Document | What it settles |
|---|---|---|
| 00 | [Product vision](docs/00-product-vision.md) | Problem, users, value, scope boundary, success measures |
| 01 | [Domain primer](docs/01-domain-primer.md) | The accounting itself — concepts, standard references, the traps |
| 02 | [Functional specification](docs/02-functional-spec.md) | Capabilities and numbered functional requirements |
| 03 | [Calculation specification](docs/03-calculation-spec.md) | The mathematics, the solver, rounding policy, worked examples |
| 04 | [Data model](docs/04-data-model.md) | Entities, relationships, temporal design |
| 05 | [Architecture](docs/05-architecture.md) | Modules, runtime views, technology choices |
| 06 | [API specification](docs/06-api-spec.md) | REST surface and payloads |
| 07 | [NFRs, controls and audit](docs/07-nfr-controls-audit.md) | Scale, precision, security, auditability, financial controls |
| 08 | [Delivery roadmap](docs/08-roadmap.md) | Phasing, milestones, what ships when |
| — | [Reference cases](docs/reference-cases/README.md) | Seven fully-worked numeric cases; the acceptance fixtures |
| — | [Decision records](docs/adr/) | Architecture decisions with rationale and consequences |
| — | [Glossary](docs/glossary.md) | Terms and abbreviations |

## Reference cases

The [reference cases](docs/reference-cases/README.md) are the specification's centre of
gravity. Seven cases — a vanilla EMI loan with fees, full prepayment, a B5.4.6 re-estimation
catch-up, a B5.4.5 floating reset, a Stage 3 net-basis switch, a POCI credit-adjusted EIR,
and a set of solver stress instruments — are worked end to end with every intermediate
figure stated. They were computed independently of any implementation and become the golden
fixtures the engine is tested against. If the engine disagrees with them, the engine is wrong.

## Repository layout (planned)

```
docs/                     specification (this phase)
  adr/                    architecture decision records
  reference-cases/        golden numeric fixtures
eir-domain/               pure-Java domain model and mathematics, zero framework deps
eir-calc/                 schedule projection, rate solver, amortisation
eir-application/          use-case orchestration, policy resolution
eir-persistence/          JPA mappings, repositories, migrations
eir-batch/                Spring Batch amortisation and close runs
eir-gl/                   journal construction and GL interface
eir-api/                  REST controllers, DTOs, OpenAPI
eir-app/                  Spring Boot assembly and configuration
```

## Contributing during the design phase

Specification changes travel as pull requests against `docs/`. A change that alters a number
in a reference case must update the case and say why in the PR description. A change that
reverses a recorded decision supersedes the ADR rather than editing it — see
[docs/adr/README.md](docs/adr/README.md).
