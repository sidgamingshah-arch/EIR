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
| Expected monthly receipt (80%) | 37,658.78 |

| Basis | Per month | Effective p.a. |
|---|---:|---:|
| **Credit-adjusted EIR (expected flows) — correct** | **2.15405325%** | **29.141920%** |
| EIR on contractual flows — wrong | 4.24580927% | 64.703664% |

Discounting *contractual* flows to a distressed purchase price yields
64.703664% per annum. It would accrue income the bank has no expectation of
collecting, then reverse it through impairment. The credit-adjusted rate is what makes the income
statement mean anything.

Note that the rate solves to **amortised cost**, not gross carrying amount (ACPIR 6(4)). There is
no gross-basis phase for a POCI asset.

## Roll-forward at the credit-adjusted EIR

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 700,000.00 | 15,078.37 | 37,658.78 | 677,419.59 |
| 2 | 677,419.59 | 14,591.98 | 37,658.78 | 654,352.79 |
| 3 | 654,352.79 | 14,095.11 | 37,658.78 | 630,789.12 |
| 4 | 630,789.12 | 13,587.53 | 37,658.78 | 606,717.87 |
| 5 | 606,717.87 | 13,069.03 | 37,658.78 | 582,128.12 |
| 6 | 582,128.12 | 12,539.35 | 37,658.78 | 557,008.69 |
| 23 | 72,952.05 | 1,571.43 | 37,658.78 | 36,864.69 |
| 24 | 36,864.69 | 794.09 | 37,658.78 | 0.00 |

*(periods 7–22 omitted; terminal closing balance 0E-8)*

## Two ACPIR rules the engine enforces

- **POCI identification is driven off acquisition terms**, not only subsequent behaviour, and
  applies at transition as well as at origination. A legacy pool bought at a credit-related
  discount before 1 April 2027 is POCI when it comes under the regime.
- **The credit-adjusted EIR is retained after cure.** It is not reset — invariant POCI-1, following
  the 2019 IFRS Interpretations Committee direction. A cure improves the expected cash flows; it
  does not retrospectively make the purchase price a par acquisition.
