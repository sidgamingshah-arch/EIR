# ADR-0009 — INV-2 orders against the schedule's par gap, not the annualised coupon

**Status:** Accepted

## Context

INV-2 is the engine's cheapest control and the one that catches a whole class of fee-classification
errors for the price of a comparison: a net integral fee **received** records the asset below par, so
it must accrete back up and the EIR must exceed the contractual rate; a **cost paid** records it above
par and the EIR must fall below. Individually each rate is plausible. Only the ordering detects a
sign error in fee classification.

It was implemented as

```
sign(EIR_eff − contractualRate.effectiveAnnual())  ==  sign(netIntegralFee)
```

with a tolerance band of 1e-6 p.a. on the spread to absorb the paise-rounding artefact that stops a
zero-fee loan from repricing exactly at its coupon.

Both halves are wrong, and the second hides the first.

**`contractualRate.effectiveAnnual()` is not the schedule's own yield.** It annualises the quoted
periodic rate as though every period were a whole one. Two ordinary populations break that:

| Population | Par gap `G` | Effect |
|---|---:|---|
| Broken first period — disbursed on the 17th against a 5th-of-month due date, 49-day stub | **+6,192.66** | On 5,000 of fee received the EIR sits 12.8 bp *below* the naive 12.682503%. Sound contract, reported as a breach. |
| S6 — 12-period full holiday, interest deferred **simple** rather than capitalised | **+6,056.91** | Perfectly uniform periods, and the EIR is 6.3 bp below contractual on 5,000 of fee income. |

S6 is the instructive one: it is uniform, so uniformity was never the precondition the invariant
needed. **Par pricing was.** The comparison against the coupon is a proxy for comparing the carrying
amount to par, and the proxy holds only where the contractual flows themselves price to par.

**A rate band is the wrong instrument for the residue.** It varies three hundredfold in economic
terms across the book — 1e-6 p.a. is worth 0.018 INR on a seven-day drawing and 5.68 at 240 months,
per million — so no single constant is both tight enough to catch a small fee on a long tenor and
loose enough to pass a rounded rental on a short one. And it treats a *knowable* quantity as noise.

## Decision

**INV-2 subtracts the schedule's measured par gap from the fee, and orders on the difference.**

```
G  =  P  −  PV(billed flows at the contractual rate)          the par gap
INV-2:   sign(EIR_eff − coupon_eff)  =  sign(F − G)           F = net integral fee
```

`G` is **not solved for.** It is read off the contractual leg the reconciliation already computed:
that leg rolls forward at the coupon, so its terminal balance is whatever the billed instalments
failed to collect, and discounting that residue back over the schedule's accumulated tau recovers the
shortfall at inception. One fractional power, no iteration —
`TwoLegResult.parGap(contractualLeg, contractualRate)`.

The residual tolerance becomes a **money** band: `ORDERING_EPSILON = INR 0.01`. It no longer stands in
for the rounding residue — that is now measured and subtracted — so it bounds only the arithmetic
noise in computing `G` itself, about 1e-6 rupees from the 12dp stored rate.

## Rationale

**The identity is exact, not approximate.** The fee enters only the solve target, so the with-fee and
without-fee problems are the same function of `r` differing by a constant. Wherever PV is monotone
between the two rates, `sign(EIR_eff − coupon_eff) = sign(F − G)` follows. The old check was that
identity with `G` silently assumed to be zero.

**It is verified two independent ways.** On the broken-period fixture the terminal-balance derivation
gives 7,912.0641 over tau 2.052055 → **6,192.6630**, agreeing to eight decimal places with a direct
`P − PV`. On S6 the gap comes from the fixture's own independent price check — 1,000,000 less
993,943.09 — and INV-2 passes with it and fails without it. Neither expected value is produced by the
routine under test.

**It converts a hidden pass into a resolved one.** The lease fixture bills a rental rounded *up*, so
`G = −0.09` and a nil fee must price *above* the coupon. The +6.02e-8 spread that the rate band
dismissed as unresolvable is now the answer the check predicts. Round the rental down instead and
every sign flips — which is the point: the residue is a property of the billed schedule.

**The money band does not vary with tenor.** One paisa sits ten thousand times above the arithmetic
noise in computing `G` (about 1e-6 rupees) and five hundred thousand times below reference case 1's
signal (`F − G` of 5,000). It means the same thing on a seven-day drawing as at 240 months, which is
exactly what the rate band it replaces could not do: 1e-6 p.a. was worth 0.018 INR on the former and
5.68 on the latter, per million advanced.

## Consequences

- `InvariantChecks.feeSignOrdering` takes a fourth argument. There is no three-argument overload
  defaulting `G` to zero: that default is the defect, and leaving it reachable would let a caller
  reintroduce it silently. Every call site states its gap.
- `ORDERING_EPSILON` changes type from `BigDecimal` (a rate) to `Money`.
- `TwoLegResult.parGap` is public, so callers outside the reconciliation — the blueprint pipeline,
  which builds no `TwoLegResult` — can obtain the same figure the same way.
- The blueprint pipeline can now safely assert INV-2 on a `DeferredSimple` structure. Before this it
  could not: wiring a two-leg reconciliation to S6 would have produced a *blocking* breach on an
  instrument where every figure was correct.
- **A control is lost and has to be replaced.** A large `G` on a schedule the entity believes prices
  at par is a data error, and INV-2 no longer notices it — it subtracts it and passes. INV-2 used to
  catch that by accident, as the false-breach behaviour this ADR removes. The replacement is **ST-13**
  ([09 § 7](../09-cashflow-structures.md#7-structure-specific-invariants)), which asks first whether
  the structure implies par pricing and only then judges the gap. It is a statement about the input
  schedule rather than about the solved rate, which is why it is a separate control and not a fourth
  limb of this one.

## Cost

The decision rests on a ratio, not on an absolute: obtaining `G` from the leg already computed costs
**one fractional power**, and solving for the contractual-only yield costs a full bracketed-Newton
run — on this solver, iterations × periods fractional powers, since every residual evaluation
discounts the whole vector. At `big-math`'s 28-digit precision the fractional power dominates both,
so the ratio is roughly the iteration-times-period count: two to three orders of magnitude on a
typical retail schedule.

Design-time sizing put that at **0.2 core-hours** per 10M-contract close for the chosen approach
against **922** for a second solve. Those two figures are an estimate, not a measurement on this
codebase — they are recorded because the *order of magnitude* is what the decision turns on, and it
is not close. A benchmark belongs alongside the batch-sizing work in
[08](../08-roadmap.md), where a close is actually timed end to end.

Reusing the leg is what makes the correction affordable at book scale, and the two-way verification
above is what licenses the reuse.

## Alternatives rejected

**Widen the rate band.** On the broken-period fixture `F − G` is over a hundred thousand times the
resolvable band in money terms. No widening absorbs that without also swallowing reference case 1's
56.6 bp signal, which is the smallest thing the check exists to catch.

**Make the band tenor-dependent.** Fixes the three-hundredfold variation and leaves the baseline
wrong, so both S6 and the broken-period fixture still breach. It also puts a second policy dial next
to the one being corrected.

**Solve for the contractual-only yield and compare against that.** Economically the cleanest reading —
"the fee moves the yield away from what the same billed schedule yields on its own" — and it is
already asserted as a property in `RateOrderingPropertiesTest`. Rejected as the *invariant* on cost:
a full solve per contract per close, for a control that a single fractional power answers.

**Restrict INV-2 to par-priced schedules.** Turns a portfolio-wide control into one that is silently
absent exactly where fee misclassification is hardest to spot by eye.

**Drop INV-2.** It is the only control that catches a fee-classification sign error from the published
figures alone, and both directions of it are reference-cased (O8).
