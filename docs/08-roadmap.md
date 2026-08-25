# 08 — Delivery Roadmap

Anchored on the ACPIR 2026 statutory clock, not on an arbitrary internal cadence. As at
**August 2026** the effective date is roughly **seven months away**.

```
Aug 2026 ────────── 1 Apr 2027 ──── Mar 2028 ──────── 31 Mar 2030 ──── 2031
   |                    |               |                   |            |
 today            ACPIR effective   first comparative   legacy book   CET1 add-back
                  new origination   disclosure          fully on EIR  fully unwound
                  on EIR;                               (ACPIR 21+50)
                  day-1 fair value
                  (ACPIR 19)
```

---

## 0. The sequencing argument

**EIR is upstream of ECL**, because ACPIR 50 makes the EIR the ECL discount rate. Every programme
that runs EIR as a finance-team workstream parallel to the risk team's ECL build discovers this at
integration testing. Sequence EIR first and the ECL build inherits a clean discount rate; sequence
it second and the ECL model is rebuilt twice.

**The binding constraint is data, not accounting.** Brazil ran the closest structural analogue —
central-bank-led convergence, CMN 4966 — with a four-year lead time, and banks were still
unprepared at go-live because the granularity of required data was underestimated. India has one
year to the effective date. The fee and cost taxonomy is therefore the critical path, and it is a
data-sourcing problem owned jointly with HR and finance, not an accounting one.

The specific gap: ACPIR 53 capitalises incentives paid to **employees acting as selling agents** and
excludes internal credit-appraisal cost. Source HR and cost-centre data is structured along neither
line. Closing that is a months-long exercise with no shortcut, and nothing downstream can be
correct until it is closed.

---

## Phase 0 — Specification (current)

**Status: complete for review.** This document set.

| Deliverable | State |
|---|---|
| Product vision, scope boundary | [00](00-product-vision.md) |
| Domain primer with ACPIR anchor | [01](01-domain-primer.md) |
| Functional requirements, FR-1xx…FR-9xx | [02](02-functional-spec.md) |
| Normative calculation specification | [03](03-calculation-spec.md) |
| Data model with bitemporal and transition structures | [04](04-data-model.md) |
| Architecture and module boundaries | [05](05-architecture.md) |
| API surface | [06](06-api-spec.md) |
| NFRs, 15 controls, audit position | [07](07-nfr-controls-audit.md) |
| Nine golden reference cases | [reference-cases](reference-cases/README.md) |
| Decision records | [adr](adr/) |

**Exit gate:** the thirty-three policy positions in
[reference §9](reference/acpir-2026-eir-application-reference.md) tabled with the ACPIR 57
sub-committee. The engine cannot be built ahead of the positions it implements — but see Phase 1,
which is deliberately built to be *policy-agnostic* so it can start now.

---

## Phase 1 — Core engine (Sep–Nov 2026) — **DELIVERED**

Everything here is independent of the unresolved policy positions, which is why it goes first.

| Workstream | Deliverable | Requirements |
|---|---|---|
| `eir-domain` | Money, Rate, day-count strategies, invariant definitions. Framework-free, `double` banned. | [03 §1](03-calculation-spec.md#1-numeric-foundation) |
| `eir-calc` — solver | Newton–Raphson + **mandatory** bisection fallback; `NoSolution` and `MultipleRoots` raising correctly | FR-401…403 |
| `eir-calc` — projection | Annuity, bullet, balloon, step, moratorium, tranched, discount, external-schedule projectors | FR-302…305 |
| `eir-calc` — amortisation | Gross-basis roll-forward, broken periods by compound accretion, event ordering | FR-501…503 |
| Reference cases | All 9 as a **merge gate** | [reference-cases](reference-cases/README.md) |
| Invariant sweep | Property-based (jqwik) over generated contracts | [05 §6](05-architecture.md#6-testing-strategy) |

**Exit gate: met.** All nine reference cases pass to the paisa; the property sweep runs clean with
zero tolerance on IC-1, INV-3, INV-4 (at every period), ST-2, S3-2, CU-1 and CU-2; the
shifted-clock determinism test passes.

The sweep's size is worth stating accurately, because this line previously read "149,000 generated
contracts" and that was two claims too strong. 148,300 is the total **property tries**, and most of
them are not contracts:

| Property class | Tries | What each try is |
|---|---:|---|
| `RateOrderingProperties` | 22,000 | a generated contract, projected, solved and amortised |
| `ReconciliationSweep` | 20,000 | a generated contract, projected, solved and amortised |
| `NumericProperties` | 60,000 | a `BigDecimal` or a date pair — precision and day-count laws |
| `SolverProperties` | 20,000 | a flow vector handed straight to the solver |
| `StageAndCatchUpProperties` | 25,000 | a Stage 3 decomposition or a catch-up from supplied balances |
| `Stage3IdentityProperty`, `SolverRoundTripProperties` | 1,300 | a decomposition or a single vector |

So **42,000** tries take a contract through the whole pipeline; the other 106,300 exercise a stage of
it. Both numbers are worth having and only one of them is the one that sentence was claiming.

Every property except the three in `SolverRoundTripProperties` is **fixed-seeded**, which makes the
sweep a large deterministic fixture rather than a random search. That is deliberate: a ledger
engine's tests are also its replay evidence (invariant DT-1), and a failing case nobody can reproduce
from the test source is worth less than one they can. But it means the sweep does not widen with
repeated runs, and new coverage has to come from new generators or new seeds rather than from running
it again.

Integration found four defects a module-wide compile could not, because the four packages contained
no cross-package imports — the build was green on arrival only because nothing referenced anything.
The lesson is worth carrying into Phase 2: **a green build across independently-written packages is
evidence of nothing until something exercises the seams.** Two further defects came from the
reference cases themselves, both the same root cause — a derived or synthetic amount rounded to
currency scale before entering a solve, which [03 § 1.2](03-calculation-spec.md#12-working-precision)
forbids.

> Deliberately excluded from Phase 1: anything requiring a settled policy position. The solver does
> not need to know whether ESG ratchets reset or catch up; it needs to solve. Building the
> policy-independent core first is what makes the seven-month deadline survivable.

---

## Phase 2 — Policy, routing and persistence (Nov 2026–Jan 2027) — **CODE DELIVERED**

| Workstream | Deliverable | Requirements | State |
|---|---|---|---|
| `eir-policy` | Versioned policy and fee rule sets; maker–checker; **mandatory** impact preview | FR-201…210 | `policy/{approval,preview,registry}` — five states, not a flag; the activation gate refuses EFFECTIVE without a preview for *that draft* |
| **Routing table** | Driver→mechanism mapping as versioned data, not code | FR-504…507, [ADR-0006](adr/0006-configurable-event-routing.md) | `policy/routing` — a text format displaces `ofSpecDefaults`, and a registry selects the version in force |
| Tier assignment | Tier 1/2/3 with equivalence-test tracking | FR-107, FR-411…412 | `policy/tier` — the TG-1 evaluator that was previously a label with no logic |
| `eir-persistence` | Bitemporal schema, partitioning, Flyway migrations | [04](04-data-model.md) | **DDL only.** Two PostgreSQL 16 migrations, 54 tables, verified by execution. No ORM — see below |
| Exception queue | All categories; per-contract failure isolation | FR-905 | `policy/exception` — the ten categories of 04 § 3, with the barrier that captures a per-contract failure instead of propagating it |
| Fee & cost taxonomy | **The critical path.** Fee master with EIR-eligibility flags; `cost_function` sourcing from HR and cost-centre data | FR-203 | `policy/fee` — the rule set, resolver, cost-function type, commitment thresholds and exclusion rules. **The sourcing half is not code and is not done** |

**Exit gate: met in code.** A fee code cannot reach the engine unclassified — an unmapped code
returns a refusal naming the key and files under `UNMAPPED_FEE_CODE`, never a default. A policy
version cannot go effective without an impact preview for that draft's content fingerprint. The
routing table changes without a code deploy: a table emitted to text, edited, re-parsed and
registered produces the routed decision, with the fixed-rate override still in code where its
premise belongs.

**What "delivered" does not mean here.** Three qualifications, because the gate is about code and
the phase is not:

- **The fee and cost taxonomy's critical path is untouched.** What exists is the machinery that
  classifies a fee once someone has said what it is. The months-long exercise § 0 names — sourcing HR
  and cost-centre data along the ACPIR 53 selling-agent-versus-appraisal line it is structured along
  neither — is a data problem owned with other teams, and nothing here advances it.
- **`eir-persistence` is DDL, not persistence.** The root enforcer bans JPA, Hibernate, Spring and
  Jackson across every module, so wiring an ORM needs that ban restructured — a shared root-pom
  change that deserves its own attention. Migrations use Flyway's naming so that wiring is an
  addition rather than a rewrite.
- **Four invariants are specified but not published.** `PG_1` (no version effective without a current
  preview), `RT_1` (every routed event resolves to a table in force), `PV_1` (a policy version
  resolves for every date in a closed period) and `RS_1` (every fee code has a per-code default) were
  each identified by the unit that needed them. They are absent from `InvariantId`, so those four
  checks currently return plain data or borrow another id. Until they exist, four controls are
  computed and not asserted.

> **Start the fee and cost taxonomy in Phase 0, not Phase 2.** It appears here because that is where
> it completes, but it is the longest-lead item in the programme and it depends on other teams. If
> only one thing starts early, it is this.

**The integration lesson repeated, and is worth stating twice.** Phase 1 recorded that "a green build
across independently-written packages is evidence of nothing until something exercises the seams."
Phase 2's units were built in parallel and every one was green on its own module before merge — and
the schema's two halves still could not be joined. `V1` wrote surrogate keys as `UUID` and `V2` as
`BIGINT`, because 04 calls `contract_id` a "surrogate key" and never states its SQL type; applied to
a live cluster, all seventeen of `V2`'s foreign keys into `V1`'s tables failed to form while both
migrations reported success. No unit's tests could see it, because no unit could see the other's
file. It was found by **executing the DDL**, not by reading it, which is why the module README makes
execution the verification and says plainly that a zero exit from `psql` is not the check.

---

## Phase 3 — Impairment interaction and India divergence (Jan–Mar 2027)

The part no off-the-shelf IFRS 9 sub-ledger provides.

| Workstream | Deliverable | Requirements |
|---|---|---|
| Stage 3 suppression | Income suppressed; shadow unwind computed; four-way reconciliation | FR-602…606 |
| Suspense ledger | First-class ledger object, not a memorandum | FR-604 |
| Cure | Prospective resumption, no catch-up | FR-607 |
| Pool-level suspension | For cards and KCC, with approved pool definitions | FR-608 |
| Floor duality | Pre-floor ECL retained alongside post-floor | FR-609 |
| POCI | Credit-adjusted EIR; no day-1 allowance; retained on cure | FR-407…408, FR-110 |

**Exit gate:** [Case 5](reference-cases/case-05-stage-3-acpir-suppression.md) and
[Case 6](reference-cases/case-06-poci-credit-adjusted-eir.md) pass; invariants ST-2, S3-1, S3-2,
PF-1, POCI-1 assert green on a full synthetic portfolio.

---

## Phase 4 — Transition readiness (Jan–Mar 2027, parallel)

Must be ready **before** 1 April 2027; runs alongside Phase 3.

| Workstream | Deliverable | Requirements |
|---|---|---|
| Day-1 fair valuation | ACPIR 19 run over the whole loan book; difference to opening retained earnings | FR-908 |
| **Paragraph 19 rebuttal evidence** | The file supporting carrying cost as best evidence of fair value — built during FY27, **not** at the transition date | [04 §6](04-data-model.md#6-transition-specific-structures) |
| Below-market origination | Staff and concessional loans at fair value; day-1 difference to employee benefit cost | FR-909, [reference §5 item 10](reference/acpir-2026-eir-application-reference.md) |
| Legacy cohort segmentation | Expected run-off vs 31 March 2030; migration priority | FR-908 |
| Deemed EIR | Methodology and derivation recording for cohorts where full reconstruction is infeasible | FR-909 |
| ECL discount basis tracking | `CONTRACTUAL_INTERIM` vs `EIR` per contract, tracked against ACPIR 50 | [04 §6](04-data-model.md#6-transition-specific-structures) |

**Exit gate:** the fair-valuation run completes over the full book with a rebuttal evidence
reference on every contract where the presumption was applied.

> **Prioritise legacy reconstruction by survival, not by size.** Reconstructing an EIR for a loan
> maturing in 2029 is wasted effort. Sequence the cohorts expected to remain on the books beyond
> 31 March 2030 first.

---

## Phase 5 — Batch, ledger and close (Feb–Apr 2027)

| Workstream | Deliverable | Requirements |
|---|---|---|
| `eir-batch` | Spring Batch partitioned runs; restartability; per-contract isolation | [ADR-0007](adr/0007-spring-batch-for-runs.md) |
| `eir-gl` | Balanced journals; summarised GL postings | FR-802…803 |
| Close workflow | Hard gates; immutable closed periods; read-only partitions | FR-901…902 |
| Replay | Shadow-table replay with byte comparison; nightly sampled run | FR-903, C-12 |
| Reconciliations | SL-1, C-14 to core banking, C-04, C-05 | FR-803…804 |
| `eir-api` | Full surface including the trace endpoint | [06](06-api-spec.md) |

**Exit gate:** a 10M-contract synthetic close inside 4 hours; a replay of that close is
byte-identical; the close workflow refuses to close on any red invariant.

---

## Go-live — 1 April 2027

**Two distinct gates on the same date**, and they are frequently conflated:

1. **New origination on EIR** (ACPIR 20). Every loan originated from this date measured at fair
   value ± directly attributable transaction costs, thereafter amortised cost using the EIR method.
2. **Day-1 fair valuation of the whole book** (ACPIR 19). Difference to opening retained earnings,
   not P&L.

ACPIR 50's concession — the contractual rate as an **interim** ECL discount factor — buys time on
ECL discounting but not on new-origination EIR. Do not let the concession be read as a general
deferral.

---

## Phase 6 — Post-go-live (FY28)

| Workstream | Deliverable |
|---|---|
| Investment book | Execute the EIR-versus-straight-line decision with the differential **quantified before** adoption. [Case 9](reference-cases/case-09-straight-line-vs-eir.md) is the argument. |
| Liability side | Execute the symmetry election, or document the asymmetry and its NIM consequence |
| Hedge accounting | Basis adjustment amortisation; the three-way modification case; portfolio-level ledger | FR-701…705 |
| Legacy migration | Execute cohort by cohort against the 2030 deadline |
| First comparative | March 2028 annual disclosure under the ECL framework |

**On interim comparatives.** Interim periods from June to December 2027 may present current-period
provisioning on ECL against prior period on the erstwhile method without restatement. That relief is
real and worth using — but it ends at the March 2028 annual reporting date, so the EIR-derived
comparative must be reconstructible by then. Which means the replay capability from Phase 5 is a
*reporting* dependency, not just a control.

---

## Phase 7 — Watch items

Not scheduled, because their timing is not ours.

| Item | Trigger | Response |
|---|---|---|
| **IASB Exposure Draft on Amortised Cost Measurement** | Expected H2 2026; the April 2026 tentative decision would amend B5.4.5 | Change the **routing table version**, not the engine. This is the entire point of [ADR-0006](adr/0006-configurable-event-routing.md). |
| IASB modification project | Feb 2025 tentative decision toward principles-based substantiality | Update qualitative triggers in policy |
| ACPIR amendment Directions | Any | Re-run the divergence register; new policy version |
| Investment Portfolio Directions 2026 amendment | Text not reviewed in the domain reference | Confirm whether it settles the amortisation-method gap before finalising the investment-book position |
| Derivative provisioning successor | The repealed 2025 Directions para 115 has no visible successor | Raise through the IBA; hold the conservative reading meanwhile |
| CET1 add-back unwind | 80% → 20% across 2027–2031 | Disclose the fully-loaded position from the first reporting date |

---

## What is deliberately not in scope

| Excluded | Rationale |
|---|---|
| ECL / impairment measurement | Consumed as versioned input. Different discipline, owners, cadence, validation. [05 §1.2](05-architecture.md#12-the-boundary-that-will-be-pushed). |
| Loan origination and servicing | The CBS/LMS remains the customer-facing book of record. [ADR-0004](adr/0004-delta-over-contractual-ledger.md). |
| General ledger | The engine posts to one; it is not one. |
| Fair value measurement (beyond ACPIR 19 transition) | Valuation comes from elsewhere; the engine supplies the EIR interest leg for FVOCI. |
| Regulatory capital | Floors and CCFs consumed as inputs; the capital workstream owns them. |
| Derivative valuation | Out. The engine handles the **hedge basis adjustment** interaction only. |
| Manual rate override | Refused by design, permanently. [07 §5](07-nfr-controls-audit.md#5-automation-mandate). |

---

## Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| **Fee/cost taxonomy not sourced in time** | Nothing downstream is correct. The Brazilian failure mode. | Start in Phase 0. Joint ownership with HR and finance. Weekly tracking to the sub-committee. |
| **Policy positions unresolved at build time** | Rework | Phase 1 is deliberately policy-agnostic; policy enters as versioned data in Phase 2 |
| **IASB ED changes the reset rule after go-live** | Would be a re-engineering event in a naive design | Routing as configuration ([ADR-0006](adr/0006-configurable-event-routing.md)) |
| Straight-line adopted as an expedient | The Cambodia failure mode; contaminates ECL through the discount rate | [Case 9](reference-cases/case-09-straight-line-vs-eir.md); FR-412; C-10 |
| Silent contractual-rate fallback on non-convergence | Reproduces the pre-ACPIR position invisibly | FR-402; explicit test that no path defaults; `NoSolution` rate monitored |
| Behavioural life optimistic and unchallenged | The UK restatement pattern; 3.73× leverage on year-1 fee recognition | Independent validation of curves under ACPIR Chapter V; back-testing with escalation triggers |
| Close window missed at scale | Regulatory reporting delay | Event-triggered solving; B5.4.4 election; partitioned batch; 10M-contract load test as a Phase 5 exit gate |
| Scope creep into ECL | Two systems become one and neither is validated properly | Boundary stated in [00 §4](00-product-vision.md#4-scope) and enforced by versioned-input contract |
