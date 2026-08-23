# Case 8 — The B5.4.4 next-repricing-date shortcut

> Where an instrument reprices to market before maturity, fees amortise to the next repricing date instead of over expected life. On a floating-rate retail book this largely dissolves the behavioural-life problem.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

The Case 1 loan priced as floating and reset annually. Net integral fee 5,000.00.

IFRS 9 B5.4.4 amortises a premium, discount or fee relating to a variable repriced to market rates
to the **next repricing date**. Adopted by election where ACPIR is silent
([03 §2](../03-calculation-spec.md#2-interpretive-hierarchy)); it is an **accounting policy election
per product**, not a per-contract optimisation.

## Mechanics

For fee-amortisation purposes the instrument is treated as maturing at the next reset: the projector
appends a synthetic `NOTIONAL_REDEMPTION` flow equal to the contractual balance at that date, and
solves over the truncated vector.

| Input | Value |
|---|---|
| Initial gross carrying amount | 995,000.00 |
| Flows | 12 EMIs of 47,073.47 + notional redemption of 529,815.61 at month 12 |

## Comparison

| Basis | EIR (per month) | EIR (effective p.a.) | Net fee recognised, months 1–12 | Unamortised fee past the reset |
|---|---:|---:|---:|---:|
| Full expected life (24m) | 1.04214918% | 13.248094% | 3,591.71 | 1,408.29 |
| **B5.4.4 shortcut (to m12)** | **1.05614730%** | **13.436507%** | **5,000.00** | **-0.00** |

The shortcut accelerates year-one fee recognition by 39.2% and leaves
nothing to carry across the reset.

## Roll-forward under the shortcut

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 995,000.00 | 10,508.67 | 47,073.47 | 958,435.20 |
| 2 | 958,435.20 | 10,122.49 | 47,073.47 | 921,484.21 |
| 3 | 921,484.21 | 9,732.23 | 47,073.47 | 884,142.97 |
| 4 | 884,142.97 | 9,337.85 | 47,073.47 | 846,407.36 |
| 5 | 846,407.36 | 8,939.31 | 47,073.47 | 808,273.19 |
| 6 | 808,273.19 | 8,536.56 | 47,073.47 | 769,736.28 |
| 7 | 769,736.28 | 8,129.55 | 47,073.47 | 730,792.36 |
| 8 | 730,792.36 | 7,718.24 | 47,073.47 | 691,437.13 |
| 9 | 691,437.13 | 7,302.59 | 47,073.47 | 651,666.26 |
| 10 | 651,666.26 | 6,882.56 | 47,073.47 | 611,475.34 |
| 11 | 611,475.34 | 6,458.08 | 47,073.47 | 570,859.95 |
| 12 | 570,859.95 | 6,029.12 | 576,889.08 | 0.00 |

Terminal closing balance 0.000000 — the notional redemption closes the vector.

## Why this is the operationally important case

**It removes the reset-loop cost.** EBLR resets can be monthly or quarterly. With no unamortised fee
to carry forward, a reset does not have to re-solve anything for the fee — only reprice the interest
leg. Naively re-solving the full schedule on every reset for every retail account is computationally
brutal at Indian volumes ([03 §4.6](../03-calculation-spec.md#46-performance)).

**It dissolves the behavioural-life estimation problem for the floating-rate book.** Observed
behavioural life on Indian retail mortgages is far shorter than the 15–20 year contractual tenor
because of balance-transfer churn, and behavioural life is the highest-leverage assumption in the
model — compressing assumed life from 20 years to 8 multiplies year-one fee recognition by **3.73×**
([03 §3.6](../03-calculation-spec.md#behavioural-life-leverage)). Amortising to the next repricing
date sidesteps the estimate entirely. Reserve behavioural modelling for the fixed-rate book and for
cards.

**Cross-check.** The 1,408.29 carried across the reset under full expected life is the
same figure as [Case 2](case-02-full-prepayment.md)'s prepayment acceleration and invariant INV-4 at
month 12. Three routes, one number.
