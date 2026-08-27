# 07 — Non-Functional Requirements, Controls and Audit

---

## 1. Scale and performance

| Requirement | Target |
|---|---|
| Portfolio size | 10M contracts, 12 periods/year |
| Close window | Full-portfolio amortisation within 4 hours |
| Sustained throughput | ~700 contracts/second |
| Trace latency | Per-contract computation trace under 30 seconds, self-service |
| Onboarding | 5,000 contracts/second bulk; under 500ms single synchronous |
| Replay | A sampled period replayed nightly inside the maintenance window |

The throughput target is reachable only because **re-solving is event-triggered, not per-period**
([03 §4.6](03-calculation-spec.md#46-performance)). A fixed-rate contract with no events solves once
at initial recognition and thereafter costs a handful of `BigDecimal` multiplications per period.
Any change that makes solving per-period would put the target out of reach — which is why the
B5.4.4 election matters operationally as much as accounting-wise: it removes the unamortised balance
that makes an EBLR reset expensive.

### 1.1 Load shape

Two spikes worth designing for:

- **Month-end and quarter-end.** The whole book re-amortises against a close deadline.
- **Quarter-end IBPC volumes.** Inter-bank participation certificates are used for balance-sheet
  management in India, so volumes spike precisely at reporting dates — when the engine is already
  under close load. Size the window for the coincidence, not the average.

---

## 2. Precision and determinism

| Requirement | Rule |
|---|---|
| Money type | `BigDecimal` only. `double`/`float` banned at build time in `eir-domain` and `eir-calc`. |
| Working precision | `MathContext(28, HALF_UP)` |
| Presentation | Rounded once, at the persistence boundary, to ISO 4217 minor units |
| Rate storage | 12dp; the persisted rate is the rate used downstream |
| JSON | Money and rates as decimal **strings** — never JSON numbers |
| Wall clock | **Nothing in the calculation path reads one.** Time is an input. |
| Randomness | None in the calculation path |
| Iteration order | Deterministic; no dependence on hash ordering |
| Determinism proof | Same inputs twice → byte-identical. Then under a shifted system clock → still identical. |

The shifted-clock test is the one that actually proves the property. Running twice proves the code is
not obviously non-deterministic; running under a moved clock proves no path silently reads *now* —
which is the failure mode that makes a replay diverge eighteen months later, at exactly the moment an
auditor is watching.

---

## 3. Availability and recovery

| Concern | Target |
|---|---|
| API availability | 99.5% during business hours; 99.9% during the close window |
| Batch restartability | Any run resumable from the last committed chunk |
| RPO / RTO | 15 minutes / 2 hours |
| Degraded mode | Trace and reporting endpoints remain available while a run is in flight (separate instances) |

---

## 4. Financial controls

### 4.1 The control set

Every control is asserted in production, gates the close, and is reported. Numbered for reference
from the [functional spec](02-functional-spec.md) and audit workpapers.

| ID | Control | Frequency |
|---|---|---|
| C-01 | **The identity check.** EIR interest = contractual interest ± net fee amortisation ± catch-ups, at every level of aggregation | Every period, every level |
| C-02 | **Unamortised balance roll-forward.** Opening + additions − amortisation − derecognitions = closing, per product, tied to the GL | Every period |
| C-03 | **Penal charge exclusion assertion.** Positive confirmation that no `EXCLUDED_BY_DIRECTION` amount entered any EIR stream or the GCA (invariant PC-1) | Every period |
| C-04 | **Stage 3 suppression reconciliation.** Shadow unwind computed, income suppressed, suspense movement explained — the four-way reconciliation of GCA roll-forward, unwind, suspense ledger and recognised income (invariants S3-1, S3-2, ST-2), with cure and pool-level suspension under the same control (CR-1, PL-1, PL-2) | Every period |
| C-05 | **Pre-floor / post-floor duality.** Pre-floor ECL retained and reported, not overwritten, and the reported figure is the greater of the two (invariant PF-1); Stage 3 floored at account level, never pooled (invariant PF-2, ACPIR 90) | Every period |
| C-06 | **Hedge basis adjustment.** No discontinued hedge without an active amortisation schedule; designated-risk-only (invariant HB-1) | Every period |
| C-07 | **Hedging cost exclusion assertion.** No swap or hedge cost in any EIR stream (invariant HB-2) | Every period |
| C-08 | **Model inventory registration.** EIR computation registered, tiered, documented, independently validated before implementation | On change; annual review |
| C-09 | **Product taxonomy audit.** Classification decisions audited; gold-loan segregation verified | Periodic internal audit |
| C-10 | **Tier 3 equivalence re-test.** Solved-versus-approximated comparison re-performed (invariant TG-1) | Annual |
| C-11 | **Pool back-test.** Pool EIR against contract-level on a statistical sample; breach forces contract-level | Quarterly |
| C-12 | **Determinism replay.** Sampled closed period replayed and byte-compared — scale included, and against the policy versions in force then, not today's (invariant DT-1) | Nightly |
| C-13 | **Sub-ledger to GL.** Zero *unexplained* difference: an explanation carries a cause and a narrative, and the claims on an account may not exceed its difference in gross (invariant SL-1) | Period close |
| C-14 | **Contractual leg to core banking.** Zero unexplained difference, compared at the scale the borrower was billed (invariant RC-1) | Period close |
| C-15 | **FTP coherence.** EIR compared to treasury's internal funds-transfer-pricing rate for the same exposure; material divergence investigated | Monthly |

C-03, C-04 and C-05 have **no IFRS 9 analogue** — they exist because of ACPIR's India-specific
positions, and C-04 is the one that will attract the most audit attention because it is where the
engine departs from the standard whose mechanics it borrows.

C-15 is worth more than it looks. If the accounting EIR and the rate treasury uses to price the same
exposure internally differ materially, one of them is wrong. It is the cheapest available external
check on engine correctness, because it compares against a number computed by a different team for a
different purpose.

### 4.1.1 The transition controls are not in that table, and that is deliberate

Every one of the fifteen has a recurring frequency — every period, monthly, quarterly, nightly,
annual. The transition controls do not: they are programme controls with an end date, and five of
them stop mattering once the migration is complete.

| Invariant | What it asserts | Ends |
|---|---|---|
| TF-1 | No ACPIR 19 paragraph 19 presumption without rebuttal evidence | When the day-1 valuation is signed off |
| BM-1 | No day-1 below-market difference without an approved Board position | Never — new concessional lending continues |
| LC-1 | No cohort surviving 31 March 2030 queued behind one that does not | When the migration plan is complete |
| DE-1 | Every deemed-EIR cohort has an approved derivation | When the last deemed cohort is reconstructed or runs off |
| TM-1 | ACPIR 21 and ACPIR 50 tracked separately | 31 March 2030, after which any gap is simply a breach |

Numbering them C-16 to C-20 would put five controls with expiry dates in a table whose whole
premise is that each row gates every close indefinitely — and would leave five rows to be
explained, or quietly retired, in 2030. They are asserted in exactly the same way; they are listed
in [03 § 9](03-calculation-spec.md#9-invariants) with the rest, and tracked against
[08 Phase 4](08-roadmap.md) rather than here.

BM-1 is the exception that proves the distinction: concessional lending does not stop at the
transition, so it is the one transition-shaped control that becomes permanent. It belongs in the
ongoing set once the programme closes, and moving it there is a deliberate act rather than an
oversight avoided.

### 4.2 Maker–checker

Every one of these requires a maker, a checker, an effective date, and a **stored impact preview
generated before approval**:

- Policy versions (the interpretive hierarchy, thresholds, elections)
- Fee rule-set versions
- The driver→mechanism routing table
- Pool definitions and homogeneity criteria
- Behavioural assumptions (CPR curves, behavioural life)
- Materiality thresholds and tier assignment rules
- Exception acceptances that permit a close

A CPR curve change is a catch-up event across **every affected contract simultaneously**. A one-line
assumption change can therefore move a material number, which is why the impact preview is a hard
gate rather than a courtesy.

### 4.3 Period close gates

`POST /periods/{id}/close` returns `409` unless **all** hold:

1. Every upstream feed received and version-recorded — CBS schedules, fee postings, ECL staging and
   allowances, benchmark rates.
2. Every invariant green.
3. Every exception resolved, or accepted with approval.
4. Every reconciliation tied: C-01, C-02, C-13, C-14, plus C-04 and C-05 where applicable.
5. No discontinued hedge without an amortisation schedule.
6. No Tier 3 population with a stale equivalence test.

On close, the period's partitions are set read-only — immutability enforced in the database, not
only in application code.

### 4.4 Restatement

A closed period is never mutated. A correction:

1. Creates a `RESTATEMENT` artefact referencing the original run and period.
2. Records the cause, the approver, and the policy version under which the correction was determined.
3. Recognises the adjustment in the **current** open period, classified per the restatement policy.
4. Leaves the original published figures intact and still replayable.

Backdated contractual amendments follow the same path: a new `CONTRACT_VERSION` with an earlier
`validFrom`, a current `recordedAt`, and a restatement artefact in the current period. History is
never rewritten ([04 §5](04-data-model.md#5-temporal-design)).

---

## 5. Automation mandate

ACPIR requires classification, upgradation and provisioning to be **fully system-driven with no
manual intervention**, with the system periodically validated by internal or external auditors for
both controls and methodology, and directs banks to automate the ECL framework with documented
rules, maker–checker control, audit trails and exception logs.

Consequences for this engine:

| Requirement | Implementation |
|---|---|
| No manual intervention in computation | No endpoint permits overriding a computed rate or balance. Corrections change *inputs* or *policy*, then recompute. |
| Documented rules | Fee classification, routing and tier assignment are versioned data with approval records — readable without reading code. |
| Maker–checker | §4.2 |
| Audit trail | Every computation stores its inputs and policy versions; nothing is updated in place |
| Exception logs | The exception queue is a first-class, reportable object |
| Periodic validation | C-08; the engine is a **model** under ACPIR Chapter V |

There is deliberately **no manual rate override anywhere in the system**. It is the feature every
implementation is asked for and the one that destroys the audit position: a single overridden rate
makes the whole book's replayability unprovable, because nothing distinguishes a computed figure from
an adjusted one. Where a computed figure is wrong, the input or the policy is wrong, and fixing it
there fixes every affected contract and leaves a record.

---

## 6. Auditability

### 6.1 What must be reproducible

For any published figure, at any time within the retention period:

- The flow vector solved, and the rate solved from it with its residual
- The roll-forward arithmetic for the period
- Every event, its driver tag, the routed mechanism, and the routing table version
- The policy version, fee rule-set version, and ECL input version in force
- Who approved each of those versions, and when
- For a modification: the 10% test result, qualitative triggers, the conclusion, and who decided

### 6.2 What makes it possible

| Mechanism | Role |
|---|---|
| Bitemporal versioning | Distinguishes when a fact was true from when the engine learned it |
| Immutable computations | `EIR_COMPUTATION` rows are never updated; superseded, never overwritten |
| Version stamping | Every result carries the versions that produced it |
| No wall-clock reads | Time is an input, so a replay reproduces the past rather than re-deciding it |
| Read-only closed partitions | Immutability at the storage layer |
| Nightly replay | C-12 proves the property continuously rather than assuming it |

`EIR_COMPUTATION` is never archived. It is the audit anchor, and it is small relative to the balance
tables.

---

## 7. Security

| Concern | Approach |
|---|---|
| AuthN/AuthZ | OAuth2 client credentials; scopes per resource group; RBAC on approval actions |
| Segregation of duties | A maker cannot be the checker on the same version — enforced, not advisory |
| Data at rest | Transparent database encryption; column encryption on borrower identifiers |
| Data in transit | TLS 1.3 |
| PII | The engine holds account references and amounts, not borrower personal data. Borrower identity stays in the CBS. |
| Audit log | Every mutating call logged with principal, payload hash and timestamp; append-only |
| Secrets | External secret manager; nothing in configuration files |

Holding no borrower PII is a deliberate scope choice. The engine needs the account reference and the
cash flows; it does not need names, addresses or identifiers, and not holding them removes a whole
class of obligation.

---

## 8. Observability

| Signal | Detail |
|---|---|
| Run metrics | Contracts processed, throughput, per-partition progress, exception count by category |
| Invariant metrics | Pass/fail per invariant per run, as a time series — a rising INV-4 breach rate is an early warning |
| Solver metrics | Iterations distribution, bisection-fallback rate, `NoSolution` rate, `MultipleRoots` rate |
| Business metrics | EIR-vs-contractual spread by product; fee amortisation by product; catch-up totals by driver |
| Alerts | Any invariant breach; bisection-fallback rate above baseline; run projected to miss the close window |

The bisection-fallback rate is a useful canary. It should be low and stable; a jump means either new
instrument structures have arrived in the book or a projector has started emitting malformed vectors
— both worth knowing before close rather than after.
