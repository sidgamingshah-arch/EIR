# Case 4 — Floating-rate benchmark reset: prospective, no catch-up

> IFRS 9 B5.4.5 mechanics. The rate is re-solved from the carrying amount already on the books; nothing hits P&L.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

The Case 1 loan, priced instead at a repo-linked EBLR of repo + 550bps and reset annually.
At the end of month 12 the benchmark rises 100bps, taking the contractual rate from 12% to 13% p.a.

Event driver tag: `TIME_VALUE_OF_MONEY` → mechanism `RESET`.

## Computation

| Step | Value |
|---|---|
| Contractual rate after reset | 13.00% p.a. nominal = 1.08333333% per month |
| Contractual balance at reset | 529,815.61 |
| Revised EMI for the remaining 12 months | 47,321.69 |
| **Carrying amount at reset — unchanged** | **528,407.32** |
| **Revised EIR** | **1.12558515% per month** |
| Revised EIR, effective p.a. | 14.375386% |
| **Catch-up adjustment to P&L** | **0.00** |

The EIR is re-solved so that the revised flows discount to the carrying amount **already on the
books**. The unamortised fee balance of 1,408.29 is not released early — it is absorbed into the
new rate and continues to amortise across the remaining life.

## Post-reset roll-forward

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 528,407.32 | 5,947.67 | 47,321.69 | 487,033.30 |
| 2 | 487,033.30 | 5,481.97 | 47,321.69 | 445,193.59 |
| 3 | 445,193.59 | 5,011.03 | 47,321.69 | 402,882.93 |
| 4 | 402,882.93 | 4,534.79 | 47,321.69 | 360,096.03 |
| 5 | 360,096.03 | 4,053.19 | 47,321.69 | 316,827.53 |
| 6 | 316,827.53 | 3,566.16 | 47,321.69 | 273,072.00 |
| 7 | 273,072.00 | 3,073.66 | 47,321.69 | 228,823.97 |
| 8 | 228,823.97 | 2,575.61 | 47,321.69 | 184,077.89 |
| 9 | 184,077.89 | 2,071.95 | 47,321.69 | 138,828.15 |
| 10 | 138,828.15 | 1,562.63 | 47,321.69 | 93,069.09 |
| 11 | 93,069.09 | 1,047.57 | 47,321.69 | 46,794.97 |
| 12 | 46,794.97 | 526.72 | 47,321.69 | -0.00 |
| **Total** | | **39,452.96** | **567,860.28** | |

Terminal closing balance -0E-8.

## Why prospective

A benchmark reset changes future cash flows for reasons wholly outside the entity's estimation
process. There is nothing to correct retrospectively, so B5.4.5 revises the rate and moves on.

## Two operational notes

**Do not re-solve the whole book on every reset.** EBLR resets can be monthly or quarterly; naively
re-solving the full schedule per account is computationally brutal at Indian retail volumes. The
B5.4.4 shortcut ([Case 8](case-08-b544-repricing-shortcut.md)) amortises the fee to the next
repricing date instead, which removes the unamortised balance that makes the reset expensive.

**This is not the treatment for a renegotiated fixed rate.** B5.4.5 applies to instruments
repricing off a market benchmark *by their own terms*. A negotiated rate change on a fixed-rate
loan is a modification and runs the substantiality test
([03 §6.4](../03-calculation-spec.md#64-modification-versus-derecognition)).
