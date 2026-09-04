# 03 — Calculation Specification

Normative. Where this document and any other disagree, this one governs; where this document
and a [reference case](reference-cases/README.md) disagree, the reference case governs.

**Regulatory anchor.** RBI (Commercial Banks — Asset Classification, Provisioning and Income
Recognition) Directions, 2026 ("ACPIR"), effective 1 April 2027. IFRS 9 and Ind AS 109 are
**interpretive sources only**, applied where ACPIR is silent, per the interpretive hierarchy at
[§2](#2-interpretive-hierarchy). Paragraph references of the form `ACPIR 51` are to the
Directions; `B5.4.6` to IFRS 9 application guidance. Full domain background:
[ACPIR 2026 application reference](reference/acpir-2026-eir-application-reference.md).

---

## 1. Numeric foundation

### 1.1 Types

| Quantity | Type | Notes |
|---|---|---|
| Money | `BigDecimal` | Always. Never `double`, `float`, or `Double`. See [ADR-0002](adr/0002-precision-policy.md). |
| Rates | `BigDecimal` | Decimal fraction: 1.04214918% is `0.0104214918`. |
| Day counts, period indices | `int` / `long` | |
| Dates | `java.time.LocalDate` | No time-of-day in the calculation path. No `Instant`, no zone. |

A build-failing lint rule rejects any `double` or `float` in `eir-domain` or `eir-calc`.

### 1.2 Working precision

`MathContext(28, RoundingMode.HALF_UP)` — 28 significant digits, IEEE 754 decimal128. This is the
**working** precision; intermediate values are never rounded to currency scale.

### 1.3 Presentation scale

Rounding to currency scale happens exactly once, where a figure is persisted as a reportable
amount or emitted to the GL. Scale from ISO 4217 minor units: INR 2, USD 2, JPY 0, KWD 3. Mode
`HALF_UP`, consistent with Indian financial reporting convention and every downstream
reconciliation target. Recorded in [ADR-0002](adr/0002-precision-policy.md).

### 1.4 Rate storage precision

Rates persist at **12 decimal places**, `HALF_UP`. The rate that is *persisted* is the rate that
must be *used* in every downstream period: solve at 28 digits, round to 12, then roll forward with
the rounded value. Solving to 28 and rolling forward with the unrounded value makes the published
amortisation irreproducible from the published rate.

For a 20-year exposure the stored-rate precision matters twice over, because ACPIR 50 makes the
EIR the ECL discount rate — a rate error propagates into lifetime ECL with compounding effect.
Tier 1 exposures ([§10](#10-the-materiality-tier-gate)) carry a tightened solver tolerance for
this reason.

---

## 2. Interpretive hierarchy

ACPIR carries nine operative EIR paragraphs and is silent on much of the mechanics. The engine
resolves every silence through one declared, auditable hierarchy, configured once per entity and
recorded on every computation:

```
1. ACPIR 2026 operative text                    (binding)
2. Other applicable RBI Directions              (binding in their own domain —
     Investment Portfolio Directions per ACPIR 22; penal charges framework 2023;
     Resolution of Stressed Assets Directions 2025)
3. IFRS 9 / Ind AS 109 + application guidance   (interpretive, where 1 and 2 are silent)
4. Entity accounting policy, Board-approved      (selects among defensible readings)
```

Level 3 rests on RBI's own stated objective of closer alignment with internationally accepted
financial reporting principles. Adopting it converts nine open questions into one documented
choice. Every `EirComputation` record stores the policy version that resolved it, so a replay
years later reproduces the reading in force at the time — not today's.

**Where ACPIR is silent and this engine therefore follows IFRS 9 by election:** subsequent
changes in cash flows (B5.4.5 / B5.4.6 — [§6](#6-event-treatments)); modification versus
derecognition ([§6.4](#64-modification-versus-derecognition)); the negative fee list
(B5.4.3 — [§3.2](#32-fee-and-cost-classification)); the probable-drawdown condition on commitment fees; the
next-repricing-date shortcut (B5.4.4 — [§5.6](#56-the-b544-next-repricing-date-shortcut));
liability-side symmetry ([§11](#11-liabilities)); hedge basis adjustment amortisation
([§12](#12-hedge-accounting-interaction)).

**Where ACPIR diverges from IFRS 9 and ACPIR governs:** Stage 3 income non-recognition
([§7](#7-impairment-interaction-the-india-divergence)) and the hard exclusion of penal charges
([§3.4](#34-penal-charges-hard-exclusion)).

---

## 3. Cash-flow projection

The solver consumes a projected flow vector; it never reads contract terms directly. Projection is
a separate, independently testable stage.

### 3.1 The flow vector

An ordered sequence of `(date, amount, kind, driver)`, signed from the **holder's** perspective:
outflows negative, inflows positive. Liability signs invert and the same solver applies unchanged.

Flow kinds: `DISBURSEMENT`, `PRINCIPAL`, `INTEREST`, `COMBINED_EMI`, `INTEGRAL_FEE_RECEIVED`,
`INTEGRAL_COST_PAID`, `BALLOON`, `EXPECTED_PREPAYMENT`, `RESIDUAL_VALUE`, `NOTIONAL_REDEMPTION`
(the synthetic terminal flow used by the B5.4.4 shortcut, [§5.6](#56-the-b544-next-repricing-date-shortcut)).

Kind and driver never affect discounting — only amount and date do. They are carried for
traceability, for the reconciliation legs, for event routing ([§6](#6-event-treatments)), and to
let the projector be validated.

### 3.2 Fee and cost classification

ACPIR 52 states only the positive limb — origination fees and commitment fees to originate a
loan — and ACPIR 53 the transaction-cost inclusions and exclusions. There is no negative list, so
without policy a bank has no principled basis to keep any fee out. The engine adopts IFRS 9
B5.4.2 and B5.4.3 in full as configured policy.

A versioned rule set maps `(fee_code, product, entity, effective_date)` to:

| Classification | Treatment |
|---|---|
| `INTEGRAL` | Into the initial carrying amount; amortised via the EIR |
| `AS_INCURRED` | P&L when incurred — servicing fees, contingent charges |
| `OVER_COMMITMENT_PERIOD` | Commitment fee where drawdown is **not** probable |
| `SEPARATE_SERVICE` | Distinct performance obligation — insurance commission, advisory |
| `EXCLUDED_BY_DIRECTION` | Cannot enter any EIR stream — penal charges ([§3.4](#34-penal-charges-hard-exclusion)) |

Resolution is most-specific-wins with a mandatory default per fee code. An **unmapped fee code
fails the contract into the exception queue** rather than defaulting silently to either treatment.

Two India-specific classification rules the rule set must express:

- **ACPIR 53, selling versus processing.** Incentive paid to an employee *acting as a selling
  agent* is a capitalisable transaction cost. Salary of the credit-appraisal team is internal
  administrative cost, explicitly excluded. The dividing line is *selling*, not *processing*, and
  source HR and cost-centre data is structured along neither — so the rule set takes a
  `cost_function` attribute that the ingestion layer must populate, and rejects the posting if it
  is absent.
- **Commitment fees.** ACPIR 52 omits IFRS 9 B5.4.2(b)'s *probable drawdown* condition. Read
  literally every commitment fee defers, including on facilities that were never going to draw.
  The engine requires a `drawdown_probability` assessment with a numeric threshold defined per
  product in policy, evidenced by historical drawdown rates; below threshold the fee routes to
  `OVER_COMMITMENT_PERIOD` and is recognised on expiry if undrawn.

Rule sets are immutable once approved. A change creates a new version with an effective date, a
maker, a checker and a stored impact preview — see [07 § controls](07-nfr-controls-audit.md).

### 3.3 Contingent fees

Fees contingent on a future event — prepayment penalty, late fee, bounce charge — are **excluded**
from the projection at inception regardless of being contractually specified, and recognised in
the period the event occurs. The projector rejects any flow carrying a `contingent` marker.

Note the interaction with Indian retail mortgages: RBI's restrictions on foreclosure charges for
floating-rate individual loans mean there is little prepayment-penalty cash flow to model in the
first place — a variable that dominates the equivalent calculation in other jurisdictions is
largely absent here.

### 3.4 Penal charges: hard exclusion

Under RBI's 2023 penal charges framework these are *charges*, not penal *interest*: they are not
capitalised and they bear no further interest. They therefore cannot enter the amortisation
schedule or the gross carrying amount at all.

This is implemented as a **filter at the ingestion boundary, not a judgement in the rule set**:
any posting whose fee code resolves to `EXCLUDED_BY_DIRECTION` is rejected from every EIR cash
flow stream and from the gross carrying amount, and the rejection is logged as a positive
assertion for the period ([§9](#9-invariants), control PC-1). Legacy core banking systems
routinely book penal amounts into the interest ledger, which is exactly why the control is an
assertion rather than an assumption.

### 3.5 Two definitions of life

Two horizons exist and they are not the same field:

| Parameter | Source | Definition |
|---|---|---|
| **EIR expected life** | ACPIR 51 | Expected life, considering all contractual terms including prepayment, extension and call options. May be materially shorter than contractual. |
| **ECL horizon** | ACPIR 46(1) | The *maximum contractual* period, including extension options. |

The data model carries both explicitly and **never derives one from the other**
([04 § contract](04-data-model.md)). Where they differ, the contract records the reconciliation
basis, because the difference is the first thing an auditor asks about.

Where reliable estimation genuinely fails, ACPIR 51 permits fallback to contractual cash flows
over the full contractual term. The engine supports this as an explicit, per-contract election
that records its justification — and reports on its incidence, because the fallback is intended
for *rare* cases and a portfolio quietly resting on it is a finding waiting to happen.

### 3.6 Expected versus contractual flows

The projector emits both vectors:

- **Contractual** — the face schedule. Reconciles to the core banking system. Drives the
  contractual interest leg and the 10% test.
- **Expected** — contractual adjusted for behavioural assumptions (prepayment/CPR), and for POCI,
  for expected credit losses. Drives EIR determination.

ACPIR 51 excludes expected credit losses from the EIR cash flows for all non-POCI instruments.
POCI is the sole exception ([§8](#8-poci-the-credit-adjusted-eir)).

Where policy sets expected life to contractual the two coincide, and the engine records that this
was a **policy choice, not an absence of assumption**. "We used contractual life" and "we never
considered life" produce identical numbers and very different audit outcomes.

#### Behavioural-life leverage

The single highest-leverage assumption in the model. On a 5,000,000 mortgage at 9% p.a. with
50,000 of net integral fee:

| Assumed life | EIR (effective p.a.) | Net fee recognised in year 1 |
|---|---:|---:|
| 240 months (20 years) | 9.533867% | 2,525.04 |
| 96 months (8 years) | 9.689903% | 9,410.03 |

Compressing assumed life from 20 years to 8 multiplies year-one fee recognition by **3.73×** — not
the 2.5× a naive inverse-of-life estimate suggests, because declining-balance EIR amortisation
front-loads on top of the shorter horizon. No other single input has this leverage, which is why a
CPR assumption change is governed as a policy change with a mandatory impact preview
([§6.3](#63-the-catch-up-computation)) rather than as a parameter update.

### 3.7 Revolving facilities

Cash credit, overdraft, credit cards, KCC and working-capital limits have no contractual repayment
schedule, so there is nothing to solve a conventional EIR over. ACPIR 54 explicitly contemplates
that the EIR cannot be determined directly for revolving facilities and permits an approximation.
Selected by policy per product:

- **Fee amortised over the renewal or sanction period**, where the drawn balance is volatile. The
  defensible split: the component compensating credit assessment and documentation at each renewal
  defers over the renewal period; the component compensating *availability* of the undrawn limit is
  service income.
- **EIR over an expected drawdown and repayment profile**, where utilisation is stable and
  modellable.

For credit cards, ACPIR 46(2)(iii) mandates behavioural analysis — historical default patterns,
drawdown behaviour, and the effectiveness of limit reduction, suspension or cancellation. Interest
accretes only on revolving balances, never on transactions settled inside the interest-free period.
These are a distinct instrument family with their own projection strategy, not term loans forced
through the annuity path ([05 § projection strategies](05-architecture.md)).

### 3.8 Multiple disbursements

Tranched facilities produce negative flows after `t=0`, which can give the NPV function more than
one sign change and hence multiple real roots. Handled at [§4.4](#44-multiple-roots).

Project finance is the live case: draws run over 24–48 months against a projected schedule that
never matches actual, so the EIR struck at financial closure is stale by first drawdown. Policy is
to strike on the projected schedule at financial closure, then re-estimate when **cumulative**
deviation breaches a configured tolerance — not on every drawdown, which would churn the book for
no informational gain.

### 3.9 Day count conventions

Supported: `ACT/ACT` (ISDA), `ACT/365F`, `ACT/360`, `30/360` (bond basis), `30E/360`, `ACT/365L`.
Per instrument from contract terms, defaulting per product.

Indian convention discipline, fixed once in policy and applied uniformly: **actual/365 for money
market** instruments (T-bills, CP, CD, call and notice money, TREPS), **30/360 commonly for term
loans**. A mixed convention that is not documented produces a permanent unexplained reconciliation
difference between treasury and finance.

Sensitivity is **inverted against tenor**: on a 20-year mortgage a convention error is noise; on a
7-day money-market instrument a three-day error is a materially wrong rate. Short tenor is where
convention discipline actually bites. Implemented as a strategy interface with table-driven tests
per convention against published vectors — month-ends and leap years are easy to get subtly wrong,
and every discount factor moves when they are.

### 3.10 Two conventions for time

**Periodic-index.** `τ_t = t`, the ordinal period; `r` is the per-period rate; discount factor
`(1+r)^-t`. Valid only when periods are uniform and flows fall on period boundaries.

**Actual-date (XIRR).** `τ_t = D(d_0, d_t) / B` under the instrument's day count; discount factor
`(1+R)^-τ_t` with `R` annual.

```
if all periods uniform AND all flows on period boundaries AND no broken period:
      periodic-index          # cheaper, and exactly equivalent here
else: actual-date             # required for correctness
```

Actual-date is the **default and the fallback**. Periodic-index is an optimisation whose
precondition is *checked*, not assumed — a moratorium, a mid-period disbursement, an actual-dated
28/31-day month pair, or a broken first period all void it. The check is cheap; guessing is not.

---

## 4. The solver

### 4.1 Statement

Find `r` such that

$$f(r) = \sum_{t} \frac{CF_t}{(1+r)^{\tau_t}} - \text{GCA}_0 = 0$$

### 4.2 Algorithm

Newton–Raphson with an analytic derivative, inside a guaranteed bracket, falling back to bisection.

```
1. BRACKET
     Scan r over a fixed ladder: -0.9999, -0.5, -0.1, 0, 0.001, 0.01, 0.05,
     0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0   (per-period or annual per convention)
     Find adjacent pairs with a sign change in f.
     A node where |f| <= tol_abs is itself the root: take it, refine nothing.
     0 sign changes  -> ESCALATE the ladder to
                        -0.999999999999, -0.9999999, -0.99999, 100, 1e4, 1e6, 1e12
                        and rescan. Still 0 -> NoSolution  (4.3)
                        A root found only here -> REQUIRES_REVIEW, never SOLVED.
     >1 sign changes -> MultipleRoots     (4.4)

2. SEED
     r0 = the contractual rate where available, else the bracket midpoint.
     A contractual seed converges in 3-4 iterations on typical instruments.

3. ITERATE  (Newton-Raphson)
     f'(r) = SUM  -tau_t * CF_t * (1+r)^(-tau_t - 1)
     r_{n+1} = r_n - f(r_n)/f'(r_n)
     Reject the step and bisect instead if it leaves the bracket,
     if |f'| < 1e-20, or if |f| increased.

4. CONVERGE
     Stop when |f(r)| <= tol_abs  OR  |r_{n+1} - r_n| <= 1e-14
     tol_abs = max(1e-10, GCA_0 * 1e-16)      # scale-relative
     Tier 1 exposures use a tightened tolerance (3.9, 10).
     Hard cap 100 iterations, then pure bisection over the bracket
     for 200 iterations.
     The first test fires here, at working precision; step 5's rounding is
     what puts the PUBLISHED residual back outside tol_abs -- see 4.2.1.

5. ROUND
     Round to 12dp HALF_UP. Re-evaluate |f| at the rounded rate and
     record the residual on the computation record.
```

**Bisection fallback is mandatory, not optional.** Newton–Raphson fails on the irregular profiles
that are ordinary in an Indian bank's book: step-up and step-down EMIs, balloons, moratoria with
interest capitalisation, IDC, FITL creation, tranched project finance. Bisection over a bracket
with a sign change is guaranteed to converge, so the fallback path cannot fail to terminate.
Newton–Raphson is there for speed, not for correctness.

Note, though, that under the cap of 100 the *phase* fallback is unreachable: every accepted point
replaces a bracket endpoint, so a Newton phase that bisects on all 100 iterations has halved the
bracket 100 times, and the width test in step 4 fires long before. The guarantee is real and the
path is not exercised. What does happen on irregular profiles is that individual steps are
safeguarded inside the Newton phase, and that count — not the reported phase — is the signal to
monitor across a population.

### 4.2.1 What the convergence test can actually deliver

`tol_abs` says how small a residual is worth chasing. It does not say how small a residual survives
**publication**, and those are different numbers.

The refinement runs at 28 significant digits, where `tol_abs` is comfortably reachable: an ordinary
instrument converges on the residual test in three to seven iterations. Then step 5 rounds the rate
to 12dp, moving it by up to half a unit in its last place, and the residual comes back up by roughly
`|f'|·10⁻¹²/2`. That figure — `residualAtStoredRate` — is the one recorded on the computation and the
one a reviewer sees. Measured on annuities priced net of a 0.5% integral fee, so the root is not
itself a 12dp grid point:

| Instrument | `residualAtStoredRate` | `tol_abs` | inside? |
|---|---:|---:|:--:|
| 50,000 consumer durable, 12 EMIs | 2.5e-08 | 1e-10 | no |
| 50,000 consumer durable, 24 EMIs | 2.5e-07 | 1e-10 | no |
| 1,000,000 EMI loan, 12 months | 3.9e-07 | 1e-10 | no |
| 1,000,000 EMI loan, 24 months | 4.1e-06 | 1e-10 | no |
| 1,000,000,000 facility, 24 months | 4.9e-03 | 1e-07 | no |

Across that sweep **none of the 36 published rates satisfied `tol_abs`**, by two to five orders of
magnitude. Price the same annuities *without* a fee and all 36 satisfy it, at 1e-23 to 4.5e-18 —
which is not the solver doing better but the fixture putting the true root exactly on a 12dp grid
point, so that step 5 has nothing to round. A test built that way measures its own construction, and this
document previously reasoned from exactly that kind of fixture.

None of this is a defect in the solver or in the tolerance: refining past the twelfth decimal place
cannot change a published figure, so there is nothing to buy by chasing further. But it has to be
**stated**, because otherwise a reviewer reading a recorded residual of 4.1e-06 against a `tol_abs`
of 1e-10 has no way to tell that this is what a 12dp rate leaves rather than evidence that the solve
went wrong.

The engine therefore treats `max(tol_abs, rounding floor)` as the bound a published rate must
satisfy. Missing `tol_abs` alone is the ordinary case and carries no information. Missing both means
the refinement did not reach the root, and the computation is marked `REQUIRES_REVIEW` rather than
reported as converged.

**A corollary worth its own line.** A loan priced at its contractual rate has its root exactly on a
ladder node — 1% a month *is* the node `0.01` — and `f` there is around 1e-21: non-zero, so the
bracket is not degenerate, and the root is the bracket's own lower endpoint. `f` is convex and
decreasing, so every tangent step from inside the bracket lands *past* the root and outside the
bracket, is refused by the step-3 safeguard, and pure bisection walks the bracket down to 1e-14.
That is 41 iterations for a root the scan had already found, on the modal instrument in the book,
in a close window that has to price ten million contracts (4.6). Hence the second line of step 1:
ask whether a bracket endpoint is already inside `tol_abs` before refining. It costs nothing — the
scan has both residuals in hand — and it is safe precisely because `tol_abs` is tighter than the
half-ULP of a stored rate, so a node inside it rounds to the same published rate as the true root.

### 4.3 No solution

No sign change over the ladder **or its escalation** means no root exists: `f`'s own coefficient
sequence never changes sign, so it cannot cross zero at any rate. A facility drawn twice and never
repaid is the case. The contract fails into the exception queue with the flow vector attached.

**"Total inflows do not exceed the initial outflow" is *not* this case**, and the spec said it was
until the engine was built against it. For a vector of one outflow at inception and receipts
afterwards, `f` runs from `+∞` as `r → -100%⁺` — discounting at a negative rate inflates — to
`-GCA₀` as `r → ∞`, continuously. It therefore always crosses zero: a unique rate above -100%
exists whatever the recovery. A token recovery of 50.00 against 1,000,000.00 advanced a month
earlier has an EIR, and it is exactly -99.995% per month.

What failed was reach, not existence. A ladder node caps the money multiple the scan can bracket at
`(1+node)^τ`, and that cap collapses toward 1 as τ falls: the top node 10.0 brackets a multiple of
11 over a year and only 1.006591 over a single day. A one-day drawing of 10,000,000.00 with a
100,000.00 integral fee at 6.75% ACT/365F repays 10,001,849.32 against 9,900,000.00 — a multiple of
1.010288 and an unambiguous **4,092.4331% annual effective** — and sat above the ladder. That is
the whole short-tenor population, which is the Tier 3 population (10).

Hence escalation, and hence `REQUIRES_REVIEW` rather than `SOLVED` for anything it finds. A root out
there is one of two things and both need a person: a tenor too short to carry the fee loaded onto
it, where the annualisation is right and the presentation is a policy question (4.5); or a recovery
so far below the advance that the answer is impairment rather than interest. Reporting *which rate*
the vector implies is strictly more useful than reporting that none exists — and it does not send a
reviewer hunting a fee misclassification that is not there.

**It is never defaulted to zero, and never to the contractual rate.** Silently falling back to the
contractual rate is the defect that quietly reproduces the pre-ACPIR position while appearing to
have implemented EIR — the single most damaging failure mode available to this engine, because it
produces plausible numbers and leaves no trace.

### 4.4 Multiple roots

More than one sign change means multiple mathematically valid IRRs. Policy, in order:

1. If exactly one root lies in the **plausible band** — configured per product, default `(-0.5, 2.0)`
   annualised — take it and record that disambiguation occurred.
2. If several lie in the band, take the one nearest the contractual rate, mark the computation
   `REQUIRES_REVIEW`, route for approval. The figure is computed and usable; it is flagged, not
   blocked.
3. If none lie in the band, exception queue.

Every disambiguation records all candidate roots. Uncommon — it needs a genuinely non-monotonic
vector, in practice tranched project finance with large interim drawdowns — but not rare enough to
leave undefined.

### 4.5 Annualisation

Two annualisations exist and they are not interchangeable:

| Form | Formula | Use |
|---|---|---|
| **Effective annual** | `(1+r_p)^p − 1` | Reporting, disclosure, comparison. The economically meaningful figure. |
| **Nominal annual** | `r_p × p` | Comparison to a quoted contractual rate only. |

Case 1: monthly 1.04214918% is **13.248094%** effective and **12.505790%** nominal. Both persist,
both are labelled, neither is called simply "the annual EIR". Interest is always computed from the
**periodic** rate, never by dividing an annual rate, so no annualisation round-trip enters the
amortisation.

**The annualisation optic on short tenor.** A 1% processing fee on a 30-day WCDL, amortised over
30 days, annualises to a rate far above the card rate. The arithmetic is correct; the MIS looks
broken. The presentation convention for sub-year instruments is settled in policy with finance
*before* go-live, not during the first quarter's variance review — otherwise the engine takes the
blame for a reporting design choice.

### 4.6 Performance

Target: 10M contracts inside the close window. Per solve, ~4 Newton iterations over ~360 flow terms
is ~1,440 `BigDecimal` power operations, dominated by `pow`. Mitigations:

- Cache discount factors per `(τ, r)` within a solve; consecutive iterations share `τ`.
- For uniform periodic-index vectors compute `(1+r)^-τ` incrementally — each factor is the previous
  times `(1+r)^-1`, replacing `n` powers with `n` multiplications. Roughly an order of magnitude on
  the common path.
- **Re-solve only on a triggering event, never on every period.** A fixed-rate contract with no
  events solves once, at initial recognition.

That last point is what makes the target reachable, and it is why the B5.4.4 shortcut
([§5.6](#56-the-b544-next-repricing-date-shortcut)) matters so much operationally: naively
re-solving the full schedule on every EBLR reset for every retail account is computationally
brutal at Indian volumes, and the shortcut decouples fee amortisation from the reset loop
entirely.

---

## 5. Amortisation

### 5.1 Gross basis — Stage 1 and Stage 2

```
interest_p     = round(GCA_open,p x r_periodic)
GCA_close,p    = GCA_open,p + interest_p - cash_received_p
```

Under ACPIR, Stage 1 and Stage 2 interest continues on the gross carrying amount and the
provisions are presented separately rather than netted from gross advances.

### 5.2 Stage 3 — see §7

ACPIR suppresses income recognition on Stage 3 entirely. This diverges from IFRS 9's net-basis
mechanic and is handled in [§7](#7-impairment-interaction-the-india-divergence), which is the most
consequential section of this document.

### 5.3 Broken periods

Where a period is not a whole compounding period — first period from disbursement to first due
date, or a period truncated by an event — interest accrues for the actual fraction:

```
interest = GCA_open x ((1 + R_annual)^(D(d_start, d_end)/B) - 1)
```

Compound accretion for the fraction, **not** a simple-interest pro-rata. Pro-rating understates
interest on long broken periods and is a common source of small, persistent breaks.

### 5.4 Event ordering within a period

Deterministic, applied strictly:

```
1. Accrue interest to the event date (broken period, 5.3)
2. Apply cash received
3. Apply the lifecycle event (reset / re-estimation / modification / stage change)
4. Re-solve or restate as the event type dictates (6)
5. Accrue from the event date to period end
```

Two events on the same date order by event-type precedence, then receipt sequence. The precedence
table lives in `eir-domain` as a single enum ordinal with an explicit test — ordering ambiguity
here produces small, irreproducible differences that are miserable to diagnose.

### 5.5 Moratorium and interest capitalisation

Where interest accrues and capitalises during a moratorium — education loans under ACPIR 9(6)(i),
project finance IDC pre-COD — the accrued interest increases the gross carrying amount and the EIR
is computed over total expected life *inclusive* of the moratorium. ACPIR 9(6)(i) confirms interest
becomes due only after the moratorium, so it is not overdue in the interim; the capitalisation is
an EIR input, and the non-overdue status is a staging input. Two separate consequences from one
contractual feature, and the engine must not let one imply the other.

### 5.6 The B5.4.4 next-repricing-date shortcut

Where a premium, discount or fee relates to a variable repriced to market rates before maturity,
IFRS 9 B5.4.4 amortises it to the **next repricing date** rather than over expected life. For a
floating-rate Indian retail mortgage this largely dissolves the behavioural-life estimation
problem — a substantial simplification that is frequently missed and then over-engineered around.

Mechanically the instrument is treated, for fee-amortisation purposes only, as maturing at the
next reset: the projector appends a synthetic `NOTIONAL_REDEMPTION` flow equal to the contractual
balance at that date, and the EIR is solved over the truncated vector.

[Reference Case 8](reference-cases/case-08-b544-repricing-shortcut.md), on the Case 1 loan
repriced annually:

| Basis | EIR | Net fee recognised, months 1–12 | Unamortised fee past the reset |
|---|---:|---:|---:|
| Full expected life (24m) | 13.248094% p.a. | 3,591.71 | 1,408.29 |
| B5.4.4 shortcut (to m12 reset) | 13.436507% p.a. | **5,000.00** | 0.00 |

The shortcut accelerates year-one fee recognition by 39.2% and leaves nothing to carry across the
reset. Two consequences worth stating:

- It is an **accounting policy election per product**, not a per-contract optimisation. Applied
  inconsistently it is indefensible.
- It removes the reset-loop cost entirely: with no unamortised fee to carry forward, the reset
  does not have to re-solve anything for the fee, only reprice the interest leg.

The 1,408.29 carried across the reset under full-expected-life is the same figure as
[Case 2](reference-cases/case-02-full-prepayment.md)'s prepayment acceleration and as invariant
INV-4 at month 12 — three routes to one number, which is a useful engine cross-check.

---

### 5.7 Rounding and residue policy

**Where rounding happens.** Once per persisted figure, at currency scale, `HALF_UP`. Rolling balances
are carried at working precision **and** at presentation scale; the presented balance is derived from
the presented components so that the published table adds up. A published movement schedule whose
columns do not sum is a defect even when every figure is individually correct — controllers reconcile
from the published numbers, not from the working ones.

**Terminal residue on the EIR leg.** Invariant TR-1: on a contract run to contractual maturity with
no events, the closing gross carrying amount on the **EIR leg** is exactly zero. It is exactly zero
because the rate was solved against that same flow vector. Any non-zero terminal balance on the EIR
leg means the solve and the roll-forward disagreed about the flows — failure mode
[01 §11 #9](01-domain-primer.md#11-common-failure-modes).

**Residue on the contractual leg is real and is not a defect.** In
[Case 1](reference-cases/case-01-emi-loan-with-fees.md) the true annuity payment is 47,073.472223 and
the billed EMI is 47,073.47; over 24 periods that 0.002223 monthly shortfall compounds to a
**0.059969** residue. Every real lender resolves this somewhere, and the engine must follow whatever
the core banking system does rather than invent its own answer:

| Policy | Behaviour |
|---|---|
| `LMS_AUTHORITATIVE` *(strongly preferred)* | Consume the schedule the CBS actually billed; do not derive one. |
| `FINAL_PERIOD_PLUG` | The last instalment absorbs the residue. Final EMI becomes 47,073.53. |
| `FIRST_PERIOD_PLUG` | The first instalment absorbs it. |
| `SPREAD_LAST_N` | Spread over the final `n` instalments. |

`LMS_AUTHORITATIVE` is preferred in production and is why the projector accepts an externally-supplied
schedule as a first-class input (FR-102, [ADR-0004](adr/0004-delta-over-contractual-ledger.md)).
Deriving a schedule the CBS did not bill guarantees a reconciliation break every month, which turns
control C-14 into noise. The plug policies exist for instruments where no external schedule is
available.

Under any plug policy the residue lands in the contractual leg only; it never touches the EIR leg.
Invariant INV-3 is stated against the **actual billed flows**, so it holds regardless of which policy
is in force.

**Residue is never resolved by tolerance.** A tolerance hides exactly the class of defect this system
exists to prevent — see [ADR-0002](adr/0002-precision-policy.md). Where a difference exists, a rule
accounts for it.

---

## 6. Event treatments

ACPIR is **silent** on subsequent changes in cash flows — there is no equivalent of B5.4.5 or
B5.4.6. Yet every EBLR reset, every prepayment-curve revision and every credit ratchet is a change
in estimated cash flows. The engine therefore adopts the IFRS 9 mechanics by election under the
[§2](#2-interpretive-hierarchy) hierarchy, and — critically — implements the choice between them as
**configuration, not code**.

### 6.1 Routing is configuration, keyed to a driver taxonomy

The IASB is actively amending B5.4.5. As at the compilation date of the
[domain reference](reference/acpir-2026-eir-application-reference.md), the April 2026 tentative
decision would adjust the EIR for re-estimations providing consideration for *the time value of
money or for credit risk*, with an Exposure Draft planned for H2 2026 — meaning pre-determined
adjustments that compensate for neither (ESG ratchets, step-ups) would route to a catch-up instead.
**The rule this engine is being asked to encode is under active revision.**

Accordingly: every contractual rate component carries a `driver` tag recording *what it
compensates for*, and routing is a mapping table from driver to mechanism.

| Driver tag | Meaning | Default mechanism |
|---|---|---|
| `TIME_VALUE_OF_MONEY` | Benchmark movement — repo, EBLR, MCLR, inflation index | `RESET` |
| `CREDIT_RISK_MARKET` | Repricing of credit spread to prevailing market | `RESET` |
| `CREDIT_RATCHET_PREDETERMINED` | Contractual step keyed to a covenant or rating trigger | `CATCH_UP` |
| `ESG_LINKED` | Sustainability margin ratchet | `CATCH_UP` |
| `STEP_UP_PREDETERMINED` | Contractual step-up unrelated to market | `CATCH_UP` |
| `BEHAVIOURAL_ESTIMATE` | CPR / prepayment-curve revision | `CATCH_UP` |
| `DISBURSEMENT_TIMING` | Tranche schedule deviation | `CATCH_UP` |
| `NEGOTIATED` | Renegotiated terms | Modification test ([§6.4](#64-modification-versus-derecognition)) |

Build the switch, not the constant. When the Exposure Draft lands, the change is a mapping-table
version — an approved policy change with an impact preview — not a re-opened engine. Recorded in
[ADR-0006](adr/0006-configurable-event-routing.md).

The mapping is also where the bank's **auditor's house view** gets encoded, and firms differ:
one view applies B5.4.5 to a reset of *any* component of market rates; another extends it to
features resetting to a current market rate including inflation-indexed principal and variable
credit-spread ratchets. This is an accounting policy choice that must be made, applied
consistently and disclosed. The engine makes it explicit and versioned rather than implicit in
code.

### 6.2 The decision table

`EIR` = the rate; `GCA` = gross carrying amount.

| Event | Rate | GCA | P&L now | Reference |
|---|---|---|---|---|
| Benchmark reset, driver `TIME_VALUE_OF_MONEY` | **Re-solve** over remaining flows from current GCA | Unchanged | None | B5.4.5, [Case 4](reference-cases/case-04-floating-rate-reset.md) |
| Market credit-spread repricing | **Re-solve** | Unchanged | None | B5.4.5 |
| Revised cash-flow estimate (CPR, tenor re-profile) | Unchanged | **Restate** to PV of revised flows at **original** EIR | Catch-up | B5.4.6, [Case 3](reference-cases/case-03-b546-reestimation.md) |
| Pre-determined ratchet / ESG / step-up | Unchanged | **Restate** at original EIR | Catch-up | Apr 2026 tentative decision |
| Tranche schedule deviation past tolerance | Unchanged | **Restate** at original EIR | Catch-up | B5.4.6 |
| Non-substantial modification | Unchanged | **Restate** at original EIR | Modification gain/loss | 5.4.3 |
| Substantial modification | **New** EIR on the new asset | Derecognise; new asset at fair value | Derecognition gain/loss | 3.2.3 |
| Part-prepayment, tenor shortened | Unchanged | Reduce by cash; re-project | None beyond accrual | — |
| Part-prepayment, EMI reduced | Unchanged | **Restate** at original EIR | Catch-up | B5.4.6 |
| Full prepayment / closure | n/a | To zero | **Accelerate** unamortised fee | [Case 2](reference-cases/case-02-full-prepayment.md) |
| Stage 1/2 → Stage 3 | Unchanged | Unchanged | **Suppress income** ([§7](#7-impairment-interaction-the-india-divergence)) | ACPIR |
| Stage 3 → Stage 1/2 (cure) | Unchanged | Unchanged | Resume gross basis **prospectively** | ACPIR |
| Allowance remeasured | Unchanged | Unchanged (GCA is gross) | None from this engine | ACPIR 6(12) |
| DLG invoked | Unchanged | Unchanged | None from this engine | ACPIR 88 — triggers ECL recompute |
| Write-off | n/a | To zero | Per impairment policy | — |
| Rate renegotiated on a **fixed**-rate instrument | Per modification test | Per modification test | Per modification test | 5.4.3 |

The last row is the trap. A fixed-rate loan whose rate is renegotiated is **not** a B5.4.5 event —
B5.4.5 covers instruments repricing off a market benchmark *by their own terms*. A negotiated
change to a fixed-rate loan is a modification and runs the substantiality test. The engine keys
treatment off the instrument's **rate type** and the event's **driver tag**, never off the
observation that the rate moved.

### 6.3 The catch-up computation

```
GCA_restated = SUM  revised_flow_t / (1 + r_original)^tau_t
catch_up     = GCA_restated - GCA_before
```

Positive is income, negative an expense. Discounting is at the **original** EIR.
[Case 3](reference-cases/case-03-b546-reestimation.md) produces **−627.42**, a charge.

A frequently-seen defect is discounting at a re-solved rate, which drives the catch-up to
approximately zero and quietly converts a B5.4.6 event into a B5.4.5 one.

**Invariant CU-1:** for a catch-up event the persisted EIR before and after must be bit-identical.
Asserted, because it is the cheapest possible detector of that defect.

Because a CPR assumption change is a catch-up event across **every affected contract
simultaneously**, a one-line assumption change can move a material number. Assumption changes
therefore run through maker–checker with a mandatory portfolio-level impact preview before the
version goes effective.

### 6.4 Modification versus derecognition

ACPIR is silent on the accounting boundary — paragraphs 78–81 govern stage migration on
restructuring and cross-refer the Resolution of Stressed Assets Directions 2025, but say nothing
about whether the original EIR survives. For a bank with a restructuring book, a DCCO-deferment
book and FITL creation this is the most consequential single gap.

The engine's posture, and it is deliberately not full automation:

- Compute the quantitative **10% test** and record the result as **evidence, not the decision**.
  The test is authoritative for financial *liabilities* (B3.3.6); IFRS 9 sets no equivalent bright
  line for assets, and the IASB's February 2025 tentative direction is toward a principles-based
  qualitative assessment where outcomes cannot be determined by a quantitative test alone.
- Evaluate configured **qualitative triggers**: change in the basis for determining interest
  (fixed to floating), change of currency, change of obligor, introduction of an equity conversion
  feature, a change causing SPPI failure, conversion of a revolving facility to a term loan,
  changes made for commercial reasons aligning terms to current market.
- For assets, produce a **recommendation requiring approval** where the test lands within a
  configurable band around the threshold.
- Record who decided, on what basis, under which policy version.

This is a refusal to automate a judgement. Silently derecognising a corporate loan on a 10.02%
test result is not a feature. DCCO deferment and FITL creation each need their own documented
position — whether the deferment is itself a modification requiring a catch-up is unanswered by
ACPIR and is the highest-value position for a wholesale bank to get right.

### 6.5 Part-prepayment: the two variants

Both reduce the balance; they diverge on the remaining schedule.

- **Tenor reduced, EMI held.** Fewer future flows at the same amount. Re-project and continue; no
  catch-up is booked.
- **EMI reduced, tenor held.** The flow *pattern* changed, which is a revision of estimated
  receipts. Restate at the original EIR with a catch-up.

Which applies is a contractual or borrower election carried **on the event**, never inferred from
the resulting schedule.

> **The reason is the election, not the size of the number.** An earlier draft of this section
> justified booking nothing on a tenor reduction by asserting that the original EIR still discounts
> the remaining flows to the post-payment carrying amount "closely enough that no restatement is
> warranted". That is false, and measurably so. On the
> [Case 1](reference-cases/case-01-emi-loan-with-fees.md) loan at month 12 with a 100,000
> part-prepayment, discounting each variant's revised flows at the original EIR against the
> post-payment carrying amount of 428,407.32 gives:
>
> | Variant | Revised schedule | PV at original EIR | Implied restatement |
> |---|---|---:|---:|
> | Tenor reduced | EMI held at 47,073.47, 10 periods (final stub 29,364.65) | 428,875.96 | **468.64** — *not booked* |
> | EMI reduced | 12 periods held, EMI 38,188.60 | 428,673.22 | **265.90** — *booked* |
>
> The variant that books nothing produces the **larger** divergence. The unamortised fee is
> untouched by the cash in both cases, and a tenor reduction leaves it *fewer* periods to amortise
> over, so the gap widens rather than narrows.
>
> The engine's posture is nonetheless correct, for a different reason. The two variants are
> different **contractual elections**, and B5.4.6 attaches to a revision of *estimated receipts*.
> Holding the instalment and shortening the tenor consumes the schedule as written; re-sizing the
> instalment changes the pattern of receipts and is therefore a revision. Justifying the treatment
> by the magnitude of the number invites exactly the wrong question at audit — "how close is close
> enough?" — when the answer turns on what the borrower elected.

### 6.6 Rollover versus new instrument

The dominant question for WCDL, gold loans, bill discounting and CP. A 90-day WCDL rolled
continuously for three years is either a series of new instruments each striking a fresh EIR, or in
substance a revolving facility. The engine takes this as a **product-level policy attribute**, not
a per-event judgement at the desk, and the same attribute feeds the ACPIR 46(2)(ii) test on whether
a renewal process is genuinely substantive.

---

## 7. Impairment interaction: the India divergence

This section is where ACPIR departs from IFRS 9, and it is the control auditors will look at hardest.

### 7.1 The three regimes

| Stage | IFRS 9 | ACPIR 2026 |
|---|---|---|
| Stage 1 / 2 | EIR on gross carrying amount | **Same** |
| Stage 3 | EIR on amortised cost (gross less allowance) | **Income not recognised** |
| POCI | Credit-adjusted EIR on amortised cost from inception | **Aligned** (ACPIR 24, 50) |

### 7.2 Stage 3: compute the unwind, suppress the income

ACPIR does not recognise income on Stage 3 assets. But ECL is a present-value measure discounted
at the EIR (ACPIR 50), so the discount **unwinds mechanically** every period whether or not
anything is recognised. The engine must therefore compute the unwind and keep it out of the P&L —
and ACPIR does not say where it goes, which is an open question the bank must close by policy.

[Reference Case 5](reference-cases/case-05-stage-3-acpir-suppression.md), 40% allowance:

| # | Quantity | Amount |
|---|---|---:|
| (a) | Gross-basis interest, `GCA × EIR` — the Stage 1/2 amount | 5,506.79 |
| (b) | IFRS 9 Stage 3 net-basis interest, `AC × EIR` | 3,304.08 |
| (c) | ECL discount unwind, `allowance × EIR` | 2,202.72 |
| | **Recognised in P&L under ACPIR** | **0.00** |
| | Contractual interest to interest-in-suspense | 5,298.16 |

**Invariant ST-2, exact:** `(b) + (c) = (a)`. 3,304.08 + 2,202.72 = 5,506.79.

That identity is worth dwelling on, because it is what makes the Indian treatment tractable. The
ECL discount unwind is *precisely* the interest the gross basis would have recognised on the
allowance portion of the balance. So the engine does not need a separate unwind model: it computes
one gross-basis figure and decomposes it. IFRS 9 recognises (b) and books (c) inside impairment;
ACPIR recognises neither, and the suspense ledger absorbs the contractual amount.

### 7.3 What the engine maintains

Four parallel quantities per Stage 3 contract per period, all first-class and all reconciled:

1. **Gross carrying amount** — continues to roll forward on the gross basis. Staging never changes
   the GCA or the EIR.
2. **Shadow EIR unwind** — computed and retained for the ECL roll-forward. Not P&L.
3. **Interest-in-suspense** — the contractual interest billed but not recognised. A first-class
   ledger object, not a memorandum note: the Hong Kong precedent is that a suspense regime
   reconciles to accounting EIR only when the suspense ledger is a real object.
4. **Recognised interest income** — zero while in Stage 3.

Control S3-1 requires all four to reconcile every period, and it is the control that will attract
the most audit attention because it is where India diverges.

### 7.4 Cure

On cure, recognition resumes on the gross basis **prospectively**. No catch-up for interest not
recognised while in Stage 3 — recognising it would recognise income that was correctly never
recognised. The EIR is unchanged throughout; staging is not an EIR event.

For **credit cards and KCC**, individual account-level suspension analysis is impractical at
volume. ACPIR provides no portfolio carve-out, so one must come from policy; the HKMA precedent of
an explicit portfolio treatment for portfolio-managed products is the model, and the engine
supports suspension at pool level with the pool definition as the approved artefact.

### 7.5 Pre-floor and post-floor duality

ACPIR 90 applies prudential floors per product category — portfolio basis for Stages 1 and 2,
mandatorily account level for Stage 3. Where a floor binds, the EIR-derived accounting number and
the reported provision diverge.

The engine produces the **pre-floor number as a first-class, retained output**, never as an
intermediate that the floor overwrites. Ordering is: compute the accounting number at the EIR,
apply the floor, report both. Control PF-1.

---

## 8. POCI: the credit-adjusted EIR

ACPIR 6(4), 24 and 50 track IFRS 9 closely: the credit-adjusted EIR discounts **expected** cash
flows, already net of expected credit losses, to the **amortised cost** at initial recognition —
not the gross amount. Day-1 lifetime ECL sits inside the rate, so there is no separate day-1
allowance; thereafter only *cumulative changes* in lifetime ECL relative to the initial estimate
are recognised.

[Reference Case 6](reference-cases/case-06-poci-credit-adjusted-eir.md), a pool bought for 700,000
against 24 × 47,073.47 contractual with 20% of every receipt expected lost:

| Basis | Monthly | Effective p.a. |
|---|---:|---:|
| Credit-adjusted EIR (expected flows) — **correct** | 2.15405231% | 29.141905% |
| EIR on contractual flows — **wrong** | 4.24580927% | 64.703664% |

Discounting contractual flows to a distressed purchase price yields 64.7% per annum: arithmetically
impeccable, economically fictional, and it would accrue income the entity has no expectation of
collecting before reversing it through impairment.

Two ACPIR-specific rules:

- ACPIR 6(3)(v) makes **acquisition at a discount reflecting inherent credit losses** an explicit
  credit-impairment indicator. POCI identification is therefore driven off acquisition terms, not
  only off subsequent behaviour, and it applies at transition as well as at origination.
- The credit-adjusted EIR is **retained after cure**. It is not reset. Enforced as invariant POCI-1.

---

## 9. Invariants

Asserted in production, not only in tests. A breach raises a control exception and blocks the
period close.

| ID | Invariant | Scope |
|---|---|---|
| IC-1 | `GCA₀` = net cash flow at inception | Initial recognition |
| TR-1 | Terminal EIR-leg GCA = 0 on a full-term, event-free contract | Contract close |
| INV-1 | Σ EIR interest over life = Σ contractual interest + net integral fee ± catch-ups | Contract close |
| INV-2 | `sign(EIR − contractual) = sign(F − G)`, where `F` is the net integral fee and `G = P − PV(billed flows @ contractual)` is the schedule's par gap; unresolvable only where `\|F − G\| ≤ 0.01` | Post-solve |
| INV-3 | Σ cash received = principal + Σ contractual interest (on billed flows) | Contract close |
| INV-4 | Unamortised fee balance = contractual GCA − EIR GCA | Every period |
| CU-1 | EIR unchanged across a catch-up restatement | Event |
| CU-2 | Catch-up = PV(revised, original EIR) − GCA_before | Event |
| ST-13 | A schedule whose structure implies par pricing prices to par at its own coupon, within the measured instalment-rounding residue | Post-projection |
| PG-1 | No policy version is EFFECTIVE without a stored impact preview for that draft's content (FR-210) | Policy activation |
| PV-1 | A policy version of each consulted kind resolves for every date in a closed period | Period close |
| RT-1 | Every routed event resolves to a routing table version in force on its date ([ADR-0006](adr/0006-configurable-event-routing.md)) | Event routing |
| RS-1 | Every fee code in the rule set has a per-code default in force (FR-201) | Rule-set approval |
| **ST-2** | **Stage 3: net-basis interest + ECL unwind = gross-basis interest**, plus the decomposition's accrual length and interest against the ledger row it decomposes | Every period |
| **S3-1** | **Stage 3 four-way reconciliation**, as one result over four legs: closing GCA = opening + EIR accrual − cash applied; closing suspense = opening + charged − recovered − written off; what was charged to suspense is what was billed and not recognised; cash applied to interest = suspense recovered. Deviation is the total **absolute** residual | Every period |
| S3-2 | Recognised interest income on a Stage 3 contract = 0 | Every period |
| SG-1 | A stage migration left the EIR unchanged — staging is not an EIR event (FR-610). Deviation is a **rate** | Event |
| SG-2 | A stage migration left the gross carrying amount unchanged (FR-610) | Event |
| CR-1 | The cure period recognises its own gross-basis interest and nothing more — no catch-up for suppressed periods (FR-607) | Event |
| **PC-1** | **No `EXCLUDED_BY_DIRECTION` posting entered any EIR stream or the GCA** — one result per period, the conjunction over every screening route that applies | Every period |
| PF-1 | Pre-floor ECL retained: the reported provision is the greater of the EIR-derived figure and the ACPIR 90 floor, and both survive | Period close |
| PF-2 | A Stage 3 exposure is floored at **account level**, never on a portfolio basis (ACPIR 90) | Period close |
| POCI-1 | Credit-adjusted EIR unchanged across a cure | Event |
| HB-1 | No discontinued hedge relationship without an active basis-adjustment amortisation schedule | Every period |
| HB-2 | No hedging or swap cost present in any EIR cash flow stream | Every period |
| SL-1 | Σ sub-ledger contract balances = GL control account balance, with **zero unexplained** difference — an explained difference needs a cause and a narrative, and the claims filed against an account may not exceed its difference in gross | Period close |
| SL-2 | Σ journal debits = Σ journal credits, **per contract** — the run grain is the sum of the same residuals and is not a second check. Deviation is the total **absolute** residual | Every posting |
| DT-1 | Re-run of a closed period reproduces published figures **bit-identically** — scale included, so `1.0` against `1.00` is a breach — **and** resolves against the policy versions in force then, including a kind in force that neither run consulted | Nightly replay |
| TG-1 | Every Tier 3 population has a current, in-date equivalence test on file | Annual |
| CL-1 | A closed period is never mutated; a correction is a dated restatement artefact in system time, leaving business time alone (FR-902) | Period close |
| RC-1 | The contractual interest leg ties to core banking with zero unexplained difference (FR-804, C-14) | Period close |
| PL-1 | No exposure has income suspended except under a pool definition in force on the date (FR-608) | Every period |
| PL-2 | Only portfolio-managed products — cards and KCC — are suspended at pool level (FR-608) | Pool approval |
| TF-1 | No transition fair value relies on the ACPIR 19 paragraph 19 presumption without a rebuttal evidence reference (FR-908) | Transition valuation |
| BM-1 | No day-1 below-market difference is taken to a destination without an approved Board position (FR-909, reference § 4 Silence 6) | Origination |
| LC-1 | No legacy cohort surviving 31 March 2030 is queued behind one that runs off before it (FR-908) | Migration planning |
| DE-1 | Every cohort measured on a deemed EIR has an **approved** derivation on file (FR-909) | Migration planning |
| TM-1 | ACPIR 21 and ACPIR 50 are **tracked separately**: every contract carries a recorded ECL discount basis, and none remains on the interim basis after 31 March 2030 | Period close |

**On ST-2 being an identity, and what to do about it.** The first limb is a *tautology by
construction* and cannot fail. The engine computes one figure — the gross-basis interest the Stage
1/2 ledger would have recognised — and derives the net basis and the unwind from it by subtracting
the same accretion applied to the allowance. `net + unwind = gross` then holds whatever the accrual
factor was, including badly wrong: it checks the **decomposition**, never the **magnitude**. That is
not a reason to drop it — the decomposition is what a reader has to be able to follow, and the limb
is what makes the Indian treatment legible — but it must not be mistaken for assurance about the
number.

Getting this wrong twice is instructive. A cross-check was added to close the gap and recomputed the
gross interest as `GCA × accretion(EIR, exponent)` against a figure computed as
`GCA × accretion(EIR, exponent)` — the same expression, so bit-identical by construction, and with a
wrong exponent both sides were wrong by the same factor. A second tautology, emitted under the same
`ST-2` id as the first, in the belief that it fixed the first.

The check that works compares the decomposition to the **amortisation row it decomposes**. Those are
two independent derivations of the accrual length: the ledger's comes from the flow vector's dates
and the time convention, the decomposition's from its caller. A disagreement is a broken first period
or a mis-selected day count — precisely the defect the identities cannot see. The accrual length is
compared before the interest, because it is the cause and the other is the effect.

**On S3-1 having been four claims and none of them itself.** Worth recording next to the ST-2 and
PC-1 notes below, because it is the same defect for the third time and the mechanism is identical.
S3-1's statement is a four-way reconciliation. It was published from four places and not one of
them was one:

| What was published under `S3_1` | What it actually was |
|---|---|
| Billed interest splits into recognised income and suspense | A tautology — asserted three lines below the two that construct the split |
| The cure period recognises no catch-up | FR-607, now **CR-1** |
| The EIR is unchanged across a stage migration | FR-610, now **SG-1** — with a *rate* deviation |
| The GCA is unchanged across the same migration | FR-610, now **SG-2** |

Each of the last three is a real control and each was correctly computed. What was missing was the
one the id names, and it was missing for a structural reason: the four quantities do not live in one
place. `Stage3Decomposition` can see the EIR accrual and the recognised amount and cannot see the
carrying-amount ledger or the suspense balance — so a reconciliation of all four was not something
it could assert, and what it asserted instead were the claims it could. Interest-in-suspense being a
single `Money` field rather than a ledger (FR-604) is the same gap seen from the other side.

Two consequences followed from the sharing, both of which `conjunction` makes unavoidable. It keeps
only the **first** breach's deviation among results sharing an id, so of four claims at most one
figure ever surfaced. And one of the four carried a **periodic rate** while the rest carried rupees,
so a close aggregating S3-1 deviations was adding quantities that are not comparable. The remedy in
every case has been the same: one id per claim, and where a claim genuinely has several legs — S3-1
now, PC-1 below — **one result** covering all of them, with the deviation aggregated deliberately.
S3-1 sums the legs' residuals in *absolute* terms, because signed residuals let two breaks in
opposite directions net to a reconciled period.

**On TM-1, and an invariant that would have been red by design.** Worth recording because the
first formulation was wrong in a way that looked right. ACPIR 21 puts the loan under the EIR regime
and ACPIR 50 moves its ECL discounting to the EIR; they share a deadline of 31 March 2030 and they
are two obligations, which is why [04 § 6](04-data-model.md#6-transition-specific-structures) gives
the discount basis its own table rather than a column on the contract. A contract can be on the EIR
for interest while its ECL is still discounted at the contractual rate, and a single migration flag
cannot express that.

The obvious control is "fail while any contract is still on the interim basis". It fails
continuously from 2027 to 2030 — and a breach blocks the close, per the sentence at the top of this
section — so it would block every close for three years while describing a state ACPIR 50
explicitly permits. **A control that is red by design is a control that gets suppressed, and then
it is absent for the year it matters.**

So TM-1 asserts that both obligations are *tracked*, not that both are *finished*, and fires on the
two things that are genuine failures on the day they occur: a contract with no recorded basis, and
a contract still on the interim basis after the deadline. The size of the remaining migration is
published as plain data, because a shrinking number is what a programme tracks and a control is not
the place to put it.

The same reasoning drew two other Phase 4 lines. Wasted reconstruction effort — a cohort queued for
full reconstruction that runs off before 2030 — is reported as data and not under an id, because
spending effort badly is not an accounting breach and putting a programme-management question in
this table is how the table stops being read. And valuation coverage against the contract master is
reported as data too: a contract missing from a valuation population was never presented, so an id
raised from the run would attribute a data-feed problem to the valuation.

**On what four independent reviews of Phase 5 found, and why the tally matters.** Phase 5's four
units were built in parallel and reviewed adversarially, one reviewer per unit, each asked to
construct a failing input for every invariant the unit published. Every unit's own tests passed —
205 of them — and the reviews still found:

| Finding | Family |
|---|---|
| DT-1's policy leg iterated only the kinds a *run* had stamped, so two unstamped runs compared nothing and returned a **pass** | control that cannot fail |
| `isVacuous()` was computed, honest, and **not in the result**, so a comparison of nothing reported green | control that cannot fail |
| SL-1's register check gated on the account *already agreeing*, so a cancelling pair of explanations was invisible wherever it was not | control that cannot fail |
| An explanation filed twice reduced RC-1's deviation twice; duplicate *figure* lines were refused with careful reasoning | asymmetric guard on the input that reduces |
| Explanations carried no period, while both figure sides were period-checked | asymmetric guard on the input that reduces |
| A UTC+14 date-widening copied between two packages | one rule, two places |
| Multi-currency aggregation answered in opposite ways by two files in one package | one rule, two places |
| Three javadocs describing a scope the code did not have | one rule, two places |

**Two more, from wiring Phase 5's controls to a caller in `eir-application`.** The four
`eir-application` units carry no independent adversarial review — all four reviewers failed on the
session limit — so the substitute was a self-review of the *seams* between them, which is where
Phase 1's four defects were and where these two were:

| Finding | Family |
|---|---|
| `ReconciliationScope.requiredForClose()` is true for all four of step 6's reconciliations and `RunClose` presented two, so the gate refused **every** close | control with no caller |
| `OnboardingRun` declared its obligations and reported the gaps; `RunAggregate` derived its invariant set from what the contracts happened to publish, so an invariant nobody evaluated read as one that passed | control that cannot fail; one rule, two places |

**Two more, from the adversarial review of `eir-application`'s own four units** — the review those
units shipped without, and the third pass over this seam:

| Finding | Family |
|---|---|
| `ContractPipeline` threw on `Mechanism.NONE` alongside `DERECOGNITION`, so every contract whose driver a bank had elected as immaterial was quarantined, every month | control with no caller, in the strict direction |
| `RoutingTable` accepted `DERECOGNITION` as a driver's treatment, which derecognises every event on that driver with no substantiality assessment | missing guard, at the artefact a maker–checker gate approves |
| `TierAssignmentResult.requiresEquivalenceTest()` is true for every Tier 3 assignment and `InitialRecognition` never asks; `EquivalenceTestGate` has no caller outside its own package | control with no caller, in the permissive direction — **closed**, see below |

**The `NONE` pair is the fourth family in both directions at once.** `Mechanism.NONE` means "no EIR
consequence", which is precisely a roll-forward; throwing on it was the pipeline being *too strict*
about a value the routing table may legitimately carry, and `RoutingTableFormat`'s own tests already
pinned that "DISBURSEMENT_TIMING routed to NONE is a legitimate materiality election". Those tests
also located the accounting opinion correctly — "the format's job is to say what the file means, not
to hold an accounting opinion that `RoutingTable` itself does not hold" — and `RoutingTable` then
held no opinion, so the question fell through three files to the pipeline, which answered it by
quarantining the contract. `Mechanism.isRoutable()` is where the opinion now lives: `NONE` is
routable because it is an election a bank is entitled to have approved, and `DERECOGNITION` is not,
because derecognition is the *conclusion* of the substantiality assessment reached per modification,
never a treatment a driver carries. One refusal when a table is authored beats a wrong number per
contract for as long as the version stays in force.

**The tier finding is closed, and how it closed is the more useful record.** It was left open on a
stated reason: wiring `EquivalenceTestGate` needs an `EquivalenceTestSubject`, and three of its six
fields — `populationId`, `redemptionAmount`, `contractualCouponTotal` — are not derivable from an
`OnboardingRequest` or a `ProjectionResult` without a judgement about how a contract maps to an
equivalence-test population and how a coupon leg is separated from principal repayment across a
schedule shape. Supplying a *guessed* `contractualCouponTotal` to the gate whose purpose is catching a
fabricated approximation would have been the worst available version of the fix.

What resolved it was noticing that two of the three are arithmetic rather than judgement.
Contractual coupon total is total contractual inflows less principal — a sum over the projection the
engine has already built, not an estimate — and the redemption amount is the final principal flow.
`EquivalenceTestSubject.couponBearingAtPar` takes them that way, with
`discountInstrument` for a vector carrying no interest leg. Only `populationId` was a judgement, and
it is a *policy* one: `productId + ":" + segment`, decided and recorded rather than inferred.
`InitialRecognition.recognise` now consults the gate for every Tier 3 assignment and returns a
`TierPermission` carrying the effective tier, the TG-1 result and any queue entry.

**Three further findings, from wiring that gate to callers and merging seventeen parallel units.**
This is the fifth time this document has recorded the same lesson, and it arrived the same way:

| Finding | Family |
|---|---|
| `EirService` called `InitialRecognition.onboard`, which returns the disposition and **drops** the `TierPermission` — so a Tier 3 contract through HTTP was gated, demoted, solved on Tier 2's tolerance, and reported back as `TIER_3` with no exception | control with no caller, in the permissive direction |
| `OnboardingRun.blocksClose()` read breaches, the quarantine count and `assertedNothing`, and a TG-1 demotion trips none: `STALE_EQUIVALENCE_TEST` does not quarantine, so `describeClose()` printed "close may proceed" while `ExceptionQueue.blocksClose()` said otherwise | one rule, two places, with two answers |
| `AccessControlModule`'s coverage report read a hand-maintained inventory of nine routes while forty-one were registered, so it named a gap of eight over a surface it had never seen | control that reads as coverage |
| `EquivalenceTestGate`'s same-day tie-break ranked on `excessOverThresholdBps()`, which clamps at zero — two duplicates that both passed compared equal, and the governing test fell to the register's list order | control that cannot fail, in the case it was written for |
| `Json.Obj` took the second value for a repeated key silently, so a response that assembled a figure in two branches published one and discarded the other | silent loss on the value path |

**The first two were named in javadoc by the unit that built the gate, as changes it could not make,
and neither was found by a test.** That is the honest reading and it cuts both ways: a unit that
writes down what it cannot reach is doing the right thing, and a written-down defect is still a
defect until somebody with the reach closes it. Both are now closed with the assertion that pinned
the divergence *inverted* rather than deleted — `TierPermissionTest` asserted the run said false while
the queue said true, and now asserts they agree and that nothing was quarantined and no invariant
breached, so it cannot pass for the wrong reason.

**A sixth pass, on `eir-persistence-jdbc` after its first successful build.** The module had never
compiled under its profile — its tests still called two methods the main classes had refactored away
— so no claim in it had ever been tested by execution. Building it, running its live suite against a
real PostgreSQL 16 cluster, and reviewing it adversarially produced the largest single crop of this
programme, and the shapes are the familiar ones:

| Finding | Family |
|---|---|
| `JdbcCoreBankingFeed` and `JdbcContractStateSource` read the same `cbs_billed_interest` row under the same predicate, so under the JDBC wiring **both sides of RC-1 come from one column of one table** and its deviation is identically nil | control that cannot fail — the literal "field against itself" its own port javadoc warns of |
| The flow vector is read over the accounting calendar month while `ContractPipeline` defines the accrual period as `(dueDate(n−1), dueDate(n)]`; a contract whose instalment falls outside the month gets an empty vector and a zero-amount boundary flow | one rule, two places, with two answers |
| A weekly or fortnightly contract yields several accrual boundaries in one month and `ContractPipeline` refuses `roll.periods() != 1` — reinstating, one layer up, exactly the abort `CompoundingBasis.stepOf` was rewritten to prevent | a refusal moved rather than removed |
| `SELECT_POPULATION` ignores the `bookId` the adapter holds, so a run of one book enumerates FR-109's parallel books | asymmetric guard: the population is unscoped while the balances are scoped |
| `readRateInForce` wraps the stored rate without consulting `eir_computation.convention`, which the period source reads for the same solve | one rule, two places |
| The live suite's **business-time predicate is behaviourally unfailable** — deletable from both queries with no test failing — while its system-time predicate is genuinely exercised against a superseded version | control that cannot fail, on one axis of two |
| The flow vector has **no decision-time axis at all**, undeclared, while the module declares two other such gaps honestly in javadoc | a gap named nowhere, in a module built to close exactly this |

**The one the live suite caught by itself is the one worth dwelling on.** `RemainingPortsLiveTest`
expected period ordinal 12 where the engine derives 13, and the engine is right. The test's
derivation named its own error — "YearMonth 2026-05 to 2027-04 is eleven months" — which is the
truncation `PeriodId.elapsedPeriods` was rewritten to replace, and which counts a due date already
passed within the month as not passed. Left at 12 it would not have been a failing test: it would
have been ST-2 red on every contract whose due day is not the 1st, on every period, because
`ContractPipeline` and the roll-forward derive the accrual length independently and that
independence is the whole reason ST-2 is a control rather than a tautology.

**None of the five defects is active, and saying why matters.** Nothing constructs `JdbcPorts` for a
run, so they are latent — which is the honest reason they are recorded rather than patched in the
same hour they were found. The engine's exit gate for this module is not "the tests pass"; it is a
close driven through these ports.

**That close has now been run, and it found a tautology that five review passes and 3,400 tests did
not.** With `eir-batch` wired to the JDBC ports, a 24-period loan closed at a **nil gross carrying
amount** while SL-2 balanced, ST-2 tied at nil deviation, CU-1 and CU-2 both reported satisfied, and
the run reported 0 breaches and 0 unaccounted contracts.

**The mechanism, traced rather than assumed** — a first reading of this attributed it to the ordinary
roll over a one-flow schedule, and that was wrong. The period carries a `NEGOTIATED` event which the
baseline table routes to `MODIFICATION_TEST`: a B5.4.6 catch-up restatement, where the carrying amount
becomes the present value of the **revised** expected cash flows at the original EIR. The revised
vector held **one** flow of ₹47,073.47 against a term of 24 periods at ordinal 13. So the restated
balance was that flow's PV at 0.0115 — ₹46,538.28 — and the catch-up was **−₹481,869.04: a 91%
write-down of the carrying amount in a single period.** The roll then closed the contract to nil, which
`CatchUpCalculator` says happens "by construction" once the restated balance is the PV of those flows.

**CU-2 asserts an identity.** In `CatchUpCalculator.restate`:

```java
Money catchUp = restated.minus(gcaBefore);      // the quantity is DEFINED here
...
InvariantResult.ofMoney(InvariantId.CU_2, "...",
    restated.atPresentationScale().minus(gcaBefore.atPresentationScale()),  // expected
    catchUp);                                                               // actual
```

The two sides are the same subtraction, one rounded and one not, so the **only** discrepancy CU-2 can
ever report is a presentation-scale rounding residue. **This was demonstrated, not merely argued:**
replacing the entire restatement with `Money restated = gcaBefore;` — deleting the present-value
calculation outright — drives the catch-up to nil and **leaves CU-1 and CU-2 both green**. A control
that survives the deletion of the arithmetic it exists to check is not a control, and no test can make
it fail, because its two inputs are the same expression. It cannot detect a wrong restatement, a wrong
revised vector, or a catch-up of any magnitude whatever. And its statement in `InvariantId` —
"catch-up = PV(revised, original EIR) − GCA before" — *is* the definition of the quantity, which is
exactly why it reads as coverage. CU-1 was vacuous on this run too: it compares the original EIR to the
persisted one, and both were 0.0115.

| Finding | Family |
|---|---|
| **CU-2 compares a quantity against its own definition**, so it reports satisfied through a 91% write-down | **control that cannot fail** — and unlike the earlier instances the id's own *statement* is the tautology, so reading the invariant list does not reveal it |
| Nothing compares the **revised** flow vector against the contract's remaining term, so a truncated vector is indistinguishable from a genuine final period | the "reconciles perfectly" family, one level below a dropped contract |
| A run stamped one of three policy kinds and closed without complaint, so a replay cannot resolve the fee rule set or the tier policy as then in force | control with no caller |
| Recognised interest of ₹1,070.38 reconciles to neither stored rate for a full period | **undiagnosed, and recorded as such** |

**The fourth is left open deliberately, for the reason the tier gate was.** The implied one-period rate
is 0.002025672 — roughly 0.18 of a period. Whether that is the accrual exponent over the restated
balance, the journal's composition, or an interaction with the ordinal correction above has not been
established, and adjusting a figure to make it reconcile would move the number an auditor reads.

**The fix CU-2 needed already existed and was never called.** `CatchUpCalculator.rollForwardRestated`
has claimed "TR-1 is asserted" in its javadoc since the class was written, and nothing in
`eir-application` ever called it — so the claim was true of the method and false of the engine. That is
the **fourth** control-with-no-caller in this programme. TR-1 is the second independent derivation:
`Discounting.presentValueMoney` sums discounted flows, `AmortisationEngine.eirLeg` iterates
`B_k = B_(k−1)·(1+r)^Δτ − CF_k` and asserts the terminal balance is nil. They agree only if both
arithmetics are right and both were handed the same rate, vector and convention. It is now folded into
`restate` itself rather than left to callers, because a control a caller must remember to invoke is a
control a caller forgets — which is the defect being fixed.

**What TR-1 buys, and what it does not, worked by hand rather than claimed.** The mutation CU-2 slept
through is now caught: `restated = gcaBefore` rolled over reference case 3's flows leaves a terminal
residue instead of nil. But **TR-1 passes on the 91% write-down**, and it must — 46,538.28 × 1.0115 −
47,073.47 is nil, because the restatement and the roll consume the *same* truncated vector and
therefore agree with each other. TR-1 detects an inconsistency **between two derivations**; it cannot
detect a wrong input to both. A test pins that boundary deliberately
(`terminalBalanceCannotCatchATruncatedVector`) so nobody later reads TR-1 as more than it is.

**And the fix reproduced the defect it was fixing, which is the most useful thing in this entry.** The
first version of its tests asserted that TR-1 was *present* and *satisfied* — and a mutation replacing
the whole terminal check with `List.of(InvariantResult.pass(InvariantId.TR_1, "vacuous"))` **left all
three green**. A vacuous pass under a real identifier, inside the change that existed to end vacuous
passes. The tests now assert that TR-1's detail carries the roll's own period count ("after 18
periods", "after 1 periods"), which only the evaluator can produce after actually walking the vector.
Three mutations are now caught: the wiring removed, the check made vacuous, and the roll performed at
the wrong rate. **The standing mitigation earned its place again — the first mutation run is what
revealed the fix was worthless, not review and not the 1,211 tests that passed around it.**

**Bounding the magnitude is still not done, as a design constraint rather than an excuse.** Breaching
when the catch-up exceeds a material share of the carrying amount needs a **threshold**, and a
materiality threshold is a Board decision belonging in [10](10-decision-register.md) beside
`DEEP_DISCOUNT_ACCRETION_SHARE`. The naive alternative — refuse a revised vector shorter than the
remaining term — would **false-refuse every legitimate modification that shortens a schedule**, which
is a large share of real reschedules, and this document already records three controls argued back from
exactly that over-strictness.

**And every one of the five earlier fixes is mutation-verified.** Reverting each guard fails the test that claims
it; the headroom tie-break fails two different tests under two different mutations. That matters
because the fourth of them was, on the first attempt, a control that could not fail: the branch where
the route seam cannot enumerate is unreachable over a socket, so the first mutation run passed and
proved the test worthless. It has its own test now. Nineteen defects of this family have been found in
this programme, and the standing mitigation — mutate the implementation to prove the test can fail at
all — caught one more here, in a fix for a defect of exactly the same shape.

**A fourth family, and the one this pair is really about: an unwired control is wrong in whichever
direction nobody checked.** The first finding is the mirror image of everything above it — not a
control that could not fail, but one that could not *pass*. `PeriodCloseGate` and
`ReconciliationScope` were both correct and both well tested, and together they made a close
impossible; a gate whose refusals nobody has to satisfy has no pressure on it in either direction.
The lesson is not that the gate was wrong. It is that "live code with no caller", recorded honestly
as a qualification in [08 § Phase 5](08-roadmap.md), was itself the defect, and the tests that
found it could only be written once something called it.

The second finding is the two older families arriving together, and its remedy is the same one the
four-eyes comparison got: `InvariantResult.idsWithoutEvidence` states the rule once and both runs
call it. Note what was *not* wrong — `RunAggregate` already refused a close where contracts computed
and no invariant at all was asserted. The check was all-or-nothing, and the failure it missed was
per-id. A control can be present, correct, and one granularity too coarse.

**Two families, and both were already in this document.** "A computed control nobody asserts is
indistinguishable from one that does not exist, and a control asserting something other than its own
statement is worse — it reads as coverage" was written for `S3-1`. "One rule stated in two places,
where only one place has it" was written for the four-eyes comparison. Phase 5 supplies eight more
instances, which is enough to stop treating either as a surprise.

The third family is new and worth naming: **the guard was always on the input that could only make a
figure worse, never on the input that could make it better.** RC-1 refused a duplicate engine
figure and a duplicate explanation was free; both figure sides were period-checked and the
explanation was not. An input that *reduces* a deviation deserves the stricter guard, not the
looser one, and in both cases it had none.

**What that says about the method.** A unit's own tests are written by whoever holds its
assumptions, so they verify the implementation against the intent and cannot see a defect in the
intent. Two mitigations are now standard here: an adversarial reader per unit who must construct a
failing input for each published invariant, and **mutating the implementation to confirm the test
can fail at all**. The second caught nothing in Phase 5 — every fix's test failed as designed under
mutation — which is the outcome that makes it worth continuing to run.

A third is now standard, from the `eir-application` pair: **wire a control to a caller and assert
the caller's outcome, not only the control's.** Neither finding above is reachable from a unit test
of the control itself, because both are statements about what a control does *for whoever invokes
it*. The strongest single piece of evidence produced in that work was a mutation, not a test:
breaking `idsWithoutEvidence` fails tests in three separate packages, which is what "one rule, one
place" looks like from the outside once it is true.

**On PC-1's arity.** A projection can be screened along more than one route at once: the
classified-fee route always applies, and `LMS_AUTHORITATIVE` adds the billed-schedule attestation on
top. Those are not two invariants. Emitted separately they produced two PC-1 results for one period
and, on an unattested LMS feed, two *contradictory* ones — the fee route passing because the rule set
had resolved every posting, the schedule route failing because nothing attested to the feed. The
conjunction was still enforced, so the breach was not lost, but anything reading PC-1 by name got
whichever answer came first in the list. PC-1 is the control an ACPIR auditor asks for by name: it
yields one result per period, satisfied only if every applicable route is, with the detail carrying
each route's evidence. A pass on the fee route alone means something quite different from a pass on
both, so the evidence is concatenated rather than replaced.

**On INV-2's baseline.** "Equals it when there is none" cannot be read as exact equality. A borrower
is billed an instalment rounded to the paise, so a zero-fee loan does not reprice exactly at its
coupon: on 1,000,000 at 1% a month the residual spread is −5.3e-8 over 24 months, +5.1e-8 over 60
and −2.1e-8 over 240. Read literally the invariant therefore fails on every zero-fee contract in
the book, and a control that fires on a whole legitimate population teaches reviewers to dismiss it.

The wrong fix is a tolerance band on the *spread*, and it was the first one this engine shipped:
1e-6 p.a., chosen as nineteen times the largest artefact above. A rate band is the wrong instrument
twice over. It varies three hundredfold in economic terms across the book — 1e-6 p.a. is worth 0.018
INR on a seven-day drawing and 5.68 at 240 months, per million — so one constant cannot be both
tight enough to catch a small fee on a long tenor and loose enough to pass a rounded rental on a
short one. And it treats a *knowable* number as noise: the artefact is not random, it is the
schedule's own **par gap**, and it can be measured.

So the baseline is the par gap, not the coupon:

```
G  =  P  −  PV(billed flows @ contractual rate)          the par gap
INV-2:   sign(EIR − contractual)  =  sign(F − G)          F = net integral fee
```

`G` is zero for a schedule that does price to par at its coupon, the paise residue for one whose
rental was rounded, and materially non-zero for a broken first period or a deferred-interest
schedule — where the old formulation was not merely imprecise but *inverted*. `G` is not solved for:
it falls out of the contractual leg already computed, as the terminal balance discounted back over
the schedule's accumulated tau. On the broken-period fixture that is a terminal 7,912.0641 over
2.052055 years, giving **G = 6,192.6630**, which agrees to eight decimal places with a direct
`P − PV` — and it makes a *negative* spread the arithmetically correct answer there, since 5,000 of
fee against a 6,192.66 gap is `F − G = −1,192.66`. Reusing the leg is what makes the correction
affordable: one fractional power per contract, against a full solve — two to three orders of
magnitude on a typical retail schedule, and the reason the invariant is not simply restated as a
comparison against the contractual-only yield.

What survives as a band is a **money** band of one paisa, and it now bounds only the arithmetic
noise in computing `G` itself — about 1e-6 rupees from the 12dp stored rate. One paisa sits ten
thousand times above that and five hundred thousand times below reference case 1's 5,000 of fee net
of gap, and unlike a rate band it means the same thing at every tenor. Inside it the ordering
genuinely carries no information; outside it, the ordering is resolved rather than excused.

Subtracting `G` costs one thing worth naming: the gap itself stops being visible to this check. A
schedule the entity believes prices at par and that misses it by thousands now passes INV-2 on
arithmetic that is entirely correct, where the old formulation caught it by accident — as one of the
false breaches this correction removes. That control is replaced rather than dropped, by **ST-13**,
which asks whether the structure implies par pricing before judging the gap. The lease fixture is the illustration: a nil fee against a
par gap of −0.09 must yield a rate *above* the coupon, and the +6.02e-8 spread that the rate band
once dismissed as unresolvable is now the answer the check predicts. The decision, its cost and the
alternatives rejected are in [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md).

Structure-specific invariants ST-3 … ST-13 extend this table and are stated in
[09 § 7](09-cashflow-structures.md#7-structure-specific-invariants). ST-9 in particular constrains
this section: a behavioural or option re-estimation on an instrument with a nil unamortised
premium or discount must produce a catch-up of exactly zero, which is why catch-up processing keys
on that balance rather than on an assumption having moved.

INV-4 is the workhorse. The unamortised fee balance is not an independent accumulator — it is
*defined* as the difference between the two legs, so it cannot drift from them. Case 1 at month 12:
529,815.61 − 528,407.32 = **1,408.29**, the same figure Case 2 accelerates on prepayment and Case 8
eliminates via the B5.4.4 shortcut.

PC-1 and ST-2 are the two India-specific invariants. Neither has an IFRS 9 analogue, and both are
audit-facing.

---

## 10. The materiality tier gate

Estimation risk distributes by **tenor**, not by product. Roughly 80% of EIR estimation risk in an
Indian universal bank sits in five long-tenor families: individual housing loans, project and
infrastructure finance, securitisation notes, long-dated HTM SDLs and corporate bonds, and the
credit card book. Engineering a full solver for products that do not need one is waste; applying an
undocumented shortcut to products that do is the Cambodia failure mode.

The engine assigns every contract to a tier, and the tier selects the computation strategy.

| Tier | Population | Treatment | Control |
|---|---|---|---|
| **Tier 1** — instrument level | Wholesale above a Board threshold · all project finance · all POCI · all restructured or modified · any contingent rate feature (ratchet, ESG, step-up) · any exposure in a hedge relationship | Full EIR per instrument, tightened solver tolerance. Routing applied per event. Re-performed on every contractual change. | Individually documented expected-life and contingency assumptions. ACPIR 57 sub-committee review where individually significant. |
| **Tier 2** — pool level | Retail and MSME with original tenor > 12 months — housing, LAP, auto, personal, consumer durable, education, MSME term, gold rollovers, CV/CE · credit cards · KCC revolvers | Pool EIR under the ACPIR 51 group presumption. Behavioural curves by product, vintage, segment. Re-estimation on a defined calendar. | Pooling criteria versioned and validated. Homogeneity reviewed per ACPIR 66–68. |
| **Tier 3** — documented approximation | Original tenor ≤ 12 months — WCDL, bills, packing credit, post-shipment, temporary OD, T-bills, CP, CD, call/notice money, TREPS, market repo · plus fully collateralised low-fee products | EIR = contractual rate plus straight-line accretion of net fees over tenor. | **Permitted only against a current equivalence test.** Invariant TG-1. |

### 10.1 Pool-level EIR (Tier 2)

ACPIR 51 expressly presumes that cash flows and expected life of a *group* of similar instruments
can be estimated reliably, so pool-level EIR is permitted and is the only tractable answer for
retail. Requirements:

- Pool definition is a versioned, approved artefact with explicit homogeneity criteria.
- Pools are **closed cohorts** — typically product × origination month × rate band × tenor band.
  Members are never added after the pool's EIR is struck; a later origination joins a later pool.
- **Mandatory quarterly back-test** against contract-level computation on a statistical sample,
  with a materiality threshold that, when breached, forces the pool to contract-level measurement.
- A contract leaving the pool early — prepayment, restructuring, Stage 3 entry — is measured at
  contract level from that point, at its own EIR solved on exit.

### 10.2 The equivalence test (Tier 3)

The shortcut is proportionate but it must be *evidenced*. "We approximated because it was
immaterial" is a complete answer only when the materiality assessment exists on paper with a number
attached. Required per Tier 3 population:

1. Solved-versus-approximated comparison on a representative sample.
2. The delta, documented, against a Board-approved threshold.
3. Annual re-performance (invariant TG-1). An out-of-date test demotes the population to Tier 2.

### 10.3 Where approximation is never permitted

Zero-coupon and deep-discount instruments, at any tenor. The entire return is accretion and the
straight-line error compounds with tenor.
[Reference Case 9](reference-cases/case-09-straight-line-vs-eir.md), a 15-year zero-coupon at 8%:

| | Year 1 | Year 10 | Year 15 | Total |
|---|---:|---:|---:|---:|
| Straight-line | 45,650.55 | 45,650.55 | 45,650.55 | 684,758.30 |
| EIR | 25,219.34 | 50,413.57 | 74,074.07 | 684,758.30 |
| Straight-line overstates | +20,431.22 | −4,763.02 | −28,423.52 | 0.00 |

Straight-line **overstates year-one income by 81.0%** and understates the final year by 38.4%. The
totals tie exactly, which is precisely what makes the error invisible over the life of the
instrument and glaring in any single period. This is why selective EIR adoption on the investment
book is indefensible, and it is the wedge for a full migration.

---

## 11. Liabilities

ACPIR 20 addresses financial assets, and the ACPIR 6(6) EIR definition references only the gross
carrying amount of an *asset*. Deposits, bond issuances, Tier 2 and refinance lines are therefore
outside ACPIR's EIR scope, while IFRS 9 applies the effective interest method symmetrically.

The engine **supports liability-side EIR** and treats the election as an entity-level policy
decision that must be taken explicitly, because an asset-only EIR produces an internally
inconsistent net interest margin. Mechanically the method is mirrored: issue costs — arranger,
rating, listing, trustee, stamp duty — reduce the initial carrying amount and amortise through
finance cost, lifting effective cost above the coupon. Call options drive expected life to first
call.

Two asymmetries encoded:

- The **10% test is authoritative** for liabilities (B3.3.6), not merely evidential as for assets.
- There is no staging and no allowance, so no Stage 3 suppression. The gross basis always applies.

Deposit brokerage paid to mobilisation agents is a transaction cost; premature-withdrawal options
are contractual terms feeding expected life.

**Structured deposits** are the one place bifurcation applies: on the liability side the embedded
derivative separates to FVTPL and the **host retains an EIR** — which is the bank's true cost of
funds on the product, not the advertised return. On the asset side there is no bifurcation: an
embedded feature that breaks SPPI sends the *entire* instrument to FVTPL and no EIR arises at all.
That asymmetry is why the SPPI screen is a gate *before* any EIR work is commissioned
([05 § classification gate](05-architecture.md)).

---

## 12. Hedge accounting interaction

ACPIR is silent on hedge accounting entirely. Where a fixed-rate instrument at amortised cost is
the hedged item in a fair value hedge, the basis adjustment to its carrying amount **must be
amortised to P&L as an adjustment to the effective interest rate** (IFRS 9 6.5.10, adopted by
election). This is a direct EIR mechanic, not a peripheral one.

Engine obligations:

- Track the basis adjustment separately from the EIR-derived carrying amount, restricted to the
  **designated risk component only** (6.5.8(a)) — benchmark rate risk, not credit spread.
  Over-adjusting to the full fair value change embeds an error that compounds across periods.
- Amortisation may be deferred while the hedge runs, but must begin no later than when the hedged
  item ceases to be adjusted for hedging gains and losses. **Invariant HB-1** makes a discontinued
  hedge without an active amortisation schedule a control failure — the "frozen adjustment" is the
  most commonly observed defect in this area and it recurs indefinitely once established.
- Hedging costs are **financing costs**, excluded from transaction costs by ACPIR 53. They never
  enter the EIR of the underlying. **Invariant HB-2** asserts this positively, because business
  teams think in all-in hedged cost and some finance systems let them book it that way.
- **Portfolio fair value hedges** of interest rate risk may use the IAS 39 89–94 carve-out
  preserved by IFRS 9 5.2.3 / 6.1.3. The basis adjustment then sits at portfolio level and
  amortises across the portfolio — a separate ledger and a separate reconciliation from the
  instrument-level EIR.

**The three-way case** — a hedged asset modified while its basis adjustment is amortising — is the
hardest computation in the system and is Tier 1 by definition:

```
new GCA        = PV(modified contractual flows, EIR adjusted for hedge accounting effects)
modification   = new GCA - old GCA including any hedge adjustments
hedge adj      = PV(hedged portion, benchmark at inception)
                   - PV(hedged portion, current benchmark)   -> ineffectiveness to P&L
```

A modification substantial enough to derecognise generally requires the hedging relationship to be
discontinued. This needs a documented position **before** the first restructuring inside a hedged
relationship, not after.

---

## 13. Worked examples

Full derivations, every intermediate figure, in [reference-cases](reference-cases/README.md):

| Case | Demonstrates | Key figure |
|---|---|---|
| [1](reference-cases/case-01-emi-loan-with-fees.md) | Fixed-rate EMI with integral fees | EIR 13.248094% vs contractual 12.682503% p.a. |
| [2](reference-cases/case-02-full-prepayment.md) | Full prepayment, accelerated fee | 1,408.29 to P&L at month 12 |
| [3](reference-cases/case-03-b546-reestimation.md) | Re-estimation catch-up | −627.42 charge, EIR unchanged |
| [4](reference-cases/case-04-floating-rate-reset.md) | Prospective reset | EIR → 14.375386% p.a., nil catch-up |
| [5](reference-cases/case-05-stage-3-acpir-suppression.md) | **ACPIR Stage 3 suppression** | 0.00 recognised; 3,304.08 + 2,202.72 = 5,506.79 |
| [6](reference-cases/case-06-poci-credit-adjusted-eir.md) | POCI credit-adjusted EIR | 29.141905% vs 64.703664% naive |
| [7](reference-cases/case-07-solver-stress.md) | Solver edge instruments | Zero-coupon recovers 1.000000000000% |
| [8](reference-cases/case-08-b544-repricing-shortcut.md) | **B5.4.4 next-repricing shortcut** | Year-1 fee 5,000.00 vs 3,591.71 |
| [9](reference-cases/case-09-straight-line-vs-eir.md) | **Straight-line error** | +81.0% year-1 overstatement |

Cases 3 and 4 are the same instrument at the same date under different **drivers**, and they are
the pair to reach for when explaining the routing table. Case 5 is the pair to reach for when
explaining why this is an Indian product and not an IFRS 9 one.
