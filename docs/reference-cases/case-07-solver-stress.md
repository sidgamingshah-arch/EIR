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

At this magnitude Newton–Raphson seeded from the contractual rate can overshoot outside the
bracket; the bisection fallback is what guarantees termination
([03 §4.2](../03-calculation-spec.md#42-algorithm)).

## Must raise, never guess

| Scenario | Required behaviour |
|---|---|
| Total inflows ≤ initial outflow (no sign change over the bracket ladder) | `NoSolution` → exception queue with the flow vector attached. **Never** default to zero or to the contractual rate. |
| Multiple sign changes, exactly one root in the plausible band | Take it; record that disambiguation occurred and log all candidate roots. |
| Multiple sign changes, several roots in band | Take the root nearest contractual; mark `REQUIRES_REVIEW`; route for approval. Computed and usable, but flagged. |
| Multiple sign changes, no root in band | Exception queue. |
| Newton–Raphson exceeds 100 iterations | Fall back to 200 bisection iterations over the bracket. |
| Unmapped fee code on the contract | Exception queue before the solver is reached. |
| `EXCLUDED_BY_DIRECTION` posting (penal charge) present in the vector | Rejected at ingestion; invariant PC-1 asserted for the period. |

**The one that matters most.** Silently falling back to the contractual rate on non-convergence
reproduces the pre-ACPIR position while appearing to have implemented EIR. It produces plausible
numbers and leaves no trace, which makes it the most damaging failure mode available to this engine.
Non-convergence is an exception requiring manual review — always.

## Structures that generate these profiles

Not hypothetical. In an Indian bank's book: step-up and step-down EMIs, balloon and bullet
repayments, moratoria with interest capitalisation, IDC on project finance, FITL creation on
restructuring, and tranched disbursement against milestones. Multiple sign changes come from
tranched facilities with large interim drawdowns.
