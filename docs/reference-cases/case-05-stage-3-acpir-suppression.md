# Case 5 — Stage 3 under ACPIR: compute the unwind, suppress the income

> The India divergence. ACPIR does not recognise income on Stage 3; IFRS 9 recognises it on the net basis. The engine must compute the unwind regardless, because ECL is discounted at the EIR.
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

The Case 1 loan enters Stage 3 at the start of month 13, with a lifetime ECL allowance of
40% of the gross carrying amount.

## Position

| Quantity | Value |
|---|---|
| Gross carrying amount, month 13 opening | 528,407.32 |
| Lifetime ECL allowance (40%) | 211,362.93 |
| Amortised cost (net of allowance) | 317,044.39 |
| **EIR — unchanged; staging is not an EIR event** | **1.04214918% per month** |

## The three-way decomposition

| | Quantity | Amount |
|---|---|---:|
| (a) | Gross-basis interest, `GCA × EIR` — what Stage 1/2 would recognise | 5,506.79 |
| (b) | IFRS 9 Stage 3 net-basis interest, `AC × EIR` | 3,304.08 |
| (c) | ECL discount unwind, `allowance × EIR` | 2,202.72 |

**Invariant ST-2, exact: (b) + (c) = (a).**  3,304.08 + 2,202.72 = 5,506.79 = 5,506.79

This identity is what makes the Indian treatment tractable. The ECL discount unwind is *precisely*
the interest the gross basis would have recognised on the allowance portion of the balance — so the
engine needs no separate unwind model. It computes one gross-basis figure and decomposes it.

## What each framework does with those numbers

| | IFRS 9 | ACPIR 2026 |
|---|---|---|
| Recognised in interest income | 3,304.08 | **0.00** |
| Where (c) goes | Within the impairment line | Not recognised |
| To interest-in-suspense | — | 5,298.16 (contractual interest billed) |
| Shadow unwind retained for ECL roll-forward | implicit | 2,202.72 |

ACPIR suppresses recognition entirely; the Seventh Amendment Directions protect that from an
auditor qualification. But ECL is a present-value measure discounted at the EIR (ACPIR 50), so the
discount unwinds mechanically whether or not anything is recognised. **ACPIR does not say where the
unwind goes** — this is one of the open questions the bank must close by Board-approved policy
([reference §12 Q2](../reference/acpir-2026-eir-application-reference.md)).

## The four quantities the engine maintains

1. **Gross carrying amount** — continues rolling forward on the gross basis. Staging changes
   neither the GCA nor the EIR.
2. **Shadow EIR unwind** — 2,202.72 this period. Retained for the ECL roll-forward. Never P&L.
3. **Interest-in-suspense** — 5,298.16. A first-class ledger object, not a memorandum.
4. **Recognised interest income** — 0.00 (invariant S3-2).

Control S3-1 reconciles all four every period. This is the control that will attract the most audit
attention, because it is where India diverges from the standard the engine's mechanics are borrowed
from.

## Cure

Recognition resumes on the gross basis **prospectively**. There is no catch-up for interest not
recognised while in Stage 3 — booking one would recognise income that was correctly never
recognised. The EIR is unchanged throughout.

## The card-book problem

Account-level suspension analysis is impractical for credit cards and KCC at volume. ACPIR provides
no portfolio carve-out, so policy must supply one; the HKMA's explicit portfolio treatment for
portfolio-managed products is the precedent. The engine supports suspension at pool level with the
pool definition as an approved, versioned artefact.
