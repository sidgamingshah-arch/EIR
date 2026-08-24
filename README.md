# EIR — Effective Interest Rate Engine

A calculation and sub-ledger platform for **effective interest rate accounting** in an Indian
commercial bank: EIR determination, amortised-cost roll-forward, integral fee and transaction-cost
amortisation, lifecycle event handling, and audit-grade traceability over large lending portfolios.

**Regulatory anchor:** RBI (Commercial Banks — Asset Classification, Provisioning and Income
Recognition) Directions, 2026 — "**ACPIR**" — effective **1 April 2027**, legacy book on EIR by
**31 March 2030**. IFRS 9 and Ind AS 109 are **interpretive sources only**, applied where ACPIR is
silent.

> **Status: Phase 1 implemented.** `eir-domain` and `eir-calc` are built and tested — the
> policy-agnostic computation core: value types, day counts, the rate solver, twelve projectors,
> amortisation, the ACPIR Stage 3 decomposition, the catch-up restatement, and event routing.
> Java 21 / Maven; see [ADR-0001](docs/adr/0001-modular-monolith.md). Everything from
> `eir-policy` outward is still specification only — see the
> [roadmap](docs/08-roadmap.md).

---

## The problem in one paragraph

ACPIR severs the link between the interest a bank *bills* and the interest it *reports*. Fees and
costs integral to originating a loan — processing fees collected, DSA and DMA payouts, incentives to
employees acting as selling agents — must be folded into the carrying amount at inception and
released to the P&L across expected life at a single constant rate. That rate has to be solved per
contract, re-derived when EBLR resets, restated with a catch-up when estimates are revised, and
reconciled back to the contractual interest the borrower is actually billed. Then the Indian overlay:
income is **not recognised at all** on Stage 3, yet the EIR discount on the expected credit loss
unwinds mechanically every period and must still be computed. Most banks do this in spreadsheets that
no auditor can replay — and ACPIR's automation mandate makes that untenable.

## What makes this an Indian product, not an IFRS 9 one

Four things an off-the-shelf IFRS 9 sub-ledger does not do:

| | ACPIR position | IFRS 9 |
|---|---|---|
| **Stage 3 interest** | **Not recognised.** But the ECL discount unwind must still be computed, because ACPIR 50 makes the EIR the ECL discount rate. | Recognised on the net carrying amount |
| **Penal charges** | **Hard-excluded** from every EIR stream and from the gross carrying amount (RBI 2023 framework) | No equivalent restriction |
| **Prudential floors** | Applied per product category; the **pre-floor** figure must survive as a first-class output (ACPIR 90) | No floors |
| **Reset vs catch-up** | **Silent.** Both mechanics adopted by election — and the IASB is actively amending the rule | B5.4.5 / B5.4.6 |

The Stage 3 arithmetic is worth stating, because it is the whole design in one line. On the
[Case 5](docs/reference-cases/case-05-stage-3-acpir-suppression.md) exposure:

```
gross-basis interest    5,506.79      what Stage 1/2 would recognise
  net-basis interest    3,304.08      what IFRS 9 would recognise
  ECL discount unwind   2,202.72      mechanical, must be computed
  ─────────────────────────────
  3,304.08 + 2,202.72 = 5,506.79      exact identity, invariant ST-2
  recognised under ACPIR:    0.00
```

The unwind is *precisely* the interest the gross basis would have earned on the allowance portion. So
the engine needs no separate unwind model — it computes one figure and decomposes it. That identity
is what makes the Indian treatment tractable.

## What this system does

| Capability | Summary |
|---|---|
| **EIR determination** | Solves the effective rate per contract or pool from the projected flow vector net of integral fees and costs. Actual-date and periodic-index conventions. Credit-adjusted EIR for POCI. |
| **Fee classification** | Versioned rule set closing ACPIR's silences — the B5.4.3 negative list, the probable-drawdown condition, the ACPIR 53 selling-versus-processing line. |
| **Amortisation** | Period-by-period roll-forward producing EIR interest and the delta against contractual interest. |
| **Event routing** | Reset versus catch-up decided by a **versioned mapping table** keyed on what each rate component compensates for — not hard-coded. |
| **Stage 3 handling** | Income suppressed; shadow unwind computed; interest-in-suspense as a first-class ledger; four-way reconciliation. |
| **Materiality tiering** | Instrument-level, pool-level, or documented approximation — with a mandatory equivalence test behind every shortcut. |
| **Sub-ledger & GL** | Contract-level balances and balanced, reconciled postings. |
| **Controls** | 15 named controls, maker–checker, immutable closed periods, nightly determinism replay. |
| **Transition** | ACPIR 19 day-1 fair valuation, legacy cohort migration, deemed EIR, ECL discount-basis tracking. |

## What this system deliberately does not do

It is **not** a loan management system and does not become the customer-facing book of record. The
CBS/LMS stays authoritative for contractual balances, billing and borrower statements; this engine
computes the accounting overlay — see
[ADR-0004](docs/adr/0004-delta-over-contractual-ledger.md).

It is **not** an ECL engine. Staging and allowances are consumed as versioned inputs. The boundary is
drawn in [00 §4](docs/00-product-vision.md#4-scope) and it will be pushed — ACPIR 50 makes the two
engines mutually dependent, which is an argument for sequencing, not for merging.

And there is **no manual rate override**, permanently — [ADR-0008](docs/adr/0008-no-manual-rate-override.md).

## Documentation map

Read in order on a first pass. Each document stands alone on a second.

| # | Document | What it settles |
|---|---|---|
| 00 | [Product vision](docs/00-product-vision.md) | Problem, users, scope boundary, success measures |
| 01 | [Domain primer](docs/01-domain-primer.md) | The accounting itself — concepts, the traps, the failure modes |
| 02 | [Functional specification](docs/02-functional-spec.md) | Capabilities and numbered requirements FR-1xx…FR-9xx |
| 03 | [Calculation specification](docs/03-calculation-spec.md) | **Normative.** The mathematics, solver, routing, invariants, tier gate |
| 04 | [Data model](docs/04-data-model.md) | Entities, bitemporal design, transition structures |
| 05 | [Architecture](docs/05-architecture.md) | Modules, runtime views, technology choices |
| 06 | [API specification](docs/06-api-spec.md) | REST surface and payloads |
| 07 | [NFRs, controls and audit](docs/07-nfr-controls-audit.md) | Scale, precision, the 15 controls, auditability |
| 08 | [Delivery roadmap](docs/08-roadmap.md) | Phasing against the statutory clock |
| 09 | [Cash-flow structures](docs/09-cashflow-structures.md) | The compositional structure model — amortising, bullet, balloon, step, moratorium, tranched, revolving, and optionality |
| — | [Reference cases](docs/reference-cases/README.md) | Nine worked cases; the acceptance fixtures |
| — | [Decision records](docs/adr/) | Eight ADRs with rationale and rejected alternatives |
| — | [Glossary](docs/glossary.md) | Terms and abbreviations |
| — | [ACPIR 2026 application reference](docs/reference/acpir-2026-eir-application-reference.md) | The domain authority: ACPIR spine, silence map, 38-product matrix, divergence register, jurisdiction dossiers |

## Reference cases

The [reference cases](docs/reference-cases/README.md) are the specification's centre of gravity —
prose describes, the worked cases decide. Nine cases, every intermediate figure stated, computed
independently of any implementation and used as a **merge gate**.

| # | Case | Headline |
|---|---|---|
| 1 | EMI loan with integral fees | EIR 13.248094% vs contractual 12.682503% p.a. |
| 2 | Full prepayment | 1,408.29 accelerated to P&L |
| 3 | Re-estimation catch-up | −627.42 charge, EIR unchanged |
| 4 | Floating-rate reset | EIR → 14.375386% p.a., nil catch-up |
| 5 | **Stage 3 under ACPIR** | 0.00 recognised; 3,304.08 + 2,202.72 = 5,506.79 |
| 6 | POCI credit-adjusted EIR | 29.141905% correct vs 64.703664% naive |
| 7 | Solver stress | Zero-coupon recovers 1.000000000000% exactly |
| 8 | B5.4.4 repricing shortcut | Year-1 fee 5,000.00 vs 3,591.71 |
| 9 | Straight-line vs EIR | Year 1 overstated by **81.0%** |

**If the engine disagrees with a reference case, the engine is wrong.**

Cases 3 and 4 are the pair to reach for when explaining the routing table: the same instrument, the
same month, opposite treatments, discriminated only by *why* the flows moved. Case 5 is the pair to
reach for when explaining why this is not an IFRS 9 product.

## The three decisions that shape everything else

1. **[ADR-0006](docs/adr/0006-configurable-event-routing.md) — routing is configuration.** The IASB's
   April 2026 tentative decision would amend B5.4.5, with an Exposure Draft expected H2 2026. Every
   rate component carries a tag for *what it compensates for*, and routing is a versioned mapping
   table. When the wording changes, the response is an approved policy version — not a re-engineering
   event on a live ledger. Build the switch, not the constant.
2. **[ADR-0004](docs/adr/0004-delta-over-contractual-ledger.md) — an overlay, not a replacement.** Two
   parallel legs per contract. The contractual leg ties to the CBS; the unamortised fee is *defined*
   as the gap between the legs, so it cannot drift.
3. **[ADR-0003](docs/adr/0003-event-sourced-recompute.md) — determinism by construction.** Nothing in
   the calculation path reads a wall clock. Every result stores the policy versions that produced it.
   A nightly replay of a sampled closed period turns the claim into a measurement.

## Repository layout (planned)

```
docs/                     specification (this phase)
  adr/                    architecture decision records
  reference/              ACPIR 2026 domain authority
  reference-cases/        golden numeric fixtures
eir-domain/               pure-Java domain model and mathematics, zero framework deps
eir-calc/                 projection strategies, rate solver, amortisation
eir-policy/               policy and rule-set resolution, event routing table, tiering
eir-application/          use-case orchestration
eir-persistence/          JPA mappings, repositories, Flyway migrations
eir-batch/                Spring Batch amortisation, close and transition runs
eir-gl/                   journal construction and GL interface
eir-api/                  REST controllers, DTOs, OpenAPI
eir-app/                  Spring Boot assembly
```

## Contributing during the design phase

Specification changes travel as pull requests against `docs/`. Two rules:

- A change that alters a number in a reference case must change the **generator**, and the pull
  request must say why the number moved. Reviewing a diff in a golden fixture is the point — a silent
  edit to an expected value is how a regression becomes a specification.
- A change that reverses a recorded decision **supersedes** the ADR rather than editing it. See
  [docs/adr/README.md](docs/adr/README.md).
