# Case 1 — Fixed-rate EMI term loan with integral fees

> The baseline. Establishes the EIR, the two-leg reconciliation, and the fee amortisation profile.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

## Terms

| Input | Value |
|---|---|
| Principal advanced | 1,000,000.00 |
| Contractual rate | 12.00% p.a. nominal, monthly compounding = 1.00% per month |
| Tenor | 24 monthly EMIs |
| Processing fee received (integral, ACPIR 52) | 15,000.00 |
| DSA commission paid (integral, ACPIR 53) | 10,000.00 |
| Product tier | Tier 2 illustrated at contract level ([03 §10](../03-calculation-spec.md#10-the-materiality-tier-gate)) |

## Derived

| Quantity | Value |
|---|---|
| EMI, unrounded annuity | 47,073.472223 |
| EMI, billed at 2dp | 47,073.47 |
| Initial gross carrying amount | 995,000.00 |
| Cash disbursed to borrower | 985,000.00 |
| Cash paid to sourcing agent | 10,000.00 |
| **Net cash outflow at inception** | **995,000.00** — equals GCA₀, invariant IC-1 |
| Contractual effective rate | 12.682503% p.a. |
| **EIR, per month** | **1.04214918%** |
| EIR, nominal p.a. (×12) | 12.505790% |
| **EIR, effective p.a.** | **13.248094%** |
| Solver residual at the stored 12dp rate | 0E-12 |

The 5,000 of net integral fee lifts reported yield 56.6 basis
points above the contractual effective rate. EIR exceeds contractual because the net fee is income,
so the asset is recorded below par and must accrete back up — invariant INV-2.

## Amortised-cost roll-forward (EIR leg)

| Pd | Opening GCA | EIR interest | Cash received | Closing GCA |
|---:|---:|---:|---:|---:|
| 1 | 995,000.00 | 10,369.38 | 47,073.47 | 958,295.91 |
| 2 | 958,295.91 | 9,986.87 | 47,073.47 | 921,209.32 |
| 3 | 921,209.32 | 9,600.38 | 47,073.47 | 883,736.22 |
| 4 | 883,736.22 | 9,209.85 | 47,073.47 | 845,872.60 |
| 5 | 845,872.60 | 8,815.25 | 47,073.47 | 807,614.39 |
| 6 | 807,614.39 | 8,416.55 | 47,073.47 | 768,957.46 |
| 7 | 768,957.46 | 8,013.68 | 47,073.47 | 729,897.68 |
| 8 | 729,897.68 | 7,606.62 | 47,073.47 | 690,430.83 |
| 9 | 690,430.83 | 7,195.32 | 47,073.47 | 650,552.68 |
| 10 | 650,552.68 | 6,779.73 | 47,073.47 | 610,258.94 |
| 11 | 610,258.94 | 6,359.81 | 47,073.47 | 569,545.28 |
| 12 | 569,545.28 | 5,935.51 | 47,073.47 | 528,407.32 |
| 13 | 528,407.32 | 5,506.79 | 47,073.47 | 486,840.64 |
| 14 | 486,840.64 | 5,073.61 | 47,073.47 | 444,840.78 |
| 15 | 444,840.78 | 4,635.90 | 47,073.47 | 402,403.21 |
| 16 | 402,403.21 | 4,193.64 | 47,073.47 | 359,523.38 |
| 17 | 359,523.38 | 3,746.77 | 47,073.47 | 316,196.68 |
| 18 | 316,196.68 | 3,295.24 | 47,073.47 | 272,418.45 |
| 19 | 272,418.45 | 2,839.01 | 47,073.47 | 228,183.99 |
| 20 | 228,183.99 | 2,378.02 | 47,073.47 | 183,488.54 |
| 21 | 183,488.54 | 1,912.22 | 47,073.47 | 138,327.29 |
| 22 | 138,327.29 | 1,441.58 | 47,073.47 | 92,695.40 |
| 23 | 92,695.40 | 966.02 | 47,073.47 | 46,587.95 |
| 24 | 46,587.95 | 485.52 | 47,073.47 | -0.00 |
| **Total** | | **134,763.28** | **1,129,763.28** | |

Terminal closing balance: **-0E-8** — invariant TR-1.

## Two-leg reconciliation

| Quantity | Value |
|---|---|
| Total EIR interest recognised | 134,763.28 |
| Total contractual interest (billed flows) | 129,763.28 |
| Difference | 5,000.00 |
| Net integral fee | 5,000.00 |

**Invariant INV-1 holds:** the EIR method changes the *timing* of recognition, never the total.

## Per-period fee amortisation

| Pd | EIR interest | Contractual interest | Fee amortised | Unamortised fee c/f |
|---:|---:|---:|---:|---:|
| 1 | 10,369.38 | 10,000.00 | 369.38 | 4,630.62 |
| 2 | 9,986.87 | 9,629.27 | 357.61 | 4,273.01 |
| 3 | 9,600.38 | 9,254.82 | 345.55 | 3,927.46 |
| 4 | 9,209.85 | 8,876.64 | 333.21 | 3,594.24 |
| 5 | 8,815.25 | 8,494.67 | 320.59 | 3,273.66 |
| 6 | 8,416.55 | 8,108.88 | 307.67 | 2,965.99 |
| 12 | 5,935.51 | 5,711.77 | 223.74 | 1,408.29 |
| 18 | 3,295.24 | 3,167.20 | 128.05 | 394.79 |
| 23 | 966.02 | 927.53 | 38.49 | 19.50 |
| 24 | 485.52 | 466.07 | 19.44 | 0.06 |

Straight-lining the fee would recognise 208.33 every month. EIR amortisation recognises
369.38 in month 1 and
19.44 in month 24 — front-loaded, because the balance it
accretes on is largest early. That difference is the whole purpose of the method.

**Month 12 unamortised fee = 1,408.29**
(invariant INV-4: contractual GCA 529,815.61 − EIR GCA 528,407.32).
This figure recurs in Cases 2 and 8.

## The contractual-leg residue

The billed EMI of 47,073.47 is the true annuity payment of 47,073.472223 rounded down to paise. Over
24 periods that shortfall compounds to a residue of **0.059969** on the
*contractual* leg — visible as the 0.06 standing in the unamortised-fee column at period 24.

The EIR leg is unaffected and amortises to exactly zero. The residue is resolved by the
rounding-residue policy at [03 §7](../03-calculation-spec.md), preferably
`LMS_AUTHORITATIVE` — consume the schedule the core banking system actually billed rather than
deriving one, because a derived schedule guarantees a monthly reconciliation break.
