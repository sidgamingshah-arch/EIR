"""Golden numerics for the cash-flow structure build-out."""
from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import q, emi, solve_irr, npv, annualise, pow_d, amort_table

def hdr(t):
    print("\n" + "="*78); print(t); print("="*78)

def show(rows, limit=None, label="roll-forward"):
    print(f"  {'Pd':>3} {'Opening':>14} {'Interest':>12} {'Cash':>12} {'Closing':>14}")
    sel = rows if limit is None else rows[:limit]
    for r in sel:
        print(f"  {r['period']:>3} {q(r['opening']):>14} {q(r['interest']):>12} "
              f"{q(r['cash']):>12} {q(r['closing']):>14}")

P = D('1000000'); im = D('0.12')/12; NETFEE = D('5000'); CA0 = P - NETFEE

# ---------------------------------------------------------------- 1. EQUAL PRINCIPAL
hdr("S1  EQUAL PRINCIPAL (EPI) - straight-line principal, declining instalment")
n = 24
prin = q(P/n)
flows = {}
bal = P; sched = []
for t in range(1, n+1):
    intr = q(bal*im)
    p = prin if t < n else bal          # last period absorbs the rounding residue
    sched.append((t, p, intr, p+intr))
    flows[t] = p + intr
    bal -= p
print(f"  Principal per period      : {q(prin)}")
print(f"  Instalment period 1       : {q(sched[0][3])}  (principal {q(sched[0][1])} + interest {q(sched[0][2])})")
print(f"  Instalment period 24      : {q(sched[-1][3])}")
print(f"  Total interest            : {q(sum(s[2] for s in sched))}")
eir_epi = solve_irr([(t, flows[t]) for t in range(1, n+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_epi*100,8)}% /mo, {q(annualise(eir_epi)*100,6)}% p.a.")
print(f"  (contractual effective    : {q(annualise(im)*100,6)}% p.a.)")
r = amort_table(CA0, eir_epi, flows, n); show(r, 3)
print(f"  terminal                  : {q(r[-1]['closing'],8)}")

# ---------------------------------------------------------------- 2. BALLOON
hdr("S2  BALLOON - level instalments sized against a terminal lump")
balloon = D('400000')
# instalment amortises (P - PV(balloon)) as an annuity
pv_balloon = balloon / pow_d(D(1)+im, n)
inst = q(emi(P - pv_balloon, im, n))
flows = {t: inst for t in range(1, n+1)}
flows[n] = flows[n] + balloon
print(f"  Balloon at maturity       : {q(balloon)}")
print(f"  PV of balloon             : {q(pv_balloon)}")
print(f"  Level instalment          : {q(inst)}")
print(f"  Final period total        : {q(flows[n])}")
eir_bal = solve_irr([(t, flows[t]) for t in range(1, n+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_bal*100,8)}% /mo, {q(annualise(eir_bal)*100,6)}% p.a.")
r = amort_table(CA0, eir_bal, flows, n); show(r, 2)
print(f"  terminal                  : {q(r[-1]['closing'],8)}")

# ---------------------------------------------------------------- 3. STEP-UP
hdr("S3  STEP-UP - instalment rises 10% every 6 periods (contractual ladder)")
step = D('1.10'); every = 6
def factor(t):  return pow_d(step, (t-1)//every)
# solve base instalment B such that sum B*factor(t)/(1+i)^t = P
denom = sum(factor(t)/pow_d(D(1)+im, t) for t in range(1, n+1))
base = q(P/denom)
flows = {t: q(base*factor(t)) for t in range(1, n+1)}
print(f"  Base instalment (pd 1-6)  : {q(base)}")
for blk in range(4):
    t = blk*every+1
    print(f"  Periods {t:>2}-{t+every-1:<2}            : {q(flows[t])}  (factor {q(factor(t),4)})")
print(f"  Total cash                : {q(sum(flows.values()))}")
eir_step = solve_irr([(t, flows[t]) for t in range(1, n+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_step*100,8)}% /mo, {q(annualise(eir_step)*100,6)}% p.a.")
r = amort_table(CA0, eir_step, flows, n); show(r, 2)
print(f"  terminal                  : {q(r[-1]['closing'],8)}  (residue from instalment rounding)")

# ---------------------------------------------------------------- 4. PRINCIPAL MORATORIUM
hdr("S4  PRINCIPAL MORATORIUM - 6 periods interest-serviced, then 18 EMIs")
mor = 6; rem = n - mor
svc = q(P*im)
emi_after = q(emi(P, im, rem))
flows = {t: svc for t in range(1, mor+1)}
for t in range(mor+1, n+1): flows[t] = emi_after
print(f"  Interest serviced pd 1-6  : {q(svc)}  (principal untouched)")
print(f"  EMI periods 7-24          : {q(emi_after)}")
eir_pm = solve_irr([(t, flows[t]) for t in range(1, n+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_pm*100,8)}% /mo, {q(annualise(eir_pm)*100,6)}% p.a.")
r = amort_table(CA0, eir_pm, flows, n); show(r, 2)
print(f"  closing at pd 6           : {q(r[5]['closing'])}")
print(f"  terminal                  : {q(r[-1]['closing'],8)}")

# ------------------------------------------- 5. FULL MORATORIUM, INTEREST CAPITALISED
hdr("S5  FULL MORATORIUM, INTEREST CAPITALISED - education loan / project IDC")
mor = 12; after = 24
grossed = P * pow_d(D(1)+im, mor)
emi_cap = q(emi(grossed, im, after))
flows = {t: D(0) for t in range(1, mor+1)}
for t in range(mor+1, mor+after+1): flows[t] = emi_cap
print(f"  Disbursed                 : {q(P)}")
print(f"  Moratorium periods        : {mor}  (nothing paid; interest compounds into GCA)")
print(f"  Balance at moratorium end : {q(grossed)}   <- capitalised interest {q(grossed-P)}")
print(f"  EMI for {after} periods after   : {q(emi_cap)}")
tot = mor + after
eir_cap = solve_irr([(t, flows[t]) for t in range(1, tot+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_cap*100,8)}% /mo, {q(annualise(eir_cap)*100,6)}% p.a.")
r = amort_table(CA0, eir_cap, flows, tot); show(r, 2)
print(f"  closing at pd 12          : {q(r[11]['closing'])}   (accreted, no cash)")
print(f"  terminal                  : {q(r[-1]['closing'],8)}")
print("  NOTE ACPIR 9(6)(i): interest becomes due only after the moratorium, so it is")
print("  not overdue in the interim. Capitalisation is an EIR input; the non-overdue")
print("  status is a staging input. One contractual feature, two separate consequences.")

# ------------------------------------- 6. FULL MORATORIUM, DEFERRED SIMPLE (not compounded)
hdr("S6  FULL MORATORIUM, INTEREST DEFERRED SIMPLE - accrued, paid as a lump")
simple = q(P*im*mor)
flows = {t: D(0) for t in range(1, mor+1)}
flows[mor] = simple                       # lump at moratorium end
emi_simple = q(emi(P, im, after))
for t in range(mor+1, mor+after+1): flows[t] = emi_simple
print(f"  Simple interest accrued   : {q(simple)}  ({mor} x {q(P*im)})")
print(f"  vs compounded equivalent  : {q(grossed-P)}   -> difference {q((grossed-P)-simple)}")
print(f"  Lump settled at pd 12     : {q(simple)}")
print(f"  EMI for {after} periods after   : {q(emi_simple)}")
eir_sim = solve_irr([(t, flows[t]) for t in range(1, tot+1)], CA0)
print(f"  EIR with 5,000 net fee    : {q(eir_sim*100,8)}% /mo, {q(annualise(eir_sim)*100,6)}% p.a.")
print(f"  -> simple deferral yields {q((annualise(eir_cap)-annualise(eir_sim))*10000,1)} bp LESS than capitalising")
