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
| `eir-persistence` | Bitemporal schema, partitioning, Flyway migrations | [04](04-data-model.md) | **DDL only, and now with an implementation beside it.** Two PostgreSQL 16 migrations, 54 tables, verified by execution. `eir-persistence-jdbc` ([ADR-0011](adr/0011-jdbc-persistence-behind-a-profile.md)) implements all seven ports over that DDL with both temporal predicates on every read, so a read as at an earlier `recorded_at` returns the version set recorded then — which is the first time DT-1 has anything real to check, since the in-memory book answers the same thing at every boundary. Behind a `jdbc` profile: the driver and Flyway are not cached, and `mvn -o install` must keep building the whole engine |
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
- ~~**`eir-persistence` is DDL, not persistence.**~~ **Answered by `eir-persistence-jdbc`**, which is
  hand-written JDBC rather than an ORM, so the paragraph below still describes the constraint the
  implementation was written under. The root enforcer bans JPA, Hibernate, Spring and
  Jackson across every module, so wiring an ORM needs that ban restructured — a shared root-pom
  change that deserves its own attention. Migrations use Flyway's naming so that wiring is an
  addition rather than a rewrite.
- **`eir-application`, `eir-batch`, `eir-gl`, `eir-api` and `eir-app` do not exist.** 05 § 2 names
  nine modules and four now exist. Phase 2 never promised the rest, but "policy, routing and
  persistence delivered" should not be read as an engine anything can call: there is no
  orchestration layer, no run, no journal and no API.
  *Superseded:* `eir-gl` landed in Phase 5, then `eir-application`, then `eir-api`, then `eir-batch`
  and `eir-persistence-jdbc`. **All nine of 05 § 2's modules now exist**, counting `eir-api` as the
  console `eir-app` was to have been — it serves 06's surface and the month-end page from one module,
  and a separate front-end module would have been a second deployable for one HTML file.

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

## Phase 3 — Impairment interaction and India divergence (Jan–Mar 2027) — **CODE DELIVERED**

The part no off-the-shelf IFRS 9 sub-ledger provides.

| Workstream | Deliverable | Requirements | State |
|---|---|---|---|
| Stage 3 suppression | Income suppressed; shadow unwind computed; four-way reconciliation | FR-602…606 | `Stage3Decomposition` shipped in Phase 1; the **four-way reconciliation was the gap** — see below |
| Suspense ledger | First-class ledger object, not a memorandum | FR-604 | `amort/SuspenseLedger` — an opening balance and three movements, with no release-to-income on cure |
| Cure | Prospective resumption, no catch-up | FR-607 | `Stage3Decomposition.onCure` + `SuspenseLedger.onCure`, asserted by **CR-1** |
| Pool-level suspension | For cards and KCC, with approved pool definitions | FR-608 | `policy/pool` — the definition carries a `POOL_DEFINITION` version, asserted by **PL-1** and **PL-2** |
| Floor duality | Pre-floor ECL retained alongside post-floor | FR-609 | `amort/FloorApplication` — **PF-1** finally has an evaluator, plus **PF-2** for the ACPIR 90 basis rule |
| POCI | Credit-adjusted EIR; no day-1 allowance; retained on cure | FR-407…408, FR-110 | `amort/PociAmortisation`, shipped in Phase 1; Case 6 green |

**Exit gate:** [Case 5](reference-cases/case-05-stage-3-acpir-suppression.md) and
[Case 6](reference-cases/case-06-poci-credit-adjusted-eir.md) pass; invariants ST-2, S3-1, S3-2,
PF-1, POCI-1 assert green on a full synthetic portfolio.

**Exit gate: met in code, with one honest reading of the last clause.** Both reference cases pass —
Case 5 now includes the four-way reconciliation its own text calls for ("Control S3-1 reconciles all
four every period"), which it had never asserted. ST-2, S3-2, S3-1 and PF-1/PF-2 are swept over
generated balances, allowances, rates and part payments. **POCI-1 is not swept, and should not be.**
`PociAmortisation.afterCure` takes no rate parameter, so the rate cannot move across a cure by
construction; a generated property over it would assert what the signature already guarantees. That
is the third tautology this programme has found wearing an invariant id, and the pattern is now
documented in [03 § 9](03-calculation-spec.md#9-invariants). POCI-1 is asserted where it can
actually fail — `assertRateRetained` at a boundary where the rate has round-tripped through
persistence or a generic event handler that re-solves by default.

**The finding worth carrying forward: S3-1 was four claims and none of them was S3-1.** Its
statement is "Stage 3 four-way reconciliation" and it was published from four places — a tautology,
FR-607's no-catch-up rule, and both halves of FR-610's staging-is-not-an-EIR-event, one of them
carrying a *rate* deviation under an id whose others carried rupees. Each was a real control,
correctly computed. What was absent was the one the id names, and it was absent structurally: the
four quantities do not live in one place. `Stage3Decomposition` can see the EIR accrual and the
recognised amount and cannot see the carrying-amount ledger or the suspense balance, so what it
published under the id was whatever it could compute from what was in scope. Interest-in-suspense
being a single field rather than a ledger (FR-604) is the same gap from the other side — which is
why FR-604 and FR-605 turned out to be one change and not two.

This is the same defect as Phase 2's `DT_1` borrowing and the `PC-1` arity problem, and the
mechanism is identical every time: `InvariantResult.conjunction` keeps only the **first** breach's
deviation among results sharing an id. **A computed control nobody asserts is indistinguishable
from one that does not exist, and a control asserting something other than its own statement is
worse — it reads as coverage.**

**What "delivered" does not mean here.** Three qualifications:

- **FR-608's approved artefact has no table.** [04](04-data-model.md) has a `POOL`, and it is a
  *different* pool: 04 § 2.10's Tier 2 measurement cohort, with homogeneity criteria, a pool EIR
  and back-testing (FR-409). A suspension pool is a product book approved for income suppression.
  They share a word and nothing else, and only one of them is in the schema.
- **The stage and allowance inputs are still consumed, not produced** (FR-601), which is correct per
  [00 § 4](00-product-vision.md#4-scope) — but it means nothing here has been exercised against a
  real ECL feed, only against generated and reference-case values.
- **Still no orchestration layer.** `eir-application`, `eir-batch`, `eir-gl`, `eir-api` and
  `eir-app` do not exist. There is no run that walks a portfolio calling any of this, so
  "reconciled every period" is a property of the types rather than of a close.
  *Superseded:* `eir-gl` and `eir-application` landed in Phase 5. `RunClose` walks a population and
  closes it, so this is now a property of a close — over a population supplied through a port, not
  yet over a database. See Phase 5.

---

## Phase 4 — Transition readiness (Jan–Mar 2027, parallel) — **CODE DELIVERED**

Must be ready **before** 1 April 2027; runs alongside Phase 3.

| Workstream | Deliverable | Requirements | State |
|---|---|---|---|
| Day-1 fair valuation | ACPIR 19 run over the whole loan book; difference to opening retained earnings | FR-908 | `policy/transition/TransitionFairValue`, `TransitionValuationRun` |
| **Paragraph 19 rebuttal evidence** | The file supporting carrying cost as best evidence of fair value — built during FY27, **not** at the transition date | [04 §6](04-data-model.md#6-transition-specific-structures) | Invariant **TF-1**, per contract and per run |
| Below-market origination | Staff and concessional loans at fair value; day-1 difference to employee benefit cost | FR-909, [reference §5 item 11](reference/acpir-2026-eir-application-reference.md) | `BelowMarketOrigination`, invariant **BM-1**; `PolicyKind.POLICY_POSITION` added to carry the Board position |
| Legacy cohort segmentation | Expected run-off vs 31 March 2030; migration priority | FR-908 | `LegacyCohort`, `LegacyMigrationPlan`, invariant **LC-1** |
| Deemed EIR | Methodology and derivation recording for cohorts where full reconstruction is infeasible | FR-909 | `DeemedEirDerivation`, invariant **DE-1** |
| ECL discount basis tracking | `CONTRACTUAL_INTERIM` vs `EIR` per contract, tracked against ACPIR 50 | [04 §6](04-data-model.md#6-transition-specific-structures) | `ContractMigrationState`, `MigrationTracker`, invariant **TM-1** |

**Exit gate:** the fair-valuation run completes over the full book with a rebuttal evidence
reference on every contract where the presumption was applied.

**Exit gate: met on the second half, and the first half is not this phase's to meet.** TF-1 asserts
the rebuttal evidence over a population and per contract, and all five Phase 4 invariants are swept
over generated populations as well as pinned on fixtures — 2,700 tries across six properties. Four
of the five publish a **count** as their deviation, and a count is exactly the figure that comes out
right on a four-row fixture and wrong on a population: an off-by-one at a boundary, a filter that
drops a category, a shortcut that holds for the ordering the fixture happens to use.

Every property recomputes the answer from the invariant's definition rather than from a run of the
code. LC-1 is the clearest: it is *defined* pairwise — no survivor of the 2030 deadline queued behind
any cohort that runs off before it — and implemented by comparing against the minimum non-survivor
priority. The property computes the pairwise form and compares, which is what establishes that the
shortcut computes the same set. Confirmed by mutation: `min` for `max`, `>` for `>=`, and TM-1's
`isAfter` for `!isBefore` were each introduced deliberately and each was caught, one of them shrunk
to a minimal counterexample in eighteen steps. A property suite that has never been seen to fail is
not evidence, and this programme has recorded three tautologies wearing invariant ids already. "Completes over the full book" is a
reconciliation between the valuation population and the contract master, and
`TransitionValuationRun.coverageAgainst` performs it when a caller supplies the book size — but the
run cannot know that a contract exists and was never presented to it, so the completeness half needs
a caller that can see both. Saying so is more useful than a control that implies otherwise.

> **Prioritise legacy reconstruction by survival, not by size.** Reconstructing an EIR for a loan
> maturing in 2029 is wasted effort. Sequence the cohorts expected to remain on the books beyond
> 31 March 2030 first.

**That warning is now a control, and the reason is that the wrong answer looks right.** LC-1 refuses
a plan where a cohort surviving 31 March 2030 is queued behind one that runs off before it. The test
fixture makes the trap concrete: a 1,200,000-contract auto book running off in 2029 against an
800-contract project finance cohort running to 2038. Ordered by size the 1.2 million-contract cohort
goes first, and it is the one that never needs a reconstructed rate at all.

**What "delivered" does not mean here.** Four qualifications:

- **The fair value is an input, not a computation.** [The scope table below](#what-is-deliberately-not-in-scope)
  excludes fair value measurement beyond the ACPIR 19 transition, and what is in scope is the
  *application*: recording the measurement, computing the difference to opening retained earnings,
  and controlling the evidence. Nothing here values a loan.
- **Silence 6 is still a silence.** ACPIR 19 and 20 require fair value at initial recognition and
  give no guidance on the day-1 difference — [reference § 4](reference/acpir-2026-eir-application-reference.md)
  marks it `[MED-HIGH]`. BM-1 asserts that a Board position was taken, not that any particular
  destination is right. The reference's reading for staff loans — the shortfall is employee
  compensation, not a lending loss — is a reading, and the position is one of the thirty-three
  Phase 0 puts in front of the ACPIR 57 sub-committee.
- **No transition job runs any of this.** 05 § 2 puts transition jobs in `eir-batch`, which does not
  exist, along with `eir-application`, `eir-gl`, `eir-api` and `eir-app`. These are types a run
  would call, and there is no run.
  *Still true of the transition types specifically*, and narrower than it was: `eir-application`
  exists and runs the month-end and replay paths (Phase 5), but nothing in it reaches the
  `policy/transition` package. That remains the untouched half.
- **The legacy cohort definitions are strings.** `LegacyCohort.definition` carries what the cohort
  selects as free text, matching the schema's `JSONB`. Nothing evaluates it, so a cohort's
  membership is asserted rather than derived — which is the right place to stop, since the
  segmentation criteria are a programme decision, but it means `contractCount` is supplied and not
  counted.

---

## Phase 5 — Batch, ledger and close (Feb–Apr 2027) — **FRAMEWORK-FREE CORE DELIVERED**

| Workstream | Deliverable | Requirements | State |
|---|---|---|---|
| `eir-gl` | Balanced journals; summarised GL postings | FR-802…803 | `gl/journal` and `gl/posting`. **SL-2** and **SL-1** both had identifiers and no evaluator |
| Close workflow | Hard gates; immutable closed periods; read-only partitions | FR-901…902 | `policy/close` — refusals as values, exception acceptance under four eyes, **CL-1** on immutability |
| Replay | Shadow-table replay with byte comparison; nightly sampled run | FR-903, C-12 | `policy/replay` — **DT-1** with a scale-sensitive comparison and the policy-then-in-force half |
| Reconciliations | SL-1, C-14 to core banking, C-04, C-05 | FR-803…804 | `policy/reconciliation` — **RC-1**; C-04 and C-05 landed in Phase 3 |
| `eir-batch` | Spring Batch partitioned runs; restartability; per-contract isolation | [ADR-0007](adr/0007-spring-batch-for-runs.md) | **Delivered.** `AmortisationBatchJob` over `PartitionPlan`, restart from `RunProgressStore`, per-contract isolation through FR-905's existing barrier, and `NightlyReplaySchedule` — which gives `NightlyReplayReport` the caller this section recorded it lacking. Offline-safe: Spring Batch 5.1.2 is cached, and the module takes ADR-0010's exemption with the authority named in its pom |
| `eir-api` | Full surface including the trace endpoint | [06](06-api-spec.md) | **Delivered.** 41 routes across eleven modules on one `Routes` seam: the trace endpoint (FR-808), runs and periods with 06's specified 409 on a refused close, policy versions behind the impact-preview gate (FR-210), fee rule sets and routing tables, the exception work queue, the movement schedule (FR-805, now under `InvariantId.MV_1`), the four reconciliation reports, the approximations register and Ind AS 107 extract (FR-809), contracts and events, the five transition endpoints, and FR-906's role model. Framework-free on `com.sun.net.httpserver`, no ADR-0010 exemption, so the repo still builds and runs offline. **Authorisation is modelled and not enforced** — see the qualification below |
| `eir-application` | Framework-free orchestration: ports, onboarding, the per-contract run, replay, the run-level close | 05 § 3.1–3.3 | Delivered. The layer that gives every Phase 5 control a caller; see the first qualification below |

**Exit gate:** a 10M-contract synthetic close inside 4 hours; a replay of that close is
byte-identical; the close workflow refuses to close on any red invariant.

**Exit gate: two of three met, and the third is now blocked on something other than speed.**
Measured by [`tools/load-harness`](../tools/load-harness/RESULTS.md), which replaces the estimates
this section used to carry.

- **Clause 3 — refuses to close on any red invariant: met.** `policy/close`, as before. Every run in
  the harness also reported `red invariants 0`, `blocking reasons 0`, `mayClose true`, and the
  population accounting closed exactly (`unaccounted 0`) at every size.
- **Clause 2 — a replay of that close is byte-identical: met, and measured at scale.** Bit-identical
  at 10,000, 100,000 and 300,000 contracts, with **1,506,000 figures compared** at 300,000. Not a
  spot check and not an extrapolation. What it does *not* cover is the persistence round trip: both
  sides reduce through `ShadowRun.figures`, so the arithmetic is genuinely tested and a scale lost on
  the way into a database would show in `eir-persistence-jdbc`'s live-cluster suite and cannot show
  here.
- **Clause 1 — a 10M-contract close inside 4 hours: not met as measured, and the reason is the
  finding.** No 10M close was run. The largest actually run was **1,000,000 contracts in 3 min 16 s**
  single-threaded, extrapolating to **0.55 h** for 10M — comfortably inside four hours *on time*. The
  binding constraint is heap: peak was **8.3 GiB at 1M**, so 10M in one JVM needs on the order of
  **80 GiB** and a **~19 GB** retained live set. This section said "the 4-hour figure is untested and
  remains an estimate"; it is now tested at a tenth of the population and the timing is not the
  problem. **A 10M close is not a single-JVM workload**, and that was never stated before. Partitioning
  is not an optimisation here — it is what makes the gate reachable.

**The measurement also produced a falsifiable argument for
[ADR-0007](adr/0007-spring-batch-for-runs.md), where there was an assumed one.** The per-contract cost
is flat to 100,000 contracts (59 µs) and then rises — 80 µs at 300,000, **197 µs at 1,000,000**. The
arithmetic per contract is identical at every size; what grows is the live set, because
`MonthEndRun.Completion` keeps every `ContractComputation` and `RunAggregate` keeps every
`ContractResult`, at ~1.9 KB retained per contract. A partitioned run over bounded slices should hold
that flat and recover the 59 µs figure, which would put 10M at 0.16 h. `eir-batch` exists to test that
prediction; nothing has tested it yet.

**And [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md)'s costing is corrected.** Its 0.2
core-hours per 10M close was a design-time estimate; measured, it is **0.53 core-hours** — optimistic
by 2.7×. The ADR's decision turns on a ratio of two to three orders of magnitude against 922
core-hours for a second solve, so a 2.7× error in the smaller figure changes nothing, and the ADR now
says so with the measured number.

**Why the Spring half is deferred rather than blocked.** Central is reachable and Spring Batch
resolves; this is a choice. Spring Batch is the *runner*, and wiring a runner with no database
connection and no application to host it buys network dependence and configuration ceremony rather
than verified behaviour — the same reasoning that made `eir-persistence` ship as DDL in Phase 2.
ADR-0010 makes `eir-batch` a one-pom change when there is something for it to run.

**What "delivered" does not mean here.** Three qualifications:

- ~~**Nothing in `src/main` calls any of it.**~~ **Closed by `eir-application`.**
  `application/close/RunClose` assembles a run's evidence and puts it to the gate: it constructs the
  `JournalBatch` and reads **SL-2**, the `GlReconciliation` against the GL port and reads **SL-1**,
  and the `CoreBankingReconciliation` against the CBS port and reads **RC-1**, then calls
  `PeriodCloseGate.evaluate`. `application/replay/ReplayUseCase` does the same for
  `ReplayComparison` and **DT-1**. What the wiring found, which no unit's own tests could:
  - **No run could ever have closed.** `ReconciliationScope.requiredForClose()` is true for all
    four of step 6's reconciliations and `RunClose` presented two, so the gate refused every close
    with `RECONCILIATION_NOT_PRESENTED`. The gate was right. The Stage 3 four-way and the floor
    duality are not derivable from a `ContractResult` — it carries a closing gross carrying amount
    and a journal, no ECL allowance, no suspense balance, no stage — so they are a parameter,
    supplied by whoever holds the figures and attributable to them. Synthesising them internally as
    nil against nil would have presented as tied on every run in the book's history.
  - **An invariant nobody evaluated read exactly like one that passed.** `OnboardingRun` declared
    its obligations and reported the gaps; `RunAggregate`, written independently, derived its
    invariant list from whatever the contracts happened to publish. Its existing absence check was
    all-or-nothing, and the gate's only absence check is that the whole list is empty, so a run
    whose contracts all published SL-2 and none published ST-2 closed clean. The rule now has one
    statement — `InvariantResult.idsWithoutEvidence` — called by both runs.

  SL-1's only appearance in `main` outside its own package used to be `FailureIsolation`'s run-level
  breach set — a control named in a list of controls. That is no longer the only one.

  **And the two halves are joined.** `MonthEndRun` produced a `RunAggregate` and `RunClose`
  consumed one, with nothing between them. `MonthEndCloseTest` closes that: a two-contract
  performing run publishes SL-2 and ST-2, reaches the gate on SL-2, SL-1 and RC-1, and returns
  `mayClose() == true`. That is the first end-to-end close in the repository. It also settles a
  question no unit test could — `RunAggregate.POPULATION_INVARIANTS` was derived by *reading*
  `ContractPipeline.assertions`, and a declared obligation the pipeline does not satisfy would
  block every performing book's close. Adding S3-1 to that list fails three tests, so a wrongly
  strict obligation list cannot be introduced quietly either.

  **And now a user.** `eir-api` serves a console that drives the whole cycle, verified end to end
  in a browser: a run over three contracts leaves one quarantined and refuses the close with five
  gate reasons and 41,358.04 of deviation; repairing the missing opening balance, re-running,
  posting and closing reaches a permitted close at nil deviation; the replay then proves
  reproduction. Posting is a separate step because SL-1's two sides have to be in different states
  for the tie to mean anything.

  **Four defects the wiring exposed**, which is the fourth time Phase 1's lesson has arrived: DT-1
  caught a run citing a routing-table version the registry did not carry, on a book where every
  figure was bit-identical; the demonstration ledger summed opening balances against the
  sub-ledger's closing ones and produced a 36,059.88 SL-1 break where nothing was wrong; a
  "contract with no opening state" was documentation with no mechanism, so nothing was ever
  quarantined; and `RunClose.evidence()` showed SL-2 twice — found by rendering an invariant list to
  a person for the first time, which is a test no unit had.

  ~~**What still has no caller**~~ — **all three closed.** `NightlyReplayReport` is scheduled by
  `eir-batch`'s `NightlyReplaySchedule`. `eir-application/transition` reaches `policy/transition`,
  running the ACPIR 19 day-1 valuation over a book and reporting migration coverage against both
  deadlines. And `EquivalenceTestGate` is called by `InitialRecognition` for every Tier 3 assignment
  — the item that mattered, and the two seam defects that closing it exposed are recorded below.

- ~~**The Tier 3 permission gate is unwired.**~~ **Closed.** `InitialRecognition.recognise` consults
  `EquivalenceTestGate` for every Tier 3 assignment and returns a `TierPermission`:
  `NO_TEST_ON_FILE` demotes to Tier 2 and raises `STALE_EQUIVALENCE_TEST`, and FR-412 refuses the
  shortcut outright for zero-coupon and deep-discount instruments at any tenor. `populationId` is
  `productId + ":" + segment`, and the subject is derived with
  `EquivalenceTestSubject.couponBearingAtPar` — lifetime interest is total contractual inflows less
  principal, which is arithmetic on the projection rather than the guess that would have defeated the
  gate. TG-1 is deliberately **not** in `OnboardingRun.POPULATION_INVARIANTS`, for the reason
  `RunAggregate` records for leaving S3-1 out.

  **Two defects the wiring found afterwards, both at seams and neither visible to the unit that built
  the gate.** `EirService` called `InitialRecognition.onboard`, which returns the disposition alone
  and *drops* the permission — so a Tier 3 contract through HTTP had its gate consulted, was demoted,
  was solved on Tier 2's tolerance, and came back reporting `TIER_3` with no exception, which is this
  register's Cambodia row arriving through a dropped return value rather than through a decision. And
  `OnboardingRun.blocksClose()` read breaches, the quarantine count and `assertedNothing`, none of
  which a demotion trips: `STALE_EQUIVALENCE_TEST` does not quarantine, so `describeClose()` printed
  "close may proceed" over a population whose measurement basis had changed while
  `ExceptionQueue.blocksClose()` correctly said otherwise. The run now carries what it raised and has
  a fourth reason reading it. The unit that built the gate had *named both* in javadoc as changes it
  could not make; neither was found by a test.

- **Superseded, and kept because it is the argument that got the gate built** — and because the
  figure in it is the reason the gate matters. The reading that follows describes the position before
  the item above closed it.
  `TierAssignment` assigns the tier and `InitialRecognition` uses it for one thing: the solver's
  tolerance. `TierAssignmentResult.requiresEquivalenceTest()` is true for every Tier 3 assignment
  and nothing asks it. `EquivalenceTestGate` — built in Phase 2 specifically because "until now
  TG-1 was a label" — implements the whole second gate, including `FORBIDDEN_APPROXIMATION` for
  zero-coupon and deep-discount instruments and `NO_TEST_ON_FILE` demoting to Tier 2 with an
  exception raised, and has no caller outside its own package. So a 15-year zero-coupon instrument
  is assigned Tier 3, solved on Tier 3's tolerance and recognised, with nothing recording that the
  permission was never sought. That is the Cambodia failure mode in the risk register arriving
  through an unwired gate rather than through a decision, and Case 9 measures it at **81.0%**
  year-one income overstatement.

  Not fixed in the review that found it, for a stated reason. `EquivalenceTestSubject` needs
  `populationId`, `redemptionAmount` and `contractualCouponTotal`, none of which is derivable from
  an `OnboardingRequest` or a `ProjectionResult`: the first is a judgement about how a contract maps
  to an equivalence-test population, and the third needs a coupon leg separated from principal
  repayment across a schedule shape, which is projector work. Handing a guessed coupon total to the
  gate whose purpose is catching a fabricated approximation would defeat the gate — which is why the
  subject is now derived from the projection's own contractual inflows rather than supplied.
- **Authorisation is modelled and not enforced, and the API says so on every response.**
  `eir-policy/access` is FR-906's role model as values — capabilities, roles, principals, a grant
  register, and a decision — with the maker-cannot-be-the-checker comparison borrowed from
  `FourEyes.sameIdentity` rather than restated for a fifth time. What it is not is authentication:
  07 § 7 specifies OAuth2 client credentials over TLS 1.3 with an external secret manager, none of
  which is in this repository, so the identity on a request is asserted by the caller on a header and
  every response repeats that. `GET /api/access/coverage` publishes the gap as a value, derived from
  `Routes.registeredRoutes()`: **41 routes registered, 4 enforced, 36 counted as gaps, 1 owing no
  guard.** That endpoint previously read a hand-maintained list of nine and so reported a gap of eight
  over a surface of forty — a control that read as coverage, which is why the inventory now comes from
  the registration and cannot drift from it.

- **`read-only partitions` is schema, not code.** FR-902's partition-level enforcement lives in
  V2's DDL (verified by execution in Phase 2); the Java models the *restatement artefact* that makes
  immutability workable, not the lock.
- ~~**The 4-hour figure is untested and remains an estimate**, as does ADR-0009's core-hour
  costing.~~ **Both measured** — see the exit-gate reading above and
  [`tools/load-harness/RESULTS.md`](../tools/load-harness/RESULTS.md). What replaces this
  qualification is a narrower and more useful one: every figure is in-memory and single-threaded.
  A real close reads seven ports over JDBC and the I/O is absent from all of it, so the numbers bound
  the arithmetic's cost and not a deployment's.

**The one qualification `eir-application` adds rather than removes.** Its four units were built in
parallel and **carry no independent adversarial review** — all four reviewers failed on the session
limit. The merge commit records that, and the two findings above came from a self-review of the
seams afterwards rather than from any unit's own 144 tests. Both were seam defects between units
that were individually green, which is Phase 1's lesson arriving for the fourth time.

**The review finding worth carrying into Phase 6.** Four units, built in parallel, reviewed
adversarially one reviewer each — every unit green on its own 205 tests, and the reviews still
found three controls that could not fail, two guards missing on exactly the input that reduces a
figure, and three javadocs describing a scope the code did not have. The full tally and the two
mitigations now standard — an adversarial reader per unit, and mutating the implementation to prove
each test can fail — are in [03 § 9](03-calculation-spec.md#9-invariants).

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
