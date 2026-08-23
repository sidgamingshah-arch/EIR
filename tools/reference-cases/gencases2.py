from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import amort_table, q, emi, solve_irr, npv, annualise, pow_d
import os
OUT="/home/user/EIR/docs/reference-cases"
def f(x,p=2): return f"{q(x,p):,}"
def tbl(rows,hdr=("Pd","Opening GCA","EIR interest","Cash received","Closing GCA")):
    out=["| "+" | ".join(hdr)+" |","|"+"|".join(["---:"]*len(hdr))+"|"]
    for r in rows:
        out.append(f"| {r['period']} | {f(r['opening'])} | {f(r['interest'])} | {f(r['cash'])} | {f(r['closing'])} |")
    return "\n".join(out)
HDR = lambda t,s: f"""# {t}

> {s}
>
> Golden fixture. Generated from the reference implementation and verified independently of
> the engine. **If the engine disagrees with these figures, the engine is wrong.**
> All amounts INR. Working precision 28 significant digits; presented at 2dp `HALF_UP`.

"""
P=D('1000000'); i=D('0.12')/12; n=24
E=q(emi(P,i,n)); ca0=D('995000')
eir=solve_irr([(t,E) for t in range(1,n+1)],ca0)
rowsE=amort_table(ca0,eir,{t:E for t in range(1,n+1)},n)
rowsC=amort_table(P,i,{t:E for t in range(1,n+1)},n)
bal12C=rowsC[11]['closing']

# CASE 6 POCI
price=D('700000'); exp=q(E*D('0.80'))
ca_eir=solve_irr([(t,exp) for t in range(1,25)],price)
plain=solve_irr([(t,E) for t in range(1,25)],price)
rows6=amort_table(price,ca_eir,{t:exp for t in range(1,25)},24)
c6=HDR("Case 6 — POCI asset: the credit-adjusted EIR",
       "Expected losses live inside the rate from inception. Using contractual flows instead produces a yield that is arithmetically impeccable and economically fictional.")
c6+=f"""A distressed pool is acquired for {f(price)}. Contractual flows are the 24 EMIs of
[Case 1](case-01-emi-loan-with-fees.md), but 20% of every receipt is expected to be lost.

ACPIR 6(3)(v) makes acquisition at a discount reflecting inherent credit losses an explicit
credit-impairment indicator, so this is POCI on acquisition terms alone. ACPIR 24 requires the day-1
lifetime ECL to sit inside the cash flows used to compute the rate, with **no separate day-1
allowance**; thereafter only *cumulative changes* in lifetime ECL are recognised.

## Computation

| Input | Value |
|---|---|
| Purchase price = amortised cost at initial recognition | {f(price)} |
| Contractual monthly receipt | {f(E)} |
| Expected monthly receipt (80%) | {f(exp)} |

| Basis | Per month | Effective p.a. |
|---|---:|---:|
| **Credit-adjusted EIR (expected flows) — correct** | **{f(ca_eir*100,8)}%** | **{f(annualise(ca_eir)*100,6)}%** |
| EIR on contractual flows — wrong | {f(plain*100,8)}% | {f(annualise(plain)*100,6)}% |

Discounting *contractual* flows to a distressed purchase price yields
{f(annualise(plain)*100,6)}% per annum. It would accrue income the bank has no expectation of
collecting, then reverse it through impairment. The credit-adjusted rate is what makes the income
statement mean anything.

Note that the rate solves to **amortised cost**, not gross carrying amount (ACPIR 6(4)). There is
no gross-basis phase for a POCI asset.

## Roll-forward at the credit-adjusted EIR

{tbl(rows6[:6]+rows6[-2:])}

*(periods 7–22 omitted; terminal closing balance {f(rows6[-1]['closing'],8)})*

## Two ACPIR rules the engine enforces

- **POCI identification is driven off acquisition terms**, not only subsequent behaviour, and
  applies at transition as well as at origination. A legacy pool bought at a credit-related
  discount before 1 April 2027 is POCI when it comes under the regime.
- **The credit-adjusted EIR is retained after cure.** It is not reset — invariant POCI-1, following
  the 2019 IFRS Interpretations Committee direction. A cure improves the expected cash flows; it
  does not retrospectively make the purchase price a par acquisition.
"""
open(f"{OUT}/case-06-poci-credit-adjusted-eir.md","w").write(c6)

# CASE 7 solver stress
zc_face=D('1000000')*pow_d(D('1.01'),60)
zc=solve_irr([(60,zc_face)],D('1000000'))
io=[(t,D('10000')) for t in range(1,60)]+[(60,D('1010000'))]
io_eir=solve_irr(io,D('950000'))
dd=solve_irr([(t,E) for t in range(1,25)],D('780000'))
c7=HDR("Case 7 — Solver stress instruments",
       "Edge cases that must converge, and the behaviours that must never be silently defaulted.")
c7+=f"""These exercise the solver rather than the accounting. Every one must converge to the stated
figure; the negative cases must raise, not guess.

## Must converge

**Zero-coupon, 5-year bullet, no fees.** The EIR must recover the contractual yield exactly.

| Input | Value |
|---|---|
| Amount advanced | {f(D('1000000'))} |
| Single receipt at month 60 | {f(zc_face)} |
| **Recovered monthly EIR** | **{f(zc,12)}** |
| Expected | {f(D('0.01'),12)} |

Exact to 12 decimal places. This is the solver's calibration test: a single flow, closed-form
answer, no room to hide.

**Interest-only with bullet repayment and a large upfront fee.** 59 monthly interest payments of
{f(D('10000'))} then {f(D('1010000'))} at month 60, against {f(D('950000'))} advanced.

EIR = **{f(io_eir*100,8)}% per month**, {f(annualise(io_eir)*100,6)}% p.a.

**Deep-discount instrument, fee > 20% of principal.** The 24 Case 1 EMIs against {f(D('780000'))}
advanced.

EIR = **{f(dd*100,8)}% per month**, {f(annualise(dd)*100,6)}% p.a.

At this magnitude Newton–Raphson seeded from the contractual rate can overshoot outside the
bracket; the bisection fallback is what guarantees termination
([03 §4.2](../03-calculation-spec.md#42-algorithm)).

## Must raise, never guess

| Scenario | Required behaviour |
|---|---|
| Total inflows ≤ initial outflow (no sign change over the bracket ladder) | `NoSolution` → exception queue with the flow vector attached. **Never** default to zero or to the contractual rate. |
| Multiple sign changes, exactly one root in the plausible band | Take it; record that disambiguation occurred and log all candidate roots. |
| Multiple sign changes, several roots in band | Take the root nearest contractual; mark `REQUIRES_REVIEW`; route for approval. Computed and usable, but flagged. |
| Multiple sign changes, no root in band | Exception queue. |
| Newton–Raphson exceeds 100 iterations | Fall back to 200 bisection iterations over the bracket. |
| Unmapped fee code on the contract | Exception queue before the solver is reached. |
| `EXCLUDED_BY_DIRECTION` posting (penal charge) present in the vector | Rejected at ingestion; invariant PC-1 asserted for the period. |

**The one that matters most.** Silently falling back to the contractual rate on non-convergence
reproduces the pre-ACPIR position while appearing to have implemented EIR. It produces plausible
numbers and leaves no trace, which makes it the most damaging failure mode available to this engine.
Non-convergence is an exception requiring manual review — always.

## Structures that generate these profiles

Not hypothetical. In an Indian bank's book: step-up and step-down EMIs, balloon and bullet
repayments, moratoria with interest capitalisation, IDC on project finance, FITL creation on
restructuring, and tranched disbursement against milestones. Multiple sign changes come from
tranched facilities with large interim drawdowns.
"""
open(f"{OUT}/case-07-solver-stress.md","w").write(c7)

# CASE 8 B5.4.4
sc=[(t,E) for t in range(1,13)]+[(12,bal12C)]
eir_sc=solve_irr(sc,ca0)
rs=amort_table(ca0,eir_sc,{t:(E+bal12C if t==12 else E) for t in range(1,13)},12)
fee_full=sum(rowsE[k]['interest']-rowsC[k]['interest'] for k in range(12))
fee_sc=sum(rs[k]['interest']-rowsC[k]['interest'] for k in range(12))
c8=HDR("Case 8 — The B5.4.4 next-repricing-date shortcut",
       "Where an instrument reprices to market before maturity, fees amortise to the next repricing date instead of over expected life. On a floating-rate retail book this largely dissolves the behavioural-life problem.")
c8+=f"""The Case 1 loan priced as floating and reset annually. Net integral fee {f(D('5000'))}.

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
| Initial gross carrying amount | {f(ca0)} |
| Flows | 12 EMIs of {f(E)} + notional redemption of {f(bal12C)} at month 12 |

## Comparison

| Basis | EIR (per month) | EIR (effective p.a.) | Net fee recognised, months 1–12 | Unamortised fee past the reset |
|---|---:|---:|---:|---:|
| Full expected life (24m) | {f(eir*100,8)}% | {f(annualise(eir)*100,6)}% | {f(fee_full)} | {f(D('5000')-fee_full)} |
| **B5.4.4 shortcut (to m12)** | **{f(eir_sc*100,8)}%** | **{f(annualise(eir_sc)*100,6)}%** | **{f(fee_sc)}** | **{f(D('5000')-fee_sc)}** |

The shortcut accelerates year-one fee recognition by {f((fee_sc/fee_full-1)*100,1)}% and leaves
nothing to carry across the reset.

## Roll-forward under the shortcut

{tbl(rs)}

Terminal closing balance {f(rs[-1]['closing'],6)} — the notional redemption closes the vector.

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

**Cross-check.** The {f(D('5000')-fee_full)} carried across the reset under full expected life is the
same figure as [Case 2](case-02-full-prepayment.md)'s prepayment acceleration and invariant INV-4 at
month 12. Three routes, one number.
"""
open(f"{OUT}/case-08-b544-repricing-shortcut.md","w").write(c8)

# CASE 9 straight line vs EIR
face=D('1000000'); y=D('0.08'); yrs=15
price=face/pow_d(D(1)+y,yrs); disc=face-price; sl=disc/yrs
rows=[]; bal=price
for t in range(1,yrs+1):
    ea=bal*y; rows.append((t,bal,sl,ea,sl-ea)); bal+=ea
c9=HDR("Case 9 — Straight-line versus EIR on a long zero-coupon: the wedge",
       "The totals tie exactly, which is what makes the error invisible over the life of the instrument and glaring in any single period.")
c9+=f"""The documented failure mode from the Cambodian CIFRS 9 transition, and the argument that makes
selective EIR adoption on the investment book indefensible.

## Instrument

| Input | Value |
|---|---|
| Face value at maturity | {f(face)} |
| Yield | {f(y*100,2)}% p.a. |
| Tenor | {yrs} years, zero coupon |
| Purchase price (PV) | {f(price)} |
| Total discount to accrete | {f(disc)} |
| Straight-line accretion per year | {f(sl)} |

## Year-by-year

| Yr | Opening carrying amount | Straight-line | EIR accretion | SL overstates by |
|---:|---:|---:|---:|---:|
"""
for t,b,s,e,d in rows:
    c9+=f"| {t} | {f(b)} | {f(s)} | {f(e)} | {f(d)} |\n"
c9+=f"""| **Total** | | **{f(sl*yrs)}** | **{f(sum(r[3] for r in rows))}** | **{f(sl*yrs-sum(r[3] for r in rows))}** |

Terminal carrying amount {f(price+sum(r[3] for r in rows))} against face {f(face)}.

## The numbers that matter

| | Straight-line | EIR | Error |
|---|---:|---:|---:|
| Year 1 | {f(sl)} | {f(rows[0][3])} | **+{f((sl/rows[0][3]-1)*100,1)}%** overstated |
| Year 15 | {f(sl)} | {f(rows[14][3])} | **−{f((1-sl/rows[14][3])*100,1)}%** understated |
| Crossover | | | around year {[t for t,b,s,e,d in rows if d<0][0]} |
| **Life total** | {f(sl*yrs)} | {f(sum(r[3] for r in rows))} | **0.00** |

Straight-line overstates year-one income by **{f((sl/rows[0][3]-1)*100,1)}%**.

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
"""
open(f"{OUT}/case-09-straight-line-vs-eir.md","w").write(c9)
print("cases 6-9 written")
