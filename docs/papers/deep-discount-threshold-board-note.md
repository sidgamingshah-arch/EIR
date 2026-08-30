# Board note — the deep-discount threshold for FR-412

**For:** the ACPIR 57 implementation sub-committee
**Item:** DR-06 of the [decision register](../10-decision-register.md), § 2
**Status:** draft for tabling, August 2026
**Prepared by:** the EIR programme

---

## 1. The ask

FR-412 refuses Tier 3 measurement — contractual rate plus straight-line accretion of net fees — for
**zero-coupon and deep-discount instruments at any tenor**.
[03 § 10.3](../03-calculation-spec.md#103-where-approximation-is-never-permitted) states the same
prohibition and gives the mechanism behind it. Neither states a number, and neither does
[reference case 9](../reference-cases/case-09-straight-line-vs-eir.md).

"Zero-coupon" needs no number: it is the absence of a coupon leg, and the engine tests it against
the cash amounts. "Deep discount" needs one. The engine currently supplies its own — a constant,
`EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE`, set to `0.50`, whose javadoc states in terms
that it is "a policy default of one half, not a specification figure".

**The sub-committee is asked to resolve:**

> That an instrument whose accretion share of total contractual return is **one half or more** is a
> deep-discount instrument for the purposes of FR-412 and
> [03 § 10.3](../03-calculation-spec.md#103-where-approximation-is-never-permitted), and is therefore
> refused Tier 3 measurement at any tenor; that the share is computed as
> `(redemption amount − inception amount) ÷ ((redemption amount − inception amount) + lifetime
> contractual coupon)`; and that the figure is carried as a dated, maker–checked policy version
> rather than as a code constant.

The second half of that resolution matters as much as the first. See § 9.

---

## 2. Recommendation

**Approve one half.** It is the only boundary in the vicinity that can be justified from the
mechanism of the error rather than from market usage of the phrase "deep discount", and the two ways
of being wrong about it are not symmetric — one costs money and is correct, the other is the
documented Cambodian failure mode.

---

## 3. What the number does, and what it does not do

The engine puts every proposed Tier 3 assignment to a second gate, `EquivalenceTestGate`, which asks
two questions in a fixed order:

1. **Is approximation forbidden outright?** — FR-412. Checked first, and not curable by any
   equivalence test however current.
2. **Is there a current equivalence test on file?** — FR-411. Anything else demotes the population to
   Tier 2.

The threshold sits inside the first question only, and only in its second limb:

| Limb of FR-412 | Test | Depends on the threshold? |
|---|---|---|
| Zero-coupon | Lifetime contractual coupon is nil | **No.** Absolute wherever the threshold is set |
| Deep discount | Accretion share of total return at or above the threshold | **Yes. Entirely** |

So the absolute part of FR-412 is absolute regardless of this decision. What the sub-committee is
setting is the width of the band between "no coupon at all" and "a coupon leg that carries the
recognition profile".

---

## 4. The reasoning: why the accretion share, and why one half

### 4.1 The error being guarded against is an error in the accretion pattern

Tier 3's shortcut takes the contractual rate and accretes net fees on a straight line. The error
[03 § 10.3](../03-calculation-spec.md#103-where-approximation-is-never-permitted) identifies is not
an error in the total — the totals tie exactly — it is an error in the *shape*: a straight line where
the correct pattern is compound. That error therefore lives entirely in the part of the instrument's
return that arises from accretion. It does not touch the coupon leg, which the shortcut recognises on
the contractual rate, correctly.

**Which means the error scales with how much of the return is accretion.** That is the whole
argument, and it is why the discriminant is a share of return rather than a discount to face, a
tenor, or a product name.

### 4.2 Where the two regimes swap over

- **Accretion is the minority of return.** A coupon leg carries the recognition profile. The
  straight-line treatment of the remainder is a second-order effect on reported income — wrong, but
  not shaping the income statement.
- **Accretion is the majority of return.** The shortcut *is* the recognition profile. It sets the
  shape of reported income, and the instrument's whole return arrives on a pattern nobody chose.

One half is the point at which those two swap over. There is no other point in the vicinity with an
argument behind it: every alternative boundary is either a round number chosen for conservatism, or a
number imported from a different measure (see § 6).

### 4.3 Why the share is computed from cash amounts and not read off a flag

The engine could take a supplied `is_zero_coupon` attribute and trust it, and then the refusal would
hold exactly as far as the reference data does. It does not. The three inputs are the inception
amount, the contractual redemption amount and the lifetime contractual coupon — figures the engine
needs anyway:

```
accretion       = redemptionAmount − inceptionAmount
total return    = accretion + contractualCouponTotal
accretion share = accretion ÷ total return
```

An instrument booked with a coupon code but no coupon flows is caught here; a flag-based test would
pass it. That property is also what makes option B in § 6 unsafe.

---

## 5. The asymmetry of the two errors

This is the part of the paper the recommendation actually turns on.

| | What it costs |
|---|---|
| **False positive** — an instrument caught that need not have been | Tier 2 measurement: a pool EIR under the ACPIR 51 group presumption, with closed cohorts and a quarterly back-test. **More expensive and correct.** [03 § 10.2](../03-calculation-spec.md#102-the-equivalence-test-tier-3) already names Tier 2 as the consequence of a Tier 3 test being out of date, so it is not a novel or punitive outcome — it is the specification's own fallback, reached one instrument earlier |
| **False negative** — an instrument missed | The straight-line shortcut applied to an instrument whose return is mostly accretion. This is the Cambodia failure mode named in the [08 risk register](../08-roadmap.md#risk-register), and it contaminates ECL as well as income, because straight-line fee amortisation travels with contractual-rate ECL discounting — the two defects the Cambodian remediation projects were run to fix together |

The asymmetry is the reason the threshold is deliberately set to catch more rather than fewer. It is
not a conservatism preference; it is that one side of the error is a compute bill and the other is a
restatement.

---

## 6. Alternatives considered

**A. One half (recommended).** Justified from the mechanism, per § 4.

**B. No threshold — refuse only the absence of a coupon.** Rejected, and this is the option worth
being explicit about, because it looks like the simplification that keeps the absolute rule and drops
the judgement. It does not. FR-412 says "zero-coupon **and** deep-discount", so dropping the second
limb deletes half the requirement — and worse, it publishes an avoidance route. On Case 9's
instrument a coupon of one rupee takes `isZeroCoupon()` from true to false, at which point the only
remaining test is the share. Under option B a 15-year instrument with a token coupon carrying 81% of
year-one income error would pass the gate. A rule that can be defeated by a rounding-sized cash flow
is not a rule.

**C. A lower share — 0.25 or 0.30.** Catches more, and everything it catches goes to Tier 2, which is
the cheap direction of error. Rejected all the same, for the reason § 4.2 gives: there is no
mechanism argument for those boundaries, so they would be numbers chosen for conservatism, and
[03 § 10.2](../03-calculation-spec.md#102-the-equivalence-test-tier-3) exists precisely to forbid an
undocumented materiality boundary. A threshold whose only defence is "we were being careful" is the
same artefact as a shortcut whose only defence is "it was immaterial", pointed the other way.

**D. A higher share — 0.75 or 0.90, matching market usage of "deep discount".** Rejected on two
grounds. First, market usage of the phrase is about issue price relative to face, not about the
accretion share of total return, so adopting it imports a boundary from a different measure and the
resulting number means nothing in the mechanism the refusal rests on. Second, it would leave the
whole majority-accretion band between one half and three quarters on the shortcut — which is exactly
the band § 4.2 identifies as the one where the shortcut sets the shape of the income statement.

**E. A tenor carve-out instead of a share.** Rejected because FR-412 and
[03 § 10.3](../03-calculation-spec.md#103-where-approximation-is-never-permitted) each say "at any
tenor", and because every Tier 3 population is by construction inside twelve months, so a tenor
carve-out for short instruments would empty FR-412 entirely. `EquivalenceTestGate` declines to "split
the difference with an undocumented tenor threshold" for the same reason.

**F. Defer, and leave the constant in place.** The status quo, and its cost is in § 8.

---

## 7. Quantification: reference case 9

The only measured point available. A 15-year zero-coupon at 8%, purchased at 315,241.70 against a
face of 1,000,000.00, so 684,758.30 of discount to accrete and not one rupee of coupon — an accretion
share of exactly **1.0000**.

| | Straight-line | EIR | Error |
|---|---:|---:|---:|
| Year 1 | 45,650.55 | 25,219.34 | **+81.0%** overstated |
| Year 10 | 45,650.55 | 50,413.57 | −9.4% understated |
| Year 15 | 45,650.55 | 74,074.07 | **−38.4%** understated |
| Crossover | | | around year 9 |
| **Life total** | **684,758.30** | **684,758.30** | **0.00** |

*(Year 1, year 15, the crossover and the life total are Case 9's own published figures; the year-10
percentage is derived from Case 9's 45,650.55 against 50,413.57.)*

**The exact tie is the defect, not the reassurance.** An error that nets to zero over fifteen years
is invisible to every control that looks at cumulative figures and material in every single reporting
period. It also has a specific consequence for the equivalence test of
[03 § 10.2](../03-calculation-spec.md#102-the-equivalence-test-tier-3): that test is a
solved-versus-approximated *delta*, and on this instrument the lifetime delta is nil. A test that
measured exactly the thing it is defined to measure would **pass**, on an instrument whose year-one
error is 81%. That is why FR-412 is checked before the evidence and cannot be cured by it, and it is
why this threshold is load-bearing: it is the only thing standing between this category and the
shortcut.

### What the accretion share does to that arithmetic — worked, exactly

| Instrument | Accretion | Coupon | Total return | Share | Refused? |
|---|---:|---:|---:|---:|---|
| Case 9's zero-coupon | 684,758.30 | 0.00 | 684,758.30 | 1.0000 | Yes — zero-coupon limb, threshold irrelevant |
| Paid 100.00, redeems 150.00, lifetime coupon 50.00 | 50.00 | 50.00 | 100.00 | 0.5000 | Yes — at the boundary, which is inclusive |
| Paid 950.00, redeems 1,000.00, lifetime coupon 400.00 | 50.00 | 400.00 | 450.00 | 0.1111 | No — the coupon leg carries the profile |
| Advanced at par, repayable at par, lifetime interest 400.00 | 0.00 | 400.00 | 400.00 | 0.0000 | No — nothing to mis-accrete |

The second row is the boundary and it is inclusive: the engine's test is `share ≥ threshold`, so an
instrument at exactly one half is refused. That is deliberate and follows from § 5 — at the point
where the argument is balanced, the cheap error is the one to make.

### What is not known, and should not be implied

**There is no measured error curve as a function of accretion share.** The only measured point in the
repository is share = 1, at Case 9. If the sub-committee wants the year-one error plotted against
accretion share — which would be the strongest possible support for any threshold, including a
different one — that is a measurement somebody must commission, and this repository does not contain
it.

**There is no population estimate either.** The engine has no portfolio, so nothing here can say how
many of the bank's instruments sit between one half and one. That figure is worth having before the
threshold is reviewed, and it is a query against the securities master rather than an analysis.

---

## 8. Consequence of deferring

Three, in order of seriousness.

**The number keeps deciding.** Deferral does not suspend the rule; it leaves the constant in force.
An unratified figure in `eir-policy` continues to determine which instruments a statutory refusal
catches, and the sub-committee's position on the record is that it has not addressed the question —
which is a weaker position than having set any number, including this one.

**A period closed under an unversioned constant cannot be replayed under the threshold then in
force.** Invariant DT-1 requires a closed period to reproduce bit-identically *and* to resolve
against the policy versions in force at the time. A constant has no effective date. The comparable
figure — the Tier 1 wholesale threshold — is deliberately carried by a dated `PolicyVersion` and not
by a constant, for exactly this reason: "a Board decision has a date and an approver, and a period
closed under last year's threshold must replay under last year's threshold". The deep-discount share
is the same kind of figure and does not yet have the same treatment.

**It blocks the companion decision.** [DR-08](../10-decision-register.md) asks whether the bank
ratifies the engine's resolution of the T-bill tension — § 10's Tier 3 row names T-bills, CP and CD
by hand, and FR-412 refuses every one of them — or approves a carve-out. That question cannot be put
sensibly while the boundary defining "deep discount" is itself unapproved.

---

## 9. Implementation, if approved

1. **The figure moves out of code into a policy version.** Carried as a dated, maker–checked
   `PolicyVersion` with an impact preview, on the same pattern as the Tier 1 wholesale threshold. The
   constant stays only as the fallback of last resort or is removed entirely; either way the
   authoritative value acquires an effective date, so a replay resolves the threshold that was in
   force rather than today's.
2. **Reviewed on the same cadence as the equivalence tests** — annually, alongside control C-10 and
   invariant TG-1. A threshold reviewed on a different clock from the evidence it governs drifts out
   of relationship with it.
3. **Reported.** FR-809 requires reporting on the incidence of every approximation and fallback in
   force. The count of instruments refused Tier 3 under each limb of FR-412 — zero-coupon and deep
   discount, separately — is the figure that tells the sub-committee whether the threshold is doing
   anything at its next review.

## 10. What the sub-committee is not being asked to decide here

- **The zero-coupon limb.** Absolute under FR-412 and independent of this threshold.
- **The T-bill reading.** [DR-08](../10-decision-register.md), a separate paper, and it should follow
  this one rather than precede it.
- **The equivalence-test delta threshold in basis points.** [DR-05](../10-decision-register.md).
  Different question, different evidence: that threshold governs how large a measured delta may be,
  this one governs which instruments may be measured that way at all.
- **What "fully collateralised low-fee" means.** [DR-07](../10-decision-register.md) — and it is
  worth the sub-committee knowing that DR-07 is the route by which a *long*-tenor instrument reaches
  Tier 3 at all, since the tenor rules otherwise bound Tier 3 at twelve months. The two decisions
  bite on the same population from different sides.
