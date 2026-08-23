# Case 2 — Full prepayment, accelerated fee recognition

> Closure before maturity accelerates the entire unamortised fee to P&L.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

Continues [Case 1](case-01-emi-loan-with-fees.md). The borrower settles in full at the end
of month 12.

## Position immediately before settlement

| Quantity | Value |
|---|---|
| Contractual outstanding principal | 529,815.61 |
| EIR amortised cost (gross carrying amount) | 528,407.32 |
| **Unamortised net integral fee** | **1,408.29** |
| Net fee already amortised, months 1–12 | 3,591.71 |

## Treatment

The borrower discharges the **contractual** principal of 529,815.61; the carrying amount on the
books is 528,407.32. The residual 1,408.29 is the deferred fee that would have been released over
months 13–24 and is recognised **immediately in P&L on derecognition**.

| Entry | Amount |
|---|---|
| Cash received | 529,815.61 |
| Gross carrying amount derecognised | (528,407.32) |
| **Gain to P&L — accelerated fee** | **1,408.29** |

## Why this matters

The failure mode is writing the residual to a suspense account rather than to income
([01 §11 #9](../01-domain-primer.md#11-common-failure-modes)). That produces unexplained P&L and an
unreconciled fee balance which then never clears.

Note also what is *not* here: no prepayment penalty enters the calculation. Contingent fees are
excluded from the EIR projection at inception ([03 §3.3](../03-calculation-spec.md#33-contingent-fees));
a penalty, where one is chargeable at all, is separate income in this period. For floating-rate
individual loans RBI restricts foreclosure charges, so in the Indian retail book there is usually
no penalty cash flow to consider.

**Cross-check.** 1,408.29 is the same number as the month-12 unamortised fee in Case 1 (INV-4) and
the amount [Case 8](case-08-b544-repricing-shortcut.md) eliminates by amortising to the next
repricing date instead. Three routes, one figure.
