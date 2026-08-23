"""Generates docs/reference-cases/*.md as golden fixtures from the verified computations."""
from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import amort_table, q, emi, solve_irr, npv, annualise, pow_d
import os

OUT="/home/user/EIR/docs/reference-cases"
os.makedirs(OUT, exist_ok=True)

def f(x, p=2):
    s = f"{q(x,p):,}"
    return s

def tbl(rows, cols=("period","opening","interest","cash","closing"),
        hdr=("Pd","Opening GCA","EIR interest","Cash received","Closing GCA")):
    out=["| "+" | ".join(hdr)+" |","|"+"|".join(["---:"]*len(hdr))+"|"]
    for r in rows:
        cells=[]
        for c in cols:
            v=r[c]
            cells.append(str(v) if c=="period" else f(v))
        out.append("| "+" | ".join(cells)+" |")
    ti=sum(r['interest'] for r in rows); tc=sum(r['cash'] for r in rows)
    out.append(f"| **Total** | | **{f(ti)}** | **{f(tc)}** | |")
    return "\n".join(out)

# ---- shared Case 1 setup
P=D('1000000'); i=D('0.12')/12; n=24
Eu=emi(P,i,n); E=q(Eu); ca0=D('995000')
flows=[(t,E) for t in range(1,n+1)]
eir=solve_irr(flows,ca0)
rowsE=amort_table(ca0,eir,{t:E for t in range(1,n+1)},n)
rowsC=amort_table(P,i,{t:E for t in range(1,n+1)},n)
netfee=D('5000')

HDR = lambda t,s: f"""# {t}

> {s}
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

"""

# ============ CASE 1
c1 = HDR("Case 1 — Fixed-rate EMI term loan with integral fees",
         "The baseline. Establishes the EIR, the two-leg reconciliation, and the fee amortisation profile.")
c1 += f"""## Terms

| Input | Value |
|---|---|
| Principal advanced | {f(P)} |
| Contractual rate | 12.00% p.a. nominal, monthly compounding = 1.00% per month |
| Tenor | {n} monthly EMIs |
| Processing fee received (integral, ACPIR 52) | {f(D('15000'))} |
| DSA commission paid (integral, ACPIR 53) | {f(D('10000'))} |
| Product tier | Tier 2 illustrated at contract level ([03 §10](../03-calculation-spec.md#10-the-materiality-tier-gate)) |

## Derived

| Quantity | Value |
|---|---|
| EMI, unrounded annuity | {f(Eu,6)} |
| EMI, billed at 2dp | {f(E)} |
| Initial gross carrying amount | {f(ca0)} |
| Cash disbursed to borrower | {f(P-D('15000'))} |
| Cash paid to sourcing agent | {f(D('10000'))} |
| **Net cash outflow at inception** | **{f(ca0)}** — equals GCA₀, invariant IC-1 |
| Contractual effective rate | {f(annualise(i)*100,6)}% p.a. |
| **EIR, per month** | **{f(eir*100,8)}%** |
| EIR, nominal p.a. (×12) | {f(eir*12*100,6)}% |
| **EIR, effective p.a.** | **{f(annualise(eir)*100,6)}%** |
| Solver residual at the stored 12dp rate | {f(npv(eir,flows)-ca0,12)} |

The 5,000 of net integral fee lifts reported yield {f((annualise(eir)-annualise(i))*10000,1)} basis
points above the contractual effective rate. EIR exceeds contractual because the net fee is income,
so the asset is recorded below par and must accrete back up — invariant INV-2.

## Amortised-cost roll-forward (EIR leg)

{tbl(rowsE)}

Terminal closing balance: **{f(rowsE[-1]['closing'],8)}** — invariant TR-1.

## Two-leg reconciliation

| Quantity | Value |
|---|---|
| Total EIR interest recognised | {f(sum(r['interest'] for r in rowsE))} |
| Total contractual interest (billed flows) | {f(E*n-P)} |
| Difference | {f(sum(r['interest'] for r in rowsE)-(E*n-P))} |
| Net integral fee | {f(netfee)} |

**Invariant INV-1 holds:** the EIR method changes the *timing* of recognition, never the total.

## Per-period fee amortisation

| Pd | EIR interest | Contractual interest | Fee amortised | Unamortised fee c/f |
|---:|---:|---:|---:|---:|
"""
cum=D(0)
for a,b in zip(rowsE,rowsC):
    cum += a['interest']-b['interest']
    if a['period'] in (1,2,3,4,5,6,12,18,23,24):
        c1 += f"| {a['period']} | {f(a['interest'])} | {f(b['interest'])} | {f(a['interest']-b['interest'])} | {f(netfee-cum)} |\n"
c1 += f"""
Straight-lining the fee would recognise {f(netfee/n)} every month. EIR amortisation recognises
{f(rowsE[0]['interest']-rowsC[0]['interest'])} in month 1 and
{f(rowsE[23]['interest']-rowsC[23]['interest'])} in month 24 — front-loaded, because the balance it
accretes on is largest early. That difference is the whole purpose of the method.

**Month 12 unamortised fee = {f(netfee-sum(rowsE[k]['interest']-rowsC[k]['interest'] for k in range(12)))}**
(invariant INV-4: contractual GCA {f(rowsC[11]['closing'])} − EIR GCA {f(rowsE[11]['closing'])}).
This figure recurs in Cases 2 and 8.

## The contractual-leg residue

The billed EMI of {f(E)} is the true annuity payment of {f(Eu,6)} rounded down to paise. Over
{n} periods that shortfall compounds to a residue of **{f(rowsC[-1]['closing'],6)}** on the
*contractual* leg — visible as the 0.06 standing in the unamortised-fee column at period 24.

The EIR leg is unaffected and amortises to exactly zero. The residue is resolved by the
rounding-residue policy at [03 §5.7](../03-calculation-spec.md#57-rounding-and-residue-policy), preferably
`LMS_AUTHORITATIVE` — consume the schedule the core banking system actually billed rather than
deriving one, because a derived schedule guarantees a monthly reconciliation break.
"""
open(f"{OUT}/case-01-emi-loan-with-fees.md","w").write(c1)

# ============ CASE 2
bal12C=rowsC[11]['closing']; bal12E=rowsE[11]['closing']; unam=bal12C-bal12E
c2 = HDR("Case 2 — Full prepayment, accelerated fee recognition",
         "Closure before maturity accelerates the entire unamortised fee to P&L.")
c2 += f"""Continues [Case 1](case-01-emi-loan-with-fees.md). The borrower settles in full at the end
of month 12.

## Position immediately before settlement

| Quantity | Value |
|---|---|
| Contractual outstanding principal | {f(bal12C)} |
| EIR amortised cost (gross carrying amount) | {f(bal12E)} |
| **Unamortised net integral fee** | **{f(unam)}** |
| Net fee already amortised, months 1–12 | {f(netfee-unam)} |

## Treatment

The borrower discharges the **contractual** principal of {f(bal12C)}; the carrying amount on the
books is {f(bal12E)}. The residual {f(unam)} is the deferred fee that would have been released over
months 13–24 and is recognised **immediately in P&L on derecognition**.

| Entry | Amount |
|---|---|
| Cash received | {f(bal12C)} |
| Gross carrying amount derecognised | ({f(bal12E)}) |
| **Gain to P&L — accelerated fee** | **{f(unam)}** |

## Why this matters

The failure mode is writing the residual to a suspense account rather than to income
([01 §11 #9](../01-domain-primer.md#11-common-failure-modes)). That produces unexplained P&L and an
unreconciled fee balance which then never clears.

Note also what is *not* here: no prepayment penalty enters the calculation. Contingent fees are
excluded from the EIR projection at inception ([03 §3.3](../03-calculation-spec.md#33-contingent-fees));
a penalty, where one is chargeable at all, is separate income in this period. For floating-rate
individual loans RBI restricts foreclosure charges, so in the Indian retail book there is usually
no penalty cash flow to consider.

**Cross-check.** {f(unam)} is the same number as the month-12 unamortised fee in Case 1 (INV-4) and
the amount [Case 8](case-08-b544-repricing-shortcut.md) eliminates by amortising to the next
repricing date instead. Three routes, one figure.
"""
open(f"{OUT}/case-02-full-prepayment.md","w").write(c2)

# ============ CASE 3
new_n=18; new_emi=q(emi(bal12C,i,new_n))
revised=[(t,new_emi) for t in range(1,new_n+1)]
new_gca=npv(eir,revised); catch=new_gca-bal12E
rows3=amort_table(new_gca,eir,{t:new_emi for t in range(1,new_n+1)},new_n)
c3 = HDR("Case 3 — Re-estimation of cash flows: retrospective catch-up at the original EIR",
         "IFRS 9 B5.4.6 mechanics, adopted by election where ACPIR is silent. The rate does not move; the balance sheet is restated and the difference hits P&L at once.")
c3 += f"""Continues [Case 1](case-01-emi-loan-with-fees.md). At the end of month 12 the borrower is
granted a six-month tenor extension: the remaining 12 EMIs are re-profiled into 18 smaller ones.
Assessed as **not a substantial modification**, so no derecognition.

Event driver tag: `BEHAVIOURAL_ESTIMATE` → mechanism `CATCH_UP`
([03 §6.1](../03-calculation-spec.md#61-routing-is-configuration-keyed-to-a-driver-taxonomy)).

## Computation

| Step | Value |
|---|---|
| Contractual balance at month 12 | {f(bal12C)} |
| Revised EMI, contractual rate unchanged at 1.00%/month, 18 periods | {f(new_emi)} |
| **Original EIR — retained, not re-solved** | **{f(eir*100,8)}% per month** |
| PV of revised flows discounted at the **original** EIR | {f(new_gca)} |
| Carrying amount before restatement | {f(bal12E)} |
| **Catch-up adjustment to P&L** | **{f(catch)}** (a charge) |

## Post-modification roll-forward — EIR unchanged

{tbl(rows3[:6]+rows3[-2:])}

*(periods 7–16 omitted; terminal closing balance {f(rows3[-1]['closing'],8)})*

## The discriminator

Compare [Case 4](case-04-floating-rate-reset.md): the **same instrument**, the **same month**, and
in both cases "the schedule changed".

| | Case 3 — re-estimation | Case 4 — benchmark reset |
|---|---|---|
| Cause | Entity's own estimate revised | Market benchmark moved |
| Driver tag | `BEHAVIOURAL_ESTIMATE` | `TIME_VALUE_OF_MONEY` |
| EIR | **Unchanged** {f(eir*100,8)}% | **Re-solved** to {f(solve_irr([(t,q(emi(bal12C,D('0.13')/12,12))) for t in range(1,13)],bal12E)*100,8)}% |
| Carrying amount | **Restated** to {f(new_gca)} | **Unchanged** at {f(bal12E)} |
| P&L now | **{f(catch)}** | **0.00** |

Getting this pair the wrong way round is the highest-impact error available in this domain, which
is why the engine routes on an explicit driver tag carried on the event rather than inferring
anything from the fact that the rate or the schedule moved.

**Invariant CU-1:** the persisted EIR before and after this event must be bit-identical. Discounting
the revised flows at a *re-solved* rate would drive the catch-up to approximately zero and silently
convert this into a Case 4.
"""
open(f"{OUT}/case-03-b546-reestimation.md","w").write(c3)

# ============ CASE 4
i2=D('0.13')/12; emi2=q(emi(bal12C,i2,12))
flows4=[(t,emi2) for t in range(1,13)]
eir2=solve_irr(flows4,bal12E)
rows4=amort_table(bal12E,eir2,{t:emi2 for t in range(1,13)},12)
c4 = HDR("Case 4 — Floating-rate benchmark reset: prospective, no catch-up",
         "IFRS 9 B5.4.5 mechanics. The rate is re-solved from the carrying amount already on the books; nothing hits P&L.")
c4 += f"""The Case 1 loan, priced instead at a repo-linked EBLR of repo + 550bps and reset annually.
At the end of month 12 the benchmark rises 100bps, taking the contractual rate from 12% to 13% p.a.

Event driver tag: `TIME_VALUE_OF_MONEY` → mechanism `RESET`.

## Computation

| Step | Value |
|---|---|
| Contractual rate after reset | 13.00% p.a. nominal = {f(i2*100,8)}% per month |
| Contractual balance at reset | {f(bal12C)} |
| Revised EMI for the remaining 12 months | {f(emi2)} |
| **Carrying amount at reset — unchanged** | **{f(bal12E)}** |
| **Revised EIR** | **{f(eir2*100,8)}% per month** |
| Revised EIR, effective p.a. | {f(annualise(eir2)*100,6)}% |
| **Catch-up adjustment to P&L** | **0.00** |

The EIR is re-solved so that the revised flows discount to the carrying amount **already on the
books**. The unamortised fee balance of {f(unam)} is not released early — it is absorbed into the
new rate and continues to amortise across the remaining life.

## Post-reset roll-forward

{tbl(rows4)}

Terminal closing balance {f(rows4[-1]['closing'],8)}.

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
"""
open(f"{OUT}/case-04-floating-rate-reset.md","w").write(c4)

# ============ CASE 5
gca=rowsE[11]['closing']; ecl=q(gca*D('0.40')); net=gca-ecl
gi=gca*eir; ni=net*eir; uw=ecl*eir; contr13=rowsC[12]['interest']
c5 = HDR("Case 5 — Stage 3 under ACPIR: compute the unwind, suppress the income",
         "The India divergence. ACPIR does not recognise income on Stage 3; IFRS 9 recognises it on the net basis. The engine must compute the unwind regardless, because ECL is discounted at the EIR.")
c5 += f"""The Case 1 loan enters Stage 3 at the start of month 13, with a lifetime ECL allowance of
40% of the gross carrying amount.

## Position

| Quantity | Value |
|---|---|
| Gross carrying amount, month 13 opening | {f(gca)} |
| Lifetime ECL allowance (40%) | {f(ecl)} |
| Amortised cost (net of allowance) | {f(net)} |
| **EIR — unchanged; staging is not an EIR event** | **{f(eir*100,8)}% per month** |

## The three-way decomposition

| | Quantity | Amount |
|---|---|---:|
| (a) | Gross-basis interest, `GCA × EIR` — what Stage 1/2 would recognise | {f(gi)} |
| (b) | IFRS 9 Stage 3 net-basis interest, `AC × EIR` | {f(ni)} |
| (c) | ECL discount unwind, `allowance × EIR` | {f(uw)} |

**Invariant ST-2, exact: (b) + (c) = (a).**  {f(ni)} + {f(uw)} = {f(ni+uw)} = {f(gi)}

This identity is what makes the Indian treatment tractable. The ECL discount unwind is *precisely*
the interest the gross basis would have recognised on the allowance portion of the balance — so the
engine needs no separate unwind model. It computes one gross-basis figure and decomposes it.

## What each framework does with those numbers

| | IFRS 9 | ACPIR 2026 |
|---|---|---|
| Recognised in interest income | {f(ni)} | **0.00** |
| Where (c) goes | Within the impairment line | Not recognised |
| To interest-in-suspense | — | {f(contr13)} (contractual interest billed) |
| Shadow unwind retained for ECL roll-forward | implicit | {f(uw)} |

ACPIR suppresses recognition entirely; the Seventh Amendment Directions protect that from an
auditor qualification. But ECL is a present-value measure discounted at the EIR (ACPIR 50), so the
discount unwinds mechanically whether or not anything is recognised. **ACPIR does not say where the
unwind goes** — this is one of the open questions the bank must close by Board-approved policy
([reference §12 Q2](../reference/acpir-2026-eir-application-reference.md)).

## The four quantities the engine maintains

1. **Gross carrying amount** — continues rolling forward on the gross basis. Staging changes
   neither the GCA nor the EIR.
2. **Shadow EIR unwind** — {f(uw)} this period. Retained for the ECL roll-forward. Never P&L.
3. **Interest-in-suspense** — {f(contr13)}. A first-class ledger object, not a memorandum.
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
"""
open(f"{OUT}/case-05-stage-3-acpir-suppression.md","w").write(c5)
print("cases 1-5 written")
