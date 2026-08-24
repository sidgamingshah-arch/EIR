# Case 7 — Solver stress instruments

> Edge cases that must converge, and the behaviours that must never be silently defaulted.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

These exercise the solver rather than the accounting. Every one must converge to the stated
figure; the negative cases must raise, not guess.

## Must converge

**Zero-coupon, 5-year bullet, no fees.** The EIR must recover the contractual yield exactly.

| Input | Value |
|---|---|
| Amount advanced | 1,000,000.00 |
| Single receipt at month 60 | 1,816,696.70 |
| **Recovered monthly EIR** | **0.010000000000** |
| Expected | 0.010000000000 |

Exact to 12 decimal places. This is the solver's calibration test: a single flow, closed-form
answer, no room to hide.

**Interest-only with bullet repayment and a large upfront fee.** 59 monthly interest payments of
10,000.00 then 1,010,000.00 at month 60, against 950,000.00 advanced.

EIR = **1.11473209% per month**, 14.228172% p.a.

**Deep-discount instrument, fee > 20% of principal.** The 24 Case 1 EMIs against 780,000.00
advanced.

EIR = **3.20369894% per month**, 45.996740% p.a.

At this magnitude a raw tangent step from the contractual rate can leave the bracket, and the
safeguards of step 3 replace it with a bisection of the current bracket rather than abandoning the
solve ([03 §4.2](../03-calculation-spec.md#42-algorithm)).

## Must raise, never guess

| Scenario | Required behaviour |
|---|---|
| No sign change anywhere on the ladder **or its escalation** | `NoSolution` → exception queue with the flow vector attached. **Never** default to zero or to the contractual rate. |
| A root the standard ladder cannot bracket, found only by escalation | `REQUIRES_REVIEW` with the rate and its full working. Never `SOLVED`. |
| Multiple sign changes, exactly one root in the plausible band | Take it; record that disambiguation occurred and log all candidate roots. |
| Multiple sign changes, several roots in band | Take the root nearest contractual; mark `REQUIRES_REVIEW`; route for approval. Computed and usable, but flagged. |
| Multiple sign changes, no root in band | Exception queue. |
| Newton–Raphson exceeds 100 iterations | Fall back to 200 bisection iterations over the bracket. Unreachable under the specification's cap — every accepted point halves the bracket, so the width test fires first — and the guarantee is kept anyway. |
| Unmapped fee code on the contract | Exception queue before the solver is reached. |
| `EXCLUDED_BY_DIRECTION` posting (penal charge) present in the vector | Rejected at ingestion; invariant PC-1 asserted for the period. |

### Why "total inflows <= initial outflow" is not the no-solution case

This row said, until the engine was built against it, that a vector whose inflows do not exceed the
initial outflow has no rate. That is false, and it was worth getting wrong to find out why.

For a plain asset vector — one outflow at inception, receipts afterwards — `f(r) = PV(r) - GCA_0`
tends to `+infinity` as `r -> -100%+`, because discounting at a negative rate *inflates*, and tends
to `-GCA_0` as `r -> infinity`. It is continuous between them. So it **always** crosses zero: a
unique rate above -100% exists for every such vector, whatever the recovery. A token recovery of
50.00 against 1,000,000.00 advanced one month earlier does have an EIR, and it is exactly
-99.995% per month.

What actually failed was reach, not existence. A ladder node caps the money multiple the scan can
bracket at `(1+node)^tau`, and that cap collapses toward 1 as tau falls:

| tau | bracketable at node 10.0 | bracketable at node -0.9999 |
|---|---|---|
| 1 day | 1.006591 | 0.975082 — a 2.49% loss |
| 3 days | 1.019904 | 0.927093 — a 7.29% loss |
| 7 days | 1.047061 | 0.838084 — a 16.19% loss |
| 1 year | 11.000000 | 0.000100 — a 99.99% loss |

So a single-day money-market drawing of 10,000,000.00 with a 100,000.00 integral fee and 6.75%
ACT/365F interest repays 10,001,849.32 against 9,900,000.00 advanced — a multiple of 1.010288, an
ordinary instrument, and an unambiguous **4,092.4331% annual effective**. It sat above the top of
the ladder and came back `NoSolution`. That is the entire short-tenor population, which is the
Tier 3 population.

The engine therefore escalates the ladder to `[-0.999999999999, 1e12]` when the standard rungs find
no sign change, and reports what it finds as `REQUIRES_REVIEW` — never `SOLVED`. A root out there is
one of two things and both need a person: a tenor too short to carry the fee loaded onto it, where
the annualisation is arithmetically correct and the presentation is a policy question
([03 §4.5](../03-calculation-spec.md)); or a recovery so far below the advance that the answer is
impairment rather than interest. Saying *which rate* the vector implies is strictly more actionable
than saying none exists — and it sends nobody looking for a fee misclassification that is not there.

`NoSolution` is now reserved for vectors where no root exists at all: no sign change in `f`'s own
coefficient sequence, so it cannot cross zero. A facility drawn twice and never repaid is the case,
and in production it is a data defect on a tranched facility rather than an economic outcome.

**The one that matters most.** Silently falling back to the contractual rate on non-convergence
reproduces the pre-ACPIR position while appearing to have implemented EIR. It produces plausible
numbers and leaves no trace, which makes it the most damaging failure mode available to this engine.
Non-convergence is an exception requiring manual review — always.

## Structures that generate these profiles

Not hypothetical. In an Indian bank's book: step-up and step-down EMIs, balloon and bullet
repayments, moratoria with interest capitalisation, IDC on project finance, FITL creation on
restructuring, and tranched disbursement against milestones. Multiple sign changes come from
tranched facilities with large interim drawdowns.
