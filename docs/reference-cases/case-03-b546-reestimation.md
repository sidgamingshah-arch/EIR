# Case 3 — Re-estimation of cash flows: retrospective catch-up at the original EIR

> IFRS 9 B5.4.6 mechanics, adopted by election where ACPIR is silent. The rate does not move; the balance sheet is restated and the difference hits P&L at once.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

Continues [Case 1](case-01-emi-loan-with-fees.md). At the end of month 12 the borrower is
granted a six-month tenor extension: the remaining 12 EMIs are re-profiled into 18 smaller ones.
Assessed as **not a substantial modification**, so no derecognition.

Event driver tag: `BEHAVIOURAL_ESTIMATE` → mechanism `CATCH_UP`
([03 §6.1](../03-calculation-spec.md#61-routing-is-configuration-keyed-to-a-driver-taxonomy)).

## Computation

| Step | Value |
|---|---|
| Contractual balance at month 12 | 529,815.61 |
| Revised EMI, contractual rate unchanged at 1.00%/month, 18 periods | 32,309.24 |
| **Original EIR — retained, not re-solved** | **1.04214918% per month** |
| PV of revised flows discounted at the **original** EIR | 527,779.90 |
| Carrying amount before restatement | 528,407.32 |
| **Catch-up adjustment to P&L** | **-627.42** (a charge) |

## Post-modification roll-forward — EIR unchanged

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 527,779.90 | 5,500.25 | 32,309.24 | 500,970.91 |
| 2 | 500,970.91 | 5,220.86 | 32,309.24 | 473,882.54 |
| 3 | 473,882.54 | 4,938.56 | 32,309.24 | 446,511.86 |
| 4 | 446,511.86 | 4,653.32 | 32,309.24 | 418,855.94 |
| 5 | 418,855.94 | 4,365.10 | 32,309.24 | 390,911.80 |
| 6 | 390,911.80 | 4,073.88 | 32,309.24 | 362,676.45 |
| 17 | 63,622.20 | 663.04 | 32,309.24 | 31,976.00 |
| 18 | 31,976.00 | 333.24 | 32,309.24 | 0.00 |
| **Total** | | **29,748.26** | **258,473.92** | |

*(periods 7–16 omitted; terminal closing balance 0E-8)*

## The discriminator

Compare [Case 4](case-04-floating-rate-reset.md): the **same instrument**, the **same month**, and
in both cases "the schedule changed".

| | Case 3 — re-estimation | Case 4 — benchmark reset |
|---|---|---|
| Cause | Entity's own estimate revised | Market benchmark moved |
| Driver tag | `BEHAVIOURAL_ESTIMATE` | `TIME_VALUE_OF_MONEY` |
| EIR | **Unchanged** 1.04214918% | **Re-solved** to 1.12558515% |
| Carrying amount | **Restated** to 527,779.90 | **Unchanged** at 528,407.32 |
| P&L now | **-627.42** | **0.00** |

Getting this pair the wrong way round is the highest-impact error available in this domain, which
is why the engine routes on an explicit driver tag carried on the event rather than inferring
anything from the fact that the rate or the schedule moved.

**Invariant CU-1:** the persisted EIR before and after this event must be bit-identical. Discounting
the revised flows at a *re-solved* rate would drive the catch-up to approximately zero and silently
convert this into a Case 4.
