# 06 — API Specification

REST, contract-first, OpenAPI 3. Resource-oriented. All money as decimal **strings** — never JSON
numbers, which are IEEE 754 doubles in most parsers and would silently defeat the entire precision
policy ([ADR-0002](adr/0002-precision-policy.md)).

Base path `/api/v1`. Auth: OAuth2 client credentials, scopes per resource group.

---

## 1. Conventions

| Concern | Convention |
|---|---|
| Money | Decimal string, e.g. `"995000.00"`. Never a JSON number. |
| Rates | Decimal string at 12dp, e.g. `"0.010421491800"` |
| Dates | ISO-8601 `LocalDate`, e.g. `"2027-04-01"` |
| Idempotency | `Idempotency-Key` header required on all POSTs |
| Versions | Every response carries the `policyVersionId` and `ruleSetVersionId` that produced it |
| Errors | RFC 7807 `application/problem+json` |
| Pagination | Cursor-based; `?cursor=&limit=` |

Every computed figure in every response carries the policy versions that produced it. This is not
metadata decoration — it is what makes an API response reproducible, and it is the same discipline
as the `EIR_COMPUTATION` table.

---

## 2. Contracts

### `POST /contracts` — onboard

```json
{
  "sourceSystemRef": "CBS-88214470",
  "entityId": "BANK-IN-01",
  "productId": "RETAIL-HOUSING-FLOAT",
  "currency": "INR",
  "measurementCategory": "AMORTISED_COST",
  "instrumentClass": "LOAN",
  "sppi": { "outcome": "PASS", "assessedOn": "2027-04-15", "approver": "u.rao" },
  "isPoci": false,
  "initialRecognitionDate": "2027-04-15",
  "terms": {
    "principal": "1000000.00",
    "contractualRate": "0.120000000000",
    "rateType": "FLOATING",
    "benchmarkId": "RBI-REPO",
    "spreadBps": 550,
    "resetFrequency": "ANNUAL",
    "dayCountConvention": "ACT_365F",
    "contractualMaturityDate": "2029-04-15",
    "eirExpectedLifeMonths": 24,
    "eclHorizonMonths": 24
  },
  "schedule": {
    "source": "LMS_AUTHORITATIVE",
    "lines": [
      { "sequenceNo": 1, "flowDate": "2027-05-15", "amount": "47073.47", "kind": "COMBINED_EMI" }
    ]
  },
  "feePostings": [
    { "feeCode": "PROC-FEE", "amount": "15000.00", "postedOn": "2027-04-15",
      "payer": "BORROWER", "costFunction": "PROCESSING" },
    { "feeCode": "DSA-COMM", "amount": "10000.00", "postedOn": "2027-04-15",
      "payer": "BANK", "costFunction": "SELLING" }
  ]
}
```

`201` with the assigned `contractId`, resolved `materialityTier`, fee classifications, and the
initial `EirComputation`.

**Rejections are explicit, never silent.** `422` with an RFC 7807 body naming the failing element:

| Condition | `type` |
|---|---|
| Fee code not in the rule set | `urn:eir:error:unmapped-fee-code` |
| `costFunction` absent on a cost posting | `urn:eir:error:missing-cost-function` |
| `eclHorizonMonths` differs from `eirExpectedLifeMonths` with no `lifeDivergenceBasis` | `urn:eir:error:life-divergence-unexplained` |
| Fee code resolves to `EXCLUDED_BY_DIRECTION` | `urn:eir:error:penal-charge-rejected` |
| `measurementCategory` is `FVTPL` | `urn:eir:error:fvtpl-no-eir` |
| GCA₀ ≠ net cash at inception | `urn:eir:error:ic1-breach` |
| Solver found no sign change | `urn:eir:error:no-solution` |

### `GET /contracts/{id}` · `GET /contracts/{id}/versions`

Current state; full bitemporal version history with `validFrom`/`validTo` and
`recordedAt`/`supersededAt`.

### `GET /contracts/{id}/trace?period=2027-09`

**The audit endpoint** (FR-808). Resolves any published figure to its inputs:

```json
{
  "contractId": "c-4471",
  "period": "2027-09",
  "policyVersionId": "pol-v7", "ruleSetVersionId": "fee-v12",
  "routingTableVersionId": "route-v3",
  "eirComputation": {
    "computationId": "eir-9931", "trigger": "INITIAL_RECOGNITION",
    "ratePeriodic": "0.010421491800",
    "rateEffectiveAnnual": "0.132480940000",
    "rateNominalAnnual": "0.125057900000",
    "convention": "ACTUAL_DATE", "solverMethod": "NEWTON",
    "iterations": 4, "residualAtStoredRate": "0.000000000012",
    "openingCarryingAmount": "995000.00", "status": "SOLVED"
  },
  "flowVector": [ { "flowDate": "2027-05-15", "amount": "47073.47", "kind": "COMBINED_EMI" } ],
  "rollForward": {
    "openingGca": "569545.28", "eirInterest": "5935.51",
    "contractualInterest": "5711.77", "feeAmortised": "223.74",
    "cashReceived": "47073.47", "closingGca": "528407.32",
    "unamortisedFee": "1408.29"
  },
  "stage": 1,
  "invariantResults": [ { "id": "INV-4", "status": "PASS" } ]
}
```

Target: under 30 seconds, self-service. In practice a single partition-pruned read.

---

## 3. Events

### `POST /contracts/{id}/events`

```json
{
  "eventDate": "2028-04-15",
  "eventType": "RATE_RESET",
  "driver": "TIME_VALUE_OF_MONEY",
  "newContractualRate": "0.130000000000",
  "revisedSchedule": { "source": "LMS_AUTHORITATIVE", "lines": [] }
}
```

`driver` is **required**. The engine routes on it plus the instrument's `rateType`; it never infers
treatment from the observation that the rate moved. Response echoes the routed mechanism and the
routing table version:

```json
{ "eventId": "ev-2201", "routedMechanism": "RESET",
  "routingTableVersionId": "route-v3",
  "newEir": "0.011255851500", "catchUpAmount": "0.00" }
```

A `CATCH_UP` routing instead returns the restated carrying amount and the catch-up, with
`"eirUnchanged": true` asserting invariant CU-1.

For `RESTRUCTURE`, the response carries the 10% test result and qualitative triggers as
**evidence** with `"substantialityConclusion": "PENDING_APPROVAL"` where the test falls in the
review band — the engine does not decide (FR-511).

---

## 4. Runs and periods

| Endpoint | Purpose |
|---|---|
| `POST /runs` | Start an amortisation run for `{periodId, bookId}`. `202` with `runId`. |
| `GET /runs/{id}` | Status, per-partition progress, contracts processed, exception count, invariant results |
| `POST /runs/{id}/replay` | Replay to a shadow table; returns the byte-comparison result (DT-1) |
| `GET /periods` · `GET /periods/{id}` | Period status `OPEN` / `CLOSING` / `CLOSED` |
| `POST /periods/{id}/close` | Close workflow. **`409` if any gate fails**, with the failing gates enumerated. |

`POST /periods/{id}/close` returning `409` is the intended behaviour, not an error path to route
around. Gates: all invariants green, all exceptions cleared or approved, all reconciliations tied
(FR-901).

A closed period is immutable. There is deliberately **no** `PATCH /periods/{id}` and no reopen
endpoint; a correction is `POST /restatements`.

---

## 5. Policy and rule sets

| Endpoint | Purpose |
|---|---|
| `GET /policy-versions` · `POST /policy-versions` | Draft a new version; `status: DRAFT` |
| `POST /policy-versions/{id}/impact-preview` | **Mandatory** before approval. Portfolio-level impact. |
| `POST /policy-versions/{id}/approve` | Checker approval. `409` without a stored impact preview. |
| `GET /fee-rule-sets` · `POST /fee-rule-sets` · `.../approve` | Same lifecycle |
| `GET /routing-tables` · `POST /routing-tables` · `.../approve` | The driver→mechanism map ([ADR-0006](adr/0006-configurable-event-routing.md)) |

Approval without an impact preview returns `409`. For a CPR curve change the preview is a catch-up
across every affected contract simultaneously, so the number can be material — approving blind is
exactly what the gate prevents.

---

## 6. Exceptions

| Endpoint | Purpose |
|---|---|
| `GET /exceptions?status=OPEN&category=NO_SOLUTION` | The work queue |
| `POST /exceptions/{id}/resolve` | Resolution with a mandatory note |
| `POST /exceptions/{id}/accept` | Accept with approval — permits close despite the exception |

---

## 7. Reporting

| Endpoint | Purpose |
|---|---|
| `GET /reports/movement?period=&productId=` | Movement schedule. Published columns **sum** (FR-805). |
| `GET /reports/reconciliation/gl?period=` | SL-1: sub-ledger to GL |
| `GET /reports/reconciliation/cbs?period=` | Contractual leg to core banking |
| `GET /reports/reconciliation/stage3?period=` | S3-1: the four-way — GCA, shadow unwind, suspense, recognised |
| `GET /reports/ecl-floor-duality?period=` | PF-1: pre-floor and post-floor side by side |
| `GET /reports/approximations?period=` | **Incidence of every shortcut in force** (FR-809) |
| `GET /reports/disclosure/ind-as-107?period=` | Disclosure extract |

`/reports/approximations` deserves note. It reports Tier 3 populations with equivalence-test dates,
ACPIR 51 contractual fallbacks with justifications, pool-level measurement with back-test variances,
and revolving approximations. Making the shortcuts *visible* is what keeps them defensible — an
undocumented approximation drifting quietly across a portfolio is the failure this endpoint exists
to prevent.

---

## 8. Transition

| Endpoint | Purpose |
|---|---|
| `POST /transition/fair-value-run` | ACPIR 19 day-1 fair valuation of the book |
| `GET /transition/fair-value/{contractId}` | Pre-transition carrying amount, fair value, difference to opening retained earnings, and the **paragraph 19 rebuttal evidence reference** |
| `GET /transition/legacy-cohorts` | Cohorts with expected run-off vs 31 March 2030 and migration method |
| `POST /transition/legacy-cohorts/{id}/migrate` | Full reconstruction or deemed EIR |
| `GET /transition/coverage` | Migration coverage against **both** the ACPIR 21 and ACPIR 50 deadlines, tracked separately |
