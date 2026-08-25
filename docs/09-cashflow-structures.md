# 09 — Cash-Flow Structures

Normative extension of [03 § 3](03-calculation-spec.md#3-cash-flow-projection). Where this
document and 03 disagree, 03 governs; where either and a fixture in
[§ 6](#6-golden-fixtures) disagree, the fixture governs.

---

## 1. Why a shape enum does not survive contact with the book

Phase 1 shipped a flat `ScheduleShape` enum — `ANNUITY_EMI`, `BULLET`, `BALLOON`,
`STEP_UP` and so on — and one projector per shape. That is the right first cut and it is
also a dead end, for one reason: **real products are combinations, and a flat enum cannot
express a combination.**

From the [38-product matrix](reference/acpir-2026-eir-application-reference.md):

| Product | What it actually is |
|---|---|
| Education loan | course-plus-grace moratorium **+** interest capitalisation **+** annuity thereafter |
| Project finance | tranched disbursement **+** IDC capitalisation pre-COD **+** sculpted principal **+** possible DCCO deferment |
| Housing loan | annuity **+** floating EBLR resets **+** prepayment option **+** CPR curve **+** possible B5.4.4 election |
| CV / equipment finance | balloon **+** residual value **+** net integral cost |
| Callable corporate bond | bullet **+** call schedule **+** an expected-life *policy* |
| Perpetual (SPPI-passing) | no maturity **+** first call **+** earliest-call amortisation |
| KCC | revolving **+** crop-cycle seasonality **+** behavioural life |
| Restructured exposure with FITL | modified ladder **+** a *new* instrument carved from capitalised interest |

Enumerating combinations gives a combinatorial explosion; each new dimension multiplies the
enum rather than adding to it. Worse, `ContractTerms` had already started down the flat-record
road — `moratoriumPeriods`, `balloonAmount`, `stepFactor`, `residualValue` all sitting as
optional fields that most shapes ignore. That pattern degrades fast.

**The decomposition.** These products vary along eight largely orthogonal dimensions. Compose
the dimensions and the 38 families fall out as presets rather than as classes.

> This supersedes nothing already built. `ScheduleShape` and the existing projectors remain
> as the thin preset layer; the blueprint is what they are presets *of*, and
> `BlueprintProjector` bridges to the existing `CashflowProjector` interface so no caller
> changes.

---

## 2. The eight dimensions

### 2.1 Disbursement profile

| Variant | Notes |
|---|---|
| `Single(date, amount)` | The ordinary case. |
| `Tranched(projected[], actual[])` | Milestone drawdowns. Produces **negative flows after t=0**, hence possible multiple IRR roots ([03 § 4.4](03-calculation-spec.md#44-multiple-roots)). Deviation past a cumulative tolerance is a `DISBURSEMENT_TIMING` event — re-estimate on cumulative drift, not on every drawdown, or the book churns for no informational gain. |
| `UtilisationDriven(limit, utilisationCurve)` | Revolvers. There is no drawdown schedule to project. |

### 2.2 Principal repayment profile

| Variant | Notes |
|---|---|
| `LevelAnnuity` | Instalment fixed; principal is the residual after interest. EMI/EQI/EHI. |
| `EqualPrincipal` | Principal straight-line, instalment declines. Corporate term loans ("EPI"). Fixture [S1](#s1). |
| `BulletAtMaturity` | Principal once, at the end. |
| `Balloon(terminal)` | Amortise to a terminal lump. Fixture [S2](#s2). |
| `Sculpted(ladder[])` | Explicit principal ladder — project finance sized to projected free cash flow. No formula reproduces it; it is supplied. |
| `StepLadder(factor, everyN, direction)` | Instalment steps on a contractual ladder. Fixture [S3](#s3). |
| `NoneUntilMaturity` | Zero-coupon. Everything capitalises. |
| `ResidualValue(rv)` | Lease / CV finance. Composes with `Balloon`. Fixture [O8](#o8). |

### 2.3 Interest servicing profile

This is the dimension most often collapsed into the moratorium, and they are not the same
thing. Servicing says *when interest is paid*; the moratorium says *when principal is*.

| Variant | Effect on the carrying amount |
|---|---|
| `ServicedEachPeriod` | Interest leaves as cash each period. |
| `CapitalisedEachPeriod` | Interest **compounds into** the gross carrying amount. Project IDC, education loans. Fixture [S5](#s5). |
| `DeferredSimple(settlementDate)` | Interest accrues **without compounding** and settles as a lump. Fixture [S6](#s6) — worth **34.6 bp less** than capitalising on the same loan. |
| `DiscountedUpfront` | Interest collected at inception: T-bills, CP, CD, bills discounted. The discount *is* the interest. |
| `ServicedThenCombined(n)` | Interest-only period, then annuity. |

### 2.4 Moratorium

`Moratorium(periods, kind, termEffect)`

- **kind** — `NONE`, `PRINCIPAL_ONLY` (interest serviced, fixture [S4](#s4)),
  `FULL_INTEREST_CAPITALISED`, `FULL_INTEREST_DEFERRED_SIMPLE`, `PARTIAL_SERVICING`.
- **termEffect** — `EXTEND_TERM`, `COMPRESS_REMAINING` (maturity held, instalments rise),
  `BALLOON_ARREARS` (arrears to a terminal lump).

> **ACPIR 9(6)(i) produces two separate consequences from one feature, and they must not be
> allowed to imply one another.** Interest becomes due only after the moratorium, so it is
> **not overdue in the interim** — that is a *staging* input. The capitalisation is an *EIR*
> input. A system that lets the non-overdue status suppress the accretion, or lets the
> accretion imply an overdue, is wrong in one of two different directions.

### 2.5 Rate profile

Every variant carries the `RateDriver` tag that a change to it emits. **This is the join
between projection and [routing](adr/0006-configurable-event-routing.md)** — the projector
does not decide reset-versus-catch-up, it supplies the tag that the routing table maps.

| Variant | Driver emitted on change |
|---|---|
| `Fixed(rate)` | — (a renegotiation is `NEGOTIATED`) |
| `Floating(benchmark, spreadBps, resetSchedule)` | `TIME_VALUE_OF_MONEY` |
| `FloatingWithCollar(base, cap, floor)` | `TIME_VALUE_OF_MONEY`; the collar itself is a contractual term, not a driver |
| `StepCoupon(ladder[])` | `STEP_UP_PREDETERMINED` |
| `CreditRatchet(tiers[], trigger)` | `CREDIT_RATCHET_PREDETERMINED` |
| `EsgRatchet(tiers[], kpi)` | `ESG_LINKED` |
| `InflationIndexed(indexId)` | `TIME_VALUE_OF_MONEY` |
| `MarketSpreadReset(schedule)` | `CREDIT_RISK_MARKET` |

A collar deserves a note: a cap or floor changes the *cash flows* but is not itself a
re-estimation event. Where the benchmark moves and the cap binds, the driver is still
`TIME_VALUE_OF_MONEY` and the cap is simply part of the projection.

### 2.6 Optionality — see [§ 3](#3-optionality-and-expected-life)

### 2.7 Behavioural overlay

| Variant | Notes |
|---|---|
| `Contractual` | Expected equals contractual. **Recorded as a policy choice, not an absence of assumption** — the two produce identical numbers and very different audit outcomes. |
| `ConstantPrepaymentRate(annualCpr)` | Fixture [O6](#o6). |
| `CprVector(byPeriod[])` | Vintage/segment curves. |
| `RolloverAssumption(expectedRollovers, probability)` | WCDL, gold loan. Feeds the ACPIR 46(2)(ii) substantive-renewal test. |
| `RevolverBehaviour(lifeMonths, utilisationCurve)` | Cards, CC/OD, KCC. ACPIR 46(2)(iii) mandates the supporting analysis. |

### 2.8 Schedule calendar

`ScheduleCalendar(frequency, businessDayConvention, holidays, endOfMonthRule, customDueDates)`

- **frequency** — `MONTHLY`, `QUARTERLY`, `HALF_YEARLY`, `ANNUAL`, `WEEKLY`, `FORTNIGHTLY`,
  `SEASONAL`, `CUSTOM`.
- **businessDayConvention** — `NONE`, `FOLLOWING`, `MODIFIED_FOLLOWING`, `PRECEDING`,
  `MODIFIED_PRECEDING`.
- **`SEASONAL`** is not a nicety. KCC and agricultural term loans have due dates aligned to
  harvest, so the periods are genuinely unequal and
  [`periodicIndexEligible`](03-calculation-spec.md#310-two-conventions-for-time) must return
  false — actual-date discounting is mandatory. A monthly-index approximation on a crop-cycle
  loan is simply a wrong rate.

Any business-day adjustment moves a due date and therefore voids periodic-index eligibility.
The calendar is consequently an input to convention selection, not a presentational detail.

---

## 3. Optionality and expected life

The dimension with the most judgement in it, and the one where ACPIR and IFRS 9 **diverge in a
way that is exactly measurable rather than arguable**.

### 3.1 The option schedule

`EmbeddedOption(type, holder, firstExercise, lastExercise, frequency, strikePctOfPar, noticePeriod, makeWhole)`

- **type** — `CALL`, `PUT`, `EXTENSION`, `PREPAYMENT`, `CLEAN_UP_CALL`, `CONVERSION`.
- **holder** — `ISSUER_BORROWER`, `HOLDER_LENDER`, `EITHER`.

**Holder identity is a required field, not decoration.** A put held by the bank is the bank's
own exercise judgement. A call held by the issuer requires the bank to model *someone else's*
rational behaviour, which is a different estimation problem with different evidence
requirements. Fixture [O4](#o4).

`CONVERSION` is present only to be **rejected**: a conversion feature on an asset almost always
fails SPPI, which sends the whole instrument to FVTPL where **no EIR arises at all**. That is a
cliff, not a gradient, and the classification gate must run before any EIR work is commissioned
([03 § 11](03-calculation-spec.md#11-liabilities)).

### 3.2 The exercise policy — where the divergence lives

Expected life is not derived; it is **chosen by policy and recorded**.

| Policy | Basis |
|---|---|
| `CONTRACTUAL_MATURITY` | Ignore options. The RBI Investment Directions reading for callable and puttable securities — amortise over residual *contractual* maturity. |
| `EARLIEST_CALL` | The RBI FAQ reading for perpetual debt. Fixture [O3](#o3). |
| `MOST_LIKELY_OUTCOME` | IFRS 9. A binary management judgement. Suited to large single-name facilities with bespoke features; poorly suited to a collective estimate. |
| `PROBABILITY_WEIGHTED` | IFRS 9. Expected value across exercise outcomes. The right method for homogeneous retail pools. |
| `NEXT_REPRICING` | The B5.4.4 shortcut ([Case 8](reference-cases/case-08-b544-repricing-shortcut.md)). |
| `ECONOMIC_RATIONALITY` | Exercise where in the money past a threshold. Model-driven, and a model under ACPIR Chapter V. |

**The engine computes the alternatives and reports the difference.** The domain reference calls
the callable-security divergence "one of the few that can be measured exactly rather than
argued — compute both and disclose the difference", so
`ExpectedLifeDetermination` carries the chosen life, the policy, every alternative computed, and
the quantified divergence in both rate and first-period income.

Fixture [O1](#o1) — a 9% ten-year bond bought at a **premium**, callable at par at year 5:

| Policy | Life | EIR p.a. | Year-1 income |
|---|---|---:|---:|
| `CONTRACTUAL_MATURITY` | 10y | 8.246545% | 86,588.72 |
| `EARLIEST_CALL` | 5y | 7.755768% | 81,435.57 |
| | | **49.1 bp** | **5,153.16** |

Fixture [O2](#o2) — the **same bond at a discount**, and the divergence **reverses**:

| Policy | Life | EIR p.a. | Year-1 income |
|---|---|---:|---:|
| `CONTRACTUAL_MATURITY` | 10y | 9.806992% | 93,166.43 |
| `EARLIEST_CALL` | 5y | 10.330130% | 98,136.23 |
| | | **52.3 bp** | **4,969.81** |

A premium amortised over five years instead of ten is expensed about twice as fast, so the call
basis reports **less**. A discount accreted over five instead of ten recognises income **faster**,
so the call basis reports **more**. **The sign of the divergence depends on whether the
instrument was bought above or below par**, which means no blanket policy can be assumed
conservative and the choice cannot be made once, globally, on prudence grounds. It has to be
made per portfolio, with the number in front of the committee.

### 3.3 Two lives, again

`EXTENSION` makes the [two-lives rule](03-calculation-spec.md#35-two-definitions-of-life)
concrete. Fixture [O5](#o5): a 5-year bond extendable by 3.

- **ECL horizon** (ACPIR 46(1)) — the *maximum contractual* period including extension: **8y**.
- **EIR expected life** (ACPIR 51) — what is expected: possibly **5y**.

Two different numbers from one contractual feature, 37.0 bp apart. This is why the data model
carries them as separate fields with a mandatory divergence basis, and why neither may be
derived from the other.

### 3.4 The par test — when optionality and behaviour are P&L events at all

Fixture [O7](#o7), and the most useful single result in this document.

A securitisation note, pool coupon 10%, CPR assumption revised 10% → 20% at month 24:

| Purchase price | GCA at m24 | Unamortised at m24 | Catch-up |
|---|---:|---:|---:|
| 1,030,000 (premium) | 493,520.53 | 7,688.32 | **−876.39** loss |
| 970,000 (discount) | 477,984.62 | −7,847.59 | **+878.89** gain |
| 1,000,000 (**par**) | 485,832.22 | 0.0089 | **−0.01** |

Pool balance at m24 is 485,832.21 in all three cases; only the carrying amount differs.

At par the catch-up is nil, and correctly so: with no premium or discount there is nothing for a
change in prepayment speed to accelerate, because the revised flows always discount to the
outstanding balance at the contractual rate.

> **Why "nil" and not "0.00", and why these figures moved.** This table previously read
> 7,688.30 / −7,847.60 / −876.38 / +878.90 and **0.00** at par, computed on an *unrounded*
> schedule. A pool bills cash: the level payment is 21,247.04, not the exact annuity
> 21,247.0447110715, and every flow is paid to the paisa — which is what
> `FlowVectorAssembler` emits. On that basis the 47 billed flows price to 999,999.9973 rather
> than to par, the par note solves to 0.008333333204 rather than the contractual
> 0.008333333333, and **0.0089 of instalment-rounding residue** shows up as "unamortised" at
> month 24.
>
> So the par row's catch-up is −0.01, not 0.00. The economics of §3.4 are untouched — at par
> there is nothing to accelerate — but "exactly zero" is the limit a schedule reaches only if
> a borrower can be billed fractions of a paisa. What the engine asserts (invariant ST-9) is
> that a catch-up at par is nil **to within the schedule's own instalment-rounding residue**,
> and that bound is measured rather than assumed: `0.005 × ((1+r)^n − 1)/r`, which is 0.13 over
> 24 periods and **3.34 over 240**. Comparing at presentation scale instead fixes the threshold
> at half a paisa for every tenor, and it made this very note — bought *at par* — read as held
> away from par, post a catch-up, and take ST-9's away-from-par branch where it asserts nothing.
>
> The premium row was additionally inconsistent with itself: 7,688.30 arises only on a fully
> unrounded basis, where the GCA would be 493,520.41 and the pool balance 485,832.10, both of
> which contradict the figures published beside it. The generator now bills the level payment
> *and* every flow, so all three rows reproduce on one basis — the engine's.

> **Design consequence.** A behavioural-assumption change is a P&L event **only for an
> instrument held away from par.** The engine must therefore key catch-up processing on the
> **unamortised premium/discount balance**, not on the fact that an assumption moved. Keying it
> on the assumption churns the entire par-priced book on every curve refresh, for no P&L effect
> — a correctness-neutral but ruinous performance mistake at 10M contracts, and one that also
> floods the movement schedule with nil-effect restatements.
>
> This is also why a floating-rate note bought at par raises no reset-versus-catch-up question
> at all, while the same note bought in the secondary market away from par raises the whole
> question.

---

## 4. The blueprint

```java
public record ScheduleBlueprint(
    Money notional, Currency currency,
    LocalDate valueDate, LocalDate statedMaturity,
    DisbursementProfile disbursement,
    PrincipalProfile principal,
    InterestServicing servicing,
    Moratorium moratorium,
    RateProfile rate,
    OptionSchedule options,
    BehaviouralOverlay behaviour,
    ScheduleCalendar calendar,
    DayCountConvention dayCount,
    ExercisePolicy exercisePolicy,
    ResiduePolicy residuePolicy) { }
```

Pipeline:

```
ScheduleBlueprint
   -> ScheduleBuilder          contractual InstalmentLadder
   -> OptionalityResolver      ExpectedLifeDetermination; truncate or extend
   -> BehaviouralAdjuster      CPR / rollover overlay -> expected ladder
   -> FlowVectorAssembler      contractual FlowVector + expected FlowVector
   -> ConventionSelector       checked periodic-index eligibility, else actual-date
   -> ProjectionResult         + IC-1 assertion
```

Each stage is independently testable, and each stage's output is a value. `BlueprintProjector`
implements the existing `CashflowProjector`, so the solver, amortisation engine and every
existing test are unaffected.

**Validity is checked, not assumed.** Not every combination is coherent —
`NoneUntilMaturity` principal with `ServicedEachPeriod` interest and a `PRINCIPAL_ONLY`
moratorium is contradictory. `ScheduleBlueprint` validates in its constructor and names the
conflict; an incoherent blueprint is a configuration error, not a runtime surprise.

---

## 5. Product templates

`ProductTemplates` maps the ACPIR product families to blueprints — the product matrix as
executable code rather than prose. A sample:

| Family | Blueprint |
|---|---|
| Retail housing, floating | `LevelAnnuity` + `ServicedEachPeriod` + `Floating(EBLR)` + `PREPAYMENT` option + `CprVector` + `NEXT_REPRICING` |
| Education loan | `LevelAnnuity` + `FULL_INTEREST_CAPITALISED` moratorium (course+grace) + `EXTEND_TERM` |
| Project finance | `Tranched` + `Sculpted` + `CapitalisedEachPeriod` pre-COD + `Floating` |
| CV / equipment | `Balloon` + `ResidualValue` + `Fixed` |
| Gold loan | `BulletAtMaturity` + `ServicedEachPeriod` + `RolloverAssumption` |
| KCC | `UtilisationDriven` + `SEASONAL` calendar + `RevolverBehaviour` |
| Credit card | `UtilisationDriven` + `RevolverBehaviour` + ACPIR 46(2)(iii) analysis |
| T-bill / CP / CD | `NoneUntilMaturity` + `DiscountedUpfront` + `ACT_365F` |
| Callable corporate bond | `BulletAtMaturity` + `CALL` schedule + explicit `ExercisePolicy` |
| Perpetual (SPPI-passing) | `BulletAtMaturity` at first call + `EARLIEST_CALL` |
| Securitisation PTC | `Sculpted` from pool + `CprVector` + away-from-par catch-up per § 3.4 |
| NCD issued | `BulletAtMaturity` + issue costs as integral + `CALL` to first call |

A template is a starting point that a contract may override, never a constraint. Templates are
versioned policy artefacts like everything else.

---

## 6. Golden fixtures

Computed independently of the implementation, as with the
[nine reference cases](reference-cases/README.md). Base loan throughout S1–S6: **1,000,000 at
12% nominal monthly (1.00% per period), 24 periods, net integral fee 5,000, initial carrying
amount 995,000**. Contractual effective rate 12.682503% p.a.

<a name="s1"></a>
### S1 — Equal principal (EPI)
Principal 41,666.67 per period; instalment 51,666.67 falling to 42,083.26; total interest
125,000.00. **EIR 1.04368895% /mo = 13.268805% p.a.** Period 1: opening 995,000.00, interest
10,384.71, closing 953,718.04. Terminal 0.

<a name="s2"></a>
### S2 — Balloon
Balloon 400,000 at period 24; PV of balloon 315,026.45; level instalment 32,244.08; final
period 432,244.08. **EIR 1.03207441% /mo = 13.112667% p.a.** Terminal 0.

<a name="s3"></a>
### S3 — Step-up, +10% every 6 periods
Base instalment 40,861.10 → 44,947.21 → 49,441.93 → 54,386.12. Total cash 1,137,818.16.
**EIR 1.03978106% /mo = 13.216248% p.a.** Terminal 0.

<a name="s4"></a>
### S4 — Principal moratorium, 6 periods interest-serviced
Interest serviced 10,000.00 for 6 periods, principal untouched; EMI 60,982.05 for periods 7–24.
**EIR 1.03481316% /mo = 13.149467% p.a.** Carrying amount *rises* to 996,824.99 by period 6 —
the fee is amortising while no principal repays. Terminal 0.

<a name="s5"></a>
### S5 — Full moratorium, interest capitalised (education loan / IDC)
12 periods nothing paid; balance grows to 1,126,825.03 (capitalised interest 126,825.03); then
24 EMIs of 53,043.57. **EIR 1.02108050% /mo = 12.965053% p.a.** EIR carrying at period 12:
1,124,002.28. Terminal 0.

<a name="s6"></a>
### S6 — Full moratorium, interest deferred simple
Simple accrual 120,000.00 versus compounded 126,825.03 — a 6,825.03 difference. Lump settled at
period 12, then 24 EMIs of 47,073.47. **EIR 0.99527906% /mo = 12.619315% p.a.**, which is
**34.6 bp below** the capitalising variant. Compounding versus simple deferral is a real
economic difference, not a presentational one.

<a name="o1"></a><a name="o2"></a>
### O1 / O2 — Callable bond, both directions
See [§ 3.2](#32-the-exercise-policy--where-the-divergence-lives). Face 1,000,000, 9% annual,
10y, callable at par year 5. Premium at 1,050,000: 49.1 bp, 5,153.16 of year-1 income.
Discount at 950,000: 52.3 bp the other way, 4,969.81.

<a name="o3"></a>
### O3 — Perpetual to first call
8% coupon, bought 1,020,000, first call year 5. **EIR to earliest call 7.505597% p.a.**;
running yield 7.843137% for contrast. Run the SPPI gate first — an AT1-style instrument with
discretionary coupons and loss absorption fails SPPI and carries no EIR at all.

<a name="o4"></a>
### O4 — Puttable bond
9% at 1,050,000, holder may put at par year 3. To put **7.091554%**, to maturity **8.246545%** —
115.5 bp apart.

<a name="o5"></a>
### O5 — Extension option
5y stated, extendable 3y. Stated **7.755768%**, extended **8.125769%** — 37.0 bp. ECL horizon 8y,
EIR expected life possibly 5y.

<a name="o6"></a>
### O6 — Prepayment via CPR
Mortgage 5,000,000 at 9% p.a., 240 months, net fee 50,000, EMI 44,986.30:

| CPR | Life (months) | Principal-WAL | EIR p.a. |
|---:|---:|---:|---:|
| 0% | 240 | 12.88y | 9.533867% |
| 8% | 116 | 4.90y | 9.674777% |
| 15% | 86 | 3.30y | 9.785190% |
| 25% | 64 | 2.24y | 9.945464% |

<a name="o7"></a>
### O7 — The par test
See [§ 3.4](#34-the-par-test--when-optionality-and-behaviour-are-pl-events-at-all).
Premium −876.39, discount +878.89, par −0.01 — nil to within the schedule's own
instalment-rounding residue, which is the claim ST-9 makes and the reason the screen's
threshold is measured rather than fixed at half a paisa.

<a name="o8"></a>
### O8 — Lease with residual value, and INV-2 in both directions
Asset 1,000,000 at 11% p.a., 36 months, RV 200,000. PV of RV 144,001.06; rental 28,024.31;
final period 228,024.31. Contractual effective 11.571884%.

The billed rental of 28,024.31 is the true annuity of 28,024.30702715317 rounded **up**, so this
schedule over-collects. Its par gap is `G = P − PV(billed @ contractual) = ` **−0.09** — which is
what makes the third row below interesting rather than trivial.

| Integral amount | GCA₀ | `F` | `F − G` | EIR p.a. | INV-2 |
|---|---:|---:|---:|---:|---|
| Fee **received** 12,000 | 988,000 | +12,000 | +12,000.09 | 12.377404% | EIR **above** contractual |
| Cost **paid** 12,000 | 1,012,000 | −12,000 | −11,999.91 | 10.784759% | EIR **below** contractual |
| None | 1,000,000 | 0 | **+0.09** | 11.571890% | EIR **above** contractual, by 6.02e-8 |

The third row does *not* say the rates are equal, and INV-2 does not claim they are. A nil fee
against a −0.09 gap must price above the coupon, and the +6.02e-8 spread is exactly the ordering
`sign(F − G)` predicts — nine times the one-paisa resolvable band, so the check resolves it rather
than abstaining. Round the rental **down** instead and every sign in that row flips, which is the
point: the residue is a property of the billed schedule, not noise to be tolerated. See
[03 § 9](03-calculation-spec.md#9-invariants) on why the baseline is the par gap and not the
annualised coupon, and [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md) for the decision.

The cheapest sign check in the engine, and it catches a whole class of fee-classification
errors.

---

## 7. Structure-specific invariants

Extending [03 § 9](03-calculation-spec.md#9-invariants). All asserted, all blocking — but not all
*fatal*, and the difference is deliberate. **ST-10 and ST-11 throw**: a calendar irregularity taken as
a period ordinal, or a blueprint whose dimensions contradict each other, is an engine or
configuration defect, nothing downstream can compensate for it, and the number it would produce is a
wrong rate rather than a flagged one. **The rest are returned as failed results**: they mark the
contract for the exception queue with its evidence and let the close proceed, because a batch of ten
million that aborts on one mis-keyed instalment is a worse control than one that reports it. ST-13 is
the clearest case — it fails on an input the engine was given, and ADR-0008 makes fixing the input
the only available response.

| ID | Invariant | Scope |
|---|---|---|
| ST-3 | Σ scheduled principal over the contractual ladder = principal advanced (net of any contractual write-down) | Every blueprint |
| ST-4 | Capitalised interest during a moratorium equals the compound accretion over the moratorium; deferred-simple equals the simple accrual, and the two differ | Moratorium |
| ST-5 | A balloon or residual-value ladder amortises to exactly the terminal amount, not to zero | Balloon, lease |
| ST-6 | Σ tranche disbursements = notional; each tranche dated on or after the value date | Tranched |
| ST-7 | `ExpectedLifeDetermination` records ≥ 2 policies whenever any option is present, with the divergence quantified | Optionality |
| ST-8 | Expected life ≤ ECL horizon, always | Every blueprint |
| ST-9 | A behavioural or option re-estimation on an instrument with a **nil** unamortised premium/discount produces a catch-up of exactly zero (§ 3.4) | Re-estimation |
| ST-10 | Any due date that moves off its raw anchor — by business-day convention, holiday, unequal-period calendar, or a month-end rule on a month-end value date — makes `periodicIndexEligible` false | Calendar |
| ST-11 | An incoherent blueprint is rejected at construction, naming the conflicting dimensions | Every blueprint |
| ST-12 | A `CONVERSION` option, or any SPPI failure, yields no EIR — the instrument is excluded from EIR processing entirely | Classification gate |
| ST-13 | A schedule whose structure implies par pricing prices to par at its own coupon, within the instalment-rounding residue | Post-projection |

ST-9 is the one that will be argued about, and it is the one that keeps the close window
survivable.

**ST-13 is the newest and the least obvious.** It exists because INV-2 stopped providing it. That
check used to compare the EIR against the annualised coupon, so it breached on any schedule away from
par — wrongly, on structures where nothing was wrong. Correcting its baseline to subtract the measured
par gap ([ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md)) removed the false breaches and, in
the same motion, removed the signal: `sign(F − G)` orders correctly whether `G` is 0.09 or 6,192.66,
so a schedule missing par by thousands now passes INV-2 on arithmetic that is entirely correct. Where
the structure says par is expected, that miss can only come from the terms the engine was given.

Its scope was set by measurement rather than by reasoning — holding every other dimension fixed on a
three-year monthly advance of a million and reading the gap off the projected contractual vector:

| Prices to par, to the paisa | Legitimately away from par | `G` |
|---|---|---:|
| every principal profile — annuity, equal principal, bullet, balloon, step ladder | interest deferred **simple** | +6,056.87 |
| every moratorium kind and term effect | **discounted upfront** | +301,075.05 |
| **every day-count convention** | a **step coupon**, 1% → 1.5% | −37,079.62 |
| every rate profile carrying one rate for life — floating, collared, ratchet, indexed, spread-reset | an **undrawn tranche** | +600,000.00 |
| every behavioural overlay | **actual dating**, exact whole months | +261.03 |

Three of those rows contradict what reasoning suggests, which is why they were measured. The **day
count is irrelevant and the convention is decisive**: ACT/365F and ACT/360 both hold par exactly,
because a uniform calendar licenses the periodic index and the day count never reaches the
discounting — yet under actual dating even a schedule spaced in exact whole months misses par by
261.03, since a monthly rate compounded over year fractions does not reproduce the annuity that sized
the instalments. **The whole floating family holds par**, collars and ratchets included, because each
projects at its current rate and the leg discounts at that same rate. And **behaviour cannot move the
gap at all** — the gap is a property of the contractual leg, and an overlay only reshapes the expected
one. The undrawn-tranche row is the cleanest: 400,000 advanced against a million of notional gives a
gap of exactly 600,000.00, the undrawn amount to the paisa.

The rate clause is stated as the *property* — one rate in force for every period — rather than as a
list of admissible profiles, so a step coupon carrying a single rung is admitted and a profile nobody
has written yet is judged on what it does. The band is the same measured
instalment-rounding bound ST-9 uses, and measured for the same reason: it is 0.13 over 24 periods,
0.22 over 36, 4.95 over 240 and 17.47 over 360, so a gap of one rupee is a defect on a three-year loan
and noise on a twenty-year one. No constant serves both ends of the book.

---

## 8. Implementation plan

| Step | Deliverable |
|---|---|
| 1 | The eight dimension types as sealed interfaces and records, with constructor validation |
| 2 | `ScheduleBlueprint` + coherence validation (ST-11) |
| 3 | `ScheduleBuilder` — blueprint to contractual `InstalmentLadder` |
| 4 | `OptionalityResolver` + `ExpectedLifeDetermination`, all six exercise policies, divergence quantified (ST-7) |
| 5 | `BehaviouralAdjuster` — CPR, rollover, revolver overlays |
| 6 | `FlowVectorAssembler` + `ConventionSelector` integration (ST-10) |
| 7 | `BlueprintProjector` bridging to `CashflowProjector`; existing projectors re-expressed as presets |
| 8 | `ProductTemplates` — the 38 families |
| 9 | Fixtures S1–S6 and O1–O8 as JUnit, from **this document's** figures |
| 10 | Property sweep extension: ST-3…ST-12 over generated blueprints |

Steps 1–3 are policy-agnostic and can proceed immediately. Step 4 needs the exercise-policy
positions ([reference § 9](reference/acpir-2026-eir-application-reference.md), items 7 and 13)
to be settled before the *defaults* are set, though not before the mechanism is built — the
same separation that let Phase 1 start ahead of the thirty-three policy positions.
