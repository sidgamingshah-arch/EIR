# Case 9 — Straight-line versus EIR on a long zero-coupon: the wedge

> The totals tie exactly, which is what makes the error invisible over the life of the instrument and glaring in any single period.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

The documented failure mode from the Cambodian CIFRS 9 transition, and the argument that makes
selective EIR adoption on the investment book indefensible.

## Instrument

| Input | Value |
|---|---|
| Face value at maturity | 1,000,000.00 |
| Yield | 8.00% p.a. |
| Tenor | 15 years, zero coupon |
| Purchase price (PV) | 315,241.70 |
| Total discount to accrete | 684,758.30 |
| Straight-line accretion per year | 45,650.55 |

## Year-by-year

| Yr | Opening carrying amount | Straight-line | EIR accretion | SL overstates by |
|---:|---:|---:|---:|---:|
| 1 | 315,241.70 | 45,650.55 | 25,219.34 | 20,431.22 |
| 2 | 340,461.04 | 45,650.55 | 27,236.88 | 18,413.67 |
| 3 | 367,697.92 | 45,650.55 | 29,415.83 | 16,234.72 |
| 4 | 397,113.76 | 45,650.55 | 31,769.10 | 13,881.45 |
| 5 | 428,882.86 | 45,650.55 | 34,310.63 | 11,339.92 |
| 6 | 463,193.49 | 45,650.55 | 37,055.48 | 8,595.07 |
| 7 | 500,248.97 | 45,650.55 | 40,019.92 | 5,630.64 |
| 8 | 540,268.88 | 45,650.55 | 43,221.51 | 2,429.04 |
| 9 | 583,490.40 | 45,650.55 | 46,679.23 | -1,028.68 |
| 10 | 630,169.63 | 45,650.55 | 50,413.57 | -4,763.02 |
| 11 | 680,583.20 | 45,650.55 | 54,446.66 | -8,796.10 |
| 12 | 735,029.85 | 45,650.55 | 58,802.39 | -13,151.84 |
| 13 | 793,832.24 | 45,650.55 | 63,506.58 | -17,856.03 |
| 14 | 857,338.82 | 45,650.55 | 68,587.11 | -22,936.55 |
| 15 | 925,925.93 | 45,650.55 | 74,074.07 | -28,423.52 |
| **Total** | | **684,758.30** | **684,758.30** | **-0.00** |

Terminal carrying amount 1,000,000.00 against face 1,000,000.00.

## The numbers that matter

| | Straight-line | EIR | Error |
|---|---:|---:|---:|
| Year 1 | 45,650.55 | 25,219.34 | **+81.0%** overstated |
| Year 15 | 45,650.55 | 74,074.07 | **−38.4%** understated |
| Crossover | | | around year 9 |
| **Life total** | 684,758.30 | 684,758.30 | **0.00** |

Straight-line overstates year-one income by **81.0%**.

## Why this is the wedge

The life totals are identical to the paisa. An error that nets to zero over 15 years is invisible to
any control that looks only at cumulative figures, and material in every single reporting period —
front-loading income in the early years and starving the later ones.

Three consequences:

1. **No approximation is permissible for zero-coupon and deep-discount instruments at any tenor**
   ([03 §10.3](../03-calculation-spec.md#103-where-approximation-is-never-permitted)). The Tier 3
   equivalence test cannot be passed here, because the error is not small at any horizon.
2. **It contaminates ECL.** Straight-line fee amortisation travels with contractual-rate ECL
   discounting, which is what ACPIR 50 exists to prevent. The Cambodian remediation projects were
   run to fix both together, because they are the same defect seen twice.
3. **It settles the investment-book question.** The Investment Portfolio Directions mandate
   amortisation over remaining life but do not, on the text reviewed, specify the method
   ([reference §5 item 7](../reference/acpir-2026-eir-application-reference.md)). If the bank adopts
   EIR selectively, this is the category where selectivity cannot be defended — which makes it the
   lever for a full migration rather than a carve-out.
