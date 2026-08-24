# Case 6 — POCI asset: the credit-adjusted EIR

> Expected losses live inside the rate from inception. Using contractual flows instead produces a yield that is arithmetically impeccable and economically fictional.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

A distressed pool is acquired for 700,000.00. Contractual flows are the 24 EMIs of
[Case 1](case-01-emi-loan-with-fees.md), but 20% of every receipt is expected to be lost.

ACPIR 6(3)(v) makes acquisition at a discount reflecting inherent credit losses an explicit
credit-impairment indicator, so this is POCI on acquisition terms alone. ACPIR 24 requires the day-1
lifetime ECL to sit inside the cash flows used to compute the rate, with **no separate day-1
allowance**; thereafter only *cumulative changes* in lifetime ECL are recognised.

## Computation

| Input | Value |
|---|---|
| Purchase price = amortised cost at initial recognition | 700,000.00 |
| Contractual monthly receipt | 47,073.47 |
| Expected monthly receipt (80%), as carried | 37658.7760 |
| Expected monthly receipt, as presented | 37,658.78 |

| Basis | Per month | Effective p.a. |
|---|---:|---:|
| **Credit-adjusted EIR (expected flows) — correct** | **2.15405231%** | **29.141905%** |
| EIR on contractual flows — wrong | 4.24580927% | 64.703664% |

Discounting *contractual* flows to a distressed purchase price yields
64.703664% per annum. It would accrue income the bank has no expectation of
collecting, then reverse it through impairment. The credit-adjusted rate is what makes the income
statement mean anything.

Note that the rate solves to **amortised cost**, not gross carrying amount (ACPIR 6(4)). There is
no gross-basis phase for a POCI asset.

> **On the expected receipt.** It is carried at working precision (37658.7760), not rounded to
> paise, because it is a derived *estimate of collections* rather than a billed amount — nobody is
> ever billed 80% of an instalment, so no cash event attaches currency scale to it, and
> [03 § 1.2](../03-calculation-spec.md#12-working-precision) forbids rounding an intermediate
> before it enters a solve. The same principle governs Case 8's synthetic notional redemption and
> Case 9's yield-derived price. Rounding it first shifts the rate to 2.15405325% per month —
> a difference of 1.5 bp p.a., invisible in the roll-forward (period 1 is 15,078.37 either way)
> and therefore exactly the kind of discrepancy that survives review. Stated here explicitly so
> that a reader who recomputes and gets the other number knows which input differed.
>
> The **Cash received** column below shows the *presented* 37,658.78; the carried figure
> is 37658.7760. So the life total is 903,810.62, not 24 x 37,658.78 =
> 903,810.72. Multiplying the presented figure is how a stale expected value gets
> written down, so the carried total is stated here rather than left to be inferred.

## Roll-forward at the credit-adjusted EIR

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 700,000.00 | 15,078.37 | 37,658.78 | 677,419.59 |
| 2 | 677,419.59 | 14,591.97 | 37,658.78 | 654,352.79 |
| 3 | 654,352.79 | 14,095.10 | 37,658.78 | 630,789.11 |
| 4 | 630,789.11 | 13,587.53 | 37,658.78 | 606,717.86 |
| 5 | 606,717.86 | 13,069.02 | 37,658.78 | 582,128.11 |
| 6 | 582,128.11 | 12,539.34 | 37,658.78 | 557,008.68 |
| 23 | 72,952.04 | 1,571.43 | 37,658.78 | 36,864.69 |
| 24 | 36,864.69 | 794.08 | 37,658.78 | -0.00 |

*(periods 7–22 omitted; terminal closing balance -0E-8)*

## Two ACPIR rules the engine enforces

- **POCI identification is driven off acquisition terms**, not only subsequent behaviour, and
  applies at transition as well as at origination. A legacy pool bought at a credit-related
  discount before 1 April 2027 is POCI when it comes under the regime.
- **The credit-adjusted EIR is retained after cure.** It is not reset — invariant POCI-1, following
  the 2019 IFRS Interpretations Committee direction. A cure improves the expected cash flows; it
  does not retrospectively make the purchase price a par acquisition.
