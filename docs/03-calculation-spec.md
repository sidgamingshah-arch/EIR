# 03 — Calculation Specification

Normative. Where this document and any other disagree, this one governs; where this document
and a [reference case](reference-cases/README.md) disagree, the reference case governs.

---

## 1. Numeric foundation

### 1.1 Types

| Quantity | Type | Notes |
|---|---|---|
| Money | `BigDecimal` | Always. Never `double`, `float`, or `Double`. See [ADR-0002](adr/0002-precision-policy.md). |
| Rates | `BigDecimal` | Stored as a decimal fraction: 1.04214918% is `0.0104214918`. |
| Day counts, period indices | `int` / `long` | |
| Dates | `java.time.LocalDate` | No time-of-day in the calculation path. No `Instant`, no zone. |

A lint rule fails the build on any `double` or `float` in `eir-domain` or `eir-calc`.

### 1.2 Working precision

All intermediate arithmetic uses `MathContext(28, RoundingMode.HALF_UP)` — 28 significant
digits, the IEEE 754 decimal128 precision. This is the **working** precision; it is not the
presentation precision and intermediate values are never rounded to currency scale.

### 1.3 Presentation scale

Rounding to currency scale happens exactly once, at the boundary where a figure is persisted
as a reportable amount or emitted to the GL. Scale is per currency, from ISO 4217 minor
units: INR 2, USD 2, JPY 0, KWD 3. Mode `HALF_UP`.

> **Rationale.** `HALF_UP` over banker's `HALF_EVEN` because Indian financial reporting
> convention and every downstream reconciliation target use it. Consistency with the systems
> we must tie to outranks the statistical bias argument. Recorded in
> [ADR-0002](adr/0002-precision-policy.md).

### 1.4 Rate storage precision

Rates are persisted at **12 decimal places**, `HALF_UP`. At 12dp the truncation error on a
rate is under 1e-12; applied to a 10^9 balance over 360 monthly periods the accumulated
error stays below 0.01 of currency. Sufficient, and it makes the stored rate exactly
reproducible rather than a lossy render of a longer internal value.

The rate that is **persisted** is the rate that must be **used** in every downstream period.
Solving to 28 digits and then rolling forward with a 12dp value is fine; solving to 28,
persisting 12, and rolling forward with the 28-digit value is not — the published amortisation
would not be reproducible from the published rate. Round once, then use the rounded value.

---

## 2. Cash-flow projection

The EIR solver consumes a projected flow vector; it never reads contract terms directly.
Projection is a separate, independently testable stage.

### 2.1 The flow vector

An ordered sequence of `(date, amount, kind)`, signed from the **holder's** perspective:
outflows negative, inflows positive. For a liability the signs invert, and the same solver
applies unchanged.

Flow kinds: `DISBURSEMENT`, `PRINCIPAL`, `INTEREST`, `COMBINED_EMI`, `INTEGRAL_FEE_RECEIVED`,
`INTEGRAL_COST_PAID`, `BALLOON`, `EXPECTED_PREPAYMENT`, `RESIDUAL_VALUE`.

The kind never affects discounting — only the amount and the date do. It is carried for
traceability, for the reconciliation legs, and to let the projector be validated.

### 2.2 Two conventions for time

**Periodic-index.** `τ_t = t`, the ordinal period number, and `r` is the per-period rate.
Discount factor `(1+r)^-t`. Valid only when periods are uniform in length and flows fall on
period boundaries.

**Actual-date (XIRR).** `τ_t = D(d_0, d_t) / B`, where `D` is the day count under the
instrument's convention and `B` the basis. Discount factor `(1+R)^-τ_t`, with `R` an annual
rate.

Selection rule, evaluated per contract:

```
if all periods uniform AND all flows on period boundaries AND no broken period:
      periodic-index          # cheaper, and exactly equivalent here
else: actual-date             # required for correctness
```

Actual-date is the **default and the fallback**. Periodic-index is an optimisation whose
precondition is checked, not assumed — a moratorium, a mid-period disbursement, a
28/31-day month pair with actual dating, or a broken first period all void it. The check is
cheap; guessing is not.

### 2.3 Day count conventions

Supported: `ACT/ACT` (ISDA), `ACT/365F`, `ACT/360`, `30/360` (bond basis), `30E/360`,
`ACT/365L`. Per instrument, from the contract terms, defaulting per product.

Implemented as a strategy interface with a table-driven test per convention against published
vectors — these are easy to get subtly wrong at month ends and leap years, and getting them
wrong shifts every discount factor.

### 2.4 Expected versus contractual flows

The projector emits both vectors:

- **Contractual** — the face schedule. Reconciles to the LMS. Used for the contractual
  interest leg and for the 10% test.
- **Expected** — contractual adjusted for behavioural assumptions (prepayment/CPR), and for
  POCI, for expected credit losses. Used for EIR determination.

Where policy sets expected life to contractual, the two coincide and the engine records that
this was a policy choice, not an absence of assumption. The distinction matters in an audit:
"we used contractual life" and "we never considered life" look identical in the numbers.

### 2.5 Multiple disbursements

Tranched facilities produce negative flows after `t=0`, which can give the NPV function more
than one sign change and hence multiple real roots. Handled in §4.4.

---

## 3. Fee treatment

### 3.1 Initial carrying amount

```
GCA₀ = Σ disbursements
     − Σ integral fees received
     + Σ integral costs paid
     ± premium / discount on acquisition
```

**Invariant IC-1:** `GCA₀` equals the net cash flow at inception. Asserted on every contract
at initial recognition; a breach is a hard failure, not a warning, because it means a fee has
been misclassified or a non-cash item has entered the vector.

### 3.2 Classification

A versioned rule set maps `(fee_code, product, entity, effective_date)` to
`INTEGRAL | AS_INCURRED | OVER_COMMITMENT_PERIOD | SEPARATE_SERVICE`. Resolution is
most-specific-wins with a mandatory default per fee code; an unmapped fee code fails the
contract into the exception queue rather than defaulting silently to either treatment.

Rule sets are immutable once approved. A change creates a new version with an effective date,
a maker, a checker, and a stored impact preview. See
[07 § controls](07-nfr-controls-audit.md#4-financial-controls).

### 3.3 Contingent fees

Fees contingent on a future event — prepayment penalty, late fee, bounce charge — are
**excluded** from the projection at inception regardless of being contractually specified,
and recognised in the period the event occurs. Enforced by the projector, which rejects any
flow carrying a `contingent` marker.

---

## 4. The solver

### 4.1 Statement

Find `r` such that

$$f(r) = \sum_{t} \frac{CF_t}{(1+r)^{\tau_t}} - \text{GCA}_0 = 0$$

### 4.2 Algorithm

Newton–Raphson with an analytic derivative, inside a guaranteed bracket, falling back to
bisection.

```
1. BRACKET
     Scan r over a fixed ladder: -0.9999, -0.5, -0.1, 0, 0.001, 0.01, 0.05,
     0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0  (per-period or annual per convention)
     Find adjacent pairs with a sign change in f.
     0 sign changes  -> NoSolution        (§4.3)
     >1 sign changes -> MultipleRoots     (§4.4)

2. SEED
     r₀ = the contractual rate where available, else the bracket midpoint.
     A contractual seed converges in 3-4 iterations on typical instruments.

3. ITERATE  (Newton-Raphson)
     f'(r) = Σ  -τ_t · CF_t · (1+r)^(-τ_t - 1)
     r_{n+1} = r_n - f(r_n)/f'(r_n)
     Reject the step and bisect instead if it leaves the bracket,
     if |f'| < 1e-20, or if |f| increased.

4. CONVERGE
     Stop when |f(r)| <= tol_abs  OR  |r_{n+1} - r_n| <= 1e-14
     tol_abs = max(1e-10, GCA₀ × 1e-16)     # scale-relative
     Hard cap 100 iterations, then fall back to pure bisection
     over the bracket for 200 iterations.

5. ROUND
     Round to 12dp HALF_UP. Re-evaluate |f| at the rounded rate and
     record the residual on the computation record.
```

Bisection over the ladder bracket is guaranteed to converge for any continuous `f` with a
sign change, so the fallback path cannot fail to terminate. Newton–Raphson is there for
speed, not for correctness.

### 4.3 No solution

No sign change over the ladder means no economically meaningful rate exists — typically
because total inflows do not exceed the initial outflow (a loan that never repays its
principal), or the flow vector is malformed. The contract fails into the exception queue with
the flow vector attached. It is never defaulted to zero, and never to the contractual rate.

### 4.4 Multiple roots

More than one sign change means multiple mathematically valid IRRs. Policy, in order:

1. If exactly one root lies in the **plausible band** — configured per product, default
   `(-0.5, 2.0)` annualised — take it and record that disambiguation occurred.
2. If several lie in the band, take the one nearest the contractual rate, mark the
   computation `REQUIRES_REVIEW`, and route it for approval. The figure is computed and
   usable; it is flagged, not blocked.
3. If none lie in the band, exception queue.

Every disambiguation is recorded with all candidate roots, so the choice is auditable. This
is uncommon — it needs a genuinely non-monotonic flow vector, in practice a tranched facility
with large interim drawdowns — but it is not rare enough to leave undefined.

### 4.5 Annualisation

Two annualisations exist and they are not interchangeable:

| Form | Formula | Use |
|---|---|---|
| **Effective annual** | `(1+r_p)^p − 1` | Reporting, disclosure, comparison. The economically meaningful figure. |
| **Nominal annual** | `r_p × p` | Comparison to a quoted contractual rate only. |

Case 1: monthly 1.04214918% is **13.248094%** effective and **12.505790%** nominal. Both are
persisted; both are labelled; neither is called simply "the annual EIR". Interest is always
computed from the **periodic** rate, never by dividing an annual rate, so no annualisation
round-trip enters the amortisation.

### 4.6 Performance

Target: 10M contracts within the close window. Per solve, ~4 Newton iterations at ~360 flow
terms is ~1,440 `BigDecimal` power operations — dominated by `pow`. Mitigations:

- Cache discount factors per `(τ, r)` within a solve; consecutive iterations share `τ` values.
- Compute `(1+r)^-τ` incrementally for uniform periodic-index vectors: each factor is the
  previous times `(1+r)^-1`, replacing `n` powers with `n` multiplications. Roughly an order
  of magnitude on the common path.
- Re-solve only on a triggering event, never on every period. A fixed-rate contract with no
  events solves **once**, at initial recognition. The steady-state month-end run is
  overwhelmingly roll-forward arithmetic, not root-finding.

That last point is the one that makes the target reachable: the expensive operation is rare.

---

## 5. Amortisation

### 5.1 Gross basis — Stage 1 and 2

```
interest_p     = round(GCA_open,p × r_periodic)
GCA_close,p    = GCA_open,p + interest_p − cash_received_p
```

### 5.2 Net basis — Stage 3

```
AC_open,p      = GCA_open,p − allowance_open,p
interest_p     = round(AC_open,p × r_periodic)          # rate unchanged
GCA_close,p    = GCA_open,p + interest_p − cash_received_p
```

The gross carrying amount continues to be tracked and rolled forward on the gross basis; only
the interest *measurement* base changes. Two balances, not one.

### 5.3 Broken periods

Where a period is not a whole compounding period — a first period from disbursement to the
first due date, or a period truncated by a lifecycle event — interest accrues for the actual
fraction:

```
interest = GCA_open × ((1 + R_annual)^(D(d_start, d_end)/B) − 1)
```

Compound accretion for the fraction, not a simple-interest pro-rata. Pro-rating understates
interest on long broken periods and is a common source of small, persistent breaks.

### 5.4 Stage transitions mid-period

Interest is measured on the basis in force at the **start** of the period; a transition takes
effect from the next period. Configurable to split the period at the transition date, off by
default: the added precision is immaterial against monthly periods and the operational cost
of intra-period splits is not.

### 5.5 Event ordering within a period

Deterministic order, applied strictly:

```
1. Accrue interest to the event date (broken period, §5.3)
2. Apply cash received
3. Apply the lifecycle event (reset / re-estimation / modification / stage change)
4. Re-solve or restate as the event type dictates (§6)
5. Accrue from the event date to period end
```

Two events on the same date order by event type precedence, then by receipt sequence. The
precedence table lives in `eir-domain` as a single enum ordinal and is covered by an explicit
test — ordering ambiguity here produces small, irreproducible differences that are miserable
to diagnose.

---

## 6. Event treatments

The normative decision table. `EIR` = the rate; `GCA` = gross carrying amount.

| Event | Rate | GCA | P&L now | Reference |
|---|---|---|---|---|
| Floating benchmark reset | **Re-solve** over remaining flows from current GCA | Unchanged | None | B5.4.5, [Case 4](reference-cases/case-04-floating-rate-reset.md) |
| Revised cash-flow estimate (CPR, tenor re-profile) | Unchanged | **Restate** to PV of revised flows at **original** EIR | Catch-up | B5.4.6, [Case 3](reference-cases/case-03-b546-reestimation.md) |
| Non-substantial modification | Unchanged | **Restate** at original EIR | Modification gain/loss | 5.4.3 |
| Substantial modification | **New** EIR on the new asset | Derecognise; recognise new at fair value | Derecognition gain/loss | 3.2.3, B3.3.6 |
| Part-prepayment, schedule shortened | Unchanged | Reduce by cash; re-project remaining | None beyond normal accrual | — |
| Part-prepayment, EMI reduced | Unchanged | **Restate** at original EIR | Catch-up | B5.4.6 |
| Full prepayment / closure | n/a | To zero | **Accelerate** unamortised fee | [Case 2](reference-cases/case-02-full-prepayment.md) |
| Stage 1/2 → Stage 3 | Unchanged | Unchanged | None (base changes prospectively) | 5.4.1, [Case 5](reference-cases/case-05-stage-3-net-basis.md) |
| Stage 3 → Stage 1/2 | Unchanged | Unchanged | None — **prospective, no catch-up** | 5.4.1 |
| Allowance remeasured | Unchanged | Unchanged (GCA is gross) | None from this engine | — |
| Write-off | n/a | To zero | Per impairment policy | — |
| Rate change on a **fixed**-rate instrument by renegotiation | Per modification test | Per modification test | Per modification test | 5.4.3 |

The last row is the trap: a fixed-rate loan whose rate is renegotiated is **not** a B5.4.5
event. B5.4.5 covers floating-rate instruments repricing off a market benchmark by their own
terms. A negotiated rate change to a fixed-rate loan is a modification and runs the
substantiality test. The engine keys the treatment off the instrument's **rate type** and the
**cause code** on the event, never off the observation that the rate moved.

### 6.1 Part-prepayment: the two variants

Both reduce the balance; they diverge on what happens to the remaining schedule.

- **Tenor reduced, EMI held.** Fewer future flows at the same amount. The original EIR still
  discounts the remaining flows to the post-payment carrying amount, near enough that no
  restatement is warranted; re-project and continue.
- **EMI reduced, tenor held.** The flow *pattern* has changed, which is a revision of
  estimated receipts. Restate at the original EIR with a catch-up under B5.4.6.

Which one applies is a contractual/borrower election carried on the event, not something to
infer from the resulting schedule.

### 6.2 The catch-up computation

```
GCA_restated = Σ  revised_flow_t / (1 + r_original)^τ_t
catch_up     = GCA_restated − GCA_before
```

Positive is income, negative is an expense. Discounting is at the **original** EIR —
[Case 3](reference-cases/case-03-b546-reestimation.md) produces −627.42, a charge. A
frequently-seen defect is discounting at a re-solved rate, which drives the catch-up to
approximately zero and quietly converts a B5.4.6 event into a B5.4.5 one.

**Invariant CU-1:** for a B5.4.6 event the persisted EIR before and after must be
bit-identical. Asserted, because it is the cheapest possible detector of this defect.

---

## 7. Rounding and residue policy

### 7.1 Where rounding happens

Once per persisted figure, at currency scale, `HALF_UP`. Rolling balances are carried at
working precision **and** at presentation scale; the presented balance is derived from the
presented components so that the published table adds up. A published movement schedule whose
columns do not sum is a defect even when every figure is individually correct — controllers
reconcile from the published numbers.

### 7.2 Terminal residue

**Invariant TR-1:** on a contract run to contractual maturity with no events, the closing
gross carrying amount on the **EIR leg** is exactly zero.

It is exactly zero because the rate was solved against that same flow vector. Any non-zero
terminal balance on the EIR leg indicates the solve and the roll-forward disagreed about the
flows — the defect at [primer §11 #11](01-domain-primer.md#11-common-failure-modes).

### 7.3 Contractual-leg residue

The contractual leg does *not* amortise to zero, and this is not a defect. In
[Case 1](reference-cases/case-01-emi-loan-with-fees.md) the true annuity payment is
47,073.472223 and the billed EMI is 47,073.47; the 0.002223 monthly shortfall compounds to a
**0.059969** residue at month 24.

Every real lender resolves this in the loan management system, and the engine must follow
whatever the LMS does rather than invent its own answer:

| Policy | Behaviour |
|---|---|
| `FINAL_PERIOD_PLUG` *(default)* | The last instalment absorbs the residue. Final EMI becomes 47,073.53. |
| `FIRST_PERIOD_PLUG` | The first instalment absorbs it. |
| `SPREAD_LAST_N` | Spread over the final `n` instalments. |
| `LMS_AUTHORITATIVE` | Consume the LMS's actual schedule; do not derive one. |

`LMS_AUTHORITATIVE` is strongly preferred in production and is the reason the projector
accepts an externally-supplied schedule as a first-class input. Deriving a schedule the LMS
did not bill guarantees a reconciliation break every month. The plug policies exist for
instruments where no external schedule is available.

Under the default, the residue lands in the final period's contractual interest; it never
touches the EIR leg, and the total-interest reconciliation (§8, INV-3) is stated against the
actual billed flows so it holds regardless of policy.

---

## 8. Invariants

Asserted in production, not only in tests. A breach raises a control exception and blocks the
period close.

| ID | Invariant | Scope |
|---|---|---|
| IC-1 | `GCA₀` = net cash flow at inception | Initial recognition |
| TR-1 | Terminal EIR-leg GCA = 0 on a full-term, event-free contract | Contract close |
| INV-1 | Σ EIR interest over life = Σ contractual interest + net integral fee | Contract close |
| INV-2 | `EIR > contractual` iff net integral fee is income; `<` iff cost; `=` iff nil | Post-solve |
| INV-3 | Σ cash received = principal + Σ contractual interest (on billed flows) | Contract close |
| INV-4 | Unamortised fee balance = contractual GCA − EIR GCA | Every period |
| CU-1 | EIR unchanged across a B5.4.6 restatement | Event |
| CU-2 | Catch-up = PV(revised, original EIR) − GCA_before | Event |
| SL-1 | Σ sub-ledger contract balances = GL control account balance | Period close |
| SL-2 | Σ journal debits = Σ journal credits, per run and per contract | Every posting |
| ST-1 | Stage 3 interest ≤ gross-basis interest, equal only at a nil allowance | Every period |
| DT-1 | Re-run of a closed period reproduces published figures bit-identically | Nightly replay |

INV-4 is the workhorse. The unamortised fee balance is not stored as an independent
accumulator — it is *defined* as the difference between the two legs, so it cannot drift away
from them. Case 1 at month 12: 529,815.61 − 528,407.32 = **1,408.29**, which is exactly the
amount [Case 2](reference-cases/case-02-full-prepayment.md) accelerates to P&L on prepayment.
The same number arrived at two ways.

---

## 9. Pool-level EIR

A practical expedient for homogeneous retail portfolios: solve one EIR for a pool and apply
it to every member. Permitted where members are genuinely homogeneous in rate, tenor, fee
structure and expected life, and where the difference against contract-level measurement is
demonstrably immaterial.

Requirements when enabled:

- Pool definition is a versioned, approved artefact with explicit homogeneity criteria.
- Pools are closed cohorts — typically product × origination month × rate band × tenor band.
  Members are never added after the pool's EIR is struck; a later origination joins a later
  pool.
- **Mandatory quarterly back-test** against contract-level computation on a statistical
  sample, with a materiality threshold that, when breached, forces the pool to contract-level
  measurement.
- A contract leaving the pool early (prepayment, restructuring, stage-3 entry) is measured at
  contract level from that point, at its own EIR, solved on exit.

The engine supports the expedient because entities use it. It defaults to **contract-level**,
because the expedient's justification is operational cost, and at the scale this system is
built for that cost is not high enough to trade accuracy for. See
[ADR-0005](adr/0005-contract-level-default.md).

---

## 10. Worked examples

Full derivations, every intermediate figure, in [reference-cases](reference-cases/README.md):

| Case | Demonstrates | Key figure |
|---|---|---|
| [1](reference-cases/case-01-emi-loan-with-fees.md) | Fixed-rate EMI with integral fees | EIR 13.248094% p.a. vs contractual 12.682503% |
| [2](reference-cases/case-02-full-prepayment.md) | Full prepayment, accelerated fee | 1,408.29 to P&L at month 12 |
| [3](reference-cases/case-03-b546-reestimation.md) | B5.4.6 re-estimation catch-up | −627.42 charge, EIR unchanged |
| [4](reference-cases/case-04-floating-rate-reset.md) | B5.4.5 prospective reset | EIR → 14.375386% p.a., nil catch-up |
| [5](reference-cases/case-05-stage-3-net-basis.md) | Stage 3 net basis | 3,304.08 vs 5,506.79 gross |
| [6](reference-cases/case-06-poci-credit-adjusted-eir.md) | POCI credit-adjusted EIR | 29.141905% vs 64.703664% naive |
| [7](reference-cases/case-07-solver-stress.md) | Solver edge instruments | Zero-coupon recovers 1.000000000000% |

Cases 3 and 4 are the same instrument at the same date under different causes, and they are
the pair to reach for when explaining this system to anyone.
